package com.forge.pixpin.guardados

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File

/**
 * **Las fotos, derechas** (5-oct-2026, lo del PC: «las fotos se abren de lado»).
 *
 * Una foto del móvil hecha en vertical se guarda tumbada y con una etiqueta EXIF que dice cómo
 * girarla. El PC la gira al leerla (`pixpin_codec::cargar`); aquí todo lee las fotos con
 * [com.forge.pixpin.pin.ImageStore.load], que no mira la etiqueta, y además el editor coloca la
 * foto con sus medidas: girarla solo al leerla deformaría las ya anotadas.
 *
 * Por eso se endereza **una vez, al entrar** en el chat ([MensajesStore.copiarAdjunto]): la
 * copia guardada ya tiene los píxeles derechos y sin etiqueta, y la miniatura, el editor, el PDF
 * y el otro aparato la ven igual sin saber nada de EXIF. Lo que llega sincronizando no pasa por
 * aquí: reescribirlo cambiaría su resumen y los aparatos se lo volverían a pasar.
 */
object FotoDerecha {
    /**
     * Endereza [archivo] en su sitio si su EXIF lo pide. `true` si lo reescribió. Si algo falla
     * —no cabe en memoria, no se lee— se queda como estaba: de lado es mejor que perderla.
     */
    fun enSuSitio(archivo: File): Boolean = runCatching {
        if (!esJpeg(archivo)) return false
        val orientacion = android.media.ExifInterface(archivo.absolutePath)
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        val giro = GiroExif.de(orientacion)
        if (giro.nada) return false
        val original = BitmapFactory.decodeFile(archivo.absolutePath) ?: return false
        val derecha = girada(original, giro)
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.outputStream().use { derecha.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
        if (derecha !== original) derecha.recycle()
        original.recycle()
        // Entero o nada: una foto a medio escribir se quedaría rota para siempre.
        if (!tmp.renameTo(archivo)) { tmp.delete(); return false }
        true
    }.getOrElse { false }

    /** [b] con el [giro] aplicado; el mismo si no hay nada que hacer. */
    fun girada(b: Bitmap, giro: Giro): Bitmap {
        if (giro.nada) return b
        val m = Matrix().apply {
            setRotate(giro.grados.toFloat())
            if (giro.espejo) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Solo los JPEG llevan la etiqueta en la práctica; se mira la cabecera, no el nombre. */
    private fun esJpeg(f: File): Boolean = runCatching {
        f.inputStream().use { val c = ByteArray(2); it.read(c) == 2 && c[0] == 0xFF.toByte() && c[1] == 0xD8.toByte() }
    }.getOrDefault(false)

    /** La de las cámaras: no se nota y no engorda la foto. */
    private const val CALIDAD = 92
}
