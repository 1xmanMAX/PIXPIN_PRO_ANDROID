package com.forge.pixpin.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **La hoja de compartir de un archivo, desde donde no hay pantalla** (27-oct-2026).
 *
 * Un pin flota en su ventana de la superposición y la captura se cierra al compartir: ninguno de
 * los dos tiene una pantalla de Compose donde subir la [HojaDeCompartir], y por eso mandaban el
 * archivo al panel del teléfono sin más, sin imagen, PDF, página web, `.pixpin` ni Wi-Fi como en
 * el resto de la aplicación. Esta pantalla transparente sube la misma hoja ([ExportarVisor.deArchivo])
 * y se va con ella.
 */
class CompartirArchivoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ruta = intent.getStringExtra(EXTRA_RUTA)
        if (ruta == null || !File(ruta).isFile) { finish(); return }
        val nombre = intent.getStringExtra(EXTRA_NOMBRE)?.ifBlank { null } ?: File(ruta).name
        val detalle = intent.getStringExtra(EXTRA_DETALLE)?.ifBlank { null } ?: "Tal cual, sin cambiar nada"
        setContent {
            PixPinTheme {
                var c by remember { mutableStateOf<Compartible?>(null) }
                LaunchedEffect(ruta) {
                    c = withContext(Dispatchers.IO) {
                        runCatching { ExportarVisor.deArchivo(this@CompartirArchivoActivity, ruta, nombre, detalle) }
                            .onFailure { android.util.Log.e("PixPinCompartir", "archivo", it) }.getOrNull()
                    }
                    if (c == null) finish()
                }
                c?.let { HojaDeCompartir(it) { finish() } }
            }
        }
    }

    companion object {
        private const val EXTRA_RUTA = "ruta"
        private const val EXTRA_NOMBRE = "nombre"
        private const val EXTRA_DETALLE = "detalle"

        /** Sube la hoja de compartir de [archivo]. Vale desde un servicio: abre su propia tarea. */
        fun abrir(context: Context, archivo: File, nombre: String = archivo.name, detalle: String? = null) {
            val i = Intent(context, CompartirArchivoActivity::class.java)
                .putExtra(EXTRA_RUTA, archivo.absolutePath).putExtra(EXTRA_NOMBRE, nombre).putExtra(EXTRA_DETALLE, detalle)
            if (context !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }
}
