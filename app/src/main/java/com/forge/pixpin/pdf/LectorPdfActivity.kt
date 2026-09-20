package com.forge.pixpin.pdf

import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

        /** Con [anotando], se abre ya con el editor rápido puesto sobre la primera página. */
        fun abrir(context: Context, ruta: String, nombre: String, anotando: Boolean = false) {
            context.startActivity(
                Intent(context, LectorPdfActivity::class.java)
                    .putExtra(EXTRA_RUTA, ruta)
                    .putExtra(EXTRA_NOMBRE, nombre)
                    .putExtra(EXTRA_ANOTAR, anotando)
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
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        fun dejarDeAnotar() { anotando = false; capas.guardarTodo() }
        androidx.activity.compose.BackHandler(enabled = anotando) { dejarDeAnotar() }
        androidx.compose.runtime.DisposableEffect(capas) { onDispose { capas.guardarTodo() } }
        val quiereAnotarAlAbrir = remember { intent?.getBooleanExtra(EXTRA_ANOTAR, false) == true }
        LaunchedEffect(cuantas) { if (quiereAnotarAlAbrir && cuantas > 0) anotando = true }
        LaunchedEffect(rutaPedida) {
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1B1B1B))
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
                        // **Alejar una página con recortes la abre a solas.** Solo con el documento
                        // a su tamaño: acercado, el pellizco sigue siendo el zoom.
                        var acumulado = 1f
                        var decidido = false
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedos = evento.changes.count { it.pressed }
                            if (dedos == 0) { pellizcando = false; break }
                            if (dedos >= 2) { cogido = true; pellizcando = true }
                            if (!cogido) continue
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
                            val ahora = (antes * factor).coerceIn(1f, 6f)
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
                            val topeX = (ahora - 1f) * size.width / 2f
                            val topeY = (ahora - 1f) * size.height / 2f
                            // Anotando, un dedo dibuja: los dos dedos son los que pasan las hojas.
                            if (anotando) estado.dispatchRawDelta(-evento.calculatePan().y / ahora)
                            val conPan = movido + evento.calculatePan()
                            desplazado = Offset(
                                conPan.x.coerceIn(-topeX, topeX),
                                conPan.y.coerceIn(-topeY, topeY)
                            )
                            if (ahora <= 1.001f) desplazado = Offset.Zero
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
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom, scaleY = zoom,
                                translationX = desplazado.x, translationY = desplazado.y,
                                alpha = if (aSolas == null) 1f else 0f
                            )
                    ) {
                        items((0 until cuantas).toList()) { i ->
                            Hoja(ruta, i, zoomFirme, anchoPx, recortes[i].orEmpty(), encima = {
                                CapaDePagina(capas, i, anchoPx, anotando, maestro, Modifier.matchParentSize()) { tickDeLaBarra++ }
                            }) { aSolas = i }
                        }
                    }
                    aSolas?.let { i -> VistaDeLaPagina(ruta, i, recortes[i].orEmpty(), anchoPx) { aSolas = null } }
                }
            }
            // **El lápiz del editor rápido**: pequeño y semitransparente, abajo a un lado.
            if (cuantas > 0 && aSolas == null && !anotando) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(18.dp)
                        .size(52.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0x9914182B))
                        .clickable { anotando = true },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Edit, contentDescription = "Anotar",
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                }
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
                            permitidas = ajustes.lectorToolSet - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                            grupos = ajustes.lectorGroupList.map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() }
                        )
                    }
                }
            }
            if (nombre.isNotBlank()) {
                Text(
                    nombre,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
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
            PdfDoc.medidaEnPuntos(ruta, i)?.let { (an, al) -> (an / al).toFloat() } ?: 0.7f
        }
        var mapa by remember(ruta, i) { mutableStateOf(PaginasEnMemoria.alguna(ruta, i)) }
        LaunchedEffect(ruta, i, escalon) {
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
                PaginasEnMemoria.pintar(ruta, i, minOf(pedido, cabe).coerceAtLeast(320))
            } ?: mapa
        }
        val actual = mapa
        if (suyos.isNotEmpty()) {
            // En la lista solo los recuadros y el aviso: las miniaturas, en la vista a solas.
            HojaConSublienzos(actual, proporcion, suyos, abierta = false, alPedirlos = alAbrirSublienzos, encima = encima) { }
            return
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            if (actual != null) {
                Image(
                    bitmap = actual.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
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
 *   dibujo suelto del propio PDF (`pdf-<huella>-p<n>`).
 * - **No se crea ningún proyecto** por anotar. Solo con «Al proyecto» ([pasarAProyecto]), que
 *   además le entrega al proyecto nuevo los dibujos ya hechos.
 * - **Lo anotado se pinta siempre encima de cada hoja**, leyendo su dibujo: no depende de rehacer
 *   el archivo. Por eso el lector enseña la copia **limpia** de un PDF de proyecto.
 */
private class CapasDelPdf(private val actividad: ComponentActivity, private val rutaPedida: String) {
    private val app = actividad.application as? com.forge.pixpin.PixPinApp
    private val huella = rutaPedida.hashCode().toUInt().toString(16)
    private val abiertas = HashMap<Int, com.forge.pixpin.motor.DrawController>()
    private val sucias = HashSet<Int>()
    private var ultimaTocada = -1
    /** Para que las capas que solo se pintan se repinten cuando cambia algo. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    private fun proyecto(): com.forge.pixpin.motor.Proyecto? =
        app?.proyectos?.proyectos?.value?.firstOrNull { it.pdfOrigen == rutaPedida || it.pdfLimpio == rutaPedida }

    fun esDeUnProyecto() = proyecto() != null

    fun documentoLimpio(): String? = proyecto()?.pdfLimpio?.takeIf { java.io.File(it).exists() }

    /** El dibujo de la hoja [i]. En un proyecto, el de su hoja; se le pone uno si aún no tenía. */
    private fun idDe(i: Int, paraEscribir: Boolean): String {
        val p = proyecto() ?: return "pdf-$huella-p$i"
        val hoja = p.hojas.firstOrNull { it.pagina == i } ?: return "pdf-$huella-p$i"
        hoja.dibujo?.let { return it }
        val suelto = "pdf-$huella-p$i"
        if (paraEscribir) app?.proyectos?.guardar(com.forge.pixpin.motor.Proyectos.conDibujo(p, hoja.id, suelto, System.currentTimeMillis()))
        return suelto
    }

    /** Trabajo de disco la primera vez. */
    fun controladorDe(i: Int): com.forge.pixpin.motor.DrawController = synchronized(abiertas) {
        abiertas.getOrPut(i) {
            val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(actividad, idDe(i, false)))
            com.forge.pixpin.motor.DrawController(escena ?: com.forge.pixpin.motor.Scene()).also { it.pedirLaMedida = false }
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
            escenas.forEachIndexed { k, (_, escena) -> runCatching { com.forge.pixpin.motor.ExcalidrawStore.guardar(contexto, ids[k], escena) } }
            proyecto()?.let { p -> runCatching { com.forge.pixpin.motor.PdfDelProyecto.rehacer(contexto, p) } }
        }
    }

    /** **Solo cuando se pide**: el PDF pasa a ser un proyecto, y sus hojas se quedan con lo ya anotado. */
    fun pasarAProyecto(nombre: String, paginas: Int): Boolean = runCatching {
        val repo = app?.proyectos ?: return false
        // Primero a disco, que el proyecto va a leer los dibujos de ahí.
        val todo = synchronized(abiertas) { abiertas.map { (i, c) -> i to c.scene } }
        todo.forEach { (i, escena) -> com.forge.pixpin.motor.ExcalidrawStore.guardar(actividad, "pdf-$huella-p$i", escena) }
        synchronized(sucias) { sucias.clear() }
        var p = repo.deEstePdf(rutaPedida, nombre, paginas, System.currentTimeMillis())
        for (hoja in p.hojas) {
            val i = hoja.pagina ?: continue
            if (hoja.dibujo != null) continue
            val suelto = "pdf-$huella-p$i"
            if (!java.io.File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(actividad, suelto)).exists()) continue
            p = com.forge.pixpin.motor.Proyectos.conDibujo(p, hoja.id, suelto, System.currentTimeMillis())
        }
        repo.guardar(p)
        runCatching { com.forge.pixpin.motor.PdfDelProyecto.rehacer(actividad, p) }
        true
    }.getOrDefault(false)
}

/** El pintor de las capas que solo se miran: uno para todas las hojas, con su caché de formas. */
private var elPintorDeCapas: com.forge.pixpin.motor.Renderer? = null

/** Con la letra de verdad del lienzo, que si no un texto anotado se pintaría con otra y no cuadraría. */
private fun pintorDeCapas(contexto: Context): com.forge.pixpin.motor.Renderer =
    elPintorDeCapas ?: com.forge.pixpin.motor.Renderer(typefaces = com.forge.pixpin.motor.DrawFonts.provider(contexto.applicationContext)).also { elPintorDeCapas = it }

/**
 * **La capa de una hoja.** Leyendo —o con la mano puesta— **solo se pinta**, y no coge ningún toque:
 * la lista se desliza como siempre. Anotando, es el [com.forge.pixpin.motor.DrawCanvas] de
 * siempre, con la vista clavada a la hoja; al posar el dedo coge la herramienta y el estilo de la
 * barra. Un dedo dibuja; **dos dedos pasan las hojas** y amplían, que de eso se encarga el lector.
 */
@Composable
private fun CapaDePagina(
    capas: CapasDelPdf, i: Int, anchoPx: Int, anotando: Boolean,
    maestro: com.forge.pixpin.motor.DrawController, modifier: Modifier, alCambiar: () -> Unit
) {
    val lienzo by androidx.compose.runtime.produceState<com.forge.pixpin.motor.DrawController?>(null, capas, i) {
        value = withContext(Dispatchers.IO) { runCatching { capas.controladorDe(i) }.getOrNull() }
    }
    val c = lienzo ?: return
    // La hoja mide PAGE_WIDTH en las unidades del dibujo, y en pantalla lo que mida la lista.
    val zoom = anchoPx.toDouble() / PdfDoc.PAGE_WIDTH
    LaunchedEffect(c, anchoPx) { c.setViewport(com.forge.pixpin.motor.Viewport(scrollX = 0.0, scrollY = 0.0, zoom = zoom)) }
    val conLaMano = maestro.tool == com.forge.pixpin.motor.Tool.HAND
    val pintor = pintorDeCapas(androidx.compose.ui.platform.LocalContext.current)
    if (!anotando || conLaMano) {
        androidx.compose.foundation.Canvas(modifier) {
            @Suppress("UNUSED_EXPRESSION") capas.version.intValue
            if (c.scene.elements.none { !it.isDeleted }) return@Canvas
            pintor.renderScene(
                drawContext.canvas.nativeCanvas,
                c.scene.copy(viewport = com.forge.pixpin.motor.Viewport(scrollX = 0.0, scrollY = 0.0, zoom = size.width.toDouble() / PdfDoc.PAGE_WIDTH)),
                size.width.toDouble(), size.height.toDouble()
            )
        }
        return
    }
    Box(
        modifier.pointerInput(c) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.changes.any { it.pressed && !it.previousPressed }) {
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
            vistaFija = true
        )
    }
}

