package com.forge.pixpin.guardados

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Vídeos y Enlaces en la ficha del chat** (25-oct-2026): las mismas pruebas que
 * `cada_seccion_se_queda_con_lo_suyo` y `los_videos_y_los_enlaces_tienen_su_seccion` de
 * `crates/pixpin-proyecto/src/cuaderno.rs` del PC.
 */
class SeccionesDelChatTest {
    private var n = 0
    private fun msg(id: String, clase: Clase, nombre: String = "", texto: String = "", fijado: Boolean = false, buzon: Boolean = false, ruta: String? = null) =
        Mensaje(id = id, cuando = (++n) * 1000L, clase = clase, nombre = nombre, texto = texto, fijado = fijado, enBuzon = buzon, ruta = ruta)

    private fun ids(l: List<Mensaje>, s: Seccion) = deSeccion(l, s).map { it.id }

    @Test
    fun `cada seccion se queda con lo suyo`() {
        val l = listOf(
            msg("n", Clase.NOTA, texto = "hola"),
            msg("i", Clase.IMAGEN, "a.jpg"),
            msg("a", Clase.ARCHIVO, "plano.pdf"),
            msg("v", Clase.VOZ, "v.m4a"),
            msg("d", Clase.DIBUJO, "croquis"),
            msg("p", Clase.PAGINA, "plano.pdf", fijado = true),
            msg("b", Clase.NOTA, texto = "caduca", buzon = true)
        )
        assertEquals(listOf("n", "i", "a", "v", "d", "p"), ids(l, Seccion.TODO))
        assertEquals(listOf("i"), ids(l, Seccion.FOTOS))
        assertEquals(listOf("a", "p"), ids(l, Seccion.ARCHIVOS))
        assertEquals(listOf("v"), ids(l, Seccion.VOZ))
        assertEquals(listOf("d"), ids(l, Seccion.DIBUJOS))
        assertEquals(listOf("p"), ids(l, Seccion.FIJADOS))
        assertEquals(listOf("b"), ids(l, Seccion.BUZON))
        assertEquals(emptyList<String>(), ids(l, Seccion.VIDEOS))
        assertEquals(emptyList<String>(), ids(l, Seccion.ENLACES))
    }

    @Test
    fun `los videos y los enlaces tienen su seccion`() {
        val l = listOf(
            msg("f", Clase.IMAGEN, "a.jpg"),
            msg("v1", Clase.IMAGEN, "clip.MP4"),
            msg("v2", Clase.ARCHIVO, "obra.mov"),
            msg("a", Clase.ARCHIVO, "plano.pdf"),
            msg("e", Clase.NOTA, texto = "mira (https://ejemplo.com/x)"),
            msg("w", Clase.NOTA, texto = "en www.pixpin.app hoy"),
            msg("n", Clase.NOTA, texto = "sin enlace: http:// ni www.")
        )
        assertEquals(listOf("v1", "v2"), ids(l, Seccion.VIDEOS))
        // Caso negativo: un vídeo no es una foto ni un archivo más.
        assertEquals(listOf("f"), ids(l, Seccion.FOTOS))
        assertEquals(listOf("a"), ids(l, Seccion.ARCHIVOS))
        assertEquals(listOf("e", "w"), ids(l, Seccion.ENLACES))
        // Lo del buzón tampoco se cuela en Vídeos ni en Enlaces.
        val conBuzon = l + msg("bv", Clase.IMAGEN, "x.mp4", texto = "https://a.com", buzon = true)
        assertEquals(listOf("v1", "v2"), ids(conBuzon, Seccion.VIDEOS))
        assertEquals(listOf("e", "w"), ids(conBuzon, Seccion.ENLACES))
    }

    @Test
    fun `que es un video y que no`() {
        assertTrue(SeccionesDelChat.esVideo(msg("1", Clase.ARCHIVO, "x.WebM")))
        // Sin nombre (lo de antes), por su ruta.
        assertTrue(SeccionesDelChat.esVideo(msg("2", Clase.IMAGEN, ruta = "/data/files/guardados/clip.m4v")))
        // Casos negativos: una voz con .mp4, un dibujo, un nombre sin extensión, un .mp3.
        assertFalse(SeccionesDelChat.esVideo(msg("3", Clase.VOZ, "grabacion.mp4")))
        assertFalse(SeccionesDelChat.esVideo(msg("4", Clase.DIBUJO, "x.mp4")))
        assertFalse(SeccionesDelChat.esVideo(msg("5", Clase.ARCHIVO, "mp4")))
        assertFalse(SeccionesDelChat.esVideo(msg("6", Clase.ARCHIVO, "cancion.mp3")))
    }

