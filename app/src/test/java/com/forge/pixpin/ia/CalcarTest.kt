package com.forge.pixpin.ia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * **Calcar da lo mismo que el PC** (`calcar.rs` + `calcar/trazado.rs`, con lo de «sin escaleras»):
 * 11 imágenes (círculo y cuadrado, una lámina con rayas finas de 1-2 px —el PC del 15-oct las saca a
 * toda resolución por su línea central—, una casa con degradado de antialias y fondo de color, un anillo
 * con agujero; con contornos, zona, detalle bajo/alto y en negro) calcadas por el PC de verdad
 * (main 841c32f) y aquí: los mismos trazos, en el mismo orden, con los mismos colores y puntos.
 */
class CalcarTest {
    private val casos = Json.parseToJsonElement(javaClass.getResource("/ia/calcar/casos.json")!!.readText()).jsonArray.map { it.jsonObject }
    private val delPc = Json.parseToJsonElement(javaClass.getResource("/ia/calcar/del-pc.json")!!.readText()).jsonArray.map { it.jsonObject }

    private fun calcar(c: JsonObject): Calcar.Calcado {
        val rgba = GZIPInputStream(javaClass.getResourceAsStream("/ia/calcar/" + c["archivo"]!!.jsonPrimitive.content)!!).readBytes()
        val p = Calcar.Parametros(
            detalle = when (c["detalle"]?.jsonPrimitive?.content) { "bajo" -> Calcar.Detalle.BAJO; "alto" -> Calcar.Detalle.ALTO; else -> Calcar.Detalle.MEDIO },
            colores = c["colores"]?.jsonPrimitive?.booleanOrNull ?: true,
            soloContornos = c["solo_contornos"]?.jsonPrimitive?.booleanOrNull ?: false,
            zona = c["zona"]?.jsonArray?.map { it.jsonPrimitive.double }?.toDoubleArray()
        )
        return Calcar.calcar(rgba, c["ancho"]!!.jsonPrimitive.int, c["alto"]!!.jsonPrimitive.int, p)
    }

    private fun puntos(t: Calcar.Trazo) = when (t) {
        is Calcar.Trazo.Mancha -> t.contorno
        is Calcar.Trazo.Linea -> t.puntos
        is Calcar.Trazo.Fondo -> emptyList()
        is Calcar.Trazo.Raya -> t.puntos
    }

