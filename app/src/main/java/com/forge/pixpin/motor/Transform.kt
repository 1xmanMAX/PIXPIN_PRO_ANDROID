package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Mover, redimensionar, rotar y voltear. Port de `resizeElements.ts`.
 *
 * Lo delicado de todo esto es una sola cosa: **el punto de anclaje no se puede
 * mover**. Al arrastrar la esquina SE, la NW tiene que quedarse clavada donde
 * está, y con el elemento girado eso deja de ser una resta de coordenadas
 * porque el centro de rotación se desplaza al cambiar el tamaño. El truco del
 * original, que es el que se copia aquí, es calcular la caja nueva en el
 * sistema sin rotar y luego corregir `x`/`y` con la diferencia entre dónde
 * *quedaría* el ancla y dónde *tiene que estar*.
 */

/** Ángulo al que se enganchan las rotaciones con el gesto de bloqueo: 15°. */
const val SHIFT_LOCKING_ANGLE = Math.PI / 12

/** Tamaño mínimo al redimensionar, en px de escena. */
private const val MIN_SIZE = 1.0

/** Marca el elemento como modificado. Todo cambio debe pasar por aquí. */
fun Element.touched(): Element = copy(
    version = version + 1,
    versionNonce = randomVersionNonce(),
    updated = System.currentTimeMillis()
)

/** Desplaza elementos (`dragElements`). */
fun dragElements(elements: List<Element>, dx: Double, dy: Double): List<Element> =
    elements.map {
        if (it.locked) it
        // **La lupa deja su foco donde estaba.** Apartar el cristal es
        // precisamente lo que convierte una lupa apoyada en una lupa de lámina:
        // el detalle se ve al lado, con su guía, sin taparlo. Si el foco fuera
        // detrás no habría forma de conseguir eso.
        //
        // Se fija **aquí**, al empezar a moverla, porque hasta entonces valía
        // null —«miro lo que tengo debajo»— y con null puesto el foco seguiría
        // al cristal para siempre. Ver [focoDe].
        else if (it.type == ElementType.LUPA) {
            it.copy(foco = focoDe(it), x = it.x + dx, y = it.y + dy).touched()
        } else {
            it.copy(x = it.x + dx, y = it.y + dy).touched()
        }
    }

/**
 * Redimensiona un elemento arrastrando [handle] hasta [pointer].
 *
 * [keepAspectRatio] conserva la proporción; [resizeFromCenter] fija el centro
 * en vez de la esquina opuesta.
 */
