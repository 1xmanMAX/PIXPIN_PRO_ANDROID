package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El nombre con el que se guarda un archivo compartido. Ver [nombreConExtension].
 *
 * Android deduce de qué es un archivo **por su extensión**, así que uno guardado sin ella se
 * abre como «archivo» a secas y deja de abrirse con su programa. Es lo que pasaba al guardar
 * un PDF que llegaba sin nombre, solo con el asunto del envío.
 */
class NombreConExtensionTest {

    @Test
    fun `sin extension se le pone la de su tipo`() {
        assertEquals("Plano de la nave.pdf", nombreConExtension("Plano de la nave", "pdf"))
        assertEquals("foto.jpg", nombreConExtension("foto", "jpg"))
    }

    @Test
    fun `la que ya trae se respeta`() {
        assertEquals("plano.pdf", nombreConExtension("plano.pdf", "pdf"))
        // Aunque no cuadre con lo que dice el sistema: quien la puso sabía lo que hacía.
        assertEquals("datos.csv", nombreConExtension("datos.csv", "txt"))
    }

    /** Un punto en mitad de una frase no es una extensión. */
    @Test
    fun `un punto en la frase no cuenta como extension`() {
        assertEquals("Acta del 3. reunión.pdf", nombreConExtension("Acta del 3. reunión", "pdf"))
    }

    /** Un número de versión tampoco: «Plano v1.2» compartido como PDF es `Plano v1.2.pdf`. */
    @Test
    fun `una version no es una extension`() {
        assertEquals("Plano v1.2.pdf", nombreConExtension("Plano v1.2", "pdf"))
        assertEquals("informe.PDF", nombreConExtension("informe.PDF", "pdf"))
        assertEquals("nota.m4a", nombreConExtension("nota.m4a", "mp4"))
    }

    @Test
    fun `sin tipo conocido se queda como esta`() {
        assertEquals("cosa", nombreConExtension("cosa", null))
        assertEquals("cosa", nombreConExtension("cosa", "  "))
    }

    /** Y nunca sale un nombre que no se pueda escribir en el disco. */
    @Test
    fun `los caracteres que no valen en un nombre se cambian`() {
        val salida = nombreConExtension("informe/2026: v2?", "pdf")
        assertTrue("salió $salida", salida.none { it in "\\/:*?\"<>|" })
        assertTrue(salida.endsWith(".pdf"))
    }

    @Test
    fun `un nombre vacio no deja el archivo sin nombre`() {
        assertEquals("compartido.pdf", nombreConExtension("   ", "pdf"))
    }
}
