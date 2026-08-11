package com.forge.pixpin.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los bloques del editor enriquecido de Telegram, portados a Markdown.
 *
 * Se comprueba lo que hace falta para que el editor no mienta: que lo que
 * escribe cada plantilla sea lo que el parser reconoce después. Si esas dos
 * mitades se separan, el botón pone algo que no se ve, que es el peor fallo
 * posible en un editor porque parece que no ha pasado nada.
 */
class BloquesTest {

    // ---- El catálogo ----

    @Test
    fun `no hay dos bloques con el mismo tipo`() {
        assertEquals(Bloques.todos.size, Bloques.todos.map { it.tipo }.toSet().size)
    }

    @Test
    fun `todos los tipos estan en el catalogo`() {
        assertEquals(TipoDeBloque.entries.toSet(), Bloques.todos.map { it.tipo }.toSet())
    }

    @Test
    fun `todo bloque tiene al menos un atajo`() {
        Bloques.todos.forEach { assertTrue(it.nombre, it.atajos.isNotEmpty()) }
    }

    /**
     * La prueba que une las dos mitades: lo que escribe la plantilla tiene que
     * volver a salir del parser como el bloque que se pidió.
     */
    @Test
    fun `lo que escribe cada plantilla lo reconoce el parser`() {
        TipoDeBloque.entries.forEach { tipo ->
            val p = Bloques.plantilla(tipo)
            // Los medios se rellenan con una ruta: la plantilla los deja vacíos
            // a propósito para que el selector escriba dentro.
            val relleno = if (Bloques.pideArchivo(tipo)) "/tmp/x.png" else "contenido"
            val texto = p.antes + relleno + p.despues
            val bloques = Markdown.parse(texto)

            assertTrue("$tipo no produjo nada", bloques.isNotEmpty())
            val primero = bloques.first()
            val bien = when (tipo) {
                TipoDeBloque.TITULO_1 -> primero is MarkdownBlock.Heading && primero.level == 1
                TipoDeBloque.TITULO_2 -> primero is MarkdownBlock.Heading && primero.level == 2
                TipoDeBloque.TITULO_3 -> primero is MarkdownBlock.Heading && primero.level == 3
                TipoDeBloque.TITULO_4 -> primero is MarkdownBlock.Heading && primero.level == 4
                TipoDeBloque.TITULO_5 -> primero is MarkdownBlock.Heading && primero.level == 5
                TipoDeBloque.TITULO_6 -> primero is MarkdownBlock.Heading && primero.level == 6
                TipoDeBloque.CITA -> primero is MarkdownBlock.Quote
                TipoDeBloque.LISTA -> primero is MarkdownBlock.Bullet
                TipoDeBloque.NUMERADA -> primero is MarkdownBlock.Numbered
                TipoDeBloque.TAREAS -> primero is MarkdownBlock.Tarea
                TipoDeBloque.CODIGO -> primero is MarkdownBlock.Code
                TipoDeBloque.FORMULA -> primero is MarkdownBlock.Formula
                TipoDeBloque.SEPARADOR -> primero is MarkdownBlock.Rule
                TipoDeBloque.TABLA -> primero is MarkdownBlock.Tabla
                TipoDeBloque.PLEGABLE ->
                    primero is MarkdownBlock.Caja && primero.tipo == TipoDeCaja.PLEGABLE
                TipoDeBloque.DESTACADO ->
                    primero is MarkdownBlock.Caja && primero.tipo == TipoDeCaja.DESTACADO
                TipoDeBloque.PIE ->
                    primero is MarkdownBlock.Caja && primero.tipo == TipoDeCaja.PIE
                TipoDeBloque.CENTRAR ->
                    primero is MarkdownBlock.Caja && primero.tipo == TipoDeCaja.CENTRO
                TipoDeBloque.DERECHA ->
                    primero is MarkdownBlock.Caja && primero.tipo == TipoDeCaja.DERECHA
                TipoDeBloque.IMAGEN, TipoDeBloque.VIDEO,
                TipoDeBloque.AUDIO, TipoDeBloque.ARCHIVO -> primero is MarkdownBlock.Medio
            }
            assertTrue("$tipo salió como ${primero::class.simpleName}", bien)
        }
    }

    // ---- Buscar, que es su RichCommand.matches ----

    @Test
    fun `buscar por el principio del nombre`() {
        val r = Bloques.buscar("tab")
        assertTrue(r.any { it.tipo == TipoDeBloque.TABLA })
    }

    @Test
    fun `buscar por el atajo con o sin barra`() {
        assertTrue(Bloques.buscar("/tabla").any { it.tipo == TipoDeBloque.TABLA })
        assertTrue(Bloques.buscar("tabla").any { it.tipo == TipoDeBloque.TABLA })
    }

    @Test
    fun `los sinonimos tambien encuentran`() {
        assertTrue(Bloques.buscar("latex").any { it.tipo == TipoDeBloque.FORMULA })
        assertTrue(Bloques.buscar("foto").any { it.tipo == TipoDeBloque.IMAGEN })
        assertTrue(Bloques.buscar("casillas").any { it.tipo == TipoDeBloque.TAREAS })
    }

    /** Por el principio, no por «contiene»: si no, dos letras sacan media lista. */
    @Test
    fun `no encuentra por el medio de la palabra`() {
        assertFalse(Bloques.buscar("abla").any { it.tipo == TipoDeBloque.TABLA })
    }

    @Test
    fun `sin nada escrito salen todos`() {
        assertEquals(Bloques.todos.size, Bloques.buscar("").size)
    }

    // ---- La barra que abre la lista ----

    @Test
    fun `la barra al principio de linea abre comando`() {
        assertEquals("tab", Comandos.consulta("/tab", 4))
    }

    @Test
    fun `la barra tras un espacio abre comando`() {
        assertEquals("ta", Comandos.consulta("hola /ta", 8))
    }

    /** El caso que arruinaría escribir: una fecha no puede abrir la lista. */
    @Test
    fun `una fecha no abre comando`() {
        assertNull(Comandos.consulta("el 12/03 quedamos", 8))
    }

    @Test
    fun `una ruta no abre comando`() {
        assertNull(Comandos.consulta("mira a/b", 8))
    }

    @Test
    fun `sin barra no hay comando`() {
        assertNull(Comandos.consulta("tabla", 5))
    }

    @Test
    fun `la barra sola abre la lista entera`() {
        assertEquals("", Comandos.consulta("/", 1))
    }

    @Test
    fun `elegir se lleva el comando escrito`() {
        val r = Comandos.elegir("hola /tab", 9, TipoDeBloque.TABLA)
        assertFalse(r.text.contains("/tab"))
        assertTrue(r.text.startsWith("hola "))
        assertTrue(Markdown.parse(r.text).any { it is MarkdownBlock.Tabla })
    }

    @Test
    fun `un bloque de linea entera no se mete en mitad de un parrafo`() {
        val r = Comandos.elegir("texto de antes", 14, TipoDeBloque.TABLA)
        assertTrue(r.text.startsWith("texto de antes\n"))
    }

    @Test
    fun `indices desbordados no revientan al elegir`() {
        TipoDeBloque.entries.forEach { Comandos.elegir("hola", 99, it) }
        TipoDeBloque.entries.forEach { Comandos.elegir("", 0, it) }
    }
}
