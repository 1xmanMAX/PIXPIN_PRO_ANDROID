package com.forge.pixpin.ajustes

/**
 * **El idioma de la aplicación** (1-nov-2026, porte de `idioma.rs` y `PreferenciaIdioma` del PC):
 * español o inglés, como Windows.
 *
 * Tres elecciones, las mismas del PC y guardadas con sus mismas claves (`sistema`, `es`, `en`):
 * - **El del teléfono** ([PreferenciaIdioma.SISTEMA], la de fábrica): español si el teléfono está
 *   en español —cualquier variante: es-ES, es-MX…— y **si no, inglés**. Es la regla del PC y no la
 *   de Android, que con un teléfono en francés caería a `values/`, que es el español: quien tiene
 *   el teléfono en alemán entiende antes el inglés.
 * - **Español** o **English**: la elección explícita siempre gana al sistema.
 *
 * Sin Android: lo prueba `IdiomaTest`. Lo que lo aplica a la aplicación está en [IdiomaDeLaApp].
 */
enum class PreferenciaIdioma(val clave: String) {
    SISTEMA("sistema"),
    ESPANOL("es"),
    INGLES("en");

    companion object {
        /** Lo guardado; algo desconocido o nada es «el del teléfono», como el `#[default]` del PC. */
        fun deClave(clave: String?): PreferenciaIdioma = entries.firstOrNull { it.clave == clave } ?: SISTEMA
    }
}

/** Los dos idiomas con catálogo: `values/` (español, el de fábrica) y `values-en/`. */
enum class Idioma(val etiqueta: String) {
    ESPANOL("es"),
    INGLES("en")
}

/**
 * **Decide el idioma final** (`resolver_idioma` del PC). Del locale del sistema solo se mira la
 * parte del idioma: es-MX y es-ES comparten catálogo, y `_` vale como `-` (`es_ES`).
 */
fun resolverIdioma(localeSistema: String, preferencia: PreferenciaIdioma): Idioma = when (preferencia) {
    PreferenciaIdioma.ESPANOL -> Idioma.ESPANOL
    PreferenciaIdioma.INGLES -> Idioma.INGLES
    PreferenciaIdioma.SISTEMA ->
        if (idiomaDe(localeSistema) == "es") Idioma.ESPANOL else Idioma.INGLES
}

/**
 * **La etiqueta que se le pone a la aplicación** para [idioma]: si el teléfono habla ese mismo
 * idioma se conserva su región (`es-MX`, `en-GB`), para que fechas y números sigan siendo los de
 * allí; si no —un teléfono en francés que acaba en inglés—, el idioma a secas.
 */
fun etiquetaPara(localeSistema: String, idioma: Idioma): String {
    val sistema = localeSistema.replace('_', '-').trim()
    return if (idiomaDe(sistema) == idioma.etiqueta && sistema.length > idioma.etiqueta.length) sistema else idioma.etiqueta
}

private fun idiomaDe(locale: String): String = locale.split('-', '_').firstOrNull().orEmpty().lowercase()
