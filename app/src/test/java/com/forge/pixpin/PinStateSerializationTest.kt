package com.forge.pixpin.pin

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PinStateSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `roundtrip completo`() {
        val state = PinState(
            id = "abc",
            type = PinType.TEXT,
            text = "hola\nmundo",
            x = 42, y = 84,
            scale = 1.5f, alpha = 0.7f,
            clickThrough = true, minimized = true
        )
        val restored = json.decodeFromString<PinState>(json.encodeToString(state))
        assertEquals(state, restored)
    }

    @Test
    fun `campos por defecto al faltar`() {
        val minimal = """{"id":"x","type":"COLOR","colorArgb":-1}"""
        val restored = json.decodeFromString<PinState>(minimal)
        assertEquals(1f, restored.scale)
        assertEquals(1f, restored.alpha)
        assertEquals(false, restored.clickThrough)
    }

    /** Los pines guardados por versiones anteriores deben seguir abriéndose. */
    @Test
    fun `formato antiguo con campos retirados`() {
        val legacy = """{"id":"x","type":"TEXT","text":"hola","locked":true,"x":10}"""
        val restored = json.decodeFromString<PinState>(legacy)
        assertEquals("hola", restored.text)
        assertEquals(10, restored.x)
    }

    @Test
    fun `lista de pines`() {
        val pins = listOf(
            PinState(id = "1", type = PinType.IMAGE, imagePath = "/f/a.png"),
            PinState(id = "2", type = PinType.COLOR, colorArgb = 0xFF29B8DB.toInt())
        )
        val restored = json.decodeFromString<List<PinState>>(json.encodeToString(pins))
        assertEquals(pins, restored)
    }
}
