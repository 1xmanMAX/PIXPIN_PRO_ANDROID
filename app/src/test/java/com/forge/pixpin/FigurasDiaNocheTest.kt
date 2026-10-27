package com.forge.pixpin

import com.forge.pixpin.motor.Bounds
import com.forge.pixpin.motor.DrawTheme
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.FiguraGuardada
import com.forge.pixpin.motor.FigurasDeSerie
import com.forge.pixpin.motor.PapelDeLaHoja
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.VistaPreviaDeFigura
import com.forge.pixpin.motor.estampar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **El interruptor de día y noche del panel de figuras** (27-oct-2026): de día sin las gemelas
 * «(noche)» del PC, de noche cada una en el sitio de su plantilla de día, y buscando lo mismo.
 */
class FigurasDiaNocheTest {
    private companion object {
        val todas: List<FiguraGuardada> by lazy {
            listOf("src/main/assets/figuras/serie.json.gz", "app/src/main/assets/figuras/serie.json.gz")
                .map(::File).first { it.exists() }.inputStream().use(FigurasDeSerie::leer)
        }
    }

    private fun f(id: String, nombre: String, propia: Boolean = false) = FiguraGuardada(id, nombre, propia = propia)

    @Test fun el_catalogo_trae_las_28_gemelas_de_noche_del_pc_cada_una_con_su_de_dia() {
        val deNoche = todas.filter(FigurasDeSerie::esDeNoche)
        assertEquals(28, deNoche.size)
        val ids = todas.map { it.id }.toSet()
        assertTrue(deNoche.all { it.id.removeSuffix(FigurasDeSerie.SUFIJO_NOCHE) in ids })
        assertTrue(deNoche.all { it.nombre.endsWith(FigurasDeSerie.MARCA_NOCHE) })
    }

    @Test fun una_mesita_de_noche_no_es_una_plantilla_de_noche() {
        val mesita = todas.first { it.id == "serie-planta-mesita-redonda" }
        assertFalse(FigurasDeSerie.esDeNoche(mesita))
        assertNull(FigurasDeSerie.gemelaDeNoche(mesita, todas))
    }

    @Test fun de_dia_no_sale_ninguna_de_noche() {
        val dia = FigurasDeSerie.segunElModo(todas, noche = false)
        assertEquals(todas.size - 28, dia.size)
        assertTrue(dia.none(FigurasDeSerie::esDeNoche))
    }

    @Test fun de_noche_cada_gemela_va_en_el_sitio_de_su_de_dia_y_lo_demas_se_queda() {
        val dia = FigurasDeSerie.segunElModo(todas, noche = false)
        val noche = FigurasDeSerie.segunElModo(todas, noche = true)
        assertEquals(dia.size, noche.size)
        for ((d, n) in dia.zip(noche)) {
            if (n.id != d.id) assertEquals(d.id + FigurasDeSerie.SUFIJO_NOCHE, n.id)
        }
        assertEquals(28, noche.count(FigurasDeSerie::esDeNoche))
        // Las de sin gemela salen igual: un símbolo de electrónica es el mismo de día y de noche.
        assertEquals(
            dia.filter { it.nombre.startsWith("Electrónica") }.map { it.id },
            noche.filter { it.nombre.startsWith("Electrónica") }.map { it.id }
        )
        assertEquals("serie-diario-noche", FigurasDeSerie.gemelaDeNoche(todas.first { it.id == "serie-diario" }, todas)?.id)
    }

    @Test fun las_categorias_siguen_en_el_orden_del_pc_en_los_dos_modos() {
        for (modo in listOf(false, true)) {
            assertEquals(
                FigurasDeSerie.CATEGORIAS,
                FigurasDeSerie.porCategoria(FigurasDeSerie.segunElModo(todas, modo)).map { it.first }
            )
        }
    }

    @Test fun buscando_se_respeta_el_modo_y_no_cuentan_las_tildes() {
        val dia = FigurasDeSerie.buscar(FigurasDeSerie.segunElModo(todas, false), "habitos")
        assertEquals(listOf("serie-habitos"), dia.map { it.id })
        val noche = FigurasDeSerie.buscar(FigurasDeSerie.segunElModo(todas, true), "HÁBITOS mes")
        assertEquals(listOf("serie-habitos-noche"), noche.map { it.id })
        assertEquals(FigurasDeSerie.segunElModo(todas, false), FigurasDeSerie.buscar(FigurasDeSerie.segunElModo(todas, false), "  "))
    }

