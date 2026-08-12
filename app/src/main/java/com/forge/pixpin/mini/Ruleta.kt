package com.forge.pixpin.mini

/**
 * La ruleta: una lista de nombres y un sorteo.
 *
 * Todo lo que decide algo vive aquí, sin Compose ni Android, para que se pueda
 * comprobar en la JVM: quién entra en el sorteo, a quién le toca, dónde para la
 * rueda y qué queda cuando se saca a uno.
 *
 * El azar entra **por fuera**, como una función. Así una prueba puede decir
 * «devuelve siempre el tercero» y comprobar de verdad lo que pasa después, en
 * vez de girar mil veces y confiar.
 */
object Ruleta {

    /** Cuántos nombres caben. Más allá, ni se leen ni se distinguen al girar. */
    const val MAXIMO = 40

    /**
     * Los nombres de la lista, uno por línea.
     *
     * Se quitan las líneas en blanco y los espacios de los lados, que es lo que
     * sobra al pegar una lista de cualquier sitio. Los repetidos **se quedan**:
     * si alguien pone dos veces a la misma persona es porque quiere que tenga el
     * doble de posibilidades.
     */
    fun nombres(texto: String?): List<String> =
        texto.orEmpty()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAXIMO)

    /** ¿Hay con qué sortear? Con uno solo no hay sorteo que valga. */
    fun sePuedeGirar(texto: String?): Boolean = nombres(texto).size >= 2

    /**
     * A quién le toca. [azar] devuelve un número de 0 (incluido) a 1 (excluido).
     *
     * Devuelve el índice dentro de [nombres], o -1 si no hay a quién elegir.
     */
    fun elegir(nombres: List<String>, azar: () -> Double): Int {
        if (nombres.isEmpty()) return -1
        val n = (azar() * nombres.size).toInt()
        return n.coerceIn(0, nombres.size - 1)
    }

    /** Cuánto ocupa cada porción, en grados. */
    fun anguloDePorcion(cuantos: Int): Float =
        if (cuantos <= 0) 360f else 360f / cuantos

    /**
     * Dónde tiene que quedarse la rueda para que la aguja señale a [elegido].
     *
     * La aguja está arriba y quieta; lo que gira es la rueda. Así que el ángulo
     * es el que lleva el **centro de esa porción** hasta arriba: el negativo de
     * donde está, más las vueltas enteras que se dan para que se vea girar.
     *
     * [vueltas] son de adorno y no cambian a quién le toca —ya está decidido—,
     * pero sin ellas la rueda se limitaría a saltar a la respuesta.
     */
    fun anguloFinal(elegido: Int, cuantos: Int, vueltas: Int = 4): Float {
        if (cuantos <= 0) return 0f
        val porcion = anguloDePorcion(cuantos)
        val centro = porcion * elegido + porcion / 2f
        return vueltas * 360f - centro
    }

    /**
     * El texto sin el nombre [indice], para seguir sorteando entre los que
     * quedan.
     *
     * Se rehace desde los nombres limpios en vez de tocar el texto original:
     * quitar una línea a mano dejaría los espacios y los renglones vacíos que
     * hubiera alrededor, y a la vuelta la lista ya no cuadraría con lo que se ve.
     */
    fun sinElNombre(texto: String?, indice: Int): String {
        val quedan = nombres(texto).toMutableList()
        if (indice !in quedan.indices) return texto.orEmpty()
        quedan.removeAt(indice)
        return quedan.joinToString("\n")
    }
}
