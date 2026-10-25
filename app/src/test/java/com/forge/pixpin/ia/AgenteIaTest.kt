package com.forge.pixpin.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El turno con un motor de mentira** (pruebas de `agente.rs` portadas): dibujo, sin modelo,
 * herramienta que falla, parar a mitad, bucle cortado a las 4 vueltas, llamada cortada por
 * tokens y coordenadas que se salen un poco.
 */
class AgenteIaTest {

    /** Un ejecutor que apunta lo que le piden. */
    private class Apunta(val fallar: Boolean = false) : Agente.Ejecutor {
        val hechas = mutableListOf<Llamada>()
        override fun ejecutar(llamada: Llamada): Result<String> {
            hechas += llamada
            return if (fallar) Result.failure(IllegalStateException("el lienzo esta cerrado")) else Result.success("hecho")
        }
    }

    private fun historial(pedido: String = "dibuja una casa") =
        mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario(pedido))

    private class Corrida(val final: Agente.Final, val eventos: List<Agente.Evento>, val historial: List<MensajeIa>, val motor: MotorGuionado)

    private fun correr(guion: List<Respuesta>, ej: Agente.Ejecutor, pedido: String = "dibuja una casa", pararEn: ((Agente.Evento) -> Boolean)? = null): Corrida {
        val motor = MotorGuionado(guion)
        val h = historial(pedido)
        val eventos = mutableListOf<Agente.Evento>()
        val f = Agente.turno(motor, h, Herramientas.catalogo(), ej) { e ->
            eventos += e
            if (pararEn?.invoke(e) == true) Seguir.PARAR else Seguir.SI
        }
        return Corrida(f, eventos, h, motor)
    }

    @Test fun `solo texto acaba en una vuelta`() {
        val c = correr(listOf(Respuesta.deTexto("Hola que tal")), Apunta())
        assertEquals("Hola que tal", c.final.texto)
        assertEquals(0, c.final.herramientasHechas)
        assertEquals(Agente.Evento.Texto("Hola "), c.eventos[0])
        assertEquals(Rol.ASISTENTE, c.historial.last().rol)
    }

    @Test fun `llamada buena se ejecuta y el resultado vuelve al modelo`() {
        val ej = Apunta()
        val c = correr(listOf(
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"rectangulo","x":10,"y":10}]}"""),
            Respuesta.deTexto("Listo, dibuje un rectangulo.")
        ), ej)
        assertEquals(1, c.final.herramientasHechas)
        assertEquals("Listo, dibuje un rectangulo.", c.final.texto)
        assertEquals(1, ej.hechas.size)
        assertTrue(c.eventos.any { it is Agente.Evento.Resultado && it.ok })
        val segunda = c.motor.recibido[1].mensajes
        assertEquals(1, segunda[segunda.size - 2].llamadas.size)
        assertEquals(Rol.HERRAMIENTA, segunda.last().rol)
        assertEquals("hecho", segunda.last().textoEntero)
        assertEquals("llamada-dibujar_en_lienzo", segunda.last().idLlamada)
        // La petición lleva las herramientas con sus topes y un tope de tokens que cabe.
        assertTrue(c.motor.recibido[0].herramientas[0].parametros.toString().contains("\"maxItems\":16"))
        assertTrue(c.motor.recibido[0].maxTokens in Agente.MIN_TOKENS..Agente.MAX_TOKENS_CON_HERRAMIENTAS)
        assertEquals(5, c.historial.size)
    }

    @Test fun `llamada mala se reintenta con el motivo del PC`() {
        val ej = Apunta()
        val c = correr(listOf(
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"texto","x":1,"y":1,"texto":"a","tam_letra":900}]}"""),
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"texto","x":1,"y":1,"texto":"a","tam_letra":90}]}"""),
            Respuesta.deTexto("Ya esta.")
        ), ej)
        assertEquals(1, c.final.reintentos)
        assertEquals(1, ej.hechas.size)
        assertEquals("ERROR: elemento 1: «tam_letra» = 900 esta fuera de rango (6 a 200). Corrige los argumentos y vuelve a llamar.",
            c.motor.recibido[1].mensajes.last().textoEntero)
        assertEquals("Ya esta.", c.final.texto)
    }

    @Test fun `una coordenada que se sale un poco se lleva al borde y se dibuja`() {
        val ej = Apunta()
        val c = correr(listOf(
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"rectangulo","x":2010,"y":1}]}"""),
            Respuesta.deTexto("Ya esta.")
        ), ej)
        assertEquals(0, c.final.reintentos)
        assertEquals(2000.0, (ej.hechas.single() as Llamada.Dibujar).figuras[0].x, 0.0)
    }

    @Test fun `un error de validacion de verdad vuelve como ERROR y baja los topes`() {
        val c = correr(listOf(
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"dragon","x":1,"y":1}]}"""),
            Respuesta.deTexto("No puedo.")
        ), Apunta())
        val m = c.motor.recibido[1].mensajes.last()
        assertEquals(Rol.HERRAMIENTA, m.rol)
        assertTrue(m.textoEntero, m.textoEntero.startsWith("ERROR: elemento 1: «tipo» = «dragon» no existe"))
        assertTrue(c.motor.recibido[1].herramientas[0].parametros.toString().contains("\"maxItems\":10"))
        assertTrue(c.eventos.any { it is Agente.Evento.Reintento })
    }

    @Test fun `herramienta que falla se dice al modelo y cuenta como no hecha`() {
        val c = correr(listOf(
            Respuesta.deLlamada("grafica", """{"tipo":"barras","etiquetas":["a"],"valores":[1]}"""),
            Respuesta.deTexto("No pude dibujarla.")
        ), Apunta(fallar = true))
        assertEquals(0, c.final.herramientasHechas)
        assertEquals("ERROR: el lienzo esta cerrado", c.motor.recibido[1].mensajes.last().textoEntero)
        assertTrue(c.eventos.any { it is Agente.Evento.Resultado && !it.ok })
    }

    @Test fun `el bucle se corta a las vueltas y lo dice`() {
        // Un modelo que llama una y otra vez sin contestar nunca con texto.
        val siempre = List(Agente.VUELTAS + Agente.REINTENTOS + 3) {
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"elipse","x":1,"y":1}]}""")
        }
        val ej = Apunta()
        val c = correr(siempre, ej)
        assertEquals(Agente.VUELTAS + Agente.REINTENTOS, c.motor.recibido.size)
        assertEquals(Agente.HECHO_EN_PARTE, c.final.texto)
        // Y uno que llama mal siempre: corta a los reintentos y dice que no supo.
        val mal = List(10) { Respuesta.deLlamada("dibujar_en_lienzo", "{\"elementos\":[]}") }
        val c2 = correr(mal, Apunta())
        assertEquals(Agente.REINTENTOS + 1, c2.motor.recibido.size)
        assertEquals(Agente.NO_SUPE, c2.final.texto)
    }

    @Test fun `parar a mitad corta el turno sin texto`() {
        val ej = Apunta()
        val c = correr(listOf(Respuesta.deTexto("uno dos tres cuatro"), Respuesta.deTexto("otra")), ej,
            pararEn = { it is Agente.Evento.Texto && it.texto.startsWith("dos") })
        assertTrue(c.final.cortado)
        assertEquals("", c.final.texto)
        assertEquals(1, c.motor.recibido.size)
    }

    @Test fun `un fallo de la nube llega tal cual para el usuario`() {
        val motor = object : Motor {
            override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta = throw ErrorIa.Nube("Falta la clave de Claude Haiku 5.5: pégala en Ajustes → Asistente.")
        }
        val e = try {
            Agente.turno(motor, historial(), Herramientas.catalogo(), Apunta()) { Seguir.SI }
            null
        } catch (e: ErrorIa) { e }
        assertTrue(e is ErrorIa.Nube)
        assertEquals("Falta la clave de Claude Haiku 5.5: pégala en Ajustes → Asistente.", e!!.paraElUsuario())
    }

    @Test fun `una llamada cortada por tokens se repite mas corta`() {
        var vueltas = 0
        val motor = object : Motor {
            val tops = mutableListOf<String>()
            override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta {
                vueltas++
                tops += peticion.herramientas[0].parametros.toString()
                if (vueltas == 1) throw ErrorIa.Protocolo("HTTP 500: Failed to parse tool call arguments as JSON")
                return Respuesta.deTexto("Listo.")
            }
        }
        val h = historial()
        val f = Agente.turno(motor, h, Herramientas.catalogo(), Apunta()) { Seguir.SI }
        assertEquals("Listo.", f.texto)
        assertEquals(1, f.reintentos)
        assertTrue(motor.tops[1].contains("\"maxItems\":10"))
        assertTrue(h.any { it.rol == Rol.USUARIO && it.textoEntero.contains("salio cortada") })
        // Otro error de protocolo no es una llamada rota: sube.
        val roto = object : Motor {
            override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta = throw ErrorIa.Protocolo("HTTP 503: cargando")
        }
        assertTrue(runCatching { Agente.turno(roto, historial(), Herramientas.catalogo(), Apunta()) { Seguir.SI } }.exceptionOrNull() is ErrorIa.Protocolo)
    }

    @Test fun `sanear lleva al borde lo que se sale un poco y no toca lo demas`() {
        val c = LlamadaCruda("1", "dibujar_en_lienzo", """{"elementos":[{"tipo":"rectangulo","x":2010,"y":-300,"ancho":5000,"texto":"x"}]}""")
        val s = Agente.sanear(c)
        assertTrue(s.argumentos, s.argumentos.contains("\"x\":2000.0") && s.argumentos.contains("\"y\":-200.0") && s.argumentos.contains("\"ancho\":2000.0"))
        val bien = LlamadaCruda("1", "grafica", """{"tipo":"barras"}""")
        assertEquals(bien, Agente.sanear(bien))
        val roto = LlamadaCruda("1", "grafica", "{roto")
        assertEquals(roto, Agente.sanear(roto))
    }

    @Test fun `el modelo que acaba sin palabras recibe una frase`() {
        val c = correr(listOf(Respuesta.deTexto("")), Apunta())
        assertEquals(Agente.SIN_PALABRAS, c.final.texto)
        val c2 = correr(listOf(Respuesta.deLlamada("grafica", """{"tipo":"barras","etiquetas":["a"],"valores":[1]}"""), Respuesta.deTexto("  ")), Apunta())
        assertEquals(Agente.HECHO, c2.final.texto)
    }

    @Test fun `los colores olvidados se ponen antes de ejecutar`() {
        val ej = Apunta()
        correr(listOf(
            Respuesta.deLlamada("dibujar_en_lienzo", """{"elementos":[{"tipo":"elipse","x":100,"y":150,"ancho":100,"alto":100}]}"""),
            Respuesta.deTexto("Listo.")
        ), ej, pedido = "dibuja un círculo rojo")
        assertEquals("#e03131", (ej.hechas[0] as Llamada.Dibujar).figuras[0].color)
    }

    @Test fun `el pensado de Qwen no llega a la burbuja`() {
        assertEquals("Hola", Agente.quitarPensado("<think>\nhmm\n</think>\n\nHola"))
        assertEquals("Hola", Agente.quitarPensado("Hola"))
        assertTrue(Agente.llamadaRota("Failed to parse tool call arguments as JSON"))
        assertFalse(Agente.llamadaRota("contexto lleno"))
    }

    @Test fun `un resultado larguisimo se recorta diciendolo`() {
        val r = Agente.recortarResultado("a".repeat(5000))
        assertTrue(r.endsWith("[...recortado: el usuario tiene el resultado entero]"))
        assertEquals("corto", Agente.recortarResultado("corto"))
    }
}
