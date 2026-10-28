package com.forge.pixpin.jev

import com.forge.pixpin.anki.AnkiDelMensaje
import com.forge.pixpin.anki.Tarjetas
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Importantes
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.MotorGuionado
import com.forge.pixpin.ia.Nube
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.Respuesta
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.mini.ChapitasDeTarea
import com.forge.pixpin.mini.TareaInteligente
import com.forge.pixpin.mini.TodasLasTareas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Dónde se engancha Jev en la app** (28-oct-2026): lo puro de [ReunionEnLaApp], Anki con y sin Jev
 * (`tarjetas.rs` del PC, `a4974705`), la prioridad en Tareas y su chapita (`fa4ca12b`) y el motor que
 * elige su modelo (`e51da395`). Sin red ni Android.
 */
class JevEnLaAppTest {

    // --- Lo importante de la clase ------------------------------------------------------------

    private fun voz(transcripcion: String, importantes: JsonArray? = null) =
        Mensaje(id = "v", cuando = 1, clase = Clase.VOZ, transcripcion = transcripcion, importantes = importantes)

    @Test
    fun `los renglones son los de estudiar la clase`() {
        val r = ReunionEnLaApp.renglonesDe(voz("[0:05] Hola a todos\n\n[1:05] Para el lunes el **ejercicio 3**\n\nsin minuto"))
        assertEquals(listOf(5_000L, 65_000L, null), r.map { it.ms })
        assertEquals("Para el lunes el ejercicio 3", r[1].texto)
    }

    @Test
    fun `volver a mirarla lo rehace entero y lo apuntado sigue marcado`() {
        val antes = Importantes.poner(listOf(Importantes.Importante(65_000, Importantes.Tipo.TAREA, 0.9, enTareas = true)), "jev-1.13.0").first
        val m = voz("[1:05] Para el lunes el ejercicio 3", antes)
        val hallado = listOf(
            Reunion.Hallazgo(65_000, Reunion.Tipo.TAREA, 0.94, true, null),
            Reunion.Hallazgo(65_000, Reunion.Tipo.EXAMEN, 0.8, null, null)
        ).map(ReunionEnLaApp::aGuardar)
        val nueva = ReunionEnLaApp.escribirSobre(m, m.transcripcion, hallado, "jev-1.14.0")!!
        assertEquals("jev-1.14.0", nueva.importantesDe)
        val v = Importantes.de(nueva)!!
        assertEquals(listOf(Importantes.Tipo.TAREA, Importantes.Tipo.EXAMEN), v.map { it.tipo })
        assertTrue(v[0].enTareas)
        assertEquals(true, v[0].quien)
        // Si el texto cambió entretanto (otra vuelta de pasar a texto), no se toca.
        assertNull(ReunionEnLaApp.escribirSobre(m.copy(transcripcion = "[0:00] otra"), m.transcripcion, hallado, "jev"))
        // Nada hallado se guarda como `[]` («se miró y no hay nada»).
        assertEquals(JsonArray(emptyList()), ReunionEnLaApp.escribirSobre(voz("[0:00] x"), "[0:00] x", emptyList(), "jev")!!.importantes)
    }

    // --- Anki -----------------------------------------------------------------------------------

    @Test
    fun `lo dicho por captura y quedarse con las buenas`() {
        val trozos = listOf(0L to "Buenos días.", 10_000L to "La derivada mide el cambio.", 30_000L to "Ejemplo con una recta.")
        assertEquals(
            listOf("La derivada mide el cambio. Ejemplo con una recta.", "Ejemplo con una recta."),
            Tarjetas.loDichoPorCaptura(trozos, listOf(12_000, 31_000))
        )
        val t = { f: String -> Tarjetas.Tarjeta(f, "") }
        assertEquals(listOf("a", "c"), Tarjetas.quedarseCon(listOf(t("a"), t("b"), t("c")), listOf(true, false, true)).map { it.frente })
        // Si no casan los largos, todas.
        assertEquals(2, Tarjetas.quedarseCon(listOf(t("a"), t("b")), listOf(false)).size)
    }

    private fun clase(): Pair<Mensaje, List<Mensaje>> {
        val capturas = JsonArray(listOf(1_000L to "c1", 20_000L to "c2", 40_000L to "c3").map { (ms, id) ->
            JsonObject(mapOf("ms" to JsonPrimitive(ms), "id" to JsonPrimitive(id)))
        })
        val v = Mensaje(
            id = "v", cuando = 1, clase = Clase.VOZ, capturas = capturas, grabacion = "clase",
            transcripcion = "[0:00] Buenos días a todos\n\n[0:20] La derivada es el límite del cociente\n\n[0:40] Bueno, nos vemos"
        )
        val fotos = listOf("c1", "c2", "c3").map { Mensaje(id = it, cuando = 2, clase = Clase.IMAGEN, ruta = "/x/$it.png") }
        return v to (listOf(v) + fotos)
    }

