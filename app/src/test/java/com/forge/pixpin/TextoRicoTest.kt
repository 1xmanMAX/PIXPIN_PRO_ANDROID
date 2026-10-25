package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El texto rico del lienzo, como en el PC** (25-oct-2026). Los casos son los de
 * `crates/pixpin-motor2d/src/texto_rico.rs` y `tests/texto_rico.rs` del PC, con su mismo medidor
 * fijo (cada letra media eme, el renglón 1,25): el plano de reserva tiene que salir **igual**
 * carácter a carácter, porque de él depende la regla de coherencia entre los dos.
 */
class TextoRicoTest {

    private val medidor = TextoRico.Medidor { s, tam, _, _ -> s.length * tam * 0.5 }
    private fun opciones(tam: Double = 20.0) = TextoRico.Opciones(tam = tam)
    private fun componer(fuente: String, o: TextoRico.Opciones = opciones()) =
        TextoRico.componer(fuente, o, medidor)

    private fun textos(m: TextoRico.Maqueta) = m.piezas.filterIsInstance<CajasRicas.Pieza.Texto>()
    private fun rayas(m: TextoRico.Maqueta) = m.piezas.filterIsInstance<CajasRicas.Pieza.Raya>()

    // ---- El texto plano de reserva (el `text`) ----

    @Test fun elPlanoDeReservaSeLeeSinFormato() {
        assertEquals("Nos piden m∢COD = X", TextoRico.plano("Nos piden \$m\\sphericalangle COD = \\color{red}{X}\$"))
        assertEquals("Si x² y ya", TextoRico.plano("**Si** \$x^2\$ y *ya*"))
        assertEquals("45° = (2a + 2b)/2", TextoRico.plano("\$45^\\circ = \\frac{2a+2b}{2}\$"))
        assertEquals("A̅B̅", TextoRico.plano("\\centro \$\\overline{AB}\$"))
        assertEquals("mAC⌒ = 2a", TextoRico.plano("\$m\\overset{\\frown}{AC}=2a\$"))
        assertEquals("cuesta \$5", TextoRico.plano("cuesta \\\$5"))
        assertEquals("a₁ + √(x + 1)", TextoRico.plano("\$a_1 + \\sqrt{x+1}\$"))
    }

    @Test fun soloEsRicoLoQueLlevaMarcas() {
        assertFalse(TextoRico.tieneMarcas("Hola mundo"))
        assertFalse(TextoRico.tieneMarcas("2*3*4 y nombre_de_fichero"))
        assertFalse("dinero no es fórmula", TextoRico.tieneMarcas("cuesta \$5 y \$10"))
        assertFalse("una ruta se queda como está", TextoRico.tieneMarcas("C:\\Users\\max"))
        assertTrue(TextoRico.tieneMarcas("\$x^2\$"))
        assertTrue(TextoRico.tieneMarcas("**ojo**"))
        assertTrue(TextoRico.tieneMarcas("\\centro hola"))
        assertTrue(TextoRico.tieneMarcas("\\color{red}{X}"))
        assertTrue(TextoRico.tieneMarcas("<u>sub</u>"))
    }

    @Test fun lasMarcasYLosTramos() {
        val r = TextoRico.leer("Nos piden \\color{rojo}{**X**} y ~~no~~")[0]
        val x = r.tramos.first { it is TextoRico.Tramo.Texto && it.texto == "X" }
        assertEquals(TextoRico.colorDeNombre("rojo"), x.estilo.color)
        assertTrue(x.estilo.negrita)
        assertTrue(r.tramos.any { it is TextoRico.Tramo.Texto && it.texto == "no" && it.estilo.tachado })
        // Una fórmula con punto y coma no se corta.
        val f = TextoRico.leer("\$F_{(x; y)}=x^{2}\$ es un polinomio")[0].tramos[0]
        assertEquals("F_{(x; y)}=x^{2}", (f as TextoRico.Tramo.Formula).fuente)
        // Alineación por renglón, con la del elemento si no se dice.
        val rs = TextoRico.leer("uno\n\\derecha dos\n\\centering tres\n\\centrox cuatro")
        assertEquals(listOf(null, TextoRico.Alineado.DERECHA, TextoRico.Alineado.CENTRO, null), rs.map { it.alineado })
        assertEquals("dos", (rs[1].tramos[0] as TextoRico.Tramo.Texto).texto)
    }

