package com.forge.pixpin.sincro

import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **Ocupado al grabar, contra el PC de verdad** (28-oct-2026, §11.6 de `docs/movil-como-camara.md`
 * del PC). Quien graba se da por ocupado para sincronizar, pero la cámara sigue:
 *
 * 1. **El PC graba** (`ocupar` en `pc-simulado camara`, lo que hace su baliza): su `Respondedor`
 *    manda un «ocupado» suelto tras el `hola` y atiende la cámara igual. El teléfono pregunta
 *    `camara_remota` y sube fotos por la **misma** conexión sin descuadrarse; y su vuelta de
 *    sincronizar oye «ocupado».
 * 2. **El teléfono graba** ([Protocolo.grabando]): el PC le manda una captura a su grabación y su
 *    aviso de cámara y entran; la vuelta del PC oye «ocupado» (`ErrorSincro::Remoto`); al acabar,
 *    la vuelta va.
 *
 * Solo corre con `PIXPIN_PC_SIMULADO=<ruta del programa>`.
 */
class OcupadoAlGrabarContraElPcTest {

    private val programa = System.getenv("PIXPIN_PC_SIMULADO")
    private lateinit var raiz: File
    private lateinit var pc: File
    private lateinit var movil: Disco
    private var proceso: Process? = null
    private var deLaEntrada: BufferedReader? = null
    private var puerto = 0
    private val s = 1000L
    private val pared = 1_761_550_000_000L
    private val adelanto = 7 * s
    @Volatile private var relojDelPc = pared
    private fun ahoraMovil() = relojDelPc + adelanto

