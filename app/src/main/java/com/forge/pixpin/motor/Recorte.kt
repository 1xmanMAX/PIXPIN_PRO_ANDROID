package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Recortar y extender: **las dos operaciones de un plano hecho a mano**.
 *
 * Dibujando a escuadra y cartabón nadie traza las rayas a medida. Se trazan de
 * largo, se cruzan con lo que tengan que cruzar, y después se quita lo que
 * sobra y se estira lo que falta hasta que todo se toca donde debe. Eso es
 * exactamente lo que aquí no se podía hacer: había que acertar el largo a la
 * primera, o borrar y volver a trazar.
 *
 * - **Recortar** quita el trozo de raya que se toca, hasta donde la cruzan las
 *   demás figuras. Si el trozo está en medio, la raya se parte en dos.
 * - **Extender** estira la punta que se toca hasta la primera figura que se
 *   encuentre en su camino.
 *
 * Las dos trabajan sobre el recorrido de la raya en coordenadas de escena y
 * devuelven elementos nuevos, sin tocar la escena ni saber nada de Android: lo
 * que se puede equivocar aquí —qué trozo se va, cuál se queda, dónde queda el
 * origen del elemento después de cortarlo— se comprueba sin dispositivo.
 */

/**
 * Los cortes de [e] con [otros], como distancias recorridas desde su principio.
 *
 * Ordenados y sin repetidos: dos figuras que se cruzan en el mismo punto dan un
 * solo corte, y una tangente que roza dos veces el mismo sitio también.
 */
fun cortesDe(e: Element, otros: List<Element>): List<Double> {
    val camino = puntosDeTrazado(e)
    if (camino.size < 2) return emptyList()
    val paredes = otros.filter { !it.isDeleted }.flatMap { segmentosDe(it) }
    if (paredes.isEmpty()) return emptyList()

    val cortes = mutableListOf<Double>()
    var recorrido = 0.0
    for (i in 0 until camino.size - 1) {
        val a = camino[i]
        val b = camino[i + 1]
        val largo = hypot(b.x - a.x, b.y - a.y)
        for ((c, d) in paredes) {
            val x = interseccion(a, b, c, d) ?: continue
            cortes += recorrido + hypot(x.x - a.x, x.y - a.y)
        }
        recorrido += largo
    }
    cortes.sort()
    // Dos cortes a menos de un pelo son el mismo: un vértice donde se juntan dos
    // lados de un rectángulo corta la raya dos veces en el mismo punto, y
    // tratarlos como dos dejaría un trozo de largo cero entre ellos.
    return cortes.fold(mutableListOf<Double>()) { out, t ->
        if (out.isEmpty() || t - out.last() > MISMO_CORTE) out += t
        out
    }
}

/**
 * Recorta el trozo de [e] que contiene [p]. Devuelve los trozos que quedan.
 *
 * - Lista vacía: la raya entera se va, porque no la cruzaba nada.
 * - Un elemento: se ha ido una punta.
 * - Dos: el trozo estaba en medio y la raya se parte.
 * - **null**: no hay nada que recortar ahí y no se toca la escena.
 *
 * El corte no exige tocar el trozo con precisión: se toca *dentro* de él, en
 * cualquier punto, que es la forma en que uno piensa este gesto —«este cacho
 * fuera»— y no «córtame en tal coordenada».
 */
fun recortarEn(e: Element, otros: List<Element>, p: Pt): List<Element>? = when {
    e.isLinear -> recortarLineal(e, otros, p)

    // **El óvalo y el arco se recortan en arco**, no en polilínea: un trozo de
    // circunferencia sigue siendo una circunferencia, y convertirlo en cien
    // segmentos rectos perdería para siempre la posibilidad de seguir
    // estirándolo con el compás. Un óvalo entero al que se le quita un trozo es,
    // literalmente, un arco.
    e.type == ElementType.ELLIPSE || e.type == ElementType.ARC ->
        recortarArco(e, otros, p)

    // El rectángulo y el rombo sí: quitarles un lado los deja siendo una
    // polilínea abierta, y eso ya no es un rectángulo por mucho que se llame
    // así. En un plano es lo normal — se traza la caja y se van quitando los
    // trozos que sobran hasta que queda la pieza.
    e.type == ElementType.RECTANGLE || e.type == ElementType.DIAMOND ->
        recortarAnillo(e, otros, p)

    else -> null
}

