package com.forge.pixpin.motor

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La caja en volumen **ya metida en el motor**.
 *
 * `SolidoTest` comprueba la cuenta pura —las caras, el sombreado, el orden—; lo
 * que se vigila aquí es lo otro, que es donde de verdad se rompen las cosas: que
 * el elemento y el sólido digan lo mismo, que se pueda tocar lo que se ve y solo
 * lo que se ve, que el gesto de dos fases no confunda subir con moverse, y que
 * la cámara sobreviva a guardar y volver a abrir.
 *
 * Casi todo son fallos que **no se ven como fallos**: una caja que se selecciona
 * desde una esquina vacía parece que el dedo va mal, una vista que no se guarda
 * parece que el dibujo cambió solo, y una huella con la anchura en negativo se
 * dibuja perfectamente mientras rompe la selección por dentro.
 */
class SolidoEnElMotorTest {

    private val tol = 1e-9
    private val vistas = Vista.entries

    /** Una caja de tres cuadros de lado apoyada en el (100, 100) de la escena. */
    private fun caja(
        x: Double = 100.0,
        y: Double = 100.0,
        ancho: Double = 60.0,
        fondo: Double = 60.0,
        alto: Double? = 60.0
    ): Element = newElement(ElementType.SOLIDO, x, y, ItemStyle(), ancho, fondo)
        .copy(altura = alto, backgroundColor = "#808080")

    // ---------------------------------------------------------------------
    // El puente entre el elemento y el sólido
    // ---------------------------------------------------------------------

    /**
     * La huella del elemento **es** la del sólido: si esto se desalinea, todo lo
     * demás —picar, exportar, imantar— trabaja sobre una caja distinta de la que
     * se ve, y ninguna prueba de dibujo lo notaría.
     */
    @Test
    fun `el elemento y el solido cuentan lo mismo`() {
        val e = caja(ancho = 40.0, fondo = 25.0, alto = 70.0)
        val s = solidoDe(e)
        assertEquals(0.0, s.x, tol)
        assertEquals(0.0, s.y, tol)
        assertEquals(40.0, s.ancho, tol)
        assertEquals(25.0, s.fondo, tol)
        assertEquals(70.0, s.altura, tol)
    }

    /** Sin altura escrita todavía, la caja es una plancha y no un fantasma. */
    @Test
    fun `una caja sin levantar tiene altura cero`() {
        assertEquals(0.0, solidoDe(caja(alto = null)).altura, tol)
    }

    /**
     * El origen de la huella cae **exactamente** en `(x, y)`: es la promesa del
     * convenio, y de ella depende que mover la caja sea sumar.
     */
    @Test
    fun `el origen de la huella cae en la esquina del elemento`() {
        for (v in vistas) {
            val e = caja(x = 30.0, y = -12.0)
            val sombra = sombraDeElemento(e, v)
            assertEquals("vista $v", 30.0, sombra.first().x, tol)
            assertEquals("vista $v", -12.0, sombra.first().y, tol)
        }
    }

    /** Moverla es sumar a `x` e `y`, sin volver a proyectar nada. */
    @Test
    fun `mover la caja traslada todo el dibujo`() {
        val e = caja()
        val movida = e.copy(x = e.x + 17.0, y = e.y - 5.0)
        val antes = carasDeElemento(e, Vista.CERO).flatMap { it.poligono }
        val despues = carasDeElemento(movida, Vista.CERO).flatMap { it.poligono }
        assertEquals(antes.size, despues.size)
        for ((a, b) in antes.zip(despues)) {
            assertEquals(a.x + 17.0, b.x, tol)
            assertEquals(a.y - 5.0, b.y, tol)
        }
    }

    // ---------------------------------------------------------------------
    // Los contornos
    // ---------------------------------------------------------------------

    /**
     * Tres caras y todas cerradas. Son las que se ven, y son las que hacen de
     * pared para el bote y de raíl para el imán.
     */
    @Test
    fun `el contorno de la caja son sus tres caras visibles`() {
        for (v in vistas) {
            val contornos = contornosDe(caja(), PASO_PERIMETRO, v)
            assertEquals("vista $v", 3, contornos.size)
            assertTrue("vista $v", contornos.all { it.cerrado && it.puntos.size == 4 })
        }
    }

