package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La barra de formato: el orden de los botones y lo que hace cada uno.
 *
 * Las reglas son las de `EditTextCaption.toggleStyleForSelection` de Telegram
 * —alternar por cobertura, código excluyente, quitar formato lo quita todo—
 * aplicadas sobre texto Markdown en vez de sobre spans.
 */
class FormatoTest {

    private fun aplicar(f: Formato, texto: String, a: Int, b: Int, dato: String? = null) =
        MarkdownEdit.aplicar(f, texto, a, b, dato)

    // ---- El reparto de botones ----

    @Test
    fun `no se repite ningun boton entre las islas`() {
        assertEquals(BarraDeFormato.todos.size, BarraDeFormato.todos.toSet().size)
    }

    @Test
    fun `estan todos los formatos en la barra`() {
        assertEquals(Formato.entries.toSet(), BarraDeFormato.todos.toSet())
    }

    /**
     * Cada isla es una familia: los estilos por un lado y lo que inserta por
     * otro, como sus `formattingLayout`. Mezclarlas rompería la única pista que
     * da la separación.
     */
    @Test
    fun `las islas agrupan por familia`() {
        assertTrue(BarraDeFormato.estilos.all { it.esEnvolvente || it == Formato.CITA })
        assertEquals(listOf(Formato.ENLACE, Formato.FECHA), BarraDeFormato.insertar)
        assertTrue(BarraDeFormato.pildoras.all { it.isNotEmpty() })
    }

    @Test
    fun `quitar formato va el ultimo, que es lo unico que destruye`() {
        assertEquals(Formato.QUITAR, BarraDeFormato.desbordamiento.last())
    }

    // ---- Alternar por cobertura ----

    @Test
    fun `el boton pone y el mismo boton quita`() {
        val puesto = aplicar(Formato.NEGRITA, "hola mundo", 5, 10)
        assertEquals("hola **mundo**", puesto.text)

        val quitado = aplicar(Formato.NEGRITA, puesto.text, puesto.selStart, puesto.selEnd)
        assertEquals("hola mundo", quitado.text)
    }

    @Test
    fun `el estilo de la seleccion enciende el boton`() {
        val e = MarkdownEdit.estiloDeLaSeleccion("hola **mundo**", 7, 12)
        assertTrue(Formato.NEGRITA in e)
        assertFalse(Formato.CURSIVA in e)
    }

    /**
     * El caso que rompía: `*` es el principio de `**`. Sin distinguirlos, darle
     * a cursiva dentro de una negrita se comía un asterisco de cada lado y
     * dejaba la negrita rota en vez de añadir la cursiva.
     */
    @Test
    fun `la cursiva dentro de una negrita no rompe la negrita`() {
        val r = aplicar(Formato.CURSIVA, "**mundo**", 2, 7)
        assertEquals("***mundo***", r.text)

        val e = MarkdownEdit.estiloDeLaSeleccion("**mundo**", 2, 7)
        assertTrue(Formato.NEGRITA in e)
        assertFalse(Formato.CURSIVA in e)
    }

    // ---- El código es excluyente ----

    @Test
    fun `poner codigo se lleva por delante las otras marcas`() {
        val r = aplicar(Formato.CODIGO, "a **b** c", 0, 9)
        assertEquals("`a b c`", r.text)
    }

    @Test
    fun `poner negrita sobre codigo saca el texto del codigo`() {
        val r = aplicar(Formato.NEGRITA, "`x`", 0, 3)
        assertEquals("**x**", r.text)
    }

    // ---- Enlace ----

    @Test
    fun `el enlace envuelve con la url`() {
        val r = aplicar(Formato.ENLACE, "mira esto ya", 5, 9, "https://a.com")
        assertEquals("mira [esto](https://a.com) ya", r.text)
        assertEquals("esto", r.text.substring(r.selStart, r.selEnd))
    }

    @Test
    fun `sin url deja el cursor entre los parentesis`() {
        val r = aplicar(Formato.ENLACE, "mira esto", 5, 9, "")
        assertEquals("mira [esto]()", r.text)
        assertEquals(r.selStart, r.selEnd)
        assertEquals("[esto](", r.text.substring(5, r.selStart))
    }

    @Test
    fun `sobre un enlace entero lo deshace y deja el texto`() {
        val texto = "[esto](https://a.com)"
        val r = aplicar(Formato.ENLACE, texto, 0, texto.length, "https://b.com")
        assertEquals("esto", r.text)
    }

