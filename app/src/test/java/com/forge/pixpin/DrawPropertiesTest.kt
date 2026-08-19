package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué ajustes se ofrecen en cada momento.
 *
 * Es la tabla que decide si el panel de estilos enseña algo útil o una parrilla
 * de botones que no hacen nada. Se comprueba entera porque el fallo típico es
 * silencioso: una herramienta nueva que no aparece en el `when` se queda sin
 * ajustes y nadie se entera hasta que alguien la usa.
 */
class DrawPropertiesTest {

    private fun elemento(tipo: ElementType, grupos: List<String> = emptyList()) = Element(
        id = "e$tipo", type = tipo, x = 0.0, y = 0.0, width = 10.0, height = 10.0,
        seed = 1, groupIds = grupos
    )

    // ---- Sin nada seleccionado manda la herramienta ----

    @Test
    fun `las herramientas que no dibujan no ofrecen ajustes`() {
        for (t in listOf(Tool.SELECTION, Tool.LASSO, Tool.HAND, Tool.ERASER)) {
            assertTrue(
                "$t no crea nada: el panel tiene que desaparecer",
                propiedadesPara(t, emptyList()).isEmpty()
            )
        }
    }

    @Test
    fun `con el rectangulo activo se ofrece lo del rectangulo`() {
        val p = propiedadesPara(Tool.RECTANGLE, emptyList())
        assertTrue(Propiedad.ESQUINAS in p)
        assertTrue(Propiedad.RELLENO in p)
        assertTrue(Propiedad.FUENTE !in p)
        assertTrue(Propiedad.PUNTAS !in p)
    }

    /**
     * Fondo y relleno **sí**, y no por completismo: un garabato cerrado se
     * rellena solo, así que sin estos dos controles no hay forma de quitarle la
     * trama. Quitarlos dejó la trama puesta y sin interruptor.
     *
     * Y la imperfección también. El lápiz no pasa por rough.js, pero ahí los
     * tres niveles gradúan cuánto se corrige el pulso —ver
     * `FreedrawTuning.streamlineDe`—, que se nota igual y es la misma idea.
     */
    @Test
    fun `el lapiz ofrece color fondo relleno grosor imperfeccion y opacidad`() {
        assertEquals(
            setOf(
                Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO,
                Propiedad.GROSOR, Propiedad.RUGOSIDAD, Propiedad.OPACIDAD, Propiedad.PRESION
            ),
            propiedadesPara(Tool.FREEDRAW, emptyList())
        )
        assertEquals(
            propiedadesPara(Tool.FREEDRAW, emptyList()),
            propiedadesPara(Tool.HIGHLIGHTER, emptyList())
        )
    }

    @Test
    fun `solo la flecha ofrece puntas`() {
        assertTrue(Propiedad.PUNTAS in propiedadesPara(Tool.ARROW, emptyList()))
        for (t in Tool.entries.filter { it != Tool.ARROW }) {
            assertTrue("$t no debería ofrecer puntas", Propiedad.PUNTAS !in propiedadesPara(t, emptyList()))
        }
    }

    @Test
    fun `la elipse no ofrece esquinas`() {
        assertTrue(Propiedad.ESQUINAS !in propiedadesPara(Tool.ELLIPSE, emptyList()))
    }

    @Test
    fun `el texto ofrece fuente y no relleno`() {
        val p = propiedadesPara(Tool.TEXT, emptyList())
        assertTrue(Propiedad.FUENTE in p)
        assertTrue(Propiedad.RELLENO !in p)
    }

    /** La lupa ofrece lo suyo; el mosaico, el grano. */
    @Test
    fun `las herramientas propias ofrecen lo justo`() {
        // La lupa: cuánto agranda y cómo es su montura. Nada de relleno —dentro
        // no hay color propio, hay dibujo— ni de tipo de línea.
        assertEquals(
            setOf(Propiedad.LUPA, Propiedad.TRAZO, Propiedad.GROSOR, Propiedad.OPACIDAD),
            propiedadesPara(Tool.LUPA, emptyList())
        )
        // El mosaico añade elegir entre bloques y mancha: el campo existía en el
        // modelo desde el principio y no había forma de tocarlo.
        assertEquals(
            setOf(Propiedad.GROSOR, Propiedad.MOSAICO, Propiedad.OPACIDAD),
            propiedadesPara(Tool.MOSAIC, emptyList())
        )
        assertTrue(Propiedad.FUENTE in propiedadesPara(Tool.SERIAL, emptyList()))
    }

