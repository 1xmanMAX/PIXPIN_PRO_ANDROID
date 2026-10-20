package com.forge.pixpin.motor

import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** Lo que sabe meter una imagen en lo que se está mirando. Interfaz para probarlo sin Android. */
interface RecibeImagenes {
    /** Llamar en el hilo de la interfaz. Devuelve si quedó puesta. El [archivo] no se toca: se copia. */
    fun recibirImagen(archivo: File, mime: String): Boolean
}

/**
 * **Lo que sabe meter figuras ya hechas en lo que se está mirando**: el asistente, que dibuja en el
 * lienzo abierto (como en el PC, «hay un lienzo abierto → ahí, centrado en la vista»). Llamar en el
 * hilo de la interfaz. Lo mete **en un solo paso de deshacer**, centrado en lo que se ve, y
 * devuelve el id del dibujo; null si no se pudo (un lienzo que solo se mira).
 */
interface RecibeElementos {
    fun recibirElementos(elementos: List<Element>): String?
}

/**
 * **Lo que deja al asistente editar el lienzo abierto** (PC 14-oct, herramientas de editar): la escena
 * tal como está, lo que el usuario tiene elegido («lo elegido») y la esquina de lo que se ve (el marco
 * de las coordenadas del modelo); y ponerle la escena editada en un solo paso de deshacer. Llamar en
 * el hilo de la interfaz.
 */
interface EditaElLienzo {
    /** Null si el lienzo solo se mira. */
    fun paraEditar(): LienzoAbierto?
    fun ponerEscena(elementos: List<Element>, archivos: Map<String, SceneFile>): Boolean
}

data class LienzoAbierto(val id: String, val escena: Scene, val elegidos: Set<String>, val esquina: Pt)

/**
 * **Quién se puso delante el último**, entre el lienzo ([LienzoAlFrente]) y el chat
 * (`guardados/ChatAlFrente`). Con la pantalla partida pueden estar los dos a la vista: manda el
 * del `onResume` más reciente.
 */
object OrdenAlFrente {
    private val cuenta = AtomicLong(0)
    fun siguiente(): Long = cuenta.incrementAndGet()
}

/**
 * **El lienzo que el usuario tiene delante ahora mismo**, para meterle lo que llega del PC (la
 * «foto al lienzo del móvil», `suelto` en `sincro/Protocolo.kt`).
 *
 * `LienzosAbiertos` es la tira de la multitarea, no «el de ahora»: esto lo pone el `onResume` del
 * editor y lo quita su `onPause`. Débil: si la pantalla se destruye sin pasar por `onPause` no se
 * queda colgada en memoria.
 */
object LienzoAlFrente {
    @Volatile private var ref: WeakReference<RecibeImagenes>? = null

    /** Cuándo se puso, en el orden de [OrdenAlFrente]. */
    @Volatile var orden = 0L
        private set

    fun poner(l: RecibeImagenes) { ref = WeakReference(l); orden = OrdenAlFrente.siguiente() }

    /**
     * Solo quita si es el mismo: al pasar de un lienzo a otro (o con dos en pantalla partida) el
     * `onResume` del nuevo puede llegar antes o después del `onPause` del viejo; así da igual.
     */
    fun quitar(l: RecibeImagenes) { if (ref?.get() === l) ref = null }

    fun actual(): RecibeImagenes? = ref?.get()
}
