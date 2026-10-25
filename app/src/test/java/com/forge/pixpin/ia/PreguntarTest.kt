package com.forge.pixpin.ia

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** **`preguntar_usuario`** (pruebas de `preguntar.rs` del PC, portadas tal cual). */
class PreguntarTest {

    @Test fun `lee la forma buena y la simplificada`() {
        val p = Preguntar.leer("""{"preguntas":[{"pregunta":"¿Qué idioma?","opciones":[{"etiqueta":"Inglés","descripcion":"todo"},{"etiqueta":"Francés"}]}]}""").getOrThrow()
        assertEquals(1, p.size)
        assertEquals("¿Qué idioma?", p[0].texto)
        assertEquals(Preguntar.Opcion("Inglés", "todo"), p[0].opciones[0])
        // Sin la lista y con opciones en cadena (un modelo con prisa); «Otro» lo pone la app.
        val q = Preguntar.leer("""{"pregunta":"¿Cuál?","opciones":["A","B","Otro"]}""").getOrThrow()
        assertEquals(listOf("A", "B"), q[0].opciones.map { it.etiqueta })
    }

    @Test fun `rechaza con un motivo que se puede corregir`() {
        val e = Preguntar.leer("""{"pregunta":"¿Sigo?","opciones":["Sí"]}""").exceptionOrNull()!!.message!!
        assertTrue(e, e.contains("de 2 a 4") && e.contains("no preguntes"))
        assertTrue(Preguntar.leer("""{"pregunta":"¿?","opciones":["a","b","c","d","e"]}""").exceptionOrNull()!!.message!!.contains("deja las 4"))
        val tres = """{"preguntas":[{"pregunta":"1","opciones":["a","b"]},{"pregunta":"2","opciones":["a","b"]},{"pregunta":"3","opciones":["a","b"]}]}"""
        assertTrue(Preguntar.leer(tres).exceptionOrNull()!!.message!!.contains("como mucho 2"))
        assertTrue(Preguntar.leer("""{"opciones":["a","b"]}""").isFailure)
        assertTrue(Preguntar.leer("no es json").isFailure)
        // Repetidas cuentan una vez.
        assertTrue(Preguntar.leer("""{"pregunta":"¿?","opciones":["Sí","si"]}""").isFailure)
    }

    @Test fun `lo contestado vuelve al modelo y a la burbuja`() {
        val p = Preguntar.leer("""{"pregunta":"¿Qué idioma?","opciones":[{"etiqueta":"Inglés","descripcion":"todo el lienzo"},"Francés"]}""").getOrThrow()[0]
        assertTrue(Preguntar.paraElModelo(p, Preguntar.Contestada.Opcion(0)).contains("eligió «Inglés» (todo el lienzo)"))
        assertTrue(Preguntar.paraElModelo(p, Preguntar.Contestada.Texto(" alemán ")).contains("«alemán»"))
        val sin = Preguntar.paraElModelo(p, Preguntar.Contestada.SinRespuesta)
        assertTrue(sin.contains("no contestó") && sin.contains("«Inglés»") && sin.contains("No vuelvas a preguntarlo"))
        // Una opción que no existe es como no contestar.
        assertEquals(sin, Preguntar.paraElModelo(p, Preguntar.Contestada.Opcion(9)))
        assertEquals("❓ ¿Qué idioma? → ✅ Francés", Preguntar.lineaContestada(p, Preguntar.Contestada.Opcion(1)))
        assertEquals("❓ ¿Qué idioma? → ⌛ sin respuesta: sigo con «Inglés»", Preguntar.lineaContestada(p, Preguntar.Contestada.SinRespuesta))
    }

    @Test fun `el esquema es el que lee`() {
        val h = Preguntar.herramienta()
        assertEquals(Preguntar.NOMBRE, h.nombre)
        val ejemplo = h.descripcion.split("Ejemplo: ")[1]
        assertEquals("el ejemplo de la descripcion vale", 2, Preguntar.leer(ejemplo).getOrThrow()[0].opciones.size)
        val preguntas = h.parametros["properties"]!!.jsonObject["preguntas"]!!.jsonObject
        assertEquals(Preguntar.MAX_PREGUNTAS, preguntas["maxItems"]!!.jsonPrimitive.int)
        // Las claves en orden alfabético, como las escribe el PC.
        assertEquals(h.parametros.keys.sorted(), h.parametros.keys.toList())
    }
}
