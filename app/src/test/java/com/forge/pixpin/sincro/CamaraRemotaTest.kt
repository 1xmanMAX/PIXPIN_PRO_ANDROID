package com.forge.pixpin.sincro

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **El móvil como cámara de la reunión del PC, sin el PC** (28-oct-2026): el reloj de la grabación
 * con los mismos números que las pruebas de `crates/pixpin-sincro/src/camara.rs`, el cable del
 * anuncio, la cola, y el [Respondedor] de este teléfono atendiendo el aviso del PC. Contra el PC
 * de verdad: `CamaraDelPcTest`.
 */
class CamaraRemotaTest {

    private val s = 1000L
    private val pared = 1_761_550_000_000L

    // ------------------------------------------------------------ el reloj (camara.rs)

    @Test
    fun `el minuto no cuenta lo pausado`() {
        val r = RelojDeGrabacion.empezar(pared, 0)
        assertEquals(10 * s, r.msEn(pared + 10 * s))
        r.pausar(pared + 10 * s)
        assertTrue(r.enPausa)
        // En pausa el minuto no corre.
        assertEquals(10 * s, r.msEn(pared + 25 * s))
        r.seguir(pared + 30 * s)
        assertEquals(20 * s, r.msEn(pared + 40 * s))
        r.acabar(pared + 50 * s)
        // Tras acabar, lo que duró.
        assertEquals(30 * s, r.msEn(pared + 999 * s))
        assertEquals(30 * s, r.duracion(pared + 999 * s))
        // Antes de empezar, 0.
        assertEquals(0L, r.msEn(pared - s))
        assertEquals(listOf(listOf(pared + 10 * s, pared + 30 * s)), r.pausasAlCable())
    }

    @Test
    fun `seguir a la grabadora corrige la deriva y las pausas`() {
        val r = RelojDeGrabacion.empezar(pared, 2 * s)
        assertEquals(2 * s, r.msEn(pared))
        // Va 40 ms por detrás: se deja.
        r.seguirA(pared + 10 * s, 12 * s - 40, false, 250)
        assertEquals(12 * s, r.msEn(pared + 10 * s))
        // Va un segundo por detrás: se corrige.
        r.seguirA(pared + 20 * s, 21 * s, false, 250)
        assertEquals(21 * s, r.msEn(pared + 20 * s))
        r.seguirA(pared + 21 * s, 22 * s, true, 250)
        assertTrue(r.enPausa)
        assertEquals(22 * s, r.msEn(pared + 30 * s))
        r.seguirA(pared + 31 * s, 22 * s, false, 250)
        assertFalse(r.enPausa)
        assertEquals(23 * s, r.msEn(pared + 32 * s))
    }

    @Test
    fun `el desfase se mide como NTP`() {
        // El otro va 5 s adelantado; la ida y la vuelta, 100 ms cada una.
        assertEquals(5 * s, CamaraRemota.desfase(pared, pared + 100 + 5 * s, pared + 200))
        assertEquals(-7 * s, CamaraRemota.desfase(pared, pared + 100 - 7 * s, pared + 200))
        // Un t2 roto (antes que t0) no da un desfase absurdo.
        assertEquals(0L, CamaraRemota.desfase(pared, pared, pared - 50))
    }

    @Test
    fun `el movil graba en HEVC solo si el PC lo sabe ver`() {
        var a = CamaraRemota.Anuncio()
        assertNull(a.codecDelClip)
        assertFalse(a.conClips)
        a = a.copy(codecs = listOf(CamaraRemota.H264))
        assertEquals(CamaraRemota.H264, a.codecDelClip)
        a = a.copy(codecs = listOf(CamaraRemota.H264, CamaraRemota.HEVC))
        assertEquals(CamaraRemota.HEVC, a.codecDelClip)
        assertEquals(CamaraRemota.CLIP_MAXIMO_MS, a.clipMaximo)
    }

