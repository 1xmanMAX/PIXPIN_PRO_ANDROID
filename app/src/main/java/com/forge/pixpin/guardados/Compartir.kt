package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Cómo sale de aquí lo que se comparte: como imagen o como PDF.
 *
 * ## Por qué hay que elegir
 *
 * Una página anotada tiene dos destinos distintos y no valen lo mismo. Para mandarla por
 * un chat, **imagen**: se ve en la conversación sin abrir nada. Para adjuntarla a un
 * correo o subirla a un sitio que espera un documento, **PDF**: conserva la proporción de
 * la hoja y se imprime bien.
 *
 * ## Y por qué no se manda el archivo original
 *
 * Porque el archivo original **no es lo que se está viendo**. Una foto anotada guarda por
 * un lado la foto y por otro lo dibujado encima; una página de un PDF de doce hojas es
 * eso, una de doce. Compartir el archivo tal cual mandaba la foto sin las anotaciones, o
 * el documento entero cuando se pedía una página. Lo que se comparte se compone primero.
 */
object Compartir {

    /** La carpeta de lo que está de salida. En caché: son copias de usar y tirar. */
    private fun carpeta(context: Context): File =
        File(context.cacheDir, "compartir").apply { mkdirs() }

    /** Un nombre de archivo que no sorprenda a quien lo recibe. */
    private fun limpio(nombre: String, extension: String): String {
        val base = nombre.substringBeforeLast('.').ifBlank { "pixpin" }
            .replace(Regex("""[^\p{L}\p{N} ._-]"""), "_")
            .take(60)
        return "$base.$extension"
    }

    /** El bitmap como PNG. */
    fun comoImagen(context: Context, mapa: Bitmap, nombre: String): File? = runCatching {
        val destino = File(carpeta(context), limpio(nombre, "png"))
        destino.outputStream().use { mapa.compress(Bitmap.CompressFormat.PNG, 100, it) }
        destino
    }.getOrNull()

    /**
     * El bitmap como PDF de una sola página, del tamaño de la propia imagen.
     *
     * Del tamaño de la imagen y no de un A4: la página ya venía de un documento con su
     * proporción, y meterla en un A4 le pondría bandas blancas o la recortaría. Quien
     * quiera imprimirla en A4 lo hará al imprimir, que es donde se decide eso.
     */
    fun comoPdf(context: Context, mapa: Bitmap, nombre: String): File? = runCatching {
        val documento = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(mapa.width, mapa.height, 1).create()
        val pagina = documento.startPage(info)
        pagina.canvas.drawBitmap(mapa, 0f, 0f, null)
        documento.finishPage(pagina)
        val destino = File(carpeta(context), limpio(nombre, "pdf"))
        destino.outputStream().use { documento.writeTo(it) }
        documento.close()
        destino
    }.getOrNull()
}
