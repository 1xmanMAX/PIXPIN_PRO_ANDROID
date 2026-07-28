package com.forge.pixpin.pin

/**
 * Grupos de pines: matemática y reglas puras, sin ventanas de por medio.
 *
 * Agrupar sirve para **colocar**, no para uniformar: los miembros se mueven,
 * se ocultan y se cierran juntos, pero cada uno conserva su escala y su
 * opacidad. Por eso aquí solo hay pertenencia y posiciones.
 */
object PinGroups {

    /** Colores del borde indicador, para distinguir grupos de un vistazo. */
    private val COLORS = intArrayOf(
        0xFF29B8DB.toInt(), 0xFFFFB300.toInt(), 0xFF7CB342.toInt(),
        0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF00BFA5.toInt()
    )

    fun colorFor(groupId: String): Int {
        val h = groupId.hashCode()
        // El resto de un negativo es negativo en Kotlin: hay que normalizarlo.
        val index = ((h % COLORS.size) + COLORS.size) % COLORS.size
        return COLORS[index]
    }

    fun membersOf(pins: List<PinState>, groupId: String?): List<PinState> =
        if (groupId == null) emptyList() else pins.filter { it.groupId == groupId }

    /**
     * Posiciones a las que hay que llevar a los compañeros de grupo cuando uno
     * de ellos se arrastra [dx],[dy] desde donde empezó el gesto.
     *
     * Se trabaja con las posiciones del ARRANQUE del gesto y un desplazamiento
     * absoluto, no con incrementos: si se acumulasen incrementos, cualquier
     * evento perdido desalinearía el grupo para siempre.
     */
    fun followPositions(
        anchors: Map<String, Pair<Int, Int>>,
        dx: Int,
        dy: Int
    ): Map<String, Pair<Int, Int>> =
        anchors.mapValues { (_, start) -> (start.first + dx) to (start.second + dy) }

    /** Marca (o desmarca, con [groupId] nulo) la pertenencia de varios pines. */
    fun assign(pins: List<PinState>, ids: Set<String>, groupId: String?): List<PinState> =
        pins.map { if (it.id in ids) it.copy(groupId = groupId) else it }

    /**
     * Qué ofrecer para una selección: agrupar hace falta desde dos pines;
     * desagrupar, en cuanto uno de los elegidos ya pertenece a un grupo.
     */
    fun canGroup(selection: List<PinState>): Boolean = selection.size >= 2

    fun canUngroup(selection: List<PinState>): Boolean = selection.any { it.groupId != null }
}
