package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un color por tipo de archivo ([ColorDeExtension]), como pidió el usuario el 30-sep-2026. */
class ColorDeExtensionTest {

    @Test
    fun `cada familia con su color`() {
        assertEquals(ColorDeExtension.PDF, ColorDeExtension.de("Tesis final.PDF"))
        assertEquals(ColorDeExtension.WORD, ColorDeExtension.de("informe.docx"))
        assertEquals(ColorDeExtension.WORD, ColorDeExtension.de("viejo.doc"))
        assertEquals(ColorDeExtension.PLANO, ColorDeExtension.de("Planta baja.dwg"))
        assertEquals(ColorDeExtension.PLANO, ColorDeExtension.de("corte.dxf"))
        assertEquals(ColorDeExtension.HOJA, ColorDeExtension.de("cuentas.xlsx"))
        assertEquals(ColorDeExtension.APLICACION, ColorDeExtension.de("/storage/Download/PixPin-0.98.4.apk"))
        assertEquals(0xE5484D, ColorDeExtension.PDF.color)
        assertTrue("sobre el amarillo el texto va oscuro", ColorDeExtension.PLANO.textoOscuro)
    }

    @Test
    fun `lo que no esta en la tabla sale siempre igual`() {
        val una = ColorDeExtension.de("a.xyz").color
        assertEquals(una, ColorDeExtension.de("otro nombre.XYZ").color)
        assertEquals("Archivo", ColorDeExtension.de("sin extension").nombre)
    }

    @Test
    fun `el rotulo es la extension corta`() {
        assertEquals("pdf", ColorDeExtension.rotulo("a.PDF"))
        assertEquals("xlsx", ColorDeExtension.rotulo("a.xlsx"))
        assertEquals("pixp", ColorDeExtension.rotulo("proyecto.pixpin"))
        assertEquals("", ColorDeExtension.rotulo("LEEME"))
    }
}
