package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La reunión presencial** (28-oct-2026): las pruebas de `crates/pixpin-proyecto/src/presencial.rs`
 * del PC, una a una, y el formato que se guarda, **byte a byte** el del PC (`serde_json` sin
 * `preserve_order`: las claves de cada objeto en orden alfabético).
 */
class PresencialTest {

    /** El mismo `Json` que [MensajesStore]. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun linea(m: Mensaje) = json.encodeToString(Mensaje.serializer(), m)
    private fun leer(l: String) = json.decodeFromString(Mensaje.serializer(), l)

    // --- Las del PC ---------------------------------------------------------------------------

    @Test
    fun `el modo se recuerda por su palabra y da la vuelta`() {
        for (m in Presencial.Modo.TODOS) {
            assertEquals(m, Presencial.Modo.de(m.palabra))
            assertEquals(m, m.siguiente().anterior())
            assertEquals("rueda de tres", m, m.siguiente().siguiente().siguiente())
            assertNotEquals(m, m.siguiente())
        }
        assertEquals(Presencial.Modo.PRESENCIAL, Presencial.Modo.de("presencial\n"))
        // Caso negativo: lo que no se entiende es el modo que menos ocupa.
        assertEquals(Presencial.Modo.CAPTURAS, Presencial.Modo.de(""))
        assertEquals(Presencial.Modo.CAPTURAS, Presencial.Modo.de("camara"))
        assertEquals(Presencial.Modo.CAPTURAS, Presencial.Modo.de(null))
    }

    @Test
    fun `en persona solo suena el microfono`() {
        assertEquals(true to false, Presencial.Modo.PRESENCIAL.sonidos())
        assertEquals(true to true, Presencial.Modo.CAPTURAS.sonidos())
        assertEquals(true to true, Presencial.Modo.PANTALLA.sonidos())
        assertTrue(Presencial.Modo.PRESENCIAL.conCamara() && !Presencial.Modo.PRESENCIAL.conVideo())
        assertTrue(Presencial.Modo.PANTALLA.conVideo() && !Presencial.Modo.PANTALLA.conCamara())
        // Caso negativo: el de capturas no lleva ni vídeo ni cámara.
        assertTrue(!Presencial.Modo.CAPTURAS.conVideo() && !Presencial.Modo.CAPTURAS.conCamara())
    }

    private fun cam(id: String, lado: Presencial.Lado) = id to lado

    @Test
    fun `se empieza con la recordada o con la que mira a la sala`() {
        val c = listOf(
            cam("delante", Presencial.Lado.DELANTE),
            cam("usb", Presencial.Lado.FUERA),
            cam("atras", Presencial.Lado.DETRAS)
        )
        assertEquals("la de atrás", 2, Presencial.camaraElegida(c, null))
        assertEquals("la recordada", 0, Presencial.camaraElegida(c, "delante"))
        assertEquals("sin atrás, la USB", 1, Presencial.camaraElegida(c.take(2), null))
        assertEquals(0, Presencial.camaraElegida(c.take(1), null))
        // Entre iguales, la primera del sistema.
        val dos = listOf(cam("a", Presencial.Lado.FUERA), cam("b", Presencial.Lado.FUERA))
        assertEquals(0, Presencial.camaraElegida(dos, null))
        // Caso negativo: la recordada ya no está y no hay cámaras.
        assertEquals(2, Presencial.camaraElegida(c, "vieja"))
        assertEquals(2, Presencial.camaraElegida(c, "  "))
        assertNull(Presencial.camaraElegida(emptyList(), "delante"))
    }

    private fun modo(ancho: Int, alto: Int, formato: String) = Presencial.ModoDeCamara(ancho, alto, formato, 0)
    private fun video(ancho: Int, alto: Int, formato: String, fps: Int) = Presencial.ModoDeCamara(ancho, alto, formato, fps)

    @Test
    fun `el clip va a 1080p y 30 fps como mucho`() {
        val modos = listOf(
            video(3840, 2160, "NV12", 30),
            video(1920, 1080, "MJPG", 30),
            video(1920, 1080, "NV12", 30),
            video(1920, 1080, "NV12", 60),
            video(1280, 720, "NV12", 30)
        )
        assertEquals("1080p30 sin comprimir", 2, Presencial.modoParaClip(modos))
        assertEquals("más fotogramas", 1, Presencial.modoParaClip(listOf(video(1280, 720, "NV12", 15), video(1280, 720, "YUY2", 30))))
        // De pie (una tableta) también vale.
        assertEquals(0, Presencial.modoParaClip(listOf(video(1080, 1920, "NV12", 30))))
        // Caso negativo: solo 4K o solo 60 fps, ninguno.
        assertNull(Presencial.modoParaClip(listOf(video(3840, 2160, "NV12", 30))))
        assertNull(Presencial.modoParaClip(listOf(video(1280, 720, "NV12", 60))))
    }

    @Test
    fun `el clip pesa poco y menos con hevc`() {
        assertEquals(5_000_000, Presencial.bitsDelClip(1920, 1080, false))
        assertEquals(3_500_000, Presencial.bitsDelClip(1920, 1080, true))
        val hd = Presencial.bitsDelClip(1280, 720, false)
        assertTrue("$hd", hd in 2_000_000..2_600_000)
        // Caso negativo: ni una cámara enana baja de 1 Mbps ni una enorme pasa de 6.
        assertEquals(1_000_000, Presencial.bitsDelClip(160, 120, false))
        assertEquals(1_000_000, Presencial.bitsDelClip(0, 0, true))
        assertEquals(6_000_000, Presencial.bitsDelClip(3840, 2160, false))
    }

    @Test
    fun `los clips van y vuelven en orden`() {
        val clips = listOf(Presencial.Clip(90_000, 12_000, "b"), Presencial.Clip(5_000, 3_000, "a"))
        val lista = Presencial.clipsEnJson(clips)
        val v = Presencial.clipsDe(lista)
        assertEquals(listOf("a", "b"), v.map { it.id })
        assertEquals(12_000L, v[1].duracionMs)
        assertEquals(
            """{"de":"voz","duracionMs":3000,"ms":5000}""",
            Presencial.enlaceDelClip("voz", 5_000, 3_000).toString()
        )
        // Caso negativo: sin clips la clave se quita, y una entrada rota se salta sin llevarse las demás.
        assertNull(Presencial.clipsEnJson(emptyList()))
        val rota = buildJsonArray {
            add(buildJsonObject { put("ms", JsonPrimitive("x")) })
            add(buildJsonObject { put("ms", JsonPrimitive(7)); put("id", JsonPrimitive("c")) })
        }
        assertEquals(listOf(Presencial.Clip(7, 0, "c")), Presencial.clipsDe(rota))
    }

    @Test
    fun `los clips van en orden sin repetirse y se sabe cual suena`() {
        // `clase::poner_clip` y `clip_en` del PC (da5f7739).
        var r = Presencial.ponerClip(null, 50_000, 20_000, "b")
        r = Presencial.ponerClip(r, 10_000, 5_000, "a")
        assertNull("el mismo id no entra dos veces", Presencial.ponerClip(r, 99_000, 1, "b"))
        val c = Presencial.clipsDe(r)
        assertEquals(listOf(10_000L to "a", 50_000L to "b"), c.map { it.ms to it.id })
        assertEquals(0, Presencial.clipEn(c, 12_000))
        assertEquals(1, Presencial.clipEn(c, 69_999))
        // Casos negativos: entre los dos, y justo al acabar.
        assertNull(Presencial.clipEn(c, 30_000))
        assertNull(Presencial.clipEn(c, 70_000))
        assertTrue(r.toString().contains("\"duracionMs\":5000"))
    }

    @Test
    fun `la foto va a la mayor resolucion de la camara`() {
        assertEquals(2, Presencial.mejorModo(listOf(modo(640, 480, "YUY2"), modo(1920, 1080, "NV12"), modo(3840, 2160, "NV12"), modo(1280, 720, "MJPG"))))
        // Más píxeles gana aunque sea menos ancha (4:3 frente a 16:9).
        assertEquals(1, Presencial.mejorModo(listOf(modo(1920, 1080, "NV12"), modo(1920, 1440, "YUY2"))))
        // Entre dos iguales, la que ya llega en JPEG; si no, la primera.
        assertEquals(1, Presencial.mejorModo(listOf(modo(1920, 1080, "NV12"), modo(1920, 1080, "mjpg"))))
        assertEquals(0, Presencial.mejorModo(listOf(modo(1920, 1080, "NV12"), modo(1920, 1080, "YUY2"))))
        // Caso negativo: sin modos, o solo modos sin tamaño, ninguno.
        assertNull(Presencial.mejorModo(emptyList()))
        assertNull(Presencial.mejorModo(listOf(modo(0, 1080, "NV12"))))
    }

    @Test
    fun `el minuto de la foto es el de cuando se pulso`() {
        assertEquals(65_400L, Presencial.msDeLaFoto(65_400, null))
        assertEquals(65_400L, Presencial.msDeLaFoto(65_400, 90_000))
        // Caso negativo: no antes del principio ni después del final.
        assertEquals(0L, Presencial.msDeLaFoto(-30, null))
        assertEquals(90_000L, Presencial.msDeLaFoto(95_000, 90_000))
        assertEquals(0L, Presencial.msDeLaFoto(10, -1))
    }

    @Test
    fun `cada foto tiene su nombre`() {
        assertEquals("foto-1761500000000-3.jpg", Presencial.nombreDeLaFoto(true, 1_761_500_000_000, 3, "jpg"))
        assertEquals("clase-1761500000000-1.png", Presencial.nombreDeLaFoto(false, 1_761_500_000_000, 1, ".PNG"))
        assertNotEquals("dos en el mismo milisegundo no se pisan", Presencial.nombreDeLaFoto(true, 7, 1, "jpg"), Presencial.nombreDeLaFoto(true, 7, 2, "jpg"))
        // Caso negativo: sin extensión, PNG.
        assertEquals("clase-7-1.png", Presencial.nombreDeLaFoto(false, 7, 1, " "))
    }

    @Test
    fun `la foto de la camara lo dice en su resto`() {
        val m = Mensaje(id = "f", cuando = 1, clase = Clase.IMAGEN)
        assertFalse(Presencial.esDeCamara(m))
        val deCamara = m.copy(fuente = Presencial.DE_CAMARA)
        assertTrue(Presencial.esDeCamara(deCamara))
        assertTrue(linea(deCamara), linea(deCamara).contains(""""fuente":"camara""""))
        assertFalse(Presencial.esDelMovil(deCamara))
        val delMovil = m.copy(fuente = Presencial.DEL_MOVIL)
        assertTrue(Presencial.esDelMovil(delMovil) && !Presencial.esDeCamara(delMovil))
        // Caso negativo: otra fuente no es la cámara, y el campo `origen` del mensaje no se toca.
        assertFalse(Presencial.esDeCamara(m.copy(fuente = "pantalla")))
        assertNull(deCamara.origen)
        assertFalse(linea(deCamara).contains(""""origen":"camara""""))
        // Sin fuente (la pantalla, todo lo de antes) no se escribe la clave.
        assertFalse(linea(m).contains("fuente"))
    }

    @Test
    fun `la imagen entera solo se carga al acercar`() {
        // Una foto de 4000 cargada a 2560: encajada en 1200 no hace falta.
        assertFalse(Presencial.haceFaltaLaEntera(1200f, 2560, 4000))
        // Acercada a 3000 de ancho, sí.
        assertTrue(Presencial.haceFaltaLaEntera(3000f, 2560, 4000))
        // Caso negativo: ya está entera (o casi igual de ancha al pintarla).
        assertFalse(Presencial.haceFaltaLaEntera(9000f, 4000, 4000))
        assertFalse("el margen", Presencial.haceFaltaLaEntera(2600f, 2560, 4000))
        // La entera, hasta lo que pinta la GPU: 12 Mpx tal cual, 50 Mpx a la mitad.
        assertEquals(1, Presencial.pasoParaLaEntera(4000, 3000))
        assertEquals(2, Presencial.pasoParaLaEntera(8160, 6120))
    }

    // --- Lo del teléfono ----------------------------------------------------------------------

    @Test
    fun `la forma y la calidad se recuerdan y la automatica baja a 720 pasada la hora`() {
        assertEquals(Presencial.Forma.VIDEO, Presencial.Forma.de("video"))
        assertEquals(Presencial.Forma.AUDIO, Presencial.Forma.de("raro"))
        assertEquals(Presencial.Calidad.LIGERA, Presencial.Calidad.de("720"))
        assertEquals(Presencial.Calidad.AUTOMATICA, Presencial.Calidad.de(null))
        assertEquals(1080, Presencial.altoDelVideo(Presencial.Calidad.AUTOMATICA, 3_599_999))
        assertEquals(720, Presencial.altoDelVideo(Presencial.Calidad.AUTOMATICA, 3_600_000))
        assertEquals(1080, Presencial.altoDelVideo(Presencial.Calidad.ALTA, 9_000_000))
        assertTrue(Presencial.tocaBajar(Presencial.Calidad.AUTOMATICA, 3_600_000, 1080))
        // Caso negativo: ya a 720, o la elegida a mano, no se cambia.
        assertFalse(Presencial.tocaBajar(Presencial.Calidad.AUTOMATICA, 3_600_000, 720))
        assertFalse(Presencial.tocaBajar(Presencial.Calidad.ALTA, 9_000_000, 1080))
        assertEquals(5_000_000, Presencial.bitsDeLaReunion(1080, false))
        assertEquals(2_500_000, Presencial.bitsDeLaReunion(720, false))
        assertTrue(Presencial.bitsDeLaReunion(1080, true) < 5_000_000)
    }

    @Test
    fun `el clip sale de su tramo del video`() {
        val tramos = listOf(Presencial.Tramo(0, 3_600_000), Presencial.Tramo(3_600_000, 1_200_000))
        assertEquals(Triple(0, 65_000L, 10_000L), Presencial.tramoDelClip(tramos, 65_000, 10_000))
        assertEquals(Triple(1, 1_000L, 5_000L), Presencial.tramoDelClip(tramos, 3_601_000, 5_000))
        // Un clip que pasa el final del tramo se corta ahí.
        assertEquals(Triple(0, 3_590_000L, 10_000L), Presencial.tramoDelClip(tramos, 3_590_000, 60_000))
        // Caso negativo: fuera de lo grabado.
        assertNull(Presencial.tramoDelClip(tramos, 9_000_000, 1_000))
        assertNull(Presencial.tramoDelClip(emptyList(), 0, 1_000))
    }

    @Test
    fun `la foto hecha en pausa cae en el minuto en que se paro`() {
        var ahora = 0L
        val c = GrabarClase.Cronometro { ahora }
        ahora = 10_000
        c.pausar(true)
        ahora = 50_000 // cuarenta segundos en pausa: no entra audio
        assertEquals("en pausa, el minuto es el de la pausa", 10_000L, Presencial.msDeLaFoto(c.llevadoMs()))
        ahora = 60_000
        c.pausar(false)
        ahora = 65_000
        assertEquals("al seguir, sin el hueco de la pausa", 15_000L, Presencial.msDeLaFoto(c.llevadoMs()))
        // Y nunca pasa de lo grabado.
        assertEquals(12_000L, Presencial.msDeLaFoto(c.llevadoMs(), 12_000))
    }

    // --- Lo que se guarda: el formato del PC --------------------------------------------------

    @Test
    fun `una reunion con dos fotos y un clip guarda exactamente lo del PC`() {
        val fotos = listOf(
            Presencial.FotoGuardada("f2", 65_000, "/a/f2.jpg", "foto-7-2.jpg", 20),
            Presencial.FotoGuardada("f1", 5_000, "/a/f1.jpg", "foto-7-1.jpg", 10)
        )
        val clips = listOf(Presencial.ClipGuardado("c1", 30_000, 12_000, "/a/c1.mp4", "Reunión 28-10 10h05 · clip 1.mp4", 99))
        val ms = Presencial.mensajes(
            "voz", "Reunión 28-10 10h05", 1_000, "obra", "/a/voz.m4a", 500, 90_000, listOf(1, 2),
            null, fotos, clips
        )
        assertEquals(listOf("voz", "f1", "f2", "c1"), ms.map { it.id })
        assertEquals("números seguidos", listOf(1_000L, 1_001L, 1_002L, 1_003L), ms.map { it.cuando })
        val voz = linea(ms[0])
        assertTrue(voz, voz.contains(""""capturas":[{"id":"f1","ms":5000},{"id":"f2","ms":65000}]"""))
        assertTrue(voz, voz.contains(""""grabacion":"clase""""))
        assertTrue(voz, voz.contains(""""clips":[{"duracionMs":12000,"id":"c1","ms":30000}]"""))
        assertFalse("sin vídeo no hay `video`", voz.contains("\"video\""))
        assertFalse("la nota no es de ninguna fuente", voz.contains("fuente"))
        assertEquals("Reunión 28-10 10h05.m4a", ms[0].nombre)
        val f1 = linea(ms[1])
        assertTrue(f1, f1.contains(""""enGrabacion":{"de":"voz","ms":5000}"""))
        assertTrue(f1, f1.contains(""""fuente":"camara""""))
        assertEquals("⏱ 0:05 · Reunión 28-10 10h05", ms[1].texto)
        assertEquals(Clase.IMAGEN, ms[1].clase)
        val c1 = linea(ms[3])
        assertEquals(Clase.ARCHIVO, ms[3].clase)
        assertTrue(c1, c1.contains(""""enGrabacion":{"de":"voz","duracionMs":12000,"ms":30000}"""))
        assertTrue(c1, c1.contains(""""fuente":"camara""""))
        // Nunca `origen` con la fuente: es el campo del mensaje (y va vacío).
        for (l in ms.map(::linea)) assertFalse(l, l.contains(""""origen":"camara""""))
        // La carpeta: la nota sin título, las fotos con «⏱» y el clip con «▶», en el orden del minuto.
        val carpeta = Presencial.carpeta("voz", 90_000, null, fotos, clips)
        assertEquals(listOf("voz", "f1", "c1", "f2"), carpeta.paginas.map { it.id })
        assertEquals(listOf("", "⏱ 0:05", "▶ 0:30", "⏱ 1:05"), carpeta.paginas.map { it.titulo })
        assertEquals(listOf(0L, 5_000L, 30_000L, 65_000L), carpeta.paginas.map { it.ms })
        assertTrue(carpeta.esGrabacion)
    }

    @Test
    fun `con video la nota lo enlaza y sin clips la clave no esta`() {
        val v = Presencial.VideoGuardado("vid", "/a/v.mp4", "Reunión.mp4", 1_000, 90_000)
        val ms = Presencial.mensajes("voz", "Reunión", 1, null, "/a/voz.m4a", 1, 90_000, emptyList(), v, emptyList(), emptyList())
        assertEquals(listOf("voz", "vid"), ms.map { it.id })
        val voz = linea(ms[0])
        assertTrue(voz, voz.contains(""""video":{"id":"vid"}"""))
        assertTrue("capturas siempre, aunque vacía", voz.contains(""""capturas":[]"""))
        assertFalse("sin clips la clave se quita, no `[]`", voz.contains("clips"))
        assertTrue(linea(ms[1]), linea(ms[1]).contains(""""enGrabacion":{"de":"voz","ms":0}"""))
        assertFalse("el vídeo de la reunión es como el del §3, sin fuente", linea(ms[1]).contains("fuente"))
    }

    /**
     * **La grabación única** (29-oct-2026): en la misma reunión hay fotos de la cámara (`fuente: "camara"`)
     * y capturas de la pantalla y trozos de su vídeo (sin `fuente`, como las de la clase del PC).
     */
    @Test
    fun `en la grabacion unica las capturas y la pantalla van sin fuente y las fotos con la suya`() {
        val fotos = listOf(
            Presencial.FotoGuardada("f1", 5_000, "/a/f1.jpg", Presencial.nombreDeLaFoto(true, 7, 1, "jpg"), 10),
            Presencial.FotoGuardada("k1", 9_000, "/a/k1.png", Presencial.nombreDeLaFoto(false, 7, 2, "png"), 10, deCamara = false)
        )
        val clips = listOf(
            Presencial.ClipGuardado("c1", 30_000, 12_000, "/a/c1.mp4", Presencial.nombreDelClip("Clase", 1), 99),
            Presencial.ClipGuardado("p2", 60_000, 20_000, "/a/p2.mp4", Presencial.nombreDeLaPantalla("Clase", 1), 99, deCamara = false)
        )
        val ms = Presencial.mensajes("voz", "Clase", 1_000, null, "/a/voz.m4a", 500, 90_000, emptyList(), null, fotos, clips)
        assertEquals(listOf("voz", "f1", "k1", "c1", "p2"), ms.map { it.id })
        assertEquals(Presencial.DE_CAMARA, ms[1].fuente)
        assertNull("la captura de la pantalla no es de la cámara", ms[2].fuente)
        assertEquals("clase-7-2.png", ms[2].nombre)
        assertEquals(Presencial.DE_CAMARA, ms[3].fuente)
        assertNull("el trozo de la pantalla no es de la cámara", ms[4].fuente)
        assertEquals("Clase · pantalla 1.mp4", ms[4].nombre)
        val voz = linea(ms[0])
        assertTrue(voz, voz.contains(""""capturas":[{"id":"f1","ms":5000},{"id":"k1","ms":9000}]"""))
        assertTrue(voz, voz.contains(""""clips":[{"duracionMs":12000,"id":"c1","ms":30000},{"duracionMs":20000,"id":"p2","ms":60000}]"""))
        assertFalse(linea(ms[2]), linea(ms[2]).contains("fuente"))
        // Los trozos del vídeo de la pantalla, en sus grupos, aparte de los del vídeo de la sala.
        assertTrue(Presencial.esDePantalla(Presencial.GRUPO_DE_PANTALLA))
        assertTrue(Presencial.esDePantalla(Presencial.GRUPO_DE_PANTALLA + 3))
        assertFalse(Presencial.esDePantalla(0))
        assertFalse(Presencial.esDePantalla(1))
    }

