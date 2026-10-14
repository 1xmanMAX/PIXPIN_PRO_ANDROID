package com.forge.pixpin.ia

import com.forge.pixpin.asistente.ChatDelAsistente
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.conversaciones
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.sincro.Canonico
import com.forge.pixpin.sincro.Disco
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El chat «Asistente» y la compatibilidad con el PC** (`docs/manual-asistente-android.md` §3):
 * un solo chat del asistente, quién contesta, la conversación que ve el modelo, y que las notas
 * del PC con `lienzoIa`/`fuentesIa` y el proyecto con `asistente` **no pierdan nada** al pasar por
 * el teléfono (era el bloqueante del manual).
 */
class ChatDelAsistenteTest {

    private fun nota(id: String, texto: String, proyecto: String? = "pr-a", aparato: String? = "K7Q2", cuando: Long = id.hashCode().toLong().and(0xffff)) =
        Mensaje(id = id, cuando = cuando, clase = Clase.NOTA, texto = texto, proyecto = proyecto, aparato = aparato)

    @Test fun `un solo chat del asistente, reconocido como dice el manual`() {
        val otro = Proyecto("pr-x", "Obra")
        val conMarca = Proyecto("pr-m", "Mi IA", asistente = true)
        val delPc = Proyecto("pr-pc", "Asistente")
        // 1. La marca manda.
        assertEquals("pr-m", ChatDelAsistente.buscar(listOf(otro, delPc, conMarca), emptyList())?.id)
        // 2. Sin marca: el que tiene el mensaje de id fijo del PC (aunque lo hayan renombrado).
        val renombrado = Proyecto("pr-r", "Mi ayudante")
        val auto = Mensaje(id = "auto-automatizaciones", cuando = 1, clase = Clase.ARCHIVO, proyecto = "pr-r")
        assertEquals("pr-r", ChatDelAsistente.buscar(listOf(otro, renombrado), listOf(auto))?.id)
        // 3. Sin nada de eso: el que se llama «Asistente» y tiene notas del aparato «IA».
        val respuesta = nota("r1", "Hola", proyecto = "pr-pc", aparato = "IA")
        assertEquals("pr-pc", ChatDelAsistente.buscar(listOf(otro, delPc), listOf(respuesta))?.id)
        // Un proyecto que solo se llama «Asistente», sin nada del asistente, no es él.
        assertNull(ChatDelAsistente.buscar(listOf(otro, delPc), listOf(nota("n", "x", proyecto = "pr-pc"))))
        // Uno archivado no cuenta.
        assertNull(ChatDelAsistente.buscar(listOf(conMarca.copy(archivado = true)), emptyList()))
    }

    @Test fun `contesta a todo en su chat y a la mencion en los demas`() {
        assertTrue(ChatDelAsistente.leToca(nota("1", "dibuja un gato", proyecto = "pr-a"), "pr-a"))
        assertFalse(ChatDelAsistente.leToca(nota("2", "dibuja un gato", proyecto = "pr-b"), "pr-a"))
        assertTrue(ChatDelAsistente.leToca(nota("3", "@asistente ¿qué pone en el plano?", proyecto = "pr-b"), "pr-a"))
        assertTrue(ChatDelAsistente.leToca(nota("4", "oye @Asistente, resume", proyecto = null), null))
        assertFalse(ChatDelAsistente.leToca(nota("5", "correo@asistente.com", proyecto = "pr-b"), "pr-a"))
        // Nunca a sí mismo.
        assertFalse(ChatDelAsistente.leToca(nota("6", "@asistente hola", aparato = "IA"), "pr-a"))
        assertEquals("¿qué pone?", ChatDelAsistente.sinMencion("@asistente, ¿qué pone?"))
        assertEquals("resume esto", ChatDelAsistente.sinMencion("resume @asistente esto").replace("  ", " "))
    }

