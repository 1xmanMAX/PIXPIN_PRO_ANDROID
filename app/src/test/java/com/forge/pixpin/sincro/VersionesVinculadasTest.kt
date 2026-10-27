package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.VersionesEnElChat
import com.forge.pixpin.guardados.reenviado
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Las versiones vinculadas, sin red** (27-oct-2026): qué es ajeno, qué se versiona, cómo se llama
 * la copia y qué lleva, que el vínculo se conserva al guardar y al fusionar, y que un borrador que
 * no se tocó no deja nada. Ver [VersionesVinculadas].
 */
class VersionesVinculadasTest {

    private lateinit var files: File

    /** Max (`MAXX`, y su PC `MAPC`) y Ana (`ANA1`) en un chat compartido, visto desde el teléfono de Max. */
    private val lados = LadosDelChat.nuevo(listOf("MAXX", "MAPC"), listOf("MAXX" to "Max", "ANA1" to "Ana", "MAPC" to "Max"))

    @Before
    fun montar() {
        files = Files.createTempDirectory("versiones").toFile()
    }

    @After
    fun desmontar() {
        files.deleteRecursively()
    }

    private fun m(id: String, clase: Clase, aparato: String?, texto: String = "", ruta: String? = null, referencia: String? = null, miniapp: String? = null) =
        Mensaje(id = id, cuando = 1_000, clase = clase, texto = texto, ruta = ruta, referencia = referencia, proyecto = "p1",
            numero = 3, aparato = aparato, uid = Codigos.de("u:$id"), miniapp = miniapp, fijado = true, emoji = "⭐", unido = true)

    private fun lienzo(id: String, texto: String, version: Int = 1) {
        val f = File(files, "pins/draw/$id.excalidraw.gz").apply { parentFile!!.mkdirs() }
        GZIPOutputStream(f.outputStream()).use {
            it.write("""{"elements":[{"id":"e1","type":"text","text":"$texto","version":$version,"versionNonce":1,"updated":1}],"files":{}}""".toByteArray())
        }
    }

    // ------------------------------------------------------------------ decidir

    @Test
    fun `es ajeno solo lo de otra persona en un chat compartido`() {
        assertTrue(VersionesVinculadas.esAjeno(m("a", Clase.DIBUJO, "ANA1", referencia = "x"), lados))
        // Lo de mis aparatos, lo sin código y lo del asistente es mío.
        assertFalse(VersionesVinculadas.esAjeno(m("b", Clase.DIBUJO, "MAXX", referencia = "x"), lados))
        assertFalse(VersionesVinculadas.esAjeno(m("c", Clase.DIBUJO, "MAPC", referencia = "x"), lados))
        assertFalse(VersionesVinculadas.esAjeno(m("d", Clase.DIBUJO, null, referencia = "x"), lados))
        assertFalse(VersionesVinculadas.esAjeno(m("e", Clase.NOTA, "IA"), lados))
        // El código largo de su aparato se acorta igual que en la burbuja.
        val largo = "id-del-movil-de-ana"
        val conLargo = LadosDelChat.nuevo(listOf("MAXX"), listOf(Aparato(largo, "").codigo to "Ana"))
        assertTrue(VersionesVinculadas.esAjeno(m("f", Clase.NOTA, largo), conLargo))
        // En un chat que no es compartido, nada es ajeno: todo se edita en su sitio, como siempre.
        assertFalse(VersionesVinculadas.esAjeno(m("a", Clase.DIBUJO, "ANA1", referencia = "x"), null))
        assertFalse(VersionesVinculadas.seVersiona(m("a", Clase.DIBUJO, "ANA1", referencia = "x"), null))
    }

