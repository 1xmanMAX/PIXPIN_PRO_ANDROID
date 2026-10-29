package com.forge.pixpin

import com.forge.pixpin.guardados.ActaViva
import com.forge.pixpin.guardados.ClaseExportar
import com.forge.pixpin.guardados.ClaseExportar.Datos
import com.forge.pixpin.guardados.ClaseExportar.Medio
import com.forge.pixpin.guardados.ClaseExportar.Rotulos
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.TemasDeLaReunion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La página compartida con lo de la reunión** (29-oct-2026): `la_agenda_va_con_su_estado` y
 * `lo_de_la_reunion_en_vivo_va_al_lado_con_su_minuto` de `clase_exportar/pruebas.rs` del PC, con las
 * mismas cadenas esperadas, y que la nota de voz los trae.
 */
class ClaseExportarReunionTest {

    private fun ejemplo() = Datos(
        titulo = "Física",
        duracionMs = 180_000,
        audio = "/x/clase.m4a",
        lineas = listOf(0L to "Hola", 60_000L to "Vamos con la ley de Ohm", 120_000L to "Y la potencia")
    )

    private val incrustar: (String) -> Medio? = { Medio.Incrustado("audio/mp4", "a".toByteArray()) }

    @Test
    fun `la agenda va con su estado`() {
        val t = mutableListOf(TemasDeLaReunion.Tema("t1", "Presupuesto <2027>"), TemasDeLaReunion.Tema("t2", "Viaje"))
        TemasDeLaReunion.porJev(t, "t1", 65_000, 0.9)
        var c = ejemplo().copy(temas = t)
        val h = ClaseExportar.html(c, Rotulos(), incrustar)
        assertTrue(h.contains("Temas de la reunión"))
        assertTrue(h.contains("1/2"))
        assertTrue(h.contains("Presupuesto &lt;2027&gt;"))
        assertTrue(h.contains("class=\"tema hecho\" data-ms=\"65000\""))
        assertTrue(h.contains("Sin tratar"))
        val md = ClaseExportar.markdown(c, Rotulos()) { null }
        assertTrue(md, md.contains("- [x] Presupuesto"))
        assertTrue(md, md.contains("· 1:05"))
        assertTrue(md, md.contains("- [ ] Viaje — *Sin tratar*"))
        // Sin temas, la página es la de siempre.
        c = c.copy(temas = emptyList())
        assertFalse(ClaseExportar.html(c, Rotulos(), incrustar).contains("lista-temas\""))
    }

    @Test
    fun `lo de la reunion en vivo va al lado con su minuto`() {
        val c = ejemplo().copy(
            temas = listOf(TemasDeLaReunion.Tema("t1", "Paro"), TemasDeLaReunion.Tema("t2", "Viaje")),
            acta = ActaViva.Acta(
                decisiones = listOf(ActaViva.Dicho(125_000, "adelantamos <el> pedido")),
                preguntasAbiertas = listOf(ActaViva.Dicho(300_000, "¿quién paga?")),
                tiempoPorTema = ActaViva.TiempoPorTema(listOf(ActaViva.TiempoDeTema("t1", 359_000)), 0)
            )
        )
        val h = ClaseExportar.html(c, Rotulos(), incrustar)
        assertTrue(h.contains("Decisiones <span class=\"cuenta\">1</span>"))
        assertTrue(h.contains("data-ms=\"125000\"><a class=\"t\" href=\"#t-125000\""))
        assertTrue(h.contains("adelantamos &lt;el&gt; pedido"))
        assertTrue(h.contains("Preguntas sin respuesta"))
        assertTrue(h.contains("<span class=\"duracion\">6 min</span>"))
        // El tema sin minutos contados no dice nada.
        assertEquals(1, Regex("class=\"duracion\"").findAll(h).count())
        // El estilo de la agenda va dentro (el `pagina.css` del PC).
        assertTrue(h.contains(".lista-temas"))
        val md = ClaseExportar.markdown(c, Rotulos()) { null }
        assertTrue(md, md.contains("## Decisiones"))
        assertTrue(md, md.contains("- 2:05 · adelantamos \\<el\\> pedido"))
        assertTrue(md, md.contains("- [ ] Paro (6 min)"))
    }

    @Test
    fun `la nota de voz trae los temas y el acta a la pagina`() {
        val json = Json { ignoreUnknownKeys = true }
        val voz = json.decodeFromString(
            Mensaje.serializer(),
            """{"id":"v","cuando":1,"clase":"VOZ","nombre":"Clase.m4a","temas":[{"id":"t1","texto":"Paro","hecho":true,"ms":5000,"porJev":false,"aMano":true}],"decisiones":[{"ms":1000,"texto":"sí"}]}"""
        )
        val d = ClaseExportar.deLaNota(voz, listOf(voz), emptyList(), "") { null }
        assertEquals(listOf("t1"), d.temas.map { it.id })
        assertEquals(1, d.acta.decisiones.size)
    }
}
