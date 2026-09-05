package com.forge.pixpin.croquis3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * **La malla del suelo: la que mide.**
 *
 * Lo que se comprueba aquí no es que se vea bonita —eso se mira en la pantalla— sino lo
 * único que puede estar mal de verdad en una referencia: **que no sea una referencia**. Una
 * malla cuyo cuadro se dobla y se parte con el aumento se ve estupenda en todos los aumentos
 * y no dice cuánto mide nada; y una que además se plantara debajo de la mirada tampoco
 * diría dónde está uno. Así que se comprueba que el cuadro vale siempre lo mismo, que
 * cuando el aumento no deja solo cambia de diez en diez, y que aun así ni se cierra en una
 * trama gris ni desaparece en ningún aumento que la cámara permita.
 */
class MallaDelSueloTest {

    /** Una pantalla de teléfono de las de ahora, que es de donde sale cuánto hay que cubrir. */
    private val ancho = 1080.0
    private val alto = 2160.0

    private fun alcance(zoom: Double) =
        hypot(ancho, alto) / 2.0 / zoom * MARGEN_DEL_SUELO

    private fun pisos(zoom: Double) = pisosDeLaMalla(zoom, alcance(zoom))

    // ---- El cuadro mide, y mide siempre lo mismo ----

    /**
     * **A la escala de trabajo, el cuadro es el cuadro.**
     *
     * Es la prueba del enunciado entero: si esto falla, contar cuadros no mide.
     */
    @Test
    fun `el cuadro base mide lo mismo en toda la banda de trabajo`() {
        for (zoom in listOf(0.7, 1.0, 1.5, 2.0, 3.0, 5.0)) {
            val base = pisos(zoom).firstOrNull { it.decena == 0 }
            assertTrue("a $zoom no está el cuadro base", base != null)
            assertEquals(LADO_DEL_CUADRO, base!!.lado, 1e-9)
        }
    }

    /**
     * **Y en cualquier otro aumento, el cuadro que se vea es una potencia de diez de ese.**
     *
     * Aquí está lo que separa esta malla de la de antes, que iba doblando el paso: un cuadro
     * de ochenta o de ciento sesenta unidades no se puede contar contra nada. Uno de cuatro,
     * de cuarenta o de cuatrocientas sí: se cuenta lo que se ve y se sabe por cuánto va.
     */
    @Test
    fun `todos los cuadros son decenas del cuadro base`() {
        var zoom = Camara3D.ZOOM_MINIMO
        while (zoom <= Camara3D.ZOOM_MAXIMO) {
            for (piso in pisos(zoom)) {
                val veces = piso.lado / LADO_DEL_CUADRO
                val decenas = log10(veces)
                assertEquals(
                    "a $zoom sale un cuadro de ${piso.lado}, que no es una decena",
                    decenas.roundToInt().toDouble(), decenas, 1e-9
                )
                assertEquals(piso.decena, decenas.roundToInt())
            }
            zoom *= 1.2
        }
    }

    // ---- Y aun así se ve, en todos los aumentos ----

    /**
     * **Nunca se queda el suelo sin malla.**
     *
     * Es la otra mitad del trato: el cuadro fijo no vale de nada si a un aumento cualquiera
     * la malla no está. Se barre el rango entero de la cámara —que va de un veinteavo a
     * cuarenta veces— y en todos hay al menos un piso puesto del todo.
     */
    @Test
    fun `hay malla puesta en todos los aumentos que la camara permite`() {
        var zoom = Camara3D.ZOOM_MINIMO
        while (zoom <= Camara3D.ZOOM_MAXIMO) {
            val puestos = pisos(zoom)
            assertFalse("a $zoom no hay malla", puestos.isEmpty())
            assertTrue(
                "a $zoom la malla está a medio poner",
                puestos.any { it.cuanto >= 1f }
            )
            zoom *= 1.2
        }
    }

