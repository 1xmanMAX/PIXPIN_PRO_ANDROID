package com.forge.pixpin.ia

import com.forge.pixpin.asistente.ChatDelAsistente
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.sincro.Disco
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Lo que entró en el turno del asistente el 25-oct-2026** (pruebas de `agente.rs` y de
 * `asistente/chat.rs` del PC, portadas con el motor guionado): «Conversación nueva», preguntar
 * al usuario y esperar, escribirle mientras trabaja, no acabar anunciando y la guarda
 * anti-duplicado.
 */
class TurnoNovedadesTest {

    /** Un ejecutor que apunta y, si se le deja, contesta las dudas con [contestar]. */
    private class Apunta(val contestar: ((Preguntar.Pregunta) -> Preguntar.Contestada)? = null) : Agente.Ejecutor {
        val hechas = mutableListOf<Llamada>()
        val preguntado = mutableListOf<Preguntar.Pregunta>()
        override fun ejecutar(llamada: Llamada): Result<String> { hechas += llamada; return Result.success("hecho") }
        override fun puedePreguntar() = contestar != null
        override fun preguntar(p: Preguntar.Pregunta): Preguntar.Contestada { preguntado += p; return contestar!!(p) }
    }

    private fun historial(pedido: String) = mutableListOf(MensajeIa.sistema(Agente.instrucciones()), MensajeIa.usuario(pedido))

    private val pregunta = """{"preguntas":[{"pregunta":"¿Traduzco todo el lienzo o solo la figura?","opciones":[{"etiqueta":"Solo la figura"},{"etiqueta":"Todo el lienzo"}]}]}"""

    // ------------------------------------------------------------------------------- preguntar

