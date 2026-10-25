package com.forge.pixpin.mini

import java.time.LocalDate

/**
 * **La vista Kanban de Tareas**, sin Android (25-oct-2026, porte de `tareas/kanban.rs` del PC,
 * 17-oct-2026). Lo pinta [TodasLasTareasActivity]; aquí está lo que se prueba sin pantalla.
 *
 * El usuario en el PC: «añadir la vista estilo Kanban». Cada lista es una **columna** (como en
 * Trello): su encabezado arriba, lo pendiente debajo y, al pie, lo hecho plegado. Arrastrar una
 * tarjeta a otra columna la **pasa a esa lista**: es el mismo «Mover a…» de siempre
 * ([TodasLasTareas.mover]), así que se guarda y viaja igual.
 *
 * **Columnas por lista y no por estado**, como el PC: una tarea solo tiene dos estados (`- [ ]` y
 * `- [x]`, el formato que comparten los dos) y un tercero («en curso») rompería el documento en el
 * otro aparato.
 *
 * Lo que cambia respecto al PC, y por qué: allí lo que no cabe a lo ancho baja a otra banda; en un
 * teléfono, con sitio para una columna y poco más, las columnas van **una al lado de otra y se
 * desplazan de lado**, y al arrastrar una tarjeta cerca del borde el tablero se desplaza solo
 * ([desplazamientoAlBorde]). Y sin buscar salen también **las listas vacías**: son donde se suelta
 * una tarjeta para empezar a llenarlas.
 */
object Kanban {

    /** **Cómo se ven las tareas**, y se recuerda al volver (`rejilla::Vista` del PC). */
    enum class Vista(val texto: String) {
        LISTA("lista"),
        KANBAN("kanban");

        companion object {
            /** Lo guardado; lo que no se reconozca es la lista de siempre. */
            fun deTexto(t: String?): Vista = entries.firstOrNull { it.texto == t?.trim() } ?: LISTA
        }
    }

    /**
     * **Las columnas del tablero**, en el orden de las listas ([TodasLasTareas.ORDEN_DE_LISTAS]),
     * con las mismas tarjetas que la vista de lista ([TodasLasTareas.agrupar]): arriba lo pendiente
     * (y lo recién tachado), al pie lo hecho. Sin buscar, una columna por lista, también vacía;
     * buscando, solo las listas con algo que coincida.
     */
    fun columnas(
        listas: List<TodasLasTareas.Lista>,
        palabras: List<String>,
        hoy: LocalDate,
        sigueArriba: (TodasLasTareas.Lista, TodasLasTareas.Fila) -> Boolean = { _, _ -> false }
    ): List<TodasLasTareas.Grupo> {
        val grupos = TodasLasTareas.agrupar(listas, palabras, hoy, sigueArriba)
        if (palabras.isNotEmpty()) return grupos
        val porLista = grupos.associateBy { it.lista }
        return listas.indices.map { porLista[it] ?: TodasLasTareas.Grupo(it, emptyList(), emptyList()) }
    }

    /** La caja de una columna en la pantalla (en píxeles, todas en las mismas coordenadas). */
    data class Caja(val lista: Int, val izquierda: Float, val arriba: Float, val derecha: Float, val abajo: Float) {
        fun contiene(x: Float, y: Float) = x in izquierda..derecha && y in arriba..abajo
    }

    /** La lista de la columna bajo el punto, si hay alguna (`columna_en` del PC). */
    fun columnaEn(cajas: List<Caja>, x: Float, y: Float): Int? = cajas.firstOrNull { it.contiene(x, y) }?.lista

    /**
     * **Adónde va una tarjeta arrastrada y soltada en (x, y)**: la lista de la columna, si es otra
     * que la suya. Soltada en su misma columna o fuera de todas, no se mueve (`null`).
     */
    fun destinoAlSoltar(cajas: List<Caja>, desde: Int, x: Float, y: Float): Int? = columnaEn(cajas, x, y)?.takeIf { it != desde }

    /**
     * **Cuánto desplazar el tablero de lado** mientras se arrastra una tarjeta a [x] (desde el
     * borde izquierdo del tablero, que mide [ancho]): hacia atrás cerca del borde izquierdo, hacia
     * delante cerca del derecho, más rápido cuanto más cerca, y nada en medio. [borde] es la franja
     * que lo dispara y [paso] lo más que se mueve cada vez.
     */
    fun desplazamientoAlBorde(x: Float, ancho: Float, borde: Float, paso: Float): Float {
        if (ancho <= 0f || borde <= 0f) return 0f
        return when {
            x < borde -> -paso * ((borde - x) / borde).coerceIn(0f, 1f)
            x > ancho - borde -> paso * ((x - (ancho - borde)) / borde).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
