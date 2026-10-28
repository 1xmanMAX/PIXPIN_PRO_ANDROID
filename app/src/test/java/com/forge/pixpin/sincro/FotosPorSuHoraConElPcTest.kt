package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.CarpetaDePaginas
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.GrabarClase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.PorSuHora
import com.forge.pixpin.guardados.RelojDePared
import java.io.BufferedReader
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **Las fotos por su hora, de la PC al móvil, contra el PC de verdad** (28-oct-2026, §12-§14 de
 * `docs/movil-como-camara.md` del PC). El «PC» es `herramientas/pc-simulado` con los crates de
 * `origin/main` (995fd94b): su `por_su_hora`, su `Camaras` y su `Respondedor`; el teléfono, el código
 * de aquí. Cuatro recorridos:
 *
 * 1. **El PC graba y la foto del teléfono entra por su hora** al sincronizar (el PC dirige, mide el
 *    reloj del teléfono y repasa), y lo que escribió el PC vuelve al teléfono, se reescribe aquí y
 *    vuelve al PC sin perder nada.
 * 2. **El teléfono graba y la foto del PC entra por su hora** en la clase del teléfono (lo hace el
 *    teléfono, que es quien grabó), y el PC se lo queda tal cual.
 * 3. **Una foto sin grabación** (§12.4): la cámara del teléfono sube sin `grabacion`, solo con su hora;
 *    el PC la pone en la clase guardada donde cae, y la de fuera la niega («no cae en ninguna»).
 * 4. **El PC manda una captura a la grabación del teléfono** (§13): el teléfono le dice al PC que graba
 *    (`camara_remota` con `suya`), el PC apunta la grabación ajena y le manda la captura con su minuto.
 *
 * Solo corre con `PIXPIN_PC_SIMULADO=<ruta del programa>`.
 */
class FotosPorSuHoraConElPcTest {

    private val programa = System.getenv("PIXPIN_PC_SIMULADO")
    private lateinit var raiz: File
    private lateinit var pc: File
    private lateinit var movil: Disco
    private var proceso: Process? = null
    private var deLaEntrada: BufferedReader? = null
    private var puerto = 0
    private val s = 1000L

    private fun montar(modo: String) {
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        raiz = Files.createTempDirectory("pc-por-su-hora").toFile()
        pc = File(raiz, "PixPin Max")
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        orden("preparar", pc.path, CODIGO)
        val archivoDelPuerto = File(raiz, "puerto")
        val p = ProcessBuilder(programa, modo, pc.path, archivoDelPuerto.path).redirectError(File(raiz, "pc.log")).start()
        proceso = p
        if (modo == "camara") deLaEntrada = p.inputStream.bufferedReader() else p.inputStream.close()
        val hasta = System.currentTimeMillis() + 10_000
        var leido: Int? = null
        while (leido == null && System.currentTimeMillis() < hasta) {
            leido = archivoDelPuerto.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (leido == null) Thread.sleep(50)
        }
        puerto = leido ?: error("El PC simulado no dijo su puerto: " + File(raiz, "pc.log").takeIf { it.isFile }?.readText().orEmpty())
        // El teléfono se une al grupo del PC.
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

    private fun ultimaLinea(t: String) = Json.parseToJsonElement(t.trim().lines().last()).jsonObject

    private fun barra(linea: String): String {
        val p = proceso!!
        p.outputStream.write((linea + "\n").toByteArray())
        p.outputStream.flush()
        return deLaEntrada!!.readLine() ?: error("El PC se calló: " + File(raiz, "pc.log").readText())
    }

    /** El móvil llama y el PC responde: todos los chats, como la pantalla. */
    private fun desdeElMovil() {
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val sesion = Sesion.conectar(so.getInputStream(), so.getOutputStream(), movil)
            try {
                sesion.vuelta((movil.chats().map { it.id } + sesion.catalogo().map { it.id }).distinct(), Sesion.Hecho())
                sesion.adios()
            } finally { sesion.soltar() }
        }
    }

