package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las tablas con el modelo de Telegram: celdas fusionadas, cabecera por celda,
 * alineación y altura por celda, y título.
 *
 * Lo que más se vigila es que **se escriba lo mínimo**: una tabla normal tiene
 * que seguir guardándose con barras, para que el archivo se lea a simple vista,
 * y solo pasar a HTML cuando use algo que Markdown no sabe decir.
 */
class TablasTest {

    private val simple = "| a | b |\n|---|---|\n| 1 | 2 |"

    private fun celda(t: MarkdownBlock.Tabla, f: Int, c: Int): Celda? =
        Tablas.rejilla(t).getOrNull(f)?.getOrNull(c)?.celda

    // ---- Leer y escribir ----

    @Test
    fun `una tabla de markdown se lee`() {
        val t = Tablas.leer(simple)!!
        assertEquals(2, t.filas.size)
        assertEquals(2, t.columnas)
        assertEquals("a", t.filas[0][0].contenido.text)
        assertEquals("2", t.filas[1][1].contenido.text)
        assertTrue(t.filas[0].all { it.cabecera })
        assertFalse(t.filas[1].any { it.cabecera })
    }

    /** Lo normal se sigue guardando con barras, no en HTML. */
    @Test
    fun `una tabla normal no se convierte en html`() {
        val t = Tablas.leer(simple)!!
        assertFalse(t.esAvanzada)
        val escrita = Tablas.aTexto(t)
        assertFalse("salio en html: $escrita", escrita.contains("<table"))
        assertTrue(escrita.startsWith("|"))
    }

    @Test
    fun `escribir y volver a leer no pierde nada`() {
        val t = Tablas.leer(simple)!!
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertEquals(t.columnas, vuelta.columnas)
        assertEquals(t.filas.size, vuelta.filas.size)
        assertEquals("a", vuelta.filas[0][0].contenido.text)
    }

    @Test
    fun `la alineacion sobrevive a la ida y vuelta`() {
        val t = Tablas.alinear(Tablas.leer(simple)!!, 0, 1, Alineacion.DERECHA)
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertEquals(Alineacion.DERECHA, celda(vuelta, 0, 1)!!.alineacion)
        assertEquals(Alineacion.DERECHA, celda(vuelta, 1, 1)!!.alineacion)
    }

    @Test
    fun `los estilos dentro de una celda sobreviven`() {
        val t = Tablas.leer("| **a** | b |\n|---|---|\n| 1 | 2 |")!!
        assertTrue(t.filas[0][0].contenido.tramos().any { it.tiene(SpanKind.BOLD) })
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertTrue(vuelta.filas[0][0].contenido.tramos().any { it.tiene(SpanKind.BOLD) })
    }

    // ---- Lo que obliga a pasar a HTML ----

    @Test
    fun `fusionar obliga a html y se vuelve a leer igual`() {
        val t = Tablas.fusionar(Tablas.leer(simple)!!, 0, 0, 0, 1)
        assertTrue(t.esAvanzada)

        val escrita = Tablas.aTexto(t)
        assertTrue("no salio en html: $escrita", escrita.contains("<table"))
        assertTrue(escrita.contains("colspan=\"2\""))

        val vuelta = Tablas.leer(escrita)!!
        assertEquals(2, celda(vuelta, 0, 0)!!.anchoEnColumnas)
        assertEquals("a\nb", celda(vuelta, 0, 0)!!.contenido.text)
    }

    @Test
    fun `el titulo obliga a html`() {
        val t = Tablas.conTitulo(Tablas.leer(simple)!!, InlineText("Gastos"))
        assertTrue(t.esAvanzada)
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertEquals("Gastos", vuelta.titulo.text)
    }

    @Test
    fun `la altura obliga a html`() {
        val t = Tablas.aLaAltura(Tablas.leer(simple)!!, 1, 0, AlturaEnCelda.ABAJO)
        assertTrue(t.esAvanzada)
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertEquals(AlturaEnCelda.ABAJO, celda(vuelta, 1, 0)!!.altura)
    }

    @Test
    fun `una cabecera que no es la primera fila obliga a html`() {
        val t = Tablas.comoCabecera(Tablas.leer(simple)!!, 1, 0, true)
        assertTrue(t.esAvanzada)
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertTrue(celda(vuelta, 1, 0)!!.cabecera)
    }

