package com.forge.pixpin.markdown

/**
 * Las tablas, con el modelo de Telegram entero.
 *
 * ## Por qué HTML
 *
 * Su `pageTableCell` lleva `colspan`, `rowspan`, `header`, `align` y `valign`
 * **por celda**. La sintaxis de tabla de Markdown no sabe decir ninguna de las
 * cinco: solo tiene una alineación por columna y una cabecera que es siempre la
 * primera fila. Para hacerlo igual hay que guardarlo de otra forma.
 *
 * Esa forma es **HTML**, y no es un invento: Markdown admite HTML dentro desde
 * el primer día y lo entiende cualquier lector. Encima el mapeo es exacto, campo
 * a campo:
 *
 * | Suyo | Aquí |
 * |---|---|
 * | `pageTableCell.header` | `<th>` en vez de `<td>` |
 * | `colspan` · `rowspan` | los atributos del mismo nombre |
 * | `align_center` · `align_right` | `align="center"` · `align="right"` |
 * | `valign_middle` · `valign_bottom` | `valign="middle"` · `valign="bottom"` |
 * | `pageBlockTable.title` | `<caption>` |
 *
 * ## Se escribe lo mínimo
 *
 * Una tabla que no usa nada de eso **se guarda como tabla de Markdown**, con sus
 * barras, para que el archivo se siga leyendo a simple vista. Solo al fusionar
 * una celda —o al poner un título, o una cabecera rara— pasa a HTML. Se leen las
 * dos, así que las notas de antes siguen funcionando y nadie paga el HTML si no
 * lo necesita.
 *
 * ## Las anclas
 *
 * Al fusionar, su `TableModel` deja **una sola celda** con `colspan`/`rowspan` y
 * borra las demás de la fila. Aquí igual: [MarkdownBlock.Tabla.filas] guarda solo
 * las anclas, y [rejilla] las despliega a la cuadrícula completa cuando hay que
 * pintar o cuando hay que saber qué ocupa la fila 2, columna 3.
 */
object Tablas {

    // ---- Leer ----

    /** Interpreta [texto] como tabla, sea de Markdown o de HTML. Null si no lo es. */
    fun leer(texto: String): MarkdownBlock.Tabla? {
        val limpio = texto.trim()
        return if (limpio.startsWith("<table", ignoreCase = true)) {
            deHtml(limpio)
        } else {
            deMarkdown(limpio)
        }
    }

    fun esTabla(texto: String): Boolean = leer(texto) != null

    private fun deMarkdown(texto: String): MarkdownBlock.Tabla? {
        val lineas = texto.split('\n').map { it.trim() }.filter { it.startsWith("|") }
        if (lineas.size < 2) return null

        val separadora = celdasDeFila(lineas[1])
        if (separadora.isEmpty() || !separadora.all { esGuiones(it) }) return null
        val alineaciones = separadora.map { alineacionDeGuiones(it) }

        val filas = lineas.filterIndexed { i, _ -> i != 1 }.mapIndexed { f, linea ->
            celdasDeFila(linea).mapIndexed { c, celda ->
                Celda(
                    contenido = Markdown.parseInline(celda),
                    cabecera = f == 0,
                    alineacion = alineaciones.getOrElse(c) { Alineacion.IZQUIERDA }
                )
            }
        }
        return MarkdownBlock.Tabla(filas)
    }

