package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La tabla pegada de una hoja de cálculo, dibujada.
 *
 * Medir letras es lo único que necesita Android, y llega por parámetro: aquí se
 * usa una regla falsa —cada letra ocupa medio tamaño de fuente— que permite
 * comprobar exactamente lo que importa, que es **de dónde sale el ancho de cada
 * columna**. Con la medida de verdad los números dependerían de la fuente
 * instalada y la prueba no diría nada.
 */
class TablaDibujadaTest {

    private val medir: MedidaDeTexto = { texto, tamano ->
        texto.length * tamano * 0.5 to tamano * 1.25
    }

    private val estilo = ItemStyle(fontSize = 20.0)

    private fun tabla(filas: List<List<String>>, conCabecera: Boolean = true) =
        elementosDeTabla(filas, estilo, Pt(0.0, 0.0), medir, conCabecera)

    private fun textos(e: List<Element>) =
        e.filter { it.type == ElementType.TEXT }.mapNotNull { it.text }

    @Test
    fun `una tabla trae su marco, sus separadores y su contenido`() {
        val e = tabla(listOf(listOf("Mes", "Alta"), listOf("Ene", "120"), listOf("Feb", "98")))

        // El fondo de la cabecera y el marco de fuera.
        assertEquals(2, e.count { it.type == ElementType.RECTANGLE })
        // Una raya por separación: una vertical y dos horizontales.
        assertEquals(3, e.count { it.type == ElementType.LINE })
        assertEquals(listOf("Mes", "Alta", "Ene", "120", "Feb", "98"), textos(e))
    }

    /** Sin cabecera no hay fondo: queda el marco y ya. */
    @Test
    fun `sin cabecera no se pinta el fondo`() {
        val e = tabla(listOf(listOf("a", "b"), listOf("c", "d")), conCabecera = false)
        assertEquals(1, e.count { it.type == ElementType.RECTANGLE })
    }

    /**
     * **El ancho de una columna sale de su texto más largo.**
     *
     * Es lo que separa esto de una rejilla a partes iguales: con una columna de
     * nombres y otra de cifras, repartir por igual desperdicia la mitad del
     * ancho y aun así parte los nombres.
     */
    @Test
    fun `cada columna se ancha segun su texto mas largo`() {
        val e = tabla(listOf(listOf("Concepto", "N"), listOf("x", "1")))
        val marco = e.first { it.type == ElementType.RECTANGLE && it.width > 0 }
        // «Concepto» son ocho letras a diez píxeles, más el aire de los dos
        // lados; la segunda columna no llega al mínimo y se queda en él.
        val primera = 8 * 10.0 + AIRE_DE_CELDA * 2
        assertEquals(primera + ANCHO_MINIMO_DE_COLUMNA, marco.width, 1e-6)
    }

    /** Una celda vacía no deja un texto invisible que luego robe toques. */
    @Test
    fun `las celdas vacias no dejan elemento`() {
        val e = tabla(listOf(listOf("a", ""), listOf("", "d")))
        assertEquals(listOf("a", "d"), textos(e))
    }

    /**
     * Una fila más corta que las demás no descuadra la tabla.
     *
     * Pasa siempre: al copiar de Excel, una fila que acaba en celdas vacías
     * llega recortada. Sin cuadrar la rejilla, a partir de ahí cada fila pintaba
     * sus columnas en otro sitio.
     */
    @Test
    fun `una fila corta se rellena hasta cuadrar`() {
        val e = tabla(listOf(listOf("a", "b", "c"), listOf("d")))
        // Dos separadores verticales: la rejilla es de tres columnas para todos.
        assertEquals(2, e.count { it.type == ElementType.LINE && it.height > it.width })
    }

    /** Y la línea en blanco del final del pegado no deja una fila de nada. */
    @Test
    fun `la fila vacia del final se cae`() {
        assertEquals(
            listOf(listOf("a", "b")),
            rejillaRegular(listOf(listOf("a", "b"), listOf("", "")))
        )
    }

