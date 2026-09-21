package com.forge.pixpin

import com.forge.pixpin.motor.LectorIfc
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Diagnóstico con el IFC de verdad del usuario. Se salta si no está. */
class DiagnosticoIfcTest {
    private val archivo = File("/root/.claude/uploads/8a7d0245-243d-44b8-aee1-2f83d2ec34b2/a98806dc-PROYECTO_EDIFICIO_2.ifc")

    /**
     * **El descarte de caras traseras, con el modelo de verdad.** Si el signo estuviera al revés
     * se vería el interior del edificio en vez del exterior, o desaparecería: se comprueba que
     * desde cualquier lado se descarta cerca de la mitad y que **ninguna pieza se queda sin una
     * sola cara**. Ver `PintorDeMalla.haciaFuera`.
     */
    @Test fun `las caras de atras se descartan sin comerse ninguna pieza`() {
        assumeTrue(archivo.isFile)
        val malla = LectorIfc.leer(archivo)
        val v = malla.vertices; val ix = malla.triangulos
        val t = malla.cuantosTriangulos
        // El mismo volumen con signo que calcula el pintor.
        var seis = 0.0
        for (k in 0 until t) {
            val a = ix[k * 3] * 3; val b = ix[k * 3 + 1] * 3; val c = ix[k * 3 + 2] * 3
            val ax = v[a].toDouble(); val ay = v[a + 1].toDouble(); val az = v[a + 2].toDouble()
            val bx = v[b].toDouble(); val by = v[b + 1].toDouble(); val bz = v[b + 2].toDouble()
            val cx = v[c].toDouble(); val cy = v[c + 1].toDouble(); val cz = v[c + 2].toDouble()
            seis += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
        }
        val haciaFuera = if (seis < 0) -1.0 else 1.0
        println("volumen con signo = %.2f m3 -> haciaFuera=%.0f".format(seis / 6, haciaFuera))
        assertTrue("el modelo tiene que tener volumen", Math.abs(seis / 6) > 1.0)

        val normales = DoubleArray(t * 3)
        for (k in 0 until t) {
            val a = ix[k * 3] * 3; val b = ix[k * 3 + 1] * 3; val c = ix[k * 3 + 2] * 3
            val ux = (v[b] - v[a]).toDouble(); val uy = (v[b + 1] - v[a + 1]).toDouble(); val uz = (v[b + 2] - v[a + 2]).toDouble()
            val wx = (v[c] - v[a]).toDouble(); val wy = (v[c + 1] - v[a + 1]).toDouble(); val wz = (v[c + 2] - v[a + 2]).toDouble()
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            val l = Math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
            normales[k * 3] = nx / l; normales[k * 3 + 1] = ny / l; normales[k * 3 + 2] = nz / l
        }
        // La hondura crece hacia atrás: una cara se descarta si mira hacia allá.
        val miradas = listOf(
            doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.577, 0.577, 0.577), doubleArrayOf(-0.577, -0.577, 0.577)
        )
        for (f in miradas) {
            var fuera = 0
            val piezasVacias = ArrayList<String>()
            for (p in malla.piezas) {
                var suyas = 0
                for (k in p.desde until p.hasta) {
                    val mira = normales[k * 3] * f[0] + normales[k * 3 + 1] * f[1] + normales[k * 3 + 2] * f[2]
                    if (mira * haciaFuera > 0) fuera++ else suyas++
                }
                if (suyas == 0 && p.hasta > p.desde) piezasVacias += p.nombre
            }
            val porciento = 100.0 * fuera / t
            println("mirada %.1f,%.1f,%.1f -> descarta %.1f%%, piezas sin ninguna cara: %d".format(f[0], f[1], f[2], porciento, piezasVacias.size))
            // Mirando en planta, casi todas las caras de un edificio son verticales —muros,
            // vigas de canto— y quedan de perfil: ahí se descarta poco, y está bien. Lo que no
            // puede pasar nunca es que una pieza se quede sin una sola cara que pintar.
            assertTrue("desde esta mirada se descarta $porciento%, algo va mal", porciento in 10.0..75.0)
            assertTrue("piezas que desaparecen: " + piezasVacias.take(3), piezasVacias.isEmpty())
        }
    }

    @Test fun `como sale la malla`() {
        assumeTrue(archivo.isFile)
        val malla = LectorIfc.leer(archivo)
        val v = malla.vertices; val ix = malla.triangulos
        val t = malla.cuantosTriangulos
        val caja = malla.caja()
        println("TRIANGULOS=$t VERTICES=${v.size / 3} PIEZAS=${malla.piezas.size}")
        println("CAJA=%.2f %.2f %.2f .. %.2f %.2f %.2f".format(caja[0], caja[1], caja[2], caja[3], caja[4], caja[5]))
        val diagonal = Math.sqrt(
            Math.pow(caja[3] - caja[0], 2.0) + Math.pow(caja[4] - caja[1], 2.0) + Math.pow(caja[5] - caja[2], 2.0)
        )
        var degenerados = 0; var largos = 0; var agujas = 0
        var maxArista = 0.0
        val muestras = ArrayList<String>()
        for (k in 0 until t) {
            val a = ix[k * 3] * 3; val b = ix[k * 3 + 1] * 3; val c = ix[k * 3 + 2] * 3
            fun d(p: Int, q: Int): Double {
                val dx = (v[p] - v[q]).toDouble(); val dy = (v[p + 1] - v[q + 1]).toDouble(); val dz = (v[p + 2] - v[q + 2]).toDouble()
                return Math.sqrt(dx * dx + dy * dy + dz * dz)
            }
            val l1 = d(a, b); val l2 = d(b, c); val l3 = d(c, a)
            val mayor = maxOf(l1, l2, l3); val menor = minOf(l1, l2, l3)
            if (mayor > maxArista) maxArista = mayor
            // Área por Herón, con el semiperímetro.
            val s = (l1 + l2 + l3) / 2
            val area = Math.sqrt(Math.max(0.0, s * (s - l1) * (s - l2) * (s - l3)))
            if (area < 1e-9) degenerados++
            // Una aguja: larguísima y sin superficie para su largo.
            if (mayor > 1e-9 && area / (mayor * mayor) < 1e-4) {
                agujas++
                if (muestras.size < 5) muestras += "aguja k=$k largo=%.3f area=%.6f menor=%.4f".format(mayor, area, menor)
            }
            if (mayor > diagonal * 0.3) {
                largos++
                if (muestras.size < 10) muestras += "largo k=$k arista=%.2f (diagonal=%.2f)".format(mayor, diagonal)
            }
        }
        println("DEGENERADOS=$degenerados AGUJAS=$agujas LARGOS=$largos maxArista=%.2f diagonal=%.2f".format(maxArista, diagonal))
        muestras.forEach { println("  $it") }
        // Cuántos triángulos por pieza, y las piezas más gordas
        malla.piezas.sortedByDescending { it.hasta - it.desde }.take(8).forEach {
            println("  pieza '${it.nombre}' tipo='${it.tipo}' triangulos=${(it.hasta - it.desde)}")
        }
        val tipos = malla.piezas.groupBy { it.tipo }.mapValues { (_, v) -> v.size }.toList().sortedByDescending { it.second }
        println("TIPOS=" + tipos.joinToString { "${it.first}:${it.second}" })
    }
}
