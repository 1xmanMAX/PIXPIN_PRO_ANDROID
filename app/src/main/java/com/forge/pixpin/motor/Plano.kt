package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * El plano cartesiano: **los ejes para dibujar una función**.
 *
 * No es la cuadrícula del fondo ni la figura de «unos ejes» de la lista. Aquellas
 * dos son dibujo: rayas que quedan y ya. Esto es un **instrumento**: sabe cuánto
 * vale una unidad, cada cuánto se rotula y cada cuánto va una raya, así que
 * puede responder a la pregunta que hace falta al graficar —«¿dónde cae el
 * punto (3, −2)?»— y seguir respondiéndola después de estirarlo.
 *
 * ## Lo que decide todo: `unidad`
 *
 * Es cuántos píxeles de escena mide **una unidad**. De ahí sale lo demás: la caja
 * dividida por la unidad es el intervalo que se ve, los números caen cada
 * [Element.pasoDeNumeros] unidades y las rayas finas cada [Element.pasoDeCuadros].
 * Con paso 1 salen los enteros, con 0,5 los medios, con 10 las decenas.
 *
 * ## Y por eso estirarlo hace dos cosas distintas
 *
 * Esta es la parte que lo hace servir de verdad, y es la que no tendría una
 * figura dibujada:
 *
 * - **Por un lado** —los tiradores del medio— crece la caja y la unidad se queda
 *   como está. El plano no se deforma: **aparecen más números**, porque cabe más
 *   intervalo. Es lo que se hace cuando la función se sale por la derecha.
 * - **Por una esquina** crecen la caja y la unidad a la vez. Los números que
 *   había siguen siendo los mismos, **repartidos en más sitio**: el plano entero
 *   se ve más grande. Es lo que se hace cuando no se lee.
 *
 * Las dos son «agrandar» y las dos hacen falta; lo que las separa es de dónde se
 * tira. Ver [resizeSingleElement], que es donde se aplica.
 *
 * Sin Android: dónde cae cada raya y cada número es geometría, y es justo lo que
 * se puede equivocar en silencio — un eje con los números descolocados medio
 * cuadro no se ve mal, se lee mal.
 */

/** Una raya del plano, **relativa a `x`/`y`**. [eje] distingue los dos ejes. */
data class TrazoDelPlano(val a: Pt, val b: Pt, val eje: Boolean)

/**
 * Un número del plano, con dónde va su ancla.
 *
 * [horizontal] dice si es de la regla de abajo —y entonces se centra bajo su
 * marca— o de la de la izquierda, que se pega a la izquierda del eje.
 */
data class NumeroDelPlano(val donde: Pt, val texto: String, val horizontal: Boolean)

/** Lo que vale una unidad y cada cuánto se rotula, si el elemento no lo dice. */
const val UNIDAD_POR_DEFECTO = 40.0
const val PASO_POR_DEFECTO = 1.0

/**
 * Lo fina que va una raya de la rejilla, en píxeles **de escena**, y **no sale del
 * pincel**.
 *
 * La rejilla no es un trazo: es el papel pautado sobre el que se dibuja la gráfica. Atada
 * al grosor del elemento —que es lo que había— elegir un eje gordo, que es lo normal para
 * que se vea, multiplicaba también la cuadrícula: con el eje a ocho la malla iba a casi
 * cinco píxeles y competía con la curva que uno intenta seguir con la vista. Lo que gradúa
 * el grosor es el eje, que sí es trazo; la rejilla se queda en un pelo.
 *
 * De escena y no de pantalla —al revés que la cuadrícula del fondo del lienzo, que se
 * divide por el zoom—: esta pertenece al plano y tiene que crecer con él, o al acercarse
 * se despegaría de sus propios ejes.
 *
 * Vive aquí y no en el renderizador porque **lo comparten las tres salidas**: estaba
 * escrito a mano en pantalla, en el SVG y en el PDF, y tres copias de un número es un
 * número que acaba siendo tres distintos.
 */
const val GROSOR_DE_LA_REJILLA = 0.75

/** Y lo que se transparenta respecto de sus ejes, por lo mismo y en los tres sitios. */
const val ALFA_DE_LA_REJILLA = 0.22

/**
 * Cuántas rayas se dibujan como mucho, por eje.
 *
 * Es la guarda de rendimiento **y la de legibilidad**: pasado eso las rayas se
 * tocan entre sí y lo que queda es una mancha. Antes de llegar ahí, [pasoUtil]
 * ya habrá ensanchado el paso.
 */
private const val MAXIMAS = 300

/** Lo poco que puede medir un cuadro en píxeles de escena antes de cerrarse. */
private const val MINIMO_CUADRO = 6.0

/** Lo poco que puede medir el hueco entre dos números antes de que se pisen. */
private const val MINIMO_ENTRE_NUMEROS = 34.0

/** Cuánto vale una unidad en este plano. */
val Element.unidadDelPlano: Double
    get() = (unidad ?: UNIDAD_POR_DEFECTO).coerceAtLeast(0.5)

/** Cada cuántas unidades va un número. */
val Element.pasoDeNumerosDelPlano: Double
    get() = (pasoDeNumeros ?: PASO_POR_DEFECTO).coerceAtLeast(1e-6)

/** Cada cuántas unidades va una raya fina. */
val Element.pasoDeCuadrosDelPlano: Double
    get() = (pasoDeCuadros ?: PASO_POR_DEFECTO).coerceAtLeast(1e-6)