private fun recortarLineal(e: Element, otros: List<Element>, p: Pt): List<Element>? {
    val camino = puntosDeTrazado(e)
    if (camino.size < 2) return null
    val total = largoDe(camino)
    if (total <= 0.0) return null

    val donde = recorridoHasta(camino, p) ?: return null
    val cortes = cortesDe(e, otros)

    val desde = cortes.lastOrNull { it < donde - MISMO_CORTE } ?: 0.0
    val hasta = cortes.firstOrNull { it > donde + MISMO_CORTE } ?: total

    val principio = trozo(camino, 0.0, desde)
    val final = trozo(camino, hasta, total)

    val trozos = listOfNotNull(
        principio?.let { conPuntos(e, it) },
        final?.let { conPuntos(e, it) }
    )
    // Partir en dos hace nacer un elemento: la segunda mitad no es la de antes.
    return if (trozos.size == 2) {
        listOf(trozos[0], trozos[1].copy(id = randomId(), seed = randomSeed()))
    } else trozos
}

/**
 * Recorta el trozo tocado de una figura **cerrada** de lados rectos.
 *
 * Lo que queda deja de ser un rectángulo o un rombo y pasa a ser una raya
 * abierta: es lo que se ve y lo que se espera. La raya empieza donde acababa el
 * trozo quitado y da la vuelta entera hasta donde empezaba, pasando por la
 * costura del contorno como si no existiera — que es justo por lo que hay que
 * recorrerlo en círculo y no de principio a fin.
 */
private fun recortarAnillo(e: Element, otros: List<Element>, p: Pt): List<Element>? {
    val contorno = contornosDe(e).firstOrNull()?.puntos ?: return null
    if (contorno.size < 3) return null
    val anillo = contorno + contorno.first()
    val total = largoDe(anillo)
    if (total <= 0.0) return null

    val donde = recorridoHasta(anillo, p) ?: return null
    val cortes = cortesEnCamino(anillo, otros).filter { it > MISMO_CORTE && it < total - MISMO_CORTE }

    // Con menos de dos cortes no hay trozo que quitar: la figura entera se va.
    if (cortes.size < 2) return emptyList()

    val (desde, hasta) = trozoCiclico(cortes, donde, total)
    val puntos = recorridoCiclico(anillo, hasta, desde, total) ?: return emptyList()
    return listOf(
        conPuntos(e, puntos).copy(
            type = ElementType.LINE,
            // Sin redondeo: el contorno ya viene muestreado con sus esquinas
            // redondeadas si las tenía, y pasarle además la curva de la línea lo
            // redondearía dos veces.
            roundness = null,
            backgroundColor = Element.TRANSPARENT
        )
    )
}

/**
 * Recorta el trozo tocado de un óvalo o de un arco, **y devuelve arcos**.
 *
 * Aquí no se trabaja con distancias recorridas sino con **ángulos del óvalo**:
 * un arco no guarda por dónde pasa, guarda dónde empieza y cuánto barre, así
 * que lo que hay que cortar es el barrido. La conversión sale gratis porque
 * cada cruce es un punto y todo punto tiene su ángulo en el óvalo.
 */
private fun recortarArco(e: Element, otros: List<Element>, p: Pt): List<Element>? {
    val c = getElementAbsoluteCoords(e)
    if (c.x2 - c.x1 <= 0.0 || c.y2 - c.y1 <= 0.0) return null

    val cerrado = e.type == ElementType.ELLIPSE
    val inicio = if (cerrado) 0.0 else (e.arcStart ?: 0.0)
    val barrido = if (cerrado) 2 * Math.PI else (e.arcSweep ?: (2 * Math.PI))
    if (abs(barrido) < 1e-6) return null
    val signo = if (barrido < 0) -1.0 else 1.0
    val largo = abs(barrido)

    /** Cuánto se lleva barrido al llegar a [punto], de 0 a [largo]. */
    fun avance(punto: Pt): Double {
        val bruto = (anguloEnElOvalo(e, punto) - inicio) * signo
        var t = bruto % (2 * Math.PI)
        if (t < 0) t += 2 * Math.PI
        return t
    }

    val donde = avance(p)
    if (!cerrado && donde > largo) return null

    val muestreo = if (cerrado) {
        puntosDelArco(e.copy(arcStart = 0.0, arcSweep = 2 * Math.PI))
    } else puntosDelArco(e)
    if (muestreo.size < 2) return null
    val camino = muestreo.map { Pt(e.x + it.x, e.y + it.y) }

    val cortes = cruces(camino, otros).map { avance(it) }
        .filter { cerrado || (it > MISMO_CORTE && it < largo - MISMO_CORTE) }
        .sorted()
        .fold(mutableListOf<Double>()) { out, t ->
            if (out.isEmpty() || t - out.last() > MISMO_CORTE) out += t
            out
        }

    fun arco(desdeT: Double, hastaT: Double): Element? {
        val barre = hastaT - desdeT
        if (barre < MINIMO_BARRIDO) return null
        return e.copy(
            type = ElementType.ARC,
            arcStart = inicio + signo * desdeT,
            arcSweep = signo * barre,
            backgroundColor = Element.TRANSPARENT
        ).touched()
    }

    if (cerrado) {
        if (cortes.size < 2) return emptyList()
        val (desde, hasta) = trozoCiclico(cortes, donde, 2 * Math.PI)
        var barre = desde - hasta
        if (barre < 0) barre += 2 * Math.PI
        return listOfNotNull(arco(hasta, hasta + barre))
    }

    val desde = cortes.lastOrNull { it < donde - MISMO_CORTE } ?: 0.0
    val hasta = cortes.firstOrNull { it > donde + MISMO_CORTE } ?: largo
    val trozos = listOfNotNull(arco(0.0, desde), arco(hasta, largo))
    return if (trozos.size == 2) {
        listOf(trozos[0], trozos[1].copy(id = randomId(), seed = randomSeed()))
    } else trozos
}

