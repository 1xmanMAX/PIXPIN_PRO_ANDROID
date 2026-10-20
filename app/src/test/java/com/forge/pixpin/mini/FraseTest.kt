package com.forge.pixpin.mini

import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las pruebas de `pixpin-frase/src/tarea.rs` del PC (18-oct-2026), una a una y con los mismos casos. */
class FraseTest {

    /** Miércoles 14 de octubre de 2026, 10:30 (el de las pruebas de fechas). */
    private fun ahora() = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)

    private fun a(mes: Int, dia: Int, h: Int, m: Int) = Momento(Fechas.diasDe(2026, mes.toLong(), dia.toLong()), h * 60 + m)

    private fun leer(t: String) = Frase.leer(t, ahora())

    @Test
    fun la_frase_del_usuario_se_entiende_entera() {
        val f = leer("llamar al banco prioridad alta recordar a las 5 #casa")
        assertEquals("llamar al banco", f.titulo)
        assertEquals(Urgencia.URGENTE, f.urgencia)
        assertEquals(listOf("casa"), f.etiquetas)
        assertEquals("las 5 son las 17:00 de hoy", a(10, 14, 17, 0), f.hora)
        assertEquals("llamar al banco 🔼 #casa ⏰ 2026-10-14 17:00", f.texto())
        assertEquals("Urgente · #casa · ⏰ hoy 17:00", f.resumen(ahora()))
    }

    @Test
    fun sin_palabras_la_urgencia_es_normal_y_no_lleva_marca() {
        val f = leer("regar las plantas")
        assertEquals(Urgencia.NORMAL, f.urgencia)
        assertEquals("regar las plantas", f.texto())
        assertEquals("", f.resumen(ahora()))
    }

    @Test
    fun urgente_y_muy_urgente_con_las_palabras_de_fabrica() {
        val u = { t: String -> leer(t).urgencia }
        assertEquals(Urgencia.URGENTE, u("pagar luz urgente"))
        assertEquals(Urgencia.URGENTE, u("pagar luz URGENTE!"))
        assertEquals(Urgencia.URGENTE, u("importante revisar planos"))
        assertEquals(Urgencia.URGENTE, u("revisar planos con prioridad alta"))
        assertEquals(Urgencia.URGENTE, u("revisar planos !"))
        assertEquals(Urgencia.MUY_URGENTE, u("pagar luz muy urgente"))
        assertEquals(Urgencia.MUY_URGENTE, u("pagar luz urgentísimo"))
        assertEquals(Urgencia.MUY_URGENTE, u("pagar luz prioridad maxima"))
        assertEquals(Urgencia.MUY_URGENTE, u("pagar luz !!"))
        assertEquals("gana la mayor", Urgencia.MUY_URGENTE, u("urgente y muy urgente a la vez"))
        assertEquals("pagar luz", leer("pagar luz muy urgente").titulo)
        assertEquals("revisar planos", leer("revisar planos con prioridad alta").titulo)
        assertEquals(Urgencia.MUY_URGENTE, u("pagar luz ⏫"))
        assertEquals(Urgencia.URGENTE, u("pagar luz 🔼"))
        val viejo = leer("pagar luz 🔽")
        assertEquals("la «baja» de antes", Urgencia.NORMAL to "pagar luz", viejo.urgencia to viejo.titulo)
        for (t in listOf("comprar pan prioridad baja", "ir a urgencias", "subir a la parte alta", "muy bien hecho", "¡hola"))
            assertEquals(t, Urgencia.NORMAL, u(t))
        assertEquals("ir a urgencias", leer("ir a urgencias").titulo)
    }

    @Test
    fun las_palabras_son_las_de_ajustes() {
        val p = Frase.Palabras(urgente = listOf("pronto"), muyUrgente = listOf("ya mismo"))
        val f = Frase.leer("llamar ya mismo", ahora(), p)
        assertEquals("llamar" to Urgencia.MUY_URGENTE, f.titulo to f.urgencia)
        assertEquals(Urgencia.URGENTE, Frase.leer("llamar pronto", ahora(), p).urgencia)
        assertEquals(Urgencia.NORMAL, Frase.leer("llamar urgente", ahora(), p).urgencia)
        val nada = Frase.Palabras(urgente = emptyList(), muyUrgente = listOf(" "))
        assertEquals(Urgencia.NORMAL, Frase.leer("llamar urgente !!", ahora(), nada).urgencia)
        assertEquals(listOf("pronto", "ya"), Frase.Palabras.deTextos("pronto, ya", null).urgente)
    }

    @Test
    fun las_etiquetas_en_cualquier_sitio_salen_del_titulo() {
        val f = leer("#obra pedir yeso #Obra para el #muro-norte, #1 hoy no")
        assertEquals("#1 es un número, no una etiqueta", "pedir yeso para el #1 hoy no", f.titulo)
        assertEquals(listOf("obra", "muro-norte"), f.etiquetas)
        val medio = leer("llamar a #casa Ana")
        assertEquals("llamar a Ana", medio.titulo)
        assertEquals(listOf("casa"), medio.etiquetas)
        assertEquals("llamar a Ana #casa", medio.texto())
        assertTrue(leer("precio # 5").etiquetas.isEmpty())
        assertTrue(leer("canal C#").etiquetas.isEmpty())
    }

    @Test
    fun la_hora_como_se_escribe() {
        val h = { t: String -> leer(t).hora }
        assertEquals(a(10, 18, 17, 0), h("pagar alquiler 18/10 17:00"))
        assertEquals(a(10, 18, 17, 0), h("pagar alquiler 18-10 5pm"))
        assertEquals(a(10, 18, 17, 0), h("pagar alquiler 18 oct 17:00"))
        assertEquals(a(10, 20, 9, 0), h("reunión el 20 a las 9"))
        assertEquals(a(10, 20, 8, 30), h("cita 20 de octubre 8:30"))
        assertEquals(a(10, 15, 7, 0), h("gimnasio mañana 7"))
        assertEquals(a(10, 16, 15, 30), h("entregar informe viernes 15:30"))
        assertEquals(a(10, 15, 10, 0), h("llamar a Ana recuérdame mañana a las 10"))
        assertEquals(a(10, 14, 18, 30), h("cita médico avisame a las 18:30"))
        val f = leer("18/10 17:00 llamar al banco")
        assertEquals("llamar al banco" to a(10, 18, 17, 0), f.titulo to f.hora)
        val g = leer("mañana 7 gimnasio")
        assertEquals("gimnasio" to a(10, 15, 7, 0), g.titulo to g.hora)
        assertEquals(Momento(Fechas.diasDe(2027, 10, 2), 9 * 60), h("renovar seguro 2/10"))
        val r = leer("recordar mañana comprar pan")
        assertEquals("comprar pan" to a(10, 15, 9, 0), r.titulo to r.hora)
    }

    @Test
    fun caso_negativo_lo_que_no_es_fecha_no_pone_hora() {
        for (t in listOf(
            "leer el capítulo 5", "recordar el 31 de febrero", "comprar 3 kilos", "recordar", "5 manzanas",
            "2 kilos de pan", "llamar al 911", "pagar 18/13", "hoy limpiar", "el 31 de febrero comprar"
        )) assertNull(t, leer(t).hora)
        assertEquals("recordar el 31 de febrero", leer("recordar el 31 de febrero").titulo)
        assertEquals("5 manzanas", leer("5 manzanas").titulo)
        assertEquals("regar las plantas", leer("  regar las plantas ").texto())
    }

    @Test
    fun lo_guardado_se_vuelve_a_leer_igual() {
        val crudo = "llamar al banco ⏫ #casa 💬 cierran a las 3 ⏰ 2026-10-14 17:00"
        val f = leer(crudo)
        assertEquals(crudo, f.texto())
        assertEquals("la nota no se lee como hora", "cierran a las 3", f.nota)
        val viejo = "pan ⏰ 2020-01-01 09:00"
        assertEquals(viejo, leer(viejo).texto())
        assertEquals("pan ⏰ 2026-10-15 07:00", leer("pan mañana 7 ⏰ 2026-10-14 17:00").texto())
        val m = Frase.marcasDe(crudo)
        assertEquals("llamar al banco", m.titulo)
        assertEquals(Urgencia.MUY_URGENTE, m.urgencia)
        assertEquals(listOf("casa"), m.etiquetas)
        assertEquals("cierran a las 3", m.nota)
        assertEquals(a(10, 14, 17, 0), m.hora)
    }

    @Test
    fun sin_la_fecha_dicha_lo_demas_queda_en_su_sitio() {
        val s = { t: String -> Frase.sinFechaDicha(t, ahora()) }
        assertEquals("pan urgente #casa", s("pan mañana a las 10 urgente #casa"))
        assertEquals("llamar #casa", s("18/10 17:00 llamar #casa"))
        assertEquals("llamar", s("llamar recuérdame el viernes"))
        assertEquals("leer el capítulo 5 urgente", s("leer el capítulo 5 urgente"))
    }
}
