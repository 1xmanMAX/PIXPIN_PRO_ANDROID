package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Picado: qué elemento hay bajo el dedo. Port de `collision.ts`.
 *
 * La estructura del original se conserva entera porque es lo que lo hace
 * rápido: **primero un descarte contra la caja rotada, y solo si pasa, el test
 * exacto**. El comentario de Excalidraw dice que el descarte se ahorra el 99%
 * de los casos, y con una lista larga de elementos eso es la diferencia entre
 * responder al toque o no.
 */

/** Umbral de picado por defecto, en px de escena. Se divide por el zoom. */
const val DEFAULT_HIT_THRESHOLD = 10.0

/**
 * ¿Se puede agarrar el elemento por dentro, o solo por el borde?
 *
 * Port de `shouldTestInside`. La regla es la que espera la mano: una forma
 * rellena se coge por el centro, una vacía solo por la raya. Un contorno sin
 * relleno con el interior «sólido» haría imposible seleccionar lo que hay
 * debajo de él.
 */
fun shouldTestInside(element: Element): Boolean {
    // La cota se coge por la raya, como la flecha: lo que encierra es lo que se
    // está midiendo, y agarrarla por ahí robaría el toque a lo de debajo.
    if (element.type == ElementType.ARROW || element.isMeasure) return false
    // **La lupa se coge por dentro**, como una foto. Es una ventana con cosas
    // pintadas, no un aro vacío: pedir que se acierte en el borde era pedir
    // acertarle a una raya de dos puntos, y encima solo respondía por los
    // costados —el borde que se probaba era el de su caja, no el de su forma—.
    if (element.type == ElementType.LUPA) return true
    // El foco también, pero su dentro es **el anillo**: el hueco tiene que dejar
    // pasar el toque, que ahí está lo que se está resaltando. Ver
    // [isPointInElement].
    if (element.type == ElementType.SPOTLIGHT) return true

    // La reglita se coge por dentro: es una placa, no un contorno.
    if (element.isEscalaGrafica) return true

    // **El sólido se coge por dentro aunque no tenga relleno**, al contrario que
    // un rectángulo vacío. Un rectángulo sin fondo es un aro y hay que dejar ver
    // y tocar lo que encierra; una caja en volumen no encierra nada que esté
    // detrás, es un bulto, y pedir que se acierte en una arista de un píxel para
    // moverla la volvería inservible con el dedo.
    if (element.isSolido) return true

    val draggableFromInside =
        (element.hasBackground && !isTransparent(element.backgroundColor)) ||
            element.type == ElementType.TEXT ||
            !element.boundElements.isNullOrEmpty()

    if (element.type == ElementType.LINE || element.isFreeDraw) {
        return draggableFromInside && isPathALoop(element.points)
    }
    return draggableFromInside || element.type == ElementType.IMAGE
}

/**
 * ¿El punto [p] toca el elemento? (`hitElementItself`).
 *
 * [threshold] es la tolerancia en coordenadas de escena; quien llame debe
 * dividir por el zoom para que el margen sea constante en pantalla.
 */
fun hitElementItself(
    p: Pt, element: Element, threshold: Double, vista: Vista = Vista.CERO
): Boolean {
    // Descarte rápido: si ni siquiera está en la caja rotada, no hay nada que
    // mirar. Aquí se cae la inmensa mayoría de los elementos.
    //
    // **El sólido se descarta contra otra caja.** La suya es la huella, y lo que
    // dibuja se sale de ella por arriba y por un lado —lo lleva la proyección—,
    // así que este descarte le habría comido el toque en toda la parte alta de
    // la caja: se veía el volumen y no se podía coger. Ver [envolturaDeSolido].
    val dentro = if (element.isSolido) {
        envolturaDeSolido(element).let {
            p.x >= it.x1 - threshold && p.x <= it.x2 + threshold &&
                p.y >= it.y1 - threshold && p.y <= it.y2 + threshold
        }
    } else {
        isPointInRotatedBounds(p, element, threshold)
    }
    if (!dentro) return false

    return if (shouldTestInside(element)) {
        isPointInElement(p, element, vista) ||
            isPointOnElementOutline(p, element, threshold, vista)
    } else {
        isPointOnElementOutline(p, element, threshold, vista)
    }
}

