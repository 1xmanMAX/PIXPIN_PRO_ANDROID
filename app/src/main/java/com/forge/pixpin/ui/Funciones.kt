package com.forge.pixpin.ui

/**
 * **Funciones**: la segunda parte del interruptor **Chat · Funciones** de arriba de la lista de
 * chats (25-oct-2026, porte de `crates/pixpin-ui/src/funciones.rs` del PC, `2e0da60a`), las cuentas
 * sin pintar nada. Lo pinta [PestanaDeFunciones].
 *
 * Elegida, la lista de chats se cambia por una **rejilla de mosaicos grandes**, uno por función
 * fuerte de la aplicación (grabar una reunión, un lienzo nuevo, las tareas…), con su icono, su
 * título y una línea que dice qué hace. El usuario en el PC: «que solo aparezcan unas pocas
 * funciones fuertes … y quitarlas de los chats». Por eso los botones de Tareas, Lecciones y Galería
 * que había arriba de Proyectos se quitaron: ahora viven aquí. El orden, los nombres y los textos
 * son los de `docs/ui-funciones-y-chats.md`, los mismos en todos los aparatos.
 *
 * Todo va en dp con escala 100 (Compose ya escala los dp); la escala se queda para que las pruebas
 * sean las del PC.
 */
object Funciones {

    /**
     * Cada función de la rejilla, **en el orden en que sale**: de la que más se usa a la que menos.
     * [nombre] es el estable (atajos, pruebas, analítica), el mismo en todos los aparatos; [color],
     * el del círculo del icono, igual en claro y en oscuro.
     */
    enum class Funcion(val nombre: String, val color: Long) {
        /** Grabar una clase o una reunión, y luego su transcripción. */
        GRABAR("grabar", 0xFFE5484D),
        /** Un lienzo en blanco; desde la lista no hay chat abierto, así que va a «Guardados». */
        LIENZO("lienzo", 0xFF8E4EC6),
        /** Las listas de tareas de todos los chats juntas. */
        TAREAS("tareas", 0xFF1F7A3A),
        /** El día minuto a minuto con las lecciones dentro (en el teléfono, las lecciones). */
        TIMELINE("timeline", 0xFF0090FF),
        /** El horario de clases: abre Tareas en su vista Horario (desde el 25-oct-2026). */
        HORARIO("horario", 0xFFF76B15),
        /** Todas las capturas guardadas. */
        GALERIA("galeria", 0xFF12A594),
        /** La biblioteca de figuras de los lienzos. */
        FIGURAS("figuras", 0xFF3E63DD);

        /** La clave de su título en el catálogo del PC; aquí su recurso es la misma con `_`. */
        val claveTitulo: String get() = "funciones-$nombre"

        /** La clave de su línea de descripción. */
        val claveDetalle: String get() = "$claveTitulo-detalle"
    }

    /** El aire alrededor de la rejilla. */
    const val MARGEN = 14
    /** El hueco entre dos mosaicos. */
    const val HUECO = 10
    /** Lo alto del título «Funciones», encima de la rejilla. */
    const val TITULO_ALTO = 44
    /** Lo más estrecho que puede ser un mosaico con su texto; por debajo, solo iconos. */
    const val MOSAICO_MIN = 140
    /** Nunca más columnas que estas: un mosaico grande se pulsa sin apuntar. */
    const val COLUMNAS_MAX = 3
    /** Lo alto de un mosaico con su descripción. */
    const val MOSAICO_ALTO = 104
    /** Lo menos que se encoge en una pantalla baja; por debajo de [ALTO_CON_DETALLE], sin descripción. */
    const val MOSAICO_ALTO_MIN = 60
    const val ALTO_CON_DETALLE = 88
    /** El radio de las esquinas, el círculo del icono y el relleno de dentro. */
    const val RADIO = 12
    const val ICONO_LADO = 34
    const val RELLENO = 12

    /** Un rectángulo (el `Rect` de `pixpin-geom`). */
    data class Caja(val x: Int, val y: Int, val ancho: Int, val alto: Int) {
        val derecha: Int get() = x + ancho
        val abajo: Int get() = y + alto
        fun contiene(px: Int, py: Int) = px >= x && py >= y && px < derecha && py < abajo
        fun pisa(o: Caja) = x < o.derecha && o.x < derecha && y < o.abajo && o.y < abajo
    }

