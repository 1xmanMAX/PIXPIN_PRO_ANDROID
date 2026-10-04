package com.forge.pixpin.sincro

import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 4-oct-2026, encontrado sincronizando con el PC de verdad: con el desfase entre relojes, el nombre
 * de un proyecto lo ganaba el lado que dijera el signo del desfase, no el más reciente. El nombre
 * provisional (el id) de un proyecto recién llegado se quedaba. Igual en el PC (`mezcla.rs`).
 */
class MezclaConDesfaseTest {
    @Test
    fun `sin base y con desfase el nombre lo gana el mas reciente`() {
        val provisional = Proyecto(id = "pr-movil", nombre = "pr-movil", tocado = 0)
        val deVerdad = Proyecto(id = "pr-movil", nombre = "Reforma", tocado = 1_791_000_000_000,
            hojas = listOf(Hoja(id = "h1", nombre = "Planta", dibujo = "d1")))
        for (desfase in listOf(-3L, 0L, 3L, 5_000L)) {
            assertEquals("desfase $desfase", "Reforma", Mezcla.proyecto(provisional, deVerdad, null, desfase = desfase)!!.nombre)
            assertEquals("desfase $desfase, al revés", "Reforma", Mezcla.proyecto(deVerdad, provisional, null, desfase = desfase)!!.nombre)
        }
    }
}
