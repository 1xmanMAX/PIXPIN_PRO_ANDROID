package com.forge.pixpin.pin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Copia archivos compartidos al almacenamiento privado para pinearlos de forma duradera. */
object FileStore {

    data class ImportedFile(val path: String, val name: String, val mime: String)

    fun importFromUri(context: Context, uri: Uri): ImportedFile? {
        return runCatching {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryDisplayName(context, uri) ?: "archivo"
            val dir = File(context.filesDir, "pins/files").apply { mkdirs() }
            val safeName = "${System.currentTimeMillis()}_" +
                name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            val dest = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            ImportedFile(dest.absolutePath, name, mime)
        }.getOrNull()
    }

    fun delete(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
