package com.forge.pixpin.sincro

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.remember
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors

/** **El QR del envío**: dibujarlo y leerlo con la cámara. Con ZXing, sin servicios de Google. */
object Qr {

    fun imagen(texto: String, lado: Int = 720): Bitmap {
        val matriz = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, lado, lado, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(lado * lado)
        for (y in 0 until lado) {
            val fila = y * lado
            for (x in 0 until lado) px[fila + x] = if (matriz[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.ARGB_8888)
    }

    /**
     * La cámara, mirando QR. Llama a [alLeer] con el texto de cada uno que entienda; quien lo usa
     * decide si le vale. Solo se mira el brillo (el plano Y), que es lo que un QR necesita.
     */
    @Composable
    fun Escaner(modifier: Modifier = Modifier, alLeer: (String) -> Unit) {
        val contexto = LocalContext.current
        val ciclo = LocalLifecycleOwner.current
        val avisar = rememberUpdatedState(alLeer)
        val hilo = remember { Executors.newSingleThreadExecutor() }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val vista = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                val futuro = ProcessCameraProvider.getInstance(ctx)
                futuro.addListener({
                    val proveedor = runCatching { futuro.get() }.getOrNull() ?: return@addListener
                    val mirilla = Preview.Builder().build().also { it.surfaceProvider = vista.surfaceProvider }
                    val analisis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val lector = QRCodeReader()
                    analisis.setAnalyzer(hilo) { imagen ->
                        runCatching {
                            val plano = imagen.planes[0]
                            val buf = plano.buffer
                            val ancho = plano.rowStride
                            val alto = imagen.height
                            // La última fila puede venir sin su relleno: se reserva el rectángulo entero.
                            val datos = ByteArray(ancho * alto).also { buf.get(it, 0, minOf(buf.remaining(), it.size)) }
                            val fuente = PlanarYUVLuminanceSource(datos, ancho, alto, 0, 0, imagen.width.coerceAtMost(ancho), alto, false)
                            val texto = lector.decode(BinaryBitmap(HybridBinarizer(fuente)), mapOf(DecodeHintType.TRY_HARDER to true)).text
                            ContextCompat.getMainExecutor(ctx).execute { avisar.value(texto) }
                        }
                        lector.reset()
                        imagen.close()
                    }
                    runCatching {
                        proveedor.unbindAll()
                        proveedor.bindToLifecycle(ciclo, CameraSelector.DEFAULT_BACK_CAMERA, mirilla, analisis)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                vista
            }
        )
        DisposableEffect(Unit) {
            onDispose {
                runCatching { ProcessCameraProvider.getInstance(contexto).get().unbindAll() }
                hilo.shutdown()
            }
        }
    }
}
