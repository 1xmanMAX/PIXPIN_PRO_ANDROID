package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.GrabacionAMedias.Diario
import com.forge.pixpin.guardados.GrabacionAMedias.Elemento
import com.forge.pixpin.guardados.GrabacionAMedias.Espacio
import com.forge.pixpin.guardados.GrabacionAMedias.EstadoMp4
import com.forge.pixpin.guardados.GrabacionAMedias.Marcador
import com.forge.pixpin.guardados.GrabacionAMedias.Nota
import com.forge.pixpin.guardados.GrabacionAMedias.Tipo
import com.forge.pixpin.guardados.GrabacionAMedias.Video
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * **El diario de una grabación a medias** (28-oct-2026, «Grabar sin perder nada»): las pruebas de
 * `grabacion_a_medias.rs` y de `pixpin_sistema::mp4` del PC, con sus mismos números, y el JSON
 * campo por campo con el que escribe el PC. Lo que escribe con `AtomicFile` va en
 * [GrabacionAMediasEnDiscoTest] (Robolectric).
 */
class GrabacionAMediasTest {

    private val T0 = 1_761_645_900_000L
    private val raiz: File = Files.createTempDirectory("pixpin-a-medias").toFile()

    @After
    fun limpiar() {
        raiz.deleteRecursively()
    }

    private fun caja(tipo: String, cuerpo: Int): ByteArray {
        val b = ByteBuffer.allocate(cuerpo + 8)
        b.putInt(cuerpo + 8)
        b.put(tipo.toByteArray(Charsets.ISO_8859_1))
        return b.array()
    }

    private fun mp4Cerrado() = caja("ftyp", 8) + caja("mdat", 64) + caja("moov", 16)
    private fun mp4Cortado() = (caja("ftyp", 8) + caja("mdat", 640)).copyOf(200)

    // --- El JSON, campo por campo con el PC ---------------------------------------------------

    /** Lo que escribe `serde_json::to_vec_pretty` del PC para un diario. */
    private val delPc = """
        {
          "version": 1,
          "marca": "1761645900000",
          "proyecto": "p1",
          "nombre_proyecto": "Física",
          "nombre": "Clase 28-10 10h05",
          "modo": "presencial",
          "microfono": true,
          "equipo": false,
          "audio": "/raiz/voz-1.m4a.parcial",
          "video": null,
          "reloj": {
            "inicio": 1761645900000,
            "fin": null,
            "pausas": [
              {
                "desde": 1761646500000,
                "hasta": 1761646620000
              }
            ],
            "huecos": [
              {
                "desde": 1761646920000,
                "hasta": 1761648720000
              }
            ]
          },
          "latido": {
            "utc": 1761648722000,
            "ms": 902000,
            "pausa": false
          },
          "capturas": [
            {
              "ms": 61000,
              "fichero": "captura-1.png",
              "de_camara": false
            },
            {
              "ms": 2000,
              "fichero": "captura-2.jpg",
              "de_camara": true
            }
          ],
          "clips": [
            {
              "ms": 90000,
              "ruta": "/raiz/clip-1.mp4",
              "duracion_ms": 12345
            },
            {
              "ms": 95000,
              "ruta": "/raiz/clip-2.mp4",
              "duracion_ms": null
            }
          ],
          "marcadores": [
            {
              "ms": 5000,
              "texto": "importante"
            }
          ],
          "movil": "g-123",
          "solo_la_nota": false
        }
    """.trimIndent()