    @Test fun una_propia_con_su_gemela_por_el_nombre_tambien_cambia() {
        val mias = listOf(f("a", "Mi hoja", true), f("b", "Mi hoja (noche)", true), f("c", "Otra", true))
        assertEquals(listOf("a", "c"), FigurasDeSerie.segunElModo(mias, false).map { it.id })
        assertEquals(listOf("b", "c"), FigurasDeSerie.segunElModo(mias, true).map { it.id })
    }

    @Test fun una_de_noche_sin_su_de_dia_sale_solo_de_noche() {
        val l = listOf(f("serie-x-noche", "Diario · X (noche)"), f("serie-y", "Diario · Y"))
        assertEquals(listOf("serie-y"), FigurasDeSerie.segunElModo(l, false).map { it.id })
        assertEquals(listOf("serie-y", "serie-x-noche"), FigurasDeSerie.segunElModo(l, true).map { it.id })
    }
}

/**
 * **Una hoja lleva su propio papel** (27-oct-2026): sus piezas se pintan como se diseñaron y lo de
 * encima se adapta a la hoja, no al lienzo. Ver [PapelDeLaHoja].
 */
class PapelDeLaHojaTest {
    private companion object {
        val todas: List<FiguraGuardada> by lazy {
            listOf("src/main/assets/figuras/serie.json.gz", "app/src/main/assets/figuras/serie.json.gz")
                .map(::File).first { it.exists() }.inputStream().use(FigurasDeSerie::leer)
        }
        val PIZARRA = DrawTheme.colorDe(DrawTheme.FONDO_NOCHE)
        val BLANCO = 0xFFFFFFFF.toInt()
        val NEGRO = 0xFF000000.toInt()
    }

    private fun estampada(id: String) = todas.first { it.id == id }.let { estampar(it, Pt(it.caja.width / 2, it.caja.height / 2)) }

    private fun trazo(id: String, x: Double, y: Double, color: String = "#ffffff") =
        Element(id = id, type = ElementType.RECTANGLE, x = x, y = y, width = 40.0, height = 10.0, seed = 1, strokeColor = color)

    private fun color(hex: String) = DrawTheme.colorDe(hex)

    @Test fun las_piezas_de_una_plantilla_estampada_se_pintan_como_se_disenaron() {
        val hoja = estampada("serie-diario-noche")
        val mapa = PapelDeLaHoja.de(hoja)
        assertEquals(hoja.filter { !it.isDeleted }.map { it.id }.toSet(), mapa.comoSeDiseno)
        // Su papel azul noche, sobre la pizarra: igual que lo diseñó el PC (antes se aclaraba).
        val papel = color("#1b2030")
        assertEquals(papel, PapelDeLaHoja.tinta(mapa, hoja.first().id, papel, PIZARRA))
        assertNotEquals("sin la hoja se aclaraba", papel, DrawTheme.adaptar(papel, PIZARRA))
    }

    @Test fun una_hoja_de_dia_en_el_lienzo_de_noche_no_sale_en_negativo() {
        val hoja = estampada("serie-diario")
        val mapa = PapelDeLaHoja.de(hoja)
        val pagina = hoja.first()
        assertEquals(BLANCO, PapelDeLaHoja.tinta(mapa, pagina.id, BLANCO, PIZARRA))
        // La tinta oscura de la hoja se queda oscura sobre su página blanca (antes se aclaraba
        // contra la pizarra y quedaba clara sobre blanco).
        val tinta = color("#22304a")
        assertEquals(tinta, PapelDeLaHoja.tinta(mapa, pagina.id, tinta, PIZARRA))
        assertNotEquals(tinta, DrawTheme.adaptar(tinta, PIZARRA))
        // Cada tinta de la hoja, la suya: ninguna se da la vuelta.
        for (e in hoja) {
            val c = color(e.strokeColor)
            assertEquals(e.strokeColor, c, PapelDeLaHoja.tinta(mapa, e.id, c, PIZARRA))
        }
    }

