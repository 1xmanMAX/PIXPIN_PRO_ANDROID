package com.forge.pixpin.capa

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.annotate.StrokeTouchReader
import com.forge.pixpin.capture.CaptureFlow
import com.forge.pixpin.capture.Export
import com.forge.pixpin.capture.ProjectionSession
import com.forge.pixpin.motor.DrawController
import com.forge.pixpin.motor.DrawFonts
import com.forge.pixpin.motor.DrawToolbar
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.Tool
import com.forge.pixpin.motor.Viewport
import com.forge.pixpin.motor.estiloAplicado
import com.forge.pixpin.pin.OverlayComposeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Una capa para escribir encima de la pantalla.**
 *
 * Lo de debajo sigue funcionando: el vídeo corre, la lista se desplaza, la
 * aplicación de otro no se entera de nada. Encima hay un lienzo del motor —el
 * mismo de los pines y del editor— donde se dibuja, se tapa y se señala.
 *
 * Son **dos ventanas y no una**, y esa es la decisión que sostiene todo lo
 * demás:
 *
 * - El **lienzo** ocupa la pantalla entera y se queda con los toques mientras
 *   se dibuja. Al pasar a «atravesar» se le pone `FLAG_NOT_TOUCHABLE` y el
 *   sistema entrega los toques a lo que haya debajo, con el dibujo todavía a la
 *   vista. Es lo que permite dibujar sobre una lista, atravesar, desplazarla y
 *   volver a dibujar sin perder nada.
 * - La **barra** va aparte y siempre toca. Metida dentro del lienzo, al
 *   atravesar se volvería intocable con él y no habría forma de volver.
 *
 * La escena se mide en **píxeles de pantalla**, sin desplazamiento ni zoom: aquí
 * no hay lienzo infinito que recorrer, hay una pantalla. Eso hace que dibujar,
 * pintar y hornear sobre la captura sean el mismo sistema de coordenadas y no
 * haya nada que convertir.
 */
class CapaPantalla(private val app: PixPinApp) {

    private val wm = app.getSystemService(WindowManager::class.java)

    private val controller = DrawController().apply { selectTool(Tool.FREEDRAW) }

    /** Ata el controlador —estado mutable corriente— a Compose. */
    private val tick = mutableIntStateOf(0)

    /** Con esto puesto, los toques van a la aplicación de debajo. */
    private val atravesar = mutableStateOf(false)

    /** Texto recién plantado que está pidiendo teclado. */
    private val escribiendo = mutableStateOf<String?>(null)

    private var lienzo: OverlayComposeWindow? = null
    private var barra: OverlayComposeWindow? = null
    private var lienzoLp: WindowManager.LayoutParams? = null
    private var barraLp: WindowManager.LayoutParams? = null

    /** Lo que mide el lienzo en pantalla; la escena se mide en lo mismo. */
    private var anchoLienzo = 0
    private var altoLienzo = 0

    val activa: Boolean get() = lienzo != null

    fun alternar() {
        if (activa) cerrar() else abrir()
    }

    // ---------------------------------------------------------------------
    // Las dos ventanas
    // ---------------------------------------------------------------------

    fun abrir() {
        if (activa) return
        val metrics = app.resources.displayMetrics
        anchoLienzo = metrics.widthPixels
        altoLienzo = metrics.heightPixels

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsDelLienzo(),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val ventana = OverlayComposeWindow(app, matchParent = true) { Lienzo() }
        ventana.setTouchHandler(lector())
        runCatching {
            wm.addView(ventana.view, lp)
            ventana.onAttached()
            lienzo = ventana
            lienzoLp = lp
        }.onFailure { return }

        abrirBarra()
    }

    fun cerrar() {
        escribiendo.value = null
        listOf(lienzo, barra).forEach { v ->
            v ?: return@forEach
            runCatching { wm.removeView(v.view) }
            v.onDetached()
        }
        lienzo = null
        barra = null
        lienzoLp = null
        barraLp = null
    }

