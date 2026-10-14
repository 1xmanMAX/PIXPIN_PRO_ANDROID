package com.forge.pixpin.asistente

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **Lo que ve el modelo de una foto o de una hoja de PDF** (`foto.rs` del PC): reducida a unos
 * [LADO] px de lado y en JPEG antes de mandarla. Leer una imagen cuesta cientos de tokens de
 * entrada y, en un teléfono, segundos de visión: con 768 px el modelo ve lo mismo y tarda menos.
 */
object FotoParaElModelo {
    const val LADO = 768

    /** Para leer el texto de una hoja hace falta algo más de resolución que para describirla. */
    const val LADO_PARA_LEER = 1280

    /** La foto [archivo], reducida y en JPEG; null si no se puede leer. */
    fun deArchivo(archivo: File, lado: Int = LADO): ByteArray? = runCatching {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.path, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null
        var muestra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / (muestra * 2) >= lado) muestra *= 2
        val b = BitmapFactory.decodeFile(archivo.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return null
        aJpeg(b, lado)
    }.getOrNull()

    /** La hoja [pagina] (desde 0) de un PDF, pintada en blanco y reducida. */
    fun deHojaDePdf(pdf: File, pagina: Int, lado: Int = LADO_PARA_LEER): ByteArray? = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (pagina !in 0 until r.pageCount) return null
                r.openPage(pagina).use { p ->
                    val escala = lado.toFloat() / maxOf(p.width, p.height)
                    val b = Bitmap.createBitmap((p.width * escala).toInt().coerceAtLeast(1), (p.height * escala).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    aJpeg(b, lado)
                }
            }
        }
    }.getOrNull()

    private fun aJpeg(b: Bitmap, lado: Int): ByteArray {
        val escala = minOf(1f, lado.toFloat() / maxOf(b.width, b.height))
        val reducida = if (escala < 1f) Bitmap.createScaledBitmap(b, (b.width * escala).toInt().coerceAtLeast(1), (b.height * escala).toInt().coerceAtLeast(1), true) else b
        val salida = ByteArrayOutputStream()
        reducida.compress(Bitmap.CompressFormat.JPEG, 85, salida)
        if (reducida !== b) reducida.recycle()
        b.recycle()
        return salida.toByteArray()
    }
}
