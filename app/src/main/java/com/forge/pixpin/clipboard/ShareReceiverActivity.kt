package com.forge.pixpin.clipboard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.floating.PinHostService

/**
 * Receptor del menú «Compartir» del sistema: cualquier documento, archivo o
 * foto compartido a PixPin se convierte en pin (imagen → pin de imagen,
 * cualquier otro archivo → pin de archivo que se abre al tocarlo).
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent) {
        val app = application as PixPinApp
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        if (uris.isEmpty()) return

        // Asegura que el servicio host esté vivo para mantener los pines
        PinHostService.start(this)
        uris.forEach { uri -> app.overlayManager.pinFromSharedUri(uri) }
    }

    private fun Intent.streamUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun Intent.streamUris(): List<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }
}
