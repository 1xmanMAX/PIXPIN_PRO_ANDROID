package com.forge.pixpin.motor

/**
 * **Texto pegado en el lienzo** (14-oct-2026): lo que se hace con un texto del portapapeles antes
 * de convertirlo en un texto del motor.
 *
 * Es el gemelo de `pixpin_motor2d::texto_pegado` del PC (commit fdc9095), que a su vez sigue el
 * `addTextFromPaste` de Excalidraw en su variante de «pegado plano»: un solo elemento con todo el
 * texto. Las reglas son las mismas en los dos para que lo pegado en uno y en otro salga igual:
 *
 * - los finales de línea se unifican y los tabuladores pasan a cuatro espacios (`normalizeText`);
 * - fuera los renglones vacíos de los extremos, el espacio de delante del primero y el de detrás de
 *   cada renglón; la sangría de los demás se respeta, que puede ser código;
 * - un renglón más ancho que [anchoMaximo] se parte **por palabras** con saltos de verdad (aquí un
 *   texto suelto no tiene ancho fijo). Una palabra nunca se parte: una dirección web partida
 *   dejaría de servir al copiarla de vuelta;
 * - y un tope de [TOPE_CARACTERES]: lo que pasa se corta y se marca con «…».
 *
 * Sin Android: entra un texto y sale otro. Quien lo coloca es [DrawController.pegarTexto].
 */
object TextoPegado {

    /** Lo más largo que se pega de una vez, en caracteres: unas diez páginas. Igual que el PC. */
    const val TOPE_CARACTERES = 20_000

    /** Un tabulador, en espacios: lo que mide la tecla Tab en Excalidraw. */
    private const val TABULADOR = "    "

    /**
     * El ancho máximo de un texto pegado, en unidades de la escena: la mitad de lo que se ve, entre
     * 200 y 800 (`maxTextWidth` de Excalidraw).
     */
    fun anchoMaximo(anchoVisible: Double): Double =
        if (!anchoVisible.isFinite()) 800.0 else (anchoVisible * 0.5).coerceIn(200.0, 800.0)

    /**
     * **Limpia un texto del portapapeles** para pegarlo como texto nuevo. Null si no queda nada que
     * pegar (vacío o solo espacios).
     */
    fun preparar(crudo: String): String? {
        val limpio = normalizar(crudo)
        // Los espacios al final de cada renglón no se ven y engordan la caja.
        val junto = limpio.split('\n').joinToString("\n") { it.trimEnd() }
        val recortado = junto.trim('\n').trimStart(' ').trimEnd()
        if (recortado.isEmpty()) return null
        return cortarAlTope(recortado)
    }

    /** Finales de línea, tabuladores y caracteres de control. */
    private fun normalizar(crudo: String): String {
        val limpio = StringBuilder(minOf(crudo.length, TOPE_CARACTERES * 4))
        var i = 0
        while (i < crudo.length) {
            val c = crudo[i]
            when {
                // CRLF de Windows y CR suelto del Mac viejo: los dos son un salto.
                c == '\r' -> {
                    if (i + 1 < crudo.length && crudo[i + 1] == '\n') i++
                    limpio.append('\n')
                }
                c == '\n' -> limpio.append('\n')
                c == '\t' -> limpio.append(TABULADOR)
                // Los separadores de párrafo y de línea de Unicode (Word los deja a veces).
                c == ' ' || c == ' ' -> limpio.append('\n')
                // Lo demás que sea de control (NUL, el ^V de algún terminal, la marca de orden de
                // bytes) no se ve y estorbaría al medir.
                c.isISOControl() || c == '﻿' -> {}
                else -> limpio.append(c)
            }
            i++
        }
        return limpio.toString()
    }

    /** Corta en [TOPE_CARACTERES] —contando letras, no mitades de un emoji— y marca el corte. */
    private fun cortarAlTope(texto: String): String {
        if (texto.codePointCount(0, texto.length) <= TOPE_CARACTERES) return texto
        val fin = texto.offsetByCodePoints(0, TOPE_CARACTERES)
        return texto.substring(0, fin).trimEnd() + "…"
    }

    /**
     * **Parte en renglones** lo que pase de [ancho] (medido con [mide]), solo por los espacios. Los
     * saltos que ya traía el texto se respetan, y la sangría de un párrafo va con su primera
     * palabra.
     */
    fun ajustar(texto: String, ancho: Double, mide: (String) -> Double): String {
        if (ancho <= 0.0) return texto
        val salida = StringBuilder(texto.length + texto.length / 40)
        for ((i, parrafo) in texto.split('\n').withIndex()) {
            if (i > 0) salida.append('\n')
            if (mide(parrafo) <= ancho) {
                salida.append(parrafo)
                continue
            }
            val sangria = parrafo.length - parrafo.trimStart(' ').length
            val linea = StringBuilder(parrafo.substring(0, sangria))
            var vacia = true
            for (palabra in parrafo.substring(sangria).split(' ')) {
                if (palabra.isEmpty()) continue
                if (vacia) {
                    linea.append(palabra)
                    vacia = false
                    continue
                }
                val largo = linea.length
                linea.append(' ').append(palabra)
                if (mide(linea.toString()) > ancho) {
                    linea.setLength(largo)
                    salida.append(linea).append('\n')
                    linea.setLength(0)
                    linea.append(palabra)
                }
            }
            salida.append(linea)
        }
        return salida.toString()
    }
}
