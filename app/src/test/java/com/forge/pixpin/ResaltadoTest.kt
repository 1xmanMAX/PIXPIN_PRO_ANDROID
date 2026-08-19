package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Que el subrayado caiga **donde casó** y no un carácter más allá.
 *
 * Un resaltado mal calculado no se estrella: pinta. Pinta el trozo de al lado y ahí se
 * queda, sin excepción ni registro, hasta que alguien mira una nota con un nombre acentuado
 * y no sabe explicar por qué está subrayado a medias. Lo único que caza eso es medir lo que
 * sale de aplicar los rangos al texto original, que es lo que hacen la mitad de estas
 * pruebas.
 *
 * La otra mitad comprueba que el resaltado y [buscar] **no se separan**: todo lo que el
 * filtro deja pasar tiene que poder marcarse, porque un resultado sin marca se lee como un
 * fallo de la búsqueda.
 */
class ResaltadoTest {

    /** Lo que se vería subrayado, sacado del texto original con los rangos devueltos. */
    private fun marcado(texto: String, consulta: String): List<String> =
        tramosQueCasan(texto, consulta).map { texto.substring(it.first, it.last + 1) }

    // ---- Que los índices valgan sobre el original -------------------------

    /**
     * **La prueba que caza el fallo de índices.**
     *
     * Si los rangos se calcularan sobre un texto normalizado de otra longitud, lo recortado
     * mediría lo que mide, pero no sería lo buscado. Se mide sobre casos con tilde, con `ñ`
     * y con la `İ` turca, que es la única letra de Unicode que cambia de longitud al bajar
     * de caja y por tanto la única que descoloca todo lo que viene detrás.
     */
    @Test
    fun `lo recortado con los rangos mide lo mismo que la consulta`() {
        val casos = listOf(
            "Práctica 3.pdf" to "practica",
            "Cañón del Sil" to "canon",
            "MAÑANA sin falta" to "mañ",
            "Café İstanbul mañana" to "manana",
            "resumen del resumen" to "resumen",
            "über alles" to "UBER"
        )
        for ((texto, consulta) in casos) {
            val tramos = tramosQueCasan(texto, consulta)
            assertTrue("«$consulta» tendría que aparecer en «$texto»", tramos.isNotEmpty())
            for (t in tramos) {
                val trozo = texto.substring(t.first, t.last + 1)
                assertEquals("«$trozo» no mide lo que «$consulta»", consulta.length, trozo.length)
                // Y no basta con que mida igual: tiene que ser lo mismo una vez quitados
                // los acentos, o estaría marcando el trozo de al lado con el largo correcto.
                assertEquals(
                    "«$trozo» no es lo que se buscaba",
                    sinAcentosUnoAUno(consulta),
                    sinAcentosUnoAUno(trozo)
                )
            }
        }
    }

    /**
     * **La `İ` turca es el caso que rompe la solución ingenua.**
     *
     * `"İ".lowercase()` devuelve dos caracteres —la `i` y un punto combinante—, así que el
     * texto pasado por `sinAcentos` mide uno más que el original y todo lo que va detrás
     * queda corrido. Un nombre propio en una nota basta para descolocar el subrayado del
     * resto de la línea.
     */
    @Test
    fun `un nombre con la i turca no descoloca lo que viene detras`() {
        val texto = "Café İstanbul mañana"
        // El error que se está evitando: por aquí el índice saldría 15 en vez de 14.
        assertEquals(texto.length + 1, sinAcentos(texto).length)
        assertEquals(texto.length, sinAcentosUnoAUno(texto).length)

        assertEquals(listOf("mañana"), marcado(texto, "manana"))
        assertEquals(listOf(14..19), tramosQueCasan(texto, "manana"))
    }

    /**
     * La invariante de la que depende todo lo demás, comprobada de una vez para **todos**
     * los caracteres y no solo para los que se le ocurran a quien escriba una prueba.
     */
    @Test
    fun `normalizar un caracter siempre devuelve un caracter`() {
        for (c in Char.MIN_VALUE..Char.MAX_VALUE) {
            assertEquals(
                "U+%04X cambia de longitud".format(c.code),
                1,
                sinAcentosUnoAUno(c.toString()).length
            )
        }
    }

