package com.forge.pixpin.motor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * El cronograma: **un plan dibujado, no una hoja de cálculo**.
 *
 * En una reunión el cronograma se dibuja siempre igual: una columna de nombres a la
 * izquierda, una escala arriba y una barra por fila. Hacerlo con rectángulos sueltos son
 * doce elementos que hay que alinear a mano y que se descuadran en cuanto se mueve uno; y
 * a mano alzada sale torcido y sin escala, que es justo lo que un cronograma tiene que
 * tener para decir algo.
 *
 * Aquí es **una figura con datos dentro**. La rejilla se reparte sola dentro de la caja,
 * las barras se arrastran por encima de ella —moverlas y estirarlas es el gesto entero— y
 * añadir una tarea recoloca lo demás. Estirar la figura estira el plan sin descuadrarlo,
 * porque nada está en coordenadas: todo está en filas y columnas.
 *
 * Lo que **no** es: un gestor de proyectos. No hay dependencias, ni recursos, ni fechas
 * reales. Es el croquis de cuándo va cada cosa.
 */

/** Qué parte del ancho se lleva la columna de los nombres. */
const val ANCHO_DE_LOS_NOMBRES = 0.32

/** Lo que ocupa la fila de la escala, arriba, en tanto por uno del alto. */
const val ALTO_DE_LA_ESCALA = 0.16

/** Lo más fina que se deja una barra, en columnas: por debajo no se puede ni agarrar. */
const val MINIMA_BARRA = 0.25

/** A cuánto engancha el arrastre de una barra: a cuartos de columna. */
const val PASO_DEL_CRONOGRAMA = 0.25

/** Cuánto de una barra, por su punta, estira en vez de mover. */
private const val PUNTA_DE_LA_BARRA = 0.3

/** Y como mucho esto en píxeles: en una barra larga, la punta no puede ser medio metro. */
private const val PUNTA_MAXIMA = 24.0

/** La caja del elemento, que es donde vive todo lo demás. */
private fun caja(e: Element): Bounds = getElementAbsoluteCoords(e).toBounds()

/** Dónde empieza la zona de las barras: a la derecha de los nombres. */
fun xDeLaEscala(e: Element): Double = caja(e).let { it.x1 + it.width * ANCHO_DE_LOS_NOMBRES }

/** Dónde empieza la primera fila: debajo de la escala. */
fun yDeLasFilas(e: Element): Double = caja(e).let { it.y1 + it.height * ALTO_DE_LA_ESCALA }

/** Lo que mide una columna de la escala. */
fun anchoDeColumna(e: Element): Double {
    val b = caja(e)
    val periodos = max(1, e.periodos)
    return (b.x2 - xDeLaEscala(e)) / periodos
}

/** Lo que mide una fila. Con cero tareas, la caja entera: es el hueco donde caerá la primera. */
fun altoDeFila(e: Element): Double {
    val b = caja(e)
    val filas = max(1, e.tareas.size)
    return (b.y2 - yDeLasFilas(e)) / filas
}

/**
 * La caja de la barra de la tarea [i], en coordenadas de escena.
 *
 * La barra no ocupa la fila entera de alto: deja un respiro arriba y abajo para que dos
 * filas seguidas no se lean como un bloque. Es el mismo motivo por el que una tabla lleva
 * relleno en las celdas.
 */
fun barraDeTarea(e: Element, i: Int): Bounds? {
    val t = e.tareas.getOrNull(i) ?: return null
    val col = anchoDeColumna(e)
    val fila = altoDeFila(e)
    if (col <= 0.0 || fila <= 0.0) return null
    val x0 = xDeLaEscala(e) + t.desde * col
    val y0 = yDeLasFilas(e) + i * fila
    val respiro = fila * RESPIRO_DE_LA_BARRA
    return Bounds(
        x0,
        y0 + respiro,
        x0 + max(t.cuanto, MINIMA_BARRA) * col,
        y0 + fila - respiro
    )
}

private const val RESPIRO_DE_LA_BARRA = 0.18

/** Las rayas verticales de la escala, de la primera a la última. */
fun columnasDelCronograma(e: Element): List<Double> {
    val col = anchoDeColumna(e)
    if (col <= 0.0) return emptyList()
    return (0..max(1, e.periodos)).map { xDeLaEscala(e) + it * col }
}

/**
 * De qué tamaño va la letra de un cronograma, **sin poder reventar**.
 *
 * Aquí vivía un fallo que tiró la aplicación en un móvil de verdad. La cuenta era
 * `coerceIn(1.0, alto * 0.8)`, y `coerceIn` **no recorta cuando el máximo baja del
 * mínimo: lanza**. Con la figura pequeña o con muchas filas, una fila mide menos de un
 * píxel y medio y la excepción saltaba — y saltaba **al dibujar**, así que se llevaba la
 * aplicación desde el hilo de la pantalla, lejos de donde uno estaba tocando.
 *
 * Está aquí y no repetida en los tres sitios que la necesitan —la pantalla, el SVG y el
 * PDF— justamente por eso: una cuenta que puede reventar no puede estar copiada tres
 * veces, porque se arregla en dos.
 */
