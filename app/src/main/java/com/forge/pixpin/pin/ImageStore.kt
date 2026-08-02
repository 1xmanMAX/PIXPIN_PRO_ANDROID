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
    /** Pauta de fondo de la pizarra. */
    enum class BoardGrid { NONE, SQUARES, HORIZONTAL, VERTICAL, DOTS }

    fun saveBlankBoard(
        context: Context,
        argb: Int = WHITE_BOARD,
        grid: BoardGrid = BoardGrid.NONE,
        width: Int = BOARD_W,
        height: Int = BOARD_H
    ): String? {
        return runCatching {
            val bmp = Bitmap.createBitmap(
                width.coerceIn(200, 4000),
                height.coerceIn(200, 4000),
                Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(argb)
            drawGrid(bmp, argb, grid)
            val path = saveBitmap(context, bmp, "board_${System.currentTimeMillis()}.png")
            bmp.recycle()
            path
        }.getOrNull()
    }

    private const val BOARD_W = 900
    private const val BOARD_H = 1200
    const val WHITE_BOARD = 0xFFFFFFFF.toInt()

    /** Separación de la pauta, en píxeles del lienzo. */
    private const val GRID_STEP = 50

    /**
     * Pinta la pauta encima del fondo.
     *
     * El color se deduce del propio fondo: sobre uno claro, líneas oscuras; y al
     * revés. Fijarlo a un gris haría la pauta invisible en la pizarra negra, que
     * es justo donde más falta hace la referencia.
     */
    private fun drawGrid(bmp: Bitmap, background: Int, grid: BoardGrid) {
        if (grid == BoardGrid.NONE) return
        val canvas = android.graphics.Canvas(bmp)
        val light = isLight(background)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (light) 0x22000000 else 0x33FFFFFF
            strokeWidth = 2f
        }
        when (grid) {
            BoardGrid.NONE -> Unit
            BoardGrid.DOTS -> {
                var y = GRID_STEP
                while (y < bmp.height) {
                    var x = GRID_STEP
                    while (x < bmp.width) {
                        canvas.drawCircle(x.toFloat(), y.toFloat(), 3f, paint)
                        x += GRID_STEP
                    }
                    y += GRID_STEP
                }
            }
            else -> {
                if (grid != BoardGrid.VERTICAL) {
                    var y = GRID_STEP
                    while (y < bmp.height) {
                        canvas.drawLine(0f, y.toFloat(), bmp.width.toFloat(), y.toFloat(), paint)
                        y += GRID_STEP
                    }
                }
                if (grid != BoardGrid.HORIZONTAL) {
                    var x = GRID_STEP
                    while (x < bmp.width) {
                        canvas.drawLine(x.toFloat(), 0f, x.toFloat(), bmp.height.toFloat(), paint)
                        x += GRID_STEP
                    }
                }
            }
        }
    }

    private fun isLight(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b > 140
    }

    fun delete(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun sampleFor(width: Int, height: Int, maxDim: Int = MAX_DIMENSION): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxDim) sample *= 2
        return sample
    }
}
