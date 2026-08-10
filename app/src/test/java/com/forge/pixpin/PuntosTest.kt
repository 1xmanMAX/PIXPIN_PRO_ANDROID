package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los puntos etiquetados: A, B, C sobre el dibujo.
 *
 * Es la herramienta de las matemáticas, y en matemáticas las dos cosas que no
 * pueden fallar son que **no se repita una letra** —una letra repetida invalida
 * un problema de geometría entero— y que **la letra se lea**, o sea que no caiga
 * encima de la figura que está nombrando.
 */
class PuntosTest {

    private fun linea(id: String, a: Pt, b: Pt) = Element(
        id = id, type = ElementType.LINE, x = a.x, y = a.y,
        width = b.x - a.x, height = b.y - a.y, seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    private fun punto(id: String, etiqueta: String) = Element(
        id = id, type = ElementType.PUNTO, x = 0.0, y = 0.0,
        width = 0.0, height = 0.0, seed = 1, text = etiqueta
    )

    // ---- Las series ----

    @Test
    fun `las mayúsculas van de la A a la Z`() {
        assertEquals("A", etiquetaNumero(0, SerieDePunto.MAYUSCULAS))
        assertEquals("B", etiquetaNumero(1, SerieDePunto.MAYUSCULAS))
        assertEquals("Z", etiquetaNumero(25, SerieDePunto.MAYUSCULAS))
    }

    /**
     * Pasada la Z no se para ni se inventa símbolos: A1, B1… y luego A2. Es lo
     * que se escribe a mano cuando hacen falta más de veintiséis, y se teclea
     * sin salir del teclado normal.
     */
    @Test
    fun `pasada la Z sigue con A1`() {
        assertEquals("A1", etiquetaNumero(26, SerieDePunto.MAYUSCULAS))
        assertEquals("B1", etiquetaNumero(27, SerieDePunto.MAYUSCULAS))
        assertEquals("A2", etiquetaNumero(52, SerieDePunto.MAYUSCULAS))
    }

    @Test
    fun `las minúsculas y los números tienen su propia serie`() {
        assertEquals("a", etiquetaNumero(0, SerieDePunto.MINUSCULAS))
        assertEquals("z", etiquetaNumero(25, SerieDePunto.MINUSCULAS))
        assertEquals("a1", etiquetaNumero(26, SerieDePunto.MINUSCULAS))
        assertEquals("1", etiquetaNumero(0, SerieDePunto.NUMEROS))
        assertEquals("100", etiquetaNumero(99, SerieDePunto.NUMEROS))
    }

    // ---- Que no se repita ninguna ----

    @Test
    fun `el primero de una serie es el primero`() {
        assertEquals("A", siguienteEtiqueta(emptyList(), SerieDePunto.MAYUSCULAS))
        assertEquals("a", siguienteEtiqueta(emptyList(), SerieDePunto.MINUSCULAS))
        assertEquals("1", siguienteEtiqueta(emptyList(), SerieDePunto.NUMEROS))
    }

    @Test
    fun `se salta las que ya están puestas`() {
        assertEquals("C", siguienteEtiqueta(listOf("A", "B"), SerieDePunto.MAYUSCULAS))
        assertEquals("B", siguienteEtiqueta(listOf("A", "C"), SerieDePunto.MAYUSCULAS))
    }

    /**
     * **El hueco de la letra borrada se reutiliza**, y es lo correcto.
     *
     * Va mirando el dibujo, no un contador: un contador se desincronizaría en
     * cuanto se borra un punto —el siguiente repetiría una letra que ya está— y
     * sobre todo no sabría nada de los puntos que llegan al abrir un archivo.
     */
    @Test
    fun `borrar la B hace que el siguiente sea B`() {
        assertEquals("B", siguienteEtiqueta(listOf("A", "C", "D"), SerieDePunto.MAYUSCULAS))
    }

    /** Las series no se estorban: poner minúsculas no gasta las mayúsculas. */
    @Test
    fun `cada serie va por su lado`() {
        val puestas = listOf("A", "B", "C")
        assertEquals("a", siguienteEtiqueta(puestas, SerieDePunto.MINUSCULAS))
        assertEquals("1", siguienteEtiqueta(puestas, SerieDePunto.NUMEROS))
    }

    @Test
    fun `las etiquetas se leen de los puntos de la escena`() {
        val escena = listOf(
            punto("p1", "A"),
            punto("p2", "B"),
            linea("l", Pt(0.0, 0.0), Pt(10.0, 0.0))
        )
        assertEquals(listOf("A", "B"), etiquetasUsadas(escena))
        assertEquals("C", siguienteEtiqueta(etiquetasUsadas(escena), SerieDePunto.MAYUSCULAS))
    }

    // ---- Dónde va la letra ----

    /** Sin nada alrededor, arriba a la derecha, que es donde la pone todo el mundo. */
    @Test
    fun `un punto suelto lleva la letra arriba a la derecha`() {
        val a = anguloLibre(Pt(0.0, 0.0), emptyList())
        assertEquals(-PI / 4, a, 0.01)
    }

    /**
     * **En un vértice, la letra va por fuera.**
     *
     * Es la prueba que da sentido a la herramienta. En la esquina de abajo a la
     * izquierda de un cuadrado salen dos lados: uno hacia la derecha y otro
     * hacia arriba. La letra tiene que irse hacia abajo-izquierda, que es el
     * cuadrante libre — y no «arriba a la derecha», que caería justo dentro de
     * la figura.
     */
    @Test
    fun `en un vértice la letra se va al hueco de fuera`() {
        val esquina = Pt(0.0, 0.0)
        val figura = listOf(
            linea("abajo", esquina, Pt(100.0, 0.0)),      // hacia la derecha
            linea("izq", esquina, Pt(0.0, -100.0))        // hacia arriba (Y va abajo)
        )
        val a = anguloLibre(esquina, figura)
        // El hueco libre es el cuadrante de abajo-izquierda: x negativo, y positivo.
        assertTrue("la letra cae dentro de la figura: $a", cos(a) < 0)
        assertTrue("la letra cae dentro de la figura: $a", sin(a) > 0)
    }

    /**
     * En mitad de una recta la letra se va perpendicular.
     *
     * La raya sigue a los dos lados, así que los dos huecos son iguales y
     * cualquiera de las dos perpendiculares vale — lo que no puede es quedarse
     * sobre la propia raya.
     */
    @Test
    fun `en mitad de una recta la letra se aparta de ella`() {
        val medio = Pt(50.0, 0.0)
        val recta = listOf(linea("r", Pt(0.0, 0.0), Pt(100.0, 0.0)))
        // El punto medio parte la recta en dos tramos que salen de él.
        val partida = listOf(
            linea("a", Pt(0.0, 0.0), medio),
            linea("b", medio, Pt(100.0, 0.0))
        )
        val a = anguloLibre(medio, partida)
        assertTrue("la letra se queda sobre la raya: $a", abs(sin(a)) > 0.7)
        // Y con la recta sin partir, el punto no toca ningún extremo: todo libre.
        assertEquals(-PI / 4, anguloLibre(medio, recta), 0.01)
    }

    // ---- La letra orbita ----

    @Test
    fun `la letra se coloca a su radio y en su ángulo`() {
        val e = punto("p", "A").copy(x = 100.0, y = 100.0, etiquetaAngulo = 0.0, etiquetaRadio = 20.0)
        val donde = sitioDeLaEtiqueta(e)
        assertEquals(120.0, donde.x, 0.01)
        assertEquals(100.0, donde.y, 0.01)
    }

    /**
     * **Arrastrar la letra la hace girar, no soltarse.**
     *
     * Una etiqueta a tres centímetros de su punto ya no dice de quién es, y en
     * un croquis con doce puntos eso lo convierte en un jeroglífico.
     */
    @Test
    fun `la letra orbita y no se separa`() {
        val e = punto("p", "A").copy(x = 0.0, y = 0.0)
        // Se tira de ella muy lejos: el ángulo obedece, la distancia se topa.
        val lejos = conLaEtiquetaHacia(e, Pt(1000.0, 0.0))
        assertEquals(0.0, lejos.etiquetaAngulo!!, 0.01)
        assertEquals(RADIO_MAXIMO, lejos.etiquetaRadio!!, 0.01)
        // Y encima del propio punto tampoco: se queda al radio mínimo.
        val encima = conLaEtiquetaHacia(e, Pt(2.0, 0.0))
        assertEquals(RADIO_MINIMO, encima.etiquetaRadio!!, 0.01)
    }

    @Test
    fun `soltar la letra justo en su punto no la mueve`() {
        val e = punto("p", "A").copy(x = 5.0, y = 5.0, etiquetaAngulo = 1.0, etiquetaRadio = 30.0)
        assertEquals(e, conLaEtiquetaHacia(e, Pt(5.0, 5.0)))
    }

    // ---- El punto entero ----

    @Test
    fun `un punto nuevo nace etiquetado y colocado`() {
        val figura = listOf(linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0)))
        val e = nuevoPunto("p1", Pt(0.0, 0.0), figura, SerieDePunto.MAYUSCULAS, ItemStyle(), 7)
        assertEquals(ElementType.PUNTO, e.type)
        assertEquals("A", e.text)
        assertEquals(0.0, e.width, 0.0)
        assertEquals(0.0, e.height, 0.0)
        assertTrue("no ha elegido sitio para la letra", e.etiquetaAngulo != null)
        assertEquals(RADIO_DE_LA_LETRA, e.etiquetaRadio!!, 0.01)
        // Y el siguiente sale B sin que nadie lo diga.
        val segundo = nuevoPunto("p2", Pt(50.0, 0.0), figura + e, SerieDePunto.MAYUSCULAS, ItemStyle(), 8)
        assertEquals("B", segundo.text)
    }

    // ---- Dónde se deja poner ----

    private fun anclaje(tipo: TipoAnclaje, de: String) = Anclaje(Pt(0.0, 0.0), tipo, de)

    private fun forma(id: String, tipo: ElementType) = Element(
        id = id, type = tipo, x = 0.0, y = 0.0, width = 50.0, height = 50.0, seed = 1
    )

    /**
     * **La intersección vale siempre.** Es la que más se busca y la única que no
     * pertenece a ninguna figura: nace de la relación entre dos.
     */
    @Test
    fun `una intersección siempre vale`() {
        val escena = listOf(forma("r", ElementType.RECTANGLE))
        assertTrue(sitioValidoParaPunto(anclaje(TipoAnclaje.INTERSECCION, "r"), escena))
    }

    /** De una recta valen sus extremos, sus vértices y sus medios. */
    @Test
    fun `de una recta valen extremos, vértices y medios`() {
        val escena = listOf(linea("l", Pt(0.0, 0.0), Pt(100.0, 0.0)))
        for (t in listOf(TipoAnclaje.EXTREMO, TipoAnclaje.ESQUINA, TipoAnclaje.MEDIO)) {
            assertTrue("$t debería valer", sitioValidoParaPunto(anclaje(t, "l"), escena))
        }
        assertTrue(!sitioValidoParaPunto(anclaje(TipoAnclaje.BORDE, "l"), escena))
    }

    /** De una circunferencia, solo el centro. */
    @Test
    fun `de un círculo solo vale el centro`() {
        val escena = listOf(forma("c", ElementType.ELLIPSE))
        assertTrue(sitioValidoParaPunto(anclaje(TipoAnclaje.CENTRO, "c"), escena))
        assertTrue(!sitioValidoParaPunto(anclaje(TipoAnclaje.ESQUINA, "c"), escena))
        assertTrue(!sitioValidoParaPunto(anclaje(TipoAnclaje.BORDE, "c"), escena))
    }

    /**
     * Las esquinas de un rectángulo o de un rombo se quedan fuera a propósito:
     * son vértices de una caja, no puntos de una construcción, y ofrecerlos
     * llenaría de candidatos justo cuando se busca la intersección de al lado.
     */
    @Test
    fun `las esquinas de una caja no son sitio`() {
        for (tipo in listOf(ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.TEXT)) {
            val escena = listOf(forma("f", tipo))
            assertTrue(
                "$tipo no debería ofrecer sus esquinas",
                !sitioValidoParaPunto(anclaje(TipoAnclaje.ESQUINA, "f"), escena)
            )
            assertTrue(
                "$tipo no debería ofrecer su centro",
                !sitioValidoParaPunto(anclaje(TipoAnclaje.CENTRO, "f"), escena)
            )
        }
    }

    @Test
    fun `un anclaje de algo que ya no está no vale`() {
        assertTrue(!sitioValidoParaPunto(anclaje(TipoAnclaje.EXTREMO, "fantasma"), emptyList()))
    }

    /** El texto ya no tiene caja: sus esquinas dejan de ser puntos de enganche. */
    @Test
    fun `un texto no aporta contorno`() {
        val t = Element(
            id = "t", type = ElementType.TEXT, x = 0.0, y = 0.0,
            width = 80.0, height = 20.0, seed = 1, text = "hola"
        )
        assertTrue("el texto sigue teniendo caja", contornosDe(t).isEmpty())
        assertTrue(segmentosDe(t).isEmpty())
    }

    /** Un punto no es pared ni tiene contorno: no estorba al bote ni a los cruces. */
    @Test
    fun `un punto no interfiere con la geometría`() {
        val e = punto("p", "A")
        assertTrue(!esPared(e))
        assertTrue(contornosDe(e).isEmpty())
    }
}

