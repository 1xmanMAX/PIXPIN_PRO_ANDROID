package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * **Grabar clase** (25-oct-2026): las mismas pruebas que `grabar_clase.rs` del PC, y el JSON
 * **byte a byte** como lo escribe el PC (`serde_json` sin `preserve_order`: claves en orden).
 */
class GrabarClaseTest {

    /** El mismo `Json` que [MensajesStore]. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun nota(vararg c: GrabarClase.Enlace, video: String? = null) = Mensaje(
        id = "voz", cuando = 1L, clase = Clase.VOZ,
        capturas = GrabarClase.capturasEnJson(c.toList()),
        grabacion = GrabarClase.CLASE,
        video = video?.let { GrabarClase.videoEnJson(it) }
    )

    @Test
    fun `la nota lleva capturas, grabacion y video como el PC`() {
        val m = nota(GrabarClase.Enlace(9_000, "b"), GrabarClase.Enlace(2_000, "a"), video = "v")
        val linea = json.encodeToString(Mensaje.serializer(), m)
        // En orden de minuto y con las claves en orden alfabético, como `capturas_en_json`.
        assertTrue(linea, linea.contains(""""capturas":[{"id":"a","ms":2000},{"id":"b","ms":9000}]"""))
        assertTrue(linea, linea.contains(""""grabacion":"clase""""))
        assertTrue(linea, linea.contains(""""video":{"id":"v"}"""))
        // Caso negativo: una nota cualquiera no escribe ninguno de los tres.
        val normal = json.encodeToString(Mensaje.serializer(), Mensaje(id = "x", cuando = 1L, clase = Clase.VOZ))
        assertFalse(normal, normal.contains("capturas") || normal.contains("grabacion") || normal.contains("\"video\""))
    }

    @Test
    fun `sin capturas la lista va igual, vacia`() {
        val linea = json.encodeToString(Mensaje.serializer(), nota())
        assertTrue(linea, linea.contains(""""capturas":[]"""))
        assertTrue(GrabarClase.esClase(nota()))
        assertFalse(GrabarClase.esClase(Mensaje(id = "x", cuando = 1L, clase = Clase.VOZ)))
    }

    @Test
    fun `cada imagen dice de que nota es y en que minuto`() {
        val img = Mensaje(id = "a", cuando = 2L, clase = Clase.IMAGEN, enGrabacion = GrabarClase.enGrabacion("voz", 2_000))
        val linea = json.encodeToString(Mensaje.serializer(), img)
        assertTrue(linea, linea.contains(""""enGrabacion":{"de":"voz","ms":2000}"""))
        assertEquals(GrabarClase.Enlace(2_000, "voz"), GrabarClase.deLaGrabacion(img))
        // El vídeo, con `ms: 0`.
        val video = json.encodeToString(Mensaje.serializer(), img.copy(enGrabacion = GrabarClase.enGrabacion("voz", 0)))
        assertTrue(video, video.contains(""""enGrabacion":{"de":"voz","ms":0}"""))
        assertNull(GrabarClase.deLaGrabacion(Mensaje(id = "b", cuando = 1L, clase = Clase.IMAGEN)))
    }

    @Test
    fun `lo que escribe el PC se lee y vuelve a salir igual`() {
        val delPc = """{"id":"voz","cuando":5,"clase":"VOZ","capturas":[{"id":"a","ms":2000},{"id":"b","ms":9000}],""" +
            """"grabacion":"clase","video":{"id":"v"},"otraCosa":1}"""
        val m = json.decodeFromString(Mensaje.serializer(), delPc)
        assertEquals(listOf(GrabarClase.Enlace(2_000, "a"), GrabarClase.Enlace(9_000, "b")), GrabarClase.capturasDe(m))
        assertEquals("v", GrabarClase.videoDe(m))
        val otra = json.encodeToString(Mensaje.serializer(), m)
        assertTrue(otra, otra.contains(""""capturas":[{"id":"a","ms":2000},{"id":"b","ms":9000}]"""))
        assertTrue(otra, otra.contains(""""video":{"id":"v"}"""))
    }

    @Test
    fun `las capturas van y vuelven en orden y una rota no se lleva las demas`() {
        val m = nota(GrabarClase.Enlace(9_000, "b"), GrabarClase.Enlace(2_000, "a"))
        assertEquals(listOf(GrabarClase.Enlace(2_000, "a"), GrabarClase.Enlace(9_000, "b")), GrabarClase.capturasDe(m))
        assertTrue(GrabarClase.capturasDe(Mensaje(id = "x", cuando = 1L, clase = Clase.VOZ)).isEmpty())
        // Como la prueba del PC: `{ms: "x"}` se salta, `{ms: 5, id: "c"}` queda.
        val rota = m.copy(capturas = buildJsonArray {
            add(buildJsonObject { put("ms", JsonPrimitive("x")) })
            add(buildJsonObject { put("ms", JsonPrimitive(5)); put("id", JsonPrimitive("c")) })
            add(JsonPrimitive(3))
        })
        assertEquals(listOf(GrabarClase.Enlace(5, "c")), GrabarClase.capturasDe(rota))
        assertNull(GrabarClase.videoDe(m))
    }

    @Test
    fun `la captura del momento es la ultima hecha antes`() {
        val t = listOf(2_000L, 9_000L)
        assertEquals("antes de la primera, la primera", 0, GrabarClase.capturaEn(t, 500))
        assertEquals(0, GrabarClase.capturaEn(t, 2_000))
        assertEquals(0, GrabarClase.capturaEn(t, 8_999))
        assertEquals(1, GrabarClase.capturaEn(t, 9_000))
        assertEquals(1, GrabarClase.capturaEn(t, 99_000))
        assertNull(GrabarClase.capturaEn(emptyList(), 1_000))
    }

    @Test
    fun `el minuto de una captura se lee como un reloj`() {
        assertEquals("0:00", GrabarClase.minuto(0))
        assertEquals("1:05", GrabarClase.minuto(65_400))
        assertEquals("1:02:03", GrabarClase.minuto(3_723_000))
        assertEquals("0:00", GrabarClase.minuto(-5))
        assertEquals("⏱ 1:05 · Clase 24-10 18h05", GrabarClase.pie(65_400, "Clase 24-10 18h05"))
    }

    @Test
    fun `la fecha del nombre no lleva dos puntos`() {
        // 24-oct-2026 18:05 UTC.
        val ms = 20_750L * 86_400_000 + (18 * 60 + 5) * 60_000L
        val f = GrabarClase.fechaDelNombre(ms, TimeZone.getTimeZone("UTC"))
        assertEquals("24-10 18h05", f)
        assertFalse(f.contains(':'))
        assertEquals("Clase 1.m4a", GrabarClase.nombreDeLaNota("Clase 1"))
        assertEquals("Clase 1.m4a", GrabarClase.nombreDeLaNota("Clase 1.m4a"))
        assertEquals("Clase 1.mp4", GrabarClase.nombreDelVideo("Clase 1"))
    }

    @Test
    fun `el cronometro no cuenta las pausas`() {
        var ahora = 1_000L
        val c = GrabarClase.Cronometro { ahora }
        ahora += 5_000
        assertEquals(5_000, c.llevadoMs())
        c.pausar(true)
        ahora += 60_000
        assertEquals("en pausa no corre", 5_000, c.llevadoMs())
        c.pausar(true) // otra vez: no cambia nada
        c.pausar(false)
        ahora += 2_000
        assertEquals(7_000, c.llevadoMs())
        assertFalse(c.enPausa)
    }

    @Test
    fun `la zona se mete dentro del fotograma`() {
        val z = GrabarClase.Zona(100, 200, 300, 400)
        assertEquals(z, GrabarClase.recorte(z, 1080, 2400))
        assertEquals(GrabarClase.Zona(0, 0, 1080, 2400), GrabarClase.recorte(null, 1080, 2400))
        // Se sale por la derecha (el teléfono giró): se recorta a lo que cabe.
        assertEquals(GrabarClase.Zona(900, 200, 180, 400), GrabarClase.recorte(GrabarClase.Zona(900, 200, 600, 400), 1080, 2400))
        // Fuera del todo, o un toque: no vale.
        assertNull(GrabarClase.recorte(GrabarClase.Zona(2000, 200, 300, 400), 1080, 2400))
        assertNull(GrabarClase.recorte(GrabarClase.Zona(10, 10, 5, 5), 1080, 2400))
        assertNull(GrabarClase.recorte(z, 0, 0))
    }

    @Test
    fun `lo arrastrado pasa a pixeles del fotograma`() {
        // Una vista a la mitad del fotograma: todo se dobla, y da igual hacia dónde se arrastre.
        val z = GrabarClase.zonaArrastrada(300f, 400f, 50f, 100f, 540f, 1200f, 1080, 2400)
        assertEquals(GrabarClase.Zona(100, 200, 500, 600), z)
        assertNull(GrabarClase.zonaArrastrada(10f, 10f, 12f, 11f, 540f, 1200f, 1080, 2400))
        assertNull(GrabarClase.zonaArrastrada(0f, 0f, 100f, 100f, 0f, 0f, 1080, 2400))
    }

    @Test
    fun `el video se reduce a 1280 con lados multiplos de 16`() {
        assertEquals(576 to 1280, GrabarClase.medidasDelVideo(1080, 2400))
        assertEquals(1280 to 576, GrabarClase.medidasDelVideo(2400, 1080))
        // Una pantalla pequeña no se agranda, solo se ajusta a 16.
        assertEquals(800 to 480, GrabarClase.medidasDelVideo(800, 480))
        assertEquals(16 to 16, GrabarClase.medidasDelVideo(3, 3))
    }
}