    @Test
    fun `el anuncio viaja como el del PC, sin lo que va por omision`() {
        val nada = CamaraRemota.Anuncio()
        assertEquals("""{"t":"camara_remota"}""", CamaraRemota.aTexto(nada))
        assertFalse(nada.grabando)
        val a = CamaraRemota.Anuncio(grabacion = "g1", titulo = "Clase", activa = true, enPausa = true, msActual = 754_000, pausas = listOf(listOf(5L, 0L)))
        // Byte a byte lo que escribe `serde` en `el_anuncio_viaja_sin_lo_que_va_por_omision`.
        assertEquals(
            """{"t":"camara_remota","grabacion":"g1","titulo":"Clase","activa":true,"en_pausa":true,"ms_actual":754000,"pausas":[[5,0]]}""",
            CamaraRemota.aTexto(a)
        )
        // Lo que no se conoce se ignora (un PC más nuevo).
        assertEquals("g1", CamaraRemota.deTexto("""{"t":"camara_remota","grabacion":"g1","nuevo":1}""").grabacion)
        // El «No sé qué es» de un PC de antes también se lee.
        assertEquals("No sé qué es «camara_remota»", CamaraRemota.deTexto("""{"error":"No sé qué es «camara_remota»"}""").error)
        // La pregunta del móvil.
        assertEquals("""{"t":"camara_remota","reloj":7}""", CamaraRemota.aTexto(CamaraRemota.Anuncio(reloj = 7)))
    }

    @Test
    fun `el suelto de una foto lleva lo de la camara y nada mas`() {
        val p = Protocolo.Peticion(
            "suelto", nombre = "IMG.jpg", mime = "image/jpeg", bytes = 10, destino = CamaraRemota.DESTINO,
            grabacion = "g-1", ms = 0, tomadaEn = 99, reloj = -7000, clave = "toma-1"
        )
        val t = Protocolo.json.encodeToString(Protocolo.Peticion.serializer(), p)
        assertEquals(
            """{"t":"suelto","bytes":10,"nombre":"IMG.jpg","mime":"image/jpeg","destino":"grabacion","grabacion":"g-1","ms":0,"tomada_en":99,"reloj":-7000,"clave":"toma-1"}""",
            t
        )
        // Un suelto de los de siempre no escribe nada de esto.
        val viejo = Protocolo.json.encodeToString(Protocolo.Peticion.serializer(), Protocolo.Peticion("suelto", nombre = "a.pdf", bytes = 3))
        assertFalse(viejo.contains("grabacion") || viejo.contains("ms") || viejo.contains("reloj") || viejo.contains("clave"))
        // El «listo» con su minuto.
        assertEquals(754_000L, Protocolo.json.decodeFromString(Protocolo.Respuesta.serializer(), """{"t":"listo","donde":"grabacion","ms":754000}""").ms)
    }

    @Test
    fun `el reloj del movil sigue al anuncio, congelado en pausa y al acabar`() {
        val a = CamaraRemota.Anuncio(grabacion = "g", activa = true, msActual = 10 * s)
        // Recibido a los 1000 s de su reloj, con 100 ms de ida: ya va por 10,1 s.
        var r = RelojDelMovil.de(a, 1000 * s, ida = 100)
        assertEquals(10 * s + 100, r.ms(1000 * s))
        assertEquals(15 * s + 100, r.ms(1005 * s))
        // En pausa: congelado.
        r = r.alDia(a.copy(enPausa = true, msActual = 15 * s), 1005 * s)
        assertTrue(r.enPausa)
        // 100 ms de diferencia con el PC: menos de la tolerancia, se dejan.
        assertEquals(15 * s + 100, r.ms(1030 * s))
        // Un segundo de diferencia: se corrige.
        r = r.alDia(a.copy(msActual = 14 * s), 1040 * s)
        assertEquals(16 * s, r.ms(1042 * s))
        // Acabó: lo que duró, para siempre.
        r = r.alDia(a.copy(activa = false, duracion = 20 * s), 1050 * s)
        assertTrue(r.acabado)
        assertEquals(20 * s, r.ms(9999 * s))
        // Otra grabación: otro reloj.
        r = r.alDia(CamaraRemota.Anuncio(grabacion = "g2", activa = true, msActual = s), 2000 * s)
        assertEquals("g2", r.grabacion)
        assertEquals(3 * s, r.ms(2002 * s))
        assertEquals("● 12:34 grabando en el PC", RelojDelMovil.rotulo(754_000, false, false))
        assertEquals("‖ 1:02:03 en pausa", RelojDelMovil.rotulo(3_723_000, true, false))
    }

