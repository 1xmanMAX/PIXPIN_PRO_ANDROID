package com.forge.pixpin.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Las pruebas del afinado del PC** (`crates/pixpin-indra`: `lexico.rs`, `corrector.rs`,
 * `dudas.rs`, `catalogo.rs`), con los mismos casos y los mismos números: si una sale distinta
 * aquí, el teléfono y el PC corregirían distinto el mismo texto.
 */
class CorrectorOcrTest {

    private fun linea(texto: String, conf: Float, dudosa: Boolean = false) = LineaOcr(
        texto,
        texto.split(' ').filter { it.isNotEmpty() }.mapTo(ArrayList()) { PalabraOcr(it, conf, List(it.length) { conf }) },
        conf, dudosa
    )

    private fun pagina(vararg l: LineaOcr) = PaginaOcr(l.toMutableList())

    /** Un diccionario de juguete con lo que piden las pruebas del escáner. */
    private fun lexico() = Hunspell.cargar(
        "",
        "el\nanálisis\nde\nla\nestructura\ncon\nconcreto\narmado\npara\nedificación\nevaluación\ndel\nfuerza\ntotal\n"
    )

    private val contexto = "el análisis de la estructura con concreto armado para la edificación"

    // ------------------------------------------------------------------------------------ lexico.rs

    @Test fun hunspellMinimoConAfijos() {
        val aff = "SET UTF-8\nFLAG UTF-8\nPFX r Y 1\nPFX r 0 re .\nSFX S Y 2\nSFX S 0 s [aeiou]\nSFX S 0 es [^aeiou]\nSFX A Y 1\nSFX A r ción/S ar\n"
        val dic = "4\ncasa/S\nhacer/r\nevaluar/A\nMadrid\n"
        val h = Hunspell.cargar(aff, dic)
        assertTrue(h.contiene("casa"))
        assertTrue(h.contiene("casas"))
        assertFalse(h.contiene("casaes"))
        assertTrue(h.contiene("rehacer"))
        assertTrue(h.contiene("evaluación"))
        assertTrue("continuación /S", h.contiene("evaluaciónes"))
        assertTrue(h.contiene("Madrid"))
        assertTrue(h.contiene("MADRID"))
        assertTrue(h.contiene("Casas"))
        assertFalse(h.contiene("xadrid"))
        assertEquals(4, h.tamano)
    }

    @Test fun elVocabularioQuitaDuplicadosYComparaSinTildes() {
        var v = Vocabulario.nuevo(listOf("sismorresistente", "E030", "concreto armado", "sísmica", " ", "sísmica"))
        assertEquals(listOf("sismorresistente", "E030", "concreto armado", "sísmica"), v.terminos)
        assertTrue(v.contiene("Armado"))
        assertTrue("sin tildes", v.contiene("sismica"))
        assertEquals("sísmica", v.forma("SISMICA"))
        assertTrue("sism" in v.prefijos)
        assertEquals("ñandu", clave("Ñandú"))
        v = Vocabulario.deTexto("uno, dos\ntres;  ")
        assertEquals(listOf("uno", "dos", "tres"), v.terminos)
    }

    // --------------------------------------------------------------------------------- corrector.rs

    @Test fun corrigeTildesEnPaginaEspanola() {
        val c = CorrectorOcr(lexico(), Vocabulario.VACIO)
        var p = pagina(linea(contexto, 0.99f), linea("la evaluacion del analisis", 0.7f))
        val (_, esp) = c.corregir(p)
        assertTrue(esp)
        assertEquals("la evaluación del análisis", p.lineas[1].texto)
        p = pagina(linea(contexto, 0.99f), linea("la evaluacion", 0.97f))
        c.corregir(p)
        assertEquals("leída con seguridad", "la evaluacion", p.lineas[1].texto)
    }

    @Test fun noTocaCodigosNumerosNiNombresNiPaginasInglesas() {
        val c = CorrectorOcr(lexico(), Vocabulario.VACIO)
        val t = "CÓDIGO U20191A543 total 152.40 Erick Hotev"
        var p = pagina(linea(contexto, 0.99f), linea(t, 0.5f))
        c.corregir(p)
        assertEquals(t, p.lineas[1].texto)
        p = pagina(linea("the quick brown fox jumps over the lazy dog and the cat", 0.99f), linea("the evaluacion form", 0.5f))
        val (_, esp) = c.corregir(p)
        assertFalse(esp)
        assertEquals("the evaluacion form", p.lineas[1].texto)
    }

    @Test fun elVocabularioDelUsuarioManda() {
        val v = Vocabulario.nuevo(listOf("sismorresistente", "E030", "concreto armado", "sísmica"))
        var c = CorrectorOcr(lexico(), v)
        var p = pagina(linea(contexto, 0.99f), linea("Ing. Sismorresistenta norma E03O sismica", 0.6f))
        val (cambios, _) = c.corregir(p)
        assertEquals("Ing. Sismorresistente norma E030 sísmica", p.lineas[1].texto)
        assertTrue(cambios.all { it.origen == "vocabulario" })
        p = pagina(linea(contexto, 0.99f), linea("parado hermano", 0.5f))
        c.corregir(p)
        assertEquals("lejos del término", "parado hermano", p.lineas[1].texto)
        // Sin diccionario el vocabulario sigue.
        c = CorrectorOcr(null, v)
        p = pagina(linea("sismorresistenta", 0.5f))
        c.corregir(p)
        assertEquals("sismorresistente", p.lineas[0].texto)
    }

