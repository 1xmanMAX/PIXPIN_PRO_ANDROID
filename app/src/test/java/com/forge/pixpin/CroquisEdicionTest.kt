package com.forge.pixpin.croquis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recortar, extender, pendiente y colocar por ángulo: la edición del croquis. */
class CroquisEdicionTest {

    // --- Interseccion, que es de lo que dependen recortar y extender ---

    @Test
    fun `dos rectas que se cruzan dan su punto de corte`() {
        val p = CroquisGeometria.corteDeRectas(
            P(0.0, 0.0), P(10.0, 0.0),
            P(4.0, -5.0), P(4.0, 5.0)
        )!!
        assertEquals(4.0, p.x, 1e-12)
        assertEquals(0.0, p.y, 1e-12)
    }

    @Test
    fun `dos rectas paralelas no se cortan`() {
        assertNull(
            CroquisGeometria.corteDeRectas(
                P(0.0, 0.0), P(10.0, 0.0),
                P(0.0, 3.0), P(10.0, 3.0)
            )
        )
    }

    @Test
    fun `el corte se calcula sobre las rectas infinitas, no sobre los segmentos`() {
        // El segundo segmento acaba antes de llegar, pero su recta sí cruza.
        val p = CroquisGeometria.corteDeRectas(
            P(0.0, 0.0), P(10.0, 0.0),
            P(4.0, 2.0), P(4.0, 8.0)
        )!!
        assertEquals(4.0, p.x, 1e-12)
        assertEquals(0.0, p.y, 1e-12)
    }

    // --- Extender: alargar hasta encontrarse con otra ---

    @Test
    fun `extender alarga el extremo mas cercano al corte`() {
        val linea = Entidad.Linea(P(0.0, 0.0), P(3.0, 0.0))
        val contra = Entidad.Linea(P(8.0, -2.0), P(8.0, 2.0))
        val r = CroquisGeometria.extender(linea, contra)!!
        assertEquals(0.0, r.a.x, 1e-12)   // el otro extremo no se toca
        assertEquals(8.0, r.b.x, 1e-12)
        assertEquals(0.0, r.b.y, 1e-12)
    }

    @Test
    fun `extender no hace nada si la otra recta es paralela`() {
        val linea = Entidad.Linea(P(0.0, 0.0), P(3.0, 0.0))
        val contra = Entidad.Linea(P(0.0, 4.0), P(9.0, 4.0))
        assertNull(CroquisGeometria.extender(linea, contra))
    }

    @Test
    fun `extender por el lado de atras mueve el extremo de atras`() {
        val linea = Entidad.Linea(P(5.0, 0.0), P(9.0, 0.0))
        val contra = Entidad.Linea(P(1.0, -2.0), P(1.0, 2.0))
        val r = CroquisGeometria.extender(linea, contra)!!
        assertEquals(1.0, r.a.x, 1e-12)
        assertEquals(9.0, r.b.x, 1e-12)
    }

    // --- Recortar: cortar por donde cruza, quedandose con el lado indicado ---

    @Test
    fun `recortar quita el trozo del lado que se toca`() {
        val linea = Entidad.Linea(P(0.0, 0.0), P(10.0, 0.0))
        val cuchilla = Entidad.Linea(P(4.0, -3.0), P(4.0, 3.0))
        // Se toca cerca del final: se va el trozo del final.
        val r = CroquisGeometria.recortar(linea, cuchilla, P(9.0, 0.0))!!
        assertEquals(0.0, r.a.x, 1e-12)
        assertEquals(4.0, r.b.x, 1e-12)
    }

    @Test
    fun `recortar tocando el principio quita el principio`() {
        val linea = Entidad.Linea(P(0.0, 0.0), P(10.0, 0.0))
        val cuchilla = Entidad.Linea(P(4.0, -3.0), P(4.0, 3.0))
        val r = CroquisGeometria.recortar(linea, cuchilla, P(1.0, 0.0))!!
        assertEquals(4.0, r.a.x, 1e-12)
        assertEquals(10.0, r.b.x, 1e-12)
    }

    @Test
    fun `no se recorta si el corte cae fuera del segmento`() {
        val linea = Entidad.Linea(P(0.0, 0.0), P(3.0, 0.0))
        val cuchilla = Entidad.Linea(P(8.0, -3.0), P(8.0, 3.0))
        assertNull(CroquisGeometria.recortar(linea, cuchilla, P(1.0, 0.0)))
    }

    // --- Elegir una linea tocando cerca de ella ---

