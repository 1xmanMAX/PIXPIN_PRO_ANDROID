package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pegar una tabla de coordenadas desde una hoja de cálculo.
 *
 * Lo que llega del portapapeles no viene en un formato: viene en el que sea, y
 * casi siempre con una columna de nombres delante y una cabecera arriba. Aquí se
 * comprueba que **no haya que preparar nada antes de copiar**, que es la única
 * forma de que esto ahorre tiempo de verdad.
 */
class TablaPegadaTest {

    private fun x(p: List<PuntoDeTabla>) = p.map { it.x }
    private fun y(p: List<PuntoDeTabla>) = p.map { it.y }

    // ---- El caso corriente ----

    /** Excel y Sheets copian separando por tabulador. Es el 90 % de las veces. */
    @Test
    fun `una seleccion de Excel entra tal cual`() {
        val p = leerTablaPegada("100\t200\n150\t250\n-30\t12.5")
        assertEquals(3, p.size)
        assertEquals(listOf(100.0, 150.0, -30.0), x(p))
        assertEquals(listOf(200.0, 250.0, 12.5), y(p))
    }

    /**
     * La cabecera se cae sola.
     *
     * Nadie selecciona las celdas sin la fila de títulos: se marca la columna
     * entera y se copia. Pedir que la quiten antes sería pedirles el trabajo que
     * esto viene a ahorrar.
     */
    @Test
    fun `la cabecera se descarta sin decir nada`() {
        val p = leerTablaPegada("X\tY\n100\t200\n150\t250")
        assertEquals(2, p.size)
        assertEquals(listOf(100.0, 150.0), x(p))
    }

    /**
     * Y la columna del nombre del punto también.
     *
     * Se cogen **las dos primeras columnas que sean números**, así que da igual
     * que delante venga `P1`, `A` o `Est. 3`.
     */
    @Test
    fun `la columna de nombres no estorba`() {
        val p = leerTablaPegada("Punto\tX\tY\nP1\t100\t200\nP2\t150\t250")
        assertEquals(2, p.size)
        assertEquals(listOf(100.0, 150.0), x(p))
        assertEquals(listOf(200.0, 250.0), y(p))
    }

    // ---- Los decimales, que es donde se rompen estas cosas ----

    /** Un Excel en español escribe la coma decimal. */
    @Test
    fun `la coma decimal es un decimal`() {
        val p = leerTablaPegada("12,5\t7,25")
        assertEquals(listOf(12.5), x(p))
        assertEquals(listOf(7.25), y(p))
    }

    /**
     * Con los dos signos manda el último: el que va detrás es el decimal.
     *
     * `1.234,56` lo escribe un español y `1,234.56` un inglés, y del
     * portapapeles llegan los dos. Son el mismo número.
     */
    @Test
    fun `los miles no se confunden con los decimales`() {
        assertEquals(1234.56, leerNumeroDeHoja("1.234,56")!!, 1e-9)
        assertEquals(1234.56, leerNumeroDeHoja("1,234.56")!!, 1e-9)
        assertEquals(-9876.5, leerNumeroDeHoja("-9.876,5")!!, 1e-9)
    }

    /** Y las unidades pegadas al número no lo estropean. */
    @Test
    fun `las unidades pegadas se ignoran`() {
        assertEquals(12.5, leerNumeroDeHoja("12,5 m")!!, 1e-9)
        assertEquals(340.0, leerNumeroDeHoja("\"340\"")!!, 1e-9)
    }

    @Test
    fun `lo que no es un número no lo es`() {
        assertEquals(null, leerNumeroDeHoja("Punto"))
        assertEquals(null, leerNumeroDeHoja(""))
        assertEquals(null, leerNumeroDeHoja("--"))
    }

    // ---- Los otros formatos ----

    @Test
    fun `un CSV europeo se separa por punto y coma`() {
        val p = leerTablaPegada("X;Y\n100;200\n12,5;7,5")
        assertEquals(2, p.size)
        assertEquals(listOf(100.0, 12.5), x(p))
    }

    /** Una tabla copiada de un PDF llega con espacios de sobra. */
    @Test
    fun `varios espacios separan columnas`() {
        val p = leerTablaPegada("P1    100    200\nP2    150    250")
        assertEquals(2, p.size)
        assertEquals(listOf(100.0, 150.0), x(p))
    }

    @Test
    fun `una tabla con barras verticales tambien`() {
        val p = leerTablaPegada("| X | Y |\n| 100 | 200 |")
        assertEquals(1, p.size)
        assertEquals(listOf(100.0), x(p))
    }

    /**
     * **La coma es lo ambiguo, y gana el número.**
     *
     * `12,5` es un número en español y dos columnas en un CSV inglés, y desde el
     * portapapeles no hay forma de saberlo. Se lee como número, que es lo que
     * quiso escribir quien lo escribió — con solo una columna no se forma
     * ningún punto, y eso es mejor que inventarse el punto (12, 5).
     */
    @Test
    fun `una coma sola no parte el número`() {
        assertTrue(leerTablaPegada("12,5\n7,25").isEmpty())
    }

    /** Con tres trozos ya no hay duda: ahí la coma sí separa. */
    @Test
    fun `con tres columnas la coma sí separa`() {
        val p = leerTablaPegada("P1,100,200\nP2,150,250")
        assertEquals(2, p.size)
        assertEquals(listOf(100.0, 150.0), x(p))
    }

    /** Y un CSV inglés se reconoce por el punto decimal de sus trozos. */
    @Test
    fun `un CSV ingles de dos columnas se reconoce`() {
        val p = leerTablaPegada("100.5,200.25")
        assertEquals(1, p.size)
        assertEquals(listOf(100.5), x(p))
        assertEquals(listOf(200.25), y(p))
    }

    // ---- Lo que no vale ----

    @Test
    fun `pegar cualquier cosa no da puntos`() {
        assertTrue(leerTablaPegada(null).isEmpty())
        assertTrue(leerTablaPegada("").isEmpty())
        assertTrue(leerTablaPegada("hola qué tal").isEmpty())
        assertTrue(leerTablaPegada("https://ejemplo.com/algo").isEmpty())
    }

    /** Las líneas en blanco y los totales del final no cuentan como puntos. */
    @Test
    fun `las filas sueltas se caen y las buenas se quedan`() {
        val p = leerTablaPegada("X\tY\n\n100\t200\n\nTotal\t\n150\t250\n")
        assertEquals(2, p.size)
        assertEquals(listOf(100.0, 150.0), x(p))
    }

    /** Una tabla de verdad, con todo junto. */
    @Test
    fun `una libreta de campo entera`() {
        val p = leerTablaPegada(
            """
            Est.	X (m)	Y (m)	Cota
            E1	1.234,50	2.100,25	812,4
            E2	1.240,00	2.115,80	813,1
            E3	-12,75	-8,50	810,0
            """.trimIndent()
        )
        assertEquals(3, p.size)
        assertEquals(1234.50, p[0].x, 1e-9)
        assertEquals(2100.25, p[0].y, 1e-9)
        assertEquals(-12.75, p[2].x, 1e-9)
        assertEquals(-8.50, p[2].y, 1e-9)
    }
}
