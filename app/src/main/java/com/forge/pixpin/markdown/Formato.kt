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
 * Cómo se agrupan los botones.
 *
 * ## Islas, no una barra
 *
 * Su `RichEditorToolbar` **no pone todo en una fila**. Pone varias píldoras
 * redondas separadas, cada una con una familia dentro: los estilos en una
 * (`formattingLayout` principal: negrita, cursiva, subrayado, tachado, tapado,
 * código, sub, super, cita), lo que inserta en otra (`formattingLayout2`: enlace
 * y fecha), y la fórmula en la suya (`formattingLayout3`). El botón de adjuntar
 * y los de deshacer viven aparte, en sus propias píldoras.
 *
 * La separación **es** la información: el hueco entre islas dice «esto de aquí
 * es otra cosa» sin escribir una etiqueta ni pintar una raya. Una fila de trece
 * iconos seguidos obliga a leerlos todos cada vez; cuatro islas de tres se miran
 * de un vistazo.
 *
 * La primera versión de esto copió su **otra** barra —la `FloatingToolbar`, la
 * que flota sobre una selección de texto en un chat—, que sí es una fila con
 * desbordamiento. Son dos barras distintas para dos sitios distintos, y la que
 * toca en un editor de documentos es esta.
 */
object BarraDeFormato {

    /** Los estilos que se ponen sobre lo escrito. Su píldora principal. */
    val estilos = listOf(
        Formato.NEGRITA,
        Formato.CURSIVA,
        Formato.TACHADO,
        Formato.CODIGO,
        Formato.TAPADO,
        Formato.CITA
    )

    /** Lo que mete algo nuevo. Su `formattingLayout2`: enlace y fecha. */
    val insertar = listOf(
        Formato.ENLACE,
        Formato.FECHA
    )

    /** Las islas que se ven siempre, en orden. */
    val pildoras: List<List<Formato>> = listOf(estilos, insertar)

    /**
     * El resto, tras el botón de desplegar.
     *
     * [Formato.QUITAR] va el último: es el único que deshace trabajo, y lo que
     * destruye no se pone donde está el pulgar.
     */
    val desbordamiento = listOf(
        Formato.TITULO,
        Formato.LISTA,
        Formato.NUMERADA,
        Formato.BLOQUE,
        Formato.QUITAR
    )

    val todos: List<Formato> = pildoras.flatten() + desbordamiento
}
