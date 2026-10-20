package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La selección por turno da lo mismo que el PC**: 100 pedidos (los 42 del banco del PC y 58
 * más de tareas, documentos, edición, estudiantes, ayuda y charla) pasados por
 * `seleccion::para_el_pedido` del PC de verdad (main 1984938, 20-oct-2026) y por la nuestra.
 */
class SeleccionTest {
    private val casos = javaClass.getResource("/ia/seleccion-del-pc.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it) as JsonObject }

    @Test fun `con el catalogo entero elige lo mismo que el PC`() {
        assertEquals(100, casos.size)
        for (c in casos) {
            val pedido = c["pedido"]!!.jsonPrimitive.content
            val imagen = c["imagen"]!!.jsonPrimitive.boolean
            val delPc = (c["herramientas"] as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(pedido, delPc, Seleccion.paraElPedido(pedido, imagen).map { it.nombre })
            assertEquals("mejor vista: $pedido", c["mejor_vista"]!!.jsonPrimitive.boolean, Seleccion.pideMejorVista(pedido, imagen))
        }
    }

    @Test fun `en el telefono solo salen las que sabe hacer, como mucho 6`() {
        for (c in casos) {
            val pedido = c["pedido"]!!.jsonPrimitive.content
            val elegidas = Herramientas.paraElPedido(pedido, c["imagen"]!!.jsonPrimitive.boolean)
            assertTrue(pedido, elegidas.size <= Seleccion.MAXIMO)
            assertTrue(pedido, elegidas.all { it.nombre in Herramientas.DISPONIBLES })
        }
        assertTrue(Herramientas.paraElPedido("hola").isEmpty())
        assertEquals(listOf(Herramientas.AYUDA), Herramientas.paraElPedido("¿Cómo hago una captura?").map { it.nombre })
        val tareas = Herramientas.paraElPedido("Apunta comprar pan mañana a las 10").map { it.nombre }
        assertTrue(tareas.toString(), "anadir_tarea" in tareas)
        // Lo de Windows no sale aunque se pida.
        assertFalse(Herramientas.paraElPedido("Sincroniza con el móvil").any { it.nombre == "sincronizar_movil" })
    }
}
