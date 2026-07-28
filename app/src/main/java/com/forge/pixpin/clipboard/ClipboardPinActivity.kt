package com.forge.pixpin.clipboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import kotlinx.coroutines.launch

/**
 * Actividad transparente de un instante: pone la app en primer plano para
 * poder leer el portapapeles y crea el pin correspondiente.
 *
 * Dos detalles que Android impone y que hay que respetar:
 * 1. Desde Android 10 el portapapeles solo se puede leer con la ventana
 *    ENFOCADA — no basta con estar creada (por eso se lee en
 *    onWindowFocusChanged y no en onCreate).
 * 2. Si el contenido es una URI (imagen o documento), el permiso de lectura
 *    muere con la actividad: hay que terminar la importación ANTES de finish().
 */
class ClipboardPinActivity : ComponentActivity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        lifecycleScope.launch {
            (application as PixPinApp).overlayManager.pinFromClipboard()
            // finishAndRemoveTask: la tarea propia desaparece y el usuario
            // vuelve directo a su app, sin pasar por PixPin.
            finishAndRemoveTask()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