    /** Una caja aplastada del todo no aporta paredes falsas. */
    @Test
    fun `una huella sin tamaño no da contornos`() {
        val plana = caja(ancho = 0.0, fondo = 0.0, alto = 0.0)
        assertTrue(contornosDe(plana, PASO_PERIMETRO, Vista.CERO).isEmpty())
    }

    /**
     * **Los contornos cambian al girar la cámara.** Es lo que hace que el imán y
     * el bote sigan al dibujo en vez de quedarse donde estaba; si esta prueba
     * pasara con las dos vistas dando lo mismo, sería que la vista no se está
     * mirando.
     */
    @Test
    fun `girar la vista cambia los contornos`() {
        val e = caja(ancho = 80.0, fondo = 20.0)
        val cero = contornosDe(e, PASO_PERIMETRO, Vista.CERO).flatMap { it.puntos }
        val cuarto = contornosDe(e, PASO_PERIMETRO, Vista.CUARTO).flatMap { it.puntos }
        assertFalse(cero == cuarto)
    }

    /** Es pared: se puede rellenar el hueco que deja contra otra cosa. */
    @Test
    fun `la caja hace de pared para el bote`() {
        assertTrue(esPared(caja()))
    }

    // ---------------------------------------------------------------------
    // La colisión
    // ---------------------------------------------------------------------

    /**
     * **La esquina vacía de la caja envolvente no selecciona.**
     *
     * Es la razón entera por la que se pica contra el polígono y no contra
     * [cajaDeSolido]. La silueta de una caja isométrica es un hexágono, y su
     * rectángulo envolvente le añade cuatro triángulos donde se ve el dibujo de
     * debajo. Si esos triángulos robaran el toque, no habría forma de coger nada
     * que estuviera detrás de un volumen.
     */
    @Test
    fun `la caja se coge por su silueta y no por su rectangulo`() {
        val e = caja(x = 200.0, y = 200.0, ancho = 60.0, fondo = 60.0, alto = 60.0)
        val envolvente = cajaDeSolido(solidoDe(e), Vista.CERO, PASO_DEL_SOLIDO)

        // El centro de la tapa: dentro de las dos, tiene que coger.
        val tapa = carasDeElemento(e, Vista.CERO).first { it.cara == Cara.TAPA }.poligono
        val dentro = Pt(tapa.sumOf { it.x } / 4, tapa.sumOf { it.y } / 4)
        assertTrue(hitElementItself(dentro, e, 0.0, Vista.CERO))

        // La esquina de arriba a la izquierda del rectángulo envolvente: dentro
        // de la caja envolvente y **fuera** del hexágono.
        val esquina = Pt(e.x + envolvente.x1 + 1.0, e.y + envolvente.y1 + 1.0)
        assertTrue(
            "la esquina tiene que caer dentro del rectángulo, o no se prueba nada",
            esquina.x >= e.x + envolvente.x1 && esquina.y >= e.y + envolvente.y1
        )
        assertFalse(hitElementItself(esquina, e, 0.0, Vista.CERO))
    }

    /**
     * Se coge por dentro **aunque no tenga relleno**: es un bulto, no un aro.
     * Pedir que se acierte en una arista de un píxel la haría inservible.
     */
    @Test
    fun `una caja sin fondo se sigue cogiendo por dentro`() {
        val e = caja().copy(backgroundColor = Element.TRANSPARENT)
        assertTrue(shouldTestInside(e))
        val centroDeLaTapa = carasDeElemento(e, Vista.CERO)
            .first { it.cara == Cara.TAPA }
            .poligono
            .let { p -> Pt(p.sumOf { it.x } / 4, p.sumOf { it.y } / 4) }
        assertTrue(hitElementItself(centroDeLaTapa, e, 0.0, Vista.CERO))
    }

    /**
     * **Lo que se ve por encima de la huella también se toca.**
     *
     * El descarte rápido de [hitElementItself] va contra la caja del elemento, y
     * la del sólido es la huella en el suelo: la parte alta del volumen queda
     * fuera de ella. Sin la envoltura, se veía la caja y no se podía coger por
     * arriba, que es justo por donde uno la agarra.
     */
    @Test
    fun `la caja se coge también por su parte alta`() {
        val e = caja(x = 300.0, y = 300.0, alto = 120.0)
        val tapa = carasDeElemento(e, Vista.CERO).first { it.cara == Cara.TAPA }.poligono
        val centro = Pt(tapa.sumOf { it.x } / 4, tapa.sumOf { it.y } / 4)
        assertTrue(
            "la tapa tiene que quedar por encima de la huella, o no se prueba nada",
            centro.y < e.y
        )
        assertNotNull(getElementAtPosition(listOf(e), centro, 0.0, Vista.CERO))
    }

