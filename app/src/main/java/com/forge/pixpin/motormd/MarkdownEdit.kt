package com.forge.pixpin.motormd

/** Texto y selección tras aplicar un formato. */
data class EditResult(val text: String, val selStart: Int, val selEnd: Int)

/**
 * Aplica marcas de Markdown a la selección de un editor.
 *
 * Es matemática de índices y nada más, así que vive aquí y no en el composable:
 * se comprueba en la JVM y no hace falta un teclado delante para saber si el
 * cursor acaba donde debe.
 *
 * ## De dónde salen las reglas
 *
 * De `EditTextCaption.toggleStyleForSelection` de Telegram, que es donde tienen
 * escrito qué hace un botón de formato cuando lo aprietas:
 *
 * - **Alternar por cobertura**: si el estilo ya cubre la selección entera, el
 *   botón lo quita; si no, lo pone. Un solo botón para las dos cosas.
 * - **El código es excluyente**: al ponerlo se quitan negrita, cursiva, tachado
 *   y tapado, y al poner cualquiera de esos se quita el código. Un trozo `así`
 *   sale a un solo espacio entre letras y en negrita no significa nada.
 * - **Quitar formato lo quita todo**, marcas de línea incluidas.
 *
 * La diferencia con ellos es dónde vive el formato. Telegram lo guarda en spans,
 * aparte del texto, y aquí el texto **es** el formato: lo que se escribe son los
 * asteriscos. Así que donde ellos preguntan a los spans, aquí se mira alrededor
 * de la selección; y donde ellos pintan, aquí se escriben caracteres. El
 * resultado de apretar el botón es el mismo.
 */
object MarkdownEdit {