    /** Y sin separarse de [sinAcentos] allá donde esta sí conserva la longitud. */
    @Test
    fun `normalizar coincide con sinAcentos salvo donde este no cabe`() {
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            val dela = sinAcentos(c.toString())
            if (dela.length == 1) {
                assertEquals("U+%04X no coincide".format(code), dela, sinAcentosUnoAUno(c.toString()))
            }
        }
    }

    // ---- Mayúsculas y acentos --------------------------------------------

    @Test
    fun `encuentra el tramo sin acentos de por medio`() {
        assertEquals(listOf("taller"), marcado("factura taller.pdf", "taller"))
        assertEquals(listOf(8..13), tramosQueCasan("factura taller.pdf", "taller"))
    }

    /** Con tilde en el texto y sin ella en la consulta: es como se escribe con prisa. */
    @Test
    fun `el texto con acento casa con la consulta sin acento`() {
        assertEquals(listOf("Cañón"), marcado("Cañón del Sil", "canon"))
        assertEquals(listOf("Práctica"), marcado("Práctica 3.pdf", "practica"))
    }

    /** Y al revés, que también pasa: el nombre se guardó sin tilde y uno la escribe. */
    @Test
    fun `el texto sin acento casa con la consulta con acento`() {
        assertEquals(listOf("MANANA"), marcado("MANANA sin falta", "mañana"))
        assertEquals(listOf("canon"), marcado("el canon del Sil", "cañón"))
    }

    @Test
    fun `las mayusculas mezcladas dan igual`() {
        assertEquals(listOf("MAÑ"), marcado("MAÑANA sin falta", "mañ"))
        assertEquals(listOf("HoLa"), marcado("HoLa mundo", "hola"))
    }

    // ---- Todas las apariciones -------------------------------------------

    /** Marcar solo la primera obliga a leerse la nota entera igual, que era el problema. */
    @Test
    fun `se marcan todas las apariciones y no solo la primera`() {
        assertEquals(listOf("hola", "HOLA", "Hola"), marcado("hola HOLA Hola", "hola"))
        assertEquals(listOf(0..3, 5..8, 10..13), tramosQueCasan("hola HOLA Hola", "hola"))
    }

    @Test
    fun `todas las apariciones tambien con acentos de por medio`() {
        assertEquals(listOf("Mañana", "MANANA"), marcado("Mañana y otra vez MANANA", "manana"))
    }

    /**
     * Las coincidencias no se solapan: tras cada una el cursor salta al final del tramo.
     * Dos subrayados encima del mismo carácter son un subrayado, y en cambio obligarían a
     * ordenar y fundir tramos antes de pintarlos.
     */
    @Test
    fun `las apariciones solapadas no se cuentan dos veces`() {
        assertEquals(listOf(0..1), tramosQueCasan("aaa", "aa"))
        assertEquals(listOf(0..1, 2..3), tramosQueCasan("aaaa", "aa"))
    }

    // ---- Lo que no se marca ----------------------------------------------

    /** Sin consulta no se marca nada: lo contrario dejaría la pantalla entera subrayada. */
    @Test
    fun `sin consulta no se marca nada`() {
        assertTrue(tramosQueCasan("hola mundo", "").isEmpty())
    }

    /** Espacios sueltos es lo mismo que nada, igual que en [buscar], que hace `trim`. */
    @Test
    fun `una consulta de solo espacios no marca nada`() {
        assertTrue(tramosQueCasan("hola mundo", "   ").isEmpty())
        assertTrue(tramosQueCasan("hola mundo", "\t\n ").isEmpty())
    }

    @Test
    fun `lo que no aparece no se marca`() {
        assertTrue(tramosQueCasan("hola mundo", "adios").isEmpty())
    }

    @Test
    fun `una consulta mas larga que el texto no marca nada`() {
        assertTrue(tramosQueCasan("ab", "abcd").isEmpty())
        assertTrue(tramosQueCasan("hola", "hola mundo").isEmpty())
    }

    @Test
    fun `un texto vacio no marca nada`() {
        assertTrue(tramosQueCasan("", "hola").isEmpty())
        assertTrue(tramosQueCasan("", "").isEmpty())
    }

    // ---- De acuerdo con la búsqueda --------------------------------------

    /**
     * Lo que el filtro deja pasar tiene que poder marcarse.
     *
     * Si [buscar] enseña una nota y aquí no sale ningún tramo, el usuario ve un resultado
     * sin subrayar y concluye que la búsqueda se ha equivocado — aunque no lo haya hecho.
     */
    @Test
    fun `todo lo que encuentra la busqueda se puede marcar`() {
        val lista = listOf(
            Mensaje(id = "1", cuando = 1, clase = Clase.NOTA, texto = "acordarse del taller"),
            Mensaje(id = "2", cuando = 2, clase = Clase.ARCHIVO, nombre = "Práctica 3.pdf"),
            Mensaje(id = "3", cuando = 3, clase = Clase.NOTA, texto = "MAÑANA sin falta")
        )
        for (consulta in listOf("taller", "practica", "manana", "PRÁCTICA", "  taller  ")) {
            for (m in buscar(lista, consulta)) {
                val hay = tramosQueCasan(m.texto, consulta).isNotEmpty() ||
                    tramosQueCasan(m.nombre, consulta).isNotEmpty()
                assertTrue("«$consulta» filtró ${m.id} pero no hay dónde marcarlo", hay)
            }
        }
    }

    /** La consulta se recorta igual que en [buscar], o una y otro dirían cosas distintas. */
    @Test
    fun `los espacios de los lados de la consulta no cuentan`() {
        assertEquals(listOf("men"), marcado("resumen", "  men "))
    }

    // ---- El recorte de textos largos -------------------------------------

    /**
     * Con la coincidencia en el carácter 900, la fila enseñaría el principio del texto —sin
     * nada marcado— y el usuario diría que la búsqueda falla.
     */
    @Test
    fun `el recorte se lleva la coincidencia consigo`() {
        val texto = "x".repeat(900) + "taller" + "y".repeat(900)
        val tramo = tramosQueCasan(texto, "taller").single()
        val (trozo, recolocado) = recortadoAlrededor(texto, tramo, margen = 20)

        assertEquals("taller", trozo.substring(recolocado.first, recolocado.last + 1))
        assertEquals(1 + 20 + 6 + 20 + 1, trozo.length) // los dos «…» cuentan uno cada uno
        assertTrue("falta el corte por delante", trozo.startsWith("…"))
        assertTrue("falta el corte por detrás", trozo.endsWith("…"))
    }

    /** Si no se ha cortado nada, no hay puntos ni hay nada que recolocar. */
    @Test
    fun `un texto que cabe entero se queda como estaba`() {
        val texto = "acordarse del taller"
        val tramo = tramosQueCasan(texto, "taller").single()
        val (trozo, recolocado) = recortadoAlrededor(texto, tramo, margen = 50)
        assertEquals(texto, trozo)
        assertEquals(tramo, recolocado)
    }

    /** Cortado por un solo lado salen unos puntos, no dos. */
    @Test
    fun `solo se ponen puntos del lado por el que se ha cortado`() {
        val texto = "0123456789abcdefghij"
        val tramo = tramosQueCasan(texto, "ghij").single()
        val (trozo, recolocado) = recortadoAlrededor(texto, tramo, margen = 3)
        assertTrue(trozo.startsWith("…"))
        assertTrue("no se ha cortado nada por detrás", !trozo.endsWith("…"))
        assertEquals("ghij", trozo.substring(recolocado.first, recolocado.last + 1))
    }

    /** El recorte tiene que valer para cualquier posición, no solo para las de las pruebas. */
    @Test
    fun `el tramo recolocado sigue senalando lo mismo en cualquier posicion`() {
        val texto = (0..99).joinToString("") { "abcdefghij" }
        for (inicio in 0 until texto.length - 4 step 7) {
            val tramo = inicio until inicio + 4
            val (trozo, recolocado) = recortadoAlrededor(texto, tramo, margen = 12)
            assertEquals(
                "se descoloca empezando en $inicio",
                texto.substring(tramo.first, tramo.last + 1),
                trozo.substring(recolocado.first, recolocado.last + 1)
            )
        }
    }
}