    /** Y girando la cámara, se coge donde ahora se ve, no donde se veía. */
    @Test
    fun `al girar la vista el toque sigue a la caja`() {
        val e = caja(x = 400.0, y = 400.0, ancho = 100.0, fondo = 20.0, alto = 20.0)
        val puntoDeCero = carasDeElemento(e, Vista.CERO)
            .first { it.cara == Cara.TAPA }.poligono
            .let { p -> Pt(p.sumOf { it.x } / 4, p.sumOf { it.y } / 4) }

        assertTrue(hitElementItself(puntoDeCero, e, 0.0, Vista.CERO))
        // El mismo punto, mirando desde el otro lado: la caja ya no está ahí.
        assertFalse(hitElementItself(puntoDeCero, e, 0.0, Vista.MEDIA))
    }

    // ---------------------------------------------------------------------
    // La huella que deja el arrastre
    // ---------------------------------------------------------------------

    /** El dedo donde bajó es siempre una esquina de la huella. */
    @Test
    fun `el punto donde baja el dedo es una esquina`() {
        for (v in vistas) {
            val origen = Pt(50.0, 60.0)
            val h = huellaArrastrada(origen, Pt(origen.x + 90.0, origen.y + 40.0), v)
            val e = caja(h.x, h.y, h.ancho, h.fondo, 0.0)
            val esquinas = sombraDeElemento(e, v)
            assertTrue(
                "vista $v",
                esquinas.any { abs(it.x - origen.x) < 1e-6 && abs(it.y - origen.y) < 1e-6 }
            )
        }
    }

    /**
     * **Arrastrando hacia atrás la huella sale igual de válida.**
     *
     * Es el fallo silencioso que se evita recolocando la esquina aquí y no en
     * [Solido.normalizado]: allí el dibujo saldría bien pero `width` se quedaría
     * en negativo dentro del elemento, y una caja con la anchura negativa rompe
     * todo lo que mide cajas, empezando por la selección.
     */
    @Test
    fun `arrastrando hacia atrás no salen medidas negativas`() {
        for (v in vistas) {
            for (dx in listOf(-140.0, 140.0)) {
                for (dy in listOf(-90.0, 90.0)) {
                    val h = huellaArrastrada(Pt(0.0, 0.0), Pt(dx, dy), v)
                    assertTrue("vista $v, $dx/$dy", h.ancho >= 0.0)
                    assertTrue("vista $v, $dx/$dy", h.fondo >= 0.0)
                }
            }
        }
    }

    /** La huella se pega a la retícula: nada de 41 por 79. */
    @Test
    fun `la huella se imanta a la retícula`() {
        val h = huellaArrastrada(Pt(0.0, 0.0), Pt(103.0, 47.0), Vista.CERO)
        assertEquals(0.0, h.ancho % PASO_ISO, tol)
        assertEquals(0.0, h.fondo % PASO_ISO, tol)
    }

    /** La altura también, y nunca por debajo del suelo. */
    @Test
    fun `la altura se imanta y no baja del suelo`() {
        assertEquals(PASO_ISO, alturaImantada(PASO_ISO * 1.2), tol)
        assertEquals(2 * PASO_ISO, alturaImantada(PASO_ISO * 1.7), tol)
        assertEquals(0.0, alturaImantada(-500.0), tol)
    }

    // ---------------------------------------------------------------------
    // El gesto de dos fases
    // ---------------------------------------------------------------------

    /**
     * Una caja con la huella ya dibujada y esperando altura.
     *
     * El arrastre va **en vertical puro**, que es a propósito: es justo la
     * dirección ambigua de la isométrica. En la primera fase no lo es —todo lo
     * que hace el dedo es suelo— y sale una huella cuadrada de tres por tres
     * cuadros, `(100, 100)` a `(100, 160)` en la escena.
     */
    private fun conLaHuellaPuesta(): DrawController {
        val c = DrawController()
        c.selectTool(Tool.SOLIDO)
        c.pointerDown(Pt(100.0, 100.0))
        c.pointerMove(Pt(100.0, 160.0))
        c.pointerUp(Pt(100.0, 160.0))
        return c
    }

