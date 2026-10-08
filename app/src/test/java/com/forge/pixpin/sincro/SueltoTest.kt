package com.forge.pixpin.sincro

import com.forge.pixpin.motor.LienzoAlFrente
import com.forge.pixpin.motor.RecibeImagenes
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * **La foto del PC al lienzo del móvil** (`suelto`), contra el `Respondedor` de verdad por un
 * socket local, con el PC hecho a mano tal como lo describe su guía
 * (`docs/investigacion/2026-10-06-foto-al-lienzo-android.md` del repo de Windows).
 */
class SueltoTest {

    private lateinit var raiz: File
    private lateinit var movil: Disco
    private lateinit var cache: File
    private lateinit var codigo: String

    /** Lo que recibió el gancho: el contenido (el fichero se borra al acabar), el nombre y el tipo. */
    private val llegadas = java.util.Collections.synchronizedList(mutableListOf<Triple<ByteArray, String, String>>())

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("suelto").toFile()
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Pixel")))
        codigo = movil.crearGrupo(codigo = "ABCDE23456", ahora = 1_000L)
        cache = File(raiz, "cache")
    }

    @After
    fun desmontar() {
        raiz.deleteRecursively()
    }

    private val alLienzo: (File, String, String, Aparato) -> Pair<String, String?> = { f, nombre, mime, _ ->
        llegadas += Triple(f.readBytes(), nombre, mime)
        Protocolo.SUELTO_EN_EL_LIENZO to null
    }

    /** Un lienzo delante, como lo decide `Presencia`: lo que no es foto se niega antes del vale. */
    private val conUnLienzo: (String, String) -> String? = { _, mime -> LoAbierto.aceptar(LoAbierto.Delante.LIENZO, mime, "Pixel") }

    /** El PC: abre el canal, saluda, hace lo que diga [uso] y se despide. */
    private fun comoElPc(
        gancho: ((File, String, String, Aparato) -> Pair<String, String?>)?,
        aceptar: ((String, String) -> String?)? = null,
        uso: (Canal) -> Unit
    ) {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        var fallo: Throwable? = null
        val hilo = Thread {
            try {
                server.accept().use { s ->
                    Respondedor(movil, alAceptarSuelto = aceptar, alRecibirSuelto = gancho, cache = if (gancho != null) cache else null)
                        .atender(s.getInputStream(), s.getOutputStream())
                }
            } catch (e: Throwable) {
                fallo = e
            } finally {
                server.close()
            }
        }
        hilo.start()
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            val canal = Canal.abrir(s.getInputStream(), s.getOutputStream(), Grupo.clave(codigo), inicia = true)
            Protocolo.enviar(canal, Protocolo.Peticion("hola", hola = Protocolo.Hola(Aparato("id-pc", "PC de casa"))))
            val hola = Protocolo.leerRespuesta(canal)
            assertEquals(Protocolo.VERSION, hola.hola!!.version)
            uso(canal)
            Protocolo.enviar(canal, Protocolo.Peticion("adios"))
            assertNull("el adiós sigue sano", Protocolo.leerRespuesta(canal).error)
        }
        hilo.join(5000)
        fallo?.let { throw AssertionError("El móvil se cortó", it) }
    }

    /** La respuesta tal cual, sin lanzar si trae error. */
    private fun respuesta(c: Canal): Protocolo.Respuesta {
        val (tipo, datos) = c.recibir()
        assertEquals(Protocolo.JSON_, tipo)
        return Protocolo.json.decodeFromString(Protocolo.Respuesta.serializer(), datos.decodeToString())
    }

    private fun pedir(c: Canal, nombre: String, bytes: Long, mime: String = "image/png", destino: String = "abierto") =
        Protocolo.enviar(c, Protocolo.Peticion("suelto", nombre = nombre, mime = mime, bytes = bytes, destino = destino))

    /** Los trozos y la cola, como `Salida::de_fichero` del PC. */
    private fun mandar(c: Canal, datos: ByteArray, saltado: Boolean = false) {
        var i = 0
        while (i < datos.size) {
            val n = minOf(Canal.TOPE_DE_TRAMO, datos.size - i)
            c.enviar(Protocolo.TROZO, datos, i, n)
            i += n
        }
        val resumen = MessageDigest.getInstance("SHA-256").digest(datos).joinToString("") { "%02x".format(it) }
        Protocolo.enviar(c, Protocolo.Respuesta(resumen = resumen, saltado = saltado))
    }

    private fun bytes(n: Int, semilla: Int = 7) = ByteArray(n) { ((it * 31 + semilla) and 0xff).toByte() }

    @Test
    fun una_foto_llega_entera_y_va_al_lienzo() {
        val foto = bytes((1 shl 20) + 777)   // dos trozos
        comoElPc(alLienzo) { c ->
            pedir(c, "captura.png", foto.size.toLong())
            val vale = respuesta(c)
            assertNull(vale.error)
            mandar(c, foto)
            val listo = respuesta(c)
            assertNull(listo.error)
            assertEquals("listo", listo.t)
            assertEquals("lienzo", listo.donde)
        }
        assertEquals(1, llegadas.size)
        assertArrayEquals(foto, llegadas[0].first)
        assertEquals("captura.png", llegadas[0].second)
        assertEquals("image/png", llegadas[0].third)
        assertTrue("no queda nada en la caché", cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun varias_fotos_en_la_misma_conexion_llegan_en_orden() {
        val a = bytes(5000, 1)
        val b = bytes(9000, 2)
        comoElPc(alLienzo) { c ->
            for ((nombre, datos) in listOf("a.png" to a, "b.jpg" to b)) {
                pedir(c, nombre, datos.size.toLong())
                assertNull(respuesta(c).error)
                mandar(c, datos)
                assertEquals("listo", respuesta(c).t)
            }
        }
        assertEquals(listOf("a.png", "b.jpg"), llegadas.map { it.second })
        assertArrayEquals(a, llegadas[0].first)
        assertArrayEquals(b, llegadas[1].first)
    }

    @Test
    fun el_nombre_se_sanea_antes_de_tocar_el_disco() {
        comoElPc(alLienzo) { c ->
            pedir(c, "../../fuera/x.png", 3)
            assertNull(respuesta(c).error)
            mandar(c, byteArrayOf(1, 2, 3))
            assertEquals("listo", respuesta(c).t)
        }
        assertEquals(".._.._fuera_x.png", llegadas.single().second)
        assertTrue(File(raiz, "fuera").let { !it.exists() })
    }

    @Test
    fun sin_gancho_se_contesta_como_un_pixpin_de_antes_y_la_conversacion_sigue() {
        comoElPc(null) { c ->
            pedir(c, "c.png", 5)
            assertEquals("No sé qué es «suelto»", respuesta(c).error)
            // Sin trozos: lo siguiente es el adiós, y tiene que ir bien.
        }
        assertTrue(llegadas.isEmpty())
    }

    @Test
    fun demasiado_grande_no_pide_trozos() {
        comoElPc(alLienzo) { c ->
            pedir(c, "enorme.mp4", (2L shl 30) + 1, "video/mp4")
            assertEquals("El archivo es demasiado grande", respuesta(c).error)
            pedir(c, "negativa.png", -1)
            assertEquals("El archivo es demasiado grande", respuesta(c).error)
        }
        assertTrue(llegadas.isEmpty())
    }

    @Test
    fun saltado_no_llama_al_gancho_ni_deja_fichero() {
        comoElPc(alLienzo) { c ->
            pedir(c, "c.png", 4)
            assertNull(respuesta(c).error)
            mandar(c, byteArrayOf(1, 2, 3, 4), saltado = true)
            assertTrue(respuesta(c).error!!.contains("cambió"))
        }
        assertTrue(llegadas.isEmpty())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun si_guardarlo_falla_se_dice_y_se_sigue() {
        val falla: (File, String, String, Aparato) -> Pair<String, String?> = { _, _, _, _ -> throw IllegalStateException("No se pudo guardar la imagen") }
        comoElPc(falla) { c ->
            pedir(c, "c.png", 2)
            assertNull(respuesta(c).error)
            mandar(c, byteArrayOf(9, 9))
            assertEquals("No se pudo guardar la imagen", respuesta(c).error)
        }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun ocupado_si_ya_sincroniza_con_otro() {
        assertTrue(Protocolo.ocupar(movil))
        try {
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val hilo = Thread {
                runCatching { server.accept().use { s -> Respondedor(movil, alRecibirSuelto = alLienzo, cache = cache).atender(s.getInputStream(), s.getOutputStream()) } }
                server.close()
            }
            hilo.start()
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                val c = Canal.abrir(s.getInputStream(), s.getOutputStream(), Grupo.clave(codigo), inicia = true)
                Protocolo.enviar(c, Protocolo.Peticion("hola", hola = Protocolo.Hola(Aparato("id-pc", "PC"))))
                Protocolo.leerRespuesta(c)
                try {
                    Protocolo.leerRespuesta(c)
                    fail("tenía que decir que está ocupado")
                } catch (e: IOException) {
                    assertEquals(Protocolo.OCUPADO, e.message)
                }
            }
            hilo.join(5000)
        } finally {
            Protocolo.soltar(movil)
        }
        assertTrue(llegadas.isEmpty())
    }

    /**
     * **Con el cliente de verdad del PC** (`al_lienzo::Conexion` en Rust, vía
     * `herramientas/pc-simulado suelto`). Como `SincronizarConElPcTest`: sin
     * `PIXPIN_PC_SIMULADO` se salta.
     */
    @Test
    fun con_el_pc_de_verdad_una_al_lienzo_otra_al_chat_y_un_movil_viejo() {
        val programa = System.getenv("PIXPIN_PC_SIMULADO")
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        val a = File(raiz, "captura.png").apply { writeBytes(bytes((1 shl 20) + 3)) }
        val b = File(raiz, "foto.jpg").apply { writeBytes(bytes(4321, 5)) }
        var veces = 0
        val gancho: (File, String, String, Aparato) -> Pair<String, String?> = { f, nombre, mime, otro ->
            llegadas += Triple(f.readBytes(), nombre, mime)
            assertEquals("id-pc", otro.id)
            if (veces++ == 0) Protocolo.SUELTO_EN_EL_LIENZO to null else Protocolo.SUELTO_EN_EL_CHAT_ABIERTO to "Tesis"
        }
        fun pc(
            gancho: ((File, String, String, Aparato) -> Pair<String, String?>)?,
            aceptar: ((String, String) -> String?)? = null,
            vararg ficheros: File = arrayOf(a, b)
        ): List<String> {
            val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val hilo = Thread {
                runCatching { server.accept().use { s -> Respondedor(movil, alAceptarSuelto = aceptar, alRecibirSuelto = gancho, cache = cache).atender(s.getInputStream(), s.getOutputStream()) } }
                server.close()
            }
            hilo.start()
            val p = ProcessBuilder(listOf(programa, "suelto", codigo, server.localPort.toString()) + ficheros.map { it.path }).redirectErrorStream(true).start()
            val salida = p.inputStream.bufferedReader().readText()
            assertTrue("el PC no terminó: $salida", p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("el PC falló: $salida", 0, p.exitValue())
            hilo.join(5000)
            return salida.lines().filter { it.isNotBlank() && !it.startsWith("warning") }
        }
        assertEquals(listOf("lienzo", "chat_abierto Tesis"), pc(gancho))
        assertArrayEquals(a.readBytes(), llegadas[0].first)
        assertEquals("image/png", llegadas[0].third)
        assertEquals("foto.jpg", llegadas[1].second)
        assertEquals("image/jpeg", llegadas[1].third)
        // Un PixPin de antes: el PC lo reconoce como tal, las dos veces, y no se corta.
        val viejo = pc(null)
        assertEquals(2, viejo.size)
        assertTrue(viejo.toString(), viejo.all { it.startsWith("error: MovilSinSoporte") })
        // Un lienzo delante: el PDF se niega (el PC lo reconoce como «solo fotos») y la foto de
        // detrás entra.
        llegadas.clear()
        veces = 0
        val pdf = File(raiz, "informe.pdf").apply { writeBytes(bytes(3000, 9)) }
        assertEquals(listOf("error: SoloFotos", "lienzo"), pc(gancho, conUnLienzo, pdf, a))
        assertEquals(listOf("captura.png"), llegadas.map { it.second })
    }

    @Test
    fun con_un_chat_delante_cualquier_archivo_va_a_ese_chat_y_dice_cual() {
        val pdf = bytes(70_000, 3)
        val gancho: (File, String, String, Aparato) -> Pair<String, String?> = { f, nombre, mime, _ ->
            llegadas += Triple(f.readBytes(), nombre, mime)
            Protocolo.SUELTO_EN_EL_CHAT_ABIERTO to "Tesis"
        }
        // Con un chat delante `aceptar` no niega nada, sea lo que sea.
        val conUnChat: (String, String) -> String? = { _, mime -> LoAbierto.aceptar(LoAbierto.Delante.CHAT, mime, "Pixel") }
        comoElPc(gancho, conUnChat) { c ->
            for ((nombre, mime) in listOf("informe.pdf" to "application/pdf", "foto.png" to "image/png")) {
                pedir(c, nombre, pdf.size.toLong(), mime)
                assertNull(respuesta(c).error)
                mandar(c, pdf)
                val listo = respuesta(c)
                assertEquals("chat_abierto", listo.donde)
                assertEquals("Tesis", listo.chat)
            }
        }
        assertEquals(listOf("application/pdf", "image/png"), llegadas.map { it.third })
        assertArrayEquals(pdf, llegadas[0].first)
    }

    @Test
    fun con_un_lienzo_delante_lo_que_no_es_foto_se_niega_sin_trozos_y_la_foto_de_detras_entra() {
        comoElPc(alLienzo, conUnLienzo) { c ->
            pedir(c, "informe.pdf", 1234, "application/pdf")
            val no = respuesta(c)
            assertEquals("Pixel tiene un lienzo abierto: solo acepta fotos", no.error)
            assertTrue(no.error!!.endsWith(Protocolo.SOLO_FOTOS))
            // Sin trozos: si el móvil esperara alguno, la petición de aquí lo rompería.
            pedir(c, "captura.png", 3)
            assertNull(respuesta(c).error)
            mandar(c, byteArrayOf(1, 2, 3))
            assertEquals("lienzo", respuesta(c).donde)
        }
        assertEquals(listOf("captura.png"), llegadas.map { it.second })
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun el_destino_del_pc_del_6_oct_vale_lo_mismo_que_abierto() {
        comoElPc(alLienzo, conUnLienzo) { c ->
            for (destino in listOf("lienzo", "abierto")) {
                pedir(c, "c.png", 2, destino = destino)
                assertNull(respuesta(c).error)
                mandar(c, byteArrayOf(4, 5))
                assertEquals("lienzo", respuesta(c).donde)
                pedir(c, "c.pdf", 2, "application/pdf", destino = destino)
                assertTrue(respuesta(c).error!!.endsWith(Protocolo.SOLO_FOTOS))
            }
        }
        assertEquals(2, llegadas.size)
    }

    @Test
    fun que_hay_delante_y_que_se_acepta() {
        val d = LoAbierto.Delante.entries
        assertEquals(LoAbierto.Delante.NADA, LoAbierto.delante(false, 0, false, 0))
        assertEquals(LoAbierto.Delante.LIENZO, LoAbierto.delante(true, 5, false, 9))
        assertEquals(LoAbierto.Delante.CHAT, LoAbierto.delante(false, 9, true, 5))
        // Pantalla partida: el último que se puso delante.
        assertEquals(LoAbierto.Delante.LIENZO, LoAbierto.delante(true, 7, true, 6))
        assertEquals(LoAbierto.Delante.CHAT, LoAbierto.delante(true, 6, true, 7))
        for (x in d) assertNull("una foto entra siempre ($x)", LoAbierto.aceptar(x, "image/jpeg", "Pixel"))
        assertNull(LoAbierto.aceptar(LoAbierto.Delante.CHAT, "application/pdf", "Pixel"))
        assertNull(LoAbierto.aceptar(LoAbierto.Delante.NADA, "video/mp4", "Pixel"))
        // Caso negativo: lienzo + algo que no es foto, también sin tipo conocido.
        assertEquals("Pixel tiene un lienzo abierto: solo acepta fotos", LoAbierto.aceptar(LoAbierto.Delante.LIENZO, "application/pdf", "Pixel"))
        assertTrue(LoAbierto.aceptar(LoAbierto.Delante.LIENZO, "application/octet-stream", "Pixel")!!.endsWith(Protocolo.SOLO_FOTOS))
    }

    @Test
    fun el_chat_al_frente_se_pregunta_en_el_momento_y_solo_lo_quita_quien_lo_puso() {
        var chat = com.forge.pixpin.guardados.ChatAlFrente.Chat(null, "Mensajes guardados")
        val pantalla = com.forge.pixpin.guardados.ChatAlFrente.Pantalla { chat }
        val otra = com.forge.pixpin.guardados.ChatAlFrente.Pantalla { com.forge.pixpin.guardados.ChatAlFrente.Chat("x", "Otro") }
        com.forge.pixpin.guardados.ChatAlFrente.poner(pantalla)
        val lienzo = object : RecibeImagenes { override fun recibirImagen(archivo: File, mime: String) = true }
        LienzoAlFrente.poner(lienzo)
        assertTrue("el lienzo se puso después", LienzoAlFrente.orden > com.forge.pixpin.guardados.ChatAlFrente.orden)
        LienzoAlFrente.quitar(lienzo)
        chat = com.forge.pixpin.guardados.ChatAlFrente.Chat("p1", "Tesis")   // cambió de conversación sin onResume
        assertEquals("Tesis", com.forge.pixpin.guardados.ChatAlFrente.actual()!!.nombre)
        com.forge.pixpin.guardados.ChatAlFrente.quitar(otra)
        assertEquals("p1", com.forge.pixpin.guardados.ChatAlFrente.actual()!!.proyecto)
        com.forge.pixpin.guardados.ChatAlFrente.quitar(pantalla)
        assertNull(com.forge.pixpin.guardados.ChatAlFrente.actual())
    }

    @Test
    fun el_json_del_pc_se_entiende_tal_cual() {
        val p = Protocolo.json.decodeFromString(
            Protocolo.Peticion.serializer(),
            """{"t":"suelto","nombre":"c.png","mime":"image/png","bytes":5,"destino":"lienzo"}"""
        )
        assertEquals("suelto", p.t)
        assertEquals("c.png", p.nombre)
        assertEquals("image/png", p.mime)
        assertEquals(5L, p.bytes)
        assertEquals("lienzo", p.destino)
        assertEquals("""{"t":"listo","donde":"chat_abierto","chat":"Tesis"}""",
            Protocolo.json.encodeToString(Protocolo.Respuesta.serializer(), Protocolo.Respuesta(t = "listo", donde = "chat_abierto", chat = "Tesis")))
        assertEquals("""{"t":"listo","donde":"chat"}""",
            Protocolo.json.encodeToString(Protocolo.Respuesta.serializer(), Protocolo.Respuesta(t = "listo", donde = "chat")))
        assertEquals("{}", Protocolo.json.encodeToString(Protocolo.Respuesta.serializer(), Protocolo.Respuesta()))
        // Y una petición de siempre no lleva los campos nuevos.
        assertEquals("""{"t":"catalogo"}""", Protocolo.json.encodeToString(Protocolo.Peticion.serializer(), Protocolo.Peticion("catalogo")))
    }

    @Test
    fun el_lienzo_al_frente_solo_lo_quita_quien_lo_puso() {
        val a = object : RecibeImagenes { override fun recibirImagen(archivo: File, mime: String) = true }
        val b = object : RecibeImagenes { override fun recibirImagen(archivo: File, mime: String) = true }
        LienzoAlFrente.poner(a)
        LienzoAlFrente.quitar(b)
        assertSame(a, LienzoAlFrente.actual())
        LienzoAlFrente.poner(b)          // el onResume del nuevo antes que el onPause del viejo
        LienzoAlFrente.quitar(a)
        assertSame(b, LienzoAlFrente.actual())
        LienzoAlFrente.quitar(b)
        assertNull(LienzoAlFrente.actual())
    }
}
