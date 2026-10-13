package com.forge.pixpin.motor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * **Una tabla con fórmulas, como se guarda.** Es el cuarto tipo de hoja de un proyecto, al
 * lado del lienzo, la nota y el croquis 3D (lo pidió el usuario el 10-sep-2026).
 *
 * Solo lo escrito: cada celda con **lo que se tecleó** —`12`, `Total`, `=SUMA(B2:B9)`—, por
 * su dirección. Los resultados no se guardan: se calculan al abrir, que es lo que hace que un
 * archivo editado a mano o pegado desde otro sitio nunca enseñe un total viejo.
 *
 * El mismo JSON va dentro de la página web exportada (ver [VisorTabla]) y del `.pixpin`
 * (`tablas/<id>.json`), así que se lee igual en los tres sitios.
 */
@Serializable
data class TablaDeCalculo(
    val nombre: String = "",
    /** `"B7"` → lo escrito. */
    val celdas: Map<String, String> = emptyMap(),
    /** `"B"` → ancho en dp, solo las columnas que se han ensanchado o estrechado. */
    val anchos: Map<String, Int> = emptyMap(),
    val estilos: Map<String, EstiloDeCelda> = emptyMap(),
    val tocado: Long = 0L,
    /**
     * **Protegida**: en la página web solo se pueden cambiar las celdas marcadas como editables
     * ([EstiloDeCelda.e]); el resto se ve, recalcula y se raya encima, pero no se toca (lo pidió
     * el usuario el 11-sep-2026). En la aplicación, quien la hizo lo edita todo.
     */
    val protegida: Boolean = false
) {
    companion object {
        /** Sin los valores por defecto: una tabla de mil celdas no repite mil veces `"n":false`. */
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun deJson(texto: String): TablaDeCalculo? =
            runCatching { json.decodeFromString(serializer(), texto) }.getOrNull()

        fun aJson(t: TablaDeCalculo): String = json.encodeToString(serializer(), t)

        /** Las claves de la tabla que se conocen. Las demás se conservan: ver [aJsonSobre]. */
        val CLAVES = setOf("nombre", "celdas", "anchos", "estilos", "tocado", "protegida")

        /**
         * [t] en JSON **sin perder lo que no se conoce de [original]** (el JSON que había antes):
         * una clave nueva de la tabla o de un estilo que escribió otra versión —del PC o de un
         * móvil más nuevo— sigue ahí después de editar aquí. Es lo que hace el PC con su `resto`
         * (`#[serde(flatten)]`). Lo conocido manda siempre lo de [t]: lo que vale lo de por
         * defecto no se escribe y tampoco se rescata de [original].
         */
        fun aJsonSobre(t: TablaDeCalculo, original: String?): String {
            val nuevo = aJson(t)
            if (original.isNullOrBlank()) return nuevo
            val viejo = runCatching { Json.parseToJsonElement(original) as? JsonObject }.getOrNull() ?: return nuevo
            val raros = viejo.filterKeys { it !in CLAVES }
            val estilosViejos = viejo["estilos"] as? JsonObject
            val conRaros = estilosViejos?.any { (_, e) -> (e as? JsonObject)?.keys?.any { it !in EstiloDeCelda.CLAVES } == true } == true
            if (raros.isEmpty() && !conRaros) return nuevo
            val n = Json.parseToJsonElement(nuevo).jsonObject
            val salida = LinkedHashMap<String, JsonElement>(n)
            for ((k, v) in raros) salida[k] = v
            if (conRaros) {
                val estilos = (n["estilos"] as? JsonObject)?.let { LinkedHashMap<String, JsonElement>(it) } ?: LinkedHashMap()
                // También la celda que aquí se quedó sin estilo: lo que no se conoce no es formato
                // que «Borrar formato» quite (el PC lo guarda igual, en su `resto`).
                for ((celda, e) in estilosViejos.orEmpty()) {
                    val suyos = (e as? JsonObject)?.filterKeys { it !in EstiloDeCelda.CLAVES }
                    if (suyos.isNullOrEmpty()) continue
                    val mio = estilos[celda]?.jsonObject.orEmpty()
                    estilos[celda] = JsonObject(LinkedHashMap<String, JsonElement>(mio).apply { putAll(suyos) })
                }
                if (estilos.isNotEmpty()) salida["estilos"] = JsonObject(estilos)
            }
            return JsonObject(salida).toString()
        }
    }
}

