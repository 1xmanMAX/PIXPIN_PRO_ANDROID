package com.forge.pixpin.markdown

/**
 * Editar una tabla sin escribir barras, como su `TableModel`.
 *
 * Lo que hace avanzada a la suya no es cómo se ve: es que se **edita con
 * botones**. Añadir una fila, quitar una columna, alinear, poner o quitar la
 * cabecera. Escribiendo a mano hay que contar barras y guiones y cuadrar el
 * número de celdas de cada fila, que es exactamente el trabajo que un editor
 * tiene que quitar de en medio.
 *
 * Todo esto opera sobre **el texto de la tabla**, no sobre el modelo ya
 * interpretado: es lo que se guarda y lo que exporta a PDF y a SVG. Un modelo
 * aparte habría que traducirlo de vuelta en cada toque, y ahí es donde se pierde
 * lo que había escrito.
 *
 * De su `TableModel` quedan fuera fusionar y partir celdas (`colspan`,
 * `rowspan`): la sintaxis de tabla de Markdown no sabe decir eso, y para poder
 * decirlo habría que inventarse una marca que ningún otro sitio entiende.
 */
object Tablas {

    /** Las filas de la tabla, ya partidas en celdas. La de guiones va aparte. */
    private data class Rejilla(
        val filas: MutableList<MutableList<String>>,
        val alineaciones: MutableList<Alineacion>
    )

    private fun leer(texto: String): Rejilla? {
        val lineas = texto.trim().split('\n').map { it.trim() }.filter { it.startsWith("|") }
        if (lineas.size < 2) return null

        val separadora = celdas(lineas[1])
        if (separadora.isEmpty() || !separadora.all { esGuiones(it) }) return null

        val alineaciones = separadora.map { alineacionDe(it) }.toMutableList()
        val filas = lineas
            .filterIndexed { i, _ -> i != 1 }
            .map { celdas(it).toMutableList() }
            .toMutableList()

        return Rejilla(filas, alineaciones)
    }

    /**
     * Vuelve a escribir la tabla, **cuadrando todas las filas** al ancho mayor.
     *
     * Una fila corta y otra larga es lo más fácil que se estropea escribiendo a
     * mano, y el resultado es una tabla que se ve torcida sin que se sepa por
     * qué. Al pasar por aquí eso se arregla solo.
     */
    private fun escribir(r: Rejilla): String {
        val ancho = maxOf(
            r.filas.maxOfOrNull { it.size } ?: 0,
            r.alineaciones.size
        ).coerceAtLeast(1)

        while (r.alineaciones.size < ancho) r.alineaciones += Alineacion.IZQUIERDA
        while (r.alineaciones.size > ancho) r.alineaciones.removeAt(r.alineaciones.size - 1)

        val salida = StringBuilder()
        r.filas.forEachIndexed { i, fila ->
            val completa = (0 until ancho).map { fila.getOrElse(it) { "" } }
            salida.append("| ").append(completa.joinToString(" | ")).append(" |\n")
            if (i == 0) {
                salida.append("|")
                r.alineaciones.forEach { salida.append(guionesDe(it)).append("|") }
                salida.append('\n')
            }
        }
        return salida.toString().trimEnd('\n')
    }

    fun añadirFila(texto: String, despuesDe: Int = -1): String {
        val r = leer(texto) ?: return texto
        val ancho = r.filas.maxOfOrNull { it.size } ?: 1
        val donde = if (despuesDe < 0 || despuesDe >= r.filas.size) r.filas.size else despuesDe + 1
        r.filas.add(donde, MutableList(ancho) { "" })
        return escribir(r)
    }

    fun quitarFila(texto: String, cual: Int): String {
        val r = leer(texto) ?: return texto
        // Una tabla sin filas no es una tabla; la última no se quita.
        if (r.filas.size <= 1 || cual !in r.filas.indices) return texto
        r.filas.removeAt(cual)
        return escribir(r)
    }

    fun añadirColumna(texto: String, despuesDe: Int = -1): String {
        val r = leer(texto) ?: return texto
        val ancho = r.filas.maxOfOrNull { it.size } ?: 0
        val donde = if (despuesDe < 0 || despuesDe >= ancho) ancho else despuesDe + 1
        r.filas.forEach { fila ->
            while (fila.size < ancho) fila.add("")
            fila.add(donde.coerceAtMost(fila.size), "")
        }
        r.alineaciones.add(donde.coerceAtMost(r.alineaciones.size), Alineacion.IZQUIERDA)
        return escribir(r)
    }

