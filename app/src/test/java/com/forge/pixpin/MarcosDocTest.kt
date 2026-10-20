package com.forge.pixpin

import com.forge.pixpin.motor.GuardadoDoc
import com.forge.pixpin.motor.MarcoDoc
import com.forge.pixpin.motor.MarcosDoc
import com.forge.pixpin.motor.VistasDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Los casos de `marcos_doc.rs` del PC (20-oct-2026) y los JSON tal como los escribe. */
class MarcosDocTest {
    private fun dir(): File = Files.createTempDirectory("marcos").toFile()

    @Test
    fun `como en el plano se numeran por orden y quitar el ultimo renumera`() {
        val m = MarcosDoc()
        assertFalse("sin el modo, el toque es del visor", m.pulsar(doubleArrayOf(0.0, 0.0)))
        m.alternar()
        for (i in 0 until 3) {
            val x = i * 100.0
            assertTrue(m.pulsar(doubleArrayOf(x, 0.0)))
            m.mover(doubleArrayOf(x + 50, 40.0))
            assertTrue(m.soltar(doubleArrayOf(x + 50, 40.0), 1.0))
        }
        assertEquals(3, m.lista.size)
        assertEquals(listOf(200.0, 0.0, 250.0, 40.0), m.lista[2].caja)
        assertTrue(m.quitarUltimo())
        assertEquals(2, m.lista.size)
        m.alternar()
        assertFalse(m.activo)
    }

    @Test
    fun `un toque no es un marco y se ordena arrastre hacia donde arrastre`() {
        val m = MarcosDoc(); m.alternar()
        m.pulsar(doubleArrayOf(10.0, 10.0))
        assertFalse("2 px de ancho es un toque", m.soltar(doubleArrayOf(12.0, 30.0), 1.0))
        m.pulsar(doubleArrayOf(50.0, 50.0))
        assertTrue("a doble zoom, 5 unidades son 10 px", m.soltar(doubleArrayOf(45.0, 40.0), 2.0))
        assertEquals(listOf(45.0, 40.0, 50.0, 50.0), m.lista[0].caja)
    }

    @Test
    fun `lee el JSON del PC, con parte y vista, y salta lo que no entiende`() {
        val pc = """[
  { "caja": [0.0, 0.0, 50.0, 40.0], "parte": 2 },
  { "caja": [10, 20, 5, 8], "parte": 0, "vista": "abc", "futuro": true },
  { "caja": [1, 2] },
  "roto"
]"""
        val l = MarcosDoc.deTexto(pc)
        assertEquals(2, l.size)
        assertEquals(2, l[0].parte)
        assertEquals(listOf(5.0, 8.0, 10.0, 20.0), l[1].caja)
        assertEquals("abc", l[1].vista)
        assertEquals(l, MarcosDoc.deTexto(MarcosDoc.aTexto(l)))
        assertEquals(emptyList<MarcoDoc>(), MarcosDoc.deTexto("{"))
    }

    @Test
    fun `los de un adjunto vacios quedan vacios y los sueltos se quitan`() {
        val d = dir()
        val f = File(d, "anot-ABCDE23456.marcos.json")
        val m = MarcosDoc.de(GuardadoDoc(f, viaja = true))
        m.guardar()
        assertFalse("abrir sin marcos no crea nada", f.exists())
        m.alternar(); m.pulsar(doubleArrayOf(0.0, 0.0), 1); m.soltar(doubleArrayOf(100.0, 80.0), 1.0)
        assertTrue(f.isFile)
        assertEquals(m.lista, MarcosDoc.de(GuardadoDoc(f, true)).lista)
        val fecha = f.lastModified()
        Thread.sleep(20)
        f.setLastModified(fecha - 5000)
        val antes = f.lastModified()
        m.guardar()
        assertEquals("guardar lo mismo no toca el archivo", antes, f.lastModified())
        m.borrar()
        assertEquals("[]", f.readText())

        val suelto = File(d, "plano.pdf.pixpin-marcos")
        val s = MarcosDoc.de(GuardadoDoc(suelto, viaja = false))
        s.alternar(); s.pulsar(doubleArrayOf(0.0, 0.0)); s.soltar(doubleArrayOf(100.0, 80.0), 1.0)
        assertTrue(suelto.isFile)
        s.borrar()
        assertFalse("sin marcos no queda archivo", suelto.exists())
    }

    @Test
    fun `la vista de un marcador vuelve igual y vacia es un objeto vacio`() {
        val f = File(dir(), "anot-ABCDE23456.vistas.json")
        val v = VistasDoc.de(GuardadoDoc(f, true))
        v.poner("a1", VistasDoc.zoomATexto(1.75, 120.0, 3400.5))
        assertEquals("1.75 120 3400.5", VistasDoc.de(GuardadoDoc(f, true)).deMarca("a1"))
        assertEquals(Triple(1.75, 120.0, 3400.5), VistasDoc.zoomDeTexto(VistasDoc.de(GuardadoDoc(f, true)).deMarca("a1")))
        assertNull(VistasDoc.zoomDeTexto("roto"))
        // Lo que escribe el PC (`{ "1792…": "1.75 120 3400.5" }`).
        assertEquals(mapOf("1792" to "2 0 10"), VistasDoc.deTexto("""{ "1792": "2 0 10", "otro": 5 }"""))
        v.quitar("a1")
        assertEquals("{}", f.readText())
    }

    @Test
    fun `para llenar la ventana manda el lado que mas aprieta`() {
        val px = MarcosDoc.pxParaLlenar(MarcoDoc(listOf(0.0, 0.0, 200.0, 100.0)), 1000.0, 1000.0)
        assertEquals(4.7, px, 1e-6)
    }
}