/**
 * Lo poco de formato que hace falta para que una tabla se entienda: **negrita** en los
 * títulos, **alineación** y un **color de fondo** para marcar filas. Nombres de una letra
 * porque se repiten en cada celda con estilo.
 *
 * **Lo que añadió el PC** (barra de formato del pin de hoja, 12-oct-2026; aquí el 13-oct):
 * cursiva [k], subrayado [s], tachado [t], color de letra [c], ajustar texto [w] y formato de
 * número [nf]. Mismos nombres que `EstiloDeCelda` de `crates/pixpin-proyecto/src/tabla.rs`:
 * antes el móvil los leía (`ignoreUnknownKeys`) pero los perdía al volver a guardar.
 */
@Serializable
data class EstiloDeCelda(
    val n: Boolean = false,
    /** `i`, `c` o `d`; null deja la alineación que toque por el valor. */
    val a: String? = null,
    /** El fondo, `#rrggbb`. */
    val f: String? = null,
    /** Editable aunque la tabla esté protegida. Ver [TablaDeCalculo.protegida]. */
    val e: Boolean = false,
    /** Cursiva (la `k` de Ctrl+K en el Excel español: la `c` ya es el color). */
    val k: Boolean = false,
    /** Subrayado. */
    val s: Boolean = false,
    /** Tachado. */
    val t: Boolean = false,
    /** El color de la letra, `#rrggbb`. */
    val c: String? = null,
    /** Ajustar el texto a la celda: varias líneas en vez de cortarse. */
    val w: Boolean = false,
    /** El formato del número: `numero`, `moneda`, `porcentaje` o `fecha`. Null es «General». Ver [FormatoDeNumero]. */
    val nf: String? = null
) {
    val vacio: Boolean get() = !e && sinFormato

    /** Sin nada que se vea: lo que deja «Borrar formato» (la marca de editable no es formato). */
    val sinFormato: Boolean get() = !n && a == null && f == null && !k && !s && !t && c == null && !w && nf == null

    /** **Borrar formato**: quita todo lo que se ve y deja la marca de editable, como en el PC. */
    fun sinElFormato(): EstiloDeCelda? = if (e) EstiloDeCelda(e = true) else null

    companion object {
        /** Las claves que se conocen: las demás, de una versión más nueva, se conservan al guardar. */
        val CLAVES = setOf("n", "a", "f", "e", "k", "s", "t", "c", "w", "nf")
    }
}

/**
 * **Los formatos de número** del botón «123» de la barra (el `nf` de un estilo). La palabra es
 * lo que se guarda y **no se cambia nunca**: es la del PC (`FormatoNumero::palabra`).
 */
enum class FormatoDeNumero(val palabra: String, val nombre: String) {
    NUMERO("numero", "Número"),
    MONEDA("moneda", "Moneda"),
    PORCENTAJE("porcentaje", "Porcentaje"),
    FECHA("fecha", "Fecha");

    companion object {
        /** De la palabra guardada; una que no se conoce (de una versión más nueva) se ve como «General». */
        fun de(palabra: String?): FormatoDeNumero? = entries.firstOrNull { it.palabra == palabra }
    }
}

/**
 * **La tabla mientras se edita**: lo que la pantalla toca, con deshacer y rehacer.
 *
 * Deshacer guarda **lo que cambió**, no la tabla entera: pegar cincuenta celdas en una tabla
 * de diez mil anota cincuenta cambios. Solo meter o quitar filas y columnas guarda la foto
 * completa, porque mueve todo, y eso se hace poco.
 */
class TablaViva(inicial: TablaDeCalculo) {

    val calc = Calculadora()
    private val estilos = HashMap<Int, EstiloDeCelda>()
    private val anchos = HashMap<Int, Int>()
    var nombre: String = inicial.nombre

    /** Ver [TablaDeCalculo.protegida]. Se cambia con [proteger], que se puede deshacer. */
    var protegida: Boolean = inicial.protegida
        private set

    /** Sube con cada cambio: la pantalla repinta y el guardado se entera. */
    var version: Int = 0
        private set

