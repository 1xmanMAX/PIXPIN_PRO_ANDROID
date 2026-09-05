package com.forge.pixpin.guardados

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * **Toda la pantalla para la aplicación**: sin la barra de estado ni la de navegación
 * encima de lo que se está haciendo (la hora, el wifi y los botones se superponían a la
 * cabecera de la letra y de la biblioteca; lo reportó el usuario el 5-sep-2026). Un
 * deslizamiento desde el borde las enseña un momento, como en el editor del lienzo.
 */
fun Activity.aPantallaCompleta() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controlador = WindowInsetsControllerCompat(window, window.decorView)
    controlador.hide(WindowInsetsCompat.Type.systemBars())
    controlador.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
