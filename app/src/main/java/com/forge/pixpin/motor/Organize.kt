package com.forge.pixpin.motor

/**
 * Organización: orden de pintado, grupos, alinear y distribuir.
 * Port de `zindex.ts`, `groups.ts`, `align.ts` y `distribute.ts`.
 *
 * **El orden de la lista es el orden de pintado.** No hay campo `z`: el último
 * elemento de la lista es el que se ve encima. Todas las operaciones de esta
 * sección son, en el fondo, mover trozos de lista.
 */

// -------------------------------------------------------------------------
// Orden de pintado (z-index)
// -------------------------------------------------------------------------

/**
 * Trozos de índices consecutivos (`toContiguousGroups`).
 *
 * Una selección salteada —el 1, el 2 y el 7— se mueve como dos bloques
 * independientes, no como uno. Si se movieran de golpe, los elementos sueltos
 * se juntarían y el usuario vería reordenarse cosas que no había tocado.
 */
internal fun toContiguousGroups(indices: List<Int>): List<List<Int>> {
    if (indices.isEmpty()) return emptyList()
    val sorted = indices.sorted()
    val groups = mutableListOf<MutableList<Int>>()
    var current = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
        if (sorted[i] == sorted[i - 1] + 1) current += sorted[i]
        else { groups += current; current = mutableListOf(sorted[i]) }
    }
    groups += current
    return groups
}

/**
 * Índice al que saltar en [direction] desde [boundary] (`getTargetIndex`).
 *
 * Si el vecino pertenece a un grupo, se salta el grupo **entero**: pasar por
 * encima de la mitad de un grupo lo partiría en dos capas.
 */
private fun getTargetIndex(
    elements: List<Element>,
    boundary: Int,
    direction: Int,
    movingGroupIds: Set<String>
): Int {
    val candidate = boundary + direction
    if (candidate < 0 || candidate >= elements.size) return -1

    // El grupo del vecino que el elemento que se mueve NO comparte.
    val neighbourGroup = elements[candidate].groupIds.lastOrNull { it !in movingGroupIds }
        ?: return candidate

    return if (direction < 0) {
        // Hacia el fondo: hasta el primer elemento del grupo vecino.
        elements.indexOfFirst { neighbourGroup in it.groupIds }
    } else {
        elements.indexOfLast { neighbourGroup in it.groupIds }
    }
}

private fun shiftByOne(
    elements: List<Element>, selectedIds: Set<String>, direction: Int
): List<Element> {
    val indices = elements.indices.filter { elements[it].id in selectedIds }
    if (indices.isEmpty()) return elements

    var groups = toContiguousGroups(indices)
    // Hacia arriba se procesa de atrás adelante: si no, el primer bloque en
    // moverse pisa el sitio al que iba el siguiente.
    if (direction > 0) groups = groups.reversed()

    val out = elements.toMutableList()
    for (group in groups) {
        val leading = group.first()
        val trailing = group.last()
        val boundary = if (direction < 0) leading else trailing
        val movingGroupIds = group.flatMap { elements[it].groupIds }.toSet()
        val target = getTargetIndex(out, boundary, direction, movingGroupIds)
        if (target == -1 || target == boundary) continue

        val moving = group.map { out[it] }
        // Se quita de atrás adelante para no invalidar los índices restantes.
        for (i in group.sortedDescending()) out.removeAt(i)
        val insertAt = if (direction < 0) target else target - moving.size + 1
        out.addAll(insertAt.coerceIn(0, out.size), moving)
    }
    return out
}

/** Envía un paso hacia el fondo (`moveOneLeft`). */
fun moveOneLeft(elements: List<Element>, selectedIds: Set<String>): List<Element> =
    shiftByOne(elements, selectedIds, -1)

/** Trae un paso hacia delante (`moveOneRight`). */
fun moveOneRight(elements: List<Element>, selectedIds: Set<String>): List<Element> =
    shiftByOne(elements, selectedIds, 1)

