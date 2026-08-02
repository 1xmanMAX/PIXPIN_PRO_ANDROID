package com.forge.pixpin.clipboard

/**
 * Tablas copiadas de una hoja de cálculo.
 *
 * Excel, Sheets y Numbers dejan en el portapapeles el mismo texto plano:
 * columnas separadas por TABULADOR y filas por salto de línea. También dejan una
 * versión HTML con colores y celdas combinadas, pero interpretarla es otro
 * proyecto; con el texto se tiene la tabla legible, alineada y editable, que es
 * lo que sirve dentro de un pin.
 */
object TableData {

    /** Mínimo para no confundir dos líneas cualesquiera con una tabla. */
    private const val MIN_ROWS = 2

    /**
     * Separadores que se prueban, en orden de fiabilidad.
     *
     * No basta con el tabulador: al copiar una tabla de una web, de un PDF o de
     * ciertas apps, el texto llega con barras verticales —el formato de tabla de
     * Markdown— o con columnas alineadas a base de espacios.
     */
    private val SEPARATORS = listOf(
        Regex("\t"),
        Regex("\\s*\\|\\s*"),
        Regex(" {2,}")
    )

    fun looksLikeTable(text: String?): Boolean = grid(text) != null

    /**
     * Filas y columnas ya separadas, todas con el mismo ancho: las filas cortas
     * se rellenan con celdas vacías para que la rejilla no se descuadre.
     */
    fun parse(text: String?): List<List<String>> {
        val rows = grid(text) ?: return emptyList()
        val width = rows.maxOf { it.size }
        return rows.map { row -> List(width) { i -> row.getOrElse(i) { "" } } }
    }

    /**
     * La mejor rejilla que sale del texto, o null si no hay ninguna.
     *
     * Se prueba cada separador y se exige que la MAYORÍA de las filas coincidan
     * en número de columnas: un texto corriente puede llevar algún tabulador o
     * alguna barra suelta —código indentado, una frase con guiones—, pero no una
     * rejilla regular. Gana el separador que dé más columnas coincidentes, que
     * es el que de verdad estructuraba el texto.
     */
    private fun grid(text: String?): List<List<String>>? {
        val lines = text.orEmpty()
            .replace("\r\n", "\n")
            .split('\n')
            .filter { it.isNotBlank() }
        if (lines.size < MIN_ROWS) return null

        var best: List<List<String>>? = null
        var bestScore = 0

        for (sep in SEPARATORS) {
            val rows = lines.map { line ->
                // Las tablas de Markdown vienen con barra al principio y al
                // final: separar sin quitarlas deja una celda vacía a cada lado.
                line.trim().trim('|').split(sep).map { it.trim() }
            }
            // Fuera la línea de guiones que separa cabecera y cuerpo en Markdown.
            val useful = rows.filterNot { row ->
                row.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }
            }
            if (useful.size < MIN_ROWS) continue

            val widths = useful.map { it.size }
            val common = widths.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
            if (common.key < 2) continue
            // Al menos dos filas con el MISMO número de columnas. Sin esto, un
            // texto de dos líneas donde solo una lleva una barra suelta ya se
            // daba por tabla: una fila coincidente consigo misma es mayoría.
            if (common.value < MIN_ROWS) continue
            // Y además la mayoría del total, para que no cuele una rejilla de
            // dos filas escondida en un texto largo.
            if (common.value * 2 < useful.size) continue

            val score = common.value * common.key
            if (score > bestScore) {
                bestScore = score
                best = useful
            }
        }
        return best
    }
}
