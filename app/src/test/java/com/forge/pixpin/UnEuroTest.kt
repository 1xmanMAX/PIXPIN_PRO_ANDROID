package com.forge.pixpin.motor

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El filtro 1€ del puntero.
 *
 * Lo que se vigila es el trato que promete el filtro: que **arranca sin
 * transitorio** (la primera muestra sale tal cual), que en reposo **quita
 * temblor de verdad** y a toda velocidad **no deja la tinta atrás**, que los
 * sellos de tiempo del mundo real —repetidos, a trompicones, incluso hacia
 * atrás— no le rompen la cuenta, y que reiniciar borra el trazo entero, no
 * la mitad.
 */
class UnEuroTest {

    /** El periodo de una pantalla de 120 Hz, que es el ritmo que asume el filtro. */
    private val paso = 1.0 / 120.0

    private fun varianza(xs: List<Double>): Double {
        val media = xs.sum() / xs.size
        return xs.sumOf { (it - media) * (it - media) } / xs.size
    }

    // ---- Sin transitorio de arranque ----

    /** La primera muestra es identidad: la tinta nace donde cayó el dedo. */
    @Test
    fun `la primera muestra sale tal cual`() {
        assertEquals(3.7, UnEuro().filtrar(3.7, 0.0), 0.0)
    }

    @Test
    fun `el puntero tampoco tiene transitorio en ningun eje`() {
        val muestra = AlisadoDelPuntero().filtrar(10.0, 20.0, 0.5, 0L)
        assertEquals(10.0, muestra.x, 0.0)
        assertEquals(20.0, muestra.y, 0.0)
        assertEquals(0.5, muestra.presion, 0.0)
    }

    // ---- Los dos lados del trato ----

    /** Con la señal quieta no hay nada que alisar: la salida no puede derivar. */
    @Test
    fun `una señal constante sale constante`() {
        val filtro = UnEuro(120.0, corteMinimo = 1.0, beta = 0.5)
        for (i in 0 until 50) {
            assertEquals(7.5, filtro.filtrar(7.5, i * paso), 1e-9)
        }
    }

    /**
     * El lado del reposo: puntero casi quieto y ruido blanco encima. El corte
     * mínimo tiene que comerse la mayor parte —se pide varianza a menos de un
     * cuarto de la de entrada, y el filtro real la deja en centésimas—.
     */
    @Test
    fun `el ruido a velocidad baja se atenua fuerte`() {
        val filtro = UnEuro(120.0, corteMinimo = 1.0, beta = 0.0)
        val azar = Random(42)
        val entrada = mutableListOf<Double>()
        val salida = mutableListOf<Double>()
        for (i in 0 until 600) {
            val v = 100.0 + (azar.nextDouble() * 2.0 - 1.0)
            val s = filtro.filtrar(v, i * paso)
            // Las primeras muestras son el arranque; el reposo se mide después.
            if (i >= 100) {
                entrada += v
                salida += s
            }
        }
        val varEntrada = varianza(entrada)
        val varSalida = varianza(salida)
        assertTrue("la entrada tiene que traer ruido de verdad", varEntrada > 0.1)
        assertTrue(
            "deja pasar demasiado temblor: varianza $varSalida frente a $varEntrada",
            varSalida < varEntrada / 4.0
        )
    }

    /**
     * El lado de la velocidad: una rampa a 4000 px/s durante medio segundo.
     * Beta abre el corte y el retardo queda acotado: al acabar la rampa la
     * salida está a menos del 5% del recorrido. Con corte fijo (beta = 0) el
     * mismo trazo se quedaría a más del doble.
     */
    @Test
    fun `una rampa rapida no deja la tinta atras`() {
        val filtro = UnEuro(120.0, corteMinimo = 1.4, beta = 0.003)
        val recorrido = 2000.0
        val muestras = 60
        var ultima = 0.0
        for (i in 0..muestras) {
            ultima = filtro.filtrar(recorrido * i / muestras, i * paso)
        }
        // Un paso bajo nunca se pasa de una señal que solo sube.
        assertTrue("se pasó del objetivo: $ultima", ultima <= recorrido)
        assertTrue(
            "el retardo se come más del 5%: la tinta queda a ${recorrido - ultima} px",
            recorrido - ultima < recorrido * 0.05
        )
    }

