package com.forge.pixpin.guardados

import com.forge.pixpin.motor.OrdenAlFrente
import java.lang.ref.WeakReference

/**
 * **El chat que el usuario tiene delante ahora mismo**, para meterle lo que llega del PC («p s»
 * en Flow Launcher: cualquier archivo al chat abierto). Como `motor/LienzoAlFrente`: lo pone el
 * `onResume` de [MensajesActivity] y lo quita su `onPause`.
 *
 * Se guarda la pantalla (débil) y no el chat: dentro de la misma pantalla se cambia de
 * conversación sin pasar por `onResume`, así que el chat se le pregunta en el momento.
 */
object ChatAlFrente {
    /** Un chat: su proyecto (`null` = la Conversación general) y su nombre, como se ve arriba. */
    data class Chat(val proyecto: String?, val nombre: String)

    /** Lo que sabe decir qué chat enseña. */
    fun interface Pantalla { fun chatQueSeVe(): Chat }

    @Volatile private var ref: WeakReference<Pantalla>? = null

    /** Cuándo se puso, en el orden de [OrdenAlFrente]. */
    @Volatile var orden = 0L
        private set

    fun poner(p: Pantalla) { ref = WeakReference(p); orden = OrdenAlFrente.siguiente() }

    /** Solo quita si es la misma: ver `LienzoAlFrente.quitar`. */
    fun quitar(p: Pantalla) { if (ref?.get() === p) ref = null }

    fun actual(): Chat? = ref?.get()?.chatQueSeVe()
}
