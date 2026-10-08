package com.forge.pixpin.capture

import com.forge.pixpin.capture.CaducidadDeCapturas.DIA_MS
import com.forge.pixpin.capture.GaleriaCompartida.Entrada
import com.forge.pixpin.capture.GaleriaCompartida.Estado
import com.forge.pixpin.capture.GaleriaCompartida.Local
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GaleriaCompartidaTest {
    private val T = 1_790_000_000_000L
    private val r0 = CaducidadDeCapturas.Registro(desde = 0)

    @Test fun `una captura nueva entra con su fecha de irse`() {
        val e = GaleriaCompartida.alDia(Estado(), listOf(Local("a.png", T, 10, "image/png")), r0, 7, T, "tel")
        val a = e.porNombre["a.png"]!!
        assertEquals(T + 7 * DIA_MS, a.seVa)
        assertFalse(a.conservada)
        assertEquals("tel", a.de)
        assertEquals(setOf("a.png"), e.tenia)
    }

    @Test fun `sin poder listar no se toca nada`() {
        val antes = Estado(listOf(Entrada("a.png", T, seVa = T + DIA_MS)), setOf("a.png"))
        assertSame(antes, GaleriaCompartida.alDia(antes, null, r0, 7, T, "tel"))
    }

    @Test fun `la que falta se marca borrada solo si estaba aqui y no le tocaba irse`() {
        val viva = Entrada("viva.png", T, seVa = T + 5 * DIA_MS)
        val caducada = Entrada("vieja.png", T - 9 * DIA_MS, seVa = T - 2 * DIA_MS)
        val ajena = Entrada("ajena.png", T, seVa = T + 5 * DIA_MS)       // nunca estuvo aquí
        val conservada = Entrada("guardada.png", T, conservada = true)
        val e = GaleriaCompartida.alDia(Estado(listOf(viva, caducada, ajena, conservada), setOf("viva.png", "vieja.png", "guardada.png")), emptyList(), r0, 7, T, "tel")
        val p = e.porNombre
        assertTrue(p["viva.png"]!!.borrada)
        assertEquals(T, p["viva.png"]!!.cambiado)
        assertFalse(p["vieja.png"]!!.borrada)
        assertFalse(p["ajena.png"]!!.borrada)
        assertTrue("quitar una conservada también es quitarla", p["guardada.png"]!!.borrada)
        assertTrue(e.tenia.isEmpty())
    }

    @Test fun `conservar y prorrogar aqui cambian la entrada con la hora de ahora`() {
        val a = Entrada("a.png", T, seVa = T + 7 * DIA_MS, cambiado = T)
        val b = Entrada("b.png", T, seVa = T + 7 * DIA_MS, cambiado = T)
        val r = r0.copy(conservadas = setOf("a.png"), fijadas = mapOf("b.png" to T + 14 * DIA_MS))
        val l = listOf(Local("a.png", T, 1, "image/png"), Local("b.png", T, 1, "image/png"))
        val p = GaleriaCompartida.alDia(Estado(listOf(a, b), setOf("a.png", "b.png")), l, r, 7, T + 99, "tel").porNombre
        assertTrue(p["a.png"]!!.conservada); assertNull(p["a.png"]!!.seVa); assertEquals(T + 99, p["a.png"]!!.cambiado)
        assertEquals(T + 14 * DIA_MS, p["b.png"]!!.seVa); assertEquals(T + 99, p["b.png"]!!.cambiado)
    }

    @Test fun `sin caducidad aqui no se cambia la fecha de los demas`() {
        val a = Entrada("a.png", T, seVa = T + 7 * DIA_MS, cambiado = T)
        val p = GaleriaCompartida.alDia(Estado(listOf(a), setOf("a.png")), listOf(Local("a.png", T, 1, "image/png")), r0, 0, T + 5, "tel").porNombre
        assertEquals(a, p["a.png"])
    }

    @Test fun `juntar se queda con lo mas reciente y da lo mismo en los dos sentidos`() {
        val vieja = Entrada("a.png", T, seVa = T + DIA_MS, cambiado = 10)
        val nueva = Entrada("a.png", T, seVa = T + 9 * DIA_MS, cambiado = 20)
        val borrada = Entrada("a.png", T, seVa = T + DIA_MS, borrada = true, cambiado = 20)
        val sola = Entrada("b.png", T, cambiado = 1)
        assertEquals(listOf(nueva, sola), GaleriaCompartida.juntar(listOf(vieja), listOf(nueva, sola)))
        assertEquals(GaleriaCompartida.juntar(listOf(nueva), listOf(vieja, sola)), GaleriaCompartida.juntar(listOf(vieja, sola), listOf(nueva)))
        // A la misma hora gana la borrada, venga de donde venga.
        assertTrue(GaleriaCompartida.juntar(listOf(nueva), listOf(borrada)).single().borrada)
        assertTrue(GaleriaCompartida.juntar(listOf(borrada), listOf(nueva)).single().borrada)
    }

    @Test fun `aplicar pone conservadas y fechas y olvida las borradas`() {
        val r = r0.copy(conservadas = setOf("x.png"), prorrogadas = mapOf("x.png" to 5L), fijadas = mapOf("x.png" to 6L))
        val n = GaleriaCompartida.aplicar(r, listOf(
            Entrada("x.png", T, borrada = true), Entrada("c.png", T, conservada = true), Entrada("f.png", T, seVa = 77)
        ))
        assertEquals(setOf("c.png"), n.conservadas)
        assertEquals(emptyMap<String, Long>(), n.prorrogadas)
        assertEquals(mapOf("f.png" to 77L), n.fijadas)
        // Y la fecha acordada manda sobre la regla.
        assertEquals(77L, CaducidadDeCapturas.seVaEl(n, "f.png", T, 7))
    }

    @Test fun `que traer y que mandar no cuentan borradas ni caducadas`() {
        val l = listOf(
            Entrada("viva.png", T, seVa = T + DIA_MS), Entrada("borrada.png", T, borrada = true),
            Entrada("caducada.png", T - 9 * DIA_MS, seVa = T - DIA_MS), Entrada("guardada.png", T - 99 * DIA_MS, conservada = true)
        )
        val todas = setOf("viva.png", "borrada.png", "caducada.png", "guardada.png")
        assertEquals(listOf("viva.png", "guardada.png"), GaleriaCompartida.queTraer(l, emptySet(), todas, T).map { it.nombre })
        assertEquals(listOf("viva.png", "guardada.png"), GaleriaCompartida.queMandar(l, todas, emptySet(), T).map { it.nombre })
        assertTrue(GaleriaCompartida.queTraer(l, todas, todas, T).isEmpty())
        assertEquals(listOf("borrada.png"), GaleriaCompartida.aTirar(l, todas))
    }

    @Test fun `el registro con fijadas se escribe y se lee, y uno del PC sin ellas tambien`() {
        val r = r0.copy(fijadas = mapOf("a.png" to 5L))
        assertEquals(r, CaducidadDeCapturas.deTexto(CaducidadDeCapturas.aTexto(r)))
        assertFalse(CaducidadDeCapturas.aTexto(r0).contains("fijadas"))
        assertEquals(emptyMap<String, Long>(), CaducidadDeCapturas.deTexto("""{"desde":1,"conservadas":[]}""")!!.fijadas)
    }

    @Test fun `prorrogar una con fecha acordada mueve esa`() {
        val r = r0.copy(fijadas = mapOf("a.png" to T + DIA_MS))
        val n = CaducidadDeCapturas.prorrogada(r, "a.png", T - 6 * DIA_MS, T, 7)
        assertEquals(T + 8 * DIA_MS, n.fijadas["a.png"])
        assertTrue(n.prorrogadas.isEmpty())
    }
}
