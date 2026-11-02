package com.forge.pixpin.mini

import com.forge.pixpin.ia.Fechas
import com.forge.pixpin.ia.Momento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** **Sacar las listas de tareas** (2-nov-2026): las pruebas de `tareas_exportar/pruebas.rs` del PC. */
class ExportarTareasTest {

    /** El 1-nov-2026 a las 9:12, domingo. */
    private val ahora = Momento(Fechas.diasDe(2026, 11, 1), 9 * 60 + 12)
    private val r = ExportarTareas.Rotulos()

    private val compras = "# Compras <de> la \"obra\" & más\n\n" +
        "- [ ] Llamar al banco ⏫ #casa 💬 no abren hasta el lunes ⏰ 2026-11-02 17:00 ➕ 2026-10-28\n" +
        "- [ ] Comprar yeso y <cinta> de carrocero ![img 01](pixpin:files/a/yeso.png) 🔼 #obra ➕ 2026-10-30\n" +
        "- [ ] Pedir presupuesto del suelo radiante ⏰ 2026-11-06 09:00\n" +
        "- [ ] Renovar el seguro del coche 🔼 ⏰ 2026-10-30 10:00\n" +
        "- [x] Cemento ➕ 2026-10-20\n" +
        "- [x] Arena de río #obra\n"
    private val casa = "# Casa\n\n- [ ] Regar las plantas #casa\n- [ ] Cambiar la bombilla del pasillo ⏰ 2026-11-01 20:30\n- [x] Limpiar los cristales\n"
    private val hecha = "# Viaje\n\n- [x] Maletas\n- [x] Billetes ⏰ 2026-10-01 08:00\n"

    private fun listas() = listOf(
        ExportarTareas.ListaParaExportar.delDocumento(compras, "x"),
        ExportarTareas.ListaParaExportar.delDocumento(casa, "x"),
        ExportarTareas.ListaParaExportar.delDocumento(hecha, "x")
    )

    private val foto = { e: String -> if (e.endsWith("yeso.png")) "data:image/png;base64,iVBORw0KGgo=" else null }

    /** La hoja de estilo de verdad (la del PC, en `assets`). */
    private val css: String = sequenceOf("src/main/assets/tareas-exportar.css", "app/src/main/assets/tareas-exportar.css")
        .map(::File).firstOrNull { it.isFile }?.readText().orEmpty()

    @Test
    fun `cada tarea se entiende con sus marcas y sin ellas en el titulo`() {
        val l = ExportarTareas.ListaParaExportar.delDocumento(compras, "x")
        assertEquals("Compras <de> la \"obra\" & más", l.titulo)
        assertEquals(2 to 6, l.cuenta())
        val t = l.tareas[0]
        assertEquals("Llamar al banco", t.titulo)
        assertEquals(TareaInteligente.Urgencia.MUY_URGENTE, t.urgencia)
        assertEquals(listOf("casa"), t.etiquetas)
        assertEquals("no abren hasta el lunes", t.nota)
        assertEquals(Momento(Fechas.diasDe(2026, 11, 2), 17 * 60), t.hora)
        assertEquals("2026-10-28", t.creada.toString())
        val y = l.tareas[1]
        assertEquals("Comprar yeso y <cinta> de carrocero", y.titulo)
        assertEquals(listOf("pixpin:files/a/yeso.png"), y.imagenes)
        assertEquals(TareaInteligente.Urgencia.URGENTE, y.urgencia)
        assertEquals("Lista", ExportarTareas.ListaParaExportar.delDocumento("- [ ] a", "Lista").titulo)
        assertEquals(5 to 11, ExportarTareas.cuenta(listas()))
        assertEquals(45, ExportarTareas.porCiento(5, 11))
        assertEquals(0, ExportarTareas.porCiento(0, 0))
    }

    @Test
    fun `el recordatorio dice hoy manana y el dia con su fecha`() {
        fun m(d: Long, min: Int) = Momento(ahora.dia + d, min)
        assertEquals("Mañana 17:00" to "2 de noviembre", ExportarTareas.recordatorio(m(1, 17 * 60), ahora, r))
        assertEquals("Hoy 20:30", ExportarTareas.recordatorio(m(0, 20 * 60 + 30), ahora, r).first)
        assertEquals("vie 6 de noviembre 09:00" to null, ExportarTareas.recordatorio(m(5, 9 * 60), ahora, r))
        assertEquals("dom 10 de enero de 2027 09:00", ExportarTareas.recordatorio(m(70, 9 * 60), ahora, r).first)
        assertEquals("Exportado el 1 de noviembre de 2026 · 09:12", r.exportado(ahora))
        val l = ExportarTareas.ListaParaExportar.delDocumento(compras, "x")
        assertTrue("el seguro ya pasó", ExportarTareas.pasada(l.tareas[3], ahora))
        assertFalse(ExportarTareas.pasada(l.tareas[0], ahora))
        assertFalse(ExportarTareas.pasada(ExportarTareas.ListaParaExportar.delDocumento(hecha, "x").tareas[1], ahora))
    }

