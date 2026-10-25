package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.Transcriptor.PARRAFO_MINIMO_MS
import com.forge.pixpin.guardados.Transcriptor.PARRAFO_MS
import com.forge.pixpin.guardados.Transcriptor.Segmento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Una marca `[m:ss]` cada 15 s como mucho** (25-oct-2026): las pruebas de `tiempos.rs` del PC
 * (7df5532) con los mismos números, para que el teléfono saque las mismas líneas que el PC con
 * los mismos trozos.
 */
class TiemposTest {

    @Test
    fun `a los quince segundos empieza un parrafo nuevo`() {
        val segmentos = listOf(Segmento(0, "uno"), Segmento(PARRAFO_MS + 1, "dos"))
        assertEquals("[0:00] uno\n\n[0:15] dos", Transcriptor.conTiempos(segmentos))
    }

    /** Un monólogo de [n] palabras de 400 ms seguidas, una frase cada [cada] palabras (la última de cada frase lleva punto). */
    private fun monologo(n: Int, cada: Int): List<Segmento> = (0 until n).map { i ->
        val a = i * 400
        Segmento.entre(a, a + 400, if ((i + 1) % cada == 0) "fin." else "palabra")
    }

    @Test
    fun `un monologo largo sale con una marca cada quince segundos como mucho`() {
        // Cinco minutos sin un solo punto ni pausa.
        val palabras = (0 until 750).map { Segmento.entre(it * 400, it * 400 + 400, "palabra") }
        val l = Transcriptor.lineas(palabras)
        assertTrue("${l.size}", l.size >= 20)
        assertTrue(l.all { it.hastaMs - it.desdeMs <= PARRAFO_MS })
        for ((a, b) in l.zipWithNext()) assertTrue(b.desdeMs - a.desdeMs <= PARRAFO_MS)
        // No se pierde ni una palabra.
        assertEquals(750, l.sumOf { it.texto.split(' ').size })
    }

    @Test
    fun `la clase de veintisiete segundos ya no sale en un solo bloque`() {
        // Dos tandas de Whisper seguidas: 0-14 s y 14-27 s.
        val tandas = listOf(
            Segmento.entre(0, 14_000, "Hoy vemos derivadas."),
            Segmento.entre(14_000, 27_000, "Empezamos por la definicion.")
        )
        assertEquals("[0:00] Hoy vemos derivadas.\n\n[0:14] Empezamos por la definicion.", Transcriptor.conTiempos(tandas))
    }

    @Test
    fun `un audio corto de menos de quince segundos queda en una linea`() {
        // 12 s, con un punto cada 2 s.
        val l = Transcriptor.lineas(monologo(30, 5))
        assertEquals("$l", 1, l.size)
        assertEquals(0 to 12_000, l[0].desdeMs to l[0].hastaMs)
    }

    @Test
    fun `se parte tras un punto antes que a mitad de frase`() {
        // Frases de 4 s (10 palabras): el corte cae tras el punto de los 12 s, no en la palabra
        // que pasa de los 15.
        val l = Transcriptor.lineas(monologo(100, 10))
        assertTrue(l.size > 1)
        for (s in l.dropLast(1)) {
            assertTrue(s.texto, s.texto.endsWith("fin."))
            assertTrue(s.hastaMs - s.desdeMs >= PARRAFO_MINIMO_MS)
        }
        assertEquals(12_000, l[1].desdeMs)
    }

    @Test
    fun `sin puntos se prefiere un silencio y luego una coma`() {
        // Palabras de 0,5 s; una coma tras la que acaba a los 5,5 s y un silencio de 1 s tras la
        // que acaba a los 7,5 s.
        val v = (0 until 32).map { i ->
            val corrida = if (i >= 15) 1_000 else 0
            Segmento.entre(i * 500 + corrida, i * 500 + 500 + corrida, if (i == 10) "coma," else "a")
        }
        assertEquals("tras el silencio", 8_500, Transcriptor.lineas(v)[1].desdeMs)
        // Sin el silencio, tras la coma.
        val seguido = (0 until 32).map { i -> Segmento.entre(i * 500, i * 500 + 500, if (i == 15) "coma," else "a") }
        assertEquals("tras la coma", 8_000, Transcriptor.lineas(seguido)[1].desdeMs)
    }