    private sealed interface Paso {
        class Celdas(val cambios: List<Triple<Int, String, String>>) : Paso
        class Estilos(val cambios: List<Triple<Int, EstiloDeCelda?, EstiloDeCelda?>>) : Paso
        class Ancho(val col: Int, val antes: Int?, val despues: Int?) : Paso
        class Foto(val antes: TablaDeCalculo, val despues: TablaDeCalculo) : Paso
        class Compuesto(val pasos: List<Paso>) : Paso
        class Proteccion(val antes: Boolean, val despues: Boolean) : Paso
    }

    private val hechos = ArrayDeque<Paso>()
    private val rehechos = ArrayDeque<Paso>()

    init { cargar(inicial) }

    private fun cargar(t: TablaDeCalculo) {
        protegida = t.protegida
        val celdas = HashMap<Int, String>()
        for ((dir, v) in t.celdas) Celdas.leer(dir)?.let { celdas[Celdas.clave(it[0], it[1])] = v }
        calc.ponerTodo(celdas)
        estilos.clear()
        for ((dir, e) in t.estilos) if (!e.vacio) Celdas.leer(dir)?.let { estilos[Celdas.clave(it[0], it[1])] = e }
        anchos.clear()
        for ((letras, a) in t.anchos) Celdas.columna(letras).takeIf { it in 0 until Celdas.MAX_COLS }?.let { anchos[it] = a }
    }

    fun aTabla(tocado: Long = System.currentTimeMillis()): TablaDeCalculo = TablaDeCalculo(
        nombre = nombre,
        celdas = calc.todo().entries.associate { (k, v) -> Celdas.nombre(Celdas.filaDe(k), Celdas.colDe(k)) to v },
        anchos = anchos.entries.associate { (c, a) -> Celdas.letras(c) to a },
        estilos = estilos.entries.associate { (k, e) -> Celdas.nombre(Celdas.filaDe(k), Celdas.colDe(k)) to e },
        tocado = tocado,
        protegida = protegida
    )

    fun estilo(fila: Int, col: Int): EstiloDeCelda? = estilos[Celdas.clave(fila, col)]

    /**
     * **Lo que se ve en la celda**, ya con su formato de número ([EstiloDeCelda.nf]): el texto de
     * la fórmula o lo escrito si no es un número o la celda no lleva formato.
     */
    fun visto(fila: Int, col: Int): String = conFormato(calc, estilo(fila, col), fila, col)
    fun ancho(col: Int): Int = anchos[col] ?: ANCHO

    val puedeDeshacer: Boolean get() = hechos.isNotEmpty()
    val puedeRehacer: Boolean get() = rehechos.isNotEmpty()

    private fun anotar(p: Paso) {
        hechos.addLast(p)
        while (hechos.size > MAX_PASOS) hechos.removeFirst()
        rehechos.clear()
        version++
    }

    fun escribir(fila: Int, col: Int, texto: String) = escribirVarias(listOf(Triple(fila, col, texto)))

    /** Varias celdas, **un solo paso** de deshacer. */
    fun escribirVarias(cambios: List<Triple<Int, Int, String>>) {
        val anotados = ArrayList<Triple<Int, String, String>>(cambios.size)
        for ((f, c, t) in cambios) {
            if (f !in 0 until Celdas.MAX_FILAS || c !in 0 until Celdas.MAX_COLS) continue
            val antes = calc.crudo(f, c)
            if (antes == t) continue
            anotados += Triple(Celdas.clave(f, c), antes, t)
            calc.poner(f, c, t)
        }
        if (anotados.isNotEmpty()) anotar(Paso.Celdas(anotados))
    }

    fun borrar(f1: Int, c1: Int, f2: Int, c2: Int) {
        val cambios = ArrayList<Triple<Int, Int, String>>()
        for (k in calc.todo().keys) {
            val f = Celdas.filaDe(k); val c = Celdas.colDe(k)
            if (f in f1..f2 && c in c1..c2) cambios += Triple(f, c, "")
        }
        escribirVarias(cambios)
    }

