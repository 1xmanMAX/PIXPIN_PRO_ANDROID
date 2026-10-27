package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * **El diario de cambios y lo que hace fiable la vuelta** (27-oct-2026), con dos aparatos de
 * verdad por un socket, como `SincronizarDeVerdadTest`: que la segunda vuelta no mire nada, que un
 * cambio solo haga mirar su chat, que sin diario (o con él roto) se haga lo de siempre y no se
 * pierda nada, que lo cortado se reanude, que sin sitio no se descuadre la conversación, que lo
 * repetido se corte, que lo que va primero (tareas, compartidos) llegue aunque se corte, y que con
 * un PixPin de antes todo siga igual.
 */
class DiarioDeCambiosTest {

    private lateinit var raiz: File
    private lateinit var telefono: Disco
    private lateinit var tableta: Disco
    private var reloj = 1_000_000L

    @Before
    fun montar() {
        Diario.activo = true
        raiz = Files.createTempDirectory("diario").toFile()
        telefono = Disco(File(raiz, "telefono/files").apply { mkdirs() })
        tableta = Disco(File(raiz, "tableta/data/files").apply { mkdirs() })
        telefono.identidad.guardar(Identidad(Aparato("id-tel", "Teléfono")))
        tableta.identidad.guardar(Identidad(Aparato("id-tab", "Tableta")))
    }

    @After
    fun desmontar() {
        Diario.activo = true
        raiz.deleteRecursively()
    }

    // ------------------------------------------------------------ utilidades

    private fun nuevo(d: Disco, id: String, texto: String = "", clase: Clase = Clase.NOTA, proyecto: String? = null,
                      ruta: String? = null, referencia: String? = null, miniapp: String? = null): Mensaje {
        val suyos = d.leerMensajes().filter { it.proyecto == proyecto }
        return Mensaje(id = id, cuando = reloj++, clase = clase, texto = texto, proyecto = proyecto, ruta = ruta,
            referencia = referencia, miniapp = miniapp, numero = (suyos.maxOfOrNull { it.numero } ?: 0) + 1,
            letra = d.identidad.leer().yo.letra)
    }