/**
 * El paso de verdad, ensanchado si el elegido no cabe.
 *
 * El usuario pide «de uno en uno» pensando en el plano que tiene delante; al
 * alejarse o al pedir un intervalo enorme, ese uno se convierte en rayas a medio
 * píxel. Doblando el paso hasta que quepa, las rayas que quedan **siguen siendo
 * las suyas** —un subconjunto de las que pidió— y no unas cualesquiera.
 */
fun pasoUtil(paso: Double, unidad: Double, minimo: Double): Double {
    if (paso <= 0.0 || unidad <= 0.0) return paso
    var p = paso
    var vueltas = 0
    while (p * unidad < minimo && vueltas < 16) {
        p *= 2
        vueltas++
    }
    return p
}

/**
 * El origen del plano: **el centro de su caja**.
 *
 * Centrado y no en una esquina porque una función se mira alrededor del cero: en
 * una esquina, la mitad del plano serían números negativos que casi nunca se
 * usan y el cero quedaría pegado al borde. Devuelto en relativo a `x`/`y`.
 */
fun origenDelPlano(e: Element): Pt = Pt(e.width / 2, e.height / 2)

/** Hasta dónde llega el plano a cada lado, en unidades. */
fun alcanceDelPlano(e: Element): Pair<Double, Double> {
    val u = e.unidadDelPlano
    return (e.width / 2 / u) to (e.height / 2 / u)
}

/**
 * Todas las rayas del plano: la rejilla y los dos ejes.
 *
 * Los ejes van **los últimos** para que se pinten encima de la rejilla; el orden
 * de la lista es el orden de pintado, igual que en la escena.
 */
fun trazosDelPlano(e: Element): List<TrazoDelPlano> {
    if (e.width <= 0 || e.height <= 0) return emptyList()
    val u = e.unidadDelPlano
    val o = origenDelPlano(e)
    val (alcanceX, alcanceY) = alcanceDelPlano(e)
    val paso = pasoUtil(e.pasoDeCuadrosDelPlano, u, MINIMO_CUADRO)

    val out = mutableListOf<TrazoDelPlano>()
    for (n in enteros(alcanceX, paso)) {
        val x = o.x + n * paso * u
        out += TrazoDelPlano(Pt(x, 0.0), Pt(x, e.height), eje = false)
    }
    for (n in enteros(alcanceY, paso)) {
        val y = o.y + n * paso * u
        out += TrazoDelPlano(Pt(0.0, y), Pt(e.width, y), eje = false)
    }
    out += TrazoDelPlano(Pt(0.0, o.y), Pt(e.width, o.y), eje = true)
    out += TrazoDelPlano(Pt(o.x, 0.0), Pt(o.x, e.height), eje = true)
    return out
}

/**
 * Los números de las dos reglas.
 *
 * El cero se escribe una sola vez, en la esquina del origen: escrito en los dos
 * ejes se pisa consigo mismo y se lee como un ocho.
 */
fun numerosDelPlano(e: Element): List<NumeroDelPlano> {
    if (e.width <= 0 || e.height <= 0) return emptyList()
    val u = e.unidadDelPlano
    val o = origenDelPlano(e)
    val (alcanceX, alcanceY) = alcanceDelPlano(e)
    val paso = pasoUtil(e.pasoDeNumerosDelPlano, u, MINIMO_ENTRE_NUMEROS)

    val out = mutableListOf<NumeroDelPlano>()
    for (n in enteros(alcanceX, paso)) {
        if (n == 0) continue
        val valor = n * paso
        out += NumeroDelPlano(Pt(o.x + valor * u, o.y), escrito(valor), horizontal = true)
    }
    for (n in enteros(alcanceY, paso)) {
        if (n == 0) continue
        val valor = n * paso
        // La Y de la pantalla crece hacia abajo y la del plano hacia arriba: el
        // número que va debajo del origen es el negativo.
        out += NumeroDelPlano(Pt(o.x, o.y + valor * u), escrito(-valor), horizontal = false)
    }
    out += NumeroDelPlano(Pt(o.x, o.y), "0", horizontal = true)
    return out
}

/**
 * Dónde cae el punto de coordenadas [x], [y] del plano, **en la escena**.
 *
 * Es lo que convierte esto en un instrumento y no en un dibujo: con esto se
 * puede clavar un punto en (3, −2) sin contar cuadros con el dedo.
 */
fun puntoDelPlano(e: Element, x: Double, y: Double): Pt {
    val u = e.unidadDelPlano
    val o = origenDelPlano(e)
    return Pt(e.x + o.x + x * u, e.y + o.y - y * u)
}

/** Los múltiplos que caben a cada lado del origen, del centro hacia fuera. */
private fun enteros(alcance: Double, paso: Double): List<Int> {
    if (paso <= 0.0 || alcance <= 0.0) return emptyList()
    val cuantos = floor(alcance / paso).toInt().coerceAtMost(MAXIMAS)
    if (cuantos <= 0) return listOf(0)
    return (-cuantos..cuantos).toList()
}

/**
 * Un número escrito para la regla: sin decimales si no hacen falta.
 *
 * Con paso entero salen «1, 2, 3» y con paso de medio, «0,5». Cuatro caracteres
 * es lo que cabe entre dos marcas antes de que se pisen, así que no se escriben
 * más de dos decimales.
 */
internal fun escrito(valor: Double): String {
    if (abs(valor - valor.roundToInt()) < 1e-9) return valor.roundToInt().toString()
    val redondo = Math.round(valor * 100.0) / 100.0
    return redondo.toString().trimEnd('0').trimEnd('.').replace('.', ',')
}
