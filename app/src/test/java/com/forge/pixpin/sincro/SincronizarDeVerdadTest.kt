package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * **Dos aparatos de mentira, sincronizándose de verdad.**
 *
 * Cada «aparato» es una carpeta `files` con lo que PixPin guardaría, y se hablan por un socket en
 * esta misma máquina con el protocolo y el cifrado de verdad. Las carpetas tienen rutas distintas
 * a propósito: así se comprueba que lo que viaja no lleva rutas de un aparato dentro.
 *
 * Es lo más cerca que se puede estar aquí de dos teléfonos en la misma Wi-Fi.
 */
class SincronizarDeVerdadTest {

    private lateinit var raiz: File
    private lateinit var telefono: Disco
    private lateinit var tableta: Disco
    private var reloj = 1_000_000L

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("sincro").toFile()
        telefono = Disco(File(raiz, "telefono/files").apply { mkdirs() })
        tableta = Disco(File(raiz, "tableta-de-otro-usuario/data/files").apply { mkdirs() })
        telefono.identidad.guardar(Identidad(Aparato("id-tel", "Teléfono")))
        tableta.identidad.guardar(Identidad(Aparato("id-tab", "Tableta")))
    }

    @After
    fun desmontar() {
        raiz.deleteRecursively()
    }

    // ------------------------------------------------------------ utilidades

    private fun mensaje(d: Disco, id: String, texto: String = "", clase: Clase = Clase.NOTA, proyecto: String? = null,
                        ruta: String? = null, referencia: String? = null): Mensaje {
        val lista = d.leerMensajes()
        val suyos = lista.filter { it.proyecto == proyecto }
        val letra = d.identidad.leer().yo.letra
        val m = Mensaje(id = id, cuando = reloj++, clase = clase, texto = texto, proyecto = proyecto, ruta = ruta,
            referencia = referencia, numero = (suyos.maxOfOrNull { it.numero } ?: 0) + 1, letra = letra)
        File(d.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")
        return m
    }

    /** Como hace el chat: reescribe sin el mensaje y deja la marca. */
    private fun borrar(d: Disco, id: String) {
        val antes = d.leerMensajes()
        val despues = antes.filter { it.id != id }
        File(d.filesDir, "guardados.jsonl").writeText(despues.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
        d.anotarBorrados(Disco.borradosEntre(antes, despues, d.identidad.leer().letra, reloj++))
    }

    private fun editar(d: Disco, id: String, cambio: (Mensaje) -> Mensaje) {
        val lista = d.leerMensajes().map { if (it.id == id) cambio(it) else it }
        File(d.filesDir, "guardados.jsonl").writeText(lista.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
    }

    private fun dibujo(d: Disco, id: String, texto: String, foto: String? = null): File {
        val f = File(d.filesDir, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }
        val fotoRuta = foto?.let { File(d.filesDir, "pins/draw/files/$it").absolutePath }
        val json = """{"elements":[{"text":"$texto"}],"files":{${if (fotoRuta != null) "\"$foto\":{\"path\":\"$fotoRuta\"}" else ""}}}"""
        GZIPOutputStream(f.outputStream()).use { it.write(json.toByteArray()) }
        return f
    }

    private fun textoDelDibujo(d: Disco, id: String): String =
        GZIPInputStream(File(d.filesDir, "pins/draw/$id.excalidraw.gz").inputStream()).use { it.readBytes().decodeToString() }

    private fun proyecto(d: Disco, p: Proyecto) = d.guardarProyecto(p)

    /** Abre un «aparato» que responde y devuelve el puerto. */
    private fun escuchar(d: Disco): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching { server.accept().use { s -> Respondedor(d, ahora = { reloj }).atender(s.getInputStream(), s.getOutputStream()) } }
            server.close()
        }
        hilo.start()
        return server to hilo
    }

    private fun <T> conectado(desde: Disco, hacia: Disco, unirme: Boolean = false, codigo: String? = null, uso: (Sesion) -> T): T {
        val (server, hilo) = escuchar(hacia)
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), desde, unirme, codigo, ahora = { reloj })
            val r = uso(sesion)
            sesion.adios()
            hilo.join(5000)
            return r
        }
    }

    private fun emparejar(): String {
        val codigo = telefono.crearGrupo(codigo = "ABCDE23456", ahora = reloj)
        conectado(tableta, telefono, unirme = true, codigo = codigo) {}
        return codigo
    }

    /** Sincroniza los chats dados, sin preguntas: lo cambiado en los dos se junta. Devuelve lo hecho. */
    private fun sincronizar(
        chats: List<String> = listOf(Disco.GENERAL),
        antesDeCerrar: () -> Unit = {},
        loMioManda: Boolean = false,
        bytes: (Long) -> Unit = {}
    ): Sesion.Hecho {
        val hecho = Sesion.Hecho()
        conectado(telefono, tableta) { s ->
            s.loMioManda = loMioManda
            for (chat in chats) {
                val prep = s.preparar(chat)
                s.aplicar(prep, hecho)
                val arch = s.prepararArchivos(prep)
                s.aplicarArchivos(arch, hecho)
                antesDeCerrar()
                s.cerrar(prep)
            }
            bytes(s.enviados + s.recibidos)
        }
        return hecho
    }

    /** Los códigos de chat de un chat: `1a`, `2·K7Q2`… */
    private fun senas(d: Disco, chat: String = Disco.GENERAL) =
        d.leerMensajes().filter { Disco.chatDe(it) == chat }.mapNotNull { Codigos.deChat(it) }.toSortedSet()

    /** Un lienzo de verdad: figuras con id, versión y hora. */
    private fun lienzo(d: Disco, id: String, vararg figuras: String) {
        val f = File(d.filesDir, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }
        val json = figuras.joinToString(",", prefix = "{\"elements\":[", postfix = "],\"files\":{}}") { it }
        GZIPOutputStream(f.outputStream()).use { it.write(json.toByteArray()) }
    }

    private fun fig(id: String, x: Int = 0, color: String = "#000", v: Int = 1, updated: Long = 1) =
        """{"id":"$id","type":"rectangle","x":$x,"strokeColor":"$color","version":$v,"versionNonce":7,"updated":$updated}"""

    private fun figuras(d: Disco, id: String): List<String> =
        Regex("\"id\":\"([^\"]+)\"").findAll(textoDelDibujo(d, id)).map { it.groupValues[1] }.toList()

    // ------------------------------------------------------------------ pruebas

    @Test
    fun `unirse da la siguiente letra y los dos se conocen`() {
        emparejar()
        val tel = telefono.identidad.leer()
        val tab = tableta.identidad.leer()
        assertEquals("a", tel.yo.letra)
        assertEquals("b", tab.yo.letra)
        assertEquals(tel.codigo, tab.codigo)
        assertEquals(setOf("id-tel", "id-tab"), tel.miembros.map { it.id }.toSet())
        assertEquals(setOf("id-tel", "id-tab"), tab.miembros.map { it.id }.toSet())
    }

    @Test
    fun `con otro codigo no se entienden`() {
        telefono.crearGrupo(codigo = "ABCDE23456")
        tableta.crearGrupo(codigo = "ZZZZZ99999")
        try {
            conectado(telefono, tableta) {}
            fail("No debería haber podido hablar")
        } catch (e: java.io.IOException) {
            // Bien: el que responde no puede descifrar el saludo y corta.
        }
    }

    @Test
    fun `lo de antes del grupo se sella con el codigo del aparato`() {
        mensaje(telefono, "viejo1", "de antes")
        mensaje(tableta, "viejo2", "de antes también")
        emparejar()
        val tel = telefono.identidad.leer().yo.codigo
        val tab = tableta.identidad.leer().yo.codigo
        assertEquals(setOf("1·$tel"), senas(telefono))
        assertEquals(setOf("1·$tab"), senas(tableta))
        // Y con su código único, sacado de su id: el mismo en cualquier aparato.
        assertEquals(Codigos.de("m:viejo1"), telefono.leerMensajes().single().uid)
        sincronizar()
        assertEquals(setOf("1·$tel", "1·$tab"), senas(telefono))
        assertEquals(senas(telefono), senas(tableta))
    }

    @Test
    fun `la primera vuelta junta los dos chats sin preguntar nada`() {
        emparejar()
        mensaje(telefono, "t1", "hola desde el teléfono")
        mensaje(telefono, "t2", "otra")
        mensaje(tableta, "b1", "hola desde la tableta")
        assertEquals(0, sincronizar().fusionados)
        assertEquals(setOf("1a", "2a", "1b"), senas(telefono))
        assertEquals(senas(telefono), senas(tableta))
        // Y en el mismo orden: por hora de creación.
        assertEquals(
            telefono.leerMensajes().sortedBy { it.cuando }.map { it.id },
            tableta.leerMensajes().sortedBy { it.cuando }.map { it.id }
        )
    }

    @Test
    fun `sincronizar dos veces seguidas no mueve nada`() {
        emparejar()
        mensaje(telefono, "t1", "hola")
        mensaje(tableta, "b1", "adiós")
        sincronizar()
        conectado(telefono, tableta) { s ->
            val prep = s.preparar(Disco.GENERAL)
            assertTrue("sin pasos: ${prep.pasos}", prep.pasos.isEmpty())
            s.aplicar(prep, Sesion.Hecho())
            assertTrue(s.prepararArchivos(prep).pasos.isEmpty())
        }
    }

    @Test
    fun `lo que cambio en un solo lado pasa solo`() {
        emparejar()
        mensaje(telefono, "t1", "versión 1")
        sincronizar()
        editar(tableta, "t1") { it.copy(texto = "versión 2, desde la tableta") }
        assertEquals(0, sincronizar().fusionados)
        assertEquals("versión 2, desde la tableta", telefono.leerMensajes().single().texto)
    }

    @Test
    fun `una nota cambiada en los dos se junta parrafo por parrafo sin preguntar`() {
        emparejar()
        mensaje(telefono, "t1", "Introducción\n\nMétodo\n\nResultados")
        sincronizar()
        editar(telefono, "t1") { it.copy(texto = "Introducción corregida\n\nMétodo\n\nResultados") }
        editar(tableta, "t1") { it.copy(texto = "Introducción\n\nMétodo\n\nResultados\n\nConclusiones") }
        assertEquals(1, sincronizar().fusionados)
        val esperado = "Introducción corregida\n\nMétodo\n\nResultados\n\nConclusiones"
        assertEquals(esperado, telefono.leerMensajes().single().texto)
        assertEquals(esperado, tableta.leerMensajes().single().texto)
        // Y la vuelta siguiente ya no tiene nada que juntar.
        conectado(telefono, tableta) { s -> assertTrue(s.preparar(Disco.GENERAL).pasos.isEmpty()) }
    }

    @Test
    fun `lo borrado en un lado y sin tocar en el otro se borra en los dos y no resucita`() {
        emparejar()
        val m = mensaje(telefono, "t1", "para borrar")
        mensaje(telefono, "t2", "se queda")
        sincronizar()
        borrar(telefono, m.id)
        sincronizar()
        assertEquals(setOf("2a"), senas(tableta))
        assertEquals(setOf("2a"), senas(telefono))
        sincronizar()
        assertEquals(setOf("2a"), senas(telefono))
    }

    @Test
    fun `una marca de borrado de antes de los codigos sigue borrando en el otro`() {
        emparejar()
        mensaje(telefono, "t1", "viejo")
        mensaje(telefono, "t2", "queda")
        sincronizar()
        // Se borró con una versión anterior de PixPin: la marca solo sabe la seña.
        val antes = telefono.leerMensajes()
        File(telefono.filesDir, "guardados.jsonl").writeText(antes.filter { it.id != "t1" }.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
        telefono.anotarBorrados(listOf(Marca(Disco.GENERAL, "1a", reloj++)))
        sincronizar()
        assertEquals(setOf("2a"), senas(tableta))
        sincronizar()
        assertEquals(setOf("2a"), senas(telefono))
    }

    @Test
    fun `lo borrado en un lado y cambiado en el otro se queda en los dos`() {
        emparejar()
        val m = mensaje(telefono, "t1", "borrador")
        sincronizar()
        borrar(telefono, m.id)
        editar(tableta, "t1") { it.copy(texto = "borrador mejorado en la tableta") }
        sincronizar()
        assertEquals("borrador mejorado en la tableta", telefono.leerMensajes().single().texto)
        assertEquals("borrador mejorado en la tableta", tableta.leerMensajes().single().texto)
        sincronizar()
        assertEquals(1, telefono.leerMensajes().size)
    }

    @Test
    fun `los numeros nuevos de cada aparato no chocan aunque se creen a la vez`() {
        emparejar()
        mensaje(telefono, "t1", "uno")
        sincronizar()
        // Los dos crean «el siguiente» sin saber del otro: el 2a y el 2b.
        mensaje(telefono, "t2", "segundo del teléfono")
        mensaje(tableta, "b2", "segundo de la tableta")
        sincronizar()
        assertEquals(setOf("1a", "2a", "2b"), senas(telefono))
        assertEquals(senas(telefono), senas(tableta))
    }

    @Test
    fun `los adjuntos viajan y llegan con la ruta del otro aparato`() {
        emparejar()
        val pdf = File(telefono.filesDir, "guardados/123_plano.pdf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3_000_000) { (it % 251).toByte() }) }
        mensaje(telefono, "t1", clase = Clase.ARCHIVO, ruta = pdf.absolutePath)
        sincronizar()
        val llegado = tableta.leerMensajes().single()
        val ruta = File(llegado.ruta!!)
        assertTrue("con la carpeta de la tableta: $ruta", ruta.path.startsWith(tableta.filesDir.absolutePath))
        assertTrue(ruta.readBytes().contentEquals(pdf.readBytes()))
    }

    @Test
    fun `lo anotado sobre un PDF, un Word y un libro del chat viaja con el codigo del mensaje`() {
        emparejar()
        val carpeta = File(telefono.filesDir, "guardados").apply { mkdirs() }
        val pdf = File(carpeta, "1_plano.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val docx = File(carpeta, "2_informe.docx").apply { writeBytes(byteArrayOf(4, 5)) }
        val epub = File(carpeta, "3_libro.epub").apply { writeBytes(byteArrayOf(6)) }
        mensaje(telefono, "p", clase = Clase.ARCHIVO, ruta = pdf.absolutePath)
        mensaje(telefono, "d", clase = Clase.ARCHIVO, ruta = docx.absolutePath)
        mensaje(telefono, "e", clase = Clase.ARCHIVO, ruta = epub.absolutePath)
        val a = AnotacionesDelAdjunto
        val uPdf = a.uidDe(telefono.filesDir, pdf.absolutePath)!!
        val uDocx = a.uidDe(telefono.filesDir, docx.absolutePath)!!
        val uEpub = a.uidDe(telefono.filesDir, epub.absolutePath)!!
        lienzo(telefono, a.dePagina(uPdf, 2), fig("trazo-pdf"))
        a.escribir(a.marcas(telefono.filesDir, a.delPdf(uPdf)), "m1:0.5:2.25:⭐")
        a.escribir(a.espacios(telefono.filesDir, a.delPdf(uPdf)), "3")
        lienzo(telefono, a.delDocumento(uDocx, docx), fig("trazo-docx"))
        a.escribir(a.maqueta(telefono.filesDir, a.delDocumento(uDocx, docx)), "420,280,280,100,1,0")
        lienzo(telefono, a.delDocumento(uEpub, epub), fig("trazo-epub"))
        a.escribir(a.marcas(telefono.filesDir, a.delDocumento(uEpub, epub)), "0.3:📌")

        sincronizar()

        // En la tableta, el mismo mensaje con otra ruta da el mismo código: el lector lo encuentra.
        fun rutaAlla(id: String) = tableta.leerMensajes().single { it.id == id }.ruta!!
        assertEquals(uPdf, a.uidDe(tableta.filesDir, rutaAlla("p")))
        assertEquals(uDocx, a.uidDe(tableta.filesDir, rutaAlla("d")))
        assertEquals(uEpub, a.uidDe(tableta.filesDir, rutaAlla("e")))
        assertEquals(listOf("trazo-pdf"), figuras(tableta, a.dePagina(uPdf, 2)))
        assertEquals("m1:0.5:2.25:⭐", a.leer(a.marcas(tableta.filesDir, a.delPdf(uPdf))))
        assertEquals("3", a.leer(a.espacios(tableta.filesDir, a.delPdf(uPdf))))
        assertEquals(listOf("trazo-docx"), figuras(tableta, a.delDocumento(uDocx, docx)))
        assertEquals(AnotacionesDelAdjunto.Maqueta(420, 280, 280, 100, 1, 0),
            AnotacionesDelAdjunto.Maqueta.deTexto(a.leer(a.maqueta(tableta.filesDir, a.delDocumento(uDocx, docx)))))
        assertEquals(listOf("trazo-epub"), figuras(tableta, a.delDocumento(uEpub, epub)))
        assertEquals("0.3:📌", a.leer(a.marcas(tableta.filesDir, a.delDocumento(uEpub, epub))))

        // Y de vuelta: lo que se cambia en la tableta llega al teléfono.
        reloj += 10_000
        a.escribir(a.espacios(tableta.filesDir, a.delPdf(uPdf)), "1")
        File(tableta.filesDir, "pins/draw/${a.delPdf(uPdf)}.espacios").setLastModified(System.currentTimeMillis() + 60_000)
        lienzo(tableta, a.dePagina(uPdf, 2), fig("trazo-pdf"), fig("otro"))
        sincronizar()
        assertEquals("1", a.leer(a.espacios(telefono.filesDir, a.delPdf(uPdf))))
        assertEquals(setOf("trazo-pdf", "otro"), figuras(telefono, a.dePagina(uPdf, 2)).toSet())
    }

    @Test
    fun `los marcadores de un lienzo, el verde de la voz y los del PDF de un proyecto tambien viajan`() {
        emparejar()
        val a = AnotacionesDelAdjunto
        // Un lienzo del chat con sus marcadores al lado.
        dibujo(telefono, "d1", "planta")
        mensaje(telefono, "l", clase = Clase.DIBUJO, referencia = "d1")
        a.escribir(a.delLienzo(telefono.filesDir, "d1"), "k:10.0:20.0:🏠")
        // Un Word con el verde de la voz y el punto de lectura.
        val docx = File(telefono.filesDir, "guardados/4_acta.docx").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        mensaje(telefono, "w", clase = Clase.ARCHIVO, ruta = docx.absolutePath)
        val uW = a.uidDe(telefono.filesDir, docx.absolutePath)!!
        a.escribir(a.voz(telefono.filesDir, a.delDocumento(uW, docx)), "12:0.4")
        a.escribir(a.sitio(telefono.filesDir, a.delDocumento(uW, docx)), "0.37")
        sincronizar()
        assertEquals("k:10.0:20.0:🏠", a.leer(a.delLienzo(tableta.filesDir, "d1")))
        assertEquals("12:0.4", a.leer(a.voz(tableta.filesDir, a.delDocumento(uW, docx))))
        assertEquals("0.37", a.leer(a.sitio(tableta.filesDir, a.delDocumento(uW, docx))))

        // El PDF de un proyecto: sus marcadores y espacios con el código del proyecto.
        proyecto(telefono, Proyecto("pr-9", "Tesis", hojas = listOf(Hoja("h1", "Pág. 1", pagina = 0)), tocado = 5))
        mensaje(telefono, "n", "nota", proyecto = "pr-9")
        val uP = Codigos.unico(telefono.leerProyectos().single())
        a.escribir(a.marcas(telefono.filesDir, a.delPdf(uP)), "m:0.1:0.5:⭐")
        a.escribir(a.espacios(telefono.filesDir, a.delPdf(uP)), "2")
        sincronizar(listOf("pr-9"))
        assertEquals(uP, Codigos.unico(tableta.leerProyectos().single()))
        assertEquals("m:0.1:0.5:⭐", a.leer(a.marcas(tableta.filesDir, a.delPdf(uP))))
        assertEquals("2", a.leer(a.espacios(tableta.filesDir, a.delPdf(uP))))
    }

    @Test
    fun `borrar el mensaje se lleva lo anotado en los dos aparatos`() {
        emparejar()
        val a = AnotacionesDelAdjunto
        val pdf = File(telefono.filesDir, "guardados/5_libro.pdf").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        mensaje(telefono, "p", clase = Clase.ARCHIVO, ruta = pdf.absolutePath)
        val u = a.uidDe(telefono.filesDir, pdf.absolutePath)!!
        lienzo(telefono, a.dePagina(u, 0), fig("t"))
        a.escribir(a.marcas(telefono.filesDir, a.delPdf(u)), "x:0:0:⭐")
        sincronizar()
        assertEquals(2, a.todoDe(tableta.filesDir, u).size)
        borrar(telefono, "p")
        a.todoDe(telefono.filesDir, u).forEach { it.delete() } // lo que hace el chat al borrar
        sincronizar()
        assertTrue(tableta.leerMensajes().none { it.id == "p" })
        assertEquals(emptyList<File>(), a.todoDe(tableta.filesDir, u))
    }

    @Test
    fun `lo anotado de antes se copia una vez al nombre del mensaje y no se pisa`() {
        val a = AnotacionesDelAdjunto
        val dir = telefono.filesDir
        val viejo = dibujo(telefono, "pdf-abc-p0", "de antes")
        val nuevo = File(dir, "pins/draw/${a.dePagina("ABCDE23456", 0)}.excalidraw.gz")
        assertTrue(a.copiarSiFalta(viejo, nuevo))
        assertTrue(viejo.exists())
        assertTrue(nuevo.readBytes().contentEquals(viejo.readBytes()))
        dibujo(telefono, "pdf-abc-p0", "cambiado despues")
        assertFalse("lo nuevo ya existe: no se pisa", a.copiarSiFalta(viejo, nuevo))
        val marcas = a.marcas(dir, a.delPdf("ABCDE23456"))
        assertTrue(a.ponerSiFalta(marcas, "x:0:0:⭐"))
        assertFalse(a.ponerSiFalta(marcas, "otra"))
        assertFalse(a.ponerSiFalta(a.espacios(dir, "anot-ABCDE23456"), null))
        assertEquals("x:0:0:⭐", a.leer(marcas))
        // Solo lo de un mensaje, con su código entero; ni temporales ni otros dibujos.
        File(dir, "pins/draw/anot-ABCDE23456.marcas.tmp").writeText("?")
        File(dir, "pins/draw/anot-CORTO.marcas").writeText("?")
        assertEquals(
            mapOf("ABCDE23456" to setOf("pins/draw/anot-ABCDE23456-p0.excalidraw.gz", "pins/draw/anot-ABCDE23456.marcas")),
            a.porUid(dir).mapValues { it.value.toSet() }
        )
        assertNull(a.uidDe(dir, File(dir, "guardados/no-esta.pdf").absolutePath))
        assertNull(AnotacionesDelAdjunto.Maqueta.deTexto("0,1,2,3,4,5"))
        assertNull(AnotacionesDelAdjunto.Maqueta.deTexto("420,x,2,3,4,5"))
    }

    @Test
    fun `un lienzo viaja con su foto y sin rutas del telefono dentro`() {
        emparejar()
        File(telefono.filesDir, "pins/draw/files/foto1").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        dibujo(telefono, "d1", "planta", foto = "foto1")
        mensaje(telefono, "t1", clase = Clase.DIBUJO, referencia = "d1")
        sincronizar()
        val texto = textoDelDibujo(tableta, "d1")
        assertTrue(texto.contains("planta"))
        assertTrue("la foto apunta a la tableta: $texto", texto.contains(File(tableta.filesDir, "pins/draw/files/foto1").absolutePath))
        assertFalse(texto.contains(telefono.filesDir.absolutePath))
        assertTrue(File(tableta.filesDir, "pins/draw/files/foto1").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `un lienzo dibujado en los dos se junta figura por figura - A+B y A+C dan A+B+C`() {
        emparejar()
        lienzo(telefono, "d1", fig("A"))
        mensaje(telefono, "t1", "Planta baja", clase = Clase.DIBUJO, referencia = "d1")
        sincronizar()
        lienzo(telefono, "d1", fig("A"), fig("B"))
        lienzo(tableta, "d1", fig("A", color = "#f00", v = 2, updated = 50), fig("C"))
        val hecho = sincronizar()
        assertEquals(1, hecho.fusionados)
        assertEquals(listOf("A", "B", "C"), figuras(telefono, "d1"))
        assertEquals(listOf("A", "B", "C"), figuras(tableta, "d1"))
        assertTrue("el color de la tableta se queda", textoDelDibujo(telefono, "d1").contains("#f00"))
        assertEquals(textoDelDibujo(telefono, "d1").let(Canonico::de), textoDelDibujo(tableta, "d1").let(Canonico::de))
        // Nada más que hacer en la vuelta siguiente.
        assertEquals(0, sincronizar().fusionados)
        conectado(telefono, tableta) { s -> val prep = s.preparar(Disco.GENERAL); s.aplicar(prep, Sesion.Hecho()); assertTrue(s.prepararArchivos(prep).pasos.isEmpty()) }
    }

    @Test
    fun `una figura borrada en un lado y movida en el otro vuelve`() {
        emparejar()
        lienzo(telefono, "d1", fig("A"), fig("B"))
        mensaje(telefono, "t1", "Planta", clase = Clase.DIBUJO, referencia = "d1")
        sincronizar()
        lienzo(telefono, "d1", fig("A"))
        lienzo(tableta, "d1", fig("A"), fig("B", x = 300, v = 2, updated = 90))
        val hecho = sincronizar()
        assertEquals(listOf("A", "B"), figuras(telefono, "d1"))
        assertEquals(1, hecho.rescatados)
    }

    @Test
    fun `solo viajan los cambios de un lienzo grande`() {
        emparejar()
        val muchas = (1..400).map { fig("f$it", x = it) }
        lienzo(telefono, "d1", *muchas.toTypedArray())
        mensaje(telefono, "t1", "Grande", clase = Clase.DIBUJO, referencia = "d1")
        var primera = 0L
        sincronizar(bytes = { primera = it })
        // Un cambio pequeño en la tableta.
        lienzo(tableta, "d1", *(muchas.map { if (it.contains("\"f9\"")) fig("f9", x = 999, v = 2, updated = 5) else it }).toTypedArray())
        var segunda = 0L
        val hecho = sincronizar(bytes = { segunda = it })
        assertTrue(textoDelDibujo(telefono, "d1").contains("999"))
        assertTrue("la segunda vuelta mueve $segunda bytes y la primera $primera", segunda * 3 < primera)
        assertTrue(hecho.ahorrados > 0)
    }

    /**
     * El caso del usuario (21-sep-2026): vació un aparato creyendo que el otro lo volvería a
     * llenar. Juntando, lo borrado —que es lo más reciente— gana; con «lo mío manda», el que
     * manda no pierde nada y al otro le vuelve el proyecto entero, ya sin su marca de borrado.
     */
    @Test
    fun `con lo mio manda, lo que el otro borro le vuelve entero y aqui no se borra nada`() {
        emparejar()
        dibujo(telefono, "d1", "hoja uno")
        proyecto(telefono, Proyecto("pr-1", "Tesis", hojas = listOf(Hoja("h1", "Planta", dibujo = "d1")), tocado = 5))
        mensaje(telefono, "b1", "Planta", clase = Clase.DIBUJO, proyecto = "pr-1", referencia = "d1")
        mensaje(telefono, "b2", "una nota", proyecto = "pr-1")
        sincronizar(listOf("pr-1"))
        assertEquals("Tesis", tableta.leerProyectos().single().nombre)

        // La tableta lo borra todo, más tarde que cualquier cambio del teléfono.
        tableta.borrarChat("pr-1", "Antes de borrarlo", cuando = reloj++)
        assertTrue(tableta.leerProyectos().isEmpty())
        assertNotNull(tableta.lapidaDe("pr-1"))
        val antes = senas(telefono, "pr-1")

        sincronizar(listOf("pr-1"), loMioManda = true)

        assertEquals("Tesis", telefono.leerProyectos().single().nombre)
        assertEquals(antes, senas(telefono, "pr-1"))
        assertEquals("Tesis", tableta.leerProyectos().single().nombre)
        assertEquals(listOf("h1"), tableta.leerProyectos().single().hojas.map { it.id })
        assertEquals(antes, senas(tableta, "pr-1"))
        assertTrue(textoDelDibujo(tableta, "d1").contains("hoja uno"))
        assertNull(tableta.lapidaDe("pr-1"))
    }

    @Test
    fun `un proyecto nuevo en la tableta llega entero, con sus hojas juntadas despues`() {
        emparejar()
        dibujo(tableta, "d1", "hoja uno")
        proyecto(tableta, Proyecto("pr-1", "Reforma", hojas = listOf(Hoja("h1", "Planta", dibujo = "d1")), tocado = 5))
        tableta.let { mensaje(it, "b1", "Planta", clase = Clase.DIBUJO, proyecto = "pr-1", referencia = "d1") }
        sincronizar(listOf("pr-1"))
        assertEquals("Reforma", telefono.leerProyectos().single().nombre)
        assertTrue(textoDelDibujo(telefono, "d1").contains("hoja uno"))
        assertEquals(setOf("1b"), senas(telefono, "pr-1"))

        // Cada uno añade una hoja: quedan las dos, sin preguntar.
        val p = telefono.leerProyectos().single()
        proyecto(telefono, p.copy(hojas = p.hojas + Hoja("h2", "Alzado"), tocado = 6))
        val q = tableta.leerProyectos().single()
        proyecto(tableta, q.copy(hojas = q.hojas + Hoja("h3", "Detalle"), tocado = 7))
        sincronizar(listOf("pr-1"))
        assertEquals(listOf("h1", "h2", "h3"), telefono.leerProyectos().single().hojas.map { it.id })
        assertEquals(listOf("h1", "h2", "h3").toSet(), tableta.leerProyectos().single().hojas.map { it.id }.toSet())

        // Y una hoja quitada a mano en un lado se quita en los dos.
        val r = tableta.leerProyectos().single()
        proyecto(tableta, Proyectos.sinHoja(r, "h2", 8))
        sincronizar(listOf("pr-1"))
        assertEquals(listOf("h1", "h3"), telefono.leerProyectos().single().hojas.map { it.id })
        // Pero una que simplemente falta —sin marca de quitada— vuelve.
        val t = telefono.leerProyectos().single()
        proyecto(telefono, t.copy(hojas = t.hojas.filter { it.id != "h3" }, tocado = 9))
        sincronizar(listOf("pr-1"))
        assertEquals(setOf("h1", "h3"), telefono.leerProyectos().single().hojas.map { it.id }.toSet())
    }

    @Test
    fun `un chat de cientos de mensajes pasa en tandas`() {
        emparejar()
        val largo = "x".repeat(4_000)
        repeat(450) { mensaje(telefono, "t$it", "nota $it $largo") }
        sincronizar()
        assertEquals(450, tableta.leerMensajes().size)
        assertEquals(senas(telefono), senas(tableta))
    }

    @Test
    fun `un aparato que ya esta sincronizando dice que esta ocupado`() {
        emparejar()
        assertTrue(Protocolo.ocupar(tableta))
        try {
            conectado(telefono, tableta) { s ->
                try { s.catalogo(); fail("debería decir ocupado") } catch (e: java.io.IOException) {
                    assertEquals(Protocolo.OCUPADO, e.message)
                }
            }
        } finally {
            Protocolo.soltar(tableta)
        }
        // Y el que dirigía queda libre para la siguiente.
        assertTrue(Protocolo.ocupar(telefono)); Protocolo.soltar(telefono)
    }

    @Test
    fun `lo tocado mientras se sincronizaba no hace que la vuelta siguiente traiga lo viejo`() {
        // Lo que vio el usuario: guardar algo justo al sincronizar, y que después «no pasara».
        // Antes se apuntaba como acordado lo de este aparato sin mirar el otro; la vuelta
        // siguiente creía que el otro se había movido y traía su versión vieja encima.
        emparejar()
        dibujo(telefono, "d1", "v1")
        mensaje(telefono, "t1", "Planta", clase = Clase.DIBUJO, referencia = "d1")
        sincronizar()
        dibujo(telefono, "d1", "v2")
        sincronizar(antesDeCerrar = { dibujo(telefono, "d1", "v3 guardado a última hora") })
        assertTrue(textoDelDibujo(tableta, "d1").contains("v2"))
        assertEquals("nada que juntar", 0, sincronizar().fusionados)
        assertTrue(textoDelDibujo(telefono, "d1").contains("v3"))
        assertTrue("la tableta recibe lo último", textoDelDibujo(tableta, "d1").contains("v3"))
    }

    @Test
    fun `cada chat dice cuando se toco por ultima vez`() {
        mensaje(telefono, "t1", "hola")
        proyecto(telefono, Proyecto("pr-1", "Obra", tocado = 5))
        val tocado = reloj
        mensaje(telefono, "t2", "en la obra", proyecto = "pr-1")
        val chats = telefono.chats().associateBy { it.id }
        assertEquals(tocado - 1, chats.getValue(Disco.GENERAL).tocado)
        assertEquals(tocado, chats.getValue("pr-1").tocado)
    }

    @Test
    fun `se recuerda donde estaba cada aparato`() {
        telefono.apuntarDireccion("id-tab", "192.168.1.20", 47474)
        telefono.apuntarDireccion("id-otro", "192.168.1.21", 5000)
        telefono.apuntarDireccion("id-tab", "192.168.1.30", 47474)
        assertEquals("192.168.1.30" to 47474, telefono.direcciones()["id-tab"])
        assertEquals(2, telefono.direcciones().size)
    }

    @Test
    fun `el PDF de un proyecto que estaba en la cache se mete dentro y viaja`() {
        // Lo que vio el usuario: el proyecto llegaba, pero sin su PDF debajo. Los proyectos
        // creados al compartir un PDF lo tenían en la caché, y lo de fuera de `files` no viaja.
        emparejar()
        val cache = File(raiz, "telefono/cache/compartido").apply { mkdirs() }
        val pdf = File(cache, "123-plano.pdf").apply { writeBytes("%PDF-1.4 plano".toByteArray()) }
        val limpio = File(telefono.filesDir, "proyectos/limpio-1.pdf").apply { parentFile!!.mkdirs(); writeBytes("%PDF-1.4 plano".toByteArray()) }
        proyecto(telefono, Proyecto("pr-1", "Plano", hojas = listOf(Hoja("h1", pagina = 0)), pdfOrigen = pdf.absolutePath, pdfLimpio = limpio.absolutePath, tocado = 3))
        sincronizar(listOf("pr-1"))
        val suyo = tableta.leerProyectos().single()
        assertTrue("dentro de la tableta: ${suyo.pdfOrigen}", suyo.pdfOrigen!!.startsWith(tableta.filesDir.absolutePath))
        assertTrue(File(suyo.pdfOrigen!!).readText().contains("plano"))
        assertTrue(telefono.leerProyectos().single().pdfOrigen!!.startsWith(telefono.filesDir.absolutePath))
    }

    // ------------------------------------------------------------ envío de una vez

    private fun enviar(cosas: List<Pair<Envio.Elemento, File>>, codigoDelEmisor: String, codigoDelReceptor: String,
                       acepta: Boolean = true): Pair<Envio.Final?, List<Pair<Envio.Elemento, File>>?> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var final: Envio.Final? = null
        val hilo = Thread {
            runCatching {
                server.accept().use { s -> final = Envio.Emisor(Aparato("id-tel", "Teléfono"), cosas).atender(s.getInputStream(), s.getOutputStream(), codigoDelEmisor) }
            }
            server.close()
        }
        hilo.start()
        val recibido = runCatching {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                val r = Envio.Receptor.conectar(s.getInputStream(), s.getOutputStream(), codigoDelReceptor, Aparato("id-otro", "Huawei"))
                assertEquals("Teléfono", r.oferta.de)
                if (acepta) r.aceptar(File(raiz, "recibidos")) else { r.rechazar(); null }
            }
        }.getOrNull()
        hilo.join(5000)
        return final to recibido
    }

    @Test
    fun `un envio de una vez llega entero con el codigo bueno`() {
        val grande = File(raiz, "plano.pdf").apply { writeBytes(ByteArray(2_500_000) { (it % 97).toByte() }) }
        val foto = File(raiz, "foto.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val cosas = listOf(
            Envio.Elemento(Envio.ARCHIVO, "plano.pdf", grande.length(), "application/pdf", "archivo:id-tel:plano.pdf") to grande,
            Envio.Elemento(Envio.ARCHIVO, "foto.jpg", foto.length(), "image/jpeg", "archivo:id-tel:foto.jpg") to foto
        )
        val codigo = "482913"
        val (final, recibido) = enviar(cosas, codigo, codigo)
        assertEquals(Envio.Final.ENVIADO, final)
        assertEquals(2, recibido!!.size)
        assertTrue(recibido[0].second.readBytes().contentEquals(grande.readBytes()))
        assertEquals("archivo:id-tel:foto.jpg", recibido[1].first.identidad)
    }

    @Test
    fun `con otro codigo el envio no llega`() {
        val f = File(raiz, "a.txt").apply { writeText("hola") }
        val (final, recibido) = enviar(listOf(Envio.Elemento(Envio.ARCHIVO, "a.txt", 4, null, "x") to f), "111111", "222222")
        assertNull(final)
        assertNull(recibido)
    }

    @Test
    fun `rechazar un envio no manda nada`() {
        val f = File(raiz, "a.txt").apply { writeText("hola") }
        val (final, _) = enviar(listOf(Envio.Elemento(Envio.ARCHIVO, "a.txt", 4, null, "x") to f), "111111", "111111", acepta = false)
        assertEquals(Envio.Final.RECHAZADO, final)
    }

    @Test
    fun `el qr del envio se lee`() {
        val t = Envio.textoDelQr("482913", "192.168.1.5", 40123)
        assertEquals(Envio.DelQr("482913", "192.168.1.5", 40123), Envio.leerQr(t))
        assertNull(Envio.leerQr("otra cosa"))
        assertEquals("482 913", Envio.legible("482913"))
        assertEquals("482913", Envio.limpiar("482 913"))
    }

    @Test
    fun `solo se escribe dentro de las carpetas de PixPin`() {
        assertFalse(telefono.permitida("../../etc/passwd"))
        assertFalse(telefono.permitida("guardados.jsonl"))
        assertFalse(telefono.permitida("proyectos/proyectos.json"))
        assertFalse(telefono.permitida("sincro/identidad.json"))
        assertTrue(telefono.permitida("guardados/1_foto.jpg"))
        assertTrue(telefono.permitida("pins/draw/d1.excalidraw.gz"))
    }

    @Test
    fun `el codigo se limpia al teclearlo`() {
        assertEquals("ABCDE23456", Grupo.limpiar("abcde-23456 "))
        assertTrue(Grupo.valido(Grupo.nuevoCodigo()))
        assertEquals("ABCDE-23456", Grupo.legible("ABCDE23456"))
        assertNotNull(Grupo.etiqueta(Grupo.clave("ABCDE23456")))
        assertEquals(Grupo.etiqueta(Grupo.clave("ABCDE23456")), Grupo.etiqueta(Grupo.clave("ABCDE23456")))
    }

    @Test
    fun `juntar listas conserva el orden y lo de cada lado`() {
        val base = listOf("a", "b", "c")
        val mio = listOf("a", "b", "y")
        val suyo = listOf("a", "x", "b", "c")
        // «c» se quitó en el mío; «x» se añadió en el suyo detrás de «a»; «y» en el mío.
        assertEquals(listOf("a", "x", "b", "y"), Fusion.ordenJunto(mio, suyo, base) { k -> (k in mio && k in suyo) || k !in base })
        assertNull(Mezcla.proyecto(null, null, null))
    }

    /** Sin maestro: el nombre cambiado en un lado pasa; en los dos, gana el proyecto tocado más tarde. */
    @Test
    fun `sin maestro el nombre cambiado en los dos lo gana el ultimo`() {
        val hoja = Hoja(id = "h1", nombre = "planta")
        val base = Proyecto(id = "p", nombre = "obra", hojas = listOf(hoja), tocado = 100)
        assertEquals("obra nueva", Mezcla.proyecto(base.copy(nombre = "obra vieja", tocado = 200), base.copy(nombre = "obra nueva", tocado = 300), base)?.nombre)
        assertEquals("obra vieja", Mezcla.proyecto(base.copy(nombre = "obra vieja", tocado = 400), base.copy(nombre = "obra nueva", tocado = 300), base)?.nombre)
        // Cambiado en uno solo, pasa aunque el otro sea más reciente.
        assertEquals("obra nueva", Mezcla.proyecto(base.copy(tocado = 900), base.copy(nombre = "obra nueva", tocado = 300), base)?.nombre)
    }

    @Test
    fun `las hojas nuevas de los dos se suman y una nota se junta por parrafos`() {
        val a = Hoja(id = "h1", nota = "uno\ndos")
        val base = Proyecto(id = "p", nombre = "obra", hojas = listOf(a), tocado = 100)
        val mio = base.copy(hojas = listOf(a.copy(nota = "UNO\ndos"), Hoja(id = "h2")), tocado = 200)
        val suyo = base.copy(hojas = listOf(a.copy(nota = "uno\ndos\ntres"), Hoja(id = "h3")), tocado = 300)
        val junto = Mezcla.proyecto(mio, suyo, base)!!
        assertEquals(listOf("h1", "h2", "h3"), junto.hojas.map { it.id })
        assertEquals("UNO\ndos\ntres", junto.hojas.first().nota)
    }

    @Test
    fun `una hoja quitada a mano en un lado y cambiada en el otro se queda`() {
        val h = Hoja(id = "h1", dibujo = "d1")
        val base = Proyecto(id = "p", nombre = "obra", hojas = listOf(h), tocado = 100)
        val mio = Proyectos.sinHoja(base, "h1", 200)
        assertNull(Mezcla.proyecto(mio, base, base)!!.hojas.firstOrNull())
        val junto = Mezcla.proyecto(mio, base.copy(tocado = 300), base, cambiadasAlli = setOf("h1"))!!
        assertEquals(listOf("h1"), junto.hojas.map { it.id })
        assertTrue("vuelve y pierde la marca", "h1" !in junto.quitadas)
    }

    /**
     * **Lo que le pasó al usuario el 15-sep-2026 con «Tesis».** Los dos aparatos compartían dos
     * lienzos; un envío dejó el proyecto del teléfono con un mismo lienzo repetido, y la tableta tenía
     * además uno suyo. Sincronizando con la tableta de maestro, se perdió uno de los compartidos en
     * los dos. Ahora no manda nadie: lo que falta sin marca de quitado vuelve, y antes de tocar nada
     * hay copia en los dos.
     */
    @Test
    fun `el caso de Tesis - sin maestro no se pierde ningun lienzo`() {
        emparejar()
        dibujo(tableta, "d1", "uno"); dibujo(tableta, "d2", "dos")
        proyecto(tableta, Proyecto("tesis", "Tesis", hojas = listOf(Hoja("h1", "Uno", dibujo = "d1"), Hoja("h2", "Dos", dibujo = "d2")), tocado = 5))
        sincronizar(listOf("tesis"))
        assertEquals(listOf("h1", "h2"), telefono.leerProyectos().single().hojas.map { it.id })

        reloj += 1_000
        // La tableta añade uno suyo.
        dibujo(tableta, "d3", "tres")
        val t = tableta.leerProyectos().single()
        proyecto(tableta, t.copy(hojas = t.hojas + Hoja("h3", "Tres", dibujo = "d3"), tocado = 6))
        // El teléfono queda roto como lo dejó el envío: el mismo lienzo tres veces, tocado después.
        val f = telefono.leerProyectos().single()
        proyecto(telefono, f.copy(hojas = listOf(f.hojas[1], f.hojas[1], f.hojas[1]), tocado = 9))
        // Nunca dos hojas con el mismo id.
        assertEquals(listOf("h2"), telefono.leerProyectos().single().hojas.map { it.id })

        sincronizar(listOf("tesis"))
        assertEquals(setOf("h1", "h2", "h3"), telefono.leerProyectos().single().hojas.map { it.id }.toSet())
        assertEquals(setOf("h1", "h2", "h3"), tableta.leerProyectos().single().hojas.map { it.id }.toSet())
        assertTrue(textoDelDibujo(telefono, "d3").contains("tres"))

        // Y en los dos quedó copia de cómo estaba antes de esa vuelta.
        val copiaTel = Copias(telefono).lista("tesis").first()
        assertEquals(1, copiaTel.hojas)
        assertTrue(copiaTel.motivo.contains("Tableta"))
        assertEquals(3, Copias(tableta).lista("tesis").first().hojas)
    }

    @Test
    fun `una copia vuelve a poner el indice, los lienzos y los mensajes, y se puede deshacer`() {
        dibujo(telefono, "d1", "original")
        proyecto(telefono, Proyecto("p", "Obra", hojas = listOf(Hoja("h1", "Uno", dibujo = "d1"), Hoja("h2", "Dos")), tocado = 1))
        mensaje(telefono, "m1", "Uno", clase = Clase.DIBUJO, proyecto = "p", referencia = "d1")
        val copias = Copias(telefono)
        assertNotNull(copias.hacer("p", "Antes de la prueba", 100))
        // Igual que la última: no se repite.
        assertNull(copias.hacer("p", "Otra vez", 101))

        // Algo lo pisa: se va una hoja, cambia el lienzo, se borra el mensaje, y se añade otra hoja.
        dibujo(telefono, "d1", "pisado")
        proyecto(telefono, Proyecto("p", "Obra", hojas = listOf(Hoja("h9", "Nueva")), tocado = 2))
        File(telefono.filesDir, "guardados.jsonl").writeText("")

        // Sin proyecto que lo señale, el lienzo sale entre los sueltos.
        assertEquals(listOf("d1"), copias.lienzosSueltos().map { it.dibujo })

        assertTrue(copias.restaurar(copias.lista("p").last(), 200))
        val p = telefono.leerProyectos().single()
        // Lo de entonces, y lo añadido después se queda detrás.
        assertEquals(listOf("h1", "h2", "h9"), p.hojas.map { it.id })
        assertTrue(textoDelDibujo(telefono, "d1").contains("original"))
        assertEquals(listOf("m1"), telefono.leerMensajes().map { it.id })
        assertTrue(copias.lienzosSueltos().isEmpty())

        // Restaurar dejó otra copia antes: se puede volver a lo pisado.
        val antesDeRestaurar = copias.lista("p").first { it.motivo.startsWith("Antes de volver") }
        assertTrue(copias.restaurar(antesDeRestaurar, 300))
        assertTrue(textoDelDibujo(telefono, "d1").contains("pisado"))
    }

    @Test
    fun `dos aparatos que repararon su chat por separado acaban con un solo mensaje`() {
        emparejar()
        proyecto(telefono, Proyecto("p", "Obra", tocado = 1))
        sincronizar(listOf("p"))
        val id = com.forge.pixpin.guardados.RegistroDelChat.idPara("p", "d1")
        mensaje(telefono, id, "Lienzo", clase = Clase.DIBUJO, proyecto = "p", referencia = "d1")
        mensaje(tableta, id, "Lienzo", clase = Clase.DIBUJO, proyecto = "p", referencia = "d1")
        sincronizar(listOf("p"))
        assertEquals(1, telefono.leerMensajes().count { it.id == id })
        assertEquals(1, tableta.leerMensajes().count { it.id == id })
        assertEquals(senas(telefono, "p"), senas(tableta, "p"))
        // Y la siguiente vuelta no lo resucita.
        sincronizar(listOf("p"))
        assertEquals(1, telefono.leerMensajes().count { it.id == id })
    }

    // ------------------------------------------- 4-oct-2026: nombres, lecciones y repetidos

    private fun adjunto(d: Disco, rel: String, contenido: String): String {
        val f = File(d.filesDir, rel).apply { parentFile!!.mkdirs(); writeText(contenido) }
        return f.absolutePath
    }

    private fun linea(d: Disco, m: Mensaje) =
        File(d.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")

    @Test
    fun `un audio renombrado en un lado llega con su nombre y sin duplicarse`() {
        emparejar()
        val ruta = adjunto(telefono, "guardados/voz_1.m4a", "audio")
        mensaje(telefono, "v1", clase = Clase.VOZ, ruta = ruta)
        editar(telefono, "v1") { it.copy(nombre = "Nota de voz 10:00") }
        sincronizar()
        editar(tableta, "v1") { it.copy(nombre = "Clase de estructuras") }
        sincronizar()
        for (d in listOf(telefono, tableta)) {
            val voz = d.leerMensajes().filter { it.id == "v1" }
            assertEquals(1, voz.size)
            assertEquals("Clase de estructuras", voz.single().nombre)
            assertEquals(listOf("voz_1.m4a"), File(d.filesDir, "guardados").listFiles()!!.filter { it.isFile }.map { it.name })
        }
    }

    @Test
    fun `renombrado distinto en los dos lados queda uno solo, el mismo`() {
        emparejar()
        mensaje(telefono, "a1", clase = Clase.ARCHIVO, ruta = adjunto(telefono, "guardados/informe.pdf", "%PDF"))
        sincronizar()
        editar(telefono, "a1") { it.copy(nombre = "Memoria.pdf") }
        reloj += 10
        editar(tableta, "a1") { it.copy(nombre = "Informe final.pdf") }
        sincronizar()
        val t = telefono.leerMensajes().filter { it.id == "a1" }
        val b = tableta.leerMensajes().filter { it.id == "a1" }
        assertEquals(1, t.size); assertEquals(1, b.size)
        assertEquals(t.single().nombre, b.single().nombre)
    }

    @Test
    fun `una leccion viaja con su foto colgada de ella`() {
        emparejar()
        val l = adjunto(telefono, "guardados/lecciones/abc.leccion", "{\"id\":\"abc\",\"creada\":1,\"titulo\":\"Medir dos veces\",\"adjuntos\":[\"f1\"]}")
        val f = adjunto(telefono, "guardados/leccion_1.jpg", "jpg")
        linea(telefono, Mensaje(id = "lec-abc", cuando = reloj++, clase = Clase.ARCHIVO, ruta = l, nombre = "💡 Medir dos veces"))
        linea(telefono, Mensaje(id = "f1", cuando = reloj++, clase = Clase.IMAGEN, ruta = f, nombre = "Foto de la lección", respondeA = "lec-abc"))
        sincronizar()
        val llegados = tableta.leerMensajes().associateBy { it.id }
        assertEquals(setOf("lec-abc", "f1"), llegados.keys)
        assertEquals("lec-abc", llegados.getValue("f1").respondeA)
        assertTrue(File(tableta.filesDir, "guardados/lecciones/abc.leccion").readText().contains("Medir dos veces"))
        assertEquals("jpg", File(tableta.filesDir, "guardados/leccion_1.jpg").readText())
        // Y otra vuelta no trae nada nuevo ni repite nada.
        sincronizar()
        assertEquals(2, tableta.leerMensajes().size)
        assertEquals(2, telefono.leerMensajes().size)
    }

    @Test
    fun `un mensaje repetido en el archivo del chat no viaja dos veces y se queda uno`() {
        emparejar()
        val l = adjunto(telefono, "guardados/lecciones/x.leccion", "{}")
        val m = Mensaje(id = "lec-x", cuando = reloj++, clase = Clase.ARCHIVO, ruta = l, nombre = "💡 x")
        linea(telefono, m); linea(telefono, m)
        sincronizar()
        assertEquals(1, tableta.leerMensajes().count { it.id == "lec-x" })
        assertEquals(1, telefono.leerMensajes().count { it.id == "lec-x" })
    }
}
