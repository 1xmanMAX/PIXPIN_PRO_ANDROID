package com.forge.pixpin.motormd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las imágenes de una nota viajan dentro de la página web.** Antes solo salía «Imagen:
 * foto.jpg», porque la nota guarda una ruta del teléfono; ahora quien exporta le da la imagen
 * ya pequeña como `data:` y va como `<img>`. Sin ella, se sigue diciendo que la había.
 */
class MarkdownHtmlImagenTest {

    @Test
    fun `con quien la lea, la imagen va dentro`() {
        val html = MarkdownHtml.deTexto("Hola\n\n![La obra](/sdcard/f.jpg)\n\nAdiós") { ruta ->
            if (ruta == "/sdcard/f.jpg") "data:image/webp;base64,AAA" else null
        }
        assertTrue(html, html.contains("<img src=\"data:image/webp;base64,AAA\" alt=\"La obra\""))
        assertTrue(html.contains("<figcaption>La obra</figcaption>"))
        assertFalse(html.contains("Imagen:"))
    }

    @Test
    fun `sin quien la lea, se dice que la habia`() {
        val html = MarkdownHtml.deTexto("![x](/sdcard/f.jpg)")
        assertTrue(html, html.contains("Imagen"))
        assertFalse(html.contains("<img"))
    }
}
