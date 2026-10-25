package com.forge.pixpin.motor

import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **La vista de cada marcador del lienzo** (25-oct-2026): las fórmulas de §3.8 de
 * `docs/sincronizar-marcos-vistas-y-tinta.md` y las pruebas de `VistaDeMarca` del PC
 * (`crates/pixpin-motor2d/src/marcas.rs`), con la cámara de aquí ([Viewport]).
 */
class VistasDeMarcasTest {
    private fun cerca(a: Double, b: Double, tol: Double = 1e-6) = assertEquals(b, a, tol)

    @Test
    fun `guardar y volver en la misma pantalla da la camara exacta`() {
        // Esquina del mundo (-120.5, 3400.25) a 2.5 aumentos en un hueco de 1600×900.
        val c = Viewport(scrollX = 120.5, scrollY = -3400.25, zoom = 2.5)
        val v = VistaDeMarca.deCamara(c, 1600.0, 900.0)!!
        cerca(v.x, -120.5 + 320.0); cerca(v.y, 3400.25 + 180.0)
        cerca(v.ancho, 640.0); cerca(v.alto, 360.0)
        val vuelta = v.aViewport(1600.0, 900.0)
        cerca(vuelta.scrollX, c.scrollX, 1e-3); cerca(vuelta.scrollY, c.scrollY, 1e-3); cerca(vuelta.zoom, c.zoom)
        // Y pasando por el texto que viaja, igual (a diezmilésimas).
        val leida = VistaDeMarca.deTexto(v.aTexto())!!.aViewport(1600.0, 900.0)
        cerca(leida.scrollX, c.scrollX, 1e-3); cerca(leida.scrollY, c.scrollY, 1e-3); cerca(leida.zoom, c.zoom, 1e-4)
    }

    @Test
    fun `en otra pantalla la vista sale entera y centrada`() {
        val v = VistaDeMarca(500.0, 200.0, 1000.0, 400.0)
        // Un móvil de pie: manda el ancho.
        val c = v.aViewport(400.0, 800.0)
        cerca(c.zoom, 0.4)
        val centro = c.toScene(200.0, 400.0)
        cerca(centro.x, 500.0, 1e-3); cerca(centro.y, 200.0, 1e-3)
        // Lo guardado cabe entero: sus esquinas caen dentro de la pantalla.
        val a = c.toScreen(Pt(0.0, 0.0)); val b = c.toScreen(Pt(1000.0, 400.0))
        assertTrue(a.x >= -1e-6 && b.x <= 400.0 + 1e-6 && a.y >= 0 && b.y <= 800.0)
        // Excalidraw: scrollX = w/(2·zoom) − x.
        cerca(c.scrollX, 400.0 / (2 * 0.4) - 500.0)
        // El aumento no se sale de sus topes por una vista rarísima.
        assertEquals(VistaDeMarca.ZOOM_MAXIMO, VistaDeMarca(0.0, 0.0, 1e-6, 1e-6).aViewport(800.0, 600.0).zoom, 0.0)
        assertEquals(VistaDeMarca.ZOOM_MINIMO, VistaDeMarca(0.0, 0.0, 1e9, 1e9).aViewport(800.0, 600.0).zoom, 0.0)
    }

    @Test
    fun `con los topes del editor una vista enorme no baja de su minimo y sigue centrada`() {
        // 25-oct-2026, como `camara_entre` del PC: el pellizco no baja de 0,1.
        val enorme = VistaDeMarca(500.0, 200.0, 100_000.0, 40_000.0)
        val c = enorme.aViewportEntre(800.0, 600.0, Viewport.MIN_ZOOM, VistaDeMarca.ZOOM_MAXIMO)
        assertEquals(0.1, c.zoom, 0.0)
        val centro = c.toScene(400.0, 300.0)
        cerca(centro.x, 500.0, 1e-2); cerca(centro.y, 200.0, 1e-2)
        // Caso de control: sin los topes del editor llega al de la cámara.
        assertEquals(VistaDeMarca.ZOOM_MINIMO, enorme.aViewport(800.0, 600.0).zoom, 0.0)
        // Unos topes del revés no dan un aumento del revés.
        assertEquals(2.0, VistaDeMarca(0.0, 0.0, 1.0, 1.0).aViewportEntre(800.0, 600.0, 2.0, 1.0).zoom, 0.0)
    }

