package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los temas de la reunión en vivo** (29-oct-2026): las pruebas de `temas.rs` de `pixpin-typesafe` del
 * PC (la tabla, dos a medias solo con algo nuevo, lo recordado que caduca, el rollo y su ventana de 40 s,
 * el ritmo con un reloj de mentira, el estado, una petición por ventana con respuestas grabadas y los
 * fallos como errores). Nunca sale nada a la red.
 */
class TemasEnVivoTest {

    private fun s(tratado: Double, cerrado: Double, ahora: Double) = Temas.Senal(tratado, cerrado, ahora)

    private fun una(id: String, x: Temas.Senal) = listOf(id to x)

    /** Una ventana de mentira: lo nuevo empieza en [msAhora], la conversación 10 s antes, y acaba 3 s después. */
    private fun v(msAhora: Long) = Temas.Ventana(null, "x", msAhora, msAhora - 10_000, msAhora + 3_000, 0, 0)

    private fun dicho(ms: Long, texto: String): Temas.Dicho {
        val palabras = texto.split(Regex("\\s+")).count { it.isNotEmpty() }
        return Temas.Dicho(ms, ms + 400L * palabras, texto, ms + 400L * palabras)
    }

    @Test
    fun `la tabla de lo tratado`() {
        val g = Temas.Seguimiento()
        assertEquals(listOf(Temas.Tratado("t1", 45_000, 0.86)), g.decidir(v(45_000), una("t1", s(0.86, 0.2, 0.9))))
        // Si lo nuevo es de otra cosa, en el principio de la conversación.
        assertEquals(35_000L, g.decidir(v(45_000), una("t1", s(0.8, 0.2, 0.1)))[0].ms)
        // El umbral es inclusivo.
        assertEquals(1, g.decidir(v(0), una("t1", s(0.7, 0.0, 0.0))).size)
        // A medias pero cerrado: se trató.
        val h = g.decidir(v(60_000), una("t2", s(0.55, 0.9, 0.6)))
        assertEquals(60_000L, h[0].ms)
        assertEquals(0.9, h[0].p, 1e-9)
        // Cerrado sin tratar: no.
        assertTrue(g.decidir(v(0), una("t3", s(0.2, 0.95, 0.4))).isEmpty())
        // Solo nombrado o dejado para luego: no.
        assertTrue(g.decidir(v(0), una("t3", s(0.1, 0.05, 0.1))).isEmpty())
    }

    @Test
    fun `dos a medias cuentan solo si la segunda trae algo nuevo`() {
        val g = Temas.Seguimiento()
        assertTrue(g.decidir(v(150_000), una("t1", s(0.6, 0.3, 0.7))).isEmpty())
        // La misma frase leída otra vez (lo nuevo es de otra cosa): no.
        assertTrue(g.decidir(v(153_000), una("t1", s(0.62, 0.3, 0.1))).isEmpty())
        // Algo nuevo del tema: sí, en el minuto de la primera.
        assertEquals(listOf(Temas.Tratado("t1", 150_000, 0.65)), g.decidir(v(156_000), una("t1", s(0.65, 0.4, 0.6))))
        // Un «no» entre medias rompe la racha.
        assertTrue(g.decidir(v(0), una("t3", s(0.6, 0.0, 0.6))).isEmpty())
        assertTrue(g.decidir(v(3_000), una("t3", s(0.2, 0.0, 0.0))).isEmpty())
        assertTrue(g.decidir(v(6_000), una("t3", s(0.6, 0.0, 0.6))).isEmpty())
        // Lo recordado caduca.
        assertTrue(g.decidir(v(0), una("t5", s(0.6, 0.0, 0.6))).isEmpty())
        assertTrue(g.decidir(v(Temas.VIGENCIA_MS + 1), una("t5", s(0.6, 0.0, 0.6))).isEmpty())
        // Olvidado (se marcó a mano): no cuenta.
        assertTrue(g.decidir(v(0), una("t4", s(0.6, 0.0, 0.6))).isEmpty())
        g.olvidar("t4")
        assertTrue(g.decidir(v(3_000), una("t4", s(0.6, 0.0, 0.6))).isEmpty())
        // Recordado y luego claro: el minuto de cuando empezó.
        assertTrue(g.decidir(v(10_000), una("t6", s(0.6, 0.0, 0.6))).isEmpty())
        assertEquals(10_000L, g.decidir(v(14_000), una("t6", s(0.9, 0.0, 0.9)))[0].ms)
    }

