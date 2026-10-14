package com.forge.pixpin

import com.forge.pixpin.motor.EdgeVoz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las direcciones de las voces de Microsoft, las mismas que el PC (`edge.rs`, 13-oct-2026). */
class EdgeVozUrlTest {
    @Test
    fun hablar_va_por_el_servidor_nuevo_y_la_lista_por_el_de_siempre() {
        val u = EdgeVoz.urlDeHablar("id", "GEC")
        assertEquals(
            "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1?Ocp-Apim-Subscription-Key=6A5AA1D4EAFF4E9FB37E23D68491D6F4&ConnectionId=id&Sec-MS-GEC=GEC&Sec-MS-GEC-Version=1-143.0.3650.75",
            u
        )
        // Caso negativo: el de antes (403 desde el 12-oct) ya no se usa para hablar.
        assertTrue(!u.contains("speech.platform.bing.com"))
        assertTrue(EdgeVoz.URL_DE_VOCES.startsWith("https://speech.platform.bing.com/"))
    }
}