    private fun montar() {
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        raiz = Files.createTempDirectory("pc-ocupado").toFile()
        pc = File(raiz, "PixPin Max")
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        orden("preparar", pc.path, CODIGO)
        val archivoDelPuerto = File(raiz, "puerto")
        val p = ProcessBuilder(programa, "camara", pc.path, archivoDelPuerto.path).redirectError(File(raiz, "pc.log")).start()
        proceso = p
        deLaEntrada = p.inputStream.bufferedReader()
        val hasta = System.currentTimeMillis() + 10_000
        var leido: Int? = null
        while (leido == null && System.currentTimeMillis() < hasta) {
            leido = archivoDelPuerto.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (leido == null) Thread.sleep(50)
        }
        puerto = leido ?: error("El PC simulado no dijo su puerto: " + File(raiz, "pc.log").takeIf { it.isFile }?.readText().orEmpty())
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val sesion = Sesion.conectar(so.getInputStream(), so.getOutputStream(), movil, true, CODIGO)
            try { sesion.adios() } finally { sesion.soltar() }
        }
        assertTrue(movil.identidad.leer().enGrupo)
    }

    @After
    fun desmontar() {
        if (::movil.isInitialized) Protocolo.grabando(movil, false)
        runCatching { proceso?.outputStream?.apply { write("fin\n".toByteArray()); flush() } }
        proceso?.destroy()
        if (::raiz.isInitialized) raiz.deleteRecursively()
    }

    private fun orden(vararg args: String): String {
        val p = ProcessBuilder(programa, *args).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText()
        assertTrue("el PC no terminó: $salida", p.waitFor(120, TimeUnit.SECONDS))
        assertEquals("el PC falló: $salida", 0, p.exitValue())
        return salida
    }

    private fun barra(linea: String): String {
        val p = proceso!!
        p.outputStream.write((linea + "\n").toByteArray())
        p.outputStream.flush()
        return deLaEntrada!!.readLine() ?: error("El PC se calló: " + File(raiz, "pc.log").readText())
    }

    private fun aLas(segundos: Long) {
        relojDelPc = pared + segundos * s
        assertEquals("ok", barra("hora $relojDelPc"))
    }

    private fun jpeg(semilla: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10))
        b.write("Exif\u0000\u0000MM\u0000*\u0000\u0000\u0000\u0008".toByteArray(Charsets.ISO_8859_1))
        for (i in 0 until 3000) b.write((i * semilla) and 0xff)
        b.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return b.toByteArray()
    }

    /** El teléfono atiende una llamada (del PC) con su [Respondedor]. */
    private fun atenderUnaVez(quien: QuienGraba?): Pair<ServerSocket, Thread> {
        val escucha = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching {
                escucha.accept().use { so ->
                    Respondedor(movil, ahora = ::ahoraMovil, cache = File(raiz, "cache"), quienGraba = quien).atender(so.getInputStream(), so.getOutputStream())
                }
            }
            escucha.close()
        }
        hilo.start()
        return escucha to hilo
    }

    /** La vuelta entera del PC contra el teléfono: lo que escribe y si acabó bien. */
    private fun dirigeElPc(): Pair<Int, String> {
        val (escucha, hilo) = atenderUnaVez(null)
        val p = ProcessBuilder(programa, "dirigir", pc.path, escucha.localPort.toString()).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS))
        hilo.join(10_000)
        return p.exitValue() to salida
    }

    @Test
    fun `el PC que graba se da por ocupado y el telefono le sigue mandando fotos por la misma conexion`() {
        montar()
        aLas(0)
        assertEquals("ok", barra("empezar g-1 Reunión de obra"))
        assertEquals("se da por ocupado, como su baliza al grabar", "true", barra("ocupar"))
        aLas(12)
        val cola = ColaDeLaCamara.de(movil.filesDir)
        val f = cola.ficheroNuevo("jpg").apply { writeBytes(jpeg(3)) }
        cola.poner(
            ColaDeLaCamara.Toma(
                clave = "toma-1", fichero = f.name, nombre = "IMG_1.jpg", mime = "image/jpeg", grabacion = "g-1", pc = "id-pc",
                ms = 12 * s, tomadaEn = ahoraMovil(), reloj = adelanto, creada = 1
            )
        )
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val con = ConexionDeCamara.abrir(so.getInputStream(), so.getOutputStream(), movil.identidad.leer(), ahora = ::ahoraMovil)
            // Antes, esta primera pregunta leía el «ocupado» suelto del PC y la conexión quedaba descuadrada.
            val r = con.preguntar(::ahoraMovil)
            assertEquals("g-1", r.anuncio.grabacion)
            assertTrue(r.anuncio.activa)
            assertEquals(adelanto, r.adelanto)
            val pasada = SubidaALaGrabacion.pasar(con, cola, "id-pc", ::ahoraMovil)
            assertEquals("$pasada", 1, pasada.subidas)
            assertFalse(pasada.reintentar)
            // Y la misma conexión sigue al día: otra pregunta (el keepalive de la conexión caliente).
            aLas(15)
            assertEquals(15 * s, con.preguntar(::ahoraMovil).anuncio.msActual)
            con.adios()
        }
        assertEquals(12 * s, cola.todas().single().msListo)
        assertTrue(barra("nuevas").contains("\"ms\":12000"))
        // La vuelta del teléfono, en cambio, oye «ocupado» y vuelve luego (la automática ya ni la
        // empieza: lo oye por la baliza).
        val e = runCatching {
            Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
                val sesion = Sesion.conectar(so.getInputStream(), so.getOutputStream(), movil)
                try { sesion.catalogo() } finally { sesion.soltar() }
            }
        }.exceptionOrNull()
        assertEquals(Protocolo.OCUPADO, e?.message)
        // Al acabar de grabar, suelto: la vuelta va.
        assertEquals("ok", barra("soltar"))
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val sesion = Sesion.conectar(so.getInputStream(), so.getOutputStream(), movil)
            try { assertTrue(sesion.catalogo().isNotEmpty()); sesion.adios() } finally { sesion.soltar() }
        }
    }

    @Test
    fun `el telefono que graba oye ocupado para sincronizar pero recibe la captura y el aviso del PC`() {
        montar()
        aLas(0)
        val g = GrabacionesDelMovil(File(raiz, "cola-del-movil"), "Teléfono", movil.identidad.leer().yo.codigo).also { it.almacen = movil }
        g.empezar("m-1", "Clase del móvil", ahoraMovil())
        Protocolo.grabando(movil, true)
        val quien = object : QuienGraba {
            override fun anuncio() = g.anuncio(ahoraMovil())
            override fun aceptar(p: Protocolo.Peticion) = g.aceptar(p.grabacion, p.tomadaEn, p.mime.orEmpty(), p.bytes)
            override fun guardar(p: Protocolo.Peticion, fichero: File, otro: Aparato) =
                g.recibir(GrabacionesDelMovil.Foto.de(p), fichero, otro.id, otro.nombre, ahoraMovil())
        }
        // El PC sabe de la grabación del teléfono (`camara_remota` con `suya`, §13).
        aLas(20)
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val con = ConexionDeCamara.abrir(so.getInputStream(), so.getOutputStream(), movil.identidad.leer(), ahora = ::ahoraMovil)
            con.anunciarLaMia(g.anuncio(ahoraMovil())!!, ::ahoraMovil)
            con.adios()
        }
        // La vuelta del PC: «ocupado».
        val (fin, salida) = dirigeElPc()
        assertTrue("la vuelta del PC no acaba bien: $salida", fin != 0)
        assertTrue("oye el «ocupado» del teléfono: $salida", salida.contains(Protocolo.OCUPADO))
        // Pero su aviso de cámara entra (y mide el reloj del teléfono)…
        val (e1, h1) = atenderUnaVez(quien)
        val aviso = Json.parseToJsonElement(barra("avisar ${e1.localPort}")).jsonObject
        h1.join(10_000)
        assertEquals("$aviso", "true", aviso["sabe"]!!.jsonPrimitive.content)
        // …y su captura, en el minuto de la grabación del teléfono.
        aLas(35)
        val (e2, h2) = atenderUnaVez(quien)
        val png = File(raiz, "Captura.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + ByteArray(500) { 7 }) }
        val enviado = Json.parseToJsonElement(barra("al-movil ${e2.localPort} ${png.path}")).jsonObject
        h2.join(10_000)
        assertEquals("$enviado", 35 * s, enviado["ms"]!!.jsonPrimitive.long)
        assertEquals(1, g.tomarNuevas().size)
        // Acaba de grabar: la vuelta del PC va.
        Protocolo.grabando(movil, false)
        val (fin2, salida2) = dirigeElPc()
        assertEquals("la vuelta tras grabar: $salida2", 0, fin2)
    }

    @Test
    fun `solo se salta el ocupado suelto y a secas`() {
        assertTrue(ConexionDeCamara.esElOcupadoSuelto("""{"error":"${Protocolo.OCUPADO}"}"""))
        assertFalse(ConexionDeCamara.esElOcupadoSuelto("""{"error":"No sé qué es «camara_remota»"}"""))
        assertFalse(ConexionDeCamara.esElOcupadoSuelto("""{"t":"listo","ms":5}"""))
        assertFalse(ConexionDeCamara.esElOcupadoSuelto("""{"t":"camara_remota","grabacion":"g-1"}"""))
        assertFalse(ConexionDeCamara.esElOcupadoSuelto("no es json"))
    }

    companion object {
        const val CODIGO = "ABCDE23456"
    }
}
