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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Sesión de captura de pantalla.
 *
 * Regla de Android 14+: un token de MediaProjection sirve para UNA sola llamada
 * a createVirtualDisplay(); pedir consentimiento y crear un display nuevo en
 * cada captura falla a partir de la segunda. Por eso el VirtualDisplay se crea
 * una única vez y vive mientras dura la sesión.
 *
 * El espejo se mantiene enganchado todo el rato y se conserva el último
 * fotograma recibido. Es lo que hace la captura fiable: un display espejo solo
 * emite fotogramas cuando la pantalla CAMBIA, así que enganchar la superficie
 * en el momento de capturar no garantiza que llegue ninguno (fallaba de forma
 * intermitente, sobre todo con la pantalla quieta). Con el último fotograma
 * guardado siempre hay algo válido: si no ha llegado uno nuevo es precisamente
 * porque la pantalla no ha cambiado.
 */
object ProjectionSession {

    private const val MAX_IMAGES = 3

    private val lock = Any()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Último fotograma recibido, a la espera de que alguien lo pida. */
    private var latest: Image? = null
    private var waiter: CancellableContinuation<Image?>? = null

    private var width = 0
    private var height = 0
    private var densityDpi = 0

    /** Se avisa cuando el sistema corta la proyección (chip de la barra, bloqueo de pantalla). */
    var onSessionLost: (() -> Unit)? = null

    val isAlive: Boolean get() = projection != null && virtualDisplay != null

    /**
     * Arranca la sesión con el token recién concedido. Debe llamarse DESPUÉS de
     * que el servicio esté en primer plano con el tipo mediaProjection.
     */
    fun start(context: Context, proj: MediaProjection): Boolean {
        stop()
        val metrics = displayMetrics(context) ?: return false
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
                onSessionLost?.invoke()
            }
        }, Handler(context.mainLooper))

        val ht = HandlerThread("pixpin-projection").apply { start() }
        val h = Handler(ht.looper)
        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        ir.setOnImageAvailableListener({ r -> onFrame(r) }, h)

        val vd = runCatching {
            proj.createVirtualDisplay(
                "pixpin-capture", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                ir.surface, null, h
            )
        }.getOrNull()

        if (vd == null) {
            runCatching { ir.close() }
            ht.quitSafely()
            runCatching { proj.stop() }
            return false
        }

        projection = proj
        virtualDisplay = vd
        reader = ir
        thread = ht
        handler = h
        return true
    }

    /** Llega un fotograma: se entrega a quien esté esperando o se guarda como último. */
    private fun onFrame(r: ImageReader) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val cont: CancellableContinuation<Image?>?
        synchronized(lock) {
            cont = waiter
            if (cont != null) {
                waiter = null
            } else {
                latest?.close()
                latest = img
            }
        }
        if (cont != null && cont.isActive) cont.resume(img) else if (cont != null) img.close()
    }

    private fun takeLatest(): Image? = synchronized(lock) {
        val img = latest
        latest = null
        img
    }

    /** Toma un fotograma de la pantalla actual. Null si la sesión ya no es válida. */
    suspend fun grab(): Bitmap? {
        if (!isAlive) return null

        // 1) Acabamos de ocultar los overlays: eso es un cambio de pantalla, así
        //    que lo normal es que llegue un fotograma nuevo en uno o dos frames.
        var image = awaitFrame(350)
        // 2) Si no llega, la pantalla está quieta: el último guardado sigue
        //    siendo exactamente lo que se ve.
        if (image == null) image = takeLatest()
        // 3) Ni uno ni otro (sesión recién abierta sobre pantalla estática):
        //    un resize fuerza el repintado del espejo.
        if (image == null) {
            runCatching { virtualDisplay?.resize(width, height, densityDpi) }
            image = awaitFrame(2500)
        }
        if (image == null) return null

        return try {
            // Copiar ~10 MB de píxeles fuera del hilo de UI.
            withContext(Dispatchers.Default) {
                image.use { img -> img.toBitmap(width, height) }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Fotograma solo si la pantalla se ha movido. Null si no llega nada.
     *
     * A diferencia de [grab], no fuerza un repintado del espejo cuando no hay
     * novedad: esto se llama en bucle durante la captura con scroll y ahí "no ha
     * pasado nada" es una respuesta válida y frecuente —el dedo está parado—,
     * no un fallo que haya que remediar.
     */
    suspend fun grabIfChanged(timeoutMs: Long = 200): Bitmap? {
        if (!isAlive) return null
        val image = awaitFrame(timeoutMs) ?: takeLatest() ?: return null
        return try {
            withContext(Dispatchers.Default) { image.use { it.toBitmap(width, height) } }
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun awaitFrame(timeoutMs: Long): Image? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val ready = synchronized(lock) {
                val pending = latest
                if (pending != null) {
                    latest = null
                    pending
                } else {
                    waiter = cont
                    null
                }
            }
            if (ready != null) cont.resume(ready)
            cont.invokeOnCancellation { synchronized(lock) { if (waiter === cont) waiter = null } }
        }
    }

    /**
     * Idempotente y reentrante: MediaProjection.stop() dispara el callback
     * onStop(), que vuelve a llamar aquí. Se sueltan las referencias ANTES de
     * cerrar nada para que la segunda pasada no haga nada.
     */
    fun stop() {
        val proj = projection
        val vd = virtualDisplay
        val ir = reader
        val ht = thread
        projection = null
        virtualDisplay = null
        reader = null
        thread = null
        handler = null
        synchronized(lock) {
            latest?.close()
            latest = null
            waiter?.let { if (it.isActive) it.resume(null) }
            waiter = null
        }

        runCatching { vd?.surface = null }
        runCatching { vd?.release() }
        runCatching { ir?.setOnImageAvailableListener(null, null) }
        runCatching { ir?.close() }
        runCatching { proj?.stop() }
        ht?.quitSafely()
    }

    private fun displayMetrics(context: Context): DisplayMetrics? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics
    }
}

/**
 * Copia el fotograma a un Bitmap recortando el padding de fila que añade el
 * buffer de hardware.
 */
private fun Image.toBitmap(width: Int, height: Int): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedWidth = width + rowPadding / pixelStride
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(plane.buffer)
    return if (paddedWidth == width) {
        padded
    } else {
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        cropped
    }
}
