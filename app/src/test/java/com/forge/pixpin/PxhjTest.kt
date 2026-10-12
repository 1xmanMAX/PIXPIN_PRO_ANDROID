package com.forge.pixpin

import com.forge.pixpin.excel.ExcelTexto
import com.forge.pixpin.excel.Pxhj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **Los formatos PXHL, PXHG y PXHT** con que `libpixpinoffice.so` (la lectura de Excel del PC,
 * `crates/pixpin-hojas`) le pasa un libro a Kotlin. Los fixtures (`resources/excel/`) los escribió
 * el propio binario de Rust desde un libro hecho a mano —título combinado con relleno, número con
 * miles, fecha, texto en rojo, porcentaje, borde, fila alta, columna ancha, enlace y fila
 * inmovilizada—: `herramientas/pixpin-office-android/pruebas/hacer-fixtures-excel.sh` los rehace
 * (con la configuración regional `es`, punto decimal y coma de miles).
 */
class PxhjTest {
    private fun fixture(n: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("excel/$n")) { "falta excel/$n" }.use { it.readBytes() }

    @Test
    fun el_libro_trae_sus_dos_hojas() {
        val l = Pxhj.libro(fixture("prueba.pxhl"))
        assertEquals(listOf("Resumen", "Datos"), l.hojas.map { it.nombre })
        assertTrue(l.hojas.all { it.visible && it.tipo == 0 })
        assertEquals(0, l.activa)
    }

    @Test
    fun la_geometria_mide_como_excel() {
        val g = Pxhj.geometria(fixture("prueba.pxhg"))
        assertTrue(g.completa)
        // La fila 1 inmovilizada, y la celda activa B2.
        assertEquals(1, g.filasFijas)
        assertEquals(0, g.columnasFijas)
        assertEquals(1, g.filaActiva)
        assertEquals(1, g.columnaActiva)
        // Calibri 11: 64 px por columna y 20 por fila; la B, de 20 caracteres, 140 px (como Excel a 96 ppp).
        assertEquals(64, g.anchoDefecto)
        assertEquals(20, g.altoDefecto)
        assertEquals(64, g.ancho(0))
        assertEquals(140, g.ancho(1))
        // La fila 3 mide 30 puntos = 40 px; y más allá de lo leído, lo de por defecto.
        assertEquals(40, g.alto(2))
        assertEquals(20, g.alto(500))
        assertEquals(g.y(3) + 497L * 20, g.y(500))
        // De vuelta: la columna y la fila bajo un punto.
        assertEquals(1, g.columnaEn(64.0))
        assertEquals(1, g.columnaEn(203.0))
        assertEquals(2, g.columnaEn(204.0))
        assertEquals(2, g.filaEn(g.y(2) + 39.0))
        assertEquals(3, g.filaEn(g.y(3).toDouble()))
        assertEquals(10_000, g.filaEn(g.y(10_000) + 1.0))
        // La combinación del título y el enlace a la otra hoja.
        assertEquals(listOf(Pxhj.Rango(0, 0, 0, 2)), g.combinadas)
        assertEquals(Pxhj.Rango(0, 0, 0, 2), g.combinadaEn(0, 1))
        assertNull(g.combinadaEn(1, 1))
        val e = g.enlaceEn(2, 1)
        assertNotNull(e)
        assertEquals("Datos!A1", e!!.destino)
        assertFalse(e.externo)
        assertEquals(2, g.ultimaFila)
        assertEquals(2, g.ultimaColumna)
    }