    /** Primera fase: sale una huella apoyada en el suelo, todavía sin levantar. */
    @Test
    fun `el primer arrastre deja la huella pendiente de altura`() {
        val c = conLaHuellaPuesta()
        val e = c.scene.visible.single()
        assertEquals(ElementType.SOLIDO, e.type)
        assertNull("todavía no se ha levantado", e.altura)
        assertTrue(e.width > 0.0 && e.height > 0.0)
        assertEquals(e.id, c.solidoPendiente)
    }

    /**
     * **Segunda fase: solo cuenta el desplazamiento vertical.**
     *
     * Es la prueba de la decisión de diseño entera. Dos arrastres que suben lo
     * mismo en pantalla pero uno se va de lado tienen que dar **exactamente** la
     * misma altura: si el eje horizontal se colara en la cuenta, un arrastre en
     * diagonal levantaría distinto que uno recto y volvería la ambigüedad de la
     * isométrica que este gesto existe para eliminar.
     */
    @Test
    fun `al levantar solo se lee el desplazamiento vertical`() {
        val recto = conLaHuellaPuesta()
        recto.pointerDown(Pt(200.0, 200.0))
        recto.pointerMove(Pt(200.0, 120.0))
        recto.pointerUp(Pt(200.0, 120.0))

        val diagonal = conLaHuellaPuesta()
        diagonal.pointerDown(Pt(200.0, 200.0))
        diagonal.pointerMove(Pt(600.0, 120.0))
        diagonal.pointerUp(Pt(600.0, 120.0))

        assertEquals(
            recto.scene.visible.single().altura!!,
            diagonal.scene.visible.single().altura!!,
            tol
        )
        assertTrue("y tiene que haber subido", recto.scene.visible.single().altura!! > 0.0)
    }

    /** Subir el dedo sube la caja: en pantalla la `y` crece hacia abajo. */
    @Test
    fun `arrastrar hacia arriba levanta y hacia abajo baja`() {
        val c = conLaHuellaPuesta()
        c.pointerDown(Pt(200.0, 200.0))
        c.pointerMove(Pt(200.0, 100.0))
        c.pointerUp(Pt(200.0, 100.0))
        val arriba = c.scene.visible.single().altura!!
        assertTrue(arriba > 0.0)

        // Y otro arrastre corrige la que ya tenía en vez de empezar de cero.
        c.pointerDown(Pt(200.0, 200.0))
        c.pointerMove(Pt(200.0, 260.0))
        c.pointerUp(Pt(200.0, 260.0))
        assertTrue(c.scene.visible.single().altura!! < arriba)
    }

    /** Un toque la fija, y a partir de ahí el siguiente gesto empieza otra caja. */
    @Test
    fun `un toque fija la altura`() {
        val c = conLaHuellaPuesta()
        c.pointerDown(Pt(200.0, 200.0))
        c.pointerMove(Pt(200.0, 120.0))
        c.pointerUp(Pt(200.0, 120.0))
        assertNotNull("sigue pendiente tras arrastrar", c.solidoPendiente)

        c.pointerDown(Pt(300.0, 300.0))
        c.pointerUp(Pt(300.0, 300.0))
        assertNull(c.solidoPendiente)

        // Y lo siguiente que se arrastre es una caja nueva, no la de antes.
        c.pointerDown(Pt(400.0, 400.0))
        c.pointerMove(Pt(400.0, 460.0))
        c.pointerUp(Pt(400.0, 460.0))
        assertEquals(2, c.scene.visible.size)
    }

    /**
     * Fijar sin haber levantado deja un cuadro de alto y no una plancha: quien
     * coge la herramienta del volumen quiere volumen.
     */
    @Test
    fun `una caja fijada sin levantar sale con un cuadro de alto`() {
        val c = conLaHuellaPuesta()
        c.pointerDown(Pt(300.0, 300.0))
        c.pointerUp(Pt(300.0, 300.0))
        assertEquals(PASO_ISO, c.scene.visible.single().altura!!, tol)
    }

    /** Un toque seco con la herramienta puesta no deja nada tirado. */
    @Test
    fun `un toque sin arrastrar no deja caja`() {
        val c = DrawController()
        c.selectTool(Tool.SOLIDO)
        c.pointerDown(Pt(10.0, 10.0))
        c.pointerUp(Pt(10.0, 10.0))
        assertTrue(c.scene.visible.isEmpty())
        assertNull(c.solidoPendiente)
    }

