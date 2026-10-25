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
}
