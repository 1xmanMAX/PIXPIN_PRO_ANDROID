package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.Importantes.Importante
import com.forge.pixpin.guardados.Importantes.Tipo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Lo importante de una clase** (28-oct-2026, Jev): las pruebas de `clase.rs` del PC
 * (`las_partes_importantes_ida_y_vuelta`, `un_tipo_que_no_se_conoce_se_salta`), el JSON como lo escribe
 * el PC y que el campo sobrevive a reescribir el mensaje aquí.
 */
class ImportantesTest {

    private fun leer(s: String) = Json.parseToJsonElement(s) as JsonArray

    @Test
    fun `las partes importantes van y vuelven`() {
        // Sin la clave: nunca se miró.
        assertNull(Importantes.de(null as JsonArray?))
        val (vacia, de0) = Importantes.poner(emptyList(), "jev-1.14.0")
        // Lista vacía: se miró y no hay nada.
        assertEquals(emptyList<Importante>(), Importantes.de(vacia))
        assertEquals("jev-1.14.0", de0)
        val v = listOf(
            Importante(45_000, Tipo.TAREA, 0.9372, quien = true),
            Importante(20_000, Tipo.EXAMEN, 0.81)
        )
        val (lista, de) = Importantes.poner(v, " jev-1.14.0 ")
        assertEquals("jev-1.14.0", de)
        // El formato que lee el PC: {ms, tipo, p} y, en las tareas, quien y cuando si se saben; las
        // claves en orden alfabético, como el `Map` del PC.
        assertEquals("""[{"ms":20000,"p":0.81,"tipo":"examen"},{"ms":45000,"p":0.94,"quien":true,"tipo":"tarea"}]""", lista.toString())
        val leidos = Importantes.de(lista)!!
        assertEquals(Tipo.EXAMEN, leidos[0].tipo)
        assertEquals(0.94, leidos[1].p, 0.0)
        assertEquals(listOf(Tipo.TAREA), Importantes.tiposEn(leidos, 45_000))
        assertTrue(Importantes.tiposEn(leidos, null).isEmpty())
        assertTrue(Importantes.seVeConFiltro(leidos, null, null))
        assertTrue(Importantes.seVeConFiltro(leidos, 20_000, Tipo.EXAMEN))
        assertFalse(Importantes.seVeConFiltro(leidos, 20_000, Tipo.TAREA))
        // Un renglón sin minuto solo se ve sin filtro.
        assertFalse(Importantes.seVeConFiltro(leidos, null, Tipo.TAREA))
        // Apuntar las tareas en Tareas: solo la tarea de ese minuto, una vez.
        val marcada = Importantes.marcarEnTareas(lista, listOf(45_000L, 20_000L))!!
        assertNull(Importantes.marcarEnTareas(marcada, listOf(45_000L)))
        val otra = Importantes.de(marcada)!!
        assertTrue(otra[1].enTareas)
        assertFalse(otra[0].enTareas)
        assertEquals("""{"enTareas":true,"ms":45000,"p":0.94,"quien":true,"tipo":"tarea"}""", marcada[1].toString())
        assertFalse((marcada[0] as JsonObject).containsKey("enTareas"))
        assertEquals(listOf<Long>(), Importantes.tareasSinApuntar(otra))
        assertEquals(listOf(45_000L), Importantes.tareasSinApuntar(leidos))
        // Sin modelo, la clave del modelo se quita.
        assertNull(Importantes.poner(v, "  ").second)
    }

    @Test
    fun `un tipo que no se conoce se salta`() {
        val v = Importantes.de(leer("""[{"ms":1,"tipo":"chiste","p":0.9},{"ms":2,"tipo":"pregunta"},{"tipo":"tarea"},"x"]"""))!!
        assertEquals(1, v.size)
        assertEquals(Tipo.PREGUNTA, v[0].tipo)
        assertEquals(1.0, v[0].p, 0.0)
        // Lo de otra versión se respeta al marcar: solo cambia la tarea de ese minuto.
        assertNull(Importantes.marcarEnTareas(leer("""[{"ms":1,"tipo":"chiste"}]"""), listOf(1L)))
    }

    @Test
    fun `las cuentas del filtro son de renglones`() {
        val v = Importantes.de(leer("""[{"ms":1,"tipo":"tarea","p":0.9},{"ms":1,"tipo":"examen","p":0.8},{"ms":5,"tipo":"tarea","p":0.7},{"ms":5,"tipo":"tarea","p":0.7}]"""))!!
        val c = Importantes.cuentas(v)
        assertEquals(2, c[Tipo.TAREA])
        assertEquals(1, c[Tipo.EXAMEN])
        assertEquals(0, c[Tipo.DECISION])
        assertEquals(listOf(Tipo.TAREA, Tipo.EXAMEN), Importantes.tiposEn(v, 1))
    }

    @Test
    fun `los colores son los del PC`() {
        assertEquals(0xFF3B82F6, Tipo.TAREA.color)
        assertEquals(0xFF22A06B, Tipo.DECISION.color)
        assertEquals(0xFF8B5CF6, Tipo.PREGUNTA.color)
        assertEquals(0xFFE5534B, Tipo.EXAMEN.color)
    }

    @Test
    fun `la tarea lleva de que clase y que minuto`() {
        assertEquals(
            "Para el lunes resuelvan los ejercicios del 1 al 5. 💬 🎙 Cálculo I ⏱ 1:05",
            Importantes.textoDeTarea("Para el lunes   resuelvan los ejercicios del 1 al 5.", "Cálculo I", 65_000)
        )
        assertTrue(Importantes.textoDeTarea("palabra ".repeat(60), "x", 0).contains('…'))
    }

    @Test
    fun `el campo sobrevive a reescribir el mensaje y el vacio no es lo mismo que nada`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val linea = """{"id":"v","cuando":1,"clase":"VOZ","importantes":[{"ms":20000,"p":0.81,"tipo":"examen"}],"importantesDe":"jev-1.14.0"}"""
        val m = json.decodeFromString(Mensaje.serializer(), linea)
        assertEquals(1, Importantes.de(m)!!.size)
        // Reescrito aquí (fijado), sigue igual.
        val otra = json.encodeToString(Mensaje.serializer(), m.copy(fijado = true))
        assertTrue(otra.contains(""""importantes":[{"ms":20000,"p":0.81,"tipo":"examen"}]"""))
        assertTrue(otra.contains(""""importantesDe":"jev-1.14.0""""))
        // `[]` se escribe (se miró); sin la clave no aparece.
        val vacia = json.encodeToString(Mensaje.serializer(), m.copy(importantes = JsonArray(emptyList()), importantesDe = null))
        assertTrue(vacia.contains(""""importantes":[]"""))
        assertFalse(vacia.contains("importantesDe"))
        val nada = json.encodeToString(Mensaje.serializer(), m.copy(importantes = null, importantesDe = null))
        assertFalse(nada.contains("importantes"))
        assertNull(Importantes.de(json.decodeFromString(Mensaje.serializer(), nada)))
    }
}
