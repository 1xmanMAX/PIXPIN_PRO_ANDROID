package com.forge.pixpin.motor

/**
 * **Lo que entra y sale de una tabla por el portapapeles**: pegar desde Excel, Google Sheets
 * o LibreOffice, y copiar de vuelta a cualquiera de ellos.
 *
 * ## Lo que traen al copiar
 *
 * Las tres dejan **dos versiones** de lo copiado: texto separado por tabuladores —los
 * valores tal como se ven— y una tabla HTML. La tabla es la que interesa, porque puede traer
 * más:
 *
 * - **Google Sheets** pone en cada celda `data-sheets-formula` con la fórmula **en notación
 *   R1C1 relativa** (`=SUM(R[-3]C[0]:R[-1]C[0])`): «tres filas más arriba». No dice dónde
 *   estaba la celda, y no le hace falta: se traduce a `A1` en el sitio donde se pega ([r1c1])
 *   y la fórmula funciona igual que en la hoja de origen.
 * - **Excel** marca los números (`x:num`) y, en algunas versiones, deja la fórmula en
 *   `x:fmla`, ya en `A1` y referida a donde estaba. Esa se pega tal cual.
 * - La **negrita** viaja en el estilo de la celda o como `<b>`, y se conserva.
 *
 * Si no hay tabla —se copió una sola palabra de otra aplicación—, se usa el texto.
 *
 * ## Lo que se deja al copiar
 *
 * Texto con tabuladores con **los valores** (lo que se pega en un chat es lo que se ve, no
 * `=SUMA(B2:B9)`) y una tabla HTML que además lleva cada fórmula en R1C1 en
 * `data-sheets-formula`: así, pegar en Sheets o en otra tabla de PixPin —también en la página
 * web exportada— conserva las fórmulas.
 */
object PortapapelesDeTabla {

    /** Una celda pegada: lo que se escribe en ella y cómo hay que entenderlo. */
    data class Pegada(val texto: String, val r1c1: Boolean = false, val negrita: Boolean = false)

