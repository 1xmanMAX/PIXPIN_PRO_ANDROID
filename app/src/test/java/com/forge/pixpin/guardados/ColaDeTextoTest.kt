package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.ColaDeTexto.Fin
import com.forge.pixpin.guardados.ColaDeTexto.Pendiente
import com.forge.pixpin.guardados.ColaDeTexto.Tarea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Si algo entra en la cola de pasar a texto, y cómo cambia la cola** (28-oct-2026). Ver [ColaDeTexto]. */
class ColaDeTextoTest {

    private fun voz(id: String = "v", texto: String? = null, estado: String? = null, ruta: String? = "/a.m4a") =
        Mensaje(id = id, cuando = 1, clase = Clase.VOZ, ruta = ruta, transcripcion = texto, estadoDelTexto = estado)

    private fun reunion(texto: String? = null) =
        voz(texto = texto).copy(grabacion = GrabarClase.CLASE, capturas = GrabarClase.capturasEnJson(emptyList()))

    @Test
    fun la_reunion_presencial_recien_guardada_entra_en_la_cola() {
        val m = Presencial.mensajes(
            "voz", "Reunión", 1000, null, "/r.m4a", 10, 60_000, emptyList(), null, emptyList(), emptyList()
        ).first()
        assertEquals(Tarea.PASAR_A_TEXTO, ColaDeTexto.queToca(m, hayAudio = true))
        assertTrue(ColaDeTexto.entraSola(m, hayAudio = true, grabadoAqui = false))
    }

    @Test
    fun una_clase_entra_sola_aunque_no_lo_diga_quien_la_guarda() {
        assertTrue(ColaDeTexto.entraSola(reunion(), hayAudio = true, grabadoAqui = false))
    }

    @Test
    fun una_nota_grabada_aqui_entra_y_una_importada_no() {
        assertTrue(ColaDeTexto.entraSola(voz(), hayAudio = true, grabadoAqui = true))
        assertFalse(ColaDeTexto.entraSola(voz(), hayAudio = true, grabadoAqui = false))
    }

    @Test
    fun lo_que_ya_tiene_texto_o_no_tiene_audio_no_entra() {
        assertEquals(Tarea.NADA, ColaDeTexto.queToca(reunion("[0:00] hola"), hayAudio = true))
        assertEquals(Tarea.NADA, ColaDeTexto.queToca(reunion(), hayAudio = false))
        assertEquals(Tarea.NADA, ColaDeTexto.queToca(voz(ruta = null), hayAudio = true))
        assertEquals(Tarea.NADA, ColaDeTexto.queToca(Mensaje(id = "i", cuando = 1, clase = Clase.IMAGEN, ruta = "/f.png"), hayAudio = true))
        assertEquals(Tarea.NADA, ColaDeTexto.queToca(null, hayAudio = true))
        assertFalse(ColaDeTexto.entraSola(reunion("[0:00] hola"), hayAudio = true, grabadoAqui = true))
    }

    @Test
    fun un_intento_que_acabo_mal_y_sin_texto_vuelve_a_tocar() {
        assertEquals(Tarea.PASAR_A_TEXTO, ColaDeTexto.queToca(reunion().copy(estadoDelTexto = TEXTO_MAL), hayAudio = true))
    }

    @Test
    fun una_cancion_sin_tiempos_o_sin_letra_pide_tiempos_y_con_tiempos_nada() {
        assertEquals(Tarea.TIEMPOS_DE_LA_LETRA, ColaDeTexto.queToca(voz(estado = TEXTO_LETRA), hayAudio = true))
        assertEquals(Tarea.TIEMPOS_DE_LA_LETRA, ColaDeTexto.queToca(voz(texto = "Bésame\nmucho", estado = TEXTO_LETRA), hayAudio = true))
        assertEquals(Tarea.NADA, ColaDeTexto.queToca(voz(texto = "[0:12] Bésame\n\n[0:18] mucho", estado = TEXTO_LETRA), hayAudio = true))
        // Importada o no, una canción sin tiempos entra.
        assertTrue(ColaDeTexto.entraSola(voz(texto = "Bésame", estado = TEXTO_LETRA), hayAudio = true, grabadoAqui = false))
    }

    @Test
    fun poner_no_repite_y_guarda_el_idioma() {
        var c = ColaDeTexto.poner(emptyList(), "a")
        c = ColaDeTexto.poner(c, "a")
        c = ColaDeTexto.poner(c, "b", "en")
        assertEquals(listOf(Pendiente("a"), Pendiente("b", idioma = "en")), c)
        assertEquals(Pendiente("a", idioma = "fr"), ColaDeTexto.poner(c, "a", "fr").first())
    }

    @Test
    fun hecho_sale_sin_modelo_espera_y_un_fallo_se_reintenta_hasta_tres_veces() {
        val c = listOf(Pendiente("a"), Pendiente("b"))
        assertEquals(listOf(Pendiente("b")), ColaDeTexto.alAcabar(c, "a", Fin.HECHO))
        assertEquals(listOf(Pendiente("b")), ColaDeTexto.alAcabar(c, "a", Fin.NADA_QUE_HACER))
        val sinModelo = ColaDeTexto.alAcabar(c, "a", Fin.SIN_MODELO)
        assertEquals(Pendiente("a", faltaElModelo = true), sinModelo.first())
        assertTrue(ColaDeTexto.esperaAlModelo(sinModelo))
        var f = c
        f = ColaDeTexto.alAcabar(f, "a", Fin.FALLO)
        assertEquals(1, f.first { it.id == "a" }.intentos)
        f = ColaDeTexto.alAcabar(f, "a", Fin.FALLO)
        f = ColaDeTexto.alAcabar(f, "a", Fin.FALLO)
        assertTrue(f.none { it.id == "a" })
    }

    @Test
    fun lo_que_espera_al_modelo_solo_se_retoma_con_red_y_lo_que_ya_corre_no() {
        val c = listOf(Pendiente("a"), Pendiente("b", faltaElModelo = true), Pendiente("c"))
        assertEquals(listOf("a", "c"), ColaDeTexto.aRetomar(c, hayRed = false, enMarcha = emptySet()).map { it.id })
        assertEquals(listOf("b", "c"), ColaDeTexto.aRetomar(c, hayRed = true, enMarcha = setOf("a")).map { it.id })
    }

    @Test
    fun limpiar_quita_lo_borrado_y_lo_que_ya_tiene_texto() {
        val c = listOf(Pendiente("v"), Pendiente("borrado"), Pendiente("hecho"))
        val mensajes = listOf(voz("v"), voz("hecho", texto = "[0:00] ya")).associateBy { it.id }
        assertEquals(listOf(Pendiente("v")), ColaDeTexto.limpiar(c, mensajes) { true })
    }

    @Test
    fun la_cola_va_y_vuelve_del_disco_y_un_archivo_roto_es_una_cola_vacia() {
        val c = listOf(Pendiente("a", 2, true, "es"), Pendiente("b"))
        assertEquals(c, ColaDeTexto.deJson(ColaDeTexto.aJson(c)))
        assertEquals(emptyList<Pendiente>(), ColaDeTexto.deJson("{roto"))
        assertEquals(emptyList<Pendiente>(), ColaDeTexto.deJson(null))
        assertEquals(listOf(Pendiente("ok")), ColaDeTexto.deJson("""[{"id":"ok"},{"sin":"id"},3,{"id":"ok"}]"""))
    }
}
