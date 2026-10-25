package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.BuscarEnChats.Acierto
import com.forge.pixpin.guardados.BuscarEnChats.Elemento
import com.forge.pixpin.guardados.BuscarEnChats.Entrada
import com.forge.pixpin.guardados.BuscarEnChats.ESPERA_MS
import com.forge.pixpin.guardados.BuscarEnChats.Hallado
import com.forge.pixpin.guardados.BuscarEnChats.Retardo
import com.forge.pixpin.guardados.BuscarEnChats.Tramo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Buscar en todos los chats** (25-oct-2026): las pruebas de `crates/pixpin-ui/src/buscar_en_chats.rs`
 * del PC, con los mismos textos y resultados, y el índice de los mensajes de Android.
 */
class BuscarEnChatsTest {

    private fun entrada(chat: String, codigo: String, cuando: Long, vararg textos: String) =
        Entrada.nueva(chat, codigo, cuando, textos.toList())

    @Test fun buscaSinMirarMayusculasNiTildesYEnCualquierPieza() {
        val es = listOf(
            entrada("a", "1", 30, "La ARAÑA del tejado"),
            entrada("b", "2", 20, "", "nota de voz: cemento listo"),
            entrada("c", "3", 10, "", "", "Plano_Cimentación.pdf"),
            entrada("c", "4", 5, "nada que ver"),
        )
        assertEquals(listOf(Acierto(0, 0)), BuscarEnChats.buscar(es, "arana", 10).aciertos)
        assertEquals(listOf(Acierto(1, 0)), BuscarEnChats.buscar(es, "CEMENTO", 10).aciertos)
        assertEquals(listOf(Acierto(2, 0)), BuscarEnChats.buscar(es, "cimentacion", 10).aciertos)
        assertEquals(Hallado(), BuscarEnChats.buscar(es, "   ", 10))
    }

    @Test fun losVaciosYRepetidosNoSonPiezas() {
        val e = entrada("a", "1", 0, "  ", "hola", "hola", "hola.png")
        assertEquals(listOf("hola", "hola.png"), e.piezas.map { it.texto })
    }

    @Test fun elTopeCortaLoQueSeDevuelvePeroNoLaCuenta() {
        val es = (0 until 50).map { entrada("a", "$it", 100L - it, "pan y vino") }
        val h = BuscarEnChats.buscar(es, "vino", 20)
        assertEquals(20, h.aciertos.size)
        assertEquals(50, h.total)
        assertTrue(h.hayMas)
        assertFalse(BuscarEnChats.buscar(es, "vino", 50).hayMas)
    }

    @Test fun losBlancosCuentanComoEspacio() {
        val es = listOf(entrada("a", "1", 0, "hola\nmundo"))
        assertEquals(1, BuscarEnChats.buscar(es, "hola mundo", 5).total)
        val f = BuscarEnChats.fragmento("hola\nmundo", "hola mundo", 10, 50)
        assertEquals("hola mundo", f.texto)
        assertEquals(listOf(Tramo(0, 10)), f.tramos)
    }

    @Test fun seOrdenaDeLoMasNuevoALoMasViejo() {
        val a = entrada("a", "1", 10, "x")
        val b = entrada("b", "2", 30, "x")
        val c = entrada("a", "3", 20, "x")
        assertEquals(listOf("2", "3", "1"), listOf(a, b, c).sortedWith(BuscarEnChats.ORDEN).map { it.codigo })
        // A la par, por chat y código: la misma búsqueda da siempre lo mismo.
        val d = entrada("b", "0", 20, "x")
        assertEquals(listOf("2", "3", "0", "1"), listOf(a, b, d, c).sortedWith(BuscarEnChats.ORDEN).map { it.codigo })
    }

    @Test fun elFragmentoEmpiezaCercaDeLoEncontradoPorUnaPalabra() {
        val texto = "uno dos tres cuatro cinco seis siete ocho nueve diez once"
        val f = BuscarEnChats.fragmento(texto, "nueve", 12, 100)
        assertEquals("…siete ocho nueve diez once", f.texto)
        val t = f.tramos[0]
        assertEquals("nueve", f.texto.substring(t.desde, t.hasta))
    }

    @Test fun elFragmentoCortoNoLlevaPuntosYResaltaTodas() {
        val f = BuscarEnChats.fragmento("  Casa y casa  ", "casa", 20, 100)
        assertEquals("Casa y casa", f.texto)
        assertEquals(listOf("Casa", "casa"), f.tramos.map { f.texto.substring(it.desde, it.hasta) })
    }

    @Test fun elFragmentoLargoSeCortaConPuntosYRespetaLasTildes() {
        val f = BuscarEnChats.fragmento("Árbol ñandú camión árbol más texto aquí", "arbol", 5, 12)
        assertEquals("Árbol ñandú …", f.texto)
        val t = f.tramos[0]
        assertEquals("Árbol", f.texto.substring(t.desde, t.hasta))
        // La segunda coincidencia queda fuera del trozo: no se resalta.
        assertEquals(1, f.tramos.size)
    }

