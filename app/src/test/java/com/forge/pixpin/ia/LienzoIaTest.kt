package com.forge.pixpin.ia

import com.forge.pixpin.motor.ElementType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Lo dibujado por el asistente lo lee el lienzo del teléfono**, con los mismos ids, grupos y
 * firma que el PC (pruebas de `lienzo.rs` portadas: aquí el lector es el [ExcalidrawJson] del
 * motor, que es el que abre el lienzo).
 */
class LienzoIaTest {

    private fun llamada(nombre: String, args: String) =
        Herramientas.validar(LlamadaCruda("1", nombre, args), Herramientas.catalogo()).llamada

    @Test fun `el dibujo lo lee el lienzo de verdad`() {
        val l = llamada(Herramientas.DIBUJAR, """{"elementos":[
            {"tipo":"elipse","x":40,"y":60,"ancho":150,"alto":150,"color":"rojo","relleno":"rojo"},
            {"tipo":"rectangulo","x":280,"y":60,"ancho":160,"alto":160,"color":"azul"},
            {"tipo":"linea","x":40,"y":300,"x2":470,"y2":300,"color":"verde"},
            {"tipo":"flecha","x":10,"y":10,"x2":60,"y2":40},
            {"tipo":"rombo","x":500,"y":60},
            {"tipo":"texto","x":60,"y":315,"texto":"HOLA PIXPIN 2026"}]}""")
        val json = Lienzo.elementos(l, 1000.0 to 2000.0, 7)
        val els = Lienzo.aElementos(json)
        assertEquals(listOf(ElementType.ELLIPSE, ElementType.RECTANGLE, ElementType.LINE, ElementType.ARROW, ElementType.DIAMOND, ElementType.TEXT), els.map { it.type })
        assertEquals("HOLA PIXPIN 2026", els[5].text)
        // Corrido al origen pedido, con los puntos relativos al primero.
        assertEquals(1040.0, els[0].x, 0.0)
        assertEquals(430.0, els[2].points!![1].x, 0.0)
        // Los ids y la firma del PC.
        assertEquals("ia-7-1", els[0].id)
        assertEquals(JsonPrimitive("pixpin-ia"), els[0].customData!!["origen"])
        assertEquals("#e03131", els[0].backgroundColor)
        assertEquals(els.size, els.map { it.id }.toSet().size)
    }