    /** Le cambia el estilo a un rango, en un paso. */
    fun cambiarEstilo(f1: Int, c1: Int, f2: Int, c2: Int, cambio: (EstiloDeCelda?) -> EstiloDeCelda?) {
        val anotados = ArrayList<Triple<Int, EstiloDeCelda?, EstiloDeCelda?>>()
        val alto = minOf(f2, maxOf(f1, calc.filas + 200))
        for (f in f1..alto) for (c in c1..minOf(c2, Celdas.MAX_COLS - 1)) {
            val k = Celdas.clave(f, c)
            val antes = estilos[k]
            val despues = cambio(antes)?.takeIf { !it.vacio }
            if (antes == despues) continue
            if (despues == null) estilos.remove(k) else estilos[k] = despues
            anotados += Triple(k, antes, despues)
        }
        if (anotados.isNotEmpty()) anotar(Paso.Estilos(anotados))
    }

    fun proteger(si: Boolean) {
        if (si == protegida) return
        anotar(Paso.Proteccion(protegida, si))
        protegida = si
    }

    /**
     * **Marca o desmarca el rango como editable** en una tabla protegida: si ya lo eran todas,
     * dejan de serlo; si no, lo pasan a ser todas. Un solo paso de deshacer.
     */
    fun alternarEditables(f1: Int, c1: Int, f2: Int, c2: Int) {
        val alto = minOf(f2, f1 + 20_000)
        val todas = (f1..alto).all { f -> (c1..c2).all { c -> estilo(f, c)?.e == true } }
        cambiarEstilo(f1, c1, alto, c2) { (it ?: EstiloDeCelda()).copy(e = !todas) }
    }

    fun ponerAncho(col: Int, dp: Int?) {
        val antes = anchos[col]
        val despues = dp?.coerceIn(ANCHO_MIN, ANCHO_MAX)
        if (antes == despues) return
        if (despues == null) anchos.remove(col) else anchos[col] = despues
        // Arrastrar el borde da cien anchos seguidos: se juntan en un solo paso.
        val ultimo = hechos.lastOrNull()
        if (ultimo is Paso.Ancho && ultimo.col == col && rehechos.isEmpty()) {
            hechos.removeLast()
            anotar(Paso.Ancho(col, ultimo.antes, despues))
        } else anotar(Paso.Ancho(col, antes, despues))
    }

    /**
     * Mete ([cuantos] > 0) o quita filas o columnas en [en]. Las celdas, los estilos y los
     * anchos se corren, y **las fórmulas de toda la tabla se corrigen**. Ver
     * [CalculoLexico.alInsertar].
     */
    fun insertar(columnas: Boolean, en: Int, cuantos: Int) {
        if (cuantos == 0) return
        val antes = aTabla(0)
        fun mover(v: Int): Int? = when {
            cuantos > 0 -> if (v >= en) v + cuantos else v
            v < en -> v
            v < en - cuantos -> null
            else -> v + cuantos
        }
        val celdas = HashMap<Int, String>()
        for ((k, raw) in calc.todo()) {
            var f = Celdas.filaDe(k); var c = Celdas.colDe(k)
            val m = mover(if (columnas) c else f) ?: continue
            if (columnas) c = m else f = m
            if (f >= Celdas.MAX_FILAS || c >= Celdas.MAX_COLS) continue
            celdas[Celdas.clave(f, c)] = CalculoLexico.alInsertar(raw, columnas, en, cuantos)
        }
        val nuevosEstilos = HashMap<Int, EstiloDeCelda>()
        for ((k, e) in estilos) {
            var f = Celdas.filaDe(k); var c = Celdas.colDe(k)
            val m = mover(if (columnas) c else f) ?: continue
            if (columnas) c = m else f = m
            if (f >= Celdas.MAX_FILAS || c >= Celdas.MAX_COLS) continue
            nuevosEstilos[Celdas.clave(f, c)] = e
        }
        calc.ponerTodo(celdas)
        estilos.clear(); estilos.putAll(nuevosEstilos)
        if (columnas) {
            val nuevos = HashMap<Int, Int>()
            for ((c, a) in anchos) mover(c)?.takeIf { it < Celdas.MAX_COLS }?.let { nuevos[it] = a }
            anchos.clear(); anchos.putAll(nuevos)
        }
        anotar(Paso.Foto(antes, aTabla(0)))
    }