    @Test
    fun `que se versiona y como`() {
        assertEquals(VersionesVinculadas.Como.BORRADOR, VersionesVinculadas.como(m("1", Clase.DIBUJO, "ANA1", referencia = "x")))
        assertEquals(VersionesVinculadas.Como.BORRADOR, VersionesVinculadas.como(m("2", Clase.PAGINA, "ANA1", ruta = "/f/guardados/a.pdf", referencia = "x")))
        assertEquals(VersionesVinculadas.Como.BORRADOR, VersionesVinculadas.como(m("3", Clase.IMAGEN, "ANA1", ruta = "/f/guardados/a.jpg")))
        assertEquals(VersionesVinculadas.Como.BORRADOR, VersionesVinculadas.como(m("4", Clase.ARCHIVO, "ANA1", ruta = "/f/guardados/1_plano.pdf")))
        assertEquals(VersionesVinculadas.Como.BORRADOR, VersionesVinculadas.como(m("5", Clase.ARCHIVO, "ANA1", ruta = "/f/guardados/informe.docx")))
        assertEquals(VersionesVinculadas.Como.PROVISIONAL, VersionesVinculadas.como(m("6", Clase.MINIAPP, "ANA1", miniapp = "gastos")))
        assertEquals(VersionesVinculadas.Como.PROVISIONAL, VersionesVinculadas.como(m("7", Clase.MINIAPP, "ANA1", miniapp = "carpeta")))
        assertEquals(VersionesVinculadas.Como.AL_MOMENTO, VersionesVinculadas.como(m("8", Clase.NOTA, "ANA1", texto = "hola")))
        // Lo que no: una lista de tareas (tachar en una compartida es lo que se quiere), una tabla
        // del chat (solo se mira), un vídeo, un zip, un audio.
        assertNull(VersionesVinculadas.como(m("9", Clase.MINIAPP, "ANA1", miniapp = "tareas")))
        assertNull(VersionesVinculadas.como(m("10", Clase.MINIAPP, "ANA1", miniapp = "tabla")))
        assertNull(VersionesVinculadas.como(m("11", Clase.IMAGEN, "ANA1", ruta = "/f/guardados/v.mp4")))
        assertNull(VersionesVinculadas.como(m("12", Clase.ARCHIVO, "ANA1", ruta = "/f/guardados/a.zip")))
        assertNull(VersionesVinculadas.como(m("13", Clase.VOZ, "ANA1", ruta = "/f/guardados/a.m4a")))
        assertNull(VersionesVinculadas.como(m("14", Clase.DIBUJO, "ANA1")))
        // Mis cosas no se versionan: se editan en su sitio.
        assertFalse(VersionesVinculadas.seVersiona(m("15", Clase.DIBUJO, "MAXX", referencia = "x"), lados))
        assertTrue(VersionesVinculadas.seVersiona(m("16", Clase.DIBUJO, "ANA1", referencia = "x"), lados))
    }

    // ------------------------------------------------------------------ la copia

    @Test
    fun `la version es otro mensaje mio que contesta al original`() {
        val original = m("orig", Clase.DIBUJO, "ANA1", texto = "planta", referencia = "lienzo-ana")
        val v = VersionesVinculadas.nueva(original, "nuevo", "NUEVOUID23", "MAXX", 5_000, 2)
        assertEquals("nuevo", v.id)
        assertEquals("NUEVOUID23", v.uid)
        assertEquals("MAXX", v.aparato)
        assertEquals(5_000, v.cuando)
        assertEquals(Codigos.unico(original), v.versionDe)
        assertEquals(2, v.versionN)
        assertEquals("orig", v.respondeA)
        assertEquals("p1", v.proyecto)
        assertEquals("planta", v.texto)
        // Lo que es de la organización del chat o de este aparato no se copia; el número lo pone quien guarda.
        assertEquals(0, v.numero)
        assertFalse(v.fijado)
        assertNull(v.emoji)
        assertFalse(v.unido)
        // Y se numera como cualquier mensaje nuevo de su chat.
        assertEquals(4, VersionesVinculadas.conNumero(listOf(original), v).numero)
    }

