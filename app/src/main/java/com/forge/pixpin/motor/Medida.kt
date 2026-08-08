package com.forge.pixpin.motor

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Medir dentro del motor.
 *
 * Viene del croquis, que era una aplicación aparte con su propio modelo, su
 * propio renderizador y su propio editor para hacer **una sola cosa** que el
 * motor no sabía hacer: decir cuánto mide algo. Mantener dos motores por eso
 * salía carísimo —lo que se arreglaba en uno había que arreglarlo en el otro— y
 * además dejaba las dos mitades incomunicadas: no se podía acotar encima de un
 * dibujo ni dibujar encima de un plano acotado.
 *
 * Así que la parte que valía la pena se trae aquí: la escala y la cota. Lo que
 * queda es geometría pura, comprobable sin dispositivo, que es como tiene que
 * ser lo que decide una medida — equivocarse en una cota es peor que
 * equivocarse en un color.
 */

/**
 * Cuánto mide de verdad un píxel de la escena.
 *
 * Una imagen no trae su escala: una captura de un plano son píxeles y nada más.
 * La única forma honrada de saber cuánto vale cada uno es que alguien señale
 * algo de medida conocida y la diga. Eso es [calibrar], y sin ello no se puede
 * dar ninguna medida en metros — se dan en píxeles, que al menos no engaña.
 *
 * [unidad] es texto libre a propósito: en obra se mide en metros, en un plano
 * de pieza en milímetros y en una captura de un mapa en kilómetros, y ninguna
 * de las tres necesita que el motor sepa convertir entre ellas. Se calibra en
 * la unidad en la que se va a leer y se acabó.
 */
@Serializable
data class Escala(
    /** Unidades de mundo real que vale cada píxel de la escena. */
    val unidadesPorPixel: Double,
    val unidad: String = METRO,
    /** Cuántos decimales se escriben. Dos son los de una cota de obra. */
    val decimales: Int = 2
) {
    /** ¿Vale para medir? Una escala que no sea un número positivo, no. */
    val valida: Boolean
        get() = unidadesPorPixel.isFinite() && unidadesPorPixel > 0.0

    companion object {
        const val METRO = "m"

        /** Las que se ofrecen en el diálogo, de mayor a menor uso. */
        val UNIDADES = listOf("m", "cm", "mm", "km", "ft", "in")

        /**
         * La escala que sale de decir que [largoPx] píxeles son [medidaReal].
         *
         * Devuelve null cuando la calibración no puede ser cierta: una medida
         * que no sea positiva o una raya sin arrastrar. **Sin escala válida no
         * se entra a medir**: más vale no medir que medir mal.
         */
        fun calibrar(
            largoPx: Double,
            medidaReal: Double,
            unidad: String = METRO,
            decimales: Int = 2
        ): Escala? {
            if (!largoPx.isFinite() || largoPx <= 0.0) return null
            if (!medidaReal.isFinite() || medidaReal <= 0.0) return null
            return Escala(medidaReal / largoPx, unidad, decimales)
        }

        /**
         * El número que se teclea, sea con coma o con punto.
         *
         * El teclado del diálogo escribe coma porque es lo que se escribe en
         * español, pero un número pegado del portapapeles llega con punto, y
         * ninguno de los dos tiene por qué fallar.
         */
        fun leerNumero(texto: String): Double? =
            texto.trim().replace(',', '.').toDoubleOrNull()
    }
}

/**
 * El largo en píxeles de escena de un elemento de puntos.
 *
 * De la punta a la punta, en línea recta: una cota mide la distancia entre sus
 * dos extremos, no lo que recorre el trazo.
 */
fun longitudDe(element: Element): Double {
    val pts = absolutePoints(element)
    if (pts.size < 2) return 0.0
    val a = pts.first()
    val b = pts.last()
    return hypot(b.x - a.x, b.y - a.y)
}

/**
 * Lo que va escrito sobre la cota.
 *
 * Sin escala se rotula en píxeles y no se calla: una cota sin unidades que
 * pareciera metros sería exactamente la trampa que esto viene a evitar.
 *
 * El idioma llega por parámetro en vez de leerse del sistema para que la cifra
 * sea comprobable sin depender de la configuración de la máquina.
 */
fun textoDeMedida(
    largoPx: Double,
    escala: Escala?,
    locale: Locale = Locale.getDefault()
): String {
    if (escala == null || !escala.valida) {
        return String.format(locale, "%.0f px", largoPx)
    }
    val valor = largoPx * escala.unidadesPorPixel
    val decimales = escala.decimales.coerceIn(0, 6)
    return String.format(locale, "%.${decimales}f %s", valor, escala.unidad)
}

/**
 * Lo que va escrito **dentro** de la cota: cuánto mide y hacia dónde va.
 *
 * Las dos cosas juntas y en la propia raya, no en un panel aparte. Una medida
 * que hay que ir a leer a otro sitio no se usa para dibujar: se usa para
 * comprobar al final, cuando ya está todo mal. Estando encima de la raya, se
 * traza mirando el número.
 *
 * El ángulo solo sale si la raya está torcida: en una horizontal, «0°» es ruido
 * que ocupa la mitad del rótulo para no decir nada.
 */
fun textoDeCota(
    element: Element,
    escala: Escala?,
    locale: Locale = Locale.getDefault()
): String {
    val medida = textoDeMedida(longitudDe(element), escala, locale)
    val grados = anguloDe(element)
    if (kotlin.math.abs(grados) < 0.5) return medida
    return "$medida · ${String.format(locale, "%.0f", grados)}°"
}