    @Test
    fun `el diario del PC se lee campo por campo`() {
        val d = Diario.deJson(delPc)
        assertEquals(1, d.version)
        assertEquals("1761645900000", d.marca)
        assertEquals("p1", d.proyecto)
        assertEquals("Física", d.nombreProyecto)
        assertEquals("Clase 28-10 10h05", d.nombre)
        assertEquals("presencial", d.modo)
        assertTrue(d.microfono)
        assertFalse(d.equipo)
        assertEquals("/raiz/voz-1.m4a.parcial", d.audio)
        assertNull(d.video)
        assertEquals(T0, d.reloj.inicio)
        assertNull(d.reloj.fin)
        assertEquals(listOf(GrabacionAMedias.Tramo(1_761_646_500_000, 1_761_646_620_000)), d.reloj.pausas)
        assertEquals(1, d.reloj.huecos.size)
        assertEquals(GrabacionAMedias.Latido(1_761_648_722_000, 902_000, false), d.latido)
        assertEquals(GrabacionAMedias.CapturaApuntada(2_000, "captura-2.jpg", true), d.capturas[1])
        assertEquals(12_345L, d.clips[0].duracionMs)
        assertNull(d.clips[1].duracionMs)
        assertEquals(Marcador(5_000, "importante"), d.marcadores.single())
        assertEquals("g-123", d.movil)
        assertFalse(d.soloLaNota)
        // Lo del teléfono, vacío en el del PC.
        assertTrue(d.trozos.isEmpty())
        assertTrue(d.capturasDelVideo.isEmpty())
    }

    @Test
    fun `el diario de aqui lleva las claves del PC en su orden y vuelve entero`() {
        val d = Diario.deJson(delPc)
        val escrito = d.aJson()
        val o = Json.parseToJsonElement(escrito).jsonObject
        val delPcClaves = Json.parseToJsonElement(delPc).jsonObject.keys.toList()
        // Las del PC primero y en su orden; detrás, solo las del teléfono.
        assertEquals(delPcClaves, o.keys.toList().take(delPcClaves.size))
        assertEquals(listOf("trozos", "capturas_del_video"), o.keys.toList().drop(delPcClaves.size))
        // Los nulos se escriben (como serde), no se quitan.
        assertEquals(JsonNull, o["video"])
        assertEquals(JsonNull, o["reloj"]!!.jsonObject["fin"])
        assertEquals(listOf("inicio", "fin", "pausas", "huecos"), o["reloj"]!!.jsonObject.keys.toList())
        assertEquals(listOf("ms", "fichero", "de_camara"), Json.parseToJsonElement(escrito).jsonObject["capturas"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject.keys.toList() })
        // Ida y vuelta: lo mismo.
        assertEquals(d, Diario.deJson(escrito))
        // Y cada valor del PC sigue igual en lo escrito aquí.
        val pc = Json.parseToJsonElement(delPc).jsonObject
        for (k in delPcClaves) assertEquals(k, pc[k], o[k])
    }

    @Test
    fun `un diario nuevo es el del PC`() {
        val d = Diario.nuevo(T0, "p", "Clase", "/a/voz.m4a")
        assertEquals(GrabacionAMedias.VERSION, d.version)
        assertEquals(T0.toString(), d.marca)
        assertEquals(T0, d.reloj.inicio)
        assertEquals(GrabacionAMedias.Latido(T0, 0, false), d.latido)
        assertEquals(File(raiz, "grabaciones-a-medias/$T0"), d.carpeta(raiz))
        // Lo que no se entiende del JSON no rompe la lectura (un campo nuevo del futuro).
        val conDeMas = d.aJson().replaceFirst("{", "{\"algo_nuevo\": 7,")
        assertEquals(d, Diario.deJson(conDeMas))
    }

    // --- El reloj de pared -------------------------------------------------------------------