/**
 * Entre qué dos cortes cae [donde], **dando la vuelta si hace falta**.
 *
 * En un contorno cerrado no hay principio ni final, así que el trozo que
 * contiene al dedo puede ser el que cruza la costura: el que va del último corte
 * al primero pasando por el cero. Devolverlo como un par ordenado sin más lo
 * dejaría del revés y se quitaría justo todo lo contrario.
 */
private fun trozoCiclico(cortes: List<Double>, donde: Double, total: Double): Pair<Double, Double> {
    val anterior = cortes.lastOrNull { it < donde } ?: cortes.last()
    val siguiente = cortes.firstOrNull { it > donde } ?: cortes.first()
    return anterior to siguiente
}

/** El recorrido de [anillo] de [desde] a [hasta] hacia delante, dando la vuelta. */
private fun recorridoCiclico(
    anillo: List<Pt>, desde: Double, hasta: Double, total: Double
): List<Pt>? {
    val puntos = if (desde <= hasta) {
        trozo(anillo, desde, hasta)
    } else {
        val cola = trozo(anillo, desde, total)
        val cabeza = trozo(anillo, 0.0, hasta)
        when {
            cola == null -> cabeza
            cabeza == null -> cola
            // La costura: el último punto de la cola y el primero de la cabeza
            // son el mismo sitio, y repetirlo dejaría un tramo de largo cero que
            // luego confunde a todo el que recorra la raya.
            else -> cola + cabeza.drop(1)
        }
    }
    return puntos?.takeIf { it.size >= 2 }
}

/** Los cruces de un camino ya muestreado, como puntos. */
private fun cruces(camino: List<Pt>, otros: List<Element>): List<Pt> {
    val paredes = otros.filter { !it.isDeleted }.flatMap { segmentosDe(it) }
    val out = mutableListOf<Pt>()
    for (i in 0 until camino.size - 1) {
        for ((c, d) in paredes) {
            interseccion(camino[i], camino[i + 1], c, d)?.let { out += it }
        }
    }
    return out
}

/** Los cruces de un camino ya muestreado, como distancias recorridas. */
private fun cortesEnCamino(camino: List<Pt>, otros: List<Element>): List<Double> {
    val paredes = otros.filter { !it.isDeleted }.flatMap { segmentosDe(it) }
    val cortes = mutableListOf<Double>()
    var recorrido = 0.0
    for (i in 0 until camino.size - 1) {
        val a = camino[i]
        val b = camino[i + 1]
        for ((c, d) in paredes) {
            val x = interseccion(a, b, c, d) ?: continue
            cortes += recorrido + hypot(x.x - a.x, x.y - a.y)
        }
        recorrido += hypot(b.x - a.x, b.y - a.y)
    }
    cortes.sort()
    return cortes.fold(mutableListOf()) { out, t ->
        if (out.isEmpty() || t - out.last() > MISMO_CORTE) out += t
        out
    }
}

/**
 * Estira la punta de [e] más cercana a [p] hasta la primera figura de [otros].
 *
 * Devuelve null si no hay nada en su camino: estirar hasta el infinito no es
 * extender, es tirar una raya al vacío, y eso ya se hace dibujando.
 *
 * Se prolonga **en la dirección del último tramo**, no en la de toda la raya:
 * en una polilínea con dobleces lo que se estira es el tramo del final, que es
 * lo que uno ve como «la punta».
 */
