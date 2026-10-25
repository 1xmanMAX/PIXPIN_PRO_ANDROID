package com.forge.pixpin.anki

import com.forge.pixpin.anki.Tarjetas.Imagen
import com.forge.pixpin.anki.Tarjetas.Tarjeta
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las pruebas de `pixpin-anki` (`tarjetas.rs`, `fichero.rs`) y `ventana_chat/anki.rs` del PC, portadas tal cual. */
class TarjetasTest {

    @Test fun `una nota con dos puntos dobles da una tarjeta por linea`() {
        val t = Tarjetas.deLaNota("Capital de Peru :: Lima\n- 2 + 2 ;; 4\nUn parrafo suelto.")
        assertEquals(2, t.size)
        assertEquals("Capital de Peru" to "Lima", t[0].frente to t[0].reverso)
        assertEquals("2 + 2" to "4", t[1].frente to t[1].reverso)
    }

    @Test fun `pregunta y respuesta en varias lineas`() {
        val t = Tarjetas.deLaNota("P: ¿Qué es la mitosis?\nR: División celular\nque da dos células\n\nQ: <b>?\nA: x & y")
        assertEquals(2, t.size)
        assertEquals("División celular<br>que da dos células", t[0].reverso)
        // El HTML de la nota no se cuela: se escapa.
        assertEquals("&lt;b&gt;?", t[1].frente)
        assertEquals("x &amp; y", t[1].reverso)
        // Caso negativo: una pregunta sin respuesta no es tarjeta.
        assertTrue(Tarjetas.deLaNota("P: ¿sola?").isEmpty())
    }

    @Test fun `los huecos de anki tapan delante y se ven detras`() {
        val t = Tarjetas.deLaNota("{{c1::Lima}} es la capital de {{c2::Perú::país}}")
        assertEquals(1, t.size)
        assertEquals("[…] es la capital de [país]", t[0].frente)
        assertEquals("<b>Lima</b> es la capital de <b>Perú</b>", t[0].reverso)
    }

    @Test fun `una clase da una tarjeta por captura con lo dicho en su tramo`() {
        fun img(n: String) = Imagen(n, "/x/$n")
        val trozos = listOf(0L to "Hola.", 10_000L to "La celula.", 20_000L to "El nucleo.", 40_000L to "Las mitocondrias.")
        val t = Tarjetas.deLaClase("Biología", "¿Qué se explicó aquí?", trozos, listOf(39_000L to img("b.png"), 12_000L to img("a.png")))
        assertEquals(2, t.size)
        assertTrue(t[0].frente.contains("a.png") && t[0].frente.contains("0:12"))
        assertTrue(t[0].reverso, t[0].reverso.startsWith("La celula. El nucleo."))
        // Caso negativo: lo de la captura siguiente no se mete en esta.
        assertFalse(t[0].reverso.contains("mitocondrias"))
        assertTrue(t[1].reverso.contains("Las mitocondrias."))
        assertEquals("b.png", t[1].imagenes[0].nombre)
    }

    @Test fun `las etiquetas no llevan espacios`() {
        assertEquals("Clase_de_física", Tarjetas.etiqueta(" Clase de física "))
    }

    @Test fun `el fichero lleva su mazo y una tarjeta por linea`() {
        val t = listOf(Tarjeta("a\tb", "c\nd", listOf("pixpin", "clase")))
        val s = Tarjetas.fichero("PixPin::Física", t)
        assertTrue(s.startsWith("#separator:tab\n#html:true\n#deck:PixPin::Física\n"))
        assertTrue(s, s.endsWith("a b\tc d\tpixpin clase\n"))
    }

    @Test fun `el mazo cuelga de pixpin sin romper los submazos`() {
        assertEquals("PixPin::Física", AnkiDelMensaje.mazoDe(" Física "))
        assertEquals("PixPin::A:B", AnkiDelMensaje.mazoDe("A::B"))
    }

    // ---- Lo del teléfono: de qué mensajes salen.

    @Test fun `una clase grabada del chat da sus tarjetas con la transcripcion y sus capturas`() {
        val capturas = buildJsonArray {
            add(buildJsonObject { put("ms", 39_000); put("id", "c2") })
            add(buildJsonObject { put("ms", 12_000); put("id", "c1") })
            add(buildJsonObject { put("ms", 50_000); put("id", "no-esta") })
        }
        val voz = Mensaje(
            id = "v", cuando = 0, clase = Clase.VOZ, grabacion = "clase", capturas = capturas,
            transcripcion = "[0:00] Hola.\n\n[0:10] La **celula**.\n\n[0:20] El nucleo.\n\n[0:40] Las mitocondrias."
        )
        val c1 = Mensaje(id = "c1", cuando = 1, clase = Clase.IMAGEN, ruta = "/f/c1.jpg")
        val c2 = Mensaje(id = "c2", cuando = 2, clase = Clase.IMAGEN, ruta = "/f/c2.png")
        assertTrue(AnkiDelMensaje.sePuede(voz))
        val t = AnkiDelMensaje.tarjetasDe(voz, listOf(voz, c1, c2), "Biología", "Clase de física") { true }
        assertEquals(2, t.size)
        assertEquals("pixpin-c1.jpg", t[0].imagenes[0].nombre)
        assertTrue(t[0].reverso, t[0].reverso.startsWith("La celula. El nucleo."))
        assertEquals(listOf("pixpin", "Clase_de_física"), t[0].etiquetas)
        // Caso negativo: una nota de voz sin capturas no se ofrece.
        assertFalse(AnkiDelMensaje.sePuede(voz.copy(capturas = null)))
    }

    @Test fun `una foto lleva su descripcion detras y una nota sin preguntas no da nada`() {
        val foto = Mensaje(id = "f", cuando = 0, clase = Clase.IMAGEN, ruta = "/f/x.png", texto = "Mitocondria\ncon crestas", nombre = "x.png")
        val t = AnkiDelMensaje.tarjetasDe(foto, listOf(foto), "", "Bio") { true }
        assertEquals("<img src=\"pixpin-f.png\">", t.single().frente)
        assertEquals("Mitocondria<br>con crestas", t.single().reverso)
        val nota = Mensaje(id = "n", cuando = 0, clase = Clase.NOTA, texto = "Solo un apunte.")
        assertFalse(AnkiDelMensaje.sePuede(nota))
        assertTrue(AnkiDelMensaje.sePuede(nota.copy(texto = "Capital de Peru :: Lima")))
    }
}