    @Test
    fun `el reloj lleva pausas y huecos y pasa la hora a minuto`() {
        val d = Diario.nuevo(T0, "p", "Clase", "a")
        // Diez minutos grabando.
        assertFalse(d.latir(T0 + 600_000, 600_000, false))
        // Pausa de dos minutos.
        assertTrue(d.latir(T0 + 600_000, 600_000, true))
        assertFalse(d.latir(T0 + 660_000, 600_000, true))
        assertTrue(d.latir(T0 + 720_000, 600_000, false))
        // Cinco minutos más, y el teléfono se duerme 30 minutos.
        d.latir(T0 + 1_020_000, 900_000, false)
        assertTrue(d.latir(T0 + 2_820_000 + 2_000, 902_000, false))
        assertEquals(1, d.reloj.pausas.size)
        assertEquals(1, d.reloj.huecos.size)
        assertEquals(T0 + 2_822_000, d.reloj.huecos[0].hasta)

        // Antes de la pausa, la hora es el minuto.
        assertEquals(300_000L, d.msEn(T0 + 300_000))
        // En la pausa, el minuto no corre.
        assertEquals(600_000L, d.msEn(T0 + 690_000))
        // Después de la pausa y del hueco, se descuentan los dos.
        assertEquals(902_000L, d.msEn(T0 + 2_822_000))
        assertEquals(962_000L, d.msEn(T0 + 2_882_000))
        // Caso negativo: antes de empezar no hay minutos negativos.
        assertEquals(0L, d.msEn(T0 - 50_000))

        // Un atasco de pocos segundos no es un hueco.
        val e = Diario.nuevo(T0, "p", "Clase", "a")
        assertFalse(e.latir(T0 + 8_000, 1_000, false))
        assertTrue(e.reloj.huecos.isEmpty())

        // Acabado, la hora de después cae al final.
        d.acabar(T0 + 3_000_000)
        assertEquals(d.msEn(T0 + 3_000_000), d.msEn(T0 + 9_000_000))
    }

    @Test
    fun `la cuenta de los huecos es la del reloj que va en la nota`() {
        val d = Diario.nuevo(T0, "p", "Clase", "a")
        d.latir(T0 + 60_000, 60_000, false)
        // La app estuvo congelada 20 s más de lo que grabó la nota: un hueco de 20 s.
        assertTrue(d.latir(T0 + 120_000, 100_000, false))
        val h = d.reloj.huecos.single()
        assertEquals(T0 + 100_000, h.desde)
        assertEquals(T0 + 120_000, h.hasta)
        val r = RelojDeLaGrabacion.delDiario(d.reloj, "K7Q2", d.latido.utc)
        assertEquals(listOf(listOf(T0 + 100_000, T0 + 120_000)), r.huecos)
        // El mismo minuto por los dos caminos.
        for (t in listOf(T0, T0 + 50_000, T0 + 110_000, T0 + 120_000)) assertEquals(d.msEn(t), RelojDeLaGrabacion.msHasta(r, t))
        // Una pausa abierta (sigue en pausa) cuenta hasta el tope.
        d.latir(T0 + 130_000, 110_000, true)
        assertEquals(110_000L, d.msEn(T0 + 200_000))
    }

    @Test
    fun `la linea de tiempo va en orden y dentro de la nota`() {
        val d = Diario.nuevo(T0, "p", "Clase", "a")
        d.apuntarCaptura(70_000, "captura-1.png", false)
        d.apuntarCaptura(10_000, "captura-2.jpg", true)
        d.empezarClip(40_000, "c.mp4")
        d.marcadores += Marcador(10_000, "x")
        // Una captura de después del final cae al final, no fuera.
        d.apuntarCaptura(999_000, "captura-3.png", false)
        assertEquals(
            listOf(
                10_000L to Elemento(Tipo.CAPTURA, 1),
                10_000L to Elemento(Tipo.MARCADOR, 0),
                40_000L to Elemento(Tipo.CLIP, 0),
                70_000L to Elemento(Tipo.CAPTURA, 0),
                80_000L to Elemento(Tipo.CAPTURA, 2)
            ),
            d.lineaDeTiempo(80_000)
        )
    }

    // --- Mirar un MP4 (pixpin_sistema::mp4) ----------------------------------------------------

    private fun fichero(nombre: String, bytes: ByteArray) = File(raiz, nombre).also { it.writeBytes(bytes) }

