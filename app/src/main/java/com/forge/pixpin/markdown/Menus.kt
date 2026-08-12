package com.forge.pixpin.markdown

/**
 * Cómo se agrupan los bloques detrás de los botones de la barra.
 *
 * ## Lo que hacen ellos
 *
 * Su barra enseña **cuatro botones** —texto, listas, tabla, fórmula— y cada uno
 * abre un menú anclado con lo suyo dentro (`ChatAttachAlertRichLayout`:
 * `showTextTypeMenu`, `showListMenu`). El de texto lleva encabezado, texto,
 * cita, destacado, código y pie; el de listas lleva ninguna, viñetas, numerada,
 * casillas y plegable. Y los seis niveles de encabezado están **dentro** del
 * encabezado, en un panel que entra por el lado con un «atrás» arriba.
 *
 * Es un árbol de dos niveles, no una lista de veintitrés. Cuatro botones caben
 * en cualquier pantalla y se recuerdan; veintitrés no.
 *
 * ## Lo importante, que no es la forma
 *
 * Sus menús usan `addChecked`: **el tipo que ya tiene el bloque sale marcado**.
 * O sea que no es un menú de «insertar», es un menú de «esto qué es». Aprietas
 * cita sobre un párrafo y el párrafo pasa a ser cita; vuelves a abrir y ves que
 * ahora la marcada es cita. Es la misma idea que alternar por cobertura en los
 * estilos, aplicada al bloque entero, y es lo que hace que no haga falta
 * acordarse de qué había antes.
 */
enum class Familia {
    TEXTO,
    LISTAS,
    TABLA,
    FORMULA,
    INSERTAR,
    ADJUNTAR
}

/** Una entrada de menú: cambia el bloque a [tipo], o abre un submenú. */
data class Entrada(
    val tipo: TipoDeBloque?,
    val submenu: List<Entrada> = emptyList(),
    val nombre: String = tipo?.let { Bloques.de(it).nombre } ?: ""
) {
    val abre: Boolean get() = submenu.isNotEmpty()
}

object Menus {

    /** Los seis botones de la barra, en orden. */
    val familias = listOf(
        Familia.TEXTO,
        Familia.LISTAS,
        Familia.TABLA,
        Familia.FORMULA,
        Familia.INSERTAR,
        Familia.ADJUNTAR
    )

    /**
     * Lo que hay dentro de cada botón.
     *
     * El de texto es el suyo tal cual, con los seis encabezados en un submenú.
     * El de listas también, menos sangrar y quitar sangría, que aquí no existen
     * porque no hay listas anidadas.
     */
    fun de(familia: Familia): List<Entrada> = when (familia) {
        Familia.TEXTO -> listOf(
            Entrada(
                tipo = null,
                nombre = "Encabezado",
                submenu = listOf(
                    TipoDeBloque.TITULO_1, TipoDeBloque.TITULO_2, TipoDeBloque.TITULO_3,
                    TipoDeBloque.TITULO_4, TipoDeBloque.TITULO_5, TipoDeBloque.TITULO_6
                ).map { Entrada(it) }
            ),
            Entrada(null, nombre = "Texto"),
            Entrada(TipoDeBloque.CITA),
            Entrada(TipoDeBloque.DESTACADO),
            Entrada(TipoDeBloque.CODIGO),
            Entrada(TipoDeBloque.PIE)
        )

        Familia.LISTAS -> listOf(
            Entrada(null, nombre = "Ninguna"),
            Entrada(TipoDeBloque.LISTA),
            Entrada(TipoDeBloque.NUMERADA),
            Entrada(TipoDeBloque.TAREAS),
            Entrada(TipoDeBloque.PLEGABLE)
        )

        Familia.TABLA -> listOf(Entrada(TipoDeBloque.TABLA))
        Familia.FORMULA -> listOf(Entrada(TipoDeBloque.FORMULA))

        Familia.INSERTAR -> listOf(
            Entrada(TipoDeBloque.SEPARADOR),
            Entrada(TipoDeBloque.CENTRAR),
            Entrada(TipoDeBloque.DERECHA)
        )

        Familia.ADJUNTAR -> listOf(
            Entrada(TipoDeBloque.IMAGEN),
            Entrada(TipoDeBloque.VIDEO),
            Entrada(TipoDeBloque.AUDIO),
            Entrada(TipoDeBloque.ARCHIVO)
        )
    }

    val nombres: Map<Familia, String> = mapOf(
        Familia.TEXTO to "Texto",
        Familia.LISTAS to "Listas",
        Familia.TABLA to "Tabla",
        Familia.FORMULA to "Fórmula",
        Familia.INSERTAR to "Insertar",
        Familia.ADJUNTAR to "Adjuntar"
    )

