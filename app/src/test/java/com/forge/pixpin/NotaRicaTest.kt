package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.NotaRica.Imagen
import com.forge.pixpin.guardados.NotaRica.Trozo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **La nota rica del chat** (25-oct-2026, `docs/nota-rica-del-chat.md` del PC): cómo se parte el
 * Markdown en texto, álbumes y adjuntos, cómo se reparte el álbum y dónde está cada foto.
 */
class NotaRicaTest {
    private val foto1 = "pixpin:files/guardados/pc/casa/notas/1761400000000-pegado.png"
    private val foto2 = "pixpin:files/guardados/pc/casa/notas/1761400000001-planta.jpg"

    @Test
    fun `la nota del documento se parte en su texto y su album`() {
        val t = "Visita a la obra\n\n**Pendiente:** la escalera\n![]($foto1)\n![planta|300]($foto2)\n- [ ] medir hueco"
        val trozos = NotaRica.trozos(t)
        assertEquals(3, trozos.size)
        assertEquals(Trozo.Texto("Visita a la obra\n\n**Pendiente:** la escalera"), trozos[0])
        // El ancho del editor detrás de la barra no es parte del alt.
        assertEquals(Trozo.Album(listOf(Imagen("", foto1), Imagen("planta", foto2))), trozos[1])
        assertEquals(Trozo.Texto("- [ ] medir hueco"), trozos[2])
        // La ruta no aparece en ningún texto.
        assertTrue(trozos.filterIsInstance<Trozo.Texto>().none { "pixpin:" in it.markdown })
        assertTrue(NotaRica.tieneFormato(t))
    }

    @Test
    fun `fotos en el mismo renglon van juntas y una linea en blanco separa albumes`() {
        val t = "![a]($foto1) ![b]($foto2)\n\n![c]($foto1)"
        val trozos = NotaRica.trozos(t)
        assertEquals(listOf(
            Trozo.Album(listOf(Imagen("a", foto1), Imagen("b", foto2))),
            Trozo.Album(listOf(Imagen("c", foto1)))
        ), trozos)
        // Texto pegado a una foto: el texto va a su párrafo, en el orden en que se escribió.
        assertEquals(listOf(Trozo.Texto("antes"), Trozo.Album(listOf(Imagen("", foto1))), Trozo.Texto("despues")),
            NotaRica.trozos("antes ![]($foto1) despues"))
    }

    @Test
    fun `lo que no es una foto es un adjunto y no se pinta`() {
        val t = "acta\n![Acta firmada](pixpin:files/guardados/pc/casa/notas/1-acta.pdf)"
        assertEquals(listOf(Trozo.Texto("acta"), Trozo.Adjunto(Imagen("Acta firmada", "pixpin:files/guardados/pc/casa/notas/1-acta.pdf"))), NotaRica.trozos(t))
        assertTrue(NotaRica.esFoto("x/y.JPEG?v=2"))
        assertFalse(NotaRica.esFoto("x/y.mp3"))
        assertFalse(NotaRica.esFoto("sin-extension"))
    }

    @Test
    fun `un salto de linea es un salto de linea y el codigo no se toca`() {
        // Dos renglones de párrafo seguidos: se separan para que el Markdown no los junte.
        assertEquals(listOf(Trozo.Texto("uno\n\ndos")), NotaRica.trozos("uno\ndos"))
        // Una lista sigue siendo una lista, sin huecos de más.
        assertEquals(listOf(Trozo.Texto("- a\n- b")), NotaRica.trozos("- a\n- b"))
        // Dentro de un bloque de código ni fotos ni saltos.
        val codigo = "```\nuno\n![]($foto1)\n```"
        assertEquals(listOf(Trozo.Texto(codigo)), NotaRica.trozos(codigo))
        // Un `![` sin cerrar es texto.
        assertEquals(listOf(Trozo.Texto("mira ![esto")), NotaRica.trozos("mira ![esto"))
    }