    @Test
    fun `un mp4 cerrado lleva su moov y uno cortado no se abre`() {
        val c = fichero("cerrado.mp4", caja("ftyp", 16) + caja("mdat", 1000) + caja("moov", 200))
        assertEquals(listOf("ftyp", "mdat", "moov"), GrabacionAMedias.cajas(c))
        assertEquals(EstadoMp4.CERRADO, GrabacionAMedias.mirar(c))
        assertTrue(GrabacionAMedias.mirar(c).seAbre)
        // El `mdat` dice más de lo que hay: la app murió escribiendo.
        val cortado = fichero("cortado.mp4", (caja("ftyp", 16) + caja("mdat", 5000)).copyOf(2000))
        assertEquals(EstadoMp4.SIN_INDICE, GrabacionAMedias.mirar(cortado))
        assertFalse(GrabacionAMedias.mirar(cortado).seAbre)
        // Un mdat «hasta el final» (tamaño cero) tampoco trae índice.
        val cero = fichero("cero.mp4", caja("ftyp", 16) + byteArrayOf(0, 0, 0, 0) + "mdat".toByteArray() + ByteArray(300))
        assertEquals(EstadoMp4.SIN_INDICE, GrabacionAMedias.mirar(cero))
    }

    @Test
    fun `un mp4 fragmentado se abre aunque se cortara y el tamano de 64 bits se entiende`() {
        val frag = caja("ftyp", 16) + caja("moov", 100) + caja("moof", 50) + caja("mdat", 400) + caja("moof", 50) + caja("mdat", 400)
        val f = fichero("frag.mp4", frag.copyOf(frag.size - 100))
        assertEquals(EstadoMp4.FRAGMENTADO, GrabacionAMedias.mirar(f))
        assertTrue(GrabacionAMedias.mirar(f).seAbre)
        val conIndice = fichero("mfra.mp4", caja("ftyp", 16) + caja("moov", 100) + caja("moof", 50) + caja("mdat", 400) + caja("mfra", 30))
        assertEquals(EstadoMp4.FRAGMENTADO_CON_INDICE, GrabacionAMedias.mirar(conIndice))
        // `mdat` con tamaño 1 y el de verdad en 64 bits: 16 + 100.
        val largo = ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(116).array()
        val l = fichero("largo.mp4", caja("ftyp", 16) + largo + ByteArray(100) + caja("moov", 20))
        assertEquals(listOf("ftyp", "mdat", "moov"), GrabacionAMedias.cajas(l))
        assertEquals(EstadoMp4.CERRADO, GrabacionAMedias.mirar(l))
    }

    @Test
    fun `lo que no es un mp4 es nada`() {
        assertEquals(EstadoMp4.NADA, GrabacionAMedias.mirar(File("/no/esta/aqui.mp4")))
        assertEquals(EstadoMp4.NADA, GrabacionAMedias.mirar(fichero("vacio.mp4", ByteArray(0))))
        assertEquals(EstadoMp4.NADA, GrabacionAMedias.mirar(fichero("texto.mp4", byteArrayOf(0, 1) + "hola que tal esto no es nada".toByteArray())))
        assertEquals(EstadoMp4.NADA, GrabacionAMedias.mirar(null))
    }

    // --- Hallar y el plan --------------------------------------------------------------------

