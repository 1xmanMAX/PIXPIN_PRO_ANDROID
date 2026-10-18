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

    /** Como `las_hojas_viejas_del_movil_se_editan_y_al_guardar_pasan_a_mensajes` del PC (punto 4 del manual). */
    @Test
    fun `las hojas viejas migradas salen editadas, con el estilo de ahora y sin perder lo desconocido`() {
        val store = MensajesStore(context)
        store.anadir(Mensaje(id = "xl-5", cuando = 1_700_000_000_000, clase = Clase.ARCHIVO, nombre = "Cuentas.xlsx", ruta = "/x/Cuentas.xlsx"))
        val hoja0 = """{"nombre":"Cuentas · Gastos","celdas":{"A1":"Editado en el movil","B1":"5"},"tocado":1700000500000,"protegida":true}"""
        val hoja1 = """{"nombre":"Cuentas · Resumen","celdas":{"A1":"Total"},"estilos":{"A1":"negrita"},"futuro":{"x":1}}"""
        disco.archivo("libro-xl-5-0").apply { parentFile!!.mkdirs() }.writeText(hoja0)
        disco.archivo("libro-xl-5-1").writeText(hoja1)
        val antes = System.currentTimeMillis()
        assertEquals(listOf("libro-xl-5-0", "libro-xl-5-1"), LibroDelChat.tablas(context, "xl-5", "/x/Cuentas.xlsx", "Cuentas.xlsx").map { it.first })
        val hechas = TablasDelChat.hechas(store.leer(), "xl-5")
        assertEquals(2, hechas.size)
        for ((_, m) in hechas) {
            val t = TablaDeCalculo.deJson(m.texto)!!
            assertTrue("«editada»: el PC no la esconde tras el .xlsx", t.tocado > m.cuando + 2000)
            assertTrue(t.tocado >= antes)
        }
        val t0 = TablaDeCalculo.deJson(hechas[0].second.texto)!!
        assertEquals("Editado en el movil", t0.celdas["A1"])
        assertTrue(t0.protegida)
        val j1 = Json.parseToJsonElement(hechas[1].second.texto).jsonObject
        assertEquals("""{"n":true}""", j1["estilos"]!!.jsonObject["A1"].toString())
        assertEquals("""{"x":1}""", j1["futuro"].toString())
        // Los archivos viejos no se tocan.
        assertEquals(hoja0, disco.archivo("libro-xl-5-0").readText())
        assertEquals(hoja1, disco.archivo("libro-xl-5-1").readText())
    }

    /** Punto 5 del manual: leer un libro no deja nada en `tablas/` (lo que viaja) ni crea mensajes. */
    @Test
    fun `leer un Excel deja una copia de trabajo que no viaja y editarlo crea los mensajes`() {
        val store = MensajesStore(context)
        val csv = java.io.File(context.cacheDir, "Notas.csv").apply { parentFile!!.mkdirs(); writeText("a;b\n1;2\n") }
        store.anadir(Mensaje(id = "xl-c", cuando = 1_000, clase = Clase.ARCHIVO, nombre = "Notas.csv", ruta = csv.absolutePath))
        val ids = LibroDelChat.tablas(context, "xl-c", csv.absolutePath, "Notas.csv").map { it.first }
        assertEquals(listOf("libro-xl-c-0"), ids)
        assertTrue(!disco.archivo("libro-xl-c-0").exists())
        assertTrue(disco.leida("libro-xl-c-0").exists())
        assertTrue(TablasDelChat.hechas(store.leer(), "xl-c").isEmpty())
        val leida = disco.cargar("libro-xl-c-0")!!
        assertEquals("1", leida.celdas["A2"])
        // Volver a abrirlo no lo migra como si se hubiera editado.
        LibroDelChat.tablas(context, "xl-c", csv.absolutePath, "Notas.csv")
        assertTrue(TablasDelChat.hechas(store.leer(), "xl-c").isEmpty())
        // Editar: nace el mensaje, con la edición, y nada en `tablas/`.
        assertTrue(disco.guardar("libro-xl-c-0", leida.copy(celdas = leida.celdas + ("C1" to "nuevo"))))
        val m = TablasDelChat.hechas(store.leer(), "xl-c").single().second
        assertEquals("nuevo", TablaDeCalculo.deJson(m.texto)!!.celdas["C1"])
        assertTrue(!disco.archivo("libro-xl-c-0").exists())
    }

    /** Como `una_hoja_vieja_rota_no_se_pierde_y_se_lee_el_libro` del PC. */
    @Test
    fun `una hoja vieja rota no se pierde y se lee el libro`() {
        val store = MensajesStore(context)
        val csv = java.io.File(context.cacheDir, "Rota.csv").apply { parentFile!!.mkdirs(); writeText("Original\n") }
        store.anadir(Mensaje(id = "xl-r", cuando = 1_000, clase = Clase.ARCHIVO, nombre = "Rota.csv", ruta = csv.absolutePath))
        disco.archivo("libro-xl-r-0").apply { parentFile!!.mkdirs() }.writeText("{ esto no es una tabla")
        assertEquals(listOf("libro-xl-r-0"), LibroDelChat.tablas(context, "xl-r", csv.absolutePath, "Rota.csv").map { it.first })
        assertTrue("una hoja que no se entiende no se migra", TablasDelChat.hechas(store.leer(), "xl-r").isEmpty())
        val t = disco.cargar("libro-xl-r-0")!!
        assertEquals("se lee el libro, como si no hubiera nada", "Original", t.celdas["A1"])
        assertTrue(disco.guardar("libro-xl-r-0", t.copy(celdas = t.celdas + ("A2" to "1"))))
        val m = TablasDelChat.hechas(store.leer(), "xl-r").single().second
        assertEquals("1", TablaDeCalculo.deJson(m.texto)!!.celdas["A2"])
        assertEquals("el archivo roto sigue tal cual", "{ esto no es una tabla", disco.archivo("libro-xl-r-0").readText())
    }

    /** Una hoja de un libro sin nada aquí (llegó un proyecto que la nombra): se lee el libro. */
    @Test
    fun `una hoja de un libro sin copia aqui se prepara leyendo el libro`() {
        val store = MensajesStore(context)
        val csv = java.io.File(context.cacheDir, "Otro.csv").apply { parentFile!!.mkdirs(); writeText("x;y\n") }
        store.anadir(Mensaje(id = "xl-p", cuando = 1_000, clase = Clase.ARCHIVO, nombre = "Otro.csv", ruta = csv.absolutePath))
        assertEquals(TablasEnDisco.Lectura.NoHay, disco.leer("libro-xl-p-0"))
        assertTrue(LibroDelChat.prepararHoja(context, "libro-xl-p-0"))
        assertEquals("x", disco.cargar("libro-xl-p-0")!!.celdas["A1"])
        // Caso negativo: ni una hoja que el libro no tiene ni un id que no es de libro.
        assertTrue(!LibroDelChat.prepararHoja(context, "libro-xl-p-3"))
        assertTrue(!LibroDelChat.prepararHoja(context, "t-1"))
    }

    /** Punto 1 del manual: un mensaje-tabla con algo que no se lee no se abre en blanco ni se pisa. */
    @Test
    fun `una tabla del chat que no se lee no se abre ni se guarda encima`() {
        val store = MensajesStore(context)
        store.anadir(Mensaje(id = "roto", cuando = 1, clase = Clase.MINIAPP, miniapp = "tabla", texto = "no es json"))
        assertEquals(TablasEnDisco.Lectura.NoSeLee, disco.leer("mini-roto"))
        assertTrue(!disco.guardar("mini-roto", TablaDeCalculo(nombre = "encima")))
        assertEquals("no es json", store.leer().single { it.id == "roto" }.texto)
        assertTrue(!disco.archivo("mini-roto").exists())
        // Un estilo-palabra del PC viejo ya no deja la tabla sin abrir.
        store.anadir(Mensaje(id = "vieja", cuando = 1, clase = Clase.MINIAPP, miniapp = "tabla", texto = """{"celdas":{"A1":"x"},"estilos":{"A1":"negrita"}}"""))
        assertTrue(disco.cargar("mini-vieja")!!.estilos["A1"]!!.n)
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
