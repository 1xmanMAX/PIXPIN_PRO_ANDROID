package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **Dictar al chat y repasar en voz alta** (29-oct-2026): las pruebas de `dictado.rs`, `repaso.rs`,
 * `vivo::clave` y las nuevas de `preguntas.rs` del PC (`origin/main` `50e87978`), y cada una con
 * respuestas grabadas (nada sale a la red).
 */
class DictadoYRepasoTest {

    private fun cliente(t: Grabado) = Cliente("k", t, cache = Cache(), registro = {}, dormir = {})

    private fun f(dice: String) = DictadoAlChat.Frase(0, "Ana", dice)

    // --- preguntas.rs -------------------------------------------------------------------------

    @Test
    fun `las preguntas de los temas son tres por tema con el tema dentro`() {
        val t = Preguntas.Temas.preguntas(listOf("t1" to "Presupuesto", "t2" to "Viaje"))
        assertEquals(6, t.size)
        for ((n, p) in t) {
            val texto = p.aJson().toString()
            assertTrue(n, texto.contains("`conversacion"))
            assertTrue(texto.contains("Presupuesto") || texto.contains("Viaje"))
        }
        assertEquals(listOf("tratado_t1", "cerrado_t1", "ahora_t1"), t.take(3).map { it.first })
        assertTrue(t[2].second.aJson().toString().contains("`conversacion.ahora`"))
    }

    @Test
    fun `las del dictado y el repaso nombran su campo y llevan dentro lo suyo`() {
        val v = Preguntas.Vivo
        for (p in listOf(v.dictado(), v.dictadoChatDicho(), v.dictadoChat(listOf("obra" to "Obra")), v.dictadoQue(listOf("a" to "b")))) {
            assertTrue(p.aJson().toString().contains('`') || p is Pregunta.Choice)
        }
        val chat = v.dictadoChat(listOf("obra_calle_lima" to "Obra Calle Lima")) as Pregunta.Choice
        assertEquals(listOf("obra_calle_lima" to "The chat «Obra Calle Lima»."), chat.opciones)
        assertEquals("cubre_p1", Preguntas.Repaso.cubre("p1"))
        assertEquals("contradice_p1", Preguntas.Repaso.contradice("p1"))
        val n = Preguntas.Repaso.explica("Usa luz") as Pregunta.Noul
        assertEquals("Does `explicacion` explain the key point «Usa luz»?", n.instrucciones)
        assertEquals(12, Preguntas.Repaso.POR_PETICION)
    }

    // --- vivo::clave --------------------------------------------------------------------------

    @Test
    fun `las claves de las opciones`() {
        assertEquals("cotizacion_del_viernes", DictadoAlChat.clave("Cotización del viernes"))
        assertEquals("ana", DictadoAlChat.clave("  ¿Ana?  "))
        assertEquals("x", DictadoAlChat.clave("¡!"))
        val v = DictadoAlChat.claves(listOf("Ana", "ana", "Todos"))
        assertEquals("ana", v[0].first)
        assertEquals("ana_2", v[1].first)
        assertEquals("«todos» es la opción de todos", "todos_2", v[2].first)
        assertEquals("obra_calle_lima", DictadoAlChat.clave("Obra Calle Lima"))
        assertEquals(60, DictadoAlChat.clave("a".repeat(80)).length)
    }

    // --- dictado.rs ---------------------------------------------------------------------------

    @Test
    fun `los textos que se podrian anotar`() {
        val antes = listOf(f("el cemento llega el jueves a las 8"))
        assertEquals(
            listOf(Preguntas.Vivo.LA_ANTERIOR to "el cemento llega el jueves a las 8"),
            DictadoAlChat.textos(f("anota esto en el chat de la obra"), antes)
        )
        assertEquals(
            listOf(Preguntas.Vivo.LO_QUE_SIGUE to "faltan tornillos"),
            DictadoAlChat.textos(f("pon en el grupo de compras que faltan tornillos."), emptyList())
        )
        val t = DictadoAlChat.textos(f("Anota en el chat de la obra: Llega la grúa el lunes"), antes)
        assertEquals("Llega la grúa el lunes", t[0].second)
        assertEquals(2, t.size)
        assertTrue(DictadoAlChat.textos(f("anótalo"), emptyList()).isEmpty())
    }

