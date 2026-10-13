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
                // **Medio píxel cuando no hay transparencia que guardar.**
                //
                // Un JPEG no tiene canal alfa por definición, así que el cuarto
                // byte de cada píxel es relleno: en 565 la misma foto ocupa **la
                // mitad**. Una imagen de 2048 pasa de 16 MB a 8, y las fotos de
                // la galería —que es de donde sale casi todo lo que se pinea—
                // son justo eso.
                //
                // Solo con el JPEG: un PNG o un WEBP **pueden** llevar alfa, y
                // aplanarlo pondría fondo negro donde había transparencia. No se
                // adivina por el nombre; se mira lo que dice el propio archivo.
                if (bounds.outMimeType == "image/jpeg") {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // Nítida: para lo que se mira, no para medir
    // ---------------------------------------------------------------------

    /**
     * **La imagen a la resolución que tiene**, o tan cerca como quepa, para pintarla.
     *
     * [load] parte la foto **por dos** hasta que su lado largo no pasa de 2048: una captura de
     * 2560 se queda en 1280, y una del escritorio entero del PC (5760, tres monitores) en 1440, a
     * un cuarto. Eso servía para colocar la foto en el lienzo —y sigue sirviendo: con esas medidas
     * se mide la foto en las unidades del lienzo, aquí y en el PC (`lienzo_de_la_foto::LADO_MAXIMO`)—,
     * pero pintarla así es enseñar la mitad de los píxeles que llegaron. El usuario lo dijo el
     * 13-oct-2026: lo anotado en pantalla en el PC llegaba al móvil «irreconocible, no se lee ni una
     * letra», y la galería y las fotos, igual: el archivo viajaba entero y era la lectura de aquí la
     * que lo estropeaba.
     *
     * Aquí la reducción, si hace falta, es **exacta** (no a saltos de dos) y solo hasta que cabe en
     * [lado] y en [maxPixeles]; por debajo, la foto entera. El renderizador la estira a la caja del
     * elemento, así que da igual que mida más que esa caja: lo dibujado sigue en su sitio.
     */
    fun cargarNitida(path: String, lado: Int = LADO_NITIDO, maxPixeles: Long = PIXELES_NITIDOS): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val (ancho, alto) = medidaNitida(bounds.outWidth, bounds.outHeight, lado, maxPixeles)
            val opts = BitmapFactory.Options().apply {
                // Primero a saltos de dos, que es barato (lo hace el descodificador), sin bajar
                // nunca de la medida pedida; lo que falte, con filtro, abajo.
                inSampleSize = muestraSinPasarse(bounds.outWidth, ancho)
                // Lo mismo que en [load]: un JPEG no tiene alfa.
                if (bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
            }
            val leida = BitmapFactory.decodeFile(path, opts) ?: return null
            if (leida.width <= ancho && leida.height <= alto) return leida
            val exacta = Bitmap.createScaledBitmap(leida, ancho, alto, true)
            if (exacta !== leida) leida.recycle()
            exacta
        }.getOrNull()
    }

    /**
     * **Pura**: a qué medida se lee una imagen de [ancho]×[alto] para que su lado largo no pase de
     * [lado] ni sus píxeles de [maxPixeles], sin deformarla. Si ya cabe, la suya.
     */
    fun medidaNitida(ancho: Int, alto: Int, lado: Int = LADO_NITIDO, maxPixeles: Long = PIXELES_NITIDOS): Pair<Int, Int> {
        if (ancho <= 0 || alto <= 0) return ancho to alto
        val porLado = lado.toDouble() / maxOf(ancho, alto)
        val porPixeles = kotlin.math.sqrt(maxPixeles.toDouble() / (ancho.toLong() * alto))
        val f = minOf(1.0, porLado, porPixeles)
        if (f >= 1.0) return ancho to alto
        return (ancho * f).toInt().coerceAtLeast(1) to (alto * f).toInt().coerceAtLeast(1)
    }

    /**
     * **Pura**: el mayor `inSampleSize` (potencia de dos) que deja la imagen de [ancho] en
     * [objetivo] píxeles o más. Nunca pasa por debajo: lo que se pierde ahí ya no vuelve.
     */
    fun muestraSinPasarse(ancho: Int, objetivo: Int): Int {
        var m = 1
        while (objetivo > 0 && ancho / (m * 2) >= objetivo) m *= 2
        return m
    }

    /**
     * **Pura**: cuántos píxeles le tocan a cada imagen nítida cuando hay [cuantas] a la vez en
     * memoria (las de un lienzo abierto). Con pocas, enteras; con muchas se reparte
     * [PIXELES_A_LA_VEZ], sin bajar nunca de 2048² —lo que ya se tenía con [load]—: un lienzo con
     * veinte fotos de cámara no puede pedir un gigabyte para enseñarlas.
     */
    fun pixelesParaVarias(cuantas: Int): Long =
        (PIXELES_A_LA_VEZ / cuantas.coerceAtLeast(1)).coerceIn(MAX_DIMENSION.toLong() * MAX_DIMENSION, PIXELES_NITIDOS)

    /** ¿[bmp], leída de [path], tiene ya todos los píxeles del archivo? Entonces no hay otra más nítida. */
    fun estaEntera(path: String, bmp: Bitmap): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        bmp.width >= bounds.outWidth && bmp.height >= bounds.outHeight
    }.getOrDefault(true)

    /** Los píxeles nítidos que se tienen a la vez entre todas las imágenes de un lienzo. */
    const val PIXELES_A_LA_VEZ = 48L * 1024L * 1024L

    /**
     * El lado más largo de una imagen nítida. 8192 es lo que cualquier GPU de los últimos años
     * acepta como textura; lo que pase de ahí no se podría ni pintar.
     */
    const val LADO_NITIDO = 8192

    /**
     * Y sus píxeles: 4096², unos 64 MB en ARGB (la mitad en un JPEG). Caben enteras una captura
     * de un 4K, la de tres monitores en fila y la foto de una cámara de 12 Mpx; una de 50 Mpx se
     * reduce lo justo. Desde Android 8 los píxeles de un `Bitmap` no ocupan el montón de Java.
     */
    const val PIXELES_NITIDOS = 4096L * 4096L

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
