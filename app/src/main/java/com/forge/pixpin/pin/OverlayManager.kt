package com.forge.pixpin.pin

import android.content.Context
import android.graphics.PixelFormat
import android.view.HapticFeedbackConstants
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoNotTouch
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.clipboard.ClipboardPinReader
import com.forge.pixpin.clipboard.ContentClassifier
import com.forge.pixpin.clipboard.MiniApp
import com.forge.pixpin.clipboard.PinContent
import com.forge.pixpin.data.CrashLog
import com.forge.pixpin.data.PinRepository
import com.forge.pixpin.floating.FloatingBallController
import java.io.File
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

    /** Lista de pines guardados (isPinned = true) para mostrar en la sección de guardados. */
    private val savedPinsState = mutableStateOf<List<PinState>>(emptyList())

    /** Selección de la lista de pines, para agrupar y desagrupar. */
    private val selection = mutableStateOf<Set<String>>(emptySet())

    /** Posición de cada compañero de grupo al empezar el arrastre en curso. */
    private var dragAnchors: Map<String, Pair<Int, Int>> = emptyMap()

    private val callbacks = object : PinWindowController.Callbacks {
        override fun onPinChanged(controller: PinWindowController) = scheduleSave()

        override fun onPinClosed(controller: PinWindowController) {
            pins.remove(controller.id)
            // Si el pin está guardado, no se borra su imagen: sobrevive al cierre.
            if (!controller.isPinned) {
                // Los archivos NO se borran aquí: el pin sigue vivo en el
                // historial y hay que poder restaurarlo. Borrarlos en este punto
                // era lo que hacía que un pin de imagen recuperado saliera como
                // un recuadro rojo de error: quedaba la ficha, no el archivo.
                // Se borran cuando el pin se cae del historial, en pushHistory.
                pushHistory(controller.snapshot())
            } else {
                // El pin guardado persiste en disco aunque se cierre.
                val state = controller.snapshot().copy(minimized = false)
                scope.launch(Dispatchers.IO) { repo.saveSavedPin(state) }
            }
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
            // Los escondidos detrás de la burbuja de su grupo no ocupan hueco.
            val slot = pins.values.count {
                it.isMinimized && !it.isCollapsedInGroup && it !== controller
            }
            controller.moveTo(baseX, baseY + slot * gap)
        }

        /**
         * Un grupo minimizado es UNA burbuja: los demás miembros están
         * escondidos detrás y se restauran juntos.
         */
        override fun onPinMinimizeChanged(controller: PinWindowController, minimized: Boolean) {
            val group = controller.groupId ?: return
            if (syncingGroup) return
            syncingGroup = true
            val others = pins.values.filter { it.groupId == group && it !== controller }

            if (minimized) {
                // Se apunta la disposición del grupo respecto al pin que va a
                // enseñar la burbuja. Si no, al restaurar cada uno volvía a
                // donde estaba de antes mientras que la burbuja sí se había
                // movido: el grupo se deshacía.
                val (ox, oy) = controller.positionBeforeMinimize
                groupOffsets[group] = PinGroups.offsetsFrom(
                    others.associate { it.id to it.position }, ox, oy
                )
                others.forEach { it.minimize(dock = false) }
                collapseGroupBubbles(owner = controller.id)
            } else {
                val offsets = groupOffsets.remove(group).orEmpty()
                val (ox, oy) = controller.position
                // Cada uno vuelve a su sitio RELATIVO a donde haya acabado la
                // burbuja, así que mover el grupo minimizado mueve el grupo
                // entero y la disposición se conserva.
                val places = PinGroups.followPositions(offsets, ox, oy)
                others.forEach { other ->
                    other.setCollapsedInGroup(false)
                    other.restore()
                    places[other.id]?.let { (x, y) -> other.moveTo(x, y) }
                }
            }
            syncingGroup = false
            refreshPinList()
        }

        /** Al empezar a arrastrar se apuntan las posiciones de los compañeros de grupo. */
        override fun onPinDragStarted(controller: PinWindowController) {
            val group = controller.groupId
            // Una burbuja se mueve sola: arrastrar la del grupo no debe tirar de
            // los pines escondidos detrás, que se restaurarán donde estaban.
            dragAnchors = if (group == null || controller.isMinimized) {
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

        override fun onPinCopyRequested(controller: PinWindowController) {
            val state = controller.snapshot()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    com.forge.pixpin.capture.PinExporter.copyPin(app, state)
                }
                toast(if (ok) R.string.copied_image else R.string.capture_error)
            }
        }

        override fun onPinImageExtracted(controller: PinWindowController, imagePath: String) {
            pinImage(imagePath)
        }

        override fun onPinToggleSave(controller: PinWindowController) {
            controller.togglePinned()
            if (controller.isPinned) {
                toast(R.string.pin_saved)
                scope.launch(Dispatchers.IO) {
                    repo.saveSavedPin(controller.snapshot())
                }
            } else {
                toast(R.string.pin_unsaved)
                scope.launch(Dispatchers.IO) {
                    repo.removeSavedPin(controller.id)
                }
            }
            refreshPinList()
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
            is PinContent.MiniAppPin -> {
                createMiniApp(content.app)
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
    fun pinImage(imagePath: String, x: Int? = null, y: Int? = null, scale: Float? = null) {
        if (!Settings.canDrawOverlays(app)) return
        val state = if (x != null && y != null && scale != null) {
            PinState(
                id = UUID.randomUUID().toString(),
                type = PinType.IMAGE,
                imagePath = imagePath,
                x = x,
                y = y,
                scale = scale
            )
        } else {
            newPin(PinType.IMAGE).copy(imagePath = imagePath)
        }
        createPin(state)
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

    /**
     * Abre la herramienta que pidió la palabra mágica.
     *
     * La pizarra NO es un tipo de pin nuevo: es un pin de imagen con un lienzo
     * liso generado al vuelo. Así hereda entero el dibujo a mano, el zoom, las
     * anotaciones re-editables y la exportación a la galería, sin una línea de
     * código de dibujo nueva.
     */
    private fun createMiniApp(app: MiniApp) {
        if (app == MiniApp.BOARD) {
            scope.launch {
                val path = withContext(Dispatchers.IO) { ImageStore.saveBlankBoard(this@OverlayManager.app) }
                if (path != null) pinImage(path) else toast(R.string.capture_error)
            }
            return
        }
        val type = when (app) {
            MiniApp.TIMER -> PinType.TIMER
            MiniApp.CHECKLIST -> PinType.CHECKLIST
            MiniApp.COUNTER -> PinType.COUNTER
            MiniApp.LEDGER -> PinType.LEDGER
            MiniApp.BOARD -> return
        }
        val seed = when (app) {
            MiniApp.CHECKLIST -> appString(R.string.checklist_seed)
            MiniApp.LEDGER -> appString(R.string.ledger_seed)
            else -> null
        }
        createPin(newPin(type).copy(text = seed))
    }

    private fun appString(resId: Int): String = app.getString(resId)

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

    /**
     * Red de seguridad: devuelve los pines a la vista si se quedaron ocultos por
     * una captura que murió antes de restaurarlos. Respeta el ocultar-todo, que
     * eso sí lo pidió el usuario a propósito.
     */
    fun recoverVisibility() {
        if (hiddenAll) return
        pins.values.forEach { it.setViewVisible(true) }
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

    /**
     * Mete el pin cerrado en el historial y borra los archivos de los que se
     * caen por el otro extremo.
     *
     * Este es el único sitio donde muere el archivo de un pin cerrado: mientras
     * siga en el historial tiene que poder restaurarse con su contenido, no con
     * un hueco.
     */
    private fun pushHistory(state: PinState) {
        history.addLast(state)
        while (history.size > historyLimit) {
            val dropped = history.removeFirst()
            // Un pin guardado comparte archivo con su copia en saved.json: ese
            // no se toca aunque su paso por el historial haya terminado.
            if (!dropped.isPinned) {
                val image = dropped.imagePath
                val file = dropped.filePath
                scope.launch(Dispatchers.IO) {
                    ImageStore.delete(image)
                    FileStore.delete(file)
                }
            }
        }
        scope.launch(Dispatchers.IO) { repo.saveHistory(history.toList()) }
    }

    // ---- Restauración al arrancar ----

    /**
     * Restaura los pines del arranque anterior.
     *
     * **Modo seguro:** si el arranque anterior acabó en fallo, NO se restaura
     * nada. Un pin que revienta al dibujarse deja la app inservible para
     * siempre: casca, se guarda tal cual, y al abrir se vuelve a crear y a
     * cascar. Sin esta puerta no hay forma de entrar a la app ni siquiera para
     * mandar el informe del fallo.
     *
     * No se borra nada: los pines siguen en disco y vuelven en cuanto se
     * descarte el informe desde la pantalla principal.
     */
    fun restoreOnStart() {
        if (pins.isNotEmpty() || !Settings.canDrawOverlays(app)) return
        if (CrashLog.hasReport(app)) {
            toast(R.string.safe_mode)
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.loadPins() }
            val loadedHistory = withContext(Dispatchers.IO) { repo.loadHistory() }
            history.clear()
            history.addAll(loadedHistory)
            loaded.forEach { state ->
                // No restaurar pines de imagen si la imagen ya no existe en disco
                if (state.type == PinType.IMAGE) {
                    if (state.imagePath == null || !withContext(Dispatchers.IO) { File(state.imagePath).exists() }) {
                        return@forEach
                    }
                }
                val controller = PinWindowController(app, state, callbacks)
                pins[state.id] = controller
                controller.show()
                // Escalona la creación: 20 ventanas de golpe atascan el hilo de UI.
                delay(16)
            }
            collapseGroupBubbles()
            refreshPinList()
        }
    }

    /** Restaura un pin guardado desde la lista de guardados (lo muestra en pantalla). */
    fun restoreSavedPin(state: PinState) {
        if (!Settings.canDrawOverlays(app)) return
        // Si ya está activo, solo lo traemos al frente.
        if (state.id in pins) return
        val controller = PinWindowController(app, state, callbacks)
        pins[state.id] = controller
        controller.show()
        saveNow()
        refreshPinList()
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
        selection.value.forEach {
            pins[it]?.setGroup(null)
            // Fuera del grupo vuelve a tener su propia burbuja.
            pins[it]?.setCollapsedInGroup(false)
        }
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

    /** Evita que la propagación a los compañeros de grupo se dispare a sí misma. */
    private var syncingGroup = false

    /**
     * Disposición de cada grupo minimizado: dónde estaba cada miembro respecto
     * al que enseña la burbuja. Es lo que permite mover el grupo en burbuja y
     * que al desplegarlo mantenga su forma.
     */
    private val groupOffsets = mutableMapOf<String, Map<String, Pair<Int, Int>>>()

    /**
     * Deja una sola burbuja por grupo minimizado. Hace falta al restaurar los
     * pines al arrancar: en disco cada miembro guarda su propio "minimizado".
     */
    private fun collapseGroupBubbles(owner: String? = null) {
        pins.values.filter { it.groupId != null }
            .groupBy { it.groupId }
            .forEach { (_, members) ->
                val minimized = members.filter { it.isMinimized }
                val hidden = PinGroups.collapsedIds(minimized.map { it.id }, owner)
                minimized.forEach { it.setCollapsedInGroup(it.id in hidden) }
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

    /**
     * Alterna la prioridad de un pin. Va por dos caminos porque el pin puede
     * estar vivo en pantalla o ser un guardado que ya se cerró: en el primer
     * caso manda el controlador, en el segundo el archivo de guardados.
     */
    private fun togglePriority(pinId: String) {
        val active = pins[pinId]
        if (active != null) {
            val state = active.snapshot()
            active.updateState(state.copy(priority = !state.priority))
            saveNow()
            if (active.isPinned) {
                scope.launch(Dispatchers.IO) { repo.saveSavedPin(active.snapshot()) }
            }
            refreshPinList()
            return
        }
        scope.launch(Dispatchers.IO) {
            val saved = repo.loadSavedPins().map {
                if (it.id == pinId) it.copy(priority = !it.priority) else it
            }
            repo.saveSavedPins(saved)
            refreshPinList()
        }
    }

    private fun refreshPinList() {
        listState.value = pins.values.map { it.snapshot() }
        // Carga los pines guardados del disco para la sección de guardados.
        scope.launch(Dispatchers.IO) {
            val saved = repo.loadSavedPins()
            // Filtra los que ya están activos para no duplicarlos.
            val activeIds = pins.keys
            savedPinsState.value = saved.filter { it.id !in activeIds }
        }
        // Los pines cerrados no pueden seguir marcados.
        selection.value = selection.value.intersect(pins.keys)
    }

    @Composable
    private fun PinListContent() {
        val items = listState.value
        val savedItems = savedPinsState.value

        // Agrupar por tipo
        val groupedByType: Map<PinType, List<PinState>> = items.groupBy { it.type }
        val typeOrder = listOf(PinType.IMAGE, PinType.TEXT, PinType.COLOR, PinType.FILE)

        Card(modifier = Modifier.width(360.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header
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

                if (items.isEmpty() && savedItems.isEmpty()) {
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
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // --- Sección de pines guardados ---
                        if (savedItems.isNotEmpty()) {
                            SectionHeader(
                                title = app.getString(R.string.saved_pins_section),
                                count = savedItems.size,
                                color = MaterialTheme.colorScheme.primary
                            )
                            savedItems.forEach { pin ->
                                SavedPinRow(pin)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }

                        // --- Pines activos agrupados por tipo ---
                        var firstGroup = true
                        typeOrder.forEach { type ->
                            val groupItems = groupedByType[type].orEmpty()
                            if (groupItems.isNotEmpty()) {
                                if (!firstGroup) {
                                    Spacer(Modifier.height(4.dp))
                                }
                                firstGroup = false
                                SectionHeader(
                                    title = typeHeader(type),
                                    count = groupItems.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                groupItems.forEach { pin ->
                                    PinListRow(pin)
                                }
                            }
                        }
                    }
                    GroupActions(items)
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(title: String, count: Int, color: androidx.compose.ui.graphics.Color) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.7f)
            )
        }
    }

    private fun typeHeader(type: PinType): String = when (type) {
        PinType.IMAGE -> "📷 " + app.getString(R.string.pin_type_image)
        PinType.TEXT -> "📝 " + app.getString(R.string.pin_type_text_plural)
        PinType.COLOR -> "🎨 " + app.getString(R.string.pin_type_color_plural)
        PinType.FILE -> "📁 " + app.getString(R.string.pin_type_file_plural)
        PinType.TIMER -> "⏱ " + app.getString(R.string.pin_type_timer)
        PinType.CHECKLIST -> "☑ " + app.getString(R.string.pin_type_checklist)
        PinType.COUNTER -> "🔢 " + app.getString(R.string.pin_type_counter)
        PinType.LEDGER -> "💶 " + app.getString(R.string.pin_type_ledger)
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
        val groupColor = pin.groupId?.let { Color(PinGroups.colorFor(it)) }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .then(
                        if (groupColor != null) Modifier.padding(start = 4.dp)
                        else Modifier
                    )
            ) {
                if (groupColor != null) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(24.dp)
                            .padding(end = 4.dp)
                            .background(groupColor, RoundedCornerShape(2.dp))
                    )
                }
                val checked = pin.id in selection.value
                IconButton(onClick = {
                    selection.value = if (checked) selection.value - pin.id
                    else selection.value + pin.id
                }) {
                    Icon(
                        if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = app.getString(R.string.cd_select_pin),
                        tint = groupColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    when (pin.type) {
                        PinType.IMAGE -> Icons.Filled.Image
                        PinType.TEXT -> Icons.Filled.TextFields
                        PinType.COLOR -> Icons.Filled.Palette
                        PinType.FILE -> Icons.Filled.InsertDriveFile
                        else -> Icons.Filled.Widgets
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                PinLabel(
                    pin = pin,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                IconButton(onClick = {
                    pins[pin.id]?.let { controller ->
                        controller.togglePinned()
                        if (controller.isPinned) {
                            scope.launch(Dispatchers.IO) { repo.saveSavedPin(controller.snapshot()) }
                        } else {
                            scope.launch(Dispatchers.IO) { repo.removeSavedPin(controller.id) }
                        }
                        refreshPinList()
                    }
                }) {
                    Icon(
                        if (pin.isPinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = app.getString(
                            if (pin.isPinned) R.string.cd_unbookmark else R.string.cd_bookmark
                        ),
                        tint = if (pin.isPinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
    }

    @Composable
    private fun SavedPinRow(pin: PinState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    when (pin.type) {
                        PinType.IMAGE -> Icons.Filled.Image
                        PinType.TEXT -> Icons.Filled.TextFields
                        PinType.COLOR -> Icons.Filled.Palette
                        PinType.FILE -> Icons.Filled.InsertDriveFile
                        else -> Icons.Filled.Widgets
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                PinLabel(
                    pin = pin,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                )
                IconButton(onClick = { restoreSavedPin(pin) }) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = app.getString(R.string.cd_restore_pin),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = {
                    scope.launch(Dispatchers.IO) { repo.removeSavedPin(pin.id) }
                    refreshPinList()
                }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    /**
     * El nombre del pin en la lista, con su pulsación larga para alternar la
     * prioridad. Lo comparten la fila de activos y la de guardados: eran dos
     * copias del mismo bloque y se desincronizaban a cada cambio.
     */
    @Composable
    private fun PinLabel(pin: PinState, modifier: Modifier = Modifier) {
        val view = LocalView.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.pointerInput(pin.id) {
                detectTapGestures(onLongPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    togglePriority(pin.id)
                })
            }
        ) {
            pin.emoji?.let {
                // Sin rotar: la pegatina se inclina sobre el pin, no en una lista.
                Text(text = it, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
            }
            if (pin.priority && pin.type != PinType.IMAGE) {
                PriorityChip()
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = labelFor(pin),
                style = MaterialTheme.typography.bodySmall,
                color = if (pin.priority) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun PriorityChip() {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = app.getString(R.string.priority_chip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }

    /**
     * En la imagen el nombre ES el estado: no lleva chip, cambia entero. Antes
     * todas las imágenes se llamaban igual y seis capturas eran seis filas
     * idénticas.
     */
    private fun labelFor(pin: PinState): String = when (pin.type) {
        PinType.IMAGE -> app.getString(
            if (pin.priority) R.string.pin_image_priority else R.string.pin_image_default
        )
        PinType.COLOR -> pin.colorArgb?.let { ContentClassifier.toHex(it) } ?: "Color"
        PinType.TEXT -> pin.text.orEmpty().replace('\n', ' ').take(30)
        PinType.FILE -> pin.fileName ?: app.getString(R.string.pin_type_file)
        PinType.TIMER -> app.getString(R.string.pin_type_timer)
        PinType.CHECKLIST -> app.getString(R.string.pin_type_checklist)
        PinType.COUNTER -> app.getString(R.string.pin_type_counter) + " ${pin.widget.count}"
        PinType.LEDGER -> app.getString(R.string.pin_type_ledger)
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
