package com.forge.pixpin.capture

import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent

/**
 * Actividad transparente que solo muestra el diálogo del sistema de
 * consentimiento de captura y reenvía el resultado al [CaptureService].
 */
class ConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        startForegroundService(
            Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_PROJECTION_GRANTED
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_DATA, result.data)
            }
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            launcher.launch(mgr.createScreenCaptureIntent())
        }
    }
}