/** Envía al fondo del todo (`moveAllLeft`). */
fun moveAllLeft(elements: List<Element>, selectedIds: Set<String>): List<Element> {
    val (moving, rest) = elements.partition { it.id in selectedIds }
    return moving + rest
}

/** Trae al frente del todo (`moveAllRight`). */
fun moveAllRight(elements: List<Element>, selectedIds: Set<String>): List<Element> {
    val (moving, rest) = elements.partition { it.id in selectedIds }
    return rest + moving
}

// -------------------------------------------------------------------------
// Grupos
// -------------------------------------------------------------------------

/**
 * Agrupa la selección (`actionGroup`).
 *
 * `groupIds` es una **pila**, no un id suelto: el último es el grupo más
 * exterior. Así se pueden anidar grupos dentro de grupos sin más estructura, y
 * desagrupar es quitar el de arriba.
 *
 * Además los elementos se juntan en la lista, porque un grupo cuyos miembros
 * están separados por elementos ajenos no se puede mover de capa sin partirlo.
 */
fun groupElements(elements: List<Element>, selectedIds: Set<String>): List<Element> {
    val selected = elements.filter { it.id in selectedIds }
    if (selected.size < 2) return elements

    val groupId = randomId()
    val regrouped = selected.map { it.copy(groupIds = it.groupIds + groupId).touched() }

    // Se reinsertan donde estaba el más alto, para que el grupo no cambie de
    // capa al crearse.
    val lastIndex = elements.indexOfLast { it.id in selectedIds }
    val rest = elements.filter { it.id !in selectedIds }
    val insertAt = (lastIndex - selected.size + 1).coerceIn(0, rest.size)
    return rest.subList(0, insertAt) + regrouped + rest.subList(insertAt, rest.size)
}

/** Deshace el grupo más exterior de la selección (`actionUngroup`). */
fun ungroupElements(elements: List<Element>, selectedIds: Set<String>): List<Element> {
    val outermost = elements
        .filter { it.id in selectedIds }
        .mapNotNull { it.groupIds.lastOrNull() }
        .toSet()
    if (outermost.isEmpty()) return elements
    return elements.map { e ->
        if (e.groupIds.lastOrNull() in outermost) {
            e.copy(groupIds = e.groupIds.dropLast(1)).touched()
        } else e
    }
}

/** Todos los elementos que arrastra consigo seleccionar [element]. */
fun getElementsInGroupOf(elements: List<Element>, element: Element): List<Element> {
    val groupId = element.groupIds.lastOrNull() ?: return listOf(element)
    return elements.filter { groupId in it.groupIds }
}

/** La selección partida por grupos: cada grupo cuenta como una unidad. */
internal fun selectedElementsByGroup(
    selected: List<Element>
): List<List<Element>> {
    val grouped = LinkedHashMap<String, MutableList<Element>>()
    val loose = mutableListOf<List<Element>>()
    for (e in selected) {
        val gid = e.groupIds.lastOrNull()
        if (gid == null) loose += listOf(e)
        else grouped.getOrPut(gid) { mutableListOf() } += e
    }
    return grouped.values.map { it.toList() } + loose
}

// -------------------------------------------------------------------------
// Alinear
// -------------------------------------------------------------------------

enum class AlignAxis { X, Y }
enum class AlignPosition { START, CENTER, END }

/**
 * Alinea la selección dentro de su caja común (`alignElements`).
 *
 * **Los grupos se mueven enteros.** Si cada elemento se alinease por su cuenta,
 * alinear a la izquierda desharía visualmente cualquier grupo apilando todos
 * sus miembros en la misma columna.
 */
