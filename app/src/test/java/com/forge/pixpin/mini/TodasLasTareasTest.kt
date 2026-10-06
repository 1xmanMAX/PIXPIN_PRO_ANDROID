package com.forge.pixpin.mini

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La pantalla de Tareas por dentro, con los mismos casos que `tareas.rs` y `tareas/tarjetas.rs`
 * del PC: lo que sale, en qué orden, qué encuentra el buscador y que nada se toca si la tarea
 * cambió desde que se vio.
 */
class TodasLasTareasTest {

    private fun dia(d: Int) = LocalDate.of(2026, 10, d)

    private fun lista(chat: String, titulo: String, cuando: Long, doc: String, proyecto: String? = chat.lowercase()) =
        TodasLasTareas.Lista(proyecto, chat, "m$cuando", titulo, cuando, TodasLasTareas.filasDe(doc))

    private fun textos(v: List<TodasLasTareas.Fila>) = v.map { it.texto }

    private fun mensaje(id: String, texto: String, proyecto: String? = null, clase: Clase = Clase.MINIAPP, cuando: Long = 1, enBuzon: Boolean = false) =
        Mensaje(id = id, cuando = cuando, clase = clase, texto = texto, miniapp = if (clase == Clase.MINIAPP) MiniApp.TAREAS.id else null, proyecto = proyecto, enBuzon = enBuzon)

    private fun muestra() = listOf(
        lista(
            "Mensajes guardados", "Inbox", 30,
            "- [ ] Llamar al fontanero ➕ 2026-10-03\n- [x] pagar la luz ➕ 2026-10-02\n- [ ] comprar pan ➕ 2026-10-02",
            proyecto = null
        ),
        lista("Obra Miraflores", "Pendientes", 20, "- [ ] revisar puntales ➕ 2026-10-01\n- [x] pedir yeso")
    )

    // ---- La lectura ------------------------------------------------------

    @Test
    fun `las filas separan el texto de su fecha y de sus imagenes`() {
        val f = TodasLasTareas.filasDe("# Compra\n\n- [ ] pan ➕ 2026-10-01\n- [x] sal\nun parrafo suelto\n- [ ] yeso ![img 01](pixpin:files/a.png) y arena ➕ 2026-10-03")
        assertEquals("el título y el párrafo no son tareas", 3, f.size)
        assertEquals("pan", f[0].texto)
        assertEquals("pan ➕ 2026-10-01", f[0].crudo)
        assertEquals(dia(1), f[0].creada)
        assertEquals(Triple(1, true, null), Triple(f[1].indice, f[1].hecha, f[1].creada))
        assertEquals("yeso y arena", f[2].texto)
        assertEquals(listOf("pixpin:files/a.png"), f[2].imagenes)
        assertEquals(dia(3), f[2].creada)
    }

    @Test
    fun `las imagenes de una tarea como en el PC y lo que no lo es se queda como texto`() {
        assertEquals("comprar yeso" to listOf("pixpin:files/a.png"), TodasLasTareas.imagenesDe("comprar yeso ![img 01](pixpin:files/a.png)"))
        assertEquals("" to listOf("a.png"), TodasLasTareas.imagenesDe("![img 01](a.png)"))
        assertEquals("ñandú ★" to listOf("ñ.png"), TodasLasTareas.imagenesDe("ñandú ![img 01](ñ.png) ★"))
        // Caso negativo: nada de esto es una imagen.
        for (t in listOf("un [enlace](x.png) normal", "![sin cierre](x.png", "![vacia]()", "![con blanco](mi foto.png)", "! [separada](x.png)", "pan")) {
            assertEquals(t, t to emptyList<String>(), TodasLasTareas.imagenesDe(t))
        }
    }

    @Test
    fun `lo pendiente con fecha va primero y la mas vieja arriba`() {
        val l = lista("Casa", "Compra", 1, "- [ ] sin fecha a\n- [ ] nueva ➕ 2026-10-03\n- [x] hecha ➕ 2026-09-01\n- [ ] vieja ➕ 2026-10-01\n- [ ] sin fecha b")
        val (arriba, plegadas) = l.aLaVista { false }
        assertEquals(listOf("vieja", "nueva", "sin fecha a", "sin fecha b"), textos(arriba))
        // Caso negativo: lo hecho no sale arriba aunque sea lo más viejo.
        assertEquals(listOf("hecha"), textos(plegadas))
    }

