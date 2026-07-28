package com.forge.pixpin.clipboard

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.floating.PinHostService
import kotlinx.coroutines.launch

/**
 * Receptor del menú «Compartir» del sistema: cualquier documento, archivo o
 * foto compartido a PixPin se convierte en pin (imagen → pin de imagen,
 * cualquier otro archivo → pin de archivo que se abre al tocarlo).
 *
 * La copia del archivo se hace ANTES de finish(): el permiso sobre la URI
 * compartida se revoca en cuanto la actividad termina.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val app = application as PixPinApp
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            finish()
            return
        }

        // Asegura que el servicio host esté vivo para mantener los pines
        PinHostService.start(this)
        lifecycleScope.launch {
            uris.forEach { uri -> app.overlayManager.pinFromUri(uri) }
            finishAndRemoveTask()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
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
