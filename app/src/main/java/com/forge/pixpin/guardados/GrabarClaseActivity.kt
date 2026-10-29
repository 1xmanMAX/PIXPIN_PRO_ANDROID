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
import androidx.compose.foundation.verticalScroll
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
 * **Preparar la grabación** (25-oct-2026, el paso «Preparar» de `grabar_clase.rs` del PC): qué hace, dónde
 * se guarda y un botón grande para empezar.
 *
 * **Una sola opción** (29-oct-2026, el usuario: «solo debería haber una opción de grabar, que funcione para
 * los tres escenarios: la clase en línea, la presencial y la reunión»). Antes había tres modos («Audio y
 * capturas», «Pantalla y sonido» y «Presencial», con dos servicios distintos); ahora todo es
 * [ReunionPresencialService]: graba el audio siempre y, en su barra, Foto, Captura, **Pantalla** (el vídeo de
 * la pantalla, que se enciende y se apaga mientras el audio sigue) y Clip. Aquí solo queda si se empieza ya
 * grabando la pantalla (y su calidad), la burbuja y, en «Más opciones», el vídeo de la sala.
 *
 * Es una pantalla y no un botón directo en el chat por dos motivos de Android: los permisos (micrófono,
 * cámara, avisos y dibujar encima) se piden aquí sin tocar el chat, y **el servicio de micrófono y cámara
 * solo puede arrancar con la app a la vista** (Android 14+), que es justo lo que es esto.
 */
class GrabarClaseActivity : ComponentActivity() {

    private var proyecto: String? = null

    /** **Empezar grabando la pantalla** (se recuerda). Se puede encender y apagar luego en la barra. */
    private var conPantalla by androidx.compose.runtime.mutableStateOf(false)

    /** El vídeo de la sala con la cámara (en «Más opciones»): la reunión presencial grabada entera. */
    private var forma by androidx.compose.runtime.mutableStateOf(Presencial.Forma.AUDIO)
    private var calidad by androidx.compose.runtime.mutableStateOf(Presencial.Calidad.AUTOMATICA)
    private var conBurbuja by androidx.compose.runtime.mutableStateOf(true)
    private var masOpciones by androidx.compose.runtime.mutableStateOf(false)

    /**
     * **La calidad del vídeo de la pantalla** (25-oct-2026, como la ficha de Preparar del PC): «Ligero»,
     * «Normal» o «Nítido», con lo que pesa una hora debajo. Se recuerda la última (por su clave, la del PC).
     */
    private var perfil by androidx.compose.runtime.mutableStateOf(PerfilDeVideo.NORMAL)

    private fun prefs() = getSharedPreferences("grabar_clase", MODE_PRIVATE)

