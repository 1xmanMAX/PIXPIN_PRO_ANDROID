package com.forge.pixpin.ia

import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.MaterialDeTinta
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.StrokeStyle
import com.forge.pixpin.motor.absolutePoints
import com.forge.pixpin.motor.getElementBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * **Editar el lienzo desde el asistente** (PC `edicion/pruebas.rs`): cada herramienta sobre una escena
 * de verdad, y los casos que tienen que fallar bien (referencia ambigua, `#n` que no existe, cortar
 * sin cruce...) sin tocar nada.
 */
class EdicionTest {
    private val ROJO = "#e03131"
    private val AZUL = "#1971c2"
    private val NEGRO = "#1e1e1e"
    private var n = 0

    private fun raya(a: Pt, b: Pt, color: String) = Element(
        id = "r${n++}", type = ElementType.LINE, x = a.x, y = a.y, width = abs(b.x - a.x), height = abs(b.y - a.y), seed = 1,
        strokeColor = color, points = listOf(Pt(0.0, 0.0), Pt(b.x - a.x, b.y - a.y))
    )

    private fun texto(t: String, x: Double, y: Double, tam: Double) = Element(
        id = "t${n++}", type = ElementType.TEXT, x = x, y = y, width = t.length * tam * 0.5, height = tam * 1.25, seed = 1,
        text = t, fontSize = tam, strokeColor = NEGRO
    )

    private fun circulo(cx: Double, cy: Double, r: Double, color: String) = Element(
        id = "c${n++}", type = ElementType.ELLIPSE, x = cx - r, y = cy - r, width = 2 * r, height = 2 * r, seed = 1, strokeColor = color
    )

    private fun j(s: String) = Json.parseToJsonElement(s) as JsonObject

    private class Lienzo(var escena: Scene) {
        val num = Edicion.Numeracion(escena.elements)
        var ctx = Edicion.Contexto()
        fun n(id: String) = num.numero(id)
        fun e(id: String) = escena.elements.first { it.id == id }
        fun llamar(nombre: String, args: JsonObject, an: Edicion.Anfitrion = Edicion.SinImagenes): Edicion.Hecho {
            val (s, h) = Edicion.aplicar(escena, Edicion.validar(nombre, args), ctx, num, an)
            escena = s
            return h
        }
        fun falla(nombre: String, args: JsonObject, an: Edicion.Anfitrion = Edicion.SinImagenes): String {
            val antes = escena
            try { llamar(nombre, args, an); fail("$nombre $args tenía que fallar") } catch (e: Edicion.Fallo) {
                assertTrue("la escena no se toca", escena === antes)
                return e.message!!
            }
            error("")
        }
    }

    /** Una horizontal arriba y una diagonal que la cruza. */
    private fun cruce(): Triple<Lienzo, String, String> {
        val h = raya(Pt(0.0, 100.0), Pt(300.0, 100.0), ROJO)
        val d = raya(Pt(100.0, 0.0), Pt(200.0, 300.0), AZUL)
        return Triple(Lienzo(Scene(elements = listOf(h, d))), h.id, d.id)
    }

