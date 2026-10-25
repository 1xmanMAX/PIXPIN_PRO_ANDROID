package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.ClaseExportar
import com.forge.pixpin.guardados.ClaseExportar.Datos
import com.forge.pixpin.guardados.ClaseExportar.Fila
import com.forge.pixpin.guardados.ClaseExportar.Medio
import com.forge.pixpin.guardados.ClaseExportar.Rotulos
import com.forge.pixpin.guardados.EstudiarClase.Marcador
import com.forge.pixpin.guardados.Mensaje
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * **Sacar una grabación** (25-oct-2026): las pruebas de `clase_exportar/pruebas.rs` del PC (270b812,
 * dcca06b, cd491a6), con los mismos datos y las mismas cadenas esperadas.
 */
class ClaseExportarTest {

    /** Las capturas como llegan de una lista mal guardada: al revés. */
    private fun sinOrdenar() = Datos(
        titulo = "Clase <de> \"física\" & más",
        fecha = "25/10/2026 18:05",
        duracionMs = 3_725_000,
        audio = "/x/clase.m4a",
        video = null,
        capturas = listOf(65_000L to "/x/c1.png", 5_000L to "/x/c0.png"),
        lineas = listOf(
            0L to "Hola a <todos>",
            60_000L to "Vamos con la ley de Ohm",
            null to "Una línea sin minuto",
            120_000L to "Y la potencia"
        ),
        marcadores = listOf(Marcador(60_000, "Ojo: examen"), Marcador(3_700_000, ""))
    )

    private fun ejemplo() = sinOrdenar().let { it.copy(capturas = it.capturas.sortedBy { c -> c.first }) }

    private val incrustar: (String) -> Medio? = { Medio.Incrustado("image/png", "png".toByteArray()) }

    private fun ext(r: String) = r.substringAfterLast('.')

    @Test fun el_minuto_se_lee_como_en_la_burbuja() {
        assertEquals("0:00", ClaseExportar.minuto(0))
        assertEquals("1:05", ClaseExportar.minuto(65_400))
        assertEquals("1:02:05", ClaseExportar.minuto(3_725_000))
        assertEquals("0:00", ClaseExportar.minuto(-5))
    }

    @Test fun las_capturas_y_los_marcadores_caen_en_su_minuto() {
        assertEquals(
            listOf(
                Fila.Linea(0), Fila.Captura(0), Fila.Linea(1), Fila.Linea(2),
                Fila.DeMarcador(0), Fila.Captura(1), Fila.Linea(3), Fila.DeMarcador(1)
            ),
            ClaseExportar.filas(ejemplo())
        )
    }

    @Test fun las_capturas_desordenadas_salen_igual_en_su_minuto() {
        assertEquals(Fila.Captura(1), ClaseExportar.filas(sinOrdenar())[1])
    }

    @Test fun el_html_escapa_todo_lo_que_viene_del_usuario() {
        var h = ClaseExportar.html(ejemplo(), Rotulos(), incrustar)
        assertTrue(h.contains("<title>Clase &lt;de&gt; &quot;física&quot; &amp; más</title>"))
        assertTrue(h.contains("Hola a &lt;todos&gt;"))
        assertFalse(h.contains("<todos>"))
        assertFalse(h.contains("<de>"))
        h = ClaseExportar.html(ejemplo(), Rotulos(idioma = "en\"><script>"), incrustar)
        assertTrue(h.contains("lang=\"en&quot;&gt;&lt;script&gt;\""))
    }

