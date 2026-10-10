package com.forge.pixpin.planos

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
import androidx.compose.foundation.clickable
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
 * descolocado»). Y lo que en el croquis tenía el panel del modelo: **piezas por tipo** que se
 * esconden, **ocultar** o **aislar** lo tocado, **medir** entre dos puntos del modelo, y **llevarlo
 * al croquis** (leído con esta misma lectura) para dibujar encima.
 */
class Visor3DActivity : ComponentActivity() {
    private var vista: GLSurfaceView? = null
    private var pintor: Pintor3D? = null
    private var modelo: Modelo3D? = null
    private var orbita: Orbita? = null
    /** La caja del elemento aislado (lo que se encuadra con «ver todo»), o null. */
    private var cajaAislada: FloatArray? = null
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

    @Composable
    private fun Pantalla(original: File, nombre: String) {
        val abriendo = getString(com.forge.pixpin.R.string.modelo3d_abriendo)
        var estado by remember { mutableStateOf<String?>(abriendo) }
        var listo by remember { mutableStateOf(false) }
        var claro by remember { mutableStateOf(prefs.getBoolean("claro", false)) }
        var aristas by remember { mutableStateOf(true) }
        var elegido by remember { mutableStateOf<Pair<String, String>?>(null) }
        var elegidoIdx by remember { mutableIntStateOf(-1) }
        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableIntStateOf(0) }
        val densidad = resources.displayMetrics.density
        // Lo escondido (un byte por elemento) y el aislado (o -1).
        var ocultos by remember { mutableStateOf(ByteArray(0)) }
        var aislado by remember { mutableIntStateOf(-1) }
        // La caja de sección (relativa al origen), y la cara que se arrastra con su valor.
        var seccion by remember { mutableStateOf<FloatArray?>(null) }
        var caraTirada by remember { mutableStateOf<Pair<Int, Float>?>(null) }
        // El punto alrededor del que se gira, mientras se gira.
        var pivote by remember { mutableStateOf<DoubleArray?>(null) }
        var viendoPiezas by remember { mutableStateOf(false) }
        var midiendo by remember { mutableStateOf(false) }
        val medida = remember { mutableStateListOf<DoubleArray>() }
        // Sube con cada fotograma pedido: lo que va dibujado encima sigue a la cámara.
        var camara by remember { mutableIntStateOf(0) }