    @Test fun `las mismas imagenes se calcan igual que en el PC`() {
        for ((c, pc) in casos.zip(delPc)) {
            val nombre = c["nombre"]!!.jsonPrimitive.content
            val r = calcar(c)
            assertEquals(nombre, pc["escala"]!!.jsonPrimitive.double, r.escala, 1e-9)
            val esperados = pc["trazos"]!!.jsonArray.map { it.jsonObject }
            assertEquals(nombre, esperados.size, r.trazos.size)
            for ((i, par) in r.trazos.zip(esperados).withIndex()) {
                val (t, e) = par
                val tipo = when (t) { is Calcar.Trazo.Mancha -> "mancha"; is Calcar.Trazo.Linea -> "linea"; is Calcar.Trazo.Fondo -> "fondo"; is Calcar.Trazo.Raya -> "raya" }
                assertEquals("$nombre #$i", e["tipo"]!!.jsonPrimitive.content, tipo)
                val color = when (t) { is Calcar.Trazo.Mancha -> t.color; is Calcar.Trazo.Linea -> t.color; is Calcar.Trazo.Fondo -> t.color; is Calcar.Trazo.Raya -> t.color }
                assertEquals("$nombre #$i", e["color"]!!.jsonPrimitive.content, color)
                if (t is Calcar.Trazo.Linea) assertEquals("$nombre #$i", e["cerrada"]!!.jsonPrimitive.boolean, t.cerrada)
                if (t is Calcar.Trazo.Raya) assertEquals("$nombre #$i grosor", e["grosor"]!!.jsonPrimitive.double, t.grosor, 1e-6)
                if (t is Calcar.Trazo.Mancha) assertEquals("$nombre #$i huecos", e["huecos"]!!.jsonArray.size, t.huecos.size)
                val ep = e["puntos"]?.jsonArray?.map { q -> q.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } }.orEmpty()
                val mp = puntos(t)
                assertEquals("$nombre #$i puntos", ep.size, mp.size)
                for ((a, b) in mp.zip(ep)) assertTrue("$nombre #$i: $a / $b", hypot(a.x - b.first, a.y - b.second) < 1e-3)
            }
        }
    }

    @Test fun `el circulo y el cuadrado salen fieles`() {
        val c = calcar(casos.first())
        val manchas = c.trazos.filterIsInstance<Calcar.Trazo.Mancha>()
        assertEquals(2, manchas.size)
        fun aImagen(p: Trazado.P) = Trazado.P((p.x - c.desplazamiento.first) / c.escala, (p.y - c.desplazamiento.second) / c.escala)
        val rojo = manchas.first { it.color.startsWith("#d") || it.color.startsWith("#e") }
        val error = rojo.contorno.map { aImagen(it) }.map { abs(hypot(it.x - 110.0, it.y - 150.0) - 70.0) }.average()
        assertTrue("error del círculo $error", error < 1.5)
        val azul = manchas.first { it !== rojo }
        assertTrue(azul.contorno.size <= 5)
    }

    @Test fun `las rayas finas no se pierden y la mancha sigue siendo mancha`() {
        val c = calcar(casos.first { it["nombre"]!!.jsonPrimitive.content == "rayas" })
        fun aImagen(p: Trazado.P) = Trazado.P((p.x - c.desplazamiento.first) / c.escala, (p.y - c.desplazamiento.second) / c.escala)
        val rayas = c.trazos.filterIsInstance<Calcar.Trazo.Raya>()
        assertTrue(rayas.any { r -> val a = aImagen(r.puntos.first()); val b = aImagen(r.puntos.last()); listOf(a, b).any { hypot(it.x - 40.0, it.y - 60.0) < 4 } && listOf(a, b).any { hypot(it.x - 860.0, it.y - 120.0) < 4 } })
        assertTrue(rayas.flatMap { it.puntos }.count { abs(hypot(aImagen(it).x - 450.0, aImagen(it).y - 360.0) - 160.0) < 2.5 } >= 20)
        assertTrue(c.trazos.any { it is Calcar.Trazo.Mancha && it.color.startsWith("#d") })
        // Al lienzo, con su grosor.
        val e = Lienzo.elementosDeCalcado(c, 0.0 to 0.0, 7)
        assertTrue(e.any { it["type"]!!.jsonPrimitive.content == "line" && it["strokeWidth"]!!.jsonPrimitive.double == 1.5 })
    }

    @Test fun `el texto leido se tapa y va como texto en su sitio`() {
        // Una «palabra» roja de 60x20 en (100, 50) sobre papel blanco, y un cuadro azul aparte.
        val w = 400; val h = 300
        val rgba = ByteArray(w * h * 4) { -1 }
        fun pintar(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
            for (y in y0 until y1) for (x in x0 until x1) { val i = (y * w + x) * 4; rgba[i] = r.toByte(); rgba[i + 1] = g.toByte(); rgba[i + 2] = b.toByte() }
        }
        for (k in 0 until 6) pintar(100 + k * 10, 50, 104 + k * 10, 70, 200, 20, 20)
        pintar(250, 150, 350, 250, 20, 60, 200)
        val leidas = listOf(Calcar.Palabra("Hola", 100.0, 50.0, 60.0, 20.0), Calcar.Palabra("·", 10.0, 10.0, 4.0, 4.0), Calcar.Palabra("fuera", 300.0, 5.0, 40.0, 10.0))
        val palabras = Calcar.palabrasQueCuentan(leidas, w, h, doubleArrayOf(0.0, 0.0, 0.7, 1.0)).map { it.copy(color = Calcar.colorDeTinta(rgba, w, h, it)) }
        assertEquals(listOf("Hola"), palabras.map { it.texto })
        assertEquals(listOf(200, 20, 20), palabras.single().color!!.toList())
        Calcar.taparTexto(rgba, w, h, palabras)
        val c = Calcar.calcar(rgba, w, h, Calcar.Parametros())
        // Lo rojo ya no se calca como manchas; lo azul sí.
        assertTrue(c.trazos.none { it is Calcar.Trazo.Mancha && it.color.startsWith("#c") })
        assertTrue(c.trazos.any { it is Calcar.Trazo.Mancha })
        val t = Lienzo.textosDelCalco(c, palabras, 0.0 to 0.0, 5).single()
        assertEquals("Hola", t["text"]!!.jsonPrimitive.content)
        assertEquals("#c81414", t["strokeColor"]!!.jsonPrimitive.content)
        assertEquals(100 * c.escala + c.desplazamiento.first, t["x"]!!.jsonPrimitive.double, 1e-6)
    }

    @Test fun `lo que no se pidio se corrige con el pedido`() {
        val p = Calcar.Parametros(soloContornos = true, colores = false)
        assertEquals(Calcar.Parametros(), Calcar.ajustarAlPedido(p, "Dibuja este perrito de la imagen adjunta"))
        assertEquals(p, Calcar.ajustarAlPedido(p, "calca solo el contorno en blanco y negro"))
        assertTrue(Calcar.calcar(ByteArray(0), 0, 0, Calcar.Parametros()).trazos.isEmpty())
    }
}
