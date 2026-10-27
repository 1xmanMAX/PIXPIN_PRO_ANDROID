package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.EstudiarClase.Fila
import com.forge.pixpin.guardados.EstudiarClase.Marcador
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Estudiar la clase** (25-oct-2026): las mismas pruebas que `crates/pixpin-proyecto/src/clase.rs`
 * del PC (`3af2647`), y el JSON de los marcadores como lo escribe el PC.
 */
class EstudiarClaseTest {

    @Test
    fun `los marcadores van y vuelven en orden y vacios quitan la clave`() {
        var r: JsonArray? = null
        r = EstudiarClase.ponerMarcador(r, 9_000, "  ojo   con\nesto ")
        r = EstudiarClase.ponerMarcador(r, 2_000, "definicion")
        assertEquals(listOf(Marcador(2_000, "definicion"), Marcador(9_000, "ojo con esto")), EstudiarClase.marcadoresDe(r))
        // Byte a byte como el PC: claves en orden alfabético.
        assertEquals("""[{"ms":2000,"texto":"definicion"},{"ms":9000,"texto":"ojo con esto"}]""", Json.encodeToString(r!!))
        // El mismo milisegundo cambia la nota, no añade otro.
        r = EstudiarClase.ponerMarcador(r, 2_000, "otra")
        assertEquals(2, EstudiarClase.marcadoresDe(r).size)
        assertEquals("otra", EstudiarClase.marcadoresDe(r)[0].texto)
        val (sinUno, quitado) = EstudiarClase.quitarMarcador(r, 2_000)
        assertTrue(quitado)
        val (sinNinguno, otroQuitado) = EstudiarClase.quitarMarcador(sinUno, 9_000)
        assertTrue(otroQuitado)
        // Caso negativo: sin marcadores la clave se va, y quitar uno que no está no cambia nada.
        assertNull(sinNinguno)
        assertFalse(EstudiarClase.quitarMarcador(sinNinguno, 1).second)
    }

    @Test
    fun `el mensaje sin marcadores no estrena la clave`() {
        val m = Mensaje(id = "v", cuando = 1, clase = Clase.VOZ)
        assertFalse("marcadores" in Json.encodeToString(m))
        val con = m.copy(marcadores = EstudiarClase.ponerMarcador(null, 5, "x"))
        assertTrue(""""marcadores":[{"ms":5,"texto":"x"}]""" in Json.encodeToString(con))
        // Y lo que llega del PC se lee y se conserva.
        val leido = Json { ignoreUnknownKeys = true }.decodeFromString<Mensaje>(Json.encodeToString(con))
        assertEquals(con.marcadores, leido.marcadores)
    }

    @Test
    fun `un marcador roto se salta y la nota se corta`() {
        val r = Json.parseToJsonElement("""[{"ms":"x"},{"ms":5},{"ms":-3,"texto":"a"},7]""") as JsonArray
        val v = EstudiarClase.marcadoresDe(r)
        assertEquals(2, v.size)
        assertEquals("un milisegundo negativo es el principio", 0L, v[0].ms)
        assertEquals("sin texto es un marcador sin nota", "", v[1].texto)
        val largo = EstudiarClase.ponerMarcador(r, 1, "a".repeat(500))
        assertEquals(EstudiarClase.NOTA_MAXIMA, EstudiarClase.marcadoresDe(largo).first { it.ms == 1L }.texto.length)
    }

    @Test
    fun `la linea y la captura del momento`() {
        val lineas = listOf(0L, 5_000L, null, 12_000L)
        assertEquals(0, EstudiarClase.lineaEn(lineas, 4_999))
        assertEquals(1, EstudiarClase.lineaEn(lineas, 5_000))
        assertEquals(1, EstudiarClase.lineaEn(lineas, 11_000))
        assertEquals(3, EstudiarClase.lineaEn(lineas, 60_000))
        // Caso negativo: sin minutos no se sabe por dónde va.
        assertNull(EstudiarClase.lineaEn(listOf(null, null), 1_000))
        val capturas = listOf(2_000L, 9_000L)
        assertEquals(0, EstudiarClase.ultimoAntes(capturas, 0))
        assertEquals(1, EstudiarClase.ultimoAntes(capturas, 9_000))
        assertNull(EstudiarClase.ultimoAntes(emptyList(), 9_000))
    }

    @Test
    fun `los marcadores entran en su minuto del texto`() {
        val lineas = listOf(0L, 10_000L, null, 20_000L)
        val marcadores = listOf(3_000L, 15_000L, 99_000L)
        val f = EstudiarClase.mezclar(lineas, marcadores)
        assertEquals(
            listOf(Fila.Linea(0), Fila.DeMarcador(0), Fila.Linea(1), Fila.Linea(2), Fila.DeMarcador(1), Fila.Linea(3), Fila.DeMarcador(2)),
            f
        )
        assertEquals(listOf(0L, 3_000, 10_000, 10_000, 15_000, 20_000, 99_000), EstudiarClase.tiemposDeFilas(f, lineas, marcadores))
        // Caso negativo: sin texto, solo los marcadores.
        assertEquals(listOf(Fila.DeMarcador(0)), EstudiarClase.mezclar(emptyList(), listOf(1L)))
    }

