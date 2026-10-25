package com.forge.pixpin.motor

import kotlin.math.max

/**
 * **El texto rico del lienzo** (25-oct-2026): un texto con tramos de otro color, en negrita,
 * cursiva, subrayados, tachados o de otro tamaño, renglones centrados o justificados, y
 * **fórmulas en línea** (`$m\sphericalangle AOB = 90^\circ$`) compuestas como en la pizarra.
 * Es el `texto_rico.rs` del PC portado tal cual; el formato está en
 * `docs/texto-rico-del-lienzo.md` del PC.
 *
 * Lo pidió el usuario para las láminas de geometría: «Nos piden m∢COD = **X**» con la X en rojo,
 * «A̅B̅» con su raya, «mAC⌒» con su arco, «45° = (2a+2b)/2» con la fracción de verdad.
 *
 * ## Un solo elemento
 *
 * No hay tipo nuevo: es el `text` de siempre con la clave [Element.textoRico] (la **fuente**).
 * El `text` guarda su **versión plana** ([plano]), la que lee quien no entiende el formato
 * (Excalidraw, un Android viejo, la búsqueda, la voz). Y la **regla de coherencia**: si
 * `plano(textoRico) != text`, alguien que no entiende el formato cambió el `text`; la fuente está
 * vieja y **gana `text`** ([fuenteVigente]). Por eso [plano] tiene que dar lo mismo que el del
 * PC carácter a carácter, y por eso el lector de fórmulas es el del PC ([FormulaRica]).
 *
 * Lo que no se entiende **se ve tal cual**: un `$` sin cerrar es un dólar, un mandato desconocido
 * sale con su barra, unas llaves rotas no se comen nada. Nunca falla ni se cuelga.
 *
 * Sin Android: medir letras lo pone quien pinta ([Medidor]); pintar está en [PintorDeTextoRico].
 */
object TextoRico {

    /** Lo más largo que se compone una fuente: un libro pegado no puede congelar el lienzo. */
    const val MAX_FUENTE = 20_000

    /** Cómo va escrito un tramo. `color == null`: el del elemento. `escala`: veces su tamaño. */
    data class EstiloTramo(
        val color: Int? = null,
        val negrita: Boolean = false,
        val cursiva: Boolean = false,
        val subrayado: Boolean = false,
        val tachado: Boolean = false,
        val escala: Double = 1.0
    )

    /** Un trozo de renglón con su estilo. */
    sealed interface Tramo {
        val estilo: EstiloTramo
        data class Texto(val texto: String, override val estilo: EstiloTramo) : Tramo
        /** Una fórmula (sin sus dólares). `alta`: entre `$$`. */
        data class Formula(val fuente: String, override val estilo: EstiloTramo, val alta: Boolean) : Tramo
    }

    /**
     * A qué lado va un renglón. `JUSTIFICADO` no existe en `textAlign` (solo tiene tres palabras):
     * por eso va en la fuente, renglón a renglón, y no en el elemento.
     */
    enum class Alineado { IZQUIERDA, CENTRO, DERECHA, JUSTIFICADO }

    /** Un renglón de la fuente (lo que hay entre dos saltos). `alineado == null`: el del elemento. */
    data class Renglon(val alineado: Alineado?, val tramos: List<Tramo>)

    // ---------------------------------------------------------------------
    // Leer
    // ---------------------------------------------------------------------

    /** **Lee una fuente rica** a sus renglones y tramos. Nunca falla. */
    fun leer(fuente: String): List<Renglon> = recortar(fuente).split('\n').map { leerRenglon(it) }

    private fun recortar(fuente: String): String =
        if (fuente.codePointCount(0, fuente.length) <= MAX_FUENTE) fuente
        else fuente.substring(0, fuente.offsetByCodePoints(0, MAX_FUENTE))

    /** Las marcas de alineación, la más larga primero (`\centering` antes que `\center`). */
    private val DIRECTIVAS = listOf(
        "\\centro" to Alineado.CENTRO, "\\center" to Alineado.CENTRO,
        "\\centering" to Alineado.CENTRO, "\\derecha" to Alineado.DERECHA,
        "\\flushright" to Alineado.DERECHA, "\\izquierda" to Alineado.IZQUIERDA,
        "\\flushleft" to Alineado.IZQUIERDA, "\\justificado" to Alineado.JUSTIFICADO,
        "\\justify" to Alineado.JUSTIFICADO
    ).sortedByDescending { it.first.length }

