package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `tabla_escaneada.rs` del PC: sus pruebas y 27 tablas al azar comparadas con lo que da el PC. */
class TablaEscaneadaTest {
    private fun escribir(celdas: List<Triple<Int, Int, String>>, pasos: List<Double>): MutableList<TablaEscaneada.Palabra> {
        val v = mutableListOf<TablaEscaneada.Palabra>()
        for ((c, f, t) in celdas) {
            var x = pasos.take(c).sum() + 5.0
            val y = 20.0 * f + 3.0
            for (w in t.split(' ')) { val ancho = 6.0 * w.length; v += TablaEscaneada.Palabra(w, x, y, ancho, 10.0); x += ancho + 3.0 }
        }
        return v
    }

    @Test fun `una tabla sencilla sale igual`() {
        val t = TablaEscaneada.reconstruir(escribir(listOf(
            Triple(0, 0, "Calle"), Triple(1, 0, "Senales"), Triple(2, 0, "Costo"),
            Triple(0, 1, "Jr. Antisuyo"), Triple(1, 1, "12"), Triple(2, 1, "1 250.00"),
            Triple(0, 2, "Av. Tahuantinsuyo"), Triple(1, 2, "7"), Triple(2, 2, "830.50")), listOf(140.0, 80.0, 80.0)))
        assertEquals(listOf(listOf("Calle", "Senales", "Costo"), listOf("Jr. Antisuyo", "12", "1 250.00"), listOf("Av. Tahuantinsuyo", "7", "830.50")), t.filas)
        assertTrue(t.combinadas.isEmpty()); assertTrue(t.pareceCabecera())
    }

    @Test fun `las celdas vacias y el desorden no la rompen`() {
        val pal = escribir(listOf(Triple(0, 0, "A"), Triple(2, 0, "C"), Triple(0, 1, "a1"), Triple(1, 1, "b1"), Triple(2, 1, "c1"), Triple(1, 2, "b2")), listOf(60.0, 60.0, 60.0))
        pal.reverse()
        pal[0] = pal[0].copy(y = pal[0].y + 2.0)
        assertEquals(listOf(listOf("A", "", "C"), listOf("a1", "b1", "c1"), listOf("", "b2", "")), TablaEscaneada.reconstruir(pal).filas)
    }

    @Test fun `un titulo que abarca varias es combinada y no junta columnas`() {
        val pal = escribir(listOf(Triple(0, 1, "x"), Triple(1, 1, "y"), Triple(2, 1, "z"), Triple(0, 2, "1"), Triple(1, 2, "2"), Triple(2, 2, "3")), listOf(60.0, 60.0, 60.0))
        pal += TablaEscaneada.Palabra("RESUMEN", 5.0, 3.0, 80.0, 10.0)
        pal += TablaEscaneada.Palabra("DE", 88.0, 3.0, 12.0, 10.0)
        pal += TablaEscaneada.Palabra("COSTOS", 103.0, 3.0, 67.0, 10.0)
        val t = TablaEscaneada.reconstruir(pal)
        assertEquals(3, t.columnas)
        assertEquals(listOf("RESUMEN DE COSTOS", "", ""), t.filas[0])
        assertEquals(listOf(TablaEscaneada.Combinada(0, 0, 3)), t.combinadas)
        assertEquals(listOf("1", "2", "3"), t.filas[2])
    }

    @Test fun `los numeros del papel pasan a numeros de la hoja`() {
        assertEquals("1250.00", TablaEscaneada.comoNumero("1 250.00"))
        assertEquals("1250.50", TablaEscaneada.comoNumero("1.250,50"))
        assertEquals("1250.50", TablaEscaneada.comoNumero("1,250.50"))
        assertEquals("1250", TablaEscaneada.comoNumero("1.250"))
        assertEquals("830.5", TablaEscaneada.comoNumero("830,5"))
        assertEquals("12", TablaEscaneada.comoNumero("S/. 12"))
        assertEquals("-7", TablaEscaneada.comoNumero("-7"))
        assertEquals("0.75", TablaEscaneada.comoNumero("0.75"))
        for (no in listOf("R-1", "12/10/2026", "Jr. Antisuyo", "", "1,2,3")) assertNull(no, TablaEscaneada.comoNumero(no))
    }

    @Test fun `sin palabras no hay tabla y sin numeros no hay cabecera`() {
        assertEquals(TablaEscaneada.TablaLeida(), TablaEscaneada.reconstruir(emptyList()))
        assertEquals(0, TablaEscaneada.reconstruir(listOf(TablaEscaneada.Palabra("  ", 0.0, 0.0, 5.0, 10.0))).filas.size)
        val t = TablaEscaneada.reconstruir(escribir(listOf(Triple(0, 0, "12"), Triple(1, 0, "13"), Triple(0, 1, "4"), Triple(1, 1, "5")), listOf(50.0, 50.0)))
        assertTrue(!t.pareceCabecera()); assertEquals("12\t13\n4\t5", t.aTsv())
    }

    @Test fun `tablas al azar dan lo mismo que el PC`() {
        val entrada = Json.parseToJsonElement(javaClass.getResource("/ia/tabla-entrada.json")!!.readText()).jsonArray
        val delPc = Json.parseToJsonElement(javaClass.getResource("/ia/tabla-del-pc.json")!!.readText()).jsonArray
        for ((i, caso) in entrada.withIndex()) {
            val ps = caso.jsonArray.map { p -> val a = p.jsonArray; TablaEscaneada.Palabra(a[0].jsonPrimitive.content, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double, a[3].jsonPrimitive.double, a[4].jsonPrimitive.double) }
            val t = TablaEscaneada.reconstruir(ps)
            val e = delPc[i].jsonObject
            assertEquals("caso $i", e["filas"]!!.jsonArray.map { f -> f.jsonArray.map { it.jsonPrimitive.content } }, t.filas)
            assertEquals("caso $i", e["combinadas"]!!.jsonArray.map { c -> c.jsonArray.map { it.jsonPrimitive.int } }, t.combinadas.map { listOf(it.fila, it.columna, it.columnas) })
            assertEquals("caso $i", e["cabecera"]!!.jsonPrimitive.boolean, t.pareceCabecera())
            assertEquals("caso $i", e["numeros"]!!.jsonArray.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }, t.filas.flatten().map { TablaEscaneada.comoNumero(it) })
        }
    }
}