    /** Dónde va cada cosa de la pestaña de Funciones. */
    data class Rejilla(
        /** La franja del título (vacía si solo caben iconos). */
        val titulo: Caja,
        /** Un mosaico por función, en el orden de [Funcion.entries]. */
        val mosaicos: List<Caja>,
        val columnas: Int,
        /** Tan estrecha que solo caben los iconos. */
        val soloIconos: Boolean,
        /** Los mosaicos llevan su línea de descripción. */
        val conDetalle: Boolean
    ) {
        /** El mosaico bajo el punto. */
        fun en(px: Int, py: Int): Int? = mosaicos.indexOfFirst { it.contiene(px, py) }.takeIf { it >= 0 }
    }

    private fun e(v: Int, escala: Int) = v * escala / 100

    /**
     * **Reparte [cuantos] mosaicos en [zona]**: las columnas salen del ancho (de una a
     * [COLUMNAS_MAX]) y el alto de cada mosaico se encoge en una pantalla baja hasta
     * [MOSAICO_ALTO_MIN]; más baja aún, la rejilla se sale por abajo y se desplaza.
     */
    fun rejilla(zona: Caja, cuantos: Int, escala: Int = 100): Rejilla {
        val margen = e(MARGEN, escala)
        val hueco = e(HUECO, escala)
        val util = maxOf(zona.ancho - 2 * margen, 0)
        val minimo = maxOf(e(MOSAICO_MIN, escala), 1)
        if (util < minimo) {
            // Solo iconos: una columna de cuadrados del ancho que haya, sin título.
            val aire = e(6, escala)
            val lado = maxOf(zona.ancho - 2 * aire, 1)
            val x = zona.x + (zona.ancho - lado) / 2
            val mosaicos = (0 until cuantos).map { i -> Caja(x, zona.y + aire + i * (lado + aire), lado, lado) }
            return Rejilla(zona.copy(ancho = 0, alto = 0), mosaicos, 1, soloIconos = true, conDetalle = false)
        }
        val columnas = ((util + hueco) / (minimo + hueco)).coerceIn(1, COLUMNAS_MAX)
        val filas = maxOf((cuantos + columnas - 1) / columnas, 1)
        val ancho = (util - hueco * (columnas - 1)) / columnas
        val titulo = Caja(zona.x + margen, zona.y, util, minOf(e(TITULO_ALTO, escala), zona.alto))
        val arriba = titulo.abajo
        val sitio = maxOf(maxOf(zona.abajo - arriba, 0) - margen, 0)
        val porFila = maxOf(sitio - hueco * (filas - 1), 0) / filas
        val alto = porFila.coerceIn(e(MOSAICO_ALTO_MIN, escala), e(MOSAICO_ALTO, escala))
        val mosaicos = (0 until cuantos).map { i ->
            val f = i / columnas
            val c = i % columnas
            Caja(titulo.x + c * (ancho + hueco), arriba + f * (alto + hueco), ancho, alto)
        }
        return Rejilla(titulo, mosaicos, columnas, soloIconos = false, conDetalle = alto >= e(ALTO_CON_DETALLE, escala))
    }

    /** Una tecla de moverse por la rejilla (con un teclado o el mando de una tele). */
    enum class Paso { IZQUIERDA, DERECHA, ARRIBA, ABAJO, INICIO, FIN }

    /**
     * **A qué mosaico lleva [paso] desde [desde]** (sin ninguno elegido, al primero). Las flechas
     * no dan la vuelta: en el borde se queda donde está; a los lados sí pasa a la fila de al lado,
     * que es lo que se espera de una rejilla que se lee como un texto.
     */
    fun mover(desde: Int?, paso: Paso, columnas: Int, cuantos: Int): Int? {
        if (cuantos == 0) return null
        val i = desde?.takeIf { it in 0 until cuantos } ?: return if (paso == Paso.FIN) cuantos - 1 else 0
        val cols = maxOf(columnas, 1)
        return when (paso) {
            Paso.IZQUIERDA -> maxOf(i - 1, 0)
            Paso.DERECHA -> minOf(i + 1, cuantos - 1)
            Paso.ARRIBA -> if (i >= cols) i - cols else i
            Paso.ABAJO -> if (i + cols < cuantos) i + cols else i
            Paso.INICIO -> 0
            Paso.FIN -> cuantos - 1
        }
    }
}