    /**
     * Un lector de HTML **muy corto y muy tolerante**.
     *
     * No es un parser de HTML de verdad y no quiere serlo: solo tiene que
     * entender lo que escribe [aHtml] y lo que pueda pegar alguien a mano. Lo que
     * no reconozca se lo salta en vez de fallar, porque perder una tabla entera
     * por un atributo raro sería mucho peor que ignorarlo.
     */
    private fun deHtml(texto: String): MarkdownBlock.Tabla? {
        if (!texto.contains("<tr", ignoreCase = true)) return null

        val titulo = entre(texto, "<caption", "</caption>")
            ?.substringAfter('>')
            ?.let { Markdown.parseInline(desescapar(sinEtiquetas(it).trim())) }
            ?: InlineText("")

        val filas = mutableListOf<List<Celda>>()
        var pos = 0
        while (true) {
            val abre = texto.indexOf("<tr", pos, ignoreCase = true)
            if (abre < 0) break
            val cierra = texto.indexOf("</tr>", abre, ignoreCase = true)
            val hasta = if (cierra < 0) texto.length else cierra
            val celdas = celdasDeHtml(texto.substring(abre, hasta))
            if (celdas.isNotEmpty()) filas += celdas
            pos = if (cierra < 0) texto.length else cierra + 5
        }
        if (filas.isEmpty()) return null
        return MarkdownBlock.Tabla(filas, titulo)
    }

    private fun celdasDeHtml(fila: String): List<Celda> {
        val salida = mutableListOf<Celda>()
        var pos = 0
        while (true) {
            val th = fila.indexOf("<th", pos, ignoreCase = true)
            val td = fila.indexOf("<td", pos, ignoreCase = true)
            val abre = listOf(th, td).filter { it >= 0 }.minOrNull() ?: break
            val esCabecera = abre == th
            val finEtiqueta = fila.indexOf('>', abre)
            if (finEtiqueta < 0) break
            val atributos = fila.substring(abre, finEtiqueta)
            val etiqueta = if (esCabecera) "</th>" else "</td>"
            val cierra = fila.indexOf(etiqueta, finEtiqueta, ignoreCase = true)
            val hasta = if (cierra < 0) fila.length else cierra

            salida += Celda(
                contenido = Markdown.parseInline(
                    desescapar(sinEtiquetas(fila.substring(finEtiqueta + 1, hasta)).trim())
                ),
                cabecera = esCabecera,
                alineacion = when (atributo(atributos, "align")?.lowercase()) {
                    "center" -> Alineacion.CENTRO
                    "right" -> Alineacion.DERECHA
                    else -> Alineacion.IZQUIERDA
                },
                altura = when (atributo(atributos, "valign")?.lowercase()) {
                    "middle" -> AlturaEnCelda.MEDIO
                    "bottom" -> AlturaEnCelda.ABAJO
                    else -> AlturaEnCelda.ARRIBA
                },
                anchoEnColumnas = atributo(atributos, "colspan")?.toIntOrNull()
                    ?.coerceIn(1, 20) ?: 1,
                altoEnFilas = atributo(atributos, "rowspan")?.toIntOrNull()?.coerceIn(1, 40) ?: 1
            )
            pos = if (cierra < 0) fila.length else cierra + etiqueta.length
        }
        return salida
    }

    // ---- Escribir ----

    /**
     * Escribe la tabla: **de Markdown si se puede, de HTML si hace falta**.
     *
     * La decisión la toma la propia tabla, en [MarkdownBlock.Tabla.esAvanzada].
     */
    fun aTexto(tabla: MarkdownBlock.Tabla): String =
        if (tabla.esAvanzada) aHtml(tabla) else aMarkdown(tabla)

    private fun aMarkdown(tabla: MarkdownBlock.Tabla): String {
        val columnas = tabla.columnas.coerceAtLeast(1)
        val salida = StringBuilder()
        tabla.filas.forEachIndexed { f, fila ->
            salida.append("| ")
            salida.append(
                (0 until columnas).joinToString(" | ") { c ->
                    // Una barra dentro de una celda partiría la fila en dos.
                    fila.getOrNull(c)?.let { Inline.aTexto(it.contenido) }
                        .orEmpty().replace("|", "\\|")
                }
            )
            salida.append(" |\n")
            if (f == 0) {
                salida.append("|")
                (0 until columnas).forEach { c ->
                    salida.append(guionesDe(fila.getOrNull(c)?.alineacion)).append("|")
                }
                salida.append('\n')
            }
        }
        return salida.toString().trimEnd('\n')
    }

