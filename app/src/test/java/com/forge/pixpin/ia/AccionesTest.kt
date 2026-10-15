package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Las pruebas de `acciones.rs` y `acciones_documentos.rs` del PC, portadas con sus textos. */
class AccionesTest {
    private fun args(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun fila(n: String) = Acciones.porNombre(n)!!
    private fun validar(n: String, a: String = "{}") = Acciones.validar(fila(n), args(a))
    private fun error(n: String, a: String): String = try { validar(n, a); fail("tenía que fallar: $a"); "" } catch (e: ErrorHerramienta) { e.message!! }

    @Test fun `los nombres no se repiten`() {
        val nombres = Acciones.TODAS.map { it.herramienta }
        assertEquals(nombres.size, nombres.toSet().size)
        for (a in Acciones.TODAS) {
            Json.parseToJsonElement(a.fijos)
            assertTrue(a.herramienta, a.accion == Acciones.CONSULTA || a.hecho.isNotEmpty())
            a.params.forEach { p -> p.defecto?.let { Json.parseToJsonElement(it) } }
        }
    }

    @Test fun `el esquema sale de la tabla`() {
        val h = Acciones.herramienta(fila("abrir_ventana"))
        assertEquals("[\"cual\"]", h.parametros["required"].toString())
        assertEquals("\"galeria\"", (h.parametros["properties"]!!.jsonObject["cual"]!!.jsonObject["enum"] as JsonArray)[1].toString())
        assertNull(Acciones.herramienta(fila("capturar_pantalla")).parametros["required"])
        val varias = Acciones.herramienta(fila("anadir_tareas"))
        assertEquals("\"string\"", varias.parametros["properties"]!!.jsonObject["tareas"]!!.jsonObject["items"]!!.jsonObject["type"].toString())
    }

    @Test fun `el pedido sale bien armado`() {
        val a = validar("abrir_fichero", """{"ruta":" «C:\\x\\plano.pdf» "}""")
        assertEquals("""{"que":{"tipo":"fichero","ruta":"C:\\x\\plano.pdf"},"pixpin":1,"accion":"abrir"}""", a.pedido.toString())
        assertTrue(a.confirmar == null && a.porResolver.isEmpty())
        assertEquals("""{"modo":"zona","pixpin":1,"accion":"capturar"}""", validar("capturar_pantalla").pedido.toString())
        assertEquals(JsonPrimitive("galeria"), validar("abrir_ventana", """{"cual":"Galería"}""").pedido["cual"])
    }

    @Test fun `las tareas se piden por su nombre`() {
        val a = validar("marcar_tarea", """{"tarea":"comprar pan","lista":"Compras"}""")
        assertEquals("""{"pixpin":1,"accion":"marcar_tarea","hecha":true}""", a.pedido.toString())
        assertEquals(args("""{"tarea":"comprar pan","lista":"Compras"}"""), a.porResolver)
        val b = validar("anadir_tarea", """{"texto":"pan","fecha":"mañana","lista":"  "}""")
        assertEquals(JsonPrimitive("pan"), b.pedido["texto"])
        assertEquals(args("""{"fecha":"mañana"}"""), b.porResolver)
        val c = validar("consultar_tareas")
        assertTrue(c.esConsulta)
        assertEquals(JsonPrimitive("pendientes"), c.porResolver["estado"])
        assertEquals("[\"a\",\"b\"]", validar("anadir_tareas", """{"tareas":["a"," ","b"]}""").porResolver["tareas"].toString())
    }

    @Test fun `lo que borra o sale del aparato pregunta`() {
        assertEquals("¿Borro la lista «Obra» entera, con todas sus tareas? No se puede deshacer.", validar("borrar_lista", """{"lista":"Obra"}""").confirmar)
        val s = validar("sincronizar_movil")
        assertEquals(JsonPrimitive("todos"), s.pedido["aparato"]); assertNotNull(s.confirmar)
        for (n in listOf("quitar_tarea", "borrar_captura", "enviar_al_movil", "crear_automatizacion")) assertNotNull(n, fila(n).confirmar)
    }

    @Test fun `las frases se rellenan sin huecos`() {
        val h = fila("anadir_tarea").hecho
        assertEquals("Apuntada «pan».", Acciones.rellenar(h, listOf("texto" to "pan")))
        assertEquals("Apuntada «pan» en «Compras» para mañana 10:00.",
            Acciones.rellenar(h, listOf("texto" to "pan", "lista" to "Compras", "fecha" to "mañana 10:00")))
        assertEquals("Abriendo .", Acciones.rellenar("Abriendo {ruta}.", emptyList()))
    }

    @Test fun `lo mal puesto se rechaza con el motivo`() {
        assertTrue(error("lista_nueva", "{}").contains("«titulo»"))
        assertTrue(error("abrir_ventana", """{"cual":"cocina"}""").contains("tareas, galeria"))
        error("marcar_tarea", """{"lista":"x"}""")
        error("anadir_tarea", """{"texto":"  "}""")
        error("anadir_tareas", """{"tareas":[" "]}""")
        error("enviar_al_movil", """{"imagenes":[]}""")
    }

    @Test fun `los de documentos usan la validacion comun`() {
        assertEquals(JsonPrimitive("excel"), validar(Acciones.TABLA, """{"destino":"Excel","nombre":"Costos"}""").pedido["destino"])
        assertEquals(JsonPrimitive("tabla"), validar(Acciones.TABLA).pedido["destino"])
        assertEquals("[0.1,0.2,0.3,0.4]", validar(Acciones.ANOTAR, """{"tipo":"resaltar","texto":"JR. ANTISUYO","zona":[[0.1,0.2],[0.3,0.4]]}""").pedido["zona"].toString())
        assertEquals("[0.1,0.2,0.3,0.4]", validar(Acciones.ANOTAR, """{"tipo":"encerrar","zona":"[0.1, 0.2, 0.3, 0.4]"}""").pedido["zona"].toString())
        val b = validar(Acciones.BUSCAR_EN, """{"texto":"JR. ANTISUYO"}""")
        assertEquals(JsonPrimitive(true), b.pedido["abrir"]); assertNull(b.confirmar)
        error(Acciones.ANOTAR, """{"tipo":"pintar"}""")
        error(Acciones.ANOTAR, """{"tipo":"nota","zona":["a"]}""")
        error(Acciones.BUSCAR_EN, "{}")
        error(Acciones.EXPORTAR, """{"formato":"docx"}""")
        error(Acciones.ABRIR, """{"descripcion":"  "}""")
    }

    @Test fun `una pregunta de donde dice trae la busqueda`() {
        val q = Acciones.normalizar("¿Dónde dice JR. ANTISUYO en el plano?")
        assertEquals(listOf(Acciones.BUSCAR_EN), Acciones.paraUnaPregunta(q).map { it.herramienta })
        assertTrue(Acciones.paraUnaPregunta("como hago una captura").isEmpty())
    }

    @Test fun `la ayuda y las figuras se validan como en el PC`() {
        val v = Herramientas.validar(LlamadaCruda("1", Herramientas.AYUDA, """{"q":"donde estan los ajustes"}"""), Herramientas.catalogo())
        assertEquals(Llamada.Ayuda("donde estan los ajustes"), v.llamada)
        fun f(s: String) = Figuras.validar(args(s))
        assertEquals(PedidoFiguras.Guardar("Árbol isométrico"), f("""{"accion": "guardar", "nombre": "«Árbol isométrico»"}"""))
        assertEquals(PedidoFiguras.Poner("arbol"), f("""{"accion": "pon", "nombre": "arbol"}"""))
        assertEquals(PedidoFiguras.Lista, f("""{"accion": "lista"}"""))
        assertEquals(PedidoFiguras.Lista, f("{}"))
        for (mal in listOf("""{"accion": "guardar"}""", """{"accion": "poner", "nombre": "  "}""", """{"accion": "borrar_todo", "nombre": "x"}""", """{"nombre": "x"}"""))
            assertTrue(mal, runCatching { f(mal) }.isFailure)
        val l = listOf("Árbol isométrico", "Casa", "Arbol de navidad", "Norte")
        assertEquals(0, Figuras.buscar(l, "arbol isometrico")); assertEquals(0, Figuras.buscar(l, "el árbol isométrico"))
        assertEquals(1, Figuras.buscar(l, "CASA")); assertEquals(2, Figuras.buscar(l, "arboles de navidad"))
        assertNull(Figuras.buscar(l, "perro")); assertNull(Figuras.buscar(emptyList(), "casa"))
        assertTrue(Figuras.lista(l).contains("4 figuras")); assertTrue(Figuras.lista(emptyList()).contains("no hay"))
    }

    @Test fun `la ayuda contesta lo tipico y no inventa`() {
        assertTrue(Conocimiento.responder("¿Cómo hago una captura de pantalla?").contains("bola"))
        assertTrue(Conocimiento.responder("como sincronizo con el pc").contains("Sincronizar"))
        assertTrue(Conocimiento.responder("donde veo mis tareas pendientes").contains("Inbox"))
        assertTrue(Conocimiento.responder("¿como se hace una tortilla de patatas?").contains("No encuentro"))
        assertTrue(Conocimiento.responder("").contains("No encuentro"))
        assertTrue(Conocimiento.RESUMEN.length < 700)
    }
}
