package com.forge.pixpin

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.FigurasDeSerie
import com.forge.pixpin.motor.FiguraGuardada
import com.forge.pixpin.motor.HojaDePlantilla
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.estampar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Las pruebas de `hoja_de_plantilla.rs` y `exportar::hojas_de_cuaderno` del PC, portadas**
 * (25-oct-2026), con las plantillas de serie de verdad (`assets/figuras/serie.json.gz`).
 */
class HojaDePlantillaTest {
    private companion object {
        val todas: List<FiguraGuardada> by lazy {
            listOf("src/main/assets/figuras/serie.json.gz", "app/src/main/assets/figuras/serie.json.gz")
                .map(::File).first { it.exists() }.inputStream().use(FigurasDeSerie::leer)
        }
        val catalogo by lazy { HojaDePlantilla.Catalogo(todas) }
    }

    private fun deSerie(clave: String) = todas.first { it.id == FigurasDeSerie.PREFIJO_ID + clave }

    /** Estampada con su esquina en ([x], [y]), como la estampa el editor: agrupada y con ids nuevos. */
    private fun estampada(f: FiguraGuardada, x: Double = 0.0, y: Double = 0.0): List<Element> =
        estampar(f, Pt(x + f.caja.width / 2, y + f.caja.height / 2))

    private var siguiente = 0
    private fun trazo(x: Double, y: Double, w: Double = 40.0, h: Double = 10.0) =
        Element(id = "t${siguiente++}", type = ElementType.RECTANGLE, x = x, y = y, width = w, height = h, seed = 1)

    private fun vivos(f: FiguraGuardada) = f.elementos.count { !it.isDeleted }

    @Test fun el_catalogo_conoce_las_hojas_de_serie_por_su_titulo() {
        assertTrue(catalogo.porTitulo.getValue("Diario").any { it.id == "serie-diario" })
        assertTrue("las dos del storyboard se titulan igual", catalogo.porTitulo.getValue("Storyboard").size >= 2)
    }

    @Test fun la_plantilla_estampada_se_encuentra_con_lo_escrito_encima_aparte() {
        val f = deSerie("diario")
        val piezas = estampada(f)
        val encima = trazo(100.0, 200.0)
        val v = HojaDePlantilla.plantillas(piezas + encima, catalogo)
        assertEquals("una sola hoja", 1, v.size)
        assertTrue("es de serie", v[0].deSerie.isNotEmpty())
        assertEquals(piezas.size, v[0].ids.size)
        assertFalse("lo escrito no es de la plantilla", encima.id in v[0].ids)
        assertTrue(v[0].caja.toString(), v[0].caja.width > 500 && v[0].caja.height > 500)
    }

    @Test fun la_pagina_siguiente_es_la_plantilla_sin_lo_escrito() {
        val f = deSerie("diario")
        val escena = Scene(elements = estampada(f) + trazo(100.0, 200.0) + trazo(150.0, 260.0), backgroundColor = "#fdf6e3")
        val nueva = HojaDePlantilla.lienzoSiguiente(escena, catalogo)
        assertNotNull("tiene plantilla", nueva)
        assertEquals("solo las piezas de la plantilla", vivos(f), nueva!!.visible.size)
        assertEquals("con el mismo papel", "#fdf6e3", nueva.backgroundColor)
        assertEquals("y se vuelve a reconocer", 1, HojaDePlantilla.plantillas(nueva.elements, catalogo).size)
    }

    @Test fun un_texto_tecleado_dentro_de_la_plantilla_no_pasa_a_la_siguiente() {
        val f = deSerie("diario")
        val piezas = estampada(f).map {
            // El usuario escribió dentro de un texto pequeño de la plantilla (no el título).
            if (it.type == ElementType.TEXT && (it.fontSize ?: 0.0) < 30.0 && it.text?.isNotBlank() == true) it.copy(text = "Lo que pasó el lunes") else it
        }
        val vacia = HojaDePlantilla.vacia(piezas, catalogo)!!
        assertEquals("es la de serie, tal cual", "serie-diario", vacia.id)
        assertFalse(vacia.elementos.any { it.text?.contains("lunes") == true })
    }

    @Test fun de_las_gemelas_sale_la_del_mismo_papel() {
        val noche = todas.firstOrNull { it.id == "serie-diario-noche" } ?: return
        val vacia = HojaDePlantilla.vacia(estampada(noche), catalogo)!!
        assertEquals("serie-diario-noche", vacia.id)
    }

