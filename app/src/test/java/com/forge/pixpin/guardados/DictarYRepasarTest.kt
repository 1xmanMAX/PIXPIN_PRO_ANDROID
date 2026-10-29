package com.forge.pixpin.guardados

import com.forge.pixpin.jev.Cache
import com.forge.pixpin.jev.Cliente
import com.forge.pixpin.jev.DictadoAlChat
import com.forge.pixpin.jev.Grabado
import com.forge.pixpin.jev.Preguntas
import com.forge.pixpin.motor.Proyecto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Dictar al chat y repasar en voz alta, del lado de la app** (29-oct-2026): las pruebas de
 * `ventana_chat/dictado_a_chat.rs` y `ventana_chat/repaso_oral.rs` del PC (`15811c76`, `5282d2f5`), los
 * puntos clave, quitar lo dictado de la caja y la propuesta con respuestas grabadas.
 */
class DictarYRepasarTest {

    // --- dictado_a_chat.rs --------------------------------------------------------------------

    @Test
    fun `se quita de la caja lo dictado para pedirlo`() {
        assertEquals("Hola.", DictarAlChat.quitarDicho("Hola. Anota en el chat de la obra que faltan tornillos", "anota en el chat de la obra que faltan tornillos"))
        assertEquals("El cemento llega el jueves.", DictarAlChat.quitarDicho("El cemento llega el jueves. Anota esto en el chat de la obra", "anota esto en el chat de la obra"))
        assertEquals("", DictarAlChat.quitarDicho("El cemento llega el jueves.", "el cemento llega el jueves."))
        // Lo que no está (lo borró a mano) deja la caja como estaba.
        assertEquals("otra cosa", DictarAlChat.quitarDicho("otra cosa", "anótalo"))
        // La última vez que sale, y en medio deja un espacio.
        assertEquals("a x b c", DictarAlChat.quitarDicho("a x b x c", "x"))
        assertEquals("ñandú", DictarAlChat.quitarDicho("ñandú ñandú", "Ñandú"))
    }

    @Test
    fun `lo dictado se pega detras con su mayuscula`() {
        assertEquals("Hola", DictarAlChat.juntar("", "hola"))
        assertEquals("Hola. Qué tal", DictarAlChat.juntar("Hola.", "qué tal"))
        assertEquals("Hola y adiós", DictarAlChat.juntar("Hola", "Y adiós"))
        assertEquals("Hola\nadiós", DictarAlChat.juntar("Hola\n", "Adiós"))
        assertEquals("Hola.\nAdiós", DictarAlChat.juntar("Hola.\n", "adiós"))
        assertEquals("Hola", DictarAlChat.juntar("Hola", "   "))
        // Y lo pegado se quita igual.
        val caja = DictarAlChat.juntar("El cemento llega el jueves.", "anota esto en el chat de la obra")
        assertEquals("El cemento llega el jueves.", DictarAlChat.quitarDicho(caja, "anota esto en el chat de la obra"))
    }

    private fun proyecto(id: String, nombre: String, tocado: Long, archivado: Boolean = false) =
        Proyecto(id = id, nombre = nombre, tocado = tocado, archivado = archivado)

    @Test
    fun `los chats que se ofrecen son los recientes sin este`() {
        val p = listOf(
            proyecto("1", "Obra Calle Lima", 10),
            proyecto("2", "Compras", 30),
            proyecto("3", "  ", 40),
            proyecto("4", "Este", 50),
            proyecto("5", "Asistente", 60),
            proyecto("6", "Viejo", 70, archivado = true)
        )
        assertEquals(listOf("2" to "Compras", "1" to "Obra Calle Lima"), DictarAlChat.chatsPara(p, "4", "5"))
        // Un mensaje reciente sube el chat.
        assertEquals(listOf("1" to "Obra Calle Lima", "2" to "Compras"), DictarAlChat.chatsPara(p, "4", "5", mapOf("1" to 99L)))
        assertEquals(DictarAlChat.CHATS, DictarAlChat.chatsPara((1..40).map { proyecto("$it", "c$it", it.toLong()) }, null, null).size)
    }

