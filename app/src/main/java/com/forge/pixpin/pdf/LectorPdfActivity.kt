package com.forge.pixpin.pdf

import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import com.forge.pixpin.ui.theme.cristal
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import com.forge.pixpin.motor.Lectura
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.forge.pixpin.guardados.aPantallaCompleta
import com.forge.pixpin.motor.PdfDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * **Ver un PDF y ya.**
 *
 * Un PDF guardado en la conversación se abría **fuera de PixPin**, con `ACTION_VIEW`: salías de
 * la aplicación, esperabas a que arrancara otra, y para volver había que deshacer el camino.
 * Para lo que se hace de verdad —mirar un plano un momento y seguir— eso es carísimo. Lo pidió
 * el usuario el 8-sep-2026: «un lector ligero que solo me permita ver el PDF, de forma rápida
 * sencilla y ligera».
 *
 * **Sin ninguna librería.** Se miró MuPDF, que es lo que se suele traer para esto, y tiene dos
 * pegas: su licencia es AGPL y PixPin es MIT —usarla obligaría a cambiar la licencia de toda la
 * aplicación— y son megabytes de APK para hacer algo que Android ya trae. `PdfRenderer` está en
 * el sistema y ya se usa aquí para las miniaturas y para el papel del editor, así que este
 * lector cuesta **cero bytes**. Lo confirmó el usuario: «no traigas ni una librería extra».
 *
 * Lo que hace y lo que no: pasar hojas y acercarse. No anota —para eso está el editor— ni busca
 * texto ni guarda marcas. Un visor que hace tres cosas se abre al instante; uno que las hace
 * todas es otra aplicación.
 */
class LectorPdfActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"

        private const val EXTRA_ANOTAR = "anotar"
        private const val EXTRA_MENSAJE = "mensaje"

        /**
         * Con [anotando], se abre ya con el editor rápido puesto sobre la primera página. [ruta] puede
         * ser también un Word o un PowerPoint (11-oct-2026): sus hojas maquetadas como en Office
         * ([com.forge.pixpin.office.DocumentoOffice]); [mensaje], el del chat del que sale (para su
         * «Modo lectura»).
         */
        fun abrir(context: Context, ruta: String, nombre: String, anotando: Boolean = false, mensaje: String? = null) {
            context.startActivity(
                Intent(context, LectorPdfActivity::class.java)
                    .putExtra(EXTRA_RUTA, ruta)
                    .putExtra(EXTRA_NOMBRE, nombre)
                    .putExtra(EXTRA_ANOTAR, anotando)
                    .putExtra(EXTRA_MENSAJE, mensaje)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // **Sin la barra de estado.** Un plano se mira a pantalla completa: la hora y la
        // batería encima de la primera línea de un plano no aportan nada y tapan. Se enseñan
        // deslizando desde el borde, como en el editor. Ver [aPantallaCompleta].
        aPantallaCompleta()
        val ruta = intent?.getStringExtra(EXTRA_RUTA)
        if (ruta == null) { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty()
        // **En la multitarea, como una pestaña más** (19-sep-2026). Ver [com.forge.pixpin.ui.ConLaRueda].
        setContent {
            com.forge.pixpin.ui.theme.PixPinTheme {
                com.forge.pixpin.ui.ConLaRueda(com.forge.pixpin.data.Abiertos.dePdf(ruta, nombre.ifBlank { "PDF" })) {
                    Lector(ruta, nombre)
                }
            }
        }
    }

    /**
     * **El engranaje del PDF**, con los mismos apartados que el del lector de Word
     * ([com.forge.pixpin.ui.VisorHtmlActivity]): Vista, Lectura, Voz y Documento. La letra no, que
     * un PDF de hojas no se puede cambiar; para eso está «Ver como texto».
     */
    @Composable
    private fun EngranajeDelPdf(
        onCerrar: () -> Unit,
        sinLado: Boolean, onSinLado: (Boolean) -> Unit,
        invertido: Boolean, onInvertido: (Boolean) -> Unit,
        izquierda: Boolean, derecha: Boolean, onIzquierda: () -> Unit, onDerecha: () -> Unit,
        comoTexto: (escuchar: Boolean) -> Unit,
        onExportar: () -> Unit, onAlProyecto: (() -> Unit)?, onQuitarMarcadores: (() -> Unit)?,
        /** Lo de un Word o un PowerPoint maquetado: su «Modo lectura» (null, no hay), sin voz si es un PowerPoint. */
        comoTextoRotulo: Pair<String, String?>? = "Ver como texto" to "como un Word",
        conVoz: Boolean = true,
        onPresentar: (() -> Unit)? = null,
        onImprimir: (() -> Unit)? = null,
        onCopiarHoja: (() -> Unit)? = null
    ) {
        val prefs = remember { getSharedPreferences("lectura", MODE_PRIVATE) }
        com.forge.pixpin.ui.EngranajeDeLector(onCerrar) {
            com.forge.pixpin.ui.ApartadoDeLector("Vista")
            comoTextoRotulo?.let { (r, d) -> com.forge.pixpin.ui.FilaDeLector(r, d) { comoTexto(false) } }
            onPresentar?.let { com.forge.pixpin.ui.FilaDeLector("Presentar", "Las diapositivas a pantalla completa", it) }
            onCopiarHoja?.let { com.forge.pixpin.ui.FilaDeLector("Copiar el texto de esta hoja", "Mantener el dedo sobre un renglón copia ese renglón", it) }

            com.forge.pixpin.ui.ApartadoDeLector("Lectura")
            com.forge.pixpin.ui.InterruptorDeLector("Bloquear el desplazamiento de lado", "El dedo solo sube y baja; a lo ancho se queda donde lo dejes", sinLado, onSinLado)
            if (PUEDE_INVERTIR) com.forge.pixpin.ui.InterruptorDeLector("Invertir colores", "Hojas oscuras para leer de noche; los colores conservan su tono", invertido, onInvertido)
            com.forge.pixpin.ui.InterruptorDeLector("Espacio a la izquierda", "Papel en blanco para anotar", izquierda) { onIzquierda() }
            com.forge.pixpin.ui.InterruptorDeLector("Espacio a la derecha", null, derecha) { onDerecha() }

            if (conVoz) {
                com.forge.pixpin.ui.ApartadoDeLector("Voz")
                com.forge.pixpin.ui.FilaDeLector(if (comoTextoRotulo?.first == "Ver como texto") "Escuchar este PDF" else "Escuchar", null) { comoTexto(true) }
                var enLinea by remember { mutableStateOf(prefs.getBoolean("voz:enLinea", false)) }
                com.forge.pixpin.ui.InterruptorDeLector("Voces en línea de Google", "Suenan mejor; el texto se manda a Google", enLinea) {
                    enLinea = it; prefs.edit().putBoolean("voz:enLinea", it).apply()
                }
                var microsoft by remember { mutableStateOf(prefs.getBoolean("voz:edge", false)) }
                com.forge.pixpin.ui.InterruptorDeLector("Voces de Microsoft", "Muy naturales y gratis; no es un servicio oficial y el texto va a Microsoft", microsoft) {
                    microsoft = it; prefs.edit().putBoolean("voz:edge", it).apply()
                }
                var auricular by remember { mutableStateOf(prefs.getBoolean("voz:auricular", false)) }
                com.forge.pixpin.ui.InterruptorDeLector("Por el auricular de las llamadas", "Para sitios con ruido: el teléfono a la oreja", auricular) {
                    auricular = it; prefs.edit().putBoolean("voz:auricular", it).apply()
                }
            }

            com.forge.pixpin.ui.ApartadoDeLector("Documento")
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                com.forge.pixpin.ui.BotonDeTextoDeLector("Exportar", onExportar)
                if (onAlProyecto != null) com.forge.pixpin.ui.BotonDeTextoDeLector("Al proyecto", onAlProyecto)
                if (onImprimir != null) com.forge.pixpin.ui.BotonDeTextoDeLector("Imprimir", onImprimir)
                if (onQuitarMarcadores != null) com.forge.pixpin.ui.BotonDeTextoDeLector("Quitar marcadores", onQuitarMarcadores)
            }
        }
    }

    // ---- Lo de un Word o un PowerPoint maquetado (11-oct-2026). Ver [com.forge.pixpin.office.DocumentoOffice]. ----

    /** El «Modo lectura»: el Word como texto en el visor de documentos (el lector de antes), con su voz y su tinta. */
    private fun modoLectura(ruta: String, nombre: String, escuchar: Boolean = false) =
        com.forge.pixpin.office.AbrirOffice.wordComoTexto(this, ruta, nombre, intent?.getStringExtra(EXTRA_MENSAJE), escuchar) {
            com.forge.pixpin.ui.AbrirCon.abrir(this, java.io.File(it))
        }

    private fun copiar(texto: String, aviso: String) {
        if (texto.isBlank()) { android.widget.Toast.makeText(this, "No hay texto aquí", android.widget.Toast.LENGTH_SHORT).show(); return }
        val portapapeles = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        portapapeles.setPrimaryClip(android.content.ClipData.newPlainText("PixPin", texto))
        // Android 13 ya enseña su propio aviso al copiar.
        if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(this, aviso, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Un enlace del documento: solo la web y el correo (lo demás de un archivo ajeno no se abre a ciegas). */
    private fun abrirEnlace(destino: String) {
        val d = destino.trim()
        if (!(d.startsWith("http://", true) || d.startsWith("https://", true) || d.startsWith("mailto:", true))) {
            android.widget.Toast.makeText(this, d, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(d))) }
            .onFailure { android.widget.Toast.makeText(this, "Nada en el teléfono abre este enlace", android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** Imprimir: el documento en vectores con lo anotado encima, por el servicio de impresión del teléfono. */
    /**
     * **Imprimir el PDF entero con lo anotado** (mantener el marco del riel, 25-oct-2026): las hojas
     * del documento con su tinta encima, en vectores.
     */
    private fun imprimirElPdf(ruta: String, nombre: String, escenaDe: (Int) -> com.forge.pixpin.motor.Scene?) {
        android.widget.Toast.makeText(this, "Preparando la impresión…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val original = java.io.File(ruta).readBytes()
                    com.forge.pixpin.motor.PdfConAnotaciones.hacer(
                        applicationContext, original, escenaDe, izquierda = false, derecha = false,
                        marcadores = emptyList(), hojaPintada = { i -> PdfDoc.render(ruta, i, PdfDoc.PAGE_WIDTH) }
                    ) ?: original
                }.getOrNull()
            }
            if (bytes == null) android.widget.Toast.makeText(this@LectorPdfActivity, "No se pudo preparar la impresión", android.widget.Toast.LENGTH_SHORT).show()
            else com.forge.pixpin.guardados.Imprimir.pdf(this@LectorPdfActivity, bytes, nombre.substringBeforeLast('.'))
        }
    }

    private fun imprimirOffice(ruta: String, nombre: String, escenaDe: (Int) -> com.forge.pixpin.motor.Scene?) {
        android.widget.Toast.makeText(this, "Preparando la impresión…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val d = com.forge.pixpin.office.HojasOffice.de(this@LectorPdfActivity, ruta) ?: return@runCatching null
                    val pdf = com.forge.pixpin.office.OfficeAPdf.enCache(this@LectorPdfActivity, d) ?: return@runCatching null
                    val original = pdf.readBytes()
                    com.forge.pixpin.motor.PdfConAnotaciones.hacer(
                        applicationContext, original, escenaDe, izquierda = false, derecha = false,
                        marcadores = emptyList(), hojaPintada = { i -> PdfDoc.render(pdf.path, i, PdfDoc.PAGE_WIDTH) }
                    ) ?: original
                }.getOrNull()
            }
            if (bytes == null) android.widget.Toast.makeText(this@LectorPdfActivity, "No se pudo preparar la impresión", android.widget.Toast.LENGTH_SHORT).show()
            else com.forge.pixpin.guardados.Imprimir.pdf(this@LectorPdfActivity, bytes, nombre.substringBeforeLast('.'))
        }
    }

    /**
     * Lo que se comparte de un Word o un PowerPoint: el **PDF en vectores** (con lo anotado, o limpio
     * con el interruptor), **la página web** de sus hojas con lo anotado, **una hoja como imagen**,
     * **el archivo original**, tal cual, y su **.pixpin** (14-oct-2026, como el PC).
     */
    private fun formatosDeOffice(
        ruta: String, nombre: String, titulo: String, escenaDe: (Int) -> com.forge.pixpin.motor.Scene?,
        espacios: () -> Int, marcas: () -> List<com.forge.pixpin.motor.Marca>, cuantas: Int, mirando: Int
    ): com.forge.pixpin.ui.Compartible {
        val c = this
        val pdf: suspend () -> String? = {
            withContext(Dispatchers.IO) {
                com.forge.pixpin.office.HojasOffice.de(c, ruta)?.let { com.forge.pixpin.office.OfficeAPdf.enCache(c, it) }?.path
            }
        }
        return com.forge.pixpin.ui.Compartible(
            titulo, paginas = com.forge.pixpin.ui.ExportarVisor.paginasDe(cuantas), marcadas = setOf("p$mirando"), formatos = listOf(
                com.forge.pixpin.ui.ExportarPdfAnotado.formatoPdf(
                    c, ruta, titulo, escenaDe, espacios = espacios, marcas = marcas,
                    prepararBase = {
                        withContext(Dispatchers.IO) {
                            com.forge.pixpin.office.HojasOffice.de(c, ruta)?.let { com.forge.pixpin.office.OfficeAPdf.enCache(c, it) }?.path
                        }
                    }
                ),
                com.forge.pixpin.ui.Compartible.Formato(
                    "original", androidx.compose.material.icons.Icons.Filled.Description, nombre.substringAfterLast('.').uppercase().ifBlank { "Original" },
                    com.forge.pixpin.ui.Compartible.NINGUNA,
                    generar = {
                        withContext(Dispatchers.IO) {
                            val destino = java.io.File(java.io.File(c.cacheDir, "share").apply { mkdirs() }, nombre.replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80).ifBlank { "documento" })
                            java.io.File(ruta).copyTo(destino, overwrite = true)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(nombre.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                            com.forge.pixpin.ui.Compartible.Salida(destino, mime, "El documento tal cual, sin lo anotado")
                        }
                    }
                ),
                com.forge.pixpin.ui.Compartible.Formato(
                    "web-pdf-anotado", androidx.compose.material.icons.Icons.Filled.Language, "Página web", com.forge.pixpin.ui.Compartible.NINGUNA,
                    generar = {
                        val base = pdf() ?: return@Formato null
                        val funciones = (c.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.funcionesWeb
                        com.forge.pixpin.ui.ExportarPdfAnotado.hacer(c.applicationContext, base, titulo, cuantas, escenaDe, funciones)?.let {
                            com.forge.pixpin.ui.Compartible.Salida(it, com.forge.pixpin.motor.ExportarHtml.MIME_TYPE, "Las hojas con lo anotado; se sigue anotando")
                        }
                    }
                )
            ) + imagenesDeHojas(titulo, pdf, escenaDe) + com.forge.pixpin.ui.ExportarVisor.pixpin(c, ruta, nombre)
        )
    }

    /** **Una hoja como imagen** (PNG o JPG), con lo anotado encima. [pdf] da el documento sin lo anotado dentro. */
    private fun imagenesDeHojas(titulo: String, pdf: suspend () -> String?, escenaDe: (Int) -> com.forge.pixpin.motor.Scene?): List<com.forge.pixpin.ui.Compartible.Formato> =
        com.forge.pixpin.ui.ExportarVisor.imagenes(
            this, { claves -> "$titulo - hoja ${(com.forge.pixpin.ui.ExportarVisor.paginaDeClave(claves.firstOrNull()) ?: 0) + 1}" }, com.forge.pixpin.ui.Compartible.UNA
        ) { claves ->
            val i = com.forge.pixpin.ui.ExportarVisor.paginaDeClave(claves.firstOrNull()) ?: return@imagenes null
            val base = pdf() ?: return@imagenes null
            com.forge.pixpin.ui.ExportarVisor.paginaConTinta(this, base, i, escenaDe(i))
        }

    @Composable
    private fun Lector(rutaPedida: String, nombre: String) {
        // **El del proyecto, con lo anotado**, si lo que se abrió es su copia limpia (el mensaje del chat).
        var ruta by remember(rutaPedida) { mutableStateOf(rutaPedida) }
        var cuantas by remember(rutaPedida) { mutableStateOf(-1) }
        // Los recortes de cada página (sublienzos mandados al chat). Ver [SublienzosDelPdf].
        var recortes by remember(rutaPedida) { mutableStateOf<Map<Int, List<SublienzosDelPdf.Recorte>>>(emptyMap()) }
        // **La página que se mira con sus sublienzos, a solas.** Antes cada página alejada se abría
        // dentro de la lista y podían quedar varias abiertas a la vez, cada una con sus
        // miniaturas vivas: con dos o tres ya se notaba el tirón (usuario, 13-sep-2026). Ahora
        // alejar una página la abre en una vista propia —solo esa hoja y lo suyo, que se acerca y
        // se pasea— con la lista escondida detrás. Atrás, o la flecha, vuelve. Ver [VistaDeLaPagina].
        var aSolas by remember(rutaPedida) { mutableStateOf<Int?>(null) }
        androidx.activity.compose.BackHandler(enabled = aSolas != null) { aSolas = null }
        // **El editor rápido: anotar sobre todas las hojas, en la misma lista** (20-sep-2026,
        // segunda vuelta). Ver [CapasDelPdf] y [CapaDePagina].
        var anotando by remember(rutaPedida) { mutableStateOf(false) }
        val capas = remember(rutaPedida) { CapasDelPdf(this@LectorPdfActivity, rutaPedida) }
        // Solo para llevar la herramienta y el estilo de la barra: cada hoja tiene su lienzo.
        val maestro = remember { com.forge.pixpin.motor.DrawController().also { it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) } }
        var tickDeLaBarra by remember { mutableStateOf(0) }
        // **Con lápiz, el dedo pasa las hojas** (11-oct-2026). Se enciende solo con el primer toque
        // de lápiz; el botón «Dedo» de la barra lo apaga (perder el lápiz no puede dejar sin anotar)
        // y entonces ya no se vuelve a encender solo hasta pedirlo.
        var conLapiz by remember { mutableStateOf(false) }
        var vioLapiz by remember { mutableStateOf(false) }
        var lapizAutomatico by remember { mutableStateOf(true) }
        val alLapiz = remember { { vioLapiz = true; if (lapizAutomatico) conLapiz = true } }
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        fun dejarDeAnotar() { anotando = false; capas.guardarTodo() }
        androidx.activity.compose.BackHandler(enabled = anotando) { dejarDeAnotar() }
        androidx.compose.runtime.DisposableEffect(capas) { onDispose { capas.guardarTodo() } }
        val quiereAnotarAlAbrir = remember { intent?.getBooleanExtra(EXTRA_ANOTAR, false) == true }
        LaunchedEffect(cuantas) { if (quiereAnotarAlAbrir && cuantas > 0) anotando = true }
        // **Un Word o un PowerPoint** (11-oct-2026): sus hojas maquetadas como en Office, con la
        // lectura del PC. Lo demás del lector no sabe que no es un PDF. Ver [com.forge.pixpin.office.DocumentoOffice].
        val esOffice = remember(rutaPedida) { com.forge.pixpin.office.HojasOffice.es(rutaPedida) }
        var esPresentacion by remember(rutaPedida) { mutableStateOf(com.forge.pixpin.office.DocumentoOffice.esPresentacion(rutaPedida)) }
        var presentando by remember(rutaPedida) { mutableStateOf<Int?>(null) }
        LaunchedEffect(rutaPedida) {
            if (!esOffice) return@LaunchedEffect
            val d = withContext(Dispatchers.IO) { com.forge.pixpin.office.HojasOffice.de(this@LectorPdfActivity, rutaPedida) }
            if (d == null) {
                // Lo de antes, de reserva: el Word como texto, el PowerPoint a un proyecto.
                android.widget.Toast.makeText(this@LectorPdfActivity, "No se pudo maquetar; se abre como antes", android.widget.Toast.LENGTH_SHORT).show()
                com.forge.pixpin.office.AbrirOffice.comoAntes(this@LectorPdfActivity, rutaPedida, nombre, intent?.getStringExtra(EXTRA_MENSAJE)) {
                    com.forge.pixpin.ui.AbrirCon.abrir(this@LectorPdfActivity, java.io.File(it))
                }
                finish()
                return@LaunchedEffect
            }
            esPresentacion = d.esPresentacion
            ruta = rutaPedida
            cuantas = d.cuantas()
            // Word: el resto se maqueta por detrás y las hojas van apareciendo.
            if (!d.terminado) withContext(Dispatchers.IO) { d.maquetarTodo { n -> runOnUiThread { if (n > cuantas) cuantas = n } } }
        }
        LaunchedEffect(rutaPedida) {
            if (esOffice) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val doc = runCatching { SublienzosDelPdf.documentoDe(this@LectorPdfActivity, rutaPedida) }.getOrDefault(rutaPedida)
                val n = PdfDoc.pageCount(doc)
                val r = runCatching { SublienzosDelPdf.de(this@LectorPdfActivity, doc) }.getOrDefault(emptyMap())
                // **Se enseña la copia limpia**, si el PDF es de un proyecto: lo anotado lo pinta
                // la capa de cada hoja, y sobre el documento ya anotado saldría dos veces.
                val limpio = capas.documentoLimpio()
                withContext(Dispatchers.Main) { ruta = limpio ?: doc; recortes = r; cuantas = n }
            }
        }
        // **El aumento es del documento, no de una página.** Acercarse para leer una cota y que
        // al pasar de hoja se volviera al tamaño de antes obligaría a repetir el gesto en cada
        // página; en un plano de veinte hojas, veinte veces.
        var zoom by remember { mutableStateOf(1f) }
        var desplazado by remember { mutableStateOf(Offset.Zero) }
        // **Mientras los dedos están encima no se rasteriza nada.**
        //
        // Es lo que hacen los visores de PDF que van bien, y lo que este no hacía: pedía la
        // página a más resolución **en mitad del pellizco**, y rasterizar una hoja grande son
        // decenas de megas y cientos de milisegundos. Lo que se enseña mientras tanto es el
        // mapa de bits de antes, estirado, que es gratis; cuando la mano para, se pide la
        // buena. Ver [Hoja].
        var pellizcando by remember { mutableStateOf(false) }
        var zoomFirme by remember { mutableStateOf(1f) }
        LaunchedEffect(zoom, pellizcando) {
            if (pellizcando) return@LaunchedEffect
            // Un respiro tras soltar: así dos pellizcos seguidos no piden dos rasterizados.
            kotlinx.coroutines.delay(180)
            zoomFirme = zoom
        }
        val estado = rememberLazyListState()
        val anchoPx = LocalWindowInfo.current.containerSize.width
        // **La hoja, en el centro, y un margen en blanco a cada lado donde anotar** (21-sep-2026).
        // La lista mide la hoja más sus dos márgenes —más ancha que la pantalla, centrada—; a su
        // tamaño se ve la hoja de borde a borde, como siempre, y los márgenes quedan a un gesto
        // de dos dedos. Alejándose cabe la hoja con **un** margen, el de un lado o el del otro
        // ([Lectura.ALEJADO_DEL_PDF]); entonces la lista crece de alto lo mismo que encoge, y
        // sigue llenando la pantalla.
        // **Los espacios para anotar, a botón** (22-sep-2026, pedido por el usuario). Antes salían
        // los dos siempre y el documento quedaba estrecho en medio sin haberlo pedido. Ahora se
        // añade el de la izquierda, el de la derecha o los dos, y se guardan por documento: el
        // botón de la esquina los enciende y los apaga. Ver [EspaciosDelLado].
        val prefsDelLector = remember { getSharedPreferences("lectura", MODE_PRIVATE) }
        val claveDeEspacios = remember(rutaPedida) { "pdf:$rutaPedida:espacios" }
        // **Si es un adjunto del chat, en un archivo con el código del mensaje**, que viaja al
        // sincronizar (28-sep-2026); si es el PDF de un proyecto, con el código del proyecto. Lo de
        // las preferencias, la primera vez, pasa ahí. Ver [com.forge.pixpin.sincro.AnotacionesDelAdjunto].
        val baseSuelta = remember(rutaPedida) { capas.baseSuelta() }
        val archivoDeEspacios = remember(baseSuelta) {
            baseSuelta?.let { b ->
                com.forge.pixpin.sincro.AnotacionesDelAdjunto.espacios(filesDir, b).also { f ->
                    if (prefsDelLector.contains(claveDeEspacios)) com.forge.pixpin.sincro.AnotacionesDelAdjunto.ponerSiFalta(f, prefsDelLector.getInt(claveDeEspacios, 0).toString())
                }
            }
        }
        var espacios by remember(rutaPedida) {
            mutableStateOf(
                archivoDeEspacios?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(it)?.trim()?.toIntOrNull()?.and(ESPACIO_IZQUIERDA or ESPACIO_DERECHA) }
                    ?: prefsDelLector.getInt(claveDeEspacios, 0)
            )
        }
        capas.espacios = espacios
        // **Al abrir, el marco a toda la tinta que aún no lo tiene** (30-sep-2026), con los
        // espacios de ahora: si luego llegan otros del PC, esa tinta ya no se corre. El PC hace
        // lo mismo antes de ampliar la hoja (`DondeVa::fijar_marco`).
        LaunchedEffect(cuantas) { if (cuantas > 0) capas.fijarLoViejo(cuantas) }
        fun ponerEspacios(v: Int) {
            // La tinta vieja, sin marco, se leía con los espacios de cada momento y por eso se
            // corría al ponerlos: antes de cambiarlos se le apunta el marco con los de ahora.
            capas.fijarLoViejo(cuantas)
            espacios = v
            capas.espacios = v
            archivoDeEspacios?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribir(it, v.toString()) } ?: prefsDelLector.edit().putInt(claveDeEspacios, v).apply()
        }
        val hayIzquierda = espacios and ESPACIO_IZQUIERDA != 0
        val hayDerecha = espacios and ESPACIO_DERECHA != 0
        val margenPx = anchoPx * Lectura.MARGEN_DEL_PDF
        val izquierdaPx = if (hayIzquierda) margenPx else 0f
        val derechaPx = if (hayDerecha) margenPx else 0f
        var altoDeLaCaja by remember { mutableStateOf(0) }
        val densidad = LocalDensity.current
        var compartiendo by remember { mutableStateOf(false) }
        // **Marcadores, los mismos que en el visor de Word y en el lienzo** (21-sep-2026). Aquí
        // un marcador es una página y en qué parte de ella; el riel lleva a ese punto dejándolo
        // arriba. Ver [com.forge.pixpin.motor.Marcas] y [com.forge.pixpin.ui.RielDeMarcas].
        val prefsDeMarcas = remember { getSharedPreferences("marcas", MODE_PRIVATE) }
        val claveDeMarcas = remember(rutaPedida) { "pdf:" + rutaPedida }
        val archivoDeMarcas = remember(baseSuelta) {
            baseSuelta?.let { b -> com.forge.pixpin.sincro.AnotacionesDelAdjunto.marcas(filesDir, b).also { com.forge.pixpin.sincro.AnotacionesDelAdjunto.ponerSiFalta(it, prefsDeMarcas.getString(claveDeMarcas, null)) } }
        }
        var marcas by remember(rutaPedida) {
            mutableStateOf(com.forge.pixpin.motor.Marcas.deTexto(
                archivoDeMarcas?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(it) } ?: prefsDeMarcas.getString(claveDeMarcas, null)
            ))
        }
        var poniendoMarca by remember { mutableStateOf(false) }
        // Un Word o un PowerPoint del chat: sus marcadores viajan en el archivo del documento (en
        // fracciones), así que se leen en cuanto se sabe cuántas hojas tiene.
        val marcasDeOffice = remember(rutaPedida) { capas.marcasDeOffice() }
        var leidasDeOffice by remember(rutaPedida) { mutableStateOf<List<com.forge.pixpin.motor.Lectura.Marcador>?>(null) }
        LaunchedEffect(marcasDeOffice, cuantas) {
            val f = marcasDeOffice ?: return@LaunchedEffect
            if (cuantas <= 0 || leidasDeOffice != null) return@LaunchedEffect
            val leidas = withContext(Dispatchers.IO) { com.forge.pixpin.motor.Lectura.deTexto(com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(f)) }
            leidasDeOffice = leidas
            // Las que ya había solo en el teléfono (antes no viajaban) se juntan con las del archivo.
            val propias = marcas.filter { m -> leidas.none { it.id == m.id } }
            marcas = com.forge.pixpin.motor.Marcas.enOrden(com.forge.pixpin.motor.Marcas.deFracciones(leidas, cuantas) + propias)
        }

        // ---- **Lo mismo que el lector de Word y libros** (23-sep-2026, pedido por el usuario: «todos
        // son visores rápidos: la misma interfaz y las mismas funciones»). La pastilla de arriba
        // que sale al tocar y se va sola, el engranaje por apartados, los mandos de los lados con el
        // candado, un dedo que lleva el documento de lado y la página que sube sola. Las piezas son
        // las comunes de [com.forge.pixpin.ui.MandosDeLector]; los ajustes, los mismos (`lectura`). ----
        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableStateOf(0) }
        LaunchedEffect(aLaVista, toques) { if (aLaVista) { kotlinx.coroutines.delay(3500); aLaVista = false } }
        LaunchedEffect(estado.isScrollInProgress) { if (estado.isScrollInProgress) aLaVista = false }
        var conElEngranaje by remember { mutableStateOf(false) }
        var quitandoMarcas by remember { mutableStateOf(false) }
        var sinLado by remember { mutableStateOf(prefsDelLector.getBoolean("sinLado", false)) }
        // Invertir los colores de las hojas (PC 11bd1f6, la «N» de los lectores), que es **el modo
        // oscuro de los visores** (PC 74801b4, luna/sol): un PDF con la matriz por píxel, un Word o
        // un PowerPoint pintado en oscuro por color ([com.forge.pixpin.office.Oscuro]). Sin tocar
        // nunca, el tema del teléfono.
        var invertido by remember { mutableStateOf((PUEDE_INVERTIR || esOffice) && com.forge.pixpin.office.Oscuro.activo(this@LectorPdfActivity)) }
        // La hoja de Office se vuelve a pintar al cambiar (la de un PDF no: la matriz va al pintarla).
        val oscuroOffice = esOffice && invertido
        var autoDesplazando by remember { mutableStateOf(false) }
        var autoEnMarcha by remember { mutableStateOf(false) }
        var palabrasPorMinuto by remember {
            mutableStateOf(com.forge.pixpin.motor.AutoDesplazar.valida(prefsDelLector.getInt("auto:ppm", com.forge.pixpin.motor.AutoDesplazar.POR_DEFECTO)))
        }
        var palabrasDelPdf by remember(rutaPedida) { mutableStateOf(-1) }
        fun alProyecto(despues: (Boolean) -> Unit = {}) {
            alcance.launch {
                val bien = withContext(Dispatchers.IO) { capas.pasarAProyecto(nombre.substringBeforeLast('.').ifBlank { "PDF" }, cuantas) }
                android.widget.Toast.makeText(
                    this@LectorPdfActivity, if (bien) "Ya está en proyectos, con lo anotado" else "No se pudo crear el proyecto",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                despues(bien)
            }
        }
        fun guardarLasMarcas() {
            val deOffice = marcasDeOffice
            if (deOffice != null && cuantas > 0) {
                val texto = com.forge.pixpin.motor.Lectura.aTexto(com.forge.pixpin.motor.Marcas.aFracciones(marcas, cuantas, leidasDeOffice.orEmpty()))
                com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribir(deOffice, texto)
                return
            }
            val texto = com.forge.pixpin.motor.Marcas.aTexto(marcas)
            archivoDeMarcas?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribir(it, texto) } ?: prefsDeMarcas.edit().putString(claveDeMarcas, texto).apply()
        }
        // **Los marcadores que ya trae el PDF** (30-sep-2026): los de su índice —un PDF exportado
        // desde PixPin los lleva ahí— pasan al riel la primera vez que se abre. Solo si aún no se
        // guardó ninguno: quitados a mano, no vuelven.
        LaunchedEffect(rutaPedida) {
            if (esOffice) return@LaunchedEffect
            val nuncaGuardadas = archivoDeMarcas?.exists()?.not() ?: !prefsDeMarcas.contains(claveDeMarcas)
            if (!nuncaGuardadas || marcas.isNotEmpty()) return@LaunchedEffect
            val delIndice = withContext(Dispatchers.IO) {
                runCatching {
                    com.forge.pixpin.motor.leerPdf(java.io.File(rutaPedida).readBytes())
                        ?.let { com.forge.pixpin.motor.PdfAnotado.marcadoresDelIndice(it, com.forge.pixpin.motor.Marcas.MAXIMO) }
                }.getOrNull().orEmpty()
            }
            if (delIndice.isEmpty() || marcas.isNotEmpty()) return@LaunchedEffect
            var lista = emptyList<com.forge.pixpin.motor.Marca>()
            val ahora = System.currentTimeMillis()
            for (m in delIndice) {
                // «⭐ Hoja 3» vuelve a ser una estrella; lo que no empiece por uno de los nuestros, un 🔖.
                val emoji = com.forge.pixpin.motor.Marcas.EMOJIS.firstOrNull { m.titulo.startsWith(it) } ?: com.forge.pixpin.motor.Marcas.EMOJIS.first()
                val (x, y) = com.forge.pixpin.motor.Marcas.enLaPagina(m.pagina, m.alto)
                lista = com.forge.pixpin.motor.Marcas.con(lista, x, y, emoji, ahora)
            }
            marcas = lista
            guardarLasMarcas()
        }
        /** Qué página se está mirando y por dónde va, para plantar ahí el marcador. */
        fun dondeEstoy(): Pair<Int, Double> {
            val primera = estado.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0 to 0.0
            val alto = primera.size.takeIf { it > 0 } ?: 1
            // Lo que asoma por arriba de esa hoja es lo que ya ha pasado de largo.
            return primera.index to ((-primera.offset).toDouble() / alto).coerceIn(0.0, 1.0)
        }
        /** A esa página y a esa altura, con la marca **arriba**: es lo que se fue a ver. */
        // ---- **Los marcos numerados y las vistas de los marcadores** (24-oct-2026, como el PC desde
        // el 19-oct). Van en las unidades del lector del PC (1400 de ancho por hoja, las hojas
        // seguidas con 18 de hueco: [com.forge.pixpin.motor.MarcosDelDocumento.ColumnaDeHojas]), así
        // un marco puesto aquí cae en el mismo sitio allí. Ver [com.forge.pixpin.ui.CapaDeMarcos]. ----
        val marcosDoc = remember(baseSuelta) {
            com.forge.pixpin.ui.MarcosDelVisor(baseSuelta?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivo(filesDir, it) })
        }
        val vistasDeMarcas = remember(baseSuelta) {
            com.forge.pixpin.ui.VistasDelVisor(baseSuelta?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivoDeVistas(filesDir, it) })
        }
        var columna by remember(ruta, cuantas) { mutableStateOf<com.forge.pixpin.motor.MarcosDelDocumento.ColumnaDeHojas?>(null) }
        LaunchedEffect(ruta, cuantas) {
            if (cuantas <= 0) return@LaunchedEffect
            columna = withContext(Dispatchers.IO) {
                com.forge.pixpin.motor.MarcosDelDocumento.ColumnaDeHojas((0 until cuantas).map { runCatching { medidaDeLaHoja(ruta, it) }.getOrNull() })
            }
        }
        // Atrás sale antes del modo de los marcos que del lector (el Esc del PC).
        androidx.activity.compose.BackHandler(enabled = marcosDoc.activo) { marcosDoc.activo = false }
        var anchoDeLaCaja by remember { mutableStateOf(0) }
        var cajaEnVentana by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        // Con una sola hoja, la lista lleva un hueco delante: la hoja es el elemento 1.
        val antesDeLaPrimera = if (cuantas == 1) 1 else 0
        /** El alto en píxeles de la lista (sin aumento) de la hoja [i]. */
        fun altoPxDe(c: com.forge.pixpin.motor.MarcosDelDocumento.ColumnaDeHojas, i: Int) =
            c.altos[i] / com.forge.pixpin.motor.MarcosDelDocumento.ANCHO_HOJA * anchoPx
        /** Dónde empieza la hoja [i] en la lista (sin aumento), aunque no se vea: de la primera que se ve. */
        fun arribaPxDe(c: com.forge.pixpin.motor.MarcosDelDocumento.ColumnaDeHojas, i: Int): Double? {
            val vistos = estado.layoutInfo.visibleItemsInfo.filter { it.index - antesDeLaPrimera in 0 until c.cuantas }
            vistos.firstOrNull { it.index - antesDeLaPrimera == i }?.let { return it.offset.toDouble() }
            val ref = vistos.firstOrNull() ?: return null
            var y = ref.offset.toDouble()
            var j = ref.index - antesDeLaPrimera
            while (j < i) { y += altoPxDe(c, j); j++ }
            while (j > i) { j--; y -= altoPxDe(c, j) }
            return y
        }
        /** El origen de la lista en la caja, su centro y lo que hay que sumar: lo de la `graphicsLayer`. */
        fun listaEnLaCaja(): Pair<Offset, Offset> {
            val anchoLista = anchoPx + izquierdaPx + derechaPx
            val altoLista = (altoDeLaCaja / zoom.coerceAtMost(1f)).coerceAtLeast(1f)
            val c = Offset(anchoDeLaCaja / 2f, altoDeLaCaja / 2f)
            return Offset((anchoDeLaCaja - anchoLista) / 2f, (altoDeLaCaja - altoLista) / 2f) to c
        }
        fun docAPantalla(x: Double, y: Double): Offset? {
            val c = columna ?: return null
            if (c.cuantas == 0) return null
            val i = c.en(y)
            val arriba = arribaPxDe(c, i) ?: return null
            val ly = arriba + (y - c.arriba[i]) / c.altos[i] * altoPxDe(c, i)
            val lx = izquierdaPx + x / com.forge.pixpin.motor.MarcosDelDocumento.ANCHO_HOJA * anchoPx
            val (origen, centro) = listaEnLaCaja()
            return Offset(
                centro.x + (origen.x + lx.toFloat() - centro.x) * zoom + desplazado.x,
                centro.y + (origen.y + ly.toFloat() - centro.y) * zoom + desplazado.y
            )
        }
        fun pantallaADoc(p: Offset): com.forge.pixpin.ui.PuntoDelDocumento? {
            val c = columna ?: return null
            if (c.cuantas == 0) return null
            val (origen, centro) = listaEnLaCaja()
            val lx = (p.x - desplazado.x - centro.x) / zoom + centro.x - origen.x
            val ly = ((p.y - desplazado.y - centro.y) / zoom + centro.y - origen.y).toDouble()
            // La hoja bajo el dedo: la última que empieza por encima.
            var i = 0
            for (j in 0 until c.cuantas) { val a = arribaPxDe(c, j) ?: break; if (a <= ly) i = j else break }
            val arriba = arribaPxDe(c, i) ?: return null
            return com.forge.pixpin.ui.PuntoDelDocumento(
                (lx - izquierdaPx).toDouble() / anchoPx * com.forge.pixpin.motor.MarcosDelDocumento.ANCHO_HOJA,
                c.arriba[i] + (ly - arriba) / altoPxDe(c, i) * c.altos[i]
            )
        }
        /** Píxeles de pantalla por unidad del documento, con el aumento de ahora. */
        fun pxPorUnidad() = anchoPx / com.forge.pixpin.motor.MarcosDelDocumento.ANCHO_HOJA * zoom
        /**
         * **Lleva la vista a que el punto ([x], [y]) del documento quede arriba a la izquierda** con
         * el aumento [z] (lo que guarda la vista de un marcador, `"zoom x y"`).
         */
        suspend fun irAlPunto(x: Double, y: Double, z: Float) {
            val c = columna ?: return
            if (c.cuantas == 0 || anchoDeLaCaja == 0) return
            val minimo = if (espacios == 0) 1f else Lectura.ALEJADO_DEL_PDF
            val nuevo = z.coerceIn(minimo, 6f)
            zoom = nuevo
            zoomFirme = nuevo
            kotlinx.coroutines.yield()
            val i = c.en(y.coerceAtLeast(0.0))
            val ly = (y.coerceAtLeast(0.0) - c.arriba[i]) / c.altos[i] * altoPxDe(c, i)
            val (origen, centro) = listaEnLaCaja()
            // Arriba de la caja cae, en la lista, la altura Y; ahí tiene que quedar el punto.
            val alto = centro.y - centro.y / nuevo - origen.y
            estado.scrollToItem(i + antesDeLaPrimera, (ly - alto).toInt().coerceAtLeast(0))
            val lx = izquierdaPx + x / com.forge.pixpin.motor.MarcosDelDocumento.ANCHO_HOJA * anchoPx
            val deLosLados = (izquierdaPx + derechaPx) / anchoPx.coerceAtLeast(1).toFloat()
            val topeX = (((1f + deLosLados) * nuevo - 1f) * anchoDeLaCaja / 2f).coerceAtLeast(0f)
            val dx = -centro.x - (origen.x + lx.toFloat() - centro.x) * nuevo
            desplazado = Offset(if (sinLado) desplazado.x else dx.coerceIn(-topeX, topeX), 0f)
        }
        /** **A un marco**: acercado hasta llenar la pantalla y centrado (las fichas y al imprimir). */
        suspend fun irAlMarco(m: com.forge.pixpin.motor.MarcosDelDocumento.Marco) {
            if (anchoDeLaCaja == 0 || altoDeLaCaja == 0) return
            val llenar = com.forge.pixpin.motor.MarcosDelDocumento.pxParaLlenar(m, anchoDeLaCaja.toDouble(), altoDeLaCaja.toDouble())
            val k = anchoPx / com.forge.pixpin.motor.MarcosDelDocumento.ANCHO_HOJA
            val z = (llenar / k).toFloat()
            val porUnidad = k * z.coerceIn(Lectura.ALEJADO_DEL_PDF, 6f)
            irAlPunto(m.centroX - anchoDeLaCaja / 2.0 / porUnidad, m.centroY - altoDeLaCaja / 2.0 / porUnidad, z)
        }
        fun imprimirLosMarcos() {
            alcance.launch {
                com.forge.pixpin.ui.ImprimirMarcos.imprimir(
                    this@LectorPdfActivity, marcosDoc, nombre.substringBeforeLast('.').ifBlank { "PDF" },
                    ir = { m -> irAlMarco(m) },
                    cajaEnVentana = { m ->
                        val a = docAPantalla(m.x0, m.y0); val b = docAPantalla(m.x1, m.y1)
                        if (a == null || b == null) null
                        else androidx.compose.ui.geometry.Rect(a, b).translate(cajaEnVentana.topLeft)
                    }
                )
            }
        }
        suspend fun irALaMarca(m: com.forge.pixpin.motor.Marca) {
            // **Con su vista** (zoom y sitio, `.vistas.json`): justo donde se puso.
            vistasDeMarcas.de(m.id)?.let { (z, x, y) -> irAlPunto(x, y, z.toFloat()); return }
            val pagina = com.forge.pixpin.motor.Marcas.paginaDe(m)
            val dentro = com.forge.pixpin.motor.Marcas.altoEnLaPagina(m)
            val alto = estado.layoutInfo.visibleItemsInfo.firstOrNull { it.index == pagina }?.size
                ?: estado.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
            estado.animateScrollToItem(pagina.coerceIn(0, (cuantas - 1).coerceAtLeast(0)), (dentro * alto).toInt())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1B1B1B))
                .onSizeChanged { altoDeLaCaja = it.height; anchoDeLaCaja = it.width }
                .onGloballyPositioned { cajaEnVentana = it.boundsInWindow() }
                // **El pellizco se coge solo con dos dedos, y entonces sí se consume.**
                //
                // Estaba con `detectTransformGestures` sobre la lista, y la lista se comía los
                // eventos: el zoom «funcionaba y luego dejaba de funcionar» según quién
                // ganase la carrera, que es lo que reportó el usuario el 9-sep-2026. Con un
                // dedo no se toca nada —la lista pasa hojas como siempre— y en cuanto baja el
                // segundo, este gesto se lo queda entero. Es el mismo patrón que
                // [com.forge.pixpin.ui.pinzaParaAmpliar] usa en la conversación.
                .pointerInput(recortes, aSolas) {
                    if (aSolas != null) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var cogido = false
                        var recorrido = 0f
                        /** Hasta dónde se puede llevar de lado el documento a este aumento, con los espacios que haya. */
                        fun topeX(z: Float): Float {
                            val lados = ((if (espacios and ESPACIO_IZQUIERDA != 0) 1 else 0) + (if (espacios and ESPACIO_DERECHA != 0) 1 else 0)) * Lectura.MARGEN_DEL_PDF
                            return (((1f + lados) * z - 1f) * size.width / 2f).coerceAtLeast(0f)
                        }
                        // **Alejar una página con recortes la abre a solas.** Solo con el documento
                        // a su tamaño: acercado, el pellizco sigue siendo el zoom.
                        var acumulado = 1f
                        var decidido = false
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedos = evento.changes.count { it.pressed }
                            if (dedos == 0) {
                                // Un toque (sin mover y sin pellizco): sale la pastilla.
                                if (!cogido && recorrido < viewConfiguration.touchSlop) { aLaVista = true; toques++ }
                                pellizcando = false
                                // **El imán del centro**: soltando cerca de la hoja, encaja en ella.
                                if (zoom in 0.999f..1.001f && kotlin.math.abs(desplazado.x) < size.width * 0.12f) desplazado = Offset.Zero
                                break
                            }
                            if (dedos >= 2) { cogido = true; pellizcando = true }
                            // **Un dedo, también de lado** (23-sep-2026: «con un solo dedo moverme a
                            // todas partes»). De alto lo lleva la lista, como siempre; de lado, si el
                            // documento es más ancho que la pantalla —acercado o con espacios—, este
                            // gesto. No se consume nada: la lista sigue recibiendo el dedo. Con el
                            // candado puesto, a lo ancho se queda donde está.
                            if (!cogido) {
                                val c = evento.changes.firstOrNull { it.pressed }
                                if (c != null && !anotando && !marcosDoc.activo) {
                                    val d = c.position - c.previousPosition
                                    recorrido += d.getDistance()
                                    val tope = topeX(zoom)
                                    if (!sinLado && tope > 0f && d.x != 0f) desplazado = Offset((desplazado.x + d.x).coerceIn(-tope, tope), desplazado.y)
                                }
                                continue
                            }
                            val antes = zoom
                            val factor = evento.calculateZoom()
                            if (antes <= 1.001f && !decidido && recortes.isNotEmpty()) {
                                acumulado *= factor
                                val foco = evento.calculateCentroid(useCurrent = false)
                                val bajo = estado.layoutInfo.visibleItemsInfo.firstOrNull { foco.y >= it.offset && foco.y <= it.offset + it.size }?.index
                                if (bajo != null && recortes[bajo]?.isNotEmpty() == true) {
                                    if (acumulado < 0.85f) { aSolas = bajo; decidido = true }
                                    if (decidido || acumulado < 1f) {
                                        evento.changes.forEach { if (it.pressed) it.consume() }
                                        continue
                                    }
                                }
                            }
                            if (decidido) { evento.changes.forEach { if (it.pressed) it.consume() }; continue }
                            // Sin espacios no hay nada a los lados que enseñar: no se aleja de más.
                            val minimo = if (espacios == 0) 1f else Lectura.ALEJADO_DEL_PDF
                            val ahora = (antes * factor).coerceIn(minimo, 6f)
                            val centro = Offset(size.width / 2f, size.height / 2f)
                            val foco = evento.calculateCentroid(useCurrent = false)
                            // **El punto entre los dedos se queda quieto.** Sin esto el
                            // documento crece desde el centro de la pantalla y lo que uno
                            // quería mirar se escapa por un lado.
                            val movido = if (ahora == antes) desplazado
                            else foco - centro - (foco - centro - desplazado) * (ahora / antes)
                            zoom = ahora
                            // Y que no se pueda echar el documento fuera de la pantalla: como
                            // mucho, hasta que su borde toca el borde.
                            // De lado, hasta el canto del margen; de alto, lo de siempre.
                            val deLosLados = (izquierdaPx + derechaPx) / anchoPx.coerceAtLeast(1).toFloat()
                            val topeX = (((1f + deLosLados) * ahora - 1f) * size.width / 2f).coerceAtLeast(0f)
                            val topeY = ((ahora - 1f) * size.height / 2f).coerceAtLeast(0f)
                            // Anotando, un dedo dibuja: los dos dedos son los que pasan las hojas.
                            if (anotando) estado.dispatchRawDelta(-evento.calculatePan().y / ahora)
                            val conPan = movido + evento.calculatePan()
                            desplazado = Offset(
                                // Con el candado, a lo ancho no se mueve: solo amplía y sube o baja.
                                (if (sinLado) desplazado.x else conPan.x).coerceIn(-topeX, topeX),
                                conPan.y.coerceIn(-topeY, topeY)
                            )
                            evento.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
        ) {
            when {
                cuantas < 0 -> {}                                   // todavía abriendo
                cuantas == 0 -> Text(
                    "No se pudo abrir el PDF",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> {
                    // **La lista no se va mientras se mira una página a solas**: se esconde. Así
                    // al volver con atrás está tal cual, con sus hojas ya pintadas, sin cargar
                    // nada otra vez (usuario, 14-sep-2026). Escondida con alfa cero no se dibuja.
                    LazyColumn(
                        state = estado,
                        userScrollEnabled = aSolas == null,
                        modifier = Modifier
                            .requiredWidth(with(densidad) { (anchoPx + izquierdaPx + derechaPx).toDp() })
                            .requiredHeight(with(densidad) { (altoDeLaCaja / zoom.coerceAtMost(1f)).coerceAtLeast(1f).toDp() })

                            .graphicsLayer(
                                scaleX = zoom, scaleY = zoom,
                                translationX = desplazado.x, translationY = desplazado.y,
                                alpha = if (aSolas == null) 1f else 0f
                            )
                    ) {
                        // Con una sola hoja, hueco arriba y abajo para poder moverla a gusto.
                        if (cuantas == 1) item { Spacer(Modifier.requiredHeight(with(densidad) { (altoDeLaCaja * 0.28f).toDp() })) }
                        // **Una hoja sola se queda en medio** (22-sep-2026): la lista la pegaba
                        // arriba y no había forma de bajarla, porque sin nada que desplazar no hay
                        // desplazamiento. Con hueco arriba y abajo se mueve a gusto.
                        items((0 until cuantas).toList()) { i ->
                            // **En oscuro, nada de capas con efecto** (14-oct-2026, el usuario: «las tintas
                            // se ven pixeladas y al dibujar también»): el efecto de color pintaba la caja
                            // en una capa aparte del tamaño sin aumento, y el aumento del lector la
                            // estiraba. Ahora la hoja de un PDF se invierte en su propia imagen (filtro
                            // de color al pintarla), la de Office ya viene oscura, el margen es papel
                            // oscuro y la tinta la pinta el motor con su modo noche: nítida a cualquier zoom.
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(if (invertido) MARGEN_OSCURO else PAPEL_DEL_MARGEN)
                            ) {
                                Box(
                                    Modifier.padding(
                                        start = with(densidad) { izquierdaPx.toDp() },
                                        end = with(densidad) { derechaPx.toDp() }
                                    )
                                ) {
                                    Hoja(ruta, i, zoomFirme, anchoPx, recortes[i].orEmpty(), oscuroOffice = oscuroOffice, invertida = invertido && !esOffice) { aSolas = i }
                                }
                                // La capa coge **la hoja y sus dos márgenes**.
                                CapaDePagina(capas, i, anotando, espacios, maestro, Modifier.matchParentSize(), conLapiz, alLapiz, oscuro = invertido) { tickDeLaBarra++ }
                            }
                        }
                        if (cuantas == 1) item { Spacer(Modifier.requiredHeight(with(densidad) { (altoDeLaCaja * 0.28f).toDp() })) }
                    }
                    aSolas?.let { i -> VistaDeLaPagina(ruta, i, recortes[i].orEmpty(), anchoPx, invertido && !esOffice, oscuroOffice) { aSolas = null } }
                }
            }
            // **Los marcos numerados**, pegados a las hojas: se pintan con el aumento y el
            // desplazamiento de este fotograma, así que no se quedan atrás. Ver [com.forge.pixpin.ui.CapaDeMarcos].
            if (cuantas > 0 && aSolas == null && !anotando && (marcosDoc.activo || marcosDoc.lista.isNotEmpty())) {
                com.forge.pixpin.ui.CapaDeMarcos(
                    marcosDoc,
                    aPantalla = { x, y, _ -> docAPantalla(x, y) },
                    aDocumento = { pantallaADoc(it) },
                    pxPorUnidad = { pxPorUnidad() },
                    alIr = { m -> alcance.launch { irAlMarco(m) } },
                    alImprimir = { imprimirLosMarcos() },
                    // Dos dedos: el pellizco de siempre, el de la caja de fuera.
                    modifier = Modifier.fillMaxSize()
                )
            }
            // **Los mandos de los lados** (espacio a la izquierda, candado, espacio a la derecha) y
            // **la pastilla de arriba**: los mismos del lector de Word. Salen al tocar y se van solos.
            if (cuantas > 0 && aSolas == null && !anotando && !autoDesplazando && !poniendoMarca && !marcosDoc.activo) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = aLaVista, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    com.forge.pixpin.ui.MandosDeLosLadosDeLector(
                        pasosIzq = if (hayIzquierda) 1 else 0, pasosDer = if (hayDerecha) 1 else 0, tope = 1, sinLado = sinLado,
                        onIzquierda = { mas -> if (mas != hayIzquierda) ponerEspacios(espacios xor ESPACIO_IZQUIERDA); toques++ },
                        onDerecha = { mas -> if (mas != hayDerecha) ponerEspacios(espacios xor ESPACIO_DERECHA); toques++ },
                        onCandado = {
                            sinLado = !sinLado
                            prefsDelLector.edit().putBoolean("sinLado", sinLado).apply()
                            toques++
                            android.widget.Toast.makeText(this@LectorPdfActivity, if (sinLado) "De lado, bloqueado: solo sube y baja" else "Se mueve a todas partes", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            if (cuantas > 0 && aSolas == null && !anotando && !marcosDoc.imprimiendo) {
                com.forge.pixpin.ui.PastillaDeLector(
                    visible = aLaVista,
                    nombre = nombre.substringBeforeLast('.').ifBlank { "PDF" },
                    archivo = nombre.takeIf { it.contains('.') } ?: "documento.pdf",
                    botones = listOf(
                        // El lápiz y el marcador van en el riel de la derecha, como en el PC. Ver [com.forge.pixpin.ui.RielDelVisor].
                        // **Presentar** un PowerPoint: las diapositivas a pantalla completa, en vectores.
                        if (esOffice && esPresentacion) com.forge.pixpin.ui.BotonDeLector(androidx.compose.material.icons.Icons.Filled.Slideshow, "Presentar") {
                            presentando = dondeEstoy().first
                        }
                        // **Escuchar**: el PDF pasado a texto, en el lector de Word, que es quien tiene la voz.
                        // Un Word maquetado, en su «Modo lectura» (el Word como texto), que es donde está la voz.
                        else com.forge.pixpin.ui.BotonDeLector(androidx.compose.material.icons.Icons.Filled.RecordVoiceOver, "Escuchar") {
                            if (esOffice) modoLectura(rutaPedida, nombre, escuchar = true)
                            else {
                                // Desde el marcador verde, si lo hay; si no, desde donde se deje la última vez.
                                val verde = marcas.firstOrNull { it.emoji == Lectura.EMOJI_DE_VOZ }
                                com.forge.pixpin.ui.VisorHtmlActivity.abrirPdfComoTexto(
                                    this@LectorPdfActivity, rutaPedida, nombre, escuchar = true,
                                    desdeHoja = verde?.let { com.forge.pixpin.motor.Marcas.paginaDe(it) + 1 } ?: 0
                                )
                            }
                        },
                        com.forge.pixpin.ui.BotonDeLector(androidx.compose.material.icons.Icons.Filled.KeyboardDoubleArrowDown, "Desplazar sola") {
                            autoDesplazando = true; autoEnMarcha = true
                        },
                        com.forge.pixpin.ui.BotonDeLector(com.forge.pixpin.ui.IconoDeCompartir, "Compartir") { capas.guardarTodo(); compartiendo = true },
                        // **Luna/sol** (PC 74801b4): el modo oscuro, para todos los visores y recordado en este aparato.
                        if (PUEDE_INVERTIR || esOffice) com.forge.pixpin.ui.BotonDeLector(
                            if (invertido) androidx.compose.material.icons.Icons.Filled.LightMode else androidx.compose.material.icons.Icons.Filled.DarkMode,
                            if (invertido) "Claro" else "Oscuro"
                        ) {
                            invertido = !invertido
                            com.forge.pixpin.office.Oscuro.poner(this@LectorPdfActivity, invertido)
                            toques++
                        } else null,
                        com.forge.pixpin.ui.BotonDeLector(androidx.compose.material.icons.Icons.Filled.Settings, "Ajustes") { conElEngranaje = true }
                    ).filterNotNull(),
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
            if (anotando) {
                val ajustes by (application as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
                var enProyecto by remember { mutableStateOf(capas.esDeUnProyecto()) }
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(Color(0xB314182B))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Listo", color = Color.White,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable { dejarDeAnotar() }.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                    // Solo desde que se vio un lápiz: sin él, el dedo es lo único que dibuja.
                    if (vioLapiz) Text(
                        if (conLapiz) "Dedo: mover" else "Dedo: dibujar", color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .clickable { conLapiz = !conLapiz; lapizAutomatico = conLapiz }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                    Text(
                        "Exportar", color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable { capas.guardarTodo(); compartiendo = true }.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                    // **Solo si se pide**: la edición rápida no crea ningún proyecto. Con esto el
                    // PDF pasa a proyectos con lo anotado, para seguir con el editor completo.
                    if (!enProyecto) Text(
                        "Al proyecto", color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable {
                            alcance.launch {
                                val bien = withContext(Dispatchers.IO) { capas.pasarAProyecto(nombre.substringBeforeLast('.').ifBlank { "PDF" }, cuantas) }
                                enProyecto = bien
                                android.widget.Toast.makeText(
                                    this@LectorPdfActivity, if (bien) "Ya está en proyectos, con lo anotado" else "No se pudo crear el proyecto",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
                // El texto plantado en una hoja se escribe aquí. Ver [com.forge.pixpin.ui.EscribirEnElLienzo].
                capas.ultima()?.let { c -> com.forge.pixpin.ui.EscribirEnElLienzo(c, tickDeLaBarra) { capas.ensuciarLaUltima(); tickDeLaBarra++ } }
                Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp, start = 6.dp, end = 6.dp)) {
                    @Suppress("UNUSED_EXPRESSION") tickDeLaBarra
                    com.forge.pixpin.ui.theme.SuperficieDeCristal(Modifier, androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
                        com.forge.pixpin.motor.DrawToolbar(
                            tool = maestro.tool,
                            onTool = { maestro.selectTool(it); tickDeLaBarra++ },
                            style = maestro.scene.style,
                            onStyle = { nuevo -> maestro.cambiarEstilo(nuevo) { it }; capas.ultima()?.cambiarEstilo(nuevo) { it }; tickDeLaBarra++ },
                            canUndo = capas.ultima()?.canUndo == true,
                            onUndo = { capas.ultima()?.undo(); capas.ensuciarLaUltima(); tickDeLaBarra++ },
                            // La barra de este formato (24-oct-2026): sin tocar, la del lector.
                            permitidas = ajustes.formatoToolSet((if (esOffice) com.forge.pixpin.motor.HerramientasPorSitio.Sitio.DOCUMENTO else com.forge.pixpin.motor.HerramientasPorSitio.Sitio.PDF)) - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                            grupos = ajustes.formatoGroupList((if (esOffice) com.forge.pixpin.motor.HerramientasPorSitio.Sitio.DOCUMENTO else com.forge.pixpin.motor.HerramientasPorSitio.Sitio.PDF)).map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() }
                        )
                    }
                }
            }
            // **El riel de lectura**, a la izquierda como en el lector de Word: la flechita baja con
            // las hojas que se van leyendo. Ver [com.forge.pixpin.ui.RielDeLecturaDeLector].
            if (cuantas > 1 && aSolas == null && !anotando) com.forge.pixpin.ui.RielDeLecturaDeLector(
                progreso = {
                    val alto = estado.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.takeIf { it > 0 } ?: 1
                    if (!estado.canScrollForward) 1f
                    else (estado.firstVisibleItemIndex + estado.firstVisibleItemScrollOffset.toFloat() / alto) / cuantas
                },
                suena = null, noche = true,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            // **El riel de la derecha** (24-oct-2026, como el PC): marcador, lápiz y marco, y debajo
            // los marcadores. Anotando se queda, con el lápiz encendido: tocarlo deja de anotar.
            if (cuantas > 0 && aSolas == null && !marcosDoc.imprimiendo) {
                com.forge.pixpin.ui.RielDelVisor(
                    poniendoMarca = poniendoMarca, anotando = anotando, marcando = marcosDoc.activo,
                    onMarcador = { if (anotando) dejarDeAnotar(); marcosDoc.activo = false; poniendoMarca = !poniendoMarca },
                    onLapiz = { if (anotando) dejarDeAnotar() else { marcosDoc.activo = false; poniendoMarca = false; anotando = true } },
                    onMarco = { if (anotando) dejarDeAnotar(); poniendoMarca = false; marcosDoc.alternar() },
                    marcas = if (anotando) 0 else marcas.size, emojiDe = { i -> marcas[i].emoji },
                    alElegirMarca = { i -> marcas.getOrNull(i)?.let { m -> alcance.launch { irALaMarca(m) } } },
                    imprimirMarcos = if (marcosDoc.lista.isEmpty()) null else ({ imprimirLosMarcos() }),
                    imprimirTodo = {
                        capas.guardarTodo()
                        if (esOffice) imprimirOffice(rutaPedida, nombre) { i -> capas.escenaDe(i) }
                        else imprimirElPdf(ruta, nombre) { i -> capas.escenaDe(i) }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
            // Por encima de los dos botones de la esquina: pegada al canto los tapaba.
            if (poniendoMarca) com.forge.pixpin.ui.ElegirEmojiDeMarca(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
                conVerde = true,
                onCerrar = { poniendoMarca = false }
            ) { emoji ->
                poniendoMarca = false
                val (pagina, dentro) = dondeEstoy()
                val (x, y) = com.forge.pixpin.motor.Marcas.enLaPagina(pagina, dentro)
                // **El verde es uno solo**: ponerlo en otra hoja lo mueve. Escuchar empieza por él.
                val verde = emoji == Lectura.EMOJI_DE_VOZ
                val sinVerde = if (verde) marcas.filterNot { it.emoji == Lectura.EMOJI_DE_VOZ } else marcas
                marcas = com.forge.pixpin.motor.Marcas.con(sinVerde, x, y, emoji, System.currentTimeMillis())
                guardarLasMarcas()
                // Y su vista: el aumento y la esquina de arriba a la izquierda, para volver justo ahí.
                val nueva = marcas.maxByOrNull { it.id }
                val esquina = pantallaADoc(Offset.Zero)
                if (nueva != null && esquina != null) vistasDeMarcas.poner(nueva.id, zoom.toDouble(), esquina.x, esquina.y)
                if (verde) android.widget.Toast.makeText(this@LectorPdfActivity, "Marcador verde en la hoja ${pagina + 1}: se leerá desde aquí", android.widget.Toast.LENGTH_SHORT).show()
            }
            if (compartiendo && cuantas > 0) {
                val titulo = nombre.substringBeforeLast('.').ifBlank { "PDF" }
                com.forge.pixpin.ui.HojaDeCompartir(
                    remember(ruta, titulo) {
                        if (esOffice) formatosDeOffice(rutaPedida, nombre, titulo, { i -> capas.escenaDe(i) }, { espacios }, { marcas }, cuantas, dondeEstoy().first)
                        else com.forge.pixpin.ui.Compartible(
                            titulo, paginas = com.forge.pixpin.ui.ExportarVisor.paginasDe(cuantas), marcadas = setOf("p${dondeEstoy().first}"), formatos = listOf(
                                // **El PDF, con lo anotado** —o limpio, con el interruptor—. `ruta` es ya la
                                // copia limpia si el PDF es de un proyecto: lo anotado no sale dos veces.
                                com.forge.pixpin.ui.ExportarPdfAnotado.formatoPdf(
                                    this@LectorPdfActivity, ruta, titulo, { i -> capas.escenaDe(i) },
                                    espacios = { espacios }, marcas = { marcas }
                                ),
                                com.forge.pixpin.ui.ExportarPdfAnotado.formato(this@LectorPdfActivity, ruta, titulo, cuantas) { i -> capas.escenaDe(i) },
                                // **Y como texto**: el PDF pasado a un documento que se reflowea, con lo
                                // anotado leyéndolo como texto. Ver [com.forge.pixpin.motor.PdfAHtml].
                                com.forge.pixpin.ui.ExportarDocumentoAnotado.formato(this@LectorPdfActivity, java.io.File(rutaPedida), nombre, rotulo = "Página web (texto)")
                            ) + imagenesDeHojas(titulo, { ruta }, { i -> capas.escenaDe(i) }) + listOf(
                                // El original y su .pixpin, los de todos los visores (14-oct-2026). Ver [com.forge.pixpin.ui.ExportarVisor].
                                com.forge.pixpin.ui.ExportarVisor.original(this@LectorPdfActivity, rutaPedida, nombre, "El documento tal cual, sin lo anotado"),
                                com.forge.pixpin.ui.ExportarVisor.pixpin(this@LectorPdfActivity, rutaPedida, nombre)
                            )
                        )
                    }
                ) { compartiendo = false }
            }
            // ---- El engranaje, la lista para quitar marcadores y la página que sube sola. ----
            if (conElEngranaje) EngranajeDelPdf(
                onCerrar = { conElEngranaje = false },
                sinLado = sinLado, onSinLado = { sinLado = it; prefsDelLector.edit().putBoolean("sinLado", it).apply() },
                invertido = invertido, onInvertido = { invertido = it; com.forge.pixpin.office.Oscuro.poner(this@LectorPdfActivity, it) },
                izquierda = hayIzquierda, derecha = hayDerecha,
                onIzquierda = { ponerEspacios(espacios xor ESPACIO_IZQUIERDA) }, onDerecha = { ponerEspacios(espacios xor ESPACIO_DERECHA) },
                comoTexto = { escuchar ->
                    conElEngranaje = false
                    if (esOffice) { modoLectura(rutaPedida, nombre, escuchar); return@EngranajeDelPdf }
                    val verde = if (escuchar) marcas.firstOrNull { it.emoji == Lectura.EMOJI_DE_VOZ } else null
                    com.forge.pixpin.ui.VisorHtmlActivity.abrirPdfComoTexto(
                        this@LectorPdfActivity, rutaPedida, nombre, escuchar = escuchar,
                        desdeHoja = verde?.let { com.forge.pixpin.motor.Marcas.paginaDe(it) + 1 } ?: 0
                    )
                },
                onExportar = { conElEngranaje = false; capas.guardarTodo(); compartiendo = true },
                onAlProyecto = if (capas.esDeUnProyecto()) null else ({ conElEngranaje = false; alProyecto() }),
                onQuitarMarcadores = if (marcas.isEmpty()) null else ({ conElEngranaje = false; quitandoMarcas = true }),
                // Un Word: su «Modo lectura» es el Word como texto (con su voz); un PowerPoint no tiene.
                comoTextoRotulo = if (!esOffice) "Ver como texto" to "como un Word" else if (esPresentacion) null else "Modo lectura" to "El Word como texto, con su letra y su voz",
                conVoz = !(esOffice && esPresentacion),
                onPresentar = if (esOffice && esPresentacion) ({ conElEngranaje = false; presentando = dondeEstoy().first }) else null,
                // Imprimir: mantener el marco del riel (25-oct-2026).
                onImprimir = null,
                onCopiarHoja = if (esOffice) ({
                    conElEngranaje = false
                    val hoja = dondeEstoy().first
                    alcance.launch {
                        val texto = withContext(Dispatchers.IO) { com.forge.pixpin.office.HojasOffice.yaAbierto(rutaPedida)?.texto(hoja).orEmpty() }
                        copiar(texto, "Texto de la hoja ${hoja + 1} copiado")
                    }
                }) else null
            )
            if (quitandoMarcas) androidx.compose.material3.AlertDialog(
                onDismissRequest = { quitandoMarcas = false },
                title = { Text("Quitar marcadores") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        marcas.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    marcas = marcas.filterNot { it.id == m.id }
                                    guardarLasMarcas()
                                    if (marcas.isEmpty()) quitandoMarcas = false
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(m.emoji, style = MaterialTheme.typography.titleLarge)
                                Text("  Hoja " + (com.forge.pixpin.motor.Marcas.paginaDe(m) + 1), modifier = Modifier.weight(1f))
                                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Quitar", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { quitandoMarcas = false }) { Text("Hecho") } }
            )
            if (autoDesplazando && cuantas > 0) {
                // Las palabras del PDF, una vez: con ellas, la velocidad en palabras por minuto. Ver [com.forge.pixpin.motor.AutoDesplazar].
                LaunchedEffect(ruta) {
                    if (palabrasDelPdf < 0) palabrasDelPdf = withContext(Dispatchers.IO) {
                        runCatching {
                            if (esOffice) com.forge.pixpin.office.HojasOffice.yaAbierto(rutaPedida)?.let { d ->
                                (0 until d.cuantas()).sumOf { i -> d.texto(i).split(Regex("\\s+")).count { it.isNotBlank() } }
                            } ?: 0
                            else com.forge.pixpin.motor.PdfAHtml.contarPalabras(java.io.File(ruta).readBytes())
                        }.getOrDefault(0)
                            .takeIf { it > 0 } ?: (cuantas * com.forge.pixpin.motor.PdfAHtml.POR_HOJA_SI_NO_HAY)
                    }
                }
                val enMarcha = autoEnMarcha && !anotando && aSolas == null && palabrasDelPdf > 0
                LaunchedEffect(enMarcha) {
                    if (!enMarcha) return@LaunchedEffect
                    var antes = -1L
                    var sobra = 0f
                    while (true) {
                        val ahora = androidx.compose.runtime.withFrameNanos { it }
                        if (antes > 0) {
                            val altoDeHoja = estado.layoutInfo.visibleItemsInfo.maxOfOrNull { it.size } ?: 0
                            val v = com.forge.pixpin.motor.AutoDesplazar.pixelesPorSegundo(palabrasPorMinuto, palabrasDelPdf, (altoDeHoja.toFloat() * cuantas))
                            sobra += v * ((ahora - antes) / 1e9f).coerceAtMost(0.1f)
                            val n = sobra.toInt()
                            if (n > 0) { estado.dispatchRawDelta(n.toFloat()); sobra -= n }
                            if (!estado.canScrollForward) { autoEnMarcha = false; break }
                        }
                        antes = ahora
                    }
                }
                val quedan by remember {
                    androidx.compose.runtime.derivedStateOf {
                        val alto = estado.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.takeIf { it > 0 } ?: 1
                        val progreso = ((estado.firstVisibleItemIndex + estado.firstVisibleItemScrollOffset.toFloat() / alto) / cuantas).coerceIn(0f, 1f)
                        com.forge.pixpin.ui.rotuloDeLoQueQueda(palabrasDelPdf, progreso, palabrasPorMinuto)
                    }
                }
                com.forge.pixpin.ui.BarraDeAutoDesplazarDeLector(
                    palabrasPorMinuto, autoEnMarcha, if (palabrasDelPdf < 0) "Contando las palabras…" else quedan,
                    onMenos = { palabrasPorMinuto = com.forge.pixpin.motor.AutoDesplazar.otra(palabrasPorMinuto, -1); prefsDelLector.edit().putInt("auto:ppm", palabrasPorMinuto).apply() },
                    onMas = { palabrasPorMinuto = com.forge.pixpin.motor.AutoDesplazar.otra(palabrasPorMinuto, 1); prefsDelLector.edit().putInt("auto:ppm", palabrasPorMinuto).apply() },
                    onAlternar = { autoEnMarcha = !autoEnMarcha },
                    onCerrar = { autoEnMarcha = false; autoDesplazando = false },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                androidx.activity.compose.BackHandler { autoEnMarcha = false; autoDesplazando = false }
            }
            // **Presentar** (PowerPoint): encima de todo, hasta que se sale con atrás o la X.
            presentando?.let { desde -> if (cuantas > 0) Presentar(rutaPedida, desde, cuantas, capas) { presentando = null } }
        }
    }

    /**
     * **Las diapositivas a pantalla completa** (lo de «Presentar» del PC): una tras otra, con lo
     * anotado encima. Cada una se pinta en vectores **a la medida exacta de la pantalla**, fuera
     * del hilo de la pantalla, y la siguiente ya preparada: pasar es instantáneo. Un toque en el
     * tercio derecho o un barrido a la izquierda pasa; en el izquierdo, vuelve ([com.forge.pixpin.ui.ZonaDePasar]).
     *
     * **Las notas del orador** (PC ac7981a): con «Notas» salen abajo, sobre la diapositiva, las de la
     * que se ve. En el PC van en otra pantalla (la vista del orador); aquí, en el mismo teléfono.
     */
    @Composable
    private fun Presentar(ruta: String, desde: Int, cuantas: Int, capas: CapasDelPdf, alCerrar: () -> Unit) {
        var i by remember { mutableStateOf(desde.coerceIn(0, cuantas - 1)) }
        androidx.activity.compose.BackHandler { alCerrar() }
        var tam by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        var hoja by remember { mutableStateOf<Pair<Int, Bitmap>?>(null) }
        var tinta by remember { mutableStateOf<com.forge.pixpin.motor.Scene?>(null) }
        var conMandos by remember { mutableStateOf(true) }
        var conNotas by remember { mutableStateOf(false) }
        var notas by remember { mutableStateOf<String?>(null) }
        // ¿Tiene notas alguna? Solo entonces sale el botón (se mira la primera vez, fuera de la pantalla).
        var hayNotas by remember { mutableStateOf(false) }
        LaunchedEffect(ruta) {
            hayNotas = withContext(Dispatchers.IO) {
                val d = com.forge.pixpin.office.HojasOffice.yaAbierto(ruta)
                d != null && (0 until minOf(cuantas, 200)).any { d.notas(it) != null }
            }
        }
        LaunchedEffect(i, conNotas) {
            notas = if (!conNotas) null else withContext(Dispatchers.IO) { com.forge.pixpin.office.HojasOffice.yaAbierto(ruta)?.notas(i) }
        }
        val listas = remember { HashMap<Int, Bitmap>() }
        LaunchedEffect(i, tam) {
            if (tam.width <= 0) return@LaunchedEffect
            val d = com.forge.pixpin.office.HojasOffice.yaAbierto(ruta) ?: return@LaunchedEffect
            fun ancho(n: Int): Int {
                val pr = d.proporcion(n).coerceAtLeast(0.05f)
                return minOf(tam.width.toFloat(), tam.height * pr).toInt().coerceAtLeast(1)
            }
            val actual = listas[i] ?: withContext(Dispatchers.IO) { d.pintar(i, ancho(i), cuantas) }
            tinta = withContext(Dispatchers.IO) { runCatching { capas.escenaDe(i) }.getOrNull() }
            if (actual != null) { hoja = i to actual; listas[i] = actual }
            // La siguiente, ya hecha; y fuera las que quedan lejos.
            listas.keys.filter { kotlin.math.abs(it - i) > 1 }.forEach { listas.remove(it) }
            if (i + 1 < cuantas && listas[i + 1] == null) withContext(Dispatchers.IO) { d.pintar(i + 1, ancho(i + 1), cuantas) }?.let { listas[i + 1] = it }
        }
        val contexto = androidx.compose.ui.platform.LocalContext.current
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { tam = it }) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val (n, b) = hoja ?: return@Canvas
                val ox = (size.width - b.width) / 2f
                val oy = (size.height - b.height) / 2f
                val lienzo = drawContext.canvas.nativeCanvas
                lienzo.drawBitmap(b, ox, oy, null)
                val escena = tinta
                if (escena != null && n == i) {
                    lienzo.save()
                    lienzo.translate(ox, oy)
                    lienzo.clipRect(0f, 0f, b.width.toFloat(), b.height.toFloat())
                    pintorDeCapas(contexto, false).renderScene(lienzo, escena.copy(viewport = vistaDeLaCapa(b.width.toDouble(), 0)), b.width.toDouble(), b.height.toDouble())
                    lienzo.restore()
                }
            }
            com.forge.pixpin.ui.ZonaDePasar(
                onAnterior = { if (i > 0) i-- },
                onSiguiente = { if (i < cuantas - 1) i++ },
                onMedio = { conMandos = !conMandos },
                modifier = Modifier.fillMaxSize()
            )
            val texto = notas
            if (conNotas && texto != null) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = if (conMandos) 64.dp else 12.dp)
                    .heightIn(max = 180.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Color(0xCC000000))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(texto, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            if (conMandos) Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1} / $cuantas", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                if (hayNotas) Text(
                    if (conNotas) "Sin notas" else "Notas", color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable { conNotas = !conNotas }.padding(horizontal = 12.dp, vertical = 9.dp)
                )
                Text(
                    "Salir", color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable { alCerrar() }.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
    }

    /**
     * Una hoja del documento.
     *
     * **Se dibuja solo cuando entra a la vista**, que es lo que hace que un PDF de doscientas
     * páginas se abra igual de rápido que uno de dos: la lista solo compone lo que se ve, y
     * esto solo pide su mapa de bits al componerse. Y se pide **fuera del hilo de la pantalla**:
     * rasterizar una página son decenas de milisegundos dentro de un fotograma que dura
     * dieciséis, y se notaría como un tirón en cada hoja que entra.
     *
     * Mientras llega se deja un hueco **de la altura que le toca**, sacada de las medidas de la
     * página. Sin eso la lista no sabe cuánto mide lo que aún no ha dibujado, y el
     * desplazamiento pega saltos según van llegando las hojas.
     */
    @Composable
    private fun Hoja(
        ruta: String, i: Int, zoom: Float, anchoPx: Int,
        suyos: List<SublienzosDelPdf.Recorte> = emptyList(),
        /** Lo que va encima de la hoja, ajustado a ella: la capa de lo anotado. */
        encima: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
        /** Un Word o un PowerPoint en el modo oscuro: su hoja se pinta oscura (y se vuelve a pedir al cambiar). */
        oscuroOffice: Boolean = false,
        /** Un PDF en el modo oscuro: la hoja con la matriz de invertir, al pintar su imagen. */
        invertida: Boolean = false,
        alAbrirSublienzos: () -> Unit = {}
    ) {
        // A más aumento, más puntos: acercarse a una hoja ya dibujada la estiraría y se vería
        // blanda justo cuando uno se acerca para leer una cota. Por escalones, para no
        // rehacerla en cada pellizco.
        val escalon = when {
            zoom <= 1.2f -> 1
            zoom <= 2.5f -> 2
            else -> 4
        }
        val proporcion = remember(ruta, i) {
            medidaDeLaHoja(ruta, i)?.let { (an, al) -> (an / al).toFloat() } ?: 0.7f
        }
        var mapa by remember(ruta, i, oscuroOffice) { mutableStateOf(PaginasEnMemoria.alguna(ruta, i, oscuroOffice)) }
        LaunchedEffect(ruta, i, escalon, oscuroOffice) {
            mapa = withContext(Dispatchers.IO) {
                // **Y con un tope de píxeles, no solo de ancho.**
                //
                // El tope era el ancho, y en una hoja apaisada grande —un A1— cuatro veces el
                // ancho de la pantalla son trece millones de píxeles, o sea **cincuenta megas
                // de mapa de bits** por hoja. Pedirlo cuesta segundos y llena la memoria; con
                // el tope, la hoja sale a menos aumento del pedido pero **sale**, que es la
                // misma regla que sigue la lámina del plano. Ver [PIXELES_POR_HOJA].
                val pedido = (anchoPx * escalon).coerceIn(320, 4000)
                val alto = proporcion.takeIf { it > 0.01f } ?: 0.7f
                val cabe = kotlin.math.sqrt(PIXELES_POR_HOJA * alto).toInt()
                PaginasEnMemoria.pintar(ruta, i, minOf(pedido, cabe).coerceAtLeast(320), oscuroOffice)
            } ?: mapa
        }
        val actual = mapa
        if (suyos.isNotEmpty()) {
            // En la lista solo los recuadros y el aviso: las miniaturas, en la vista a solas.
            HojaConSublienzos(actual, proporcion, suyos, abierta = false, alPedirlos = alAbrirSublienzos, encima = encima) { }
            return
        }
        // **Un Word o un PowerPoint: sus enlaces y su texto** (lo del PC). Un toque en un enlace lo
        // abre (web y correo); mantener el dedo sobre un renglón lo copia.
        val deOffice = remember(ruta) { com.forge.pixpin.office.HojasOffice.es(ruta) }
        val conTexto = if (!deOffice) Modifier else Modifier.pointerInput(ruta, i) {
            fun puntoDe(o: Offset): Pair<com.forge.pixpin.office.Pxof.Pagina, Offset>? {
                val pag = com.forge.pixpin.office.HojasOffice.yaAbierto(ruta)?.pagina(i) ?: return null
                val k = pag.ancho / size.width.coerceAtLeast(1)
                return pag to Offset(o.x * k, o.y * k)
            }
            detectTapGestures(
                onTap = { o -> puntoDe(o)?.let { (pag, pt) -> pag.textoEn(pt.x, pt.y)?.enlace?.let { abrirEnlace(it) } } },
                onLongPress = { o ->
                    puntoDe(o)?.let { (pag, pt) ->
                        val renglon = pag.renglonEn(pt.x, pt.y).joinToString(" ") { it.texto.trim() }.trim()
                        if (renglon.isNotEmpty()) copiar(renglon, "Renglón copiado")
                    }
                }
            )
        }
        // **El trozo nítido de cerca** (14-oct-2026, el usuario: «las imágenes del Word se ven en baja
        // calidad; en la PC se leen, aquí no se distinguen las letras»; el PC lo hace desde 8794f6c).
        // La hoja entera tiene un tope de píxeles ([PIXELES_POR_HOJA]): un A4 no pasa de ~1 700 de
        // ancho, y al ampliar solo se estiraba. Ampliado, se pinta además **lo que se ve de la hoja**
        // a la resolución de la pantalla y se pone encima, en su sitio. Solo Word y PowerPoint, que
        // son vectores: se vuelven a pintar del documento, no de la imagen ya hecha.
        var donde by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        var movida by remember { mutableStateOf(0) }
        var nitido by remember(ruta, i, oscuroOffice) { mutableStateOf<Pair<Bitmap, androidx.compose.ui.geometry.Rect>?>(null) }
        if (deOffice) LaunchedEffect(ruta, i, zoom, movida, oscuroOffice) {
            if (zoom <= 1.2f) { nitido = null; return@LaunchedEffect }
            // Que se pare: mientras se desliza, cada fotograma cambiaría el trozo.
            kotlinx.coroutines.delay(160)
            val c = donde?.takeIf { it.isAttached } ?: return@LaunchedEffect
            val ventana = c.boundsInWindow()
            if (ventana.width < 1f || ventana.height < 1f) return@LaunchedEffect
            val a = c.windowToLocal(Offset(ventana.left, ventana.top))
            val b = c.windowToLocal(Offset(ventana.right, ventana.bottom))
            val visto = androidx.compose.ui.geometry.Rect(
                maxOf(0f, minOf(a.x, b.x)), maxOf(0f, minOf(a.y, b.y)),
                minOf(c.size.width.toFloat(), maxOf(a.x, b.x)), minOf(c.size.height.toFloat(), maxOf(a.y, b.y))
            )
            if (visto.width < 1f || visto.height < 1f) return@LaunchedEffect
            val anchoLocal = c.size.width.toFloat()
            nitido = withContext(Dispatchers.Default) {
                runCatching {
                    val doc = com.forge.pixpin.office.HojasOffice.yaAbierto(ruta) ?: return@runCatching null
                    val pag = doc.pagina(i) ?: return@runCatching null
                    // Píxeles del trozo por cada uno de la hoja en la lista: el aumento (sin pasar de ~8 MP).
                    var k = zoom
                    val area = visto.width * visto.height * k * k
                    if (area > 8_000_000f) k *= kotlin.math.sqrt(8_000_000f / area)
                    val w = (visto.width * k).toInt().coerceAtLeast(1)
                    val h = (visto.height * k).toInt().coerceAtLeast(1)
                    val mapa = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val lienzo = android.graphics.Canvas(mapa)
                    lienzo.translate(-visto.left * k, -visto.top * k)
                    doc.pintor().pintar(lienzo, pag, anchoLocal / pag.ancho.coerceAtLeast(1f) * k, doc.cuantas(), oscuro = oscuroOffice)
                    mapa to visto
                }.getOrNull()
            } ?: nitido
        }
        val densidadDelTrozo = LocalDensity.current
        Box(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).then(conTexto)
                .then(if (deOffice) Modifier.onGloballyPositioned { donde = it; if (zoom > 1.2f) movida++ } else Modifier)
        ) {
            if (actual != null) {
                Image(
                    bitmap = actual.asImageBitmap(),
                    contentDescription = null,
                    colorFilter = if (invertida) FILTRO_INVERTIDO else null,
                    modifier = Modifier.fillMaxWidth()
                )
                nitido?.let { (b, r) ->
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                        modifier = Modifier
                            .offset { androidx.compose.ui.unit.IntOffset(r.left.toInt(), r.top.toInt()) }
                            .size(with(densidadDelTrozo) { r.width.toDp() }, with(densidadDelTrozo) { r.height.toDp() })
                    )
                }
            } else {
                Spacer(Modifier.fillMaxWidth().aspectRatio(proporcion.coerceAtLeast(0.1f)))
            }
            encima()
        }
    }
}

/**
 * **Las páginas ya pintadas, en memoria.** Volver de la vista a solas, o pasar otra vez por una
 * hoja, no la rasteriza de nuevo: se enseña la que ya había. Un octavo de la memoria de la app.
 */
/**
 * **Lo anotado sobre un PDF, hoja por hoja, con el motor del lienzo** (20-sep-2026).
 *
 * El usuario lo pidió como «un editor de PDF normal»: todas las hojas a la vista, se pasa de una a
 * otra haciendo scroll y se anota en cualquiera, con las herramientas del lienzo que haya elegido
 * en Ajustes. Y tres condiciones que salen de cómo falló la primera versión (aislaba una hoja,
 * creaba un proyecto sin pedirlo, y lo anotado no se veía al volver):
 *
 * - **Cada hoja tiene su dibujo**, en las mismas unidades que usa el editor completo para una
 *   página (la hoja a [PdfDoc.PAGE_WIDTH] de ancho). Si el PDF **es de un proyecto**, es el dibujo
 *   de esa hoja —lo que se anote aquí se edita luego desde proyectos, y al revés—; si no, es un
 *   dibujo suelto del propio PDF: `anot-<uid>-p<n>` si es un adjunto del chat —con el código de
 *   su mensaje, que viaja al sincronizar ([com.forge.pixpin.sincro.AnotacionesDelAdjunto])—, o
 *   `pdf-<huella>-p<n>` (la huella de la ruta) si no lo es. El de la huella es también el de
 *   antes del 28-sep-2026: se copia al nuevo la primera vez que se abre la hoja.
 * - **No se crea ningún proyecto** por anotar. Solo con «Al proyecto» ([pasarAProyecto]), que
 *   además le entrega al proyecto nuevo los dibujos ya hechos.
 * - **Lo anotado se pinta siempre encima de cada hoja**, leyendo su dibujo: no depende de rehacer
 *   el archivo. Por eso el lector enseña la copia **limpia** de un PDF de proyecto.
 */
private class CapasDelPdf(private val actividad: ComponentActivity, private val rutaPedida: String) {
    private val app = actividad.application as? com.forge.pixpin.PixPinApp
    private val huella = rutaPedida.hashCode().toUInt().toString(16)
    /** El código del mensaje del chat que lleva este PDF, o null si no es un adjunto. No cambia nunca. */
    private val uid: String? by lazy { com.forge.pixpin.sincro.AnotacionesDelAdjunto.uidDe(actividad.filesDir, rutaPedida) }
    private val abiertas = HashMap<Int, com.forge.pixpin.motor.DrawController>()
    private val sucias = HashSet<Int>()
    private var ultimaTocada = -1
    /** Para que las capas que solo se pintan se repinten cuando cambia algo. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun proyecto(): com.forge.pixpin.motor.Proyecto? =
        app?.proyectos?.proyectos?.value?.firstOrNull { it.pdfOrigen == rutaPedida || it.pdfLimpio == rutaPedida }

    fun esDeUnProyecto() = proyecto() != null || proyectoDeOffice() != null

    /** Un Word o un PowerPoint maquetado: sus hojas salen del .so, no de un PDF. */
    private val esOffice = com.forge.pixpin.office.HojasOffice.es(rutaPedida)

    /**
     * El proyecto que se hizo con «Al proyecto» desde un Word o un PowerPoint (su PDF en vectores),
     * si sigue ahí. Sus hojas usan los mismos dibujos que el lector, así que lo anotado en uno se ve
     * en el otro; el lector sigue leyendo el documento original.
     */
    private fun proyectoDeOffice(): com.forge.pixpin.motor.Proyecto? {
        if (!esOffice) return null
        val id = actividad.getSharedPreferences("lectura", Context.MODE_PRIVATE).getString("office:$rutaPedida:proyecto", null) ?: return null
        return app?.proyectos?.proyectos?.value?.firstOrNull { it.id == id }
    }

    /**
     * Con qué se nombran los marcadores y los espacios para que viajen: el código del proyecto si
     * el PDF es de uno, el del mensaje si es un adjunto del chat, o null (se quedan por la ruta).
     */
    fun baseSuelta(): String? =
        // Un Word o un PowerPoint: `anot-<uid>.marcas` es del «Modo lectura» (el Word como texto,
        // otro formato de marcador). Los de las hojas se quedan en el teléfono, como en el PC.
        if (esOffice) null
        else proyecto()?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.delPdf(com.forge.pixpin.sincro.Codigos.unico(it)) }
            ?: uid?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.delPdf(it) }

    /**
     * Los marcadores de un Word o un PowerPoint del chat: el `anot-<uid>.marcas` del documento, el
     * mismo que el Word como texto y que el PC (en fracción del documento). Null si no es de Office
     * o no es un adjunto del chat. Ver [com.forge.pixpin.motor.Marcas.deFracciones].
     */
    fun marcasDeOffice(): java.io.File? =
        if (!esOffice) null
        else uid?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.marcas(actividad.filesDir, com.forge.pixpin.sincro.AnotacionesDelAdjunto.delDocumento(it, java.io.File(rutaPedida))) }

    /** El dibujo suelto de la hoja [i], migrando el de la huella si es la primera vez. */
    private fun suelto(i: Int): String {
        val viejo = "pdf-$huella-p$i"
        val u = uid ?: return viejo
        val nuevo = com.forge.pixpin.sincro.AnotacionesDelAdjunto.dePagina(u, i)
        com.forge.pixpin.sincro.AnotacionesDelAdjunto.copiarSiFalta(
            java.io.File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(actividad, viejo)),
            java.io.File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(actividad, nuevo))
        )
        return nuevo
    }

    fun documentoLimpio(): String? = proyecto()?.pdfLimpio?.takeIf { java.io.File(it).exists() }

    /** El dibujo de la hoja [i]. En un proyecto, el de su hoja; se le pone uno si aún no tenía. */
    private fun idDe(i: Int, paraEscribir: Boolean): String {
        val p = proyecto() ?: return suelto(i)
        val hoja = p.hojas.firstOrNull { it.pagina == i } ?: return "pdf-$huella-p$i"
        hoja.dibujo?.let { return it }
        val suelto = "pdf-$huella-p$i"
        if (paraEscribir) app?.proyectos?.guardar(com.forge.pixpin.motor.Proyectos.conDibujo(p, hoja.id, suelto, System.currentTimeMillis()))
        return suelto
    }

    /** Los espacios puestos ahora. Solo para leer la tinta vieja, la que no trae su marco. */
    @Volatile var espacios = 0

    private fun cargar(i: Int, id: String, migrar: Boolean): com.forge.pixpin.motor.Scene? =
        tintaDeLaHoja(actividad, id, rutaPedida, i, espacios, suelta = proyecto() == null, migrar = migrar)

    /** Trabajo de disco la primera vez. */
    fun controladorDe(i: Int): com.forge.pixpin.motor.DrawController = synchronized(abiertas) {
        abiertas.getOrPut(i) {
            val escena = cargar(i, idDe(i, false), migrar = true)
            com.forge.pixpin.motor.DrawController(escena ?: com.forge.pixpin.motor.Scene()).also { it.pedirLaMedida = false }
        }
    }

    /** Lo anotado en la hoja [i], abierta o no: para exportar. Null si no hay nada. */
    fun escenaDe(i: Int): com.forge.pixpin.motor.Scene? =
        (synchronized(abiertas) { abiertas[i]?.scene } ?: cargar(i, idDe(i, false), migrar = false))
            ?.takeIf { e -> e.elements.any { !it.isDeleted } }

    /** A la tinta suelta que aún no tiene marco se le escribe el de los espacios de ahora, sin tocarla. */
    fun fijarLoViejo(paginas: Int) {
        if (proyecto() != null) return
        val e = espacios
        val abiertasYa = synchronized(abiertas) { abiertas.keys.toSet() }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            for (i in 0 until paginas) {
                if (i in abiertasYa) continue
                runCatching { tintaDeLaHoja(actividad, idDe(i, false), rutaPedida, i, e, suelta = true, migrar = true) }
            }
        }
    }

    fun tocada(i: Int) { ultimaTocada = i }
    fun ultima(): com.forge.pixpin.motor.DrawController? = synchronized(abiertas) { abiertas[ultimaTocada] }
    fun ensuciar(i: Int) { synchronized(sucias) { sucias.add(i) }; version.intValue++ }
    fun ensuciarLaUltima() { if (ultimaTocada >= 0) ensuciar(ultimaTocada) }

    /** Escribe lo cambiado y, si el PDF es de un proyecto, rehace su documento. Fuera del hilo de la pantalla. */
    fun guardarTodo() {
        val pendientes = synchronized(sucias) { sucias.toList().also { sucias.clear() } }
        if (pendientes.isEmpty()) return
        val escenas = synchronized(abiertas) { pendientes.mapNotNull { i -> abiertas[i]?.let { i to it.scene } } }
        val contexto = actividad.applicationContext
        val ids = escenas.map { (i, _) -> idDe(i, true) }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val suelta = proyecto() == null
            escenas.forEachIndexed { k, (i, escena) ->
                runCatching {
                    com.forge.pixpin.motor.ExcalidrawStore.guardar(contexto, ids[k], escena)
                    ponerElMarco(contexto, ids[k], rutaPedida, i, siempre = suelta)
                }
            }
            proyecto()?.let { p -> runCatching { com.forge.pixpin.motor.PdfDelProyecto.rehacer(contexto, p) } }
        }
    }

    /** **Solo cuando se pide**: el PDF pasa a ser un proyecto, y sus hojas se quedan con lo ya anotado. */
    fun pasarAProyecto(nombre: String, paginas: Int): Boolean = runCatching {
        val repo = app?.proyectos ?: return false
        // Primero a disco, que el proyecto va a leer los dibujos de ahí.
        val todo = synchronized(abiertas) { abiertas.map { (i, c) -> i to c.scene } }
        // Con los nombres de antes de ser proyecto: los del adjunto, si lo es.
        val sueltos = HashMap<Int, String>()
        fun sueltoDe(i: Int) = sueltos.getOrPut(i) { suelto(i) }
        // Las hojas sin abrir también, que la tinta vieja está en otras unidades: el editor la
        // quiere en las de la hoja.
        val sinAbrir = (0 until paginas).filter { i -> todo.none { it.first == i } }
            .mapNotNull { i -> cargar(i, sueltoDe(i), migrar = false)?.let { i to it } }
        (todo + sinAbrir).forEach { (i, escena) ->
            com.forge.pixpin.motor.ExcalidrawStore.guardar(actividad, sueltoDe(i), escena)
            ponerElMarco(actividad, sueltoDe(i), rutaPedida, i, siempre = true)
        }
        synchronized(sucias) { sucias.clear() }
        val ahora = System.currentTimeMillis()
        // Un Word o un PowerPoint entra en el proyecto como su PDF en vectores, del mismo tamaño
        // de hoja: la tinta, en unidades de la hoja, cae en el mismo sitio.
        val pdf = if (!esOffice) rutaPedida else {
            val d = com.forge.pixpin.office.HojasOffice.yaAbierto(rutaPedida) ?: return false
            val destino = java.io.File(java.io.File(actividad.filesDir, "proyectos").apply { mkdirs() }, "doc-$ahora.pdf")
            if (com.forge.pixpin.office.OfficeAPdf.hacer(d, destino) <= 0) return false
            destino.path
        }
        var p = repo.deEstePdf(pdf, nombre, if (esOffice) PdfDoc.pageCount(pdf) else paginas, ahora)
        if (esOffice) actividad.getSharedPreferences("lectura", Context.MODE_PRIVATE).edit().putString("office:$rutaPedida:proyecto", p.id).apply()
        for (hoja in p.hojas) {
            val i = hoja.pagina ?: continue
            if (hoja.dibujo != null) continue
            val suelto = sueltoDe(i)
            if (!java.io.File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(actividad, suelto)).exists()) continue
            p = com.forge.pixpin.motor.Proyectos.conDibujo(p, hoja.id, suelto, System.currentTimeMillis())
        }
        repo.guardar(p)
        runCatching { com.forge.pixpin.motor.PdfDelProyecto.rehacer(actividad, p) }
        true
    }.getOrDefault(false)
}

