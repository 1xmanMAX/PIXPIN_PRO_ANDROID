package com.forge.pixpin.motor

/**
 * **Las direcciones de las celdas**: `A1`, `$B$7`, `AB120`. Filas y columnas cuentan desde
 * cero por dentro y desde uno por fuera, como en cualquier hoja.
 */
object Celdas {
    /** Hasta la columna ZZ: 702 columnas son más de las que caben en cualquier teléfono. */
    const val MAX_COLS = 702
    const val MAX_FILAS = 100_000

    /** `0 → A`, `25 → Z`, `26 → AA`. */
    fun letras(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** `A → 0`, o -1 si no son letras de columna. */
    fun columna(letras: String): Int {
        if (letras.isEmpty() || letras.length > 3) return -1
        var n = 0
        for (c in letras) {
            val u = c.uppercaseChar()
            if (u !in 'A'..'Z') return -1
            n = n * 26 + (u - 'A' + 1)
        }
        return n - 1
    }

    fun nombre(fila: Int, col: Int): String = letras(col) + (fila + 1)

    private val DIRECCION = Regex("^\\$?([A-Za-z]{1,3})\\$?([0-9]{1,6})$")

    /** Fila y columna de `B7` (o `$B$7`), o null. */
    fun leer(nombre: String): IntArray? {
        val m = DIRECCION.matchEntire(nombre.trim()) ?: return null
        val c = columna(m.groupValues[1])
        val f = m.groupValues[2].toInt() - 1
        if (c < 0 || c >= MAX_COLS || f < 0 || f >= MAX_FILAS) return null
        return intArrayOf(f, c)
    }

    /** La clave con la que se guarda una celda en memoria. Ver [Calculadora]. */
    fun clave(fila: Int, col: Int): Int = fila * 1024 + col
    fun filaDe(clave: Int): Int = clave / 1024
    fun colDe(clave: Int): Int = clave % 1024
}

/** Un trozo de fórmula ya leído, con dónde estaba para poder reescribirlo. */
class PiezaDeFormula(
    val tipo: Int,
    val texto: String,
    val desde: Int,
    val hasta: Int,
    val numero: Double = 0.0,
    val fila: Int = 0,
    val col: Int = 0,
    val filaFija: Boolean = false,
    val colFija: Boolean = false,
    /** Solo en las columnas enteras (`A:C`). */
    val col2: Int = 0,
    val col2Fija: Boolean = false
) {
    companion object {
        const val NUM = 1
        const val TXT = 2
        const val REF = 3
        const val COLUMNAS = 4
        const val NOMBRE = 5
        const val OP = 6
        const val ABRE = 7
        const val CIERRA = 8
        const val SEP = 9
        const val DOS_PUNTOS = 10
        const val ERR = 11
        const val MAL = 12
    }
}

/** Un rango citado en una fórmula, y dónde está escrito: [desde] incluido, [hasta] no. */
data class ReferenciaEnFormula(val f1: Int, val c1: Int, val f2: Int, val c2: Int, val desde: Int, val hasta: Int)

/** Una fórmula entendida, lista para calcular. */
sealed interface Nodo {
    data class Num(val n: Double) : Nodo
    data class Txt(val s: String) : Nodo
    data class Log(val b: Boolean) : Nodo
    data class Err(val e: String) : Nodo
    data class Ref(val fila: Int, val col: Int) : Nodo
    /** [columnas] es `A:C`: las filas las pone lo que haya escrito. */
    data class Rango(val f1: Int, val c1: Int, val f2: Int, val c2: Int, val columnas: Boolean = false) : Nodo
    data class Menos(val x: Nodo) : Nodo
    data class PorCiento(val x: Nodo) : Nodo
    data class Op(val op: String, val a: Nodo, val b: Nodo) : Nodo
    data class Funcion(val nombre: String, val args: List<Nodo>) : Nodo
    /** Un argumento dejado en blanco: `SI(A1;;2)`. */
    data object Hueco : Nodo
}

/**
 * **Leer una fórmula**: de texto a piezas y de piezas a árbol. Y las dos reescrituras que
 * hace una hoja con sus fórmulas: moverlas al copiar ([desplazar]) y corregirlas al meter o
 * quitar filas y columnas ([alInsertar]).
 *
 * Se aceptan **las dos maneras de escribir**: `;` y `,` separan argumentos, y los nombres de
 * funciones y errores valen en español y en inglés. Así una fórmula que llega pegada desde
 * Google Sheets (`=SUM(A1:A3)`) funciona igual que una tecleada aquí (`=SUMA(A1:A3)`).
 */
object CalculoLexico {

