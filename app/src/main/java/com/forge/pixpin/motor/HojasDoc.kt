package com.forge.pixpin.motor

/**
 * **Las hojas de un documento paginado puestas una debajo de otra, en las unidades del lector del
 * PC** (`pixpin_docs::vista::Hojas`, 20-oct-2026): cada hoja mide [ANCHO_HOJA] de ancho y lo que le
 * toque de alto por su proporción, con [HUECO] entre una y otra. Son las unidades de los marcos
 * numerados y de las vistas de los marcadores de un PDF o un Word maquetado: lo puesto aquí cae en
 * el mismo sitio en el PC, aunque cada pantalla dibuje las hojas a su tamaño.
 */
class HojasDoc(val arriba: DoubleArray, val altos: DoubleArray, val total: Double) {
    val cuantas get() = arriba.size

    /** La hoja que hay a la altura [y] (la de encima si cae en el hueco). */
    fun en(y: Double): Int {
        if (arriba.isEmpty()) return 0
        var lo = 0; var hi = arriba.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (arriba[m] <= y) lo = m + 1 else hi = m }
        return (lo - 1).coerceIn(0, arriba.size - 1)
    }

    /**
     * Dónde cae [y] en la hoja [en]: la fracción de su alto (0..1); en el hueco de debajo, más de 1
     * (hasta `1 + HUECO/alto`), para que una caja que lo cruza no se encoja.
     */
    fun dentro(y: Double): Pair<Int, Double> {
        if (arriba.isEmpty()) return 0 to 0.0
        val i = en(y)
        val alto = altos[i].coerceAtLeast(1e-3)
        val tope = if (i == arriba.size - 1) 1.0 else 1.0 + HUECO / alto
        return i to ((y - arriba[i]) / alto).coerceIn(0.0, tope)
    }

    /** La inversa de [dentro]. */
    fun yDe(i: Int, fraccion: Double): Double {
        if (arriba.isEmpty()) return 0.0
        val j = i.coerceIn(0, arriba.size - 1)
        return arriba[j] + fraccion * altos[j]
    }

    companion object {
        const val ANCHO_HOJA = 1400.0
        const val HUECO = 18.0
        private const val PROPORCION_A4 = 1.414

        /** [medidas]: el ancho y el alto de cada página, en lo que sea (solo cuenta la proporción). */
        fun colocar(medidas: List<Pair<Double, Double>?>): HojasDoc {
            val arriba = DoubleArray(medidas.size)
            val altos = DoubleArray(medidas.size)
            var y = 0.0
            medidas.forEachIndexed { i, m ->
                val p = m?.let { (w, h) -> if (w > 0 && h > 0 && (h / w).isFinite()) (h / w).coerceIn(0.05, 20.0) else null } ?: PROPORCION_A4
                arriba[i] = y
                altos[i] = ANCHO_HOJA * p
                y += altos[i] + HUECO
            }
            return HojasDoc(arriba, altos, (y - HUECO).coerceAtLeast(0.0))
        }
    }
}
