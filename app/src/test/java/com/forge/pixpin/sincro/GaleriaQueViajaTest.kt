package com.forge.pixpin.sincro

import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.CaducidadDeCapturas.DIA_MS
import com.forge.pixpin.capture.GaleriaCompartida
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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
 * **La galería que viaja, entre dos «teléfonos» de mentira** que se hablan por un socket con el
 * protocolo y el cifrado de verdad. Las capturas de cada uno son una carpeta (en el teléfono es
 * MediaStore: `CapturasEnElTelefono`).
 */
class GaleriaQueViajaTest {

    /** Las capturas de un aparato en una carpeta; la papelera, otra. La hora de cada una, su fecha de modificación. */
    class EnCarpeta(override val raiz: File, var diasAqui: Int = 7) : CapturasDelAparato {
        val carpeta = File(raiz, "Pictures/PixPin").apply { mkdirs() }
        val papelera = File(raiz, "papelera").apply { mkdirs() }
        override fun listar() = carpeta.listFiles().orEmpty().sortedBy { it.name }.map {
            GaleriaCompartida.Local(it.name, it.lastModified(), it.length(), if (it.name.endsWith(".mp4")) "video/mp4" else "image/png")
        }
        override fun abrir(nombre: String): Pair<Long, InputStream>? =
            File(carpeta, nombre).takeIf { it.isFile }?.let { it.length() to it.inputStream() }
        override fun guardar(e: GaleriaCompartida.Entrada, escribir: (OutputStream) -> Boolean): Boolean {
            val f = File(carpeta, e.nombre)
            val bien = f.outputStream().use(escribir)
            if (!bien) { f.delete(); return false }
            f.setLastModified(e.cuando)
            return true
        }
        override fun tirar(nombres: Collection<String>) {
            nombres.forEach { File(carpeta, it).renameTo(File(papelera, it)) }
        }
        override fun dias() = diasAqui

        fun hacer(nombre: String, cuando: Long, bytes: Int = 3000) =
            File(carpeta, nombre).apply { writeBytes(ByteArray(bytes) { (it * 7 + nombre.length).toByte() }); setLastModified(cuando) }
        fun nombres() = carpeta.list().orEmpty().sorted()
        fun seVa(nombre: String, ahora: Long): Long? {
            val f = File(carpeta, nombre)
            return CaducidadDeCapturas.seVaEl(CaducidadDeCapturas.leer(raiz, ahora), nombre, f.lastModified(), diasAqui)
        }
    }

    private lateinit var base: File
    private lateinit var telefono: Disco
    private lateinit var tableta: Disco
    private lateinit var galTel: EnCarpeta
    private lateinit var galTab: EnCarpeta

    /** Un lunes cualquiera, a mediodía (las horas de los ficheros van en segundos enteros). */
    private val T0 = 1_790_000_000_000L
    private var reloj = T0

    @Before
    fun montar() {
        base = Files.createTempDirectory("galeria").toFile()
        telefono = Disco(File(base, "telefono/files").apply { mkdirs() })
        tableta = Disco(File(base, "tableta/files").apply { mkdirs() })
        telefono.identidad.guardar(Identidad(Aparato("id-tel", "Teléfono")))
        tableta.identidad.guardar(Identidad(Aparato("id-tab", "Tableta")))
        galTel = EnCarpeta(telefono.filesDir); telefono.capturas = galTel
        galTab = EnCarpeta(tableta.filesDir); tableta.capturas = galTab
        // El registro de cada uno empieza antes que todo: nada de «lo que ya había no se va».
        CaducidadDeCapturas.leer(galTel.raiz, T0 - 30 * DIA_MS)
        CaducidadDeCapturas.leer(galTab.raiz, T0 - 30 * DIA_MS)
        val codigo = telefono.crearGrupo(codigo = "ABCDE23456", ahora = T0)
        conectado(tableta, telefono, unirme = true, codigo = codigo) {}
    }

    @After
    fun desmontar() { base.deleteRecursively() }