    /** Todos los tipos que se alcanzan desde algún botón, sin repetir. */
    val alcanzables: Set<TipoDeBloque> = familias
        .flatMap { de(it) }
        .flatMap { listOf(it) + it.submenu }
        .mapNotNull { it.tipo }
        .toSet()

    /**
     * Qué es el bloque que hay en [texto], para marcarlo en el menú.
     *
     * Se pregunta al parser en vez de mirar el principio de la línea a mano: es
     * el que decide de verdad qué es cada cosa, y con dos opiniones acabaría
     * saliendo marcada una y viéndose otra.
     */
    fun tipoDe(texto: String): TipoDeBloque? {
        val bloque = Markdown.parse(texto).firstOrNull() ?: return null
        return when (bloque) {
            is MarkdownBlock.Heading -> when (bloque.level) {
                1 -> TipoDeBloque.TITULO_1
                2 -> TipoDeBloque.TITULO_2
                3 -> TipoDeBloque.TITULO_3
                4 -> TipoDeBloque.TITULO_4
                5 -> TipoDeBloque.TITULO_5
                else -> TipoDeBloque.TITULO_6
            }
            is MarkdownBlock.Quote -> TipoDeBloque.CITA
            is MarkdownBlock.Code -> TipoDeBloque.CODIGO
            is MarkdownBlock.Bullet -> TipoDeBloque.LISTA
            is MarkdownBlock.Numbered -> TipoDeBloque.NUMERADA
            is MarkdownBlock.Tarea -> TipoDeBloque.TAREAS
            is MarkdownBlock.Tabla -> TipoDeBloque.TABLA
            is MarkdownBlock.Formula -> TipoDeBloque.FORMULA
            MarkdownBlock.Rule -> TipoDeBloque.SEPARADOR
            is MarkdownBlock.Medio -> when (bloque.clase) {
                ClaseDeMedio.IMAGEN -> TipoDeBloque.IMAGEN
                ClaseDeMedio.VIDEO -> TipoDeBloque.VIDEO
                ClaseDeMedio.AUDIO -> TipoDeBloque.AUDIO
                ClaseDeMedio.ARCHIVO -> TipoDeBloque.ARCHIVO
            }
            is MarkdownBlock.Caja -> when (bloque.tipo) {
                TipoDeCaja.PLEGABLE -> TipoDeBloque.PLEGABLE
                TipoDeCaja.PIE -> TipoDeBloque.PIE
                TipoDeCaja.DESTACADO -> TipoDeBloque.DESTACADO
                TipoDeCaja.CENTRO -> TipoDeBloque.CENTRAR
                TipoDeCaja.DERECHA -> TipoDeBloque.DERECHA
            }
            // Un párrafo es «ninguno de los anteriores», y eso es justo lo que
            // hay que decir para que en el menú salga marcado «Texto».
            is MarkdownBlock.Paragraph -> null
        }
    }

    /**
     * Cambia de tipo el bloque que hay en [texto], conservando lo escrito.
     *
     * Es su `turnInto`: el contenido no se toca, solo cambia el envoltorio. Con
     * [tipo] en null se queda en párrafo, que es su «Texto» y su «Ninguna».
     */
    fun convertir(texto: String, tipo: TipoDeBloque?): String {
        // **Una tabla no se convierte en otra cosa.** Su contenido es una
        // rejilla, no una frase: al tratarla como texto y envolverla en una
        // almohadilla, lo que salía era el HTML de la tabla escrito a la vista
        // como si fuera un título. Antes que estropear lo que hay, no hacer
        // nada. Para cambiar una tabla está su propio menú.
        if (Tablas.esTabla(texto.trim()) && tipo != TipoDeBloque.TABLA) return texto

        val cuerpo = contenidoDe(texto)
        if (tipo == null) return cuerpo
        return Bloques.envuelve(tipo, cuerpo).trimEnd('\n')
    }

    /** El texto de dentro, sin la marca que lo envolvía. */
    private fun contenidoDe(texto: String): String {
        val limpio = texto.trim('\n')
        // Las cajas y las vallas: se quita la primera línea y la última.
        val lineas = limpio.split('\n')
        if (lineas.size >= 2) {
            val abre = lineas.first().trim()
            val cierra = lineas.last().trim()
            if ((abre.startsWith(":::") && cierra == ":::") ||
                (abre.startsWith("```") && cierra.startsWith("```")) ||
                (abre == "$$" && cierra == "$$")
            ) {
                return lineas.subList(1, lineas.size - 1).joinToString("\n")
            }
        }
        // Solo la marca del principio de cada línea. Quitar TODO el formato
        // aquí sería el fallo de convertir un título con negrita en un párrafo
        // sin negrita: cambiar el envoltorio no puede tocar lo de dentro.
        return lineas.joinToString("\n") { MarkdownEdit.sinPrefijoDeLinea(it) }
    }
}
