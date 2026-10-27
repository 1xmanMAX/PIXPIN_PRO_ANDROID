package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import java.io.File
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Dos personas tocan cada una el lienzo de la otra** (27-oct-2026), sincronizando de verdad como
 * `ChatsCompartidosDeVerdadTest`: Max y Ana en un chat compartido, cada uno con su lienzo. Max
 * cambia el de Ana y Ana el de Max. Antes eso juntaba los dos cambios en cada original; ahora los
 * dos originales quedan intactos y hay dos versiones vinculadas, las mismas en los dos aparatos.
 */
class VersionesVinculadasDeVerdadTest {

    private lateinit var raiz: File
    private lateinit var max: Disco
    private lateinit var ana: Disco
    private var reloj = 1_000_000L

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("versiones-de-verdad").toFile()
        max = Disco(File(raiz, "max/files").apply { mkdirs() })
        ana = Disco(File(raiz, "ana/files").apply { mkdirs() })
        max.identidad.guardar(Identidad(Aparato("id-max", "Teléfono de Max")))
        ana.identidad.guardar(Identidad(Aparato("id-ana", "Móvil de Ana")))
        CompartidosEnDisco(max.filesDir).ponerNombre("Max")
        CompartidosEnDisco(ana.filesDir).ponerNombre("Ana")
    }

    @After
    fun desmontar() {
        raiz.deleteRecursively()
    }

    // ------------------------------------------------------------ utilidades

    private fun escribir(d: Disco, m: Mensaje) =
        File(d.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")

    /** Lo que hace `VersionesEnElChat.cerrar` con el almacén: el chat con las versiones nuevas. */
    private fun guardarChat(d: Disco, lista: List<Mensaje>) =
        File(d.filesDir, "guardados.jsonl").writeText(lista.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })

    private fun lienzoDeMensaje(d: Disco, id: String, dibujo: String, texto: String): Mensaje {
        lienzo(d, dibujo, texto, 1)
        val suyos = d.leerMensajes().filter { it.proyecto == "p1" }
        val m = Mensaje(id = id, cuando = reloj++, clase = Clase.DIBUJO, referencia = dibujo, nombre = texto, proyecto = "p1",
            numero = (suyos.maxOfOrNull { it.numero } ?: 0) + 1, uid = Codigos.nuevo(), aparato = d.identidad.leer().yo.codigo)
        escribir(d, m)
        return m
    }

    private fun lienzo(d: Disco, id: String, texto: String, version: Int) {
        val f = File(d.filesDir, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }
        GZIPOutputStream(f.outputStream()).use {
            it.write("""{"elements":[{"id":"e1","type":"text","text":"$texto","version":$version,"versionNonce":$version,"updated":$version}],"files":{}}""".toByteArray())
        }
    }

    private fun textoDelLienzo(d: Disco, id: String): String =
        GZIPInputStream(File(d.filesDir, "pins/draw/$id.excalidraw.gz").inputStream()).use { it.readBytes().decodeToString() }
            .substringAfter("\"text\":\"").substringBefore('"')

    private fun lados(d: Disco): LadosDelChat =
        EntrePersonas.porChat(CompartidosEnDisco(d.filesDir).leer()) { d.identidad.leer() }.getValue("p1").lados

    private fun escuchar(d: Disco, codigo: String? = null, chat: String? = null, veces: Int = 1): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            repeat(veces) {
                runCatching {
                    server.accept().use { s ->
                        val entrada = PushbackInputStream(s.getInputStream(), 4)
                        val magia = ByteArray(4).also { b -> var n = 0; while (n < 4) { val r = entrada.read(b, n, 4 - n); if (r < 0) break; n += r } }
                        entrada.unread(magia)
                        val r = Respondedor(d, ahora = { reloj })
                        when {
                            magia.contentEquals(Canal.MAGIA_DE_INVITACION) -> r.atenderInvitacion(entrada, s.getOutputStream(), codigo!!, chat!!)
                            magia.contentEquals(Canal.MAGIA_DE_PERSONA) -> r.atenderPersona(entrada, s.getOutputStream())
                            else -> r.atender(entrada, s.getOutputStream())
                        }
                    }
                }
            }
            server.close()
        }
        hilo.start()
        return server to hilo
    }

    private fun <T> conectar(server: ServerSocket, uso: (Socket) -> T): T =
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use(uso)

    private fun anaEntra() {
        val (server, hilo) = escuchar(max, "K7Q2M9XP", "p1", veces = 2)
        conectar(server) { s -> Invitacion.entrar(s.getInputStream(), s.getOutputStream(), ana, "K7Q2M9XP", ahora = { reloj }) }
        conectar(server) { s -> VueltaConPersona(ana, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream(), aQuien = "id-max") }
        hilo.join(5000)
    }

    private fun vuelta(desde: Disco, hacia: Disco) {
        val (server, hilo) = escuchar(hacia)
        conectar(server) { s -> VueltaConPersona(desde, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream()) }
        hilo.join(5000)
    }

    /** [d] abre [original] (de la otra persona), lo cambia en el editor y vuelve al chat. */
    private fun editarComoVersion(d: Disco, original: Mensaje, texto: String) {
        val lista = d.leerMensajes()
        assertTrue(VersionesVinculadas.seVersiona(original, lados(d)))
        val e = VersionesVinculadas.empezar(d.filesDir, original, lista, d.identidad.leer().yo.codigo, reloj++)
        // El editor escribe en la copia.
        lienzo(d, e.version.referencia!!, texto, 2)
        val c = VersionesVinculadas.cerrar(d.filesDir, d.leerMensajes(), reloj++)
        assertEquals(1, c.anadir.size)
        guardarChat(d, VersionesVinculadas.conVersiones(d.leerMensajes(), c))
    }

    // ------------------------------------------------------------------ prueba

    @Test
    fun `cada uno edita el lienzo del otro y quedan los dos originales y dos versiones en los dos`() {
        max.guardarProyecto(Proyecto("p1", "Tesis", tocado = 5))
        val c = CompartidosEnDisco(max.filesDir)
        c.poner(Compartidos.crear("p1", "Tesis", c.yo(max.identidad.leer()), reloj++))
        val deMax = lienzoDeMensaje(max, "lm", "lienzo-max", "planta de Max")
        anaEntra()
        val deAna = lienzoDeMensaje(ana, "la", "lienzo-ana", "alzado de Ana")
        vuelta(ana, max)
        assertTrue(max.leerMensajes().any { it.id == "la" })
        assertEquals("alzado de Ana", textoDelLienzo(max, "lienzo-ana"))

        // Lo mío se edita en su sitio; lo de la otra persona, no.
        assertFalse(VersionesVinculadas.seVersiona(deMax, lados(max)))
        assertFalse(VersionesVinculadas.seVersiona(deAna, lados(ana)))

        // A la vez, sin haber sincronizado entre medias: Max toca el de Ana y Ana el de Max.
        editarComoVersion(max, max.leerMensajes().first { it.id == "la" }, "alzado de Ana corregido por Max")
        editarComoVersion(ana, ana.leerMensajes().first { it.id == "lm" }, "planta de Max corregida por Ana")
        vuelta(ana, max)
        vuelta(max, ana)

        for (d in listOf(max, ana)) {
            val chat = d.leerMensajes().filter { it.proyecto == "p1" }
            assertEquals(4, chat.size)
            // Los dos originales, intactos.
            assertEquals("planta de Max", textoDelLienzo(d, "lienzo-max"))
            assertEquals("alzado de Ana", textoDelLienzo(d, "lienzo-ana"))
            val lm = chat.first { it.id == "lm" }
            val la = chat.first { it.id == "la" }
            assertEquals("lienzo-max", lm.referencia)
            assertEquals("lienzo-ana", la.referencia)
            // Las dos versiones, cada una de su autor, vinculada a su original y contestándole.
            val deLaDeAna = VersionesVinculadas.versionesDe(chat, la).single()
            val deLaDeMax = VersionesVinculadas.versionesDe(chat, lm).single()
            assertEquals(max.identidad.leer().yo.codigo, deLaDeAna.aparato)
            assertEquals(ana.identidad.leer().yo.codigo, deLaDeMax.aparato)
            assertEquals("la", deLaDeAna.respondeA)
            assertEquals("lm", deLaDeMax.respondeA)
            assertEquals(1, deLaDeAna.versionN)
            assertEquals("alzado de Ana corregido por Max", textoDelLienzo(d, deLaDeAna.referencia!!))
            assertEquals("planta de Max corregida por Ana", textoDelLienzo(d, deLaDeMax.referencia!!))
            // Cada versión es de quien la hizo: para el otro, ajena, y para su autor, suya.
            val l = lados(d)
            val mia = if (d === max) deLaDeAna else deLaDeMax
            val suya = if (d === max) deLaDeMax else deLaDeAna
            assertFalse(VersionesVinculadas.esAjeno(mia, l))
            assertTrue(VersionesVinculadas.esAjeno(suya, l))
        }
        // Los dos aparatos tienen exactamente lo mismo.
        fun firma(d: Disco) = d.leerMensajes().filter { it.proyecto == "p1" }.map { Disco.JSON.encodeToString(Mensaje.serializer(), it.copy(recuerdaEn = null, ruta = null)) }.toSet()
        assertEquals(firma(max), firma(ana))
    }
}
