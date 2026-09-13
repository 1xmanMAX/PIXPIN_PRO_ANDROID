package com.forge.pixpin.motor

/** Lo que vale una celda después de calcularla. */
sealed interface Valor {
    /** [fecha]: el número es un día de Excel y se enseña como `dd/mm/aaaa`. */
    data class Num(val n: Double, val fecha: Boolean = false) : Valor
    data class Txt(val s: String) : Valor
    data class Log(val b: Boolean) : Valor
    data class Err(val e: String) : Valor
    data object Vacio : Valor
}

/**
 * **La hoja que calcula.** Guarda lo escrito en cada celda —el número, el texto o la fórmula
 * tal cual— y calcula **solo lo que se pide**, recordándolo hasta el siguiente cambio.
 *
 * ## Por qué perezosa
 *
 * En pantalla se ven unas trescientas celdas de una tabla que puede tener diez mil. Pedir
 * el valor de las que se ven arrastra solo lo que ellas usan; el resto no se toca. Cambiar
 * una celda borra lo recordado **entero** —no se lleva la cuenta de quién depende de
 * quién—, que parece caro y no lo es: recalcular lo visible son microsegundos, y un grafo de
 * dependencias es donde las hojas de cálculo esconden sus fallos.
 *
 * ## Cadenas largas
 *
 * `A2 = A1 + 1` copiado diez mil filas es una cadena de diez mil llamadas, y eso revienta la
 * pila. Por encima de [PROFUNDIDAD] se abandona el cálculo, se recorren las fórmulas en
 * orden —así cada una encuentra hecha la anterior— y se vuelve a pedir. Ver [calentar].
 *
 * ## Igual que la página web
 *
 * `VisorTabla.JS` es este mismo archivo escrito en JavaScript, para que la tabla exportada
 * siga calculando en el navegador. Los nombres de las funciones de allí son los de aquí, y
 * `CalculoEnNodeTest` pasa las mismas fórmulas por las dos y compara lo que enseñan.
 */
class Calculadora {

    private val crudos = HashMap<Int, String>()
    private val valores = HashMap<Int, Valor>()
    private val arboles = HashMap<String, Nodo>()
    private val calculando = HashSet<Int>()
    private var medidas = true
    private var nFilas = 0
    private var nCols = 0

