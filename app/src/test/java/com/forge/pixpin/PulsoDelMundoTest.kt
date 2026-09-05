package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El pulso del trazo, medido en el mundo.
 *
 * Lo que se vigila es lo que hace que un trazo parezca de mano y no de regla:
 * que **lo rápido salga fino y lo lento gordo** —con reloj y sin él—, que las
 * dos puntas se afilen, y que la cuenta sea **invariante a escala**: el mismo
 * gesto a otro aumento tiene que dejar el mismo pulso. Y lo de siempre en una
 * cuenta que pinta: de aquí no puede salir jamás un NaN ni un ancho nulo,
 * porque eso no falla con un error — falla dejando un agujero en el trazo.
 */
class PulsoDelMundoTest {

    private val calibre = 4.0

    /** El recorrido acumulado que sale de andar estos huecos, empezando en cero. */
    private fun recorridoDe(vararg huecos: Double): DoubleArray {
        val r = DoubleArray(huecos.size + 1)
        for (i in huecos.indices) r[i + 1] = r[i] + huecos[i]
        return r
    }

    /** Tantos huecos iguales. */
    private fun parejos(cuantos: Int, hueco: Double = 1.0): DoubleArray =
        recorridoDe(*DoubleArray(cuantos) { hueco })

    /** Ni un NaN, ni un ancho nulo o negativo, ni más gordo que el calibre. */
    private fun sano(anchos: DoubleArray) {
        for (a in anchos) {
            assertFalse("salió un NaN", a.isNaN())
            assertTrue("salió un ancho de $a", a > 0.0)
            assertTrue("salió más gordo que el calibre: $a", a <= calibre + 1e-9)
        }
    }

    // ---- Sin variar nada, no pasa nada ----

    /**
     * Velocidad pareja y sin presión que escuchar: el cuerpo del trazo sale
     * **clavado al calibre**. Es la línea de base de todo lo demás: si esto se
     * moviera, el pulso estaría inventando.
     */
    @Test
    fun `parejo por dentro sale al calibre`() {
        val anchos = anchosDelMundo(calibre, null, parejos(19), null)
        // Del 5 al n-6: fuera quedan las tres puntas afiladas y las dos muestras a
        // las que el alisado de cinco les arrima el hombro.
        for (i in 5..anchos.size - 6) assertEquals(calibre, anchos[i], 1e-12)
        sano(anchos)
    }

    /**
     * La presión que llega toda igual es el dedo, o un lápiz que no la mide:
     * no se le hace caso. Si se le hiciera, todo trazo de dedo saldría
     * adelgazado por una constante que no ha medido nadie.
     */
    @Test
    fun `la presion constante no adelgaza`() {
        val pres = DoubleArray(24) { 0.4 }
        val anchos = anchosDelMundo(calibre, pres, parejos(23), null)
        assertEquals(calibre, anchos[10], 1e-12)
    }

    // ---- Lo rápido sale fino ----

    /**
     * Sin reloj, la velocidad se lee en los huecos: catorce huecos al paso y
     * nueve al triple. El tramo rápido tiene que salir más fino, y el lento,
     * al calibre — la mediana es el paso del trazo porque los lentos son más.
     */
    @Test
    fun `sin reloj el tramo rapido sale mas fino`() {
        val huecos = DoubleArray(23) { if (it < 14) 1.0 else 3.0 }
        val anchos = anchosDelMundo(calibre, null, recorridoDe(*huecos), null)
        assertTrue("rápido ${anchos[18]} no es más fino que lento ${anchos[7]}",
            anchos[18] < anchos[7])
        assertEquals(calibre, anchos[7], 1e-12)
        sano(anchos)
    }

    /**
     * Con reloj, la velocidad es la de verdad: aquí las muestras quedaron
     * **igual de separadas en el mundo** y solo el reloj sabe que la segunda
     * mitad fue cinco veces más deprisa. Sin los tiempos, este mismo trazo
     * sale parejo — que es justo lo que el reloj viene a arreglar.
     */
    @Test
    fun `con reloj manda el reloj y no los huecos`() {
        val recorrido = parejos(23)
        val tiempos = DoubleArray(24)
        for (i in 1 until 24) tiempos[i] = tiempos[i - 1] + if (i <= 14) 0.1 else 0.02
        val conReloj = anchosDelMundo(calibre, null, recorrido, tiempos)
        assertTrue("rápido ${conReloj[18]} no es más fino que lento ${conReloj[7]}",
            conReloj[18] < conReloj[7])
        val sinReloj = anchosDelMundo(calibre, null, recorrido, null)
        assertEquals("sin reloj estos huecos parejos no tienen pulso que dar",
            sinReloj[7], sinReloj[18], 1e-12)
        sano(conReloj)
    }

    // ---- Apretar engorda ----

    /** Media pasada floja y media fuerte: donde se apoyó la mano sale más gordo. */
    @Test
    fun `apretar mas deja mas gordo`() {
        val pres = DoubleArray(24) { if (it < 12) 0.3 else 0.9 }
        val anchos = anchosDelMundo(calibre, pres, parejos(23), null)
        assertTrue("flojo ${anchos[6]} no es más fino que fuerte ${anchos[18]}",
            anchos[6] < anchos[18])
        sano(anchos)
    }

    // ---- Las puntas ----