    private fun aHtml(tabla: MarkdownBlock.Tabla): String {
        val salida = StringBuilder("<table>\n")
        if (tabla.titulo.text.isNotEmpty()) {
            salida.append("  <caption>").append(escapar(Inline.aTexto(tabla.titulo)))
                .append("</caption>\n")
        }
        tabla.filas.forEach { fila ->
            salida.append("  <tr>\n")
            fila.forEach { celda ->
                val etiqueta = if (celda.cabecera) "th" else "td"
                salida.append("    <").append(etiqueta)
                if (celda.anchoEnColumnas > 1) {
                    salida.append(" colspan=\"").append(celda.anchoEnColumnas).append('"')
                }
                if (celda.altoEnFilas > 1) {
                    salida.append(" rowspan=\"").append(celda.altoEnFilas).append('"')
                }
                when (celda.alineacion) {
                    Alineacion.CENTRO -> salida.append(" align=\"center\"")
                    Alineacion.DERECHA -> salida.append(" align=\"right\"")
                    Alineacion.IZQUIERDA -> Unit
                }
                when (celda.altura) {
                    AlturaEnCelda.MEDIO -> salida.append(" valign=\"middle\"")
                    AlturaEnCelda.ABAJO -> salida.append(" valign=\"bottom\"")
                    AlturaEnCelda.ARRIBA -> Unit
                }
                salida.append('>').append(escapar(Inline.aTexto(celda.contenido)))
                    .append("</").append(etiqueta).append(">\n")
            }
            salida.append("  </tr>\n")
        }
        return salida.append("</table>").toString()
    }

    // ---- La rejilla, para pintar y para saber qué hay dónde ----

    /**
     * Despliega las anclas a la cuadrícula completa.
     *
     * Devuelve, por cada hueco, qué celda lo ocupa y si ese hueco **es** su
     * ancla. Es su `grid[r][c]` con `isAnchor`: al pintar solo dibuja el ancla, y
     * los demás huecos están tapados por ella.
     */
    fun rejilla(tabla: MarkdownBlock.Tabla): List<List<Hueco?>> {
        val alto = tabla.filas.size
        val ancho = tabla.columnas.coerceAtLeast(1)
        if (alto == 0) return emptyList()
        val rejilla = MutableList(alto) { MutableList<Hueco?>(ancho) { null } }

        tabla.filas.forEachIndexed { f, fila ->
            var c = 0
            fila.forEach { celda ->
                while (c < ancho && rejilla[f][c] != null) c++
                if (c >= ancho) return@forEach
                for (df in 0 until celda.altoEnFilas) {
                    for (dc in 0 until celda.anchoEnColumnas) {
                        val rf = f + df
                        val rc = c + dc
                        if (rf < alto && rc < ancho) {
                            rejilla[rf][rc] = Hueco(celda, f, c, df == 0 && dc == 0)
                        }
                    }
                }
                c += celda.anchoEnColumnas
            }
        }
        return rejilla
    }

    /** Qué celda ocupa un hueco de la cuadrícula, y si el hueco es su ancla. */
    data class Hueco(
        val celda: Celda,
        val filaDelAncla: Int,
        val columnaDelAncla: Int,
        val esElAncla: Boolean
    )

    // ---- Operaciones, las suyas ----

    fun conCelda(tabla: MarkdownBlock.Tabla, fila: Int, columna: Int, nuevo: InlineText) =
        conCeldaCambiada(tabla, fila, columna) { it.copy(contenido = nuevo) }

    fun alinear(tabla: MarkdownBlock.Tabla, fila: Int, columna: Int, como: Alineacion) =
        conCeldaCambiada(tabla, fila, columna) { it.copy(alineacion = como) }

