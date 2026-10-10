package com.forge.pixpin

import com.forge.pixpin.sincro.RelojDeSincro
import com.forge.pixpin.sincro.RelojDeSincro.Companion.PERIODO
import com.forge.pixpin.sincro.RelojDeSincro.Companion.QUIETO
import com.forge.pixpin.sincro.RelojDeSincro.Motivo
import com.forge.pixpin.sincro.SincronizacionAutomatica
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cuándo sincroniza solo (las pruebas de `sincronizar/automatica.rs` del PC, con el reloj de aquí). */
class RelojDeSincroTest {
    private fun firma(n: Long) = RelojDeSincro.Firma(n, n, n)
    private val tel = setOf("tel")

    @Test fun `sin nadie que conteste no se sincroniza nunca`() {
        val r = RelojDeSincro()
        assertNull(r.toca(0, emptySet(), firma(1)))
        assertNull(r.toca(1_000, emptySet(), firma(2)))
        assertNull(r.toca(PERIODO * 5, emptySet(), firma(2)))
    }

    @Test fun `al aparecer un aparato se sincroniza enseguida y solo una vez`() {
        val r = RelojDeSincro()
        assertEquals(Motivo.APARECIO to tel, r.toca(1_000, tel, firma(1)))
        r.despuesDeSincronizar(firma(1))
        assertNull("sigue contestando: no vuelve a aparecer", r.toca(3_000, tel, firma(1)))
        assertNull(r.toca(4_000, emptySet(), firma(1)))
        assertEquals("se va y vuelve: otra vez", Motivo.APARECIO, r.toca(5_000, tel, firma(1))?.first)
    }

    @Test fun `un cambio aqui espera treinta segundos quieto`() {
        val r = RelojDeSincro()
        r.toca(0, tel, firma(1)); r.despuesDeSincronizar(firma(1))
        assertNull("aún escribiendo", r.toca(1_000, tel, firma(2)))
        assertNull(r.toca(1_000 + QUIETO - 1, tel, firma(2)))
        // Sigue cambiando: el reloj vuelve a empezar.
        assertNull(r.toca(20_000, tel, firma(3)))
        assertNull(r.toca(1_000 + QUIETO + 1, tel, firma(3)))
        assertEquals(Motivo.CAMBIO, r.toca(20_000 + QUIETO, tel, firma(3))?.first)
        // Lo que escribe la propia vuelta no es un cambio.
        r.despuesDeSincronizar(firma(9))
        assertNull(r.toca(20_000 + QUIETO * 3, tel, firma(9)))
    }

    @Test fun `cada diez minutos aunque no cambie nada`() {
        val r = RelojDeSincro()
        assertEquals(Motivo.APARECIO, r.toca(0, tel, firma(1))?.first)
        r.despuesDeSincronizar(firma(1))
        assertNull(r.toca(PERIODO - 1, tel, firma(1)))
        assertEquals(Motivo.PERIODO, r.toca(PERIODO, tel, firma(1))?.first)
    }

    @Test fun `la firma cambia al escribir un archivo de lo que viaja y no con la cache`() {
        val d = Files.createTempDirectory("pp-firma").toFile()
        try {
            File(d, "pins/draw").mkdirs(); File(d, "cache").mkdirs()
            val a = SincronizacionAutomatica.firma(d)
            File(d, "pins/draw/x.excalidraw").writeText("hola")
            val b = SincronizacionAutomatica.firma(d)
            assertNotEquals(a, b)
            assertEquals(a.ficheros + 1, b.ficheros)
            File(d, "cache/y.tmp").writeText("nada")
            assertEquals(b, SincronizacionAutomatica.firma(d))
        } finally { d.deleteRecursively() }
    }
}
