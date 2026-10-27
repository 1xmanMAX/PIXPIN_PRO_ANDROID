package com.forge.pixpin.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * **El riel se va con las barras** (27-oct-2026, el usuario: «al mover el contenido se esconden la
 * barra de arriba y la de abajo, pero la lateral no: que se esconda con ellas y vuelva con ellas al
 * tocar»). Cada visor ya tenía su «a la vista» —la pastilla de arriba y los mandos de abajo salen al
 * tocar y se van al mover o solos a los pocos segundos—; el riel ([RielDelVisor]) pasa a seguir ese
 * mismo estado, con el mismo fundido.
 *
 * Lo único que lo deja quieto es **estar usándolo**: anotando (el lápiz puesto), con los marcos, con
 * la tira de emoticonos de un marcador abierta o con una herramienta propia del formato en marcha
 * (medir o las piezas del 3D). Ahí el riel es la salida de lo que se está haciendo, y esconderlo al
 * mover la hoja dejaría a uno sin poder dejar de anotar.
 *
 * Puro, para probarlo en la JVM ([RielALaVistaTest]).
 */
object RielALaVista {

    /** Si el riel (y sus puntos de marcadores) se ve: con las barras, o mientras se usa. */
    fun visible(
        barras: Boolean,
        anotando: Boolean,
        marcando: Boolean,
        poniendoMarca: Boolean = false,
        otraHerramienta: Boolean = false
    ): Boolean = barras || anotando || marcando || poniendoMarca || otraHerramienta

    /** Lo que dura un toque como mucho: un dedo que se queda más es mantener, no tocar. */
    const val TOQUE_MAXIMO_MS = 500L

    /**
     * **Si un gesto fue un toque** (el que saca las barras): un solo dedo, sin salirse del margen
     * del sistema y sin quedarse apretado. Mover o pellizcar las esconde, y mantener (elegir texto,
     * abrir un menú) no debe sacarlas.
     */
    fun esUnToque(recorrido: Float, margen: Float, dedos: Int, duracionMs: Long): Boolean =
        dedos == 1 && recorrido <= margen && duracionMs < TOQUE_MAXIMO_MS
}

/**
 * **Toque o movida, sin quitarle el gesto a nadie** (27-oct-2026): mira en la pasada inicial y no
 * consume, así el desplazamiento, los enlaces y el pellizco siguen como estaban. [alMover] llega en
 * cuanto un dedo pasa del margen; [alTocar], al levantar si fue un toque ([RielALaVista.esUnToque]).
 * Para los visores que no tenían ya su propio lector de toques (la nota, el lienzo mirando).
 */
fun Modifier.toqueOMovida(alTocar: () -> Unit, alMover: () -> Unit): Modifier = composed {
    val tocar by rememberUpdatedState(alTocar)
    val mover by rememberUpdatedState(alMover)
    pointerInput(Unit) {
        awaitEachGesture {
            val primero = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val inicio = primero.position
            var recorrido = 0f
            var dedos = 1
            var movido = false
            var fin = primero.uptimeMillis
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                dedos = maxOf(dedos, e.changes.count { it.pressed })
                e.changes.firstOrNull { it.id == primero.id }?.let { c ->
                    recorrido = maxOf(recorrido, (c.position - inicio).getDistance())
                    fin = c.uptimeMillis
                }
                if (!movido && (recorrido > viewConfiguration.touchSlop || dedos > 1)) { movido = true; mover() }
                if (e.changes.none { it.pressed }) break
            }
            if (RielALaVista.esUnToque(recorrido, viewConfiguration.touchSlop, dedos, fin - primero.uptimeMillis)) tocar()
        }
    }
}
