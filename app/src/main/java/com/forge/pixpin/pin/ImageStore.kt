package com.forge.pixpin.pin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Almacén de imágenes de los pines en el almacenamiento privado de la app,
 * con decodificación muestreada para no reventar la memoria (OOM-safe).
 */
object ImageStore {

    private const val MAX_DIMENSION = 2048

    fun importFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight)
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                saveBitmap(context, bmp, "clip_${System.currentTimeMillis()}.png")
            }
        }.getOrNull()
    }

    /** Guarda el bitmap en el almacén privado. Null si el disco falla. */
    fun saveBitmap(context: Context, bitmap: Bitmap, name: String): String? {
        return runCatching {
            val dir = File(context.filesDir, "pins").apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        }.getOrNull()
    }

    fun load(path: String, maxDim: Int = MAX_DIMENSION): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxDim)
            }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }

    /**
     * Lienzo liso para la pizarra.
     *
     * Una pizarra no es un tipo de pin nuevo: es un pin de imagen con el fondo
     * en blanco. Así hereda gratis el dibujo a mano, el zoom, las anotaciones
     * re-editables y la exportación, que ya están hechos y probados.
     *
     * El tamaño es fijo y en proporción vertical, como una cuartilla: es donde
     * se escribe a gusto sin que el pin ocupe la pantalla entera.
     */
    fun saveBlankBoard(context: Context, argb: Int = WHITE_BOARD): String? {
        return runCatching {
            val bmp = Bitmap.createBitmap(BOARD_W, BOARD_H, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(argb)
            val path = saveBitmap(context, bmp, "board_${System.currentTimeMillis()}.png")
            bmp.recycle()
            path
        }.getOrNull()
    }

    private const val BOARD_W = 900
    private const val BOARD_H = 1200
    const val WHITE_BOARD = 0xFFFFFFFF.toInt()

    fun delete(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun sampleFor(width: Int, height: Int, maxDim: Int = MAX_DIMENSION): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxDim) sample *= 2
        return sample
    }
}
