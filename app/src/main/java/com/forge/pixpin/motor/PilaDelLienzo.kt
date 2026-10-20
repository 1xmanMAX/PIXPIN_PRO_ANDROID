package com.forge.pixpin.motor

import kotlin.math.max
import kotlin.math.min

/**
 * **Fotos y texto a un lienzo por su nombre, apilados** (PC `pila_lienzo.rs`, 17-oct-2026): el pedido
 * `al_lienzo`. El usuario: «mandar directamente fotos y texto a un canvas con solo saber su nombre, que
 * sea igual que mandarle a un chat, y que se vayan apilando para que luego pueda organizarlos».
 *
 * Cada cosa va **debajo de la anterior que llegó**, en una columna a la derecha de lo que ya había, y
 * nunca encima de nada: si el usuario puso algo en la columna, se baja hasta el primer hueco. Lo apilado
 * se reconoce por su id (`pila-…`), que viaja con el lienzo: la pila sigue igual en el PC y en el teléfono.
 */
object PilaDelLienzo {
    const val PREFIJO = "pila-"
    const val ANCHO_COLUMNA = 480.0
    const val HUECO = 24.0
    const val AIRE_COLUMNA = 80.0

    private fun caja(e: Element): DoubleArray = getElementBounds(e).let { doubleArrayOf(it.x1, it.y1, it.x2, it.y2) }
    private fun finita(c: DoubleArray) = c.all { it.isFinite() }

    /** **Dónde va lo siguiente de la pila** (su esquina de arriba a la izquierda), de tamaño [tam]. */
    fun sitio(elementos: List<Element>, tam: Pair<Double, Double>, excepto: String? = null): Pair<Double, Double> {
        val w = max(tam.first, 1.0); val h = max(tam.second, 1.0)
        val vivos = elementos.filter { !it.isDeleted && it.id != excepto }
        val cajas = vivos.map(::caja).filter(::finita)
        val ultima = vivos.lastOrNull { it.id.startsWith(PREFIJO) }?.let(::caja)?.takeIf(::finita)
        val x: Double
        var y: Double
        if (ultima != null) { x = ultima[0]; y = ultima[3] + HUECO }
        else if (cajas.isNotEmpty()) { x = cajas.maxOf { it[2] } + AIRE_COLUMNA; y = cajas.minOf { it[1] } }
        else { x = 0.0; y = 0.0 }
        // Sin pisar nada: se baja por debajo de lo que estorbe.
        repeat(10_000) {
            val estorba = cajas.filter { c -> c[0] < x + w + HUECO / 2 && c[2] > x - HUECO / 2 && c[1] < y + h + HUECO / 2 && c[3] > y - HUECO / 2 }
                .maxOfOrNull { it[3] } ?: return x to y
            y = estorba + HUECO
        }
        return x to y
    }

    /** El tamaño con que entra una foto: el suyo, o reducida a lo ancho de la columna. */
    fun tamano(ancho: Int, alto: Int): Pair<Double, Double> {
        val w = max(ancho, 1).toDouble(); val h = max(alto, 1).toDouble()
        val f = min(ANCHO_COLUMNA / w, 1.0)
        return w * f to h * f
    }

    /**
     * **Apila en la escena** las fotos una debajo de otra y el texto debajo (como el pie de una foto en el
     * chat). [fotos]: el fichero de la escena de cada foto, con sus píxeles. [texto]: el elemento ya hecho
     * (como pegar un texto en el lienzo), que aquí solo se lleva a su sitio. Devuelve la escena nueva.
     */
    fun apilar(escena: Scene, fotos: List<Triple<SceneFile, Int, Int>>, texto: Element?, ahora: Long): Scene {
        var els = escena.elements
        var archivos = escena.files
        var n = 0
        fun idNuevo() = "$PREFIJO${java.lang.Long.toHexString(ahora)}-${++n}"
        for ((f, wpx, hpx) in fotos) {
            val (w, h) = tamano(wpx, hpx)
            val (x, y) = sitio(els, w to h)
            els = els + newImageElement(f.id, x, y, w, h).copy(id = idNuevo(), updated = ahora)
            archivos = archivos + (f.id to f)
        }
        if (texto != null) {
            val c = caja(texto)
            val (x, y) = sitio(els, (c[2] - c[0]) to (c[3] - c[1]), texto.id)
            els = els + texto.copy(id = idNuevo(), x = texto.x + x - c[0], y = texto.y + y - c[1], updated = ahora)
        }
        return escena.copy(elements = els, files = archivos)
    }
}
