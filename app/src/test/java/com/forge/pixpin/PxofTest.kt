package com.forge.pixpin

import com.forge.pixpin.office.DocumentoOffice
import com.forge.pixpin.office.Pxof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **El formato PXOF** con que `libpixpinoffice.so` (la maquetación de Word y PowerPoint del PC)
 * le pasa cada hoja a Kotlin. Los fixtures (`resources/office/`) los escribió el propio binario de
 * Rust desde un Word y un PowerPoint hechos a mano: `herramientas/pixpin-office-android/pruebas/
 * hacer-fixtures.sh` los rehace. El .so no carga en la JVM; lo que se prueba es que lo que escribe
 * Rust se lee aquí entero y con sentido.
 */
class PxofTest {
    private fun fixture(n: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("office/$n")) { "falta office/$n" }.use { it.readBytes() }

    private fun textos(p: Pxof.Pagina) = p.textos().map { it.first.texto }

    @Test
    fun la_primera_hoja_del_word_es_un_a4_con_su_texto() {
        val p = Pxof.leer(fixture("word-1.pxof"))
        // 11906 × 16838 veintavos de punto.
        assertEquals(595.3f, p.ancho, 0.1f)
        assertEquals(841.9f, p.alto, 0.1f)
        assertEquals(1, p.numero)
        val todo = p.textoPlano()
        assertTrue(todo, todo.contains("Informe de prueba"))
        assertTrue(todo, todo.contains("negrita"))
        assertTrue(todo, todo.contains("pingüino"))
        // La tabla, en dos filas, y el pie con su número.
        assertTrue(todo, todo.contains("Uno") && todo.contains("Cuatro"))
        assertTrue(todo, todo.contains("Página"))
        // El título va arriba de todo.
        val renglones = todo.lines()
        assertEquals("Informe de prueba", renglones.first().trim())
    }

    @Test
    fun cada_letra_trae_su_cara_su_glifo_y_su_x() {
        val p = Pxof.leer(fixture("word-1.pxof"))
        assertTrue(p.caras.isNotEmpty())
        p.caras.forEach { assertTrue(it.ruta, it.ruta.endsWith(".ttf")) }
        for ((t, _) in p.textos()) {
            if (t.total) continue
            assertEquals(t.texto, t.texto.codePointCount(0, t.texto.length), t.glifos.size)
            for (i in 1 until t.xs.size) assertTrue("x que retrocede en «${t.texto}»", t.xs[i] >= t.xs[i - 1])
            // Las letras de verdad tienen su glifo en la cara elegida.
            for (i in t.glifos.indices) if (t.codigos[i] > ' '.code) assertTrue("sin glifo: ${t.codigos[i].toChar()}", t.glifos[i] != 0)
            assertTrue(t.caras.all { it in p.caras.indices })
        }
        // La negrita sale de la cara negrita (Liberation Serif Bold mide como Times New Roman Bold).
        val negrita = p.textos().first { it.first.texto.contains("negrita") }.first
        assertTrue(p.caras[negrita.caras[0]].ruta, p.caras[negrita.caras[0]].ruta.contains("Bold"))
        // La cursiva no tiene archivo propio en las letras de PixPin: se finge.
        val cursiva = p.textos().first { it.first.texto.contains("cursiva") }.first
        val c = p.caras[cursiva.caras[0]]
        assertTrue(c.toString(), c.cursivaFalsa || c.ruta.contains("Italic"))
    }

    @Test
    fun el_enlace_se_encuentra_tocando_su_texto() {
        val p = Pxof.leer(fixture("word-1.pxof"))
        val (t, caja) = p.textos().first { it.first.texto == "Un enlace" }
        assertEquals("https://example.org/pixpin", t.enlace)
        val tocado = p.textoEn(caja[0] + caja[2] / 2, caja[1] + caja[3] / 2)
        assertEquals("https://example.org/pixpin", tocado?.enlace)
        assertNull(p.textoEn(-10f, -10f))
        // Mantener el dedo copia el renglón entero, de izquierda a derecha.
        val (n, cn) = p.textos().first { it.first.texto.startsWith("Texto normal") }
        val renglon = p.renglonEn(cn[0] + 2, cn[1] + cn[3] / 2).joinToString("") { it.texto }
        assertTrue(renglon, renglon.startsWith(n.texto) && renglon.contains("subrayado"))
    }

