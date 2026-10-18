package com.forge.pixpin.sincro

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * **Enviar a los aparatos del grupo sin escanear** (PC `ccab4f0`): el cliente `suelto` de Android
 * ([AlGrupo]) contra el [Respondedor] de verdad de otro Android por un socket local y, si está
 * `PIXPIN_PC_SIMULADO`, contra el `Respondedor` + `al_lienzo::responder` del PC en Rust.
 */
class AlGrupoTest {

    private lateinit var raiz: File
    private lateinit var telefono: Disco
    private lateinit var tableta: Disco

    /** Lo que recibió la tableta: (nombre, tipo, contenido). */
    private val llegadas = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, ByteArray>>())

    private val telefonoYo = Aparato("id-tel", "Teléfono", letra = "a")
    private val tabletaYo = Aparato("id-tab", "Tableta", letra = "b")

    @Before
    fun montar() {
        raiz = Files.createTempDirectory("al-grupo").toFile()
        telefono = Disco(File(raiz, "tel/files").apply { mkdirs() })
        tableta = Disco(File(raiz, "tab/files").apply { mkdirs() })
        val miembros = listOf(telefonoYo, tabletaYo)
        telefono.identidad.guardar(Identidad(telefonoYo, CODIGO, miembros))
        tableta.identidad.guardar(Identidad(tabletaYo, CODIGO, miembros))
    }

    @After
    fun desmontar() {
        raiz.deleteRecursively()
    }

    private fun fichero(nombre: String, bytes: ByteArray) = File(raiz, "salen/$nombre").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun bytes(n: Int, semilla: Int = 3) = ByteArray(n) { ((it * 17 + semilla) and 0xff).toByte() }

    /**
     * La tableta escucha en 127.0.0.1 con el `Respondedor` de la presencia. [aceptar] es lo que
     * diga lo que tiene delante; sin [conSuelto], un PixPin de antes del 6-oct.
     */
    private fun tabletaEscuchando(
        aceptar: ((String, String) -> String?)? = null,
        conSuelto: Boolean = true,
        disco: Disco = tableta,
        donde: String = Protocolo.SUELTO_EN_EL_CHAT_ABIERTO
    ): Pair<Int, Thread> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            try {
                server.soTimeout = 10_000
                server.accept().use { s ->
                    runCatching {
                        Respondedor(
                            disco, miPuerto = 4321,
                            alAceptarSuelto = aceptar,
                            alRecibirSuelto = if (!conSuelto) null else { f, nombre, mime, _ ->
                                llegadas += Triple(nombre, mime, f.readBytes())
                                donde to (if (donde == Protocolo.SUELTO_EN_EL_CHAT_ABIERTO) "Tesis" else null)
                            },
                            cache = if (conSuelto) File(raiz, "cache") else null
                        ).atender(s.getInputStream(), s.getOutputStream())
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
            } finally {
                server.close()
            }
        }
        hilo.start()
        return server.localPort to hilo
    }

    private fun mandar(puerto: Int, vararg f: AlGrupo.Fichero, aparato: String = "id-tab"): AlGrupo.Hecho =
        AlGrupo.mandarYa(telefono, aparato, f.toList(), miPuerto = 5555, localizar = { "127.0.0.1" to puerto })

    // ------------------------------------------------------------ a quién

    @Test
    fun la_lista_del_grupo_son_los_demas_y_sin_codigo_no_hay() {
        telefono.apuntarDireccion("id-tab", "192.168.1.9", 47474)
        assertEquals(listOf(AlGrupo.AparatoDelGrupo("id-tab", "Tableta", "b", "192.168.1.9" to 47474)), AlGrupo.aparatos(telefono))
        // Caso negativo: fuera del grupo, nadie a quien mandar sin código.
        telefono.identidad.guardar(Identidad(telefonoYo.copy(letra = null)))
        assertTrue(AlGrupo.aparatos(telefono).isEmpty())
    }

    @Test
    fun enviar_a_todos_va_a_los_que_se_ven_y_no_repite() {
        val grupo = listOf("tel", "tablet", "otro-pc").map { AlGrupo.AparatoDelGrupo(it, it) }
        val cerca = setOf("tel", "tablet")
        assertEquals(listOf("tel", "tablet"), AlGrupo.aTodos(grupo, cerca, emptyMap()))
        // Uno saliendo y otro ya entregado: ninguno otra vez.
        val enCurso = mapOf<String, AlGrupo.Hecho?>("tel" to null, "tablet" to AlGrupo.Entregado("tablet", emptyList()))
        assertTrue(AlGrupo.aTodos(grupo, cerca, enCurso).isEmpty())
        // Caso negativo: el que falló sí se puede volver a intentar.
        assertEquals(listOf("tablet"), AlGrupo.aTodos(grupo, cerca, enCurso + ("tablet" to AlGrupo.Fallo.NoEsta("tablet"))))
    }

    @Test
    fun lo_que_dice_cada_fila_y_el_aviso_de_llegada() {
        assertEquals("En la misma Wi-Fi" to AlGrupo.Tono.BIEN, AlGrupo.estadoEnFila(false, null, true))
        assertEquals(AlGrupo.Tono.SUAVE, AlGrupo.estadoEnFila(false, null, false).second)
        assertEquals("Enviando…", AlGrupo.estadoEnFila(true, null, true).first)
        val (texto, tono) = AlGrupo.estadoEnFila(true, AlGrupo.Fallo.SinSoporte("Tel"), true)
        assertEquals(AlGrupo.Tono.MAL, tono)
        assertTrue(texto, texto.startsWith("Actualiza PixPin en Tel"))
        assertFalse("aquí ya está abierta", texto.contains("Te abro"))
        assertEquals(
            "Enviado al chat «Tesis» de Tableta",
            AlGrupo.aviso(AlGrupo.Entregado("Tableta", listOf(AlGrupo.Llegada(Protocolo.SUELTO_EN_EL_CHAT_ABIERTO, "Tesis"))))
        )
        assertEquals("Portátil te envió «informe.pdf»: está en la conversación general", AlGrupo.avisoDeLlegada("Portátil", "informe.pdf", "chat", null))
        assertEquals("Portátil te envió «a.png»: está en el lienzo abierto", AlGrupo.avisoDeLlegada("Portátil", "a.png", "lienzo", null))
        assertEquals("Portátil te envió «a.pdf» al chat «Tesis»", AlGrupo.avisoDeLlegada("Portátil", "a.pdf", "chat_abierto", "Tesis"))
        // Caso negativo: sin nombre de quien manda no queda un hueco.
        assertTrue(AlGrupo.avisoDeLlegada(" ", "a.pdf", "chat", null).startsWith("? te envió"))
    }

    @Test
    fun el_tipo_que_viaja_es_el_de_la_tabla_del_pc() {
        assertEquals("application/pdf", AlGrupo.mimeDe("Informe.PDF"))
        assertEquals("image/jpeg", AlGrupo.mimeDe("foto.jpeg"))
        assertEquals("application/octet-stream", AlGrupo.mimeDe("sin-extension"))
        // Sin extensión conocida, el que dio Android al compartirlo.
        assertEquals("image/png", AlGrupo.mimeQueViaja(AlGrupo.Fichero(File("x"), "captura", "image/png")))
        assertEquals("application/pdf", AlGrupo.mimeQueViaja(AlGrupo.Fichero(File("x"), "a.pdf", "image/png")))
    }

    // ------------------------------------------------------------ de punta a punta

    @Test
    fun un_toque_manda_directo_a_la_tableta_del_grupo_y_apunta_su_puerto() {
        val (puerto, hilo) = tabletaEscuchando()
        val pdf = bytes((1 shl 20) + 333)   // dos trozos
        val hecho = mandar(puerto, AlGrupo.Fichero(fichero("1-informe.pdf", pdf), "informe.pdf"), AlGrupo.Fichero(fichero("2-nota.txt", "hola".toByteArray()), "nota.txt"))
        hilo.join(10_000)
        assertEquals(AlGrupo.Entregado("Tableta", List(2) { AlGrupo.Llegada(Protocolo.SUELTO_EN_EL_CHAT_ABIERTO, "Tesis") }), hecho)
        assertEquals(listOf("informe.pdf", "nota.txt"), llegadas.map { it.first })
        assertEquals(listOf("application/pdf", "text/plain"), llegadas.map { it.second })
        assertArrayEquals(pdf, llegadas[0].third)
        // El puerto donde escucha la tableta (el de su saludo), no el que se usó para llamarla.
        assertEquals("127.0.0.1" to 4321, telefono.direcciones()["id-tab"])
        assertTrue("no queda nada en la caché", File(raiz, "cache").listFiles().orEmpty().isEmpty())
        assertEquals("2 archivos enviados al chat «Tesis» de Tableta", AlGrupo.aviso(hecho))
    }

    @Test
    fun con_un_lienzo_delante_va_la_foto_y_lo_demas_se_niega_sin_mandarlo() {
        val conLienzo: (String, String) -> String? = { _, mime -> LoAbierto.aceptar(LoAbierto.Delante.LIENZO, mime, "Tableta") }
        val (puerto, hilo) = tabletaEscuchando(conLienzo, donde = Protocolo.SUELTO_EN_EL_LIENZO)
        val hecho = mandar(
            puerto,
            AlGrupo.Fichero(fichero("a.pdf", bytes(5000)), "a.pdf"),
            AlGrupo.Fichero(fichero("b.png", bytes(800)), "b.png")
        )
        hilo.join(10_000)
        assertEquals(AlGrupo.Entregado("Tableta", listOf(AlGrupo.Llegada(Protocolo.SUELTO_EN_EL_LIENZO)), negados = 1), hecho)
        assertEquals(listOf("b.png"), llegadas.map { it.first })
        assertTrue(AlGrupo.aviso(hecho), AlGrupo.aviso(hecho).endsWith("1 archivo no se envió: no era una foto y tiene un lienzo abierto"))
        // Caso negativo: solo un PDF a un lienzo → nada llega y se dice qué hacer.
        val (puerto2, hilo2) = tabletaEscuchando(conLienzo, donde = Protocolo.SUELTO_EN_EL_LIENZO)
        assertEquals(AlGrupo.Fallo.SoloFotos("Tableta"), mandar(puerto2, AlGrupo.Fichero(fichero("c.pdf", bytes(10)), "c.pdf")))
        hilo2.join(10_000)
        assertEquals(1, llegadas.size)
    }

    @Test
    fun caso_negativo_con_otro_codigo_de_grupo_no_entra_nada() {
        tableta.identidad.guardar(tableta.identidad.leer().copy(codigo = "ZZZZ9999ZZ"))
        val (puerto, hilo) = tabletaEscuchando()
        val hecho = mandar(puerto, AlGrupo.Fichero(fichero("a.pdf", bytes(10)), "a.pdf"))
        hilo.join(10_000)
        assertEquals(AlGrupo.Fallo.Cortado("Tableta", "¿tiene el mismo código de grupo?"), hecho)
        assertTrue(llegadas.isEmpty())
    }

    @Test
    fun caso_negativo_un_pixpin_de_antes_dice_que_actualice_y_la_conexion_sigue_sana() {
        val (puerto, hilo) = tabletaEscuchando(conSuelto = false)
        val hecho = mandar(puerto, AlGrupo.Fichero(fichero("a.pdf", bytes(10)), "a.pdf"))
        hilo.join(10_000)
        assertFalse("se despidió y la tableta acabó bien", hilo.isAlive)
        assertEquals(AlGrupo.Fallo.SinSoporte("Tableta"), hecho)
    }

    @Test
    fun caso_negativo_sin_grupo_sin_ficheros_fuera_del_grupo_o_sin_contestar() {
        val f = AlGrupo.Fichero(fichero("a.pdf", bytes(10)), "a.pdf")
        assertEquals(AlGrupo.Fallo.SinFicheros, mandar(1, AlGrupo.Fichero(File(raiz, "no-esta.pdf"), "no-esta.pdf")))
        assertEquals(AlGrupo.Fallo.NoEsDelGrupo, mandar(1, f, aparato = "id-de-nadie"))
        assertEquals(AlGrupo.Fallo.NoEsta("Tableta"), AlGrupo.mandarYa(telefono, "id-tab", listOf(f), localizar = { null }))
        // Un puerto donde nadie escucha: no está.
        val libre = ServerSocket(0).use { it.localPort }
        assertEquals(AlGrupo.Fallo.NoEsta("Tableta"), AlGrupo.mandarYa(telefono, "id-tab", listOf(f), localizar = { "127.0.0.1" to libre },
            conectar = { h, p -> Socket().apply { connect(java.net.InetSocketAddress(h, p), 1000) } }))
        telefono.identidad.guardar(Identidad(telefonoYo))
        assertEquals(AlGrupo.Fallo.SinGrupo, mandar(1, f))
    }

    @Test
    fun la_peticion_es_la_del_pc() {
        // Lo que sale por el cable, tal como lo lee `al_lienzo::Suelto` del PC.
        val p = Protocolo.Peticion("suelto", nombre = "informe.pdf", mime = "application/pdf", bytes = 12, destino = AlGrupo.DESTINO_ABIERTO)
        assertEquals(
            """{"t":"suelto","bytes":12,"nombre":"informe.pdf","mime":"application/pdf","destino":"abierto"}""",
            Protocolo.json.encodeToString(Protocolo.Peticion.serializer(), p)
        )
        assertTrue(AlGrupo.esSinSoporte("No sé qué es «suelto»"))
        assertEquals(AlGrupo.ErrorAlGrupo.Clase.SOLO_FOTOS, AlGrupo.errorRemoto("Tableta ${Protocolo.SOLO_FOTOS}").clase)
        assertEquals(AlGrupo.ErrorAlGrupo.Clase.OCUPADO, AlGrupo.errorRemoto(Protocolo.OCUPADO).clase)
        assertNotEquals(AlGrupo.ErrorAlGrupo.Clase.SIN_SOPORTE, AlGrupo.errorRemoto("No sé qué es «galeria»").clase)
        assertEquals(Protocolo.SUELTO_EN_EL_CHAT, AlGrupo.dondeDeTexto("algo-nuevo"))
    }

    // ------------------------------------------------------------ contra el PC de verdad

    /**
     * **Android manda y el PC de verdad recibe**: `pc-simulado recibir-suelto` es el `Respondedor`
     * del PC con `al_lienzo::responder`. Se salta sin `PIXPIN_PC_SIMULADO`.
     */
    @Test
    fun el_pc_de_verdad_recibe_lo_que_manda_android_sin_escanear() {
        val programa = System.getenv("PIXPIN_PC_SIMULADO")
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        for (lienzo in listOf(false, true)) {
            val pc = File(raiz, "PixPin PC $lienzo")
            orden(programa!!, "preparar", pc.path, CODIGO)
            val archivoDelPuerto = File(raiz, "puerto-$lienzo")
            val proceso = ProcessBuilder(listOfNotNull(programa, "recibir-suelto", pc.path, archivoDelPuerto.path, if (lienzo) "lienzo" else null))
                .redirectErrorStream(true).redirectOutput(File(raiz, "pc-$lienzo.log")).start()
            try {
                val hasta = System.currentTimeMillis() + 10_000
                while (!archivoDelPuerto.isFile && System.currentTimeMillis() < hasta) Thread.sleep(50)
                val puerto = archivoDelPuerto.readText().trim().toInt()
                // El teléfono está en el grupo del PC («id-pc», que crea `preparar`).
                val yo = telefonoYo
                val pcYo = Aparato("id-pc", "Portátil", letra = "a")
                telefono.identidad.guardar(Identidad(yo.copy(letra = "b"), CODIGO, listOf(pcYo, yo.copy(letra = "b"))))
                val pdf = bytes((1 shl 20) + 99)
                val hecho = AlGrupo.mandarYa(
                    telefono, "id-pc",
                    listOf(AlGrupo.Fichero(fichero("informe.pdf", pdf), "informe.pdf"), AlGrupo.Fichero(fichero("foto.png", bytes(700)), "foto.png")),
                    miPuerto = 5555, localizar = { "127.0.0.1" to puerto }
                )
                val llegados = File(pc, "llegados")
                if (!lienzo) {
                    assertEquals(AlGrupo.Entregado("Portátil", List(2) { AlGrupo.Llegada(Protocolo.SUELTO_EN_EL_CHAT) }), hecho)
                    assertArrayEquals(pdf, File(llegados, "informe.pdf").readBytes())
                    assertArrayEquals(bytes(700), File(llegados, "foto.png").readBytes())
                } else {
                    // Caso negativo: con un lienzo delante en el PC, el PDF no sale.
                    assertEquals(AlGrupo.Entregado("Portátil", listOf(AlGrupo.Llegada(Protocolo.SUELTO_EN_EL_LIENZO)), negados = 1), hecho)
                    assertFalse(File(llegados, "informe.pdf").exists())
                    assertTrue(File(llegados, "foto.png").isFile)
                }
                assertEquals("127.0.0.1" to 4747, telefono.direcciones()["id-pc"])
            } finally {
                proceso.destroy()
            }
        }
    }

    private fun orden(programa: String, vararg args: String): String {
        val p = ProcessBuilder(programa, *args).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText()
        assertTrue("el PC no terminó: $salida", p.waitFor(120, TimeUnit.SECONDS))
        assertEquals("el PC falló: $salida", 0, p.exitValue())
        return salida
    }

    private companion object {
        const val CODIGO = "ABCDE23456"
    }
}
