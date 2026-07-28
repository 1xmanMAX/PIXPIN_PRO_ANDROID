package com.forge.pixpin.pin

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.clipboard.ClipboardPinReader
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.clipboard.PinContent
import com.forge.pixpin.data.PinRepository
import com.forge.pixpin.floating.FloatingBallController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Crea, destruye y gobierna todas las ventanas de pines: ocultar-todo,
 * historial de cerrados, lista de pines (válvula de escape del click-through)
 * y persistencia en disco.
 */
class OverlayManager(private val app: PixPinApp) {

    private val wm = app.getSystemService(WindowManager::class.java)!!
    private val scope = app.scope
    private val repo = PinRepository(app)

    private val pins = LinkedHashMap<String, PinWindowController>()
    private val history = ArrayDeque<PinState>()
    private var hiddenAll = false
    private var historyLimit = 10
    private var saveJob: Job? = null

    private var listWindow: OverlayComposeWindow? = null
    private val listState = mutableStateOf<List<PinState>>(emptyList())

    /** Selección de la lista de pines, para agrupar y desagrupar. */
    private val selection = mutableStateOf<Set<String>>(emptySet())

    /** Posición de cada compañero de grupo al empezar el arrastre en curso. */
    private var dragAnchors: Map<String, Pair<Int, Int>> = emptyMap()

