package com.forge.pixpin

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * **Ningún idioma se queda atrás** (1-nov-2026; catorce desde el porte de f87a6712 del PC): cada
 * texto de `values/` (el español, el de fábrica) está en **cada** `values-XX/` que tenga textos
 * (`values-en/`, `values-zh-rCN/`, `values-ja/`, `values-ar/`…) y con **los mismos huecos**
 * (`%1$s`, `%d`…). Es la red del test de paridad de catálogos del PC
 * (`los_dos_catalogos_tienen_exactamente_las_mismas_claves`), extendida a todos.
 *
 * Un texto que falte en un idioma no rompe nada, pero sale en español: Android elige una sola
 * carpeta por idioma y lo que le falte cae a `values/`, no al inglés como en el PC. Uno con un
 * hueco de menos o cambiado sí revienta (`MissingFormatArgumentException`) en cuanto se pinta. Se
 * lee el XML de las fuentes, como [ManifiestoTest], sin Android.
 */
class TraduccionesTest {

    private val res = File("src/main/res")

    /** nombre → los huecos de su texto (de un plural, los de todas sus formas). */
    private fun textos(carpeta: String): Map<String, Set<String>> = leer(carpeta).first

    /** nombre de cada plural → sus cantidades (`one`, `other`…). */
    private fun cantidades(carpeta: String): Map<String, Set<String>> = leer(carpeta).second

    private fun leer(carpeta: String): Pair<Map<String, Set<String>>, Map<String, Set<String>>> {
        val dir = File(res, carpeta)
        val fabrica = DocumentBuilderFactory.newInstance()
        val r = LinkedHashMap<String, Set<String>>()
        val q = LinkedHashMap<String, Set<String>>()
        archivos(dir).forEach { f ->
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
                        q[nombre] = (0 until items.length).mapTo(sortedSetOf()) { (items.item(it) as Element).getAttribute("quantity") }
                    }
                }
            }
        }
        return r to q
    }

    private fun archivos(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }

    /** Las carpetas `values-XX/` con textos: una por idioma traducido. */
    private fun traducciones(): List<String> =
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && archivos(f).isNotEmpty() }
            .orEmpty().map { it.name }.sorted()

    private val hueco = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""")

    private fun huecos(texto: String): Set<String> = hueco.findAll(texto).map { it.value }.filter { it != "%%" }.toSortedSet()

    @Test
    fun `hay textos en los idiomas`() {
        assertTrue("no encuentro ${res.absolutePath}", File(res, "values/strings.xml").isFile)
        assertTrue("no hay values-en", File(res, "values-en/strings.xml").isFile)
        assertTrue(textos("values").size > 1000)
        // Los seis de esta tanda; el resto de los catorce los añade su propia carpeta.
        for (c in listOf("values-en", "values-zh-rCN", "values-ja", "values-ko", "values-hi", "values-ar", "values-tr")) {
            assertTrue("falta $c", c in traducciones())
        }
    }

    @Test
    fun `cada texto del espanol esta en cada idioma`() {
        val es = textos("values")
        val mal = traducciones().mapNotNull { c ->
            val otro = textos(c)
            val faltan = es.keys - otro.keys
            val sobran = otro.keys - es.keys
            if (faltan.isEmpty() && sobran.isEmpty()) null else "  $c/ desincronizado. Faltan: $faltan. Sobran: $sobran"
        }
        assertTrue("idiomas desincronizados:\n" + mal.joinToString("\n"), mal.isEmpty())
    }

    @Test
    fun `los huecos de cada texto son los mismos en cada idioma`() {
        val es = textos("values")
        val distintos = traducciones().flatMap { c ->
            val otro = textos(c)
            es.filter { (nombre, h) -> otro[nombre]?.let { it != h } == true }
                .map { (nombre, h) -> "  $c/$nombre: español $h, traducción ${otro[nombre]}" }
        }
        assertTrue("huecos distintos (reventaría al pintarlo):\n" + distintos.joinToString("\n"), distintos.isEmpty())
    }

    /**
     * Las formas de plural de cada idioma son las de CLDR (las mismas que usa Fluent en el PC): chino,
     * japonés y coreano solo `other`; árabe las seis; ruso `one`/`few`/`many`/`other`… Una que sobre la
     * marca lint; una que falte sale en español. Todos llevan `other`.
     */
    @Test
    fun `los plurales llevan las formas de su idioma`() {
        val formas = mapOf(
            "zh" to setOf("other"), "ja" to setOf("other"), "ko" to setOf("other"), "in" to setOf("other"),
            "ar" to setOf("zero", "one", "two", "few", "many", "other"),
            "ru" to setOf("one", "few", "many", "other"),
            "en" to setOf("one", "other"), "hi" to setOf("one", "other"), "tr" to setOf("one", "other"),
            "de" to setOf("one", "other"), "it" to setOf("one", "many", "other"),
            "fr" to setOf("one", "many", "other"), "pt" to setOf("one", "many", "other"),
        )
        val mal = traducciones().flatMap { c ->
            val idioma = c.removePrefix("values-").substringBefore('-')
            cantidades(c).mapNotNull { (nombre, q) ->
                val esperado = formas[idioma]
                when {
                    "other" !in q -> "  $c/$nombre: sin «other»"
                    esperado != null && !esperado.containsAll(q) -> "  $c/$nombre: $q, pero $idioma solo tiene $esperado"
                    else -> null
                }
            }
        }
        assertTrue("plurales con formas que no son las del idioma:\n" + mal.joinToString("\n"), mal.isEmpty())
    }

    /** Lo que sale en Ajustes del sistema › Idioma de las apps son los catorce del PC. */
    @Test
    fun `locales_config tiene los catorce idiomas`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
        val locales = doc.getElementsByTagName("locale")
        val nombres = (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") }
        assertEquals(com.forge.pixpin.ajustes.Idioma.entries.map { it.etiqueta }, nombres)
    }
}