/**
 * La medida en unidades de mundo, o null si todavía no hay escala.
 *
 * Va aparte del texto porque quien exporta o suma cotas necesita el número, no
 * la cadena con la que se pinta.
 */
fun medidaDe(element: Element, escala: Escala?): Double? {
    if (escala == null || !escala.valida) return null
    return longitudDe(element) * escala.unidadesPorPixel
}

/**
 * La misma cota, con la longitud exacta que se dicte y **sin girarla**.
 *
 * Es lo que convierte un arrastre del dedo en una medida de verdad: se apunta
 * la dirección con el dedo y se dicta el número. Devuelve el elemento tal cual
 * si no hay dirección que conservar o la medida no es positiva.
 */
fun conLongitud(element: Element, largoPxDeseado: Double): Element {
    if (!largoPxDeseado.isFinite() || largoPxDeseado <= 0.0) return element
    val pts = element.points ?: return element
    if (pts.size < 2) return element
    val a = pts.first()
    val b = pts.last()
    val actual = hypot(b.x - a.x, b.y - a.y)
    if (actual <= 0.0) return element
    val factor = largoPxDeseado / actual
    val nuevo = Pt(a.x + (b.x - a.x) * factor, a.y + (b.y - a.y) * factor)
    val puntos = listOf(a, nuevo)
    val caja = boundsOfPoints(puntos)
    return element.copy(points = puntos, width = caja.width, height = caja.height)
}

/**
 * Hacia dónde apunta la raya, en grados **como se leen en un plano**: cero a la
 * derecha y creciendo hacia arriba.
 *
 * El eje Y de la pantalla crece hacia abajo, así que el ángulo que devuelve
 * `atan2` va al revés de lo que dice cualquiera al mirar el dibujo. Se le da la
 * vuelta aquí y no en cada sitio que lo use: el que teclea «30°» quiere treinta
 * grados hacia arriba, no hacia abajo.
 */
fun anguloDe(element: Element): Double {
    val pts = absolutePoints(element)
    if (pts.size < 2) return 0.0
    val a = pts.first()
    val b = pts.last()
    if (a == b) return 0.0
    return normalizarGrados(-Math.toDegrees(kotlin.math.atan2(b.y - a.y, b.x - a.x)))
}

/**
 * La misma raya con el largo y el ángulo que se dicten, **anclada por donde
 * empieza**.
 *
 * Ese anclaje es lo que la hace servir para algo. Dibujando a mano se acierta el
 * sitio donde empieza una medida —se apoya en una esquina, en un cruce, en lo
 * que sea— y **nunca** se acierta el largo ni el ángulo. Si al corregir el
 * número se moviera el principio, habría que volver a colocarlo, y entonces no
 * se ha corregido nada: se ha vuelto a empezar.
 *
 * Los intermedios de una polilínea se pierden a propósito: lo que se está
 * diciendo con dos números es «de aquí hasta allá», y eso es una recta.
 */
fun conLargoYAngulo(element: Element, largoPx: Double, grados: Double): Element {
    if (!largoPx.isFinite() || largoPx <= 0.0) return element
    if (!grados.isFinite()) return element
    val pts = element.points ?: return element
    if (pts.isEmpty()) return element

    val a = pts.first()
    val radianes = Math.toRadians(-normalizarGrados(grados))
    val puntos = listOf(
        a,
        Pt(a.x + largoPx * kotlin.math.cos(radianes), a.y + largoPx * kotlin.math.sin(radianes))
    )
    val caja = boundsOfPoints(puntos)
    return element.copy(
        points = puntos,
        width = caja.width,
        height = caja.height,
        // Cambiar el largo o el ángulo suelta los anclajes: la punta ya no está
        // donde estaba, así que la forma a la que apuntaba deja de valer.
        startBinding = null,
        endBinding = null
    ).touched()
}

/**
 * Lo que hay que teclear para ver la medida de [element] en las unidades en que
 * se está midiendo, o en píxeles si no hay escala.
 */
fun largoEnUnidades(element: Element, escala: Escala?): Double =
    medidaDe(element, escala) ?: longitudDe(element)

/** El camino de vuelta: de lo tecleado a píxeles de escena. */
fun largoEnPixeles(valor: Double, escala: Escala?): Double =
    if (escala != null && escala.valida) valor / escala.unidadesPorPixel else valor

/**
 * ¿Está la cota más de medio giro tumbada?
 *
 * Una cota se lee de izquierda a derecha o no se lee: si la línea va hacia
 * atrás, el rótulo se voltea 180° para que el número no salga del revés. Sale
 * aquí, y no en el renderizador, porque es una regla de lectura y se puede
 * comprobar sin pintar nada.
 */
fun rotuloDelReves(gradosDeLaLinea: Double): Boolean {
    val g = normalizarGrados(gradosDeLaLinea)
    return g > 90.0 || g < -90.0
}

/** Grados en (-180, 180]. */
fun normalizarGrados(grados: Double): Double {
    if (!grados.isFinite()) return 0.0
    var g = grados % 360.0
    if (g > 180.0) g -= 360.0
    if (g <= -180.0) g += 360.0
    // -180 y 180 son el mismo ángulo; se deja el positivo para que el volteo
    // del rótulo sea una decisión y no dos según de dónde venga el número.
    return if (abs(g) == 180.0) 180.0 else g
}
