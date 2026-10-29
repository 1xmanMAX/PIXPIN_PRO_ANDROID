package com.forge.pixpin.guardados

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.forge.pixpin.R
import com.forge.pixpin.ui.theme.PixPinTheme

/**
 * **La pantalla completa de la grabación** (28-oct-2026 como pantalla de la reunión presencial; rehecha el
 * 29-oct-2026, el usuario: «que en pantalla completa me aparezca lo que voy a decir, la lista, y en la zona
 * de arriba las opciones, y abajo la transcripción, de forma que sea más grande, para personas que quieran
 * verlo grande»).
 *
 * - **Arriba, los mandos en grande**: el reloj (un toque pausa, mantenerlo termina), «Terminar», y Foto,
 *   Captura, Pantalla y Clip ([MandosDeLaGrabacion]). «Captura» sale de esta pantalla y captura lo de
 *   debajo. Con la cámara abierta, «Ver la cámara» enseña la mirilla (y grabando un clip, siempre).
 * - **En medio, lo que voy a decir**: los temas con su casilla ([TemasEnLaBarra.ListaGrande]).
 * - **Abajo, lo que se dice**: la transcripción en vivo, frase a frase con su minuto
 *   ([ReunionConJev.Estado.frases]), siguiendo la última.
 * - **A− / A+** agrandan todo el texto (se recuerda) y «Descartar» borra la grabación, con su pregunta.
 *
 * La abre tocar la notificación, el ⛶ de la burbuja o de la barra de la app. Atrás no para nada: la
 * grabación sigue con la burbuja y la notificación. Al terminar, se va sola. Es solo un mando: todo lo
 * hace [ReunionPresencialService] (mismo proceso).
 */
class ReunionEnCursoActivity : ComponentActivity() {

    private fun prefs() = getSharedPreferences("grabar_clase", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ReunionPresencialService.enMarcha) {
            Toast.makeText(this, R.string.reunion_ya_no, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // De borde a borde y negra: los iconos de arriba, claros.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { PixPinTheme(darkTheme = true) { Pantalla() } }
    }

    @Composable
    private fun Pantalla() {
        val e by ReunionPresencialService.estado.collectAsState()
        val jev by ReunionConJev.estado.collectAsState()
        var escala by remember { mutableFloatStateOf(runCatching { prefs().getFloat(ESCALA, 1.2f) }.getOrDefault(1.2f)) }
        var verCamara by remember { mutableStateOf(false) }
        var preguntar by remember { mutableStateOf(false) }
        fun cambiarEscala(d: Float) {
            escala = (escala + d).coerceIn(0.9f, 2.4f)
            runCatching { prefs().edit().putFloat(ESCALA, escala).apply() }
        }
        // Terminada (o parada por el sistema): fuera.
        LaunchedEffect(e.lista, e.terminando, e.guardada) {
            if (e.terminando || e.guardada != null || (!e.lista && !ReunionPresencialService.enMarcha)) finish()
        }
        val claro = Color.White
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            // --- Arriba: volver, el tamaño y descartar ---
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, getString(R.string.cancel), tint = claro) }
                Text(getString(R.string.reunion_en_curso), color = claro, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { cambiarEscala(-0.15f) }) { Text("A−", color = claro, fontSize = 16.sp) }
                TextButton(onClick = { cambiarEscala(0.15f) }) { Text("A+", color = claro, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { preguntar = true }) { Text(getString(R.string.grabar_descartar), color = Color(0xFFFF8A80)) }
            }
            // --- Los mandos en grande ---
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MandosDeLaGrabacion.Reloj(e, claro, grande = true, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { ReunionPresencialService.terminar() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White)
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.size(6.dp)); Text(getString(R.string.clase_terminar)) }
            }
            Text(
                getString(R.string.grabar_reloj_pista), color = claro.copy(alpha = 0.55f), fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                MandosDeLaGrabacion.Acciones(e, claro, grande = true, alCapturar = { zona ->
                    // Se va esta pantalla y se captura lo de debajo.
                    ReunionPresencialService.captura(zona, retrasoMs = 700)
                    finish()
                })
            }
            if (e.aviso != null || e.fotosEnCurso > 0) Text(
                listOfNotNull(e.aviso, getString(R.string.reunion_foto_en_curso).takeIf { e.fotosEnCurso > 0 }).joinToString(" · "),
                color = claro.copy(alpha = 0.8f), fontSize = (13 * escala).sp, maxLines = 2,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // La cámara: con un clip, siempre; si no, si se pide (y si ya está abierta).
            if (e.mirilla && (verCamara || e.clipDesde != null)) {
                MirillaDeLaReunion(
                    e, Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                    conProveedor = { ReunionPresencialService.mirillaEnLaApp(it) }
                )
            }
            if (e.conCamara && e.clipDesde == null) TextButton(
                onClick = { verCamara = !verCamara; if (verCamara) ReunionPresencialService.abrirLaCamara() },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text((if (verCamara) "▾ " else "▸ ") + getString(R.string.grabar_ver_camara), color = claro.copy(alpha = 0.8f))
            }
            Destello(e.destellos, Modifier.size(1.dp))
            // --- En medio: lo que voy a decir ---
            Titulo(getString(R.string.grabar_temas), escala)
            TemasEnLaBarra.ListaGrande(
                escala, getString(R.string.grabar_temas_vacios),
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
            )
            // --- Abajo: lo que se dice ---
            Titulo(getString(R.string.grabar_transcripcion), escala)
            Transcripcion(jev.frases, jev.parcial, escala, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp))
            // **Colaboradores** (28-oct-2026, §14 de la guía del PC): los del chat compartido aportan fotos que
            // entran en su minuto; y lo que manda el PC a esta grabación (§13).
            val todos by com.forge.pixpin.sincro.GrabacionDelMovil.abiertaATodos.collectAsState()
            val delPc by com.forge.pixpin.sincro.GrabacionDelMovil.delPc.collectAsState()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    getString(R.string.clase_todos_aportan) +
                        (if (delPc > 0) " · " + resources.getQuantityString(R.plurals.reunion_capturas_del_pc, delPc, delPc) else ""),
                    color = claro.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(checked = todos, onCheckedChange = { com.forge.pixpin.sincro.GrabacionDelMovil.abiertaATodos.value = it })
            }
        }
        if (preguntar) AlertDialog(
            onDismissRequest = { preguntar = false },
            text = { Text(getString(R.string.grabar_descartar_pregunta)) },
            confirmButton = {
                TextButton(onClick = { preguntar = false; ReunionPresencialService.descartar() }) {
                    Text(getString(R.string.grabar_descartar), color = Color(0xFFFF5252))
                }
            },
            dismissButton = { TextButton(onClick = { preguntar = false }) { Text(getString(R.string.cancel)) } }
        )
    }

    @Composable
    private fun Titulo(texto: String, escala: Float) {
        Text(
            texto, color = Color(0xFF90CAF9), fontSize = (13 * escala).sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
        )
    }

    /** **Lo que se dice**: las frases con su minuto, siguiendo la última, y lo que se oye ahora en cursiva. */
    @Composable
    private fun Transcripcion(frases: List<Pair<Long, String>>, parcial: String, escala: Float, modifier: Modifier) {
        val lista = rememberLazyListState()
        LaunchedEffect(frases.size, parcial.isNotBlank()) {
            val total = frases.size + (if (parcial.isNotBlank()) 1 else 0)
            if (total > 0) lista.animateScrollToItem(total - 1)
        }
        if (frases.isEmpty() && parcial.isBlank()) Text(
            getString(R.string.grabar_transcripcion_vacia), color = Color.White.copy(alpha = 0.55f), fontSize = (13 * escala).sp, modifier = modifier
        ) else LazyColumn(modifier, state = lista, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(frases.size) { i ->
                val (ms, texto) = frases[i]
                Row {
                    Text(GrabarClase.minuto(ms), color = Color.White.copy(alpha = 0.5f), fontSize = (12 * escala).sp, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                    Text(texto, color = Color.White, fontSize = (17 * escala).sp)
                }
            }
            if (parcial.isNotBlank()) item {
                Text("«$parcial»", color = Color.White.copy(alpha = 0.6f), fontSize = (16 * escala).sp, fontStyle = FontStyle.Italic)
            }
        }
    }

    companion object {
        private const val ESCALA = "escala_pantalla_completa"

        fun abrir(context: Context) {
            runCatching { context.startActivity(ReunionPresencialService.intentoDeLaPantalla(context)) }
        }
    }
}