    /**
     * Pega [bloque] con su esquina en ([fila], [col]).
     *
     * [origen] es de dónde se copió, si se copió en esta aplicación: entonces las fórmulas se
     * mueven lo que se ha movido el bloque, igual que en Excel. Las que llegan en R1C1 (de
     * Sheets o de una página exportada) se traducen al sitio donde caen. Devuelve el rango
     * pegado: fila y columna del final.
     */
    fun pegar(fila: Int, col: Int, bloque: List<List<PortapapelesDeTabla.Pegada>>, origen: IntArray? = null): IntArray {
        val cambios = ArrayList<Triple<Int, Int, String>>()
        val negritas = ArrayList<Pair<Int, Int>>()
        var ultimaFila = fila
        var ultimaCol = col
        for ((i, filaPegada) in bloque.withIndex()) {
            for ((j, p) in filaPegada.withIndex()) {
                val f = fila + i; val c = col + j
                if (f >= Celdas.MAX_FILAS || c >= Celdas.MAX_COLS) continue
                val texto = when {
                    p.r1c1 -> PortapapelesDeTabla.r1c1(p.texto, f, c)
                    origen != null && Calculadora.esFormula(p.texto) ->
                        CalculoLexico.desplazar(p.texto, f - (origen[0] + i), c - (origen[1] + j))
                    else -> p.texto
                }
                cambios += Triple(f, c, texto)
                if (p.negrita) negritas += f to c
                ultimaFila = maxOf(ultimaFila, f); ultimaCol = maxOf(ultimaCol, c)
            }
        }
        val antesDeEscribir = version
        escribirVarias(cambios)
        val escrito = version != antesDeEscribir
        val anotados = ArrayList<Triple<Int, EstiloDeCelda?, EstiloDeCelda?>>()
        for ((f, c) in negritas) {
            val k = Celdas.clave(f, c)
            val antes = estilos[k]
            if (antes?.n == true) continue
            val despues = (antes ?: EstiloDeCelda()).copy(n = true)
            estilos[k] = despues
            anotados += Triple(k, antes, despues)
        }
        if (anotados.isNotEmpty()) {
            // Va con el pegado: un solo deshacer quita las dos cosas.
            if (escrito) {
                val celdas = hechos.removeLast()
                hechos.addLast(Paso.Compuesto(listOf(celdas, Paso.Estilos(anotados))))
                version++
            } else anotar(Paso.Estilos(anotados))
        }
        return intArrayOf(ultimaFila, ultimaCol)
    }

    /**
     * **Rellenar**: la primera fila (o columna) del rango se copia al resto, con las fórmulas
     * movidas. Es el «arrastrar la esquina» de Excel, que con el dedo no se puede hacer bien.
     */
    fun rellenar(f1: Int, c1: Int, f2: Int, c2: Int, haciaAbajo: Boolean) {
        val cambios = ArrayList<Triple<Int, Int, String>>()
        if (haciaAbajo) {
            for (c in c1..c2) {
                val raw = calc.crudo(f1, c)
                for (f in f1 + 1..f2) cambios += Triple(f, c, if (Calculadora.esFormula(raw)) CalculoLexico.desplazar(raw, f - f1, 0) else raw)
            }
        } else {
            for (f in f1..f2) {
                val raw = calc.crudo(f, c1)
                for (c in c1 + 1..c2) cambios += Triple(f, c, if (Calculadora.esFormula(raw)) CalculoLexico.desplazar(raw, 0, c - c1) else raw)
            }
        }
        escribirVarias(cambios)
    }

    /**
     * La fórmula que propone Σ en ([fila], [col]): la suma de los números seguidos que haya
     * justo encima o, si no hay, a la izquierda.
     */
    fun autosuma(fila: Int, col: Int): String {
        fun esNumero(f: Int, c: Int) = f >= 0 && c >= 0 && calc.valor(f, c) is Valor.Num
        var f = fila - 1
        while (esNumero(f, col)) f--
        if (f < fila - 1) return "=SUMA(" + Celdas.nombre(f + 1, col) + ":" + Celdas.nombre(fila - 1, col) + ")"
        var c = col - 1
        while (esNumero(fila, c)) c--
        if (c < col - 1) return "=SUMA(" + Celdas.nombre(fila, c + 1) + ":" + Celdas.nombre(fila, col - 1) + ")"
        return "=SUMA()"
    }

