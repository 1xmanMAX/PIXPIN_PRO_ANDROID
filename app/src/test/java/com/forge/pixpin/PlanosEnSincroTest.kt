package com.forge.pixpin

import com.forge.pixpin.motor.Scene
import com.forge.pixpin.planos.CapaDelPlano
import com.forge.pixpin.planos.ImprimirPlano
import com.forge.pixpin.planos.Orbita
import com.forge.pixpin.planos.TiposIfc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo hecho sobre un plano va en la capa y su `.hoja` para viajar al PC ([CapaDelPlano]); y la
 * cámara del visor 3D ([Orbita]), con las cuentas del PC.
 */
class PlanosEnSincroTest {
    private val caja = floatArrayOf(-50f, -20f, 50f, 20f)

    @Test
    fun la_hoja_devuelve_el_mismo_encaje_aunque_el_otro_mida_distinto() {
        val e = CapaDelPlano.Encaje(0.05, 3.0, -2.0)
        val hoja = CapaDelPlano.hojaDe(e, caja)
        val vuelta = CapaDelPlano.encajeDe(hoja, caja)
        assertNotNull(vuelta)
        assertEquals(0.05, vuelta!!.u, 1e-9); assertEquals(3.0, vuelta.ceroX, 1e-9); assertEquals(-2.0, vuelta.ceroY, 1e-9)
        // El PC escribió con otra escala de capa (unidad 1) y su centro en otro sitio: un punto del
        // plano cae igual en los dos.
        val delPc = CapaDelPlano.Encaje(1.0, 0.0, 0.0)
        val leido = CapaDelPlano.encajeDe(CapaDelPlano.hojaDe(delPc, caja), caja)!!
        val xCapa = delPc.aCapaX(12.5); val yCapa = delPc.aCapaY(-7.25)
        assertEquals(12.5, leido.aPlanoX(xCapa), 1e-9); assertEquals(-7.25, leido.aPlanoY(yCapa), 1e-9)
    }

    @Test
    fun las_cotas_y_los_marcos_van_y_vuelven_por_la_capa_y_lo_quitado_viaja_como_borrado() {
        val e = CapaDelPlano.Encaje(0.1, 0.0, 0.0)
        val cotas = listOf(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0), doubleArrayOf(10.0, 5.0)))
        val marcos = listOf(ImprimirPlano.Marco.entre(-10.0, -5.0, 20.0, 15.0), ImprimirPlano.Marco.entre(30.0, 0.0, 40.0, 8.0))
        val s1 = CapaDelPlano.con(Scene(), e, cotas, marcos, 1000)
        val (c1, m1) = CapaDelPlano.leer(s1, e)
        assertEquals(1, c1.size); assertEquals(3, c1[0].size)
        assertEquals(10.0, c1[0][2][0], 1e-9); assertEquals(5.0, c1[0][2][1], 1e-9)
        assertEquals(2, m1.size)
        assertEquals(-10.0, m1[0].x0, 1e-9); assertEquals(15.0, m1[0].y1, 1e-9)
        assertEquals(30.0, m1[1].x0, 1e-9) // en su orden: son las hojas 1 y 2
        // Lo anotado a mano no se toca, y no se ve como «del plano».
        assertEquals(0, CapaDelPlano.sinLoDelPlano(s1).elements.size)
        // Sin cambios, nada cambia (mismas versiones): no hay nada que sincronizar.
        val s2 = CapaDelPlano.con(s1, e, c1, m1, 2000)
        assertEquals(s1.elements.map { it.id to it.version }, s2.elements.map { it.id to it.version })
        // Quitar el segundo marco lo deja **borrado** (con versión nueva), no lo hace desaparecer.
        val s3 = CapaDelPlano.con(s2, e, c1, m1.take(1), 3000)
        val borrado = s3.elements.single { it.isDeleted }
        assertTrue(borrado.version > s2.elements.single { it.id == borrado.id }.version)
        assertEquals(1, CapaDelPlano.leer(s3, e).second.size)
    }

    @Test
    fun la_camara_mira_al_objetivo_y_encuadra_la_caja() {
        val o = Orbita.encuadrar(floatArrayOf(-10f, -10f, 0f, 10f, 10f, 5f), 1000, 2000)
        val m = o.matriz(1000, 2000, 30.0)
        // El objetivo cae en el centro de la pantalla.
        fun proyectar(p: DoubleArray): DoubleArray {
            val x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]
            val y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]
            val z = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]
            val w = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15]
            return doubleArrayOf(x / w, y / w, z / w)
        }
        val c = proyectar(o.objetivo)
        assertEquals(0.0, c[0], 1e-5); assertEquals(0.0, c[1], 1e-5)
        assertTrue("delante de la cámara", c[2] > -1 && c[2] < 1)
        // Las ocho esquinas, dentro de la pantalla.
        for (i in 0 until 8) {
            val p = proyectar(doubleArrayOf(if (i and 1 == 0) -10.0 else 10.0, if (i and 2 == 0) -10.0 else 10.0, if (i and 4 == 0) 0.0 else 5.0))
            assertTrue("esquina $i: ${p.toList()}", kotlin.math.abs(p[0]) <= 1.0001 && kotlin.math.abs(p[1]) <= 1.0001)
        }
        // Girar alrededor del objetivo lo deja en el centro; el zoom deja quieto el punto fijo.
        val g = o.girar(0.7, 0.2, o.objetivo)
        val m2 = g.matriz(1000, 2000, 30.0)
        val t = g.objetivo
        val w = m2[3] * t[0] + m2[7] * t[1] + m2[11] * t[2] + m2[15]
        assertEquals(0.0, (m2[0] * t[0] + m2[4] * t[1] + m2[8] * t[2] + m2[12]) / w, 1e-5)
        val z = o.zoom(0.5, o.objetivo)
        assertEquals(o.dist / 2, z.dist, 1e-9)
        // La planta mira de arriba.
        assertTrue(o.enPlanta().ojo()[2] > o.objetivo[2])
    }

    @Test
    fun los_tipos_ifc_se_dicen_en_castellano() {
        assertEquals("Muro", TiposIfc.legible("IfcWallStandardCase"))
        assertEquals("Losa", TiposIfc.legible("IfcSlab"))
        assertEquals("Superficie", TiposIfc.legible("Superficie"))
        assertEquals("Algo", TiposIfc.legible("IfcAlgo"))
    }

    @Test
    fun lee_el_modelo_3d_que_escribe_el_rust_del_pc() {
        // `tramo.px3d` lo escribió libpixpincad.so desde `tramo.xml` (un LandXML: una superficie de
        // cuatro triángulos y un eje): el formato del visor de modelos del PC.
        val b = requireNotNull(javaClass.classLoader?.getResourceAsStream("planos/tramo.px3d")).use { it.readBytes() }
        val m = com.forge.pixpin.planos.Modelo3D.deBytes(java.nio.ByteBuffer.wrap(b))
        assertTrue(!m.vacio)
        assertTrue("la superficie", m.nOpacos >= 12)
        assertTrue("curvas de nivel o el eje", m.nLineas > 0)
        assertTrue(m.elementos.any { it.nombre == "TN" || it.tipo.contains("Superficie", true) })
        // De 10 a 20 m de cota.
        assertEquals(10.0, (m.caja[5] - m.caja[2]).toDouble(), 0.5)
        // Un modelo roto no se lee.
        try { com.forge.pixpin.planos.Modelo3D.deBytes(java.nio.ByteBuffer.wrap(b.copyOf(b.size / 2))); org.junit.Assert.fail() } catch (e: com.forge.pixpin.planos.Modelo3D.NoSeLee) { }
    }
}