/**
 * **La mirilla de la reunión**: lo que ve la cámara en vivo ([PreviewView] con la superficie que se
 * le da al servicio con [conProveedor]), el destello de cada foto y la chapa «● Clip 0:12» en rojo
 * mientras se graba uno. La usan la pantalla de la reunión y, en pequeño, la barra de la app.
 */
@Composable
internal fun MirillaDeLaReunion(e: EstadoDeLaReunion, modifier: Modifier, conProveedor: (androidx.camera.core.Preview.SurfaceProvider?) -> Unit) {
    val rojo = Color(0xFFE53935)
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .border(if (e.clipDesde != null) 3.dp else 0.dp, rojo, RoundedCornerShape(16.dp))
    ) {
        AndroidView(
            factory = { c ->
                PreviewView(c).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    conProveedor(surfaceProvider)
                }
            },
            onRelease = { conProveedor(null) },
            modifier = Modifier.fillMaxSize()
        )
        Destello(e.destellos, Modifier.fillMaxSize())
        val desde = e.clipDesde
        if (desde != null) Text(
            "● " + androidx.compose.ui.platform.LocalContext.current.getString(R.string.reunion_clip) + " " +
                GrabarClase.minuto((e.segundos * 1000 - desde).coerceAtLeast(0)),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(10.dp)).background(rojo)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
        if (e.fotosEnCurso > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(12.dp).size(12.dp).clip(CircleShape).background(Color.White)
        )
    }
}

/** **El destello de una foto**: blanco que se apaga en un tercio de segundo, cada vez que sube [destellos]. */
@Composable
internal fun Destello(destellos: Int, modifier: Modifier) {
    val brillo = remember { Animatable(0f) }
    val primera = remember { intArrayOf(destellos) }
    LaunchedEffect(destellos) {
        if (destellos > primera[0]) { brillo.snapTo(0.9f); brillo.animateTo(0f, tween(380)) }
        primera[0] = destellos
    }
    Box(modifier.background(Color.White.copy(alpha = brillo.value)))
}
