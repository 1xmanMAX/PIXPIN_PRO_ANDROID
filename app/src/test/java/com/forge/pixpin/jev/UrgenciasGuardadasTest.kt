package com.forge.pixpin.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UrgenciasGuardadasTest {
    private fun s(c: List<Double>, b: Double = 0.1, p: Double = 0.0, y: Double? = null) =
        Urgencia.Senales(Escala.de(c), b, p, y)

    @Test
    fun ida_y_vuelta_con_lo_desconocido() {
        val dir = Files.createTempDirectory("urg").toFile()
        val f = File(dir, "anot-ABCDE23456.urgencia.json")
        f.writeText("{\"version\":1,\"tareas\":[],\"otra\":{\"x\":1}}")
        val g = UrgenciasGuardadas.Guardada(s(listOf(0.1, 0.2, 0.3, 0.4), y = 0.9), 1000, "pc1")
        assertTrue(UrgenciasGuardadas.anadir(f, mapOf("Mandar la cotización" to g)))
        val texto = f.readText()
        assertTrue(texto.endsWith("\n"))
        assertTrue(texto.contains("\"otra\""))
        val leido = UrgenciasGuardadas.leer(f)["Mandar la cotización"]!!
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), leido.senales.consecuencia.niveles)
        assertEquals(0.9, leido.senales.pidenYa!!, 1e-9)
        assertEquals("pc1", leido.aparato)
        // Lo mismo otra vez no reescribe.
        assertFalse(UrgenciasGuardadas.anadir(f, mapOf("Mandar la cotización" to g)))
    }

    @Test
    fun sin_piden_ya_y_gana_la_mas_nueva() {
        val a = UrgenciasGuardadas.Guardada(s(listOf(1.0, 0.0, 0.0, 0.0)), 10, "a")
        val b = UrgenciasGuardadas.Guardada(s(listOf(0.0, 0.0, 0.0, 1.0)), 20, "b")
        val j = UrgenciasGuardadas.juntar(mapOf("t" to a, "u" to a), mapOf("t" to b))
        assertEquals(b, j["t"])
        assertEquals(a, j["u"])
        // Conmutativo.
        assertEquals(j, UrgenciasGuardadas.juntar(mapOf("t" to b), mapOf("t" to a, "u" to a)))
        val vuelta = UrgenciasGuardadas.deTexto(UrgenciasGuardadas.texto(j))
        assertNull(vuelta["u"]!!.senales.pidenYa)
    }

    @Test
    fun un_json_roto_no_se_pisa() {
        val dir = Files.createTempDirectory("urg").toFile()
        val f = File(dir, "x.urgencia.json")
        f.writeText("{roto")
        val g = UrgenciasGuardadas.Guardada(s(listOf(0.5, 0.5)), 1, "")
        assertFalse(UrgenciasGuardadas.anadir(f, mapOf("t" to g)))
        assertEquals("{roto", f.readText())
    }
}
