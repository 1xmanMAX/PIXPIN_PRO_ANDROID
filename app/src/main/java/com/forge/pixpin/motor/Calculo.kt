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
 * ## Igual que el PC (13-oct-2026)
 *
 * El motor de fórmulas es **el del PC** (`crates/pixpin-proyecto/src/formula/`), función por
 * función y caso raro por caso raro, para que una hoja calcule lo mismo en los dos aparatos: la
 * tabla de pruebas del PC está entera en `FormulasComoElPcTest`. El contrato, con la semántica
 * de cada función, es `docs/investigacion/2026-10-11-formulas-android.md` del repositorio del PC.
 * Lo que solo tiene el móvil se queda: las columnas enteras (`A:C`), los errores escritos
 * (`=#N/A`), las fechas y VERDADERO/FALSO escritos a mano, y unas cuantas funciones más
 * (`DESVEST`, `LN`, `SUMAPRODUCTO`, `ELEGIR`, `FILA`…), que en el PC dan `#¿NOMBRE?`.
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
 * orden —así cada una encuentra hecha la anterior— y se vuelve a pedir. Ver [calentar]. (El PC
 * calcula la hoja entera por columnas, que tiene el mismo efecto.)
 *
 * ## Igual que la página web
 *
 * `VisorTabla.CALCULO` es este mismo archivo escrito en JavaScript, para que la tabla exportada
 * siga calculando en el navegador. Los nombres de las funciones de allí son los de aquí, y
 * `CalculoEnNodeTest` pasa las mismas fórmulas por las dos y compara lo que enseñan.
 */
class Calculadora {

    private val crudos = HashMap<Int, String>()
    private val valores = HashMap<Int, Valor>()
    private val arboles = HashMap<String, Nodo>()
    private val calculando = HashSet<Int>()
    /** Las celdas escritas de cada rango ya leído, con su valor (`rala` del PC). */
    private val rangos = HashMap<Rg, List<Leida>>()
    /** Los `CONTAR.SI`, `SUMAR.SI`… ya resueltos con los mismos rangos y criterios. */
    private val criterios = HashMap<String, Valor>()
    private var medidas = true
    private var nFilas = 0
    private var nCols = 0
    /** El instante de `HOY()` y `AHORA()`: uno por cálculo, como el PC («uno por pasada»). */
    private var instante: Double? = null

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

    private fun olvidarTodo() {
        valores.clear(); rangos.clear(); criterios.clear(); instante = null
    }

    fun poner(fila: Int, col: Int, texto: String) {
        val k = Celdas.clave(fila, col)
        if (texto.isEmpty()) {
            if (crudos.remove(k) != null) medidas = false
        } else {
            crudos[k] = texto
            if (medidas) { nFilas = maxOf(nFilas, fila + 1); nCols = maxOf(nCols, col + 1) }
        }
        olvidarTodo()
    }