    fun aLaAltura(tabla: MarkdownBlock.Tabla, fila: Int, columna: Int, como: AlturaEnCelda) =
        conCeldaCambiada(tabla, fila, columna) { it.copy(altura = como) }

    fun comoCabecera(tabla: MarkdownBlock.Tabla, fila: Int, columna: Int, si: Boolean) =
        conCeldaCambiada(tabla, fila, columna) { it.copy(cabecera = si) }

    fun conTitulo(tabla: MarkdownBlock.Tabla, titulo: InlineText) = tabla.copy(titulo = titulo)

    private fun conCeldaCambiada(
        tabla: MarkdownBlock.Tabla,
        fila: Int,
        columna: Int,
        cambio: (Celda) -> Celda
    ): MarkdownBlock.Tabla {
        if (tabla.filas.getOrNull(fila)?.getOrNull(columna) == null) return tabla
        val filas = tabla.filas.mapIndexed { f, cs ->
            if (f != fila) cs else cs.mapIndexed { c, celda ->
                if (c == columna) cambio(celda) else celda
            }
        }
        return tabla.copy(filas = filas)
    }

    /** Su `insertRowAt`. Con [donde] fuera de rango, al final. */
    fun insertarFila(tabla: MarkdownBlock.Tabla, donde: Int): MarkdownBlock.Tabla {
        val ancho = tabla.columnas.coerceAtLeast(1)
        val idx = if (donde < 0 || donde > tabla.filas.size) tabla.filas.size else donde
        return tabla.copy(
            filas = tabla.filas.toMutableList().apply { add(idx, List(ancho) { Celda() }) }
        )
    }

    /** Su `insertColumnAt`. */
    fun insertarColumna(tabla: MarkdownBlock.Tabla, donde: Int): MarkdownBlock.Tabla {
        val ancho = tabla.columnas
        val idx = if (donde < 0 || donde > ancho) ancho else donde
        val filas = tabla.filas.mapIndexed { f, fila ->
            fila.toMutableList().apply {
                add(idx.coerceAtMost(size), Celda(cabecera = f == 0))
            }
        }
        return tabla.copy(filas = filas, columnas = ancho + 1)
    }

    /** Su `deleteRows`, que borra **varias a la vez**. */
    fun quitarFilas(tabla: MarkdownBlock.Tabla, cuales: Set<Int>): MarkdownBlock.Tabla {
        val quedan = tabla.filas.filterIndexed { f, _ -> f !in cuales }
        // Una tabla sin filas no es una tabla.
        return if (quedan.isEmpty()) tabla else tabla.copy(filas = quedan)
    }

    /** Su `deleteColumns`. */
    fun quitarColumnas(tabla: MarkdownBlock.Tabla, cuales: Set<Int>): MarkdownBlock.Tabla {
        val ancho = tabla.columnas
        val quitadas = cuales.count { it in 0 until ancho }
        if (quitadas <= 0 || quitadas >= ancho) return tabla
        val filas = tabla.filas.map { fila -> fila.filterIndexed { c, _ -> c !in cuales } }
        if (filas.any { it.isEmpty() }) return tabla
        return tabla.copy(filas = filas, columnas = ancho - quitadas)
    }

