package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Puntos metidos por coordenadas.
 *
 * Aquí lo que puede salir mal es callado y grave: un punto colocado donde no
 * es. Quien teclea coordenadas no las va a comprobar a ojo —para eso las
 * teclea—, así que la conversión tiene que estar bien de una vez.
 */
class TablasTest {

    private fun tabla(vararg puntos: Pair<Double, Double>) =
        TablaDeCoordenadas(id = "t1", puntos = puntos.map { PuntoDeTabla(it.first, it.second) })

    /**
     * **La Y va hacia arriba.** En la pantalla crece hacia abajo, pero quien
     * teclea coordenadas piensa en ejes cartesianos: meter (0, 10) y ver el
     * punto aparecer por debajo del origen sería, con razón, un error.
     */
    @Test
    fun `la Y sube, como en unos ejes de toda la vida`() {
        val t = tabla(0.0 to 10.0)
        val p = puntosEnEscena(t, origen = Pt(100.0, 200.0), escala = null).first()
        assertEquals(100.0, p.x, 1e-9)
        assertEquals("subir tiene que restar en pantalla", 190.0, p.y, 1e-9)
    }

    @Test
    fun `sin escala, una unidad es un píxel`() {
        val t = tabla(3.0 to -4.0)
        val p = puntosEnEscena(t, origen = Pt(0.0, 0.0), escala = null).first()
        assertEquals(3.0, p.x, 1e-9)
        assertEquals(4.0, p.y, 1e-9)
    }

    /** Con el dibujo calibrado, las unidades son las suyas. */
    @Test
    fun `con escala, las unidades son las del dibujo`() {
        // Medio metro por píxel: teclear 4 m son 8 px.
        val escala = Escala(unidadesPorPixel = 0.5)
        val t = tabla(4.0 to 0.0)
        assertEquals(8.0, puntosEnEscena(t, Pt(0.0, 0.0), escala).first().x, 1e-9)
    }

    /** Ida y vuelta: lo que se teclea es lo que se lee de vuelta. */
    @Test
    fun `de coordenadas a escena y de vuelta`() {
        val escala = Escala(unidadesPorPixel = 0.02)
        val origen = Pt(640.0, 480.0)
        val t = tabla(12.5 to -3.25)
        val enEscena = puntoEnEscena(origen, t.puntos.first(), escala)
        val devuelto = coordenadasDe(origen, enEscena, escala)
        assertEquals(12.5, devuelto.x, 1e-9)
        assertEquals(-3.25, devuelto.y, 1e-9)
    }

    /**
     * **El eje es uno solo.** Dos series con las mismas coordenadas caen en el
     * mismo sitio del papel: si no, compararlas dejaría de significar nada y el
     * color pasaría de decir «otra serie» a decir «otro mundo».
     */
    @Test
    fun `todas las series cuentan desde el mismo origen`() {
        val origen = Pt(300.0, 300.0)
        val a = tabla(5.0 to 0.0)
        val b = TablaDeCoordenadas(id = "t2", color = COLORES_DE_TABLA[1], puntos = a.puntos)
        assertEquals(
            puntosEnEscena(a, origen, null).first(),
            puntosEnEscena(b, origen, null).first()
        )
    }

    // ---- Los colores ----

    @Test
    fun `dos tablas nuevas no nacen del mismo color`() {
        val primera = colorLibreDeTabla(emptyList())
        val segunda = colorLibreDeTabla(listOf(primera))
        assertTrue(primera != segunda)
    }

    @Test
    fun `con todos los colores usados se reaprovecha en vez de fallar`() {
        assertTrue(colorLibreDeTabla(COLORES_DE_TABLA) in COLORES_DE_TABLA)
    }

    // ---- El enganche ----

    /**
     * De poco sirve teclear un punto exacto si luego hay que acertarlo a pulso
     * para trazar hasta él: los puntos de la tabla **imantan**.
     */
    @Test
    fun `los puntos de la tabla enganchan como una esquina`() {
        val t = tabla(100.0 to 0.0)
        val anclajes = anclajesDeTablas(listOf(t), Pt(0.0, 0.0), null)
        assertEquals(1, anclajes.size)
        assertEquals(TipoAnclaje.ESQUINA, anclajes.first().tipo)

        // Un dedo cerca del punto se pega a él.
        val pegado = buscarAnclaje(
            elementos = emptyList(), p = Pt(104.0, 3.0), zoom = 1.0, extra = anclajes
        )
        assertEquals(100.0, pegado!!.punto.x, 1e-9)
        assertEquals(0.0, pegado.punto.y, 1e-9)
    }

    @Test
    fun `una tabla oculta deja de enganchar`() {
        val t = tabla(100.0 to 0.0).copy(visible = false)
        assertTrue(anclajesDeTablas(listOf(t), Pt(0.0, 0.0), null).isEmpty())
    }

