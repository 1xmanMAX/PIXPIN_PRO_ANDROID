package com.forge.pixpin.markdown

/**
 * Tipo de decoración de un tramo de texto.
 *
 * Cada uno lleva su [bandera], un bit propio, para que un trozo de texto pueda
 * declarar **varios estilos a la vez** con un solo entero. Es lo que permite que
 * `**muy _importante_**` salga en negrita y cursiva en lugar de tener que elegir.
 * Ver [Tramo].
 */
enum class SpanKind(val bandera: Int) {
    BOLD(1 shl 0),
    ITALIC(1 shl 1),
    STRIKE(1 shl 2),
    CODE(1 shl 3),
    LINK(1 shl 4),

    /** Tapado hasta que se toca. Para una contraseña o un resultado en una nota. */
    SPOILER(1 shl 5)
}

/**
 * Tramo con estilo dentro de un texto **ya limpio de marcas**: [start] y [end]
 * apuntan al texto sin los asteriscos, no al original.
 *
 * Dos de estos **pueden solaparse y anidarse**: la negrita de fuera y la cursiva
 * de dentro son dos tramos distintos sobre las mismas letras. Para pintarlos hay
 * que aplanarlos antes; de eso se encarga [tramos].
 */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val kind: SpanKind,
    val url: String? = null
)

/** Una línea ya interpretada: el texto sin marcas y los tramos que lo decoran. */
data class InlineText(val text: String, val spans: List<InlineSpan> = emptyList())

/** Cómo se alinea el contenido de una celda. Es su `TableModel.ALIGN_*`. */
enum class Alineacion { IZQUIERDA, CENTRO, DERECHA }

/** Y a qué altura. Es su `TableModel.VALIGN_*`. */
enum class AlturaEnCelda { ARRIBA, MEDIO, ABAJO }

/**
 * Una celda, calcada de su `TL_iv.pageTableCell`.
 *
 * Lleva **todo por celda**, no por columna ni por fila: si es cabecera, cómo se
 * alinea, a qué altura, y cuántas columnas y filas ocupa. Ese último par es lo
 * que permite fusionar, y es la razón de que el modelo tenga esta forma y no la
 * de una simple rejilla de textos.
 */
data class Celda(
    val contenido: InlineText = InlineText(""),
    val cabecera: Boolean = false,
    val alineacion: Alineacion = Alineacion.IZQUIERDA,
    val altura: AlturaEnCelda = AlturaEnCelda.ARRIBA,
    /** Cuántas columnas ocupa. 1 es lo normal; más es una fusión. */
    val anchoEnColumnas: Int = 1,
    /** Cuántas filas ocupa. */
    val altoEnFilas: Int = 1
) {
    /** ¿Usa algo que una tabla de Markdown no sabe decir? */
    val esAvanzada: Boolean
        get() = anchoEnColumnas > 1 || altoEnFilas > 1 ||
            altura != AlturaEnCelda.ARRIBA
}

/** Qué clase de archivo lleva un bloque de medio, deducido por su extensión. */
enum class ClaseDeMedio { IMAGEN, VIDEO, AUDIO, ARCHIVO }

/**
 * Las cajas que envuelven otros bloques.
 *
 * Cada una es uno de sus `pageBlock`: [PLEGABLE] es `pageBlockDetails`, [PIE] es
 * `pageBlockFooter`, [DESTACADO] es `pageBlockPullquote`, y las dos alineaciones
 * salen de que sus celdas y párrafos llevan `align_center` y `align_right`.
 */
enum class TipoDeCaja { PLEGABLE, PIE, DESTACADO, CENTRO, DERECHA }

sealed interface MarkdownBlock {
    /** [level] va de 1 a 6, como sus seis `ArticleHeading`. */
    data class Heading(val level: Int, val content: InlineText) : MarkdownBlock
    data class Paragraph(val content: InlineText) : MarkdownBlock
    data class Bullet(val content: InlineText) : MarkdownBlock
    data class Numbered(val number: Int, val content: InlineText) : MarkdownBlock
    data class Quote(val content: InlineText) : MarkdownBlock

    /**
     * Un bloque de código. [lenguaje] es lo que se escribió pegado a la valla de
     * apertura (```` ```kotlin ````); vacío si no se puso nada.
     */
    data class Code(val text: String, val lenguaje: String = "") : MarkdownBlock
    data object Rule : MarkdownBlock

