package com.forge.pixpin.motor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Una hoja de notas con su audio adjunto sale a la web con el audio dentro. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotaConAudioWebTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `una nota larga, partida en paginas, lleva el audio en la primera`() {
        val audio = File(context.cacheDir, "voz-2.m4a").also { it.writeBytes(ByteArray(64) { 7 }) }
        val parrafos = (0 until 120).joinToString("\n\n") { "[${it / 3}:${"%02d".format((it * 20) % 60)}] Párrafo número $it de una transcripción muy larga que sigue y sigue." }
        val texto = "# Transcripción\n\n![audio](${audio.absolutePath})\n\n$parrafos"
        val proyecto = Proyecto(id = "p2", nombre = "Obra", hojas = listOf(Hoja(id = "n2", nombre = "Transcripción", nota = texto)))
        val hojas = ExportarProyectoWeb.paginas(context, proyecto, emptySet(), escenaDe = { null }, calidadDeAudio = AudioLigero.ORIGINAL)
        val notas = hojas.filterIsInstance<ExportarHtml.HojaWeb.Nota>()
        assertTrue("tenía que partirse en varias: " + notas.size, notas.size > 1)
        assertTrue(notas[0].html.take(600), notas[0].html.contains("<audio controls"))
        assertTrue("los saltos van en todas las páginas", notas.all { it.html.contains("class=\"salto\"") })
    }

    @Test
    fun `la nota transcrita lleva su audio y sus saltos`() {
        val audio = File(context.cacheDir, "voz-1.m4a").also { it.writeBytes(ByteArray(64) { 7 }) }
        val texto = "# Transcripción\n\n![audio](${audio.absolutePath})\n\n[0:00] Hola a todos.\n\n[0:21] Segundo punto."
        val proyecto = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "n", nombre = "Transcripción", nota = texto)))
        val hojas = ExportarProyectoWeb.paginas(context, proyecto, emptySet(), escenaDe = { null }, calidadDeAudio = AudioLigero.ORIGINAL)
        val nota = hojas.filterIsInstance<ExportarHtml.HojaWeb.Nota>().single()
        assertTrue(nota.html, nota.html.contains("<audio controls"))
        assertTrue(nota.html, nota.html.contains("data:audio/mp4;base64,"))
        assertTrue(nota.html, nota.html.contains("class=\"salto\" data-ms=\"21000\""))
        // Y la página entera lleva el visor de los saltos.
        val pagina = ExportarHtml.paginas(hojas, "Obra", "obra", ExportarHtml.Opciones())
        assertTrue(pagina.contains("closest('.salto')"))
    }
}
