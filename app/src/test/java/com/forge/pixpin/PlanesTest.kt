package com.forge.pixpin.planes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El andamio de los planes (30-oct-2026): mientras la app sea gratis, todo está abierto. */
class PlanesTest {

    @Test fun hoyEsGratisYLoTieneTodo() {
        assertEquals(Plan.GRATIS, Planes.actual)
        for (f in FuncionDePago.entries) {
            assertTrue(f.name, Planes.tiene(f))
            for (p in Plan.entries) assertTrue("${p.name} ${f.name}", Planes.tiene(f, p))
        }
    }

    @Test fun cadaFuncionCandidataSeExplica() {
        assertTrue(FuncionDePago.entries.isNotEmpty())
        for (f in FuncionDePago.entries) assertTrue(f.name, f.descripcion.isNotBlank())
    }
}
