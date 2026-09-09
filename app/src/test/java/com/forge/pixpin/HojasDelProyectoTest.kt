package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las páginas que enseña un proyecto, ya desplegadas de sus lienzos. */
class HojasDelProyectoTest {

    private fun marco(id: String, nombre: String? = null) = Element(
        id = id, type = ElementType.FRAME,
        x = 0.0, y = 0.0, width = 100.0, height = 100.0, seed = 1, text = nombre
    )

    private fun escenaCon(vararg marcos: Element) = Scene(elements = marcos.toList())

    @Test
    fun `una nota es una pagina`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "n1", nota = "algo")))
        val paginas = HojasDelProyecto.paginas(p) { null }
        assertEquals(1, paginas.size)
        assertNull(paginas[0].marco)
    }

    /** Una nota larga son varias hojas, igual que un PDF. */
    @Test
    fun `una nota larga se abre en sus paginas`() {
        val texto = (1..120).joinToString("\n\n") { "Párrafo número $it de la nota." }
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "n1", nota = texto)))
        val paginas = HojasDelProyecto.paginas(p) { null }

        assertTrue("salió una sola hoja", paginas.size > 1)
        assertEquals(listOf("1", "2"), paginas.take(2).map { it.nombre })
        // Cada una con su clave, o marcar una marcaría toda la nota.
        assertEquals(paginas.size, paginas.map { it.clave }.toSet().size)
        // Y pegadas dan la nota entera, sin perder ni repetir nada.
        assertEquals(texto, paginas.joinToString("") { it.texto.orEmpty() })
    }

    @Test
    fun `una nota corta sigue siendo una sola hoja`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "n1", nota = "algo")))
        val paginas = HojasDelProyecto.paginas(p) { null }
        assertEquals(1, paginas.size)
        assertEquals("algo", paginas[0].texto)
    }

    @Test
    fun `una pagina del pdf se numera`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", pagina = 4)))
        assertEquals("5", HojasDelProyecto.paginas(p) { null }[0].nombre)
    }

    /** Un lienzo con cuatro marcos son cuatro láminas, no una. */
    @Test
    fun `un lienzo se abre en sus marcos`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) {
            escenaCon(marco("m1"), marco("m2"), marco("m3"))
        }
        assertEquals(3, paginas.size)
        assertEquals(listOf("m1", "m2", "m3"), paginas.map { it.marco })
    }

    @Test
    fun `un lienzo sin marcos es una sola lamina`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) { escenaCon() }
        assertEquals(1, paginas.size)
        assertNull(paginas[0].marco)
    }

    /** Una hoja que guarda un marco concreto es ese marco, y solo él. Si el marco ya no
     * existe —se borró dentro del lienzo— la hoja no sale: desplegar el lienzo entero era
     * como duplicar la hoja principal. Ver `DrawEditorActivity.purgarHojasDeMarcosPerdidos`. */
    @Test
    fun `la hoja de un marco borrado no se abre como lienzo entero`() {
        val p = Proyecto(
            id = "p", nombre = "Obra",
            hojas = listOf(Hoja(id = "h1", dibujo = "d1", marco = "m1"))
        )
        // El marco m1 ya no está en la escena: la hoja no puede salir entera por su lado.
        val sinMarco = HojasDelProyecto.paginas(p) { escenaCon(marco("m2")) }
        assertEquals("el marco borrado no puede desplegar el lienzo entero", 0, sinMarco.size)
        // Con su marco vivo, es una sola página con el nombre del marco.
        val conMarco = HojasDelProyecto.paginas(p) { escenaCon(marco("m1", "Planta")) }
        assertEquals(1, conMarco.size)
        assertEquals("m1", conMarco[0].marco)
        assertEquals("Planta", conMarco[0].nombre)
    }

    @Test
    fun `un lienzo que no se puede leer sigue siendo una lamina`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        assertEquals(1, HojasDelProyecto.paginas(p) { null }.size)
    }

    @Test
    fun `el marco con nombre lo enseña`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) { escenaCon(marco("m1", "Planta baja")) }
        assertEquals("Planta baja", paginas[0].nombre)
    }

    /** Dos marcos del mismo lienzo son la misma hoja: sin el marco en la clave,
     * marcar uno marcaría los dos. */
    @Test
    fun `cada marco tiene su propia clave`() {
        val p = Proyecto(id = "p", nombre = "Obra", hojas = listOf(Hoja(id = "h1", dibujo = "d1")))
        val paginas = HojasDelProyecto.paginas(p) { escenaCon(marco("m1"), marco("m2")) }
        assertNotEquals(paginas[0].clave, paginas[1].clave)
    }

    @Test
    fun `se desplegan en el orden del proyecto`() {
        val p = Proyecto(
            id = "p",
            nombre = "Obra",
            hojas = listOf(
                Hoja(id = "n1", nota = "nota"),
                Hoja(id = "h1", dibujo = "d1"),
                Hoja(id = "h2", pagina = 0)
            )
        )
        val paginas = HojasDelProyecto.paginas(p) { escenaCon(marco("m1"), marco("m2")) }
        assertEquals(listOf("n1", "h1", "h1", "h2"), paginas.map { it.hoja.id })
    }

    // ---- El color ----

    /**
     * Lo que importa del color es que **no cambie**: el mismo lienzo, el mismo
     * color siempre y en todas partes. Que dos lienzos distintos coincidan es
     * posible —la paleta es finita— y no es un fallo; lo que sí lo sería es que
     * un proyecto con varios lienzos los pintara todos igual.
     */
    @Test
    fun `el color de un lienzo no cambia nunca`() {
        val uno = Hoja(id = "a", dibujo = "dib-uno")
        assertNotNull(HojasDelProyecto.colorDe(uno))
        repeat(5) {
            assertEquals(HojasDelProyecto.colorDe(uno), HojasDelProyecto.colorDe(uno))
        }
        // Y no depende de la hoja, solo del lienzo: la misma tela, el mismo color.
        val otraHoja = Hoja(id = "z", dibujo = "dib-uno")
        assertEquals(HojasDelProyecto.colorDe(uno), HojasDelProyecto.colorDe(otraHoja))
    }

    @Test
    fun `varios lienzos no salen todos del mismo color`() {
        val colores = (1..20)
            .map { HojasDelProyecto.colorDe(Hoja(id = "h$it", dibujo = "dib-$it")) }
            .toSet()
        assertTrue("todos iguales: $colores", colores.size >= 4)
    }

    @Test
    fun `las paginas del pdf y las notas no llevan color`() {
        assertNull(HojasDelProyecto.colorDe(Hoja(id = "h", pagina = 2, dibujo = "d")))
        assertNull(HojasDelProyecto.colorDe(Hoja(id = "n", nota = "x")))
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)

    // ---- Qué páginas hay delante de los ojos ----

    /**
     * La rejilla reparte por columnas, y quien prepara las miniaturas tiene que
     * pedir **eso mismo**. Pidiendo un tramo seguido de la lista se preparaba la
     * fila de arriba y páginas del final que no se veían, mientras las filas de
     * abajo —que sí estaban en pantalla— se quedaban sin tanda y tenían que
     * pedirse de una en una, abriendo el PDF por cada una.
     */
    @Test
    fun `las columnas visibles son las de la rejilla, no un tramo seguido`() {
        // 40 páginas, 4 filas, 10 columnas: la columna 0 lleva 0, 10, 20 y 30.
        val v = HojasDelProyecto.enColumnas(total = 40, filas = 4, porFila = 10, desde = 0, hasta = 2)
        assertEquals(listOf(0, 10, 20, 30, 1, 11, 21, 31), v)
    }

    /** Se piden en el orden en que se ven: la tanda entrega según dibuja. */
    @Test
    fun `salen en el orden en que se miran`() {
        val v = HojasDelProyecto.enColumnas(total = 9, filas = 3, porFila = 3, desde = 1, hasta = 3)
        assertEquals(listOf(1, 4, 7, 2, 5, 8), v)
    }

    /**
     * La última columna de un documento que no llena la rejilla tiene huecos, y
     * un hueco no es una página: pedirla sería pedir la página −1 de otra.
     */
    @Test
    fun `la ultima columna incompleta no inventa paginas`() {
        // 7 páginas en 3 filas y 3 columnas: la columna 2 solo tiene 2 y 5.
        val v = HojasDelProyecto.enColumnas(total = 7, filas = 3, porFila = 3, desde = 2, hasta = 3)
        assertEquals(listOf(2, 5), v)
    }

    /** Al principio de la lista no hay columnas por detrás que preparar. */
    @Test
    fun `el margen de atras no se sale por la izquierda`() {
        val v = HojasDelProyecto.enColumnas(total = 12, filas = 2, porFila = 6, desde = -2, hasta = 1)
        assertEquals(listOf(0, 6), v)
    }

    /** Y al final tampoco se pide más allá de la última columna. */
    @Test
    fun `no se piden columnas que no existen`() {
        val v = HojasDelProyecto.enColumnas(total = 12, filas = 2, porFila = 6, desde = 5, hasta = 20)
        assertEquals(listOf(5, 11), v)
    }

    /**
     * Lo que se prepara son **exactamente** las páginas de la rejilla, sin
     * repetir ninguna y sin dejarse ninguna: pidiendo todas las columnas tiene
     * que salir el documento entero.
     */
    @Test
    fun `todas las columnas son todas las paginas`() {
        for (total in 1..30) {
            for (filas in 1..4) {
                val porFila = (total + filas - 1) / filas
                val v = HojasDelProyecto.enColumnas(total, filas, porFila, 0, porFila)
                assertEquals("total=$total filas=$filas", (0 until total).toSet(), v.toSet())
                assertEquals("total=$total filas=$filas repetidas", total, v.size)
            }
        }
    }

    /** Un proyecto vacío no pide nada, y no revienta por el camino. */
    @Test
    fun `sin paginas no hay nada que preparar`() {
        assertEquals(emptyList<Int>(), HojasDelProyecto.enColumnas(0, 4, 10, 0, 8))
        assertEquals(emptyList<Int>(), HojasDelProyecto.enColumnas(10, 0, 10, 0, 8))
        assertEquals(emptyList<Int>(), HojasDelProyecto.enColumnas(10, 4, 0, 0, 8))
    }
}
