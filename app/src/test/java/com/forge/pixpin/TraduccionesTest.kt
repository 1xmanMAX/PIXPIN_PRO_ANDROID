package com.forge.pixpin

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * **El inglés no se queda atrás** (1-nov-2026): cada texto de `values/` (el español, el de fábrica)
 * está en `values-en/` y con **los mismos huecos** (`%1$s`, `%d`…). Es la red del test de paridad de
 * catálogos del PC (`los_dos_catalogos_tienen_exactamente_las_mismas_claves`).
 *
 * Un texto que falte en inglés no rompe nada —Android cae al español—, pero se ve; uno con un hueco
 * de menos o cambiado sí revienta (`MissingFormatArgumentException`) en cuanto se pinta. Se lee el
 * XML de las fuentes, como [ManifiestoTest], sin Android.
 */
class TraduccionesTest {

    private val res = File("src/main/res")

    /** nombre → los huecos de su texto (de un plural, los de todas sus formas). */
    private fun textos(carpeta: String): Map<String, Set<String>> {
        val dir = File(res, carpeta)
        val fabrica = DocumentBuilderFactory.newInstance()
        val r = LinkedHashMap<String, Set<String>>()
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.forEach { f ->
            val raiz = fabrica.newDocumentBuilder().parse(f).documentElement
            val hijos = raiz.childNodes
            for (i in 0 until hijos.length) {
                val e = hijos.item(i) as? Element ?: continue
                if (e.getAttribute("translatable") == "false") continue
                val nombre = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> r[nombre] = huecos(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        r[nombre] = (0 until items.length).flatMapTo(sortedSetOf()) { huecos(items.item(it).textContent) }
                    }
                }
            }
        }
        return r
    }

    private val hueco = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""")

    private fun huecos(texto: String): Set<String> = hueco.findAll(texto).map { it.value }.filter { it != "%%" }.toSortedSet()

    @Test
    fun `hay textos en los dos idiomas`() {
        assertTrue("no encuentro ${res.absolutePath}", File(res, "values/strings.xml").isFile)
        assertTrue("no hay values-en", File(res, "values-en/strings.xml").isFile)
        assertTrue(textos("values").size > 1000)
    }

    @Test
    fun `cada texto del español esta en ingles`() {
        val es = textos("values")
        val en = textos("values-en")
        val faltan = es.keys - en.keys
        val sobran = en.keys - es.keys
        assertTrue(
            "values-en/ desincronizado. Faltan en inglés: $faltan. Solo en inglés: $sobran",
            faltan.isEmpty() && sobran.isEmpty()
        )
    }

    @Test
    fun `los huecos de cada texto son los mismos en los dos idiomas`() {
        val es = textos("values")
        val en = textos("values-en")
        val distintos = es.filter { (nombre, h) -> en[nombre]?.let { it != h } == true }
            .map { (nombre, h) -> "  $nombre: español $h, inglés ${en[nombre]}" }
        assertTrue("huecos distintos (reventaría al pintarlo):\n" + distintos.joinToString("\n"), distintos.isEmpty())
    }
}
