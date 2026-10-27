package com.forge.pixpin.excel


import com.forge.pixpin.ui.EditorAvanzadoDeVisor
import androidx.compose.runtime.collectAsState

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **El visor de hojas de Excel** (11-oct-2026, lo del PC: `apps/pixpin/src/visor_hoja.rs`).
 *
 * A pantalla completa, con la hoja como la pinta Excel ([VistaHoja]) y lo de PixPin alrededor en
 * cristal: arriba, volver, el nombre, **Copiar** (lo elegido como texto con tabuladores, lo que se
 * ve en cada celda), **A tabla** (lo de antes: el libro pasado a Tablas de PixPin, editable) y
 * **Abrir con** (otra aplicación); debajo, la línea de la celda (su referencia, lo que enseña y su
 * enlace); abajo, las pestañas de las hojas y lo que falta por leer.
 *
 * Se abre al instante: la primera pantalla sale con las primeras filas y el resto llega por
 * detrás ([LibroExcel]).
 *
 * **El único visor de hojas de cálculo** (25-oct-2026, el usuario: «quita la visualización de Excel
 * antigua y todo lo incorporas en la nueva… solo visualización normal, pero que se pueda anotar»):
 * aquí se ven también los `.xls`/`.ods`/`.csv` que el .so no lee, los libros con hojas editadas y
 * las tablas del chat y de los proyectos ([abrirTablas]), pasadas al visor ([TablasEnElVisor]).
 * Nada se edita; la vista de Tablas queda sin entrada ([EntradaDeHojas]). Lo que no se deja leer,
 * con otra aplicación. Arriba la pastilla, a la derecha el riel y abajo la tira de pestañas como
 * la del PC (`hoja_office::pintar_pestanas`); «Copiar» va en la línea de la celda.
 *
 * **Luna/sol** (PC 74801b4): la hoja en oscuro o en claro, para todos los visores a la vez y
 * recordado en este aparato ([com.forge.pixpin.office.Oscuro]). En la línea de la celda, **la nota**
 * de la celda (si la tiene) y **su lista desplegable** (los valores, solo para ver), como el PC.
 */
class ExcelActivity : ComponentActivity() {
    private var libro: LibroExcel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val ruta = intent?.getStringExtra(EXTRA_RUTA)
        val tablas = intent?.getStringArrayListExtra(EXTRA_TABLAS).orEmpty()
        if (ruta == null && tablas.isEmpty()) { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { ruta?.let { File(it).name }.orEmpty() }
        val mensaje = intent?.getStringExtra(EXTRA_MENSAJE)
        val activa = intent?.getStringExtra(EXTRA_ACTIVA)
        setContent { PixPinTheme { Pantalla(ruta, tablas, activa, nombre, mensaje) } }
    }

    override fun onDestroy() {
        libro?.cerrar()
        libro = null
        super.onDestroy()
    }

    /** A un marco: su hoja, acercada hasta llenar la zona de las celdas y centrado. */
    private fun irAlMarco(m: com.forge.pixpin.motor.MarcosDelDocumento.Marco, v: VistaHoja?, alCambiarDeHoja: (Int) -> Unit) {
        v ?: return
        if (m.parte != v.indice) { v.ponerHoja(m.parte); alCambiarDeHoja(m.parte) }
        val px = com.forge.pixpin.motor.MarcosDelDocumento.pxParaLlenar(m, v.anchoDeCeldas.toDouble(), v.altoDeCeldas.toDouble())
        val densidad = resources.displayMetrics.density
        val z = px / densidad
        val e = (z * densidad).coerceAtLeast(1e-6)
        v.mirarDesde(m.centroX - v.anchoDeCeldas / 2.0 / e, m.centroY - v.altoDeCeldas / 2.0 / e, z)
    }

