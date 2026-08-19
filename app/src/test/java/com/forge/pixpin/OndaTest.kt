package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La onda de una nota de voz, que es lo que hace que veinte notas no sean veinte rayas
 * iguales.
 *
 * Lo que se comprueba aquí es lo que la vuelve inútil: que se aplane —porque se normalice
 * contra el máximo teórico y no contra el pico de la nota—, que se rompa con las notas
 * cortas, que deje huecos en los silencios, o que se salga de su franja y pise el texto de
 * al lado.
 */
class OndaTest {

    // ---- El submuestreo --------------------------------------------------

    /**
     * Sin picos no hay onda, y tampoco excepción.
     *
     * Todas las notas grabadas antes de que esto existiera están así, y una nota vieja no
     * puede tirar la lista de guardados al pintarse.
     */
    @Test
    fun `sin picos no hay barras`() {
        assertTrue(aBarras(emptyList()).isEmpty())
        assertTrue(aBarras(emptyList(), 10).isEmpty())
        assertTrue(aBarras(listOf(1, 2, 3), 0).isEmpty())
    }

    /**
     * **Menos picos que barras: se estira.**
     *
     * Una nota de un segundo son veinte picos para cincuenta barras. Devolver veinte barras
     * dejaría la fila a medias justo en las notas más habituales.
     */
    @Test
    fun `con menos picos que barras se estira`() {
        val barras = aBarras(listOf(0, 1000), 6)
        assertEquals(6, barras.size)
        assertEquals(listOf(SUELO_DE_BARRA, SUELO_DE_BARRA, SUELO_DE_BARRA), barras.take(3))
        assertEquals(listOf(1f, 1f, 1f), barras.drop(3))
    }

    /** Y una nota de un solo pico tampoco falla: sale la fila entera igualada. */
    @Test
    fun `un unico pico llena la fila`() {
        val barras = aBarras(listOf(500), BARRAS_DE_LA_ONDA)
        assertEquals(BARRAS_DE_LA_ONDA, barras.size)
        assertTrue(barras.all { it == 1f })
    }

    /**
     * **Muchos más picos que barras: se promedia por tramos.**
     *
     * Coger uno de cada n se salta los golpes de voz y devuelve una onda más plana de lo que
     * sonó, que es la forma silenciosa de que la onda no diga nada.
     */
    @Test
    fun `con muchos mas picos que barras se promedia por tramos`() {
        val picos = List(100) { 0 } + List(100) { 1000 }
        val barras = aBarras(picos, 10)
        assertEquals(10, barras.size)
        assertTrue("la primera mitad es silencio", barras.take(5).all { it == SUELO_DE_BARRA })
        assertTrue("la segunda mitad es voz", barras.drop(5).all { it == 1f })
    }

    /** El promedio es promedio: un tramo con la mitad de sus picos altos queda a la mitad. */
    @Test
    fun `el tramo mezclado queda a media altura`() {
        val picos = List(100) { if (it % 2 == 0) 0 else 1000 }
        val barras = aBarras(picos, 2)
        assertEquals(2, barras.size)
        assertTrue(barras.all { it > 0.4f && it < 0.6f })
    }

    /** Todo igual de fuerte es una onda plana **arriba**, no abajo. */
    @Test
    fun `todos los picos iguales dan todas las barras iguales`() {
        val barras = aBarras(List(200) { 4321 }, BARRAS_DE_LA_ONDA)
        assertEquals(BARRAS_DE_LA_ONDA, barras.size)
        assertTrue(barras.all { it == 1f })
    }

    // ---- La normalización relativa ---------------------------------------

    /**
     * **Una nota grabada bajito se ve igual que una grabada fuerte.**
     *
     * Es la diferencia entre una onda que dice algo y una raya plana: dividiendo por 32767,
     * una nota dictada con el móvil en la mesa —que no pasa de 3000— quedaría pegada al
     * suelo y no se distinguiría de un silencio.
     */
    @Test
    fun `una nota bajita se ve igual que una fuerte`() {
        val fuerte = listOf(0, 10_000, 30_000, 5_000, 20_000)
        val bajita = fuerte.map { it / 10 }
        assertEquals(aBarras(fuerte, 5), aBarras(bajita, 5))
        // Y las dos llegan arriba en su pico: la barra más alta siempre toca el techo.
        assertEquals(1f, aBarras(bajita, 5).maxOrNull())
    }

    /** Un grito en medio de un susurro deja el resto bajo, pero visible. */
    @Test
    fun `un pico enorme manda sobre el resto`() {
        val barras = aBarras(listOf(100, 100, 32_000, 100, 100), 5)
        assertEquals(1f, barras[2])
        assertTrue("el susurro no puede quedar igual de alto", barras[0] < 0.1f)
        assertTrue("pero tiene que verse", barras[0] >= SUELO_DE_BARRA)
    }

    // ---- Los límites -----------------------------------------------------

