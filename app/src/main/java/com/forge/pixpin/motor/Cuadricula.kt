package com.forge.pixpin.motor

/**
 * El fondo cuadriculado: **el papel pautado**.
 *
 * Dibujando a mano sobre un lienzo en blanco no hay contra qué orientarse. No es
 * el imán —eso engancha a lo que ya hay dibujado— sino lo de antes: la referencia
 * que está ahí **antes del primer trazo**, para que la primera raya salga recta y
 * la segunda a la misma distancia. Es la hoja cuadriculada de un cuaderno.
 *
 * Tiene que ser **finísima**. Una rejilla que se ve tanto como el dibujo compite
 * con él y cansa a los dos minutos; la buena es la que se nota cuando la buscas y
 * desaparece cuando dibujas. Por eso hay dos formas: la de líneas, para alinear,
 * y la de puntos, que dice lo mismo ensuciando la mitad.
 *
 * ## Y no sale al exportar
 *
 * Es andamio del editor, no parte del dibujo. Lo mismo que los tiradores de la
 * selección o las cabezas de los clavos: se ve mientras se trabaja y no viaja en
 * el archivo. Por eso vive en un parámetro del renderizador y no en la escena.
 *
 * Aquí solo está la cuenta —dónde caen las rayas y cada cuánto—, sin Android, que
 * es lo que se puede equivocar en silencio: una rejilla que a poco zoom se cierra
 * en una malla negra no se ve como un fallo, se ve como que el lienzo se ha
 * ensuciado.
 */

/** Cómo se pauta el fondo. */
enum class Cuadricula { NINGUNA, LINEAS, PUNTOS }

/** El lado del cuadro, en píxeles de escena. Veinte es el del original. */
const val PASO_DE_CUADRICULA = 20.0

/**
 * Lo poco que puede medir un cuadro **en pantalla** antes de que la rejilla deje
 * de leerse.
 *
 * Por debajo de esto las rayas se tocan entre sí y lo que queda es una trama
 * gris que tapa el dibujo. En vez de dejar de pintarla —que sería perder la
 * referencia justo cuando se mira el conjunto— se **dobla el paso**: la rejilla
 * se hace más basta y sigue diciendo lo mismo.
 */
private const val MINIMO_EN_PANTALLA = 14.0

/**
 * El paso que toca a este zoom, doblando el de partida hasta que se vea.
 *
 * Es también la guarda de rendimiento: sin ella, alejarse al diez por ciento
 * sobre un lienzo infinito pide **miles** de rayas por fotograma, que es la
 * forma más tonta de que una aplicación de dibujo se arrastre.
 */
fun pasoDeCuadricula(zoom: Double, paso: Double = PASO_DE_CUADRICULA): Double {
    if (paso <= 0.0) return PASO_DE_CUADRICULA
    val z = zoom.coerceAtLeast(0.0001)
    var p = paso
    // Doblando —y no multiplicando por cualquier cosa— los cuadros nuevos
    // siguen cayendo encima de los viejos: al alejar, la rejilla se hace basta
    // sin que las rayas se muevan de sitio.
    var vueltas = 0
    while (p * z < MINIMO_EN_PANTALLA && vueltas < 12) {
        p *= 2
        vueltas++
    }
    return p
}

/**
 * Dónde caen las rayas entre [desde] y [hasta], en coordenadas de escena.
 *
 * Alineadas al cero de la escena y no al borde de la pantalla: así la rejilla se
 * queda quieta respecto del dibujo mientras se panea, que es lo único que la
 * hace servir de referencia.
 */
fun lineasDeCuadricula(desde: Double, hasta: Double, paso: Double): List<Double> {
    if (paso <= 0.0 || !desde.isFinite() || !hasta.isFinite() || hasta < desde) {
        return emptyList()
    }
    val primera = kotlin.math.ceil(desde / paso) * paso
    val cuantas = ((hasta - primera) / paso).toInt() + 1
    if (cuantas <= 0) return emptyList()
    // Tope duro: con coordenadas absurdas —un zoom degenerado, un NaN que se
    // coló— esto se llama en cada fotograma y no puede intentar pintar un millón
    // de rayas antes de darse cuenta.
    val tope = cuantas.coerceAtMost(MAXIMAS_LINEAS)
    return (0 until tope).map { primera + it * paso }
}

private const val MAXIMAS_LINEAS = 400