fun resizeSingleElement(
    element: Element,
    handle: HandleType,
    pointer: Pt,
    keepAspectRatio: Boolean = false,
    resizeFromCenter: Boolean = false
): Element {
    if (element.locked || handle == HandleType.ROTATION) return element

    val c = getElementAbsoluteCoords(element)
    val oldCenter = Pt(c.cx, c.cy)

    // Al sistema del elemento: a partir de aquí se razona como si no girase.
    val local = pointRotateRads(pointer, oldCenter, -element.angle)

    var nx1 = c.x1
    var ny1 = c.y1
    var nx2 = c.x2
    var ny2 = c.y2

    when (handle) {
        HandleType.NW -> { nx1 = local.x; ny1 = local.y }
        HandleType.NE -> { nx2 = local.x; ny1 = local.y }
        HandleType.SW -> { nx1 = local.x; ny2 = local.y }
        HandleType.SE -> { nx2 = local.x; ny2 = local.y }
        HandleType.N -> ny1 = local.y
        HandleType.S -> ny2 = local.y
        HandleType.W -> nx1 = local.x
        HandleType.E -> nx2 = local.x
        HandleType.ROTATION -> return element
        // Las puntas no estiran la caja: mueven un punto, y de eso se encarga
        // el controlador con `withPointMovedTo`. Aquí no pintan nada.
        HandleType.POINT_START, HandleType.POINT_END,
        HandleType.POINT_MID, HandleType.POINT_ADD -> return element
    }

    if (resizeFromCenter) {
        // El centro manda: lo que crece por un lado crece igual por el otro.
        val halfW = max(abs(local.x - c.cx), MIN_SIZE / 2)
        val halfH = max(abs(local.y - c.cy), MIN_SIZE / 2)
        if (handle.affectsX) { nx1 = c.cx - halfW; nx2 = c.cx + halfW }
        if (handle.affectsY) { ny1 = c.cy - halfH; ny2 = c.cy + halfH }
    }

    var newWidth = max(nx2 - nx1, MIN_SIZE)
    var newHeight = max(ny2 - ny1, MIN_SIZE)

    if (keepAspectRatio && c.x2 - c.x1 > 0 && c.y2 - c.y1 > 0) {
        val ratio = (c.y2 - c.y1) / (c.x2 - c.x1)
        // Manda el eje que más ha cambiado: si no, la forma «resbala» cuando el
        // dedo va casi en diagonal.
        if (abs(newWidth - (c.x2 - c.x1)) > abs(newHeight - (c.y2 - c.y1))) {
            newHeight = newWidth * ratio
        } else {
            newWidth = newHeight / ratio
        }
        // Recolocar el borde que no manda, respetando cuál es el ancla.
        if (handle.anchorsRight) nx1 = nx2 - newWidth else nx2 = nx1 + newWidth
        if (handle.anchorsBottom) ny1 = ny2 - newHeight else ny2 = ny1 + newHeight
    } else {
        if (handle.anchorsRight) nx1 = nx2 - newWidth else nx2 = nx1 + newWidth
        if (handle.anchorsBottom) ny1 = ny2 - newHeight else ny2 = ny1 + newHeight
    }

    // Dónde tiene que seguir estando el ancla, en coordenadas de escena.
    val anchorLocal = handle.anchorPoint(c)
    val anchorGlobal = pointRotateRads(anchorLocal, oldCenter, element.angle)

    // Dónde caería si colocásemos la caja nueva sin corregir nada.
    val newCenter = Pt((nx1 + nx2) / 2, (ny1 + ny2) / 2)
    val anchorAfter = pointRotateRads(
        handle.anchorPointOf(nx1, ny1, nx2, ny2), newCenter, element.angle
    )

    val offsetX = anchorGlobal.x - anchorAfter.x
    val offsetY = anchorGlobal.y - anchorAfter.y

    val scaleX = if (c.x2 - c.x1 != 0.0) newWidth / (c.x2 - c.x1) else 1.0
    val scaleY = if (c.y2 - c.y1 != 0.0) newHeight / (c.y2 - c.y1) else 1.0

    // Los puntos van en relativo, así que se escalan con la caja; si no, el
    // trazo se quedaría del tamaño viejo dentro de una caja nueva.
    val puntos = element.points?.map { Pt(it.x * scaleX, it.y * scaleY) }

    /**
     * **El origen de una raya o de un garabato no es la esquina de su caja.**
     *
     * Sus puntos van relativos al primero, y el primero no tiene por qué ser el
     * de más arriba a la izquierda: un trazo hecho hacia la izquierda los tiene
     * negativos, así que su borde cae en `x + minX` y no en `x` — es lo que
     * calcula [getElementAbsoluteCoords], de donde salen `c.x1` y `c.y1`.
     *
     * Colocando el origen en `nx1` a secas, el trazo se iba justo esos `minX` ya
     * escalados: un salto de cien píxeles nada más agarrar el tirador. Se
     * descuentan aquí, que es donde se sabe cuánto han crecido.
     */
    val sangria = if (!puntos.isNullOrEmpty() && (element.isFreeDraw || element.isLinear)) {
        boundsOfPoints(puntos).let { Pt(it.x1, it.y1) }
    } else Pt(0.0, 0.0)

    /**
     * **El plano se estira de dos maneras, y las dos son «agrandar».**
     *
     * Por una **esquina** crecen la caja y la unidad a la vez: los números que
     * había siguen siendo los mismos, repartidos en más sitio. Es lo que se hace
     * cuando el plano no se lee.
     *
     * Por un **lado** crece solo la caja y la unidad se queda como está: cabe
     * más intervalo, así que **aparecen más números**. Es lo que se hace cuando
     * la función se sale por la derecha.
     *
     * Lo que las separa es de dónde se tira, y por eso el plano es de los pocos
     * que enseña los tiradores del medio. Ver [Plano].
     */
    val unidadNueva = if (element.isPlano) {
        if (handle.esEsquina) element.unidadDelPlano * escalaDeLaEsquina(scaleX, scaleY)
        else element.unidadDelPlano
    } else element.unidad

    return element.copy(
        x = nx1 + offsetX - sangria.x,
        y = ny1 + offsetY - sangria.y,
        width = newWidth,
        height = newHeight,
        unidad = unidadNueva,
        points = puntos,
        // Los agujeros de un relleno son puntos como los demás y se escalan
        // igual: dejarlos fuera los desplazaría respecto de su propia mancha.
        // No llevan sangría porque un relleno **sí** tiene su origen en la
        // esquina de su caja. Ver [nuevaRegion].
        huecos = element.huecos?.map { anillo ->
            anillo.map { Pt(it.x * scaleX, it.y * scaleY) }
        },
        fontSize = element.fontSize?.let { it * scaleY }
    ).touched()
}

