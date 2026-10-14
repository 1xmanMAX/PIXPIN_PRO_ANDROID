package com.forge.pixpin

import com.forge.pixpin.planos.ImprimirPlano
import com.forge.pixpin.planos.ModeloCad
import com.forge.pixpin.planos.PaginaDelPlano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * **La página web del plano, por capas** ([PaginaDelPlano], lo del PC del 13-oct). `capas.pxcapas` lo
 * escribió `libpixpincad.so` (`convertir-plano --capas`) a partir de `capas.dxf`: tres capas, una
 * de ellas apagada en el DWG («EJES <apagada>», con `<` y `>` en el nombre a propósito). Y la
 * página se corre entera en node con un DOM de mentira (`dom-pagina-del-plano.js`): que lea sus datos, pinte,
 * y que encender y apagar las capas cambie lo que pinta.
 */
class PaginaDelPlanoTest {
    private fun recurso(n: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream("planos/$n")) { n }.use { it.readBytes() }

    private fun porCapas(): PaginaDelPlano.PorCapas = requireNotNull(PaginaDelPlano.deBytes(ByteBuffer.wrap(recurso("capas.pxcapas"))))

    private fun visor(): ModeloCad = ModeloCad.deBytes(ByteBuffer.wrap(recurso("prueba.pxcad")))

    private fun opciones(m: ModeloCad, anotado: PaginaDelPlano.Anotado? = null, cotas: List<List<DoubleArray>> = emptyList(), marcos: List<ImprimirPlano.Marco> = emptyList(), vista: DoubleArray? = null) =
        PaginaDelPlano.Opciones(
            titulo = "Plano <de> prueba", origenX = m.origenX, origenY = m.origenY,
            vista = vista ?: m.cajaUtil.map { it.toDouble() }.toDoubleArray(), unidades = m.unidades,
            cotas = cotas, marcos = marcos, anotado = anotado
        )

    @Test
    fun lee_el_plano_por_capas_que_escribe_el_rust() {
        val p = porCapas()
        assertEquals(listOf("0", "EJES <apagada>", "MUROS"), p.grupos.map { it.capa })
        assertEquals("la apagada en el DWG llega apagada", listOf(true, false, true), p.grupos.map { it.encendida })
        assertTrue(p.grupos.all { it.grosor == PaginaDelPlano.GROSOR_NORMAL })
        assertTrue("los ejes: un círculo", p.grupos[1].modelo.arcos.cuantos == 1)
        assertTrue("los muros: rayas", p.grupos[2].modelo.lineas.cuantos >= 4)
        assertTrue("la capa 0: un relleno", p.grupos[0].modelo.triangulos.cuantos >= 3)
        assertTrue(p.imagenes.isEmpty())
    }

    @Test
    fun lo_roto_o_cortado_no_se_entiende() {
        val b = recurso("capas.pxcapas")
        assertNull(PaginaDelPlano.deBytes(ByteBuffer.wrap(b.copyOf(b.size - 3))))
        assertNull(PaginaDelPlano.deBytes(ByteBuffer.wrap(b + byteArrayOf(0))))
        assertNull(PaginaDelPlano.deBytes(ByteBuffer.wrap("PXCAPAS0".toByteArray() + b.copyOfRange(8, b.size))))
        assertNull("un .pxcad no es un plano por capas", PaginaDelPlano.deBytes(ByteBuffer.wrap(recurso("prueba.pxcad"))))
    }

