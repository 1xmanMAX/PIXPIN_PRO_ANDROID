package com.forge.pixpin.motor

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Dónde vive un dibujo y cómo se intercambia con excalidraw.com.
 *
 * Mismo trato que `CroquisStore`: **un archivo por pin**, fuera del `PinState`,
 * porque el estado de los pines se lee entero al arrancar y meterle cientos de
 * elementos encarecería justo el arranque.
 *
 * Se guarda comprimido. La idea es del plugin de Excalidraw para Obsidian, que
 * mete la escena en un bloque `compressed-json` por lo mismo: una escena con
 * mil elementos son cientos de kB de JSON muy repetitivo, y gzip se lo come.
 */
object ExcalidrawStore {

    /**
     * Sube en cada escritura para que el pin flotante se entere.
     *
     * El editor es otra actividad y escribe por su cuenta; como viven en el
     * mismo proceso, basta con que esto sea estado de Compose.
     */
    val revision: MutableIntState = mutableIntStateOf(0)

    /**
     * Lo mismo, pero **por dibujo**.
     *
     * El contador global vale para quien solo mira —se recompone de más y como
     * mucho relee un archivo—, pero no para el pin de imagen: allí la escena
     * vive en memoria dentro de un controlador con su historial, y recargarla
     * porque *otro* pin ha guardado tiraría el deshacer de lo que estabas
     * dibujando. Con un contador por id, cada pin solo reacciona a lo suyo.
     */
    private val revisiones = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    fun revisionDe(id: String): Int = revisiones[id] ?: 0

    private fun dir(context: Context): File =
        File(context.filesDir, "pins/draw").apply { mkdirs() }

    /** Carpeta de las imágenes incrustadas en los dibujos. */
    private fun filesDir(context: Context): File =
        File(context.filesDir, "pins/draw/files").apply { mkdirs() }

    /** Ruta deducible a partir del id del pin, igual que en el croquis. */
    fun rutaDe(context: Context, id: String): String =
        File(dir(context), "$id.excalidraw.gz").absolutePath

    /** Escribe la escena y devuelve su ruta, o null si no se pudo. */
    fun guardar(context: Context, id: String, scene: Scene): String? = runCatching {
        val destino = File(dir(context), "$id.excalidraw.gz")
        val temporal = File(dir(context), "$id.excalidraw.gz.tmp")

        // Se escribe a un temporal y se renombra: en esta aplicación el proceso
        // muere sin avisar más de lo normal, y un archivo a medias no abre.
        val texto = ExcalidrawJson.encodeToString(scene.copy(elements = purgeDeleted(scene.elements)))
        GZIPOutputStream(temporal.outputStream()).use { it.write(texto.toByteArray()) }

        if (destino.exists()) destino.delete()
        if (!temporal.renameTo(destino)) return null
        revision.intValue++
        revisiones[id] = revisionDe(id) + 1
        destino.absolutePath
    }.getOrNull()

    fun cargar(path: String?): Scene? = runCatching {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        val texto = GZIPInputStream(file.inputStream()).use { it.readBytes().decodeToString() }
        ExcalidrawJson.decodeFromString<Scene>(texto)
    }.getOrNull()