    @Test
    fun `la tabla del dictado`() {
        val chats = listOf("Obra Calle Lima", "Compras")
        fun d(p: Double, dicho: Double) = DictadoAlChat.Senal(p, dicho, 0, "faltan tornillos")
        val fr = f("x")
        val r1 = DictadoAlChat.decidir(fr, d(0.9, 0.9), chats) as DictadoAlChat.Resultado.Anotar
        assertEquals("Obra Calle Lima", r1.nota.chat)
        val r2 = DictadoAlChat.decidir(fr, d(0.9, 0.2), chats) as DictadoAlChat.Resultado.Anotar
        assertNull(r2.nota.chat)
        assertEquals(DictadoAlChat.Resultado.Dudoso(0.5), DictadoAlChat.decidir(fr, d(0.5, 0.9), chats))
        assertEquals(DictadoAlChat.Resultado.Nada, DictadoAlChat.decidir(fr, d(0.1, 0.9), chats))
        assertEquals(DictadoAlChat.Resultado.SinTexto, DictadoAlChat.decidir(fr, d(0.9, 0.9).copy(texto = null), chats))
        assertEquals(DictadoAlChat.Resultado.Nada, DictadoAlChat.decidir(fr, null, chats))
        // Los umbrales son inclusivos: 0.7 propone, 0.3 es «no».
        assertTrue(DictadoAlChat.decidir(fr, d(0.7, 0.7), chats) is DictadoAlChat.Resultado.Anotar)
        assertEquals(DictadoAlChat.Resultado.Nada, DictadoAlChat.decidir(fr, d(0.3, 0.9), chats))
    }