    /** Lo que hay en el portapapeles, como filas de celdas; null si no hay nada. */
    fun leer(html: String?, texto: String?): List<List<Pegada>>? {
        if (!html.isNullOrBlank()) deHtml(html)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!texto.isNullOrEmpty()) return deTsv(texto).map { fila -> fila.map { Pegada(it) } }
        return null
    }

    /**
     * Texto separado por tabuladores, con las comillas de Excel: una celda con un salto de
     * línea o un tabulador dentro va entre comillas, y sus comillas van dobladas.
     */
    fun deTsv(texto: String): List<List<String>> = deSeparado(texto, '\t')

    /** Lo mismo con otro separador: el `;` o la `,` de un CSV. Ver [ImportarHojas.deCsv]. */
    fun deSeparado(texto: String, separador: Char): List<List<String>> {
        val filas = ArrayList<List<String>>()
        var fila = ArrayList<String>()
        val celda = StringBuilder()
        var i = 0
        val n = texto.length
        var alEmpezar = true
        while (i < n) {
            val c = texto[i]
            if (alEmpezar && c == '"') {
                // Solo es una celda entre comillas si la comilla que cierra va seguida de un
                // separador o del final. Si no, es texto que empieza por comillas.
                var j = i + 1
                val sb = StringBuilder()
                var cerrada = -1
                while (j < n) {
                    if (texto[j] == '"') {
                        if (j + 1 < n && texto[j + 1] == '"') { sb.append('"'); j += 2; continue }
                        cerrada = j; break
                    }
                    sb.append(texto[j]); j++
                }
                val tras = if (cerrada >= 0 && cerrada + 1 < n) texto[cerrada + 1] else if (cerrada >= 0) '\n' else 'x'
                if (cerrada >= 0 && (tras == separador || tras == '\n' || tras == '\r')) {
                    celda.append(sb)
                    i = cerrada + 1
                    alEmpezar = false
                    continue
                }
            }
            alEmpezar = false
            when {
                c == separador -> { fila += celda.toString(); celda.setLength(0); alEmpezar = true }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && i + 1 < n && texto[i + 1] == '\n') i++
                    fila += celda.toString(); celda.setLength(0)
                    filas += fila; fila = ArrayList(); alEmpezar = true
                }
                else -> celda.append(c)
            }
            i++
        }
        if (celda.isNotEmpty() || fila.isNotEmpty()) { fila += celda.toString(); filas += fila }
        return filas
    }

    private val COMENTARIO = Regex("<!--[\\s\\S]*?-->")
    private val ESTILO = Regex("<(style|script|head)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val TABLA = Regex("<table\\b[\\s\\S]*?</table>", RegexOption.IGNORE_CASE)
    private val FILA = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val CELDA = Regex("<t([dh])\\b([^>]*)>([\\s\\S]*?)</t[dh]>", RegexOption.IGNORE_CASE)
    private val ETIQUETA = Regex("<[^>]+>")
    private val SALTO = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BLANCOS = Regex("[ \\t\\r\\n]+")

    /** Las filas de la primera tabla del HTML, o null si no hay tabla. */
    fun deHtml(html: String): List<List<Pegada>>? {
        val limpio = ESTILO.replace(COMENTARIO.replace(html, ""), "")
        val tabla = TABLA.find(limpio)?.value ?: return null
        val filas = ArrayList<List<Pegada>>()
        for (tr in FILA.findAll(tabla)) {
            val fila = ArrayList<Pegada>()
            for (td in CELDA.findAll(tr.groupValues[1])) {
                val atributos = td.groupValues[2]
                val dentro = td.groupValues[3]
                val formulaSheets = atributo(atributos, "data-sheets-formula")
                val formulaExcel = atributo(atributos, "x:fmla")
                val estilo = atributo(atributos, "style").orEmpty().lowercase()
                val negrita = Regex("font-weight:\\s*(bold|[6-9]00)").containsMatchIn(estilo) ||
                    Regex("<(b|strong)\\b", RegexOption.IGNORE_CASE).containsMatchIn(dentro) ||
                    Regex("font-weight:\\s*(bold|[6-9]00)", RegexOption.IGNORE_CASE).containsMatchIn(dentro)
                val texto = textoDe(dentro)
                fila += when {
                    formulaSheets != null && formulaSheets.startsWith("=") -> Pegada(formulaSheets, r1c1 = true, negrita = negrita)
                    formulaExcel != null && formulaExcel.startsWith("=") -> Pegada(formulaExcel, negrita = negrita)
                    // Un texto que empieza por `=` en una hoja es texto: que no se vuelva fórmula.
                    texto.startsWith("=") -> Pegada("'$texto", negrita = negrita)
                    else -> Pegada(exacto(atributos, texto) ?: texto, negrita = negrita)
                }
                val juntas = atributo(atributos, "colspan")?.toIntOrNull() ?: 1
                repeat((juntas - 1).coerceIn(0, 50)) { fila += Pegada("") }
            }
            filas += fila
        }
        return filas
    }

    /**
     * **El número de verdad, no el que se ve.** Sheets pone el valor exacto en
     * `data-sheets-value` (`{"1":3,"3":1234.5678}`, tipo 3 = número) y Excel en `x:num`, y
     * lo que se ve puede venir redondeado (`1,234.57`). Solo se usa cuando lo que se ve **es**
     * un número: una fecha o un porcentaje se quedan como se ven, que si no pasarían a ser
     * `46275` y `0.15`.
     */
    private fun exacto(atributos: String, texto: String): String? {
        if (texto.contains('%') || CalculoFormato.numero(texto) == null) return null
        atributo(atributos, "x:num")?.takeIf { CalculoFormato.numero(it) != null }?.let { return it }
        val valor = atributo(atributos, "data-sheets-value") ?: return null
        if (!Regex("\"1\"\\s*:\\s*3\\b").containsMatchIn(valor)) return null
        return Regex("\"3\"\\s*:\\s*(-?[0-9.]+(?:[eE][+-]?[0-9]+)?)").find(valor)?.groupValues?.get(1)
    }

    private fun atributo(atributos: String, nombre: String): String? {
        val m = Regex("(?:^|\\s)" + Regex.escape(nombre) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
            .find(atributos) ?: return if (Regex("(?:^|\\s)" + Regex.escape(nombre) + "(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(atributos)) "" else null
        val crudo = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
        return entidades(crudo)
    }

    private fun textoDe(html: String): String {
        val conSaltos = SALTO.replace(html, "\u0000")
        val sinEtiquetas = ETIQUETA.replace(conSaltos, "")
        val junto = BLANCOS.replace(sinEtiquetas, " ")
        return entidades(junto).split('\u0000').joinToString("\n") { it.trim() }.trim()
    }

    private val ENTIDAD = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    fun entidades(s: String): String = ENTIDAD.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e.lowercase()) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> m.value
            }
        }
    }

    private val R1C1 = Regex("R(\\[-?[0-9]+\\]|[0-9]+)?C(\\[-?[0-9]+\\]|[0-9]+)?")

    /**
     * Una fórmula en R1C1 (`=SUM(R[-3]C[0]:R[-1]C)`) escrita en `A1` para la celda
     * ([fila], [col]). Lo que quede fuera de la hoja, `#¡REF!`.
     */
    fun r1c1(formula: String, fila: Int, col: Int): String {
        val sb = StringBuilder()
        var i = 0
        val n = formula.length
        while (i < n) {
            val c = formula[i]
            if (c == '"') {
                val j = formula.indexOf('"', i + 1).let { if (it < 0) n - 1 else it }
                // Comillas dobladas: se sigue hasta la que cierra de verdad.
                var fin = j
                while (fin + 1 < n && formula[fin + 1] == '"') {
                    val otra = formula.indexOf('"', fin + 2)
                    fin = if (otra < 0) n - 1 else otra
                }
                sb.append(formula, i, fin + 1)
                i = fin + 1
                continue
            }
            val antes = if (i > 0) formula[i - 1] else ' '
            if ((c == 'R' || c == 'r') && !antes.isLetterOrDigit() && antes != '.' && antes != '_') {
                val m = R1C1.matchAt(formula.uppercase(), i)
                if (m != null) {
                    val despues = if (m.range.last + 1 < n) formula[m.range.last + 1] else ' '
                    if (!despues.isLetterOrDigit() && despues != '(' && despues != '.' && despues != '_') {
                        val f = parte(m.groups[1]?.value, fila)
                        val k = parte(m.groups[2]?.value, col)
                        if (f == null || k == null || f.first !in 0 until Celdas.MAX_FILAS || k.first !in 0 until Celdas.MAX_COLS) {
                            sb.append(CalculoLexico.ERROR_REF)
                        } else {
                            sb.append(if (k.second) "$" else "").append(Celdas.letras(k.first))
                                .append(if (f.second) "$" else "").append(f.first + 1)
                        }
                        i = m.range.last + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** `[−3]` → relativo; `5` → absoluto (fila 5); nada → la misma. Con si es fijo. */
    private fun parte(t: String?, desde: Int): Pair<Int, Boolean>? = when {
        t.isNullOrEmpty() -> desde to false
        t.startsWith("[") -> t.substring(1, t.length - 1).toIntOrNull()?.let { desde + it to false }
        else -> t.toIntOrNull()?.let { it - 1 to true }
    }

    /** La fórmula de la celda ([fila], [col]) en R1C1, para dejarla en `data-sheets-formula`. */
    fun aR1c1(formula: String, fila: Int, col: Int): String {
        if (!formula.startsWith("=")) return formula
        val cuerpo = formula.substring(1)
        val sb = StringBuilder("=")
        var ultimo = 0
        for (p in CalculoLexico.piezas(cuerpo)) {
            val nuevo = when (p.tipo) {
                PiezaDeFormula.REF -> (if (p.filaFija) "R${p.fila + 1}" else "R[${p.fila - fila}]") +
                    (if (p.colFija) "C${p.col + 1}" else "C[${p.col - col}]")
                PiezaDeFormula.COLUMNAS -> (if (p.colFija) "C${p.col + 1}" else "C[${p.col - col}]") + ":" +
                    (if (p.col2Fija) "C${p.col2 + 1}" else "C[${p.col2 - col}]")
                else -> null
            } ?: continue
            sb.append(cuerpo, ultimo, p.desde).append(nuevo)
            ultimo = p.hasta
        }
        sb.append(cuerpo, ultimo, cuerpo.length)
        return sb.toString()
    }

    /** Los valores como texto con tabuladores, con las comillas donde hagan falta. */
    fun aTsv(filas: List<List<String>>): String = filas.joinToString("\n") { fila ->
        fila.joinToString("\t") { v ->
            if (v.any { it == '\t' || it == '\n' || it == '\r' } || v.startsWith("\""))
                "\"" + v.replace("\"", "\"\"") + "\""
            else v
        }
    }

    private fun escapar(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * La tabla HTML de lo copiado. [crudos] es lo escrito en cada celda —fórmula o valor— y
     * [valores], lo que se ve; ([fila], [col]) es la esquina, para pasar las fórmulas a R1C1.
     */
    fun aHtml(
        valores: List<List<String>>,
        crudos: List<List<String>>,
        fila: Int,
        col: Int,
        negrita: (Int, Int) -> Boolean = { _, _ -> false }
    ): String = buildString {
        append("<meta charset=\"utf-8\"><table>")
        for ((i, filaDeValores) in valores.withIndex()) {
            append("<tr>")
            for ((j, v) in filaDeValores.withIndex()) {
                append("<td")
                val raw = crudos.getOrNull(i)?.getOrNull(j).orEmpty()
                if (Calculadora.esFormula(raw)) {
                    append(" data-sheets-formula=\"").append(escapar(aR1c1(raw, fila + i, col + j))).append('"')
                }
                if (negrita(fila + i, col + j)) append(" style=\"font-weight:bold\"")
                append('>')
                append(escapar(v).replace("\n", "<br>"))
                append("</td>")
            }
            append("</tr>")
        }
        append("</table>")
    }
}
