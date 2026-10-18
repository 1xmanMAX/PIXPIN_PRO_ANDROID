package com.forge.pixpin.motor

import com.forge.pixpin.motormd.Formulas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El texto rico del lienzo** ([TextoRico]), con los casos del PC: los de `texto_rico.rs`
 * (componer, plano, marcas, LaTeX roto) y los de `tests/texto_rico.rs` (editar, ida y vuelta
 * por el fichero con su texto plano de reserva). El medidor es el de las pruebas del PC: cada
 * letra mide medio tamaño y el renglón 1,25.
 */
class TextoRicoTest {

    private val medidor = TextoRico.Medidor { s, tam, _, _ -> s.length * tam * 0.5 }

    private fun opciones(tam: Double = 20.0) = TextoRico.Opciones(tam = tam)

    private fun componer(fuente: String, o: TextoRico.Opciones = opciones()) =
        TextoRico.componer(fuente, o, medidor)

    private fun textos(m: TextoRico.Maqueta) = m.piezas.filterIsInstance<Ecuacion.Pieza.Texto>()
    private fun rayas(m: TextoRico.Maqueta) = m.piezas.filterIsInstance<Ecuacion.Pieza.Raya>()

    private val rojo = Formulas.colorDeNombre("rojo")!!

    // ---- texto_rico.rs ----

    @Test
    fun `el texto y la formula comparten la linea base`() {
        val m = componer("Nos piden \$m\\sphericalangle COD = X\$")
        val t = textos(m)
        assertTrue(t.all { kotlin.math.abs(it.y - t[0].y) < 0.01 })
        assertFalse("el ángulo va dibujado", t.any { '∢' in it.texto })
        assertEquals("el signo del ángulo: sus lados y su arco", 2, rayas(m).size)
        assertFalse("sin mandatos a la vista", t.any { '\\' in it.texto })
        assertEquals(25.0, m.alto, 0.01)
    }

    @Test
    fun `el superindice sube y encoge`() {
        val t = textos(componer("area \$x^2\$"))
        val x = t.first { it.texto == "x" }
        val dos = t.first { it.texto == "2" }
        assertTrue(dos.tam < x.tam)
        assertTrue(dos.y < x.y)
        assertTrue(dos.x > x.x)
    }

    @Test
    fun `la fraccion en linea tiene raya y queda centrada en el renglon`() {
        val m = componer("\$45° = \\frac{2a+2b}{2}\$ fin")
        val t = textos(m)
        val r = rayas(m)
        assertEquals("la barra de la fracción", 1, r.size)
        val barra = r[0].puntos[0].y
        val num = t.first { it.texto.startsWith("2") && it.y < barra }
        val den = t.first { it.texto.startsWith("2") && it.y > barra }
        assertTrue("en línea encoge", num.tam < 20.0 && den.tam < 20.0)
        val fin = t.first { it.texto.trim() == "fin" }
        val igual = t.first { '=' in it.texto }
        assertEquals(igual.y, fin.y, 0.01)
        assertTrue(m.alto > 25.0)
    }

