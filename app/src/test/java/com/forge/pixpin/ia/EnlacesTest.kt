package com.forge.pixpin.ia

import com.forge.pixpin.asistente.EnlacesDelTelefono
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Los pedidos de los enlaces `pixpin://`** (PC `orden.rs`): cada pedido que arma el PC encuentra en la
 * tabla del teléfono la fila que lo hace, por su `accion` y sus campos fijos; lo prohibido no se hace
 * nunca y lo que pide confirmación no se hace sin el «Sí».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnlacesTest {
    private val e get() = EnlacesDelTelefono(RuntimeEnvironment.getApplication())
    private fun j(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `cada pedido de los verbos del PC tiene su fila`() {
        val casos = mapOf(
            """{"pixpin":1,"accion":"capturar"}""" to "capturar_pantalla",
            """{"pixpin":1,"accion":"chat","texto":"hola","proyecto":null}""" to "escribir_en_chat",
            """{"pixpin":1,"accion":"abrir","que":{"tipo":"fichero","ruta":"/sdcard/a.pdf"}}""" to "abrir_fichero",
            """{"pixpin":1,"accion":"abrir","que":{"tipo":"hoja","proyecto":null,"referencia":"dib-1"}}""" to "abrir_hoja",
            """{"pixpin":1,"accion":"ventana","cual":"tareas"}""" to "abrir_ventana",
            """{"pixpin":1,"accion":"nota_nueva","texto":"x","proyecto":null}""" to "nota_nueva",
            """{"pixpin":1,"accion":"lienzo_nuevo","nombre":"Plano","proyecto":null}""" to "lienzo_nuevo",
            """{"pixpin":1,"accion":"anadir_tarea","texto":"pan"}""" to "anadir_tarea"
        )
        for ((pedido, fila) in casos) assertEquals(pedido, fila, e.filaDe(j(pedido))?.herramienta)
        // Caso negativo: una acción que el teléfono no conoce no tiene fila.
        assertNull(e.filaDe(j("""{"pixpin":1,"accion":"no_existe"}""")))
    }

    @Test fun `lo prohibido no se hace y lo que pregunta necesita el si`() {
        val prohibido = EnlacesDelTelefono.Pedido(j("""{"pixpin":1,"accion":"abrir","que":{"tipo":"fichero","ruta":"\\\\servidor\\x"}}"""), "prohibida:ruta-no-local", "")
        assertTrue(e.hacer(prohibido, confirmado = true).exceptionOrNull()!!.message!!.contains("nunca"))
        val pregunta = EnlacesDelTelefono.Pedido(j("""{"pixpin":1,"accion":"sincronizar","aparato":"todos"}"""), "confirmar:otros-aparatos", "")
        assertTrue(e.hacer(pregunta, confirmado = false).exceptionOrNull()!!.message!!.contains("falta que digas que sí"))
        assertEquals("manda o sincroniza con tus otros aparatos", e.motivo("confirmar:otros-aparatos"))
    }
}