/** Pasa el punto al sistema del elemento sin rotar: simplifica todos los tests. */
private fun toLocal(p: Pt, element: Element): Pt {
    val c = getElementAbsoluteCoords(element)
    return pointRotateRads(p, Pt(c.cx, c.cy), -element.angle)
}

/** ¿El punto cae **dentro** de la forma? */
fun isPointInElement(p: Pt, element: Element, vista: Vista = Vista.CERO): Boolean {
    val local = toLocal(p, element)
    val c = getElementAbsoluteCoords(element)
    return when (element.type) {
        // **Un punto se coge por su redondel o por su letra.** Su caja no tiene
        // tamaño —es el punto y ya está—, así que preguntar si el dedo cae
        // «dentro» de ella no acertaría nunca.
        ElementType.PUNTO ->
            hypot(p.x - element.x, p.y - element.y) <= RADIO_DE_AGARRE ||
                tocaLaEtiqueta(element, p, 0.0)

        // El mosaico y el foco se agarran por dentro: son manchas, no contornos,
        // y buscarles el borde con el dedo sería absurdo.
        // El plano se coge **por dentro**: es una lámina con sus reglas, y
        // buscarle el borde con el dedo para moverlo sería absurdo.
        ElementType.RECTANGLE, ElementType.IMAGE, ElementType.TEXT,
        ElementType.MOSAIC, ElementType.LUPA,
        ElementType.ESCALA_GRAFICA,
        ElementType.PLANO ->
            local.x in c.x1..c.x2 && local.y in c.y1..c.y2

        // **El foco es un anillo: el hueco no cuenta.** Lo que hay dentro es
        // justo lo que se está resaltando, y comerse su toque dejaría sin poder
        // tocar aquello para lo que se puso el foco.
        ElementType.SPOTLIGHT -> {
            val dentroDelMarco = local.x in c.x1..c.x2 && local.y in c.y1..c.y2
            val zona = regionDeLaLupa(element)
            val enElHueco = local.x in zona.x1..zona.x2 && local.y in zona.y1..zona.y2
            dentroDelMarco && !enElHueco
        }

        // El número de serie es un círculo, y pequeño: se pica como la elipse.
        ElementType.ELLIPSE, ElementType.SERIAL -> {
            val rx = (c.x2 - c.x1) / 2
            val ry = (c.y2 - c.y1) / 2
            if (rx <= 0 || ry <= 0) false
            else {
                val dx = (local.x - c.cx) / rx
                val dy = (local.y - c.cy) / ry
                dx * dx + dy * dy <= 1.0
            }
        }

        ElementType.DIAMOND -> {
            val hw = (c.x2 - c.x1) / 2
            val hh = (c.y2 - c.y1) / 2
            if (hw <= 0 || hh <= 0) false
            // Un rombo es |x|/a + |y|/b <= 1 respecto del centro.
            else abs(local.x - c.cx) / hw + abs(local.y - c.cy) / hh <= 1.0
        }

        ElementType.LINE, ElementType.FREEDRAW ->
            isPointInPolygon(local, absolutePoints(element))

        // El relleno se coge por la mancha, **y no por sus agujeros**: lo que se
        // ve dentro de un agujero es lo que hay debajo, así que el toque tiene
        // que llegarle a eso y no al relleno que lo rodea.
        ElementType.REGION -> puntoEnRegion(element, local)

        // El marco NO se agarra por dentro: es una hoja, y todo lo que dibujes
        // encima quedaría por debajo de ella al picar. Se coge por su borde.
        ElementType.FRAME -> false

        // Ni la flecha, ni la cota, ni el arco tienen dentro: son rayas. Se
        // cogen por encima.
        ElementType.ARROW, ElementType.MEASURE, ElementType.ARC -> false

        /**
         * **El sólido se coge por sus caras, y no vale la caja envolvente.**
         *
         * Se probó primero con [cajaDeSolido], que es un rectángulo y sale de dos
         * comparaciones. Es lo que hay que descartar: la silueta de una caja
         * isométrica es un hexágono, y su caja envolvente le añade **cuatro
         * triángulos vacíos**, uno en cada esquina, que juntos son casi un tercio
         * de su superficie. Cada uno de esos triángulos es un trozo de pantalla
         * donde se ve el dibujo de debajo y donde tocando se selecciona la caja:
         * el fallo clásico de «no puedo coger lo que está detrás del volumen».
         * Y con dos cajas escalonadas —que es cómo se dibujan— las envolventes se
         * solapan y la de arriba roba los toques de media cara de la de abajo.
         *
         * El polígono cuesta tres pruebas de punto en cuadrilátero, y va después
         * del descarte de [hitElementItself], así que en la práctica se hace una
         * vez por gesto. Se paga.
         *
         * Se compara contra `p` y no contra `local`: las caras vienen ya en
         * coordenadas de escena y sin inclinar, porque un sólido no usa
         * [Element.angle] (ver [contornosDe]).
         */
        ElementType.SOLIDO -> carasDeElemento(element, vista)
            .any { isPointInPolygon(p, it.poligono) }
    }
}