    @Test
    fun `el rollo ensena lo nuevo y los ultimos cuarenta segundos`() {
        val r = Temas.Rollo()
        assertNull(r.ventana())
        r.meter(dicho(0, "buenos días a todos"))
        r.meter(dicho(2_000, ""))
        r.meter(dicho(3_000, "empezamos con la cotización del viernes"))
        assertTrue(r.hayNuevo())
        var w = r.ventana()!!
        assertNull(w.antes)
        assertEquals("buenos días a todos empezamos con la cotización del viernes", w.ahora)
        assertEquals(0L to 0L, w.msAhora to w.msDesde)
        r.mirado(w)
        assertFalse(r.hayNuevo())
        assertNull(r.ventana())
        // Lo que llega después: nuevo, con lo de antes de contexto.
        r.meter(dicho(6_000, "eh"))
        assertFalse("una palabra sola espera", r.hayNuevo())
        r.meter(dicho(7_000, "el proveedor sube un tres por ciento"))
        w = r.ventana()!!
        assertEquals("eh el proveedor sube un tres por ciento", w.ahora)
        assertEquals(6_000L, w.msAhora)
        assertEquals(0L, w.msDesde)
        assertEquals("buenos días a todos empezamos con la cotización del viernes", w.antes)
        assertEquals(6_400L, w.relojPrimera)
        assertEquals(9_800L, w.relojUltima)
        // Lo que llega con la petición en vuelo no se da por mirado.
        r.meter(dicho(10_000, "y lo aceptamos"))
        r.mirado(w)
        assertEquals("y lo aceptamos", r.ventana()!!.ahora)
        // Lo de hace más de 40 s ya no va.
        r.mirado(r.ventana()!!)
        r.meter(dicho(60_000, "pasamos al paro de transportistas"))
        w = r.ventana()!!
        assertNull(w.antes)
        assertEquals(60_000L, w.msDesde)
        r.mirado(w)
        assertEquals("lo viejo y mirado se tira", 1, r.dichos.size)
    }

    @Test
    fun `el rollo recorta por delante`() {
        val r = Temas.Rollo()
        val largo = "palabra ".repeat(200)
        for (i in 0 until 5) r.meter(dicho(i * 1_000L, largo))
        val w = r.ventana()!!
        assertTrue(w.ahora.length <= 1200 + 1)
        assertEquals(4_000L, w.msAhora)
        val antes = w.antes!!
        assertTrue(antes.startsWith("…"))
        assertTrue(antes.length <= 2400 + 1)
    }

    @Test
    fun `el ritmo junta lo que llega y espera entre peticiones`() {
        val t = Temas.Ritmo()
        assertEquals(Temas.Cuando.Nada, t.cuando(0, false))
        assertEquals(Temas.Cuando.Ya, t.cuando(0, true))
        t.sale(0)
        // En vuelo: lo nuevo espera (se junta).
        assertEquals(Temas.Cuando.Nada, t.cuando(400, true))
        t.vuelve(600, true)
        // Volvió pronto: hasta 1,5 s después de la anterior.
        assertEquals(Temas.Cuando.Esperar(900), t.cuando(600, true))
        assertEquals(Temas.Cuando.Ya, t.cuando(1_500, true))
        t.sale(1_500)
        // Volvió tarde: ya mismo.
        t.vuelve(4_000, true)
        assertEquals(Temas.Cuando.Ya, t.cuando(4_000, true))
        t.sale(4_000)
        // Un fallo: 5 s.
        t.vuelve(4_500, false)
        assertEquals(Temas.Cuando.Esperar(5_000), t.cuando(4_500, true))
        assertEquals(Temas.Cuando.Ya, t.cuando(9_500, true))
    }

    private fun pendientes() = listOf(Temas.Pendiente("t1", "Presupuesto 2027"), Temas.Pendiente("t3", "Viaje a Lima"))

