package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private var responde: Process? = null
    private var puerto = 0

    @Before
    fun montar() {
        assumeTrue("Sin PIXPIN_PC_SIMULADO no hay PC con quien hablar", programa != null && File(programa).canExecute())
        raiz = Files.createTempDirectory("pc-movil").toFile()
        pc = File(raiz, "PixPin Max")
        movil = Disco(File(raiz, "movil/files").apply { mkdirs() })
        movil.identidad.guardar(Identidad(Aparato("id-movil", "Teléfono")))
        // Con galería, como el teléfono: pedírsela al PC no puede romper nada.
        movil.capturas = GaleriaQueViajaTest.EnCarpeta(movil.filesDir).also { it.hacer("captura.png", System.currentTimeMillis()) }
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

    /** El móvil llama y el PC responde: todos los chats, como la pantalla sin preguntas. */
    private fun desdeElMovil(unirme: Boolean = false): Sesion.Hecho {
        val hecho = Sesion.Hecho()
        Socket(InetAddress.getLoopbackAddress(), puerto).use { s ->
            val sesion = Sesion.conectar(s.getInputStream(), s.getOutputStream(), movil, unirme, if (unirme) CODIGO else null)
            try {
                if (!unirme) {
                    val chats = (movil.chats().map { it.id } + sesion.catalogo().map { it.id }).distinct()
                    for (chat in chats) {
                        val prep = sesion.preparar(chat)
                        sesion.aplicar(prep, hecho)
                        sesion.aplicarArchivos(sesion.prepararArchivos(prep), hecho)
                        sesion.cerrar(prep)
                    }
                    // Como la pantalla: la galería detrás. El PC de hoy no sabe de galerías y
                    // contesta «No sé qué es»: no se hace nada y la vuelta sigue sana.
                    if (movil.capturas != null) assertFalse("el PC no tiene galería", sesion.galeria(hecho))
                }
                sesion.adios()
            } finally { sesion.soltar() }
        }
        return hecho
    }

    /** El PC llama (su vuelta entera) y el móvil responde. */
    private fun desdeElPc() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hilo = Thread {
            runCatching { server.accept().use { s -> Respondedor(movil).atender(s.getInputStream(), s.getOutputStream()) } }
            server.close()
        }
        hilo.start()
        orden("dirigir", pc.path, server.localPort.toString())
        hilo.join(10_000)
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

    private companion object {
        const val CODIGO = "ABCDE23456"
    }
}
