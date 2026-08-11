package com.forge.pixpin.markdown

/**
 * Los formatos que ofrece la barra, **en el orden de Telegram**.
 *
 * El orden no es una opinión: es el de `FloatingToolbar.STYLE_BUTTONS`, la lista
 * con la que arman su barra de formato. Se respeta tal cual porque es lo que
 * tiene delante toda su gente y funciona; cambiarlo por gusto sería tirar la
 * única parte del diseño que ya está probada de verdad.
 *
 * Su lista es: `regular`, `bold`, `italic`, `strike`, `mono`, `underline`,
 * `spoiler`, `link`, `quote`, `date`. Aquí faltan dos y sobran cuatro:
 *
 * - **Sin subrayado**: Markdown no tiene marca para subrayar. Ellos pueden
 *   porque guardan estilos aparte del texto; aquí el texto **es** el formato, y
 *   meter `<u>` sería inventarse una marca que nadie escribe a mano.
 * - **Con los de bloque** —título, lista, numerada, bloque de código—, que ellos
 *   no tienen porque un mensaje de chat no es un documento y una nota sí.
 *
 * [marca] es la marca que se pone a ambos lados; los que no envuelven nada la
 * dejan en null y se resuelven por su cuenta.
 */
enum class Formato(val marca: String? = null, val prefijo: String? = null) {
    NEGRITA(marca = "**"),
    CURSIVA(marca = "*"),
    TACHADO(marca = "~~"),
    CODIGO(marca = "`"),
    ENLACE,
    TAPADO(marca = "||"),
    CITA(prefijo = "> "),
    TITULO(prefijo = "# "),
    LISTA(prefijo = "- "),
    NUMERADA(prefijo = "1. "),
    BLOQUE,
    FECHA,
    QUITAR;

    /** Los que envuelven la selección entre marcas iguales. */
    val esEnvolvente: Boolean get() = marca != null

    /** Los que marcan líneas enteras por delante. */
    val esDeLinea: Boolean get() = prefijo != null
}

/**
 * Cómo se reparten los botones entre lo que se ve y lo que hay que desplegar.
 *
 * Es la idea de su `FloatingToolbarPopup`: un panel principal con lo que cabe y
 * un desbordamiento para el resto, no una fila interminable ni un menú donde
 * todo cuesta dos toques. Aquí el reparto es fijo en vez de calculado a partir
 * del ancho porque su barra flota sobre una selección y la nuestra vive sobre el
 * teclado, con un sitio que no cambia.
 */
object BarraDeFormato {

    /** Lo que se usa a todas horas al tomar notas. */
    val principal = listOf(
        Formato.NEGRITA,
        Formato.CURSIVA,
        Formato.TACHADO,
        Formato.CODIGO,
        Formato.ENLACE
    )

    /**
     * El resto, tras el botón de desplegar.
     *
     * [Formato.QUITAR] va el último y no el primero como en su lista: es el
     * único que deshace trabajo, y lo que destruye no se pone donde está el
     * pulgar. En todo lo demás se conserva su orden relativo.
     */
    val desbordamiento = listOf(
        Formato.TAPADO,
        Formato.CITA,
        Formato.TITULO,
        Formato.LISTA,
        Formato.NUMERADA,
        Formato.BLOQUE,
        Formato.FECHA,
        Formato.QUITAR
    )

    val todos: List<Formato> = principal + desbordamiento
}