    @Test
    fun el_trozo_trae_el_texto_ya_formateado_y_sus_estilos() {
        val t = Pxhj.trozo(fixture("prueba-0.pxht"))
        assertEquals(0, t.f0)
        assertEquals(63, t.f1)
        assertEquals(0, t.desde)
        fun celda(f: Int, c: Int) = t.fila(f)?.celda(c) ?: throw AssertionError("falta la celda $f,$c")
        fun estilo(f: Int, c: Int) = t.estilos[celda(f, c).estilo - t.desde]
        // El título: negrita de 14, sobre amarillo, centrado.
        assertEquals("Informe de obra", celda(0, 0).texto)
        assertEquals(2, celda(0, 0).clase)
        val titulo = estilo(0, 0)
        assertTrue(titulo.negrita)
        assertEquals(14f, titulo.puntos, 0.01f)
        assertEquals(0xFFFFFF00.toInt(), titulo.relleno)
        assertEquals(0xFF1F3864.toInt(), titulo.colorTexto)
        assertEquals(2, titulo.horizontal)
        assertEquals(1, titulo.vertical)
        // Las celdas vacías de la combinación vienen por su estilo (su relleno).
        assertEquals(0, celda(0, 1).clase)
        // Número con miles y dos decimales, a la derecha; la fecha con el formato del libro.
        assertEquals("1,234.50", celda(1, 0).texto)
        assertEquals(1, celda(1, 0).clase)
        assertEquals(3, estilo(1, 0).horizontal)
        assertEquals("15/03/2023", celda(1, 1).texto)
        // Texto en rojo en Arial; porcentaje.
        assertEquals("Texto en rojo", celda(1, 2).texto)
        assertEquals(0xFFFF0000.toInt(), estilo(1, 2).colorTexto)
        assertEquals("Arial", estilo(1, 2).familia)
        assertEquals("25%", celda(2, 0).texto)
        assertEquals("Avance", celda(2, 1).texto)
        // Lo que no hay no viene.
        assertNull(t.fila(10))
        assertNull(t.fila(1)!!.celda(5))
        // El borde de A2: fino a los lados y arriba; abajo, medio y azul.
        val lados = t.lados
        fun lado(tipo: Int, f: Int, c: Int): Pair<Int, Int>? {
            for (i in 0 until lados.size) if (lados.tipos[i].toInt() == tipo && lados.filas[i] == f && lados.columnas[i] == c) return lados.estilos[i].toInt() to lados.colores[i]
            return null
        }
        assertEquals(Pxhj.Linea.FINO to 0xFF000000.toInt(), lado(0, 1, 0))
        assertEquals(Pxhj.Linea.MEDIO to 0xFF0000FF.toInt(), lado(0, 2, 0))
        assertEquals(Pxhj.Linea.FINO to 0xFF000000.toInt(), lado(1, 1, 0))
        assertEquals(Pxhj.Linea.FINO to 0xFF000000.toInt(), lado(1, 1, 1))
        // De menos a más fuertes: el medio, el último.
        assertEquals(Pxhj.Linea.MEDIO, lados.estilos[lados.size - 1].toInt())
    }

    @Test
    fun un_formato_cortado_o_de_otra_cosa_no_se_lee() {
        val b = fixture("prueba-0.pxht")
        for (n in listOf(0, 3, 10, 40, b.size / 2, b.size - 1)) {
            try {
                Pxhj.trozo(b.copyOf(n))
                fail("se leyó un PXHT cortado a $n bytes")
            } catch (_: Pxhj.NoSeLee) {
            }
        }
        try {
            Pxhj.geometria(b)
            fail("un PXHT no es un PXHG")
        } catch (_: Pxhj.NoSeLee) {
        }
    }

    @Test
    fun referencias_y_enlaces_como_el_pc() {
        assertEquals("A", ExcelTexto.nombreColumna(0))
        assertEquals("Z", ExcelTexto.nombreColumna(25))
        assertEquals("AA", ExcelTexto.nombreColumna(26))
        assertEquals("XFD", ExcelTexto.nombreColumna(16383))
        assertEquals("B2", ExcelTexto.referencia(Pxhj.Rango(1, 1, 1, 1), false))
        assertEquals("A1:C3", ExcelTexto.referencia(Pxhj.Rango(0, 0, 2, 2), false))
        assertEquals("A1", ExcelTexto.referencia(Pxhj.Rango(0, 0, 0, 2), true))
        assertEquals("C:D", ExcelTexto.referencia(Pxhj.Rango(0, 2, Pxhj.MAX_FILAS - 1, 3), false))
        assertEquals("4:4", ExcelTexto.referencia(Pxhj.Rango(3, 0, 3, Pxhj.MAX_COLUMNAS - 1), false))
        assertEquals((11 to 1), ExcelTexto.celdaDe("\$B\$12"))
        assertNull(ExcelTexto.celdaDe("XFE1"))
        assertNull(ExcelTexto.celdaDe("A0"))
        assertEquals(ExcelTexto.Enlace.Web("https://pixpin.dev"), ExcelTexto.enlaceDe("https://pixpin.dev", true))
        // Solo web y correo: una ruta a un archivo podría ser cualquier cosa.
        assertNull(ExcelTexto.enlaceDe("C:\\programa.exe", true))
        assertEquals(ExcelTexto.Enlace.Sitio("Hoja de Max", 0 to 0), ExcelTexto.enlaceDe("#'Hoja de Max'!A1", false))
        assertEquals(ExcelTexto.Enlace.Sitio(null, 2 to 3), ExcelTexto.enlaceDe("D3", false))
        assertNull(ExcelTexto.enlaceDe("  ", false))
    }

    @Test
    fun la_fecha_corta_del_telefono_en_codigo_de_excel() {
        assertEquals("d/m/yyyy", com.forge.pixpin.excel.LibroExcel.fechaCorta("d/M/yy"))
        assertEquals("dd/mm/yyyy", com.forge.pixpin.excel.LibroExcel.fechaCorta("dd.MM.yyyy"))
        assertEquals("m/d/yyyy", com.forge.pixpin.excel.LibroExcel.fechaCorta("M/d/yy"))
        assertEquals("", com.forge.pixpin.excel.LibroExcel.fechaCorta("y年M月d日"))
    }
}
