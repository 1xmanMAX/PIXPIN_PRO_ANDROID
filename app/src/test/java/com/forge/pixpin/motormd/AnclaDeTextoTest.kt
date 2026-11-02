package com.forge.pixpin.motormd

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Anclar las marcas de texto** (2-nov-2026): las pruebas de `marcas-texto.test.mjs` del PC. */
class AnclaDeTextoTest {
    private val t = "La losa ya está hormigonada. El curado empieza mañana y la losa se riega dos veces al día."

    private fun sitio(t: String, q: String, n: Int = 0): Pair<Int, Int> {
        var i = -1
        repeat(n + 1) { i = t.indexOf(q, i + 1) }
        return i to i + q.length
    }

    private fun marca(t: String, a: Int, b: Int): JsonObject = AnclaDeTexto.nueva(t, a, b, "resaltar", 4, 1000, emptyList())!!
    private fun cita(m: JsonObject, k: String) = m["cita"]!!.jsonObject[k]!!.jsonPrimitive.content

    @Test
    fun `el ancla lleva la cita sin blancos su contexto y donde estaba`() {
        val (a, b) = sitio(t, "hormigonada")
        val m = marca(t, a - 1, b + 1)
        assertEquals("TextQuoteSelector", cita(m, "type"))
        assertEquals("hormigonada.", cita(m, "exact"))
        assertEquals(a.toString(), m["posicion"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals(t.substring(0, a), cita(m, "prefix"))
        assertTrue(cita(m, "suffix").length <= AnclaDeTexto.CONTEXTO)
        assertNull(AnclaDeTexto.citar("a   b", 1, 4))
    }

    @Test
    fun `una cita que sale dos veces vuelve a la suya por el contexto`() {
        val (a, b) = sitio(t, "losa", 1)
        val m = marca(t, a, b)
        assertEquals(a to b, AnclaDeTexto.ubicar(t, m))
        assertEquals(a + 6 to b + 6, AnclaDeTexto.ubicar("Nota: $t", m))
    }

    @Test
    fun `si se escribio dentro de la cita lo de entre sus contextos`() {
        val (a, b) = sitio(t, "empieza mañana")
        val m = marca(t, a, b)
        val t2 = t.replace("empieza mañana", "empieza el lunes por la mañana")
        val r = AnclaDeTexto.ubicar(t2, m)!!
        assertEquals("empieza el lunes por la mañana", t2.substring(r.first, r.second))
    }

    @Test
    fun `una errata corregida en la cita y en su contexto la parecida`() {
        val t = "Uno. " + "x".repeat(50) + " El hormigon se cura con agua limpia siempre. " + "y".repeat(50)
        val (a, b) = sitio(t, "El hormigon se cura con agua")
        val m = marca(t, a, b)
        val t2 = t.replace("hormigon", "hormigón").replace("x".repeat(50), "z".repeat(50)).replace("limpia siempre", "clara")
        val r = AnclaDeTexto.ubicar(t2, m)
        assertNotNull(r)
        assertEquals("El hormigón se cura con agua", t2.substring(r!!.first, r.second))
    }

    @Test
    fun `caso negativo lo que ya no esta no se inventa`() {
        val (a, b) = sitio(t, "hormigonada")
        assertNull(AnclaDeTexto.ubicar("Otra nota que no habla de nada de eso.", marca(t, a, b)))
    }

    @Test
    fun `reanclar pone el ancla al dia y sigue al texto`() {
        val (a, b) = sitio(t, "curado")
        val m = marca(t, a, b)
        val t2 = t.replace("El curado", "Después, el curado")
        val r = AnclaDeTexto.reanclar(t2, m)!!
        assertEquals(sitio(t2, "curado"), r.first)
        assertTrue(cita(r.second, "prefix").endsWith("Después, el "))
        assertEquals("1000", r.second["cuando"]!!.jsonPrimitive.content)
        // Sin cambio de cita no hay que guardar; con cambio, la lista nueva.
        assertNull(AnclaDeTexto.alDia(t2, listOf(m)))
        val t3 = t.replace("curado", "curado lento")
        val m2 = marca(t, sitio(t, "El curado empieza").first, sitio(t, "El curado empieza").second)
        assertEquals("El curado lento empieza", cita(AnclaDeTexto.alDia(t3, listOf(m2))!![0], "exact"))
    }

    @Test
    fun `los pares sustitutos no se parten`() {
        val t = "hola 😀 adios"
        val i = t.indexOf("😀")
        assertEquals("😀", AnclaDeTexto.citar(t, i + 1, i + 2)!!.exact)
    }

    @Test
    fun `marcas nuevas las tocadas y los ids sin repetir`() {
        val (a, b) = sitio(t, "losa")
        val m1 = AnclaDeTexto.nueva(t, a, b, "resaltar", 4, 1000, emptyList())!!
        assertEquals("marca-texto-1000", MarcasDeTexto.id(m1))
        assertEquals("#F5C218", m1["color"]!!.jsonPrimitive.content)
        val m2 = AnclaDeTexto.nueva(t, a, b + 3, "subrayar", 2, 1000, listOf(m1))!!
        assertEquals("marca-texto-1001", MarcasDeTexto.id(m2))
        assertNull(AnclaDeTexto.nueva(t, a, b, "pintar", 0, 1, emptyList()))
        val sitios = mapOf(MarcasDeTexto.id(m1) to (a to b), MarcasDeTexto.id(m2) to (a to b + 3))
        assertEquals(listOf(m2), AnclaDeTexto.tocadas(listOf(m1, m2), sitios, b + 1, b + 2))
        assertEquals(2, AnclaDeTexto.tocadas(listOf(m1, m2), sitios, a + 1, a + 1).size)
        assertEquals(0, AnclaDeTexto.tocadas(listOf(m1, m2), sitios, b + 10, b + 12).size)
    }
}
