package com.forge.pixpin

import com.forge.pixpin.integraciones.ProtocoloLecturaVeloz
import com.forge.pixpin.integraciones.ProtocoloLecturaVeloz.Destino
import com.forge.pixpin.integraciones.ProtocoloLecturaVeloz.Llegada
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.Marcas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LecturaVelozTest {

    @Test
    fun `los parametros van todos y en orden, acotados`() {
        val p = ProtocoloLecturaVeloz.parametros(
            "K7Q2ABCDEF", "Mi libro & yo.pdf", "content://com.forge.pixpin.fileprovider/guardados/a.pdf", "pdf",
            pagina = 3, frac = 1.7f, fraccion = 0.25f, numPaginas = 12, t = 1_700_000_000_000L
        )
        assertEquals(listOf("v", "doc", "nombre", "uri", "tipo", "pagina", "frac", "fraccion", "paginas", "t"), p.map { it.first })
        val m = p.toMap()
        assertEquals("1", m["v"])
        assertEquals("Mi libro & yo.pdf", m["nombre"])
        assertEquals("1.0", m["frac"])
        assertEquals("0.25", m["fraccion"])
        assertEquals("12", m["paginas"])
        assertEquals("1700000000000", m["t"])
        // Nada negativo ni que no sea un número.
        val raro = ProtocoloLecturaVeloz.parametros("K7Q2ABCDEF", "x", "u", "texto", -4, Float.NaN, -1f, -2, -9L).toMap()
        assertEquals("0", raro["pagina"]); assertEquals("0.0", raro["frac"]); assertEquals("0.0", raro["fraccion"])
        assertEquals("0", raro["paginas"]); assertEquals("0", raro["t"])
    }

    @Test
    fun `la fraccion de un pdf es la hoja mas lo leido de ella entre todas`() {
        assertEquals(0.35f, ProtocoloLecturaVeloz.fraccionDelPdf(3, 0.5f, 10), 1e-6f)
        assertEquals(0f, ProtocoloLecturaVeloz.fraccionDelPdf(3, 0.5f, 0), 0f)
        assertEquals(1f, ProtocoloLecturaVeloz.fraccionDelPdf(30, 0.5f, 10), 0f)
    }

    @Test
    fun `solo vale un codigo de diez letras o cifras`() {
        assertTrue(ProtocoloLecturaVeloz.uidValido("K7Q2ABCDEF"))
        assertFalse(ProtocoloLecturaVeloz.uidValido(null))
        assertFalse(ProtocoloLecturaVeloz.uidValido(""))
        assertFalse(ProtocoloLecturaVeloz.uidValido("K7Q2ABCDE"))
        assertFalse(ProtocoloLecturaVeloz.uidValido("../ABCDEFG"))
        assertFalse(ProtocoloLecturaVeloz.uidValido("ABCD/EFGHI"))
        assertFalse(ProtocoloLecturaVeloz.uidValido("ABCD.EFGHI"))
    }

    @Test
    fun `lo que llega se valida y se acota`() {
        assertNull(Llegada.de(null, "pdf", 1, 0.5f, 0.1f, 0L, 0, 0))
        assertNull(Llegada.de("a/b", "pdf", 1, 0.5f, 0.1f, 0L, 0, 0))
        assertNull(Llegada.de("K7Q2ABCDEF", "epub", 1, 0.5f, 0.1f, 0L, 0, 0))
        val l = Llegada.de(" K7Q2ABCDEF ", "PDF", -3, 2f, Float.NaN, -5L, 50, 200)
        assertNotNull(l); l!!
        assertEquals("K7Q2ABCDEF", l.doc)
        assertEquals("pdf", l.tipo)
        assertEquals(0, l.pagina)
        assertEquals(1f, l.frac, 0f)
        // Sin fracción que valga, la de la palabra.
        assertEquals(0.25f, l.fraccion, 1e-6f)
        assertEquals(0L, l.t)
        assertEquals(ProtocoloLecturaVeloz.PAGINAS_MAX, Llegada.de("K7Q2ABCDEF", "pdf", Int.MAX_VALUE, null, null, null, null, null)!!.pagina)
    }

    @Test
    fun `el destino se guarda y se lee, y lo raro no vale`() {
        val d = Destino(Destino.HOJAS_ARCHIVO, "/data/x/files/pins/draw/anot-K7Q2ABCDEF.marcas")
        assertEquals(d, Destino.deTexto(d.aTexto()))
        assertEquals(Destino(Destino.VOZ, "/a/b c.docx"), Destino.deTexto("voz\t/a/b c.docx"))
        assertNull(Destino.deTexto(null))
        assertNull(Destino.deTexto("otro\t/a"))
        assertNull(Destino.deTexto("voz\t"))
        assertNull(Destino.deTexto("voz"))
    }

    @Test
    fun `el verde de las hojas se mueve y los demas se quedan`() {
        val antes = "1:0.5:0.2:⭐|2:0.5:4.5:🟢|3:0.5:7:🔖"
        val despues = Marcas.deTexto(ProtocoloLecturaVeloz.conVerdeEnHojas(antes, 3, 0.5f, 99L))
        assertEquals(3, despues.size)
        val verdes = despues.filter { it.emoji == Lectura.EMOJI_DE_VOZ }
        assertEquals(1, verdes.size)
        assertEquals(3.5, verdes[0].y, 1e-9)
        assertEquals(99L, verdes[0].id)
        assertTrue(despues.any { it.emoji == "⭐" && it.y == 0.2 } && despues.any { it.emoji == "🔖" && it.y == 7.0 })
        // Sin nada antes, sale el verde solo.
        val solo = Marcas.deTexto(ProtocoloLecturaVeloz.conVerdeEnHojas(null, 0, 0f, 5L))
        assertEquals(listOf(Lectura.EMOJI_DE_VOZ), solo.map { it.emoji })
    }

    @Test
    fun `el verde en fracciones se mueve y los demas se quedan`() {
        val antes = Lectura.aTexto(listOf(Lectura.Marcador(1, 0.1f, "⭐"), Lectura.Marcador(2, 0.6f, "🟢")))
        val despues = Lectura.deTexto(ProtocoloLecturaVeloz.conVerdeEnFracciones(antes, 0.3f, 77L))
        assertEquals(listOf("⭐", "🟢"), despues.map { it.emoji })
        assertEquals(0.3f, despues[1].fraccion, 1e-6f)
        assertEquals(77L, despues[1].id)
    }

    @Test
    fun `lo viejo no pisa lo nuevo y un cero es no saberlo`() {
        assertTrue(ProtocoloLecturaVeloz.esViejo(100L, 200L))
        assertFalse(ProtocoloLecturaVeloz.esViejo(300L, 200L))
        assertFalse(ProtocoloLecturaVeloz.esViejo(0L, 200L))
        assertFalse(ProtocoloLecturaVeloz.esViejo(100L, 0L))
    }

    @Test
    fun `el t es el de lectura veloz mientras nadie mas toque el archivo`() {
        // El archivo sigue como lo dejó el aviso: vale el t de Lectura Veloz.
        assertEquals(1000L, ProtocoloLecturaVeloz.cuandoSeMovio(fecha = 1005L, escrito = 1005L, tLecturaVeloz = 1000L))
        // Luego lo tocó PixPin: su fecha.
        assertEquals(2000L, ProtocoloLecturaVeloz.cuandoSeMovio(fecha = 2000L, escrito = 1005L, tLecturaVeloz = 1000L))
        // Nunca hubo aviso: la fecha del archivo; sin archivo (preferencias), el último t o 0.
        assertEquals(1500L, ProtocoloLecturaVeloz.cuandoSeMovio(1500L, 0L, 0L))
        assertEquals(1000L, ProtocoloLecturaVeloz.cuandoSeMovio(0L, 0L, 1000L))
        assertEquals(0L, ProtocoloLecturaVeloz.cuandoSeMovio(0L, 0L, 0L))
        // Dos avisos seguidos: el segundo (t = 1003) no es viejo frente al primero (t = 1000),
        // aunque el archivo se escribiera a las 1005.
        assertFalse(ProtocoloLecturaVeloz.esViejo(1003L, ProtocoloLecturaVeloz.cuandoSeMovio(1005L, 1005L, 1000L)))
    }

    @Test
    fun `el verde que solo trae la fraccion se entiende y cae en su parrafo`() {
        assertEquals(-1 to 0.3f, Lectura.vozDeTexto("-1:0.3"))
        assertEquals("-1:0.3", Lectura.vozDeFraccion(0.3f))
        assertEquals(Lectura.SIN_PARRAFO to 1f, Lectura.vozDeTexto(Lectura.vozDeFraccion(4f)))
        // Otros negativos siguen sin valer.
        assertNull(Lectura.vozDeTexto("-2:0.3"))
        assertNull(Lectura.vozDeTexto("-1:NaN"))
        val donde = listOf(0f, 0.2f, 0.5f, 0.9f)
        assertEquals(1, Lectura.parrafoDeFraccion(donde, 0.3f))
        assertEquals(2, Lectura.parrafoDeFraccion(donde, 0.5f))
        assertEquals(0, Lectura.parrafoDeFraccion(listOf(0.1f, 0.2f), 0f))
        assertEquals(3, Lectura.parrafoDeFraccion(donde, 1f))
        assertEquals(-1, Lectura.parrafoDeFraccion(emptyList(), 0.5f))
    }
}