/** ¿El punto cae **sobre el trazo**, con [threshold] de tolerancia? */
fun isPointOnElementOutline(
    p: Pt, element: Element, threshold: Double, vista: Vista = Vista.CERO
): Boolean {
    val local = toLocal(p, element)
    val c = getElementAbsoluteCoords(element)
    return when (element.type) {
        // El punto es todo trazo: no hay dentro ni fuera que distinguir.
        ElementType.PUNTO -> isPointInElement(p, element) ||
            hypot(p.x - element.x, p.y - element.y) <= RADIO_DE_AGARRE + threshold

        ElementType.RECTANGLE, ElementType.IMAGE, ElementType.TEXT,
        ElementType.MOSAIC, ElementType.SPOTLIGHT, ElementType.LUPA, ElementType.FRAME,
        ElementType.ESCALA_GRAFICA, ElementType.PLANO -> {
            val corners = listOf(
                Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2)
            )
            hitsPolyline(local, corners + corners.first(), threshold)
        }

        ElementType.DIAMOND -> {
            val v = listOf(
                Pt(c.cx, c.y1), Pt(c.x2, c.cy), Pt(c.cx, c.y2), Pt(c.x1, c.cy)
            )
            hitsPolyline(local, v + v.first(), threshold)
        }

        ElementType.ELLIPSE, ElementType.SERIAL -> {
            val rx = (c.x2 - c.x1) / 2
            val ry = (c.y2 - c.y1) / 2
            if (rx <= 0 || ry <= 0) return false
            // Aproximación por muestreo: la distancia exacta a una elipse no
            // tiene forma cerrada y resolverla numéricamente aquí sería caro
            // para lo que aporta. 64 muestras dan un error muy por debajo del
            // umbral de toque de un dedo.
            var best = Double.MAX_VALUE
            var prev: Pt? = null
            for (i in 0..ELLIPSE_SAMPLES) {
                val a = 2 * Math.PI * i / ELLIPSE_SAMPLES
                val cur = Pt(c.cx + rx * kotlin.math.cos(a), c.cy + ry * kotlin.math.sin(a))
                if (prev != null) best = minOf(best, distanceToSegment(local, prev, cur))
                prev = cur
            }
            best <= threshold
        }

        ElementType.LINE, ElementType.ARROW, ElementType.MEASURE,
        ElementType.FREEDRAW ->
            hitsPolyline(local, absolutePoints(element), threshold)

        ElementType.REGION -> anillosDeRegion(element).any {
            hitsPolyline(local, it + it.first(), threshold)
        }

        // El arco no guarda sus puntos —guarda el óvalo y cuánto barre—, así
        // que se generan para picarlo. Es lo mismo que se pinta, y se compara
        // contra `local`, que es el dedo con la inclinación deshecha: los dos en
        // el sistema sin girar del elemento. Ver [puntosDelArco].
        ElementType.ARC -> hitsPolyline(
            local,
            puntosDelArco(element).map { Pt(element.x + it.x, element.y + it.y) },
            threshold
        )

        // Las aristas que se ven son los bordes de las caras que se ven, así que
        // el trazo del sólido son sus mismos polígonos recorridos. Contra `p` y
        // no contra `local`, por lo mismo que en [isPointInElement].
        ElementType.SOLIDO -> carasDeElemento(element, vista).any {
            hitsPolyline(p, it.poligono + it.poligono.first(), threshold)
        }
    }
}

/** Los puntos de un lineal en coordenadas de escena (los guarda relativos). */
fun absolutePoints(element: Element): List<Pt> =
    element.points?.map { Pt(element.x + it.x, element.y + it.y) } ?: emptyList()