    fun quitarColumna(texto: String, cual: Int): String {
        val r = leer(texto) ?: return texto
        val ancho = r.filas.maxOfOrNull { it.size } ?: 0
        if (ancho <= 1 || cual !in 0 until ancho) return texto
        r.filas.forEach { if (cual < it.size) it.removeAt(cual) }
        if (cual < r.alineaciones.size) r.alineaciones.removeAt(cual)
        return escribir(r)
    }

    /** Su `TableModel.setAlign`, pero por columna: es lo que sabe decir Markdown. */
    fun alinear(texto: String, columna: Int, como: Alineacion): String {
        val r = leer(texto) ?: return texto
        while (r.alineaciones.size <= columna) r.alineaciones += Alineacion.IZQUIERDA
        if (columna !in r.alineaciones.indices) return texto
        r.alineaciones[columna] = como
        return escribir(r)
    }

    /** Rota la alineación de una columna: izquierda → centro → derecha → … */
    fun rotarAlineacion(texto: String, columna: Int): String {
        val actual = alineacionesDe(texto).getOrNull(columna) ?: Alineacion.IZQUIERDA
        val siguiente = when (actual) {
            Alineacion.IZQUIERDA -> Alineacion.CENTRO
            Alineacion.CENTRO -> Alineacion.DERECHA
            Alineacion.DERECHA -> Alineacion.IZQUIERDA
        }
        return alinear(texto, columna, siguiente)
    }

    fun alineacionesDe(texto: String): List<Alineacion> = leer(texto)?.alineaciones.orEmpty()

    fun tamaño(texto: String): Pair<Int, Int> {
        val r = leer(texto) ?: return 0 to 0
        return r.filas.size to (r.filas.maxOfOrNull { it.size } ?: 0)
    }

    /** ¿Es [texto] una tabla entera? */
    fun esTabla(texto: String): Boolean = leer(texto) != null

    /** Una tabla nueva de [filas] × [columnas], con la cabecera puesta. */
    fun nueva(filas: Int, columnas: Int): String {
        val f = filas.coerceIn(2, 20)
        val c = columnas.coerceIn(1, 12)
        val rejilla = Rejilla(
            MutableList(f) { MutableList(c) { "" } },
            MutableList(c) { Alineacion.IZQUIERDA }
        )
        return escribir(rejilla)
    }

    /**
     * En qué columna cae [pos] dentro del texto de la tabla.
     *
     * Se cuentan las barras desde el principio de la línea del cursor. Es lo que
     * hace falta para que «alinear» y «quitar columna» actúen sobre la columna
     * donde está el dedo y no sobre la primera.
     */
    fun columnaDe(texto: String, pos: Int): Int {
        val p = pos.coerceIn(0, texto.length)
        val inicioLinea = texto.lastIndexOf('\n', (p - 1).coerceAtLeast(0))
            .let { if (it < 0) 0 else it + 1 }
        val delante = texto.substring(inicioLinea, p)
        // La primera barra abre la fila, no separa columnas.
        return (delante.count { it == '|' } - 1).coerceAtLeast(0)
    }

    /** En qué fila cae [pos], sin contar la de guiones. */
    fun filaDe(texto: String, pos: Int): Int {
        val p = pos.coerceIn(0, texto.length)
        val hasta = texto.substring(0, p).count { it == '\n' }
        // La segunda línea es la de guiones: no es una fila.
        return if (hasta <= 1) 0 else hasta - 1
    }

    private fun celdas(fila: String): List<String> =
        fila.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun esGuiones(c: String): Boolean = Regex("""^:?-{1,}:?$""").matches(c)

    private fun alineacionDe(c: String): Alineacion {
        val izq = c.startsWith(":")
        val der = c.endsWith(":")
        return when {
            izq && der -> Alineacion.CENTRO
            der -> Alineacion.DERECHA
            else -> Alineacion.IZQUIERDA
        }
    }

    private fun guionesDe(a: Alineacion): String = when (a) {
        Alineacion.IZQUIERDA -> ":---"
        Alineacion.CENTRO -> ":---:"
        Alineacion.DERECHA -> "---:"
    }
}
