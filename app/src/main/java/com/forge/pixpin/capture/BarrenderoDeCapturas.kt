package com.forge.pixpin.capture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * **Las capturas del teléfono y su papelera**: la parte de Android de la caducidad
 * ([CaducidadDeCapturas] tiene las reglas). Lista lo que hay en `Pictures/PixPin` (y algún vídeo
 * en `Movies/PixPin`, si lo hubiera), lo lleva a la papelera del sistema y lo devuelve.
 *
 * **La papelera es la de Android** (`IS_TRASHED`, Android 11+): lo que se va desde aquí se
 * recupera desde la galería del teléfono durante 30 días, como en el PC desde la de Windows. Solo
 * se puede marcar sin preguntar lo que escribió PixPin; lo demás (por ejemplo, capturas de antes de
 * reinstalar) lo devuelve [aLaPapelera] como pendiente, y la galería pide permiso con
 * `MediaStore.createTrashRequest`. En Android 10 no hay papelera: se borra.
 *
 * Trabajo de disco: nada de esto en el hilo principal.
 */
object BarrenderoDeCapturas {

    /** Una captura de la galería. [cuando] en ms UTC. */
    data class Captura(
        val uri: Uri,
        val nombre: String,
        val cuando: Long,
        val bytes: Long,
        val mime: String,
        val ancho: Int,
        val alto: Int
    ) {
        val extension: String get() = nombre.substringAfterLast('.', "").lowercase().ifEmpty { mime.substringAfter('/') }
        val esVideo: Boolean get() = mime.startsWith("video/")
    }

    /** Cada cuánto se barre con la aplicación viva. */
    const val CADA_MS = 60 * 60 * 1000L

    /** Donde vive `capturas-caducidad.json`. */
    fun raiz(context: Context): File = context.filesDir

    /**
     * Las capturas, la más nueva primero; a igual hora, por nombre al revés. null si no se pudo
     * preguntar a Android (no es lo mismo que «no hay ninguna»: con null no se olvida nada).
     */
    fun listar(context: Context): List<Captura>? = runCatching {
        val salida = ArrayList<Captura>()
        fun leer(col: Uri, carpetas: List<String>) {
            val donde = carpetas.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
            context.contentResolver.query(
                col,
                arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATE_TAKEN
                ),
                donde, carpetas.map { "%$it%" }.toTypedArray(), null
            )?.use { c ->
                while (c.moveToNext()) salida += Captura(
                    ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "captura",
                    // La hora en que se hizo si se sabe: una captura llegada de otro aparato
                    // la trae en DATE_TAKEN (`CapturasEnElTelefono`); si no, cuando entró aquí.
                    c.getLong(7).takeIf { it > 0 } ?: (c.getLong(2) * 1000), c.getLong(3), c.getString(4) ?: "image/*", c.getInt(5), c.getInt(6)
                )
            }
        }
        leer(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, listOf("Pictures/PixPin"))
        // Android no graba vídeo en PixPin, pero si alguno cae en su carpeta (o en Movies/PixPin)
        // se lista como en el PC, con su chip y su filtro.
        runCatching { leer(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, listOf("Pictures/PixPin", "Movies/PixPin")) }
        salida.sortWith(compareByDescending<Captura> { it.cuando }.thenByDescending { it.nombre })
        salida
    }.onFailure { Log.w("PixPin", "no se pudieron listar las capturas", it) }.getOrNull()

    /**
     * Lleva [uris] a la papelera sin preguntar. Devuelve las que no se pudieron (ajenas a PixPin):
     * para esas, la galería pide permiso con `createTrashRequest`.
     */
    fun aLaPapelera(context: Context, uris: List<Uri>): List<Uri> = uris.filterNot { uri ->
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null) > 0
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
    }

    /** Saca [uris] de la papelera. Devuelve las que no se pudieron (ajenas: hay que pedir permiso). */
    fun devolver(context: Context, uris: List<Uri>): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return uris
        val incluir = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return uris.filterNot { uri ->
            runCatching {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, incluir) > 0
            }.getOrDefault(false)
        }
    }

    /** Si este Android tiene papelera (y por tanto «Deshacer» al borrar). */
    val hayPapelera: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * **Barre**: lo caducado, a la papelera. Devuelve cuántas se fueron. Solo lo que se puede
     * marcar sin preguntar: el barrendero no tiene pantalla a la que pedir permiso, y lo ajeno
     * se queda hasta que se borre desde la galería.
     *
     * A diferencia del PC, aquí **no se olvidan** las conservadas cuyo fichero no aparece: en el
     * PC el nombre `captura-NNNN` se reutiliza, aquí lleva la hora y no; y una lista incompleta
     * (sin permiso para ver las capturas de antes de reinstalar) olvidaría conservadas que siguen
     * ahí, que el siguiente barrido se llevaría. Borrar desde la galería sí las olvida.
     */
    fun barrer(context: Context, ahora: Long, dias: Int): Int {
        if (dias <= 0) return 0
        val lista = listar(context) ?: return 0
        val r = CaducidadDeCapturas.leer(raiz(context), ahora)
        val tocan = CaducidadDeCapturas.caducadas(r, lista.map { it.nombre to it.cuando }, ahora, dias).map { lista[it].uri }
        if (tocan.isEmpty()) return 0
        val idas = tocan.size - aLaPapelera(context, tocan).size
        if (idas > 0) Log.i("PixPin", "capturas caducadas a la papelera: $idas")
        return idas
    }
}