    @Test fun elSesgoDelLmPremiaElVocabularioYElDiccionario() {
        val c = CorrectorOcr(lexico(), Vocabulario.nuevo(listOf("sismorresistente")))
        val s = c.sesgo()
        assertTrue(s.activo)
        assertEquals(2.0, s.parcial("sism"), 1e-9)
        assertEquals(0.0, s.parcial("sixm"), 0.0)
        assertEquals(0.5 * 16.0 + 1.0, s.completa("sismorresistente"), 1e-9)
        assertEquals(1.0, s.completa("concreto"), 0.0)
        assertEquals(0.0, s.completa("xyzzy"), 0.0)
        assertFalse(CorrectorOcr(null, Vocabulario.VACIO).sesgo().activo)
    }

    @Test fun laDistanciaPonderaPorConfianza() {
        val dudoso = CorrectorOcr.distanciaPonderada("luerza", "fuerza", listOf(0.2f, 1f, 1f, 1f, 1f, 1f))
        val seguro = CorrectorOcr.distanciaPonderada("luerza", "fuerza", List(6) { 1f })
        assertTrue(dudoso < seguro)
        assertEquals(0.1, CorrectorOcr.distanciaPonderada("evaluacion", "evaluación", null), 1e-9)
        assertTrue("caso" in CorrectorOcr.ediciones1("casa"))
        val c = CorrectorOcr(lexico(), Vocabulario.VACIO)
        assertNull(c.corregirPalabra(PalabraOcr("12,50", 0.3f), true))
    }

    /** Con un LM que desempata: dos candidatos a una edición, gana el que el LM prefiere con margen. */
    @Test fun elLmDesempataLasEdicionesYSinElNoSeAdivina() {
        val lx = Hunspell.cargar("", "el\nde\nla\ncon\npara\nmanzana\nmanzano\ncasa\nperro\ngato\n")
        val c = CorrectorOcr(lx, Vocabulario.VACIO)
        fun pag() = pagina(linea("el perro de la casa con el gato para", 0.99f), linea("la manzanx", 0.6f))
        var p = pag()
        c.corregir(p)
        assertEquals("sin LM, empate: no se toca", "la manzanx", p.lineas[1].texto)
        p = pag()
        c.corregir(p) { w -> if (w == "manzana") -2.0 else -20.0 }
        assertEquals("la manzana", p.lineas[1].texto)
    }

    // ------------------------------------------------------------------------------------- dudas.rs

    @Test fun dudosasSinBasuraEvidente() {
        val s = 0.3f
        assertTrue(DudasOcr.conservar("14,0", 0.42f, 1.2f, s))
        assertTrue(DudasOcr.conservar("Ing. Sismorresistente", 0.45f, 1.0f, s))
        assertTrue(DudasOcr.conservar("Erick Horev", 0.35f, 1.0f, s))
        assertFalse("raya", DudasOcr.conservar("-", 0.45f, 1.0f, s))
        assertFalse("casilla", DudasOcr.conservar("I", 0.3f, 1.0f, s))
        assertFalse("puntos", DudasOcr.conservar("...", 0.45f, 1.0f, s))
        assertFalse("letras sueltas", DudasOcr.conservar("gi", 0.44f, 1.0f, s))
        assertFalse("bajo el suelo", DudasOcr.conservar("Texto largo", 0.2f, 1.0f, s))
        assertFalse("renglones fundidos", DudasOcr.conservar("la dei son de", 0.45f, 3.5f, s))
        assertFalse("muy baja y corta", DudasOcr.conservar("an t", 0.35f, 1.0f, s))
        assertTrue("cifras a baja confianza", DudasOcr.conservar("27 50", 0.35f, 1.0f, s))
    }

    // ---------------------------------------------------------------------------------- catalogo.rs

    @Test fun losPesosEstanFijadosALaEtiquetaYTraenSuSha() {
        for (f in CatalogoIndra.FICHEROS) {
            assertEquals(f.nombre, 64, f.sha256.length)
            assertTrue(f.sha256.all { it in "0123456789abcdef" })
            assertTrue(f.url.startsWith("https://"))
            assertTrue(f.url.endsWith(f.nombre))
            if (f.nombre.endsWith(".onnx") || f.nombre.endsWith(".npz") || f.nombre == "meta.json") {
                assertTrue(f.nombre, CatalogoIndra.REVISION in f.url)
            }
        }
        assertEquals(26L, CatalogoIndra.megas())
    }

    // ----------------------------------------------------------- lo del teléfono: la visión del modelo

    @Test fun elVocabularioCorrigeLoQueTranscribeLaVision() {
        val v = Vocabulario.deTexto("sismorresistente, E030\nArequipa")
        val leido = "Diseño Sismorresistenta, norma E03O\nen la ciudad de AREQUIPA y Arequlpa.\n12,50 soles"
        assertEquals(
            "Diseño Sismorresistente, norma E030\nen la ciudad de AREQUIPA y Arequipa.\n12,50 soles",
            CorrectorOcr.conVocabulario(leido, v)
        )
        assertEquals("sin vocabulario, tal cual", leido, CorrectorOcr.conVocabulario(leido, Vocabulario.VACIO))
        assertEquals(
            "lejos de un término, tal cual", "parado hermano",
            CorrectorOcr.conVocabulario("parado hermano", Vocabulario.deTexto("concreto armado"))
        )
        assertEquals("espacios dobles y renglones vacíos se quedan", "a  b\n\nc", CorrectorOcr.conVocabulario("a  b\n\nc", v))
    }
}
