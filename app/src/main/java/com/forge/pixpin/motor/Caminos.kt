package com.forge.pixpin.motor

/**
 * Todo lo que se dibuja, reducido a **una sola forma de camino**.
 *
 * El motor genera geometría de tres maneras distintas: el garabato rugoso sale
 * como [Op], el contorno del lápiz como una nube de puntos que hay que coser con
 * cuadráticas, y las siluetas y los anillos como polilíneas. Cada salida
 * —pantalla, SVG, PDF— tenía que saber convertir las tres, y eso son tres
 * traducciones por salida y tres sitios donde pueden separarse.
 *
 * Aquí se convierten **todas a [Op]** una sola vez. A partir de ahí cualquier
 * salida sabe pintar cualquier cosa sabiendo escribir cuatro órdenes: mover,
 * recta, curva y cerrar. Que resulta que son exactamente las que tienen un
 * camino de SVG y un flujo de PDF.
 *
 * Es geometría pura y se comprueba sin dispositivo, que es donde tiene que
 * estar lo que comparten tres salidas.
 */

/** Una polilínea, abierta o cerrada. */
fun opsDePuntos(puntos: List<Pt>, cerrado: Boolean): List<Op> {
    if (puntos.isEmpty()) return emptyList()
    val ops = ArrayList<Op>(puntos.size + 1)
    ops.add(Op.Move(puntos[0].x, puntos[0].y))
    for (i in 1 until puntos.size) ops.add(Op.LineTo(puntos[i].x, puntos[i].y))
    if (cerrado) ops.add(Op.Cerrar)
    return ops
}

/** Varios anillos en un mismo camino: con la regla par/impar, son agujeros. */
fun opsDeAnillos(anillos: List<List<Pt>>, minimo: Int = 3): List<Op> =
    anillos.filter { it.size >= minimo }.flatMap { opsDePuntos(it, cerrado = true) }

/**
 * El contorno del lápiz, cerrado y **suavizado**.
 *
 * Los puntos que devuelve `getStroke` son muchos y muy juntos; unirlos con
 * rectas deja el borde de la mancha dentado. Excalidraw los cose con
 * cuadráticas que pasan por los **puntos medios** de cada par: cada punto hace
 * de tirador y el camino queda continuo en tangente sin calcular nada más.
 *
 * Aquí esas cuadráticas se pasan a cúbicas, que **no es una aproximación**: toda
 * cuadrática es una cúbica con los tiradores a dos tercios del camino hacia el
 * suyo. Se hace para no tener que arrastrar un quinto tipo de orden hasta las
 * tres salidas por una curva que sale en un solo sitio.
 */
fun opsSuaveCerrado(puntos: List<Pt>): List<Op> {
    if (puntos.isEmpty()) return emptyList()
    if (puntos.size < 4) return opsDePuntos(puntos, cerrado = true)

    val ops = ArrayList<Op>(puntos.size + 2)
    var desde = puntos[0]
    ops.add(Op.Move(desde.x, desde.y))
    ops.add(cubicaDeCuadratica(desde, puntos[1], medio(puntos[1], puntos[2])))
    desde = medio(puntos[1], puntos[2])
    for (i in 2 until puntos.size - 1) {
        val hasta = medio(puntos[i], puntos[i + 1])
        ops.add(cubicaDeCuadratica(desde, puntos[i], hasta))
        desde = hasta
    }
    ops.add(Op.Cerrar)
    return ops
}

/**
 * La cúbica que recorre exactamente la misma curva que una cuadrática.
 *
 * Los dos tiradores de la cúbica van a dos tercios del camino de cada extremo
 * hacia el tirador de la cuadrática. Es una identidad, no un ajuste: las dos
 * curvas coinciden punto por punto.
 */
private fun cubicaDeCuadratica(desde: Pt, tirador: Pt, hasta: Pt): Op.CurveTo = Op.CurveTo(
    desde.x + 2.0 / 3.0 * (tirador.x - desde.x), desde.y + 2.0 / 3.0 * (tirador.y - desde.y),
    hasta.x + 2.0 / 3.0 * (tirador.x - hasta.x), hasta.y + 2.0 / 3.0 * (tirador.y - hasta.y),
    hasta.x, hasta.y
)

private fun medio(a: Pt, b: Pt) = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)

/** Gira un camino entero alrededor de un punto. Lo necesita quien no tenga matriz. */
fun List<Op>.girado(centro: Pt, radianes: Double): List<Op> {
    if (radianes == 0.0) return this
    fun g(x: Double, y: Double): Pt = pointRotateRads(Pt(x, y), centro, radianes)
    return map { op ->
        when (op) {
            is Op.Move -> g(op.x, op.y).let { Op.Move(it.x, it.y) }
            is Op.LineTo -> g(op.x, op.y).let { Op.LineTo(it.x, it.y) }
            is Op.CurveTo -> {
                val a = g(op.x1, op.y1)
                val b = g(op.x2, op.y2)
                val c = g(op.x, op.y)
                Op.CurveTo(a.x, a.y, b.x, b.y, c.x, c.y)
            }
            Op.Cerrar -> Op.Cerrar
        }
    }
}