    // ---- El tiempo del mundo real ----

    /**
     * Los eventos no llegan a ritmo de metrónomo: hay ráfagas, atascos, sellos
     * repetidos y hasta alguno que retrocede. Nada de eso puede acabar en una
     * división por cero ni en una salida que se escape del sobre de la señal.
     */
    @Test
    fun `los sellos de tiempo irregulares no rompen la cuenta`() {
        val filtro = UnEuro(120.0, corteMinimo = 1.4, beta = 0.003)
        val saltos = listOf(0.008, 0.001, 0.032, 0.0, 0.016, 0.05, -0.004, 0.008)
        var t = 1.0
        var v = 0.0
        for (i in 0 until 80) {
            t += saltos[i % saltos.size]
            v = minOf(100.0, v + 5.0)
            val s = filtro.filtrar(v, t)
            assertTrue("salida no finita en la muestra $i", s.isFinite())
            assertTrue("se escapa del sobre de la señal: $s", s in 0.0..100.0)
        }
    }

    // ---- Reiniciar ----

    /**
     * Reiniciar tiene que borrarlo TODO: valor, derivada, frecuencia medida e
     * instante. Si olvidara a medias, un filtro reiniciado y uno recién hecho
     * divergirían con la misma señal — se les da una vida anterior con otros
     * valores y otros sellos, y luego se les exige salida idéntica.
     */
    @Test
    fun `reiniciar deja el filtro como recien hecho`() {
        val usado = UnEuro(120.0, corteMinimo = 1.4, beta = 0.003)
        for (i in 0 until 40) {
            usado.filtrar(500.0 + i * 3.0, 100.0 + i * paso)
        }
        usado.reiniciar()

        val nuevo = UnEuro(120.0, corteMinimo = 1.4, beta = 0.003)
        val azar = Random(7)
        var t = 0.0
        for (i in 0 until 100) {
            t += 0.004 + azar.nextDouble() * 0.02
            val v = azar.nextDouble() * 50.0
            assertEquals("divergen en la muestra $i", nuevo.filtrar(v, t), usado.filtrar(v, t), 0.0)
        }
    }

    /** Tras reiniciar, la primera muestra del trazo nuevo vuelve a ser identidad. */
    @Test
    fun `el puntero reinicia sus tres ejes`() {
        val alisado = AlisadoDelPuntero()
        alisado.filtrar(0.0, 0.0, 0.1, 0L)
        alisado.filtrar(40.0, 40.0, 0.9, 8L)
        alisado.reiniciar()
        val muestra = alisado.filtrar(300.0, 200.0, 0.5, 5000L)
        assertEquals(300.0, muestra.x, 0.0)
        assertEquals(200.0, muestra.y, 0.0)
        assertEquals(0.5, muestra.presion, 0.0)
    }

    // ---- Un filtro por eje ----

    /**
     * La x quieta y la y a toda velocidad: si los ejes compartieran filtro o
     * corte, la velocidad de la y abriría el corte de la x y le dejaría pasar
     * ruido. Cada eje va por su cuenta, así que la x sale clavada.
     */
    @Test
    fun `cada eje va por su cuenta`() {
        val alisado = AlisadoDelPuntero()
        var muestra = MuestraAlisada(0.0, 0.0, 0.0)
        for (i in 0 until 30) {
            muestra = alisado.filtrar(50.0, i * 40.0, 0.5, (i * 8).toLong())
        }
        assertEquals(50.0, muestra.x, 1e-9)
        assertEquals(0.5, muestra.presion, 1e-9)
        assertTrue("la y tiene que haberse movido", muestra.y > 0.0)
    }
}
