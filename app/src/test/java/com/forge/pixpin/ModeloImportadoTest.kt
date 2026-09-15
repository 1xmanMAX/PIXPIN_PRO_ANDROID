package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.LectorIfc
import com.forge.pixpin.motor.LectorObj
import com.forge.pixpin.motor.Malla3D
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** **Los modelos importados en el croquis 3D.** Ver [Modelo3D], [LectorIfc] y [LectorObj]. */
class ModeloImportadoTest {

    @get:Rule val carpeta = TemporaryFolder()

    private fun ifc() = File(javaClass.classLoader!!.getResource("ifc/revit-ejemplo.ifc")!!.toURI())

    @Test
    fun `la malla se guarda y se vuelve a leer igual`() {
        val malla = LectorIfc.leer(ifc())
        val f = carpeta.newFile("m.malla")
        malla.guardar(f)
        val otra = Malla3D.cargar(f)!!
        assertArrayEquals(malla.vertices, otra.vertices, 0f)
        assertArrayEquals(malla.triangulos, otra.triangulos)
        assertArrayEquals(malla.colores, otra.colores)
        assertEquals(malla.piezas.size, otra.piezas.size)
        assertEquals(malla.piezas[3].nombre, otra.piezas[3].nombre)
    }

    @Test
    fun `un OBJ con la y hacia arriba sale con la z hacia arriba`() {
        val f = carpeta.newFile("cubo.obj")
        f.writeText(
            """
            o caja
            v 0 0 0
            v 1 0 0
            v 1 2 0
            v 0 2 0
            f 1 2 3 4
            """.trimIndent()
        )
        val m = LectorObj.leer(f)
        assertEquals(2, m.cuantosTriangulos)
        val c = m.caja()
        // Lo alto del OBJ (y = 2) es lo alto del croquis (z = 2).
        assertEquals(2.0, c[5] - c[2], 1e-6)
        assertEquals(0.0, c[4] - c[1], 1e-6)
    }

    @Test
    fun `el modelo se pone apoyado en el suelo a escala real y se maneja`() {
        val c = Croquis3DControlador().apply { medida(1080.0, 1920.0) }
        val malla = LectorIfc.leer(ifc())
        val caja = malla.caja()
        c.ponerModelo("/tmp/m.malla", "Edificio", caja, malla.cuantosTriangulos)
        val m = c.croquis.modelos.single()
        assertEquals(setOf(m.id), c.seleccion)
        val esquinas = m.esquinas()
        // Apoyado en el suelo.
        assertEquals(0.0, esquinas.minOf { it.z }, 1e-6)
        // Un metro, un cuadro del suelo.
        assertEquals((caja[5] - caja[2]) * METRO, esquinas.maxOf { it.z } - esquinas.minOf { it.z }, 1e-6)

        c.empezarAManejar()
        c.moverLaSeleccionPorElEje(EjeDelMundo.Z, 100.0)
        val subido = c.croquis.modelos.single()
        assertTrue(subido.esquinas().minOf { it.z } > 0.0)
        // Girar no lo deforma: los ejes siguen midiendo un metro.
        c.girarLaSeleccionConLaMano(60.0, 30.0)
        val girado = c.croquis.modelos.single()
        assertEquals(METRO, com.forge.pixpin.croquis3d.largo(girado.ejeX), 1e-6)
        assertEquals(METRO, com.forge.pixpin.croquis3d.largo(girado.ejeZ), 1e-6)

        c.borrarLaSeleccion()
        assertTrue(c.croquis.modelos.isEmpty())
    }

    @Test
    fun `un modelo va en su grupo, se esconde con el y se elige desde la lista`() {
        val c = Croquis3DControlador().apply { medida(1080.0, 1920.0) }
        val malla = LectorIfc.leer(ifc())
        c.ponerModelo("/tmp/a.malla", "A", malla.caja(), malla.cuantosTriangulos)
        val a = c.croquis.modelos.single().id
        c.ponerModelo("/tmp/b.malla", "B", malla.caja(), malla.cuantosTriangulos)
        val b = c.croquis.modelos.last().id
        c.elegir(setOf(a, b))
        c.agruparLaSeleccion()
        val grupo = c.croquis.grupos.single().id
        assertTrue(c.croquis.modelos.all { it.grupo == grupo })
        assertEquals(setOf(a, b), c.idsDelGrupo(grupo))
        c.ocultarElGrupo(grupo, true)
        assertTrue(c.grupoEscondido(grupo))
        assertTrue(c.croquis.modelos.all { it.oculto })
        c.ocultarElGrupo(grupo, false)
        c.elegir(emptySet())
        // Desde la lista se coge el modelo y, con él, su grupo.
        c.elegirElModelo(a)
        assertEquals(setOf(a, b), c.seleccion)
        c.desagrupar(grupo)
        assertTrue(c.croquis.modelos.all { it.grupo == null })
        c.ocultarElModelo(b, true)
        assertTrue(c.croquis.modelos.first { it.id == b }.oculto)
        c.quitarElModelo(b)
        assertEquals(listOf(a), c.croquis.modelos.map { it.id })
    }

    @Test
    fun `el modelo sale en el OBJ y en la pagina web, ya colocado`() {
        val malla = LectorIfc.leer(ifc())
        val f = carpeta.newFile("m.malla")
        malla.guardar(f)
        val c = Croquis3DControlador().apply { medida(1080.0, 1920.0) }
        c.ponerModelo(f.absolutePath, "Edificio", malla.caja(), malla.cuantosTriangulos)
        val croquis = c.croquis
        val leer = { ruta: String -> Malla3D.cargar(File(ruta)) }

        val obj = ExportarObj.escribir(croquis, "prueba", leer)!!
        assertEquals(malla.cuantosTriangulos, obj.caras)
        assertEquals(malla.vertices.size / 3, obj.vertices)
        assertTrue(obj.obj.lines().count { it.startsWith("f ") } == malla.cuantosTriangulos)
        // Sin poder leer la malla no hay nada que exportar.
        assertEquals(null, ExportarObj.escribir(croquis, "prueba"))

        val datos = ExportarCroquisHtml.datos(croquis, Camara3D(), malla = leer)!!
        val v = Regex("\"mo\":\\[\\{\"n\":\"Edificio\",\"v\":\"([^\"]+)\",\"t\":\"([^\"]+)\",\"k\":\"([^\"]+)\"").find(datos)!!
        val dec = java.util.Base64.getDecoder()
        assertEquals(malla.vertices.size * 4, dec.decode(v.groupValues[1]).size)
        assertEquals(malla.triangulos.size * 4, dec.decode(v.groupValues[2]).size)
        assertEquals(malla.cuantosTriangulos * 4, dec.decode(v.groupValues[3]).size)
        // El primer vértice, ya en el mundo.
        val bb = java.nio.ByteBuffer.wrap(dec.decode(v.groupValues[1])).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val m = croquis.modelos.single()
        val esperado = m.alMundo(malla.vertices[0].toDouble(), malla.vertices[1].toDouble(), malla.vertices[2].toDouble())
        assertEquals(esperado.x, bb.getFloat(0).toDouble(), 1e-2)
        assertEquals(esperado.z, bb.getFloat(8).toDouble(), 1e-2)
        java.io.File(System.getProperty("java.io.tmpdir"), "pixpin-modelo-datos.json").writeText(datos)
    }
}
