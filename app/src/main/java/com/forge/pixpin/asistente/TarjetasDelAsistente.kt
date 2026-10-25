package com.forge.pixpin.asistente

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ia.Nube
import kotlinx.coroutines.launch

/**
 * **La tarjeta de la primera vez**, encima de la caja del chat «Asistente» (§4.9 del manual): si
 * falta la clave de la nube, lo dice y a dónde ir a pegarla. Se va sola cuando hay clave.
 *
 * Antes era la de bajar el modelo del teléfono; desde el 25-oct-2026 el asistente solo contesta
 * con la nube y lo único que puede faltar es la clave.
 */
@Composable
fun TarjetaDelAsistente(modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp ?: return
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    val proveedor = Nube.Proveedor.elegido(ajustes.asistenteMotor)
    // La clave se lee en el hilo de disco; mientras tanto no se enseña nada (null = no se sabe).
    var hayClave by remember(proveedor) { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(proveedor) {
        hayClave = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClavesDeLaNube.leer(contexto, proveedor) != null }
    }
    if (hayClave != false) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🤖 Asistente", style = MaterialTheme.typography.titleSmall)
            Text(
                "Contesta con ${proveedor.nombre} y tu clave: falta pegarla en Ajustes → Asistente. " +
                    "Las órdenes simples («abre la galería», «apunta comprar pan mañana») van sin clave.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * **Ajustes → Asistente**: quién contesta (Claude o DeepSeek), su clave y cómo contesta. Es de
 * cada aparato y no viaja (§1.4).
 */
@Composable
fun AsistenteCard() {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp ?: return
    val ajustes by app.settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QuienContesta(ajustes)
        }
    }
}

