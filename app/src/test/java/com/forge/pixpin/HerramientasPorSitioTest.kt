package com.forge.pixpin.motor

import com.forge.pixpin.data.Settings
import com.forge.pixpin.motor.HerramientasPorSitio.Reparto
import com.forge.pixpin.motor.HerramientasPorSitio.Sitio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué herramientas salen en cada sitio y la lista general. Las reglas son las del PC (55db60d). */
class HerramientasPorSitioTest {

    private val fabrica = Settings().reparto

    @Test fun deFabricaNoHayApagadasYCadaSitioLlevaLoSuyo() {
        assertTrue(fabrica.apagadas.isEmpty())
        assertEquals(PIN_TOOLS_POR_DEFECTO, fabrica.visibles(Sitio.PIN))
        assertEquals(Settings().editorToolSet, fabrica.visibles(Sitio.LIENZO))
        // Caso negativo: lo que el pin no lleva de fábrica no sale ahí.
        assertFalse(fabrica.activaEn(Sitio.PIN, Tool.LASSO))
    }

    @Test fun apagarEnUnSitioNoLaQuitaDeLosDemasNiDeLaGeneral() {
        val r = fabrica.alternarEn(Sitio.PIN, Tool.MOSAIC)
        assertFalse(r.activaEn(Sitio.PIN, Tool.MOSAIC))
        assertTrue(r.activaEn(Sitio.PANTALLA, Tool.MOSAIC))
        assertTrue(r.activa(Tool.MOSAIC))
    }

    @Test fun apagadaEnTodosNoSaleEnNingunSitioAunqueSuBarraLaTenga() {
        val r = fabrica.alternarEnTodos(Tool.ARROW)
        for (s in Sitio.entries) assertFalse(s.name, r.activaEn(s, Tool.ARROW))
        assertTrue("la barra la sigue teniendo", Tool.ARROW in r.enSitio(Sitio.PIN))
        // Y volver a encenderla la devuelve donde estaba.
        assertTrue(r.alternarEnTodos(Tool.ARROW).activaEn(Sitio.PIN, Tool.ARROW))
    }

    @Test fun encenderEnUnSitioUnaApagadaEnTodosLaDejaSoloAhi() {
        val r = fabrica.alternarEnTodos(Tool.TEXT).alternarEn(Sitio.PIN, Tool.TEXT)
        assertTrue(r.activa(Tool.TEXT))
        assertTrue(r.activaEn(Sitio.PIN, Tool.TEXT))
        for (s in Sitio.entries.filter { it != Sitio.PIN }) assertFalse(s.name, r.activaEn(s, Tool.TEXT))
    }

    @Test fun reordenarUnaBarraConservaLoApagadoEnTodos() {
        val r = fabrica.alternarEnTodos(Tool.ARROW)
        val visibles = r.visibles(Sitio.PIN) - Tool.TEXT
        val despues = r.conBarra(Sitio.PIN, visibles)
        assertTrue("sigue guardada para cuando se encienda", Tool.ARROW in despues.enSitio(Sitio.PIN))
        assertFalse(despues.activaEn(Sitio.PIN, Tool.TEXT))
        // Caso negativo: meter en la barra una apagada en todos la enciende solo ahí.
        val metida = r.conBarra(Sitio.LECTOR, r.visibles(Sitio.LECTOR) + Tool.ARROW)
        assertTrue(metida.activaEn(Sitio.LECTOR, Tool.ARROW))
        assertFalse(metida.activaEn(Sitio.PIN, Tool.ARROW))
    }

    @Test fun losAjustesRestanLaListaGeneralEnCadaBarra() {
        val s = Settings(herramientasApagadas = setOf("ARROW", "NO_EXISTE"))
        assertFalse(Tool.ARROW in s.pinToolSet)
        assertFalse(Tool.ARROW in s.editorToolSet)
        assertFalse(Tool.ARROW in s.capaToolSet)
        assertFalse(Tool.ARROW in s.lectorToolSet)
        assertFalse(s.pinGroupList.flatten().contains(Tool.ARROW))
        // Caso negativo: un nombre que ya no existe no rompe nada ni quita otra.
        assertEquals(PIN_TOOLS_POR_DEFECTO - Tool.ARROW, s.pinToolSet)
    }

    @Test fun losNombresVanYVuelven() {
        val t = setOf(Tool.ARROW, Tool.ZONA)
        assertEquals(t, HerramientasPorSitio.leer(HerramientasPorSitio.escribir(t)))
        assertTrue(HerramientasPorSitio.leer(null).isEmpty())
        assertEquals(PIN_TOOLS_POR_DEFECTO, Reparto(emptySet(), emptyMap()).visibles(Sitio.PIN))
    }
}