    @Test fun lo_escrito_encima_se_adapta_a_la_hoja_y_lo_de_fuera_al_lienzo() {
        val hoja = estampada("serie-diario")
        val caja = hoja.first().let { Bounds(it.x, it.y, it.x + it.width, it.y + it.height) }
        val encima = trazo("encima", caja.midX, caja.midY)
        val fuera = trazo("fuera", caja.x2 + 500, caja.y2 + 500)
        val mapa = PapelDeLaHoja.de(hoja + encima + fuera)
        assertEquals(BLANCO, mapa.papelDe["encima"])
        assertNull(mapa.papelDe["fuera"])
        // El rotulador blanco del lienzo de noche, sobre la página blanca: oscuro, se lee.
        val sobreLaHoja = PapelDeLaHoja.tinta(mapa, "encima", BLANCO, PIZARRA)
        assertTrue(DrawTheme.contraste(DrawTheme.luminancia(sobreLaHoja), DrawTheme.luminancia(BLANCO)) >= DrawTheme.CONTRASTE_BUSCADO)
        // Fuera de la hoja, como siempre.
        assertEquals(DrawTheme.adaptar(NEGRO, PIZARRA), PapelDeLaHoja.tinta(mapa, "fuera", NEGRO, PIZARRA))
    }

    @Test fun lo_que_queda_debajo_de_la_hoja_no_va_sobre_ella() {
        val hoja = estampada("serie-diario")
        val p = hoja.first()
        val debajo = trazo("debajo", p.x + p.width / 2, p.y + p.height / 2)
        val mapa = PapelDeLaHoja.de(listOf(debajo) + hoja)
        assertNull(mapa.papelDe["debajo"])
    }

    @Test fun sin_grupo_o_sin_pagina_opaca_no_hay_hoja() {
        val rect = Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 100.0, height = 100.0, seed = 1, backgroundColor = "#ffffff")
        val t = trazo("t", 50.0, 50.0)
        assertTrue(PapelDeLaHoja.de(listOf(rect, t)).comoSeDiseno.isEmpty())
        val g = listOf("g")
        assertTrue("translúcida", PapelDeLaHoja.de(listOf(rect.copy(groupIds = g, opacity = 50), t.copy(groupIds = g))).comoSeDiseno.isEmpty())
        assertTrue("sin relleno", PapelDeLaHoja.de(listOf(rect.copy(groupIds = g, backgroundColor = Element.TRANSPARENT), t.copy(groupIds = g))).comoSeDiseno.isEmpty())
        assertTrue("no lo tapa todo", PapelDeLaHoja.de(listOf(rect.copy(groupIds = g), t.copy(groupIds = g, x = 300.0))).comoSeDiseno.isEmpty())
        assertEquals(setOf("r", "t"), PapelDeLaHoja.de(listOf(rect.copy(groupIds = g), t.copy(groupIds = g))).comoSeDiseno)
    }

    @Test fun en_un_simbolo_lo_de_dentro_del_cuerpo_blanco_no_se_vuelve_blanco() {
        val simbolo = estampada("serie-elec-fuente-corriente-ca")
        val mapa = PapelDeLaHoja.de(simbolo)
        assertTrue("no es una hoja", mapa.comoSeDiseno.isEmpty())
        val cuerpo = simbolo.first { it.type == ElementType.ELLIPSE }
        val grafito = color("#2b2f3a")
        // El cuerpo y las patas van sobre el lienzo: se aclaran.
        assertNull(mapa.papelDe[cuerpo.id])
        assertNotEquals(grafito, PapelDeLaHoja.tinta(mapa, cuerpo.id, grafito, PIZARRA))
        // Lo de dentro del cuerpo (la onda, la flecha), contra su blanco: se queda oscuro.
        val dentro = simbolo.filter { mapa.papelDe[it.id] == BLANCO }
        assertTrue(dentro.isNotEmpty())
        for (e in dentro) assertEquals(grafito, PapelDeLaHoja.tinta(mapa, e.id, grafito, PIZARRA))
    }

    @Test fun fuera_de_una_pieza_la_tinta_va_contra_el_lienzo() {
        assertEquals(DrawTheme.adaptar(NEGRO, PIZARRA), PapelDeLaHoja.tinta(PapelDeLaHoja.Mapa.VACIO, null, NEGRO, PIZARRA))
    }
}

