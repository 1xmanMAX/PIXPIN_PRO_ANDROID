package com.forge.pixpin.ocr

import com.forge.pixpin.capture.GaleriaCompartida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Donde se usa el OCR en la app** (26-oct-2026): lo que se dice al copiar, qué resultado llega
 * tarde, qué capturas de la galería faltan por leer y cómo el texto leído pasa a la entrada que viaja.
 */
class OcrEnLaAppTest {

    @Test fun losAvisosSonLosDelPc() {
        assertEquals("No se encontró texto en la imagen.", CopiarTexto.aviso(""))
        assertEquals("No se pudo leer el texto de la imagen.", CopiarTexto.aviso(null))
        assertEquals("Texto copiado (10 caracteres): «Hola mundo»", CopiarTexto.aviso("Hola\nmundo"))
        val largo = "palabra ".repeat(20).trim()
        assertTrue(CopiarTexto.aviso(largo).endsWith("…»"))
    }

    @Test fun loQueLlegaTardeSeTira() {
        assertTrue(CopiarTexto.vale(3, 3, true))
        assertFalse("hubo otro pedido después", CopiarTexto.vale(2, 3, true))
        assertFalse("el pin se cerró o cambió de imagen", CopiarTexto.vale(3, 3, false))
    }

    @Test fun faltanPorLeerLasImagenesSinTextoEnSuOrden() {
        val l = listOf(
            TextoDeLaGaleria.Leible("c.png", 30, "image/png", null),
            TextoDeLaGaleria.Leible("v.mp4", 99, "video/mp4", null),
            TextoDeLaGaleria.Leible("b.png", 20, "image/png", null),
            TextoDeLaGaleria.Leible("a.webp", 10, "image/webp", null)
        )
        assertEquals(listOf("c.png", "a.webp"), TextoDeLaGaleria.pendientes(l, setOf("b.png")).map { it.nombre })
        assertTrue(TextoDeLaGaleria.pendientes(l, setOf("a.webp", "b.png", "c.png")).isEmpty())
    }

    @Test fun laCacheVaPorNombreYTamanoSinSalirseDeLaCarpeta() {
        val d = File("/tmp/x")
        assertEquals(File(d, "captura-1.png-123.txt"), TextoDeLaGaleria.enCache(d, "captura-1.png", 123))
        assertEquals(d, TextoDeLaGaleria.enCache(d, "../../etc/passwd", 1).parentFile)
        assertTrue(TextoDeLaGaleria.enCache(d, "a.png", 1) != TextoDeLaGaleria.enCache(d, "a.png", 2))
    }

    @Test fun elTextoLeidoAquiVaALaEntradaSinPisarElDelPc() {
        val e = GaleriaCompartida.Estado(listOf(
            GaleriaCompartida.Entrada("a.png", 1, cambiado = 5),
            GaleriaCompartida.Entrada("b.png", 2, cambiado = 6, texto = "del PC")
        ))
        val r = GaleriaCompartida.ponerTexto(e, mapOf("a.png" to "leído aquí", "b.png" to "otro", "nueva.png" to "x"))
        assertEquals("leído aquí", r.porNombre.getValue("a.png").texto)
        assertEquals("del PC", r.porNombre.getValue("b.png").texto)
        assertEquals("no mueve la fecha del cambio", 5, r.porNombre.getValue("a.png").cambiado)
        assertEquals("sin entrada, no se inventa", 2, r.entradas.size)
        assertSame(e, GaleriaCompartida.ponerTexto(e, mapOf("a.png" to " ")))
        // Y viaja: al juntar con el otro aparato, el texto no se pierde aunque gane la otra entrada.
        val otra = GaleriaCompartida.Entrada("a.png", 1, cambiado = 9, conservada = true)
        assertEquals("leído aquí", GaleriaCompartida.juntar(r.entradas, listOf(otra)).first { it.nombre == "a.png" }.texto)
    }
}
