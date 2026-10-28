package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.ClaseExportar
import com.forge.pixpin.guardados.ClaseExportar.Datos
import com.forge.pixpin.guardados.ClaseExportar.Medio
import com.forge.pixpin.guardados.ClaseExportar.Rotulos
import com.forge.pixpin.guardados.Importantes
import com.forge.pixpin.guardados.Mensaje
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La página compartida de la clase con lo importante** (28-oct-2026, Jev): la prueba
 * `lo_importante_lleva_sus_chapitas_y_el_filtro` de `clase_exportar/pruebas.rs` del PC (`a6b248fe`), con
 * las mismas cadenas esperadas.
 */
class ClaseExportarImportantesTest {

    private fun ejemplo() = Datos(
        titulo = "Física",
        duracionMs = 180_000,
        audio = "/x/clase.m4a",
        lineas = listOf(0L to "Hola", 60_000L to "Vamos con la ley de Ohm", 120_000L to "Y la potencia")
    )

    private val incrustar: (String) -> Medio? = { Medio.Incrustado("audio/mp4", "a".toByteArray()) }

    @Test
    fun `lo importante lleva sus chapitas y el filtro`() {
        // Sin nada hallado, la página es la de siempre.
        var h = ClaseExportar.html(ejemplo(), Rotulos(), incrustar)
        assertFalse(h.contains("filtro-importante\" role"))
        assertFalse(h.contains(" data-tipos="))
        val c = ejemplo().copy(
            importantes = listOf(
                Importantes.Importante(60_000, Importantes.Tipo.EXAMEN, 0.9),
                Importantes.Importante(60_000, Importantes.Tipo.TAREA, 0.8, quien = true)
            )
        )
        h = ClaseExportar.html(c, Rotulos(), incrustar)
        assertTrue(h.contains("data-tipos=\"tarea examen\""))
        assertTrue(h.contains("<span class=\"chip tarea\">Tarea</span><span class=\"chip examen\">Examen</span>"))
        assertTrue(h.contains("data-filtro=\"tarea\""))
        assertTrue(h.contains("Examen 1</button>"))
        // Sin decisiones ni preguntas, sus botones no salen.
        assertFalse(h.contains("button\" data-filtro=\"decision\""))
        // Lo que no es importante no lleva `data-tipos`.
        assertTrue(h.contains("<p class=\"linea\" id=\"t-0\" data-ms=\"0\"><a"))
        // El estilo y el guion del filtro van dentro de la página (los del PC).
        assertTrue(h.contains(".filtro-importante"))
        assertTrue(h.contains("data-filtro"))
    }

    @Test
    fun `la nota de voz trae lo importante a la pagina`() {
        val json = Json { ignoreUnknownKeys = true }
        val voz = json.decodeFromString(
            Mensaje.serializer(),
            """{"id":"v","cuando":1,"clase":"VOZ","nombre":"Clase.m4a","importantes":[{"ms":60000,"p":0.81,"tipo":"decision"}]}"""
        )
        assertEquals(Clase.VOZ, voz.clase)
        val d = ClaseExportar.deLaNota(voz, listOf(voz), listOf(60_000L to "Queda aprobado"), "") { null }
        assertEquals(listOf(Importantes.Tipo.DECISION), d.importantes.map { it.tipo })
        // Sin la clave, nada.
        assertTrue(ClaseExportar.deLaNota(voz.copy(importantes = null), listOf(voz), emptyList(), "") { null }.importantes.isEmpty())
        assertTrue(ClaseExportar.deLaNota(voz.copy(importantes = JsonArray(emptyList())), listOf(voz), emptyList(), "") { null }.importantes.isEmpty())
    }
}