fun alignElements(
    elements: List<Element>,
    selectedIds: Set<String>,
    axis: AlignAxis,
    position: AlignPosition
): List<Element> {
    val selected = elements.filter { it.id in selectedIds }
    if (selected.size < 2) return elements

    val box = getCommonBounds(selected)
    val moved = HashMap<String, Element>()

    for (group in selectedElementsByGroup(selected)) {
        val gb = getCommonBounds(group)
        val delta = when (axis) {
            AlignAxis.X -> when (position) {
                AlignPosition.START -> box.x1 - gb.x1
                AlignPosition.END -> box.x2 - gb.x2
                AlignPosition.CENTER -> (box.x1 + box.x2) / 2 - (gb.x1 + gb.x2) / 2
            }
            AlignAxis.Y -> when (position) {
                AlignPosition.START -> box.y1 - gb.y1
                AlignPosition.END -> box.y2 - gb.y2
                AlignPosition.CENTER -> (box.y1 + box.y2) / 2 - (gb.y1 + gb.y2) / 2
            }
        }
        for (e in group) {
            moved[e.id] = when (axis) {
                AlignAxis.X -> e.copy(x = e.x + delta)
                AlignAxis.Y -> e.copy(y = e.y + delta)
            }.touched()
        }
    }
    return elements.map { moved[it.id] ?: it }
}

// -------------------------------------------------------------------------
// Distribuir
// -------------------------------------------------------------------------

/**
 * Reparte la selección con huecos iguales (`distributeElements`).
 *
 * Si los elementos no caben —el hueco calculado sale negativo porque se solapan
 * más de lo que da la caja— se reparten por **centros** en vez de por huecos,
 * que es la rama rara del original y la única forma de que el resultado siga
 * pareciendo repartido.
 */
fun distributeElements(
    elements: List<Element>, selectedIds: Set<String>, axis: AlignAxis
): List<Element> {
    val selected = elements.filter { it.id in selectedIds }
    if (selected.size < 3) return elements

    val bounds = getCommonBounds(selected)
    val groups = selectedElementsByGroup(selected)
        .map { it to getCommonBounds(it) }
        .sortedBy { if (axis == AlignAxis.X) it.second.midX else it.second.midY }

    fun extentOf(b: Bounds) = if (axis == AlignAxis.X) b.width else b.height
    fun startOf(b: Bounds) = if (axis == AlignAxis.X) b.x1 else b.y1
    fun endOf(b: Bounds) = if (axis == AlignAxis.X) b.x2 else b.y2
    fun midOf(b: Bounds) = if (axis == AlignAxis.X) b.midX else b.midY

    val span = groups.sumOf { extentOf(it.second) }
    val totalExtent = extentOf(bounds)
    val step = (totalExtent - span) / (groups.size - 1)

    val moved = HashMap<String, Element>()

    if (step < 0) {
        val i0 = groups.indexOfFirst { startOf(it.second) == startOf(bounds) }
        val i1 = groups.indexOfLast { endOf(it.second) == endOf(bounds) }
        if (i0 < 0 || i1 < 0 || i0 == i1) return elements
        val centreStep = (midOf(groups[i1].second) - midOf(groups[i0].second)) / (groups.size - 1)
        var pos = midOf(groups[i0].second)
        groups.forEachIndexed { index, (group, gb) ->
            // Los dos extremos se quedan donde están: son los que definen la caja.
            val delta = if (index == i0 || index == i1) 0.0 else {
                pos += centreStep
                pos - midOf(gb)
            }
            shiftGroupBy(group, axis, delta, moved)
        }
    } else {
        var pos = startOf(bounds)
        for ((group, gb) in groups) {
            val delta = pos - startOf(gb)
            shiftGroupBy(group, axis, delta, moved)
            pos += extentOf(gb) + step
        }
    }
    return elements.map { moved[it.id] ?: it }
}

private fun shiftGroupBy(
    group: List<Element>, axis: AlignAxis, delta: Double, out: MutableMap<String, Element>
) {
    for (e in group) {
        out[e.id] = when (axis) {
            AlignAxis.X -> e.copy(x = e.x + delta)
            AlignAxis.Y -> e.copy(y = e.y + delta)
        }.touched()
    }
}
