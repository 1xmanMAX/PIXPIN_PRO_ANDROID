package com.forge.pixpin.anki

/**
 * **De dónde salen las tarjetas de Anki** (25-oct-2026, `crates/pixpin-anki/src/tarjetas.rs` del PC,
 * portado tal cual; el usuario: «quiero que des una integración con Anki … ayuda mucho a estudiantes
 * a personas para recordar»). Puro: se prueba con textos (`TarjetasTest`).
 *
 * - De una **nota**: «pregunta :: respuesta», «P: … / R: …» y los huecos `{{c1::…}}` de Anki.
 * - De una **clase grabada**: cada captura es una tarjeta con la imagen delante y, detrás, lo que se
 *   dijo en ese tramo de la transcripción.
 * - De una **foto**: la imagen delante y su descripción detrás.
 *
 * Quien las manda es [EnviarAAnkiActivity] (AnkiDroid o, sin él, un fichero para importar).
 */
object Tarjetas {

    /**
     * Una imagen que va dentro de una tarjeta (en Anki, en su carpeta de medios y con `<img src>` en
     * el campo). [nombre]: el nombre con que se guarda en Anki, único y sin carpetas. **[ruta] y no
     * los bytes** como en el PC: una clase trae muchas capturas y aquí se copian del disco solo al
     * mandarlas.
     */
    data class Imagen(val nombre: String, val ruta: String)

    /** Una tarjeta básica de Anki: delante y detrás, en HTML. */
    data class Tarjeta(
        val frente: String,
        val reverso: String,
        val etiquetas: List<String> = emptyList(),
        /** Las imágenes que nombran [frente] o [reverso]. */
        val imagenes: List<Imagen> = emptyList()
    )

    /** El texto a salvo dentro de HTML, con los saltos de línea como `<br>`. */
    fun html(texto: String): String = talCual(texto.trim())