    /**
     * Su `mergeCells`: fusiona el rectángulo que va de una celda a otra.
     *
     * El texto de todas se junta en la de arriba a la izquierda separado por
     * saltos —igual que ellos, que van pegando con `\n`— y las demás desaparecen
     * de sus filas. Queda una sola celda con su `colspan` y su `rowspan`.
     *
     * El rectángulo **crece hasta cubrir enteras las fusiones que toca**. Si no,
     * quedaría media celda dentro y media fuera, que es la comprobación que ellos
     * hacen comparando los anclas cubiertos con los pedidos: si no coinciden,
     * rechazan la fusión. Aquí en vez de rechazarla se agranda, que hace lo que
     * la gente esperaba en vez de no hacer nada.
     */
    fun fusionar(
        tabla: MarkdownBlock.Tabla,
        filaA: Int,
        columnaA: Int,
        filaB: Int,
        columnaB: Int
    ): MarkdownBlock.Tabla {
        val rejilla = rejilla(tabla)
        val alto = rejilla.size
        val ancho = tabla.columnas
        if (alto == 0 || ancho == 0) return tabla

        var f1 = minOf(filaA, filaB).coerceIn(0, alto - 1)
        var f2 = maxOf(filaA, filaB).coerceIn(0, alto - 1)
        var c1 = minOf(columnaA, columnaB).coerceIn(0, ancho - 1)
        var c2 = maxOf(columnaA, columnaB).coerceIn(0, ancho - 1)

        var crecio = true
        var vueltas = 0
        while (crecio && vueltas < 8) {
            crecio = false
            vueltas++
            for (f in f1..f2) {
                for (c in c1..c2) {
                    val h = rejilla.getOrNull(f)?.getOrNull(c) ?: continue
                    val hastaF = h.filaDelAncla + h.celda.altoEnFilas - 1
                    val hastaC = h.columnaDelAncla + h.celda.anchoEnColumnas - 1
                    if (h.filaDelAncla < f1) { f1 = h.filaDelAncla; crecio = true }
                    if (h.columnaDelAncla < c1) { c1 = h.columnaDelAncla; crecio = true }
                    if (hastaF > f2) { f2 = hastaF; crecio = true }
                    if (hastaC > c2) { c2 = hastaC; crecio = true }
                }
            }
        }
        if (f1 == f2 && c1 == c2) return tabla

        val dentro = mutableListOf<Celda>()
        val vistas = mutableSetOf<Pair<Int, Int>>()
        for (f in f1..f2) {
            for (c in c1..c2) {
                val h = rejilla.getOrNull(f)?.getOrNull(c) ?: continue
                if (vistas.add(h.filaDelAncla to h.columnaDelAncla)) dentro += h.celda
            }
        }

        val juntado = dentro.map { it.contenido.text }.filter { it.isNotEmpty() }
        val ancla = (rejilla[f1][c1]?.celda ?: Celda()).copy(
            contenido = InlineText(juntado.joinToString("\n")),
            anchoEnColumnas = c2 - c1 + 1,
            altoEnFilas = f2 - f1 + 1
        )
        val posicion = posicionEnLaFila(rejilla, f1, c1)

        val filas = tabla.filas.mapIndexed { f, fila ->
            if (f !in f1..f2) return@mapIndexed fila
            val quedan = fila.filterIndexed { c, _ ->
                val h = rejilla.getOrNull(f)?.getOrNull(indiceDeAncla(rejilla, f, c))
                h == null || h.filaDelAncla !in f1..f2 || h.columnaDelAncla !in c1..c2
            }.toMutableList()
            if (f == f1) quedan.add(posicion.coerceIn(0, quedan.size), ancla)
            quedan
        }
        return tabla.copy(filas = filas)
    }

    /** En qué columna de la rejilla empieza el ancla número [n] de la fila [f]. */
    private fun indiceDeAncla(rejilla: List<List<Hueco?>>, f: Int, n: Int): Int {
        var vistas = 0
        rejilla.getOrNull(f)?.forEachIndexed { c, h ->
            if (h?.esElAncla == true) {
                if (vistas == n) return c
                vistas++
            }
        }
        return -1
    }

    /** Cuántas anclas hay a la izquierda de la columna [c] en la fila [f]. */
    private fun posicionEnLaFila(rejilla: List<List<Hueco?>>, f: Int, c: Int): Int =
        (0 until c).count { rejilla.getOrNull(f)?.getOrNull(it)?.esElAncla == true }

