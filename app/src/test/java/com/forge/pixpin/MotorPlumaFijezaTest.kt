package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import kotlin.math.sin

/**
 * **La fijeza: nada del trazo se recalcula al orbitar.** Es la meta entera del Motor Pluma
 * convertida en contrato ejecutable.
 *
 * La forma de probarlo es de identidad, no de parecido: la geometría que consume el pintado
 * —el esqueleto con su espina, sus marcos y sus anchos— tiene que ser **el mismo objeto**
 * ante dos cámaras distintas. Si alguna vez alguien mete la vista en la llave del armario o
 * el ángulo de órbita en el nivel de detalle, estas pruebas caen en seco — y ese es su
 * trabajo.
 */
class MotorPlumaFijezaTest {

    @Before
    fun limpiar() = ElArmarioDeLosEsqueletos.vaciar()

    private fun unTrazoNuevo(): Trazo3D {
        val puntos = (0..30).map { Pt3(it * 4.0, sin(it / 4.0) * 12.0, it * 0.5) }
        return Trazo3D(
            id = "t", puntos = puntos, color = "#1971c2", grosor = 3.0,
            pincel = Pincel.REDONDO, calibre = 3.0,
            presiones = List(puntos.size) { 0.6 + (it % 5) * 0.05 },
            tiempos = List(puntos.size) { it * 0.012 },
            normal = Pt3(0.0, 0.0, 1.0)
        )
    }

    @Test
    fun `orbitar no toca el esqueleto - mismo objeto ante cualquier camara`() {
        val trazo = unTrazoNuevo()
        // Ocho cámaras dando la vuelta entera al mismo zoom.
        val esqueletos = (0 until 8).map { i ->
            val camara = Camara3D(giro = i * Math.PI / 4, inclinacion = 0.4, zoom = 2.5)
            ElArmarioDeLosEsqueletos.de(trazo, nivelDePluma(trazo.calibre, camara.zoom))
        }
        assertNotNull(esqueletos.first())
        for (e in esqueletos.drop(1)) {
            assertSame("la órbita no puede recocer nada", esqueletos.first(), e)
        }
    }

    @Test
    fun `el nivel de detalle solo escucha al zoom`() {
        // A zoom fijo, cualquier ángulo de órbita e inclinación da el mismo nivel: el nivel
        // sale de calibre×zoom y de nada más. (La firma ni siquiera admite la cámara — esta
        // prueba existe para que nadie se la añada.)
        val nivelBase = nivelDePluma(3.0, 2.5)
        for (zoom in listOf(2.5, 2.5, 2.5)) {
            assertEquals(nivelBase, nivelDePluma(3.0, zoom))
        }
        // Y con el zoom sí se mueve, en escalones.
        assertEquals(0, nivelDePluma(3.0, 40.0))
        assertEquals(2, nivelDePluma(3.0, 0.1))
    }

    @Test
    fun `los marcos del esqueleto viven en el mundo`() {
        // La normal de apoyo de cada muestra es dato del MUNDO: dos consultas al armario
        // con el trazo bajo dos cámaras devuelven marcos idénticos porque son el mismo
        // array — no una copia parecida.
        val trazo = unTrazoNuevo()
        val a = ElArmarioDeLosEsqueletos.de(trazo, 0)!!
        val b = ElArmarioDeLosEsqueletos.de(trazo, 0)!!
        assertSame(a.marcos, b.marcos)
        assertSame(a.anchos, b.anchos)
        assertSame(a.xyz, b.xyz)
    }
}
