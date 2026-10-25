package com.forge.pixpin

import com.forge.pixpin.motor.FigurasDeSerie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FigurasDeSerieTest {
    private val asset = listOf("src/main/assets/figuras/serie.json.gz", "app/src/main/assets/figuras/serie.json.gz")
        .map(::File).first { it.exists() }
    private val todas by lazy { asset.inputStream().use(FigurasDeSerie::leer) }

    @Test fun se_leen_las_627_del_pc_con_sus_piezas() {
        assertEquals(627, todas.size)
        assertTrue(todas.all { it.id.startsWith("serie-") && it.elementos.isNotEmpty() })
        assertEquals(todas.size, todas.map { it.id }.toSet().size)
        assertTrue(todas.sumOf { it.elementos.size } > 19000)
    }

    @Test fun todas_caen_en_una_categoria_del_pc() {
        val grupos = FigurasDeSerie.porCategoria(todas)
        assertEquals(FigurasDeSerie.CATEGORIAS, grupos.map { it.first })
        assertEquals(46, grupos.first { it.first == "Electrónica" }.second.size)
    }

    @Test fun los_textos_conservan_su_letra_del_pc() {
        val familias = todas.flatMap { it.elementos }.mapNotNull { it.fontFamily }.toSet()
        assertTrue(familias.containsAll(setOf(101, 102, 104)))
    }

    @Test fun la_categoria_va_en_el_nombre() {
        assertEquals("Diario" to "Gratitud", FigurasDeSerie.categoriaDe("Diario · Gratitud"))
        assertNull(FigurasDeSerie.categoriaDe("Mi figura"))
        assertEquals("Gratitud", FigurasDeSerie.nombreCorto("Diario · Gratitud"))
    }
}

class FigurasDelPcTest {
    @Test fun el_figuras_json_del_pc_con_puntos_en_par_se_lee() {
        val delPc = """[{"id":"a","nombre":"Raya","propia":true,"elementos":[{"id":"e","type":"line","x":0,"y":0,"width":10,"height":0,"seed":1,"points":[[0.0,0.0],[10.0,0.0]]}]}]"""
        val l = FigurasDeSerie.leerTexto(delPc)
        assertEquals(1, l.size)
        assertEquals(10.0, l[0].elementos[0].points!![1].x, 0.0)
    }

    @Test fun el_de_este_telefono_se_sigue_leyendo() {
        val aqui = """[{"id":"a","nombre":"Raya","elementos":[{"id":"e","type":"line","x":0,"y":0,"width":10,"height":0,"seed":1,"points":[{"x":0.0,"y":0.0},{"x":10.0,"y":0.0}]}]}]"""
        assertEquals(10.0, FigurasDeSerie.leerTexto(aqui)[0].elementos[0].points!![1].x, 0.0)
    }
}

class InstalarFigurasDeSerieTest {
    private fun f(id: String, nombre: String) = com.forge.pixpin.motor.FiguraGuardada(id, nombre, propia = true)

    @Test fun quita_las_de_antes_y_pone_las_de_ahora_al_final_sin_tocar_las_mias() {
        val lista = listOf(f("serie-vieja", "Diario · Viejo"), f("a", "Mía"), f("b", "Otra mía"))
        val catalogo = listOf(f("serie-1", "Diario · Gratitud"), f("serie-2", "Blocs · Cuadros"))
        val r = com.forge.pixpin.motor.FigurasDeSerie.instalarEn(lista, catalogo)
        assertEquals(listOf("a", "b", "serie-1", "serie-2"), r.map { it.id })
    }

    @Test fun si_una_mia_se_llama_igual_gana_la_mia() {
        val r = com.forge.pixpin.motor.FigurasDeSerie.instalarEn(
            listOf(f("a", "diario · gratitud")), listOf(f("serie-1", "Diario · Gratitud"))
        )
        assertEquals(listOf("a"), r.map { it.id })
    }

    @Test fun instalar_dos_veces_deja_lo_mismo() {
        val catalogo = listOf(f("serie-1", "Diario · Gratitud"))
        val una = com.forge.pixpin.motor.FigurasDeSerie.instalarEn(listOf(f("a", "Mía")), catalogo)
        assertEquals(una, com.forge.pixpin.motor.FigurasDeSerie.instalarEn(una, catalogo))
    }

    @Test fun el_catalogo_en_claro_como_queda_en_el_apk_tambien_se_lee() {
        val gz = listOf("src/main/assets/figuras/serie.json.gz", "app/src/main/assets/figuras/serie.json.gz").map(::File).first { it.exists() }
        val claro = java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()
        assertEquals(627, com.forge.pixpin.motor.FigurasDeSerie.leer(claro.inputStream()).size)
    }
}