/** El pintor de las capas que solo se miran: uno para todas las hojas, con su caché de formas. */
private val losPintoresDeCapas = HashMap<Boolean, com.forge.pixpin.motor.Renderer>()

/** Con la letra de verdad del lienzo, que si no un texto anotado se pintaría con otra y no cuadraría; [oscuro]: modo noche. */
private fun pintorDeCapas(contexto: Context, oscuro: Boolean): com.forge.pixpin.motor.Renderer =
    losPintoresDeCapas.getOrPut(oscuro) {
        com.forge.pixpin.motor.Renderer(typefaces = com.forge.pixpin.motor.DrawFonts.provider(contexto.applicationContext), dark = oscuro)
    }

/** La matriz de invertir, como filtro al pintar la imagen de una hoja (sin capa aparte: no se pixela al ampliar). */
private val FILTRO_INVERTIDO by lazy {
    androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(MATRIZ_INVERTIDA.array))
}

/**
 * **La capa de una hoja.** Leyendo —o con la mano puesta— **solo se pinta**, y no coge ningún toque:
 * la lista se desliza como siempre. Anotando, es el [com.forge.pixpin.motor.DrawCanvas] de
 * siempre, con la vista clavada a la hoja; al posar el dedo coge la herramienta y el estilo de la
 * barra. Un dedo dibuja; **dos dedos pasan las hojas** y amplían, que de eso se encarga el lector.
 * Con lápiz ([conLapiz]) dibuja solo el lápiz y **un dedo también pasa las hojas**.
 */
