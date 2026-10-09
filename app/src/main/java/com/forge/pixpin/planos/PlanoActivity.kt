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
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import kotlin.math.hypot

/**
 * **Ver planos DWG y DXF, a pantalla completa** (9-oct-2026). En el PC el visor es una ventana
 * como un pin, con una barrita aparte que sale al pasar el ratón (`crates/pixpin-cad`); en el
 * teléfono, el usuario lo pidió **a pantalla completa**: el plano lo ocupa todo y lo único nuestro
 * es la pastilla del nombre, como en el visor de páginas web — sale al entrar y al tocar, y se va
 * sola o en cuanto se mueve el plano —, con lo mismo que la barra del PC: **el tema** (fondo claro
 * u oscuro) y **acotar plano**, más «ver todo» (en el PC es la tecla F).
 *
 * - **Un dedo** mueve, **dos** acercan y alejan, **doble toque** enseña el plano entero.
 * - **Acotar**: cada toque pone un punto, enganchado al vértice, extremo o centro de círculo más
 *   cercano (los de [Enganches]); las medidas se encadenan con su total (Σ), en las unidades del
 *   plano (`$INSUNITS`). Doble toque o ✓ termina la cadena; ↶ quita el último punto; la papelera
 *   borra las cotas. Como en el PC (allí: doble clic o Intro, Retroceso y Supr).
 *
 * El plano se lee en otro proceso ([LectorDePlanos]) y se dibuja con [PintorDePlano].
 */
class PlanoActivity : ComponentActivity() {
    private var vista: GLSurfaceView? = null
    private var pintor: PintorDePlano? = null
    private var modelo: ModeloCad? = null
    private var enganches: Enganches? = null
    private var camara: CamaraPlano? = null
    private var ancho = 0
    private var alto = 0

    /** Sube con cada cambio de la vista: lo lee solo el dibujo de las cotas (no recompone nada). */
    private var marcos by mutableIntStateOf(0)

