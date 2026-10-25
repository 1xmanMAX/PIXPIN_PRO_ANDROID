package com.forge.pixpin.sincro

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Enviar a mis aparatos sin código** ([AlGrupo]): a quién se ofrece, y el `suelto` de verdad
 * contra el [Respondedor] de otro aparato del grupo por un socket local, como `al_movil.rs` del PC.
 */
class AlGrupoTest {

    private lateinit var raiz: File
    private lateinit var otro: Disco
    private lateinit var codigo: String

    /** Lo que recibió el otro: el contenido, el nombre y el tipo. */
    private val llegadas = java.util.Collections.synchronizedList(mutableListOf<Triple<ByteArray, String, String>>())

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("algrupo").toFile()
        otro = Disco(File(raiz, "tablet/files").apply { mkdirs() })
        otro.identidad.guardar(Identidad(Aparato("id-tablet", "Tablet")))
        codigo = otro.crearGrupo(codigo = "ABCDE23456", ahora = 1_000L)
    }

    @After
    fun desmontar() {
        Protocolo.soltar(otro)
        raiz.deleteRecursively()
    }

    private val yo = Aparato("id-movil", "Pixel", letra = "b")
    private fun miIdentidad(codigoDelGrupo: String? = codigo) =
        Identidad(yo, codigoDelGrupo, listOf(Aparato("id-tablet", "Tablet", "a"), yo))

    private fun alChat(chat: String): (File, String, String, Aparato) -> Pair<String, String?> = { f, nombre, mime, _ ->
        llegadas += Triple(f.readBytes(), nombre, mime)
        Protocolo.SUELTO_EN_EL_CHAT_ABIERTO to chat
    }

    /** El otro aparato atiende una conexión; este le manda [archivos] con [AlGrupo.mandar]. */
    private fun mandar(
        archivos: List<AlGrupo.Archivo>,
        gancho: ((File, String, String, Aparato) -> Pair<String, String?>)? = alChat("Tesis"),
        aceptar: ((String, String) -> String?)? = null,
        identidad: Identidad = miIdentidad(),
        saludos: MutableList<Pair<Aparato, Int>> = mutableListOf()
    ): Result<AlGrupo.Entregado> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var fallo: Throwable? = null
        val hilo = Thread {
            try {
                server.accept().use { s ->
                    Respondedor(otro, miPuerto = 47474, alAceptarSuelto = aceptar, alRecibirSuelto = gancho, cache = File(raiz, "cache"))
                        .atender(s.getInputStream(), s.getOutputStream())
                }
            } catch (e: Throwable) {
                fallo = e
            } finally {
                server.close()
            }
        }
        hilo.start()
        val r = Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            AlGrupo.mandar(s.getInputStream(), s.getOutputStream(), identidad, "Tablet", archivos, miPuerto = 47475, alSaludar = { a, p -> saludos += a to p })
        }
        hilo.join(5000)
        // Con otro código el otro lado se corta, como debe; en lo demás la conversación acaba sana.
        if (identidad.codigo == codigo) fallo?.let { throw AssertionError("El otro aparato se cortó", it) }
        return r
    }

    private fun archivo(nombre: String, datos: ByteArray, mime: String = AlGrupo.mimeDe(nombre)): AlGrupo.Archivo {
        val f = File(raiz, "salida/$nombre").apply { parentFile.mkdirs(); writeBytes(datos) }
        return AlGrupo.Archivo(f, nombre, mime)
    }

    private fun bytes(n: Int, semilla: Int = 7) = ByteArray(n) { ((it * 31 + semilla) and 0xff).toByte() }

    // ---- A quién ---------------------------------------------------------

    @Test
    fun `salen los del grupo menos yo y primero los que se ven`() {
        val id = Identidad(
            yo, codigo,
            listOf(
                Aparato("id-tablet", "Tablet", "a"), yo, Aparato("id-pc", "Torre", "c"),
                Aparato("", "Sin id"), Aparato("id-pc", "Torre repetida", "c")
            )
        )
        val presentes = listOf(
            AlDia.Estado("id-pc", "Torre", "192.168.1.20", 47474),
            AlDia.Estado("id-extrano", "No es del grupo", "192.168.1.99", 47474),
            AlDia.Estado("id-tablet", "Tablet", "", 0)   // sin dirección: no cuenta
        )
        val f = AlGrupo.filas(id, presentes)
        assertEquals(listOf("id-pc", "id-tablet"), f.map { it.aparato.id })
        assertEquals(listOf(true, false), f.map { it.seVe })
        assertEquals("192.168.1.20" to 47474, f[0].host to f[0].puerto)
        // Sin nadie en la Wi-Fi, salen igual (para decir «abre PixPin allí»), sin poder mandarles.
        assertEquals(listOf(false, false), AlGrupo.filas(id, emptyList()).map { it.seVe })
        // Caso negativo: sin grupo, ninguno.
        assertTrue(AlGrupo.filas(Identidad(yo.copy(letra = null), null, id.miembros), presentes).isEmpty())
        assertTrue(AlGrupo.filas(Identidad(yo, codigo, listOf(yo)), presentes).isEmpty())
    }

    @Test
    fun `a todos va a los que se ven y no repite lo que sale o ya llego`() {
        val filas = listOf("tel", "tablet", "pc", "lejos").map { AlGrupo.Fila(Aparato(it, it), if (it == "lejos") null else "10.0.0.1", 47474) }
        assertEquals(listOf("tel", "tablet", "pc"), AlGrupo.aTodos(filas, emptyMap()))
        val estados = mapOf(
            "tel" to AlGrupo.Estado.Saliendo(),
            "tablet" to AlGrupo.Estado.Hecho(AlGrupo.Entregado("tablet", emptyList(), 0)),
            "pc" to AlGrupo.Estado.Fallo(AlGrupo.Fallo.NoEsta("pc"))
        )
        assertEquals("al que falló se le vuelve a probar", listOf("pc"), AlGrupo.aTodos(filas, estados))
    }

    @Test
    fun `solo los archivos van directos`() {
        assertTrue(AlGrupo.sePuede(listOf(Envio.ARCHIVO, Envio.ARCHIVO)))
        assertTrue(!AlGrupo.sePuede(listOf(Envio.ARCHIVO, Envio.PROYECTO)))
        assertTrue(!AlGrupo.sePuede(listOf(Envio.LIENZO)))
        assertTrue(!AlGrupo.sePuede(emptyList()))
    }

    @Test
    fun `el tipo sale de la extension como en el PC`() {
        assertEquals("image/png", AlGrupo.mimeDe("Captura.PNG"))
        assertEquals("image/jpeg", AlGrupo.mimeDe("a.b.jpeg"))
        assertEquals("application/pdf", AlGrupo.mimeDe("informe.pdf"))
        assertEquals("application/octet-stream", AlGrupo.mimeDe("sin_extension"))
        assertEquals("application/octet-stream", AlGrupo.mimeDe("raro.xyz"))
    }

    // ---- El cable --------------------------------------------------------

    @Test
    fun `una foto y un pdf llegan enteros al chat abierto del otro`() {
        val foto = bytes((1 shl 20) + 333)   // dos trozos
        val pdf = bytes(4000, 3)
        val saludos = mutableListOf<Pair<Aparato, Int>>()
        val r = mandar(listOf(archivo("foto.png", foto), archivo("informe.pdf", pdf)), saludos = saludos)
        val e = r.getOrThrow()
        assertEquals("Tablet", e.aparato)
        assertEquals(listOf(AlGrupo.Llegada("chat_abierto", "Tesis"), AlGrupo.Llegada("chat_abierto", "Tesis")), e.llegadas)
        assertEquals(0, e.negados)
        assertEquals(listOf("foto.png", "informe.pdf"), llegadas.map { it.second })
        assertEquals(listOf("image/png", "application/pdf"), llegadas.map { it.third })
        assertArrayEquals(foto, llegadas[0].first)
        assertArrayEquals(pdf, llegadas[1].first)
        assertEquals("2 archivos enviados al chat «Tesis» de Tablet", AlGrupo.texto(e))
        // Dónde escucha el otro, para apuntarlo.
        assertEquals(listOf("id-tablet" to 47474), saludos.map { it.first.id to it.second })
        // Y el otro aparato me tiene en su grupo (el saludo de siempre junta los miembros).
        assertTrue(otro.identidad.leer().miembros.any { it.id == "id-movil" })
    }

    @Test
    fun `el avance llega hasta el total`() {
        val datos = bytes(3 * (1 shl 20) / 2)
        val vistos = mutableListOf<Pair<Long, Long>>()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            server.accept().use { s -> Respondedor(otro, alRecibirSuelto = alChat("Tesis"), cache = File(raiz, "cache")).atender(s.getInputStream(), s.getOutputStream()) }
            server.close()
        }.apply { start() }
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            AlGrupo.mandar(s.getInputStream(), s.getOutputStream(), miIdentidad(), "Tablet", listOf(archivo("v.mp4", datos))) { h, t -> vistos += h to t }.getOrThrow()
        }
        hilo.join(5000)
        assertEquals(datos.size.toLong() to datos.size.toLong(), vistos.last())
        assertTrue(vistos.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun `con un lienzo delante la foto entra y el pdf se niega sin cortar`() {
        val alLienzo: (File, String, String, Aparato) -> Pair<String, String?> = { f, nombre, mime, _ ->
            llegadas += Triple(f.readBytes(), nombre, mime); Protocolo.SUELTO_EN_EL_LIENZO to null
        }
        val conLienzo: (String, String) -> String? = { _, mime -> LoAbierto.aceptar(LoAbierto.Delante.LIENZO, mime, "Tablet") }
        val e = mandar(listOf(archivo("plano.pdf", bytes(100)), archivo("foto.jpg", bytes(200))), alLienzo, conLienzo).getOrThrow()
        assertEquals(listOf(AlGrupo.Llegada("lienzo")), e.llegadas)
        assertEquals(1, e.negados)
        assertEquals(listOf("foto.jpg"), llegadas.map { it.second })
        assertEquals("Enviada al lienzo de Tablet\n1 archivo no se envió: no era una foto y tiene un lienzo abierto", AlGrupo.texto(e))
        // Solo cosas que no son fotos: no llega nada, y se dice por qué.
        val r = mandar(listOf(archivo("plano.pdf", bytes(100))), alLienzo, conLienzo)
        assertTrue(AlGrupo.falloDe(r.exceptionOrNull()!!) is AlGrupo.Fallo.SoloFotos)
    }

    @Test
    fun `un PixPin de antes contesta que no sabe y la conversacion acaba sana`() {
        // Sin gancho, el Respondedor contesta «No sé qué es «suelto»», como uno de antes del 6-oct.
        val r = mandar(listOf(archivo("foto.png", bytes(10))), gancho = null)
        val f = AlGrupo.falloDe(r.exceptionOrNull()!!)
        assertTrue(f is AlGrupo.Fallo.SinSoporte)
        assertEquals("Actualiza PixPin en Tablet: aún no recibe archivos directos. Que use el código de abajo.", AlGrupo.texto(f))
        assertTrue(llegadas.isEmpty())
    }

    @Test
    fun `si el otro esta sincronizando lo dice`() {
        assertTrue(Protocolo.ocupar(otro))
        val r = mandar(listOf(archivo("foto.png", bytes(10))))
        assertTrue(AlGrupo.falloDe(r.exceptionOrNull()!!) is AlGrupo.Fallo.Ocupado)
        assertTrue(llegadas.isEmpty())
    }

    @Test
    fun `quien manda no se ocupa a si mismo`() {
        // Mandar no escribe nada aquí: tiene que poder hacerse mientras este aparato sincroniza.
        val r = mandar(listOf(archivo("foto.png", bytes(10))))
        assertTrue(r.isSuccess)
        assertTrue("el otro queda libre al acabar", Protocolo.ocupar(otro))
    }

    @Test
    fun `con otro codigo de grupo no llega nada`() {
        val r = mandar(listOf(archivo("foto.png", bytes(10))), identidad = miIdentidad("ZZZZZ23456"))
        val f = AlGrupo.falloDe(r.exceptionOrNull()!!)
        assertTrue(f is AlGrupo.Fallo.Cortado)
        assertEquals("No se pudo enviar a Tablet: ¿tiene el mismo código de grupo?", AlGrupo.texto(f))
        assertTrue(llegadas.isEmpty())
    }

    @Test
    fun `sin direccion no se intenta y se dice que abra PixPin`() {
        val r = AlGrupo.mandarA(otro, AlGrupo.Fila(Aparato("id-tablet", "Tablet")), listOf(archivo("a.png", bytes(5))), 0) { _, _ -> }
        val f = AlGrupo.falloDe(r.exceptionOrNull()!!)
        assertTrue(f is AlGrupo.Fallo.NoEsta)
        assertEquals("Tablet no contesta: abre PixPin en ese aparato", AlGrupo.texto(f))
        assertNull(AlGrupo.Fila(Aparato("x", "x")).host)
    }

    @Test
    fun `los textos de cada fila`() {
        assertEquals("En la misma Wi-Fi", AlGrupo.texto(null, true))
        assertEquals("No se ve ahora: abre PixPin en ese aparato", AlGrupo.texto(null, false))
        assertEquals("Enviando…", AlGrupo.texto(AlGrupo.Estado.Saliendo(), true))
        assertEquals(
            "Tablet lo guardó en la conversación general (no tenía un chat ni un lienzo abierto)",
            AlGrupo.texto(AlGrupo.Estado.Hecho(AlGrupo.Entregado("Tablet", listOf(AlGrupo.Llegada("chat")), 0)), true)
        )
        assertEquals(
            "3 imágenes enviadas al lienzo de Tablet",
            AlGrupo.texto(AlGrupo.Entregado("Tablet", List(3) { AlGrupo.Llegada("lienzo") }, 0))
        )
    }
}