    @Test fun `la conversacion para el modelo, con lo de antes y la pregunta al final`() {
        val ms = listOf(
            nota("1", "Hola", cuando = 1), nota("2", "¡Hola! ¿Qué dibujo?", aparato = "IA", cuando = 2),
            nota("3", "✏️ dibujando en el lienzo…", aparato = "IA", cuando = 3),
            nota("4", "Un círculo rojo", cuando = 4), nota("x", "de otro chat", proyecto = "pr-z", cuando = 5)
        )
        val h = ChatDelAsistente.historial("SIS", ms, ms[3], enSuChat = true, foto = null)
        assertEquals(listOf(Rol.SISTEMA, Rol.USUARIO, Rol.ASISTENTE, Rol.USUARIO), h.map { it.rol })
        assertEquals("Un círculo rojo", h.last().textoEntero)
        assertEquals("¡Hola! ¿Qué dibujo?", h[2].textoEntero)
        // Con foto: la imagen va en la pregunta, primero.
        val conFoto = ChatDelAsistente.historial("SIS", ms, ms[3], true, byteArrayOf(1) to "image/jpeg")
        assertTrue(conFoto.last().tieneImagen)
        // Fuera de su chat, lo último del chat va como contexto en la misma pregunta.
        val fuera = ChatDelAsistente.historial("SIS", ms, nota("5", "@asistente ¿de qué hablamos?", cuando = 9), enSuChat = false, foto = null)
        assertEquals(2, fuera.size)
        assertTrue(fuera.last().textoEntero.startsWith("Lo ultimo de este chat:"))
        assertTrue(fuera.last().textoEntero.endsWith("¿de qué hablamos?"))
        assertFalse(fuera.last().textoEntero.contains("de otro chat"))
    }

    @Test fun `la foto que ve el modelo es la ultima del usuario entre los cuatro de antes`() {
        val foto = Mensaje(id = "f", cuando = 1, clase = Clase.IMAGEN, ruta = "/x.jpg", proyecto = "pr-a")
        val ms = listOf(foto, nota("r", "Bonita", aparato = "IA", cuando = 2), nota("p", "dibújala", cuando = 3))
        assertEquals("f", ChatDelAsistente.fotoDe(ms, ms[2])?.id)
        val lejos = listOf(foto) + (1..5).map { nota("n$it", "x", cuando = 1L + it) } + nota("p", "dibújala", cuando = 9)
        assertNull(ChatDelAsistente.fotoDe(lejos, lejos.last()))
    }

    @Test fun `la burbuja en vivo, los fallos y el parado`() {
        assertEquals("💭 …", ChatDelAsistente.mostrar("", null, true))
        assertEquals("Hola ▍", ChatDelAsistente.mostrar("Hola", null, true))
        assertEquals("Voy\n✏️ dibujando en el lienzo…", ChatDelAsistente.mostrar("Voy", ChatDelAsistente.rotuloDe("dibujar_en_lienzo"), true))
        assertEquals("Listo\n⚠️ a · b", ChatDelAsistente.textoFinal("Listo", listOf("a", "b", "a"), false))
        assertEquals("Medio ⏹ (parado)", ChatDelAsistente.textoFinal("Medio", emptyList(), true))
        assertEquals(Agente.SIN_PALABRAS, ChatDelAsistente.textoFinal("", emptyList(), false))
        assertEquals("Hola", ChatDelAsistente.sinPensado("<think>mmm</think>Hola"))
        assertEquals("Ho", ChatDelAsistente.sinPensado("Ho<think>a medias"))
        assertEquals("Hola", ChatDelAsistente.limpiarRespuesta("Hola\n⚠️ algo"))
    }

    @Test fun `el chat del asistente va fijo detras de la general, exista o no`() {
        val ms = listOf(nota("1", "x", proyecto = "pr-b", cuando = 50))
        val nombres = mapOf("pr-a" to "Asistente", "pr-b" to "Obra")
        assertEquals(listOf(null, "pr-a", "pr-b"), conversaciones(ms, nombres, "Mensajes", "pr-a").map { it.proyecto })
        assertEquals(listOf(null, ChatDelAsistente.SIN_CREAR, "pr-b", "pr-a"),
            conversaciones(ms, nombres, "Mensajes", ChatDelAsistente.SIN_CREAR).map { it.proyecto })
        assertEquals(listOf(null, "pr-b", "pr-a"), conversaciones(ms, nombres, "Mensajes").map { it.proyecto })
    }

