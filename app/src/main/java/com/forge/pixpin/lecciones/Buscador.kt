package com.forge.pixpin.lecciones

/**
 * **El buscador de las lecciones**: en el teléfono, sin conexión y al instante con miles.
 *
 * - Sin acentos, sin plurales y con erratas perdonadas ([Texto]): se dicta, y el dictado se equivoca.
 * - Busca en todo: lo aprendido pesa más que el suceso, y las etiquetas y las **palabras de
 *   referencia** casi tanto como el título —son las que uno puso para encontrarla—.
 * - **Por significado cercano**: una palabra que pertenece a un concepto del [Etiquetador] busca
 *   también la etiqueta de ese concepto y su área. «Construcción» encuentra la lección del
 *   encofrado aunque no diga «construcción».
 * - Las que se repiten y las graves suben: son las que más cuesta que vuelvan a pasar.
 *
 * Cada lección se prepara una vez ([Indice]) y se reutiliza mientras no cambie: buscar es solo
 * comparar palabras ya cortadas.
 */
object Buscador {

    private const val TITULO = 3.0
    private const val ETIQUETA = 2.5
    private const val REFERENCIA = 2.2
    private const val PROXIMA = 1.6
    private const val PORQUE = 1.3
    private const val AREA = 1.2
    private const val PASO = 1.0

    /** Una lección ya cortada en palabras, campo por campo, con el peso de cada campo. */
    class Indice(val leccion: Leccion) {
        val campos: List<Pair<Set<String>, Double>> = listOf(
            Texto.raices(leccion.titulo).toSet() to TITULO,
            leccion.todasLasEtiquetas.flatMap { Texto.raices(it) }.toSet() to ETIQUETA,
            leccion.referencias.flatMap { Texto.raices(it) }.toSet() to REFERENCIA,
            Texto.raices(leccion.proxima).toSet() to PROXIMA,
            (Texto.raices(leccion.porQue) + leccion.causas.flatMap { Texto.raices(it) }).toSet() to PORQUE,
            Texto.raices(leccion.area).toSet() to AREA,
            Texto.raices(leccion.quePaso).toSet() to PASO
        )
        val todas: Set<String> = campos.flatMap { it.first }.toSet()
    }

    class Resultado(val leccion: Leccion, val puntos: Double)

    /** Qué tan bien casa la palabra buscada [q] con la palabra [p] de la lección: 1 exacta, menos con prefijo o errata. */
    private fun casa(q: String, p: String): Double {
        if (q == p) return 1.0
        if (q.length >= 3 && p.startsWith(q)) return 0.8
        val tope = Texto.erratas(q)
        if (tope > 0 && Texto.distancia(q, p, tope) <= tope) return 0.6
        return 0.0
    }

    /** Lo buscado: cada raíz con su peso, más las etiquetas y áreas de los conceptos a los que apunta. */
    private class Consulta(val terminos: List<Pair<String, Double>>, val cuantas: Int)

    private fun consulta(texto: String): Consulta {
        val raices = Texto.raices(texto).distinct()
        val extra = raices.flatMap { r ->
            Etiquetador.conceptosDe(r).flatMap { c -> Texto.raices(c.etiqueta) + Texto.raices(c.area) }
        }.distinct().filter { it !in raices }
        return Consulta(raices.map { it to 1.0 } + extra.map { it to 0.5 }, raices.size)
    }

    fun buscar(indices: List<Indice>, texto: String): List<Resultado> {
        val q = consulta(texto)
        if (q.terminos.isEmpty()) return emptyList()
        val salida = ArrayList<Resultado>()
        for (ix in indices) {
            var puntos = 0.0
            var halladas = 0
            for ((i, termino) in q.terminos.withIndex()) {
                val (palabra, pesoDeLaPalabra) = termino
                var mejor = 0.0
                for ((palabras, pesoDelCampo) in ix.campos) {
                    if (palabras.isEmpty()) continue
                    if (palabra in palabras) { mejor = maxOf(mejor, pesoDelCampo); continue }
                    for (p in palabras) {
                        val c = casa(palabra, p)
                        if (c > 0) mejor = maxOf(mejor, c * pesoDelCampo)
                    }
                }
                if (mejor > 0 && i < q.cuantas) halladas++
                puntos += mejor * pesoDeLaPalabra
            }
            if (puntos <= 0) continue
            // Las palabras buscadas que no salen restan: buscar «escala planos» prefiere la que tiene las dos.
            val cubre = if (q.cuantas == 0) 1.0 else (halladas.toDouble() / q.cuantas)
            if (q.cuantas > 0 && halladas == 0) {
                // Solo casó por concepto: vale, pero va detrás.
                puntos *= 0.5
            } else puntos *= (0.4 + 0.6 * cubre)
            val l = ix.leccion
            puntos *= 1.0 + 0.15 * l.repeticiones.size + 0.1 * (l.gravedad - 1) + if (l.esError) 0.1 else 0.0
            salida += Resultado(l, puntos)
        }
        return salida.sortedByDescending { it.puntos }
    }

    /**
     * **¿Ya tienes una lección así?** Mientras se escribe una nueva, las que se le parecen. Si se
     * parece mucho, lo que toca no es otra lección sino apuntar que **volvió a pasar**.
     * Parecido = proporción de raíces del texto nuevo que salen en la lección (y al revés).
     */
    fun parecidas(indices: List<Indice>, texto: String, minimo: Double = 0.45, cuantas: Int = 3): List<Resultado> {
        val nuevas = Texto.raices(texto).toSet()
        if (nuevas.size < 2) return emptyList()
        return indices.mapNotNull { ix ->
            val suyas = ix.todas
            if (suyas.isEmpty()) return@mapNotNull null
            val comunes = nuevas.count { n -> n in suyas || suyas.any { casa(n, it) >= 0.8 } }
            val a = comunes.toDouble() / nuevas.size
            val b = comunes.toDouble() / minOf(suyas.size, 12).coerceAtLeast(1)
            val s = (2 * a * b) / (a + b).coerceAtLeast(1e-9)
            if (s >= minimo) Resultado(ix.leccion, s) else null
        }.sortedByDescending { it.puntos }.take(cuantas)
    }

    /** **Relacionadas**: las que comparten etiquetas o palabras con esta, para leerlas juntas. */
    fun relacionadas(indices: List<Indice>, l: Leccion, cuantas: Int = 4): List<Leccion> {
        val mia = indices.firstOrNull { it.leccion.id == l.id }?.todas ?: Indice(l).todas
        val etiquetas = l.todasLasEtiquetas.toSet()
        return indices.asSequence().filter { it.leccion.id != l.id }.map { ix ->
            val comunes = ix.todas.count { it in mia }
            val mismas = ix.leccion.todasLasEtiquetas.count { it in etiquetas }
            ix.leccion to (comunes + 2 * mismas)
        }.filter { it.second >= 3 }.sortedByDescending { it.second }.take(cuantas).map { it.first }.toList()
    }

    /**
     * **Las de este sitio**: para un proyecto o un documento, las lecciones que hablan de lo
     * mismo. Es el aviso en el momento justo, que es lo que más ayuda a no repetir un error.
     */
    fun paraElContexto(indices: List<Indice>, contexto: String, cuantas: Int = 5): List<Leccion> =
        if (contexto.isBlank()) emptyList()
        else buscar(indices, contexto).filter { it.puntos >= 2.0 }.map { it.leccion }.take(cuantas)
}