    @Test fun losColoresPorSuNombre() {
        assertEquals(0xFFE03131.toInt(), TextoRico.colorDeNombre("rojo"))
        assertEquals(0xFFE03131.toInt(), TextoRico.colorDeNombre("Rojas"))
        assertEquals(0xFF1971C2.toInt(), TextoRico.colorDeNombre("blue"))
        assertEquals(0xFF2F9E44.toInt(), TextoRico.colorDeNombre("verdes"))
        assertEquals(0xFF112233.toInt(), TextoRico.colorDeNombre("#123"))
        assertEquals(0xFFABCDEF.toInt(), TextoRico.colorDeNombre("#abcdef"))
        assertNull(TextoRico.colorDeNombre("fucsia-neón"))
    }

    // ---- Ida y vuelta por el fichero (las muestras de las pruebas del PC) ----

    private fun elemento(json: String) = ExcalidrawJson.decodeFromString(Element.serializer(), json)

    @Test fun idaYVueltaPorElFicheroConSuTextoPlanoDeReserva() {
        // Lo que escribe el PC (`elementos_a_json`) para su prueba de ida y vuelta.
        val fuente = "\\centro \$45^\\circ = \\frac{\\color{blue}{2a+2b}}{2}\$\n**ya**"
        assertEquals("45° = (2a + 2b)/2\nya", TextoRico.plano(fuente))
        val json = """{"id":"t","seed":1,"type":"text","x":10,"y":10,"width":200,"height":60,
            "fontSize":20,"fontFamily":6,"textAlign":"left",
            "text":"45° = (2a + 2b)/2\nya","originalText":"45° = (2a + 2b)/2\nya",
            "textoRico":"\\centro ${'$'}45^\\circ = \\frac{\\color{blue}{2a+2b}}{2}${'$'}\n**ya**"}"""
        val e = elemento(json)
        assertEquals(fuente, e.textoRico)
        assertEquals(fuente, TextoRico.fuenteVigente(e))
        assertEquals(fuente, TextoRico.paraEditar(e))
        // Vuelve a salir tal cual.
        val vuelta = elemento(ExcalidrawJson.encodeToString(Element.serializer(), e))
        assertEquals(fuente, vuelta.textoRico)
        assertEquals(e.text, vuelta.text)
        // Quien no lo entiende cambió el texto plano: gana el suyo.
        val editado = e.copy(text = "otra cosa")
        assertNull(TextoRico.fuenteVigente(editado))
        assertEquals("otra cosa", TextoRico.paraEditar(editado))
    }

    @Test fun laMuestraDelFormatoDelPc() {
        // El ejemplo de `docs/texto-rico-del-lienzo.md`.
        val e = elemento(
            """{"id":"g","seed":2,"type":"text","x":0,"y":0,"width":10,"height":10,
            "text":"Nos piden m∢COD = X","originalText":"Nos piden m∢COD = X",
            "textoRico":"Nos piden ${'$'}m\\sphericalangle COD = \\color{rojo}{X}${'$'}",
            "fontSize":20,"fontFamily":6,"textAlign":"left"}"""
        )
        assertNotNull(TextoRico.fuenteVigente(e))
        // Un texto que no es texto nunca es rico.
        assertNull(TextoRico.fuenteVigente(e.copy(type = ElementType.RECTANGLE)))
    }

    @Test fun loEscritoSeGuardaComoPideElPc() {
        val rico = TextoRico.escrito("Nos piden \$m\\sphericalangle COD = \\color{red}{X}\$")
        assertEquals("Nos piden m∢COD = X", rico.texto)
        assertEquals("Nos piden \$m\\sphericalangle COD = \\color{red}{X}\$", rico.fuente)
        // Un texto sin marcas no estrena la clave.
        assertEquals(TextoRico.Escrito("cuesta \$5 y 2*3*4", null), TextoRico.escrito("cuesta \$5 y 2*3*4"))
    }