private object PaginasEnMemoria {
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val anchos = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** La mejor que haya de esa página, sin esperar. */
    fun alguna(ruta: String, i: Int): Bitmap? = anchos["$ruta#$i"]?.let { cache.get("$ruta#$i#$it") }

    /** Lo guardado de [ruta] ya no vale: el documento ha cambiado (se ha anotado). */
    fun olvidar(ruta: String) {
        anchos.keys.filter { it.startsWith("$ruta#") }.forEach { anchos.remove(it) }
        cache.snapshot().keys.filter { it.startsWith("$ruta#") }.forEach { cache.remove(it) }
    }

    /** Trabajo de disco. */
    fun pintar(ruta: String, i: Int, ancho: Int): Bitmap? {
        cache.get("$ruta#$i#$ancho")?.let { return it }
        val b = runCatching { PdfDoc.render(ruta, i, ancho) }.getOrNull() ?: return null
        cache.put("$ruta#$i#$ancho", b)
        anchos["$ruta#$i"] = ancho
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
private fun VistaDeLaPagina(ruta: String, i: Int, suyos: List<SublienzosDelPdf.Recorte>, anchoPx: Int, alCerrar: () -> Unit) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val proporcion = remember(ruta, i) {
        PdfDoc.medidaEnPuntos(ruta, i)?.let { (an, al) -> (an / al).toFloat() } ?: 0.7f
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
    var mapa by remember(ruta, i) { mutableStateOf(PaginasEnMemoria.alguna(ruta, i)) }
    LaunchedEffect(ruta, i, escalon) {
        mapa = withContext(Dispatchers.IO) {
            val cabe = kotlin.math.sqrt(PIXELES_POR_HOJA * proporcion.coerceAtLeast(0.01f)).toInt()
            PaginasEnMemoria.pintar(ruta, i, minOf((anchoPx * 0.6f * escalon).toInt().coerceIn(320, 4000), cabe).coerceAtLeast(320))
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
