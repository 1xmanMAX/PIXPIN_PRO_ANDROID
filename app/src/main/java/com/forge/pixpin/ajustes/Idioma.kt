package com.forge.pixpin.ajustes

/**
 * **El idioma de la aplicación** (1-nov-2026, porte de `idioma.rs` y `PreferenciaIdioma` del PC,
 * commit f87a6712 «catorce idiomas con red al inglés y al español»): los catorce idiomas de Windows.
 *
 * Quince elecciones, las mismas del PC y guardadas con sus mismas claves (`sistema`, `es`, `en`,
 * `zh-CN`, `hi`, `ar`, `pt-BR`, `fr`, `de`, `ja`, `ru`, `ko`, `it`, `id`, `tr`), que también acepta
 * con los alias del PC (`es-ES`, `zh-Hans`, `pt`…):
 * - **El del teléfono** ([PreferenciaIdioma.SISTEMA], la de fábrica): el idioma del teléfono si es uno
 *   de los catorce —cualquier región: es-MX, fr-CA, ar-EG…; chino tradicional al simplificado, el
 *   portugués de Portugal al de Brasil— y **si no, inglés**. Es la regla del PC y no la de Android,
 *   que con un teléfono en neerlandés caería a `values/`, que es el español.
 * - **Un idioma concreto**: la elección explícita siempre gana al sistema.
 *
 * Sin Android: lo prueba `IdiomaTest`. Lo que lo aplica a la aplicación está en [IdiomaDeLaApp].
 */
enum class PreferenciaIdioma(val clave: String, val idioma: Idioma?, private val alias: List<String> = emptyList()) {
    SISTEMA("sistema", null),
    ESPANOL("es", Idioma.ESPANOL, listOf("es-ES")),
    INGLES("en", Idioma.INGLES, listOf("en-US")),
    CHINO_SIMPLIFICADO("zh-CN", Idioma.CHINO_SIMPLIFICADO, listOf("zh", "zh-Hans")),
    HINDI("hi", Idioma.HINDI, listOf("hi-IN")),
    ARABE("ar", Idioma.ARABE, listOf("ar-SA")),
    PORTUGUES("pt-BR", Idioma.PORTUGUES, listOf("pt")),
    FRANCES("fr", Idioma.FRANCES, listOf("fr-FR")),
    ALEMAN("de", Idioma.ALEMAN, listOf("de-DE")),
    JAPONES("ja", Idioma.JAPONES, listOf("ja-JP")),
    RUSO("ru", Idioma.RUSO, listOf("ru-RU")),
    COREANO("ko", Idioma.COREANO, listOf("ko-KR")),
    ITALIANO("it", Idioma.ITALIANO, listOf("it-IT")),
    INDONESIO("id", Idioma.INDONESIO, listOf("id-ID")),
    TURCO("tr", Idioma.TURCO, listOf("tr-TR"));

    companion object {
        /** Lo guardado; algo desconocido o nada es «el del teléfono», como el `#[default]` del PC. */
        fun deClave(clave: String?): PreferenciaIdioma =
            entries.firstOrNull { it.clave == clave || clave in it.alias } ?: SISTEMA

        /** La que fuerza [idioma] (`PreferenciaIdioma::de` del PC). */
        fun de(idioma: Idioma): PreferenciaIdioma = entries.first { it.idioma == idioma }
    }
}

/**
 * Los catorce idiomas, **en el orden del PC** (`Idioma::TODOS`, el del selector).
 *
 * - [etiqueta]: la etiqueta que se le da a Android y la carpeta de recursos: `values/` para el
 *   español (el de fábrica), `values-en/`, `values-zh-rCN/`, `values-pt-rBR/`, `values-in/` (el
 *   indonesio, con el código viejo que usa Android para la carpeta)…
 * - [nombrePropio]: el nombre en su propia lengua, el que sale en el selector (`nombre_propio` del
 *   PC), para que lo encuentre quien no lee el idioma puesto.
 */
enum class Idioma(val etiqueta: String, val nombrePropio: String) {
    ESPANOL("es", "Español"),
    INGLES("en", "English"),
    /** Chino simplificado; el tradicional (Taiwán, Hong Kong) cae aquí mientras no tenga catálogo. */
    CHINO_SIMPLIFICADO("zh-CN", "中文 (简体)"),
    HINDI("hi", "हिन्दी"),
    /** Se escribe de derecha a izquierda (el manifiesto lleva `supportsRtl`). */
    ARABE("ar", "العربية"),
    /** Portugués de Brasil; el de Portugal cae aquí. */
    PORTUGUES("pt-BR", "Português (Brasil)"),
    FRANCES("fr", "Français"),
    ALEMAN("de", "Deutsch"),
    JAPONES("ja", "日本語"),
    RUSO("ru", "Русский"),
    COREANO("ko", "한국어"),
    ITALIANO("it", "Italiano"),
    INDONESIO("id", "Bahasa Indonesia"),
    TURCO("tr", "Türkçe");