    /** [html] sin quitar los blancos de los bordes (para trozos de una línea). */
    private fun talCual(texto: String): String = buildString(texto.length) {
        for (c in texto) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\n' -> append("<br>")
            '\r' -> {}
            else -> append(c)
        }
    }

    /** Una etiqueta de Anki: sin espacios (allí separan etiquetas). */
    fun etiqueta(texto: String): String = texto.trim().map { if (it.isWhitespace()) '_' else it }.joinToString("")

    /**
     * Las líneas como las parte Rust (`str::lines`): sin la vacía de detrás del último salto y sin
     * el `\r` de las de Windows. El `lines()` de Kotlin da una de más al final.
     */
    internal fun lineas(texto: String): List<String> {
        if (texto.isEmpty()) return emptyList()
        val v = texto.split('\n').toMutableList()
        if (v.last().isEmpty()) v.removeAt(v.size - 1)
        return v.map { it.removeSuffix("\r") }
    }

    /**
     * **Las tarjetas de una nota.** Se entienden:
     *
     * - `pregunta :: respuesta` (o `;;`), una por línea;
     * - `P: …` / `R: …` (y `Q:` / `A:`), la respuesta en las líneas que siguen hasta la siguiente
     *   pregunta o una línea en blanco;
     * - una línea con huecos de Anki, `{{c1::Lima}} es la capital`: delante con el hueco `[…]` y
     *   detrás entera, lo tapado en negrita.
     *
     * Lo demás se deja: un párrafo suelto no es una pregunta.
     */
    fun deLaNota(texto: String): List<Tarjeta> {
        val v = mutableListOf<Tarjeta>()
        var pregunta: String? = null
        val respuesta = StringBuilder()
        fun cerrar() {
            val p = pregunta
            if (p != null && p.isNotBlank() && respuesta.isNotBlank()) v += Tarjeta(html(p), html(respuesta.toString()))
            pregunta = null
            respuesta.setLength(0)
        }
        for (linea in lineas(texto.replace("\r\n", "\n"))) {
            var l = linea.trim()
            if (l.isEmpty()) { cerrar(); continue }
            val p = quitarPrefijo(l, listOf("P:", "Q:", "Pregunta:", "Question:"))
            if (p != null) { cerrar(); pregunta = p; continue }
            val r = quitarPrefijo(l, listOf("R:", "A:", "Respuesta:", "Answer:"))
            if (r != null) {
                if (pregunta != null) {
                    if (respuesta.isNotEmpty()) respuesta.append('\n')
                    respuesta.append(r)
                }
                continue
            }
            if (pregunta != null && respuesta.isNotEmpty()) {
                respuesta.append('\n').append(l)
                continue
            }
            l = l.trimStart('-', '*', '•').trim()
            val corte = partirEn(l, "::") ?: partirEn(l, ";;")
            if (corte != null && !corte.first.contains("{{") && corte.first.isNotBlank() && corte.second.isNotBlank()) {
                v += Tarjeta(html(corte.first), html(corte.second))
                continue
            }
            deHuecos(l)?.let { v += it }
        }
        cerrar()
        return v
    }

    /** `split_once` de Rust: lo de antes y lo de después de la primera vez que sale [sep]. */
    private fun partirEn(s: String, sep: String): Pair<String, String>? {
        val i = s.indexOf(sep)
        return if (i < 0) null else s.substring(0, i) to s.substring(i + sep.length)
    }

    private fun quitarPrefijo(l: String, prefijos: List<String>): String? = prefijos.firstNotNullOfOrNull { p ->
        if (l.length >= p.length && l.regionMatches(0, p, 0, p.length, ignoreCase = true)) l.substring(p.length).trim() else null
    }

    /** Una línea con huecos `{{c1::texto}}` (o `{{c1::texto::pista}}`). */
    private fun deHuecos(l: String): Tarjeta? {
        val frente = StringBuilder()
        val reverso = StringBuilder()
        var resto = l
        var alguno = false
        while (true) {
            val i = resto.indexOf("{{c")
            if (i < 0) break
            val fin = resto.indexOf("}}", i).takeIf { it >= 0 }?.minus(i) ?: break
            val dentro = resto.substring(i + 2, i + fin)
            val cuerpo = partirEn(dentro, "::")?.second ?: break
            val (tapado, pista) = partirEn(cuerpo, "::")?.let { it.first to it.second } ?: (cuerpo to null)
            frente.append(talCual(resto.substring(0, i)))
            reverso.append(talCual(resto.substring(0, i)))
            frente.append(if (pista != null) "[${talCual(pista)}]" else "[…]")
            reverso.append("<b>${talCual(tapado)}</b>")
            resto = resto.substring(i + fin + 2)
            alguno = true
        }
        if (!alguno) return null
        frente.append(talCual(resto))
        reverso.append(talCual(resto))
        return Tarjeta(frente.toString(), reverso.toString())
    }

    /** El minuto de una marca: `12:34` o `1:02:03`. */
    fun minuto(ms: Long): String {
        val s = maxOf(ms, 0) / 1000
        val h = s / 3600; val m = (s / 60) % 60; val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    /** Lo más que se pone detrás de una tarjeta de clase: más no se repasa. */
    const val MAXIMO_REVERSO = 700

    private fun caracteres(s: String) = s.codePointCount(0, s.length)

    /**
     * **Las tarjetas de una clase grabada**: una por captura. Delante la imagen con su minuto y la
     * pregunta; detrás lo que se dijo desde esa captura hasta la siguiente ([trozos] son los de la
     * transcripción, `(ms, texto)`). Una captura sin nada dicho detrás se queda con su minuto: la
     * imagen ya es lo que se repasa.
     */
    fun deLaClase(titulo: String, pregunta: String, trozos: List<Pair<Long, String>>, capturas: List<Pair<Long, Imagen>>): List<Tarjeta> {
        val ordenadas = capturas.sortedBy { it.first }
        val dichos = loDichoPorCaptura(trozos, ordenadas.map { it.first })
        return ordenadas.mapIndexed { n, (ms, imagen) ->
            val dicho = dichos[n]
            val cabeza = "${titulo.trim()} · ⏱ ${minuto(ms)}"
            Tarjeta(
                frente = "<img src=\"${imagen.nombre}\"><br><small>${html(cabeza)}</small><br>${html(pregunta)}",
                reverso = if (dicho.isEmpty()) html(cabeza) else "${html(dicho)}<br><small>${html(cabeza)}</small>",
                imagenes = listOf(imagen)
            )
        }
    }

    /**
     * **Lo dicho en el tramo de cada captura** ([tiempos], en orden; 28-oct-2026, PC `a4974705`): desde
     * el trozo que sonaba al hacerla (empezó un poco antes) hasta la siguiente, recortado a
     * [MAXIMO_REVERSO]. Es el reverso de su tarjeta, y lo que valora Jev para quedarse solo con las que
     * valen la pena ([com.forge.pixpin.jev.AnkiEnLaApp]).
     */
    fun loDichoPorCaptura(trozos: List<Pair<Long, String>>, tiempos: List<Long>): List<String> =
        tiempos.mapIndexed { n, ms ->
            val hasta = tiempos.getOrNull(n + 1) ?: Long.MAX_VALUE
            val primero = trozos.indexOfLast { it.first <= ms }.takeIf { it >= 0 } ?: 0
            var dicho = ""
            for ((t, texto) in trozos.drop(primero)) {
                if (t >= hasta && dicho.isNotEmpty()) break
                if (dicho.isNotEmpty()) dicho += " "
                dicho += texto.trim()
                if (caracteres(dicho) > MAXIMO_REVERSO) {
                    dicho = dicho.substring(0, dicho.offsetByCodePoints(0, MAXIMO_REVERSO)) + "…"
                    break
                }
            }
            dicho
        }

    /**
     * **Solo las que quedan** (`quedarse_con` del PC): `quedan[i]` dice si la tarjeta `i` se manda. Si
     * los largos no casan, todas (lo de siempre: nada se pierde por un error).
     */
    fun quedarseCon(tarjetas: List<Tarjeta>, quedan: List<Boolean>): List<Tarjeta> =
        if (quedan.size != tarjetas.size) tarjetas else tarjetas.filterIndexed { i, _ -> quedan[i] }

    /** **Una foto sola**: delante la imagen; detrás su descripción. */
    fun deLaFoto(imagen: Imagen, descripcion: String, titulo: String): Tarjeta {
        val detras = if (descripcion.isBlank()) titulo else descripcion
        return Tarjeta(frente = "<img src=\"${imagen.nombre}\">", reverso = html(detras), imagenes = listOf(imagen))
    }

    /**
     * **Sin AnkiDroid**: un `.txt` que Anki importa tal cual («Archivo → Importar»), como
     * `fichero.rs` del PC. Las cabeceras `#…` son las de Anki 2.1.55 en adelante: el separador, que
     * los campos son HTML, el mazo y las etiquetas.
     */
    fun fichero(mazo: String, tarjetas: List<Tarjeta>): String = buildString {
        append("#separator:tab\n#html:true\n")
        append("#deck:${limpio(mazo)}\n")
        append("#tags column:3\n")
        for (t in tarjetas) {
            append(limpio(t.frente)); append('\t')
            append(limpio(t.reverso)); append('\t')
            append(t.etiquetas.joinToString(" ")); append('\n')
        }
    }

    /** Sin tabuladores ni saltos de línea: romperían las columnas. */
    private fun limpio(t: String) = t.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