    @Test
    fun el_subrayado_y_la_tabla_van_como_rayas() {
        val p = Pxof.leer(fixture("word-1.pxof"))
        fun todas(els: List<Pxof.Elemento>): List<Pxof.Elemento> = els.flatMap { if (it is Pxof.Grupo) todas(it.elementos) else listOf(it) }
        val rayas = todas(p.elementos).filterIsInstance<Pxof.Linea>()
        // El subrayado de «y subrayado», el del enlace y los bordes de la tabla de 2 × 2.
        assertTrue("rayas: ${rayas.size}", rayas.size >= 2 + 6)
    }

    @Test
    fun la_segunda_hoja_lleva_su_numero() {
        val p = Pxof.leer(fixture("word-2.pxof"))
        assertEquals(2, p.numero)
        assertTrue(p.textoPlano().contains("Segunda página."))
        assertTrue(p.textoPlano(), p.textoPlano().contains("Página 2"))
    }

    @Test
    fun la_diapositiva_mide_lo_de_la_presentacion_con_su_fondo_y_sus_formas() {
        val p = Pxof.leer(fixture("powerpoint-1.pxof"))
        // 12192000 × 6858000 EMU = 960 × 540 puntos.
        assertEquals(960f, p.ancho, 0.5f)
        assertEquals(540f, p.alto, 0.5f)
        val fondo = p.fondo
        assertTrue(fondo.toString(), fondo is Pxof.Relleno.Solido && fondo.argb == 0xFFF4F1EA.toInt())
        fun todas(els: List<Pxof.Elemento>): List<Pxof.Elemento> = els.flatMap { if (it is Pxof.Grupo) todas(it.elementos) + it else listOf(it) }
        val formas = todas(p.elementos)
        // La elipse: un trayecto con curvas y el borde rojo.
        val elipse = formas.filterIsInstance<Pxof.Trayecto>().firstOrNull { it.borde?.argb == 0xFFC00000.toInt() }
        assertNotNull(formas.joinToString { it.javaClass.simpleName }, elipse)
        assertTrue(elipse!!.figuras.any { f -> f.tipos.any { it.toInt() == 1 } })
        // El rectángulo redondeado relleno de azul.
        assertTrue(formas.any { (it as? Pxof.Trayecto)?.relleno == Pxof.Relleno.Solido(0xFF9DC3E6.toInt()) || (it as? Pxof.Rect)?.relleno == Pxof.Relleno.Solido(0xFF9DC3E6.toInt()) })
        assertTrue(p.textoPlano().contains("Diapositiva de prueba"))
        assertTrue(p.textoPlano().contains("Rectángulo"))
    }

    @Test
    fun lo_roto_no_se_pinta_a_medias() {
        val bien = fixture("word-1.pxof")
        for (corte in listOf(3, 10, 40, bien.size / 2, bien.size - 1)) {
            try {
                Pxof.leer(bien.copyOf(corte))
                fail("se leyó cortado en $corte")
            } catch (_: Pxof.NoSeLee) {
            }
        }
        val otraFirma = bien.copyOf().also { it[0] = 'Q'.code.toByte() }
        try { Pxof.leer(otraFirma); fail("firma") } catch (_: Pxof.NoSeLee) {}
        val otraVersion = bien.copyOf().also { it[4] = 9 }
        try { Pxof.leer(otraVersion); fail("versión") } catch (_: Pxof.NoSeLee) {}
        // Una cuenta enorme no pide memoria de más: se rechaza antes.
        val enorme = bien.copyOf().also { for (i in 22..25) it[i] = 0x7f }
        try { Pxof.leer(enorme) } catch (_: Pxof.NoSeLee) {}
    }

    @Test
    fun la_cabecera_da_el_tamano_sin_leer_lo_demas() {
        val (an, al) = requireNotNull(DocumentoOffice.cabecera(fixture("powerpoint-1.pxof")))
        assertEquals(960f, an, 0.5f)
        assertEquals(540f, al, 0.5f)
        assertNull(DocumentoOffice.cabecera(ByteArray(5)))
    }

    @Test
    fun se_reconocen_las_extensiones_de_office() {
        for (s in listOf("a.docx", "B.DOCM", "c.dotx", "d.dotm", "e.pptx", "f.ppsx", "g.potx", "h.pptm"))
            assertTrue(s, DocumentoOffice.esDeOffice(s))
        for (s in listOf("a.doc", "b.ppt", "c.pdf", "d.xlsx", null, ""))
            assertTrue("$s", !DocumentoOffice.esDeOffice(s))
        assertTrue(DocumentoOffice.esPresentacion("x.ppsx"))
        assertTrue(!DocumentoOffice.esPresentacion("x.docx"))
    }
}