    @Test
    fun `una nota llana se pinta como siempre`() {
        assertFalse(NotaRica.tieneFormato("hola, mañana a las 9"))
        assertFalse(NotaRica.tieneFormato("dos\nrenglones"))
        // Un enlace suelto ya tiene su tarjeta: no hace rica la nota.
        assertFalse(NotaRica.tieneFormato("mira https://ejemplo.com/x"))
        assertFalse(NotaRica.tieneFormato(""))
        // Casos positivos: negrita, título, lista, enlace con texto, una foto.
        assertTrue(NotaRica.tieneFormato("es **importante**"))
        assertTrue(NotaRica.tieneFormato("# Obra"))
        assertTrue(NotaRica.tieneFormato("- uno\n- dos"))
        assertTrue(NotaRica.tieneFormato("ver [el plano](https://ejemplo.com)"))
        assertTrue(NotaRica.tieneFormato("![]($foto1)"))
    }

    @Test
    fun `la ruta de una foto se resuelve en este aparato y nunca fuera`() {
        val files = Files.createTempDirectory("nota-rica").toFile()
        try {
            val f = File(files, "guardados/pc/casa/notas/1761400000000-pegado.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
            // La portátil, la de aquí (como llega tras `Rutas.aLocal`) y `file://`.
            assertEquals(f, NotaRica.archivo(files, foto1))
            assertEquals(f, NotaRica.archivo(files, f.absolutePath))
            assertEquals(f, NotaRica.archivo(files, "file://" + f.absolutePath))
            // Con espacios escritos `%20`.
            val g = File(files, "guardados/pc/casa/notas/2-mi plano.png").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(g, NotaRica.archivo(files, "pixpin:files/guardados/pc/casa/notas/2-mi%20plano.png"))
            // Casos negativos: una relativa, una que se sale con `..`, una web.
            assertNull(NotaRica.archivo(files, "notas/1-pegado.png"))
            assertNull(NotaRica.archivo(files, "pixpin:files/../secreto.png"))
            assertNull(NotaRica.archivo(files, "https://ejemplo.com/a.png"))
            // Una que aún no ha llegado: su fichero, que no existe (se pinta el recuadro).
            assertFalse(NotaRica.archivo(files, "pixpin:files/guardados/pc/casa/notas/9-falta.png")!!.isFile)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `el album va en filas de dos o tres`() {
        assertEquals(emptyList<Int>(), NotaRica.filasDelAlbum(0))
        assertEquals(listOf(1), NotaRica.filasDelAlbum(1))
        assertEquals(listOf(2), NotaRica.filasDelAlbum(2))
        assertEquals(listOf(1, 2), NotaRica.filasDelAlbum(3))
        assertEquals(listOf(2, 2), NotaRica.filasDelAlbum(4))
        assertEquals(listOf(3, 2), NotaRica.filasDelAlbum(5))
        assertEquals(listOf(3, 3), NotaRica.filasDelAlbum(6))
        // 3+3+1 se ve mal: 3+2+2.
        assertEquals(listOf(3, 2, 2), NotaRica.filasDelAlbum(7))
        for (n in 2..20) {
            val f = NotaRica.filasDelAlbum(n)
            assertEquals(n, f.sum())
            assertTrue(f.all { it in 1..3 })
        }
    }

    @Test
    fun `las cajas del album llenan el ancho y una sola guarda su proporcion`() {
        val (sola, altoSolo) = NotaRica.album(listOf(2f), 300f, 280f, 170f, 3f)
        assertEquals(1, sola.size)
        assertEquals(300f, sola[0].ancho, 1e-3f); assertEquals(150f, altoSolo, 1e-3f)
        // Una de pie muy alta se queda en el alto de una foto sola.
        val (dePie, _) = NotaRica.album(listOf(0.25f), 300f, 280f, 170f, 3f)
        assertEquals(280f, dePie[0].alto, 1e-3f)
        val (cajas, alto) = NotaRica.album(listOf(1f, 1f, 1f, 1f, 1f), 300f, 280f, 170f, 3f)
        assertEquals(5, cajas.size)
        // Cada fila acaba justo en el borde y la segunda va debajo de la primera.
        val filas = cajas.groupBy { it.y }
        assertEquals(2, filas.size)
        for (fila in filas.values) assertEquals(300f, fila.last().x + fila.last().ancho, 1e-3f)
        assertEquals(alto, cajas.last().y + cajas.last().alto, 1e-3f)
    }
}
