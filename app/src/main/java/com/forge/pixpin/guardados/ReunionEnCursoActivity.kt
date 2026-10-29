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
 * **La pantalla de la reunión presencial en marcha** (28-oct-2026, el usuario: «mientras grabo una
 * reunión no sé si tomé la foto o grabé el clip, y no hay botón para terminar la grabación»; y «puede
 * ser un pequeño cuadro donde vea que estoy tomando fotos o grabando»).
 *
 * La abre tocar la notificación de la reunión o el reloj de la barra de la reunión en la app
 * ([ReunionEnLaApp]). Arriba, **la mirilla grande** (la cámara de la reunión en vivo, la misma
 * sesión de CameraX que las fotos: ver [CamaraDeLaReunion.ponerMirilla]) con su destello al hacer una
 * foto y «● Clip 0:12» en rojo mientras se graba un clip; debajo, el reloj, lo hecho y los cuatro
 * botones grandes: **Foto**, **Clip**, **Pausa** y **Terminar**. Atrás no para nada: la reunión sigue
 * con la burbuja y la notificación. Al terminar, se va sola.
 *
 * Es solo un mando: todo lo hace [ReunionPresencialService] (mismo proceso), y lo que se ve sale de
 * [ReunionPresencialService.estado].
 */
class ReunionEnCursoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ReunionPresencialService.enMarcha) {
            Toast.makeText(this, R.string.reunion_ya_no, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // De borde a borde y negra, como una cámara: los iconos de arriba, claros.
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
        // Terminada (o parada por el sistema): fuera.
        LaunchedEffect(e.lista, e.terminando, e.guardada) {
            if (e.terminando || e.guardada != null || (!e.lista && !ReunionPresencialService.enMarcha)) finish()
        }
        Column(
            Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, getString(R.string.cancel), tint = Color.White) }
                Text(getString(R.string.reunion_en_curso), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                if (e.mirilla) MirillaDeLaReunion(e, Modifier.fillMaxSize(), conProveedor = { ReunionPresencialService.mirillaEnLaApp(it) })
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.VideocamOff, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
                    Text(
                        getString(if (e.conCamara) R.string.reunion_sin_mirilla else R.string.reunion_sin_camara),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(16.dp)
                    )
                    // Sin mirilla, el destello va en el reloj: también se ve la foto.
                    Destello(e.destellos, Modifier.size(1.dp))
                }
            }
            Text(
                (if (e.enPausa) "❚❚ " else "● ") + GrabarClase.minuto(e.segundos * 1000),
                color = if (e.enPausa) Color.Gray else Color(0xFFFF5252), fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                resources.getQuantityString(R.plurals.reunion_fotos, e.fotos, e.fotos) + " · " +
                    resources.getQuantityString(R.plurals.reunion_clips, e.clips, e.clips) +
                    (if (e.fotosEnCurso > 0) " · " + getString(R.string.reunion_foto_en_curso) else ""),
                color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp
            )
            Text(e.aviso ?: " ", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            // **Los temas de la reunión y las fichas de Jev** (29-oct-2026): «☑ 2/5» abre la lista; un toque marca a mano.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center) { TemasEnLaBarra.Boton() }
            TemasEnLaBarra.Panel(Modifier.padding(horizontal = 12.dp))
            // **Colaboradores** (28-oct-2026, §14 de la guía del PC): los del chat compartido aportan
            // fotos que entran en su minuto; y lo que manda el PC a esta grabación (§13).
            val todos by com.forge.pixpin.sincro.GrabacionDelMovil.abiertaATodos.collectAsState()
            val delPc by com.forge.pixpin.sincro.GrabacionDelMovil.delPc.collectAsState()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    getString(R.string.clase_todos_aportan) +
                        (if (delPc > 0) " · " + resources.getQuantityString(R.plurals.reunion_capturas_del_pc, delPc, delPc) else ""),
                    color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(checked = todos, onCheckedChange = { com.forge.pixpin.sincro.GrabacionDelMovil.abiertaATodos.value = it })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = { ReunionPresencialService.foto() }, enabled = e.conCamara, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.size(6.dp)); Text(getString(R.string.reunion_foto))
                }
                FilledTonalButton(
                    onClick = { ReunionPresencialService.clip() }, enabled = e.conCamara, modifier = Modifier.weight(1f).height(56.dp),
                    colors = if (e.clipDesde != null) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFB71C1C), contentColor = Color.White)
                    else ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(if (e.clipDesde != null) Icons.Filled.StopCircle else Icons.Filled.Videocam, null)
                    Spacer(Modifier.size(6.dp))
                    Text(getString(if (e.clipDesde != null) R.string.reunion_parar_clip else R.string.reunion_clip))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = { ReunionPresencialService.pausa() }, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(if (e.enPausa) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                    Spacer(Modifier.size(6.dp))
                    Text(getString(if (e.enPausa) R.string.clase_seguir else R.string.clase_pausa))
                }
                Button(
                    onClick = { ReunionPresencialService.terminar() }, modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.size(6.dp)); Text(getString(R.string.clase_terminar))
                }
            }
        }
    }

    companion object {
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