    @Test
    fun `ninguna linea sale vacia y un trozo largo va solo`() {
        val v = listOf(
            Segmento.entre(0, 1_000, "  "),
            Segmento.entre(1_000, 30_000, "una tanda larguisima"),
            Segmento.entre(30_000, 31_000, ""),
            Segmento.entre(31_000, 32_000, "y otra")
        )
        assertEquals(
            listOf(Segmento.entre(1_000, 30_000, "una tanda larguisima"), Segmento.entre(31_000, 32_000, "y otra")),
            Transcriptor.lineas(v)
        )
        assertTrue(Transcriptor.lineas(emptyList()).isEmpty())
    }

    @Test
    fun `juntar lo ya juntado no cambia nada`() {
        val l = Transcriptor.lineas(monologo(200, 7))
        assertEquals(l, Transcriptor.lineas(l))
    }

    @Test
    fun `repartir da a cada palabra su trozo de la frase`() {
        val p = Transcriptor.repartir(10_000, 20_000, "uno dos tres cuatro")
        assertEquals(4, p.size)
        assertEquals(Segmento.entre(10_000, 11_875, "uno"), p[0])
        assertEquals(20_000, p[3].hastaMs)
        assertTrue(p.zipWithNext().all { (a, b) -> a.hastaMs == b.desdeMs })
        assertTrue(Transcriptor.repartir(0, 100, "   ").isEmpty())
        // Una frase de 40 s sin pausa sale en tres líneas.
        val larga = List(100) { "palabra" }.joinToString(" ")
        assertEquals(3, Transcriptor.lineas(Transcriptor.repartir(0, 40_000, larga)).size)
    }

    @Test
    fun `una frase que cabe en una linea va entera y una larga por palabras`() {
        assertEquals(listOf(Segmento.entre(2_000, 9_000, "hola que tal")), Transcriptor.trozosDeFrase(2_000, 9_000, " hola que tal "))
        assertTrue(Transcriptor.trozosDeFrase(0, 1_000, "  ").isEmpty())
        val larga = List(60) { "palabra" }.joinToString(" ")
        val t = Transcriptor.trozosDeFrase(0, 30_000, larga)
        assertEquals(60, t.size)
        assertEquals(2, Transcriptor.lineas(t).size)
    }

    @Test
    fun `un fin antes del principio se queda en el principio, y un turno corre los dos`() {
        assertEquals(5_000, Segmento.entre(5_000, 1_000, "x").hastaMs)
        assertEquals(Segmento.entre(61_000, 63_000, "x"), Segmento.entre(1_000, 3_000, "x").corrido(60_000))
    }

    @Test
    fun `con el tiempo de cada palabra se apuntan sueltas para partir en lineas`() {
        // `motor.rs` del PC: el mismo resultado de Vosk da las mismas palabras.
        val segmentos = ArrayList<Segmento>()
        MotorVosk.apuntar(
            """{"text":"hola que tal","result":[
                {"word":"hola","start":0.5,"end":0.9,"conf":1.0},
                {"word":"que","start":1.0,"end":1.2},
                {"word":"tal","start":1.2,"end":1.6}]}""",
            segmentos
        )
        assertEquals(
            listOf(Segmento.entre(500, 900, "hola"), Segmento.entre(1_000, 1_200, "que"), Segmento.entre(1_200, 1_600, "tal")),
            segmentos
        )
        assertEquals("[0:00] hola que tal", Transcriptor.conTiempos(segmentos))
    }

    @Test
    fun `sin tiempos de palabra, la frase entera desde la primera, y lo vacio no cuenta`() {
        val segmentos = ArrayList<Segmento>()
        MotorVosk.apuntar("""{"text":"hola que tal","result":[{"word":"hola","start":2.5},{"word":"que"}]}""", segmentos)
        MotorVosk.apuntar("""{"text":"sin palabras"}""", segmentos)
        MotorVosk.apuntar("""{"text":"  "}""", segmentos)
        MotorVosk.apuntar("no es json", segmentos)
        assertEquals(listOf(Segmento(2_500, "hola que tal"), Segmento(0, "sin palabras")), segmentos)
    }
}