/** Una fila de opciones en forma de pastillas: la elegida, rellena. */
@Composable
private fun Opciones(titulo: String, opciones: List<Pair<String, String>>, actual: String, alElegir: (String) -> Unit) {
    Text(titulo, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((valor, rotulo) in opciones) {
            if (valor == actual) Button(onClick = {}) { Text(rotulo, fontSize = 13.sp) }
            else OutlinedButton(onClick = { alElegir(valor) }) { Text(rotulo, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Interruptor(texto: String, si: Boolean, alCambiar: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(texto, fontSize = 13.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = si, onCheckedChange = alCambiar)
    }
}

/**
 * **Quién contesta y las claves de la nube** (§1.4 y §1.5 del manual del PC): Claude o
 * DeepSeek. La clave se pega aquí, se guarda cifrada con el Keystore
 * ([ClavesDeLaNube]) y solo se enseña enmascarada; no viaja a ningún otro aparato.
 */
@Composable
private fun QuienContesta(ajustes: com.forge.pixpin.data.Settings) {
    val contexto = LocalContext.current
    val app = contexto.applicationContext as? PixPinApp ?: return
    val alcance = rememberCoroutineScope()
    Text("Quién contesta", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    val proveedor = Nube.Proveedor.elegido(ajustes.asistenteMotor)
    Opciones("Motor", Nube.Proveedor.entries.map { it.id to it.nombre.substringBefore(" ") }, proveedor.id) { v ->
        alcance.launch { app.settings.setAsistenteMotor(v) }
    }
    var version by remember { mutableStateOf(0) }
    var guardada by remember(proveedor, version) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(proveedor, version) {
        guardada = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClavesDeLaNube.leer(contexto, proveedor)?.let { com.forge.pixpin.ia.Nube.enmascarar(it) } }
    }
    var nueva by remember(proveedor) { mutableStateOf("") }
    var aviso by remember(proveedor) { mutableStateOf<String?>(null) }
    Text(
        "${proveedor.nombre}: " + (guardada?.let { "clave $it, cifrada en este teléfono" } ?: "sin clave") +
            ". Se paga en tu cuenta y manda lo que preguntas por internet.",
        fontSize = 13.sp
    )
    androidx.compose.material3.OutlinedTextField(
        value = nueva, onValueChange = { nueva = it.trim() }, singleLine = true,
        label = { Text("Pega la clave") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = {
            val clave = nueva
            if (!com.forge.pixpin.ia.Nube.pareceClave(clave)) { aviso = "Eso no parece una clave."; return@TextButton }
            alcance.launch {
                aviso = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ClavesDeLaNube.guardar(contexto, proveedor, clave) }.fold({ "Guardada." }, { "No se pudo guardar." })
                }
                nueva = ""; version++
            }
        }) { Text("Guardar") }
        TextButton(onClick = {
            alcance.launch {
                aviso = "Probando…"
                aviso = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val clave = nueva.ifBlank { ClavesDeLaNube.leer(contexto, proveedor).orEmpty() }
                    com.forge.pixpin.ia.Nube.Motor(proveedor, clave).probar().fold({ it.replaceFirstChar { c -> c.uppercase() } }, { "⚠️ " + (it.message ?: "no se pudo") })
                }
            }
        }) { Text("Probar") }
        if (guardada != null) TextButton(onClick = {
            alcance.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClavesDeLaNube.borrar(contexto, proveedor) }; version++; aviso = "Clave borrada." }
        }) { Text("Borrar") }
        TextButton(onClick = {
            runCatching { contexto.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(proveedor.paginaDeClaves)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Text("Sacar una") }
    }
    aviso?.let { Text(it, fontSize = 13.sp) }
    Opciones("Mandar trozos de tus documentos a la nube", listOf("" to "Preguntar", "si" to "Sí", "no" to "No"), ajustes.asistenteDocumentosNube) { v ->
        alcance.launch { app.settings.setAsistenteDocumentosNube(v) }
    }
    Opciones("Pensar antes de contestar", listOf("" to "Auto", "si" to "Sí", "no" to "No"), ajustes.asistentePensar) { v ->
        alcance.launch { app.settings.setAsistentePensar(v) }
    }
    Interruptor("Leer las respuestas en voz alta", ajustes.asistenteLeerRespuestas) { alcance.launch { app.settings.setAsistenteLeerRespuestas(it) } }
    Interruptor("Lo dictado se queda en la caja", ajustes.asistenteRevisarDicho) { alcance.launch { app.settings.setAsistenteRevisarDicho(it) } }
}

/**
 * **La pregunta Sí/No dentro de la burbuja** (§4.3 del manual del PC): lo que borra o saca algo
 * del aparato no abre un cuadro del sistema; el turno espera aquí. «No» va relleno (es lo de
 * por omisión) y «Sí» con borde rojo. Caduca a los 5 min o al pulsar parar.
 */
@Composable
fun PreguntaDelAsistente(respuesta: String, modifier: Modifier = Modifier) {
    // La duda con opciones (`preguntar_usuario`) sale en el mismo sitio que la de Sí/No.
    DudaDelAsistente(respuesta, modifier)
    val p = Asistente.pregunta?.takeIf { it.respuesta == respuesta } ?: return
    Column(modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("❓ ${p.texto}", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Asistente.contestarPregunta(false) }) { Text("No") }
            OutlinedButton(
                onClick = { Asistente.contestarPregunta(true) },
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) { Text("Sí", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * **La duda con opciones dentro de la burbuja** (`preguntar_usuario`, la tarjeta del PC del
 * 23-oct): la pregunta, un botón por opción (la recomendada primero, rellena) con lo que pasa si se
 * elige, y «Otro…» para escribir lo suyo. Lo que se escribe en el chat mientras espera también es
 * la respuesta. A los 5 minutos, o al parar, el asistente sigue con la recomendada.
 */
@Composable
fun DudaDelAsistente(respuesta: String, modifier: Modifier = Modifier) {
    val d = Asistente.duda?.takeIf { it.respuesta == respuesta } ?: return
    var otro by remember(d) { mutableStateOf<String?>(null) }
    Column(modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("❓ ${d.pregunta.texto}", fontSize = 14.sp)
        d.pregunta.opciones.forEachIndexed { i, o ->
            val alElegir = { Asistente.contestarDuda(com.forge.pixpin.ia.Preguntar.Contestada.Opcion(i)) }
            val rotulo: @Composable () -> Unit = {
                Column {
                    Text(o.etiqueta, fontSize = 14.sp)
                    if (o.descripcion.isNotEmpty()) Text(o.descripcion, fontSize = 12.sp)
                }
            }
            if (i == 0) Button(onClick = alElegir, modifier = Modifier.fillMaxWidth()) { rotulo() }
            else OutlinedButton(onClick = alElegir, modifier = Modifier.fillMaxWidth()) { rotulo() }
        }
        val escrito = otro
        if (escrito == null) TextButton(onClick = { otro = "" }) { Text("Otro…") }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = escrito, onValueChange = { otro = it }, singleLine = false,
                placeholder = { Text("Escribe lo tuyo") }, modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { if (escrito.isNotBlank()) Asistente.contestarDuda(com.forge.pixpin.ia.Preguntar.Contestada.Texto(escrito)) },
                enabled = escrito.isNotBlank()
            ) { Text("Enviar") }
        }
    }
}

/**
 * **«🧹 Conversación nueva»** encima de la caja del chat «Asistente» (`nueva_conversacion` del PC):
 * lo de arriba sigue a la vista, pero ya no se le manda al asistente, y lo duradero de lo que se
 * cierra queda en su memoria. Con un turno en marcha avisa en vez de cortar.
 */
@Composable
fun ConversacionNueva(chat: String?, modifier: Modifier = Modifier, alHacerse: () -> Unit) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        androidx.compose.material3.AssistChip(
            onClick = {
                alcance.launch {
                    val aviso = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Asistente.nuevaConversacion(contexto, chat) }
                    if (aviso != null) android.widget.Toast.makeText(contexto, aviso, android.widget.Toast.LENGTH_SHORT).show()
                    alHacerse()
                }
            },
            label = { Text("🧹 Conversación nueva", fontSize = 13.sp) }
        )
    }
}

