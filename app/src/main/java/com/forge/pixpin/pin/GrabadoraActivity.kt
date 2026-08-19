package com.forge.pixpin.pin

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File

/**
 * Grabar una nota de voz.
 *
 * ## Por qué una actividad y no la ventana flotante
 *
 * El micrófono no se puede abrir desde un servicio que arrancó en segundo plano —Android
 * lo prohíbe desde el 12— y el de PixPin arranca también al encender el móvil. Con una
 * actividad en primer plano no hay duda: es el caso para el que el permiso existe.
 *
 * Además hace falta pedir el permiso, y pedirlo es cosa de una actividad.
 *
 * ## Un toque para empezar, otro para parar
 *
 * No «mantener pulsado». Mantener obliga a sostener el móvil quieto mientras se habla, y
 * si uno suelta sin querer pierde la nota entera — justo lo que se quería apuntar. Con dos
 * toques se puede dejar el móvil en la mesa y hablar.
 */
class GrabadoraActivity : ComponentActivity() {

    private var grabador: MediaRecorder? = null
    private var destino: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pedirPermiso = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { concedido ->
            if (concedido) empezar() else salir(R.string.voz_sin_permiso)
        }

        setContent {
            PixPinTheme {
                var grabando by remember { mutableStateOf(false) }
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(shape = CircleShape, shadowElevation = 12.dp) {
                        Column(
                            Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                if (grabando) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = getString(R.string.voz_grabar),
                                tint = if (grabando) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = getString(
                                    if (grabando) R.string.voz_tocar_para_parar
                                    else R.string.voz_tocar_para_grabar
                                ),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    // El toque se coge en toda la pantalla: con el móvil en la mano y sin
                    // mirar, acertarle a un botón de dos centímetros es pedir demasiado.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable {
                                if (grabando) {
                                    terminar()
                                } else {
                                    grabando = true
                                    val tiene = ContextCompat.checkSelfPermission(
                                        this@GrabadoraActivity, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (tiene) empezar()
                                    else pedirPermiso.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                    )
                }
            }
        }
    }

    private fun empezar() {
        val archivo = Voz.archivoNuevo(this)
        destino = archivo
        grabador = Voz.empezar(this, archivo)
        if (grabador == null) salir(R.string.voz_sin_microfono)
    }

    /** Para, y **solo crea el pin si de verdad se grabó algo**. */
    private fun terminar() {
        val bien = Voz.parar(grabador)
        grabador = null
        val archivo = destino
        destino = null
        if (!bien || archivo == null || !archivo.exists() || archivo.length() <= 0) {
            archivo?.delete()
            salir(R.string.voz_muy_corta)
            return
        }
        if (Voz.duracion(archivo.absolutePath) < Voz.MINIMO_MS) {
            archivo.delete()
            salir(R.string.voz_muy_corta)
            return
        }
        (application as? PixPinApp)?.overlayManager?.pinVoz(archivo.absolutePath)
        finish()
    }

    private fun salir(mensaje: Int) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Si la pantalla se va con la grabación en marcha, no se deja el micrófono cogido. */
    override fun onStop() {
        super.onStop()
        if (grabador != null) {
            Voz.parar(grabador)
            grabador = null
            destino?.delete()
            destino = null
        }
    }
}
