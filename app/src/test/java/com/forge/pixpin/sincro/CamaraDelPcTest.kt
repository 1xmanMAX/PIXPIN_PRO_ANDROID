package com.forge.pixpin.sincro

import java.io.BufferedReader
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **El móvil como cámara de la reunión que graba el PC, contra el PC de verdad** (28-oct-2026).
 *
 * El «PC» es `herramientas/pc-simulado` con la orden `camara`: el `Respondedor` de Rust con
 * `Camaras` y `Recepcion` de `pixpin-proyecto` y `camara1` en su saludo, con su reloj movido por la
 * prueba. El teléfono es el código de aquí: [ConexionDeCamara], [RelojDelMovil], [ColaDeLaCamara],
 * [SubidaALaGrabacion] y el [Respondedor]. Es `crates/pixpin-proyecto/tests/movil_como_camara.rs`
 * del PC con el móvil de verdad en vez del de mentira: un móvil 7 s adelantado hace dos fotos (una
 * en pausa) y un clip durante una grabación con una pausa, un clip cortado a medias sigue desde
 * `desdeByte`, el PC acaba y todo entra en la clase en su minuto; luego una tarde.
 *
 * Solo corre con `PIXPIN_PC_SIMULADO=<ruta del programa>` (ver `herramientas/pc-simulado`).
 */
class CamaraDelPcTest {

    private val programa = System.getenv("PIXPIN_PC_SIMULADO")
    private lateinit var raiz: File
    private lateinit var pc: File
    private lateinit var movil: Disco
    private lateinit var cola: ColaDeLaCamara
    private var proceso: Process? = null
    private var deLaEntrada: BufferedReader? = null
    private var puerto = 0

    private val s = 1000L
    private val pared = 1_761_550_000_000L
    /** Lo que va adelantado el reloj del móvil. */
    private val adelanto = 7 * s
    @Volatile private var relojDelPc = pared

    private fun ahoraMovil() = relojDelPc + adelanto

