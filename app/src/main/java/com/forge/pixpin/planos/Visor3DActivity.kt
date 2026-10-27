package com.forge.pixpin.planos

import com.forge.pixpin.ui.EditorAvanzadoDeVisor
import androidx.compose.runtime.collectAsState

import androidx.compose.ui.graphics.nativeCanvas

import androidx.compose.ui.layout.boundsInWindow

import androidx.compose.ui.layout.onGloballyPositioned

import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **El visor de modelos 3D, a pantalla completa** (9-oct-2026): lo que en el PC es el «visor de
 * modelos 3D como un pin» (`crates/pixpin-cad/src/ventana3d.rs`): un plano DWG/DXF visto en 3D, un
 * LandXML de Civil 3D (superficies con color por cota y curvas de nivel, alineamientos con su
 * rasante, puntos, tuberías, parcelas), un fichero de puntos (PNEZD, PENZD, ENZ…) triangulado en
 * su superficie, o un IFC. Como el visor de planos: solo el modelo y la pastilla del nombre con lo
 * de la barra del PC — ver todo, fondo claro u oscuro, aristas, planta y la **caja de sección**.
 *
 * - **Un dedo** gira —media vuelta por el lado corto de la pantalla— alrededor de lo que toca (o
 *   del centro de lo que se ve), con el pivote a la vista; **dos** mueven y acercan.
 * - **Tocar** un elemento lo resalta en azul y dice qué es («Muro · M-01», «Superficie · TN»…);
 *   **tocarlo otra vez** lo aísla, solo él y encuadrado (PC `1e5be50`).
 * - **Doble toque**: en un elemento, aislarlo; en el vacío, el modelo entero (y todo a la vista).
 * - **Caja de sección** (la de Revit, PC `1e5be50`): seis tiradores, uno por cara; arrastrándolos
 *   se corta por donde se quiera y lo cortado se ve macizo (ver [Pintor3D.seccion]).
 *
 * **Desde el 10-oct-2026 abre también los IFC y los Revit** que antes iban al croquis 3D (pedido por
 * el usuario: «ábrelos como en Windows, en una app separada, que en el canvas 3D se ve todo
 * descolocado»). Y lo que en el croquis tenía el panel del modelo: **ocultar** o **aislar** lo
 * tocado, **medir** entre dos puntos del modelo, y **llevarlo al croquis** (leído con esta misma
 * lectura) para dibujar encima.
 *
 * **Niveles y categorías** (PC `9625d0d`): un panel con las plantas del edificio y las categorías
 * (muros, losas…), cada una con cuántos elementos tiene y su volumen; un toque la muestra u oculta
 * y mantener deja solo esa ([ArbolDelModelo]). Lo tocado dice su nivel, su volumen y su área.
 */
