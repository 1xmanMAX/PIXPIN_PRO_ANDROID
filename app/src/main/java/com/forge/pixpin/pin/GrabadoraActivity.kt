package com.forge.pixpin.pin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.R
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.Picos
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * **El micrófono flotante** (como el del PC, 3-oct-2026): una tarjetita abajo, encima de lo que
 * se esté haciendo, que graba nada más abrirse. Un toque en cualquier sitio para y guarda la
 * nota en el chat; atrás la tira. Guardada, ofrece **«Convertir en llamada»**: elegir a qué
 * hora te llama esa misma nota (la llamada secreta), sin abrir el chat.
 *
 * Fases, las del PC: grabando → guardando → guardada (se cierra sola a los 10 s si no se toca)
 * → hora → puesta («Listo: te llamará…»). Convertir no crea otro mensaje: pone la hora en la
 * nota recién guardada (`recuerdaEn`, que viaja) y apunta quién llama (el nombre de la nota, que
 * no viaja, como en el PC).
 *
 * ## Por qué una actividad y no la ventana flotante
 *
 * El micrófono no se puede abrir desde un servicio que arrancó en segundo plano —Android lo
 * prohíbe desde el 12—, y pedir el permiso es cosa de una actividad.
 */
class GrabadoraActivity : ComponentActivity() {

    private var grabador: com.forge.pixpin.audio.Grabador? = null
    private var destino: File? = null
    private var picos: Picos? = null

    private enum class Fase { GRABANDO, GUARDANDO, GUARDADA, HORA, PUESTA }

