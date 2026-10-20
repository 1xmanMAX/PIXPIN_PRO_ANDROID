package com.forge.pixpin.ia

import com.forge.pixpin.asistente.HojaAlLienzo
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.motor.ExcalidrawStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **El puente de la hoja al lienzo** (lo que hace el teléfono para la biblioteca del PC): los
 * formatos de bytes, la nube con la forma del PC, y cada zona al lienzo — rehacer una la sustituye,
 * con nada la quita — y la hoja nueva entra al chat al acabar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HojaAlLienzoTest {
    private val c get() = RuntimeEnvironment.getApplication()

    private fun texto(id: String, t: String, x: Double) =
        """{"id":"$id","type":"text","x":$x,"y":10,"width":50,"height":20,"text":"$t","fontSize":16,"fontFamily":2,"seed":1,"strokeColor":"#1e1e1e"}"""

    private fun documento(vararg els: String) = """{"type":"excalidraw","version":2,"elements":[${els.joinToString(",")}],"files":{}}"""

    private fun en(zona: Int, rehacer: Boolean = false, ultima: Boolean = false) =
        """{"sesion":"77","zona":$zona,"rehacer":$rehacer,"ultima":$ultima,"tam":[1000,1414],"junto_a":null}"""

    @Test fun `cada zona entra y al rehacerla se sustituye`() {
        val almacen = MensajesStore(c)
        val motor = MotorGuionado(listOf(Respuesta("""{"x":1}""")), vision = true)
        val h = HojaAlLienzo(c, almacen, "chat1", motor, esNube = true, parar = AtomicBoolean(false), destino = null)
        assertEquals("", h.entregar(documento(texto("a", "Titulo", 0.0)), ByteArray(4), en(0)))
        assertEquals("", h.entregar(documento(texto("b", "Parrafo", 0.0), texto("c", "dos", 60.0)), ByteArray(4), en(1)))
        val id = h.lienzo!!
        fun escena() = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(c, id))!!
        assertEquals(listOf("a", "b", "c"), escena().elements.map { it.id })
        // Rehacer la zona 1 con otra cosa la sustituye; con nada, la quita.
        h.entregar(documento(texto("d", "Parrafo bien", 0.0)), ByteArray(4), en(1, rehacer = true))
        assertEquals(listOf("a", "d"), escena().elements.map { it.id })
        h.entregar("", ByteArray(4), en(0, rehacer = true))
        assertEquals(listOf("d"), escena().elements.map { it.id })
        // Un id que ya está no choca: estrena otro.
        h.entregar(documento(texto("d", "otra", 0.0)), ByteArray(4), en(2))
        assertEquals(2, escena().elements.map { it.id }.toSet().size)
        // Al acabar, la hoja nueva entra al chat como dibujo del asistente.
        h.entregar("", ByteArray(4), en(0, ultima = true))
        assertTrue(almacen.leer().any { it.clase == Clase.DIBUJO && it.referencia == id && it.proyecto == "chat1" })
    }

    @Test fun `las imagenes de una zona van a ficheros del lienzo con su id`() {
        val h = HojaAlLienzo(c, MensajesStore(c), "chat2", MotorGuionado(emptyList()), esNube = true, parar = AtomicBoolean(false), destino = null)
        val id = "pc1f".toByteArray()
        val px = ByteArray(2 * 2 * 4) { 0x7f }
        val paquete = ByteBuffer.allocate(4 + 4 + id.size + 8 + px.size).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(id.size).put(id).putInt(2).putInt(2).put(px).array()
        assertEquals(1, HojaAlLienzo.desempaquetar(paquete).size)
        val img = """{"id":"i1","type":"image","x":0,"y":0,"width":2,"height":2,"fileId":"pc1f","seed":1}"""
        assertEquals("", h.entregar(documento(img), paquete, en(0)))
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(c, h.lienzo!!))!!
        assertTrue(escena.files.containsKey("pc1f"))
        assertTrue(java.io.File(escena.files.getValue("pc1f").path).isFile)
    }

    @Test fun `la nube va con la forma del PC y lo que falla se dice`() {
        val motor = MotorGuionado(listOf(Respuesta("\\frac{a}{b}")), vision = true)
        val h = HojaAlLienzo(c, MensajesStore(c), null, motor, esNube = true, parar = AtomicBoolean(false), destino = null)
        val r = h.completar("""{"mensajes":[{"rol":"sistema","partes":[{"texto":"lee"}]},{"rol":"usuario","partes":[{"imagen":"iVBORw0=","mime":"image/png"},{"texto":"la formula"}]}],"max_tokens":300,"temperatura":0.1}""")
        assertEquals("""{"texto":"\\frac{a}{b}"}""", r)
        val p = motor.recibido.single()
        assertEquals(Rol.SISTEMA, p.mensajes[0].rol)
        assertTrue(p.mensajes[1].tieneImagen)
        assertEquals(300, p.maxTokens)
        // Sin guion, el motor falla: se dice el motivo.
        assertTrue(h.completar("""{"mensajes":[{"rol":"usuario","partes":[{"texto":"x"}]}]}""").startsWith("{\"error\""))
        // Sin la nube no se intenta.
        val local = HojaAlLienzo(c, MensajesStore(c), null, motor, esNube = false, parar = AtomicBoolean(false), destino = null)
        assertEquals(HojaAlLienzo.SOLO_NUBE, local.hacer(kotlinx.serialization.json.JsonObject(emptyMap())).exceptionOrNull()?.message)
    }

    @Test fun `pintar devuelve la hoja a su tamano con lo hecho encima`() {
        val h = HojaAlLienzo(c, MensajesStore(c), null, MotorGuionado(emptyList()), esNube = true, parar = AtomicBoolean(false), destino = null)
        val b = h.pintar(documento(), ByteArray(4), 1000f, 500f, 200)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(200, bb.int); assertEquals(100, bb.int)
        assertEquals(8 + 200 * 100 * 4, b.size)
    }
}