/**
 * El punto dentro del editor: ponerlo y moverlo.
 *
 * Lo de arriba comprueba las cuentas; esto comprueba **el gesto**, que es donde
 * se notaba que faltaba algo: el punto nacía clavado en la intersección y en
 * cuanto había que recolocarlo se movía a pulso y ahí se quedaba, a dos píxeles
 * del sitio.
 */
class PuntoEnElEditorTest {

    private fun conUnaLinea(): DrawController {
        val c = DrawController()
        c.load(
            Scene(
                elements = listOf(
                    Element(
                        id = "l", type = ElementType.LINE,
                        x = 0.0, y = 0.0, width = 200.0, height = 0.0, seed = 1,
                        points = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0))
                    )
                )
            )
        )
        return c
    }

    private fun puntos(c: DrawController) =
        c.scene.elements.filter { it.type == ElementType.PUNTO }

    /** Se pone en el extremo aunque el dedo caiga un poco al lado. */
    @Test
    fun `un toque cerca del extremo lo clava en el extremo`() {
        val c = conUnaLinea()
        c.selectTool(Tool.PUNTO)
        c.pointerDown(Pt(196.0, 5.0))
        c.pointerUp(Pt(196.0, 5.0))

        assertEquals(1, puntos(c).size)
        val p = puntos(c)[0]
        assertEquals(200.0, p.x, 0.001)
        assertEquals(0.0, p.y, 0.001)
        assertEquals("A", p.text)
    }

    /** Y donde no hay nada que nombrar, no se pone nada — y se dice. */
    @Test
    fun `en medio de la nada no se planta`() {
        val c = conUnaLinea()
        c.selectTool(Tool.PUNTO)
        c.pointerDown(Pt(600.0, 600.0))
        c.pointerUp(Pt(600.0, 600.0))

        assertTrue(puntos(c).isEmpty())
        assertTrue("no ha avisado de que ahí no hay sitio", c.puntoSinSitio)
    }

    /**
     * **Arrastrarlo también imanta.** Es lo que faltaba.
     *
     * Se coge el punto que está en un extremo y se lleva cerca del otro: tiene
     * que caer clavado en él, no a la distancia a la que se soltó el dedo.
     */
    @Test
    fun `al arrastrarlo se pega al extremo de al lado`() {
        val c = conUnaLinea()
        c.selectTool(Tool.PUNTO)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerUp(Pt(0.0, 0.0))
        assertEquals(1, puntos(c).size)

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(194.0, 6.0))
        c.pointerUp(Pt(194.0, 6.0))

        val p = puntos(c)[0]
        assertEquals("no se ha imantado al arrastrarlo", 200.0, p.x, 0.001)
        assertEquals(0.0, p.y, 0.001)
    }

    /**
     * Pero lejos de todo se mueve libre.
     *
     * Es la diferencia con ponerlo: poner es decidir dónde va —y sin sitio no se
     * pone nada—, mover es corregirlo. Un imán tira cuando estás cerca y no ata
     * cuando no lo estás.
     */
    @Test
    fun `lejos de todo el punto se mueve libre`() {
        val c = conUnaLinea()
        c.selectTool(Tool.PUNTO)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerUp(Pt(0.0, 0.0))

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(500.0, 400.0))
        c.pointerUp(Pt(500.0, 400.0))

        val p = puntos(c)[0]
        assertEquals(500.0, p.x, 0.001)
        assertEquals(400.0, p.y, 0.001)
    }

    /** Y moverlo se deshace de una vez, como cualquier otra cosa. */
    @Test
    fun `mover un punto entra en el historial`() {
        val c = conUnaLinea()
        c.selectTool(Tool.PUNTO)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerUp(Pt(0.0, 0.0))

        c.selectTool(Tool.SELECTION)
        c.pointerDown(Pt(0.0, 0.0))
        c.pointerMove(Pt(200.0, 0.0))
        c.pointerUp(Pt(200.0, 0.0))
        assertEquals(200.0, puntos(c)[0].x, 0.001)

        c.undo()
        assertEquals(0.0, puntos(c)[0].x, 0.001)
    }
}
