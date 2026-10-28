package com.forge.pixpin.guardados

import com.forge.pixpin.audio.RespaldoWav
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

/**
 * **Una grabación cortada, recuperada en su chat** (28-oct-2026, «Grabar sin perder nada»): el diario
 * en el disco con `AtomicFile` (ida y vuelta, una escritura a medias que no lo rompe, los pendientes) y
 * una clase que Android mató a la mitad —la nota ya cerrada, su respaldo WAV, dos capturas en la carpeta
 * del diario, una pausa y un hueco— que entra en el chat como si se hubiera pulsado «Terminar»: la nota
 * con sus capturas en su minuto, su onda (del respaldo) y su reloj de pared, cada captura enlazada, y la
 * carpeta de la grabación. Después, el diario y el respaldo ya no están.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecuperarGrabacionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val T0 = 1_761_645_900_000L

    private fun caja(tipo: String, cuerpo: Int): ByteArray {
        val b = ByteBuffer.allocate(cuerpo + 8)
        b.putInt(cuerpo + 8)
        b.put(tipo.toByteArray(Charsets.ISO_8859_1))
        return b.array()
    }

    private fun mp4Cerrado() = caja("ftyp", 8) + caja("mdat", 64) + caja("moov", 16)

    /** Un respaldo WAV de [segundos] a 8 kHz con algo de sonido. */
    private fun respaldo(f: File, segundos: Int) {
        val r = RespaldoWav.Respaldo(f, 8_000, 1)
        val bloque = ShortArray(8_000) { ((it % 80) * 200 - 8_000).toShort() }
        repeat(segundos) { r.escribir(bloque) }
        r.cerrar()
    }

    @Test
    fun `el diario va y vuelve del disco entero y una escritura a medias no lo rompe`() {
        val raiz = context.filesDir
        val d = GrabacionAMedias.Diario.nuevo(T0, "p1", "Clase 28-10 10h05", File(raiz, "voz-1.m4a").path)
        d.modo = "presencial"
        d.microfono = true
        d.movil = "g-123"
        d.marcadores += GrabacionAMedias.Marcador(5_000, "importante")
        val dir = d.carpeta(raiz)
        d.apuntarCaptura(dir, 61_000, "png!".toByteArray(), false, "png")
        d.apuntarCaptura(dir, 2_000, "jpg!".toByteArray(), true, "jpg")
        d.empezarClip(90_000, File(raiz, "clip-1.mp4").path)
        d.cerrarClip(File(raiz, "clip-1.mp4").path, 12_345)
        d.latir(T0 + 120_000, 120_000, false)
        d.guardar(dir)

        val leido = GrabacionAMedias.Diario.leer(dir)
        assertEquals(d, leido)
        assertEquals("png!", File(dir, "captura-1.png").readText())
        assertEquals("captura-2.jpg", leido!!.capturas[1].fichero)
        // Sin restos de la escritura.
        assertFalse(File(dir, "estado.json.new").exists())

        // **Un corte a mitad de escribirlo**: lo empezado no llega a ponerse en su sitio.
        val a = android.util.AtomicFile(File(dir, GrabacionAMedias.FICHERO))
        val s = a.startWrite()
        s.write("{ \"marca\": ".toByteArray())
        s.flush()
        // ... y la app muere aquí (sin finishWrite).
        assertEquals(d, GrabacionAMedias.Diario.leer(dir))

        val p = GrabacionAMedias.pendientes(raiz)
        assertEquals(1, p.size)
        assertEquals(T0.toString(), p[0].second.marca)
        // Caso negativo: un diario que no se entiende no está, y no rompe la lista.
        File(GrabacionAMedias.carpetaDeDiarios(raiz), "roto").apply { mkdirs() }.resolve(GrabacionAMedias.FICHERO).writeText("{ nada")
        assertEquals(1, GrabacionAMedias.pendientes(raiz).size)
        assertTrue(GrabacionAMedias.pendientes(File(raiz, "no-esta")).isEmpty())
    }

    @Test
    fun `una clase cortada se recupera en su chat como al terminar`() {
        val adjuntos = MensajesStore(context).carpetaDeAdjuntos()
        val nota = File(adjuntos, "clase_$T0.m4a")
        // Lo que dejó el corte: la nota en su nombre (se cortó al guardar) y su respaldo de 130 s.
        nota.writeBytes(mp4Cerrado())
        respaldo(RespaldoWav.rutaDelRespaldo(nota), 130)
        val d = GrabacionAMedias.Diario.nuevo(T0, "obra", "Clase 28-10 10h05", nota.path).apply {
            nombreProyecto = "Obra"
            modo = Presencial.Modo.CAPTURAS.palabra
            microfono = true
        }
        val dir = d.carpeta(context.filesDir)
        d.apuntarCaptura(dir, 61_000, "png-1".toByteArray(), false, "png")
        d.apuntarCaptura(dir, 5_000, "png-2".toByteArray(), false, "png")
        // Diez segundos de pausa y un hueco de 30 s (la app congelada).
        d.latir(T0 + 20_000, 20_000, false)
        d.latir(T0 + 20_000, 20_000, true)
        d.latir(T0 + 30_000, 20_000, false)
        d.latir(T0 + 90_000, 50_000, false)
        d.latir(T0 + 95_000, 55_000, false)
        d.guardar(dir)

        val cortadas = RecuperarGrabacion.pendientes(context)
        assertEquals(1, cortadas.size)
        val c = cortadas.single()
        assertEquals(GrabacionAMedias.Nota.LISTA, c.plan.nota)
        assertEquals(130_000L, c.plan.duracionMs)
        assertEquals(2L, c.plan.minutos())
        assertEquals(listOf(0, 1), c.plan.capturas)
        assertFalse(c.perdida)

        // La que se está grabando ahora no es una cortada.
        DiarioEnMarcha.enMarcha += d.marca
        assertTrue(RecuperarGrabacion.pendientes(context).isEmpty())
        DiarioEnMarcha.enMarcha -= d.marca

        val r = RecuperarGrabacion.recuperar(context, c, pasarATexto = false)
        assertNotNull(r)
        assertEquals(GuardarLaGrabacion.Que.LISTA, r!!.que)
        assertEquals(2, r.capturas)

        val mensajes = MensajesStore(context).leer()
        val voz = mensajes.single { it.clase == Clase.VOZ }
        assertEquals("obra", voz.proyecto)
        assertEquals(GrabarClase.CLASE, voz.grabacion)
        assertEquals("Clase 28-10 10h05.m4a", voz.nombre)
        assertEquals(130_000, voz.duracionMs)
        assertTrue("la onda sale del respaldo", voz.picos.isNotEmpty() && voz.picos.any { it > 0 })
        // Las capturas en su minuto, en orden, enlazadas a la nota.
        val enlaces = GrabarClase.capturasDe(voz)
        assertEquals(listOf(5_000L, 61_000L), enlaces.map { it.ms })
        for (e in enlaces) {
            val img = mensajes.single { it.id == e.id }
            assertEquals(Clase.IMAGEN, img.clase)
            assertEquals(voz.id, (img.enGrabacion!!["de"] as JsonPrimitive).content)
            assertEquals(e.ms, img.enGrabacion!!["ms"]!!.jsonPrimitive.long)
            assertTrue(File(img.ruta!!).isFile)
        }
        // **El reloj de pared en la nota**, con su pausa y su hueco, cortado en el último latido.
        val reloj = RelojDePared.de(voz.reloj)!!
        assertEquals(T0, reloj.inicio)
        assertEquals(0L, reloj.fin)
        assertEquals(T0 + 95_000, reloj.vistoEn)
        assertEquals(listOf(listOf(T0 + 20_000, T0 + 30_000)), reloj.pausas)
        assertEquals(listOf(listOf(T0 + 60_000, T0 + 90_000)), reloj.huecos)
        assertEquals(listOf("aparato", "huecos", "inicio", "pausas", "vistoEn"), voz.reloj!!.keys.toList())
        // Y la carpeta de la grabación, con la nota y las capturas dentro.
        val carpeta = mensajes.single { CarpetaDePaginas.esCarpeta(it) }
        assertEquals("Clase 28-10 10h05", carpeta.nombre)
        assertTrue(carpeta.texto.contains(voz.id))
        // Lo del corte ya no está: ni el diario ni el respaldo. La nota sí (es la del chat).
        assertFalse(dir.exists())
        assertFalse(RespaldoWav.rutaDelRespaldo(nota).exists())
        assertTrue(File(voz.ruta!!).isFile)
        assertTrue(RecuperarGrabacion.pendientes(context).isEmpty())
        // La nota vuelve a leerse del chat con su reloj (sobrevive a reescribir el mensaje).
        MensajesStore(context).actualizar(voz.id) { it.copy(fijado = true) }
        assertEquals(voz.reloj, MensajesStore(context).leer().single { it.id == voz.id }.reloj)
        assertTrue((voz.capturas as JsonArray).size == 2)
        assertTrue(voz.reloj!!["pausas"] is JsonArray)
        assertEquals(T0, voz.reloj!!.jsonObject["inicio"]!!.jsonPrimitive.long)
    }

    @Test
    fun `descartar borra lo grabado y una cortada sin nada se va sola`() {
        val adjuntos = MensajesStore(context).carpetaDeAdjuntos()
        val nota = File(adjuntos, "clase_${T0 + 1}.m4a")
        val d = GrabacionAMedias.Diario.nuevo(T0 + 1, "", "Clase", nota.path)
        val dir = d.carpeta(context.filesDir)
        d.guardar(dir)
        // Se cortó al abrir el micrófono: nada que salvar, se va sola (como el PC).
        assertTrue(RecuperarGrabacion.pendientes(context).isEmpty())
        assertFalse(dir.exists())

        // Otra con su respaldo: Descartar (elegido por el usuario) se lo lleva todo.
        val d2 = GrabacionAMedias.Diario.nuevo(T0 + 2, "", "Clase", nota.path)
        respaldo(RespaldoWav.rutaDelRespaldo(nota), 3)
        GuardarLaGrabacion.parteDe(nota).writeBytes(byteArrayOf(1, 2, 3))
        val dir2 = d2.carpeta(context.filesDir)
        d2.guardar(dir2)
        val c = RecuperarGrabacion.pendientes(context).single()
        assertEquals(GrabacionAMedias.Nota.DEL_RESPALDO, c.plan.nota)
        RecuperarGrabacion.descartar(c)
        assertFalse(dir2.exists())
        assertFalse(RespaldoWav.rutaDelRespaldo(nota).exists())
        assertFalse(GuardarLaGrabacion.parteDe(nota).exists())
        assertTrue(MensajesStore(context).leer().isEmpty())
    }
}
