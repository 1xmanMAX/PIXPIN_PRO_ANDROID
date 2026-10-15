package com.forge.pixpin.motor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los símbolos del PDF de una nota salen con su letra y no como «?»** (15-oct-2026, como el PC el
 * 14-oct). Antes todo lo que no era Latin-1 —∑, π, ✓, las flechas, las griegas, un emoji, y hasta
 * la viñeta «•» de las listas— se escribía como «?». Se mira el PDF que sale, sin rasterizar: qué
 * letras lleva incrustadas, con qué glifos y qué dice su `ToUnicode`; y, si hay Ghostscript, el
 * texto que saca un lector de verdad.
 */
class PdfDeNotaSimbolosTest {

    private val liberation = File("src/main/assets/fuentes-ttf/LiberationSans-Regular.ttf")
    /** La de la máquina, si la hay: tiene ✓ y ⇒, que Liberation no. */
    private val dejaVu = File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")

    private val texto = """
        # Cálculo Σ

        - Suma: ∑ x² ± 3 ° y π ≈ 3,14
        - Flechas → ← ⇒ y griegas α β γ Δ Ω λ μ
        - Hecho ✓ y un emoji 😀 al final; € y “comillas” y 漢

        | Símbolo | Valor |
        |:---|---:|
        | π | 3,1416 |
        | √2 | 1,41 |
    """.trimIndent()

    private fun pdfEnBlanco(): ByteArray = PdfUnion.enBlanco()

    /** El PDF entero con los flujos descomprimidos, para buscar en él. */
    private fun dentroDe(pdf: ByteArray): String {
        val plano = String(pdf, Charsets.ISO_8859_1)
        val salida = StringBuilder(plano)
        var desde = 0
        while (true) {
            val abre = plano.indexOf("stream", desde)
            if (abre < 0) break
            var inicio = abre + 6
            if (inicio < plano.length && plano[inicio] == '\r') inicio++
            if (inicio < plano.length && plano[inicio] == '\n') inicio++
            val cierra = plano.indexOf("endstream", inicio)
            if (cierra < 0) break
            runCatching {
                val inflador = java.util.zip.Inflater()
                inflador.setInput(pdf.copyOfRange(inicio, cierra))
                val buffer = ByteArray(1 shl 16)
                val out = java.io.ByteArrayOutputStream()
                while (!inflador.finished()) { val n = inflador.inflate(buffer); if (n == 0) break; out.write(buffer, 0, n) }
                inflador.end()
                salida.append('\n').append(String(out.toByteArray(), Charsets.ISO_8859_1))
            }
            desde = cierra + 9
        }
        return salida.toString()
    }

    /** Solo las cadenas que se escriben (`(...) Tj`): ahí no puede haber un «?» que el texto no traía. */
    private fun cadenasDeTexto(contenido: String): List<String> =
        Regex("""\(((?:\\.|[^\\()\n])*)\) Tj""").findAll(contenido).map { it.groupValues[1] }.toList()

    /** Lo que mapea el `ToUnicode`: `<glifo> <unicode>`. */
    private fun unicodes(contenido: String): Set<String> =
        Regex("""<([0-9A-F]{4})> <([0-9A-F]+)>""").findAll(contenido).map { it.groupValues[2] }.toSet()

    @Test
    fun `sin letras de reserva nada sale como interrogante`() {
        val pdf = PdfDeNota.aniadir(pdfEnBlanco(), texto)!!
        val dentro = dentroDe(pdf)
        val cadenas = cadenasDeTexto(dentro)
        assertTrue(cadenas.isNotEmpty())
        assertTrue("salió un «?»: $cadenas", cadenas.none { '?' in it })
        // La viñeta, el euro y las comillas son de WinAnsi: van con su byte (octal 225, 200, 223).
        assertTrue("la viñeta no es WinAnsi", dentro.contains("(\u0095  ) Tj"))
        assertTrue(cadenas.any { '\u0080' in it } && cadenas.any { '\u0093' in it })
        // Lo que no tiene ninguna letra, un cuadrito dibujado.
        assertTrue(dentro.contains(" re S"))
        // ° ± ² siguen en la Helvetica.
        assertTrue(cadenas.any { it.contains("x² ± 3 °") })
    }

