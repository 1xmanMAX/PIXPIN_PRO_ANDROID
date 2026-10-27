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

    // --- 27-oct-2026: las pestañas, abrir lo encontrado e ir al mensaje -----------------------

    private val foto = Mensaje(id = "f", cuando = 60, clase = Clase.IMAGEN, ruta = "/x/obra.jpg", nombre = "obra.jpg", texto = "fachada de la obra")
    private val video = Mensaje(id = "v", cuando = 50, clase = Clase.ARCHIVO, ruta = "/x/visita.mp4", nombre = "visita obra.mp4")
    private val pdf = Mensaje(id = "p", cuando = 40, clase = Clase.ARCHIVO, ruta = "/x/plano.pdf", nombre = "Plano obra.pdf", proyecto = "casa")
    private val enlace = Mensaje(id = "e", cuando = 30, clase = Clase.NOTA, texto = "la obra en https://ejemplo.com/obra")
    private val voz = Mensaje(id = "z", cuando = 20, clase = Clase.VOZ, ruta = "/x/a.m4a", nombre = "a.m4a", transcripcion = "llamar por la obra")
    private val lienzo = Mensaje(id = "d", cuando = 10, clase = Clase.DIBUJO, referencia = "dib1", nombre = "Croquis obra")
    private val nota = Mensaje(id = "n", cuando = 5, clase = Clase.NOTA, texto = "repasar la obra mañana")
    private val todos = listOf(foto, video, pdf, enlace, voz, lienzo, nota)

    @Test fun cadaMensajeSaleEnLasPestanasQueEnSuChat() {
        assertEquals(setOf(Seccion.FOTOS), BuscarEnChats.seccionesDe(foto))
        // Un vídeo no cuenta como archivo, como en la ficha del chat.
        assertEquals(setOf(Seccion.VIDEOS), BuscarEnChats.seccionesDe(video))
        assertEquals(setOf(Seccion.ARCHIVOS), BuscarEnChats.seccionesDe(pdf))
        assertEquals(setOf(Seccion.ENLACES), BuscarEnChats.seccionesDe(enlace))
        assertEquals(setOf(Seccion.VOZ), BuscarEnChats.seccionesDe(voz))
        assertEquals(setOf(Seccion.DIBUJOS), BuscarEnChats.seccionesDe(lienzo))
        assertEquals(emptySet<Seccion>(), BuscarEnChats.seccionesDe(nota))
        // Lo del buzón no sale en ninguna pestaña (en su chat tampoco): solo en «Todo».
        assertEquals(emptySet<Seccion>(), BuscarEnChats.seccionesDe(pdf.copy(enBuzon = true)))
        // Las pestañas, como las de la ficha y en su orden, con «Todo» delante.
        assertEquals(Seccion.TODO, BuscarEnChats.SECCIONES.first())
        assertTrue(BuscarEnChats.SECCIONES.drop(1).all { it in SeccionesDelChat.DE_LA_FICHA })
    }

    @Test fun laPestanaFiltraAntesDelTopeYCuentaCadaUna() {
        val i = BuscarEnChats.Indice()
        i.ponerAlDia(todos)
        val enTodo = i.buscar("obra", 3)
        assertEquals(7, enTodo.total)
        assertEquals(listOf("f", "v", "p"), enTodo.encontrados.map { it.id })
        assertEquals(7, enTodo.porSeccion[Seccion.TODO])
        assertEquals(1, enTodo.porSeccion[Seccion.ARCHIVOS])
        assertEquals(1, enTodo.porSeccion[Seccion.VOZ])
        assertEquals(null, enTodo.porSeccion[Seccion.FIJADOS])
        // Con «Archivos», solo el PDF, aunque no estuviera entre los tres primeros de «Todo».
        val archivos = i.buscar("plano", 1, Seccion.ARCHIVOS)
        assertEquals(listOf("p"), archivos.encontrados.map { it.id })
        assertEquals("casa", archivos.encontrados.single().chat)
        // El encontrado trae el mensaje entero: con él se pinta la miniatura y se abre.
        assertEquals(pdf, archivos.encontrados.single().mensaje)
        assertEquals(0, i.buscar("fachada", 20, Seccion.ARCHIVOS).total)
        assertEquals(1, i.buscar("fachada", 20, Seccion.ARCHIVOS).porSeccion[Seccion.FOTOS])
    }

    @Test fun conPestanaYSinEscribirSalenTodosLosDeEsaClase() {
        val es = todos.sortedWith(compareByDescending { it.cuando }).map { BuscarEnChats.entradaDe(it) }
        // Sin escribir y en «Todo», nada: eso sería el archivo entero.
        assertEquals(BuscarEnChats.Hallado(), BuscarEnChats.buscar(es, "  ", 10))
        val fotos = BuscarEnChats.buscar(es, "", 10, Seccion.FOTOS)
        assertEquals(1, fotos.total)
        assertEquals(listOf(BuscarEnChats.Acierto(0, 0)), fotos.aciertos)
        val i = BuscarEnChats.Indice()
        i.ponerAlDia(todos)
        val r = i.buscar("", 10, Seccion.DIBUJOS)
        assertEquals(listOf("d"), r.encontrados.map { it.id })
        // El trozo es el principio de su texto, sin nada resaltado.
        assertEquals("Croquis obra", r.encontrados.single().fragmento.texto)
        assertTrue(r.encontrados.single().fragmento.tramos.isEmpty())
    }

    @Test fun tocarUnResultadoAbreLoQueTieneVisorYLoDemasLlevaAlMensaje() {
        val abrir = BuscarEnChats.AlTocar.ABRIR
        val ir = BuscarEnChats.AlTocar.IR_AL_MENSAJE
        // Un PDF, una foto, un vídeo, un lienzo: a su visor.
        assertEquals(abrir, BuscarEnChats.alTocar(pdf))
        assertEquals(abrir, BuscarEnChats.alTocar(foto))
        assertEquals(abrir, BuscarEnChats.alTocar(video))
        assertEquals(abrir, BuscarEnChats.alTocar(lienzo))
        // Una nota con enlace se abre (el enlace); sin él no hay nada que abrir: a su burbuja.
        assertEquals(abrir, BuscarEnChats.alTocar(enlace))
        assertEquals(ir, BuscarEnChats.alTocar(nota))
        // La nota de un proyecto se abre en su editor.
        assertEquals(abrir, BuscarEnChats.alTocar(nota.copy(referencia = "hoja1")))
        // La voz se escucha en el chat, con su barra.
        assertEquals(ir, BuscarEnChats.alTocar(voz))
        // El fichero aún no llegó a este aparato: al mensaje, donde se ve que falta.
        assertEquals(ir, BuscarEnChats.alTocar(pdf) { false })
        assertEquals(abrir, BuscarEnChats.alTocar(pdf) { it == "/x/plano.pdf" })
        // Sin nada a lo que apuntar.
        assertEquals(ir, BuscarEnChats.alTocar(lienzo.copy(referencia = null)))
        assertEquals(ir, BuscarEnChats.alTocar(pdf.copy(ruta = null)))
        assertEquals(ir, BuscarEnChats.alTocar(Mensaje(id = "pg", cuando = 1, clase = Clase.PAGINA, ruta = "/x/plano.pdf", pagina = 2)))
        assertEquals(abrir, BuscarEnChats.alTocar(Mensaje(id = "pg", cuando = 1, clase = Clase.PAGINA, ruta = "/x/plano.pdf", pagina = 2, referencia = "d")))
        assertEquals(abrir, BuscarEnChats.alTocar(Mensaje(id = "t", cuando = 1, clase = Clase.TABLA, referencia = "t1")))
        assertEquals(abrir, BuscarEnChats.alTocar(Mensaje(id = "m", cuando = 1, clase = Clase.MINIAPP, miniapp = CarpetaDePaginas.MINIAPP)))
    }

    @Test fun irAlMensajeLlegaAlMensajeOALaTarjetaDeSuCarpeta() {
        val pagina = Mensaje(id = "d1", cuando = 2, clase = Clase.DIBUJO, referencia = "x")
        val carpeta = Mensaje(
            id = "c", cuando = 1, clase = Clase.MINIAPP, miniapp = CarpetaDePaginas.MINIAPP, nombre = "Diario",
            texto = CarpetaDePaginas().anadir("d1", "Día 1")!!.escribir()
        )
        // A la vista: a él.
        assertEquals("d1", BuscarEnChats.adondeSaltar(listOf(carpeta, pagina), "d1"))
        // Dentro de una carpeta, que es lo que se ve en el chat: a la tarjeta de la carpeta.
        val enElChat = CarpetaDePaginas.sueltosDelChat(listOf(carpeta, pagina))
        assertEquals(listOf("c"), enElChat.map { it.id })
        assertEquals("c", BuscarEnChats.adondeSaltar(enElChat, "d1"))
        // No se ve de ninguna forma: no hay a dónde (hay que quitar filtros o cambiar de pestaña).
        assertNull(BuscarEnChats.adondeSaltar(listOf(nota), "d1"))
    }
}