/**
 * Redimensiona una selección entera como un bloque (`resizeMultipleElements`).
 *
 * **Por una esquina en proporción; por un lado, aplastando ese eje.**
 *
 * Antes forzaba la proporción siempre y solo aceptaba las esquinas, así que una
 * figura estampada de la lista —que llega agrupada, o sea, varios elementos— no
 * había forma de estrecharla: o crecía entera o no cambiaba. Y eso es justo lo
 * que hace falta para meter algo grande en un hueco pequeño, que es la mitad de
 * para lo que se guarda una figura.
 *
 * La regla es la de cualquier programa de dibujo, y no hay que explicarla: la
 * esquina conserva la forma, el lado la estruja. [keepAspectRatio] a null deja
 * que la decida el tirador; con true se fuerza la proporción, que es lo que pide
 * el segundo dedo apoyado o el interruptor de figuras perfectas.
 */
fun resizeMultipleElements(
    elements: List<Element>,
    handle: HandleType,
    pointer: Pt,
    keepAspectRatio: Boolean? = null
): List<Element> {
    if (elements.size < 2) return elements
    val b = getCommonBounds(elements)
    if (b.width <= 0 || b.height <= 0) return elements

    var nx1 = b.x1; var ny1 = b.y1; var nx2 = b.x2; var ny2 = b.y2
    when (handle) {
        HandleType.NW -> { nx1 = pointer.x; ny1 = pointer.y }
        HandleType.NE -> { nx2 = pointer.x; ny1 = pointer.y }
        HandleType.SW -> { nx1 = pointer.x; ny2 = pointer.y }
        HandleType.SE -> { nx2 = pointer.x; ny2 = pointer.y }
        HandleType.N -> ny1 = pointer.y
        HandleType.S -> ny2 = pointer.y
        HandleType.W -> nx1 = pointer.x
        HandleType.E -> nx2 = pointer.x
        else -> return elements
    }

    // El eje que el tirador no toca no se escala: un tirador del lado derecho
    // no puede cambiar el alto, por mucho que el dedo suba y baje.
    var scaleX = if (handle.affectsX) max(nx2 - nx1, MIN_SIZE) / b.width else 1.0
    var scaleY = if (handle.affectsY) max(ny2 - ny1, MIN_SIZE) / b.height else 1.0

    if (keepAspectRatio ?: handle.esEsquina) {
        // Manda el eje que más ha cambiado: si no, la selección «resbala»
        // cuando el dedo va casi en diagonal.
        val s = if (abs(scaleX - 1) > abs(scaleY - 1)) scaleX else scaleY
        scaleX = s; scaleY = s
    }

    // El ancla es el borde opuesto al que se arrastra.
    val ax = if (handle.anchorsRight) b.x2 else b.x1
    val ay = if (handle.anchorsBottom) b.y2 else b.y1

    return elements.map { e ->
        if (e.locked) return@map e
        e.copy(
            x = ax + (e.x - ax) * scaleX,
            y = ay + (e.y - ay) * scaleY,
            width = max(e.width * scaleX, MIN_SIZE),
            height = max(e.height * scaleY, MIN_SIZE),
            points = e.points?.map { Pt(it.x * scaleX, it.y * scaleY) },
            huecos = e.huecos?.map { anillo ->
                anillo.map { Pt(it.x * scaleX, it.y * scaleY) }
            },
            // La unidad de un plano acompaña al eje que se escala; aplastado en
            // uno solo se queda como estaba, que es lo que deja el plano de ser
            // cuadrado sin mentir sobre lo que mide. Ver [Plano].
            unidad = e.unidad?.let { if (scaleX == scaleY) it * scaleX else it },
            fontSize = e.fontSize?.let { it * minOf(scaleX, scaleY) }
        ).touched()
    }
}

