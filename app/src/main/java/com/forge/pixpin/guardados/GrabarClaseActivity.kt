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

    /**
     * **El modo**: «Audio y capturas», «Pantalla y sonido» (25-oct-2026) o **«Presencial»** (28-oct-2026,
     * la reunión en persona del PC, §3.3). Se recuerda por su palabra, la del PC ([Presencial.Modo]).
     */
    private var modo by androidx.compose.runtime.mutableStateOf(Presencial.Modo.CAPTURAS)

    /** «Grabar también el vídeo de la pantalla» (25-oct-2026, como el PC): el modo «Pantalla y sonido». */
    private val conVideo: Boolean get() = modo == Presencial.Modo.PANTALLA

    /** La presencial: vídeo de la sala o solo audio, su calidad y si sale la burbuja (28-oct-2026). */
    private var forma by androidx.compose.runtime.mutableStateOf(Presencial.Forma.AUDIO)
    private var calidad by androidx.compose.runtime.mutableStateOf(Presencial.Calidad.AUTOMATICA)
    private var conBurbuja by androidx.compose.runtime.mutableStateOf(true)

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

    /**
     * **Los permisos de la presencial** (28-oct-2026): el micrófono (sin él no hay reunión), la cámara
     * (sin ella, solo audio) y los avisos (Android 13: sin ellos la notificación con Foto, Clip y
     * Terminar no se ve).
     */
    private val pedirLosDeLaReunion = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val microfono = r[Manifest.permission.RECORD_AUDIO] == true || tiene(Manifest.permission.RECORD_AUDIO)
        if (!microfono) {
            Toast.makeText(this, R.string.clase_sin_microfono, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (!tiene(Manifest.permission.CAMERA)) Toast.makeText(this, R.string.reunion_sin_camara_permiso, Toast.LENGTH_LONG).show()
        arrancarLaReunion()
    }

    private fun tiene(permiso: String) = ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proyecto = intent.getStringExtra(EL_PROYECTO)
        if (GrabarClaseService.enMarcha || ReunionPresencialService.enMarcha) {
            Toast.makeText(this, R.string.clase_ya, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        aPantallaCompleta()
        perfil = PerfilDeVideo.deClave(runCatching { prefs().getString(PERFIL, null) }.getOrNull())
        // **El modo, recordado** (25-oct-2026, como el PC: «pantalla», «capturas» o «presencial»).
        modo = Presencial.Modo.de(runCatching { prefs().getString(MODO, null) }.getOrNull())
        forma = Presencial.Forma.de(runCatching { prefs().getString(FORMA, null) }.getOrNull())
        calidad = Presencial.Calidad.de(runCatching { prefs().getString(CALIDAD, null) }.getOrNull())
        conBurbuja = runCatching { prefs().getBoolean(BURBUJA, true) }.getOrDefault(true)
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
                // **Dos modos** (25-oct-2026, como el selector de Preparar del PC): «Pantalla y sonido»
                // (el vídeo de esta pantalla con el micrófono, y su calidad) o «Audio y capturas»
                // (solo el sonido; «Captura» guarda la pantalla entera al instante).
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    for ((m, rotulo) in listOf(
                        Presencial.Modo.CAPTURAS to R.string.clase_modo_capturas,
                        Presencial.Modo.PANTALLA to R.string.clase_modo_pantalla,
                        Presencial.Modo.PRESENCIAL to R.string.clase_modo_presencial
                    )) {
                        androidx.compose.material3.FilterChip(
                            selected = modo == m,
                            onClick = {
                                modo = m
                                runCatching { prefs().edit().putString(MODO, m.palabra).apply() }
                            },
                            label = { Text(getString(rotulo)) }
                        )
                    }
                }
                Text(
                    getString(
                        when (modo) {
                            Presencial.Modo.PANTALLA -> R.string.clase_modo_pantalla_sub
                            Presencial.Modo.CAPTURAS -> R.string.clase_modo_capturas_sub
                            Presencial.Modo.PRESENCIAL -> R.string.clase_modo_presencial_sub
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
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
                if (modo == Presencial.Modo.PRESENCIAL) Reunion()
                Button(onClick = { empezar() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Text(getString(R.string.clase_empezar))
                }
                TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                    Text(getString(R.string.cancel))
                }
            }
        }
    }

    /**
     * **Lo de la presencial** (28-oct-2026): vídeo de la sala o solo audio, la calidad del vídeo y la
     * burbuja. Sin `return` antes de tiempo: es una composable en línea (R8).
     */
    @Composable
    private fun Reunion() {
        Text(getString(R.string.reunion_forma), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            for ((f, rotulo) in listOf(Presencial.Forma.AUDIO to R.string.reunion_forma_audio, Presencial.Forma.VIDEO to R.string.reunion_forma_video)) {
                androidx.compose.material3.FilterChip(
                    selected = forma == f,
                    onClick = { forma = f; runCatching { prefs().edit().putString(FORMA, f.clave).apply() } },
                    label = { Text(getString(rotulo)) }
                )
            }
        }
        Text(
            getString(if (forma == Presencial.Forma.VIDEO) R.string.reunion_forma_video_sub else R.string.reunion_forma_audio_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (forma == Presencial.Forma.VIDEO) {
            Text(getString(R.string.clase_calidad), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                for ((c, rotulo) in listOf(
                    Presencial.Calidad.ALTA to R.string.reunion_calidad_1080,
                    Presencial.Calidad.LIGERA to R.string.reunion_calidad_720,
                    Presencial.Calidad.AUTOMATICA to R.string.reunion_calidad_auto
                )) {
                    androidx.compose.material3.FilterChip(
                        selected = calidad == c,
                        onClick = { calidad = c; runCatching { prefs().edit().putString(CALIDAD, c.clave).apply() } },
                        label = { Text(getString(rotulo)) }
                    )
                }
            }
            Text(
                getString(
                    when (calidad) {
                        Presencial.Calidad.ALTA -> R.string.reunion_calidad_1080_pista
                        Presencial.Calidad.LIGERA -> R.string.reunion_calidad_720_pista
                        Presencial.Calidad.AUTOMATICA -> R.string.reunion_calidad_auto_pista
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(getString(R.string.reunion_burbuja), style = MaterialTheme.typography.bodyMedium)
                Text(
                    getString(R.string.reunion_burbuja_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = conBurbuja,
                onCheckedChange = { conBurbuja = it; runCatching { prefs().edit().putBoolean(BURBUJA, it).apply() } }
            )
        }
    }

    private fun empezar() {
        if (modo == Presencial.Modo.PRESENCIAL) return empezarLaReunion()
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
        if (GrabarClaseService.enMarcha || ReunionPresencialService.enMarcha) {
            Toast.makeText(this, R.string.clase_ya, Toast.LENGTH_SHORT).show()
        } else {
            // Ahora, con esta pantalla delante: es lo que deja al servicio quedarse el micrófono.
            runCatching { GrabarClaseService.empezar(this, proyecto, codigo, datos, perfil) }
                .onFailure { Toast.makeText(this, R.string.clase_no_arranca, Toast.LENGTH_LONG).show() }
        }
        finish()
    }

    /**
     * **La presencial**: la burbuja solo si se quiere (sin permiso de dibujar encima se va a los
     * ajustes, como la barra); luego el micrófono, la cámara y los avisos de una vez.
     */
    private fun empezarLaReunion() {
        if (conBurbuja && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_toast, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        val faltan = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot { tiene(it) }
        if (faltan.isEmpty()) arrancarLaReunion() else pedirLosDeLaReunion.launch(faltan.toTypedArray())
    }

    private fun arrancarLaReunion() {
        if (GrabarClaseService.enMarcha || ReunionPresencialService.enMarcha) {
            Toast.makeText(this, R.string.clase_ya, Toast.LENGTH_SHORT).show()
        } else {
            // Ahora, con esta pantalla delante: es lo que deja al servicio quedarse el micrófono y la cámara.
            runCatching { ReunionPresencialService.empezar(this, proyecto, forma, calidad, conBurbuja) }
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
        private const val MODO = "modo_de_grabar"
        /** La forma de la presencial, su calidad y la burbuja (28-oct-2026). */
        private const val FORMA = "forma_presencial"
        private const val CALIDAD = "calidad_presencial"
        private const val BURBUJA = "burbuja_presencial"

        /** Abre la preparación para grabar en el chat [proyecto] (null, el general). */
        fun abrir(context: Context, proyecto: String?) {
            context.startActivity(Intent(context, GrabarClaseActivity::class.java).putExtra(EL_PROYECTO, proyecto))
        }
    }
}
