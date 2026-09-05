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

class MarkdownHtmlSaltosTest {
    @org.junit.Test
    fun `un parrafo con su minuto es un salto, y un pdf adjunto se baja`() {
        val texto = "[1:23] Segundo punto de la reunión.\n\n![informe](/x/informe.pdf)\n\nSin minuto."
        val html = com.forge.pixpin.motormd.MarkdownHtml.deTexto(texto) { ruta ->
            if (ruta.endsWith(".pdf")) "data:application/pdf;base64,AAAA" else null
        }
        org.junit.Assert.assertTrue(html, html.contains("<a class=\"salto\" data-ms=\"83000\" href=\"#\">1:23</a> Segundo punto"))
        org.junit.Assert.assertTrue(html, html.contains("class=\"adjunto\" download=\"informe\""))
        org.junit.Assert.assertTrue(html, html.contains("<p>Sin minuto.</p>"))
    }
}

class MarkdownHtmlAudioTest {
    @org.junit.Test
    fun `un audio de la nota viaja como reproductor`() {
        val texto = "# Transcripción\n\n![audio](/data/notas/1-voz.m4a)\n\nHola, esto es la obra."
        val html = com.forge.pixpin.motormd.MarkdownHtml.deTexto(texto) { ruta ->
            if (ruta.endsWith(".m4a")) "data:audio/mp4;base64,AAAA" else null
        }
        org.junit.Assert.assertTrue(html, html.contains("<audio controls"))
        org.junit.Assert.assertTrue(html, html.contains("src=\"data:audio/mp4;base64,AAAA\""))
        org.junit.Assert.assertTrue("sin el nombre «audio» de relleno", !html.contains("<figcaption>audio"))
        // Sin quien lo lea, se dice que estaba.
        val sinDatos = com.forge.pixpin.motormd.MarkdownHtml.deTexto(texto)
        org.junit.Assert.assertTrue(sinDatos, sinDatos.contains("Audio: 1-voz.m4a"))
    }
}