    @Test
    fun `lo que el PC sabe ensenar se reconoce por sus primeros bytes`() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        assertTrue(CamaraRemota.esImagen(b(0xFF, 0xD8, 0xFF, 0xE1, 0, 0)))
        assertTrue(CamaraRemota.esImagen("\u0089PNG\r\n\u001a\n....".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(CamaraRemota.esImagen("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(CamaraRemota.esImagen("%PDF-1.7".toByteArray()))
        assertFalse(CamaraRemota.esImagen(ByteArray(0)))
        assertTrue(CamaraRemota.esMp4("\u0000\u0000\u0000\u0018ftypmp42\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        // Una HEIC lleva la misma caja: no es un clip, y hay que pasarla a JPEG.
        val heic = "\u0000\u0000\u0000\u0018ftypheic\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertFalse(CamaraRemota.esMp4(heic))
        assertTrue(CamaraRemota.esHeif(heic))
        assertFalse(CamaraRemota.esJpeg(heic))
        assertTrue(CamaraRemota.tipoAceptado("video/mp4"))
        assertFalse(CamaraRemota.tipoAceptado("image/heic"))
        assertTrue(CamaraRemota.esNoParaSiempre("Esa grabación ya no está en el PC"))
        assertTrue(CamaraRemota.esNoParaSiempre(CamaraRemota.SOLO_FOTOS_Y_CLIPS))
        assertFalse(CamaraRemota.esNoParaSiempre("La cámara del móvil está apagada en el PC"))
        assertEquals("0:12", CamaraRemota.minuto(12_345))
    }

    // ------------------------------------------------------------ la cola

    private lateinit var raiz: File

    @Before
    fun montar() { raiz = Files.createTempDirectory("camara-movil").toFile() }

    @After
    fun desmontar() { if (::movil.isInitialized) Protocolo.soltar(movil); raiz.deleteRecursively() }

    private fun toma(cola: ColaDeLaCamara, clave: String, creada: Long, mime: String = "image/jpeg"): ColaDeLaCamara.Toma {
        val f = cola.ficheroNuevo(if (mime == "video/mp4") "mp4" else "jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return ColaDeLaCamara.Toma(clave, f.name, "IMG_$clave.jpg", mime, "g-1", "id-pc", ms = creada, tomadaEn = creada, creada = creada)
    }

    @Test
    fun `la cola guarda las tomas en disco y sobrevive a otra instancia`() {
        val cola = ColaDeLaCamara(File(raiz, "sincro/camara-movil"))
        val antes = ColaDeLaCamara.cambios.value
        cola.poner(toma(cola, "b", 2))
        cola.poner(toma(cola, "a", 1))
        cola.poner(toma(cola, "c", 3, "video/mp4"))
        assertTrue(ColaDeLaCamara.cambios.value > antes)
        assertEquals(listOf("a", "b", "c"), cola.pendientes().map { it.clave })
        assertEquals("Subiendo 2 fotos y 1 clip al PC…", ColaDeLaCamara.aviso(cola.pendientes()))
        cola.lista("a", 754_000, 10)
        cola.negada("b", "Esa grabación ya no está en el PC", 10)
        cola.fallo("c", "Se cortó")
        val otra = ColaDeLaCamara(File(raiz, "sincro/camara-movil"))
        assertEquals(listOf("c"), otra.pendientes().map { it.clave })
        assertEquals(1, otra.pendientes().single().intentos)
        assertEquals(754_000L, otra.todas().first { it.clave == "a" }.msListo)
        assertEquals("Subiendo 1 clip al PC…", ColaDeLaCamara.aviso(otra.pendientes()))
        // Lo viejo se va con su fichero; lo pendiente, nunca.
        val fa = otra.fichero(otra.todas().first { it.clave == "a" })
        otra.limpiar(10 + 3 * 24 * 3600_000L)
        assertEquals(setOf("b", "c"), otra.todas().map { it.clave }.toSet())
        assertFalse(fa.exists())
        otra.limpiar(10 + 30 * 24 * 3600_000L)
        assertEquals(listOf("c"), otra.todas().map { it.clave })
    }

    @Test
    fun `la espera entre intentos es 2, 4, 8 s hasta un minuto`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..7).map { ColaDeLaCamara.espera(it) })
    }

    // ------------------------------------------------------------ el aviso del PC

    private lateinit var movil: Disco
    private val codigo = "ABCDE23456"

    private fun movilEnGrupo(): Disco {
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        movil.crearGrupo(codigo = codigo, ahora = 1_000L)
        return movil
    }

    /**
     * **Un PC de mentira avisa al móvil** (lo que hace `anunciar_camara` del PC): el `hola`, el
     * anuncio con su reloj, lo que manda [despues] y el `adios`. Devuelve las respuestas.
     */
    private fun avisar(
        anuncio: CamaraRemota.Anuncio, capacidades: List<String> = Protocolo.CAPACIDADES,
        alAviso: ((CamaraRemota.Anuncio, Aparato, Long) -> Unit)? = null, despues: List<String> = emptyList(),
        ahoraDelMovil: Long = 50_000L
    ): Pair<Protocolo.Hola, List<String>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            server.accept().use { s ->
                Respondedor(movil, ahora = { ahoraDelMovil }, capacidades = capacidades, alAvisoDeCamara = alAviso).atender(s.getInputStream(), s.getOutputStream())
            }
            server.close()
        }
        hilo.start()
        val respuestas = ArrayList<String>()
        lateinit var suHola: Protocolo.Hola
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val canal = Canal.abrir(s.getInputStream(), s.getOutputStream(), Grupo.clave(codigo), inicia = true)
            val pc = Aparato("id-pc", "Portátil")
            Protocolo.enviar(canal, Protocolo.Peticion("hola", hola = Protocolo.Hola(pc, reloj = 1, capacidades = listOf(Protocolo.DIARIO, Protocolo.REANUDAR, CamaraRemota.CAPACIDAD))))
            suHola = Protocolo.leerRespuesta(canal).hola!!
            for (texto in listOf(CamaraRemota.aTexto(anuncio)) + despues) {
                canal.enviar(Protocolo.JSON_, texto.toByteArray())
                respuestas += canal.recibir().second.decodeToString()
            }
            Protocolo.enviar(canal, Protocolo.Peticion("adios"))
            respuestas += canal.recibir().second.decodeToString()
        }
        hilo.join(5000)
        return suHola to respuestas
    }

