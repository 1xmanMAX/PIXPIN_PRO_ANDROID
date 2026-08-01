package com.forge.pixpin.pin

/** Tamaño del cuadro de un pin de texto, en dp. */
data class TextBoxDims(val width: Int, val height: Int)

/**
 * Límites del cuadro de un pin de texto.
 *
 * Está aparte del controlador, como [PinZoom], porque es lo único del gesto de
 * redimensionar que se puede comprobar sin un dispositivo delante.
 *
 * Los topes no son decorativos: por debajo del mínimo el cuadro deja de poder
 * agarrarse por su esquina, y por encima del máximo el pin tapa la pantalla que
 * está anotando, que es justo lo contrario de para lo que sirve.
 */
object TextBoxSize {

    const val MIN_WIDTH = 120
    const val MAX_WIDTH = 500
    const val MIN_HEIGHT = 60
    const val MAX_HEIGHT = 900

    /**
     * @param startWidth/startHeight tamaño en dp al empezar el gesto
     * @param dxDp/dyDp desplazamiento del dedo desde que empezó, en dp
     */
    fun resize(startWidth: Int, startHeight: Int, dxDp: Float, dyDp: Float): TextBoxDims =
        TextBoxDims(
            width = (startWidth + dxDp).toInt().coerceIn(MIN_WIDTH, MAX_WIDTH),
            height = (startHeight + dyDp).toInt().coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        )
}
