package com.forge.pixpin

import com.forge.pixpin.guardados.CarpetaDePaginas
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.EstudiarClase
import com.forge.pixpin.guardados.ExportarGrabacion
import com.forge.pixpin.guardados.GrabarClase
import com.forge.pixpin.guardados.Mensaje
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La carpeta de una grabación** (25-oct-2026): las pruebas de `carpeta_de_paginas.rs` del PC de
 * 2061e6b y 40daac7, la de `grabar_clase.rs` de e015e2b y las de `clase.rs` de 8d7fe93 (poner una
 * captura), portadas con los mismos datos. El documento viaja en el `texto` de un mensaje: tiene que
 * escribirse byte a byte como allí.
 */
class CarpetaDeGrabacionTest {

    private fun ids(l: List<CarpetaDePaginas.Pagina>) = l.map { it.id }
    private fun mensaje(nombre: String, c: CarpetaDePaginas, cuando: Long = 1) = CarpetaDePaginas.mensajeNuevo(nombre, cuando, "p", c)

    @Test fun la_carpeta_de_una_grabacion_lleva_su_voz_su_duracion_y_sus_minutos() {
        var c = CarpetaDePaginas.grabacion("v", 5_400_000).copy(video = "video")
        assertTrue(c.esGrabacion)
        c = c.anadirAlMinuto("v", "", 0)!!
        c = c.anadirAlMinuto("video", "", 0)!!
        c = c.anadirAlMinuto("f1", "⏱ 01:05", 65_000)!!
        assertNull("no entra dos veces", c.anadirAlMinuto("f1", "otra vez", 9))
        val m = mensaje("  Clase 25-10 10h58 ", c)
        assertTrue(CarpetaDePaginas.esCarpeta(m) && CarpetaDePaginas.esGrabacion(m))
        assertEquals("Clase 25-10 10h58", m.nombre)
        val texto = m.texto
        assertTrue(texto, texto.contains("\"tipo\":\"grabacion\""))
        assertTrue(texto, texto.contains("\"voz\":\"v\""))
        assertTrue(texto, texto.contains("\"duracionMs\":5400000"))
        assertTrue(texto, texto.contains("\"ms\":65000"))
        assertTrue(texto, texto.contains("\"video\":\"video\""))
        assertEquals("ni la nota ni el vídeo", listOf("f1"), ids(c.capturas()))
        // Byte a byte lo del PC: las claves en el orden de su `struct`.
        assertEquals(
            """{"formato":1,"tipo":"grabacion","voz":"v","video":"video","duracionMs":5400000,"paginas":[""" +
                """{"id":"v","titulo":"","ms":0},{"id":"video","titulo":"","ms":0},{"id":"f1","titulo":"⏱ 01:05","ms":65000}]}""",
            texto
        )
        val leida = CarpetaDePaginas.deMensaje(m)!!
        assertEquals(c, leida)
        assertEquals(65_000L, leida.paginas[2].ms)
        // Caso negativo: una carpeta de páginas no escribe nada de eso.
        val normal = CarpetaDePaginas.mensajeNuevo("Diario", 2, "p")
        assertFalse(CarpetaDePaginas.esGrabacion(normal))
        assertEquals("""{"formato":1,"paginas":[]}""", normal.texto)
    }

    @Test fun un_pc_de_antes_conserva_lo_de_la_grabacion_y_la_lee_como_carpeta() {
        val viejo = """{"formato":1,"paginas":[{"id":"a","titulo":"Día 1"}]}"""
        val c = CarpetaDePaginas.leer(viejo)
        assertFalse(c.esGrabacion)
        assertNull(c.paginas[0].ms)
        assertFalse("sin minuto no se escribe", c.escribir().contains("\"ms\""))
        assertEquals(viejo, c.escribir())
        val nuevo = """{"formato":1,"tipo":"grabacion","voz":"v","duracionMs":9,"paginas":[{"id":"v","titulo":"","ms":0}]}"""
        val g = CarpetaDePaginas.leer(nuevo)
        assertTrue(g.esGrabacion)
        assertEquals("v" to 9L, g.voz to g.duracionMs)
        assertEquals(g, CarpetaDePaginas.leer(g.escribir()))
        assertEquals(nuevo, g.escribir())
        // Y lo que aún no se entiende (de una versión más nueva) se sigue conservando.
        val masNuevo = """{"formato":1,"tipo":"grabacion","paginas":[{"id":"v","titulo":"","ms":0,"color":"rojo"}],"otro":true}"""
        assertEquals(masNuevo, CarpetaDePaginas.leer(masNuevo).escribir())
    }

    @Test fun lo_de_dentro_de_cualquier_carpeta_no_sale_suelto() {
        val g = CarpetaDePaginas.grabacion("v", 1000).anadirAlMinuto("v", "", 0)!!.anadirAlMinuto("f1", "", 500)!!
        val diario = CarpetaDePaginas().anadir("a", "Día 1")!!
        val mensajes = listOf(
            Mensaje(id = "a", cuando = 0, clase = Clase.DIBUJO, referencia = "dib-a"),
            Mensaje(id = "v", cuando = 1, clase = Clase.NOTA, texto = "v"),
            mensaje("Clase", g, 2),
            mensaje("Diario", diario, 3)
        )
        val dentro = CarpetaDePaginas.dentroDeCarpetas(mensajes)
        assertTrue("v" in dentro && "f1" in dentro)
        assertTrue("la página de un cuaderno, también", "a" in dentro)
        assertEquals(3, dentro.size)
        // Caso negativo: sin carpetas, nada está dentro.
        assertTrue(CarpetaDePaginas.dentroDeCarpetas(mensajes.take(2)).isEmpty())
    }