    private var fase by mutableStateOf(Fase.GRABANDO)
    private var empezadaEn by mutableLongStateOf(0L)
    private var nivel by mutableFloatStateOf(0f)
    private var guardado: Mensaje? = null
    private var puestaA by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pedirPermiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { si ->
            if (si) empezar() else salir(R.string.voz_sin_permiso)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) empezar()
        else pedirPermiso.launch(Manifest.permission.RECORD_AUDIO)
        setContent { PixPinTheme(cielo = false) { Pantalla() } }
    }

    @Composable
    private fun Pantalla() {
        BackHandler { if (fase == Fase.GRABANDO) tirar() else finish() }
        Box(
            Modifier.fillMaxSize().clickable(MutableInteractionSource(), null) {
                // Grabando, un toque en cualquier sitio para y guarda: sin tener que acertarle a un botón.
                if (fase == Fase.GRABANDO) terminar() else if (fase == Fase.GUARDADA || fase == Fase.PUESTA) finish()
            },
            contentAlignment = Alignment.BottomEnd
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 14.dp,
                modifier = Modifier.padding(16.dp).navigationBarsPadding().widthIn(max = 340.dp)
                    .clickable(MutableInteractionSource(), null) { if (fase == Fase.GRABANDO) terminar() }
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (fase) {
                        Fase.GRABANDO -> Grabando()
                        Fase.GUARDANDO -> Text("Guardando…")
                        Fase.GUARDADA -> Guardada()
                        Fase.HORA -> Hora()
                        Fase.PUESTA -> Text("Listo: te llamará $puestaA", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable
    private fun Grabando() {
        val latido = rememberInfiniteTransition(label = "latido")
        val escala by latido.animateFloat(1f, 1.18f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "escala")
        var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { ahora = System.currentTimeMillis(); delay(250) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).scale(escala).background(Color(0x33E5534B), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, null, tint = Color(0xFFE5534B), modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                val s = if (empezadaEn > 0) ((ahora - empezadaEn) / 1000).toInt() else 0
                Text(String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60), style = MaterialTheme.typography.titleLarge)
                // Las barras de nivel: lo que entra ahora mismo.
                Canvas(Modifier.width(150.dp).height(20.dp)) {
                    val n = 14
                    for (i in 0 until n) {
                        val alto = size.height * (0.15f + 0.85f * (nivel * (1f - kotlin.math.abs(i - n / 2f) / n)).coerceIn(0f, 1f))
                        val x = i * size.width / n
                        drawLine(Color(0xFFE5534B), Offset(x, (size.height - alto) / 2), Offset(x, (size.height + alto) / 2), strokeWidth = 5f)
                    }
                }
            }
        }
        Text("Toca para parar · Atrás la descarta", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun Guardada() {
        LaunchedEffect(Unit) { delay(10_000); if (fase == Fase.GUARDADA) finish() }
        Text("Nota de voz guardada", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { fase = Fase.HORA }) {
                Icon(Icons.Filled.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Convertir en llamada")
            }
            TextButton(onClick = { finish() }) { Text("Listo") }
        }
    }

    @Composable
    private fun Hora() {
        // Empieza una hora más tarde y en punto; nunca queda en el pasado.
        val inicio = remember { Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) } }
        var h by remember { mutableIntStateOf(inicio.get(Calendar.HOUR_OF_DAY)) }
        var m by remember { mutableIntStateOf(0) }
        fun cuando(): Long {
            val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        Text("¿A qué hora te llamo?", fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { poner(System.currentTimeMillis() + 15 * 60_000L) }, label = { Text("En 15 min") })
            AssistChip(onClick = { poner(System.currentTimeMillis() + 60 * 60_000L) }, label = { Text("En 1 h") })
            AssistChip(onClick = {
                poner(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis)
            }, label = { Text("Mañana 9:00") })
        }
        // Las flechas, como el PC: la hora de 1 en 1 y los minutos de 5 en 5.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { h = (h + 1) % 24 }) { Text("▲") }
                Text(String.format(java.util.Locale.ROOT, "%02d", h), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { h = (h + 23) % 24 }) { Text("▼") }
            }
            Text(":", style = MaterialTheme.typography.headlineMedium)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { m = (m + 5) % 60 }) { Text("▲") }
                Text(String.format(java.util.Locale.ROOT, "%02d", m), style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { m = (m + 55) % 60 }) { Text("▼") }
            }
        }
        Text("Te llamará: ${legible(cuando())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { poner(cuando()) }) { Text("Poner la llamada") }
            TextButton(onClick = { fase = Fase.GUARDADA }) { Text("Volver") }
        }
    }

    private fun legible(ms: Long): String {
        val hoy = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = ms }
        val hora = String.format(java.util.Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return if (hoy.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && hoy.get(Calendar.YEAR) == c.get(Calendar.YEAR)) "hoy a las $hora" else "mañana a las $hora"
    }

    /** Convierte la nota recién guardada en llamada: su hora (viaja) y quién llama (su nombre). */
    private fun poner(cuando: Long) {
        val m = guardado ?: return
        Recordatorios.poner(this, RecordatorioReceiver.DE_UN_MENSAJE + m.id, cuando)
        LlamadaSecretaActivity.ponerQuienLlama(this, m.id, m.nombre.removeSuffix(".m4a").ifBlank { null })
        Thread { MensajesStore(this).actualizar(m.id) { it.copy(recuerdaEn = cuando) } }.start()
        puestaA = legible(cuando)
        fase = Fase.PUESTA
        lifecycleScope.launch { delay(1600); finish() }
    }

    private fun empezar() {
        val almacen = MensajesStore(this)
        val archivo = File(almacen.carpetaDeAdjuntos(), "voz_${System.currentTimeMillis()}.m4a")
        destino = archivo
        grabador = Voz.empezar(this, archivo)
        if (grabador == null) { salir(R.string.voz_sin_microfono); return }
        empezadaEn = System.currentTimeMillis()
        val p = Picos(); picos = p
        lifecycleScope.launch {
            while (grabador != null) {
                val v = Voz.pico(grabador)
                p.anota(v)
                nivel = (v / 12000f).coerceIn(0f, 1f)
                delay(Voz.MS_ENTRE_PICOS)
            }
        }
    }

    private fun tirar() {
        Voz.parar(grabador); grabador = null
        destino?.delete(); destino = null
        finish()
    }

    /** Para y guarda la nota en el chat general, como la voz del chat. */
    private fun terminar() {
        fase = Fase.GUARDANDO
        val bien = Voz.parar(grabador)
        grabador = null
        val archivo = destino
        destino = null
        if (!bien || archivo == null || !archivo.exists() || Voz.duracion(archivo.absolutePath) < Voz.MINIMO_MS) {
            archivo?.delete()
            salir(R.string.voz_muy_corta)
            return
        }
        val ahora = System.currentTimeMillis()
        val m = Mensaje(
            id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.VOZ, ruta = archivo.absolutePath,
            nombre = "Nota de voz " + String.format(java.util.Locale.ROOT, "%tH:%<tM", ahora),
            duracionMs = Voz.duracion(archivo.absolutePath), picos = picos?.lista().orEmpty()
        )
        MensajesStore(this).anadir(m)
        guardado = m
        fase = Fase.GUARDADA
    }

    private fun salir(mensaje: Int) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Si la pantalla se va con la grabación en marcha, no se deja el micrófono cogido. */
    override fun onStop() {
        super.onStop()
        if (grabador != null) {
            Voz.parar(grabador); grabador = null
            destino?.delete(); destino = null
            finish()
        }
    }

    companion object {
        fun abrir(context: Context) = context.startActivity(
            Intent(context, GrabadoraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
