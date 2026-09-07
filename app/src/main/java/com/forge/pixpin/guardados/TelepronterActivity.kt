package com.forge.pixpin.guardados

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.forge.pixpin.R
import com.forge.pixpin.pin.Voz
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **El teleprónter: un texto que baja solo mientras uno lo lee en voz alta, grabándose.**
 *
 * Es la nota de voz al revés. En una nota primero se habla y después sale el texto; aquí
 * el texto ya está —una nota del proyecto, una del chat, o algo pegado— y lo que se
 * graba es la lectura. Sirve para oírse: cómo suena, dónde se atasca uno, qué palabra
 * salió mal. Lo pidió el usuario (6-sep-2026).
 *
 * **El texto queda con sus minutos sin pasarlo por ningún reconocedor.** Como el texto
 * baja a una velocidad que se conoce, se sabe en qué segundo cruzó cada párrafo la línea
 * de lectura (la franja de color a un tercio de la pantalla), y ese es su minuto. Da
 * igual que se baje solo o que se empuje con el dedo: se mira dónde está, no a qué
 * velocidad va. Al terminar, la grabación entra en el chat como nota de voz con el texto
 * ya puesto por párrafos (`[1:23] …`), y se abre en la pantalla de la letra para seguirlo
 * mientras suena, como cualquier transcripción. Sin reconocedor: la voz de uno leyendo
 * un texto que ya se tiene no hay que adivinarla.
 *
 * Se puede **ensayar** sin grabar (el texto baja y ya), y mientras se graba se puede
 * parar el desplazamiento y seguir, o tirar la grabación.
 */
class TelepronterActivity : ComponentActivity() {