    private fun <T> conectado(desde: Disco, hacia: Disco, unirme: Boolean = false, codigo: String? = null, uso: (Sesion) -> T): T {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var fallo: Throwable? = null
        val hilo = Thread {
            try { server.accept().use { s -> Respondedor(hacia, ahora = { reloj }).atender(s.getInputStream(), s.getOutputStream()) } }
            catch (e: Throwable) { fallo = e } finally { server.close() }
        }
        hilo.start()
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), desde, unirme, codigo, ahora = { reloj })
            val r = uso(sesion)
            sesion.adios()
            hilo.join(5000)
            fallo?.let { throw AssertionError("El que responde falló", it) }
            return r
        }
    }

    /** Una vuelta con el teléfono dirigiendo (o la tableta), solo la galería. */
    private fun sincronizar(desde: Disco = telefono, hacia: Disco = tableta): Sesion.Hecho {
        val hecho = Sesion.Hecho()
        conectado(desde, hacia) { s -> assertTrue(s.galeria(hecho, reloj)) }
        return hecho
    }

    @Test
    fun `las capturas de cada uno pasan al otro y se van a la vez en los dos`() {
        galTel.hacer("captura-tel.png", T0 - 2 * DIA_MS, 1_300_000)   // más de un trozo
        galTab.hacer("captura-tab.png", T0 - DIA_MS)
        reloj = T0
        val h = sincronizar()
        assertEquals(2, h.capturas)
        assertEquals(listOf("captura-tab.png", "captura-tel.png"), galTel.nombres())
        assertEquals(galTel.nombres(), galTab.nombres())
        assertTrue(File(galTel.carpeta, "captura-tel.png").readBytes().contentEquals(File(galTab.carpeta, "captura-tel.png").readBytes()))
        // Llegó con su hora de verdad, y se va el mismo día en los dos: a los 7 días de hacerse.
        for (n in galTel.nombres()) {
            assertEquals(n, galTel.seVa(n, reloj), galTab.seVa(n, reloj))
        }
        assertEquals(T0 - 2 * DIA_MS + 7 * DIA_MS, galTab.seVa("captura-tel.png", reloj))
        // Otra vuelta: ya están iguales, no pasa nada.
        assertEquals(0, sincronizar(tableta, telefono).capturas)
    }

    @Test
    fun `siete dias mas y conservar en uno se ven en el otro`() {
        galTel.hacer("a.png", T0 - DIA_MS)
        galTel.hacer("b.png", T0 - DIA_MS)
        sincronizar()
        // En la tableta: «7 días más» a una, como hace la galería.
        reloj = T0 + 1000
        val cuandoA = File(galTab.carpeta, "a.png").lastModified()
        CaducidadDeCapturas.cambiar(galTab.raiz, reloj) { CaducidadDeCapturas.prorrogada(it, "a.png", cuandoA, reloj, 7) }
        // Y en el teléfono se conserva la otra.
        CaducidadDeCapturas.cambiar(galTel.raiz, reloj) { it.copy(conservadas = it.conservadas + "b.png") }
        reloj = T0 + 2000
        sincronizar()
        assertEquals(T0 - DIA_MS + 14 * DIA_MS, galTel.seVa("a.png", reloj))
        assertEquals(galTab.seVa("a.png", reloj), galTel.seVa("a.png", reloj))
        assertNull(galTab.seVa("b.png", reloj))
        assertTrue("b.png" in CaducidadDeCapturas.leer(galTab.raiz, reloj).conservadas)
    }

    @Test
    fun `quitar una a mano antes de tiempo la quita del otro, a su papelera`() {
        galTel.hacer("a.png", T0 - DIA_MS)
        galTel.hacer("b.png", T0 - DIA_MS)
        sincronizar()
        File(galTab.carpeta, "a.png").delete()   // borrada en la tableta
        reloj = T0 + 5000
        val h = sincronizar(tableta, telefono)
        assertEquals(listOf("b.png"), galTel.nombres())
        assertEquals(listOf("a.png"), galTel.papelera.list()!!.toList())
        assertEquals("la quitada no vuelve a pasar", 0, h.capturas)
        // Y no vuelve a la tableta en la siguiente vuelta.
        sincronizar()
        assertEquals(listOf("b.png"), galTab.nombres())
    }

    @Test
    fun `lo caducado no viaja ni deja marca de borrado`() {
        galTel.hacer("vieja.png", T0 - 10 * DIA_MS)
        galTel.hacer("nueva.png", T0 - DIA_MS)
        reloj = T0
        sincronizar()
        assertEquals(listOf("nueva.png"), galTab.nombres())
        // El teléfono barre la vieja (caducó): eso no es «quitarla a mano».
        File(galTel.carpeta, "vieja.png").renameTo(File(galTel.papelera, "vieja.png"))
        sincronizar()
        val e = GaleriaCompartida.leer(galTab.raiz).porNombre["vieja.png"]!!
        assertFalse(e.borrada)
    }

    @Test
    fun `un aparato sin galeria contesta como uno de antes y la vuelta sigue`() {
        tableta.capturas = null
        galTel.hacer("a.png", T0)
        conectado(telefono, tableta) { s ->
            assertFalse(s.galeria(Sesion.Hecho(), reloj))
            // La conversación sigue sana: se puede pedir otra cosa.
            s.lapidas()
        }
        assertTrue(galTab.nombres().isEmpty())
    }

    @Test
    fun `un nombre con ruta no se acepta`() {
        for (malo in listOf("../fuera.png", "a/b.png", "", "..", "x\\y.png")) {
            assertTrue(malo, runCatching { GaleriaQueViaja.nombreValido(malo) }.isFailure)
        }
        assertEquals("captura 1.png", GaleriaQueViaja.nombreValido("captura 1.png"))
    }
}
