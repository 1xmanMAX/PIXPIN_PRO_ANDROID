package com.forge.pixpin.motor

import com.forge.pixpin.motormd.Formulas
import kotlin.math.abs
import kotlin.math.max

/**
 * **El texto rico del lienzo** (PC `texto_rico.rs`, 15-oct-2026; aquí el 18-oct): un texto con
 * tramos de otro color, en negrita, cursiva, subrayados, tachados o de otro tamaño, y
 * **fórmulas en línea** (`$m\sphericalangle AOB = 90^\circ$`) compuestas con el compositor
 * común ([Ecuacion.deFormula]) y alineadas con las letras de su renglón.
 *
 * Lo pidió el usuario al reconstruir láminas de geometría: «Nos piden m∢COD = **X**» con la X
 * en rojo, «A̅B̅» con su raya, «mAC⌒» con su arco, «45° = (2a+2b)/2» con la fracción de verdad.
 *
 * ## Un solo motor
 *
 * No es un elemento nuevo: es el **mismo** `TEXT`, con su fuente rica en [Element.textoRico].
 * El `text` de siempre se queda con la versión **plana** ([plano]: `m∢COD = X`, `A̅B̅`, `AC⌒`,
 * `(2a + 2b)/2`), que es lo que lee quien no entiende el formato (un Android viejo, Excalidraw,
 * la búsqueda, la voz). Pintarlo es producir más textos y rayas de los de siempre ([Maqueta]):
 * los recorren el lienzo, el SVG (y con él la página web) y el PDF.
 *
 * **Regla de coherencia** (la del PC, `docs/texto-rico-del-lienzo.md`): si `plano(textoRico)`
 * no es el `text`, alguien que no entiende el formato editó el texto; la fuente está vieja y no
 * vale ([vigente]). Así cualquier camino que cambie `text` sin saber de esto gana sin más.
 *
 * Lo que no se entiende **se ve tal cual**: un `$` sin cerrar es un dólar, un mandato
 * desconocido sale con su barra. Nunca falla ni se cuelga. Sin Android: las letras las mide
 * quien llama ([Medidor]).
 */
object TextoRico {

    /** Lo más largo que se compone una fuente rica: un libro pegado no congela el lienzo. */
    const val MAX_FUENTE = 20_000

    /** Un ancho que no parte renglones (el `SIN_PARTIR` del PC). */
    const val SIN_PARTIR = 1.0e6

    private const val ALTURA_DEL_TACHADO = 0.30
    private const val GROSOR_DEL_TACHADO = 0.06

    /** Lo que mide un trozo de letra: el ancho. El alto del renglón es `tam × INTERLINEADO`. */
    fun interface Medidor {
        fun ancho(texto: String, tam: Double, negrita: Boolean, cursiva: Boolean): Double
    }

    /** Cómo va escrito un tramo. [color] null: el del elemento. [escala]: en veces su tamaño. */
    data class Estilo(
        val color: String? = null,
        val negrita: Boolean = false,
        val cursiva: Boolean = false,
        val subrayado: Boolean = false,
        val tachado: Boolean = false,
        val escala: Double = 1.0
    )

    /** Un trozo de renglón con su estilo. */
    sealed class Tramo {
        data class Texto(val texto: String, val estilo: Estilo) : Tramo()

        /** Una fórmula (sin sus dólares). [alta]: entre `$$`. */
        data class Formula(val fuente: String, val estilo: Estilo, val alta: Boolean) : Tramo()
    }

    /** A qué lado va un renglón. `JUSTIFICADO` no existe en `textAlign`: va en la fuente. */
    enum class Alineado { IZQUIERDA, CENTRO, DERECHA, JUSTIFICADO }

    /** Un renglón de la fuente. [alineado] null: el del elemento. */
    data class Renglon(val alineado: Alineado?, val tramos: List<Tramo>)

    // ---------------------------------------------------------------------
    // Leer
    // ---------------------------------------------------------------------

    /** **Lee una fuente rica** a sus renglones y tramos. Nunca falla. */
    fun leer(fuente: String): List<Renglon> =
        fuente.take(MAX_FUENTE).split('\n').map { leerRenglon(it) }