fun extenderEn(
    e: Element, otros: List<Element>, p: Pt, alcance: Double = ALCANCE_EXTENDER
): Element? {
    val camino = puntosDeTrazado(e)
    if (camino.size < 2) return null

    val porElPrincipio = hypot(p.x - camino.first().x, p.y - camino.first().y) <
        hypot(p.x - camino.last().x, p.y - camino.last().y)

    val punta = if (porElPrincipio) camino.first() else camino.last()
    val anterior = if (porElPrincipio) camino[1] else camino[camino.size - 2]
    val dx = punta.x - anterior.x
    val dy = punta.y - anterior.y
    val largo = hypot(dx, dy)
    if (largo <= 0.0) return null

    val lejos = Pt(punta.x + dx / largo * alcance, punta.y + dy / largo * alcance)
    val paredes = otros.filter { !it.isDeleted }.flatMap { segmentosDe(it) }

    // La primera que se encuentre, no la más lejana: extender es «hasta que
    // topes», y pasarse de largo hasta la segunda dejaría la raya cruzando por
    // encima de la figura que tenía delante.
    var destino: Pt? = null
    var mejor = Double.MAX_VALUE
    for ((c, d) in paredes) {
        val x = interseccion(punta, lejos, c, d) ?: continue
        val dist = hypot(x.x - punta.x, x.y - punta.y)
        // El cero es la propia punta apoyada en algo: no es un sitio al que ir.
        if (dist > MISMO_CORTE && dist < mejor) {
            mejor = dist
            destino = x
        }
    }
    val fin = destino ?: return null

    val nuevos = camino.toMutableList()
    if (porElPrincipio) nuevos[0] = fin else nuevos[nuevos.size - 1] = fin
    return conPuntos(e, nuevos)
}

// -------------------------------------------------------------------------
// Sobre el recorrido
// -------------------------------------------------------------------------

/** Cuánto se lleva recorrido en el punto del camino más cercano a [p]. */
private fun recorridoHasta(camino: List<Pt>, p: Pt): Double? {
    var recorrido = 0.0
    var mejor = Double.MAX_VALUE
    var respuesta: Double? = null
    for (i in 0 until camino.size - 1) {
        val a = camino[i]
        val b = camino[i + 1]
        val largo = hypot(b.x - a.x, b.y - a.y)
        val d = distanceToSegment(p, a, b)
        if (d < mejor) {
            mejor = d
            val t = if (largo <= 0.0) 0.0 else
                (((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / (largo * largo))
                    .coerceIn(0.0, 1.0)
            respuesta = recorrido + t * largo
        }
        recorrido += largo
    }
    return respuesta
}

/**
 * El trozo de [camino] entre las distancias [desde] y [hasta].
 *
 * Null si el trozo no da ni para una raya: un cacho de dos píxeles no es un
 * resto, es basura que se queda estorbando al picar.
 */
private fun trozo(camino: List<Pt>, desde: Double, hasta: Double): List<Pt>? {
    if (hasta - desde < MINIMO_TROZO) return null
    val out = mutableListOf<Pt>()
    var recorrido = 0.0
    for (i in 0 until camino.size - 1) {
        val a = camino[i]
        val b = camino[i + 1]
        val largo = hypot(b.x - a.x, b.y - a.y)
        if (largo > 0.0) {
            val fin = recorrido + largo
            // El principio del trozo cae dentro de este tramo.
            if (desde in recorrido..fin && out.isEmpty()) out += enElTramo(a, b, (desde - recorrido) / largo)
            // Los vértices que quedan dentro se conservan: son los dobleces.
            if (out.isNotEmpty() && fin > desde && fin < hasta) out += b
            if (hasta in recorrido..fin) {
                out += enElTramo(a, b, (hasta - recorrido) / largo)
                break
            }
        }
        recorrido += largo
    }
    return if (out.size >= 2) out else null
}

private fun enElTramo(a: Pt, b: Pt, t: Double) =
    Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * El elemento con otro recorrido, **recolocando su origen**.
 *
 * Los puntos se guardan relativos a `x`/`y`, así que cambiar el recorrido sin
 * mover el origen desplazaría la raya entera. Es el mismo cuidado que hay que
 * tener al arrastrar el primer punto de una flecha.
 */
private fun conPuntos(e: Element, absolutos: List<Pt>): Element {
    val origen = absolutos.first()
    val relativos = absolutos.map { Pt(it.x - origen.x, it.y - origen.y) }
    val caja = boundsOfPoints(relativos)
    return e.copy(
        x = origen.x,
        y = origen.y,
        points = relativos,
        width = caja.width,
        height = caja.height,
        // Recortar rompe cualquier anclaje: la punta ya no está donde estaba.
        startBinding = null,
        endBinding = null
    ).touched()
}

/** Dos cortes más juntos que esto son el mismo corte. */
private const val MISMO_CORTE = 0.01

/** Por debajo de este largo, un resto no es una raya. */
private const val MINIMO_TROZO = 1.0

/** Y por debajo de este barrido, un resto de arco no es un arco. */
private const val MINIMO_BARRIDO = 0.02

/**
 * Hasta dónde se busca al extender, en px de escena.
 *
 * Tiene tope a propósito: sin él, una raya casi paralela a otra se estiraría
 * kilómetros para alcanzarla, y lo que aparecería en pantalla sería una raya que
 * se va y no vuelve.
 */
const val ALCANCE_EXTENDER = 4000.0
