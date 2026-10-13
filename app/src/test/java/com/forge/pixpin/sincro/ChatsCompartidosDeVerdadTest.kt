package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import java.io.File
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
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
 * **Dos personas de verdad, un chat compartido** (13-oct-2026). Como `SincronizarDeVerdadTest`:
 * cada aparato es una carpeta, y se hablan por un socket con el protocolo y el cifrado de verdad.
 * Max y Ana **no** están en el mismo grupo: no comparten código. Lo que se comprueba es lo que
 * pidió el usuario —que el chat compartido viaje y nada más— y que no se pueda pedir lo que no toca.
 */
class ChatsCompartidosDeVerdadTest {

    private lateinit var raiz: File
    private lateinit var max: Disco
    private lateinit var ana: Disco
    private lateinit var eva: Disco
    private var reloj = 1_000_000L

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("compartidos").toFile()
        max = Disco(File(raiz, "max/files").apply { mkdirs() })
        ana = Disco(File(raiz, "ana-otra-ruta/data/files").apply { mkdirs() })
        eva = Disco(File(raiz, "eva/files").apply { mkdirs() })
        max.identidad.guardar(Identidad(Aparato("id-max", "Teléfono de Max")))
        ana.identidad.guardar(Identidad(Aparato("id-ana", "Móvil de Ana")))
        eva.identidad.guardar(Identidad(Aparato("id-eva", "Móvil de Eva")))
        // Max tiene además su grupo de siempre: no tiene nada que ver con Ana.
        max.crearGrupo(codigo = "ABCDE23456", ahora = reloj)
        CompartidosEnDisco(max.filesDir).ponerNombre("Max")
        CompartidosEnDisco(ana.filesDir).ponerNombre("Ana")
    }

    @After
    fun desmontar() {
        raiz.deleteRecursively()
    }

    // ------------------------------------------------------------ utilidades

    private fun mensaje(d: Disco, id: String, texto: String, proyecto: String?, clase: Clase = Clase.NOTA,
                        ruta: String? = null, referencia: String? = null, uid: String? = null): Mensaje {
        val suyos = d.leerMensajes().filter { it.proyecto == proyecto }
        val m = Mensaje(id = id, cuando = reloj++, clase = clase, texto = texto, proyecto = proyecto, ruta = ruta,
            referencia = referencia, numero = (suyos.maxOfOrNull { it.numero } ?: 0) + 1, uid = uid,
            aparato = d.identidad.leer().yo.codigo)
        File(d.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")
        return m
    }

    private fun lienzo(d: Disco, id: String, texto: String) {
        val f = File(d.filesDir, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }
        GZIPOutputStream(f.outputStream()).use { it.write("""{"elements":[{"id":"e1","type":"text","text":"$texto","version":1,"versionNonce":1,"updated":1}],"files":{}}""".toByteArray()) }
    }

    private fun existe(d: Disco, rel: String) = File(d.filesDir, rel).isFile

    private fun textos(d: Disco, chat: String) = d.leerMensajes().filter { Disco.chatDe(it) == chat }.map { it.texto }.toSet()

    /**
     * Max con dos proyectos: «Tesis» (p1), que comparte, y «Privado» (p2), que no. Y algo en la
     * conversación general.
     */
    private fun montarMax() {
        lienzo(max, "tesis", "planta de la tesis")
        lienzo(max, "privado", "mis cuentas")
        max.guardarProyecto(Proyecto("p1", "Tesis", hojas = listOf(Hoja("h1", "Planta", dibujo = "tesis")), tocado = 5))
        max.guardarProyecto(Proyecto("p2", "Privado", hojas = listOf(Hoja("h2", "Cuentas", dibujo = "privado")), tocado = 5))
        val adjunto = File(max.filesDir, "guardados/memoria.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF memoria") }
        mensaje(max, "m1", "hola equipo", "p1")
        mensaje(max, "m2", "", "p1", Clase.ARCHIVO, ruta = adjunto.absolutePath, uid = "ADJUNTA234")
        // Lo anotado sobre el adjunto, que viaja con él.
        File(max.filesDir, "pins/draw/anot-ADJUNTA234.hoja").writeText("0,0,1,1")
        mensaje(max, "m3", "secreto del privado", "p2")
        mensaje(max, "m4", "nota de la general", null)
        File(max.filesDir, "guardados/nomina.pdf").writeText("%PDF nómina")
        mensaje(max, "m5", "", "p2", Clase.ARCHIVO, ruta = File(max.filesDir, "guardados/nomina.pdf").absolutePath)
        val c = CompartidosEnDisco(max.filesDir)
        c.poner(Compartidos.crear("p1", "Tesis", c.yo(max.identidad.leer()), reloj++))
    }

    /**
     * Un aparato que escucha como lo hace [Presencia]: mira la magia y atiende a una persona o una
     * invitación (si se le da un [codigo]). Atiende [veces] conexiones y se cierra.
     */
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

    /** Ana entra con el código que le enseña Max y se trae el chat, como en la pantalla. */
    private fun anaEntra(): VueltaConPersona.Resultado {
        val (server, hilo) = escuchar(max, "K7Q2M9XP", "p1", veces = 2)
        val entrada = conectar(server) { s -> Invitacion.entrar(s.getInputStream(), s.getOutputStream(), ana, "K7Q2M9XP", ahora = { reloj }) }
        assertEquals("p1", entrada.chat.chat)
        assertEquals("id-max", entrada.quien.id)
        val r = conectar(server) { s -> VueltaConPersona(ana, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream(), aQuien = "id-max") }
        hilo.join(5000)
        return r
    }

    /** Una vuelta normal entre dos personas: [desde] llama a [hacia]. */
    private fun vuelta(desde: Disco, hacia: Disco): VueltaConPersona.Resultado {
        val (server, hilo) = escuchar(hacia)
        val r = conectar(server) { s -> VueltaConPersona(desde, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream()) }
        hilo.join(5000)
        return r
    }

    // ------------------------------------------------------------------ pruebas

    @Test
    fun `el chat compartido viaja a la otra persona y nada mas`() {
        montarMax()
        val r = anaEntra()
        assertEquals(listOf("p1"), r.chats)
        // Lo del chat compartido: sus mensajes, su proyecto, su lienzo, el adjunto y lo anotado.
        assertEquals(setOf("hola equipo", ""), textos(ana, "p1"))
        assertEquals(listOf("p1"), ana.leerProyectos().map { it.id })
        assertTrue(existe(ana, "pins/draw/tesis.excalidraw.gz"))
        assertTrue(existe(ana, "guardados/memoria.pdf"))
        assertTrue(existe(ana, "pins/draw/anot-ADJUNTA234.hoja"))
        // Y nada de lo demás: ni el otro proyecto, ni su lienzo, ni su adjunto, ni la general.
        assertTrue(textos(ana, "p2").isEmpty())
        assertTrue(textos(ana, Disco.GENERAL).isEmpty())
        assertFalse(existe(ana, "pins/draw/privado.excalidraw.gz"))
        assertFalse(existe(ana, "guardados/nomina.pdf"))
        // Ana no entró en el grupo de Max ni sabe su código.
        assertNull(ana.identidad.leer().codigo)
        assertTrue(ana.identidad.leer().miembros.isEmpty())
        // Los dos se ven dentro del chat.
        val enAna = CompartidosEnDisco(ana.filesDir).de("p1")!!
        assertEquals(setOf("id-max", "id-ana"), enAna.personas.filter { it.dentro }.map { it.id }.toSet())
        assertEquals("Ana", CompartidosEnDisco(max.filesDir).de("p1")!!.personas.first { it.id == "id-ana" }.nombre)
    }

    @Test
    fun `lo que escribe Ana llega a Max con su nombre y al reves`() {
        montarMax()
        anaEntra()
        mensaje(ana, "a1", "he revisado la planta", "p1")
        mensaje(max, "m6", "gracias", "p1")
        vuelta(ana, max)
        assertTrue("he revisado la planta" in textos(max, "p1"))
        assertTrue("gracias" in textos(ana, "p1"))
        // Quién lo escribió: por el código de su aparato.
        val autores = Compartidos.autores(CompartidosEnDisco(max.filesDir).de("p1")!!)
        val deAna = max.leerMensajes().first { it.texto == "he revisado la planta" }
        assertEquals("Ana", autores[deAna.aparato])
        // Y la vuelta al revés (Max llama a Ana) también va.
        mensaje(max, "m7", "otra más", "p1")
        vuelta(max, ana)
        assertTrue("otra más" in textos(ana, "p1"))
        assertTrue(textos(ana, "p2").isEmpty())
    }

    @Test
    fun `un chat no compartido no viaja aunque se pida`() {
        montarMax()
        anaEntra()
        val (server, hilo) = escuchar(max)
        conectar(server) { s ->
            val sesion = Sesion.conectarConPersona(s.getInputStream(), s.getOutputStream(), ana, ahora = { reloj })
            try {
                // El catálogo solo enseña el compartido.
                assertEquals(listOf("p1"), sesion.catalogo().map { it.id })
                // Pedir el otro proyecto por su id: no.
                try { sesion.infoDe("p2", "pins/draw/privado.excalidraw.gz"); fail("No debería dar nada de p2") } catch (_: Protocolo.ErrorDelOtro) {}
                // Ni la general.
                try { sesion.infoDe(Disco.GENERAL, "guardados/nomina.pdf"); fail("No debería dar nada de la general") } catch (_: Protocolo.ErrorDelOtro) {}
                // Ni colarlo por el chat compartido, a mano.
                assertNull(sesion.infoDe("p1", "pins/draw/privado.excalidraw.gz"))
                // Ni la galería ni las lápidas del aparato.
                assertTrue(sesion.lapidas().isEmpty())
            } finally {
                sesion.adios()
            }
        }
        hilo.join(5000)
    }

    @Test
    fun `pedir a mano un archivo de otro chat se rechaza`() {
        montarMax()
        anaEntra()
        val (server, hilo) = escuchar(max)
        conectar(server) { s ->
            val llaves = CompartidosEnDisco(ana.filesDir).llaves()
            val (canal, _) = Canal.abrirEntrePersonas(s.getInputStream(), s.getOutputStream(), llaves, inicia = true) { true }
            Protocolo.enviar(canal, Protocolo.Peticion("hola", hola = Protocolo.Hola(Aparato("id-ana", "Ana"), reloj = reloj)))
            Protocolo.leerRespuesta(canal)
            for (rel in listOf("pins/draw/privado.excalidraw.gz", "guardados/nomina.pdf", "guardados/memoria.pdf")) {
                Protocolo.enviar(canal, Protocolo.Peticion("dame", chat = "p1", ruta = rel))
                val r = Protocolo.leerRespuestaSinLanzar(canal)
                if (rel == "guardados/memoria.pdf") {
                    // El del chat compartido sí: es la prueba de que la puerta funciona y no está cerrada a todo.
                    assertNull(r.error)
                    Protocolo.recibirTrozos(canal, r.bytes, java.io.OutputStream.nullOutputStream()) {}
                } else assertNotNull("Se dio $rel", r.error)
            }
            // Escribir encima de un archivo de otro chat, tampoco.
            val pisar = "basura".toByteArray()
            Protocolo.enviar(canal, Protocolo.Peticion("pon", chat = "p1", ruta = "pins/draw/privado.excalidraw.gz", bytes = pisar.size.toLong()))
            canal.enviar(Protocolo.TROZO, pisar)
            Protocolo.enviar(canal, Protocolo.Respuesta(resumen = sha256(pisar)))
            assertNotNull(Protocolo.leerRespuestaSinLanzar(canal).error)
            // Ni pedir la galería ni borrar proyectos.
            Protocolo.enviar(canal, Protocolo.Peticion("borrarchat", chat = "p2"))
            assertNotNull(Protocolo.leerRespuestaSinLanzar(canal).error)
            Protocolo.enviar(canal, Protocolo.Peticion("adios"))
            Protocolo.leerRespuesta(canal)
        }
        hilo.join(5000)
        assertTrue(existe(max, "pins/draw/privado.excalidraw.gz"))
        assertEquals(listOf("p1", "p2"), max.leerProyectos().map { it.id })
        assertTrue("secreto del privado" in textos(max, "p2"))
    }

    @Test
    fun `quien no es miembro es rechazado`() {
        montarMax()
        anaEntra()
        // Eva se ha hecho con la lista del chat (con la clave de Max) y se ha puesto ella misma.
        val evaC = CompartidosEnDisco(eva.filesDir)
        val yoEva = evaC.yo(eva.identidad.leer())
        val robada = CompartidosEnDisco(max.filesDir).de("p1")!!
        val falsa = Persona(yoEva.id, "Eva", yoEva.codigo, yoEva.clave, reloj, invito = "id-max", firma = yoEva.llaves.firmar("x".toByteArray()))
        evaC.guardar(listOf(robada.copy(personas = robada.personas + falsa)))
        val (server, hilo) = escuchar(max)
        try {
            conectar(server) { s -> VueltaConPersona(eva, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream()) }
            fail("Eva no debería poder hablar con Max")
        } catch (e: java.io.IOException) {
            // Bien: Max no la conoce por su clave y corta antes de hablar.
        }
        hilo.join(5000)
        assertTrue(eva.leerMensajes().isEmpty())
        assertTrue(eva.leerProyectos().isEmpty())
        // Y la lista de Max no se ha creído lo de Eva.
        assertFalse(CompartidosEnDisco(max.filesDir).de("p1")!!.personas.any { it.id == "id-eva" })
        // Ni haciéndose pasar por Ana (su id, otra clave).
        val evaComoAna = Disco(File(raiz, "eva2/files").apply { mkdirs() })
        evaComoAna.identidad.guardar(Identidad(Aparato("id-ana", "Ana")))
        CompartidosEnDisco(evaComoAna.filesDir).guardar(CompartidosEnDisco(ana.filesDir).leer())
        val (server2, hilo2) = escuchar(max)
        try {
            conectar(server2) { s -> VueltaConPersona(evaComoAna, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream()) }
            fail("Con otra clave no debería pasar por Ana")
        } catch (e: java.io.IOException) {
        }
        hilo2.join(5000)
        assertTrue(evaComoAna.leerMensajes().isEmpty())
    }

    @Test
    fun `una invitacion con otro codigo no entra`() {
        montarMax()
        val (server, hilo) = escuchar(max, "K7Q2M9XP", "p1")
        try {
            conectar(server) { s -> Invitacion.entrar(s.getInputStream(), s.getOutputStream(), ana, "ZZZZ9999", ahora = { reloj }) }
            fail("Con otro código no debería entrar")
        } catch (e: java.io.IOException) {
        }
        hilo.join(5000)
        assertNull(CompartidosEnDisco(ana.filesDir).de("p1"))
        assertFalse(CompartidosEnDisco(max.filesDir).de("p1")!!.personas.any { it.id == "id-ana" })
    }

    @Test
    fun `un mensaje que nombra un archivo de otro chat no lo saca`() {
        montarMax()
        anaEntra()
        // Ana mete en el chat compartido mensajes que señalan el lienzo y el adjunto privados de Max.
        mensaje(ana, "trampa1", "mira", "p1", Clase.DIBUJO, referencia = "privado")
        mensaje(ana, "trampa2", "", "p1", Clase.ARCHIVO, ruta = File(ana.filesDir, "guardados/nomina.pdf").absolutePath)
        mensaje(ana, "a1", "esto sí", "p1")
        vuelta(ana, max)
        vuelta(max, ana)
        assertFalse(existe(ana, "pins/draw/privado.excalidraw.gz"))
        assertFalse(existe(ana, "guardados/nomina.pdf"))
        // Max no se los quedó; lo honrado, sí.
        assertTrue("esto sí" in textos(max, "p1"))
        assertFalse(max.leerMensajes().any { it.id == "trampa1" || it.id == "trampa2" })
        // Y si luego Ana «borra» un mensaje que dice ser el adjunto privado, no se lleva nada de Max.
        assertTrue(existe(max, "guardados/nomina.pdf"))
    }

    @Test
    fun `quitar a una persona corta la sincronizacion con ella`() {
        montarMax()
        anaEntra()
        val c = CompartidosEnDisco(max.filesDir)
        c.poner(Compartidos.quitar(c.de("p1")!!, c.yo(max.identidad.leer()), "id-ana", reloj++))
        mensaje(max, "m9", "ya no lo ves", "p1")
        mensaje(ana, "a9", "ni tú esto", "p1")
        val r = vuelta(ana, max)
        // Ana se entera de que ya no está, y no pasa nada más.
        assertTrue(r.chats.isEmpty())
        assertFalse(Compartidos.dentro(CompartidosEnDisco(ana.filesDir).de("p1")!!, "id-ana"))
        assertFalse("ya no lo ves" in textos(ana, "p1"))
        assertFalse("ni tú esto" in textos(max, "p1"))
        // Lo que ya tenía se le queda.
        assertTrue("hola equipo" in textos(ana, "p1"))
    }

    @Test
    fun `quien sale deja de recibir y los demas se enteran`() {
        montarMax()
        anaEntra()
        val c = CompartidosEnDisco(ana.filesDir)
        c.poner(Compartidos.quitar(c.de("p1")!!, c.yo(ana.identidad.leer()), "id-ana", reloj++))
        mensaje(max, "m9", "después de irse", "p1")
        vuelta(ana, max)
        assertFalse(Compartidos.dentro(CompartidosEnDisco(max.filesDir).de("p1")!!, "id-ana"))
        assertFalse("después de irse" in textos(ana, "p1"))
    }

    @Test
    fun `el grupo de Max lleva el chat compartido a su tableta y Ana la acepta`() {
        montarMax()
        anaEntra()
        // La tableta de Max se une a su grupo y sincroniza con el teléfono.
        val tableta = Disco(File(raiz, "tableta-de-max/files").apply { mkdirs() })
        tableta.identidad.guardar(Identidad(Aparato("id-max-tab", "Tableta de Max")))
        fun conGrupo(desde: Disco, hacia: Disco, unirme: Boolean = false, uso: (Sesion) -> Unit) {
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val hilo = Thread { runCatching { server.accept().use { s -> Respondedor(hacia, ahora = { reloj }).atender(s.getInputStream(), s.getOutputStream()) } }; server.close() }
            hilo.start()
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), desde, unirme, if (unirme) "ABCDE23456" else null, ahora = { reloj })
                uso(sesion); sesion.adios()
            }
            hilo.join(5000)
        }
        conGrupo(tableta, max, unirme = true) {}
        conGrupo(tableta, max) { s ->
            assertTrue(s.compartidosDelGrupo(reloj))
            val hecho = Sesion.Hecho()
            val prep = s.preparar("p1"); s.aplicar(prep, hecho); s.aplicarArchivos(s.prepararArchivos(prep), hecho); s.cerrar(prep)
        }
        val enTableta = CompartidosEnDisco(tableta.filesDir).de("p1")!!
        assertTrue(Compartidos.dentro(enTableta, "id-max-tab"))
        assertTrue(Compartidos.dentro(CompartidosEnDisco(max.filesDir).de("p1")!!, "id-max-tab"))
        // El teléfono le cuenta a Ana que la tableta está; luego la tableta habla con Ana directamente.
        vuelta(max, ana)
        assertTrue(Compartidos.dentro(CompartidosEnDisco(ana.filesDir).de("p1")!!, "id-max-tab"))
        mensaje(tableta, "t1", "desde la tableta", "p1")
        vuelta(tableta, ana)
        assertTrue("desde la tableta" in textos(ana, "p1"))
        assertTrue(textos(ana, "p2").isEmpty())
    }
}