    // ---- Componer ----

    @Test fun laFraccionEnLineaTieneRayaYQuedaCentradaEnElRenglon() {
        val m = componer("\$45° = \\frac{2a+2b}{2}\$ fin")
        val t = textos(m)
        val r = rayas(m)
        assertEquals("la barra de la fracción", 1, r.size)
        val barra = r[0].puntos[0].y
        val num = t.first { it.texto.startsWith("2") && it.y < barra }
        val den = t.first { it.texto.startsWith("2") && it.y > barra }
        assertTrue("en línea encoge", num.tam < 20.0 && den.tam < 20.0)
        // El texto de después va en la línea del «=».
        val fin = t.first { it.texto.trim() == "fin" }
        val igual = t.first { it.texto.contains('=') }
        assertEquals(igual.y, fin.y, 0.01)
        // El renglón crece para que quepa la fracción.
        assertTrue(m.alto > 25.0)
    }

    @Test fun elTextoYLaFormulaCompartenLaLineaBase() {
        val m = componer("Nos piden \$m\\sphericalangle COD = X\$")
        val ys = textos(m).map { it.y }
        assertTrue(ys.all { kotlin.math.abs(it - ys[0]) < 0.01 })
        assertFalse("el ángulo va dibujado", textos(m).any { '∢' in it.texto })
        assertEquals("el signo del ángulo: sus lados y su arco", 2, rayas(m).size)
        assertFalse(textos(m).any { '\\' in it.texto })
        assertEquals(25.0, m.alto, 0.01)
    }

    @Test fun elSuperindiceSubeYEncoge() {
        val t = textos(componer("area \$x^2\$"))
        val x = t.first { it.texto == "x" }
        val dos = t.first { it.texto == "2" }
        assertTrue(dos.tam < x.tam && dos.y < x.y && dos.x > x.x)
    }

