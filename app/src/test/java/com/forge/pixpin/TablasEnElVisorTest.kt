package com.forge.pixpin

import com.forge.pixpin.excel.Pxhj
import com.forge.pixpin.excel.TablasEnElVisor
import com.forge.pixpin.motor.EstiloDeCelda
import com.forge.pixpin.motor.TablaDeCalculo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las tablas de PixPin en el visor de hojas** (25-oct-2026): lo que llega a [com.forge.pixpin.excel.VistaHoja]
 * desde una [TablaDeCalculo] tiene que ser lo que enseñaría el PC (`pixpin_proyecto::tabla_xlsx`):
 * valores calculados, celdas vacías fuera, anchos, formato y una pestaña por tabla.
 */
class TablasEnElVisorTest {

    private fun tabla() = TablaDeCalculo(
        nombre = "Cuentas · Gastos",
        celdas = mapOf(
            "A1" to "Concepto", "B1" to "Importe",
            "A2" to "Luz", "B2" to "40",
            "A3" to "Agua", "B3" to "2.5",
            "A5" to "Total", "B5" to "=SUMA(B2:B3)",
            "C5" to "=1/0"
        ),
        anchos = mapOf("A" to 180),
        estilos = mapOf(
            "A1" to EstiloDeCelda(n = true, f = "#FFEB9C"),
            "B5" to EstiloDeCelda(n = true, a = "c", c = "#C00000"),
            "D7" to EstiloDeCelda(f = "#DDEBF7")
        )
    )

    @Test
    fun los_valores_salen_calculados_y_las_formulas_no_se_ven() {
        val h = TablasEnElVisor.hoja(tabla())
        assertEquals("Gastos", h.nombre)
        val total = h.fila(4)!!.celda(1)!!
        assertEquals("42.5", total.texto.replace(',', '.'))
        assertEquals(1, total.clase)
        assertEquals("Concepto", h.fila(0)!!.celda(0)!!.texto)
        assertEquals(2, h.fila(0)!!.celda(0)!!.clase)
        // Un error se ve como error, centrado (como Excel con «General»).
        val err = h.fila(4)!!.celda(2)!!
        assertEquals(4, err.clase)
        assertTrue(err.texto.startsWith("#"))
        assertEquals(2, h.estilos[err.estilo].horizontal)
    }

    @Test
    fun las_celdas_vacias_no_llegan_y_una_fila_vacia_tampoco() {
        val h = TablasEnElVisor.hoja(tabla())
        assertNull(h.fila(3))
        assertNull(h.fila(1)!!.celda(2))
        assertEquals(listOf(0, 1), h.fila(1)!!.celdas.map { it.col })
        // Una celda vacía con fondo sí llega (el fondo se pinta), sin texto.
        val d7 = h.fila(6)!!.celda(3)!!
        assertEquals("", d7.texto)
        assertEquals(0, d7.clase)
        assertEquals(0xFFDDEBF7.toInt(), h.estilos[d7.estilo].relleno)
    }

    @Test
    fun los_anchos_y_las_medidas_son_los_del_pc() {
        val h = TablasEnElVisor.hoja(tabla())
        val g = h.geometria
        assertEquals(180, g.ancho(0))
        assertEquals(TablasEnElVisor.ANCHO_COLUMNA, g.ancho(1))
        assertEquals(TablasEnElVisor.ALTO_FILA, g.alto(0))
        // Hasta la D (la del fondo) y la fila 7.
        assertEquals(3, g.ultimaColumna)
        assertEquals(6, g.ultimaFila)
        assertEquals(180 + 3 * 88, g.anchoUsado)
        assertTrue(g.completa)
        assertTrue(g.cuadricula && g.encabezados)
        // Más allá de lo usado, columnas y filas por defecto.
        assertEquals(TablasEnElVisor.ANCHO_COLUMNA, g.ancho(40))
    }

