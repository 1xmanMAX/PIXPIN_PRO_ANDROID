package com.forge.pixpin.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationGeometryTest {

    @Test
    fun `rectFrom normaliza min y max`() {
        val r = AnnotationGeometry.rectFrom(Pt(10f, 20f), Pt(5f, 30f))
        assertEquals(5f, r[0], 0.001f)
        assertEquals(20f, r[1], 0.001f)
        assertEquals(10f, r[2], 0.001f)
        assertEquals(30f, r[3], 0.001f)
    }

    @Test
    fun `arrowHead apunta hacia atras`() {
        val start = Pt(0f, 0f)
        val end = Pt(100f, 0f)
        val (h1, h2) = AnnotationGeometry.arrowHead(start, end, 20f)
        // La punta debe quedar detrás del extremo (x < 100)
        assertTrue(h1.x < 100f)
        assertTrue(h2.x < 100f)
        // Simétrica respecto al eje de la flecha
        assertEquals(h1.y, -h2.y, 0.001f)
        // Longitud aproximada pedida
        assertEquals(20f, AnnotationGeometry.distance(end, h1), 0.5f)
    }

    @Test
    fun `boundingBox incluye el grosor`() {
        val a = Annotation(AnnotationType.RECT, listOf(Pt(0f, 0f), Pt(10f, 10f)), 0, 4f)
        val bb = AnnotationGeometry.boundingBox(a)
        assertEquals(-2f, bb[0], 0.001f)
        assertEquals(12f, bb[2], 0.001f)
    }

    @Test
    fun `pathHitsAnnotation detecta cruce y no cruce`() {
        val rect = Annotation(AnnotationType.RECT, listOf(Pt(0f, 0f), Pt(100f, 100f)), 0, 4f)
        val crossing = listOf(Pt(-10f, 50f), Pt(50f, 50f), Pt(200f, 50f))
        val farAway = listOf(Pt(300f, 300f), Pt(400f, 400f))
        assertTrue(AnnotationGeometry.pathHitsAnnotation(crossing, rect, 10f))
        assertFalse(AnnotationGeometry.pathHitsAnnotation(farAway, rect, 10f))
    }

    @Test
    fun `boxesIntersect`() {
        val a = floatArrayOf(0f, 0f, 10f, 10f)
        assertTrue(AnnotationGeometry.boxesIntersect(a, floatArrayOf(5f, 5f, 15f, 15f)))
        assertFalse(AnnotationGeometry.boxesIntersect(a, floatArrayOf(20f, 0f, 30f, 10f)))
    }
}