    private fun leerRenglon(lineaCruda: String): Renglon {
        val linea = lineaCruda.removeSuffix("\r")
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
        leerTramos(resto, EstiloTramo(), tramos)
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

    private fun leerTramos(c: String, est: EstiloTramo, out: MutableList<Tramo>) {
        val buf = StringBuilder()
        fun soltar() {
            if (buf.isNotEmpty()) {
                out += Tramo.Texto(buf.toString(), est)
                buf.clear()
            }
        }
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            if (ch == '\\') {
                val d = c.getOrNull(i + 1)
                if (d != null && !d.isLetter()) {
                    // `\$`, `\*`, `\\`, `\{`: el carácter tal cual.
                    buf.append(d)
                    i += 2
                    continue
                }
                if (d != null) {
                    val m = mandato(c, i, est)
                    if (m != null) {
                        soltar()
                        leerTramos(c.substring(m.desde, m.hasta), m.estilo, out)
                        i = m.fin
                        continue
                    }
                }
                buf.append('\\')
                i++
                continue
            }
            if (ch == '$') {
                val f = formula(c, i)
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
            val marca = marcaEn(c, i)
            if (marca != null) {
                val j = cierre(c, i + marca.abre, marca.cierra)
                if (j != null) {
                    soltar()
                    leerTramos(c.substring(i + marca.abre, j), marca.cambio(est), out)
                    i = j + marca.cierra.length
                    continue
                }
            }
            buf.append(ch)
            i++
        }
        soltar()
    }

    private class Marca(val abre: Int, val cierra: String, val cambio: (EstiloTramo) -> EstiloTramo)

    /** Si en `i` empieza una marca de las de Markdown que abre de verdad. */
    private fun marcaEn(c: String, i: Int): Marca? {
        fun detrasNoBlanco(k: Int) = c.getOrNull(k)?.isWhitespace() == false
        if (c.startsWith("<u>", i)) return Marca(3, "</u>") { it.copy(subrayado = true) }
        if (c.startsWith("**", i) && detrasNoBlanco(i + 2)) return Marca(2, "**") { it.copy(negrita = true) }
        if (c.startsWith("~~", i) && detrasNoBlanco(i + 2)) return Marca(2, "~~") { it.copy(tachado = true) }
        // `*` y `_` solos: como en CommonMark, no abren pegados a una letra por delante
        // (`2*3*4` y `nombre_de_fichero` siguen siendo eso).
        val ch = c[i]
        if ((ch == '*' || ch == '_') && c.getOrNull(i + 1) != ch && detrasNoBlanco(i + 1) &&
            (i == 0 || !c[i - 1].isLetterOrDigit())
        ) return Marca(1, ch.toString()) { it.copy(cursiva = true) }
        return null
    }

    /**
     * Dónde se cierra una marca abierta antes de [desde]: pegada a algo escrito y, para `*` y `_`,
     * sin una letra detrás. Se saltan los escapes y las fórmulas.
     */
    private fun cierre(c: String, desde: Int, marca: String): Int? {
        var k = desde
        while (k < c.length) {
            if (c[k] == '\\') {
                k += 2
                continue
            }
            if (c[k] == '$') {
                val f = formula(c, k)
                if (f != null) {
                    k = f.fin
                    continue
                }
            }
            if (c.startsWith(marca, k) && k > desde) {
                val antesOk = marca.startsWith('<') || !c[k - 1].isWhitespace()
                val simple = marca.length == 1
                val despuesOk = !simple || c.getOrNull(k + 1)
                    .let { it == null || (!it.isLetterOrDigit() && it != marca[0]) }
                if (antesOk && despuesOk) return k
            }
            k++
        }
        return null
    }

    private class Hallada(val fin: Int, val fuente: String, val alta: Boolean)

    /**
     * Una fórmula que empieza en `i` (un `$`), con las reglas de Pandoc para no confundir dinero
     * con fórmulas: el `$` que abre no va seguido de un blanco; el que cierra no va detrás de un
     * blanco ni delante de una cifra. Si no cierra, no es fórmula.
     */
    private fun formula(c: String, i: Int): Hallada? {
        if (c.getOrNull(i + 1) == '$') {
            val desde = i + 2
            var k = desde
            while (k + 1 < c.length) {
                if (c[k] == '\\') {
                    k += 2
                    continue
                }
                if (c[k] == '$' && c[k + 1] == '$') {
                    val f = c.substring(desde, k)
                    return if (f.trim().isNotEmpty()) Hallada(k + 2, f.trim(), true) else null
                }
                k++
            }
            return null
        }
        val primero = c.getOrNull(i + 1) ?: return null
        if (primero.isWhitespace()) return null
        var k = i + 1
        while (k < c.length) {
            if (c[k] == '\\') {
                k += 2
                continue
            }
            if (c[k] == '$') {
                if (c[k - 1].isWhitespace() || c.getOrNull(k + 1)?.let { it in '0'..'9' } == true) return null
                return Hallada(k + 1, c.substring(i + 1, k), false)
            }
            k++
        }
        return null
    }

    private class Mandato(val fin: Int, val estilo: EstiloTramo, val desde: Int, val hasta: Int)

    /** Un mandato de estilo fuera de una fórmula (`\color{rojo}{X}`), o `null` si no lo es. */
    private fun mandato(c: String, i: Int, est: EstiloTramo): Mandato? {
        var k = i + 1
        val nombre = StringBuilder()
        while (k < c.length && c[k].isLetter()) {
            nombre.append(c[k])
            k++
        }
        val n = nombre.toString()
        var argumento: String? = null
        if (n == "color" || n == "textcolor" || n == "tam") {
            val ll = llaves(c, k) ?: return null
            k = ll.third
            argumento = c.substring(ll.first, ll.second)
        }
        val sub = when (n) {
            // Un color que no se conoce deja el de fuera: el texto se ve.
            "color", "textcolor" -> colorDeNombre(argumento.orEmpty())?.let { est.copy(color = it) } ?: est
            "tam" -> {
                val f = argumento.orEmpty().trim().replace(',', '.').toDoubleOrNull() ?: return null
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
        val (a, b, fin) = llaves(c, k) ?: return null
        return Mandato(fin, sub, a, b)
    }

    /** Unas llaves equilibradas en `k` (con blancos delante): dentro (desde, hasta) y lo de después. */
    private fun llaves(c: String, desde: Int): Triple<Int, Int, Int>? {
        var k = desde
        while (c.getOrNull(k) == ' ') k++
        if (c.getOrNull(k) != '{') return null
        val dentro = k + 1
        var prof = 0
        while (k < c.length) {
            when (c[k]) {
                '\\' -> k++
                '{' -> prof++
                '}' -> {
                    prof--
                    if (prof == 0) return Triple(dentro, k, k + 1)
                }
            }
            k++
        }
        return null
    }

    // ---------------------------------------------------------------------
    // El texto plano y la regla de coherencia
    // ---------------------------------------------------------------------

    /** **El texto plano de reserva**: lo que se guarda en `text` para quien no sabe componer. */
    fun plano(fuente: String): String = leer(fuente).joinToString("\n") { r ->
        r.tramos.joinToString("") { t ->
            when (t) {
                is Tramo.Texto -> t.texto
                is Tramo.Formula -> FormulaRica.legible(FormulaRica.leer(t.fuente)).trim()
            }
        }
    }

    /**
     * **Si una fuente lleva algo que componer**: una fórmula, un tramo con estilo, una alineación
     * o un escape. Si no, es un texto de los de siempre y se guarda sin fuente rica.
     */
    fun tieneMarcas(fuente: String): Boolean {
        if (fuente.any { it in CajasRicas.ANGULOS }) return true
        if (fuente.none { it == '$' || it == '*' || it == '_' || it == '~' || it == '<' || it == '\\' }) return false
        val renglones = leer(fuente)
        return renglones.any { r ->
            r.alineado != null || r.tramos.any { it is Tramo.Formula || it.estilo != EstiloTramo() }
        } || plano(fuente) != fuente
    }

    /** Los planos ya calculados: [fuenteVigente] corre por texto y por fotograma. */
    private val planos = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 256
    }

    private fun planoRecordado(fuente: String): String {
        synchronized(planos) { planos[fuente]?.let { return it } }
        val p = plano(fuente)
        synchronized(planos) { planos[fuente] = p }
        return p
    }

    /**
     * **La fuente rica que vale** de un texto, o `null` si no la tiene o está vieja: la regla de
     * coherencia del PC. Si `plano(textoRico) != text`, alguien que no entiende el formato
     * cambió el texto, y gana lo que escribió.
     */
    fun fuenteVigente(e: Element): String? {
        val r = e.textoRico?.takeIf { it.isNotEmpty() } ?: return null
        if (e.type != ElementType.TEXT) return null
        return r.takeIf { planoRecordado(it) == (e.text ?: "") }
    }

    /** Lo que se abre para editar: la fuente si vale, y si no el texto de siempre. */
    fun paraEditar(e: Element): String = fuenteVigente(e) ?: e.text.orEmpty()

    /** Lo que se escribe al cerrar el editor: el `text` plano y la fuente (o `null` si no hay marcas). */
    data class Escrito(val texto: String, val fuente: String?)

    /** **Lo escrito, listo para guardar**: con marcas, fuente + plano; sin ellas, el texto tal cual. */
    fun escrito(fuente: String): Escrito =
        if (tieneMarcas(fuente)) Escrito(plano(fuente), fuente) else Escrito(fuente, null)

    /**
     * **Un color por su nombre** (español o inglés, masculino, femenino o plural) o `#rrggbb` /
     * `#rgb`, en ARGB opaco. La misma paleta que el PC.
     */
    fun colorDeNombre(nombre: String): Int? {
        val t = nombre.trim().lowercase()
        fun hex(h: String): Int? {
            fun v(i: Int, n: Int): Int? = if (i + n <= h.length) h.substring(i, i + n).toIntOrNull(16) else null
            val (r, g, b) = when (h.length) {
                3 -> Triple((v(0, 1) ?: return null) * 17, (v(1, 1) ?: return null) * 17, (v(2, 1) ?: return null) * 17)
                6, 8 -> Triple(v(0, 2) ?: return null, v(2, 2) ?: return null, v(4, 2) ?: return null)
                else -> return null
            }
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        if (t.startsWith("#")) return hex(t.substring(1))
        val candidatos = mutableListOf(t)
        for (sufijo in listOf("es", "s")) if (t.endsWith(sufijo)) candidatos += t.removeSuffix(sufijo)
        for (c in candidatos.toList()) if (c.endsWith("a")) candidatos += c.dropLast(1) + "o"
        for (c in candidatos) {
            val h = when (c) {
                "negro", "black" -> "1e1e1e"
                "blanco", "white" -> "ffffff"
                "rojo", "red" -> "e03131"
                "verde", "green" -> "2f9e44"
                "azul", "blue" -> "1971c2"
                "amarillo", "yellow" -> "f08c00"
                "naranja", "orange" -> "e8590c"
                "morado", "violeta", "purple", "violet" -> "6741d9"
                "rosa", "pink", "magenta" -> "c2255c"
                "gris", "gray", "grey" -> "868e96"
                "marron", "marrón", "cafe", "café", "brown" -> "8b5a2b"
                "celeste", "cyan", "cian", "teal" -> "0c8599"
                else -> null
            } ?: continue
            return hex(h)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Componer
    // ---------------------------------------------------------------------

    /** Cuánto avanza un trozo de texto con su letra (lo pone quien tiene las fuentes). */
    fun interface Medidor {
        fun ancho(texto: String, tam: Double, negrita: Boolean, cursiva: Boolean): Double
    }

    /** Con qué se compone. [anchoMax] `null`: no se parten los renglones (un texto suelto). */
    data class Opciones(
        val tam: Double,
        val negrita: Boolean = false,
        val cursiva: Boolean = false,
        val tachado: Boolean = false,
        val alineacion: TextAlign? = null,
        val anchoMax: Double? = null
    )

    /** Lo compuesto, con la esquina en (0, 0): lo que mide, sus piezas y dónde empieza cada renglón. */
    data class Maqueta(
        val ancho: Double,
        val alto: Double,
        val piezas: List<CajasRicas.Pieza>,
        val renglones: List<Double>
    )

    /** El alto de un renglón: el interlineado de Excalidraw, el mismo del texto plano de aquí. */
    fun altoDeRenglon(t: Double) = t * INTERLINEADO

    private class Atomo(val caja: CajasRicas.Caja, val espacio: Boolean)

    /** Grosor y altura de la raya del tachado, en veces el tamaño (las del PC). */
    private const val GROSOR_DEL_TACHADO = 0.06
    private const val ALTURA_DEL_TACHADO = 0.30

    private fun anchoReal(s: String, t: Double, negrita: Boolean, cursiva: Boolean, m: Medidor) =
        if (s.isEmpty()) 0.0 else m.ancho(s, t, negrita, cursiva)

    private fun medirFormula(m: Medidor) = CajasRicas.Medir { s, t ->
        anchoReal(s, t, false, false, m) to altoDeRenglon(t)
    }

    /** Una caja de letras de un tramo, con su raya de subrayado o tachado. */
    private fun cajaDeTexto(s: String, t: Double, e: EstiloTramo, m: Medidor): CajasRicas.Caja {
        val ancho = anchoReal(s, t, e.negrita, e.cursiva, m)
        val alto = altoDeRenglon(t)
        val piezas = ArrayList<CajasRicas.Pieza>()
        piezas += CajasRicas.Pieza.Texto(0.0, 0.0, ancho, alto, s, t, e.cursiva, e.negrita, e.color)
        val grosor = max(t * GROSOR_DEL_TACHADO, 1.0)
        for ((si, y) in listOf(e.subrayado to t * 1.1, e.tachado to t - t * ALTURA_DEL_TACHADO)) {
            if (si && ancho > 0) {
                piezas += CajasRicas.Pieza.Raya(listOf(Pt(0.0, y), Pt(ancho, y)), grosor, e.color)
            }
        }
        return CajasRicas.Caja(ancho, alto, alto * 0.55, piezas)
    }

    private fun atomos(r: Renglon, o: Opciones, m: Medidor): List<Atomo> {
        val v = ArrayList<Atomo>()
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
                    // Un signo de ángulo suelto en el texto, dibujado como en las fórmulas.
                    if (ch in CajasRicas.ANGULOS) {
                        if (trozo.isNotEmpty()) {
                            v += Atomo(cajaDeTexto(trozo.toString(), t, e, m), enBlanco == true)
                            trozo.clear()
                        }
                        enBlanco = null
                        var c = CajasRicas.angulo(ch, t, medirFormula(m))
                        e.color?.let { c = c.tenida(it) }
                        v += Atomo(c, false)
                        continue
                    }
                    val blanco = ch == ' ' || ch == '\t'
                    if (enBlanco != null && enBlanco != blanco) {
                        v += Atomo(cajaDeTexto(trozo.toString(), t, e, m), enBlanco == true)
                        trozo.clear()
                    }
                    enBlanco = blanco
                    trozo.append(if (ch == '\t') ' ' else ch)
                }
                if (trozo.isNotEmpty()) v += Atomo(cajaDeTexto(trozo.toString(), t, e, m), enBlanco == true)
            }
            is Tramo.Formula -> {
                val t = o.tam * tr.estilo.escala
                val arbol = FormulaRica.leer(tr.fuente)
                var c = CajasRicas.deFormula(arbol, t, medirFormula(m), !tr.alta)
                // Un poco de aire a cada lado, como deja TeX entre una fórmula y el texto.
                c = CajasRicas.fila(listOf(CajasRicas.hueco(t * 0.04), c, CajasRicas.hueco(t * 0.04)))
                tr.estilo.color?.let { c = c.tenida(it) }
                if (tr.estilo.negrita || o.negrita) c = c.enNegrita()
                v += Atomo(c, false)
            }
        }
        return v
    }

    private class Visual(val atomos: List<Atomo>, val al: Alineado, val ultimo: Boolean, val k: Int)

    /** **Compone una fuente rica**: renglones partidos (si hay ancho), alineados por el eje. */
    fun componer(fuente: String, o: Opciones, m: Medidor): Maqueta {
        val renglones = leer(fuente)
        val partir = o.anchoMax != null
        val anchoMax = o.anchoMax ?: Double.MAX_VALUE
        val altoBase = altoDeRenglon(o.tam)
        val visuales = ArrayList<Visual>()
        val porDefecto = when (o.alineacion) {
            TextAlign.CENTER -> Alineado.CENTRO
            TextAlign.RIGHT -> Alineado.DERECHA
            else -> Alineado.IZQUIERDA
        }
        val n = renglones.size
        renglones.forEachIndexed { k, r ->
            val al = r.alineado ?: porDefecto
            val finDeParrafo = k + 1 == n || renglones[k + 1].tramos.isEmpty()
            val ats = atomos(r, o, m)
            if (!partir) {
                visuales += Visual(ats, al, finDeParrafo, k)
                return@forEachIndexed
            }
            var linea = ArrayList<Atomo>()
            var ancho = 0.0
            for (a in ats) {
                if (!a.espacio && linea.isNotEmpty() && ancho + a.caja.ancho > anchoMax) {
                    while (linea.lastOrNull()?.espacio == true) linea.removeAt(linea.size - 1)
                    visuales += Visual(linea, al, false, k)
                    linea = ArrayList()
                    ancho = 0.0
                }
                // El blanco donde se partió no empieza el renglón.
                if (a.espacio && linea.isEmpty() && visuales.lastOrNull()?.k == k) continue
                ancho += a.caja.ancho
                linea += a
            }
            visuales += Visual(linea, al, true, k)
        }
        fun finDeTinta(ats: List<Atomo>) = ats.indexOfLast { !it.espacio } + 1
        fun anchoNatural(ats: List<Atomo>) = ats.subList(0, finDeTinta(ats)).sumOf { it.caja.ancho }
        var bloque = visuales.fold(0.0) { mx, v -> max(mx, anchoNatural(v.atomos)) }
        if (partir) bloque = max(bloque, anchoMax)
        val piezas = ArrayList<CajasRicas.Pieza>()
        var y = 0.0
        val inicioDeRenglon = DoubleArray(n)
        val visto = BooleanArray(n)
        for (v in visuales) {
            if (!visto[v.k]) {
                visto[v.k] = true
                inicioDeRenglon[v.k] = y
            }
            val ats = v.atomos
            val arriba = ats.fold(altoBase * 0.55) { mx, a -> max(mx, a.caja.eje) }
            val abajo = ats.fold(altoBase * 0.45) { mx, a -> max(mx, a.caja.alto - a.caja.eje) }
            val natural = anchoNatural(ats)
            val fin = finDeTinta(ats)
            val huecos = ats.subList(0, fin).count { it.espacio }
            var x: Double
            var reparto = 0.0
            when {
                v.al == Alineado.CENTRO -> x = (bloque - natural) / 2
                v.al == Alineado.DERECHA -> x = bloque - natural
                v.al == Alineado.JUSTIFICADO && !v.ultimo && huecos > 0 -> {
                    x = 0.0
                    reparto = max(bloque - natural, 0.0) / huecos
                }
                else -> x = 0.0
            }
            x = max(x, 0.0)
            val desde = piezas.size
            for (a in ats.subList(0, fin)) {
                piezas += a.caja.piezasEn(x, y + arriba - a.caja.eje)
                x += a.caja.ancho
                if (a.espacio) x += reparto
            }
            // Sin reparto, las palabras seguidas del mismo estilo van en una pieza.
            if (reparto == 0.0) {
                val renglon = ArrayList(piezas.subList(desde, piezas.size))
                while (piezas.size > desde) piezas.removeAt(piezas.size - 1)
                piezas += juntarPalabras(renglon)
            }
            y += arriba + abajo
        }
        return Maqueta(bloque, max(y, altoBase), piezas, inicioDeRenglon.toList())
    }

    /** Junta los textos seguidos de un renglón con la misma cara, tamaño, color y altura. */
    private fun juntarPalabras(v: List<CajasRicas.Pieza>): List<CajasRicas.Pieza> {
        val out = ArrayList<CajasRicas.Pieza>(v.size)
        var ultimo = -1
        for (p in v) {
            if (p is CajasRicas.Pieza.Texto && ultimo >= 0) {
                val a = out[ultimo] as CajasRicas.Pieza.Texto
                if (kotlin.math.abs(a.y - p.y) < 0.01 && kotlin.math.abs(a.tam - p.tam) < 0.01 &&
                    a.cursiva == p.cursiva && a.negrita == p.negrita && a.color == p.color &&
                    kotlin.math.abs(a.x + a.ancho - p.x) < 0.05
                ) {
                    out[ultimo] = a.copy(texto = a.texto + p.texto, ancho = a.ancho + p.ancho)
                    continue
                }
            }
            if (p is CajasRicas.Pieza.Texto) ultimo = out.size
            out += p
        }
        return out
    }

    /** **Lo que mide un texto rico** suelto (sin partir). Un renglón vacío mide uno de letra. */
    fun medida(fuente: String, o: Opciones, m: Medidor): Pair<Double, Double> {
        val mq = componer(fuente, o.copy(alineacion = null, anchoMax = null), m)
        return max(mq.ancho, anchoReal(" ", o.tam, o.negrita, o.cursiva, m)) to mq.alto
    }

    /** Las opciones de componer de un texto del lienzo: su letra, su estilo y su alineación. */
    fun opcionesDe(e: Element, anchoMax: Double? = null) = Opciones(
        tam = e.fontSize ?: 20.0,
        negrita = e.negrita,
        cursiva = e.cursiva,
        tachado = e.tachado,
        alineacion = e.textAlign,
        anchoMax = anchoMax
    )

    // ---------------------------------------------------------------------
    // Editar: las marcas alrededor de lo elegido
    // ---------------------------------------------------------------------

    /** Un texto con su selección (`desde..hasta`, en UTF-16, como la del cuadro de escribir). */
    data class Edicion(val texto: String, val desde: Int, val hasta: Int)

    /**
     * **Pone unas marcas alrededor de lo elegido**, como Ctrl+B del PC. Sin nada elegido las pone
     * y deja el cursor en medio, para seguir escribiendo dentro.
     */
    fun envolver(ed: Edicion, abre: String, cierra: String): Edicion {
        val a = minOf(ed.desde, ed.hasta).coerceIn(0, ed.texto.length)
        val b = maxOf(ed.desde, ed.hasta).coerceIn(0, ed.texto.length)
        val nuevo = ed.texto.substring(0, a) + abre + ed.texto.substring(a, b) + cierra + ed.texto.substring(b)
        return if (a == b) Edicion(nuevo, a + abre.length, a + abre.length)
        else Edicion(nuevo, a + abre.length, b + abre.length)
    }

    /** La palabra de cada alineación, como se escribe al principio de un renglón. */
    fun directiva(a: Alineado): String = when (a) {
        Alineado.IZQUIERDA -> "\\izquierda"
        Alineado.CENTRO -> "\\centro"
        Alineado.DERECHA -> "\\derecha"
        Alineado.JUSTIFICADO -> "\\justificado"
    }

    /**
     * **Alinea los renglones tocados por la selección**: les quita la marca que tuvieran y les
     * pone la de [a]; con `null`, se quedan con la del elemento. La selección se corre con el texto.
     */
    fun alinear(ed: Edicion, a: Alineado?): Edicion {
        val t = ed.texto
        val selA = minOf(ed.desde, ed.hasta).coerceIn(0, t.length)
        val selB = maxOf(ed.desde, ed.hasta).coerceIn(0, t.length)
        val ini = t.lastIndexOf('\n', selA - 1) + 1
        val finRel = t.indexOf('\n', selB).let { if (it < 0) t.length else it }
        val bloque = t.substring(ini, finRel).split('\n')
        var desde = ed.desde
        var hasta = ed.hasta
        var cursor = ini
        val nuevos = bloque.map { r ->
            val sinBlancos = r.trimStart()
            var quitado = 0
            var cuerpo = r
            for ((d, _) in DIRECTIVAS) {
                if (sinBlancos.startsWith(d) && sinBlancos.getOrNull(d.length)?.isLetter() != true) {
                    val resto = sinBlancos.substring(d.length).removePrefix(" ")
                    quitado = r.length - resto.length
                    cuerpo = resto
                    break
                }
            }
            val pone = a?.let { directiva(it) + " " } ?: ""
            val delta = pone.length - quitado
            // Lo que caía detrás del principio de este renglón se corre lo que cambie.
            if (desde > cursor) desde = maxOf(cursor + pone.length, desde + delta)
            if (hasta > cursor) hasta = maxOf(cursor + pone.length, hasta + delta)
            cursor += pone.length + cuerpo.length + 1
            pone + cuerpo
        }
        val nuevo = t.substring(0, ini) + nuevos.joinToString("\n") + t.substring(finRel)
        return Edicion(nuevo, desde.coerceIn(0, nuevo.length), hasta.coerceIn(0, nuevo.length))
    }
}
