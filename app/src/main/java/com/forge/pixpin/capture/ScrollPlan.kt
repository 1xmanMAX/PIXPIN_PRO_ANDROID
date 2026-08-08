package com.forge.pixpin.capture

/**
 * Qué hacer con cada fotograma de una captura con scroll, **sin tocar un solo
 * píxel**.
 *
 * Aquí está toda la decisión: si el fotograma encaja, cuánto se ha desplazado la
 * pantalla, qué franja de él es contenido nuevo y cuándo hay que parar. Copiar
 * las filas y pintarlas seguidas es lo que queda después, y eso lo hace
 * [ScrollStitcher] con bitmaps.
 *
 * Se separó por un motivo muy concreto: comprobar el cosido obligaba a crear
 * bitmaps de verdad, y eso solo funciona con el motor gráfico nativo de
 * Robolectric, que **no existe para Linux sobre ARM**. Las siete pruebas del
 * cosido no fallaban por un error del cosido: no llegaban a ejecutarse. Con la
 * decisión aquí, se comprueban en cualquier máquina, que es justo lo que
 * necesita la parte que decide si una captura larga sale bien o sale rota.
 *
 * El fotograma llega ya reducido a **firmas de fila** —un número por fila, ver
 * [ScrollMatcher.signatures]—, que es lo único que el algoritmo mira.
 */
class ScrollPlan(private val maxHeight: Int) {

    enum class Result {
        /** Primer fotograma: es la base. */
        FIRST,
        /** Se ha añadido contenido nuevo. */
        APPENDED,
        /** La pantalla no se ha movido desde el fotograma anterior. */
        NO_MOVEMENT,
        /** No se puede encajar con confianza: se descarta y se espera al siguiente. */
        UNCERTAIN,
        /** Se ha alcanzado el alto máximo. */
        FULL
    }

    /**
     * La orden: qué franja del fotograma hay que quedarse.
     *
     * [filas] a cero significa que no hay nada que copiar, y entonces [desde] no
     * significa nada.
     */
    data class Orden(val result: Result, val desde: Int = 0, val filas: Int = 0)

    /** Lo que llevamos cosido, en filas. */
    var height: Int = 0
        private set

    val isEmpty: Boolean get() = height == 0

    /** La banda de referencia que se busca en el fotograma siguiente. */
    private var tail = IntArray(0)

    /**
     * Decide qué hacer con un fotograma.
     *
     * **Ante la duda, no cose.** Un fotograma mal encajado estropea la imagen
     * entera y el usuario no lo ve hasta el final, mientras que descartarlo solo
     * cuesta unos milisegundos porque el siguiente ya viene de camino.
     *
     * Actualiza el estado solo cuando la respuesta es [Result.FIRST] o
     * [Result.APPENDED]: quien llama tiene que poder descartar el fotograma sin
     * haber ensuciado nada.
     */
    fun plan(signatures: IntArray, frameRows: Int): Orden {
        // Un fotograma más corto que la propia referencia no puede contenerla.
        if (frameRows <= TAIL_ROWS || signatures.size < frameRows) {
            return Orden(Result.UNCERTAIN)
        }

        if (isEmpty) {
            if (frameRows > maxHeight) return Orden(Result.FULL)
            height = frameRows
            tail = chooseTail(signatures)
            return Orden(Result.FIRST, desde = 0, filas = frameRows)
        }

        val offset = ScrollMatcher.findOffset(tail, signatures, TOLERANCE)
        if (offset == ScrollMatcher.NO_MATCH) return Orden(Result.UNCERTAIN)

        val nuevoDesde = offset + tail.size
        val filasNuevas = frameRows - nuevoDesde
        if (filasNuevas <= 0) return Orden(Result.NO_MOVEMENT)
        if (height + filasNuevas > maxHeight) return Orden(Result.FULL)

        height += filasNuevas
        tail = chooseTail(signatures)
        return Orden(Result.APPENDED, desde = nuevoDesde, filas = filasNuevas)
    }

    fun reset() {
        height = 0
        tail = IntArray(0)
    }

    /**
     * Banda de referencia del final de lo acumulado. Si no tiene textura (un
     * fondo liso, el final de una página en blanco) se agranda hasta encontrar
     * algo distinguible: si no, encajaría en cualquier sitio.
     */
    private fun chooseTail(signatures: IntArray): IntArray {
        var rows = TAIL_ROWS
        while (rows < MAX_TAIL_ROWS && rows < signatures.size) {
            val candidate = signatures.copyOfRange(signatures.size - rows, signatures.size)
            if (!ScrollMatcher.isFlat(candidate, MIN_VARIATION)) return candidate
            rows *= 2
        }
        val take = minOf(rows, signatures.size)
        return signatures.copyOfRange(signatures.size - take, signatures.size)
    }

    companion object {
        /** Filas de referencia que se buscan en el fotograma siguiente. */
        const val TAIL_ROWS = 48

        /** Hasta dónde se agranda la referencia si la banda no tiene textura. */
        const val MAX_TAIL_ROWS = 384

        /** Se muestrea uno de cada N píxeles por fila: sobra para distinguirlas. */
        const val SAMPLE_STEP = 5

        const val TOLERANCE = 40
        const val MIN_VARIATION = 300
    }
}
