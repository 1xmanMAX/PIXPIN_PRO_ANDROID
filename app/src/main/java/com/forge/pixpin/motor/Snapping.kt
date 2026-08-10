package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Enganche a los puntos notables de lo ya dibujado.
 *
 * **Es lo que separa un esquema de un montón de rayas casi alineadas.** Con el
 * dedo es imposible acertar la esquina exacta de un rectángulo o el punto medio
 * de un lado: fallas dos o tres píxeles, y esos dos o tres píxeles se ven. El
 * enganche los perdona — cuando el dedo pasa cerca de un punto notable, la
 * forma que estás dibujando se pega a él.
 *
 * Los puntos notables de una figura son sus **cuatro esquinas**, los **puntos
 * medios de sus lados** y su **centro**. En una línea o una flecha, sus
 * extremos y su punto medio.
 *
 * Sin Android y sin estado: se le dan los elementos y un punto, y dice a dónde
 * habría que pegarse. Así se puede comprobar sin dispositivo que engancha donde
 * debe y —más importante— que **no** engancha donde no debe.
 */

/** Qué clase de punto notable es. Sirve para pintar la pista de otra forma. */
enum class TipoAnclaje {
    ESQUINA, MEDIO, CENTRO, EXTREMO,

    /**
     * El eje de coordenadas: su origen y sus dos rectas.
     *
     * No sale de ninguna figura ni de ningún punto tecleado — es la referencia
     * del plano. Sin él, poner algo «justo en el eje» o «a la altura del cero»
     * había que hacerlo a ojo, que es lo contrario de para lo que se pone un
     * eje.
     */
    EJE,

    /**
     * Donde se cruzan **dos figuras cualesquiera**.
     *
     * Es el que más se echa de menos y el único que **no pertenece a ninguna
     * figura**: nace de la relación entre dos. Sin él, cerrar un contorno donde
     * dos trazos se cruzan es imposible a pulso, porque el punto que buscas no
     * existe como vértice de nada.
     *
     * Cualesquiera de verdad: una elipse contra un rectángulo, un arco contra un
     * garabato, una imagen contra una línea, o una figura consigo misma donde su
     * propio trazo se cruza. Cómo se saca el perímetro de cada tipo, en
     * [contornosDe].
     */
    INTERSECCION,

    /**
     * Cualquier punto **del borde de una línea guía**: la escuadra.
     *
     * Es el único enganche que no lleva a un punto notable sino a **todo un
     * canto**, y solo lo ofrecen las guías. Los demás sirven para empezar y
     * acabar un trazo sobre una guía; este sirve para **recorrerla**, que es
     * justo lo que no se podía: entre esquina y esquina no hay ningún punto al
     * que pegarse, así que el lado salía torcido y la curva de un círculo no
     * había forma de repasarla a pulso.
     *
     * Va el último en prioridad a propósito. El borde pasa por encima de los
     * vértices y de los cruces, y si ganase, apuntar a una esquina de una guía
     * dejaría el trazo *cerca* de la esquina en vez de *en* la esquina.
     */
    BORDE
}

/** Un punto al que merece la pena pegarse. */
data class Anclaje(val punto: Pt, val tipo: TipoAnclaje, val elementId: String)

/**
 * Qué se engancha. Es la parte **configurable**: cada clase de punto se puede
 * apagar por separado, y apagarlas todas deja el dibujo completamente libre.
 */
data class AjustesEnganche(
    val activo: Boolean = true,
    val esquinas: Boolean = true,
    val medios: Boolean = true,
    val centros: Boolean = true,
    /** Donde se cruzan dos figuras. Ver [TipoAnclaje.INTERSECCION]. */
    val intersecciones: Boolean = true,
    /** El origen y las dos rectas del eje. Ver [TipoAnclaje.EJE]. */
    val eje: Boolean = true,
    /** El canto entero de una guía. Ver [TipoAnclaje.BORDE]. */
    val bordeDeGuia: Boolean = true,
    /**
     * El canto de una figura del dibujo, no solo de una guía.
     *
     * Es el que permite clavar algo **sobre un lado**: el pie de una altura, un
     * punto de tangencia, el sitio por donde cortar. Va el último de todos, así
     * que nunca le quita el sitio a un vértice ni a un cruce — solo manda a lo
     * largo del lado, donde no hay nada mejor.
     */
    val bordeDeFigura: Boolean = true,
    /**
     * Radio de captura **en píxeles de pantalla**, no de escena.
     *
     * En pantalla es donde ocurre el problema: el dedo tapa unos 40 px mires al
     * zoom que mires. Si el radio fuese de escena, muy acercado engancharía a
     * medio dibujo y muy alejado no engancharía a nada.
     */
    val radio: Double = 14.0
) {
    companion object {
        /** Todo apagado: dibujar a pulso, sin que nada tire del dedo. */
        val NINGUNO = AjustesEnganche(activo = false)

        /**
         * **Solo el canto de las guías.** Es con lo que engancha el lápiz.
         *
         * A mano alzada no se puede ofrecer lo mismo que a una figura: un trazo
         * que salta a un vértice o a un cruce en mitad del recorrido no se
         * corrige, se rompe — el garabato pega un tirón y sigue. El canto no da
         * ese problema porque no es un punto al que ir, es una superficie sobre
         * la que resbalar: el dedo se mueve y el trazo se mueve con él, pegado.
         */
        val SOLO_GUIAS = AjustesEnganche(
            esquinas = false, medios = false, centros = false,
            intersecciones = false, eje = false,
            bordeDeGuia = true, bordeDeFigura = false
        )
    }
}

