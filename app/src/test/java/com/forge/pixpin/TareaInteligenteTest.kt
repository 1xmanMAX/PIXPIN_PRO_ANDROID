package com.forge.pixpin

import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.mini.AlarmasDeTareas
import com.forge.pixpin.mini.TareaInteligente
import com.forge.pixpin.mini.TareaInteligente.Palabras
import com.forge.pixpin.mini.TareaInteligente.Urgencia
import com.forge.pixpin.mini.Tarea
import com.forge.pixpin.mini.Tareas
import com.forge.pixpin.mini.TodasLasTareas
import com.forge.pixpin.widget.LogicaDeLosWidgets
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las tareas inteligentes del PC** (`crates/pixpin-frase/src/tarea.rs`, 17 y 18-oct-2026): sus
 * pruebas portadas tal cual, con los mismos casos y los mismos textos esperados (menos las del
 * `pixpinmax.toml`, que aquí no hay), y detrás las del teléfono: los dos órdenes de `➕` y `⏰`,
 * lo que se apunta y lo que enseñan la lista, el widget y las alarmas.
 */
class TareaInteligenteTest {

    /** Miércoles 14 de octubre de 2026, 10:30 (el de las pruebas de fechas). */
    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 10 * 60 + 30)

    private fun a(mes: Long, dia: Long, h: Int, m: Int) = Momento(Fechas.diasDe(2026, mes, dia), h * 60 + m)

    private fun leer(t: String) = TareaInteligente.leer(t, ahora, Palabras.preestablecidas())

    // ---- Las del PC ----------------------------------------------------------

    @Test fun `la frase del usuario se entiende entera`() {
        val f = leer("llamar al banco prioridad alta recordar a las 5 #casa")
        assertEquals("llamar al banco", f.titulo)
        assertEquals(Urgencia.URGENTE, f.urgencia)
        assertEquals(listOf("casa"), f.etiquetas)
        assertEquals("las 5 son las 17:00 de hoy", a(10, 14, 17, 0), f.hora)
        assertEquals("llamar al banco 🔼 #casa ⏰ 2026-10-14 17:00", f.texto())
        assertEquals("Urgente · #casa · ⏰ hoy 17:00", f.resumen(ahora))
    }

    @Test fun `sin palabras la urgencia es normal y no lleva marca`() {
        val f = leer("regar las plantas")
        assertEquals(Urgencia.NORMAL, f.urgencia)
        assertEquals("regar las plantas", f.texto())
        assertEquals("", f.resumen(ahora))
        assertEquals(Urgencia.NORMAL, TareaInteligente.Frase().urgencia)
    }

    @Test fun `urgente y muy urgente con las palabras de fabrica`() {
        fun u(t: String) = leer(t).urgencia
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
        assertEquals("«muy» no se queda", "pagar luz", leer("pagar luz muy urgente").titulo)
        assertEquals("revisar planos", leer("revisar planos con prioridad alta").titulo)
        // Lo guardado se vuelve a leer igual (corregir una tarea).
        assertEquals(Urgencia.MUY_URGENTE, u("pagar luz ⏫"))
        assertEquals(Urgencia.URGENTE, u("pagar luz 🔼"))
        val viejo = leer("pagar luz 🔽")
        assertEquals("la «baja» de antes", Urgencia.NORMAL to "pagar luz", viejo.urgencia to viejo.titulo)
        // Negativos: ni «baja» ni palabras parecidas.
        for (t in listOf("comprar pan prioridad baja", "ir a urgencias", "subir a la parte alta", "muy bien hecho", "¡hola"))
            assertEquals(t, Urgencia.NORMAL, u(t))
        assertEquals("ir a urgencias", leer("ir a urgencias").titulo)
    }

    @Test fun `las palabras son las de ajustes`() {
        val p = Palabras(urgente = listOf("pronto"), muyUrgente = listOf("ya mismo"))
        val f = TareaInteligente.leer("llamar ya mismo", ahora, p)
        assertEquals("llamar" to Urgencia.MUY_URGENTE, f.titulo to f.urgencia)
        assertEquals(Urgencia.URGENTE, TareaInteligente.leer("llamar pronto", ahora, p).urgencia)
        // Las de fábrica ya no valen si se quitaron.
        assertEquals(Urgencia.NORMAL, TareaInteligente.leer("llamar urgente", ahora, p).urgencia)
        // Sin ninguna, todo normal.
        val nada = Palabras(urgente = emptyList(), muyUrgente = listOf(" "))
        assertEquals(Urgencia.NORMAL, TareaInteligente.leer("llamar urgente !!", ahora, nada).urgencia)
    }

    @Test fun `las etiquetas en cualquier sitio salen del titulo`() {
        val f = leer("#obra pedir yeso #Obra para el #muro-norte, #1 hoy no")
        assertEquals("#1 es un número, no una etiqueta", "pedir yeso para el #1 hoy no", f.titulo)
        assertEquals(listOf("obra", "muro-norte"), f.etiquetas)
        val medio = leer("llamar a #casa Ana")
        assertEquals("llamar a Ana" to listOf("casa"), medio.titulo to medio.etiquetas)
        // En el fichero van detrás del título: el PC (y quien no sepa de esto) las ve.
        assertEquals("llamar a Ana #casa", medio.texto())
        // Negativos: «#» suelto o pegado a nada.
        assertTrue(leer("precio # 5").etiquetas.isEmpty())
        assertTrue(leer("canal C#").etiquetas.isEmpty())
    }

    @Test fun `la hora como se escribe`() {
        fun h(t: String) = leer(t).hora
        assertEquals(a(10, 18, 17, 0), h("pagar alquiler 18/10 17:00"))
        assertEquals(a(10, 18, 17, 0), h("pagar alquiler 18-10 5pm"))
        assertEquals(a(10, 18, 17, 0), h("pagar alquiler 18 oct 17:00"))
        assertEquals(a(10, 20, 9, 0), h("reunión el 20 a las 9"))
        assertEquals(a(10, 20, 8, 30), h("cita 20 de octubre 8:30"))
        assertEquals(a(10, 15, 7, 0), h("gimnasio mañana 7"))
        assertEquals(a(10, 16, 15, 30), h("entregar informe viernes 15:30"))
        assertEquals(a(10, 15, 10, 0), h("llamar a Ana recuérdame mañana a las 10"))
        assertEquals(a(10, 14, 18, 30), h("cita médico avisame a las 18:30"))
        // Al principio también.
        val f = leer("18/10 17:00 llamar al banco")
        assertEquals("llamar al banco" to a(10, 18, 17, 0), f.titulo to f.hora)
        val g = leer("mañana 7 gimnasio")
        assertEquals("gimnasio" to a(10, 15, 7, 0), g.titulo to g.hora)
        // Ya pasada este año: la del que viene.
        assertEquals(Momento(Fechas.diasDe(2027, 10, 2), 9 * 60), h("renovar seguro 2/10"))
        // Lo que sigue a la fecha tras «recordar» vuelve al título.
        val r = leer("recordar mañana comprar pan")
        assertEquals("comprar pan" to a(10, 15, 9, 0), r.titulo to r.hora)
    }

    @Test fun `caso negativo lo que no es fecha no pone hora`() {
        for (t in listOf(
            "leer el capítulo 5", "recordar el 31 de febrero", "comprar 3 kilos", "recordar", "5 manzanas",
            "2 kilos de pan", "llamar al 911", "pagar 18/13", "hoy limpiar", "el 31 de febrero comprar"
        )) assertNull(t, leer(t).hora)
        assertEquals("recordar el 31 de febrero", leer("recordar el 31 de febrero").titulo)
        assertEquals("5 manzanas", leer("5 manzanas").titulo)
        assertEquals("regar las plantas", leer("  regar las plantas ").texto())
    }

    @Test fun `lo guardado se vuelve a leer igual`() {
        val crudo = "llamar al banco ⏫ #casa 💬 cierran a las 3 ⏰ 2026-10-14 17:00"
        val f = leer(crudo)
        assertEquals(crudo, f.texto())
        assertEquals("la nota no se lee como hora", "cierran a las 3", f.nota)
        // Una hora ya pasada se respeta (es la que tenía).
        val viejo = "pan ⏰ 2020-01-01 09:00"
        assertEquals(viejo, leer(viejo).texto())
        // Corregida con otra fecha escrita: manda la nueva.
        assertEquals("pan ⏰ 2026-10-15 07:00", leer("pan mañana 7 ⏰ 2026-10-14 17:00").texto())
        // Y lo que se ve.
        val m = TareaInteligente.marcas(crudo)
        assertEquals("llamar al banco", m.titulo)
        assertEquals(Urgencia.MUY_URGENTE, m.urgencia)
        assertEquals(listOf("casa"), m.etiquetas)
        assertEquals("cierran a las 3", m.nota)
        assertEquals(a(10, 14, 17, 0), m.hora)
    }

    @Test fun `sin la fecha dicha lo demas queda en su sitio`() {
        fun s(t: String) = TareaInteligente.sinFechaDicha(t, ahora)
        assertEquals("pan urgente #casa", s("pan mañana a las 10 urgente #casa"))
        assertEquals("llamar #casa", s("18/10 17:00 llamar #casa"))
        assertEquals("llamar", s("llamar recuérdame el viernes"))
        // Sin fecha, tal cual.
        assertEquals("leer el capítulo 5 urgente", s("leer el capítulo 5 urgente"))
    }

    // ---- Las del teléfono --------------------------------------------------------

    @Test fun `lo apuntado aqui se guarda como en el PC`() {
        val hoy = LocalDate.of(2026, 10, 14)
        assertEquals(
            "llamar al banco ⏫ #casa ⏰ 2026-10-15 10:00 ➕ 2026-10-14",
            TareaInteligente.paraApuntar("llamar al banco muy urgente #casa mañana a las 10", hoy, ahora)
        )
        assertEquals("regar las plantas ➕ 2026-10-14", TareaInteligente.paraApuntar("  regar las plantas ", hoy, ahora))
        // Lo pegado con su fecha se queda tal cual; lo vacío no apunta nada.
        assertEquals("pan ➕ 2026-10-02", TareaInteligente.paraApuntar("pan ➕ 2026-10-02", hoy, ahora))
        assertEquals("", TareaInteligente.paraApuntar("   ", hoy, ahora))
        // Solo «#casa»: sin título no se entiende nada, se guarda lo escrito.
        assertEquals("#casa ➕ 2026-10-14", TareaInteligente.paraApuntar("#casa", hoy, ahora))
        // Y la lista lo guarda sin tocarlo (con su `➕` detrás de la hora, como el PC).
        val doc = TodasLasTareas.conTarea("# Inbox\n", TareaInteligente.paraApuntar("pan urgente el viernes", hoy, ahora), hoy)
        assertEquals("pan 🔼 ⏰ 2026-10-16 09:00 ➕ 2026-10-14", Tareas.leer(doc).single().texto)
    }

    @Test fun `con imagenes estas van detras del titulo y delante de las marcas`() {
        val t = TareaInteligente.aGuardar("yeso ![img 01](/f/a.png) urgente #obra mañana", ahora)
        assertEquals("yeso ![img 01](/f/a.png) 🔼 #obra ⏰ 2026-10-15 09:00", t)
        assertEquals(listOf("/f/a.png"), TareaInteligente.analizar(t).imagenes)
        assertEquals("![img 01](/f/a.png)", TareaInteligente.aGuardar("![img 01](/f/a.png)", ahora))
    }

    @Test fun `una casilla se analiza por partes en los dos ordenes`() {
        val delPc = "llamar al banco ⏫ #casa 💬 no abren hasta el lunes ⏰ 2026-10-17 17:00 ➕ 2026-10-17"
        val p = TareaInteligente.analizar(delPc)
        assertEquals("llamar al banco", p.titulo)
        assertEquals(Urgencia.MUY_URGENTE, p.urgencia)
        assertEquals(listOf("casa"), p.etiquetas)
        assertEquals("no abren hasta el lunes", p.nota)
        assertEquals(a(10, 17, 17, 0), p.recordatorio)
        assertEquals(LocalDate.of(2026, 10, 17), p.creada)
        // El orden de siempre del teléfono.
        val delMovil = TareaInteligente.analizar("pan 🔼 #casa ➕ 2026-10-02 ⏰ 2026-10-09 10:30")
        assertEquals("pan", delMovil.titulo)
        assertEquals(Urgencia.URGENTE, delMovil.urgencia)
        assertEquals(LocalDate.of(2026, 10, 2), delMovil.creada)
        assertEquals(a(10, 9, 10, 30), delMovil.recordatorio)
    }

    @Test fun `quitar o cambiar la hora conserva la fecha de creacion del PC`() {
        val delPc = "llamar ⏫ ⏰ 2026-10-17 17:00 ➕ 2026-10-17"
        assertEquals(LocalDateTime.of(2026, 10, 17, 17, 0), Tareas.horaDe(delPc))
        assertEquals("llamar ⏫ ➕ 2026-10-17", Tareas.sinHora(delPc))
        assertEquals("llamar ⏫ ➕ 2026-10-17 ⏰ 2026-10-18 09:00", Tareas.conHora(delPc, LocalDateTime.of(2026, 10, 18, 9, 0)))
        assertEquals("llamar ⏫" to LocalDate.of(2026, 10, 17), Tareas.partir(delPc))
        // Lo que no es la fecha, detrás de la hora, se va con ella (como siempre).
        assertEquals("x", Tareas.sinHora("x ⏰ 2026-10-17 17:00 y algo"))
        // Al sonar se quita la hora y el aviso dice el título limpio, con su urgencia.
        val doc = Tareas.escribir("Inbox", listOf(Tarea(delPc)))
        val (nuevo, dice) = AlarmasDeTareas.sono(doc, LocalDateTime.of(2026, 10, 17, 17, 0))!!
        assertEquals("llamar ⏫ ➕ 2026-10-17", Tareas.leer(nuevo).single().texto)
        assertEquals("Muy urgente · llamar", dice)
    }

    @Test fun `la lista y el widget ensenan el titulo limpio`() {
        val f = TodasLasTareas.filasDe("- [ ] llamar al banco 🔼 #casa #obra 💬 cierran a las 3 ⏰ 2026-10-17 17:00 ➕ 2026-10-17").single()
        assertEquals("llamar al banco", f.marcas.titulo)
        assertEquals(Urgencia.URGENTE, f.marcas.urgencia)
        assertEquals(listOf("casa", "obra"), f.marcas.etiquetas)
        assertEquals("cierran a las 3", f.marcas.nota)
        assertEquals(LocalDate.of(2026, 10, 17), f.creada)
        assertEquals("🟠 llamar al banco #casa #obra", LogicaDeLosWidgets.textoDeFila(f.texto))
        assertEquals("🔴 pan", LogicaDeLosWidgets.textoDeFila("pan ⏫"))
        assertEquals("pan", LogicaDeLosWidgets.textoDeFila("pan"))
        assertEquals(LogicaDeLosWidgets.SOLO_IMAGEN, LogicaDeLosWidgets.textoDeFila(""))
    }
}