    @Test
    fun `el plan sale de lo que quedo en el disco`() {
        val audio = File(raiz, "voz-1.m4a.parcial")
        val video = File(raiz, "voz-1.m4a.parcial.video.mp4")
        val d = Diario.nuevo(T0, "p", "Clase", audio.path)
        d.video = video.path
        val dir = d.carpeta(raiz).apply { mkdirs() }
        File(dir, "captura-1.png").writeBytes(byteArrayOf(1))
        d.apuntarCaptura(1_000, "captura-1.png", false)
        d.apuntarCaptura(2_000, "captura-2.png", false) // su fichero no llegó al disco
        val clipBueno = fichero("clip-1.mp4", mp4Cerrado())
        val clipRoto = fichero("clip-2.mp4", mp4Cortado())
        d.empezarClip(3_000, clipBueno.path)
        d.empezarClip(4_000, clipRoto.path)
        d.latir(T0 + 2_820_000, 2_820_000, false)
        // **Lo que deja un corte**: la nota a medias sin índice, su respaldo de 47 minutos y el vídeo
        // sin índice.
        File(audio.path + ".parte").writeBytes(mp4Cortado())
        File(video.path + ".parte").writeBytes(mp4Cortado())
        val conRespaldo = { r: File -> if (r.name == "voz-1.m4a.parcial.respaldo.wav") 2_820_500L else null }

        val h = GrabacionAMedias.hallar(dir, d, conRespaldo)
        assertFalse(h.audioListo)
        assertEquals(EstadoMp4.SIN_INDICE, h.audioParte)
        assertEquals(listOf(0), h.capturas)
        assertEquals(listOf(0), h.clips)
        var p = GrabacionAMedias.plan(d, h)
        assertEquals(Nota.DEL_RESPALDO, p.nota)
        assertEquals(2_820_500L, p.duracionMs)
        assertEquals(47L, p.minutos())
        assertEquals(Video.PERDIDO, p.video)
        assertTrue(p.algo())

        // El `.parte` de la nota llegó a cerrarse: se usa antes que el WAV.
        File(audio.path + ".parte").writeBytes(mp4Cerrado())
        p = GrabacionAMedias.plan(d, GrabacionAMedias.hallar(dir, d, conRespaldo))
        assertEquals(Nota.DEL_PARTE, p.nota)
        // Y el vídeo fragmentado se abre.
        File(video.path + ".parte").writeBytes(caja("ftyp", 8) + caja("moov", 8) + caja("moof", 8))
        assertEquals(Video.DEL_PARTE, GrabacionAMedias.plan(d, GrabacionAMedias.hallar(dir, d, conRespaldo)).video)
        // La nota ya en su nombre, mejor aún.
        audio.writeBytes(mp4Cerrado())
        assertEquals(Nota.LISTA, GrabacionAMedias.plan(d, GrabacionAMedias.hallar(dir, d, conRespaldo)).nota)

        // Sin respaldo, lo que dura sale del último latido.
        p = GrabacionAMedias.plan(d, GrabacionAMedias.hallar(dir, d) { null })
        assertEquals(2_820_000L, p.duracionMs)

        // Caso negativo: no queda nada de nada.
        val vacio = Diario.nuevo(T0, "p", "Clase", File(raiz, "otra.m4a.parcial").path)
        p = GrabacionAMedias.plan(vacio, GrabacionAMedias.hallar(File(raiz, "x"), vacio) { null })
        assertEquals(Nota.NINGUNA, p.nota)
        assertFalse(p.algo())
        assertEquals(0L, p.minutos())
    }

    @Test
    fun `un respaldo de menos de 700 ms no es una nota`() {
        val d = Diario.nuevo(T0, "p", "Clase", File(raiz, "a.m4a").path)
        val p = GrabacionAMedias.plan(d, GrabacionAMedias.hallar(raiz, d) { 699L })
        assertEquals(Nota.NINGUNA, p.nota)
        assertEquals(1L, p.minutos()) // dura algo, aunque no sea nota
        assertEquals(Nota.DEL_RESPALDO, GrabacionAMedias.plan(d, GrabacionAMedias.hallar(raiz, d) { 700L }).nota)
    }