private fun hitsPolyline(p: Pt, points: List<Pt>, threshold: Double): Boolean {
    if (points.size < 2) {
        return points.size == 1 && hypot(p.x - points[0].x, p.y - points[0].y) <= threshold
    }
    for (i in 0 until points.size - 1) {
        if (distanceToSegment(p, points[i], points[i + 1]) <= threshold) return true
    }
    return false
}

/** Test de punto en polígono por lanzamiento de rayo (par/impar). */
private fun isPointInPolygon(p: Pt, polygon: List<Pt>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > p.y) != (b.y > p.y) &&
            p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/**
 * El elemento de más arriba bajo el punto (`getElementAtPosition`).
 *
 * Recorre al revés porque el orden de la lista **es** el orden de pintado: el
 * último dibujado es el que se ve encima y el que debe ganar el toque.
 */
fun getElementAtPosition(
    elements: List<Element>, p: Pt, threshold: Double = DEFAULT_HIT_THRESHOLD,
    vista: Vista = Vista.CERO
): Element? {
    for (i in elements.indices.reversed()) {
        val e = elements[i]
        if (e.isDeleted || e.locked) continue
        if (hitElementItself(p, e, threshold, vista)) return e
    }
    return null
}

/** Todos los que toca el punto, de arriba abajo (`getAllHoveredElementAtPoint`). */
fun getElementsAtPosition(
    elements: List<Element>, p: Pt, threshold: Double = DEFAULT_HIT_THRESHOLD,
    vista: Vista = Vista.CERO
): List<Element> = elements.filter {
    !it.isDeleted && !it.locked && hitElementItself(p, it, threshold, vista)
}.reversed()

/** Modo de selección por área: encerrar del todo o basta con rozar. */
enum class BoxSelectionMode { CONTAIN, OVERLAP }

/** Los que caen dentro del rectángulo de selección (`getElementsWithinSelection`). */
fun getElementsWithinSelection(
    elements: List<Element>,
    selection: Bounds,
    mode: BoxSelectionMode = BoxSelectionMode.CONTAIN
): List<Element> = elements.filter { e ->
    if (e.isDeleted || e.locked) return@filter false
    // Del sólido, lo que ocupa dibujado y no su huella: encerrarlo con el
    // recuadro pide encerrar **lo que se ve**, que es lo que sobresale por
    // arriba. Ver [envolturaDeSolido].
    val b = if (e.isSolido) envolturaDeSolido(e) else getElementBounds(e)
    when (mode) {
        BoxSelectionMode.CONTAIN -> boundsContains(selection, b)
        BoxSelectionMode.OVERLAP -> boundsOverlap(selection, b)
    }
}

/**
 * Selección con lazo: los que corta o encierra un trazo a mano alzada.
 *
 * Excalidraw comprueba de verdad la intersección del camino con cada forma;
 * aquí se hace lo mismo en dos pasos: el descarte por caja del lazo, y luego
 * o bien el centro dentro del lazo, o bien algún punto del lazo tocando el
 * elemento. Coge lo mismo en la práctica y no necesita el motor de
 * intersecciones completo.
 */
fun getElementsWithinLasso(
    elements: List<Element>, lasso: List<Pt>, threshold: Double = DEFAULT_HIT_THRESHOLD,
    vista: Vista = Vista.CERO
): List<Element> {
    if (lasso.size < 3) return emptyList()
    val lassoBounds = boundsOfPoints(lasso)
    return elements.filter { e ->
        if (e.isDeleted || e.locked) return@filter false
        // Del sólido, lo que ocupa dibujado: con su huella a secas, un lazo
        // trazado alrededor de la caja que se ve no la cogía.
        val b = if (e.isSolido) envolturaDeSolido(e) else getElementBounds(e)
        if (!boundsOverlap(lassoBounds, b)) return@filter false
        if (isPointInPolygon(Pt(b.midX, b.midY), lasso)) return@filter true
        lasso.any { hitElementItself(it, e, threshold, vista) }
    }
}

internal fun boundsOfPoints(points: List<Pt>): Bounds {
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (p in points) {
        minX = minOf(minX, p.x); minY = minOf(minY, p.y)
        maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
    }
    return Bounds(minX, minY, maxX, maxY)
}

private const val ELLIPSE_SAMPLES = 64
