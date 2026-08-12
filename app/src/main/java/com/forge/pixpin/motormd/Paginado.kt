package com.forge.pixpin.motormd

/**
 * Parte una nota en páginas, como las de un PDF.
 *
 * ## Por qué en páginas
 *
 * Una nota larga en un pin es un rollo que se desplaza sin fin: no se sabe por
 * dónde vas ni cuánto queda, y no hay forma de saltar a un sitio. Un PDF se
 * maneja bien justamente porque tiene páginas, y una nota que va a acabar siendo
 * un PDF —dentro de un proyecto— se maneja mejor si las tiene desde antes.
 *
 * ## Cómo se decide dónde cortar
 *
 * Se cuenta en **renglones**, no en puntos de pantalla. Medir de verdad exigiría
 * pintar la nota, y aquí no hay pantalla: esto es lógica pura para que se pueda
 * comprobar en la JVM y para que el corte sea el mismo se mire donde se mire.
 * Cada bloque declara cuántos renglones ocupa más o menos y se van sumando.
 *
 * Un bloque **no se parte por la mitad**: si no cabe, empieza la página
 * siguiente. Salvo que él solo sea más grande que una página entera, que
 * entonces se queda solo en la suya — partir un párrafo por el medio dejaría una
 * frase cortada, y partir una tabla la dejaría sin cabecera.
 */
object Paginado {

    /** Cuántos renglones caben en una página. Es una hoja A4 con margen. */
    const val RENGLONES_POR_PAGINA = 46

    /** Cuántos caracteres caben en un renglón, para estimar lo que dobla. */
    private const val ANCHO_EN_LETRAS = 78

    /**
     * Las páginas de la nota, cada una con sus bloques.
     *
     * Nunca devuelve una lista vacía si hay algo que enseñar: una nota siempre
     * tiene al menos una página, aunque esté en blanco, porque si no habría que
     * distinguir «sin páginas» de «una página vacía» en todos los sitios que la
     * miran.
     */
    fun paginas(
        bloques: List<MarkdownBlock>,
        porPagina: Int = RENGLONES_POR_PAGINA
    ): List<List<MarkdownBlock>> {
        if (bloques.isEmpty()) return listOf(emptyList())
        val tope = porPagina.coerceAtLeast(4)

        val paginas = mutableListOf<List<MarkdownBlock>>()
        var actual = mutableListOf<MarkdownBlock>()
        var altura = 0

        bloques.forEach { bloque ->
            val suyo = renglones(bloque)

            // **Una tabla grande empieza página.** Aunque quepan sus últimas
            // dos filas al pie de la anterior, partirla ahí deja la cabecera en
            // una hoja y los datos en otra, que es justo lo que hace ilegible
            // una tabla. Con media página de alto ya vale la pena bajarla.
            val mandaSola = bloque is MarkdownBlock.Tabla && suyo > tope / 2
            if (mandaSola && actual.isNotEmpty()) {
                paginas += actual
                actual = mutableListOf()
                altura = 0
            }
            // Cabe justo o no cabe: si ya hay algo en la página, se cierra y se
            // empieza otra. Si está vacía, este bloque se queda solo en ella
            // aunque se pase, porque no hay dónde ponerlo mejor.
            if (altura + suyo > tope && actual.isNotEmpty()) {
                paginas += actual
                actual = mutableListOf()
                altura = 0
            }
            actual += bloque
            altura += suyo
        }
        if (actual.isNotEmpty() || paginas.isEmpty()) paginas += actual
        return paginas
    }

    /**
     * Una página **con su sitio en el texto**.
     *
     * [paginas] devuelve bloques ya interpretados, que sirven para pintar y para
     * escribir el PDF. Pero para llevar al editor a la página que se estaba
     * leyendo hace falta lo otro: por qué letra empieza. Un bloque no lo sabe
     * —es el resultado de interpretar, no el original—, así que aquí se pagina
     * sobre los **trozos** del documento, que sí llevan sus dos extremos.
     */
    data class PaginaDeNota(
        val desde: Int,
        val hasta: Int,
        val bloques: List<MarkdownBlock>
    )

