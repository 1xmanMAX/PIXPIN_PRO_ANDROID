package com.forge.pixpin.capture

import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Va cosiendo los fotogramas de una captura con scroll en una sola imagen larga.
 *
 * Guarda solo las **tiras nuevas** de cada fotograma, no fotogramas enteros: lo
 * que ya estaba se descarta en cuanto se sabe cuánto se ha desplazado. Al
 * terminar se pintan todas seguidas.
 *
 * Ante la duda, no cose: un fotograma mal encajado estropea la imagen entera y
 * el usuario no lo ve hasta el final, mientras que descartarlo solo cuesta unos
 * milisegundos porque el siguiente ya viene de camino.
 */
class ScrollStitcher(private val width: Int, private val maxHeight: Int) {

    enum class Result {
        /** Primer fotograma: es la base. */
        FIRST,
        /** Se ha añadido contenido nuevo. */
        APPENDED,
        /** La pantalla no se ha movido desde el fotograma anterior. */
        NO_MOVEMENT,
        /** No se puede encajar con confianza: se descarta y se espera al siguiente. */
        UNCERTAIN,
        /** Se ha alcanzado el alto máximo. */
        FULL
    }

    private companion object {
        /** Filas de referencia que se buscan en el fotograma siguiente. */
        const val TAIL_ROWS = 48

        /** Hasta dónde se agranda la referencia si la banda no tiene textura. */
        const val MAX_TAIL_ROWS = 384

        /** Se muestrea uno de cada N píxeles por fila: sobra para distinguirlas. */
        const val SAMPLE_STEP = 5

        const val TOLERANCE = 40
        const val MIN_VARIATION = 300
    }

    private val strips = mutableListOf<Bitmap>()
    private var tail = IntArray(0)
    private var scratch = IntArray(0)

    var height: Int = 0
        private set

    val isEmpty: Boolean get() = strips.isEmpty()

    /** @param frame el recorte de la zona elegida en el fotograma actual. */
    fun addFrame(frame: Bitmap): Result {
        if (frame.width != width || frame.height <= TAIL_ROWS) return Result.UNCERTAIN
        val signatures = signaturesOf(frame)

        if (strips.isEmpty()) {
            if (frame.height > maxHeight) return Result.FULL
            append(frame, 0, frame.height)
            tail = chooseTail(signatures)
            return Result.FIRST
        }

        val offset = ScrollMatcher.findOffset(tail, signatures, TOLERANCE)
        if (offset == ScrollMatcher.NO_MATCH) return Result.UNCERTAIN

        val newStart = offset + tail.size
        val newRows = frame.height - newStart
        if (newRows <= 0) return Result.NO_MOVEMENT
        if (height + newRows > maxHeight) return Result.FULL

        append(frame, newStart, newRows)
        tail = chooseTail(signatures)
        return Result.APPENDED
    }

    /** Junta todas las tiras en la imagen final. Null si no hay nada. */
    fun build(): Bitmap? {
        if (strips.isEmpty() || height <= 0) return null
        val out = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val canvas = Canvas(out)
        var y = 0f
        for (strip in strips) {
            canvas.drawBitmap(strip, 0f, y, null)
            y += strip.height
        }
        return out
    }

    fun recycle() {
        strips.forEach { if (!it.isRecycled) it.recycle() }
        strips.clear()
        height = 0
        tail = IntArray(0)
        scratch = IntArray(0)
    }

    private fun append(frame: Bitmap, y: Int, rows: Int) {
        // Ojo: createBitmap devuelve el MISMO bitmap si la región es la imagen
        // entera, y quien nos lo pasa lo recicla justo después.
        val strip = if (y == 0 && rows == frame.height) {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createBitmap(frame, 0, y, width, rows)
        }
        strips += strip
        height += rows
    }

    /**
     * Banda de referencia del final de lo acumulado. Si no tiene textura (un
     * fondo liso, el final de una página en blanco) se agranda hasta encontrar
     * algo distinguible: si no, encajaría en cualquier sitio.
     */
    private fun chooseTail(signatures: IntArray): IntArray {
        var rows = TAIL_ROWS
        while (rows < MAX_TAIL_ROWS && rows < signatures.size) {
            val candidate = signatures.copyOfRange(signatures.size - rows, signatures.size)
            if (!ScrollMatcher.isFlat(candidate, MIN_VARIATION)) return candidate
            rows *= 2
        }
        val take = minOf(rows, signatures.size)
        return signatures.copyOfRange(signatures.size - take, signatures.size)
    }

    private fun signaturesOf(frame: Bitmap): IntArray {
        val needed = width * frame.height
        if (scratch.size < needed) scratch = IntArray(needed)
        frame.getPixels(scratch, 0, width, 0, 0, width, frame.height)
        return ScrollMatcher.signatures(scratch, width, frame.height, SAMPLE_STEP)
    }
}
