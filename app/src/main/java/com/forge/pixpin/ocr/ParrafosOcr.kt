package com.forge.pixpin.ocr

/**
 * Una línea leída en la forma de siempre (`pixpin_ocr::Linea` del PC): su caja en px enteros de la
 * imagen ([x], [y], [ancho], [alto]), su texto y sus palabras con su caja.
 */
data class LineaLeida(val x: Int, val y: Int, val ancho: Int, val alto: Int, val texto: String, val palabras: List<PalabraLeida> = emptyList()) {
    val derecha: Int get() = x + ancho
    val abajo: Int get() = y + alto
}

data class PalabraLeida(val x: Int, val y: Int, val ancho: Int, val alto: Int, val texto: String)

/**
 * **De las líneas leídas al texto que se copia y que viaja** (26-oct-2026, puerto de
 * `crates/pixpin-geom/src/parrafos.rs` del PC, con sus mismas pruebas).
 *
 * Es el formato de `texto_de_imagen` del PC —el de «Copiar texto» y el de
 * `GaleriaCompartida.Entrada.texto`, que el PC pone al leer la galería—: las líneas de un párrafo
 * unidas por un espacio, los párrafos separados por una línea en blanco, columnas enteras una tras
 * otra y sin salto final. **El mismo en el teléfono**, para que el texto de una captura leída aquí
 * y el de la misma leída en el PC se parezcan y la búsqueda encuentre lo mismo en los dos.
 */
object ParrafosOcr {
    /** Hueco vertical máximo entre dos líneas del mismo párrafo, en fracciones de la altura típica. */
    const val HUECO_MAXIMO_ENTRE_LINEAS = 0.60f
    /** Solape horizontal mínimo entre dos líneas del mismo párrafo, en fracciones del ancho de la más estrecha. */
    const val SOLAPE_HORIZONTAL_MINIMO = 0.30f

    /** Agrupa las líneas en párrafos, en orden de lectura: primero columnas, luego párrafos dentro. */
    fun agrupar(lineas: List<LineaLeida>): List<List<LineaLeida>> {
        if (lineas.isEmpty()) return emptyList()
        val huecoMaximo = (medianaDeAlturas(lineas) * HUECO_MAXIMO_ENTRE_LINEAS).toInt()
        val grupos = ArrayList<List<LineaLeida>>()
        for (col in repartirEnColumnas(lineas)) {
            val columna = col.sortedWith(compareBy<LineaLeida>({ it.y }, { it.x }))
            var parrafo = ArrayList<LineaLeida>()
            for (l in columna) {
                val anterior = parrafo.lastOrNull()
                val sigue = anterior == null || (l.y - anterior.abajo <= huecoMaximo && seSolapanEnHorizontal(anterior, l))
                if (!sigue) { grupos += parrafo; parrafo = ArrayList() }
                parrafo += l
            }
            if (parrafo.isNotEmpty()) grupos += parrafo
        }
        return grupos
    }

    /** Las líneas de un párrafo unidas por un espacio; los párrafos, por una línea en blanco; sin salto final. */
    fun aTexto(grupos: List<List<LineaLeida>>): String = grupos.joinToString("\n\n") { p -> p.joinToString(" ") { it.texto } }

    /** `texto_de_lineas` del PC. */
    fun texto(lineas: List<LineaLeida>): String = aTexto(agrupar(lineas))

    /** Bloques de líneas cuyos rangos horizontales se solapan (encadenados), de izquierda a derecha. */
    private fun repartirEnColumnas(lineas: List<LineaLeida>): List<List<LineaLeida>> {
        val ordenadas = lineas.sortedWith(compareBy<LineaLeida>({ it.x }, { it.derecha }))
        val columnas = ArrayList<MutableList<LineaLeida>>()
        var bordeDerecho = Int.MIN_VALUE
        for (l in ordenadas) {
            val ultima = columnas.lastOrNull()
            if (ultima != null && l.x < bordeDerecho) {
                bordeDerecho = maxOf(bordeDerecho, l.derecha)
                ultima += l
            } else {
                bordeDerecho = l.derecha
                columnas += mutableListOf(l)
            }
        }
        return columnas
    }

    /** Altura típica: la mediana (un título no arrastra el umbral), sin las cajas degeneradas. */
    private fun medianaDeAlturas(lineas: List<LineaLeida>): Int {
        val alturas = lineas.map { it.alto }.filter { it > 0 }.sorted()
        return if (alturas.isEmpty()) 1 else alturas[alturas.size / 2]
    }

    private fun seSolapanEnHorizontal(a: LineaLeida, b: LineaLeida): Boolean {
        val solape = minOf(a.derecha, b.derecha) - maxOf(a.x, b.x)
        if (solape <= 0) return false
        val anchoMenor = minOf(a.ancho, b.ancho)
        if (anchoMenor == 0) return false
        return solape >= anchoMenor * SOLAPE_HORIZONTAL_MINIMO
    }
}