    // ---- Fusionar, su mergeCells ----

    @Test
    fun `fusionar junta los textos con saltos`() {
        val t = Tablas.fusionar(Tablas.leer(simple)!!, 0, 0, 1, 1)
        val ancla = celda(t, 0, 0)!!
        assertEquals(2, ancla.anchoEnColumnas)
        assertEquals(2, ancla.altoEnFilas)
        assertEquals("a\nb\n1\n2", ancla.contenido.text)
    }

    /** Toda la rejilla queda tapada por el ancla, sin huecos sueltos. */
    @Test
    fun `la fusion tapa todos sus huecos`() {
        val t = Tablas.fusionar(Tablas.leer(simple)!!, 0, 0, 1, 1)
        val rejilla = Tablas.rejilla(t)
        var anclas = 0
        rejilla.forEach { fila ->
            fila.forEach { h ->
                assertNotNull("hay un hueco sin celda", h)
                if (h!!.esElAncla) anclas++
            }
        }
        assertEquals("deberia quedar una sola celda", 1, anclas)
    }

    @Test
    fun `fusionar una sola celda no hace nada`() {
        val t = Tablas.leer(simple)!!
        assertEquals(t, Tablas.fusionar(t, 0, 0, 0, 0))
    }

    /**
     * Su comprobación: media celda fusionada no puede quedar dentro y media
     * fuera. Ellos rechazan; aquí el rectángulo se agranda hasta cubrirla, que
     * hace lo que se esperaba en vez de no hacer nada.
     */
    @Test
    fun `fusionar sobre una fusion existente la absorbe entera`() {
        var t = Tablas.nueva(3, 3)
        t = Tablas.fusionar(t, 0, 0, 0, 1)
        t = Tablas.fusionar(t, 0, 1, 0, 2)
        val ancla = celda(t, 0, 0)!!
        assertEquals(3, ancla.anchoEnColumnas)
    }

    @Test
    fun `separar devuelve las celdas que tapaba`() {
        val fusionada = Tablas.fusionar(Tablas.leer(simple)!!, 0, 0, 0, 1)
        val suelta = Tablas.separar(fusionada, 0, 0)
        assertEquals(1, celda(suelta, 0, 0)!!.anchoEnColumnas)
        assertEquals(2, suelta.columnas)
        Tablas.rejilla(suelta).forEach { fila ->
            fila.forEach { assertTrue(it!!.esElAncla) }
        }
        // Y al no quedar nada avanzado, vuelve a guardarse con barras.
        assertFalse(suelta.esAvanzada)
    }

    @Test
    fun `separar algo que no esta fusionado no hace nada`() {
        val t = Tablas.leer(simple)!!
        assertEquals(t, Tablas.separar(t, 0, 0))
    }

    // ---- Filas y columnas ----

    @Test
    fun `insertar fila y columna en el sitio pedido`() {
        var t = Tablas.leer(simple)!!
        t = Tablas.insertarFila(t, 1)
        assertEquals(3, t.filas.size)
        assertEquals("", t.filas[1][0].contenido.text)
        assertEquals("1", t.filas[2][0].contenido.text)

        t = Tablas.insertarColumna(t, 0)
        assertEquals(3, t.columnas)
        assertEquals("a", celda(t, 0, 1)!!.contenido.text)
    }

    /** Su `deleteRows` borra varias a la vez. */
    @Test
    fun `quitar varias filas de una vez`() {
        var t = Tablas.nueva(4, 2)
        t = Tablas.conCelda(t, 3, 0, InlineText("ultima"))
        val menos = Tablas.quitarFilas(t, setOf(1, 2))
        assertEquals(2, menos.filas.size)
        assertEquals("ultima", menos.filas[1][0].contenido.text)
    }

    @Test
    fun `quitar varias columnas de una vez`() {
        val t = Tablas.nueva(2, 4)
        val menos = Tablas.quitarColumnas(t, setOf(0, 2))
        assertEquals(2, menos.columnas)
        menos.filas.forEach { assertEquals(2, it.size) }
    }

    @Test
    fun `no se puede vaciar la tabla del todo`() {
        val t = Tablas.leer(simple)!!
        assertEquals(t, Tablas.quitarFilas(t, setOf(0, 1)))
        assertEquals(t, Tablas.quitarColumnas(t, setOf(0, 1)))
    }