    /**
     * **Se dibuja recta.** Una rejilla temblorosa no se lee como una tabla: son
     * muchas paralelas muy juntas, que es donde más canta el ruido.
     */
    @Test
    fun `la rejilla no tiembla`() {
        val e = tabla(listOf(listOf("a", "b"), listOf("c", "d")))
        val rejilla = e.filter { it.type == ElementType.LINE || it.type == ElementType.RECTANGLE }
        assertTrue(rejilla.isNotEmpty())
        assertTrue(rejilla.all { it.roughness == Element.ROUGHNESS_ARCHITECT })
        assertTrue(rejilla.all { it.roundness == null })
    }

    /** Y sale de una pieza: mover una tabla no puede ser veinte arrastres. */
    @Test
    fun `la tabla sale agrupada`() {
        val e = tabla(listOf(listOf("a", "b"), listOf("c", "d")))
        val grupos = e.map { it.groupIds.lastOrNull() }.distinct()
        assertEquals(1, grupos.size)
        assertNotNull(grupos.first())
    }

    @Test
    fun `sin nada que dibujar no se dibuja nada`() {
        assertTrue(tabla(emptyList()).isEmpty())
        assertTrue(tabla(listOf(listOf("", ""))).isEmpty())
    }
}

/**
 * La lista de figuras: lo que no hay que volver a dibujar.
 *
 * Lo que se defiende aquí es que una figura **se pueda estampar dos veces sin
 * que la segunda pise a la primera**, y que lo guardado no se acuerde de dónde
 * estaba dibujado — que son los dos fallos que convierten una biblioteca en un
 * sitio del que no te puedes fiar.
 */
class BibliotecaTest {

    private val medir: MedidaDeTexto = { texto, tamano ->
        texto.length * tamano * 0.5 to tamano * 1.25
    }

