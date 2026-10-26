package com.forge.pixpin.ocr.indra

import com.forge.pixpin.data.OCR_INDRA
import com.forge.pixpin.data.OCR_NINGUNO
import com.forge.pixpin.ocr.CatalogoIndra
import com.forge.pixpin.ocr.CorrectorOcr
import com.forge.pixpin.ocr.Hunspell
import com.forge.pixpin.ocr.Vocabulario
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * **Las partes puras del puerto de Indra** (26-oct-2026), con los casos de `IndraUnitTest.kt` y
 * `IndraAfinadoTest.kt` del escáner y de las pruebas de `crates/pixpin-indra` del PC: si una sale
 * distinta aquí, el teléfono leería distinto que el PC la misma imagen. Sin pesos ni runtime: los
 * modelos son de mentira.
 */
class IndraTest {

    private fun gris(w: Int, h: Int, vararg v: Int) = Gris(w, h, ByteArray(v.size) { v[it].toByte() })
    private fun Gris.valores() = px.map { it.toInt() and 0xFF }

    // ------------------------------------------------------------------------------------ gris.rs

    @Test fun laLumaEsLaDeOpenCv() {
        assertEquals(255, OpsGris.luma(0xFFFFFFFF.toInt()))
        assertEquals(0, OpsGris.luma(0xFF000000.toInt()))
        assertEquals(76, OpsGris.luma(0xFFFF0000.toInt())) // 0,299·255 = 76,2
        assertEquals(listOf(255, 0), Gris.deArgb(2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())).valores())
    }

    @Test fun reducirPromediaYAmpliarInterpola() {
        val r = OpsGris.redimensionar(gris(4, 2, 0, 100, 200, 200, 0, 100, 200, 200), 2, 1, OpsGris.Interpolacion.AREA)
        assertEquals(listOf(50, 200), r.valores())
        // Escala no entera: 3 → 2, cada destino cubre 1,5 píxeles.
        assertEquals(listOf(30, 150), OpsGris.redimensionar(gris(3, 1, 0, 90, 180), 2, 1, OpsGris.Interpolacion.AREA).valores())
        // Centros: −0,25 (pega al 0), 0,25, 0,75, 1,25 (pega al 1).
        assertEquals(listOf(0, 25, 75, 100), OpsGris.redimensionar(gris(2, 1, 0, 100), 4, 1, OpsGris.Interpolacion.LINEAL).valores())
    }

    @Test fun rot90VuelveAlOrigenYGiraHaciaDondeToca() {
        val g = gris(3, 2, 1, 2, 3, 4, 5, 6)
        val a = OpsGris.rot90(g, 1)
        // np.rot90([[1,2,3],[4,5,6]]) = [[3,6],[2,5],[1,4]]
        assertEquals(2, a.ancho); assertEquals(3, a.alto)
        assertEquals(listOf(3, 6, 2, 5, 1, 4), a.valores())
        assertEquals(listOf(4, 1, 5, 2, 6, 3), OpsGris.rot90(g, -1).valores())
        assertEquals(g.valores(), OpsGris.rot90(OpsGris.rot90(g, 1), -1).valores())
        assertEquals(g.valores(), OpsGris.rot90(OpsGris.rot90(g, 2), 2).valores())
    }

    @Test fun girarCeroGradosNoCambiaNadaYNoventaMueveElPunto() {
        val g = Gris.lisa(9, 9, 0)
        g.px[2 * 9 + 6] = 255.toByte() // (6, 2)
        assertEquals(g.valores(), OpsGris.girarAfin(g, OpsGris.matrizDeGiro(4.0, 4.0, 0.0), OpsGris.Borde.fijo(0)).valores())
        // 90 grados antihorario sobre el centro: (6, 2) → (2, 2).
        assertEquals(255, OpsGris.girarAfin(g, OpsGris.matrizDeGiro(4.0, 4.0, 90.0), OpsGris.Borde.fijo(0)).en(2, 2))
    }

    @Test fun elNucleoGaussianoYLaElipseSonLosDeOpenCv() {
        val k = OpsGris.nucleoGaussiano(1.0)
        assertEquals(7, k.size)
        assertEquals(1f, k.sum(), 1e-5f)
        assertEquals(35, OpsGris.nucleoGaussiano(17.0 / 3.0).size)
        assertTrue(OpsGris.desenfocar(Gris.lisa(10, 10, 77), 2.0).all { abs(it - 77f) < 1e-3f })
        // getStructuringElement(MORPH_ELLIPSE, (5, 5))
        assertEquals(listOf(2 to 3, 0 to 5, 0 to 5, 0 to 5, 2 to 3), OpsGris.elipse(5))
    }

    @Test fun cerrarBorraLaTintaFinaYLaMedianaElPuntoSuelto() {
        val g = Gris.lisa(20, 20, 220)
        for (y in 5 until 15) g.px[y * 20 + 10] = 10
        assertTrue(OpsGris.cerrar(g, 5).valores().all { it == 220 })
        val m = Gris.lisa(9, 9, 200)
        m.px[4 * 9 + 4] = 0
        assertTrue(OpsGris.mediana5(m).valores().all { it == 200 })
    }

    @Test fun lasComponentesVanPorOrdenDeLecturaConSusCajas() {
        val m = byteArrayOf(
            1, 1, 0, 0, 1,
            0, 1, 0, 0, 1,
            0, 0, 0, 1, 0
        )
        val (et, manchas) = OpsGris.componentes(m, 5, 3)
        assertEquals(3, manchas.size) // la diagonal no une (vecindad 4)
        assertEquals(1, et[0]); assertEquals(2, et[4]); assertEquals(3, et[13])
        assertEquals(OpsGris.Mancha(0, 0, 2, 2, 3), manchas[0])
        assertEquals(2, manchas[1].alto)
    }

    // ------------------------------------------------------------------------------------ prep.rs

    @Test fun laPreparacionBlanqueaLaSombraYConservaLaTinta() {
        val w = 800; val h = 600
        val g = Gris.lisa(w, h, 0)
        for (y in 0 until h) for (x in 0 until w) g.px[y * w + x] = (230.0 - 110.0 * x / w).toInt().toByte()
        for (i in 0 until 5) {
            val y0 = 65 + 100 * i
            for (y in y0 until y0 + 18) for (x in 30 until 600) if ((x / 6) % 3 != 2) g.px[y * w + x] = 25
        }
        val p = PrepIndra.preparar(g)
        assertEquals(w, p.ancho); assertEquals(h, p.alto)
        assertTrue("papel claro ${p.en(20, 40)}", p.en(20, 40) > 235)
        assertTrue("papel en sombra ${p.en(780, 40)} (antes ${g.en(780, 40)})", p.en(780, 40) > 235 && g.en(780, 40) < 130)
        assertTrue((30 until 600).minOf { p.en(it, 75) } < 80)
    }

    @Test fun unaPaginaOscuraNoSePreparaYUnaLisaQuedaIgual() {
        assertFalse(PrepIndra.conviene(Gris.lisa(10, 10, 30)))
        assertTrue(PrepIndra.conviene(Gris.lisa(10, 10, 240)))
        assertTrue(PrepIndra.preparar(Gris.lisa(300, 200, 250)).valores().all { it >= 254 })
    }

    // ------------------------------------------------------------------------------------ imagen.rs, deteccion.rs

    @Test fun lotesPorAnchoComoElEscaner() {
        assertEquals(listOf(listOf(1, 4), listOf(0, 3), listOf(2)), ImagenIndra.lotesPorAncho(intArrayOf(100, 30, 500, 110, 40)))
    }

    @Test fun laEntradaDelDetectorRellenaAMultiplosDe32() {
        var e = ImagenIndra.entradaDetector(Gris.lisa(100, 50, 255), 1024)
        assertEquals(1024, e.ancho); assertEquals(512, e.alto)
        assertEquals(10.24, e.escala, 1e-9)
        assertTrue(e.datos.all { it == 0f })
        e = ImagenIndra.entradaDetector(Gris.lisa(1000, 333, 0), 1024)
        // 333 · 1,024 = 340,99 → 341 → 352
        assertEquals(352, e.alto)
        assertEquals(1f, e.datos[0])
        assertEquals(0f, e.datos[351 * 1024])
    }

    @Test fun elRecorteTieneLaAlturaPedidaYLaTintaEnPositivo() {
        val g = Gris.lisa(200, 60, 255)
        for (y in 20 until 40) for (x in 20 until 180) g.px[y * 200 + x] = 0
        val caja = floatArrayOf(20f, 20f, 180f, 40f)
        val r = ImagenIndra.recortarLinea(g, caja, 32, 1, false)
        assertEquals(32, r.alto)
        // (160 + 2·0,3·20) · 32 / (20·1,4) = 172 · 32 / 28 ≈ 196,6 → 197
        assertEquals(197, r.ancho)
        assertTrue(r.datos[16 * r.ancho + r.ancho / 2] > 0.9f)
        assertEquals(96, ImagenIndra.recortarLinea(g, caja, 32, 3, false).alto)
    }

    @Test fun textoClaroSobreOscuroSeDetecta() {
        val o = Gris.lisa(100, 20, 30)
        for (x in 10 until 90 step 4) for (y in 5 until 15) o.px[y * 100 + x] = 240.toByte()
        assertTrue(ImagenIndra.lineaOscura(o, floatArrayOf(0f, 0f, 99f, 19f)))
        val c = Gris.lisa(100, 20, 240)
        for (x in 10 until 90 step 4) for (y in 5 until 15) c.px[y * 100 + x] = 20
        assertFalse(ImagenIndra.lineaOscura(c, floatArrayOf(0f, 0f, 99f, 19f)))
    }

    @Test fun unaPaginaEnBlancoNoSeEnderezaYUnaTorcidaSi() {
        assertEquals(0.0, ImagenIndra.estimarInclinacion(Gris.lisa(400, 300, 255)), 0.0)
        val g = Gris.lisa(600, 600, 255)
        for (r in 0 until 12) {
            val y0 = 60 + r * 40
            for (y in y0 until y0 + 10) for (x in 80 until 520) g.px[y * 600 + x] = 0
        }
        val a = ImagenIndra.estimarInclinacion(ImagenIndra.girarPagina(g, -4.0))
        assertEquals(4.0, a, 0.35)
    }

    @Test fun desgirarUnaCajaLaDevuelveASuSitio() {
        val b = floatArrayOf(100f, 100f, 200f, 120f)
        assertArrayEquals(b, ImagenIndra.desgirarCaja(b, 0.0, 400, 400), 0f)
        val c = ImagenIndra.desgirarCaja(b, 90.0, 400, 400)
        assertTrue(c.toList().toString(), abs(c[2] - c[0] - 20f) < 0.2f && abs(c[3] - c[1] - 100f) < 0.2f)
    }

    @Test fun unaBandaDeNucleoEsUnRenglonConSuAlto() {
        // Salida 20 × 10; núcleo en las filas 4-5, columnas 2-15.
        val w = 20; val h = 10
        val s = FloatArray(3 * w * h) { -10f }
        for (y in 4 until 6) for (x in 2 until 16) {
            val i = y * w + x
            s[i] = 10f; s[w * h + i] = 6f; s[2 * w * h + i] = 6f
        }
        val c = ImagenIndra.decodificar(s, w, h)
        assertEquals(1, c.size)
        // ys medio = (9 + 11) / 2 = 10 → 4..16 de alto 12; ex = 1,8.
        assertArrayEquals(floatArrayOf(2.2f, 4f, 33.8f, 16f), c[0], 1e-4f)
        assertTrue(ImagenIndra.decodificar(FloatArray(3 * w * h) { -5f }, w, h).isEmpty())
    }

    // ------------------------------------------------------------------------------------ vocab, lm, orden

    @Test fun vocabDecodificaYEspacioDeComandos() {
        val v = VocabIndra.deJson("""{"blank": 0, "chars": [" ", "a", "b", "\\alpha", "x"]}""")
        assertEquals(1, v.espacio)
        assertEquals("ab a", v.decodificar(intArrayOf(2, 3, 1, 2)))
        assertEquals("\\alpha x", v.decodificar(intArrayOf(4, 5)))
        assertEquals("", v.decodificar(intArrayOf(0, 99)))
    }

    @Test fun metaJsonConYSinParametrosDelLm() {
        val m = meta()
        assertEquals(256, m.ladoLargo); assertEquals(32, m.altoRec); assertEquals(1, m.ventana)
        assertEquals(CanalIndra.ALFA_LM, m.alfa, 0.0)
        val m2 = MetaIndra.deJson("""{"vocab": "{\"chars\": []}", "det_long_side": 1024, "rec_height": 32, "rec_window": 3, "lm_params": {"base": {"alpha": 0.7, "gate": 0.8}}}""")
        assertEquals(3, m2.ventana); assertEquals(0.7, m2.alfa, 0.0); assertEquals(CanalIndra.BETA_LM, m2.beta, 0.0); assertEquals(0.8, m2.puerta, 0.0)
        assertTrue(runCatching { MetaIndra.deJson("{}") }.isFailure)
    }

    @Test fun ordenPorParesAgrupacionYXyCut() {
        val pos = intArrayOf(1, 2, 0)
        val a = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 0.0 else if (pos[i] < pos[j]) 4.0 else -4.0 } }
        assertEquals(listOf(2, 0, 1), OrdenIndra.decodificarOrden(a).toList())
        val mismo = Array(4) { i -> FloatArray(4) { j -> if ((i < 2) == (j < 2)) 3f else -3f } }
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), OrdenIndra.agrupar(mismo).sortedBy { it.first() })
        val b = listOf(floatArrayOf(10f, 10f, 90f, 20f), floatArrayOf(110f, 10f, 190f, 20f), floatArrayOf(10f, 30f, 90f, 40f), floatArrayOf(110f, 30f, 190f, 40f))
        assertEquals(listOf(0, 2, 1, 3), OrdenIndra.ordenXy(b))
        assertEquals(listOf(0, 1, 2, 3), OrdenIndra.ordenDeArribaAbajo(b))
    }

    @Test fun rasgosDeLinea() {
        val f = OrdenIndra.rasgos(listOf(floatArrayOf(0f, 0f, 100f, 10f)), 200f, 100f, listOf("12) Hola."))
        assertEquals(16, f.size)
        assertEquals(0.5f, f[2], 1e-6f)
        assertEquals(1f, f[12]); assertEquals(1f, f[14]); assertEquals(0f, f[15])
    }

    private fun npy(descr: String, forma: String, datos: ByteArray): ByteArray {
        var cab = "{'descr': '$descr', 'fortran_order': False, 'shape': $forma, }"
        while ((10 + cab.length + 1) % 64 != 0) cab += " "
        cab += "\n"
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray() + byteArrayOf(1, 0))
        out.write(byteArrayOf((cab.length and 0xFF).toByte(), (cab.length shr 8).toByte()))
        out.write(cab.toByteArray(Charsets.ISO_8859_1)); out.write(datos)
        return out.toByteArray()
    }

    /** LM de orden 2 con 11 bits: unigramas y bigramas de 2 fichas (0 y 1). */
    private fun lmDeJuguete(): LmIndra {
        val tmp = File.createTempFile("indra_lm", ".npz")
        try {
            ZipOutputStream(tmp.outputStream()).use { z ->
                fun pon(n: String, d: String, f: String, b: ByteArray) { z.putNextEntry(ZipEntry("$n.npy")); z.write(npy(d, f, b)); z.closeEntry() }
                fun longs(vararg v: Long) = ByteBuffer.allocate(8 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putLong(it) } }.array()
                fun floats(vararg v: Float) = ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
                pon("order", "<i8", "()", longs(2)); pon("vocab_size", "<i8", "()", longs(2))
                pon("k0", "<u8", "(2,)", longs(1, 2)); pon("v0", "<f4", "(2,)", floats(-1f, -2f))
                pon("hk0", "<u8", "(1,)", longs(0)); pon("hb0", "<f4", "(1,)", floats(0f))
                pon("k1", "<u8", "(1,)", longs((1L shl 11) or 2)); pon("v1", "<f4", "(1,)", floats(-0.1f))
                pon("hk1", "<u8", "(2,)", longs(0, 1)); pon("hb1", "<f4", "(2,)", floats(-0.5f, -0.7f))
            }
            return LmIndra.cargar(tmp)
        } finally {
            tmp.delete()
        }
    }

    @Test fun lectorNpyYModeloDeLenguaje() {
        val lm = lmDeJuguete()
        assertEquals(2, lm.orden)
        assertEquals(-0.1, lm.logpClave((1L shl 11) or 2), 1e-6)        // bigrama visto
        assertEquals(-0.7 + -1.0, lm.logpClave((1L shl 11) or 1), 1e-6) // retroceso al unigrama
        assertEquals(-0.5 + -2.0, lm.logpClave(2), 1e-6)                // historia = inicio de línea
        val ln = { p: Double -> kotlin.math.ln(p).toFloat() }
        val blanco = floatArrayOf(ln(0.01), ln(0.99), ln(0.01))
        val topv = floatArrayOf(ln(0.98), ln(0.01), ln(0.99), ln(0.005), ln(0.98), ln(0.01))
        val topi = longArrayOf(1, 0, 0, 2, 2, 0)
        val (ids, pasos) = lm.buscarEnHaz(blanco, topv, topi, 2, 3, 0.5, 1.5)
        assertEquals(listOf(1, 2), ids.toList())
        assertEquals(2, pasos.size)
        assertEquals(0, pasos[0])
    }

    @Test fun elSesgoDelVocabularioInclinaLaBusquedaEnHaz() {
        // Casi igual de probables en el paso de en medio: «aba» o «aca». «Mi vocabulario» dice «acá».
        val v = VocabIndra(listOf("a", "b", "c"))
        val lm = lmDeJuguete()
        val ln = { p: Double -> kotlin.math.ln(p).toFloat() }
        val blanco = floatArrayOf(ln(0.001), ln(0.001), ln(0.001))
        val topv = floatArrayOf(ln(0.99), ln(0.005), ln(0.5), ln(0.49), ln(0.99), ln(0.005))
        val topi = longArrayOf(1, 0, 2, 3, 1, 0)
        val sinSesgo = lm.buscarEnHaz(blanco, topv, topi, 2, 3, 0.0, 0.0, vocab = v).first
        assertEquals(listOf(1, 2, 1), sinSesgo.toList())
        val sesgo = CorrectorOcr(null, Vocabulario.deTexto("acá")).sesgo()
        val conSesgo = lm.buscarEnHaz(blanco, topv, topi, 2, 3, 0.0, 0.0, sesgo = sesgo, vocab = v).first
        assertEquals(listOf(1, 3, 1), conSesgo.toList())
    }

    // ------------------------------------------------------------------------------------ canal.rs

    /** Modelos de mentira: un detector que ve los renglones que se le digan y un reconocedor que «lee» siempre lo mismo. */
    private class Falsos(val nucleos: List<IntArray>, var lee: List<Pair<IntArray, Float>>, val orden: SalidaOrden? = null) : ModelosIndra {
        override fun detectar(x: FloatArray, alto: Int, ancho: Int): Triple<FloatArray, Int, Int> {
            val h = alto / 2; val w = ancho / 2
            val s = FloatArray(3 * w * h) { -10f }
            for (n in nucleos) for (y in n[1] until n[3]) for (xx in n[0] until n[2]) {
                s[y * w + xx] = 10f; s[w * h + y * w + xx] = 6f; s[2 * w * h + y * w + xx] = 6f
            }
            return Triple(s, h, w)
        }

        override fun reconocer(x: FloatArray, lote: Int, alto: Int, ancho: Int, anchos: LongArray, conLm: Boolean): SalidaRec {
            val pasos = ancho / 4
            val ids = LongArray(lote * pasos)
            val conf = FloatArray(lote * pasos)
            for (k in 0 until lote) {
                val (clases, c) = lee[k % lee.size]
                for ((i, cl) in clases.withIndex()) {
                    val s = 2 * i // una clase cada dos pasos, con blanco en medio
                    if (s < pasos) { ids[k * pasos + s] = cl.toLong(); conf[k * pasos + s] = c }
                }
            }
            return SalidaRec(ids, conf, LongArray(lote) { pasos.toLong() }, pasos)
        }

        override fun orientar(x: FloatArray, lado: Int) = floatArrayOf(0.99f, 0f, 0f, 0.01f, 0f)
        override fun ordenar(rasgos: FloatArray, n: Int) = orden
    }

    private fun meta() = MetaIndra.deJson("""{"vocab": "{\"blank\": 0, \"chars\": [\" \", \"H\", \"o\", \"l\", \"a\", \"-\"]}", "det_long_side": 256, "rec_height": 32, "rec_window": 1}""")

    private fun paginaBlanca() = Gris.lisa(256, 128, 255)

    private val hola = intArrayOf(2, 3, 4, 5, 1, 2, 3, 4, 5)

    @Test fun dosRenglonesSalenEnOrdenConPalabrasYBloques() {
        val f = Falsos(listOf(intArrayOf(10, 40, 60, 42), intArrayOf(10, 10, 60, 12)), listOf(hola to 0.95f))
        val p = CanalIndra(meta(), null).apply { preparar = false }.ejecutar(f, paginaBlanca())
        assertEquals(2, p.lineas.size)
        assertTrue(p.lineas[0].caja[1] < p.lineas[1].caja[1]) // el de arriba primero (XY-cut)
        assertEquals("Hola Hola", p.lineas[0].texto)
        assertEquals(2, p.lineas[0].palabras.size)
        assertTrue(p.lineas[0].palabras[0].caja[2] <= p.lineas[0].palabras[1].caja[0] + 1f)
        assertEquals(0, p.giro)
        assertEquals("Hola Hola\nHola Hola", p.texto)
        assertTrue(p.bloques.isNotEmpty())
        assertTrue("total_ms" in p.ms)
    }

    @Test fun unaLineaDeBajaConfianzaQuedaDudosaOVacia() {
        val f = Falsos(listOf(intArrayOf(10, 10, 60, 12)), listOf(hola to 0.42f))
        val canal = CanalIndra(meta(), null).apply { preparar = false }
        var p = canal.ejecutar(f, paginaBlanca())
        assertEquals(1, p.lineas.size)
        assertTrue(p.lineas[0].dudosa)
        assertEquals("Hola Hola", p.lineas[0].texto)
        assertEquals("las dudosas no van al texto plano", "", p.texto)
        assertTrue(MotorIndra.aLineas(p).isEmpty())
        f.lee = listOf(intArrayOf(2, 3) to 0.1f)
        p = canal.ejecutar(f, paginaBlanca())
        assertTrue(!p.lineas[0].dudosa && p.lineas[0].texto.isEmpty())
    }

    @Test fun lasRayasDeRellenoSeQuitan() {
        val f = Falsos(listOf(intArrayOf(10, 10, 60, 12)), listOf(intArrayOf(6, 6, 6, 6) to 0.95f))
        assertTrue(CanalIndra(meta(), null).apply { preparar = false }.ejecutar(f, paginaBlanca()).lineas.isEmpty())
    }

    @Test fun conElModeloDeOrdenMandaSuOrdenYSusBloques() {
        val cls = FloatArray(20)
        cls[1] = 5f  // línea 0: Section-Header
        cls[10] = 5f // línea 1: Text
        val f = Falsos(
            listOf(intArrayOf(10, 10, 60, 12), intArrayOf(10, 40, 60, 42)), listOf(intArrayOf(2, 3, 4, 5) to 0.95f),
            SalidaOrden(floatArrayOf(0f, -4f, 4f, 0f), 2, cls, floatArrayOf(0f, -3f, -3f, 0f))
        )
        val p = CanalIndra(meta(), null).apply { preparar = false }.ejecutar(f, paginaBlanca())
        assertEquals(2, p.lineas.size)
        assertTrue("la de abajo primero", p.lineas[0].caja[1] > p.lineas[1].caja[1])
        assertEquals(2, p.bloques.size)
        assertEquals("Text", p.bloques[0].etiqueta)
        assertEquals("Section-Header", p.bloques[1].etiqueta)
        assertEquals(listOf(0), p.bloques[0].lineas)
    }

    @Test fun cajasDeVueltaALaImagenOriginal() {
        fun linea() = CanalIndra.Linea(floatArrayOf(175f, 5f, 185f, 15f), "x", 0.9f, listOf(CanalIndra.Palabra("x", floatArrayOf(175f, 5f, 185f, 15f), 0.9f)))
        // Imagen 100 × 200 que Indra giró 90 (página girada 200 × 100).
        val p = CanalIndra.Pagina(listOf(linea()), emptyList(), 90, 0.0, 200, 100)
        assertEquals(-90f, p.aLaImagen(100, 200, 1f, 1f), 1e-4f)
        assertArrayEquals(floatArrayOf(5f, 15f, 15f, 25f), p.lineas[0].caja, 1e-4f)
        assertArrayEquals(floatArrayOf(5f, 15f, 15f, 25f), p.lineas[0].palabras[0].caja, 1e-4f)
        val p0 = CanalIndra.Pagina(listOf(linea()), emptyList(), 0, 1.5, 200, 100)
        assertEquals(1.5f, p0.aLaImagen(300, 150, 1.5f, 1.5f), 1e-4f)
        assertEquals(262.5f, p0.lineas[0].caja[0], 1e-3f)
        assertTrue(p0.lineas[0].caja[2] <= 300f)
        // Y a la forma de siempre: cajas enteras que cubren.
        val l = MotorIndra.aLineas(CanalIndra.Pagina(listOf(CanalIndra.Linea(floatArrayOf(10.2f, 5.7f, 50.1f, 20f), "Hola", 0.9f, emptyList())), emptyList(), 0, 0.0, 100, 100))
        assertEquals(listOf(10, 5, 41, 15), l.single().let { listOf(it.x, it.y, it.ancho, it.alto) })
    }

    @Test fun enumeradores() {
        for (t in listOf("1.", "12)", "a)", "(iv)", "(3)")) assertTrue(t, CanalIndra.esEnumerador(t))
        for (t in listOf("1", "hola.", "(abc)", "1234.")) assertFalse(t, CanalIndra.esEnumerador(t))
    }

    @Test fun laCorreccionDelPcCambiaLaPaginaDeIndra() {
        // «Mi vocabulario» pone las tildes de su término, y el diccionario las de una palabra española.
        val p = CanalIndra.Pagina(
            listOf(
                CanalIndra.Linea(floatArrayOf(0f, 0f, 100f, 10f), "la evaluacion sismica", 0.6f, listOf(
                    CanalIndra.Palabra("la", floatArrayOf(0f, 0f, 10f, 10f), 0.95f),
                    CanalIndra.Palabra("evaluacion", floatArrayOf(12f, 0f, 50f, 10f), 0.6f, FloatArray(10) { 0.6f }),
                    CanalIndra.Palabra("sismica", floatArrayOf(52f, 0f, 100f, 10f), 0.95f)
                ))
            ), emptyList(), 0, 0.0, 100, 10
        )
        val canal = CanalIndra(meta(), null)
        canal.vocabulario = Vocabulario.deTexto("sísmica")
        canal.lexico = Hunspell.cargar("", "la\nevaluación\nde\nel\nanálisis\ncon\npara\nconcreto\narmado\n")
        val cambios = canal.corregir(p)
        assertEquals("la evaluacion sísmica", p.lineas[0].texto) // página corta: el diccionario no se usa
        assertEquals("sísmica", p.lineas[0].palabras[2].texto)
        assertEquals(1, cambios.size)
    }

    // ------------------------------------------------------------------------------------ motor

    @Test fun indraSoloSiSeEligeYEstaBajado() {
        assertTrue(MotorIndra.usaIndra(OCR_INDRA, true))
        assertFalse(MotorIndra.usaIndra(OCR_INDRA, false))
        assertFalse(MotorIndra.usaIndra(OCR_NINGUNO, true))
        assertFalse(MotorIndra.usaIndra("tesseract", true))
    }

    @Test fun sinFicherosNoEstaListoYConUnTamanoMaloTampoco() {
        val d = File(System.getProperty("java.io.tmpdir"), "pixpin-indra-${System.nanoTime()}")
        try {
            assertFalse(MotorIndra.listo(d))
            assertEquals(CatalogoIndra.megas(), MotorIndra.megasQueFaltan(d))
            d.mkdirs()
            for (f in CatalogoIndra.FICHEROS) File(d, f.nombre).writeBytes(ByteArray(f.bytes.toInt()))
            assertTrue(MotorIndra.listo(d))
            assertEquals(0L, MotorIndra.megasQueFaltan(d))
            File(d, "rec.onnx").writeText("corto")
            assertFalse("un fichero a medias no vale", MotorIndra.listo(d))
            MotorIndra.borrar(d)
            assertFalse(d.exists())
        } finally {
            d.deleteRecursively()
        }
    }

    @Test fun losModelosDelCanalEstanEnElCatalogo() {
        val nombres = CatalogoIndra.FICHEROS.map { it.nombre }
        assertTrue(CanalIndra.MODELOS.all { it in nombres })
        assertNull(CanalIndra.MODELOS.firstOrNull { !it.endsWith(".onnx") && !it.endsWith(".json") && !it.endsWith(".npz") })
    }

    @Test fun unaImagenGrandeSeReduceAlLadoMaximo() {
        val g = MotorIndra.reducir(Gris.lisa(8000, 2000, 200))
        assertEquals(CanalIndra.LADO_MAX, g.ancho)
        assertEquals(1000, g.alto)
        val p = Gris.lisa(300, 200, 1)
        assertTrue(MotorIndra.reducir(p) === p)
    }
}
