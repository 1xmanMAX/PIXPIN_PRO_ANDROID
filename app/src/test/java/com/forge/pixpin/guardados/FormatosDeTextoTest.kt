package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.FormatosDeTexto.Como
import com.forge.pixpin.guardados.ModoDePaso.Recibido
import com.forge.pixpin.guardados.ModoDePaso.Visor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los archivos de texto, como en el PC** (27-oct-2026): qué se abre como Markdown y qué tal cual,
 * desde el chat y desde fuera, y cómo se lee lo que llega sin extensión. Ver [FormatosDeTexto].
 */
class FormatosDeTextoTest {

    @Test
    fun `la lista es la del PC, markdown compuesto y lo demas tal cual`() {
        assertEquals(Como.MARKDOWN, FormatosDeTexto.como("notas.md"))
        assertEquals(Como.MARKDOWN, FormatosDeTexto.como("LEEME.MARKDOWN"))
        for (e in listOf("txt", "log", "ini", "cfg", "srt", "vtt")) assertEquals(e, Como.LLANO, FormatosDeTexto.como("archivo.$e"))
        assertEquals(setOf("md", "markdown", "txt", "log", "ini", "cfg", "srt", "vtt"), FormatosDeTexto.TODAS)
        // El csv va al visor de hojas, no aquí; y lo que no es texto, tampoco.
        assertNull(FormatosDeTexto.como("datos.csv"))
        assertNull(FormatosDeTexto.como("informe.pdf"))
        assertNull(FormatosDeTexto.como("README"))
        assertNull(FormatosDeTexto.como(".md"))
        assertNull(FormatosDeTexto.como(null))
    }

    @Test
    fun `en el chat manda el nombre del mensaje y, sin extension, la ruta`() {
        assertEquals(Como.MARKDOWN, FormatosDeTexto.delMensaje("Apuntes.md", "/data/guardados/abc.md"))
        assertEquals(Como.LLANO, FormatosDeTexto.delMensaje("registro.log", "/data/guardados/abc.log"))
        // Un nombre sin extensión (un asunto): la ruta lo dice.
        assertEquals(Como.MARKDOWN, FormatosDeTexto.delMensaje("Apuntes de clase", "/data/guardados/abc.md"))
        assertEquals(Como.LLANO, FormatosDeTexto.delMensaje("", "/data/guardados/abc.txt"))
        // Un PDF no se abre aquí aunque alguien le pusiera otro nombre a la ruta.
        assertNull(FormatosDeTexto.delMensaje("informe.pdf", "/data/guardados/abc.md"))
        assertNull(FormatosDeTexto.delMensaje("foto.jpg", null))
    }

    @Test
    fun `de fuera, cada formato de texto va a su visor aunque el explorador diga octet-stream`() {
        assertEquals(Visor.MARKDOWN, ModoDePaso.visorPara("notas.md", "application/octet-stream"))
        assertEquals(Visor.MARKDOWN, ModoDePaso.visorPara("notas.md", "text/plain"))
        assertEquals(Visor.MARKDOWN, ModoDePaso.visorPara("notas.markdown", "*/*"))
        assertEquals(Visor.MARKDOWN, ModoDePaso.visorPara(null, "text/x-markdown"))
        assertEquals(Visor.MARKDOWN, ModoDePaso.visorPara(null, "text/markdown; charset=utf-8"))
        for (e in listOf("txt", "log", "ini", "cfg", "srt", "vtt")) assertEquals(e, Visor.TEXTO, ModoDePaso.visorPara("a.$e", "application/octet-stream"))
        assertEquals(Visor.TEXTO, ModoDePaso.visorPara(null, "text/plain"))
        assertEquals(Visor.TEXTO, ModoDePaso.visorPara(null, "text/x-log"))
        // Un tipo de texto cualquiera, sin nombre: tal cual.
        assertEquals(Visor.TEXTO, ModoDePaso.visorPara("", "text/x-desconocido"))
        // El csv sigue en el visor de hojas.
        assertEquals(Visor.HOJA, ModoDePaso.visorPara("datos.csv", "text/plain"))
    }