class Visor3DActivity : ComponentActivity() {
    private var vista: GLSurfaceView? = null
    private var pintor: Pintor3D? = null
    private var modelo: Modelo3D? = null
    private var orbita: Orbita? = null
    /** La caja del elemento aislado (lo que se encuadra con «ver todo»), o null. */
    private var cajaAislada: FloatArray? = null
    /** Los niveles y las categorías del modelo (el panel). */
    private var arbol: ArbolDelModelo? = null
    /** Las señas del modo de paso, si se abrió de otra aplicación sin guardarlo. */
    private var estadoDePaso: com.forge.pixpin.guardados.AbrirDePaso.Estado? = null
    private var ancho = 0
    private var alto = 0
    private val prefs by lazy { getSharedPreferences("planos", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val ruta = intent?.getStringExtra(EXTRA_RUTA) ?: run { finish(); return }
        val nombre = intent?.getStringExtra(EXTRA_NOMBRE).orEmpty().ifBlank { File(ruta).name }
        // **De paso** (27-oct-2026): abierto desde otra aplicación sin guardarlo. Ver [com.forge.pixpin.guardados.ModoDePaso].
        estadoDePaso = com.forge.pixpin.guardados.AbrirDePaso.vigilar(this)
        setContent { PixPinTheme { Pantalla(File(ruta), nombre) } }
    }

    override fun onResume() { super.onResume(); vista?.onResume() }
    override fun onPause() { vista?.onPause(); super.onPause() }

    private fun pintar() { pintor?.orbita = orbita; vista?.requestRender() }

    /** Lo que se ve: el elemento aislado o el modelo (sin los puntos sueltos lejanos). */
    private fun cajaVisible(): FloatArray? = cajaAislada ?: modelo?.cajaUtil()

    private fun verTodo() {
        val c = cajaVisible() ?: return
        if (ancho <= 0) return
        orbita = Orbita.encuadrar(c, ancho, alto)
        pintar()
    }

    /** La matriz de la cámara de ahora (la misma que usa la tarjeta). */
    private fun matriz(): FloatArray? = orbita?.matriz(ancho, alto, pintor?.radio ?: 1.0)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Pantalla(original: File, nombre: String) {
        val abriendo = getString(com.forge.pixpin.R.string.modelo3d_abriendo)
        var estado by remember { mutableStateOf<String?>(abriendo) }
        var listo by remember { mutableStateOf(false) }
        var claro by remember { mutableStateOf(prefs.getBoolean("claro", false)) }
        var aristas by remember { mutableStateOf(true) }
        var elegido by remember { mutableStateOf<Modelo3D.Elemento?>(null) }
        var elegidoIdx by remember { mutableIntStateOf(-1) }
        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableIntStateOf(0) }
        val densidad = resources.displayMetrics.density
        // Lo escondido (un byte por elemento) y el aislado (o -1). Lo escondido sale de los niveles y
        // categorías ocultos del panel ([grupos]) y de lo ocultado a mano ([sueltos]).
        var ocultos by remember { mutableStateOf(ByteArray(0)) }
        var grupos by remember { mutableStateOf(emptySet<ArbolDelModelo.Clave>()) }
        var sueltos by remember { mutableStateOf(emptySet<Int>()) }
        var aislado by remember { mutableIntStateOf(-1) }
        // La caja de sección (relativa al origen), y la cara que se arrastra con su valor.
        var seccion by remember { mutableStateOf<FloatArray?>(null) }
        var caraTirada by remember { mutableStateOf<Pair<Int, Float>?>(null) }
        // El punto alrededor del que se gira, mientras se gira.
        var pivote by remember { mutableStateOf<DoubleArray?>(null) }
        var viendoPiezas by remember { mutableStateOf(false) }
        var midiendo by remember { mutableStateOf(false) }
        val medida = remember { mutableStateListOf<DoubleArray>() }
        // **Exportar** (14-oct-2026, como el PC): la vista y el archivo, con la hoja de compartir de siempre.
        var exportando by remember { mutableStateOf<com.forge.pixpin.ui.Compartible?>(null) }
        // Sube con cada fotograma pedido: lo que va dibujado encima sigue a la cámara.
        var camara by remember { mutableIntStateOf(0) }

        fun repintar() { pintar(); camara++ }
        fun esconder(nuevos: ByteArray) { ocultos = nuevos; pintor?.ocultos = nuevos; repintar() }
        fun ponerSeccion(c: FloatArray?) { seccion = c; pintor?.seccion = c; repintar() }
        fun soltar() { elegido = null; elegidoIdx = -1; pintor?.elegido = -1 }
        /** Lo escondido de nuevo, con lo oculto del panel y a mano (sin aislar). */
        fun aplicar() {
            val mm = modelo ?: return
            val n = arbol?.escondidos(mm, grupos) ?: ByteArray(mm.elementos.size)
            for (e in sueltos) if (e in n.indices) n[e] = 1
            aislado = -1; cajaAislada = null
            esconder(n)
        }
        fun mostrarTodo() { grupos = emptySet(); sueltos = emptySet(); aplicar() }
        fun aislar(e: Int) {
            val m = modelo ?: return
            val nuevos = ByteArray(m.elementos.size) { 1 }
            if (e in nuevos.indices) nuevos[e] = 0
            aislado = e
            esconder(nuevos)
            lifecycleScope.launch {
                val c = withContext(Dispatchers.Default) { ElegirEn3D.cajaDe(m, e) } ?: return@launch
                if (aislado != e) return@launch
                cajaAislada = c
                orbita = orbita?.encuadrarCon(c, ancho, alto)
                repintar()
            }
        }

        LaunchedEffect(original) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (val leido = LectorDePlanos.leer3D(this@Visor3DActivity, original, nombre)) {
                        is LectorDePlanos.Companion.Resultado.Fallo -> throw Modelo3D.NoSeLee(leido.motivo)
                        is LectorDePlanos.Companion.Resultado.Listo -> Modelo3D.abrir(leido.archivo)
                    }
                }
            }
            r.onSuccess { m ->
                if (m.vacio) { estado = getString(com.forge.pixpin.R.string.modelo3d_vacio); return@onSuccess }
                modelo = m
                arbol = ArbolDelModelo.de(m, getString(com.forge.pixpin.R.string.modelo3d_sin_nivel))
                ocultos = ByteArray(m.elementos.size)
                pintor = Pintor3D(m, grosor = (densidad / 1.5f).coerceIn(1f, 3f), densidad = densidad).also {
                    it.claro = claro
                    it.alCambiarTamano = { w, h -> runOnUiThread { val primera = orbita == null || ancho <= 0; ancho = w; alto = h; if (primera) verTodo() else pintar(); camara++ } }
                }
                estado = null
                listo = true
            }.onFailure { e -> estado = (e as? Modelo3D.NoSeLee)?.message ?: getString(com.forge.pixpin.R.string.modelo3d_no) }
        }
        LaunchedEffect(aLaVista, toques) { if (aLaVista) { delay(3500); aLaVista = false } }
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                pintor?.fallo?.let { estado = getString(com.forge.pixpin.R.string.plano_tarjeta, it); listo = false; return@LaunchedEffect }
            }
        }
        // ---- **Los marcadores del modelo** (24-oct-2026, como el PC `Marcador3d`): la cámara
        // entera, en `anot-<uid>.vistas.json` (`"modelo":[…]`), junto a los del plano si los hay. ----
        var archivoDeVistas by remember { mutableStateOf<java.io.File?>(null) }
        var vistas by remember { mutableStateOf(MarcadoresDelPlano.deTexto(null)) }
        var poniendoMarca by remember { mutableStateOf(false) }
        var base by remember { mutableStateOf<String?>(null) }
        var marcos3d by remember { mutableStateOf(MarcadoresDelPlano.marcosDeTexto(null)) }
        var leidos by remember { mutableStateOf(false) }
        LaunchedEffect(original) {
            val b = withContext(Dispatchers.IO) { com.forge.pixpin.ui.ExportarDocumentoAnotado.baseDe(this@Visor3DActivity, original) }
            base = b
            val f = b?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivoDeVistas(filesDir, it) }
            archivoDeVistas = f
            vistas = MarcadoresDelPlano.deTexto(f?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(it) })
            marcos3d = MarcadoresDelPlano.marcosDeTexto(b?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(com.forge.pixpin.motor.MarcosDelDocumento.archivo(filesDir, it)) })
            leidos = true
        }
        fun guardarVistas(nuevas: MarcadoresDelPlano.Archivo) {
            vistas = nuevas
            archivoDeVistas?.let { com.forge.pixpin.motor.MarcosDelDocumento.escribir(it, nuevas.aTexto(), vacio = false) }
        }
        /** La vista de ahora como un `Estado3d` del PC, en coordenadas absolutas. */
        fun estadoAhora(): MarcadoresDelPlano.Marcador3d? {
            val ob = orbita ?: return null
            val o = modelo?.origen ?: return null
            return MarcadoresDelPlano.Marcador3d(
                "", "", doubleArrayOf(ob.objetivo[0] + o[0], ob.objetivo[1] + o[1], ob.objetivo[2] + o[2]), ob.rumbo, ob.altura, ob.dist,
                seccion?.let { c -> DoubleArray(6) { k -> c[k] + o[k % 3] } }
            )
        }
        /** Lo que se ve es la vista de un marcador (su cámara y su corte): su caja no se pinta. */
        fun cajaEscondida(): Boolean {
            val a = estadoAhora() ?: return false
            return a.seccion != null && vistas.modelo.any { MarcadoresDelPlano.coincide(a, it) }
        }
        /** Ir a una vista guardada (un marcador o un marco). */
        fun ponerEstado(v: MarcadoresDelPlano.Marcador3d) {
            val o = modelo?.origen ?: return
            orbita = Orbita(doubleArrayOf(v.objetivo[0] - o[0], v.objetivo[1] - o[1], v.objetivo[2] - o[2]), v.rumbo, v.altura, v.dist)
            ponerSeccion(v.seccion?.let { c -> FloatArray(6) { k -> (c[k] - o[k % 3]).toFloat() } })
        }

        // ---- **Lo anotado sobre cada marcador** (como el PC, `ventana3d.rs`): una capa por marcador,
        // `anot-<uid>-m<id>.excalidraw.gz` con su `.hoja` `-2500,-2500,2500,2500`, en milésimas del alto de
        // la pantalla desde su centro. Solo se ve en la vista de su marcador. El lápiz, fuera de un
        // marcador, pone el de esta vista y anota sobre él. ----
        var anotando by remember { mutableStateOf<String?>(null) }
        var tickDeTinta by remember { mutableIntStateOf(0) }
        val capas = remember { HashMap<String, com.forge.pixpin.motor.DrawController>() }
        fun capaDe(id: String): com.forge.pixpin.motor.DrawController? {
            val b = base ?: return null
            return capas.getOrPut(id) {
                com.forge.pixpin.motor.DrawController(
                    com.forge.pixpin.motor.ExcalidrawStore.cargar(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@Visor3DActivity, MarcadoresDelPlano.baseDeLaTinta(b, id)))
                        ?: com.forge.pixpin.motor.Scene()
                ).also { it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
            }
        }
        fun dejarDeAnotar() {
            val id = anotando ?: return
            anotando = null
            val b = base ?: return
            val c = capas[id] ?: return
            val tinta = MarcadoresDelPlano.baseDeLaTinta(b, id)
            com.forge.pixpin.motor.ExcalidrawStore.guardar(this@Visor3DActivity, tinta, c.scene)
            runCatching { com.forge.pixpin.sincro.AnotacionesDelAdjunto.escribirMarco(com.forge.pixpin.sincro.AnotacionesDelAdjunto.hoja(filesDir, tinta), MarcadoresDelPlano.HOJA_DE_LA_TINTA) }
            camara++
        }
        fun empezarAAnotar() {
            val ahora = estadoAhora() ?: return
            if (base == null) { android.widget.Toast.makeText(this@Visor3DActivity, "Solo se anota un modelo del chat", android.widget.Toast.LENGTH_SHORT).show(); return }
            val ya = vistas.modelo.firstOrNull { MarcadoresDelPlano.coincide(ahora, it) }
            val m = ya ?: ahora.copy(id = MarcadoresDelPlano.idNuevo(System.currentTimeMillis(), vistas.modelo.map { it.id } + vistas.plano.map { it.id }))
                .also { guardarVistas(vistas.conModelo(vistas.modelo + it)) }
            capaDe(m.id)?.setViewport(MarcadoresDelPlano.vistaDeTinta(ancho, alto))
            anotando = m.id
        }
        // ---- **Los marcos 3D** (`anot-<uid>.marcos.json` del modelo): cada uno con la vista en la que se
        // puso, y solo se ve en ella. En la lista común su `vista` es su id. ----
        val marcosDoc = remember(leidos) {
            com.forge.pixpin.ui.MarcosDelVisor(
                null,
                inicial = marcos3d.marcos.map { m -> com.forge.pixpin.motor.MarcosDelDocumento.Marco(m.caja[0], m.caja[1], m.caja[2], m.caja[3], 0, m.id) },
                alGuardar = { nueva ->
                    val porId = marcos3d.marcos.associateBy { it.id }
                    val usados = HashSet<String>(porId.keys)
                    val ahora = estadoAhora()
                    val salida = nueva.mapNotNull { m ->
                        val viejo = porId[m.vista]
                        val est = viejo?.estado ?: ahora ?: return@mapNotNull null
                        val id = viejo?.id ?: MarcadoresDelPlano.idNuevo(System.currentTimeMillis(), usados).also { usados += it }
                        MarcadoresDelPlano.Marco3d(id, doubleArrayOf(m.x0, m.y0, m.x1, m.y1), est)
                    }
                    marcos3d = marcos3d.con(salida)
                    base?.let { b -> com.forge.pixpin.motor.MarcosDelDocumento.escribir(com.forge.pixpin.motor.MarcosDelDocumento.archivo(filesDir, b), marcos3d.aTexto(), vacio = false) }
                    salida.map { m -> com.forge.pixpin.motor.MarcosDelDocumento.Marco(m.caja[0], m.caja[1], m.caja[2], m.caja[3], 0, m.id) }
                }
            )
        }
        BackHandler {
            when {
                anotando != null -> dejarDeAnotar()
                marcosDoc.activo -> marcosDoc.activo = false
                poniendoMarca -> poniendoMarca = false
                viendoPiezas -> viendoPiezas = false
                midiendo -> { midiendo = false; medida.clear(); camara++ }
                elegido != null -> { soltar(); repintar() }
                aislado >= 0 -> { mostrarTodo(); verTodo() }
                else -> finish()
            }
        }

        val m = modelo
        val fondo = if (claro) Color(0xFFF3F4F6) else Color(0xFF212633)
        val azul = Color(0xFF5AA0FF)
        Box(Modifier.fillMaxSize().then(if (listo) Modifier else Modifier.background(fondo))) {
            if (listo) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                    GLSurfaceView(c).apply {
                        setEGLContextClientVersion(3)
                        setEGLConfigChooser(Pintor3D.Muestreo())
                        preserveEGLContextOnPause = true
                        setRenderer(pintor)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        vista = this
                    }
                })
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    var ultimoToque = 0L
                    awaitEachGesture {
                        val abajo = awaitFirstDown(requireUnconsumed = false)
                        val mm = modelo ?: return@awaitEachGesture
                        // **Un tirador de la caja**: su cara sigue al dedo a lo largo de su eje.
                        val caja = seccion
                        val mvp = matriz()
                        // En la vista de un marcador la caja no se ve (solo su corte): tampoco se agarra.
                        val tirador = if (caja != null && mvp != null && !cajaEscondida()) tiradorEn(caja, mvp, abajo.position, 24f * densidad) else null
                        if (caja != null && mvp != null && tirador != null) {
                            val k = tirador
                            val p = centroDeCara(caja, k)
                            val lado = decimo(caja)
                            val a = enPantalla(mvp, p); val b = enPantalla(mvp, Orbita.suma(p, Orbita.por(fueraDeCara(k), lado)))
                            val porUnidad = if (a != null && b != null) Offset(((b.x - a.x) / lado).toFloat(), ((b.y - a.y) / lado).toFloat()) else Offset.Zero
                            val l2 = porUnidad.x * porUnidad.x + porUnidad.y * porUnidad.y
                            val limite = cajaHolgada(cajaHolgada(mm.caja))
                            val v0 = caja[k].toDouble()
                            var corrido = Offset.Zero
                            caraTirada = k to caja[k]
                            do {
                                val ev = awaitPointerEvent()
                                corrido += ev.calculatePan()
                                if (l2 > 1e-9f) {
                                    val d = (corrido.x * porUnidad.x + corrido.y * porUnidad.y) / l2
                                    val signo = if (k < 3) -1.0 else 1.0
                                    val nueva = moverCara(caja, k, v0 + signo * d, limite)
                                    caraTirada = k to nueva[k]
                                    ponerSeccion(nueva)
                                }
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                            caraTirada = null
                            camara++
                            return@awaitEachGesture
                        }
                        var movido = false
                        var varios = false
                        var corrido = Offset.Zero
                        // El pivote del giro: lo que hay bajo el dedo (se busca aparte, que un modelo
                        // grande son millones de triángulos); mientras, el centro de lo que se ve si
                        // queda delante del ojo, o el objetivo (que el modelo no salga disparado).
                        val o0 = orbita
                        var giraEn: DoubleArray? = null
                        if (o0 != null) {
                            val c = seccion ?: cajaVisible() ?: mm.caja
                            val centro = doubleArrayOf((c[0] + c[3]) / 2.0, (c[1] + c[4]) / 2.0, (c[2] + c[5]) / 2.0)
                            val delante = Orbita.punto(Orbita.sub(centro, o0.ojo()), o0.ejes()[2]) > 0
                            giraEn = if (delante) centro else o0.objetivo
                            val dir = o0.rayo(abajo.position.x.toDouble(), abajo.position.y.toDouble(), ancho, alto)
                            val sec = seccion; val esc = ocultos
                            lifecycleScope.launch {
                                val t = withContext(Dispatchers.Default) { runCatching { ElegirEn3D.elegir(mm, o0.ojo(), dir, sec, 0.0, esc) }.getOrNull() }
                                if (t != null) { giraEn = t.punto; if (pivote != null) pivote = t.punto }
                            }
                        }
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } >= 2) varios = true
                            val pan = ev.calculatePan()
                            val zoom = ev.calculateZoom()
                            if (!movido) {
                                corrido += pan
                                if (corrido.getDistance() > viewConfiguration.touchSlop || (varios && zoom != 1f)) { movido = true; aLaVista = false }
                            }
                            val o = orbita
                            if (movido && o != null) {
                                orbita = if (varios) {
                                    pivote = null
                                    var n = o.mover(pan.x.toDouble(), pan.y.toDouble(), alto)
                                    if (zoom > 0f && zoom != 1f) n = n.zoom(1.0 / zoom, n.objetivo)
                                    n
                                } else {
                                    // Un dedo gira: media vuelta por el lado corto de la pantalla, poco a poco.
                                    val k = Math.PI / minOf(ancho, alto).coerceAtLeast(1)
                                    val p = giraEn ?: o.objetivo
                                    pivote = p
                                    o.girar(-pan.x * k, pan.y * k, p)
                                }
                                repintar()
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (pivote != null) { pivote = null; camara++ }
                        if (!movido && !varios) {
                            val ahora = System.currentTimeMillis()
                            val doble = ahora - ultimoToque < 300
                            ultimoToque = if (doble) 0L else ahora
                            // Qué hay bajo el dedo: en otro hilo.
                            val o = orbita ?: return@awaitEachGesture
                            val dir = o.rayo(abajo.position.x.toDouble(), abajo.position.y.toDouble(), ancho, alto)
                            val tolerancia = 6.0 * densidad * 2 * kotlin.math.tan(Orbita.FOV / 2) / alto.coerceAtLeast(1)
                            val sec = seccion
                            val escondidos = ocultos
                            val yaElegido = elegidoIdx
                            lifecycleScope.launch {
                                val t = withContext(Dispatchers.Default) { runCatching { ElegirEn3D.elegir(mm, o.ojo(), dir, sec, tolerancia, escondidos) }.getOrNull() }
                                when {
                                    // Midiendo, cada toque es un punto del modelo; el tercero empieza otra.
                                    midiendo -> if (t != null) { if (medida.size >= 2) medida.clear(); medida.add(t.punto); camara++ }
                                    // Doble toque en el vacío: todo, entero.
                                    doble && t == null -> { if (aislado >= 0 || ocultos.any { it.toInt() != 0 }) mostrarTodo(); verTodo(); camara++ }
                                    t == null -> { soltar(); aLaVista = !aLaVista; toques++; repintar() }
                                    // Otro toque (o doble) en el elegido: aislarlo.
                                    (t.elemento == yaElegido || doble) && aislado != t.elemento -> {
                                        elegidoIdx = t.elemento; pintor?.elegido = t.elemento
                                        elegido = mm.elementos.getOrNull(t.elemento)
                                        aislar(t.elemento)
                                    }
                                    else -> {
                                        elegidoIdx = t.elemento
                                        pintor?.elegido = t.elemento
                                        elegido = mm.elementos.getOrNull(t.elemento)
                                        repintar()
                                    }
                                }
                            }
                        }
                    }
                })
                // Encima del modelo: la caja con sus tiradores, el pivote del giro y la medida.
                Canvas(Modifier.fillMaxSize()) {
                    if (camara < 0) return@Canvas
                    val mvp = matriz() ?: return@Canvas
                    // **Mirando un marcador con corte, el corte sí y la caja no** (25-oct-2026, el
                    // usuario): fuera las aristas y los tiradores mientras la vista sea la del marcador.
                    seccion?.takeIf { !cajaEscondida() }?.let { c ->
                        val raya = azul.copy(alpha = 0.75f)
                        for ((a, b) in aristasDeCaja(c)) {
                            val p = enPantalla(mvp, a); val q = enPantalla(mvp, b)
                            if (p != null && q != null) drawLine(raya, p, q, strokeWidth = 1.5f * densidad)
                        }
                        val lado = decimo(c)
                        for (k in 0 until 6) {
                            val centro = centroDeCara(c, k)
                            val p = enPantalla(mvp, centro) ?: continue
                            val q = enPantalla(mvp, Orbita.suma(centro, Orbita.por(fueraDeCara(k), lado)))
                            val grande = caraTirada?.first == k
                            val r = (if (grande) 9f else 7.5f) * densidad
                            if (q != null) {
                                val d = q - p
                                val l = d.getDistance()
                                if (l > 1e-3f) {
                                    val u = d / l
                                    val punta = p + u * (r + 13f * densidad)
                                    val base = p + u * (r + 2f * densidad)
                                    val n = Offset(-u.y, u.x) * (6.5f * densidad)
                                    drawPath(Path().apply { moveTo(punta.x, punta.y); lineTo(base.x + n.x, base.y + n.y); lineTo(base.x - n.x, base.y - n.y); close() }, azul)
                                }
                            }
                            drawCircle(Color.White, r + 1.5f * densidad, p)
                            drawCircle(azul, r, p)
                        }
                    }
                    pivote?.let { p -> enPantalla(mvp, p)?.let { q -> drawCircle(Color.White, 6.5f * densidad, q); drawCircle(Color(0xFFFF8A00), 4.5f * densidad, q) } }
                    if (medida.isNotEmpty()) {
                        val pts = medida.map { enPantalla(mvp, it) }
                        if (pts.size == 2 && pts[0] != null && pts[1] != null) drawLine(azul, pts[0]!!, pts[1]!!, strokeWidth = 2.5f * densidad)
                        for (q in pts) if (q != null) { drawCircle(Color.White, 6f * densidad, q); drawCircle(azul, 4f * densidad, q) }
                    }
                }
            }
            estado?.let { texto ->
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (texto == abriendo) { CircularProgressIndicator(color = if (claro) Color.Black else Color.White); Spacer(Modifier.size(16.dp)) }
                    Text(texto, color = if (claro) Color(0xFF6B6A66) else Color(0xFF9A9893))
                    if (texto != abriendo) TextButton(onClick = { com.forge.pixpin.ui.AbrirCon.abrir(this@Visor3DActivity, original) }) {
                        Text(getString(com.forge.pixpin.R.string.plano_otra_app))
                    }
                }
            }
            // La pastilla: nombre, ver todo, tema, aristas, planta y caja de sección.
            AnimatedVisibility(
                visible = aLaVista || estado != null, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp)
            ) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x8C14182B)).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val blanco = Color.White
                    com.forge.pixpin.ui.IconoDeFormato(nombre.takeIf { it.contains('.') } ?: original.name, Modifier.padding(end = 8.dp))
                    Text(nombre.substringBeforeLast('.'), color = blanco, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 180.dp).padding(vertical = 10.dp))
                    Spacer(Modifier.width(4.dp))
                    if (listo) {
                        // «Ver todo» ya no va en la barra (parecía de pantalla completa): doble toque en el vacío.
                        Boton(if (claro) Icons.Filled.DarkMode else Icons.Filled.LightMode, com.forge.pixpin.R.string.plano_tema, al = {
                            claro = !claro; prefs.edit().putBoolean("claro", claro).apply(); pintor?.claro = claro; pintar(); toques++
                        })
                        Boton(Icons.Filled.GridOn, com.forge.pixpin.R.string.modelo3d_aristas, aristas, al = { aristas = !aristas; pintor?.conAristas = aristas; pintar(); toques++ })
                        Boton(Icons.Filled.Map, com.forge.pixpin.R.string.modelo3d_planta, al = { orbita = orbita?.enPlanta(); repintar(); toques++ })
                        Boton(Icons.Filled.ContentCut, com.forge.pixpin.R.string.modelo3d_corte, seccion != null, al = {
                            // La caja recién puesta: la de lo que se ve, algo holgada (que no corte nada).
                            ponerSeccion(if (seccion != null) null else cajaVisible()?.let { cajaHolgada(it) }); toques++
                        })
                        IconButton(onClick = {
                            val titulo = nombre.substringBeforeLast('.').ifBlank { "Modelo" }
                            exportando = com.forge.pixpin.ui.Compartible(
                                titulo, formatos = com.forge.pixpin.ui.ExportarVisor.deLaVista(this@Visor3DActivity, titulo) { vista?.let { com.forge.pixpin.ui.ExportarVisor.fotoDeLaTarjeta(it) } } +
                                    listOf(com.forge.pixpin.ui.ExportarVisor.original(this@Visor3DActivity, original.absolutePath, nombre), com.forge.pixpin.ui.ExportarVisor.pixpin(this@Visor3DActivity, original.absolutePath, nombre))
                            )
                            toques++
                        }, modifier = Modifier.size(38.dp)) {
                            Icon(com.forge.pixpin.ui.IconoDeCompartir, contentDescription = "Exportar", tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
            // **Abrir con…, Guardar y Compartir**, solo de paso. Ver [com.forge.pixpin.ui.BarraDePaso].
            if (anotando == null) com.forge.pixpin.ui.BarraDePaso(
                estadoDePaso, aLaVista || estado != null, Modifier.align(Alignment.TopCenter),
                alCompartir = if (!listo) null else fun() {
                    val titulo = nombre.substringBeforeLast('.').ifBlank { "Modelo" }
                    exportando = com.forge.pixpin.ui.Compartible(
                        titulo, formatos = com.forge.pixpin.ui.ExportarVisor.deLaVista(this@Visor3DActivity, titulo) { vista?.let { com.forge.pixpin.ui.ExportarVisor.fotoDeLaTarjeta(it) } } +
                            listOf(com.forge.pixpin.ui.ExportarVisor.original(this@Visor3DActivity, original.absolutePath, nombre), com.forge.pixpin.ui.ExportarVisor.pixpin(this@Visor3DActivity, original.absolutePath, nombre))
                    )
                    toques++
                }
            )
            // De paso, con «Por Wi-Fi a mis aparatos». Ver [com.forge.pixpin.guardados.AbrirDePaso.conWifi].
            exportando?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(remember(c) { com.forge.pixpin.guardados.AbrirDePaso.conWifi(this@Visor3DActivity, c, estadoDePaso) }) { exportando = null } }
            // **Lo anotado del marcador de esta vista**: mirando, se pinta encima; anotando, el lienzo.
            @Suppress("UNUSED_EXPRESSION") camara
            val ahora = if (listo) estadoAhora() else null
            val vigente = ahora?.let { a -> vistas.modelo.firstOrNull { MarcadoresDelPlano.coincide(a, it) } }
            val idAnotando = anotando
            if (listo && idAnotando != null) {
                val c = capaDe(idAnotando)
                if (c != null) {
                    @Suppress("UNUSED_EXPRESSION") tickDeTinta
                    com.forge.pixpin.motor.DrawCanvas(
                        controller = c,
                        modifier = Modifier.fillMaxSize(),
                        imageProvider = { f -> c.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                        dark = !claro,
                        vistaFija = true,
                        cambios = tickDeTinta,
                        onChange = { trazando -> if (!trazando) tickDeTinta++ }
                    )
                    val ajustes by (application as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
                    // **El editor avanzado de todos los visores** (25-oct-2026). Ver [com.forge.pixpin.ui.EditorAvanzadoDeVisor].
                    EditorAvanzadoDeVisor(
                        capa = c, ajustes = ajustes,
                        permitidas = ajustes.formatoToolSet(com.forge.pixpin.motor.HerramientasPorSitio.Sitio.MODELO) - com.forge.pixpin.motor.LECTOR_TOOLS_FUERA,
                        grupos = ajustes.formatoGroupList(com.forge.pixpin.motor.HerramientasPorSitio.Sitio.MODELO)
                            .map { g -> g.filterNot { it in com.forge.pixpin.motor.LECTOR_TOOLS_FUERA } }.filter { it.isNotEmpty() },
                        noche = !claro, tick = tickDeTinta,
                        alListo = { dejarDeAnotar() },
                        cambiado = { tickDeTinta++ }
                    )
                    Text(
                        "Lo anotado solo se ve en la vista de este marcador",
                        color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp)
                            .background(Color(0xCC14182B), androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            } else if (listo && vigente != null && base != null) {
                val c = capaDe(vigente.id)
                if (c != null && c.scene.elements.any { !it.isDeleted }) {
                    val pintorDeTinta = remember(claro) {
                        com.forge.pixpin.motor.Renderer(
                            { f -> c.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                            com.forge.pixpin.motor.DrawFonts.provider(this@Visor3DActivity), dark = !claro
                        )
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        val escena = c.scene.copy(viewport = MarcadoresDelPlano.vistaDeTinta(size.width.toInt(), size.height.toInt()))
                        drawContext.canvas.nativeCanvas.let { pintorDeTinta.renderScene(it, escena, size.width.toDouble(), size.height.toDouble()) }
                    }
                }
            }
            // **Los marcos 3D**: los de esta vista, pegados a la pantalla.
            var cajaDelVisor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
            var ocultarParaImprimir by remember { mutableStateOf(false) }
            fun imprimirLosMarcos() {
                lifecycleScope.launch {
                    val volver = estadoAhora()
                    com.forge.pixpin.ui.ImprimirMarcos.imprimir(
                        this@Visor3DActivity, marcosDoc, nombre.substringBeforeLast('.'),
                        ir = { m -> marcos3d.marcos.firstOrNull { it.id == m.vista }?.estado?.let { ponerEstado(it) } },
                        cajaEnVentana = { m ->
                            val a = MarcadoresDelPlano.deNormal(m.x0, m.y0, ancho, alto); val b = MarcadoresDelPlano.deNormal(m.x1, m.y1, ancho, alto)
                            androidx.compose.ui.geometry.Rect(a[0].toFloat(), a[1].toFloat(), b[0].toFloat(), b[1].toFloat()).translate(cajaDelVisor.topLeft)
                        }
                    )
                    volver?.let { ponerEstado(it) }
                }
            }
            /** «Imprimir todo»: la vista de ahora, entera y sin los mandos. */
            fun imprimirLaVista() {
                val ventana = window?.decorView ?: return
                lifecycleScope.launch {
                    com.forge.pixpin.ui.ImprimirMarcos.imprimirLoQueSeVe(
                        this@Visor3DActivity, androidx.compose.ui.geometry.Rect(0f, 0f, ventana.width.toFloat(), ventana.height.toFloat()),
                        nombre.substringBeforeLast('.')
                    ) { ocultarParaImprimir = it; aLaVista = !it && aLaVista }
                }
            }
            if (listo && anotando == null && (marcosDoc.activo || marcosDoc.lista.isNotEmpty())) {
                val porId = marcos3d.marcos.associateBy { it.id }
                com.forge.pixpin.ui.CapaDeMarcos(
                    marcosDoc,
                    aPantalla = { x, y, _ -> MarcadoresDelPlano.deNormal(x, y, ancho, alto).let { Offset(it[0].toFloat(), it[1].toFloat()) } },
                    aDocumento = { p -> MarcadoresDelPlano.aNormal(p.x.toDouble(), p.y.toDouble(), ancho, alto).let { com.forge.pixpin.ui.PuntoDelDocumento(it[0], it[1]) } },
                    pxPorUnidad = { alto.toDouble() },
                    seVe = { m -> val e = porId[m.vista]?.estado; val a = estadoAhora(); e != null && a != null && MarcadoresDelPlano.coincide(a, e) },
                    alIr = { m -> porId[m.vista]?.estado?.let { ponerEstado(it) } },
                    alImprimir = { imprimirLosMarcos() },
                    alDosDedos = { pan, factor, _ ->
                        orbita?.let { o ->
                            var n = o.mover(pan.x.toDouble(), pan.y.toDouble(), alto)
                            if (factor > 0f && factor != 1f) n = n.zoom(1.0 / factor, n.objetivo)
                            orbita = n
                            repintar()
                        }
                    },
                    modifier = Modifier.fillMaxSize().onGloballyPositioned { cajaDelVisor = it.boundsInWindow() }
                )
            }
            // **El riel de la derecha**: marcador, lápiz (anotar sobre esta vista) y marcos, como el PC.
            if (listo && !marcosDoc.imprimiendo && !ocultarParaImprimir) com.forge.pixpin.ui.RielDelVisor(
                poniendoMarca = poniendoMarca, anotando = anotando != null, marcando = marcosDoc.activo,
                onMarcador = { dejarDeAnotar(); marcosDoc.activo = false; poniendoMarca = !poniendoMarca },
                onLapiz = { if (anotando != null) dejarDeAnotar() else { poniendoMarca = false; marcosDoc.activo = false; empezarAAnotar() } },
                onMarco = { dejarDeAnotar(); poniendoMarca = false; marcosDoc.alternar() },
                marcas = if (anotando != null) 0 else vistas.modelo.size, emojiDe = { i -> vistas.modelo[i].emoji },
                alElegirMarca = { i -> vistas.modelo.getOrNull(i)?.let { ponerEstado(it) } },
                imprimirMarcos = if (marcosDoc.lista.isEmpty()) null else ({ imprimirLosMarcos() }),
                imprimirTodo = { imprimirLaVista() },
                modifier = Modifier.align(Alignment.CenterEnd),
                // **Piezas, medir y al croquis, en el mismo riel** (25-oct-2026): antes una columna aparte
                // en el mismo sitio, que se pisaba con él. Una sola columna, como el visor de Revit.
                extras = if (anotando != null) null else ({
                    if (m != null && m.elementos.isNotEmpty()) com.forge.pixpin.ui.BotonDelRiel(Icons.Filled.Layers, getString(com.forge.pixpin.R.string.modelo3d_piezas), viendoPiezas) { viendoPiezas = !viendoPiezas; toques++ }
                    com.forge.pixpin.ui.BotonDelRiel(Icons.Filled.Straighten, getString(com.forge.pixpin.R.string.modelo3d_medir), midiendo) { midiendo = !midiendo; medida.clear(); camara++; toques++ }
                    if (m != null && m.nOpacos + m.nTransparentes > 0 && esModeloBim(nombre)) com.forge.pixpin.ui.BotonDelRiel(Icons.Filled.ViewInAr, getString(com.forge.pixpin.R.string.modelo3d_al_croquis)) {
                        com.forge.pixpin.croquis3d.Croquis3DActivity.abrirConModelo(this@Visor3DActivity, original, nombre)
                    }
                })
            )
            if (poniendoMarca) com.forge.pixpin.ui.ElegirEmojiDeMarca(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
                onCerrar = { poniendoMarca = false }
            ) { emoji ->
                poniendoMarca = false
                val ob = orbita
                val mm = modelo
                if (ob != null && mm != null) {
                    val o = mm.origen
                    val id = MarcadoresDelPlano.idNuevo(System.currentTimeMillis(), vistas.modelo.map { it.id } + vistas.plano.map { it.id })
                    val nuevo = MarcadoresDelPlano.Marcador3d(
                        id, emoji, doubleArrayOf(ob.objetivo[0] + o[0], ob.objetivo[1] + o[1], ob.objetivo[2] + o[2]), ob.rumbo, ob.altura, ob.dist,
                        seccion?.let { c -> DoubleArray(6) { k -> c[k] + o[k % 3] } }
                    )
                    guardarVistas(vistas.conModelo(vistas.modelo + nuevo))
                }
            }
            // Niveles y categorías (PC 9625d0d): un toque muestra u oculta, mantener deja solo ese.
            val ar = arbol
            if (viendoPiezas && m != null && ar != null) {
                val unidad = if (m.metros == 1f) "m" else ""
                val tenue = Color.White.copy(alpha = 0.55f)
                LazyColumn(
                    Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding()
                        .padding(end = 60.dp, top = 64.dp, bottom = 16.dp).width(270.dp)
                        .clip(RoundedCornerShape(18.dp)).background(Color(0xE614182B)).padding(vertical = 6.dp)
                ) {
                    item(key = " todo") {
                        val algo = grupos.isNotEmpty() || sueltos.isNotEmpty() || aislado >= 0
                        TextButton(onClick = { val estaba = aislado >= 0; mostrarTodo(); if (estaba) verTodo() }, enabled = algo, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(getString(com.forge.pixpin.R.string.modelo3d_mostrar_todo), color = if (algo) azul else tenue)
                        }
                    }
                    fun lista(titulo: Int, filas: List<ArbolDelModelo.Grupo>, prefijo: String) {
                        item(key = " cabecera $prefijo") {
                            Text(getString(titulo), color = tenue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp))
                        }
                        items(filas.size, key = { prefijo + filas[it].clave.toString() }) { i ->
                            val g = filas[i]
                            val ve = g.clave !in grupos
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { grupos = ar.alternar(grupos, g.clave); aplicar() },
                                    onLongClick = { grupos = ar.soloEse(grupos, g.clave); aplicar() },
                                ).padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                        .then(if (ve) Modifier.background(azul) else Modifier.border(1.3.dp, tenue, RoundedCornerShape(4.dp))),
                                    contentAlignment = Alignment.Center
                                ) { if (ve) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Text(g.nombre, color = if (ve) Color.White else Color.White.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                val dato = if (g.volumen > 0) "${g.cuenta} · ${ArbolDelModelo.conUnidad(g.volumen, unidad, 3)}" else "${g.cuenta}"
                                Text(dato, color = tenue, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                    if (ar.niveles.isNotEmpty()) lista(com.forge.pixpin.R.string.modelo3d_niveles, ar.niveles, "n")
                    lista(com.forge.pixpin.R.string.modelo3d_categorias, ar.tipos, "t")
                    item(key = " pista") {
                        Text(getString(com.forge.pixpin.R.string.modelo3d_solo_ese_pista), color = tenue, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            // Abajo: la cara que se arrastra, la medida, lo elegido y lo escondido.
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val unidad = if (m?.metros == 1f) " m" else ""
                caraTirada?.let { (k, v) ->
                    val absoluto = (m?.origen?.get(k % 3) ?: 0.0) + v
                    Chip(getString(com.forge.pixpin.R.string.modelo3d_altura_corte, "XYZ"[k % 3] + " " + String.format(java.util.Locale.ROOT, "%.2f", absoluto) + unidad))
                }
                if (midiendo) {
                    val texto = if (medida.size == 2) {
                        val d = Orbita.sub(medida[1], medida[0])
                        fun f(x: Double) = String.format(java.util.Locale.ROOT, "%.3f", x) + unidad
                        getString(com.forge.pixpin.R.string.modelo3d_medida, f(kotlin.math.sqrt(Orbita.punto(d, d))), f(kotlin.math.abs(d[2])))
                    } else getString(com.forge.pixpin.R.string.modelo3d_medir_pista)
                    Chip(texto)
                }
                elegido?.let { el ->
                    val t = if (m != null) ArbolDelModelo.describir(el, m, unidad.trim()) else TiposIfc.legible(el.tipo)
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(vertical = 9.dp))
                        val e = elegidoIdx
                        if (m != null && e in ocultos.indices) {
                            TextButton(onClick = {
                                soltar()
                                // Si estaba aislado, se vuelve a lo de antes sin él (no a nada a la vista).
                                sueltos = sueltos + e
                                aplicar()
                            }) { Text(getString(com.forge.pixpin.R.string.modelo3d_ocultar), color = azul) }
                            if (aislado != e) TextButton(onClick = { aislar(e) }) { Text(getString(com.forge.pixpin.R.string.modelo3d_aislar), color = azul) }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                }
                val escondidos = ocultos.count { it.toInt() != 0 }
                if (escondidos > 0 && !viendoPiezas) {
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(getString(com.forge.pixpin.R.string.modelo3d_escondidos, escondidos), color = Color.White.copy(alpha = 0.8f))
                        TextButton(onClick = { val estaba = aislado >= 0; mostrarTodo(); if (estaba) verTodo() }) {
                            Text(getString(com.forge.pixpin.R.string.modelo3d_mostrar_todo), color = azul)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Boton(icono: androidx.compose.ui.graphics.vector.ImageVector, desc: Int, activo: Boolean = false, tamano: Int = 38, al: () -> Unit) {
        IconButton(onClick = al, modifier = Modifier.size(tamano.dp)) {
            Icon(icono, contentDescription = getString(desc), tint = if (activo) Color(0xFF5AA0FF) else Color.White.copy(alpha = 0.9f), modifier = Modifier.size((tamano / 2).dp))
        }
    }

    @Composable
    private fun Chip(texto: String) {
        Text(texto, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(horizontal = 16.dp, vertical = 9.dp))
        Spacer(Modifier.size(8.dp))
    }

    /** Un punto del modelo en la pantalla (píxeles); null si queda detrás del ojo. */
    private fun enPantalla(mvp: FloatArray, p: DoubleArray): Offset? {
        val x = mvp[0] * p[0] + mvp[4] * p[1] + mvp[8] * p[2] + mvp[12]
        val y = mvp[1] * p[0] + mvp[5] * p[1] + mvp[9] * p[2] + mvp[13]
        val w = mvp[3] * p[0] + mvp[7] * p[1] + mvp[11] * p[2] + mvp[15]
        if (w <= 1e-9) return null
        return Offset(((x / w + 1) / 2 * ancho).toFloat(), ((1 - y / w) / 2 * alto).toFloat())
    }

    /** El tirador bajo [p] (su disco o su flecha), si hay uno a menos de [radio] píxeles. */
    private fun tiradorEn(c: FloatArray, mvp: FloatArray, p: Offset, radio: Float): Int? {
        val lado = decimo(c)
        var mejor: Int? = null
        var dMejor = radio
        for (k in 0 until 6) {
            val centro = centroDeCara(c, k)
            val a = enPantalla(mvp, centro) ?: continue
            val b = enPantalla(mvp, Orbita.suma(centro, Orbita.por(fueraDeCara(k), lado)))
            var d = (a - p).getDistance()
            if (b != null) {
                val l = (b - a).getDistance()
                if (l > 1e-3f) d = minOf(d, (a + (b - a) / l * (radio * 0.9f) - p).getDistance())
            }
            if (d <= dMejor) { dMejor = d; mejor = k }
        }
        return mejor
    }

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"

        /** Lo que el croquis 3D sabe recibir del visor: IFC y Revit (no los planos ni Civil 3D). */
        private fun esModeloBim(nombre: String) = LectorDePlanos.esDelVisor3D(nombre)

        // ---- La caja de sección (`ventana3d.rs` del PC): caras 0..3 las del mínimo x, y, z; 3..6 las del máximo.

        /** El centro de la cara [k]. */
        fun centroDeCara(c: FloatArray, k: Int): DoubleArray {
            val p = doubleArrayOf((c[0] + c[3]) / 2.0, (c[1] + c[4]) / 2.0, (c[2] + c[5]) / 2.0)
            p[k % 3] = c[k].toDouble()
            return p
        }

        /** Hacia fuera de la cara [k]. */
        fun fueraDeCara(k: Int): DoubleArray = DoubleArray(3).also { it[k % 3] = if (k < 3) -1.0 else 1.0 }

        /** Un décimo del lado mayor: la medida para ver cómo se mueve una cara en la pantalla. */
        fun decimo(c: FloatArray): Double = (maxOf(c[3] - c[0], c[4] - c[1], c[5] - c[2]) * 0.1).toDouble().coerceAtLeast(1e-6)

        /** Las 12 aristas de la caja. */
        fun aristasDeCaja(c: FloatArray): List<Pair<DoubleArray, DoubleArray>> {
            fun esq(i: Int) = doubleArrayOf(
                c[if (i and 1 == 0) 0 else 3].toDouble(), c[if (i and 2 == 0) 1 else 4].toDouble(), c[if (i and 4 == 0) 2 else 5].toDouble()
            )
            val v = ArrayList<Pair<DoubleArray, DoubleArray>>(12)
            for (i in 0 until 8) for (bit in intArrayOf(1, 2, 4)) if (i and bit == 0) v += esq(i) to esq(i or bit)
            return v
        }

        /** La caja algo holgada (un 1 % del lado mayor): la recién puesta no corta nada. */
        fun cajaHolgada(c: FloatArray): FloatArray {
            val m = maxOf(c[3] - c[0], c[4] - c[1], c[5] - c[2]) * 0.01f
            return floatArrayOf(c[0] - m, c[1] - m, c[2] - m, c[3] + m, c[4] + m, c[5] + m)
        }

        /** Lleva la cara [k] a [valor], sin cruzar la de enfrente ni irse lejos del modelo ([limite]). */
        fun moverCara(c: FloatArray, k: Int, valor: Double, limite: FloatArray): FloatArray {
            val n = c.copyOf()
            val a = k % 3
            val hueco = ((limite[a + 3] - limite[a]) * 0.002f).coerceAtLeast(1e-6f)
            val v = valor.toFloat()
            n[k] = if (k < 3) v.coerceIn(limite[a], (c[a + 3] - hueco).coerceAtLeast(limite[a]))
            else v.coerceIn((c[a] + hueco).coerceAtMost(limite[a + 3]), limite[a + 3])
            return n
        }

        fun abrir(c: Context, ruta: String, nombre: String?) {
            // Desde fuera y sin la aplicación abierta, con la lista de chats debajo. Ver [com.forge.pixpin.Volver.abrirEnLaApp].
            com.forge.pixpin.Volver.abrirEnLaApp(c, Intent(c, Visor3DActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre))
        }
    }
}
