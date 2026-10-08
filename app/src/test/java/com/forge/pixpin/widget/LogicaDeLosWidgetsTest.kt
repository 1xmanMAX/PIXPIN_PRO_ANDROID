package com.forge.pixpin.widget

import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.CaducidadDeCapturas.DIA_MS
import com.forge.pixpin.capture.GaleriaLogica
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.mini.MiniApp
import com.forge.pixpin.mini.Tareas
import com.forge.pixpin.mini.TodasLasTareas
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los widgets por dentro: qué tareas salen y cómo se marca una, y qué capturas salen con qué
 * chapita. Con los casos que fallan: nada pendiente, una tarea que solo es una foto, una lista
 * que cambió desde que se pintó, una captura conservada y una ya caducada.
 */
class LogicaDeLosWidgetsTest {

    private val hoy = LocalDate.of(2026, 10, 8)
    private val utc = TimeZone.getTimeZone("UTC")

    private fun lista(chat: String, titulo: String, cuando: Long, doc: String, proyecto: String? = chat.lowercase()) =
        TodasLasTareas.Lista(proyecto, chat, "m$cuando", titulo, cuando, TodasLasTareas.filasDe(doc))

    private fun mensaje(id: String, texto: String, clase: Clase = Clase.MINIAPP) =
        Mensaje(id = id, cuando = 1, clase = clase, texto = texto, miniapp = if (clase == Clase.MINIAPP) MiniApp.TAREAS.id else null)

    // ---- Tareas ----------------------------------------------------------

    @Test
    fun salenSoloLasPendientesEnElOrdenDeLaPantallaDeTareas() {
        val listas = listOf(
            lista("Obra", "Pendientes", 50, "- [ ] revisar puntales ➕ 2026-10-01\n- [x] pedir yeso"),
            lista("Mensajes guardados", "Inbox", 10, "- [ ] sin fecha\n- [ ] llamar ➕ 2026-10-07\n- [x] hecha ➕ 2026-10-01", proyecto = null)
        )
        val filas = LogicaDeLosWidgets.filasDeTareas(listas, hoy)
        // El Inbox primero aunque sea más viejo; dentro, lo que tiene fecha antes que lo que no.
        assertEquals(listOf("llamar", "sin fecha", "revisar puntales"), filas.map { it.texto })
        assertEquals("Inbox · hace 1 día", filas[0].detalle)
        assertEquals("Inbox", filas[1].detalle)
        assertEquals("Pendientes · Obra · hace 7 días", filas[2].detalle)
        assertEquals(3, LogicaDeLosWidgets.pendientes(listas))
        // Lo que lleva para marcarla: su lista, su número en el documento y su texto crudo.
        assertEquals("m10", filas[0].codigo)
        assertEquals(1, filas[0].indice)
        assertEquals("llamar ➕ 2026-10-07", filas[0].crudo)
    }

    @Test
    fun sinListasOConTodoHechoNoSaleNada() {
        assertTrue(LogicaDeLosWidgets.filasDeTareas(emptyList(), hoy).isEmpty())
        val hechas = listOf(lista("Obra", "Todo", 5, "- [x] una\n- [x] otra"), lista("Casa", "Vacía", 6, ""))
        assertTrue(LogicaDeLosWidgets.filasDeTareas(hechas, hoy).isEmpty())
        assertEquals(0, LogicaDeLosWidgets.pendientes(hechas))
        assertEquals("", LogicaDeLosWidgets.cuenta(0))
        assertEquals("Nada pendiente. Toca «+» para apuntar.", LogicaDeLosWidgets.vacioDeTareas(hayListas = true))
        assertTrue(LogicaDeLosWidgets.vacioDeTareas(hayListas = false).startsWith("Aún no hay tareas"))
    }

    @Test
    fun unaTareaQueSoloEsUnaImagenDiceQueLoEs() {
        val l = lista("Obra", "Fotos", 5, "- [ ] ![img 01](pixpin:files/guardados/tarea-1-01.png) ➕ 2026-10-08\n- [ ] ver ![img 02](x.png)  y   medir")
        val filas = LogicaDeLosWidgets.filasDeTareas(listOf(l), hoy)
        assertEquals(LogicaDeLosWidgets.SOLO_IMAGEN, filas.first { it.indice == 0 }.texto)
        // La imagen no se pinta ni deja su enlace; los blancos de más se juntan.
        val otra = filas.first { it.indice == 1 }.texto
        assertTrue(otra, !otra.contains("img") && !otra.contains("  "))
        assertTrue(otra.startsWith("ver") && otra.endsWith("medir"))
    }

    @Test
    fun laCuentaNoSePasaDeDosCifrasYElTopeCortaLasFilas() {
        assertEquals("7", LogicaDeLosWidgets.cuenta(7))
        assertEquals("99", LogicaDeLosWidgets.cuenta(99))
        assertEquals("99+", LogicaDeLosWidgets.cuenta(100))
        val muchas = lista("Obra", "Larga", 1, (1..30).joinToString("\n") { "- [ ] t$it" })
        assertEquals(10, LogicaDeLosWidgets.filasDeTareas(listOf(muchas), hoy, max = 10).size)
        assertEquals(30, LogicaDeLosWidgets.pendientes(listOf(muchas)))
    }

