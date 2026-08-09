package com.forge.pixpin.floating

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión: la bola desaparecía y no volvía ni pulsando «Comenzar».
 *
 * La causa era que el código solo distinguía dos estados —hay ventana / no hay
 * ventana— y el que fallaba era un tercero: la ventana puesta pero con el
 * contenido oculto, que es como queda si una captura se va al traste sin
 * restaurar los overlays.
 */
class BallStateTest {

    @Test
    fun `sin ventana hay que crearla`() {
        assertEquals(
            BallRecovery.CREAR,
            BallState.recoveryFor(windowExists = false, contentVisible = false)
        )
    }

    @Test
    fun `con la ventana visible no hay nada que hacer`() {
        assertEquals(
            BallRecovery.NADA,
            BallState.recoveryFor(windowExists = true, contentVisible = true)
        )
    }

    /** El caso del fallo: existe pero está en GONE. Antes se daba por buena. */
    @Test
    fun `con la ventana puesta pero oculta hay que volver a ensenarla`() {
        assertEquals(
            BallRecovery.VOLVER_A_ENSENAR,
            BallState.recoveryFor(windowExists = true, contentVisible = false)
        )
    }

    /**
     * Sin ventana da igual lo que diga la visibilidad del contenido: no hay
     * contenido del que hablar.
     */
    @Test
    fun `sin ventana la visibilidad del contenido es irrelevante`() {
        assertEquals(
            BallRecovery.CREAR,
            BallState.recoveryFor(windowExists = false, contentVisible = true)
        )
    }

    // ---------------------------------------------------------------------
    // Girar el móvil: el otro caso en el que la bola desaparecía
    // ---------------------------------------------------------------------

    // Un móvil corriente: 1080 × 2400 de pie, 2400 × 1080 tumbado.
    private val vertical = 1080 to 2400
    private val horizontal = 2400 to 1080
    private val lado = 144   // 48 dp a densidad 3

    @Test
    fun `pegada a un borde asoma justo la mitad`() {
        assertEquals(-72, BallState.pegadaAlBorde(izquierda = true, lado = lado, ancho = 1080))
        assertEquals(1008, BallState.pegadaAlBorde(izquierda = false, lado = lado, ancho = 1080))
    }

    /**
     * **El fallo que dejaba la bola en mitad de la pantalla.**
     *
     * Pegada al borde derecho en vertical, x ≈ 1008 de 1080. Girando, esos
     * mismos 1008 píxeles caen a media pantalla de 2400 — que es exactamente lo
     * que se veía. Tiene que seguir pegada a la derecha, ahora en 2328.
     */
    @Test
    fun `al girar sigue pegada al mismo borde`() {
        val antes = BallState.pegadaAlBorde(false, lado, vertical.first)
        val despues = BallState.alCambiarLaPantalla(
            antes, 800, lado, vertical.first, vertical.second, horizontal.first, horizontal.second
        )
        assertEquals(
            BallState.pegadaAlBorde(false, lado, horizontal.first),
            despues.x
        )
    }

    @Test
    fun `al girar conserva la altura relativa`() {
        // A un tercio de la pantalla vertical.
        val y = vertical.second / 3 - lado / 2
        val despues = BallState.alCambiarLaPantalla(
            BallState.pegadaAlBorde(true, lado, vertical.first), y, lado,
            vertical.first, vertical.second, horizontal.first, horizontal.second
        )
        // Sigue a un tercio, ahora de 1080. Con margen: el redondeo del centro.
        val esperado = horizontal.second / 3 - lado / 2
        assertEquals(esperado.toDouble(), despues.y.toDouble(), 2.0)
    }

    /**
     * **El otro fallo: se va por debajo.**
     *
     * Abajo del todo en vertical (y ≈ 2256) y girando a una pantalla de 1080 de
     * alto, la bola queda mil píxeles por debajo del borde. No hay forma de
     * tocarla.
     */
    @Test
    fun `al girar no se queda por debajo del borde`() {
        val despues = BallState.alCambiarLaPantalla(
            1008, vertical.second - lado, lado,
            vertical.first, vertical.second, horizontal.first, horizontal.second
        )
        assertEquals(horizontal.second - lado, despues.y)
    }

    @Test
    fun `la que asoma se ve y la que se fue no`() {
        // Retraída a medias: asoma media bola, de sobra.
        assertEquals(true, BallState.seVe(-72, 400, lado, 1080, 2400))
        assertEquals(true, BallState.seVe(1008, 400, lado, 1080, 2400))
        // Del todo fuera por la derecha y por abajo.
        assertEquals(false, BallState.seVe(1080, 400, lado, 1080, 2400))
        assertEquals(false, BallState.seVe(400, 2400, lado, 1080, 2400))
        assertEquals(false, BallState.seVe(-lado, 400, lado, 1080, 2400))
    }

    /**
     * El cuarto estado: puesta, visible y fuera de la pantalla.
     *
     * `recoveryFor` decía NADA y nadie la traía de vuelta. Esta es la parte que
     * lo arregla — y tiene que dejar quieta a la que sí se ve, o la bola daría
     * un salto cada vez que se arranca el servicio.
     */
    @Test
    fun `rescatar devuelve la perdida y no toca la que está bien`() {
        val perdida = BallState.rescatar(3000, 5000, lado, 1080, 2400)
        assertEquals(true, BallState.seVe(perdida.x, perdida.y, lado, 1080, 2400))

        val buena = BallState.rescatar(1008, 700, lado, 1080, 2400)
        assertEquals(1008, buena.x)
        assertEquals(700, buena.y)
    }

    /** Y con la pantalla sin medir todavía no se inventa nada. */
    @Test
    fun `sin saber la pantalla no se mueve nada`() {
        val sitio = BallState.rescatar(500, 500, lado, 0, 0)
        assertEquals(500, sitio.x)
        assertEquals(500, sitio.y)
    }

    /** Arrastrando no se le deja salir volando de la pantalla. */
    @Test
    fun `arrastrando se queda a mano`() {
        val lejos = BallState.mientrasSeArrastra(9000, -3000, lado, 1080, 2400)
        assertEquals(true, BallState.seVe(lejos.x, lejos.y, lado, 1080, 2400))
        // Pero sí se le deja pasarse del borde, que es como se esconde: la
        // postura de reposo (1008 de 1080) se alcanza arrastrando.
        val escondida = BallState.mientrasSeArrastra(1008, 400, lado, 1080, 2400)
        assertEquals(1008, escondida.x)
        val asomandoPorLaIzquierda = BallState.mientrasSeArrastra(-72, 400, lado, 1080, 2400)
        assertEquals(-72, asomandoPorLaIzquierda.x)
    }
}
