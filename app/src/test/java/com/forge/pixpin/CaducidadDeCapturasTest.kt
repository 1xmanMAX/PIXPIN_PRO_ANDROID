package com.forge.pixpin.capture

import com.forge.pixpin.capture.CaducidadDeCapturas.DIAS
import com.forge.pixpin.capture.CaducidadDeCapturas.DIA_MS
import com.forge.pixpin.capture.CaducidadDeCapturas.Registro
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las mismas pruebas que `caducidad_capturas.rs` del PC: las reglas y el fichero tienen que ser los mismos. */
class CaducidadDeCapturasTest {

    @Test
    fun una_captura_nueva_se_va_a_los_siete_dias_de_hacerse() {
        val r = Registro(desde = 0)
        assertEquals(17 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, DIAS))
        assertTrue(CaducidadDeCapturas.caducadas(r, listOf("a.png" to 10 * DIA_MS), 17 * DIA_MS - 1, DIAS).isEmpty())
        assertEquals(listOf(0), CaducidadDeCapturas.caducadas(r, listOf("a.png" to 10 * DIA_MS), 17 * DIA_MS, DIAS))
    }

    @Test
    fun las_que_ya_habia_cuentan_la_semana_desde_el_estreno_y_no_se_van_de_golpe() {
        val r = Registro(desde = 100 * DIA_MS)
        assertTrue("el primer día no", CaducidadDeCapturas.caducadas(r, listOf("vieja.png" to 3 * DIA_MS), 100 * DIA_MS, DIAS).isEmpty())
        assertEquals(107 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "vieja.png", 3 * DIA_MS, DIAS))
    }

    @Test
    fun el_plazo_lo_pone_el_ajuste_y_cero_es_nunca() {
        val r = Registro(desde = 0)
        assertEquals(40 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, 30))
        assertEquals(11 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, 1))
        // Caso negativo: con cero no se va, por vieja que sea.
        assertNull(CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, 0))
        assertTrue(CaducidadDeCapturas.caducadas(r, listOf("a.png" to 0L), Long.MAX_VALUE / 2, 0).isEmpty())
    }

    @Test
    fun la_conservada_no_se_va_nunca() {
        val r = Registro(desde = 0, conservadas = setOf("a.png"))
        assertNull(CaducidadDeCapturas.seVaEl(r, "a.png", 0, DIAS))
        assertTrue(CaducidadDeCapturas.caducadas(r, listOf("a.png" to 0L), Long.MAX_VALUE / 2, DIAS).isEmpty())
        // Caso negativo: conservar una no conserva otra.
        assertEquals(DIAS * DIA_MS, CaducidadDeCapturas.seVaEl(r, "b.png", 0, DIAS))
    }

    @Test
    fun dar_siete_dias_mas_alarga_desde_la_fecha_que_tenia() {
        var r = Registro(desde = 0)
        // Se iba el día 17; hoy es el 12: pasa al 24.
        assertEquals(24 * DIA_MS, CaducidadDeCapturas.fechaProrrogada(r, "a.png", 10 * DIA_MS, 12 * DIA_MS, DIAS))
        r = CaducidadDeCapturas.prorrogada(r, "a.png", 10 * DIA_MS, 12 * DIA_MS, DIAS)
        assertEquals(24 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, DIAS))
        assertTrue(CaducidadDeCapturas.caducadas(r, listOf("a.png" to 10 * DIA_MS), 20 * DIA_MS, DIAS).isEmpty())
        // Otra vez: del 24 al 31.
        assertEquals(31 * DIA_MS, CaducidadDeCapturas.fechaProrrogada(r, "a.png", 10 * DIA_MS, 12 * DIA_MS, DIAS))
        // Si ya se había pasado, siete días desde ahora.
        assertEquals(47 * DIA_MS, CaducidadDeCapturas.fechaProrrogada(r, "a.png", 10 * DIA_MS, 40 * DIA_MS, DIAS))
    }

    @Test
    fun caso_negativo_una_prorroga_vieja_no_acorta_ni_vale_para_otra() {
        var r = Registro(desde = 0, prorrogadas = mapOf("a.png" to 3 * DIA_MS))
        // Apuntada antes que la regla: gana la regla (día 17).
        assertEquals(17 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, DIAS))
        // La prórroga de otra captura no toca a esta.
        r = r.copy(prorrogadas = r.prorrogadas + ("b.png" to 90 * DIA_MS))
        assertEquals(17 * DIA_MS, CaducidadDeCapturas.seVaEl(r, "a.png", 10 * DIA_MS, DIAS))
        // Y a una conservada no se le prorroga nada.
        r = r.copy(conservadas = setOf("a.png"))
        assertNull(CaducidadDeCapturas.fechaProrrogada(r, "a.png", 10 * DIA_MS, 0, DIAS))
        assertEquals(r, CaducidadDeCapturas.prorrogada(r, "a.png", 10 * DIA_MS, 0, DIAS))
    }

    @Test
    fun el_fichero_es_el_del_pc() {
        // Un registro del PC, sin prórrogas: se lee.
        val viejo = CaducidadDeCapturas.deTexto("""{"desde": 5, "conservadas": ["a.png"]}""")!!
        assertEquals(5L, viejo.desde)
        assertEquals(setOf("a.png"), viejo.conservadas)
        assertTrue(viejo.prorrogadas.isEmpty())
        // Caso negativo: sin prórrogas el fichero queda como antes.
        assertFalse(CaducidadDeCapturas.aTexto(viejo).contains("prorrogadas"))
        // Con ellas, ida y vuelta, y con los nombres de campo del PC.
        val con = viejo.copy(prorrogadas = mapOf("b.png" to 99L))
        val texto = CaducidadDeCapturas.aTexto(con)
        assertTrue(texto, texto.contains("\"prorrogadas\"") && texto.contains("\"desde\"") && texto.contains("\"conservadas\""))
        assertEquals(con, CaducidadDeCapturas.deTexto(texto))
        // Sin «conservadas» (serde la deja vacía) también vale; sin «desde», no.
        assertEquals(Registro(desde = 7), CaducidadDeCapturas.deTexto("""{"desde": 7}"""))
        assertNull(CaducidadDeCapturas.deTexto("""{"conservadas": []}"""))
        assertNull(CaducidadDeCapturas.deTexto("{roto"))
    }

    @Test
    fun el_registro_se_crea_una_vez_y_conserva_su_estreno() {
        val raiz = Files.createTempDirectory("pixpin-caducidad").toFile()
        try {
            assertEquals(1000L, CaducidadDeCapturas.leer(raiz, 1000).desde)
            // Leído otro día, el estreno es el de la primera vez.
            assertEquals(1000L, CaducidadDeCapturas.leer(raiz, 9_999_999).desde)
            CaducidadDeCapturas.cambiar(raiz, 5) { it.copy(conservadas = it.conservadas + "x.png") }
            assertTrue("x.png" in CaducidadDeCapturas.leer(raiz, 5).conservadas)
            val r = CaducidadDeCapturas.cambiar(raiz, 5) { CaducidadDeCapturas.prorrogada(it, "y.png", 0, 5, DIAS) }
            assertEquals(1000 + 2 * DIAS * DIA_MS, r.prorrogadas["y.png"])
            assertEquals(1000 + 2 * DIAS * DIA_MS, CaducidadDeCapturas.leer(raiz, 5).prorrogadas["y.png"])
            // Un registro roto se aparta y se empieza de nuevo, sin pánico.
            File(raiz, CaducidadDeCapturas.FICHERO).writeText("{roto")
            assertEquals(77L, CaducidadDeCapturas.leer(raiz, 77).desde)
            assertTrue(raiz.listFiles()!!.any { it.name.startsWith("${CaducidadDeCapturas.FICHERO}.roto-") })
        } finally {
            raiz.deleteRecursively()
        }
    }

    @Test
    fun borrar_olvida_lo_apuntado_de_esas_y_solo_de_esas() {
        val r = Registro(desde = 0, conservadas = setOf("a.png", "b.png"), prorrogadas = mapOf("c.png" to 9L, "d.png" to 8L))
        val sin = CaducidadDeCapturas.sinEstas(r, listOf("a.png", "c.png"))
        assertEquals(setOf("b.png"), sin.conservadas)
        // Caso negativo: lo de las demás se queda.
        assertEquals(mapOf("d.png" to 8L), sin.prorrogadas)
        assertEquals(0L, sin.desde)
    }
}
