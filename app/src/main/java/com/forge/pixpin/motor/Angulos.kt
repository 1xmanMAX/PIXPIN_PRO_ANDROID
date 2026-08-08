package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Los ángulos que se forman donde dos rayas se juntan.
 *
 * **Solo mientras se mueve algo.** Un dibujo con todos sus ángulos escritos es
 * ilegible, y además nadie los mira cuando no está tocando nada; pero mientras
 * arrastras un vértice, el ángulo es exactamente lo único que quieres saber y lo
 * único que no puedes ver. Aparece al empezar el gesto y se va al soltar, como
 * la guía de un nivel de burbuja.
 *
 * Se miden **entre las dos rayas que salen del vértice**, no respecto de la
 * horizontal: lo que importa de una esquina es si es recta, no cómo está
 * inclinada la figura entera.
 *
 * Geometría pura y sin estado: quién forma ángulo con quién se decide aquí, y se
 * comprueba sin dispositivo.
 */

/** Un ángulo que enseñar mientras dura el gesto. */
data class AnguloInterno(
    val vertice: Pt,
    /** Cuánto mide, de 0 a 180. */
    val grados: Double,
    /**
     * Hacia dónde cae su bisectriz, en radianes de pantalla.
     *
     * Es donde hay que escribir el número: dentro del ángulo, que es donde lo
     * pone cualquiera a mano. Fuera se lee como si fuera del ángulo de al lado.
     */
    val bisectriz: Double
)

/**
 * Los ángulos que forman las rayas de [elementos] en sus juntas.
 *
 * Una junta es un sitio donde acaban dos rayas: porque comparten vértice, o
 * porque las une un clavo. Se enseña solo lo que toca a [moviendose] — con todo
 * el dibujo lleno de números no se ve el dibujo.
 */
fun angulosInternos(
    elementos: List<Element>,
    alfileres: List<Alfiler>,
    moviendose: Set<String>,
    tolerancia: Double = JUNTA
): List<AnguloInterno> {
    val rayas = elementos.filter { !it.isDeleted && it.isLinear && absolutePoints(it).size >= 2 }
    if (rayas.size < 2 || moviendose.isEmpty()) return emptyList()

    // Cada punta, con hacia dónde se va la raya desde ella.
    data class Punta(val id: String, val donde: Pt, val hacia: Double)
    val puntas = mutableListOf<Punta>()
    for (r in rayas) {
        val pts = absolutePoints(r)
        puntas += Punta(r.id, pts.first(), direccion(pts.first(), pts[1]))
        puntas += Punta(r.id, pts.last(), direccion(pts.last(), pts[pts.size - 2]))
    }

    val clavados = alfileres.flatMap { a ->
        a.agarres.map { it.elementId }.distinct().let { ids ->
            ids.flatMap { x -> ids.filter { it != x }.map { y -> x to y } }
        }
    }.toSet()

    val out = mutableListOf<AnguloInterno>()
    for (i in puntas.indices) {
        for (j in i + 1 until puntas.size) {
            val a = puntas[i]
            val b = puntas[j]
            if (a.id == b.id) continue
            if (a.id !in moviendose && b.id !in moviendose) continue
            val juntas = hypot(a.donde.x - b.donde.x, a.donde.y - b.donde.y) <= tolerancia ||
                (a.id to b.id) in clavados
            if (!juntas) continue

            val grados = Math.toDegrees(abs(normalizarRad(b.hacia - a.hacia)))
            // Dos rayas en la misma dirección no forman esquina: forman una raya
            // más larga, y un «180°» ahí es ruido.
            if (grados < 0.5 || grados > 179.5) continue
            out += AnguloInterno(a.donde, grados, bisectrizDe(a.hacia, b.hacia))
        }
    }

    // **Uno por junta, y pocos.**
    //
    // Enseñarlos todos era peor que no enseñar ninguno: en una esquina donde se
    // juntan tres rayas salen tres números amontonados encima del vértice, y en
    // una figura cerrada salen tantos que tapan el propio dibujo. Lo que hace
    // falta mientras se mueve algo es **el ángulo de la esquina que se está
    // tocando**, así que se deja uno por sitio —el más cerrado, que es el que
    // uno está intentando ajustar— y como mucho un puñado.
    return out
        .groupBy { redondear(it.vertice) }
        .map { (_, enLaJunta) -> enLaJunta.minByOrNull { it.grados }!! }
        .sortedBy { it.grados }
        .take(MAXIMOS)
}

/** Dos vértices a menos de un pelo son la misma junta. */
private fun redondear(p: Pt): Pair<Long, Long> =
    Math.round(p.x / JUNTA) to Math.round(p.y / JUNTA)

/**
 * Cuántos se enseñan a la vez.
 *
 * Cuatro son los de un cuadrilátero: pasado eso, lo que se ve es una nube de
 * cifras y no un dibujo. Si hubiera más, los que se quedan son los más cerrados
 * — un ángulo de 170° no lo está mirando nadie.
 */
private const val MAXIMOS = 4

/** Hacia dónde va la raya que sale de [desde] pasando por [hacia]. */
private fun direccion(desde: Pt, hacia: Pt): Double =
    atan2(hacia.y - desde.y, hacia.x - desde.x)

/** El ángulo, llevado a (−π, π]. */
private fun normalizarRad(a: Double): Double {
    var d = a
    while (d > Math.PI) d -= 2 * Math.PI
    while (d <= -Math.PI) d += 2 * Math.PI
    return d
}

/**
 * La bisectriz de dos direcciones, **por el lado corto**.
 *
 * Sumar y dividir entre dos parece lo natural y falla justo donde más se nota:
 * con una dirección a 170° y otra a −170° da 0, que apunta al lado contrario del
 * ángulo. Yendo por la diferencia corta sale siempre dentro.
 */
private fun bisectrizDe(a: Double, b: Double): Double =
    normalizarRad(a + normalizarRad(b - a) / 2)

/**
 * A cuánto se dan por juntas dos puntas, en píxeles de escena.
 *
 * Es generoso a propósito: dibujando con el dedo, dos rayas que se ven unidas
 * casi nunca comparten el punto exacto, y un ángulo que solo aparece cuando las
 * puntas coinciden al píxel no aparecería nunca.
 */
private const val JUNTA = 6.0