    @Test
    fun `el vinculo se guarda, se lee y se fusiona sin perderse`() {
        val original = m("orig", Clase.DIBUJO, "ANA1", referencia = "lienzo-ana")
        val v = VersionesVinculadas.nueva(original, "nuevo", "NUEVOUID23", "MAXX", 5_000, 1)
        val linea = Disco.JSON.encodeToString(Mensaje.serializer(), v)
        val obj = Json.parseToJsonElement(linea).jsonObject
        assertEquals(JsonPrimitive(Codigos.unico(original)), obj["versionDe"])
        assertEquals(JsonPrimitive(1), obj["versionN"])
        assertEquals(v, Disco.JSON.decodeFromString(Mensaje.serializer(), linea))
        // Un mensaje normal no lleva las claves.
        val normal = Disco.JSON.encodeToString(Mensaje.serializer(), original)
        assertFalse("versionDe" in normal)
        assertFalse("versionN" in normal)
        // Lo escrito por un PixPin de antes (sin las claves) se lee igual.
        assertNull(Disco.JSON.decodeFromString(Mensaje.serializer(), normal).versionDe)
        // La fusión de tres vías lo conserva: yo lo fijo aquí y la otra persona no lo toca.
        val base = Json.parseToJsonElement(linea)
        val mio = Json.parseToJsonElement(Disco.JSON.encodeToString(Mensaje.serializer(), v.copy(fijado = true)))
        val junto = Fusion.json(base, mio, base) as JsonObject
        assertEquals(JsonPrimitive(Codigos.unico(original)), junto["versionDe"])
        assertEquals(JsonPrimitive(true), junto["fijado"])
        // Reenviar una versión a otro chat ya no es versión de nada de allí.
        assertNull(reenviado(v, "p2", 9, "otro").versionDe)
    }

    // ------------------------------------------------------------------ borradores

