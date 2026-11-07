package com.forge.pixpin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VerTextoRicoTest {
    @Test
    fun las_marcas_no_se_ven_y_el_cursor_cae_en_su_sitio() {
        val f = "Hola **mundo** y \\color{verde}{casa}"
        val v = VerTextoRico.ver(f)
        assertEquals("Hola mundo y casa", v.texto.text)
        // «m» de mundo está en la fuente en 7, y se ve en 5.
        assertEquals(5, v.aVisto(7))
        assertEquals(7, v.aFuente(5))
        // Al final de lo que se ve, tras la última letra (antes de la llave que cierra).
        assertEquals(f.length - 1, v.aFuente(v.texto.length))
        // La negrita y el color van en su tramo.
        val negrita = v.texto.spanStyles.first { it.item.fontWeight != null }
        assertEquals("mundo", v.texto.text.substring(negrita.start, negrita.end))
        val color = v.texto.spanStyles.first { it.item.color != androidx.compose.ui.graphics.Color.Unspecified }
        assertEquals("casa", v.texto.text.substring(color.start, color.end))
    }

    @Test
    fun vacias_con_el_cursor_en_medio_las_formulas_y_las_directivas() {
        val v = VerTextoRico.ver("****")
        assertEquals("", v.texto.text)
        assertEquals(0, v.aVisto(2))
        assertEquals("a \$x*y\$ b", VerTextoRico.ver("a \$x*y\$ b").texto.text)
        assertEquals("Título\nfin", VerTextoRico.ver("\\centro Título\nfin").texto.text)
        assertEquals("no es \\color{nada", VerTextoRico.ver("~~no~~ es \\color{nada").texto.text)
    }
}