    @Test
    fun `que es un enlace y que no`() {
        assertTrue(SeccionesDelChat.tieneEnlace("«https://pixpin.app/precios»"))
        assertTrue(SeccionesDelChat.tieneEnlace("HTTP://Ejemplo.ES"))
        assertTrue(SeccionesDelChat.tieneEnlace("ver\n<www.ejemplo.com>."))
        // Casos negativos: sin punto en el servidor, empieza por punto, solo el prefijo, un correo.
        assertFalse(SeccionesDelChat.tieneEnlace("https://localhost/x"))
        assertFalse(SeccionesDelChat.tieneEnlace("www..ejemplo"))
        assertFalse(SeccionesDelChat.tieneEnlace("http:// y www."))
        assertFalse(SeccionesDelChat.tieneEnlace("escribe a ana@ejemplo.com"))
        assertFalse(SeccionesDelChat.tieneEnlace(""))
    }

    // ---- La ficha del chat (25-oct-2026, PC `8c92ba4`, `99f918a`, `2439c5c`). ----

    @Test
    fun `la de proyectos no se queda ningun mensaje`() {
        // Como `la_de_proyectos_no_se_queda_ningun_mensaje` del PC: ni una nota, ni un proyecto
        // adjunto, ni lo del buzón se cuelan en ella.
        val l = listOf(
            msg("n", Clase.NOTA, texto = "hola", fijado = true),
            msg("p", Clase.PROYECTO, "otro"),
            msg("b", Clase.IMAGEN, "x.png", buzon = true)
        )
        assertTrue(deSeccion(l, Seccion.PROYECTOS).isEmpty())
        assertTrue(SeccionesDelChat.cosasDe(l, Seccion.PROYECTOS).isEmpty())
        assertFalse(SeccionesDelChat.esCuadricula(Seccion.PROYECTOS))
        // Es la primera de la ficha, y «Todo» y el buzón no están en ella.
        assertEquals(Seccion.PROYECTOS, SeccionesDelChat.DE_LA_FICHA.first())
        assertFalse(Seccion.TODO in SeccionesDelChat.DE_LA_FICHA)
        assertFalse(Seccion.BUZON in SeccionesDelChat.DE_LA_FICHA)
        // Caso de control: los mismos mensajes sí salen en «Todo».
        assertEquals(2, deSeccion(l, Seccion.TODO).size)
    }

    @Test
    fun `las fotos de las notas ricas van en fotos en el sitio de su nota`() {
        val foto = "pixpin:files/guardados/pc/c/notas/1-a.png"
        val l = listOf(
            msg("f1", Clase.IMAGEN, "a.jpg"),
            msg("nota", Clase.NOTA, texto = "obra\n![]($foto)\n![planta]($foto)\n![acta](pixpin:files/x/acta.pdf)"),
            msg("llana", Clase.NOTA, texto = "sin fotos"),
            msg("v", Clase.IMAGEN, "clase.mp4"),
            msg("f2", Clase.IMAGEN, "b.jpg")
        )
        // El PDF de la nota no es una foto; el vídeo tiene su pestaña.
        assertEquals(2, SeccionesDelChat.fotosDeLaNota(l[1].texto).size)
        assertEquals(
            listOf(
                SeccionesDelChat.Cosa("f1"), SeccionesDelChat.Cosa("nota", 0),
                SeccionesDelChat.Cosa("nota", 1), SeccionesDelChat.Cosa("f2")
            ),
            SeccionesDelChat.cosasDe(l, Seccion.FOTOS)
        )
        // Caso negativo: lo escrito entre comillas no es una foto.
        assertTrue(SeccionesDelChat.fotosDeLaNota("`![](x.png)`").isEmpty())
        assertTrue(SeccionesDelChat.fotosDeLaNota("sin nada").isEmpty())
    }

    @Test
    fun `con las fotos de las notas mezcla en el orden del chat`() {
        // Como `con_las_fotos_de_las_notas` del PC: fotos en 0, 3 y 5; notas en 1 (dos fotos),
        // 4 (ninguna) y 6 (una).
        val cuantas = mapOf(1 to 2, 4 to 0, 6 to 1)
        assertEquals(
            listOf(0 to null, 1 to 0, 1 to 1, 3 to null, 5 to null, 6 to 0),
            SeccionesDelChat.conLasFotosDeLasNotas(listOf(0, 3, 5), listOf(1, 4, 6)) { cuantas.getValue(it) }
        )
        assertEquals(emptyList<Pair<Int, Int?>>(), SeccionesDelChat.conLasFotosDeLasNotas(emptyList(), emptyList()) { 0 })
    }

