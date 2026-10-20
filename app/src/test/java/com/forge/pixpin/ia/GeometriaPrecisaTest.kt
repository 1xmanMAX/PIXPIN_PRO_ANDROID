package com.forge.pixpin.ia

import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.marca
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** La geometría exacta del asistente (PC `edicion/hacer.rs`, 18-oct-2026). */
class GeometriaPrecisaTest {
    private fun a(s: String) = Json.parseToJsonElement(s) as JsonObject
    private val cero = Pt(0.0, 0.0)

    /** Dos rectas que se cruzan en (100, 100): una horizontal y otra a 60°. */
    private val dos = run {
        val h = GeometriaPrecisa.hacer(a("""{"figura":"recta","puntos":[0,100,300,100]}"""), emptyList(), cero)
        val o = GeometriaPrecisa.hacer(a("""{"figura":"recta","desde":[100,100],"angulo":60,"longitud":200}"""), h, cero)
        h + o
    }

    @Test fun `la recta por angulo y longitud va hacia arriba con 90`() {
        val r = GeometriaPrecisa.hacer(a("""{"figura":"recta","desde":[10,10],"angulo":90,"longitud":50,"flecha":"fin"}"""), emptyList(), cero).single()
        assertEquals(ElementType.ARROW, r.type)
        assertEquals(-50.0, r.points!!.last().y, 1e-9)
        assertNotNull(r.endArrowhead); assertNull(r.startArrowhead)
    }

    @Test fun `la marca entre dos rectas sale en su cruce con la siguiente griega`() {
        val m = GeometriaPrecisa.hacer(a("""{"figura":"marca_angulo","entre":"#1 #2","valor":"griega"}"""), dos, cero).single()
        assertEquals(ElementType.ARC, m.type)
        // Centro en el cruce.
        assertEquals(100.0, m.x + m.width / 2, 1e-6)
        assertEquals(100.0, m.y + m.height / 2, 1e-6)
        assertEquals("α", m.marca?.valor)
        assertEquals(60.0, Math.toDegrees(abs(m.arcSweep!!)), 0.5)
    }

    @Test fun `entre rectas a 90 grados sale el recto solo`() {
        val h = GeometriaPrecisa.hacer(a("""{"figura":"recta","puntos":[0,0,200,0]}"""), emptyList(), cero)
        val v = GeometriaPrecisa.hacer(a("""{"figura":"recta","puntos":[0,0,0,200]}"""), h, cero)
        val m = GeometriaPrecisa.hacer(a("""{"figura":"marca_angulo","entre":"#1 #2"}"""), h + v, cero).single()
        assertEquals(true, m.marca?.recto)
    }

    @Test fun `el punto del cruce lleva la siguiente letra`() {
        val p = GeometriaPrecisa.hacer(a("""{"figura":"punto","entre":"#1 #2"}"""), dos, cero).single()
        assertEquals(ElementType.PUNTO, p.type)
        assertEquals(100.0, p.x, 1e-6); assertEquals(100.0, p.y, 1e-6)
        assertEquals("A", p.text)
    }

    @Test fun `el sector con degradado y el marco del modelo`() {
        val s = GeometriaPrecisa.hacer(a("""{"figura":"sector","centro":[50,50],"radio":40,"inicio":0,"fin":90,"relleno":"rojo","degradado":"radial"}"""), emptyList(), Pt(1000.0, 500.0)).single()
        assertEquals(1050.0, s.x + s.width / 2, 1e-6)
        assertEquals(-PI / 2, s.arcSweep!!, 1e-9)
        assertNotNull(s.degradado)
        assertTrue(s.backgroundColor != com.forge.pixpin.motor.Element.TRANSPARENT)
    }

    @Test fun `lo mal puesto se dice`() {
        try { GeometriaPrecisa.hacer(a("""{"figura":"marca_angulo","entre":"#1"}"""), dos, cero); fail() } catch (e: GeometriaPrecisa.Fallo) { assertTrue(e.message!!.contains("dos rectas")) }
        try { GeometriaPrecisa.hacer(a("""{"figura":"circulo","puntos":[0,0,1,1,2,2]}"""), emptyList(), cero); fail() } catch (e: GeometriaPrecisa.Fallo) { assertTrue(e.message!!.contains("en linea")) }
        try { GeometriaPrecisa.hacer(a("""{"figura":"paralela","a":"#9","distancia":5}"""), dos, cero); fail() } catch (e: GeometriaPrecisa.Fallo) { assertTrue(e.message!!.contains("#9")) }
    }
}
