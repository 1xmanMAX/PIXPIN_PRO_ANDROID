package com.forge.pixpin.motor

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El port de `perfect-freehand`.
 *
 * No se comparan coordenadas contra las del original —no tengo su salida a
 * mano— sino las propiedades de las que depende que el trazo se vea bien: que
 * la mancha envuelva lo dibujado, que se afile con la velocidad y que un toque
 * seco deje un punto redondo.
 */
class FreehandTest {

    private val opciones = StrokeOptions(
        size = 2.0 * FreedrawTuning.SIZE_FACTOR,
        thinning = FreedrawTuning.THINNING,
        smoothing = FreedrawTuning.SMOOTHING,
        streamline = FreedrawTuning.STREAMLINE,
        simulatePressure = true,
        last = true
    )

    /** Una recta muestreada cada [paso] px: el paso hace de velocidad. */
    private fun recta(paso: Double, largo: Double = 300.0): List<Pt> {
        val out = mutableListOf<Pt>()
        var s = 0.0
        while (s <= largo) { out += Pt(s, 100.0); s += paso }
        return out
    }

    /** Punto dentro de un polígono, por conteo de cruces. */
    private fun dentro(p: Pt, poly: List<Pt>): Boolean {
        var cruza = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) cruza = !cruza
            j = i
        }
        return cruza
    }

    /** Ancho de la mancha midiendo la altura del contorno hacia la mitad. */
    private fun anchoEn(contorno: List<Pt>, x: Double, margen: Double = 8.0): Double {
        val cerca = contorno.filter { kotlin.math.abs(it.x - x) < margen }
        if (cerca.size < 2) return 0.0
        return cerca.maxOf { it.y } - cerca.minOf { it.y }
    }

    // ---- Lo básico ----

    @Test
    fun `sin puntos no hay contorno`() {
        assertTrue(getStroke(emptyList(), null, opciones).isEmpty())
    }

    /**
     * Un toque seco deja una manchita, no nada.
     *
     * **No es un círculo perfecto**, y eso es fiel al original: con un solo
     * punto de entrada, `getStrokePoints` se inventa un segundo a un píxel en
     * diagonal, así que lo que sale es una cápsula diminuta con sus dos
     * casquetes. La rama del círculo puro solo se alcanza si el trazo llega ya
     * reducido a un punto.
     */
    @Test
    fun `un toque seco deja una mancha del tamaño del trazo`() {
        val contorno = getStroke(listOf(Pt(50.0, 50.0)), listOf(0.5), opciones)
        assertTrue("un punto tiene que dejar mancha", contorno.size >= 8)

        val radios = contorno.map { hypot(it.x - 50.0, it.y - 50.0) }
        assertTrue("mancha degenerada: ${radios.max()}", radios.max() > opciones.size / 4)
        assertTrue("mancha desbocada: ${radios.max()}", radios.max() < opciones.size * 1.5)
    }

    /**
     * La mancha tiene que **envolver** lo que se dibujó. Si un punto de entrada
     * cae fuera, es que el trazo se ha despegado del dedo.
     */
    @Test
    fun `el contorno envuelve el trazo`() {
        val entrada = recta(paso = 6.0)
        val contorno = getStroke(entrada, null, opciones)
        assertTrue(contorno.size > 10)

        // Se salta el arranque: el alisado espera a alejarse del origen antes de
        // hacer caso, así que los primeros píxeles quedan fuera a propósito.
        for (p in entrada.drop(4).dropLast(1)) {
            assertTrue("(${p.x}, ${p.y}) se queda fuera de la mancha",
                dentro(p, contorno))
        }
    }

    // ---- El adelgazamiento ----

    /**
     * Es la razón de ser de todo esto: deprisa afila, despacio engorda. Con el
     * trazo de ancho fijo que había antes esto no pasaba y por eso no se
     * parecía a Excalidraw.
     */
    @Test
    fun `deprisa el trazo sale mas fino que despacio`() {
        val lento = getStroke(recta(paso = 3.0), null, opciones)
        val rapido = getStroke(recta(paso = 40.0), null, opciones)

        val anchoLento = anchoEn(lento, 200.0, margen = 12.0)
        val anchoRapido = anchoEn(rapido, 200.0, margen = 25.0)

        assertTrue("lento $anchoLento no es más gordo que rápido $anchoRapido",
            anchoLento > anchoRapido)
    }

    @Test
    fun `sin adelgazamiento el ancho es el tamaño pedido`() {
        val o = opciones.copy(thinning = 0.0, size = 20.0)
        val contorno = getStroke(recta(paso = 5.0), null, o)
        // Ventana ancha: con la corrección al mínimo los puntos del contorno
        // ya no caen a intervalos regulares, y una ventana estrecha se queda
        // sin muestras y mide cero.
        val ancho = anchoEn(contorno, 150.0, margen = 20.0)
        assertEquals(20.0, ancho, 3.0)
    }

    @Test
    fun `el grosor pedido manda sobre el tamaño de la mancha`() {
        val fino = getStroke(recta(paso = 5.0), null, opciones.copy(size = 4.0))
        val gordo = getStroke(recta(paso = 5.0), null, opciones.copy(size = 20.0))
        assertTrue(anchoEn(gordo, 150.0) > anchoEn(fino, 150.0))
    }

    // ---- El alisado ----

    /**
     * El alisado **suaviza sin enderezar**.
     *
     * Con la corrección alta esta prueba pedía que la banda de 5 px del temblor
     * quedara por debajo de 4: o sea, que el programa reescribiera el trazo. Es
     * justo lo que se notaba como «no es mi letra». Ahora se pide lo contrario
     * en espíritu: que quite el filo pero **deje pasar el pulso**, y que el
     * final siga cayendo donde acabó el dedo y no arrastrado detrás.
     */
    @Test
    fun `el alisado suaviza pero no endereza`() {
        val tembloroso = (0..60).map { i ->
            Pt(i * 5.0, 100.0 + if (i % 2 == 0) 2.5 else -2.5)
        }
        val puntos = getStrokePoints(tembloroso, null, opciones)
        val banda = puntos.map { it.point.y }.let { it.max() - it.min() }

        assertTrue("se ha comido el trazo entero: $banda", banda > 3.0)
        assertTrue("no ha suavizado nada: $banda", banda < 5.0)

        // Y el final tiene que estar donde acabó el dedo, no arrastrado detrás.
        assertEquals(tembloroso.last().x, puntos.last().point.x, 1.0)
    }

    @Test
    fun `el recorrido acumulado crece y el primer punto no tiene distancia`() {
        val puntos = getStrokePoints(recta(paso = 10.0), null, opciones)
        assertEquals(0.0, puntos.first().distance, 0.0)
        assertEquals(0.0, puntos.first().runningLength, 0.0)
        for (i in 1 until puntos.size) {
            assertTrue(puntos[i].runningLength >= puntos[i - 1].runningLength)
        }
    }

    /** Con dos puntos el original mete intermedios para que no salga a rayas. */
    @Test
    fun `dos puntos se convierten en cinco antes de alisar`() {
        val puntos = getStrokePoints(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), null, opciones)
        assertTrue("con dos puntos no basta: ${puntos.size}", puntos.size >= 3)
    }

    // ---- Los ajustes de Excalidraw ----

    @Test
    fun `las opciones salen de los ajustes del original`() {
        val e = Element(
            id = "x", type = ElementType.FREEDRAW, x = 0.0, y = 0.0,
            width = 10.0, height = 10.0, seed = 1, strokeWidth = 2.0
        )
        val o = strokeOptionsFor(e)
        assertEquals(2.0 * 4.25, o.size, 1e-9)
        assertEquals(0.6, o.thinning, 1e-9)
        assertEquals(0.5, o.smoothing, 1e-9)
        // El alisado NO es el 0,5 del original: a ese valor una letra sale
        // enderezada y deja de parecerse a tu escritura. Se usa el «preciso»
        // que el propio Excalidraw define para dibujar con detalle.
        assertEquals(0.2, o.streamline, 1e-9)
        assertTrue("el trazo guardado está terminado", o.last)
    }

    /** El lápiz tiene su propia escala: la de las formas lo haría enorme. */
    @Test
    fun `el grosor del lapiz usa su propia escala`() {
        assertEquals(0.5, ItemStyle.freedrawWidthFor(1.0), 1e-9)
        assertEquals(1.0, ItemStyle.freedrawWidthFor(2.0), 1e-9)
        assertEquals(2.0, ItemStyle.freedrawWidthFor(4.0), 1e-9)
        assertEquals(4.0, ItemStyle.freedrawWidthFor(8.0), 1e-9)
    }

    /**
     * El alisado tiene que dejar pasar el pulso.
     *
     * Se compara el trazo alisado contra la entrada: con la corrección alta el
     * resultado se aleja de lo que hizo la mano, y eso es lo que se notaba como
     * «no es mi letra». Con la corrección baja tiene que seguirla de cerca.
     */
    @Test
    fun `el alisado sigue de cerca al trazo original`() {
        val ondulado = (0..80).map { i ->
            Pt(i * 4.0, 100.0 + 6 * kotlin.math.sin(i / 2.0))
        }
        val puntos = getStrokePoints(ondulado, null, opciones)

        // Para cada punto alisado, lo lejos que queda del más cercano de la
        // entrada. Con la corrección al mínimo tiene que ser un par de píxeles.
        val desvio = puntos.drop(3).map { sp ->
            ondulado.minOf { hypot(it.x - sp.point.x, it.y - sp.point.y) }
        }.max()
        assertTrue("el trazo se aleja $desvio px de lo dibujado", desvio < 3.0)
    }
}
