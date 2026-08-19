package com.forge.pixpin.motor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * La cabecera de una ventana flotante: **lo mínimo para manejarla**.
 *
 * Todas las ventanitas del editor —la paleta, la referencia, los ajustes— se
 * mueven, se cierran y se agrandan igual, así que la cabecera es la misma. Y
 * como flotan **encima del dibujo**, cada píxel que ocupa la cabecera es dibujo
 * que no se ve: va a la altura de un dedo escaso y sin título, que en una
 * ventana que acabas de abrir tú no dice nada que no sepas.
 *
 * ## Baja pero no difícil de coger
 *
 * Son dos cosas distintas y aquí se separan. El asa de arrastrar es **toda la
 * franja del medio**, de lado a lado: aunque mida veintiséis puntos de alto, es
 * un blanco larguísimo y el dedo la encuentra sin mirar. Lo que se aprieta es lo
 * que no hace falta —el título— y no la zona que se toca.
 *
 * ## El orden
 *
 * Ampliar a la izquierda, el asa en medio y cerrar a la derecha, con lo que la
 * ventana necesite entre medias. Cerrar va en la punta a propósito: es el que
 * más caro sale pulsar por error, así que se pone donde el pulgar no cae solo al
 * ir a agarrar el asa.
 */
@Composable
fun BarraDeVentana(
    onArrastrar: (Float, Float) -> Unit,
    onAmpliar: (() -> Unit)?,
    onCerrar: () -> Unit,
    modifier: Modifier = Modifier,
    /** El color de los iconos. Sobre una imagen hace falta que sea claro. */
    tinta: Color = Color.Unspecified,
    /** Lo que la ventana añada, entre el asa y el botón de cerrar. */
    extras: @Composable () -> Unit = {}
) {
    val color = if (tinta == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant
    else tinta
    Row(
        modifier.fillMaxWidth().height(ALTO_DE_LA_CABECERA),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onAmpliar != null) {
            BotonMinimo(
                Icons.Filled.OpenInFull,
                "Ampliar la ventana", color, onAmpliar
            )
        }

        // **El asa: toda la franja del medio.** Se arrastra desde cualquier
        // punto de ella, y la pastillita solo dice dónde está.
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures { cambio, arrastre ->
                        cambio.consume()
                        onArrastrar(arrastre.x, arrastre.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 26.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.55f))
            )
        }

        extras()
        BotonMinimo(
            Icons.Filled.Close,
            "Cerrar la ventana", color, onCerrar
        )
    }
}

/**
 * Un botón de la cabecera: **sin el relleno de un `IconButton`**.
 *
 * El de Material mide cuarenta y ocho puntos por accesibilidad, y tres de ellos
 * seguidos son más alto que toda esta barra. Aquí el blanco es el propio icono
 * con su aire, que en una ventana que ya se está tocando con el dedo encima es
 * suficiente.
 */
@Composable
private fun BotonMinimo(
    icono: ImageVector,
    descripcion: String,
    tinta: Color,
    alTocar: () -> Unit
) {
    Box(
        Modifier
            .size(ALTO_DE_LA_CABECERA)
            .clip(RoundedCornerShape(6.dp))
            .tocable(alTocar),
        contentAlignment = Alignment.Center
    ) {
        Icon(icono, contentDescription = descripcion, tint = tinta, modifier = Modifier.size(15.dp))
    }
}

/** Un toque sin el destello, que en una barra tan baja se ve como un parpadeo. */
private fun Modifier.tocable(alTocar: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(alTocar) { detectTapGestures { alTocar() } }
)

/**
 * Lo que mide de alto.
 *
 * Veintiséis puntos: por debajo, el icono deja de leerse; por encima, se le come
 * una franja al dibujo sin dar nada a cambio. El asa compensa lo bajo que es
 * siendo larga.
 */
val ALTO_DE_LA_CABECERA: Dp = 26.dp