    /** Las palabras comprimidas de la página, y su JSON. */
    private fun partes(html: String): Pair<IntArray, String> {
        val gz = Regex("""<script id="plano-gz"[^>]*>([^<]*)</script>""").find(html)!!.groupValues[1]
        val bytes = GZIPInputStream(java.util.Base64.getDecoder().decode(gz).inputStream()).readBytes()
        assertEquals(0, bytes.size % 4)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val w = IntArray(b.remaining()).also { b.get(it) }
        val json = Regex("""<script id="datos" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        return w to json
    }

    @Test
    fun la_pagina_lleva_las_capas_los_datos_y_nada_de_internet() {
        val p = porCapas()
        val m = p.grupos.first().modelo
        val html = PaginaDelPlano.pagina(p.grupos, opciones(m))
        val (w, json) = partes(html)
        assertTrue(json.contains("\"capas\":[{\"n\":\"0\",\"on\":true"))
        assertTrue("el nombre no cierra el guion", json.contains("\"n\":\"EJES \\u003capagada\\u003e\",\"on\":false"))
        assertFalse(json.contains("<apagada>"))
        assertEquals("un grupo por capa y grosor", 3, Regex("\"k\":").findAll(json).count())
        // Las fichas de los tramos apuntan dentro de los datos.
        for (g in Regex(""""(L|T|H|A|G)":\[(\d+),(\d+)\]""").findAll(json)) {
            val sitio = g.groupValues[2].toInt(); val n = g.groupValues[3].toInt()
            assertTrue("${g.value} cabe en ${w.size}", sitio + 7 * n <= w.size)
            for (k in 0 until n) {
                val desde = w[sitio + 7 * k + 5]; val cuantos = w[sitio + 7 * k + 6]
                assertTrue(desde in 0 until w.size && cuantos > 0)
            }
        }
        assertTrue(html.contains("<title>Plano &lt;de&gt; prueba</title>"))
        assertFalse(html.contains("<button id=\"b-anot\""))
        for (fuera in listOf("src=\"http", "href=\"http", "@import", "url(")) assertFalse(fuera, html.contains(fuera))
        assertTrue(html.contains("DecompressionStream"))
    }

    @Test
    fun sin_capas_va_el_modelo_del_visor_con_cotas_marcos_y_lo_anotado() {
        val m = visor()
        val svg = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20" viewBox="-5 2.5 40 20"><path d="M0 0L10 10"/></svg>"""
        val anotado = requireNotNull(PaginaDelPlano.anotadoDe(svg, 0.25, 1.0, 2.0))
        assertEquals(listOf(-5.0, 2.5, 35.0, 22.5), anotado.caja.toList())
        assertFalse(anotado.svg.contains("<?xml"))
        assertNull(PaginaDelPlano.anotadoDe("<svg></svg>", 1.0, 0.0, 0.0))
        val html = PaginaDelPlano.pagina(
            PaginaDelPlano.sinCapas(m),
            opciones(m, anotado, cotas = listOf(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(3.0, 4.0))), marcos = listOf(ImprimirPlano.Marco(0.0, 0.0, 5.0, 2.0)))
        )
        val (_, json) = partes(html)
        assertTrue(json.contains("\"capas\":[{\"n\":\"0\",\"on\":true"))
        assertTrue(json.contains("\"cotas\":[[0,0,3,4]]"))
        assertTrue(json.contains("\"marcos\":[[0,0,5,2]]"))
        assertTrue(json.contains("\"anotado\":{\"caja\":[-5,2.5,35,22.5],\"u\":0.25,\"cero\":[1,2]}"))
        assertTrue(json.contains("\"unidad\":\"m\""))
        assertTrue(html.contains("<div id=\"anot\"><svg"))
        assertTrue(html.contains("<button id=\"b-anot\""))
    }

    @Test
    fun la_pagina_pesa_poco_mas_que_el_plano() {
        val m = visor()
        val html = PaginaDelPlano.pagina(PaginaDelPlano.sinCapas(m), opciones(m))
        val (w, _) = partes(html)
        // El armazón (estilo y guion) son ~22 KB; los datos, comprimidos, menos que la caché del visor.
        val gz = Regex("""<script id="plano-gz"[^>]*>([^<]*)</script>""").find(html)!!.groupValues[1].length
        assertTrue("datos ${w.size * 4} B, en base64 $gz B, caché ${recurso("prueba.pxcad").size} B", gz < recurso("prueba.pxcad").size)
        assertTrue("página ${html.length} B", html.length < 30_000)
    }

