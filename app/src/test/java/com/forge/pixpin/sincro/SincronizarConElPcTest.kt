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
                    for (chat in chats) {
                        val prep = sesion.preparar(chat)
                        sesion.aplicar(prep, hecho)
                        sesion.aplicarArchivos(sesion.prepararArchivos(prep), hecho)
                        sesion.cerrar(prep)
                    }
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

    private companion object {
        const val CODIGO = "ABCDE23456"
    }
}
