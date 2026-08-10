package com.forge.pixpin.motor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * El punto etiquetado: **A, B, C sobre el dibujo**.
 *
 * Es la herramienta de las matemáticas. Un croquis de geometría no se explica
 * con flechas y textos sueltos: se explica nombrando los puntos y hablando de
 * ellos. «El triángulo ABC», «la mediatriz de AB», «M es el punto medio». Sin
 * poder nombrar un vértice, cada afirmación hay que acompañarla de un dedo
 * señalando.
 *
 * Hasta ahora se podía poner un texto al lado de una esquina, pero eso es otra
 * cosa: un texto no sabe a qué punto pertenece, no se mueve con él, no se
 * numera solo y hay que colocarlo a ojo cada vez para que no tape la figura.
 *
 * ## Las tres decisiones que lo hacen servir
 *
 * **Se numeran solos y en serie.** Se pone A, y el siguiente es B. Nombrar a
 * mano quince vértices es quince oportunidades de repetir una letra sin darse
 * cuenta, y una letra repetida en un problema de geometría lo invalida entero.
 * Ver [siguienteEtiqueta].
 *
 * **La letra se coloca donde no estorba.** No a la derecha por defecto —encima
 * de la figura la mitad de las veces— sino en el hueco más ancho que quede
 * alrededor del punto. Es lo que hace uno a mano sin pensarlo: la letra va
 * hacia fuera. Ver [anguloLibre].
 *
 * **La letra orbita, el punto no.** Se arrastra la letra y da vueltas alrededor
 * de su punto sin separarse de él, porque una etiqueta suelta a tres
 * centímetros ya no dice de quién es. Ver [conLaEtiquetaHacia].
 */

/** De qué serie sale la etiqueta de un punto. */
enum class SerieDePunto {
    /** A, B, C… Z, A₁, B₁… Es la de los vértices de toda la vida. */
    MAYUSCULAS,

    /** a, b, c… Para lo que no es un vértice: lados, rectas, ángulos. */
    MINUSCULAS,

    /** 1, 2, 3… Para nubes de puntos, donde las letras se acaban. */
    NUMEROS
}

/**
 * La siguiente etiqueta libre de [serie], mirando las que ya hay.
 *
 * «Según el existente»: no lleva un contador aparte, se mira el dibujo. Un
 * contador se desincroniza en cuanto se borra un punto —el siguiente repetiría
 * una letra que ya está— y sobre todo no sabría nada de los puntos que llegan
 * al abrir un archivo guardado.
 *
 * Pasada la Z no se para ni se inventa símbolos raros: sigue con **A1, B1…** y
 * luego A2. Es lo que se escribe a mano cuando hacen falta más de veintiséis, se
 * teclea sin salir del teclado normal y no se confunde con nada.
 */
fun siguienteEtiqueta(usadas: Collection<String>, serie: SerieDePunto): String {
    val ocupadas = usadas.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    var i = 0
    while (true) {
        val propuesta = etiquetaNumero(i, serie)
        if (propuesta !in ocupadas) return propuesta
        i++
        // Un tope por si alguien pega diez mil puntos: mejor repetir que colgarse.
        if (i > 100_000) return etiquetaNumero(i, serie)
    }
}

/** La etiqueta que hace el número [i] de la serie, contando desde cero. */
fun etiquetaNumero(i: Int, serie: SerieDePunto): String {
    if (serie == SerieDePunto.NUMEROS) return (i + 1).toString()
    val letras = if (serie == SerieDePunto.MAYUSCULAS) 'A' else 'a'
    val letra = letras + (i % 26)
    val vuelta = i / 26
    return if (vuelta == 0) letra.toString() else "$letra$vuelta"
}

/** Las etiquetas que ya están puestas en la escena. */
fun etiquetasUsadas(elementos: List<Element>): List<String> = elementos
    .filter { it.type == ElementType.PUNTO && !it.isDeleted }
    .mapNotNull { it.text }

/**
 * Hacia dónde poner la letra para que no tape nada.
 *
 * Se miran las direcciones en las que **sale algo** del punto —los tramos de las
 * figuras que pasan por ahí— y la letra se pone en medio del hueco más ancho
 * que queda entre ellas. Es exactamente lo que hace la mano sin pensarlo: en un
 * vértice de un triángulo, la letra va por fuera; en un punto medio de un lado,
 * perpendicular a él; y en un punto suelto, donde sea, que todo está libre.
 *
 * Sin esto habría que elegir un lado fijo, y un lado fijo acierta la mitad de
 * las veces: en la mitad de los vértices de cualquier figura, «arriba a la
 * derecha» cae justo encima de una raya.
 *
 * Devuelve el ángulo en radianes, con la Y hacia abajo como en toda la escena.
 */
fun anguloLibre(punto: Pt, elementos: List<Element>, alcance: Double = ALCANCE_DE_LA_LETRA): Double {
    val salidas = direccionesQueSalen(punto, elementos, alcance)
    // Nada alrededor: arriba a la derecha, que es donde la pone todo el mundo.
    if (salidas.isEmpty()) return -PI / 4

    val orden = salidas.sorted()
    var mejorHueco = -1.0
    var mejorAngulo = -PI / 4
    for (i in orden.indices) {
        val desde = orden[i]
        // El hueco entre esta dirección y la siguiente, dando la vuelta al final.
        val hasta = if (i + 1 < orden.size) orden[i + 1] else orden[0] + 2 * PI
        val hueco = hasta - desde
        if (hueco > mejorHueco) {
            mejorHueco = hueco
            mejorAngulo = desde + hueco / 2
        }
    }
    return normalizado(mejorAngulo)
}

