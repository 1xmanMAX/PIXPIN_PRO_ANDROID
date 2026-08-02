package com.forge.pixpin.pin

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.material.icons.filled.EmojiEmotions
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import com.forge.pixpin.markdown.LinkHit
import com.forge.pixpin.markdown.Markdown
import com.forge.pixpin.markdown.MarkdownEdit
import com.forge.pixpin.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        /** Se ha extraído una página de un PDF: el gestor la convierte en pin. */
        fun onPinImageExtracted(controller: PinWindowController, imagePath: String) {}

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
    private var emojiPicker: OverlayComposeWindow? = null

    private var touchHandler: OverlayTouchHandler? = null

    /** Esquina agarrable del cuadro de texto, en coordenadas de la ventana. */
    private var resizeHandle: android.graphics.Rect? = null

    /**
     * Enlaces del pin de texto, en coordenadas SIN escalar del contenido. El
     * toque llega en píxeles de ventana, así que hay que dividirlo por el zoom
     * antes de comparar.
     */
    private var linkHits: List<LinkHit> = emptyList()

    /**
     * Desplazamiento del cuadro de texto. Vive AQUÍ y no en la composición
     * porque quien lo mueve es el reconocedor de gestos: la ventana del pin se
     * queda con todos los toques, así que un `verticalScroll` dentro no recibe
     * ninguno y no se desplazaría jamás.
     */
    private val textScroll = ScrollState(0)

    /** Zona de la pastilla, y cuánto recorre, para traducir el arrastre. */
    private var scrollPipTravelPx = 1f

    private fun setScrollRect(rect: android.graphics.Rect?, travelPx: Float) {
        scrollPipTravelPx = travelPx.coerceAtLeast(1f)
        touchHandler?.scrollRect = rect
        pendingScrollRect = rect
    }

    private var pendingScrollRect: android.graphics.Rect? = null

    private val pin = mutableStateOf(initialState)
    private val scale = mutableFloatStateOf(initialState.scale)
    private val minimized = mutableStateOf(initialState.minimized)

    /** Imagen del pin, compartida entre el dibujado y el conversor de coordenadas. */
    private val bitmapState = mutableStateOf<Bitmap?>(null)

    /**
     * Si ya se intentó leer la imagen del disco.
     *
     * Sin esto, «todavía cargando» y «no se pudo leer» eran el mismo null, y el
     * recuadro rojo de error parpadeaba un instante en CADA pin de imagen antes
     * de que la lectura terminase.
     */
    private val bitmapLoadTried = mutableStateOf(false)

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

    /** Hueco ya aplicado a la ventana, en px, para compensar al cambiarlo. */
    private var appliedLeft = 0
    private var appliedTop = 0

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
            closeEmojiPicker()
        closePdfViewer()
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
        closeEmojiPicker()
        closePdfViewer()
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
        closeEmojiPicker()
        closePdfViewer()
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
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // Editando NO se pone NOT_FOCUSABLE: sin foco no hay teclado, que es
        // justo el motivo por el que hasta ahora no se podía escribir en un pin.
        if (!editing.value) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (clickThrough) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /** Mientras dura, el pin deja de responder a gestos y se escribe en él. */
    private val editing = mutableStateOf(false)

    /**
     * El texto que se está escribiendo, CON su selección.
     *
     * Vive en el controlador y no en el composable porque la barra de formato
     * está en otra ventana: para poner algo en negrita hay que saber qué hay
     * seleccionado, y desde otra ventana no se llega al estado de la que edita.
     */
    private val draft = mutableStateOf(TextFieldValue(""))

    private var editBar: OverlayComposeWindow? = null

    private fun enterEditMode() {
        if (editing.value || pin.value.type != PinType.TEXT) return
        closeActionBar()
        closeEmojiPicker()
        closePdfViewer()
        val body = pin.value.text.orEmpty()
        draft.value = TextFieldValue(body, TextRange(body.length))
        editing.value = true
        openEditBar()
        // Sin reconocedor: los toques son para el cursor y la selección.
        window?.setTouchHandler(null)
        touchHandler = null
        lp?.let { p ->
            p.flags = computeFlags(pin.value.clickThrough)
            // El teclado no debe tapar el pin que se está editando.
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            applyLayoutNow()
        }
    }

    /** Aplica un formato a lo que haya seleccionado ahora mismo. */
    private fun applyWrap(marker: String) {
        val v = draft.value
        val r = MarkdownEdit.wrap(v.text, v.selection.start, v.selection.end, marker)
        draft.value = TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
    }

    private fun applyPrefix(prefix: String) {
        val v = draft.value
        val r = MarkdownEdit.togglePrefix(v.text, v.selection.start, prefix)
        draft.value = TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
    }

    private fun exitEditMode(newText: String) {
        if (!editing.value) return
        editing.value = false
        closeEditBar()
        if (newText != pin.value.text) {
            pin.value = pin.value.copy(text = newText)
        }
        window?.setTouchHandler(newTouchHandler())
        lp?.let { p ->
            p.flags = computeFlags(pin.value.clickThrough)
            applyLayoutNow()
        }
        callbacks.onPinChanged(this)
    }

    private var layoutScheduled = false

    /**
     * Un solo `updateViewLayout` por fotograma dibujado.
     *
     * Los eventos táctiles llegan a 120-240 Hz según el panel, y cada uno pedía
     * su propio redimensionado de ventana: dos, tres o cuatro operaciones entre
     * procesos contra el WindowManager por cada fotograma que se llega a ver.
     * Todas menos la última son trabajo tirado, y en Android 10 y 12 —donde ese
     * camino es bastante más caro que en 16— son las que se notan como tirones.
     *
     * Encolarlo en el Choreographer deja exactamente una por fotograma, que es
     * justo la que el usuario ve.
     */
    private fun applyLayout() {
        if (layoutScheduled) return
        layoutScheduled = true
        Choreographer.getInstance().postFrameCallback {
            layoutScheduled = false
            applyLayoutNow()
        }
    }

    /** Sin esperar al fotograma: para lo que tiene que estar ya, como aparcar una burbuja. */
    private fun applyLayoutNow() {
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

    /**
     * Lleva la ventana al tamaño que le toca por la escala actual, más el hueco
     * del sticker si lo hay.
     *
     * [naturalW]/[naturalH] siguen siendo el tamaño de la IMAGEN, no el de la
     * ventana: AnnotationCanvas calcula su rectángulo con naturalW × zoom, y si
     * incluyeran el margen los trazos se despegarían de la foto.
     */
    private fun applyContentSize() {
        val p = lp ?: return
        if (naturalW <= 0) return
        if (minimized.value) {
            // La burbuja se mide sola: es un círculo de tamaño fijo.
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            val density = context.resources.displayMetrics.density
            val insets = PinChrome.insetsFor(pin.value.emoji != null)
            val left = (insets.left * density).toInt()
            val top = (insets.top * density).toInt()
            // La ventana se coloca por su esquina superior izquierda: sin
            // compensar, poner un emoji empujaba la imagen hacia abajo y el pin
            // parecía dar un salto.
            if (left != appliedLeft || top != appliedTop) {
                p.x -= left - appliedLeft
                p.y -= top - appliedTop
                appliedLeft = left
                appliedTop = top
            }
            p.width = (naturalW * scale.floatValue).toInt().coerceAtLeast(1) +
                (insets.horizontal * density).toInt()
            p.height = (naturalH * scale.floatValue).toInt().coerceAtLeast(1) +
                (insets.vertical * density).toInt()
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
            it.scrollRect = pendingScrollRect
            touchHandler = it
        }

    private fun setResizeHandle(rect: android.graphics.Rect?) {
        resizeHandle = rect
        touchHandler?.handleRect = rect
    }

    /** Zoom actual, nunca cero: se usa como divisor al pasar px a tamaño base. */
    private fun zoomOrOne(): Float = scale.floatValue.takeIf { it > 0.01f } ?: 1f

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
                // Con tamaño explícito la ventana puede pasar del borde de la
                // pantalla para acercarse y leer. El texto también, ahora que lo
                // tiene, pero se queda en 5×: más allá no se lee mejor.
                val max = PinZoom.maxScaleFor(
                    realW = naturalW, realH = naturalH, currentScale = 1f,
                    screenW = metrics.widthPixels, screenH = metrics.heightPixels,
                    overzoom = PinZoom.IMAGE_OVERZOOM
                )
                if (pin.value.type == PinType.TEXT) max.coerceAtMost(5f) else max
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

            // El tamaño con el que se ancla el foco es al que la ventana VA a
            // quedar, no el que tiene ahora. v.width va uno o dos fotogramas por
            // detrás porque el redimensionado es asíncrono, y realimentar esa
            // medida retrasada en la fórmula de posición es lo que hacía
            // temblar el pin al pellizcar en Android 10 y 12 (en 16 el
            // compositor iba lo bastante rápido para taparlo).
            //
            // Antes se usaba la medida real porque el tamaño teórico se
            // desviaba: una ventana WRAP_CONTENT no puede medir más que la
            // pantalla y ahí ambos dejaban de coincidir. Con tamaño explícito
            // eso ya no pasa, así que el teórico es a la vez exacto y estable.
            val projected = PinZoom.scaleFor(scaleStart, factorFromDown, zoomMax)
            val useNatural = naturalW > 0 && naturalH > 0
            val stepResult = PinZoom.step(
                scaleAtStart = scaleStart,
                factor = factorFromDown,
                maxScale = zoomMax,
                realW = if (useNatural) (naturalW * projected).toInt() else v.width,
                realH = if (useNatural) (naturalH * projected).toInt() else v.height,
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
            // así el cuadro no pega un salto en el primer píxel de arrastre. La
            // ventana medida ya incluye el zoom, y lo que se guarda es la base.
            resizeStartH = pin.value.textBoxHeight
                ?: ((window?.view?.height ?: 0) / density / zoomOrOne()).toInt()
                    .coerceAtLeast(TextBoxSize.MIN_HEIGHT)
        }

        override fun onResize(dxFromDown: Float, dyFromDown: Float) {
            val density = context.resources.displayMetrics.density
            // Se divide TAMBIÉN por el zoom: lo que se guarda es el tamaño base,
            // y sin esto un dedo que recorre 100 px sobre un pin al triple
            // movería la base 100 dp en vez de 33, estirando el cuadro tres
            // veces más rápido que el dedo.
            val z = zoomOrOne()
            val dims = TextBoxSize.resize(
                startWidth = resizeStartW,
                startHeight = resizeStartH,
                dxDp = dxFromDown / density / z,
                dyDp = dyFromDown / density / z
            )
            if (dims.width != pin.value.textBoxWidth ||
                dims.height != pin.value.textBoxHeight
            ) {
                pin.value = pin.value.copy(
                    textBoxWidth = dims.width,
                    textBoxHeight = dims.height
                )
                // Ya no se mide el contenido, así que la ventana tiene que
                // seguir al estado desde aquí o el cuadro crecería por dentro
                // sin que la ventana se enterase.
                val d = context.resources.displayMetrics.density
                naturalW = (dims.width * d).toInt().coerceAtLeast(1)
                naturalH = (dims.height * d).toInt().coerceAtLeast(1)
                applyContentSize()
            }
        }

        private var scrollStartValue = 0

        override fun onScrollStart() {
            closeActionBar()
            scrollStartValue = textScroll.value
        }

        override fun onScrollDrag(dyFromDown: Float) {
            val max = textScroll.maxValue
            if (max <= 0) return
            // El dedo recorre la pastilla, no el texto: lo que avanza el
            // contenido es proporcional a lo que le queda de recorrido.
            val perPx = max / scrollPipTravelPx / zoomOrOne()
            val target = (scrollStartValue + dyFromDown * perPx).coerceIn(0f, max.toFloat())
            // dispatchRawDelta es síncrono; scrollTo suspende y aquí no hay
            // corrutina que valga: esto corre en el reparto de toques.
            textScroll.dispatchRawDelta(target - textScroll.value)
        }

        override fun onScrollEnd() = Unit

        override fun onResizeEnd() {
            keepReachable()
            // Se persiste al soltar, no en cada muestra: scheduleSave ya hace
            // debounce, pero escribir el estado por píxel arrastrado es ruido.
            callbacks.onPinChanged(this@PinWindowController)
        }

        override fun onTap(x: Float, y: Float) {
            val s = pin.value
            when {
                minimized.value -> restore()
                actionBar != null -> closeActionBar()
                // Un enlace tocado abre el navegador; el resto del pin sigue
                // copiando el texto entero, como siempre.
                s.type == PinType.TEXT && openLinkAt(x, y) -> Unit
                s.type == PinType.TEXT -> copyText(s)
                s.type == PinType.COUNTER -> bumpCounter(+1)
                // La lista reparte el toque por filas: cada una son ~26 dp.
                s.type == PinType.CHECKLIST -> toggleCheck(rowAt(y))
                s.type == PinType.COLOR -> s.colorArgb?.let { copyColor(it) }
                s.type == PinType.FILE -> openFile(s)
                s.type == PinType.IMAGE -> copyImage(s)
            }
        }

        override fun onDoubleTap() {
            if (!minimized.value && pin.value.type == PinType.COUNTER) {
                bumpCounter(-1)
                return
            }
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

    // ---- Visor de PDF ----

    private var pdfViewer: OverlayComposeWindow? = null

    /**
     * Scope de la app y no uno propio: extraer una página tiene que terminar
     * aunque el visor se cierre por el camino.
     */
    private val scope
        get() = (context.applicationContext as com.forge.pixpin.PixPinApp).scope

    val isPdf: Boolean
        get() = pin.value.type == PinType.FILE && pin.value.mimeType == "application/pdf"

    private fun openPdfViewer() {
        if (pdfViewer != null) return
        closeActionBar()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val viewer = OverlayComposeWindow(context) { PdfViewerContent() }
        pdfViewer = viewer
        runCatching {
            wm.addView(viewer.view, params)
            viewer.onAttached()
        }.onFailure { pdfViewer = null }
    }

    private fun closePdfViewer() {
        val viewer = pdfViewer ?: return
        runCatching { wm.removeView(viewer.view) }
        viewer.onDetached()
        pdfViewer = null
    }

    /** Saca una página como pin de imagen. */
    private fun extractPage(index: Int) {
        val path = pin.value.filePath ?: return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val bmp = PdfDoc.render(path, index, PdfDoc.PAGE_WIDTH) ?: return@withContext null
                val out = ImageStore.saveBitmap(
                    context, bmp, "pdf_${System.currentTimeMillis()}_$index.png"
                )
                bmp.recycle()
                out
            }
            if (saved != null) {
                callbacks.onPinImageExtracted(this@PinWindowController, saved)
            } else {
                toast(context.getString(R.string.capture_error))
            }
        }
    }

    private fun extractAllPages(count: Int) {
        closePdfViewer()
        scope.launch {
            // De una en una y con respiro: veinte páginas a la vez son veinte
            // bitmaps grandes y veinte ventanas nuevas de golpe.
            for (i in 0 until count.coerceAtMost(MAX_PAGES_AT_ONCE)) {
                extractPage(i)
                delay(120)
            }
        }
    }

    /**
     * Rejilla de páginas. Las miniaturas se dibujan bajo demanda y pequeñas: un
     * PDF de doscientas páginas renderizado entero de golpe se lleva la memoria
     * por delante.
     */
    @Composable
    private fun PdfViewerContent() {
        val path = pin.value.filePath
        val pages = remember(path) { if (path != null) PdfDoc.pageCount(path) else 0 }
        Surface(shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = context.getString(R.string.pdf_pages, pages),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (pages > 0) {
                        TextButton(onClick = { extractAllPages(pages) }) {
                            Text(context.getString(R.string.pdf_all))
                        }
                    }
                    IconButton(onClick = { closePdfViewer() }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                if (pages == 0) {
                    Text(
                        text = context.getString(R.string.pdf_unreadable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    return@Column
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    (0 until pages).chunked(3).forEach { row ->
                        Row {
                            row.forEach { index -> PdfThumb(path!!, index) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PdfThumb(path: String, index: Int) {
        var thumb by remember(path, index) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(path, index) {
            thumb = withContext(Dispatchers.IO) {
                PdfDoc.render(path, index, PdfDoc.THUMB_WIDTH)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(4.dp)
                .width(96.dp)
                .clickable {
                    extractPage(index)
                    closePdfViewer()
                }
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(130.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                thumb?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
        }
    }

    // ---- Barra de edición de texto ----

    /**
     * Va en ventana propia, pegada abajo, por dos motivos. Uno: dentro del pin
     * los botones quedaban empujados fuera de la vista en cuanto el texto era
     * largo, y no había forma de cerrar la edición. Y dos: es donde el usuario
     * ya espera encontrarla, porque la barra de dibujo funciona igual.
     */
    private fun openEditBar() {
        if (editBar != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Por encima del teclado, que es lo que ocupa la mitad de abajo.
            y = (12 * context.resources.displayMetrics.density).toInt()
        }
        val bar = OverlayComposeWindow(context) { EditBarContent() }
        editBar = bar
        runCatching {
            wm.addView(bar.view, params)
            bar.onAttached()
        }.onFailure { editBar = null }
    }

    private fun closeEditBar() {
        val bar = editBar ?: return
        runCatching { wm.removeView(bar.view) }
        bar.onDetached()
        editBar = null
    }

    /**
     * Seis formatos y el botón de cerrar. Son los que se usan de verdad al
     * tomar notas; meter subíndices y superíndices en una barra que tiene que
     * caber sobre un teclado sería llenarla de cosas que nadie toca.
     */
    @Composable
    private fun EditBarContent() {
        Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditBarButton("H") { applyPrefix("# ") }
                EditBarButton("B", bold = true) { applyWrap("**") }
                EditBarButton("I", italic = true) { applyWrap("*") }
                EditBarButton("S", strike = true) { applyWrap("~~") }
                EditBarButton("</>") { applyWrap("`") }
                EditBarButton("•") { applyPrefix("- ") }
                IconButton(onClick = { exitEditMode(pin.value.text.orEmpty()) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = context.getString(R.string.cancel)
                    )
                }
                IconButton(onClick = { exitEditMode(draft.value.text) }) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = context.getString(R.string.cd_done),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    @Composable
    private fun EditBarButton(
        label: String,
        bold: Boolean = false,
        italic: Boolean = false,
        strike: Boolean = false,
        onClick: () -> Unit
    ) {
        TextButton(onClick = onClick, modifier = Modifier.widthIn(min = 40.dp)) {
            Text(
                text = label,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (strike) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    // ---- Pegatinas ----

    /**
     * Va en ventana propia por el mismo motivo que la barrita de anotación: la
     * del pin se queda con TODOS los toques y ningún botón dentro de ella se
     * enteraría.
     */
    private fun openEmojiPicker() {
        if (emojiPicker != null) return
        closeActionBar()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val picker = OverlayComposeWindow(context) { EmojiPickerContent() }
        emojiPicker = picker
        runCatching {
            wm.addView(picker.view, params)
            picker.onAttached()
        }.onFailure { emojiPicker = null }
    }

    private fun closeEmojiPicker() {
        val picker = emojiPicker ?: return
        runCatching { wm.removeView(picker.view) }
        picker.onDetached()
        emojiPicker = null
    }

    private fun setEmoji(value: String?) {
        pin.value = pin.value.copy(emoji = value)
        // Poner o quitar la pegatina cambia el tamaño que necesita la ventana.
        applyContentSize()
        closeEmojiPicker()
        closePdfViewer()
        callbacks.onPinChanged(this)
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
                if (isPdf) {
                    IconButton(onClick = { openPdfViewer() }) {
                        Icon(
                            Icons.Filled.PictureAsPdf,
                            contentDescription = context.getString(R.string.pdf_view)
                        )
                    }
                }
                if (s.type == PinType.TEXT) {
                    IconButton(onClick = { enterEditMode() }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = context.getString(R.string.cd_edit_text)
                        )
                    }
                }
                IconButton(onClick = { openEmojiPicker() }) {
                    Icon(
                        Icons.Filled.EmojiEmotions,
                        contentDescription = context.getString(R.string.cd_emoji),
                        tint = if (s.emoji != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
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

    @Composable
    private fun EmojiPickerContent() {
        Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // En un temporizador la pegatina deja de ser decoración y hace
                // de mando: cada emoji de número arranca esos minutos.
                if (pin.value.type == PinType.TIMER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TIMER_PRESETS.forEach { (emoji, minutes) ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clickable {
                                        startTimer(minutes)
                                        setEmoji(emoji)
                                    }
                            ) {
                                Text(text = emoji, fontSize = 26.sp)
                                Text(
                                    text = "$minutes",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                PIN_EMOJIS.chunked(6).forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .clickable { setEmoji(emoji) }
                            )
                        }
                    }
                }
                TextButton(onClick = { setEmoji(null) }) {
                    Text(context.getString(R.string.emoji_none))
                }
            }
        }
    }

    // ---- Acciones de contenido ----

    /**
     * ¿Cayó el toque sobre un enlace? El contenido está escalado por el zoom y
     * desplazado por el hueco de la sombra, así que el punto se devuelve a
     * coordenadas de contenido antes de comparar.
     *
     * @return true si se abrió algo, para que quien llama no copie además el texto.
     */
    private fun openLinkAt(x: Float, y: Float): Boolean {
        if (linkHits.isEmpty()) return false
        val z = zoomOrOne()
        val hit = linkHits.firstOrNull { it.contains(x / z, y / z) } ?: return false
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(hit.url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .getOrElse {
                toast(context.getString(R.string.no_app_for_file))
                false
            }
    }

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
        val insets = PinChrome.insetsFor(s.emoji != null)
        val shape = if (small) CircleShape else RoundedCornerShape(12.dp)
        // El color lo lleva la SOMBRA, no un marco: misma información, sin
        // dibujar una caja alrededor. Se respeta la precedencia que tenían los
        // marcos, con los toques a través por delante del grupo.
        val shadowColor = when {
            s.clickThrough -> MaterialTheme.colorScheme.tertiary
            s.groupId != null -> Color(PinGroups.colorFor(s.groupId!!))
            else -> Color.Black
        }
        Box(modifier = Modifier.graphicsLayer { alpha = contentAlpha.floatValue }) {
            Surface(
                shape = shape,
                // A cero: la sombra la pone el Modifier, que es el único que
                // admite color. Dejarla aquí también pintaría dos.
                shadowElevation = 0.dp,
                modifier = Modifier
                    .padding(
                        start = insets.left.dp,
                        top = insets.top.dp,
                        end = insets.right.dp,
                        bottom = insets.bottom.dp
                    )
                    .shadow(
                        elevation = PinChrome.SHADOW_ELEVATION_DP.dp,
                        shape = shape,
                        ambientColor = shadowColor,
                        spotColor = shadowColor
                    )
            ) {
                if (small) BubbleContent(s) else PinBodyContent(s)
            }
            // La pegatina va DESPUÉS del Surface para quedar por encima, y se
            // dibuja dentro de su propia caja: rotado 30°, un glifo de 22 dp
            // ocupa unos 30 dp, así que en una caja de 34 dp no se sale ni se
            // corta contra el borde de la ventana.
            s.emoji?.let { emoji ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // El hueco de la sombra también rodea a la pegatina: sin
                        // descontarlo se pegaría al borde de la ventana y se
                        // cortaría al rotar.
                        .padding(top = PinChrome.SHADOW_DP.dp, end = PinChrome.SHADOW_DP.dp)
                        .size(PinChrome.STICKER_SIZE_DP.dp)
                        .rotate(30f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = if (small) 16.sp else 22.sp)
                }
            }
        }
    }

    @Composable
    private fun PinBodyContent(s: PinState) {
        when (s.type) {
            PinType.IMAGE -> ImagePinBody(s)
            PinType.TEXT -> TextPinBody(s)
            PinType.COLOR -> ColorPinBody(s)
            PinType.FILE -> FilePinBody(s)
            PinType.TIMER -> TimerBody(
                widget = s.widget,
                nowProvider = { System.currentTimeMillis() },
                onFinished = { onTimerFinished() }
            )
            PinType.CHECKLIST -> ChecklistBody(s.text, s.widget) { toggleCheck(it) }
            PinType.COUNTER -> CounterBody(s.widget)
            PinType.LEDGER -> LedgerBody(s.text, context.getString(R.string.ledger_total))
        }
    }

    /** Marca o desmarca un ítem de la lista, creciendo la lista si hace falta. */
    private fun toggleCheck(index: Int) {
        val w = pin.value.widget
        val marks = w.checked.toMutableList()
        while (marks.size <= index) marks.add(false)
        marks[index] = !marks[index]
        pin.value = pin.value.copy(widget = w.copy(checked = marks))
        callbacks.onPinChanged(this)
    }

    private fun bumpCounter(delta: Int) {
        val w = pin.value.widget
        pin.value = pin.value.copy(widget = w.copy(count = w.count + delta))
        callbacks.onPinChanged(this)
    }

    /** Qué fila de la lista cae bajo el dedo, en coordenadas de ventana. */
    private fun rowAt(y: Float): Int {
        val density = context.resources.displayMetrics.density
        val padTop = (12 + PinChrome.SHADOW_DP) * density
        val rowH = 26 * density * zoomOrOne()
        return (((y - padTop) / rowH).toInt()).coerceAtLeast(0)
    }

    private var timerAlerted = false

    private fun onTimerFinished() {
        if (timerAlerted) return
        timerAlerted = true
        window?.view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        toast(context.getString(R.string.timer_done))
    }

    /** Arranca la cuenta atrás con los minutos que diga la pegatina elegida. */
    private fun startTimer(minutes: Int) {
        timerAlerted = false
        pin.value = pin.value.copy(
            widget = pin.value.widget.copy(
                timerMinutes = minutes,
                timerEndsAt = System.currentTimeMillis() + minutes * 60_000L
            )
        )
        callbacks.onPinChanged(this)
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
                PinType.TIMER -> Text("⏱", color = Color.White)
                PinType.CHECKLIST -> Text("☑", color = Color.White)
                PinType.COUNTER -> Text("${s.widget.count}", color = Color.White)
                PinType.LEDGER -> Text("€", color = Color.White)
            }
        }
    }

    @Composable
    private fun ImagePinBody(s: PinState) {
        val bmp = rememberPinBitmap(s.imagePath).value
        // Mientras se lee del disco no se pinta nada: enseñar el recuadro de
        // error en ese hueco hacía parpadear un cuadro rojo en cada pin.
        if (bmp == null && !bitmapLoadTried.value) return
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
                // Sin marco: la separación del fondo la da la sombra de PinRoot.
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
                bitmapLoadTried.value = true
            }
        }
        return bitmapState
    }

    /**
     * El ancho y el alto los manda el estado, no la medida del texto: es lo que
     * permite estirar el cuadro por su esquina. Sin alto fijado, se ajusta al
     * texto; con él, lo que sobre se desplaza dentro.
     *
     * Los dos son el tamaño BASE, a zoom 1. Al pellizcar se multiplican por el
     * zoom igual que la fuente, así que el pin crece entero y en proporción,
     * como si fuera una imagen, en vez de re-ajustar el texto dentro de un
     * cuadro que no se mueve.
     */
    @Composable
    private fun TextPinBody(s: PinState) {
        val zoom by scale
        val density = LocalDensity.current
        // Se lee aquí y no dentro del Canvas: en un DrawScope no hay MaterialTheme.
        val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        // Se re-interpreta solo cuando cambia el texto, no en cada fotograma del
        // pellizco: el zoom no afecta a la sintaxis.
        val blocks = remember(s.text) { Markdown.parse(s.text.orEmpty()) }
        Box(Modifier.fillMaxSize()) {
            // El texto se compone UNA VEZ a tamaño base y se escala con una
            // transformación de GPU, igual que una imagen.
            //
            // Antes el ancho iba en dp multiplicados por el zoom, así que cada
            // fotograma del pellizco re-medía y re-ajustaba el texto: las
            // palabras cambiaban de línea, volvían y repetían, y el cuadro
            // vibraba. Escalando el dibujado ya compuesto no hay re-ajuste
            // ninguno que hacer.
            ScaledContent(
                baseWidthPx = with(density) { s.textBoxWidth.dp.roundToPx() },
                baseHeightPx = s.textBoxHeight?.let { with(density) { it.dp.roundToPx() } },
                zoom = zoom,
                onBaseMeasured = { w, h ->
                    if (w != naturalW || h != naturalH) {
                        naturalW = w
                        naturalH = h
                        applyContentSize()
                    }
                }
            ) {
                Box {
                    if (editing.value) {
                        TextEditor(s)
                    } else {
                        MarkdownText(
                            blocks = blocks,
                            baseSizeSp = 14f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(textScroll)
                                .padding(14.dp),
                            onLinks = { linkHits = it }
                        )
                        ScrollPip(textScroll)
                    }
                }
            }
            // El handle va FUERA de la capa escalada: es un control, no
            // contenido, y así su zona táctil sale medida sin corregir nada.
            Canvas(
                modifier = Modifier
                    .size(HANDLE_DP.dp)
                    .align(Alignment.BottomEnd)
                    .onGloballyPositioned { coords ->
                        val origin = coords.positionInRoot()
                        setResizeHandle(
                            android.graphics.Rect(
                                origin.x.toInt(),
                                origin.y.toInt(),
                                (origin.x + coords.size.width).toInt(),
                                (origin.y + coords.size.height).toInt()
                            )
                        )
                    }
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

    /**
     * Pastilla de desplazamiento del cuadro de texto, a caballo sobre el borde
     * derecho: medio dentro y medio fuera.
     *
     * Solo aparece cuando hay algo que desplazar; con el texto entero a la vista
     * sería un adorno que estorba. Va dentro de la capa escalada a propósito:
     * así crece con el pellizco y sigue siendo agarrable con el pin grande.
     */
    /**
     * Mide el contenido a tamaño base y le dice al padre que ocupa ese tamaño
     * **ya multiplicado por el zoom**.
     *
     * Es la pieza que quita las tres fuentes de verdad que traía el pin de
     * texto. Antes el estado decía un tamaño, el contenido le reportaba otro al
     * padre (el base, porque `graphicsLayer` no cambia la disposición) y en
     * pantalla se dibujaba un tercero (el escalado). De ahí venía todo: el
     * cuadro que encogía solo, y el redimensionador que movía el texto sin que
     * la ventana lo siguiera.
     *
     * Las restricciones con las que se mide son EXACTAS y salen del estado, no
     * del padre. Eso corta la realimentación de raíz —el padre ya no puede
     * recortar al hijo— y evita medir con restricciones infinitas, que es lo
     * que revienta en la fase de medida.
     */
    @Composable
    private fun ScaledContent(
        baseWidthPx: Int,
        baseHeightPx: Int?,
        zoom: Float,
        onBaseMeasured: (Int, Int) -> Unit,
        content: @Composable () -> Unit
    ) {
        Layout(content = content) { measurables, _ ->
            val child = measurables.firstOrNull()
                ?: return@Layout layout(0, 0) {}
            val w = baseWidthPx.coerceAtLeast(1)
            // El alto SIEMPRE con tope. Sin él queda en infinito, y un
            // verticalScroll medido con alto infinito lanza IllegalStateException
            // y se lleva la app por delante: es exactamente lo que pasaba con el
            // wrapContentSize(unbounded) que había aquí antes. El tope es la
            // pantalla, que a escala 1 es todo lo alto que un pin puede ser útil.
            val maxH = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val placeable = child.measure(
                if (baseHeightPx != null) {
                    Constraints.fixed(w, baseHeightPx.coerceIn(1, maxH))
                } else {
                    Constraints(minWidth = w, maxWidth = w, maxHeight = maxH)
                }
            )
            onBaseMeasured(placeable.width, placeable.height)
            val z = if (zoom > 0.01f) zoom else 1f
            layout(
                (placeable.width * z).toInt().coerceAtLeast(1),
                (placeable.height * z).toInt().coerceAtLeast(1)
            ) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = z
                    scaleY = z
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }

    @Composable
    private fun BoxScope.ScrollPip(state: ScrollState) {
        val max = state.maxValue
        val density = LocalDensity.current
        if (max <= 0) {
            // Sin nada que desplazar no hay zona que capturar toques.
            LaunchedEffect(Unit) { setScrollRect(null, 1f) }
            return
        }
        val fraction = (state.value.toFloat() / max).coerceIn(0f, 1f)
        val trackHeight = 54.dp
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                // La mitad de su ancho por fuera del borde del cuadro.
                .offset(x = SCROLL_PIP_DP.dp / 2)
                .width(SCROLL_PIP_DP.dp)
                .height(trackHeight)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    RoundedCornerShape(50)
                )
                // La zona que responde al dedo se ensancha a los lados: 10 dp de
                // ancho son imposibles de acertar, y el rectángulo táctil no
                // tiene por qué medir lo mismo que lo que se ve.
                .onGloballyPositioned { coords ->
                    val origin = coords.positionInRoot()
                    val grow = with(density) { SCROLL_TOUCH_PAD_DP.dp.toPx() }
                    val travel = coords.size.height -
                        with(density) { SCROLL_PIP_DP.dp.toPx() }
                    setScrollRect(
                        android.graphics.Rect(
                            (origin.x - grow).toInt(),
                            (origin.y - grow).toInt(),
                            (origin.x + coords.size.width + grow).toInt(),
                            (origin.y + coords.size.height + grow).toInt()
                        ),
                        travel
                    )
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                Modifier
                    .offset(y = (trackHeight - SCROLL_PIP_DP.dp) * fraction)
                    .size(SCROLL_PIP_DP.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }

    /**
     * Edición en el sitio. Se escribe el Markdown en crudo, con sus marcas: es
     * lo que se guarda y lo que se copia, así que es lo que hay que poder tocar.
     */
    @Composable
    private fun TextEditor(s: PinState) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        // Sin botones aquí dentro: con texto largo quedaban empujados fuera de
        // la vista y no había forma de cerrar la edición. Están en EditBarContent.
        Column(Modifier.fillMaxWidth()) {
            BasicTextField(
                value = draft.value,
                onValueChange = { draft.value = it },
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .focusRequester(focus)
            )
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

        /** Diámetro de la pastilla de desplazamiento del cuadro de texto, en dp. */
        const val SCROLL_PIP_DP = 10

        /** Cuánto se ensancha su zona táctil respecto a lo que se ve, en dp. */
        const val SCROLL_TOUCH_PAD_DP = 10

        /** Lado mínimo de un pin: por debajo de esto deja de poder agarrarse. */
        const val MIN_PIN_DP = 24

        /**
         * Tope al sacar «todas» las páginas. Un PDF de doscientas páginas son
         * doscientas ventanas overlay: el sistema no lo aguanta y el usuario
         * tampoco lo quiere.
         */
        const val MAX_PAGES_AT_ONCE = 20
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

/**
 * Pegatinas disponibles. Es una lista fija y no el teclado de emojis del
 * sistema porque una ventana overlay no es enfocable y no puede abrirlo.
 */
private val PIN_EMOJIS = listOf(
    "⭐", "🔥", "❗", "✅", "❌", "⏳",
    "📌", "💡", "🔒", "💰", "📈", "🎯",
    "❤️", "👀", "🔔", "📅", "✏️", "🧠",
    "🚀", "🐛", "☕", "🎵", "📷", "🗑️"
)