    @Test
    fun el_formato_llega_al_estilo() {
        val h = TablasEnElVisor.hoja(tabla())
        val a1 = h.estilos[h.fila(0)!!.celda(0)!!.estilo]
        assertTrue(a1.negrita)
        assertEquals(0xFFFFEB9C.toInt(), a1.relleno)
        assertEquals("Calibri", a1.familia)
        assertEquals(11f, a1.puntos)
        val b5 = h.estilos[h.fila(4)!!.celda(1)!!.estilo]
        assertEquals(2, b5.horizontal)
        assertEquals(0xFFC00000.toInt(), b5.colorTexto)
        // Sin alineación propia: números a la derecha, texto a la izquierda.
        assertEquals(3, h.estilos[h.fila(1)!!.celda(1)!!.estilo].horizontal)
        assertEquals(1, h.estilos[h.fila(1)!!.celda(0)!!.estilo].horizontal)
        // Los estilos iguales se comparten.
        assertEquals(h.fila(1)!!.celda(1)!!.estilo, h.fila(2)!!.celda(1)!!.estilo)
        assertEquals(h.estilos.size, h.estilos.toSet().size)
    }

    @Test
    fun los_trozos_traen_sus_filas_y_los_estilos_que_faltan() {
        val h = TablasEnElVisor.hoja(tabla())
        val t = h.trozo(0, 63, 0)
        assertEquals(0, t.desde)
        assertEquals(h.estilos.size, t.estilos.size)
        assertNotNull(t.fila(4))
        assertEquals(0, t.lados.size)
        val otro = h.trozo(64, 127, h.estilos.size)
        assertTrue(otro.estilos.isEmpty())
        assertTrue(otro.filas.isEmpty())
        assertEquals(h.estilos.size, otro.desde)
    }

    @Test
    fun copiar_da_lo_que_se_ve_con_tabuladores() {
        val h = TablasEnElVisor.hoja(tabla())
        assertEquals("Concepto\tImporte\nLuz\t40", h.copiar(0, 0, 1, 1))
    }

    @Test
    fun varias_hojas_hacen_un_libro_con_sus_pestanas() {
        val a = TablasEnElVisor.hoja(tabla())
        val b = TablasEnElVisor.hoja(TablaDeCalculo(nombre = "Cuentas · Ingresos", celdas = mapOf("A1" to "x")))
        val vacia = TablasEnElVisor.hoja(TablaDeCalculo(nombre = "Notas"))
        val libro = TablasEnElVisor.libro(listOf(a, b, vacia), activa = 1)
        assertEquals(listOf("Gastos", "Ingresos", "Notas"), libro.hojas.map { it.nombre })
        assertTrue(libro.hojas.all { it.visible && it.tipo == 0 })
        assertEquals(1, libro.activa)
        // Una tabla vacía se ve: una rejilla sin nada.
        assertTrue(vacia.filas.isEmpty())
        assertEquals(0, vacia.geometria.ultimaFila)
        assertEquals(0, TablasEnElVisor.libro(listOf(a), activa = 7).activa)
    }

    @Test
    fun nombres_de_pestana_y_colores_como_el_pc() {
        assertEquals("Gastos", TablasEnElVisor.nombreDePestana("Cuentas · Gastos"))
        assertEquals("Suelta", TablasEnElVisor.nombreDePestana("Suelta"))
        assertEquals("Tabla", TablasEnElVisor.nombreDePestana(""))
        assertEquals(0xFF112233.toInt(), TablasEnElVisor.color("#112233"))
        assertNull(TablasEnElVisor.color("rojo"))
        assertNull(TablasEnElVisor.color(null))
        assertFalse(TablasEnElVisor.estilo(null, 1).negrita)
        assertEquals(0xFF000000.toInt(), TablasEnElVisor.estilo(null, 1).colorTexto)
        assertEquals(0, TablasEnElVisor.estilo(null, 1).relleno)
    }

    @Test
    fun la_tabla_no_se_toca_al_verla() {
        val t = tabla()
        val copia = t.copy()
        TablasEnElVisor.hoja(t)
        assertEquals(copia, t)
        assertEquals(TablaDeCalculo.aJson(copia), TablaDeCalculo.aJson(t))
        // Un Pxhj.Rango sigue siendo lo de siempre (el visor elige igual en una tabla).
        assertEquals(4L, Pxhj.Rango(0, 0, 1, 1).celdas)
    }
}