    // --- La ida y vuelta con el PC ------------------------------------------------------------

    /** Una clase escrita por el PC con fotos y clips de la cámara y un clip del móvil, como su cuaderno. */
    private val delPc = listOf(
        """{"id":"voz","cuando":1761600000000,"clase":"VOZ","texto":"","ruta":"archivos/Reunión.m4a","nombre":"Reunión.m4a","bytes":5000,"referencia":null,"pagina":null,"duracionMs":120000,"proyecto":"obra","emoji":null,"fijado":false,"enBuzon":false,"transcripcion":"[0:00] Hola","miniapp":null,"respondeA":null,"numero":7,"letra":null,"uid":"ABCDE23456","aparato":"PC01","origen":null,"capturas":[{"id":"foto1","ms":5000}],"clips":[{"duracionMs":12000,"id":"clip1","ms":30000},{"duracionMs":4000,"id":"movil1","ms":61000}],"grabacion":"clase","marcadores":[{"ms":1000,"texto":"Inicio"}]}""",
        """{"id":"foto1","cuando":1761600000001,"clase":"IMAGEN","texto":"⏱ 0:05 · Reunión","ruta":"archivos/foto-1761600000000-1.jpg","nombre":"foto-1761600000000-1.jpg","bytes":4000000,"referencia":null,"pagina":null,"duracionMs":0,"proyecto":"obra","emoji":null,"fijado":false,"enBuzon":false,"transcripcion":null,"miniapp":null,"respondeA":null,"numero":8,"letra":null,"uid":"BCDEF23456","aparato":"PC01","origen":null,"enGrabacion":{"de":"voz","ms":5000},"fuente":"camara"}""",
        """{"id":"clip1","cuando":1761600000002,"clase":"ARCHIVO","texto":"","ruta":"archivos/Reunión · clip 1.mp4","nombre":"Reunión · clip 1.mp4","bytes":7000000,"referencia":null,"pagina":null,"duracionMs":0,"proyecto":"obra","emoji":null,"fijado":false,"enBuzon":false,"transcripcion":null,"miniapp":null,"respondeA":null,"numero":9,"letra":null,"uid":"CDEFG23456","aparato":"PC01","origen":null,"enGrabacion":{"de":"voz","duracionMs":12000,"ms":30000},"fuente":"camara"}""",
        """{"id":"movil1","cuando":1761600000003,"clase":"ARCHIVO","texto":"","ruta":"archivos/movil.mp4","nombre":"movil.mp4","bytes":3000000,"referencia":null,"pagina":null,"duracionMs":0,"proyecto":"obra","emoji":null,"fijado":false,"enBuzon":false,"transcripcion":null,"miniapp":null,"respondeA":null,"numero":10,"letra":null,"uid":"DEFGH23456","aparato":"PC01","origen":null,"enGrabacion":{"de":"voz","duracionMs":4000,"ms":61000},"fuente":"movil"}"""
    )