    @Test
    fun `la recien marcada se queda arriba en su sitio`() {
        val l = lista("Casa", "Compra", 1, "- [ ] a ➕ 2026-10-01\n- [x] b ➕ 2026-10-02\n- [ ] c ➕ 2026-10-03\n- [x] d")
        val (arriba, plegadas) = l.aLaVista { it.crudo.startsWith("b") }
        assertEquals(listOf("a", "b", "c"), textos(arriba))
        assertEquals(listOf("d"), textos(plegadas))
    }

    @Test
    fun `el inbox va primero luego lo pendiente y la mas nueva arriba`() {
        val v = listOf(
            lista("Casa", "Compra", 10, "- [x] todo hecho"),
            lista("Casa", "Vieja", 5, "- [ ] vieja"),
            lista("Casa", "Nueva", 20, "- [ ] nueva"),
            lista("Mensajes guardados", "inbox", 1, "- [x] hecho", proyecto = null)
        ).sortedWith(TodasLasTareas.ORDEN_DE_LISTAS)
        assertEquals(listOf(1L, 20L, 5L, 10L), v.map { it.cuando })
        // Caso negativo: una lista «Inbox» de un proyecto no es el Inbox.
        assertFalse(TodasLasTareas.esInbox(lista("Obra", "Inbox", 3, "")))
    }

    @Test
    fun `reunir junta las listas de todos los chats y nada mas`() {
        val mensajes = listOf(
            mensaje("a", "# Obra\n\n- [ ] yeso", proyecto = "p1", cuando = 5),
            mensaje("b", "# Inbox\n\n- [ ] pan", cuando = 6),
            mensaje("vacia", "# Nada\n\n", proyecto = "p1", cuando = 7),
            // Caso negativo: una nota con casillas no es una lista, y la de un proyecto que ya no está no sale.
            mensaje("nota", "- [ ] no soy lista", clase = Clase.NOTA),
            mensaje("huerfana", "# X\n\n- [ ] y", proyecto = "borrado")
        )
        val listas = TodasLasTareas.reunir(mensajes, mapOf("p1" to "Obra"))
        assertEquals("el Inbox, luego lo pendiente", listOf("b", "a", "vacia"), listas.map { it.codigo })
        assertEquals("Mensajes guardados", listas[0].chat)
        assertTrue(TodasLasTareas.esInbox(listas[0]))
        assertEquals("Obra", listas[1].chat)
        assertTrue("la vacía sale sin tareas: es adonde mover", listas[2].filas.isEmpty())
    }

    @Test
    fun `el inbox es la lista del chat general que se llama asi`() {
        val m = listOf(
            mensaje("proy", "# Inbox\n\n", proyecto = "p1"),
            mensaje("buzon", "# Inbox\n\n", enBuzon = true),
            mensaje("otra", "# Compra\n\n"),
            mensaje("bueno", "#  INBOX \n\n- [ ] x"),
            mensaje("segundo", "# Inbox\n\n")
        )
        assertEquals("bueno", TodasLasTareas.inboxEn(m)?.id)
        // Caso negativo: sin ninguna en el chat general, no hay Inbox (se creará).
        assertNull(TodasLasTareas.inboxEn(m.take(3)))
    }

    @Test
    fun `apuntar lleva la fecha de hoy y lo vacio no apunta nada`() {
        val doc = Tareas.escribir(TodasLasTareas.INBOX, emptyList())
        val uno = TodasLasTareas.conTarea(doc, "pan", dia(3))
        assertEquals("# Inbox\n\n- [ ] pan ➕ 2026-10-03", uno)
        assertEquals(uno, TodasLasTareas.conTarea(uno, "   ", dia(3)))
    }

    // ---- Buscar ----------------------------------------------------------

    private fun sale(v: List<TodasLasTareas.Lista>, q: String, hoy: LocalDate = dia(3)) =
        TodasLasTareas.agrupar(v, TodasLasTareas.palabras(q), hoy).flatMap { g -> (g.arriba + g.hechas).map { v[g.lista].filas[it].texto } }

    @Test
    fun `buscar sin tildes ni mayusculas y con todas las palabras`() {
        val v = muestra()
        assertEquals(listOf("Llamar al fontanero"), sale(v, "FONTANÉRO"))
        assertEquals(listOf("Llamar al fontanero"), sale(v, "llamar fontanero"))
        // Por el nombre de la lista o del chat salen todas las suyas.
        assertEquals(listOf("revisar puntales", "pedir yeso"), sale(v, "miraflores"))
        assertEquals(listOf("comprar pan"), sale(v, "inbox pan"))
        assertEquals(5, sale(v, "  ").size)
        // Caso negativo: una palabra que no está deja fuera aunque las demás estén, y un grupo sin nada no sale.
        assertTrue(sale(v, "fontanero yeso").isEmpty())
        assertEquals(1, TodasLasTareas.agrupar(v, listOf("yeso"), dia(3)).size)
    }