@Composable
private fun CapaDePagina(
    capas: CapasDelPdf, i: Int, anotando: Boolean, espacios: Int,
    maestro: com.forge.pixpin.motor.DrawController, modifier: Modifier,
    /** Con lápiz visto, el dedo no dibuja: sigue a la lista. Ver [com.forge.pixpin.motor.DrawCanvas]. */
    conLapiz: Boolean, alLapiz: () -> Unit,
    /** El modo oscuro del visor: la tinta, con el modo noche del motor (clara sobre papel oscuro). */
    oscuro: Boolean = false,
    alCambiar: () -> Unit
) {
    val lienzo by androidx.compose.runtime.produceState<com.forge.pixpin.motor.DrawController?>(null, capas, i) {
        value = withContext(Dispatchers.IO) { runCatching { capas.controladorDe(i) }.getOrNull() }
    }
    val c = lienzo ?: return
    // La hoja mide PAGE_WIDTH en las unidades del dibujo y **empieza en el cero**, como en el
    // editor completo; el margen de la izquierda son las equis negativas. Ver [vistaDeLaCapa].
    var anchoDeLaCapa by remember { mutableStateOf(0) }
    LaunchedEffect(c, anchoDeLaCapa, espacios) { if (anchoDeLaCapa > 0) c.setViewport(vistaDeLaCapa(anchoDeLaCapa.toDouble(), espacios)) }
    val medido = modifier.onSizeChanged { anchoDeLaCapa = it.width }
    val conLaMano = maestro.tool == com.forge.pixpin.motor.Tool.HAND
    val pintor = pintorDeCapas(androidx.compose.ui.platform.LocalContext.current, oscuro)
    if (!anotando || conLaMano) {
        androidx.compose.foundation.Canvas(medido) {
            @Suppress("UNUSED_EXPRESSION") capas.version.intValue
            if (c.scene.elements.none { !it.isDeleted }) return@Canvas
            pintor.renderScene(
                drawContext.canvas.nativeCanvas,
                c.scene.copy(viewport = vistaDeLaCapa(size.width.toDouble(), espacios)),
                size.width.toDouble(), size.height.toDouble()
            )
        }
        return
    }
    Box(
        medido.pointerInput(c, conLapiz) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    // Con lápiz, el dedo que pasa hojas no hace suya la hoja (deshacer sigue en la del lápiz); el texto
                    // sí se pone con el dedo, como en el lienzo.
                    if (e.changes.any { it.pressed && !it.previousPressed && (!conLapiz || it.type == androidx.compose.ui.input.pointer.PointerType.Stylus || maestro.tool == com.forge.pixpin.motor.Tool.TEXT) }) {
                        capas.tocada(i)
                        val herramienta = maestro.tool
                        if (herramienta !in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA && herramienta != c.tool) c.selectTool(herramienta)
                        if (c.scene.style != maestro.scene.style) c.cambiarEstilo(maestro.scene.style) { it }
                    }
                }
            }
        }
    ) {
        com.forge.pixpin.motor.DrawCanvas(
            controller = c,
            modifier = Modifier.fillMaxSize(),
            onChange = { trazando -> if (!trazando) { capas.ensuciar(i); alCambiar() } },
            vistaFija = true,
            dark = oscuro,
            modoLapiz = conLapiz,
            onLapizDetectado = alLapiz,
            dedoAlLector = true
        )
    }
}

