package com.forge.pixpin.ia

import com.forge.pixpin.ia.Anotacion.Clase
import com.forge.pixpin.ia.Anotacion.Colocador
import com.forge.pixpin.ia.Anotacion.Franja
import com.forge.pixpin.ia.Anotacion.Lado
import com.forge.pixpin.ia.Anotacion.Pagina
import com.forge.pixpin.ia.Anotacion.Palabra
import com.forge.pixpin.ia.Anotacion.Pedida
import com.forge.pixpin.ia.Anotacion.Renglon
import com.forge.pixpin.motor.ElementType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **El anotador con cabeza**: las pruebas de `pixpin_ia::anotacion` del PC, una a una. */
class AnotacionTest {
    private fun hoja(renglones: List<String>): Pagina {
        val v = ArrayList<Renglon>()
        for ((i, t) in renglones.withIndex()) {
            val y = 200.0 + i * 40.0
            var x = 300.0
            val palabras = ArrayList<Palabra>()
            for (w in t.split(' ').filter { it.isNotEmpty() }) {
                val ancho = w.codePointCount(0, w.length) * 13.0
                palabras += Palabra(w, doubleArrayOf(x, y, x + ancho, y + 26.0))
                x += ancho + 8.0
            }
            v += Renglon(palabras)
        }
        return Pagina(0, doubleArrayOf(0.0, 0.0, 1400.0, 1980.0), v, 20.0, 0.0)
    }

    private val TEXTO = listOf(
        "Capitulo 2. El modelo de la viga",
        "La viga se apoya en dos columnas y soporta",
        "una carga repartida de 12 kN/m en toda su luz.",
        "El momento maximo vale M = q L^2 / 8 y aparece",
        "en el centro del vano, donde la flecha es mayor.",
        "Para el calculo se usa el modelo elastico lineal."
    )

    private fun pedida(clase: Clase, ancla: String, nota: String) = Pedida(clase, 0, ancla, null, nota)
    private fun solapan(a: DoubleArray, b: DoubleArray) = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun `ancla el trozo exacto sin mirar mayusculas tildes ni signos`() {
        val p = hoja(TEXTO)
        val a = Anotacion.Ancla.anclar(p, "carga repartida de 12 kN/m", null)!!
        assertTrue(a.exacto)
        assertEquals(2 to 2, a.renglon to a.ultimo)
        assertEquals(p.renglones[2].palabras[1].caja[0], a.cajas[0][0], 0.0)
        assertEquals(3, Anotacion.Ancla.anclar(p, "M = q L^2/8", null)!!.renglon)
        val dos = Anotacion.Ancla.anclar(p, "aparece en el centro del vano", null)!!
        assertEquals(Triple(3, 4, 2), Triple(dos.renglon, dos.ultimo, dos.cajas.size))
    }

    @Test fun `una palabra corta no se ancla dentro de otra`() {
        val p = hoja(listOf("el cuaderno de notas"))
        assertEquals(p.renglones[0].palabras[2].caja[0], Anotacion.Ancla.anclar(p, "de", null)!!.cajas[0][0], 0.0)
        assertNull("un trozo de palabra no es un ancla", Anotacion.Ancla.anclar(p, "uad", null))
    }

    @Test fun `con un error de OCR se ancla por parecido y lo inventado no`() {
        val p = hoja(TEXTO)
        p.renglones[5].palabras[5].texto = "rnodelo"
        val a = Anotacion.Ancla.anclar(p, "se usa el modelo elastico lineal", null)!!
        assertFalse(a.exacto)
        assertEquals(5, a.renglon)
        assertNull(Anotacion.Ancla.anclar(p, "la resistencia del hormigon armado", null))
    }

    @Test fun `con dos sitios iguales manda el renglon que dijo el modelo`() {
        val p = hoja(listOf("el modelo uno", "otra cosa", "el modelo dos", "el modelo tres"))
        assertEquals(0, Anotacion.Ancla.anclar(p, "el modelo", null)!!.renglon)
        assertEquals(2, Anotacion.Ancla.anclar(p, "el modelo", 3)!!.renglon)
        assertEquals(3, Anotacion.Ancla.anclar(p, "el modelo", 4)!!.renglon)
    }

    @Test fun `en una hoja de calculo se ancla a la celda o al rango`() {
        val p = hoja(emptyList())
        for ((etq, t) in listOf("A1" to "Precio", "B1" to "120", "A2" to "Cantidad", "B2" to "3", "B3" to "360")) {
            val (f, c) = Anotacion.Ancla.celda(etq)!!
            p.renglones += Renglon(mutableListOf(Palabra(t, doubleArrayOf(100.0 + c * 80, 100.0 + f * 20, 180.0 + c * 80, 120.0 + f * 20))), etq)
        }
        assertEquals(2 to 1, Anotacion.Ancla.celda("B3"))
        assertEquals(9 to 26, Anotacion.Ancla.celda("AA10"))
        assertNull(Anotacion.Ancla.celda("3B"))
        assertEquals(listOf(180.0, 140.0, 260.0, 160.0), Anotacion.Ancla.anclar(p, "B3", null)!!.union().toList())
        assertEquals(listOf(180.0, 100.0, 260.0, 160.0), Anotacion.Ancla.anclar(p, "B1:B3", null)!!.union().toList())
        assertNull(Anotacion.Ancla.anclar(p, "D10:E12", null))
        assertEquals(2, Anotacion.Ancla.anclar(p, "Cantidad", null)!!.renglon)
    }