    /**
     * Toda herramienta que dibuja tiene que ofrecer **algo**.
     *
     * Con una excepción: la hoja no es un dibujo sino un límite, así que no
     * tiene color, ni grosor, ni relleno. Lo único que se le hace es estirarla.
     */
    @Test
    fun `ninguna herramienta que dibuja se queda sin ajustes`() {
        for (t in Tool.entries) {
            if (tipoQueCrea(t) == null || t == Tool.FRAME) continue
            assertTrue("$t dibuja pero no ofrece nada", propiedadesPara(t, emptyList()).isNotEmpty())
        }
    }

    @Test
    fun `la hoja no ofrece estilo porque no es un dibujo`() {
        assertTrue(propiedadesPara(Tool.FRAME, emptyList()).isEmpty())
    }

    // ---- Con selección manda la selección ----

    @Test
    fun `la seleccion manda sobre la herramienta activa`() {
        // Herramienta de rectángulo, pero lo seleccionado es un texto.
        val p = propiedadesPara(Tool.RECTANGLE, listOf(elemento(ElementType.TEXT)))
        assertTrue(Propiedad.FUENTE in p)
        assertTrue(Propiedad.RELLENO !in p)
    }

    @Test
    fun `con varios elementos se ofrece la union`() {
        val p = propiedadesPara(
            Tool.SELECTION,
            listOf(elemento(ElementType.TEXT), elemento(ElementType.RECTANGLE))
        )
        assertTrue(Propiedad.FUENTE in p)
        assertTrue(Propiedad.ESQUINAS in p)
    }

    // ---- Las acciones ----

    @Test
    fun `sin seleccion no hay acciones`() {
        assertTrue(gruposPara(emptyList()).isEmpty())
    }

    @Test
    fun `con uno solo no se puede agrupar ni alinear`() {
        val g = gruposPara(listOf(elemento(ElementType.RECTANGLE)))
        assertTrue(GrupoAcciones.ORDEN in g)
        assertTrue(GrupoAcciones.VOLTEO in g)
        assertTrue(GrupoAcciones.AGRUPAR !in g)
        assertTrue(GrupoAcciones.ALINEAR !in g)
    }

    @Test
    fun `con dos o mas si`() {
        val g = gruposPara(List(2) { elemento(ElementType.RECTANGLE) })
        assertTrue(GrupoAcciones.AGRUPAR in g)
        assertTrue(GrupoAcciones.ALINEAR in g)
    }

    /** Desagrupar tiene que estar aunque hayas tocado un solo miembro. */
    @Test
    fun `un elemento ya agrupado ofrece desagrupar`() {
        val g = gruposPara(listOf(elemento(ElementType.RECTANGLE, grupos = listOf("g1"))))
        assertTrue(GrupoAcciones.AGRUPAR in g)
        assertTrue(GrupoAcciones.ALINEAR !in g)
    }
}

/**
 * Tocar un mando toca **ese** mando.
 *
 * Es el fallo de «subo la opacidad y se me cambia el color»: un panel de estilos
 * no dice «ponle este estilo», dice «súbele la opacidad», y lo que llega es el
 * estilo entero con un campo distinto. Volcándolo tal cual, los otros quince
 * campos —el color del pincel, su grosor— se escribían encima de lo que hubiera
 * marcado. Y con el panel enseñando siempre el pincel en vez de lo marcado, esos
 * quince campos casi nunca eran los de la figura.
 */
class CambioDeEstiloTest {

    private fun roja() = Element(
        id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0,
        width = 100.0, height = 50.0, seed = 1,
        strokeColor = "#e03131", strokeWidth = 1.0, opacity = 100
    )

    private fun conLaRojaMarcada(): DrawController {
        val c = DrawController()
        c.load(Scene(elements = listOf(roja()), style = ItemStyle()))
        c.setSelection(setOf("r"))
        return c
    }