    /** Solo el idioma (`es`, `zh`, `ar`…). */
    val codigo: String get() = etiqueta.substringBefore('-')

    /** Si se escribe de derecha a izquierda (`es_rtl` del PC). */
    val esRtl: Boolean get() = this == ARABE

    /**
     * Dónde se busca un texto, en orden (`Idioma::cadena` del PC): el propio idioma, inglés y
     * español. En Android el español es `values/`, al que siempre se acaba cayendo.
     */
    val cadena: List<Idioma> get() = listOf(this, INGLES, ESPANOL).distinct()
}

/**
 * **Decide el idioma final** (`resolver_idioma` del PC). La preferencia explícita siempre gana; si
 * no, el idioma soportado más cercano al locale del sistema (`idioma_del_locale`): se mira la parte
 * del idioma (`es-MX` y `es-ES` comparten catálogo; `_`, `.` y `@` valen como `-`), sin distinguir
 * mayúsculas. Sin parecido, inglés.
 */
fun resolverIdioma(localeSistema: String, preferencia: PreferenciaIdioma): Idioma =
    preferencia.idioma ?: idiomaDelLocale(localeSistema)

private fun idiomaDelLocale(locale: String): Idioma = when (idiomaDe(locale)) {
    "es" -> Idioma.ESPANOL
    "en" -> Idioma.INGLES
    // zh-Hans, zh-CN, zh-SG: simplificado. zh-Hant, zh-TW, zh-HK serían tradicional; mientras no
    // haya catálogo, simplificado antes que inglés (se lee, aunque con otras formas).
    "zh" -> Idioma.CHINO_SIMPLIFICADO
    "hi" -> Idioma.HINDI
    "ar" -> Idioma.ARABE
    // pt-PT cae al de Brasil: se entiende mucho mejor que el inglés.
    "pt" -> Idioma.PORTUGUES
    "fr" -> Idioma.FRANCES
    "de" -> Idioma.ALEMAN
    "ja" -> Idioma.JAPONES
    "ru" -> Idioma.RUSO
    "ko" -> Idioma.COREANO
    "it" -> Idioma.ITALIANO
    // `in` es el código viejo del indonesio (Java y Android lo siguen dando).
    "id", "in" -> Idioma.INDONESIO
    "tr" -> Idioma.TURCO
    else -> Idioma.INGLES
}

/**
 * **La etiqueta que se le pone a la aplicación** para [idioma]: si el teléfono habla ese mismo
 * idioma se conserva su región (`es-MX`, `en-GB`, `ar-EG`), para que fechas y números sigan siendo
 * los de allí; si no —un teléfono en neerlandés que acaba en inglés—, el idioma a secas.
 *
 * El chino y el portugués van siempre con la suya (`zh-CN`, `pt-BR`): su carpeta lleva región y
 * Android no le daría `values-zh-rCN/` a un `zh-TW` ni `values-pt-rBR/` a un `pt-PT`, que caerían al
 * español de `values/`.
 */
fun etiquetaPara(localeSistema: String, idioma: Idioma): String {
    if ('-' in idioma.etiqueta) return idioma.etiqueta
    val sistema = localeSistema.replace('_', '-').trim()
    val mismo = idiomaDe(sistema) == idioma.codigo ||
        (idioma == Idioma.INDONESIO && idiomaDe(sistema) == "in")
    return if (mismo && sistema.length > idioma.codigo.length) sistema else idioma.etiqueta
}

/**
 * **La lista de idiomas que se le da a Android**: la [etiquetaPara] y, detrás, el inglés si el
 * idioma no es español ni inglés. Es la red del PC (lo que falte, al inglés): si un idioma no
 * tuviera aún su carpeta, Android escoge el inglés de la lista antes que el español de `values/`.
 * Una clave suelta que faltara en una carpeta sí saldría en español —Android elige un solo idioma
 * por carpeta—; por eso `TraduccionesTest` exige todas las claves en todas.
 */
fun etiquetasPara(localeSistema: String, idioma: Idioma): String {
    val primera = etiquetaPara(localeSistema, idioma)
    return if (idioma == Idioma.ESPANOL || idioma == Idioma.INGLES) primera else "$primera,en"
}

private fun idiomaDe(locale: String): String =
    locale.trim().split('-', '_', '.', '@').firstOrNull().orEmpty().lowercase()