    @Test fun `las notas no se solapan y cambian de lado cuando un margen se llena`() {
        val c = Colocador(listOf(Franja(20.0, 280.0, 0.0, 600.0, Lado.IZQUIERDA, false), Franja(1120.0, 1380.0, 0.0, 600.0, Lado.DERECHA, false)), 10.0)
        val puestas = (0 until 10).mapNotNull { c.colocar(doubleArrayOf(900.0, 300.0, 1000.0, 326.0), { 110.0 }, 400.0) }
        for ((i, a) in puestas.withIndex()) {
            for (b in puestas.drop(i + 1)) assertFalse(solapan(a.caja, b.caja))
            assertTrue(a.caja[1] >= 0 && a.caja[3] <= 600)
        }
        assertEquals(Lado.DERECHA, puestas[0].franja.lado)
        assertEquals(295.0, puestas[0].caja[1], 1.0)
        assertTrue(puestas.any { it.franja.lado == Lado.IZQUIERDA })
        assertTrue("${puestas.size}", puestas.size in 8..10)
        assertNull(c.colocar(doubleArrayOf(900.0, 300.0, 1000.0, 326.0), { 110.0 }, 400.0))
    }

    @Test fun `salir de la hoja solo si dentro no hay sitio`() {
        val p = hoja(TEXTO); p.fuera = 1050.0
        val fs = p.franjas()
        assertEquals(2, fs.count { it.fuera })
        assertFalse(Colocador(fs, 10.0).colocar(doubleArrayOf(900.0, 300.0, 1000.0, 326.0), { 100.0 }, 360.0)!!.franja.fuera)
        val ancha = hoja(listOf("texto de borde a borde de la hoja entera sin margen ninguno"))
        val pal = ancha.renglones[0].palabras
        for (k in pal.indices) pal[k] = Palabra(pal[k].texto, doubleArrayOf(10.0, pal[k].caja[1], 1390.0, pal[k].caja[3]))
        assertTrue(ancha.franjas().isEmpty())
        ancha.fuera = 1050.0
        assertTrue(ancha.franjas().all { it.fuera })
    }

    @Test fun `el flujo suelta cada objeto al cerrarse aunque llegue a trozos`() {
        val f = Anotacion.Flujo()
        val texto = "```json\n{\"tipo\":\"importante\",\"ancla\":\"la {viga}\",\"nota\":\"dice \\\"hola\\\"\"}\n{\"tipo\":\"paso\",\"ancla\":\"x\",\"n\":2}\n```"
        val salen = ArrayList<kotlinx.serialization.json.JsonObject>(); val cuando = ArrayList<Int>()
        for ((i, c) in texto.withIndex()) { val v = f.empujar(c.toString()); if (v.isNotEmpty()) cuando += i; salen += v }
        assertEquals(2, salen.size)
        assertEquals("la {viga}", salen[0]["ancla"]!!.jsonPrimitive.content)
        assertEquals("dice \"hola\"", salen[0]["nota"]!!.jsonPrimitive.content)
        assertTrue(cuando[0] < texto.indexOf("paso"))
        assertEquals(0, f.rotos)
        assertTrue(f.empujar("{\"tipo\": roto}").isEmpty())
        assertEquals(1, f.rotos)
    }

    @Test fun `se leen las anotaciones y sus sinonimos`() {
        val a = Anotacion.Leida.de(j("""{"tipo":"error","pag":2,"ancla":"12 kN/m","l":"L3","nota":"la unidad","corr":"12 kN/m²"}"""))
        val p = (a as Anotacion.Leida.Anotacion).p
        assertEquals(Triple(Clase.ERROR, 1, 3), Triple(p.clase, p.pagina, p.linea))
        assertEquals("12 kN/m²", p.correccion)
        assertEquals(Clase.DEFINICION, (Anotacion.Leida.de(j("""{"tipo":"glosario","ancla":"luz","nota":"luz: distancia"}""")) as Anotacion.Leida.Anotacion).p.clase)
        assertTrue(Anotacion.Leida.de(j("""{"tipo":"nada","nota":"pagina en blanco"}""")) is Anotacion.Leida.Nada)
        assertTrue(Anotacion.Leida.de(j("""{"tipo":"importante","nota":"sin ancla"}""")) is Anotacion.Leida.Mal)
        assertTrue(Anotacion.Leida.de(j("""{"tipo":"dibujo","ancla":"x","nota":"y"}""")) is Anotacion.Leida.Mal)
    }

