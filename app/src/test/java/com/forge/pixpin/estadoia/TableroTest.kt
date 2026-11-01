package com.forge.pixpin.estadoia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las reglas del estado de las IA** (1-nov-2026): las pruebas de `pixpin_ui::estado_ia` del PC,
 * una a una, para que el teléfono y el PC digan lo mismo con los mismos códigos y tiempos.
 */
class TableroTest {
    private val claude = Servicio.CLAUDE
    private val deepseek = Servicio.DEEPSEEK
    private val jev = Servicio.JEV

    @Test fun `cada codigo lleva a su estado`() {
        fun e(c: Int) = Desenlace.Http(c).estado
        assertEquals(Estado.Funciona, e(200))
        assertEquals(Estado.Funciona, e(204))
        // De esa petición, no del servicio.
        assertEquals(Estado.Funciona, e(400))
        assertEquals(Estado.Funciona, e(413))
        assertEquals(Estado.Falla(Motivo.ClaveMala(401)), e(401))
        assertEquals(Estado.Falla(Motivo.ClaveMala(403)), e(403))
        assertEquals(Estado.Falla(Motivo.SinSaldo), e(402))
        assertEquals(Estado.Falla(Motivo.Limite), e(429))
        assertEquals(Estado.Falla(Motivo.Servidor(500)), e(500))
        assertEquals(Estado.Falla(Motivo.Servidor(529)), e(529))
        assertEquals(Estado.Falla(Motivo.Otro(404)), e(404))
        assertEquals(Estado.Falla(Motivo.Red), Desenlace.Red.estado)
        assertEquals(Desenlace.Red, Desenlace.deCodigo(null))
        assertEquals(Desenlace.Http(401), Desenlace.deCodigo(401))
        assertEquals(Desenlace.Http(Desenlace.CODIGO_MAXIMO), Desenlace.deCodigo(70_000))
    }

    @Test fun `los colores y las frases`() {
        assertEquals(Luz.VERDE, Estado.Funciona.luz)
        assertEquals(Luz.ROJA, Estado.Falla(Motivo.Red).luz)
        assertEquals(Luz.HUECA, Estado.SinClave.luz)
        assertEquals(Luz.GRIS, Estado.Desconocido.luz)
        assertEquals("ia-estado-clave-mala", Estado.Falla(Motivo.ClaveMala(401)).clave)
        assertEquals(401, Estado.Falla(Motivo.ClaveMala(401)).codigo)
        assertNull(Estado.Falla(Motivo.Limite).codigo)
        assertNull(Estado.Funciona.codigo)
    }

    @Test fun `sin clave no se comprueba nunca`() {
        val t = Tablero()
        for (s in Servicio.entries) {
            assertEquals(Estado.SinClave, t.estado(s))
            assertNull(t.cuandoToca(s, 0))
        }
        assertTrue(t.porComprobar(Long.MAX_VALUE, 0).isEmpty())
        assertNull(t.proxima(0))
        assertFalse(t.todoBien())
    }

    @Test fun `con clave se comprueba a los diez segundos de arrancar`() {
        val t = Tablero()
        assertTrue(t.ponerClave(claude, true))
        assertFalse("sin cambio", t.ponerClave(claude, true))
        assertEquals(Estado.Desconocido, t.estado(claude))
        val arranque = 5_000L
        assertTrue(t.porComprobar(arranque + 9_999, arranque).isEmpty())
        assertEquals(listOf(claude), t.porComprobar(arranque + ESPERA_INICIAL_MS, arranque))
        assertEquals(arranque + ESPERA_INICIAL_MS, t.proxima(arranque))
    }

    @Test fun `lo de verdad cuenta y aplaza la comprobacion`() {
        val t = Tablero()
        t.ponerClave(deepseek, true)
        // Una petición del asistente salió bien antes de la primera comprobación: ya no hace
        // falta mirar hasta dentro de media hora.
        t.apuntar(deepseek, Desenlace.Http(200), 3_000)
        assertEquals(Estado.Funciona, t.estado(deepseek))
        assertTrue(t.todoBien())
        assertTrue(t.porComprobar(20_000, 0).isEmpty())
        assertEquals(3_000 + CADA_MS, t.cuandoToca(deepseek, 0))
        // Y una que falla lo pone en rojo al momento.
        t.apuntar(deepseek, Desenlace.Http(401), 4_000)
        assertEquals(Luz.ROJA, t.estado(deepseek).luz)
        assertFalse(t.todoBien())
    }

