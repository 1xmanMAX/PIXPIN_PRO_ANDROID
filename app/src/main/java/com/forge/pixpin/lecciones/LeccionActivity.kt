package com.forge.pixpin.lecciones

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Apuntar una lección en dos toques**, encima de lo que se esté haciendo.
 *
 * Es una hoja que sube desde abajo sobre cualquier pantalla —también sobre otra aplicación, desde
 * el botón flotante—. Solo pide **lo que aprendiste**; mientras se escribe, todo lo demás se
 * propone solo ([Etiquetador]): etiquetas, área, si es un error o un acierto, las causas. Se
 * acepta tal cual o se quita con un toque. Si ya había una lección parecida, lo ofrece: lo que
 * toca entonces no es otra lección, es apuntar que **volvió a pasar**.
 *
 * Tocar fuera **guarda** (si hay algo escrito): la forma más rápida de apuntar es no tener que
 * buscar el botón. Lo mismo sirve para editar una que ya existe ([EXTRA_ID]).
 */
class LeccionActivity : ComponentActivity() {

    /** Las fotos y audios puestos y aún sin guardar: si la hoja se va sin guardar, se borran. */
    private val nuevos = mutableStateListOf<com.forge.pixpin.guardados.Mensaje>()
    private var guardada = false

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !guardada) nuevos.forEach { m -> m.ruta?.let { java.io.File(it).delete() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sin esto el teclado no empuja la hoja: `imePadding` necesita dibujar bajo las barras.
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_ID)
        val compartido = intent.getStringExtra(Intent.EXTRA_TEXT)
        val texto = intent.getStringExtra(EXTRA_TEXTO) ?: compartido
        val deMensaje = intent.getStringExtra(EXTRA_MENSAJE)
        val proyecto = intent.getStringExtra(EXTRA_PROYECTO)
        val dictar = intent.getBooleanExtra(EXTRA_DICTAR, false)
        setContent { PixPinTheme(cielo = false) { Hoja(id, texto, deMensaje, proyecto, dictar) } }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Hoja(id: String?, prefijado: String?, deMensaje: String?, proyectoInicial: String?, dictarYa: Boolean) {
        val almacen = remember { LeccionesStore(this) }
        val todas by LeccionesStore.todas.collectAsState()
        var cargada by remember { mutableStateOf(id == null) }
        val existente = remember(todas, id) { todas.firstOrNull { it.leccion.id == id } }
        LaunchedEffect(Unit) { if (todas.isEmpty()) withContext(Dispatchers.IO) { almacen.recargar() }; cargada = true }

        // Lo que se está escribiendo.
        var titulo by remember { mutableStateOf("") }
        var quePaso by remember { mutableStateOf("") }
        var porQue by remember { mutableStateOf("") }
        var proxima by remember { mutableStateOf("") }
        var referencias by remember { mutableStateOf("") }
        var tipo by remember { mutableStateOf<String?>(null) }
        var area by remember { mutableStateOf<String?>(null) }
        var gravedad by remember { mutableIntStateOf(1) }
        val etiquetas = remember { mutableStateListOf<String>() }
        val quitadas = remember { mutableStateListOf<String>() }
        val causas = remember { mutableStateListOf<String>() }
        var causasTocadas by remember { mutableStateOf(false) }
        var proyecto by remember { mutableStateOf(proyectoInicial) }
        var detalle by remember { mutableStateOf(false) }
        var nuevaEtiqueta by remember { mutableStateOf("") }
        var listo by remember { mutableStateOf(false) }
        val quitados = remember { mutableStateListOf<String>() }
        var viejos by remember { mutableStateOf<List<com.forge.pixpin.guardados.Mensaje>>(emptyList()) }
        LaunchedEffect(existente?.leccion?.adjuntos) {
            val l = existente?.leccion ?: return@LaunchedEffect
            viejos = withContext(Dispatchers.IO) { runCatching { almacen.adjuntosDe(l) }.getOrDefault(emptyList()) }
        }

        fun poner(dicho: String) {
            val limpio = Etiquetador.sinEtiquetas(dicho)
            Etiquetador.escritas(dicho).forEach { if (it !in etiquetas) etiquetas += it }
            val c = Dictado.repartir(limpio)
            titulo = c.titulo
            if (c.quePaso.isNotBlank()) quePaso = c.quePaso
            if (c.porQue.isNotBlank()) porQue = c.porQue
            if (c.proxima.isNotBlank() && c.proxima != c.titulo) proxima = c.proxima
            if (quePaso.isNotBlank() || porQue.isNotBlank() || proxima.isNotBlank()) detalle = true
        }

        // Rellenar una vez: lo que había (editar) o lo que llega (compartir, mensaje, dictado).
        LaunchedEffect(cargada, existente) {
            if (!cargada || listo) return@LaunchedEffect
            val e = existente
            if (e != null) {
                val l = e.leccion
                titulo = l.titulo; quePaso = l.quePaso; porQue = l.porQue; proxima = l.proxima
                referencias = l.referencias.joinToString(", "); tipo = l.tipo; area = l.area.ifBlank { null }
                gravedad = l.gravedad; etiquetas.addAll(l.etiquetas); quitadas.addAll(l.quitadas)
                causas.addAll(l.causas); causasTocadas = true; proyecto = e.proyecto
                detalle = l.quePaso.isNotBlank() || l.porQue.isNotBlank() || l.proxima.isNotBlank()
            } else if (!prefijado.isNullOrBlank()) poner(prefijado)
            listo = true
        }

        // Las propuestas, al dejar de escribir un momento.
        val textoEntero = "$titulo\n$quePaso\n$porQue\n$proxima\n$referencias"
        var propuesta by remember { mutableStateOf<Etiquetador.Propuesta?>(null) }
        var parecidas by remember { mutableStateOf<List<Buscador.Resultado>>(emptyList()) }
        LaunchedEffect(textoEntero, todas) {
            delay(250)
            val aprendido = withContext(Dispatchers.Default) { Etiquetador.aprender(todas.map { it.leccion }) }
            propuesta = withContext(Dispatchers.Default) { Etiquetador.proponer(textoEntero, aprendido, quitadas) }
            parecidas = if (existente != null) emptyList() else withContext(Dispatchers.Default) {
                Buscador.parecidas(todas.map { it.indice }, "$titulo $quePaso")
            }
        }
        val auto = propuesta?.etiquetas.orEmpty().filter { it !in etiquetas && it !in quitadas }
        val areaFinal = area ?: propuesta?.area
        val tipoFinal = tipo ?: propuesta?.tipo ?: Leccion.TIPO_LECCION
        val causasFinal = if (causasTocadas) causas.toList() else (causas + propuesta?.causas.orEmpty()).distinct()

        fun guardarYSalir() {
            if (titulo.isBlank() && nuevos.isEmpty()) { finish(); return }
            val ahora = System.currentTimeMillis()
            val base = existente?.leccion
            val l = (base ?: Leccion(id = LeccionesStore.nuevoId(ahora), creada = ahora, titulo = "", deMensaje = deMensaje)).copy(
                tocada = ahora,
                titulo = titulo.trim().ifBlank { if (nuevos.any { it.clase == com.forge.pixpin.guardados.Clase.VOZ }) "Nota de voz" else "Foto" }, quePaso = quePaso.trim(), porQue = porQue.trim(), proxima = proxima.trim(),
                tipo = tipoFinal, area = areaFinal.orEmpty(), gravedad = gravedad,
                etiquetas = etiquetas.toList(), etiquetasAuto = auto, quitadas = quitadas.toList(),
                referencias = referencias.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                causas = causasFinal
            )
            val app = application as PixPinApp
            val van = nuevos.toList(); val fuera = quitados.toList()
            guardada = true
            app.scope.launch(Dispatchers.IO) { runCatching { almacen.guardar(l, proyecto, van, fuera) } }
            Toast.makeText(this, if (base == null) "💡 Lección guardada" else "Lección guardada", Toast.LENGTH_SHORT).show()
            finish()
        }

        fun volvioAPasar(e: LeccionesStore.Entrada) {
            val app = application as PixPinApp
            app.scope.launch(Dispatchers.IO) { runCatching { almacen.guardar(Repaso.repetida(e.leccion, System.currentTimeMillis()), e.proyecto) } }
            Toast.makeText(this, "🔁 Apuntado: volvió a pasar (${e.leccion.vecesQuePaso + 1} veces)", Toast.LENGTH_LONG).show()
            finish()
        }

        val dictado = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val dicho = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!dicho.isNullOrBlank()) {
                if (titulo.isBlank()) poner(dicho) else poner("$titulo. $dicho")
            }
        }
        fun dictar() {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Cuenta qué aprendiste")
            runCatching { dictado.launch(i) }.onFailure {
                Toast.makeText(this, "No hay dictado en este teléfono: usa el micrófono del teclado", Toast.LENGTH_LONG).show()
            }
        }
        LaunchedEffect(Unit) { if (dictarYa) dictar() }

        val foco = remember { FocusRequester() }
        LaunchedEffect(listo) { if (listo && !dictarYa && existente == null) runCatching { foco.requestFocus() } }

        // Atrás también guarda: es el gesto con el que uno «deja» la hoja. Descartar es la X.
        androidx.activity.compose.BackHandler { guardarYSalir() }

        // Fuera de la hoja: guardar y cerrar.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { guardarYSalir() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                Modifier.fillMaxWidth().widthIn(max = 640.dp).imePadding().navigationBarsPadding()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    Modifier.heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (existente == null) "💡 Nueva lección" else "💡 Lección", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (existente != null) {
                            IconButton(onClick = {
                                val e = existente
                                lifecycleScope.launch(Dispatchers.IO) { almacen.borrar(e) }
                                finish()
                            }) { Icon(Icons.Filled.DeleteOutline, "Borrar") }
                        }
                        IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Descartar") }
                    }

                    // Lo único obligatorio.
                    OutlinedTextField(
                        value = titulo, onValueChange = { titulo = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(foco),
                        placeholder = { Text("¿Qué aprendiste? Escríbelo o díctalo de corrido") },
                        minLines = 2, maxLines = 6,
                        textStyle = MaterialTheme.typography.titleMedium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { guardarYSalir() }),
                        trailingIcon = { IconButton(onClick = { dictar() }) { Icon(Icons.Filled.Mic, "Dictar") } },
                        shape = RoundedCornerShape(18.dp)
                    )

                    // Fotos y audios: la foto del error, lo que se dijo.
                    AdjuntosDeLaLeccion(viejos, nuevos, quitados)

                    // ¿Ya la tenías?
                    parecidas.firstOrNull()?.let { r ->
                        val e = todas.firstOrNull { it.leccion.id == r.leccion.id }
                        if (e != null) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Se parece a una que ya tienes", style = MaterialTheme.typography.labelMedium)
                                    Text("«${e.leccion.titulo}»", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                }
                                FilledTonalButton(onClick = { volvioAPasar(e) }) { Text("🔁 Pasó otra vez") }
                            }
                        }
                    }

                    // Tipo: lo propuesto viene marcado.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Leccion.TIPO_LECCION to "💡 Lección", Leccion.TIPO_ERROR to "⚠️ Error", Leccion.TIPO_ACIERTO to "✅ Acierto").forEach { (t, nombre) ->
                            FilterChip(selected = tipoFinal == t, onClick = { tipo = t }, label = { Text(nombre) })
                        }
                        Spacer(Modifier.width(4.dp))
                        listOf(1 to "Leve", 2 to "Importante", 3 to "Grave").forEach { (g, nombre) ->
                            FilterChip(selected = gravedad == g, onClick = { gravedad = g }, label = { Text(nombre) })
                        }
                    }

                    // Área.
                    Text("Área", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val areas = (Leccion.AREAS + todas.map { it.leccion.area }.filter { it.isNotBlank() }).distinct()
                        areas.forEach { a ->
                            val propuesta2 = area == null && propuesta?.area == a
                            FilterChip(
                                selected = areaFinal == a,
                                onClick = { area = if (areaFinal == a) "" else a },
                                label = { Text(if (propuesta2) "✨ $a" else a) }
                            )
                        }
                    }

                    // Etiquetas: las puestas y las propuestas (✨), que se quitan con un toque.
                    Text("Etiquetas", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        etiquetas.forEach { e ->
                            InputChip(selected = true, onClick = { etiquetas.remove(e) }, label = { Text("#$e") },
                                trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) })
                        }
                        auto.forEach { e ->
                            InputChip(selected = false, onClick = { quitadas += e }, label = { Text("✨ #$e") },
                                trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) })
                        }
                        OutlinedTextField(
                            value = nuevaEtiqueta, onValueChange = { v ->
                                if (v.endsWith(' ') || v.endsWith(',')) {
                                    val e = v.trim(' ', ',', '#').lowercase()
                                    if (e.isNotEmpty() && e !in etiquetas) etiquetas += e
                                    quitadas.remove(e); nuevaEtiqueta = ""
                                } else nuevaEtiqueta = v
                            },
                            modifier = Modifier.widthIn(min = 120.dp, max = 180.dp).height(52.dp),
                            placeholder = { Text("+ etiqueta") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val e = nuevaEtiqueta.trim(' ', ',', '#').lowercase()
                                if (e.isNotEmpty() && e !in etiquetas) etiquetas += e
                                nuevaEtiqueta = ""
                            }),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }

                    // Lo demás, plegado: no estorba a quien solo quiere apuntar la frase.
                    TextButton(onClick = { detalle = !detalle }) {
                        Icon(if (detalle) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        Text(if (detalle) "Menos detalle" else "Qué pasó, por qué y qué haré distinto")
                    }
                    AnimatedVisibility(detalle) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Campo(quePaso, { quePaso = it }, "Qué pasó")
                            Campo(porQue, { porQue = it }, "Por qué pasó")
                            Text("Causas (un toque)", style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Leccion.CAUSAS.forEach { c ->
                                    val propuestaC = !causasTocadas && c in propuesta?.causas.orEmpty() && c !in causas
                                    FilterChip(
                                        selected = c in causasFinal,
                                        onClick = {
                                            if (!causasTocadas) { causas.clear(); causas.addAll(causasFinal); causasTocadas = true }
                                            if (c in causas) causas.remove(c) else causas += c
                                        },
                                        label = { Text(if (propuestaC) "✨ $c" else c) }
                                    )
                                }
                            }
                            Campo(proxima, { proxima = it }, "La próxima vez… (si pasa X, haré Y)")
                            Campo(referencias, { referencias = it }, "Palabras para encontrarla (separadas por comas)")
                        }
                    }

                    // El proyecto, al crearla: de él depende en qué chat sale.
                    if (existente == null) {
                        val proyectos by ((application as PixPinApp).proyectos.proyectos).collectAsState()
                        var eligiendo by remember { mutableStateOf(false) }
                        Box {
                            AssistChip(
                                onClick = { eligiendo = true },
                                leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                                label = { Text(proyectos.firstOrNull { it.id == proyecto }?.nombre ?: "Sin proyecto (chat general)") }
                            )
                            DropdownMenu(expanded = eligiendo, onDismissRequest = { eligiendo = false }) {
                                DropdownMenuItem(text = { Text("Sin proyecto") }, onClick = { proyecto = null; eligiendo = false })
                                proyectos.filter { !it.archivado }.sortedByDescending { it.tocado }.forEach { p ->
                                    DropdownMenuItem(text = { Text(p.nombre) }, onClick = { proyecto = p.id; eligiendo = false })
                                }
                            }
                        }
                    } else {
                        val e = existente
                        // Lo que hace que no se repita: apuntar que pasó otra vez.
                        OutlinedButton(onClick = { volvioAPasar(e) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (e.leccion.repeticiones.isEmpty()) "🔁 Me volvió a pasar" else "🔁 Me volvió a pasar (van ${e.leccion.vecesQuePaso})")
                        }
                        val relacionadas = remember(todas, e) { Buscador.relacionadas(todas.map { it.indice }, e.leccion) }
                        if (relacionadas.isNotEmpty()) {
                            Text("Relacionadas", style = MaterialTheme.typography.labelMedium)
                            relacionadas.forEach { r ->
                                Text("💡 ${r.titulo}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { abrir(this@LeccionActivity, r.id) }.padding(8.dp))
                            }
                        }
                    }

                    Button(onClick = { guardarYSalir() }, enabled = titulo.isNotBlank() || nuevos.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Guardar", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    @Composable
    private fun Campo(valor: String, cambiar: (String) -> Unit, pista: String) {
        OutlinedTextField(
            value = valor, onValueChange = cambiar, modifier = Modifier.fillMaxWidth(),
            label = { Text(pista) }, minLines = 1, maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp)
        )
    }

    companion object {
        private const val EXTRA_ID = "leccion_id"
        private const val EXTRA_TEXTO = "leccion_texto"
        private const val EXTRA_MENSAJE = "leccion_mensaje"
        private const val EXTRA_PROYECTO = "leccion_proyecto"
        private const val EXTRA_DICTAR = "leccion_dictar"

        /** Abre una lección para verla o cambiarla. */
        fun abrir(context: Context, id: String) = context.startActivity(intent(context).putExtra(EXTRA_ID, id))

        /**
         * Una lección nueva. [texto] la empieza (lo de un mensaje, lo compartido); [mensaje] y
         * [proyecto] dicen de dónde sale y en qué chat irá; [dictar] abre el micrófono ya.
         */
        fun nueva(context: Context, texto: String? = null, mensaje: String? = null, proyecto: String? = null, dictar: Boolean = false) =
            context.startActivity(
                intent(context).putExtra(EXTRA_TEXTO, texto).putExtra(EXTRA_MENSAJE, mensaje)
                    .putExtra(EXTRA_PROYECTO, proyecto).putExtra(EXTRA_DICTAR, dictar)
            )

        private fun intent(context: Context) = Intent(context, LeccionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
