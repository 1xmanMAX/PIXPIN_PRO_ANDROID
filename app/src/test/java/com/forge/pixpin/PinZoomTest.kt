package com.forge.pixpin.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinZoomTest {

    /** Un paso de pellizco sobre un pin de 400×300 con el foco en su centro. */
    private fun step(
        factor: Float,
        currentScale: Float = 1f,
        realW: Int = 400,
        realH: Int = 300,
        focusX: Float = 500f,
        focusY: Float = 700f,
        state: ZoomState = ZoomState()
    ) = PinZoom.step(
        scaleAtStart = 1f,
        factor = factor,
        currentScale = currentScale,
        realW = realW,
        realH = realH,
        focusX = focusX,
        focusY = focusY,
        relX = 0.5f,
        relY = 0.5f,
        state = state
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

    /**
     * Regresión: al topar de tamaño (una ventana no puede medir más que la
     * pantalla) el pin se escapaba en diagonal hacia la esquina superior
     * izquierda en vez de quedarse quieto.
     */
    @Test
    fun `al topar de tamaño el pin deja de crecer y de moverse`() {
        // La ventana se queda clavada en 1000 px por más que pidamos.
        var state = ZoomState(requestedW = 1400, lastRealW = 1000, maxScale = PinZoom.MAX_SCALE)
        var last: ZoomStep? = null
        var factor = 2.5f
        repeat(4) {
            factor += 0.4f
            last = PinZoom.step(
                scaleAtStart = 1f, factor = factor, currentScale = 2.5f,
                realW = 1000, realH = 750, focusX = 500f, focusY = 700f,
                relX = 0.5f, relY = 0.5f, state = state
            )
            state = last!!.state
        }
        assertEquals("la escala se congela", 2.5f, last!!.scale, 0.001f)
        assertEquals("y el tope queda registrado", 2.5f, state.maxScale, 0.001f)

        val despues = PinZoom.step(
            scaleAtStart = 1f, factor = 4.5f, currentScale = 2.5f,
            realW = 1000, realH = 750, focusX = 500f, focusY = 700f,
            relX = 0.5f, relY = 0.5f, state = state
        )
        assertEquals(2.5f, despues.scale, 0.001f)
        assertEquals("la posición tampoco se mueve", last!!.x, despues.x)
        assertEquals(last!!.y, despues.y)
    }

    /**
     * Durante un pellizco rápido el tamaño real va un fotograma por detrás: eso
     * NO es haber topado y no debe congelar el zoom.
     */
    @Test
    fun `un fotograma de retraso no cuenta como tope`() {
        var state = ZoomState()
        var scale = 1f
        var realW = 400
        repeat(6) {
            val result = PinZoom.step(
                scaleAtStart = 1f, factor = 1f + (it + 1) * 0.3f, currentScale = scale,
                realW = realW, realH = 300, focusX = 500f, focusY = 700f,
                relX = 0.5f, relY = 0.5f, state = state
            )
            state = result.state
            scale = result.scale
            realW = (realW * 1.25f).toInt() // crece, pero por detrás de lo pedido
        }
        assertEquals("el tope no debe haberse activado", PinZoom.MAX_SCALE, state.maxScale, 0.001f)
        assertTrue("y la escala debe haber subido", scale > 2f)
    }

    @Test
    fun `se puede volver a encoger despues de topar`() {
        val result = PinZoom.step(
            scaleAtStart = 1f, factor = 0.5f, currentScale = 2.5f,
            realW = 1000, realH = 750, focusX = 500f, focusY = 700f,
            relX = 0.5f, relY = 0.5f,
            state = ZoomState(maxScale = 2.5f, requestedW = 0, lastRealW = 1000)
        )
        assertEquals("encoger siempre debe poderse", 0.5f, result.scale, 0.001f)
    }

    @Test
    fun `respeta los limites de escala`() {
        assertEquals(PinZoom.MAX_SCALE, step(factor = 99f).scale, 0.001f)
        assertEquals(PinZoom.MIN_SCALE, step(factor = 0.001f).scale, 0.001f)
    }
}
