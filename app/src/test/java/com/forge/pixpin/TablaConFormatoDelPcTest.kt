package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * **El formato de celdas del PC** (13-oct-2026): cursiva, subrayado, tachado, color de letra,
 * ajustar y formato de número viajan con sus nombres de una letra, no se pierden al guardar aquí
 * y se enseñan con el mismo texto que el PC. Y lo que no se conoce, tampoco se pierde.
 */
class TablaConFormatoDelPcTest {

    private val delPc = """{"nombre":"Metrados","celdas":{"A1":"1234.5","B1":"0.125","C1":"=FECHA(2026;10;11)","D1":"hola"},""" +
        """"estilos":{"A1":{"n":true,"k":true,"s":true,"t":true,"c":"#c00000","w":true,"nf":"moneda","zz":7},""" +
        """"B1":{"nf":"porcentaje"},"C1":{"nf":"fecha"},"D1":{"nf":"numero"},"E9":{"futuro":true}},"tocado":5,"logo":"x.png"}"""

    @Test
    fun `los seis campos nuevos se leen y se vuelven a escribir con sus nombres`() {
        val t = TablaDeCalculo.deJson(delPc)!!
        val e = t.estilos["A1"]!!
        assertEquals(EstiloDeCelda(n = true, k = true, s = true, t = true, c = "#c00000", w = true, nf = "moneda"), e)
        val vuelta = TablaDeCalculo.aJsonSobre(TablaViva(t).aTabla(9), delPc)
        val j = Json.parseToJsonElement(vuelta).jsonObject
        val a1 = j["estilos"]!!.jsonObject["A1"]!!.jsonObject
        assertEquals(setOf("n", "k", "s", "t", "c", "w", "nf", "zz"), a1.keys)
        assertEquals("x.png", j["logo"].toString().trim('"'))
        assertTrue("la celda con solo lo desconocido sigue", j["estilos"]!!.jsonObject.containsKey("E9"))
        // Caso negativo: lo que vale lo de por defecto no se escribe.
        assertEquals("{\"nf\":\"porcentaje\"}", j["estilos"]!!.jsonObject["B1"].toString())
    }

    @Test
    fun `cada celda se ve con el texto del PC`() {
        val v = TablaViva(TablaDeCalculo.deJson(delPc)!!)
        val antes = TablaViva.moneda
        try {
            TablaViva.moneda = "S/"
            assertEquals("S/ 1,234.50", v.visto(0, 0))
            assertEquals("12.50%", v.visto(0, 1))
            assertEquals("11/10/2026", v.visto(0, 2))
            assertEquals("hola", v.visto(0, 3)) // un texto no tiene formato de número
            assertEquals("", v.visto(5, 5))
        } finally { TablaViva.moneda = antes }
    }

    @Test
    fun `los formatos de numero dan lo que el visor de Excel del PC`() {
        fun f(x: Double, nf: FormatoDeNumero) = CalculoFormato.conFormatoDeCelda(x, nf, "S/")
        assertEquals("1,234.50", f(1234.5, FormatoDeNumero.NUMERO))
        assertEquals("-1,234.57", f(-1234.567, FormatoDeNumero.NUMERO))
        assertEquals("2.68", f(2.675, FormatoDeNumero.NUMERO))
        assertEquals("0.00", f(-0.001, FormatoDeNumero.NUMERO))
        assertEquals("-S/ 0.00", f(-0.001, FormatoDeNumero.MONEDA))
        assertEquals("S/ 1,234,567.13", f(1234567.125, FormatoDeNumero.MONEDA))
        assertEquals("12.50%", f(0.125, FormatoDeNumero.PORCENTAJE))
        assertEquals("29/02/1900", f(60.0, FormatoDeNumero.FECHA))
        assertEquals("11/10/2026", f(46306.99999, FormatoDeNumero.FECHA))
        assertEquals("12/10/2026", f(46306.999999, FormatoDeNumero.FECHA)) // redondeado al segundo
        assertEquals("###########", f(-1.0, FormatoDeNumero.FECHA))
        // Una palabra que no se conoce (de una versión más nueva) es «General».
        assertNull(FormatoDeNumero.de("cientifico"))
    }

    @Test
    fun `borrar formato deja la marca de editable y lo desconocido`() {
        assertEquals(EstiloDeCelda(e = true), EstiloDeCelda(n = true, k = true, e = true, nf = "fecha").sinElFormato())
        assertNull(EstiloDeCelda(n = true, c = "#c00000").sinElFormato())
    }

    @Test
    fun `guardar en archivo no pierde lo que no se conoce`() {
        val carpeta = Files.createTempDirectory("tablas").toFile()
        try {
            val disco = TablasEnDisco(carpeta)
            disco.archivo("t-1").apply { parentFile!!.mkdirs() }.writeText(delPc)
            val t = disco.cargar("t-1")!!
            assertTrue(disco.guardar("t-1", t.copy(celdas = t.celdas + ("A2" to "2"))))
            val j = Json.parseToJsonElement(disco.archivo("t-1").readText()).jsonObject
            assertEquals("\"x.png\"", j["logo"].toString())
            assertEquals("\"2\"", j["celdas"]!!.jsonObject["A2"].toString())
            assertEquals("7", j["estilos"]!!.jsonObject["A1"]!!.jsonObject["zz"].toString())
        } finally { carpeta.deleteRecursively() }
    }
}
