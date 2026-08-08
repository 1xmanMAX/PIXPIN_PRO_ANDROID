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
 * **Aquí solo se copian píxeles.** Qué franja de cada fotograma es nueva, si
 * encaja o no y cuándo hay que parar lo decide [ScrollPlan], que no toca
 * Android y por eso se puede comprobar en cualquier máquina. La separación no es
 * teórica: con la decisión metida aquí, sus pruebas necesitaban bitmaps de
 * verdad, y eso solo funciona con el motor gráfico nativo de Robolectric — que
 * no existe para Linux sobre ARM.
 */
class ScrollStitcher(private val width: Int, maxHeight: Int) {

    private val plan = ScrollPlan(maxHeight)
    private val strips = mutableListOf<Bitmap>()
    private var scratch = IntArray(0)

    val height: Int get() = plan.height

    val isEmpty: Boolean get() = strips.isEmpty()

    /** @param frame el recorte de la zona elegida en el fotograma actual. */
    fun addFrame(frame: Bitmap): ScrollPlan.Result {
        if (frame.width != width) return ScrollPlan.Result.UNCERTAIN

        val orden = plan.plan(signaturesOf(frame), frame.height)
        if (orden.filas > 0) append(frame, orden.desde, orden.filas)
        return orden.result
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
        plan.reset()
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
    }

    private fun signaturesOf(frame: Bitmap): IntArray {
        val needed = width * frame.height
        if (scratch.size < needed) scratch = IntArray(needed)
        frame.getPixels(scratch, 0, width, 0, 0, width, frame.height)
        return ScrollMatcher.signatures(scratch, width, frame.height, ScrollPlan.SAMPLE_STEP)
    }
}