    @Test
    fun `la pagina lleva el resumen las listas y lo hecho plegado al final`() {
        val h = ExportarTareas.html(listas(), "Mis tareas", r, ahora, css, foto)
        assertTrue(h.startsWith("<!DOCTYPE html>"))
        assertTrue(h.contains("<html lang=\"es\">"))
        assertTrue(h.contains("<title>Mis tareas</title>"))
        assertTrue("la hoja del PC", h.contains("prefers-color-scheme: dark") && h.contains("@media print"))
        assertFalse(h.contains("http://") || h.contains("https://"))
        assertTrue(h.contains("<b>6</b><span>Pendientes</span>"))
        assertTrue(h.contains("<b>5</b><span>Hechas</span>"))
        assertTrue(h.contains("<b>45<small>%</small></b>"))
        assertTrue(h.contains("aria-valuenow=\"5\""))
        assertEquals(3, Regex("<section class=\"lista\">").findAll(h).count())
        assertEquals(3, Regex("<header class=\"lista-cab\">").findAll(h).count())
        assertTrue(h.contains("<span class=\"cuenta\">2 de 6</span>"))
        assertEquals(11, Regex("<li class=\"tarea").findAll(h).count())
        assertEquals(5, Regex("<li class=\"tarea hecha").findAll(h).count())
        assertEquals(3, Regex("<details class=\"hechas\">").findAll(h).count())
        val iHechas = h.indexOf("<details class=\"hechas\">")
        assertTrue(h.indexOf("Cemento") > iHechas)
        assertTrue(h.indexOf("Llamar al banco") < iHechas)
        assertTrue(h.contains("chip prio muy") && h.contains("chip prio alta"))
        assertTrue(h.contains("<time datetime=\"2026-11-02T17:00\">Mañana 17:00</time>"))
        assertTrue(h.contains("<span class=\"lejos\">2 de noviembre</span>"))
        assertTrue(h.contains("chip hora pasada"))
        assertTrue(h.contains("<span class=\"chip tag\">#casa</span>"))
        assertTrue(h.contains("<p class=\"nota\">no abren hasta el lunes</p>"))
        assertTrue(h.contains("Creada el 28 de octubre"))
        assertTrue(h.contains("<img src=\"data:image/png;base64,"))
        assertTrue(h.contains("<p class=\"vacio\">Todo hecho</p>"))
        for (crudo in listOf("⏫", "🔼", "➕", "💬", "pixpin:", "- [ ]", "2026-11-02 17:00")) assertFalse("se coló $crudo", h.contains(crudo))
    }

    @Test
    fun `lo que escribe el usuario va escapado`() {
        val doc = "# <script>alert(1)</script>\n\n- [ ] <img src=x onerror=alert(2)> \"y\" & 'z' #<b>\n"
        val l = ExportarTareas.ListaParaExportar.delDocumento(doc, "x")
        val h = ExportarTareas.html(listOf(l), l.titulo, r, ahora, css) { null }
        assertFalse(h.contains("<script>alert"))
        assertFalse(h.contains("<img src=x"))
        assertTrue(h.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(h.contains("&lt;img src=x onerror=alert(2)&gt; &quot;y&quot; &amp; &#39;z&#39;"))
        assertFalse(h.contains("<header class=\"lista-cab\">"))
        val sinFoto = ExportarTareas.html(listOf(ExportarTareas.ListaParaExportar.delDocumento(compras, "x")), "", r, ahora, css) { null }
        assertFalse(sinFoto.contains("<img"))
        assertTrue(sinFoto.contains("<title>Tareas</title>"))
        assertTrue(ExportarTareas.markdown(listOf(l), "x", r, ahora).contains("\\<img src=x onerror=alert(2)\\>"))
    }

    @Test
    fun `el markdown y el texto llevan casillas y un sufijo compacto`() {
        val m = ExportarTareas.markdown(listas(), "Mis tareas", r, ahora)
        assertTrue(m.startsWith("# Mis tareas\n\n*Exportado el 1 de noviembre de 2026 · 09:12 · Pendientes: 6 · Hechas: 5 · 45 %*\n"))
        assertTrue(m.contains("\n## Compras \\<de\\> la \"obra\" & más — 2 de 6\n"))
        assertTrue(m.contains("- [ ] Llamar al banco · **Muy urgente** · ⏰ Mañana 17:00 (2 de noviembre) · #casa — *no abren hasta el lunes*\n"))
        assertTrue(m.contains("- [ ] Renovar el seguro del coche · **Urgente** · ⏰ vie 30 de octubre 10:00 · Pasada\n"))
        val t = ExportarTareas.texto(listas(), "Mis tareas", r, ahora)
        assertTrue(t.startsWith("Mis tareas\nExportado el 1 de noviembre de 2026 · 09:12 · Pendientes: 6 · Hechas: 5\n"))
        assertTrue(t.contains("☐ Llamar al banco · Muy urgente · ⏰ Mañana 17:00 (2 de noviembre) · #casa\n   💬 no abren hasta el lunes\n"))
        assertTrue(t.contains("☑ Cemento"))
        // Con fotos (el PDF y el Word): la foto va debajo de su tarea.
        val conFotos = ExportarTareas.markdown(listas(), "x", r, ahora) { e -> e.takeIf { it.endsWith("yeso.png") } }
        assertTrue(conFotos.contains("![Foto 1](pixpin:files/a/yeso.png)"))
    }

    @Test
    fun `en ruso el mes va en genitivo dentro de la fecha`() {
        val ru = ExportarTareas.Rotulos(meses = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"), fecha = "{dia} {mes}", fechaConAno = "{dia} {mes} {ano}")
        assertEquals("28 октября", ru.dia(Fechas.diasDe(2026, 10, 28), ahora))
    }
}
