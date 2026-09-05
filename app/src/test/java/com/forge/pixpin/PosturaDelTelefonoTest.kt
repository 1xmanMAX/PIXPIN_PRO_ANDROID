package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * **La postura del teléfono, pasada a los ángulos de la cámara.** Ver [PosturaDelTelefono].
 *
 * Se comprueba con matrices escritas a mano, que es lo que devolvería el sensor con el
 * aparato en cada postura: aquí no hay nada de Android que haga falta.
 */
class PosturaDelTelefonoTest {

    /**
     * La matriz que lleva del aparato al mundo, dadas las tres direcciones de la pantalla.
     *
     * Sus **columnas** son los ejes del aparato vistos desde el mundo, así que se escribe
     * poniendo la derecha, el arriba y el «hacia el que mira» en columnas. Ver
     * [PosturaDelTelefono.deLaMatriz].
     */
    private fun matriz(derecha: Pt3, arriba: Pt3, haciaAtras: Pt3) = floatArrayOf(
        derecha.x.toFloat(), arriba.x.toFloat(), haciaAtras.x.toFloat(),
        derecha.y.toFloat(), arriba.y.toFloat(), haciaAtras.y.toFloat(),
        derecha.z.toFloat(), arriba.z.toFloat(), haciaAtras.z.toFloat()
    )

    /** El teléfono de pie mirando al norte: la cámara apunta a `+y`. */
    private fun mirandoAlNorte() = matriz(
        derecha = Pt3(1.0, 0.0, 0.0),
        arriba = Pt3(0.0, 0.0, 1.0),
        // La cámara mira a `-z` del aparato, así que su `+z` mira al sur.
        haciaAtras = Pt3(0.0, -1.0, 0.0)
    )

    @Test
    fun `de pie mirando al norte, ni inclinado ni ladeado`() {
        val p = PosturaDelTelefono.deLaMatriz(mirandoAlNorte())!!
        assertEquals("mirar a +y es giro cero", 0.0, p.giro, 1e-6)
        assertEquals(0.0, p.inclinacion, 1e-6)
        assertEquals(0.0, p.balanceo, 1e-6)
    }

    @Test
    fun `girarse al este gira un cuarto`() {
        // Mirando a `+x`: la derecha de la pantalla pasa a mirar al sur.
        val p = PosturaDelTelefono.deLaMatriz(
            matriz(
                derecha = Pt3(0.0, -1.0, 0.0),
                arriba = Pt3(0.0, 0.0, 1.0),
                haciaAtras = Pt3(-1.0, 0.0, 0.0)
            )
        )!!
        assertEquals(Math.PI / 2, p.giro, 1e-6)
        assertEquals(0.0, p.inclinacion, 1e-6)
    }

    @Test
    fun `apuntar al suelo es inclinacion positiva`() {
        // Tumbado en la mesa con la cámara hacia abajo: mira a `-z`.
        val p = PosturaDelTelefono.deLaMatriz(
            matriz(
                derecha = Pt3(1.0, 0.0, 0.0),
                arriba = Pt3(0.0, 1.0, 0.0),
                haciaAtras = Pt3(0.0, 0.0, 1.0)
            )
        )!!
        assertEquals("mirar al suelo", Math.PI / 2, p.inclinacion, 1e-6)
    }

    @Test
    fun `apuntar al cielo es inclinacion negativa`() {
        val p = PosturaDelTelefono.deLaMatriz(
            matriz(
                derecha = Pt3(1.0, 0.0, 0.0),
                arriba = Pt3(0.0, -1.0, 0.0),
                haciaAtras = Pt3(0.0, 0.0, -1.0)
            )
        )!!
        assertEquals(-Math.PI / 2, p.inclinacion, 1e-6)
    }

    @Test
    fun `ladear el telefono ladea la vista`() {
        // Mirando al norte pero con el aparato girado un cuarto sobre su propio eje: la
        // derecha de la pantalla apunta al cielo.
        val p = PosturaDelTelefono.deLaMatriz(
            matriz(
                derecha = Pt3(0.0, 0.0, 1.0),
                arriba = Pt3(-1.0, 0.0, 0.0),
                haciaAtras = Pt3(0.0, -1.0, 0.0)
            )
        )!!
        assertEquals(0.0, p.giro, 1e-6)
        assertEquals(0.0, p.inclinacion, 1e-6)
        assertEquals(Math.PI / 2, kotlin.math.abs(p.balanceo), 1e-6)
    }

    @Test
    fun `los angulos que salen son los que la camara vuelve a dar`() {
        // Una postura cualquiera: se pasa a ángulos y se comprueba que una cámara puesta en
        // esos ángulos mira a donde miraba el teléfono.
        val giro = 0.7
        val inclinacion = 0.4
        val mira = Pt3(
            sin(giro) * cos(inclinacion), cos(giro) * cos(inclinacion), -sin(inclinacion)
        )
        val horizontal = Pt3(cos(giro), -sin(giro), 0.0)
        val p = PosturaDelTelefono.deLaMatriz(
            matriz(
                derecha = horizontal,
                arriba = normalizado(producto(horizontal, mira)),
                haciaAtras = por(mira, -1.0)
            )
        )!!
        assertEquals(giro, p.giro, 1e-5)
        assertEquals(inclinacion, p.inclinacion, 1e-5)
        assertEquals(0.0, p.balanceo, 1e-5)

        val camara = Camara3D(giro = p.giro, inclinacion = p.inclinacion, balanceo = p.balanceo)
        assertEquals(mira.x, camara.adelante.x, 1e-5)
        assertEquals(mira.y, camara.adelante.y, 1e-5)
        assertEquals(mira.z, camara.adelante.z, 1e-5)
    }

    @Test
    fun `una matriz que no vale no da postura`() {
        assertNull(PosturaDelTelefono.deLaMatriz(floatArrayOf(1f, 0f, 0f)))
        assertNull(PosturaDelTelefono.deLaMatriz(FloatArray(9)))
    }

    @Test
    fun `mirar alrededor deja el ojo donde estaba`() {
        // Con lente puesta hay ojo de verdad: girar la vista tiene que dejarlo quieto.
        val camara = Camara3D(lente = 0.4, zoom = 2.0, centro = Pt3(3.0, 4.0, 1.0))
        val alto = 1000.0
        fun ojoDe(c: Camara3D): Pt3 {
            val d = c.aPantalla(c.centro, 1000.0, alto)
            assertNotNull(d)
            return c.centro
        }
        val movida = camara.mirandoDesdeElMismoSitio(1.1, 0.2, 0.0, alto)
        // Lo que se conserva es el ojo, no el centro: el centro tiene que haberse ido.
        assertEquals(true, largo(menos(movida.centro, camara.centro)) > 1e-6)
        // Y el ojo se recalcula igual desde las dos: centro menos adelante por la distancia.
        val d1 = largo(menos(camara.centro, ojoDe(camara)))
        assertEquals(0.0, d1, 1e-9)
    }
}
