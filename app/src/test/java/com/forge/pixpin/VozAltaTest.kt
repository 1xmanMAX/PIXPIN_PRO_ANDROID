package com.forge.pixpin

import com.forge.pixpin.motor.VozAlta
import com.forge.pixpin.motor.VozAlta.Voz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VozAltaTest {

    private val voces = listOf(
        Voz("es-es-x-eea-network", "es-ES", local = false, instalada = true, calidad = 500),
        Voz("es-es-x-eea-local", "es-ES", local = true, instalada = true, calidad = 400),
        Voz("es-us-x-esc-local", "es-US", local = true, instalada = true, calidad = 400),
        Voz("es-us-x-sfb-local", "es-US", local = true, instalada = true, calidad = 300),
        Voz("en-gb-x-gba-local", "en-GB", local = true, instalada = false, calidad = 400),
        Voz("fr-fr-x-frc-network", "fr-FR", local = false, instalada = true, calidad = 400)
    )

    @Test
    fun `se elige la voz sin conexion del mismo pais y si no la del mismo idioma`() {
        assertEquals("es-es-x-eea-local", VozAlta.mejorVoz(voces, "es-ES")?.nombre)
        // «es-PE» no está: la del mismo idioma con más calidad.
        assertEquals("es-es-x-eea-local", VozAlta.mejorVoz(voces, "es_PE")?.nombre)
        assertEquals("es-us-x-esc-local", VozAlta.mejorVoz(voces, "es-us")?.nombre)
    }

    @Test
    fun `nunca una voz de la red ni una sin bajar`() {
        assertNull(VozAlta.mejorVoz(voces, "fr-FR"))
        assertNull(VozAlta.mejorVoz(voces, "en-GB"))
        assertTrue(VozAlta.hayQueBajarla(voces, "en-US"))
        assertFalse(VozAlta.hayQueBajarla(voces, "fr-FR"))
        assertNull(VozAlta.mejorVoz(emptyList(), "es"))
    }

    @Test
    fun `las partes de una etiqueta de idioma`() {
        assertEquals("es" to "ES", VozAlta.partes("es_es"))
        assertEquals("en" to "", VozAlta.partes("EN"))
        assertEquals("zh" to "", VozAlta.partes("zh-Hant"))
        assertEquals("" to "", VozAlta.partes(""))
    }

    @Test
    fun `se adivina el idioma por sus palabras`() {
        val es = "El perro de la casa come en el jardín y no quiere salir porque llueve mucho desde la mañana."
        val en = "The dog of the house is eating in the garden and it does not want to go out because it was raining."
        val pt = "O cão da casa come no jardim e não quer sair porque está chovendo muito desde a manhã com o vento."
        val fr = "Le chien de la maison mange dans le jardin et il ne veut pas sortir parce que la pluie est forte."
        assertEquals("es", VozAlta.idiomaDelTexto(es, "?"))
        assertEquals("en", VozAlta.idiomaDelTexto(en, "?"))
        assertEquals("pt", VozAlta.idiomaDelTexto(pt, "?"))
        assertEquals("fr", VozAlta.idiomaDelTexto(fr, "?"))
        // Muy poco texto, o nada que diga algo: lo de por defecto.
        assertEquals("?", VozAlta.idiomaDelTexto("Hola", "?"))
        assertEquals("?", VozAlta.idiomaDelTexto("1 2 3 4 5 6 7 8 9 10 11 12", "?"))
    }

    @Test
    fun `el idioma para leer afina con el de la pagina o el del aparato`() {
        val en = "The dog of the house is eating in the garden and it does not want to go out because it was raining."
        val es = "El perro de la casa come en el jardín y no quiere salir porque llueve mucho desde la mañana."
        // Un Word en inglés dice lang="es": manda el texto.
        assertEquals("en", VozAlta.idiomaParaLeer(en, "es", "es-PE"))
        // Español en un teléfono de Perú: la variante del aparato.
        assertEquals("es-PE", VozAlta.idiomaParaLeer(es, "es", "es-PE"))
        // Un libro que dice es-MX en un teléfono en inglés: el del libro.
        assertEquals("es-MX", VozAlta.idiomaParaLeer(es, "es-MX", "en-US"))
        // Sin pistas en el texto: la página, y sin página, el aparato.
        assertEquals("de", VozAlta.idiomaParaLeer("Hola", "de", "es-ES"))
        assertEquals("es-ES", VozAlta.idiomaParaLeer("Hola", "", "es-ES"))
    }

    @Test
    fun `un parrafo corto va entero y uno largo se corta por las frases`() {
        assertEquals(listOf("Hola, mundo."), VozAlta.trozos("  Hola,\n  mundo.  "))
        assertTrue(VozAlta.trozos("   ").isEmpty())
        val frase = "Esta es una frase de prueba bastante normal. "
        val largo = frase.repeat(40)
        val trozos = VozAlta.trozos(largo, tope = 200)
        assertTrue(trozos.all { it.length <= 200 && it.isNotBlank() })
        assertTrue(trozos.all { it.endsWith(".") })
        assertEquals(largo.trim().replace(" ", ""), trozos.joinToString("").replace(" ", ""))
    }

    @Test
    fun `una frase eterna se corta por las comas y luego por los espacios`() {
        val comas = (1..60).joinToString(", ") { "elemento número $it" }
        val t1 = VozAlta.trozos(comas, tope = 120)
        assertTrue(t1.size > 1 && t1.all { it.length <= 120 })
        assertEquals(comas.replace(" ", ""), t1.joinToString("").replace(" ", ""))
        val sinNada = "palabra ".repeat(100) + "x".repeat(300)
        val t2 = VozAlta.trozos(sinNada, tope = 100)
        assertTrue(t2.all { it.length <= 100 })
        assertEquals(sinNada.replace(" ", ""), t2.joinToString("").replace(" ", ""))
    }

    @Test
    fun `los nombres de los trozos van y vuelven`() {
        assertEquals(Triple(3, 12, 0), VozAlta.deId(VozAlta.idDe(3, 12, 0)))
        assertNull(VozAlta.deId(null))
        assertNull(VozAlta.deId("otra:cosa"))
        assertNull(VozAlta.deId("1:x:2"))
    }

    @Test
    fun `la velocidad rota y se lee bien`() {
        assertEquals(1.25f, VozAlta.siguienteVelocidad(1f))
        assertEquals(0.75f, VozAlta.siguienteVelocidad(2f))
        assertEquals(0.75f, VozAlta.siguienteVelocidad(1.1f))
        assertEquals("1×", VozAlta.rotulo(1f))
        assertEquals("1,5×", VozAlta.rotulo(1.5f))
        assertEquals("1,25×", VozAlta.rotulo(1.25f))
        assertEquals("0,75×", VozAlta.rotulo(0.75f))
    }

    @Test
    fun `el resaltado apunta al parrafo pedido`() {
        assertTrue(VozAlta.resaltar(7, seguir = true).contains("[data-pixpin-voz=\"7\"]"))
        assertTrue(VozAlta.resaltar(7, seguir = false).contains("if(false&&"))
        // Devuelve dónde cae el párrafo, para la flecha del riel.
        assertTrue(VozAlta.resaltar(7, seguir = true).contains("return Math.max(0,Math.min(1,f))"))
        assertTrue(VozAlta.PREPARAR.contains("pixpin-tinta"))
    }
}
