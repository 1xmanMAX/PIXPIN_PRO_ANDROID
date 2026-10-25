package com.forge.pixpin.ia

import com.forge.pixpin.motor.Binding
import com.forge.pixpin.motor.BoundElement
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que construyen las pruebas del lienzo del asistente: una escena con sus `#n`. */
internal class EscenaDePrueba {
    val l = mutableListOf<Element>()
    private var n = 0

    /** Añade y devuelve su `#n` (su puesto, desde 1). */
    fun anadir(e: Element): Int { l += e; return l.size }

    fun id() = "e${++n}"

    fun caja(x: Double, y: Double, w: Double, h: Double, tipo: ElementType = ElementType.RECTANGLE) =
        Element(id = id(), type = tipo, x = x, y = y, width = w, height = h, seed = 1)

    fun texto(t: String, x: Double, y: Double, tam: Double = 20.0): Element {
        val (w, h) = LienzoDestino.medir(t, tam)
        return Element(id = id(), type = ElementType.TEXT, x = x, y = y, width = w, height = h, seed = 1, text = t, fontSize = tam)
    }

    /** Un texto centrado encima de una caja, sin atar (como lo dibuja `dibujar_en_lienzo`). */
    fun en(t: String, c: Element): Element {
        val e = texto(t, 0.0, 0.0)
        return e.copy(textAlign = TextAlign.CENTER, x = c.x + c.width / 2 - e.width / 2, y = c.y + c.height / 2 - e.height / 2)
    }

    /** Un rótulo atado a su caja (`containerId`); la caja lo apunta en `boundElements`. */
    fun atado(t: String, c: Element): Pair<Element, Element> {
        val e = en(t, c).copy(containerId = c.id)
        return c.copy(boundElements = listOf(BoundElement(e.id, ElementType.TEXT))) to e
    }

    fun flecha(a: Pair<Double, Double>, b: Pair<Double, Double>, fin: String? = null) = Element(
        id = id(), type = ElementType.ARROW, x = a.first, y = a.second,
        width = Math.abs(b.first - a.first), height = Math.abs(b.second - a.second), seed = 1,
        points = listOf(Pt(0.0, 0.0), Pt(b.first - a.first, b.second - a.second)),
        endBinding = fin?.let { Binding(elementId = it) }
    )
}

/** **La revisión automática del lienzo** (pruebas de `lint/pruebas.rs` del PC, portadas). */
class LintTest {

    private fun avisos(s: EscenaDePrueba) = Lint.revisar(s.l).map { it.texto }

    /** Dos cajas en fila con su rótulo y una flecha de una a otra. */
    private fun limpio(): Triple<EscenaDePrueba, Int, Int> {
        val s = EscenaDePrueba()
        val a = s.caja(0.0, 0.0, 160.0, 60.0)
        val b = s.caja(260.0, 0.0, 220.0, 60.0)
        val ia = s.anadir(a)
        val ib = s.anadir(b)
        s.anadir(s.en("Inicio", a))
        s.anadir(s.en("Fin del proceso", b))
        s.anadir(s.flecha(162.0 to 30.0, 258.0 to 30.0))
        s.anadir(s.texto("Número de página: también", 0.0, 200.0))
        return Triple(s, ia, ib)
    }

    @Test fun `un dibujo limpio no da avisos`() {
        assertEquals(emptyList<String>(), avisos(limpio().first))
        assertEquals("", Lint.paraElModelo(emptyList()))
    }

    @Test fun `textos encimados y figuras que se pisan`() {
        val s = EscenaDePrueba()
        val a = s.anadir(s.texto("Primero", 0.0, 0.0))
        val b = s.anadir(s.texto("Segundo", 10.0, 4.0))
        val v = avisos(s)
        assertTrue(v.toString(), v.contains("#$a y #$b: textos encimados"))
        val c = s.anadir(s.caja(300.0, 0.0, 100.0, 100.0))
        val d = s.anadir(s.caja(350.0, 50.0, 100.0, 100.0))
        assertTrue(avisos(s).any { it.startsWith("#$c y #$d: figuras que se pisan") })
        // Solo lo tocado: con #c tocado no sale lo de los textos.
        val solo = Lint.revisar(s.l, setOf(s.l[c - 1].id))
        assertTrue(solo.toString(), solo.isNotEmpty() && solo.all { s.l[c - 1].id in it.ids })
    }