    @Test
    fun `sin hueco no hay camara para ir a la marca`() {
        val dir = Files.createTempDirectory("vistas").toFile()
        try {
            val marcas = listOf(Marca(id = 1L, x = 0.0, y = 0.0, emoji = "⭐"))
            assertTrue(VistasDeMarcas.guardar(dir, "d", 1L, Viewport(zoom = 1.0), 800.0, 600.0, marcas))
            // La ventana minimizada o a medio crear: no se va a ningún sitio.
            assertNull(VistasDeMarcas.camaraPara(dir, "d", 1L, 0.0, 600.0))
            assertNull(VistasDeMarcas.camaraPara(dir, "d", 1L, 800.0, 0.5))
            // Con hueco, sí, y con el mínimo del editor.
            val c = VistasDeMarcas.camaraPara(dir, "d", 1L, 8.0, 6.0)!!
            assertEquals(Viewport.MIN_ZOOM, c.zoom, 0.0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `el texto de la vista y lo que no es una`() {
        assertEquals("12 -3.5 640 360.1235", VistaDeMarca(12.0, -3.5, 640.0, 360.123456).aTexto())
        assertEquals("320.5 1040 800 450", VistaDeMarca(320.5, 1040.0, 800.0, 450.0).aTexto())
        // Sin notación científica y las mitades hacia arriba, también las negativas (como el PC).
        assertEquals("0.0001 -0.0001 1 1", VistaDeMarca(0.0001, -0.00015, 1.0, 1.0).aTexto())
        assertEquals(VistaDeMarca(1.0, 2.0, 3.0, 4.0), VistaDeMarca.deTexto("  1  2 3   4 "))
        // Casos negativos: le falta un número, no es un número, no mide nada, infinito.
        assertNull(VistaDeMarca.deTexto(""))
        assertNull(VistaDeMarca.deTexto("1 2 3"))
        assertNull(VistaDeMarca.deTexto("1 2 x 4"))
        assertNull(VistaDeMarca.deTexto("1 2 0 4"))
        assertNull(VistaDeMarca.deTexto("1 2 Infinity 4"))
        assertNull(VistaDeMarca.deCamara(Viewport(), 0.0, 600.0))
    }

    @Test
    fun `el fichero va y vuelve por id y lo roto se salta`() {
        val vistas = mapOf(1792000000000L to VistaDeMarca(320.5, 1040.0, 800.0, 450.0), 5L to VistaDeMarca(0.0, 0.0, 10.0, 10.0))
        val texto = VistasDeMarcas.aTexto(vistas)
        // Por id en orden y con el formato del PC: un objeto de texto a texto.
        assertEquals("""{"5":"0 0 10 10","1792000000000":"320.5 1040 800 450"}""", texto)
        assertEquals(vistas, VistasDeMarcas.deTexto(texto))
        assertEquals("{}", VistasDeMarcas.aTexto(emptyMap()))
        // Lo que no se entiende se salta: un id que no es número, una vista rota, un número suelto.
        val raro = """{ "x": "1 2 3 4", "7": "1 2", "8": 5, "9": "1 2 3 4" }"""
        assertEquals(mapOf(9L to VistaDeMarca(1.0, 2.0, 3.0, 4.0)), VistasDeMarcas.deTexto(raro))
        assertTrue(VistasDeMarcas.deTexto("no es json").isEmpty())
        assertTrue(VistasDeMarcas.deTexto("[1,2]").isEmpty())
    }

    @Test
    fun `guardar podar y vaciar escriben llaves y no borran`() {
        val files = Files.createTempDirectory("vistas").toFile()
        try {
            val marcas = listOf(Marca(1, "🏠", 0.0, 0.0), Marca(2, "⭐", 5.0, 5.0))
            val f = VistasDeMarcas.archivo(files, "d1")
            assertEquals(File(files, "pins/draw/d1.vistas.json"), f)
            // Un lienzo que nunca tuvo vistas no estrena un `{}`.
            VistasDeMarcas.podar(files, "d1", marcas)
            assertFalse(f.exists())
            assertTrue(VistasDeMarcas.guardar(files, "d1", 1, Viewport(0.0, 0.0, 2.0), 800.0, 600.0, marcas))
            assertTrue(VistasDeMarcas.guardar(files, "d1", 2, Viewport(-100.0, 0.0, 1.0), 800.0, 600.0, marcas))
            assertEquals(setOf(1L, 2L), VistasDeMarcas.leer(files, "d1").keys)
            // Ir a la marca 1 en la misma pantalla: la cámara de cuando se puso.
            val c = VistasDeMarcas.camaraPara(files, "d1", 1, 800.0, 600.0)!!
            cerca(c.zoom, 2.0); cerca(c.scrollX, 0.0); cerca(c.scrollY, 0.0)
            // Sin vista, null: la marca vuelve como siempre.
            assertNull(VistasDeMarcas.camaraPara(files, "d1", 99, 800.0, 600.0))
            // Actualizar la vista la cambia.
            VistasDeMarcas.guardar(files, "d1", 1, Viewport(0.0, 0.0, 4.0), 800.0, 600.0, marcas)
            cerca(VistasDeMarcas.camaraPara(files, "d1", 1, 800.0, 600.0)!!.zoom, 4.0)
            // Quitar un marcador quita su entrada; sin ninguno, `{}` (no se borra).
            VistasDeMarcas.podar(files, "d1", marcas.take(1))
            assertEquals(setOf(1L), VistasDeMarcas.leer(files, "d1").keys)
            VistasDeMarcas.podar(files, "d1", emptyList())
            assertTrue(f.isFile)
            assertEquals("{}", f.readText())
            // Un hueco que no mide nada no guarda.
            assertFalse(VistasDeMarcas.guardar(files, "d1", 1, Viewport(), 0.0, 600.0, marcas))
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `viaja al lado del dibujo y de sus marcas`() {
        assertEquals("pins/draw/d1.vistas.json", AnotacionesDelAdjunto.vistasJuntoA("pins/draw/d1.excalidraw.gz"))
        assertEquals("pins/draw/hoja-pr-1-0.vistas.json", AnotacionesDelAdjunto.vistasJuntoA("pins/draw/hoja-pr-1-0.excalidraw.gz"))
        // Caso negativo: lo que no tiene marcas tampoco tiene vistas.
        assertNull(AnotacionesDelAdjunto.vistasJuntoA("pins/draw/files/x.excalidraw.gz"))
        assertNull(AnotacionesDelAdjunto.vistasJuntoA("croquis3d/c.croquis.gz"))
        assertNull(AnotacionesDelAdjunto.vistasJuntoA("pins/draw/d1.marcas"))
    }
}
