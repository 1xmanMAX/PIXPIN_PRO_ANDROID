package com.forge.pixpin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.forge.pixpin.excel.IconosExcel
import com.forge.pixpin.excel.Pxhj
import com.forge.pixpin.office.Oscuro
import com.forge.pixpin.office.PintorOffice
import com.forge.pixpin.office.Pxof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Lo de los visores de Office del PC del 11 al 13-oct** (954e77e, f38c011, 9975385, 74801b4):
 * efectos de DrawingML y caras teñidas en PXOF v2, lo nuevo de Excel en PXHG/PXHT v2 (barras,
 * iconos, minigráficos, notas, autofiltro, listas, `.xls` antiguo) y el modo oscuro.
 *
 * Los fixtures los escribió el .so (`herramientas/pixpin-office-android/pruebas/hacer-fixtures.sh` y
 * `hacer-fixtures-excel.sh`). El pintado se mira **contando lo que se le pide al lienzo** y de qué
 * color (Robolectric no rasteriza aquí: ver `RendererTodoLoQuePintaTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisoresDeOfficeTest {
    private fun fixture(n: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(n)) { "falta $n" }.use { it.readBytes() }

    private fun todas(els: List<Pxof.Elemento>): List<Pxof.Elemento> = els.flatMap {
        when (it) {
            is Pxof.Grupo -> todas(it.elementos) + it
            is Pxof.Efectos -> todas(it.elementos) + it
            else -> listOf(it)
        }
    }

    /** Un lienzo que no pinta: apunta cada orden con el color de su pintura. */
    private class LienzoQueApunta(b: Bitmap) : Canvas(b) {
        val ordenes = ArrayList<Pair<String, Int>>()
        override fun drawPath(path: Path, paint: Paint) { ordenes += "camino" to paint.color }
        override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { ordenes += "raya" to paint.color }
        override fun drawLines(pts: FloatArray, off: Int, count: Int, paint: Paint) { ordenes += "rayas" to paint.color }
        override fun drawCircle(cx: Float, cy: Float, radio: Float, paint: Paint) { ordenes += "círculo" to paint.color }
        override fun drawArc(rect: RectF, desde: Float, barrido: Float, centro: Boolean, paint: Paint) { ordenes += "arco" to paint.color }
        override fun drawArc(l: Float, t: Float, r: Float, b: Float, desde: Float, barrido: Float, centro: Boolean, paint: Paint) { ordenes += "arco" to paint.color }
        override fun drawRect(rect: RectF, paint: Paint) { ordenes += "rect" to paint.color }
        override fun drawRect(rect: Rect, paint: Paint) { ordenes += "rect" to paint.color }
        override fun drawRect(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) { ordenes += "rect" to paint.color }
        override fun drawText(t: String, x: Float, y: Float, p: Paint) { ordenes += "texto" to p.color }
        override fun drawText(t: String, ini: Int, fin: Int, x: Float, y: Float, p: Paint) { ordenes += "texto" to p.color }
        override fun drawGlyphs(
            glyphIds: IntArray, glyphIdOffset: Int, positions: FloatArray, positionOffset: Int, glyphCount: Int,
            font: android.graphics.fonts.Font, paint: Paint
        ) { ordenes += "texto" to paint.color }
        override fun drawBitmap(b: Bitmap, x: Float, y: Float, p: Paint?) { ordenes += "mapa" to (p?.color ?: 0) }
        override fun drawBitmap(b: Bitmap, m: Matrix, p: Paint?) { ordenes += "mapa" to (p?.color ?: 0) }
        override fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, p: Paint?) { ordenes += "mapa" to (p?.color ?: 0) }
        override fun drawBitmap(b: Bitmap, src: Rect?, dst: Rect, p: Paint?) { ordenes += "mapa" to (p?.color ?: 0) }
    }

    private fun pintar(p: Pxof.Pagina, oscuro: Boolean): List<Pair<String, Int>> {
        val l = LienzoQueApunta(Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888))
        PintorOffice { _, _ -> null }.pintar(l, p, 0.5f, 1, oscuro = oscuro)
        return l.ordenes
    }

    // --------------------------------------------------------------------------------- PXOF v2

    @Test
    fun la_diapositiva_con_efectos_los_trae_todos() {
        val p = Pxof.leer(fixture("office/powerpoint-2.pxof"))
        val efectos = todas(p.elementos).filterIsInstance<Pxof.Efectos>().flatMap { it.efectos }
        assertTrue(efectos.joinToString { it.javaClass.simpleName }, efectos.any { it is Pxof.Efecto.SombraExterior })
        assertTrue(efectos.any { it is Pxof.Efecto.Brillo })
        assertTrue(efectos.any { it is Pxof.Efecto.BordeSuave })
        assertTrue(efectos.any { it is Pxof.Efecto.SombraInterior })
        // La sombra: negra al 40 %, 3 puntos hacia abajo a la derecha (45°), desenfoque de 4 puntos.
        val s = efectos.filterIsInstance<Pxof.Efecto.SombraExterior>().first()
        assertEquals(0x66, s.argb ushr 24)
        assertEquals(0, s.argb and 0xFFFFFF)
        assertEquals(4f, s.desenfoque, 0.05f)
        assertTrue("${s.dx}, ${s.dy}", s.dx > 2f && s.dy > 2f)
        val b = efectos.filterIsInstance<Pxof.Efecto.Brillo>().first()
        assertEquals(0xFFC000, b.argb and 0xFFFFFF)
        assertEquals(5f, b.radio, 0.05f)
        // El texto de dentro de un efecto también se encuentra (buscar, copiar, enlaces).
        assertTrue(p.textoPlano(), p.textoPlano().contains("Sombra"))
    }

    @Test
    fun el_cubo_trae_sus_caras_tenidas() {
        val p = Pxof.leer(fixture("office/powerpoint-2.pxof"))
        val figuras = todas(p.elementos).filterIsInstance<Pxof.Trayecto>().flatMap { it.figuras }
        val tintes = figuras.map { it.tinte }.toSet()
        // El cubo de Office: la cara de delante sin tinte, la de arriba clara y la del lado oscura.
        assertTrue("tintes: $tintes", 0 in tintes && tintes.any { it in 1..4 })
        assertTrue(figuras.all { it.tinte in 0..5 })
    }

    @Test
    fun el_pintor_pinta_los_tintes_y_los_efectos_sin_perder_lo_envuelto() {
        val p = Pxof.leer(fixture("office/powerpoint-2.pxof"))
        val o = pintar(p, oscuro = false)
        // Las caras del cubo, con su tinte encima (los colores del PC).
        val tintes = setOf(0x2EFFFFFF, 0x5CFFFFFF, 0x2E000000, 0x66000000)
        assertTrue(o.joinToString(), o.any { it.first == "camino" && it.second in tintes })
        // Lo envuelto en efectos se pinta igual (en vectores o desde su mapa, con borde suave).
        assertTrue(o.any { it.first == "texto" })
        assertTrue(o.count { it.first == "camino" || it.first == "rect" || it.first == "mapa" } >= 5)
    }

    @Test
    fun en_oscuro_la_hoja_es_papel_oscuro_y_el_texto_negro_se_aclara() {
        val p = Pxof.leer(fixture("office/powerpoint-2.pxof"))
        val claro = pintar(p, oscuro = false)
        val oscuro = pintar(p, oscuro = true)
        // El fondo de la hoja: blanco en claro, el papel oscuro del PC en oscuro.
        assertEquals("rect" to 0xFFFFFFFF.toInt(), claro.first())
        assertEquals("rect" to Oscuro.PAPEL, oscuro.first())
        // El texto negro suelto (sin relleno debajo): claro en oscuro.
        val negro = 0xFF202020.toInt()
        assertTrue(claro.joinToString(), claro.any { it.first == "texto" && it.second == negro })
        // («Sombra», sobre su relleno azul, sigue negro: lo puso así su autor y se lee.)
        fun negros(o: List<Pair<String, Int>>) = o.count { it.first == "texto" && it.second == negro }
        assertTrue(negros(oscuro) in 1 until negros(claro))
        assertTrue(oscuro.any { it.first == "texto" && Oscuro.luz(it.second) > 0.8f })
        // Caso negativo: los rellenos de color se quedan como los puso su autor.
        val gris = 0xFFD9D9D9.toInt()
        assertTrue(oscuro.any { it.second == gris })
        // Una diapositiva con fondo de color (la primera) se ve como la diseñó su autor.
        val primera = pintar(Pxof.leer(fixture("office/powerpoint-1.pxof")), oscuro = true)
        assertEquals("rect" to 0xFFF4F1EA.toInt(), primera.first())
    }

    @Test
    fun el_texto_sobre_un_relleno_se_queda_en_oscuro() {
        val p = Pxof.leer(fixture("office/powerpoint-1.pxof"))
        // La primera diapositiva lleva fondo propio: nada se toca.
        assertEquals(pintar(p, false), pintar(p, true))
    }

    // ------------------------------------------------------------------------------- oscuro

    @Test
    fun las_cuentas_del_oscuro_son_las_del_pc() {
        // El blanco pasa al papel oscuro y el negro a la tinta clara (ni negro ni blanco puros).
        assertEquals(Oscuro.PAPEL, Oscuro.invertirLuz(0xFFFFFFFF.toInt()))
        assertEquals(Oscuro.TINTA, Oscuro.invertirLuz(0xFF000000.toInt()))
        // Un gris sigue gris.
        val g = Oscuro.invertirLuz(0xFF666666.toInt())
        assertTrue(kotlin.math.abs(((g shr 16) and 0xff) - ((g shr 8) and 0xff)) <= 5)
        // Un rojo oscuro sale claro y sigue rojo (el tono se queda).
        val r = Oscuro.invertirLuz(0xFFDB1F1F.toInt())
        assertTrue(((r shr 16) and 0xff) > ((r shr 8) and 0xff) + 50)
        // Lo que ya se lee no cambia; el negro automático sí.
        assertEquals(0xFFFFE633.toInt(), Oscuro.legible(0xFFFFE633.toInt()))
        assertTrue(Oscuro.luz(Oscuro.legible(0xFF000000.toInt())) > 0.8f)
        // Las rayas: la negra se aclara; la azul de un gráfico se queda (como `raya_legible_u8`).
        assertTrue(((Oscuro.rayaLegible(0xFF000000.toInt()) shr 16) and 0xff) > 200)
        assertEquals(0xFF4472C4.toInt(), Oscuro.rayaLegible(0xFF4472C4.toInt()))
        // Casi blanco es papel; un amarillo claro o un gris medio no.
        assertTrue(Oscuro.casiBlanco(0xFFF2F2F2.toInt()))
        assertTrue(!Oscuro.casiBlanco(0xFFFFF2CC.toInt()))
        assertTrue(!Oscuro.casiBlanco(0xFFD9D9D9.toInt()))
        assertTrue(!Oscuro.casiBlanco(0x28FFFFFF))
    }

    // ------------------------------------------------------------------------------- Excel v2

    @Test
    fun excel_barras_iconos_minigrafico_y_nota() {
        val t = Pxhj.trozo(fixture("excel/extras-0.pxht"))
        fun celda(f: Int, c: Int) = t.fila(f)?.celda(c) ?: throw AssertionError("falta la celda $f,$c")
        // Barras de datos en A2:A7 (3, 8, -2, 5, 10, 1): la del 10 llega al final; con el negativo,
        // la barra arranca en el eje.
        val b10 = requireNotNull(celda(5, 0).barra)
        // El largo máximo de Excel: el 90 % de la celda.
        assertEquals(0.9f, b10.hasta, 0.01f)
        assertEquals(0xFF638EC6.toInt(), b10.argb)
        assertTrue(b10.degradado)
        val bNeg = requireNotNull(celda(3, 0).barra)
        assertTrue("${bNeg.desde}..${bNeg.hasta}", bNeg.hasta > bNeg.desde)
        // Iconos: tres flechas; el 100 (máximo) es la mejor y el -20 la peor.
        assertEquals(IconosExcel.FLECHAS_3, celda(5, 1).conjunto)
        assertEquals(2, celda(5, 1).icono)
        assertEquals(0, celda(3, 1).icono)
        assertEquals(-1, celda(5, 0).conjunto)
        // El minigráfico de columnas de C2, con los seis valores y el eje que abarca el cero.
        val m = t.minis.single()
        assertEquals(1 to 2, m.fila to m.col)
        assertEquals(1, m.tipo)
        assertEquals(listOf(3f, 8f, -2f, 5f, 10f, 1f), m.puntos.toList())
        assertTrue(m.minimo <= -2.0 && m.maximo >= 10.0)
        assertEquals(0xFF376092.toInt(), m.colores[0])
        assertEquals(0xFFD00000.toInt(), m.colores[1])
        // La nota de A2.
        val n = requireNotNull(t.comentario(1, 0))
        assertEquals("Luis", n.autor)
        assertEquals("Revisar este valor", n.texto)
        assertTrue(n.completo().contains("Luis: Revisar"))
        assertNull(t.comentario(1, 1))
    }

    @Test
    fun excel_autofiltro_y_listas_en_la_geometria() {
        val g = Pxhj.geometria(fixture("excel/extras.pxhg"))
        // Botones en A1, B1 y C1; el filtro puesto, en B.
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2), g.botones.map { it.fila to it.col })
        assertEquals(listOf(false, true, false), g.botones.map { it.filtrado })
        // La lista desplegable de D2:D7.
        assertTrue(g.conLista(1, 3) && g.conLista(6, 3))
        assertTrue(!g.conLista(0, 3) && !g.conLista(1, 2))
    }

    @Test
    fun un_xls_antiguo_se_lee_como_un_libro() {
        val l = Pxhj.libro(fixture("excel/viejo.pxhl"))
        assertTrue(l.hojas.isNotEmpty())
        val t = Pxhj.trozo(fixture("excel/viejo-0.pxht"))
        assertTrue(t.filas.isNotEmpty())
        assertTrue(t.filas.any { f -> f.celdas.any { it.texto.isNotEmpty() } })
    }

    @Test
    fun los_veinte_conjuntos_de_iconos_pintan_algo() {
        for (conjunto in 0 until 20) {
            for (i in 0 until IconosExcel.cantidad(conjunto)) {
                val l = LienzoQueApunta(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888))
                IconosExcel.pintar(l, conjunto, i, 16f, 16f, 15f)
                assertTrue("conjunto $conjunto, icono $i: mudo", l.ordenes.isNotEmpty())
            }
        }
        assertEquals(3, IconosExcel.cantidad(IconosExcel.ESTRELLAS_3))
        assertEquals(4, IconosExcel.cantidad(IconosExcel.SEMAFORO_4))
        assertEquals(5, IconosExcel.cantidad(IconosExcel.CAJAS_5))
        assertNotNull(IconosExcel)
    }
}