    fun deshacer(): Boolean {
        val p = hechos.removeLastOrNull() ?: return false
        aplicar(p, false)
        rehechos.addLast(p)
        version++
        return true
    }

    fun rehacer(): Boolean {
        val p = rehechos.removeLastOrNull() ?: return false
        aplicar(p, true)
        hechos.addLast(p)
        version++
        return true
    }

    private fun aplicar(p: Paso, adelante: Boolean) {
        when (p) {
            is Paso.Celdas -> for ((k, antes, despues) in if (adelante) p.cambios else p.cambios.asReversed()) {
                calc.poner(Celdas.filaDe(k), Celdas.colDe(k), if (adelante) despues else antes)
            }
            is Paso.Estilos -> for ((k, antes, despues) in p.cambios) {
                val e = if (adelante) despues else antes
                if (e == null) estilos.remove(k) else estilos[k] = e
            }
            is Paso.Ancho -> {
                val a = if (adelante) p.despues else p.antes
                if (a == null) anchos.remove(p.col) else anchos[p.col] = a
            }
            is Paso.Foto -> cargar(if (adelante) p.despues else p.antes)
            is Paso.Proteccion -> protegida = if (adelante) p.despues else p.antes
            is Paso.Compuesto -> (if (adelante) p.pasos else p.pasos.asReversed()).forEach { aplicar(it, adelante) }
        }
    }

    /** Lo que se ve en un rango, fila a fila: para copiar, compartir y exportar. */
    fun valores(f1: Int, c1: Int, f2: Int, c2: Int): List<List<String>> =
        (f1..f2).map { f -> (c1..c2).map { c -> visto(f, c) } }

    fun crudos(f1: Int, c1: Int, f2: Int, c2: Int): List<List<String>> =
        (f1..f2).map { f -> (c1..c2).map { c -> calc.crudo(f, c) } }

    companion object {
        /** El símbolo de la moneda del aparato, para el formato `moneda`. La aplicación lo pone al arrancar. */
        @Volatile var moneda: String = CalculoFormato.MONEDA_POR_DEFECTO

        /** Lo que enseña la celda ([fila], [col]) de [calc] con el estilo [e]. Ver [visto]. */
        fun conFormato(calc: Calculadora, e: EstiloDeCelda?, fila: Int, col: Int): String {
            val nf = FormatoDeNumero.de(e?.nf) ?: return calc.texto(fila, col)
            if (calc.crudo(fila, col).isEmpty()) return ""
            val v = calc.valor(fila, col) as? Valor.Num ?: return calc.texto(fila, col)
            return CalculoFormato.conFormatoDeCelda(v.n, nf, moneda)
        }

        const val ANCHO = 96
        const val ANCHO_MIN = 28
        const val ANCHO_MAX = 600
        const val MAX_PASOS = 200

        /**
         * **La tabla como Markdown**, con los valores ya calculados: es como entra en el PDF de
         * un proyecto y en la miniatura, que ya saben componer tablas de Markdown.
         * La primera fila escrita hace de cabecera.
         */
        fun comoMarkdown(t: TablaDeCalculo, maxFilas: Int = 400, maxCols: Int = 26): String {
            val v = TablaViva(t)
            val filas = minOf(v.calc.filas, maxFilas)
            val cols = minOf(v.calc.cols, maxCols)
            if (filas == 0 || cols == 0) return ""
            fun celda(f: Int, c: Int): String {
                val s = v.visto(f, c).replace("|", "\\|").replace("\n", " ")
                if (s.isBlank()) return s
                val e = v.estilo(f, c) ?: return s
                var r = s
                if (e.k) r = "*$r*"
                if (e.n) r = "**$r**"
                if (e.t) r = "~~$r~~"
                return r
            }
            return buildString {
                if (t.nombre.isNotBlank()) append("## ").append(t.nombre).append("\n\n")
                append("| ").append((0 until cols).joinToString(" | ") { celda(0, it) }).append(" |\n")
                append("|").append((0 until cols).joinToString("|") { c ->
                    if (v.calc.alineacion(minOf(1, filas - 1), c) == 'd') "---:" else "---"
                }).append("|\n")
                for (f in 1 until filas) {
                    append("| ").append((0 until cols).joinToString(" | ") { celda(f, it) }).append(" |\n")
                }
            }
        }
    }
}