    /** Una casilla. Su `ArticleListChecklist`, en sintaxis de GitHub. */
    data class Tarea(val hecha: Boolean, val content: InlineText) : MarkdownBlock

    /**
     * Una tabla, calcada de su `pageBlockTable`.
     *
     * [filas] guarda **solo las celdas ancla**: una celda fusionada ocupa el
     * hueco de sus vecinas y esas ya no están en la lista, igual que en su
     * `TableModel`, donde al fusionar se borran del `block.rows`. Por eso una
     * fila puede tener menos celdas que columnas tiene la tabla.
     *
     * [titulo] es su `pageBlockTable.title`, el rótulo que va encima.
     */
    data class Tabla(
        val filas: List<List<Celda>>,
        val titulo: InlineText = InlineText(""),
        /** Cuántas columnas tiene la rejilla, contando las fusiones. */
        val columnas: Int = filas.maxOfOrNull { f -> f.sumOf { it.anchoEnColumnas } } ?: 0
    ) : MarkdownBlock {
        /** ¿Hace falta HTML para escribirla, o basta con Markdown? */
        val esAvanzada: Boolean
            get() = titulo.text.isNotEmpty() ||
                filas.any { fila -> fila.any { it.esAvanzada } } ||
                // Cabecera que no sea exactamente «toda la primera fila y nada
                // más»: eso Markdown tampoco lo sabe decir.
                filas.withIndex().any { (i, fila) -> fila.any { it.cabecera != (i == 0) } } ||
                alineacionDesigual

        /**
         * ¿Hay alguna columna donde no todas las celdas se alineen igual?
         *
         * Una tabla de Markdown guarda **una alineación por columna**, en la
         * fila de guiones, y nada más. Así que centrar una celda suelta de la
         * tercera fila no se puede escribir con barras: al guardar se perdía y
         * al volver a leer la celda salía como estaba. Era el motivo de que
         * alinear pareciera no hacer nada.
         */
        private val alineacionDesigual: Boolean
            get() = filas.any { fila ->
                fila.withIndex().any { (c, celda) ->
                    celda.alineacion != (filas.firstOrNull()?.getOrNull(c)?.alineacion
                        ?: Alineacion.IZQUIERDA)
                }
            }
    }

    /** Una fórmula. Su `pageBlockMath`, que allí es de pago. */
    data class Formula(val latex: String) : MarkdownBlock

    /** Una imagen, un vídeo, un audio o un archivo adjunto. */
    data class Medio(
        val clase: ClaseDeMedio,
        val ruta: String,
        val alt: String = ""
    ) : MarkdownBlock

    /** Una caja con bloques dentro: plegable, pie, destacado o alineación. */
    data class Caja(
        val tipo: TipoDeCaja,
        val titulo: String,
        val dentro: List<MarkdownBlock>
    ) : MarkdownBlock
}

/**
 * Un subconjunto de Markdown, suficiente para lo que se pega en un pin desde
 * unas notas o desde un chat.
 *
 * Es propio y no una librería por dos motivos. Uno, aquí **todo tiene que ser
 * función del zoom** —el pin de texto escala en proporción al pellizcar— y una
 * librería no deja gobernar los tamaños. Y dos, sin Compose ni Android dentro se
 * puede probar en la JVM, como el resto de la lógica pura del proyecto.
 *
 * La regla que gobierna todo el parser: **no perder texto nunca**. Una marca sin
 * cerrar se queda tal cual se escribió. El formateo está siempre activo y una
 * nota mal interpretada que se coma caracteres sería mucho peor que ver un
 * asterisco de más.
 *
 * ## Lo que entiende
 *
 * | | |
 * |---|---|
 * | `**x**` `__x__` | negrita |
 * | `*x*` `_x_` | cursiva |
 * | `~~x~~` | tachado |
 * | `` `x` `` | código, y **dentro no se interpreta nada** |
 * | `\|\|x\|\|` | tapado hasta que se toca |
 * | `[t](u)`, `https://…`, `www.…` | enlace |
 * | `#` `##` `###` | títulos |
 * | `-` `*` `+`, `1.` | listas |
 * | `>` | cita, y las líneas seguidas son **una sola** |
 * | ```` ```lenguaje ```` | bloque de código |
 *
 * Las marcas **se anidan**: `**muy _importante_**` sale negrita y cursiva a la
 * vez. Eso es dos tramos que se pisan, y para pintarlo hay que aplanarlos —ver
 * [tramosDe], que es donde vive esa idea, tomada de Telegram.
 *
 * El tapado es de ellos también. Aquí sirve para lo mismo que el mosaico del
 * motor pero en texto: una nota sobre una captura puede llevar una contraseña
 * que no tiene por qué verse cada vez que se mira la pantalla.
 *
 * Donde sí se diverge de Telegram es en `__x__`: allí es cursiva, aquí negrita,
 * porque es lo que dice Markdown de toda la vida y estas notas se pegan desde
 * cualquier sitio, no se escriben en un chat de Telegram.
 */