    @Test
    fun `sin extension ni tipo se mira dentro`() {
        val md = "# Título\n\n* uno\n* dos\n".toByteArray()
        assertEquals(Visor.NINGUNO, ModoDePaso.visorPara("documento", "application/octet-stream"))
        assertEquals(Visor.TEXTO, ModoDePaso.visorPara("documento", "application/octet-stream", md))
        assertEquals(Visor.TEXTO, ModoDePaso.visorPara(null, null, "hola\r\nmundo".toByteArray()))
        // Lo binario no: un PDF, un PNG, un ZIP.
        assertEquals(Visor.NINGUNO, ModoDePaso.visorPara("documento", "application/octet-stream", "%PDF-1.7\n%âã".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(Visor.NINGUNO, ModoDePaso.visorPara(null, null, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0)))
        // Con una extensión que no es de texto, no se mira dentro: manda la extensión.
        assertEquals(Visor.NINGUNO, ModoDePaso.visorPara("programa.apk", null, md))
    }

    @Test
    fun `lo compartido de texto se abre de paso`() {
        assertEquals(Recibido.DE_PASO, ModoDePaso.queHacerConLoCompartido(1, "notas.md", "application/octet-stream"))
        assertEquals(Recibido.DE_PASO, ModoDePaso.queHacerConLoCompartido(1, "server.log", null))
        assertEquals(Recibido.DE_PASO, ModoDePaso.queHacerConLoCompartido(1, null, "text/x-markdown"))
    }

    @Test
    fun `parece texto`() {
        assertTrue(FormatosDeTexto.pareceTexto("año, niño — «comillas»\tTab\n".toByteArray()))
        // Una letra cortada al final de la cabeza no lo estropea.
        val corto = "acció".toByteArray().let { it.copyOf(it.size - 1) }
        assertTrue(FormatosDeTexto.pareceTexto(corto))
        assertTrue(FormatosDeTexto.pareceTexto(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0)))
        assertFalse(FormatosDeTexto.pareceTexto(ByteArray(0)))
        assertFalse(FormatosDeTexto.pareceTexto(byteArrayOf(0x41, 0, 0x42)))
        assertFalse(FormatosDeTexto.pareceTexto(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        // Latin-1 suelto no es UTF-8: no se da por texto sin extensión (podría ser cualquier cosa).
        assertFalse(FormatosDeTexto.pareceTexto(byteArrayOf(0x61, 0xE9.toByte(), 0x62)))
    }

    @Test
    fun `se lee como el PC, con su marca y en Latin-1 si no es UTF-8`() {
        assertEquals("# hola\n", FormatosDeTexto.leer("# hola\n".toByteArray()))
        assertEquals("ñu", FormatosDeTexto.leer(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ñu".toByteArray()))
        assertEquals("añ", FormatosDeTexto.leer(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "añ".toByteArray(Charsets.UTF_16LE)))
        assertEquals("añ", FormatosDeTexto.leer(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "añ".toByteArray(Charsets.UTF_16BE)))
        // Un .txt del Bloc de notas de antes (Windows-1252): las tildes se conservan.
        assertEquals("canción", FormatosDeTexto.leer("canción".toByteArray(Charsets.ISO_8859_1)))
        // Los saltos de línea, tal cual.
        assertEquals("a\r\n\r\nb", FormatosDeTexto.leer("a\r\n\r\nb".toByteArray()))
    }

    @Test
    fun `un adjunto de texto del chat se anota en la nota de su mensaje`() {
        val m = Mensaje(id = "abc", cuando = 0, clase = Clase.ARCHIVO, ruta = "/x/abc.md", nombre = "a.md")
        assertEquals("md-abc", AbrirMensaje.idDeLaNota(m))
    }

    @Test
    fun `los nombres de texto conservan su extension al guardarse`() {
        for (e in FormatosDeTexto.TODAS) assertTrue(e, tieneExtensionConocida("archivo.$e"))
        assertEquals("notas.markdown", nombreConExtension("notas.markdown", "txt"))
    }
}
