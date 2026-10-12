package com.forge.pixpin.excel

/**
 * Lo del visor de hojas que no pinta ni toca Android (se prueba en la JVM): nombres de columna,
 * la referencia que enseña la barra de arriba y adónde lleva un enlace (`logica.rs` del PC).
 */
object ExcelTexto {
    /** 0 → A, 25 → Z, 26 → AA… */
    fun nombreColumna(col: Int): String {
        var c = col + 1
        val sb = StringBuilder()
        while (c > 0) {
            val r = (c - 1) % 26
            sb.append('A' + r)
            c = (c - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** «B2», o «A1:C3» si se eligió más de una (una fila o columna entera: «3:3», «B:B»). */
    fun referencia(r: Pxhj.Rango, unaSola: Boolean): String {
        val filasEnteras = r.c0 == 0 && r.c1 >= Pxhj.MAX_COLUMNAS - 1
        val columnasEnteras = r.f0 == 0 && r.f1 >= Pxhj.MAX_FILAS - 1
        return when {
            filasEnteras && columnasEnteras -> "Todo"
            filasEnteras -> "${r.f0 + 1}:${r.f1 + 1}"
            columnasEnteras -> "${nombreColumna(r.c0)}:${nombreColumna(r.c1)}"
            unaSola || (r.f0 == r.f1 && r.c0 == r.c1) -> "${nombreColumna(r.c0)}${r.f0 + 1}"
            else -> "${nombreColumna(r.c0)}${r.f0 + 1}:${nombreColumna(r.c1)}${r.f1 + 1}"
        }
    }

    /** Lo que abre un hipervínculo. */
    sealed interface Enlace {
        /** Una página web o un correo: con la aplicación del teléfono. */
        data class Web(val url: String) : Enlace
        /** Un sitio del libro: una hoja (por nombre) y una celda (fila, columna). */
        data class Sitio(val hoja: String?, val celda: Pair<Int, Int>?) : Enlace
    }

    /** El destino de un hipervínculo, si es de los que se abren: solo web y correo (una ruta a un archivo podría ser cualquier cosa). */
    fun enlaceDe(destino: String, externo: Boolean): Enlace? {
        val d = destino.trim()
        if (d.isEmpty()) return null
        if (externo) {
            val l = d.lowercase()
            return if (l.startsWith("http://") || l.startsWith("https://") || l.startsWith("mailto:")) Enlace.Web(d) else null
        }
        val sin = d.trimStart('#')
        val corte = sin.lastIndexOf('!')
        val (hoja, celda) = if (corte >= 0) {
            var h = sin.substring(0, corte).trim()
            if (h.length >= 2 && h.startsWith('\'') && h.endsWith('\'')) h = h.substring(1, h.length - 1).replace("''", "'")
            h to sin.substring(corte + 1)
        } else null to sin
        val c = celdaDe(celda.substringBefore(':'))
        return if (hoja != null || c != null) Enlace.Sitio(hoja, c) else null
    }

    /** «$B$12» → (11, 1). */
    fun celdaDe(s: String): Pair<Int, Int>? {
        val t = s.trim().replace("$", "").uppercase()
        val letras = t.takeWhile { it in 'A'..'Z' }
        val numero = t.drop(letras.length)
        if (letras.isEmpty() || letras.length > 3 || numero.isEmpty() || !numero.all { it.isDigit() } || numero.length > 7) return null
        var col = 0
        for (ch in letras) col = col * 26 + (ch - 'A' + 1)
        val fila = numero.toInt()
        if (col > Pxhj.MAX_COLUMNAS || fila < 1 || fila > Pxhj.MAX_FILAS) return null
        return (fila - 1) to (col - 1)
    }

    /** Lo más que se copia de una vez: un millón de celdas ya es un texto de decenas de MB. */
    const val MAXIMO_A_COPIAR = 2_000_000L
}
