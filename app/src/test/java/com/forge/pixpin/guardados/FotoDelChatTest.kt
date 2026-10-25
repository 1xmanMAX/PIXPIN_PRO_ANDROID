package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.FotoDelChat.AUMENTO_MAXIMO
import com.forge.pixpin.guardados.FotoDelChat.EMOJIS
import com.forge.pixpin.guardados.FotoDelChat.Eleccion
import com.forge.pixpin.guardados.FotoDelChat.Estado
import com.forge.pixpin.guardados.FotoDelChat.FONDOS
import com.forge.pixpin.guardados.FotoDelChat.Recorte
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La foto del chat** (25-oct-2026): las pruebas del PC (`pixpin-proyecto/src/avatar.rs`,
 * `pixpin-ui/src/foto_del_chat.rs` y la hoja de `ventana_chat/foto_del_chat.rs`) pasadas a Kotlin,
 * con los mismos números, para que los dos lados escriban y lean lo mismo.
 */
class FotoDelChatTest {

    private fun json(t: String) = Json.parseToJsonElement(t).jsonObject

    // ---- El formato (avatar.rs) ----

    @Test fun losColoresSeLeenYSeEscribenComoHex() {
        assertEquals(0xff885e, FotoDelChat.colorDeTexto("#FF885E"))
        assertEquals(0xff885e, FotoDelChat.colorDeTexto("ff885e"))
        assertEquals("#2A9EF1", FotoDelChat.textoDeColor(0x2a9ef1))
        // Caso negativo: lo que no es un color no se inventa.
        assertNull(FotoDelChat.colorDeTexto("#FFF"))
        assertNull(FotoDelChat.colorDeTexto("rojo!!"))
    }

    @Test fun elFormatoEsElDelManualYSinCamposVacios() {
        val a = FotoDelChat.emoji("🏠", "#FF885E" to "#FF516A", 7)
        assertEquals(json("""{"tipo":"emoji","emoji":"🏠","color":"#FF885E","color2":"#FF516A","cuando":7}"""), a.aJson())
        val liso = FotoDelChat.iniciales("#72D5FD" to "#72D5FD", 8)
        assertEquals(json("""{"tipo":"iniciales","color":"#72D5FD","cuando":8}"""), liso.aJson())
        assertEquals(0x72d5fd to 0x72d5fd, liso.fondo)
        val siempre = FotoDelChat.iniciales(null, 9)
        assertEquals(json("""{"tipo":"iniciales","cuando":9}"""), siempre.aJson())
        assertTrue(siempre.esLoDeSiempre)
        assertFalse(a.esLoDeSiempre)
        assertTrue(FotoDelChat.imagen("avatar-1.png", 1).esImagen)
        // Y de ida y vuelta: lo escrito se lee igual.
        for (x in listOf(a, liso, siempre, FotoDelChat.imagen("/files/guardados/avatar-5.png", 5)))
            assertEquals(x, FotoDelChat.de(x.aJson()))
    }

    @Test fun unTipoQueNoSeConoceSePintaComoSiempreYSeConserva() {
        val raro = json("""{"tipo":"animado","gif":"x.gif","cuando":3}""")
        val a = FotoDelChat.de(raro)!!
        assertEquals("animado", a.tipo)
        assertTrue(a.esLoDeSiempre)
        // Lo que se guarda en el proyecto es lo crudo, con su `gif`, y sobrevive al disco.
        val p = Proyecto("p", "Casa", avatar = raro)
        val leido = Proyectos.json.decodeFromString(Proyecto.serializer(), Proyectos.json.encodeToString(Proyecto.serializer(), p))
        assertEquals(raro, leido.avatar)
        // Un emoji vacío tampoco es un emoji.
        assertNull(FotoDelChat.de(json("""{"tipo":"emoji","emoji":" "}"""))!!.elEmoji)
        // Y algo que no es un objeto no es una foto.
        assertNull(FotoDelChat.de(JsonPrimitive("hola")))
        assertNull(FotoDelChat.de(null as JsonObject?))
    }

    @Test fun sinFotoElProyectoNoEscribeElCampo() {
        val texto = Proyectos.json.encodeToString(Proyecto.serializer(), Proyecto("p", "Casa"))
        assertFalse(texto, texto.contains("avatar"))
        // Un proyecto del PC con su foto se lee entero.
        val delPc = """{"id":"p","nombre":"Casa","avatar":{"tipo":"emoji","emoji":"🏗️","color":"#FFCD6A","color2":"#FFA85C","cuando":1761400000000}}"""
        val p = Proyectos.json.decodeFromString(Proyecto.serializer(), delPc)
        assertEquals("🏗️", FotoDelChat.de(p)!!.elEmoji)
        assertEquals(0xffcd6a to 0xffa85c, FotoDelChat.de(p)!!.fondo)
    }

    @Test fun soloSeReconocenLasImagenesDeFotoNombradasPorSuHora() {
        assertTrue(FotoDelChat.esImagenDeAvatar("avatar-1761400000000.png"))
        assertEquals("avatar-12.png", FotoDelChat.nombreDeImagen(12))
        assertFalse(FotoDelChat.esImagenDeAvatar("avatar-.png"))
        assertFalse(FotoDelChat.esImagenDeAvatar("avatar-casa.png"))
        assertFalse(FotoDelChat.esImagenDeAvatar("captura.png"))
    }