    @Test
    fun `la distancia a un segmento se mide al trozo, no a la recta infinita`() {
        val l = Entidad.Linea(P(0.0, 0.0), P(10.0, 0.0))
        // Perpendicular por el medio: 3.
        assertEquals(3.0, CroquisGeometria.distanciaA(l, P(5.0, 3.0)), 1e-12)
        // Más allá del final: manda la distancia al extremo, no la perpendicular.
        assertEquals(5.0, CroquisGeometria.distanciaA(l, P(15.0, 0.0)), 1e-12)
    }

    @Test
    fun `elegir linea devuelve la mas cercana dentro de la tolerancia`() {
        val croquis = Croquis(
            entidades = listOf(
                Entidad.Linea(P(0.0, 0.0), P(10.0, 0.0)),
                Entidad.Linea(P(0.0, 6.0), P(10.0, 6.0))
            )
        )
        assertEquals(0, CroquisGeometria.lineaMasCercana(croquis, P(5.0, 1.0), 2.0))
        assertEquals(1, CroquisGeometria.lineaMasCercana(croquis, P(5.0, 5.0), 2.0))
    }

    @Test
    fun `tocar lejos de todo no elige ninguna linea`() {
        val croquis = Croquis(entidades = listOf(Entidad.Linea(P(0.0, 0.0), P(10.0, 0.0))))
        assertNull(CroquisGeometria.lineaMasCercana(croquis, P(5.0, 40.0), 2.0))
    }

    // --- Borrador: elegir cualquier entidad, no solo lineas ---

    @Test
    fun `el borrador alcanza un circulo por su contorno, no por su centro`() {
        val croquis = Croquis(entidades = listOf(Entidad.Circulo(P(0.0, 0.0), 5.0)))
        // Tocando sobre el trazo del círculo: dentro de tolerancia.
        assertEquals(0, CroquisGeometria.entidadMasCercana(croquis, P(5.2, 0.0), 1.0))
        // Tocando en mitad del hueco: el círculo está a 5 m de ahí.
        assertNull(CroquisGeometria.entidadMasCercana(croquis, P(0.0, 0.0), 1.0))
    }

    @Test
    fun `el borrador elige la entidad mas cercana entre varias`() {
        val croquis = Croquis(
            entidades = listOf(
                Entidad.Linea(P(0.0, 0.0), P(10.0, 0.0)),
                Entidad.Linea(P(0.0, 20.0), P(10.0, 20.0)),
                Entidad.Circulo(P(5.0, 9.0), 1.0)
            )
        )
        assertEquals(2, CroquisGeometria.entidadMasCercana(croquis, P(5.0, 10.2), 1.0))
        assertEquals(0, CroquisGeometria.entidadMasCercana(croquis, P(5.0, 0.5), 1.0))
    }

    @Test
    fun `el borrador alcanza una polilinea por cualquiera de sus tramos`() {
        val croquis = Croquis(
            entidades = listOf(
                Entidad.Polilinea(listOf(P(0.0, 0.0), P(10.0, 0.0), P(10.0, 10.0)))
            )
        )
        assertEquals(0, CroquisGeometria.entidadMasCercana(croquis, P(10.3, 6.0), 1.0))
    }

    // --- Pendiente en porcentaje, que es como se habla en obra ---

    @Test
    fun `45 grados es una pendiente del 100 por ciento`() {
        assertEquals(100.0, CroquisGeometria.gradosAPorcentaje(45.0), 1e-9)
    }

    @Test
    fun `una pendiente del 2 por ciento son 1,1458 grados`() {
        assertEquals(1.14576, CroquisGeometria.porcentajeAGrados(2.0), 1e-5)
    }

    @Test
    fun `la horizontal es pendiente cero`() {
        assertEquals(0.0, CroquisGeometria.gradosAPorcentaje(0.0), 1e-12)
    }

    @Test
    fun `una bajada da pendiente negativa`() {
        assertEquals(-100.0, CroquisGeometria.gradosAPorcentaje(-45.0), 1e-9)
    }

    // --- Colocar por longitud Y angulo a la vez ---

    @Test
    fun `colocar por longitud y angulo pone el punto donde toca`() {
        val r = CroquisGeometria.desdePolar(P(1.0, 1.0), longitudM = 10.0, grados = 90.0)
        assertEquals(1.0, r.x, 1e-9)
        assertEquals(11.0, r.y, 1e-9)
    }

    @Test
    fun `el angulo de una linea se mide desde la horizontal`() {
        assertEquals(90.0, CroquisGeometria.gradosDe(P(0.0, 0.0), P(0.0, 5.0)), 1e-9)
        assertEquals(0.0, CroquisGeometria.gradosDe(P(0.0, 0.0), P(5.0, 0.0)), 1e-9)
    }
}
