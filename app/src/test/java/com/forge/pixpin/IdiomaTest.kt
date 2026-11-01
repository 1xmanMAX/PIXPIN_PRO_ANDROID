package com.forge.pixpin.ajustes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La regla del idioma** (1-nov-2026): las pruebas de `resolver_idioma` del PC (`idioma.rs`, los
 * catorce idiomas), las mismas, y lo que añade el teléfono (la región que se conserva, la red al
 * inglés y las claves guardadas).
 */
class IdiomaTest {

    @Test
    fun `la preferencia explicita gana al sistema`() {
        assertEquals(Idioma.INGLES, resolverIdioma("es-ES", PreferenciaIdioma.INGLES))
        assertEquals(Idioma.ESPANOL, resolverIdioma("en-US", PreferenciaIdioma.ESPANOL))
        assertEquals(Idioma.ESPANOL, resolverIdioma("fr-FR", PreferenciaIdioma.ESPANOL))
        assertEquals(Idioma.JAPONES, resolverIdioma("es-ES", PreferenciaIdioma.JAPONES))
        for (p in PreferenciaIdioma.entries) {
            val i = p.idioma ?: continue
            assertEquals(i, resolverIdioma("ko-KR", p))
        }
    }

    @Test
    fun `en modo sistema se mira el locale`() {
        assertEquals(Idioma.ESPANOL, resolverIdioma("es-ES", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.ESPANOL, resolverIdioma("es-MX", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.ESPANOL, resolverIdioma("es_419", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.ESPANOL, resolverIdioma("es", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("en-GB", PreferenciaIdioma.SISTEMA))
    }

    /** `el_locale_del_sistema_cae_en_el_idioma_mas_cercano` del PC, con sus mismos casos. */
    @Test
    fun `el locale del sistema cae en el idioma mas cercano`() {
        for ((locale, idioma) in listOf(
            "zh-CN" to Idioma.CHINO_SIMPLIFICADO,
            "zh-Hans-CN" to Idioma.CHINO_SIMPLIFICADO,
            "zh-SG" to Idioma.CHINO_SIMPLIFICADO,
            // Tradicional: aún sin catálogo propio, al simplificado.
            "zh-TW" to Idioma.CHINO_SIMPLIFICADO,
            "zh-Hant-HK" to Idioma.CHINO_SIMPLIFICADO,
            "pt-PT" to Idioma.PORTUGUES,
            "pt-BR" to Idioma.PORTUGUES,
            "hi-IN" to Idioma.HINDI,
            "ar-EG" to Idioma.ARABE,
            "fr-CA" to Idioma.FRANCES,
            "de-AT" to Idioma.ALEMAN,
            "ja-JP" to Idioma.JAPONES,
            "ru-RU" to Idioma.RUSO,
            "ko-KR" to Idioma.COREANO,
            "it-CH" to Idioma.ITALIANO,
            "id-ID" to Idioma.INDONESIO,
            "in-ID" to Idioma.INDONESIO,
            "tr-TR" to Idioma.TURCO,
            "fr_FR.UTF-8" to Idioma.FRANCES,
            "ES-es" to Idioma.ESPANOL,
            "sw-KE" to Idioma.INGLES,
        )) {
            assertEquals(locale, idioma, resolverIdioma(locale, PreferenciaIdioma.SISTEMA))
        }
    }

    /**
     * Inglés y no español —que es lo que haría Android, cayendo a `values/`—: quien tiene el
     * teléfono en neerlandés entiende antes el inglés. Es la regla del PC.
     */
    @Test
    fun `un idioma no soportado cae a ingles`() {
        assertEquals(Idioma.INGLES, resolverIdioma("nl-NL", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("sv-SE", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("basura", PreferenciaIdioma.SISTEMA))
        // «est» (estonio) empieza por «es» pero no es español: se mira el idioma entero.
        assertEquals(Idioma.INGLES, resolverIdioma("et-EE", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("esx", PreferenciaIdioma.SISTEMA))
    }

    @Test
    fun `se guarda con las claves del PC y lo desconocido es el del telefono`() {
        assertEquals(
            listOf("sistema", "es", "en", "zh-CN", "hi", "ar", "pt-BR", "fr", "de", "ja", "ru", "ko", "it", "id", "tr"),
            PreferenciaIdioma.entries.map { it.clave }
        )
        for (p in PreferenciaIdioma.entries) assertEquals(p, PreferenciaIdioma.deClave(p.clave))
        // Los alias que el PC también lee.
        assertEquals(PreferenciaIdioma.ESPANOL, PreferenciaIdioma.deClave("es-ES"))
        assertEquals(PreferenciaIdioma.CHINO_SIMPLIFICADO, PreferenciaIdioma.deClave("zh-Hans"))
        assertEquals(PreferenciaIdioma.PORTUGUES, PreferenciaIdioma.deClave("pt"))
        assertEquals(PreferenciaIdioma.TURCO, PreferenciaIdioma.deClave("tr-TR"))
        assertEquals(PreferenciaIdioma.SISTEMA, PreferenciaIdioma.deClave(null))
        assertEquals(PreferenciaIdioma.SISTEMA, PreferenciaIdioma.deClave("nl"))
        for (i in Idioma.entries) assertEquals(i, PreferenciaIdioma.de(i).idioma)
    }

    @Test
    fun `cada idioma se nombra en su lengua y solo el arabe va al reves`() {
        assertEquals(14, Idioma.entries.size)
        assertEquals(Idioma.entries.size, Idioma.entries.map { it.nombrePropio }.toSet().size)
        assertEquals("العربية", Idioma.ARABE.nombrePropio)
        assertEquals("中文 (简体)", Idioma.CHINO_SIMPLIFICADO.nombrePropio)
        assertEquals(listOf(Idioma.ARABE), Idioma.entries.filter { it.esRtl })
        for (i in Idioma.entries) assertTrue(i.etiqueta.startsWith(i.codigo))
    }

    @Test
    fun `la red es el ingles y despues el espanol`() {
        assertEquals(listOf(Idioma.JAPONES, Idioma.INGLES, Idioma.ESPANOL), Idioma.JAPONES.cadena)
        assertEquals(listOf(Idioma.INGLES, Idioma.ESPANOL), Idioma.INGLES.cadena)
        assertEquals(listOf(Idioma.ESPANOL, Idioma.INGLES), Idioma.ESPANOL.cadena)
        // A Android se le da el inglés detrás de cualquier idioma que no sea español ni inglés.
        assertEquals("ja-JP,en", etiquetasPara("ja-JP", Idioma.JAPONES))
        assertEquals("zh-CN,en", etiquetasPara("zh-TW", Idioma.CHINO_SIMPLIFICADO))
        assertEquals("es-MX", etiquetasPara("es-MX", Idioma.ESPANOL))
        assertEquals("en", etiquetasPara("nl-NL", Idioma.INGLES))
    }

    @Test
    fun `la region se conserva solo si el telefono habla ese idioma`() {
        assertEquals("es-MX", etiquetaPara("es-MX", Idioma.ESPANOL))
        assertEquals("en-GB", etiquetaPara("en_GB", Idioma.INGLES))
        assertEquals("ar-EG", etiquetaPara("ar-EG", Idioma.ARABE))
        assertEquals("fr-CA", etiquetaPara("fr-CA", Idioma.FRANCES))
        assertEquals("in-ID", etiquetaPara("in-ID", Idioma.INDONESIO))
        // Un teléfono en neerlandés acaba en inglés, a secas: «en-NL» no es nada.
        assertEquals("en", etiquetaPara("nl-NL", Idioma.INGLES))
        assertEquals("es", etiquetaPara("en-US", Idioma.ESPANOL))
        assertEquals("ja", etiquetaPara("es-ES", Idioma.JAPONES))
        assertEquals("es", etiquetaPara("es", Idioma.ESPANOL))
        assertEquals("en", etiquetaPara("", Idioma.INGLES))
        // Chino y portugués, siempre con su región: su carpeta la lleva (values-zh-rCN, values-pt-rBR).
        assertEquals("zh-CN", etiquetaPara("zh-TW", Idioma.CHINO_SIMPLIFICADO))
        assertEquals("zh-CN", etiquetaPara("zh-Hans-CN", Idioma.CHINO_SIMPLIFICADO))
        assertEquals("pt-BR", etiquetaPara("pt-PT", Idioma.PORTUGUES))
        assertEquals("pt-BR", etiquetaPara("en-US", Idioma.PORTUGUES))
    }
}