    @Test
    fun los_numeros_del_json_son_validos() {
        assertEquals("0", PaginaDelPlano.num(Double.NaN))
        assertEquals("0", PaginaDelPlano.num(-0.0000001))
        assertEquals("1.5", PaginaDelPlano.num(1.5))
        assertEquals("-2", PaginaDelPlano.num(-2.0))
        assertEquals("123456.123457", PaginaDelPlano.num(123456.1234567))
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\\u2028\"", PaginaDelPlano.textoJson("a\"b\\c\n\u0001 "))
    }

    // ---------------------------------------------------------------- en node

    private fun node(): String? {
        val candidatos = ArrayList<String>()
        System.getenv("NODE")?.let { candidatos += it }
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).forEach { candidatos += "$it/node" }
        File("/root/.nvm/versions/node").listFiles()?.forEach { candidatos += "${it.path}/bin/node" }
        candidatos += listOf("/usr/bin/node", "/usr/local/bin/node")
        return candidatos.firstOrNull { File(it).canExecute() }
    }

    private fun correr(html: String): String {
        val n = node()
        assumeTrue("sin node", n != null)
        val dir = File.createTempFile("pagina-del-plano", "").apply { delete(); mkdirs(); deleteOnExit() }
        val pagina = File(dir, "plano.html").apply { writeText(html) }
        val dom = File(dir, "dom-pagina-del-plano.js").apply { writeBytes(recurso("dom-pagina-del-plano.js")) }
        val p = ProcessBuilder(n!!, dom.path, pagina.path).redirectErrorStream(true).start()
        val salida = p.inputStream.bufferedReader().readText()
        assertTrue("node no acabó", p.waitFor(60, TimeUnit.SECONDS))
        assertEquals("node terminó con error:\n$salida", 0, p.exitValue())
        dir.deleteRecursively()
        return salida.trim().lines().last()
    }

    private fun cuenta(json: String, grupo: String, campo: String): Int =
        Regex(""""$grupo":\{([^}]*)\}""").find(json)!!.groupValues[1].let { Regex(""""$campo":(\d+)""").find(it)!!.groupValues[1].toInt() }

    @Test
    fun en_node_pinta_y_las_capas_se_encienden_y_apagan() {
        val p = porCapas()
        val m = p.grupos.first().modelo
        // Lo que se ve al abrir: todo el plano (la caja de un solo grupo dejaría los muros fuera).
        val vista = PaginaDelPlano.cajaDe(p.grupos, m.origenX, m.origenY)
        assertEquals(10.0, vista[2] - vista[0], 1e-3)
        assertEquals("de la raya de los ejes (−1) al techo de los muros (5)", 7.0, vista[3] - vista[1], 1e-3)
        val r = correr(PaginaDelPlano.pagina(p.grupos, opciones(m, vista = vista)))
        assertTrue(r, r.contains("\"listo\":true"))
        assertTrue(r, r.contains("\"errores\":[]"))
        // Al abrir, sin los ejes (apagados en el DWG): las rayas de los muros y el relleno, sin el círculo.
        assertTrue(r, cuenta(r, "alAbrir", "stroke") > 0 && cuenta(r, "alAbrir", "fill") > 0)
        assertEquals(r, 0, cuenta(r, "alAbrir", "arc"))
        // «Ninguna»: nada del plano.
        assertEquals(r, 0, cuenta(r, "ninguna", "stroke"))
        assertEquals(r, 0, cuenta(r, "ninguna", "fill"))
        // «Todas»: también el círculo de los ejes.
        assertTrue(r, cuenta(r, "todas", "arc") >= 1)
        assertTrue(r, cuenta(r, "todas", "stroke") > cuenta(r, "alAbrir", "stroke"))
        assertTrue(r, r.contains("\"capas\":[\"0\",\"EJES <apagada>\",\"MUROS\"]"))
    }

    @Test
    fun en_node_el_plano_del_visor_con_letras_y_lo_anotado() {
        val m = visor()
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\" viewBox=\"0 0 10 10\"><path d=\"M0 0L10 10\"/></svg>"
        val r = correr(PaginaDelPlano.pagina(PaginaDelPlano.sinCapas(m), opciones(m, PaginaDelPlano.anotadoDe(svg, 0.5, 0.0, 0.0))))
        assertTrue(r, r.contains("\"listo\":true"))
        assertTrue(r, r.contains("\"errores\":[]"))
        assertTrue("el círculo y el arco: $r", cuenta(r, "alAbrir", "arc") >= 2)
        assertTrue("las letras: $r", cuenta(r, "alAbrir", "addPath") >= 8)
        assertNotNull(Regex(""""transformAnot":"matrix\(""").find(r))
    }
}