    /**
     * El trazo nace fino y muere fino: las primeras muestras **crecen una a
     * una** hasta el cuerpo, y las últimas bajan igual. Un trazo que empieza a
     * todo ancho acaba en un tajo recto, y eso no lo hace ninguna mano.
     */
    @Test
    fun `las dos puntas se afilan en escalera`() {
        val anchos = anchosDelMundo(calibre, null, parejos(19), null)
        val n = anchos.size
        for (i in 0 until 3) {
            assertTrue("la punta de salida no crece en $i",
                anchos[i] < anchos[i + 1])
            assertTrue("la punta de llegada no decrece en ${n - 1 - i}",
                anchos[n - 1 - i] < anchos[n - 2 - i])
        }
        assertTrue("la mismísima punta no está afilada", anchos[0] < calibre)
        // Y las dos puntas son la misma: posar y levantar se afilan igual.
        for (i in 0 until 4) assertEquals(anchos[i], anchos[n - 1 - i], 1e-12)
    }

    // ---- Invariante a escala ----

    /**
     * El mismo gesto dibujado mil veces más grande —otro aumento, otra unidad
     * de mundo— deja **exactamente el mismo pulso**: la velocidad se compara
     * con la mediana del propio trazo, así que la escala se va de la cuenta.
     * Es lo que la versión de pantalla no podía dar, y el porqué de esta.
     */
    @Test
    fun `mil veces mas grande deja los mismos anchos`() {
        val huecos = doubleArrayOf(1.0, 2.5, 0.5, 3.0, 1.0, 4.0, 0.75, 2.0, 1.5, 1.0, 3.5, 0.5)
        val pres = doubleArrayOf(0.2, 0.9, 0.5, 1.0, 0.3, 0.7, 0.6, 0.8, 0.4, 1.0, 0.25, 0.65, 0.55)
        val chico = recorridoDe(*huecos)
        val grande = DoubleArray(chico.size) { chico[it] * 1000.0 }
        val a = anchosDelMundo(calibre, pres, chico, null)
        val b = anchosDelMundo(calibre, pres, grande, null)
        for (i in a.indices) assertEquals("difieren en $i", a[i], b[i], 1e-9)
    }

    // ---- Los trazos que no tienen pulso ----

    /** Un punto es un punto: el calibre por lo que apretó, y ya. */
    @Test
    fun `una sola muestra sale al calibre por su presion`() {
        val solo = anchosDelMundo(calibre, doubleArrayOf(0.5), doubleArrayOf(0.0), null)
        assertEquals(1, solo.size)
        assertEquals(calibre * 0.5, solo[0], 1e-12)
        assertEquals(calibre, anchosDelMundo(calibre, null, doubleArrayOf(0.0), null)[0], 1e-12)
    }

    /**
     * Una raya de dos muestras sale de ancho **parejo**: afilarle las puntas
     * la convertiría en una lenteja, y una arista tiene que ser una arista.
     * La presión media sí se conserva: apretar la mitad sigue siendo la mitad.
     */
    @Test
    fun `dos muestras salen parejas`() {
        val raya = anchosDelMundo(
            calibre, doubleArrayOf(0.4, 0.8),
            doubleArrayOf(0.0, 7.0), doubleArrayOf(0.0, 0.1)
        )
        assertEquals(2, raya.size)
        assertEquals(calibre * 0.6, raya[0], 1e-12)
        assertEquals(raya[0], raya[1], 1e-12)
    }

    @Test
    fun `sin muestras no hay anchos`() {
        assertEquals(0, anchosDelMundo(calibre, null, DoubleArray(0), null).size)
    }

    // ---- El cinturón: de aquí no sale un agujero ----

    /**
     * Un reloj que se repite o va hacia atrás —pasa al coser trazos y al
     * importar— no es una velocidad infinita: ese hueco cuenta como repetido.
     */
    @Test
    fun `el reloj parado o hacia atras no rompe`() {
        val tiempos = doubleArrayOf(0.0, 0.1, 0.1, 0.05, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
        sano(anchosDelMundo(calibre, null, parejos(9), tiempos))
    }

    /**
     * El dedo clavado en el sitio deja huecos de cero y una mediana de cero:
     * no hay paso normal con el que comparar, y aun así todos los anchos
     * tienen que salir sanos — la única muestra que se movió, más fina.
     */
    @Test
    fun `el dedo clavado no divide por cero`() {
        val quieto = recorridoDe(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 0.0, 0.0)
        val anchos = anchosDelMundo(calibre, null, quieto, null)
        sano(anchos)
        assertTrue("la muestra que corrió no salió más fina", anchos[6] < anchos[4])
    }

    /**
     * **Las rachas no dan saltos.** El paso por los huecos de pantalla mete
     * varias muestras con el mismo instante y luego una con todo el tiempo del
     * toque; muestra a muestra eso era velocidad infinita y luego casi cero, y
     * el rotulador salía a bandas. Medida en ventana, una mano que va a paso
     * constante sale al calibre aunque el reloj llegue a rachas.
     */
    @Test
    fun `las muestras que llegan a rachas no dan saltos`() {
        // Cuatro muestras por toque, todas con el instante del toque, cada 16 ms.
        val n = 40
        val tiempos = DoubleArray(n) { (it / 4) * 0.016 }
        val anchos = anchosDelMundo(calibre, null, parejos(n - 1), tiempos)
        sano(anchos)
        for (i in 6..n - 7) assertEquals("salto en $i", calibre, anchos[i], calibre * 0.02)
    }

    /** Presiones en los extremos, rotas y de menos: nada de eso puede romper. */
    @Test
    fun `las presiones raras no rompen`() {
        val recorrido = parejos(11)
        sano(anchosDelMundo(calibre, DoubleArray(12) { if (it % 2 == 0) 0.0 else 1.0 }, recorrido, null))
        sano(anchosDelMundo(calibre, doubleArrayOf(0.5, Double.NaN, 0.9, 0.2, 0.7, 0.4, 0.6, 0.3, 0.8, 0.5, 0.6, 0.7), recorrido, null))
        // Menos presiones que muestras: la última vale para las que faltan.
        sano(anchosDelMundo(calibre, doubleArrayOf(0.3, 0.9), recorrido, null))
    }
}
