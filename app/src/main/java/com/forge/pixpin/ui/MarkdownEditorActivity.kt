package com.forge.pixpin.ui

import androidx.compose.material.icons.filled.EditNote

import androidx.compose.ui.graphics.nativeCanvas

import androidx.compose.ui.layout.boundsInWindow

import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.runtime.rememberCoroutineScope

import com.forge.pixpin.motormd.Adjuntos
import com.forge.pixpin.motormd.BarraDeEstilosUi
import com.forge.pixpin.motormd.BarraDeFamiliasUi
import com.forge.pixpin.motormd.Bloque
import com.forge.pixpin.motormd.Bloques
import com.forge.pixpin.motormd.Comandos
import com.forge.pixpin.motormd.EditorVivo
import com.forge.pixpin.motormd.Historial
import com.forge.pixpin.motormd.Inline
import com.forge.pixpin.motormd.Markdown
import com.forge.pixpin.motormd.MarkdownText
import com.forge.pixpin.motormd.InlineText
import com.forge.pixpin.motormd.Menus
import com.forge.pixpin.motormd.Sitio
import com.forge.pixpin.motormd.SpanKind
import com.forge.pixpin.motormd.TextoStore
import com.forge.pixpin.motormd.TipoDeBloque
import com.forge.pixpin.motormd.Vivo
import com.forge.pixpin.motormd.conEnlace
import com.forge.pixpin.motormd.iconoDeBloque
import com.forge.pixpin.motormd.trozoEn
import com.forge.pixpin.motormd.trozosDe

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AddComment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Info
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * El editor avanzado de notas, hermano del editor avanzado de dibujo.
 *
 * Vive **fuera del motor MD** a propósito: una actividad sabe de proyectos, de
 * temas y de la aplicación entera, y el motor no tiene por qué. Esto es la
 * carcasa —abre, guarda, manda a un proyecto— y todo lo que interpreta, compone
 * y edita Markdown está en `motormd`, que se puede sacar y usar en otro sitio.
 *
 * ## Qué se copia de Telegram y qué no
 *
 * Se copia **toda la organización**: los mismos formatos, en el orden de su
 * `FloatingToolbar.STYLE_BUTTONS`, repartidos entre panel principal y
 * desbordamiento como su `FloatingToolbarPopup`, con los botones encendidos
 * según lo que cubra la selección y alternando por cobertura como su
 * `toggleStyleForSelection`. Y su diálogo de enlace, con el botón de pegar que
 * solo asoma cuando hay algo que pegar. Todo eso vive en [BarraDeFormatoUi] y en
 * [MarkdownEdit], compartido con la nota flotante.
 *
 * No se copia una cosa: que el suyo sea **wysiwyg**. En Telegram nunca ves un
 * asterisco; el formato va en spans, aparte del texto. Aquí el texto que se
 * escribe **es** lo que se guarda, y de él viven la exportación, el PDF y el
 * SVG. Convertir spans a Markdown y de vuelta en cada pulsación metería un
 * traductor entre el dedo y el archivo, y ese traductor es justo donde se pierde
 * lo que el usuario escribió. Así que aquí se escribe Markdown y se ve el
 * resultado al lado, con el botón de arriba.
 */
class MarkdownEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val desde = intent.getIntExtra(EXTRA_DESDE, -1)
        // Leerlo toca disco (el chat): fuera del hilo que pinta, y el editor lo recibe al llegar.
        val comentariosDeLaNota = androidx.compose.runtime.mutableStateOf<ComentariosDeNota?>(null)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val c = runCatching { ComentariosDeNota.de(this@MarkdownEditorActivity, id) }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { comentariosDeLaNota.value = c }
        }

        // **Un .md o un .txt abierto de paso** (27-oct-2026, desde otra aplicación sin guardarlo). Ver [com.forge.pixpin.guardados.ModoDePaso].
        val paso = com.forge.pixpin.guardados.AbrirDePaso.vigilar(this)
        // **Un archivo, por su ruta** (27-oct-2026): un `.md` o un texto del chat o de paso. El texto
        // no viaja en el intento —uno grande no cabe y Android no abría nada—: se lee aquí, fuera del
        // hilo que pinta, y la pantalla sale al tenerlo. Ver [abrirArchivo].
        val ruta = intent.getStringExtra(EXTRA_RUTA) ?: paso?.ruta?.takeIf { !intent.hasExtra(EXTRA_TEXTO) }
        if (ruta == null) mostrar(id, intent.getStringExtra(EXTRA_TEXTO).orEmpty(), desde, comentariosDeLaNota, paso)
        else lifecycleScope.launch {
            val texto = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.forge.pixpin.guardados.FormatosDeTexto.leer(java.io.File(ruta).readBytes()) }.getOrNull()
            }
            if (texto == null) {
                Toast.makeText(this@MarkdownEditorActivity, "No se pudo leer el archivo", Toast.LENGTH_SHORT).show()
                finish()
            } else mostrar(id, texto, desde, comentariosDeLaNota, paso)
        }
    }

    /** La pantalla, con lo que ya se tiene: [inicial] es el texto, venga del intento o del archivo. */
    private fun mostrar(
        id: String, inicial: String, desde: Int,
        comentariosDeLaNota: androidx.compose.runtime.MutableState<ComentariosDeNota?>,
        paso: com.forge.pixpin.guardados.AbrirDePaso.Estado?
    ) {
        val llano = intent.getBooleanExtra(EXTRA_LLANO, false)
        val soloLeer = intent.getBooleanExtra(EXTRA_SOLO_LEER, false)
        val archivo = intent.getStringExtra(EXTRA_NOMBRE)?.takeIf { it.isNotBlank() } ?: paso?.nombre
        setContent {
            PixPinTheme {
              Box(Modifier.fillMaxSize()) {
                Pantalla(
                    inicial = inicial,
                    desde = desde,
                    llano = llano,
                    soloLeer = soloLeer,
                    archivo = archivo,
                    onGuardar = { texto ->
                        if (id.isNotEmpty()) TextoStore.guardar(id, texto)
                        guardarEnSuProyecto(id, texto)
                        // La letra de una canción o el texto de un audio: vuelve a su mensaje.
                        intent.getStringExtra(EXTRA_MENSAJE)?.let { mensaje ->
                            Thread {
                                com.forge.pixpin.guardados.MensajesStore(this@MarkdownEditorActivity).actualizar(mensaje) {
                                    it.copy(transcripcion = texto, estadoDelTexto = it.estadoDelTexto ?: com.forge.pixpin.guardados.TEXTO_LETRA)
                                }
                            }.start()
                        }
                        cerrarYVolver()
                    },
                    onDescartar = { cerrarYVolver() },
                    detalle = { texto -> detalleDe(id, texto) },
                    comentarios = comentariosDeLaNota.value,
                    idDeLaNota = id,
                    proyectoDeLaNota = intent.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
                        ?: (application as? PixPinApp)?.proyectos?.proyectos?.value?.firstOrNull { p -> p.hojas.any { it.id == id } }?.id,
                    masAcciones = listOfNotNull(
                        "Compartir…" to { texto: String -> abrirCompartir(texto) },
                        // **Lectura veloz** (27-oct-2026): la nota en la app de lectura rápida; con su porcentaje si ya se leyó allí.
                        if (codigoDeLaNota.value == null) null
                        else (listOfNotNull("Lectura veloz", porcentajeEnLecturaVeloz.value?.let { "$it %" }).joinToString(" · ")) to { texto: String -> abrirEnLecturaVeloz(texto) }
                    )
                )
                compartiendo.value?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(c) { compartiendo.value = null } }
                // **Abrir con…, Guardar y Compartir**, solo de paso. Ver [BarraDePaso].
                var compartiendoDePaso by remember { mutableStateOf(false) }
                BarraDePaso(paso, true, Modifier.align(Alignment.TopCenter), alCompartir = { compartiendoDePaso = true })
                if (compartiendoDePaso && paso != null) HojaDeCompartir(
                    remember {
                        com.forge.pixpin.guardados.AbrirDePaso.conWifi(
                            this@MarkdownEditorActivity,
                            Compartible(paso.nombre.substringBeforeLast('.'), formatos = listOf(ExportarVisor.original(this@MarkdownEditorActivity, paso.ruta, paso.nombre))),
                            paso
                        )
                    }
                ) { compartiendoDePaso = false }
              }
            }
        }
    }

    /**
     * Si esta nota **es** una hoja de un proyecto, deja lo escrito dentro.
     *
     * El editor no sabe de dónde le abrieron —recibe un identificador y un
     * texto—, así que se busca por identificador. Es barato: son unos pocos
     * proyectos con unas pocas hojas, y a cambio da igual desde dónde se haya
     * entrado. Sin esto, abrir una nota desde el proyecto y guardarla escribía
     * en el almacén del pin y el proyecto seguía enseñando lo de antes.
     */
    /**
     * **Lectura veloz** (27-oct-2026, lo pidió el usuario): la nota, como `.md`, en la app «Lectura
     * Veloz». Su punto vuelve al `anot-<uid>.voz` de la nota. Ver [com.forge.pixpin.guardados.LecturaVeloz].
     * [codigoDeLaNota] es el código único de la nota (null: no es de un chat ni de un proyecto).
     */
    private val codigoDeLaNota = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val porcentajeEnLecturaVeloz = androidx.compose.runtime.mutableStateOf<Int?>(null)

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val codigo = runCatching { ComentariosDeNota.uidDeLaNota(this@MarkdownEditorActivity, id) }.getOrNull()
                ?.takeIf { com.forge.pixpin.guardados.LecturaVeloz.docValido(it) }
            val pct = codigo?.let { com.forge.pixpin.guardados.LecturaVelozEnElTelefono.porcentaje(this@MarkdownEditorActivity, it) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                codigoDeLaNota.value = codigo
                porcentajeEnLecturaVeloz.value = pct
            }
        }
    }

    private fun abrirEnLecturaVeloz(texto: String) {
        val codigo = codigoDeLaNota.value ?: return
        val titulo = com.forge.pixpin.motormd.ExportarDocx.tituloDe(texto)
        com.forge.pixpin.guardados.LecturaVelozEnElTelefono.abrir(this, {
            com.forge.pixpin.guardados.LecturaVelozEnElTelefono.deNota(this, codigo, titulo, texto)
        }) { que -> Toast.makeText(this, com.forge.pixpin.guardados.LecturaVelozEnElTelefono.avisoCorto(que), Toast.LENGTH_LONG).show() }
    }

    /** Lo que se está compartiendo de la nota: la hoja de toda la aplicación. Ver [abrirCompartir]. */
    private val compartiendo = androidx.compose.runtime.mutableStateOf<com.forge.pixpin.ui.Compartible?>(null)

    /**
     * **Compartir la nota, en la hoja de toda la aplicación** (27-oct-2026). Antes aquí solo había
     * «Exportar a Word» (como el PC, 1-oct-2026), que pedía dónde guardar el `.docx`; ahora es la
     * misma hoja que en el chat: PDF, página web, Word, Markdown, texto y `.pixpin`. El Word sigue
     * siendo el de entonces: con lo escrito aunque no se haya guardado, sus fotos y sus comentarios.
     */
    private fun abrirCompartir(texto: String) {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val c = runCatching {
                val titulo = com.forge.pixpin.motormd.ExportarDocx.tituloDe(texto)
                val suelta = com.forge.pixpin.motor.Proyecto(
                    id = "suelto-nota-${id.ifBlank { "nota" }}", nombre = titulo, tocado = System.currentTimeMillis(),
                    hojas = listOf(com.forge.pixpin.motor.Hoja(id = id.ifBlank { "nota" }, nombre = titulo, nota = texto))
                )
                val base = com.forge.pixpin.ui.CompartirPaginas.de(
                    this@MarkdownEditorActivity, titulo, listOf(suelta to null), editable = suelta,
                    contenido = com.forge.pixpin.ui.FormatosDeCompartir.Contenido.NOTA
                )
                val word = com.forge.pixpin.ui.Compartible.Formato(
                    "word", Icons.Filled.Article, "Word", com.forge.pixpin.ui.Compartible.NINGUNA,
                    generar = {
                        val comentarios = runCatching { ComentariosDeNota.de(this@MarkdownEditorActivity, id)?.leer() }.getOrNull()
                        val datos = com.forge.pixpin.motormd.ExportarDocx.exportar(
                            com.forge.pixpin.motormd.ExportarDocx.Nota(
                                markdown = texto, titulo = titulo, comentarios = comentarios,
                                imagen = { ruta -> com.forge.pixpin.ui.CompartirPaginas.fotoParaWord(this@MarkdownEditorActivity, ruta) },
                                autor = runCatching { com.forge.pixpin.sincro.IdentidadEnDisco(filesDir).leer().yo.nombre }.getOrNull().orEmpty()
                            )
                        )
                        val destino = java.io.File(java.io.File(cacheDir, "share").apply { mkdirs() }, com.forge.pixpin.motormd.ExportarDocx.nombreDeArchivo(titulo))
                        destino.writeBytes(datos)
                        com.forge.pixpin.ui.Compartible.Salida(
                            destino, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            if (comentarios?.comentarios.isNullOrEmpty()) "Con sus títulos, listas y fotos" else "Con sus fotos y sus comentarios"
                        )
                    }
                )
                com.forge.pixpin.ui.Compartible(
                    base.titulo, base.paginas, base.formatos.map { if (it.id == "word") word else it }, base.marcadas, base.contenido
                )
            }.onFailure { android.util.Log.e("PixPinCompartir", "nota", it) }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (c == null) Toast.makeText(this@MarkdownEditorActivity, "No se pudo preparar", Toast.LENGTH_SHORT).show()
                else compartiendo.value = c
            }
        }
    }

    /** Nombre y peso de la nota, y de su proyecto si lo tiene. */
    private fun detalleDe(id: String, texto: String): List<Pair<String, String>> {
        val nota = com.forge.pixpin.motor.Detalle.deLaNota(id.ifBlank { "nota" }, texto)
        val filas = arrayListOf(
            getString(com.forge.pixpin.R.string.detalle_archivo) to nota.nombre,
            getString(com.forge.pixpin.R.string.detalle_peso) to com.forge.pixpin.motor.Detalle.legible(nota.bytes)
        )
        val proyecto = intent?.getStringExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO)
            ?.let { (application as? com.forge.pixpin.PixPinApp)?.proyectos?.porId(it) }
        if (proyecto != null) {
            val p = com.forge.pixpin.motor.Detalle.delProyecto(this, proyecto)
            filas += getString(com.forge.pixpin.R.string.detalle_proyecto) to p.nombre
            filas += getString(com.forge.pixpin.R.string.detalle_hojas) to "${p.hojas}"
            filas += getString(com.forge.pixpin.R.string.detalle_peso_proyecto) to com.forge.pixpin.motor.Detalle.legible(p.bytes)
        } else filas += getString(com.forge.pixpin.R.string.detalle_proyecto) to getString(com.forge.pixpin.R.string.detalle_sin_proyecto)
        return filas
    }

    private fun guardarEnSuProyecto(id: String, texto: String) {
        if (id.isEmpty()) return
        val app = application as? PixPinApp ?: return
        val ahora = System.currentTimeMillis()
        app.proyectos.proyectos.value
            .filter { p -> p.hojas.any { it.id == id && it.nota != null } }
            .forEach { p -> app.proyectos.guardar(Proyectos.conNota(p, id, texto, ahora)) }
    }

    /**
     * **Cerrar devuelve a donde se vino**: al chat del que se abrió, y de ahí a la lista de chats.
     * El editor vive en su propia tarea, así que sin decirlo atrás no es ese chat sino lo que
     * hubiera debajo, casi siempre el escritorio. Ver [com.forge.pixpin.Volver].
     */
    private fun cerrarYVolver() {
        com.forge.pixpin.Volver.alCerrar(this)
        finish()
    }

    companion object {
        private const val EXTRA_ID = "md_id"
        private const val EXTRA_TEXTO = "md_texto"
        private const val EXTRA_DESDE = "md_desde"
        /** El mensaje de audio cuya letra o texto se está escribiendo; lo guardado vuelve a él. */
        private const val EXTRA_MENSAJE = "md_mensaje"
        /** El archivo que se abre: el editor lo lee él mismo. Ver [abrirArchivo]. */
        internal const val EXTRA_RUTA = "md_ruta"
        /** Cómo se llama el archivo (el del mensaje o el que dijo la otra aplicación). */
        internal const val EXTRA_NOMBRE = "md_nombre"
        /** Texto llano: se ve tal cual, sin el Markdown. Ver [com.forge.pixpin.guardados.FormatosDeTexto]. */
        internal const val EXTRA_LLANO = "md_llano"
        /** Solo para mirar: sin «Escribir». */
        internal const val EXTRA_SOLO_LEER = "md_solo_leer"

        /**
         * **Abre un archivo de texto** —un `.md`, un `.txt`, un `.log`…— por su [ruta] (27-oct-2026).
         * El editor lo lee él mismo: el texto no va en el intento, que tiene un límite (unos cientos
         * de KB) y, pasado, `startActivity` fallaba sin decir nada. [llano]: se ve tal cual, sin
         * Markdown. [soloLeer]: sin «Escribir» (un adjunto del chat: lo escrito no tendría adónde
         * volver). [id] es la nota con que se anota y se comenta (`md-<mensaje>` desde el chat; ver
         * [ComentariosDeNota.uidDeLaNota]). Devuelve si se pudo abrir.
         */
        fun abrirArchivo(
            context: Context, ruta: String, nombre: String, id: String,
            llano: Boolean = false, soloLeer: Boolean = false
        ): Boolean = runCatching { context.startActivity(intentoDeArchivo(context, ruta, nombre, id, llano, soloLeer)) }.isSuccess

        /** El intento de [abrirArchivo], sin lanzarlo (para probarlo). */
        internal fun intentoDeArchivo(
            context: Context, ruta: String, nombre: String, id: String, llano: Boolean, soloLeer: Boolean
        ): Intent = Intent(context, MarkdownEditorActivity::class.java)
            // Cada nota en su tarea. Ver el manifiesto.
            .setData(android.net.Uri.parse("pixpin://nota/" + android.net.Uri.encode(id.ifBlank { "archivo-" + ruta.hashCode() })))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_RUTA, ruta)
            .putExtra(EXTRA_NOMBRE, nombre)
            .putExtra(EXTRA_LLANO, llano)
            .putExtra(EXTRA_SOLO_LEER, soloLeer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Abre la nota [id] con [texto], opcionalmente por la letra [desde].
         *
         * El texto va en el intent y no se lee de disco porque el pin puede
         * tener cambios sin guardar en el momento de abrir, y empezar a editar
         * perdiendo lo último escrito es el peor estreno posible. Al cerrar
         * vuelve por [TextoStore].
         *
         * [desde] es por dónde se estaba leyendo —el principio de una página—
         * y solo sirve para **empezar ahí**: el editor sigue siendo un rollo
         * seguido, sin hojas. Ver el visor de páginas de la nota flotante.
         */
        fun abrir(
            context: Context, id: String, texto: String, desde: Int = -1,
            /** El proyecto del que es: cerrar vuelve a su chat. Ver [com.forge.pixpin.Volver]. */
            desdeProyecto: String? = null,
            /** El mensaje de audio al que pertenece esta letra o texto, si es el caso. */
            mensaje: String? = null
        ) {
            val i = Intent(context, MarkdownEditorActivity::class.java)
                .also { if (mensaje != null) it.putExtra(EXTRA_MENSAJE, mensaje) }
                // Cada nota en su tarea. Ver el manifiesto.
                .setData(android.net.Uri.parse("pixpin://nota/" + android.net.Uri.encode(id.ifBlank { "suelta" })))
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXTO, texto)
                .putExtra(EXTRA_DESDE, desde)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (desdeProyecto != null) {
                i.putExtra(com.forge.pixpin.EXTRA_DESDE_PROYECTO, desdeProyecto)
            }
            runCatching { context.startActivity(i) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Pantalla(
    inicial: String,
    onGuardar: (String) -> Unit,
    onDescartar: () -> Unit,
    desde: Int = -1,
    /**
     * **Texto llano** (27-oct-2026, un `.txt`, un `.log`…, como el PC): se ve y se escribe tal cual,
     * renglón a renglón, sin componer el Markdown. Ver [com.forge.pixpin.guardados.FormatosDeTexto].
     */
    llano: Boolean = false,
    /** Solo para mirar (un adjunto del chat): sin «Escribir». */
    soloLeer: Boolean = false,
    /** El archivo que se ve, si es uno: su nombre va en la pastilla, con su icono. Null: una nota. */
    archivo: String? = null,
    /** Qué archivo es esta nota y cuánto pesa, con su proyecto. Ver [com.forge.pixpin.motor.Detalle]. */
    detalle: (String) -> List<Pair<String, String>> = { emptyList() },
    /** Los comentarios de esta nota, si tiene código con el que viajar. Ver [ComentariosDeNota]. */
    comentarios: ComentariosDeNota? = null,
    /** El proyecto de la nota, si es una hoja de uno: «Del chat» mira su chat. */
    proyectoDeLaNota: String? = null,
    /** El id de la nota, para su letra y tamaño propios. */
    idDeLaNota: String = "",
    /** Lo demás del menú ⋮ que sabe la actividad (exportar a Word…). */
    masAcciones: List<Pair<String, (String) -> Unit>> = emptyList()
) {
    var viendoDetalle by remember { mutableStateOf(false) }
    var valor by remember {
        mutableStateOf(TextFieldValue(inicial, TextRange(inicial.length)))
    }
    if (viendoDetalle) {
        val filas = remember { detalle(valor.text) }
        AlertDialog(
            onDismissRequest = { viendoDetalle = false },
            title = { Text("Detalle") },
            text = {
                Column {
                    filas.forEach { (que, cuanto) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(que, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(cuanto, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viendoDetalle = false }) { Text("Cerrar") } }
        )
    }
    // Dónde se está escribiendo, en coordenadas del texto limpio. Ver [Sitio].
    //
    // Si se entró desde una página de la nota, se empieza **en su primer
    // bloque**: al quedar activo pide el foco, y un campo con el foco se trae
    // solo a la vista. Así el editor abre por donde se estaba leyendo sin tener
    // que saber nada de páginas ni medir la pantalla.
    var sitio by remember {
        mutableStateOf(
            if (desde >= 0) {
                trozosDe(inicial).takeIf { it.isNotEmpty() }
                    ?.let { Sitio(bloque = trozoEn(it, desde)) }
            } else if (inicial.isBlank()) {
                // **Una nota nueva se abre escribiendo.** Sin sitio no hay campo, y
                // sin campo no hay teclado: se abría en blanco y muda.
                Sitio(0, TextRange(0))
            } else {
                null
            }
        )
    }
    var pidiendoUrl by remember { mutableStateOf(false) }
    // La letra y el tamaño de la vista (preferencia de este aparato, no del `.md`).
    val contextoDeLaVista = androidx.compose.ui.platform.LocalContext.current
    var vista by remember { mutableStateOf(VistaDeNotas.leer(contextoDeLaVista, idDeLaNota)) }
    var eligiendoVista by remember { mutableStateOf(false) }
    val letraDeLaNota = remember(vista.letra) { VistaDeNotas.familia(vista.letra) }
    // Lo que se está eligiendo para meter en la nota (página viva, enlace a una hoja, del chat).
    var eligiendo by remember { mutableStateOf<TipoDeBloque?>(null) }
    val contextoDelEditor = androidx.compose.ui.platform.LocalContext.current
    val contextoDeLaNota = remember { IncrustadosDeLaNota(contextoDelEditor) }
    // **Las páginas vivas al día** al abrir la nota: las hojas que cambiaron se repintan.
    var repintadas by remember { mutableIntStateOf(0) }
    if (!llano) LaunchedEffect(Unit) {
        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { PaginasVivas.ponerAlDia(contextoDelEditor, inicial) }.getOrDefault(0)
        }
        if (n > 0) repintadas += n
    }
    // **Los comentarios** (como en el PC): el fichero, el panel y el trozo que se va a comentar.
    var fichero by remember { mutableStateOf(com.forge.pixpin.motormd.Comentarios.Fichero()) }
    var viendoComentarios by remember { mutableStateOf<Int?>(null) }
    var comentando by remember { mutableStateOf<com.forge.pixpin.motormd.Comentarios.Ancla?>(null) }
    val alcanceDeComentarios = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(comentarios) {
        val c = comentarios ?: return@LaunchedEffect
        fichero = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.leer() }
    }
    fun cambiarComentarios(nuevo: com.forge.pixpin.motormd.Comentarios.Fichero, texto: String) {
        val c = comentarios ?: return
        fichero = nuevo
        alcanceDeComentarios.launch(kotlinx.coroutines.Dispatchers.IO) {
            c.guardar(nuevo, texto)?.let { guardado -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { fichero = guardado } }
        }
    }
    // **Ver la nota como va a quedar**, sin salir: el editor por bloques enseña
    // cada bloque ya compuesto, pero solo de uno en uno; esto es la página
    // entera, seguida, como la verá quien la reciba.
    // **Se abre mirando** (24-oct-2026, como todos los visores): una nota con algo escrito sale ya
    // compuesta, y el lápiz del riel de la derecha anota; escribir es el botón de la pastilla.
    // **Siempre** (27-oct-2026, el usuario: «que se abra siempre en modo visualización»): también una
    // nueva —que invita a escribir— o abierta desde un bloque, que se abre mirando por ese sitio.
    var viendo by remember { mutableStateOf(true) }
    val desplazado = rememberScrollState()
    if (desde > 0 && inicial.isNotEmpty()) LaunchedEffect(Unit) {
        // Por donde se estaba leyendo: lo escrito antes de [desde], en proporción al alto.
        val alto = androidx.compose.runtime.snapshotFlow { desplazado.maxValue }.first { it > 0 }
        desplazado.scrollTo((desde.toFloat() / inicial.length * alto).toInt().coerceIn(0, alto))
    }
    // ---- **Las barras, como en todos los visores** (27-oct-2026, el usuario: «que la nota tenga la
    // misma interfaz que los demás visores»): la pantalla entera, la pastilla de cristal arriba y el
    // riel a la derecha, que salen al tocar, se van al mover y solas a los pocos segundos. Escribiendo
    // la pastilla se queda: lleva deshacer, rehacer y guardar. Ver [RielALaVista]. ----
    var aLaVista by remember { mutableStateOf(true) }
    var toques by remember { mutableIntStateOf(0) }
    var menuAbierto by remember { mutableStateOf(false) }
    LaunchedEffect(aLaVista, toques, viendo, menuAbierto) {
        if (aLaVista && viendo && !menuAbierto) { kotlinx.coroutines.delay(3500); aLaVista = false }
    }
    LaunchedEffect(viendo) { aLaVista = true }
    val barras = !viendo || aLaVista || menuAbierto
    // **Lo que queda encima del texto**: la barra del sistema y la pastilla. El documento empieza ahí,
    // como antes bajo la barra fija, para que la tinta y los marcos ya puestos no se corran.
    val densidadDeLaPantalla = androidx.compose.ui.platform.LocalDensity.current
    val margenArriba = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(densidadDeLaPantalla) +
        with(densidadDeLaPantalla) { ALTO_DE_LA_PASTILLA.roundToPx() }
    // Los marcadores de la nota, en fracción del alto: el formato del PC para notas y páginas.
    var marcadores by remember { mutableStateOf(emptyList<com.forge.pixpin.motor.Lectura.Marcador>()) }
    var poniendoMarca by remember { mutableStateOf(false) }
    val archivoDeMarcas = comentarios?.archivoDeMarcas
    LaunchedEffect(archivoDeMarcas) {
        marcadores = archivoDeMarcas?.let { f ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.forge.pixpin.motor.Lectura.deTexto(com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(f))
            }
        } ?: emptyList()
    }
    val alcanceDeMarcas = rememberCoroutineScope()
    // ---- **La tinta y los marcos de la nota** (24-oct-2026, como el lector de notas del PC): en
    // unidades del documento —dp desde la esquina del texto, lo más parecido a los píxeles CSS del
    // PC—, `anot-<uid>.excalidraw.gz` (sin `.hoja`) y `anot-<uid>.marcos.json`. El lápiz anota;
    // escribir el texto va en su propio botón de arriba (`EDIT_NOTE` del PC). ----
    val baseDeLaNota = archivoDeMarcas?.name?.removeSuffix(".marcas")
    val densidadDeLaNota = androidx.compose.ui.platform.LocalDensity.current.density.toDouble()
    var anotandoLaNota by remember { mutableStateOf(false) }
    var tickDeLaTinta by remember { mutableIntStateOf(0) }
    val tintaDeLaNota = remember(baseDeLaNota) {
        com.forge.pixpin.motor.DrawController(
            baseDeLaNota?.let { com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contextoDelEditor, it)) }
                ?: com.forge.pixpin.motor.Scene()
        ).also { it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
    }
    val marcosDeLaNota = remember(baseDeLaNota) {
        MarcosDelVisor(baseDeLaNota?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivo(contextoDelEditor.filesDir, it) })
    }
    fun vistaDeLaTinta() = com.forge.pixpin.motor.Viewport(scrollX = 0.0, scrollY = (margenArriba - desplazado.value) / densidadDeLaNota, zoom = densidadDeLaNota)
    fun dejarDeAnotarLaNota() {
        if (!anotandoLaNota) return
        anotandoLaNota = false
        baseDeLaNota?.let { com.forge.pixpin.motor.ExcalidrawStore.guardar(contextoDelEditor, it, tintaDeLaNota.scene) }
    }
    var cajaDeLaNota by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    androidx.compose.runtime.DisposableEffect(tintaDeLaNota) { onDispose { dejarDeAnotarLaNota() } }
    fun tituloParaImprimir() = valor.text.lineSequence().firstOrNull()?.trim()?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() } ?: "Nota"
    fun imprimirLosMarcosDeLaNota() {
        val actividad = contextoDelEditor as? android.app.Activity ?: return
        alcanceDeMarcas.launch {
            ImprimirMarcos.imprimir(
                actividad, marcosDeLaNota, tituloParaImprimir(),
                ir = { m -> desplazado.scrollTo((m.y0 * densidadDeLaNota).toInt()) },
                cajaEnVentana = { m ->
                    androidx.compose.ui.geometry.Rect(
                        (m.x0 * densidadDeLaNota).toFloat(), (m.y0 * densidadDeLaNota + margenArriba - desplazado.value).toFloat(),
                        (m.x1 * densidadDeLaNota).toFloat(), (m.y1 * densidadDeLaNota + margenArriba - desplazado.value).toFloat()
                    ).translate(cajaDeLaNota.topLeft)
                }
            )
        }
    }
    /** «Imprimir todo»: la nota entera en PDF, con su letra (el mismo PDF de las notas). */
    fun imprimirLaNota() {
        val actividad = contextoDelEditor as? android.app.Activity ?: return
        val texto = valor.text
        alcanceDeMarcas.launch {
            val pdf = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.forge.pixpin.motor.PdfDeNota.aniadir(com.forge.pixpin.motor.PdfUnion.enBlanco(), texto, letras = com.forge.pixpin.motor.Detalle.letrasParaPdf())
                }.getOrNull()
            }
            if (pdf == null) Toast.makeText(actividad, "No se pudo preparar la impresión", Toast.LENGTH_SHORT).show()
            else com.forge.pixpin.guardados.Imprimir.pdf(actividad, pdf, tituloParaImprimir())
        }
    }
    var viendoCatalogo by remember { mutableStateOf(false) }
    var pidiendoArchivo by remember { mutableStateOf<TipoDeBloque?>(null) }
    var viendoAdjuntar by remember { mutableStateOf(false) }

    // Deshacer y rehacer, como su `historyButtons`. Se anota cada cambio y el
    // propio historial decide qué agrupar; ver [Historial].
    val historial = remember { Historial().also { it.empezar(inicial) } }
    var pasos by remember { mutableIntStateOf(0) }
    var confirmandoDescarte by remember { mutableStateOf(false) }
    val hayCambios = valor.text != inicial

    /**
     * Vuelve a poner el cursor en un sitio que exista.
     *
     * Deshacer cambiaba el texto y **dejaba el sitio como estaba**: si lo
     * deshecho era un bloque, el sitio señalaba a uno que ya no existe, y el
     * editor se quedaba escribiendo en un hueco. Se conserva el bloque si sigue
     * habiendo, y si no, el último.
     */
    fun recolocar(nuevoTexto: String) {
        val cuantos = Vivo.trozos(nuevoTexto).size
        if (cuantos == 0) {
            sitio = null
            return
        }
        val bloque = (sitio?.bloque ?: (cuantos - 1)).coerceIn(0, cuantos - 1)
        val largo = Vivo.contenido(nuevoTexto, bloque)?.text?.length ?: 0
        sitio = Sitio(bloque, TextRange(largo))
    }

    // **El botón de atrás guarda.** No lo tocaba nadie, así que hacía lo de por
    // defecto —cerrar la pantalla— y una nota entera escrita se perdía sin un
    // aviso. Una nota se guarda al salir, como en cualquier libreta; para tirar
    // lo escrito está la flecha, que ahora pregunta.
    androidx.activity.compose.BackHandler { onGuardar(valor.text) }

    fun cambia(nuevo: TextFieldValue) {
        if (nuevo.text != valor.text) {
            historial.anota(nuevo.text)
            pasos++
        }
        valor = nuevo
    }

    val contexto = LocalContext.current
    val selector = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val tipo = pidiendoArchivo
        pidiendoArchivo = null
        if (uri == null || tipo == null) return@rememberLauncherForActivityResult
        val ruta = Adjuntos.importar(contexto, uri, System.currentTimeMillis())
        if (ruta == null) {
            Toast.makeText(contexto, "No se pudo copiar el archivo", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        // La plantilla deja el cursor entre los paréntesis, así que la ruta se
        // escribe justo ahí y el bloque queda entero de una vez.
        val donde = sitio?.let { Vivo.trozos(valor.text).getOrNull(it.bloque)?.hasta }
            ?: valor.text.length
        val r = Comandos.elegir(valor.text, donde, tipo)
        val conRuta = r.text.substring(0, r.selStart) + ruta + r.text.substring(r.selStart)
        cambia(valor.copy(text = conRuta))
    }

    LaunchedEffect(pidiendoArchivo) {
        val tipo = pidiendoArchivo ?: return@LaunchedEffect
        selector.launch(arrayOf(filtroDe(tipo)))
    }

    // Lo tecleado tras una barra, si es que se está tecleando un comando.
    val consulta = remember(valor.text, valor.selection) {
        if (valor.selection.collapsed) {
            Comandos.consulta(valor.text, valor.selection.start)
        } else {
            null
        }
    }

    /** Cambia de tipo el bloque activo, conservando lo escrito. */
    fun convertirBloque(tipo: TipoDeBloque?) {
        val s2 = sitio ?: return
        val trozos = Vivo.trozos(valor.text)
        val trozo = trozos.getOrNull(s2.bloque) ?: return
        val fuente = trozo.de(valor.text)
        val cola = fuente.takeLastWhile { it == '\n' }
        val nuevo = Menus.convertir(fuente.trimEnd('\n'), tipo) + cola
        cambia(
            valor.copy(
                text = valor.text.substring(0, trozo.desde) + nuevo +
                    valor.text.substring(trozo.hasta)
            )
        )
    }

    /** Sale de la tabla a un bloque nuevo debajo. */
    fun fueraDeLaTabla() {
        val s2 = sitio ?: return
        val (nuevo, donde) = Vivo.bloqueNuevo(valor.text, s2.bloque)
        cambia(valor.copy(text = nuevo))
        sitio = donde
    }

    /**
     * Pone o quita un estilo sobre lo seleccionado, **sin escribir ni una marca**.
     *
     * El texto no se toca: solo cambian sus estilos, y de volverlos a Markdown se
     * encarga [Vivo.conContenido] al guardar el bloque. Es la diferencia entre
     * esto y la barra de la nota flotante, que sí escribe asteriscos porque allí
     * lo que se edita es el Markdown a la vista.
     */
    fun aplicarEstilo(kind: SpanKind, url: String?) {
        val s2 = sitio ?: return
        val contenido = Vivo.contenido(valor.text, s2.bloque) ?: return
        val spans = Inline.alternar(
            contenido.spans, s2.seleccion.min, s2.seleccion.max, kind, url
        )
        cambia(
            valor.copy(
                text = Vivo.conContenido(valor.text, s2.bloque, InlineText(contenido.text, spans))
            )
        )
    }

    fun insertarBloque(tipo: TipoDeBloque) {
        if (Bloques.pideEleccion(tipo)) {
            eligiendo = tipo
            return
        }
        if (Bloques.pideArchivo(tipo)) {
            pidiendoArchivo = tipo
            return
        }
        // El bloque activo se convierte si está vacío, y si no se abre uno
        // nuevo detrás: meter un título en mitad de un párrafo lo partiría.
        val s2 = sitio
        val vacio = s2?.let { Vivo.contenido(valor.text, it.bloque)?.text.isNullOrEmpty() } == true
        if (s2 != null && vacio) {
            convertirBloque(tipo)
            return
        }
        if (s2 != null) {
            val (conNuevo, donde) = Vivo.bloqueNuevo(valor.text, s2.bloque)
            cambia(valor.copy(text = conNuevo))
            sitio = donde
            convertirBloque(tipo)
            return
        }
        val r = Comandos.elegir(valor.text, valor.text.length, tipo)
        cambia(TextFieldValue(r.text, TextRange(r.selStart, r.selEnd)))
    }

    // El título de la barra es el de la nota, si lo tiene: se mira solo la
    // primera línea, que es barato y es donde va un título.
    // Un archivo se llama como el archivo (sin la extensión, que ya dice su icono).
    val titulo = remember(valor.text, archivo) {
        if (archivo != null) archivo.substringBeforeLast('.').ifBlank { archivo }
        else valor.text.lineSequence().firstOrNull()?.trim()
            ?.takeIf { it.startsWith("#") }
            ?.trimStart('#')?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Nota"
    }

    // **Sin barra fija** (27-oct-2026): la nota a pantalla completa, como los demás visores; lo que
    // estaba en la barra va en la pastilla de cristal ([PastillaDeLector]) y en su ⋮, y atrás guarda.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Mirando: tocar saca la pastilla y el riel, y mover los esconde. Solo mira el gesto.
                    .then(
                        if (viendo) Modifier.toqueOMovida(
                            alTocar = { aLaVista = true; toques++ },
                            alMover = { if (!anotandoLaNota && !marcosDeLaNota.activo) aLaVista = false }
                        ) else Modifier
                    )
                    .verticalScroll(desplazado)
                    .padding(top = with(densidadDeLaPantalla) { margenArriba.toDp() })
                    .then(if (viendo) Modifier.navigationBarsPadding() else Modifier)
                    .padding(16.dp)
            ) {
                if (viendo && valor.text.isBlank()) {
                    // Una nota vacía se abre mirando, así que invita a escribir.
                    Text(
                        if (soloLeer) "Archivo vacío." else "Nota vacía. Toca para escribir.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().then(if (soloLeer) Modifier else Modifier.clickable { viendo = false }).padding(vertical = 24.dp)
                    )
                } else if (llano && viendo) {
                    // **Texto llano, tal cual** (27-oct-2026, el `html::llano` del PC): cada renglón
                    // donde está y sin Markdown —un `#` es un `#`—, con la letra y el tamaño de la vista.
                    // Los finales de línea de Windows (`\r\n`) se ven como uno, igual que en el PC.
                    Text(
                        remember(valor.text) { valor.text.replace("\r\n", "\n").replace('\r', '\n') },
                        style = TextStyle(fontFamily = letraDeLaNota, fontSize = vista.tamano.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (llano) {
                    // Escribiendo texto llano: un campo de texto sin más, sin bloques ni comandos.
                    BasicTextField(
                        value = valor,
                        onValueChange = { cambia(it) },
                        textStyle = TextStyle(fontFamily = letraDeLaNota, fontSize = vista.tamano.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (viendo) {
                    val bloques = remember(valor.text) { Markdown.parse(valor.text) }
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.forge.pixpin.motormd.LocalMediosTocables provides true,
                        com.forge.pixpin.motormd.LocalContextoDeLaNota provides contextoDeLaNota,
                        com.forge.pixpin.motormd.LocalVersionDeMedios provides repintadas,
                        com.forge.pixpin.motormd.LocalLetraDeLaNota provides letraDeLaNota,
                        // **El audio de la nota suena por el reproductor de siempre**, el
                        // mismo de la pantalla de la letra y el de la barra de abajo: así
                        // los minutos de cada párrafo pueden saltar dentro de él, que es lo
                        // que el usuario pidió (7-sep-2026). Ver [AudioDeLaNota].
                        com.forge.pixpin.motormd.LocalAudioDeLaNota provides elReproductorDeLaNota()
                    ) {
                        MarkdownText(blocks = bloques, baseSizeSp = vista.tamano)
                    }
                } else {
                    androidx.compose.runtime.CompositionLocalProvider(
                        com.forge.pixpin.motormd.LocalContextoDeLaNota provides contextoDeLaNota,
                        com.forge.pixpin.motormd.LocalVersionDeMedios provides repintadas,
                        com.forge.pixpin.motormd.LocalLetraDeLaNota provides letraDeLaNota
                    ) {
                    EditorVivo(
                        texto = valor.text,
                        sitio = sitio,
                        onTexto = { cambia(valor.copy(text = it)) },
                        onSitio = { sitio = it },
                        comentados = remember(fichero, valor.text) {
                            if (comentarios == null) emptyMap() else ComentariosDeNota.porBloque(fichero, valor.text)
                        },
                        onComentarios = { viendoComentarios = it },
                        onPasarATexto = { ruta ->
                            Toast.makeText(contextoDelEditor, "Pasando a texto…", Toast.LENGTH_SHORT).show()
                            com.forge.pixpin.guardados.Transcriptor.transcribir(contextoDelEditor, java.io.File(ruta)) { r ->
                                val texto = when (r) {
                                    is com.forge.pixpin.guardados.Transcriptor.Resultado.Texto ->
                                        r.porParrafos ?: com.forge.pixpin.guardados.Transcriptor.conTiempos(r.segmentos).ifBlank { r.texto }
                                    else -> null
                                }
                                if (texto.isNullOrBlank()) {
                                    Toast.makeText(contextoDelEditor, "No se pudo pasar a texto", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Debajo de su audio, como lo escribe el PC y la transcripción del chat.
                                    val doc = valor.text
                                    val i = doc.indexOf("](" + ruta + ")")
                                    val fin = if (i < 0) doc.length else doc.indexOf('\n', i).let { if (it < 0) doc.length else it }
                                    val nuevo = doc.substring(0, fin) + "\n\n" + texto.trim() + "\n" + doc.substring(fin)
                                    cambia(valor.copy(text = nuevo))
                                }
                            }
                        },
                        baseSizeSp = vista.tamano
                    )
                    }
                }
            }
            // **La tinta**: mirando, encima del texto y con él; anotando, el lienzo, y el texto lo sigue.
            if (viendo && anotandoLaNota) {
                @Suppress("UNUSED_EXPRESSION") tickDeLaTinta
                com.forge.pixpin.motor.DrawCanvas(
                    controller = tintaDeLaNota,
                    modifier = Modifier.fillMaxSize(),
                    imageProvider = { f -> tintaDeLaNota.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                    zoomBloqueado = true,
                    cambios = tickDeLaTinta,
                    onChange = { trazando ->
                        // Dos dedos mueven la nota: solo de alto, y sin salirse.
                        val vp = tintaDeLaNota.scene.viewport
                        val y = (margenArriba - vp.scrollY * densidadDeLaNota).toInt().coerceIn(0, desplazado.maxValue)
                        tintaDeLaNota.setViewport(vp.copy(scrollX = 0.0, scrollY = (margenArriba - y) / densidadDeLaNota))
                        alcanceDeMarcas.launch { desplazado.scrollTo(y) }
                        if (!trazando) tickDeLaTinta++
                    }
                )
            } else if (viendo && tintaDeLaNota.scene.elements.any { !it.isDeleted }) {
                val pintor = remember { com.forge.pixpin.motor.Renderer({ f -> tintaDeLaNota.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } }, com.forge.pixpin.motor.DrawFonts.provider(contextoDelEditor)) }
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION") tickDeLaTinta
                    val escena = tintaDeLaNota.scene.copy(viewport = vistaDeLaTinta())
                    drawContext.canvas.nativeCanvas.let { pintor.renderScene(it, escena, size.width.toDouble(), size.height.toDouble()) }
                }
            }
            if (viendo && !anotandoLaNota && (marcosDeLaNota.activo || marcosDeLaNota.lista.isNotEmpty())) CapaDeMarcos(
                marcosDeLaNota,
                aPantalla = { x, y, _ -> androidx.compose.ui.geometry.Offset((x * densidadDeLaNota).toFloat(), (y * densidadDeLaNota + margenArriba - desplazado.value).toFloat()) },
                aDocumento = { o -> PuntoDelDocumento(o.x / densidadDeLaNota, (o.y - margenArriba + desplazado.value) / densidadDeLaNota) },
                pxPorUnidad = { densidadDeLaNota },
                alIr = { m -> alcanceDeMarcas.launch { desplazado.animateScrollTo((m.y0 * densidadDeLaNota).toInt()) } },
                alImprimir = { imprimirLosMarcosDeLaNota() },
                modifier = Modifier.fillMaxSize().onGloballyPositioned { cajaDeLaNota = it.boundsInWindow() }
            )
            if (viendo && anotandoLaNota) {
                val ajustesDeLaNota by (contextoDelEditor.applicationContext as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
                // **El editor avanzado de todos los visores** (25-oct-2026). Ver [EditorAvanzadoDeVisor].
                EditorAvanzadoDeVisor(
                    capa = tintaDeLaNota, ajustes = ajustesDeLaNota,
                    permitidas = ajustesDeLaNota.formatoToolSet(com.forge.pixpin.motor.HerramientasPorSitio.Sitio.NOTA) - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                    grupos = ajustesDeLaNota.formatoGroupList(com.forge.pixpin.motor.HerramientasPorSitio.Sitio.NOTA)
                        .map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() },
                    noche = false, tick = tickDeLaTinta,
                    alListo = { dejarDeAnotarLaNota() },
                    cambiado = { tickDeLaTinta++ }
                )
            }
            // **El riel de la derecha**: marcador, lápiz (anotar encima) y marcos, como en los visores.
            // **Y se va con la pastilla** (27-oct-2026): al mover la nota, y vuelve al tocar. Ver [RielALaVista].
            if (viendo && !marcosDeLaNota.imprimiendo) RielDelVisor(
                visible = RielALaVista.visible(barras, anotandoLaNota, marcosDeLaNota.activo, poniendoMarca),
                alUsar = { toques++ },
                poniendoMarca = poniendoMarca, anotando = anotandoLaNota, marcando = marcosDeLaNota.activo,
                onMarcador = if (archivoDeMarcas == null) null else ({ dejarDeAnotarLaNota(); marcosDeLaNota.activo = false; poniendoMarca = !poniendoMarca }),
                onLapiz = {
                    if (anotandoLaNota) dejarDeAnotarLaNota()
                    else { poniendoMarca = false; marcosDeLaNota.activo = false; tintaDeLaNota.setViewport(vistaDeLaTinta()); anotandoLaNota = true }
                },
                onMarco = { dejarDeAnotarLaNota(); poniendoMarca = false; marcosDeLaNota.alternar() },
                imprimirMarcos = if (marcosDeLaNota.lista.isEmpty()) null else ({ imprimirLosMarcosDeLaNota() }),
                imprimirTodo = { imprimirLaNota() },
                marcas = if (!anotandoLaNota) marcadores.size else 0,
                emojiDe = { i -> marcadores[i].emoji },
                alElegirMarca = { i ->
                    marcadores.getOrNull(i)?.let { m -> alcanceDeMarcas.launch { desplazado.animateScrollTo((m.fraccion * desplazado.maxValue).toInt()) } }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
            if (poniendoMarca) ElegirEmojiDeMarca(Modifier.align(Alignment.BottomCenter).navigationBarsPadding(), onCerrar = { poniendoMarca = false }) { emoji ->
                poniendoMarca = false
                val f = if (desplazado.maxValue <= 0) 0f else desplazado.value.toFloat() / desplazado.maxValue
                marcadores = com.forge.pixpin.motor.Lectura.conMarcador(marcadores, f, emoji, System.currentTimeMillis())
                archivoDeMarcas?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribir(it, com.forge.pixpin.motor.Lectura.aTexto(marcadores)) }
            }
            // **La pastilla de cristal** (27-oct-2026), la de todos los visores: el icono «MD» y el
            // nombre, y los botones. Mirando: escribir, los comentarios, compartir y el ⋮. Escribiendo:
            // ver la nota, deshacer, rehacer, los comentarios, el ⋮ y guardar. Imprimir es mantener el
            // marco del riel. Anotando o con los marcos se aparta, como en los demás.
            // `pasos` está solo para que Compose vuelva a mirar deshacer y rehacer: el historial es
            // un objeto normal y no un estado observable.
            @Suppress("UNUSED_EXPRESSION") pasos
            val botonDeComentarios = if (comentarios == null) null else BotonDeLector(Icons.Filled.ChatBubbleOutline, "Comentarios", insignia = fichero.abiertos) { viendoComentarios = -1 }
            val guardarYCerrar = BotonDeLector(Icons.Filled.Check, "Guardar") {
                if (comentarios != null && fichero.comentarios.isNotEmpty()) cambiarComentarios(fichero, valor.text)
                onGuardar(valor.text)
            }
            PastillaDeLector(
                visible = barras && !anotandoLaNota && !marcosDeLaNota.activo && !marcosDeLaNota.imprimiendo,
                nombre = titulo,
                archivo = archivo ?: "nota.md",
                botones = if (viendo) listOfNotNull(
                    // **Escribir el texto** (el `EDIT_NOTE` del PC): el lápiz del riel es para anotar.
                    // Un adjunto del chat se mira y se anota, pero no se escribe ([soloLeer]).
                    if (soloLeer) null else BotonDeLector(Icons.Filled.EditNote, "Escribir") {
                        dejarDeAnotarLaNota(); marcosDeLaNota.activo = false; poniendoMarca = false; viendo = false
                    },
                    botonDeComentarios,
                    BotonDeLector(IconoDeCompartir, "Compartir") { CompartirNativo.texto(contextoDelEditor, valor.text, titulo); toques++ },
                    guardarYCerrar.takeIf { hayCambios }
                ) else listOfNotNull(
                    BotonDeLector(Icons.Filled.Visibility, "Ver la nota") { viendo = true },
                    // Deshacer y rehacer, como su `historyButtons`; solo cuando hay algo que deshacer o rehacer.
                    if (historial.puedeDeshacer) BotonDeLector(Icons.AutoMirrored.Filled.Undo, "Deshacer") {
                        historial.deshacer(valor.text)?.let {
                            valor = TextFieldValue(it, TextRange(it.length))
                            recolocar(it)
                            pasos++
                        }
                    } else null,
                    if (historial.puedeRehacer) BotonDeLector(Icons.AutoMirrored.Filled.Redo, "Rehacer") {
                        historial.rehacer(valor.text)?.let {
                            valor = TextFieldValue(it, TextRange(it.length))
                            recolocar(it)
                            pasos++
                        }
                    } else null,
                    botonDeComentarios,
                    guardarYCerrar
                ),
                // Lo de vez en cuando, detrás de los tres puntos. «Añadir a un proyecto» se fue
                // (27-oct-2026, el usuario): la nota vive en su chat.
                menu = { cerrar ->
                    masAcciones.forEach { (nombre, hacer) ->
                        DropdownMenuItem(text = { Text(nombre) }, onClick = { cerrar(); hacer(valor.text) })
                    }
                    DropdownMenuItem(text = { Text("Letra y tamaño") }, onClick = { cerrar(); eligiendoVista = true })
                    DropdownMenuItem(
                        text = { Text("Detalle") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { cerrar(); viendoDetalle = true }
                    )
                    // Con algo escrito se pregunta antes de tirarlo. Ver [confirmandoDescarte].
                    if (hayCambios) DropdownMenuItem(text = { Text("Descartar los cambios") }, onClick = { cerrar(); confirmandoDescarte = true })
                },
                alCambiarElMenu = { menuAbierto = it; toques++ },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            }

            // Texto llano: ni bloques, ni comandos, ni estilos; el teclado y ya.
            if (!viendo && !llano) run {
                // La lista de comandos va **encima** de la barra y por delante
                // del teclado: es lo que estás mirando mientras tecleas.
                val sugerencias = remember(consulta) {
                    if (consulta == null) emptyList() else Bloques.buscar(consulta!!)
                }
                if (sugerencias.isNotEmpty()) {
                    ListaDeComandos(sugerencias) { tipo -> insertarBloque(tipo) }
                }
                if (comentarios != null && !comentarios.roto && sitio != null) {
                    val s2 = sitio!!
                    // Con algo elegido se comenta eso; sin nada, la palabra del cursor (como el PC).
                    AssistChip(
                        onClick = {
                            val texto = valor.text
                            val (a1, b1) = if (!s2.seleccion.collapsed) {
                                com.forge.pixpin.motormd.Vivo.aCrudo(texto, s2.bloque, s2.seleccion.min) to
                                    com.forge.pixpin.motormd.Vivo.aCrudo(texto, s2.bloque, s2.seleccion.max)
                            } else {
                                val p = com.forge.pixpin.motormd.Vivo.aCrudo(texto, s2.bloque, s2.seleccion.start)
                                com.forge.pixpin.motormd.Comentarios.palabraEn(texto, p)?.let { it.first to it.last + 1 } ?: (p to p)
                            }
                            comentando = com.forge.pixpin.motormd.Comentarios.anclaDe(texto, a1, b1)
                        },
                        leadingIcon = { Icon(Icons.Filled.AddComment, null) },
                        label = { Text("Comentar") },
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // El cambio de panel es suyo: sin selección no hay nada que
                    // poner en negrita, y lo que quieres es empezar un bloque.
                    if (sitio != null && !sitio!!.seleccion.collapsed) {
                        val contenido = remember(valor.text, sitio) {
                            sitio?.let { Vivo.contenido(valor.text, it.bloque) }
                        }
                        BarraDeEstilosUi(
                            activos = remember(contenido, sitio) {
                                val c = contenido
                                val s2 = sitio
                                if (c == null || s2 == null) {
                                    emptySet()
                                } else {
                                    Inline.estilosDe(
                                        c.spans,
                                        s2.seleccion.min,
                                        s2.seleccion.max
                                    )
                                }
                            },
                            onEstilo = { kind -> aplicarEstilo(kind, null) },
                            onEnlace = { pidiendoUrl = true }
                        )
                    } else {
                        BarraDeFamiliasUi(
                            tipoActual = remember(valor.text, sitio) {
                                sitio?.let { Vivo.tipo(valor.text, it.bloque) }
                            },
                            onConvertir = { tipo -> convertirBloque(tipo) },
                            onInsertar = { tipo -> insertarBloque(tipo) },
                            onCatalogo = { viendoCatalogo = true },
                            onBorrarBloque = sitio?.let {
                                {
                                    val (doc, donde) = Vivo.borrarBloque(valor.text, it.bloque)
                                    cambia(valor.copy(text = doc))
                                    sitio = donde
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    /** Mete [md] como renglones suyos detrás del bloque activo, quitando el `/comando` a medias. */
    fun meterRenglones(md: String) {
        var texto = valor.text
        var cursor = valor.selection.start
        Comandos.consulta(texto, cursor)?.let { q ->
            val desde = cursor - q.length - 1
            texto = texto.removeRange(desde, cursor); cursor = desde
        }
        val donde = sitio?.let { Vivo.trozos(texto).getOrNull(it.bloque)?.hasta } ?: texto.length
        val antes = texto.substring(0, donde.coerceIn(0, texto.length))
        val salto = if (antes.isEmpty() || antes.endsWith("\n")) "" else "\n"
        val nuevo = antes + salto + md + "\n" + texto.substring(donde.coerceIn(0, texto.length))
        cambia(TextFieldValue(nuevo, TextRange((antes + salto + md).length)))
    }
    val alcanceDeElegir = androidx.compose.runtime.rememberCoroutineScope()
    when (eligiendo) {
        TipoDeBloque.PAGINA_VIVA, TipoDeBloque.ENLACE_HOJA -> {
            val comoPagina = eligiendo == TipoDeBloque.PAGINA_VIVA
            DialogoDeHojas(
                titulo = if (comoPagina) "Página de un proyecto" else "Enlace a una hoja",
                onCerrar = { eligiendo = null }
            ) { p, h ->
                eligiendo = null
                if (!comoPagina) meterRenglones(ElegirParaLaNota.enlace(p, h))
                else alcanceDeElegir.launch {
                    val md = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { ElegirParaLaNota.paginaViva(contextoDelEditor, p, h) }.getOrNull()
                    }
                    // Una tabla o una nota no se pintan aquí: van como enlace, que también se abre.
                    meterRenglones(md ?: ElegirParaLaNota.enlace(p, h))
                }
            }
        }
        TipoDeBloque.DEL_CHAT -> {
            val app = contextoDelEditor.applicationContext as PixPinApp
            DialogoDelChat(proyecto = proyectoDeLaNota, onCerrar = { eligiendo = null }) { m ->
                eligiendo = null
                meterRenglones(ElegirParaLaNota.delChat(m, app.proyectos.porId(m.proyecto)))
            }
        }
        else -> {}
    }

    if (eligiendoVista) {
        DialogoDeVista(vista, onCerrar = { eligiendoVista = false }) { v ->
            VistaDeNotas.guardar(contextoDelEditor, idDeLaNota, v)
            vista = v
            eligiendoVista = false
        }
    }
    if (viendoComentarios != null && comentarios != null) {
        PanelDeComentarios(
            fichero = fichero, texto = valor.text, quien = comentarios.quien(), soloLectura = comentarios.roto,
            enfoque = viendoComentarios?.takeIf { it >= 0 },
            onCambio = { cambiarComentarios(it, valor.text) },
            onIrA = { r ->
                // Al bloque de su texto, con lo comentado elegido si cae dentro.
                val trozos = com.forge.pixpin.motormd.Vivo.trozos(valor.text)
                val b = com.forge.pixpin.motormd.trozoEn(trozos, r.first)
                sitio = com.forge.pixpin.motormd.Sitio(b)
                viendoComentarios = null
            },
            onCerrar = { viendoComentarios = null }
        )
    }
    comentando?.let { ancla ->
        DialogoDeComentar(ancla.cita, onCerrar = { comentando = null }) { t ->
            val c = comentarios
            if (c != null) {
                com.forge.pixpin.motormd.Comentarios.nuevo(fichero, ancla, c.quien(), System.currentTimeMillis(), t)?.let { (f, _) ->
                    cambiarComentarios(f, valor.text)
                }
            }
            comentando = null
        }
    }

    // **Preguntar antes de tirar lo escrito.** Es lo único que separa un toque
    // sin querer de perder una nota entera.
    if (confirmandoDescarte) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmandoDescarte = false },
            title = { Text("¿Descartar lo escrito?") },
            text = { Text("Se perderá lo que has escrito desde que abriste la nota.") },
            confirmButton = {
                TextButton(onClick = { confirmandoDescarte = false; onDescartar() }) {
                    Text("Descartar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmandoDescarte = false }) {
                    Text("Seguir escribiendo")
                }
            }
        )
    }

    if (viendoAdjuntar) {
        HojaDeAdjuntar(
            onCerrar = { viendoAdjuntar = false },
            onElegir = { tipo ->
                viendoAdjuntar = false
                insertarBloque(tipo)
            }
        )
    }

    if (viendoCatalogo) {
        CatalogoDeBloques(
            onCerrar = { viendoCatalogo = false },
            onElegir = { tipo ->
                viendoCatalogo = false
                insertarBloque(tipo)
            }
        )
    }

    if (pidiendoUrl) {
        DialogoDeEnlace(
            onCerrar = { pidiendoUrl = false },
            onAceptar = { url ->
                aplicarEstilo(SpanKind.LINK, url)
                pidiendoUrl = false
            }
        )
    }
}

/** Lo que ocupa la pastilla de arriba con su aire (8 dp encima, 38 dp de botón y un poco debajo). */
private val ALTO_DE_LA_PASTILLA = 56.dp

/**
 * El diálogo de enlace de Telegram (`EditTextCaption.makeSelectedUrl`).
 *
 * Dos detalles suyos que valen lo que cuestan: el campo empieza con `https://`
 * escrito, y hay un **botón de pegar que solo asoma cuando el campo está intacto
 * y el portapapeles tiene algo**. El camino real es copiar la dirección en el
 * navegador, volver, seleccionar la palabra y darle a enlace; con el botón eso
 * son dos toques y sin él es abrir el teclado y buscar el menú de pegar.
 */
@Composable
private fun DialogoDeEnlace(onCerrar: () -> Unit, onAceptar: (String) -> Unit) {
    val contexto = LocalContext.current
    val porDefecto = "https://"
    var url by remember { mutableStateOf(porDefecto) }

    val hayQuePegar = remember(url) {
        if (url.isNotEmpty() && url != porDefecto) {
            false
        } else {
            val cb = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.hasPrimaryClip() == true
        }
    }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Enlace") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("Dirección") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (hayQuePegar) {
                    TextButton(onClick = {
                        val cb = contexto.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        val texto = runCatching {
                            cb?.primaryClip?.getItemAt(0)?.coerceToText(contexto)?.toString()
                        }.getOrNull()
                        if (!texto.isNullOrEmpty()) url = texto
                    }) {
                        Text("Pegar")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Solo el prefijo no es una dirección: se trata como vacío y el
                // enlace queda con los paréntesis listos para rellenar.
                onAceptar(if (url == porDefecto) "" else url.trim())
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

/** Qué se le pide al selector de archivos según el bloque. */
private fun filtroDe(tipo: TipoDeBloque): String = when (tipo) {
    TipoDeBloque.IMAGEN -> "image/*"
    TipoDeBloque.VIDEO -> "video/*"
    TipoDeBloque.AUDIO -> "audio/*"
    else -> "*/*"
}

/**
 * La lista que sale al teclear `/`, como su `RichCommandSuggestions`.
 *
 * Cada fila lleva el icono, el nombre y **el atajo a la derecha**, tal como la
 * suya (`RichCommand.View`: icono, texto, espacio elástico, texto2). Ese atajo a
 * la derecha es lo que hace que la lista se deje de usar: la ves tres veces,
 * aprendes que la tabla es `/tabla`, y a la cuarta ya no miras.
 *
 * Va apoyada abajo y crece hacia arriba, porque nace justo encima de la barra.
 */
@Composable
private fun ListaDeComandos(bloques: List<Bloque>, onElegir: (TipoDeBloque) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(max = 220.dp)
    ) {
        LazyColumn {
            items(bloques) { bloque ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onElegir(bloque.tipo) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconoDeBloque(bloque.tipo),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(bloque.nombre, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = bloque.atajos.first(),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * El catálogo entero, tras el `+`.
 *
 * Es la misma lista que sale tecleando, y a propósito: quien la abre por el
 * botón ve el atajo de cada bloque a la derecha y la próxima vez ya no necesita
 * el botón. Enseñar el atajo donde se busca la función es lo que convierte un
 * menú en algo que se deja de usar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogoDeBloques(onCerrar: () -> Unit, onElegir: (TipoDeBloque) -> Unit) {
    ModalBottomSheet(onDismissRequest = onCerrar) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(Bloques.todos) { bloque ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onElegir(bloque.tipo) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconoDeBloque(bloque.tipo),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(bloque.nombre, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = bloque.atajos.first(),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * La hoja de adjuntar, tras el botón propio de la barra.
 *
 * Telegram abre aquí su `ChatAttachAlertRichLayout`, una rejilla con la galería
 * dentro. Esta es más sosa a propósito: son cuatro filas que llevan al selector
 * del sistema. Enseñar la galería dentro de la nota obligaría a pedir permiso de
 * fotos para poder adjuntar un PDF, y el selector del sistema no pide ninguno.
 *
 * Las cuatro son las suyas —foto, vídeo, audio— más el archivo suelto, que en un
 * documento hace más falta que en un artículo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaDeAdjuntar(onCerrar: () -> Unit, onElegir: (TipoDeBloque) -> Unit) {
    val opciones = listOf(
        TipoDeBloque.IMAGEN,
        TipoDeBloque.VIDEO,
        TipoDeBloque.AUDIO,
        TipoDeBloque.ARCHIVO
    )
    ModalBottomSheet(onDismissRequest = onCerrar) {
        Column(Modifier.navigationBarsPadding()) {
            opciones.forEach { tipo ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onElegir(tipo) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = iconoDeBloque(tipo),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(Bloques.de(tipo).nombre, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * **El puente entre una nota y el reproductor de la aplicación.**
 *
 * El Markdown no sabe de reproductores: pide un [com.forge.pixpin.motormd.AudioDeLaNota] y
 * aquí se le da uno hecho con el [Reproductor] de siempre. Se rehace cuando cambia lo que
 * suena, que es lo que hace que el icono pase de «tocar» a «parar» solo.
 */
@Composable
private fun elReproductorDeLaNota(): com.forge.pixpin.motormd.AudioDeLaNota {
    val estado by com.forge.pixpin.guardados.Reproductor.estado.collectAsState()
    return remember(estado.ruta, estado.sonando, estado.duracionMs) {
        object : com.forge.pixpin.motormd.AudioDeLaNota {
            override fun alternar(ruta: String) {
                com.forge.pixpin.guardados.Reproductor.alternar(ruta, java.io.File(ruta).name)
            }

            override fun saltar(ruta: String, ms: Int) {
                val r = com.forge.pixpin.guardados.Reproductor
                if (r.estado.value.ruta != ruta) r.cargar(ruta, java.io.File(ruta).name, arrancar = false)
                val total = r.estado.value.duracionMs
                if (total > 0) r.irA(ms.toFloat() / total)
                if (!r.estado.value.sonando) r.seguir()
            }

            override fun sonando(ruta: String): Boolean =
                estado.ruta == ruta && estado.sonando
        }
    }
}
