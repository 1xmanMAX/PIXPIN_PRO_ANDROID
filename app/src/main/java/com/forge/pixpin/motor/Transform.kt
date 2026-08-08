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
    elements.map { if (it.locked) it else it.copy(x = it.x + dx, y = it.y + dy).touched() }

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

    return element.copy(
        x = nx1 + offsetX,
        y = ny1 + offsetY,
        width = newWidth,
        height = newHeight,
        // Los puntos van en relativo, así que se escalan con la caja; si no, el
        // trazo se quedaría del tamaño viejo dentro de una caja nueva. Los
        // agujeros de un relleno son puntos como los demás y se escalan igual:
        // dejarlos fuera los desplazaría respecto de su propia mancha.
        points = element.points?.map { Pt(it.x * scaleX, it.y * scaleY) },
        huecos = element.huecos?.map { anillo ->
            anillo.map { Pt(it.x * scaleX, it.y * scaleY) }
        },
        fontSize = element.fontSize?.let { it * scaleY }
    ).touched()
}

/** Redimensiona una selección entera como un bloque (`resizeMultipleElements`). */
fun resizeMultipleElements(
    elements: List<Element>,
    handle: HandleType,
    pointer: Pt,
    keepAspectRatio: Boolean = true
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
        else -> return elements
    }

    var scaleX = max(nx2 - nx1, MIN_SIZE) / b.width
    var scaleY = max(ny2 - ny1, MIN_SIZE) / b.height
    if (keepAspectRatio) {
        // Con varios elementos la proporción se fuerza siempre: escalar en un
        // solo eje deformaría los círculos y el texto de la selección.
        val s = if (abs(scaleX - 1) > abs(scaleY - 1)) scaleX else scaleY
        scaleX = s; scaleY = s
    }

    // El ancla es la esquina opuesta de la caja común.
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
            fontSize = e.fontSize?.let { it * scaleY }
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
    val axis = if (horizontal) b.midX else b.midY

    return elements.map { e ->
        if (e.locked) return@map e
        val c = getElementAbsoluteCoords(e)
        val newX = if (horizontal) 2 * axis - c.x2 else e.x
        val newY = if (horizontal) e.y else 2 * axis - c.y2

        // Los puntos se reflejan sobre el eje de su propia caja; la imagen usa
        // el signo de `scale`, que es como Excalidraw guarda el volteo para no
        // tener que reescribir el archivo.
        val newPoints = e.points?.map {
            if (horizontal) Pt(e.width - it.x, it.y) else Pt(it.x, e.height - it.y)
        }
        val newHuecos = e.huecos?.map { anillo ->
            anillo.map {
                if (horizontal) Pt(e.width - it.x, it.y) else Pt(it.x, e.height - it.y)
            }
        }
        val newScale = if (e.type == ElementType.IMAGE) {
            if (horizontal) listOf(-e.scale[0], e.scale[1])
            else listOf(e.scale[0], -e.scale[1])
        } else e.scale

        e.copy(
            x = newX,
            y = newY,
            points = newPoints,
            huecos = newHuecos,
            scale = newScale,
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