    /** Los mandos enseñan lo de lo marcado, no lo del pincel. */
    @Test
    fun `el estilo activo es el de lo marcado`() {
        val c = conLaRojaMarcada()
        assertEquals("#e03131", c.estiloActivo().strokeColor)
        assertEquals(1.0, c.estiloActivo().strokeWidth, 1e-9)
        // Y sin nada marcado, el del pincel.
        c.deselect()
        assertEquals(ItemStyle().strokeColor, c.estiloActivo().strokeColor)
    }

    @Test
    fun `subir la opacidad no toca el color ni el grosor`() {
        val c = conLaRojaMarcada()
        c.cambiarEstilo(c.estiloActivo().copy(opacity = 40))

        val r = c.scene.byId("r")!!
        assertEquals("el color no puede cambiar", "#e03131", r.strokeColor)
        assertEquals("el grosor tampoco", 1.0, r.strokeWidth, 1e-9)
        assertEquals("y la opacidad sí", 40, r.opacity)
    }

    /**
     * Y la opacidad **llega de verdad al elemento**. Antes solo se copiaban el
     * color y el grosor, así que el mando se movía, el número subía y en el
     * dibujo no pasaba nada: parecía que la opacidad no llegaba nunca.
     */
    @Test
    fun `la opacidad llega hasta el tope`() {
        val c = conLaRojaMarcada()
        c.cambiarEstilo(c.estiloActivo().copy(opacity = 10))
        assertEquals(10, c.scene.byId("r")!!.opacity)
        c.cambiarEstilo(c.estiloActivo().copy(opacity = 100))
        assertEquals(100, c.scene.byId("r")!!.opacity)
    }

    /** Y el mando de opacidad da los dos extremos de su recorrido. */
    @Test
    fun `el deslizador de opacidad da el minimo y el cien`() {
        assertEquals(100, valorConPaso(1f, 10, 100, 5))
        assertEquals(10, valorConPaso(0f, 10, 100, 5))
    }

    /**
     * El pincel se lleva **solo lo que se tocó**. Si se llevara el estilo
     * entero, marcar una figura roja y subirle la opacidad dejaría el pincel
     * cargado de rojo sin que nadie lo haya pedido.
     */
    @Test
    fun `el pincel solo se lleva el campo que se ha tocado`() {
        val c = conLaRojaMarcada()
        val pincelAntes = c.scene.style.strokeColor
        c.cambiarEstilo(c.estiloActivo().copy(opacity = 40))

        assertEquals(pincelAntes, c.scene.style.strokeColor)
        assertEquals(40, c.scene.style.opacity)
    }

    /** Cambiar el color sí cambia el color, no vaya a ser. */
    @Test
    fun `cambiar el color cambia el color y nada mas`() {
        val c = conLaRojaMarcada()
        c.cambiarEstilo(c.estiloActivo().copy(strokeColor = "#1971c2"))

        val r = c.scene.byId("r")!!
        assertEquals("#1971c2", r.strokeColor)
        assertEquals(1.0, r.strokeWidth, 1e-9)
        assertEquals(100, r.opacity)
    }

    /** A cada tipo solo le entra lo que su tabla admite. Ver [conEstilo]. */
    @Test
    fun `a un texto no se le escribe lo que no tiene`() {
        val texto = Element(
            id = "t", type = ElementType.TEXT, x = 0.0, y = 0.0,
            width = 30.0, height = 20.0, seed = 1, text = "hola",
            backgroundColor = Element.TRANSPARENT, strokeStyle = StrokeStyle.SOLID
        )
        val puesto = conEstilo(
            texto,
            ItemStyle(
                strokeColor = "#e03131", backgroundColor = "#ffc9c9",
                strokeStyle = StrokeStyle.DASHED, fontSize = 28.0
            )
        )
        assertEquals("#e03131", puesto.strokeColor)
        assertEquals(28.0, puesto.fontSize!!, 1e-9)
        assertEquals("un texto no tiene fondo", Element.TRANSPARENT, puesto.backgroundColor)
        assertEquals("ni tipo de línea", StrokeStyle.SOLID, puesto.strokeStyle)
    }