    @Test fun cada_minuto_lleva_a_donde_salta() {
        val h = ClaseExportar.html(ejemplo(), Rotulos(), incrustar)
        assertTrue(h.contains("<p class=\"linea\" id=\"t-60000\" data-ms=\"60000\"><a class=\"t\" href=\"#t-60000\" data-ms=\"60000\">1:00</a>"))
        assertTrue(h.contains("<p class=\"linea sin-tiempo\"><span class=\"dicho\">Una línea sin minuto"))
        assertTrue(h.contains("class=\"senal marcador\" href=\"#t-60000\" data-ms=\"60000\" style=\"left:1.611%\""))
        assertTrue(h.contains("class=\"senal captura\" href=\"#captura-1\""))
        assertTrue(h.contains("<audio id=\"audio\" src=\"data:image/png;base64,cG5n\""))
        assertTrue(h.contains("data-maestro"))
        val c0 = h.indexOf("id=\"captura-1\" data-ms=\"5000\"")
        val l1 = h.indexOf("id=\"t-60000\"")
        val c1 = h.indexOf("id=\"captura-2\" data-ms=\"65000\"")
        assertTrue(c0 in 0 until l1 && l1 < c1)
        // Con capturas y marcadores, el lado; sin ellos, el texto a lo ancho (cd491a6).
        assertTrue(h.contains("<main class=\"cuerpo con-lado\">"))
        val sola = ClaseExportar.html(ejemplo().copy(capturas = emptyList(), marcadores = emptyList()), Rotulos(), incrustar)
        assertTrue(sola.contains("<main class=\"cuerpo\">"))
        assertFalse(sola.contains("<aside"))
    }

    @Test fun sin_nada_que_oir_los_minutos_no_saltan() {
        val h = ClaseExportar.html(ejemplo(), Rotulos()) { r -> if (ext(r) == "png") Medio.Incrustado("image/png", byteArrayOf(1)) else null }
        assertFalse(h.contains("<audio"))
        assertFalse(h.contains("href=\"#t-"))
        assertTrue(h.contains("<span class=\"t\">1:00</span>"))
        assertFalse(h.contains("class=\"mandos\""))
    }

    @Test fun la_pagina_no_pide_nada_a_la_red() {
        val c = ejemplo().copy(video = "/x/Clase 25-10: vídeo.mp4", adjuntos = listOf("/x/apuntes.pdf"))
        val h = ClaseExportar.html(c, Rotulos()) { r ->
            when (ext(r)) {
                "png", "m4a" -> incrustar(r)
                else -> Medio.AlLado("../../" + ClaseExportar.nombre(r))
            }
        }
        for (prohibido in listOf("http:", "https:", "src=\"//", "href=\"//", "@import", "url(")) {
            assertFalse("la página lleva $prohibido", h.contains(prohibido))
        }
        assertTrue(h.contains("<video id=\"video\" src=\"Clase%2025-10%3A%20v%C3%ADdeo.mp4\""))
        assertTrue(h.contains("href=\"apuntes.pdf\""))
        assertTrue(h.contains("preload=\"metadata\" playsinline muted>"))
    }

    @Test fun las_urls_relativas_no_se_salen_ni_cambian_de_esquema() {
        assertEquals("a%20b/%C3%B1.png", ClaseExportar.urlRelativa("a b/ñ.png"))
        assertEquals("etc/x", ClaseExportar.urlRelativa("../../etc/x"))
        assertEquals("javascript%3Aalert%281%29", ClaseExportar.urlRelativa("javascript:alert(1)"))
        assertEquals("C%3A/x/y.mp4", ClaseExportar.urlRelativa("C:\\x\\y.mp4"))
    }

    @Test fun el_markdown_lleva_las_capturas_en_su_minuto() {
        val md = ClaseExportar.markdown(ejemplo(), Rotulos()) { r -> "Clase x_archivos/" + ClaseExportar.nombre(r) }
        assertTrue(md, md.startsWith("# Clase \\<de\\> \"física\" & más\n"))
        assertTrue(md.contains("**1:00** Vamos con la ley de Ohm"))
        assertTrue(md.contains("![Captura 2 · 1:05](Clase%20x_archivos/c1.png)"))
        assertTrue(md.contains("- Audio: [clase.m4a](Clase%20x_archivos/clase.m4a)"))
        assertTrue(md.contains("## Marcadores\n\n- **1:00** Ojo: examen\n- **1:01:40** Marcador\n"))
        val c1 = md.indexOf("c1.png)\n")
        assertTrue(md.indexOf("**1:00** Vamos") < c1)
        assertTrue(c1 < md.indexOf("**2:00** Y la potencia"))
    }