    /**
     * **Ni se cierra en una trama gris, ni pide más rayas de las que se pueden pintar.**
     *
     * Las dos cosas son la misma cuenta: un cuadro que en la pantalla mide menos de lo que se
     * lee es también uno que hay que repetir cientos de veces por fotograma.
     */
    @Test
    fun `ningun piso se aprieta ni pide demasiadas rayas`() {
        var zoom = Camara3D.ZOOM_MINIMO
        while (zoom <= Camara3D.ZOOM_MAXIMO) {
            for (piso in pisos(zoom)) {
                val enPantalla = piso.lado * zoom
                assertTrue("a $zoom un cuadro cae a $enPantalla px", enPantalla > 20.0)
                val rayas = alcance(zoom) / piso.lado
                assertTrue("a $zoom hacen falta $rayas rayas", rayas <= RAYAS_DE_UN_LADO + 1)
            }
            zoom *= 1.2
        }
    }

    /**
     * **Los pisos que se ven van seguidos.**
     *
     * De ello depende que el piso de encima pueda quedarse con las rayas de diez en diez del
     * de abajo: si entre uno y otro faltara un piso, esas rayas no las pintaría nadie y la
     * malla saldría con calles vacías. Ver [pisosDeLaMalla].
     */
    @Test
    fun `los pisos que se ven van seguidos y de menor a mayor`() {
        var zoom = Camara3D.ZOOM_MINIMO
        while (zoom <= Camara3D.ZOOM_MAXIMO) {
            val puestos = pisos(zoom)
            for (i in 1 until puestos.size) {
                assertEquals(
                    "a $zoom los pisos saltan",
                    puestos[i - 1].decena + 1, puestos[i].decena
                )
                assertEquals(
                    "a $zoom el piso de encima no es diez veces el de abajo",
                    puestos[i - 1].lado * PISOS_POR_DECENA, puestos[i].lado, 1e-9
                )
            }
            zoom *= 1.2
        }
    }

    /**
     * **Un piso entra desvaneciéndose, no de golpe.**
     *
     * Un pellizco recorre muchos aumentos en un gesto; si el piso fino apareciera entero de
     * una vez, el suelo parpadearía a media pinza.
     */
    @Test
    fun `el piso fino entra poco a poco`() {
        // Se busca el aumento donde el piso más fino de los que hay está a medias.
        var aMedias = 0
        var zoom = Camara3D.ZOOM_MINIMO
        while (zoom <= Camara3D.ZOOM_MAXIMO) {
            val fino = pisos(zoom).firstOrNull()
            if (fino != null && fino.cuanto > 0.05f && fino.cuanto < 0.95f) aMedias++
            zoom *= 1.05
        }
        assertTrue("ningún piso entra desvaneciéndose", aMedias > 0)
    }

    // ---- Lo marcado de cada piso ----

    /**
     * Cada decena pesa más que la de debajo —la raya gorda del papel milimetrado— y ninguna
     * llega a los ejes, que son los que tienen que ganar siempre.
     */
    @Test
    fun `una decena va mas marcada que la de debajo y menos que los ejes`() {
        assertEquals(ALFA_DEL_SUELO, alfaDelPiso(0), 1e-6f)
        assertTrue("lo fino pesa más que el cuadro", alfaDelPiso(-1) < alfaDelPiso(0))
        assertTrue("la decena no pesa más que el cuadro", alfaDelPiso(1) > alfaDelPiso(0))
        assertTrue("la centena no pesa más que la decena", alfaDelPiso(2) > alfaDelPiso(1))
        for (decena in -3..6) {
            assertTrue("la decena $decena no se ve", alfaDelPiso(decena) > 0f)
            assertTrue("la decena $decena le gana a los ejes", alfaDelPiso(decena) <= ALFA_DE_LOS_EJES)
            assertTrue(alfaDelPiso(decena) <= alfaDelPiso(decena + 1))
        }
    }

    // ---- El cuadro de la pauta de una hoja ----

    /**
     * **Un cuadro es un cuadro**: el de la mesa de dibujo y el del suelo miden lo mismo, o
     * contar sobre la hoja y contar sobre el suelo darían números distintos.
     */
    @Test
    fun `el cuadro de la hoja es el mismo que el del suelo`() {
        assertEquals(LADO_DEL_CUADRO, ladoDelCuadro(1.0), 1e-9)
        assertEquals(
            pisos(1.0).first { it.decena == 0 }.lado,
            ladoDelCuadro(1.0),
            1e-9
        )
    }

