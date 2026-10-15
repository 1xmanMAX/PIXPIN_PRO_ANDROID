package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las pruebas de `tareas.rs` del PC, portadas tal cual (miércoles 14-oct-2026, 10:30). */
class TareasIaTest {
    private val ahora = Momento(Fechas.diasDe(2026, 10, 14), 630)
    private fun t(i: Int, texto: String, hecha: Boolean, vence: Pair<Long, Int>?) =
        Tareas.VistaTarea(i, texto, hecha, vence?.let { Momento(ahora.dia + it.first, it.second) })

    private fun lista(p: String, chat: String, g: Boolean, codigo: String, titulo: String, tareas: List<Tareas.VistaTarea>, cuando: Long = 0) =
        Tareas.VistaLista(p, chat, g, codigo, titulo, cuando, tareas)

    private val fuente = Tareas.Fijas(
        listOf(
            lista("g", "Mensajes guardados", true, "inbox", "Inbox", listOf(t(0, "comprar pan", false, null), t(1, "llamar al banco", false, -1L to 600), t(2, "pagar la luz", true, null))),
            lista("casa", "Casa Lima", false, "obra", "Obra", listOf(t(0, "revisar encofrado", false, 1L to 540), t(1, "pedir cemento", true, null))),
            lista("norte", "Obra Norte", false, "compras", "Compras", listOf(t(0, "comprar clavos", false, 20L to 540)))
        ),
        listOf(Tareas.Chat("g", "Mensajes guardados", true), Tareas.Chat("casa", "Casa Lima", false), Tareas.Chat("norte", "Obra Norte", false)),
        ahora
    )

    private fun accion(n: String, a: String) = Acciones.validar(Acciones.porNombre(n)!!, Json.parseToJsonElement(a) as JsonObject)
    private fun pasos(n: String, a: String, f: Tareas.Fuente = fuente) = Tareas.resolver(accion(n, a), f)
    private fun error(n: String, a: String): String = try { pasos(n, a); throw AssertionError("tenía que fallar") } catch (e: Tareas.NoCasa) { e.message!! }

    @Test fun `se parecen como se espera`() {
        assertEquals(100, Tareas.parecido("comprar pan", "Comprar pan"))
        assertEquals(85, Tareas.parecido("pan", "comprar pan"))
        assertEquals(70, Tareas.parecido("la tarea de revisar el encofrado ya", "revisar encofrado"))
        assertTrue(Tareas.parecido("llama al banco", "llamar al banco") > 0)
        assertEquals(0, Tareas.parecido("pintar la luna", "comprar pan"))
    }

    @Test fun `anadir con lista fecha y chat`() {
        val p = pasos("anadir_tarea", """{"texto":"revisar planos","fecha":"mañana a las 10"}""")
        assertEquals(JsonPrimitive("revisar planos ⏰ 2026-10-15 10:00"), p.pedidos[0]["texto"])
        assertNull(p.pedidos[0]["codigo"])
        assertEquals("Apuntada «revisar planos» para mañana 10:00.", p.hecho)
        val q = pasos("anadir_tarea", """{"texto":"tornillos","lista":"compras"}""")
        assertEquals(JsonPrimitive("compras"), q.pedidos[0]["codigo"]); assertEquals(JsonPrimitive("norte"), q.pedidos[0]["proyecto"])
        assertEquals("Apuntada «tornillos» en «Compras (Obra Norte)».", q.hecho)
        assertEquals(JsonPrimitive("casa"), pasos("lista_nueva", """{"titulo":"Acabados","chat":"casa lima"}""").pedidos[0]["proyecto"])
    }

    @Test fun `marcar mover editar y quitar por su nombre`() {
        val p = pasos("marcar_tarea", """{"tarea":"revisar el encofrado"}""")
        assertEquals("""{"pixpin":1,"accion":"marcar_tarea","hecha":true,"codigo":"obra","proyecto":"casa","indice":0}""", p.pedidos[0].toString())
        assertEquals("«revisar encofrado» marcada como hecha.", p.hecho)
        val q = pasos("marcar_tarea", """{"tarea":"pedir cemento","hecha":false}""")
        assertEquals(JsonPrimitive(1), q.pedidos[0]["indice"]); assertTrue(q.hecho.endsWith("pendiente."))
        val m = pasos("mover_tarea", """{"tarea":"comprar pan","a_lista":"Compras"}""")
        assertEquals(JsonPrimitive("compras"), m.pedidos[0]["a_codigo"]); assertEquals(JsonPrimitive("norte"), m.pedidos[0]["a_proyecto"])
        val e = pasos("editar_tarea", """{"tarea":"llamar al banco","texto":"llamar al banco y al seguro"}""")
        assertEquals(JsonPrimitive("corregir_tarea"), e.pedidos[0]["accion"])
        assertEquals(JsonPrimitive("llamar al banco y al seguro ⏰ 2026-10-13 10:00"), e.pedidos[0]["texto"])
        assertEquals(JsonPrimitive("comprar pan ⏰ 2026-10-16 09:00"), pasos("editar_tarea", """{"tarea":"comprar pan","fecha":"el viernes"}""").pedidos[0]["texto"])
        assertEquals("¿Quito la tarea «comprar pan» de «Inbox»?", pasos("quitar_tarea", """{"tarea":"pan"}""").confirmar)
        assertTrue(pasos("borrar_lista", """{"lista":"obra"}""").confirmar!!.contains("«Obra (Casa Lima)»"))
    }

