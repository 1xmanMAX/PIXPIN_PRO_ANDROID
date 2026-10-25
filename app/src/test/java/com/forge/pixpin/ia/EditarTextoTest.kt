package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **`editar_texto`** (las pruebas de `edicion/pruebas_textos.rs` del PC que no dependen del motor
 * 2D, portadas): traducir una figura en una llamada, que lo centrado siga centrado, el rótulo
 * atado se recoloque, lo que no cabe se diga, y los errores digan cómo arreglarlo.
 */
class EditarTextoTest {

    private fun edicion(json: String) = EditarTexto.validar(Json.parseToJsonElement(json) as JsonObject)

    /** Un diagrama: dos cajas, una con su rótulo suelto encima y otra con el rótulo atado. */
    private fun diagrama(): EscenaDePrueba {
        val s = EscenaDePrueba()
        val a = s.caja(0.0, 0.0, 160.0, 60.0)
        s.anadir(a)                                   // #1
        s.anadir(s.en("Inicio", a))                  // #2
        val (b, rb) = s.atado("Fin", s.caja(260.0, 0.0, 160.0, 60.0))
        s.anadir(b)                                   // #3
        s.anadir(rb)                                  // #4
        s.anadir(s.texto("Título", 0.0, 120.0, tam = 28.0)) // #5
        return s
    }

    private fun centroX(e: Element) = e.x + e.width / 2

    @Test fun `traducir una figura entera es una llamada y lo centrado sigue centrado`() {
        val s = diagrama()
        val antes = s.l[1]
        val h = EditarTexto.hacer(s.l, edicion("""{"cambios":[{"ref":"#1","texto":"开始"},{"ref":"#4","texto":"结束"},{"ref":"#5","texto":"Title"}]}""")).getOrThrow()
        assertTrue(h.texto, h.texto.startsWith("hecho: 3 textos cambiados: #2 #4 #5"))
        val nuevo = h.elementos[1]
        assertEquals("开始", nuevo.text)
        // Nombrar la caja (#1) cambia el texto suelto que tiene encima (#2), centrado donde estaba.
        assertTrue(abs(centroX(nuevo) - centroX(antes)) < 0.01)
        assertTrue(nuevo.width < antes.width)
        // El rótulo atado (#4) se recoloca en el centro de su caja.
        val rb = h.elementos[3]
        assertTrue(abs(centroX(rb) - (260.0 + 80.0)) < 0.01)
        assertEquals("结束", rb.text)
        // Lo que no cambia, igual; las cajas no se tocan.
        assertEquals(s.l[0], h.elementos[0])
        assertEquals(setOf(s.l[1].id, s.l[3].id, s.l[4].id), h.cambiados.toSet())
        // La sola vuelta al mismo texto no cambia nada.
        val otra = EditarTexto.hacer(h.elementos, edicion("""{"que":"#2","texto":"开始"}""")).getOrThrow()
        assertTrue(otra.texto, otra.texto.contains("sin cambio (ya era asi): #2") && otra.cambiados.isEmpty())
    }

    @Test fun `lo que no cabe se dice y con ajustar se arregla`() {
        val s = diagrama()
        val largo = "Inicio del proceso de compra con todos los pasos"
        val sin = EditarTexto.hacer(s.l, edicion("""{"cambios":[{"ref":"#2","texto":"$largo"}]}""")).getOrThrow()
        assertTrue(sin.texto, sin.texto.contains("no caben (repite con «ajustar»: «auto»") && sin.texto.contains("#2 no cabe en #1"))
        val auto = EditarTexto.hacer(s.l, edicion("""{"cambios":[{"ref":"#2","texto":"$largo"}],"ajustar":"auto"}""")).getOrThrow()
        val t = auto.elementos[1]
        assertTrue(auto.texto, auto.texto.contains("letra encogida: #2"))
        assertTrue(t.text!!.contains('\n'))
        assertTrue("cabe en su caja", Lint.noCabe(auto.elementos, t) == null)
        // Ajustar todo sin cambiar lo que dice.
        val roto = EditarTexto.hacer(s.l, edicion("""{"cambios":[{"ref":"#4","texto":"$largo"}]}""")).getOrThrow()
        val ajustado = EditarTexto.hacer(roto.elementos, edicion("""{"ajustar":"encoger"}""")).getOrThrow()
        assertTrue(ajustado.texto, ajustado.texto.contains("letra encogida"))
        assertEquals(largo, ajustado.elementos[3].text)
    }

    @Test fun `listar da cada texto con su id en orden de lectura`() {
        val s = diagrama()
        val h = EditarTexto.hacer(s.l, edicion("{}")).getOrThrow()
        val lineas = h.texto.lines()
        assertEquals("3 textos (de arriba abajo):", lineas[0])
        assertTrue(h.texto, lineas[1].startsWith("#2 \"Inicio\""))
        assertTrue(h.texto, lineas.any { it.startsWith("#4 \"Fin\"") && it.endsWith("(rotulo de #3)") })
        assertTrue(lineas[3].startsWith("#5 \"Título\"") && lineas[3].contains("letra 28"))
        assertTrue(h.cambiados.isEmpty())
    }