    @Test
    fun `sin jev van todas las tarjetas y con jev solo las que valen`() {
        val (v, todos) = clase()
        val (todas, quitadas0) = AnkiDelMensaje.tarjetasYQuitadas(v, todos, "Cálculo", "Clase", { true }, null)
        assertEquals(3, todas.size)
        assertEquals(0, quitadas0)
        assertEquals(todas, AnkiDelMensaje.tarjetasDe(v, todos, "Cálculo", "Clase") { true })
        var visto: List<Pair<Long, String>> = emptyList()
        val (unas, quitadas) = AnkiDelMensaje.tarjetasYQuitadas(v, todos, "Cálculo", "Clase", { true }) { tramos ->
            visto = tramos
            listOf(false, true, false)
        }
        assertEquals(listOf(1_000L, 20_000L, 40_000L), visto.map { it.first })
        assertEquals("La derivada es el límite del cociente", visto[1].second)
        assertEquals(1, unas.size)
        assertEquals(2, quitadas)
        assertTrue(unas[0].frente.contains("pixpin-c2.png"))
        // Si Jev falla (null), todas, como siempre.
        assertEquals(3, AnkiDelMensaje.tarjetasYQuitadas(v, todos, "Cálculo", "Clase", { true }) { null }.first.size)
    }

    // --- Tareas -------------------------------------------------------------------------------

    @Test
    fun `la marca y el texto que se manda`() {
        assertEquals(Urgencia.Marca.MUY_URGENTE, UrgenciaEnLaApp.marcaDe(TareaInteligente.Urgencia.MUY_URGENTE))
        assertEquals(Urgencia.Marca.NINGUNA, UrgenciaEnLaApp.marcaDe(TareaInteligente.Urgencia.NORMAL))
        val m = TareaInteligente.marcas("llamar al banco 🔼 #casa 💬 cierran a las 3")
        assertEquals("llamar al banco (cierran a las 3)", UrgenciaEnLaApp.textoParaJev(m))
    }

    @Test
    fun `los recientes del chat son las ultimas notas escritas`() {
        val ms = (1..8).map { Mensaje(id = "n$it", cuando = it.toLong(), clase = Clase.NOTA, texto = "nota $it", proyecto = "p") } +
            Mensaje(id = "otro", cuando = 99, clase = Clase.NOTA, texto = "de otro chat", proyecto = "q") +
            Mensaje(id = "foto", cuando = 100, clase = Clase.IMAGEN, texto = "una foto", ruta = "/x.png", proyecto = "p")
        assertEquals((4..8).map { "nota $it" }, UrgenciaEnLaApp.recientesDelChat(ms, "p"))
    }

    @Test
    fun `la prioridad ordena lo pendiente y sin jev el orden es el de siempre`() {
        val filas = TodasLasTareas.filasDe("- [ ] a ➕ 2026-10-01\n- [ ] b ➕ 2026-10-02\n- [ ] c")
        assertEquals(listOf("a", "b", "c"), filas.sortedWith(TodasLasTareas.ORDEN_DE_PENDIENTE).map { it.marcas.titulo })
        val con = listOf(
            filas[0].copy(prioridad = Urgencia.Prioridad.BAJA),
            filas[1],
            filas[2].copy(prioridad = Urgencia.Prioridad.URGENTE)
        )
        assertEquals(listOf("c", "b", "a"), con.sortedWith(TodasLasTareas.ORDEN_DE_PENDIENTE).map { it.marcas.titulo })
    }

    @Test
    fun `la chapita de jev no pisa la marca del usuario`() {
        assertEquals("✦ Alta" to ChapitasDeTarea.AMBAR, ChapitasDeTarea.deJev(TareaInteligente.Urgencia.NORMAL, Urgencia.Prioridad.ALTA))
        assertEquals("✦ Urgente" to ChapitasDeTarea.ROJO, ChapitasDeTarea.deJev(TareaInteligente.Urgencia.NORMAL, Urgencia.Prioridad.URGENTE))
        assertEquals("✦ Baja" to ChapitasDeTarea.GRIS, ChapitasDeTarea.deJev(TareaInteligente.Urgencia.NORMAL, Urgencia.Prioridad.BAJA))
        // Marcada a mano: solo su marca. Normal, o sin Jev: como siempre.
        assertNull(ChapitasDeTarea.deJev(TareaInteligente.Urgencia.URGENTE, Urgencia.Prioridad.URGENTE))
        assertNull(ChapitasDeTarea.deJev(TareaInteligente.Urgencia.NORMAL, Urgencia.Prioridad.NORMAL))
        assertNull(ChapitasDeTarea.deJev(TareaInteligente.Urgencia.NORMAL, null))
    }

