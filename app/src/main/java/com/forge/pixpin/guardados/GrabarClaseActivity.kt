package com.forge.pixpin.guardados

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.forge.pixpin.R
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * **Preparar «Grabar clase»** (25-oct-2026, el paso «Preparar» de `grabar_clase.rs` del PC): qué
 * hace, dónde se guarda y un botón grande para empezar.
 *
 * Es una pantalla y no un botón directo en el chat por dos motivos de Android: los permisos
 * (micrófono y dibujar encima, sin los que no hay nota ni barra) se piden aquí sin tocar el chat,
 * y **el servicio de micrófono solo puede arrancar con la app a la vista** (Android 14+), que es
 * justo lo que es esto. Al empezar se cierra y queda la barra flotante ([GrabarClaseService]).
 *
 * Del PC no se trae «Sonido del equipo»: en el teléfono se graba el micrófono (ver el aviso de
 * [R.string.clase_solo_microfono]). Sí el **vídeo de la pantalla** (opcional, como el PC): su
 * permiso se pide aquí, una vez, y se le pasa al servicio ([VideoDeLaClase]).
 */
class GrabarClaseActivity : ComponentActivity() {

    private var proyecto: String? = null

    /** «Grabar también el vídeo de la pantalla» (25-oct-2026, como el PC): apagado, pesa mucho. */
    private var conVideo by androidx.compose.runtime.mutableStateOf(false)

    /**
     * **La calidad del vídeo** (25-oct-2026, como la ficha de Preparar del PC): «Ligero», «Normal» o
     * «Nítido», con lo que pesa una hora debajo. Se recuerda la última (por su clave, la del PC).
     */
    private var perfil by androidx.compose.runtime.mutableStateOf(PerfilDeVideo.NORMAL)

    private fun prefs() = getSharedPreferences("grabar_clase", MODE_PRIVATE)

    /**
     * El permiso de grabar la pantalla, **una vez para toda la clase** (Android 14: un permiso, un
     * `VirtualDisplay`). Sin él se graba solo el audio: no se pierde la clase por un «no».
     */
    private val pedirPantalla = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val datos = r.data
        if (r.resultCode == RESULT_OK && datos != null) arrancar(r.resultCode, datos)
        else Toast.makeText(this, R.string.capture_consent_denied, Toast.LENGTH_SHORT).show()
    }

    private val pedirMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
        if (si) seguir() else Toast.makeText(this, R.string.clase_sin_microfono, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proyecto = intent.getStringExtra(EL_PROYECTO)
        if (GrabarClaseService.enMarcha) {
            Toast.makeText(this, R.string.clase_ya, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        aPantallaCompleta()
        perfil = PerfilDeVideo.deClave(runCatching { prefs().getString(PERFIL, null) }.getOrNull())
        setContent { PixPinTheme { Pantalla() } }
    }

    @Composable
    private fun Pantalla() {
        val app = application as? com.forge.pixpin.PixPinApp
        val donde = proyecto?.let { p -> app?.proyectos?.porId(p)?.nombre }
            ?: if (proyecto == null) getString(R.string.clase_general) else null
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(getString(R.string.clase_titulo), style = MaterialTheme.typography.headlineSmall)
                Text(
                    getString(R.string.clase_sub),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    getString(R.string.clase_explicacion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    getString(R.string.clase_solo_microfono),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (donde != null) {
                    Text(
                        getString(R.string.clase_guardar_en, donde),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(getString(R.string.clase_con_video), Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = conVideo, onCheckedChange = { conVideo = it })
                }
                if (conVideo) {
                    Text(
                        getString(R.string.clase_video_explicacion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        getString(R.string.clase_calidad),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        for (p in PerfilDeVideo.entries) {
                            androidx.compose.material3.FilterChip(
                                selected = p == perfil,
                                onClick = {
                                    perfil = p
                                    runCatching { prefs().edit().putString(PERFIL, p.clave).apply() }
                                },
                                label = { Text(getString(nombreDe(p))) }
                            )
                        }
                    }
                    // Lo que pesa, siempre a la vista: en el teléfono no hay ratón que pase por encima.
                    Text(
                        getString(pistaDe(perfil)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Button(onClick = { empezar() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Text(getString(R.string.clase_empezar))
                }
                TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                    Text(getString(R.string.cancel))
                }
            }
        }
    }

    private fun empezar() {
        // Sin dibujar encima no hay barra: a los ajustes, como la portada.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_toast, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        seguir()
    }

    /** Con el micrófono concedido: el permiso de la pantalla si se quiere vídeo, o a grabar. */
    private fun seguir() {
        if (!conVideo) return arrancar()
        val pedir = runCatching {
            getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent()
        }.getOrNull()
        if (pedir == null) arrancar() else pedirPantalla.launch(pedir)
    }

    private fun arrancar(codigo: Int = 0, datos: Intent? = null) {
        if (GrabarClaseService.enMarcha) {
            Toast.makeText(this, R.string.clase_ya, Toast.LENGTH_SHORT).show()
        } else {
            // Ahora, con esta pantalla delante: es lo que deja al servicio quedarse el micrófono.
            runCatching { GrabarClaseService.empezar(this, proyecto, codigo, datos, perfil) }
                .onFailure { Toast.makeText(this, R.string.clase_no_arranca, Toast.LENGTH_LONG).show() }
        }
        finish()
    }

    private fun nombreDe(p: PerfilDeVideo) = when (p) {
        PerfilDeVideo.LIGERO -> R.string.clase_calidad_ligero
        PerfilDeVideo.NORMAL -> R.string.clase_calidad_normal
        PerfilDeVideo.NITIDO -> R.string.clase_calidad_nitido
    }

    private fun pistaDe(p: PerfilDeVideo) = when (p) {
        PerfilDeVideo.LIGERO -> R.string.clase_calidad_ligero_pista
        PerfilDeVideo.NORMAL -> R.string.clase_calidad_normal_pista
        PerfilDeVideo.NITIDO -> R.string.clase_calidad_nitido_pista
    }

    companion object {
        private const val EL_PROYECTO = "clase_proyecto"
        private const val PERFIL = "perfil_video"

        /** Abre la preparación para grabar en el chat [proyecto] (null, el general). */
        fun abrir(context: Context, proyecto: String?) {
            context.startActivity(Intent(context, GrabarClaseActivity::class.java).putExtra(EL_PROYECTO, proyecto))
        }
    }
}
