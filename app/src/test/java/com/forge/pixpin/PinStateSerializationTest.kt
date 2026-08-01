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
    fun `roundtrip con los campos nuevos`() {
        val state = PinState(
            id = "abc",
            type = PinType.TEXT,
            text = "hola",
            textBoxWidth = 240,
            textBoxHeight = 180,
            priority = true,
            emoji = "🔥"
        )
        val restored = json.decodeFromString<PinState>(json.encodeToString(state))
        assertEquals(state, restored)
    }

    @Test
    fun `los campos nuevos tienen valores por defecto seguros`() {
        val minimal = """{"id":"x","type":"TEXT","text":"hola"}"""
        val restored = json.decodeFromString<PinState>(minimal)
        assertEquals(330, restored.textBoxWidth)
        assertEquals(null, restored.textBoxHeight)
        assertEquals(false, restored.priority)
        assertEquals(null, restored.emoji)
    }

    /** Un pins.json de la v0.2 lleva savedCategory; el campo ya no existe y debe ignorarse. */
    @Test
    fun `un pin de la version anterior sigue cargando`() {
        val v02 = """{"id":"x","type":"IMAGE","imagePath":"/f/a.png",""" +
            """"isPinned":true,"savedCategory":"⭐ Importante","textBoxWidth":330}"""
        val restored = json.decodeFromString<PinState>(v02)
        assertEquals("/f/a.png", restored.imagePath)
        assertEquals(true, restored.isPinned)
        assertEquals(false, restored.priority)
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