/**
 * Rota un elemento hasta apuntar a [pointer] (`rotateSingleElement`).
 *
 * El `5π/2` del original no es magia: `atan2` devuelve el ángulo respecto al
 * eje X y el tirador de rotación está arriba, así que hay que girar un cuarto
 * de vuelta; se suma `2π` de más para que el resultado entre en `normalize`
 * siempre positivo.
 */
fun rotateSingleElement(
    element: Element, pointer: Pt, discreteAngle: Boolean = false
): Element {
    if (element.locked) return element
    val c = getElementAbsoluteCoords(element)
    var angle = (5 * Math.PI) / 2 + atan2(pointer.y - c.cy, pointer.x - c.cx)
    if (discreteAngle) {
        angle += SHIFT_LOCKING_ANGLE / 2
        angle -= angle % SHIFT_LOCKING_ANGLE
    }
    // Rotar suelta los anclajes: la flecha ya no apunta a donde apuntaba.
    return element.copy(
        angle = normalizeAngle(angle),
        startBinding = null,
        endBinding = null
    ).touched()
}

/** Rota una selección entera alrededor de su centro común. */
fun rotateMultipleElements(
    elements: List<Element>, pointer: Pt, discreteAngle: Boolean = false
): List<Element> {
    if (elements.isEmpty()) return elements
    val b = getCommonBounds(elements)
    val center = Pt(b.midX, b.midY)
    var angle = (5 * Math.PI) / 2 + atan2(pointer.y - center.y, pointer.x - center.x)
    if (discreteAngle) {
        angle += SHIFT_LOCKING_ANGLE / 2
        angle -= angle % SHIFT_LOCKING_ANGLE
    }
    return elements.map { e ->
        if (e.locked) return@map e
        val ec = getElementAbsoluteCoords(e)
        // Cada elemento gira sobre sí mismo **y** orbita el centro común: sin
        // lo segundo la selección se deshace en cuanto se rota.
        val moved = pointRotateRads(Pt(ec.cx, ec.cy), center, angle - e.angle)
        e.copy(
            x = e.x + (moved.x - ec.cx),
            y = e.y + (moved.y - ec.cy),
            angle = normalizeAngle(angle),
            startBinding = null,
            endBinding = null
        ).touched()
    }
}

/**
 * Voltea la selección en horizontal (`actionFlip`).
 *
 * Se hace sobre la caja común y no elemento a elemento: volteando cada uno por
 * su cuenta la composición se queda igual y no parece que haya pasado nada.
 */
fun flipHorizontal(elements: List<Element>): List<Element> =
    flip(elements, horizontal = true)

/** Voltea la selección en vertical. */
fun flipVertical(elements: List<Element>): List<Element> =
    flip(elements, horizontal = false)

