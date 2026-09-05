package com.forge.pixpin.croquis3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **El acelerómetro, integrado con freno.** Ver [InerciaDelTelefono]. */
class InerciaDelTelefonoTest {

    private val paso = 10_000_000L // diez milisegundos

    @Test
    fun `la primera muestra solo pone el reloj`() {
        val i = InerciaDelTelefono()
        assertNull(i.muestra(1.0, 0.0, 0.0, paso))
    }

    /** Un empujón hacia delante mueve hacia delante, y luego se frena solo. */
    @Test
    fun `un empujon se nota y se frena solo`() {
        val i = InerciaDelTelefono()
        var t = paso
        i.muestra(0.0, 0.0, 0.0, t)
        var recorrido = 0.0
        // Un cuarto de segundo empujando a 2 m/s² hacia el norte.
        repeat(25) { t += paso; recorrido += i.muestra(0.0, 2.0, 0.0, t)!!.y }
        assertTrue("tenía que avanzar: $recorrido", recorrido > 0.03)
        val v = i.velocidad.y
        assertTrue(v > 0.2)
        // Y sin empujar, con ruido pequeño, la velocidad se apaga.
        repeat(100) { t += paso; recorrido += i.muestra(0.0, 0.05, 0.0, t)!!.y }
        assertTrue("no se paró: ${i.velocidad.y}", i.velocidad.y < 0.02)
        // Y el recorrido no se dispara: es un vaivén, no una deriva.
        assertTrue("se fue de largo: $recorrido", recorrido < 0.5)
    }

    /** Quieto de verdad, la velocidad se cancela de golpe (ZUPT). */
    @Test
    fun `quieto un rato cancela la velocidad`() {
        val i = InerciaDelTelefono()
        var t = paso
        i.muestra(0.0, 0.0, 0.0, t)
        repeat(10) { t += paso; i.muestra(3.0, 0.0, 0.0, t) }
        assertTrue(i.velocidad.x > 0.1)
        repeat(30) { t += paso; i.muestra(0.0, 0.0, 0.0, t) }
        assertEquals(0.0, i.velocidad.x, 1e-9)
    }

    /** La velocidad tiene tope: un dato disparatado no manda el croquis a la luna. */
    @Test
    fun `la velocidad no se dispara`() {
        val i = InerciaDelTelefono()
        var t = paso
        i.muestra(0.0, 0.0, 0.0, t)
        repeat(50) { t += paso; i.muestra(0.0, 0.0, 100.0, t) }
        assertTrue(largo(i.velocidad) <= InerciaDelTelefono.VELOCIDAD_MAXIMA + 1e-9)
    }

    /** Un salto del reloj hacia atrás no revienta ni integra tiempos negativos. */
    @Test
    fun `un reloj que va hacia atras se ignora`() {
        val i = InerciaDelTelefono()
        i.muestra(0.0, 0.0, 0.0, 5 * paso)
        assertNull(i.muestra(1.0, 0.0, 0.0, 2 * paso))
    }
}
