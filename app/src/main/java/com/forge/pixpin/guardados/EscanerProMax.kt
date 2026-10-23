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
 * **Lo escaneado queda etiquetado** ([Mensaje.escaneado], con el `DOCUMENT_ID` que devuelve el
 * escáner) y se ve con la chapa «Escaneado» en la burbuja. **«Editar en el escáner»** (la chapa, o
 * el menú del mensaje) abre ESE documento en el escáner con la acción `EDIT` ([peticionDeEdicion]);
 * al volver con «Guardar en PixPin» el archivo del mensaje se sustituye por el nuevo.
 *
 * Aquí está lo que no necesita la pantalla —armar la petición y leer la respuesta— para poder
 * probarlo sin aparato. Ver `EscanerProMaxTest`.
 */
object EscanerProMax {
    const val PAQUETE = "com.scannerpromax"
    const val ACCION = "com.scannerpromax.action.SCAN"
    const val ACCION_EDITAR = "com.scannerpromax.action.EDIT"

    const val EXTRA_MODE = "com.scannerpromax.extra.MODE"
    const val EXTRA_FORMAT = "com.scannerpromax.extra.FORMAT"
    const val EXTRA_MULTI_PAGE = "com.scannerpromax.extra.MULTI_PAGE"
    const val EXTRA_REVIEW = "com.scannerpromax.extra.REVIEW"
    const val EXTRA_CALLER_LABEL = "com.scannerpromax.extra.CALLER_LABEL"
    const val EXTRA_URIS = "com.scannerpromax.extra.URIS"
    const val EXTRA_MIME_TYPE = "com.scannerpromax.extra.MIME_TYPE"
    const val EXTRA_PAGE_COUNT = "com.scannerpromax.extra.PAGE_COUNT"
    /** El id del documento en la biblioteca del escáner: viene en el resultado y se pide para editar. */
    const val EXTRA_DOCUMENT_ID = "com.scannerpromax.extra.DOCUMENT_ID"
    /** Por qué volvió cancelado (códigos de abajo). */
    const val EXTRA_ERROR = "com.scannerpromax.extra.ERROR"
    const val ERROR_NO_EXISTE = "document_not_found"
    const val ERROR_ID_INVALIDO = "invalid_document_id"

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

    /**
     * **Abrir un escaneo ya adjuntado en el escáner**, en ese documento: mismo formato que al
     * escanear (PDF de varias páginas), para que lo que vuelva sustituya al archivo tal cual.
     */
    fun peticionDeEdicion(documento: String): Intent = Intent(ACCION_EDITAR)
        .setPackage(PAQUETE)
        .putExtra(EXTRA_DOCUMENT_ID, documento)
        .putExtra(EXTRA_FORMAT, "pdf")
        .putExtra(EXTRA_MULTI_PAGE, true)
        .putExtra(EXTRA_CALLER_LABEL, QUIEN_LLAMA)

    /** Si el escáner instalado sabe editar (versiones de antes del 23-oct-2026 solo escanean). */
    fun sabeEditar(pm: PackageManager): Boolean =
        peticionDeEdicion("x").resolveActivity(pm) != null

    /** La etiqueta para un escaneo que acaba de entrar. */
    fun etiqueta(documento: String?): Escaneado = Escaneado(PAQUETE, documento?.takeIf { it.isNotBlank() })

    /** Si [m] entró escaneado (con este escáner o con otro). */
    fun esEscaneo(m: Mensaje): Boolean = m.escaneado != null

    /** El documento de [m] en el escáner, si se puede reabrir con él. */
    fun documentoDe(m: Mensaje): String? =
        m.escaneado?.takeIf { it.app == PAQUETE }?.documento?.takeIf { it.isNotBlank() }

    /** Qué hacer al pulsar «Editar en el escáner». Ver [queHacerAlEditar]. */
    enum class AlEditar { ABRIR, FALTA_EL_ESCANER, ESCANER_ANTIGUO, SIN_DOCUMENTO }

