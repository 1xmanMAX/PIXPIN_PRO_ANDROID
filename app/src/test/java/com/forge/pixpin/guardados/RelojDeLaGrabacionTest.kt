package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **El reloj de pared en la nota** (`resto.reloj`, 28-oct-2026): la forma exacta del PC
 * (`RelojDeGrabacion` de `por_su_hora.rs`, `4fec53d3`), las claves en orden alfabético y sin lo vacío,
 * y la cuenta de `ms_hasta` con sus pausas y huecos.
 */
class RelojDeLaGrabacionTest {

    @Test
    fun `va en la nota como lo escribe el PC`() {
        val r = RelojDePared(
            aparato = "K7Q2", inicio = 1_793_178_000_000, fin = 1_793_181_600_000,
            pausas = listOf(listOf(1_793_179_000_000, 1_793_179_300_000))
        )
        // El ejemplo de `docs/movil-como-camara.md` §12.1, sin `vistoEn` 0 (no se escribe).
        assertEquals(
            """{"aparato":"K7Q2","fin":1793181600000,"inicio":1793178000000,"pausas":[[1793179000000,1793179300000]]}""",
            RelojDeLaGrabacion.enJson(r).toString()
        )
        // Con todo: huecos, el id de la grabación y una pausa abierta (hasta 0).
        val todo = RelojDePared("K7Q2", 10, 0, listOf(listOf(20, 0)), 99, listOf(listOf(30, 40)), "g-1")
        assertEquals(
            """{"aparato":"K7Q2","grabacion":"g-1","huecos":[[30,40]],"inicio":10,"pausas":[[20,0]],"vistoEn":99}""",
            RelojDeLaGrabacion.enJson(todo).toString()
        )
        // De ida y vuelta, también leído de un mensaje del PC.
        assertEquals(todo, RelojDePared.de(Json.parseToJsonElement(RelojDeLaGrabacion.enJson(todo).toString()).jsonObject))
        assertEquals(r, RelojDePared.de(RelojDeLaGrabacion.enJson(r)))
        // Caso negativo: sin inicio no hay reloj.
        assertNull(RelojDePared.de(Json.parseToJsonElement("""{"aparato":"X"}""").jsonObject))
        assertNull(RelojDePared.de(null))
    }

    @Test
    fun `el del diario pasa sus pausas y huecos y se corta en el ultimo latido`() {
        val d = GrabacionAMedias.Diario.nuevo(1_000, "p", "Clase", "a")
        d.latir(61_000, 60_000, true)
        d.latir(121_000, 60_000, false)
        val r = RelojDeLaGrabacion.delDiario(d.reloj, "K7Q2", d.latido.utc)
        assertEquals(listOf(listOf(61_000L, 121_000L)), r.pausas)
        assertEquals(121_000L, r.vistoEn)
        assertEquals(0L, r.fin)
        // Cortada: la ventana acaba en lo último visto.
        assertEquals(121_000L, RelojDeLaGrabacion.final(r))
        assertEquals(60_000L, RelojDeLaGrabacion.msHasta(r, 10_000_000))
        // Acabada: `fin` y sin `vistoEn`.
        d.acabar(181_000)
        val a = RelojDeLaGrabacion.delDiario(d.reloj, "K7Q2", d.latido.utc)
        assertEquals(181_000L, a.fin)
        assertEquals(0L, a.vistoEn)
        assertEquals(120_000L, RelojDeLaGrabacion.msHasta(a, 181_000))
        // Antes de empezar, 0; sin final, corre.
        assertEquals(0L, RelojDeLaGrabacion.msHasta(RelojDePared(inicio = 1_000), 500))
        assertEquals(4_000L, RelojDeLaGrabacion.msHasta(RelojDePared(inicio = 1_000), 5_000))
    }
}