    @Test
    fun `de un punto al milisegundo y vuelta`() {
        val tiempos = listOf(0L, 10_000L, 20_000L)
        val altos = listOf(100f, 50f, 100f)
        assertEquals(0L, EstudiarClase.msEn(tiempos, altos, 0f))
        assertEquals(5_000L, EstudiarClase.msEn(tiempos, altos, 50f))
        assertEquals(15_000L, EstudiarClase.msEn(tiempos, altos, 125f))
        assertEquals(20_000L, EstudiarClase.msEn(tiempos, altos, 9_999f))
        assertEquals(50f, EstudiarClase.yDe(tiempos, altos, 5_000, true))
        assertEquals(0f, EstudiarClase.yDe(tiempos, altos, 5_000, false))
        assertEquals(125f, EstudiarClase.yDe(tiempos, altos, 15_000, true))
        for (y in listOf(0f, 30f, 110f, 149f)) {
            val ms = EstudiarClase.msEn(tiempos, altos, y)!!
            val y2 = EstudiarClase.yDe(tiempos, altos, ms, true)!!
            assertTrue("$y -> $ms -> $y2", Math.abs(y - y2) < 1f)
        }
        assertNull(EstudiarClase.msEn(emptyList(), emptyList(), 10f))
        assertNull(EstudiarClase.yDe(emptyList(), emptyList(), 10, true))
    }

    @Test
    fun `mover una lista mueve la otra al mismo minuto`() {
        val tTexto = (0 until 10).map { it * 6_000L }
        val aTexto = List(10) { 40f }
        val tFotos = listOf(0L, 18_000L, 36_000L, 54_000L)
        val aFotos = List(4) { 120f }
        val scroll = 3f * 40f - 100f * EstudiarClase.ANCLA
        assertEquals("el ancla lee la fila 3", 18_000L, EstudiarClase.msLeido(tTexto, aTexto, 100f, scroll))
        val otra = EstudiarClase.seguir(tTexto, aTexto, 100f, scroll, tFotos, aFotos, 200f)!!
        assertTrue("$otra", Math.abs(otra - (120f - 200f * EstudiarClase.ANCLA)) < 1f)
        val fin = EstudiarClase.tope(aTexto, 100f)
        assertEquals(EstudiarClase.tope(aFotos, 200f), EstudiarClase.seguir(tTexto, aTexto, 100f, fin, tFotos, aFotos, 200f))
        assertNull(EstudiarClase.seguir(tTexto, aTexto, 100f, 0f, emptyList(), emptyList(), 200f))
    }

    @Test
    fun `el scroll que sigue no se sale`() {
        val t = listOf(0L, 1_000L, 2_000L)
        val a = listOf(50f, 50f, 50f)
        assertEquals(0f, EstudiarClase.scrollPara(t, a, 100f, 0, false))
        assertEquals("el tope", 50f, EstudiarClase.scrollPara(t, a, 100f, 2_000, false))
        assertEquals("todo cabe", 0f, EstudiarClase.scrollPara(t, a, 400f, 2_000, false))
    }

    @Test
    fun `la barra se pega a las capturas y marcadores`() {
        assertEquals(30_000L, EstudiarClase.msDeLaBarra(50f, 100f, 60_000))
        assertEquals(0L, EstudiarClase.msDeLaBarra(-5f, 100f, 60_000))
        assertEquals(60_000L, EstudiarClase.msDeLaBarra(500f, 100f, 60_000))
        assertEquals(50f, EstudiarClase.xEnLaBarra(15_000, 200f, 60_000))
        assertEquals(30_000L, EstudiarClase.pegarASenal(51f, 100f, 60_000, listOf(30_000L), 3f))
        assertEquals(36_000L, EstudiarClase.pegarASenal(60f, 100f, 60_000, listOf(30_000L), 3f))
        assertEquals(0L, EstudiarClase.msDeLaBarra(50f, 100f, 0))
        assertEquals(0L, EstudiarClase.saltar(2_000, -5_000, 60_000))
        assertEquals(60_000L, EstudiarClase.saltar(58_000, 5_000, 60_000))
        assertEquals("sin duración no hay fin", 63_000L, EstudiarClase.saltar(58_000, 5_000, 0))
    }