object Markdown {

    private val NUMBERED = Regex("""^(\d{1,3})[.)](?:\s+(.*))?$""")
    private val RULE = Regex("""^\s*([-*_])\1{2,}\s*$""")

    /** Prefijos que arrancan una url suelta. Van de más largo a más corto. */
    private val AUTOLINK_PREFIXES = listOf("https://", "http://", "www.")

    /**
     * Puntuación que, al final de una url, es de la frase y no del enlace.
     * `https://ejemplo.com.` termina en la url, no en el punto.
     */
    private const val TRAILING = ".,;:!?)]}>\"'"

    fun parse(source: String): List<MarkdownBlock> =
        parseLineas(source.replace("\r\n", "\n").split('\n'))

    /**
     * El cuerpo del parser, sobre líneas ya partidas.
     *
     * Va aparte de [parse] porque las cajas —`:::plegable`, `:::pie`…— llevan
     * bloques dentro y hay que volver a entrar con lo que haya entre las vallas.
     * Una caja dentro de otra funciona por el mismo motivo.
     */
    private fun parseLineas(lines: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks += MarkdownBlock.Paragraph(parseInline(paragraph.joinToString(" ")))
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                // Lo que va pegado a la valla es el lenguaje. Antes se tiraba; se
                // guarda porque es lo único que dice de qué es el bloque cuando
                // se lee la nota meses después.
                val lenguaje = trimmed.removePrefix("```").trim()
                // Sin valla de cierre se llega hasta el final: tragarse el resto
                // del pin en silencio sería peor que mostrarlo como código.
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body += lines[i]
                    i++
                }
                i++ // la valla de cierre, si la hay
                blocks += MarkdownBlock.Code(body.joinToString("\n"), lenguaje)
                continue
            }

            // Una caja: `:::plegable Título` … `:::`. Es la sintaxis de
            // contenedor de remark, no una invención, y sirve para las cinco
            // cosas que Markdown no tiene y su editor sí: plegables, pies,
            // destacados y las dos alineaciones.
            cajaQueAbre(trimmed)?.let { (tipo, titulo) ->
                flushParagraph()
                val dentro = mutableListOf<String>()
                var nivel = 1
                i++
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (cajaQueAbre(t) != null) nivel++
                    if (t == ":::") {
                        nivel--
                        if (nivel == 0) break
                    }
                    dentro += lines[i]
                    i++
                }
                i++ // la valla de cierre, si la hay
                blocks += MarkdownBlock.Caja(tipo, titulo, parseLineas(dentro))
                continue
            }

            // Una fórmula entre `$$`, que es como se escriben en LaTeX y en
            // cualquier sitio donde se peguen mates.
            if (trimmed == "$$") {
                flushParagraph()
                val cuerpo = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim() != "$$") {
                    cuerpo += lines[i]
                    i++
                }
                i++
                blocks += MarkdownBlock.Formula(cuerpo.joinToString("\n"))
                continue
            }

            // Una tabla de Markdown: la fila de guiones de debajo la delata.
            if (esTabla(lines, i)) {
                flushParagraph()
                val cuerpo = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    cuerpo += lines[i].trim()
                    i++
                }
                // De interpretarla se encarga Tablas, que es quien sabe de
                // tablas. Tener aquí una segunda opinión era pedir que un día
                // dijeran cosas distintas.
                Tablas.leer(cuerpo.joinToString("\n"))?.let { blocks += it }
                continue
            }

            // Una tabla de HTML, que es como se guardan las que fusionan celdas
            // o llevan título. Ver [Tablas].
            if (trimmed.startsWith("<table", ignoreCase = true)) {
                flushParagraph()
                val cuerpo = mutableListOf<String>()
                while (i < lines.size) {
                    cuerpo += lines[i]
                    val cerro = lines[i].contains("</table>", ignoreCase = true)
                    i++
                    if (cerro) break
                }
                Tablas.leer(cuerpo.joinToString("\n"))?.let { blocks += it }
                continue
            }

            // Las líneas de cita seguidas son **una sola cita**. Una por línea
            // dejaba la barra lateral partida en trozos, como si fueran citas
            // distintas de sitios distintos.
            if (esCita(trimmed)) {
                flushParagraph()
                val cuerpo = mutableListOf<String>()
                while (i < lines.size && esCita(lines[i].trim())) {
                    cuerpo += sinMarcaDeCita(lines[i].trim())
                    i++
                }
                blocks += MarkdownBlock.Quote(parseInline(cuerpo.joinToString("\n")))
                continue
            }

            when {
                trimmed.isEmpty() -> flushParagraph()

                RULE.matches(trimmed) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Rule
                }

                // Los seis niveles, como sus seis ArticleHeading. Se prueban de
                // más almohadillas a menos: `## ` empieza por `# `.
                TITULO.matches(trimmed) -> {
                    flushParagraph()
                    val m = TITULO.find(trimmed)!!
                    blocks += MarkdownBlock.Heading(
                        m.groupValues[1].length,
                        parseInline(m.groupValues[2])
                    )
                }

                // La casilla va antes que la viñeta: `- [ ] x` empieza por `- `.
                TAREA.matches(trimmed) -> {
                    flushParagraph()
                    val m = TAREA.find(trimmed)!!
                    blocks += MarkdownBlock.Tarea(
                        hecha = m.groupValues[1].lowercase() == "x",
                        content = parseInline(m.groupValues[2])
                    )
                }

                medioDe(trimmed) != null -> {
                    flushParagraph()
                    blocks += medioDe(trimmed)!!
                }

                isBullet(trimmed) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Bullet(
                        parseInline(trimmed.drop(2))
                    )
                }

                NUMBERED.matches(trimmed) -> {
                    flushParagraph()
                    val m = NUMBERED.find(trimmed)!!
                    blocks += MarkdownBlock.Numbered(
                        m.groupValues[1].toInt(), parseInline(m.groupValues[2])
                    )
                }

                else -> paragraph += trimmed
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    /**
     * Una viñeta necesita marcador Y espacio: sin el espacio, `*cursiva*` al
     * empezar una línea se convertiría en viñeta y se comería el asterisco.
     */
    private fun isBullet(line: String): Boolean =
        // Con contenido, o vacía. Una viñeta vacía es lo que deja pulsar intro
        // dentro de una lista, y sin reconocerla el renglón nuevo dejaría de ser
        // lista en cuanto se mirase.
        (line.length > 2 && line[0] in "-*+" && line[1] == ' ') ||
            (line.length <= 2 && line.isNotEmpty() && line[0] in "-*+" &&
                (line.length == 1 || line[1] == ' '))

    /** El espacio tras el `>` es opcional: mucha gente pega citas sin él. */
    private fun esCita(line: String): Boolean = line.startsWith(">")

    private val TITULO = Regex("""^(#{1,6})\s+(.*)$""")
    private val TAREA = Regex("""^[-*+]\s+\[([ xX])]\s*(.*)$""")
    private val MEDIO = Regex("""^!\[([^]]*)]\(([^)]*)\)$""")

    /** `:::plegable Título` → el tipo y su título. Null si la línea no abre. */
    private fun cajaQueAbre(line: String): Pair<TipoDeCaja, String>? {
        if (!line.startsWith(":::") || line == ":::") return null
        val resto = line.removePrefix(":::").trim()
        val nombre = resto.substringBefore(' ').lowercase()
        val titulo = resto.substringAfter(' ', "").trim()
        val tipo = when (nombre) {
            "plegable", "detalles" -> TipoDeCaja.PLEGABLE
            "pie", "nota" -> TipoDeCaja.PIE
            "destacado", "resaltado" -> TipoDeCaja.DESTACADO
            "centro", "centrar" -> TipoDeCaja.CENTRO
            "derecha" -> TipoDeCaja.DERECHA
            else -> return null
        }
        return tipo to titulo
    }

    /**
     * `![alt](ruta)` en una línea suya es un medio, y **la extensión decide qué
     * clase**: así una sola marca de Markdown sirve para las cuatro que ellos
     * tienen separadas en `pageBlockPhoto`, `Video`, `Audio` y el adjunto.
     */
    private fun medioDe(line: String): MarkdownBlock.Medio? {
        val m = MEDIO.find(line) ?: return null
        val ruta = m.groupValues[2].trim()
        if (ruta.isEmpty()) return null
        return MarkdownBlock.Medio(claseDeMedio(ruta), ruta, m.groupValues[1])
    }

    private fun claseDeMedio(ruta: String): ClaseDeMedio {
        val ext = ruta.substringAfterLast('.', "").lowercase().substringBefore('?')
        return when (ext) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg" -> ClaseDeMedio.IMAGEN
            "mp4", "mkv", "webm", "mov", "avi", "3gp" -> ClaseDeMedio.VIDEO
            "mp3", "ogg", "oga", "m4a", "wav", "flac", "opus", "aac" -> ClaseDeMedio.AUDIO
            else -> ClaseDeMedio.ARCHIVO
        }
    }

    /**
     * ¿Empieza aquí una tabla?
     *
     * Hace falta la fila de guiones **debajo** de la primera. Sin exigirla, una
     * línea suelta con barras —una ruta, una expresión— se convertiría en una
     * tabla de una fila y el texto se vería descuartizado en columnas.
     */
    private fun esTabla(lines: List<String>, i: Int): Boolean {
        if (!lines[i].trim().startsWith("|")) return false
        val siguiente = lines.getOrNull(i + 1)?.trim() ?: return false
        if (!siguiente.startsWith("|")) return false
        return celdasDe(siguiente).isNotEmpty() &&
            celdasDe(siguiente).all { Regex("""^:?-{1,}:?$""").matches(it.trim()) }
    }

    private fun celdasDe(fila: String): List<String> =
        fila.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }


    private fun sinMarcaDeCita(line: String): String =
        line.removePrefix(">").removePrefix(" ")

    /**
     * Recorre la línea de una pasada buscando aperturas de marca. Cuando
     * encuentra una, busca su cierre; si no lo hay, escribe la marca tal cual y
     * sigue — así nunca se pierde nada.
     *
     * El código inline va primero a propósito: dentro de un acento grave no se
     * interpreta nada, que es lo que se espera al pegar una ruta o una fórmula.
     */
    fun parseInline(line: String): InlineText = parseInline(line, 0)

    /**
     * Hasta dónde se mira dentro de una marca dentro de otra marca.
     *
     * El anidamiento se hace con recursión, y una nota pegada de cualquier sitio
     * puede traer una ristra de asteriscos que no lo son de nada. Sin tope, mil
     * asteriscos serían mil llamadas y la pila se acaba. Pasado el tope el
     * contenido se copia tal cual: se ve un asterisco de más, que es exactamente
     * lo que promete la regla de no perder texto nunca.
     */
    private const val ANIDAMIENTO_MAXIMO = 8

    private fun parseInline(line: String, profundidad: Int): InlineText {
        val out = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        var i = 0

        while (i < line.length) {
            val rest = line.length - i

            // Una barra invertida delante de una marca la desarma: el carácter
            // siguiente se copia tal cual y no abre nada. Es lo que permite que
            // «2 \* 3» se guarde y vuelva sin convertirse en cursiva. Ver
            // [Inline.escapar].
            if (line[i] == '\\' && rest >= 2) {
                out.append(line[i + 1])
                i += 2
                continue
            }

            if (line[i] == '`') {
                val close = line.indexOf('`', i + 1)
                if (close > i) {
                    val start = out.length
                    out.append(line, i + 1, close)
                    spans += InlineSpan(start, out.length, SpanKind.CODE)
                    i = close + 1
                    continue
                }
            }

            // Las marcas de dos caracteres, con su longitud mínima. El orden
            // entre ellas da igual: empiezan por caracteres distintos.
            val doble = when {
                rest >= 4 && line.startsWith("**", i) -> "**" to SpanKind.BOLD
                rest >= 4 && line.startsWith("__", i) -> "__" to SpanKind.BOLD
                rest >= 4 && line.startsWith("~~", i) -> "~~" to SpanKind.STRIKE
                rest >= 4 && line.startsWith("||", i) -> "||" to SpanKind.SPOILER
                else -> null
            }
            if (doble != null) {
                val (marca, kind) = doble
                val close = line.indexOf(marca, i + 2)
                if (close > i + 1) {
                    appendStyled(line, i + 2, close, kind, out, spans, profundidad)
                    i = close + 2
                    continue
                }
            }

            if (line[i] == '*' || line[i] == '_') {
                val close = line.indexOf(line[i], i + 1)
                // El cierre pegado a la apertura (`**`) no es cursiva vacía: es
                // una negrita rota, y se deja pasar como texto.
                if (close > i + 1) {
                    appendStyled(line, i + 1, close, SpanKind.ITALIC, out, spans, profundidad)
                    i = close + 1
                    continue
                }
            }

            if (line[i] == '[') {
                val closeText = line.indexOf(']', i + 1)
                if (closeText > i && closeText + 1 < line.length && line[closeText + 1] == '(') {
                    val closeUrl = line.indexOf(')', closeText + 2)
                    if (closeUrl > closeText) {
                        appendStyled(
                            line, i + 1, closeText, SpanKind.LINK, out, spans, profundidad,
                            url = line.substring(closeText + 2, closeUrl)
                        )
                        i = closeUrl + 1
                        continue
                    }
                }
            }

            // Una url suelta. Va la última de las reglas porque es la más
            // ambiciosa: dentro de un `código` o de un [enlace] con corchetes ya
            // se ha consumido el texto y aquí no llega, que es lo que se quiere.
            val auto = autolinkAt(line, i)
            if (auto != null) {
                val start = out.length
                out.append(line, i, auto.end)
                spans += InlineSpan(start, out.length, SpanKind.LINK, url = auto.url)
                i = auto.end
                continue
            }

            out.append(line[i])
            i++
        }
        return InlineText(out.toString(), spans)
    }

    private class Auto(val end: Int, val url: String)

    /**
     * ¿Empieza en [from] una url suelta? Devuelve dónde acaba y con qué url.
     *
     * El prefijo tiene que caer en frontera de palabra: sin eso, «httpsomething»
     * se comería medio texto como si fuera un enlace.
     */
    private fun autolinkAt(line: String, from: Int): Auto? {
        if (from > 0 && (line[from - 1].isLetterOrDigit() || line[from - 1] == '.')) return null
        val prefix = AUTOLINK_PREFIXES.firstOrNull { line.startsWith(it, from) } ?: return null

        var end = from + prefix.length
        while (end < line.length && !line[end].isWhitespace()) end++
        // La puntuación de la frase se queda fuera del enlace.
        while (end > from + prefix.length && line[end - 1] in TRAILING) end--
        // Solo el prefijo no es una url: hace falta algo de dominio detrás.
        if (end <= from + prefix.length) return null

        val raw = line.substring(from, end)
        return Auto(end, if (prefix == "www.") "https://$raw" else raw)
    }

    /**
     * Copia el contenido entre marcas y le apunta su tramo — **mirando dentro**,
     * para que `**muy _importante_**` salga negrita y cursiva a la vez.
     *
     * Telegram lo consigue de otra forma: pasa un patrón por el texto entero,
     * borra sus marcas, y vuelve a pasar el siguiente sobre lo que queda, así que
     * al llegar la cursiva los asteriscos de la negrita ya no están y el
     * anidamiento le sale gratis. A cambio tiene que ir corrigiendo a mano el
     * desplazamiento de todo lo encontrado antes, con un `-= 4` y un `-= 2` que
     * dan por hecho que las marcas miden dos caracteres.
     *
     * Aquí se recorre una sola vez y se baja en recursión, que no necesita
     * corregir nada: lo de dentro se interpreta contra su propio trozo y sus
     * posiciones se suman a [start] al subir. El resultado es el mismo y no hay
     * ningún número mágico atado al tamaño de las marcas.
     *
     * Dentro de un acento grave no se baja nunca —ver [parseInline]—, que es lo
     * que se espera al pegar una ruta o una fórmula.
     */
    private fun appendStyled(
        line: String,
        from: Int,
        to: Int,
        kind: SpanKind,
        out: StringBuilder,
        spans: MutableList<InlineSpan>,
        profundidad: Int,
        url: String? = null
    ) {
        val start = out.length
        if (profundidad >= ANIDAMIENTO_MAXIMO) {
            out.append(line, from, to)
        } else {
            val dentro = parseInline(line.substring(from, to), profundidad + 1)
            out.append(dentro.text)
            dentro.spans.forEach {
                spans += it.copy(start = it.start + start, end = it.end + start)
            }
        }
        spans += InlineSpan(start, out.length, kind, url)
    }
}