private fun flip(elements: List<Element>, horizontal: Boolean): List<Element> {
    if (elements.isEmpty()) return elements
    val b = getCommonBounds(elements)
    val eje = if (horizontal) b.midX else b.midY

    fun espejo(p: Pt): Pt =
        if (horizontal) Pt(2 * eje - p.x, p.y) else Pt(p.x, 2 * eje - p.y)

    return elements.map { e ->
        if (e.locked) return@map e

        // **Las rayas y el lápiz se reflejan por sus puntos, no por su caja.**
        //
        // Su caja sale de los puntos —no de `x`/`y`—, así que espejar la caja
        // por un lado y los puntos por otro los descoloca entre sí en cuanto el
        // primer punto no es el de arriba a la izquierda: un garabato trazado
        // hacia la izquierda tiene puntos negativos y salía disparado en vez de
        // quedarse donde estaba. Reflejando en la escena y rehaciendo el
        // elemento con [conPuntosAbsolutos] las dos cosas no pueden discrepar.
        val reflejado = if ((e.isFreeDraw || e.isLinear) && !e.points.isNullOrEmpty()) {
            e.conPuntosAbsolutos(absolutePoints(e).map { espejo(it) })
        } else {
            val c = getElementAbsoluteCoords(e)
            e.copy(
                x = if (horizontal) 2 * eje - c.x2 else e.x,
                y = if (horizontal) e.y else 2 * eje - c.y2,
                // Aquí sí valen las cuentas sobre la caja: el relleno guarda su
                // contorno y sus agujeros relativos a la esquina de la suya.
                points = e.points?.map {
                    if (horizontal) Pt(e.width - it.x, it.y) else Pt(it.x, e.height - it.y)
                },
                huecos = e.huecos?.map { anillo ->
                    anillo.map {
                        if (horizontal) Pt(e.width - it.x, it.y) else Pt(it.x, e.height - it.y)
                    }
                }
            )
        }

        reflejado.copy(
            // La imagen usa el signo de `scale`, que es como Excalidraw guarda
            // el volteo para no tener que reescribir el archivo.
            scale = if (e.type == ElementType.IMAGE) {
                if (horizontal) listOf(-e.scale[0], e.scale[1])
                else listOf(e.scale[0], -e.scale[1])
            } else e.scale,
            // Reflejar invierte el sentido de giro.
            angle = normalizeAngle(-e.angle),
            startArrowhead = if (horizontal) e.endArrowhead else e.startArrowhead,
            endArrowhead = if (horizontal) e.startArrowhead else e.endArrowhead
        ).touched()
    }
}

/** Duplica elementos desplazados un poco (`actionDuplicateSelection`). */
fun duplicateElements(elements: List<Element>, offset: Double = 10.0): List<Element> {
    // Los grupos se renumeran en bloque: si se copiase el `groupId` tal cual,
    // la copia y el original quedarían agrupados entre sí.
    val groupMap = elements.flatMap { it.groupIds }.distinct().associateWith { randomId() }
    return elements.map { e ->
        e.copy(
            id = randomId(),
            x = e.x + offset,
            y = e.y + offset,
            seed = randomSeed(),
            version = 1,
            versionNonce = randomVersionNonce(),
            groupIds = e.groupIds.mapNotNull { groupMap[it] },
            // Los anclajes no se copian: apuntarían a los elementos originales.
            startBinding = null,
            endBinding = null,
            boundElements = null,
            updated = System.currentTimeMillis()
        )
    }
}

// -------------------------------------------------------------------------
// Qué esquina ancla cada tirador
// -------------------------------------------------------------------------

/** Las cuatro esquinas: las que escalan en vez de extender. Ver [Plano]. */
internal val HandleType.esEsquina: Boolean
    get() = this == HandleType.NW || this == HandleType.NE ||
        this == HandleType.SW || this == HandleType.SE

/**
 * Cuánto se agranda al tirar de una esquina.
 *
 * La menor de las dos escalas, no la media: con la mayor, arrastrar en diagonal
 * hacia fuera agranda más de lo que se ha tirado por el lado corto y el plano se
 * sale de su propia caja.
 */
private fun escalaDeLaEsquina(sx: Double, sy: Double): Double =
    minOf(sx, sy).takeIf { it.isFinite() && it > 0.0 } ?: 1.0

private val HandleType.anchorsRight: Boolean
    get() = this == HandleType.NW || this == HandleType.SW || this == HandleType.W

private val HandleType.anchorsBottom: Boolean
    get() = this == HandleType.NW || this == HandleType.NE || this == HandleType.N

private val HandleType.affectsX: Boolean
    get() = this != HandleType.N && this != HandleType.S

private val HandleType.affectsY: Boolean
    get() = this != HandleType.E && this != HandleType.W

private fun HandleType.anchorPoint(c: AbsoluteCoords): Pt =
    anchorPointOf(c.x1, c.y1, c.x2, c.y2)

private fun HandleType.anchorPointOf(
    x1: Double, y1: Double, x2: Double, y2: Double
): Pt = Pt(
    if (anchorsRight) x2 else x1,
    if (anchorsBottom) y2 else y1
)