    /** Escribe el chat **como la aplicación** (`MensajesStore`): el archivo y su apunte en el diario. */
    private fun comoLaApp(d: Disco, cambio: (List<Mensaje>) -> List<Mensaje>) {
        val antes = d.leerMensajes()
        val despues = cambio(antes)
        File(d.filesDir, "guardados.jsonl").writeText(despues.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
        d.diario.mensajes(antes, despues)
    }

    /** Escribe el chat **a escondidas** (otro proceso, una versión de antes): sin apuntarlo. */
    private fun aEscondidas(d: Disco, cambio: (List<Mensaje>) -> List<Mensaje>) {
        val despues = cambio(d.leerMensajes())
        File(d.filesDir, "guardados.jsonl").writeText(despues.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
    }

    private fun dibujo(d: Disco, id: String, texto: String): File {
        val f = File(d.filesDir, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }
        f.outputStream().use { o -> GZIPOutputStream(o).use { it.write("""{"elements":[{"id":"e1","text":"$texto"}],"files":{}}""".toByteArray()) } }
        return f
    }

    private fun binario(d: Disco, rel: String, bytes: Int, semilla: Int = 7): File {
        val f = File(d.filesDir, rel).apply { parentFile!!.mkdirs() }
        val r = java.util.Random(semilla.toLong())
        f.writeBytes(ByteArray(bytes).also { r.nextBytes(it) })
        return f
    }

    /** Corta la conexión cuando han pasado [tope] bytes por aquí. */
    private class CortaTras(private val dentro: OutputStream, private val tope: Long, private val cortar: () -> Unit) : OutputStream() {
        private var n = 0L
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (n + len > tope) {
                val cabe = (tope - n).toInt().coerceAtLeast(0)
                if (cabe > 0) dentro.write(b, off, cabe)
                n = tope
                runCatching { dentro.flush() }
                cortar()
                throw IOException("cortado a propósito")
            }
            dentro.write(b, off, len); n += len
        }
        override fun flush() = dentro.flush()
    }

    private fun escuchar(d: Disco, capacidades: List<String> = Protocolo.CAPACIDADES, cortarTras: Long? = null): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching {
                server.accept().use { s ->
                    val salida = cortarTras?.let { t -> CortaTras(s.getOutputStream(), t) { s.close() } } ?: s.getOutputStream()
                    Respondedor(d, ahora = { reloj }, capacidades = capacidades).atender(s.getInputStream(), salida)
                }
            }
            server.close()
        }
        hilo.start()
        return server to hilo
    }

    private fun <T> conectado(
        desde: Disco, hacia: Disco, unirme: Boolean = false, codigo: String? = null,
        capsDelQueResponde: List<String> = Protocolo.CAPACIDADES, cortarDirigeTras: Long? = null, cortarRespondeTras: Long? = null,
        uso: (Sesion) -> T
    ): T {
        val (server, hilo) = escuchar(hacia, capsDelQueResponde, cortarRespondeTras)
        try {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                val salida = cortarDirigeTras?.let { t -> CortaTras(s.getOutputStream(), t) { s.close() } } ?: s.getOutputStream()
                val sesion = Sesion.conectar(s.getInputStream(), salida, desde, unirme, codigo, ahora = { reloj })
                try {
                    val r = uso(sesion)
                    sesion.adios()
                    return r
                } finally {
                    sesion.soltar()
                }
            }
        } finally {
            hilo.join(5000)
        }
    }

    private fun emparejar() {
        val codigo = telefono.crearGrupo(codigo = "ABCDE23456", ahora = reloj)
        conectado(tableta, telefono, unirme = true, codigo = codigo) {}
    }

    private class Vuelta(val hecho: Sesion.Hecho, val bytes: Long, val enviados: Long)

    private fun vuelta(
        desde: Disco = telefono, hacia: Disco = tableta, chats: List<String> = listOf(Disco.GENERAL, "p1", "p2"),
        capsDelQueResponde: List<String> = Protocolo.CAPACIDADES, cortarDirigeTras: Long? = null, cortarRespondeTras: Long? = null
    ): Vuelta {
        val hecho = Sesion.Hecho()
        return conectado(desde, hacia, capsDelQueResponde = capsDelQueResponde, cortarDirigeTras = cortarDirigeTras, cortarRespondeTras = cortarRespondeTras) { s ->
            s.vuelta(chats, hecho)
            Vuelta(hecho, s.enviados + s.recibidos, s.enviados)
        }
    }

    /** Tres chats con algo en cada uno, sincronizados una vez. */
    private fun tresChatsAlDia() {
        emparejar()
        telefono.guardarProyecto(Proyecto(id = "p1", nombre = "Uno"))
        telefono.guardarProyecto(Proyecto(id = "p2", nombre = "Dos"))
        dibujo(telefono, "d1", "plano")
        comoLaApp(telefono) { it + nuevo(telefono, "g1", "hola") + nuevo(telefono, "a1", "en uno", proyecto = "p1") +
            nuevo(telefono, "b1", "en dos", proyecto = "p2") + nuevo(telefono, "dib1", clase = Clase.DIBUJO, proyecto = "p1", referencia = "d1") }
        comoLaApp(tableta) { it + nuevo(tableta, "t1", "de la tableta") }
        val v = vuelta()
        assertEquals(0, v.hecho.quietos)
        assertTrue(File(tableta.filesDir, "pins/draw/d1.excalidraw.gz").isFile)
    }

    private fun textos(d: Disco) = d.leerMensajes().map { it.texto }.toSet()

    // ------------------------------------------------------------ el diario solo

    @Test
    fun `el diario apunta cada cambio del chat con su numero y por chats`() {
        val d = telefono.diario
        val antes = d.ultimo()
        comoLaApp(telefono) { it + nuevo(telefono, "m1", "uno", proyecto = "p1") }
        comoLaApp(telefono) { l -> l.map { if (it.id == "m1") it.copy(texto = "uno bis") else it } }
        comoLaApp(telefono) { l -> l.filter { it.id != "m1" } }
        val v = telefono.vistaDelDiario()
        assertTrue(v.ultimo >= antes + 3)
        assertTrue(v.cambio("p1", antes))
        assertFalse(v.cambio(Disco.GENERAL, antes))
        val lineas = File(telefono.filesDir, "sincro/diario/diario.jsonl").readLines().drop(1)
        assertTrue(lineas.any { "\"a\":\"alta\"" in it } && lineas.any { "\"a\":\"baja\"" in it })
    }

    @Test
    fun `la huella de un inventario es la de la guia para el PC`() {
        val h = Diario.huellaDeInventario(
            listOf(Diferencia.Apunte("b", 5, 5, "2"), Diferencia.Apunte("a", 9, 9, "1", borrado = true)),
            null, listOf(ArchivoInfo("x.pdf", "ff", 10, 123))
        )
        assertEquals("17850c345c3cb2825327a7bef787cab20e302089178cd80d61079c06ea5bf9d7", h)
    }

    @Test
    fun `se compacta solo y sigue diciendo lo mismo`() {
        val d = telefono.diario
        repeat(4_500) { i -> d.apuntar(listOf(Diario.Apunte(k = Diario.MENSAJE, c = "c${i % 3}", o = "m${i % 5}"))) }
        val lineas = File(telefono.filesDir, "sincro/diario/diario.jsonl").readLines().size
        assertTrue("compactado: $lineas líneas", lineas < 1_000)
        val v = telefono.vistaDelDiario()
        assertTrue(v.cambio("c0", v.ultimo - 3))
        assertFalse(v.cambio("c9", 0))
        assertEquals(4_500L, v.ultimo)
    }

    @Test
    fun `una linea rota o un diario borrado no se fian - se mira todo`() {
        val d = telefono.diario
        d.apuntar(listOf(Diario.Apunte(k = Diario.MENSAJE, c = "a", o = "x")))
        val n = d.ultimo()
        File(telefono.filesDir, "sincro/diario/diario.jsonl").appendText("{\"n\":99,\"k\":")
        // Otro proceso: una instancia nueva lee desde cero (aquí, la misma tras cambiar el archivo).
        d.apuntar(listOf(Diario.Apunte(k = Diario.MENSAJE, c = "b", o = "y")))
        assertTrue("lo roto cuenta como «todo»", telefono.vistaDelDiario().cambio("cualquiera", n))
        val libro = telefono.vistaDelDiario().libro
        File(telefono.filesDir, "sincro/diario/diario.jsonl").delete()
        assertTrue("diario nuevo, libro nuevo", telefono.vistaDelDiario().libro != libro)
    }

    @Test
    fun `el barrido ve lo escrito sin avisar aunque sea del mismo tamano y en el mismo instante`() {
        val f = binario(telefono, "guardados/a.bin", 1000, semilla = 1)
        telefono.diario.barrer()
        val n = telefono.diario.ultimo()
        // Mismo tamaño, en su sitio y enseguida: la fecha puede no moverse; el CRC de lo reciente sí.
        f.writeBytes(ByteArray(1000).also { java.util.Random(2).nextBytes(it) })
        assertTrue(telefono.diario.barrer() >= 1)
        assertTrue(File(telefono.filesDir, "sincro/diario/diario.jsonl").readLines().any { "guardados/a.bin" in it && it.contains("\"n\":${n + 1}") })
        // Y lo escrito por Disco (apuntado con su huella) no se apunta otra vez.
        telefono.escribirArchivo("guardados/b.bin") { it.write(byteArrayOf(1, 2, 3)); true }
        assertEquals(0, telefono.diario.barrer())
    }

    // ------------------------------------------------------------ la vuelta con diario

    @Test
    fun `la segunda vuelta ni mira los chats y no mueve nada, desde los dos lados`() {
        tresChatsAlDia()
        val v = vuelta()
        assertEquals(3, v.hecho.quietos)
        assertEquals(0, v.hecho.traidos + v.hecho.enviados + v.hecho.fusionados + v.hecho.archivos)
        // Quien respondía también: su diario y el cursor que apuntó al cerrar.
        val w = vuelta(desde = tableta, hacia = telefono)
        assertEquals(3, w.hecho.quietos)
        assertEquals(textos(telefono), textos(tableta))
    }

    @Test
    fun `un cambio de la app solo hace mirar su chat`() {
        tresChatsAlDia()
        comoLaApp(telefono) { l -> l.map { if (it.id == "a1") it.copy(texto = "en uno, cambiado") else it } }
        val v = vuelta()
        assertEquals("general y p2 quietos", 2, v.hecho.quietos)
        assertTrue("en uno, cambiado" in textos(tableta))
        assertEquals(3, vuelta().hecho.quietos)
    }

    @Test
    fun `lo escrito a escondidas tambien llega - el chat entero se mira`() {
        tresChatsAlDia()
        aEscondidas(tableta) { l -> l.map { if (it.id == "b1") it.copy(texto = "en dos, por la puerta de atrás") else it } }
        val v = vuelta()
        assertEquals("sin saber qué chat, todos", 0, v.hecho.quietos)
        assertTrue("en dos, por la puerta de atrás" in textos(telefono))
        assertEquals(3, vuelta().hecho.quietos)
    }

    @Test
    fun `un lienzo reescrito sin avisar se encuentra por el indice y solo se mira su chat`() {
        tresChatsAlDia()
        dibujo(tableta, "d1", "plano retocado")
        val v = vuelta()
        assertEquals(2, v.hecho.quietos)
        assertTrue(telefono.textoDe("pins/draw/d1.excalidraw.gz").contains("plano retocado"))
    }

    @Test
    fun `sin diario se hace lo de siempre y no se pierde nada`() {
        tresChatsAlDia()
        File(telefono.filesDir, "sincro/diario").deleteRecursively()
        aEscondidas(telefono) { it + nuevo(telefono, "g2", "tras perder el diario") }
        val v = vuelta()
        assertEquals(0, v.hecho.quietos)
        assertTrue("tras perder el diario" in textos(tableta))
        assertEquals(3, vuelta().hecho.quietos)
    }

    @Test
    fun `apagado, todo como antes y con el mismo resultado`() {
        tresChatsAlDia()
        Diario.activo = false
        comoLaApp(telefono) { it + nuevo(telefono, "g3", "sin diario") }
        val v = vuelta()
        assertEquals(0, v.hecho.quietos)
        assertTrue("sin diario" in textos(tableta))
    }

    @Test
    fun `cada tantas vueltas se mira todo aunque nada cambie`() {
        tresChatsAlDia()
        telefono.diario.cambiarCursores("id-tab") { it.copy(vueltas = Diario.Cursores.VUELTAS_POR_VERIFICACION) }
        assertEquals("la de verificar mira todo", 0, vuelta().hecho.quietos)
        assertEquals(3, vuelta().hecho.quietos)
    }

    @Test
    fun `con un PixPin sin diario (como el PC de hoy) tambien se salta lo quieto, por su inventario`() {
        emparejar()
        telefono.guardarProyecto(Proyecto(id = "p1", nombre = "Uno"))
        comoLaApp(telefono) { it + nuevo(telefono, "g1", "hola") + nuevo(telefono, "a1", "en uno", proyecto = "p1") }
        val antes = Protocolo.CAPACIDADES
        assertEquals(0, vuelta(chats = listOf(Disco.GENERAL, "p1"), capsDelQueResponde = emptyList()).hecho.quietos)
        val v = vuelta(chats = listOf(Disco.GENERAL, "p1"), capsDelQueResponde = emptyList())
        assertEquals(2, v.hecho.quietos)
        // Un cambio allí (sin diario) se ve en su inventario y llega.
        aEscondidas(tableta) { l -> l.map { if (it.id == "a1") it.copy(texto = "cambiado en el de antes") else it } }
        val w = vuelta(chats = listOf(Disco.GENERAL, "p1"), capsDelQueResponde = emptyList())
        assertEquals(1, w.hecho.quietos)
        assertTrue("cambiado en el de antes" in textos(telefono))
        assertEquals(antes, Protocolo.CAPACIDADES)
    }

    // ------------------------------------------------------------ fiabilidad

    @Test
    fun `una vuelta cortada a mitad de un archivo grande sigue donde se quedo, al mandar`() {
        emparejar()
        val f = binario(telefono, "guardados/obra.mp4", 4 shl 20)
        comoLaApp(telefono) { it + nuevo(telefono, "v1", clase = Clase.ARCHIVO, ruta = f.absolutePath) }
        try { vuelta(chats = listOf(Disco.GENERAL), cortarDirigeTras = (5L shl 20) / 2); fail("tenía que cortarse") } catch (_: IOException) {}
        assertFalse(File(tableta.filesDir, "guardados/obra.mp4").exists())
        val llegado = File(tableta.filesDir, "guardados/obra.mp4" + Disco.PARCIAL).length()
        assertTrue("algo llegó: $llegado", llegado >= (1L shl 20))
        val v = vuelta(chats = listOf(Disco.GENERAL))
        assertArrayEquals(f.readBytes(), File(tableta.filesDir, "guardados/obra.mp4").readBytes())
        assertTrue("no se mandó entero otra vez: ${v.enviados}", v.enviados < (4L shl 20) - llegado / 2)
        assertFalse(File(tableta.filesDir, "guardados/obra.mp4" + Disco.PARCIAL).exists())
    }

    @Test
    fun `una vuelta cortada a mitad de un archivo grande sigue donde se quedo, al traer`() {
        emparejar()
        val f = binario(tableta, "guardados/clase.m4a", 4 shl 20)
        comoLaApp(tableta) { it + nuevo(tableta, "v1", clase = Clase.VOZ, ruta = f.absolutePath) }
        try { vuelta(chats = listOf(Disco.GENERAL), cortarRespondeTras = (5L shl 20) / 2); fail("tenía que cortarse") } catch (_: IOException) {}
        val llegado = File(telefono.filesDir, "guardados/clase.m4a" + Disco.PARCIAL).length()
        assertTrue("algo llegó: $llegado", llegado >= (1L shl 20))
        val v = vuelta(chats = listOf(Disco.GENERAL))
        assertArrayEquals(f.readBytes(), File(telefono.filesDir, "guardados/clase.m4a").readBytes())
        assertTrue("solo lo que faltaba: ${v.bytes}", v.bytes < (4L shl 20) - llegado / 2)
        assertTrue(v.hecho.ahorrados >= llegado)
    }

    @Test
    fun `lo llegado a medias de otra version no se aprovecha - el archivo sale entero y bien`() {
        emparejar()
        val f = binario(telefono, "guardados/obra.mp4", 3 shl 20, semilla = 1)
        comoLaApp(telefono) { it + nuevo(telefono, "v1", clase = Clase.ARCHIVO, ruta = f.absolutePath) }
        try { vuelta(chats = listOf(Disco.GENERAL), cortarDirigeTras = 2L shl 20) } catch (_: IOException) {}
        binario(telefono, "guardados/obra.mp4", 3 shl 20, semilla = 2)
        vuelta(chats = listOf(Disco.GENERAL))
        assertArrayEquals(f.readBytes(), File(tableta.filesDir, "guardados/obra.mp4").readBytes())
    }

    @Test
    fun `sin sitio para recibir no se descuadra la conversacion y llega en cuanto lo hay`() {
        emparejar()
        val f = binario(telefono, "guardados/foto.jpg", 20_000)
        dibujo(telefono, "d1", "plano")
        comoLaApp(telefono) { it + nuevo(telefono, "f1", clase = Clase.IMAGEN, ruta = f.absolutePath) +
            nuevo(telefono, "d1m", clase = Clase.DIBUJO, referencia = "d1") + nuevo(telefono, "n1", "y una nota") }
        tableta.espacioLibre = { 1L }
        val v = vuelta(chats = listOf(Disco.GENERAL))
        assertTrue("los mensajes llegan", "y una nota" in textos(tableta))
        assertTrue("se quedaron sin pasar: ${v.hecho.saltados}", v.hecho.saltados.isNotEmpty())
        assertFalse(File(tableta.filesDir, "guardados/foto.jpg").exists())
        tableta.espacioLibre = null
        vuelta(chats = listOf(Disco.GENERAL))
        assertArrayEquals(f.readBytes(), File(tableta.filesDir, "guardados/foto.jpg").readBytes())
        assertTrue(File(tableta.filesDir, "pins/draw/d1.excalidraw.gz").isFile)
        // Y al traer: sin sitio aquí, se dice.
        val g = binario(tableta, "guardados/otra.jpg", 20_000)
        comoLaApp(tableta) { it + nuevo(tableta, "f2", clase = Clase.IMAGEN, ruta = g.absolutePath) }
        telefono.espacioLibre = { 1L }
        assertTrue(vuelta(chats = listOf(Disco.GENERAL)).hecho.sinEspacio)
        telefono.espacioLibre = null
    }

    @Test
    fun `la salida que aguanta guarda el fallo y sigue tragando`() {
        var escritos = 0
        val lleno = object : OutputStream() {
            override fun write(b: Int) { if (escritos++ >= 10) throw IOException("No space left on device") }
        }
        val s = SalidaQueAguanta(lleno)
        repeat(100) { s.write(it) }
        assertNotNull(s.fallo)
    }

    @Test
    fun `los restos de una vuelta muerta se limpian al empezar la siguiente`() {
        tresChatsAlDia()
        val resto = File(tableta.filesDir, "guardados/x.pdf.sincro").apply { parentFile!!.mkdirs(); writeText("medio") }
        val viejo = File(tableta.filesDir, "guardados/y.mp4" + Disco.PARCIAL).apply { writeText("viejo"); setLastModified(System.currentTimeMillis() - 8L * 24 * 3600_000) }
        val nuevoParcial = File(tableta.filesDir, "guardados/z.mp4" + Disco.PARCIAL).apply { writeText("nuevo") }
        vuelta()
        assertFalse(resto.exists())
        assertFalse(viejo.exists())
        assertTrue("lo reciente se guarda para reanudar", nuevoParcial.exists())
    }

    /** Dos canales de verdad por un socket local; lo que escribe el primero se copia también en [copia]. */
    private fun dosCanales(copia: ByteArrayOutputStream): Triple<Canal, Canal, OutputStream> {
        val clave = Grupo.clave("ABCDE23456")
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var b: Canal? = null
        val hilo = Thread { val s = server.accept(); b = Canal.abrir(s.getInputStream(), s.getOutputStream(), clave, inicia = false) }.apply { start() }
        val s = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val crudo = s.getOutputStream()
        val aEscribe = object : OutputStream() {
            override fun write(x: Int) { crudo.write(x); copia.write(x) }
            override fun write(x: ByteArray, off: Int, len: Int) { crudo.write(x, off, len); copia.write(x, off, len) }
            override fun flush() = crudo.flush()
        }
        val a = Canal.abrir(s.getInputStream(), aEscribe, clave, inicia = true)
        hilo.join(5000)
        server.close()
        return Triple(a, b!!, crudo)
    }

    @Test
    fun `un trozo repetido o de mas corta la conversacion sin escribir nada`() {
        val copia = ByteArrayOutputStream()
        val (a, b, crudo) = dosCanales(copia)
        copia.reset()
        a.enviar(Protocolo.TROZO, ByteArray(10) { 1 }); a.vaciar()
        crudo.write(copia.toByteArray()); crudo.flush()   // el mismo tramo, otra vez
        assertEquals(Protocolo.TROZO, b.recibir().first)
        try { b.recibir(); fail("el repetido no puede pasar") } catch (e: Canal.TramoDanado) {}
        // De más: diez bytes donde se prometieron cinco.
        val (a2, b2, _) = dosCanales(ByteArrayOutputStream())
        a2.enviar(Protocolo.TROZO, ByteArray(10)); a2.vaciar()
        val salida = ByteArrayOutputStream()
        try { Protocolo.recibirTrozos(b2, 5, salida) {}; fail("de más no") } catch (e: IOException) {}
        assertEquals(0, salida.size())
    }

    @Test
    fun `dos a la vez - el segundo oye ocupado, nada se rompe y luego va`() {
        tresChatsAlDia()
        assertTrue(Protocolo.ocupar(tableta))
        try {
            comoLaApp(telefono) { it + nuevo(telefono, "g9", "mientras tanto") }
            try { vuelta(); fail("tenía que estar ocupado") } catch (e: IOException) {
                assertEquals(Protocolo.OCUPADO, e.message)
            }
        } finally {
            Protocolo.soltar(tableta)
        }
        assertTrue("el que dirigía quedó libre", Protocolo.ocupar(telefono)); Protocolo.soltar(telefono)
        vuelta()
        assertTrue("mientras tanto" in textos(tableta))
    }

    @Test
    fun `borrado en uno y cambiado en el otro - se queda lo cambiado en los dos`() {
        tresChatsAlDia()
        val antes = telefono.leerMensajes()
        comoLaApp(telefono) { l -> l.filter { it.id != "b1" } }
        telefono.anotarBorrados(Disco.borradosEntre(antes, antes.filter { it.id != "b1" }, telefono.identidad.leer().letra, reloj++))
        comoLaApp(tableta) { l -> l.map { if (it.id == "b1") it.copy(texto = "en dos, retocado") else it } }
        vuelta()
        assertTrue("en dos, retocado" in textos(telefono))
        assertTrue("en dos, retocado" in textos(tableta))
    }

    @Test
    fun `dos vueltas seguidas sin cambios no mueven nada (idempotente)`() {
        tresChatsAlDia()
        val primero = File(tableta.filesDir, "guardados.jsonl").readText()
        repeat(2) {
            val v = vuelta()
            assertEquals(0, v.hecho.traidos + v.hecho.enviados + v.hecho.borrados + v.hecho.archivos + v.hecho.fusionados)
        }
        assertEquals(primero, File(tableta.filesDir, "guardados.jsonl").readText())
    }

    // ------------------------------------------------------------ prioridades

    @Test
    fun `cortada a mitad del video, las tareas y el chat compartido ya llegaron`() {
        emparejar()
        telefono.guardarProyecto(Proyecto(id = "p-tareas", nombre = "Obra"))
        telefono.guardarProyecto(Proyecto(id = "p-compartido", nombre = "Con Ana"))
        CompartidosEnDisco(telefono.filesDir).cambiar { listOf(ChatCompartido("p-compartido", "Con Ana", "id-tel", reloj)) }
        val video = binario(telefono, "guardados/clase.mp4", 4 shl 20)
        comoLaApp(telefono) {
            it + nuevo(telefono, "vid", clase = Clase.ARCHIVO, ruta = video.absolutePath) +
                nuevo(telefono, "tar", "- [ ] comprar cemento", clase = Clase.MINIAPP, proyecto = "p-tareas", miniapp = "tareas") +
                nuevo(telefono, "ana", "para Ana", proyecto = "p-compartido")
        }
        try {
            vuelta(chats = listOf(Disco.GENERAL, "p-compartido", "p-tareas"), cortarDirigeTras = 2L shl 20)
            fail("tenía que cortarse")
        } catch (_: IOException) {}
        val llegados = textos(tableta)
        assertTrue("las tareas: $llegados", "- [ ] comprar cemento" in llegados)
        assertTrue("el compartido: $llegados", "para Ana" in llegados)
        assertFalse(File(tableta.filesDir, "guardados/clase.mp4").exists())
    }

    @Test
    fun `los archivos van por niveles y lo pesado al final`() {
        emparejar()
        val video = binario(telefono, "guardados/a.mp4", 300_000, 1)
        val pdf = binario(telefono, "guardados/b.pdf", 200_000, 2)
        dibujo(telefono, "c", "lienzo")
        comoLaApp(telefono) {
            it + nuevo(telefono, "v", clase = Clase.ARCHIVO, ruta = video.absolutePath) +
                nuevo(telefono, "p", clase = Clase.ARCHIVO, ruta = pdf.absolutePath) + nuevo(telefono, "l", clase = Clase.DIBUJO, referencia = "c")
        }
        val orden = ArrayList<String>()
        val (server, hilo) = escuchar(tableta)
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), telefono, ahora = { reloj })
            val hecho = Sesion.Hecho()
            val prep = sesion.preparar(Disco.GENERAL)
            sesion.aplicar(prep, hecho)
            val arch = sesion.prepararArchivos(prep)
            sesion.aplicarArchivos(arch, hecho) { }
            sesion.cerrar(prep)
            sesion.adios(); sesion.soltar()
        }
        hilo.join(5000)
        // El orden en que llegaron, por la fecha de escritura en la tableta.
        for (rel in listOf("guardados/b.pdf", "pins/draw/c.excalidraw.gz", "guardados/a.mp4")) {
            val f = File(tableta.filesDir, rel)
            assertTrue(rel, f.isFile)
            orden += rel
        }
        val fechas = orden.map { java.nio.file.Files.getLastModifiedTime(File(tableta.filesDir, it).toPath()).toMillis() }
        assertTrue("el vídeo el último: $fechas", fechas[2] >= fechas[0] && fechas[2] >= fechas[1])
    }
}
