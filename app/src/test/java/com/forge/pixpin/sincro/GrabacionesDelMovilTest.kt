package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.CarpetaDePaginas
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.GrabarClase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.RelojDePared
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **El teléfono graba y el PC le manda capturas** (28-oct-2026, §13): [GrabacionesDelMovil] es `Camaras`
 * de `camara_del_movil.rs` del PC con el teléfono de quien graba; estas son sus pruebas
 * (`el_anuncio_dice_en_que_minuto_va…`, `caso_negativo_lo_de_otra_grabacion…`, la cola hasta guardar y
 * lo de por su hora), sobre el [Disco] del teléfono.
 */
class GrabacionesDelMovilTest {

    private val s = 1000L
    private val pared = 1_761_550_000_000L
    private lateinit var dir: File
    private lateinit var disco: Disco
    private lateinit var g: GrabacionesDelMovil

    @Before
    fun montar() {
        dir = Files.createTempDirectory("grabaciones-del-movil").toFile()
        disco = Disco(File(dir, "files").apply { mkdirs() })
        disco.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        g = GrabacionesDelMovil(File(dir, "cola"), "Teléfono", disco.identidad.leer().yo.codigo).also { it.almacen = disco }
    }

    @After
    fun desmontar() { dir.deleteRecursively() }

