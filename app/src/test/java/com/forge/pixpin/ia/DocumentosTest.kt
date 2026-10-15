package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **Buscar en mis cosas da lo mismo que el PC**: el mismo corpus (6 fuentes, una rota, un chat con
 * un adjunto) y 14 consultas pasadas por el `Indice` del PC de verdad (main 882d465) y por el
 * nuestro: mismos trozos, en el mismo orden, mismos fragmentos, mismo texto para el modelo y mismas
 * citas; los puntos, con tolerancia de coma flotante. Y las pruebas de `texto.rs`.
 */
class DocumentosTest {
    @get:Rule val tmp = TemporaryFolder()

    private val entrada = Json.parseToJsonElement(javaClass.getResource("/ia/bm25-entrada.json")!!.readText()).jsonObject
    private val delPc = Json.parseToJsonElement(javaClass.getResource("/ia/bm25-del-pc.json")!!.readText()).jsonArray

    private fun fuentes() = entrada["fuentes"]!!.jsonArray.map { it.jsonObject }.map { x ->
        fun s(k: String) = x[k]!!.jsonPrimitive.content
        Documentos.Fuente(s("clave"), s("proyecto"), s("proyectoNombre"), s("nombre"), Documentos.Tipo.deClave(s("tipo"))!!, s("ruta"),
            Documentos.Firma(1, 1), Documentos.Abrir.Fichero(s("ruta"), null))
    }

    private val lector = Documentos.Lector { f ->
        val x = entrada["fuentes"]!!.jsonArray.map { it.jsonObject }.first { it["clave"]!!.jsonPrimitive.content == f.clave }
        (x["error"] as? JsonPrimitive)?.let { error(it.content) }
        x["unidades"]!!.jsonArray.map { it.jsonObject }.map { u ->
            Documentos.Unidad(
                u["texto"]!!.jsonPrimitive.content, (u["pagina"] as? JsonPrimitive)?.intOrNull, (u["seccion"] as? JsonPrimitive)?.content,
                (u["codigo"] as? JsonPrimitive)?.content?.let { Documentos.Abrir.Mensaje(x["proyecto"]!!.jsonPrimitive.content, it) },
                (u["cuando"] as? JsonPrimitive)?.longOrNull
            )
        }
    }

    private fun canon(e: kotlinx.serialization.json.JsonElement) = ordenado(e).toString()

    @Test fun `las mismas busquedas dan lo mismo que el PC`() {
        val ix = Documentos.Indice(null)
        val inf = ix.ponerAlDia(fuentes(), lector)
        assertEquals(6, inf.leidas); assertEquals(1, inf.rotas.size)
        for ((i, c) in entrada["consultas"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
            val consulta = c["consulta"]!!.jsonPrimitive.content
            val pc = delPc[i].jsonObject
            assertEquals(consulta, pc["palabras"]!!.jsonArray.map { it.jsonPrimitive.content }, Documentos.deLaConsulta(consulta))
            val alcance = (c["solo"] as? JsonPrimitive)?.let { Documentos.Alcance.solo(it.content) } ?: Documentos.Alcance.TODO
            val h = ix.buscar(consulta, alcance)
            val esperados = pc["hallazgos"]!!.jsonArray.map { it.jsonObject }
            assertEquals(consulta, esperados.size, h.size)
            for ((a, e) in h.zip(esperados)) {
                assertEquals(consulta, e["nombre"]!!.jsonPrimitive.content, a.nombre)
                assertEquals(consulta, (e["pagina"] as? JsonPrimitive)?.intOrNull, a.pagina)
                assertEquals(consulta, e["fragmento"]!!.jsonPrimitive.content, a.fragmento)
                assertEquals(consulta, canon(e["pedido"]!!), canon(a.abrir.pedido()))
                assertEquals(consulta, e["puntos"]!!.jsonPrimitive.float, a.puntos, 1e-4f * maxOf(1f, a.puntos))
            }
            val citas = Documentos.Citas()
            assertEquals(consulta, pc["modelo"]!!.jsonPrimitive.content, citas.anadir(consulta, h))
            assertEquals(consulta, canon(pc["citas"]!!), canon(JsonArray(citas.lista.map { it.aJson() })))
        }
    }

    @Test fun `el indice se guarda, se carga y solo relee lo que cambio`() {
        val dir = tmp.newFolder("documentos")
        val ix = Documentos.Indice(dir)
        ix.ponerAlDia(fuentes(), lector)
        val cargado = Documentos.Indice.cargar(dir)
        assertEquals(ix.tamano, cargado.tamano)
        assertEquals(ix.buscar("cuneta km 12", Documentos.Alcance.TODO).map { it.fragmento },
            cargado.buscar("cuneta km 12", Documentos.Alcance.TODO).map { it.fragmento })
        var leidas = 0
        val contando = Documentos.Lector { leidas++; lector.leer(it) }
        val otra = fuentes().map { if (it.nombre == "Tesis.pdf") it.copy(firma = Documentos.Firma(2, 2)) else it }.filter { it.nombre != "Presupuesto.xlsx" }
        val inf = cargado.ponerAlDia(otra, contando)
        assertEquals(1, leidas); assertEquals(1, inf.quitadas)
        assertTrue(cargado.buscar("excavación partida", Documentos.Alcance.TODO).none { it.nombre == "Presupuesto.xlsx" })
    }

    @Test fun `plurales tildes y mayusculas dan lo mismo`() {
        assertEquals(Documentos.palabras("cuneta"), Documentos.palabras("Cunetas"))
        assertEquals(Documentos.palabras("drenaje"), Documentos.palabras("DRENAJES"))
        assertEquals(Documentos.palabras("diseno"), Documentos.palabras("Diseño"))
        assertEquals(Documentos.palabras("pavimentacion"), Documentos.palabras("Pavimentación"))
        assertEquals(listOf("plan", "cunet", "v2", "pdf"), Documentos.palabras("plano_cunetas-v2.pdf"))
        assertEquals(listOf("tesi", "cunet"), Documentos.deLaConsulta("¿Qué dice la tesis sobre las cunetas?"))
        assertEquals(listOf("not", "presupuest"), Documentos.deLaConsulta("busca en mis notas el presupuesto"))
        assertTrue(Documentos.deLaConsulta("   ").isEmpty()); assertTrue(Documentos.deLaConsulta("¿qué dice?").isEmpty())
        assertEquals(listOf("capitul", "2", "tabl", "4", "1"), Documentos.palabras("capítulo 2, tabla 4.1"))
    }

    @Test fun `valida la consulta y la fecha corta`() {
        fun v(s: String) = Documentos.validar(Json.parseToJsonElement(s) as JsonObject)
        assertEquals(Documentos.PeticionBusqueda("cunetas pendiente", "Obra"), v("""{"consulta": " cunetas pendiente ", "proyecto": "Obra"}"""))
        assertNull(v("""{"query": "plano", "proyecto": "todos"}""").proyecto)
        for (mal in listOf("{}", """{"consulta": "   "}""", """{"consulta": "¿qué dice de la?"}""")) assertTrue(mal, runCatching { v(mal) }.isFailure)
        assertNull(Documentos.fechaCorta(0))
        assertEquals("3-oct-2026", Documentos.fechaCorta(1_790_985_600_000))
        assertEquals("3-oct-2026", Documentos.fechaCorta(1_790_985_600_000 + 86_399_999))
    }
}