/**
 * **«Pensando…» plegable y la petición mejorada** encima de la respuesta (§4.2 del manual del PC):
 * abierto mientras piensa, con el final de lo pensado; al acabar, plegado y «Pensó 12 s»; un toque
 * lo abre entero. Debajo, «📝 Entiendo: …» en una línea que se abre, y «Contexto renovado» si el
 * turno empezó reiniciado. Lo plegado o abierto es solo de la vista y no viaja.
 */
@Composable
fun SeccionDelPensado(m: com.forge.pixpin.guardados.Mensaje, recarga: Int) {
    val contexto = LocalContext.current
    val vivo = Asistente.enVivo?.takeIf { it.respuesta == m.id }
    val guardado by androidx.compose.runtime.produceState<com.forge.pixpin.ia.HojaTrabajo.Registro?>(null, m.id, m.texto, recarga, vivo == null) {
        value = if (vivo != null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PensadoDelChat.registroDe(contexto, m) }
    }
    val pensamiento = vivo?.pensamiento ?: guardado?.pensamiento.orEmpty()
    val segundos = vivo?.segundos ?: guardado?.segundos
    val pensando = vivo?.pensando == true
    val plan = vivo?.plan ?: guardado?.plan
    val reinicio = vivo?.reiniciado ?: guardado?.reinicio ?: false
    if (pensamiento.isBlank() && plan == null && !reinicio) return
    var abierto by remember(m.id) { mutableStateOf(false) }
    var planAbierto by remember(m.id) { mutableStateOf(false) }
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (pensamiento.isNotBlank()) {
            val rotulo = if (pensando) "Pensando…" else segundos?.let { "Pensó ${maxOf(1, Math.round(it))} s" } ?: "Pensó"
            Text(
                (if (abierto || pensando) "▾ " else "▸ ") + rotulo, fontSize = 13.sp, color = tenue,
                modifier = Modifier.clickable { abierto = !abierto }
            )
            if (pensando) Text(pensamiento.takeLast(700), fontSize = 12.sp, color = tenue, maxLines = 6)
            else if (abierto) Text(pensamiento.take(6000), fontSize = 12.sp, color = tenue)
        }
        plan?.let { p ->
            val lineas = p.lineas()
            Text(
                "📝 " + if (planAbierto) lineas else lineas.lineSequence().first(), fontSize = 13.sp, color = tenue,
                maxLines = if (planAbierto) 4 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable { planAbierto = !planAbierto }
            )
        }
        if (reinicio) Text("Contexto renovado", fontSize = 12.sp, color = tenue)
    }
}

/**
 * **«Abrir fuente»** (§4.5): la primera cita de `fuentesIa`, con su título y «+N» si hay más.
 * Ejecuta su pedido: un mensaje se abre en su chat; un fichero (ruta del PC o de aquí) se busca
 * entre los adjuntos por su nombre y se abre en su visor; si no está, la tarjeta no hace nada.
 */
