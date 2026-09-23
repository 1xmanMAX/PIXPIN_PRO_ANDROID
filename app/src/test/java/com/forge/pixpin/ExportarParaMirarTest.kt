package com.forge.pixpin

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Solo con `PAGINA_EXPORTADA=<ruta>`: escribe una página exportada de documento para mirarla fuera. */
class ExportarParaMirarTest {
    @Test
    fun `escribe una pagina de documento exportada`() {
        val ruta = System.getenv("PAGINA_EXPORTADA")
        assumeTrue(ruta != null)
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val html = "<!DOCTYPE html><html lang=\"es\"><head><style>body{font:16px/1.5 serif}img{max-width:100%}</style></head><body><main>" +
            "<h1>Título</h1><p>Un párrafo con texto.</p><p><img alt=\"\" src=\"data:image/png;base64,$png\" width=\"200\" height=\"100\"></p>" +
            (1..80).joinToString("") { "<p>Párrafo $it para que haya que desplazarse.</p>" } + "</main></body></html>"
        val hoja = com.forge.pixpin.motor.DocumentoAnotado.hoja("Doc", html, 600, 400, emptyList(), emptyList(), emptyList(), 100, "#fff")
        java.io.File(ruta!!).writeText(com.forge.pixpin.motor.ExportarHtml.paginas(listOf(hoja), "Doc", "Doc (anotado)"))
    }
}
