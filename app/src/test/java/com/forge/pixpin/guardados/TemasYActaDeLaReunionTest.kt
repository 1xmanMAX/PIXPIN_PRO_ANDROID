package com.forge.pixpin.guardados

import com.forge.pixpin.mini.Tareas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Los temas y el acta de la reunión en la nota** (29-oct-2026): las pruebas de `temas.rs` y
 * `acta_viva.rs` de `pixpin-proyecto` del PC (leer lo pegado y las listas, el formato, lo hecho a mano
 * manda, el acta de ida y vuelta, lo vacío no cambia la nota) y **la ida y vuelta de una línea escrita
 * por el PC**: se lee, se reescribe aquí (fijado) y los campos nuevos siguen igual.
 */
class TemasYActaDeLaReunionTest {

    private val T = TemasDeLaReunion

    // --- temas.rs ------------------------------------------------------------------------------

    @Test
    fun `lo pegado sale un tema por linea sin vinetas ni repetidos`() {
        val pegado = "# Agenda del lunes\n\n1. Presupuesto 2027\n2) Viaje a Lima:\n- [ ] Nuevo becario\n• presupuesto   2027\n   \n* Varios\n"
        assertEquals(listOf("Presupuesto 2027", "Viaje a Lima", "Nuevo becario", "Varios"), T.deTexto(pegado))
        // Un tema larguísimo se recorta, sin partir un carácter.
        val v = T.deTexto("ñ".repeat(500))
        assertEquals(T.LARGO_MAXIMO + 1, v[0].length)
        val muchos = (0 until 60).joinToString("") { "tema $it\n" }
        assertEquals(T.MAXIMO, T.deTexto(muchos).size)
        assertTrue(T.deTexto("").isEmpty())
    }

    @Test
    fun `de una lista de tareas sin fechas y con lo hecho`() {
        val doc = "# Reunion\n\n- [ ] Presupuesto 2027 ➕ 2026-10-27\n- [x] Viaje a Lima\n- [ ] \n"
        assertEquals(listOf("Presupuesto 2027" to false, "Viaje a Lima" to true), T.deDocumento(doc))
    }

    @Test
    fun `los ids son estables y no se reutilizan`() {
        val v = mutableListOf<TemasDeLaReunion.Tema>()
        assertEquals("t1", T.anadir(v, "Uno"))
        assertEquals("t2", T.anadir(v, "Dos"))
        assertNull("repetido", T.anadir(v, "  uno "))
        assertNull("vacío", T.anadir(v, "   "))
        assertTrue(T.quitar(v, "t2"))
        assertFalse(T.quitar(v, "t2"))
        assertEquals("t2", T.anadir(v, "Tres"))
        assertTrue(T.quitar(v, "t1"))
        assertEquals("t3", T.anadir(v, "Cuatro"))
        assertEquals(2, T.anadirVarios(v, listOf("Cinco", "Tres", "Seis")))
    }

    @Test
    fun `lo hecho a mano manda sobre jev`() {
        val v = mutableListOf(TemasDeLaReunion.Tema("t1", "A"), TemasDeLaReunion.Tema("t2", "B"))
        assertTrue(T.porJev(v, "t1", 60_000, 0.86))
        assertTrue(v[0].hecho && v[0].porJev && v[0].ms == 60_000L)
        // Y no lo vuelve a marcar (ni le cambia el minuto).
        assertFalse(T.porJev(v, "t1", 90_000, 0.99))
        assertEquals(60_000L, v[0].ms)
        // La persona lo desmarca: Jev ya no lo toca.
        assertTrue(T.aMano(v, "t1", false, null))
        assertTrue(!v[0].hecho && v[0].aMano && !v[0].porJev && v[0].p == null)
        assertFalse(T.porJev(v, "t1", 120_000, 0.95))
        assertFalse(v[0].hecho)
        // Marcado a mano antes que Jev: Jev no lo cambia.
        assertTrue(T.aMano(v, "t2", true, 30_000))
        assertFalse(T.porJev(v, "t2", 40_000, 0.9))
        assertEquals(30_000L to false, v[1].ms to v[1].porJev)
        assertTrue(T.paraJev(v).isEmpty())
        assertEquals(1 to 2, T.cuenta(v))
        assertEquals(1, T.sinTratar(v).size)
        assertFalse(T.aMano(v, "t9", true, null))
        assertFalse(T.porJev(v, "t9", 0, 1.0))
    }

    @Test
    fun `van y vuelven y sin temas la clave se quita`() {
        val v = mutableListOf(TemasDeLaReunion.Tema("t1", "Presupuesto"), TemasDeLaReunion.Tema("t2", "Viaje"))
        T.porJev(v, "t1", 750_000, 0.8649)
        val j = T.aJson(v)!!
        val leidos = T.temasDe(j)
        assertEquals(0.86, leidos[0].p!!, 1e-9)
        assertEquals(750_000L, leidos[0].ms)
        assertEquals(v[1], leidos[1])
        val texto = j.toString()
        assertTrue(texto.contains("\"porJev\":true") && texto.contains("\"aMano\":false"))
        assertNull(T.aJson(emptyList()))
        // Lo que no se entiende se salta.
        val raro = Json.parseToJsonElement("""[{"id": "t1"}, {"texto": "x"}, {"id": "t2", "texto": "ok", "hecho": true}]""") as JsonArray
        val w = T.temasDe(raro)
        assertEquals(1, w.size)
        assertTrue(w[0].hecho && !w[0].aMano)
        assertEquals("p1" to "m7", T.listaDe(T.listaEnJson("p1", "m7")))
    }