    private val DIRECTIVAS = listOf(
        "\\centering" to Alineado.CENTRO,
        "\\justificado" to Alineado.JUSTIFICADO,
        "\\flushright" to Alineado.DERECHA,
        "\\izquierda" to Alineado.IZQUIERDA,
        "\\flushleft" to Alineado.IZQUIERDA,
        "\\derecha" to Alineado.DERECHA,
        "\\justify" to Alineado.JUSTIFICADO,
        "\\centro" to Alineado.CENTRO,
        "\\center" to Alineado.CENTRO
    ).sortedByDescending { it.first.length }

    private fun leerRenglon(linea0: String): Renglon {
        val linea = linea0.removeSuffix("\r")
        var alineado: Alineado? = null
        var resto = linea
        val sinBlancos = linea.trimStart()
        for ((d, a) in DIRECTIVAS) {
            if (sinBlancos.startsWith(d)) {
                val r = sinBlancos.substring(d.length)
                if (r.firstOrNull()?.isLetter() == true) continue
                alineado = a
                resto = r.removePrefix(" ")
                break
            }
        }
        val tramos = ArrayList<Tramo>()
        leerTramos(resto, 0, resto.length, Estilo(), tramos)
        return Renglon(alineado, fusionar(tramos))
    }

    /** Junta los textos seguidos con el mismo estilo. */
    private fun fusionar(v: List<Tramo>): List<Tramo> {
        val out = ArrayList<Tramo>(v.size)
        for (t in v) {
            val ultimo = out.lastOrNull()
            if (ultimo is Tramo.Texto && t is Tramo.Texto && ultimo.estilo == t.estilo) {
                out[out.size - 1] = Tramo.Texto(ultimo.texto + t.texto, t.estilo)
            } else out += t
        }
        return out
    }

    private fun leerTramos(c: String, desde: Int, hasta: Int, est: Estilo, out: MutableList<Tramo>) {
        val buf = StringBuilder()
        fun soltar() {
            if (buf.isNotEmpty()) {
                out += Tramo.Texto(buf.toString(), est)
                buf.clear()
            }
        }
        var i = desde
        while (i < hasta) {
            val ch = c[i]
            if (ch == '\\') {
                val d = if (i + 1 < hasta) c[i + 1] else null
                if (d != null && !d.isLetter()) {
                    // `\$`, `\*`, `\\`, `\{`: el carácter tal cual.
                    buf.append(d)
                    i += 2
                    continue
                }
                if (d != null) {
                    val m = mandato(c, i, hasta, est)
                    if (m != null) {
                        soltar()
                        leerTramos(c, m.desde, m.hasta, m.sub, out)
                        i = m.fin
                        continue
                    }
                }
                buf.append('\\')
                i++
                continue
            }
            if (ch == '$') {
                val f = formula(c, i, hasta)
                if (f != null) {
                    soltar()
                    out += Tramo.Formula(f.fuente, est, f.alta)
                    i = f.fin
                    continue
                }
                buf.append('$')
                i++
                continue
            }
            val marca = marcaEn(c, i, hasta)
            if (marca != null) {
                val j = cierre(c, i + marca.abre, hasta, marca.cierra)
                if (j != null) {
                    soltar()
                    leerTramos(c, i + marca.abre, j, marca.cambio(est), out)
                    i = j + marca.cierra.length
                    continue
                }
            }
            buf.append(ch)
            i++
        }
        soltar()
    }

    private class Marca(val abre: Int, val cierra: String, val cambio: (Estilo) -> Estilo)

    /** Si en `i` empieza una marca de las de Markdown que abre de verdad. */
    private fun marcaEn(c: String, i: Int, hasta: Int): Marca? {
        fun empieza(s: String) = i + s.length <= hasta && c.startsWith(s, i)
        fun detrasNoBlanco(k: Int) = k < hasta && !c[k].isWhitespace()
        if (empieza("<u>")) return Marca(3, "</u>") { it.copy(subrayado = true) }
        if (empieza("**") && detrasNoBlanco(i + 2)) return Marca(2, "**") { it.copy(negrita = true) }
        if (empieza("~~") && detrasNoBlanco(i + 2)) return Marca(2, "~~") { it.copy(tachado = true) }
        // `*` y `_` solos: como en CommonMark, no abren pegados a una letra por delante
        // (`2*3*4` y `nombre_de_fichero` siguen siendo eso).
        val ch = c[i]
        if ((ch == '*' || ch == '_') &&
            (i + 1 >= hasta || c[i + 1] != ch) &&
            detrasNoBlanco(i + 1) &&
            (i == 0 || !c[i - 1].isLetterOrDigit())
        ) return Marca(1, ch.toString()) { it.copy(cursiva = true) }
        return null
    }

