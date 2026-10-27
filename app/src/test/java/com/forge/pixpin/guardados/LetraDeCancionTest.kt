package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.LetraDeCancion.Linea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **La letra de una canción** (27-oct-2026): leer LRC, guardarla como la leen el PC y la letra, y la línea que suena. */
class LetraDeCancionTest {

    @Test
    fun un_lrc_se_lee_con_sus_tiempos_titulo_y_artista() {
        val l = LetraDeCancion.leer(
            """
            [ti:Bésame mucho]
            [ar:Consuelo Velázquez]
            [by:alguien]
            [00:12.30]Bésame, bésame mucho
            [00:17.80]como si fuera esta noche la última vez
            """.trimIndent()
        )
        assertEquals("Bésame mucho", l.titulo)
        assertEquals("Consuelo Velázquez", l.artista)
        assertTrue(l.conTiempos)
        assertEquals(listOf(Linea(12_300, "Bésame, bésame mucho"), Linea(17_800, "como si fuera esta noche la última vez")), l.lineas)
    }

    @Test
    fun varias_marcas_en_una_linea_salen_en_su_orden_y_el_offset_se_aplica() {
        val l = LetraDeCancion.leer("[offset:+500]\n[00:10.00][00:30.00]Estribillo\n[00:20.00]Estrofa")
        assertEquals(listOf(Linea(9_500, "Estribillo"), Linea(19_500, "Estrofa"), Linea(29_500, "Estribillo")), l.lineas)
    }

    @Test
    fun las_marcas_por_palabra_del_lrc_mejorado_se_quitan() {
        val l = LetraDeCancion.leer("[00:01.00]<00:01.00>Hola <00:01.50>mundo")
        assertEquals(listOf(Linea(1_000, "Hola mundo")), l.lineas)
    }

    @Test
    fun los_tiempos_validos_y_los_que_no() {
        assertEquals(83_000, LetraDeCancion.tiempoDe("1:23"))
        assertEquals(83_450, LetraDeCancion.tiempoDe("01:23.45"))
        assertEquals(83_400, LetraDeCancion.tiempoDe("01:23.4"))
        assertEquals(83_456, LetraDeCancion.tiempoDe("01:23.456"))
        assertEquals(3_723_000, LetraDeCancion.tiempoDe("1:02:03"))
        assertNull(LetraDeCancion.tiempoDe("1:5"))
        assertNull(LetraDeCancion.tiempoDe("1:75"))
        assertNull(LetraDeCancion.tiempoDe("Coro"))
        assertNull(LetraDeCancion.tiempoDe("ti:Algo"))
    }

    @Test
    fun el_texto_llano_se_respeta_tal_cual() {
        val texto = "[Coro]\nPrimera línea\n\nSegunda estrofa"
        val l = LetraDeCancion.leer(texto)
        assertFalse(l.conTiempos)
        assertEquals(texto, LetraDeCancion.aGuardar(l))
    }

    @Test
    fun se_guarda_en_el_formato_que_leen_el_pc_y_la_pantalla_de_la_letra() {
        val guardado = LetraDeCancion.aGuardar(LetraDeCancion.leer("[00:12.30]Uno\n[00:17.80]Dos\n[01:02.00]\n[61:00.00]Tres"))
        // `[m:ss] texto`, renglón en blanco entre líneas, redondeado al segundo; un silencio, solo su marca.
        assertEquals("[0:12] Uno\n\n[0:18] Dos\n\n[1:02]\n\n[1:01:00] Tres", guardado)
        // Y lo lee de vuelta el mismo reconocedor de minutos que la burbuja y la pantalla de la letra.
        val bloques = guardado.split("\n\n")
        assertEquals(12_000 to "Uno", Transcriptor.tiempoDe(bloques[0]))
        assertEquals(3_660_000 to "Tres", Transcriptor.tiempoDe(bloques[3]))
    }

    @Test
    fun lo_guardado_vuelve_igual_al_leerlo_otra_vez() {
        val guardado = "[0:12] Uno\nsigue uno\n\n[0:18] Dos\n\nNota suelta"
        val l = LetraDeCancion.leer(guardado)
        assertEquals(listOf(Linea(12_000, "Uno\nsigue uno"), Linea(18_000, "Dos"), Linea(null, "Nota suelta")), l.lineas)
        assertEquals(guardado, LetraDeCancion.aGuardar(l))
    }

    @Test
    fun la_linea_que_suena_es_la_ultima_cuyo_tiempo_ya_paso() {
        val lineas = listOf(Linea(1_000, "a"), Linea(5_000, "b"), Linea(null, "c"), Linea(9_000, "d"))
        assertEquals(-1, LetraDeCancion.lineaActual(lineas, 500))
        assertEquals(0, LetraDeCancion.lineaActual(lineas, 1_000))
        assertEquals(1, LetraDeCancion.lineaActual(lineas, 8_999))
        assertEquals(3, LetraDeCancion.lineaActual(lineas, 60_000))
        assertEquals(-1, LetraDeCancion.lineaActual(listOf(Linea(null, "sin tiempos")), 60_000))
    }

    @Test
    fun a_lrc_y_de_vuelta() {
        val l = LetraDeCancion.Letra(listOf(Linea(12_340, "Uno"), Linea(75_000, "Dos")))
        val lrc = LetraDeCancion.aLrc(l, "Canción", "Artista")
        assertEquals("[ti:Canción]\n[ar:Artista]\n[00:12.34]Uno\n[01:15.00]Dos", lrc)
        val vuelta = LetraDeCancion.leer(lrc)
        assertEquals(l.lineas, vuelta.lineas)
        assertEquals("Canción", vuelta.titulo)
    }

    @Test
    fun parece_letra_lo_que_tiene_renglones_o_tiempos() {
        assertTrue(LetraDeCancion.pareceLetra("uno\ndos\ntres\ncuatro"))
        assertTrue(LetraDeCancion.pareceLetra("[00:01.00]sola"))
        assertFalse(LetraDeCancion.pareceLetra("https://ejemplo.com"))
        assertFalse(LetraDeCancion.pareceLetra(null))
    }

    @Test
    fun sincronizar_a_toques() {
        val s = SincronizadorDeLetra(listOf("Uno", "", "Dos", "Tres"))
        assertEquals(listOf("Uno", "Dos", "Tres"), s.textos)
        assertTrue(s.marcar(1_000))
        assertTrue(s.marcar(4_000))
        // Deshacer quita la última y vuelve un poco antes de la anterior.
        assertEquals(0, s.deshacer())
        assertEquals(1, s.siguiente)
        // Un toque «antes» de la anterior (tras saltar atrás) no desordena la letra.
        assertTrue(s.marcar(500))
        assertEquals(1_000, s.tiempo(1))
        assertFalse(s.marcar(7_000))
        assertTrue(s.terminado)
        assertFalse(s.marcar(9_000))
        assertEquals("[0:01] Uno\n\n[0:01] Dos\n\n[0:07] Tres", LetraDeCancion.aGuardar(s.letra()))
    }

    @Test
    fun sincronizar_a_medias_deja_lo_que_falta_sin_tiempo() {
        val s = SincronizadorDeLetra.de(LetraDeCancion.leer("Uno\nDos\nTres"))
        s.marcar(2_000)
        assertEquals(listOf(Linea(2_000, "Uno"), Linea(null, "Dos"), Linea(null, "Tres")), s.letra().lineas)
        assertNull(SincronizadorDeLetra(emptyList()).deshacer())
    }
}