    private fun png(nombre: String): File = File(dir, nombre).apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)) }

    private fun foto(grabacion: String?, ms: Long?, tomadaEn: Long = 0, reloj: Long = 0, clave: String = "") =
        GrabacionesDelMovil.Foto("Captura.png", "image/png", 11, grabacion, ms, tomadaEn, reloj, 0, clave)

    /** La clase ya guardada en el chat del teléfono: su nota y su carpeta. */
    private fun guardarLaClase(duracion: Long): GrabacionesDelMovil.Destino {
        val voz = Mensaje(id = "voz1", cuando = pared, clase = Clase.VOZ, duracionMs = duracion.toInt(), proyecto = "p1", grabacion = GrabarClase.CLASE, capturas = GrabarClase.capturasEnJson(emptyList()))
        val carpeta = CarpetaDePaginas.mensajeNuevo("Clase", pared + 1, "p1", CarpetaDePaginas.grabacion("voz1", duracion).anadirAlMinuto("voz1", "", 0)!!)
        disco.cambiarMensajes { it + voz + carpeta }
        return GrabacionesDelMovil.Destino(disco, "p1", "voz1", carpeta.id) { pared + 500_000 }
    }

    @Test
    fun `el anuncio dice en que minuto va y si esta en pausa`() {
        assertNull(g.anuncio(pared))
        g.empezar("m-1", "Clase del móvil", pared)
        g.seguirA(pared + 20 * s, 20 * s, true)
        val a = g.anuncio(pared + 30 * s)!!
        assertEquals("m-1", a.grabacion)
        assertTrue(a.activa && a.enPausa)
        assertEquals(20 * s, a.msActual)
        assertEquals(pared, a.desde)
        assertEquals(listOf(listOf(pared + 20 * s, 0L)), a.pausas)
        assertEquals("Teléfono", a.aparato)
        g.seguirA(pared + 40 * s, 20 * s, false)
        g.acabar(pared + 50 * s)
        val acabada = g.anuncio(pared + 99 * s)!!
        assertFalse(acabada.activa)
        assertEquals(30 * s, acabada.duracion)
        // Apagada: nada.
        g.encendida = false
        assertNull(g.anuncio(pared))
    }

    @Test
    fun `la captura del PC espera a que se guarde la clase y entra en su minuto`() {
        g.empezar("m-1", "Clase", pared)
        assertEquals(35 * s, g.recibir(foto("m-1", 35 * s, clave = "pc-1"), png("a.png"), "id-pc", "Portátil", pared + 40 * s))
        assertEquals(1, g.recibidas)
        assertEquals(listOf(GrabacionesDelMovil.Recibida(35 * s, false, "Portátil")), g.tomarNuevas())
        g.acabar(pared + 60 * s)
        val destino = guardarLaClase(60 * s)
        assertEquals(1, g.guardada(destino, 60 * s, pared + 61 * s))
        val todos = disco.leerMensajes()
        val captura = todos.first { it.fotoUid == "pc-1" }
        assertEquals(Clase.IMAGEN, captura.clase)
        assertEquals(35 * s, captura.enGrabacion!!["ms"]!!.jsonPrimitive.long)
        assertEquals("voz1", captura.enGrabacion!!["de"]!!.jsonPrimitive.content)
        assertEquals(GrabacionesDelMovil.DE_PANTALLA, captura.fuente)
        assertEquals("Portátil", captura.deAparato)
        assertTrue(File(captura.ruta!!).isFile)
        val voz = todos.first { it.id == "voz1" }
        assertEquals(listOf(GrabarClase.Enlace(35 * s, captura.id)), GrabarClase.capturasDe(voz))
        assertTrue(CarpetaDePaginas.deMensaje(todos.first { CarpetaDePaginas.esGrabacion(it) })!!.contiene(captura.id))
        // El reloj de pared entró en la nota, con el id de la grabación.
        val r = RelojDePared.de(voz)!!
        assertEquals(Triple(pared, pared + 60 * s, "m-1"), Triple(r.inicio, r.fin, r.grabacion))
        assertEquals(disco.identidad.leer().yo.codigo, r.aparato)
        // Ya guardada: la misma otra vez (el PC no supo si llegó) no se repite; una nueva entra al momento.
        assertEquals(35 * s, g.recibir(foto("m-1", 35 * s, clave = "pc-1"), png("b.png"), "id-pc", "Portátil", pared + 70 * s))
        assertEquals(1, disco.leerMensajes().count { it.fotoUid == "pc-1" })
        assertEquals(50 * s, g.recibir(foto("m-1", 50 * s, clave = "pc-2"), png("c.png"), "id-pc", "Portátil", pared + 70 * s))
        assertEquals(2, GrabarClase.capturasDe(disco.leerMensajes().first { it.id == "voz1" }).size)
    }

    @Test
    fun `sin minuto, por su hora con el desfase que manda`() {
        g.empezar("m-1", "Clase", pared)
        // El PC va 5 s adelantado: su +25 s es el +20 de aquí.
        assertEquals(20 * s, g.recibir(foto("m-1", null, tomadaEn = pared + 25 * s, reloj = 5 * s), png("a.png"), "id-pc", "PC", pared + 30 * s))
        // Sin grabación sabida (§12.4): la de ahora, si cae dentro.
        assertEquals(10 * s, g.recibir(foto(null, null, tomadaEn = pared + 10 * s), png("b.png"), "id-pc", "PC", pared + 30 * s))
    }

    @Test
    fun `por su hora en una clase ya guardada y caso negativo fuera de todas`() {
        g.empezar("m-1", "Clase", pared)
        g.acabar(pared + 100 * s)
        g.guardada(guardarLaClase(100 * s), 100 * s, pared + 101 * s)
        // Otro teléfono «recién arrancado»: nada en memoria, solo lo guardado.
        val otro = GrabacionesDelMovil(File(dir, "cola2"), "Teléfono", g.codigo).also { it.almacen = disco }
        assertEquals(30 * s, otro.recibir(foto(null, null, tomadaEn = pared + 30 * s, clave = "pc-h"), png("a.png"), "id-pc", "PC", pared + 999 * s))
        // Con su id de grabación (lo que llega tarde tras un reinicio): ese, con su minuto.
        assertEquals(44 * s, otro.recibir(foto("m-1", 44 * s, clave = "pc-i"), png("b.png"), "id-pc", "PC", pared + 999 * s))
        val e = runCatching { otro.recibir(foto(null, null, tomadaEn = pared + 3600 * s), png("c.png"), "id-pc", "PC", pared + 999 * s) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(CamaraRemota.esNinguna(e!!.message.orEmpty()))
        assertTrue(CamaraRemota.esNoParaSiempre(e.message.orEmpty()))
    }

    @Test
    fun `caso negativo lo de otra grabacion, sin hora o que no es una foto no entra`() {
        g.empezar("m-1", "Clase", pared)
        assertEquals("Esa grabación ya no está en el teléfono", g.aceptar("otra", 0, "image/png", 10))
        assertEquals("La foto no dice de qué grabación es", g.aceptar(null, 0, "image/png", 10))
        assertEquals(CamaraRemota.SOLO_FOTOS_Y_CLIPS, g.aceptar("m-1", 0, "image/heic", 10))
        assertEquals("La foto o el clip es demasiado grande", g.aceptar("m-1", 0, "image/png", 0))
        assertNull(g.aceptar("m-1", 0, "image/png", 10))
        g.encendida = false
        assertNotNull(g.aceptar("m-1", 0, "image/png", 10))
    }

    @Test
    fun `tirada la grabacion, lo suyo tambien`() {
        g.empezar("m-1", "Clase", pared)
        val f = png("a.png")
        g.recibir(foto("m-1", 5 * s), f, "id-pc", "PC", pared + 10 * s)
        g.tirar()
        assertNull(g.enCurso)
        assertEquals(0, File(dir, "cola").walk().count { it.isFile })
    }

    @Test
    fun `la pregunta con la suya va como la del PC`() {
        val p = CamaraRemota.Pregunta(reloj = 5, suya = CamaraRemota.Anuncio(grabacion = "m-1", activa = true, msActual = 35000, desde = 7, reloj = 5))
        assertEquals(
            """{"t":"camara_remota","reloj":5,"suya":{"t":"camara_remota","grabacion":"m-1","activa":true,"ms_actual":35000,"desde":7,"reloj":5}}""",
            CamaraRemota.aTexto(p)
        )
        assertEquals(p, CamaraRemota.preguntaDe(CamaraRemota.aTexto(p)))
        // La de siempre, sin `suya`.
        assertEquals("""{"t":"camara_remota","reloj":5}""", CamaraRemota.aTexto(CamaraRemota.Pregunta(reloj = 5)))
    }

    @Test
    fun `el minuto de la foto que llega a quien graba`() {
        val r = RelojDeGrabacion(pared).apply { pausar(pared + 30 * s); seguir(pared + 50 * s) }
        // Con su minuto, ese; sin él, por su hora con el desfase que mandó o el medido; sin nada, ahora.
        assertEquals(12 * s, CamaraRemota.msDeLaFoto(r, pared + 60 * s, null, 12 * s, 0, 0, null))
        assertEquals(30 * s, CamaraRemota.msDeLaFoto(r, pared + 60 * s, null, null, pared + 47 * s, 7 * s, null))
        assertEquals(30 * s, CamaraRemota.msDeLaFoto(r, pared + 60 * s, null, null, pared + 57 * s, 0, 7 * s))
        assertEquals(40 * s, CamaraRemota.msDeLaFoto(r, pared + 60 * s, null, null, 0, 0, null))
        // Nunca más allá de lo que lleva.
        assertEquals(40 * s, CamaraRemota.msDeLaFoto(r, pared + 60 * s, null, 999 * s, 0, 0, null))
        assertEquals(5 * s, CamaraRemota.duracionDelClip(9 * s, 35 * s, 40 * s))
    }
}