/**
 * Los puntos notables de [e].
 *
 * El propio elemento que se está dibujando no debe entrar en la lista: se
 * engancharía a sí mismo en cuanto naciera.
 */
fun anclajesDe(e: Element, ajustes: AjustesEnganche): List<Anclaje> {
    if (e.isDeleted) return emptyList()
    val out = mutableListOf<Anclaje>()

    // Los lineales dan sus extremos y su medio; su caja no significa nada.
    if (e.isLinear || e.isFreeDraw) {
        val pts = absolutePoints(e)
        if (pts.size < 2) return emptyList()
        if (ajustes.esquinas) {
            out += Anclaje(pts.first(), TipoAnclaje.EXTREMO, e.id)
            out += Anclaje(pts.last(), TipoAnclaje.EXTREMO, e.id)
        }
        if (ajustes.medios) {
            out += Anclaje(pts[pts.size / 2], TipoAnclaje.MEDIO, e.id)
        }
        return out
    }

    val c = getElementAbsoluteCoords(e)
    fun girado(x: Double, y: Double) = pointRotateRads(Pt(x, y), Pt(c.cx, c.cy), e.angle)

    if (ajustes.esquinas) {
        out += Anclaje(girado(c.x1, c.y1), TipoAnclaje.ESQUINA, e.id)
        out += Anclaje(girado(c.x2, c.y1), TipoAnclaje.ESQUINA, e.id)
        out += Anclaje(girado(c.x2, c.y2), TipoAnclaje.ESQUINA, e.id)
        out += Anclaje(girado(c.x1, c.y2), TipoAnclaje.ESQUINA, e.id)
    }
    if (ajustes.medios) {
        out += Anclaje(girado(c.cx, c.y1), TipoAnclaje.MEDIO, e.id)
        out += Anclaje(girado(c.x2, c.cy), TipoAnclaje.MEDIO, e.id)
        out += Anclaje(girado(c.cx, c.y2), TipoAnclaje.MEDIO, e.id)
        out += Anclaje(girado(c.x1, c.cy), TipoAnclaje.MEDIO, e.id)
    }
    if (ajustes.centros) {
        // En una elipse el centro es **el centro de la circunferencia**, que es
        // lo que se busca al trazar un radio o alinear con el eje.
        out += Anclaje(Pt(c.cx, c.cy), TipoAnclaje.CENTRO, e.id)
    }
    return out
}

/**
 * El punto al que engancharse desde [p], o null si no hay ninguno cerca.
 *
 * [excluir] es el elemento que se está dibujando o moviendo: sus propios puntos
 * no cuentan.
 *
 * Gana **el más cercano**, y en empate el de mayor prioridad: una esquina antes
 * que un punto medio, y un punto medio antes que un centro. El centro de una
 * caja grande está lejos de todo y engancharse a él por sorpresa desconcierta
 * más de lo que ayuda, así que va el último.
 */
