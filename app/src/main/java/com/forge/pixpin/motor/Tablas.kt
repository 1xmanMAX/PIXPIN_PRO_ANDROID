package com.forge.pixpin.motor

import kotlinx.serialization.Serializable

/**
 * Puntos metidos **por coordenadas**, no a pulso.
 *
 * Hay dibujos que no se hacen mirando: un levantamiento, unos ejes, una nube de
 * medidas tomadas en obra. Ahí el dedo sobra —el dato ya existe, está en una
 * libreta— y lo que hace falta es teclearlo y que el punto aparezca donde le
 * toca, exacto.
 *
 * **El eje es uno solo para todo el dibujo.** Las tablas se distinguen por el
 * color —un perfil en rojo, otro en azul— pero todas cuentan desde el mismo
 * origen, que es lo que hace que sus puntos se puedan comparar entre sí. Con un
 * origen por tabla, dos series con las mismas coordenadas podían acabar en
 * sitios distintos del papel, y entonces el color dejaba de decir «otra serie»
 * para decir «otro mundo».
 *
 * Los puntos **no son elementos del dibujo**. Son referencias: se ven, se
 * enganchan y se editan desde su tabla, pero no se arrastran ni se borran de
 * uno en uno. Lo dibujado encima —las líneas que los unen— sí son elementos
 * normales, y esa separación es lo que permite rehacer el trazado sin volver a
 * teclear las coordenadas.
 */
@Serializable
data class TablaDeCoordenadas(
    val id: String,
    val nombre: String = "",
    /** `#rrggbb`, el mismo con el que se pintan sus puntos. */
    val color: String = COLORES_DE_TABLA.first(),
    val puntos: List<PuntoDeTabla> = emptyList(),
    /** Se puede apagar para dejar de verla sin perder lo tecleado. */
    val visible: Boolean = true
)

/** Una fila de la tabla. */
@Serializable
data class PuntoDeTabla(val x: Double = 0.0, val y: Double = 0.0)

/**
 * Los colores que se ofrecen, en orden.
 *
 * Se reparten por el círculo cromático a propósito: dos series contiguas tienen
 * que distinguirse **de un vistazo y de lejos**, que es como se miran cuando
 * hay tres encima del mismo plano.
 */
val COLORES_DE_TABLA: List<String> = listOf(
    "#e03131", "#1971c2", "#2f9e44", "#f08c00", "#9c36b5", "#0c8599"
)

/** El siguiente color libre, para que dos tablas nuevas no nazcan iguales. */
fun colorLibreDeTabla(usados: List<String>): String =
    COLORES_DE_TABLA.firstOrNull { it !in usados } ?: COLORES_DE_TABLA.first()

/**
 * Dónde cae un punto de la tabla en la escena.
 *
 * Dos conversiones, y las dos importan:
 *
 * - **La Y va hacia arriba.** En la pantalla crece hacia abajo, pero quien
 *   teclea coordenadas piensa en ejes cartesianos, donde subir es más Y. Meter
 *   un punto en (0, 10) y verlo aparecer por debajo del origen sería, con toda
 *   razón, un error.
 * - **Las unidades son las de la escala del dibujo**, si se ha calibrado: si un
 *   píxel vale 0,02 m, teclear 4 pone el punto a 200 px. Sin calibrar, un punto
 *   es una unidad, que es lo único honrado que se puede suponer.
 */
fun puntoEnEscena(origen: Pt, punto: PuntoDeTabla, escala: Escala?): Pt {
    val porUnidad = if (escala != null && escala.valida) 1.0 / escala.unidadesPorPixel else 1.0
    return Pt(
        origen.x + punto.x * porUnidad,
        origen.y - punto.y * porUnidad
    )
}

/** Todos los de una tabla, en el orden en que se teclearon. */
fun puntosEnEscena(tabla: TablaDeCoordenadas, origen: Pt, escala: Escala?): List<Pt> =
    tabla.puntos.map { puntoEnEscena(origen, it, escala) }

