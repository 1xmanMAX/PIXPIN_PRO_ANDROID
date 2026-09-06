package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compartir la página como enlace: leer la respuesta del servicio y elegir a cuál cabe. */
class SubirPaginaTest {

    @Test
    fun `la direccion pelada y la que viene en un JSON`() {
        // litterbox y catbox contestan la dirección y nada más.
        assertEquals("https://litter.catbox.moe/abc123.html", SubirPagina.enlaceDe("https://litter.catbox.moe/abc123.html\n"))
        // kappa la trae dentro de un JSON, y a veces con las barras escapadas.
        val json = """{"id":"DgTBM2","ext":".html","link":"https:\/\/kappa.lol\/DgTBM2","delete":"https://kappa.lol/x"}"""
        assertEquals("https://kappa.lol/DgTBM2", SubirPagina.enlaceDe(json))
    }

    @Test
    fun `una queja no es un enlace`() {
        assertNull(SubirPagina.enlaceDe("uploads disabled because it has been nothing but spam"))
        assertNull(SubirPagina.enlaceDe(""))
        assertNull(SubirPagina.enlaceDe("""{"status":"error","message":"Invalid file type."}"""))
        // Y lo que conteste se puede enseñar, sin etiquetas ni saltos.
        assertEquals("500 Internal Server Error", SubirPagina.motivoDe("<html>\n <h1>500  Internal\nServer Error</h1></html>"))
        assertEquals("el servicio no contestó nada", SubirPagina.motivoDe("   "))
    }

    @Test
    fun `cada servicio dice cuanto admite y cuanto dura`() {
        for (s in SubirPagina.SERVICIOS) {
            assertTrue("«${s.nombre}» tiene que decir cuánto dura", s.caduca.isNotBlank())
            assertTrue(s.topeMb > 0)
        }
        // El de una hora es el que se ofrece marcado: lo que dura mirar algo que te mandan.
        assertEquals("litter1h", SubirPagina.POR_DEFECTO.id)
        assertEquals("litter1h", SubirPagina.porId("no existe").id)
        assertEquals("kappa", SubirPagina.porId("kappa").id)
    }

    @Test
    fun `lo que no cabe no se ofrece`() {
        val kappa = SubirPagina.porId("kappa")
        val litter = SubirPagina.porId("litter1h")
        assertTrue(SubirPagina.cabe(kappa, 40_000_000))
        assertFalse(SubirPagina.cabe(kappa, 140_000_000))
        assertTrue(SubirPagina.cabe(litter, 140_000_000))
    }

    @Test
    fun `el nombre del archivo no rompe la cabecera`() {
        assertEquals("croquis.html", SubirPagina.sano("croquis"))
        assertEquals("croquis.html", SubirPagina.sano("croquis.html"))
        assertEquals("nave-1.html", SubirPagina.sano("nave\"1"))
        assertEquals("a-b.html", SubirPagina.sano("a\r\nb"))
        assertEquals("pagina.html", SubirPagina.sano("   "))
    }
}