    @Test
    fun `el PC avisa al movil, que contesta con su reloj y se lo pasa a quien lleva la camara`() {
        movilEnGrupo()
        val vistos = ArrayList<Triple<CamaraRemota.Anuncio, Aparato, Long>>()
        val anuncio = CamaraRemota.Anuncio(grabacion = "g-2", titulo = "Reunión", aparato = "Portátil", activa = true, msActual = 30_000, desde = 1_761_550_000_000, pausas = listOf(listOf(1L, 2L)), reloj = 43_000, codecs = listOf("h264"))
        val (hola, r) = avisar(anuncio, alAviso = { a, o, t -> vistos += Triple(a, o, t) })
        // El teléfono dice `camara1` en su saludo: el PC solo avisa a quien lo dice.
        assertTrue(CamaraRemota.CAPACIDAD in hola.capacidades)
        assertEquals("""{"t":"camara_remota","reloj":50000}""", r[0])
        // NTP en el PC: t0 = 43 000, t1 = 50 000, t2 = 43 000 → el móvil va 7 s adelantado.
        assertEquals(7_000L, CamaraRemota.desfase(43_000, CamaraRemota.deTexto(r[0]).reloj, 43_000))
        assertEquals("{}", r.last())
        val (a, de, cuando) = vistos.single()
        assertEquals("g-2", a.grabacion)
        assertEquals(30_000L, a.msActual)
        assertEquals("id-pc", de.id)
        assertEquals(50_000L, cuando)
    }

    @Test
    fun `un tipo desconocido se contesta No se que es y la conversacion sigue`() {
        movilEnGrupo()
        val (_, r) = avisar(
            CamaraRemota.Anuncio(grabacion = "g", activa = true),
            despues = listOf("""{"t":"algo_del_futuro","x":1}""", CamaraRemota.aTexto(CamaraRemota.Anuncio(reloj = 5)))
        )
        assertEquals("""{"error":"No sé qué es «algo_del_futuro»"}""", r[1])
        // Y después sigue contestando como si nada.
        assertTrue(r[2].contains("\"reloj\":50000"))
        assertEquals("{}", r.last())
    }

    @Test
    fun `un telefono de antes contesta No se que es al aviso y sigue sano`() {
        movilEnGrupo()
        var llamado = false
        val (hola, r) = avisar(CamaraRemota.Anuncio(grabacion = "g", activa = true), capacidades = emptyList(), alAviso = { _, _, _ -> llamado = true })
        assertFalse(CamaraRemota.CAPACIDAD in hola.capacidades)
        assertEquals("""{"error":"No sé qué es «camara_remota»"}""", r[0])
        assertEquals("{}", r.last())
        assertFalse(llamado)
    }
}
