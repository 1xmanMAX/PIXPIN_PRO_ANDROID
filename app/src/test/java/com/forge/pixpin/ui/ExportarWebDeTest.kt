package com.forge.pixpin.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El resumen que se dice al compartir una página web.
 *
 * El aviso del audio estaba aquí: contaba los `<audio>` del archivo y aun así añadía
 * «· audio: ligero» siempre —un proyecto sin un solo audio anunciaba que lo llevaba.
 * Ver [ExportarWebDe.resumenDe].
 */
class ExportarWebDeTest {

    private fun temporal(html: String): File =
        File.createTempFile("exportar-web", ".html").apply {
            writeText(html)
            deleteOnExit()
        }

    @Test
    fun `sin audios el resumen no anuncia el audio`() {
        val archivo = temporal("<html><body><div class=\"hoja\">dibujo</div></body></html>")
        val resumen = ExportarWebDe.resumenDe(archivo, "ligero")
        assertFalse("sin audios no puede anunciar el audio: «$resumen»", resumen.contains("audio"))
    }

    @Test
    fun `con audios el resumen sí anuncia cuantos y con qué calidad`() {
        val archivo = temporal(
            "<html><body><div class=\"hoja\"><audio controls src=\"x\"></audio></div></body></html>"
        )
        val resumen = ExportarWebDe.resumenDe(archivo, "ligero")
        assertTrue("no cuenta el audio que sí viaja: «$resumen»", resumen.contains("1 audio"))
        assertTrue("no dice la calidad: «$resumen»", resumen.contains("audio: ligero"))
    }
}
