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
 * 9 imágenes (círculo y cuadrado, una casa con degradado de antialias y fondo de color, un anillo
 * con agujero; con contornos, zona, detalle bajo/alto y en negro) calcadas por el PC de verdad
 * (main 882d465) y aquí: los mismos trazos, en el mismo orden, con los mismos colores y puntos.
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
                val tipo = when (t) { is Calcar.Trazo.Mancha -> "mancha"; is Calcar.Trazo.Linea -> "linea"; is Calcar.Trazo.Fondo -> "fondo" }
                assertEquals("$nombre #$i", e["tipo"]!!.jsonPrimitive.content, tipo)
                val color = when (t) { is Calcar.Trazo.Mancha -> t.color; is Calcar.Trazo.Linea -> t.color; is Calcar.Trazo.Fondo -> t.color }
                assertEquals("$nombre #$i", e["color"]!!.jsonPrimitive.content, color)
                if (t is Calcar.Trazo.Linea) assertEquals("$nombre #$i", e["cerrada"]!!.jsonPrimitive.boolean, t.cerrada)
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

    @Test fun `lo que no se pidio se corrige con el pedido`() {
        val p = Calcar.Parametros(soloContornos = true, colores = false)
        assertEquals(Calcar.Parametros(), Calcar.ajustarAlPedido(p, "Dibuja este perrito de la imagen adjunta"))
        assertEquals(p, Calcar.ajustarAlPedido(p, "calca solo el contorno en blanco y negro"))
        assertTrue(Calcar.calcar(ByteArray(0), 0, 0, Calcar.Parametros()).trazos.isEmpty())
    }
}
