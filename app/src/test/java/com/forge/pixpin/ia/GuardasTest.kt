package com.forge.pixpin.ia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Las guardas del turno** (pruebas de `guardas.rs` del PC, portadas). */
class GuardasTest {

    private val resumenConPlantilla = "\n\n[Lienzo destino: 120 elementos (coordenadas como en dibujar_en_lienzo).\n" +
        "${Guardas.MARCA_PLANTILLAS}: «Cronograma». Para agrandarlas…\n#1 rectangulo]"
    private val resumenConAlgo = "\n\n[Lienzo destino: 3 elementos (coordenadas como en dibujar_en_lienzo).\n#1 rectangulo]"

    private fun svg(): Llamada = Herramientas.validar(
        LlamadaCruda("1", Herramientas.SVG, """{"svg":"<svg viewBox='0 0 10 10'><rect x='1' y='1' width='5' height='5' fill='red'/></svg>"}"""),
        Herramientas.catalogo()
    ).llamada

    @Test fun `sin anexos deja solo lo que escribio`() {
        val entero = "agrándala a 30 actividades y 30 semanas$resumenConPlantilla\n\n(Ahora: sáb 24-oct-2026 22:05)"
        assertEquals("agrándala a 30 actividades y 30 semanas", Guardas.sinAnexos(entero))
        assertEquals("dibuja una casa", Guardas.sinAnexos("dibuja una casa"))
        // Con el resumen de verdad del teléfono.
        val r = LienzoDestino.conResumen("agranda el cronograma", LienzoDestino.resumen(emptyList()))
        assertTrue(r.contains(Guardas.MARCA_RESUMEN))
        assertFalse("vacio no es «con algo»", Guardas.lienzoConAlgo(r))
        assertEquals("agranda el cronograma", Guardas.sinAnexos(r))
    }

    @Test fun `agrandar lo que hay no pone otra plantilla ni otro dibujo al lado`() {
        val pedido = "agrándala a 30 actividades y 30 semanas$resumenConPlantilla"
        val error = Guardas.duplicaria(pedido, Llamada.Figuras(PedidoFiguras.Poner("cronograma")))
        assertNotNull(error)
        assertTrue(error!!, error.startsWith("ERROR") && error.contains("preguntar_usuario"))
        assertNotNull(Guardas.duplicaria("amplía esta figura$resumenConAlgo", svg()))
        // Dibujar piezas sueltas no se para; la lista de la biblioteca tampoco.
        assertNull(Guardas.duplicaria(pedido, Llamada.Dibujar(emptyList())))
        assertNull(Guardas.duplicaria(pedido, Llamada.Figuras(PedidoFiguras.Lista)))
    }

    @Test fun `la guarda no estorba lo que si es nuevo`() {
        assertNull(Guardas.duplicaria("agranda el cronograma\n\n[Lienzo destino: vacio.]", svg()))
        assertNull("sin resumen no se adivina", Guardas.duplicaria("agranda el cronograma", svg()))
        assertNull(Guardas.duplicaria("haz otro cronograma más grande al lado$resumenConPlantilla", Llamada.Figuras(PedidoFiguras.Poner("cronograma"))))
        assertNull(Guardas.duplicaria("dibuja una casa$resumenConAlgo", svg()))
        // Poner una figura sin plantilla puesta es añadir, no duplicar.
        assertNull(Guardas.duplicaria("amplía esta figura$resumenConAlgo", Llamada.Figuras(PedidoFiguras.Poner("árbol"))))
    }

    @Test fun `anunciar no es acabar`() {
        assertTrue(Guardas.anunciaPendiente("Todavía no está listo el cronograma de 19 semanas y 30 actividades, quedó a medias y desordenado… ahora voy a limpiar el resto y hacerlo por completo, alineado y revisado"))
        assertTrue(Guardas.anunciaPendiente("Agrandé las filas. A continuación ajusto las semanas."))
        assertTrue(Guardas.anunciaPendiente("Procedo a numerar las actividades."))
        assertTrue(Guardas.anunciaPendiente("Voy a corregir la columna de semanas."))
        assertFalse(Guardas.anunciaPendiente("Listo: el cronograma ya tiene 30 actividades y 30 semanas."))
        assertFalse(Guardas.anunciaPendiente("Hecho. Si quieres, voy a colorear las semanas."))
        assertFalse(Guardas.anunciaPendiente("¿Quieres que lo haga también con las semanas?"))
        assertFalse(Guardas.anunciaPendiente("Sigo con lo más razonable."))
        assertFalse(Guardas.anunciaPendiente("Faltaban los atajos: ya están."))
        assertFalse(Guardas.anunciaPendiente("No pude: el lienzo esta cerrado."))
        val largo = "Voy a explicarlo. ${"Todo bien. ".repeat(80)} Listo, quedó como pediste."
        assertFalse(Guardas.anunciaPendiente(largo))
    }

    @Test fun `pedir que pare y lo que llega a media tarea`() {
        for (s in listOf("para", "¡Para!", "detente", "stop", "no sigas", "Déjalo")) assertTrue(s, Guardas.esParar(s))
        for (s in listOf("para el rojo usa azul", "sigue", "")) assertFalse(s, Guardas.esParar(s))
        val m = Guardas.mensajeEntrante(listOf("mejor en azul"))
        assertEquals(Rol.USUARIO, m.rol)
        assertTrue(m.textoEntero.startsWith("[Mensaje nuevo del usuario mientras trabajabas]\nmejor en azul"))
        assertTrue(Guardas.EMPUJON_ANUNCIO.startsWith(Guardas.MARCA_DEL_TURNO))
    }
}