    /** El PC llama (su vuelta entera; mide el reloj del teléfono y repasa) y el móvil responde. */
    private fun desdeElPc(): String {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching { server.accept().use { so -> Respondedor(movil).atender(so.getInputStream(), so.getOutputStream()) } }
            server.close()
        }
        hilo.start()
        val salida = orden("dirigir", pc.path, server.localPort.toString())
        hilo.join(10_000)
        return salida
    }

    /** Los mensajes del chat del PC que se llama [nombre] (o cuyo id es [nombre]). */
    private fun chatDelPc(nombre: String): List<JsonObject> {
        val chats = ultimaLinea(orden("ver", pc.path))["chats"]!!.jsonArray.map { it.jsonObject }
        val c = chats.firstOrNull { it["nombre"]!!.jsonPrimitive.content == nombre || it["id"]!!.jsonPrimitive.content == nombre }
            ?: error("No está «$nombre» en el PC: " + chats.map { it["id"]!!.jsonPrimitive.content + "=" + it["nombre"]!!.jsonPrimitive.content })
        return c["mensajes"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.resto(): JsonObject = this["resto"]?.jsonObject ?: JsonObject(emptyMap())

    private fun capturasDe(voz: JsonObject): List<JsonObject> = voz.resto()["capturas"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    /** El repaso de aquí, ya; y que acabe el de fondo que lanzó la vuelta (los dos son el mismo, idempotente). */
    private fun repasarAqui(): Map<String?, Int> {
        val r = FotosPorSuHora.repasar(movil)
        Thread.sleep(300)
        return r
    }

    @Test
    fun `el PC graba y la foto del telefono, hecha sin conexion, entra por su hora`() {
        montar("responder")
        val ahora = System.currentTimeMillis()
        val inicio = ahora - 600 * s
        // La clase del PC: 0-100 s con una pausa de 40 a 70 (en su reloj), 100 s de nota.
        val clase = ultimaLinea(orden("clase-reloj", pc.path, "Obra", "$inicio", "${inicio + 130 * s}", "${inicio + 40 * s}", "${inicio + 70 * s}", "100000"))
        val voz = clase["voz"]!!.jsonPrimitive.content
        desdeElMovil()
        val proyecto = movil.leerMensajes().first { it.id == voz }.proyecto
        assertNotNull("la clase llegó al teléfono", proyecto)
        assertNotNull("con su reloj", RelojDePared.de(movil.leerMensajes().first { it.id == voz }))
        // El teléfono hizo dos fotos sin conexión en ese chat: una a los +90 s del PC (el 60 de la
        // clase, sin la pausa) y otra una hora después (fuera).
        val dentro = Mensaje(id = "foto-dentro", cuando = ahora, clase = Clase.IMAGEN, nombre = "dentro.jpg", proyecto = proyecto, tomadaEn = inicio + 90 * s)
        val fuera = Mensaje(id = "foto-fuera", cuando = ahora + 1, clase = Clase.IMAGEN, nombre = "fuera.jpg", proyecto = proyecto, tomadaEn = inicio + 3600 * s)
        val yo = movil.identidad.leer().yo.codigo
        movil.cambiarMensajes { it + Codigos.sellar(dentro.copy(numero = 90), yo) + Codigos.sellar(fuera.copy(numero = 91), yo) }
        // El PC dirige: mide el reloj del teléfono y repasa.
        desdeElPc()
        val enElPc = chatDelPc("Obra")
        val laVoz = enElPc.first { it["id"]!!.jsonPrimitive.content == voz }
        val entrada = capturasDe(laVoz).single()
        assertEquals("foto-dentro", entrada["id"]!!.jsonPrimitive.content)
        // Los relojes de verdad: lo medido al saludar puede bailar un milisegundo.
        assertTrue("$entrada", kotlin.math.abs(entrada["ms"]!!.jsonPrimitive.long - 60 * s) < s)
        assertEquals(yo, entrada["aparato"]!!.jsonPrimitive.content)
        assertEquals(Codigos.unico(movil.leerMensajes().first { it.id == "foto-dentro" }), entrada["uid"]!!.jsonPrimitive.content)
        assertNull("su reloj se midió al sincronizar", entrada["horaSinComprobar"])
        val laFoto = enElPc.first { it["id"]!!.jsonPrimitive.content == "foto-dentro" }.resto()
        assertEquals(entrada["ms"]!!.jsonPrimitive.long, laFoto["enGrabacion"]!!.jsonObject["ms"]!!.jsonPrimitive.long)
        assertEquals("movil", laFoto["fuente"]!!.jsonPrimitive.content)
        assertNull("la de fuera se queda como estaba", enElPc.first { it["id"]!!.jsonPrimitive.content == "foto-fuera" }.resto()["enGrabacion"])
        val carpeta = enElPc.first { it["id"]!!.jsonPrimitive.content == clase["carpeta"]!!.jsonPrimitive.content }
        assertTrue(carpeta["carpeta"]!!.jsonObject["capturas"]!!.jsonArray.any { it.jsonPrimitive.content == "foto-dentro" })

        // Lo del PC vuelve al teléfono, el usuario fija la nota aquí y vuelve al PC: nada se pierde.
        desdeElMovil()
        val vozAqui = movil.leerMensajes().first { it.id == voz }
        assertEquals(listOf(GrabarClase.Enlace(entrada["ms"]!!.jsonPrimitive.long, "foto-dentro")), GrabarClase.capturasDe(vozAqui))
        assertEquals(yo, PorSuHora.autorEnLaNota(vozAqui, "foto-dentro"))
        movil.cambiarMensajes { l -> l.map { if (it.id == voz) it.copy(fijado = true) else it } }
        desdeElMovil()
        val otraVez = chatDelPc("Obra").first { it["id"]!!.jsonPrimitive.content == voz }.resto()
        assertEquals(laVoz.resto()["reloj"], otraVez["reloj"])
        assertEquals(laVoz.resto()["capturas"], otraVez["capturas"])
        // Repasar otra vez no cambia nada.
        assertEquals("[]", orden("repasar", pc.path).trim().lines().last())
    }

    @Test
    fun `el telefono graba, el colaborador y el PC aportan, y el PC se lo queda tal cual`() {
        montar("responder")
        val ahora = System.currentTimeMillis()
        val inicio = ahora - 600 * s
        // Un chat del PC que el teléfono ya tiene.
        val chat = ultimaLinea(orden("foto-hora", pc.path, "Reunión del jueves", "${inicio - 5000 * s}"))
        desdeElMovil()
        val proyecto = movil.leerMensajes().first { it.id == chat["id"]!!.jsonPrimitive.content }.proyecto!!
        // El teléfono grabó 0-100 s (en su reloj) y abrió la clase a todos.
        val yo = movil.identidad.leer().yo.codigo
        val voz = Mensaje(
            id = "voz-movil", cuando = inicio, clase = Clase.VOZ, nombre = "Reunión.m4a", duracionMs = 100_000, proyecto = proyecto,
            grabacion = GrabarClase.CLASE, capturas = GrabarClase.capturasEnJson(emptyList()),
            reloj = RelojDePared(aparato = yo, inicio = inicio, fin = inicio + 100 * s, grabacion = "m-1").aJson(),
            colaboradores = JsonArray(listOf(JsonPrimitive(PorSuHora.TODOS)))
        )
        val carpeta = CarpetaDePaginas.mensajeNuevo("Reunión", inicio + 1, proyecto, CarpetaDePaginas.grabacion("voz-movil", 100 * s).anadirAlMinuto("voz-movil", "", 0)!!)
        // Una colaboradora (otra persona, sin medida de su reloj) que hizo una foto a los 20 s.
        val deAna = Mensaje(id = "foto-ana", cuando = inicio + 20 * s, clase = Clase.IMAGEN, nombre = "ana.jpg", proyecto = proyecto, uid = "UIDANA0001", aparato = "ANA7", tomadaEn = inicio + 20 * s)
        movil.cambiarMensajes { it + Codigos.sellar(voz.copy(numero = 50), yo) + Codigos.sellar(carpeta.copy(numero = 51), yo) + deAna.copy(numero = 1) }
        // El PC hizo una captura a los 45 s (en su reloj, que va casi igual: lo mide la vuelta).
        val delPc = ultimaLinea(orden("foto-hora", pc.path, "Reunión del jueves", "${inicio + 45 * s}"))
        // Sincroniza el teléfono (mide el reloj del PC al saludar) y repasa: él grabó.
        desdeElMovil()
        repasarAqui()
        val vozAqui = movil.leerMensajes().first { it.id == "voz-movil" }
        val enLaNota = vozAqui.capturas!!.map { it.jsonObject }
        val delPcAqui = enLaNota.first { it["id"]!!.jsonPrimitive.content == delPc["id"]!!.jsonPrimitive.content }
        assertTrue(kotlin.math.abs(delPcAqui["ms"]!!.jsonPrimitive.long - 45 * s) < 2 * s)
        assertNull("el reloj del PC se midió al saludar", delPcAqui["horaSinComprobar"])
        assertEquals(delPc["uid"]!!.jsonPrimitive.content, delPcAqui["uid"]!!.jsonPrimitive.content)
        val deAnaAqui = enLaNota.first { it["id"]!!.jsonPrimitive.content == "foto-ana" }
        assertEquals(20 * s, deAnaAqui["ms"]!!.jsonPrimitive.long)
        assertEquals(true, deAnaAqui[PorSuHora.SIN_COMPROBAR]?.jsonPrimitive?.content?.toBoolean())
        // La foto de otra persona no se toca; la del PC (un aparato mío) lleva su enlace.
        assertNull(movil.leerMensajes().first { it.id == "foto-ana" }.enGrabacion)
        val fotoDelPc = movil.leerMensajes().first { it.id == delPc["id"]!!.jsonPrimitive.content }
        assertEquals("voz-movil", fotoDelPc.enGrabacion!!["de"]!!.jsonPrimitive.content)
        assertTrue(CarpetaDePaginas.deMensaje(movil.leerMensajes().first { it.id == carpeta.id })!!.contiene("foto-ana"))
        // Repasar otra vez: nada.
        assertEquals(emptyMap<String?, Int>(), FotosPorSuHora.repasar(movil))

        // Al PC: lo escrito aquí lo guarda tal cual, y el PC no lo repasa (no grabó él).
        desdeElMovil()
        val enElPc = chatDelPc(proyecto)
        val vozEnElPc = enElPc.first { it["id"]!!.jsonPrimitive.content == "voz-movil" }.resto()
        assertEquals(JsonArray(vozAqui.capturas!!), vozEnElPc["capturas"])
        assertEquals(vozAqui.reloj, vozEnElPc["reloj"])
        assertEquals(vozAqui.colaboradores, vozEnElPc["colaboradores"])
        assertEquals("[]", orden("repasar", pc.path).trim().lines().last())
    }

    // ------------------------------------------------------------ la cámara (§12.4 y §13)

    private val pared = 1_761_550_000_000L
    private val adelanto = 7 * s
    @Volatile private var relojDelPc = pared
    private fun ahoraMovil() = relojDelPc + adelanto

    private fun aLas(segundos: Long) {
        relojDelPc = pared + segundos * s
        assertEquals("ok", barra("hora $relojDelPc"))
    }

    private fun jpeg(semilla: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2000) { ((it * semilla) and 0xff).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `una foto hecha sin saber de la grabacion entra por su hora y la de fuera se queda como esta`() {
        montar("camara")
        // El PC graba de 0 a 100 s con una pausa de 30 a 50 y guarda la clase (con su reloj).
        aLas(0)
        assertEquals("ok", barra("empezar g-1 Clase"))
        aLas(30); assertEquals("ok", barra("seguir 30000 1"))
        aLas(50); assertEquals("ok", barra("seguir 30000 0"))
        aLas(100)
        val terminada = Json.parseToJsonElement(barra("terminar 80000")).jsonObject
        val voz = terminada["voz"]!!.jsonPrimitive.content
        // Una hora después el teléfono por fin conecta: dos fotos hechas sin conexión, por su hora.
        aLas(3600)
        val cola = ColaDeLaCamara.de(movil.filesDir)
        fun toma(clave: String, tomadaEn: Long): ColaDeLaCamara.Toma {
            val f = cola.ficheroNuevo("jpg").apply { writeBytes(jpeg(clave.length)) }
            return ColaDeLaCamara.Toma(clave = clave, fichero = f.name, nombre = "$clave.jpg", mime = "image/jpeg", grabacion = "", pc = "",
                ms = 0, tomadaEn = tomadaEn, creada = System.nanoTime()).also { cola.poner(it) }
        }
        // A los 70 s del PC (el 50 de la grabación), en el reloj del teléfono 7 s adelantado.
        toma("UIDDENTRO1", pared + 70 * s + adelanto)
        toma("UIDFUERA01", pared + 3000 * s + adelanto)
        val pasada = Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            SubidaALaGrabacion.subir(so.getInputStream(), so.getOutputStream(), movil.identidad.leer(), cola, "id-pc", ahora = ::ahoraMovil)
        }
        assertEquals("$pasada", 1, pasada.subidas)
        assertEquals(1, pasada.negadas)
        val porClave = cola.todas().associateBy { it.clave }
        assertEquals(50 * s, porClave.getValue("UIDDENTRO1").msListo)
        assertTrue(CamaraRemota.esNinguna(porClave.getValue("UIDFUERA01").error.orEmpty()))
        assertEquals(ColaDeLaCamara.NEGADA, porClave.getValue("UIDFUERA01").estado)
        // En la clase del PC: la copia con `fotoUid`, en su minuto, en la nota.
        val mensajes = chatDelPc("Reunion")
        val copia = mensajes.single { it.resto()["fotoUid"]?.jsonPrimitive?.content == "UIDDENTRO1" }
        assertEquals(50 * s, copia.resto()["enGrabacion"]!!.jsonObject["ms"]!!.jsonPrimitive.long)
        val laVoz = mensajes.first { it["id"]!!.jsonPrimitive.content == voz }
        assertEquals(listOf(50 * s), capturasDe(laVoz).map { it["ms"]!!.jsonPrimitive.long })
        assertEquals("g-1", laVoz.resto()["reloj"]!!.jsonObject["grabacion"]!!.jsonPrimitive.content)
        // Mandada otra vez (el teléfono no supo si llegó): una sola.
        cola.poner(porClave.getValue("UIDDENTRO1").copy(estado = ColaDeLaCamara.PENDIENTE))
        Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            SubidaALaGrabacion.subir(so.getInputStream(), so.getOutputStream(), movil.identidad.leer(), cola, "id-pc", ahora = ::ahoraMovil)
        }
        assertEquals(1, chatDelPc("Reunion").count { it.resto()["fotoUid"]?.jsonPrimitive?.content == "UIDDENTRO1" })
    }

    @Test
    fun `el PC manda una captura a la grabacion del telefono con su minuto`() {
        montar("camara")
        aLas(0)
        val g = GrabacionesDelMovil(File(raiz, "cola-del-movil"), "Teléfono", movil.identidad.leer().yo.codigo).also { it.almacen = movil }
        g.empezar("m-1", "Clase del móvil", ahoraMovil())
        aLas(20)
        // El teléfono le dice al PC que graba (`camara_remota` con la suya) y mide su reloj.
        val respuesta = Socket(InetAddress.getLoopbackAddress(), puerto).use { so ->
            val con = ConexionDeCamara.abrir(so.getInputStream(), so.getOutputStream(), movil.identidad.leer(), ahora = ::ahoraMovil)
            assertTrue(con.sabeDeCamara)
            con.anunciarLaMia(g.anuncio(ahoraMovil())!!, ::ahoraMovil).also { con.adios() }
        }
        assertEquals(adelanto, respuesta.adelanto)
        val ajena = Json.parseToJsonElement(barra("ajena")).jsonObject
        assertEquals("m-1", ajena["grabacion"]!!.jsonPrimitive.content)
        assertEquals("id-movil", ajena["de"]!!.jsonPrimitive.content)
        assertEquals(adelanto, ajena["desfase"]!!.jsonPrimitive.long)
        // A los 35 s el PC hace una captura y se la manda al teléfono, que la recibe con su Respondedor.
        aLas(35)
        val escucha = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val quien = object : QuienGraba {
            override fun anuncio() = g.anuncio(ahoraMovil())
            override fun aceptar(p: Protocolo.Peticion) = g.aceptar(p.grabacion, p.tomadaEn, p.mime.orEmpty(), p.bytes)
            override fun guardar(p: Protocolo.Peticion, fichero: File, otro: Aparato) =
                g.recibir(GrabacionesDelMovil.Foto.de(p), fichero, otro.id, otro.nombre, ahoraMovil())
        }
        val hilo = Thread {
            escucha.accept().use { so ->
                Respondedor(movil, ahora = ::ahoraMovil, cache = File(raiz, "cache"), quienGraba = quien).atender(so.getInputStream(), so.getOutputStream())
            }
            escucha.close()
        }
        hilo.start()
        val png = File(raiz, "Captura.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + ByteArray(500) { 7 }) }
        val enviado = Json.parseToJsonElement(barra("al-movil ${escucha.localPort} ${png.path}")).jsonObject
        hilo.join(10_000)
        assertEquals("$enviado", 35 * s, enviado["ms"]!!.jsonPrimitive.long)
        assertEquals(listOf(GrabacionesDelMovil.Recibida(35 * s, false, "Portátil")), g.tomarNuevas())
        // Caso negativo: sin grabación del teléfono, el PC no tiene a quién mandar.
        aLas(35 + 91)
        assertTrue(barra("ajena") == "null")

        // El teléfono termina y guarda su clase: la captura del PC entra en su minuto, «de la pantalla».
        g.acabar(ahoraMovil())
        val voz = Mensaje(id = "voz-m1", cuando = pared, clase = Clase.VOZ, duracionMs = 120_000, grabacion = GrabarClase.CLASE, capturas = GrabarClase.capturasEnJson(emptyList()))
        movil.cambiarMensajes { it + Codigos.sellar(voz.copy(numero = 7), movil.identidad.leer().yo.codigo) }
        assertEquals(1, g.guardada(GrabacionesDelMovil.Destino(movil, null, "voz-m1", null), 120_000, ahoraMovil()))
        val captura = movil.leerMensajes().single { it.fotoUid?.startsWith("pc-") == true }
        assertEquals(35 * s, captura.enGrabacion!!["ms"]!!.jsonPrimitive.long)
        assertEquals(GrabacionesDelMovil.DE_PANTALLA, captura.fuente)
        assertEquals("Portátil", captura.deAparato)
        assertEquals("m-1", RelojDePared.de(movil.leerMensajes().first { it.id == "voz-m1" })!!.grabacion)
        // Y viaja al PC tal cual (la Conversación general).
        desdeElMovil()
        val enElPc = ultimaLinea(orden("ver", pc.path))["chats"]!!.jsonArray.flatMap { it.jsonObject["mensajes"]!!.jsonArray.map { m -> m.jsonObject } }
        val llegada = enElPc.single { it.resto()["fotoUid"]?.jsonPrimitive?.content == captura.fotoUid }
        assertEquals("pantalla", llegada.resto()["fuente"]!!.jsonPrimitive.content)
        assertEquals(35 * s, llegada.resto()["enGrabacion"]!!.jsonObject["ms"]!!.jsonPrimitive.long)
        val vozEnElPc = enElPc.single { it["id"]!!.jsonPrimitive.content == "voz-m1" }
        assertEquals(listOf(captura.id), capturasDe(vozEnElPc).map { it["id"]!!.jsonPrimitive.content })
        assertFalse(vozEnElPc.resto()["reloj"] == null)
    }

    companion object {
        const val CODIGO = "ABCDE23456"
    }
}