    @Test fun `sin red se vuelve a mirar con espera creciente`() {
        assertEquals(60_000L, esperaSinRed(1))
        assertEquals(120_000L, esperaSinRed(2))
        assertEquals(240_000L, esperaSinRed(3))
        assertEquals(CADA_MS, esperaSinRed(10))
        assertEquals(CADA_MS, esperaSinRed(Int.MAX_VALUE))
        val t = Tablero()
        t.ponerClave(jev, true)
        t.empezar(jev, 10_000)
        assertNull("en marcha", t.cuandoToca(jev, 0))
        t.apuntar(jev, Desenlace.Red, 11_000)
        t.acabar(jev)
        assertEquals(11_000 + 60_000L, t.cuandoToca(jev, 0))
        t.empezar(jev, 71_000)
        t.apuntar(jev, Desenlace.Red, 72_000)
        t.acabar(jev)
        assertEquals(72_000 + 120_000L, t.cuandoToca(jev, 0))
        // Vuelve la red: la cuenta se pone a cero.
        t.apuntar(jev, Desenlace.Http(200), 200_000)
        assertEquals(200_000 + CADA_MS, t.cuandoToca(jev, 0))
    }

    @Test fun `una comprobacion que no contesto tampoco se repite enseguida`() {
        val t = Tablero()
        t.ponerClave(claude, true)
        t.empezar(claude, 10_000)
        t.acabar(claude)
        assertEquals(10_000 + CADA_MS, t.cuandoToca(claude, 0))
    }

    @Test fun `cambiar la clave olvida lo de antes y comprueba ya`() {
        val t = Tablero()
        t.ponerClave(claude, true)
        t.apuntar(claude, Desenlace.Http(401), 1_000)
        t.claveCambiada(claude, true)
        assertEquals(Estado.Desconocido, t.estado(claude))
        assertEquals(listOf(claude), t.porComprobar(1_001, 0))
        t.claveCambiada(claude, false)
        assertEquals(Estado.SinClave, t.estado(claude))
        assertTrue(t.porComprobar(Long.MAX_VALUE, 0).isEmpty())
    }

    @Test fun `comprobar ahora pide solo los que tienen clave`() {
        val t = Tablero()
        t.ponerClave(claude, true)
        t.ponerClave(jev, true)
        t.apuntar(claude, Desenlace.Http(200), 1_000)
        t.apuntar(jev, Desenlace.Http(200), 1_000)
        assertTrue(t.porComprobar(2_000, 0).isEmpty())
        t.pedirAhora()
        assertEquals(listOf(claude, jev), t.porComprobar(2_000, 0))
        assertEquals(0L, t.proxima(0))
    }

    @Test fun `quitar la clave del disco lo deja sin clave`() {
        val t = Tablero()
        t.ponerClave(claude, true)
        t.apuntar(claude, Desenlace.Http(200), 1_000)
        assertTrue(t.ponerClave(claude, false))
        assertEquals(Estado.SinClave, t.estado(claude))
        assertNull(t.ficha(claude).cuando)
    }

    @Test fun `hace cuanto`() {
        assertEquals("ia-estado-hace-nada" to 0L, hace(0, 59_999))
        assertEquals("ia-estado-hace-min" to 1L, hace(0, 60_000))
        assertEquals("ia-estado-hace-min" to 119L, hace(0, 119 * 60_000L))
        assertEquals("ia-estado-hace-horas" to 3L, hace(0, 3 * 3_600_000L))
        // Un reloj que no avanzó no da negativos.
        assertEquals("ia-estado-hace-nada" to 0L, hace(5_000, 1_000))
    }

    @Test fun `la copia no cambia con el tablero`() {
        val t = Tablero()
        t.ponerClave(claude, true)
        val c = t.copia()
        assertEquals(t, c)
        t.apuntar(claude, Desenlace.Http(200), 1_000)
        assertEquals(Estado.Desconocido, c.estado(claude))
        assertFalse(t == c)
    }
}