    // ---- El sitio de cada celda en su fila ----

    /**
     * El fallo que había: la fila guarda **solo las anclas**, así que con una
     * fusión la celda que se ve en la columna 2 puede ser la primera de la
     * lista. Escribiendo con el número de columna se escribía en la de al lado.
     */
    @Test
    fun `el indice en la fila no es el de la rejilla`() {
        var t = Tablas.nueva(2, 3)
        t = Tablas.fusionar(t, 0, 0, 0, 1)

        val anclas = anclasDe(t)
        val segunda = anclas.first { it.fila == 0 && it.columna == 2 }
        // Se ve en la columna 2, pero es la SEGUNDA de su fila.
        assertEquals(1, segunda.indiceEnLaFila)
    }

    @Test
    fun `escribir en una celda de una fila con fusion no toca a la vecina`() {
        var t = Tablas.nueva(2, 3)
        t = Tablas.fusionar(t, 0, 0, 0, 1)
        val ancla = anclasDe(t).first { it.fila == 0 && it.columna == 2 }

        t = Tablas.conCelda(t, ancla.fila, ancla.indiceEnLaFila, InlineText("tercera"))
        assertEquals("tercera", celda(t, 0, 2)!!.contenido.text)
        // Y la fusionada de al lado sigue vacía.
        assertEquals("", celda(t, 0, 0)!!.contenido.text)
    }

    @Test
    fun `las anclas cubren la tabla sin repetirse`() {
        var t = Tablas.nueva(3, 3)
        t = Tablas.fusionar(t, 0, 0, 1, 1)
        val anclas = anclasDe(t)

        // Una celda de 2x2 más las cinco sueltas que quedan de la esquina.
        assertEquals(6, anclas.size)
        assertEquals(anclas.size, anclas.map { it.fila to it.columna }.toSet().size)

        val huecos = anclas.sumOf { it.celda.anchoEnColumnas * it.celda.altoEnFilas }
        assertEquals("las anclas no cubren la rejilla entera", 9, huecos)
    }

    /** Una fusión que baja dos filas tiene que decir que ocupa dos filas. */
    @Test
    fun `una fusion vertical se guarda y se lee`() {
        var t = Tablas.nueva(3, 2)
        t = Tablas.fusionar(t, 0, 0, 1, 0)
        assertEquals(2, celda(t, 0, 0)!!.altoEnFilas)

        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertEquals(2, celda(vuelta, 0, 0)!!.altoEnFilas)
        assertEquals(1, celda(vuelta, 0, 0)!!.anchoEnColumnas)
        // Y la fila 1 sigue teniendo su celda de la derecha.
        assertEquals(3, vuelta.filas.size)
        assertTrue(anclasDe(vuelta).any { it.fila == 1 && it.columna == 1 })
    }

    // ---- Lo marcado: fila, columna o rectángulo ----

    @Test
    fun `marcar una columna la coge entera`() {
        val t = Tablas.nueva(3, 4)
        val m = Tablas.Marcado.columna(1, t.filas.size)
        assertEquals(0..2, m.filas)
        assertEquals(1..1, m.columnas)
        assertTrue((2 to 1) in m)
        assertFalse((2 to 0) in m)
    }

    @Test
    fun `marcar una fila la coge entera`() {
        val t = Tablas.nueva(3, 4)
        val m = Tablas.Marcado.fila(2, t.columnas)
        assertEquals(2..2, m.filas)
        assertEquals(0..3, m.columnas)
        assertTrue((2 to 3) in m)
    }

    /** Centrar una columna centra sus cinco celdas, no solo la de arriba. */
    @Test
    fun `alinear lo marcado toca todas sus celdas`() {
        var t = Tablas.nueva(3, 2)
        t = Tablas.enElMarcado(t, Tablas.Marcado.columna(1, 3)) {
            it.copy(alineacion = Alineacion.CENTRO)
        }
        (0..2).forEach { f ->
            assertEquals("fila $f", Alineacion.CENTRO, celda(t, f, 1)!!.alineacion)
            assertEquals("fila $f", Alineacion.IZQUIERDA, celda(t, f, 0)!!.alineacion)
        }
    }

