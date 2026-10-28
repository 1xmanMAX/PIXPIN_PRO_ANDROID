package com.forge.pixpin.guardados

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.FormatosDeCompartir
import com.forge.pixpin.ui.FormatosDeCompartir.Clase as Formato
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **Compartir un cuaderno con todos los formatos** (28-oct-2026, [CompartirCarpeta]).
 *
 * Un cuaderno de dos páginas —un lienzo y una nota— tiene que salir en la hoja con los formatos de
 * toda la aplicación, en su orden, con las páginas para elegir, y **cada formato tiene que dar un
 * archivo con algo dentro**. Su `.pixpin` lleva la carpeta para el teléfono y para el PC
 * (`guardados.jsonl`), con solo las páginas marcadas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompartirCarpetaTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var lienzo: Mensaje
    private lateinit var nota: Mensaje
    private lateinit var carpeta: Mensaje
    private lateinit var todos: List<Mensaje>

    @Before
    fun cuadernoDeDosPaginas() {
        val escena = Scene(
            elements = listOf(
                Element(id = "r", type = ElementType.RECTANGLE, x = 0.0, y = 0.0, width = 120.0, height = 80.0, seed = 1),
                Element(id = "e", type = ElementType.ELLIPSE, x = 140.0, y = 10.0, width = 60.0, height = 60.0, seed = 2)
            )
        )
        assertNotNull(ExcalidrawStore.guardar(context, "dib-cuaderno", escena))
        lienzo = Mensaje(id = "p1", cuando = 1_000, clase = Clase.DIBUJO, nombre = "Lienzo", referencia = "dib-cuaderno", proyecto = "chat")
        nota = Mensaje(id = "p2", cuando = 2_000, clase = Clase.NOTA, texto = "# Apuntes\nHola **mundo**", proyecto = "chat")
        val doc = CarpetaDePaginas().anadir("p1", "Día 1")!!.anadir("p2", "Día 2")!!
        carpeta = CarpetaDePaginas.mensajeNuevo("Diario", 3_000, "chat", doc)
        todos = listOf(lienzo, nota, carpeta)
    }

    private fun hoja(): Compartible = CompartirCarpeta.compartible(context, carpeta, todos)!!

    @Test
    fun losFormatosDeUnCuadernoSonLosDeTodaLaAplicacionYEnSuOrden() {
        val c = hoja()
        assertEquals(FormatosDeCompartir.Contenido.CARPETA, c.contenido)
        val visibles = FormatosDeCompartir.visibles(c.contenido, c.formatos) { it.id }
        assertEquals(
            listOf(Formato.PNG, Formato.JPG, Formato.PDF, Formato.WEB, Formato.WORD, Formato.MARKDOWN, Formato.TEXTO, Formato.PIXPIN, Formato.ORIGINAL, Formato.WIFI),
            visibles.map { FormatosDeCompartir.claseDe(it.id) }
        )
        // Nada se queda fuera de la hoja por no estar en la tabla.
        assertEquals(c.formatos.size, visibles.size)
        assertEquals(
            listOf("Imagen PNG", "Imagen JPG", "PDF", "Página web (HTML)", "Word", "Markdown", "Texto", "PixPin (.pixpin)", "Original (ZIP)", "Por Wi‑Fi a mis aparatos"),
            visibles.map { FormatosDeCompartir.rotulo(it.id, it.nombre) }
        )
        // Abre en el PDF: lo que más se comparte de un cuaderno.
        assertEquals("pdf", c.formatos.first().id)
    }

    @Test
    fun lasPaginasSeEligenEnTodoLoQueLasAdmite() {
        val c = hoja()
        assertEquals(listOf("p1", "p2"), c.paginas.map { it.clave })
        assertEquals(listOf("Día 1", "Día 2"), c.paginas.map { it.nombre })
        assertEquals(listOf("Lienzo", "Nota"), c.paginas.map { it.detalle })
        val porId = c.formatos.associateBy { it.id }
        for (id in listOf("pdf", "web", "word", "md", "texto", "editable", "original")) assertEquals(id, Compartible.VARIAS, porId.getValue(id).paginas)
        for (id in listOf("png", "jpg")) assertEquals(id, Compartible.UNA, porId.getValue(id).paginas)
        assertEquals(Compartible.NINGUNA, porId.getValue("wifi").paginas)
        // El texto, solo de la página que lo lleva.
        assertFalse(porId.getValue("word").admite("p1"))
        assertTrue(porId.getValue("word").admite("p2"))
        assertTrue(porId.getValue("png").admite("p1"))
    }

    /** Genera [f] con las páginas que admite (una, si es de una sola); el fallo, por escrito. */
    private fun fallo(c: Compartible, f: Compartible.Formato): String? {
        val generar = f.generar ?: return null
        val admitidas = c.paginas.map { it.clave }.filter(f.admite)
        val claves = when (f.paginas) {
            Compartible.NINGUNA -> emptyList()
            Compartible.UNA -> admitidas.take(1)
            else -> admitidas
        }
        val salida = runCatching { runBlocking { generar(claves) } }
        val archivo = salida.getOrNull()?.archivo
        return if (archivo == null || !archivo.isFile || archivo.length() == 0L) "${f.id}: ${salida.exceptionOrNull() ?: archivo?.length() ?: "nada"}" else null
    }

    /** Lo que sale del PDF de Android (`android.graphics.pdf`): el PDF y la página en imagen. */
    private val delPdfDeAndroid = setOf("pdf", "png", "jpg")

    @Test
    fun cadaFormatoDaUnArchivoConAlgoDentro() {
        val c = hoja()
        val probados = c.formatos.filter { it.generar != null && it.id !in delPdfDeAndroid }
        assertEquals(listOf("web", "word", "md", "texto", "editable", "original"), probados.map { it.id })
        val fallos = probados.mapNotNull { fallo(c, it) }
        assertTrue("sin archivo: $fallos", fallos.isEmpty())
        // La Wi‑Fi no da archivo: abre la pantalla de enviar.
        assertNotNull(c.formatos.single { it.id == "wifi" }.accion)
    }

    /**
     * **El PDF y la imagen de una página**, que pinta `PdfDocument`. Robolectric no lo implementa (ni
     * con los gráficos nativos: «document is closed!»), así que aquí solo se prueban donde sí lo está;
     * en el teléfono salen como el PDF de siempre de la carpeta ([CarpetaEnPdf]).
     */
    @Test
    fun elPdfYLaImagenDeUnaPaginaSiHayPdfDocument() {
        val hayPdf = runCatching {
            android.graphics.pdf.PdfDocument().run {
                finishPage(startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 10, 1).create())); close()
            }
        }.isSuccess
        org.junit.Assume.assumeTrue("Robolectric sin PdfDocument", hayPdf)
        val c = hoja()
        val fallos = c.formatos.filter { it.id in delPdfDeAndroid }.mapNotNull { fallo(c, it) }
        assertTrue("sin archivo: $fallos", fallos.isEmpty())
    }

    @Test
    fun elTextoLlevaElTituloDeSuPagina() {
        val md = runBlocking { hoja().formatos.single { it.id == "md" }.generar!!(listOf("p1", "p2")) }!!.archivo.readText()
        assertTrue(md, md.startsWith("## Día 2\n\n# Apuntes"))
    }

    @Test
    fun elPixpinLlevaLaCarpetaParaElTelefonoYParaElPc() {
        val f = runBlocking { hoja().formatos.single { it.id == "editable" }.generar!!(listOf("p1", "p2")) }!!.archivo
        ZipFile(f).use { z ->
            val nombres = z.entries().toList().map { it.name }.toSet()
            assertTrue(nombres.toString(), "proyecto.json" in nombres)
            assertTrue(nombres.toString(), "lienzos/dib-cuaderno.excalidraw" in nombres)
            assertTrue(nombres.toString(), "chat/mensajes.jsonl" in nombres)
            assertTrue(nombres.toString(), CompartirCarpeta.CHAT_DEL_PC in nombres)

            val delTelefono = z.getInputStream(z.getEntry("chat/mensajes.jsonl")).readBytes().decodeToString().lines().filter { it.isNotBlank() }
            assertEquals(3, delTelefono.size)
            val delPc = z.getInputStream(z.getEntry(CompartirCarpeta.CHAT_DEL_PC)).readBytes().decodeToString().lines().filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it) as JsonObject }
            assertEquals(setOf("p1", "p2", carpeta.id), delPc.map { it["id"]!!.jsonPrimitive.content }.toSet())
            // El lienzo, por la ruta con la que el PC apunta un lienzo; y que está dentro.
            assertEquals("lienzos/dib-cuaderno.excalidraw", delPc.single { it["id"]!!.jsonPrimitive.content == "p1" }["ruta"]!!.jsonPrimitive.content)
            // La carpeta, con su documento y sus dos páginas.
            val laCarpeta = delPc.single { it["id"]!!.jsonPrimitive.content == carpeta.id }
            assertEquals("carpeta", laCarpeta["miniapp"]!!.jsonPrimitive.content)
            assertEquals(listOf("p1", "p2"), CarpetaDePaginas.leer(laCarpeta["texto"]!!.jsonPrimitive.content).paginas.map { it.id })
            assertTrue("en el PC el chat es la carpeta: sin proyecto", delPc.none { "proyecto" in it })
        }
        // Y el teléfono lo vuelve a abrir con su chat.
        val abierto = com.forge.pixpin.motor.PaquetePixpin.abrir(f)!!
        assertEquals("Diario", abierto.proyecto.nombre)
        assertEquals(listOf("dib-cuaderno"), abierto.proyecto.hojas.mapNotNull { it.dibujo })
        assertNotNull(abierto.chat)
    }

    @Test
    fun elPixpinDeUnaPaginaSoloLlevaEsa() {
        val f = runBlocking { hoja().formatos.single { it.id == "editable" }.generar!!(listOf("p2")) }!!.archivo
        ZipFile(f).use { z ->
            val delPc = z.getInputStream(z.getEntry(CompartirCarpeta.CHAT_DEL_PC)).readBytes().decodeToString().lines().filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it) as JsonObject }
            assertEquals(setOf("p2", carpeta.id), delPc.map { it["id"]!!.jsonPrimitive.content }.toSet())
            val laCarpeta = delPc.single { it["id"]!!.jsonPrimitive.content == carpeta.id }
            assertEquals(listOf("p2"), CarpetaDePaginas.leer(laCarpeta["texto"]!!.jsonPrimitive.content).paginas.map { it.id })
            assertNull("sin el lienzo, que no va", z.getEntry("lienzos/dib-cuaderno.excalidraw"))
        }
    }

    @Test
    fun elOriginalEsUnZipConCadaPaginaNumerada() {
        val f = runBlocking { hoja().formatos.single { it.id == "original" }.generar!!(listOf("p1", "p2")) }!!.archivo
        val nombres = ZipFile(f).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("01 - Día 1.excalidraw", "02 - Día 2.md"), nombres)
    }

    @Test
    fun lasRutasDelChatEnElCuadernoDelPc() {
        val texto = listOf(
            """{"id":"a","cuando":1,"clase":"IMAGEN","texto":"","ruta":"adjunto:a-foto.png","nombre":"foto.png","proyecto":"cuaderno-9"}""",
            """{"id":"b","cuando":2,"clase":"PAGINA","ruta":"documento:","pagina":3,"referencia":"dib-b","proyecto":"cuaderno-9"}""",
            """{"id":"c","cuando":3,"clase":"DIBUJO","nombre":"Día 1","referencia":"dib-c","proyecto":"cuaderno-9"}""",
            """{"id":"d","cuando":4,"clase":"NOTA","texto":"hola","proyecto":"cuaderno-9","cosaNueva":{"x":1}}"""
        ).joinToString("\n")
        val lineas = CompartirCarpeta.chatParaElPc(texto).lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it) as JsonObject }
        assertEquals(4, lineas.size)
        assertEquals("chat/adjuntos/a-foto.png", lineas[0]["ruta"]!!.jsonPrimitive.content)
        assertEquals("documento.pdf", lineas[1]["ruta"]!!.jsonPrimitive.content)
        assertEquals("lienzos/dib-c.excalidraw", lineas[2]["ruta"]!!.jsonPrimitive.content)
        assertNull(lineas[3]["ruta"])
        assertTrue(lineas.none { "proyecto" in it })
        // Lo que no se entiende, tal cual.
        assertEquals("""{"x":1}""", lineas[3]["cosaNueva"].toString())
    }

    @Test
    fun loMarcadoAbreLasCarpetas() {
        val suelta = Mensaje(id = "s", cuando = 5, clase = Clase.NOTA, texto = "suelta")
        // Una carpeta sola: su hoja.
        assertEquals(FormatosDeCompartir.Contenido.CARPETA, CompartirCarpeta.deVarios(context, listOf(carpeta), todos)?.contenido)
        assertNull(CompartirCarpeta.deVarios(context, listOf(carpeta, suelta), todos))
        // Con más cosas, sus páginas (sin repetir la que ya iba marcada) y no su documento.
        assertEquals(listOf("p1", "p2", "s"), CompartirCarpeta.conSusPaginas(listOf(carpeta, nota, suelta), todos).map { it.id })
    }

    @Test
    fun laCarpetaDeUnaGrabacionSaleComoUnaGrabacionConLaClaseEntera() {
        val audio = File(context.cacheDir, "clase.m4a").also { it.writeBytes(ByteArray(64) { 1 }) }
        val foto = File(context.cacheDir, "captura.png").also { it.writeBytes(ByteArray(32) { 2 }) }
        val voz = Mensaje(id = "v", cuando = 10, clase = Clase.VOZ, nombre = "Clase.m4a", ruta = audio.path, duracionMs = 60_000, transcripcion = "Hola clase")
        val captura = Mensaje(id = "f", cuando = 11, clase = Clase.IMAGEN, nombre = "captura.png", ruta = foto.path)
        val doc = CarpetaDePaginas.grabacion("v", 60_000).anadirAlMinuto("v", "", 0)!!.anadirAlMinuto("f", "⏱ 0:30", 30_000)!!
        val grabacion = CarpetaDePaginas.mensajeNuevo("Clase", 20, null, doc)
        val lista = listOf(voz, captura, grabacion)

        val c = CompartirCarpeta.compartible(context, grabacion, lista)!!
        assertEquals(FormatosDeCompartir.Contenido.GRABACION, c.contenido)
        val ids = FormatosDeCompartir.visibles(c.contenido, c.formatos) { it.id }.map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("pdf", "web", "word", "md", "texto", "editable", "original", "wifi")))
        // Un solo .pixpin, un solo original: los de la clase entera, con su carpeta.
        assertEquals(1, ids.count { it == "editable" })
        val pixpin = runBlocking { c.formatos.single { it.id == "editable" }.generar!!(emptyList()) }!!.archivo
        val delPc = ZipFile(pixpin).use { z -> z.getInputStream(z.getEntry(CompartirCarpeta.CHAT_DEL_PC)).readBytes().decodeToString() }
        assertTrue(delPc, delPc.contains("\"id\":\"v\"") && delPc.contains("\"id\":\"f\"") && delPc.contains("\"miniapp\":\"carpeta\""))
        val zip = runBlocking { c.formatos.single { it.id == "original" }.generar!!(emptyList()) }!!.archivo
        assertEquals(2, ZipFile(zip).use { it.size() })
    }
}