/** Qué espacios hay puestos, en bits. Ver [com.forge.pixpin.ui.MandosDeLosLadosDeLector]. */
private const val ESPACIO_IZQUIERDA = 1
private const val ESPACIO_DERECHA = 2

/**
 * La vista de una capa de [ancho] píxeles: la hoja y los espacios puestos, de [Lectura.MARGEN_DEL_PDF] cada uno.
 *
 * **La hoja va siempre de 0 a [PdfDoc.PAGE_WIDTH]**, pongan o quiten espacios (30-sep-2026). Antes la
 * vista abarcaba siempre la hoja y los dos márgenes aunque la capa midiera menos, así que las unidades
 * dependían de los espacios: al añadir uno, lo anotado se corría y cambiaba de tamaño, y el PC y el
 * editor completo lo ponían en otro sitio. Ahora son las del editor, y lo escrito con las de antes se
 * trae con su marco ([tintaDeLaHoja]).
 */
private fun vistaDeLaCapa(ancho: Double, espacios: Int): com.forge.pixpin.motor.Viewport {
    val margen = PdfDoc.PAGE_WIDTH * Lectura.MARGEN_DEL_PDF.toDouble()
    val izquierda = if (espacios and ESPACIO_IZQUIERDA != 0) 1 else 0
    val lados = izquierda + (if (espacios and ESPACIO_DERECHA != 0) 1 else 0)
    return com.forge.pixpin.motor.Viewport(scrollX = margen * izquierda, scrollY = 0.0, zoom = ancho / (PdfDoc.PAGE_WIDTH + margen * lados))
}