    @Test fun `texto que se sale de su caja, suelto o atado`() {
        val s = EscenaDePrueba()
        val c = s.caja(0.0, 0.0, 140.0, 40.0)
        val t = s.anadir(s.en("Un rotulo demasiado largo", c))
        val ic = s.anadir(c)
        val v = avisos(s)
        assertTrue(v.toString(), v.any { it.startsWith("#$t se sale de #$ic (sobra") && it.contains("editar_texto con «ajustar»") })
        // Atado: se parte a lo ancho y se sale a lo alto.
        val s2 = EscenaDePrueba()
        val (caja, rotulo) = s2.atado("Un texto muy largo para tan poca caja", s2.caja(0.0, 0.0, 100.0, 30.0))
        val k = s2.anadir(caja)
        val r = s2.anadir(rotulo)
        assertTrue(avisos(s2).toString(), avisos(s2).any { it.startsWith("#$r se sale de #$k (sobra") && it.contains("a lo alto") })
    }

    @Test fun `flecha que no llega, marco y casi alineados`() {
        val (s, _, b) = limpio()
        val f = s.anadir(s.flecha(80.0 to 62.0, 300.0 to 100.0))
        val v = avisos(s)
        assertTrue(v.toString(), v.contains("#$f: el fin de la flecha se queda a 40 px de #$b sin tocarlo"))
        // Una flecha atada a algo que ya no está.
        val g = s.anadir(s.flecha(0.0 to 400.0, 50.0 to 400.0, fin = "no-esta"))
        assertTrue(avisos(s).contains("#$g: el fin apunta a una figura que ya no esta"))
        // Algo medio fuera de un marco.
        val m = s.anadir(s.caja(0.0, 300.0, 200.0, 200.0, ElementType.FRAME))
        val fuera = s.anadir(s.caja(150.0, 350.0, 100.0, 50.0))
        assertTrue(avisos(s).contains("#$fuera se sale a medias del marco #$m"))
        // Una tercera caja a 4 px de la fila de las dos primeras.
        val c = s.anadir(s.caja(520.0, 4.0, 160.0, 60.0))
        val w = avisos(s)
        assertTrue(w.toString(), w.any { it.contains("#$c") && it.contains("casi en la misma fila (4 px") })
    }

    @Test fun `faltan tildes en espanol`() {
        val s = EscenaDePrueba()
        val t = s.anadir(s.texto("El numero de pagina y la informacion", 0.0, 0.0))
        val q = s.anadir(s.texto("¿Que es esto?", 0.0, 100.0))
        val e = s.anadir(s.texto("La tarea esta terminada", 0.0, 200.0))
        val v = avisos(s)
        assertTrue(v.toString(), v.contains("#$t ortografia: «numero»→«número», «pagina»→«página», «informacion»→«información»"))
        assertTrue(v.toString(), v.contains("#$q ortografia: «Que»→«Qué»"))
        assertTrue(v.toString(), v.contains("#$e ortografia: «esta»→«está»"))
        // «Esta casa» y «que» sin pregunta están bien; en inglés no se busca nada de esto.
        val s2 = EscenaDePrueba(); s2.anadir(s2.texto("Esta casa es la que vimos", 0.0, 0.0))
        assertEquals(emptyList<String>(), avisos(s2))
        val s3 = EscenaDePrueba(); s3.anadir(s3.texto("The menu of the area", 0.0, 0.0))
        assertEquals(emptyList<String>(), avisos(s3))
    }

    @Test fun `lo que quedo sin traducir`() {
        val s = EscenaDePrueba()
        s.anadir(s.texto("开始", 0.0, 0.0))
        s.anadir(s.texto("处理数据", 0.0, 100.0))
        s.anadir(s.texto("结束", 0.0, 200.0))
        val sin = s.anadir(s.texto("Fin del proceso", 0.0, 300.0))
        assertTrue(avisos(s).toString(), avisos(s).contains("#$sin sigue en espanol y el resto esta en chino"))
    }

    @Test fun `el idioma de cada texto`() {
        assertEquals("zh", Lint.idiomaDe("流程开始"))
        assertEquals("es", Lint.idiomaDe("Inicio del proceso"))
        assertEquals("en", Lint.idiomaDe("Start of the process"))
        assertEquals(null, Lint.idiomaDe("A"))
        assertEquals(null, Lint.idiomaDe("42"))
    }

    @Test fun `un corrector de fuera se enchufa`() {
        val s = EscenaDePrueba()
        val t = s.anadir(s.texto("Inicio del proceso", 0.0, 0.0))
        val v = Lint.revisar(s.l, null) { texto, _ -> listOf(Lint.Falta(texto, null)) }
        assertEquals("#$t ortografia: «Inicio del proceso»", v[0].texto)
    }

    @Test fun `el resumen para el modelo es corto`() {
        val muchos = (0 until 20).map { Lint.Hallazgo(listOf("e$it"), "aviso $it") }
        val r = Lint.paraElModelo(muchos)
        assertEquals(r, Lint.MAX_LINEAS + 1, r.lines().size)
        assertTrue(r, r.endsWith("(+13 avisos mas)"))
    }
}