    private fun montar(conCamara: Boolean) {
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        raiz = Files.createTempDirectory("pc-camara").toFile()
        pc = File(raiz, "PixPin Max")
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        cola = ColaDeLaCamara.de(movil.filesDir)
        orden("preparar", pc.path, CODIGO)
        val archivoDelPuerto = File(raiz, "puerto")
        val p = ProcessBuilder(programa, if (conCamara) "camara" else "responder", pc.path, archivoDelPuerto.path)
            .redirectError(File(raiz, "pc.log")).start()
        proceso = p
        if (conCamara) deLaEntrada = p.inputStream.bufferedReader() else p.inputStream.close()
        val hasta = System.currentTimeMillis() + 10_000
        var leido: Int? = null
        while (leido == null && System.currentTimeMillis() < hasta) {
            leido = archivoDelPuerto.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (leido == null) Thread.sleep(50)
        }
        puerto = leido ?: error("El PC simulado no dijo su puerto: " + File(raiz, "pc.log").takeIf { it.isFile }?.readText().orEmpty())
        // El teléfono se une al grupo del PC, como en la pantalla de Sincronizar.
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val sesion = Sesion.conectar(so.getInputStream(), so.getOutputStream(), movil, true, CODIGO)
            try { sesion.adios() } finally { sesion.soltar() }
        }
        assertTrue(movil.identidad.leer().enGrupo)
    }

    @After
    fun desmontar() {
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

    /** Una orden a la barra de «Grabar clase» del PC, y lo que contesta. */
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

    private fun conectar(salida: (OutputStream) -> OutputStream = { it }, hacer: (Socket, OutputStream) -> Unit) {
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so -> hacer(so, salida(so.getOutputStream())) }
    }

    private fun abrir(so: Socket, out: OutputStream) =
        ConexionDeCamara.abrir(so.getInputStream(), out, movil.identidad.leer(), ahora = ::ahoraMovil)

    /** Una toma en la cola, como la deja la pantalla de cámara en el disparo. */
    private fun toma(nombre: String, bytes: ByteArray, ms: Long, duracionMs: Long = 0, grabacion: String = "g-1", reloj: Long = adelanto, tomadaEn: Long = ahoraMovil()): ColaDeLaCamara.Toma {
        val clip = nombre.endsWith(".mp4")
        val f = cola.ficheroNuevo(if (clip) "mp4" else "jpg").apply { writeBytes(bytes) }
        val t = ColaDeLaCamara.Toma(
            clave = "toma-$nombre-${System.nanoTime()}", fichero = f.name, nombre = nombre, mime = AlGrupo.mimeDe(nombre),
            grabacion = grabacion, pc = "id-pc", ms = ms, tomadaEn = tomadaEn, reloj = reloj, duracionMs = duracionMs,
            creada = System.nanoTime()
        )
        cola.poner(t)
        return t
    }

    /** Una foto JPEG pequeña con su EXIF (APP1): tiene que llegar tal cual. */
    private fun jpeg(semilla: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10))
        b.write("Exif\u0000\u0000MM\u0000*\u0000\u0000\u0000\u0008".toByteArray(Charsets.ISO_8859_1))
        for (i in 0 until 3000) b.write((i * semilla) and 0xff)
        b.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return b.toByteArray()
    }

    private fun mp4(extra: Int = 5000): ByteArray =
        "\u0000\u0000\u0000\u0018ftypmp42\u0000\u0000\u0000\u0000mp42isom".toByteArray(Charsets.ISO_8859_1) + ByteArray(extra) { ((it % 253) + 1).toByte() }

    /** La huella del PC simulado (FNV-1a de 64 bits). */
    private fun huella(b: ByteArray): String {
        var h = -0x340d631b7bdddcdbL
        for (x in b) { h = h xor (x.toLong() and 0xff); h *= 0x100000001b3L }
        return "%016x".format(h)
    }

    /** Corta la conexión a los [tope] bytes, como una Wi-Fi que se va a mitad de un clip. */
    private class Cortar(out: OutputStream, private val tope: Long, private val alCortar: () -> Unit) : FilterOutputStream(out) {
        var llevados = 0L
        override fun write(b: ByteArray, off: Int, len: Int) {
            val caben = (tope - llevados).coerceAtLeast(0).coerceAtMost(len.toLong()).toInt()
            if (caben > 0) { out.write(b, off, caben); llevados += caben }
            if (caben < len) { out.flush(); alCortar(); throw IOException("La Wi-Fi se fue") }
        }
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    }

    private fun mensajesDe(chat: String): List<JsonObject> {
        val v = Json.parseToJsonElement(orden("ver", pc.path).trim().lines().last()).jsonObject
        return v["chats"]!!.jsonArray.map { it.jsonObject }.first { it["nombre"]!!.jsonPrimitive.content == chat }["mensajes"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `el movil hace de camara del PC con pausa, reloj adelantado, un clip cortado y una foto tarde`() {
        montar(conCamara = true)
        aLas(0)
        assertEquals("ok", barra("codecs h264,hevc"))
        assertEquals("ok", barra("empezar g-1 Clase 28-10 10h05"))
        aLas(10)
        assertEquals("ok", barra("seguir 10000 0"))

        // --- El PC avisa al teléfono (al empezar) y mide su reloj ---------------------------
        val avisos = java.util.Collections.synchronizedList(ArrayList<CamaraRemota.Anuncio>())
        val escucha = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            escucha.accept().use { so ->
                Respondedor(movil, ahora = ::ahoraMovil, alAvisoDeCamara = { a, _, _ -> avisos += a }).atender(so.getInputStream(), so.getOutputStream())
            }
            escucha.close()
        }
        hilo.start()
        val aviso = Json.parseToJsonElement(barra("avisar ${escucha.localPort}")).jsonObject
        hilo.join(5000)
        assertEquals("el PC mide el reloj del teléfono: $aviso", 7000L, aviso["desfase"]!!.jsonPrimitive.long)
        assertEquals("g-1", avisos.single().grabacion)
        assertEquals(10 * s, avisos.single().msActual)

        lateinit var reloj: RelojDelMovil
        conectar { so, out ->
            val con = abrir(so, out)
            assertTrue("el PC dice camara1 en el hola", con.sabeDeCamara)
            // --- A los 10 s el móvil pregunta y empieza su reloj ----------------------------
            val r = con.preguntar(::ahoraMovil)
            assertTrue(r.anuncio.grabando)
            assertEquals("Clase 28-10 10h05", r.anuncio.titulo)
            assertEquals("Portátil", r.anuncio.aparato)
            assertEquals(10 * s, r.anuncio.msActual)
            assertEquals(CamaraRemota.HEVC, r.anuncio.codecDelClip)
            assertEquals(CamaraRemota.CLIP_MAXIMO_MS, r.anuncio.clipMaximoMs)
            assertEquals("el desfase, medido como NTP", adelanto, r.adelanto)
            reloj = RelojDelMovil.de(r.anuncio, ahoraMovil(), r.ida)

            // --- A los 12 s, la primera foto, con su minuto del móvil ----------------------
            aLas(12)
            toma("IMG_0001.jpg", jpeg(3), reloj.ms(ahoraMovil()))
            // --- De los 20 a los 50 s, en pausa; a los 30, una foto en la pausa -------------
            aLas(20)
            assertEquals("ok", barra("seguir 20000 1"))
            aLas(25)
            val p = con.preguntar(::ahoraMovil)
            assertTrue(p.anuncio.enPausa)
            reloj = reloj.alDia(p.anuncio, ahoraMovil(), p.ida)
            aLas(30)
            assertEquals("congelado en la pausa", 20 * s, reloj.ms(ahoraMovil()))
            toma("IMG_0002.jpg", jpeg(5), reloj.ms(ahoraMovil()))
            aLas(50)
            assertEquals("ok", barra("seguir 20000 0"))
            val q = con.preguntar(::ahoraMovil)
            assertFalse(q.anuncio.enPausa)
            reloj = reloj.alDia(q.anuncio, ahoraMovil(), q.ida)
            // --- Un clip de los 52 a los 57 s: su minuto es donde empezó ----------------------
            aLas(52)
            val empezo = reloj.ms(ahoraMovil())
            assertEquals(22 * s, empezo)
            aLas(57)
            toma("VID_0001.mp4", mp4(), empezo, duracionMs = 5 * s)
            // Caso negativo: de una grabación que el PC no tiene, y sin su hora (con ella, desde el PC
            // de 995fd94b, entraría por su hora: §12.4). Se niega para siempre y la conexión sigue sana.
            toma("IMG_9.jpg", jpeg(9), 1, grabacion = "no-existe", tomadaEn = 0)

            val pasada = SubidaALaGrabacion.pasar(con, cola, "id-pc", ::ahoraMovil)
            assertEquals(3, pasada.subidas)
            assertEquals(1, pasada.negadas)
            assertFalse(pasada.reintentar)
            val porNombre = cola.todas().associateBy { it.nombre }
            assertEquals(12 * s, porNombre.getValue("IMG_0001.jpg").msListo)
            assertEquals(20 * s, porNombre.getValue("IMG_0002.jpg").msListo)
            assertEquals(22 * s, porNombre.getValue("VID_0001.mp4").msListo)
            assertEquals(ColaDeLaCamara.NEGADA, porNombre.getValue("IMG_9.jpg").estado)
            assertTrue(porNombre.getValue("IMG_9.jpg").error!!.contains("ya no está"))
            // Sigue hablando.
            assertTrue(con.preguntar(::ahoraMovil).anuncio.grabando)
            con.adios()
        }
        // Grabando, todo espera en la cola del PC: aún no hay clase donde ponerlo.
        val nuevas = Json.parseToJsonElement(barra("nuevas")).jsonArray.map { it.jsonObject["ms"]!!.jsonPrimitive.long to it.jsonObject["clip"]!!.jsonPrimitive.content }
        assertEquals(listOf(12 * s to "false", 20 * s to "false", 22 * s to "true"), nuevas)

        // --- De los 60 a los 75 s un clip de 3 MB: la Wi-Fi se va a mitad y luego sigue desde ahí --
        aLas(60)
        val empiezaEn = reloj.ms(ahoraMovil())
        aLas(75)
        val grande = mp4(3_000_000)
        val cortado = toma("VID_0002.mp4", grande, empiezaEn, duracionMs = 15 * s)
        assertEquals(30 * s, cortado.ms)
        // Lo que el PC ya dijo de conexiones que acabaron mal (se cuenta antes del corte).
        fun cortesDelPc() = runCatching { Regex("UnexpectedEof|ConnectionReset|BrokenPipe").findAll(File(raiz, "pc.log").readText()).count() }.getOrDefault(0)
        val cortesAntes = cortesDelPc()
        conectar({ Cortar(it, 2_500_000) {} }) { so, out ->
            val p = SubidaALaGrabacion.subir(so.getInputStream(), out, movil.identidad.leer(), cola, "id-pc", ahora = ::ahoraMovil)
            assertTrue("se cortó: a reintentar", p.reintentar)
            assertEquals(0, p.subidas)
        }
        val trasElCorte = cola.pendientes().single()
        assertEquals(cortado.clave, trasElCorte.clave)
        assertEquals(1, trasElCorte.intentos)
        // El PC guarda lo llegado en su `.parcial` al ver el corte (`camara::parcial`, en la carpeta
        // temporal del PC): se espera a que **el PC haya acabado con la conexión cortada** (lo apunta en su
        // registro) y a que el `.parcial` esté y no crezca. Solo mirar que no crezca no basta: el PC escribe
        // con un búfer de 1 MB y, con la máquina cargada, el `.parcial` se queda quieto en 1 MB mientras aún
        // lee lo que quedaba en el socket; el reintento seguía desde ahí y lo de la conexión vieja llegaba
        // después, detrás (29-oct-2026: el clip salía con 1 MB de más). Un teléfono de verdad reintenta
        // segundos después; aquí se espera a lo mismo, sin adivinar tiempos.
        val hastaQueAcabe = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < hastaQueAcabe && cortesDelPc() <= cortesAntes) Thread.sleep(100)
        assertTrue("el PC no apuntó el corte: " + File(raiz, "pc.log").readText().takeLast(2000), cortesDelPc() > cortesAntes)
        val limpia = cortado.clave.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }.take(80).joinToString("")
        val parcial = File(System.getenv("TMPDIR") ?: "/tmp", "pixpin-suelto/parciales/$limpia-${grande.size}.parcial")
        var antes = -1L
        val hasta = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < hasta && !(parcial.length() >= (1 shl 20) && parcial.length() == antes)) {
            antes = parcial.length()
            Thread.sleep(200)
        }
        assertTrue("lo llegado espera en ${parcial.path}: ${parcial.length()}", parcial.length() >= (1 shl 20))
        // El PC puede tardar un momento en soltar la conexión cortada y contestar «ocupado»: la
        // subida de verdad reintenta, y aquí igual (hasta 10 s).
        var segundaVez = 0L
        var subidas = 0
        var ultima = ""
        val hastaQueSuelte = System.currentTimeMillis() + 10_000
        while (subidas == 0 && System.currentTimeMillis() < hastaQueSuelte) {
            conectar({ o -> object : FilterOutputStream(o) {
                override fun write(b: ByteArray, off: Int, len: Int) { segundaVez += len; out.write(b, off, len) }
            } }) { so, out ->
                val p = SubidaALaGrabacion.subir(so.getInputStream(), out, movil.identidad.leer(), cola, "id-pc", ahora = ::ahoraMovil)
                subidas = p.subidas
                ultima = "$p"
            }
            if (subidas == 0) Thread.sleep(300)
        }
        assertEquals("$ultima · ${cola.todas().first { it.clave == cortado.clave }} · " + File(raiz, "pc.log").readText(), 1, subidas)
        // Lo normal es que siga desde el `.parcial` (menos de lo que falta + 1 MB). Con la máquina muy
        // cargada el PC puede tirar el `.parcial` de la conexión cortada antes del reintento
        // (`al_lienzo.rs`: un corte que no ve como corte borra lo llegado) y entonces va entero: el clip
        // llega igual, en su minuto, una sola vez. Lo que no puede pasar es que vaya más de una vez.
        assertTrue("lo mandado: $segundaVez de ${grande.size}", segundaVez < grande.size - (1 shl 20) || segundaVez < 2L * grande.size)
        assertEquals(30 * s, cola.todas().first { it.clave == cortado.clave }.msListo)

        // --- A los 80 s, «Terminar»: la clase se guarda y entra todo ------------------------------
        aLas(80)
        val terminada = Json.parseToJsonElement(barra("terminar 50000")).jsonObject
        assertEquals(4L, terminada["hechas"]!!.jsonPrimitive.long)
        val voz = terminada["voz"]!!.jsonPrimitive.content

        // --- A los 300 s, una foto que el móvil subió tarde (sin red entonces) ----------------
        aLas(300)
        conectar { so, out ->
            val con = abrir(so, out)
            val r = con.preguntar(::ahoraMovil)
            assertFalse("ya acabó", r.anuncio.activa)
            assertEquals("g-1", r.anuncio.grabacion)
            assertEquals(50 * s, r.anuncio.duracion)
            reloj = reloj.alDia(r.anuncio, ahoraMovil(), r.ida)
            assertTrue(reloj.acabado)
            assertEquals(50 * s, reloj.ms(ahoraMovil()))
            toma("IMG_0003.jpg", jpeg(7), 45 * s)
            assertEquals(1, SubidaALaGrabacion.pasar(con, cola, "id-pc", ::ahoraMovil).subidas)
            con.adios()
        }
        assertFalse(cola.hayPendientes())

        // --- Lo que quedó en la clase, visto con los ojos del PC ------------------------------
        val mensajes = mensajesDe("Reunion")
        val delMovil = mensajes.filter { it["resto"]?.jsonObject?.get("fuente")?.jsonPrimitive?.content == "movil" }
        assertEquals(5, delMovil.size)
        val vistos = delMovil.map { m ->
            val g = m["resto"]!!.jsonObject["enGrabacion"]!!.jsonObject
            assertEquals(voz, g["de"]!!.jsonPrimitive.content)
            Triple(g["ms"]!!.jsonPrimitive.long, g["duracionMs"]?.jsonPrimitive?.longOrNull, m["clase"]!!.jsonPrimitive.content)
        }.sortedBy { it.first }
        assertEquals(
            listOf(
                Triple(12 * s, null, "Imagen"), Triple(20 * s, null, "Imagen"), Triple(22 * s, 5 * s, "Archivo"),
                Triple(30 * s, 15 * s, "Archivo"), Triple(45 * s, null, "Imagen")
            ),
            vistos
        )
        val primera = delMovil.first { it["nombre"]!!.jsonPrimitive.content == "IMG_0001.jpg" }
        assertEquals("los bytes tal cual, con su EXIF", huella(jpeg(3)), primera["huella"]!!.jsonPrimitive.content)
        assertTrue(primera["texto"]!!.jsonPrimitive.content, primera["texto"]!!.jsonPrimitive.content.startsWith("📱 ⏱ 0:12"))
        assertEquals("Teléfono", primera["resto"]!!.jsonObject["deAparato"]!!.jsonPrimitive.content)
        assertEquals(pared + 12 * s + adelanto, primera["resto"]!!.jsonObject["tomadaEn"]!!.jsonPrimitive.long)
        val clipCortado = delMovil.first { it["nombre"]!!.jsonPrimitive.content == "VID_0002.mp4" }
        assertEquals("el clip cortado llega entero, sin repetir nada", huella(grande), clipCortado["huella"]!!.jsonPrimitive.content)
        assertTrue(clipCortado["texto"]!!.jsonPrimitive.content.startsWith("📱 ▶ 0:30"))
        // En la nota: las capturas y los clips, en orden.
        val nota = mensajes.first { it["id"]!!.jsonPrimitive.content == voz }["resto"]!!.jsonObject
        assertEquals(listOf(12 * s, 20 * s, 45 * s), nota["capturas"]!!.jsonArray.map { it.jsonObject["ms"]!!.jsonPrimitive.long })
        assertEquals(
            listOf(22 * s to 5 * s, 30 * s to 15 * s),
            nota["clips"]!!.jsonArray.map { it.jsonObject["ms"]!!.jsonPrimitive.long to it.jsonObject["duracionMs"]!!.jsonPrimitive.long }
        )
        // Y en su carpeta, en el orden de la grabación.
        val carpeta = mensajes.first { it["id"]!!.jsonPrimitive.content == terminada["carpeta"]!!.jsonPrimitive.content }["carpeta"]!!.jsonObject
        assertEquals(listOf(0L, 12 * s, 20 * s, 22 * s, 30 * s, 45 * s), carpeta["minutos"]!!.jsonArray.map { it.jsonPrimitive.long })
    }

    /** **Caso negativo: un PC de antes** no sabe de la cámara: «No sé qué es» y la conexión sigue sana. */
    @Test
    fun `un PC sin camara contesta No se que es, no sube nada y sigue sano`() {
        montar(conCamara = false)
        toma("IMG_1.jpg", jpeg(1), 1000)
        conectar { so, out ->
            val con = abrir(so, out)
            assertFalse(con.sabeDeCamara)
            val e = runCatching { con.preguntar(::ahoraMovil) }.exceptionOrNull()
            assertTrue("$e", e is Protocolo.ErrorDelOtro && e.message!!.contains("camara_remota"))
            // Sin `camara1`, ni se intenta: sigue pendiente para otro PC o para cuando se actualice.
            val p = SubidaALaGrabacion.pasar(con, cola, "id-pc", ::ahoraMovil)
            assertTrue(p.reintentar)
            assertEquals(0, p.subidas)
            assertTrue(cola.hayPendientes())
            // Mandándola igual, el «no» llega antes de los trozos y la conversación sigue.
            val t = cola.pendientes().single()
            val e2 = runCatching { con.mandar(cola.fichero(t), t) }.exceptionOrNull()
            assertTrue("$e2", e2 is Protocolo.ErrorDelOtro && e2.message!!.startsWith("No sé qué es"))
            assertTrue(runCatching { con.preguntar(::ahoraMovil) }.exceptionOrNull() is Protocolo.ErrorDelOtro)
            con.adios()
        }
    }

    private companion object {
        const val CODIGO = "ABCDE23456"
    }
}
