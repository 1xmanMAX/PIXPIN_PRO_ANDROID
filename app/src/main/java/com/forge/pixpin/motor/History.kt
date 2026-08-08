package com.forge.pixpin.motor

/**
 * Deshacer y rehacer. Port del modelo de `store.ts` / `delta.ts`.
 *
 * **Guarda diferencias, no copias de la escena.** Es la decisión de fondo del
 * original y aquí importa más todavía: un lienzo con mil elementos ocuparía
 * megas por cada paso del historial, y en un móvil eso acaba en un
 * `OutOfMemory`. Un delta solo guarda lo que cambió, que casi siempre es un
 * elemento.
 *
 * Cada entrada guarda el antes y el después de los elementos tocados, más el
 * orden de la lista si cambió. Deshacer aplica el «antes», rehacer el
 * «después»; la misma estructura vale para las dos direcciones.
 */

/**
 * Lo que cambió entre dos estados.
 *
 * Un id con `before` nulo es un elemento **creado**; con `after` nulo, uno
 * **borrado**. Con los dos, uno modificado.
 */
data class ElementsDelta(
    val changes: Map<String, Pair<Element?, Element?>>,
    /** Orden de ids antes y después. Null si el orden no cambió. */
    val orderBefore: List<String>? = null,
    val orderAfter: List<String>? = null
) {
    val isEmpty: Boolean get() = changes.isEmpty() && orderBefore == null

    /** El delta al revés: lo que convierte deshacer en rehacer. */
    fun inverted(): ElementsDelta = ElementsDelta(
        changes = changes.mapValues { (_, v) -> v.second to v.first },
        orderBefore = orderAfter,
        orderAfter = orderBefore
    )
}

/** Calcula qué cambió entre dos versiones de la escena (`ElementsDelta.calculate`). */
fun calculateDelta(before: List<Element>, after: List<Element>): ElementsDelta {
    val beforeById = before.associateBy { it.id }
    val afterById = after.associateBy { it.id }
    val changes = mutableMapOf<String, Pair<Element?, Element?>>()

    for ((id, b) in beforeById) {
        val a = afterById[id]
        // Se comparan las instancias completas: `version` no basta porque el
        // mismo elemento puede volver a un estado anterior con otra versión.
        if (a == null || a != b) changes[id] = b to a
    }
    for ((id, a) in afterById) {
        if (id !in beforeById) changes[id] = null to a
    }

    val orderBefore = before.map { it.id }
    val orderAfter = after.map { it.id }
    val orderChanged = orderBefore != orderAfter

    return ElementsDelta(
        changes = changes,
        orderBefore = if (orderChanged) orderBefore else null,
        orderAfter = if (orderChanged) orderAfter else null
    )
}

/** Aplica un delta en la dirección «hacia el después». */
fun applyDelta(elements: List<Element>, delta: ElementsDelta): List<Element> {
    if (delta.isEmpty) return elements

    val byId = elements.associateByTo(LinkedHashMap()) { it.id }
    for ((id, change) in delta.changes) {
        val target = change.second
        if (target == null) byId.remove(id) else byId[id] = target
    }

    val order = delta.orderAfter
    if (order == null) return byId.values.toList()

    // Se respeta el orden guardado y se añade al final lo que no estuviera en
    // él: un elemento creado después del delta no debe desaparecer al deshacer
    // otra cosa.
    val ordered = order.mapNotNull { byId.remove(it) }
    return ordered + byId.values
}

/**
 * La pila de deshacer/rehacer (`History`).
 *
 * [limit] acota la memoria. Excalidraw no limita porque en escritorio da igual;
 * aquí sí, por la misma razón que se guardan deltas.
 */
class History(private val limit: Int = 100) {

    private val undoStack = ArrayDeque<ElementsDelta>()
    private val redoStack = ArrayDeque<ElementsDelta>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Registra un cambio ya hecho.
     *
     * Anotar algo nuevo **vacía la pila de rehacer**: a partir de aquí la
     * historia se bifurca y el futuro que había guardado ya no lleva a ningún
     * sitio alcanzable.
     */
    fun record(before: List<Element>, after: List<Element>) {
        val delta = calculateDelta(before, after)
        if (delta.isEmpty) return
        undoStack.addLast(delta)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Deshace un paso y devuelve la escena resultante, o null si no hay nada. */
    fun undo(current: List<Element>): List<Element>? {
        val delta = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(delta)
        return applyDelta(current, delta.inverted())
    }

    /** Rehace un paso. */
    fun redo(current: List<Element>): List<Element>? {
        val delta = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(delta)
        return applyDelta(current, delta)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