    /**
     * **Qué pasa al pulsar «Editar en el escáner»**: sin escáner, se dice que falta; con uno de
     * antes de que supiera editar, que hay que ponerlo al día; sin documento que reabrir (un escaneo
     * de antes de la etiqueta con id), que no se puede y se ofrece escanear otra vez.
     */
    fun queHacerAlEditar(instalado: Boolean, sabeEditar: Boolean, documento: String?): AlEditar = when {
        !instalado -> AlEditar.FALTA_EL_ESCANER
        documento.isNullOrBlank() -> AlEditar.SIN_DOCUMENTO
        !sabeEditar -> AlEditar.ESCANER_ANTIGUO
        else -> AlEditar.ABRIR
    }

    /**
     * **El mensaje con su archivo nuevo**, tras editarlo en el escáner: otra ruta, otro peso y, si
     * cambió la extensión, otro nombre; la etiqueta se queda (con el id que devolvió, o el de antes).
     * Al cambiar la ruta y el peso cambia también su resumen, y la sincronización lo manda.
     */
    fun conArchivoNuevo(m: Mensaje, ruta: String, bytes: Long, nombreNuevo: String, documento: String?): Mensaje {
        val ext = { n: String -> n.substringAfterLast('.', "").lowercase() }
        val nombre = if (m.nombre.isNotBlank() && ext(m.nombre) == ext(nombreNuevo)) m.nombre else nombreNuevo
        return m.copy(
            ruta = ruta,
            bytes = bytes,
            nombre = nombre,
            escaneado = etiqueta(documento ?: documentoDe(m))
        )
    }

    /** Lo que volvió del escáner. */
    sealed interface Resultado {
        /** Canceló, o volvió sin nada que adjuntar: no se hace nada. */
        data object Nada : Resultado

        /**
         * Los archivos, en orden y sin repetir, con su tipo si lo dijo, y el id del documento en la
         * biblioteca del escáner ([documento]) si lo dio.
         */
        data class Archivos(val uris: List<Uri>, val tipo: String?, val paginas: Int, val documento: String? = null) : Resultado

        /** Al editar: el documento ya no está en el escáner (se borró, o se reinstaló). */
        data object NoExiste : Resultado
    }

    /** Lee la respuesta de `setResult`. Ver [archivosDe]. */
    fun resultado(codigo: Int, data: Intent?): Resultado {
        if (codigo != Activity.RESULT_OK || data == null) {
            val error = runCatching { data?.getStringExtra(EXTRA_ERROR) }.getOrNull()
            return if (motivoEsQueNoExiste(error)) Resultado.NoExiste else Resultado.Nada
        }
        val clip = data.clipData
        val delClip = if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        val delExtra = runCatching { IntentCompat.getParcelableArrayListExtra(data, EXTRA_URIS, Uri::class.java) }.getOrNull()
        val uris = archivosDe(delClip, delExtra, data.data)
        if (uris.isEmpty()) return Resultado.Nada
        val tipo = data.getStringExtra(EXTRA_MIME_TYPE) ?: data.type
        val documento = runCatching { data.getStringExtra(EXTRA_DOCUMENT_ID) }.getOrNull()?.takeIf { it.isNotBlank() }
        return Resultado.Archivos(uris, tipo, data.getIntExtra(EXTRA_PAGE_COUNT, uris.size), documento)
    }

    /** Si el motivo de un «cancelado» es que el documento no se puede abrir. */
    fun motivoEsQueNoExiste(error: String?): Boolean = error == ERROR_NO_EXISTE || error == ERROR_ID_INVALIDO

    /**
     * **De dónde salen los archivos**, por orden de confianza: el `ClipData` (es el que lleva el
     * permiso de lectura de todos), luego el extra `URIS`, luego `data` (solo el primero). El
     * escáner manda los mismos en los tres sitios; se juntan sin repetir por si uno viene
     * incompleto, conservando el orden de las páginas.
     */
    fun <T : Any> archivosDe(delClip: List<T?>, delExtra: List<T?>?, data: T?): List<T> =
        (delClip + delExtra.orEmpty() + listOf(data)).filterNotNull().distinct()
}