fun letraDelCronograma(e: Element, alto: Double): Double {
    val pedida = e.fontSize ?: (alto * 0.5)
    val tope = max(1.0, alto * 0.8)
    return pedida.coerceIn(1.0, tope)
}

/** Qué se hace al agarrar una barra: moverla entera o estirarla por la punta. */
enum class ManoEnLaBarra { MOVER, ESTIRAR }

/** Qué barra se ha tocado y para qué, o null si el dedo no cayó en ninguna. */
data class ToqueEnBarra(val indice: Int, val mano: ManoEnLaBarra)

/**
 * Qué barra toca el dedo, **y si la toca por la punta**.
 *
 * Por el cuerpo se mueve y por la punta derecha se estira, que es el reparto que uno
 * espera de una barra y el que no obliga a un tirador aparte —en un cronograma de diez
 * filas, veinte tiradores serían una alfombra de bolitas.
 *
 * La punta es un tanto por uno del largo **con tope**: en una barra de media pantalla, un
 * tercio de punta sería absurdo.
 */
fun toqueEnBarra(e: Element, p: Pt, margen: Double = 0.0): ToqueEnBarra? {
    for (i in e.tareas.indices) {
        val b = barraDeTarea(e, i) ?: continue
        if (p.y < b.y1 - margen || p.y > b.y2 + margen) continue
        if (p.x < b.x1 - margen || p.x > b.x2 + margen) continue
        val punta = min(b.width * PUNTA_DE_LA_BARRA, PUNTA_MAXIMA)
        val mano = if (p.x >= b.x2 - punta) ManoEnLaBarra.ESTIRAR else ManoEnLaBarra.MOVER
        return ToqueEnBarra(i, mano)
    }
    return null
}

/**
 * La tarea [i] después de arrastrarla hasta [p].
 *
 * Se engancha a cuartos de columna: a pulso salen barras que empiezan en 2,37 y el dibujo
 * deja de decir «esta empieza cuando acaba aquella», que es lo único que un cronograma
 * bocetado tiene que decir. Es el mismo imán de la retícula del lienzo, en una dimensión.
 *
 * [agarre] es por dónde se cogió la barra, en columnas desde su principio: sin él, la
 * barra pega un salto al empezar a moverla para ponerse con su origen bajo el dedo.
 */
fun tareaArrastrada(
    e: Element,
    i: Int,
    mano: ManoEnLaBarra,
    p: Pt,
    agarre: Double
): TareaDelCronograma? {
    val t = e.tareas.getOrNull(i) ?: return null
    val col = anchoDeColumna(e)
    if (col <= 0.0) return null
    val enColumnas = (p.x - xDeLaEscala(e)) / col
    return when (mano) {
        ManoEnLaBarra.MOVER -> {
            val desde = enganchado(enColumnas - agarre)
            t.copy(desde = desde.coerceIn(0.0, max(0.0, e.periodos - t.cuanto)))
        }
        ManoEnLaBarra.ESTIRAR -> {
            val cuanto = enganchado(enColumnas - t.desde)
            t.copy(cuanto = cuanto.coerceIn(MINIMA_BARRA, max(MINIMA_BARRA, e.periodos - t.desde)))
        }
    }
}

private fun enganchado(v: Double): Double =
    if (!v.isFinite()) 0.0 else (v / PASO_DEL_CRONOGRAMA).roundToInt() * PASO_DEL_CRONOGRAMA

/**
 * Una tarea más, **detrás de la última y del mismo largo**.
 *
 * Nace donde acaba la anterior porque es lo que uno va a querer nueve de cada diez veces
 * —un plan se lee de arriba abajo y las cosas van una detrás de otra— y corregirlo es
 * arrastrar. Naciendo en cero habría que moverlas todas.
 */
fun conTareaNueva(e: Element, nombre: String): Element {
    val ultima = e.tareas.lastOrNull()
    val desde = ultima?.let { it.desde + it.cuanto } ?: 0.0
    val cuanto = ultima?.cuanto ?: 1.0
    val periodos = max(e.periodos, kotlin.math.ceil(desde + cuanto).toInt())
    return e.copy(
        tareas = e.tareas + TareaDelCronograma(
            nombre = nombre,
            desde = desde.coerceAtMost(max(0.0, periodos - cuanto)),
            cuanto = cuanto
        ),
        periodos = periodos
    ).touched()
}

/** Una tarea menos: la última. Con ninguna, la figura se queda como está. */
fun sinLaUltimaTarea(e: Element): Element =
    if (e.tareas.isEmpty()) e else e.copy(tareas = e.tareas.dropLast(1)).touched()

/** Una columna más o menos en la escala, sin bajar de una. */
fun conPeriodos(e: Element, cuantos: Int): Element =
    e.copy(periodos = cuantos.coerceIn(1, MAXIMO_DE_PERIODOS)).touched()

/**
 * Hasta cuántas columnas se deja llegar.
 *
 * Cuarenta ya son cuarenta rayas verticales en el ancho de una figura: a partir de ahí no
 * se distingue una de otra y lo que se dibuja es una trama, no una escala.
 */
const val MAXIMO_DE_PERIODOS = 40