    @Test
    fun `la previa sigue a su cosa por id y si se va vuelve a la rejilla`() {
        val a = SeccionesDelChat.Cosa("a")
        val nota1 = SeccionesDelChat.Cosa("n", 1)
        val antes = listOf(a, SeccionesDelChat.Cosa("n", 0), nota1)
        assertEquals(2, SeccionesDelChat.reubicar(antes, nota1))
        // La sincronización mete dos delante: sigue en la misma, no en la que cae en su sitio.
        val despues = listOf(SeccionesDelChat.Cosa("x"), SeccionesDelChat.Cosa("y")) + antes
        assertEquals(4, SeccionesDelChat.reubicar(despues, nota1))
        // Borrada: a la rejilla.
        assertEquals(null, SeccionesDelChat.reubicar(listOf(a), nota1))
        assertEquals(null, SeccionesDelChat.reubicar(antes, null))
    }

    @Test
    fun `por meses con lo nuevo arriba`() {
        val zona = java.util.TimeZone.getTimeZone("UTC")
        fun ms(a: Int, m: Int, d: Int) = java.util.Calendar.getInstance(zona).apply { clear(); set(a, m - 1, d, 12, 0) }.timeInMillis
        val cosas = listOf("sep1" to ms(2026, 9, 1), "oct1" to ms(2026, 10, 1), "sep2" to ms(2026, 9, 20), "oct2" to ms(2026, 10, 25), "dic25" to ms(2025, 12, 31))
        val meses = SeccionesDelChat.porMeses(cosas, zona) { it.second }
        assertEquals(listOf(2026 to 10, 2026 to 9, 2025 to 12), meses.map { it.anio to it.mes })
        assertEquals(listOf("oct2", "oct1"), meses[0].cosas.map { it.first })
        assertEquals(listOf("sep2", "sep1"), meses[1].cosas.map { it.first })
        assertTrue(SeccionesDelChat.porMeses(emptyList<Long>(), zona) { it }.isEmpty())
    }

    @Test
    fun `los proyectos de la ficha se filtran por nombre con su negrita`() {
        val charlas = listOf(
            Conversacion(null, "Mensajes guardados", null, 3),
            Conversacion("asis", "Asistente", null, 0),
            Conversacion("r", "Reforma del Baño", null, 7),
            Conversacion("SIN", "Asistente", null, 0)
        )
        assertEquals(listOf(null, "asis", "r"), SeccionesDelChat.proyectosDeLaFicha(charlas, null, "SIN").map { it.proyecto })
        // Sin tildes ni mayúsculas, y la negrita en su sitio del nombre de verdad.
        assertEquals(listOf("r"), SeccionesDelChat.proyectosDeLaFicha(charlas, "bano", "SIN").map { it.proyecto })
        assertEquals(12..15, SeccionesDelChat.tramoBuscado("Reforma del Baño", "BAÑO"))
        // Casos negativos: nada que buscar, o no está.
        assertEquals(null, SeccionesDelChat.tramoBuscado("Reforma", " "))
        assertEquals(null, SeccionesDelChat.tramoBuscado("Reforma", "cocina"))
        assertTrue(SeccionesDelChat.proyectosDeLaFicha(charlas, "cocina", "SIN").isEmpty())
    }

    @Test
    fun `las hojas de cada chat son sus lienzos y paginas fuera del buzon`() {
        val l = listOf(
            msg("d1", Clase.DIBUJO).copy(proyecto = "r"),
            msg("p1", Clase.PAGINA, "plano.pdf").copy(proyecto = "r"),
            msg("d2", Clase.DIBUJO, buzon = true).copy(proyecto = "r"),
            msg("n", Clase.NOTA, texto = "x").copy(proyecto = "r"),
            msg("g", Clase.DIBUJO)
        )
        assertEquals(mapOf("r" to 2, null to 1), SeccionesDelChat.hojasPorChat(l))
    }

    @Test
    fun `las iniciales de un chat sin logo`() {
        assertEquals("RD", iniciales("Reforma del baño"))
        assertEquals("C", iniciales("Casa"))
        assertEquals("·", iniciales("  "))
    }

    @Test
    fun `cada extension tiene su color de familia`() {
        assertEquals(colorDeExtension("PDF"), colorDeExtension("pdf"))
        assertTrue(colorDeExtension("pdf") != colorDeExtension("docx"))
        assertEquals(colorDeExtension("mp4"), colorDeExtension("mov"))
    }
}
