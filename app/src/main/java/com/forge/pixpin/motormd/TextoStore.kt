package com.forge.pixpin.motormd

import androidx.compose.runtime.mutableStateMapOf

/**
 * Por dónde vuelve el texto del editor avanzado al pin.
 *
 * El editor es **otra actividad** —a pantalla completa, con su teclado y su
 * historial— y el pin es una ventana del servicio que sigue viva detrás. No hay
 * resultado que devolver porque el pin no lanzó nada con `startActivityForResult`:
 * lo lanzó desde un contexto de servicio.
 *
 * Así que se hace igual que con los dibujos —ver `ExcalidrawStore`—: el editor
 * deja aquí lo que escribió con una revisión nueva, y el pin, que está mirando
 * esa revisión desde la composición, se entera y lo recoge. Es memoria y no
 * disco porque los dos están en el mismo proceso y el texto del pin ya se
 * persiste por su cuenta al cambiarlo.
 *
 * La revisión es **por pin**. Con una global, editar cualquier nota recargaría
 * todas las demás.
 */
object TextoStore {

    private val textos = mutableStateMapOf<String, String>()
    private val revisiones = mutableStateMapOf<String, Int>()

    fun revisionDe(id: String): Int = revisiones[id] ?: 0

    fun guardar(id: String, texto: String) {
        textos[id] = texto
        revisiones[id] = revisionDe(id) + 1
    }

    fun leer(id: String): String? = textos[id]

    /** Se suelta al recogerlo: no tiene por qué seguir ocupando sitio. */
    fun recoger(id: String): String? = textos.remove(id)
}
