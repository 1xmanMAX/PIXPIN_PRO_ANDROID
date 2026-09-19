package com.forge.pixpin

import com.forge.pixpin.motor.Lectura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LecturaTest {
    @Test
    fun `el estilo se pone antes de cerrar la cabecera y no se acumula`() {
        val pagina = "<html><head><title>x</title></head><body><p>hola</p></body></html>"
        val una = Lectura.conEstilo(pagina, grosor = 2, letra = 1)
        assertTrue(una.contains("font-weight:600 !important"))
        assertTrue(una.contains("font-family:sans-serif !important"))
        assertTrue(una.indexOf("pixpin-lector") < una.indexOf("</head>"))
        val dos = Lectura.conEstilo(una, grosor = 0, letra = 2)
        assertEquals(1, Regex("pixpin-lector").findAll(dos).count())
        assertTrue(dos.contains("font-weight:300") && dos.contains("monospace"))
        // Sin cabecera también vale, y un índice fuera de la lista cae en lo normal.
        assertTrue(Lectura.conEstilo("<p>a</p>", 99, 99).startsWith("<style"))
        assertTrue(Lectura.estilo(99, 99).contains("font-weight:400"))
    }

    @Test
    fun `los marcadores van en el orden del documento y se guardan en una línea`() {
        var m = Lectura.conMarcador(emptyList(), 0.8f, "⭐", 1)
        m = Lectura.conMarcador(m, 0.2f, "🔖", 2)
        m = Lectura.conMarcador(m, 1.7f, "❤️", 3)
        assertEquals(listOf("🔖", "⭐", "❤️"), m.map { it.emoji })
        assertEquals(1f, m.last().fraccion)
        assertEquals(m, Lectura.deTexto(Lectura.aTexto(m)))
        assertTrue(Lectura.deTexto("roto|1:x:y|").isEmpty())
        assertTrue(Lectura.deTexto(null).isEmpty())
    }

    @Test
    fun `el punto bajo el dedo no se sale de la fila`() {
        assertEquals(0, Lectura.puntoBajoElDedo(-30f, 40f, 3))
        assertEquals(1, Lectura.puntoBajoElDedo(55f, 40f, 3))
        assertEquals(2, Lectura.puntoBajoElDedo(900f, 40f, 3))
        assertEquals(-1, Lectura.puntoBajoElDedo(10f, 40f, 0))
    }

    @Test
    fun `el tamaño de la letra tiene topes`() {
        assertEquals(Lectura.TAMANO_MIN, Lectura.tamanoValido(10))
        assertEquals(Lectura.TAMANO_MAX, Lectura.tamanoValido(900))
        assertEquals(130, Lectura.tamanoValido(130))
    }
}
