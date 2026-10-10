package com.forge.pixpin

import com.forge.pixpin.planos.ArbolDelModelo
import com.forge.pixpin.planos.ArbolDelModelo.Clave
import com.forge.pixpin.planos.Modelo3D
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Niveles y categorías del visor 3D (PC `9625d0d`): el PX3D v3 con niveles y medidas, y las pruebas
 * de `ventana3d/arbol.rs` del PC pasadas al panel de Android (un toque oculta, mantener deja solo ese).
 */
class ArbolDelModeloTest {
    /** Un PX3D v3 sin geometría, como lo escribe `Modelo3d::a_bytes` del PC. */
    private fun px3d(elementos: List<Modelo3D.Elemento>, niveles: List<Modelo3D.Nivel>, version: Int = 3): Modelo3D {
        val s = ByteArrayOutputStream()
        fun u(v: Int) = s.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun f(v: Float) = s.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
        fun d(v: Double) = s.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v).array())
        fun texto(t: String) { val b = t.toByteArray(); u(b.size); s.write(b) }
        s.write(byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), '3'.code.toByte(), 'D'.code.toByte(), 0, 0, 0, version.toByte()))
        repeat(3) { d(0.0) }
        repeat(6) { f(0f) }
        f(1f)
        u(0) // vértices
        repeat(5) { u(0) } // opacos, transparentes, aristas, líneas, puntos
        u(elementos.size)
        for (e in elementos) {
            texto(e.tipo); texto(e.nombre)
            u(e.nivel) // -1 = u32::MAX
            f(e.medidas.volumen); f(e.medidas.planta); f(e.medidas.alzado); f(e.medidas.superficie)
            s.write(if (e.medidas.cerrado) 1 else 0)
        }
        u(niveles.size)
        for (n in niveles) { texto(n.nombre); d(n.cota) }
        return Modelo3D.deBytes(ByteBuffer.wrap(s.toByteArray()))
    }

    /** El de las pruebas del PC: un muro y una losa en «1ER PISO», un muro sin nivel, y un nivel vacío. */
    private fun modelo() = px3d(
        listOf(
            Modelo3D.Elemento("IfcWall", "a", 0, Modelo3D.Medidas(volumen = 2f)),
            Modelo3D.Elemento("IfcSlab", "b", 0, Modelo3D.Medidas(volumen = 3f)),
            Modelo3D.Elemento("IfcWallStandardCase", "c", -1),
        ),
        listOf(Modelo3D.Nivel("1ER PISO", 3.4), Modelo3D.Nivel("VACIO", 9.0)),
    )

    @Test fun `el PX3D v3 trae niveles y medidas`() {
        val m = modelo()
        assertEquals(listOf("1ER PISO", "VACIO"), m.niveles.map { it.nombre })
        assertEquals(3.4, m.niveles[0].cota, 1e-12)
        assertEquals(listOf(0, 0, -1), m.elementos.map { it.nivel })
        assertEquals(3f, m.elementos[1].medidas.volumen)
    }

    @Test fun `un PX3D de otra version o con un nivel que no existe no se lee`() {
        for (malo in listOf(
            { px3d(listOf(Modelo3D.Elemento("IfcWall", "", 0)), emptyList()) },
            { px3d(emptyList(), emptyList(), version = 2) },
        )) {
            try { malo(); fail("debía fallar") } catch (_: Modelo3D.NoSeLee) {}
        }
    }

    @Test fun `agrupa por nivel y categoria sin los vacios`() {
        val ar = ArbolDelModelo.de(modelo(), "Sin nivel")
        // «VACIO» no sale; el muro sin nivel va a «Sin nivel».
        assertEquals(listOf("1ER PISO" to 2, "Sin nivel" to 1), ar.niveles.map { it.nombre to it.cuenta })
        // IfcWall e IfcWallStandardCase son los dos «Muro».
        assertEquals(listOf("Losa" to 1, "Muro" to 2), ar.tipos.map { it.nombre to it.cuenta })
        assertEquals(5.0, ar.niveles[0].volumen, 1e-9)
    }

    @Test fun `sin niveles no hay fila de sin nivel`() {
        val m = px3d(listOf(Modelo3D.Elemento("IfcWall", "", -1)), emptyList())
        val ar = ArbolDelModelo.de(m, "Sin nivel")
        assertTrue(ar.niveles.isEmpty())
        assertTrue(ar.visible(m.elementos[0], setOf(Clave.Nivel(-1))))
    }

    @Test fun `un toque oculta y mantener deja solo ese`() {
        val m = modelo()
        val ar = ArbolDelModelo.de(m, "Sin nivel")
        // Ocultar «Muro».
        var o = ar.alternar(emptySet(), Clave.Tipo("Muro"))
        assertTrue(!ar.visible(m.elementos[0], o) && ar.visible(m.elementos[1], o))
        // Mantener en «1ER PISO»: fuera el «sin nivel» (el tercer elemento), y «Muro» sigue oculto.
        o = ar.soloEse(o, Clave.Nivel(0))
        assertFalse(ar.visible(m.elementos[2], o))
        assertTrue(Clave.Tipo("Muro") in o)
        assertEquals(listOf<Byte>(1, 0, 1), ar.escondidos(m, o).toList())
        // Otro toque en «Muro»: vuelve; el sin nivel sigue fuera.
        o = ar.alternar(o, Clave.Tipo("Muro"))
        assertEquals(listOf<Byte>(0, 0, 1), ar.escondidos(m, o).toList())
        // Mostrar todo.
        assertTrue(m.elementos.all { ar.visible(it, emptySet()) })
    }

    @Test fun `la pastilla dice nivel, volumen y area`() {
        // La losa de 2 × 1 × 1 de la prueba del PC, con lo que mide su malla.
        val m = px3d(
            listOf(Modelo3D.Elemento("IfcSlab", "Losa 1", 0, Modelo3D.Medidas(2f, 2f, 3f, 10f, true))),
            listOf(Modelo3D.Nivel("2DO PISO", 6.65)),
        )
        assertEquals("Losa · Losa 1 · 2DO PISO · 2.00 m³ · 2.00 m²", ArbolDelModelo.describir(m.elementos[0], m, " m"))
        // Sin unidad conocida (un DWG), los números solos.
        assertTrue(ArbolDelModelo.describir(m.elementos[0], m, "").endsWith("2.00 · 2.00"))
        // Una malla abierta: el volumen, aproximado; un muro, el área de una cara.
        val abierto = Modelo3D.Elemento("IfcWall", "", -1, Modelo3D.Medidas(1f, 1f, 2f, 5f, false))
        assertEquals("Muro · ≈ 1.00 m³ · 2.00 m²", ArbolDelModelo.describir(abierto, m, "m"))
    }

    @Test fun `el IFC de Revit leido por el PC trae sus niveles y medidas`() {
        val m = Modelo3D.abrir(File(javaClass.classLoader!!.getResource("ifc/revit-ejemplo.px3d")!!.toURI()))
        assertTrue("sin niveles", m.niveles.isNotEmpty())
        assertEquals(m.niveles.sortedBy { it.cota }, m.niveles)
        assertTrue(m.elementos.any { it.nivel >= 0 })
        assertTrue(m.elementos.any { it.medidas.volumen > 0f && it.medidas.cerrado })
        val ar = ArbolDelModelo.de(m, "Sin nivel")
        assertEquals(m.elementos.size, ar.niveles.sumOf { it.cuenta })
        assertEquals(m.elementos.size, ar.tipos.sumOf { it.cuenta })
    }
}