    /**
     * El permiso de grabar la pantalla al empezar (Android 14: un permiso, un `VirtualDisplay`). Sin él se
     * graba igual, sin la pantalla: no se pierde la clase por un «no».
     */
    private val pedirPantalla = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val datos = r.data
        if (r.resultCode == RESULT_OK && datos != null) arrancar(r.resultCode, datos)
        else {
            Toast.makeText(this, R.string.capture_consent_denied, Toast.LENGTH_SHORT).show()
            arrancar()
        }
    }

    /**
     * **Los permisos** (28-oct-2026): el micrófono (sin él no hay grabación), la cámara (sin ella, sin
     * fotos) y los avisos (Android 13: sin ellos la notificación con Foto, Captura y Terminar no se ve).
     */
    private val pedirLosPermisos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val microfono = r[Manifest.permission.RECORD_AUDIO] == true || tiene(Manifest.permission.RECORD_AUDIO)
        if (!microfono) {
            Toast.makeText(this, R.string.clase_sin_microfono, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (!tiene(Manifest.permission.CAMERA)) Toast.makeText(this, R.string.reunion_sin_camara_permiso, Toast.LENGTH_LONG).show()
        seguir()
    }

    private fun tiene(permiso: String) = ContextCompat.checkSelfPermission(this, permiso) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proyecto = intent.getStringExtra(EL_PROYECTO)
        // **Con una grabación en marcha, su pantalla** (28-oct-2026): los mandos en grande, en vez de un
        // «ya se está grabando» sin más. Ver [ReunionEnCursoActivity].
        if (ReunionPresencialService.enMarcha) {
            ReunionEnCursoActivity.abrir(this)
            finish()
            return
        }
        aPantallaCompleta()
        perfil = PerfilDeVideo.deClave(runCatching { prefs().getString(PERFIL, null) }.getOrNull())
        // Lo de antes: el modo «pantalla» del PC es empezar grabando la pantalla.
        conPantalla = runCatching {
            if (prefs().contains(CON_PANTALLA)) prefs().getBoolean(CON_PANTALLA, false)
            else Presencial.Modo.de(prefs().getString(MODO, null)) == Presencial.Modo.PANTALLA
        }.getOrDefault(false)
        forma = Presencial.Forma.de(runCatching { prefs().getString(FORMA, null) }.getOrNull())
        calidad = Presencial.Calidad.de(runCatching { prefs().getString(CALIDAD, null) }.getOrNull())
        conBurbuja = runCatching { prefs().getBoolean(BURBUJA, true) }.getOrDefault(true)
        masOpciones = forma == Presencial.Forma.VIDEO
        // **Los temas de la reunión** (29-oct-2026): los que esperaban a la próxima reunión, ya puestos.
        val anteriores = PrepararTemas.empezar(this)
        if (anteriores > 0) Toast.makeText(this, TextosDeLaReunion.anteriores(anteriores), Toast.LENGTH_SHORT).show()
        setContent { PixPinTheme { Pantalla() } }
    }

    @Composable
    private fun Pantalla() {
        val app = application as? com.forge.pixpin.PixPinApp
        val donde = proyecto?.let { p -> app?.proyectos?.porId(p)?.nombre }
            ?: if (proyecto == null) getString(R.string.clase_general) else null
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(getString(R.string.grabar_titulo), style = MaterialTheme.typography.headlineSmall)
                Text(
                    getString(R.string.grabar_sub),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    getString(R.string.grabar_explicacion),
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
                if (forma != Presencial.Forma.VIDEO) {
                    Interruptor(getString(R.string.grabar_con_pantalla), getString(R.string.grabar_con_pantalla_sub), conPantalla) {
                        conPantalla = it
                        runCatching { prefs().edit().putBoolean(CON_PANTALLA, it).apply() }
                    }
                    if (conPantalla) CalidadDeLaPantalla()
                }
                Interruptor(getString(R.string.reunion_burbuja), getString(R.string.reunion_burbuja_sub), conBurbuja) {
                    conBurbuja = it
                    runCatching { prefs().edit().putBoolean(BURBUJA, it).apply() }
                }
                // **Temas de la reunión** y la duración prevista (29-oct-2026, la fila de Preparar del PC).
                PrepararTemas.Fila(proyecto)
                TextButton(onClick = { masOpciones = !masOpciones }, modifier = Modifier.padding(top = 8.dp)) {
                    Text((if (masOpciones) "▾ " else "▸ ") + getString(R.string.grabar_mas_opciones))
                }
                if (masOpciones) Sala()
                Button(onClick = { empezar() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    Text(getString(R.string.clase_empezar))
                }
                TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                    Text(getString(R.string.cancel))
                }
            }
        }
    }

    /** Un interruptor con su nombre y lo que hace debajo, a lo ancho. */
    @Composable
    private fun Interruptor(nombre: String, sub: String, encendido: Boolean, alCambiar: (Boolean) -> Unit) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(nombre, style = MaterialTheme.typography.bodyLarge)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = encendido, onCheckedChange = alCambiar)
        }
    }

    /** La calidad del vídeo de la pantalla, con lo que pesa (en el teléfono no hay ratón que pase por encima). */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun CalidadDeLaPantalla() {
        Text(getString(R.string.clase_calidad), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        // En filas que se parten: en una pantalla estrecha no se aprietan las palabras.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        Text(
            getString(pistaDe(perfil)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }

    /**
     * **El vídeo de la sala** (28-oct-2026, la reunión presencial del PC): la cámara de atrás graba la
     * reunión entera, con su calidad. Sin `return` antes de tiempo: es una composable en línea (R8).
     */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun Sala() {
        Interruptor(getString(R.string.grabar_sala), getString(R.string.grabar_sala_sub), forma == Presencial.Forma.VIDEO) {
            forma = if (it) Presencial.Forma.VIDEO else Presencial.Forma.AUDIO
            runCatching { prefs().edit().putString(FORMA, forma.clave).apply() }
        }
        if (forma == Presencial.Forma.VIDEO) {
            Text(getString(R.string.clase_calidad), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
    }

    /**
     * **Empezar**: la burbuja solo si se quiere (sin permiso de dibujar encima se va a los ajustes); luego
     * el micrófono, la cámara y los avisos de una vez; y, si se empieza con la pantalla, su permiso.
     */
    private fun empezar() {
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
        if (faltan.isEmpty()) seguir() else pedirLosPermisos.launch(faltan.toTypedArray())
    }

    /** Con los permisos: el de la pantalla si se empieza con ella, o a grabar. */
    private fun seguir() {
        if (!conPantalla || forma == Presencial.Forma.VIDEO) return arrancar()
        val pedir = runCatching {
            getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent()
        }.getOrNull()
        if (pedir == null) arrancar() else pedirPantalla.launch(pedir)
    }

    private fun arrancar(codigo: Int = 0, datos: Intent? = null) {
        if (ReunionPresencialService.enMarcha) {
            Toast.makeText(this, R.string.clase_ya, Toast.LENGTH_SHORT).show()
        } else {
            // Ahora, con esta pantalla delante: es lo que deja al servicio quedarse el micrófono, la cámara y la pantalla.
            PrepararTemas.entregar()
            runCatching { ReunionPresencialService.empezar(this, proyecto, forma, calidad, conBurbuja, codigo, datos, perfil) }
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
        /** Empezar grabando la pantalla (29-oct-2026, la grabación única). */
        private const val CON_PANTALLA = "con_pantalla"
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