/** Lo que mide una hoja: ancho entre alto, como la dibuja la lista. */
private fun proporcionDeLaHoja(rutaPdf: String, i: Int): Double =
    medidaDeLaHoja(rutaPdf, i)?.let { (an, al) -> an / al }?.takeIf { it > 0.01 } ?: 0.7

/** Dónde está la hoja en las unidades del lector y del editor: siempre la misma. */
private fun marcoDeLaHoja(rutaPdf: String, i: Int) =
    com.forge.pixpin.sincro.AnotacionesDelAdjunto.Marco.deHoja(PdfDoc.PAGE_WIDTH.toDouble(), proporcionDeLaHoja(rutaPdf, i))

/**
 * **La tinta de la hoja [i] en las unidades de la hoja**, con cualquier marco que se escribiera.
 *
 * Con su `.hoja` (lo escrito desde el 30-sep-2026, aquí o en el PC), se lleva de ese marco al de la
 * hoja. Sin él: si es tinta **suelta** (un PDF que no es de un proyecto), está en las unidades del
 * lector de antes, que dependían de [espacios] ([com.forge.pixpin.sincro.AnotacionesDelAdjunto.Marco.delLectorViejo]);
 * y con [migrar] se le apunta ese marco al lado, sin tocar la tinta, para que ya no dependa de los
 * espacios que se pongan después. La de un proyecto es el dibujo de su hoja: ya está en estas unidades.
 */
