package com.forge.pixpin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Un archivo de texto se abre por su ruta** (27-oct-2026): el intento lleva la ruta y no el texto
 * (uno grande no cabía y Android no abría nada), y dice si es texto llano y si es solo para mirar.
 * Ver [MarkdownEditorActivity.abrirArchivo].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbrirArchivoDeTextoTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun el_intento_lleva_la_ruta_y_no_el_texto() {
        val i = MarkdownEditorActivity.intentoDeArchivo(context, "/data/guardados/a.md", "Apuntes.md", "md-abc", llano = false, soloLeer = true)
        assertEquals(MarkdownEditorActivity::class.java.name, i.component?.className)
        assertEquals("/data/guardados/a.md", i.getStringExtra(MarkdownEditorActivity.EXTRA_RUTA))
        assertEquals("Apuntes.md", i.getStringExtra(MarkdownEditorActivity.EXTRA_NOMBRE))
        assertNull(i.getStringExtra("md_texto"))
        assertEquals("md-abc", i.getStringExtra("md_id"))
        assertFalse(i.getBooleanExtra(MarkdownEditorActivity.EXTRA_LLANO, true))
        assertTrue(i.getBooleanExtra(MarkdownEditorActivity.EXTRA_SOLO_LEER, false))
        // Cada nota en su tarea: la misma nota, la misma.
        assertEquals("pixpin://nota/md-abc", i.dataString)
    }

    @Test
    fun el_texto_llano_lo_dice() {
        val i = MarkdownEditorActivity.intentoDeArchivo(context, "/x/registro.log", "registro.log", "md-pasoabc", llano = true, soloLeer = false)
        assertTrue(i.getBooleanExtra(MarkdownEditorActivity.EXTRA_LLANO, false))
        assertFalse(i.getBooleanExtra(MarkdownEditorActivity.EXTRA_SOLO_LEER, true))
    }
}