    @Test
    fun `la propuesta sale con respuestas grabadas`() {
        val r = """{"model":"jev-1.14.0","answers":{
            "dictado":{"type":"noul","noul":0.95},
            "dictado_chat_dicho":{"type":"noul","noul":0.9},
            "dictado_chat":{"type":"choice","choice":"obra_calle_lima","confidence":0.6,
                "probabilities":{"obra_calle_lima":0.8,"compras":0.2}}},
            "usage":{"input_tokens":180,"output_tokens":9}}"""
        val c = Cliente("k", Grabado(listOf(Grabado.ok(r))), cache = Cache(), registro = {}, dormir = {})
        val chats = listOf("1" to "Obra Calle Lima", "2" to "Compras")
        val frase = DictadoAlChat.Frase(0, DictarAlChat.QUIEN, "Anota en el chat de la obra que faltan tornillos")
        val s = DictadoAlChat.preguntar(c, emptyList(), frase, chats.map { it.second })
        val prop = DictarAlChat.propuestaDe(frase, emptyList(), chats, s)!!
        assertEquals("1" to "Obra Calle Lima", prop.chat)
        assertEquals("faltan tornillos", prop.texto)
        assertEquals(listOf(frase.dice), prop.quitar)
        assertTrue(DictarAlChat.rotulo(prop).contains("Obra Calle Lima"))
        assertEquals("Anotar en «Obra Calle Lima»: faltan tornillos", DictarAlChat.rotulo(prop))
    }

    @Test
    fun `anota esto quita tambien la frase anterior y sin chat se pregunta`() {
        val antes = listOf(DictadoAlChat.Frase(0, "Yo", "El cemento llega el jueves a las 8"))
        val frase = DictadoAlChat.Frase(0, "Yo", "anota esto en algún sitio")
        val chats = listOf("1" to "Obra", "2" to "Compras")
        val prop = DictarAlChat.propuestaDe(frase, antes, chats, DictadoAlChat.Senal(0.9, 0.1, 1, "El cemento llega el jueves a las 8"))!!
        assertNull(prop.chat)
        assertEquals(listOf(frase.dice, antes[0].dice), prop.quitar)
        assertEquals("¿En qué chat anoto «El cemento llega el jueves a las 8»? Elegir chat", DictarAlChat.rotulo(prop))
        // Dudoso o «no»: nada que proponer.
        assertNull(DictarAlChat.propuestaDe(frase, antes, chats, DictadoAlChat.Senal(0.5, 0.9, 0, "x y")))
        assertNull(DictarAlChat.propuestaDe(frase, antes, chats, null))
        // Dos chats con el mismo nombre: el del índice.
        val dobles = listOf("a" to "Obra", "b" to "Obra")
        assertEquals("b" to "Obra", DictarAlChat.propuestaDe(frase, antes, dobles, DictadoAlChat.Senal(0.9, 0.9, 1, "x y"))!!.chat)
        // Un texto largo se corta en la chapa.
        val largo = prop.copy(texto = "a".repeat(100))
        assertTrue(DictarAlChat.rotulo(largo).contains("a".repeat(90) + "…"))
    }

    // --- repaso_oral.rs -----------------------------------------------------------------------

    private fun importante(ms: Long, tipo: Importantes.Tipo) = Importantes.Importante(ms, tipo, 0.9)

