package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinZoomTest {

    /** Un paso de pellizco sobre un pin de 400×300 con el foco en su centro. */
    private fun step(
        factor: Float,
        maxScale: Float = PinZoom.ABSOLUTE_MAX_SCALE,
        realW: Int = 400,
        realH: Int = 300,
        focusX: Float = 500f,
        focusY: Float = 700f
    ) = PinZoom.step(
        scaleAtStart = 1f,
        factor = factor,
        maxScale = maxScale,
        realW = realW,
        realH = realH,
        focusX = focusX,
        focusY = focusY,
        relX = 0.5f,
        relY = 0.5f
    )

    @Test
    fun `el punto entre los dedos se queda clavado`() {
        val result = step(factor = 1.5f)
        // Foco en el centro: la esquina va a foco − mitad del tamaño real.
        assertEquals(500 - 200, result.x)
        assertEquals(700 - 150, result.y)
    }

    @Test
    fun `al mover los dedos el pin los sigue`() {
        val result = step(factor = 1.2f, focusX = 800f, focusY = 400f)
        assertEquals(800 - 200, result.x)
        assertEquals(400 - 150, result.y)
    }

    @Test
    fun `respeta el tope recibido`() {
        assertEquals(3f, step(factor = 99f, maxScale = 3f).scale, 0.001f)
        assertEquals(PinZoom.MIN_SCALE, step(factor = 0.001f).scale, 0.001f)
    }

    // ---- El tope: crecer hasta que un eje toque el borde de la pantalla ----

    @Test
    fun `el tope lo marca el eje que llega antes al borde`() {
        // Contenido natural de 500×1000 en una pantalla de 1080×2400: el alto
        // llega al borde con 2,4× mientras que el ancho aún daría para 2,16×...
        val max = PinZoom.maxScaleFor(
            realW = 500, realH = 1000, currentScale = 1f, screenW = 1080, screenH = 2400
        )
        // ...así que manda el menor de los dos.
        assertEquals(minOf(1080f / 500f, 2400f / 1000f), max, 0.001f)
    }

    @Test
    fun `a ese tope el pin llena la pantalla justo sin pasarse`() {
        val naturalW = 500
        val naturalH = 1000
        val screenW = 1080
        val screenH = 2400
        val max = PinZoom.maxScaleFor(naturalW, naturalH, 1f, screenW, screenH)
        val finalW = naturalW * max
        val finalH = naturalH * max
        assertTrue("no se sale de ancho", finalW <= screenW + 0.5f)
        assertTrue("no se sale de alto", finalH <= screenH + 0.5f)
        assertTrue("y toca un borde", finalW >= screenW - 0.5f || finalH >= screenH - 0.5f)
    }

    @Test
    fun `el tope se deduce aunque el pin ya venga escalado`() {
        // Mismo contenido natural (500×1000) pero medido ya a 2×.
        val max = PinZoom.maxScaleFor(
            realW = 1000, realH = 2000, currentScale = 2f, screenW = 1080, screenH = 2400
        )
        assertEquals(2.16f, max, 0.001f)
    }

    @Test
    fun `un contenido diminuto no crece sin freno`() {
        val max = PinZoom.maxScaleFor(
            realW = 8, realH = 8, currentScale = 1f, screenW = 1080, screenH = 2400
        )
        assertEquals(PinZoom.ABSOLUTE_MAX_SCALE, max, 0.001f)
    }

    @Test
    fun `medidas imposibles no rompen el calculo`() {
        assertEquals(
            PinZoom.ABSOLUTE_MAX_SCALE,
            PinZoom.maxScaleFor(0, 0, 1f, 1080, 2400),
            0.001f
        )
        assertEquals(
            PinZoom.ABSOLUTE_MAX_SCALE,
            PinZoom.maxScaleFor(400, 300, 0f, 1080, 2400),
            0.001f
        )
    }

    @Test
    fun `encoger siempre se puede`() {
        val result = PinZoom.step(
            scaleAtStart = 2.5f, factor = 0.2f, maxScale = 2.5f,
            realW = 1000, realH = 750, focusX = 500f, focusY = 700f,
            relX = 0.5f, relY = 0.5f
        )
        assertEquals(0.5f, result.scale, 0.001f)
    }
}
