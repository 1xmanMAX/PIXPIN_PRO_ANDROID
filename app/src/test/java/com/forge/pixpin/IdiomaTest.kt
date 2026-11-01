package com.forge.pixpin.ajustes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **La regla del idioma** (1-nov-2026): las pruebas de `resolver_idioma` del PC (`idioma.rs`), las
 * mismas, y lo que añade el teléfono (la región que se conserva y las claves guardadas).
 */
class IdiomaTest {

    @Test
    fun `la preferencia explicita gana al sistema`() {
        assertEquals(Idioma.INGLES, resolverIdioma("es-ES", PreferenciaIdioma.INGLES))
        assertEquals(Idioma.ESPANOL, resolverIdioma("en-US", PreferenciaIdioma.ESPANOL))
        assertEquals(Idioma.ESPANOL, resolverIdioma("fr-FR", PreferenciaIdioma.ESPANOL))
    }

    @Test
    fun `en modo sistema se mira el locale`() {
        assertEquals(Idioma.ESPANOL, resolverIdioma("es-ES", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.ESPANOL, resolverIdioma("es-MX", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.ESPANOL, resolverIdioma("es_419", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.ESPANOL, resolverIdioma("es", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("en-GB", PreferenciaIdioma.SISTEMA))
    }

    /**
     * Inglés y no español —que es lo que haría Android, cayendo a `values/`—: quien tiene el
     * teléfono en francés o en alemán entiende antes el inglés. Es la regla del PC.
     */
    @Test
    fun `un idioma no soportado cae a ingles`() {
        assertEquals(Idioma.INGLES, resolverIdioma("fr-FR", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("de-DE", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("basura", PreferenciaIdioma.SISTEMA))
        // «est» (estonio) empieza por «es» pero no es español: se mira el idioma entero.
        assertEquals(Idioma.INGLES, resolverIdioma("et-EE", PreferenciaIdioma.SISTEMA))
        assertEquals(Idioma.INGLES, resolverIdioma("esx", PreferenciaIdioma.SISTEMA))
    }

    @Test
    fun `se guarda con las claves del PC y lo desconocido es el del telefono`() {
        assertEquals(listOf("sistema", "es", "en"), PreferenciaIdioma.entries.map { it.clave })
        for (p in PreferenciaIdioma.entries) assertEquals(p, PreferenciaIdioma.deClave(p.clave))
        assertEquals(PreferenciaIdioma.SISTEMA, PreferenciaIdioma.deClave(null))
        assertEquals(PreferenciaIdioma.SISTEMA, PreferenciaIdioma.deClave("fr"))
    }

    @Test
    fun `la region se conserva solo si el telefono habla ese idioma`() {
        assertEquals("es-MX", etiquetaPara("es-MX", Idioma.ESPANOL))
        assertEquals("en-GB", etiquetaPara("en_GB", Idioma.INGLES))
        // Un teléfono en francés acaba en inglés, a secas: «en-FR» no es nada.
        assertEquals("en", etiquetaPara("fr-FR", Idioma.INGLES))
        assertEquals("es", etiquetaPara("en-US", Idioma.ESPANOL))
        assertEquals("es", etiquetaPara("es", Idioma.ESPANOL))
        assertEquals("en", etiquetaPara("", Idioma.INGLES))
    }
}