    @Test fun `la tabla valida lo obligatorio y tolera numeros como texto y listas de ids`() {
        try { Edicion.validar(Edicion.CORTAR, j("{}")); fail() } catch (e: Edicion.Fallo) { assertTrue(e.message!!.contains("que")) }
        try { Edicion.validar(Edicion.TRANSFORMAR, j("""{"que":"#1","accion":"teletransportar"}""")); fail() } catch (e: Edicion.Fallo) { assertTrue(e.message!!.contains("mover")) }
        val ok = Edicion.validar(Edicion.TRANSFORMAR, j("""{"que":[3,4],"accion":"Mover","dx":"10"}"""))
        assertEquals("#3 #4", ok.texto("que"))
        assertEquals(10.0, ok.numero("dx")!!, 0.0)
        assertEquals("mover", ok.texto("accion"))
        // Y pasan por la validación común de herramientas.
        val v = Herramientas.validar(LlamadaCruda("1", Edicion.CORTAR, """{"que":"#2"}"""), Herramientas.catalogo())
        assertEquals(Edicion.CORTAR, v.llamada.nombre)
        // Todas las filas están en el catálogo del PC, con los mismos obligatorios.
        for (f in Edicion.FILAS) {
            val h = Herramientas.delPc(f.nombre) ?: error("${f.nombre} no está en el catálogo del PC")
            val req = (h.parametros["required"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }.toSet()
            assertEquals(f.nombre, req, f.campos.filter { it.obligatorio }.map { it.nombre }.toSet())
        }
    }

    @Test fun `el resumen dice numeros tipos colores y textos en el marco`() {
        val (l, h, _) = cruce()
        l.escena = l.escena.copy(elements = l.escena.elements + texto("Total: 42", 10.0, 200.0, 20.0))
        val r = Edicion.resumen(l.escena, l.num, Edicion.Contexto(marco = Pt(-50.0, 0.0), elegidos = listOf(h)))
        assertTrue(r, r.contains("3 elementos"))
        assertTrue(r, r.contains("Elegido por el usuario: #1"))
        assertTrue(r, r.contains("#1 recta rojo (50,100)->(350,100)"))
        assertTrue(r, r.contains("texto negro") && r.contains("\"Total: 42\""))
        assertEquals("Lienzo destino: vacio.", Edicion.resumen(Scene(), Edicion.Numeracion(emptyList()), Edicion.Contexto()))
        // Con muchos, se recorta.
        val muchas = Scene(elements = (0 until Edicion.MAX_RESUMEN + 10).map { raya(Pt(0.0, it.toDouble()), Pt(10.0, it.toDouble()), NEGRO) })
        val rr = Edicion.resumen(muchas, Edicion.Numeracion(muchas.elements), Edicion.Contexto())
        assertEquals(1 + Edicion.MAX_RESUMEN + 1, rr.lines().size)
        assertTrue(rr.endsWith("(+10 mas antiguos sin listar)"))
    }

    @Test fun `referencias por numero tipo color texto y sitio`() {
        val (l, h, d) = cruce()
        val t = texto("Resultado: 12", 0.0, 400.0, 16.0)
        val titulo = texto("Mi plano", 0.0, 0.0, 32.0)
        val c = circulo(500.0, 500.0, 30.0, ROJO)
        l.escena = l.escena.copy(elements = l.escena.elements + t + titulo + c)
        fun r(q: String) = Edicion.resolver(l.escena, q, l.ctx, l.num).ids
        assertEquals(listOf(h), r("#1"))
        assertEquals(listOf(d), r("2"))
        assertEquals(listOf(c.id), r("el circulo rojo"))
        assertEquals(listOf(d), r("la recta azul"))
        assertEquals(listOf(t.id), r("el texto \"resultado\""))
        assertEquals(listOf(titulo.id), r("el titulo"))
        assertEquals(listOf(h), r("la recta de arriba"))
        assertEquals(listOf(h, d), Edicion.varios(l.escena, "las rectas", l.ctx, l.num))
        assertEquals(listOf(c.id), r("lo ultimo"))
        assertEquals(listOf(h), Edicion.resolver(l.escena, "lo ultimo", Edicion.Contexto(ultimos = listOf(h)), l.num).ids)
        assertEquals(listOf(c.id), Edicion.resolver(l.escena, "todo", l.ctx, l.num, doubleArrayOf(400.0, 400.0, 200.0, 200.0)).ids)
        // Una caja copiada del resumen y un número de orden.
        assertEquals(listOf(d), r("recta_2"))
    }

    @Test fun `lo ambiguo lo que no existe y nada elegido son errores con lo que hay`() {
        val (l, _, _) = cruce()
        val e = try { Edicion.uno(l.escena, "la recta", l.ctx, l.num); "" } catch (x: Edicion.Fallo) { x.message!! }
        assertTrue(e, e.contains("ambiguo") && e.contains("#1") && e.contains("#2"))
        assertTrue(l.falla(Edicion.CORTAR, j("""{"que":"la recta"}""")).contains("ambiguo"))
        fun error(q: String) = try { Edicion.resolver(l.escena, q, l.ctx, l.num); "" } catch (x: Edicion.Fallo) { x.message!! }
        assertTrue(error("#999").contains("no existe #999"))
        assertTrue(error("lo elegido").contains("nada elegido"))
        assertTrue(error("el triangulo verde").contains("no hay nada que encaje"))
        assertTrue(try { Edicion.resolver(Scene(), "#1", l.ctx, Edicion.Numeracion(emptyList())); "" } catch (x: Edicion.Fallo) { x.message!! }.contains("vacio"))
    }

    @Test fun `cortar la de abajo en el cruce`() {
        val (l, _, d) = cruce()
        val hecho = l.llamar(Edicion.CORTAR, j("""{"que":"#2","con":"#1","quitar":"fin"}"""))
        assertEquals(listOf(d), hecho.ids)
        val p = absolutePoints(l.e(d))
        assertEquals(100.0, p.last().y, 0.5)
        assertTrue(p.all { it.y <= 100.5 })
        // Sin «quitar»: el trozo más corto de un extremo (el de arriba).
        val (l2, _, d2) = cruce()
        l2.llamar(Edicion.CORTAR, j("""{"que":"la recta azul"}"""))
        val q = absolutePoints(l2.e(d2))
        assertEquals(100.0, q.first().y, 0.5); assertEquals(300.0, q.last().y, 0.5)
        // Sin cruce, falla sin tocar nada.
        val a = raya(Pt(0.0, 0.0), Pt(100.0, 0.0), NEGRO)
        val s = Lienzo(Scene(elements = listOf(a, raya(Pt(0.0, 50.0), Pt(100.0, 50.0), NEGRO))))
        assertTrue(s.falla(Edicion.CORTAR, j("""{"que":"#1"}""")).contains("no se cruza"))
    }

    @Test fun `extender hasta otra y sin nada delante falla`() {
        val corta = raya(Pt(0.0, 50.0), Pt(60.0, 50.0), NEGRO)
        val pared = raya(Pt(200.0, 0.0), Pt(200.0, 100.0), NEGRO)
        val l = Lienzo(Scene(elements = listOf(corta, pared)))
        l.llamar(Edicion.EXTENDER, j("""{"que":"#1","hasta":"#2"}"""))
        assertEquals(200.0, absolutePoints(l.e(corta.id)).last().x, 0.5)
        assertTrue(l.falla(Edicion.EXTENDER, j("""{"que":"#2","hasta":"#1","extremo":"inicio"}""")).contains("no topa"))
    }

    @Test fun `trazos a mano con el gesto del editor`() {
        val l = Lienzo(Scene())
        val h = l.llamar(Edicion.TRAZAR, j("""{"herramienta":"grafito","dureza":"8B","puntos":[0,0,100,30,200,0]}"""))
        val g = l.e(h.ids.single())
        assertEquals(ElementType.FREEDRAW, g.type)
        assertEquals(MaterialDeTinta.CUADRITOS, g.material)
        assertEquals("8B", g.dureza?.rotulo)
        assertTrue("muchos puntos, como una mano: ${g.points?.size}", (g.points?.size ?: 0) > 20)
        val h2 = l.llamar(Edicion.TRAZAR, j("""{"herramienta":"lapiz","puntos":[0,50,100,50],"color":"azul"}"""))
        assertEquals("azul", Edicion.nombreDeColor(l.e(h2.ids.single()).strokeColor))
        l.falla(Edicion.TRAZAR, j("""{"herramienta":"lapiz"}"""))
        // El recorrido de la mano: empieza y acaba donde se pidió.
        val m = Edicion.aMano(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), 7)
        assertEquals(0.0, m.first().x, 1e-9); assertEquals(100.0, m.last().x, 1e-9)
        assertTrue(m.drop(1).dropLast(1).all { abs(it.y) <= 0.4 })
    }

