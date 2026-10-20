package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** PC `6cddabe`: el `.pixpin` de un archivo suelto lleva todo lo anotado con el código de su mensaje. */
class LoSueltoTest {
    @Test
    fun `lo anotado de un suelto va con el codigo del mensaje y lo de otros no`() {
        val files = Files.createTempDirectory("suelto").toFile()
        val pdf = File(files, "plano.pdf").apply { writeBytes(byteArrayOf(1)) }
        val h = pdf.absolutePath.hashCode().toUInt().toString(16)
        val draw = File(files, "pins/draw").apply { mkdirs() }
        File(draw, "pdf-$h-p0.excalidraw.gz").writeText("t0")
        File(draw, "pdf-$h-p0.hoja").writeText("0,0,1400,1980\nv1\n")
        File(draw, "pdf-${h}9-p0.excalidraw.gz").writeText("de otro")
        File(draw, "modelo-$h-mm1.excalidraw.gz").writeText("3d")
        File("${pdf.absolutePath}.pixpin-marcos").writeText("[]")
        File(files, "planos-marcadores").mkdirs()
        File(files, "planos-marcadores/$h.vistas.json").writeText("{\"version\":1}")
        val p = ChatQueViaja.conLoSuelto(ChatQueViaja.Paquete("", emptyMap()), "ABCDE23456", pdf.absolutePath, files)
        assertEquals(
            setOf(
                "anotado/anot-ABCDE23456-p0.excalidraw.gz", "anotado/anot-ABCDE23456-p0.hoja", "anotado/anot-ABCDE23456-mm1.excalidraw.gz",
                "anotado/anot-ABCDE23456.marcos.json", "anotado/anot-ABCDE23456.vistas.json"
            ),
            p.adjuntos.keys
        )
    }
}