    @Test fun sobraLaImagenViejaQueYaNoNombraNadieYNadaMas() {
        val vieja = "/f/guardados/avatar-100.png"
        val nueva = "/f/guardados/avatar-200.png"
        val antes = listOf(
            Proyecto("a", "A", avatar = FotoDelChat.imagen(vieja, 100).aJson()),
            Proyecto("b", "B", avatar = FotoDelChat.imagen("/f/guardados/1_foto.png", 1).aJson()),
            Proyecto("c", "C", avatar = FotoDelChat.imagen("/f/guardados/pc/C/logo.png", 1).aJson()),
        )
        val despues = listOf(
            Proyecto("a", "A", avatar = FotoDelChat.imagen(nueva, 200).aJson()),
            // Una foto del chat que alguien puso de foto: se deja, no es de PixPin.
            Proyecto("b", "B", avatar = FotoDelChat.emoji("🍕", null, 2).aJson()),
            Proyecto("c", "C", avatar = FotoDelChat.iniciales(null, 2).aJson()),
        )
        assertEquals(setOf(vieja, "/f/guardados/pc/C/logo.png"), FotoDelChat.sobrantes(antes, despues))
        // Caso negativo: la que sigue siendo la foto de otro no sobra.
        val compartida = despues + Proyecto("d", "D", avatar = FotoDelChat.imagen(vieja, 100).aJson())
        assertEquals(setOf("/f/guardados/pc/C/logo.png"), FotoDelChat.sobrantes(antes, compartida))
    }

    @Test fun cambiarLaFotoDaElProyectoPorTocado() {
        val p = Proyecto("p", "Casa", tocado = 10)
        val con = FotoDelChat.conFoto(p, FotoDelChat.emoji("🏠", null, 50), 50)
        assertEquals(50, con.tocado)
        assertEquals("🏠", FotoDelChat.de(con)!!.elEmoji)
    }

    // ---- Los fondos y los emojis (foto_del_chat.rs) ----

    @Test fun laPaletaTieneDoceFondosDistintosYCadaChatSuPropuesto() {
        for ((i, a) in FONDOS.withIndex()) {
            assertTrue("$i repetido", FONDOS.drop(i + 1).none { it == a })
            assertEquals(i, FotoDelChat.lugarDelFondo(a.arriba, a.abajo))
        }
        assertEquals(12, FONDOS.size)
        assertTrue(FotoDelChat.fondoPropuesto("AGJ36629KT") < FONDOS.size)
        assertEquals(FotoDelChat.fondoPropuesto("AGJ36629KT"), FotoDelChat.fondoPropuesto(" AGJ36629KT "))
        // FNV-1a de 32 bits como el PC: «a» es 0xE40C292C, que módulo 12 da 4.
        assertEquals(4, FotoDelChat.fondoPropuesto("a"))
        // Caso negativo: un degradado que no es de la paleta no tiene lugar.
        assertNull(FotoDelChat.lugarDelFondo(0x123456, 0x654321))
    }

    @Test fun buscarEncuentraPorElPrincipioDeLasPalabrasSinTildes() {
        fun de(q: String) = FotoDelChat.buscarEmojis(q).map { EMOJIS[it].first }
        assertEquals(EMOJIS.size, FotoDelChat.buscarEmojis("").size)
        assertTrue(de("casa").contains("🏠"))
        assertTrue(de("Construcción").contains("🏗️"))
        assertTrue(de("house").contains("🏠"))
        // Dos palabras: tienen que estar las dos.
        assertEquals(listOf("🏡"), de("casa jardin"))
        // Un emoji pegado se encuentra a sí mismo.
        assertEquals(listOf("🍕"), de("🍕"))
        // Caso negativo: lo que no está no encuentra nada, y una letra en medio de una palabra no cuenta.
        assertTrue(FotoDelChat.buscarEmojis("zzzz").isEmpty())
        assertFalse(de("asa").contains("🏠"))
    }

    @Test fun losEmojisNoSeRepitenYTodosTienenNombre() {
        for ((i, p) in EMOJIS.withIndex()) {
            val (e, n) = p
            assertTrue("$e sin nombre", n.isNotBlank())
            assertEquals("$e: nombres sin normalizar", n, FotoDelChat.normalizar(n))
            assertTrue("$e repetido", EMOJIS.drop(i + 1).none { it.first == e })
        }
        assertEquals(100, EMOJIS.size)
    }

    // ---- El recorte ----

    @Test fun alAbrirEsElCuadradoMasGrandeDelCentro() {
        assertEquals(Triple(50, 0, 300), Recorte.centrado(400, 300).enPixeles(400, 300))
        assertEquals(Triple(0, 50, 300), Recorte.centrado(300, 400).enPixeles(300, 400))
        assertEquals(Triple(0, 0, 10), Recorte.centrado(10, 10).enPixeles(10, 10))
    }

