package com.forge.pixpin.mini

import com.forge.pixpin.mini.TodasLasTareas.Grupo

/**
 * **La vista Kanban de Tareas**: la lógica pura (PC `18aaf99`, `apps/pixpin/src/tareas/kanban.rs`,
 * con sus pruebas en `KanbanTest`). Sin Android.
 *
 * Cada lista es una **columna** (como en Trello): su encabezado arriba, sus tarjetas pendientes
 * debajo y al pie su pliegue de hechas. Arrastrar una tarjeta a otra columna la **pasa a esa lista**
 * ([TodasLasTareas.mover], el mismo «Mover a…»).
 *
 * Columnas por lista y no por estado: una tarea solo tiene dos estados (`- [ ]` y `- [x]`, el formato
 * que se comparte con el PC) y un tercero («en curso») rompería el documento. Lo que no cabe a lo ancho
 * baja a una segunda banda de columnas, así nunca hace falta desplazarse de lado.
 */
object Kanban {

    /** Las medidas del tablero (en dp en el teléfono). */
    data class Medidas(
        val ancho: Float,
        /** Lo menos que mide una columna: con menos sitio, la siguiente baja a otra banda. */
        val columnaMin: Float,
        /** Lo más que mide: con pocas listas y mucho sitio no se estiran hasta ser renglones. */
        val columnaMax: Float,
        /** Entre columna y columna, y entre tarjeta y tarjeta. */
        val hueco: Float,
        /** El relleno de dentro de cada columna. */
        val relleno: Float,
        val encabezado: Float,
        val pliegue: Float,
        /** Entre una banda de columnas y la siguiente. */
        val entreBandas: Float,
        /** Lo menos que mide una hecha. */
        val filaHecha: Float
    )

    data class Caja(val x: Float, val y: Float, val ancho: Float, val alto: Float) {
        fun contiene(px: Float, py: Float) = px >= x && px <= x + ancho && py >= y && py <= y + alto
    }

    /** Qué hay en cada sitio del tablero. */
    sealed class Pieza {
        data class Encabezado(val lista: Int) : Pieza()
        data class Tarjeta(val lista: Int, val fila: Int) : Pieza()
        data class Pliegue(val lista: Int) : Pieza()
    }

    data class Celda(val pieza: Pieza, val caja: Caja)

    /** Una columna: de qué lista es y su caja entera, que es donde se suelta una tarjeta. */
    data class Columna(val lista: Int, val caja: Caja)

    /** Cuántas columnas van en cada banda y el ancho de cada una. */
    fun reparto(m: Medidas, n: Int): Pair<Int, Float> {
        val caben = kotlin.math.floor((m.ancho + m.hueco) / (m.columnaMin + m.hueco)).toInt().coerceAtLeast(1)
        val k = minOf(caben, n.coerceAtLeast(1))
        val ancho = ((m.ancho - (k - 1) * m.hueco) / k).coerceAtMost(m.columnaMax).coerceAtLeast(1f)
        return k to ancho
    }

    /**
     * Coloca los grupos como columnas. [altoDe] (lista, fila, ancho) mide una tarjeta al ancho que le
     * toca. Devuelve las piezas, las columnas y el alto total.
     */
    fun disponer(
        grupos: List<Grupo>,
        abierta: (Int) -> Boolean,
        altoDe: (Int, Int, Float) -> Float,
        m: Medidas
    ): Triple<List<Celda>, List<Columna>, Float> {
        val (k, anchoCol) = reparto(m, grupos.size)
        val dentro = (anchoCol - 2 * m.relleno).coerceAtLeast(1f)
        val celdas = ArrayList<Celda>()
        val columnas = ArrayList<Columna>()
        var yBanda = 0f
        grupos.chunked(k).forEachIndexed { b, banda ->
            if (b > 0) yBanda += m.entreBandas
            val primera = columnas.size
            var altoBanda = 0f
            banda.forEachIndexed { c, g ->
                val x0 = c * (anchoCol + m.hueco)
                val x = x0 + m.relleno
                var y = yBanda
                celdas += Celda(Pieza.Encabezado(g.lista), Caja(x, y, dentro, m.encabezado))
                y += m.encabezado
                for (fi in g.arriba) {
                    val alto = altoDe(g.lista, fi, dentro).coerceAtLeast(dentro * 0.5f)
                    celdas += Celda(Pieza.Tarjeta(g.lista, fi), Caja(x, y, dentro, alto))
                    y += alto + m.hueco
                }
                if (g.hechas.isNotEmpty()) {
                    celdas += Celda(Pieza.Pliegue(g.lista), Caja(x, y, dentro, m.pliegue))
                    y += m.pliegue
                    if (abierta(g.lista)) for (fi in g.hechas) {
                        celdas += Celda(Pieza.Tarjeta(g.lista, fi), Caja(x, y, dentro, m.filaHecha))
                        y += m.filaHecha + m.hueco * 0.5f
                    }
                }
                altoBanda = maxOf(altoBanda, y - yBanda + m.relleno)
                columnas += Columna(g.lista, Caja(x0, yBanda, anchoCol, 0f))
            }
            // Todas las columnas de una banda, del alto de la más larga: se suelta en cualquier punto.
            for (i in primera until columnas.size) columnas[i] = columnas[i].copy(caja = columnas[i].caja.copy(alto = altoBanda))
            yBanda += altoBanda
        }
        return Triple(celdas, columnas, yBanda)
    }

    /** La columna bajo el punto, si hay alguna. */
    fun columnaEn(columnas: List<Columna>, x: Float, y: Float): Int? = columnas.firstOrNull { it.caja.contiene(x, y) }?.lista

    /**
     * Adónde va una tarjeta arrastrada y soltada en (x, y): la lista de la columna, si es otra que la
     * suya. Soltada en su misma columna o fuera de todas, no se mueve (null).
     */
    fun destinoAlSoltar(columnas: List<Columna>, desde: Int, x: Float, y: Float): Int? =
        columnaEn(columnas, x, y)?.takeIf { it != desde }

    /** Lo que se mueve el dedo antes de que sea un arrastre (a escala 1): menos es un toque que tiembla. */
    const val UMBRAL_ARRASTRE = 6f

    fun esArrastre(desde: Pair<Float, Float>, ahora: Pair<Float, Float>, escala: Float): Boolean {
        val dx = ahora.first - desde.first
        val dy = ahora.second - desde.second
        val u = UMBRAL_ARRASTRE * escala
        return dx * dx + dy * dy >= u * u
    }

    /** Las vistas de la pantalla de Tareas; se recuerda la elegida (`tareas-vista.txt` del PC). */
    enum class Vista(val clave: String) {
        LISTA("lista"), KANBAN("kanban");

        companion object {
            fun deTexto(t: String?): Vista = entries.firstOrNull { it.clave == t?.trim() } ?: LISTA
        }
    }
}