    @Composable
    private fun Pantalla(ruta: String?, ids: List<String>, activaId: String?, nombreDado: String, mensaje: String?) {
        var abierto by remember { mutableStateOf<LibroExcel?>(null) }
        var nombre by remember { mutableStateOf(nombreDado) }
        var vista by remember { mutableStateOf<VistaHoja?>(null) }
        /**
         * Sube con cada cambio de la selección, de lo leído o del zoom. Solo la leen la línea de la
         * celda y el estado de abajo (cada uno en su composable): desplazar la hoja no recompone nada.
         */
        val marca = remember { mutableIntStateOf(0) }
        var activa by remember { mutableIntStateOf(0) }
        var oscuro by remember { mutableStateOf(com.forge.pixpin.office.Oscuro.activo(this@ExcelActivity)) }
        // **Exportar** (14-oct-2026, como el PC): la hoja de compartir de siempre. Ver [ExportarExcel].
        var exportando by remember { mutableStateOf<com.forge.pixpin.ui.Compartible?>(null) }

        // ---- **El riel de todos los visores** (24-oct-2026, como el PC `visor_hoja/riel.rs`):
        // marcadores en una celda, el lápiz con la tinta de cada hoja y los marcos numerados. Lo del
        // chat va con el código de su mensaje y viaja:
        // - `anot-<uid>.marcas`: `id:columna:hoja·10⁷+fila:emoji` ([POR_HOJA]);
        // - `anot-<uid>.vistas.json`: el aumento y la esquina de cada marcador;
        // - `anot-<uid>-h<i>.excalidraw.gz`: la tinta de la hoja `i`, en píxeles de la hoja al 100 %;
        // - `anot-<uid>.marcos.json`: los marcos, con `parte` = la hoja.
        // Una tabla (25-oct-2026) usa el código de lo que la lleva: ver [HojasParaVer.uidDeTabla]. ----
        var base by remember { mutableStateOf<String?>(null) }
        var listo by remember { mutableStateOf(false) }
        /** Las tablas que se ven, si no es el archivo leído por el .so: lo que se exporta. */
        var tablasVistas by remember { mutableStateOf<List<com.forge.pixpin.motor.TablaDeCalculo>?>(null) }

        // **Todo se abre aquí, solo para ver y anotar** (25-oct-2026, el usuario: «quita la
        // visualización de Excel antigua y todo lo incorporas en la nueva»): un archivo con el .so o
        // por sus tablas, y las tablas del chat y de los proyectos. Lo que no se deja leer, con otra
        // aplicación; nunca la vista de Tablas. Ver [HojasParaVer].
        LaunchedEffect(ruta, ids) {
            val leido = withContext(Dispatchers.IO) {
                runCatching { if (ruta != null) HojasParaVer.deArchivo(applicationContext, ruta, nombreDado, mensaje) else HojasParaVer.deIds(applicationContext, ids, activaId) }
            }
            val a = leido.getOrNull()
            if (a == null) {
                Toast.makeText(
                    this@ExcelActivity,
                    (leido.exceptionOrNull() as? com.forge.pixpin.motor.ImportarHojas.NoSeLee)?.message
                        ?: if (ruta != null) "No se pudo leer la hoja de cálculo" else "No se encontró la tabla",
                    Toast.LENGTH_LONG
                ).show()
                if (ruta != null) com.forge.pixpin.ui.AbrirCon.abrir(this@ExcelActivity, File(ruta))
                finish()
                return@LaunchedEffect
            }
            val l = a.libro
            libro = l
            if (nombre.isBlank()) nombre = a.nombre
            base = a.uid?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.delPdf(it) }
            listo = true
            tablasVistas = a.tablas
            activa = l.libro.activa.takeIf { l.libro.hojas.getOrNull(it)?.let { h -> h.visible && h.tipo == 0 } == true }
                ?: l.libro.hojas.indexOfFirst { it.visible && it.tipo == 0 }.coerceAtLeast(0)
            abierto = l
        }