    /**
     * Dónde se cierra una marca abierta antes de [desde]: pegada a algo escrito y, para `*` y
     * `_`, sin una letra detrás. Se saltan los escapes y las fórmulas.
     */
    private fun cierre(c: String, desde: Int, hasta: Int, marca: String): Int? {
        var k = desde
        while (k < hasta) {
            if (c[k] == '\\') { k += 2; continue }
            if (c[k] == '$') {
                val f = formula(c, k, hasta)
                if (f != null) { k = f.fin; continue }
            }
            if (k > desde && k + marca.length <= hasta && c.startsWith(marca, k)) {
                val antesOk = marca.startsWith('<') || !c[k - 1].isWhitespace()
                val simple = marca.length == 1
                val despuesOk = !simple || k + 1 >= hasta ||
                    (!c[k + 1].isLetterOrDigit() && c[k + 1] != marca[0])
                if (antesOk && despuesOk) return k
            }
            k++
        }
        return null
    }

    private class Formula(val fin: Int, val fuente: String, val alta: Boolean)

    /**
     * Una fórmula que empieza en `i` (un `$`), con las reglas de Pandoc para no confundir
     * dinero con fórmulas: el `$` que abre no va seguido de un blanco, el que cierra no va
     * detrás de un blanco ni delante de una cifra. Si no cierra, no es fórmula.
     */
    private fun formula(c: String, i: Int, hasta: Int): Formula? {
        if (i + 1 < hasta && c[i + 1] == '$') {
            val desde = i + 2
            var k = desde
            while (k + 1 < hasta) {
                if (c[k] == '\\') { k += 2; continue }
                if (c[k] == '$' && c[k + 1] == '$') {
                    val f = c.substring(desde, k)
                    return if (f.isBlank()) null else Formula(k + 2, f.trim(), true)
                }
                k++
            }
            return null
        }
        if (i + 1 >= hasta || c[i + 1].isWhitespace()) return null
        var k = i + 1
        while (k < hasta) {
            if (c[k] == '\\') { k += 2; continue }
            if (c[k] == '$') {
                if (c[k - 1].isWhitespace() || (k + 1 < hasta && c[k + 1] in '0'..'9')) return null
                return Formula(k + 1, c.substring(i + 1, k), false)
            }
            k++
        }
        return null
    }

    private class Mandato(val fin: Int, val sub: Estilo, val desde: Int, val hasta: Int)

    /** Un mandato de estilo fuera de una fórmula (`\color{rojo}{X}`), o null (se ve tal cual). */
    private fun mandato(c: String, i: Int, hasta: Int, est: Estilo): Mandato? {
        var k = i + 1
        val nombre = StringBuilder()
        while (k < hasta && c[k].isLetter()) { nombre.append(c[k]); k++ }
        val n = nombre.toString()
        var argumento: String? = null
        if (n == "color" || n == "textcolor" || n == "tam") {
            val ll = llaves(c, k, hasta) ?: return null
            argumento = c.substring(ll.first, ll.second)
            k = ll.third
        }
        val sub = when (n) {
            // Un color que no se conoce deja el de fuera: el texto se ve.
            "color", "textcolor" -> Formulas.colorDeNombre(argumento.orEmpty())?.let { est.copy(color = it) } ?: est
            "tam" -> {
                val f = argumento.orEmpty().trim().replace(',', '.').toDoubleOrNull() ?: return null
                if (!f.isFinite()) return null
                est.copy(escala = est.escala * f.coerceIn(0.3, 5.0))
            }
            "textbf", "negrita" -> est.copy(negrita = true)
            "textit", "emph", "cursiva" -> est.copy(cursiva = true)
            "underline", "uline", "subrayado" -> est.copy(subrayado = true)
            "sout", "st", "tachado" -> est.copy(tachado = true)
            "tiny" -> est.copy(escala = est.escala * 0.6)
            "small", "pequeno" -> est.copy(escala = est.escala * 0.8)
            "large", "grande" -> est.copy(escala = est.escala * 1.2)
            "Large" -> est.copy(escala = est.escala * 1.44)
            "huge", "Huge" -> est.copy(escala = est.escala * 1.73)
            else -> return null
        }
        val ll = llaves(c, k, hasta) ?: return null
        return Mandato(ll.third, sub, ll.first, ll.second)
    }