    @Test fun `preguntar para el turno hasta que contesta y lo dicho vuelve al modelo`() {
        val ej = Apunta { Preguntar.Contestada.Opcion(1) }
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(Preguntar.NOMBRE, pregunta),
            Respuesta.deLlamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":10,"y":10}]}"""),
            Respuesta.deTexto("Hecho en todo el lienzo.")
        ))
        val eventos = mutableListOf<Agente.Evento>()
        val f = Agente.turno(motor, historial("traduce esto"), Herramientas.catalogo(), ej) { eventos += it; Seguir.SI }
        assertEquals("Hecho en todo el lienzo.", f.texto)
        assertEquals(1, ej.preguntado.size)
        // La herramienta va la última del catálogo (el prefijo de la caché no cambia).
        assertEquals(Preguntar.NOMBRE, motor.recibido[0].herramientas.last().nombre)
        val resultado = motor.recibido[1].mensajes.last()
        assertEquals(Rol.HERRAMIENTA, resultado.rol)
        assertTrue(resultado.textoEntero, resultado.textoEntero.contains("eligió «Todo el lienzo»"))
        assertTrue(eventos.any { it is Agente.Evento.Resultado && it.nombre == Preguntar.NOMBRE && it.ok })
    }

    @Test fun `sin nadie a quien preguntar no se ofrece y no se pregunta mas de dos veces`() {
        val sin = MotorGuionado(listOf(Respuesta.deTexto("Hola.")))
        Agente.turno(sin, historial("hola"), Herramientas.catalogo(), Apunta()) { Seguir.SI }
        assertTrue(sin.recibido[0].herramientas.none { it.nombre == Preguntar.NOMBRE })
        val ej = Apunta { Preguntar.Contestada.SinRespuesta }
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(Preguntar.NOMBRE, pregunta),
            Respuesta.deLlamada(Preguntar.NOMBRE, pregunta),
            Respuesta.deLlamada(Preguntar.NOMBRE, pregunta),
            Respuesta.deTexto("Sigo con la figura.")
        ))
        val f = Agente.turno(motor, historial("traduce"), Herramientas.catalogo(), ej) { Seguir.SI }
        assertEquals("Sigo con la figura.", f.texto)
        assertEquals(Agente.PREGUNTAS_POR_TURNO, ej.preguntado.size)
        assertTrue(motor.recibido[3].mensajes.last().textoEntero.startsWith("Ya preguntaste en este turno"))
        // Sin respuesta: que siga con la recomendada.
        assertTrue(motor.recibido[1].mensajes.last().textoEntero.contains("sigue con la recomendada («Solo la figura»)"))
    }

    @Test fun `una pregunta mal hecha vuelve con el motivo`() {
        val ej = Apunta { Preguntar.Contestada.Opcion(0) }
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(Preguntar.NOMBRE, """{"pregunta":"¿Sigo?","opciones":["Sí"]}"""),
            Respuesta.deTexto("Sigo.")
        ))
        Agente.turno(motor, historial("x"), Herramientas.catalogo(), ej) { Seguir.SI }
        assertTrue(ej.preguntado.isEmpty())
        assertTrue(motor.recibido[1].mensajes.last().textoEntero.startsWith("ERROR: «¿Sigo?» necesita de 2 a 4 opciones"))
    }

    // -------------------------------------------------------------- escribirle mientras trabaja

    @Test fun `lo que escribe a media tarea entra antes de la vuelta siguiente y para lo corta`() {
        val cola = ArrayDeque(listOf(emptyList(), listOf("mejor en azul")))
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":10,"y":10,"color":"rojo"}]}"""),
            Respuesta.deTexto("Lo cambié a azul.")
        ))
        val f = Agente.turno(motor, historial("dibuja un cuadro rojo"), Herramientas.catalogo(), Apunta(),
            opciones = Agente.Opciones(entrantes = { cola.removeFirstOrNull() ?: emptyList() })) { Seguir.SI }
        assertEquals("Lo cambié a azul.", f.texto)
        assertTrue(motor.recibido[1].mensajes.last().textoEntero.startsWith("[Mensaje nuevo del usuario mientras trabajabas]\nmejor en azul"))
        // Al acabar sin herramientas, lo escrito se lee y se sigue (no se pierde).
        val tarde = ArrayDeque(listOf(emptyList(), listOf("y ponle un título")))
        val m2 = MotorGuionado(listOf(Respuesta.deTexto("Listo."), Respuesta.deTexto("Puesto el título.")))
        val f2 = Agente.turno(m2, historial("dibuja"), Herramientas.catalogo(), Apunta(),
            opciones = Agente.Opciones(entrantes = { tarde.removeFirstOrNull() ?: emptyList() })) { Seguir.SI }
        assertEquals("Puesto el título.", f2.texto)
        // «Para» a secas corta el turno.
        val m3 = MotorGuionado(listOf(Respuesta.deTexto("no se llega")))
        val f3 = Agente.turno(m3, historial("dibuja"), Herramientas.catalogo(), Apunta(),
            opciones = Agente.Opciones(entrantes = { listOf("para") })) { Seguir.SI }
        assertTrue(f3.cortado)
        assertTrue(m3.recibido.isEmpty())
    }

    // -------------------------------------------------------------------- no acabar anunciando

    @Test fun `anunciar trabajo recibe un empujon y el turno sigue, con su tope`() {
        val ej = Apunta()
        val motor = MotorGuionado(listOf(
            Respuesta.deTexto("Ahora voy a dibujar el resto."),
            Respuesta.deLlamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":10,"y":10}]}"""),
            Respuesta.deTexto("Listo: dibujado.")
        ))
        val f = Agente.turno(motor, historial("dibuja una casa"), Herramientas.catalogo(), ej, opciones = Agente.Opciones(empujar = true)) { Seguir.SI }
        assertEquals("Listo: dibujado.", f.texto)
        assertEquals(1, ej.hechas.size)
        assertTrue(motor.recibido[1].mensajes.last().textoEntero.startsWith(Guardas.MARCA_DEL_TURNO))
        // Sin empujar (o con una respuesta normal), se acaba en la primera.
        val m2 = MotorGuionado(listOf(Respuesta.deTexto("Ahora voy a dibujar el resto.")))
        assertEquals("Ahora voy a dibujar el resto.", Agente.turno(m2, historial("x"), Herramientas.catalogo(), Apunta()) { Seguir.SI }.texto)
        // Con el tope: tres empujones y la cuarta se da por buena.
        val m3 = MotorGuionado(List(4) { Respuesta.deTexto("Voy a hacerlo.") })
        val f3 = Agente.turno(m3, historial("x"), Herramientas.catalogo(), Apunta(), opciones = Agente.Opciones(empujar = true)) { Seguir.SI }
        assertEquals(Guardas.EMPUJONES + 1, m3.recibido.size)
        assertEquals("Voy a hacerlo.", f3.texto)
    }

    @Test fun `con la nube hay mas vueltas`() {
        val guion = List(Agente.VUELTAS_NUBE) { Respuesta.deLlamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","x":10,"y":10}]}""") } + Respuesta.deTexto("Ya.")
        val f = Agente.turno(MotorGuionado(guion), historial("x"), Herramientas.catalogo(), Apunta(), opciones = Agente.Opciones(vueltas = Agente.VUELTAS_NUBE)) { Seguir.SI }
        assertEquals("Ya.", f.texto)
        assertEquals(Agente.VUELTAS_NUBE, f.herramientasHechas)
    }

    // ------------------------------------------------------------------ la guarda en el turno

    @Test fun `la guarda no deja poner otra plantilla al lado de la que hay que agrandar`() {
        val ej = Apunta()
        val pedido = LienzoDestino.conResumen("agranda el cronograma a 30 semanas",
            "Lienzo destino: 40 elementos (coordenadas como en dibujar_en_lienzo; usa sus #id para editarlos).\n${Guardas.MARCA_PLANTILLAS}: «Cronograma».\n#1 rectangulo negro [0,0,10,10]")
        val motor = MotorGuionado(listOf(
            Respuesta.deLlamada(Herramientas.FIGURAS, """{"accion":"poner","nombre":"cronograma"}"""),
            Respuesta.deTexto("En este teléfono aún no puedo alargarla.")
        ))
        val f = Agente.turno(motor, historial(pedido), Herramientas.catalogo(), ej) { Seguir.SI }
        assertTrue(ej.hechas.isEmpty())
        assertTrue(motor.recibido[1].mensajes.last().textoEntero.startsWith("ERROR: no se hizo figuras_guardadas"))
        assertEquals("En este teléfono aún no puedo alargarla.", f.texto)
    }

    // ------------------------------------------------------------------- «Conversación nueva»

    private fun nota(id: String, texto: String, ia: Boolean = false, corte: Boolean = false, clase: Clase = Clase.NOTA) =
        Mensaje(id = id, cuando = id.removePrefix("m").toLong(), clase = clase, texto = texto, proyecto = "pr-a",
            aparato = if (ia) ChatDelAsistente.APARATO else "K7Q2", nuevaConversacionIa = if (corte) true else null)

    @Test fun `tras la conversacion nueva lo de antes no se le manda`() {
        val ms = listOf(
            nota("m1", "dibuja una casa"), nota("m2", "Hecha.", ia = true),
            nota("m3", "foto", clase = Clase.IMAGEN).copy(ruta = "/x/foto.jpg"),
            nota("m4", ChatDelAsistente.TEXTO_NUEVA_CONVERSACION, ia = true, corte = true),
            nota("m5", "¿qué hora es?")
        )
        assertTrue(ChatDelAsistente.esCorte(ms[3]))
        // Una nota del usuario con la marca no es un corte (solo las del asistente).
        assertFalse(ChatDelAsistente.esCorte(nota("m9", "x", corte = true)))
        assertEquals(listOf("m5"), ChatDelAsistente.desdeElCorte(ms).map { it.id })
        assertEquals(ms.take(3), ChatDelAsistente.desdeElCorte(ms.take(3)))
        // La conversación que ve el modelo empieza tras el corte; la foto de antes, tampoco.
        val h = ChatDelAsistente.historial("S", ms, ms[4], enSuChat = true, foto = null)
        assertEquals(listOf("S", "¿qué hora es?"), h.map { it.textoEntero })
        assertEquals(null, ChatDelAsistente.fotoDe(ms, ms[4]))
        assertEquals("m3", ChatDelAsistente.fotoDe(ms.take(3) + ms[4], ms[4])?.id)
        // Dos cortes seguidos no dicen nada nuevo; tras una pregunta, sí se puede cortar otra vez.
        assertTrue(ChatDelAsistente.yaCortado(ms.take(4)))
        assertFalse(ChatDelAsistente.yaCortado(ms))
        // Lo que se cierra va a la memoria: sin la marca ni lo de antes del corte anterior.
        assertEquals(listOf(true to "dibuja una casa", false to "Hecha."), ChatDelAsistente.laQueSeCierra(ms.take(3)))
        assertEquals(listOf(true to "¿qué hora es?"), ChatDelAsistente.laQueSeCierra(ms))
    }

    @Test fun `la marca viaja como la escribe el PC y no se pierde al reescribir`() {
        // Como la escribe el PC (`resto` de su nota).
        val delPc = """{"id":"1792044200000","cuando":1792044200000,"clase":"NOTA","texto":"${ChatDelAsistente.TEXTO_NUEVA_CONVERSACION}","proyecto":"pr-1","numero":51,"uid":"Q2W7X1KD9Q","aparato":"IA","nuevaConversacionIa":true}"""
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val m = json.decodeFromString(Mensaje.serializer(), delPc)
        assertTrue(ChatDelAsistente.esCorte(m))
        val vuelta = Json.parseToJsonElement(json.encodeToString(Mensaje.serializer(), m.copy(fijado = true))).jsonObject
        assertEquals("true", vuelta["nuevaConversacionIa"]!!.jsonPrimitive.content)
        val disco = Disco(kotlin.io.path.createTempDirectory("ia").toFile())
        assertEquals("true", Json.parseToJsonElement(disco.portatil(m)).jsonObject["nuevaConversacionIa"]!!.jsonPrimitive.content)
        // Una nota normal no la escribe.
        assertFalse(json.encodeToString(Mensaje.serializer(), nota("m1", "hola")).contains("nuevaConversacionIa"))
        disco.filesDir.deleteRecursively()
    }
}
