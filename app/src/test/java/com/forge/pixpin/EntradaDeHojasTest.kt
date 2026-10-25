package com.forge.pixpin

import com.forge.pixpin.excel.EntradaDeHojas
import com.forge.pixpin.excel.EntradaDeHojas.ComoSeVe
import com.forge.pixpin.excel.EntradaDeHojas.Destino
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Todo va al visor de hojas, nada a la vista de Tablas** (25-oct-2026, el usuario: «quita la
 * visualización de Excel antigua y todo lo incorporas en la nueva»).
 */
class EntradaDeHojasTest {

    @Test
    fun todas_las_hojas_de_calculo_van_al_visor() {
        for (n in listOf("a.xlsx", "a.XLSM", "a.xltx", "a.xls", "a.ods", "a.csv", "a.tsv")) {
            assertTrue(n, EntradaDeHojas.esHojaDeCalculo(n, "/x/$n"))
            assertEquals(n, Destino.Archivo, EntradaDeHojas.destino(null, nombre = n, ruta = "/x/$n"))
        }
        // El nombre del mensaje manda aunque el archivo copiado no tenga extensión.
        assertEquals(Destino.Archivo, EntradaDeHojas.destino(null, nombre = "Cuentas.ods", ruta = "/x/f-123"))
        assertEquals(Destino.OtraCosa, EntradaDeHojas.destino(null, nombre = "a.pdf", ruta = "/x/a.pdf"))
        assertEquals(Destino.OtraCosa, EntradaDeHojas.destino(null, nombre = "a.xlsx", ruta = null))
        assertFalse(EntradaDeHojas.esHojaDeCalculo("a.docx", null))
    }

    @Test
    fun las_tablas_van_al_visor_por_su_id() {
        // La mini-app `tabla` del chat y la tabla de un proyecto: una pestaña.
        assertEquals(Destino.Tablas(listOf("mini-m1"), "mini-m1"), EntradaDeHojas.destino("mini-m1"))
        assertEquals(Destino.Tablas(listOf("t-5"), "t-5"), EntradaDeHojas.destino("t-5"))
        // Una hoja de un libro del chat: con sus hermanas, en orden, y ella delante.
        val hermanas = listOf("libro-m2-0", "libro-m2-1", "libro-m2-2")
        assertEquals(Destino.Tablas(hermanas, "libro-m2-1"), EntradaDeHojas.destino("libro-m2-1", hermanas))
        // Si las hermanas no la nombran (aún sin mensajes), sola.
        assertEquals(Destino.Tablas(listOf("libro-m2-4"), "libro-m2-4"), EntradaDeHojas.destino("libro-m2-4", hermanas))
        // Una tabla manda aunque tenga nombre de archivo.
        assertTrue(EntradaDeHojas.destino("t-1", nombre = "a.xlsx", ruta = "/x/a.xlsx") is Destino.Tablas)
    }

    @Test
    fun un_libro_se_ve_por_su_archivo_si_nadie_edito_sus_hojas() {
        assertEquals(ComoSeVe.ARCHIVO, EntradaDeHojas.comoSeVe(seAbreNativo = true, hojasEditadas = false, hayMensajesDeHojas = false, hayTablasViejas = false))
        // Sus hojas ya son mensajes pero sin tocar: el original.
        assertEquals(ComoSeVe.ARCHIVO, EntradaDeHojas.comoSeVe(true, false, true, true))
        // Editadas aquí o en el PC: lo editado no se esconde.
        assertEquals(ComoSeVe.TABLAS, EntradaDeHojas.comoSeVe(true, true, true, false))
        // Hojas viejas del móvil, solo en archivo y sin mensajes: por ellas.
        assertEquals(ComoSeVe.TABLAS, EntradaDeHojas.comoSeVe(true, false, false, true))
        // Sin el .so, un `.ods`, un `.csv`, un `.xls`…: por sus tablas.
        assertEquals(ComoSeVe.TABLAS, EntradaDeHojas.comoSeVe(false, false, false, false))
    }

    @Test
    fun editada_es_como_en_el_pc_con_dos_segundos_de_holgura() {
        assertFalse(EntradaDeHojas.editada(tocado = 1_000, cuando = 1_000))
        assertFalse(EntradaDeHojas.editada(tocado = 3_000, cuando = 1_000))
        assertTrue(EntradaDeHojas.editada(tocado = 3_001, cuando = 1_000))
        assertFalse(EntradaDeHojas.editada(tocado = 0, cuando = 1_000))
    }

    @Test
    fun la_vista_de_tablas_queda_apagada() {
        assertFalse(com.forge.pixpin.excel.ExcelActivity.EDITAR_EN_TABLAS)
    }
}
