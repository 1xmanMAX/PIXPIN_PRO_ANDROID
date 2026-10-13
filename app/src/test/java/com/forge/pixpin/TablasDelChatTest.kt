package com.forge.pixpin

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.LibroDelChat
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.TablasDelChat
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.TablasEnDisco
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Las tablas que son mensajes del chat**, como en el PC (13-oct-2026): la mini-app `tabla` y las
 * hojas editadas de un Excel (`referencia = libro-<mensaje>-<hoja>`). Se abren por su id con
 * [TablasEnDisco] y se guardan en el texto de su mensaje, sin perder lo que no se conoce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TablasDelChatTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val disco get() = TablasEnDisco.de(context.filesDir)

    /** El enganche es global y Robolectric cambia de aplicación (y de carpeta) en cada prueba. */
    @org.junit.Before
    fun enganchar() { TablasEnDisco.enMensajes = TablasDelChat(context) }

    @Test
    fun `una tabla del PC se abre y se guarda en su mismo mensaje`() {
        val store = MensajesStore(context)
        val json = """{"nombre":"Del PC","celdas":{"A1":"2","A2":"=A1*3"},"estilos":{"A1":{"k":true,"zz":1}},"tocado":10,"futuro":"sí"}"""
        store.anadir(Mensaje(id = "pc-1", cuando = 1000, clase = Clase.MINIAPP, miniapp = "tabla", nombre = "Del PC", texto = json))
        val t = disco.cargar("mini-pc-1")!!
        assertEquals("=A1*3", t.celdas["A2"])
        assertTrue(t.estilos["A1"]!!.k)
        assertTrue(disco.guardar("mini-pc-1", t.copy(nombre = "Cambiada", celdas = t.celdas + ("B1" to "hola"), tocado = 1500)))
        val m = store.leer().single { it.id == "pc-1" }
        val j = Json.parseToJsonElement(m.texto).jsonObject
        assertEquals("\"hola\"", j["celdas"]!!.jsonObject["B1"].toString())
        assertEquals("\"sí\"", j["futuro"].toString())
        assertEquals("1", j["estilos"]!!.jsonObject["A1"]!!.jsonObject["zz"].toString())
        assertTrue("tocado después de nacer, como `editada_a`", j["tocado"].toString().toLong() >= 1000 + 2001)
        assertEquals("Cambiada", m.nombre)
        // Caso negativo: no queda un archivo suelto con su id.
        assertTrue(!disco.archivo("mini-pc-1").exists())
    }

    @Test
    fun `la hoja de un Excel editada pasa a ser un mensaje con la referencia del libro, y las demas tambien`() {
        val store = MensajesStore(context)
        store.anadir(Mensaje(id = "xl-7", cuando = 1, clase = Clase.ARCHIVO, nombre = "Cuentas.xlsx", ruta = "/x/Cuentas.xlsx", proyecto = "obra"))
        disco.enArchivo("libro-xl-7-0", TablaDeCalculo(nombre = "Cuentas · Gastos", celdas = mapOf("A1" to "1")))
        disco.enArchivo("libro-xl-7-1", TablaDeCalculo(nombre = "Cuentas · Resumen", celdas = mapOf("A1" to "2")))
        assertTrue("leer del Excel no llena el chat", TablasDelChat.hechas(store.leer(), "xl-7").isEmpty())
        assertTrue(disco.guardar("libro-xl-7-1", TablaDeCalculo(nombre = "Cuentas · Resumen", celdas = mapOf("A1" to "20"))))
        val hechas = TablasDelChat.hechas(store.leer(), "xl-7")
        assertEquals(listOf(0, 1), hechas.map { it.first })
        assertEquals(listOf("libro-xl-7-0", "libro-xl-7-1"), hechas.map { it.second.referencia })
        assertTrue(hechas.all { it.second.proyecto == "obra" && it.second.miniapp == "tabla" && it.second.clase == Clase.MINIAPP })
        assertEquals("20", disco.cargar("libro-xl-7-1")!!.celdas["A1"])
        // Guardar otra vez cambia el mismo mensaje, no crea otro; y lo viejo no se borra.
        disco.guardar("libro-xl-7-0", TablaDeCalculo(nombre = "Cuentas · Gastos", celdas = mapOf("A1" to "5")))
        assertEquals(2, TablasDelChat.hechas(store.leer(), "xl-7").size)
        assertEquals("5", disco.cargar("libro-xl-7-0")!!.celdas["A1"])
        assertTrue(disco.archivo("libro-xl-7-0").exists())
        // Las pestañas salen de los mensajes.
        assertEquals(listOf("libro-xl-7-0", "libro-xl-7-1"), LibroDelChat.tablas(context, "xl-7", null, "Cuentas.xlsx").map { it.first })
    }

    @Test
    fun `las hojas editadas de antes, solo en archivo, se pasan a mensajes al abrir el libro`() {
        val store = MensajesStore(context)
        store.anadir(Mensaje(id = "xl-9", cuando = 1, clase = Clase.ARCHIVO, nombre = "Viejo.xlsx", ruta = "/x/Viejo.xlsx"))
        disco.enArchivo("libro-xl-9-0", TablaDeCalculo(nombre = "Viejo · Hoja1", celdas = mapOf("A1" to "editada aquí")))
        LibroDelChat.tablas(context, "xl-9", "/x/Viejo.xlsx", "Viejo.xlsx")
        val m = TablasDelChat.hechas(store.leer(), "xl-9").single().second
        assertEquals("editada aquí", TablaDeCalculo.deJson(m.texto)!!.celdas["A1"])
        assertTrue(disco.archivo("libro-xl-9-0").exists())
    }

    @Test
    fun `una tabla del chat cambiada sale al instante, como las tareas`() {
        val t = Mensaje(id = "t", cuando = 1, clase = Clase.MINIAPP, miniapp = "tabla", texto = "{\"celdas\":{}}", proyecto = "obra")
        val otra = Mensaje(id = "n", cuando = 1, clase = Clase.MINIAPP, miniapp = "contador", texto = "1")
        assertEquals(setOf("obra"), MensajesStore.chatsConTareasCambiadas(listOf(t, otra), listOf(t.copy(texto = "{\"celdas\":{\"A1\":\"1\"}}"), otra)))
        // Caso negativo: otra mini-app no dispara nada.
        assertEquals(emptySet<String>(), MensajesStore.chatsConTareasCambiadas(listOf(t, otra), listOf(t, otra.copy(texto = "2"))))
    }
}
