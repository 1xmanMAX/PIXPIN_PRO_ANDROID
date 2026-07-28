package com.forge.pixpin.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.forge.pixpin.R

/**
 * Muestra el diálogo del sistema de consentimiento de captura y, solo si el
 * usuario acepta, arranca [CaptureService] con el token.
 *
 * El orden importa: en Android 14+ arrancar un servicio de tipo mediaProjection
 * sin este consentimiento previo lanza SecurityException y mata la app.
 */
class ConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.startSession(this, result.resultCode, data)
        } else {
            CaptureFlow.toast(this, R.string.capture_consent_denied)
        }
        // Se va con su tarea: la pantalla vuelve a la app del usuario, que es
        // la que hay que capturar.
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        if (savedInstanceState == null) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val intent = runCatching { mgr.createScreenCaptureIntent() }.getOrNull()
            if (intent == null) {
                CaptureFlow.toast(this, R.string.capture_error)
                finish()
            } else {
                launcher.launch(intent)
            }
        }
    }
}
