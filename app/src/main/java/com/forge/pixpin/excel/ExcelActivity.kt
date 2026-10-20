package com.forge.pixpin.excel

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
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
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
import com.forge.pixpin.ui.theme.BarraDeCristal
import com.forge.pixpin.ui.theme.BotonDeBarra
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import com.forge.pixpin.ui.theme.SuperficieDeCristal
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
 * detrás ([LibroExcel]). Un libro que no se deja leer va por donde iba antes (a Tablas).
 *
 * **Luna/sol** (PC 74801b4): la hoja en oscuro o en claro, para todos los visores a la vez y
 * recordado en este aparato ([com.forge.pixpin.office.Oscuro]). En la línea de la celda, **la nota**
 * de la celda (si la tiene) y **su lista desplegable** (los valores, solo para ver), como el PC.
 */
/** Lo que mide una hoja en la `y` de un marcador de Excel (PC `visor_hoja/riel.rs`, `POR_HOJA`). */
private const val POR_HOJA = 10_000_000.0

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
        if (ruta == null) { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { File(ruta).name }
        val mensaje = intent?.getStringExtra(EXTRA_MENSAJE)
        setContent { PixPinTheme { Pantalla(ruta, nombre, mensaje) } }
    }

    override fun onDestroy() {
        libro?.cerrar()
        libro = null
        super.onDestroy()
    }

    @Composable
    private fun Pantalla(ruta: String, nombre: String, mensaje: String?) {
        var abierto by remember { mutableStateOf<LibroExcel?>(null) }
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

        LaunchedEffect(ruta) {
            val l = withContext(Dispatchers.IO) { LibroExcel.abrir(ruta) }
            if (l == null) {
                Toast.makeText(this@ExcelActivity, "No se pudo leer el libro: se abre en Tablas", Toast.LENGTH_LONG).show()
                aTabla(ruta, nombre, mensaje, alFallar = true) { finish() }
                return@LaunchedEffect
            }
            libro = l
            activa = l.libro.activa.takeIf { l.libro.hojas.getOrNull(it)?.let { h -> h.visible && h.tipo == 0 } == true }
                ?: l.libro.hojas.indexOfFirst { it.visible && it.tipo == 0 }.coerceAtLeast(0)
            abierto = l
        }

        // **Como los otros visores** (13-oct-2026, el usuario: «arriba aparece un espacio; que la
        // interfaz del Excel se iguale a los otros visores»): la hoja a pantalla completa, sin barras
        // del sistema ni barra fija; arriba la pastilla de los lectores ([com.forge.pixpin.ui.PastillaDeLector]),
        // que sale al tocar y se va sola; abajo, flotando, la línea de la celda y las pestañas.
        // ---- **El riel, los marcadores con vista, los marcos y la tinta de cada hoja** (PC 19/20-oct-2026,
        // `visor_hoja/riel.rs`, `marcos.rs`, `capa.rs`), en píxeles de la hoja al 100 %. Los de un libro
        // del chat viajan con su mensaje: `anot-<uid>.marcas`, `.vistas.json`, `.marcos.json` y la tinta
        // `anot-<uid>-h<i>`; los de un libro suelto se quedan en el teléfono. ----
        val ad = com.forge.pixpin.sincro.AnotacionesDelAdjunto
        val uid = remember(ruta) { ad.uidDe(filesDir, ruta) }
        val huella = remember(ruta) { ruta.hashCode().toUInt().toString(16) }
        fun guardado(terminacion: String, junto: String) =
            if (uid != null) com.forge.pixpin.motor.GuardadoDoc(java.io.File(filesDir, "${ad.CARPETA}/${ad.delPdf(uid)}$terminacion"), viaja = true)
            else com.forge.pixpin.motor.GuardadoDoc(java.io.File("$ruta$junto"), viaja = false)
        val marcosDoc = remember(ruta) { com.forge.pixpin.motor.MarcosDoc.de(guardado(ad.MARCOS, ".pixpin-marcos")) }
        val vistasDoc = remember(ruta) { com.forge.pixpin.motor.VistasDoc.de(guardado(ad.VISTAS, ".pixpin-vistas")) }
        val archivoDeMarcas = remember(ruta) { uid?.let { ad.marcas(filesDir, ad.delPdf(it)) } ?: java.io.File(filesDir, "${ad.CARPETA}/xlsx-$huella.marcas") }
        var marcas by remember(ruta) { mutableStateOf(com.forge.pixpin.motor.Marcas.deTexto(ad.leer(archivoDeMarcas))) }
        var versionDeMarcos by remember { mutableIntStateOf(0) }
        var poniendoMarca by remember { mutableStateOf(false) }
        var anotando by remember { mutableStateOf(false) }
        /** Sube en cada fotograma de la hoja: lo de encima se repinta con ella sin recomponer. */
        val pulso = remember { mutableIntStateOf(0) }
        val alcance = androidx.compose.runtime.rememberCoroutineScope()
        fun guardarLasMarcas() {
            vistasDoc.soloDe(marcas.map { it.id.toString() }.toSet())
            ad.escribir(archivoDeMarcas, com.forge.pixpin.motor.Marcas.aTexto(marcas))
        }
        // La tinta de la hoja que se ve.
        fun idDeLaTinta(i: Int) = uid?.let { ad.deHojaDelLibro(it, i) } ?: "xlsx-$huella-h$i"
        val tintas = remember(ruta) { HashMap<Int, com.forge.pixpin.motor.DrawController>() }
        var tinta by remember { mutableStateOf<com.forge.pixpin.motor.DrawController?>(null) }
        LaunchedEffect(activa, ruta) {
            tinta = tintas[activa] ?: withContext(Dispatchers.IO) {
                com.forge.pixpin.motor.DrawController(
                    com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@ExcelActivity, idDeLaTinta(activa))) ?: com.forge.pixpin.motor.Scene()
                ).also { c -> c.pedirLaMedida = false; c.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
            }.also { tintas[activa] = it }
        }
        fun guardarLaTinta() {
            val c = tinta ?: return
            val id = idDeLaTinta(activa)
            val escena = c.scene
            alcance.launch(Dispatchers.IO) { runCatching { com.forge.pixpin.motor.ExcalidrawStore.guardar(this@ExcelActivity, id, escena) } }
        }
        fun cambiarDeHoja(i: Int) { if (i != activa) { if (anotando) { guardarLaTinta(); anotando = false }; activa = i; vista?.ponerHoja(i) } }
        fun irALaMarca(m: com.forge.pixpin.motor.Marca) {
            val v = vista ?: return
            val hoja = (m.y / POR_HOJA).toInt()
            val fila = (m.y - hoja * POR_HOJA).toInt()
            val columna = m.x.toInt()
            cambiarDeHoja(hoja)
            val vistaGuardada = com.forge.pixpin.motor.VistasDoc.zoomDeTexto(vistasDoc.deMarca(m.id.toString()))
            if (vistaGuardada != null) v.ponerVista(vistaGuardada.first, vistaGuardada.second, vistaGuardada.third)
            else v.hoja?.geo?.let { g -> v.ponerVista(v.zoom, g.x(columna).toDouble(), g.y(fila).toDouble()) }
        }
        fun irAlMarco(i: Int) {
            val v = vista ?: return
            val m = marcosDoc.lista.getOrNull(i) ?: return
            cambiarDeHoja(m.parte)
            val px = com.forge.pixpin.motor.MarcosDoc.pxParaLlenar(m, v.width.toDouble(), v.height.toDouble())
            val z = px / resources.displayMetrics.density
            v.ponerVista(z, m.x0 - 8 / px, m.y0 - 8 / px)
        }
        fun alternarMarcos() {
            if (!marcosDoc.activo) { if (anotando) { guardarLaTinta(); anotando = false }; poniendoMarca = false }
            marcosDoc.alternar(); versionDeMarcos++
        }
        /** **Imprimir los marcos**: la hoja de cada uno, en claro, con su tinta, una hoja de papel por marco. */
        fun imprimirLosMarcos() {
            val v = vista ?: return
            val lista = marcosDoc.lista
            if (lista.isEmpty()) return
            val antes = v.vistaDeAhora(); val hojaAntes = activa; val oscuroAntes = v.oscuro
            alcance.launch {
                val fotos = ArrayList<android.graphics.Bitmap>()
                v.oscuro = false
                for ((k, m) in lista.withIndex()) {
                    irAlMarco(k)
                    kotlinx.coroutines.delay(400)
                    val w = v.width.coerceAtLeast(1); val h = v.height.coerceAtLeast(1)
                    val entera = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val lienzo = android.graphics.Canvas(entera)
                    v.draw(lienzo)
                    val escena = withContext(Dispatchers.IO) {
                        com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@ExcelActivity, idDeLaTinta(m.parte)))
                    }
                    escena?.let { e ->
                        val t = v.vistaDeLaTinta()
                        runCatching {
                            com.forge.pixpin.motor.Renderer(typefaces = com.forge.pixpin.motor.DrawFonts.provider(this@ExcelActivity)).renderScene(
                                lienzo, e.copy(viewport = com.forge.pixpin.motor.Viewport(t[0], t[1], t[2])), w.toDouble(), h.toDouble()
                            )
                        }
                    }
                    val x0 = v.xEnPantalla(m.x0).toInt().coerceIn(0, w - 1); val y0 = v.yEnPantalla(m.y0).toInt().coerceIn(0, h - 1)
                    val x1 = v.xEnPantalla(m.x1).toInt().coerceIn(x0 + 1, w); val y1 = v.yEnPantalla(m.y1).toInt().coerceIn(y0 + 1, h)
                    fotos += android.graphics.Bitmap.createBitmap(entera, x0, y0, x1 - x0, y1 - y0)
                }
                v.oscuro = oscuroAntes
                cambiarDeHoja(hojaAntes)
                v.ponerVista(antes.first, antes.second, antes.third)
                com.forge.pixpin.guardados.Imprimir.fotos(this@ExcelActivity, fotos, nombre.substringBeforeLast('.'))
            }
        }

        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableIntStateOf(0) }
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
                            nueva.alPintar = { pulso.intValue++ }
                            l.alCambiar = { nueva.post { nueva.invalidate(); marca.intValue++ } }
                            nueva.ponerHoja(activa)
                            vista = nueva
                        }
                    }
                )
                DisposableEffect(l) { onDispose { l.alCambiar = null } }
                vista?.let { v ->
                    com.forge.pixpin.ui.TintaSobreVisor(
                        controlador = tinta, anotando = anotando, pulso = pulso.intValue,
                        vistaDelVisor = { val t = v.vistaDeLaTinta(); com.forge.pixpin.motor.Viewport(t[0], t[1], t[2]) },
                        alSeguir = { vp -> v.seguirALaTinta(vp.scrollX, vp.scrollY, vp.zoom) },
                        alCambiar = { guardarLaTinta() },
                        alAcabar = { guardarLaTinta(); anotando = false },
                        oscuro = oscuro
                    )
                    if (!anotando && (marcosDoc.activo || marcosDoc.lista.isNotEmpty())) com.forge.pixpin.ui.CapaDeMarcos(
                        marcos = marcosDoc, version = versionDeMarcos,
                        aPantalla = { x, y -> @Suppress("UNUSED_EXPRESSION") pulso.intValue; androidx.compose.ui.geometry.Offset(v.xEnPantalla(x), v.yEnPantalla(y)) },
                        aDocumento = { o -> doubleArrayOf(v.xEnHoja(o.x), v.yEnHoja(o.y)) },
                        px = { v.escala.toDouble() },
                        parte = { activa },
                        alCambiar = { versionDeMarcos++ },
                        alIrA = { i -> irAlMarco(i) },
                        alImprimir = { imprimirLosMarcos() },
                        alDesplazar = { pan, factor, centro -> v.moverYAcercar(pan.x, pan.y, factor, centro.x, centro.y) },
                        abajo = 96.dp
                    )
                }
            }
            // **La barra de arriba y el riel, como los pines del PC** (20-oct-2026).
            if (!anotando) com.forge.pixpin.ui.BarraDeVisor(
                visible = aLaVista,
                tipo = "Excel",
                nombre = nombre.substringBeforeLast('.').ifBlank { "Excel" },
                botones = listOfNotNull(
                    if (abierto != null) com.forge.pixpin.ui.BotonDeLector(Icons.Filled.ContentCopy, "Copiar") { vista?.let { copiar(it) }; toques++ } else null,
                    com.forge.pixpin.ui.BotonDeLector(Icons.Filled.TableChart, "A tabla") { aTabla(ruta, nombre, mensaje, alFallar = false) },
                    abierto?.let { l ->
                        com.forge.pixpin.ui.BotonDeLector(com.forge.pixpin.ui.IconoDeCompartir, "Exportar") {
                            val hojas = l.libro.hojas.filter { it.visible && it.tipo == 0 }.map { it.nombre }
                            exportando = ExportarExcel.compartible(this@ExcelActivity, ruta, nombre, hojas) { vista }
                            toques++
                        }
                    }
                ),
                oscuro = oscuro,
                onOscuro = {
                    oscuro = !oscuro
                    com.forge.pixpin.office.Oscuro.poner(this@ExcelActivity, oscuro)
                    vista?.oscuro = oscuro
                    toques++
                },
                menu = listOfNotNull(
                    com.forge.pixpin.ui.OpcionDeMenu("Abrir con otra app", Icons.Filled.OpenInNew) { com.forge.pixpin.ui.AbrirCon.abrir(this@ExcelActivity, File(ruta)) },
                    if (marcosDoc.lista.isNotEmpty()) com.forge.pixpin.ui.OpcionDeMenu("Imprimir los marcos", Icons.Filled.Print) { imprimirLosMarcos() } else null,
                    if (marcosDoc.lista.isNotEmpty()) com.forge.pixpin.ui.OpcionDeMenu("Borrar los marcos", Icons.Filled.DeleteSweep) { marcosDoc.borrar(); versionDeMarcos++ } else null
                ),
                onCerrar = { finish() },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            if (abierto != null) {
                @Suppress("UNUSED_EXPRESSION") versionDeMarcos
                com.forge.pixpin.ui.RielDeVisor(
                    com.forge.pixpin.ui.botonesDelRiel(
                        poniendoMarca = poniendoMarca, onMarca = if (anotando) null else ({ if (marcosDoc.activo) alternarMarcos(); poniendoMarca = !poniendoMarca }),
                        anotando = anotando, onAnotar = {
                            if (anotando) { guardarLaTinta(); anotando = false }
                            else { if (marcosDoc.activo) alternarMarcos(); poniendoMarca = false; anotando = true }
                        },
                        enmarcando = marcosDoc.activo, onMarcos = if (anotando) null else ({ alternarMarcos() })
                    ),
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp)
                )
                if (!anotando && marcas.isNotEmpty()) {
                    val enOrden = com.forge.pixpin.motor.Marcas.enOrden(marcas)
                    com.forge.pixpin.ui.RielDeMarcas(enOrden.size, { i -> enOrden[i].emoji }, Modifier.align(Alignment.CenterEnd)) { i -> enOrden.getOrNull(i)?.let { irALaMarca(it) } }
                }
                if (poniendoMarca) com.forge.pixpin.ui.ElegirEmojiDeMarca(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), onCerrar = { poniendoMarca = false }) { emoji ->
                    poniendoMarca = false
                    val v = vista
                    if (v != null) {
                        // En la celda activa, como el PC: `x` la columna, `y` la hoja por POR_HOJA más la fila.
                        val (f, c) = v.activa
                        val antes = marcas.map { it.id }.toSet()
                        marcas = com.forge.pixpin.motor.Marcas.con(marcas, c.toDouble(), activa * POR_HOJA + f, emoji, System.currentTimeMillis())
                        val (z, x, y) = v.vistaDeAhora()
                        marcas.firstOrNull { it.id !in antes }?.let { vistasDoc.poner(it.id.toString(), com.forge.pixpin.motor.VistasDoc.zoomATexto(z, x, y)) }
                        guardarLasMarcas()
                    }
                }
            }
            exportando?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(c) { exportando = null } }
            // Abajo, flotando: la línea de la celda (con algo elegido) y las pestañas de las hojas.
            if (l != null) Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LineaDeCelda(vista, marca) { activa = it }
                Row(Modifier.padding(horizontal = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val hojas = remember(l) { l.libro.hojas.withIndex().filter { it.value.visible } }
                    // Con una sola hoja no hay pestañas que elegir: no tapan nada.
                    if (hojas.size > 1) LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(hojas, key = { _, h -> h.index }) { _, h ->
                            val puesta = h.index == activa
                            Text(
                                h.value.nombre,
                                color = if (puesta) Cristal.tintaPuesta else Cristal.tinta,
                                fontWeight = if (puesta) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (puesta) Cristal.puesto else Cristal.barra)
                                    .clickable(enabled = h.value.tipo == 0) { activa = h.index; vista?.ponerHoja(h.index) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    } else Spacer(Modifier.weight(1f))
                    EstadoDeLaHoja(vista, marca)
                }
            }
        }
    }

    /** La línea de la celda (como la barra de fórmulas; las fórmulas no se evalúan: se enseña lo guardado). */
    @Composable
    private fun LineaDeCelda(v: VistaHoja?, marca: androidx.compose.runtime.IntState, alCambiarDeHoja: (Int) -> Unit) {
        @Suppress("UNUSED_VARIABLE") val leer = marca.intValue
        // Solo con una celda elegida: sin ella, la hoja se ve entera.
        if (v?.seleccion == null) return
        SuperficieDeCristal(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(40.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val sel = v?.seleccion
                val (fa, ca) = v?.activa ?: (0 to 0)
                val unaSola = sel != null && v.hoja?.geo?.combinadaEn(fa, ca) == sel
                Text(
                    if (sel != null) ExcelTexto.referencia(sel, unaSola) else "",
                    color = Cristal.tinta, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, modifier = Modifier.widthIn(min = 56.dp)
                )
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(22.dp).background(Cristal.tinta.copy(alpha = 0.3f)))
                val texto = v?.textoDe(fa, ca).orEmpty().replace("\r", "").replace("\n", " ⏎ ")
                Text(texto, color = Cristal.tinta, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // La nota de la celda: se lee entera al tocar.
                val nota = v?.comentarioDe(fa, ca)
                if (nota != null) {
                    Text(
                        "Nota", color = Cristal.tintaPuesta, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { verNota(nota) }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                // La lista desplegable: sus valores, solo para ver (como el PC: no se cambia el libro).
                if (v != null && v.conLista(fa, ca)) {
                    var valores by remember(fa, ca) { mutableStateOf<List<String>?>(null) }
                    Box {
                        Text(
                            "Lista ▾", color = Cristal.tintaPuesta, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                libro?.lista(v.indice, fa, ca) { l -> runOnUiThread { valores = l.orEmpty() } }
                            }.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        androidx.compose.material3.DropdownMenu(expanded = valores != null, onDismissRequest = { valores = null }) {
                            val l = valores.orEmpty()
                            if (l.isEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("Sin valores que enseñar") }, onClick = { valores = null }, enabled = false)
                            for (x in l) androidx.compose.material3.DropdownMenuItem(text = { Text(x, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { valores = null })
                        }
                    }
                }
                val enlace = v?.hoja?.geo?.enlaceEn(fa, ca)
                if (enlace != null && v != null) {
                    // El enlace se abre con este botón y no al tocar la celda: tocar es elegir.
                    Text(
                        "Abrir", color = Cristal.tintaPuesta, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { abrirEnlace(v, enlace)?.let(alCambiarDeHoja) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    /** Lo que falta por leer y el zoom, a la derecha de las pestañas. */
    @Composable
    private fun EstadoDeLaHoja(v: VistaHoja?, marca: androidx.compose.runtime.IntState) {
        @Suppress("UNUSED_VARIABLE") val leer = marca.intValue
        val hoja = v?.hoja
        val estado = buildList {
            if (hoja != null && !hoja.completa && hoja.geo != null) add("Leyendo… ${"%,d".format(hoja.filasLeidas)} filas")
            val z = v?.zoom ?: 1.0
            if (kotlin.math.abs(z - 1.0) > 0.004) add("${(z * 100).toInt()} %")
        }.joinToString("  ·  ")
        if (estado.isNotEmpty()) Text(estado, color = Cristal.tinta.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(12.dp)).background(Cristal.barra).padding(horizontal = 10.dp, vertical = 6.dp))
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
     */
    private fun aTabla(ruta: String, nombre: String, mensaje: String?, alFallar: Boolean, alAcabar: (() -> Unit)? = null) {
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

        /** Si [nombre] (o [ruta]) se abre en el visor: un libro moderno, con el .so, y que es un ZIP. */
        fun seAbre(nombre: String?, ruta: String?): Boolean = LibroExcel.seAbre(nombre, ruta)

        fun abrir(c: Context, ruta: String, nombre: String?, mensaje: String? = null) {
            c.startActivity(
                Intent(c, ExcelActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre).putExtra(EXTRA_MENSAJE, mensaje)
                    .apply { if (c !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