    private fun caja(id: String, x: Double, y: Double) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y,
        width = 40.0, height = 20.0, seed = 7
    )

    @Test
    fun `las de fabrica salen con la esquina en el origen`() {
        val figuras = figurasDeFabrica(ItemStyle(), medir)
        assertTrue("no hay figuras de fábrica", figuras.isNotEmpty())
        figuras.forEach {
            assertEquals("${it.nombre} no está normalizada", 0.0, it.caja.x1, 1e-6)
            assertEquals("${it.nombre} no está normalizada", 0.0, it.caja.y1, 1e-6)
            assertTrue("${it.nombre} no ocupa nada", it.caja.width > 0 && it.caja.height > 0)
        }
    }

    /**
     * Los tres instrumentos de graficar vienen de fábrica, cada uno como un
     * solo elemento con su unidad puesta; los ejes, la cuadrícula y la recta
     * dibujados con flechas sueltas ya no están (los quitó el usuario porque al
     * alargarlos se deformaban).
     */
    @Test
    fun `de fabrica vienen el plano, la recta y el espacio como instrumentos`() {
        val figuras = figurasDeFabrica(ItemStyle(), medir)
        val porId = figuras.associateBy { it.id }
        for ((id, tipo) in listOf(ID_PLANO to ElementType.PLANO, ID_RECTA to ElementType.RECTA, ID_ESPACIO to ElementType.ESPACIO)) {
            val f = porId[id] ?: error("falta $id")
            assertEquals(1, f.elementos.size)
            assertEquals(tipo, f.elementos[0].type)
            assertEquals(UNIDAD_POR_DEFECTO, f.elementos[0].unidad)
        }
        assertTrue(figuras.none { it.id == "fabrica-ejes" || it.id == "fabrica-cuadricula" })
        assertTrue(figuras.none { e -> e.elementos.any { it.type == ElementType.ARROW } })
    }

    @Test
    fun `estampar deja la figura centrada donde se pide`() {
        val figura = FiguraGuardada("f", "prueba", listOf(caja("a", 0.0, 0.0), caja("b", 60.0, 0.0)))
        val puesta = estampar(figura, Pt(500.0, 300.0))
        val caja = getCommonBounds(puesta)
        assertEquals(500.0, caja.midX, 1e-6)
        assertEquals(300.0, caja.midY, 1e-6)
    }

    /**
     * **Dos veces la misma figura son dos figuras.**
     *
     * Copiando los ids tal cual, la segunda estampada tendría los mismos que la
     * primera y todo lo que busca por id —mover, borrar, deshacer— se llevaría
     * las dos por delante.
     */
    @Test
    fun `estampar dos veces no repite ids`() {
        val figura = FiguraGuardada("f", "prueba", listOf(caja("a", 0.0, 0.0)))
        val unos = estampar(figura, Pt(0.0, 0.0)).map { it.id }
        val otros = estampar(figura, Pt(100.0, 100.0)).map { it.id }
        assertTrue(unos.intersect(otros.toSet()).isEmpty())
        // Y la figura guardada no se toca: sigue sirviendo para la siguiente.
        assertEquals("a", figura.elementos.first().id)
    }

    /** Y sale de una pieza, para poder moverla nada más ponerla. */
    @Test
    fun `lo estampado viene agrupado`() {
        val figura = FiguraGuardada("f", "p", listOf(caja("a", 0.0, 0.0), caja("b", 60.0, 0.0)))
        val puesta = estampar(figura, Pt(0.0, 0.0))
        assertEquals(1, puesta.map { it.groupIds.lastOrNull() }.distinct().size)
        assertNotNull(puesta.first().groupIds.lastOrNull())
    }

    /**
     * Guardar normaliza: la figura no se acuerda de en qué parte del lienzo
     * estaba dibujada, o estamparla obligaría a restar ese sitio cada vez.
     */
    @Test
    fun `guardar la seleccion la lleva al origen`() {
        val figura = figuraDeLaSeleccion("mía", listOf(caja("a", 900.0, 700.0)))
        assertNotNull(figura)
        assertEquals(0.0, figura!!.caja.x1, 1e-6)
        assertEquals(0.0, figura.caja.y1, 1e-6)
        assertTrue(figura.propia)
        assertEquals("mía", figura.nombre)
    }

    @Test
    fun `sin nombre se le pone uno, y sin nada no se guarda`() {
        assertEquals(
            NOMBRE_POR_DEFECTO,
            figuraDeLaSeleccion("   ", listOf(caja("a", 0.0, 0.0)))?.nombre
        )
        assertNull(figuraDeLaSeleccion("x", emptyList()))
        assertNull(figuraDeLaSeleccion("x", listOf(caja("a", 0.0, 0.0).copy(isDeleted = true))))
    }

    /** Las de fábrica no son propias: no se pueden borrar de la lista. */
    @Test
    fun `las de fabrica no se pueden quitar`() {
        assertTrue(figurasDeFabrica(ItemStyle(), medir).none { it.propia })
    }

    /** Y una figura vacía no se estampa: no dejaría nada y ensuciaría el historial. */
    @Test
    fun `una figura sin elementos no estampa nada`() {
        assertTrue(estampar(FiguraGuardada("f", "vacía"), Pt(0.0, 0.0)).isEmpty())
    }
}

/**
 * Meter algo hecho en el dibujo: una figura de la lista o una tabla pegada.
 *
 * Entra por [DrawController.insertar], que es la única puerta, y lo que se
 * comprueba aquí es lo que se espera **después** de meterlo: que quede cogido,
 * que la herramienta sea la flecha y que un solo deshacer lo quite entero.
 */
class InsertarEnElDibujoTest {

    private fun caja(id: String) = Element(
        id = id, type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
        width = 10.0, height = 10.0, seed = 1
    )

    @Test
    fun `lo insertado queda marcado y con la flecha puesta`() {
        val c = DrawController()
        c.selectTool(Tool.RECTANGLE)
        c.insertar(listOf(caja("a"), caja("b")))

        assertEquals(setOf("a", "b"), c.selectedIds)
        assertEquals(Tool.SELECTION, c.tool)
    }

    @Test
    fun `un solo deshacer se lleva la figura entera`() {
        val c = DrawController()
        c.insertar(listOf(caja("a"), caja("b"), caja("c")))
        assertEquals(3, c.scene.elements.size)

        c.undo()
        assertTrue(c.scene.elements.isEmpty())
        assertFalse("ha quedado un deshacer de sobra", c.canUndo)
    }

    @Test
    fun `insertar nada no toca el dibujo`() {
        val c = DrawController()
        c.insertar(emptyList())
        assertTrue(c.scene.elements.isEmpty())
        assertFalse(c.canUndo)
    }
}