    @Test fun `el anotador pone cada nota en el margen sin pisar texto ni otra nota`() {
        val col = hoja(TEXTO).columna()!!
        val a = Anotacion.Anotador(listOf(hoja(TEXTO)), "t1")
        val pedidas = listOf(
            pedida(Clase.IMPORTANTE, "carga repartida de 12 kN/m", "Es la carga de diseño: entra en todas las fórmulas del capítulo."),
            pedida(Clase.EXPLICACION, "M = q L^2 / 8", "M: momento máximo; q: carga por metro; L: luz entre apoyos."),
            pedida(Clase.PASO, "La viga se apoya en dos columnas", "Identifica los apoyos.").copy(numero = 1),
            pedida(Clase.ERROR, "modelo elastico lineal", "Falta la tilde.").copy(correccion = "modelo elástico lineal"),
            pedida(Clase.DEFINICION, "vano", "vano: tramo entre dos apoyos."),
            pedida(Clase.PREGUNTA, "donde la flecha es mayor", "¿Por qué la flecha máxima está en el centro?"),
            pedida(Clase.RESUMEN, "", "Viga biapoyada con carga repartida: momento máximo en el centro.")
        )
        val notas = ArrayList<DoubleArray>()
        for (p in pedidas) {
            val puesta = a.poner(p).getOrThrow()
            assertFalse(puesta.fuera)
            assertTrue(puesta.elementos.all { "asistente-anotado:t1" in it.groupIds })
            val papel = puesta.elementos.last { it.type == ElementType.RECTANGLE && it.backgroundColor != com.forge.pixpin.motor.Element.TRANSPARENT && it.opacity > 90 }
            val caja = doubleArrayOf(papel.x, papel.y, papel.x + papel.width, papel.y + papel.height)
            assertTrue("la nota tapa la columna: ${caja.toList()}", caja[2] <= col[0] || caja[0] >= col[2])
            notas += caja
        }
        for ((i, x) in notas.withIndex()) for (y in notas.drop(i + 1)) assertFalse(solapan(x, y))
        assertEquals(7, a.cuantas())
        assertTrue(a.poner(pedidas[0]).isFailure)
        assertTrue(a.poner(pedida(Clase.IMPORTANTE, "hormigon pretensado", "x")).isFailure)
        val r = a.resumen()
        assertTrue(r, r.startsWith("7 anotaciones") && r.contains("hormigon pretensado"))
    }

    @Test fun `la linea guia no tapa ninguna palabra`() {
        val p = hoja(TEXTO)
        val palabras = p.renglones.flatMap { r -> r.palabras.map { it.caja } }
        val a = Anotacion.Anotador(listOf(p), "t2")
        val puesta = a.poner(pedida(Clase.IMPORTANTE, "dos columnas", "Los apoyos.")).getOrThrow()
        val marca = puesta.elementos.first { it.type == ElementType.RECTANGLE }
        val dentroDeLaMarca = { c: DoubleArray -> c[0] >= marca.x && c[2] <= marca.x + marca.width && c[1] >= marca.y && c[3] <= marca.y + marca.height }
        for (e in puesta.elementos.filter { it.type == ElementType.LINE }) {
            val pts = e.points!!.map { (e.x + it.x) to (e.y + it.y) }
            for (k in 0 until pts.size - 1) for (s in 0..40) {
                val t = s / 40.0
                val x = pts[k].first + (pts[k + 1].first - pts[k].first) * t; val y = pts[k].second + (pts[k + 1].second - pts[k].second) * t
                for (c in palabras.filter { !dentroDeLaMarca(it) }) assertFalse("la guia pasa por ${c.toList()} en ($x, $y)", x > c[0] + 2 && x < c[2] - 2 && y > c[1] + 2 && y < c[3] - 2)
            }
        }
    }

    @Test fun `una pagina vacia no da nada que anclar`() {
        val vacia = Pagina(0, doubleArrayOf(0.0, 0.0, 1400.0, 1980.0), ArrayList(), 20.0, 0.0)
        assertTrue(vacia.vacia()); assertNull(vacia.columna())
        val a = Anotacion.Anotador(listOf(vacia), "t3")
        assertTrue(a.poner(pedida(Clase.IMPORTANTE, "algo", "nota")).isFailure)
        assertTrue(a.poner(pedida(Clase.RESUMEN, "", "nota")).isFailure)
        assertTrue(Anotacion.Anotador(emptyList(), "t4").poner(pedida(Clase.IMPORTANTE, "x", "y")).isFailure)
    }

    @Test fun `el mensaje numera los renglones y se recorta`() {
        val p = hoja(TEXTO)
        val m = Anotacion.mensaje("señala lo importante", listOf(p), Anotacion.MAX_LETRAS)
        assertTrue(m.contains("=== Pagina 1 ===") && m.contains("L3: una carga repartida"))
        val corto = Anotacion.mensaje("x", listOf(p), 120)
        assertTrue(corto.contains("no cabe") && corto.length < 200)
        assertTrue(Anotacion.instrucciones(true).contains("B4:D9"))
        assertFalse(Anotacion.instrucciones(false).contains("B4:D9"))
        assertNotNull(Clase.de("glosario"))
    }
}