    private val callbacks = object : PinWindowController.Callbacks {
        override fun onPinChanged(controller: PinWindowController) = scheduleSave()

        override fun onPinClosed(controller: PinWindowController) {
            pins.remove(controller.id)
            pushHistory(controller.snapshot())
            // Un grupo se cierra entero: cerrar solo una parte dejaría el resto
            // huérfano en pantalla sin forma evidente de recuperarlo junto.
            controller.groupId?.let { closeRestOfGroup(it) }
            saveNow()
            refreshPinList()
        }

        override fun onPinDestroyed(controller: PinWindowController) {
            pins.remove(controller.id)
            val state = controller.snapshot()
            ImageStore.delete(state.imagePath)
            FileStore.delete(state.filePath)
            saveNow()
            refreshPinList()
        }

        /**
         * Aparca la burbuja en una columna junto a la bola, para que varias
         * minimizadas no queden una encima de otra.
         */
        override fun onPinMinimized(controller: PinWindowController) {
            val density = app.resources.displayMetrics.density
            val gap = (54 * density).toInt()
            val ball = FloatingBallController.active?.ballBounds()
            val baseX = ball?.left ?: (app.resources.displayMetrics.widthPixels - (56 * density).toInt())
            val baseY = (ball?.bottom ?: (app.resources.displayMetrics.heightPixels / 3)) + (8 * density).toInt()
            val slot = pins.values.count { it.isMinimized && it !== controller }
            controller.moveTo(baseX, baseY + slot * gap)
        }

        /** Al empezar a arrastrar se apuntan las posiciones de los compañeros de grupo. */
        override fun onPinDragStarted(controller: PinWindowController) {
            val group = controller.groupId
            dragAnchors = if (group == null) {
                emptyMap()
            } else {
                pins.values
                    .filter { it.id != controller.id && it.groupId == group }
                    .associate { val s = it.snapshot(); it.id to (s.x to s.y) }
            }
        }

        override fun onPinDragged(controller: PinWindowController, dx: Int, dy: Int) {
            if (dragAnchors.isEmpty()) return
            PinGroups.followPositions(dragAnchors, dx, dy).forEach { (id, pos) ->
                pins[id]?.moveTo(pos.first, pos.second)
            }
        }

        override fun onPinSaveRequested(controller: PinWindowController) {
            val state = controller.snapshot()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    com.forge.pixpin.capture.PinExporter.savePin(app, state)
                }
                toast(if (ok) R.string.saved_to_gallery else R.string.capture_error)
            }
        }
    }

    init {
        scope.launch {
            historyLimit = app.settings.settings.first().historySize
        }
    }

    // ---- Pines desde portapapeles ----

    /**
     * Suspende hasta terminar: quien llama (una actividad transparente) debe
     * seguir viva mientras se importa el contenido, porque el permiso sobre la
     * URI del portapapeles se revoca en cuanto la actividad termina.
     */
    suspend fun pinFromClipboard(): Boolean {
        if (!Settings.canDrawOverlays(app)) {
            toast(R.string.need_overlay_toast)
            return false
        }
        return when (val content = ClipboardPinReader(app).read()) {
            is PinContent.Empty -> {
                toast(R.string.nothing_to_pin)
                false
            }
            is PinContent.TextPin -> {
                createPin(newPin(PinType.TEXT).copy(text = content.text))
                true
            }
            is PinContent.ColorPin -> {
                createPin(newPin(PinType.COLOR).copy(colorArgb = content.argb))
                true
            }
            is PinContent.ImageUri -> pinFromUri(Uri.parse(content.uriString))
            is PinContent.FileUri -> pinFromUri(Uri.parse(content.uriString))
        }
    }

    /** Importa una URI (compartida o del portapapeles) y la convierte en pin. */
    suspend fun pinFromUri(uri: Uri): Boolean {
        if (!Settings.canDrawOverlays(app)) {
            toast(R.string.need_overlay_toast)
            return false
        }
        val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        if (mime == null || mime.startsWith("image/")) {
            val path = withContext(Dispatchers.IO) { ImageStore.importFromUri(app, uri) }
            if (path != null) {
                pinImage(path)
                return true
            }
        }
        val imported = withContext(Dispatchers.IO) { FileStore.importFromUri(app, uri) }
        if (imported != null) {
            pinFile(imported.path, imported.name, imported.mime)
            return true
        }
        toast(R.string.pin_image_error)
        return false
    }

    /** Crea un pin de imagen (desde la captura o desde un archivo importado). */
    fun pinImage(imagePath: String) {
        if (!Settings.canDrawOverlays(app)) return
        createPin(newPin(PinType.IMAGE).copy(imagePath = imagePath))
    }

    /** Crea un pin de archivo (documento compartido a PixPin). */
    fun pinFile(filePath: String, fileName: String, mimeType: String) {
        if (!Settings.canDrawOverlays(app)) return
        createPin(
            newPin(PinType.FILE).copy(
                filePath = filePath, fileName = fileName, mimeType = mimeType
            )
        )
    }

    private fun createPin(state: PinState) {
        val controller = PinWindowController(app, state, callbacks)
        pins[state.id] = controller
        if (!hiddenAll) controller.show()
        saveNow()
        refreshPinList()
    }

    // ---- Visibilidad ----

    /**
     * Oculta/muestra los overlays sin destruirlos. Se usa al capturar (si no,
     * la bola y los pines saldrían dentro de la propia captura) y para el
     * botón "ocultar todo".
     */
    fun setOverlaysVisible(visible: Boolean) {
        if (!visible) closePinList()
        pins.values.forEach { it.setViewVisible(visible && !hiddenAll) }
        FloatingBallController.active?.setVisible(visible)
    }

    fun toggleHideAll() {
        hiddenAll = !hiddenAll
        pins.values.forEach { it.setViewVisible(!hiddenAll) }
        toast(if (hiddenAll) R.string.pins_hidden else R.string.pins_shown)
    }

    val isHiddenAll: Boolean get() = hiddenAll

    // ---- Historial ----

    fun restoreLastClosed() {
        val state = history.removeLastOrNull() ?: run {
            toast(R.string.pin_history_empty)
            return
        }
        scope.launch(Dispatchers.IO) { repo.saveHistory(history.toList()) }
        createPin(state)
    }

    private fun pushHistory(state: PinState) {
        history.addLast(state)
        while (history.size > historyLimit) history.removeFirst()
        scope.launch(Dispatchers.IO) { repo.saveHistory(history.toList()) }
    }

    // ---- Restauración al arrancar ----

    fun restoreOnStart() {
        if (pins.isNotEmpty() || !Settings.canDrawOverlays(app)) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.loadPins() }
            val loadedHistory = withContext(Dispatchers.IO) { repo.loadHistory() }
            history.clear()
            history.addAll(loadedHistory)
            loaded.forEach { state ->
                val controller = PinWindowController(app, state, callbacks)
                pins[state.id] = controller
                controller.show()
                // Escalona la creación: 20 ventanas de golpe atascan el hilo de UI.
                delay(16)
            }
            refreshPinList()
        }
    }

    // ---- Grupos ----

    /** Une los pines seleccionados en un grupo nuevo. */
    private fun groupSelected() {
        val ids = selection.value
        if (ids.size < 2) return
        val groupId = UUID.randomUUID().toString()
        ids.forEach { pins[it]?.setGroup(groupId) }
        selection.value = emptySet()
        saveNow()
        refreshPinList()
    }

    private fun ungroupSelected() {
        selection.value.forEach { pins[it]?.setGroup(null) }
        selection.value = emptySet()
        saveNow()
        refreshPinList()
    }

    /**
     * Cierra el resto del grupo. Se llama desde `onPinClosed`, así que el que
     * disparó el cierre ya no está en el mapa y no hay riesgo de reentrada.
     */
    private fun closeRestOfGroup(groupId: String) {
        if (closingGroup) return // cada cierre reentra aquí; una pasada basta
        closingGroup = true
        pins.values.filter { it.groupId == groupId }.toList().forEach { it.close() }
        closingGroup = false
    }

    private var closingGroup = false

    // ---- Lista de pines (válvula de escape del click-through) ----

    fun togglePinList() {
        if (listWindow != null) closePinList() else openPinList()
    }

    private fun openPinList() {
        refreshPinList()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val window = OverlayComposeWindow(app) { PinListContent() }
        listWindow = window
        runCatching {
            wm.addView(window.view, lp)
            window.onAttached()
        }.onFailure { listWindow = null }
    }

    private fun closePinList() {
        listWindow?.let {
            runCatching { wm.removeView(it.view) }
            it.onDetached()
        }
        listWindow = null
    }

    private fun refreshPinList() {
        listState.value = pins.values.map { it.snapshot() }
        // Los pines cerrados no pueden seguir marcados.
        selection.value = selection.value.intersect(pins.keys)
    }

    @Composable
    private fun PinListContent() {
        val items = listState.value
        Card(modifier = Modifier.width(320.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.getString(R.string.pins_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { restoreLastClosed() }) {
                        Icon(Icons.Filled.Restore, contentDescription = null)
                    }
                    IconButton(onClick = { closePinList() }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                if (items.isEmpty()) {
                    Text(
                        app.getString(R.string.pins_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        items.forEach { pin -> PinListRow(pin) }
                    }
                    GroupActions(items)
                }
            }
        }
    }

    /**
     * Agrupar y desagrupar, solo cuando la selección lo permite: con la lista
     * limpia no hay botones de más estorbando.
     */
    @Composable
    private fun GroupActions(items: List<PinState>) {
        val chosen = items.filter { it.id in selection.value }
        if (chosen.isEmpty()) return
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(
                text = app.getString(R.string.pins_selected, chosen.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            if (PinGroups.canUngroup(chosen)) {
                TextButton(onClick = { ungroupSelected() }) {
                    Text(app.getString(R.string.pins_ungroup))
                }
            }
            if (PinGroups.canGroup(chosen)) {
                TextButton(onClick = { groupSelected() }) {
                    Text(app.getString(R.string.pins_group))
                }
            }
        }
    }

    @Composable
    private fun PinListRow(pin: PinState) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            val checked = pin.id in selection.value
            IconButton(onClick = {
                selection.value = if (checked) selection.value - pin.id
                else selection.value + pin.id
            }) {
                Icon(
                    if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = app.getString(R.string.cd_select_pin),
                    tint = if (pin.groupId != null) Color(PinGroups.colorFor(pin.groupId))
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                when (pin.type) {
                    PinType.IMAGE -> Icons.Filled.Image
                    PinType.TEXT -> Icons.Filled.TextFields
                    PinType.COLOR -> Icons.Filled.Palette
                    PinType.FILE -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = labelFor(pin),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            IconButton(onClick = {
                pins[pin.id]?.setClickThrough(!pin.clickThrough)
                refreshPinList()
            }) {
                Icon(
                    if (pin.clickThrough) Icons.Filled.DoNotTouch else Icons.Filled.TouchApp,
                    contentDescription = app.getString(R.string.cd_clickthrough),
                    tint = if (pin.clickThrough) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { pins[pin.id]?.close() }) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
    }

    private fun labelFor(pin: PinState): String = when (pin.type) {
        PinType.IMAGE -> app.getString(R.string.pin_type_image)
        PinType.COLOR -> pin.colorArgb?.let { ContentClassifier.toHex(it) } ?: "Color"
        PinType.TEXT -> pin.text.orEmpty().replace('\n', ' ').take(30)
        PinType.FILE -> pin.fileName ?: app.getString(R.string.pin_type_file)
    }

    // ---- Persistencia ----

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            saveNow()
        }
    }

    private fun saveNow() {
        val states = pins.values.map { it.snapshot() }
        scope.launch(Dispatchers.IO) { repo.savePins(states) }
    }

    // ---- Utilidades ----

    private var cascadeCounter = 0

    /** Pin nuevo, en cascada para que no se apilen exactamente uno sobre otro. */
    private fun newPin(type: PinType): PinState {
        cascadeCounter++
        val step = (cascadeCounter % 5) * 32
        return PinState(
            id = UUID.randomUUID().toString(),
            type = type,
            x = 120 + step,
            y = 200 + step
        )
    }

    private fun toast(resId: Int) {
        Toast.makeText(app, resId, Toast.LENGTH_SHORT).show()
    }
}