    @Test
    fun `la lista de tareas dice lo mismo que los temas`() {
        val v = mutableListOf(TemasDeLaReunion.Tema("t1", "Presupuesto"), TemasDeLaReunion.Tema("t2", "Viaje"), TemasDeLaReunion.Tema("t3", "Varios"))
        val doc = T.aDocumento("Temas · Reunion", v)
        assertEquals("# Temas · Reunion\n\n- [ ] Presupuesto\n- [ ] Viaje\n- [ ] Varios", doc)
        assertEquals(3, T.deDocumento(doc).size)
        // En Tareas marcaron «Viaje»: entra como hecho a mano.
        val marcada = Tareas.escribir("Temas · Reunion", Tareas.alternar(Tareas.leer(doc), 1))
        assertEquals(1, T.conLaLista(v, marcada))
        assertTrue(v[1].hecho && v[1].aMano)
        // Jev trató el primero: la lista se marca allí (la que ya lo estaba no se toca).
        T.porJev(v, "t1", 0, 0.9)
        assertEquals(listOf(0 to true), T.marcasParaLaLista(v, marcada))
    }

    // --- acta_viva.rs --------------------------------------------------------------------------

    private fun acta() = ActaViva.Acta(
        decisiones = listOf(ActaViva.Dicho(125_000, "adelantamos el pedido")),
        preguntasAbiertas = listOf(ActaViva.Dicho(300_000, "¿quién paga el autobús?")),
        tiempoPorTema = ActaViva.TiempoPorTema(listOf(ActaViva.TiempoDeTema("t1", 360_000)), 120_000),
        propuestas = listOf(
            ActaViva.Propuesta("p1", ActaViva.Tipo.TAREA, 90_000, "yo mando la cotización el viernes", fecha = "2026-10-30", prioridad = "normal", estado = ActaViva.Estado.ACEPTADA)
        ),
        cambios = listOf(ActaViva.CambioDeUrgencia(400_000, "Pagar al proveedor", "normal", "urgente", fechaDespues = "2026-10-29", deshecho = true))
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun voz() = Mensaje(id = "v", cuando = 1, clase = Clase.VOZ)

    @Test
    fun `el acta va y vuelve con los nombres del pc`() {
        val m = ActaViva.poner(voz(), acta())
        val j = json.encodeToString(Mensaje.serializer(), m)
        for (k in listOf("\"preguntasAbiertas\"", "\"tiempoPorTema\"", "\"fechaDespues\"", "\"estado\":\"aceptada\"", "\"tipo\":\"tarea\"", "\"deshecho\":true")) {
            assertTrue("$k en $j", j.contains(k))
        }
        // Lo que no se sabe no sale.
        assertFalse(j, j.contains("responsable"))
        assertEquals(acta(), ActaViva.de(json.decodeFromString(Mensaje.serializer(), j)))
        assertEquals(360_000L, acta().tiempoPorTema!!.de("t1"))
    }

    @Test
    fun `sin nada la nota no cambia y lo raro se salta`() {
        val antes = json.encodeToString(Mensaje.serializer(), voz())
        val despues = json.encodeToString(Mensaje.serializer(), ActaViva.poner(voz(), ActaViva.Acta()))
        assertEquals(antes, despues)
        assertTrue(ActaViva.de(voz()).vacia())
        val linea = """{"id":"v","cuando":1,"clase":"VOZ","decisiones": [{"ms": 1, "texto": "sí"}, {"texto": 3}],
            "propuestas": [{"id": "p1", "tipo": "evento", "ms": 5, "texto": "nos vemos"}],
            "tiempoPorTema": {"temas": [{"id": "t1", "ms": -4}]}}"""
        val a = ActaViva.de(json.decodeFromString(Mensaje.serializer(), linea))
        assertEquals(1, a.decisiones.size)
        assertEquals(ActaViva.Estado.PENDIENTE, a.propuestas[0].estado)
        assertEquals(1, a.pendientes().size)
        assertEquals(0L, a.tiempoPorTema!!.temas[0].ms)
    }

    @Test
    fun `la duracion corta`() {
        assertEquals("44 s", ActaViva.duracionCorta(44_000))
        assertEquals("1 min", ActaViva.duracionCorta(60_000))
        assertEquals("6 min", ActaViva.duracionCorta(359_000))
        assertEquals("0 s", ActaViva.duracionCorta(-3))
    }

    // --- La línea del PC, de ida y vuelta ------------------------------------------------------

    /** Una nota de voz como la escribe el PC (el orden de claves de `serde_json`, alfabético). */
    private val LINEA_DEL_PC = """{"aparato":"PC01","cambiosDeUrgencia":[{"antes":"normal","deshecho":false,"despues":"urgente","fechaAntes":"2026-11-05","fechaDespues":"2026-10-29","ms":400000,"tarea":"Pagar al proveedor"}],"clase":"VOZ","cuando":1761730000000,"decisiones":[{"ms":125000,"texto":"queda decidido: adelantamos el pedido"}],"duracionMs":900000,"grabacion":"clase","id":"nota-del-pc","nombre":"Clase 29-10 10h05.m4a","preguntasAbiertas":[{"ms":300000,"texto":"¿quién paga el autobús?"}],"propuestas":[{"estado":"pendiente","fecha":"2026-10-30","hora":"15:00","id":"p1","ms":90000,"prioridad":"alta","responsable":"Luis","texto":"yo mando la cotización el viernes","tipo":"tarea"},{"estado":"aceptada","fecha":"2026-11-03","hora":"15:00","id":"p2","ms":95000,"texto":"nos vemos el martes a las 3","tipo":"evento"}],"temas":[{"aMano":false,"hecho":true,"id":"t1","ms":750000,"p":0.86,"porJev":true,"texto":"Presupuesto 2027"},{"aMano":true,"hecho":true,"id":"t2","ms":1210000,"porJev":false,"texto":"Nuevo becario"},{"aMano":false,"hecho":false,"id":"t3","porJev":false,"texto":"Viaje a Lima"}],"temasLista":{"id":"lista-1","proyecto":"general"},"tiempoPorTema":{"otro":120000,"temas":[{"id":"t1","ms":360000},{"id":"t2","ms":45000}]}}"""

    @Test
    fun `una nota escrita por el pc se lee entera y sobrevive a reescribirla aqui`() {
        val m = json.decodeFromString(Mensaje.serializer(), LINEA_DEL_PC)
        val temas = TemasDeLaReunion.temasDe(m)
        assertEquals(listOf("t1", "t2", "t3"), temas.map { it.id })
        assertTrue(temas[0].porJev && temas[1].aMano && !temas[2].hecho)
        assertEquals(0.86, temas[0].p!!, 1e-9)
        assertEquals("general" to "lista-1", TemasDeLaReunion.listaDe(m.temasLista))
        val a = ActaViva.de(m)
        assertEquals(1, a.decisiones.size)
        assertEquals(1, a.preguntasAbiertas.size)
        assertEquals(45_000L, a.tiempoPorTema!!.de("t2"))
        assertEquals(120_000L, a.tiempoPorTema!!.otro)
        assertEquals(listOf("p1"), a.pendientes().map { it.id })
        assertEquals("Luis", a.propuestas[0].responsable)
        assertEquals(ActaViva.Tipo.EVENTO, a.propuestas[1].tipo)
        assertEquals("2026-11-05", a.cambios[0].fechaAntes)
        // Reescrita aquí (fijada): los campos nuevos siguen igual, valor a valor.
        val otra = json.decodeFromString(Mensaje.serializer(), json.encodeToString(Mensaje.serializer(), m.copy(fijado = true)))
        for (campo in listOf<(Mensaje) -> Any?>({ it.temas }, { it.temasLista }, { it.decisiones }, { it.preguntasAbiertas }, { it.tiempoPorTema }, { it.propuestas }, { it.cambiosDeUrgencia })) {
            assertEquals(campo(m), campo(otra))
        }
        assertEquals(a, ActaViva.de(otra))
        // Y tocar un tema a mano aquí lo escribe entero, con «aMano».
        val v = temas.toMutableList()
        assertTrue(TemasDeLaReunion.aMano(v, "t3", true, null))
        val escrita = json.encodeToString(Mensaje.serializer(), m.copy(temas = TemasDeLaReunion.aJson(v)))
        val t3 = TemasDeLaReunion.temasDe(json.decodeFromString(Mensaje.serializer(), escrita))[2]
        assertTrue(t3.hecho && t3.aMano && !t3.porJev)
        // Sin temas ni acta, la nota del PC de antes no lleva ninguna clave nueva.
        val vieja = json.encodeToString(Mensaje.serializer(), m.copy(temas = null, temasLista = null, decisiones = null, preguntasAbiertas = null, tiempoPorTema = null, propuestas = null, cambiosDeUrgencia = null))
        for (k in listOf("temas", "temasLista", "decisiones", "preguntasAbiertas", "tiempoPorTema", "propuestas", "cambiosDeUrgencia")) assertFalse(k, vieja.contains("\"$k\""))
    }

    @Test
    fun `el acta tolera lo que escriba otra version`() {
        val o = JsonObject(mapOf("temas" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("t1"), "ms" to JsonPrimitive(5)))))))
        assertEquals(5L, ActaViva.de(voz().copy(tiempoPorTema = o)).tiempoPorTema!!.de("t1"))
    }
}