/**
 * Estirar lo que hay marcado, que casi siempre es una figura estampada.
 *
 * Lo que se defiende: **por la esquina en proporción y por el lado aplastando**.
 * Antes se forzaba la proporción siempre y solo se aceptaban las esquinas, así
 * que una figura de la lista no había forma de estrecharla para meterla en un
 * hueco — que es la mitad de para lo que se guarda una figura.
 */
class EstirarVariosTest {

    private fun caja(id: String, x: Double, y: Double) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y,
        width = 200.0, height = 200.0, seed = 1
    )

    /**
     * Dos cajas de 200 en fila: la caja común va de 0 a 400 por 200 de alto.
     *
     * Las dos medidas están por encima del mínimo que pide [getTransformHandles]
     * para sacar los ocho tiradores —en una figura pequeña los del lado se
     * amontonarían con los de esquina y no se acertaría a ninguno—, que es lo
     * que hace falta para poder aplastar un eje.
     */
    private fun figura() = listOf(caja("a", 0.0, 0.0), caja("b", 200.0, 0.0))

    @Test
    fun `por el lado derecho se aplasta solo el ancho`() {
        val despues = resizeMultipleElements(figura(), HandleType.E, Pt(150.0, 0.0))
        val caja = getCommonBounds(despues)
        assertEquals("el ancho tenía que encogerse", 150.0, caja.width, 1e-6)
        assertEquals("el alto no se toca", 200.0, caja.height, 1e-6)
        assertEquals("y el borde de la izquierda se queda clavado", 0.0, caja.x1, 1e-6)
    }

    @Test
    fun `por el lado de abajo se aplasta solo el alto`() {
        val despues = resizeMultipleElements(figura(), HandleType.S, Pt(0.0, 40.0))
        val caja = getCommonBounds(despues)
        assertEquals(400.0, caja.width, 1e-6)
        assertEquals(40.0, caja.height, 1e-6)
    }

    /** Por la esquina, en cambio, la forma se conserva. */
    @Test
    fun `por la esquina se mantiene la proporcion`() {
        val antes = getCommonBounds(figura())
        val despues = resizeMultipleElements(figura(), HandleType.SE, Pt(600.0, 400.0))
        val caja = getCommonBounds(despues)
        assertEquals(
            "la proporción ha cambiado",
            antes.width / antes.height, caja.width / caja.height, 1e-6
        )
    }

    /** Y con la proporción forzada —el segundo dedo— el lado tampoco aplasta. */
    @Test
    fun `con la proporcion forzada el lado no aplasta`() {
        val antes = getCommonBounds(figura())
        val despues = resizeMultipleElements(
            figura(), HandleType.E, Pt(150.0, 0.0), keepAspectRatio = true
        )
        val caja = getCommonBounds(despues)
        assertEquals(
            antes.width / antes.height, caja.width / caja.height, 1e-6
        )
    }

    /** Y los ocho tiradores están ahí para poder hacerlo. */
    @Test
    fun `una seleccion de varios enseña los ocho tiradores`() {
        val tipos = getSelectionTransformHandles(figura(), 1.0).map { it.type }
        assertTrue("faltan los del lado: $tipos", HandleType.E in tipos)
        assertTrue(HandleType.W in tipos)
        assertTrue(HandleType.N in tipos)
        assertTrue(HandleType.S in tipos)
        assertTrue(HandleType.SE in tipos)
    }

    /**
     * Pero en una figura pequeña **no**, y es a propósito.
     *
     * Los del lado caerían encima de los de esquina y no se acertaría a ninguno.
     * Se recuperan acercándose, porque el mínimo se mide en píxeles de pantalla:
     * con el zoom al doble, la misma figura ya da de sí.
     */
    @Test
    fun `en una figura pequeña los del lado no salen`() {
        val pequena = listOf(caja("a", 0.0, 0.0).copy(width = 30.0, height = 30.0),
            caja("b", 40.0, 0.0).copy(width = 30.0, height = 30.0))
        val tipos = getSelectionTransformHandles(pequena, 1.0).map { it.type }
        assertTrue("no deberían salir: $tipos", HandleType.E !in tipos)
        assertTrue("las esquinas sí", HandleType.SE in tipos)
    }
}
