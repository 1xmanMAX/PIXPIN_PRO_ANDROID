package com.forge.pixpin.sincro

import com.forge.pixpin.sincro.TarjetasDeAparatos.Punto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Las tarjetas de tus aparatos** (27-oct-2026): qué dice el punto de cada una y en qué orden y
 * filas salen.
 */
class TarjetasDeAparatosTest {

    @Test fun elPuntoEsVerdeSiSeLeVeYGrisSiNo() {
        assertEquals(Punto.CERCA, TarjetasDeAparatos.punto(cerca = true, sincronizando = false, fallo = false))
        assertEquals(Punto.LEJOS, TarjetasDeAparatos.punto(cerca = false, sincronizando = false, fallo = false))
    }

    @Test fun loQuePasaAhoraGanaALoQuePaso() {
        // Sincronizando gana a todo, también a un fallo anterior y a no verse.
        assertEquals(Punto.SINCRONIZANDO, TarjetasDeAparatos.punto(cerca = false, sincronizando = true, fallo = true))
        assertEquals(Punto.SINCRONIZANDO, TarjetasDeAparatos.punto(cerca = true, sincronizando = true, fallo = false))
        // El fallo se ve aunque ahora conteste, hasta que se vuelva a probar.
        assertEquals(Punto.FALLO, TarjetasDeAparatos.punto(cerca = true, sincronizando = false, fallo = true))
        assertEquals(Punto.FALLO, TarjetasDeAparatos.punto(cerca = false, sincronizando = false, fallo = true))
    }

    private data class A(val nombre: String, val cerca: Boolean)

    @Test fun losQueSeVenVanPrimeroYLuegoPorNombre() {
        val lista = listOf(A("tableta", false), A("Z", true), A("portátil", true), A("Avión", false))
        val orden = TarjetasDeAparatos.ordenar(lista, { it.cerca }, { it.nombre }).map { it.nombre }
        assertEquals(listOf("portátil", "Z", "Avión", "tableta"), orden)
    }

    @Test fun deDosEnDosConLaUltimaFilaAMedias() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), TarjetasDeAparatos.enFilas(listOf(1, 2, 3, 4, 5)))
        assertEquals(emptyList<List<Int>>(), TarjetasDeAparatos.enFilas(emptyList<Int>()))
        // Caso negativo: cero columnas no revienta, va de una en una.
        assertEquals(listOf(listOf(1), listOf(2)), TarjetasDeAparatos.enFilas(listOf(1, 2), columnas = 0))
    }
}
