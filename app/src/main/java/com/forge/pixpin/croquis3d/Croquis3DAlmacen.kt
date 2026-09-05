package com.forge.pixpin.croquis3d

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Dónde vive un croquis del espacio.
 *
 * Mismo trato que las escenas del lienzo: JSON comprimido, escrito a un temporal y
 * renombrado. Lo segundo no es manía — esta aplicación vive detrás de un servicio y el
 * proceso muere sin avisar más de lo normal, y un archivo escrito a medias no abre y se
 * lleva el trabajo de la tarde.
 */
object Croquis3DAlmacen {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "croquis3d").apply { mkdirs() }

    fun ruta(context: Context, id: String): String =
        File(dir(context), "$id.croquis.gz").absolutePath

    fun guardar(context: Context, id: String, croquis: Croquis): String? = runCatching {
        val destino = File(dir(context), "$id.croquis.gz")
        val temporal = File(dir(context), "$id.croquis.gz.tmp")
        val texto = json.encodeToString(Croquis.serializer(), croquis)
        GZIPOutputStream(temporal.outputStream()).use { it.write(texto.toByteArray()) }
        if (destino.exists()) destino.delete()
        if (!temporal.renameTo(destino)) return null
        destino.absolutePath
    }.getOrNull()

    fun cargar(context: Context, id: String): Croquis? = runCatching {
        val archivo = File(dir(context), "$id.croquis.gz")
        if (!archivo.exists()) return null
        val texto = GZIPInputStream(archivo.inputStream()).use { it.readBytes().decodeToString() }
        json.decodeFromString(Croquis.serializer(), texto)
    }.getOrNull()

    /** El de siempre: mientras no haya varios croquis, hay uno y se llama así. */
    const val EL_DE_SIEMPRE = "croquis"
}