    /** Cambia todo lo escrito de golpe. Las claves son las de [Celdas.clave]. */
    fun ponerTodo(celdas: Map<Int, String>) {
        crudos.clear()
        for ((k, v) in celdas) if (v.isNotEmpty()) crudos[k] = v
        medidas = false
        olvidarTodo()
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

    /**
     * Lo que enseña el PC en esa celda: como [texto], pero un número que sale de una fecha se ve
     * como número («General»), que es como lo pinta el PC. Para comparar los dos motores.
     */
    fun mostrarGeneral(fila: Int, col: Int): String {
        val raw = crudos[Celdas.clave(fila, col)] ?: return ""
        if (!esFormula(raw)) return texto(fila, col)
        return when (val v = valor(fila, col)) {
            is Valor.Num -> CalculoFormato.general(v.n)
            else -> mostrar(v)
        }
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
        try { celda(fila, col, 0) } catch (_: Hondo) { Valor.Err(ERROR_CICLO) }
    }

    /** Vuelve a calcular todo lo que se pida a partir de ahora (lo de `HOY()`, por ejemplo). */
    fun olvidar() = olvidarTodo()

    private class Hondo : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Un error que sube: se lanza desde donde se descubre y se recoge al acabar la función. */
    private class Fallo(val e: String) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Ver la cabecera: las fórmulas en orden, a pasadas, hasta que la cadena se resuelve. */
    private fun calentar() {
        // Por columnas y de arriba abajo, como el PC: cada una encuentra hecha la de encima.
        val orden = crudos.entries.filter { esFormula(it.value) }.map { it.key }
            .sortedWith(compareBy({ Celdas.colDe(it) }, { Celdas.filaDe(it) }))
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
        if (k in calculando) return Valor.Err(ERROR_CICLO)
        valores[k]?.let { return it }
        val raw = crudos[k] ?: return Valor.Vacio
        if (!esFormula(raw)) {
            val v = literal(raw)
            valores[k] = v
            return v
        }
        if (prof > PROFUNDIDAD) throw Hondo()
        calculando += k
        try {
            val arbol = arboles.getOrPut(raw) { CalculoLexico.arbol(raw.substring(1)) }
            var v = escalar(evaluar(arbol, prof + 1, fila, col), prof + 1)
            // Una fórmula que da una celda vacía da el texto vacío, como en el PC.
            if (v is Valor.Vacio) v = Valor.Txt("")
            if (v is Valor.Num && !v.n.isFinite()) v = Valor.Err(CalculoFormato.ERROR_NUM)
            // Un ciclo no se recuerda: depende de por dónde se entró (como el PC).
            if (!(v is Valor.Err && v.e == ERROR_CICLO)) valores[k] = v
            return v
        } finally {
            calculando -= k
        }
    }

    // ------------------------------------------------------------------ evaluar

    /** Un rectángulo de celdas con las esquinas en orden. */
    private data class Rg(val f1: Int, val c1: Int, val f2: Int, val c2: Int) {
        val nfilas: Long get() = (f2 - f1).toLong() + 1
        val ncols: Long get() = (c2 - c1).toLong() + 1

        /** El mismo sitio de arriba a la izquierda con otro tamaño. */
        fun conTamano(filas: Long, cols: Long) = Rg(
            f1, c1,
            (f1 + maxOf(filas, 1L) - 1).coerceAtMost(CalculoLexico.FILAS_EN_FORMULA - 1L).toInt(),
            (c1 + maxOf(cols, 1L) - 1).coerceAtMost(Celdas.MAX_COLS - 1L).toInt()
        )
    }

    /** Una celda escrita de un rango, con su valor. */
    private class Leida(val f: Int, val c: Int, val v: Valor)

    /**
     * Lo que da evaluar un trozo de fórmula: un valor suelto ([V]), el de **una celda citada**
     * ([C]: dentro de `SUMA` un texto escrito a mano se lee como número y el de una celda se
     * ignora, como en Excel) o un rango ([R]). Es `Ev` del PC.
     */
    private sealed class Ev {
        class V(val v: Valor) : Ev()
        class C(val v: Valor) : Ev()
        class R(val r: Rg) : Ev()
    }

    private fun evaluar(n: Nodo, prof: Int, fila: Int, col: Int): Ev = when (n) {
        is Nodo.Num -> Ev.V(Valor.Num(n.n))
        is Nodo.Txt -> Ev.V(Valor.Txt(n.s))
        is Nodo.Log -> Ev.V(Valor.Log(n.b))
        is Nodo.Err -> Ev.V(Valor.Err(n.e))
        is Nodo.Ref -> Ev.C(celda(n.fila, n.col, prof))
        is Nodo.Rango -> Ev.R(
            if (n.columnas) Rg(n.f1, n.c1, maxOf(n.f1, filas - 1), n.c2) else Rg(n.f1, n.c1, n.f2, n.c2)
        )
        is Nodo.Menos -> Ev.V(conFallo { Valor.Num(-numero(valorDe(n.x, prof, fila, col))) })
        is Nodo.PorCiento -> Ev.V(conFallo { Valor.Num(numero(valorDe(n.x, prof, fila, col)) / 100) })
        is Nodo.Op -> Ev.V(operar(n.op, valorDe(n.a, prof, fila, col), valorDe(n.b, prof, fila, col)))
        is Nodo.Funcion -> llamar(n, prof, fila, col)
    }

    private inline fun conFallo(cuenta: () -> Valor): Valor = try { cuenta() } catch (f: Fallo) { Valor.Err(f.e) }

    /** El valor de un trozo donde solo cabe un valor: un rango de varias celdas es `#¡VALOR!`. */
    private fun valorDe(n: Nodo, prof: Int, fila: Int, col: Int): Valor = escalar(evaluar(n, prof, fila, col), prof)

    private fun escalar(e: Ev, prof: Int): Valor = when (e) {
        is Ev.V -> e.v
        is Ev.C -> e.v
        is Ev.R -> if (e.r.nfilas == 1L && e.r.ncols == 1L) celda(e.r.f1, e.r.c1, prof) else Valor.Err(ERROR_VALOR)
    }

    private fun operar(op: String, a: Valor, b: Valor): Valor = conFallo {
        when (op) {
            "&" -> {
                val x = aTexto(a); val y = aTexto(b)
                Valor.Txt(x + y)
            }
            "=", "<>", "<", ">", "<=", ">=" -> {
                if (a is Valor.Err) throw Fallo(a.e)
                if (b is Valor.Err) throw Fallo(b.e)
                val c = comparar(a, b)
                Valor.Log(
                    when (op) {
                        "=" -> c == 0
                        "<>" -> c != 0
                        "<" -> c < 0
                        ">" -> c > 0
                        "<=" -> c <= 0
                        else -> c >= 0
                    }
                )
            }
            else -> {
                val x = numero(a); val y = numero(b)
                val fx = (a as? Valor.Num)?.fecha == true
                val fy = (b as? Valor.Num)?.fecha == true
                val r = when (op) {
                    "+" -> x + y
                    "-" -> x - y
                    "*" -> x * y
                    "/" -> if (y == 0.0) throw Fallo(ERROR_DIV0) else x / y
                    else -> potencia(x, y)
                }
                if (!r.isFinite()) throw Fallo(CalculoFormato.ERROR_NUM)
                // Una fecha más días sigue siendo una fecha (y una fecha menos otra, días).
                Valor.Num(r, (op == "+" && (fx || fy)) || (op == "-" && fx && !fy))
            }
        }
    }

    // ------------------------------------------------------------------ conversiones

    private fun ev(e: Ev, prof: Int): Valor = escalar(e, prof)
    private fun num(e: Ev, prof: Int): Double = numero(ev(e, prof))
    private fun txt(e: Ev, prof: Int): String = aTexto(ev(e, prof))
    private fun log(e: Ev, prof: Int): Boolean = aLogico(ev(e, prof))

    /** A entero, truncando hacia el cero; más de 9e15 es `#¡NUM!`. */
    private fun entero(e: Ev, prof: Int): Long {
        val n = num(e, prof)
        if (!n.isFinite() || Math.abs(n) > 9e15) throw Fallo(CalculoFormato.ERROR_NUM)
        return n.toLong()
    }

    /** Las celdas escritas de [rg] con su valor, por columnas y de arriba abajo. Un ciclo dentro sube. */
    private fun rala(rg: Rg, prof: Int): List<Leida> {
        rangos[rg]?.let { return it }
        val area = rg.nfilas * rg.ncols
        val claves: List<Int> = if (area <= crudos.size) {
            val l = ArrayList<Int>()
            for (c in rg.c1..rg.c2) for (f in rg.f1..rg.f2) {
                val k = Celdas.clave(f, c)
                if (crudos.containsKey(k)) l += k
            }
            l
        } else {
            crudos.keys.filter { val f = Celdas.filaDe(it); val c = Celdas.colDe(it); f in rg.f1..rg.f2 && c in rg.c1..rg.c2 }
                .sortedWith(compareBy({ Celdas.colDe(it) }, { Celdas.filaDe(it) }))
        }
        val salida = ArrayList<Leida>(claves.size)
        for (k in claves) {
            val v = celda(Celdas.filaDe(k), Celdas.colDe(k), prof)
            if (v is Valor.Err && v.e == ERROR_CICLO) throw Fallo(ERROR_CICLO)
            salida += Leida(Celdas.filaDe(k), Celdas.colDe(k), v)
        }
        rangos[rg] = salida
        return salida
    }

    /**
     * Los números que aportan unos argumentos (`SUMA`, `PROMEDIO`…). El texto y las celdas
     * vacías de un rango o de una celda citada no aportan nada; escrito en la fórmula, un texto que
     * sea un número sí (`SUMA("5";1)`) y uno que no, se calla. Un error dentro sube.
     */
    private fun numeros(args: List<Ev>, prof: Int): ArrayList<Double> {
        val l = ArrayList<Double>()
        for (a in args) when (a) {
            is Ev.V -> when (val v = a.v) {
                is Valor.Num -> l += v.n
                is Valor.Log -> l += if (v.b) 1.0 else 0.0
                is Valor.Txt -> CalculoFormato.numero(v.s)?.let { l += it }
                is Valor.Err -> throw Fallo(v.e)
                Valor.Vacio -> {}
            }
            is Ev.C -> when (val v = a.v) {
                is Valor.Num -> l += v.n
                is Valor.Err -> throw Fallo(v.e)
                else -> {}
            }
            is Ev.R -> for (x in rala(a.r, prof)) when (val v = x.v) {
                is Valor.Num -> l += v.n
                is Valor.Err -> throw Fallo(v.e)
                else -> {}
            }
        }
        return l
    }

    private fun ahora(): Double = instante ?: run {
        val t = reloj()
        val s = CalculoFormato.aSerie(t.year.toLong(), t.monthValue.toLong(), t.dayOfMonth.toLong()) +
            (t.hour * 3_600_000L + t.minute * 60_000L + t.second * 1000L + t.nano / 1_000_000) / 86_400_000.0
        instante = s
        s
    }

    // ------------------------------------------------------------------ funciones

    private fun llamar(fn: Nodo.Funcion, prof: Int, fila: Int, col: Int): Ev {
        val (canon, min, max) = CATALOGO_COMPLETO[fn.nombre] ?: return Ev.V(Valor.Err(ERROR_NOMBRE))
        val a = fn.args
        if (a.size < min || a.size > max) return Ev.V(Valor.Err(ERROR_VALOR))
        fun e(i: Int) = evaluar(a[i], prof, fila, col)
        try {
            when (canon) {
                "SI" -> {
                    if (!log(e(0), prof)) return if (a.size > 2) e(2) else Ev.V(Valor.Log(false))
                    return e(1)
                }
                "SI.ERROR", "SI.ND" -> {
                    val x = e(0)
                    val v = when (x) {
                        is Ev.R -> return x
                        is Ev.V -> x.v
                        is Ev.C -> x.v
                    }
                    // Un ciclo no se tapa: no es un dato malo, es la hoja mal escrita.
                    if (v is Valor.Err && v.e != ERROR_CICLO && (canon == "SI.ERROR" || v.e == ERROR_ND)) return e(1)
                    return x
                }
                "SI.CONJUNTO" -> {
                    if (a.size % 2 != 0) return Ev.V(Valor.Err(ERROR_VALOR))
                    var i = 0
                    while (i < a.size) {
                        if (log(e(i), prof)) return e(i + 1)
                        i += 2
                    }
                    return Ev.V(Valor.Err(ERROR_ND))
                }
                // FILA y COLUMNA miran la dirección citada, no su valor.
                "FILA", "COLUMNA" -> {
                    val r = if (a.isEmpty()) intArrayOf(fila, col) else when (val x = a[0]) {
                        is Nodo.Ref -> intArrayOf(x.fila, x.col)
                        is Nodo.Rango -> intArrayOf(x.f1, x.c1)
                        else -> return Ev.V(Valor.Err(ERROR_VALOR))
                    }
                    return Ev.V(Valor.Num(((if (canon == "FILA") r[0] else r[1]) + 1).toDouble()))
                }
                "ELEGIR" -> {
                    val i = entero(e(0), prof)
                    if (i < 1 || i >= a.size) return Ev.V(Valor.Err(ERROR_VALOR))
                    return e(i.toInt())
                }
            }
            val evs = a.indices.map { i ->
                // SUMAPRODUCTO admite una celda suelta como matriz de 1×1.
                val n = a[i]
                if (canon == "SUMAPRODUCTO" && n is Nodo.Ref) Ev.R(Rg(n.fila, n.col, n.fila, n.col)) else e(i)
            }
            // Los errores de los argumentos suben, salvo en las funciones cuyo oficio es mirarlos.
            if ((!canon.startsWith("ES") || canon == "ESPACIOS") && canon != "CONTARA" && canon != "CONTAR.BLANCO") {
                for (x in evs) {
                    val v = when (x) { is Ev.V -> x.v; is Ev.C -> x.v; else -> null }
                    if (v is Valor.Err) return Ev.V(v)
                }
            }
            return Ev.V(funcion(canon, evs, prof, fila, col))
        } catch (f: Fallo) {
            return Ev.V(Valor.Err(f.e))
        }
    }

    private fun funcion(canon: String, a: List<Ev>, prof: Int, fila: Int, col: Int): Valor = when (canon) {
        // ---- Lógicas
        "VERDADERO" -> Valor.Log(true)
        "FALSO" -> Valor.Log(false)
        "Y", "O" -> logica(canon == "Y", a, prof)
        "NO" -> Valor.Log(!log(a[0], prof))
        // ---- Las de siempre
        "SUMA" -> Valor.Num(numeros(a, prof).sum())
        "CONTAR" -> Valor.Num(numeros(a, prof).size.toDouble())
        "PROMEDIO" -> numeros(a, prof).let { if (it.isEmpty()) throw Fallo(ERROR_DIV0) else Valor.Num(it.sum() / it.size) }
        "MIN" -> Valor.Num(numeros(a, prof).let { l -> if (l.isEmpty()) 0.0 else l.reduce { x, y -> minOf(x, y) } })
        "MAX" -> Valor.Num(numeros(a, prof).let { l -> if (l.isEmpty()) 0.0 else l.reduce { x, y -> maxOf(x, y) } })
        "PRODUCTO" -> numeros(a, prof).let { l -> Valor.Num(if (l.isEmpty()) 0.0 else l.fold(1.0) { x, y -> x * y }) }
        "MEDIANA" -> numeros(a, prof).let { l ->
            if (l.isEmpty()) throw Fallo(CalculoFormato.ERROR_NUM)
            l.sort()
            val m = l.size / 2
            Valor.Num(if (l.size % 2 == 1) l[m] else (l[m - 1] + l[m]) / 2)
        }
        // ---- Matemáticas
        "REDONDEAR" -> redondear(a, prof, 0)
        "REDONDEAR.MAS" -> redondear(a, prof, 1)
        "REDONDEAR.MENOS" -> redondear(a, prof, -1)
        "ENTERO" -> Valor.Num(Math.floor(num(a[0], prof)))
        "ABS" -> Valor.Num(Math.abs(num(a[0], prof)))
        "RESIDUO" -> {
            val n = num(a[0], prof); val d = num(a[1], prof)
            if (d == 0.0) throw Fallo(ERROR_DIV0)
            Valor.Num(n - d * Math.floor(n / d))
        }
        "POTENCIA" -> Valor.Num(potencia(num(a[0], prof), num(a[1], prof)))
        "RAIZ" -> num(a[0], prof).let { if (it < 0) throw Fallo(CalculoFormato.ERROR_NUM) else Valor.Num(Math.sqrt(it)) }
        "PI" -> Valor.Num(Math.PI)
        "ALEATORIO" -> Valor.Num(azar())
        "ALEATORIO.ENTRE" -> {
            val x = Math.ceil(num(a[0], prof)); val y = Math.floor(num(a[1], prof))
            if (x > y || !x.isFinite() || !y.isFinite()) throw Fallo(CalculoFormato.ERROR_NUM)
            Valor.Num(minOf(x + Math.floor(azar() * (y - x + 1)), y))
        }
        "CONTARA" -> {
            var n = 0L
            for (x in a) when (x) {
                is Ev.V -> n++
                is Ev.C -> if (!esVacio(x.v)) n++
                is Ev.R -> n += rala(x.r, prof).count { !esVacio(it.v) }
            }
            Valor.Num(n.toDouble())
        }
        "CONTAR.BLANCO" -> when (val x = a[0]) {
            is Ev.R -> Valor.Num((x.r.nfilas * x.r.ncols - rala(x.r, prof).count { !esVacio(it.v) }).toDouble())
            is Ev.C -> Valor.Num(if (esVacio(x.v)) 1.0 else 0.0)
            is Ev.V -> Valor.Num(if (esVacio(x.v)) 1.0 else 0.0)
        }
        // ---- Con criterio
        "CONTAR.SI" -> criterioUna(a, null, Que.CONTAR, prof)
        "SUMAR.SI" -> criterioUna(a, a.getOrNull(2), Que.SUMAR, prof)
        "PROMEDIO.SI" -> criterioUna(a, a.getOrNull(2), Que.PROMEDIAR, prof)
        "CONTAR.SI.CONJUNTO" -> criterioVarias(a, null, Que.CONTAR, prof)
        "SUMAR.SI.CONJUNTO" -> criterioVarias(a.drop(1), a[0], Que.SUMAR, prof)
        "PROMEDIO.SI.CONJUNTO" -> criterioVarias(a.drop(1), a[0], Que.PROMEDIAR, prof)
        // ---- Búsqueda
        "BUSCARV" -> buscarVH(a, true, prof)
        "BUSCARH" -> buscarVH(a, false, prof)
        "INDICE" -> indice(a, prof)
        "COINCIDIR" -> coincidir(a, prof)
        "BUSCARX" -> buscarX(a, prof)
        // ---- Texto
        "CONCATENAR" -> Valor.Txt(a.joinToString("") { txt(it, prof) })
        "CONCAT" -> {
            val sb = StringBuilder()
            for (x in a) {
                if (x is Ev.R) rala(x.r, prof).sortedWith(compareBy({ it.f }, { it.c })).forEach { sb.append(aTexto(it.v)) }
                else sb.append(txt(x, prof))
            }
            Valor.Txt(sb.toString())
        }
        "UNIRCADENAS" -> unir(a, prof)
        "IZQUIERDA", "DERECHA" -> {
            val t = letras(txt(a[0], prof))
            val n = if (a.size > 1) entero(a[1], prof) else 1L
            if (n < 0) throw Fallo(ERROR_VALOR)
            val k = minOf(n, t.size.toLong()).toInt()
            Valor.Txt(if (canon == "IZQUIERDA") texto(t, 0, k) else texto(t, t.size - k, t.size))
        }
        "EXTRAE" -> {
            val t = letras(txt(a[0], prof))
            val ini = entero(a[1], prof); val n = entero(a[2], prof)
            if (ini < 1 || n < 0) throw Fallo(ERROR_VALOR)
            val desde = minOf(ini - 1, t.size.toLong()).toInt()
            Valor.Txt(texto(t, desde, minOf(desde.toLong() + n, t.size.toLong()).toInt()))
        }
        "LARGO" -> Valor.Num(letras(txt(a[0], prof)).size.toDouble())
        "MAYUSC" -> Valor.Txt(txt(a[0], prof).uppercase())
        "MINUSC" -> Valor.Txt(txt(a[0], prof).lowercase())
        "NOMPROPIO" -> Valor.Txt(nomPropio(txt(a[0], prof)))
        "ESPACIOS" -> Valor.Txt(txt(a[0], prof).split(' ').filter { it.isNotEmpty() }.joinToString(" "))
        "SUSTITUIR" -> sustituir(a, prof)
        "ENCONTRAR" -> buscarTexto(a, true, prof)
        "HALLAR" -> buscarTexto(a, false, prof)
        "TEXTO" -> {
            val v = ev(a[0], prof)
            val f = txt(a[1], prof)
            textoConFormato(v, f)
        }
        "VALOR" -> when (val v = ev(a[0], prof)) {
            is Valor.Num -> Valor.Num(v.n)
            is Valor.Log -> throw Fallo(ERROR_VALOR)
            is Valor.Err -> v
            Valor.Vacio -> Valor.Num(0.0)
            is Valor.Txt -> if (v.s.isBlank()) Valor.Num(0.0)
                else (CalculoFormato.numero(v.s) ?: CalculoFormato.serieDeTexto(v.s))?.let { Valor.Num(it) } ?: throw Fallo(ERROR_VALOR)
        }
        "REPETIR" -> {
            val n = entero(a[1], prof)
            if (n < 0 || n > 10000) throw Fallo(ERROR_VALOR)
            Valor.Txt(txt(a[0], prof).repeat(n.toInt()))
        }
        // ---- Fecha
        "HOY" -> Valor.Num(Math.floor(ahora()), true)
        "AHORA" -> Valor.Num(ahora(), true)
        "FECHA" -> Valor.Num(fecha(num(a[0], prof), num(a[1], prof), num(a[2], prof)), true)
        "AÑO", "MES", "DIA" -> {
            val p = CalculoFormato.desdeSerie(Math.floor(serie(a[0], prof)).toLong()) ?: throw Fallo(CalculoFormato.ERROR_NUM)
            Valor.Num(p[if (canon == "AÑO") 0 else if (canon == "MES") 1 else 2].toDouble())
        }
        "DIAS" -> Valor.Num(Math.floor(serie(a[0], prof)) - Math.floor(serie(a[1], prof)))
        "DIASEM" -> {
            val s = serie(a[0], prof)
            val tipo = if (a.size > 1) entero(a[1], prof) else 1L
            val d = Math.floorMod(Math.floor(s).toLong() - 1, 7L)
            Valor.Num(
                when (tipo) {
                    1L, 17L -> d + 1
                    2L, 11L -> (d + 6) % 7 + 1
                    3L -> (d + 6) % 7
                    else -> throw Fallo(CalculoFormato.ERROR_NUM)
                }.toDouble()
            )
        }
        // ---- Información
        "ESERROR", "ESND", "ESBLANCO", "ESNUMERO", "ESTEXTO" -> {
            val v = ev(a[0], prof)
            if (v is Valor.Err && v.e == ERROR_CICLO) throw Fallo(ERROR_CICLO)
            Valor.Log(
                when (canon) {
                    "ESERROR" -> v is Valor.Err
                    "ESND" -> v is Valor.Err && v.e == ERROR_ND
                    "ESBLANCO" -> esVacio(v)
                    "ESNUMERO" -> v is Valor.Num
                    else -> v is Valor.Txt && v.s.isNotEmpty()
                }
            )
        }
        // ---- Solo del móvil (en el PC, `#¿NOMBRE?`)
        "TRUNCAR" -> Valor.Num(CalculoFormato.redondear(num(a[0], prof), if (a.size > 1) entero(a[1], prof).coerceIn(-400, 400).toInt() else 0, -1))
        "DESVEST" -> numeros(a, prof).let { l ->
            if (l.size < 2) throw Fallo(ERROR_DIV0)
            val media = l.sum() / l.size
            Valor.Num(Math.sqrt(l.sumOf { (it - media) * (it - media) } / (l.size - 1)))
        }
        "EXP" -> Valor.Num(Math.exp(num(a[0], prof)))
        "LN" -> num(a[0], prof).let { if (it <= 0) throw Fallo(CalculoFormato.ERROR_NUM) else Valor.Num(Math.log(it)) }
        "LOG10" -> num(a[0], prof).let { if (it <= 0) throw Fallo(CalculoFormato.ERROR_NUM) else Valor.Num(Math.log10(it)) }
        "LOG" -> {
            val x = num(a[0], prof); val b = if (a.size > 1) num(a[1], prof) else 10.0
            if (x <= 0 || b <= 0) throw Fallo(CalculoFormato.ERROR_NUM)
            if (b == 1.0) throw Fallo(ERROR_DIV0)
            Valor.Num(Math.log(x) / Math.log(b))
        }
        "SENO" -> Valor.Num(Math.sin(num(a[0], prof)))
        "COS" -> Valor.Num(Math.cos(num(a[0], prof)))
        "TAN" -> Valor.Num(Math.tan(num(a[0], prof)))
        "GRADOS" -> Valor.Num(num(a[0], prof) * 180 / Math.PI)
        "RADIANES" -> Valor.Num(num(a[0], prof) * Math.PI / 180)
        "SIGNO" -> num(a[0], prof).let { Valor.Num(if (it > 0) 1.0 else if (it < 0) -1.0 else 0.0) }
        "SUMAPRODUCTO" -> {
            val rs = a.map { (it as? Ev.R)?.r ?: throw Fallo(ERROR_VALOR) }
            val base = rs[0]
            for (r in rs) if (r.nfilas != base.nfilas || r.ncols != base.ncols) throw Fallo(ERROR_VALOR)
            val leidas = rs.map { r -> rala(r, prof).associateBy { Celdas.clave(it.f - r.f1, it.c - r.c1) } }
            var s = 0.0
            for ((k, x) in leidas[0]) {
                var p = 1.0
                for ((i, l) in leidas.withIndex()) {
                    val v = if (i == 0) x.v else l[k]?.v
                    if (v is Valor.Err) throw Fallo(v.e)
                    p *= if (v is Valor.Num) v.n else 0.0
                }
                s += p
            }
            // Un error en las otras matrices, aunque la primera tenga hueco ahí, también sube.
            for (l in leidas.drop(1)) for (x in l.values) if (x.v is Valor.Err) throw Fallo((x.v as Valor.Err).e)
            Valor.Num(s)
        }
        else -> throw Fallo(ERROR_NOMBRE)
    }

    /** Y / O, como el PC (`mates::logica`). */
    private fun logica(y: Boolean, a: List<Ev>, prof: Int): Valor {
        var hay = false
        var r = y
        for (x in a) {
            val lista: List<Valor> = when (x) {
                is Ev.R -> rala(x.r, prof).map { it.v }
                is Ev.C -> listOf(x.v)
                is Ev.V -> listOf(x.v)
            }
            for (v in lista) {
                val b = when (v) {
                    is Valor.Err -> throw Fallo(v.e)
                    is Valor.Num -> v.n != 0.0
                    is Valor.Log -> v.b
                    // Dentro de un rango o de una celda citada, el texto se ignora.
                    is Valor.Txt, Valor.Vacio -> if (x !is Ev.V) continue else aLogico(v)
                }
                hay = true
                r = if (y) r && b else r || b
            }
        }
        if (!hay) throw Fallo(ERROR_VALOR)
        return Valor.Log(r)
    }

    private fun redondear(a: List<Ev>, prof: Int, modo: Int): Valor {
        val n = num(a[0], prof)
        val d = entero(a[1], prof).coerceIn(-400, 400).toInt()
        val r = CalculoFormato.redondear(n, d, modo)
        if (!r.isFinite()) throw Fallo(CalculoFormato.ERROR_NUM)
        return Valor.Num(r)
    }

    // ---- Criterios (`criterio.rs`)

    private enum class Que { CONTAR, SUMAR, PROMEDIAR }

    private fun rangoDe(e: Ev): Rg = (e as? Ev.R)?.r ?: throw Fallo(ERROR_VALOR)

    private fun criterioUna(a: List<Ev>, suma: Ev?, que: Que, prof: Int): Valor {
        val crit = ev(a[1], prof)
        if (crit is Valor.Err) throw Fallo(crit.e)
        val rg = rangoDe(a[0])
        val s = suma?.let { rangoDe(it) }
        val clave = "$que|$rg|$s|${claveDe(crit)}"
        return resolver(listOf(rg to Criterio.de(crit)), s, que, clave, prof)
    }

    private fun criterioVarias(a: List<Ev>, suma: Ev?, que: Que, prof: Int): Valor {
        if (a.size % 2 != 0) throw Fallo(ERROR_VALOR)
        val pares = ArrayList<Pair<Rg, Criterio>>()
        val clave = StringBuilder(que.name)
        var i = 0
        while (i < a.size) {
            val crit = ev(a[i + 1], prof)
            if (crit is Valor.Err) throw Fallo(crit.e)
            val rg = rangoDe(a[i])
            clave.append('|').append(rg).append('|').append(claveDe(crit))
            pares += rg to Criterio.de(crit)
            i += 2
        }
        val s = suma?.let {
            val r = rangoDe(it)
            if (r.nfilas != pares[0].first.nfilas || r.ncols != pares[0].first.ncols) throw Fallo(ERROR_VALOR)
            r
        }
        clave.append('|').append(s)
        return resolver(pares, s, que, clave.toString(), prof)
    }

    private fun claveDe(v: Valor): String = when (v) {
        is Valor.Num -> "n" + v.n
        is Valor.Txt -> "t" + v.s
        is Valor.Log -> "b" + v.b
        is Valor.Err -> "e" + v.e
        Valor.Vacio -> "t"
    }

    private fun resolver(pares: List<Pair<Rg, Criterio>>, suma: Rg?, que: Que, clave: String, prof: Int): Valor {
        criterios[clave]?.let { return it }
        val filasR = pares[0].first.nfilas
        val colsR = pares[0].first.ncols
        for ((rg, _) in pares) if (rg.nfilas != filasR || rg.ncols != colsR) throw Fallo(ERROR_VALOR)
        val s = suma?.conTamano(filasR, colsR)
        // Solo hasta lo escrito de toda la tabla: más allá todo está vacío y se cuenta de golpe.
        val extF = filas.toLong(); val extC = cols.toLong()
        fun recortado(rg: Rg): Pair<Long, Long> =
            maxOf(0L, extF - rg.f1).coerceAtMost(filasR) to maxOf(0L, extC - rg.c1).coerceAtMost(colsR)
        var cajaF = 0L; var cajaC = 0L
        for (rg in pares.map { it.first } + listOfNotNull(s)) {
            val (f, k) = recortado(rg)
            cajaF = maxOf(cajaF, f); cajaC = maxOf(cajaC, k)
        }
        val leidos = pares.map { rala(it.first, prof) }
        val leidoSuma = s?.let { rala(it, prof) }
        fun cuadricula(l: List<Leida>, rg: Rg): Array<Valor> {
            val c = Array<Valor>((cajaF * cajaC).toInt()) { Valor.Vacio }
            for (x in l) {
                val i = (x.f - rg.f1).toLong(); val j = (x.c - rg.c1).toLong()
                if (i < cajaF && j < cajaC) c[(i * cajaC + j).toInt()] = x.v
            }
            return c
        }
        val datos = pares.indices.map { cuadricula(leidos[it], pares[it].first) }
        val datosSuma = if (s != null && leidoSuma != null) cuadricula(leidoSuma, s) else null
        var cuenta = 0L
        var total = 0.0
        var conNumero = 0L
        for (k in 0 until (cajaF * cajaC).toInt()) {
            if (!pares.indices.all { pares[it].second.cumple(datos[it][k]) }) continue
            cuenta++
            if (que == Que.CONTAR) continue
            when (val v = (datosSuma ?: datos[0])[k]) {
                is Valor.Num -> { total += v.n; conNumero++ }
                is Valor.Err -> throw Fallo(v.e)
                else -> {}
            }
        }
        if (que == Que.CONTAR) {
            val fuera = filasR * colsR - cajaF * cajaC
            if (fuera > 0 && pares.all { it.second.cumple(Valor.Txt("")) }) cuenta += fuera
        }
        val r = when (que) {
            Que.CONTAR -> Valor.Num(cuenta.toDouble())
            Que.SUMAR -> Valor.Num(total)
            Que.PROMEDIAR -> if (conNumero == 0L) throw Fallo(ERROR_DIV0) else Valor.Num(total / conNumero)
        }
        criterios[clave] = r
        return r
    }

    // ---- Búsqueda (`busqueda.rs`)

    /** Una matriz donde buscar: un rango o un valor suelto (que cuenta como una celda). */
    private inner class Matriz(val rg: Rg?, val una: Valor?) {
        val dim: Pair<Long, Long> get() = if (rg != null) rg.nfilas to rg.ncols else 1L to 1L

        fun en(f: Long, c: Long, prof: Int): Valor =
            if (rg != null) celda((rg.f1 + f).toInt(), (rg.c1 + c).toInt(), prof) else una!!

        /** Las celdas escritas de la primera columna (o fila) con su posición. */
        fun linea(vertical: Boolean, prof: Int): List<Pair<Long, Valor>> {
            if (rg == null) return listOf(0L to una!!)
            val sub = if (vertical) Rg(rg.f1, rg.c1, rg.f2, rg.c1) else Rg(rg.f1, rg.c1, rg.f1, rg.c2)
            return rala(sub, prof).map { (if (vertical) (it.f - rg.f1).toLong() else (it.c - rg.c1).toLong()) to it.v }
        }
    }

    private fun matriz(e: Ev): Matriz = when (e) {
        is Ev.R -> Matriz(e.r, null)
        is Ev.C -> Matriz(null, e.v)
        is Ev.V -> Matriz(null, e.v)
    }

    private fun buscarVH(a: List<Ev>, vertical: Boolean, prof: Int): Valor {
        val buscado = vacioComoTexto(ev(a[0], prof))
        val m = matriz(a[1])
        val n = entero(a[2], prof)
        val aprox = if (a.size > 3) log(a[3], prof) else true
        val (filasM, colsM) = m.dim
        if (n < 1) throw Fallo(ERROR_VALOR)
        if (n > (if (vertical) colsM else filasM)) throw Fallo(CalculoLexico.ERROR_REF)
        val celdas = m.linea(vertical, prof)
        val pos = if (aprox) ultimoMenorOIgual(buscado, celdas)
            else celdas.firstOrNull { esIgual(buscado, it.second, true) }?.first
        pos ?: throw Fallo(ERROR_ND)
        return if (vertical) m.en(pos, n - 1, prof) else m.en(n - 1, pos, prof)
    }

    private fun ultimoMenorOIgual(buscado: Valor, celdas: List<Pair<Long, Valor>>): Long? {
        var mejor: Long? = null
        for ((p, v) in celdas) {
            val o = ordenDelMismoTipo(v, buscado) ?: continue
            if (o > 0) break
            mejor = p
        }
        return mejor
    }

    private fun indice(a: List<Ev>, prof: Int): Valor {
        val m = matriz(a[0])
        val (filasM, colsM) = m.dim
        val x = entero(a[1], prof)
        val (f, k) = when {
            a.size > 2 -> x to entero(a[2], prof)
            colsM == 1L -> x to 1L
            filasM == 1L -> 1L to x
            else -> throw Fallo(ERROR_VALOR)
        }
        if (f <= 0 || k <= 0) throw Fallo(ERROR_VALOR)
        if (f > filasM || k > colsM) throw Fallo(CalculoLexico.ERROR_REF)
        return m.en(f - 1, k - 1, prof)
    }

    private fun lineaUnica(e: Ev, prof: Int): Pair<Matriz, List<Pair<Long, Valor>>> {
        val m = matriz(e)
        val (f, c) = m.dim
        if (f != 1L && c != 1L) throw Fallo(ERROR_ND)
        return m to m.linea(c == 1L, prof)
    }

    private fun coincidir(a: List<Ev>, prof: Int): Valor {
        val buscado = vacioComoTexto(ev(a[0], prof))
        val (_, celdas) = lineaUnica(a[1], prof)
        val tipo = if (a.size > 2) num(a[2], prof) else 1.0
        val pos = when {
            tipo == 0.0 -> celdas.firstOrNull { esIgual(buscado, it.second, true) }?.first
            tipo > 0 -> ultimoMenorOIgual(buscado, celdas)
            else -> {
                var mejor: Long? = null
                for ((p, v) in celdas) {
                    val o = ordenDelMismoTipo(v, buscado) ?: continue
                    if (o < 0) break
                    mejor = p
                }
                mejor
            }
        } ?: throw Fallo(ERROR_ND)
        return Valor.Num(pos + 1.0)
    }

    private fun buscarX(a: List<Ev>, prof: Int): Valor {
        val buscado = vacioComoTexto(ev(a[0], prof))
        val (donde, celdas0) = lineaUnica(a[1], prof)
        val devolver = matriz(a[2])
        val (df, dc) = devolver.dim
        if (df != 1L && dc != 1L) throw Fallo(ERROR_VALOR)
        val (fd, cd) = donde.dim
        if (maxOf(fd, cd) != maxOf(df, dc)) throw Fallo(ERROR_VALOR)
        val modo = if (a.size > 4) entero(a[4], prof) else 0L
        val sentido = if (a.size > 5) entero(a[5], prof) else 1L
        if (modo !in -1L..2L || sentido !in listOf(-2L, -1L, 1L, 2L)) throw Fallo(ERROR_VALOR)
        val celdas = if (sentido < 0) celdas0.asReversed() else celdas0
        var pos = celdas.firstOrNull { esIgual(buscado, it.second, modo == 2L) }?.first
        if (pos == null && (modo == -1L || modo == 1L)) {
            var mejor: Pair<Long, Valor>? = null
            for ((p, v) in celdas) {
                val o = ordenDelMismoTipo(v, buscado) ?: continue
                if (if (modo == -1L) o >= 0 else o <= 0) continue
                val mejora = mejor == null || comparar(v, mejor.second).let { if (modo == -1L) it > 0 else it < 0 }
                if (mejora) mejor = p to v
            }
            pos = mejor?.first
        }
        if (pos != null) return if (df == 1L) devolver.en(0, pos, prof) else devolver.en(pos, 0, prof)
        return if (a.size > 3) ev(a[3], prof) else throw Fallo(ERROR_ND)
    }

    // ---- Texto (`texto.rs`)

    private fun unir(a: List<Ev>, prof: Int): Valor {
        val sep = txt(a[0], prof)
        val ignorar = log(a[1], prof)
        val trozos = ArrayList<String>()
        for (x in a.drop(2)) {
            if (x is Ev.R) {
                val mapa = rala(x.r, prof).associate { Celdas.clave(it.f, it.c) to it.v }
                val f2 = minOf(x.r.f2, filas - 1)
                val c2 = minOf(x.r.c2, cols - 1)
                if (x.r.f1 <= f2 && x.r.c1 <= c2) for (f in x.r.f1..f2) for (c in x.r.c1..c2) {
                    trozos += aTexto(mapa[Celdas.clave(f, c)] ?: Valor.Vacio)
                }
            } else trozos += txt(x, prof)
        }
        return Valor.Txt((if (ignorar) trozos.filter { it.isNotEmpty() } else trozos).joinToString(sep))
    }

    private fun sustituir(a: List<Ev>, prof: Int): Valor {
        val t = txt(a[0], prof)
        val viejo = txt(a[1], prof)
        val nuevo = txt(a[2], prof)
        val cual = if (a.size > 3) entero(a[3], prof).also { if (it < 1) throw Fallo(ERROR_VALOR) } else null
        if (viejo.isEmpty()) return Valor.Txt(t)
        val sb = StringBuilder()
        var desde = 0
        var visto = 0L
        while (true) {
            val i = t.indexOf(viejo, desde)
            if (i < 0) break
            visto++
            sb.append(t, desde, i).append(if (cual == null || cual == visto) nuevo else viejo)
            desde = i + viejo.length
        }
        sb.append(t, desde, t.length)
        return Valor.Txt(sb.toString())
    }

    private fun buscarTexto(a: List<Ev>, exacto: Boolean, prof: Int): Valor {
        val quiero = txt(a[0], prof)
        val en = txt(a[1], prof)
        val desde = if (a.size > 2) entero(a[2], prof) else 1L
        val e0 = letras(en)
        if (desde < 1 || desde > maxOf(e0.size, 1)) throw Fallo(ERROR_VALOR)
        val d = (desde - 1).toInt()
        val q = if (exacto) letras(quiero) else letras(quiero.lowercase())
        val e = if (exacto) e0 else letras(en.lowercase())
        if (q.isEmpty()) return Valor.Num(d + 1.0)
        for (i in d until e.size) {
            val casa = if (exacto) (i + q.size <= e.size && (q.indices).all { e[i + it] == q[it] })
                else comodin(q, 0, e, i, false)
            if (casa) return Valor.Num(i + 1.0)
        }
        throw Fallo(ERROR_VALOR)
    }

    private fun textoConFormato(v: Valor, formato: String): Valor {
        val n = when (v) {
            is Valor.Err -> throw Fallo(v.e)
            is Valor.Num -> v.n
            is Valor.Log -> return Valor.Txt(if (v.b) "VERDADERO" else "FALSO")
            Valor.Vacio -> return Valor.Txt("")
            is Valor.Txt -> (if (v.s.isNotEmpty()) CalculoFormato.numero(v.s) else null)
                ?: CalculoFormato.serieDeTexto(v.s) ?: return Valor.Txt(v.s)
        }
        val (s, err) = CalculoFormato.darFormato(n, formato)
        if (err != null) throw Fallo(err)
        return Valor.Txt(s!!)
    }

    // ---- Fecha (`fecha.rs`)

    private fun serie(e: Ev, prof: Int): Double {
        val s = when (val v = ev(e, prof)) {
            is Valor.Num -> v.n
            is Valor.Log -> if (v.b) 1.0 else 0.0
            Valor.Vacio -> 0.0
            is Valor.Txt -> if (v.s.isEmpty()) 0.0 else CalculoFormato.serieDeTexto(v.s) ?: throw Fallo(ERROR_VALOR)
            is Valor.Err -> throw Fallo(v.e)
        }
        if (s < 0.0 || !s.isFinite() || s > 2_958_465.0) throw Fallo(CalculoFormato.ERROR_NUM)
        return s
    }

    private fun fecha(a0: Double, m0: Double, d0: Double): Double {
        var a = truncar(a0); val m = truncar(m0); val d = truncar(d0)
        if (!(a >= 0.0 && a < 10000.0)) throw Fallo(CalculoFormato.ERROR_NUM)
        if (a < 1900.0) a += 1900.0
        if (Math.abs(m) > 1e6 || Math.abs(d) > 1e7) throw Fallo(CalculoFormato.ERROR_NUM)
        val total = a.toLong() * 12 + (m.toLong() - 1)
        val anio = Math.floorDiv(total, 12L)
        val mes = Math.floorMod(total, 12L) + 1
        val dl = d.toLong()
        // El 29-feb-1900 que no existió es el 60; lo demás, el serial del día (que se desborda solo).
        val s = if (anio == 1900L && mes == 2L && dl == 29L) 60L else CalculoFormato.aSerie(anio, mes, dl)
        if (s !in 0L..2_958_465L) throw Fallo(CalculoFormato.ERROR_NUM)
        return s.toDouble()
    }

    private fun truncar(x: Double) = if (x < 0) Math.ceil(x) else Math.floor(x)

    companion object {
        const val PROFUNDIDAD = 400
        const val ERROR_DIV0 = "#¡DIV/0!"
        const val ERROR_VALOR = "#¡VALOR!"
        const val ERROR_NOMBRE = "#¿NOMBRE?"
        /** «No disponible»: como lo escribe el PC (antes aquí era `#N/D`). */
        const val ERROR_ND = "#N/A"
        /** Una fórmula que depende de sí misma: como lo escribe el PC (antes aquí era `#¡CIRC!`). */
        const val ERROR_CICLO = "#¡CICLO!"

        fun esFormula(raw: String) = raw.length > 1 && raw[0] == '='

        /**
         * Lo escrito a mano, sin `=`: número, fecha, lógico o texto. Como el PC (texto recortado;
         * solo espacios es vacío) más lo del móvil: las fechas (`11/10/2026`) y VERDADERO/FALSO
         * escritos, que el PC lee como texto.
         */
        fun literal(raw: String): Valor {
            if (raw.startsWith("'")) return Valor.Txt(raw.substring(1))
            val t = raw.trim()
            if (t.isEmpty()) return Valor.Txt("")
            CalculoFormato.numero(t)?.let { return Valor.Num(it) }
            CalculoFormato.fecha(t)?.let { return Valor.Num(it, true) }
            return when (t.uppercase()) {
                "VERDADERO", "TRUE" -> Valor.Log(true)
                "FALSO", "FALSE" -> Valor.Log(false)
                else -> Valor.Txt(t)
            }
        }

        fun mostrar(v: Valor): String = when (v) {
            is Valor.Num -> if (v.fecha) CalculoFormato.textoDeFecha(v.n) else CalculoFormato.general(v.n)
            is Valor.Txt -> v.s
            is Valor.Log -> if (v.b) "VERDADERO" else "FALSO"
            is Valor.Err -> v.e
            is Valor.Vacio -> ""
        }

        /** Una celda sin nada o el texto vacío, que aquí es lo mismo (`es_vacio` del PC). */
        private fun esVacio(v: Valor) = v is Valor.Vacio || (v is Valor.Txt && v.s.isEmpty())

        private fun vacioComoTexto(v: Valor): Valor = if (v is Valor.Vacio) Valor.Txt("") else v

        // ---- Conversiones (`motor.rs`): un error se lanza con [Fallo]

        private fun numero(v: Valor): Double = when (v) {
            is Valor.Num -> v.n
            is Valor.Log -> if (v.b) 1.0 else 0.0
            Valor.Vacio -> 0.0
            is Valor.Txt -> if (v.s.isEmpty()) 0.0 else CalculoFormato.numero(v.s) ?: throw Fallo(ERROR_VALOR)
            is Valor.Err -> throw Fallo(v.e)
        }

        private fun aTexto(v: Valor): String = when (v) {
            is Valor.Num -> CalculoFormato.general(v.n)
            is Valor.Txt -> v.s
            is Valor.Log -> if (v.b) "VERDADERO" else "FALSO"
            Valor.Vacio -> ""
            is Valor.Err -> throw Fallo(v.e)
        }

        private fun aLogico(v: Valor): Boolean = when (v) {
            is Valor.Num -> v.n != 0.0
            is Valor.Log -> v.b
            Valor.Vacio -> false
            is Valor.Txt -> when (v.s.trim().uppercase()) {
                "" -> false
                "VERDADERO", "TRUE" -> true
                "FALSO", "FALSE" -> false
                else -> throw Fallo(ERROR_VALOR)
            }
            is Valor.Err -> throw Fallo(v.e)
        }

        /** `x^y` con los errores de Excel: `0^0` y la raíz de un negativo son `#¡NUM!`, `0^-1` `#¡DIV/0!`. */
        private fun potencia(x: Double, y: Double): Double {
            if (x == 0.0 && y == 0.0) throw Fallo(CalculoFormato.ERROR_NUM)
            if (x == 0.0 && y < 0.0) throw Fallo(ERROR_DIV0)
            if (x < 0.0 && y != Math.floor(y)) throw Fallo(CalculoFormato.ERROR_NUM)
            val r = Math.pow(x, y)
            if (!r.isFinite()) throw Fallo(CalculoFormato.ERROR_NUM)
            return r
        }

        /** Las letras de un texto (puntos de código, como los `char` de Rust): la `ñ` y un emoji son una. */
        private fun letras(s: String): IntArray = s.codePoints().toArray()
        private fun texto(l: IntArray, desde: Int, hasta: Int): String = String(l, desde, maxOf(0, hasta - desde))

        private fun nomPropio(t: String): String {
            val sb = StringBuilder()
            var enPalabra = false
            for (cp in t.codePoints()) {
                if (Character.isLetter(cp)) {
                    sb.append(if (enPalabra) String(Character.toChars(cp)).lowercase() else String(Character.toChars(cp)).uppercase())
                    enPalabra = true
                } else {
                    sb.appendCodePoint(cp)
                    enPalabra = false
                }
            }
            return sb.toString()
        }

        private fun rango(v: Valor): Int = when (v) {
            is Valor.Num -> 0
            is Valor.Txt, Valor.Vacio -> 1
            is Valor.Log -> 2
            is Valor.Err -> 3
        }

        private fun signo(x: Int) = if (x < 0) -1 else if (x > 0) 1 else 0

        /**
         * Compara dos valores como Excel (`ordenar` del PC): números < textos < lógicos; el texto
         * sin mirar mayúsculas; dos números que solo se separan en la cifra quince, iguales
         * (`0,1 + 0,2 = 0,3` es VERDADERO). Una celda vacía se parece a lo que tenga delante.
         */
        fun comparar(a: Valor, b: Valor): Int {
            val va = esVacio(a); val vb = esVacio(b)
            if (va && vb) return 0
            val x = if (va) vacioComo(b) else a
            val y = if (vb) vacioComo(a) else b
            return when {
                x is Valor.Num && y is Valor.Num -> numeros(x.n, y.n)
                x is Valor.Txt && y is Valor.Txt -> signo(x.s.lowercase().compareTo(y.s.lowercase()))
                x is Valor.Log && y is Valor.Log -> if (x.b == y.b) 0 else if (!x.b) -1 else 1
                else -> signo(rango(x) - rango(y))
            }
        }

        private fun vacioComo(o: Valor): Valor = when (o) {
            is Valor.Num -> Valor.Num(0.0)
            is Valor.Log -> Valor.Log(false)
            else -> Valor.Txt("")
        }

        /** El orden de dos valores del mismo tipo (número, texto o lógico), o null si no lo son. */
        private fun ordenDelMismoTipo(a: Valor, b: Valor): Int? {
            val ta = if (a is Valor.Vacio) Valor.Txt("") else a
            val tb = if (b is Valor.Vacio) Valor.Txt("") else b
            val mismo = (ta is Valor.Num && tb is Valor.Num) || (ta is Valor.Txt && tb is Valor.Txt) || (ta is Valor.Log && tb is Valor.Log)
            return if (mismo) comparar(ta, tb) else null
        }

        /** Igualdad al buscar: un número nunca es igual a un texto; el texto, sin mayúsculas y quizá con comodines. */
        private fun esIgual(buscado: Valor, celda: Valor, comodines: Boolean): Boolean {
            val c = if (celda is Valor.Vacio) Valor.Txt("") else celda
            return when {
                buscado is Valor.Num && c is Valor.Num -> mismoNumero(buscado.n, c.n)
                buscado is Valor.Log && c is Valor.Log -> buscado.b == c.b
                buscado is Valor.Txt && c is Valor.Txt ->
                    if (comodines && tieneComodines(buscado.s)) comodin(letras(buscado.s.lowercase()), 0, letras(c.s.lowercase()), 0, true)
                    else buscado.s.lowercase() == c.s.lowercase()
                else -> false
            }
        }

        fun mismoNumero(a: Double, b: Double) = a == b || Math.abs(a - b) <= 1e-14 * maxOf(Math.abs(a), Math.abs(b))

        fun numeros(a: Double, b: Double): Int = if (mismoNumero(a, b)) 0 else if (a < b) -1 else 1

        private fun tieneComodines(t: String) = t.any { it == '*' || it == '?' || it == '~' }

        /**
         * `*` es cualquier cosa, `?` una letra y `~` quita lo especial a lo que sigue. Con [exacto]
         * el patrón tiene que llegar al final del texto; sin él, basta con que case desde el
         * principio (`HALLAR`). Es `comodin` del PC.
         */
        private fun comodin(p: IntArray, pi: Int, t: IntArray, ti: Int, exacto: Boolean): Boolean {
            if (pi >= p.size) return !exacto || ti >= t.size
            val c = p[pi]
            return when {
                c == '*'.code -> (ti..t.size).any { comodin(p, pi + 1, t, it, exacto) }
                c == '?'.code -> ti < t.size && comodin(p, pi + 1, t, ti + 1, exacto)
                c == '~'.code && pi + 1 < p.size && p[pi + 1].let { it == '*'.code || it == '?'.code || it == '~'.code } ->
                    ti < t.size && t[ti] == p[pi + 1] && comodin(p, pi + 2, t, ti + 1, exacto)
                else -> ti < t.size && t[ti] == c && comodin(p, pi + 1, t, ti + 1, exacto)
            }
        }

        /** Para quien lo usaba con cadenas: [p] patrón y [s] texto, ya en minúsculas si toca. */
        fun comodin(p: String, s: String): Boolean = comodin(letras(p), 0, letras(s), 0, true)

        /** **Un criterio** de `CONTAR.SI` y familia: `">5"`, `"<>x"`, `"ab*"`, `7`… (`criterio.rs`). */
        private class Criterio(val op: String, val tipo: Char, val n: Double = 0.0, val b: Boolean = false, val t: String = "") {
            companion object {
                fun de(v: Valor): Criterio = when (v) {
                    is Valor.Num -> Criterio("=", 'n', n = v.n)
                    is Valor.Log -> Criterio("=", 'b', b = v.b)
                    is Valor.Err -> Criterio("=", 'v')
                    Valor.Vacio -> Criterio("=", 'v')
                    is Valor.Txt -> {
                        val op = listOf("<>", "<=", ">=", "=", "<", ">").firstOrNull { v.s.startsWith(it) } ?: ""
                        val resto = v.s.substring(op.length)
                        val o = op.ifEmpty { "=" }
                        when {
                            resto.isEmpty() -> Criterio(o, 'v')
                            CalculoFormato.numero(resto) != null -> Criterio(o, 'n', n = CalculoFormato.numero(resto)!!)
                            resto.uppercase() == "VERDADERO" || resto.uppercase() == "TRUE" -> Criterio(o, 'b', b = true)
                            resto.uppercase() == "FALSO" || resto.uppercase() == "FALSE" -> Criterio(o, 'b', b = false)
                            else -> Criterio(o, 't', t = resto.lowercase())
                        }
                    }
                }
            }

            private fun ord(o: Int) = when (op) {
                "=" -> o == 0
                "<>" -> o != 0
                "<" -> o < 0
                ">" -> o > 0
                "<=" -> o <= 0
                else -> o >= 0
            }

            fun cumple(v: Valor): Boolean {
                if (v is Valor.Err) return false
                val otroTipo = op == "<>"
                return when (tipo) {
                    'v' -> if (op == "<>") !esVacio(v) else esVacio(v)
                    'n' -> if (v is Valor.Num) ord(if (mismoNumero(v.n, n)) 0 else if (v.n < n) -1 else 1) else otroTipo
                    'b' -> if (v is Valor.Log) ord(if (v.b == b) 0 else if (!v.b) -1 else 1) else otroTipo
                    else -> if (v is Valor.Txt && v.s.isNotEmpty()) {
                        if (op == "=" || op == "<>") {
                            val casa = if (tieneComodines(t)) comodin(letras(t), 0, letras(v.s.lowercase()), 0, true)
                                else v.s.lowercase() == t
                            casa == (op == "=")
                        } else ord(signo(v.s.lowercase().compareTo(t)))
                    } else otroTipo
                }
            }
        }

        /** Las funciones del PC (`motor::CATALOGO`): nombre → (nombre en español, mínimo y máximo de argumentos). */
        private val CATALOGO_PC: Map<String, Triple<String, Int, Int>> = listOf(
            Triple("SI", 2, 3) to listOf("SI", "IF"),
            Triple("Y", 1, 255) to listOf("Y", "AND"),
            Triple("O", 1, 255) to listOf("O", "OR"),
            Triple("NO", 1, 1) to listOf("NO", "NOT"),
            Triple("SI.ERROR", 2, 2) to listOf("SI.ERROR", "IFERROR"),
            Triple("SI.ND", 2, 2) to listOf("SI.ND", "IFNA"),
            Triple("SI.CONJUNTO", 2, 254) to listOf("SI.CONJUNTO", "IFS"),
            Triple("VERDADERO", 0, 0) to listOf("VERDADERO", "TRUE"),
            Triple("FALSO", 0, 0) to listOf("FALSO", "FALSE"),
            Triple("SUMA", 0, 255) to listOf("SUMA", "SUM"),
            Triple("CONTAR", 0, 255) to listOf("CONTAR", "COUNT"),
            Triple("PROMEDIO", 0, 255) to listOf("PROMEDIO", "AVERAGE"),
            Triple("MIN", 0, 255) to listOf("MIN", "MINIMO"),
            Triple("MAX", 0, 255) to listOf("MAX", "MAXIMO"),
            Triple("REDONDEAR", 2, 2) to listOf("REDONDEAR", "ROUND"),
            Triple("REDONDEAR.MAS", 2, 2) to listOf("REDONDEAR.MAS", "ROUNDUP"),
            Triple("REDONDEAR.MENOS", 2, 2) to listOf("REDONDEAR.MENOS", "ROUNDDOWN"),
            Triple("ENTERO", 1, 1) to listOf("ENTERO", "INT"),
            Triple("ABS", 1, 1) to listOf("ABS"),
            Triple("RESIDUO", 2, 2) to listOf("RESIDUO", "MOD"),
            Triple("POTENCIA", 2, 2) to listOf("POTENCIA", "POWER"),
            Triple("RAIZ", 1, 1) to listOf("RAIZ", "SQRT"),
            Triple("PRODUCTO", 1, 255) to listOf("PRODUCTO", "PRODUCT"),
            Triple("MEDIANA", 1, 255) to listOf("MEDIANA", "MEDIAN"),
            Triple("PI", 0, 0) to listOf("PI"),
            Triple("ALEATORIO", 0, 0) to listOf("ALEATORIO", "RAND"),
            Triple("ALEATORIO.ENTRE", 2, 2) to listOf("ALEATORIO.ENTRE", "RANDBETWEEN"),
            Triple("CONTARA", 1, 255) to listOf("CONTARA", "COUNTA"),
            Triple("CONTAR.BLANCO", 1, 1) to listOf("CONTAR.BLANCO", "COUNTBLANK"),
            Triple("SUMAR.SI", 2, 3) to listOf("SUMAR.SI", "SUMIF"),
            Triple("SUMAR.SI.CONJUNTO", 3, 255) to listOf("SUMAR.SI.CONJUNTO", "SUMIFS"),
            Triple("CONTAR.SI", 2, 2) to listOf("CONTAR.SI", "COUNTIF"),
            Triple("CONTAR.SI.CONJUNTO", 2, 254) to listOf("CONTAR.SI.CONJUNTO", "COUNTIFS"),
            Triple("PROMEDIO.SI", 2, 3) to listOf("PROMEDIO.SI", "AVERAGEIF"),
            Triple("BUSCARV", 3, 4) to listOf("BUSCARV", "VLOOKUP"),
            Triple("BUSCARH", 3, 4) to listOf("BUSCARH", "HLOOKUP"),
            Triple("INDICE", 2, 3) to listOf("INDICE", "INDEX"),
            Triple("COINCIDIR", 2, 3) to listOf("COINCIDIR", "MATCH"),
            Triple("BUSCARX", 3, 6) to listOf("BUSCARX", "XLOOKUP"),
            Triple("CONCATENAR", 1, 255) to listOf("CONCATENAR", "CONCATENATE"),
            Triple("CONCAT", 1, 255) to listOf("CONCAT"),
            Triple("UNIRCADENAS", 3, 255) to listOf("UNIRCADENAS", "TEXTJOIN"),
            Triple("IZQUIERDA", 1, 2) to listOf("IZQUIERDA", "LEFT"),
            Triple("DERECHA", 1, 2) to listOf("DERECHA", "RIGHT"),
            Triple("EXTRAE", 3, 3) to listOf("EXTRAE", "MID"),
            Triple("LARGO", 1, 1) to listOf("LARGO", "LEN"),
            Triple("MAYUSC", 1, 1) to listOf("MAYUSC", "UPPER"),
            Triple("MINUSC", 1, 1) to listOf("MINUSC", "LOWER"),
            Triple("NOMPROPIO", 1, 1) to listOf("NOMPROPIO", "PROPER"),
            Triple("ESPACIOS", 1, 1) to listOf("ESPACIOS", "TRIM"),
            Triple("SUSTITUIR", 3, 4) to listOf("SUSTITUIR", "SUBSTITUTE"),
            Triple("ENCONTRAR", 2, 3) to listOf("ENCONTRAR", "FIND"),
            Triple("HALLAR", 2, 3) to listOf("HALLAR", "SEARCH"),
            Triple("TEXTO", 2, 2) to listOf("TEXTO", "TEXT"),
            Triple("VALOR", 1, 1) to listOf("VALOR", "VALUE"),
            Triple("HOY", 0, 0) to listOf("HOY", "TODAY"),
            Triple("AHORA", 0, 0) to listOf("AHORA", "NOW"),
            Triple("FECHA", 3, 3) to listOf("FECHA", "DATE"),
            Triple("AÑO", 1, 1) to listOf("AÑO", "ANO", "YEAR"),
            Triple("MES", 1, 1) to listOf("MES", "MONTH"),
            Triple("DIA", 1, 1) to listOf("DIA", "DAY"),
            Triple("DIAS", 2, 2) to listOf("DIAS", "DAYS"),
            Triple("DIASEM", 1, 2) to listOf("DIASEM", "WEEKDAY"),
            Triple("ESERROR", 1, 1) to listOf("ESERROR", "ISERROR"),
            Triple("ESBLANCO", 1, 1) to listOf("ESBLANCO", "ISBLANK"),
            Triple("ESNUMERO", 1, 1) to listOf("ESNUMERO", "ISNUMBER"),
            Triple("ESND", 1, 1) to listOf("ESND", "ISNA"),
            Triple("ESTEXTO", 1, 1) to listOf("ESTEXTO", "ISTEXT")
        ).flatMap { (t, nombres) -> nombres.map { it to t } }.toMap()

        /** Lo que solo tiene el móvil, y las tildes que se aceptan al escribir. */
        private val CATALOGO_MOVIL: Map<String, Triple<String, Int, Int>> = listOf(
            Triple("DESVEST", 1, 255) to listOf("DESVEST", "DESVEST.M", "STDEV", "STDEV.S"),
            Triple("TRUNCAR", 1, 2) to listOf("TRUNCAR", "TRUNC"),
            Triple("EXP", 1, 1) to listOf("EXP"),
            Triple("LN", 1, 1) to listOf("LN"),
            Triple("LOG10", 1, 1) to listOf("LOG10"),
            Triple("LOG", 1, 2) to listOf("LOG"),
            Triple("SENO", 1, 1) to listOf("SENO", "SIN"),
            Triple("COS", 1, 1) to listOf("COS"),
            Triple("TAN", 1, 1) to listOf("TAN"),
            Triple("GRADOS", 1, 1) to listOf("GRADOS", "DEGREES"),
            Triple("RADIANES", 1, 1) to listOf("RADIANES", "RADIANS"),
            Triple("SIGNO", 1, 1) to listOf("SIGNO", "SIGN"),
            Triple("REPETIR", 2, 2) to listOf("REPETIR", "REPT"),
            Triple("SUMAPRODUCTO", 1, 255) to listOf("SUMAPRODUCTO", "SUMPRODUCT"),
            Triple("ELEGIR", 2, 255) to listOf("ELEGIR", "CHOOSE"),
            Triple("FILA", 0, 1) to listOf("FILA", "ROW"),
            Triple("COLUMNA", 0, 1) to listOf("COLUMNA", "COLUMN"),
            Triple("PROMEDIO.SI.CONJUNTO", 3, 255) to listOf("PROMEDIO.SI.CONJUNTO", "AVERAGEIFS")
        ).flatMap { (t, nombres) -> nombres.map { it to t } }.toMap()

        private val CATALOGO_COMPLETO: Map<String, Triple<String, Int, Int>> = CATALOGO_PC + CATALOGO_MOVIL + mapOf(
            "RAÍZ" to CATALOGO_PC["RAIZ"]!!, "DÍA" to CATALOGO_PC["DIA"]!!, "ÍNDICE" to CATALOGO_PC["INDICE"]!!,
            "DÍAS" to CATALOGO_PC["DIAS"]!!, "MÍNIMO" to CATALOGO_PC["MIN"]!!, "MÁXIMO" to CATALOGO_PC["MAX"]!!
        )

        /**
         * **Las funciones que se ofrecen al escribir**, en español, con cómo se usan. Es lo que
         * enseña el botón «fx» de la aplicación.
         */
        val CATALOGO: List<Pair<String, String>> = listOf(
            "SUMA" to "SUMA(A1:A10)", "PROMEDIO" to "PROMEDIO(A1:A10)", "MIN" to "MIN(A1:A10)",
            "MAX" to "MAX(A1:A10)", "CONTAR" to "CONTAR(A1:A10)", "CONTARA" to "CONTARA(A1:A10)",
            "CONTAR.BLANCO" to "CONTAR.BLANCO(A1:A10)",
            "SI" to "SI(A1>0; \"sí\"; \"no\")", "SI.ERROR" to "SI.ERROR(A1/B1; 0)",
            "SI.CONJUNTO" to "SI.CONJUNTO(A1>10; \"alto\"; A1>5; \"medio\")",
            "Y" to "Y(A1>0; B1>0)", "O" to "O(A1>0; B1>0)", "NO" to "NO(A1>0)", "REDONDEAR" to "REDONDEAR(A1; 2)",
            "REDONDEAR.MAS" to "REDONDEAR.MAS(A1; 0)", "REDONDEAR.MENOS" to "REDONDEAR.MENOS(A1; 0)",
            "SUMAR.SI" to "SUMAR.SI(A1:A10; \">5\")", "CONTAR.SI" to "CONTAR.SI(A1:A10; \"x\")",
            "PROMEDIO.SI" to "PROMEDIO.SI(A1:A10; \">0\")",
            "SUMAR.SI.CONJUNTO" to "SUMAR.SI.CONJUNTO(C1:C10; A1:A10; \"x\")",
            "CONTAR.SI.CONJUNTO" to "CONTAR.SI.CONJUNTO(A1:A10; \">0\"; B1:B10; \"x\")",
            "BUSCARV" to "BUSCARV(E1; A1:C10; 2; FALSO)", "BUSCARH" to "BUSCARH(E1; A1:J3; 2; FALSO)",
            "BUSCARX" to "BUSCARX(E1; A1:A10; B1:B10)",
            "INDICE" to "INDICE(A1:C10; 2; 3)", "COINCIDIR" to "COINCIDIR(E1; A1:A10; 0)",
            "SUMAPRODUCTO" to "SUMAPRODUCTO(A1:A10; B1:B10)", "PRODUCTO" to "PRODUCTO(A1:A10)",
            "ABS" to "ABS(A1)", "RAIZ" to "RAIZ(A1)", "POTENCIA" to "POTENCIA(A1; 2)",
            "RESIDUO" to "RESIDUO(A1; 2)", "ENTERO" to "ENTERO(A1)", "MEDIANA" to "MEDIANA(A1:A10)",
            "DESVEST" to "DESVEST(A1:A10)", "CONCATENAR" to "CONCATENAR(A1; \" \"; B1)",
            "UNIRCADENAS" to "UNIRCADENAS(\", \"; VERDADERO; A1:A10)",
            "LARGO" to "LARGO(A1)", "MAYUSC" to "MAYUSC(A1)", "MINUSC" to "MINUSC(A1)", "NOMPROPIO" to "NOMPROPIO(A1)",
            "ESPACIOS" to "ESPACIOS(A1)", "IZQUIERDA" to "IZQUIERDA(A1; 3)", "DERECHA" to "DERECHA(A1; 3)",
            "EXTRAE" to "EXTRAE(A1; 2; 3)", "SUSTITUIR" to "SUSTITUIR(A1; \"a\"; \"b\")",
            "ENCONTRAR" to "ENCONTRAR(\"a\"; A1)", "HALLAR" to "HALLAR(\"a*\"; A1)",
            "TEXTO" to "TEXTO(A1; \"#,##0.00\")", "VALOR" to "VALOR(A1)", "HOY" to "HOY()",
            "AHORA" to "AHORA()", "FECHA" to "FECHA(2026; 9; 10)", "DIA" to "DIA(A1)", "MES" to "MES(A1)",
            "AÑO" to "AÑO(A1)", "DIAS" to "DIAS(B1; A1)", "DIASEM" to "DIASEM(A1; 2)",
            "ESERROR" to "ESERROR(A1)", "ESBLANCO" to "ESBLANCO(A1)", "ESNUMERO" to "ESNUMERO(A1)",
            "ALEATORIO.ENTRE" to "ALEATORIO.ENTRE(1; 6)", "PI" to "PI()"
        )
    }
}