    @Test
    fun `los trozos del video que se abren se recuperan y el que se grababa no`() {
        val d = Diario.nuevo(T0, "p", "Clase", File(raiz, "a.m4a").path)
        d.video = File(raiz, "a.mp4").path
        val t1 = fichero("t1.mp4", mp4Cerrado())
        val t2 = fichero("t2.mp4", mp4Cerrado())
        val t3 = fichero("t3.mp4", mp4Cortado()) // el que se grababa al cortarse
        d.empezarTrozo(0, t1.path, 0); d.cerrarTrozo(t1.path, 180_000)
        d.empezarTrozo(180_000, t2.path, 0); d.cerrarTrozo(t2.path, 180_000)
        d.empezarTrozo(360_000, t3.path, 0)
        d.capturasDelVideo += 200_000
        // Un clip del vídeo de la sala (sin fichero), cerrado, y otro sin cerrar.
        d.empezarClip(10_000, ""); d.cerrarClip("", 5_000)
        d.empezarClip(400_000, "")
        val h = GrabacionAMedias.hallar(raiz, d) { 400_000L }
        assertEquals(listOf(0, 1), h.trozos)
        assertEquals(listOf(0), h.clipsDelVideo)
        val p = GrabacionAMedias.plan(d, h)
        assertEquals(Video.DEL_PARTE, p.video)
        assertEquals(listOf(0, 1), p.trozos)
        assertEquals(listOf(200_000L), p.capturasDelVideo)
        assertEquals(listOf(0), p.clipsDelVideo)
        // Caso negativo: ningún trozo se abre: perdido (se conserva hasta Descartar).
        t1.writeBytes(mp4Cortado()); t2.writeBytes(mp4Cortado())
        val p2 = GrabacionAMedias.plan(d, GrabacionAMedias.hallar(raiz, d) { 400_000L })
        assertEquals(Video.PERDIDO, p2.video)
        assertTrue(p2.capturasDelVideo.isEmpty())
        assertTrue(p2.clipsDelVideo.isEmpty())
    }

    @Test
    fun `solo la nota no vuelve a meter lo que ya entro`() {
        val audio = File(raiz, "voz-1.m4a.parcial")
        val d = Diario.nuevo(T0, "p", "Clase", audio.path)
        val dir = d.carpeta(raiz).apply { mkdirs() }
        File(dir, "captura-1.png").writeBytes(byteArrayOf(1))
        d.apuntarCaptura(1_000, "captura-1.png", false)
        d.soloLaNota = true
        val p = GrabacionAMedias.plan(d, GrabacionAMedias.hallar(dir, d) { 60_000L })
        assertEquals(Nota.DEL_RESPALDO, p.nota)
        assertTrue(p.capturas.isEmpty())
    }

    @Test
    fun `descartar se lleva lo suyo y nada mas`() {
        val audio = File(raiz, "voz-1.m4a.parcial")
        val video = File(raiz, "voz-1.m4a.parcial.video.mp4")
        val d = Diario.nuevo(T0, "p", "Clase", audio.path)
        d.video = video.path
        val dir = d.carpeta(raiz).apply { mkdirs() }
        File(dir, "captura-1.png").writeBytes(byteArrayOf(1))
        val trozo = fichero("t1.mp4", mp4Cerrado())
        d.empezarTrozo(0, trozo.path, 0)
        val suyos = listOf(File(audio.path + ".parte"), File(audio.path + ".respaldo.wav"), File(video.path + ".parte"), trozo)
        suyos.forEach { it.writeBytes(byteArrayOf(1)) }
        val ajeno = fichero("voz-2.m4a", "de otra".toByteArray())
        GrabacionAMedias.descartar(dir, d)
        assertTrue(suyos.none { it.exists() })
        assertFalse(dir.exists())
        assertTrue("descartar no se lleva lo que no es suyo", ajeno.exists())
    }

    // --- El disco ------------------------------------------------------------------------------

    @Test
    fun `el espacio avisa a los dos gigas y para el video a los quinientos megas`() {
        val mb = 1024L * 1024
        assertEquals(Espacio.BIEN, GrabacionAMedias.espacio(50_000 * mb))
        assertEquals(Espacio.BIEN, GrabacionAMedias.espacio(2_048 * mb))
        assertEquals(Espacio.POCO, GrabacionAMedias.espacio(2_047 * mb))
        assertEquals(Espacio.POCO, GrabacionAMedias.espacio(500 * mb))
        assertEquals(Espacio.CRITICO, GrabacionAMedias.espacio(499 * mb))
        assertEquals(Espacio.CRITICO, GrabacionAMedias.espacio(0))
        // Caso negativo: si no se sabe, se graba igual.
        assertEquals(Espacio.BIEN, GrabacionAMedias.espacio(null))
    }
}
