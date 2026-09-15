package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.RegistroDelChat
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.Proyecto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Recibir no apila lienzos distintos en uno**, y el chat lo registra todo. Ver [Recepcion] y [RegistroDelChat]. */
class RecibirSinPerderTest {

    @Test
    fun `lienzos creados aqui no se toman por el mismo al recibir`() {
        // Los tres lienzos de la tableta, como se llaman al crearlos: hoja-<hora>.
        val aqui = listOf(Hoja("hoja-1726000000001", dibujo = "a"), Hoja("hoja-1726000000002", dibujo = "b"), Hoja("hoja-1726000000003", dibujo = "c"))
        // Llegan otros tres distintos, con la cola de esta importación.
        val sufijo = "-1726999999999"
        val llegan = listOf("hoja-1726000000011", "hoja-1726000000012", "hoja-1726000000013").map { Hoja(it + sufijo) }
        assertTrue(Recepcion.parejas(aqui, llegan, sufijo).isEmpty())
    }

    @Test
    fun `la misma hoja ida y vuelta se reconoce, y cada una una sola vez`() {
        val sufijo = "-1726999999999"
        // Aquí llegó de un envío anterior (lleva su cola); ahora vuelve con otra.
        val aqui = listOf(Hoja("hoja-1726000000001-1726500000000"), Hoja("hoja-1726000000002"))
        val llegan = listOf(Hoja("hoja-1726000000001$sufijo"), Hoja("hoja-1726000000002$sufijo"), Hoja("hoja-1726000000002-1726600000000$sufijo"))
        val r = Recepcion.parejas(aqui, llegan, sufijo)
        assertEquals("hoja-1726000000001-1726500000000", r["hoja-1726000000001$sufijo"]?.id)
        assertEquals("hoja-1726000000002", r["hoja-1726000000002$sufijo"]?.id)
        // La segunda que parece la misma no se pone encima de la ya emparejada.
        assertEquals(null, r["hoja-1726000000002-1726600000000$sufijo"])
    }

    @Test
    fun `un id no pierde la hora que lo hace unico`() {
        assertEquals(setOf("hoja-1726000000001"), Recepcion.nombresDe("hoja-1726000000001"))
        assertEquals(setOf("hoja-1726000000001-1726500000000", "hoja-1726000000001"), Recepcion.nombresDe("hoja-1726000000001-1726500000000"))
    }

    @Test
    fun `lo que esta en el proyecto y no en el chat entra en el chat, el PDF una vez y sin sus paginas`() {
        val p = Proyecto(
            "pr-1726000000000", "Tesis",
            hojas = listOf(
                Hoja("h-1726000000000-0", pagina = 0), Hoja("h-1726000000000-1", pagina = 1, dibujo = "pag1"),
                Hoja("hoja-1726100000000", "Uno", dibujo = "d1"),
                Hoja("hoja-1726200000000", "Dos", dibujo = "d2"),
                Hoja("hoja-1726300000000", "Tabla", tabla = "t1"),
                Hoja("nota-1726400000000", "Idea", nota = "texto")
            ),
            pdfOrigen = "/x/doc.pdf", pdfLimpio = "/x/limpio.pdf", croquis = listOf("croquis-1726500000000")
        )
        // El chat solo tenía el lienzo «Dos».
        val chat = listOf(Mensaje("m", 1726200000000, Clase.DIBUJO, proyecto = p.id, referencia = "d2"))
        val falta = RegistroDelChat.queFalta(p, chat)
        assertEquals(listOf(Clase.ARCHIVO, Clase.DIBUJO, Clase.TABLA, Clase.NOTA, Clase.CROQUIS), falta.map { it.clase })
        // Con la hora a la que se creó cada cosa.
        assertEquals(1726100000000, falta.first { it.referencia == "d1" }.cuando)
        assertEquals(1726000000000, falta.first { it.clase == Clase.ARCHIVO }.cuando)
        assertTrue(falta.all { it.unido && it.proyecto == p.id })
        // Ya puesto, no falta nada.
        assertTrue(RegistroDelChat.queFalta(p, chat + falta).isEmpty())
    }
}
