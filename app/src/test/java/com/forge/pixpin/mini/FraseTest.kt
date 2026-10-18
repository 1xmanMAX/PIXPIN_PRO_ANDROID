package com.forge.pixpin.mini

import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Las pruebas de `tareas/frase.rs` del PC (bfdd89a), una a una y con los mismos casos. Solo cambia
 * `a_la_vista`: aquí los emoticonos se quedan en el texto (Android no los saca como chapitas).
 */
class FraseTest {

    /** Miércoles 14 de octubre de 2026, 10:30 (el de las pruebas de fechas). */
    private fun ahora() = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)

    private fun a(dia: Int, h: Int, m: Int) = Momento(Fechas.diasDe(2026, 10, dia.toLong()), h * 60 + m)

    @Test
    fun la_frase_del_usuario_se_entiende_entera() {
        val f = Frase.leer("llamar al banco prioridad alta recordar a las 5 #casa", ahora())
        assertEquals("llamar al banco", f.titulo)
        assertEquals(Prioridad.ALTA, f.prioridad)
        assertEquals(listOf("casa"), f.etiquetas)
        assertEquals("las 5 son las 17:00 de hoy", a(14, 17, 0), f.hora)
        assertEquals("llamar al banco ⏫ #casa ⏰ 2026-10-14 17:00", f.texto())
    }

    @Test
    fun prioridades_dichas_de_varias_formas() {
        val p = { t: String -> Frase.leer(t, ahora()).prioridad }
        assertEquals(Prioridad.BAJA, p("comprar pan prioridad baja"))
        assertEquals(Prioridad.MEDIA, p("comprar pan con prioridad media"))
        assertEquals(Prioridad.ALTA, p("revisar planos alta prioridad"))
        assertEquals(Prioridad.ALTA, p("pagar luz !alta"))
        assertEquals(Prioridad.ALTA, p("pagar luz urgente"))
        assertEquals(Prioridad.BAJA, p("pagar luz 🔽"))
        assertEquals("comprar pan", Frase.leer("comprar pan con prioridad media", ahora()).titulo)
        // Casos negativos: «alta» o «media» sueltas son palabras del título.
        assertNull(p("subir a la parte alta"))
        assertEquals("subir a la parte alta", Frase.leer("subir a la parte alta", ahora()).titulo)
        assertNull(p("media naranja"))
    }

    @Test
    fun las_etiquetas_se_quitan_del_titulo_y_no_se_repiten() {
        val f = Frase.leer("#obra pedir yeso #Obra #urgente-ya, #1", ahora())
        assertEquals("#1 es un número, no una etiqueta", "pedir yeso #1", f.titulo)
        assertEquals(listOf("obra", "urgente-ya"), f.etiquetas)
        assertNull(f.prioridad)
    }

    @Test
    fun el_recordatorio_tras_su_palabra_o_al_final() {
        val h = { t: String -> Frase.leer(t, ahora()).hora }
        assertEquals(a(15, 10, 0), h("llamar a Ana recuérdame mañana a las 10"))
        assertEquals(a(16, 9, 0), h("entregar informe el viernes"))
        assertEquals(a(20, 9, 0), h("pagar alquiler 20/10"))
        assertEquals(a(14, 18, 30), h("cita médico avisame a las 18:30"))
        assertEquals(a(15, 9, 0), h("recoger paquete a las 9 de la mañana"))
        // Lo que sigue a la fecha vuelve al título.
        val f = Frase.leer("recordar mañana comprar pan", ahora())
        assertEquals("comprar pan" to a(15, 9, 0), f.titulo to f.hora)
        assertEquals("llamar a Ana", Frase.leer("llamar a Ana recuérdame mañana a las 10", ahora()).titulo)
    }

    @Test
    fun caso_negativo_lo_que_no_es_fecha_no_pone_hora() {
        for (t in listOf("leer el capítulo 5", "recordar el 31 de febrero", "comprar 3 kilos", "recordar")) {
            assertNull(t, Frase.leer(t, ahora()).hora)
        }
        assertEquals("recordar el 31 de febrero", Frase.leer("recordar el 31 de febrero", ahora()).titulo)
        assertEquals("leer el capítulo 5", Frase.leer("leer el capítulo 5", ahora()).titulo)
        // Sin nada que reconocer, el texto tal cual.
        assertEquals("regar las plantas", Frase.leer("  regar las plantas ", ahora()).texto())
    }

    @Test
    fun las_marcas_guardadas_se_leen_para_ensenarlas() {
        val crudo = "llamar al banco ⏫ #casa 💬 cierran a las 3 ⏰ 2026-10-14 17:00 ➕ 2026-10-14"
        val m = Frase.marcas(crudo)
        assertEquals("llamar al banco", m.titulo)
        assertEquals(Prioridad.ALTA, m.prioridad)
        assertEquals(listOf("casa"), m.etiquetas)
        assertEquals("cierran a las 3", m.nota)
        assertEquals(Frase.msDe(a(14, 17, 0)), m.hora)
        val (texto, prioridad) = Frase.aLaVista(crudo, ahora())
        assertEquals("llamar al banco #casa · ⏰ hoy 17:00 · 💬 cierran a las 3", texto)
        assertEquals(Prioridad.ALTA, prioridad)
        // Una tarea de antes, sin marcas: tal cual.
        assertEquals("pan 🍞" to null, Frase.aLaVista("pan 🍞", null))
        // Y en el orden de Android (la fecha antes de la hora), lo mismo.
        assertEquals("llamar al banco", Frase.marcas("llamar al banco ⏫ ➕ 2026-10-14 ⏰ 2026-10-14 17:00").titulo)
    }

    @Test
    fun la_nota_entra_y_sale_sin_tocar_la_hora_la_fecha_ni_las_imagenes() {
        val crudo = "yeso ⏫ ![img 01](pixpin:files/a.png) ⏰ 2026-10-14 17:00 ➕ 2026-10-10"
        val con = Frase.conNota(crudo, "falta\nel camión ⏰")
        assertEquals("yeso ⏫ 💬 falta el camión ![img 01](pixpin:files/a.png) ⏰ 2026-10-14 17:00 ➕ 2026-10-10", con)
        assertEquals(Tareas.horaDe(crudo), Tareas.horaDe(con))
        assertEquals("la fecha sigue", Tareas.partir(crudo).second, Tareas.partir(con).second)
        assertEquals(1, Tareas.imagenes(Tareas.partir(con).first).second.size)
        // Otra nota cambia la de antes; vacía la quita.
        val otra = Frase.conNota(con, "mañana")
        assertEquals("mañana", Frase.marcas(otra).nota)
        assertEquals(1, otra.split(Frase.NOTA).size - 1)
        assertEquals(crudo, Frase.conNota(otra, "  "))
        // Sin hora ni fecha.
        assertEquals("pan 💬 no hay", Frase.conNota("pan", "no hay"))
        // En el orden de Android también se queda como estaba.
        val android = "pan ➕ 2026-10-10 ⏰ 2026-10-14 17:00"
        assertEquals("pan 💬 ya ➕ 2026-10-10 ⏰ 2026-10-14 17:00", Frase.conNota(android, "ya"))
        assertEquals(android, Frase.conNota(Frase.conNota(android, "ya"), ""))
    }

    @Test
    fun la_fecha_del_pc_tras_la_hora_ya_no_se_pierde() {
        // Antes `partir` y `sinHora` se comían la fecha de creación si iba detrás de la hora.
        val pc = "pan ⏫ ⏰ 2026-10-14 17:00 ➕ 2026-10-10"
        assertEquals("pan ⏫" to java.time.LocalDate.of(2026, 10, 10), Tareas.partir(pc))
        assertEquals("pan ⏫ ➕ 2026-10-10", Tareas.sinHora(pc))
        assertEquals("pan ⏫ ➕ 2026-10-10", Tareas.conHora(pc, null))
    }
}