    @Test
    fun marcarTachaLaTareaEnSuMensajeYNadaMas() {
        val doc = Tareas.escribir("Inbox", Tareas.leer("- [ ] pan\n- [ ] leche"))
        val ms = listOf(mensaje("a", doc), mensaje("b", "hola", Clase.NOTA))
        val nuevos = LogicaDeLosWidgets.marcarEn(ms, "a", 1, "leche")
        assertNotNull(nuevos)
        val tareas = Tareas.leer(nuevos!![0].texto)
        assertEquals(listOf(false, true), tareas.map { it.hecha })
        assertEquals("Inbox", com.forge.pixpin.mini.Cabecera.titulo(nuevos[0].texto))
        assertEquals(ms[1], nuevos[1])
        // Marcarla otra vez no escribe nada.
        assertNull(LogicaDeLosWidgets.marcarEn(nuevos, "a", 1, "leche"))
    }

    @Test
    fun marcarNoTocaNadaSiLaListaCambioODesaparecio() {
        val ms = listOf(mensaje("a", "- [ ] pan\n- [ ] leche"), mensaje("b", "texto suelto", Clase.NOTA))
        // Otro texto en ese número: la lista cambió desde que se pintó el widget.
        assertNull(LogicaDeLosWidgets.marcarEn(ms, "a", 1, "pan"))
        // Un número que ya no está.
        assertNull(LogicaDeLosWidgets.marcarEn(ms, "a", 7, "leche"))
        // Una lista que ya no está, o un mensaje que no es una lista.
        assertNull(LogicaDeLosWidgets.marcarEn(ms, "z", 0, "pan"))
        assertNull(LogicaDeLosWidgets.marcarEn(ms, "b", 0, "texto suelto"))
        assertNull(LogicaDeLosWidgets.marcarEn(emptyList(), "a", 0, "pan"))
    }

    // ---- Galería ---------------------------------------------------------

    /** El 8-oct-2026 a mediodía UTC. */
    private val ahora = 1_791_460_800_000L
    private val registro = CaducidadDeCapturas.Registro(desde = 0L)

    @Test
    fun cadaCapturaLlevaLosDiasQueLeQuedanConSuColor() {
        val lista = listOf(
            "a.png" to ahora - DIA_MS / 2,       // se va en 6,5 días
            "b.png" to ahora - 5 * DIA_MS,       // en 2 días
            "c.png" to ahora - 6 * DIA_MS,       // mañana
            "d.png" to ahora - 7 * DIA_MS + 1000 // hoy, dentro de un segundo
        )
        val c = LogicaDeLosWidgets.casillas(lista, registro, 7, ahora, zona = utc)
        assertEquals(listOf(0, 1, 2, 3), c.map { it.indice })
        assertEquals(listOf("7 d", "2 d", "mañana", "hoy"), c.map { it.chapita })
        assertEquals(
            listOf(GaleriaLogica.Tono.LEJOS, GaleriaLogica.Tono.PRONTO, GaleriaLogica.Tono.URGENTE, GaleriaLogica.Tono.URGENTE),
            c.map { it.tono }
        )
        assertEquals("Se borra en 2 días", c[1].descripcion)
    }

    @Test
    fun laConservadaNoLlevaChapitaYLaCaducadaNoSale() {
        val lista = listOf("viva.png" to ahora, "vieja.png" to ahora - 30 * DIA_MS, "mia.png" to ahora - 30 * DIA_MS)
        val r = registro.copy(conservadas = setOf("mia.png"))
        val c = LogicaDeLosWidgets.casillas(lista, r, 7, ahora, zona = utc)
        assertEquals(listOf(0, 2), c.map { it.indice })
        assertNull(c[1].chapita)
        assertNull(c[1].tono)
        assertEquals("Conservada", c[1].descripcion)
        assertEquals(2, LogicaDeLosWidgets.vivas(lista, r, 7, ahora))
    }

    @Test
    fun sinCaducidadNingunaLlevaChapitaYLaProrrogaCuenta() {
        val lista = listOf("a.png" to ahora - 30 * DIA_MS)
        val sin = LogicaDeLosWidgets.casillas(lista, registro, 0, ahora, zona = utc)
        assertEquals(1, sin.size)
        assertNull(sin[0].chapita)
        val prorrogada = registro.copy(prorrogadas = mapOf("a.png" to ahora + 4 * DIA_MS))
        assertEquals("4 d", LogicaDeLosWidgets.casillas(lista, prorrogada, 7, ahora, zona = utc).single().chapita)
    }

    @Test
    fun galeriaVaciaOTodasCaducadasYElTope() {
        assertTrue(LogicaDeLosWidgets.casillas(emptyList(), registro, 7, ahora, zona = utc).isEmpty())
        val caducadas = listOf("x.png" to ahora - 20 * DIA_MS, "y.png" to ahora - 9 * DIA_MS)
        assertTrue(LogicaDeLosWidgets.casillas(caducadas, registro, 7, ahora, zona = utc).isEmpty())
        assertEquals(0, LogicaDeLosWidgets.vivas(caducadas, registro, 7, ahora))
        val muchas = (0 until 40).map { "c$it.png" to ahora - it * 1000L }
        assertEquals((0 until 12).toList(), LogicaDeLosWidgets.casillas(muchas, registro, 7, ahora, zona = utc).map { it.indice })
        assertTrue(LogicaDeLosWidgets.vacioDeGaleria(fallo = true).startsWith("No se pudieron"))
        assertTrue(LogicaDeLosWidgets.vacioDeGaleria(fallo = false).startsWith("Aún no hay"))
    }

    @Test
    fun elRecorteEsElCuadradoDelCentro() {
        assertEquals(Triple(0, 0, 0), LogicaDeLosWidgets.recorteCuadrado(0, 100))
        assertEquals(Triple(100, 0, 200), LogicaDeLosWidgets.recorteCuadrado(400, 200))
        assertEquals(Triple(0, 50, 100), LogicaDeLosWidgets.recorteCuadrado(100, 200))
        assertEquals(Triple(0, 0, 256), LogicaDeLosWidgets.recorteCuadrado(256, 256))
    }
}
