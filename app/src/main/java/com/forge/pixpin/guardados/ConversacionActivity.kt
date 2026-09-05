package com.forge.pixpin.guardados

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.forge.pixpin.R
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.ui.theme.PixPinTheme
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * **Una conversación de varias personas, grabada por turnos y transcrita con nombres.**
 *
 * Se dice cuántos hablan y cómo se llaman, y la pantalla se llena con un micrófono grande
 * por persona. Cada uno **mantiene pulsado el suyo mientras habla** y lo suelta al acabar:
 * así se sabe quién dijo cada cosa sin tener que adivinarlo por la voz, que es lo que ningún
 * reconocedor gratuito hace bien. Al terminar, cada turno se pasa a texto por su cuenta
 * ([Transcriptor]) y la nota queda como un diálogo —**Pepe:** …, **Juan:** …—, con el audio
 * entero (los turnos, seguidos) adjunto para poder volver a escucharlo. Lo pidió el usuario
 * (5-sep-2026).
 *
 * Se abre desde el clip del chat; en el chat de un proyecto, la conversación entra además
 * como hoja de notas del proyecto.
 */
class ConversacionActivity : ComponentActivity() {

    private var grabador: MediaRecorder? = null
    private var grabandoDesde = 0L

    /** Un turno: quién habló y el archivo de lo que dijo. */
    private class Turno(val quien: Int, val archivo: File, val ms: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val proyecto = intent.getStringExtra(EL_PROYECTO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        aPantallaCompleta()
        setContent { PixPinTheme { Pantalla(proyecto) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Voz.parar(grabador); grabador = null
    }

    @Composable
    private fun Pantalla(proyecto: String?) {
        var cuantos by remember { mutableIntStateOf(2) }
        val nombres = remember { mutableStateListOf("", "", "", "", "", "") }
        var paso by remember { mutableStateOf(Paso.PREPARAR) }
        val turnos = remember { mutableStateListOf<Turno>() }
        var hablando by remember { mutableIntStateOf(-1) }
        var avance by remember { mutableStateOf(0f) }

        // `Surface`, que pone el color del texto a juego con el fondo (ver [LetraActivity]).
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (paso) {
                Paso.PREPARAR -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(getString(R.string.conversacion_titulo), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        getString(R.string.conversacion_explicacion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(getString(R.string.conversacion_cuantos), Modifier.weight(1f))
                        OutlinedButton(onClick = { if (cuantos > 2) cuantos-- }) { Text("−") }
                        Text("$cuantos", Modifier.padding(horizontal = 14.dp), fontSize = 20.sp)
                        OutlinedButton(onClick = { if (cuantos < 6) cuantos++ }) { Text("+") }
                    }
                    Spacer(Modifier.height(10.dp))
                    for (i in 0 until cuantos) {
                        OutlinedTextField(
                            value = nombres[i],
                            onValueChange = { nombres[i] = it },
                            label = { Text(getString(R.string.conversacion_persona, i + 1)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { paso = Paso.GRABAR }, modifier = Modifier.fillMaxWidth()) {
                        Text(getString(R.string.conversacion_empezar))
                    }
                    TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.cancel)) }
                }

                Paso.GRABAR -> Column(Modifier.fillMaxSize()) {
                    // **Un micrófono por persona, a pantalla completa.** En dos columnas a
                    // partir de tres: con el teléfono en la mesa, cada uno alcanza el suyo.
                    val columnas = if (cuantos <= 2) 1 else 2
                    val filas = (cuantos + columnas - 1) / columnas
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        for (f in 0 until filas) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                for (c in 0 until columnas) {
                                    val i = f * columnas + c
                                    if (i >= cuantos) { Spacer(Modifier.weight(1f)); continue }
                                    Microfono(
                                        nombre = nombreDe(nombres, i),
                                        activo = hablando == i,
                                        otroActivo = hablando >= 0 && hablando != i,
                                        color = COLORES[i % COLORES.size],
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                        onEmpezar = {
                                            if (hablando < 0 && empezarTurno()) hablando = i
                                        },
                                        onSoltar = {
                                            if (hablando == i) {
                                                acabarTurno(i)?.let { turnos += it }
                                                hablando = -1
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            getString(R.string.conversacion_turnos, turnos.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            enabled = turnos.isNotEmpty() && hablando < 0,
                            onClick = {
                                paso = Paso.PROCESAR
                                lifecycleScope.launch {
                                    terminar(proyecto, nombres.take(cuantos).mapIndexed { i, n -> n.ifBlank { nombreDe(nombres, i) } }, turnos.toList()) { avance = it }
                                    finish()
                                }
                            }
                        ) { Text(getString(R.string.conversacion_terminar)) }
                    }
                }

                Paso.PROCESAR -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text(getString(R.string.conversacion_transcribiendo), Modifier.padding(top = 16.dp))
                    Text("${(avance * 100).toInt()} %", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
    }

    private fun nombreDe(nombres: List<String>, i: Int): String =
        nombres[i].trim().ifBlank { getString(R.string.conversacion_persona, i + 1) }

    /** El botón de una persona: se mantiene pulsado para hablar. */
    @Composable
    private fun Microfono(
        nombre: String, activo: Boolean, otroActivo: Boolean, color: Color, modifier: Modifier,
        onEmpezar: () -> Unit, onSoltar: () -> Unit
    ) {
        Column(
            modifier
                .padding(10.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(if (activo) color else color.copy(alpha = if (otroActivo) 0.25f else 0.55f))
                .pointerInput(nombre) {
                    detectTapGestures(
                        onPress = {
                            onEmpezar()
                            tryAwaitRelease()
                            onSoltar()
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(if (activo) 112.dp else 96.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = if (activo) 0.95f else 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = color, modifier = Modifier.size(56.dp))
            }
            Text(
                nombre, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                getString(if (activo) R.string.conversacion_hablando else R.string.conversacion_manten),
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp
            )
        }
    }

    private fun carpeta(): File = File(cacheDir, "conversacion").apply { mkdirs() }

    private fun empezarTurno(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val destino = File(carpeta(), "turno-${System.currentTimeMillis()}.m4a")
        grabador = Voz.empezar(this, destino) ?: return false
        grabandoDesde = System.currentTimeMillis()
        destino.deleteOnExit()
        turnoEnCurso = destino
        return true
    }

    private var turnoEnCurso: File? = null

    private fun acabarTurno(quien: Int): Turno? {
        val archivo = turnoEnCurso ?: return null
        val bien = Voz.parar(grabador)
        grabador = null
        turnoEnCurso = null
        val ms = (System.currentTimeMillis() - grabandoDesde).toInt()
        // Un toque sin querer no es un turno.
        if (!bien || ms < Voz.MINIMO_MS) { archivo.delete(); return null }
        return Turno(quien, archivo, ms)
    }

    /**
     * Al acabar: los turnos seguidos en un solo audio, cada turno a texto, y todo al chat
     * —el audio, la nota del diálogo— y, con proyecto, a sus hojas con el audio adjunto.
     */
    private suspend fun terminar(proyecto: String?, nombres: List<String>, turnos: List<Turno>, avance: (Float) -> Unit) {
        val almacen = MensajesStore(this)
        val ahora = System.currentTimeMillis()
        val juntos = File(carpeta(), "conversacion-$ahora.m4a")
        val unido = withContext(Dispatchers.IO) { unirM4a(turnos.map { it.archivo }, juntos) }
        // Cada turno, con su nombre y con el minuto en el que empieza dentro del audio
        // entero (los turnos van seguidos): así cada línea es un sitio al que saltar.
        val lineas = ArrayList<String>()
        var desdeMs = 0
        for ((i, t) in turnos.withIndex()) {
            val segmentos = transcribirUno(t.archivo)
            if (segmentos != null) {
                val nombre = nombres[t.quien]
                val corridos = segmentos.map { Transcriptor.Segmento(desdeMs + it.desdeMs, it.texto) }
                lineas += Transcriptor.conTiempos(corridos, prefijo = "**$nombre:** ")
            }
            desdeMs += t.ms
            avance((i + 1).toFloat() / turnos.size)
        }
        withContext(Dispatchers.IO) {
            val titulo = getString(R.string.conversacion_titulo)
            val cuerpo = lineas.joinToString("\n\n")
            val hoja = if (lineas.isEmpty()) null else almacen.apuntarTranscripcion(
                titulo = titulo, cuerpo = cuerpo, audio = if (unido) juntos else null, proyecto = proyecto, cuando = ahora
            )
            if (unido) {
                almacen.copiarAdjunto(juntos, "conversacion-$ahora.m4a")?.let { ruta ->
                    almacen.anadir(
                        Mensaje(
                            id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.VOZ, ruta = ruta,
                            duracionMs = turnos.sumOf { it.ms }, proyecto = proyecto,
                            transcripcion = cuerpo.ifBlank { null },
                            estadoDelTexto = when {
                                lineas.isEmpty() -> TEXTO_MAL
                                lineas.size < turnos.size -> TEXTO_CON_AVISOS
                                else -> TEXTO_BIEN
                            },
                            hojaDelTexto = hoja
                        ),
                        transcribir = false
                    )
                }
            }
            turnos.forEach { it.archivo.delete() }
            juntos.delete()
        }
    }

    private suspend fun transcribirUno(archivo: File): List<Transcriptor.Segmento>? = suspendCancellableCoroutine { cont ->
        if (!Transcriptor.disponible(this)) { cont.resume(null); return@suspendCancellableCoroutine }
        Transcriptor.transcribir(this, archivo) { r ->
            val t = r as? Transcriptor.Resultado.Texto
            cont.resume(t?.let { it.segmentos.ifEmpty { listOf(Transcriptor.Segmento(0, it.texto)) } })
        }
    }

    private enum class Paso { PREPARAR, GRABAR, PROCESAR }

    companion object {
        private const val EL_PROYECTO = "conv_proyecto"
        private val COLORES = listOf(
            Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047),
            Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00897B)
        )

        fun abrir(context: Context, proyecto: String?) {
            context.startActivity(
                Intent(context, ConversacionActivity::class.java)
                    .putExtra(EL_PROYECTO, proyecto)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * Varios `.m4a` grabados igual (mismo códec y frecuencia, ver [Voz.empezar]), uno
         * detrás de otro en un solo archivo, sin recodificar: se copian las muestras AAC
         * con el tiempo corrido. Devuelve si salió.
         */
        internal fun unirM4a(trozos: List<File>, destino: File): Boolean = runCatching {
            if (trozos.isEmpty()) return false
            val muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var pista = -1
            var desplazamientoUs = 0L
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var empezado = false
            for (t in trozos) {
                val ex = MediaExtractor()
                ex.setDataSource(t.absolutePath)
                var suya = -1
                var formato: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { suya = i; formato = f; break }
                }
                if (suya < 0 || formato == null) { ex.release(); continue }
                ex.selectTrack(suya)
                if (pista < 0) { pista = muxer.addTrack(formato); muxer.start(); empezado = true }
                var ultimoUs = 0L
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    info.offset = 0; info.size = n
                    info.presentationTimeUs = desplazamientoUs + ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(pista, buf, info)
                    ultimoUs = ex.sampleTime
                    ex.advance()
                }
                // Un cuadro AAC son 1024 muestras: el siguiente trozo empieza justo después.
                val muestreo = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                desplazamientoUs += ultimoUs + 1_024_000_000L / muestreo
                ex.release()
            }
            if (empezado) { muxer.stop() }
            muxer.release()
            empezado && destino.length() > 0
        }.getOrDefault(false)
    }
}
