package com.forge.pixpin

import com.forge.pixpin.sincro.RelojDeSincro
import com.forge.pixpin.sincro.RelojDeSincro.Companion.PERIODO
import com.forge.pixpin.sincro.RelojDeSincro.Companion.QUIETO
import com.forge.pixpin.sincro.RelojDeSincro.Motivo
import com.forge.pixpin.sincro.SincronizacionAutomatica
import com.forge.pixpin.sincro.ColaDeTareas
import com.forge.pixpin.sincro.ColaDeTareas.Companion.CALMA
import com.forge.pixpin.sincro.ColaDeTareas.Companion.TOPE
import com.forge.pixpin.sincro.Disco
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cuándo sincroniza solo (las pruebas de `sincronizar/automatica.rs` del PC, con el reloj de aquí). */
class RelojDeSincroTest {
    private fun firma(n: Long) = RelojDeSincro.Firma(n, n, n)
    private val tel = setOf("tel")

    @Test fun `sin nadie que conteste no se sincroniza nunca`() {
        val r = RelojDeSincro()
        assertNull(r.toca(0, emptySet(), firma(1)))
        assertNull(r.toca(1_000, emptySet(), firma(2)))
        assertNull(r.toca(PERIODO * 5, emptySet(), firma(2)))
    }

    @Test fun `al aparecer un aparato se sincroniza enseguida y solo una vez`() {
        val r = RelojDeSincro()
        assertEquals(Motivo.APARECIO to tel, r.toca(1_000, tel, firma(1)))
        r.despuesDeSincronizar(firma(1))
        assertNull("sigue contestando: no vuelve a aparecer", r.toca(3_000, tel, firma(1)))
        assertNull(r.toca(4_000, emptySet(), firma(1)))
        assertEquals("se va y vuelve: otra vez", Motivo.APARECIO, r.toca(5_000, tel, firma(1))?.first)
    }

    @Test fun `un cambio aqui espera treinta segundos quieto`() {
        val r = RelojDeSincro()
        r.toca(0, tel, firma(1)); r.despuesDeSincronizar(firma(1))
        assertNull("aún escribiendo", r.toca(1_000, tel, firma(2)))
        assertNull(r.toca(1_000 + QUIETO - 1, tel, firma(2)))
        // Sigue cambiando: el reloj vuelve a empezar.
        assertNull(r.toca(20_000, tel, firma(3)))
        assertNull(r.toca(1_000 + QUIETO + 1, tel, firma(3)))
        assertEquals(Motivo.CAMBIO, r.toca(20_000 + QUIETO, tel, firma(3))?.first)
        // Lo que escribe la propia vuelta no es un cambio.
        r.despuesDeSincronizar(firma(9))
        assertNull(r.toca(20_000 + QUIETO * 3, tel, firma(9)))
    }

    @Test fun `cada diez minutos aunque no cambie nada`() {
        val r = RelojDeSincro()
        assertEquals(Motivo.APARECIO, r.toca(0, tel, firma(1))?.first)
        r.despuesDeSincronizar(firma(1))
        assertNull(r.toca(PERIODO - 1, tel, firma(1)))
        assertEquals(Motivo.PERIODO, r.toca(PERIODO, tel, firma(1))?.first)
    }

    @Test fun `la firma cambia al escribir un archivo de lo que viaja y no con la cache`() {
        val d = Files.createTempDirectory("pp-firma").toFile()
        try {
            File(d, "pins/draw").mkdirs(); File(d, "cache").mkdirs()
            val a = SincronizacionAutomatica.firma(d)
            File(d, "pins/draw/x.excalidraw").writeText("hola")
            val b = SincronizacionAutomatica.firma(d)
            assertNotEquals(a, b)
            assertEquals(a.ficheros + 1, b.ficheros)
            File(d, "cache/y.tmp").writeText("nada")
            assertEquals(b, SincronizacionAutomatica.firma(d))
        } finally { d.deleteRecursively() }
    }

    // --------------------------- tareas al instante (las de `automatica.rs` del PC, 11-oct-2026)

    @Test fun `una tarea tachada sale en cuanto se calma y no antes`() {
        val c = ColaDeTareas()
        assertEquals(ColaDeTareas.Paso.NADA, c.siguiente(1_000, false, true))
        c.avisan("obra", 1_000)
        assertEquals(CALMA, c.espera(1_000))
        assertEquals("aún tachando", ColaDeTareas.Paso.NADA, c.siguiente(1_000 + CALMA - 1, false, true))
        assertEquals(ColaDeTareas.Paso.MANDAR, c.siguiente(1_000 + CALMA, false, true))
        assertEquals(setOf("obra"), c.tomar())
        assertEquals("caso negativo: ya salió", ColaDeTareas.Paso.NADA, c.siguiente(1_000 + CALMA, false, true))
        assertNull(c.espera(5_000))
    }

