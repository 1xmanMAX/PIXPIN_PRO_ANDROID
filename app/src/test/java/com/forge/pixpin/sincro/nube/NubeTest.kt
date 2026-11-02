package com.forge.pixpin.sincro.nube

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.sincro.Aparato
import com.forge.pixpin.sincro.Disco
import com.forge.pixpin.sincro.Identidad
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** **La nube de los chats compartidos** (2-nov-2026): las pruebas de `nube/` del PC, contra una nube falsa. */
class NubeTest {
    private lateinit var raiz: File
    private lateinit var max: Disco
    private lateinit var ana: Disco
    private var reloj = 1_000_000L
    private val chat = "pr-1"

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("nube").toFile()
        max = Disco(File(raiz, "max/files").apply { mkdirs() })
        ana = Disco(File(raiz, "ana/otra/files").apply { mkdirs() })
        max.identidad.guardar(Identidad(Aparato("id-max", "Max")))
        ana.identidad.guardar(Identidad(Aparato("id-ana", "Ana")))
    }

    @After
    fun desmontar() { raiz.deleteRecursively() }

    private fun mensaje(d: Disco, id: String, texto: String, clase: Clase = Clase.NOTA, referencia: String? = null): Mensaje {
        val m = Mensaje(id = id, cuando = reloj++, clase = clase, texto = texto, proyecto = chat, referencia = referencia, numero = 1)
        File(d.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")
        return m
    }

    private fun fig(id: String, x: Int, v: Int = 1, nonce: Int = 7) = """{"id":"$id","type":"rectangle","x":$x,"version":$v,"versionNonce":$nonce}"""

    private fun lienzo(d: Disco, vararg figuras: String) {
        val f = File(d.filesDir, "pins/draw/d1.excalidraw.gz").apply { parentFile!!.mkdirs() }
        GZIPOutputStream(f.outputStream()).use { it.write(figuras.joinToString(",", "{\"elements\":[", "],\"files\":{}}").toByteArray()) }
    }

    private fun figuras(d: Disco): List<String> =
        (TrazosNube.escenaDe(GZIPInputStream(File(d.filesDir, "pins/draw/d1.excalidraw.gz").inputStream()).use { it.readBytes().decodeToString() })["elements"] as JsonArray)
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun vuelta(d: Disco, n: NubeFalsa, bajar: Boolean = false, r: Retencion = Retencion.Nada) =
        VueltaNube(d, ClienteNube("https://obra.test", "pxn_abcdefghijk", n), deltas = true, retencion = r, ahora = reloj++).deChat(chat, bajar)

    private fun montarChat(d: Disco) {
        d.guardarProyecto(Proyecto(chat, "Obra", hojas = listOf(Hoja("h1", "Planta", dibujo = "d1")), tocado = 5))
        mensaje(d, "m1", "hola")
        mensaje(d, "m2", "", Clase.DIBUJO, referencia = "d1")
    }

    @Test
    fun `la regla gana la version mayor y a igualdad el nonce menor`() {
        assertTrue(TrazosNube.gana(TrazosNube.Version(3, 9), TrazosNube.Version(2, 1)))
        assertTrue(TrazosNube.gana(TrazosNube.Version(2, 1), TrazosNube.Version(2, 5)))
        assertFalse(TrazosNube.gana(TrazosNube.Version(2, 5), TrazosNube.Version(2, 5)))
        val base = TrazosNube.escenaDe("{\"elements\":[${fig("a", 0)}],\"files\":{}}")
        val k = TrazosNube.Conocido.de(base)
        assertNull("sin cambios no hay delta", TrazosNube.calcular("x", "s", k, base))
        val nueva = TrazosNube.escenaDe("{\"elements\":[${fig("a", 0)},${fig("b", 5)}],\"files\":{}}")
        val d = TrazosNube.calcular("x", "s", k, nueva)!!
        assertEquals(listOf("b"), d["elementos"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        // Lápidas: borrar sube la versión y queda.
        val conBorrado = JsonObject(d + ("borrados" to JsonArray(listOf(JsonPrimitive("a")))))
        val (e, cambio) = TrazosNube.aplicar(base, conBorrado)
        assertTrue(cambio)
        assertEquals(2, e["elements"]!!.jsonArray.size)
        assertEquals("true", e["elements"]!!.jsonArray[0].jsonObject["isDeleted"]!!.jsonPrimitive.content)
        assertTrue(TrazosNube.compactar(21, 10, 1000))
        assertTrue(TrazosNube.compactar(2, 600, 1000))
        assertFalse(TrazosNube.compactar(2, 100, 1000))
    }

    @Test
    fun `un chat sube y otro aparato lo baja entero con sus rutas`() {
        val n = NubeFalsa()
        montarChat(max)
        lienzo(max, fig("a", 0))
        val inf = vuelta(max, n)
        assertTrue(inf.subido)
        vuelta(ana, n, bajar = true)
        assertEquals(setOf("m1", "m2"), ana.mensajesPorClave(chat).values.map { it.id }.toSet())
        assertEquals("Obra", ana.leerProyectos().single().nombre)
        assertEquals(listOf("a"), figuras(ana))
        assertFalse("sin rutas de otro aparato", File(ana.filesDir, "pins/draw/d1.excalidraw.gz").let { f -> GZIPInputStream(f.inputStream()).use { it.readBytes().decodeToString() } }.contains(max.filesDir.path))
    }

    @Test
    fun `una raya nueva sube como delta pequeno y no el lienzo entero`() {
        val n = NubeFalsa()
        montarChat(max)
        lienzo(max, *(0 until 300).map { fig("t$it", it) }.toTypedArray())
        vuelta(max, n)
        val antes = n.bytesSubidos
        lienzo(max, *((0 until 300).map { fig("t$it", it) } + fig("raya", 999)).toTypedArray())
        File(max.filesDir, "pins/draw/d1.excalidraw.gz").setLastModified(System.currentTimeMillis() + 10_000)
        vuelta(max, n)
        val subido = n.bytesSubidos - antes
        assertTrue("subió $subido bytes", subido < 3_000)
        assertTrue(n.cuerpos.last().contains("\"deltas\""))
        vuelta(ana, n, bajar = true)
        assertEquals(301, figuras(ana).size)
    }

    @Test
    fun `dos personas pintan a la vez y se suman con el 409`() {
        val n = NubeFalsa()
        montarChat(max)
        lienzo(max, fig("a", 0))
        vuelta(max, n)
        vuelta(ana, n, bajar = true)
        lienzo(max, fig("a", 0), fig("deMax", 1))
        lienzo(ana, fig("a", 0), fig("deAna", 2))
        vuelta(max, n)
        val inf = vuelta(ana, n)
        assertEquals(1, inf.conflictos)
        assertEquals(setOf("a", "deMax", "deAna"), figuras(ana).toSet())
        vuelta(max, n, bajar = true)
        assertEquals(setOf("a", "deMax", "deAna"), figuras(max).toSet())
    }

    @Test
    fun `un mensaje borrado aqui se quita alla por su id`() {
        val n = NubeFalsa()
        montarChat(max)
        vuelta(max, n)
        vuelta(ana, n, bajar = true)
        max.aplicarMensajes(chat, emptyList(), listOf(com.forge.pixpin.sincro.Codigos.unico(max.mensajesPorClave(chat).values.first { it.id == "m1" })))
        vuelta(max, n)
        assertTrue(n.cuerpos.last().contains("\"quitar\":[\"m1\"]"))
        vuelta(ana, n, bajar = true)
        assertEquals(setOf("m2"), ana.mensajesPorClave(chat).values.map { it.id }.toSet())
    }

    @Test
    fun `sin deltas sube el inventario entero`() {
        val n = NubeFalsa(conDeltas = false)
        montarChat(max)
        lienzo(max, fig("a", 0))
        VueltaNube(max, ClienteNube("https://obra.test", "pxn_abcdefghijk", n), deltas = false, ahora = reloj++).deChat(chat, false)
        VueltaNube(ana, ClienteNube("https://obra.test", "pxn_abcdefghijk", n), deltas = false, ahora = reloj++).deChat(chat, true)
        assertEquals(listOf("a"), figuras(ana))
    }

    @Test
    fun `sin conexion no sube y descartar vuelve a lo de la nube`() {
        val n = NubeFalsa()
        montarChat(max)
        lienzo(max, fig("a", 0))
        File(max.filesDir, "pins/draw/anot-ABCDE23456.marcas").apply { parentFile!!.mkdirs(); writeText("1:0.5:📌") }
        vuelta(max, n)
        val sc = SinConexion(max.filesDir)
        sc.retener(chat, "d1")
        assertTrue(sc.esta(chat, "d1"))
        assertTrue(sc.retenido(chat, "pins/draw/d1.excalidraw.gz"))
        assertFalse(sc.retenido(chat, "pins/draw/anot-ABCDE23456.marcas"))
        lienzo(max, fig("a", 0), fig("sinRed", 3))
        val antes = n.cuerpos.size
        vuelta(max, n, r = sc)
        assertEquals("nada subido de lo retenido", antes, n.cuerpos.size)
        sc.descartar(chat, "d1")
        assertEquals(listOf("a"), figuras(max))
        assertFalse(sc.esta(chat, "d1"))
        assertTrue(SinConexion.pertenece("ABCDE23456|d7", "pins/draw/d7.vistas.json"))
        assertTrue(SinConexion.pertenece("ABCDE23456", "pins/draw/anot-ABCDE23456-p0.excalidraw.gz"))
        assertFalse(SinConexion.pertenece("ABCDE23456", "pins/draw/sub/anot-ABCDE23456.marcas"))
    }

    @Test
    fun `los modos y lo sabido se guardan y lo puesto en local a mano no vuelve solo`() {
        var c = ChatsEnLaNube()
        assertEquals(Modo.LOCAL, c.modo("p1"))
        c = c.adoptar("p1")!!
        assertEquals(Modo.NUBE, c.modo("p1"))
        c = c.poner("p1", Modo.LOCAL)
        assertNull(c.adoptar("p1"))
        ChatsEnLaNube.cambiar(max.filesDir) { c }
        assertEquals(c, ChatsEnLaNube.leer(max.filesDir))
        assertTrue(Nube.llaveValida("pxn_abcdefghijk"))
        assertFalse(Nube.llaveValida("sk-abc"))
        assertTrue(Nube.direccionValida("https://obra.ejemplo.com/"))
        assertFalse(Nube.direccionValida("http://obra.ejemplo.com"))
        assertEquals("a%20b%2F%C3%B1", ClienteNube.codificar("a b/ñ"))
        assertTrue(Nube.espacioCompartido("max", "pr-1").startsWith("compartido-"))
    }
}
