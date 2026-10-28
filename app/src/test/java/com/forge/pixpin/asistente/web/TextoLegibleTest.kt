package com.forge.pixpin.asistente.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El HTML hecho texto legible** (28-oct-2026): las pruebas de `html.rs` y `extraer.rs` del PC
 * (piezas, entidades, artículo, tablas, listas, oculto, enlaces) y alguna más del teléfono.
 */
class TextoLegibleTest {
    private fun base() = Direccion.leer("https://www.uni.edu/estudios/grado.html")

    // ------------------------------------------------------------------------------ html.rs

    @Test fun `parte etiquetas atributos y texto`() {
        val p = Html.piezas("""<p class=a id="b">Hola <a href='/x?a=1&amp;b=2'>mundo</a><br/></p>""")
        assertEquals(p.toString(), 7, p.size)
        assertEquals("a", p[0].atributo("class"))
        assertEquals("b", p[0].atributo("id"))
        assertEquals(Html.Pieza.Texto("Hola "), p[1])
        assertEquals("/x?a=1&b=2", p[2].atributo("href"))
        assertEquals(Html.Pieza.Cierra("a"), p[4])
        assertTrue((p[5] as Html.Pieza.Abre).nombre == "br")
    }

    @Test fun `el script es crudo y los comentarios no salen`() {
        val p = Html.piezas("<SCRIPT>if (a<b) { x='</p>' }</script><!-- <b>no</b> -->fin")
        assertEquals("script", (p[0] as Html.Pieza.Abre).nombre)
        assertEquals(Html.Pieza.Texto("if (a<b) { x='</p>' }"), p[1])
        assertTrue(Html.Pieza.Texto("fin") in p)
        assertFalse(p.any { it is Html.Pieza.Texto && "no" in it.t })
    }

    @Test fun `lo roto no cuelga`() {
        for (raro in listOf("<", "<a", "<a href=", "<a href='x", "</", "<!--", "a < b", "<script>x", "&#xZZ;")) Html.piezas(raro)
        assertEquals(listOf(Html.Pieza.Texto("a < b")), Html.piezas("a < b"))
    }

    @Test fun `lee las entidades`() {
        assertEquals("Matemáticas & Física – 2º", Html.entidades("Matem&aacute;ticas &amp; F&#237;sica &#x2013; 2&ordm;"))
        assertEquals("–", Html.entidades("&#150;"))
        assertEquals("a &desconocida; b & c", Html.entidades("a &desconocida; b & c"))
        assertEquals(" ", Html.entidades("&nbsp"))
        assertEquals("😀", Html.entidades("&#128512;"))
    }

    // --------------------------------------------------------------------------- extraer.rs

    @Test fun `se queda con el articulo y tira menus codigo y lo oculto`() {
        val html = """<!DOCTYPE html><html><head><title>Horarios — Uni</title>
            <style>body{color:red}</style><script>var x = "<p>no</p>";</script></head>
            <body><header><a href="/">Inicio</a> <a href="/login">Entrar</a></header>
            <nav><ul><li><a href="/a">Menu A</a></li></ul></nav>
            <main><h1>Horario de 1º</h1>
            <p>Las clases empiezan el <b>15 de septiembre</b>. Consulta las
               aulas en la <a href="aulas.pdf">lista de aulas</a>.</p>
            <p>Texto de relleno para que el articulo tenga suficiente texto y se elija
               como lo principal de la pagina en vez del resto, que es menu.</p>
            <div hidden>Oculto</div><span style="display: none">Tambien oculto</span>
            <div aria-hidden="true">Aria oculto</div><div role="navigation">Migas</div>
            </main>
            <aside>Publicidad</aside><footer>© Uni</footer></body></html>"""
        val p = Extraer.deHtml(html, base())
        assertEquals("Horarios — Uni", p.titulo)
        assertTrue(p.texto, p.texto.startsWith("# Horario de 1º"))
        assertTrue(p.texto, p.texto.contains("empiezan el 15 de septiembre. Consulta"))
        assertTrue(p.texto, p.texto.contains("lista de aulas [1]."))
        for (fuera in listOf("Menu A", "Entrar", "Publicidad", "© Uni", "var x", "color:red", "Oculto", "no</p>", "Aria oculto", "Migas"))
            assertFalse("$fuera en ${p.texto}", p.texto.contains(fuera))
        assertEquals(listOf(Extraer.Enlace("lista de aulas", "https://www.uni.edu/estudios/aulas.pdf")), p.enlaces)
    }

    @Test fun `un article corto no basta y se lee la pagina entera`() {
        val p = Extraer.deHtml("<body><p>Antes del articulo</p><article>Poco</article></body>", base())
        assertEquals("Antes del articulo\n\nPoco", p.texto)
    }

    @Test fun `las tablas salen fila a fila`() {
        val html = """<table><thead><tr><th>Hora</th><th>Lunes</th><th>Martes</th></tr></thead>
            <tbody><tr><td>8:00</td><td><p>Cálculo</p><br>Aula 3</td><td></td></tr>
            <tr><td>10:00</td><td>Física&nbsp;I</td><td>Química</td></tr></tbody></table>"""
        assertEquals(
            "| Hora | Lunes | Martes |\n| 8:00 | Cálculo Aula 3 | |\n| 10:00 | Física I | Química |",
            Extraer.deHtml(html, base()).texto
        )
    }

    @Test fun `listas titulos y pre`() {
        val html = "<h2>Pasos</h2><ol><li>Uno</li><li>Dos <a href='https://x.com/d'>aqui</a></li></ol><pre>a   b\n  c</pre><p>fin</p>"
        assertEquals("## Pasos\n\n- Uno\n- Dos aqui [1]\n\na   b\n  c\n\nfin", Extraer.deHtml(html, base()).texto)
    }

    @Test fun `sin main lee el cuerpo entero y repite el numero del mismo enlace`() {
        val html = "<body><div>Hola <a href='/x'>x</a></div><div>otra vez <a href='/x'>x</a></div><a href='mailto:a@b.c'>correo</a> <a href='#top'>arriba</a></body>"
        val p = Extraer.deHtml(html, base())
        assertEquals("Hola x [1]\notra vez x [1]\ncorreo arriba", p.texto)
        assertEquals(1, p.enlaces.size)
    }

    @Test fun `no pasa de los enlaces maximos`() {
        val html = (0 until 200).joinToString("") { "<p><a href='/p$it'>p$it</a></p>" }
        val p = Extraer.deHtml(html, base())
        assertEquals(Extraer.MAX_ENLACES, p.enlaces.size)
        assertTrue(p.texto.contains("p199"))
    }

    @Test fun `una imagen es el texto de su enlace`() {
        val p = Extraer.deHtml("<p><a href='/pdf'><img src='i.png' alt='Horario en PDF'></a></p>", base())
        assertEquals("Horario en PDF [1]", p.texto)
        assertEquals("https://www.uni.edu/pdf", p.enlaces.single().url)
    }
}
