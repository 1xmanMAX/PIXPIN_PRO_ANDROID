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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
 * de la barra del PC — ver todo, fondo claro u oscuro, aristas, planta y el **corte** horizontal.
 *
 * - **Un dedo** gira alrededor de lo que se mira; **dos** mueven y acercan.
 * - **Tocar** un elemento lo resalta en azul y dice qué es («Muro · M-01», «Superficie · TN»…).
 * - **Doble toque**: el modelo entero.
 */
class Visor3DActivity : ComponentActivity() {
    private var vista: GLSurfaceView? = null
    private var pintor: Pintor3D? = null
    private var modelo: Modelo3D? = null
    private var orbita: Orbita? = null
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

    private fun verTodo() {
        val m = modelo ?: return
        if (ancho <= 0) return
        orbita = Orbita.encuadrar(m.cajaUtil(), ancho, alto)
        pintar()
    }

    @Composable
    private fun Pantalla(original: File, nombre: String) {
        val abriendo = getString(com.forge.pixpin.R.string.modelo3d_abriendo)
        var estado by remember { mutableStateOf<String?>(abriendo) }
        var listo by remember { mutableStateOf(false) }
        var claro by remember { mutableStateOf(prefs.getBoolean("claro", false)) }
        var aristas by remember { mutableStateOf(true) }
        var cortando by remember { mutableStateOf(false) }
        var corte by remember { mutableFloatStateOf(0.6f) }
        var elegido by remember { mutableStateOf<Pair<String, String>?>(null) }
        var aLaVista by remember { mutableStateOf(true) }
        var toques by remember { mutableIntStateOf(0) }
        val densidad = resources.displayMetrics.density

        LaunchedEffect(original) {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (val leido = LectorDePlanos.leer3D(this@Visor3DActivity, original)) {
                        is LectorDePlanos.Companion.Resultado.Fallo -> throw Modelo3D.NoSeLee(leido.motivo)
                        is LectorDePlanos.Companion.Resultado.Listo -> Modelo3D.abrir(leido.archivo)
                    }
                }
            }
            r.onSuccess { m ->
                if (m.vacio) { estado = getString(com.forge.pixpin.R.string.modelo3d_vacio); return@onSuccess }
                modelo = m
                pintor = Pintor3D(m, grosor = (densidad / 1.5f).coerceIn(1f, 3f), densidad = densidad).also {
                    it.claro = claro
                    it.alCambiarTamano = { w, h -> runOnUiThread { val primera = orbita == null || ancho <= 0; ancho = w; alto = h; if (primera) verTodo() else pintar() } }
                }
                estado = null
                listo = true
            }.onFailure { e -> estado = (e as? Modelo3D.NoSeLee)?.message ?: getString(com.forge.pixpin.R.string.modelo3d_no) }
        }
        LaunchedEffect(aLaVista, toques, cortando) { if (aLaVista && !cortando) { delay(3500); aLaVista = false } }
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                pintor?.fallo?.let { estado = getString(com.forge.pixpin.R.string.plano_tarjeta, it); listo = false; return@LaunchedEffect }
            }
        }
        BackHandler { if (elegido != null) { elegido = null; pintor?.elegido = -1; pintar() } else finish() }

        val m = modelo
        val alturaDelCorte: Float? = if (cortando && m != null) m.caja[2] + (m.caja[5] - m.caja[2]) * corte else null
        LaunchedEffect(alturaDelCorte) { pintor?.corte = alturaDelCorte; pintar() }

        val fondo = if (claro) Color(0xFFF3F4F6) else Color(0xFF212633)
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
                        var movido = false
                        var varios = false
                        var corrido = Offset.Zero
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
                                    var n = o.mover(pan.x.toDouble(), pan.y.toDouble(), alto)
                                    if (zoom > 0f && zoom != 1f) n = n.zoom(1.0 / zoom, n.objetivo)
                                    n
                                } else {
                                    // Un dedo gira: el ancho de la pantalla, media vuelta.
                                    o.girar(-pan.x / ancho.coerceAtLeast(1) * Math.PI, pan.y / alto.coerceAtLeast(1) * Math.PI, o.objetivo)
                                }
                                pintar()
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (!movido && !varios) {
                            val ahora = System.currentTimeMillis()
                            val doble = ahora - ultimoToque < 300
                            ultimoToque = if (doble) 0L else ahora
                            if (doble) { verTodo(); return@awaitEachGesture }
                            // Qué hay bajo el dedo: en otro hilo (un modelo grande son millones de triángulos).
                            val o = orbita ?: return@awaitEachGesture
                            val mm = modelo ?: return@awaitEachGesture
                            val dir = o.rayo(abajo.position.x.toDouble(), abajo.position.y.toDouble(), ancho, alto)
                            val tolerancia = 6.0 * densidad * 2 * kotlin.math.tan(Orbita.FOV / 2) / alto.coerceAtLeast(1)
                            val corteAhora = pintor?.corte
                            lifecycleScope.launch {
                                val t = withContext(Dispatchers.Default) { runCatching { ElegirEn3D.elegir(mm, o.ojo(), dir, corteAhora, tolerancia) }.getOrNull() }
                                if (t == null) { elegido = null; pintor?.elegido = -1; aLaVista = !aLaVista; toques++ }
                                else {
                                    pintor?.elegido = t.elemento
                                    elegido = mm.elementos.getOrNull(t.elemento)
                                    // Se sigue girando alrededor de lo tocado.
                                    orbita = orbita?.let { ob -> ob.copy(objetivo = t.punto, dist = Orbita.punto(Orbita.sub(t.punto, ob.ojo()), Orbita.sub(t.punto, ob.ojo())).let { kotlin.math.sqrt(it) }.coerceAtLeast(1e-3)) }
                                }
                                pintar()
                            }
                        }
                    }
                })
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
            // La pastilla: nombre, ver todo, tema, aristas, planta y corte.
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
                        @Composable fun Boton(icono: androidx.compose.ui.graphics.vector.ImageVector, desc: Int, activo: Boolean = false, al: () -> Unit) {
                            IconButton(onClick = { al(); toques++ }, modifier = Modifier.size(38.dp)) {
                                Icon(icono, contentDescription = getString(desc), tint = if (activo) Color(0xFF5AA0FF) else blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                            }
                        }
                        Boton(Icons.Filled.FitScreen, com.forge.pixpin.R.string.plano_ver_todo) { verTodo() }
                        Boton(if (claro) Icons.Filled.DarkMode else Icons.Filled.LightMode, com.forge.pixpin.R.string.plano_tema) {
                            claro = !claro; prefs.edit().putBoolean("claro", claro).apply(); pintor?.claro = claro; pintar()
                        }
                        Boton(Icons.Filled.GridOn, com.forge.pixpin.R.string.modelo3d_aristas, aristas) { aristas = !aristas; pintor?.conAristas = aristas; pintar() }
                        Boton(Icons.Filled.Map, com.forge.pixpin.R.string.modelo3d_planta) { orbita = orbita?.enPlanta(); pintar() }
                        Boton(Icons.Filled.ContentCut, com.forge.pixpin.R.string.modelo3d_corte, cortando) { cortando = !cortando }
                    }
                }
            }
            // Abajo: lo elegido, y el corte con su altura.
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                elegido?.let { (tipo, n) ->
                    val t = TiposIfc.legible(tipo).let { if (n.isBlank()) it else "$it · $n" }
                    Text(t, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(horizontal = 16.dp, vertical = 9.dp))
                    Spacer(Modifier.size(8.dp))
                }
                if (cortando && m != null && alturaDelCorte != null) {
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC14182B)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val z = m.origen[2] + alturaDelCorte
                        Text(getString(com.forge.pixpin.R.string.modelo3d_altura_corte, String.format(java.util.Locale.ROOT, "%.2f", z) + if (m.metros == 1f) " m" else ""),
                            color = Color.White, modifier = Modifier.width(120.dp))
                        Slider(value = corte, onValueChange = { corte = it }, modifier = Modifier.width(200.dp),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF5AA0FF), activeTrackColor = Color(0xFF5AA0FF)))
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"

        fun abrir(c: Context, ruta: String, nombre: String?) {
            c.startActivity(
                Intent(c, Visor3DActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre)
                    .apply { if (c !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }
}