    @Test fun `los errores dicen como arreglarlo`() {
        val s = diagrama()
        val e = EditarTexto.hacer(s.l, edicion("""{"cambios":[{"ref":"#99","texto":"x"}]}""")).exceptionOrNull()!!.message!!
        assertTrue(e, e.startsWith("no se cambio nada:\n#99: no existe #99; textos con id parecido:") && e.contains("(accion «listar» da todos)"))
        // Una referencia que encaja con varios textos da la lista para elegir por su #id.
        val v = EditarTexto.hacer(s.l, edicion("""{"que":"los textos","texto":"x"}""")).exceptionOrNull()!!.message!!
        assertTrue(v, v.contains("encaja con 3 textos; di cual por su #id"))
        // «El título» es el de letra más grande; por su texto citado también vale.
        val t = EditarTexto.hacer(s.l, edicion("""{"que":"el titulo","texto":"Portada"}""")).getOrThrow()
        assertEquals("Portada", t.elementos[4].text)
        val c = EditarTexto.hacer(s.l, edicion("""{"que":"el texto \"Fin\"","texto":"End"}""")).getOrThrow()
        assertEquals("End", c.elementos[3].text)
        // Bloqueado: no se toca.
        s.l[4] = s.l[4].copy(locked = true)
        val b = EditarTexto.hacer(s.l, edicion("""{"cambios":[{"ref":"#5","texto":"x"},{"ref":"#4","texto":"y"}]}""")).getOrThrow()
        assertTrue(b.texto, b.texto.contains("#5 esta bloqueado"))
    }

    @Test fun `los cambios llegan como los mande el modelo`() {
        assertEquals(listOf("#3" to "hola", "#5" to "adiós"), edicion("""{"cambios":{"#3":"hola","5":"adiós"}}""").cambios)
        assertEquals(listOf("#7" to "a"), edicion("""{"cambios":"[{\"id\":7,\"text\":\"a\"}]"}""").cambios)
        val falta = runCatching { edicion("""{"cambios":[{"ref":"#3"}]}""") }.exceptionOrNull()!!.message!!
        assertTrue(falta, falta.startsWith("al cambio 1 (#3) le falta «texto»"))
        assertTrue(runCatching { edicion("""{"cambios":[]}""") }.exceptionOrNull()!!.message!!.contains("«cambios» esta vacio"))
        assertEquals("#12 #13", edicion("""{"que":[12,13]}""").que)
        assertEquals("auto", edicion("""{"ajustar":"Auto"}""").ajustar)
    }

    @Test fun `el esquema es el del PC y va en el catalogo donde lo pone el PC`() {
        val h = EditarTexto.herramienta()
        assertEquals("editar_texto", h.nombre)
        assertEquals(0, h.parametros["required"]!!.jsonArray.size)
        val p = h.parametros["properties"]!!.jsonObject
        assertEquals(listOf("accion", "ajustar", "ancho_max", "cambios", "desde", "que", "texto"), p.keys.toList())
        assertEquals(300, p["cambios"]!!.jsonObject["maxItems"]!!.jsonPrimitive.int)
        assertEquals(listOf("no", "encoger", "partir", "auto"), p["ajustar"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        // El ejemplo de la descripción vale.
        val ejemplo = h.descripcion.substringAfter("Ejemplo: ")
        assertEquals(2, edicion(ejemplo).cambios!!.size)
        val nombres = Herramientas.catalogo().map { it.nombre }
        assertTrue(nombres.indexOf("editar_texto") > nombres.indexOf(Herramientas.BUSCAR))
        assertTrue(nombres.indexOf("editar_texto") < nombres.indexOf("abrir_chat"))
        // Y la valida el catálogo.
        val v = Herramientas.validar(LlamadaCruda("1", "editar_texto", """{"cambios":[{"ref":"#3","texto":"Hola"}]}"""), Herramientas.catalogo())
        assertTrue(v.llamada is Llamada.EditarTextos)
    }

    @Test fun `el resumen del lienzo trae los ids, los rotulos y los textos`() {
        val s = diagrama()
        val r = LienzoDestino.resumen(s.l)
        assertTrue(r, r.startsWith("Lienzo destino: 5 elementos (coordenadas como en dibujar_en_lienzo; usa sus #id para editarlos)."))
        assertTrue(r, r.contains("#1 rectangulo negro [0,0,160,60]"))
        assertTrue(r, r.contains("#4 texto negro") && r.contains("\"Fin\" (rotulo de #3)"))
        assertTrue(r, r.contains("#3 rectangulo negro [260,0,160,60] (rotulo #4)"))
        assertEquals("Lienzo destino: vacio.", LienzoDestino.resumen(emptyList()))
        // Solo va pegado cuando el pedido es del lienzo.
        assertTrue(LienzoDestino.vieneACuento("Traduce esta figura al inglés"))
        assertTrue(LienzoDestino.vieneACuento("dibuja una casa"))
        assertTrue(!LienzoDestino.vieneACuento("¿qué hora es?"))
    }
}
