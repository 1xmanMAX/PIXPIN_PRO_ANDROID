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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.clipboard.ClipboardPinReader
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.clipboard.PinContent
import com.forge.pixpin.data.PinRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Crea, destruye y gobierna todas las ventanas de pines: hide-all global,
 * historial de cerrados, lista de pines (válvula de escape del click-through)
 * y persistencia en disco.
 */
class OverlayManager(private val app: PixPinApp) {

    private val wm = app.getSystemService(WindowManager::class.java)!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = PinRepository(app)

    private val pins = LinkedHashMap<String, PinWindowController>()
    private val history = ArrayDeque<PinState>()
    private var hiddenAll = false
    private var historyLimit = 10
    private var saveJob: Job? = null

    private var listWindow: OverlayComposeWindow? = null
    private val listState = mutableStateOf<List<PinState>>(emptyList())

    private val callbacks = object : PinWindowController.Callbacks {
        override fun onPinChanged(controller: PinWindowController) = scheduleSave()

        override fun onPinClosed(controller: PinWindowController) {
            pins.remove(controller.id)
            pushHistory(controller.snapshot())
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

    fun pinFromClipboard() {
        if (!Settings.canDrawOverlays(app)) {
            toast(R.string.need_overlay_toast)
            return
        }
        when (val content = ClipboardPinReader(app).read()) {
            is PinContent.Empty -> toast(R.string.nothing_to_pin)
            is PinContent.TextPin -> createPin(
                PinState(id = newId(), type = PinType.TEXT, text = content.text,
                    x = cascadeX(), y = cascadeY())
            )
            is PinContent.ColorPin -> createPin(
                PinState(id = newId(), type = PinType.COLOR, colorArgb = content.argb,
                    x = cascadeX(), y = cascadeY())
            )
            is PinContent.ImageUri -> scope.launch {
                val path = withContext(Dispatchers.IO) {
                    ImageStore.importFromUri(app, Uri.parse(content.uriString))
                }
                if (path != null) {
                    createPin(PinState(id = newId(), type = PinType.IMAGE, imagePath = path,
                        x = cascadeX(), y = cascadeY()))
                } else {
                    toast(R.string.pin_image_error)
                }
            }
            is PinContent.FileUri -> pinFromSharedUri(Uri.parse(content.uriString))
        }
    }

    /** Compartido desde otra app o URI de archivo en portapapeles. */
    fun pinFromSharedUri(uri: Uri) {
        if (!Settings.canDrawOverlays(app)) {
            toast(R.string.need_overlay_toast)
            return
        }
        val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        scope.launch {
            if (mime == null || mime.startsWith("image/")) {
                val path = withContext(Dispatchers.IO) { ImageStore.importFromUri(app, uri) }
                if (path != null) {
                    pinImage(path)
                    return@launch
                }
            }
            val imported = withContext(Dispatchers.IO) { FileStore.importFromUri(app, uri) }
            if (imported != null) {
                pinFile(imported.path, imported.name, imported.mime)
            } else {
                toast(R.string.pin_image_error)
            }
        }
    }

    /** Crea un pin (imagen desde captura). Usado por CaptureActivity en M5. */
    fun pinImage(imagePath: String) {
        if (!Settings.canDrawOverlays(app)) return
        createPin(PinState(id = newId(), type = PinType.IMAGE, imagePath = imagePath,
            x = cascadeX(), y = cascadeY()))
    }

    /** Crea un pin de archivo (documento compartido a PixPin). */
    fun pinFile(filePath: String, fileName: String, mimeType: String) {
        if (!Settings.canDrawOverlays(app)) return
        createPin(
            PinState(
                id = newId(), type = PinType.FILE,
                filePath = filePath, fileName = fileName, mimeType = mimeType,
                x = cascadeX(), y = cascadeY()
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

    // ---- Ocultar / mostrar todo ----

    fun toggleHideAll() {
        hiddenAll = !hiddenAll
        pins.values.forEach { if (hiddenAll) it.hideView() else it.show() }
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
        if (pins.isNotEmpty()) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.loadPins() }
            val loadedHistory = withContext(Dispatchers.IO) { repo.loadHistory() }
            history.addAll(loadedHistory)
            loaded.forEach { state ->
                val controller = PinWindowController(app, state, callbacks)
                pins[state.id] = controller
                controller.show()
            }
        }
    }

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        val window = OverlayComposeWindow(app) { PinListContent() }
        listWindow = window
        runCatching {
            wm.addView(window.view, lp)
            window.onAttached()
        }
    }

    private fun closePinList() {
        listWindow?.let {
            runCatching { wm.removeView(it.view) }
            it.onDetached()
        }
        listWindow = null
    }

    private fun refreshPinList() {
        if (listWindow != null) {
            listState.value = pins.values.map { it.snapshot() }
        }
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
                            .verticalScroll(rememberScrollState())
                    ) {
                        items.forEach { pin ->
                            PinListRow(pin)
                        }
                    }
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

    private fun cascadeX(): Int {
        cascadeCounter++
        return 120 + (cascadeCounter % 5) * 32
    }

    private fun cascadeY(): Int = 160 + (cascadeCounter % 5) * 32

    private fun newId(): String = UUID.randomUUID().toString()

    private fun toast(resId: Int) {
        Toast.makeText(app, resId, Toast.LENGTH_SHORT).show()
    }
}
