package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import com.forge.pixpin.guardados.LecturaVeloz.AlLlegar
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.Marcas
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * **El punto que devuelve Lectura Veloz** (27-oct-2026): qué se acepta, dónde se escribe (el `.voz`
 * de siempre y el 🟢 de un PDF) y que gane siempre lo más reciente. Ver [LecturaVelozEnElTelefono].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LecturaVelozEnElTelefonoTest {

    private lateinit var c: Context
    private val doc = "ABCDEFGHJK"
    private val base = "anot-$doc"

    @Before
    fun preparar() {
        c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("lectura_veloz", Context.MODE_PRIVATE).edit().clear().commit()
        File(c.filesDir, AnotacionesDelAdjunto.CARPETA).deleteRecursively()
        File(c.filesDir, "lectura-veloz").deleteRecursively()
    }

    private fun voz(b: String = base) = AnotacionesDelAdjunto.voz(c.filesDir, b)

    private fun apuntarTexto() = LecturaVelozEnElTelefono.apuntar(
        c, LecturaVelozEnElTelefono.Documento(doc, File(c.filesDir, "guardados/x.docx"), "x.docx", base, LecturaVeloz.TIPO_TEXTO)
    )

    private fun aviso(
        d: String? = doc, t: Long, fraccion: Float = 0.5f, pagina: Int = 0, frac: Float = 0f, tipo: String = "texto",
        accion: String = LecturaVeloz.ACCION_DE_VUELTA
    ) = Intent(accion).setPackage("com.forge.pixpin")
        .putExtra("doc", d).putExtra("tipo", tipo).putExtra("pagina", pagina).putExtra("frac", frac)
        .putExtra("fraccion", fraccion).putExtra("t", t).putExtra("palabra", 10).putExtra("total", 100)

    @Test
    fun `un documento que no se le dio se ignora`() {
        assertNull(LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L), ahora = 5000L))
        assertFalse(voz().exists())
        assertNull(LecturaVelozEnElTelefono.porcentaje(c, doc))
    }

    @Test
    fun `otra accion o un codigo raro se ignoran`() {
        apuntarTexto()
        assertNull(LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L, accion = "otra.cosa"), ahora = 5000L))
        assertNull(LecturaVelozEnElTelefono.recibir(c, aviso(d = "../../x", t = 1000L), ahora = 5000L))
        assertNull(LecturaVelozEnElTelefono.recibir(c, aviso(t = 0L), ahora = 5000L))
        assertFalse(voz().exists())
    }

    @Test
    fun `el punto mueve el verde de la voz y la chapa`() {
        apuntarTexto()
        assertEquals(AlLlegar.ESCRIBIR, LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L, fraccion = 0.47f), ahora = 5000L))
        // Sin mapa de párrafos: `0:<fracción>`, y el visor busca el párrafo por la fracción.
        assertEquals(Lectura.vozATexto(0, 0.47f), voz().readText())
        assertEquals(47, LecturaVelozEnElTelefono.porcentaje(c, doc))
        // El mismo aviso otra vez, o uno más viejo: nada.
        assertEquals(AlLlegar.IGNORAR, LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L, fraccion = 0.9f), ahora = 5000L))
        assertEquals(AlLlegar.IGNORAR, LecturaVelozEnElTelefono.recibir(c, aviso(t = 900L, fraccion = 0.9f), ahora = 5000L))
        assertEquals(47, LecturaVelozEnElTelefono.porcentaje(c, doc))
    }

    @Test
    fun `si la voz de PixPin lo movio despues gana la voz`() {
        apuntarTexto()
        LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L, fraccion = 0.2f), ahora = 5000L)
        // La voz de PixPin lee más tarde y apunta su verde.
        AnotacionesDelAdjunto.escribir(voz(), "4:0.8")
        voz().setLastModified(3000L)
        // Un aviso de Lectura Veloz de antes de eso (la app seguía abierta): solo la chapa.
        assertEquals(AlLlegar.SOLO_LA_CHAPA, LecturaVelozEnElTelefono.recibir(c, aviso(t = 2000L, fraccion = 0.3f), ahora = 5000L))
        assertEquals("4:0.8", voz().readText())
        assertEquals(30, LecturaVelozEnElTelefono.porcentaje(c, doc))
        // Y uno de después: vuelve a mandar ella.
        assertEquals(AlLlegar.ESCRIBIR, LecturaVelozEnElTelefono.recibir(c, aviso(t = 4000L, fraccion = 0.6f), ahora = 5000L))
        assertEquals(Lectura.vozATexto(0, 0.6f), voz().readText())
    }

    @Test
    fun `lo que se sale se encaja`() {
        apuntarTexto()
        LecturaVelozEnElTelefono.recibir(c, aviso(t = 99_000L, fraccion = 7f), ahora = 5000L)
        assertEquals(100, LecturaVelozEnElTelefono.porcentaje(c, doc))
        assertEquals(Lectura.vozATexto(0, 1f), voz().readText())
        // Una hora del futuro se queda en «ahora»: lo siguiente de verdad no se pierde.
        assertEquals(AlLlegar.ESCRIBIR, LecturaVelozEnElTelefono.recibir(c, aviso(t = 6000L, fraccion = 0.1f), ahora = 6000L))
    }

    @Test
    fun `con el mapa del visor el verde cae en su parrafo`() {
        apuntarTexto()
        LecturaVelozEnElTelefono.guardarMapa(c, base, LecturaVeloz.Mapa(listOf(10, 30, 60), listOf(0f, 0.12f, 0.45f), emptyList()))
        LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L, fraccion = 0.5f), ahora = 5000L)
        assertEquals(Lectura.vozATexto(2, 0.45f), voz().readText())
    }

    @Test
    fun `en un PDF tambien se mueve el verde de sus hojas`() {
        val pdfBase = "anot-$doc-texto"
        LecturaVelozEnElTelefono.apuntar(
            c, LecturaVelozEnElTelefono.Documento(doc, File(c.filesDir, "guardados/x.pdf"), "x.pdf", pdfBase, LecturaVeloz.TIPO_PDF, paginas = 4)
        )
        val marcas = AnotacionesDelAdjunto.marcas(c.filesDir, AnotacionesDelAdjunto.delPdf(doc))
        // Un marcador que ya había y un verde viejo.
        AnotacionesDelAdjunto.escribir(marcas, Marcas.aTexto(listOf(
            com.forge.pixpin.motor.Marca(1L, "⭐", 0.5, 0.2), com.forge.pixpin.motor.Marca(2L, Lectura.EMOJI_DE_VOZ, 0.5, 0.1)
        )))
        assertEquals(AlLlegar.ESCRIBIR, LecturaVelozEnElTelefono.recibir(c, aviso(t = 1000L, pagina = 2, frac = 0.5f, fraccion = 0.6f, tipo = "pdf"), ahora = 5000L))
        val lista = Marcas.deTexto(marcas.readText())
        assertEquals(1, lista.count { it.emoji == Lectura.EMOJI_DE_VOZ })
        assertEquals(2.5, lista.first { it.emoji == Lectura.EMOJI_DE_VOZ }.y, 1e-6)
        assertTrue(lista.any { it.emoji == "⭐" })
        assertTrue(voz(pdfBase).isFile)
        // Al volver a abrir: el verde es el suyo, así que va su punto exacto con su hora.
        val u = LecturaVelozEnElTelefono.ultimo(c, doc)!!
        assertTrue(u.escrito)
        assertEquals(1000L, u.t)
        assertEquals(1000L, LecturaVeloz.horaDeLaMarca(2.5, 1000L, u))
    }
}