    @Test fun `resaltar y subrayar un texto`() {
        val t = texto("Resultado final", 100.0, 100.0, 24.0)
        val l = Lienzo(Scene(elements = listOf(t)))
        val h = l.llamar(Edicion.TRAZAR, j("""{"herramienta":"resaltador","sobre":"el texto \"resultado\""}"""))
        val r = l.e(h.ids.single())
        assertEquals("resaltado", Edicion.nombreDe(r))
        assertEquals("amarillo", Edicion.nombreDeColor(r.strokeColor))
        val bt = getElementBounds(t); val br = getElementBounds(r)
        assertTrue("cubre lo ancho", br.x1 <= bt.x1 + 2 && br.x2 >= bt.x2 - 2)
        assertTrue("pasa por encima del texto", br.y1 < bt.y2 && br.y2 > bt.y1)
        val h2 = l.llamar(Edicion.TRAZAR, j("""{"herramienta":"lapiz","sobre":"#1","como":"subrayar","color":"rojo"}"""))
        assertTrue(getElementBounds(l.e(h2.ids.single())).y1 > bt.y2 - 6)
    }

    private object ConFoto : Edicion.Anfitrion {
        override fun imagenNueva(escena: Scene, fuente: String): Pair<Scene, Element> {
            if (fuente == "nada") throw Edicion.Fallo("no hay ninguna imagen adjunta")
            return escena to Element(id = "foto", type = ElementType.IMAGE, x = 0.0, y = 0.0, width = 800.0, height = 400.0, seed = 1, fileId = "f77")
        }
        override fun tamanoDe(fileId: String) = 800 to 400
    }