    /** Una celda fusionada se cambia UNA vez, no una por cada hueco que ocupa. */
    @Test
    fun `lo marcado no cambia dos veces una celda fusionada`() {
        var t = Tablas.nueva(2, 3)
        t = Tablas.fusionar(t, 0, 0, 0, 1)
        t = Tablas.enElMarcado(t, Tablas.Marcado(0, 0, 0, 2)) {
            it.copy(altura = AlturaEnCelda.MEDIO)
        }
        assertEquals(AlturaEnCelda.MEDIO, celda(t, 0, 0)!!.altura)
        assertEquals(AlturaEnCelda.MEDIO, celda(t, 0, 2)!!.altura)
        // Y la fusión sigue siendo de dos columnas, no se ha tocado su tamaño.
        assertEquals(2, celda(t, 0, 0)!!.anchoEnColumnas)
    }

    @Test
    fun `quitar lo marcado se lleva la fila o la columna entera`() {
        val t = Tablas.nueva(4, 3)
        val menos = Tablas.quitarFilas(t, Tablas.Marcado.fila(1, 3).filas.toSet())
        assertEquals(3, menos.filas.size)

        val menosC = Tablas.quitarColumnas(t, Tablas.Marcado.columna(0, 4).columnas.toSet())
        assertEquals(2, menosC.columnas)
    }

    @Test
    fun `combinar lo marcado usa las dos esquinas`() {
        var t = Tablas.nueva(3, 3)
        val m = Tablas.Marcado(0, 0, 1, 1)
        t = Tablas.fusionar(t, m.filas.first, m.columnas.first, m.filas.last, m.columnas.last)
        assertEquals(2, celda(t, 0, 0)!!.anchoEnColumnas)
        assertEquals(2, celda(t, 0, 0)!!.altoEnFilas)
    }

    // ---- Basura ----

    @Test
    fun `lo que no es tabla no se lee como tabla`() {
        assertNull(Tablas.leer("un parrafo"))
        assertNull(Tablas.leer("| una fila sola |"))
        assertNull(Tablas.leer("<table></table>"))
    }

    @Test
    fun `un html raro no revienta y se aprovecha lo que se pueda`() {
        val t = Tablas.leer("<table><tr><td colspan=\"loquesea\">x</td></tr></table>")
        assertNotNull(t)
        assertEquals(1, t!!.filas[0][0].anchoEnColumnas)
        assertEquals("x", t.filas[0][0].contenido.text)
    }

    @Test
    fun `los signos de html dentro de una celda no rompen la tabla`() {
        val t = Tablas.conTitulo(
            Tablas.conCelda(Tablas.leer(simple)!!, 1, 0, InlineText("a < b & c > d")),
            InlineText("T")
        )
        val vuelta = Tablas.leer(Tablas.aTexto(t))!!
        assertEquals("a < b & c > d", celda(vuelta, 1, 0)!!.contenido.text)
    }

    @Test
    fun `indices imposibles no revientan`() {
        val t = Tablas.leer(simple)!!
        Tablas.alinear(t, 99, 99, Alineacion.CENTRO)
        Tablas.fusionar(t, -5, -5, 99, 99)
        Tablas.separar(t, 99, 99)
        Tablas.insertarFila(t, 99)
        Tablas.quitarColumnas(t, setOf(99))
    }

    // ---- El parser del documento las ve ----

    @Test
    fun `el parser reconoce las dos formas`() {
        assertTrue(Markdown.parse(simple).first() is MarkdownBlock.Tabla)
        val html = Tablas.aTexto(Tablas.fusionar(Tablas.leer(simple)!!, 0, 0, 0, 1))
        assertTrue(Markdown.parse(html).first() is MarkdownBlock.Tabla)
    }

    @Test
    fun `una tabla html es un solo trozo del documento`() {
        val html = Tablas.aTexto(Tablas.conTitulo(Tablas.leer(simple)!!, InlineText("T")))
        val doc = "antes\n\n$html\n\ndespues"
        val trozos = trozosDe(doc)
        assertEquals(doc, trozos.joinToString("") { it.de(doc) })
        assertTrue(trozos.any { Tablas.esTabla(it.de(doc).trim()) })
        assertEquals(Markdown.parse(doc), trozos.flatMap { Markdown.parse(it.de(doc)) })
    }
}
