package com.forge.pixpin.pin

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.forge.pixpin.R
import com.forge.pixpin.annotate.AnnotationCanvas
import com.forge.pixpin.annotate.AnnotationController
import com.forge.pixpin.annotate.AnnotationType
import com.forge.pixpin.annotate.Pt
import com.forge.pixpin.annotate.StrokeTouchReader
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.floating.FloatingBallController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Un pin = una ventana overlay independiente, sin límites de pantalla
 * (puede cruzar los bordes y cubrir la barra de estado).
 *
 * Todo por gestos, sin menús:
 * - arrastrar: mover (soltarlo sobre la bola lo minimiza en burbuja)
 * - pellizcar: escalar · 2 dedos arriba/abajo: opacidad
 * - doble toque: minimizar/restaurar en burbuja
 * - toque: copiar (texto/color) o abrir (archivo)
 * - pulsación larga: barra mínima de 4 acciones
 *
 * Posición y opacidad viven en los LayoutParams (fuera del estado de Compose):
 * moverlo o atenuarlo no recompone el contenido.
 */
class PinWindowController(
    private val context: Context,
    initialState: PinState,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPinChanged(controller: PinWindowController)
        fun onPinClosed(controller: PinWindowController)
        fun onPinDestroyed(controller: PinWindowController)
        fun onPinSaveRequested(controller: PinWindowController)

        /** Copiar al portapapeles, horneando lo dibujado encima. */
        fun onPinCopyRequested(controller: PinWindowController) {}

        /** Se acaba de minimizar: el gestor decide dónde aparcar la burbuja. */
        fun onPinMinimized(controller: PinWindowController)

        /** El usuario marcó/desmarcó el pin como guardado. */
        fun onPinToggleSave(controller: PinWindowController)

        /**
         * Arrastre en curso. El gestor es quien sabe qué pines forman grupo, así
         * que es él quien arrastra a los compañeros: el pin solo cuenta cuánto
         * se ha movido desde que empezó el gesto.
         */
        fun onPinDragStarted(controller: PinWindowController) {}
        fun onPinDragged(controller: PinWindowController, dx: Int, dy: Int) {}

        /** Se ha minimizado o restaurado: un grupo lo hace entero, en una burbuja. */
        fun onPinMinimizeChanged(controller: PinWindowController, minimized: Boolean) {}
    }

    private val wm = context.getSystemService(WindowManager::class.java)!!
    private var window: OverlayComposeWindow? = null
    private var lp: WindowManager.LayoutParams? = null

    private var actionBar: OverlayComposeWindow? = null
    private var annotateBar: OverlayComposeWindow? = null

    private var touchHandler: OverlayTouchHandler? = null

    /** Esquina agarrable del cuadro de texto, en coordenadas de la ventana. */
    private var resizeHandle: android.graphics.Rect? = null

    private val pin = mutableStateOf(initialState)
    private val scale = mutableFloatStateOf(initialState.scale)
    private val minimized = mutableStateOf(initialState.minimized)

    /** Imagen del pin, compartida entre el dibujado y el conversor de coordenadas. */
    private val bitmapState = mutableStateOf<Bitmap?>(null)

    /**
     * Anotaciones del pin. Vive siempre (para dibujar lo ya anotado); solo
     * recibe gestos mientras [annotating] está activo.
     */
    private val annotator = AnnotationController().apply {
        annotations.addAll(initialState.annotations)
    }
    private val annotating = mutableStateOf(false)

    /** Con qué se entró al modo anotación, para saber si hay algo nuevo que guardar. */
    private var annotationsAtEnter: List<com.forge.pixpin.annotate.Annotation> = emptyList()

    /**
     * La transparencia se aplica al CONTENIDO, no a la ventana. Con
     * LayoutParams.alpha muy bajo el sistema deja de entregar toques a la
     * ventana y el pin se quedaba intocable; atenuando solo lo que se dibuja,
     * la zona táctil sigue intacta por transparente que se vea.
     */
    private val contentAlpha = mutableFloatStateOf(initialState.alpha)

    // Anclas del gesto en curso
    private var dragStartX = 0
    private var dragStartY = 0
    private var scaleStart = 1f
    private var alphaStart = 1f
    private var restoreX = initialState.x
    private var restoreY = initialState.y
    private var preMinimizeX = initialState.x
    private var preMinimizeY = initialState.y

    /** Dónde estaba el pin justo antes de encogerse en burbuja. */
    val positionBeforeMinimize: Pair<Int, Int> get() = preMinimizeX to preMinimizeY

    /** Posición actual de la ventana. */
    val position: Pair<Int, Int>
        get() = (lp?.x ?: pin.value.x) to (lp?.y ?: pin.value.y)
    private var scaleStartW = 1
    private var scaleStartH = 1
    private var focusRelX = 0.5f
    private var focusRelY = 0.5f

    /** Tope del gesto en curso. */
    private var zoomMax = PinZoom.ABSOLUTE_MAX_SCALE

    /**
     * Tamaño del contenido a escala 1, en px. Solo se conoce en los pines de
     * imagen, y es lo que permite darle a la ventana un **tamaño explícito**.
     *
     * Importa más de lo que parece: una ventana WRAP_CONTENT no puede medir más
     * que la pantalla, así que al llegar ahí la imagen dejaba de crecer mientras
     * la escala seguía subiendo — y lo dibujado encima, que se calculaba con el
     * tamaño teórico, sí crecía y se despegaba de la foto. Con tamaño explícito
     * la ventana crece de verdad y ambos van siempre a la par.
     */
    private var naturalW = 0
    private var naturalH = 0

    /**
     * Dónde y cuánto ocupa la imagen dentro de la ventana, medido de verdad.
     *
     * Tiene que ser ESTADO, no un campo normal: al escalar, la ventana cambia
     * de tamaño con una nueva medida y disposición, pero sin recomponer nada.
     * Guardado en un campo suelto, la capa de anotaciones se quedaba con el
     * tamaño de la primera vez y los trazos dejaban de seguir a la imagen.
     */
    private val imageOrigin = mutableStateOf(Offset.Zero)
    private val imageSize = mutableStateOf(IntSize.Zero)

    private var visibleByGlobal = true
    private var collapsedInGroup = false

    val id: String get() = pin.value.id
    val isPinned: Boolean get() = pin.value.isPinned
    val isShowing: Boolean get() = window != null

    /** Estado completo actual, incluidos posición, escala y opacidad reales. */
    fun snapshot(): PinState {
        val p = lp
        return pin.value.copy(
            scale = scale.floatValue,
            minimized = minimized.value,
            x = p?.x ?: pin.value.x,
            y = p?.y ?: pin.value.y,
            alpha = contentAlpha.floatValue,
            annotations = annotator.annotations.toList()
        )
    }

    // ---- Ciclo de vida de la ventana ----

    fun show() {
        if (window != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            computeFlags(pin.value.clickThrough),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pin.value.x
            y = pin.value.y
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val w = OverlayComposeWindow(context) { PinRoot() }
        w.setTouchHandler(newTouchHandler())
        window = w
        lp = params
        runCatching {
            wm.addView(w.view, params)
            w.onAttached()
        }.onFailure {
            window = null
            lp = null
        }
    }

    /** Oculta la ventana conservando su posición y estado (ocultar todo / captura). */
    fun setViewVisible(visible: Boolean) {
        visibleByGlobal = visible
        applyVisibility()
        if (!visible) {
            closeActionBar()
            exitAnnotateMode()
        }
    }

    /**
     * Miembro de un grupo minimizado que NO es el que enseña la burbuja.
     *
     * Un grupo se minimiza en una sola burbuja: varias burbujas juntas se
     * estorban —al mover una se movían todas y alguna podía acabar fuera de la
     * pantalla— y no aportan nada, porque el grupo va siempre junto.
     */
    val isCollapsedInGroup: Boolean get() = collapsedInGroup

    fun setCollapsedInGroup(value: Boolean) {
        if (collapsedInGroup == value) return
        collapsedInGroup = value
        applyVisibility()
    }

    private fun applyVisibility() {
        window?.isContentVisible = visibleByGlobal && !collapsedInGroup
    }

    fun hideView() {
        exitAnnotateMode()
        closeActionBar()
        val w = window ?: return
        // Guarda la posición viva antes de soltar los LayoutParams.
        pin.value = snapshot()
        runCatching { wm.removeView(w.view) }
        w.onDetached()
        window = null
        lp = null
    }

    fun close() {
        hideView()
        callbacks.onPinClosed(this)
    }

    fun destroy() {
        hideView()
        callbacks.onPinDestroyed(this)
    }

    fun setClickThrough(value: Boolean) {
        if (pin.value.clickThrough == value) return
        pin.value = pin.value.copy(clickThrough = value)
        lp?.let { p ->
            p.flags = computeFlags(value)
            applyLayout()
        }
        callbacks.onPinChanged(this)
    }

    val isMinimized: Boolean get() = minimized.value

    val groupId: String? get() = pin.value.groupId

    fun setGroup(newGroupId: String?) {
        if (pin.value.groupId == newGroupId) return
        pin.value = pin.value.copy(groupId = newGroupId)
        callbacks.onPinChanged(this)
    }

    /** Marca/desmarca el pin como guardado. */
    fun togglePinned() {
        pin.value = pin.value.copy(isPinned = !pin.value.isPinned)
        callbacks.onPinChanged(this)
    }

    /** Actualiza el estado del pin (usado para renombrar, etc.). */
    fun updateState(newState: PinState) {
        pin.value = newState
        callbacks.onPinChanged(this)
    }

    /**
     * @param dock true solo cuando se ha soltado el pin sobre la bola: entonces
     * la burbuja se aparca junto a ella. Con doble toque la burbuja se queda
     * exactamente donde estaba el pin.
     */
    fun minimize(dock: Boolean = false) {
        if (minimized.value) return
        // Guarda dónde estaba el pin para devolverlo ahí al restaurarlo.
        restoreX = lp?.x ?: pin.value.x
        restoreY = lp?.y ?: pin.value.y
        // Dónde estaba ANTES de convertirse en burbuja. Es lo que necesita el
        // gestor para colocar a sus compañeros de grupo en su sitio relativo:
        // más abajo, clampBubbleIntoScreen() machaca restoreX/Y con la posición
        // de la burbuja ya recolocada.
        preMinimizeX = restoreX
        preMinimizeY = restoreY
        minimized.value = true
        closeActionBar()
        exitAnnotateMode()
        // La burbuja no se redimensiona; sin esto conservaría el rect del pin abierto.
        setResizeHandle(null)
        applyContentSize()
        if (dock) callbacks.onPinMinimized(this)
        // La imagen puede vivir fuera de la pantalla, pero la burbuja no: si no,
        // queda flotando en una zona invisible y ya no hay forma de tocarla.
        clampBubbleIntoScreen()
        callbacks.onPinMinimizeChanged(this, true)
        callbacks.onPinChanged(this)
    }

    fun restore() {
        if (!minimized.value) return
        minimized.value = false
        applyContentSize()
        moveTo(restoreX, restoreY)
        callbacks.onPinMinimizeChanged(this, false)
        callbacks.onPinChanged(this)
    }

    /** Coloca la ventana en coordenadas de pantalla (aparcar burbujas). */
    fun moveTo(x: Int, y: Int) {
        val p = lp ?: run {
            pin.value = pin.value.copy(x = x, y = y)
            return
        }
        p.x = x
        p.y = y
        applyLayout()
    }

    private fun computeFlags(clickThrough: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (clickThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun applyLayout() {
        val p = lp ?: return
        val v = window?.view ?: return
        runCatching { wm.updateViewLayout(v, p) }
    }

    /**
     * Tamaño natural de la imagen del pin: el ancho de partida (limitado para
     * que un pin recién creado no ocupe media pantalla) y el alto que le
     * corresponde por proporción.
     */
    private fun measureNatural(bitmap: Bitmap) {
        if (naturalW > 0) return
        val screenW = context.resources.displayMetrics.widthPixels
        naturalW = minOf(bitmap.width, (screenW * 0.6f).toInt()).coerceAtLeast(1)
        naturalH = (naturalW.toLong() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        applyContentSize()
    }

    /** Lleva la ventana al tamaño que le toca por la escala actual. */
    private fun applyContentSize() {
        val p = lp ?: return
        if (naturalW <= 0) return
        if (minimized.value) {
            // La burbuja se mide sola: es un círculo de tamaño fijo.
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            p.width = (naturalW * scale.floatValue).toInt().coerceAtLeast(1)
            p.height = (naturalH * scale.floatValue).toInt().coerceAtLeast(1)
        }
        applyLayout()
    }

    /**
     * Un reconocedor nuevo hereda el handle vivo: se recrea al salir del modo
     * anotación y sin esto el cuadro de texto dejaba de poder redimensionarse
     * hasta la siguiente recomposición.
     */
    private fun newTouchHandler(): OverlayTouchHandler =
        OverlayTouchHandler(context, GestureListener()).also {
            it.handleRect = resizeHandle
            touchHandler = it
        }

    private fun setResizeHandle(rect: android.graphics.Rect?) {
        resizeHandle = rect
        touchHandler?.handleRect = rect
    }

    // ---- Gestos ----

    private inner class GestureListener : OverlayTouchHandler.Listener {

        override fun onDragStart() {
            closeActionBar()
            dragStartX = lp?.x ?: 0
            dragStartY = lp?.y ?: 0
            callbacks.onPinDragStarted(this@PinWindowController)
        }

        override fun onDrag(dxFromDown: Float, dyFromDown: Float) {
            val p = lp ?: return
            p.x = dragStartX + dxFromDown.toInt()
            p.y = dragStartY + dyFromDown.toInt()
            applyLayout()
            callbacks.onPinDragged(
                this@PinWindowController, dxFromDown.toInt(), dyFromDown.toInt()
            )
        }

        override fun onDragEnd() {
            if (!minimized.value && droppedOnBall()) {
                minimize(dock = true)
                return
            }
            if (minimized.value) {
                clampBubbleIntoScreen()
            } else {
                keepReachable()
            }
            callbacks.onPinChanged(this@PinWindowController)
        }

        override fun onScaleStart(focusX: Float, focusY: Float) {
            closeActionBar()
            scaleStart = scale.floatValue
            val v = window?.view
            val p = lp
            scaleStartW = (v?.width ?: 0).coerceAtLeast(1)
            scaleStartH = (v?.height ?: 0).coerceAtLeast(1)
            val metrics = context.resources.displayMetrics
            zoomMax = if (naturalW > 0) {
                // Imagen con tamaño explícito: puede pasar del borde de la
                // pantalla para acercarse y leer.
                PinZoom.maxScaleFor(
                    realW = naturalW, realH = naturalH, currentScale = 1f,
                    screenW = metrics.widthPixels, screenH = metrics.heightPixels,
                    overzoom = PinZoom.IMAGE_OVERZOOM
                )
            } else {
                // Texto, color y archivo se miden solos, y ahí la ventana no
                // puede pasar de la pantalla: el tope es llenarla. El texto se
                // queda además en 5×, que es donde deja de leerse mejor.
                val max = PinZoom.maxScaleFor(
                    realW = scaleStartW, realH = scaleStartH, currentScale = scaleStart,
                    screenW = metrics.widthPixels, screenH = metrics.heightPixels
                )
                if (pin.value.type == PinType.TEXT) max.coerceAtMost(5f) else max
            }
            // Posición del foco DENTRO del pin (0..1): es lo que hay que dejar
            // clavado bajo los dedos mientras se escala.
            focusRelX = ((focusX - (p?.x ?: 0)) / scaleStartW).coerceIn(0f, 1f)
            focusRelY = ((focusY - (p?.y ?: 0)) / scaleStartH).coerceIn(0f, 1f)
        }

        override fun onScale(factorFromDown: Float, focusX: Float, focusY: Float) {
            if (minimized.value) return
            val v = window?.view ?: return
            val p = lp ?: return
            if (v.width <= 0 || v.height <= 0) return

            val stepResult = PinZoom.step(
                scaleAtStart = scaleStart,
                factor = factorFromDown,
                maxScale = zoomMax,
                realW = v.width,
                realH = v.height,
                focusX = focusX,
                focusY = focusY,
                relX = focusRelX,
                relY = focusRelY
            )
            scale.floatValue = stepResult.scale
            p.x = stepResult.x
            p.y = stepResult.y
            // Posición y tamaño de la ventana se actualizan a la vez: en dos
            // pasadas el pin daría un tirón entre una y otra.
            if (naturalW > 0) applyContentSize() else applyLayout()
        }

        override fun onScaleEnd() {
            keepReachable()
            callbacks.onPinChanged(this@PinWindowController)
        }

        override fun onOpacityStart() {
            closeActionBar()
            alphaStart = contentAlpha.floatValue
        }

        override fun onOpacity(dyFromDown: Float) {
            contentAlpha.floatValue = (alphaStart - dyFromDown / 600f).coerceIn(0.12f, 1f)
        }

        override fun onOpacityEnd() {
            callbacks.onPinChanged(this@PinWindowController)
        }

        private var resizeStartW = 0
        private var resizeStartH = 0

        override fun onResizeStart() {
            closeActionBar()
            val density = context.resources.displayMetrics.density
            resizeStartW = pin.value.textBoxWidth
            // Sin alto fijado aún, se parte del que tenga el pin ahora mismo:
            // así el cuadro no pega un salto en el primer píxel de arrastre.
            resizeStartH = pin.value.textBoxHeight
                ?: ((window?.view?.height ?: 0) / density).toInt()
                    .coerceAtLeast(TextBoxSize.MIN_HEIGHT)
        }

        override fun onResize(dxFromDown: Float, dyFromDown: Float) {
            val density = context.resources.displayMetrics.density
            val dims = TextBoxSize.resize(
                startWidth = resizeStartW,
                startHeight = resizeStartH,
                dxDp = dxFromDown / density,
                dyDp = dyFromDown / density
            )
            if (dims.width != pin.value.textBoxWidth ||
                dims.height != pin.value.textBoxHeight
            ) {
                pin.value = pin.value.copy(
                    textBoxWidth = dims.width,
                    textBoxHeight = dims.height
                )
            }
        }

        override fun onResizeEnd() {
            keepReachable()
            // Se persiste al soltar, no en cada muestra: scheduleSave ya hace
            // debounce, pero escribir el estado por píxel arrastrado es ruido.
            callbacks.onPinChanged(this@PinWindowController)
        }

        override fun onTap() {
            val s = pin.value
            when {
                minimized.value -> restore()
                actionBar != null -> closeActionBar()
                s.type == PinType.TEXT -> copyText(s)
                s.type == PinType.COLOR -> s.colorArgb?.let { copyColor(it) }
                s.type == PinType.FILE -> openFile(s)
                s.type == PinType.IMAGE -> copyImage(s)
            }
        }

        override fun onDoubleTap() {
            // Sin dock: la burbuja se queda donde estaba el pin.
            if (minimized.value) restore() else minimize(dock = false)
        }

        override fun onLongPress() {
            window?.view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (actionBar != null) closeActionBar() else openActionBar()
        }
    }

    /** La burbuja minimizada siempre entera dentro de la pantalla. */
    private fun clampBubbleIntoScreen() {
        val p = lp ?: return
        val metrics = context.resources.displayMetrics
        // Tamaño de la burbuja: la vista aún mide lo que medía el pin abierto.
        val size = (BUBBLE_DP * metrics.density).toInt()
        p.x = p.x.coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
        // El pin se restaurará donde acabe la burbuja, no donde estaba antes.
        restoreX = p.x
        restoreY = p.y
        applyLayout()
    }

    /**
     * Los pines pueden salirse de los bordes (es deliberado), pero nunca del
     * todo: siempre queda un trozo agarrable en pantalla.
     */
    private fun keepReachable() {
        val p = lp ?: return
        val v = window?.view ?: return
        val metrics = context.resources.displayMetrics
        val margin = (48 * metrics.density).toInt()
        val w = v.width.coerceAtLeast(margin)
        val h = v.height.coerceAtLeast(margin)
        val newX = p.x.coerceIn(margin - w, metrics.widthPixels - margin)
        // Un pin más alto que la pantalla tiene que poder subirse para ver su
        // parte de abajo; uno normal, en cambio, no se mete bajo la barra.
        val minY = minOf(0, metrics.heightPixels - h)
        val newY = p.y.coerceIn(minY, metrics.heightPixels - margin)
        if (newX != p.x || newY != p.y) {
            p.x = newX
            p.y = newY
            applyLayout()
        }
    }

    private fun droppedOnBall(): Boolean {
        val p = lp ?: return false
        val v = window?.view ?: return false
        val bounds = FloatingBallController.active?.ballBounds() ?: return false
        if (v.width == 0) return false
        return bounds.contains(p.x + v.width / 2, p.y + v.height / 2)
    }

    // ---- Barra de acciones (ventana aparte: la del pin no recibe toques de UI) ----

    private fun openActionBar() {
        if (actionBar != null) return
        val p = lp ?: return
        val v = window?.view ?: return
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.x
            y = p.y + v.height + (6 * density).toInt()
        }
        val bar = OverlayComposeWindow(context) { ActionBarContent() }
        actionBar = bar
        runCatching {
            wm.addView(bar.view, params)
            bar.onAttached()
        }.onFailure { actionBar = null }
    }

    private fun closeActionBar() {
        val bar = actionBar ?: return
        runCatching { wm.removeView(bar.view) }
        bar.onDetached()
        actionBar = null
    }

    // ---- Modo anotación (dibujar sobre el pin sin abrir ninguna pantalla) ----

    /**
     * Mientras dura, el pin queda clavado: los toques dejan de mover, escalar y
     * atenuar, y se los queda el motor de trazo. Se sale con el botón de la
     * barrita, que persiste lo dibujado.
     */
    private fun enterAnnotateMode() {
        if (annotating.value || pin.value.type != PinType.IMAGE) return
        closeActionBar()
        annotating.value = true
        annotationsAtEnter = annotator.annotations.toList()
        window?.setTouchHandler(
            StrokeTouchReader(
                onBegin = { x, y, p -> toImagePt(x, y, p)?.let { annotator.begin(it) } },
                onExtend = { x, y, p -> toImagePt(x, y, p)?.let { annotator.drag(it) } },
                onFinish = { annotator.end() },
                onCancel = { annotator.cancel() }
            )
        )
        openAnnotateBar()
    }

    private fun exitAnnotateMode() {
        if (!annotating.value) return
        annotating.value = false
        annotator.finishPolyline()
        annotator.cancel()
        window?.setTouchHandler(newTouchHandler())
        closeAnnotateBar()
        pin.value = snapshot()
        callbacks.onPinChanged(this)
        // Se acabó el botón de guardar: si has dibujado algo, la versión con lo
        // dibujado va sola a la galería.
        if (annotator.annotations != annotationsAtEnter) {
            callbacks.onPinSaveRequested(this)
        }
    }

    /**
     * Coordenadas de la ventana → píxeles de la imagen original. Guardar las
     * anotaciones en el espacio de la imagen es lo que hace que se puedan
     * dibujar con el pin pequeño y sigan cuadrando al agrandarlo.
     */
    private fun toImagePt(x: Float, y: Float, pressure: Float): Pt? {
        val bmp = bitmapState.value ?: return null
        val size = imageSize.value
        val origin = imageOrigin.value
        if (size.width <= 0 || bmp.width <= 0) return null
        // Se descuenta dónde empieza la imagen dentro de la ventana: si no, todo
        // el trazo sale corrido justo ese margen.
        val shownX = size.width.toFloat() / bmp.width
        val shownY = size.height.toFloat() / bmp.height
        return Pt(
            ((x - origin.x) / shownX).coerceIn(0f, bmp.width.toFloat()),
            ((y - origin.y) / shownY).coerceIn(0f, bmp.height.toFloat()),
            pressure
        )
    }

    private fun openAnnotateBar() {
        if (annotateBar != null) return
        val p = lp ?: return
        val v = window?.view ?: return
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val barPx = (ANNOTATE_BAR_DP * density).toInt()

        // La barra se pega al borde que menos tape del pin. Antes iba justo
        // debajo del pin y con un pin grande se le echaba encima, dejando sin
        // poder dibujar precisamente la parte que tapaba.
        val pinTop = p.y
        val pinBottom = p.y + v.height
        val overlapBottom =
            (minOf(pinBottom, metrics.heightPixels) - maxOf(pinTop, metrics.heightPixels - barPx))
                .coerceAtLeast(0)
        val overlapTop = (minOf(pinBottom, barPx) - maxOf(pinTop, 0)).coerceAtLeast(0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or
                if (overlapTop < overlapBottom) Gravity.TOP else Gravity.BOTTOM
            y = (12 * density).toInt()
        }
        val bar = OverlayComposeWindow(context) { AnnotateBarContent() }
        annotateBar = bar
        runCatching {
            wm.addView(bar.view, params)
            bar.onAttached()
        }.onFailure { annotateBar = null }
    }

    private fun closeAnnotateBar() {
        val bar = annotateBar ?: return
        runCatching { wm.removeView(bar.view) }
        bar.onDetached()
        annotateBar = null
    }

    @Composable
    private fun ActionBarContent() {
        val s by pin
        Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    setClickThrough(!s.clickThrough)
                    closeActionBar()
                }) {
                    Icon(
                        if (s.clickThrough) Icons.Filled.DoNotTouch else Icons.Filled.TouchApp,
                        contentDescription = context.getString(R.string.cd_clickthrough),
                        tint = if (s.clickThrough) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                // Dibujar encima solo tiene sentido sobre una imagen.
                if (s.type == PinType.IMAGE) {
                    IconButton(onClick = { enterAnnotateMode() }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = context.getString(R.string.cd_annotate_pin)
                        )
                    }
                }
                IconButton(onClick = { callbacks.onPinToggleSave(this@PinWindowController) }) {
                    Icon(
                        if (s.isPinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = context.getString(R.string.cd_bookmark),
                        tint = if (s.isPinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { close() }) {
                    Icon(Icons.Filled.Close, contentDescription = context.getString(R.string.cd_close))
                }
            }
        }
    }

    /**
     * Barrita de dibujo, pegada al borde de la pantalla que menos tape del pin.
     *
     * Va en su propia ventana porque la del pin se queda con TODOS los toques
     * para el trazo, y ahí ningún botón llegaría a enterarse.
     *
     * Es de una sola fila a propósito: diez herramientas en fila hacían una
     * barra larguísima que tapaba media imagen. La herramienta activa hace de
     * botón y despliega las demás en una rejilla solo mientras eliges.
     */
    @Composable
    private fun AnnotateBarContent() {
        var toolsOpen by remember { mutableStateOf(false) }
        val current = annotator.tool.value
        val currentIcon = ANNOTATE_TOOLS.firstOrNull { it.first == current }?.second
            ?: Icons.Filled.Gesture

        Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (toolsOpen) {
                    ANNOTATE_TOOLS.chunked(5).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            row.forEach { (type, icon) ->
                                IconButton(onClick = {
                                    annotator.selectTool(type)
                                    toolsOpen = false
                                }) {
                                    Icon(
                                        icon,
                                        contentDescription = type.name,
                                        tint = if (current == type) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { toolsOpen = !toolsOpen }) {
                        Icon(
                            currentIcon,
                            contentDescription = context.getString(R.string.cd_tools),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    PIN_PALETTE.forEach { argb ->
                        val selected = annotator.color.value == argb
                        Box(
                            Modifier
                                .padding(2.dp)
                                .size(if (selected) 22.dp else 18.dp)
                                .background(Color(argb), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { annotator.color.value = argb }
                        )
                    }
                    IconButton(onClick = {
                        // Tres grosores en rotación: no hay sitio para un slider.
                        annotator.strokeWidth.value = when (annotator.strokeWidth.value) {
                            in 0f..6f -> 8f
                            in 6f..12f -> 18f
                            else -> 4f
                        }
                    }) {
                        Box(
                            Modifier
                                .size((annotator.strokeWidth.value / 2 + 4).dp)
                                .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                        )
                    }
                    // Solo mientras haya una polilínea a medias: es su forma de cerrarse.
                    if (annotator.polylineOpen) {
                        IconButton(onClick = { annotator.finishPolyline() }) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = context.getString(R.string.cd_done),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = { annotator.undo() },
                        enabled = annotator.canUndo.value
                    ) {
                        Icon(Icons.Filled.Undo, contentDescription = context.getString(R.string.cd_undo))
                    }
                    IconButton(onClick = { exitAnnotateMode() }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = context.getString(R.string.cd_done),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // ---- Acciones de contenido ----

    private fun copyText(s: PinState) {
        val text = s.text ?: return
        clipboard()?.setPrimaryClip(ClipData.newPlainText("pixpin", text))
        toast(context.getString(R.string.copied_text))
    }

    /**
     * Un toque en la imagen la deja en el portapapeles, igual que el texto.
     *
     * Va por el gestor porque hay que hornear las anotaciones y escribir un
     * archivo, y eso no se hace en el hilo de UI.
     */
    private fun copyImage(s: PinState) {
        if (s.imagePath == null) return
        callbacks.onPinCopyRequested(this)
    }

    private fun copyColor(argb: Int) {
        val hex = ContentClassifier.toHex(argb)
        clipboard()?.setPrimaryClip(ClipData.newPlainText("color", hex))
        toast(context.getString(R.string.copied_hex, hex))
    }

    private fun openFile(s: PinState) {
        val path = s.filePath ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, s.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(context.getString(R.string.no_app_for_file))
        }
    }

    private fun clipboard() = context.getSystemService(ClipboardManager::class.java)

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // ---- Contenido Compose ----

    @Composable
    private fun PinRoot() {
        val s by pin
        val small by minimized
        Surface(
            shape = if (small) CircleShape else RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            border = when {
                s.clickThrough -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
                else -> s.groupId?.let { BorderStroke(2.dp, Color(PinGroups.colorFor(it))) }
            },
            modifier = Modifier.graphicsLayer { alpha = contentAlpha.floatValue }
        ) {
            if (small) BubbleContent(s) else PinBodyContent(s)
        }
    }

    @Composable
    private fun PinBodyContent(s: PinState) {
        when (s.type) {
            PinType.IMAGE -> ImagePinBody(s)
            PinType.TEXT -> TextPinBody(s)
            PinType.COLOR -> ColorPinBody(s)
            PinType.FILE -> FilePinBody(s)
        }
    }

    @Composable
    private fun BubbleContent(s: PinState) {
        Box(
            modifier = Modifier
                .size(BUBBLE_DP.dp)
                .clip(CircleShape)
                .background(
                    if (s.type == PinType.COLOR && s.colorArgb != null) Color(s.colorArgb)
                    else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            when (s.type) {
                PinType.IMAGE -> {
                    val bitmap = rememberPinBitmap(s.imagePath).value
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(BUBBLE_DP.dp)
                                .clip(CircleShape)
                        )
                    }
                }
                PinType.TEXT -> Icon(Icons.Filled.TextFields, null, tint = Color.White)
                PinType.COLOR -> Unit // la burbuja ya es del color
                PinType.FILE -> Icon(mimeIcon(s.mimeType), null, tint = Color.White)
            }
        }
    }

    @Composable
    private fun ImagePinBody(s: PinState) {
        val bmp = rememberPinBitmap(s.imagePath).value
        if (bmp == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .border(2.dp, MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = context.getString(R.string.pin_image_error),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            }
            return
        }
        // La ventana ya tiene el tamaño exacto que toca (measureNatural +
        // applyContentSize), así que la imagen solo tiene que llenarla.
        Box(
            Modifier
                .fillMaxSize()
                .border(2.dp, Color.Gray)
                // El origen y el tamaño REALES de la imagen en pantalla. Todo
                // lo demás —dónde se dibuja y dónde se toca— se deriva de aquí:
                // calcularlo por separado en dos sitios era lo que hacía que el
                // trazo saliera desplazado del dedo.
                .onGloballyPositioned { coords ->
                    imageOrigin.value = coords.positionInRoot()
                    imageSize.value = coords.size
                }
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            if (annotating.value || annotator.annotations.isNotEmpty()) {
                // Los trazos se dibujan sobre el MISMO tamaño con el que se
                // dimensiona la ventana (naturalW × escala, con el mismo
                // truncado), y leyendo la misma escala. Así imagen y dibujo
                // cambian de tamaño en el mismo fotograma: midiendo el
                // resultado ya dispuesto, el dibujo iría siempre uno por
                // detrás y se vería perseguir a la imagen al pellizcar.
                val zoom by scale
                val drawW = (naturalW * zoom).toInt().toFloat()
                val drawH = (naturalH * zoom).toInt().toFloat()
                AnnotationCanvas(
                    controller = annotator,
                    sourceBitmap = bmp,
                    imageRectInView = Rect(0f, 0f, drawW, drawH),
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }

    /**
     * La imagen se guarda en el controlador y no solo en la composición: el
     * conversor de coordenadas del modo anotación necesita su tamaño real.
     */
    @Composable
    private fun rememberPinBitmap(path: String?): State<Bitmap?> {
        LaunchedEffect(path) {
            if (path != null && bitmapState.value == null) {
                val loaded = withContext(Dispatchers.IO) { ImageStore.load(path) }
                // Primero se le da tamaño a la ventana y después se publica la
                // imagen: al revés, la composición se encontraría un instante
                // con una ventana aún sin medida y el contenido daría un salto.
                if (loaded != null) measureNatural(loaded)
                bitmapState.value = loaded
            }
        }
        return bitmapState
    }

    /**
     * El ancho y el alto los manda el estado, no la medida del texto: es lo que
     * permite estirar el cuadro por su esquina. Sin alto fijado, se ajusta al
     * texto; con él, lo que sobre se desplaza dentro.
     */
    @Composable
    private fun TextPinBody(s: PinState) {
        val zoom by scale
        val density = LocalDensity.current
        // Se lee aquí y no dentro del Canvas: en un DrawScope no hay MaterialTheme.
        val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Box(
            Modifier
                .width(s.textBoxWidth.dp)
                .then(
                    if (s.textBoxHeight != null) Modifier.height(s.textBoxHeight.dp)
                    else Modifier
                )
                // El handle se mide de verdad en vez de calcularse aparte: es lo
                // único que garantiza que la zona que responde al dedo y el
                // triangulito que se ve sean el mismo sitio.
                .onGloballyPositioned { coords ->
                    val origin = coords.positionInRoot()
                    val side = with(density) { HANDLE_DP.dp.roundToPx() }
                    val right = (origin.x + coords.size.width).toInt()
                    val bottom = (origin.y + coords.size.height).toInt()
                    setResizeHandle(
                        android.graphics.Rect(right - side, bottom - side, right, bottom)
                    )
                }
        ) {
            Text(
                text = s.text.orEmpty(),
                fontSize = (14f * zoom).sp,
                lineHeight = (20f * zoom).sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    // fillMaxWidth y no fillMaxSize: sin alto fijado, el Box se
                    // ajusta al texto, y un hijo que llenase el alto máximo lo
                    // estiraría hasta el tope de la pantalla.
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            )
            Canvas(
                modifier = Modifier
                    .size(HANDLE_DP.dp)
                    .align(Alignment.BottomEnd)
            ) {
                // Tres rayas en diagonal, el gesto universal de "estírame".
                val stroke = 1.5.dp.toPx()
                for (i in 1..3) {
                    val inset = size.width * (i / 4f)
                    drawLine(
                        color = handleColor,
                        start = Offset(size.width - inset, size.height),
                        end = Offset(size.width, size.height - inset),
                        strokeWidth = stroke
                    )
                }
            }
        }
    }

    @Composable
    private fun ColorPinBody(s: PinState) {
        val argb = s.colorArgb ?: return
        val zoom by scale
        // Sin tope propio: quien manda es el tope del pellizco (PinZoom).
        val side = (96f * zoom).coerceAtLeast(48f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(side.dp)
                    .background(Color(argb))
            )
            Text(
                text = ContentClassifier.toHex(argb),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    @Composable
    private fun FilePinBody(s: PinState) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 250.dp)
                .padding(12.dp)
        ) {
            Icon(
                mimeIcon(s.mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = s.fileName ?: context.getString(R.string.pin_type_file),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = mimeLabel(s.mimeType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private companion object {
        /** Diámetro de la burbuja minimizada, en dp. */
        const val BUBBLE_DP = 46

        /** Lado de la esquina agarrable del cuadro de texto, en dp. */
        const val HANDLE_DP = 30
    }

    private fun mimeIcon(mime: String?) = when {
        mime == null -> Icons.Filled.InsertDriveFile
        mime == "application/pdf" -> Icons.Filled.PictureAsPdf
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("video/") -> Icons.Filled.Videocam
        mime.startsWith("audio/") -> Icons.Filled.AudioFile
        mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") ->
            Icons.Filled.FolderZip
        mime.startsWith("text/") -> Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }

    private fun mimeLabel(mime: String?): String {
        if (mime == null) return ""
        return mime.substringAfter('/').take(12).uppercase()
    }
}

/** Colores de la barrita de anotación del pin (los mismos de la pantalla de captura). */
private val PIN_PALETTE = listOf(
    0xFFF44336.toInt(), 0xFFFFEB3B.toInt(), 0xFF4CAF50.toInt(),
    0xFF2196F3.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()
)

/**
 * Herramientas del pin: las mismas de la pantalla de captura salvo el texto,
 * que necesita teclado, y una ventana overlay sin foco no lo tiene.
 */
private val ANNOTATE_TOOLS = listOf(
    AnnotationType.PENCIL to Icons.Filled.Gesture,
    AnnotationType.HIGHLIGHT to Icons.Filled.Highlight,
    AnnotationType.ARROW to Icons.Filled.NorthEast,
    AnnotationType.RECT to Icons.Filled.CropSquare,
    AnnotationType.ELLIPSE to Icons.Filled.RadioButtonUnchecked,
    AnnotationType.SERIAL to Icons.Filled.FormatListNumbered,
    AnnotationType.POLYLINE to Icons.Filled.Timeline,
    AnnotationType.SPOTLIGHT to Icons.Filled.CenterFocusStrong,
    AnnotationType.MOSAIC to Icons.Filled.BlurOn,
    AnnotationType.ERASER to Icons.Filled.AutoFixNormal
)

/** Alto aproximado de la barrita, para decidir a qué borde se pega. */
private const val ANNOTATE_BAR_DP = 76
