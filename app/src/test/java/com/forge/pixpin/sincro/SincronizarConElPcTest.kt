package com.forge.pixpin.sincro

import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.CaducidadDeCapturas.DIA_MS
import com.forge.pixpin.capture.GaleriaCompartida
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
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
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * **El PC de verdad contra Android de verdad** (4-oct-2026).
 *
 * El «PC» es un programa en Rust hecho con los crates del repositorio de Windows
 * (`pixpin-proyecto`: su almacén de siempre visto por `DiscoPc`; `pixpin-sincro`: su
 * `Respondedor`, su `Sesion` y su vuelta). Aquí el móvil es el [Disco] de siempre. Se hablan por
 * un socket con el protocolo y el cifrado de verdad, en los dos sentidos.
 *
 * Solo corre con `PIXPIN_PC_SIMULADO=<ruta del programa>` (ver `herramientas/pc-simulado`); sin
 * él se salta, así que no estorba en GitHub, que no compila el PC.
 */
class SincronizarConElPcTest {

    private val programa = System.getenv("PIXPIN_PC_SIMULADO")
    private lateinit var raiz: File
    private lateinit var pc: File
    private lateinit var movil: Disco
    private lateinit var galMovil: GaleriaQueViajaTest.EnCarpeta
    private var responde: Process? = null
    private var puerto = 0

    @Before
    fun montar() {
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        raiz = Files.createTempDirectory("pc-movil").toFile()
        pc = File(raiz, "PixPin Max")
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        // Con galería, como el teléfono. El registro empieza hace un mes (como el del PC en
        // `preparar`): nada de «lo que ya había no se va».
        CaducidadDeCapturas.leer(movil.filesDir, System.currentTimeMillis() - 30 * DIA_MS)
        galMovil = GaleriaQueViajaTest.EnCarpeta(movil.filesDir)
        movil.capturas = galMovil.also { it.hacer("captura.png", segundos(System.currentTimeMillis())) }
        orden("preparar", pc.path, CODIGO)
        val archivoDelPuerto = File(raiz, "puerto")
        responde = ProcessBuilder(programa, "responder", pc.path, archivoDelPuerto.path).redirectErrorStream(true)
            .redirectOutput(File(raiz, "pc.log")).start()
        val hasta = System.currentTimeMillis() + 10_000
        while (!archivoDelPuerto.isFile && System.currentTimeMillis() < hasta) Thread.sleep(50)
        puerto = archivoDelPuerto.readText().trim().toInt()
    }

    @After
    fun desmontar() {
        responde?.destroy()
        if (::raiz.isInitialized) raiz.deleteRecursively()
    }

    private fun orden(vararg args: String): String {
        val p = ProcessBuilder(programa, *args).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText()
        assertTrue("el PC no terminó: $salida", p.waitFor(120, TimeUnit.SECONDS))
        assertEquals("el PC falló: $salida", 0, p.exitValue())
        return salida
    }