    @Test fun una_retocada_sale_con_sus_piezas_sin_lo_escrito() {
        val f = deSerie("diario")
        val piezas = estampada(f).filterIndexed { i, _ -> i !in 10..14 }
        val vacia = HojaDePlantilla.vacia(piezas + trazo(100.0, 200.0), catalogo)!!
        assertFalse(vacia.id.startsWith(FigurasDeSerie.PREFIJO_ID))
        assertEquals(piezas.size, vacia.elementos.size)
        assertTrue("sin el grupo de la estampación", vacia.elementos.all { it.groupIds.isEmpty() })
    }

    @Test fun varias_hojas_salen_en_orden_de_lectura() {
        val f = deSerie("planificador-semanal")
        // La segunda, debajo; la primera, arriba (se estampan al revés).
        val piezas = estampada(f, 0.0, f.caja.height + 100.0) + estampada(f)
        val v = HojaDePlantilla.plantillas(piezas, catalogo)
        assertEquals(2, v.size)
        assertTrue("la de arriba primero", v[0].caja.y1 < v[1].caja.y1)
    }

    @Test fun sin_plantilla_no_hay_hoja_ni_pagina_siguiente() {
        val escena = Scene(elements = listOf(trazo(0.0, 0.0), trazo(30.0, 40.0)))
        assertTrue(HojaDePlantilla.plantillas(escena.elements, catalogo).isEmpty())
        assertNull(HojaDePlantilla.lienzoSiguiente(escena, catalogo))
        // Caso negativo: un recuadro pequeño agrupado con algo dentro no es una hoja (un símbolo).
        val caja = trazo(0.0, 0.0, 100.0, 80.0).copy(groupIds = listOf("g1"))
        val dentro = trazo(10.0, 10.0).copy(groupIds = listOf("g1"))
        assertTrue(HojaDePlantilla.plantillas(listOf(caja, dentro), catalogo).isEmpty())
        // Pero uno grande y propio, sí.
        val grande = trazo(0.0, 0.0, 600.0, 800.0).copy(groupIds = listOf("g2"))
        val suyo = trazo(50.0, 50.0).copy(groupIds = listOf("g2"))
        assertEquals(1, HojaDePlantilla.plantillas(listOf(grande, suyo), catalogo).size)
        // Y una de serie desagrupada se reconoce por su título.
        val suelta = estampada(deSerie("diario")).map { it.copy(groupIds = emptyList()) }
        assertEquals(1, HojaDePlantilla.plantillas(suelta, catalogo).size)
        // Caso negativo: sin catálogo, una desagrupada no es hoja.
        assertTrue(HojaDePlantilla.plantillas(suelta, HojaDePlantilla.Catalogo.VACIO).isEmpty())
    }

    @Test fun en_el_cuaderno_manda_la_plantilla_luego_el_marco_y_si_no_todo() {
        // Sin plantilla ni marcos: todo.
        val suelto = Scene(elements = listOf(trazo(10.0, 20.0, 100.0, 50.0)))
        val h0 = HojaDePlantilla.hojasDeCuaderno(suelto, catalogo)
        assertEquals(1, h0.size)
        assertFalse(h0[0].aRas)
        assertEquals(10.0, h0[0].caja.x1, 0.5)
        // Con un marco: el marco, y no lo de fuera.
        val marco = Element(id = "m", type = ElementType.FRAME, x = 0.0, y = 0.0, width = 200.0, height = 200.0, seed = 1)
        val dentro = trazo(20.0, 20.0, 50.0, 50.0)
        val fuera = trazo(900.0, 900.0, 50.0, 50.0)
        val h1 = HojaDePlantilla.hojasDeCuaderno(Scene(elements = listOf(marco, dentro, fuera)), catalogo)
        assertEquals(1, h1.size)
        assertEquals(200.0, h1[0].caja.width, 0.5)
        assertTrue(h1[0].contenido.any { it.id == dentro.id })
        assertFalse(h1[0].contenido.any { it.id == fuera.id })
        // Con una plantilla de serie y algo escrito lejos: solo su página, a ras.
        val f = deSerie("planificador-semanal")
        val lejos = trazo(5000.0, 5000.0, 50.0, 50.0)
        val h2 = HojaDePlantilla.hojasDeCuaderno(Scene(elements = estampada(f) + lejos), catalogo)
        assertEquals(1, h2.size)
        assertTrue(h2[0].aRas)
        assertTrue(h2[0].caja.x2 < 2000 && h2[0].caja.y2 < 2000)
        assertFalse(h2[0].contenido.any { it.id == lejos.id })
    }
}