    @Test fun el_texto_es_la_transcripcion_con_sus_minutos() {
        val t = ClaseExportar.texto(ejemplo(), Rotulos())
        assertTrue(t, t.startsWith("Clase <de> \"física\" & más\n25/10/2026 18:05 · Duración 1:02:05\n\n"))
        assertTrue(t, t.contains("[0:00] Hola a <todos>\n\n[1:00] Vamos con la ley de Ohm\n\nUna línea sin minuto\n\n[1:00] -- Marcador: Ojo: examen\n\n[2:00] Y la potencia\n\n[1:01:40] -- Marcador\n"))
    }

    @Test fun de_la_nota_y_sus_enlaces() {
        val json = Json { ignoreUnknownKeys = true }
        fun arr(s: String) = json.parseToJsonElement(s) as JsonArray
        fun obj(s: String) = json.parseToJsonElement(s) as JsonObject
        val voz = Mensaje(
            id = "voz", cuando = 0, clase = Clase.VOZ, nombre = "Clase 25-10 18h05.m4a", ruta = "clase.m4a", duracionMs = 90_000,
            grabacion = "clase",
            capturas = arr("""[{"ms":30000,"id":"c2"},{"ms":1000,"id":"c1"},{"ms":5,"id":"no-esta"}]"""),
            video = obj("""{"id":"v"}"""),
            marcadores = arr("""[{"ms":40000,"texto":"importante"}]"""),
            marcas = listOf(10_000, 40_000)
        )
        assertTrue(ClaseExportar.esGrabacion(voz) && ClaseExportar.esDeClase(voz))
        fun hijo(id: String, clase: Clase, ruta: String, ms: Long) =
            Mensaje(id = id, cuando = 0, clase = clase, ruta = ruta, enGrabacion = obj("""{"de":"voz","ms":$ms}"""))
        val mensajes = listOf(
            voz,
            hijo("c1", Clase.IMAGEN, "c1.png", 1_000),
            hijo("c2", Clase.IMAGEN, "c2.png", 30_000),
            hijo("v", Clase.ARCHIVO, "v.mp4", 0),
            // Llegada por la carpeta de la grabación, sin estar en sus listas.
            hijo("c3", Clase.IMAGEN, "c3.png", 50_000),
            hijo("pdf", Clase.ARCHIVO, "apuntes.pdf", 0),
            Mensaje(id = "otro", cuando = 0, clase = Clase.IMAGEN, ruta = "otro.png")
        )
        val c = ClaseExportar.deLaNota(voz, mensajes, listOf(0L to "hola"), "hoy") { m -> m.ruta?.let { "/p/$it" } }
        assertEquals("Clase 25-10 18h05", c.titulo)
        assertEquals("/p/clase.m4a", c.audio)
        assertEquals("/p/v.mp4", c.video)
        assertEquals(listOf(1_000L to "/p/c1.png", 30_000L to "/p/c2.png", 50_000L to "/p/c3.png"), c.capturas)
        assertEquals(listOf("/p/apuntes.pdf"), c.adjuntos)
        assertEquals(listOf(10_000L, 40_000L), c.marcadores.map { it.ms })
        assertEquals("importante", c.marcadores[1].texto)
        assertEquals(6, c.ficheros().size)
        assertEquals(90_000L, c.duracion())
    }

    @Test fun el_markdown_va_en_un_zip_con_su_carpeta() {
        val zip = ClaseExportar.paqueteMarkdown(ejemplo(), Rotulos(), "Clase 1") { r ->
            if (ClaseExportar.nombre(r) == "c1.png") "png".toByteArray() else null
        }
        val dentro = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                dentro[e.name] = z.readBytes()
            }
        }
        assertEquals(listOf("Clase 1.md", "Clase 1_archivos/captura-02-1m05s.png"), dentro.keys.sorted())
        val md = String(dentro["Clase 1.md"]!!, Charsets.UTF_8)
        assertTrue(md.contains("![Captura 2 · 1:05](Clase%201_archivos/captura-02-1m05s.png)"))
        assertFalse("la que no se pudo leer no se enlaza", md.contains("Captura 1 ·"))
        assertEquals("png", String(dentro["Clase 1_archivos/captura-02-1m05s.png"]!!))
    }
}
