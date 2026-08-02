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

    /** Mínimo para no confundir un texto con tabulaciones sueltas con una tabla. */
    private const val MIN_ROWS = 2

    /**
     * ¿Esto es una tabla?
     *
     * Se exige que la MAYORÍA de las filas tengan el mismo número de columnas.
     * Un texto cualquiera puede llevar algún tabulador suelto —código indentado,
     * por ejemplo—, pero no una rejilla regular.
     */
    fun looksLikeTable(text: String?): Boolean {
        val rows = rowsOf(text)
        if (rows.size < MIN_ROWS) return false
        val widths = rows.map { it.size }
        if (widths.any { it < 2 }) return false
        val common = widths.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return false
        return common.value * 2 >= rows.size
    }

    /**
     * Filas y columnas ya separadas, todas con el mismo ancho: las filas cortas
     * se rellenan con celdas vacías para que la rejilla no se descuadre.
     */
    fun parse(text: String?): List<List<String>> {
        val rows = rowsOf(text)
        if (rows.isEmpty()) return emptyList()
        val width = rows.maxOf { it.size }
        return rows.map { row -> List(width) { i -> row.getOrElse(i) { "" } } }
    }

    private fun rowsOf(text: String?): List<List<String>> =
        text.orEmpty()
            .replace("\r\n", "\n")
            .split('\n')
            .filter { it.isNotBlank() }
            .map { line -> line.split('\t').map { it.trim() } }
}