    /** El móvil llama y el PC responde: todos los chats y la galería, como la pantalla sin preguntas. */
    private fun desdeElMovil(unirme: Boolean = false): Sesion.Hecho {
        val hecho = Sesion.Hecho()
        Socket(InetAddress.getLoopbackAddress(), puerto).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), movil, unirme, if (unirme) CODIGO else null)
            try {
                if (!unirme) {
                    val chats = (movil.chats().map { it.id } + sesion.catalogo().map { it.id }).distinct()
                    // Como la sincronización de verdad (27-oct-2026): por prioridad, los archivos de
                    // todos los chats mezclados, y lo quieto en los dos ni se mira. El PC contesta
                    // cada petición por su lado: esto prueba que el orden nuevo no le molesta.
                    sesion.vuelta(chats, hecho)
                    // Como la pantalla: la galería detrás. El PC sabe de galerías desde c493abb
                    // (`atender_con_galeria`); uno de antes contestaría «No sé qué es» (eso lo
                    // prueba `GaleriaQueViajaTest`).
                    if (movil.capturas != null) assertTrue("el PC tiene galería", sesion.galeria(hecho))
                }
                sesion.adios()
            } finally { sesion.soltar() }
        }
        return hecho
    }

    /** El PC llama (su vuelta entera, con galería) y el móvil responde. Devuelve lo que contó el PC. */
    private fun desdeElPc(): String {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching { server.accept().use { s -> Respondedor(movil).atender(s.getInputStream(), s.getOutputStream()) } }
            server.close()
        }
        hilo.start()
        val salida = orden("dirigir", pc.path, server.localPort.toString())
        hilo.join(10_000)
        return salida
    }

    private fun verPc(): JsonObject = Json.parseToJsonElement(orden("ver", pc.path).trim().lines().last()).jsonObject

    private fun mensajesDelPc(): List<JsonObject> =
        verPc()["chats"]!!.jsonArray.flatMap { it.jsonObject["mensajes"]!!.jsonArray.map { m -> m.jsonObject } }

    private fun JsonObject.s(k: String): String? = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun sinRepetidos() {
        val ids = movil.leerMensajes().map { Codigos.unico(it) }
        assertEquals("repetidos en el móvil: $ids", ids.size, ids.toSet().size)
        val delPc = mensajesDelPc().map { it.s("uid") }
        assertEquals("repetidos en el PC: $delPc", delPc.size, delPc.toSet().size)
    }

    @Test
    fun `con el PC - el saludo con capacidades no le molesta y la segunda vuelta no mira nada`() {
        desdeElMovil(unirme = true)
        val primera = desdeElMovil()
        assertTrue("algo llegó del PC", primera.traidos > 0)
        // El PC no lleva diario ni sabe de capacidades: se salta lo quieto por su inventario.
        val segunda = desdeElMovil()
        assertEquals(0, segunda.traidos + segunda.enviados + segunda.fusionados + segunda.archivos)
        val chats = movil.chats().size
        assertEquals("todos quietos: $chats chats", chats, segunda.quietos)
        // Algo nuevo en el PC (una clase grabada) se ve en su inventario y llega.
        orden("clase", pc.path, "Tema nuevo del PC", "1")
        val tercera = desdeElMovil()
        assertTrue("la clase llegó", tercera.traidos > 0)
        assertTrue(movil.leerProyectos().any { it.nombre == "Tema nuevo del PC" })
        assertTrue(movil.leerMensajes().any { it.nombre == "Clase 25-oct.m4a" })
        assertTrue("lo demás, quieto", tercera.quietos >= 1)
        // Y el PC, al dirigir él, sigue entendiendo al móvil de siempre.
        desdeElPc()
        sinRepetidos()
    }

    @Test
    fun `lo del PC llega entero al movil, una sola vez`() {

        desdeElMovil(unirme = true)
        desdeElMovil()
        val ms = movil.leerMensajes()
        val porTexto = ms.associateBy { it.texto }
        val hola = porTexto.getValue("Hola desde el PC")
        assertEquals(hola.id, porTexto.getValue("Respuesta en el PC").respondeA)
        val pdf = ms.single { it.nombre == "informe (1).pdf" }
        assertEquals("%PDF-1.4 del PC", File(pdf.ruta!!).readText())
        val foto = ms.single { it.clase == Clase.IMAGEN && it.nombre == "Fachada norte.jpg" }
        assertEquals("JPG del PC", File(foto.ruta!!).readText())
        val voz = ms.single { it.clase == Clase.VOZ }
        assertEquals("Clase de estructuras", voz.nombre)
        assertEquals(4200, voz.duracionMs)
        assertEquals("AAC del PC", File(voz.ruta!!).readText())
        val tareas = ms.single { it.clase == Clase.MINIAPP }
        assertEquals("tareas", tareas.miniapp)
        assertTrue(tareas.texto.contains("comprar cemento ➕ 2026-10-03"))
        assertTrue("comentarios de la nota", File(movil.filesDir, "pins/draw/anot-${Codigos.unico(hola)}.comentarios.json").isFile)
        // El proyecto del PC: su chat con la nota, la foto y el lienzo con su imagen.
        val obra = ms.single { it.texto == "medir la cocina" }.proyecto!!
        val delaObra = ms.filter { it.proyecto == obra }
        assertEquals(3, delaObra.size)
        assertEquals("el proyecto del PC sale en Proyectos", listOf("Obra del PC"), movil.leerProyectos().filter { it.id == obra }.map { it.nombre })
        val dib = delaObra.single { it.clase == Clase.DIBUJO }
        assertTrue(File(movil.filesDir, "pins/draw/${dib.referencia}.excalidraw.gz").isFile)
        sinRepetidos()
        // Y otra vuelta no trae nada ni duplica nada.
        val cuantos = ms.size
        val otra = desdeElMovil()
        assertEquals(0, otra.traidos + otra.enviados + otra.fusionados)
        assertEquals(cuantos, movil.leerMensajes().size)
        sinRepetidos()
    }

    @Test
    fun `lo nuevo de Android llega al PC y vuelve sin duplicarse`() {
        desdeElMovil(unirme = true)
        desdeElMovil()
        // En el móvil: una lección con su foto, un audio propio y el del PC renombrado.
        val lec = File(movil.filesDir, "guardados/lecciones/abc.leccion").apply { parentFile!!.mkdirs(); writeText("{\"id\":\"abc\",\"titulo\":\"Medir dos veces\"}") }
        val f = File(movil.filesDir, "guardados/leccion_1.jpg").apply { writeText("jpg del móvil") }
        val a = File(movil.filesDir, "guardados/voz_9.m4a").apply { writeText("aac del móvil") }
        val ahora = System.currentTimeMillis()
        fun linea(m: Mensaje) = File(movil.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")
        linea(Mensaje(id = "lec-abc", cuando = ahora, clase = Clase.ARCHIVO, ruta = lec.path, nombre = "💡 Medir dos veces"))
        linea(Mensaje(id = "f1", cuando = ahora + 1, clase = Clase.IMAGEN, ruta = f.path, nombre = "Foto de la lección", respondeA = "lec-abc"))
        linea(Mensaje(id = "v9", cuando = ahora + 2, clase = Clase.VOZ, ruta = a.path, nombre = "Dictado de la obra", duracionMs = 1500))
        val lista = movil.leerMensajes().map { if (it.clase == Clase.VOZ && it.nombre == "Clase de estructuras") it.copy(nombre = "Estructuras, tema 3") else it }
        File(movil.filesDir, "guardados.jsonl").writeText(lista.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })

        // Y un proyecto nacido en el móvil, con su lienzo.
        movil.guardarProyecto(com.forge.pixpin.motor.Proyecto(id = "pr-movil", nombre = "Reforma del móvil", tocado = ahora,
            hojas = listOf(com.forge.pixpin.motor.Hoja(id = "h1", nombre = "Planta", dibujo = "dm1"))))
        File(movil.filesDir, "pins/draw/dm1.excalidraw.gz").apply { parentFile!!.mkdirs() }.outputStream().use { o ->
            java.util.zip.GZIPOutputStream(o).use { it.write("{\"elements\":[{\"id\":\"A\",\"type\":\"rectangle\",\"x\":0,\"version\":1,\"versionNonce\":7,\"updated\":1}],\"files\":{}}".toByteArray()) }
        }
        linea(Mensaje(id = "md1", cuando = ahora + 3, clase = Clase.DIBUJO, referencia = "dm1", nombre = "Planta", proyecto = "pr-movil"))

        desdeElPc()
        val delPc = mensajesDelPc()
        val porNombre = delPc.groupBy { it.s("nombre") }
        assertEquals(1, porNombre["💡 Medir dos veces"]?.size)
        val fotoEnPc = porNombre.getValue("Foto de la lección").single()
        assertEquals("lec-abc", fotoEnPc.s("respondeA"))
        assertEquals(1, porNombre["Dictado de la obra"]?.size)
        assertEquals("el renombrado viaja", 1, porNombre["Estructuras, tema 3"]?.size)
        assertEquals(null, porNombre["Clase de estructuras"])
        val chatsDelPc = verPc()["chats"]!!.jsonArray.map { it.jsonObject.s("nombre") }
        assertTrue("el proyecto del móvil en el PC: $chatsDelPc", "Reforma del móvil" in chatsDelPc)
        assertEquals(1, chatsDelPc.count { it == "Obra del PC" })
        val archivos = verPc()["archivos"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("la lección en el PC: $archivos", archivos.any { it.endsWith("abc.leccion") })
        assertTrue(archivos.any { it.endsWith("leccion_1.jpg") })
        sinRepetidos()

        // Dos vueltas más, una por cada lado: nada se mueve ni se repite.
        val n = movil.leerMensajes().size
        val m = delPc.size
        val otra = desdeElMovil()
        assertEquals(0, otra.traidos + otra.enviados + otra.fusionados)
        desdeElPc()
        assertEquals(n, movil.leerMensajes().size)
        assertEquals(m, mensajesDelPc().size)
        sinRepetidos()
    }

    /** El chat del PC por su nombre, como lo enseña `ver` (con su `avatar` y lo que lleva su imagen). */
    private fun chatDelPc(nombre: String): JsonObject =
        verPc()["chats"]!!.jsonArray.map { it.jsonObject }.single { it.s("nombre") == nombre }

    @Test
    fun `la foto del chat viaja con su imagen entre el PC y el movil, en los dos sentidos`() {
        // El manual del PC, §10 (25-oct-2026): `avatar` es un campo más del Proyecto, su imagen va
        // con ruta portátil y la nombra el proyecto, así que viaja con él.
        desdeElMovil(unirme = true)
        desdeElMovil()
        val obra = { movil.leerProyectos().single { it.nombre == "Obra del PC" } }
        assertNull("sin foto no sale ningún campo", obra().avatar)

        // 1. Una imagen puesta en el PC llega al móvil con su fichero, con la ruta local del móvil.
        orden("foto", pc.path, "Obra del PC", "imagen", "PNG de la foto")
        desdeElMovil()
        val llego = com.forge.pixpin.guardados.FotoDelChat.de(obra())!!
        assertEquals("imagen", llego.tipo)
        assertTrue("ruta del móvil: ${llego.ruta}", llego.ruta!!.startsWith(movil.filesDir.absolutePath + "/guardados/pc/"))
        assertTrue(llego.ruta!!.substringAfterLast('/').startsWith("avatar-"))
        assertEquals("PNG de la foto", File(llego.ruta!!).readText())
        val quieta = desdeElMovil()
        assertEquals("otra vuelta no mueve nada", 0, quieta.traidos + quieta.enviados + quieta.fusionados)

        // 2. Un emoji puesto en el móvil llega al PC, y la imagen vieja se va de los dos.
        val ahora = System.currentTimeMillis()
        movil.guardarProyecto(com.forge.pixpin.guardados.FotoDelChat.conFoto(obra(),
            com.forge.pixpin.guardados.FotoDelChat.emoji("🏗️", "#FFCD6A" to "#FFA85C", ahora), ahora))
        assertFalse("la vieja se va del móvil", File(llego.ruta!!).exists())
        desdeElMovil()
        val enPc = chatDelPc("Obra del PC")["avatar"]!!.jsonObject
        assertEquals("emoji", enPc.s("tipo"))
        assertEquals("🏗️", enPc.s("emoji"))
        assertEquals("#FFCD6A", enPc.s("color"))
        assertEquals("#FFA85C", enPc.s("color2"))
        val archivos = verPc()["archivos"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("la vieja se va del PC: $archivos", archivos.none { it.substringAfterLast('/').startsWith("avatar-") })

        // 3. Una imagen puesta en el móvil llega al PC, que la encuentra y la lee.
        val png = File(movil.filesDir, com.forge.pixpin.guardados.FotoDelChat.rutaRelativaDeImagen(ahora + 1)).apply { parentFile!!.mkdirs(); writeText("PNG del móvil") }
        movil.guardarProyecto(com.forge.pixpin.guardados.FotoDelChat.conFoto(obra(),
            com.forge.pixpin.guardados.FotoDelChat.imagen(png.absolutePath, ahora + 1), ahora + 1))
        desdeElPc()
        val conImagen = chatDelPc("Obra del PC")
        assertEquals("imagen", conImagen["avatar"]!!.jsonObject.s("tipo"))
        assertEquals("el PC lee la imagen del móvil", "PNG del móvil", conImagen.s("imagenDelAvatar"))

        // 4. Volver a lo de siempre en el PC también viaja: se escribe, no se borra.
        orden("foto", pc.path, "Obra del PC", "iniciales")
        desdeElMovil()
        val siempre = obra().avatar!!
        assertEquals("iniciales", siempre.s("tipo"))
        assertNull(siempre["color"])
        assertTrue(com.forge.pixpin.guardados.FotoDelChat.de(obra())!!.esLoDeSiempre)

        // 5. Un tipo que el PC no conoce vuelve tal cual, con sus claves.
        val raro = Json.parseToJsonElement("""{"tipo":"animado","gif":"x","cuando":${ahora + 5}}""").jsonObject
        movil.guardarProyecto(obra().copy(avatar = raro, tocado = ahora + 5))
        desdeElMovil()
        desdeElPc()
        assertEquals("x", chatDelPc("Obra del PC")["avatar"]!!.jsonObject.s("gif"))
        assertEquals(raro, obra().avatar)
        sinRepetidos()
    }

    @Test
    fun `tareas al instante - lo tachado en el movil llega al PC con la vuelta recortada`() {
        desdeElMovil(unirme = true)
        desdeElMovil()
        val compras = movil.leerMensajes().single { it.miniapp == "tareas" }
        assertTrue(compras.texto.contains("- [ ] comprar cemento"))
        // Se tacha en el móvil (como el almacén del chat) y se escribe algo más en otro sitio.
        val tachada = compras.copy(texto = compras.texto.replace("- [ ] comprar cemento", "- [x] comprar cemento"))
        val lista = movil.leerMensajes().map { if (it.id == compras.id) tachada else it }
        File(movil.filesDir, "guardados.jsonl").writeText(lista.joinToString("") { Disco.JSON.encodeToString(Mensaje.serializer(), it) + "\n" })
        val chat = Disco.chatDe(compras)
        assertEquals(setOf(chat), com.forge.pixpin.guardados.MensajesStore.chatsConTareasCambiadas(movil.leerMensajes().map { if (it.id == compras.id) compras else it }, movil.leerMensajes()))

        val t0 = System.nanoTime()
        Socket(InetAddress.getLoopbackAddress(), puerto).use { s ->
            VueltaCallada(movil).con(s.getInputStream(), s.getOutputStream(), soloEstos = setOf(chat))
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val enPc = mensajesDelPc().single { it.s("texto")?.contains("comprar cemento") == true }
        assertTrue("tachada en el PC (${ms} ms): ${enPc.s("texto")}", enPc.s("texto")!!.contains("- [x] comprar cemento"))
        println("tareas al instante, móvil → PC: $ms ms")
        sinRepetidos()
    }

    // ------------------------------------------------------------------ la galería (10-oct-2026)
    // Contra la galería del PC de c493abb (`pixpin_sincro::galeria`): en `pc-simulado` sus capturas
    // son una carpeta (su `EnCarpeta`, 7 días hasta borrar), el `Respondedor` atiende con
    // `atender_con_galeria` y su vuelta lleva `con_galeria`.

    private val carpetaPc get() = File(raiz, "PixPin Max-galeria/Pictures/PixPin")
    private val papeleraPc get() = File(raiz, "PixPin Max-galeria/papelera")
    private val registroPc get() = File(raiz, "PixPin Max-galeria/capturas-caducidad.json")

    /** Una captura hecha en el PC a la hora [cuando]. */
    private fun capturaEnPc(nombre: String, cuando: Long) =
        File(carpetaPc, nombre).apply { writeText("PNG del PC: $nombre"); setLastModified(cuando) }

    /** La galería vista con los ojos del PC (`pc-simulado galeria`): fechas de irse según su registro. */
    private fun galeriaPc(): JsonObject = Json.parseToJsonElement(orden("galeria", pc.path).trim().lines().last()).jsonObject
    private fun capturasPc(): Map<String, JsonObject> =
        galeriaPc()["capturas"]!!.jsonArray.associate { it.jsonObject.s("nombre")!! to it.jsonObject }
    private fun seVaEnPc(nombre: String): Long? = capturasPc().getValue(nombre)["seVa"]!!.jsonPrimitive.longOrNull
    private fun seVaEnMovil(nombre: String): Long? = galMovil.seVa(nombre, System.currentTimeMillis())
    private fun borradaEnPc(nombre: String): Boolean =
        galeriaPc()["entradas"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it.s("nombre") == nombre }
            ?.get("borrada")?.jsonPrimitive?.content?.toBoolean() ?: false

    /** Las horas de los ficheros, en segundos enteros. */
    private fun segundos(ms: Long) = ms / 1000 * 1000

    /** Para que lo cambiado después tenga otra hora que lo de antes (gana lo más reciente). */
    private fun pasaUnPoco() = Thread.sleep(20)

    @Test
    fun `galeria, llama el movil - cada captura llega al otro con su hora y se va el mismo dia`() {
        val ahora = segundos(System.currentTimeMillis())
        capturaEnPc("pc-1.png", ahora - 2 * DIA_MS)
        galMovil.hacer("tel-1.png", ahora - 3 * DIA_MS, 1_300_000)   // más de un trozo
        desdeElMovil(unirme = true)
        assertEquals("tres capturas pasaron", 3, desdeElMovil().capturas)
        assertEquals(listOf("captura.png", "pc-1.png", "tel-1.png"), galMovil.nombres())
        assertEquals("PNG del PC: pc-1.png", File(galMovil.carpeta, "pc-1.png").readText())
        assertEquals("llegó con la hora del PC", ahora - 2 * DIA_MS, File(galMovil.carpeta, "pc-1.png").lastModified())
        val enPc = capturasPc()
        assertEquals(setOf("captura.png", "pc-1.png", "tel-1.png"), enPc.keys)
        assertEquals("llegó al PC con la hora del móvil", ahora - 3 * DIA_MS, enPc.getValue("tel-1.png")["cuando"]!!.jsonPrimitive.long)
        assertTrue("entera", File(carpetaPc, "tel-1.png").readBytes().contentEquals(File(galMovil.carpeta, "tel-1.png").readBytes()))
        for (n in galMovil.nombres()) assertEquals(n, seVaEnMovil(n), seVaEnPc(n))
        assertEquals(ahora - 2 * DIA_MS + 7 * DIA_MS, seVaEnMovil("pc-1.png"))
        assertEquals(ahora - 3 * DIA_MS + 7 * DIA_MS, seVaEnPc("tel-1.png"))
        // Otra vuelta, llame quien llame: nada que pasar.
        assertEquals(0, desdeElMovil().capturas)
        val salida = desdeElPc()
        assertTrue("lo que contó el PC: $salida", salida.contains("capturas=0 tiradas=0"))
    }

    @Test
    fun `galeria, llama el PC - cada captura llega al otro con su hora y se va el mismo dia`() {
        val ahora = segundos(System.currentTimeMillis())
        capturaEnPc("pc-1.png", ahora - 2 * DIA_MS)
        galMovil.hacer("tel-1.png", ahora - 3 * DIA_MS, 1_300_000)
        desdeElMovil(unirme = true)
        val salida = desdeElPc()
        assertTrue("lo que contó el PC: $salida", salida.contains("capturas=3 tiradas=0"))
        assertEquals(listOf("captura.png", "pc-1.png", "tel-1.png"), galMovil.nombres())
        assertEquals("PNG del PC: pc-1.png", File(galMovil.carpeta, "pc-1.png").readText())
        assertEquals(ahora - 2 * DIA_MS, File(galMovil.carpeta, "pc-1.png").lastModified())
        val enPc = capturasPc()
        assertEquals(setOf("captura.png", "pc-1.png", "tel-1.png"), enPc.keys)
        assertEquals(ahora - 3 * DIA_MS, enPc.getValue("tel-1.png")["cuando"]!!.jsonPrimitive.long)
        assertTrue(File(carpetaPc, "tel-1.png").readBytes().contentEquals(File(galMovil.carpeta, "tel-1.png").readBytes()))
        for (n in galMovil.nombres()) assertEquals(n, seVaEnMovil(n), seVaEnPc(n))
        assertEquals(ahora - 2 * DIA_MS + 7 * DIA_MS, seVaEnMovil("pc-1.png"))
        assertTrue(desdeElPc().contains("capturas=0 tiradas=0"))
        assertEquals(0, desdeElMovil().capturas)
    }

    @Test
    fun `galeria - la fecha acordada manda en los dos, con otros dias, siete mas y conservar`() {
        galMovil.diasAqui = 30   // el móvil borra a los 30 días; el PC, a los 7
        val ahora = segundos(System.currentTimeMillis())
        capturaEnPc("pc-1.png", ahora - DIA_MS)
        capturaEnPc("pc-2.png", ahora - DIA_MS)
        galMovil.hacer("tel-1.png", ahora - DIA_MS)
        desdeElMovil(unirme = true)
        desdeElMovil()
        // Cada una, con la fecha de donde se hizo, en los dos.
        assertEquals(ahora + 6 * DIA_MS, seVaEnPc("pc-1.png"))
        assertEquals(ahora + 6 * DIA_MS, seVaEnMovil("pc-1.png"))
        assertEquals(ahora + 29 * DIA_MS, seVaEnMovil("tel-1.png"))
        assertEquals(ahora + 29 * DIA_MS, seVaEnPc("tel-1.png"))
        assertEquals("fijada en el registro del móvil", ahora + 6 * DIA_MS,
            CaducidadDeCapturas.leer(movil.filesDir, ahora).fijadas["pc-1.png"])
        // «7 días más» en el móvil: se ve en el PC (llama el PC).
        pasaUnPoco()
        val t = System.currentTimeMillis()
        CaducidadDeCapturas.cambiar(movil.filesDir, t) { CaducidadDeCapturas.prorrogada(it, "pc-1.png", ahora - DIA_MS, t, galMovil.diasAqui) }
        assertEquals(ahora + 13 * DIA_MS, seVaEnMovil("pc-1.png"))
        pasaUnPoco()
        desdeElPc()
        assertEquals(ahora + 13 * DIA_MS, seVaEnPc("pc-1.png"))
        // Conservar en el PC (su registro, `conservadas`): se ve en el móvil (llama el móvil).
        pasaUnPoco()
        val reg = Json.parseToJsonElement(registroPc.readText()).jsonObject
        val conservadas = (reg["conservadas"]?.jsonArray.orEmpty()) + JsonPrimitive("pc-2.png")
        registroPc.writeText(JsonObject(reg + ("conservadas" to JsonArray(conservadas))).toString())
        assertNull(seVaEnPc("pc-2.png"))
        pasaUnPoco()
        desdeElMovil()
        assertNull("conservada en el PC, conservada en el móvil", seVaEnMovil("pc-2.png"))
        assertTrue("pc-2.png" in CaducidadDeCapturas.leer(movil.filesDir, System.currentTimeMillis()).conservadas)
        // Otra vuelta desde el PC: nada se mueve.
        desdeElPc()
        assertEquals(ahora + 13 * DIA_MS, seVaEnPc("pc-1.png"))
        assertEquals(ahora + 13 * DIA_MS, seVaEnMovil("pc-1.png"))
        assertNull(seVaEnPc("pc-2.png"))
        assertNull(seVaEnMovil("pc-2.png"))
    }

    @Test
    fun `galeria - quitada a mano en uno se quita en el otro, a su papelera, en los dos sentidos`() {
        val ahora = segundos(System.currentTimeMillis())
        capturaEnPc("pc-1.png", ahora - DIA_MS)
        galMovil.hacer("tel-1.png", ahora - DIA_MS)
        desdeElMovil(unirme = true)
        desdeElMovil()
        assertEquals(setOf("captura.png", "pc-1.png", "tel-1.png"), capturasPc().keys)
        // Quitada en el móvil; llama el móvil.
        pasaUnPoco()
        assertTrue(File(galMovil.carpeta, "pc-1.png").delete())
        assertEquals("la quitada no vuelve a pasar", 0, desdeElMovil().capturas)
        assertEquals(setOf("captura.png", "tel-1.png"), capturasPc().keys)
        assertEquals(listOf("pc-1.png"), papeleraPc.list()!!.toList())
        assertTrue(borradaEnPc("pc-1.png"))
        // Quitada en el PC; llama el PC.
        pasaUnPoco()
        assertTrue(File(carpetaPc, "tel-1.png").delete())
        val salida = desdeElPc()
        assertTrue("lo que contó el PC: $salida", salida.contains("capturas=0"))
        assertEquals(listOf("captura.png"), galMovil.nombres())
        assertEquals(listOf("tel-1.png"), galMovil.papelera.list()!!.toList())
        // Ninguna vuelve, llame quien llame.
        desdeElMovil()
        desdeElPc()
        assertEquals(listOf("captura.png"), galMovil.nombres())
        assertEquals(setOf("captura.png"), capturasPc().keys)
    }

    @Test
    fun `galeria - lo caducado no viaja ni deja marca de borrado, en los dos sentidos`() {
        val ahora = segundos(System.currentTimeMillis())
        galMovil.hacer("vieja-tel.png", ahora - 10 * DIA_MS)
        capturaEnPc("vieja-pc.png", ahora - 10 * DIA_MS)
        capturaEnPc("nueva-pc.png", ahora - DIA_MS)
        desdeElMovil(unirme = true)
        desdeElMovil()
        assertEquals(listOf("captura.png", "nueva-pc.png", "vieja-tel.png"), galMovil.nombres())
        assertEquals(setOf("captura.png", "nueva-pc.png", "vieja-pc.png"), capturasPc().keys)
        // Cada uno barre la suya (caducó): eso no es «quitarla a mano».
        File(galMovil.carpeta, "vieja-tel.png").renameTo(File(galMovil.papelera, "vieja-tel.png"))
        File(carpetaPc, "vieja-pc.png").renameTo(File(papeleraPc, "vieja-pc.png"))
        pasaUnPoco()
        desdeElPc()
        pasaUnPoco()
        desdeElMovil()
        for (n in listOf("vieja-tel.png", "vieja-pc.png")) {
            assertFalse("$n en el móvil", GaleriaCompartida.leer(movil.filesDir).porNombre[n]?.borrada ?: false)
            assertFalse("$n en el PC", borradaEnPc(n))
        }
        assertEquals(listOf("captura.png", "nueva-pc.png"), galMovil.nombres())
        assertEquals(setOf("captura.png", "nueva-pc.png"), capturasPc().keys)
    }

    // ------------------------------------------------------- grabaciones y carpetas (26-oct-2026)
    // El usuario, con la v0.142.0: «no se están sincronizando las grabaciones, ni hay una interfaz
    // para verlas». Una clase grabada como la graba el PC (`pc-simulado clase`, que llama a
    // `carpeta_de_paginas` de verdad) tiene que llegar entera —voz, vídeo de varios megas con
    // paréntesis en el nombre, capturas y su carpeta— y verse; y una grabada aquí, llegar al PC y
    // que el PC la lea como grabación.

    /** La huella FNV-1a de 64 bits de un fichero, como `huella` de `pc-simulado`. */
    private fun huella(f: File): String {
        var h = -0x340d631b7bdddcdbL
        f.inputStream().buffered().use { e ->
            while (true) {
                val b = e.read()
                if (b < 0) break
                h = (h xor b.toLong()) * 0x100000001b3L
            }
        }
        return java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
    }

    @Test
    fun `una clase grabada en el PC llega al movil con voz, video, capturas y su carpeta, y se ve`() {
        desdeElMovil(unirme = true)
        desdeElMovil()
        val hecha = Json.parseToJsonElement(orden("clase", pc.path, "Clases del PC", "5").trim().lines().last()).jsonObject
        fun id(k: String) = hecha.s(k)!!
        desdeElMovil()
        val ms = movil.leerMensajes()
        val porId = ms.associateBy { it.id }
        val voz = porId[id("voz")] ?: error("la voz no llegó: ${ms.map { it.id to it.nombre }}")
        assertEquals(Clase.VOZ, voz.clase)
        assertEquals("clase", voz.grabacion)
        assertEquals(id("video"), voz.video!!["id"]!!.jsonPrimitive.content)
        assertEquals(2, voz.capturas!!.size)
        assertEquals(300_000L, File(voz.ruta!!).length())
        val video = porId.getValue(id("video"))
        assertEquals("Clase 25-oct (1).mp4", video.nombre)
        assertEquals(id("voz"), video.enGrabacion!!["de"]!!.jsonPrimitive.content)
        val mp4 = File(video.ruta!!)
        assertTrue("el vídeo está en el móvil: ${video.ruta}", mp4.isFile)
        assertEquals(hecha["largoDelVideo"]!!.jsonPrimitive.long, mp4.length())
        assertEquals("el vídeo llega byte a byte", id("huellaDelVideo"), huella(mp4))
        for ((n, c) in hecha["capturas"]!!.jsonArray.withIndex()) {
            val img = porId.getValue(c.jsonPrimitive.content)
            assertEquals(Clase.IMAGEN, img.clase)
            assertEquals("PNG captura ${n + 1}", File(img.ruta!!).readText())
            assertEquals(id("voz"), img.enGrabacion!!["de"]!!.jsonPrimitive.content)
        }
        // La carpeta de la grabación, entera y leída como grabación.
        val carpeta = porId.getValue(id("carpeta"))
        val doc = com.forge.pixpin.guardados.CarpetaDePaginas.deMensaje(carpeta)!!
        assertTrue(doc.esGrabacion)
        assertEquals(id("voz"), doc.voz)
        assertEquals(id("video"), doc.video)
        assertEquals(90_000L, doc.duracionMs)
        assertEquals(listOf(id("voz"), id("video")) + hecha["capturas"]!!.jsonArray.map { it.jsonPrimitive.content }, doc.paginas.map { it.id })
        assertEquals(listOf(65_000L, 80_000L), doc.capturas().map { it.ms })
        val diario = com.forge.pixpin.guardados.CarpetaDePaginas.deMensaje(porId.getValue(id("diario")))!!
        assertEquals(listOf(id("nota"), id("foto")), diario.paginas.map { it.id })
        assertEquals("JPG de la pizarra", File(porId.getValue(id("foto")).ruta!!).readText())
        // Y se ve: en el chat del proyecto, las dos tarjetas de carpeta y nada de lo de dentro suelto;
        // dentro de cada carpeta, todas sus piezas, con su fichero.
        val delChat = ms.filter { Disco.chatDe(it) == id("proyecto") }
        val sueltos = com.forge.pixpin.guardados.CarpetaDePaginas.sueltosDelChat(delChat)
        assertEquals(setOf(id("carpeta"), id("diario")), sueltos.filter { com.forge.pixpin.guardados.CarpetaDePaginas.esCarpeta(it) }.map { it.id }.toSet())
        assertTrue("nada de dentro suelto", sueltos.none { it.id in doc.paginas.map { p -> p.id } + diario.paginas.map { p -> p.id } })
        assertEquals(doc.paginas.size, doc.vivas(delChat).size)
        // Otra vuelta: nada se mueve (el vídeo con paréntesis no vuelve a viajar) ni se repite.
        val quieta = desdeElMovil()
        assertEquals("otra vuelta no mueve nada", 0, quieta.traidos + quieta.enviados + quieta.fusionados)
        desdeElPc()
        val otra = desdeElMovil()
        assertEquals(0, otra.traidos + otra.enviados + otra.fusionados)
        sinRepetidos()
    }

    @Test
    fun `una clase grabada en el movil llega al PC con sus ficheros y el PC la lee como grabacion`() {
        desdeElMovil(unirme = true)
        desdeElMovil()
        val ahora = System.currentTimeMillis()
        val obra = movil.leerProyectos().single { it.nombre == "Obra del PC" }.id
        // Como `GrabarClaseService.guardar`: la voz y el vídeo en `guardados/`, las capturas también.
        val m4a = File(movil.filesDir, "guardados/clase_$ahora.m4a").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(200_000) { 3 }) }
        val mp4 = File(movil.filesDir, "guardados/${ahora}_Clase (2).mp4").apply { writeBytes(ByteArray((3 shl 20) + 77) { (it % 249).toByte() }) }
        val png = File(movil.filesDir, "guardados/clase-$ahora-1.png").apply { writeText("PNG del móvil") }
        val vozId = "voz-movil-$ahora"
        val videoId = "video-movil-$ahora"
        val capId = "cap-movil-$ahora"
        val enlaces = listOf(com.forge.pixpin.guardados.GrabarClase.Enlace(30_000, capId))
        val lista = listOf(
            Mensaje(id = vozId, cuando = ahora, clase = Clase.VOZ, ruta = m4a.path, nombre = "Clase 26-oct.m4a", bytes = m4a.length(),
                duracionMs = 60_000, proyecto = obra, grabacion = com.forge.pixpin.guardados.GrabarClase.CLASE,
                capturas = com.forge.pixpin.guardados.GrabarClase.capturasEnJson(enlaces),
                video = com.forge.pixpin.guardados.GrabarClase.videoEnJson(videoId)),
            Mensaje(id = videoId, cuando = ahora + 1, clase = Clase.ARCHIVO, ruta = mp4.path, nombre = "Clase (2).mp4", bytes = mp4.length(),
                proyecto = obra, enGrabacion = com.forge.pixpin.guardados.GrabarClase.enGrabacion(vozId, 0)),
            Mensaje(id = capId, cuando = ahora + 2, clase = Clase.IMAGEN, ruta = png.path, nombre = png.name, texto = "⏱ 0:30 · Clase 26-oct",
                proyecto = obra, enGrabacion = com.forge.pixpin.guardados.GrabarClase.enGrabacion(vozId, 30_000)),
        )
        val doc = com.forge.pixpin.guardados.GrabarClase.carpetaDeLaGrabacion(vozId, 60_000, videoId, enlaces)
        val carpeta = com.forge.pixpin.guardados.CarpetaDePaginas.mensajeNuevo("Clase 26-oct", ahora + 3, obra, doc)
        for (m in lista + carpeta) File(movil.filesDir, "guardados.jsonl").appendText(Disco.JSON.encodeToString(Mensaje.serializer(), m) + "\n")
        movil.sellar()

        desdeElPc()
        val enPc = verPc()["chats"]!!.jsonArray.map { it.jsonObject }.single { it.s("nombre") == "Obra del PC" }
        val porId = enPc["mensajes"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("id") }
        val v = porId[vozId] ?: error("la voz no llegó al PC: ${porId.keys}")
        assertEquals("Voz", v.s("clase"))
        assertEquals(200_000L, v["largo"]!!.jsonPrimitive.long)
        assertEquals("clase", v["resto"]!!.jsonObject.s("grabacion"))
        val vid = porId.getValue(videoId)
        assertEquals("el PC abre el vídeo entero", huella(mp4), vid.s("huella"))
        assertEquals(vozId, vid["resto"]!!.jsonObject["enGrabacion"]!!.jsonObject.s("de"))
        assertEquals("PNG del móvil".toByteArray().size.toLong(), porId.getValue(capId)["largo"]!!.jsonPrimitive.long)
        val c = porId.getValue(carpeta.id)["carpeta"]!!.jsonObject
        assertEquals("el PC la lee como grabación: $c", true, c["grabacion"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(vozId, c.s("voz"))
        assertEquals(videoId, c.s("video"))
        assertEquals(listOf(capId), c["capturas"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("el PC no la enseña suelta", setOf(vozId, videoId, capId), enPc["dentro"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        // El texto de la carpeta, byte a byte el mismo en los dos.
        assertEquals(carpeta.texto, porId.getValue(carpeta.id).s("texto"))
        val quieta = desdeElMovil()
        assertEquals("otra vuelta no mueve nada", 0, quieta.traidos + quieta.enviados + quieta.fusionados)
        sinRepetidos()
    }

    private companion object {
        const val CODIGO = "ABCDE23456"
    }
}