    @Test
    fun `hoy y ayer buscan por la fecha en que se apunto`() {
        val v = muestra()
        val hoy = dia(3)
        assertEquals(listOf(0), TodasLasTareas.agrupar(v, listOf("hoy"), hoy).single().arriba)
        val ayer = TodasLasTareas.agrupar(v, listOf("Ayer"), hoy).single()
        assertEquals(listOf(2) to listOf(1), ayer.arriba to ayer.hechas)
        val g = TodasLasTareas.agrupar(v, listOf("2026-10-01"), hoy).single()
        assertEquals(1 to listOf(0), g.lista to g.arriba)
        assertEquals(listOf("comprar pan"), sale(v, "ayer pan"))
        // Caso negativo: sin fecha no es de hoy, una del futuro tampoco, y un número suelto no es una fecha.
        assertFalse(TodasLasTareas.Dia.Hace(0).es(null, hoy))
        assertFalse(TodasLasTareas.Dia.Hace(0).es(dia(9), hoy))
        assertNull(TodasLasTareas.diaDe("2026-13-01"))
        assertNull(TodasLasTareas.diaDe("2026-02-31"))
        assertNull(TodasLasTareas.diaDe("2026"))
        assertNull(TodasLasTareas.diaDe("hoyo"))
        assertEquals(TodasLasTareas.Dia.Hace(2), TodasLasTareas.diaDe("ANTEAYER"))
    }

    @Test
    fun `agrupar deja lo pendiente arriba y lo hecho aparte`() {
        val v = muestra()
        var g = TodasLasTareas.agrupar(v, emptyList(), dia(3))
        assertEquals(2, g.size)
        assertEquals(listOf(2, 0), g[0].arriba)
        assertEquals(listOf(1), g[0].hechas)
        assertEquals(2, g[0].pendientes(v[0]))
        // Lo recién tachado se queda arriba, pero no cuenta como pendiente.
        g = TodasLasTareas.agrupar(v, emptyList(), dia(3)) { _, f -> f.texto == "pagar la luz" }
        assertEquals(3, g[0].arriba.size)
        assertEquals(2, g[0].pendientes(v[0]))
        // Caso negativo: una lista vacía no hace grupo.
        assertTrue(TodasLasTareas.agrupar(listOf(lista("X", "Nada", 1, "# Nada\n")), emptyList(), dia(3)).isEmpty())
    }

    @Test
    fun `lo resaltado cae en su sitio del original aunque lleve tildes`() {
        val t = "Revisión del cañón"
        assertEquals(listOf(0..7, 13..17), TodasLasTareas.resaltes(t, listOf("revision", "CANON")))
        // Dos palabras que se pisan se juntan en un tramo.
        assertEquals(listOf(0..7), TodasLasTareas.resaltes(t, listOf("revis", "vision")))
        // Caso negativo: nada en blanco ni lo que no está.
        assertTrue(TodasLasTareas.resaltes(t, listOf(" ", "puente")).isEmpty())
        assertTrue(TodasLasTareas.coincidencias("ab", "abc").isEmpty())
    }

    @Test
    fun `la edad y los resumenes con los textos del PC`() {
        assertEquals("hoy", TodasLasTareas.edad(dia(3), dia(3)))
        assertEquals("hace 1 día", TodasLasTareas.edad(dia(2), dia(3)))
        assertEquals("hace 5 días", TodasLasTareas.edad(LocalDate.of(2026, 9, 28), dia(3)))
        assertNull(TodasLasTareas.edad(null, dia(3)))
        assertEquals("Nada pendiente · 1 lista", TodasLasTareas.resumen(0, 1))
        assertEquals("3 pendientes · 2 listas", TodasLasTareas.resumen(3, 2))
        assertEquals("1 pendiente", TodasLasTareas.pendientesDeGrupo(1))
        assertEquals("2 tareas encontradas", TodasLasTareas.encontradas(2))
    }

    // ---- Escribir --------------------------------------------------------

    @Test
    fun `marcar tacha solo la pedida y no toca nada si cambio`() {
        val doc = "# Obra\n\n- [ ] yeso ➕ 2026-10-03\n- [ ] arena ➕ 2026-10-03"
        val f = TodasLasTareas.filasDe(doc)
        val nuevo = TodasLasTareas.marcar(doc, f[1], true)!!
        assertEquals(listOf(false, true), Tareas.leer(nuevo).map { it.hecha })
        assertEquals("Obra", Cabecera.titulo(nuevo))
        // Caso negativo: la del número ya no es la que se vio.
        assertNull(TodasLasTareas.marcar(doc, f[0].copy(crudo = "otra cosa"), true))
        assertNull(TodasLasTareas.marcar(doc, f[1].copy(indice = 7), true))
    }

