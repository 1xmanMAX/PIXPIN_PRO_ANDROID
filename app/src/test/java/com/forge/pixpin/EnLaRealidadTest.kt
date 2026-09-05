package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **El croquis puesto en el sitio.** Ver [Croquis3DControlador.verEnLaRealidad].
 *
 * Lo que se comprueba es lo que decide si esto se siente como estar delante de algo: que la
 * primera postura lo ponga delante, que a partir de ahí el ojo no se mueva, y que el dedo no
 * se pelee con el sensor por girar la vista.
 */
class EnLaRealidadTest {

    private fun mirando(giro: Double, inclinacion: Double = 0.0, balanceo: Double = 0.0) =
        PosturaDelTelefono(giro, inclinacion, balanceo)

    /** Dónde está el ojo de una cámara con lente puesta, en el mundo. */
    private fun ojoDe(c: Camara3D, alto: Double): Pt3 = c.ojo(alto)!!

    private fun controlador() = Croquis3DControlador().apply { medida(1000.0, 2000.0) }

    @Test
    fun `asomarse abre la lente y volver la deja como estaba`() {
        val c = controlador()
        val antes = c.camara
        assertEquals("de fábrica es ortográfica", 0.0, antes.lente, 1e-9)

        c.verEnLaRealidad(true)
        assertTrue(c.realidad)
        assertEquals(Croquis3DControlador.LENTE_DE_UN_TELEFONO, c.camara.lente, 1e-9)

        c.verEnLaRealidad(false)
        assertFalse(c.realidad)
        assertEquals(antes, c.camara)
    }

    /**
     * Asomado, la lente proyecta como una cámara de verdad y no como un ojo de pez: es lo
     * que hace que el croquis no resbale sobre la habitación por los bordes. Y al salir se
     * vuelve a la de dibujar. Ver [Camara3D.rectilinea].
     */
    @Test
    fun `asomarse proyecta como una camara y volver lo deja como estaba`() {
        val c = controlador()
        assertFalse(c.camara.rectilinea)
        c.verEnLaRealidad(true)
        assertTrue(c.camara.rectilinea)
        c.verEnLaRealidad(false)
        assertFalse(c.camara.rectilinea)
    }