    const val ERROR_REF = "#¡REF!"

    /** Los errores que se pueden escribir, en los dos idiomas, con su forma de aquí. */
    val ERRORES = linkedMapOf(
        "#¡DIV/0!" to "#¡DIV/0!", "#DIV/0!" to "#¡DIV/0!",
        "#¡VALOR!" to "#¡VALOR!", "#VALUE!" to "#¡VALOR!",
        "#¡REF!" to "#¡REF!", "#REF!" to "#¡REF!",
        "#¿NOMBRE?" to "#¿NOMBRE?", "#NAME?" to "#¿NOMBRE?",
        "#N/D" to "#N/D", "#N/A" to "#N/D",
        "#¡NUM!" to "#¡NUM!", "#NUM!" to "#¡NUM!",
        "#¡NULO!" to "#¡NULO!", "#NULL!" to "#¡NULO!",
        "#¡CIRC!" to "#¡CIRC!"
    )

    private fun esLetra(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c in LETRAS_CON_TILDE

    /** Las de los nombres en español: `AÑO`, `RAÍZ`, `ÍNDICE`, `DÍA`. */
    private const val LETRAS_CON_TILDE = "ÁÉÍÓÚÜÑáéíóúüñ"
    private fun esCifra(c: Char) = c in '0'..'9'

    fun piezas(f: String): List<PiezaDeFormula> {
        val out = ArrayList<PiezaDeFormula>()
        var i = 0
        val n = f.length
        while (i < n) {
            val c = f[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ') { i++; continue }
            val desde = i
            when {
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    var cerrada = false
                    while (i < n) {
                        if (f[i] == '"') {
                            if (i + 1 < n && f[i + 1] == '"') { sb.append('"'); i += 2; continue }
                            i++; cerrada = true; break
                        }
                        sb.append(f[i]); i++
                    }
                    out += PiezaDeFormula(if (cerrada) PiezaDeFormula.TXT else PiezaDeFormula.MAL, sb.toString(), desde, i)
                }
                esCifra(c) || (c == '.' && i + 1 < n && esCifra(f[i + 1])) -> {
                    // ¿Es `1:3` (filas enteras)? No se admite: se lee como número y el `:` falla.
                    while (i < n && (esCifra(f[i]) || f[i] == '.')) i++
                    if (i < n && (f[i] == 'e' || f[i] == 'E')) {
                        var j = i + 1
                        if (j < n && (f[j] == '+' || f[j] == '-')) j++
                        if (j < n && esCifra(f[j])) {
                            i = j
                            while (i < n && esCifra(f[i])) i++
                        }
                    }
                    val t = f.substring(desde, i)
                    val v = t.toDoubleOrNull()
                    out += if (v == null) PiezaDeFormula(PiezaDeFormula.MAL, t, desde, i) else PiezaDeFormula(PiezaDeFormula.NUM, t, desde, i, numero = v)
                }
                c == '#' -> {
                    val resto = f.substring(i)
                    val hallado = ERRORES.keys.firstOrNull { resto.startsWith(it, ignoreCase = true) }
                    if (hallado == null) { i++; out += PiezaDeFormula(PiezaDeFormula.MAL, "#", desde, i) }
                    else { i += hallado.length; out += PiezaDeFormula(PiezaDeFormula.ERR, ERRORES[hallado]!!, desde, i) }
                }
                c == '$' || esLetra(c) -> {
                    out += palabra(f, i).also { i = it.hasta }
                }
                c == '(' -> { i++; out += PiezaDeFormula(PiezaDeFormula.ABRE, "(", desde, i) }
                c == ')' -> { i++; out += PiezaDeFormula(PiezaDeFormula.CIERRA, ")", desde, i) }
                c == ',' || c == ';' -> { i++; out += PiezaDeFormula(PiezaDeFormula.SEP, c.toString(), desde, i) }
                c == ':' -> { i++; out += PiezaDeFormula(PiezaDeFormula.DOS_PUNTOS, ":", desde, i) }
                c == '<' && i + 1 < n && (f[i + 1] == '=' || f[i + 1] == '>') -> {
                    i += 2; out += PiezaDeFormula(PiezaDeFormula.OP, f.substring(desde, i), desde, i)
                }
                c == '>' && i + 1 < n && f[i + 1] == '=' -> { i += 2; out += PiezaDeFormula(PiezaDeFormula.OP, ">=", desde, i) }
                c in "+-*/^&=<>%" -> { i++; out += PiezaDeFormula(PiezaDeFormula.OP, c.toString(), desde, i) }
                else -> { i++; out += PiezaDeFormula(PiezaDeFormula.MAL, c.toString(), desde, i) }
            }
        }
        return out
    }

    /** Una palabra: dirección (`$A$1`), columnas (`A:C`), o nombre de función. */
    private fun palabra(f: String, desde: Int): PiezaDeFormula {
        val n = f.length
        var i = desde
        // Dirección: $? letras $? cifras, y que no siga un `(` ni más letras.
        run {
            var j = i
            val colFija = j < n && f[j] == '$'
            if (colFija) j++
            val l0 = j
            while (j < n && f[j].let { it in 'A'..'Z' || it in 'a'..'z' }) j++
            val letras = f.substring(l0, j)
            if (letras.isNotEmpty() && letras.length <= 3) {
                var k = j
                val filaFija = k < n && f[k] == '$'
                if (filaFija) k++
                val c0 = k
                while (k < n && esCifra(f[k])) k++
                val cifras = f.substring(c0, k)
                val sigue = if (k < n) f[k] else ' '
                if (cifras.isNotEmpty() && !esLetra(sigue) && sigue != '(' && sigue != '.' && sigue != '_') {
                    val col = Celdas.columna(letras)
                    val fila = cifras.toIntOrNull()?.minus(1) ?: -1
                    if (col in 0 until Celdas.MAX_COLS && fila in 0 until Celdas.MAX_FILAS) {
                        return PiezaDeFormula(PiezaDeFormula.REF, f.substring(desde, k), desde, k, fila = fila, col = col,
                            filaFija = filaFija, colFija = colFija)
                    }
                }
                // Columnas enteras: `A:C`, `$A:$A`.
                if (cifras.isEmpty() && !filaFija && k < n && f[k] == ':') {
                    var m = k + 1
                    val col2Fija = m < n && f[m] == '$'
                    if (col2Fija) m++
                    val l2 = m
                    while (m < n && f[m].let { it in 'A'..'Z' || it in 'a'..'z' }) m++
                    val letras2 = f.substring(l2, m)
                    val sigue2 = if (m < n) f[m] else ' '
                    if (letras2.isNotEmpty() && letras2.length <= 3 && !esCifra(sigue2) && !esLetra(sigue2) && sigue2 != '(') {
                        val c1 = Celdas.columna(letras)
                        val c2 = Celdas.columna(letras2)
                        if (c1 in 0 until Celdas.MAX_COLS && c2 in 0 until Celdas.MAX_COLS) {
                            return PiezaDeFormula(PiezaDeFormula.COLUMNAS, f.substring(desde, m), desde, m, col = c1, colFija = colFija,
                                col2 = c2, col2Fija = col2Fija)
                        }
                    }
                }
            }
        }
        if (f[i] == '$') return PiezaDeFormula(PiezaDeFormula.MAL, "$", desde, i + 1)
        while (i < n && (esLetra(f[i]) || esCifra(f[i]) || f[i] == '.')) i++
        return PiezaDeFormula(PiezaDeFormula.NOMBRE, f.substring(desde, i), desde, i)
    }

    /** El árbol de una fórmula sin el `=` de delante. Lo que no se entienda, `#¿NOMBRE?`. */
    fun arbol(formula: String): Nodo {
        val p = piezas(formula)
        if (p.isEmpty()) return Nodo.Err("#¿NOMBRE?")
        return try {
            val lector = Lector(p)
            val r = lector.comparacion()
            if (lector.i != p.size) Nodo.Err("#¿NOMBRE?") else r
        } catch (_: Mal) {
            Nodo.Err("#¿NOMBRE?")
        }
    }

    private class Mal : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Lector(val p: List<PiezaDeFormula>) {
        var i = 0
        fun ver(): PiezaDeFormula? = p.getOrNull(i)
        fun esOp(vararg ops: String): String? {
            val x = ver() ?: return null
            return if (x.tipo == PiezaDeFormula.OP && x.texto in ops) x.texto else null
        }

        fun comparacion(): Nodo {
            var a = concatenar()
            while (true) {
                val op = esOp("=", "<>", "<", ">", "<=", ">=") ?: return a
                i++
                a = Nodo.Op(op, a, concatenar())
            }
        }

        fun concatenar(): Nodo {
            var a = suma()
            while (esOp("&") != null) { i++; a = Nodo.Op("&", a, suma()) }
            return a
        }

        fun suma(): Nodo {
            var a = producto()
            while (true) {
                val op = esOp("+", "-") ?: return a
                i++
                a = Nodo.Op(op, a, producto())
            }
        }

        fun producto(): Nodo {
            var a = potencia()
            while (true) {
                val op = esOp("*", "/") ?: return a
                i++
                a = Nodo.Op(op, a, potencia())
            }
        }

        fun potencia(): Nodo {
            var a = signo()
            while (esOp("^") != null) { i++; a = Nodo.Op("^", a, signo()) }
            return a
        }

        /** En Excel el signo va **antes** que la potencia: `-2^2` son 4. */
        fun signo(): Nodo {
            val op = esOp("+", "-")
            if (op != null) {
                i++
                val x = signo()
                return if (op == "-") Nodo.Menos(x) else x
            }
            return porCiento()
        }

        fun porCiento(): Nodo {
            var a = primario()
            while (esOp("%") != null) { i++; a = Nodo.PorCiento(a) }
            return a
        }

        fun primario(): Nodo {
            val x = ver() ?: throw Mal()
            when (x.tipo) {
                PiezaDeFormula.NUM -> { i++; return Nodo.Num(x.numero) }
                PiezaDeFormula.TXT -> { i++; return Nodo.Txt(x.texto) }
                PiezaDeFormula.ERR -> { i++; return Nodo.Err(x.texto) }
                PiezaDeFormula.COLUMNAS -> {
                    i++
                    return Nodo.Rango(0, minOf(x.col, x.col2), Celdas.MAX_FILAS - 1, maxOf(x.col, x.col2), columnas = true)
                }
                PiezaDeFormula.REF -> {
                    i++
                    val sig = ver()
                    if (sig != null && sig.tipo == PiezaDeFormula.DOS_PUNTOS) {
                        val otra = p.getOrNull(i + 1)
                        if (otra != null && otra.tipo == PiezaDeFormula.REF) {
                            i += 2
                            return Nodo.Rango(
                                minOf(x.fila, otra.fila), minOf(x.col, otra.col),
                                maxOf(x.fila, otra.fila), maxOf(x.col, otra.col)
                            )
                        }
                        throw Mal()
                    }
                    return Nodo.Ref(x.fila, x.col)
                }
                PiezaDeFormula.NOMBRE -> {
                    i++
                    val nombre = x.texto.uppercase()
                    val sig = ver()
                    if (sig != null && sig.tipo == PiezaDeFormula.ABRE) {
                        i++
                        val args = ArrayList<Nodo>()
                        if (ver()?.tipo == PiezaDeFormula.CIERRA) { i++; return Nodo.Funcion(nombre, args) }
                        while (true) {
                            val a = ver() ?: throw Mal()
                            if (a.tipo == PiezaDeFormula.SEP || a.tipo == PiezaDeFormula.CIERRA) args += Nodo.Hueco
                            else args += comparacion()
                            val s = ver() ?: throw Mal()
                            if (s.tipo == PiezaDeFormula.SEP) { i++; continue }
                            if (s.tipo == PiezaDeFormula.CIERRA) { i++; break }
                            throw Mal()
                        }
                        return Nodo.Funcion(nombre, args)
                    }
                    return when (nombre) {
                        "VERDADERO", "TRUE" -> Nodo.Log(true)
                        "FALSO", "FALSE" -> Nodo.Log(false)
                        else -> Nodo.Err("#¿NOMBRE?")
                    }
                }
                PiezaDeFormula.ABRE -> {
                    i++
                    val dentro = comparacion()
                    if (ver()?.tipo != PiezaDeFormula.CIERRA) throw Mal()
                    i++
                    return dentro
                }
                else -> throw Mal()
            }
        }
    }

    /**
     * **Las celdas que cita una fórmula**, aunque esté a medio escribir, con dónde está cada cita
     * en el texto (contando el `=`). Es lo que pinta cada referencia de un color mientras se
     * edita, como Excel.
     */
    fun referencias(formula: String): List<ReferenciaEnFormula> {
        if (!formula.startsWith("=")) return emptyList()
        val p = piezas(formula.substring(1))
        val out = ArrayList<ReferenciaEnFormula>()
        var k = 0
        while (k < p.size) {
            val x = p[k]
            if (x.tipo == PiezaDeFormula.REF && k + 2 < p.size && p[k + 1].tipo == PiezaDeFormula.DOS_PUNTOS && p[k + 2].tipo == PiezaDeFormula.REF) {
                val y = p[k + 2]
                out += ReferenciaEnFormula(minOf(x.fila, y.fila), minOf(x.col, y.col), maxOf(x.fila, y.fila), maxOf(x.col, y.col), x.desde + 1, y.hasta + 1)
                k += 3
                continue
            }
            when (x.tipo) {
                PiezaDeFormula.REF -> out += ReferenciaEnFormula(x.fila, x.col, x.fila, x.col, x.desde + 1, x.hasta + 1)
                PiezaDeFormula.COLUMNAS -> out += ReferenciaEnFormula(0, minOf(x.col, x.col2), Celdas.MAX_FILAS - 1, maxOf(x.col, x.col2), x.desde + 1, x.hasta + 1)
            }
            k++
        }
        return out
    }

    /** Escribe una dirección con sus `$`. */
    private fun direccion(fila: Int, col: Int, filaFija: Boolean, colFija: Boolean): String =
        (if (colFija) "$" else "") + Celdas.letras(col) + (if (filaFija) "$" else "") + (fila + 1)

    /**
     * **La fórmula copiada [dFilas] y [dCols] más allá**: lo que no lleva `$` se mueve con
     * ella, como al copiar en Excel. Lo que se sale de la hoja se queda en `#¡REF!`.
     */
    fun desplazar(formula: String, dFilas: Int, dCols: Int): String {
        if (dFilas == 0 && dCols == 0) return formula
        val cuerpo = if (formula.startsWith("=")) formula.substring(1) else return formula
        val sb = StringBuilder("=")
        var ultimo = 0
        for (x in piezas(cuerpo)) {
            val nuevo = when (x.tipo) {
                PiezaDeFormula.REF -> {
                    val f = if (x.filaFija) x.fila else x.fila + dFilas
                    val c = if (x.colFija) x.col else x.col + dCols
                    if (f !in 0 until Celdas.MAX_FILAS || c !in 0 until Celdas.MAX_COLS) ERROR_REF
                    else direccion(f, c, x.filaFija, x.colFija)
                }
                PiezaDeFormula.COLUMNAS -> {
                    val c1 = if (x.colFija) x.col else x.col + dCols
                    val c2 = if (x.col2Fija) x.col2 else x.col2 + dCols
                    if (c1 !in 0 until Celdas.MAX_COLS || c2 !in 0 until Celdas.MAX_COLS) ERROR_REF
                    else (if (x.colFija) "$" else "") + Celdas.letras(c1) + ":" + (if (x.col2Fija) "$" else "") + Celdas.letras(c2)
                }
                else -> null
            } ?: continue
            sb.append(cuerpo, ultimo, x.desde).append(nuevo)
            ultimo = x.hasta
        }
        sb.append(cuerpo, ultimo, cuerpo.length)
        return sb.toString()
    }

    /**
     * **Se han metido ([cuantos] > 0) o quitado ([cuantos] < 0) filas o columnas** a partir de
     * [en]. Las direcciones que quedan detrás se corren —con `$` o sin él, igual que en Excel—;
     * un rango que pierde filas se encoge; lo que apuntaba a una celda borrada pasa a
     * `#¡REF!`.
     */
    fun alInsertar(formula: String, columnas: Boolean, en: Int, cuantos: Int): String {
        if (cuantos == 0 || !formula.startsWith("=")) return formula
        val cuerpo = formula.substring(1)
        val p = piezas(cuerpo)
        val sb = StringBuilder("=")
        var ultimo = 0
        val borradoHasta = en - cuantos // exclusivo, solo al quitar
        fun mover(v: Int): Int? = when {
            cuantos > 0 -> if (v >= en) v + cuantos else v
            v < en -> v
            v < borradoHasta -> null
            else -> v + cuantos
        }
        var k = 0
        while (k < p.size) {
            val x = p[k]
            // Un rango `A1:B3`: se mira entero para poder encogerlo.
            if (x.tipo == PiezaDeFormula.REF && k + 2 < p.size && p[k + 1].tipo == PiezaDeFormula.DOS_PUNTOS && p[k + 2].tipo == PiezaDeFormula.REF) {
                val y = p[k + 2]
                val a1 = if (columnas) x.col else x.fila
                val a2 = if (columnas) y.col else y.fila
                val lo = minOf(a1, a2)
                val hi = maxOf(a1, a2)
                var nlo: Int?
                var nhi: Int?
                if (cuantos > 0) {
                    nlo = mover(lo); nhi = mover(hi)
                } else {
                    nlo = mover(lo) ?: en
                    nhi = mover(hi) ?: (en - 1)
                    if (nhi < nlo) { nlo = null; nhi = null }
                }
                val texto = if (nlo == null || nhi == null) ERROR_REF else {
                    val (primero, segundo) = if (a1 <= a2) nlo to nhi else nhi to nlo
                    if (columnas) {
                        direccion(x.fila, primero, x.filaFija, x.colFija) + ":" + direccion(y.fila, segundo, y.filaFija, y.colFija)
                    } else {
                        direccion(primero, x.col, x.filaFija, x.colFija) + ":" + direccion(segundo, y.col, y.filaFija, y.colFija)
                    }
                }
                sb.append(cuerpo, ultimo, x.desde).append(texto)
                ultimo = y.hasta
                k += 3
                continue
            }
            val texto = when (x.tipo) {
                PiezaDeFormula.REF -> {
                    val v = mover(if (columnas) x.col else x.fila)
                    if (v == null) ERROR_REF
                    else if (columnas) direccion(x.fila, v, x.filaFija, x.colFija)
                    else direccion(v, x.col, x.filaFija, x.colFija)
                }
                PiezaDeFormula.COLUMNAS -> if (!columnas) null else {
                    val lo = minOf(x.col, x.col2)
                    val hi = maxOf(x.col, x.col2)
                    var nlo = mover(lo)
                    var nhi = mover(hi)
                    if (cuantos < 0) { nlo = nlo ?: en; nhi = nhi ?: (en - 1) }
                    if (nlo == null || nhi == null || nhi < nlo) ERROR_REF
                    else (if (x.colFija) "$" else "") + Celdas.letras(nlo) + ":" + (if (x.col2Fija) "$" else "") + Celdas.letras(nhi)
                }
                else -> null
            }
            if (texto != null) {
                sb.append(cuerpo, ultimo, x.desde).append(texto)
                ultimo = x.hasta
            }
            k++
        }
        sb.append(cuerpo, ultimo, cuerpo.length)
        return sb.toString()
    }
}
