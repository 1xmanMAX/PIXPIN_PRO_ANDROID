package com.forge.pixpin

import com.forge.pixpin.motor.LectorIfc
import com.forge.pixpin.motor.Malla3D
import com.forge.pixpin.planos.Modelo3D
import com.forge.pixpin.planos.ModeloAMalla
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **Del visor 3D al croquis** (10-oct-2026): el modelo leído con la lectura del PC
 * (`revit-ejemplo.px3d`, hecho con `convertir-plano --3d` del mismo `revit-ejemplo.ifc`) pasa a la
 * malla del croquis entero, en metros, con cada elemento en su tramo.
 */
class ModeloAMallaTest {
    private fun recurso(n: String) = File(javaClass.classLoader!!.getResource(n)!!.toURI())

    @Test fun `el modelo del PC pasa a la malla del croquis con sus piezas`() {
        val m = Modelo3D.abrir(recurso("ifc/revit-ejemplo.px3d"))
        val malla = ModeloAMalla.malla(m)
        assertTrue("tiene que tener caras", malla.cuantosTriangulos > 1000)
        // Las piezas, seguidas y sin solaparse, cubriendo todos los triángulos.
        var hasta = 0
        for (p in malla.piezas) { assertEquals(hasta, p.desde); assertTrue(p.hasta > p.desde); hasta = p.hasta }
        assertEquals(malla.cuantosTriangulos, hasta)
        assertTrue("los tipos van en mayúsculas, como los del croquis", malla.piezas.all { it.tipo == it.tipo.uppercase() })
        assertTrue(malla.piezas.any { it.tipo.startsWith("IFCWALL") })
        // La misma caja que el modelo (en metros, relativa a su origen).
        val c = malla.caja()
        for (k in 0 until 3) {
            assertEquals(m.caja[k].toDouble(), c[k], 0.05)
            assertEquals(m.caja[k + 3].toDouble(), c[k + 3], 0.05)
        }
    }

    /**
     * **Dónde deja cada lector cada pieza.** El usuario veía su edificio «descolocado» en el croquis
     * (leído con [LectorIfc]); en Windows, con ifc-lite, bien. Se comparan los centros de las piezas
     * del mismo nombre una vez quitado el desplazamiento general (los dos orígenes no son el mismo).
     */
    @Test fun `diagnostico del lector del croquis contra el del PC`() {
        comparar(recurso("ifc/revit-ejemplo.ifc"), recurso("ifc/revit-ejemplo.px3d"))
        val suyo = File("/root/.claude/uploads/8a7d0245-243d-44b8-aee1-2f83d2ec34b2/a98806dc-PROYECTO_EDIFICIO_2.ifc")
        val suyoPx3d = File(System.getenv("PIXPIN_EDIFICIO_PX3D") ?: "/nada")
        assumeTrue(suyo.isFile && suyoPx3d.isFile)
        comparar(suyo, suyoPx3d)
    }

    private fun centros(m: Malla3D): Map<String, DoubleArray> {
        val porNombre = HashMap<String, DoubleArray>()
        for (p in m.piezas) {
            val s = porNombre.getOrPut(p.tipo.uppercase().removeSuffix("STANDARDCASE") + "|" + p.nombre) { DoubleArray(4) }
            for (t in p.desde until p.hasta) for (j in 0 until 3) {
                val v = m.triangulos[t * 3 + j] * 3
                s[0] += m.vertices[v].toDouble(); s[1] += m.vertices[v + 1].toDouble(); s[2] += m.vertices[v + 2].toDouble(); s[3] += 1.0
            }
        }
        return porNombre.mapValues { (_, s) -> doubleArrayOf(s[0] / s[3], s[1] / s[3], s[2] / s[3]) }
    }

    private fun comparar(ifc: File, px3d: File) {
        val viejo = LectorIfc.leer(ifc)
        val nuevo = ModeloAMalla.malla(Modelo3D.abrir(px3d))
        val a = centros(viejo); val b = centros(nuevo)
        val comunes = a.keys.intersect(b.keys).filter { !it.endsWith("|") }
        // El desplazamiento general: la mediana de las diferencias.
        val d = DoubleArray(3) { k -> comunes.map { b[it]!![k] - a[it]!![k] }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] } }
        val lejos = comunes.map { n -> n to (0 until 3).maxOf { k -> Math.abs(b[n]!![k] - a[n]!![k] - d[k]) } }.sortedByDescending { it.second }
        println("${ifc.name}: ${viejo.piezas.size} piezas (croquis) / ${nuevo.piezas.size} (PC); comunes ${comunes.size}; desplazamiento ${d.joinToString { "%.2f".format(it) }}")
        println("  caja croquis ${viejo.caja().joinToString { "%.2f".format(it) }}")
        println("  caja PC      ${nuevo.caja().joinToString { "%.2f".format(it) }}")
        println("  más de 0,5 m fuera de sitio: ${lejos.count { it.second > 0.5 }}")
        lejos.take(12).forEach { (n, e) -> println("    %-60s %.2f m".format(n.take(60), e)) }
    }
}
