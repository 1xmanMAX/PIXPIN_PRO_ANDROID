package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.R
import com.forge.pixpin.motor.añadirVistaFluida
import com.forge.pixpin.pin.OverlayComposeWindow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * **El recorte de una captura de la grabación** (25-oct-2026, como el PC; sacado el 29-oct-2026 de la
 * barra de la clase para la grabación única, [ReunionPresencialService]): la pantalla congelada a
 * pantalla completa, se arrastra un rectángulo y fuera de él se oscurece. «Pantalla entera» no pide nada
 * más.
 */
class ZonaDeLaCaptura(private val context: Context) {

    /** Lo que sale de elegir: [zona] nula es la pantalla entera. */
    class Eleccion(val zona: GrabarClase.Zona?)

    private val wm = context.getSystemService(WindowManager::class.java)
    private var selector: OverlayComposeWindow? = null

    /** Enseña [foto] a pantalla completa y espera la zona. Null si se cancela. */
    suspend fun elegir(foto: Bitmap): Eleccion? = suspendCancellableCoroutine { cont ->
        var hecho = false
        fun acabar(e: Eleccion?) {
            if (hecho) return
            hecho = true
            quitar()
            if (cont.isActive) cont.resume(e)
        }
        val (ancho, alto) = pantalla()
        val params = WindowManager.LayoutParams(
            ancho, alto,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val w = OverlayComposeWindow(context, matchParent = true) { Selector(foto) { acabar(it) } }
        val puesto = runCatching {
            wm.añadirVistaFluida(w.view, params)
            w.onAttached()
            selector = w
        }.isSuccess
        if (!puesto) acabar(null)
        cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { acabar(null) } }
    }

    /** Quita el selector si está puesto (al terminar la grabación a medio elegir). */
    fun quitar() {
        selector?.let { w -> runCatching { wm.removeView(w.view) }; w.onDetached() }
        selector = null
    }

    /** El tamaño real de la pantalla, barras incluidas: el del fotograma. Ver `FloatingBallController.pantalla`. */
    private fun pantalla(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    @Composable
    private fun Selector(foto: Bitmap, alElegir: (Eleccion?) -> Unit) {
        val imagen = remember(foto) { foto.asImageBitmap() }
        var a by remember { mutableStateOf<Offset?>(null) }
        var b by remember { mutableStateOf<Offset?>(null) }
        var tam by remember { mutableStateOf(IntSize.Zero) }
        val elegida = run {
            val p = a
            val q = b
            if (p == null || q == null) null
            else GrabarClase.zonaArrastrada(
                p.x, p.y, q.x, q.y, tam.width.toFloat(), tam.height.toFloat(), foto.width, foto.height
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(imagen, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(
                Modifier.fillMaxSize().onSizeChanged { tam = it }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { a = it; b = it },
                        onDrag = { cambio, _ -> cambio.consume(); b = cambio.position }
                    )
                }
            ) {
                val sombra = Color.Black.copy(alpha = 0.45f)
                val p = a
                val q = b
                if (p == null || q == null) {
                    drawRect(sombra)
                } else {
                    val l = minOf(p.x, q.x); val t = minOf(p.y, q.y)
                    val r = maxOf(p.x, q.x); val btm = maxOf(p.y, q.y)
                    drawRect(sombra, Offset(0f, 0f), Size(size.width, t))
                    drawRect(sombra, Offset(0f, btm), Size(size.width, size.height - btm))
                    drawRect(sombra, Offset(0f, t), Size(l, btm - t))
                    drawRect(sombra, Offset(r, t), Size(size.width - r, btm - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, btm - t), style = Stroke(width = 3.dp.toPx()))
                }
            }
            Text(
                context.getString(R.string.clase_elige_zona), color = Color.White, fontSize = 15.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xCC000000)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { alElegir(null) }) { Text(context.getString(R.string.cancel), color = Color.White) }
                OutlinedButton(onClick = { alElegir(Eleccion(null)) }) {
                    Text(context.getString(R.string.clase_pantalla_entera), color = Color.White)
                }
                Button(enabled = elegida != null, onClick = { elegida?.let { alElegir(Eleccion(it)) } }) {
                    Text(context.getString(R.string.clase_usar_zona), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    companion object {
        /** El recorte de [foto] a la zona, en PNG en [destino]. Si la zona ya no cabe (giró), la pantalla. */
        fun recortarYGuardar(foto: Bitmap, zona: GrabarClase.Zona?, destino: File): File? = runCatching {
            val r = GrabarClase.recorte(zona, foto.width, foto.height)
                ?: GrabarClase.recorte(null, foto.width, foto.height)
                ?: return null
            val recorte = if (r.x == 0 && r.y == 0 && r.ancho == foto.width && r.alto == foto.height) foto
            else Bitmap.createBitmap(foto, r.x, r.y, r.ancho, r.alto)
            destino.parentFile?.mkdirs()
            val bytes = java.io.ByteArrayOutputStream().also { recorte.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            GrabacionAMedias.escribirEntero(destino, bytes)
            if (recorte !== foto) recorte.recycle()
            foto.recycle()
            destino
        }.getOrNull()
    }
}
