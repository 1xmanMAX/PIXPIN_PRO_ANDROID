package com.forge.pixpin.guardados

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * **Meter algo de fuera en un chat**: copia el archivo al almacén y lo da de alta como foto, nota
 * de voz o archivo, según su tipo (lo mismo que «Compartir → Mensajes guardados»). Lo usan la
 * galería de capturas y el recuadro de soltar, como en el PC. Trabajo de disco.
 */
object AlChat {

    /** Mete [uri] en el chat [proyecto] (null = el general). Devuelve si entró. */
    fun meter(context: Context, uri: Uri, proyecto: String?, nombrePorOmision: String = "archivo"): Boolean = runCatching {
        val r = context.contentResolver
        val nombre = runCatching {
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: nombrePorOmision
        val tipo = r.getType(uri)
        val temporal = File(context.cacheDir, "alchat_${System.currentTimeMillis()}_${nombre.hashCode()}")
        r.openInputStream(uri)?.use { e -> temporal.outputStream().use { e.copyTo(it) } } ?: return false
        meterArchivo(context, temporal, nombre, tipo, proyecto).also { temporal.delete() }
    }.getOrDefault(false)

    fun meterArchivo(
        context: Context, archivo: File, nombre: String, tipo: String?, proyecto: String?,
        /** Lo que llega del PC entra tal cual, como un envío: un PDF no se aligera. */
        aligerar: Boolean = true,
        /** Quién lo mandó, para el «recibido de» de la burbuja. */
        recibidoDe: String? = null
    ): Boolean = runCatching {
        val almacen = MensajesStore(context)
        val ext = tipo?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val ruta = almacen.copiarAdjunto(archivo, nombre, ext, aligerar = aligerar) ?: return false
        val esImagen = tipo?.startsWith("image/") == true ||
            ruta.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "bmp")
        val esAudio = tipo?.startsWith("audio/") == true ||
            ruta.substringAfterLast('.', "").lowercase() in setOf("m4a", "mp3", "ogg", "oga", "opus", "wav", "flac", "aac", "amr", "3gp")
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(),
                clase = if (esImagen) Clase.IMAGEN else if (esAudio) Clase.VOZ else Clase.ARCHIVO,
                ruta = ruta, nombre = nombre, bytes = File(ruta).length(),
                duracionMs = if (esAudio) com.forge.pixpin.pin.Voz.duracion(ruta) else 0,
                proyecto = proyecto,
                recibidoDe = recibidoDe
            )
        )
        true
    }.getOrDefault(false)

    /** Un texto como nota del chat. */
    fun meterTexto(context: Context, texto: String, proyecto: String?) {
        if (texto.isBlank()) return
        MensajesStore(context).anadir(
            Mensaje(id = UUID.randomUUID().toString(), cuando = System.currentTimeMillis(), clase = Clase.NOTA, texto = texto.trim(), proyecto = proyecto)
        )
    }
}