    @Test
    fun `el estado y la peticion`() {
        val agenda = listOf("Presupuesto 2027", "Nuevo becario", "Viaje a Lima")
        var w = v(0).copy(ahora = "bueno, el presupuesto…")
        var e = Temas.estado("Comité", agenda, w)
        assertEquals(JsonPrimitive("Comité"), e["reunion"])
        assertEquals(3, e["agenda"]!!.jsonArray.size)
        assertEquals(JsonPrimitive("bueno, el presupuesto…"), e["conversacion"]!!.jsonObject["ahora"])
        // Sin nada de antes el campo no va (vacío se leería como señal).
        assertFalse("antes" in e["conversacion"]!!.jsonObject)
        w = w.copy(antes = "antes")
        e = Temas.estado("", agenda, w)
        assertFalse("reunion" in e)
        assertEquals(JsonPrimitive("antes"), e["conversacion"]!!.jsonObject["antes"])
        val p = Temas.peticion("Comité", agenda, w, pendientes())
        assertEquals(6, p.preguntas.size)
        assertTrue("ahora_t3" in p.preguntas)
        // Como mucho POR_PETICION temas.
        val muchos = (0 until 30).map { Temas.Pendiente("t$it", "tema $it") }
        assertEquals(3 * Preguntas.Temas.POR_PETICION, Temas.peticion("", emptyList(), w, muchos).preguntas.size)
    }

    @Test
    fun `las preguntas de los temas llevan el tema y nombran la conversacion`() {
        val t = Preguntas.Temas.preguntas(listOf("t1" to "Presupuesto", "t2" to "Viaje"))
        assertEquals(6, t.size)
        for ((_, p) in t) {
            val texto = p.aJson().toString()
            assertTrue(texto.contains("`conversacion"))
            assertTrue(texto.contains("Presupuesto") || texto.contains("Viaje"))
        }
        assertEquals(listOf("tratado_t1", "cerrado_t1", "ahora_t1"), t.take(3).map { it.first })
        assertTrue(t[2].second.aJson().toString().contains("`conversacion.ahora`"))
    }

    @Test
    fun `pregunta con respuestas grabadas una sola peticion por ventana`() {
        val t = Grabado(listOf(Grabado.ok(Grabado.nouls(listOf(
            "tratado_t1" to 0.91, "cerrado_t1" to 0.8, "ahora_t1" to 0.9,
            "tratado_t3" to 0.12, "cerrado_t3" to 0.05, "ahora_t3" to 0.2
        )))))
        val c = Cliente("k", t, Cache(), registro = {})
        val r = Temas.Rollo()
        r.meter(dicho(12_000, "El presupuesto queda en cuarenta mil, lo aprobamos. Del viaje hablamos luego."))
        val w = r.ventana()!!
        val (senales, modelo) = Temas.preguntar(c, "Comité", emptyList(), w, pendientes())
        assertEquals("una sola petición por ventana", 1, t.llamadas)
        assertEquals("jev-1.14.0", modelo)
        val cuerpo = Json.parseToJsonElement(String(t.pedidos[0].cuerpo)).toString()
        assertTrue(cuerpo, cuerpo.contains("cuarenta mil"))
        val hechos = Temas.Seguimiento().decidir(w, senales)
        assertEquals(1, hechos.size)
        assertEquals("t1" to 12_000L, hechos[0].id to hechos[0].ms)
    }

    @Test
    fun `un fallo o una respuesta que falta es un error`() {
        val w = v(0)
        val caida = Cliente("k", Grabado(listOf(Grabado.caida())), Cache(), registro = {})
        assertTrue(runCatching { Temas.preguntar(caida, "", emptyList(), w, pendientes()) }.exceptionOrNull() is ErrorJev.Red)
        val corta = Cliente("k", Grabado(listOf(Grabado.ok(Grabado.nouls(listOf("tratado_t1" to 0.9, "cerrado_t1" to 0.9))))), Cache(), registro = {})
        assertTrue(runCatching { Temas.preguntar(corta, "", emptyList(), w, pendientes()) }.exceptionOrNull() is ErrorJev.FaltaRespuesta)
        // Sin clave no se llama a nadie.
        val g = Grabado()
        val sin = Cliente("", g, Cache(), registro = {})
        assertTrue(runCatching { Temas.preguntar(sin, "", emptyList(), w, pendientes()) }.exceptionOrNull() is ErrorJev.SinClave)
        assertEquals(0, g.llamadas)
    }
}
