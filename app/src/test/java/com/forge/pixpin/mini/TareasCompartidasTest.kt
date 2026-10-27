package com.forge.pixpin.mini

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.sincro.EnElChat
import com.forge.pixpin.sincro.LadosDelChat
import com.forge.pixpin.widget.LogicaDeLosWidgets
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Qué listas de tareas son de un chat compartido y quién las apuntó (27-oct-2026): lo que decide el
 * símbolo verde del widget y de la pantalla de Tareas. Con los casos que fallan: «Mensajes
 * guardados», un chat normal, sin ningún compartido, una lista mía o de un aparato desconocido.
 */
class TareasCompartidasTest {

    /** El chat compartido «obra», con Ana dentro; mis aparatos son `MIO1` y `MIO2`. */
    private val compartidos = mapOf(
        "obra" to EnElChat(
            "obra", 2,
            LadosDelChat.nuevo(listOf("MIO1", "MIO2"), listOf("MIO1" to "Max", "AN42" to "Ana"))
        )
    )

    private fun mensaje(id: String, proyecto: String?, aparato: String?, texto: String = "- [ ] una\n- [ ] dos") =
        Mensaje(id = id, cuando = 1, clase = Clase.MINIAPP, texto = texto, miniapp = MiniApp.TAREAS.id, proyecto = proyecto, aparato = aparato)

    private fun lista(proyecto: String?, aparato: String?, chat: String = "Obra") =
        TodasLasTareas.listaDe(mensaje("m-$proyecto-$aparato", proyecto, aparato), chat)!!

    @Test
    fun laListaDeUnChatCompartidoEsCompartidaYLasDemasNo() {
        assertTrue(TareasCompartidas.esCompartida(lista("obra", "MIO1"), compartidos))
        // Un chat normal, «Mensajes guardados» (nunca se comparte) y sin compartidos.
        assertFalse(TareasCompartidas.esCompartida(lista("casa", "MIO1", "Casa"), compartidos))
        assertFalse(TareasCompartidas.esCompartida(lista(null, null, TodasLasTareas.GUARDADOS), compartidos))
        assertFalse(TareasCompartidas.esCompartida(lista("obra", "AN42"), emptyMap()))
        assertEquals("obra", TareasCompartidas.de(lista("obra", null), compartidos)?.chat)
    }

    @Test
    fun elAutorSoloSiLaApuntoOtraPersona() {
        // `reunir` guarda el aparato del mensaje en la lista.
        val l = TodasLasTareas.reunir(listOf(mensaje("m1", "obra", "AN42")), mapOf("obra" to "Obra")).single()
        assertEquals("AN42", l.aparato)
        assertEquals("Ana", TareasCompartidas.autor(l, compartidos))
        // Mía (de cualquiera de mis aparatos), sin aparato (de antes) o de uno que no está: nadie.
        assertNull(TareasCompartidas.autor(lista("obra", "MIO2"), compartidos))
        assertNull(TareasCompartidas.autor(lista("obra", null), compartidos))
        assertNull(TareasCompartidas.autor(lista("obra", "ZZ99"), compartidos))
        // De un chat que no es compartido, aunque el aparato sea de Ana: nadie.
        assertNull(TareasCompartidas.autor(lista("casa", "AN42", "Casa"), compartidos))
    }

    @Test
    fun laInicialYElSimbolo() {
        assertEquals("A", TareasCompartidas.inicial("  ana "))
        assertEquals("É", TareasCompartidas.inicial("élida"))
        assertEquals("😀", TareasCompartidas.inicial("😀 Feliz"))
        assertEquals("", TareasCompartidas.inicial("   "))
        assertEquals("👥 Llamar", TareasCompartidas.conSimbolo("Llamar", true))
        assertEquals("Llamar", TareasCompartidas.conSimbolo("Llamar", false))
    }

    @Test
    fun enElWidgetLasFilasDeUnaListaCompartidaLlevanElSimboloYSuAutor() {
        val listas = listOf(lista("obra", "AN42"), lista("casa", "MIO1", "Casa"))
        val hoy = LocalDate.of(2026, 10, 27)
        val filas = LogicaDeLosWidgets.filasDeTareas(listas, hoy, compartidos = compartidos)
        val (deObra, deCasa) = filas.partition { it.codigo == "m-obra-AN42" }
        assertEquals(2, deObra.size)
        assertTrue(deObra.all { it.compartida && it.autor == "Ana" })
        assertTrue(deCasa.isNotEmpty() && deCasa.none { it.compartida || it.autor != null })
        // Sin compartidos (lo de siempre), ninguna; el texto y el detalle no cambian.
        val sin = LogicaDeLosWidgets.filasDeTareas(listas, hoy)
        assertTrue(sin.none { it.compartida })
        assertEquals(sin.map { it.texto to it.detalle }, filas.map { it.texto to it.detalle })
    }
}
