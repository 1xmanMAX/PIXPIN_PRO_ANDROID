package com.forge.pixpin.motor

import kotlin.math.abs

/**
 * Las marcas de un deslizador: **los valores que uno usa siempre**.
 *
 * Un deslizador continuo tiene una pega que se nota al segundo día: el grosor con el que
 * escribes es *ese*, y volver a él después de haber probado otro es imposible — quedas a
 * ojo, y a ojo nunca es el mismo. Con una marca puesta, el mando se pega a él al pasar
 * cerca, así que volver es un gesto y no una puntería.
 *
 * ## Cómo se usa
 *
 * 1. Se pone el deslizador donde uno quiere.
 * 2. Se toca el mango: sale un `+`.
 * 3. Al pulsarlo queda una marca en la barra.
 *
 * A partir de ahí, arrastrar cerca de la marca cae en ella. Y tocando una marca puesta
 * sale la aspa para quitarla — poner y quitar por el mismo sitio, que es donde se busca.
 *
 * ## Por qué vive aquí
 *
 * Esto es aritmética de fracciones y no tiene nada de Android, así que se puede comprobar:
 * que el imán tire solo cuando toca, que no se guarden dos marcas en el mismo sitio y que
 * el orden no dependa de en qué orden se pusieron. Son las tres cosas que, mal hechas,
 * convierten una ayuda en un estorbo.
 */

/**
 * Cuánto tiene que acercarse el mango a una marca para que se pegue, en fracción del
 * recorrido entero.
 *
 * El 3 % de una barra de cuatrocientos puntos son doce: **más o menos lo que abarca la
 * yema del dedo**, que es la medida de la que se trata. Más ancho y el imán se lleva
 * valores que uno quería de verdad; más estrecho y no se nota que está.
 */
const val IMAN_DE_LA_MARCA = 0.03

/**
 * Cuántas marcas caben en un deslizador.
 *
 * Pocas a propósito. Una barra llena de marcas es una barra con topes por todas partes:
 * deja de ser continua y no se puede afinar entre ellas, que es para lo que estaba.
 */
const val MARCAS_POR_DESLIZADOR = 6

/**
 * El valor de la marca a la que se pega [fraccion], o la propia [fraccion] si no hay
 * ninguna cerca.
 *
 * Gana **la más cercana**, no la primera: con dos marcas juntas, quedarse con la primera
 * que se mire haría que el imán tirase hacia un lado según el orden de la lista, que es
 * de esas cosas que se notan sin saber explicar por qué.
 */
fun conIman(fraccion: Float, marcas: List<Float>, margen: Float = IMAN_DE_LA_MARCA.toFloat()): Float {
    var mejor = fraccion
    var distancia = margen
    for (m in marcas) {
        val d = abs(m - fraccion)
        if (d <= distancia) {
            distancia = d
            mejor = m
        }
    }
    return mejor
}

/**
 * Las marcas con una más, o las mismas si esa ya estaba.
 *
 * «Ya estaba» quiere decir **a menos de un imán de distancia**, no exactamente igual: dos
 * marcas separadas por medio píxel son una marca que no se puede quitar, porque al tocar
 * una siempre se agarra la otra.
 *
 * Se devuelven ordenadas para que pintarlas y recorrerlas no dependa de en qué orden se
 * fueron poniendo.
 */
fun conMarca(marcas: List<Float>, nueva: Float): List<Float> {
    val v = nueva.coerceIn(0f, 1f)
    if (marcas.any { abs(it - v) <= IMAN_DE_LA_MARCA }) return marcas
    if (marcas.size >= MARCAS_POR_DESLIZADOR) return marcas
    return (marcas + v).sorted()
}

/** Las marcas sin la que esté en [donde], si es que hay alguna ahí. */
fun sinMarca(marcas: List<Float>, donde: Float): List<Float> {
    val victima = marcaEn(marcas, donde) ?: return marcas
    return marcas.filter { it != victima }
}

/** La marca que hay en [donde], o null. Es la que se toca para quitarla. */
fun marcaEn(marcas: List<Float>, donde: Float, margen: Float = IMAN_DE_LA_MARCA.toFloat()): Float? =
    marcas.filter { abs(it - donde) <= margen }.minByOrNull { abs(it - donde) }

/**
 * Qué deslizador es cada uno, para guardar sus marcas por separado.
 *
 * El grosor y la opacidad no comparten marcas ni tendría sentido: son escalas distintas
 * y lo que uno guarda en una no significa nada en la otra.
 */
enum class Deslizador { GROSOR, OPACIDAD, AUMENTO, ZONA, TEXTO, OSCURECER }

/**
 * Dónde cae el centro del mango, en píxeles, para una fracción del recorrido.
 *
 * **No es `alto · (1 − f)`**, y esa es justo la cuenta que sale mal: el mango mide lo suyo
 * y no puede salirse por los topes, así que lo que recorre es el alto **menos el mango**.
 * Con la cuenta corta, la marca se pinta hasta medio mango por encima del mango que la
 * guardó — y una marca que no está donde se puso no es una marca.
 */
fun yDelMango(fraccion: Float, alto: Float, mango: Float): Float =
    (alto - mango).coerceAtLeast(0f) * (1f - fraccion.coerceIn(0f, 1f)) + mango / 2f

/**
 * Si un toque a la altura [y] cae sobre el mango.
 *
 * Es lo que separa los dos gestos que comparten la barra: tocar el hueco lleva el mango
 * al punto tocado, y tocar **el mango** —donde está el valor que uno acaba de buscar—
 * pide guardarlo. No se pisan porque no caen en el mismo sitio.
 */
fun tocaElMango(y: Float, alto: Float, fraccion: Float, mango: Float): Boolean =
    abs(y - yDelMango(fraccion, alto, mango)) <= mango / 2f

/**
 * Las marcas de todos los deslizadores, leídas de su texto guardado.
 *
 * El formato es `grosor:0.2,0.55|opacidad:0.8`, y se lee **perdonando**: un nombre que ya
 * no existe, un número roto o una versión antigua no pueden dejar la aplicación sin
 * abrir. Lo que no se entiende, se ignora.
 */
fun marcasDeTexto(texto: String): Map<Deslizador, List<Float>> {
    if (texto.isBlank()) return emptyMap()
    val salida = mutableMapOf<Deslizador, List<Float>>()
    for (trozo in texto.split('|')) {
        val partes = trozo.split(':')
        if (partes.size != 2) continue
        val cual = Deslizador.entries.firstOrNull { it.name.equals(partes[0], ignoreCase = true) }
            ?: continue
        val valores = partes[1].split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it in 0f..1f }
            .sorted()
            .take(MARCAS_POR_DESLIZADOR)
        if (valores.isNotEmpty()) salida[cual] = valores
    }
    return salida
}

/** Y de vuelta a texto, para guardarlas. */
fun marcasATexto(marcas: Map<Deslizador, List<Float>>): String =
    marcas.filterValues { it.isNotEmpty() }
        .entries
        .sortedBy { it.key.name }
        .joinToString("|") { (cual, valores) ->
            cual.name.lowercase() + ":" + valores.joinToString(",") { redondeoDeMarca(it) }
        }

/**
 * La marca escrita corta.
 *
 * Tres decimales sobran para una barra de cuatrocientos puntos —el cuarto decimal es un
 * cuarto de píxel— y guardar el flotante entero llenaba el texto de ruido.
 */
private fun redondeoDeMarca(v: Float): String =
    (Math.round(v * 1000) / 1000.0).toString()