    private val prefs by lazy { getSharedPreferences("planos", Context.MODE_PRIVATE) }

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
        setContent { PixPinTheme { Pantalla(File(ruta), nombre) } }
    }

    override fun onResume() { super.onResume(); vista?.onResume() }
    override fun onPause() { vista?.onPause(); super.onPause() }

    private fun pintar() {
        pintor?.camara = camara
        vista?.requestRender()
        marcos++
    }

    private fun verTodo() {
        val m = modelo ?: return
        if (ancho <= 0 || alto <= 0) return
        camara = CamaraPlano.encuadrar(m.caja, ancho, alto)
        pintar()
    }

    @Composable
    private fun Pantalla(original: File, nombre: String) {
        var estado by remember { mutableStateOf<String?>(getString(com.forge.pixpin.R.string.plano_abriendo)) }
        var listo by remember { mutableStateOf(false) }
        var claro by remember { mutableStateOf(prefs.getBoolean("claro", false)) }
        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableIntStateOf(0) }
        var acotando by remember { mutableStateOf(false) }
        // Las cotas: cadenas hechas y la que se está poniendo (puntos del plano).
        var hechas by remember { mutableStateOf<List<List<DoubleArray>>>(emptyList()) }
        var puntos by remember { mutableStateOf<List<DoubleArray>>(emptyList()) }
        var enganchado by remember { mutableStateOf<DoubleArray?>(null) }
        val densidad = resources.displayMetrics.density

        LaunchedEffect(original) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (val leido = LectorDePlanos.leer(this@PlanoActivity, original)) {
                        is LectorDePlanos.Companion.Resultado.Fallo -> throw ModeloCad.NoSeLee(leido.motivo)
                        is LectorDePlanos.Companion.Resultado.Listo -> ModeloCad.abrir(leido.archivo)
                    }
                }
            }
            r.onSuccess { m ->
                if (m.vacio) { estado = getString(com.forge.pixpin.R.string.plano_vacio); return@onSuccess }
                modelo = m
                pintor = PintorDePlano(m, grosor = (densidad / 1.5f).coerceIn(1f, 3f)).also {
                    it.claro = claro
                    it.alCambiarTamano = { w, h -> runOnUiThread { alCambiarTamano(w, h) } }
                }
                estado = null
                listo = true
                // Los enganches de la cota, sin esperar a que se pidan (en un plano grande tardan).
                lifecycleScope.launch(Dispatchers.Default) { enganches = runCatching { Enganches.de(m) }.getOrNull() }
            }.onFailure { e ->
                estado = (e as? ModeloCad.NoSeLee)?.message ?: getString(com.forge.pixpin.R.string.plano_no)
            }
        }
        LaunchedEffect(aLaVista, toques, acotando) {
            if (aLaVista && !acotando) { delay(3500); aLaVista = false }
        }
        LaunchedEffect(Unit) {
            // Si la tarjeta no pudo, se dice (el hilo de la tarjeta no puede tocar la interfaz).
            while (true) {
                delay(500)
                pintor?.fallo?.let { estado = getString(com.forge.pixpin.R.string.plano_tarjeta, it); listo = false; return@LaunchedEffect }
            }
        }

        fun terminarCadena() {
            if (puntos.size >= 2) hechas = hechas + listOf(puntos)
            puntos = emptyList(); enganchado = null; marcos++
        }
        BackHandler {
            when {
                acotando && puntos.isNotEmpty() -> terminarCadena()
                acotando -> acotando = false
                else -> finish()
            }
        }

        val fondo = if (claro) PintorDePlano.FONDO_CLARO else PintorDePlano.FONDO_OSCURO
        // El fondo lo pinta la tarjeta en cuanto hay plano: uno de Compose encima taparía el
        // hueco por donde se ve el SurfaceView.
        Box(Modifier.fillMaxSize().then(if (listo) Modifier else Modifier.background(Color(fondo[0], fondo[1], fondo[2])))) {
            if (listo) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        GLSurfaceView(c).apply {
                            setEGLContextClientVersion(3)
                            setEGLConfigChooser(PintorDePlano.Muestreo())
                            preserveEGLContextOnPause = true
                            setRenderer(pintor)
                            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                            vista = this
                        }
                    }
                )
                // Gestos y cotas encima del plano.
                Box(
                    Modifier.fillMaxSize().pointerInput(acotando) {
                        var ultimoToque = 0L
                        var dondeUltimo = Offset.Zero
                        awaitEachGesture {
                            val abajo = awaitFirstDown(requireUnconsumed = false)
                            var movido = false
                            var varios = false
                            var corrido = Offset.Zero
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) varios = true
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (!movido) {
                                    corrido += pan
                                    if (corrido.getDistance() > viewConfiguration.touchSlop || (varios && zoom != 1f)) {
                                        movido = true
                                        aLaVista = false
                                    }
                                }
                                val c = camara
                                if (movido && c != null && ancho > 0) {
                                    val centro = ev.calculateCentroid(useCurrent = true)
                                    var n = c
                                    if (zoom != 1f && zoom > 0f && centro != Offset.Unspecified) {
                                        n = n.zoom(1.0 / zoom, centro.x.toDouble(), centro.y.toDouble(), ancho, alto)
                                    }
                                    n = n.mover(pan.x.toDouble(), pan.y.toDouble())
                                    camara = n
                                    pintar()
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                            if (!movido && !varios) {
                                val ahora = System.currentTimeMillis()
                                val doble = ahora - ultimoToque < 300 && (abajo.position - dondeUltimo).getDistance() < 48 * densidad
                                ultimoToque = if (doble) 0L else ahora
                                dondeUltimo = abajo.position
                                val c = camara
                                when {
                                    acotando && doble -> terminarCadena()
                                    acotando && c != null -> {
                                        val x = c.planoX(abajo.position.x.toDouble(), ancho)
                                        val y = c.planoY(abajo.position.y.toDouble(), alto)
                                        val g = enganches?.cerca(x, y, 28.0 * densidad * c.px)
                                        enganchado = g
                                        puntos = puntos + listOf(g ?: doubleArrayOf(x, y))
                                        marcos++
                                    }
                                    doble -> verTodo()
                                    else -> { aLaVista = !aLaVista; toques++ }
                                }
                            }
                        }
                    }
                ) {
                    Cotas(hechas, puntos, enganchado, claro, densidad)
                }
            }
            estado?.let { texto ->
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!listo && texto == getString(com.forge.pixpin.R.string.plano_abriendo)) {
                        CircularProgressIndicator(color = if (claro) Color.Black else Color.White)
                        Spacer(Modifier.size(16.dp))
                    }
                    Text(texto, color = if (claro) Color(0xFF6B6A66) else Color(0xFF9A9893))
                    if (texto != getString(com.forge.pixpin.R.string.plano_abriendo)) {
                        TextButton(onClick = { com.forge.pixpin.ui.AbrirCon.abrir(this@PlanoActivity, original) }) {
                            Text(getString(com.forge.pixpin.R.string.plano_otra_app))
                        }
                    }
                }
            }
            // La pastilla del nombre, con el tema, acotar y ver todo.
            AnimatedVisibility(
                visible = aLaVista || estado != null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0x8C14182B)).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val blanco = Color.White
                    Text(
                        nombre.substringBeforeLast('.'), color = blanco, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp).padding(vertical = 10.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    if (listo) {
                        IconButton(onClick = { verTodo(); toques++ }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Filled.FitScreen, contentDescription = getString(com.forge.pixpin.R.string.plano_ver_todo), tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = {
                            claro = !claro
                            prefs.edit().putBoolean("claro", claro).apply()
                            pintor?.claro = claro
                            pintar(); toques++
                        }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                if (claro) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                                contentDescription = getString(com.forge.pixpin.R.string.plano_tema), tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(onClick = { acotando = !acotando; if (!acotando) terminarCadena(); toques++ }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Filled.Straighten, contentDescription = getString(com.forge.pixpin.R.string.plano_acotar),
                                tint = if (acotando) Color(0xFFFF8A00) else blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
            // Acotando: la pista y sus mandos, abajo, mientras dure.
            if (acotando) {
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val blanco = Color.White
                    Text(
                        getString(if (puntos.isEmpty()) com.forge.pixpin.R.string.plano_pista_acotar else com.forge.pixpin.R.string.plano_pista_seguir),
                        color = blanco, maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp)
                    )
                    IconButton(onClick = { puntos = puntos.dropLast(1); enganchado = null; marcos++ }, enabled = puntos.isNotEmpty(), modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Undo, contentDescription = getString(com.forge.pixpin.R.string.plano_quitar_punto), tint = blanco.copy(alpha = if (puntos.isNotEmpty()) 0.9f else 0.35f), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { terminarCadena() }, enabled = puntos.size >= 2, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = getString(com.forge.pixpin.R.string.plano_terminar), tint = blanco.copy(alpha = if (puntos.size >= 2) 0.9f else 0.35f), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { hechas = emptyList(); puntos = emptyList(); enganchado = null; marcos++ }, enabled = hechas.isNotEmpty() || puntos.isNotEmpty(), modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = getString(com.forge.pixpin.R.string.plano_borrar_cotas), tint = blanco.copy(alpha = if (hechas.isNotEmpty() || puntos.isNotEmpty()) 0.9f else 0.35f), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { terminarCadena(); acotando = false }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = getString(com.forge.pixpin.R.string.plano_dejar_de_acotar), tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    private fun alCambiarTamano(w: Int, h: Int) {
        val primeraVez = camara == null || ancho <= 0
        ancho = w; alto = h
        // Al girar el teléfono se queda mirando lo mismo, con la misma escala.
        if (primeraVez) verTodo() else pintar()
    }

    /**
     * Las cotas, dibujadas por encima del plano (en el PC van a la tarjeta como un plano más; aquí
     * son pocas rayas y el lienzo de Android basta). Naranja, con su medida en una pastilla; el
     * total (Σ) al final de una cadena de más de un tramo; un cuadro verde en el último enganche.
     */
    @Composable
    private fun Cotas(hechas: List<List<DoubleArray>>, puntos: List<DoubleArray>, enganchado: DoubleArray?, claro: Boolean, densidad: Float) {
        val m = modelo ?: return
        val naranja = android.graphics.Color.rgb(0xFF, 0x8A, 0x00)
        val raya = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val letra = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val pildora = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val leer = marcos // repinta al mover, sin recomponer
            val c = camara ?: return@Canvas
            val lienzo = drawContext.canvas.nativeCanvas
            raya.color = naranja; raya.strokeWidth = 2f * densidad; raya.style = android.graphics.Paint.Style.STROKE
            letra.textSize = 12.5f * densidad; letra.color = if (claro) android.graphics.Color.WHITE else android.graphics.Color.rgb(0x1C, 0x1C, 0x1E)
            pildora.color = if (claro) 0xEE1C1C1E.toInt() else 0xEEF5F5F7.toInt()
            fun sx(p: DoubleArray) = c.pantallaX(p[0], ancho).toFloat()
            fun sy(p: DoubleArray) = c.pantallaY(p[1], alto).toFloat()
            fun etiqueta(t: String, x: Float, y: Float) {
                val w = letra.measureText(t)
                val r = android.graphics.RectF(x - w / 2 - 6 * densidad, y - letra.textSize * 0.95f, x + w / 2 + 6 * densidad, y + letra.textSize * 0.6f)
                lienzo.drawRoundRect(r, 6 * densidad, 6 * densidad, pildora)
                lienzo.drawText(t, x - w / 2, y + letra.textSize * 0.25f, letra)
            }
            for (cadena in hechas + listOf(puntos)) {
                if (cadena.isEmpty()) continue
                var total = 0.0
                for (k in 1 until cadena.size) {
                    val p = cadena[k - 1]; val q = cadena[k]
                    lienzo.drawLine(sx(p), sy(p), sx(q), sy(q), raya)
                    val d = hypot(q[0] - p[0], q[1] - p[1])
                    total += d
                    val mx = (sx(p) + sx(q)) / 2; val my = (sy(p) + sy(q)) / 2
                    val dx = sx(q) - sx(p); val dy = sy(q) - sy(p)
                    val l = hypot(dx, dy).coerceAtLeast(1e-6f)
                    val nx = -dy / l; val ny = dx / l
                    val lado = if (ny > 0) -1f else 1f
                    etiqueta(Medidas.medida(d, m.unidades), mx + nx * lado * 16 * densidad, my + ny * lado * 16 * densidad)
                }
                raya.style = android.graphics.Paint.Style.FILL
                for (p in cadena) lienzo.drawRect(sx(p) - 3 * densidad, sy(p) - 3 * densidad, sx(p) + 3 * densidad, sy(p) + 3 * densidad, raya)
                raya.style = android.graphics.Paint.Style.STROKE
                if (cadena.size > 2) {
                    val u = cadena.last()
                    etiqueta("Σ " + Medidas.medida(total, m.unidades), sx(u) + 40 * densidad, sy(u) + 24 * densidad)
                }
            }
            enganchado?.let { g ->
                val s = 7 * densidad
                raya.color = android.graphics.Color.rgb(0x30, 0xD1, 0x58); raya.strokeWidth = 1.8f * densidad
                lienzo.drawRect(sx(g) - s, sy(g) - s, sx(g) + s, sy(g) + s, raya)
            }
        }
    }

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"

        fun esPlano(nombre: String?): Boolean = LectorDePlanos.esPlano(nombre)

        fun abrir(c: Context, ruta: String, nombre: String?) {
            c.startActivity(
                Intent(c, PlanoActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre)
                    .apply { if (c !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