    /** Unas llaves equilibradas en `k` (con blancos delante): dentro (desde, hasta) y lo de después. */
    private fun llaves(c: String, k0: Int, hasta: Int): Triple<Int, Int, Int>? {
        var k = k0
        while (k < hasta && c[k] == ' ') k++
        if (k >= hasta || c[k] != '{') return null
        val desde = k + 1
        var prof = 0
        while (k < hasta) {
            when (c[k]) {
                '\\' -> k++
                '{' -> prof++
                '}' -> {
                    prof--
                    if (prof == 0) return Triple(desde, k, k + 1)
                }
            }
            k++
        }
        return null
    }

    /**
     * **El texto plano de reserva**: lo que se guarda en `text` para quien no sabe componer.
     * Tiene que salir **idéntico** al del PC (`texto_rico::plano`).
     */
    fun plano(fuente: String): String = leer(fuente).joinToString("\n") { r ->
        r.tramos.joinToString("") { t ->
            when (t) {
                is Tramo.Texto -> t.texto
                is Tramo.Formula -> Formulas.legible(Formulas.leer(t.fuente)).trim()
            }
        }
    }

    /**
     * **Si una fuente lleva algo que componer**: una fórmula, un tramo con estilo, una
     * alineación, un escape o un signo de ángulo. Si no, es un texto de los de siempre y se
     * guarda sin fuente rica (un texto con un `2*3` no estrena nada).
     */
    fun tieneMarcas(fuente: String): Boolean {
        if (fuente.any { it in Ecuacion.ANGULOS }) return true
        if (fuente.none { it in "$*_~<\\" }) return false
        val renglones = leer(fuente)
        return renglones.any { r ->
            r.alineado != null || r.tramos.any { t ->
                when (t) {
                    is Tramo.Formula -> true
                    is Tramo.Texto -> t.estilo != Estilo()
                }
            }
        } || plano(fuente) != fuente
    }

    // ---------------------------------------------------------------------
    // La regla de coherencia
    // ---------------------------------------------------------------------

