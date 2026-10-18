package com.forge.pixpin.mini

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.pin.RecordatoriosDelHorario
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.ui.theme.BotonRedondo
import com.forge.pixpin.ui.theme.Cristal
import com.forge.pixpin.ui.theme.PixPinTheme
import com.forge.pixpin.ui.theme.cristal
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **El horario de clases** (PC `bfdd89a`, `horario.rs`): una nota de texto, una línea por clase
 * («Lun Mie 8:00-10:00 Geometría @A-12»), sin formularios; debajo, lo que se ha entendido de ella,
 * para ver en seguida si una línea no se lee. Al guardar se ponen sus llamadas
 * ([RecordatoriosDelHorario]). «Nota de voz para la próxima clase» graba lo que tiene que sonar al
 * contestar la llamada de la próxima sesión de la clase en marcha (o la última, o la próxima).
 *
 * El texto es `files/horario.md`, el mismo formato que el `horario.md` del PC. No viaja.
 */
class HorarioActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixPinTheme { Pantalla() } }
    }

    private var grabador: com.forge.pixpin.audio.Grabador? = null
    private var grabandoEn: File? = null

    override fun onDestroy() {
        // Salir grabando no deja el micrófono cogido ni una nota a medias.
        if (grabador != null) { Voz.parar(grabador); grabandoEn?.delete() }
        grabador = null
        super.onDestroy()
    }

    @Composable
    private fun Pantalla() {
        val ruta = remember { Horario.ruta(filesDir) }
        var texto by remember { mutableStateOf<String?>(null) }
        var guardado by remember { mutableStateOf("") }
        var grabando by remember { mutableStateOf<String?>(null) }
        val alcance = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            val t = withContext(Dispatchers.IO) { runCatching { ruta.takeIf { it.isFile }?.readText() }.getOrNull() } ?: Horario.PLANTILLA
            guardado = t; texto = t
        }
        val leido = remember(texto) { texto?.let { Horario.leer(it, Momento.ahora()) } }

        fun guardar(salir: Boolean) {
            val t = texto ?: return
            val ctx = applicationContext
            alcance.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ruta.writeText(t); RecordatoriosDelHorario.reprogramar(ctx) }.isSuccess
                }
                if (ok) guardado = t
                Toast.makeText(ctx, if (ok) "Horario guardado: te llamará antes de cada clase" else "No se pudo guardar el horario", Toast.LENGTH_SHORT).show()
                if (salir && ok) finish()
            }
        }

        fun grabarOParar() {
            val g = grabador
            if (g != null) {
                val ok = Voz.parar(g)
                grabador = null
                val clase = grabando
                grabando = null
                val f = grabandoEn ?: return
                grabandoEn = null
                if (!ok) { f.delete(); return }
                // La nota nueva pisa la de antes de esa clase (una por asignatura, como el PC).
                val destino = File(Horario.carpetaDeVoz(filesDir), Horario.ficheroDeVoz(clase.orEmpty()))
                val bien = runCatching { destino.parentFile?.mkdirs(); f.copyTo(destino, overwrite = true); f.delete() }.isSuccess
                Toast.makeText(this, if (bien) "Se oirá en la próxima clase de $clase" else "No se pudo guardar la nota", Toast.LENGTH_SHORT).show()
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return
            }
            val h = leido ?: return
            val ahora = System.currentTimeMillis().let { it + java.util.TimeZone.getDefault().getOffset(it) }
            val i = Horario.claseParaGrabar(h, ahora)
            if (i == null) {
                Toast.makeText(this, "Aún no hay clases en el horario: escribe una por línea y guárdalo.", Toast.LENGTH_LONG).show()
                return
            }
            val f = File(cacheDir, "horario-grabando.m4a").also { it.delete() }
            val nuevo = Voz.empezar(this, f)
            if (nuevo == null) { Toast.makeText(this, "No se puede grabar ahora (¿otra app usa el micrófono?)", Toast.LENGTH_SHORT).show(); return }
            grabador = nuevo; grabandoEn = f; grabando = h.clases[i].nombre
        }

        BackHandler { if (texto != null && texto != guardado) guardar(salir = true) else finish() }

        Scaffold(containerColor = Color.Transparent) { relleno ->
            Column(Modifier.fillMaxSize().padding(relleno).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotonRedondo(Icons.AutoMirrored.Filled.ArrowBack, "Volver", {
                        if (texto != null && texto != guardado) guardar(salir = true) else finish()
                    }, tamano = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Horario de clases", style = MaterialTheme.typography.titleLarge, color = Cristal.tinta, fontWeight = FontWeight.SemiBold)
                        Text("Te llama antes de cada clase", style = MaterialTheme.typography.labelMedium, color = Cristal.tinta.copy(alpha = 0.7f))
                    }
                    TextButton(onClick = { guardar(salir = false) }, enabled = texto != null && texto != guardado) { Text("Guardar") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val t = texto
                    if (t != null) OutlinedTextField(
                        value = t, onValueChange = { texto = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = Cristal.tinta)
                    )
                    // La nota de voz para la próxima clase.
                    FilledTonalButton(onClick = { grabarOParar() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (grabando != null) Icons.Filled.Stop else Icons.Filled.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (grabando != null) "Parar · nota para $grabando" else "Nota de voz para la próxima clase")
                    }
                    // Lo que se ha entendido.
                    val h = leido
                    if (h != null) Column(Modifier.fillMaxWidth().cristal().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (h.clases.isEmpty()) "Aún no hay clases: escribe una por línea, como «Lun Mie 8:00-10:00 Geometría @A-12»."
                            else "Llama ${h.antelacion} min antes de cada clase",
                            color = Cristal.tinta, style = MaterialTheme.typography.titleSmall
                        )
                        h.clases.forEach { c ->
                            Text("${c.nombre} · ${Horario.resumenDe(c)}", color = Cristal.tinta, style = MaterialTheme.typography.bodyMedium)
                            if (c.notas.isNotEmpty()) Text("> " + c.notas.joinToString(". "), color = Cristal.tinta.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                        val ahora = Momento.ahora()
                        h.evaluaciones.forEach { e ->
                            Text(
                                "📝 ${e.que} · ${e.cuando.legible(ahora)} (avisa ${h.diasAntes.joinToString(" y ")} días antes)",
                                color = Cristal.tinta, style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    companion object {
        fun abrir(context: Context) = context.startActivity(
            Intent(context, HorarioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
