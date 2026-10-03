package com.forge.pixpin.lecciones

/**
 * **Una frase dicha de corrido, repartida en sus campos.**
 *
 * Quien dicta no rellena un formulario: dice «pasó que se vació la losa sin revisar el encofrado
 * porque el maestro tenía prisa; la próxima vez reviso los puntales antes del vaciado». Aquí se
 * corta por las palabras que todo el mundo usa al contarlo —«pasó que», «porque», «la próxima
 * vez», «aprendí que»— y cada trozo va a su sitio. Sin ninguna de ellas, todo es lo aprendido.
 */
object Dictado {

    class Campos(val titulo: String, val quePaso: String, val porQue: String, val proxima: String)

    private enum class Campo { TITULO, PASO, PORQUE, PROXIMA }

    private val MARCAS: List<Pair<Regex, Campo>> = listOf(
        "aprendí que" to Campo.TITULO, "aprendi que" to Campo.TITULO, "lección:" to Campo.TITULO, "leccion:" to Campo.TITULO,
        "lo que pasó fue que" to Campo.PASO, "lo que pasó es que" to Campo.PASO, "pasó que" to Campo.PASO, "paso que" to Campo.PASO,
        "resulta que" to Campo.PASO, "qué pasó:" to Campo.PASO, "que paso:" to Campo.PASO,
        "porque" to Campo.PORQUE, "debido a que" to Campo.PORQUE, "debido a" to Campo.PORQUE, "la causa fue" to Campo.PORQUE,
        "por qué:" to Campo.PORQUE, "ya que" to Campo.PORQUE,
        "la próxima vez" to Campo.PROXIMA, "la proxima vez" to Campo.PROXIMA, "de ahora en adelante" to Campo.PROXIMA,
        "a partir de ahora" to Campo.PROXIMA, "en adelante" to Campo.PROXIMA, "para la próxima" to Campo.PROXIMA,
        "para la proxima" to Campo.PROXIMA, "haré distinto:" to Campo.PROXIMA
    ).map { (f, c) -> Regex("(?i)(?<![\\p{L}])" + Regex.escape(f) + "(?![\\p{L}])") to c }

    fun repartir(dicho: String): Campos {
        val texto = dicho.trim()
        // Dónde empieza cada marca, la primera de cada campo; las demás se quedan dentro del texto.
        val cortes = ArrayList<Triple<Int, Int, Campo>>()
        val vistos = HashSet<Campo>()
        val todas = MARCAS.flatMap { (rx, c) -> rx.findAll(texto).map { Triple(it.range.first, it.range.last + 1, c) } }
            .sortedBy { it.first }
        for (t in todas) {
            if (t.third in vistos) continue
            if (cortes.any { t.first < it.second }) continue // solapada con otra («debido a que» y «debido a»)
            vistos += t.third; cortes += t
        }
        if (cortes.isEmpty()) return Campos(mayuscula(limpio(texto)), "", "", "")
        val trozos = HashMap<Campo, String>()
        val delante = limpio(texto.substring(0, cortes.first().first))
        cortes.forEachIndexed { i, (_, fin, campo) ->
            val hasta = cortes.getOrNull(i + 1)?.first ?: texto.length
            trozos[campo] = limpio(texto.substring(fin, hasta))
        }
        // Lo que va antes de la primera marca es lo aprendido, si no se dijo aparte; si no, el suceso.
        var titulo = trozos[Campo.TITULO].orEmpty()
        var paso = trozos[Campo.PASO].orEmpty()
        if (delante.isNotEmpty()) {
            if (titulo.isEmpty()) titulo = delante else paso = listOf(delante, paso).filter { it.isNotEmpty() }.joinToString(". ")
        }
        val proxima = trozos[Campo.PROXIMA].orEmpty()
        // Sin frase de lo aprendido, lo que se hará distinto lo resume mejor que nada.
        if (titulo.isEmpty()) titulo = proxima.ifEmpty { paso }
        return Campos(mayuscula(titulo), mayuscula(paso), mayuscula(trozos[Campo.PORQUE].orEmpty()), mayuscula(proxima))
    }

    private fun limpio(s: String) = s.trim().trim(',', ';', ':', '.', '-', '—').trim()

    private fun mayuscula(s: String) = s.replaceFirstChar { it.uppercaseChar() }
}
