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
import androidx.compose.material.icons.filled.Fullscreen
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
 * **La barra de la grabación en la app** (28-oct-2026, el usuario: «no hay botón para terminar la
 * grabación»). Con una grabación en marcha, **cada pantalla de PixPin** lleva abajo una pastilla con los
 * mandos de [MandosDeLaGrabacion] (29-oct-2026): «● 12:34» (un toque pausa, mantenerlo termina), la
 * pantalla completa ([ReunionEnCursoActivity]) y Foto, Captura, Pantalla y Clip. Se arrastra arriba y
 * abajo si tapa algo, y se esconde mientras se hace una captura.
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

    /** La pastilla: solo con una grabación en marcha (y no mientras se guarda ni en una captura). */
    @Composable
    private fun Barra() {
        val e by ReunionPresencialService.estado.collectAsState()
        var dy by remember { mutableFloatStateOf(0f) }
        val contexto = LocalContext.current
        val claro = Color.White
        if (e.lista && !e.terminando && !e.oculta && ReunionPresencialService.enMarcha) Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 76.dp)
                .offset { IntOffset(0, dy.roundToInt()) }
                .pointerInput(Unit) { detectDragGestures { cambio, d -> cambio.consume(); dy += d.y } },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Los temas de la reunión y las fichas de Jev (29-oct-2026), encima de la pastilla.
            TemasEnLaBarra.Panel(Modifier.padding(bottom = 4.dp))
            e.aviso?.let {
                Text(
                    it, color = claro, fontSize = 12.sp, maxLines = 2,
                    modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xEE1E1E22))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            // **Dos filas con aire** (29-oct-2026): el reloj (toque, pausa; mantener, terminar), los temas y
            // la pantalla completa; debajo, Foto, Captura, Pantalla y Clip con su nombre.
            Column(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF01E1E22)).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MandosDeLaGrabacion.Reloj(e, claro)
                    TemasEnLaBarra.Boton(claro)
                    IconButton(onClick = { ReunionEnCursoActivity.abrir(contexto) }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Filled.Fullscreen, contexto.getString(R.string.grabar_pantalla_completa), tint = claro)
                    }
                }
                MandosDeLaGrabacion.Acciones(e, claro)
            }
        }
    }
}