    @Test fun `pegar la foto y recortar la mitad izquierda`() {
        val l = Lienzo(Scene())
        val h = l.llamar(Edicion.IMAGEN, j("""{"accion":"pegar","caja":[0,0,400]}"""), ConFoto)
        val id = h.ids.single()
        assertEquals(400.0, l.e(id).width, 0.0); assertEquals(200.0, l.e(id).height, 0.0)
        l.ctx = Edicion.Contexto(ultimos = h.ids)
        l.llamar(Edicion.IMAGEN, j("""{"accion":"recortar","quitar":"izquierda","cuanto":0.5}"""), ConFoto)
        val e = l.e(id)
        assertEquals(200.0, e.x, 1e-9); assertEquals(200.0, e.width, 1e-9)
        assertEquals(400.0, e.crop!!.x, 1e-9); assertEquals(400.0, e.crop!!.width, 1e-9); assertEquals(800.0, e.crop!!.naturalWidth, 1e-9)
        // Recortar dos veces mide sobre lo que se ve.
        val dos = Edicion.recortarImagen(e, doubleArrayOf(0.0, 0.5, 1.0, 0.5), null)!!
        assertEquals(200.0, dos.crop!!.y, 1e-9); assertEquals(200.0, dos.crop!!.height, 1e-9)
        // Negativos.
        l.falla(Edicion.IMAGEN, j("""{"accion":"pegar","imagen":"nada"}"""), ConFoto)
        l.escena = l.escena.copy(elements = l.escena.elements + raya(Pt(0.0, 0.0), Pt(10.0, 0.0), NEGRO))
        assertTrue(l.falla(Edicion.IMAGEN, j("""{"accion":"recortar","que":"#2","quitar":"arriba"}"""), ConFoto).contains("imagen"))
        assertTrue(Lienzo(Scene()).falla(Edicion.IMAGEN, j("""{"accion":"pegar"}""")).isNotEmpty())
    }

    @Test fun `mover escalar girar duplicar borrar y estilo`() {
        val (l, h, d) = cruce()
        l.llamar(Edicion.TRANSFORMAR, j("""{"que":"#1","accion":"mover","dx":10,"dy":5}"""))
        assertEquals(Pt(10.0, 105.0), absolutePoints(l.e(h)).first())
        val c = circulo(500.0, 500.0, 20.0, ROJO)
        l.escena = l.escena.copy(elements = l.escena.elements + c)
        l.llamar(Edicion.TRANSFORMAR, j("""{"que":"el circulo rojo","accion":"escalar","factor":2}"""))
        assertEquals(80.0, l.e(c.id).width, 1e-3); assertEquals(460.0, l.e(c.id).x, 1e-3)
        val antes = l.e(d)
        l.llamar(Edicion.TRANSFORMAR, j("""{"que":"#2","accion":"girar","grados":90}"""))
        assertNotEquals(antes.angle, l.e(d).angle)
        val dup = l.llamar(Edicion.TRANSFORMAR, j("""{"que":"#3","accion":"duplicar","dx":100,"dy":0}"""))
        assertEquals(1, dup.ids.size); assertNotEquals(c.id, dup.ids[0])
        assertEquals(l.e(c.id).x + 100, l.e(dup.ids[0]).x, 1e-9)
        assertEquals("el duplicado lleva el #4", 4, l.n(dup.ids[0]))
        l.llamar(Edicion.ESTILO, j("""{"que":"#3","color":"verde","relleno":"amarillo","trazo":"discontinuo"}"""))
        assertEquals("verde", Edicion.nombreDeColor(l.e(c.id).strokeColor))
        assertEquals(StrokeStyle.DASHED, l.e(c.id).strokeStyle)
        assertNotEquals(Element.TRANSPARENT, l.e(c.id).backgroundColor)
        l.llamar(Edicion.TRANSFORMAR, j("""{"que":"#3","accion":"borrar"}"""))
        assertTrue(l.escena.elements.none { it.id == c.id && !it.isDeleted })
        // Los #n no se mueven al borrar: el duplicado sigue siendo el #4.
        assertEquals(listOf(dup.ids[0]), Edicion.resolver(l.escena, "#4", l.ctx, l.num).ids)
        l.falla(Edicion.ESTILO, j("""{"que":"#1"}"""))
    }

