package com.forge.pixpin.guardados

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * **La música con su letra se estudia en «Estudiar la clase»** (28-oct-2026, el usuario: «unifica la
 * pantalla de la música con la letra y Estudiar la clase: tienen funciones parecidas»).
 *
 * Esto era el karaoke (27-oct-2026): portada, la línea que suena en grande, play/pausa y ±10 s, el
 * auricular de llamadas, el tamaño de la letra, escribir o pegar la letra, sincronizarla a toques y
 * copiarla en LRC. **Todo eso está ahora en [EstudiarClaseActivity]**, que con una canción enseña
 * arriba su cartel (portada, título y la línea que suena en grande) y esconde lo que no va con una
 * canción (capturas, marcadores, clips). Lo que era solo de aquí (el editor de la letra, el
 * sincronizador) vive en `LetraEnPantalla.kt`.
 *
 * Queda como **un desvío**: lo que aún la abra (un acceso viejo, la biblioteca de audio, la tarjeta de
 * música del chat por [abrir]) llega a la misma pantalla de estudiar.
 */
class MusicaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EL_MENSAJE)?.let { EstudiarClaseActivity.abrir(this, it) }
        finish()
    }

    companion object {
        private const val EL_MENSAJE = "musica_mensaje"

        /** Abre la canción [mensaje] para estudiarla (cantarla) en [EstudiarClaseActivity]. */
        fun abrir(context: Context, mensaje: String) {
            EstudiarClaseActivity.abrir(context, mensaje)
        }

        /** El intento de siempre (por si algo lo guardó): llega a la pantalla de estudiar. */
        @Suppress("unused")
        fun intento(context: Context, mensaje: String): Intent =
            Intent(context, MusicaActivity::class.java).putExtra(EL_MENSAJE, mensaje).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