    private var grabador: android.media.MediaRecorder? = null
    private var archivo: File? = null
    private var picos: Picos? = null

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
        archivo?.delete(); archivo = null
    }

    /** Un texto para leer, de dónde viene y qué dice. */
    private class Fuente(val titulo: String, val texto: () -> String)

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun Pantalla(proyecto: String?) {
        val almacen = remember { MensajesStore(this) }
        val alcance = rememberCoroutineScope()
        var texto by rememberSaveable { mutableStateOf("") }
        val parrafos = remember(texto) { parrafosDe(texto) }
        var eligiendo by remember { mutableStateOf(false) }
        var escribiendo by remember { mutableStateOf(false) }
        var tamano by rememberSaveable { mutableIntStateOf(26) }
        // Cuánto baja por segundo, en dp: 40 es leer tranquilo; 90, deprisa.
        var velocidad by rememberSaveable { mutableFloatStateOf(45f) }
        var bajando by remember { mutableStateOf(false) }
        var grabando by remember { mutableStateOf(false) }
        var guardando by remember { mutableStateOf(false) }
        // **La cuenta atrás antes de grabar.** Se pulsaba «Grabar» y empezaba en el acto, con
        // uno todavía mirando el botón: los tres primeros segundos de todas las tomas eran
        // el silencio de colocarse. Tres, dos, uno y arranca.
        var cuenta by remember { mutableIntStateOf(0) }
        var inicio by remember { mutableLongStateOf(0L) }
        var transcurrido by remember { mutableLongStateOf(0L) }
        val scroll = rememberScrollState()
        val densidad = LocalDensity.current
        // Dónde empieza cada párrafo dentro de la columna (píxeles, sin desplazar) y en
        // qué milisegundo de la grabación cruzó la línea de lectura (-1: todavía no).
        val altos = remember(texto) { FloatArray(parrafos.size) }
        val tiempos = remember(texto) { IntArray(parrafos.size) { -1 } }
        var altoVisible by remember { mutableIntStateOf(0) }
        val lineaDeLectura = altoVisible * FRANJA

        LaunchedEffect(Unit) { if (texto.isBlank()) eligiendo = true }

        // **El texto baja solo**, un poco cada fotograma, hasta el final.
        LaunchedEffect(bajando) {
            if (!bajando) return@LaunchedEffect
            var antes = 0L
            while (bajando) {
                val ahora = withFrameNanos { it }
                if (antes != 0L) {
                    val segundos = (ahora - antes) / 1_000_000_000f
                    scroll.scrollBy(with(densidad) { velocidad.dp.toPx() } * segundos)
                    if (scroll.value >= scroll.maxValue) bajando = false
                }
                antes = ahora
            }
        }
        // **Los minutos de los párrafos**: mientras se graba, cada vez que el texto se
        // mueve —solo o con el dedo— el párrafo que acaba de cruzar la línea se apunta.
        LaunchedEffect(grabando, texto) {
            if (!grabando) return@LaunchedEffect
            snapshotFlow { scroll.value }.collect { desplazado ->
                val ms = (System.currentTimeMillis() - inicio).toInt()
                for (i in parrafos.indices) {
                    if (tiempos[i] < 0 && altos[i] - desplazado <= lineaDeLectura) tiempos[i] = ms
                }
            }
        }
        LaunchedEffect(grabando) {
            while (grabando) { transcurrido = System.currentTimeMillis() - inicio; delay(500) }
        }

        fun grabarYa() {
            if (parrafos.isEmpty()) { eligiendo = true; return }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
            }
            val destino = File(cacheDir, "telepronter-${System.currentTimeMillis()}.m4a")
            grabador = Voz.empezar(this, destino) ?: return
            archivo = destino
            val p = Picos(); picos = p
            alcance.launch { while (grabador != null) { p.anota(Voz.pico(grabador)); delay(Voz.MS_ENTRE_PICOS) } }
            inicio = System.currentTimeMillis()
            java.util.Arrays.fill(tiempos, -1)
            grabando = true
            bajando = true
        }
        fun empezar() {
            if (parrafos.isEmpty()) { eligiendo = true; return }
            cuenta = CUENTA_ATRAS
        }
        // La cuenta baja sola, un número por segundo escaso, y al llegar arranca.
        LaunchedEffect(cuenta) {
            if (cuenta <= 0) return@LaunchedEffect
            delay(900)
            if (cuenta > 1) cuenta -= 1 else { cuenta = 0; grabarYa() }
        }
        fun tirar() {
            bajando = false; grabando = false
            Voz.parar(grabador); grabador = null; picos = null
            archivo?.delete(); archivo = null
        }
        fun terminar() {
            bajando = false; grabando = false
            val listaDePicos = picos?.lista().orEmpty(); picos = null
            val bien = Voz.parar(grabador); grabador = null
            val f = archivo; archivo = null
            if (!bien || f == null || !f.exists()) { f?.delete(); return }
            val duracion = Voz.duracion(f.absolutePath)
            if (duracion < Voz.MINIMO_MS) { f.delete(); Toast.makeText(this, R.string.voz_muy_corta, Toast.LENGTH_SHORT).show(); return }
            // Lo que no llegó a cruzar la línea se queda con el final: se leyó, si acaso, al acabar.
            val cuerpo = parrafos.indices.joinToString("\n\n") { i ->
                val ms = tiempos[i].takeIf { it >= 0 } ?: duracion
                "[" + Transcriptor.marcaDeTiempo(ms.coerceAtMost(duracion)) + "] " + parrafos[i]
            }
            guardando = true
            alcance.launch {
                val id = withContext(Dispatchers.IO) {
                    val ahora = System.currentTimeMillis()
                    val titulo = getString(R.string.telepronter_nota)
                    val hoja = almacen.apuntarTranscripcion(titulo, cuerpo, f, proyecto, ahora)
                    val ruta = almacen.copiarAdjunto(f, "lectura-$ahora.m4a") ?: return@withContext null
                    f.delete()
                    val id = UUID.randomUUID().toString()
                    almacen.anadir(
                        Mensaje(
                            id = id, cuando = ahora, clase = Clase.VOZ, ruta = ruta, duracionMs = duracion,
                            picos = listaDePicos, proyecto = proyecto, transcripcion = cuerpo,
                            estadoDelTexto = TEXTO_BIEN, hojaDelTexto = hoja
                        ),
                        transcribir = false
                    )
                    id
                }
                guardando = false
                if (id != null) {
                    Toast.makeText(this@TelepronterActivity, R.string.telepronter_guardado, Toast.LENGTH_LONG).show()
                    LetraActivity.abrir(this@TelepronterActivity, id)
                    finish()
                }
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { tirar(); finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.cancel)) }
                    Text(
                        if (grabando) "● " + Transcriptor.marcaDeTiempo(transcurrido.toInt()) else getString(R.string.telepronter_titulo),
                        fontWeight = FontWeight.Bold,
                        color = if (grabando) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f), maxLines = 1
                    )
                    IconButton(onClick = { if (tamano > 16) tamano -= 2 }) { Icon(Icons.Filled.TextDecrease, contentDescription = null) }
                    IconButton(onClick = { if (tamano < 48) tamano += 2 }) { Icon(Icons.Filled.TextIncrease, contentDescription = null) }
                    IconButton(enabled = !grabando, onClick = { eligiendo = true }) { Icon(Icons.Filled.Description, contentDescription = getString(R.string.telepronter_elegir)) }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onGloballyPositioned { altoVisible = it.size.height }
                ) {
                    if (parrafos.isEmpty()) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(getString(R.string.telepronter_sin_texto), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { eligiendo = true }) { Text(getString(R.string.telepronter_elegir)) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            // Aire arriba: el primer párrafo empieza en la línea de lectura, no pegado al borde.
                            Spacer(Modifier.height(with(densidad) { (altoVisible * FRANJA).toDp() }))
                            // **El que va por la línea, más marcado.** Es lo que hace que el
                            // ojo no se pierda al levantar la vista un momento.
                            val enLaLinea = parrafos.indices.lastOrNull { altos[it] - scroll.value <= lineaDeLectura } ?: -1
                            for ((i, p) in parrafos.withIndex()) {
                                val suyo = i == enLaLinea
                                Text(
                                    p,
                                    fontSize = tamano.sp,
                                    lineHeight = (tamano * 1.45f).sp,
                                    fontWeight = if (suyo) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (suyo) MaterialTheme.colorScheme.onBackground
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                        .onGloballyPositioned { altos[i] = it.positionInParent().y }
                                        .padding(horizontal = 24.dp, vertical = 10.dp)
                                )
                            }
                            // Y aire abajo: el último también llega a la línea.
                            Spacer(Modifier.height(with(densidad) { (altoVisible * (1f - FRANJA)).toDp() }))
                        }
                        // **La línea de lectura**: una franja a un tercio, que es donde van los ojos.
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(top = (with(densidad) { (altoVisible * FRANJA).toDp() } - 2.dp).coerceAtLeast(0.dp))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        )
                    }
                }
                // **Los mandos.** Cuánto queda, a qué velocidad va, y qué se hace ahora.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    // **Cuánto texto queda.** Sin esto uno lee a ciegas: no se sabe si va por
                    // la mitad o le queda un tercio, y con una toma en marcha eso importa.
                    if (parrafos.isNotEmpty()) {
                        val cuanto = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
                        LinearProgressIndicator(
                            progress = { cuanto.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }
                    // **La velocidad, dicha con palabras.** Un «45» no significa nada; y se
                    // ajusta con dos botones, que es lo que se puede tocar sin mirar mientras
                    // se lee. El deslizador se queda para afinar.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { velocidad = (velocidad - 10f).coerceAtLeast(10f) }) {
                            Icon(Icons.Filled.Remove, contentDescription = getString(R.string.telepronter_mas_lento))
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                getString(R.string.telepronter_velocidad) + ": " + nombreDeLaVelocidad(velocidad),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Slider(
                                value = velocidad, onValueChange = { velocidad = it },
                                valueRange = 10f..150f, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(onClick = { velocidad = (velocidad + 10f).coerceAtMost(150f) }) {
                            Icon(Icons.Filled.Add, contentDescription = getString(R.string.telepronter_mas_rapido))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { bajando = !bajando },
                            enabled = parrafos.isNotEmpty() && cuenta == 0,
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(if (bajando) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                            Text(
                                getString(
                                    if (bajando) R.string.telepronter_pausa
                                    else if (grabando) R.string.telepronter_seguir
                                    else R.string.telepronter_ensayar
                                ),
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // **Un solo botón grande manda.** Es el que se busca sin leer: graba,
                        // y mientras graba, termina.
                        if (!grabando) {
                            Button(
                                onClick = { empezar() },
                                enabled = parrafos.isNotEmpty() && !guardando && cuenta == 0,
                                modifier = Modifier.weight(1f).height(52.dp)
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = null)
                                Text(getString(R.string.telepronter_grabar), modifier = Modifier.padding(start = 8.dp))
                            }
                        } else {
                            Button(
                                onClick = { terminar() },
                                modifier = Modifier.weight(1f).height(52.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                                Text(getString(R.string.telepronter_terminar), modifier = Modifier.padding(start = 8.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { tirar() }) {
                                Icon(Icons.Filled.Delete, contentDescription = getString(R.string.telepronter_tirar), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    // Lo que va a pasar al pulsar, dicho una vez y en pequeño.
                    Text(
                        getString(
                            if (grabando) R.string.telepronter_pista_grabando
                            else R.string.telepronter_pista
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // **Tres, dos, uno.** Encima de todo y a pantalla completa: es lo único que hay que
        // mirar mientras se cuenta, y da tiempo a colocarse antes de que corra la cinta.
        if (cuenta > 0) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        cuenta.toString(),
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        getString(R.string.telepronter_preparate),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    TextButton(onClick = { cuenta = 0 }) {
                        Text(getString(R.string.cancel), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        if (eligiendo) {
            val fuentes = remember { fuentes(almacen, proyecto) }
            ModalBottomSheet(onDismissRequest = { eligiendo = false }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                    Text(getString(R.string.telepronter_elegir), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    Text(
                        getString(R.string.telepronter_escribir), fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { eligiendo = false; escribiendo = true }.padding(horizontal = 24.dp, vertical = 14.dp)
                    )
                    for ((grupo, lista) in fuentes) {
                        if (lista.isEmpty()) continue
                        Text(grupo, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                        for (f in lista) {
                            Text(
                                f.titulo, fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    eligiendo = false
                                    alcance.launch { texto = withContext(Dispatchers.IO) { runCatching { f.texto() }.getOrDefault("") }; scroll.scrollTo(0) }
                                }.padding(horizontal = 24.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
        if (escribiendo) {
            var borrador by remember { mutableStateOf(texto) }
            AlertDialog(
                onDismissRequest = { escribiendo = false },
                title = { Text(getString(R.string.telepronter_escribir)) },
                text = {
                    OutlinedTextField(
                        value = borrador, onValueChange = { borrador = it },
                        placeholder = { Text(getString(R.string.telepronter_texto_pegar)) },
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    )
                },
                confirmButton = { TextButton(onClick = { texto = borrador; escribiendo = false; alcance.launch { scroll.scrollTo(0) } }) { Text(getString(R.string.telepronter_listo)) } },
                dismissButton = { TextButton(onClick = { escribiendo = false }) { Text(getString(R.string.cancel)) } }
            )
        }
    }

    /**
     * De dónde se puede sacar el texto: las notas del proyecto (o de todos, en el chat
     * general) y las notas del chat, escritas o como archivo `.md`/`.txt`.
     */
    private fun fuentes(almacen: MensajesStore, proyecto: String?): List<Pair<String, List<Fuente>>> {
        val app = application as? com.forge.pixpin.PixPinApp
        val proyectos = app?.proyectos?.proyectos?.value.orEmpty().filter { proyecto == null || it.id == proyecto }
        val delProyecto = proyectos.flatMap { p ->
            p.hojas.filter { !it.nota.isNullOrBlank() }.map { h ->
                val nombre = h.nombre.ifBlank { h.nota!!.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('#', ' ').orEmpty() }
                Fuente(if (proyecto == null) p.nombre + " · " + nombre else nombre) { h.nota.orEmpty() }
            }
        }
        val delChat = almacen.leer().filter { it.proyecto == proyecto }.mapNotNull { m ->
            when {
                m.clase == Clase.NOTA && m.texto.isNotBlank() -> Fuente(m.texto.lineSequence().first().take(60)) { m.texto }
                m.clase == Clase.ARCHIVO && m.ruta != null && (m.nombre.endsWith(".md", true) || m.nombre.endsWith(".txt", true)) ->
                    Fuente(m.nombre) { File(m.ruta).readText() }
                else -> null
            }
        }.asReversed()
        return listOf(getString(R.string.telepronter_notas_del_proyecto) to delProyecto, getString(R.string.telepronter_notas_del_chat) to delChat)
    }

    companion object {
        private const val EL_PROYECTO = "telepronter_proyecto"
        /** A qué altura de la pantalla está la línea de lectura (fracción desde arriba). */
        private const val FRANJA = 0.3f

        fun abrir(context: Context, proyecto: String?) {
            context.startActivity(
                Intent(context, TelepronterActivity::class.java)
                    .putExtra(EL_PROYECTO, proyecto)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        /**
         * El texto en párrafos para leer: sin las marcas de Markdown que no se leen (`#`,
         * `**`, imágenes, audios) y partido por líneas en blanco. Puro, para probarlo.
         */
        internal fun parrafosDe(texto: String): List<String> =
            texto.split(Regex("\n\\s*\n")).mapNotNull { bloque ->
                val limpio = bloque.lines()
                    .filterNot { it.trimStart().startsWith("![") }
                    .joinToString(" ") { it.trimStart('#', ' ', '>', '-', '*').replace("**", "").replace("__", "").replace("`", "").trim() }
                    .trim()
                limpio.ifBlank { null }
            }
    }
}

/** La velocidad dicha con palabras: un número de píxeles por segundo no significa nada. */
private fun nombreDeLaVelocidad(v: Float): String = when {
    v < 30f -> "muy lenta"
    v < 55f -> "lenta"
    v < 85f -> "normal"
    v < 115f -> "rápida"
    else -> "muy rápida"
}

/** Desde cuánto cuenta antes de arrancar la grabación. */
private const val CUENTA_ATRAS = 3