    @Test
    fun `las lineas de la transcripcion y la nota de cada mensaje de la clase`() {
        val l = EstudiarClase.lineasDe("[0:05] **Hola** a todos\n\nsin minuto\n\n![audio](x.m4a)")
        assertEquals(listOf(EstudiarClase.Linea(5_000, "Hola a todos"), EstudiarClase.Linea(null, "sin minuto")), l)
        assertTrue(EstudiarClase.lineasDe(null).isEmpty())
        // Marcadores y banderitas, juntos y en orden en la barra.
        assertEquals(listOf(1_000L, 2_000L, 5_000L), EstudiarClase.senales(listOf(Marcador(5_000, ""), Marcador(1_000, "")), listOf(2_000, 1_000)))
        val nota = Mensaje(id = "v", cuando = 1, clase = Clase.VOZ, grabacion = "clase")
        val foto = Mensaje(id = "f", cuando = 2, clase = Clase.IMAGEN, enGrabacion = buildJsonObject { put("de", JsonPrimitive("v")); put("ms", JsonPrimitive(0)) })
        val suelta = Mensaje(id = "s", cuando = 3, clase = Clase.VOZ)
        val todos = listOf(nota, foto, suelta)
        assertEquals(nota, EstudiarClase.notaDe(foto, todos))
        assertEquals(nota, EstudiarClase.notaDe(nota, todos))
        // Caso negativo: una nota de voz suelta no es una clase.
        assertNull(EstudiarClase.notaDe(suelta, todos))
    }

    @Test
    fun `las miniaturas tienen el alto fijo y el ancho de su proporcion`() {
        fun m(w: Int, h: Int) = EstudiarClase.medidaDeMiniatura(w, h, 52f, 26f, 96f)
        // A×A, cuadrada; 16:9, más ancha con el mismo alto.
        assertEquals(52f to 52f, m(1000, 1000))
        val (ancho169, alto169) = m(1920, 1080)
        assertEquals(92.44f, ancho169, 0.01f)
        assertEquals(52f, alto169, 0.001f)
        // Muy ancha: topa con el máximo y baja de alto para no deformarse.
        assertEquals(96f to 32f, m(3000, 1000))
        // Muy alta (un teléfono de pie): el ancho mínimo, el alto en su tope.
        assertEquals(26f to 52f, m(1080, 2400))
        // Caso negativo: sin medidas aún, cuadrada; nunca medidas negativas.
        assertEquals(52f to 52f, m(0, 0))
        assertEquals(0f to 0f, EstudiarClase.medidaDeMiniatura(10, 10, -3f, 26f, 96f))
    }

    @Test
    fun `el reparto de la pantalla de pie tumbada y entera`() {
        // De pie con texto: la imagen casi la mitad; sin texto, todo lo que quede.
        val dePie = EstudiarClase.reparto(400f, 800f, false, textoEnVertical = true, textoEnApaisada = false, hayCapturas = true)
        assertEquals(EstudiarClase.Forma.VERTICAL, dePie.forma)
        assertTrue(dePie.conTexto && dePie.conTira)
        assertEquals(384f, dePie.altoImagen!!, 0.001f)
        val sinTexto = EstudiarClase.reparto(400f, 800f, false, textoEnVertical = false, textoEnApaisada = true, hayCapturas = true)
        assertFalse(sinTexto.conTexto)
        assertNull(sinTexto.altoImagen)
        // Un teléfono bajito: al texto le quedan sus líneas mínimas.
        val bajito = EstudiarClase.reparto(360f, 500f, false, textoEnVertical = true, textoEnApaisada = false, hayCapturas = true)
        assertEquals(218f, bajito.altoImagen!!, 0.001f)
        // Tumbada: el texto es el suyo (empieza escondido) y, si se pide, una columna al lado.
        val tumbada = EstudiarClase.reparto(800f, 400f, false, textoEnVertical = true, textoEnApaisada = false, hayCapturas = true)
        assertEquals(EstudiarClase.Forma.APAISADA, tumbada.forma)
        assertFalse(tumbada.conTexto)
        assertEquals(0f, tumbada.anchoTexto, 0f)
        val conTexto = EstudiarClase.reparto(800f, 400f, false, textoEnVertical = false, textoEnApaisada = true, hayCapturas = true)
        assertEquals(256f, conTexto.anchoTexto, 0.001f)
        assertNull(conTexto.altoImagen)
        // Caso negativo: a pantalla entera no hay ni texto ni tira, y sin capturas no hay tira.
        val entera = EstudiarClase.reparto(400f, 800f, true, textoEnVertical = true, textoEnApaisada = true, hayCapturas = true)
        assertEquals(EstudiarClase.Forma.ENTERA, entera.forma)
        assertFalse(entera.conTexto || entera.conTira)
        assertFalse(EstudiarClase.reparto(400f, 800f, false, true, false, hayCapturas = false).conTira)
    }
}
