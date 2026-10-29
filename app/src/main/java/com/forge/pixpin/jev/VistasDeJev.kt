package com.forge.pixpin.jev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Ajustes › Asistente › «TypeSafe (Jev)»** (28-oct-2026, la sección `[jev]` del PC, `24fdfa97`): la
 * clave de TypeSafe (cifrada con el Keystore, enseñada enmascarada, que no viaja) y un interruptor por
 * función, con lo que se manda en cada una. Sin clave, los interruptores no hacen nada y todo va como
 * siempre.
 */
@Composable
fun AjustesDeJev() {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val version by Jev.version.collectAsState()
    var guardada by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        guardada = withContext(Dispatchers.IO) { Jev.clave(contexto)?.let { com.forge.pixpin.ia.Nube.enmascarar(it) } }
    }
    var nueva by remember { mutableStateOf("") }
    var aviso by remember { mutableStateOf<String?>(null) }
    Text("TypeSafe (Jev)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
    Text(
        (guardada?.let { "Clave $it, cifrada en este teléfono. " } ?: "Sin clave: todo va como siempre. Sácala en console.typesafe.ai. ") +
            "Jev contesta preguntas cortas con probabilidades; las decisiones las toma PixPin. Se paga en tu cuenta (muy poco) y solo se manda lo que dice cada función.",
        fontSize = 13.sp
    )
    OutlinedTextField(
        value = nueva, onValueChange = { nueva = it.trim() }, singleLine = true,
        label = { Text("Pega la clave de TypeSafe") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            val clave = nueva
            if (!com.forge.pixpin.ia.Nube.pareceClave(clave)) { aviso = "Eso no parece una clave."; return@TextButton }
            alcance.launch {
                aviso = "Probando…"
                aviso = withContext(Dispatchers.IO) {
                    runCatching { Jev.guardar(contexto, clave) }.fold({ "Guardada. " + Jev.probar(contexto, clave) }, { "No se pudo guardar." })
                }
                nueva = ""
            }
        }) { Text("Guardar y probar") }
        if (guardada != null) TextButton(onClick = {
            alcance.launch {
                aviso = "Probando…"
                aviso = withContext(Dispatchers.IO) { Jev.probar(contexto, "") }
            }
        }) { Text("Probar") }
        if (guardada != null) TextButton(onClick = {
            alcance.launch { withContext(Dispatchers.IO) { Jev.borrar(contexto) }; aviso = "Clave borrada." }
        }) { Text("Borrar") }
        TextButton(onClick = {
            runCatching {
                contexto.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Jev.PAGINA_DE_CLAVES))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) { Text("Sacar una") }
    }
    aviso?.let { Text(it, fontSize = 13.sp) }
    for ((f, titulo, ayuda) in FUNCIONES) {
        val si = remember(version) { Jev.encendida(contexto, f) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(titulo, fontSize = 13.sp)
                Text(ayuda, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = si, onCheckedChange = { Jev.encender(contexto, f, it) })
        }
    }
}