    @Test fun `varias tareas de un acta en una lista nueva`() {
        val p = pasos("anadir_tareas", """{"tareas":["Enviar presupuesto el viernes","Revisar planos"," "],"lista_nueva":"Acta 14-oct","chat":"Casa Lima"}""")
        assertEquals(3, p.pedidos.size)
        assertEquals("""{"pixpin":1,"accion":"lista_nueva","titulo":"Acta 14-oct","proyecto":"casa"}""", p.pedidos[0].toString())
        assertEquals(JsonPrimitive("Enviar presupuesto ⏰ 2026-10-16 09:00"), p.pedidos[1]["texto"])
        assertEquals(JsonPrimitive("Acta 14-oct"), p.pedidos[2]["__en_lista_nueva"])
        assertEquals("Apuntadas 2 tareas en «Acta 14-oct».", p.hecho)
        val a = accion("anadir_tareas", """{"tareas":["a","b"],"lista_nueva":"Acta"}""")
        val mandados = mutableListOf<JsonObject>()
        val r = runCatching { Tareas.hacer(a, fuente, { true }) { mandados += it } }
        assertTrue(r.exceptionOrNull()!!.message!!.contains("lista nueva «Acta»"))
        assertEquals(1, mandados.size)
        val conActa = fuente.copy(listas = fuente.listas + lista("g", "Mensajes guardados", true, "acta", "Acta", emptyList(), 5))
        mandados.clear()
        Tareas.hacer(a, conActa, { true }) { mandados += it }
        assertEquals(JsonPrimitive("acta"), mandados[1]["codigo"]); assertNull(mandados[1]["__en_lista_nueva"])
    }

    @Test fun `lo que no casa vuelve con el motivo`() {
        error("marcar_tarea", """{"tarea":"pintar la luna"}""").let { assertTrue(it, it.contains("No encuentro") && it.contains("comprar pan")) }
        assertTrue(error("anadir_tarea", """{"texto":"pagar alquiler","fecha":"el 31 de febrero"}""").contains("no existe"))
        assertTrue(error("anadir_tarea", """{"texto":"x","lista":"Compras","chat":"Casa Lima"}""").contains("esta en el chat «Obra Norte»"))
        error("anadir_tarea", """{"texto":"x","lista":"Ferreteria"}""").let { assertTrue(it.contains("No hay ninguna lista") && it.contains("Compras")) }
        assertTrue(error("mover_tarea", """{"tarea":"comprar clavos","a_lista":"compras"}""").contains("ya esta"))
        assertTrue(error("quitar_tarea", """{"tarea":"comprar"}""").contains("varias"))
        assertTrue(error("editar_tarea", """{"tarea":"comprar pan"}""").contains("texto nuevo"))
    }

    @Test fun `un chat inventado o una lista que es la tarea se entienden`() {
        assertTrue(pasos("borrar_lista", """{"lista":"Compras","chat":"PixPin"}""").confirmar!!.contains("«Compras (Obra Norte)»"))
        val p = pasos("marcar_tarea", """{"tarea":"táchalo de mis pendientes","lista":"encofrado"}""")
        assertEquals(JsonPrimitive("obra"), p.pedidos[0]["codigo"]); assertEquals(JsonPrimitive(0), p.pedidos[0]["indice"])
        error("anadir_tarea", """{"texto":"x","lista":"Compras","chat":"Casa Lima"}""")
        error("borrar_lista", """{"lista":"encofrado"}""")
    }

    @Test fun `lo que borra no sale sin el si`() {
        var n = 0
        assertTrue(runCatching { Tareas.hacer(accion("quitar_tarea", """{"tarea":"comprar pan"}"""), fuente, { false }) { n++ } }.isFailure)
        assertEquals(0, n)
    }

    @Test fun `la consulta es una lista clara`() {
        fun c(a: String) = Tareas.consultar(accion("consultar_tareas", a).porResolver, fuente)
        val s = c("{}")
        assertTrue(s, s.startsWith("Tareas pendientes (4):"))
        assertTrue(s, s.contains("Inbox:\n  ☐ llamar al banco — venció ayer 10:00\n  ☐ comprar pan"))
        assertTrue(s, s.contains("Obra (Casa Lima):\n  ☐ revisar encofrado — mañana 09:00"))
        assertEquals("Tareas vencidas de esta semana (1):\nInbox:\n  ☐ llamar al banco — venció ayer 10:00", c("""{"estado":"vencidas","periodo":"semana"}"""))
        c("""{"estado":"hechas","chat":"Casa Lima"}""").let { assertTrue(it, it.contains("pedir cemento") && !it.contains("pagar la luz")) }
        assertEquals("No tienes tareas pendientes para hoy.", c("""{"periodo":"hoy"}"""))
        assertTrue(c("""{"chat":"Casa Lima","buscar":"Casa Lima"}""").contains("revisar encofrado"))
        assertTrue(c("""{"periodo":"mes"}""").contains("revisar encofrado"))
        assertTrue(!c("""{"periodo":"mes"}""").contains("clavos"))
        assertTrue(runCatching { c("""{"lista":"cocina"}""") }.isFailure)
    }
}
