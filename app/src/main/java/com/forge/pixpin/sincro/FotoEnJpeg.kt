package com.forge.pixpin.sincro

import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File

/**
 * **La foto para el PC, en JPEG y con su EXIF** (28-oct-2026, §5 y §8 de
 * `docs/movil-como-camara.md` del PC: «HEIC no: el móvil pasa la foto a JPEG antes, manteniendo
 * el EXIF»).
 *
 * CameraX ya guarda JPEG con su EXIF, que es lo que viaja tal cual; esto es la red de seguridad
 * para un aparato que dé HEIC/HEIF (u otra cosa): se lee, se vuelve a escribir en JPEG con la
 * mejor calidad y se le copian las etiquetas EXIF de la original (la fecha, el aparato, el
 * lugar, la orientación…). Lo que ya es JPEG no se toca: ni un byte.
 */
object FotoEnJpeg {

    /** Las etiquetas que se copian de la foto original a la JPEG. */
    private val ETIQUETAS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_SOFTWARE
    )

    private fun cabeza(f: File): ByteArray = f.inputStream().use { i -> ByteArray(16).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } }

    /**
     * **Deja [foto] en JPEG.** Devuelve `true` si ya lo era o se pudo pasar; `false` si no es una
     * foto que se sepa leer (entonces el PC la negaría igual).
     */
    fun asegurar(foto: File): Boolean {
        val c = cabeza(foto)
        if (CamaraRemota.esJpeg(c)) return true
        val bmp = BitmapFactory.decodeFile(foto.path) ?: return false
        val exifDeAntes = runCatching { ExifInterface(foto.path) }.getOrNull()
        val tmp = File(foto.parentFile, foto.name + ".jpg.tmp")
        try {
            tmp.outputStream().use { o -> if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, o)) return false }
            if (exifDeAntes != null) runCatching {
                val nueva = ExifInterface(tmp.path)
                for (t in ETIQUETAS) exifDeAntes.getAttribute(t)?.let { nueva.setAttribute(t, it) }
                nueva.saveAttributes()
            }
            if (!tmp.renameTo(foto)) { foto.delete(); if (!tmp.renameTo(foto)) return false }
            return true
        } finally {
            bmp.recycle()
            tmp.delete()
        }
    }
}
