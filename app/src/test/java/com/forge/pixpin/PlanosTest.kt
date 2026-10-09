package com.forge.pixpin

import com.forge.pixpin.planos.CamaraPlano
import com.forge.pixpin.planos.Enganches
import com.forge.pixpin.planos.LectorDePlanos
import com.forge.pixpin.planos.Medidas
import com.forge.pixpin.planos.ModeloCad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * El visor de planos DWG/DXF (`planos/`). `prueba.pxcad` lo escribió `libpixpincad.so` —la lectura
 * del PC compilada para Android— a partir de `prueba.dxf` (un rectángulo de 10 × 5 m, un círculo,
 * un arco y un texto): así se comprueba que Kotlin lee lo mismo que escribe el Rust del PC.
 * Las pruebas de la cámara, el enganche y las medidas son las del visor del PC (`ventana.rs`).
 */
class PlanosTest {
    private fun fixture(): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("planos/prueba.pxcad")).use { it.readBytes() }

    private fun modelo(): ModeloCad = ModeloCad.deBytes(ByteBuffer.wrap(fixture()))

    @Test
    fun lee_el_plano_que_escribe_el_rust_del_pc() {
        val m = modelo()
        assertEquals("INSUNITS = 6: metros", 6, m.unidades)
        assertFalse(m.vacio)
        // El rectángulo va de (0,0) a (10,5) y el arco de radio 2 en el origen asoma por debajo
        // y a la izquierda: la caja del plano, respecto a su centro.
        val ancho = m.caja[2] - m.caja[0]
        val alto = m.caja[3] - m.caja[1]
        assertTrue("ancho $ancho", ancho >= 10f && ancho < 13f)
        assertTrue("alto $alto", alto >= 5f && alto < 8f)
        assertTrue(m.vertices.cuantos >= 4)
        assertTrue(m.lineas.cuantos > 0)
        assertEquals("el círculo y el arco, exactos", 2, m.arcos.cuantos)
        assertTrue("las letras del texto", m.letras.cuantos >= 8)
        assertTrue(m.glifos.cuantos > 0 && m.mallaLetras.cuantos > 0)
        // Cada tramo apunta dentro de su lista.
        for (k in 0 until m.tramosLineas.n) assertTrue(m.tramosLineas.desde[k] + m.tramosLineas.cuantos[k] <= m.lineas.cuantos)
    }

    @Test
    fun un_plano_roto_o_cortado_no_se_lee() {
        val b = fixture()
        // Cortado por la mitad.
        try { ModeloCad.deBytes(ByteBuffer.wrap(b.copyOf(b.size / 2 / 4 * 4))); fail() } catch (e: ModeloCad.NoSeLee) { }
        // Otra cosa.
        try { ModeloCad.deBytes(ByteBuffer.wrap("hola, esto no es un plano".toByteArray().copyOf(32))); fail() } catch (e: ModeloCad.NoSeLee) { }
        // Un índice de raya que se sale de los vértices: se rechaza, no se le da a la tarjeta.
        val roto = b.copyOf()
        val bb = ByteBuffer.wrap(roto).order(ByteOrder.LITTLE_ENDIAN)
        // Cabecera (8) + origen (16) + caja (16) = 40; luego vértices (n + 3n palabras) y las rayas.
        val nv = bb.getInt(40)
        val lineas = 40 + 4 + nv * 12
        assertTrue(bb.getInt(lineas) > 0)
        bb.putInt(lineas + 4, nv + 5)
        try { ModeloCad.deBytes(ByteBuffer.wrap(roto)); fail() } catch (e: ModeloCad.NoSeLee) { }
    }

    @Test
    fun solo_se_dibuja_lo_que_cae_en_la_pantalla_y_se_veria() {
        val t = ModeloCad.Tramos(
            desde = intArrayOf(0, 10, 20, 30), cuantos = intArrayOf(10, 10, 10, 10),
            caja = floatArrayOf(0f, 0f, 100f, 100f, 0f, 0f, 10f, 10f, 50f, 50f, 60f, 60f, 0f, 0f, 1f, 1f),
            tamano = floatArrayOf(100f, 10f, 10f, 1f), clase = IntArray(4)
        )
        val salida = IntArray(10)
        // Todo a la vista y grande: los cuatro, juntos en uno.
        assertEquals(1, t.visibles(-1f, -1f, 200f, 200f, 0.5f, salida))
        assertEquals(0, salida[0]); assertEquals(40, salida[1])
        // Lo pequeño no: se corta en el primero que no llega.
        assertEquals(1, t.visibles(-1f, -1f, 200f, 200f, 5f, salida))
        assertEquals(30, salida[1])
        // Por sitio: la ventana sobre (55,55) solo ve el primero y el tercero (no seguidos).
        assertEquals(2, t.visibles(54f, 54f, 56f, 56f, 0.5f, salida))
        assertEquals(0, salida[0]); assertEquals(10, salida[1]); assertEquals(20, salida[2]); assertEquals(10, salida[3])
    }

    @Test
    fun acercar_deja_quieto_el_punto_bajo_los_dedos() {
        val c = CamaraPlano(100.0, 50.0, 2.0)
        val ax = c.planoX(30.0, 800); val ay = c.planoY(400.0, 600)
        val z = c.zoom(0.5, 30.0, 400.0, 800, 600)
        assertEquals(ax, z.planoX(30.0, 800), 1e-9)
        assertEquals(ay, z.planoY(400.0, 600), 1e-9)
        assertEquals(1.0, z.px, 0.0)
        // Ida y vuelta entre pantalla y plano.
        assertEquals(30.0, c.pantallaX(ax, 800), 1e-9)
        assertEquals(400.0, c.pantallaY(ay, 600), 1e-9)
        // Arrastrar a la derecha lleva el plano con el dedo.
        val m = c.mover(10.0, 0.0)
        assertEquals(c.pantallaX(ax, 800) + 10.0, m.pantallaX(ax, 800), 1e-9)
    }

    @Test
    fun encuadrar_mete_el_plano_entero_con_margen() {
        val c = CamaraPlano.encuadrar(floatArrayOf(-100f, -10f, 100f, 10f), 1000, 500)
        assertEquals(0.0, c.centroX, 0.0); assertEquals(0.0, c.centroY, 0.0)
        assertEquals(200.0 / 920.0, c.px, 1e-9)
        // Una caja vacía no da una escala absurda.
        assertTrue(CamaraPlano.encuadrar(FloatArray(4), 100, 100).px > 0)
    }

    @Test
    fun la_cota_se_engancha_al_vertice_cercano_y_no_al_lejano() {
        val xs = floatArrayOf(0f, 100f, 100f, 50f)
        val ys = floatArrayOf(0f, 0f, 50f, 25f)
        val g = Enganches.de(floatArrayOf(0f, 0f, 100f, 50f), 4, xs, ys)
        val p = g.cerca(100.3, -0.2, 1.0)
        assertNotNull(p); assertEquals(100.0, p!![0], 1e-4); assertEquals(0.0, p[1], 1e-4)
        assertNotNull(g.cerca(50.5, 25.0, 1.0))
        // Lejos de todo, nada.
        assertNull(g.cerca(30.0, 40.0, 1.0))
        // Fuera de la caja también busca (en el borde).
        assertNotNull(g.cerca(100.5, 50.4, 1.0))
    }

    @Test
    fun el_plano_de_prueba_engancha_en_las_esquinas_del_rectangulo() {
        val m = modelo()
        val g = Enganches.de(m)
        // La esquina (10, 5) del plano, respecto al origen.
        val x = 10.0 - m.origenX; val y = 5.0 - m.origenY
        val p = g.cerca(x + 0.05, y - 0.05, 0.2)
        assertNotNull(p)
        assertTrue(abs(p!![0] - x) < 1e-4 && abs(p[1] - y) < 1e-4)
        // Y el centro del círculo (5, 2.5).
        assertNotNull(g.cerca(5.0 - m.origenX + 0.05, 2.5 - m.origenY, 0.2))
    }

    @Test
    fun las_medidas_llevan_sus_decimales_y_su_unidad() {
        assertEquals("12.346 m", Medidas.medida(12.34567, 6))
        assertEquals("123.46 m", Medidas.medida(123.456, 6))
        assertEquals("1234.5 mm", Medidas.medida(1234.5, 4))
        assertEquals("2.000", Medidas.medida(2.0, 0))
    }

    @Test
    fun la_clave_de_la_cache_es_la_del_pc() {
        // FNV-1a de 64 bits, como `plano_cad::clave` del PC.
        assertEquals("3007ffda88e95bb9", LectorDePlanos.claveDe("C:\\planos\\casa.dwg|1234|1700000000000"))
        assertTrue(LectorDePlanos.esPlano("Casa.DWG") && LectorDePlanos.esPlano("x.dxf"))
        assertFalse(LectorDePlanos.esPlano("x.dwf") || LectorDePlanos.esPlano(null))
    }

    /** Cuánto tarda un plano grande: solo con `PIXPIN_PLANO_GRANDE=<un .pxcad>` (como el `#[ignore]` del PC). */
    @Test
    fun un_plano_grande_se_abre_pronto() {
        val ruta = System.getenv("PIXPIN_PLANO_GRANDE") ?: return
        val t0 = System.nanoTime()
        val m = ModeloCad.abrir(java.io.File(ruta))
        val t1 = System.nanoTime()
        val g = Enganches.de(m)
        val t2 = System.nanoTime()
        println("abrir ${(t1 - t0) / 1_000_000} ms, enganches ${(t2 - t1) / 1_000_000} ms: ${m.vertices.cuantos} vértices, ${m.letras.cuantos} letras")
        assertNotNull(g)
    }
}