/**
 * En qué direcciones sale algo del punto.
 *
 * Se recorren los tramos de todas las figuras cercanas y se toma la dirección de
 * los que **tocan** el punto. Un tramo que solo pasa cerca sin tocarlo no
 * estorba a la letra, así que no cuenta.
 */
private fun direccionesQueSalen(
    punto: Pt, elementos: List<Element>, alcance: Double
): List<Double> {
    val salidas = mutableListOf<Double>()
    for (e in elementos) {
        if (e.isDeleted || e.type == ElementType.PUNTO) continue
        for ((a, b) in segmentosDe(e)) {
            // De cada tramo que toca el punto salen dos direcciones: la de ida y
            // la de vuelta. Un punto en mitad de una recta tiene la raya a los
            // dos lados, y la letra tiene que irse arriba o abajo, no a un lado.
            val da = hypot(a.x - punto.x, a.y - punto.y)
            val db = hypot(b.x - punto.x, b.y - punto.y)
            if (da <= alcance) salidas += normalizado(atan2(b.y - punto.y, b.x - punto.x))
            if (db <= alcance) salidas += normalizado(atan2(a.y - punto.y, a.x - punto.x))
        }
    }
    return salidas
}

/** Un ángulo llevado al intervalo [0, 2π). */
private fun normalizado(a: Double): Double {
    var v = a % (2 * PI)
    if (v < 0) v += 2 * PI
    return v
}

/**
 * Un punto nuevo, ya colocado y ya etiquetado.
 *
 * Se le da todo hecho: la letra que toca y el hueco donde ponerla. Poner un
 * punto tiene que ser un toque, no un toque más un menú más arrastrar la letra.
 */
fun nuevoPunto(
    id: String,
    donde: Pt,
    elementos: List<Element>,
    serie: SerieDePunto,
    estilo: ItemStyle,
    seed: Int
): Element = Element(
    id = id,
    type = ElementType.PUNTO,
    // La caja es el propio punto: sin tamaño. Lo que se ve —el redondel y la
    // letra— se dibuja alrededor y no depende de la caja, igual que el rótulo
    // de una cota. Así arrastrarlo mueve el punto y no lo estira.
    x = donde.x,
    y = donde.y,
    width = 0.0,
    height = 0.0,
    seed = seed,
    strokeColor = estilo.strokeColor,
    fontSize = estilo.fontSize,
    fontFamily = estilo.fontFamily,
    text = siguienteEtiqueta(etiquetasUsadas(elementos), serie),
    etiquetaAngulo = anguloLibre(donde, elementos),
    etiquetaRadio = RADIO_DE_LA_LETRA
)

/** Dónde cae el centro de la letra de [e]. */
fun sitioDeLaEtiqueta(e: Element): Pt {
    val a = e.etiquetaAngulo ?: -PI / 4
    val r = e.etiquetaRadio ?: RADIO_DE_LA_LETRA
    return Pt(e.x + r * cos(a), e.y + r * sin(a))
}

/**
 * Mueve la letra hacia [hacia], **sin separarla de su punto**.
 *
 * Da vueltas alrededor y el radio se puede estirar un poco, pero no soltarse:
 * una etiqueta a tres centímetros de su punto ya no dice de quién es, y en un
 * dibujo con doce puntos eso convierte el croquis en un jeroglífico.
 */
fun conLaEtiquetaHacia(e: Element, hacia: Pt): Element {
    val dx = hacia.x - e.x
    val dy = hacia.y - e.y
    val d = hypot(dx, dy)
    if (d < 0.001) return e
    return e.copy(
        etiquetaAngulo = atan2(dy, dx),
        etiquetaRadio = d.coerceIn(RADIO_MINIMO, RADIO_MAXIMO)
    )
}

/** ¿El dedo ha caído sobre la letra de [e]? */
fun tocaLaEtiqueta(e: Element, p: Pt, radio: Double): Boolean {
    val centro = sitioDeLaEtiqueta(e)
    val tam = (e.fontSize ?: 20.0)
    return abs(p.x - centro.x) <= tam * 0.7 + radio && abs(p.y - centro.y) <= tam * 0.7 + radio
}

/**
 * Radio al que se pone la letra, en píxeles de escena.
 *
 * Lo justo para que el redondel no la toque y siga leyéndose como suya. Más
 * lejos empieza a parecer un texto suelto.
 */
const val RADIO_DE_LA_LETRA = 22.0
const val RADIO_MINIMO = 14.0
const val RADIO_MAXIMO = 60.0

/** Hasta dónde se mira para saber qué sale del punto. */
const val ALCANCE_DE_LA_LETRA = 6.0

/**
 * Lo que mide el redondel, en píxeles de escena.
 *
 * **Más gordo que los demás puntos del programa** a propósito: este no es una
 * marca de referencia como las de una tabla de coordenadas, es parte del dibujo
 * y hay que verlo en una foto de la pizarra hecha desde el fondo del aula.
 */
const val RADIO_DEL_PUNTO = 5.5

/** El aro blanco de alrededor, para que se lea sobre cualquier fondo. */
const val ARO_DEL_PUNTO = 2.2
