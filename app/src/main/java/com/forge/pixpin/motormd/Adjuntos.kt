package com.forge.pixpin.motormd

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Los archivos que se meten en una nota.
 *
 * **Se copian, no se enlazan.** Una nota vive meses en la pantalla y el `content://`
 * del selector caduca al reiniciar, o cuando la app de origen borra el archivo, o
 * cuando se quita la tarjeta. Guardar la referencia sería tener una nota que un
 * día deja de enseñar su foto sin que nadie haya tocado nada.
 *
 * El coste es el espacio, y es el correcto: lo que está en la nota es de la nota.
 */
object Adjuntos {

    private fun carpeta(context: Context): File =
        File(context.filesDir, "notas").apply { mkdirs() }

    /**
     * Copia lo que haya en [uri] y devuelve la ruta, o null si no se pudo.
     *
     * El nombre original se conserva —con la marca de tiempo delante para que no
     * choquen dos— porque es lo que se enseña en la tarjeta del adjunto: un
     * «informe-final.pdf» dice lo que es y un «archivo-8842» no dice nada.
     */
    fun importar(context: Context, uri: Uri, cuando: Long): String? = runCatching {
        val nombre = nombreDe(context, uri)
        val destino = File(carpeta(context), "$cuando-$nombre")
        context.contentResolver.openInputStream(uri)?.use { entrada ->
            destino.outputStream().use { entrada.copyTo(it) }
        } ?: return null
        if (destino.length() <= 0) return null
        destino.absolutePath
    }.getOrNull()

    private fun nombreDe(context: Context, uri: Uri): String {
        val delProveedor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()

        val crudo = delProveedor ?: uri.lastPathSegment ?: "adjunto"
        // Sin barras ni dos puntos: el nombre acaba dentro de un `![](ruta)` y
        // también dentro de una ruta de archivo.
        return crudo.replace(Regex("""[/\\:|()\[\]]"""), "_").take(80)
    }
}
