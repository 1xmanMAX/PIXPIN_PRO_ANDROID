package com.forge.pixpin.motor

import java.io.File
import java.lang.ref.WeakReference

/** Lo que sabe meter una imagen en lo que se está mirando. Interfaz para probarlo sin Android. */
interface RecibeImagenes {
    /** Llamar en el hilo de la interfaz. Devuelve si quedó puesta. El [archivo] no se toca: se copia. */
    fun recibirImagen(archivo: File, mime: String): Boolean
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

    fun poner(l: RecibeImagenes) { ref = WeakReference(l) }

    /**
     * Solo quita si es el mismo: al pasar de un lienzo a otro (o con dos en pantalla partida) el
     * `onResume` del nuevo puede llegar antes o después del `onPause` del viejo; así da igual.
     */
    fun quitar(l: RecibeImagenes) { if (ref?.get() === l) ref = null }

    fun actual(): RecibeImagenes? = ref?.get()
}
