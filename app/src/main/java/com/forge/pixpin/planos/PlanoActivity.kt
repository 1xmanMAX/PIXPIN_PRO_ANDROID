package com.forge.pixpin.planos

import com.forge.pixpin.ui.EditorAvanzadoDeVisor
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * - **Acotar**: con el dedo puesto sale una **mira arriba a la izquierda**, en diagonal, que se
 *   engancha al vértice, extremo o centro de círculo más cercano (los de [Enganches]); al
 *   levantar el dedo el punto queda ahí. Cada punto tiene un **asa** abajo a la derecha: se
 *   agarra y se arrastra, viendo el punto (9-oct-2026, el usuario: bajo el dedo no se sabía dónde
 *   caía ni se podía mover). Con dos dedos se mueve el plano. Las medidas se encadenan con su
 *   total (Σ), en las unidades del plano (`$INSUNITS`); ✓ termina la cadena, ↶ quita el último
 *   punto y la papelera borra las cotas.
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

    /** El marco que se está arrastrando, y su contador de repintar. */
    private var marcoVivo: ImprimirPlano.Marco? = null
    private var marcos2 by mutableIntStateOf(0)

    // ------------------------------------------------------------ anotar
    //
    // **Anotar encima del plano** (9-oct-2026, el usuario: «agregar texto en cualquier parte y
    // poder rayar como en el lienzo»). Con el motor de dibujo de siempre —el del lienzo y los
    // lectores: un solo motor en toda la app—, en una capa propia del plano guardada como la de un
    // Word anotado (`anot-<uid del mensaje>`, que viaja al sincronizar, también con el PC). Con
    // ella, las cotas y los marcos para imprimir, y su `.hoja`: ver [CapaDelPlano]. La y de la capa
    // baja; la del plano sube.

    /** La capa de anotar; null hasta que se lee el plano. */
    private var capa: com.forge.pixpin.motor.DrawController? = null
    private var idCapa = ""
    /** Cómo cae la capa en el plano (de su `.hoja`, o nuevo). */
    private var encaje = CapaDelPlano.Encaje(1.0, 0.0, 0.0)
    /** El de las unidades del fichero (su `.hoja`), y cómo pasar la capa de la memoria a él. */
    private var encajeDelFichero: CapaDelPlano.Encaje? = null
    private var capaEnMemoria: CapaDelPlano.EnMemoria? = null

    // **Lo que llega con el plano abierto** (14-oct-2026, la guía del PC «el visor de planos pisa lo
    // que llega del PC»): la capa se leía una vez y se reescribía en cada `onPause`, así que lo
    // anotado en el PC con el plano abierto aquí no se veía y, al salir, se borraba en los dos. Ahora
    // se relee lo que llega (si no hay nada a medias), no se guarda sin cambios, y al guardar se junta
    // con el disco si cambió desde que se leyó. Como `MotorDelPlano::al_dia`/`guardar_juntando` del PC.

    /** El fichero de la capa tal como se leyó o escribió aquí la última vez (la base para juntar). */
    @Volatile private var textoDeLaCapa: String? = null
    /** Fecha y tamaño de la capa y de su `.hoja` entonces: si cambian, llegó algo. */
    @Volatile private var selloDeLaCapa: Pair<Long, Long>? = null
    @Volatile private var selloDeLaHoja: Pair<Long, Long>? = null
    /** Lo que había en memoria al leer o guardar por última vez: si no cambió, no hay nada que guardar. */
    private var guardadaEnMemoria: List<com.forge.pixpin.motor.Element> = emptyList()

    private fun sello(f: File): Pair<Long, Long>? = if (f.isFile) f.lastModified() to f.length() else null

    private fun leerTexto(f: File): String? =
        runCatching { java.util.zip.GZIPInputStream(f.inputStream()).use { it.readBytes().decodeToString() } }.getOrNull()

    /** La capa leída del disco, sin tocar aún la pantalla. */
    private class CapaLeida(
        val delFichero: CapaDelPlano.Encaje, val encaje: CapaDelPlano.Encaje, val enMemoria: CapaDelPlano.EnMemoria,
        val texto: String?, val sello: Pair<Long, Long>?, val selloHoja: Pair<Long, Long>?
    )

    /** Lee la capa de [idCapa] (en un hilo de fondo): su `.hoja`, la escena pasada a la memoria y la base. */
    private fun leerCapaDelDisco(m: ModeloCad): CapaLeida {
        val ruta = File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this, idCapa))
        val hoja = com.forge.pixpin.sincro.AnotacionesDelAdjunto.hoja(filesDir, idCapa)
        // Los sellos antes que el contenido: si algo llega mientras se lee, la próxima mirada lo verá.
        val selloHoja = sello(hoja); val selloCapa = sello(ruta)
        // Dónde cae la capa: lo que diga su `.hoja` (escrita aquí o en el PC); si no hay,
        // la escala de las letras del plano. Ver [CapaDelPlano].
        val delFichero = com.forge.pixpin.sincro.AnotacionesDelAdjunto.leerMarco(hoja)
            ?.let { CapaDelPlano.encajeDe(it, m.caja) }
            ?: CapaDelPlano.Encaje(EscalaDeLaCapa.leerOPoner(ruta, m), 0.0, 0.0)
        // **En memoria, siempre la unidad de la letra** (como el PC): una capa escrita con
        // otra (la del PC de antes, la milésima del lado) engordaba el lápiz hasta tapar el
        // plano. El fichero sigue en la suya. Ver [CapaDelPlano.Unidades].
        val enMemoria = CapaDelPlano.Encaje(EscalaDeLaCapa.deLasLetras(m), 0.0, 0.0)
        val unidades = CapaDelPlano.unidadesEntre(enMemoria, delFichero)
        val texto = leerTexto(ruta)
        val escena = texto?.let { runCatching { com.forge.pixpin.motor.ExcalidrawJson.decodeFromString<com.forge.pixpin.motor.Scene>(it) }.getOrNull() }
            ?: com.forge.pixpin.motor.Scene()
        return CapaLeida(delFichero, if (unidades.iguales) delFichero else enMemoria, CapaDelPlano.aLaMemoria(escena, unidades), texto, selloCapa, selloHoja)
    }

    private fun ponerCapaLeida(l: CapaLeida) {
        encajeDelFichero = l.delFichero
        encaje = l.encaje
        capaEnMemoria = l.enMemoria
        textoDeLaCapa = l.texto
        selloDeLaCapa = l.sello
        selloDeLaHoja = l.selloHoja
    }
    /** Las cotas y los marcos de ahora, para guardarlos en la capa (los copia la pantalla). */
    @Volatile private var cotasParaGuardar: List<List<DoubleArray>> = emptyList()
    @Volatile private var marcosParaGuardar: List<ImprimirPlano.Marco> = emptyList()
    /** Sube cuando cambia lo anotado (para repintarlo sin recomponer). */
    private var cambiosEnLaCapa by mutableIntStateOf(0)

    /** La vista de la capa que cae justo donde mira la cámara del plano. */
    private fun vistaDeLaCapa(): com.forge.pixpin.motor.Viewport? = camara?.let { vistaDeLaCapa(it, ancho, alto) }

    /** La de la cámara [c] en una pantalla de [w] × [h]. */
    private fun vistaDeLaCapa(c: CamaraPlano, w: Int, h: Int): com.forge.pixpin.motor.Viewport {
        val e = encaje
        return com.forge.pixpin.motor.Viewport(
            scrollX = (w / 2.0 * c.px + e.ceroX - c.centroX) / e.u,
            scrollY = (h / 2.0 * c.px + c.centroY - e.ceroY) / e.u,
            zoom = e.u / c.px
        )
    }

    // ------------------------------------------------------------ encima del plano
    //
    // Lo anotado, las cotas hechas y los marcos los pone la tarjeta encima del plano en su mismo
    // fotograma: ver [EncimaDelPlano]. Aquí se decide qué y cuándo se vuelve a pintar.

    private val encima = EncimaDelPlano { vista?.requestRender() }
    /** Cómo se pinta lo de encima (lo copia la pantalla; lo lee [pedirEncima]). */
    @Volatile private var encimaClaro = false
    @Volatile private var encimaAnotando = false
    /** Anotando y moviendo el papel, el editor se esconde y lo pone la tarjeta (va con el plano). */
    private var dibujoOculto by mutableStateOf(false)
    private var ultimoPedidoMoviendo = 0L
    private val principal = android.os.Handler(android.os.Looper.getMainLooper())
    private val encimaNitida = Runnable { pedirEncima(true) }
    /** Los pintores del hilo de lo de encima (uno por tinta: con o sin el filtro de noche). */
    private val pintoresDelHilo = java.util.concurrent.ConcurrentHashMap<Boolean, com.forge.pixpin.motor.Renderer>()

    /**
     * Pide pintar otra vez lo de encima con la cámara de ahora: [nitido] justo la pantalla;
     * si no, el doble de campo a medio aumento (para ir moviéndose sin que falten bordes).
     */
    private fun pedirEncima(nitido: Boolean) {
        val c = camara ?: return
        if (ancho <= 0 || alto <= 0) return
        val m = modelo ?: return
        val escena = capa?.scene
        val claro = encimaClaro
        val anotando = encimaAnotando
        val cotas = cotasParaGuardar
        val hojas = marcosParaGuardar
        val hayCapa = escena != null && escena.elements.any { !it.isDeleted }
        if (!hayCapa && (anotando || (cotas.isEmpty() && hojas.isEmpty()))) { encima.vaciar(c); return }
        val densidad = resources.displayMetrics.density
        val k = if (nitido) 1f else 0.5f
        val cam = if (nitido) c else c.copy(px = c.px / k)
        val unidades = m.unidades
        encima.pedir(EncimaDelPlano.Pedido(cam, ancho, alto, k) { lienzo, cc, w, h, esc ->
            if (escena != null && hayCapa) {
                // Anotando, lo mismo que pinta el editor (con las rayas de las cotas tal cual); si no,
                // lo anotado sin lo del plano, que las cotas y los marcos van con sus medidas.
                val loQueSeVe = if (anotando) escena else CapaDelPlano.sinLoDelPlano(escena)
                pintoresDelHilo.getOrPut(!claro) {
                    com.forge.pixpin.motor.Renderer(
                        { f -> escena.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                        com.forge.pixpin.motor.DrawFonts.provider(applicationContext), dark = !claro,
                        // Contra el papel del plano, y solo de noche, como el PC. Ver [TintaDelPlano].
                        papelDelAnfitrion = TintaDelPlano.papel(claro)
                    )
                }.renderScene(lienzo, loQueSeVe.copy(viewport = vistaDeLaCapa(cc, w, h)), w.toDouble(), h.toDouble())
            }
            if (!anotando) dibujarCotas(lienzo, cc, w, h, densidad * esc, claro, cotas, hojas, unidades)
        })
    }

    /** Al revés: la cámara del plano que corresponde a la vista de la capa (anotando, manda ella). */
    private fun camaraDeLaCapa(v: com.forge.pixpin.motor.Viewport): CamaraPlano {
        val e = encaje
        val px = e.u / v.zoom.coerceAtLeast(1e-12)
        return CamaraPlano(ancho / 2.0 * px + e.ceroX - v.scrollX * e.u, v.scrollY * e.u + e.ceroY - alto / 2.0 * px, px)
    }

    /**
     * Guarda la capa con las cotas y los marcos de ahora dentro ([CapaDelPlano.con]) y su `.hoja`
     * al lado. Un plano sin nada hecho no deja archivos.
     */
    /** Las señas del modo de paso, si se abrió de otra aplicación sin guardarlo. */
    private var estadoDePaso: com.forge.pixpin.guardados.AbrirDePaso.Estado? = null

    private fun guardarCapa() {
        val laCapa = capa ?: return
        val m = modelo ?: return
        val id = idCapa.ifBlank { return }
        val e = encaje
        val nueva = CapaDelPlano.con(laCapa.scene, e, cotasParaGuardar, marcosParaGuardar, System.currentTimeMillis())
        // **Sin cambios no se guarda** (salir del plano no es un cambio): escribir lo leído al abrir
        // pisaba lo que hubiera llegado después.
        if (nueva.elements == guardadaEnMemoria) return
        // Solo si cambió algo: cargar una escena nueva en el motor le vacía el deshacer.
        if (nueva.elements != laCapa.scene.elements) laCapa.load(nueva)
        val enMemoria = laCapa.scene
        guardadaEnMemoria = enMemoria.elements
        // Al fichero, en sus unidades (lo que no se tocó, con sus números de siempre).
        val escena = capaEnMemoria?.alFichero(enMemoria) ?: enMemoria
        val delFichero = encajeDelFichero ?: e
        val base = textoDeLaCapa
        val visto = selloDeLaCapa
        val contexto = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val ruta = java.io.File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(contexto, id))
            if (escena.elements.isEmpty() && !ruta.isFile) return@launch
            runCatching {
                val hoja = com.forge.pixpin.sincro.AnotacionesDelAdjunto.hoja(contexto.filesDir, id)
                var aEscribir = escena
                var enSusUnidades = delFichero
                var juntado = false
                // **Si el disco cambió desde que se leyó, se junta** con la fusión de la sincronización.
                val enDisco = if (sello(ruta) != visto) leerTexto(ruta) else null
                if (enDisco != null && enDisco != base) {
                    // En las unidades del disco, si su `.hoja` es otra (la escribió el PC con las suyas).
                    val delDisco = com.forge.pixpin.sincro.AnotacionesDelAdjunto.leerMarco(hoja)?.let { CapaDelPlano.encajeDe(it, m.caja) } ?: delFichero
                    fun enDisco(s: com.forge.pixpin.motor.Scene) = if (delDisco == delFichero) s else CapaDelPlano.cambiarDeUnidades(s, delFichero, delDisco)
                    val mio = com.forge.pixpin.motor.ExcalidrawJson.encodeToString(enDisco(escena).let { it.copy(elements = com.forge.pixpin.motor.purgeDeleted(it.elements)) })
                    val baseEnDisco = base?.let { b ->
                        if (delDisco == delFichero) b
                        else runCatching { com.forge.pixpin.motor.ExcalidrawJson.encodeToString(enDisco(com.forge.pixpin.motor.ExcalidrawJson.decodeFromString<com.forge.pixpin.motor.Scene>(b))) }.getOrNull()
                    }
                    aEscribir = com.forge.pixpin.motor.ExcalidrawJson.decodeFromString(CapaDelPlano.juntarConElDisco(baseEnDisco, mio, enDisco))
                    enSusUnidades = delDisco
                    juntado = true
                }
                // La hoja antes que la capa: una capa sin su hoja al lado se leería con otra escala.
                val texto = CapaDelPlano.hojaDe(enSusUnidades, m.caja).aTexto()
                if (!hoja.isFile || hoja.readText() != texto) hoja.writeText(texto)
                com.forge.pixpin.motor.ExcalidrawStore.guardar(contexto, id, aEscribir)
                textoDeLaCapa = leerTexto(ruta)
                selloDeLaHoja = sello(hoja)
                // Lo junto se vuelve a leer en la pantalla (la mirada de cada 0,7 s lo ve como nuevo).
                selloDeLaCapa = if (juntado) null else sello(ruta)
            }
        }
    }

    /** Lo anotado pintado sobre el plano en [lienzo] (pantalla o papel) con la vista [v]. */
    private val pintoresDeLaCapa = HashMap<Boolean, com.forge.pixpin.motor.Renderer>()

    /** El pintor de lo anotado; [oscuro]: con el filtro de noche del motor (en papel, nunca). */
    private fun pintorDeLaCapa(oscuro: Boolean) = pintoresDeLaCapa.getOrPut(oscuro) {
        com.forge.pixpin.motor.Renderer(
            { f -> capa?.scene?.files?.get(f)?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
            com.forge.pixpin.motor.DrawFonts.provider(this), dark = oscuro,
            // **En papel, la tinta tal cual** como al imprimir en el PC (su papel blanco no es de
            // noche): ver [TintaDelPlano].
            papelDelAnfitrion = if (oscuro) TintaDelPlano.PAPEL_OSCURO else 0xFFFFFFFF.toInt()
        )
    }

    /** La mira de acotar (en la pantalla) mientras hay un dedo; dónde cae en el plano y si se enganchó. */
    private var mira: Offset? = null
    private var miraEnPlano: DoubleArray? = null
    private var miraEnganchada = false
    private var miraTipo = Rayas.Tipo.LIBRE
    /** Las rayas del plano para engancharse a ellas (se hacen aparte, con los [enganches]). */
    private var rayas: Rayas? = null

    /**
     * Pone la mira en [donde] (pantalla), enganchada si hay algo cerca: un punto, la perpendicular
     * desde [desde] (el punto anterior de la cota) o un punto de una raya. Devuelve el del plano.
     */
    private fun ponerMira(donde: Offset, densidad: Float, desde: DoubleArray?): DoubleArray? {
        val c = camara ?: return null
        val x = c.planoX(donde.x.toDouble(), ancho)
        val y = c.planoY(donde.y.toDouble(), alto)
        val radio = ENGANCHE * densidad * c.px
        val ajuste = rayas?.ajustar(x, y, radio, desde, enganches)
            ?: enganches?.cerca(x, y, radio)?.let { Rayas.Ajuste(it[0], it[1], Rayas.Tipo.PUNTO) }
            ?: Rayas.Ajuste(x, y, Rayas.Tipo.LIBRE)
        mira = donde
        miraTipo = ajuste.tipo
        miraEnganchada = ajuste.tipo != Rayas.Tipo.LIBRE
        miraEnPlano = doubleArrayOf(ajuste.x, ajuste.y)
        marcos++
        return miraEnPlano
    }

    /** El punto anterior al que se mueve o se pone: de donde sale la perpendicular. */
    private fun anterior(cadenas: List<List<DoubleArray>>, agarrado: Pair<Int, Int>?): DoubleArray? = when {
        agarrado == null -> cadenas.lastOrNull()?.lastOrNull()
        else -> cadenas.getOrNull(agarrado.first)?.let { c -> c.getOrNull(agarrado.second - 1) ?: c.getOrNull(agarrado.second + 1) }
    }

    private fun pantalla(p: DoubleArray): Offset {
        val c = camara ?: return Offset.Zero
        return Offset(c.pantallaX(p[0], ancho).toFloat(), c.pantallaY(p[1], alto).toFloat())
    }

    private fun cadenas(hechas: List<List<DoubleArray>>, puntos: List<DoubleArray>) = hechas + listOf(puntos)

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
        // **De paso** (27-oct-2026): abierto desde otra aplicación sin guardarlo. Ver [com.forge.pixpin.guardados.ModoDePaso].
        estadoDePaso = com.forge.pixpin.guardados.AbrirDePaso.vigilar(this)
        setContent { PixPinTheme { Pantalla(File(ruta), nombre) } }
    }

    override fun onResume() { super.onResume(); vista?.onResume() }
    override fun onPause() { vista?.onPause(); guardarCapa(); super.onPause() }
    override fun onDestroy() { principal.removeCallbacks(encimaNitida); super.onDestroy() }

    private fun pintar() {
        pintor?.camara = camara
        vista?.requestRender()
        marcos++
        // Lo de encima: al moverse, uno de campo ancho de vez en cuando (el de antes ya cae en su
        // sitio, solo le pueden faltar bordes); al pararse, uno nítido.
        val ahora = android.os.SystemClock.uptimeMillis()
        if (ahora - ultimoPedidoMoviendo > 90) { ultimoPedidoMoviendo = ahora; pedirEncima(false) }
        principal.removeCallbacks(encimaNitida)
        principal.postDelayed(encimaNitida, 160)
    }

    private fun verTodo() {
        val m = modelo ?: return
        if (ancho <= 0 || alto <= 0) return
        // Lo que importa, no la caja entera: un objeto suelto a kilómetros dejaba el plano diminuto.
        camara = CamaraPlano.encuadrar(m.cajaUtil, ancho, alto)
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
        // **Marcos para imprimir** (9-oct-2026): partes del plano, cada una una hoja.
        var marcando by remember { mutableStateOf(false) }
        // Las letras: el aviso de las que faltan y añadir las del usuario.
        var viendoLetras by remember { mutableStateOf(false) }
        var anadiendo by remember { mutableStateOf(false) }
        val elegirLetras = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            anadiendo = true
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { LectorDePlanos.anadirLetras(this@PlanoActivity, uris) }
                anadiendo = false
                android.widget.Toast.makeText(this@PlanoActivity, getString(com.forge.pixpin.R.string.plano_letras_anadidas, n), android.widget.Toast.LENGTH_LONG).show()
                // Con letras nuevas, el plano se vuelve a leer (su caché lleva la huella de las letras).
                if (n > 0) releerSinMoverLaVista()
            }
        }
        var anotando by remember { mutableStateOf(false) }
        // **Con lápiz, el dedo mueve el plano** (11-oct-2026); el botón «Dedo» lo apaga.
        var conLapiz by remember { mutableStateOf(false) }
        var vioLapiz by remember { mutableStateOf(false) }
        var lapizAutomatico by remember { mutableStateOf(true) }
        var tickDeAnotar by remember { mutableIntStateOf(0) }
        var hojas by remember { mutableStateOf<List<ImprimirPlano.Marco>>(emptyList()) }
        var preparandoLamina by remember { mutableStateOf(false) }
        /** «Imprimir todo» (mantener el marco del riel): el plano entero en una hoja, en vez de los marcos. */
        var imprimirElPlanoEntero by remember { mutableStateOf(false) }
        // **Exportar** (14-oct-2026): la hoja de compartir de siempre con los formatos del plano. Ver [ExportarPlano].
        var exportando by remember { mutableStateOf<com.forge.pixpin.ui.Compartible?>(null) }
        // Las cotas: cadenas hechas y la que se está poniendo (puntos del plano).
        var hechas by remember { mutableStateOf<List<List<DoubleArray>>>(emptyList()) }
        var puntos by remember { mutableStateOf<List<DoubleArray>>(emptyList()) }
        var enganchado by remember { mutableStateOf<DoubleArray?>(null) }
        val densidad = resources.displayMetrics.density

        LaunchedEffect(original) {
            val r = withContext(Dispatchers.IO) {
                // Las letras que viajan (14-oct-2026): fuera lo que llegó y no es una letra, y las
                // que ya había, a los otros aparatos. Antes de leer: lo que se quite no cuenta.
                LectorDePlanos.letrasAlAbrir(this@PlanoActivity)
                huellaAlAbrir = LectorDePlanos.huellaDeLetras(this@PlanoActivity)
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
                val leida = withContext(Dispatchers.IO) {
                    runCatching {
                        idCapa = com.forge.pixpin.ui.ExportarDocumentoAnotado.idDeLaCapa(original, com.forge.pixpin.ui.ExportarDocumentoAnotado.baseDe(this@PlanoActivity, original))
                        leerCapaDelDisco(m)
                    }.getOrNull()
                }
                capa = leida?.let { l ->
                    ponerCapaLeida(l)
                    com.forge.pixpin.motor.DrawController(l.enMemoria.escena).also { it.pedirLaMedida = false; it.selectTool(com.forge.pixpin.motor.Tool.FREEDRAW) }
                }
                guardadaEnMemoria = capa?.scene?.elements.orEmpty()
                // Las cotas y los marcos que había (de aquí o del PC).
                capa?.let { c -> CapaDelPlano.leer(c.scene, encaje).let { (cs, ms) -> hechas = cs; hojas = ms } }
                pintor = PintorDePlano(m, grosor = (densidad / 1.5f).coerceIn(1f, 3f)).also {
                    it.encima = encima
                    it.claro = claro
                    it.alCambiarTamano = { w, h -> runOnUiThread { alCambiarTamano(w, h) } }
                    it.pedirFotograma = { vista?.requestRender() }
                }
                estado = null
                listo = true
                // **Civil 3D guardado sin su dibujo** (PROXYGRAPHICS = 0): se dice, como en el PC.
                val sinDibujo = m.sinDibujar.filter { it.first.startsWith("sin dibujo: ") }.sumOf { it.second }
                if (sinDibujo > 0) android.widget.Toast.makeText(this@PlanoActivity, getString(com.forge.pixpin.R.string.plano_sin_dibujo_civil, sinDibujo), android.widget.Toast.LENGTH_LONG).show()
                // **Letras que el plano pide y no están**: se dice, y el botón «Aa» de arriba las añade.
                val faltan = m.sinDibujar.count { it.first.startsWith(LectorDePlanos.FUENTE_QUE_FALTA) }
                if (faltan > 0 && sinDibujo == 0) android.widget.Toast.makeText(this@PlanoActivity, getString(com.forge.pixpin.R.string.plano_faltan_letras, faltan), android.widget.Toast.LENGTH_LONG).show()
                // Los enganches de la cota, sin esperar a que se pidan (en un plano grande tardan).
                lifecycleScope.launch(Dispatchers.Default) {
                    enganches = runCatching { Enganches.de(m) }.getOrNull()
                    rayas = runCatching { Rayas.de(m) }.getOrNull()
                }
            }.onFailure { e ->
                estado = (e as? ModeloCad.NoSeLee)?.message ?: getString(com.forge.pixpin.R.string.plano_no)
            }
        }
        // **Llegan letras con el plano abierto** (de otro aparato, al sincronizar): la huella cambia y
        // el plano se vuelve a leer sin mover la vista, como al añadirlas con «Aa». Mirar la carpeta
        // es una lista de una docena de archivos cada pocos segundos.
        LaunchedEffect(listo) {
            if (!listo) return@LaunchedEffect
            while (true) {
                delay(3000)
                val ahora = withContext(Dispatchers.IO) { LectorDePlanos.huellaDeLetras(this@PlanoActivity) }
                val antes = huellaAlAbrir ?: continue
                if (ahora != antes) { releerSinMoverLaVista(); return@LaunchedEffect }
            }
        }
        LaunchedEffect(aLaVista, toques, acotando, marcando) {
            if (aLaVista && !acotando && !marcando) { delay(3500); aLaVista = false }
        }
        LaunchedEffect(Unit) {
            // Si la tarjeta no pudo, se dice (el hilo de la tarjeta no puede tocar la interfaz).
            while (true) {
                delay(500)
                pintor?.fallo?.let { estado = getString(com.forge.pixpin.R.string.plano_tarjeta, it); listo = false; return@LaunchedEffect }
            }
        }

        // Las cotas y los marcos se guardan en la capa al poco de cambiar (viajan con ella).
        LaunchedEffect(hechas, hojas) {
            cotasParaGuardar = hechas; marcosParaGuardar = hojas
            pedirEncima(true)
            if (listo) { delay(800); guardarCapa(); cambiosEnLaCapa++ }
        }
        // **Lo que llega con el plano abierto, a la vista**: cada 0,7 s se mira si la capa o su `.hoja`
        // cambiaron en el disco (la sincronización, o lo junto al guardar) y, si no se está anotando
        // ni hay nada sin guardar, se vuelve a leer. Con algo a medias, se juntará al guardar.
        LaunchedEffect(listo) {
            if (!listo) return@LaunchedEffect
            while (true) {
                delay(700)
                if (anotando) continue
                val m = modelo ?: continue
                val laCapa = capa ?: continue
                val id = idCapa.ifBlank { null } ?: continue
                val ruta = File(com.forge.pixpin.motor.ExcalidrawStore.rutaDe(this@PlanoActivity, id))
                val hoja = com.forge.pixpin.sincro.AnotacionesDelAdjunto.hoja(filesDir, id)
                val llego = withContext(Dispatchers.IO) { sello(ruta) != selloDeLaCapa || sello(hoja) != selloDeLaHoja }
                if (!llego) continue
                val aMedias = CapaDelPlano.con(laCapa.scene, encaje, hechas, hojas, System.currentTimeMillis()).elements != guardadaEnMemoria
                if (aMedias) continue
                val l = withContext(Dispatchers.IO) { runCatching { leerCapaDelDisco(m) }.getOrNull() } ?: continue
                ponerCapaLeida(l)
                laCapa.load(l.enMemoria.escena)
                guardadaEnMemoria = laCapa.scene.elements
                CapaDelPlano.leer(laCapa.scene, encaje).let { (cs, ms) -> hechas = cs; hojas = ms }
                cotasParaGuardar = hechas; marcosParaGuardar = hojas
                cambiosEnLaCapa++; marcos++
            }
        }
        // Lo de encima, otra vez cuando cambia lo que lleva o cómo se pinta.
        LaunchedEffect(listo, claro, anotando, cambiosEnLaCapa) {
            encimaClaro = claro
            encimaAnotando = anotando
            if (!anotando) { encima.visible = true; dibujoOculto = false; vista?.requestRender() }
            pedirEncima(true)
        }
        fun terminarCadena() {
            if (puntos.size >= 2) hechas = hechas + listOf(puntos)
            puntos = emptyList(); enganchado = null; marcos++
        }
        fun dejarDeAnotar() {
            anotando = false; capa?.let { c -> CapaDelPlano.leer(c.scene, encaje).let { (cs, ms) -> hechas = cs; hojas = ms } }; guardarCapa(); cambiosEnLaCapa++; marcos++
        }
        fun empezarAAnotar() {
            val laCapa = capa ?: return
            vistaDeLaCapa()?.let { laCapa.setViewport(it) }
            acotando = false; marcando = false; terminarCadena()
            anotando = true
        }
        // ---- **Los marcadores del plano** (24-oct-2026, como el PC): cada uno, la vista entera, en
        // `anot-<uid>.vistas.json` (`{"version":1,"plano":[…]}`). Ver [MarcadoresDelPlano]. ----
        var archivoDeVistas by remember { mutableStateOf<java.io.File?>(null) }
        var marcadores by remember { mutableStateOf(MarcadoresDelPlano.deTexto(null)) }
        var poniendoMarca by remember { mutableStateOf(false) }
        LaunchedEffect(original) {
            val f = withContext(Dispatchers.IO) {
                com.forge.pixpin.ui.ExportarDocumentoAnotado.baseDe(this@PlanoActivity, original)
                    ?.let { com.forge.pixpin.motor.MarcosDelDocumento.archivoDeVistas(filesDir, it) }
            }
            archivoDeVistas = f
            marcadores = MarcadoresDelPlano.deTexto(f?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(it) })
        }
        fun guardarMarcadores(nuevos: List<MarcadoresDelPlano.Marcador>) {
            marcadores = marcadores.con(nuevos)
            archivoDeVistas?.let { com.forge.pixpin.motor.MarcosDelDocumento.escribir(it, marcadores.aTexto(), vacio = false) }
        }
        BackHandler {
            when {
                poniendoMarca -> poniendoMarca = false
                acotando && puntos.isNotEmpty() -> terminarCadena()
                anotando -> dejarDeAnotar()
                acotando -> acotando = false
                marcando -> marcando = false
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
                val laCapa = capa
                if (anotando && laCapa != null) {
                    // **Anotando manda el lienzo**: dibuja con un dedo o el lápiz, encuadra con dos, y
                    // el plano le sigue fotograma a fotograma.
                    LaunchedEffect(Unit) {
                        var antes: com.forge.pixpin.motor.Viewport? = null
                        var quietoDesde = 0L
                        // Al entrar, lo de encima sigue puesto unos fotogramas: así no hay ninguno
                        // sin lo dibujado mientras aparece el editor.
                        var esconderEn = 3
                        while (true) {
                            val t = androidx.compose.runtime.withFrameNanos { it }
                            val v = laCapa.scene.viewport
                            if (v != antes) {
                                // **Moviendo el papel, lo dibujado lo pone la tarjeta** con el plano en
                                // el mismo fotograma; el editor, escondido (sigue cogiendo los dedos).
                                if (antes != null) { encima.visible = true; dibujoOculto = true; esconderEn = 0 }
                                antes = v; camara = camaraDeLaCapa(v); pintar()
                                quietoDesde = t
                                // Lo que se elige en el panel, a la escala de lo que se ve (PC 11bd1f6).
                                laCapa.escalaDeLoNuevo = 1.0 / v.zoom
                            } else if (dibujoOculto && t - quietoDesde > 200_000_000L) {
                                // Quieto: vuelve el editor y, unos fotogramas después, se quita lo de
                                // la tarjeta (un instante los dos a la vez, en el mismo sitio, mejor
                                // que uno sin nada).
                                dibujoOculto = false; esconderEn = 3
                            } else if (esconderEn > 0 && --esconderEn == 0 && !dibujoOculto) {
                                encima.visible = false; vista?.requestRender()
                            }
                        }
                    }
                    com.forge.pixpin.motor.DrawCanvas(
                        controller = laCapa,
                        // Solo el dibujo: la capa de alfa no toca los dedos.
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (dibujoOculto) 0f else 1f },
                        imageProvider = { f -> laCapa.scene.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                        dark = !claro,
                        // Anotando, la misma tinta que con el editor guardado: contra el plano, como el PC.
                        papelDelAnfitrion = TintaDelPlano.papel(claro),
                        onChange = { trazando -> if (!trazando) { tickDeAnotar++; cambiosEnLaCapa++ } },
                        modoLapiz = conLapiz,
                        onLapizDetectado = { vioLapiz = true; if (lapizAutomatico) conLapiz = true }
                    )
                } else
                // Gestos y cotas encima del plano.
                Box(
                    Modifier.fillMaxSize().pointerInput(acotando, marcando) {
                        var ultimoToque = 0L
                        var dondeUltimo = Offset.Zero
                        // **Al acotar, el punto va al lado del dedo** (9-oct-2026, el usuario: «no sé
                        // dónde lo estoy poniendo»): una mira arriba a la izquierda, en diagonal,
                        // que se ve mientras se arrastra y se suelta al levantar el dedo. Un punto
                        // ya puesto se agarra por su asa —abajo a la derecha, donde caería el
                        // dedo— y se mueve igual, viéndolo. Con dos dedos se mueve el plano.
                        val lado = Offset(-MIRA_X * densidad, -MIRA_Y * densidad)
                        awaitEachGesture {
                            val abajo = awaitFirstDown(requireUnconsumed = false)
                            var movido = false
                            var varios = false
                            var corrido = Offset.Zero
                            // Al acotar: qué punto se agarró (cadena, índice), o null si es uno nuevo.
                            var agarrado: Pair<Int, Int>? = null
                            // Al marcar: el marco va de donde se puso el dedo a donde está.
                            val inicioDelMarco = camara?.let { c -> doubleArrayOf(c.planoX(abajo.position.x.toDouble(), ancho), c.planoY(abajo.position.y.toDouble(), alto)) }
                            if (marcando) abajo.consume()
                            if (acotando) {
                                agarrado = Acotar.agarrar(cadenas(hechas, puntos), abajo.position + lado, ASA * densidad) { pantalla(it) }
                                ponerMira(abajo.position + lado, densidad, anterior(cadenas(hechas, puntos), agarrado))
                                abajo.consume()
                            }
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) {
                                    if (!varios && acotando) { mira = null; agarrado = null; marcos++ }
                                    varios = true
                                }
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (marcando && !varios) {
                                    val c = camara
                                    val dedo = ev.changes.firstOrNull { it.id == abajo.id } ?: ev.changes.first()
                                    if (c != null && inicioDelMarco != null) {
                                        marcoVivo = ImprimirPlano.Marco.entre(
                                            inicioDelMarco[0], inicioDelMarco[1],
                                            c.planoX(dedo.position.x.toDouble(), ancho), c.planoY(dedo.position.y.toDouble(), alto)
                                        )
                                        marcos2++
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                                if (marcando && varios && marcoVivo != null) { marcoVivo = null; marcos2++ }
                                if (acotando && !varios) {
                                    val dedo = ev.changes.firstOrNull { it.id == abajo.id } ?: ev.changes.first()
                                    val donde = ponerMira(dedo.position + lado, densidad, anterior(cadenas(hechas, puntos), agarrado))
                                    agarrado?.let { (k, i) ->
                                        if (donde != null) {
                                            if (k < hechas.size) hechas = Acotar.mover(hechas, k, i, donde)
                                            else puntos = Acotar.mover(listOf(puntos), 0, i, donde).first()
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
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
                            if (marcando) {
                                // Al soltar: el marco se queda si no es una raya (de lado, al menos 16 dp).
                                val vivo = marcoVivo
                                val c = camara
                                if (!varios && vivo != null && c != null && vivo.ancho / c.px > 16 * densidad && vivo.alto / c.px > 16 * densidad) {
                                    hojas = hojas + vivo
                                }
                                marcoVivo = null; marcos2++
                            } else if (acotando) {
                                // Al soltar: un punto nuevo donde quedó la mira (si no se agarró uno).
                                val donde = miraEnPlano
                                if (!varios && agarrado == null && donde != null) {
                                    puntos = puntos + listOf(donde)
                                    enganchado = if (miraEnganchada) donde else null
                                }
                                mira = null; miraEnPlano = null
                                marcos++
                            } else if (!movido && !varios) {
                                val ahora = System.currentTimeMillis()
                                val doble = ahora - ultimoToque < 300 && (abajo.position - dondeUltimo).getDistance() < 48 * densidad
                                ultimoToque = if (doble) 0L else ahora
                                dondeUltimo = abajo.position
                                if (doble) verTodo() else { aLaVista = !aLaVista; toques++ }
                            }
                        }
                    }
                ) {
                    Cotas(puntos, enganchado, claro, densidad, acotando, hechas)
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
            if (anotando) capa?.let { laCapa ->
                @Suppress("UNUSED_EXPRESSION") tickDeAnotar
                val ajustes by (application as com.forge.pixpin.PixPinApp).settings.settings.collectAsState(initial = com.forge.pixpin.data.Settings())
                com.forge.pixpin.ui.EscribirEnElLienzo(
                    laCapa, tickDeAnotar, enSitio = true, noche = !claro,
                    fondo = (if (claro) PintorDePlano.FONDO_CLARO else PintorDePlano.FONDO_OSCURO).let { Color(it[0], it[1], it[2], 0.85f) }
                ) { tickDeAnotar++; cambiosEnLaCapa++ }
                // **Siempre el editor avanzado** (25-oct-2026, el usuario: «nunca el simple»), el de
                // todos los visores. Ver [com.forge.pixpin.ui.EditorAvanzadoDeVisor].
                EditorAvanzadoDeVisor(
                    capa = laCapa, ajustes = ajustes,
                    permitidas = ajustes.planoToolSet, grupos = ajustes.planoGroupList,
                    noche = !claro, tick = tickDeAnotar,
                    alListo = { dejarDeAnotar() },
                    cambiado = { cambiosDeContenido -> tickDeAnotar++; if (cambiosDeContenido) cambiosEnLaCapa++ }
                ) {
                    if (vioLapiz) Text(
                        if (conLapiz) "Dedo: mover" else "Dedo: dibujar", color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .clickable { conLapiz = !conLapiz; lapizAutomatico = conLapiz }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
            if (!anotando) AnimatedVisibility(
                visible = aLaVista || estado != null,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp)
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0x8C14182B)).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val blanco = Color.White
                    com.forge.pixpin.ui.IconoDeFormato(nombre.takeIf { it.contains('.') } ?: original.name, Modifier.padding(end = 8.dp))
                    Text(
                        nombre.substringBeforeLast('.'), color = blanco, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp).padding(vertical = 10.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    if (listo) {
                        // «Ver todo» ya no va en la barra (parecía de pantalla completa): doble toque.
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
                        val faltanLetras = modelo?.sinDibujar?.any { it.first.startsWith(LectorDePlanos.FUENTE_QUE_FALTA) } == true
                        IconButton(onClick = { viendoLetras = true; toques++ }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Filled.FontDownload, contentDescription = getString(com.forge.pixpin.R.string.plano_letras),
                                tint = if (faltanLetras) Color(0xFFFFB020) else blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(onClick = { Visor3DActivity.abrir(this@PlanoActivity, original.absolutePath, nombre); toques++ }, modifier = Modifier.size(38.dp)) {
                            Text("3D", color = blanco.copy(alpha = 0.9f), fontWeight = FontWeight.Bold)
                        }
                        // Anotar y los marcos para imprimir van en el riel de la derecha (24-oct-2026, como el PC).
                        IconButton(onClick = { acotando = !acotando; if (acotando) marcando = false else terminarCadena(); toques++ }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Filled.Straighten, contentDescription = getString(com.forge.pixpin.R.string.plano_acotar),
                                tint = if (acotando) Color(0xFFFF8A00) else blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(onClick = {
                            val m = modelo ?: return@IconButton
                            guardarCapa()
                            exportando = ExportarPlano.compartible(
                                this@PlanoActivity,
                                ExportarPlano.Plano(original, nombre, m, capa?.scene, encaje, hechas + listOf(puntos).filter { it.size >= 2 }, hojas)
                            )
                            toques++
                        }, modifier = Modifier.size(38.dp)) {
                            Icon(com.forge.pixpin.ui.IconoDeCompartir, contentDescription = "Exportar", tint = blanco.copy(alpha = 0.9f), modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
            // **Abrir con…, Guardar y Compartir**, solo de paso. Ver [com.forge.pixpin.ui.BarraDePaso].
            if (!anotando) com.forge.pixpin.ui.BarraDePaso(
                estadoDePaso, aLaVista || estado != null, Modifier.align(Alignment.TopCenter),
                alCompartir = fun() {
                    val m = modelo ?: return
                    guardarCapa()
                    exportando = ExportarPlano.compartible(
                        this@PlanoActivity,
                        ExportarPlano.Plano(original, nombre, m, capa?.scene, encaje, hechas + listOf(puntos).filter { it.size >= 2 }, hojas)
                    )
                    toques++
                },
                antesDeGuardar = { guardarCapa() }
            )
            // **El riel de la derecha** (24-oct-2026): marcador, lápiz y marcos, como en todos los visores.
            // **Y se va con la pastilla** (27-oct-2026): al mover el plano, y vuelve al tocar; acotando se queda.
            // Ver [com.forge.pixpin.ui.RielALaVista].
            if (listo) com.forge.pixpin.ui.RielDelVisor(
                visible = com.forge.pixpin.ui.RielALaVista.visible(aLaVista, anotando, marcando, poniendoMarca, otraHerramienta = acotando),
                alUsar = { toques++ },
                poniendoMarca = poniendoMarca, anotando = anotando, marcando = marcando,
                onMarcador = { if (anotando) dejarDeAnotar(); marcando = false; poniendoMarca = !poniendoMarca },
                onLapiz = { if (anotando) dejarDeAnotar() else { poniendoMarca = false; empezarAAnotar() } },
                onMarco = {
                    if (anotando) dejarDeAnotar()
                    poniendoMarca = false
                    marcando = !marcando; if (marcando) { acotando = false; terminarCadena() }; toques++
                },
                marcas = if (anotando) 0 else marcadores.plano.size,
                emojiDe = { i -> marcadores.plano[i].emoji },
                imprimirMarcos = if (hojas.isEmpty()) null else ({ imprimirElPlanoEntero = false; preparandoLamina = true }),
                imprimirTodo = { imprimirElPlanoEntero = true; preparandoLamina = true },
                alElegirMarca = { i ->
                    val m = marcadores.plano.getOrNull(i)
                    val mo = modelo
                    if (m != null && mo != null && ancho > 0 && alto > 0) {
                        camara = MarcadoresDelPlano.camaraDe(m, mo.origenX, mo.origenY, ancho, alto)
                        pintar()
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
            if (poniendoMarca) com.forge.pixpin.ui.ElegirEmojiDeMarca(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
                onCerrar = { poniendoMarca = false }
            ) { emoji ->
                poniendoMarca = false
                val c = camara
                val mo = modelo
                if (c != null && mo != null && ancho > 0 && alto > 0) {
                    val id = MarcadoresDelPlano.idNuevo(System.currentTimeMillis(), marcadores.plano.map { it.id })
                    guardarMarcadores(marcadores.plano + MarcadoresDelPlano.deLaVista(c, mo.origenX, mo.origenY, ancho, alto, emoji, id))
                }
            }
            if (viendoLetras) {
                val faltan = remember(modelo) {
                    modelo?.sinDibujar.orEmpty().filter { it.first.startsWith(LectorDePlanos.FUENTE_QUE_FALTA) }.map { it.first.removePrefix(LectorDePlanos.FUENTE_QUE_FALTA) }
                }
                var mias by remember { mutableStateOf(LectorDePlanos.letrasDelUsuario(this@PlanoActivity)) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viendoLetras = false },
                    title = { Text(getString(com.forge.pixpin.R.string.plano_letras)) },
                    text = {
                        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            Text(
                                if (faltan.isEmpty()) getString(com.forge.pixpin.R.string.plano_letras_no_falta)
                                else getString(com.forge.pixpin.R.string.plano_letras_faltan)
                            )
                            // Con el archivo que hay que copiar al lado: las de Windows/Office no se llaman como
                            // su nombre (Arial Black es ariblk.ttf) y en la carpeta de AutoCAD no están.
                            if (faltan.isNotEmpty()) Text(
                                faltan.joinToString("\n") { f -> ARCHIVOS_DE_WINDOWS[f.lowercase()]?.let { "$f  ($it)" } ?: f },
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)
                            )
                            if (faltan.any { !it.endsWith(".shx", ignoreCase = true) }) Text(
                                getString(com.forge.pixpin.R.string.plano_letras_de_windows),
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(getString(com.forge.pixpin.R.string.plano_letras_donde), modifier = Modifier.padding(top = 8.dp))
                            if (mias.isNotEmpty()) {
                                Text(getString(com.forge.pixpin.R.string.plano_letras_mias, mias.size), modifier = Modifier.padding(top = 12.dp))
                                Text(mias.joinToString(", ") { it.name }, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                                TextButton(onClick = {
                                    mias = emptyList()
                                    viendoLetras = false
                                    // Con su marca de borrado: se van también de los otros aparatos.
                                    lifecycleScope.launch {
                                        withContext(Dispatchers.IO) { LectorDePlanos.quitarLetras(this@PlanoActivity) }
                                        releerSinMoverLaVista()
                                    }
                                }) { Text(getString(com.forge.pixpin.R.string.plano_letras_quitar)) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viendoLetras = false; elegirLetras.launch(arrayOf("*/*")) }, enabled = !anadiendo) {
                            Text(getString(com.forge.pixpin.R.string.plano_letras_anadir))
                        }
                    },
                    dismissButton = { TextButton(onClick = { viendoLetras = false }) { Text(getString(com.forge.pixpin.R.string.plano_listo)) } }
                )
            }
            // Marcando: la barra de los marcos de todos los visores (salir: el marco del riel otra vez;
            // imprimir: mantenerlo).
            if (marcando) com.forge.pixpin.ui.BarraDeMarcos(
                cuantos = hojas.size, alIr = null,
                alQuitarUltimo = { hojas = hojas.dropLast(1); marcos2++ },
                alBorrar = { hojas = emptyList(); marcos2++ },
                modifier = Modifier.align(Alignment.BottomStart),
                pista = if (hojas.isEmpty()) getString(com.forge.pixpin.R.string.plano_pista_marcos) else "Arrastra para otro"
            )
            if (preparandoLamina) DatosDeLaLamina(nombre.substringBeforeLast('.'), onCerrar = { preparandoLamina = false }) { lamina ->
                preparandoLamina = false
                val m = modelo
                guardarCapa()
                val escena = capa?.scene?.let(CapaDelPlano::sinLoDelPlano)?.takeIf { s -> s.elements.any { !it.isDeleted } }
                val enc = encaje
                // Lo anotado va en la hoja, con el motor de siempre (en vectores, sin modo noche).
                val tinta: ((android.graphics.Canvas, Double, Double, Double, Float, Float) -> Unit)? = escena?.let { e ->
                    { lienzo, x0, y1, s, w, h ->
                        pintorDeLaCapa(false).renderScene(
                            lienzo, e.copy(viewport = com.forge.pixpin.motor.Viewport(scrollX = (enc.ceroX - x0) / enc.u, scrollY = (y1 - enc.ceroY) / enc.u, zoom = enc.u * s)),
                            w.toDouble(), h.toDouble()
                        )
                    }
                }
                val todo = imprimirElPlanoEntero
                imprimirElPlanoEntero = false
                val cuales = if (todo && m != null) m.cajaUtil.let { c -> listOf(ImprimirPlano.Marco.entre(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble())) } else hojas
                if (m != null && cuales.isNotEmpty()) ImprimirPlano.imprimir(
                    this@PlanoActivity, m, cuales, hechas + listOf(puntos).filter { it.size >= 2 }, nombre.substringBeforeLast('.'), lamina, tinta
                )
            }
            // De paso, con «Por Wi-Fi a mis aparatos». Ver [com.forge.pixpin.guardados.AbrirDePaso.conWifi].
            exportando?.let { c -> com.forge.pixpin.ui.HojaDeCompartir(remember(c) { com.forge.pixpin.guardados.AbrirDePaso.conWifi(this@PlanoActivity, c, estadoDePaso) }) { exportando = null } }
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

    /**
     * **Los datos del membrete** antes de imprimir: con o sin lámina, y lo que va escrito. El
     * proyecto, quién dibujó y las dos opciones se recuerdan para la próxima; el título empieza
     * con el nombre del plano y la fecha con la de hoy.
     */
    @Composable
    private fun DatosDeLaLamina(nombre: String, onCerrar: () -> Unit, onImprimir: (ImprimirPlano.Lamina) -> Unit) {
        var conMembrete by remember { mutableStateOf(prefs.getBoolean("lamina:membrete", true)) }
        var escalaNormal by remember { mutableStateOf(prefs.getBoolean("lamina:escala", true)) }
        var proyecto by remember { mutableStateOf(prefs.getString("lamina:proyecto", "").orEmpty()) }
        var titulo by remember { mutableStateOf(nombre) }
        var autor by remember { mutableStateOf(prefs.getString("lamina:autor", "").orEmpty()) }
        var fecha by remember { mutableStateOf(java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())) }
        val conUnidades = ImprimirPlano.mmPorUnidad(modelo?.unidades ?: 0) != null
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onCerrar,
            title = { Text(getString(com.forge.pixpin.R.string.plano_lamina)) },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(getString(com.forge.pixpin.R.string.plano_con_membrete), Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = conMembrete, onCheckedChange = { conMembrete = it })
                    }
                    if (conMembrete) {
                        androidx.compose.material3.OutlinedTextField(proyecto, { proyecto = it.take(120) }, label = { Text(getString(com.forge.pixpin.R.string.plano_proyecto)) }, singleLine = true)
                        androidx.compose.material3.OutlinedTextField(titulo, { titulo = it.take(160) }, label = { Text(getString(com.forge.pixpin.R.string.plano_titulo_lamina)) }, singleLine = true)
                        androidx.compose.material3.OutlinedTextField(autor, { autor = it.take(80) }, label = { Text(getString(com.forge.pixpin.R.string.plano_autor)) }, singleLine = true)
                        androidx.compose.material3.OutlinedTextField(fecha, { fecha = it.take(30) }, label = { Text(getString(com.forge.pixpin.R.string.plano_fecha)) }, singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(getString(com.forge.pixpin.R.string.plano_escala_normal))
                            Text(
                                getString(if (conUnidades) com.forge.pixpin.R.string.plano_escala_normal_si else com.forge.pixpin.R.string.plano_escala_normal_no),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.Switch(checked = escalaNormal && conUnidades, enabled = conUnidades, onCheckedChange = { escalaNormal = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("lamina:membrete", conMembrete).putBoolean("lamina:escala", escalaNormal)
                        .putString("lamina:proyecto", proyecto.trim()).putString("lamina:autor", autor.trim()).apply()
                    onImprimir(ImprimirPlano.Lamina(conMembrete, proyecto.trim(), titulo.trim(), autor.trim(), fecha.trim(), escalaNormal && conUnidades))
                }) { Text(getString(com.forge.pixpin.R.string.plano_imprimir)) }
            },
            dismissButton = { TextButton(onClick = onCerrar) { Text(getString(android.R.string.cancel)) } }
        )
    }

    private fun alCambiarTamano(w: Int, h: Int) {
        val primeraVez = camara == null || ancho <= 0
        ancho = w; alto = h
        // Al girar el teléfono se queda mirando lo mismo, con la misma escala. Al volver a leerlo
        // (letras nuevas), mirando lo que se miraba.
        val volver = if (primeraVez) vistaAlReleer?.takeIf { it.first == intent?.getStringExtra(EXTRA_RUTA) }?.second else null
        if (volver != null) { vistaAlReleer = null; camara = volver; pintar() }
        else if (primeraVez) verTodo() else pintar()
    }

    /** La huella de las letras con la que se leyó el plano ([LectorDePlanos.huellaDeLetras]). */
    @Volatile private var huellaAlAbrir: String? = null

    /**
     * **Volver a leer el plano sin mover la vista**: con letras nuevas (añadidas con «Aa», quitadas
     * o llegadas de otro aparato) lo leído en caché ya no vale, porque lleva la huella de las letras.
     * Lo anotado se guarda antes; la cámara espera en [vistaAlReleer] a la pantalla nueva.
     */
    private fun releerSinMoverLaVista() {
        if (isFinishing || isDestroyed) return
        guardarCapa()
        val ruta = intent?.getStringExtra(EXTRA_RUTA)
        vistaAlReleer = camara?.let { c -> ruta?.let { it to c } }
        recreate()
    }

    /**
     * Las cotas, dibujadas por encima del plano (en el PC van a la tarjeta como un plano más; aquí
     * son pocas rayas y el lienzo de Android basta). Naranja, con su medida en una pastilla; el
     * total (Σ) al final de una cadena de más de un tramo; un cuadro verde en el último enganche.
     */
    @Composable
    private fun Cotas(puntos: List<DoubleArray>, enganchado: DoubleArray?, claro: Boolean, densidad: Float, acotando: Boolean, hechas: List<List<DoubleArray>>) {
        val m = modelo ?: return
        val naranja = android.graphics.Color.rgb(0xFF, 0x8A, 0x00)
        val raya = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val letra = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val pildora = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val asa = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val leer = marcos // repinta al mover, sin recomponer
            @Suppress("UNUSED_VARIABLE") val leer2 = marcos2
            val c = camara ?: return@Canvas
            val lienzo = drawContext.canvas.nativeCanvas
            // **Aquí solo lo vivo**: la cadena que se está poniendo, el marco que se arrastra, la mira
            // y las asas. Las cotas hechas y los marcos puestos van con lo anotado, en el fotograma
            // del plano ([pedirEncima]): pintados aquí, se quedaban atrás al moverlo.
            dibujarCotas(lienzo, c, ancho, alto, densidad, claro, listOf(puntos), emptyList(), m.unidades, marcoVivo)
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
            // La mira: el tramo vivo desde el último punto, una cruz con su círculo (verde si se
            // enganchó) y una rayita tenue hasta el dedo, para saber de dónde sale.
            val laMira = mira
            val enPlano = miraEnPlano
            if (acotando && laMira != null && enPlano != null) {
                val mx = sx(enPlano); val my = sy(enPlano)
                if (puntos.isNotEmpty()) {
                    val u = puntos.last()
                    raya.color = naranja; raya.strokeWidth = 2f * densidad; raya.alpha = 170
                    lienzo.drawLine(sx(u), sy(u), mx, my, raya)
                    raya.alpha = 255
                    val d = hypot(enPlano[0] - u[0], enPlano[1] - u[1])
                    etiqueta(Medidas.medida(d, m.unidades), mx, my - 30 * densidad)
                }
                val verde = android.graphics.Color.rgb(0x30, 0xD1, 0x58)
                raya.color = if (miraEnganchada) verde else naranja
                raya.strokeWidth = 1.6f * densidad
                val r = 13 * densidad
                lienzo.drawCircle(mx, my, r, raya)
                // Qué enganchó, como en AutoCAD: cuadro = punto, ⊥ = perpendicular, ⨯ = en la raya.
                val q = 6 * densidad
                when (miraTipo) {
                    Rayas.Tipo.PUNTO -> lienzo.drawRect(mx - q, my - q, mx + q, my + q, raya)
                    Rayas.Tipo.PERPENDICULAR -> {
                        lienzo.drawLine(mx - q, my + q, mx + q, my + q, raya)
                        lienzo.drawLine(mx - q, my + q, mx - q, my - q, raya)
                        lienzo.drawLine(mx - q, my, mx, my, raya); lienzo.drawLine(mx, my, mx, my + q, raya)
                        etiqueta("90°", mx + r + 18 * densidad, my - r)
                    }
                    Rayas.Tipo.EN_LA_RAYA -> { lienzo.drawLine(mx - q, my - q, mx + q, my + q, raya); lienzo.drawLine(mx - q, my + q, mx + q, my - q, raya) }
                    Rayas.Tipo.LIBRE -> {}
                }
                lienzo.drawLine(mx - r * 1.7f, my, mx - r * 0.35f, my, raya)
                lienzo.drawLine(mx + r * 0.35f, my, mx + r * 1.7f, my, raya)
                lienzo.drawLine(mx, my - r * 1.7f, mx, my - r * 0.35f, raya)
                lienzo.drawLine(mx, my + r * 0.35f, mx, my + r * 1.7f, raya)
                raya.alpha = 90; raya.strokeWidth = 1f * densidad
                lienzo.drawLine(mx + r, my + r, laMira.x + MIRA_X * densidad - r * 0.5f, laMira.y + MIRA_Y * densidad - r * 0.5f, raya)
                raya.alpha = 255
            }
            // Las asas de los puntos, abajo a la derecha: por ahí se agarran sin taparlos.
            if (acotando) {
                asa.color = naranja; asa.alpha = 110
                for (cadena in hechas + listOf(puntos)) for (p in cadena) {
                    val ax = sx(p) + MIRA_X * densidad; val ay = sy(p) + MIRA_Y * densidad
                    lienzo.drawCircle(ax, ay, 9 * densidad, asa)
                }
                asa.alpha = 255
            }
            enganchado?.let { g ->
                val s = 7 * densidad
                raya.color = android.graphics.Color.rgb(0x30, 0xD1, 0x58); raya.strokeWidth = 1.8f * densidad
                lienzo.drawRect(sx(g) - s, sy(g) - s, sx(g) + s, sy(g) + s, raya)
            }
        }
    }

    /**
     * Las cotas ([cadenas]: naranja, su medida en una pastilla y el total Σ) y los marcos para
     * imprimir ([hojas], azules a trazos con su número; [vivo], el que se arrastra, más tenue) en
     * [lienzo], vistos con [c] en una pantalla de [w] × [h]. Sin estado de la pantalla: lo usan el
     * hilo que pinta lo de encima del plano y lo vivo de [Cotas].
     */
    private fun dibujarCotas(
        lienzo: android.graphics.Canvas, c: CamaraPlano, w: Int, h: Int, densidad: Float, claro: Boolean,
        cadenas: List<List<DoubleArray>>, hojas: List<ImprimirPlano.Marco>, unidades: Int, vivo: ImprimirPlano.Marco? = null
    ) {
        val naranja = android.graphics.Color.rgb(0xFF, 0x8A, 0x00)
        val raya = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val letra = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val pildora = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        raya.color = naranja; raya.strokeWidth = 2f * densidad; raya.style = android.graphics.Paint.Style.STROKE
        letra.textSize = 12.5f * densidad; letra.color = if (claro) android.graphics.Color.WHITE else android.graphics.Color.rgb(0x1C, 0x1C, 0x1E)
        pildora.color = if (claro) 0xEE1C1C1E.toInt() else 0xEEF5F5F7.toInt()
        fun sx(p: DoubleArray) = c.pantallaX(p[0], w).toFloat()
        fun sy(p: DoubleArray) = c.pantallaY(p[1], h).toFloat()
        fun etiqueta(t: String, x: Float, y: Float) {
            val ancho = letra.measureText(t)
            val r = android.graphics.RectF(x - ancho / 2 - 6 * densidad, y - letra.textSize * 0.95f, x + ancho / 2 + 6 * densidad, y + letra.textSize * 0.6f)
            lienzo.drawRoundRect(r, 6 * densidad, 6 * densidad, pildora)
            lienzo.drawText(t, x - ancho / 2, y + letra.textSize * 0.25f, letra)
        }
        for (cadena in cadenas) {
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
                etiqueta(Medidas.medida(d, unidades), mx + nx * lado * 16 * densidad, my + ny * lado * 16 * densidad)
            }
            raya.style = android.graphics.Paint.Style.FILL
            for (p in cadena) lienzo.drawRect(sx(p) - 3 * densidad, sy(p) - 3 * densidad, sx(p) + 3 * densidad, sy(p) + 3 * densidad, raya)
            raya.style = android.graphics.Paint.Style.STROKE
            if (cadena.size > 2) {
                val u = cadena.last()
                etiqueta("Σ " + Medidas.medida(total, unidades), sx(u) + 40 * densidad, sy(u) + 24 * densidad)
            }
        }
        // Los marcos para imprimir: azules, a trazos, con su número (la hoja que serán).
        val todos = hojas + listOfNotNull(vivo)
        if (todos.isEmpty()) return
        val azul = android.graphics.Color.rgb(0x3D, 0x8B, 0xFF)
        val marco = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val numero = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        marco.color = azul; marco.style = android.graphics.Paint.Style.STROKE; marco.strokeWidth = 1.8f * densidad
        marco.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * densidad, 5 * densidad), 0f)
        todos.forEachIndexed { i, mc ->
            val l = c.pantallaX(mc.x0, w).toFloat(); val r = c.pantallaX(mc.x1, w).toFloat()
            val t = c.pantallaY(mc.y1, h).toFloat(); val b = c.pantallaY(mc.y0, h).toFloat()
            marco.alpha = if (i < hojas.size) 255 else 170
            lienzo.drawRect(l, t, r, b, marco)
            if (i < hojas.size) {
                numero.color = azul; numero.textSize = 14 * densidad; numero.isFakeBoldText = true
                lienzo.drawText("${i + 1}", l + 6 * densidad, t + 18 * densidad, numero)
            }
        }
    }

    companion object {
        /** La vista de un plano que se está volviendo a leer (su ruta y su cámara). Ver [releerSinMoverLaVista]. */
        @Volatile private var vistaAlReleer: Pair<String, CamaraPlano>? = null

        /**
         * El archivo de las letras de Windows y Office que más piden los planos (13-oct-2026: el
         * usuario añadió todas las SHX de AutoCAD y el plano seguía pidiendo Agency FB, Arial Black,
         * Bodoni MT, Forte, Freestyle Script y Stencil, que son TrueType de C:\Windows\Fonts).
         */
        val ARCHIVOS_DE_WINDOWS = mapOf(
            "agency fb" to "AGENCYR.TTF", "arial black" to "ariblk.ttf", "bodoni mt" to "BOD_R.TTF",
            "forte" to "FORTE.TTF", "freestyle script" to "FREESCPT.TTF", "stencil" to "STENCIL.TTF",
            "arial" to "arial.ttf", "arial narrow" to "ARIALN.TTF", "calibri" to "calibri.ttf",
            "cambria" to "cambria.ttc", "century gothic" to "GOTHIC.TTF", "comic sans ms" to "comic.ttf",
            "courier new" to "cour.ttf", "georgia" to "georgia.ttf", "impact" to "impact.ttf",
            "segoe ui" to "segoeui.ttf", "tahoma" to "tahoma.ttf", "times new roman" to "times.ttf",
            "trebuchet ms" to "trebuc.ttf", "verdana" to "verdana.ttf", "swis721 bt" to "swiss.ttf",
            "isocpeur" to "isocpeur.ttf", "romantic" to "romantic.ttf", "simplex" to "simplex.ttf",
            "gill sans mt" to "GIL_____.TTF", "franklin gothic medium" to "framd.ttf", "lucida console" to "lucon.ttf",
            "book antiqua" to "BKANT.TTF", "garamond" to "GARA.TTF", "haettenschweiler" to "HATTEN.TTF",
            "showcard gothic" to "SHOWG.TTF", "broadway" to "BROADW.TTF", "algerian" to "ALGER.TTF"
        )
        private const val EXTRA_RUTA = "ruta"
        /** Dónde va la mira respecto al dedo (dp): arriba y a la izquierda. */
        const val MIRA_X = 34f
        const val MIRA_Y = 52f
        /** Lo cerca que hay que poner la mira de un punto para agarrarlo (dp). */
        const val ASA = 26f
        /** Lo cerca que engancha la cota a un vértice (dp). */
        const val ENGANCHE = 20f
        private const val EXTRA_NOMBRE = "nombre"

        fun esPlano(nombre: String?): Boolean = LectorDePlanos.esPlano(nombre)

        fun abrir(c: Context, ruta: String, nombre: String?) {
            // Desde fuera y sin la aplicación abierta, con la lista de chats debajo. Ver [com.forge.pixpin.Volver.abrirEnLaApp].
            com.forge.pixpin.Volver.abrirEnLaApp(c, Intent(c, PlanoActivity::class.java).putExtra(EXTRA_RUTA, ruta).putExtra(EXTRA_NOMBRE, nombre))
        }
    }
}