    /** Cambiar de herramienta cierra la caja a medias en vez de dejarla colgando. */
    @Test
    fun `cambiar de herramienta fija la caja pendiente`() {
        val c = conLaHuellaPuesta()
        c.selectTool(Tool.RECTANGLE)
        assertNull(c.solidoPendiente)
        assertEquals(PASO_ISO, c.scene.visible.single().altura!!, tol)
    }

    /** Y la caja hecha se selecciona tocándola, como cualquier otra cosa. */
    @Test
    fun `la caja terminada se puede seleccionar`() {
        val c = conLaHuellaPuesta()
        c.pointerDown(Pt(200.0, 200.0))
        c.pointerMove(Pt(200.0, 120.0))
        c.pointerUp(Pt(200.0, 120.0))
        c.selectTool(Tool.SELECTION)

        val e = c.scene.visible.single()
        val tapa = carasDeElemento(e, c.scene.vista).first { it.cara == Cara.TAPA }.poligono
        val centro = Pt(tapa.sumOf { it.x } / 4, tapa.sumOf { it.y } / 4)
        c.pointerDown(centro)
        c.pointerUp(centro)
        assertEquals(setOf(e.id), c.selectedIds)
    }

    // ---------------------------------------------------------------------
    // Que viaje con el dibujo
    // ---------------------------------------------------------------------

    /** La caja se guarda entera, con su altura, y vuelve igual. */
    @Test
    fun `la caja se guarda y se relee`() {
        val escena = Scene(elements = listOf(caja(alto = 140.0)))
        val vuelta = ExcalidrawJson.decodeFromString<Scene>(
            ExcalidrawJson.encodeToString(escena)
        )
        val leida = vuelta.elements.single()
        assertEquals(ElementType.SOLIDO, leida.type)
        assertEquals(140.0, leida.altura!!, tol)
        assertEquals(60.0, leida.width, tol)
    }

    /** Y la cámara con él: es del dibujo, no de la sesión. */
    @Test
    fun `la vista de la escena se guarda y se relee`() {
        for (v in vistas) {
            val vuelta = ExcalidrawJson.decodeFromString<Scene>(
                ExcalidrawJson.encodeToString(Scene(vista = v))
            )
            assertEquals(v, vuelta.vista)
        }
    }

    /**
     * **Un dibujo de antes de que existiera la cámara sigue abriéndose.**
     *
     * Es lo que hay que comprobar de verdad al añadir un campo a la escena: el
     * archivo viejo no lleva la clave, y sin valor por defecto la lectura
     * reventaría entera —no solo el campo nuevo— y el dibujo no se abriría.
     */
    @Test
    fun `una escena vieja sin vista se lee con la de partida`() {
        val viejo = """{"elements":[],"backgroundColor":"#ffffff"}"""
        val escena = ExcalidrawJson.decodeFromString<Scene>(viejo)
        assertEquals(Vista.CERO, escena.vista)
    }

    /** Y un elemento viejo sin altura tampoco estorba. */
    @Test
    fun `un elemento viejo sin altura se lee sin altura`() {
        val viejo = """
            {"elements":[{"id":"a","type":"rectangle","x":0,"y":0,
            "width":10,"height":10,"seed":1}]}
        """.trimIndent()
        val escena = ExcalidrawJson.decodeFromString<Scene>(viejo)
        assertNull(escena.elements.single().altura)
    }

    /** El nombre serializado es el que se guarda en el archivo, y no cambia. */
    @Test
    fun `el tipo se guarda con su nombre de pixpin`() {
        val texto = ExcalidrawJson.encodeToString(Scene(elements = listOf(caja())))
        assertTrue(texto.contains("\"pixpin-solid\""))
    }

    // ---------------------------------------------------------------------
    // La trampa de la caché
    // ---------------------------------------------------------------------

    /**
     * **La altura tiene que invalidar la geometría cacheada.**
     *
     * Sin esto, una caja que se está levantando tiene la misma huella en cada
     * fotograma —lo que crece es lo que sube, no `width` ni `height`— así que la
     * caché devolvería la primera altura generada y ahí se quedaría. Es el mismo
     * fallo que tuvo el arco, que se dibujaba un pellizco y no crecía más.
     */
    @Test
    fun `subir la caja invalida la geometría`() {
        val e = caja(alto = 40.0)
        assertTrue(hasSameGeometry(e, e.copy()))
        assertFalse(hasSameGeometry(e, e.copy(altura = 80.0)))
        assertFalse(hasSameGeometry(e, e.copy(altura = null)))
    }
}