        fun repintar() { pintar(); camara++ }
        fun esconder(nuevos: ByteArray) { ocultos = nuevos; pintor?.ocultos = nuevos; repintar() }
        fun ponerSeccion(c: FloatArray?) { seccion = c; pintor?.seccion = c; repintar() }
        fun soltar() { elegido = null; elegidoIdx = -1; pintor?.elegido = -1 }
        fun mostrarTodo() {
            aislado = -1; cajaAislada = null
            modelo?.let { esconder(ByteArray(it.elementos.size)) }
        }
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
        BackHandler {
            when {
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
                        val tirador = if (caja != null && mvp != null) tiradorEn(caja, mvp, abajo.position, 24f * densidad) else null
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
                    seccion?.let { c ->
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
                    Text(nombre.substringBeforeLast('.'), color = blanco, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 180.dp).padding(vertical = 10.dp))
                    Spacer(Modifier.width(4.dp))
                    if (listo) {
                        Boton(Icons.Filled.FitScreen, com.forge.pixpin.R.string.plano_ver_todo, al = { verTodo(); camara++; toques++ })
                        Boton(if (claro) Icons.Filled.DarkMode else Icons.Filled.LightMode, com.forge.pixpin.R.string.plano_tema, al = {
                            claro = !claro; prefs.edit().putBoolean("claro", claro).apply(); pintor?.claro = claro; pintar(); toques++
                        })
                        Boton(Icons.Filled.GridOn, com.forge.pixpin.R.string.modelo3d_aristas, aristas, al = { aristas = !aristas; pintor?.conAristas = aristas; pintar(); toques++ })
                        Boton(Icons.Filled.Map, com.forge.pixpin.R.string.modelo3d_planta, al = { orbita = orbita?.enPlanta(); repintar(); toques++ })
                        Boton(Icons.Filled.ContentCut, com.forge.pixpin.R.string.modelo3d_corte, seccion != null, al = {
                            // La caja recién puesta: la de lo que se ve, algo holgada (que no corte nada).
                            ponerSeccion(if (seccion != null) null else cajaVisible()?.let { cajaHolgada(it) }); toques++
                        })
                    }
                }
            }
            // A la derecha: piezas, medir y llevar al croquis (lo que el croquis tenía en su panel).
            AnimatedVisibility(
                visible = listo && (aLaVista || viendoPiezas || midiendo), enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 10.dp)
            ) {
                Column(Modifier.clip(RoundedCornerShape(50)).background(Color(0x8C14182B)).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (m != null && m.elementos.isNotEmpty()) Boton(Icons.Filled.Layers, com.forge.pixpin.R.string.modelo3d_piezas, viendoPiezas, 42, al = { viendoPiezas = !viendoPiezas; toques++ })
                    Boton(Icons.Filled.Straighten, com.forge.pixpin.R.string.modelo3d_medir, midiendo, 42, al = { midiendo = !midiendo; medida.clear(); camara++; toques++ })
                    if (m != null && m.nOpacos + m.nTransparentes > 0 && esModeloBim(nombre)) Boton(Icons.Filled.ViewInAr, com.forge.pixpin.R.string.modelo3d_al_croquis, tamano = 42, al = {
                        com.forge.pixpin.croquis3d.Croquis3DActivity.abrirConModelo(this@Visor3DActivity, original, nombre)
                    })
                }
            }
            // Las piezas por tipo: encender y apagar todo un tipo (muros, losas, ventanas…).
            if (viendoPiezas && m != null) {
                val tipos = remember(m) {
                    m.elementos.withIndex().groupBy { TiposIfc.legible(it.value.first) }
                        .map { (n, l) -> n to l.map { it.index }.toIntArray() }.sortedBy { it.first.lowercase() }
                }
                LazyColumn(
                    Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding()
                        .padding(end = 60.dp, top = 64.dp, bottom = 16.dp).width(230.dp)
                        .clip(RoundedCornerShape(18.dp)).background(Color(0xE614182B)).padding(vertical = 6.dp)
                ) {
                    item(key = " todo") {
                        TextButton(onClick = { mostrarTodo() }, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(getString(com.forge.pixpin.R.string.modelo3d_mostrar_todo), color = azul)
                        }
                    }
                    items(tipos.size, key = { tipos[it].first }) { i ->
                        val (n, cuales) = tipos[i]
                        val visibles = cuales.count { ocultos.getOrNull(it)?.toInt() == 0 }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val nuevos = ocultos.copyOf()
                                val poner: Byte = if (visibles > 0) 1 else 0
                                for (e in cuales) nuevos[e] = poner
                                aislado = -1; cajaAislada = null
                                esconder(nuevos)
                            }.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(n, color = if (visibles > 0) Color.White else Color.White.copy(alpha = 0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(if (visibles == cuales.size) "${cuales.size}" else "$visibles/${cuales.size}", color = Color.White.copy(alpha = 0.55f))
                        }
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
                elegido?.let { (tipo, n) ->
                    val t = TiposIfc.legible(tipo).let { if (n.isBlank()) it else "$it · $n" }
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(vertical = 9.dp))
                        val e = elegidoIdx
                        if (m != null && e in ocultos.indices) {
                            TextButton(onClick = {
                                val nuevos = ocultos.copyOf(); nuevos[e] = 1
                                soltar()
                                if (aislado == e) { aislado = -1; cajaAislada = null }
                                esconder(nuevos)
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
            c.startActivity(
                Intent(c, Visor3DActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre)
                    .apply { if (c !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