    @Test fun `el tamano de letra cambia los textos y su caja`() {
        val (l, _, _) = cruce()
        val t = texto("Cemento Portland", 10.0, 10.0, 30.0)
        l.escena = l.escena.copy(elements = l.escena.elements + t)
        l.llamar(Edicion.ESTILO, j("""{"que":"#3","tam_letra":15}"""))
        assertEquals(15.0, l.e(t.id).fontSize!!, 1e-9)
        assertTrue(l.e(t.id).width < t.width * 0.7)
        l.falla(Edicion.ESTILO, j("""{"que":"#1","tam_letra":15}"""))
    }

    @Test fun `seleccionar devuelve numeros para encadenar sin cambiar el dibujo`() {
        val (l, h, d) = cruce()
        val antes = l.escena
        val r = l.llamar(Edicion.SELECCIONAR, j("""{"que":"las rectas"}"""))
        assertEquals(listOf(h, d), r.ids)
        assertTrue(r.texto, r.texto.contains("#1 recta"))
        assertTrue(l.escena === antes)
    }

    @Test fun `el resumen va cuando viene a cuento`() {
        assertTrue(Edicion.vieneACuento("resalta el titulo"))
        assertTrue(Edicion.vieneACuento("dibuja un circulo rojo"))
        assertTrue(Edicion.vieneACuento("¿que hay en el lienzo?"))
        assertFalse(Edicion.vieneACuento("apunta la tarea comprar pan"))
        assertFalse(Edicion.vieneACuento("hola"))
    }

    @Test fun `lo que falta se saca del pedido sin pisar lo puesto`() {
        var e = Edicion.ajustarAlPedido(Edicion.validar(Edicion.TRAZAR, j("""{"herramienta":"grafito","sobre":"#1"}""")), "Rodea el rectángulo con grafito 8B en azul")
        assertEquals("rodear", e.texto("como")); assertEquals("8B", e.texto("dureza")); assertEquals("azul", e.texto("color"))
        e = Edicion.ajustarAlPedido(Edicion.validar(Edicion.TRAZAR, j("""{"herramienta":"lapiz","sobre":"#1","como":"tachar"}""")), "subraya esto")
        assertEquals("tachar", e.texto("como"))
        e = Edicion.ajustarAlPedido(Edicion.validar(Edicion.TRAZAR, j("""{"herramienta":"lapiz","puntos":[0,0,1,1]}""")), "haz un trazo")
        assertNull(e.texto("como")); assertNull(e.texto("dureza")); assertNull(e.texto("color"))
        e = Edicion.ajustarAlPedido(Edicion.validar(Edicion.IMAGEN, j("""{"accion":"recortar"}""")), "pega la foto y recorta la mitad izquierda")
        assertEquals(listOf(0.0, 0.0, 0.5, 1.0), e.numeros("conservar"))
        e = Edicion.ajustarAlPedido(Edicion.validar(Edicion.IMAGEN, j("""{"accion":"recortar"}""")), "quita la parte de arriba de la foto")
        assertEquals("arriba", e.texto("quitar"))
    }

    @Test fun `la geometria nombra las rectas por los numeros del turno`() {
        val (l, _, _) = cruce()
        // Se borra la #1: la #2 sigue siendo la #2 para la geometría del mismo turno.
        l.llamar(Edicion.TRANSFORMAR, j("""{"que":"#1","accion":"borrar"}"""))
        val p = GeometriaPrecisa.hacer(j("""{"figura":"paralela","a":"#2","distancia":20}"""), l.escena.elements, Pt(0.0, 0.0), numeracion = l.num)
        assertEquals(1, p.size)
        try { GeometriaPrecisa.hacer(j("""{"figura":"paralela","a":"#1","distancia":20}"""), l.escena.elements, Pt(0.0, 0.0), numeracion = l.num); fail() } catch (e: GeometriaPrecisa.Fallo) { assertTrue(e.message!!.contains("#1")) }
    }
}
