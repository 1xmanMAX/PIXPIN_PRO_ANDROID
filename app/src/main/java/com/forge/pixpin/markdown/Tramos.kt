package com.forge.pixpin.markdown

/**
 * Un trozo de texto **sin solapes** con todos sus estilos a la vez.
 *
 * [InlineSpan] dice «de aquí a aquí hay negrita» y otro dice «de aquí a aquí hay
 * cursiva», y los dos pueden pisarse. Un [Tramo] dice «estas letras van en
 * negrita **y** cursiva», que es lo único que sabe responder quien pinta: una
 * letra tiene una pinta y solo una.
 */
data class Tramo(
    val inicio: Int,
    val fin: Int,
    val estilos: Int,
    val url: String? = null
) {
    fun tiene(kind: SpanKind): Boolean = estilos and kind.bandera != 0
}

/**
 * Aplana los tramos solapados en una partición del texto.
 *
 * ## De dónde sale
 *
 * Es la idea de `MediaDataController.getTextStyleRuns` de Telegram: separar lo
 * que el parser **encuentra** (marcas independientes que se solapan como quieran)
 * de lo que el pintor **necesita** (trozos seguidos, cada uno con la combinación
 * de estilos que le toca). Tener esas dos representaciones es lo que les permite
 * que negrita, cursiva, tachado y enlace convivan sobre las mismas letras.
 *
 * Su algoritmo recorre las marcas ordenadas y va partiendo y fusionando los
 * trozos ya emitidos, moviendo índices dentro de la lista mientras la recorre.
 * Aquí se llega al mismo sitio por **barrido de fronteras**, que es más corto y
 * se puede leer de una sentada: los extremos de todas las marcas son los únicos
 * puntos donde puede cambiar el aspecto del texto, así que se ordenan, y entre
 * dos consecutivos el estilo es constante por construcción — basta con preguntar
 * qué marcas cubren ese hueco. El resultado es idéntico; la diferencia es que no
 * hay que demostrar que las particiones a medio hacer siguen siendo válidas.
 *
 * Los huecos sin estilo no se emiten: quien pinta ya sabe qué hacer con el texto
 * a secas, y devolverlos obligaría a distinguir el tramo vacío del que no está.
 */
fun tramosDe(spans: List<InlineSpan>, longitud: Int): List<Tramo> {
    if (spans.isEmpty() || longitud <= 0) return emptyList()

    // Un tramo corrupto no puede tumbar el pin, y recortar es más útil que
    // descartar: el texto sigue viéndose y casi siempre con el estilo correcto.
    val validos = spans
        .filter { it.start in 0 until longitud && it.end > it.start }
        .map { if (it.end > longitud) it.copy(end = longitud) else it }
    if (validos.isEmpty()) return emptyList()

    val bordes = sortedSetOf<Int>().apply {
        validos.forEach { add(it.start); add(it.end) }
    }.toList()

    val salida = mutableListOf<Tramo>()
    for (i in 0 until bordes.size - 1) {
        val desde = bordes[i]
        val hasta = bordes[i + 1]

        var estilos = 0
        var url: String? = null
        for (s in validos) {
            if (s.start > desde || s.end < hasta) continue
            estilos = estilos or s.kind.bandera
            // El enlace de más adentro manda: en `[**a**](u)` el que cubre justo
            // estas letras es más específico que uno que abarque media frase.
            if (s.url != null) url = s.url
        }
        if (estilos == 0) continue

        // Dos huecos seguidos con la misma pinta son un solo tramo. Sin esto,
        // cada frontera ajena partiría el texto y el que pinta vería costuras
        // donde no las hay.
        val ultimo = salida.lastOrNull()
        if (ultimo != null && ultimo.fin == desde &&
            ultimo.estilos == estilos && ultimo.url == url
        ) {
            salida[salida.size - 1] = ultimo.copy(fin = hasta)
        } else {
            salida += Tramo(desde, hasta, estilos, url)
        }
    }
    return salida
}

/** Los tramos ya aplanados de este texto, listos para pintar. */
fun InlineText.tramos(): List<Tramo> = tramosDe(spans, text.length)