internal fun tintaDeLaHoja(
    c: Context, id: String, rutaPdf: String, i: Int, espacios: Int, suelta: Boolean, migrar: Boolean
): com.forge.pixpin.motor.Scene? {
    val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(c, id)) ?: return null
    val archivo = com.forge.pixpin.sincro.AnotacionesDelAdjunto.hoja(c.filesDir, id)
    val escrito = com.forge.pixpin.sincro.AnotacionesDelAdjunto.leerMarco(archivo)
        ?: if (!suelta) return escena
        else com.forge.pixpin.sincro.AnotacionesDelAdjunto.Marco.delLectorViejo(
            espacios, PdfDoc.PAGE_WIDTH.toDouble(), Lectura.MARGEN_DEL_PDF.toDouble(), proporcionDeLaHoja(rutaPdf, i)
        ).also { if (migrar && escena.elements.isNotEmpty()) com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribirMarco(archivo, it) }
    return escrito.llevar(escena, marcoDeLaHoja(rutaPdf, i))
}

/**
 * Tras guardar la tinta [id], su marco: el de la hoja, que es en lo que se escribe. Lo lleva la
 * tinta suelta ([siempre]); la de un proyecto, solo si ya tenía uno (lo trajo del chat).
 */
private fun ponerElMarco(c: Context, id: String, rutaPdf: String, i: Int, siempre: Boolean) {
    val archivo = com.forge.pixpin.sincro.AnotacionesDelAdjunto.hoja(c.filesDir, id)
    if (siempre || archivo.exists()) com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribirMarco(archivo, marcoDeLaHoja(rutaPdf, i))
}