    // ---- Marcas de línea sobre varias líneas ----

    @Test
    fun `la cita marca todas las lineas de la seleccion`() {
        val texto = "uno\ndos\ntres"
        val r = aplicar(Formato.CITA, texto, 0, texto.length)
        assertEquals("> uno\n> dos\n> tres", r.text)
    }

    @Test
    fun `si ya la tienen todas, se la quita a todas`() {
        val texto = "> uno\n> dos"
        val r = aplicar(Formato.CITA, texto, 0, texto.length)
        assertEquals("uno\ndos", r.text)
    }

    @Test
    fun `si solo algunas la tienen, se la pone a las que faltan`() {
        val texto = "> uno\ndos"
        val r = aplicar(Formato.CITA, texto, 0, texto.length)
        assertEquals("> uno\n> dos", r.text)
    }

    /** Diez veces «1.» sería una lista de diez unos. */
    @Test
    fun `la lista numerada numera de verdad`() {
        val texto = "uno\ndos\ntres"
        val r = aplicar(Formato.NUMERADA, texto, 0, texto.length)
        assertEquals("1. uno\n2. dos\n3. tres", r.text)
    }

    @Test
    fun `el estilo de linea solo se enciende si lo llevan todas`() {
        val mitad = MarkdownEdit.estiloDeLaSeleccion("> uno\ndos", 0, 9)
        assertFalse(Formato.CITA in mitad)

        val todas = MarkdownEdit.estiloDeLaSeleccion("> uno\n> dos", 0, 11)
        assertTrue(Formato.CITA in todas)
    }

    // ---- Bloque de código ----

    @Test
    fun `el bloque encierra entre vallas y vuelve a sacarlo`() {
        val puesto = aplicar(Formato.BLOQUE, "val x = 1", 0, 9)
        assertEquals("```\nval x = 1\n```", puesto.text)
        assertEquals("val x = 1", puesto.text.substring(puesto.selStart, puesto.selEnd))

        val quitado = aplicar(Formato.BLOQUE, puesto.text, 0, puesto.text.length)
        assertEquals("val x = 1", quitado.text)
    }

    @Test
    fun `dentro de un bloque el boton de bloque sale encendido`() {
        val texto = "```\nval x = 1\n```"
        val e = MarkdownEdit.estiloDeLaSeleccion(texto, 5, 12)
        assertTrue(Formato.BLOQUE in e)
    }

    // ---- Quitar formato ----

    @Test
    fun `quitar formato deja el texto pelado`() {
        val texto = "# Titulo **con** _cosas_ y [un enlace](https://a.com)"
        val r = aplicar(Formato.QUITAR, texto, 0, texto.length)
        assertEquals("Titulo con cosas y un enlace", r.text)
    }

    @Test
    fun `quitar formato respeta las lineas y sus marcas`() {
        val texto = "- **uno**\n- dos\n> tres"
        val r = aplicar(Formato.QUITAR, texto, 0, texto.length)
        assertEquals("uno\ndos\ntres", r.text)
    }

    /**
     * La promesa del parser se hereda aquí: de quitar formato no puede salir
     * menos texto del que entró, solo menos marcas.
     */
    @Test
    fun `quitar formato no se come texto suelto`() {
        val texto = "2 * 3 = 6 y array[0] vale 3"
        val r = aplicar(Formato.QUITAR, texto, 0, texto.length)
        assertEquals(texto, r.text)
    }

    @Test
    fun `sin seleccion, quitar formato no toca nada`() {
        val texto = "**algo**"
        val r = aplicar(Formato.QUITAR, texto, 3, 3)
        assertEquals(texto, r.text)
    }

    // ---- Fecha ----

    @Test
    fun `la fecha se mete donde esta el cursor`() {
        val r = aplicar(Formato.FECHA, "hoy: ", 5, 5, "8 ago 2026")
        assertEquals("hoy: 8 ago 2026", r.text)
        assertEquals(r.text.length, r.selStart)
    }

    // ---- Basura ----

    @Test
    fun `indices desbordados no revientan ningun formato`() {
        Formato.entries.forEach { f ->
            val r = MarkdownEdit.aplicar(f, "hola", -5, 99, "x")
            assertTrue("${f.name} perdio el texto", r.text.contains("hola") || f == Formato.FECHA)
        }
    }

    @Test
    fun `texto vacio no revienta ningun formato`() {
        Formato.entries.forEach { f ->
            MarkdownEdit.aplicar(f, "", 0, 0, "x")
        }
    }
}
