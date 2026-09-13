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

    /** Sincroniza los chats dados eligiendo con [elegir] en cada pregunta (por omisión, lo marcado). */
    private fun sincronizar(
        chats: List<String> = listOf(Disco.GENERAL),
        antesDeCerrar: () -> Unit = {},
        elegir: (Sesion.Pregunta) -> Boolean = { it.porOmision }
    ): List<Sesion.Pregunta> {
        val preguntadas = ArrayList<Sesion.Pregunta>()
        conectado(telefono, tableta) { s ->
            for (chat in chats) {
                val hecho = Sesion.Hecho()
                val prep = s.preparar(chat)
                preguntadas += prep.preguntas
                s.aplicar(prep, prep.preguntas.associate { it.clave to elegir(it) }, hecho)
                val arch = s.prepararArchivos(prep)
                preguntadas += arch.preguntas
                s.aplicarArchivos(arch, arch.preguntas.associate { it.clave to elegir(it) }, hecho)
                antesDeCerrar()
                s.cerrar(prep)
            }
        }
        return preguntadas
    }

    private fun senas(d: Disco, chat: String = Disco.GENERAL) = d.mensajesPorSena(chat).keys.toSortedSet()

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
    fun `lo de antes del grupo se sella con la letra del aparato`() {
        mensaje(telefono, "viejo1", "de antes")
        mensaje(tableta, "viejo2", "de antes también")
        emparejar()
        assertEquals(setOf("1a"), senas(telefono))
        assertEquals(setOf("1b"), senas(tableta))
    }

    @Test
    fun `la primera vuelta junta los dos chats sin preguntar nada`() {
        emparejar()
        mensaje(telefono, "t1", "hola desde el teléfono")
        mensaje(telefono, "t2", "otra")
        mensaje(tableta, "b1", "hola desde la tableta")
        val preguntas = sincronizar()
        assertTrue(preguntas.isEmpty())
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
            s.aplicar(prep, emptyMap(), Sesion.Hecho())
            assertTrue(s.prepararArchivos(prep).pasos.isEmpty())
        }
    }

    @Test
    fun `lo que cambio en un solo lado pasa solo`() {
        emparejar()
        mensaje(telefono, "t1", "versión 1")
        sincronizar()
        editar(tableta, "t1") { it.copy(texto = "versión 2, desde la tableta") }
        assertTrue(sincronizar().isEmpty())
        assertEquals("versión 2, desde la tableta", telefono.leerMensajes().single().texto)
    }

    @Test
    fun `lo que cambio en los dos se pregunta y lo elegido queda en los dos`() {
        emparejar()
        mensaje(telefono, "t1", "versión 1")
        sincronizar()
        editar(telefono, "t1") { it.copy(texto = "del teléfono") }
        editar(tableta, "t1") { it.copy(texto = "de la tableta") }
        val preguntas = sincronizar { false }  // me quedo con lo de la tableta
        assertEquals(1, preguntas.size)
        assertEquals(Diferencia.Choque.LOS_DOS_CAMBIARON, preguntas.single().porque)
        assertEquals("de la tableta", preguntas.single().suyo!!.texto)
        assertEquals("de la tableta", telefono.leerMensajes().single().texto)
        assertEquals("de la tableta", tableta.leerMensajes().single().texto)
        // Y la vuelta siguiente ya no pregunta.
        assertTrue(sincronizar().isEmpty())
    }

    @Test
    fun `lo borrado en un lado se pregunta, gana el borrado, y no resucita`() {
        emparejar()
        val m = mensaje(telefono, "t1", "para borrar")
        mensaje(telefono, "t2", "se queda")
        sincronizar()
        borrar(telefono, m.id)
        val preguntas = sincronizar()
        assertEquals(Diferencia.Choque.BORRADO_CONTRA_INTACTO, preguntas.single().porque)
        assertTrue(preguntas.single().porOmision)
        assertEquals(setOf("2a"), senas(tableta))
        assertEquals(setOf("2a"), senas(telefono))
        assertTrue(sincronizar().isEmpty())
        assertEquals(setOf("2a"), senas(telefono))
    }

    @Test
    fun `los numeros nuevos de cada aparato no chocan aunque se creen a la vez`() {
        emparejar()
        mensaje(telefono, "t1", "uno")
        sincronizar()
        // Los dos crean «el siguiente» sin saber del otro: el 2a y el 2b.
        mensaje(telefono, "t2", "segundo del teléfono")
        mensaje(tableta, "b2", "segundo de la tableta")
        assertTrue(sincronizar().isEmpty())
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
    fun `un lienzo dibujado en los dos se pregunta por su mensaje y gana lo elegido`() {
        emparejar()
        dibujo(telefono, "d1", "v1")
        mensaje(telefono, "t1", "Planta baja", clase = Clase.DIBUJO, referencia = "d1")
        sincronizar()
        dibujo(telefono, "d1", "trazo del teléfono")
        dibujo(tableta, "d1", "trazo de la tableta")
        val preguntas = sincronizar { false }
        val p = preguntas.single()
        assertTrue("se nombra por su mensaje: ${p.titulo}", p.titulo.contains("1a"))
        assertTrue(textoDelDibujo(telefono, "d1").contains("trazo de la tableta"))
        assertTrue(sincronizar().isEmpty())
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
        assertTrue(sincronizar(listOf("pr-1")).isEmpty())
        assertEquals(listOf("h1", "h2", "h3"), telefono.leerProyectos().single().hojas.map { it.id })
        assertEquals(listOf("h1", "h2", "h3").toSet(), tableta.leerProyectos().single().hojas.map { it.id }.toSet())

        // Y una hoja quitada en un lado se quita en los dos.
        val r = tableta.leerProyectos().single()
        proyecto(tableta, r.copy(hojas = r.hojas.filter { it.id != "h2" }, tocado = 8))
        sincronizar(listOf("pr-1"))
        assertEquals(listOf("h1", "h3"), telefono.leerProyectos().single().hojas.map { it.id })
    }

    @Test
    fun `un chat de cientos de mensajes pasa en tandas`() {
        emparejar()
        val largo = "x".repeat(4_000)
        repeat(450) { mensaje(telefono, "t$it", "nota $it $largo") }
        assertTrue(sincronizar().isEmpty())
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
        val preguntas = sincronizar()
        assertTrue("no hay nada que preguntar: $preguntas", preguntas.isEmpty())
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
    fun `mezclar listas conserva el orden y lo de cada lado`() {
        val base = listOf("a", "b", "c")
        assertEquals(listOf("a", "x", "b", "y"), Mezcla.lista(listOf("a", "b", "y"), listOf("a", "x", "b", "c"), base, { it }, true).let {
            // «c» se quitó en el mío; «x» se añadió en el suyo detrás de «a»; «y» en el mío.
            it
        })
        assertEquals(listOf("a", "b"), Mezcla.lista(listOf("a"), listOf("b"), null, { it }, true).sorted())
        assertNull(Mezcla.proyecto(null, null, null))
    }
}