    private fun abrirBarra() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsDeLaBarra(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (12 * app.resources.displayMetrics.density).toInt()
        }
        val ventana = OverlayComposeWindow(app) { Barra() }
        runCatching {
            wm.addView(ventana.view, lp)
            ventana.onAttached()
            barra = ventana
            barraLp = lp
        }
    }

    /**
     * Los flags del lienzo. El que importa es [FLAG_NOT_TOUCHABLE]: es toda la
     * diferencia entre «estoy dibujando» y «déjame usar el móvil».
     *
     * `LAYOUT_IN_SCREEN` y `LAYOUT_NO_LIMITS` son para poder dibujar también
     * sobre la barra de estado y la de gestos: si la capa se quedara dentro del
     * área segura, habría dos franjas donde el trazo se corta sin motivo
     * aparente.
     */
    private fun flagsDelLienzo(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (atravesar.value) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /**
     * La barra **solo coge el foco mientras se escribe un texto**.
     *
     * Con foco permanente le robaría el teclado a la aplicación de debajo y la
     * dejaría inservible, que es justo lo contrario de lo que hace esta capa.
     */
    private fun flagsDeLaBarra(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (escribiendo.value == null) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return flags
    }

    private fun aplicarFlags() {
        lienzoLp?.let { lp ->
            lp.flags = flagsDelLienzo()
            lienzo?.let { runCatching { wm.updateViewLayout(it.view, lp) } }
        }
        barraLp?.let { lp ->
            lp.flags = flagsDeLaBarra()
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            barra?.let { runCatching { wm.updateViewLayout(it.view, lp) } }
        }
    }

    /** Para que la capa no salga dentro de la propia captura de pantalla. */
    fun setVisible(visible: Boolean) {
        lienzo?.isContentVisible = visible
        barra?.isContentVisible = visible
    }

    // ---------------------------------------------------------------------
    // El dedo
    // ---------------------------------------------------------------------

    /**
     * **Se conserva `StrokeTouchReader`**, como en el pin y en la captura: trae
     * el rechazo de palma y las muestras históricas del lápiz, que con los
     * gestos de Compose se perderían.
     */
    private fun lector(): StrokeTouchReader {
        var ultimo = Pt(0.0, 0.0)
        return StrokeTouchReader(
            onBegin = { x, y, p ->
                ultimo = Pt(x.toDouble(), y.toDouble())
                controller.pointerDown(ultimo, p.toDouble())
                repintar()
            },
            onExtend = { x, y, p ->
                ultimo = Pt(x.toDouble(), y.toDouble())
                controller.pointerMove(ultimo, p.toDouble())
                repintar()
            },
            onFinish = {
                controller.pointerUp(ultimo)
                repintar()
                // Un texto recién plantado pide teclado; la barra se lo da.
                controller.pendingTextId?.let {
                    escribiendo.value = it
                    aplicarFlags()
                }
                // Y si el bote no encontró un hueco cerrado, se avisa: encima de
                // otra aplicación, un toque que no hace nada es indistinguible
                // de un toque que se ha perdido.
                if (controller.rellenoSinCerrar) {
                    controller.limpiarAvisoRelleno()
                    CaptureFlow.toast(app, R.string.relleno_sin_cerrar)
                }
            },
            onCancel = { controller.cancel(); repintar() },
            // El segundo dedo apoyado pide forma perfecta.
            onModifier = { activo ->
                controller.keepAspectRatio = activo
                repintar()
            }
        )
    }

    private fun repintar() {
        tick.intValue++
    }

    // ---------------------------------------------------------------------
    // Las tres acciones
    // ---------------------------------------------------------------------

    /**
     * Borra todo de un toque.
     *
     * Sin confirmación y **sin deshacer**: la capa es un borrador sobre la
     * pantalla de otro, no un documento. Lo que valga la pena guardar se copia
     * antes con el botón de al lado.
     */
    fun limpiar() {
        controller.load(Scene())
        repintar()
    }

    /**
     * Copia la pantalla **con lo dibujado encima**.
     *
     * El fotograma lo da la sesión de captura, así que hay que esconder los
     * overlays un instante: `MediaProjection` graba la pantalla de verdad y, si
     * no, se copiaría la capa dos veces —una fotografiada y otra pintada— y la
     * barra saldría en medio.
     */
    fun copiar() {
        CaptureFlow.conSesion(app) { contexto ->
            val overlays = app.overlayManager
            overlays.setOverlaysVisible(false)
            setVisible(false)
            val frame = try {
                // Margen para que el compositor deje de dibujar los overlays.
                delay(64)
                ProjectionSession.grab()
            } finally {
                setVisible(true)
                overlays.setOverlaysVisible(true)
            }
            if (frame == null) {
                CaptureFlow.toast(contexto, R.string.capture_error)
                return@conSesion
            }
            val horneado = withContext(Dispatchers.IO) { hornear(frame) }
            val ok = withContext(Dispatchers.IO) {
                Export.saveToGallery(contexto, horneado)
                Export.copyToClipboard(contexto, horneado)
            }
            if (!horneado.isRecycled) horneado.recycle()
            CaptureFlow.toast(
                contexto,
                if (ok) R.string.copied_image else R.string.capture_error
            )
        }
    }

    /**
     * Pinta la escena sobre el fotograma.
     *
     * El zoom sale de comparar anchos y no se da por hecho que sea 1: el
     * fotograma viene a la resolución del display y la ventana se mide en
     * píxeles de la pantalla, que **casi siempre** son lo mismo — casi.
     */
    private fun hornear(frame: Bitmap): Bitmap {
        val salida = frame.copy(Bitmap.Config.ARGB_8888, true)
        if (!frame.isRecycled && salida !== frame) frame.recycle()
        val zoom = if (anchoLienzo > 0) salida.width.toDouble() / anchoLienzo else 1.0
        Renderer(
            typefaces = DrawFonts.provider(app),
            // De aquí saca el mosaico sus píxeles: la propia pantalla.
            backdrop = salida
        ).renderScene(
            android.graphics.Canvas(salida),
            controller.scene.copy(viewport = Viewport(zoom = zoom)),
            salida.width.toDouble(),
            salida.height.toDouble()
        )
        return salida
    }

    // ---------------------------------------------------------------------
    // Lo que se ve
    // ---------------------------------------------------------------------

    @Composable
    private fun Lienzo() {
        val t by tick
        val contexto = androidx.compose.ui.platform.LocalContext.current
        val renderer = remember { Renderer(typefaces = DrawFonts.provider(contexto)) }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") t
            drawIntoCanvas { lienzoCompose ->
                renderer.renderScene(
                    lienzoCompose.nativeCanvas,
                    controller.scene.copy(viewport = Viewport()),
                    size.width.toDouble(),
                    size.height.toDouble()
                )
            }
        }
    }

    /**
     * La barra: pequeña a propósito.
     *
     * Arriba las cuatro acciones de la capa —atravesar, limpiar, copiar,
     * cerrar— y debajo las herramientas, las que se hayan elegido en los
     * ajustes. Al atravesar, las herramientas desaparecen: no se puede dibujar,
     * así que enseñarlas sería ofrecer algo que no responde.
     */
    @Composable
    private fun Barra() {
        val t by tick
        @Suppress("UNUSED_EXPRESSION") t
        val pasando by atravesar
        val ajustes by app.settings.settings.collectAsState(
            initial = com.forge.pixpin.data.Settings()
        )
        val permitidas = ajustes.capaToolSet

        LaunchedEffect(permitidas) {
            if (permitidas.isNotEmpty() && controller.tool !in permitidas) {
                com.forge.pixpin.motor.ALL_TOOLS.firstOrNull { it in permitidas }
                    ?.let { controller.selectTool(it); repintar() }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EditorDeTexto()

            Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(onClick = {
                        atravesar.value = !atravesar.value
                        aplicarFlags()
                        repintar()
                    }) {
                        Icon(
                            if (pasando) Icons.Filled.DoNotTouch else Icons.Filled.TouchApp,
                            contentDescription = app.getString(R.string.capa_atravesar),
                            tint = if (pasando) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { limpiar() }) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = app.getString(R.string.capa_limpiar)
                        )
                    }
                    IconButton(onClick = { copiar() }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = app.getString(R.string.capa_copiar)
                        )
                    }
                    IconButton(onClick = { cerrar() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = app.getString(R.string.cd_close)
                        )
                    }
                }
            }

            if (!pasando) {
                DrawToolbar(
                    tool = controller.tool,
                    onTool = { controller.selectTool(it); repintar() },
                    style = controller.scene.style,
                    onStyle = { nuevo ->
                        controller.changeStyle({ nuevo }, { estiloAplicado(it, nuevo) })
                        repintar()
                    },
                    canUndo = controller.canUndo,
                    onUndo = { controller.undo(); repintar() },
                    modifier = Modifier.padding(top = 4.dp),
                    escala = controller.scene.escala,
                    onQuitarEscala = { controller.clearScale(); repintar() },
                    modoReferencia = controller.modoReferencia,
                    onModoReferencia = { controller.modoReferencia = !controller.modoReferencia; repintar() },
                    referenciasVisibles = controller.referenciasVisibles,
                    onAlternarReferencias = { controller.alternarReferencias(); repintar() },
                    hayReferencias = controller.hayReferencias,
                    permitidas = permitidas,
                    mostrarFuente = false,
                    grupos = ajustes.capaGroupList
                )
            }
        }
    }

    /**
     * El teclado del texto recién plantado.
     *
     * Sale **dentro de la barra**, que es la ventana que puede coger el foco sin
     * dejar inservible lo de debajo. Mientras dura, la aplicación del fondo se
     * queda sin teclado; al cerrar, se lo devuelve.
     */
    @Composable
    private fun EditorDeTexto() {
        val id = escribiendo.value ?: return
        val contexto = androidx.compose.ui.platform.LocalContext.current
        // Con lo que ya hubiera: tocar un texto puesto lo abre para corregirlo.
        var texto by remember(id) { mutableStateOf(controller.scene.byId(id)?.text.orEmpty()) }

        fun cerrarTexto(guardar: Boolean) {
            val e = controller.scene.byId(id)
            if (guardar && texto.isNotBlank() && e != null) {
                val (ancho, alto) = DrawFonts.medirTexto(
                    contexto, texto, e.fontFamily, e.fontSize ?: 20.0
                )
                controller.updateText(id, texto, ancho, alto)
            } else {
                controller.setSelection(setOf(id))
                controller.deleteSelection()
            }
            controller.clearPendingText()
            escribiendo.value = null
            aplicarFlags()
            repintar()
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 6.dp,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    singleLine = false,
                    label = { Text(app.getString(R.string.draw_text_title)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { cerrarTexto(false) }) {
                        Text(app.getString(R.string.action_cancel))
                    }
                    TextButton(onClick = { cerrarTexto(true) }) {
                        Text(app.getString(R.string.action_done))
                    }
                }
            }
        }
    }
}
