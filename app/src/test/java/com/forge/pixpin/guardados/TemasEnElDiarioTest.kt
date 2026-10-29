package com.forge.pixpin.guardados

import com.forge.pixpin.audio.RespaldoWav
import com.forge.pixpin.mini.Tareas
import com.forge.pixpin.mini.TodasLasTareas
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

/**
 * **Los temas de la reunión en el diario de «Grabar sin perder nada»** (29-oct-2026, `Diario::temas` y
 * `temas_lista` de `grabacion_a_medias.rs` del PC, `a_salvo.rs` y `recuperar.rs`): el diario va y vuelve
 * con el JSON del PC (los mismos nombres, en el mismo sitio, y sin temas no van), y **una reunión
 * recuperada tras un corte vuelve con sus temas**, lo tratado con su minuto y su lista de tareas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemasEnElDiarioTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val T0 = 1_761_730_000_000L

    /** Un diario como lo escribe el PC (`serde_json::to_string_pretty`, los campos en su orden) con temas. */
    private val DIARIO_DEL_PC = """{
  "version": 1,
  "marca": "1761730000000",
  "proyecto": "obra",
  "nombre_proyecto": "Obra",
  "nombre": "Reunión 29-10 10h05",
  "modo": "presencial",
  "microfono": true,
  "equipo": false,
  "audio": "C:\\datos\\voz\\reunion.m4a",
  "video": null,
  "reloj": { "inicio": 1761730000000, "fin": null, "pausas": [], "huecos": [] },
  "latido": { "utc": 1761730120000, "ms": 120000, "pausa": false },
  "capturas": [],
  "clips": [],
  "marcadores": [],
  "movil": null,
  "solo_la_nota": false,
  "temas": [
    { "aMano": false, "hecho": true, "id": "t1", "ms": 45000, "p": 0.86, "porJev": true, "texto": "Presupuesto 2027" },
    { "aMano": true, "hecho": true, "id": "t2", "ms": 90000, "porJev": false, "texto": "Nuevo becario" },
    { "aMano": false, "hecho": false, "id": "t3", "porJev": false, "texto": "Viaje a Lima" }
  ],
  "temas_lista": ["obra", "lista-7"]
}"""

    @Test
    fun `el diario va y vuelve con los temas del pc y sin temas no los lleva`() {
        val d = GrabacionAMedias.Diario.deJson(DIARIO_DEL_PC)
        val temas = TemasDeLaReunion.temasDe(d.temas as JsonArray)
        assertEquals(listOf("t1", "t2", "t3"), temas.map { it.id })
        assertEquals(45_000L, temas[0].ms)
        assertTrue(temas[0].porJev && temas[1].aMano && !temas[2].hecho)
        assertEquals(listOf("obra", "lista-7"), d.temasLista)
        // De vuelta: los mismos nombres, detrás de `solo_la_nota` (lo del teléfono va después), y lo mismo dentro.
        val j = d.aJson()
        assertTrue(j, j.indexOf("\"solo_la_nota\"") < j.indexOf("\"temas\"") && j.indexOf("\"temas\"") < j.indexOf("\"temas_lista\""))
        assertTrue(j, j.indexOf("\"temas_lista\"") < j.indexOf("\"trozos\""))
        assertEquals(d, GrabacionAMedias.Diario.deJson(j))
        // Sin temas, ni `temas` ni `temas_lista` (el diario de antes, byte a byte).
        val sin = GrabacionAMedias.Diario.nuevo(T0, "", "Clase", "/x.m4a").aJson()
        assertFalse(sin, sin.contains("\"temas\""))
        assertFalse(sin, sin.contains("temas_lista"))
        // Un diario de antes (sin la clave) se lee sin temas.
        assertNull(GrabacionAMedias.Diario.deJson(sin).temas)
        assertNull(ReunionConJev.cierreDelDiario(GrabacionAMedias.Diario.deJson(sin)))
        // Lo que se recupera: los temas y la lista, de aquí.
        val c = ReunionConJev.cierreDelDiario(d)!!
        assertEquals(temas, c.temas)
        assertEquals("obra" to "lista-7", c.lista)
        assertEquals("Reunión 29-10 10h05", c.nombre)
    }

    private fun caja(tipo: String, cuerpo: Int): ByteArray {
        val b = ByteBuffer.allocate(cuerpo + 8)
        b.putInt(cuerpo + 8)
        b.put(tipo.toByteArray(Charsets.ISO_8859_1))
        return b.array()
    }

    @Test
    fun `una reunion recuperada tras un corte vuelve con sus temas y su lista`() {
        val adjuntos = MensajesStore(context).carpetaDeAdjuntos()
        val nota = File(adjuntos, "reunion_$T0.m4a")
        nota.writeBytes(caja("ftyp", 8) + caja("mdat", 64) + caja("moov", 16))
        RespaldoWav.Respaldo(RespaldoWav.rutaDelRespaldo(nota), 8_000, 1).apply {
            val bloque = ShortArray(8_000) { ((it % 80) * 200 - 8_000).toShort() }
            repeat(130) { escribir(bloque) }
            cerrar()
        }
        val d = GrabacionAMedias.Diario.nuevo(T0, "", "Reunión 29-10 10h05", nota.path).apply {
            modo = Presencial.Modo.CAPTURAS.palabra
            microfono = true
        }
        // Lo que apuntó la reunión mientras grababa: un tema marcado por Jev, otro a mano, uno pendiente.
        val v = mutableListOf(TemasDeLaReunion.Tema("t1", "Presupuesto 2027"), TemasDeLaReunion.Tema("t2", "Nuevo becario"), TemasDeLaReunion.Tema("t3", "Viaje a Lima"))
        TemasDeLaReunion.porJev(v, "t1", 45_000, 0.86)
        TemasDeLaReunion.aMano(v, "t2", true, 90_000)
        d.temas = TemasDeLaReunion.aJson(v)
        d.latir(T0 + 125_000, 125_000, false)
        val dir = d.carpeta(context.filesDir)
        d.guardar(dir)

        val c = RecuperarGrabacion.pendientes(context).single()
        val r = RecuperarGrabacion.recuperar(context, c, pasarATexto = false)
        assertNotNull(r)
        assertEquals(GuardarLaGrabacion.Que.LISTA, r!!.que)

        val mensajes = MensajesStore(context).leer()
        val voz = mensajes.single { it.clase == Clase.VOZ }
        val temas = TemasDeLaReunion.temasDe(voz)
        assertEquals(v, temas)
        assertEquals(45_000L, temas[0].ms)
        // Y su lista de tareas, «Temas · <nombre>», con lo tratado marcado, enlazada en la nota.
        val (proyecto, id) = TemasDeLaReunion.listaDe(voz.temasLista)!!
        assertEquals("general", proyecto)
        val lista = mensajes.single { it.id == id }
        assertTrue(TodasLasTareas.esLista(lista))
        assertEquals("Temas · Reunión 29-10 10h05", TodasLasTareas.nombreDeLista(lista))
        assertEquals(listOf(true, true, false), Tareas.leer(lista.texto).map { it.hecha })
        assertFalse(dir.exists())
    }
}
