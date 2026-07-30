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
            // Preserva la extensión ORIGINAL del archivo (incluyendo mayúsculas y puntos múltiples).
            val baseName = name.substringBeforeLast(".")
            val extension = name.substringAfterLast(".", "")
            val safeBase = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(50)
            val safeName = if (extension.isNotBlank()) {
                "${System.currentTimeMillis()}_${safeBase}.$extension"
            } else {
                "${System.currentTimeMillis()}_${safeBase}"
            }
            val dest = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            // Si el MIME es genérico, intenta inferirlo de la extensión.
            val resolvedMime = if (mime == "application/octet-stream" && extension.isNotBlank()) {
                guessMimeFromExtension(extension) ?: mime
            } else mime
            ImportedFile(dest.absolutePath, name, resolvedMime)
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

    /** Infiere el MIME type de la extensión del archivo cuando el sistema no lo detecta. */
    private fun guessMimeFromExtension(ext: String): String? {
        return when (ext.lowercase()) {
            "dwg" -> "application/acad"
            "dxf" -> "application/dxf"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "7z" -> "application/x-7z-compressed"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "js" -> "application/javascript"
            "py" -> "text/x-python"
            "java" -> "text/x-java"
            "kt" -> "text/x-kotlin"
            "psd" -> "image/vnd.adobe.photoshop"
            "ai" -> "application/postscript"
            "svg" -> "image/svg+xml"
            else -> null
        }
    }
}