    @Test
    fun `con reservas cada simbolo va con su glifo y se copia como el`() {
        val letras = LetrasDelPdf(listOf(liberation, dejaVu))
        val pdf = PdfDeNota.aniadir(pdfEnBlanco(), texto, letras = letras)!!
        val dentro = dentroDe(pdf)
        assertTrue("no se incrustó ninguna letra", dentro.contains("/FontFile2"))
        assertTrue(dentro.contains("/CIDFontType2") && dentro.contains("/Identity-H") && dentro.contains("/ToUnicode"))
        val u = unicodes(dentro)
        for (c in listOf("2211", "03C0", "03A3", "2192", "2190", "03B1", "03B2", "03B3", "0394", "03A9", "03BB", "03BC", "2248", "221A")) {
            assertTrue("falta U+$c en el ToUnicode: $u", c in u)
        }
        if (dejaVu.isFile) {
            assertTrue("✓ con DejaVu", "2713" in u)
            assertTrue("⇒ con DejaVu", "21D2" in u)
        }
        // Lo que no tiene ninguna (漢) y sin quien lo pinte: el hueco de una reserva, que se copia como U+FFFD; nunca «?».
        assertTrue("FFFD" in u)
        assertTrue(cadenasDeTexto(dentro).none { '?' in it })
        // Lo de WinAnsi no gasta reserva: ° ± ² siguen en la Helvetica.
        assertFalse("00B0" in u || "00B1" in u || "00B2" in u)
        // Sin ninguna letra: el PDF se sigue leyendo.
        assertNotNull(leerPdf(pdf))
    }

    @Test
    fun `un emoji que se sabe pintar va como imagen en su sitio`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        var pedidos = 0
        val letras = LetrasDelPdf(listOf(liberation)) { t -> pedidos++; if (t.startsWith("😀")) PdfDeNota.MedioParaPdf(jpeg, 96, 112, null) else null }
        val pdf = PdfDeNota.aniadir(pdfEnBlanco(), "hola 😀 y 😀 adiós", letras = letras)!!
        val dentro = dentroDe(pdf)
        assertTrue(dentro.contains("/Subtype /Image") || dentro.contains("/Subtype/Image"))
        // Dos veces el mismo, una sola imagen.
        assertEquals(2, Regex("""/Im1 Do""").findAll(dentro).count())
        assertFalse(dentro.contains("/Im2"))
        assertTrue(cadenasDeTexto(dentro).any { it.startsWith("hola") })
        assertTrue(pedidos >= 1)
    }

    @Test
    fun `la letra incrustada lleva solo lo que se usa`() {
        val l = LetraTrueType.leer(liberation.readBytes(), "LiberationSans-Regular")!!
        assertTrue(l.embebible)
        val pi = l.glifo(0x3C0)
        val sigma = l.glifo(0x2211)
        assertTrue(pi > 0 && sigma > 0 && l.tieneContorno(pi))
        val sub = l.subconjunto(setOf(pi, sigma))
        assertTrue("pesa ${sub.size}", sub.size < liberation.length() / 4)
        val releida = LetraTrueType.leer(sub, "sub")!!
        assertEquals(l.glifos, releida.glifos)
        assertTrue(releida.tieneContorno(pi) && releida.tieneContorno(sigma))
        assertFalse("lo que no se usa, vacío", releida.tieneContorno(l.glifo('A'.code)))
        assertEquals(l.ancho(pi), releida.ancho(pi))
        // Lo que no es una TrueType no se abre.
        assertEquals(null, LetraTrueType.leer("no soy una letra".toByteArray(), "x"))
    }

    /** El juez de fuera: Ghostscript saca el texto del PDF por los `ToUnicode`. Sin él, se salta. */
    @Test
    fun `un lector de verdad lee los simbolos`() {
        val gs = listOf("/usr/bin/gs", "/usr/local/bin/gs", "/data/data/com.termux/files/usr/bin/gs").map(::File).firstOrNull { it.canExecute() } ?: return
        val letras = LetrasDelPdf(listOf(liberation, dejaVu))
        val pdf = PdfDeNota.aniadir(pdfEnBlanco(), texto, letras = letras)!!
        val f = File.createTempFile("simbolos", ".pdf").apply { writeBytes(pdf) }
        val proceso = ProcessBuilder(gs.path, "-q", "-dBATCH", "-dNOPAUSE", "-sDEVICE=txtwrite", "-sOutputFile=-", f.path).redirectErrorStream(true).start()
        val leido = proceso.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val codigo = proceso.waitFor()
        f.delete()
        assertEquals("ghostscript se quejó:\n$leido", 0, codigo)
        for (s in listOf("∑", "π", "→", "α", "Δ", "Ω", "√2", "•", "€", "x² ± 3 °")) assertTrue("no lee «$s»:\n$leido", leido.contains(s))
        if (dejaVu.isFile) assertTrue("no lee «✓»:\n$leido", leido.contains("✓"))
        assertFalse("hay un «?»:\n$leido", leido.contains("?"))
    }
}