    @Test
    fun `quitar y deshacer la devuelven a su sitio con su fecha`() {
        val doc = "# Obra\n\n- [ ] yeso ➕ 2026-10-03\n- [x] arena ➕ 2026-10-02\n- [ ] cal"
        val f = TodasLasTareas.filasDe(doc)
        val (sin, quitada) = TodasLasTareas.quitar(doc, f[1])!!
        assertEquals(listOf("yeso", "cal"), textos(TodasLasTareas.filasDe(sin)))
        assertEquals(Tarea("arena ➕ 2026-10-02", true), quitada)
        assertEquals(doc, TodasLasTareas.reponer(sin, 1, quitada))
        // Si la lista se acortó entre medias, al final.
        assertEquals("arena", TodasLasTareas.filasDe(TodasLasTareas.reponer("# Obra\n\n", 1, quitada)).single().texto)
        // Caso negativo: la que se vio ya no está en ese número.
        assertNull(TodasLasTareas.quitar(sin, f[1]))
    }

    @Test
    fun `mover lleva la tarea al final de la otra lista con su fecha y su estado`() {
        val inbox = "# Inbox\n\n- [ ] arena ➕ 2026-10-03\n- [ ] pan ➕ 2026-10-03"
        val obra = "# Obra\n\n- [ ] yeso"
        val f = TodasLasTareas.filasDe(inbox)
        val (o, d) = TodasLasTareas.mover(inbox, f[0], obra)!!
        assertEquals(listOf("pan"), textos(TodasLasTareas.filasDe(o)))
        val suyas = TodasLasTareas.filasDe(d)
        assertEquals(listOf("yeso", "arena"), textos(suyas))
        assertEquals(dia(3), suyas[1].creada)
        assertEquals("Obra", Cabecera.titulo(d))
        // Caso negativo: una tarea que ya no está donde se vio no se mueve.
        assertNull(TodasLasTareas.mover(o, f[0].copy(crudo = "otra"), d))
    }

    @Test
    fun `mover a otro chat copia sus imagenes y cambia el enlace`() {
        val raiz = Files.createTempDirectory("tareas").toFile()
        try {
            val guardados = File(raiz, "guardados").apply { mkdirs() }
            val foto = File(guardados, "pc/obra/archivos/tarea-1-01.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val absoluta = File(guardados, "mia.jpg").apply { writeBytes(byteArrayOf(4)) }
            val crudo = "muro ![img 01](pixpin:files/guardados/pc/obra/archivos/tarea-1-01.png) ![img 02](${absoluta.absolutePath}) " +
                "![img 03](pixpin:files/guardados/no-llego.png) ➕ 2026-10-03"
            val nuevo = TodasLasTareas.conImagenesCopiadas(crudo, raiz, guardados, 99)!!
            val f = TodasLasTareas.filasDe("- [ ] $nuevo").single()
            assertEquals("muro", f.texto)
            assertEquals(dia(3), f.creada)
            assertEquals(File(guardados, "tarea-99-01.png").absolutePath, f.imagenes[0])
            assertEquals(File(guardados, "tarea-99-02.jpg").absolutePath, f.imagenes[1])
            assertTrue(File(f.imagenes[0]).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
            // El original se queda, y lo que aún no llegó conserva su enlace.
            assertTrue(foto.isFile)
            assertEquals("pixpin:files/guardados/no-llego.png", f.imagenes[2])
            // Caso negativo: sin imágenes, el texto no cambia.
            assertEquals("pan ➕ 2026-10-03", TodasLasTareas.conImagenesCopiadas("pan ➕ 2026-10-03", raiz, guardados, 100))
            assertNull(TodasLasTareas.archivoDeEnlace(raiz, "relativa.png"))
        } finally {
            raiz.deleteRecursively()
        }
    }

    @Test
    fun `cambiar enlaces solo toca los de las imagenes`() {
        val t = "a ![img 01](x.png) [no](y.png) ![img 02](z.png)"
        assertEquals("a ![img 01](X) [no](y.png) ![img 02](z.png)", TodasLasTareas.cambiarEnlaces(t) { if (it == "x.png") "X" else null })
        assertNotEquals(t, TodasLasTareas.cambiarEnlaces(t) { "Q" })
        assertNotNull(TodasLasTareas.imagenesDe(t))
    }
}