    /** Lo que tiene que volver igual, byte a byte, tras reescribir el mensaje aquí. */
    private val campos = listOf("capturas", "clips", "grabacion", "marcadores", "enGrabacion", "fuente", "video")

    private fun objeto(l: String): JsonObject = Json.parseToJsonElement(l).jsonObject

    @Test
    fun `una clase del PC con fotos y clips vuelve byte a byte al reescribirla`() {
        for (l in delPc) {
            val m = leer(l)
            // Reescribirla aquí: fijarla, como hace el chat.
            val otra = linea(m.copy(fijado = true))
            val antes = objeto(l)
            val despues = objeto(otra)
            for (k in campos) {
                assertEquals("$k de ${m.id}", antes[k]?.toString(), despues[k]?.toString())
                // Y en el texto, el mismo trozo exacto.
                antes[k]?.let { v -> assertTrue("$k de ${m.id}: $otra", otra.contains("\"$k\":$v")) }
            }
            assertEquals("true", despues["fijado"].toString())
        }
        val voz = leer(delPc[0])
        assertEquals(listOf("clip1", "movil1"), Presencial.clipsDe(voz).map { it.id })
        assertTrue(Presencial.esDeCamara(leer(delPc[1])))
        assertTrue(Presencial.esDelMovil(leer(delPc[3])))
        assertEquals(4_000L, Presencial.duracionDelEnlace(leer(delPc[3])))
        // Caso negativo: una captura de pantalla (sin fuente) sigue sin ella.
        val captura = delPc[1].replace(""","fuente":"camara"""", "")
        assertFalse(linea(leer(captura)).contains("fuente"))
    }

    @Test
    fun `la linea del PC con origen repetido se rescata`() {
        // Lo que escribía la primera reunión presencial del PC: `origen` en `resto`, dos veces en la línea.
        val l = """{"id":"7","cuando":7,"clase":"IMAGEN","nombre":"foto.jpg","aparato":null,"origen":null,"origen":"camara","enGrabacion":{"de":"v","ms":5}}"""
        val m = leer(l)
        assertEquals("no se pierde y vuelve a su sitio", Presencial.DE_CAMARA, m.fuente)
        assertNull("el campo del mensaje queda vacío", m.origen)
        assertEquals("""{"de":"v","ms":5}""", m.enGrabacion.toString())
        val otra = linea(m)
        assertTrue(otra, otra.contains(""""fuente":"camara""""))
        assertEquals("una sola vez `origen`", 1, Regex("\"origen\"").findAll(otra).count())
        // También lo del móvil.
        assertEquals(Presencial.DEL_MOVIL, leer(l.replace("\"camara\"", "\"movil\"")).fuente)
        // Caso negativo: un `origen` de verdad (la seña de un envío) no se toca.
        val envio = leer("""{"id":"8","cuando":8,"clase":"NOTA","origen":"K7Q2"}""")
        assertEquals("K7Q2", envio.origen)
        assertNull(envio.fuente)
    }
}