    /** Su `unmergeCell`: deshace la fusión y devuelve las celdas que tapaba. */
    fun separar(tabla: MarkdownBlock.Tabla, fila: Int, columna: Int): MarkdownBlock.Tabla {
        val rejilla = rejilla(tabla)
        val h = rejilla.getOrNull(fila)?.getOrNull(columna) ?: return tabla
        if (h.celda.anchoEnColumnas <= 1 && h.celda.altoEnFilas <= 1) return tabla

        val f1 = h.filaDelAncla
        val c1 = h.columnaDelAncla
        val anchoFusion = h.celda.anchoEnColumnas
        val altoFusion = h.celda.altoEnFilas

        val filas = tabla.filas.mapIndexed { f, fila2 ->
            if (f < f1 || f >= f1 + altoFusion) return@mapIndexed fila2
            val lista = fila2.toMutableList()
            val pos = posicionEnLaFila(rejilla, f, c1)
            // Las que vuelven heredan la pinta del ancla: si la fusionada era
            // cabecera, las que reaparecen también. Sin esto, separar una
            // cabecera dejaba media fila de cabecera y media no, y eso Markdown
            // no lo sabe decir, así que la tabla se pasaba a HTML sin motivo.
            val hueca = Celda(
                cabecera = h.celda.cabecera,
                alineacion = h.celda.alineacion,
                altura = h.celda.altura
            )
            if (f == f1) {
                if (pos in lista.indices) {
                    lista[pos] = h.celda.copy(anchoEnColumnas = 1, altoEnFilas = 1)
                }
                repeat(anchoFusion - 1) { lista.add((pos + 1).coerceIn(0, lista.size), hueca) }
            } else {
                repeat(anchoFusion) { lista.add(pos.coerceIn(0, lista.size), hueca) }
            }
            lista
        }
        return tabla.copy(filas = filas)
    }

    /** Una tabla nueva y vacía. */
    fun nueva(filas: Int, columnas: Int): MarkdownBlock.Tabla {
        val f = filas.coerceIn(2, 20)
        val c = columnas.coerceIn(1, 12)
        return MarkdownBlock.Tabla(
            List(f) { fila -> List(c) { Celda(cabecera = fila == 0) } },
            columnas = c
        )
    }

    // ---- Trocitos ----

    private fun celdasDeFila(fila: String): List<String> {
        val cuerpo = fila.trim().removePrefix("|").removeSuffix("|")
        val salida = mutableListOf<String>()
        val actual = StringBuilder()
        var i = 0
        while (i < cuerpo.length) {
            val ch = cuerpo[i]
            when {
                ch == '\\' && i + 1 < cuerpo.length && cuerpo[i + 1] == '|' -> {
                    actual.append('|'); i += 2
                }
                ch == '|' -> { salida += actual.toString().trim(); actual.clear(); i++ }
                else -> { actual.append(ch); i++ }
            }
        }
        salida += actual.toString().trim()
        return salida
    }

    private fun esGuiones(c: String): Boolean = Regex("""^:?-{1,}:?$""").matches(c)

    private fun alineacionDeGuiones(c: String): Alineacion {
        val izq = c.startsWith(":")
        val der = c.endsWith(":")
        return when {
            izq && der -> Alineacion.CENTRO
            der -> Alineacion.DERECHA
            else -> Alineacion.IZQUIERDA
        }
    }

    private fun guionesDe(a: Alineacion?): String = when (a) {
        Alineacion.CENTRO -> ":---:"
        Alineacion.DERECHA -> "---:"
        else -> ":---"
    }

    private fun atributo(etiqueta: String, nombre: String): String? =
        Regex("""$nombre\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(etiqueta)?.groupValues?.get(1)

    private fun entre(s: String, abre: String, cierra: String): String? {
        val a = s.indexOf(abre, ignoreCase = true)
        if (a < 0) return null
        val b = s.indexOf(cierra, a, ignoreCase = true)
        return if (b < 0) null else s.substring(a, b)
    }

    private fun sinEtiquetas(s: String): String = s.replace(Regex("<[^>]*>"), "")

    private fun escapar(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun desescapar(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
