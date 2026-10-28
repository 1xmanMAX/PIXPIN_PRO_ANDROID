package com.forge.pixpin.guardados

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.forge.pixpin.R
import com.forge.pixpin.ui.theme.PixPinTheme
import kotlin.math.roundToInt

/**
 * **La barra de la reunión en la app** (28-oct-2026, el usuario: «no hay botón para terminar la
 * grabación»). Con una reunión presencial en marcha, **cada pantalla de PixPin** lleva abajo una
 * pastilla con «● 12:34», **Foto**, **Clip** («● Clip 0:12» mientras se graba uno), **Pausa** y
 * **Terminar**; tocar el reloj abre la pantalla de la reunión ([ReunionEnCursoActivity], con la
 * mirilla grande). Se arrastra arriba y abajo si tapa algo.
 *
 * Se pone sola en cada pantalla que se abre (sin tocar ninguna): al volver una actividad de la app, se
 * añade a su `android.R.id.content` una vista de Compose encima de lo suyo, y se quita al irse. Con
 * PixPin delante, la burbuja flotante del servicio se esconde ([ReunionPresencialService.appDelante]):
 * dos mandos iguales a la vez sobran.
 */
object ReunionEnLaApp {

    private const val ETIQUETA = "pixpin-barra-de-la-reunion"
    private var delante = 0

    fun vigilar(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                delante++
                ReunionPresencialService.appDelante.value = true
                if (activity is ComponentActivity && activity !is ReunionEnCursoActivity) runCatching { poner(activity) }
            }

            override fun onActivityPaused(activity: Activity) {
                delante = (delante - 1).coerceAtLeast(0)
                ReunionPresencialService.appDelante.value = delante > 0
                runCatching { quitar(activity) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun poner(a: ComponentActivity) {
        val raiz = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (raiz.findViewWithTag<View>(ETIQUETA) != null) return
        val v = ComposeView(a).apply {
            tag = ETIQUETA
            setViewTreeLifecycleOwner(a)
            setViewTreeViewModelStoreOwner(a)
            setViewTreeSavedStateRegistryOwner(a)
            setContent { PixPinTheme(darkTheme = true) { Barra() } }
        }
        raiz.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun quitar(a: Activity) {
        val raiz = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        raiz.findViewWithTag<View>(ETIQUETA)?.let { raiz.removeView(it) }
    }

    /** La pastilla: solo con una reunión grabando (y no mientras se guarda). */
    @Composable
    private fun Barra() {
        val e by ReunionPresencialService.estado.collectAsState()
        var dy by remember { mutableFloatStateOf(0f) }
        val contexto = LocalContext.current
        val claro = Color.White
        val rojo = Color(0xFFE53935)
        if (e.lista && !e.terminando && ReunionPresencialService.enMarcha) Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 76.dp)
                .offset { IntOffset(0, dy.roundToInt()) }
                .pointerInput(Unit) { detectDragGestures { cambio, d -> cambio.consume(); dy += d.y } },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            e.aviso?.let {
                Text(
                    it, color = claro, fontSize = 12.sp, maxLines = 2,
                    modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xEE1E1E22))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF01E1E22)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // El reloj: abre la pantalla de la reunión.
                Row(
                    Modifier.clip(RoundedCornerShape(20.dp)).clickable { ReunionEnCursoActivity.abrir(contexto) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (e.enPausa) Color.Gray else rojo))
                    val desde = e.clipDesde
                    Text(
                        if (desde != null) "  ● " + contexto.getString(R.string.reunion_clip) + " " + GrabarClase.minuto((e.segundos * 1000 - desde).coerceAtLeast(0))
                        else "  " + GrabarClase.minuto(e.segundos * 1000) + (if (e.fotos > 0) " · 📷 ${e.fotos}" else ""),
                        color = if (desde != null) Color(0xFFFF8A80) else claro, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                if (e.conCamara) {
                    IconButton(onClick = { ReunionPresencialService.foto() }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Filled.PhotoCamera, contexto.getString(R.string.reunion_foto), tint = claro)
                    }
                    IconButton(onClick = { ReunionPresencialService.clip() }, modifier = Modifier.size(42.dp)) {
                        Icon(
                            if (e.clipDesde != null) Icons.Filled.StopCircle else Icons.Filled.Videocam,
                            contexto.getString(if (e.clipDesde != null) R.string.reunion_parar_clip else R.string.reunion_clip),
                            tint = if (e.clipDesde != null) Color(0xFFFF5252) else claro
                        )
                    }
                }
                IconButton(onClick = { ReunionPresencialService.pausa() }, modifier = Modifier.size(42.dp)) {
                    Icon(
                        if (e.enPausa) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contexto.getString(if (e.enPausa) R.string.clase_seguir else R.string.clase_pausa), tint = claro
                    )
                }
                IconButton(onClick = { ReunionPresencialService.terminar() }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.Stop, contexto.getString(R.string.clase_terminar), tint = Color(0xFF81C784))
                }
            }
        }
    }
}
