package com.forge.pixpin.audio

import com.forge.pixpin.audio.DecisionDeSalida.Preferida
import com.forge.pixpin.audio.DecisionDeSalida.Ruta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Por dónde suena** (27-oct-2026): las tres reglas de [DecisionDeSalida]. */
class DecisionDeSalidaTest {

    @Test
    fun los_audifonos_mandan_sobre_todo() {
        for (p in Preferida.entries) for (cerca in listOf(false, true)) {
            assertEquals(Ruta.AURICULARES, DecisionDeSalida.decidir(p, auriculares = true, cerca = cerca))
        }
    }

    @Test
    fun sin_audifonos_manda_lo_elegido() {
        assertEquals(Ruta.ALTAVOZ, DecisionDeSalida.decidir(Preferida.ALTAVOZ, false, false))
        assertEquals(Ruta.OIDO, DecisionDeSalida.decidir(Preferida.OIDO, false, false))
    }

    @Test
    fun el_telefono_en_la_oreja_pasa_al_auricular() {
        assertEquals(Ruta.OIDO, DecisionDeSalida.decidir(Preferida.ALTAVOZ, false, cerca = true))
        assertEquals(Ruta.OIDO, DecisionDeSalida.decidir(Preferida.OIDO, false, cerca = true))
    }

    @Test
    fun solo_se_rehace_al_cambiar_entre_auricular_y_lo_demas() {
        assertTrue(DecisionDeSalida.hayQueRehacer(alOido = false, ruta = Ruta.OIDO))
        assertTrue(DecisionDeSalida.hayQueRehacer(alOido = true, ruta = Ruta.ALTAVOZ))
        assertTrue(DecisionDeSalida.hayQueRehacer(alOido = true, ruta = Ruta.AURICULARES))
        // Altavoz y audífonos son el mismo uso de medios: el sistema cambia solo.
        assertFalse(DecisionDeSalida.hayQueRehacer(alOido = false, ruta = Ruta.AURICULARES))
        assertFalse(DecisionDeSalida.hayQueRehacer(alOido = false, ruta = Ruta.ALTAVOZ))
        assertFalse(DecisionDeSalida.hayQueRehacer(alOido = true, ruta = Ruta.OIDO))
    }

    @Test
    fun al_apartar_el_telefono_se_pausa_solo_si_se_habia_elegido_el_altavoz() {
        assertTrue(DecisionDeSalida.pausarAlAlejar(Preferida.ALTAVOZ, cercaAntes = true, cercaAhora = false, auriculares = false))
        assertFalse(DecisionDeSalida.pausarAlAlejar(Preferida.OIDO, cercaAntes = true, cercaAhora = false, auriculares = false))
        assertFalse(DecisionDeSalida.pausarAlAlejar(Preferida.ALTAVOZ, cercaAntes = false, cercaAhora = true, auriculares = false))
        assertFalse(DecisionDeSalida.pausarAlAlejar(Preferida.ALTAVOZ, cercaAntes = true, cercaAhora = false, auriculares = true))
    }

    @Test
    fun la_cercania_solo_se_escucha_sin_audifonos_y_con_quien_sepa_cambiar() {
        assertTrue(DecisionDeSalida.escucharLaCercania(hayQuienCambie = true, auriculares = false))
        assertFalse(DecisionDeSalida.escucharLaCercania(hayQuienCambie = true, auriculares = true))
        assertFalse(DecisionDeSalida.escucharLaCercania(hayQuienCambie = false, auriculares = false))
    }

    @Test
    fun cerca_es_por_debajo_del_maximo_del_sensor_y_de_cinco_centimetros() {
        // Sensor de dos valores (0 o su máximo, 8 cm).
        assertTrue(DecisionDeSalida.estaCerca(0f, 8f))
        assertFalse(DecisionDeSalida.estaCerca(8f, 8f))
        // Sensor que da centímetros de verdad.
        assertTrue(DecisionDeSalida.estaCerca(3f, 10f))
        assertFalse(DecisionDeSalida.estaCerca(6f, 10f))
        // Sensor de un solo centímetro de alcance: su máximo es «lejos».
        assertFalse(DecisionDeSalida.estaCerca(1f, 1f))
    }

    @Test
    fun el_boton_alterna() {
        assertEquals(Preferida.OIDO, DecisionDeSalida.otra(Preferida.ALTAVOZ))
        assertEquals(Preferida.ALTAVOZ, DecisionDeSalida.otra(Preferida.OIDO))
    }
}
