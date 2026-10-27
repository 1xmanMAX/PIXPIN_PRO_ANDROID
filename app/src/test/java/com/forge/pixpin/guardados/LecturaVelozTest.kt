package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.LecturaVeloz.AlAbrir
import com.forge.pixpin.guardados.LecturaVeloz.AlLlegar
import com.forge.pixpin.guardados.LecturaVeloz.Mapa
import com.forge.pixpin.guardados.LecturaVeloz.Posicion
import com.forge.pixpin.guardados.LecturaVeloz.Ultimo
import com.forge.pixpin.motor.Lectura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

/**
 * **Lectura Veloz** (27-oct-2026): el enlace que la abre, el paso del verde de PixPin a su punto y
 * vuelta, lo que se acepta de lo que manda y quién gana. Sin Android. Ver [LecturaVeloz].
 */
class LecturaVelozTest {

    private fun parametros(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { kv ->
            val (k, v) = kv.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    private fun casi(esperado: Float, real: Float, margen: Float = 1e-4f) = assertEquals(esperado, real, margen)

    // ---- El enlace ----

    @Test
    fun `el enlace lleva el documento, el punto y la hora, bien codificados`() {
        val url = LecturaVeloz.enlace(
            "K7Q2ABCDEF", "Capítulo 3 & más.pdf", "content://com.forge.pixpin.fileprovider/guardados/a b.pdf",
            LecturaVeloz.TIPO_PDF, Posicion(2, 0.5f, 0.25f, 1_700_000_000_000L), 8
        )
        assertTrue(url, url.startsWith("lecturaveloz://pixpin/abrir?doc=K7Q2ABCDEF&"))
        // Sin espacios ni «+» sueltos: el `URLSearchParams` de ella los leería mal.
        assertFalse(url.contains(' '))
        assertFalse(url.contains('+'))
        assertTrue(url.contains("nombre=Cap%C3%ADtulo%203%20%26%20m%C3%A1s.pdf"))
        assertTrue(url.endsWith("&tipo=pdf&pagina=2&frac=0.5&fraccion=0.25&paginas=8&t=1700000000000"))
        val p = parametros(url)
        assertEquals("content://com.forge.pixpin.fileprovider/guardados/a b.pdf", p["uri"])
        assertEquals("Capítulo 3 & más.pdf", p["nombre"])
        assertEquals(setOf("doc", "nombre", "uri", "tipo", "pagina", "frac", "fraccion", "paginas", "t"), p.keys)
    }

    @Test
    fun `el enlace encaja lo que se sale`() {
        val p = parametros(LecturaVeloz.enlace("ABC", "x.txt", "content://a/x.txt", "otro", Posicion(-3, 1.7f, Float.NaN, -5L), -2))
        assertEquals("texto", p["tipo"])
        assertEquals("0", p["pagina"])
        assertEquals("1", p["frac"])
        assertEquals("0", p["fraccion"])
        assertEquals("0", p["paginas"])
        assertEquals("0", p["t"])
    }

    @Test
    fun `el nombre lleva la extension del archivo de verdad`() {
        assertEquals("Capítulo 3.docx", LecturaVeloz.nombreConExtension("Capítulo 3", "/d/files/guardados/abc.docx"))
        assertEquals("libro.EPUB", LecturaVeloz.nombreConExtension("libro.EPUB", "/d/a.epub"))
        assertEquals("z.pdf", LecturaVeloz.nombreConExtension("", "/x/y/z.pdf"))
        assertEquals("informe.pdf.docx", LecturaVeloz.nombreConExtension("informe.pdf", "/x/a.docx"))
        assertEquals("a b.md", LecturaVeloz.nombreConExtension("a/b", "/x/n.md"))
        val largo = LecturaVeloz.nombreConExtension("x".repeat(300), "/x/a.pdf")
        assertTrue(largo.length <= 120)
        assertTrue(largo.endsWith(".pdf"))
    }

    @Test
    fun `que se puede leer alli y de que tipo`() {
        assertTrue(LecturaVeloz.admite("x.pdf"))
        assertTrue(LecturaVeloz.admite("Sin nombre", "/a/b.epub"))
        assertFalse(LecturaVeloz.admite("foto.jpg", "/a/foto.jpg"))
        assertFalse(LecturaVeloz.admite("plano.dwg"))
        assertEquals("pdf", LecturaVeloz.tipoDe("x.PDF"))
        assertEquals("texto", LecturaVeloz.tipoDe("x.docx"))
        assertEquals("pdf", LecturaVeloz.tipoDe("Sin nombre", "/a/b.pdf"))
    }

    // ---- Del verde de PixPin al punto de Lectura Veloz, y vuelta ----

    private val texto = Mapa(listOf(10, 0, 30, 60), listOf(0f, 0.08f, 0.1f, 0.4f), emptyList())
    private val pdf = Mapa(listOf(20, 20, 10, 50), listOf(0f, 0.2f, 0.4f, 0.5f), listOf(1, 1, 2, 2))

    @Test
    fun `con el mapa el verde pasa a la fraccion en palabras`() {
        casi(0.1f, LecturaVeloz.vozALv(2 to 0.1f, texto, false, 0).fraccion)
        casi(0.4f, LecturaVeloz.vozALv(3 to 0.4f, texto, false, 0).fraccion)
        // Un párrafo que ya no existe se queda en el último.
        casi(0.4f, LecturaVeloz.vozALv(99 to 0.9f, texto, false, 0).fraccion)
    }

    @Test
    fun `con el mapa el punto de alla cae en su parrafo y en la fraccion de ese parrafo`() {
        assertEquals(3 to 0.4f, LecturaVeloz.lvAVoz(Posicion(fraccion = 0.5f), texto, false))
        assertEquals(2 to 0.1f, LecturaVeloz.lvAVoz(Posicion(fraccion = 0.35f), texto, false))
        assertEquals(0 to 0f, LecturaVeloz.lvAVoz(Posicion(fraccion = 0f), texto, false))
        // Ida y vuelta: el mismo párrafo.
        val ida = LecturaVeloz.vozALv(2 to 0.1f, texto, false, 0)
        assertEquals(2 to 0.1f, LecturaVeloz.lvAVoz(ida, texto, false))
    }

    @Test
    fun `sin mapa se pasa por la fraccion y el parrafo lo busca quien lo lea`() {
        casi(0.3f, LecturaVeloz.vozALv(7 to 0.3f, null, false, 0).fraccion)
        assertEquals(0 to 0.62f, LecturaVeloz.lvAVoz(Posicion(fraccion = 0.62f), null, false))
        // Y el visor (como el PC) elige el párrafo que empieza más cerca de esa fracción.
        assertEquals(3, Lectura.parrafoDeLaMarca(listOf(0f, 0.1f, 0.4f, 0.6f), 0 to 0.62f))
    }

    @Test
    fun `en un PDF con hojas el verde dice la hoja y por donde va en ella`() {
        val a = LecturaVeloz.vozALv(1 to 0.2f, pdf, true, 2)
        assertEquals(0, a.pagina); casi(0.5f, a.frac); casi(0.2f, a.fraccion)
        val b = LecturaVeloz.vozALv(3 to 0.5f, pdf, true, 2)
        assertEquals(1, b.pagina); casi(10f / 60f, b.frac); casi(0.5f, b.fraccion)
        assertEquals(3 to 0.5f, LecturaVeloz.lvAVoz(Posicion(1, 0.5f, 0.9f), pdf, true))
        assertEquals(1 to 0.2f, LecturaVeloz.lvAVoz(Posicion(0, 0.6f, 0.1f), pdf, true))
    }

    @Test
    fun `en un PDF sin mapa la hoja sale de la fraccion`() {
        val p = LecturaVeloz.vozALv(0 to 0.55f, null, true, 10)
        assertEquals(5, p.pagina); casi(0.5f, p.frac); casi(0.55f, p.fraccion)
        val fin = LecturaVeloz.vozALv(0 to 1f, null, true, 10)
        assertEquals(9, fin.pagina)
    }

    @Test
    fun `el verde del lector de PDF va y vuelve por hoja y altura`() {
        val p = LecturaVeloz.marcaALv(2.5, 4)
        assertEquals(2, p.pagina); casi(0.5f, p.frac); casi(0.625f, p.fraccion)
        assertEquals(2.5, LecturaVeloz.lvAMarca(Posicion(2, 0.5f, 0.625f), 4), 1e-6)
        // Una hoja que no hay se queda en la última; sin hoja, por la fracción.
        assertEquals(3.3, LecturaVeloz.lvAMarca(Posicion(9, 0.3f), 4), 1e-6)
        assertEquals(2.0, LecturaVeloz.lvAMarca(Posicion(0, 0f, 0.5f), 4), 1e-6)
        assertEquals(1.999, LecturaVeloz.lvAMarca(Posicion(1, 1f), 4), 1e-6)
    }

    @Test
    fun `el parrafo de la marca es el suyo si casa y si no el mas cercano`() {
        val f = listOf(0f, 0.1f, 0.4f, 0.7f)
        assertEquals(2, Lectura.parrafoDeLaMarca(f, 2 to 0.4f))
        assertEquals(2, Lectura.parrafoDeLaMarca(f, 0 to 0.41f))
        assertEquals(0, Lectura.parrafoDeLaMarca(f, 0 to 0.02f))
        assertEquals(3, Lectura.parrafoDeLaMarca(f, 3 to 0.9f))
        assertEquals(1, Lectura.parrafoDeLaMarca(f, 9 to 0.2f))
        assertNull(Lectura.parrafoDeLaMarca(emptyList(), 0 to 0.5f))
    }

    @Test
    fun `el mapa se guarda y se lee, y sale de un texto suelto`() {
        assertEquals(pdf, Mapa.deTexto(pdf.aTexto()))
        assertNull(Mapa.deTexto("hola"))
        assertNull(Mapa.deTexto(""))
        val m = Mapa.deTextoPlano("Uno dos.\n\nTres cuatro cinco.\n\n\nSeis")!!
        assertEquals(listOf(2, 3, 1), m.palabras)
        casi(0f, m.fracciones[0])
        assertTrue(m.fracciones[1] > 0f && m.fracciones[2] > m.fracciones[1])
        val d = Mapa.deParrafos(listOf("a b", "c"), listOf(0f, 0.5f), listOf(1, 2))!!
        assertEquals(listOf(2, 1), d.palabras)
    }

    // ---- Lo que llega ----

    @Test
    fun `lo que llega sin codigo valido o sin hora se tira`() {
        for (doc in listOf(null, "", "   ", "../x", "a/b", "uno dos", "x".repeat(81))) {
            assertNull(doc, LecturaVeloz.progresoDe(doc, "pdf", 0, 0f, 0f, 1000L, 0, 0, 5000L))
        }
        assertNull(LecturaVeloz.progresoDe("ABC", "pdf", 0, 0f, 0f, 0L, 0, 0, 5000L))
        assertNull(LecturaVeloz.progresoDe("ABC", "pdf", 0, 0f, 0f, -9L, 0, 0, 5000L))
    }

    @Test
    fun `lo que llega se encaja`() {
        val p = LecturaVeloz.progresoDe(" ABC_1-x ", "PDF", -4, 3f, Float.NaN, 9_000_000_000_000L, -1, -7, 1_000_000L)!!
        assertEquals("ABC_1-x", p.doc)
        assertEquals("texto", p.tipo)
        assertEquals(0, p.pagina)
        casi(1f, p.frac)
        casi(0f, p.fraccion)
        assertEquals(1_000_000L, p.t)
        assertEquals(0, p.palabra)
        assertEquals(0, p.total)
        assertEquals("pdf", LecturaVeloz.progresoDe("ABC", "pdf", 1, 0f, 0f, 10L, 0, 0, 20L)!!.tipo)
    }

    @Test
    fun `gana la posicion mas reciente`() {
        val ultimo = Ultimo(100L, 0, 0f, 0.2f)
        fun p(t: Long) = LecturaVeloz.Progreso("ABC", "texto", 0, 0f, 0.5f, t, 0, 0)
        assertEquals(AlLlegar.IGNORAR, LecturaVeloz.alLlegar(p(100L), ultimo, 0L))
        assertEquals(AlLlegar.IGNORAR, LecturaVeloz.alLlegar(p(90L), ultimo, 0L))
        assertEquals(AlLlegar.SOLO_LA_CHAPA, LecturaVeloz.alLlegar(p(200L), ultimo, 300L))
        assertEquals(AlLlegar.SOLO_LA_CHAPA, LecturaVeloz.alLlegar(p(300L), ultimo, 300L))
        assertEquals(AlLlegar.ESCRIBIR, LecturaVeloz.alLlegar(p(400L), ultimo, 300L))
        assertEquals(AlLlegar.ESCRIBIR, LecturaVeloz.alLlegar(p(1L), null, 0L))
    }

    @Test
    fun `la hora del verde es la de Lectura Veloz solo si sigue siendo el suyo`() {
        val suyo = Ultimo(500L, 0, 0f, 0.4f, escrito = true, voz = "3:0.4", marca = 2.5)
        assertEquals(0L, LecturaVeloz.horaDeLaVoz(null, 900L, suyo))
        assertEquals(0L, LecturaVeloz.horaDeLaVoz("  ", 900L, suyo))
        assertEquals(500L, LecturaVeloz.horaDeLaVoz("3:0.4\n", 900L, suyo))
        assertEquals(900L, LecturaVeloz.horaDeLaVoz("4:0.5", 900L, suyo))
        assertEquals(900L, LecturaVeloz.horaDeLaVoz("3:0.4", 900L, suyo.copy(escrito = false)))
        assertEquals(500L, LecturaVeloz.horaDeLaMarca(2.5, 1_700_000_000_000L, suyo))
        assertEquals(1_700_000_000_000L, LecturaVeloz.horaDeLaMarca(1.0, 1_700_000_000_000L, suyo))
        assertEquals(0L, LecturaVeloz.horaDeLaMarca(1.0, 3L, null))
        assertEquals(0L, LecturaVeloz.horaDeLaMarca(null, 1_700_000_000_000L, suyo))
    }

    @Test
    fun `al abrir va el marcador mas reciente`() {
        val suyo = Ultimo(500L, 0, 0f, 0.73f, escrito = true, voz = "0:0.73")
        // El verde es el que dejó ella: su punto exacto y su hora, sin ida y vuelta.
        val a = LecturaVeloz.posicionParaAbrir(0 to 0.73f, 500L, null, 0L, suyo, texto, false, 0)
        assertEquals(Posicion(0, 0f, 0.73f, 500L), a)
        // La voz de PixPin lo movió después: el de la voz, con su hora.
        val b = LecturaVeloz.posicionParaAbrir(2 to 0.1f, 900L, null, 0L, suyo, texto, false, 0)
        casi(0.1f, b.fraccion); assertEquals(900L, b.t)
        // Sin nada: desde el principio y con hora 0, para que ella siga donde iba.
        assertEquals(Posicion(), LecturaVeloz.posicionParaAbrir(null, 0L, null, 0L, null, null, false, 0))
        // Un PDF: el 🟢 de las hojas, si es más nuevo que el de leerlo como texto.
        val c = LecturaVeloz.posicionParaAbrir(1 to 0.2f, 700L, 2.5, 800L, null, pdf, true, 4)
        assertEquals(2, c.pagina); casi(0.5f, c.frac); assertEquals(800L, c.t)
        val d = LecturaVeloz.posicionParaAbrir(1 to 0.2f, 900L, 2.5, 800L, null, pdf, true, 4)
        assertEquals(0, d.pagina); casi(0.5f, d.frac); assertEquals(900L, d.t)
    }

    @Test
    fun `lo ultimo de Lectura Veloz se guarda y se lee`() {
        val u = Ultimo(1_700_000_000_123L, 3, 0.25f, 0.5f, escrito = true, voz = "12:0.5", marca = 3.25)
        assertEquals(u, Ultimo.deTexto(u.aTexto()))
        val sin = Ultimo(5L, 0, 0f, 1f)
        assertEquals(sin, Ultimo.deTexto(sin.aTexto()))
        assertNull(Ultimo.deTexto("basura"))
        assertNull(Ultimo.deTexto(null))
    }

    @Test
    fun `el porcentaje de la chapa`() {
        assertEquals(47, LecturaVeloz.porcentaje(0.474f))
        assertEquals(100, LecturaVeloz.porcentaje(1.2f))
        assertEquals(0, LecturaVeloz.porcentaje(Float.NaN))
    }

    @Test
    fun `que pasa al pulsar Lectura veloz`() {
        assertEquals(AlAbrir.FALTA_LA_APP, LecturaVeloz.queHacerAlAbrir(false, false, "ABC", "x.pdf", null))
        assertEquals(AlAbrir.NO_SE_PUEDE, LecturaVeloz.queHacerAlAbrir(true, true, null, "x.pdf", null))
        assertEquals(AlAbrir.NO_SE_PUEDE, LecturaVeloz.queHacerAlAbrir(true, true, "ABC", "foto.png", "/a/foto.png"))
        assertEquals(AlAbrir.APP_ANTIGUA, LecturaVeloz.queHacerAlAbrir(true, false, "ABC", "x.pdf", null))
        assertEquals(AlAbrir.ABRIR, LecturaVeloz.queHacerAlAbrir(true, true, "ABC", "x.pdf", null))
        assertNotNull(LecturaVeloz.PAQUETE)
    }
}
