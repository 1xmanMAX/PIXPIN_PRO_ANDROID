package com.forge.pixpin.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * **Un texto de la interfaz desde código sin `Context`** (1-nov-2026, al traer el inglés del PC).
 *
 * Hay funciones que montan rótulos sin tener un `Context` a mano —el vistazo de la lista de chats,
 * nombres por defecto— y que además se prueban en la JVM. [t] da el texto del idioma puesto
 * (ver [com.forge.pixpin.ajustes.IdiomaDeLaApp]) y, sin aplicación —en una prueba—, [porDefecto],
 * que es el español de `values/`: así las pruebas que miran textos siguen viendo los de siempre.
 */
object Textos {
    @Volatile
    var app: Context? = null

    fun t(@StringRes id: Int, porDefecto: String): String =
        app?.let { runCatching { it.resources.getString(id) }.getOrNull() } ?: porDefecto

    fun t(@StringRes id: Int, porDefecto: String, vararg args: Any): String =
        app?.let { runCatching { it.resources.getString(id, *args) }.getOrNull() } ?: String.format(porDefecto, *args)
}
