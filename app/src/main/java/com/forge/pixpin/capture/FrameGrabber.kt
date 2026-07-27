package com.forge.pixpin.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Extrae un único fotograma de pantalla creando un VirtualDisplay efímero
 * sobre el MediaProjection vivo. Crear el display por captura (en vez de
 * mantenerlo siempre) evita el coste de batería del mirroring constante.
 */
object FrameGrabber {

    suspend fun grab(context: Context, projection: MediaProjection): Bitmap? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val display: Display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        val thread = HandlerThread("pixpin-grab").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        try {
            val image: Image = withTimeoutOrNull(2500) {
                suspendCancellableCoroutine { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireLatestImage() }.getOrNull()
                        r.setOnImageAvailableListener(null, null)
                        if (cont.isActive) cont.resume(img)
                    }, handler)
                    cont.invokeOnCancellation { reader.setOnImageAvailableListener(null, null) }
                    vd = projection.createVirtualDisplay(
                        "pixpin-capture", width, height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface, null, handler
                    )
                }
            } ?: return null

            return image.use { img ->
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                // El buffer puede llevar padding de fila: se copia con el ancho
                // acolchado y se recorta al ancho real de pantalla.
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                if (paddedWidth == width) {
                    padded
                } else {
                    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                    padded.recycle()
                    cropped
                }
            }
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { vd?.release() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }
}
