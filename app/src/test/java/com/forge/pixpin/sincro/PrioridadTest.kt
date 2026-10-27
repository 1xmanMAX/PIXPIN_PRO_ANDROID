package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El orden de la vuelta, los reintentos y lo encendido por defecto** (27-oct-2026), puros. Lo de
 * verdad por el cable (una vuelta cortada que ya trajo las tareas) está en `DiarioDeCambiosTest`.
 */
class PrioridadTest {

    @Test
    fun `cada archivo en su nivel`() {
        assertEquals(Prioridad.Nivel.PESADO, Prioridad.nivelDe("guardados/clase_1.mp4"))
        assertEquals(Prioridad.Nivel.PESADO, Prioridad.nivelDe("guardados/voz.M4A"))
        assertEquals(Prioridad.Nivel.ARCHIVOS, Prioridad.nivelDe("guardados/informe (1).pdf"))
        assertEquals(Prioridad.Nivel.ARCHIVOS, Prioridad.nivelDe("guardados/foto.jpg"))
        assertEquals(Prioridad.Nivel.LIENZOS_Y_NOTAS, Prioridad.nivelDe("pins/draw/a.excalidraw.gz"))
        assertEquals(Prioridad.Nivel.LIENZOS_Y_NOTAS, Prioridad.nivelDe("tablas/t.json"))
        assertEquals(Prioridad.Nivel.LIENZOS_Y_NOTAS, Prioridad.nivelDe("notas/n.md"))
    }

    @Test
    fun `tareas, compartidos, archivos, lienzos y lo pesado al final - lo ligero primero en cada nivel`() {
        val piezas = listOf(
            Prioridad.Pieza("guardados/video.mp4", 50_000_000),
            Prioridad.Pieza("pins/draw/grande.excalidraw.gz", 900_000),
            Prioridad.Pieza("pins/draw/chico.excalidraw.gz", 1_000),
            Prioridad.Pieza("guardados/grande.pdf", 5_000_000),
            Prioridad.Pieza("guardados/chico.pdf", 10_000),
            Prioridad.Pieza("tablas/tareas.json", 2_000, deTareas = true),
            Prioridad.Pieza("guardados/de-ana.pdf", 3_000_000, compartido = true),
            Prioridad.Pieza("guardados/video-de-ana.mp4", 1_000, compartido = true)
        )
        val orden = Prioridad.ordenar(piezas) { it }.map { it.rel }
        assertEquals(
            listOf(
                "tablas/tareas.json", "guardados/de-ana.pdf", "guardados/chico.pdf", "guardados/grande.pdf",
                "pins/draw/chico.excalidraw.gz", "pins/draw/grande.excalidraw.gz",
                // Lo pesado es pesado aunque sea de un chat compartido; dentro, lo ligero primero.
                "guardados/video-de-ana.mp4", "guardados/video.mp4"
            ),
            orden
        )
    }

    @Test
    fun `una grabacion va junta, pesada entera y por lo que pesa toda`() {
        val piezas = listOf(
            // Clase A: 10 MB en total (voz, capturas y vídeo).
            Prioridad.Pieza("guardados/a_voz.m4a", 2_000_000, grupo = "A"),
            Prioridad.Pieza("guardados/a_cap1.png", 100_000, grupo = "A"),
            Prioridad.Pieza("guardados/a_video.mp4", 7_900_000, grupo = "A"),
            // Clase B: 3 MB.
            Prioridad.Pieza("guardados/b_voz.m4a", 2_900_000, grupo = "B"),
            Prioridad.Pieza("guardados/b_cap1.png", 100_000, grupo = "B"),
            // Un vídeo suelto de 5 MB: entre las dos.
            Prioridad.Pieza("guardados/suelto.mp4", 5_000_000),
            Prioridad.Pieza("guardados/doc.pdf", 50_000)
        )
        val orden = Prioridad.ordenar(piezas) { it }.map { it.rel }
        assertEquals(
            listOf(
                "guardados/doc.pdf",
                "guardados/b_cap1.png", "guardados/b_voz.m4a",
                "guardados/suelto.mp4",
                "guardados/a_cap1.png", "guardados/a_voz.m4a", "guardados/a_video.mp4"
            ),
            orden
        )
    }

    @Test
    fun `los chats - los de tareas, los compartidos y luego del mas ligero al mas pesado`() {
        val peso = mapOf("general" to 500L, "obra" to 20L, "casa" to 5L, "ana" to 900L, "tareas" to 1000L)
        assertEquals(
            listOf("tareas", "ana", "casa", "obra", "general"),
            Prioridad.ordenarChats(listOf("general", "obra", "casa", "ana", "tareas"), setOf("tareas"), setOf("ana")) { peso.getValue(it) }
        )
    }

    @Test
    fun `reintentos - ocupado enseguida y con azar, un fallo cada vez mas tarde hasta el tope`() {
        var azar = 0.5
        val r = Reintentos { azar }
        val ocupado = r.fallo("pc", 0, ocupado = true)
        assertTrue(ocupado in Reintentos.OCUPADO_MIN..(Reintentos.OCUPADO_MIN + Reintentos.OCUPADO_AZAR))
        assertEquals("ocupado no cuenta como fallo", 0, r.seguidos("pc"))
        val esperas = (1..10).map { r.fallo("pc", 0, ocupado = false) }
        assertEquals(Reintentos.BASE, esperas[0])
        assertEquals(2 * Reintentos.BASE, esperas[1])
        assertEquals(Reintentos.TOPE, esperas.last())
        assertTrue(r.tocan(Reintentos.TOPE - 1, listOf("pc")).isEmpty())
        assertEquals(setOf("pc"), r.tocan(Reintentos.TOPE, listOf("pc", "tableta")))
        // Dos que chocan no vuelven a chocar al mismo compás.
        azar = 0.0; val a = Reintentos { azar }.fallo("x", 0, true)
        azar = 0.99; val b = Reintentos { azar }.fallo("x", 0, true)
        assertTrue(b - a > 2_000)
        r.bien("pc")
        assertTrue(r.tocan(Long.MAX_VALUE, listOf("pc")).isEmpty())
    }

    @Test
    fun `automatica por defecto - encendida salvo que el usuario la apagara`() {
        assertTrue(SincronizacionAutomatica.encendidaSegun(null))
        assertTrue(SincronizacionAutomatica.encendidaSegun(true))
        assertFalse(SincronizacionAutomatica.encendidaSegun(false))
    }

    @Test
    fun `cursores - se mira todo cada tantas vueltas o cada dia, nunca si el reloj va hacia atras`() {
        val hoy = 10L * Diario.Cursores.VERIFICAR_CADA_MS
        assertFalse(Diario.Cursores(verificado = hoy, vueltas = 3).tocaVerificar(hoy + 1000))
        assertTrue(Diario.Cursores(verificado = hoy, vueltas = Diario.Cursores.VUELTAS_POR_VERIFICACION).tocaVerificar(hoy))
        assertTrue(Diario.Cursores(verificado = hoy).tocaVerificar(hoy + Diario.Cursores.VERIFICAR_CADA_MS))
        assertTrue("el reloj cambiado a mano", Diario.Cursores(verificado = hoy).tocaVerificar(hoy - 1))
    }
}