    @Test fun `una nota del PC con lienzoIa y fuentesIa no pierde nada al pasar por el telefono`() {
        // Las dos líneas del manual (§3.1), tal cual las escribe el PC.
        val conLienzo = """{"id":"1792044000456","cuando":1792044000456,"clase":"NOTA","texto":"Listo: dibujé la casa roja en «Lienzo del asistente».","proyecto":"pr-1791999000000","numero":48,"uid":"Q2W7X1KD9P","aparato":"IA","lienzoIa":"3DSRX435ZU"}"""
        val conFuentes = """{"id":"1792044100000","cuando":1792044100000,"clase":"NOTA","aparato":"IA","numero":50,"uid":"Z2C3WQFTHJ","proyecto":"pr-1791999000000","texto":"La cuneta del km 12 queda pendiente [1].","fuentesIa":[{"n":1,"titulo":"Informe cunetas.pdf · pág. 12","proyecto":"Obra","pedido":{"pixpin":1,"accion":"abrir","que":{"tipo":"fichero","ruta":"F:\\Obra\\Informe cunetas.pdf","pagina":12}}}]}"""
        // Con el JSON del almacén del chat (el de `guardados.jsonl`).
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val m1 = json.decodeFromString(Mensaje.serializer(), conLienzo)
        assertEquals("3DSRX435ZU", m1.lienzoIa)
        // Fijarla (lo que reescribe la nota en el móvil) y volver a escribirla: sigue ahí.
        val vuelta1 = Json.parseToJsonElement(json.encodeToString(Mensaje.serializer(), m1.copy(fijado = true))).jsonObject
        assertEquals("3DSRX435ZU", vuelta1["lienzoIa"]!!.jsonPrimitive.content)
        val m2 = json.decodeFromString(Mensaje.serializer(), conFuentes)
        val vuelta2 = Json.parseToJsonElement(json.encodeToString(Mensaje.serializer(), m2)).jsonObject
        assertEquals("Informe cunetas.pdf · pág. 12", vuelta2["fuentesIa"]!!.jsonArray[0].jsonObject["titulo"]!!.jsonPrimitive.content)
        assertEquals(12, vuelta2["fuentesIa"]!!.jsonArray[0].jsonObject["pedido"]!!.jsonObject["que"]!!.jsonObject["pagina"]!!.jsonPrimitive.content.toInt())
        // Y en la forma portátil que viaja, con lo mismo que trajo el PC.
        val disco = Disco(kotlin.io.path.createTempDirectory("ia").toFile())
        val portatil = Json.parseToJsonElement(disco.portatil(m2)).jsonObject
        assertEquals(Canonico.de(conFuentes).let { Json.parseToJsonElement(it).jsonObject["fuentesIa"] }, portatil["fuentesIa"])
        // Una nota normal no escribe ni `lienzoIa` ni `fuentesIa` (nada de `null` nuevos en el disco).
        val normal = json.encodeToString(Mensaje.serializer(), nota("n", "hola"))
        assertFalse(normal.contains("lienzoIa") || normal.contains("fuentesIa"))
        disco.filesDir.deleteRecursively()
    }

    @Test fun `el proyecto conserva la marca del asistente y los demas no la escriben`() {
        val p = Proyectos.json.decodeFromString(Proyecto.serializer(), """{"id":"pr-1","nombre":"Asistente","asistente":true}""")
        assertEquals(true, p.asistente)
        assertTrue(Proyectos.json.encodeToString(Proyecto.serializer(), p.copy(nombre = "Mi IA")).contains("\"asistente\":true"))
        assertFalse(Proyectos.json.encodeToString(Proyecto.serializer(), Proyecto("pr-2", "Obra")).contains("asistente"))
    }
}
