package com.forge.pixpin.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un DWG se anuncia como plano, y todo nombre con que se busca está declarado en `<queries>`. */
class TiposDeArchivoTest {

    @Test
    fun `un dwg se anuncia como plano y con todos sus alias`() {
        assertEquals("image/vnd.dwg", TiposDeArchivo.principal("Planta baja.DWG"))
        assertTrue("application/acad" in TiposDeArchivo.candidatos("a.dwg"))
        assertEquals("image/vnd.dxf", TiposDeArchivo.principal("corte.dxf"))
    }

    @Test
    fun `lo que el sistema ya conoce se le deja al sistema`() {
        assertNull(TiposDeArchivo.principal("tesis.pdf"))
        assertNull(TiposDeArchivo.principal("sin-extension"))
    }

    @Test
    fun `cada tipo de la tabla se busca en el manifiesto`() {
        val manifiesto = File("src/main/AndroidManifest.xml").readText()
        val consultas = manifiesto.substringAfter("<queries>").substringBefore("</queries>")
        val faltan = TiposDeArchivo.TODOS.filter { "android:mimeType=\"$it\"" !in consultas }
        assertTrue("faltan en <queries>: $faltan", faltan.isEmpty())
    }
}