    @Test
    fun `la raya y el arco van encima y del ancho de las letras`() {
        val m = componer("\$\\overline{AB}\$ y \$m\\overset{\\frown}{AC}\$")
        val t = textos(m)
        val r = rayas(m)
        assertEquals(2, r.size)
        val ab = t.first { it.texto == "AB" }
        val raya = r[0].puntos
        assertEquals("la raya es recta", 2, raya.size)
        assertTrue(raya[0].x >= ab.x - 0.01 && raya[1].x <= ab.x + 20.0 + 0.01)
        assertTrue("encima de la letra", raya[0].y < ab.y + 20.0 * 0.6)
        val arco = r[1].puntos
        assertTrue("el arco es curvo", arco.size > 5)
        assertTrue("sin NaN", arco.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue("sube por el medio", arco[arco.size / 2].y < arco[0].y)
    }

    @Test
    fun `los colores por tramo y dentro de la formula`() {
        val azul = Formulas.colorDeNombre("blue")
        val m = componer("Nos piden \\color{rojo}{X} y \$a + \\color{blue}{b}\$ **ya**")
        val t = textos(m)
        assertEquals(rojo, t.first { it.texto == "X" }.color)
        assertEquals(azul, t.first { it.texto == "b" }.color)
        assertNull(t.first { it.texto == "a" }.color)
        assertTrue(t.any { it.texto == "ya" && it.negrita })
        // El de dentro gana al de fuera.
        val t2 = textos(componer("\$\\color{red}{a + \\color{green}{b}}\$"))
        assertEquals(Formulas.colorDeNombre("red"), t2.first { it.texto == "a" }.color)
        assertEquals(Formulas.colorDeNombre("green"), t2.first { it.texto == "b" }.color)
    }

    @Test
    fun `el plano de reserva se lee sin formato`() {
        assertEquals("Nos piden m∢COD = X", TextoRico.plano("Nos piden \$m\\sphericalangle COD = \\color{red}{X}\$"))
        assertEquals("Si x² y ya", TextoRico.plano("**Si** \$x^2\$ y *ya*"))
        assertEquals("45° = (2a + 2b)/2", TextoRico.plano("\$45^\\circ = \\frac{2a+2b}{2}\$"))
        assertEquals("A̅B̅", TextoRico.plano("\\centro \$\\overline{AB}\$"))
        assertEquals("mAC⌒ = 2a", TextoRico.plano("\$m\\overset{\\frown}{AC}=2a\$"))
        assertEquals("cuesta \$5", TextoRico.plano("cuesta \\\$5"))
    }

    @Test
    fun `solo es rico lo que lleva marcas`() {
        assertFalse(TextoRico.tieneMarcas("Hola mundo"))
        assertFalse(TextoRico.tieneMarcas("2*3*4 y nombre_de_fichero"))
        assertFalse("dinero no es fórmula", TextoRico.tieneMarcas("cuesta \$5 y \$10"))
        assertFalse("una ruta se queda como está", TextoRico.tieneMarcas("C:\\Users\\max"))
        assertTrue(TextoRico.tieneMarcas("\$x^2\$"))
        assertTrue(TextoRico.tieneMarcas("**ojo**"))
        assertTrue(TextoRico.tieneMarcas("\\centro hola"))
        assertTrue(TextoRico.tieneMarcas("\\color{red}{X}"))
    }

    @Test
    fun `el latex roto se ve como fuente y no se cuelga`() {
        for (roto in listOf(
            "\$\\frac{1}{\$", "\$x^\$", "texto \$sin cerrar", "\\color{rojo}{sin cerrar",
            "\$\\overline{AB\$ y \$\\sqrt[\$", "**sin cerrar", "\$}}}{{{\$", "\$\\begin{bmatrix} 1 & 2\$",
            "\\tam{x}{a}", "\$\\noexiste{x}\$"
        )) {
            val m = componer(roto)
            assertTrue(roto, m.ancho.isFinite() && m.alto.isFinite())
            TextoRico.plano(roto)
        }
        assertTrue(textos(componer("\$\\noexiste{x}\$")).any { it.texto == "\\noexiste" })
        assertTrue(textos(componer("texto \$sin cerrar")).any { '$' in it.texto })
        // Muy largo: se compone lo que cabe y no tarda.
        val largo = "\$x^2\$ ".repeat(10_000)
        val antes = System.nanoTime()
        componer(largo)
        assertTrue((System.nanoTime() - antes) / 1e9 < 5.0)
    }

    @Test
    fun `centrado y justificado por renglon`() {
        val m = componer("una linea bastante larga\n\\centro corta\n\\derecha fin")
        val t = textos(m)
        val corta = t.first { it.texto == "corta" }
        val fin = t.first { it.texto == "fin" }
        assertEquals((m.ancho - 50.0) / 2, corta.x, 0.1)
        assertEquals(m.ancho, fin.x + 30.0, 0.1)
        val m2 = componer("\\justificado a b c\nuna linea bastante larga\n")
        val c = textos(m2).first { it.texto == "c" }
        assertEquals("la última letra llega al borde", m2.ancho, c.x + 10.0, 0.1)
    }

    @Test
    fun `dentro de una figura parte los renglones por palabras`() {
        val m = componer("uno dos tres cuatro \$x^2\$", opciones().copy(anchoMax = 100.0))
        val t = textos(m)
        assertTrue(t.map { it.y.toInt() }.toSet().size >= 2)
        assertTrue(t.all { it.x + it.texto.length * it.tam * 0.5 <= 100.5 })
    }

    @Test
    fun `el recuadro rodea la formula`() {
        val m = componer("\$\\boxed{\\theta = 90^\\circ}\$")
        val r = rayas(m)
        assertEquals(1, r.size)
        assertEquals("cuatro lados cerrados", 5, r[0].puntos.size)
        val t = textos(m)
        assertTrue(t.any { 'θ' in it.texto })
        assertTrue(t.any { '°' in it.texto })
    }

    // ---- tests/texto_rico.rs: editar ----

    @Test
    fun `se compone al salir y se vuelve a editar como fuente`() {
        val fuente = "Nos piden \$m\\sphericalangle COD = \\color{red}{X}\$"
        val e = newElement(ElementType.TEXT, 100.0, 50.0, ItemStyle(fontSize = 20.0))
        val compuesto = TextoRico.conFuente(e, fuente, medidor)
        assertEquals("Nos piden m∢COD = X", compuesto.text)
        assertEquals(fuente, compuesto.textoRico)
        // Volver a abrirlo da la fuente.
        assertEquals(fuente, TextoRico.vigente(compuesto))
        // La caja es la de lo compuesto, no la del plano.
        val m = TextoRico.componer(fuente, TextoRico.opcionesDe(compuesto), medidor)
        assertEquals(m.ancho, compuesto.width, 0.01)
        assertEquals(m.alto, compuesto.height, 0.01)
    }

    @Test
    fun `un texto sin marcas sigue siendo de los de siempre`() {
        val e = newElement(ElementType.TEXT, 0.0, 0.0, ItemStyle())
        val r = TextoRico.conFuente(e.copy(width = 77.0), "cuesta \$5 y 2*3*4", medidor)
        assertEquals("cuesta \$5 y 2*3*4", r.text)
        assertNull(r.textoRico)
        assertEquals("la caja la mide quien siempre", 77.0, r.width, 0.0)
        // Y un texto que deja de tener marcas pierde la fuente.
        val rico = TextoRico.conFuente(e, "**ya**", medidor)
        assertNull(TextoRico.conFuente(rico, "ya", medidor).textoRico)
    }

    @Test
    fun `color y negrita encadenados sobre lo elegido y formula con el cursor dentro`() {
        // «Nos piden X» con la X elegida: rojo y luego negrita, sobre lo mismo.
        var r = TextoRico.conColor("Nos piden X", 10, 11, "rojo")
        assertEquals("Nos piden \\color{rojo}{X}", r.texto)
        assertEquals("X", r.texto.substring(r.desde, r.hasta))
        r = TextoRico.envolver(r.texto, r.desde, r.hasta, TextoRico.Formato.NEGRITA)
        assertEquals("Nos piden \\color{rojo}{**X**}", r.texto)
        // Sin nada elegido: las marcas con el cursor en medio.
        val fin = "Nos piden \\color{rojo}{X}"
        val f = TextoRico.envolver(fin, fin.length, fin.length, TextoRico.Formato.FORMULA)
        assertEquals(f.desde, f.hasta)
        val escrito = f.texto.substring(0, f.desde) + "x^2" + f.texto.substring(f.desde)
        assertEquals("Nos piden \\color{rojo}{X}\$x^2\$", escrito)
        assertTrue(TextoRico.tieneMarcas(escrito))
        // Lo elegido al revés (de derecha a izquierda) también vale.
        assertEquals("a **b** c", TextoRico.envolver("a b c", 3, 2, TextoRico.Formato.NEGRITA).texto)
    }

    // ---- tests/texto_rico.rs: el fichero ----

    @Test
    fun `ida y vuelta por el fichero con su texto plano de reserva`() {
        val fuente = "\\centro \$45^\\circ = \\frac{\\color{blue}{2a+2b}}{2}\$\n**ya**"
        val e = newElement(ElementType.TEXT, 10.0, 10.0, ItemStyle(fontSize = 20.0))
            .copy(text = TextoRico.plano(fuente), textoRico = fuente)
        val json = ExcalidrawJson.encodeToString(Element.serializer(), e)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(fuente, obj["textoRico"]!!.jsonPrimitive.content)
        assertEquals("el plano de reserva", "45° = (2a + 2b)/2\nya", obj["text"]!!.jsonPrimitive.content)
        val vuelta = ExcalidrawJson.decodeFromString(Element.serializer(), json)
        assertEquals(fuente, TextoRico.vigente(vuelta))
        // Quien no lo entiende (un Android viejo, Excalidraw) cambió el texto plano: gana el suyo.
        val editado = JsonObject(obj + ("text" to JsonPrimitive("otra cosa")))
        val otra = ExcalidrawJson.decodeFromString(Element.serializer(), editado.toString())
        assertNull(TextoRico.vigente(otra))
        assertEquals("otra cosa", otra.text)
        // Un texto de siempre no estrena la clave.
        val liso = newElement(ElementType.TEXT, 0.0, 0.0, ItemStyle()).copy(text = "a")
        assertFalse("textoRico" in Json.parseToJsonElement(ExcalidrawJson.encodeToString(Element.serializer(), liso)).jsonObject)
    }

    @Test
    fun `lo que escribe el PC se lee y casa con su plano`() {
        // El JSON tal cual lo escribe el PC (`excalidraw::elementos_a_json`).
        val delPc = """{"id":"t1","type":"text","x":10,"y":10,"width":200,"height":40,"angle":0,
            "strokeColor":"#1e1e1e","backgroundColor":"transparent","fillStyle":"solid","strokeWidth":1,
            "strokeStyle":"solid","roughness":0,"opacity":100,"seed":7,"version":3,"versionNonce":1,
            "isDeleted":false,"text":"Nos piden m∢COD = X","originalText":"Nos piden m∢COD = X",
            "textoRico":"Nos piden ${'$'}m\\sphericalangle COD = \\color{rojo}{X}${'$'}",
            "fontSize":20,"fontFamily":6,"textAlign":"left","verticalAlign":"top"}"""
        val e = ExcalidrawJson.decodeFromString(Element.serializer(), delPc)
        assertNotNull(TextoRico.vigente(e))
        // Y al volver a guardar, la fuente viaja intacta.
        val otraVez = Json.parseToJsonElement(ExcalidrawJson.encodeToString(Element.serializer(), e)).jsonObject
        assertEquals(e.textoRico, otraVez["textoRico"]!!.jsonPrimitive.content)
    }

    @Test
    fun `un color se lee en espanol o ingles y en hexadecimal`() {
        assertEquals("#e03131", Formulas.colorDeNombre("Roja"))
        assertEquals("#1971c2", Formulas.colorDeNombre("azules"))
        assertEquals("#aabbcc", Formulas.colorDeNombre("#abc"))
        assertEquals("#123456", Formulas.colorDeNombre("#123456"))
        assertNull(Formulas.colorDeNombre("chartreuse"))
        // Un color desconocido deja el texto con el de fuera, sin perderlo.
        val t = textos(componer("\\color{chartreuse}{X}"))
        assertTrue(t.any { it.texto == "X" && it.color == null })
    }
}
