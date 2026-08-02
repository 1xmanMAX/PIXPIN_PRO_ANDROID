package com.forge.pixpin.pin

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Reconocedor de gestos de las ventanas overlay. Los eventos se construyen con
 * coordenadas reales para poder comprobar el comportamiento sin dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayTouchHandlerTest {

    private class Recorder : OverlayTouchHandler.Listener {
        var drags = 0
        var lastDx = 0f
        var lastDy = 0f
        var scaleFactor = 0f
        var focusX = 0f
        var opacityDy = 0f
        var scaleEnds = 0
        var opacityEnds = 0

        override fun onDrag(dxFromDown: Float, dyFromDown: Float) {
            drags++
            lastDx = dxFromDown
            lastDy = dyFromDown
        }

        override fun onScale(factorFromDown: Float, focusX: Float, focusY: Float) {
            scaleFactor = factorFromDown
            this.focusX = focusX
        }

        override fun onScaleEnd() { scaleEnds++ }
        override fun onOpacity(dyFromDown: Float) { opacityDy = dyFromDown }
        override fun onOpacityEnd() { opacityEnds++ }

        var resizes = 0
        var lastResizeDx = 0f
        var lastResizeDy = 0f
        var resizeEnds = 0

        override fun onResize(dxFromDown: Float, dyFromDown: Float) {
            resizes++
            lastResizeDx = dxFromDown
            lastResizeDy = dyFromDown
        }

        override fun onResizeEnd() { resizeEnds++ }

        var scrolls = 0
        var lastScrollDy = 0f
        var scrollEnds = 0

        override fun onScrollDrag(dyFromDown: Float) {
            scrolls++
            lastScrollDy = dyFromDown
        }

        override fun onScrollEnd() { scrollEnds++ }
    }

    private lateinit var recorder: Recorder
    private lateinit var handler: OverlayTouchHandler
    private val view by lazy { android.view.View(RuntimeEnvironment.getApplication()) }

    @Before
    fun setUp() {
        recorder = Recorder()
        handler = OverlayTouchHandler(RuntimeEnvironment.getApplication(), recorder)
    }

    @Test
    fun `un dedo arrastra en coordenadas de pantalla`() {
        send(down(100f, 200f))
        send(move(160f, 260f))
        assertTrue(recorder.drags > 0)
        assertEquals(60f, recorder.lastDx, 0.01f)
        assertEquals(60f, recorder.lastDy, 0.01f)
    }

    /**
     * Regresión: al levantar un dedo del pellizco, el que quedaba arrastraba el
     * pin de golpe desde el punto inicial y lo mandaba fuera de la pantalla,
     * dejándolo imposible de tocar.
     */
    @Test
    fun `al soltar un dedo del pellizco el pin no salta`() {
        send(down(100f, 500f))
        send(pointerDown(100f, 500f, 400f, 500f))
        send(moveTwo(100f, 500f, 700f, 500f)) // pellizco de apertura
        send(pointerUp(100f, 500f, 700f, 500f))

        val dragsAntes = recorder.drags
        send(move(900f, 1400f)) // el dedo que queda se mueve muy lejos
        assertEquals("no debe arrastrar tras el multitáctil", dragsAntes, recorder.drags)

        send(up(900f, 1400f))
        assertEquals(1, recorder.scaleEnds)
    }

    @Test
    fun `pellizcar informa del punto medio entre los dedos`() {
        send(down(300f, 800f))
        send(pointerDown(300f, 800f, 500f, 800f))
        send(moveTwo(200f, 800f, 800f, 800f))

        assertTrue("debe escalar hacia arriba", recorder.scaleFactor > 1f)
        assertEquals("el foco es el punto medio", 500f, recorder.focusX, 1f)
    }

    @Test
    fun `dos dedos en vertical ajustan opacidad`() {
        send(down(300f, 800f))
        send(pointerDown(300f, 800f, 500f, 800f))
        send(moveTwo(300f, 950f, 500f, 950f)) // ambos bajan, sin abrir el pellizco

        assertEquals(150f, recorder.opacityDy, 1f)
        assertEquals(0f, recorder.scaleFactor, 0.001f)
        send(up(300f, 950f))
        assertEquals(1, recorder.opacityEnds)
    }

    /**
     * El handle es pequeño: tiene que responder al primer píxel, sin esperar al
     * touch slop, o resultaría imposible de agarrar.
     */
    @Test
    fun `el toque en la esquina redimensiona en vez de arrastrar`() {
        handler.handleRect = android.graphics.Rect(170, 270, 200, 300)
        send(down(180f, 280f))
        send(move(240f, 320f))

        assertEquals("no debe arrastrar", 0, recorder.drags)
        assertTrue("debe redimensionar", recorder.resizes > 0)
        assertEquals(60f, recorder.lastResizeDx, 0.01f)
        assertEquals(40f, recorder.lastResizeDy, 0.01f)

        send(up(240f, 320f))
        assertEquals(1, recorder.resizeEnds)
    }

    @Test
    fun `fuera de la esquina se sigue arrastrando`() {
        handler.handleRect = android.graphics.Rect(170, 270, 200, 300)
        send(down(100f, 200f))
        send(move(160f, 260f))

        assertTrue(recorder.drags > 0)
        assertEquals(0, recorder.resizes)
    }

    @Test
    fun `sin handle el comportamiento es el de siempre`() {
        handler.handleRect = null
        send(down(180f, 280f))
        send(move(240f, 320f))

        assertTrue(recorder.drags > 0)
        assertEquals(0, recorder.resizes)
    }

    /**
     * Regresión: el `verticalScroll` de Compose nunca recibía un solo toque,
     * porque la ventana del pin se los queda todos. El desplazamiento tiene que
     * salir del reconocedor o no ocurre.
     */
    @Test
    fun `la pastilla de scroll desplaza en vez de arrastrar el pin`() {
        handler.scrollRect = android.graphics.Rect(300, 400, 320, 460)
        send(down(310f, 430f))
        send(move(312f, 500f))

        assertEquals("no debe arrastrar el pin", 0, recorder.drags)
        assertTrue(recorder.scrolls > 0)
        assertEquals(70f, recorder.lastScrollDy, 0.01f)

        send(up(312f, 500f))
        assertEquals(1, recorder.scrollEnds)
    }

    /** Si se solapan gana el handle: es el más difícil de acertar de los dos. */
    @Test
    fun `el handle gana a la pastilla cuando se solapan`() {
        handler.handleRect = android.graphics.Rect(300, 400, 340, 440)
        handler.scrollRect = android.graphics.Rect(300, 400, 320, 460)
        send(down(310f, 420f))
        send(move(330f, 450f))

        assertTrue(recorder.resizes > 0)
        assertEquals(0, recorder.scrolls)
    }

    @Test
    fun `fuera de la pastilla se sigue arrastrando el pin`() {
        handler.scrollRect = android.graphics.Rect(300, 400, 320, 460)
        send(down(100f, 200f))
        send(move(160f, 260f))

        assertTrue(recorder.drags > 0)
        assertEquals(0, recorder.scrolls)
    }

    // ---- Construcción de eventos ----

    private fun send(event: MotionEvent) {
        handler.onTouch(view, event)
        event.recycle()
    }

    private fun down(x: Float, y: Float) =
        MotionEvent.obtain(0, 10, MotionEvent.ACTION_DOWN, x, y, 0)

    private fun move(x: Float, y: Float) =
        MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, x, y, 0)

    private fun up(x: Float, y: Float) =
        MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, x, y, 0)

    private fun pointerDown(x0: Float, y0: Float, x1: Float, y1: Float) =
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x0, y0, x1, y1)

    private fun pointerUp(x0: Float, y0: Float, x1: Float, y1: Float) =
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), x0, y0, x1, y1)

    private fun moveTwo(x0: Float, y0: Float, x1: Float, y1: Float) =
        multi(MotionEvent.ACTION_MOVE, x0, y0, x1, y1)

    private fun multi(action: Int, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = y0 },
            MotionEvent.PointerCoords().apply { x = x1; y = y1 }
        )
        return MotionEvent.obtain(
            0, 30, action, 2, props, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
    }
}