    /**
     * Las páginas de una nota sin interpretar, cada una sabiendo de dónde sale.
     *
     * El corte es el mismo que el de [paginas] y por el mismo motivo: se cuentan
     * renglones y un bloque no se parte. La diferencia es la unidad, que aquí es
     * el trozo del documento —lo que el editor considera un bloque editable— en
     * vez del bloque ya interpretado.
     */
    fun deTexto(texto: String?, porPagina: Int = RENGLONES_POR_PAGINA): List<PaginaDeNota> {
        val fuente = texto.orEmpty()
        val trozos = trozosDe(fuente)
        if (trozos.isEmpty()) return listOf(PaginaDeNota(0, fuente.length, emptyList()))
        val tope = porPagina.coerceAtLeast(4)

        val paginas = mutableListOf<PaginaDeNota>()
        var desde = trozos.first().desde
        var hasta = desde
        var bloques = mutableListOf<MarkdownBlock>()
        var altura = 0

        fun cerrar() {
            paginas += PaginaDeNota(desde, hasta, bloques)
            bloques = mutableListOf()
            altura = 0
        }

        trozos.forEach { trozo ->
            val suyos = Markdown.parse(trozo.de(fuente))
            val suyo = suyos.sumOf { renglones(it) }

            // Mismo criterio que en [paginas]: una tabla grande baja entera.
            val mandaSola = suyos.any { it is MarkdownBlock.Tabla } && suyo > tope / 2
            if ((mandaSola || altura + suyo > tope) && bloques.isNotEmpty()) {
                cerrar()
                desde = trozo.desde
            }
            bloques += suyos
            altura += suyo
            hasta = trozo.hasta
        }
        if (bloques.isNotEmpty() || paginas.isEmpty()) cerrar()
        return paginas
    }

    /** Cuántas páginas tiene una nota. */
    fun cuantasPaginas(texto: String?): Int = deTexto(texto).size

    /**
     * Cuántos renglones ocupa un bloque, más o menos.
     *
     * Son estimaciones y lo son a propósito: lo que importa es que el corte sea
     * **estable y razonable**, no exacto al píxel. Un título ocupa más que un
     * renglón porque va grande y con aire; una tabla, una fila por fila más su
     * marco; una imagen, un buen trozo de página.
     */
    fun renglones(bloque: MarkdownBlock): Int = when (bloque) {
        is MarkdownBlock.Heading -> when (bloque.level) {
            1 -> 4
            2 -> 3
            else -> 2
        } + dobla(bloque.content.text)

        is MarkdownBlock.Paragraph -> 1 + dobla(bloque.content.text)
        is MarkdownBlock.Bullet -> 1 + dobla(bloque.content.text)
        is MarkdownBlock.Numbered -> 1 + dobla(bloque.content.text)
        is MarkdownBlock.Tarea -> 1 + dobla(bloque.content.text)
        is MarkdownBlock.Quote -> 1 + dobla(bloque.content.text)

        // El código no dobla: cada línea es una línea, y lo que se sale se
        // desplaza. Más uno por cada valla.
        is MarkdownBlock.Code -> bloque.text.count { it == '\n' } + 3

        MarkdownBlock.Rule -> 1

        // La fórmula va compuesta y con aire; una fracción abulta el doble.
        is MarkdownBlock.Formula -> 3 + bloque.latex.count { it == '/' }

        // Una fila por fila, más el marco y el título si lo lleva.
        is MarkdownBlock.Tabla ->
            bloque.filas.size + 2 + if (bloque.titulo.text.isEmpty()) 0 else 2

        // Una imagen se lleva medio folio; lo demás es una tarjeta de dos.
        is MarkdownBlock.Medio ->
            if (bloque.clase == ClaseDeMedio.IMAGEN) RENGLONES_POR_PAGINA / 2 else 3

        // Una caja es lo que lleve dentro, más su adorno. El plegable cuenta
        // solo su título: está cerrado hasta que alguien lo abra.
        is MarkdownBlock.Caja ->
            if (bloque.tipo == TipoDeCaja.PLEGABLE) {
                2
            } else {
                1 + bloque.dentro.sumOf { renglones(it) }
            }
    }

    /** Cuántos renglones de más ocupa un texto al doblar por el ancho. */
    private fun dobla(texto: String): Int = texto.length / ANCHO_EN_LETRAS
}