fun buscarAnclaje(
    elementos: List<Element>,
    p: Pt,
    zoom: Double,
    ajustes: AjustesEnganche = AjustesEnganche(),
    excluir: String? = null,
    /**
     * Puntos que no salen de ninguna figura: los tecleados en una tabla de
     * coordenadas. Alguien los puso ahí a propósito, así que enganchan igual
     * que una esquina. Ver [anclajesDeTablas].
     */
    extra: List<Anclaje> = emptyList()
): Anclaje? {
    if (!ajustes.activo) return null
    // El radio se da en píxeles de pantalla y aquí se trabaja en escena.
    val radio = ajustes.radio / zoom.coerceAtLeast(0.0001)

    var mejor: Anclaje? = null
    var mejorDistancia = Double.MAX_VALUE

    fun considerar(a: Anclaje) {
        val d = hypot(a.punto.x - p.x, a.punto.y - p.y)
        if (d > radio) return
        val mejorQueElActual = d < mejorDistancia - 0.001 ||
            (abs(d - mejorDistancia) <= 0.001 && prioridad(a.tipo) < prioridad(mejor!!.tipo))
        if (mejorQueElActual) {
            mejor = a
            mejorDistancia = d
        }
    }

    // Los tecleados en una tabla entran los primeros: son los que más se
    // agradecen al trazar de punto a punto, y así en un empate ganan ellos.
    extra.forEach(::considerar)

    // Las intersecciones las busca [interseccionesCerca], que sabe sacarle el
    // perímetro a **cualquier** figura —elipses, arcos, esquinas redondeadas,
    // imágenes— y no solo a las que se dibujan con cuatro rectas. El descarte
    // por cercanía vive allí porque es parte del algoritmo: sin él, cruzar dos
    // curvas muestreadas sería cuadrático en cada fotograma.
    if (ajustes.intersecciones) {
        interseccionesCerca(elementos, p, radio, excluir).forEach(::considerar)
    }

    for (e in elementos) {
        if (e.id == excluir || e.isFrame) continue
        for (a in anclajesDe(e, ajustes)) {
            val d = hypot(a.punto.x - p.x, a.punto.y - p.y)
            if (d > radio) continue
            val mejorQueElActual = d < mejorDistancia - 0.001 ||
                (abs(d - mejorDistancia) <= 0.001 && prioridad(a.tipo) < prioridad(mejor!!.tipo))
            if (mejorQueElActual) {
                mejor = a
                mejorDistancia = d
            }
        }
    }

    // El canto de una guía, **y solo si no había nada notable a mano**.
    //
    // No se decide por cercanía como todo lo demás, y tiene que ser así: el
    // canto pasa por encima de los vértices de su propia figura, así que junto a
    // una esquina siempre hay un punto del borde más cerca que la esquina. Por
    // distancia, la esquina no ganaría jamás y no habría forma de clavar un
    // trazo en ella. Siendo el último recurso, el canto hace lo que se espera de
    // una escuadra: manda donde no hay nada mejor, que es a lo largo del lado.
    //
    // **Y del dibujo también, si se pide.** Estuvo restringido a las guías con
    // el argumento de que pegarse al canto de una figura de verdad convierte
    // cada figura en un carril y no deja trazar a su lado. Es cierto, pero
    // trazar un punto **sobre** un lado —un pie de altura, un punto de tangencia,
    // el sitio donde cortar— es tan corriente que quitarlo dolía más de lo que
    // ahorraba. Así que vuelve, y con su interruptor: quien lo encuentre un
    // estorbo lo apaga y se queda como estaba.
    if (mejor == null) {
        for (e in elementos) {
            if (e.id == excluir || e.isDeleted || e.locked) continue
            // La hoja se queda fuera: delimita hasta dónde llega el dibujo, no
            // es algo dibujado, y pegarse a su canto convertiría los cuatro
            // bordes del papel en carriles.
            if (e.type == ElementType.FRAME) continue
            val permitido = if (e.reference) ajustes.bordeDeGuia else ajustes.bordeDeFigura
            if (!permitido) continue
            puntoEnElPerimetro(e, p, radio)?.let {
                considerar(Anclaje(it, TipoAnclaje.BORDE, e.id))
            }
        }
    }
    return mejor
}

/** Menor es más prioritario. */
private fun prioridad(t: TipoAnclaje): Int = when (t) {
    // La intersección gana a todo: es la más difícil de acertar a pulso —no
    // existe como vértice de ninguna figura— y por tanto la que más se agradece.
    TipoAnclaje.INTERSECCION -> 0
    // El eje va con las esquinas: es igual de intencionado que un vértice.
    TipoAnclaje.ESQUINA, TipoAnclaje.EXTREMO, TipoAnclaje.EJE -> 1
    TipoAnclaje.MEDIO -> 2
    TipoAnclaje.CENTRO -> 3
    // El canto de una guía pasa por encima de sus propios vértices: a igual
    // distancia gana el vértice, o apuntar a una esquina dejaría el trazo
    // *cerca* de la esquina en vez de *en* la esquina.
    TipoAnclaje.BORDE -> 4
}

/** El punto ya enganchado, o el mismo si no había nada cerca. */
fun engancharse(
    elementos: List<Element>,
    p: Pt,
    zoom: Double,
    ajustes: AjustesEnganche = AjustesEnganche(),
    excluir: String? = null
): Pt = buscarAnclaje(elementos, p, zoom, ajustes, excluir)?.punto ?: p
