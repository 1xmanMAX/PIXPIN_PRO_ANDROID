package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.planos.LetrasQueViajan
import java.io.File
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Las letras de los planos viajan entre los aparatos propios** (14-oct-2026), con dos aparatos de
 * mentira que se sincronizan de verdad, como `SincronizarDeVerdadTest`. Son las pruebas del PC
 * (`apps/pixpin/src/letras_que_viajan/pruebas.rs`) traducidas: ida y vuelta sin re-mandar, una
 * letra cambiada, el borrado en los dos sentidos, el chat compartido (una persona ni las recibe ni
 * puede meter una) y lo que no es una letra por dentro. Letras de prueba: las de Hershey de PixPin
 * (libres, `assets/fuentes-shx`) y cabeceras TrueType sintéticas.
 */
class LetrasQueViajanDeVerdadTest {

    private lateinit var raiz: File
    private lateinit var telefono: Disco
    private lateinit var tableta: Disco
    private var reloj = 1_000_000L

    private val romans = File("src/main/assets/fuentes-shx/romans.shx").readBytes()
    private val romand = File("src/main/assets/fuentes-shx/romand.shx").readBytes()
    private val greeks = File("src/main/assets/fuentes-shx/greeks.shx").readBytes()

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("letras").toFile()
        telefono = Disco(File(raiz, "telefono/files").apply { mkdirs() })
        tableta = Disco(File(raiz, "tableta-de-otro-usuario/data/files").apply { mkdirs() })
        telefono.identidad.guardar(Identidad(Aparato("id-tel", "Teléfono")))
        tableta.identidad.guardar(Identidad(Aparato("id-tab", "Tableta")))
    }

    @After
    fun desmontar() {
        raiz.deleteRecursively()
    }

    // ------------------------------------------------------------ utilidades

    private fun escuchar(d: Disco): Pair<ServerSocket, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching { server.accept().use { s -> Respondedor(d, ahora = { reloj }).atender(s.getInputStream(), s.getOutputStream()) } }
            server.close()
        }
        hilo.start()
        return server to hilo
    }

    private fun <T> conectado(desde: Disco, hacia: Disco, unirme: Boolean = false, codigo: String? = null, uso: (Sesion) -> T): T {
        val (server, hilo) = escuchar(hacia)
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), desde, unirme, codigo, ahora = { reloj })
            val r = uso(sesion)
            sesion.adios()
            hilo.join(5000)
            return r
        }
    }

    /** Los dos en el mismo grupo, y el chat «Asistente» (del teléfono) en los dos. */
    private fun emparejar() {
        val codigo = telefono.crearGrupo(codigo = "ABCDE23456", ahora = reloj)
        conectado(tableta, telefono, unirme = true, codigo = codigo) {}
        telefono.guardarProyecto(Proyecto(CHAT, "Asistente", tocado = reloj++, asistente = true))
        sincronizar(telefono, tableta)
        assertEquals(CHAT, LetrasQueViajan.chatDelAsistente(tableta))
    }

    /** Una vuelta del chat «Asistente», empezada por [desde]: lo cambiado en los dos se junta. */
    private fun sincronizar(desde: Disco, hacia: Disco): Sesion.Hecho {
        reloj += 10
        val hecho = Sesion.Hecho()
        conectado(desde, hacia) { s ->
            val prep = s.preparar(CHAT)
            s.aplicar(prep, hecho)
            s.aplicarArchivos(s.prepararArchivos(prep), hecho)
            s.cerrar(prep)
        }
        return hecho
    }

    private fun carpeta(d: Disco) = LetrasQueViajan.carpeta(d.filesDir)
    private fun letra(d: Disco, nombre: String) = File(carpeta(d), nombre)

    /** Lo que hace «Aa» → «Añadir letras…»: copia lo que es letra por dentro y la señala. Devuelve cuántas. */
    private fun anadir(d: Disco, elegidas: List<File>): Int {
        val n = elegidas.count { f ->
            LetrasQueViajan.pareceLetra(f) && runCatching { f.copyTo(File(carpeta(d).apply { mkdirs() }, f.name), overwrite = true) }.isSuccess
        }
        LetrasQueViajan.senalar(d, reloj++) { LetrasQueViajan.chatDelAsistente(d) }
        return n
    }

    /** Unas letras fuera de la carpeta (las que elige el usuario), con una trampa. */
    private fun elegidas(): List<File> {
        val d = File(raiz, "descargas").apply { mkdirs() }
        File(d, "romans.shx").writeBytes(romans)
        // Una TrueType sintética: solo su cabecera.
        File(d, "Libre.ttf").writeBytes(byteArrayOf(0, 1, 0, 0, 0, 4, 0, 0))
        // Caso negativo: un programa rebautizado.
        File(d, "trampa.shx").writeBytes("MZ\u0090\u0000esto no es una letra".toByteArray(Charsets.ISO_8859_1))
        return listOf(File(d, "romans.shx"), File(d, "Libre.ttf"), File(d, "trampa.shx"))
    }

    private fun idsDeLetras(d: Disco): List<String> =
        d.leerMensajes().map { it.id }.filter { it.startsWith(LetrasQueViajan.PREFIJO_ID) }.sorted()

    /** El otro aparato pone una «letra» a mano, mensaje incluido, aunque no lo sea. */
    private fun letraAMano(d: Disco, nombre: String, bytes: ByteArray) {
        val f = letra(d, nombre).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        meter(d, Mensaje(
            id = LetrasQueViajan.idDe(nombre), cuando = reloj++, clase = Clase.ARCHIVO, ruta = f.absolutePath,
            nombre = nombre, bytes = bytes.size.toLong(), proyecto = CHAT,
            numero = d.leerMensajes().size + 1, aparato = d.identidad.leer().yo.codigo
        ))
    }

    private fun meter(d: Disco, m: Mensaje) =
        File(d.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")

    /** Como hace el chat al borrar un mensaje: fuera, su marca y su adjunto. */
    private fun borrarDelChat(d: Disco, id: String) {
        val antes = d.leerMensajes()
        val despues = antes.filter { it.id != id }
        File(d.filesDir, "guardados.jsonl").writeText(despues.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
        d.anotarBorrados(Disco.borradosEntre(antes, despues, d.identidad.leer().letra, reloj++))
        antes.first { it.id == id }.ruta?.let { File(it).delete() }
    }

    /** La huella de las letras como la mira el visor abierto ([com.forge.pixpin.planos.LectorDePlanos.huellaDeLetras]). */
    private fun huella(d: Disco) = LetrasQueViajan.letrasEn(carpeta(d)).joinToString("|") { it.name.lowercase() + ":" + it.length() }

    // ------------------------------------------------------------------ pruebas

    /**
     * **Ida y vuelta**: el teléfono añade (lo que no es letra no entra), llega a la tableta donde ella
     * la lee; la tableta añade la suya y llega al teléfono (la huella cambia: el visor relee). Otra
     * vuelta no manda nada; una letra cambiada viaja sin mensaje nuevo.
     */
    @Test
    fun `las letras van al otro aparato y vuelven sin re-mandar`() {
        emparejar()
        assertEquals("la trampa no entra", 2, anadir(telefono, elegidas()))
        assertFalse(letra(telefono, "trampa.shx").exists())
        val h = sincronizar(telefono, tableta)
        assertTrue("$h", h.archivos >= 2)
        assertArrayEquals(romans, letra(tableta, "romans.shx").readBytes())
        assertTrue(letra(tableta, "Libre.ttf").isFile)
        assertEquals(listOf(LetrasQueViajan.idDe("libre.ttf"), LetrasQueViajan.idDe("romans.shx")), idsDeLetras(tableta))
        val m = tableta.leerMensajes().first { it.id == LetrasQueViajan.idDe("romans.shx") }
        assertEquals("con la ruta de la tableta", letra(tableta, "romans.shx").absolutePath, m.ruta)
        assertEquals(CHAT, m.proyecto)

        // La de la tableta llega a la carpeta del teléfono.
        val antes = huella(telefono)
        File(carpeta(tableta), "greeks.shx").writeBytes(greeks)
        assertEquals(1, LetrasQueViajan.senalar(tableta, reloj++) { LetrasQueViajan.chatDelAsistente(tableta) })
        sincronizar(tableta, telefono)
        assertArrayEquals(greeks, letra(telefono, "greeks.shx").readBytes())
        assertTrue("el visor abierto relee", huella(telefono) != antes)

        // Otra vuelta: nada que mandar. Volver a señalar no duplica.
        val otra = sincronizar(telefono, tableta)
        assertEquals("sin re-mandar: $otra", 0, otra.archivos)
        assertEquals(0, LetrasQueViajan.senalar(telefono, reloj++) { CHAT })

        // Cambiada en el teléfono (mismo nombre): viaja el archivo, no un mensaje nuevo.
        val d = File(raiz, "otra").apply { mkdirs() }
        File(d, "romans.shx").writeBytes(romand)
        assertEquals(1, anadir(telefono, listOf(File(d, "romans.shx"))))
        val cambio = sincronizar(telefono, tableta)
        assertEquals("$cambio", 1, cambio.archivos)
        assertArrayEquals(romand, letra(tableta, "romans.shx").readBytes())
        assertEquals(3, idsDeLetras(tableta).size)
        assertEquals(idsDeLetras(telefono), idsDeLetras(tableta))
    }

    /** **Borrado en los dos sentidos**: la tableta quita una y se va del teléfono; «Quitar las mías» se lleva todas. */
    @Test
    fun `quitar una letra la quita en el otro aparato`() {
        emparejar()
        anadir(telefono, elegidas())
        File(carpeta(tableta).apply { mkdirs() }, "greeks.shx").writeBytes(greeks)
        LetrasQueViajan.senalar(tableta, reloj++) { CHAT }
        sincronizar(tableta, telefono)
        assertTrue(letra(telefono, "greeks.shx").isFile && letra(tableta, "romans.shx").isFile)

        borrarDelChat(tableta, LetrasQueViajan.idDe("romans.shx"))
        sincronizar(tableta, telefono)
        assertFalse("se fue con su mensaje", letra(telefono, "romans.shx").exists())
        assertTrue("las otras siguen", letra(telefono, "greeks.shx").isFile && letra(telefono, "Libre.ttf").isFile)

        assertEquals(2, LetrasQueViajan.quitarTodas(telefono, reloj++))
        assertTrue(LetrasQueViajan.letrasEn(carpeta(telefono)).isEmpty())
        sincronizar(telefono, tableta)
        assertTrue(idsDeLetras(tableta).isEmpty())
        assertFalse(letra(tableta, "greeks.shx").exists() || letra(tableta, "Libre.ttf").exists())
        // Y no vuelven: otra vuelta desde la tableta no las resucita.
        sincronizar(tableta, telefono)
        assertTrue(LetrasQueViajan.letrasEn(carpeta(telefono)).isEmpty())
        assertTrue(idsDeLetras(telefono).isEmpty())
    }

    /** **Quitar una desde la franja del chat «Asistente»**: se va su archivo, y abrir un plano no la vuelve a poner. */
    @Test
    fun `quitar una desde la franja no vuelve al abrir un plano`() {
        emparejar()
        anadir(telefono, elegidas())
        assertTrue(LetrasQueViajan.quitarUna(telefono, reloj++, LetrasQueViajan.idDe("romans.shx")))
        assertFalse(letra(telefono, "romans.shx").exists())
        assertTrue("la otra sigue", letra(telefono, "Libre.ttf").isFile)
        LetrasQueViajan.alAbrir(telefono, reloj++) { CHAT }
        assertEquals(listOf(LetrasQueViajan.idDe("Libre.ttf")), idsDeLetras(telefono))
        sincronizar(telefono, tableta)
        assertFalse(letra(tableta, "romans.shx").exists())
        assertTrue(letra(tableta, "Libre.ttf").isFile)
    }

    /**
     * **Lo que no es una letra**: llega de otro aparato con nombre de letra y no lo es por dentro, o
     * es un mensaje de letra que señala otra cosa. Se rechaza al abrir un plano (archivo y mensaje)
     * y el rechazo vuelve al otro aparato. Un mensaje falso que señala un adjunto de otro chat se
     * va, pero el adjunto se queda.
     */
    @Test
    fun `un archivo que no es una letra se rechaza en los dos aparatos`() {
        emparejar()
        anadir(telefono, elegidas())
        sincronizar(telefono, tableta)
        letraAMano(tableta, "falsa.shx", "MZ\u0090\u0000programa".toByteArray(Charsets.ISO_8859_1))
        letraAMano(tableta, "leeme.txt", "hola".toByteArray())
        sincronizar(tableta, telefono)
        assertTrue(letra(telefono, "falsa.shx").isFile)
        // En el teléfono, un adjunto de otro chat y un mensaje «de letra» que lo señala.
        val memoria = File(telefono.filesDir, "guardados/memoria.pdf").apply { writeText("%PDF memoria") }
        meter(telefono, Mensaje(id = "m-memoria", cuando = reloj++, clase = Clase.ARCHIVO, ruta = memoria.absolutePath, nombre = "memoria.pdf", proyecto = "obra", numero = 1))
        meter(telefono, Mensaje(id = LetrasQueViajan.idDe("memoria.shx"), cuando = reloj++, clase = Clase.ARCHIVO, ruta = memoria.absolutePath, nombre = "memoria.shx", proyecto = CHAT, numero = 40))

        assertTrue(LetrasQueViajan.limpiar(telefono, reloj++) >= 3)
        assertFalse(letra(telefono, "falsa.shx").exists())
        assertFalse(letra(telefono, "leeme.txt").exists())
        assertTrue("las buenas siguen", letra(telefono, "romans.shx").isFile)
        assertTrue("el adjunto de otro chat no se toca", memoria.isFile)
        assertEquals(listOf(LetrasQueViajan.idDe("libre.ttf"), LetrasQueViajan.idDe("romans.shx")), idsDeLetras(telefono))

        sincronizar(telefono, tableta)
        assertFalse(letra(tableta, "falsa.shx").exists() || letra(tableta, "leeme.txt").exists())
        assertEquals(listOf(LetrasQueViajan.idDe("libre.ttf"), LetrasQueViajan.idDe("romans.shx")), idsDeLetras(tableta))
    }

    @Test
    fun `los topes y los nombres que no viajan se quedan en este aparato`() {
        val c = carpeta(telefono).apply { mkdirs() }
        val grande = ByteArray((LetrasQueViajan.TOPE_POR_LETRA + 1).toInt()).also { byteArrayOf(0, 1, 0, 0).copyInto(it) }
        File(c, "enorme.ttf").writeBytes(grande)
        File(c, "rara (1).shx").writeBytes(romans)
        File(c, "romans.shx").writeBytes(romans)
        telefono.guardarProyecto(Proyecto(CHAT, "Asistente", tocado = 5, asistente = true))
        assertEquals(1, LetrasQueViajan.senalar(telefono, 5) { CHAT })
        assertEquals(listOf(LetrasQueViajan.idDe("romans.shx")), idsDeLetras(telefono))
        // Siguen en la carpeta: aquí se usan igual.
        assertEquals(3, LetrasQueViajan.letrasEn(c).size)
        // Sin letras, ni se busca (ni se crea) el chat «Asistente».
        var pedido = false
        assertEquals(0, LetrasQueViajan.senalar(tableta, 6) { pedido = true; CHAT })
        assertFalse(pedido)
    }

    @Test
    fun `la carpeta de antes pasa a la que viaja una vez`() {
        val antes = LetrasQueViajan.carpetaDeAntes(telefono.filesDir).apply { mkdirs() }
        File(antes, "romans.shx").writeBytes(romans)
        File(antes, "ARIALN.TTF").writeBytes(byteArrayOf(0, 1, 0, 0))
        File(carpeta(telefono).apply { mkdirs() }, "ARIALN.TTF").writeBytes(byteArrayOf(0, 1, 0, 0, 9))
        assertEquals(1, LetrasQueViajan.moverLaDeAntes(telefono.filesDir))
        assertFalse(antes.exists())
        assertArrayEquals(romans, letra(telefono, "romans.shx").readBytes())
        assertEquals("manda la nueva", 5L, letra(telefono, "ARIALN.TTF").length())
        assertEquals(0, LetrasQueViajan.moverLaDeAntes(telefono.filesDir))
        // Y es una carpeta que viaja.
        assertTrue(telefono.permitida("guardados/fuentes-shx/romans.shx"))
    }

    // ------------------------------------------------------- con una persona

    /**
     * **Un chat compartido no es un camino para las letras.** Max tiene sus letras en el chat
     * «Asistente» y comparte «Tesis» con Ana: Ana no las recibe, no puede pedirlas ni escribirlas, y
     * una «letra» que ella meta en el chat compartido no llega a la carpeta de Max.
     */
    @Test
    fun `ni a una persona ni de una persona`() {
        val max = telefono
        val ana = Disco(File(raiz, "ana-otra-ruta/data/files").apply { mkdirs() })
        ana.identidad.guardar(Identidad(Aparato("id-ana", "Móvil de Ana")))
        max.crearGrupo(codigo = "ABCDE23456", ahora = reloj)
        CompartidosEnDisco(max.filesDir).ponerNombre("Max")
        CompartidosEnDisco(ana.filesDir).ponerNombre("Ana")
        max.guardarProyecto(Proyecto(CHAT, "Asistente", tocado = 5, asistente = true))
        max.guardarProyecto(Proyecto("p1", "Tesis", tocado = 5))
        meter(max, Mensaje(id = "m1", cuando = reloj++, clase = Clase.NOTA, texto = "hola equipo", proyecto = "p1", numero = 1, aparato = max.identidad.leer().yo.codigo))
        anadir(max, elegidas())
        val c = CompartidosEnDisco(max.filesDir)
        c.poner(Compartidos.crear("p1", "Tesis", c.yo(max.identidad.leer()), reloj++))

        // Ana entra con el código que le enseña Max.
        val (server, hilo) = escucharPersona(max, "K7Q2M9XP", "p1", veces = 2)
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s -> Invitacion.entrar(s.getInputStream(), s.getOutputStream(), ana, "K7Q2M9XP", ahora = { reloj }) }
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s -> VueltaConPersona(ana, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream(), aQuien = "id-tel") }
        hilo.join(5000)
        assertTrue(ana.leerMensajes().any { it.texto == "hola equipo" })
        assertTrue("las letras no van a una persona", LetrasQueViajan.letrasEn(carpeta(ana)).isEmpty())
        assertTrue(idsDeLetras(ana).isEmpty())

        // Para Ana, la carpeta de las letras de Max está vedada: ni leer ni escribir, ni una nueva.
        val acceso = AccesoDePersona(max, CompartidosEnDisco(max.filesDir), "id-tel", "id-ana")
        assertTrue(acceso.prohibidos().contains("guardados/fuentes-shx/romans.shx"))
        assertFalse(acceso.puedeEscribir("p1", "guardados/fuentes-shx/romans.shx"))
        assertFalse(acceso.puedeEscribir("p1", "guardados/fuentes-shx/nueva.shx"))
        assertFalse(acceso.puedeLeer("p1", "guardados/fuentes-shx/romans.shx", setOf("guardados/fuentes-shx/romans.shx")))

        // Ana mete en el chat compartido una «letra» suya (una SHX de verdad) y una nota honrada.
        val suya = letra(ana, "colada.shx").apply { parentFile!!.mkdirs(); writeBytes(greeks) }
        meter(ana, Mensaje(id = "a1", cuando = reloj++, clase = Clase.ARCHIVO, ruta = suya.absolutePath, nombre = "colada.shx", proyecto = "p1", numero = 2, aparato = ana.identidad.leer().yo.codigo))
        meter(ana, Mensaje(id = "a2", cuando = reloj++, clase = Clase.NOTA, texto = "esto sí", proyecto = "p1", numero = 3, aparato = ana.identidad.leer().yo.codigo))
        for (quien in listOf(ana, max)) {
            val otro = if (quien === ana) max else ana
            val (s2, h2) = escucharPersona(otro)
            Socket(InetAddress.getLoopbackAddress(), s2.localPort).use { s -> VueltaConPersona(quien, ahora = { reloj }).con(s.getInputStream(), s.getOutputStream()) }
            h2.join(5000)
        }
        assertTrue("lo honrado llega", max.leerMensajes().any { it.texto == "esto sí" })
        assertFalse("la letra colada no", max.leerMensajes().any { it.id == "a1" })
        assertFalse(letra(max, "colada.shx").exists())
        assertEquals(listOf("Libre.ttf", "romans.shx"), LetrasQueViajan.letrasEn(carpeta(max)).map { it.name })
        assertTrue(LetrasQueViajan.letrasEn(carpeta(ana)).none { it.name != "colada.shx" })
    }

    /** Un aparato que escucha como [Presencia]: a una persona o una invitación. */
    private fun escucharPersona(d: Disco, codigo: String? = null, chat: String? = null, veces: Int = 1): Pair<ServerSocket, Thread> {
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
                }.onFailure { it.printStackTrace() }
            }
            server.close()
        }
        hilo.start()
        return server to hilo
    }

    companion object {
        private const val CHAT = "pr-asistente"
    }
}
