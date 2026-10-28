package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * **Una reunión del PC, reescrita por el chat de aquí** (28-oct-2026): las líneas tal cual las deja
 * la sincronización en `guardados.jsonl`, y el chat que fija la nota y anota una foto
 * ([MensajesStore.actualizar] reescribe el archivo entero). `clips`, `fuente` y
 * `enGrabacion.duracionMs` tienen que seguir ahí, byte a byte, y la línea antigua con `origen`
 * repetido no se pierde.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresencialEnElAlmacenTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `lo del PC sobrevive a que el movil reescriba el chat`() {
        val lineas = listOf(
            """{"id":"voz","cuando":1,"clase":"VOZ","nombre":"Reunión.m4a","duracionMs":120000,"proyecto":"obra","fijado":false,"origen":null,"capturas":[{"id":"foto1","ms":5000}],"clips":[{"duracionMs":12000,"id":"clip1","ms":30000}],"grabacion":"clase"}""",
            """{"id":"foto1","cuando":2,"clase":"IMAGEN","nombre":"foto-1-1.jpg","proyecto":"obra","origen":null,"enGrabacion":{"de":"voz","ms":5000},"fuente":"camara"}""",
            """{"id":"clip1","cuando":3,"clase":"ARCHIVO","nombre":"Reunión · clip 1.mp4","proyecto":"obra","origen":null,"enGrabacion":{"de":"voz","duracionMs":12000,"ms":30000},"fuente":"camara"}""",
            // La de la primera versión del PC: `origen` dos veces.
            """{"id":"vieja","cuando":4,"clase":"IMAGEN","nombre":"foto-0-1.jpg","proyecto":"obra","aparato":null,"origen":null,"origen":"camara","enGrabacion":{"de":"voz","ms":9000}}"""
        )
        File(context.filesDir, "guardados.jsonl").writeText(lineas.joinToString("\n", postfix = "\n"))
        val almacen = MensajesStore(context)
        assertEquals("ninguna línea se pierde", listOf("voz", "foto1", "clip1", "vieja"), almacen.leer().map { it.id })
        almacen.actualizar("voz") { it.copy(fijado = true) }
        almacen.actualizar("foto1") { it.copy(emoji = "📌") }

        val escritas = File(context.filesDir, "guardados.jsonl").readLines().filter { it.isNotBlank() }
        val porId = escritas.associateBy { Json.parseToJsonElement(it).jsonObject["id"].toString().trim('"') }
        assertTrue(porId.getValue("voz"), porId.getValue("voz").contains(""""clips":[{"duracionMs":12000,"id":"clip1","ms":30000}]"""))
        assertTrue(porId.getValue("voz"), porId.getValue("voz").contains(""""capturas":[{"id":"foto1","ms":5000}]"""))
        assertTrue(porId.getValue("voz").contains(""""fijado":true"""))
        assertTrue(porId.getValue("foto1"), porId.getValue("foto1").contains(""""enGrabacion":{"de":"voz","ms":5000}"""))
        assertTrue(porId.getValue("foto1"), porId.getValue("foto1").contains(""""fuente":"camara""""))
        assertTrue(porId.getValue("clip1"), porId.getValue("clip1").contains(""""enGrabacion":{"de":"voz","duracionMs":12000,"ms":30000}"""))
        assertTrue(porId.getValue("clip1"), porId.getValue("clip1").contains(""""fuente":"camara""""))
        // La vieja, rescatada: su fuente en su sitio y `origen` una sola vez (vacío).
        val vieja = porId.getValue("vieja")
        assertTrue(vieja, vieja.contains(""""fuente":"camara""""))
        assertEquals(vieja, 1, Regex("\"origen\"").findAll(vieja).count())
        assertNull(almacen.leer().single { it.id == "vieja" }.origen)
        // Y la clase se sigue leyendo entera: las fotos como capturas y el clip en su minuto.
        val nota = almacen.leer().single { it.id == "voz" }
        assertEquals(listOf(GrabarClase.Enlace(5_000, "foto1")), GrabarClase.capturasDe(nota))
        assertEquals(listOf(Presencial.Clip(30_000, 12_000, "clip1")), Presencial.clipsDe(nota))
    }
}