    @Test
    fun `el estado lleva las dos de antes y la frase y nada mas`() {
        val antes = listOf(f("uno"), f("dos"), f("tres"))
        val e = DictadoAlChat.estado(antes, f("anota esto en el chat de la obra"))
        assertEquals(setOf("antes", "frase"), e.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("Ana: dos"), JsonPrimitive("Ana: tres"))), e["antes"])
        assertEquals("Ana", e["frase"]!!.jsonObject["quien"]!!.jsonPrimitive.content)
        assertTrue(DictadoAlChat.estado(emptyList(), f("hola que tal")).keys == setOf("frase"))
        // Las preguntas: las tres, y la de qué solo con dos textos.
        val p = DictadoAlChat.preparar(antes, f("anota en el chat de la obra: faltan tornillos"), listOf("Obra", "Compras"))
        assertEquals(setOf("dictado", "dictado_chat", "dictado_chat_dicho", "dictado_que"), p.peticion.preguntas.keys)
        val p1 = DictadoAlChat.preparar(emptyList(), f("anota en el chat de la obra que faltan tornillos"), listOf("Obra"))
        assertEquals(setOf("dictado", "dictado_chat", "dictado_chat_dicho"), p1.peticion.preguntas.keys)
        assertTrue(DictadoAlChat.preparar(emptyList(), f("x y z"), emptyList()).peticion.preguntas.isEmpty())
    }

    private val respuestaDelDictado = """{"model":"jev-1.14.0","answers":{
        "dictado":{"type":"noul","noul":0.95},
        "dictado_chat_dicho":{"type":"noul","noul":0.9},
        "dictado_chat":{"type":"choice","choice":"obra_calle_lima","confidence":0.6,
            "probabilities":{"obra_calle_lima":0.8,"compras":0.2}}},
        "usage":{"input_tokens":180,"output_tokens":9}}"""

    @Test
    fun `el dictado con respuestas grabadas`() {
        val t = Grabado(listOf(Grabado.ok(respuestaDelDictado)))
        val frase = DictadoAlChat.Frase(0, "Yo", "Anota en el chat de la obra que faltan tornillos")
        val s = DictadoAlChat.preguntar(cliente(t), emptyList(), frase, listOf("Obra Calle Lima", "Compras"))
        assertEquals(DictadoAlChat.Senal(0.95, 0.9, 0, "faltan tornillos"), s)
        val cuerpo = Json.parseToJsonElement(String(t.pedidos[0].cuerpo)).jsonObject
        assertEquals("Anota en el chat de la obra que faltan tornillos", cuerpo["state"]!!.jsonObject["frase"]!!.jsonObject["dice"]!!.jsonPrimitive.content)
        val opciones = cuerpo["questions"]!!.jsonObject["dictado_chat"]!!.jsonObject["criteria"]!!.jsonObject
        assertEquals(listOf("obra_calle_lima", "compras"), opciones.keys.toList())
        // Una opción que no se pidió, o una respuesta que falta: error, nunca un «no».
        val rara = respuestaDelDictado.replace("\"choice\":\"obra_calle_lima\"", "\"choice\":\"otra\"")
        try {
            DictadoAlChat.preguntar(cliente(Grabado(listOf(Grabado.ok(rara)))), emptyList(), frase, listOf("Obra Calle Lima", "Compras"))
            fail("una opción que no se pidió")
        } catch (_: ErrorJev.Formato) {}
        try {
            DictadoAlChat.preguntar(cliente(Grabado(listOf(Grabado.ok(Grabado.nouls(listOf("dictado" to 0.9)))))), emptyList(), frase, listOf("Obra"))
            fail("faltan respuestas")
        } catch (_: ErrorJev.FaltaRespuesta) {}
        try {
            DictadoAlChat.preguntar(cliente(Grabado(listOf(Grabado.caida()))), emptyList(), frase, listOf("Obra"))
            fail("sin red")
        } catch (_: ErrorJev.Red) {}
    }

    // --- repaso.rs ----------------------------------------------------------------------------

    private fun s(cubre: Double, contradice: Double) = RepasoOral.SenalPunto(cubre, contradice)

    @Test
    fun `la tabla de un punto`() {
        assertEquals(RepasoOral.Estado.MAL, RepasoOral.leerPunto(s(0.9, 0.8)))
        assertEquals(RepasoOral.Estado.CUBIERTO, RepasoOral.leerPunto(s(0.9, 0.1)))
        assertEquals(RepasoOral.Estado.FALTA, RepasoOral.leerPunto(s(0.1, 0.1)))
        assertEquals(RepasoOral.Estado.DUDOSO, RepasoOral.leerPunto(s(0.5, 0.5)))
    }

    @Test
    fun `entre peticiones lo mal se queda`() {
        val r = RepasoOral("Fotosíntesis", listOf("a", "b", "c"))
        val c = r.decidir(listOf("p1" to s(0.9, 0.0), "p2" to s(0.5, 0.0), "p3" to s(0.0, 0.0)))
        assertEquals(2, c.size)
        // Lo cubierto no vuelve a faltar; lo dudoso sube; lo mal se queda.
        r.decidir(listOf("p1" to s(0.1, 0.0), "p2" to s(0.9, 0.0), "p3" to s(0.9, 0.9)))
        assertEquals(listOf(RepasoOral.Estado.CUBIERTO, RepasoOral.Estado.CUBIERTO, RepasoOral.Estado.MAL), r.estados)
        r.decidir(listOf("p3" to s(0.9, 0.0)))
        assertEquals(RepasoOral.Estado.MAL, r.estados[2])
        assertEquals(2, r.porPreguntar().size)
        // Un id que no es de ningún punto no hace nada.
        assertTrue(r.decidir(listOf("p9" to s(0.9, 0.9))).isEmpty())
    }

    @Test
    fun `el repaso pregunta con respuestas grabadas`() {
        val t = Grabado(listOf(Grabado.ok(Grabado.nouls(listOf(
            "cubre_p1" to 0.9, "contradice_p1" to 0.05, "cubre_p2" to 0.2, "contradice_p2" to 0.8
        )))))
        val r = RepasoOral("Fotosíntesis", listOf("Produce oxígeno", "Usa luz"))
        r.oir("La planta suelta oxígeno.")
        r.oir("Y lo hace de noche, sin luz.")
        val sen = RepasoOral.preguntar(cliente(t), r.clase, r.explicacion, r.porPreguntar())
        r.decidir(sen)
        assertEquals(listOf(RepasoOral.Estado.CUBIERTO, RepasoOral.Estado.MAL), r.estados)
        val cuerpo = String(t.pedidos[0].cuerpo)
        assertTrue(cuerpo.contains("suelta oxígeno. Y lo hace de noche"))
        val e = Json.parseToJsonElement(cuerpo).jsonObject
        assertEquals(setOf("clase", "explicacion"), e["state"]!!.jsonObject.keys)
        assertEquals(setOf("cubre_p1", "contradice_p1", "cubre_p2", "contradice_p2"), e["questions"]!!.jsonObject.keys)
        // Lo que falta: error.
        val t2 = Grabado(listOf(Grabado.ok(Grabado.nouls(listOf("cubre_p1" to 0.9)))))
        try {
            RepasoOral.preguntar(cliente(t2), "x", "y", r.puntos)
            fail("falta una respuesta")
        } catch (_: ErrorJev) {}
    }

    @Test
    fun `lo explicado va por el final y los puntos como mucho doce`() {
        val largo = "a".repeat(RepasoOral.LARGO + 50) + "fin"
        val p = RepasoOral.peticion("Clase", largo, (1..15).map { "p$it" to "punto $it" })
        val ex = p.estado["explicacion"]!!.jsonPrimitive.content
        assertTrue(ex.startsWith("…") && ex.endsWith("fin"))
        assertEquals(RepasoOral.LARGO + 1, ex.codePointCount(0, ex.length))
        assertEquals(24, p.preguntas.size)
        assertEquals(setOf("p1", "p2", "p3"), RepasoOral("c", listOf("a", "b", "c")).puntos.map { it.first }.toSet())
    }
}