        // **Como los otros visores** (13-oct-2026, el usuario: «arriba aparece un espacio; que la
        // interfaz del Excel se iguale a los otros visores»): la hoja a pantalla completa, sin barras
        // del sistema ni barra fija; arriba la pastilla de los lectores ([com.forge.pixpin.ui.PastillaDeLector]),
        // que sale al tocar y se va sola; abajo, flotando, la línea de la celda y las pestañas.
        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableIntStateOf(0) }

        val marcosDoc = remember(base) { com.forge.pixpin.ui.MarcosDelVisor(base?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivo(filesDir, it) }) }
        val vistas = remember(base) { com.forge.pixpin.ui.VistasDelVisor(base?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivoDeVistas(filesDir, it) }) }
        val archivoDeMarcas = remember(base) { base?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.marcas(filesDir, it) } }
        var marcas by remember(base) {
            mutableStateOf(com.forge.pixpin.motor.Marcas.deTexto(archivoDeMarcas?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(it) }))
        }
        fun guardarMarcas() { archivoDeMarcas?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribir(it, com.forge.pixpin.motor.Marcas.aTexto(marcas)) } }
        var poniendoMarca by remember { mutableStateOf(false) }
        var anotando by remember { mutableStateOf(false) }
        var cambiosDeTinta by remember { mutableIntStateOf(0) }
        // La tinta de la hoja que se ve: su propio dibujo, y su vista la de la hoja.
        val idDeLaTinta = base?.let { "$it-h$activa" }
        val tinta = remember(idDeLaTinta) {
            com.forge.pixpin.motor.DrawController(
                idDeLaTinta?.let { com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@ExcelActivity, it)) }
                    ?: com.forge.pixpin.motor.Scene()
            ).also { it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
        }
        var tickDeTinta by remember { mutableIntStateOf(0) }
        val pintorDeTinta = remember(oscuro) {
            com.forge.pixpin.motor.Renderer(
                imageProvider = { f -> tinta.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                typefaces = com.forge.pixpin.motor.DrawFonts.provider(this@ExcelActivity),
                dark = oscuro
            )
        }
        fun guardarTinta() {
            val id = idDeLaTinta ?: return
            if (cambiosDeTinta == 0) return
            cambiosDeTinta = 0
            com.forge.pixpin.motor.ExcalidrawStore.guardar(this@ExcelActivity, id, tinta.scene)
        }
        DisposableEffect(tinta) { onDispose { guardarTinta() } }
        var ocultarParaImprimir by remember { mutableStateOf(false) }
        fun imprimirLosMarcos() {
            lifecycleScope.launch {
                com.forge.pixpin.ui.ImprimirMarcos.imprimir(
                    this@ExcelActivity, marcosDoc, nombre.substringBeforeLast('.'),
                    ir = { m -> irAlMarco(m, vista) { activa = it }; vista?.invalidate() },
                    cajaEnVentana = { m ->
                        val v = vista ?: return@imprimir null
                        val a = v.aVista(m.x0, m.y0) ?: return@imprimir null
                        val b = v.aVista(m.x1, m.y1) ?: return@imprimir null
                        val en = IntArray(2).also { v.getLocationInWindow(it) }
                        androidx.compose.ui.geometry.Rect(a.x + en[0], a.y + en[1], b.x + en[0], b.y + en[1])
                    }
                )
            }
        }
        /** «Imprimir todo»: la hoja tal como se ve, con lo anotado y sin los mandos. */
        fun imprimirLaHoja() {
            val v = vista ?: return
            val en = IntArray(2).also { v.getLocationInWindow(it) }
            val caja = androidx.compose.ui.geometry.Rect(en[0].toFloat(), en[1].toFloat(), (en[0] + v.width).toFloat(), (en[1] + v.height).toFloat())
            lifecycleScope.launch {
                com.forge.pixpin.ui.ImprimirMarcos.imprimirLoQueSeVe(this@ExcelActivity, caja, nombre.substringBeforeLast('.')) { ocultarParaImprimir = it; v.marcosOcultos = it; v.invalidate() }
            }
        }
        androidx.activity.compose.BackHandler(enabled = anotando || marcosDoc.activo || poniendoMarca) {
            when {
                anotando -> { anotando = false; guardarTinta() }
                poniendoMarca -> poniendoMarca = false
                else -> marcosDoc.activo = false
            }
        }
        val ajustes by (application as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
        LaunchedEffect(aLaVista, toques) { if (aLaVista) { kotlinx.coroutines.delay(3500); aLaVista = false } }
        Box(Modifier.fillMaxSize().background(if (oscuro) Color(com.forge.pixpin.office.Oscuro.PAPEL) else Color.White)) {
            val l = abierto
            if (l == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        VistaHoja(c, l).also { nueva ->
                            nueva.oscuro = oscuro
                            nueva.alCambiar = { marca.intValue++ }
                            nueva.alTocar = { aLaVista = true; toques++ }
                            // Mover la hoja esconde la pastilla y el riel, como en los demás visores.
                            nueva.alMover = { if (!anotando) aLaVista = false }
                            nueva.marcos = { marcosDoc.lista }
                            l.alCambiar = { nueva.post { nueva.invalidate(); marca.intValue++ } }
                            nueva.ponerHoja(activa)
                            vista = nueva
                        }
                    }
                )
                DisposableEffect(l) { onDispose { l.alCambiar = null } }
                // La tinta va con la hoja: cada pintado de la hoja le pasa su vista.
                vista?.let { v ->
                    v.marcos = { marcosDoc.lista }
                    v.marcosOcultos = marcosDoc.imprimiendo || ocultarParaImprimir
                    v.alPintar = null
                    // Mirando, la tinta la pinta la hoja con su misma vista: los dedos siguen siendo de la hoja.
                    v.pintarTinta = if (anotando) null else ({ canvas ->
                        val vp = v.vistaDeLaTinta()
                        val vivos = tinta.scene.elements.filter { !it.isDeleted }
                        if (vp != null && vivos.isNotEmpty()) {
                            canvas.save()
                            canvas.scale(vp.zoom.toFloat(), vp.zoom.toFloat())
                            canvas.translate(vp.scrollX.toFloat(), vp.scrollY.toFloat())
                            for (e in vivos) pintorDeTinta.renderElement(canvas, e)
                            canvas.restore()
                        }
                    })
                    v.invalidate()
                }
                if (anotando) com.forge.pixpin.motor.DrawCanvas(
                    controller = tinta,
                    modifier = Modifier.fillMaxSize(),
                    imageProvider = { f -> tinta.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                    dark = oscuro,
                    cambios = tickDeTinta,
                    onChange = { trazando ->
                        cambiosDeTinta++
                        // Dos dedos mueven la tinta: la hoja la sigue.
                        vista?.desdeLaTinta(tinta.scene.viewport)
                        if (!trazando) tickDeTinta++
                    }
                )
            }
            if (l != null && !anotando) {
                com.forge.pixpin.ui.CapaDeMarcos(
                    marcosDoc,
                    // Los pinta la hoja (van con ella sin retraso); aquí, el gesto y las fichas.
                    aPantalla = { x, y, _ -> vista?.aVista(x, y)?.let { androidx.compose.ui.geometry.Offset(it.x, it.y) } },
                    pintarLosPuestos = false,
                    aDocumento = { o -> vista?.deVista(o.x, o.y)?.let { com.forge.pixpin.ui.PuntoDelDocumento(it[0], it[1], vista?.indice ?: 0) } },
                    pxPorUnidad = { vista?.escala ?: 1.0 },
                    alIr = { m -> irAlMarco(m, vista) { activa = it } },
                    alImprimir = { imprimirLosMarcos() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            com.forge.pixpin.ui.PastillaDeLector(
                visible = aLaVista && !anotando && !marcosDoc.activo && !marcosDoc.imprimiendo && !ocultarParaImprimir,
                nombre = (if (ruta != null) nombre.substringBeforeLast('.') else nombre).ifBlank { "Excel" },
                // Una tabla de PixPin lleva el icono de un libro: se ve como uno.
                archivo = nombre.takeIf { ruta != null && it.contains('.') } ?: "libro.xlsx",
                // **Pocos botones, como los demás visores** (25-oct-2026, el usuario: «uno limpio y con un
                // diseño simple, que tenga la misma UI que los demás visualizadores»): luna/sol, exportar y
                // abrir con otra aplicación. «Copiar» va en la línea de la celda, con algo elegido (el PC
                // no tiene botón: Ctrl+C).
                botones = listOfNotNull(
                    com.forge.pixpin.ui.BotonDeLector(if (oscuro) Icons.Filled.LightMode else Icons.Filled.DarkMode, if (oscuro) "Claro" else "Oscuro") {
                        oscuro = !oscuro
                        com.forge.pixpin.office.Oscuro.poner(this@ExcelActivity, oscuro)
                        vista?.oscuro = oscuro
                        toques++
                    },
                    // «A tabla» (editar los datos en la vista de Tablas) **desactivado, no quitado** (25-oct-2026,
                    // el usuario): de momento el Excel solo se mira y se anota. Ver [EDITAR_EN_TABLAS].
                    if (EDITAR_EN_TABLAS && ruta != null) com.forge.pixpin.ui.BotonDeLector(Icons.Filled.TableChart, "A tabla") { aTabla(ruta, nombre, mensaje, alFallar = false) } else null,
                    abierto?.let { l ->
                        com.forge.pixpin.ui.BotonDeLector(com.forge.pixpin.ui.IconoDeCompartir, "Exportar") {
                            val hojas = l.libro.hojas.filter { it.visible && it.tipo == 0 }.map { it.nombre }
                            exportando = ExportarExcel.compartible(this@ExcelActivity, ruta, nombre, hojas, { vista }, tablasVistas)
                            toques++
                        }
                    },
                    ruta?.let { r -> com.forge.pixpin.ui.BotonDeLector(Icons.Filled.OpenInNew, "Abrir con") { com.forge.pixpin.ui.AbrirCon.abrir(this@ExcelActivity, File(r)) } }
                ),
                modifier = Modifier.align(Alignment.TopCenter)
            )
            exportando?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(c) { exportando = null } }
            // **El riel se va con la pastilla** (27-oct-2026): al mover la hoja, y vuelve al tocar. Ver [com.forge.pixpin.ui.RielALaVista].
            if (l != null && !marcosDoc.imprimiendo && !ocultarParaImprimir) com.forge.pixpin.ui.RielDelVisor(
                visible = com.forge.pixpin.ui.RielALaVista.visible(aLaVista, anotando, marcosDoc.activo, poniendoMarca),
                alUsar = { toques++ },
                poniendoMarca = poniendoMarca, anotando = anotando, marcando = marcosDoc.activo,
                onMarcador = { if (anotando) { anotando = false; guardarTinta() }; marcosDoc.activo = false; poniendoMarca = !poniendoMarca },
                onLapiz = if (base == null && listo) null else ({
                    if (anotando) { anotando = false; guardarTinta() }
                    else { marcosDoc.activo = false; poniendoMarca = false; vista?.vistaDeLaTinta()?.let { tinta.setViewport(it) }; anotando = true }
                }),
                onMarco = { if (anotando) { anotando = false; guardarTinta() }; poniendoMarca = false; marcosDoc.alternar() },
                imprimirMarcos = if (marcosDoc.lista.isEmpty()) null else ({ imprimirLosMarcos() }),
                imprimirTodo = { imprimirLaHoja() },
                marcas = if (anotando) 0 else marcas.size, emojiDe = { i -> marcas[i].emoji },
                alElegirMarca = { i ->
                    marcas.getOrNull(i)?.let { m ->
                        val h = (m.y / POR_HOJA).toInt()
                        val f = (m.y - h * POR_HOJA).toInt()
                        if (h != activa) { activa = h; vista?.ponerHoja(h) }
                        val vv = vistas.de(m.id)
                        if (vv != null) vista?.mirarDesde(vv.second, vv.third, vv.first) else vista?.irA(f, m.x.toInt())
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
            if (poniendoMarca) com.forge.pixpin.ui.ElegirEmojiDeMarca(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
                onCerrar = { poniendoMarca = false }
            ) { emoji ->
                poniendoMarca = false
                val v = vista ?: return@ElegirEmojiDeMarca
                // La celda de arriba a la izquierda de lo que se ve, con el aumento de ahora.
                val esquina = v.deVista(v.width - v.anchoDeCeldas + 1f, v.height - v.altoDeCeldas + 1f) ?: return@ElegirEmojiDeMarca
                val (f, c) = v.celdaEn(v.width - v.anchoDeCeldas + 2f, v.height - v.altoDeCeldas + 2f) ?: (0 to 0)
                marcas = com.forge.pixpin.motor.Marcas.con(marcas, c.toDouble(), activa * POR_HOJA + f, emoji, System.currentTimeMillis())
                guardarMarcas()
                marcas.maxByOrNull { it.id }?.let { vistas.poner(it.id, v.zoom, esquina[0], esquina[1]) }
            }
            if (anotando) {
                // **El editor avanzado de todos los visores** (25-oct-2026). Ver [com.forge.pixpin.ui.EditorAvanzadoDeVisor].
                EditorAvanzadoDeVisor(
                    capa = tinta, ajustes = ajustes,
                    permitidas = ajustes.formatoToolSet(com.forge.pixpin.motor.HerramientasPorSitio.Sitio.HOJA) - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                    grupos = ajustes.formatoGroupList(com.forge.pixpin.motor.HerramientasPorSitio.Sitio.HOJA)
                        .map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() },
                    noche = oscuro, tick = tickDeTinta,
                    alListo = { anotando = false; guardarTinta() },
                    cambiado = { contenido -> if (contenido) cambiosDeTinta++; tickDeTinta++ }
                )
            }
            // Abajo: la línea de la celda (con algo elegido), flotando en el cristal de los visores, y
            // la tira de las pestañas de las hojas, como la del PC.
            if (l != null && !anotando && !marcosDoc.activo && !poniendoMarca && !marcosDoc.imprimiendo && !ocultarParaImprimir) Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val hojas = remember(l) { l.libro.hojas.withIndex().filter { it.value.visible } }
                LineaDeCelda(vista, marca, alCopiar = { vista?.let { copiar(it) }; toques++ }) { activa = it }
                // Con una sola hoja no hay pestañas que elegir: no tapan nada (lo que falta por leer y el
                // zoom, solos a la derecha).
                if (hojas.size > 1) PestanasDeHojas(hojas, activa, oscuro, vista, marca) { i -> activa = i; vista?.ponerHoja(i) }
                else Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                    EstadoDeLaHoja(vista, marca, oscuro, flotando = true)
                }
            }
        }
    }

    /**
     * **La tira de las pestañas, la del PC** (`hoja_office::pintar_pestanas`, 25-oct-2026, el usuario:
     * «uno limpio y con un diseño simple»): una franja gris clara pegada abajo, con su línea de arriba;
     * la hoja que se ve, sobre el papel, en negrita y subrayada en el verde de Excel; las demás, planas
     * con una rayita entre ellas. Sin cristal ni fichas: es parte de la hoja, como en Excel.
     */
    @Composable
    private fun PestanasDeHojas(
        hojas: List<IndexedValue<Pxhj.InfoHoja>>, activa: Int, oscuro: Boolean, v: VistaHoja?,
        marca: androidx.compose.runtime.IntState, alElegir: (Int) -> Unit
    ) {
        val fondo = if (oscuro) Color(0xFF262626) else Color(0xFFF3F3F3)
        val papel = if (oscuro) Color(com.forge.pixpin.office.Oscuro.PAPEL) else Color.White
        val linea = if (oscuro) Color(0xFF3C3C3C) else Color(0xFFD4D4D4)
        val texto = if (oscuro) Color(0xFFDDDDDD) else Color(0xFF212121)
        val verde = if (oscuro) Color(0xFF4CAF7A) else Color(0xFF217346)
        Column(Modifier.fillMaxWidth().background(fondo).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(linea))
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp)) {
                    itemsIndexed(hojas, key = { _, h -> h.index }) { _, h ->
                        val puesta = h.index == activa
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.fillMaxSize().background(if (puesta) papel else Color.Transparent)
                                    .clickable(enabled = h.value.tipo == 0) { alElegir(h.index) }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    h.value.nombre, color = texto, maxLines = 1,
                                    fontWeight = if (puesta) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                if (puesta) Box(Modifier.matchParentSize().padding(bottom = 1.dp), contentAlignment = Alignment.BottomCenter) {
                                    Box(Modifier.fillMaxWidth().height(2.dp).background(verde))
                                }
                            }
                            Box(Modifier.width(1.dp).height(16.dp).background(linea))
                        }
                    }
                }
                EstadoDeLaHoja(v, marca, oscuro, flotando = false)
            }
        }
    }

    /**
     * La línea de la celda (como la barra de fórmulas; las fórmulas no se evalúan: se enseña lo
     * guardado), en la pastilla de cristal de los visores ([com.forge.pixpin.ui.CRISTAL_DE_VISOR]) y
     * con lo que se puede hacer con la celda: **Copiar** lo elegido, leer su nota, ver su lista, abrir
     * su enlace.
     */
    @Composable
    private fun LineaDeCelda(v: VistaHoja?, marca: androidx.compose.runtime.IntState, alCopiar: () -> Unit, alCambiarDeHoja: (Int) -> Unit) {
        @Suppress("UNUSED_VARIABLE") val leer = marca.intValue
        // Solo con una celda elegida: sin ella, la hoja se ve entera.
        if (v?.seleccion == null) return
        val accion = Color(0xFF8AB4F8)
        @Composable
        fun Accion(t: String, al: () -> Unit) = Text(
            t, color = accion, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = al).padding(horizontal = 8.dp, vertical = 6.dp)
        )
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(50)).background(com.forge.pixpin.ui.CRISTAL_DE_VISOR).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val sel = v.seleccion
            val (fa, ca) = v.activa
            val unaSola = sel != null && v.hoja?.geo?.combinadaEn(fa, ca) == sel
            Text(
                if (sel != null) ExcelTexto.referencia(sel, unaSola) else "",
                color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, modifier = Modifier.widthIn(min = 48.dp)
            )
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.3f)))
            val texto = v.textoDe(fa, ca).orEmpty().replace("\r", "").replace("\n", " ⏎ ")
            Text(texto, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Accion("Copiar", alCopiar)
            // La nota de la celda: se lee entera al tocar.
            val nota = v.comentarioDe(fa, ca)
            if (nota != null) Accion("Nota") { verNota(nota) }
            // La lista desplegable: sus valores, solo para ver (como el PC: no se cambia el libro).
            if (v.conLista(fa, ca)) {
                var valores by remember(fa, ca) { mutableStateOf<List<String>?>(null) }
                Box {
                    Accion("Lista ▾") { libro?.lista(v.indice, fa, ca) { l -> runOnUiThread { valores = l.orEmpty() } } }
                    androidx.compose.material3.DropdownMenu(expanded = valores != null, onDismissRequest = { valores = null }) {
                        val l = valores.orEmpty()
                        if (l.isEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("Sin valores que enseñar") }, onClick = { valores = null }, enabled = false)
                        for (x in l) androidx.compose.material3.DropdownMenuItem(text = { Text(x, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { valores = null })
                    }
                }
            }
            // El enlace se abre con este botón y no al tocar la celda: tocar es elegir.
            val enlace = v.hoja?.geo?.enlaceEn(fa, ca)
            if (enlace != null) Accion("Abrir") { abrirEnlace(v, enlace)?.let(alCambiarDeHoja) }
        }
    }

    /** Lo que falta por leer y el zoom: a la derecha de las pestañas, o flotando si no las hay. */
    @Composable
    private fun EstadoDeLaHoja(v: VistaHoja?, marca: androidx.compose.runtime.IntState, oscuro: Boolean, flotando: Boolean) {
        @Suppress("UNUSED_VARIABLE") val leer = marca.intValue
        val hoja = v?.hoja
        val estado = buildList {
            if (hoja != null && !hoja.completa && hoja.geo != null) add("Leyendo… ${"%,d".format(hoja.filasLeidas)} filas")
            val z = v?.zoom ?: 1.0
            if (kotlin.math.abs(z - 1.0) > 0.004) add("${(z * 100).toInt()} %")
        }.joinToString("  ·  ")
        if (estado.isEmpty()) return
        if (flotando) Text(
            estado, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(com.forge.pixpin.ui.CRISTAL_DE_VISOR).padding(horizontal = 12.dp, vertical = 6.dp)
        )
        else Text(
            estado, color = if (oscuro) Color(0xFFAAAAAA) else Color(0xFF6B6B6B), style = MaterialTheme.typography.labelMedium, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }

    /** La nota o el comentario de la celda, entero (con sus respuestas). */
    private fun verNota(n: Pxhj.Comentario) {
        android.app.AlertDialog.Builder(this)
            .setTitle(if (n.encadenado) "Comentario" else "Nota")
            .setMessage(n.completo())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun copiar(v: VistaHoja) {
        val l = libro ?: return
        val r = v.seleccionUsada()
        val n = r.celdas
        if (n > ExcelTexto.MAXIMO_A_COPIAR) {
            Toast.makeText(this, "Demasiadas celdas para copiar (${"%,d".format(n)})", Toast.LENGTH_LONG).show()
            return
        }
        l.copiar(v.indice, r) { texto ->
            runOnUiThread {
                if (texto == null) return@runOnUiThread
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Excel", texto))
                Toast.makeText(this, if (n == 1L) "Copiada la celda" else "Copiadas ${"%,d".format(n)} celdas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Un enlace de la celda: la web (o el correo) con su aplicación; un sitio del libro, allí. Devuelve la hoja a la que se fue, si cambió. */
    private fun abrirEnlace(v: VistaHoja, e: Pxhj.Enlace): Int? {
        var hoja: Int? = null
        when (val a = ExcelTexto.enlaceDe(e.destino, e.externo)) {
            is ExcelTexto.Enlace.Web -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
                .onFailure { Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show() }
            is ExcelTexto.Enlace.Sitio -> {
                val l = libro ?: return null
                if (a.hoja != null) {
                    val i = l.libro.hojas.indexOfFirst { it.nombre.equals(a.hoja, ignoreCase = true) && it.tipo == 0 }
                    if (i >= 0 && i != v.indice) { v.ponerHoja(i); hoja = i }
                }
                a.celda?.let { (f, c) -> v.irA(f, c) }
            }
            null -> Toast.makeText(this, "Ese enlace no se abre desde aquí", Toast.LENGTH_SHORT).show()
        }
        return hoja
    }

    /**
     * **Lo de antes: el libro en Tablas de PixPin**, una tabla por hoja, editable. Con un mensaje
     * del chat, la misma tabla que se abría al tocarlo ([com.forge.pixpin.guardados.LibroDelChat]).
     * **Desactivado, no quitado** (25-oct-2026): sin [EDITAR_EN_TABLAS] no hace nada.
     */
    private fun aTabla(ruta: String, nombre: String, mensaje: String?, alFallar: Boolean, alAcabar: (() -> Unit)? = null) {
        if (!EDITAR_EN_TABLAS) return
        lifecycleScope.launch {
            val id = mensaje ?: "f${File(ruta).let { "${it.absolutePath}|${it.length()}|${it.lastModified()}" }.hashCode().toUInt()}"
            val leido = withContext(Dispatchers.IO) { runCatching { com.forge.pixpin.guardados.LibroDelChat.tablas(applicationContext, id, ruta, nombre) } }
            leido.onSuccess { hojas ->
                if (hojas.isEmpty()) com.forge.pixpin.ui.AbrirCon.abrir(this@ExcelActivity, File(ruta))
                else com.forge.pixpin.tabla.TablaActivity.abrir(this@ExcelActivity, hojas.first().first, hojas = hojas.map { it.first })
            }.onFailure { e ->
                Toast.makeText(
                    this@ExcelActivity, (e as? com.forge.pixpin.motor.ImportarHojas.NoSeLee)?.message ?: "No se pudo leer la hoja de cálculo", Toast.LENGTH_LONG
                ).show()
                if (alFallar) com.forge.pixpin.ui.AbrirCon.abrir(this@ExcelActivity, File(ruta))
            }
            alAcabar?.invoke()
        }
    }

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"
        private const val EXTRA_MENSAJE = "mensaje"
        private const val EXTRA_TABLAS = "tablas"
        private const val EXTRA_ACTIVA = "activa"

        /**
         * **Editar los datos de un Excel en la vista de Tablas**: apagado de momento (25-oct-2026, el
         * usuario: «desactívala nomás, no la quites»). Con `true` vuelve el botón «A tabla».
         */
        const val EDITAR_EN_TABLAS = false

        /** Un marcador de Excel en una sola cifra: `hoja · 10⁷ + fila` (`POR_HOJA` del PC). */
        const val POR_HOJA = 10_000_000.0

        /** Si [nombre] (o [ruta]) se abre en el visor: un libro moderno, con el .so, y que es un ZIP. */
        fun seAbre(nombre: String?, ruta: String?): Boolean = LibroExcel.seAbre(nombre, ruta)

        fun abrir(c: Context, ruta: String, nombre: String?, mensaje: String? = null) {
            // Desde fuera y sin la aplicación abierta, con la lista de chats debajo. Ver [com.forge.pixpin.Volver.abrirEnLaApp].
            com.forge.pixpin.Volver.abrirEnLaApp(
                c, Intent(c, ExcelActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre).putExtra(EXTRA_MENSAJE, mensaje)
            )
        }

        /**
         * **Unas tablas de PixPin, para ver y anotar** (25-oct-2026: lo que antes abría la vista de
         * Tablas): la mini-app `tabla` del chat (`mini-<mensaje>`), las hojas de un Excel del chat
         * (`libro-<mensaje>-<hoja>`, todas en pestañas y en su orden) o la tabla de un proyecto. [activa]:
         * la que se ve al abrir. Ver [EntradaDeHojas.destino].
         */
        fun abrirTablas(c: Context, ids: List<String>, activa: String? = ids.firstOrNull()) {
            if (ids.isEmpty()) return
            com.forge.pixpin.Volver.abrirEnLaApp(
                c, Intent(c, ExcelActivity::class.java).putStringArrayListExtra(EXTRA_TABLAS, ArrayList(ids)).putExtra(EXTRA_ACTIVA, activa)
            )
        }

        /** Lo que dice [EntradaDeHojas.destino], abierto: las tablas o el archivo, aquí. Falso si no es para este visor. */
        fun abrirDestino(c: Context, d: EntradaDeHojas.Destino, ruta: String? = null, nombre: String? = null, mensaje: String? = null): Boolean = when (d) {
            is EntradaDeHojas.Destino.Tablas -> { abrirTablas(c, d.ids, d.activa); true }
            EntradaDeHojas.Destino.Archivo -> if (ruta != null) { abrir(c, ruta, nombre, mensaje); true } else false
            EntradaDeHojas.Destino.OtraCosa -> false
        }
    }
}
