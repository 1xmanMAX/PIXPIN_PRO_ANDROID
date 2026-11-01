package com.forge.pixpin

import com.forge.pixpin.anki.AnkiDelMensaje
import com.forge.pixpin.atajos.Atajos
import com.forge.pixpin.ia.Pedidos
import com.forge.pixpin.motor.ExcalidrawJson
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.PdfConAnotaciones
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motormd.Incrustados
import com.forge.pixpin.sincro.Envio
import com.forge.pixpin.sincro.Invitacion
import com.forge.pixpin.sincro.Red
import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **El formato y los nombres de PinDesk** (1-nov-2026, `docs/formato-pindesk-android.md`): se
 * escribe el nuevo y se lee también el de antes, como el PC (`docs/formato-pindesk.md` de Windows).
 * Lo del paquete está en `PaquetePixpinTest`; lo de Lectura Veloz, en `LecturaVelozTest` y
 * `LecturaVelozEnElTelefonoTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatoPinDeskTest {

    // ---- QR: se leen los dos prefijos, se sigue escribiendo el de antes (fase B) ----

    @Test
    fun `los QR de PinDesk se leen igual que los de antes`() {
        assertEquals(Envio.leerQr("pixpin-envio:1:482913:1.2.3.4:5"), Envio.leerQr("pindesk-envio:1:482913:1.2.3.4:5"))
        assertTrue(Envio.leerQr("pindesk-recibe:1:482913:1.2.3.4:5")!!.esperaRecibir)
        assertEquals(Invitacion.leerQr("pixpin-chat:1:K7Q2M9XP::4000"), Invitacion.leerQr("pindesk-chat:1:K7Q2M9XP::4000"))
        // Lo que enseña este aparato sigue con el prefijo de antes: un móvil sin actualizar lo lee.
        assertTrue(Envio.textoDelQr("482913", "h", 1).startsWith("pixpin-envio:"))
        assertTrue(Envio.textoDelQrDeRecepcion("482913", "h", 1).startsWith("pixpin-recibe:"))
        assertTrue(Invitacion.textoDelQr("K7Q2M9XP", null, 1).startsWith("pixpin-chat:"))
        // Casos negativos: otro prefijo de PinDesk, o el QR de un envío donde se espera un chat.
        assertNull(Envio.leerQr("pindesk-otro:1:482913:1.2.3.4:5"))
        assertNull(Invitacion.leerQr("pindesk-envio:1:123456::0"))
    }

    // ---- mDNS: los dos tipos a la vez ----

    @Test
    fun `cada tipo de PinDesk tiene su tipo de antes`() {
        assertEquals("_pindesk._tcp.", Red.TIPO)
        assertEquals("_pixpin._tcp.", Red.tipoDeAntes(Red.TIPO))
        assertEquals("_pixpinenvio._tcp.", Red.tipoDeAntes(Envio.TIPO))
        assertEquals("_pixpinrecibe._tcp.", Red.tipoDeAntes(Envio.TIPO_RECIBIR))
        assertEquals("_pixpinchat._tcp.", Red.tipoDeAntes(Invitacion.TIPO))
        assertEquals(listOf("_pindesk._tcp.", "_pixpin._tcp."), Red.conElDeAntes(Red.TIPO))
        // Casos negativos: uno de antes u otro cualquiera no tiene otro tipo.
        assertNull(Red.tipoDeAntes("_pixpin._tcp."))
        assertNull(Red.tipoDeAntes("_http._tcp."))
        assertEquals(listOf("_http._tcp."), Red.conElDeAntes("_http._tcp."))
        assertEquals("PinDesk Casa", Red.instancia("Casa"))
    }

    @Test
    fun `un aparato que sale con los dos tipos queda una vez con lo nuevo`() {
        val ip = InetAddress.getByName("192.168.1.5")
        fun v(nombre: String, puerto: Int, id: String = "", g: String = "G") =
            Red.Vecino(nombre, ip, puerto, buildMap { put("g", g); if (id.isNotEmpty()) put("id", id) })
        val juntos = Red.juntar(
            listOf(v("PinDesk Casa", 1, id = "A")),
            listOf(v("PixPin Casa", 2, id = "A"), v("PixPin Móvil", 3, id = "B"))
        )
        assertEquals(listOf(1, 3), juntos.map { it.puerto })
        // Sin `id` (un envío): el mismo `g` y el mismo nombre es el mismo; otro `g` es otro.
        assertEquals(listOf(1), Red.juntar(listOf(v("Ana", 1)), listOf(v("Ana", 2))).map { it.puerto })
        assertEquals(2, Red.juntar(listOf(v("Ana", 1)), listOf(v("Ana", 2, g = "H"))).size)
        // Caso negativo: sin nada nuevo quedan los de antes tal cual.
        assertEquals(1, Red.juntar(emptyList(), listOf(v("PixPin Móvil", 3, id = "B"))).size)
    }

    // ---- Enlaces y pedidos ----

    @Test
    fun `las senas pindesk y pixpin son nuestras y se escribe pindesk`() {
        assertTrue(Atajos.esEsquema("pindesk"))
        assertTrue(Atajos.esEsquema("pixpin"))
        assertTrue(Atajos.esEsquema("PinDesk"))
        assertEquals("pindesk://atajo/tareas", Atajos.sena(Atajos.TAREAS).toString())
        // Casos negativos: el del PC de antes no es un enlace de Android, ni uno cualquiera.
        assertFalse(Atajos.esEsquema("pixpinmax"))
        assertFalse(Atajos.esEsquema(null))
        assertFalse(Atajos.esEsquema("https"))
    }

    @Test
    fun `la version del pedido vale con pindesk o con pixpin y manda pindesk`() {
        fun j(s: String) = Json.parseToJsonElement(s) as JsonObject
        assertEquals(1, Pedidos.version(j("""{"pindesk":1,"accion":"abrir"}""")))
        assertEquals(1, Pedidos.version(j("""{"pixpin":1,"accion":"abrir"}""")))
        assertEquals(2, Pedidos.version(j("""{"pixpin":1,"pindesk":2,"accion":"abrir"}""")))
        assertTrue(Pedidos.esCampoDeVersion("pixpin") && Pedidos.esCampoDeVersion("pindesk"))
        // Casos negativos.
        assertNull(Pedidos.version(j("""{"accion":"abrir"}""")))
        assertFalse(Pedidos.esCampoDeVersion("accion"))
    }

    @Test
    fun `los enlaces de las notas se leen con pindesk y se escriben como antes`() {
        assertEquals(Incrustados.Enlace.Hoja("P1", "H2"), Incrustados.enlace("pindesk:hoja=P1/H2"))
        assertEquals(Incrustados.Enlace.Mensaje("P1", "M"), Incrustados.enlace("pindesk:mensaje=P1/M"))
        assertEquals(Incrustados.Enlace.Hoja("P1", "H2"), Incrustados.enlace("pixpin:hoja=P1/H2"))
        // Se escribe aún `pixpin:` (lo lee el PC dentro de las notas, fase B).
        assertTrue(Incrustados.escribirEnlaceAHoja("A", "P1", "H2").endsWith("(pixpin:hoja=P1/H2)"))
        // Caso negativo.
        assertNull(Incrustados.enlace("pindesk:otra=P1/H2"))
    }

    // ---- PDF, Anki y el lienzo ----

    @Test
    fun `las capas de los PDF se llaman PinDesk y se reconocen las dos`() {
        assertEquals("PinDesk · hoja 3", PdfConAnotaciones.capaDeLaHoja(3))
        assertEquals(3, PdfConAnotaciones.hojaDeLaCapa("PinDesk · hoja 3"))
        assertEquals(12, PdfConAnotaciones.hojaDeLaCapa("PixPin · hoja 12"))
        assertTrue(PdfConAnotaciones.esCapaNuestra("PixPin — página 1"))
        assertTrue(PdfConAnotaciones.esCapaNuestra("PinDesk"))
        // Casos negativos.
        assertNull(PdfConAnotaciones.hojaDeLaCapa("Capa 1"))
        assertNull(PdfConAnotaciones.hojaDeLaCapa("PinDesk · hoja cero"))
        assertFalse(PdfConAnotaciones.esCapaNuestra("Cotas"))
    }

    @Test
    fun `las tarjetas nuevas van a PinDesk con la etiqueta pindesk`() {
        assertEquals("PinDesk::Obra", AnkiDelMensaje.mazoDe("Obra"))
        assertEquals("pindesk", AnkiDelMensaje.ETIQUETA)
        // Caso negativo: no se escribe el mazo de antes.
        assertFalse(AnkiDelMensaje.mazoDe("Obra").startsWith("PixPin"))
    }

    @Test
    fun `el emoji del PC conserva sus dos marcas al guardarse aqui`() {
        // El PC escribe `{"pindesk":"emoji","pixpin":"emoji"}`; aquí no se pinta como emoji propio,
        // pero el `customData` vuelve igual, para que el PC y los móviles lo sigan reconociendo.
        val texto = """{"type":"excalidraw","version":2,"source":"pindesk","elements":[
            {"id":"e1","type":"text","x":0,"y":0,"width":20,"height":20,"text":"😀","fontSize":16,"customData":{"pindesk":"emoji","pixpin":"emoji"}}
        ],"appState":{},"files":{}}"""
        val escena: Scene = ExcalidrawStore.escenaSinFotos(texto)!!
        val vuelta = Json.parseToJsonElement(ExcalidrawJson.encodeToString(Scene.serializer(), escena)).jsonObject
        val cd = vuelta["elements"]!!.jsonArray.single().jsonObject["customData"]!!.jsonObject
        assertEquals("emoji", cd["pindesk"]!!.jsonPrimitive.content)
        assertEquals("emoji", cd["pixpin"]!!.jsonPrimitive.content)
        // Caso negativo: un texto normal no gana ninguna marca.
        val normal = ExcalidrawStore.escenaSinFotos(texto.replace(""","customData":{"pindesk":"emoji","pixpin":"emoji"}""", ""))!!
        assertNull(normal.elements.single().customData)
    }
}
