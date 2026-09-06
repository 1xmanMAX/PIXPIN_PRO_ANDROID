package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compartir como enlace: leer la respuesta, decir la verdad de lo que dura y a cuál cabe. */
class SubirArchivoTest {

    @Test
    fun `la direccion pelada y la que viene en un JSON`() {
        // litterbox y temp.sh contestan la dirección y nada más.
        assertEquals("https://litter.catbox.moe/abc123.pdf", SubirArchivo.enlaceDe("https://litter.catbox.moe/abc123.pdf\n"))
        assertEquals("https://temp.sh/IpYWa/proyecto.pdf", SubirArchivo.enlaceDe("https://temp.sh/IpYWa/proyecto.pdf"))
        // Y si alguno la trajera dentro de un JSON, también.
        val json = """{"id":"DgTBM2","link":"https:\/\/ejemplo.com\/DgTBM2"}"""
        assertEquals("https://ejemplo.com/DgTBM2", SubirArchivo.enlaceDe(json))
    }

    @Test
    fun `una queja no es un enlace`() {
        assertNull(SubirArchivo.enlaceDe("uploads disabled because it has been nothing but spam"))
        assertNull(SubirArchivo.enlaceDe(""))
        assertNull(SubirArchivo.enlaceDe("""{"status":"error","message":"Invalid file type."}"""))
        // Y lo que conteste se puede enseñar, sin etiquetas ni saltos.
        assertEquals("500 Internal Server Error", SubirArchivo.motivoDe("<html>\n <h1>500  Internal\nServer Error</h1></html>"))
        assertEquals("el servicio no contestó nada", SubirArchivo.motivoDe("   "))
    }

    /**
     * **Ningún enlace se queda para siempre.** Lo que la aplicación dice que dura tiene que
     * ser lo que el servicio publica; por eso quedaron fuera catbox («2 years of inactivity»)
     * y kappa.lol, que no dice cuánto guarda. Si alguien añade uno aquí, esto se lo recuerda.
     */
    @Test
    fun `todos los servicios caducan y lo dicen`() {
        assertTrue(SubirArchivo.SERVICIOS.isNotEmpty())
        for (s in SubirArchivo.SERVICIOS) {
            assertTrue("«${s.nombre}» tiene que decir cuánto dura", s.caduca.isNotBlank())
            assertTrue("«${s.nombre}» promete que se borra", s.caduca.contains("se borra"))
            assertTrue(s.topeMb > 0)
        }
        // El de una hora es el que se ofrece marcado: lo que dura mirar algo que te mandan.
        assertEquals("litter1h", SubirArchivo.POR_DEFECTO.id)
        assertEquals("litter1h", SubirArchivo.porId("no existe").id)
        assertEquals("tempsh", SubirArchivo.porId("tempsh").id)
    }

    @Test
    fun `lo que no cabe no se ofrece`() {
        val litter = SubirArchivo.porId("litter1h")  // 1 GB
        val grande = SubirArchivo.porId("tempsh")    // 4 GB
        assertTrue(SubirArchivo.cabe(litter, 900_000_000))
        assertFalse(SubirArchivo.cabe(litter, 1_400_000_000))
        assertTrue(SubirArchivo.cabe(grande, 1_400_000_000))
    }

    /** Una página web solo se abre sola en el que sirve el archivo con su tipo. */
    @Test
    fun `se sabe cual abre el archivo y cual lo hace bajar`() {
        assertTrue(SubirArchivo.porId("litter1h").abreElArchivo)
        assertFalse(SubirArchivo.porId("tempsh").abreElArchivo)
    }

    @Test
    fun `el nombre lleva su extension y no rompe la cabecera`() {
        assertEquals("croquis.html", SubirArchivo.sano("croquis", "html"))
        assertEquals("croquis.html", SubirArchivo.sano("croquis.html", "html"))
        assertEquals("plano.pdf", SubirArchivo.sano("plano", ".pdf"))
        assertEquals("nave-1.pixpin", SubirArchivo.sano("nave\"1", "pixpin"))
        assertEquals("a-b.pdf", SubirArchivo.sano("a\r\nb", "pdf"))
        assertEquals("archivo.pdf", SubirArchivo.sano("   ", "pdf"))
        assertEquals("suelto", SubirArchivo.sano("suelto", ""))
    }
}