    /**
     * La lente de verdad del aparato llega un instante después de encender la cámara, y
     * se aplica **sin mover el ojo**: el croquis no puede pegar un salto al aparecer.
     */
    @Test
    fun `la lente del aparato entra sin mover el ojo`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.3))
        val ojo = ojoDe(c.camara, 2000.0)

        val campo = Math.toRadians(72.0)
        c.lenteDelAparato(campo)
        assertEquals(campo, c.camara.campo, 1e-9)
        val despues = ojoDe(c.camara, 2000.0)
        assertEquals(ojo.x, despues.x, 1e-6)
        assertEquals(ojo.y, despues.y, 1e-6)
        assertEquals(ojo.z, despues.z, 1e-6)

        // Y la próxima vez que se asome, ya sale con ella puesta.
        c.verEnLaRealidad(false)
        c.verEnLaRealidad(true)
        assertEquals(campo, c.camara.campo, 1e-9)
    }

    /** Un campo disparatado —un dato roto del aparato— no cambia nada. */
    @Test
    fun `una lente imposible se ignora`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        val antes = c.camara
        c.lenteDelAparato(0.0)
        c.lenteDelAparato(Double.NaN)
        c.lenteDelAparato(Math.toRadians(175.0))
        assertEquals(antes, c.camara)
    }

    /**
     * Al asomarse, **el croquis entero delante**: se entra con el aumento de estar mirando
     * un detalle y el ojo se plantaba dentro del dibujo, sin nada que ver. Y al salir, el
     * detalle vuelve.
     */
    @Test
    fun `asomarse encuadra el croquis y volver recupera el detalle`() {
        val c = controlador()
        c.cargar(Croquis(
            trazos = listOf(
                Trazo3D(
                    "t", listOf(Pt3(-400.0, 0.0, 0.0), Pt3(400.0, 0.0, 0.0), Pt3(0.0, 0.0, 300.0)),
                    "#000000", 4.0
                )
            )
        ))
        c.orbitar(0.0, 0.0, 30.0)
        val deCerca = c.camara
        assertTrue(deCerca.zoom > 10.0)

        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.0))
        // Los tres puntos, en pantalla.
        for (p in listOf(Pt3(-400.0, 0.0, 0.0), Pt3(400.0, 0.0, 0.0), Pt3(0.0, 0.0, 300.0))) {
            val q = c.camara.aPantalla(p, 1000.0, 2000.0)
            assertTrue("$p se sale: $q", q.x in 0.0..1000.0 && q.y in 0.0..2000.0)
        }

        c.verEnLaRealidad(false)
        assertEquals(deCerca, c.camara)
    }

    /**
     * **Andar acerca.** Un paso del podómetro o un empujón del acelerómetro mueven el ojo
     * por donde se mira; el croquis se queda donde estaba, así que se ve más grande.
     */
    @Test
    fun `un paso hacia el croquis lo acerca`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.0))
        val ojo = ojoDe(c.camara, 2000.0)
        val antes = largo(menos(c.camara.centro, ojo))
        c.darUnPaso()
        val despues = largo(menos(ojoDe(c.camara, 2000.0), Pt3(0.0, 0.0, 0.0)))
        // El ojo se ha acercado al origen —donde está el croquis— lo que anda un paso.
        val esperado = antes - Croquis3DControlador.LARGO_DE_PASO * antes / Croquis3DControlador.METROS_AL_COLOCARLO
        assertEquals(esperado, despues, 1e-6)
    }

    @Test
    fun `desplazarse mueve el ojo en el sistema del mundo`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.0))
        val ojo = ojoDe(c.camara, 2000.0)
        val d = largo(menos(c.camara.centro, ojo))
        // Medio metro hacia el este: el ojo se va por la x del croquis.
        c.desplazarseComoElTelefono(Pt3(0.5, 0.0, 0.0))
        val nuevo = ojoDe(c.camara, 2000.0)
        assertEquals(ojo.x + 0.5 * d / Croquis3DControlador.METROS_AL_COLOCARLO, nuevo.x, 1e-6)
        assertEquals(ojo.y, nuevo.y, 1e-6)
        assertEquals(ojo.z, nuevo.z, 1e-6)
    }

    /** Sin colocar aún —sin escala— y fuera del modo, nada se mueve. */
    @Test
    fun `sin colocar o fuera del modo andar no hace nada`() {
        val c = controlador()
        val antes = c.camara
        c.darUnPaso()
        c.desplazarseComoElTelefono(Pt3(1.0, 0.0, 0.0))
        assertEquals(antes, c.camara)
        c.verEnLaRealidad(true)
        val recienEntrado = c.camara
        c.darUnPaso()
        assertEquals(recienEntrado, c.camara)
    }

    @Test
    fun `la primera postura pone el croquis delante`() {
        val c = controlador()
        val elCentro = c.camara.centro
        c.verEnLaRealidad(true)
        // Encendido mirando a cualquier parte: el croquis tiene que seguir siendo lo mirado.
        c.mirarComoElTelefono(mirando(giro = 2.4, inclinacion = 0.3))
        assertEquals(elCentro.x, c.camara.centro.x, 1e-9)
        assertEquals(elCentro.y, c.camara.centro.y, 1e-9)
        assertEquals(elCentro.z, c.camara.centro.z, 1e-9)
        assertEquals(2.4, c.camara.giro, 1e-9)
    }

    @Test
    fun `a partir de la segunda, el ojo se queda quieto`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.0))
        val ojo = ojoDe(c.camara, 2000.0)

        // Se gira medio cuarto: el ojo tiene que estar donde estaba.
        c.mirarComoElTelefono(mirando(giro = 0.8, inclinacion = 0.2))
        val despues = ojoDe(c.camara, 2000.0)
        assertEquals(ojo.x, despues.x, 1e-6)
        assertEquals(ojo.y, despues.y, 1e-6)
        assertEquals(ojo.z, despues.z, 1e-6)
        // Y lo mirado se ha ido: eso es mirar a otro lado, no orbitar.
        assertTrue(largo(menos(c.camara.centro, ojo)) > 1e-6)
    }

    @Test
    fun `girarse deja el croquis donde estaba, no lo arrastra`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.0))
        // El croquis está delante: en la pantalla, hacia el centro.
        val antes = c.camara.aPantalla(Pt3(0.0, 0.0, 0.0), 1000.0, 2000.0)
        assertEquals(500.0, antes.x, 1.0)

        // Un cuarto de vuelta: tiene que haberse ido de la pantalla.
        c.mirarComoElTelefono(mirando(giro = Math.PI / 2))
        val alLado = c.camara.aPantalla(Pt3(0.0, 0.0, 0.0), 1000.0, 2000.0)
        assertTrue(
            "el croquis se ha girado con el teléfono en vez de quedarse",
            kotlin.math.abs(alLado.x - 500.0) > 300.0
        )

        // Y media vuelta —el croquis justo a la espalda— tampoco puede salir en el centro:
        // es el caso degenerado del mapeo, y en este modo es el primero que ocurre.
        c.mirarComoElTelefono(mirando(giro = Math.PI))
        val detras = c.camara.aPantalla(Pt3(0.0, 0.0, 0.0), 1000.0, 2000.0)
        assertTrue(
            "lo que queda detrás sale pintado delante: ${detras.x}, ${detras.y}",
            detras.x < 0 || detras.x > 1000 || detras.y < 0 || detras.y > 2000
        )
    }

    @Test
    fun `asomado, el dedo no gira la vista`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.5))
        val antes = c.camara

        c.navegar(120.0, 40.0, 1.0, 500.0, 1000.0, Gesto.GIRAR)
        assertEquals("el dedo se pelea con el sensor", antes.giro, c.camara.giro, 1e-9)
        assertEquals(antes.inclinacion, c.camara.inclinacion, 1e-9)
    }

    @Test
    fun `asomado, el dedo si coloca el croquis`() {
        val c = controlador()
        c.verEnLaRealidad(true)
        c.mirarComoElTelefono(mirando(giro = 0.0))
        val antes = c.camara.centro

        c.navegar(80.0, 0.0, 1.0, 500.0, 1000.0, Gesto.DESPLAZAR)
        assertTrue(
            "sin poder apartarlo no hay forma de ponerlo donde va",
            largo(menos(c.camara.centro, antes)) > 1e-6
        )
    }

    @Test
    fun `sin estar asomado, el sensor no toca nada`() {
        val c = controlador()
        val antes = c.camara
        c.mirarComoElTelefono(mirando(giro = 1.0))
        assertEquals(antes, c.camara)
    }
}