    /** (fuente, text) → si casan. Pocas entradas: un dibujo tiene pocos textos ricos. */
    private val casan = object : LinkedHashMap<Pair<String, String>, Boolean>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Boolean>) = size > 512
    }

    /**
     * **La fuente rica que vale** de un texto, o null: si no la tiene, si no es un texto o si
     * su plano ya no es el `text` (alguien que no la entiende cambió el texto: gana el texto).
     * Recordado: se pregunta en cada fotograma.
     */
    fun vigente(e: Element): String? {
        val rico = e.textoRico
        if (rico.isNullOrEmpty() || e.type != ElementType.TEXT) return null
        val texto = e.text.orEmpty()
        val clave = rico to texto
        val vale = synchronized(casan) { casan[clave] } ?: (plano(rico) == texto).also {
            synchronized(casan) { casan[clave] = it }
        }
        return if (vale) rico else null
    }

    // ---------------------------------------------------------------------
    // Componer
    // ---------------------------------------------------------------------

    /** Con qué letra y a qué lado se compone. */
    data class Opciones(
        val tam: Double,
        /** Negrita, cursiva y tachado del elemento entero. */
        val negrita: Boolean = false,
        val cursiva: Boolean = false,
        val tachado: Boolean = false,
        val alineacion: TextAlign? = null,
        /** Dónde se parten los renglones: [SIN_PARTIR] no los parte (un texto suelto). */
        val anchoMax: Double = SIN_PARTIR
    )

    /** Lo compuesto: lo que mide y sus piezas, con la esquina en (0, 0). */
    data class Maqueta(
        val ancho: Double,
        val alto: Double,
        val piezas: List<Ecuacion.Pieza>,
        /** Dónde empieza cada renglón de la fuente en vertical. */
        val renglones: List<Double>
    )

    private fun altoDeRenglon(t: Double) = t * INTERLINEADO

    private class Atomo(val caja: Ecuacion.Caja, val espacio: Boolean)

    /** Una caja de letras de un tramo, con su raya de subrayado o tachado. */
    private fun cajaDeTexto(s: String, t: Double, e: Estilo, medir: Medidor): Ecuacion.Caja {
        val ancho = medir.ancho(s, t, e.negrita, e.cursiva)
        val alto = altoDeRenglon(t)
        val piezas = ArrayList<Ecuacion.Pieza>(3)
        piezas += Ecuacion.Pieza.Texto(0.0, 0.0, ancho, alto, s, t, e.cursiva, e.negrita, e.color)
        val grosor = max(t * GROSOR_DEL_TACHADO, 1.0)
        for ((si, y) in listOf(e.subrayado to t * 1.1, e.tachado to t - t * ALTURA_DEL_TACHADO)) {
            if (si && ancho > 0) piezas += Ecuacion.Pieza.Raya(listOf(Pt(0.0, y), Pt(ancho, y)), grosor, e.color)
        }
        return Ecuacion.Caja(ancho, alto, alto * 0.55, piezas)
    }

    private fun atomos(r: Renglon, o: Opciones, medir: Medidor): List<Atomo> {
        val v = ArrayList<Atomo>()
        val medirLetra: MedidaDeTexto = { s, t -> medir.ancho(s, t, false, false) to altoDeRenglon(t) }
        for (tr in r.tramos) when (tr) {
            is Tramo.Texto -> {
                val e = tr.estilo.copy(
                    negrita = tr.estilo.negrita || o.negrita,
                    cursiva = tr.estilo.cursiva || o.cursiva,
                    tachado = tr.estilo.tachado || o.tachado
                )
                val t = o.tam * e.escala
                // Palabras y blancos por separado: por los blancos se parte el renglón y se
                // reparte lo que sobra al justificar.
                val trozo = StringBuilder()
                var enBlanco: Boolean? = null
                for (ch in tr.texto) {
                    // Un signo de ángulo suelto en el texto («Por ∢ inscrito»), dibujado.
                    if (ch in Ecuacion.ANGULOS) {
                        if (trozo.isNotEmpty()) {
                            v += Atomo(cajaDeTexto(trozo.toString(), t, e, medir), enBlanco == true)
                            trozo.clear()
                        }
                        enBlanco = null
                        var c = Ecuacion.angulo(ch, t, medirLetra)
                        e.color?.let { c = c.tenida(it) }
                        v += Atomo(c, false)
                        continue
                    }
                    val blanco = ch == ' ' || ch == '\t'
                    if (enBlanco != null && enBlanco != blanco) {
                        v += Atomo(cajaDeTexto(trozo.toString(), t, e, medir), enBlanco == true)
                        trozo.clear()
                    }
                    enBlanco = blanco
                    trozo.append(if (ch == '\t') ' ' else ch)
                }
                if (trozo.isNotEmpty()) v += Atomo(cajaDeTexto(trozo.toString(), t, e, medir), enBlanco == true)
            }
            is Tramo.Formula -> {
                val t = o.tam * tr.estilo.escala
                val arbol = Formulas.leer(tr.fuente)
                var c = Ecuacion.deFormula(arbol, t, medirLetra, !tr.alta)
                // Un poco de aire a cada lado, como deja TeX entre una fórmula y el texto.
                c = Ecuacion.fila(listOf(Ecuacion.hueco(t * 0.04), c, Ecuacion.hueco(t * 0.04)))
                tr.estilo.color?.let { c = c.tenida(it) }
                if (tr.estilo.negrita || o.negrita) c = c.enNegrita()
                v += Atomo(c, false)
            }
        }
        return v
    }

    private class Visual(val atomos: List<Atomo>, val alineado: Alineado, val ultimo: Boolean, val renglon: Int)

    /** **Compone una fuente rica**: renglones (partidos si hay ancho), alineados por el eje. */
    fun componer(fuente: String, o: Opciones, medir: Medidor): Maqueta {
        val renglones = leer(fuente)
        val partir = o.anchoMax < SIN_PARTIR * 0.5
        val altoBase = altoDeRenglon(o.tam)
        val visuales = ArrayList<Visual>()
        val porDefecto = when (o.alineacion) {
            TextAlign.CENTER -> Alineado.CENTRO
            TextAlign.RIGHT -> Alineado.DERECHA
            else -> Alineado.IZQUIERDA
        }
        val n = renglones.size
        for ((k, r) in renglones.withIndex()) {
            val al = r.alineado ?: porDefecto
            // Sin partir, el párrafo acaba donde hay un renglón vacío (o al final).
            val finDeParrafo = k + 1 == n || renglones[k + 1].tramos.isEmpty()
            val ats = atomos(r, o, medir)
            if (!partir) {
                visuales += Visual(ats, al, finDeParrafo, k)
                continue
            }
            var linea = ArrayList<Atomo>()
            var ancho = 0.0
            for (a in ats) {
                if (!a.espacio && linea.isNotEmpty() && ancho + a.caja.ancho > o.anchoMax) {
                    while (linea.lastOrNull()?.espacio == true) linea.removeAt(linea.size - 1)
                    visuales += Visual(linea, al, false, k)
                    linea = ArrayList()
                    ancho = 0.0
                }
                // El blanco donde se partió no empieza el renglón.
                if (a.espacio && linea.isEmpty() && visuales.lastOrNull()?.renglon == k) continue
                ancho += a.caja.ancho
                linea += a
            }
            visuales += Visual(linea, al, true, k)
        }
        fun finDe(ats: List<Atomo>) = ats.indexOfLast { !it.espacio } + 1
        fun anchoNatural(ats: List<Atomo>): Double {
            var s = 0.0
            for (i in 0 until finDe(ats)) s += ats[i].caja.ancho
            return s
        }
        var bloque = visuales.maxOfOrNull { anchoNatural(it.atomos) } ?: 0.0
        if (partir) bloque = max(bloque, o.anchoMax)
        val piezas = ArrayList<Ecuacion.Pieza>()
        var y = 0.0
        val inicio = DoubleArray(n)
        val visto = BooleanArray(n)
        for (vis in visuales) {
            val k = vis.renglon
            if (!visto[k]) { visto[k] = true; inicio[k] = y }
            val ats = vis.atomos
            val arriba = max(altoBase * 0.55, ats.maxOfOrNull { it.caja.eje } ?: 0.0)
            val abajo = max(altoBase * 0.45, ats.maxOfOrNull { it.caja.alto - it.caja.eje } ?: 0.0)
            val natural = anchoNatural(ats)
            val fin = finDe(ats)
            var huecos = 0
            for (i in 0 until fin) if (ats[i].espacio) huecos++
            var x: Double
            var reparto = 0.0
            when (vis.alineado) {
                Alineado.IZQUIERDA -> x = 0.0
                Alineado.CENTRO -> x = (bloque - natural) / 2
                Alineado.DERECHA -> x = bloque - natural
                Alineado.JUSTIFICADO -> {
                    x = 0.0
                    if (!vis.ultimo && huecos > 0) reparto = max(0.0, bloque - natural) / huecos
                }
            }
            x = max(0.0, x)
            val desde = piezas.size
            for (i in 0 until fin) {
                val a = ats[i]
                piezas += a.caja.piezasEn(x, y + arriba - a.caja.eje)
                x += a.caja.ancho
                if (a.espacio) x += reparto
            }
            // Sin reparto, las palabras seguidas del mismo estilo van en UNA pieza: menos que
            // pintar, y quien pinta con otra letra espacia con la suya en vez de montarlas.
            if (reparto == 0.0) {
                val renglon = ArrayList(piezas.subList(desde, piezas.size))
                while (piezas.size > desde) piezas.removeAt(piezas.size - 1)
                piezas += juntarPalabras(renglon)
            }
            y += arriba + abajo
        }
        return Maqueta(bloque, max(y, altoBase), piezas, inicio.toList())
    }

    /** Junta los textos seguidos de un renglón con la misma cara, tamaño, color y altura. */
    private fun juntarPalabras(v: List<Ecuacion.Pieza>): List<Ecuacion.Pieza> {
        val out = ArrayList<Ecuacion.Pieza>(v.size)
        var ultimo = -1
        for (p in v) {
            if (p is Ecuacion.Pieza.Texto && ultimo >= 0) {
                val a = out[ultimo] as Ecuacion.Pieza.Texto
                if (abs(a.y - p.y) < 0.01 && abs(a.tam - p.tam) < 0.01 && a.cursiva == p.cursiva &&
                    a.negrita == p.negrita && a.color == p.color && abs(a.x + a.ancho - p.x) < 0.05
                ) {
                    out[ultimo] = a.copy(texto = a.texto + p.texto, ancho = a.ancho + p.ancho)
                    continue
                }
            }
            if (p is Ecuacion.Pieza.Texto) ultimo = out.size
            out += p
        }
        return out
    }

    /** **Lo que mide un texto rico** (su caja). Un renglón vacío mide uno de letra. */
    fun medida(fuente: String, o: Opciones, medir: Medidor): Pair<Double, Double> {
        val m = componer(fuente, o, medir)
        return max(m.ancho, medir.ancho(" ", o.tam, o.negrita, o.cursiva)) to m.alto
    }

    /** Las opciones de un elemento de texto, con el ancho donde partir. */
    fun opcionesDe(e: Element, anchoMax: Double = SIN_PARTIR): Opciones = Opciones(
        tam = e.fontSize ?: 20.0, negrita = e.negrita, cursiva = e.cursiva, tachado = e.tachado,
        alineacion = e.textAlign, anchoMax = anchoMax
    )

    /**
     * Dónde se parte un texto rico al pintarlo: uno suelto no se parte (como en el PC); uno
     * dentro de una figura, al ancho de su caja con un poco de holgura, que otra letra (la del
     * PC) puede medir un pelo distinto y partir de más sería peor que rozar el borde.
     */
    fun anchoAlPintar(e: Element): Double =
        if (e.containerId == null) SIN_PARTIR else e.width * 1.02 + 1.0

    /**
     * **Le pone a un texto lo escrito**: si lleva marcas, la fuente va a [Element.textoRico], el
     * `text` a su plano y la caja a lo compuesto; si no, el texto tal cual y sin fuente. Es lo
     * que hace el editor al salir de la caja. [anchoMax]: el de la figura que lo contiene.
     */
    fun conFuente(e: Element, fuente: String, medir: Medidor, anchoMax: Double = SIN_PARTIR): Element {
        if (!tieneMarcas(fuente)) return e.copy(text = fuente, textoRico = null)
        val (ancho, alto) = medida(fuente, opcionesDe(e, anchoMax), medir)
        return e.copy(text = plano(fuente), textoRico = fuente, width = ancho, height = alto)
    }

    // ---------------------------------------------------------------------
    // Editar
    // ---------------------------------------------------------------------

    /** Los colores de los botones (y de Ctrl+Mayús+1..6), los del PC. */
    val COLORES_DE_ATAJO = listOf("rojo", "azul", "verde", "naranja", "morado", "gris")

    /** Lo escrito tras un cambio: el texto y lo elegido (o el cursor, si son iguales). */
    data class Edicion(val texto: String, val desde: Int, val hasta: Int)

    /**
     * **Envuelve lo elegido** entre [antes] y [despues] (negrita pone `**`, un color pone
     * `\color{rojo}{` y `}`) y lo deja elegido, ya envuelto por dentro, para encadenar otro.
     * Sin nada elegido, pone los dos y deja el cursor en medio. El `envolver` del PC.
     */
    fun envolver(texto: String, desde: Int, hasta: Int, antes: String, despues: String): Edicion {
        val a = minOf(desde, hasta).coerceIn(0, texto.length)
        val b = maxOf(desde, hasta).coerceIn(0, texto.length)
        val nuevo = texto.substring(0, a) + antes + texto.substring(a, b) + despues + texto.substring(b)
        return if (a == b) Edicion(nuevo, a + antes.length, a + antes.length)
        else Edicion(nuevo, a + antes.length, b + antes.length)
    }

    /** Lo que hace cada botón (o atajo) de formato. */
    enum class Formato(val antes: String, val despues: String) {
        NEGRITA("**", "**"), CURSIVA("*", "*"), SUBRAYADO("<u>", "</u>"), TACHADO("~~", "~~"), FORMULA("$", "$")
    }

    fun envolver(texto: String, desde: Int, hasta: Int, f: Formato): Edicion =
        envolver(texto, desde, hasta, f.antes, f.despues)

    fun conColor(texto: String, desde: Int, hasta: Int, color: String): Edicion =
        envolver(texto, desde, hasta, "\\color{$color}{", "}")
}
