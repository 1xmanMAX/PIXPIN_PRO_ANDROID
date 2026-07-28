package com.forge.pixpin.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeBufferTest {

    @Test
    fun `crece sin perder puntos`() {
        val buf = StrokeBuffer(initialCapacity = 4)
        repeat(1000) { buf.add(it.toFloat(), it * 2f, 0.5f) }
        assertEquals(1000, buf.size)
        assertEquals(999f, buf.x(999), 0.001f)
        assertEquals(1998f, buf.y(999), 0.001f)
        assertEquals(0.5f, buf.pressure(999), 0.001f)
    }

    @Test
    fun `descarta la muestra repetida en el mismo punto`() {
        val buf = StrokeBuffer()
        buf.add(10f, 10f)
        buf.add(10f, 10f)
        buf.add(10f, 11f)
        assertEquals(2, buf.size)
    }

    @Test
    fun `un solo contacto deja un punto`() {
        val buf = StrokeBuffer()
        buf.add(3f, 4f, 0.8f)
        assertEquals(listOf(Pt(3f, 4f, 0.8f)), buf.toPoints())
    }

    @Test
    fun `clear reutiliza el buffer`() {
        val buf = StrokeBuffer()
        buf.add(1f, 1f)
        buf.clear()
        assertTrue(buf.isEmpty)
        buf.add(9f, 9f)
        assertEquals(1, buf.size)
        assertEquals(9f, buf.x(0), 0.001f)
    }
}

/** Recoge lo que emite el suavizado para poder comprobarlo sin dibujar nada. */
private class RecordingSink : PathSink {
    val ops = mutableListOf<String>()
    override fun moveTo(x: Float, y: Float) {
        ops += "M $x $y"
    }
    override fun lineTo(x: Float, y: Float) {
        ops += "L $x $y"
    }
    override fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float) {
        ops += "Q $controlX $controlY $x $y"
    }
}

class StrokeSmoothingTest {

    private fun feed(vararg pts: Pair<Float, Float>): List<String> {
        val sink = RecordingSink()
        StrokeSmoothing.feed(pts.size, { pts[it].first }, { pts[it].second }, sink)
        return sink.ops
    }

    @Test
    fun `sin puntos no emite nada`() {
        assertEquals(emptyList<String>(), feed())
    }

    @Test
    fun `un punto solo se posiciona`() {
        assertEquals(listOf("M 5.0 5.0"), feed(5f to 5f))
    }

    @Test
    fun `dos puntos son una recta`() {
        assertEquals(listOf("M 0.0 0.0", "L 10.0 0.0"), feed(0f to 0f, 10f to 0f))
    }

    @Test
    fun `los puntos intermedios son controles y las curvas acaban en los medios`() {
        val ops = feed(0f to 0f, 10f to 0f, 20f to 10f)
        assertEquals("M 0.0 0.0", ops[0])
        // El punto 1 es el control; la curva termina en el medio de 1 y 2.
        assertEquals("Q 10.0 0.0 15.0 5.0", ops[1])
        assertEquals("L 20.0 10.0", ops[2])
    }

    @Test
    fun `el trazo siempre empieza y acaba en los puntos reales`() {
        val ops = feed(0f to 0f, 10f to 0f, 20f to 10f, 30f to 30f)
        assertEquals("M 0.0 0.0", ops.first())
        assertEquals("L 30.0 30.0", ops.last())
        assertEquals("una curva por cada punto intermedio", 2, ops.count { it.startsWith("Q") })
    }

    @Test
    fun `el grosor sigue a la presion`() {
        val suave = StrokeSmoothing.widthFor(10f, 0f)
        val medio = StrokeSmoothing.widthFor(10f, 0.5f)
        val fuerte = StrokeSmoothing.widthFor(10f, 1f)
        assertTrue(suave < medio && medio < fuerte)
        assertEquals(10f * StrokeSmoothing.MIN_PRESSURE_FACTOR, suave, 0.001f)
        assertEquals(10f * StrokeSmoothing.MAX_PRESSURE_FACTOR, fuerte, 0.001f)
    }

    @Test
    fun `presion fuera de rango se recorta`() {
        assertEquals(StrokeSmoothing.widthFor(10f, 1f), StrokeSmoothing.widthFor(10f, 7f), 0.001f)
        assertEquals(StrokeSmoothing.widthFor(10f, 0f), StrokeSmoothing.widthFor(10f, -3f), 0.001f)
    }

    @Test
    fun `solo se considera presion variable si cambia de verdad`() {
        // El dedo entrega siempre lo mismo: no vale la pena el trazo por tramos.
        assertFalse(StrokeSmoothing.hasVariablePressure(listOf(Pt(0f, 0f), Pt(1f, 1f))))
        assertFalse(
            StrokeSmoothing.hasVariablePressure(
                listOf(Pt(0f, 0f, 0.50f), Pt(1f, 1f, 0.52f))
            )
        )
        assertTrue(
            StrokeSmoothing.hasVariablePressure(
                listOf(Pt(0f, 0f, 0.2f), Pt(1f, 1f, 0.9f))
            )
        )
    }
}

class PalmGuardTest {

    @Test
    fun `sin lapiz el dedo dibuja`() {
        val guard = PalmGuard()
        assertTrue(guard.accepts(ToolKind.FINGER, 1_000L))
    }

    @Test
    fun `con el lapiz recien usado se ignora la mano apoyada`() {
        val guard = PalmGuard(windowMs = 1500L)
        guard.noteStylus(10_000L)
        assertFalse("la palma llega justo después", guard.accepts(ToolKind.FINGER, 10_200L))
        assertTrue("el lápiz nunca se ignora", guard.accepts(ToolKind.STYLUS, 10_200L))
    }

    @Test
    fun `pasada la ventana el dedo vuelve a valer`() {
        val guard = PalmGuard(windowMs = 1500L)
        guard.noteStylus(10_000L)
        assertTrue(guard.accepts(ToolKind.FINGER, 11_600L))
    }

    @Test
    fun `un raton o similar nunca se descarta`() {
        val guard = PalmGuard()
        guard.noteStylus(10_000L)
        assertTrue(guard.accepts(ToolKind.OTHER, 10_100L))
    }
}