    @Test
    fun `los puntos clave salen de lo importante los marcadores y los temas`() {
        val lineas = listOf(
            EstudiarClase.Linea(0, "Hoy vemos la fotosíntesis."),
            EstudiarClase.Linea(60_000, "Esto entra: la planta produce oxígeno."),
            EstudiarClase.Linea(120_000, "Necesita luz para hacerla."),
            EstudiarClase.Linea(180_000, "Traed el cuaderno el lunes.")
        )
        val importantes = listOf(
            importante(60_000, Importantes.Tipo.EXAMEN),
            importante(120_000, Importantes.Tipo.DECISION),
            // Las tareas y las preguntas no son puntos clave.
            importante(180_000, Importantes.Tipo.TAREA),
            // El mismo renglón dos veces: una.
            importante(60_000, Importantes.Tipo.DECISION)
        )
        val marcadores = listOf(EstudiarClase.Marcador(90_000, "El agua entra por la raíz"), EstudiarClase.Marcador(5, " "))
        val temas = listOf(
            RepasarClase.Tema("t1", "La clorofila", true, 150_000),
            RepasarClase.Tema("t2", "Sin tratar", false, null)
        )
        val v = RepasarClase.puntosClave(lineas, importantes, marcadores, temas)
        assertEquals(
            listOf("Esto entra: la planta produce oxígeno.", "El agua entra por la raíz", "Necesita luz para hacerla.", "La clorofila"),
            v.map { it.texto }
        )
        assertEquals(90_000L, v[1].ms)
    }

    @Test
    fun `como mucho los que caben en una peticion y primero el examen`() {
        val lineas = (0 until 20).map { EstudiarClase.Linea(it * 1000L, "renglon $it") }
        val importantes = (0 until 15).map { importante(it * 1000L, Importantes.Tipo.DECISION) } + importante(19_000, Importantes.Tipo.EXAMEN)
        val v = RepasarClase.puntosClave(lineas, importantes, emptyList(), emptyList())
        assertEquals(Preguntas.Repaso.POR_PETICION, v.size)
        assertTrue(v.any { it.texto == "renglon 19" })
        // En el orden de la clase.
        assertEquals(v.sortedBy { it.ms }, v)
    }

    @Test
    fun `un tema tratado sin minuto va al final y lo largo se recorta`() {
        val v = RepasarClase.puntosClave(
            emptyList(), emptyList(),
            listOf(EstudiarClase.Marcador(1000, "x".repeat(300))),
            listOf(RepasarClase.Tema("t1", "Sin minuto", true, null))
        )
        assertEquals(listOf(1000L, null), v.map { it.ms })
        assertEquals(RepasarClase.LARGO_PUNTO + 1, v[0].texto.length)
    }

    @Test
    fun `lo ultimo dicho se corta por delante`() {
        assertEquals("hola", RepasarClase.loUltimo("  hola  ", 10))
        assertEquals("…def", RepasarClase.loUltimo("abcdef", 3))
        assertEquals("12:34", RepasarClase.minuto(754_000))
        assertEquals("1:02:03", RepasarClase.minuto(3_723_000))
        assertEquals("0:00", RepasarClase.minuto(-5))
    }

    @Test
    fun `los temas de la nota se leen como en el PC`() {
        val a = Json.parseToJsonElement(
            """[{"id":"t1","texto":" Presupuesto ","hecho":true,"ms":60000,"p":0.9,"porJev":true},
                {"id":"t1","texto":"repetido","hecho":true},
                {"id":"t2","texto":"Viaje"},
                {"id":"","texto":"sin id"},
                {"texto":"sin id"},
                {"id":"t3","texto":"Negativo","hecho":true,"ms":-5}]"""
        ) as JsonArray
        assertEquals(
            listOf(
                RepasarClase.Tema("t1", "Presupuesto", true, 60_000),
                RepasarClase.Tema("t2", "Viaje", false, null),
                RepasarClase.Tema("t3", "Negativo", true, 0)
            ),
            RepasarClase.temasDe(a)
        )
        assertTrue(RepasarClase.temasDe(null).isEmpty())
    }

    @Test
    fun `sin marcas como el PC`() {
        assertEquals("Título\ntexto en negrita", RepasarClase.sinMarcas("## Título\n![img](x.png)\ntexto en **negrita**"))
    }
}