    /** La hoja no tiene estilo ninguno: no se le escribe nada. */
    @Test
    fun `a la hoja no se le escribe nada`() {
        val marco = Element(
            id = "f", type = ElementType.FRAME, x = 0.0, y = 0.0,
            width = 100.0, height = 100.0, seed = 1
        )
        assertEquals(marco, conEstilo(marco, ItemStyle(strokeColor = "#e03131", opacity = 30)))
    }
}

/**
 * Alinear y repartir.
 *
 * El fallo que se defiende aquí es el de «a veces no hace nada»: los grupos se
 * mueven enteros para no deshacerlos, y con **un solo grupo marcado** eso dejaba
 * una única unidad que alinear consigo misma. Y es el caso corriente, no el
 * raro — tocar un miembro selecciona el grupo entero.
 */
class AlinearTest {

    private fun caja(id: String, x: Double, y: Double, grupo: String? = null) = Element(
        id = id, type = ElementType.RECTANGLE, x = x, y = y,
        width = 20.0, height = 10.0, seed = 1,
        groupIds = listOfNotNull(grupo)
    )

    @Test
    fun `alinear a la izquierda lleva todo al mismo borde`() {
        val antes = listOf(caja("a", 0.0, 0.0), caja("b", 80.0, 40.0), caja("c", 30.0, 90.0))
        val despues = alignElements(antes, setOf("a", "b", "c"), AlignAxis.X, AlignPosition.START)
        assertEquals(listOf(0.0, 0.0, 0.0), despues.map { it.x })
    }

    @Test
    fun `alinear arriba lleva todo al mismo alto`() {
        val antes = listOf(caja("a", 0.0, 10.0), caja("b", 80.0, 40.0), caja("c", 30.0, 90.0))
        val despues = alignElements(antes, setOf("a", "b", "c"), AlignAxis.Y, AlignPosition.START)
        assertEquals(listOf(10.0, 10.0, 10.0), despues.map { it.y })
    }

    /** **El fallo:** todo lo marcado era un solo grupo y no se movía nada. */
    @Test
    fun `alinear un grupo entero alinea lo de dentro`() {
        val antes = listOf(
            caja("a", 0.0, 0.0, grupo = "g"),
            caja("b", 80.0, 40.0, grupo = "g"),
            caja("c", 30.0, 90.0, grupo = "g")
        )
        val despues = alignElements(antes, setOf("a", "b", "c"), AlignAxis.X, AlignPosition.START)
        assertEquals("no se ha movido nada", listOf(0.0, 0.0, 0.0), despues.map { it.x })
    }

    /**
     * Pero con **dos grupos** siguen moviéndose enteros: alinear no puede
     * deshacer un grupo apilando sus miembros en una columna.
     */
    @Test
    fun `con dos grupos cada uno se mueve entero`() {
        val antes = listOf(
            caja("a", 0.0, 0.0, grupo = "g1"),
            caja("b", 40.0, 0.0, grupo = "g1"),
            caja("c", 200.0, 50.0, grupo = "g2")
        )
        val despues = alignElements(antes, setOf("a", "b", "c"), AlignAxis.X, AlignPosition.START)
        val porId = despues.associateBy { it.id }
        // El primer grupo ya estaba a la izquierda: no se mueve, y su forma se
        // conserva —la separación entre sus dos cajas sigue siendo la de antes—.
        assertEquals(0.0, porId["a"]!!.x, 1e-9)
        assertEquals(40.0, porId["b"]!!.x, 1e-9)
        assertEquals(0.0, porId["c"]!!.x, 1e-9)
    }

    /** Repartir necesita tres unidades: con dos no hay nada en medio que repartir. */
    @Test
    fun `repartir separa por igual`() {
        val antes = listOf(caja("a", 0.0, 0.0), caja("b", 10.0, 0.0), caja("c", 100.0, 0.0))
        val despues = distributeElements(antes, setOf("a", "b", "c"), AlignAxis.X)
        val xs = despues.map { it.x }.sorted()
        // Tres cajas de veinte entre 0 y 120: los huecos que queden, iguales.
        val hueco1 = xs[1] - (xs[0] + 20.0)
        val hueco2 = xs[2] - (xs[1] + 20.0)
        assertEquals(hueco1, hueco2, 1e-6)
    }
}
