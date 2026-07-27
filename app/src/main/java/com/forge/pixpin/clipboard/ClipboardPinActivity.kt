package com.forge.pixpin.clipboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.forge.pixpin.PixPinApp

/**
 * Actividad transparente de un instante: pone la app en primer plano para
 * poder leer el portapapeles en Android 10+ (restricción del sistema) y
 * crea el pin correspondiente. El usuario no percibe nada.
 */
class ClipboardPinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as PixPinApp).overlayManager.pinFromClipboard()
        finish()
    }
}