    /**
     * Envuelve la selección entre [marker] (negrita, cursiva, código…). Si ya
     * estaba envuelta, la desenvuelve: el mismo botón pone y quita.
     *
     * Sin selección, inserta las dos marcas y deja el cursor en medio, listo
     * para escribir.
     */
    fun wrap(text: String, selStart: Int, selEnd: Int, marker: String): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)

        val before = text.substring(0, start)
        val selected = text.substring(start, end)
        val after = text.substring(end)

        // Ya envuelta por dentro de la selección: se quitan las marcas.
        if (envueltaPorDentro(selected, marker)) {
            val naked = selected.substring(marker.length, selected.length - marker.length)
            return EditResult(before + naked + after, start, start + naked.length)
        }
        // Envuelta por fuera: las marcas están justo alrededor de la selección.
        if (envueltaPorFuera(before, after, marker)) {
            val trimmedBefore = before.dropLast(marker.length)
            val trimmedAfter = after.drop(marker.length)
            return EditResult(
                trimmedBefore + selected + trimmedAfter,
                start - marker.length,
                end - marker.length
            )
        }
        val out = before + marker + selected + marker + after
        return EditResult(out, start + marker.length, end + marker.length)
    }

    /**
     * ¿Está la selección envuelta en [marker], por dentro o por fuera?
     *
     * El detalle que hay que cuidar es que `*` **también es el principio de**
     * `**`. Sin mirarlo, seleccionar `x` dentro de `**x**` y darle a cursiva
     * encontraría marcas de cursiva donde solo hay una negrita, y en vez de
     * añadir cursiva se comería un asterisco de cada lado y dejaría la negrita
     * rota. Por eso una marca no vale si el carácter que la precede —o el que la
     * sigue, según el lado— es el mismo: entonces es parte de una marca mayor.
     */
    fun envuelta(text: String, selStart: Int, selEnd: Int, marker: String): Boolean {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        return envueltaPorDentro(text.substring(start, end), marker) ||
            envueltaPorFuera(text.substring(0, start), text.substring(end), marker)
    }

    private fun envueltaPorDentro(selected: String, marker: String): Boolean {
        if (selected.length < marker.length * 2) return false
        if (!selected.startsWith(marker) || !selected.endsWith(marker)) return false
        return !seRepite(selected, marker.length, marker) &&
            !seRepiteAlReves(selected, selected.length - marker.length, marker)
    }

    private fun envueltaPorFuera(before: String, after: String, marker: String): Boolean {
        if (!before.endsWith(marker) || !after.startsWith(marker)) return false
        return !seRepiteAlReves(before, before.length - marker.length, marker) &&
            !seRepite(after, marker.length, marker)
    }

    /** ¿Sigue en [pos] otra copia de [marker]? Entonces la de antes era mayor. */
    private fun seRepite(s: String, pos: Int, marker: String): Boolean =
        s.startsWith(marker, pos)

    /** Lo mismo hacia atrás: ¿acaba en [pos] otra copia de [marker]? */
    private fun seRepiteAlReves(s: String, pos: Int, marker: String): Boolean =
        pos >= marker.length && s.startsWith(marker, pos - marker.length)

    // ---- La barra de formato ----

    /**
     * Qué formatos cubren la selección ahora mismo, para encender sus botones.
     *
     * Es su `getCurrentStyle`: sin esto un botón de alternar es una moneda al
     * aire, porque no se sabe si el toque va a poner o a quitar hasta que ya ha
     * pasado. En los de línea hace falta que **todas** las líneas tocadas lo
     * lleven, igual que ellos exigen que el estilo cubra la selección entera.
     */
    fun estiloDeLaSeleccion(text: String, selStart: Int, selEnd: Int): Set<Formato> {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        val salida = mutableSetOf<Formato>()

        Formato.entries.forEach { f ->
            when {
                f.esEnvolvente -> if (envuelta(text, start, end, f.marca!!)) salida += f
                f.esDeLinea -> {
                    val lineas = lineasTocadas(text, start, end)
                    if (lineas.isNotEmpty() && lineas.all { tienePrefijo(it, f) }) salida += f
                }
                else -> Unit
            }
        }

        // El código de envolver gana al de bloque y viceversa nunca: si toda la
        // selección está dentro de una valla, lo que manda es el bloque.
        if (dentroDeUnBloque(text, start)) {
            salida -= Formato.CODIGO
            salida += Formato.BLOQUE
        }
        return salida
    }

    /**
     * Aprieta un botón de la barra.
     *
     * [dato] es lo que el formato necesite de fuera: la url del enlace o la
     * fecha ya escrita. Se pasa hecho para que aquí no haya ni relojes ni
     * diálogos y todo esto se pueda comprobar en la JVM.
     */
    fun aplicar(
        formato: Formato,
        text: String,
        selStart: Int,
        selEnd: Int,
        dato: String? = null
    ): EditResult = when (formato) {
        Formato.NEGRITA, Formato.CURSIVA, Formato.TACHADO, Formato.TAPADO ->
            envolver(text, selStart, selEnd, formato.marca!!, quitandoCodigo = true)

        Formato.CODIGO ->
            envolver(text, selStart, selEnd, formato.marca!!, quitandoCodigo = false)

        Formato.ENLACE -> enlace(text, selStart, selEnd, dato.orEmpty())
        Formato.CITA, Formato.TITULO, Formato.LISTA, Formato.NUMERADA ->
            prefijoEnLineas(text, selStart, selEnd, formato)

        Formato.BLOQUE -> bloqueDeCodigo(text, selStart, selEnd)
        Formato.FECHA -> insertar(text, selStart, selEnd, dato.orEmpty())
        Formato.QUITAR -> quitarFormato(text, selStart, selEnd)
    }

    /**
     * Envolver, respetando que el código no se mezcla.
     *
     * Es la regla de su `toggleStyleForSelection`: poner código borra los demás
     * estilos de dentro, y poner cualquier otro saca lo de dentro del código.
     * Un trozo `en negrita y a un espacio` no significa nada, y dejar que
     * convivan solo sirve para que salga algo que nadie quería.
     */
    private fun envolver(
        text: String,
        selStart: Int,
        selEnd: Int,
        marca: String,
        quitandoCodigo: Boolean
    ): EditResult {
        // Quitarlo es quitarlo y ya: nada que reconciliar.
        if (envuelta(text, selStart, selEnd, marca)) return wrap(text, selStart, selEnd, marca)

        val codigo = Formato.CODIGO.marca!!
        val limpio = if (quitandoCodigo) {
            if (envuelta(text, selStart, selEnd, codigo)) {
                wrap(text, selStart, selEnd, codigo)
            } else {
                null
            }
        } else {
            // Va a ser código: dentro no puede quedar ninguna otra marca.
            val start = selStart.coerceIn(0, text.length)
            val end = selEnd.coerceIn(start, text.length)
            val desnudo = sinMarcasDeLinea(text.substring(start, end))
            if (desnudo == text.substring(start, end)) {
                null
            } else {
                EditResult(
                    text.substring(0, start) + desnudo + text.substring(end),
                    start,
                    start + desnudo.length
                )
            }
        }

        return if (limpio == null) {
            wrap(text, selStart, selEnd, marca)
        } else {
            wrap(limpio.text, limpio.selStart, limpio.selEnd, marca)
        }
    }

    /**
     * `[lo seleccionado](url)`. Si ya era un enlace, lo deshace y deja el texto.
     *
     * Con la url vacía se escriben los paréntesis igual y el cursor se queda
     * dentro: pegar la dirección después es un gesto menos que volver a empezar.
     */
    fun enlace(text: String, selStart: Int, selEnd: Int, url: String): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        val selected = text.substring(start, end)

        yaEsEnlace(selected)?.let { etiqueta ->
            return EditResult(
                text.substring(0, start) + etiqueta + text.substring(end),
                start,
                start + etiqueta.length
            )
        }

        val out = "[$selected]($url)"
        val nuevo = text.substring(0, start) + out + text.substring(end)
        return if (url.isEmpty()) {
            // Dentro de los paréntesis, listo para pegar.
            val cursor = start + selected.length + 3
            EditResult(nuevo, cursor, cursor)
        } else {
            EditResult(nuevo, start + 1, start + 1 + selected.length)
        }
    }

    /** La etiqueta si [s] es exactamente un `[texto](url)`, o null. */
    private fun yaEsEnlace(s: String): String? {
        if (!s.startsWith("[") || !s.endsWith(")")) return null
        val cierre = s.indexOf("](")
        if (cierre <= 0) return null
        // Un `[a](b) y [c](d)` entero no es un enlace, son dos.
        if (s.indexOf("](", cierre + 1) >= 0) return null
        return s.substring(1, cierre)
    }

    /**
     * Pone o quita una marca de línea en **todas** las líneas que toca la
     * selección, no solo en la del cursor.
     *
     * Telegram hace lo mismo con su cita: seleccionas un párrafo, le das, y va
     * entero. Marcar línea a línea un texto de diez renglones es exactamente el
     * trabajo que la barra tendría que estar ahorrando.
     */
    fun prefijoEnLineas(
        text: String,
        selStart: Int,
        selEnd: Int,
        formato: Formato
    ): EditResult {
        val prefijo = formato.prefijo ?: return EditResult(text, selStart, selEnd)
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)

        val desde = inicioDeLinea(text, start)
        val hasta = finDeLinea(text, end)
        val lineas = text.substring(desde, hasta).split('\n')

        val todasLoTienen = lineas.all { tienePrefijo(it, formato) }
        val nuevas = lineas.mapIndexed { i, linea ->
            when {
                todasLoTienen -> linea.removeRange(0, largoDelPrefijo(linea, formato))
                tienePrefijo(linea, formato) -> linea
                // La numerada se numera de verdad: poner «1.» diez veces sería
                // una lista de diez unos.
                formato == Formato.NUMERADA -> "${i + 1}. $linea"
                else -> prefijo + linea
            }
        }

        val cuerpo = nuevas.joinToString("\n")
        return EditResult(
            text.substring(0, desde) + cuerpo + text.substring(hasta),
            desde,
            desde + cuerpo.length
        )
    }

    /** Encierra las líneas tocadas entre vallas, o las saca si ya lo estaban. */
    fun bloqueDeCodigo(text: String, selStart: Int, selEnd: Int): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        val desde = inicioDeLinea(text, start)
        val hasta = finDeLinea(text, end)
        val cuerpo = text.substring(desde, hasta)

        val lineas = cuerpo.split('\n')
        if (lineas.size >= 2 &&
            lineas.first().trim().startsWith("```") &&
            lineas.last().trim().startsWith("```")
        ) {
            val dentro = lineas.subList(1, lineas.size - 1).joinToString("\n")
            return EditResult(
                text.substring(0, desde) + dentro + text.substring(hasta),
                desde,
                desde + dentro.length
            )
        }

        val nuevo = "```\n$cuerpo\n```"
        return EditResult(
            text.substring(0, desde) + nuevo + text.substring(hasta),
            desde + 4,
            desde + 4 + cuerpo.length
        )
    }

    /** Mete [s] donde esté el cursor, sustituyendo lo que hubiera seleccionado. */
    fun insertar(text: String, selStart: Int, selEnd: Int, s: String): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        return EditResult(
            text.substring(0, start) + s + text.substring(end),
            start + s.length,
            start + s.length
        )
    }

    /**
     * Deja la selección en texto pelado: su `makeSelectedRegular`.
     *
     * Las marcas de dentro las quita el propio parser, que es quien sabe cuáles
     * son de verdad y cuáles son un asterisco de multiplicar. Reutilizarlo aquí
     * también hereda su promesa: de aquí no sale nunca menos texto del que
     * entró, solo menos marcas.
     */
    fun quitarFormato(text: String, selStart: Int, selEnd: Int): EditResult {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(start, text.length)
        if (start == end) return EditResult(text, start, end)

        val limpio = text.substring(start, end)
            .split('\n')
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n") { sinMarcasDeLinea(sinPrefijoNinguno(it)) }

        return EditResult(
            text.substring(0, start) + limpio + text.substring(end),
            start,
            start + limpio.length
        )
    }

    private fun sinMarcasDeLinea(s: String): String =
        s.split('\n').joinToString("\n") { Markdown.parseInline(it).text }

    /**
     * Quita la marca del principio de la línea —almohadillas, `>`, viñeta,
     * número, casilla— y **solo eso**.
     *
     * Es lo que hace falta para cambiar un bloque de tipo sin tocar lo escrito:
     * un título con negrita dentro que pasa a párrafo tiene que seguir teniendo
     * su negrita. Ver [Menus.convertir].
     */
    fun sinPrefijoDeLinea(linea: String): String = sinPrefijoNinguno(linea)

    private val CASILLA_AL_PRINCIPIO = Regex("""^[-*+]\s+\[[ xX]]\s?""")

    private fun sinPrefijoNinguno(linea: String): String {
        // La casilla va antes que la viñeta: `- [ ] x` empieza por `- `, y
        // quitando solo la viñeta se quedaría un `[ ]` suelto a la vista.
        CASILLA_AL_PRINCIPIO.find(linea)?.let { return linea.removeRange(0, it.value.length) }

        Formato.entries.filter { it.esDeLinea }.forEach { f ->
            val largo = largoDelPrefijo(linea, f)
            if (largo > 0) return linea.removeRange(0, largo)
        }
        return linea
    }

    private val NUMERADA_AL_PRINCIPIO = Regex("""^\d{1,3}[.)]\s""")

    private fun tienePrefijo(linea: String, formato: Formato): Boolean =
        largoDelPrefijo(linea, formato) > 0

    /** Cuánto ocupa la marca de línea de [formato] en [linea]; 0 si no está. */
    private fun largoDelPrefijo(linea: String, formato: Formato): Int = when (formato) {
        Formato.NUMERADA -> NUMERADA_AL_PRINCIPIO.find(linea)?.value?.length ?: 0
        // Los seis niveles, como los seis del catálogo de bloques. Con tres se
        // quedaban fuera el 4, el 5 y el 6, y convertir uno de esos a párrafo
        // dejaba las almohadillas puestas.
        Formato.TITULO -> Regex("""^#{1,6}\s""").find(linea)?.value?.length ?: 0
        // La viñeta admite los tres marcadores de Markdown.
        Formato.LISTA -> if (linea.length > 2 && linea[0] in "-*+" && linea[1] == ' ') 2 else 0
        else -> {
            val p = formato.prefijo ?: return 0
            if (linea.startsWith(p)) p.length else 0
        }
    }

    private fun lineasTocadas(text: String, start: Int, end: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.substring(inicioDeLinea(text, start), finDeLinea(text, end)).split('\n')
    }

    private fun inicioDeLinea(text: String, pos: Int): Int =
        text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }

    private fun finDeLinea(text: String, pos: Int): Int =
        text.indexOf('\n', pos).let { if (it < 0) text.length else it }

    /** ¿Cae [pos] dentro de un bloque vallado? Se cuentan las vallas de antes. */
    private fun dentroDeUnBloque(text: String, pos: Int): Boolean =
        text.substring(0, pos.coerceIn(0, text.length))
            .split('\n')
            .count { it.trim().startsWith("```") } % 2 == 1

    /**
     * Pone [prefix] al principio de la línea donde está el cursor (títulos,
     * viñetas, cita). Si ya lo tenía, lo quita.
     */
    fun togglePrefix(text: String, cursor: Int, prefix: String): EditResult {
        val pos = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }

        val rest = text.substring(lineStart)
        return if (rest.startsWith(prefix)) {
            EditResult(
                text.removeRange(lineStart, lineStart + prefix.length),
                (pos - prefix.length).coerceAtLeast(lineStart),
                (pos - prefix.length).coerceAtLeast(lineStart)
            )
        } else {
            EditResult(
                text.substring(0, lineStart) + prefix + rest,
                pos + prefix.length,
                pos + prefix.length
            )
        }
    }
}