    // --- El modelo del asistente --------------------------------------------------------------

    private fun peticion() = Peticion(listOf(MensajeIa.usuario("hola")))

    @Test
    fun `el motor elige al primer pedido y una vez`() {
        val rapido = MotorGuionado(listOf(Respuesta.deTexto("rápido")))
        val fuerte = MotorGuionado(listOf(Respuesta.deTexto("fuerte"), Respuesta.deTexto("fuerte otra")))
        var preguntas = 0
        val rotulos = mutableListOf<String>()
        val m = ModeloEnLaApp.MotorSegunJev(rapido, fuerte, "Claude Haiku" to "Claude Sonnet", elegir = { preguntas++; Modelo.Ruta.FUERTE }, alElegir = { rotulos += it })
        assertEquals("fuerte", m.completar(peticion()) { Seguir.SI }.texto)
        assertEquals("fuerte otra", m.completar(peticion()) { Seguir.SI }.texto)
        assertEquals(1, preguntas)
        assertEquals(listOf("Claude Sonnet"), rotulos)
        assertTrue(rapido.recibido.isEmpty())
    }

    @Test
    fun `si jev falla el de siempre`() {
        val rapido = MotorGuionado(listOf(Respuesta.deTexto("rápido")))
        val fuerte = MotorGuionado(emptyList())
        val rotulos = mutableListOf<String>()
        val m = ModeloEnLaApp.MotorSegunJev(rapido, fuerte, "DeepSeek Flash" to "DeepSeek V4 Pro", elegir = { throw ErrorJev.Red("sin red") }, alElegir = { rotulos += it })
        assertEquals("rápido", m.completar(peticion()) { Seguir.SI }.texto)
        assertSame(rapido, m.elegido())
        // Un fallo no se anuncia en la pastilla.
        assertTrue(rotulos.isEmpty())
    }

    @Test
    fun `el fuerte de cada proveedor y su modelo en el cuerpo`() {
        assertEquals("claude-sonnet-5-5" to "Claude Sonnet", ModeloEnLaApp.fuerteDe(Nube.Proveedor.CLAUDE))
        assertEquals("deepseek-v4-pro" to "DeepSeek V4 Pro", ModeloEnLaApp.fuerteDe(Nube.Proveedor.DEEPSEEK))
        val p = peticion()
        val claude = Nube.Motor(Nube.Proveedor.CLAUDE, "sk-ant-api03-CLAVEFALSA0123456789", modelo = "claude-sonnet-5-5")
        assertEquals("claude-sonnet-5-5", claude.cuerpo(p)["model"]!!.jsonPrimitive.content)
        val deSiempre = Nube.Motor(Nube.Proveedor.DEEPSEEK, "sk-deepseekfalsa0123456789abcdef")
        assertEquals(Nube.Proveedor.DEEPSEEK.modelo, deSiempre.cuerpo(p)["model"]!!.jsonPrimitive.content)
        val pro = Nube.Motor(Nube.Proveedor.DEEPSEEK, "sk-deepseekfalsa0123456789abcdef", modelo = "deepseek-v4-pro")
        // Lo demás del cuerpo, igual: solo cambia el modelo.
        assertEquals(deSiempre.cuerpo(p).keys.toList(), pro.cuerpo(p).keys.toList())
        assertEquals("deepseek-v4-pro", pro.cuerpo(p)["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la conversacion y los recientes con quien los dijo`() {
        val pregunta = Mensaje(id = "q", cuando = 10, clase = Clase.NOTA, texto = "explícame la entropía", proyecto = "a")
        val ms = listOf(
            Mensaje(id = "1", cuando = 1, clase = Clase.NOTA, texto = "hola", proyecto = "a"),
            Mensaje(id = "2", cuando = 2, clase = Clase.NOTA, texto = "¿en qué te ayudo?", proyecto = "a", aparato = "IA"),
            Mensaje(id = "3", cuando = 3, clase = Clase.NOTA, texto = "otra cosa", proyecto = "b"),
            pregunta
        )
        val c = ModeloEnLaApp.conversacion(ms, pregunta)
        assertEquals(listOf(true to "hola", false to "¿en qué te ayudo?"), c)
        val r = ModeloEnLaApp.recientes((1..5).map { (it % 2 == 1) to "m$it" })
        assertEquals(Modelo.RECIENTES, r.size)
        assertEquals("usuario" to "m3", r[0])
        assertEquals("asistente", r[1].first)
    }

    @Test
    fun `la pastilla dice lo elegido solo un rato`() {
        assertNull(ModeloEnLaApp.rotulo("nadie"))
    }
}
