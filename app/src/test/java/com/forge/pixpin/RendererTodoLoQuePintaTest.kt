package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Y lo mismo para lo que se pinta en pantalla.**
 *
 * La hermana de [PdfTodasLasHerramientasTest]. Las salidas a papel llevan tiempo con su
 * barrida del enum entero —nacieron de tres tipos que dejaron de escribirse sin que nadie se
 * enterara— y el pintado de pantalla, que es **el camino principal**, no tenía ninguna: se
 * miraba a ojo, y a ojo no se recorren veintitantos tipos cada vez que se toca el
 * renderizador.
 *
 * El `when` de `renderElementCuerpo` es exhaustivo, así que el compilador ya obliga a que
 * cada tipo tenga su rama. Lo que eso **no** dice es que la rama pinte algo: una rama que
 * calcula y no llega a dejar tinta compila igual de bien, y es exactamente la forma en que
 * se rompieron las otras dos salidas.
 *
 * ## Se cuentan órdenes, no píxeles
 *
 * Lo primero que se intentó fue pintar sobre un mapa de bits blanco y mirar qué dejaba de
 * ser blanco. **Aquí no vale**: Robolectric solo rasteriza de verdad donde tiene su runtime
 * gráfico nativo, y sobre Linux en ARM64 —que es donde se compila esto— no lo hay: el dibujo
 * es una operación vacía y el mapa de bits se queda a ceros. Una prueba así daría todos los
 * tipos por mudos y sería peor que no tenerla.
 *
 * Así que se cuenta lo que el renderizador **le pide al lienzo**, con un `Canvas` que lleva
 * la cuenta. Es incluso lo que hay que comprobar: que la rama de cada tipo llega a pedir un
 * trazo, y no si ese trazo sale de un tono u otro. Y no depende de la máquina, así que vale
 * igual aquí que en cualquier otra.
 *
 * Tampoco se compara contra una imagen guardada, y eso sí es a propósito: una prueba de foto
 * se rompe cada vez que se afina un margen y acaba desactivada. Lo que hay que vigilar aquí
 * no es el aspecto, es el silencio.
 *
 * **Al añadir una orden de dibujo nueva al renderizador hay que añadirla también a
 * [LienzoQueCuenta].** Si no, el tipo que solo use esa saldrá por mudo sin serlo.
 */
/**
 * Un `Canvas` que no pinta: **apunta cuántas veces se lo piden**.
 *
 * Cubre las siete órdenes que emite hoy el renderizador —`drawPath`, `drawText`, `drawLine`,
 * `drawCircle`, `drawRect`, `drawRoundRect` y `drawBitmap`— con todas sus formas, más unas
 * cuantas de propina por si alguna se empieza a usar. No se llama a `super`: aquí no hay
 * nada que pintar, y sobre un mapa de bits sin rasterizador algunas revientan.
 */
private class LienzoQueCuenta(b: Bitmap) : Canvas(b) {
    var ordenes = 0

    override fun drawPath(path: Path, paint: Paint) { ordenes++ }
    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { ordenes++ }
    override fun drawLines(pts: FloatArray, off: Int, count: Int, paint: Paint) { ordenes++ }
    override fun drawCircle(cx: Float, cy: Float, radio: Float, paint: Paint) { ordenes++ }
    override fun drawOval(rect: RectF, paint: Paint) { ordenes++ }
    override fun drawArc(
        rect: RectF, desde: Float, barrido: Float, centro: Boolean, paint: Paint
    ) { ordenes++ }
    override fun drawPoints(pts: FloatArray, off: Int, count: Int, paint: Paint) { ordenes++ }

    override fun drawRect(rect: RectF, paint: Paint) { ordenes++ }
    override fun drawRect(rect: Rect, paint: Paint) { ordenes++ }
    override fun drawRect(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { ordenes++ }
    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) { ordenes++ }
    override fun drawRoundRect(
        x0: Float, y0: Float, x1: Float, y1: Float, rx: Float, ry: Float, paint: Paint
    ) { ordenes++ }

    override fun drawText(t: String, x: Float, y: Float, p: Paint) { ordenes++ }
    override fun drawText(t: String, ini: Int, fin: Int, x: Float, y: Float, p: Paint) { ordenes++ }
    override fun drawText(
        t: CharSequence, ini: Int, fin: Int, x: Float, y: Float, p: Paint
    ) { ordenes++ }
    override fun drawText(
        t: CharArray, ini: Int, cuantos: Int, x: Float, y: Float, p: Paint
    ) { ordenes++ }
    override fun drawTextOnPath(t: String, camino: Path, hx: Float, hy: Float, p: Paint) { ordenes++ }