    /**
     * **El cuadro de la hoja solo cambia de diez en diez, y siempre se ve.**
     *
     * Doblarlo lo dejaría igual de legible y le quitaría el sentido: lo que se cuenta sobre
     * la hoja tiene que valer algo dicho en voz alta.
     */
    @Test
    fun `el cuadro de la hoja va de diez en diez y siempre cabe en la ventana`() {
        var zoom = Camara3D.ZOOM_MINIMO
        while (zoom <= Camara3D.ZOOM_MAXIMO) {
            val lado = ladoDelCuadro(zoom)
            val decenas = log10(lado / LADO_DEL_CUADRO)
            assertEquals(
                "a $zoom el cuadro de la hoja es $lado",
                decenas.roundToInt().toDouble(), decenas, 1e-9
            )
            val enPantalla = lado * zoom
            assertTrue(
                "a $zoom el cuadro de la hoja cae a $enPantalla px",
                enPantalla >= MINIMO_DEL_CUADRO - 1e-6 && enPantalla <= MAXIMO_DEL_CUADRO + 1e-6
            )
            zoom *= 1.07
        }
    }

    /** Un aumento imposible no cuelga los dos bucles ni devuelve una malla rota. */
    @Test
    fun `un aumento imposible no rompe nada`() {
        assertEquals(LADO_DEL_CUADRO, ladoDelCuadro(0.0), 1e-9)
        assertEquals(LADO_DEL_CUADRO, ladoDelCuadro(-3.0), 1e-9)
        assertEquals(LADO_DEL_CUADRO, ladoDelCuadro(Double.NaN), 1e-9)
        assertTrue(pisosDeLaMalla(0.0, 100.0).isEmpty())
        assertTrue(pisosDeLaMalla(1.0, 0.0).isEmpty())
        assertTrue(pisosDeLaMalla(Double.NaN, 100.0).isEmpty())
    }

    /**
     * **La malla no se viene con uno.**
     *
     * Las rayas están en los múltiplos del cuadro contados desde el origen, así que
     * desplazarse las pasa por debajo. Es lo que hace que dos capturas del mismo croquis
     * desde sitios distintos se puedan comparar cuadro a cuadro — y es lo que no hacía la
     * malla de antes, que se plantaba centrada en lo que se miraba.
     */
    @Test
    fun `las rayas caen en los multiplos del cuadro y no en la mirada`() {
        val lado = pisos(1.0).first { it.decena == 0 }.lado
        // Mirando desde un sitio cualquiera —ninguno de ellos múltiplo del cuadro—, las
        // rayas siguen cayendo donde manda el mundo, y cubren lo que se ve.
        for (donde in listOf(0.0, 13.7, -204.3, 1000.0)) {
            val cuales = rayasDelPiso(lado, donde - 500.0, donde + 500.0)
            assertTrue("no cubre por la izquierda", cuales.first * lado <= donde - 500.0)
            assertTrue("no cubre por la derecha", cuales.last * lado >= donde + 500.0)
            for (i in cuales) {
                val x = i * lado
                assertEquals("la raya $i no cae en un múltiplo", 0.0, x % lado, 1e-9)
            }
        }
        // Y moverse media raya no mueve las rayas: mueve cuáles hacen falta.
        val quietas = rayasDelPiso(lado, -500.0, 500.0)
        val corridas = rayasDelPiso(lado, -500.0 + lado / 2, 500.0 + lado / 2)
        assertTrue("la malla se ha venido con la mirada", quietas.first <= corridas.first)
        assertEquals(
            "el cero ha dejado de ser el cero",
            0.0, (corridas.first * lado) % lado, 1e-9
        )
    }

    /**
     * **El cero es el del mundo.**
     *
     * Los ejes se pintan aparte, y son los del origen y no dos rayas gordas debajo de la
     * mirada. Es lo que dice dónde está uno: si los ejes viajaran con la vista, mirar a un
     * lado y mirar al otro se verían igual.
     */
    @Test
    fun `el cero entra en las rayas cuando se le mira y no cuando no`() {
        val lado = LADO_DEL_CUADRO
        assertTrue(0 in rayasDelPiso(lado, -100.0, 100.0))
        assertFalse(0 in rayasDelPiso(lado, 1000.0, 1200.0))
    }
}