    @Test fun elFragmentoSinCoincidenciaEsElPrincipio() {
        val f = BuscarEnChats.fragmento("abc def", "zzz", 3, 3)
        assertEquals("abc…", f.texto)
        assertTrue(f.tramos.isEmpty())
    }

    @Test fun elFragmentoCuentaLosEmojisComoUnCaracter() {
        // En Kotlin un emoji son dos unidades: el trozo cuenta caracteres de verdad, como el PC
        // (tres casas y un espacio delante de «casa» son cuatro caracteres, más de uno).
        val f = BuscarEnChats.fragmento("🏠🏠🏠 casa", "casa", 1, 100)
        assertEquals("…casa", f.texto)
        assertEquals("casa", f.texto.substring(f.tramos[0].desde, f.tramos[0].hasta))
        val entero = BuscarEnChats.fragmento("🏠🏠🏠 casa", "casa", 4, 100)
        assertEquals("🏠🏠🏠 casa", entero.texto)
    }

    @Test fun laEsperaBuscaUnaVezCuandoSeDejaDeEscribir() {
        val r = Retardo()
        assertFalse(r.toca(0))
        assertNull(r.msHasta(0))
        r.cambio(1000)
        assertEquals(ESPERA_MS, r.msHasta(1000))
        assertFalse(r.toca(1100))
        r.cambio(1100)
        assertFalse(r.toca(1100 + ESPERA_MS - 1))
        assertTrue(r.toca(1100 + ESPERA_MS))
        assertFalse("una vez por cambio", r.toca(5000))
    }

    @Test fun laSeccionLlevaCabeceraMensajesYVerMas() {
        assertEquals(
            listOf(Elemento.Cabecera, Elemento.DeMensaje(0), Elemento.DeMensaje(1), Elemento.VerMas),
            BuscarEnChats.elementos(2, verMas = true)
        )
        assertEquals(listOf(Elemento.Cabecera, Elemento.DeMensaje(0)), BuscarEnChats.elementos(1, verMas = false))
        // Sin mensajes no hay sección, ni la cabecera.
        assertTrue(BuscarEnChats.elementos(0, verMas = true).isEmpty())
    }

    @Test fun laListaFiltraLosChatsSinMirarTildes() {
        // PC `02b3124`: «cimentacion» encuentra la «Cimentación» y «playa» la «Casa de playa».
        assertTrue(BuscarEnChats.hayCoincidencia("Cimentación", "cimentacion"))
        assertTrue(BuscarEnChats.hayCoincidencia("Casa de playa", "PLAYA"))
        assertTrue(BuscarEnChats.hayCoincidencia("Açaí", "acai"))
        assertFalse(BuscarEnChats.hayCoincidencia("Casa", "  "))
        assertFalse(BuscarEnChats.hayCoincidencia("Casa", "caso"))
        // Los tramos caen en el texto de verdad, con sus tildes.
        val t = BuscarEnChats.coincidencias("La Cimentación y la cimentacion", "CIMENTACIÓN")
        assertEquals(listOf(Tramo(3, 14), Tramo(20, 31)), t)
    }

    @Test fun elIndiceBuscaEnTodosLosChatsLoQueTieneBurbuja() {
        val ms = listOf(
            Mensaje(id = "1", cuando = 10, clase = Clase.NOTA, texto = "comprar cemento", proyecto = "obra"),
            Mensaje(id = "2", cuando = 30, clase = Clase.VOZ, nombre = "audio.m4a", transcripcion = "el cemento llega el lunes"),
            Mensaje(id = "3", cuando = 20, clase = Clase.ARCHIVO, nombre = "Cemento_Portland.pdf", proyecto = "otra"),
            // Los de sistema no tienen burbuja: no se encuentran.
            Mensaje(id = "auto-figuras", cuando = 40, clase = Clase.NOTA, texto = "cemento", proyecto = "obra"),
        )
        val i = BuscarEnChats.Indice()
        i.ponerAlDia(ms)
        val r = i.buscar("CEMENTO", 2)
        assertEquals(3, r.total)
        assertTrue(r.hayMas)
        assertEquals(listOf("2", "3"), r.encontrados.map { it.id })
        assertEquals(listOf("general", "otra"), r.encontrados.map { it.chat })
        assertEquals("el cemento llega el lunes", r.encontrados[0].fragmento.texto)
        // Lo que cambia se vuelve a mirar; lo que se borra, se va.
        i.ponerAlDia(listOf(ms[0].copy(texto = "comprar arena"), ms[2]))
        assertEquals(listOf("3"), i.buscar("cemento", 20).encontrados.map { it.id })
        assertEquals(0, i.buscar("", 20).total)
    }
}