/**
 * El camino inverso: qué coordenadas tendría un punto de la escena.
 *
 * Hace falta para colocar el origen con el dedo y seguir viendo números que
 * cuadran, y para decir en qué coordenadas ha caído algo que se dibujó a mano.
 */
fun coordenadasDe(origen: Pt, escena: Pt, escala: Escala?): PuntoDeTabla {
    val porUnidad = if (escala != null && escala.valida) 1.0 / escala.unidadesPorPixel else 1.0
    if (porUnidad == 0.0) return PuntoDeTabla(0.0, 0.0)
    return PuntoDeTabla(
        (escena.x - origen.x) / porUnidad,
        (origen.y - escena.y) / porUnidad
    )
}

/**
 * Los anclajes que aportan las tablas.
 *
 * Van con prioridad de [TipoAnclaje.ESQUINA] porque son exactamente igual de
 * intencionados: alguien tecleó ese punto para que estuviera ahí. Trazar una
 * línea de un punto a otro tiene que pegarse sin pelearse con el dedo — es la
 * mitad de para lo que sirve meter coordenadas.
 */
fun anclajesDeTablas(
    tablas: List<TablaDeCoordenadas>, origen: Pt?, escala: Escala?
): List<Anclaje> {
    if (origen == null) return emptyList()
    return tablas.filter { it.visible }.flatMap { tabla ->
        puntosEnEscena(tabla, origen, escala).map { Anclaje(it, TipoAnclaje.ESQUINA, tabla.id) }
    }
}

/**
 * Los anclajes del **eje**: su origen y sus dos rectas.
 *
 * Las rectas no se pueden dar como una lista fija de puntos —son infinitas—, así
 * que lo que se ofrece es **la proyección del dedo sobre cada una**: el punto de
 * la recta que le queda más cerca. Como el buscador de anclajes descarta por
 * distancia, esa proyección se acepta justo cuando el dedo está a menos del
 * radio de la recta, que es exactamente lo que se quiere decir con «pegarse al
 * eje».
 *
 * [p] es dónde está el dedo, sin enganchar todavía.
 */
fun anclajesDelEje(
    origen: Pt?,
    p: Pt,
    /** Radio de captura en unidades de escena; el mismo que usa el buscador. */
    radio: Double = 0.0,
    activo: Boolean = true
): List<Anclaje> {
    if (origen == null || !activo) return emptyList()

    // **Cerca del cero manda el cero**, y no se ofrecen siquiera las rectas.
    //
    // Hace falta decirlo aquí porque el buscador elige por distancia, y a un
    // paso del origen la proyección sobre la horizontal siempre queda algo más
    // cerca que el origen mismo — es un cateto contra su hipotenusa. Sin esta
    // regla, apuntar al cero daba «encima del eje, un poco a la derecha», que
    // es lo que uno estaba tratando de evitar.
    val alOrigen = kotlin.math.hypot(p.x - origen.x, p.y - origen.y)
    if (radio > 0.0 && alOrigen <= radio) {
        return listOf(Anclaje(origen, TipoAnclaje.EJE, EJE_ID))
    }

    return listOf(
        Anclaje(origen, TipoAnclaje.EJE, EJE_ID),
        // Sobre la horizontal: se conserva la X y se toma la Y del eje.
        Anclaje(Pt(p.x, origen.y), TipoAnclaje.EJE, EJE_ID),
        // Sobre la vertical, al revés.
        Anclaje(Pt(origen.x, p.y), TipoAnclaje.EJE, EJE_ID)
    )
}

/** El eje no es un elemento, pero un anclaje tiene que decir de dónde sale. */
const val EJE_ID = "eje"

/**
 * Lee un número tecleado, con coma o con punto.
 *
 * Y admite el signo: media tabla de coordenadas vive en negativo.
 */
fun leerCoordenada(texto: String): Double? =
    texto.trim().replace(',', '.').toDoubleOrNull()