    @Test fun lo_adjuntado_a_la_clase_va_aparte_de_lo_grabado() {
        var c = CarpetaDePaginas.grabacion("v", 1000).anadirAlMinuto("v", "", 0)!!.anadirAlMinuto("f1", "⏱ 0:01", 1000)!!
        c = c.adjuntar("pdf", "Apuntes")!!
        assertNull("lo que ya estaba no entra", c.adjuntar("f1", "otra vez"))
        assertEquals(listOf("v", "f1"), ids(c.deLaGrabacion()))
        assertEquals(listOf("pdf"), ids(c.adjuntos()))
        assertEquals("un adjunto no es captura", listOf("f1"), ids(c.capturas()))
        val texto = c.escribir()
        assertTrue(texto, texto.contains("\"adjunto\":true"))
        assertEquals("solo en el adjunto", 1, Regex("adjunto").findAll(texto).count())
        assertEquals(c, CarpetaDePaginas.leer(texto))
    }

    /** `la_clase_entra_en_su_carpeta_en_el_orden_de_la_grabacion` de `grabar_clase.rs` (e015e2b, 40daac7). */
    @Test fun la_clase_entra_en_su_carpeta_en_el_orden_de_la_grabacion() {
        val c = GrabarClase.carpetaDeLaGrabacion(
            "v", 90_000, "mp4",
            listOf(GrabarClase.Enlace(65_000, "f2"), GrabarClase.Enlace(5_000, "f1"))
        )
        assertTrue(c.esGrabacion)
        assertEquals("v" to 90_000L, c.voz to c.duracionMs)
        assertEquals(listOf("v", "mp4", "f1", "f2"), ids(c.paginas))
        assertEquals("⏱ 1:05", c.paginas[3].titulo)
        assertEquals(65_000L, c.paginas[3].ms)
        assertEquals("la nota con su nombre", "", c.paginas[0].titulo)
        assertEquals("mp4", c.video)
        assertEquals(2, c.capturas().size)
        // Caso negativo: sin nota (no se pudo cerrar), solo las capturas.
        val sola = GrabarClase.carpetaDeLaGrabacion(null, 0, null, listOf(GrabarClase.Enlace(1, "f")))
        assertEquals("", sola.voz)
        assertEquals(1, sola.size)
        assertTrue(GrabarClase.carpetaDeLaGrabacion(null, 0, null, emptyList()).isEmpty())
    }

    /** `una_captura_nueva_entra_en_su_minuto_sin_repetirse` de `clase.rs` (8d7fe93). */
    @Test fun una_captura_nueva_entra_en_su_minuto_sin_repetirse() {
        val r = buildJsonArray {
            add(buildJsonObject { put("ms", JsonPrimitive(9_000)); put("id", JsonPrimitive("b")) })
            add(buildJsonObject { put("ms", JsonPrimitive(1_000)); put("id", JsonPrimitive("a")) })
        }
        val puesta = GrabarClase.ponerCaptura(r, 5_000, "c")!!
        fun lista(a: JsonArray) = GrabarClase.capturasDe(Mensaje(id = "n", cuando = 0, clase = Clase.VOZ, capturas = a)).map { it.ms to it.id }
        assertEquals(listOf(1_000L to "a", 5_000L to "c", 9_000L to "b"), lista(puesta))
        // Caso negativo: el mismo id no entra dos veces.
        assertNull(GrabarClase.ponerCaptura(puesta, 7_000, "c"))
        // Una nota sin capturas estrena la lista, con las claves como el PC.
        val vacia = GrabarClase.ponerCaptura(null, -5, "x")!!
        assertEquals(listOf(0L to "x"), lista(vacia))
        assertEquals("""[{"id":"x","ms":0}]""", vacia.toString())
    }

    /** `al_arrastrar_se_ve_donde_esta_el_raton` de `clase.rs` (8d7fe93). */
    @Test fun al_arrastrar_se_ve_donde_esta_el_dedo() {
        assertEquals(4_000L, EstudiarClase.posicionALaVista(27_000, 4_000))
        // Caso negativo: sin arrastrar manda el reproductor.
        assertEquals(27_000L, EstudiarClase.posicionALaVista(27_000, null))
    }

    /** La carpeta de una grabación se estudia y se comparte desde su nota (`clase_que_estudia` del PC, e1150e8). */
    @Test fun la_carpeta_de_una_grabacion_se_estudia_y_se_comparte_desde_su_nota() {
        val voz = Mensaje(id = "v", cuando = 0, clase = Clase.VOZ, grabacion = GrabarClase.CLASE, capturas = GrabarClase.capturasEnJson(emptyList()))
        val c = mensaje("Clase", CarpetaDePaginas.grabacion("v", 65_000).anadirAlMinuto("v", "", 0)!!, 5)
        val todos = listOf(voz, c)
        assertEquals("v", EstudiarClase.notaDe(c, todos)?.id)
        assertEquals("v", ExportarGrabacion.notaDe(c, todos)?.id)
        assertEquals("1:05 · 0 capturas", com.forge.pixpin.guardados.CarpetasDelChat.detalleDeGrabacion(CarpetaDePaginas.deMensaje(c)!!))
        // Caso negativo: una carpeta de páginas no se estudia.
        val normal = CarpetaDePaginas.mensajeNuevo("Diario", 6, "p")
        assertNull(EstudiarClase.notaDe(normal, todos + normal))
        assertNull(ExportarGrabacion.notaDe(normal, todos + normal))
    }
}