    override fun drawBitmap(b: Bitmap, x: Float, y: Float, p: Paint?) { ordenes++ }
    override fun drawBitmap(b: Bitmap, m: Matrix, p: Paint?) { ordenes++ }
    override fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, p: Paint?) { ordenes++ }
    override fun drawBitmap(b: Bitmap, src: Rect?, dst: Rect, p: Paint?) { ordenes++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RendererTodoLoQuePintaTest {

    /**
     * Los que **a propósito** no piden nada, con su motivo. **Hoy no hay ninguno.**
     *
     * Y esa es la diferencia con [PdfTodasLasHerramientasTest], que sí tiene uno: allí la
     * hoja se deja fuera porque un `FRAME` es el papel y no una raya escrita en él, pero
     * **en pantalla la hoja sí se dibuja** —su borde es lo que dice dónde acaba lo que va a
     * salir impreso—. Copiar la lista de la otra prueba era el error: la salida a papel y
     * el pintado no tienen por qué callarse ante lo mismo.
     *
     * Se deja el mapa aunque esté vacío para que apuntar una excepción algún día sea
     * escribir su motivo aquí, y no descubrir que hay que inventarse el sitio.
     */
    private val noPintanNada = emptyMap<ElementType, String>()

    /** Un elemento de cada tipo, con lo mínimo para que tenga algo que dibujar. */
    private fun ejemplar(tipo: ElementType): Element = Element(
        id = "e-$tipo",
        type = tipo,
        x = 60.0, y = 60.0, width = 220.0, height = 120.0,
        seed = 1234,
        strokeColor = "#1971c2",
        backgroundColor = "#a5d8ff",
        fillStyle = FillStyle.HACHURE,
        strokeWidth = 2.0,
        text = "Ab 12",
        fontSize = 20.0,
        points = listOf(Pt(0.0, 0.0), Pt(120.0, 40.0), Pt(220.0, 120.0)),
        pressures = listOf(0.5, 0.8, 0.4),
        huecos = emptyList(),
        arcStart = 0.0,
        arcSweep = Math.PI,
        // Levantada de verdad: con la altura a nulo el sólido sería una plancha y se
        // pintaría una sola cara.
        altura = 90.0,
        etiquetaAngulo = -Math.PI / 4,
        etiquetaRadio = 22.0,
        // Sin `fileId` una imagen no tiene a qué foto referirse.
        fileId = "foto-de-prueba"
    )

    /** Cuántas órdenes de dibujo pide [e] al pintarse. */
    private fun pinta(e: Element): Int {
        val ancho = 400
        val alto = 300
        val lienzo = LienzoQueCuenta(
            Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        )
        val escena = Scene(
            escala = Escala(unidadesPorPixel = 0.01, unidad = "m"),
            elements = listOf(e)
        )
        // **Con proveedor de imágenes de verdad.** Con el de por defecto —que devuelve
        // nulo— una imagen no tiene mapa de bits que pintar y saldría por muda sin serlo:
        // lo roto estaría en la prueba, no en el renderizador.
        val foto = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        Renderer(imageProvider = { foto })
            .renderScene(lienzo, escena, ancho.toDouble(), alto.toDouble())
        return lienzo.ordenes
    }

    /**
     * El recorrido entero: lo que dibuja tiene que dejar píxeles; lo que no, tiene que estar
     * en la lista de excepciones **con su motivo**.
     */
    @Test
    fun `todos los tipos del motor piden algo al lienzo`() {
        val mudos = mutableListOf<ElementType>()
        val hablan = mutableListOf<ElementType>()
        for (tipo in ElementType.entries) {
            val tocados = pinta(ejemplar(tipo))
            if (tipo in noPintanNada) {
                // Que uno de los apartados empiece a pintar tampoco es normal: querría
                // decir que la decisión de dejarlo fuera se ha roto.
                if (tocados > 0) hablan += tipo
            } else if (tocados == 0) {
                mudos += tipo
            }
        }
        assertTrue(
            "estos tipos no piden nada al lienzo: $mudos; y estos pintan y no debían: $hablan",
            mudos.isEmpty() && hablan.isEmpty()
        )
    }

    /** Los rellenos, uno por uno: cada uno pasa por un camino distinto. */
    @Test
    fun `todos los rellenos piden algo al lienzo`() {
        val mudos = FillStyle.entries.filter {
            pinta(ejemplar(ElementType.RECTANGLE).copy(fillStyle = it)) == 0
        }
        assertTrue("estos rellenos no piden nada: $mudos", mudos.isEmpty())
    }

    /** Y los tipos de raya: continua, a trazos y de puntos. */
    @Test
    fun `todos los estilos de trazo piden algo al lienzo`() {
        val mudos = StrokeStyle.entries.filter {
            pinta(ejemplar(ElementType.LINE).copy(strokeStyle = it)) == 0
        }
        assertTrue("estos trazos no piden nada: $mudos", mudos.isEmpty())
    }

    /** Y las puntas de flecha, que cada una se dibuja distinta. */
    @Test
    fun `todas las puntas de flecha piden algo al lienzo`() {
        val mudas = Arrowhead.entries.filter {
            pinta(ejemplar(ElementType.ARROW).copy(endArrowhead = it)) == 0
        }
        assertTrue("estas puntas no piden nada: $mudas", mudas.isEmpty())
    }

    /**
     * Lo girado y lo translúcido siguen viéndose.
     *
     * Se aplican **fuera** de la geometría —una matriz y una opacidad en el pincel— así que
     * no se rompen igual que lo demás y hay que mirarlas aparte.
     */
    @Test
    fun `lo girado y lo translúcido siguen pidiendo`() {
        val girado = ejemplar(ElementType.RECTANGLE).copy(angle = Math.toRadians(30.0))
        assertTrue("lo girado no pide nada", pinta(girado) > 0)
        val translucido = ejemplar(ElementType.RECTANGLE).copy(opacity = 40)
        assertTrue("lo translúcido no pide nada", pinta(translucido) > 0)
    }
}