    @Test fun `el JSON sale igual que el del PC salvo los puntos en objeto`() {
        val l = llamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"linea","x":40,"y":300,"x2":470,"y2":300,"color":"verde"}]}""")
        val e = puntosComoElPc(Lienzo.elementos(l, 0.0 to 0.0, 255)[0])
        // Lo que escribe `lienzo::elementos` del PC para esta llamada (semilla 255 = «ff»).
        assertEquals("ia-ff-1", e["id"]!!.jsonPrimitive.content)
        assertEquals("[[0.0,0.0],[430.0,0.0]]", e["points"].toString())
        assertEquals("#2f9e44", e["strokeColor"]!!.jsonPrimitive.content)
        assertEquals(256, e["seed"]!!.jsonPrimitive.content.toInt())
        assertEquals("{\"origen\":\"pixpin-ia\"}", e["customData"].toString())
    }

    @Test fun `las tres graficas salen y se leen`() {
        for ((args, minimo) in listOf(
            """{"tipo":"barras","etiquetas":["Enero","Febrero","Marzo"],"valores":[120,95,140],"titulo":"Ventas"}""" to 3 + 3 * 3,
            """{"tipo":"lineas","etiquetas":["a","b","c","d"],"valores":[1,-2,3.5,0]}""" to 3 + 4 * 2 + 1,
            """{"tipo":"pastel","etiquetas":["si","no"],"valores":[3,1]}""" to 4
        )) {
            val json = Lienzo.elementos(llamada(Herramientas.GRAFICA, args), 0.0 to 0.0, 1)
            assertTrue("$args: ${json.size}", json.size >= minimo)
            val els = Lienzo.aElementos(json)
            assertEquals(json.size, els.size)
        }
        val barras = Lienzo.aElementos(Lienzo.elementos(llamada(Herramientas.GRAFICA, """{"tipo":"barras","etiquetas":["a","b"],"valores":[1,2]}"""), 0.0 to 0.0, 1))
        assertEquals(2, barras.count { it.type == ElementType.RECTANGLE })
        assertTrue(barras.any { it.text == "a" })
    }

    @Test fun `el ocr no dibuja`() {
        assertTrue(Lienzo.elementos(llamada(Herramientas.OCR, "{}"), 0.0 to 0.0, 1).isEmpty())
    }

    @Test fun `numeros bonitos`() {
        assertEquals("140", Lienzo.bonito(140.0))
        assertEquals("3.5", Lienzo.bonito(3.5))
        assertEquals("-2", Lienzo.bonito(-2.0))
    }

    @Test fun `las figuras nuevas las lee el lienzo, agrupadas y con rotulos`() {
        val l = llamada(Herramientas.DIBUJAR, """{"elementos":[
            {"tipo":"triangulo","caja":[280,170,240,110],"color":"rojo","relleno":"rojo","grupo":"casa"},
            {"tipo":"rectangulo","caja":[300,280,200,160],"color":"marron","grupo":"casa"},
            {"tipo":"poligono","puntos":[10,10,60,10,35,50],"color":"verde"},
            {"tipo":"curva","puntos":[0,300,100,250,200,350,300,300],"color":"azul"},
            {"tipo":"rectangulo","caja":[60,260,160,70],"texto":"Inicio","relleno":"azul"},
            {"tipo":"flecha","puntos":[220,295,320,295],"texto":"si"}]}""")
        val json = Lienzo.elementos(l, 0.0 to 0.0, 5)
        val els = Lienzo.aElementos(json)
        assertEquals(8, els.size)
        assertEquals(ElementType.REGION, els[0].type)
        assertEquals(3, els[0].points!!.size)
        assertEquals(els[0].groupIds, els[1].groupIds)
        assertFalse(els[0].groupIds.isEmpty())
        // Sin relleno: línea cerrada (vuelve al primer punto).
        assertEquals(ElementType.LINE, els[2].type)
        assertEquals(els[2].points!!.first(), els[2].points!!.last())
        // La curva: línea redondeada.
        assertNotNull(els[3].roundness)
        // El rótulo, en blanco sobre azul, agrupado con su caja.
        assertEquals("Inicio", els[5].text)
        assertEquals("#ffffff", els[5].strokeColor)
        assertEquals(els[4].groupIds, els[5].groupIds)
        assertEquals("si", els[7].text)
    }

    @Test fun `lapiz punto e instrumento son del lienzo del telefono`() {
        val l = llamada(Herramientas.DIBUJAR, """{"elementos":[
            {"tipo":"lapiz","puntos":[10,10,20,30,40,35],"material":"grafito","dureza":"6B"},
            {"tipo":"punto","x":10,"y":20,"texto":"A"},
            {"tipo":"instrumento","ejes":2,"caja":[100,100,320,320],"unidad":40,"tabla":[[1,2],[-3,1.5]]},
            {"tipo":"rectangulo","caja":[0,0,10,10],"opacidad":0.5,"rugosidad":1.6}]}""")
        val els = Lienzo.aElementos(Lienzo.elementos(l, 0.0 to 0.0, 3))
        assertEquals(ElementType.FREEDRAW, els[0].type)
        assertEquals(com.forge.pixpin.motor.MaterialDeTinta.CUADRITOS, els[0].material)
        assertEquals(com.forge.pixpin.motor.DurezaDeGrafito.B6, els[0].dureza)
        assertEquals(ElementType.PUNTO, els[1].type)
        assertEquals(ElementType.PLANO, els[2].type)
        assertEquals(40.0, els[2].unidad!!, 0.0)
        // Los dos puntos de la tabla, donde caen en el plano: (1, 2) → centro + (40, -80).
        val puntos = els.filter { it.type == ElementType.PUNTO }.drop(1)
        assertEquals(2, puntos.size)
        assertEquals(100 + 160 + 40.0, puntos[0].x, 1e-9)
        assertEquals(100 + 160 - 80.0, puntos[0].y, 1e-9)
        assertTrue(els.drop(2).take(4).all { it.groupIds == els[2].groupIds && it.groupIds.isNotEmpty() })
        // Opacidad y rugosidad como enteros (si no, el lienzo no lo abriría).
        val r = els.last()
        assertEquals(50, r.opacity); assertEquals(2, r.roughness)
    }

    @Test fun `lo nuevo va a la derecha de lo que habia`() {
        val antes = Lienzo.aElementos(Lienzo.elementos(llamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","caja":[0,0,100,50]}]}"""), 10.0 to 20.0, 1))
        assertEquals(190.0 to 20.0, Lienzo.alLado(antes))
        assertEquals(0.0 to 0.0, Lienzo.alLado(emptyList()))
    }

    @Test fun `oscuro o claro`() {
        assertTrue(Lienzo.esOscuro("#1971c2"))
        assertFalse(Lienzo.esOscuro("#f08c00"))
        assertFalse(Lienzo.esOscuro("#ffffff"))
        assertTrue(Lienzo.elementos(llamada(Herramientas.DIBUJAR, """{"elementos":[{"tipo":"rectangulo","caja":[0,0,9,9]}]}"""), 0.0 to 0.0, 1)[0].jsonObject["groupIds"].toString() == "[]")
    }
}
