package com.forge.pixpin.annotate

/**
 * Búfer del trazo que se está dibujando ahora mismo.
 *
 * Antes el trazo vivo era una `List<Pt>` inmutable dentro de un `mutableStateOf`
 * y cada muestra hacía `points + pt`: una copia de la lista entera por punto
 * (O(n²) de basura en el hilo de UI) **y** una recomposición por punto. Con un
 * lápiz óptico, que muestrea a cientos de hercios, eso son miles de listas por
 * trazo — de ahí los tirones.
 *
 * Aquí los puntos viven en arrays planos que crecen duplicándose: añadir un
 * punto es escribir tres floats. El lienzo se entera por un contador de versión
 * aparte, que solo invalida el dibujado.
 */
class StrokeBuffer(initialCapacity: Int = 512) {

    private var xs = FloatArray(initialCapacity)
    private var ys = FloatArray(initialCapacity)
    private var ps = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    fun clear() {
        size = 0
    }

    /**
     * Añade una muestra. Las muestras repetidas en la misma posición se
     * descartan: el digitalizador reenvía el punto mientras el lápiz está
     * apoyado sin moverse y solo engordarían el trazo.
     */
    fun add(x: Float, y: Float, pressure: Float = 1f) {
        if (size > 0 && xs[size - 1] == x && ys[size - 1] == y) return
        ensureCapacity(size + 1)
        xs[size] = x
        ys[size] = y
        ps[size] = pressure
        size++
    }

    fun x(i: Int): Float = xs[i]
    fun y(i: Int): Float = ys[i]
    fun pressure(i: Int): Float = ps[i]

    /** Materializa el trazo como anotación inmutable, una sola vez al soltar. */
    fun toPoints(): List<Pt> = List(size) { Pt(xs[it], ys[it], ps[it]) }

    private fun ensureCapacity(needed: Int) {
        if (needed <= xs.size) return
        val newSize = maxOf(needed, xs.size * 2)
        xs = xs.copyOf(newSize)
        ys = ys.copyOf(newSize)
        ps = ps.copyOf(newSize)
    }
}
