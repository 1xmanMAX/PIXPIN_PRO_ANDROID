package com.forge.pixpin.guardados

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * **Escanear desde el clip del chat con «Escáner Pro Max»** (23-oct-2026, lo pidió el usuario).
 *
 * El escáner es otra aplicación suya (`com.scannerpromax`) y aquí se usa como si fuera de PixPin:
 * «Escanear» en la hoja de adjuntar abre directamente su cámara, y lo escaneado entra solo en el
 * chat, como un archivo elegido con «Un archivo». No hay cuadro intermedio: se pide `REVIEW=false`
 * y el escáner vuelve en cuanto se acaba.
 *
 * **Se pide PDF, no JPEG.** Un documento de varias páginas en PDF es *un* mensaje que se abre en el
 * lector de PDF de PixPin (nítido, anotable, se puede pasar a las hojas de un proyecto) y que
 * [MensajesStore.copiarAdjunto] aligera al entrar. En JPEG serían N fotos sueltas en el chat, una
 * por página, sin orden de documento. Lo que se pierde es la miniatura en línea de un escaneo de
 * una sola hoja; antes de escanear no se sabe cuántas hojas habrá, así que no se puede elegir por
 * eso.
 *
 * Aquí está lo que no necesita la pantalla —armar la petición y leer la respuesta— para poder
 * probarlo sin aparato. Ver `EscanerProMaxTest`.
 */
object EscanerProMax {
    const val PAQUETE = "com.scannerpromax"
    const val ACCION = "com.scannerpromax.action.SCAN"

    const val EXTRA_MODE = "com.scannerpromax.extra.MODE"
    const val EXTRA_FORMAT = "com.scannerpromax.extra.FORMAT"
    const val EXTRA_MULTI_PAGE = "com.scannerpromax.extra.MULTI_PAGE"
    const val EXTRA_REVIEW = "com.scannerpromax.extra.REVIEW"
    const val EXTRA_CALLER_LABEL = "com.scannerpromax.extra.CALLER_LABEL"
    const val EXTRA_URIS = "com.scannerpromax.extra.URIS"
    const val EXTRA_MIME_TYPE = "com.scannerpromax.extra.MIME_TYPE"
    const val EXTRA_PAGE_COUNT = "com.scannerpromax.extra.PAGE_COUNT"

    /** Cómo se presenta PixPin en el escáner («Adjuntar a PixPin»). */
    const val QUIEN_LLAMA = "PixPin"

    /** La petición: cámara directa, documento de varias páginas, en PDF y sin revisar. */
    fun peticion(): Intent = Intent(ACCION)
        .setPackage(PAQUETE)
        .putExtra(EXTRA_MODE, "document")
        .putExtra(EXTRA_FORMAT, "pdf")
        .putExtra(EXTRA_MULTI_PAGE, true)
        .putExtra(EXTRA_REVIEW, false)
        .putExtra(EXTRA_CALLER_LABEL, QUIEN_LLAMA)

    /** Si hay un escáner que conteste a la petición. Necesita el `<queries>` del manifiesto. */
    fun estaInstalado(pm: PackageManager): Boolean =
        peticion().resolveActivity(pm) != null

    /** Lo que volvió del escáner. */
    sealed interface Resultado {
        /** Canceló, o volvió sin nada que adjuntar: no se hace nada. */
        data object Nada : Resultado

        /** Los archivos, en orden y sin repetir, con su tipo si lo dijo. */
        data class Archivos(val uris: List<Uri>, val tipo: String?, val paginas: Int) : Resultado
    }

    /** Lee la respuesta de `setResult`. Ver [archivosDe]. */
    fun resultado(codigo: Int, data: Intent?): Resultado {
        if (codigo != Activity.RESULT_OK || data == null) return Resultado.Nada
        val clip = data.clipData
        val delClip = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        val delExtra = runCatching { IntentCompat.getParcelableArrayListExtra(data, EXTRA_URIS, Uri::class.java) }.getOrNull()
        val uris = archivosDe(delClip, delExtra, data.data)
        if (uris.isEmpty()) return Resultado.Nada
        val tipo = data.getStringExtra(EXTRA_MIME_TYPE) ?: data.type
        return Resultado.Archivos(uris, tipo, data.getIntExtra(EXTRA_PAGE_COUNT, uris.size))
    }

    /**
     * **De dónde salen los archivos**, por orden de confianza: el `ClipData` (es el que lleva el
     * permiso de lectura de todos), luego el extra `URIS`, luego `data` (solo el primero). El
     * escáner manda los mismos en los tres sitios; se juntan sin repetir por si uno viene
     * incompleto, conservando el orden de las páginas.
     */
    fun <T : Any> archivosDe(delClip: List<T?>, delExtra: List<T?>?, data: T?): List<T> =
        (delClip + delExtra.orEmpty() + listOf(data)).filterNotNull().distinct()
}