@Composable
fun FuenteDeLaRespuesta(m: com.forge.pixpin.guardados.Mensaje) {
    val contexto = LocalContext.current
    val fuentes = m.fuentesIa ?: return
    val primera = fuentes.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: return
    val titulo = (primera["titulo"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
    val alcance = rememberCoroutineScope()
    TextButton(onClick = {
        alcance.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { AbrirFuente.abrir(contexto, primera) } }
    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
        Text("📄 $titulo" + if (fuentes.size > 1) "  +${fuentes.size - 1}" else "", fontSize = 13.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * **Las recetas como botones** encima de la caja del chat «Asistente» (§4.7): ordenadas por su
 * `orden`, con su icono y su nombre; un toque manda el pedido como si se escribiera. Pulsación
 * larga: mover a izquierda o derecha, o borrar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecetasDelAsistente(modifier: Modifier = Modifier, alMandar: (String) -> Unit) {
    val contexto = LocalContext.current
    val revision = RecetasDelTelefono.revision.intValue
    val recetas by androidx.compose.runtime.produceState(emptyList<com.forge.pixpin.ia.Automatizaciones.Receta>(), revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.forge.pixpin.ia.Automatizaciones.recetas(RecetasDelTelefono.leer(contexto)) }
    }
    if (recetas.isEmpty()) return
    val alcance = rememberCoroutineScope()
    var editando by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.lazy.LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(recetas.size) { i ->
            val r = recetas[i]
            Box {
                Surface(
                    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.combinedClickable(onClick = { alMandar(r.pedido) }, onLongClick = { editando = r.id })
                ) { Text("${r.icono} ${r.nombre}", fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                androidx.compose.material3.DropdownMenu(expanded = editando == r.id, onDismissRequest = { editando = null }) {
                    fun hacer(c: (com.forge.pixpin.ia.Automatizaciones.Lista) -> Unit) {
                        editando = null
                        alcance.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { RecetasDelTelefono.cambiar(contexto) { c(it) } } }
                    }
                    androidx.compose.material3.DropdownMenuItem(text = { Text("← A la izquierda") }, onClick = { hacer { com.forge.pixpin.ia.Automatizaciones.moverReceta(it, r.id, -1, System.currentTimeMillis()) } })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("A la derecha →") }, onClick = { hacer { com.forge.pixpin.ia.Automatizaciones.moverReceta(it, r.id, 1, System.currentTimeMillis()) } })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Borrar", color = MaterialTheme.colorScheme.error) }, onClick = { hacer { com.forge.pixpin.ia.Automatizaciones.borrarReceta(it, r.id) } })
                }
            }
        }
    }
}

/**
 * **Los mensajes de sistema, plegados en una franja** (lo del PC del 14-oct): las letras de los
 * planos, las figuras y las automatizaciones (`auto-*`) siguen en el chat y viajan, pero no son
 * burbujas. Una línea discreta los cuenta y, al tocarla, los lista; quitar una letra borra su
 * mensaje, como antes la burbuja.
 */
@Composable
fun FranjaDeSistema(deSistema: List<com.forge.pixpin.guardados.Mensaje>, modifier: Modifier = Modifier, alQuitar: (com.forge.pixpin.guardados.Mensaje) -> Unit) {
    if (deSistema.isEmpty()) return
    var abierta by remember { mutableStateOf(false) }
    val letras = deSistema.filter { it.id.startsWith("auto-letra-") }
    val partes = buildList {
        if (letras.isNotEmpty()) add("Letras de planos: ${letras.size}")
        if (deSistema.any { it.id == FigurasQueViajan.ID }) add("Figuras")
        if (deSistema.any { it.id == com.forge.pixpin.ia.Automatizaciones.ID_MENSAJE }) add("Automatizaciones")
        val otros = deSistema.count { !it.id.startsWith("auto-letra-") && it.id != FigurasQueViajan.ID && it.id != com.forge.pixpin.ia.Automatizaciones.ID_MENSAJE }
        if (otros > 0) add("Otros: $otros")
    }
    Column(modifier.fillMaxWidth()) {
        Text(
            (if (abierta) "▾ " else "▸ ") + partes.joinToString(" · "), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { abierta = !abierta }.padding(vertical = 4.dp)
        )
        if (abierta) for (m in deSistema) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val que = when {
                m.id.startsWith("auto-letra-") -> "Letra «${m.nombre}»: la usan los planos"
                m.id == FigurasQueViajan.ID -> "Figuras guardadas: se usan desde el menú de figuras"
                m.id == com.forge.pixpin.ia.Automatizaciones.ID_MENSAJE -> "Automatizaciones y recetas del asistente"
                else -> m.nombre.ifBlank { m.id }
            }
            Text(que, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (m.id.startsWith("auto-letra-")) TextButton(onClick = { alQuitar(m) }) { Text("Quitar", fontSize = 12.sp) }
        }
    }
}
