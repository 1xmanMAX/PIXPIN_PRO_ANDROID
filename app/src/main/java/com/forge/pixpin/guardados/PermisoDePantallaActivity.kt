package com.forge.pixpin.guardados

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.forge.pixpin.R

/**
 * **El permiso de grabar la pantalla a media grabación** (29-oct-2026, «Pantalla» de la barra de la
 * grabación única). Transparente, como [com.forge.pixpin.capture.ConsentActivity]: enseña el diálogo
 * del sistema y, si se acepta, se lo da al servicio **con esta pantalla aún delante** (Android 14 solo
 * deja poner el servicio en primer plano con el tipo `mediaProjection` así). Luego se va con su tarea y
 * queda lo que se estaba viendo, que es lo que se graba.
 */
class PermisoDePantallaActivity : ComponentActivity() {

    private val pedir = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val datos = r.data
        if (r.resultCode == Activity.RESULT_OK && datos != null) ReunionPresencialService.conPermisoDePantalla(r.resultCode, datos)
        else Toast.makeText(this, R.string.capture_consent_denied, Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        if (savedInstanceState == null) {
            val intento = runCatching { getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent() }.getOrNull()
            if (intento == null) {
                Toast.makeText(this, R.string.clase_sin_video, Toast.LENGTH_SHORT).show()
                finish()
            } else pedir.launch(intento)
        }
    }
}