private val PAPEL_DEL_MARGEN = Color(0xFFF3F3F0)

/**
 * **Lo que mide la hoja [i]**, en puntos: de un PDF, o de un Word o un PowerPoint ya abierto
 * (11-oct-2026; sus hojas las maqueta el .so del PC). Así el lector, la tinta y su marco (`.hoja`)
 * no distinguen uno de otro.
 */
internal fun medidaDeLaHoja(ruta: String, i: Int): Pair<Double, Double>? =
    if (com.forge.pixpin.office.HojasOffice.es(ruta))
        com.forge.pixpin.office.HojasOffice.yaAbierto(ruta)?.medida(i)?.let { it[0].toDouble() to it[1].toDouble() }
    else PdfDoc.medidaEnPuntos(ruta, i)

/** La hoja [i] pintada a [ancho] píxeles: el PDF rasterizado, o el Word/PowerPoint pintado en vectores a ese ancho. */
private fun pintarLaHoja(ruta: String, i: Int, ancho: Int, oscuro: Boolean = false): Bitmap? =
    if (com.forge.pixpin.office.HojasOffice.es(ruta)) com.forge.pixpin.office.HojasOffice.yaAbierto(ruta)?.pintar(i, ancho, oscuro = oscuro)
    else PdfDoc.render(ruta, i, ancho)

/** Invertir colores hace falta un efecto de la tarjeta que llegó con Android 12. */
private val PUEDE_INVERTIR = android.os.Build.VERSION.SDK_INT >= 31

/** El margen de una hoja de Office en el modo oscuro: [PAPEL_DEL_MARGEN] con la luz invertida, como la hoja. */
private val MARGEN_OSCURO = Color(com.forge.pixpin.office.Oscuro.invertirLuz(0xFFF3F3F0.toInt()))

/**
 * **Los colores invertidos** de una hoja con su tinta (PC `11bd1f6`: la «N» invierte cualquier pin
 * y los lectores): el blanco pasa a negro y el negro a blanco, pero **el tono se queda** (se invierte
 * y se gira el matiz media vuelta, como el «invertir inteligente» del teléfono): una raya roja sigue
 * siendo roja y una foto no sale en negativo de color. Sin [PUEDE_INVERTIR], nada.
 */
internal fun Modifier.coloresInvertidos(si: Boolean): Modifier =
    if (!si || !PUEDE_INVERTIR) this else this.graphicsLayer {
        renderEffect = android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(MATRIZ_INVERTIDA))
            .asComposeRenderEffect()
    }

/** Invertir y luego girar el matiz 180° (la de CSS `invert(1) hue-rotate(180deg)`). */
internal val MATRIZ_INVERTIDA: android.graphics.ColorMatrix by lazy {
    val giro = android.graphics.ColorMatrix(floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    giro.preConcat(android.graphics.ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))
    giro
}

private object PaginasEnMemoria {
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val anchos = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** La clave de una hoja: la de Office en el modo oscuro ([oscuro]) es otra hoja (la de un PDF no cambia: la matriz va al pintarla). */
    private fun clave(ruta: String, i: Int, oscuro: Boolean) = if (oscuro) "$ruta#$i#o" else "$ruta#$i"

    /** La mejor que haya de esa página, sin esperar. */
    fun alguna(ruta: String, i: Int, oscuro: Boolean = false): Bitmap? = clave(ruta, i, oscuro).let { k -> anchos[k]?.let { cache.get("$k#$it") } }

    /** Lo guardado de [ruta] ya no vale: el documento ha cambiado (se ha anotado). */
    fun olvidar(ruta: String) {
        anchos.keys.filter { it.startsWith("$ruta#") }.forEach { anchos.remove(it) }
        cache.snapshot().keys.filter { it.startsWith("$ruta#") }.forEach { cache.remove(it) }
    }

    /** Trabajo de disco. */
    fun pintar(ruta: String, i: Int, ancho: Int, oscuro: Boolean = false): Bitmap? {
        val k = clave(ruta, i, oscuro)
        cache.get("$k#$ancho")?.let { return it }
        val b = runCatching { pintarLaHoja(ruta, i, ancho, oscuro) }.getOrNull() ?: return null
        cache.put("$k#$ancho", b)
        anchos[k] = ancho
        return b
    }
}

/**
 * **Una página con sus sublienzos, a solas.**
 *
 * La hoja en el centro, sus miniaturas a los lados unidas a su recuadro. **Se acerca y se pasea
 * con los dedos** para ver el panorama entero o el detalle, y no se sale por pellizcar: se sale
 * con atrás o con la flecha (usuario, 14-sep-2026). Al acercarse, la hoja se vuelve a pedir con
 * más puntos, por escalones y cuando la mano para. Las miniaturas salen de la caché de
 * [SublienzosDelPdf.miniatura].
 */
@Composable
private fun VistaDeLaPagina(ruta: String, i: Int, suyos: List<SublienzosDelPdf.Recorte>, anchoPx: Int, invertido: Boolean, oscuroOffice: Boolean = false, alCerrar: () -> Unit) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val proporcion = remember(ruta, i) {
        medidaDeLaHoja(ruta, i)?.let { (an, al) -> (an / al).toFloat() } ?: 0.7f
    }
    var escala by remember(i) { mutableStateOf(1f) }
    var desplazado by remember(i) { mutableStateOf(Offset(0f, 0f)) }
    var tocando by remember { mutableStateOf(false) }
    var escalaFirme by remember(i) { mutableStateOf(1f) }
    LaunchedEffect(escala, tocando) {
        if (tocando) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        escalaFirme = escala
    }
    val escalon = when {
        escalaFirme <= 1.3f -> 1
        escalaFirme <= 2.6f -> 2
        else -> 4
    }
    var mapa by remember(ruta, i, oscuroOffice) { mutableStateOf(PaginasEnMemoria.alguna(ruta, i, oscuroOffice)) }
    LaunchedEffect(ruta, i, escalon, oscuroOffice) {
        mapa = withContext(Dispatchers.IO) {
            val cabe = kotlin.math.sqrt(PIXELES_POR_HOJA * proporcion.coerceAtLeast(0.01f)).toInt()
            PaginasEnMemoria.pintar(ruta, i, minOf((anchoPx * 0.6f * escalon).toInt().coerceIn(320, 4000), cabe).coerceAtLeast(320), oscuroOffice)
        } ?: mapa
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1B))
            .clipToBounds()
            .pointerInput(i) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val evento = awaitPointerEvent()
                        val dedos = evento.changes.count { it.pressed }
                        if (dedos == 0) { tocando = false; break }
                        val zoom = evento.calculateZoom()
                        val pan = evento.calculatePan()
                        // Un dedo pasea y dos acercan; un toque sin moverse sigue llegando a las miniaturas.
                        if (dedos < 2 && pan.getDistance() < 0.5f && zoom == 1f) continue
                        tocando = true
                        val antes = escala
                        val ahora = (antes * zoom).coerceIn(0.4f, 8f)
                        val foco = evento.calculateCentroid(useCurrent = false)
                        // El punto entre los dedos se queda quieto; el origen de la escala es la esquina.
                        desplazado = foco - (foco - desplazado) * (ahora / antes) + pan
                        escala = ahora
                        evento.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .padding(top = 56.dp, bottom = 24.dp)
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    scaleX = escala; scaleY = escala
                    translationX = desplazado.x; translationY = desplazado.y
                }
                .coloresInvertidos(invertido)
        ) {
            HojaConSublienzos(mapa, proporcion, suyos, abierta = true) { r ->
                com.forge.pixpin.motor.DrawEditorActivity.abrir(
                    contexto, r.dibujo, com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, r.dibujo), null, desdeProyecto = r.proyecto
                )
            }
        }
        androidx.compose.foundation.layout.Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .then(
                    if (com.forge.pixpin.ui.theme.LocalCosmos.current) Modifier.cristal(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    else Modifier.background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                )
                .clickable(onClick = alCerrar)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver a las páginas",
                tint = Color.White, modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                "Página ${i + 1} · ${suyos.size} ${if (suyos.size == 1) "sublienzo" else "sublienzos"}",
                color = Color.White, style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Cuántos píxeles como mucho tiene la hoja rasterizada de una página.
 *
 * Cuatro millones son dieciséis megas en ARGB, que un teléfono da sin pestañear y que a una
 * pantalla de móvil le sobran: son casi cuatro veces sus píxeles. Por encima de eso lo único
 * que se gana es esperar. Ver [LectorPdfActivity.Hoja].
 */
private const val PIXELES_POR_HOJA = 4_000_000.0
