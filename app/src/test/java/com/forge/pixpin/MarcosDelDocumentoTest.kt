package com.forge.pixpin.motor

import com.forge.pixpin.motor.MarcosDelDocumento.Marco
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Los marcos numerados y las vistas de los marcadores, con las reglas y el formato del PC (`marcos_doc.rs`). */
class MarcosDelDocumentoTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun comoEnElPlanoSeNumeranPorOrdenYQuitarQuitaElUltimo() {
        var l = emptyList<Marco>()
        for (i in 0 until 3) {
            val x = i * 100.0
            l = MarcosDelDocumento.conNuevo(l, x, 0.0, x + 50, 40.0, 1.0)!!
        }
        assertEquals(3, l.size)
        assertEquals(Marco(200.0, 0.0, 250.0, 40.0), l[2])
        l = MarcosDelDocumento.sinElUltimo(l)
        assertEquals(2, l.size)
        assertEquals(100.0, l.last().x0, 0.0)
    }

    @Test fun unToqueNoEsUnMarcoYSeOrdenaArrastreHaciaDondeArrastre() {
        assertNull("2 px de ancho es un toque", MarcosDelDocumento.conNuevo(emptyList(), 10.0, 10.0, 12.0, 30.0, 1.0))
        // A doble zoom, 5 unidades son 10 px: sí cuenta.
        val l = MarcosDelDocumento.conNuevo(emptyList(), 50.0, 50.0, 45.0, 40.0, 2.0)!!
        assertEquals(Marco(45.0, 40.0, 50.0, 50.0), l[0])
    }

    @Test fun leeLoQueEscribeElPcConSerdeYAlReves() {
        // Lo de la prueba del PC (`lo_de_junto_a_un_adjunto_pasa_una_vez`) y su forma «pretty».
        val delPc = """[{"caja":[0.0,0.0,50.0,40.0],"parte":2}]"""
        assertEquals(listOf(Marco(0.0, 0.0, 50.0, 40.0, 2)), MarcosDelDocumento.deTexto(delPc))
        val bonito = "[\n  {\n    \"caja\": [\n      10.5,\n      -3.0,\n      200.0,\n      90.0\n    ],\n    \"parte\": 0,\n    \"vista\": \"x\"\n  }\n]"
        assertEquals(listOf(Marco(10.5, -3.0, 200.0, 90.0, 0, "x")), MarcosDelDocumento.deTexto(bonito))
        val l = listOf(Marco(1.0, 2.0, 3.0, 4.0, 1), Marco(5.0, 6.0, 70.25, 80.0))
        assertEquals(l, MarcosDelDocumento.deTexto(MarcosDelDocumento.aTexto(l)))
        assertFalse("sin vista no se escribe", MarcosDelDocumento.aTexto(l).contains("vista"))
        assertEquals("[]", MarcosDelDocumento.aTexto(emptyList()))
    }

    @Test fun loRotoODeUnModelo3DNoEsDeAqui() {
        assertTrue(MarcosDelDocumento.deTexto("roto").isEmpty())
        assertTrue(MarcosDelDocumento.deTexto("""{"version":1,"marcos":[]}""").isEmpty())
        // Uno sin caja entera se salta; los demás se quedan.
        assertEquals(1, MarcosDelDocumento.deTexto("""[{"caja":[1,2]},{"caja":[0,0,4,4]}]""").size)
    }

    @Test fun laVistaDeUnMarcadorVuelveIgual() {
        val t = MarcosDelDocumento.zoomATexto(1.75, 120.0, 3400.5)
        assertEquals("1.75 120 3400.5", t)
        assertEquals(Triple(1.75, 120.0, 3400.5), MarcosDelDocumento.zoomDeTexto(t))
        assertNull(MarcosDelDocumento.zoomDeTexto("roto"))
        val v = mapOf("9" to "1.5 0 300", "1792" to t)
        assertEquals(v, MarcosDelDocumento.vistasDeTexto(MarcosDelDocumento.vistasATexto(v)))
        assertEquals("{}", MarcosDelDocumento.vistasATexto(emptyMap()))
        // Las de un plano o un modelo llevan `version` y otro formato.
        assertTrue(MarcosDelDocumento.vistasDeTexto("""{"version":1,"plano":[]}""").isEmpty())
    }

    @Test fun vaciosQuedanVaciosYNoSeCreanSinNada() {
        val f = MarcosDelDocumento.archivo(tmp.root, "anot-ABCDE23456")
        assertEquals("anot-ABCDE23456.marcos.json", f.name)
        MarcosDelDocumento.guardar(f, emptyList())
        assertFalse("abrir sin marcos no crea nada", f.exists())
        MarcosDelDocumento.guardar(f, listOf(Marco(0.0, 0.0, 100.0, 80.0, 1)))
        assertEquals(1, MarcosDelDocumento.leer(f).size)
        val fecha = f.lastModified()
        Thread.sleep(20)
        f.setLastModified(fecha)
        MarcosDelDocumento.guardar(f, listOf(Marco(0.0, 0.0, 100.0, 80.0, 1)))
        assertEquals("guardar lo mismo no toca el archivo", fecha, f.lastModified())
        MarcosDelDocumento.guardar(f, emptyList())
        assertEquals("[]", f.readText())
        val fv = MarcosDelDocumento.archivoDeVistas(tmp.root, "anot-ABCDE23456")
        MarcosDelDocumento.guardarVistas(fv, mapOf("7" to "2 0 10"))
        MarcosDelDocumento.guardarVistas(fv, emptyMap())
        assertEquals("{}", fv.readText())
    }

    @Test fun lasHojasDeUnPdfVanEnLasUnidadesDelPc() {
        // Como `Hojas::colocar`: 1400 de ancho, el alto por la proporción, 18 de hueco.
        val c = MarcosDelDocumento.ColumnaDeHojas(listOf(595.0 to 842.0, 842.0 to 595.0, null))
        assertEquals(1400.0 * 842 / 595, c.altos[0], 1e-6)
        assertEquals(c.altos[0] + 18.0, c.arriba[1], 1e-6)
        assertEquals(1400.0 * 1.414, c.altos[2], 1e-6)
        assertEquals(0, c.en(10.0))
        assertEquals("en el hueco, la de encima", 0, c.en(c.arriba[1] - 1))
        assertEquals(1, c.en(c.arriba[1] + 1))
        assertEquals(2, c.en(1e9))
    }

    @Test fun paraLlenarLaVentanaMandaElLadoQueMasAprieta() {
        assertEquals(4.7, MarcosDelDocumento.pxParaLlenar(Marco(0.0, 0.0, 200.0, 100.0), 1000.0, 1000.0), 1e-6)
    }
}