/** **La vista rápida de una figura**: encajar, pellizcar y el doble toque. Ver [VistaPreviaDeFigura]. */
class VistaPreviaDeFiguraTest {
    private val caja = Bounds(0.0, 0.0, 800.0, 1100.0)

    @Test fun encajada_cabe_entera_y_centrada() {
        val v = VistaPreviaDeFigura.encajar(caja, 1080.0, 1800.0)
        val a = v.toScreen(Pt(0.0, 0.0))
        val b = v.toScreen(Pt(800.0, 1100.0))
        assertTrue(a.x >= VistaPreviaDeFigura.MARGEN - 1e-6 && a.y >= VistaPreviaDeFigura.MARGEN - 1e-6)
        assertTrue(b.x <= 1080.0 - VistaPreviaDeFigura.MARGEN + 1e-6 && b.y <= 1800.0 - VistaPreviaDeFigura.MARGEN + 1e-6)
        assertEquals(540.0, (a.x + b.x) / 2, 1e-6)
        assertEquals(900.0, (a.y + b.y) / 2, 1e-6)
    }

    @Test fun al_pellizcar_lo_de_debajo_de_los_dedos_se_queda_debajo() {
        val e = VistaPreviaDeFigura.encajar(caja, 1080.0, 1800.0)
        val dedos = Pt(300.0, 700.0)
        val antes = e.toScene(dedos.x, dedos.y)
        val v = VistaPreviaDeFigura.pellizcar(e, e, 2.0, dedos, 0.0, 0.0)
        assertEquals(e.zoom * 2, v.zoom, 1e-9)
        val despues = v.toScene(dedos.x, dedos.y)
        assertEquals(antes.x, despues.x, 1e-6)
        assertEquals(antes.y, despues.y, 1e-6)
        // Y arrastrando, el punto viaja con los dedos.
        val movida = VistaPreviaDeFigura.pellizcar(v, e, 1.0, dedos, 50.0, -20.0)
        val p = movida.toScene(dedos.x + 50, dedos.y - 20)
        assertEquals(antes.x, p.x, 1e-6)
        assertEquals(antes.y, p.y, 1e-6)
    }

    @Test fun el_aumento_tiene_topes_respecto_a_lo_encajado() {
        val e = VistaPreviaDeFigura.encajar(caja, 1080.0, 1800.0)
        val lejos = VistaPreviaDeFigura.pellizcar(e, e, 0.01, Pt(0.0, 0.0), 0.0, 0.0)
        assertEquals(e.zoom * VistaPreviaDeFigura.AUMENTO_MINIMO, lejos.zoom, 1e-9)
        val cerca = VistaPreviaDeFigura.pellizcar(e, e, 1000.0, Pt(0.0, 0.0), 0.0, 0.0)
        assertEquals(e.zoom * VistaPreviaDeFigura.AUMENTO_MAXIMO, cerca.zoom, 1e-9)
    }

    @Test fun el_doble_toque_acerca_donde_se_toca_y_el_segundo_vuelve_a_encajar() {
        val e = VistaPreviaDeFigura.encajar(caja, 1080.0, 1800.0)
        val donde = Pt(200.0, 400.0)
        val cerca = VistaPreviaDeFigura.dobleToque(e, e, donde)
        assertEquals(e.zoom * VistaPreviaDeFigura.AUMENTO_DEL_DOBLE_TOQUE, cerca.zoom, 1e-9)
        assertEquals(e.toScene(donde.x, donde.y).x, cerca.toScene(donde.x, donde.y).x, 1e-6)
        assertFalse(VistaPreviaDeFigura.estaEncajada(cerca, e))
        assertEquals(e, VistaPreviaDeFigura.dobleToque(cerca, e, donde))
    }

    @Test fun sin_tamano_no_se_rompe() {
        assertEquals(1.0, VistaPreviaDeFigura.encajar(caja, 0.0, 0.0).zoom, 0.0)
    }
}
