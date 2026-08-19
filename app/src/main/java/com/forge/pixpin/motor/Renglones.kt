package com.forge.pixpin.motor

/**
 * Partir un texto en los renglones que caben en un ancho.
 *
 * ## Por qué hacía falta
 *
 * Un texto del lienzo tenía **tope por abajo pero no por los lados**: se pintaba renglón a
 * renglón desde su esquina, partiendo solo por los saltos de línea que uno hubiera
 * escrito. Estrechabas su caja y el texto **se salía por la derecha** tan tranquilo, por
 * encima de lo que hubiera al lado. La caja decía una cosa y el texto hacía otra.
 *
 * Lo que se espera de una caja de texto es lo de siempre: la estrechas y el texto pasa a
 * la línea siguiente; la caja se hace más alta y el texto sigue dentro.
 *
 * ## Cómo mide
 *
 * Medir letras es de Android —depende de la fuente, del tamaño y hasta de la versión— así
 * que **la medida entra por fuera**, como una función. Aquí solo está la decisión de dónde
 * cortar, que es lo que se puede comprobar sin un móvil delante: que se corte por espacios
 * cuando los hay, que una palabra más larga que la caja se parta en vez de desbordarse, y
 * que los saltos escritos a mano se respeten siempre.
 */

/**
 * Los renglones de [texto] que caben en [ancho], midiendo con [mide].
 *
 * [mide] devuelve lo que ocupa un trozo de texto. Con un ancho de cero o negativo no se
 * parte nada: es lo que pasa mientras se está creando la caja arrastrando, y partir por un
 * ancho que aún no existe dejaría una letra por renglón.
 */
fun renglonesQueCaben(texto: String, ancho: Double, mide: (String) -> Double): List<String> {
    if (texto.isEmpty()) return listOf("")
    // Los saltos escritos mandan siempre: son del autor, no del ajuste.
    val escritos = texto.split('\n')
    if (ancho <= 0.0) return escritos
    return escritos.flatMap { partirRenglon(it, ancho, mide) }
}

/** Un renglón suelto, partido por espacios y —si no queda otra— por letras. */
private fun partirRenglon(renglon: String, ancho: Double, mide: (String) -> Double): List<String> {
    if (renglon.isEmpty() || mide(renglon) <= ancho) return listOf(renglon)

    val salida = mutableListOf<String>()
    var actual = StringBuilder()

    fun cerrar() {
        salida.add(actual.toString())
        actual = StringBuilder()
    }

    // Se parte conservando los espacios con la palabra que los precede: así al volver a
    // juntar los renglones sale el texto original, que es lo que se guarda y lo que se
    // vuelve a editar.
    for (palabra in trocearConEspacios(renglon)) {
        val cabe = mide(actual.toString() + palabra) <= ancho
        if (cabe) {
            actual.append(palabra)
            continue
        }
        if (actual.isNotEmpty()) cerrar()
        // **Una palabra más larga que la caja se parte por letras.** Dejarla entera sería
        // volver al problema de siempre —texto saliéndose por la derecha— y justo en el
        // caso que más se nota: una dirección web, una fórmula, un número largo.
        if (mide(palabra) <= ancho) {
            actual.append(palabra)
        } else {
            for (letra in palabra) {
                if (actual.isNotEmpty() && mide(actual.toString() + letra) > ancho) cerrar()
                actual.append(letra)
            }
        }
    }
    if (actual.isNotEmpty() || salida.isEmpty()) cerrar()
    return salida
}

/**
 * El renglón en trozos «palabra + los espacios que la siguen».
 *
 * Se llevan los espacios pegados detrás para que un corte no se coma ninguno: el texto que
 * se guarda tiene que poder rearmarse tal cual se escribió.
 */
private fun trocearConEspacios(renglon: String): List<String> {
    val trozos = mutableListOf<String>()
    var i = 0
    while (i < renglon.length) {
        val inicio = i
        while (i < renglon.length && !renglon[i].isWhitespace()) i++
        while (i < renglon.length && renglon[i].isWhitespace()) i++
        trozos.add(renglon.substring(inicio, i))
    }
    return trozos
}

/**
 * Lo que mide de alto un texto ya partido.
 *
 * El interlineado es el de Excalidraw, 1,25 veces el tamaño de letra, y está aquí para que
 * el alto que se guarda y el que se pinta salgan **de la misma cuenta**: si se calculan en
 * dos sitios, tarde o temprano dejan de coincidir y el texto se sale de su propia caja.
 */
fun altoDeRenglones(cuantos: Int, tamano: Double): Double =
    cuantos.coerceAtLeast(1) * tamano * INTERLINEADO

/** El interlineado del original. */
const val INTERLINEADO = 1.25
