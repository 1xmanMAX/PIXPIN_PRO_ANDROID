package com.forge.pixpin.ajustes

import com.forge.pixpin.ajustes.CatalogoDeAjustes.Seccion
import com.forge.pixpin.data.CaptureMode
import com.forge.pixpin.data.ModoNoche
import com.forge.pixpin.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ajustes v2: buscador, punto azul, restablecer y deshacer (como el PC, 8be72d8). */
class AjustesV2Test {

    @Test fun deFabricaNoHayNingunPuntoAzulYCadaOpcionLlevaSuExplicacion() {
        val s = Settings()
        for (o in CatalogoDeAjustes.OPCIONES) {
            assertFalse(o.id, CatalogoDeAjustes.cambiada(o, s))
            assertTrue(o.id, o.ayuda.isNotBlank() && o.claves.isNotEmpty())
        }
        assertEquals("ids sin repetir", CatalogoDeAjustes.OPCIONES.size, CatalogoDeAjustes.OPCIONES.map { it.id }.toSet().size)
        for (sec in Seccion.entries) assertTrue(sec.name, CatalogoDeAjustes.deSeccion(sec).isNotEmpty())
    }

    @Test fun cambiarPoneElPuntoAzulSoloEnLoSuyo() {
        val s = Settings(oledNegro = true, modoNoche = ModoNoche.OSCURO)
        val cambiadas = CatalogoDeAjustes.OPCIONES.filter { CatalogoDeAjustes.cambiada(it, s) }.map { it.id }
        assertEquals(listOf("noche", "oled"), cambiadas)
        assertEquals(listOf("modo_noche", "oled_negro"), CatalogoDeAjustes.clavesARestablecer(Seccion.ASPECTO, s))
        // Caso negativo: restablecer otra sección no toca estas.
        assertTrue(CatalogoDeAjustes.clavesARestablecer(Seccion.CAPTURAR, s).isEmpty())
        assertEquals(listOf("capture_mode"), CatalogoDeAjustes.clavesARestablecer(Seccion.CAPTURAR, Settings(captureMode = CaptureMode.DISCREET)))
    }

    @Test fun lasBarrasYLaListaGeneralSonPuntosDistintos() {
        val s = Settings(herramientasApagadas = setOf("ARROW"))
        val ids = CatalogoDeAjustes.OPCIONES.filter { CatalogoDeAjustes.cambiada(it, s) }.map { it.id }
        assertEquals("apagar en todos no es tocar cada barra", listOf("herramientas"), ids)
        val pin = Settings(pinTools = setOf("ARROW"))
        assertTrue(CatalogoDeAjustes.cambiada(CatalogoDeAjustes.porId("barra-pin")!!, pin))
        assertFalse(CatalogoDeAjustes.cambiada(CatalogoDeAjustes.porId("barra-lienzo")!!, pin))
    }

    @Test fun elBuscadorEncuentraSinTildesEnTodasLasSeccionesYAfina() {
        val r = CatalogoDeAjustes.buscar("compresion")
        assertEquals(Seccion.PDF, r.single().first)
        assertTrue(CatalogoDeAjustes.buscar("IMÁN").flatMap { it.second }.any { it.id == "iman" })
        assertTrue(CatalogoDeAjustes.buscar("barra").flatMap { it.second }.size >= 4)
        assertEquals(listOf("barra-pin"), CatalogoDeAjustes.buscar("barra flotante").flatMap { it.second }.map { it.id })
        // Casos negativos: vacío no da nada, y una palabra que no está tampoco.
        assertTrue(CatalogoDeAjustes.buscar("   ").isEmpty())
        assertTrue(CatalogoDeAjustes.buscar("barra zzzz").isEmpty())
    }

    @Test fun lasClavesSonLasDelAlmacen() {
        // Las mismas que escribe SettingsRepository: si se renombran, el restablecer no haría nada.
        val conocidas = setOf(
            "capture_mode", "copy_format", "compresion_pdf", "guia_editor", "guia_pin", "guia_capa", "guia_captura",
            "iman_activo", "iman_esquinas", "iman_medios", "iman_centros", "iman_intersecciones", "iman_eje",
            "iman_borde_guia", "iman_borde_figura", "zurdo", "herramientas_apagadas", "editor_tools", "editor_groups",
            "pin_tools", "pin_groups", "capa_tools", "capa_groups", "lector_tools", "lector_groups", "palabras_magicas",
            "motor_de_voz", "idioma_de_voz", "segundo_idioma_de_voz", "modelo_whisper", "modo_de_idiomas",
            "maxima_fluidez", "modo_noche", "oled_negro", "pin_font"
        )
        assertEquals(conocidas, CatalogoDeAjustes.CLAVES)
    }

    @Test fun deshacerVuelvePasoAPasoYLoQueNoCambiaNoCuenta() {
        val h = HistorialDeAjustes(setOf("a", "b"), tope = 3)
        h.observar(mapOf("a" to 1))
        assertFalse(h.hayAlgo)
        h.observar(mapOf("a" to 1, "otra" to 9)) // lo de fuera de la pantalla no cuenta
        assertFalse(h.hayAlgo)
        h.observar(mapOf("a" to 2))
        h.observar(mapOf("a" to 2, "b" to true))
        assertEquals(mapOf("a" to 2), h.deshacer())
        // Lo restaurado llega por el almacén y no se apunta como cambio nuevo.
        h.observar(mapOf("a" to 2))
        assertEquals(mapOf("a" to 1), h.deshacer())
        h.observar(mapOf("a" to 1))
        assertFalse(h.hayAlgo)
        assertNull(h.deshacer())
    }

    @Test fun elHistorialTieneTope() {
        val h = HistorialDeAjustes(setOf("a"), tope = 3)
        for (i in 0..10) h.observar(mapOf("a" to i))
        var n = 0
        while (h.deshacer() != null) n++
        assertEquals(3, n)
    }
}
