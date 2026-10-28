package com.forge.pixpin.sincro

import com.forge.pixpin.sincro.PlanCaliente.Paso
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **La conexión caliente para las fotos** (28-oct-2026, §11.7): abierta mientras el PC graba, una
 * pregunta cada 3 s, las fotos al momento, reabrir con 0,5, 1, 2… s hasta 5 s, y `adios` al acabar
 * con la cola vacía.
 */
class ConexionCalienteTest {

    @Test
    fun sin_grabacion_ni_fotos_no_se_abre_nada() {
        val p = PlanCaliente()
        assertEquals(Paso.NADA, p.paso(0, grabando = false, abierta = false, hayFotos = false))
    }

    @Test
    fun el_pc_graba_se_abre_y_se_pregunta_cada_tres_segundos() {
        val p = PlanCaliente()
        assertEquals(Paso.ABRIR, p.paso(0, grabando = true, abierta = false, hayFotos = false))
        p.abrio()
        assertEquals("la primera pregunta, ya", Paso.PREGUNTAR, p.paso(10, true, true, false))
        p.pregunto(10)
        assertEquals(Paso.NADA, p.paso(2_000, true, true, false))
        assertEquals("como mucho un segundo dormido", 1_000L, p.espera(1_000, true, true, false))
        assertEquals("lo justo hasta la siguiente pregunta", 510L, p.espera(2_500, true, true, false))
        assertEquals(Paso.PREGUNTAR, p.paso(3_010, true, true, false))
    }

    @Test
    fun una_foto_sube_al_momento_por_la_misma_conexion() {
        val p = PlanCaliente()
        p.abrio(); p.pregunto(0)
        assertEquals(Paso.SUBIR, p.paso(100, grabando = true, abierta = true, hayFotos = true))
        assertEquals("despierta enseguida", 50L, p.espera(100, true, true, true))
        // Si no salió, espera antes de volver a intentarlo (2, 4, 8… s) y sigue preguntando.
        p.subio(100, bien = false)
        assertEquals(Paso.NADA, p.paso(1_000, true, true, true))
        assertEquals("a los 2 s, otra vez", Paso.SUBIR, p.paso(2_100, true, true, true))
        p.subio(2_100, bien = false)
        assertEquals("entretanto, el keepalive sigue", Paso.PREGUNTAR, p.paso(3_000, true, true, true))
        p.pregunto(3_000)
        assertEquals(Paso.NADA, p.paso(3_001, true, true, true))
        // Una foto nueva no espera a lo que falló antes.
        p.fotoNueva()
        assertEquals(Paso.SUBIR, p.paso(3_002, true, true, true))
    }

    @Test
    fun si_se_cae_se_reabre_con_esperas_que_se_doblan_hasta_cinco_segundos() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..6).map { PlanCaliente.esperaTrasFallo(it) })
        val p = PlanCaliente()
        p.fallo(0)
        assertEquals(Paso.NADA, p.paso(400, grabando = false, abierta = false, hayFotos = true))
        assertEquals(Paso.ABRIR, p.paso(500, false, false, true))
        p.fallo(500)
        assertEquals(Paso.NADA, p.paso(1_400, false, false, true))
        assertEquals(Paso.ABRIR, p.paso(1_500, false, false, true))
        // Llega la siguiente baliza del PC: ya, sin esperar.
        p.fallo(1_500)
        p.oyoBaliza()
        assertEquals(Paso.ABRIR, p.paso(1_501, true, false, false))
    }

    @Test
    fun al_acabar_con_la_cola_vacia_adios_y_con_fotos_primero_suben() {
        val p = PlanCaliente()
        p.abrio(); p.pregunto(0)
        assertEquals("acabó pero quedan fotos", Paso.SUBIR, p.paso(100, grabando = false, abierta = true, hayFotos = true))
        assertEquals(Paso.CERRAR, p.paso(200, grabando = false, abierta = true, hayFotos = false))
    }
}