    @Test
    fun `un lienzo ajeno se edita en una copia y el original no cambia`() {
        lienzo("lienzo-ana", "planta de Ana")
        val original = m("orig", Clase.DIBUJO, "ANA1", referencia = "lienzo-ana")
        val e = VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 5_000, id = "v1", uid = "VERSIONUID")
        assertFalse(e.provisional)
        assertEquals("dib-v1", e.version.referencia)
        assertTrue(File(files, "pins/draw/dib-v1.excalidraw.gz").isFile)
        // Sin tocar: nada que añadir, y el borrador espera por si el editor sigue abierto.
        assertTrue(VersionesVinculadas.cerrar(files, listOf(original), 6_000).nada)
        assertEquals(1, VersionesVinculadas.enCurso(files).size)
        // Volver a abrirlo sigue con el mismo borrador.
        assertEquals("v1", VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 7_000).version.id)
        // El editor escribe en la copia (otra `version` del elemento).
        lienzo("dib-v1", "planta de Ana con lo de Max", version = 2)
        val c = VersionesVinculadas.cerrar(files, listOf(original), 8_000)
        assertEquals(listOf("v1"), c.anadir.map { it.id })
        assertEquals(8_000, c.anadir.single().cuando)
        assertTrue(VersionesVinculadas.enCurso(files).isEmpty())
        val chat = VersionesVinculadas.conVersiones(listOf(original), c)
        assertEquals(listOf("orig", "v1"), chat.map { it.id })
        assertEquals(listOf("v1"), VersionesVinculadas.versionesDe(chat, original).map { it.id })
        assertEquals("orig", VersionesVinculadas.originalDe(chat, chat[1])?.id)
        // El original, tal cual.
        assertEquals(chat[0], original)
        assertEquals(VersionesVinculadas.huellaDeDibujo(File(files, "pins/draw/lienzo-ana.excalidraw.gz"), false), "e1:1")
        // Abrir otra vez el original hace la versión 2, otra copia.
        val otra = VersionesVinculadas.empezar(files, original, chat, "MAXX", 9_000, id = "v2", uid = "VERSIONUI2")
        assertEquals(2, otra.version.versionN)
        assertEquals("dib-v2", otra.version.referencia)
    }

    @Test
    fun `un borrador que nadie toca se tira a las doce horas`() {
        lienzo("lienzo-ana", "planta")
        val original = m("orig", Clase.DIBUJO, "ANA1", referencia = "lienzo-ana")
        VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 5_000, id = "v1", uid = "VERSIONUID")
        assertTrue(VersionesVinculadas.cerrar(files, listOf(original), 5_000 + VersionesVinculadas.CADUCA_MS + 1).nada)
        assertTrue(VersionesVinculadas.enCurso(files).isEmpty())
        assertFalse(File(files, "pins/draw/dib-v1.excalidraw.gz").exists())
        assertTrue(File(files, "pins/draw/lienzo-ana.excalidraw.gz").exists())
    }

    @Test
    fun `mi tinta sobre el PDF de otra persona va en mi copia del PDF`() {
        val pdf = File(files, "guardados/1761_plano.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF plano") }
        val original = m("orig", Clase.ARCHIVO, "ANA1", ruta = pdf.absolutePath).copy(nombre = "plano.pdf")
        val suyo = Codigos.unico(original)
        // Lo que Ana ya anotó: su tinta en la página 0 y sus marcadores.
        val tintaDeAna = File(files, "pins/draw/anot-$suyo-p0.excalidraw.gz")
        lienzo("anot-$suyo-p0", "flecha de Ana")
        File(files, "pins/draw/anot-$suyo.marcas").writeText("1|Portada")
        val e = VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 5_000, id = "v1", uid = "VERSIONUID")
        val copia = File(e.version.ruta!!)
        assertNotEquals(pdf, copia)
        assertEquals("5000_v1_plano.pdf", copia.name)
        assertEquals("%PDF plano", copia.readText())
        assertEquals("plano.pdf", e.version.nombre)
        // Lo anotado viene con la copia, con el código de la versión, y el lector lo encuentra por la ruta.
        assertTrue(File(files, "pins/draw/anot-VERSIONUID-p0.excalidraw.gz").isFile)
        assertEquals("1|Portada", File(files, "pins/draw/anot-VERSIONUID.marcas").readText())
        assertEquals("VERSIONUID", VersionesVinculadas.uidDeBorrador(files, copia.absolutePath))
        assertEquals("VERSIONUID", AnotacionesDelAdjunto.uidDe(files, copia.absolutePath))
        // Leer (por dónde iba) no es cambiar.
        File(files, "pins/draw/anot-VERSIONUID.sitio").writeText("0.5")
        assertTrue(VersionesVinculadas.cerrar(files, listOf(original), 6_000).nada)
        // Mi raya, sí.
        lienzo("anot-VERSIONUID-p0", "flecha de Ana y raya de Max", version = 3)
        val c = VersionesVinculadas.cerrar(files, listOf(original), 7_000)
        assertEquals(copia.absolutePath, c.anadir.single().ruta)
        assertEquals("e1:1", VersionesVinculadas.huellaDeDibujo(tintaDeAna, false))
        assertEquals("%PDF plano", pdf.readText())
    }

    @Test
    fun `una foto sin dibujo no cuenta la foto de fondo como cambio`() {
        val foto = File(files, "guardados/1_foto.jpg").apply { parentFile!!.mkdirs(); writeText("jpg") }
        val original = m("orig", Clase.IMAGEN, "ANA1", ruta = foto.absolutePath)
        val e = VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 5_000, id = "v1", uid = "VERSIONUID")
        assertNull(e.version.referencia)
        assertTrue(e.sinFotos)
        // El editor pone la foto de fondo al abrir: no es un cambio.
        val f = File(files, "pins/draw/foto-v1.excalidraw.gz").apply { parentFile!!.mkdirs() }
        GZIPOutputStream(f.outputStream()).use { it.write("""{"elements":[{"id":"bg","type":"image","version":1}]}""".toByteArray()) }
        assertTrue(VersionesVinculadas.cerrar(files, listOf(original), 6_000).nada)
        GZIPOutputStream(f.outputStream()).use { it.write("""{"elements":[{"id":"bg","type":"image","version":1},{"id":"r","type":"line","version":1}]}""".toByteArray()) }
        assertEquals(1, VersionesVinculadas.cerrar(files, listOf(original), 7_000).anadir.size)
    }

    @Test
    fun `una mini-app provisional que no se toca se quita`() {
        val original = m("orig", Clase.MINIAPP, "ANA1", texto = "# Viaje\n- 10 gasolina", miniapp = "gastos")
        val e = VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 5_000, id = "v1", uid = "VERSIONUID")
        assertTrue(e.provisional)
        var chat = VersionesVinculadas.conVersiones(listOf(original), VersionesVinculadas.Cierre(listOf(e.version), emptySet()))
        assertEquals(2, chat.size)
        assertEquals(setOf("v1"), VersionesVinculadas.cerrar(files, chat, 6_000).quitar)
        // Y si se cambia, se queda.
        val e2 = VersionesVinculadas.empezar(files, original, listOf(original), "MAXX", 7_000, id = "v2", uid = "VERSIONUI2")
        chat = VersionesVinculadas.conVersiones(listOf(original), VersionesVinculadas.Cierre(listOf(e2.version), emptySet()))
        chat = chat.map { if (it.id == "v2") it.copy(texto = it.texto + "\n- 20 peaje") else it }
        assertTrue(VersionesVinculadas.cerrar(files, chat, 8_000).nada)
        assertTrue(VersionesVinculadas.enCurso(files).isEmpty())
        assertEquals("# Viaje\n- 10 gasolina", chat.first { it.id == "orig" }.texto)
    }

    @Test
    fun `un cambio de un toque sobre una nota ajena hace la version con el cambio`() {
        val original = m("orig", Clase.NOTA, "ANA1", texto = "quedamos a las 5")
        val v = VersionesVinculadas.alMomento(files, original, listOf(original), "MAXX", 5_000, { it.copy(texto = "quedamos a las 6", id = "trampa") }, id = "v1", uid = "VERSIONUID")
        assertEquals("v1", v.id)
        assertEquals("quedamos a las 6", v.texto)
        assertEquals(Codigos.unico(original), v.versionDe)
        assertEquals("orig", v.respondeA)
        assertTrue(VersionesVinculadas.enCurso(files).isEmpty())
    }

    // ------------------------------------------------------------------ los rótulos

    @Test
    fun `los rotulos dicen de quien es el original y cuantas versiones tiene`() {
        val deAna = m("a", Clase.DIBUJO, "ANA1", referencia = "x")
        val mio = m("b", Clase.DIBUJO, "MAXX", referencia = "y")
        val miVersion = VersionesVinculadas.nueva(deAna, "va", "VERSIONUIA", "MAXX", 2_000, 1)
        val suVersion = VersionesVinculadas.nueva(mio, "vb", "VERSIONUIB", "ANA1", 3_000, 1)
        val otraMia = VersionesVinculadas.nueva(deAna, "va2", "VERSIONUIC", "MAXX", 4_000, 2)
        val chat = listOf(deAna, mio, miVersion, suVersion, otraMia)
        val autor: (Mensaje) -> String? = { (lados.quien(it.aparato) as? Quien.Otro)?.nombre }
        val r = VersionesEnElChat.delChat(chat, true, autor)
        assertEquals(VersionesEnElChat.Rotulo("a", "Versión de Ana"), r.cabecera["va"])
        assertEquals(VersionesEnElChat.Rotulo("a", "Versión 2 de Ana"), r.cabecera["va2"])
        assertEquals(VersionesEnElChat.Rotulo("b", "Versión de lo tuyo"), r.cabecera["vb"])
        assertEquals(listOf("Versión 1 · tuya", "Versión 2 · tuya"), r.versiones["a"]!!.map { it.texto })
        assertEquals(listOf("Versión 1 · Ana"), r.versiones["b"]!!.map { it.texto })
        assertNull(r.versiones["va"])
        assertEquals("2 versiones", VersionesEnElChat.cuantas(2))
        // Sin el original en el chat, el rótulo no lleva a ningún sitio.
        assertEquals("", VersionesEnElChat.delChat(listOf(miVersion), true, autor).cabecera["va"]!!.id)
        assertNotNull(VersionesEnElChat.delChat(chat, false, autor).cabecera["va"])
        assertTrue(VersionesEnElChat.delChat(listOf(deAna, mio), true, autor).cabecera.isEmpty())
    }
}