/** Cada función, su nombre y lo que hace y manda (los textos del PC, `ajustes2-jev-*`). */
private val FUNCIONES = listOf(
    Triple(
        Jev.Funcion.REUNION, "Lo importante de clases y reuniones",
        "Al pasar una grabación a texto marca tareas, decisiones, preguntas y lo que entra en el examen. Se ven en «Estudiar la clase» y en la página compartida. Se manda cada renglón con sus vecinos y el título."
    ),
    Triple(
        Jev.Funcion.ANKI, "Solo las tarjetas de Anki que valen la pena",
        "Al enviar una clase a Anki deja las capturas cuyo tramo explica algo que estudiar y quita la charla. Se manda lo dicho en cada tramo."
    ),
    Triple(
        Jev.Funcion.URGENCIA, "Prioridad de las tareas según el contexto",
        "Urgente, alta, normal o baja según lo que pasa si se hace tarde, quién la espera, su hora y lo último del chat. Lo que marques tú (⏫, 🔼) manda. Se manda el texto de la tarea, su lista y los últimos mensajes de su chat."
    ),
    Triple(
        Jev.Funcion.MODELO, "Elegir el modelo según el pedido",
        "Lo fácil va al modelo rápido (Haiku, DeepSeek Flash) y lo que pide razonar, al fuerte (Sonnet, DeepSeek V4 Pro). Se manda el pedido y los tres últimos mensajes."
    ),
    // **La reunión** (29-oct-2026, `ajustes2-jev-temas` y los siete de «En la reunión» del PC): lo que se
    // oye mientras se graba, pasado a texto en este teléfono frase a frase.
    Triple(
        Jev.Funcion.TEMAS, "Temas de la reunión tratados",
        "Mientras grabas una reunión con temas, lo que se oye se pasa a texto en este teléfono frase a frase y Jev marca los temas que ya se trataron. Lo que marques tú manda. Se manda el texto de los últimos 40 s, los temas y el título."
    ),
    Triple(
        Jev.Funcion.TAREAS_DICHAS, "Tareas dichas en la reunión",
        "Mientras grabas una reunión, «yo me encargo de mandar la cotización el viernes» sale en la barra como tarea propuesta, con su fecha y su urgencia. Solo se apunta en Tareas si la aceptas. Se manda cada frase con las dos de antes y el título."
    ),
    Triple(
        Jev.Funcion.URGENCIA_EN_VIVO, "Urgencia de las tareas dicha en la reunión",
        "«Esto es para hoy» sube la prioridad o cambia la fecha de una tarea pendiente de este chat o del Inbox. Se aplica sola y se puede deshacer en la barra. Se manda cada frase y el texto de hasta 8 tareas."
    ),
    Triple(
        Jev.Funcion.TEMA_OLVIDADO, "Avisar del tema olvidado",
        "Con «Duración prevista» en Preparar, avisa cerca del final (o si se están despidiendo) de los temas de la agenda que aún no se trataron. Se manda cada frase y los temas."
    ),
    Triple(
        Jev.Funcion.TIEMPO_POR_TEMA, "Minutos de cada tema",
        "Cuánto se habló de cada tema de la agenda, en «Estudiar la clase» y en la página compartida. Se manda cada frase y los temas."
    ),
    Triple(
        Jev.Funcion.DECISIONES, "Decisiones de la reunión",
        "«Queda decidido: adelantamos el pedido» se apunta con su minuto (no «podríamos…»). Se ve en «Estudiar la clase» y en la página compartida. Se manda cada frase."
    ),
    Triple(
        Jev.Funcion.PREGUNTAS_PENDIENTES, "Preguntas sin respuesta",
        "Las preguntas que nadie contestó en la reunión, al final en «Estudiar la clase», para pasarlas a Tareas. Se manda cada frase y las preguntas aún abiertas."
    ),
    Triple(
        Jev.Funcion.FECHAS_AL_HORARIO, "Fechas dichas, al horario",
        "«Nos vemos el martes a las 3» sale en la barra como evento propuesto; si lo aceptas, va a Tareas con su hora y se ve en el Horario. Se manda cada frase."
    ),
    // Las dos del 29-oct-2026 (`ajustes2-jev-dictado` y `ajustes2-jev-repaso` del PC, con el gesto del teléfono).
    Triple(
        Jev.Funcion.DICTADO, "Dictar al chat",
        "Al dictar en la caja del chat (el botón 🗣 de la caja), «anota en el chat de la obra que faltan tornillos» propone anotarlo en ese chat: tocar la propuesta lo anota, la ✕ la quita. Se manda cada frase dictada, la anterior y los nombres de tus chats recientes."
    ),
    Triple(
        Jev.Funcion.REPASO, "Repasar en voz alta",
        "En «Estudiar la clase», explica la clase de viva voz y Jev marca qué puntos clave cubres, cuáles dices mal y cuáles faltan. Se manda lo que explicas, el título y los puntos clave."
    )
)

/**
 * **La pastilla del modelo** encima de la caja del chat «Asistente» (28-oct-2026, la del PC): lo que eligió
 * Jev para el último pedido («Claude Sonnet · Jev») durante 15 minutos. Sin Jev no sale nada.
 */
@Composable
fun PastillaDelModelo(chat: String?, modifier: Modifier = Modifier) {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rotulo = ModeloEnLaApp.rotulo(chat, ahora)
    val cuando = ModeloEnLaApp.cuandoSeEligio(chat)
    // Se esconde sola al pasar los 15 minutos.
    LaunchedEffect(cuando) {
        ahora = System.currentTimeMillis()
        val c = cuando ?: return@LaunchedEffect
        val falta = c + ModeloEnLaApp.EN_LA_PASTILLA_MS - ahora
        if (falta > 0) { kotlinx.coroutines.delay(falta + 50); ahora = System.currentTimeMillis() }
    }
    if (rotulo != null) Surface(
        shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier
    ) {
        Text("✦ $rotulo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
