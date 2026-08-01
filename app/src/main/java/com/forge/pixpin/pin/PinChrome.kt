package com.forge.pixpin.pin

/** Hueco alrededor del recuadro del pin, en dp. */
data class PinInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val horizontal: Int get() = left + right
    val vertical: Int get() = top + bottom
}

/**
 * Cuánto tiene que medir la ventana de más que el recuadro del pin.
 *
 * Una ventana overlay **recorta todo lo que se dibuje fuera de sus límites**, y
 * hay dos cosas que salen del recuadro a propósito: la sombra y la pegatina de
 * emoji. Sin este hueco la sombra aparece cortada en seco contra el borde.
 *
 * La cuenta vive aquí y no repartida entre `applyContentSize` y `PinRoot`
 * porque es exactamente donde se desincronizaría: una de las dos sumaría un
 * margen que la otra no, y el pin daría un salto o la sombra se cortaría por un
 * lado. Además, así se puede comprobar sin dispositivo.
 */
object PinChrome {

    /** Hueco para la sombra, por los cuatro lados. */
    const val SHADOW_DP = 8

    /** Hueco extra para la pegatina, que va arriba a la derecha. */
    const val STICKER_INSET_DP = 20

    /** Elevación de la sombra. Pequeña: separa del fondo sin dibujar una caja. */
    const val SHADOW_ELEVATION_DP = 6

    /** Lado de la pegatina. Mayor que su hueco: por eso pisa la esquina. */
    const val STICKER_SIZE_DP = 34

    fun insetsFor(hasEmoji: Boolean): PinInsets {
        val sticker = if (hasEmoji) STICKER_INSET_DP else 0
        return PinInsets(
            left = SHADOW_DP,
            top = SHADOW_DP + sticker,
            right = SHADOW_DP + sticker,
            bottom = SHADOW_DP
        )
    }
}
