package com.forge.pixpin.capture

import com.forge.pixpin.capture.GaleriaLogica.DIA_MS
import com.forge.pixpin.capture.GaleriaLogica.Ficha
import com.forge.pixpin.capture.GaleriaLogica.Filtro
import com.forge.pixpin.capture.GaleriaLogica.Plazo
import com.forge.pixpin.capture.GaleriaLogica.Tono
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo mismo que prueba `galeria_capturas/logica.rs` en el PC, en UTC para que no dependa de la máquina. */
class GaleriaLogicaTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun el_lunes_de_la_semana_y_el_dia_de_la_semana() {
        // 1-1-1970, jueves; 5-1-1970, lunes.
        assertEquals(3, GaleriaLogica.diaDeLaSemana(0))
        assertEquals(0, GaleriaLogica.diaDeLaSemana(4))
        assertEquals(-3L, GaleriaLogica.lunesDe(0))
        assertEquals("el 11-1 (domingo) es de la semana del 5-1", 4L, GaleriaLogica.lunesDe(10))
        // Caso negativo: el lunes siguiente ya es otra semana.
        assertNotEquals(GaleriaLogica.lunesDe(11), GaleriaLogica.lunesDe(10))
    }

    @Test
    fun el_dia_local_mira_la_zona() {
        val madrid = TimeZone.getTimeZone("Europe/Madrid")
        // 23:30 UTC del día 100 ya es el 101 en Madrid; en UTC, no.
        val t = 100 * DIA_MS + 23 * 3_600_000L + 30 * 60_000L
        assertEquals(101L, GaleriaLogica.diaLocal(t, madrid))
        assertEquals(100L, GaleriaLogica.diaLocal(t, utc))
    }

    @Test
    fun el_tono_va_de_gris_a_rojo_y_verde_si_se_conserva() {
        val ahora = 100 * DIA_MS + DIA_MS / 2
        fun en(d: Long) = GaleriaLogica.plazo(ahora + d * DIA_MS, ahora, utc)
        assertEquals(Tono.CONSERVADA, GaleriaLogica.tono(GaleriaLogica.plazo(null, ahora, utc)))
        assertEquals(Plazo.Hoy, en(0))
        assertEquals(Plazo.Manana, en(1))
        assertEquals(Tono.URGENTE, GaleriaLogica.tono(en(1)))
        assertEquals(Plazo.EnDias(2), en(2))
        assertEquals(Tono.PRONTO, GaleriaLogica.tono(en(3)))
        assertEquals(Tono.LEJOS, GaleriaLogica.tono(en(4)))
        // Caso negativo: lo ya pasado no sale como «lejos», sino «hoy».
        assertEquals(Plazo.Hoy, en(-2))
    }

    @Test
    fun la_pastilla_dice_lo_mismo_que_en_el_pc() {
        assertEquals("Conservada", GaleriaLogica.textoDelPlazo(Plazo.Conservada, utc))
        assertEquals("Se borra hoy", GaleriaLogica.textoDelPlazo(Plazo.Hoy, utc))
        assertEquals("Se borra mañana", GaleriaLogica.textoDelPlazo(Plazo.Manana, utc))
        assertEquals("Se borra en 3 días", GaleriaLogica.textoDelPlazo(Plazo.EnDias(3), utc))
        // 10-oct-2026, 12:00 UTC.
        val diezDeOctubre = 1_791_633_600_000L
        assertEquals("Se borra el 10 oct", GaleriaLogica.textoDelPlazo(Plazo.ElDia(diezDeOctubre), utc))
        assertEquals("hoy", GaleriaLogica.enDias(0))
        assertEquals("mañana", GaleriaLogica.enDias(1))
        assertEquals("en 5 días", GaleriaLogica.enDias(5))
    }

    private fun ficha(ext: String, cuando: Long, seVa: Long?, texto: String? = null) = Ficha("captura.$ext", ext, cuando, seVa, texto)

    @Test
    fun los_filtros_dejan_pasar_lo_suyo() {
        // A mediodía: a medianoche, «hace un segundo» ya es ayer.
        val ahora = 1000 * DIA_MS + DIA_MS / 2
        val hoy = ficha("png", ahora - 1000, ahora + 7 * DIA_MS, "hola")
        val vieja = ficha("gif", ahora - 20 * DIA_MS, ahora + DIA_MS, "")
        val video = ficha("MP4", ahora - 20 * DIA_MS, null)
        assertTrue(GaleriaLogica.pasa(Filtro.TODAS, vieja, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.HOY, hoy, ahora, utc) && !GaleriaLogica.pasa(Filtro.HOY, vieja, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.SEMANA, hoy, ahora, utc) && !GaleriaLogica.pasa(Filtro.SEMANA, vieja, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.CONSERVADAS, video, ahora, utc) && !GaleriaLogica.pasa(Filtro.CONSERVADAS, hoy, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.PRONTO, vieja, ahora, utc) && !GaleriaLogica.pasa(Filtro.PRONTO, hoy, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.GIF, vieja, ahora, utc) && !GaleriaLogica.pasa(Filtro.GIF, hoy, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.VIDEOS, video, ahora, utc))
        assertTrue(GaleriaLogica.pasa(Filtro.CON_TEXTO, hoy, ahora, utc))
        // Casos negativos: leída sin texto, o sin leer, no tiene texto.
        assertFalse(GaleriaLogica.pasa(Filtro.CON_TEXTO, vieja, ahora, utc))
        assertFalse(GaleriaLogica.pasa(Filtro.CON_TEXTO, video, ahora, utc))
    }

    @Test
    fun el_carril_cuenta_y_esconde_lo_que_no_hay() {
        val ahora = 1000 * DIA_MS + DIA_MS / 2
        val fichas = listOf(
            ficha("png", ahora - 1000, ahora + 7 * DIA_MS),
            ficha("png", ahora - 1000, ahora + 2 * DIA_MS),
            ficha("png", ahora - 30 * DIA_MS, null)
        )
        val carril = GaleriaLogica.carril(fichas, ahora, hayOcr = false, zona = utc).toMap()
        assertEquals(3, carril[Filtro.TODAS])
        assertEquals(2, carril[Filtro.HOY])
        assertEquals(1, carril[Filtro.CONSERVADAS])
        assertEquals(1, carril[Filtro.PRONTO])
        // Casos negativos: sin OCR no sale «Con texto»; sin GIF ni vídeos, sus filtros tampoco.
        assertFalse(Filtro.CON_TEXTO in carril)
        assertFalse(Filtro.GIF in carril || Filtro.VIDEOS in carril)
        val conGif = GaleriaLogica.carril(fichas + ficha("gif", ahora, ahora + DIA_MS), ahora, hayOcr = true, zona = utc).toMap()
        assertEquals(1, conGif[Filtro.GIF])
        assertTrue(Filtro.CON_TEXTO in conGif)
        // El orden es el del PC.
        assertEquals(Filtro.entries.filter { it in conGif }, conGif.keys.toList())
    }

    @Test
    fun la_busqueda_no_mira_tildes_ni_mayusculas_y_pide_todas_las_palabras() {
        val campos = listOf(GaleriaLogica.plegar("captura-0012.png"), GaleriaLogica.plegar("Presupuesto Obra MIRAFLORES · Acero"))
        assertTrue(GaleriaLogica.encaja(GaleriaLogica.plegar("miraflores"), campos))
        assertTrue(GaleriaLogica.encaja(GaleriaLogica.plegar("acéro 0012"), campos))
        assertTrue("sin consulta, todo", GaleriaLogica.encaja("", campos))
        // Caso negativo: una palabra que no está basta para que no encaje.
        assertFalse(GaleriaLogica.encaja(GaleriaLogica.plegar("acero ladrillo"), campos))
    }

    @Test
    fun se_busca_por_nombre_y_por_fecha() {
        // Sábado 3-oct-2026, 12:00 UTC.
        val tres = 1_791_028_800_000L
        val c = Ficha("PixPin_20261003_120000.png", "png", tres, null)
        assertTrue(GaleriaLogica.coincide(c, "pixpin", utc))
        assertTrue(GaleriaLogica.coincide(c, "3 oct", utc))
        assertTrue(GaleriaLogica.coincide(c, "Sabado", utc))
        assertTrue(GaleriaLogica.coincide(c, "  ", utc))
        // Casos negativos: otro día, otro mes.
        assertFalse(GaleriaLogica.coincide(c, "viernes", utc))
        assertFalse(GaleriaLogica.coincide(c, "nov", utc))
    }

    @Test
    fun los_grupos_por_dia() {
        val g = GaleriaLogica.agrupar(listOf(5L, 5, 5, 5, 4, 4))
        assertEquals(listOf(GaleriaLogica.Grupo(5, 0, 4), GaleriaLogica.Grupo(4, 4, 6)), g)
        // Caso negativo: vacía, sin grupos.
        assertTrue(GaleriaLogica.agrupar(emptyList()).isEmpty())
    }

    @Test
    fun el_titulo_del_dia() {
        // Día 20729 = sábado 3-oct-2026.
        val dia = 1_791_028_800_000L / DIA_MS
        assertEquals("Hoy" to "sábado 3 oct", GaleriaLogica.tituloDelDia(dia, dia, utc))
        assertEquals("Ayer" to "sábado 3 oct", GaleriaLogica.tituloDelDia(dia, dia + 1, utc))
        assertEquals("Sábado" to "3 oct", GaleriaLogica.tituloDelDia(dia, dia + 5, utc))
        assertEquals("Hoy, 12:00", GaleriaLogica.fechaYHora(dia * DIA_MS + DIA_MS / 2, dia * DIA_MS + DIA_MS / 2, utc))
        assertEquals("3 oct, 9:05", GaleriaLogica.fechaYHora(dia * DIA_MS + 9 * 3_600_000L + 5 * 60_000L, (dia + 3) * DIA_MS, utc))
    }

    @Test
    fun elegir_todo_el_dia_y_soltarlo() {
        val dia = listOf("a", "b")
        val una = setOf("a", "z")
        val todas = GaleriaLogica.alternarVarias(una, dia)
        assertEquals(setOf("a", "b", "z"), todas)
        // Si ya estaban todas, el mismo botón las suelta (y deja las de otros días).
        assertEquals(setOf("z"), GaleriaLogica.alternarVarias(todas, dia))
        // Caso negativo: un día vacío no cambia nada.
        assertEquals(una, GaleriaLogica.alternarVarias(una, emptyList()))
        assertEquals(setOf("z"), GaleriaLogica.alternarUna(una, "a"))
        assertEquals("1 elegida", GaleriaLogica.elegidas(1))
        assertEquals("3 elegidas", GaleriaLogica.elegidas(3))
    }

    @Test
    fun los_tamanos_se_leen_bien() {
        assertEquals("412 KB", GaleriaLogica.tamanoLegible(412 * 1024))
        assertEquals("1,2 GB", GaleriaLogica.tamanoLegible(1_288_490_189))
        assertEquals("5.3 MB", GaleriaLogica.tamanoLegible(5 * 1024 * 1024 + 300_000, '.'))
        // Caso negativo: nada es 0 KB, y un byte no es 0 KB.
        assertEquals("0 KB", GaleriaLogica.tamanoLegible(0))
        assertEquals("1 KB", GaleriaLogica.tamanoLegible(1))
    }

    @Test
    fun el_subtitulo_dice_cuanto_duran() {
        assertEquals("12 · duran 7 días", GaleriaLogica.subtitulo(12, 7))
        assertEquals("1 · duran 1 día", GaleriaLogica.subtitulo(1, 1))
        // Caso negativo: con cero no caducan.
        assertEquals("3 · no caducan", GaleriaLogica.subtitulo(3, 0))
    }
}
