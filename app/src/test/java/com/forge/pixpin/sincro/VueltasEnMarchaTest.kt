package com.forge.pixpin.sincro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * **El botón de sincronizar gira y se pone rojo** (25-oct-2026, PC `0470294`): la cuenta de las
 * vueltas en marcha y cómo acabó la última.
 */
class VueltasEnMarchaTest {

    @Test fun giraMientrasHayAlgunaVueltaYSeParaAlAcabarLaUltima() {
        val c = VueltasEnMarcha.Cuenta()
        assertFalse(c.gira)
        c.empezar(); c.empezar()
        assertTrue(c.gira)
        c.acabar()
        assertTrue("queda otra", c.gira)
        c.acabar()
        assertFalse(c.gira)
        // Caso negativo: acabar de más no deja la cuenta por debajo de cero.
        c.acabar()
        c.empezar()
        assertTrue(c.gira)
    }

    @Test fun elFalloSeVeHastaQueSePulsaOEmpiezaOtra() {
        val c = VueltasEnMarcha.Cuenta()
        c.empezar(); c.apuntar(false); c.acabar()
        assertTrue(c.fallo)
        c.olvidarFallo()
        assertFalse("pulsar el botón lo quita", c.fallo)
        c.empezar(); c.apuntar(false); c.acabar()
        c.empezar()
        assertFalse("una vuelta nueva tapa el fallo de la anterior", c.fallo)
        c.apuntar(true); c.acabar()
        assertFalse(c.fallo)
        // Una que empieza con otra en marcha no tapa nada: la ronda entera es una.
        c.empezar(); c.apuntar(false); c.empezar()
        assertTrue(c.fallo)
    }

    @Test fun ocupadoYCancelarNoSonFallos() {
        assertFalse(VueltasEnMarcha.fallo(java.io.IOException(Protocolo.OCUPADO)))
        assertFalse(VueltasEnMarcha.fallo(kotlinx.coroutines.CancellationException("cancelada")))
        assertTrue(VueltasEnMarcha.fallo(java.net.ConnectException("no contesta")))
    }

    @Test fun ocuparYSoltarCuentanComoVueltaEnMarcha() {
        val d = Disco(Files.createTempDirectory("vueltas").toFile())
        try {
            assertTrue(Protocolo.ocupar(d))
            assertTrue(VueltasEnMarcha.estado.value.gira)
            // Ocupado dos veces no cuenta dos: soltar una vez lo deja quieto.
            assertFalse(Protocolo.ocupar(d))
            Protocolo.soltar(d)
            Protocolo.soltar(d)
            assertFalse(VueltasEnMarcha.estado.value.gira)
        } finally {
            d.filesDir.deleteRecursively()
        }
    }
}