    @Test fun moverNoSacaElCuadradoDeLaFoto() {
        val (w, h) = 400 to 300
        var r = Recorte.centrado(w, h)
        // A la izquierda del todo: la foto va a la derecha.
        r = r.movido(w, h, 150f, 1000f, 0f)
        assertEquals(Triple(0, 0, 300), r.enPixeles(w, h))
        r = r.movido(w, h, 150f, -1000f, -1000f)
        assertEquals(Triple(100, 0, 300), r.enPixeles(w, h))
        // En el eje en que no sobra nada no se mueve.
        assertEquals(150f, r.cy)
    }

    @Test fun acercarDejaQuietoLoQueHayBajoLosDedos() {
        val (w, h) = 1000 to 1000
        // En el centro: el cuadrado se encoge alrededor del centro.
        assertEquals(Triple(250, 250, 500), Recorte.centrado(w, h).acercado(w, h, 2f, 0.5f, 0.5f).enPixeles(w, h))
        // Hacia la esquina de arriba a la izquierda: esa esquina se queda.
        var r = Recorte.centrado(w, h).acercado(w, h, 4f, 0f, 0f)
        assertEquals(Triple(0, 0, 250), r.enPixeles(w, h))
        // Caso negativo: alejar de más no da un cuadrado mayor que la foto, ni acercar de más uno
        // más pequeño que el tope.
        r = r.rueda(w, h, -120 * 50, 0.5f, 0.5f)
        assertEquals(Triple(0, 0, 1000), r.enPixeles(w, h))
        r = r.rueda(w, h, 120 * 50, 0.5f, 0.5f)
        assertEquals(AUMENTO_MAXIMO, r.aumento)
        assertEquals(125, r.enPixeles(w, h).third)
    }

    @Test fun elDestinoPoneElCuadradoEnLaCaja() {
        val (w, h) = 400 to 300
        // El cuadrado (50,0,300) en una caja de 150 en (10,20): la foto se pinta a la mitad y
        // movida 25 a la izquierda.
        val d = Recorte.centrado(w, h).destino(10f, 20f, 150f, w, h)
        assertEquals(listOf(-15f, 20f, 200f, 150f), d.toList())
    }

    // ---- La hoja (ventana_chat/foto_del_chat.rs) ----

    @Test fun elegirUnFondoConUnaImagenVuelveAlUltimoEmojiYComoSiempreLoQuitaTodo() {
        var est = Estado(Eleccion.LaDeAntes, fondo = null, ultimoEmoji = "🏠")
        est = est.conFondo(3)
        assertEquals(Eleccion.ConEmoji("🏠"), est.eleccion)
        assertEquals(FONDOS[3].arriba to FONDOS[3].abajo, est.fondo)
        assertTrue(est.tocado)
        est = est.comoSiempre()
        assertEquals(Eleccion.Iniciales, est.eleccion)
        assertNull(est.fondo)
        // Un emoji sin fondo se lleva el propuesto del chat.
        est = est.conEmoji("🍕", "AGJ36629KT")
        val f = FONDOS[FotoDelChat.fondoPropuesto("AGJ36629KT")]
        assertEquals(f.arriba to f.abajo, est.fondo)
    }

    @Test fun guardarSinTocarNadaNoEscribeYUnEmojiQuedaConSuFondo() {
        val est = Estado.de(null)
        // Caso negativo: sin tocar nada no se escribe nada.
        assertNull(est.aGuardar(1))
        val a = est.conEmoji("🏗️", "AGJ36629KT").aGuardar(5)
        assertNotNull(a)
        assertEquals("🏗️", a!!.elEmoji)
        assertNotNull(a.fondo)
        assertEquals(5L, a.cuando)
        // Como siempre se escribe (no se borra): iniciales sin colores.
        assertEquals(json("""{"tipo":"iniciales","cuando":6}"""), est.comoSiempre().aGuardar(6)!!.aJson())
        // Una imagen nueva la guarda quien tiene los píxeles: aquí no sale foto, y mover el recorte cuenta como tocar.
        val conImagen = Estado(Eleccion.Iniciales, null, null).conImagen(40, 20)
        assertNull(conImagen.aGuardar(7))
        val movida = conImagen.conRecorte { it.movido(40, 20, 100f, 50f, 0f) }
        assertEquals(Triple(0, 0, 20), (movida.eleccion as Eleccion.Nueva).recorte.enPixeles(40, 20))
    }

    @Test fun laHojaSeAbreConLaFotoQueTiene() {
        assertEquals(Eleccion.LaDeAntes, Estado.de(FotoDelChat.imagen("/a/avatar-1.png", 1)).eleccion)
        val e = Estado.de(FotoDelChat.emoji("🌳", "#A0DE7E" to "#54CB68", 1))
        assertEquals(Eleccion.ConEmoji("🌳"), e.eleccion)
        assertEquals(0xA0DE7E to 0x54CB68, e.fondo)
        assertEquals("🌳", e.ultimoEmoji)
        assertFalse(e.tocado)
        assertEquals(Eleccion.Iniciales, Estado.de(FotoDelChat.de(json("""{"tipo":"animado"}"""))).eleccion)
    }
}