    @Test
    fun `lejos del punto no engancha nada`() {
        val anclajes = anclajesDeTablas(listOf(tabla(100.0 to 0.0)), Pt(0.0, 0.0), null)
        assertNull(buscarAnclaje(emptyList(), Pt(400.0, 400.0), 1.0, extra = anclajes))
    }

    // ---- El imán del eje ----

    /**
     * **Cerca del cero manda el cero.** A un paso del origen, la proyección
     * sobre la horizontal siempre cae algo más cerca que el origen mismo —es un
     * cateto contra su hipotenusa—, así que sin una regla explícita apuntar al
     * cero daba «encima del eje, un poco a la derecha».
     */
    @Test
    fun `el origen imanta`() {
        val origen = Pt(500.0, 500.0)
        val pegado = buscarAnclaje(
            elementos = emptyList(), p = Pt(504.0, 497.0), zoom = 1.0,
            extra = anclajesDelEje(origen, Pt(504.0, 497.0), radio = 14.0)
        )
        assertEquals(origen, pegado!!.punto)
        assertEquals(TipoAnclaje.EJE, pegado.tipo)
    }

    /**
     * Las rectas del eje son infinitas, así que lo que se ofrece es **la
     * proyección del dedo sobre cada una**: se conserva la coordenada por la que
     * se va y se toma del eje la otra.
     */
    @Test
    fun `pegarse a la horizontal conserva la X`() {
        val origen = Pt(500.0, 500.0)
        val dedo = Pt(900.0, 503.0)
        val pegado = buscarAnclaje(
            emptyList(), dedo, 1.0, extra = anclajesDelEje(origen, dedo, radio = 14.0)
        )
        assertEquals("la X es la del dedo", 900.0, pegado!!.punto.x, 1e-9)
        assertEquals("la Y es la del eje", 500.0, pegado.punto.y, 1e-9)
    }

    @Test
    fun `pegarse a la vertical conserva la Y`() {
        val origen = Pt(500.0, 500.0)
        val dedo = Pt(497.0, 120.0)
        val pegado = buscarAnclaje(
            emptyList(), dedo, 1.0, extra = anclajesDelEje(origen, dedo, radio = 14.0)
        )
        assertEquals(500.0, pegado!!.punto.x, 1e-9)
        assertEquals(120.0, pegado.punto.y, 1e-9)
    }

    /** Lejos de las dos rectas, el dedo va donde va. */
    @Test
    fun `fuera del eje no engancha`() {
        val origen = Pt(500.0, 500.0)
        val dedo = Pt(900.0, 120.0)
        assertNull(
            buscarAnclaje(emptyList(), dedo, 1.0, extra = anclajesDelEje(origen, dedo, 14.0))
        )
    }

    /** Se puede apagar, como cualquier otro enganche. */
    @Test
    fun `el imán del eje se puede quitar`() {
        assertTrue(anclajesDelEje(Pt(0.0, 0.0), Pt(1.0, 1.0), 14.0, activo = false).isEmpty())
        assertTrue(anclajesDelEje(null, Pt(1.0, 1.0), 14.0).isEmpty())
    }

    // ---- Lo tecleado ----

    @Test
    fun `las coordenadas se leen con coma, con punto y con signo`() {
        assertEquals(4.2, leerCoordenada("4,2")!!, 1e-9)
        assertEquals(4.2, leerCoordenada("4.2")!!, 1e-9)
        assertEquals(-3.0, leerCoordenada(" -3 ")!!, 1e-9)
        assertNull(leerCoordenada(""))
        assertNull(leerCoordenada("-"))
    }

    /** Las tablas viajan con el dibujo: se guardan y se releen. */
    @Test
    fun `las tablas se guardan con la escena`() {
        val escena = Scene(
            tablas = listOf(tabla(1.0 to 2.0)),
            origenCoordenadas = Pt(10.0, 20.0)
        )
        val vuelta = ExcalidrawJson.decodeFromString<Scene>(
            ExcalidrawJson.encodeToString(escena)
        )
        assertEquals(escena.tablas, vuelta.tablas)
        assertEquals(escena.origenCoordenadas, vuelta.origenCoordenadas)
    }

    /** Sin eje puesto todavía, no hay puntos que colocar ni a los que pegarse. */
    @Test
    fun `sin origen no hay anclajes`() {
        assertTrue(anclajesDeTablas(listOf(tabla(1.0 to 1.0)), null, null).isEmpty())
    }

    /** Y un dibujo de antes de que existieran sigue abriendo. */
    @Test
    fun `una escena sin tablas se lee igual`() {
        assertTrue(
            ExcalidrawJson.decodeFromString<Scene>("""{"elements":[]}""").tablas.isEmpty()
        )
    }
}
