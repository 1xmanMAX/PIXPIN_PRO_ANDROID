package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.Renombrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenombrarTest {
    @Test fun conservaLaExtension() {
        assertEquals("Memoria.pdf", Renombrar.conSuExtension("informe (1).pdf", "  Memoria "))
        assertEquals("Memoria.PDF", Renombrar.conSuExtension("informe.pdf", "Memoria.PDF"))
        assertEquals("Clase de física.m4a", Renombrar.conSuExtension("voz_123.m4a", "Clase de física"))
        assertEquals("Clase 3", Renombrar.conSuExtension("Nota de voz 14:32", "Clase 3"))
        assertEquals("", Renombrar.conSuExtension("a.pdf", "   "))
        assertEquals("a-b.pdf", Renombrar.conSuExtension("x.pdf", "a/b"))
    }

    @Test fun quéSePuedeRenombrar() {
        fun m(c: Clase, ruta: String? = "/g/guardados/x") = Mensaje(id = "1", cuando = 1, clase = c, ruta = ruta)
        assertTrue(Renombrar.sePuede(m(Clase.VOZ)))
        assertTrue(Renombrar.sePuede(m(Clase.IMAGEN)))
        assertTrue(Renombrar.sePuede(m(Clase.ARCHIVO)))
        assertFalse(Renombrar.sePuede(m(Clase.NOTA, null)))
        assertFalse(Renombrar.sePuede(m(Clase.ARCHIVO, "/g/guardados/lecciones/a.leccion")))
    }
}