    @Test fun `varias casillas seguidas van en una vuelta y el tope no deja esperar de mas`() {
        val c = ColaDeTareas()
        // Un toque cada 400 ms: la calma no llega nunca, el tope sí.
        for ((i, t) in listOf(0L, 400L, 800L, 1_200L).withIndex()) {
            c.avisan(if (i % 2 == 0) "obra" else "general", 10_000 + t)
            assertEquals(ColaDeTareas.Paso.NADA, c.siguiente(10_000 + t, false, true))
        }
        assertEquals(ColaDeTareas.Paso.NADA, c.siguiente(10_000 + TOPE - 1, false, true))
        assertEquals(ColaDeTareas.Paso.MANDAR, c.siguiente(10_000 + TOPE, false, true))
        assertEquals("dos chats en la misma vuelta", setOf("obra", "general"), c.tomar())
    }

    @Test fun `con otra vuelta en marcha las tareas esperan y salen detras`() {
        val c = ColaDeTareas()
        c.avisan("obra", 0)
        assertEquals("nunca a la par", ColaDeTareas.Paso.NADA, c.siguiente(5_000, true, true))
        c.avisan("general", 5_100)
        assertEquals(ColaDeTareas.Paso.NADA, c.siguiente(5_200, true, true))
        assertEquals("se suma a la cola, no se pierde", ColaDeTareas.Paso.MANDAR, c.siguiente(PERIODO, false, true))
        assertEquals(setOf("obra", "general"), c.tomar())
    }

    @Test fun `ocupado allí vuelven a la cola y salen en cuanto se pueda`() {
        val c = ColaDeTareas()
        // Como hace la automática al recibir «ocupado»: como si ya estuvieran calmadas, con un poco
        // de azar (aquí 200 ms) para no reintentar al mismo compás que el otro.
        c.avisan("obra", 50_000 - CALMA + 200)
        assertEquals(200L, c.espera(50_000))
        assertEquals(ColaDeTareas.Paso.NADA, c.siguiente(50_199, false, true))
        assertEquals(ColaDeTareas.Paso.MANDAR, c.siguiente(50_200, false, true))
    }

    @Test fun `caso negativo sin nadie que conteste las tareas se olvidan sin salir`() {
        val c = ColaDeTareas()
        c.avisan("obra", 0)
        assertEquals(ColaDeTareas.Paso.OLVIDAR, c.siguiente(CALMA, false, false))
        c.tomar()
        assertEquals(ColaDeTareas.Paso.NADA, c.siguiente(CALMA, false, false))
    }

    @Test fun `la vuelta entera se lleva lo que esperaba de tareas`() {
        val c = ColaDeTareas()
        c.avisan("obra", 100)
        // Empieza una entera (apareció): toma la cola, y ya no espera nada.
        c.tomar()
        assertNull(c.espera(10_000))
    }

    @Test fun `que chats cambiaron de tareas`() {
        fun lista(id: String, texto: String, proyecto: String? = null) =
            Mensaje(id = id, cuando = 1, clase = Clase.MINIAPP, miniapp = "tareas", texto = texto, proyecto = proyecto)
        val nota = Mensaje(id = "n", cuando = 1, clase = Clase.NOTA, texto = "hola")
        val antes = listOf(lista("a", "- [ ] pan"), lista("b", "- [ ] losa", "obra"), nota)
        assertEquals(emptySet<String>(), MensajesStore.chatsConTareasCambiadas(antes, antes))
        assertEquals("una nota no cuenta", emptySet<String>(),
            MensajesStore.chatsConTareasCambiadas(antes, antes.map { if (it.id == "n") it.copy(texto = "adiós") else it }))
        assertEquals(setOf("obra"),
            MensajesStore.chatsConTareasCambiadas(antes, antes.map { if (it.id == "b") it.copy(texto = "- [x] losa") else it }))
        assertEquals("quitada", setOf(Disco.GENERAL), MensajesStore.chatsConTareasCambiadas(antes, antes.filter { it.id != "a" }))
        assertEquals("añadida", setOf("tesis"), MensajesStore.chatsConTareasCambiadas(antes, antes + lista("c", "- [ ] x", "tesis")))
        assertEquals("movida a otro chat: los dos", setOf(Disco.GENERAL, "obra"),
            MensajesStore.chatsConTareasCambiadas(antes, antes.map { if (it.id == "a") it.copy(proyecto = "obra") else it }))
    }
}