    fun borrar(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    /** Copia un archivo de imagen al almacén de la escena y lo registra. */
    fun guardarImagen(context: Context, origen: File, mimeType: String): SceneFile? =
        runCatching {
            val id = randomId()
            val destino = File(filesDir(context), id)
            origen.copyTo(destino, overwrite = true)
            SceneFile(id, mimeType, destino.absolutePath, System.currentTimeMillis())
        }.getOrNull()

    // ---------------------------------------------------------------------
    // Intercambio con excalidraw.com
    // ---------------------------------------------------------------------

    /**
     * La escena como archivo `.excalidraw` de verdad.
     *
     * Es la red de seguridad de todo el módulo: lo que esta versión nativa no
     * sepa hacer —flechas en codo, mermaid, librerías de formas— se exporta y
     * se termina en el navegador. Por eso el modelo copia el formato en vez de
     * inventarse uno propio.
     *
     * Las imágenes se incrustan como `dataURL` porque el formato lo exige: un
     * `.excalidraw` es un único archivo y una ruta local no significaría nada
     * en otra máquina.
     */
    fun exportar(scene: Scene): String {
        val usados = scene.visible.mapNotNull { it.fileId }.toSet()
        val binarios = scene.files
            .filterKeys { it in usados }
            .mapNotNull { (id, f) ->
                val bytes = runCatching { File(f.path).readBytes() }.getOrNull()
                    ?: return@mapNotNull null
                val b64 = Base64.getEncoder().encodeToString(bytes)
                id to ExcalidrawBinaryFile(
                    id = id,
                    mimeType = f.mimeType,
                    dataURL = "data:${f.mimeType};base64,$b64",
                    created = f.created
                )
            }.toMap()

        return ExcalidrawJson.encodeToString(
            ExcalidrawFile(
                elements = purgeDeleted(scene.elements),
                appState = ExcalidrawAppState(viewBackgroundColor = scene.backgroundColor),
                files = binarios.ifEmpty { null }
            )
        )
    }

    /**
     * Lee un `.excalidraw` venido de fuera.
     *
     * Los `dataURL` se vuelcan a archivos locales: mantenerlos en memoria haría
     * que una escena con cuatro capturas ocupase decenas de megas, y en base64
     * un tercio más de lo que pesan.
     */
    fun importar(context: Context, texto: String): Scene? = runCatching {
        val archivo = ExcalidrawJson.decodeFromString<ExcalidrawFile>(texto)
        if (archivo.type != EXPORT_TYPE) return null

        val ficheros = archivo.files.orEmpty().mapNotNull { (id, bin) ->
            val b64 = bin.dataURL.substringAfter("base64,", "")
            if (b64.isEmpty()) return@mapNotNull null
            val destino = File(filesDir(context), id)
            destino.writeBytes(Base64.getDecoder().decode(b64))
            id to SceneFile(id, bin.mimeType, destino.absolutePath, bin.created)
        }.toMap()

        Scene(
            elements = archivo.elements,
            files = ficheros,
            backgroundColor = archivo.appState.viewBackgroundColor
        )
    }.getOrNull()

    const val EXPORT_TYPE = "excalidraw"
    const val EXPORT_VERSION = 2
    const val MIME_TYPE = "application/vnd.excalidraw+json"
}

/**
 * El archivo `.excalidraw`, tal cual (`ExportedDataState` en `data/types.ts`).
 *
 * `type` y `version` son literales del formato, no elecciones de este proyecto:
 * Excalidraw comprueba los dos al abrir y rechaza el archivo si no cuadran.
 */
@Serializable
data class ExcalidrawFile(
    val type: String = ExcalidrawStore.EXPORT_TYPE,
    val version: Int = ExcalidrawStore.EXPORT_VERSION,
    val source: String = "https://github.com/1xmanMAX/PIXPIN_PRO_ANDROID",
    val elements: List<Element> = emptyList(),
    val appState: ExcalidrawAppState = ExcalidrawAppState(),
    val files: Map<String, ExcalidrawBinaryFile>? = null
)

/**
 * Del `appState` solo se escribe lo que sobrevive a un viaje de ida y vuelta.
 *
 * Lo demás —qué había seleccionado, dónde estaba la vista, qué herramienta
 * estaba activa— es estado de la sesión de quien lo exportó y no significa nada
 * en otra máquina. Excalidraw lo ignora al abrir de todos modos.
 */
@Serializable
data class ExcalidrawAppState(
    val gridSize: Int? = null,
    val viewBackgroundColor: String = "#ffffff"
)

/** Imagen incrustada en un `.excalidraw` (`BinaryFileData`). */
@Serializable
data class ExcalidrawBinaryFile(
    val id: String,
    val mimeType: String,
    val dataURL: String,
    val created: Long = 0L
)
