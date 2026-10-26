package com.forge.pixpin.ocr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.data.OCR_INDRA
import com.forge.pixpin.data.OCR_NINGUNO
import com.forge.pixpin.ocr.indra.MotorIndra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** La licencia de los pesos de Indra (la misma página que abre el PC, `LICENCIA_INDRA` de `ventana_ajustes.rs`). */
const val LICENCIA_INDRA = "https://huggingface.co/datalab-to/indra-ocr/blob/main/LICENSE"

/**
 * **Ajustes › Capturar › Leer texto (OCR)** (26-oct-2026, la sección `[ocr]` del PC): sin lector
 * (de fábrica, como el PC trae el de Windows) o Indra, que **se baja al elegirlo** (26 MB, con su
 * sha256) y nunca solo; la licencia dicha antes y con su enlace; «Corregir con el diccionario» y
 * «Borrar Indra». Los textos son los del PC (`ajustes2-…-ocr`), con lo que cambia en el teléfono.
 */
@Composable
fun MotorOcrCard() {
    val context = LocalContext.current
    val app = context.applicationContext as PixPinApp
    val scope = rememberCoroutineScope()
    val ajustes by app.settings.settings.collectAsState(initial = app.ajustes)
    val descarga by MotorIndra.Descarga.estado.collectAsState()
    var bajado by remember { mutableStateOf(false) }
    LaunchedEffect(descarga.vuelta) { bajado = withContext(Dispatchers.IO) { MotorIndra.listo(MotorIndra.carpeta(context)) } }
    val indra = ajustes.ocrMotor == OCR_INDRA

    fun elegir(motor: String) {
        scope.launch { app.settings.setOcrMotor(motor) }
        if (motor == OCR_INDRA) {
            // Elegir Indra es lo que empieza la descarga (opt-in, por la licencia).
            if (!bajado) MotorIndra.Descarga.empezar(context)
        } else {
            MotorIndra.Descarga.cancelar()
            MotorIndra.soltar()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Leer texto de las imágenes (OCR)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Lo usan «Texto» al capturar, «Copiar texto» de un pin de imagen y el buscador de la galería. " +
                    "Indra (Datalab) lee capturas, fotos, escaneos y páginas torcidas en el teléfono y sin red; al " +
                    "elegirlo se bajan ${CatalogoIndra.megas()} MB. Sus pesos tienen licencia OpenRAIL-M de Datalab: " +
                    "gratis para uso personal, investigación y empresas de menos de 20 M$; para otro uso comercial hace falta su licencia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !indra, onClick = { elegir(OCR_NINGUNO) }, label = { Text("Sin lector") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = indra, onClick = { elegir(OCR_INDRA) }, label = { Text("Indra (${CatalogoIndra.megas()} MB)") })
            }
            val estado = when {
                descarga.bajando -> "Bajando Indra… ${(descarga.avance * 100).toInt()} %."
                indra && bajado -> "Indra está bajado y lee el texto de la app, sin red."
                indra && descarga.fallo != null -> "No se pudo bajar Indra: ${descarga.fallo}. Vuelve a elegirlo o toca «Bajar» para reintentar."
                indra -> "Indra no está bajado: hasta que lo esté, no hay lector de texto."
                bajado -> "Sin lector. Indra sigue bajado: elígelo para usarlo, o bórralo abajo."
                else -> "Sin lector: el teléfono no trae OCR. Las fotos del chat las sigue leyendo la visión del asistente."
            }
            Text(estado, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            if (descarga.bajando) {
                LinearProgressIndicator(progress = { descarga.avance }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (indra && !bajado && !descarga.bajando) TextButton(onClick = { MotorIndra.Descarga.empezar(context) }) { Text("Bajar") }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENCIA_INDRA)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Licencia de Indra") }
                if (bajado || descarga.bajando) TextButton(onClick = {
                    scope.launch { app.settings.setOcrMotor(OCR_NINGUNO) }
                    MotorIndra.Descarga.borrar(context)
                }) { Text("Borrar Indra") }
            }
            Text(
                "El código de Indra es Apache 2.0; los pesos, OpenRAIL-M modificada de Datalab. PixPin no los incluye: " +
                    "los bajas tú desde Hugging Face al elegir Indra. «Borrar Indra» quita sus ${CatalogoIndra.megas()} MB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Corregir con el diccionario", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Solo con Indra y en páginas en español: arregla tildes y letras mal leídas con poca confianza. " +
                            "Nunca toca cifras, códigos ni nombres.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = ajustes.ocrCorregir, onCheckedChange = { si -> scope.launch { app.settings.setOcrCorregir(si) } })
            }
        }
    }
}