    /** El reloj de `HOY()` y `AHORA()`, y el azar de `ALEATORIO()`: se cambian en las pruebas. */
    var reloj: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() }
    var azar: () -> Double = { Math.random() }

    /** Cuántas filas y columnas llegan a tener algo escrito. */
    val filas: Int get() { medir(); return nFilas }
    val cols: Int get() { medir(); return nCols }

    private fun medir() {
        if (medidas) return
        var f = 0
        var c = 0
        for (k in crudos.keys) {
            f = maxOf(f, Celdas.filaDe(k) + 1)
            c = maxOf(c, Celdas.colDe(k) + 1)
        }
        nFilas = f; nCols = c; medidas = true
    }

    fun poner(fila: Int, col: Int, texto: String) {
        val k = Celdas.clave(fila, col)
        if (texto.isEmpty()) {
            if (crudos.remove(k) != null) medidas = false
        } else {
            crudos[k] = texto
            if (medidas) { nFilas = maxOf(nFilas, fila + 1); nCols = maxOf(nCols, col + 1) }
        }
        valores.clear()
    }

    /** Cambia todo lo escrito de golpe. Las claves son las de [Celdas.clave]. */
    fun ponerTodo(celdas: Map<Int, String>) {
        crudos.clear()
        for ((k, v) in celdas) if (v.isNotEmpty()) crudos[k] = v
        medidas = false
        valores.clear()
    }

    fun todo(): Map<Int, String> = HashMap(crudos)

    /**
     * **El rectángulo que ocupa lo escrito**: fila y columna de arriba a la izquierda y de abajo
     * a la derecha, o null si la tabla está vacía. Es lo que se comparte. Ver [VisorTabla.marco].
     */
    fun caja(): IntArray? {
        if (crudos.isEmpty()) return null
        var f1 = Int.MAX_VALUE; var c1 = Int.MAX_VALUE; var f2 = -1; var c2 = -1
        for (k in crudos.keys) {
            val f = Celdas.filaDe(k); val c = Celdas.colDe(k)
            if (f < f1) f1 = f
            if (c < c1) c1 = c
            if (f > f2) f2 = f
            if (c > c2) c2 = c
        }
        return intArrayOf(f1, c1, f2, c2)
    }

    fun crudo(fila: Int, col: Int): String = crudos[Celdas.clave(fila, col)] ?: ""

    fun esFormula(fila: Int, col: Int): Boolean = esFormula(crudo(fila, col))

    /** Lo que se ve en la celda. */
    fun texto(fila: Int, col: Int): String {
        val raw = crudos[Celdas.clave(fila, col)] ?: return ""
        if (!esFormula(raw)) return if (raw.startsWith("'")) raw.substring(1) else raw
        return mostrar(valor(fila, col))
    }

    /** `d` a la derecha (números), `c` al centro (errores y lógicos), `i` a la izquierda. */
    fun alineacion(fila: Int, col: Int): Char = when (valor(fila, col)) {
        is Valor.Num -> 'd'
        is Valor.Log, is Valor.Err -> 'c'
        else -> 'i'
    }

    fun valor(fila: Int, col: Int): Valor = try {
        celda(fila, col, 0)
    } catch (_: Hondo) {
        calentar()
        try { celda(fila, col, 0) } catch (_: Hondo) { Valor.Err(CalculoFormato.ERROR_NUM) }
    }

    /** Vuelve a calcular todo lo que se pida a partir de ahora (lo de `HOY()`, por ejemplo). */
    fun olvidar() = valores.clear()

    private class Hondo : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Fallo(val v: Valor.Err) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Ver la cabecera: las fórmulas en orden, a pasadas, hasta que la cadena se resuelve. */
    private fun calentar() {
        val orden = crudos.entries.filter { esFormula(it.value) }.map { it.key }.sorted()
        val pasadas = orden.size / PROFUNDIDAD + 2
        for (p in 0 until pasadas) {
            var falta = false
            val lista = if (p % 2 == 0) orden else orden.asReversed()
            for (k in lista) {
                if (valores.containsKey(k)) continue
                try { celda(Celdas.filaDe(k), Celdas.colDe(k), 0) } catch (_: Hondo) { falta = true }
            }
            if (!falta) return
        }
    }

    private fun celda(fila: Int, col: Int, prof: Int): Valor {
        val k = Celdas.clave(fila, col)
        valores[k]?.let { return it }
        val raw = crudos[k] ?: return Valor.Vacio
        if (!esFormula(raw)) {
            val v = literal(raw)
            valores[k] = v
            return v
        }
        if (k in calculando) return Valor.Err(ERROR_CIRC)
        if (prof > PROFUNDIDAD) throw Hondo()
        calculando += k
        try {
            val arbol = arboles.getOrPut(raw) { CalculoLexico.arbol(raw.substring(1)) }
            var v = evaluar(arbol, prof + 1, fila, col)
            if (v is Valor.Vacio) v = Valor.Num(0.0)
            if (v is Valor.Num && !v.n.isFinite()) v = Valor.Err(CalculoFormato.ERROR_NUM)
            valores[k] = v
            return v
        } finally {
            calculando -= k
        }
    }

    private fun evaluar(n: Nodo, prof: Int, fila: Int, col: Int): Valor = when (n) {
        is Nodo.Num -> Valor.Num(n.n)
        is Nodo.Txt -> Valor.Txt(n.s)
        is Nodo.Log -> Valor.Log(n.b)
        is Nodo.Err -> Valor.Err(n.e)
        is Nodo.Hueco -> Valor.Vacio
        is Nodo.Ref -> celda(n.fila, n.col, prof)
        is Nodo.Rango ->
            if (!n.columnas && n.f1 == n.f2 && n.c1 == n.c2) celda(n.f1, n.c1, prof)
            else Valor.Err(ERROR_VALOR)
        is Nodo.Menos -> when (val x = aNumero(evaluar(n.x, prof, fila, col))) {
            is Valor.Num -> Valor.Num(-x.n)
            else -> x
        }
        is Nodo.PorCiento -> when (val x = aNumero(evaluar(n.x, prof, fila, col))) {
            is Valor.Num -> Valor.Num(x.n / 100)
            else -> x
        }
        is Nodo.Op -> operar(n, prof, fila, col)
        is Nodo.Funcion -> llamar(n, prof, fila, col)
    }

    private fun operar(n: Nodo.Op, prof: Int, fila: Int, col: Int): Valor {
        val a = evaluar(n.a, prof, fila, col)
        val b = evaluar(n.b, prof, fila, col)
        when (n.op) {
            "&" -> {
                val x = aTexto(a); if (x is Valor.Err) return x
                val y = aTexto(b); if (y is Valor.Err) return y
                return Valor.Txt((x as Valor.Txt).s + (y as Valor.Txt).s)
            }
            "=", "<>", "<", ">", "<=", ">=" -> {
                if (a is Valor.Err) return a
                if (b is Valor.Err) return b
                val c = comparar(a, b)
                return Valor.Log(
                    when (n.op) {
                        "=" -> c == 0
                        "<>" -> c != 0
                        "<" -> c < 0
                        ">" -> c > 0
                        "<=" -> c <= 0
                        else -> c >= 0
                    }
                )
            }
        }
        val x = aNumero(a); if (x is Valor.Err) return x
        val y = aNumero(b); if (y is Valor.Err) return y
        val p = x as Valor.Num
        val q = y as Valor.Num
        val r = when (n.op) {
            "+" -> return Valor.Num(p.n + q.n, p.fecha || q.fecha)
            "-" -> return Valor.Num(p.n - q.n, p.fecha && !q.fecha)
            "*" -> p.n * q.n
            "/" -> if (q.n == 0.0) return Valor.Err(ERROR_DIV0) else p.n / q.n
            else -> Math.pow(p.n, q.n)
        }
        return if (r.isFinite()) Valor.Num(r) else Valor.Err(CalculoFormato.ERROR_NUM)
    }

    // ---- Conversiones ----

    private fun aNumero(v: Valor): Valor = when (v) {
        is Valor.Num, is Valor.Err -> v
        is Valor.Log -> Valor.Num(if (v.b) 1.0 else 0.0)
        is Valor.Vacio -> Valor.Num(0.0)
        is Valor.Txt -> CalculoFormato.numero(v.s)?.let { Valor.Num(it) }
            ?: CalculoFormato.fecha(v.s)?.let { Valor.Num(it, true) }
            ?: Valor.Err(ERROR_VALOR)
    }

    private fun aTexto(v: Valor): Valor = when (v) {
        is Valor.Txt, is Valor.Err -> v
        else -> Valor.Txt(mostrar(v))
    }

    private fun aLogico(v: Valor): Valor = when (v) {
        is Valor.Log, is Valor.Err -> v
        is Valor.Num -> Valor.Log(v.n != 0.0)
        is Valor.Vacio -> Valor.Log(false)
        is Valor.Txt -> when (v.s.trim().uppercase()) {
            "VERDADERO", "TRUE" -> Valor.Log(true)
            "FALSO", "FALSE" -> Valor.Log(false)
            else -> Valor.Err(ERROR_VALOR)
        }
    }

    // ---- Funciones ----

    private fun llamar(fn: Nodo.Funcion, prof: Int, fila: Int, col: Int): Valor {
        val nombre = ALIAS[fn.nombre] ?: fn.nombre
        val a = fn.args
        fun ev(i: Int): Valor = if (i < a.size) evaluar(a[i], prof, fila, col) else Valor.Vacio
        fun pide(min: Int, max: Int) { if (a.size < min || a.size > max) throw Fallo(Valor.Err(ERROR_VALOR)) }
        fun num(i: Int): Double = when (val v = aNumero(ev(i))) {
            is Valor.Num -> v.n
            is Valor.Err -> throw Fallo(v)
            else -> throw Fallo(Valor.Err(ERROR_VALOR))
        }
        fun numO(i: Int, si: Double): Double = if (i >= a.size || a[i] is Nodo.Hueco) si else num(i)
        fun txt(i: Int): String = when (val v = aTexto(ev(i))) {
            is Valor.Txt -> v.s
            is Valor.Err -> throw Fallo(v)
            else -> ""
        }
        fun log(i: Int): Boolean = when (val v = aLogico(ev(i))) {
            is Valor.Log -> v.b
            is Valor.Err -> throw Fallo(v)
            else -> false
        }
        fun rango(i: Int): Nodo.Rango = when (val n = a.getOrNull(i)) {
            is Nodo.Rango -> if (n.columnas) n.copy(f2 = maxOf(n.f1, filas - 1)) else n
            is Nodo.Ref -> Nodo.Rango(n.fila, n.col, n.fila, n.col)
            else -> throw Fallo(Valor.Err(ERROR_VALOR))
        }
        fun num1(d: Double): Valor = if (d.isFinite()) Valor.Num(d) else Valor.Err(CalculoFormato.ERROR_NUM)

        try {
            return when (nombre) {
                "SUM" -> { var s = 0.0; numeros(a, prof, fila, col, false) { s += it }; Valor.Num(s) }
                "AVERAGE" -> {
                    var s = 0.0; var n = 0
                    numeros(a, prof, fila, col, false) { s += it; n++ }
                    if (n == 0) Valor.Err(ERROR_DIV0) else Valor.Num(s / n)
                }
                "MIN", "MAX" -> {
                    var m = if (nombre == "MIN") Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
                    var n = 0
                    numeros(a, prof, fila, col, false) { m = if (nombre == "MIN") minOf(m, it) else maxOf(m, it); n++ }
                    Valor.Num(if (n == 0) 0.0 else m)
                }
                "COUNT" -> { var n = 0; numeros(a, prof, fila, col, true) { n++ }; Valor.Num(n.toDouble()) }
                "COUNTA" -> {
                    var n = 0
                    for (x in a) {
                        when (x) {
                            is Nodo.Rango, is Nodo.Ref -> recorrer(rangoDe(x), prof, true) { v, _, _ -> if (v !is Valor.Vacio) n++; true }
                            is Nodo.Hueco -> {}
                            else -> if (evaluar(x, prof, fila, col) !is Valor.Vacio) n++
                        }
                    }
                    Valor.Num(n.toDouble())
                }
                "COUNTBLANK" -> {
                    pide(1, 1)
                    var n = 0
                    recorrer(rango(0), prof, false) { v, _, _ -> if (v is Valor.Vacio || (v is Valor.Txt && v.s.isEmpty())) n++; true }
                    Valor.Num(n.toDouble())
                }
                "PRODUCT" -> {
                    var p = 1.0; var n = 0
                    numeros(a, prof, fila, col, false) { p *= it; n++ }
                    num1(if (n == 0) 0.0 else p)
                }
                "MEDIAN" -> {
                    val l = ArrayList<Double>()
                    numeros(a, prof, fila, col, false) { l += it }
                    if (l.isEmpty()) Valor.Err(CalculoFormato.ERROR_NUM) else {
                        l.sort()
                        val m = l.size / 2
                        Valor.Num(if (l.size % 2 == 1) l[m] else (l[m - 1] + l[m]) / 2)
                    }
                }
                "STDEV" -> {
                    val l = ArrayList<Double>()
                    numeros(a, prof, fila, col, false) { l += it }
                    if (l.size < 2) Valor.Err(ERROR_DIV0) else {
                        var media = 0.0
                        for (x in l) media += x
                        media /= l.size
                        var s = 0.0
                        for (x in l) s += (x - media) * (x - media)
                        Valor.Num(Math.sqrt(s / (l.size - 1)))
                    }
                }
                "IF" -> {
                    pide(1, 3)
                    if (log(0)) (if (a.size > 1) ev(1) else Valor.Log(true))
                    else (if (a.size > 2) ev(2) else Valor.Log(false))
                }
                "IFS" -> {
                    if (a.isEmpty() || a.size % 2 != 0) throw Fallo(Valor.Err(ERROR_VALOR))
                    var r: Valor = Valor.Err(ERROR_ND)
                    var i = 0
                    while (i < a.size) { if (log(i)) { r = ev(i + 1); break }; i += 2 }
                    r
                }
                "AND", "OR" -> {
                    var hay = false
                    var r = nombre == "AND"
                    for (x in a) {
                        when (x) {
                            is Nodo.Rango, is Nodo.Ref -> recorrer(rangoDe(x), prof, true) { v, _, _ ->
                                val b = when (v) {
                                    is Valor.Log -> v.b
                                    is Valor.Num -> v.n != 0.0
                                    is Valor.Err -> throw Fallo(v)
                                    else -> null
                                }
                                if (b != null) { hay = true; r = if (nombre == "AND") r && b else r || b }
                                true
                            }
                            is Nodo.Hueco -> {}
                            else -> {
                                val b = when (val v = aLogico(evaluar(x, prof, fila, col))) {
                                    is Valor.Log -> v.b
                                    is Valor.Err -> throw Fallo(v)
                                    else -> false
                                }
                                hay = true; r = if (nombre == "AND") r && b else r || b
                            }
                        }
                    }
                    if (!hay) Valor.Err(ERROR_VALOR) else Valor.Log(r)
                }
                "NOT" -> { pide(1, 1); Valor.Log(!log(0)) }
                "IFERROR" -> { pide(2, 2); val v = ev(0); if (v is Valor.Err) ev(1) else v }
                "IFNA" -> { pide(2, 2); val v = ev(0); if (v is Valor.Err && v.e == ERROR_ND) ev(1) else v }
                "ISERROR" -> { pide(1, 1); Valor.Log(ev(0) is Valor.Err) }
                "ISBLANK" -> { pide(1, 1); Valor.Log(ev(0) is Valor.Vacio) }
                "ISNUMBER" -> { pide(1, 1); Valor.Log(ev(0) is Valor.Num) }
                "ISTEXT" -> { pide(1, 1); Valor.Log(ev(0) is Valor.Txt) }
                "ROUND" -> { pide(1, 2); Valor.Num(CalculoFormato.redondear(num(0), numO(1, 0.0).toInt(), 0)) }
                "ROUNDUP" -> { pide(1, 2); Valor.Num(CalculoFormato.redondear(num(0), numO(1, 0.0).toInt(), 1)) }
                "ROUNDDOWN", "TRUNC" -> { pide(1, 2); Valor.Num(CalculoFormato.redondear(num(0), numO(1, 0.0).toInt(), -1)) }
                "INT" -> { pide(1, 1); Valor.Num(Math.floor(num(0))) }
                "ABS" -> { pide(1, 1); Valor.Num(Math.abs(num(0))) }
                "SQRT" -> { pide(1, 1); val x = num(0); if (x < 0) Valor.Err(CalculoFormato.ERROR_NUM) else Valor.Num(Math.sqrt(x)) }
                "POWER" -> { pide(2, 2); num1(Math.pow(num(0), num(1))) }
                "MOD" -> {
                    pide(2, 2)
                    val x = num(0); val y = num(1)
                    if (y == 0.0) Valor.Err(ERROR_DIV0) else Valor.Num(x - y * Math.floor(x / y))
                }
                "PI" -> { pide(0, 0); Valor.Num(Math.PI) }
                "EXP" -> { pide(1, 1); num1(Math.exp(num(0))) }
                "LN" -> { pide(1, 1); val x = num(0); if (x <= 0) Valor.Err(CalculoFormato.ERROR_NUM) else Valor.Num(Math.log(x)) }
                "LOG10" -> { pide(1, 1); val x = num(0); if (x <= 0) Valor.Err(CalculoFormato.ERROR_NUM) else Valor.Num(Math.log10(x)) }
                "LOG" -> {
                    pide(1, 2)
                    val x = num(0); val b = numO(1, 10.0)
                    if (x <= 0 || b <= 0) Valor.Err(CalculoFormato.ERROR_NUM)
                    else if (b == 1.0) Valor.Err(ERROR_DIV0)
                    else Valor.Num(Math.log(x) / Math.log(b))
                }
                "SIN" -> { pide(1, 1); Valor.Num(Math.sin(num(0))) }
                "COS" -> { pide(1, 1); Valor.Num(Math.cos(num(0))) }
                "TAN" -> { pide(1, 1); Valor.Num(Math.tan(num(0))) }
                "DEGREES" -> { pide(1, 1); Valor.Num(num(0) * 180 / Math.PI) }
                "RADIANS" -> { pide(1, 1); Valor.Num(num(0) * Math.PI / 180) }
                "SIGN" -> { pide(1, 1); val x = num(0); Valor.Num(if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0) }
                "RAND" -> { pide(0, 0); Valor.Num(azar()) }
                "RANDBETWEEN" -> {
                    pide(2, 2)
                    val lo = Math.ceil(num(0)); val hi = Math.floor(num(1))
                    if (hi < lo) Valor.Err(CalculoFormato.ERROR_NUM) else Valor.Num(Math.floor(azar() * (hi - lo + 1)) + lo)
                }
                "TODAY" -> {
                    pide(0, 0)
                    val t = reloj()
                    Valor.Num(CalculoFormato.serial(t.year, t.monthValue, t.dayOfMonth), true)
                }
                "NOW" -> {
                    pide(0, 0)
                    val t = reloj()
                    Valor.Num(
                        CalculoFormato.serial(t.year, t.monthValue, t.dayOfMonth) +
                            (t.hour * 3600 + t.minute * 60 + t.second) / 86400.0, true
                    )
                }
                "DATE" -> {
                    pide(3, 3)
                    var y = num(0).toInt()
                    if (y in 0..1899) y += 1900
                    if (y < 0 || y > 9999) Valor.Err(CalculoFormato.ERROR_NUM)
                    else Valor.Num(CalculoFormato.serial(y, num(1).toInt(), num(2).toInt()), true)
                }
                "DAY", "MONTH", "YEAR" -> {
                    pide(1, 1)
                    val p = CalculoFormato.partes(num(0))
                    Valor.Num(p[if (nombre == "YEAR") 0 else if (nombre == "MONTH") 1 else 2].toDouble())
                }
                "CONCATENATE", "CONCAT" -> {
                    val sb = StringBuilder()
                    for ((i, x) in a.withIndex()) {
                        if (x is Nodo.Rango && !(x.f1 == x.f2 && x.c1 == x.c2 && !x.columnas)) {
                            recorrer(rango(i), prof, true) { v, _, _ ->
                                when (val t = aTexto(v)) {
                                    is Valor.Txt -> sb.append(t.s)
                                    is Valor.Err -> throw Fallo(t)
                                    else -> {}
                                }
                                true
                            }
                        } else sb.append(txt(i))
                    }
                    Valor.Txt(sb.toString())
                }
                "LEN" -> { pide(1, 1); Valor.Num(txt(0).length.toDouble()) }
                "UPPER" -> { pide(1, 1); Valor.Txt(txt(0).uppercase()) }
                "LOWER" -> { pide(1, 1); Valor.Txt(txt(0).lowercase()) }
                "TRIM" -> { pide(1, 1); Valor.Txt(txt(0).split(' ').filter { it.isNotEmpty() }.joinToString(" ")) }
                "LEFT", "RIGHT" -> {
                    pide(1, 2)
                    val t = txt(0)
                    val n = numO(1, 1.0).toInt()
                    if (n < 0) Valor.Err(ERROR_VALOR)
                    else Valor.Txt(if (nombre == "LEFT") t.take(n) else t.takeLast(n))
                }
                "MID" -> {
                    pide(3, 3)
                    val t = txt(0)
                    val desde = num(1).toInt()
                    val n = num(2).toInt()
                    if (desde < 1 || n < 0) Valor.Err(ERROR_VALOR)
                    else Valor.Txt(if (desde > t.length) "" else t.substring(desde - 1, minOf(t.length, desde - 1 + n)))
                }
                "FIND", "SEARCH" -> {
                    pide(2, 3)
                    val que = txt(0)
                    val donde = txt(1)
                    val desde = numO(2, 1.0).toInt()
                    if (desde < 1 || desde > donde.length + 1) Valor.Err(ERROR_VALOR) else {
                        val i = if (nombre == "FIND") donde.indexOf(que, desde - 1)
                            else donde.lowercase().indexOf(que.lowercase(), desde - 1)
                        if (i < 0) Valor.Err(ERROR_VALOR) else Valor.Num((i + 1).toDouble())
                    }
                }
                "SUBSTITUTE" -> {
                    pide(3, 4)
                    val t = txt(0); val viejo = txt(1); val nuevo = txt(2)
                    if (viejo.isEmpty()) Valor.Txt(t)
                    else if (a.size < 4) Valor.Txt(t.replace(viejo, nuevo))
                    else {
                        val cual = num(3).toInt()
                        if (cual < 1) Valor.Err(ERROR_VALOR) else {
                            var i = -1
                            var visto = 0
                            var desde = 0
                            while (true) {
                                val j = t.indexOf(viejo, desde)
                                if (j < 0) break
                                visto++
                                if (visto == cual) { i = j; break }
                                desde = j + viejo.length
                            }
                            Valor.Txt(if (i < 0) t else t.substring(0, i) + nuevo + t.substring(i + viejo.length))
                        }
                    }
                }
                "REPT" -> {
                    pide(2, 2)
                    val n = num(1).toInt()
                    if (n < 0 || n > 10000) Valor.Err(ERROR_VALOR) else Valor.Txt(txt(0).repeat(n))
                }
                "TEXT" -> {
                    pide(2, 2)
                    when (val v = aNumero(ev(0))) {
                        is Valor.Num -> Valor.Txt(CalculoFormato.conFormato(v.n, txt(1)))
                        else -> Valor.Txt(txt(0))
                    }
                }
                "VALUE" -> {
                    pide(1, 1)
                    val v = ev(0)
                    if (v is Valor.Num) Valor.Num(v.n) else when (val x = aNumero(Valor.Txt(txt(0)))) {
                        is Valor.Num -> Valor.Num(x.n)
                        else -> x
                    }
                }
                "VLOOKUP", "HLOOKUP" -> {
                    pide(3, 4)
                    val buscado = ev(0)
                    if (buscado is Valor.Err) throw Fallo(buscado)
                    val r = rango(1)
                    val cual = num(2).toInt()
                    val aprox = if (a.size > 3 && a[3] !is Nodo.Hueco) log(3) else true
                    val vertical = nombre == "VLOOKUP"
                    val largo = if (vertical) r.f2 - r.f1 + 1 else r.c2 - r.c1 + 1
                    val ancho = if (vertical) r.c2 - r.c1 + 1 else r.f2 - r.f1 + 1
                    if (cual < 1) throw Fallo(Valor.Err(ERROR_VALOR))
                    if (cual > ancho) throw Fallo(Valor.Err(CalculoLexico.ERROR_REF))
                    val hasta = minOf(largo, if (vertical) filas - r.f1 else cols - r.c1)
                    val i = buscarEn(hasta, buscado, if (aprox) 1 else 0) { i ->
                        if (vertical) celda(r.f1 + i, r.c1, prof) else celda(r.f1, r.c1 + i, prof)
                    }
                    if (i < 0) Valor.Err(ERROR_ND)
                    else if (vertical) celda(r.f1 + i, r.c1 + cual - 1, prof)
                    else celda(r.f1 + cual - 1, r.c1 + i, prof)
                }
                "MATCH" -> {
                    pide(2, 3)
                    val buscado = ev(0)
                    if (buscado is Valor.Err) throw Fallo(buscado)
                    val r = rango(1)
                    val tipo = numO(2, 1.0).toInt().coerceIn(-1, 1)
                    val vertical = r.c1 == r.c2
                    if (!vertical && r.f1 != r.f2) throw Fallo(Valor.Err(ERROR_ND))
                    val largo = if (vertical) minOf(r.f2 - r.f1 + 1, filas - r.f1) else minOf(r.c2 - r.c1 + 1, cols - r.c1)
                    val i = buscarEn(largo, buscado, tipo) { i ->
                        if (vertical) celda(r.f1 + i, r.c1, prof) else celda(r.f1, r.c1 + i, prof)
                    }
                    if (i < 0) Valor.Err(ERROR_ND) else Valor.Num((i + 1).toDouble())
                }
                "XLOOKUP" -> {
                    pide(3, 4)
                    val buscado = ev(0)
                    if (buscado is Valor.Err) throw Fallo(buscado)
                    val r = rango(1)
                    val d = rango(2)
                    val vertical = r.c1 == r.c2
                    val largo = if (vertical) minOf(r.f2 - r.f1 + 1, filas - r.f1) else minOf(r.c2 - r.c1 + 1, cols - r.c1)
                    val i = buscarEn(largo, buscado, 0) { i ->
                        if (vertical) celda(r.f1 + i, r.c1, prof) else celda(r.f1, r.c1 + i, prof)
                    }
                    if (i < 0) (if (a.size > 3) ev(3) else Valor.Err(ERROR_ND))
                    else if (vertical) celda(d.f1 + i, d.c1, prof)
                    else celda(d.f1, d.c1 + i, prof)
                }
                "INDEX" -> {
                    pide(2, 3)
                    val r = rango(0)
                    var f = num(1).toInt()
                    var c = numO(2, 1.0).toInt()
                    if (a.size == 2 && r.f1 == r.f2 && r.c1 != r.c2) { c = f; f = 1 }
                    if (f < 1 || c < 1) Valor.Err(ERROR_VALOR)
                    else if (f > r.f2 - r.f1 + 1 || c > r.c2 - r.c1 + 1) Valor.Err(CalculoLexico.ERROR_REF)
                    else celda(r.f1 + f - 1, r.c1 + c - 1, prof)
                }
                "SUMIF", "AVERAGEIF", "COUNTIF" -> {
                    pide(2, if (nombre == "COUNTIF") 2 else 3)
                    val r = rango(0)
                    val criterio = ev(1)
                    if (criterio is Valor.Err) throw Fallo(criterio)
                    val destino = if (a.size > 2) rango(2) else r
                    var s = 0.0
                    var n = 0
                    val conVacias = nombre == "COUNTIF" && cumple(Valor.Vacio, criterio)
                    recorrer(r, prof, !conVacias) { v, df, dc ->
                        if (cumple(v, criterio)) {
                            if (nombre == "COUNTIF") n++
                            else {
                                val x = celda(destino.f1 + df, destino.c1 + dc, prof)
                                if (x is Valor.Num) { s += x.n; n++ }
                            }
                        }
                        true
                    }
                    when (nombre) {
                        "COUNTIF" -> Valor.Num(n.toDouble())
                        "SUMIF" -> Valor.Num(s)
                        else -> if (n == 0) Valor.Err(ERROR_DIV0) else Valor.Num(s / n)
                    }
                }
                "SUMIFS", "AVERAGEIFS", "COUNTIFS" -> {
                    val cuenta = nombre == "COUNTIFS"
                    val primero = if (cuenta) 0 else 1
                    if (a.size < primero + 2 || (a.size - primero) % 2 != 0) throw Fallo(Valor.Err(ERROR_VALOR))
                    val base = rango(0)
                    val pares = ArrayList<Pair<Nodo.Rango, Valor>>()
                    var i = primero
                    var conVacias = cuenta
                    while (i < a.size) {
                        val r = rango(i)
                        if (r.f2 - r.f1 != base.f2 - base.f1 || r.c2 - r.c1 != base.c2 - base.c1) throw Fallo(Valor.Err(ERROR_VALOR))
                        val crit = ev(i + 1)
                        if (crit is Valor.Err) throw Fallo(crit)
                        if (!cumple(Valor.Vacio, crit)) conVacias = false
                        pares += r to crit
                        i += 2
                    }
                    var s = 0.0
                    var n = 0
                    // Se recorre la forma del primer rango; lo que cuenta es su desplazamiento.
                    recorrer(base, prof, !conVacias) { v, df, dc ->
                        var todos = true
                        for ((r, crit) in pares) {
                            if (!cumple(celda(r.f1 + df, r.c1 + dc, prof), crit)) { todos = false; break }
                        }
                        if (todos) {
                            if (cuenta) n++
                            else if (v is Valor.Num) { s += v.n; n++ }
                        }
                        true
                    }
                    when (nombre) {
                        "COUNTIFS" -> Valor.Num(n.toDouble())
                        "SUMIFS" -> Valor.Num(s)
                        else -> if (n == 0) Valor.Err(ERROR_DIV0) else Valor.Num(s / n)
                    }
                }
                "SUMPRODUCT" -> {
                    if (a.isEmpty()) throw Fallo(Valor.Err(ERROR_VALOR))
                    val rs = a.indices.map { rango(it) }
                    val base = rs[0]
                    for (r in rs) if (r.f2 - r.f1 != base.f2 - base.f1 || r.c2 - r.c1 != base.c2 - base.c1) throw Fallo(Valor.Err(ERROR_VALOR))
                    var s = 0.0
                    recorrer(base, prof, true) { v, df, dc ->
                        var p = if (v is Valor.Num) v.n else 0.0
                        for (k in 1 until rs.size) {
                            if (p == 0.0) break
                            val x = celda(rs[k].f1 + df, rs[k].c1 + dc, prof)
                            if (x is Valor.Err) throw Fallo(x)
                            p *= if (x is Valor.Num) x.n else 0.0
                        }
                        if (v is Valor.Err) throw Fallo(v)
                        s += p
                        true
                    }
                    Valor.Num(s)
                }
                "CHOOSE" -> {
                    if (a.size < 2) throw Fallo(Valor.Err(ERROR_VALOR))
                    val i = num(0).toInt()
                    if (i < 1 || i >= a.size) Valor.Err(ERROR_VALOR) else ev(i)
                }
                "ROW", "COLUMN" -> {
                    pide(0, 1)
                    if (a.isEmpty()) Valor.Num(((if (nombre == "ROW") fila else col) + 1).toDouble())
                    else {
                        val r = rango(0)
                        Valor.Num(((if (nombre == "ROW") r.f1 else r.c1) + 1).toDouble())
                    }
                }
                else -> Valor.Err(ERROR_NOMBRE)
            }
        } catch (f: Fallo) {
            return f.v
        }
    }

    private fun rangoDe(n: Nodo): Nodo.Rango = when (n) {
        is Nodo.Rango -> if (n.columnas) n.copy(f2 = maxOf(n.f1, filas - 1)) else n
        is Nodo.Ref -> Nodo.Rango(n.fila, n.col, n.fila, n.col)
        else -> throw IllegalArgumentException()
    }

    /**
     * Pasa por cada celda de [r], por filas. [soloEscritas] se salta lo que queda fuera de lo
     * escrito —que está vacío seguro—: `SUMA(A1:A100000)` no recorre cien mil celdas.
     * [visita] recibe el valor y el desplazamiento desde la esquina; si devuelve false, para.
     */
    private inline fun recorrer(r: Nodo.Rango, prof: Int, soloEscritas: Boolean, visita: (Valor, Int, Int) -> Boolean) {
        val f2 = if (soloEscritas) minOf(r.f2, filas - 1) else r.f2
        val c2 = if (soloEscritas) minOf(r.c2, cols - 1) else r.c2
        var f = r.f1
        while (f <= f2) {
            var c = r.c1
            while (c <= c2) {
                if (!visita(celda(f, c, prof), f - r.f1, c - r.c1)) return
                c++
            }
            f++
        }
    }

    /**
     * Los números de los argumentos, como los cuenta `SUMA`: en las referencias solo los
     * números —el texto y los lógicos se saltan—; escritos a mano, también el texto que sea
     * un número y los lógicos. [contando] es `CONTAR`: lo que no es número no es un error.
     */
    private inline fun numeros(a: List<Nodo>, prof: Int, fila: Int, col: Int, contando: Boolean, cada: (Double) -> Unit) {
        for (x in a) {
            when (x) {
                is Nodo.Rango, is Nodo.Ref -> recorrer(rangoDe(x), prof, true) { v, _, _ ->
                    if (v is Valor.Num) cada(v.n)
                    else if (v is Valor.Err && !contando) throw Fallo(v)
                    true
                }
                is Nodo.Hueco -> {}
                else -> when (val v = evaluar(x, prof, fila, col)) {
                    is Valor.Num -> cada(v.n)
                    is Valor.Log -> cada(if (v.b) 1.0 else 0.0)
                    is Valor.Txt -> {
                        val n = CalculoFormato.numero(v.s) ?: CalculoFormato.fecha(v.s)
                        if (n != null) cada(n) else if (!contando) throw Fallo(Valor.Err(ERROR_VALOR))
                    }
                    is Valor.Err -> if (!contando) throw Fallo(v)
                    is Valor.Vacio -> {}
                }
            }
        }
    }

    /**
     * Dónde está [buscado] entre las [largo] celdas que da [en]. [tipo] como `COINCIDIR`:
     * 0 exacto; 1 el mayor que no se pasa (lista de menor a mayor); -1 el menor que no se
     * queda corto (de mayor a menor). -1 si no está.
     */
    private inline fun buscarEn(largo: Int, buscado: Valor, tipo: Int, en: (Int) -> Valor): Int {
        var ultimo = -1
        for (i in 0 until largo) {
            val v = en(i)
            if (v is Valor.Vacio) continue
            val mismoTipo = rango(v) == rango(buscado)
            if (tipo == 0) {
                if (mismoTipo && comparar(v, buscado) == 0) return i
                if (buscado is Valor.Txt && v is Valor.Txt && buscado.s.any { it == '*' || it == '?' } &&
                    comodin(buscado.s.lowercase(), v.s.lowercase())) return i
                continue
            }
            if (!mismoTipo) continue
            val c = comparar(v, buscado)
            if (c == 0) return i
            if (tipo == 1) { if (c < 0) ultimo = i else break }
            else { if (c > 0) ultimo = i else break }
        }
        return ultimo
    }

    companion object {
        const val PROFUNDIDAD = 400
        const val ERROR_DIV0 = "#¡DIV/0!"
        const val ERROR_VALOR = "#¡VALOR!"
        const val ERROR_NOMBRE = "#¿NOMBRE?"
        const val ERROR_ND = "#N/D"
        const val ERROR_CIRC = "#¡CIRC!"

        fun esFormula(raw: String) = raw.length > 1 && raw[0] == '='

        /** Lo escrito a mano, sin `=`: número, fecha, lógico o texto. */
        fun literal(raw: String): Valor {
            if (raw.startsWith("'")) return Valor.Txt(raw.substring(1))
            CalculoFormato.numero(raw)?.let { return Valor.Num(it) }
            CalculoFormato.fecha(raw)?.let { return Valor.Num(it, true) }
            return when (raw.trim().uppercase()) {
                "VERDADERO", "TRUE" -> Valor.Log(true)
                "FALSO", "FALSE" -> Valor.Log(false)
                else -> Valor.Txt(raw)
            }
        }

        fun mostrar(v: Valor): String = when (v) {
            is Valor.Num -> if (v.fecha) CalculoFormato.textoDeFecha(v.n) else CalculoFormato.general(v.n)
            is Valor.Txt -> v.s
            is Valor.Log -> if (v.b) "VERDADERO" else "FALSO"
            is Valor.Err -> v.e
            is Valor.Vacio -> ""
        }

        private fun rango(v: Valor): Int = when (v) {
            is Valor.Num, is Valor.Vacio -> 0
            is Valor.Txt -> 1
            is Valor.Log -> 2
            is Valor.Err -> 3
        }

        private fun signo(x: Int) = if (x < 0) -1 else if (x > 0) 1 else 0

        /**
         * Compara dos valores como Excel: números < textos < lógicos; el texto sin mirar
         * mayúsculas; y dos números que solo se separan en la cifra dieciséis, iguales
         * (`0,1 + 0,2 = 0,3` es VERDADERO).
         */
        fun comparar(a: Valor, b: Valor): Int {
            val x = if (a is Valor.Vacio) vacioComo(b) else a
            val y = if (b is Valor.Vacio) vacioComo(a) else b
            val ra = rango(x)
            val rb = rango(y)
            if (ra != rb) return if (ra < rb) -1 else 1
            return when (x) {
                is Valor.Num -> numeros((x).n, (y as Valor.Num).n)
                is Valor.Txt -> signo(x.s.lowercase().compareTo((y as Valor.Txt).s.lowercase()))
                is Valor.Log -> if (x.b == (y as Valor.Log).b) 0 else if (!x.b) -1 else 1
                else -> 0
            }
        }

        private fun vacioComo(o: Valor): Valor = when (o) {
            is Valor.Txt -> Valor.Txt("")
            is Valor.Log -> Valor.Log(false)
            else -> Valor.Num(0.0)
        }

        fun numeros(a: Double, b: Double): Int {
            if (a == b) return 0
            if (Math.abs(a - b) <= 1e-12 * maxOf(Math.abs(a), Math.abs(b))) return 0
            return if (a < b) -1 else 1
        }

        /** `*` es cualquier cosa, `?` una letra y `~` quita lo especial a lo que sigue. */
        fun comodin(p: String, s: String): Boolean {
            var pi = 0
            var si = 0
            var estrella = -1
            var desde = 0
            while (si < s.length) {
                if (pi < p.length) {
                    val ch = p[pi]
                    if (ch == '*') { estrella = pi; desde = si; pi++; continue }
                    if (ch == '~' && pi + 1 < p.length) {
                        if (p[pi + 1] == s[si]) { pi += 2; si++; continue }
                    } else if (ch == '?' || ch == s[si]) { pi++; si++; continue }
                }
                if (estrella >= 0) { pi = estrella + 1; desde++; si = desde; continue }
                return false
            }
            while (pi < p.length && p[pi] == '*') pi++
            return pi == p.length
        }

        /** Si [v] cumple el criterio de `CONTAR.SI`: `">5"`, `"<>x"`, `"ab*"`, `7`… */
        fun cumple(v: Valor, criterio: Valor): Boolean {
            val vacia = v is Valor.Vacio || (v is Valor.Txt && v.s.isEmpty())
            when (criterio) {
                is Valor.Num -> {
                    val n = when (v) {
                        is Valor.Num -> v.n
                        is Valor.Txt -> CalculoFormato.numero(v.s)
                        else -> null
                    } ?: return false
                    return numeros(n, criterio.n) == 0
                }
                is Valor.Log -> return v is Valor.Log && v.b == criterio.b
                is Valor.Err -> return v is Valor.Err && v.e == criterio.e
                is Valor.Vacio -> return vacia
                is Valor.Txt -> {
                    val s = criterio.s
                    val op = listOf(">=", "<=", "<>", ">", "<", "=").firstOrNull { s.startsWith(it) } ?: ""
                    val resto = s.substring(op.length)
                    if (resto.isEmpty()) return if (op == "<>") !vacia else if (op == "" || op == "=") vacia else false
                    val n = CalculoFormato.numero(resto) ?: CalculoFormato.fecha(resto)
                    if (n != null) {
                        val vn = when (v) {
                            is Valor.Num -> v.n
                            is Valor.Txt -> if (op == "" || op == "=") CalculoFormato.numero(v.s) else null
                            else -> null
                        } ?: return op == "<>"
                        val c = numeros(vn, n)
                        return when (op) {
                            "", "=" -> c == 0
                            "<>" -> c != 0
                            ">" -> c > 0
                            "<" -> c < 0
                            ">=" -> c >= 0
                            else -> c <= 0
                        }
                    }
                    val logico = when (resto.uppercase()) {
                        "VERDADERO", "TRUE" -> true
                        "FALSO", "FALSE" -> false
                        else -> null
                    }
                    if (logico != null && (op == "" || op == "=" || op == "<>")) {
                        val igual = v is Valor.Log && v.b == logico
                        return if (op == "<>") !igual else igual
                    }
                    if (op == "" || op == "=" || op == "<>") {
                        val igual = v is Valor.Txt && comodin(resto.lowercase(), v.s.lowercase())
                        return if (op == "<>") !igual else igual
                    }
                    if (v !is Valor.Txt) return false
                    val c = signo(v.s.lowercase().compareTo(resto.lowercase()))
                    return when (op) {
                        ">" -> c > 0
                        "<" -> c < 0
                        ">=" -> c >= 0
                        else -> c <= 0
                    }
                }
            }
        }

        /** Los nombres en español, y los ingleses que llegan pegados, a un solo nombre. */
        val ALIAS: Map<String, String> = mapOf(
            "SUMA" to "SUM", "PROMEDIO" to "AVERAGE", "CONTAR" to "COUNT", "CONTARA" to "COUNTA",
            "CONTAR.BLANCO" to "COUNTBLANK", "PRODUCTO" to "PRODUCT", "MEDIANA" to "MEDIAN",
            "DESVEST" to "STDEV", "DESVEST.M" to "STDEV", "STDEV.S" to "STDEV",
            "SI" to "IF", "SI.CONJUNTO" to "IFS", "Y" to "AND", "O" to "OR", "NO" to "NOT",
            "SI.ERROR" to "IFERROR", "SI.ND" to "IFNA", "ESERROR" to "ISERROR", "ESBLANCO" to "ISBLANK",
            "ESNUMERO" to "ISNUMBER", "ESTEXTO" to "ISTEXT", "REDONDEAR" to "ROUND",
            "REDONDEAR.MAS" to "ROUNDUP", "REDONDEAR.MENOS" to "ROUNDDOWN", "TRUNCAR" to "TRUNC",
            "ENTERO" to "INT", "RAIZ" to "SQRT", "RAÍZ" to "SQRT", "POTENCIA" to "POWER", "RESIDUO" to "MOD",
            "SENO" to "SIN", "GRADOS" to "DEGREES", "RADIANES" to "RADIANS", "SIGNO" to "SIGN",
            "ALEATORIO" to "RAND", "ALEATORIO.ENTRE" to "RANDBETWEEN", "HOY" to "TODAY", "AHORA" to "NOW",
            "FECHA" to "DATE", "DIA" to "DAY", "DÍA" to "DAY", "MES" to "MONTH", "AÑO" to "YEAR",
            "CONCATENAR" to "CONCATENATE", "LARGO" to "LEN", "MAYUSC" to "UPPER", "MINUSC" to "LOWER",
            "ESPACIOS" to "TRIM", "IZQUIERDA" to "LEFT", "DERECHA" to "RIGHT", "EXTRAE" to "MID",
            "ENCONTRAR" to "FIND", "HALLAR" to "SEARCH", "SUSTITUIR" to "SUBSTITUTE", "REPETIR" to "REPT",
            "TEXTO" to "TEXT", "VALOR" to "VALUE", "BUSCARV" to "VLOOKUP", "BUSCARH" to "HLOOKUP",
            "INDICE" to "INDEX", "ÍNDICE" to "INDEX", "COINCIDIR" to "MATCH", "BUSCARX" to "XLOOKUP",
            "SUMAR.SI" to "SUMIF", "CONTAR.SI" to "COUNTIF", "PROMEDIO.SI" to "AVERAGEIF",
            "SUMAR.SI.CONJUNTO" to "SUMIFS", "CONTAR.SI.CONJUNTO" to "COUNTIFS",
            "PROMEDIO.SI.CONJUNTO" to "AVERAGEIFS", "SUMAPRODUCTO" to "SUMPRODUCT", "ELEGIR" to "CHOOSE",
            "FILA" to "ROW", "COLUMNA" to "COLUMN"
        )

        /**
         * **Las funciones que se ofrecen al escribir**, en español, con cómo se usan. Es lo que
         * enseña el botón «fx» de la aplicación.
         */
        val CATALOGO: List<Pair<String, String>> = listOf(
            "SUMA" to "SUMA(A1:A10)", "PROMEDIO" to "PROMEDIO(A1:A10)", "MIN" to "MIN(A1:A10)",
            "MAX" to "MAX(A1:A10)", "CONTAR" to "CONTAR(A1:A10)", "CONTARA" to "CONTARA(A1:A10)",
            "SI" to "SI(A1>0; \"sí\"; \"no\")", "SI.ERROR" to "SI.ERROR(A1/B1; 0)",
            "Y" to "Y(A1>0; B1>0)", "O" to "O(A1>0; B1>0)", "REDONDEAR" to "REDONDEAR(A1; 2)",
            "SUMAR.SI" to "SUMAR.SI(A1:A10; \">5\")", "CONTAR.SI" to "CONTAR.SI(A1:A10; \"x\")",
            "PROMEDIO.SI" to "PROMEDIO.SI(A1:A10; \">0\")",
            "SUMAR.SI.CONJUNTO" to "SUMAR.SI.CONJUNTO(C1:C10; A1:A10; \"x\")",
            "BUSCARV" to "BUSCARV(E1; A1:C10; 2; FALSO)", "BUSCARX" to "BUSCARX(E1; A1:A10; B1:B10)",
            "INDICE" to "INDICE(A1:C10; 2; 3)", "COINCIDIR" to "COINCIDIR(E1; A1:A10; 0)",
            "SUMAPRODUCTO" to "SUMAPRODUCTO(A1:A10; B1:B10)", "PRODUCTO" to "PRODUCTO(A1:A10)",
            "ABS" to "ABS(A1)", "RAIZ" to "RAIZ(A1)", "POTENCIA" to "POTENCIA(A1; 2)",
            "RESIDUO" to "RESIDUO(A1; 2)", "ENTERO" to "ENTERO(A1)", "MEDIANA" to "MEDIANA(A1:A10)",
            "DESVEST" to "DESVEST(A1:A10)", "CONCATENAR" to "CONCATENAR(A1; \" \"; B1)",
            "LARGO" to "LARGO(A1)", "MAYUSC" to "MAYUSC(A1)", "MINUSC" to "MINUSC(A1)",
            "ESPACIOS" to "ESPACIOS(A1)", "IZQUIERDA" to "IZQUIERDA(A1; 3)", "DERECHA" to "DERECHA(A1; 3)",
            "EXTRAE" to "EXTRAE(A1; 2; 3)", "SUSTITUIR" to "SUSTITUIR(A1; \"a\"; \"b\")",
            "TEXTO" to "TEXTO(A1; \"#,##0.00\")", "VALOR" to "VALOR(A1)", "HOY" to "HOY()",
            "AHORA" to "AHORA()", "FECHA" to "FECHA(2026; 9; 10)", "DIA" to "DIA(A1)", "MES" to "MES(A1)",
            "AÑO" to "AÑO(A1)", "ALEATORIO.ENTRE" to "ALEATORIO.ENTRE(1; 6)", "PI" to "PI()"
        )
    }
}