    @Test fun laRayaYElArcoVanEncimaYDelAnchoDeLasLetras() {
        val m = componer("\$\\overline{AB}\$ y \$m\\overset{\\frown}{AC}\$")
        val r = rayas(m)
        assertEquals(2, r.size)
        val a = textos(m).first { it.texto == "AB" }
        val raya = r[0].puntos
        assertEquals("recta", 2, raya.size)
        assertTrue(raya[0].x >= a.x - 0.01 && raya[1].x <= a.x + a.ancho + 0.01)
        assertTrue("encima de la letra", raya[0].y < a.y + 20.0 * 0.6)
        val arco = r[1].puntos
        assertTrue("curvo", arco.size > 5)
        assertTrue(arco.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue("sube por el medio", arco[arco.size / 2].y < arco[0].y)
    }

    @Test fun losColoresPorTramoYDentroDeLaFormula() {
        val rojo = TextoRico.colorDeNombre("rojo")
        val azul = TextoRico.colorDeNombre("blue")
        val m = componer("Nos piden \\color{rojo}{X} y \$a + \\color{blue}{b}\$ **ya**")
        val t = textos(m)
        assertEquals(rojo, t.first { it.texto == "X" }.color)
        assertEquals(azul, t.first { it.texto == "b" }.color)
        assertNull(t.first { it.texto == "a" }.color)
        assertTrue(t.any { it.texto == "ya" && it.negrita })
        // El de dentro gana al de fuera.
        val t2 = textos(componer("\$\\color{red}{a + \\color{green}{b}}\$"))
        assertEquals(TextoRico.colorDeNombre("red"), t2.first { it.texto == "a" }.color)
        assertEquals(TextoRico.colorDeNombre("green"), t2.first { it.texto == "b" }.color)
    }

    @Test fun centradoYJustificadoPorRenglon() {
        val m = componer("una linea bastante larga\n\\centro corta\n\\derecha fin")
        val t = textos(m)
        val corta = t.first { it.texto == "corta" }
        val fin = t.first { it.texto == "fin" }
        assertEquals((m.ancho - 50.0) / 2, corta.x, 0.1)
        assertEquals(m.ancho, fin.x + 30.0, 0.1)
        val j = componer("\\justificado a b c\nuna linea bastante larga\n")
        val c = textos(j).first { it.texto == "c" }
        assertEquals("la última letra llega al borde", j.ancho, c.x + 10.0, 0.1)
        // La alineación del elemento, para los renglones sin marca.
        val d = componer("larga larga\ncorta", opciones().copy(alineacion = TextAlign.RIGHT))
        val co = textos(d).first { it.texto == "corta" }
        assertEquals(d.ancho, co.x + co.ancho, 0.1)
    }

    @Test fun dentroDeUnaFiguraParteLosRenglonesPorPalabras() {
        val m = componer("uno dos tres cuatro \$x^2\$", opciones().copy(anchoMax = 100.0))
        val t = textos(m)
        assertTrue(t.map { it.y.toInt() }.toSet().size >= 2)
        assertTrue(t.all { it.x + it.texto.length * it.tam * 0.5 <= 100.5 })
    }

    @Test fun elRecuadroRodeaLaFormula() {
        val m = componer("\$\\boxed{\\theta = 90^\\circ}\$")
        val r = rayas(m)
        assertEquals(1, r.size)
        assertEquals("cuatro lados cerrados", 5, r[0].puntos.size)
        assertTrue(textos(m).any { 'θ' in it.texto })
        assertTrue(textos(m).any { '°' in it.texto })
    }

    @Test fun subrayadoYTachadoLlevanSuRaya() {
        val m = componer("<u>sub</u> y ~~tach~~")
        assertEquals(2, rayas(m).size)
        // (Las palabras seguidas de la misma cara van en una pieza; la raya, del ancho de «sub».)
        val sub = textos(m).first { it.texto.startsWith("sub") }
        val raya = rayas(m)[0]
        assertEquals(sub.x, raya.puntos[0].x, 0.01)
        assertEquals(sub.x + 30.0, raya.puntos[1].x, 0.01)
    }

    @Test fun elTamanoEsEnVecesElDelTexto() {
        val t = textos(componer("a \\tam{1.5}{b} \\small{c}"))
        assertEquals(30.0, t.first { it.texto == "b" }.tam, 1e-9)
        assertEquals(16.0, t.first { it.texto == "c" }.tam, 1e-9)
    }

    @Test fun elLatexRotoSeVeComoFuenteYNoSeCuelga() {
        for (roto in listOf(
            "\$\\frac{1}{\$", "\$x^\$", "texto \$sin cerrar", "\\color{rojo}{sin cerrar",
            "\$\\overline{AB\$ y \$\\sqrt[\$", "**sin cerrar", "\$}}}{{{\$",
            "\$\\begin{bmatrix} 1 & 2\$", "\\tam{x}{a}", "\$\\noexiste{x}\$", "\\", "\$", "\$\$"
        )) {
            val m = componer(roto)
            assertTrue(roto, m.ancho.isFinite() && m.alto.isFinite())
            TextoRico.plano(roto)
        }
        assertTrue(textos(componer("\$\\noexiste{x}\$")).any { it.texto == "\\noexiste" })
        assertTrue(textos(componer("texto \$sin cerrar")).any { '$' in it.texto })
        val largo = "\$x^2\$ ".repeat(10_000)
        val antes = System.nanoTime()
        componer(largo)
        assertTrue((System.nanoTime() - antes) / 1e9 < 10.0)
    }

    @Test fun laMedidaEsLaDeLoCompuesto() {
        val (ancho, alto) = TextoRico.medida("**ab**\ncd", opciones(), medidor)
        assertEquals(20.0, ancho, 0.01)
        assertEquals(50.0, alto, 0.01)
    }

    // ---- El lector de fórmulas (casos de `formulas.rs` del PC) ----

    private fun t(s: String) = FormulaRica.plano(FormulaRica.leer(s)).replace(" ", "")

    @Test fun elLectorDeFormulasEsElDelPc() {
        assertEquals("((a/b)/c)", t("\\frac{\\frac{a}{b}}{c}"))
        assertEquals("(1/2)", t("\\frac12"))
        assertEquals("sqrt(x+1)", t("\\sqrt{x+1}"))
        assertEquals("x^n+1", t("x^{n+1}"))
        assertEquals("lnx", t("\\ln x"))
        assertEquals("2sin(3t)", t("2 sin(3t)"))
        assertEquals("rang(A)", FormulaRica.plano(FormulaRica.leer("\\operatorname{rang}(A)")))
        assertEquals("ℝ", t("\\mathbb{R}"))
        assertTrue(FormulaRica.plano(FormulaRica.leer("x \\in \\mathbb{R}, y = 2")).contains("y = 2"))
        val m = FormulaRica.leer("A = \\begin{bmatrix} 2 & -1 & 0 \\\\ 1 & 3 & 2 \\\\ 0 & 1 & 4 \\end{bmatrix}")
        val tabla = (m as FormulaRica.Nodo.Fila).partes.last() as FormulaRica.Nodo.Tabla
        assertEquals(3, tabla.filas.size)
        assertEquals("[", tabla.abre)
        val sis = FormulaRica.leer("\\begin{cases} 2x + y = 5 \\\\ x - y = 1 \\end{cases}") as FormulaRica.Nodo.Tabla
        assertEquals("x-y=1", FormulaRica.plano(sis.filas[1][0]).replace(" ", ""))
        assertEquals("90°", FormulaRica.legible(FormulaRica.leer("90^{\\circ}")))
        assertEquals("v\u20d7", FormulaRica.legible(FormulaRica.leer("\\vec{v}")))
        for (s in listOf("", "^", "_", "{", "}", "\\", "\\frac", "\\sqrt[", "((((", "x^^2", "a/", "/b", "x_{")) {
            FormulaRica.leer(s)
        }
        // Una fracción compuesta: la caja lleva su barra, numerador arriba y denominador abajo.
        val medir = CajasRicas.Medir { s, tam -> s.length * tam * 0.6 to tam * 1.2 }
        val c = CajasRicas.deFormula(FormulaRica.leer("1/x"), 20.0, medir, enLinea = false)
        val uno = c.piezas.filterIsInstance<CajasRicas.Pieza.Texto>().first { it.texto == "1" }
        val x = c.piezas.filterIsInstance<CajasRicas.Pieza.Texto>().first { it.texto == "x" }
        assertEquals(1, c.piezas.count { it is CajasRicas.Pieza.Raya })
        assertTrue("la x va debajo del 1", x.y > uno.y + 10.0)
        assertTrue(x.cursiva && !uno.cursiva)
    }

    // ---- Editar ----

    @Test fun losBotonesEnvuelvenLoElegido() {
        val e = TextoRico.envolver(TextoRico.Edicion("Nos piden X", 10, 11), "\\color{rojo}{", "}")
        assertEquals("Nos piden \\color{rojo}{X}", e.texto)
        assertEquals("X", e.texto.substring(e.desde, e.hasta))
        // Encadenado sobre lo mismo, como en el PC.
        val b = TextoRico.envolver(e, "**", "**")
        assertEquals("Nos piden \\color{rojo}{**X**}", b.texto)
        // Sin nada elegido, las marcas y el cursor en medio.
        val f = TextoRico.envolver(TextoRico.Edicion("a ", 2, 2), "$", "$")
        assertEquals(TextoRico.Edicion("a \$\$", 3, 3), f)
    }

    @Test fun alinearPoneYQuitaLaMarcaDelRenglon() {
        val t = "uno\ndos\ntres"
        val c = TextoRico.alinear(TextoRico.Edicion(t, 5, 5), TextoRico.Alineado.CENTRO)
        assertEquals("uno\n\\centro dos\ntres", c.texto)
        assertEquals(5 + "\\centro ".length, c.desde)
        val d = TextoRico.alinear(c, TextoRico.Alineado.DERECHA)
        assertEquals("uno\n\\derecha dos\ntres", d.texto)
        val todo = TextoRico.alinear(TextoRico.Edicion(d.texto, 0, d.texto.length), null)
        assertEquals(t, todo.texto)
        assertEquals(TextoRico.Alineado.DERECHA, TextoRico.leer(d.texto)[1].alineado)
    }
}