    /**
     * Ninguna barra por debajo del suelo ni por encima de 1.
     *
     * Por abajo, una barra de altura 0 no se dibuja y el silencio deja huecos: la onda
     * parece rota o a medio cargar. Por arriba, una barra mayor que 1 se sale de su franja
     * y pisa lo que tiene al lado.
     */
    @Test
    fun `ninguna barra se sale de la franja`() {
        val casos = listOf(
            List(300) { 0 },
            List(300) { it * 100 },
            listOf(0, 32_767, 0, 32_767),
            listOf(-5, 0, 7),
            List(7) { 32_767 }
        )
        for (picos in casos) {
            for (b in aBarras(picos, BARRAS_DE_LA_ONDA)) {
                assertTrue("$b se sale por abajo", b >= SUELO_DE_BARRA)
                assertTrue("$b se sale por arriba", b <= 1f)
            }
        }
    }

    /** El silencio absoluto no divide entre cero: sale una fila de barras al ras. */
    @Test
    fun `el silencio absoluto no revienta`() {
        val barras = aBarras(List(100) { 0 }, BARRAS_DE_LA_ONDA)
        assertEquals(BARRAS_DE_LA_ONDA, barras.size)
        assertTrue(barras.all { it == SUELO_DE_BARRA })
    }

    /** El alto de dibujo se queda dentro de la franja pase lo que pase. */
    @Test
    fun `el alto de barra respeta la franja`() {
        assertEquals(14f, altoDeBarra(1f, 14), 0.001f)
        assertEquals(14f, altoDeBarra(9f, 14), 0.001f)
        assertEquals(SUELO_DE_BARRA * 14, altoDeBarra(0f, 14), 0.001f)
        assertEquals(SUELO_DE_BARRA * 14, altoDeBarra(-3f, 14), 0.001f)
        assertEquals(7f, altoDeBarra(0.5f, 14), 0.001f)
    }

    // ---- El acumulador de la grabación -----------------------------------

    /** Junta de dos en dos promediando, y el que sobra se queda: no se pierde el final. */
    @Test
    fun `a mitad promedia por parejas`() {
        assertEquals(listOf(15, 35, 50), aMitad(listOf(10, 20, 30, 40, 50)))
        assertEquals(listOf(7), aMitad(listOf(7)))
        assertTrue(aMitad(emptyList()).isEmpty())
    }

    /**
     * **Una nota larga no engorda su línea del JSONL.**
     *
     * Un pico cada 50 ms son 1200 por minuto, y cada mensaje es una línea de JSON. Sin tope,
     * una nota de diez minutos escribiría decenas de kilobytes para dibujar 50 barras.
     */
    @Test
    fun `el acumulador no crece sin fin`() {
        val p = Picos(tope = 8)
        repeat(5_000) { p.anota(1_000) }
        assertTrue("se ha ido de tamaño: ${p.lista().size}", p.lista().size <= 8)
        assertTrue(p.lista().isNotEmpty())
    }

    /**
     * Y al acotar **conserva la nota entera**, no solo el principio.
     *
     * Cortar por el tope dejaría la onda de una nota larga contando únicamente sus primeros
     * segundos, con el final siempre plano — un dibujo que miente sobre lo que se oye.
     */
    @Test
    fun `el acumulador conserva la forma de toda la nota`() {
        val p = Picos(tope = 16)
        repeat(500) { p.anota(0) }
        repeat(500) { p.anota(20_000) }
        val barras = aBarras(p.lista(), 4)
        assertEquals(4, barras.size)
        assertTrue("el principio era silencio", barras.first() < 0.3f)
        assertTrue("el final era voz", barras.last() > 0.7f)
    }

    // ---- Lo guardado -----------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * **Una línea vieja del JSONL, sin picos, se sigue leyendo.**
     *
     * Es lo que hay en el archivo de cualquiera que ya tenga notas guardadas. Si el campo
     * nuevo fuera obligatorio, esa línea dejaría de decodificarse y `leer()` la saltaría en
     * silencio: la nota desaparecería de la lista sin que nadie la borrara.
     */
    @Test
    fun `un mensaje viejo sin picos se lee con la lista vacia`() {
        val vieja = """{"id":"v1","cuando":1000,"clase":"VOZ","ruta":"/x/voz.m4a","duracionMs":7400}"""
        val m = json.decodeFromString<Mensaje>(vieja)
        assertEquals("v1", m.id)
        assertEquals(7400, m.duracionMs)
        assertTrue("sin onda, pero la nota sigue ahí", m.picos.isEmpty())
        assertTrue(aBarras(m.picos).isEmpty())
    }

    /** Y una nota nueva va y vuelve del JSONL con su onda intacta. */
    @Test
    fun `los picos sobreviven al viaje por el jsonl`() {
        val m = Mensaje(
            id = "v2", cuando = 2000, clase = Clase.VOZ, ruta = "/x/voz.m4a",
            duracionMs = 3000, picos = listOf(0, 120, 9_000, 300)
        )
        val vuelta = json.decodeFromString<Mensaje>(json.encodeToString(Mensaje.serializer(), m))
        assertEquals(m, vuelta)
        assertEquals(aBarras(m.picos, 8), aBarras(vuelta.picos, 8))
    }
}
