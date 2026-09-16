package com.forge.pixpin.motor

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * **La franja que se traga el deslizamiento del borde de arriba.**
 *
 * En pantalla completa las barras están escondidas, pero Android deja sacarlas deslizando desde
 * el canto de arriba, y de ahí a bajar la cortina de notificaciones encima del dibujo hay un
 * dedo. El sistema no ofrece nada para impedirlo: las exclusiones de gestos
 * (`setSystemGestureExclusionRects`) solo valen para los laterales, y una ventana normal de la
 * aplicación no llega por encima de la zona del sistema.
 *
 * Lo que sí llega es una **ventana sobre la pantalla** —el mismo permiso con el que PixPin
 * dibuja encima de otras aplicaciones—. Se pone una tira invisible de [ALTO_DP] pegada arriba
 * que **acepta el toque y no lo suelta**: el dedo que empieza ahí lo recibe ella, no el detector
 * de gestos del sistema, así que la barra no baja. A cambio, esa franja de arriba deja de
 * dibujar — que es justo donde el usuario decía que se le bajaba la cortina al escribir.
 *
 * No hay garantía en todos los teléfonos: cada fabricante toca la capa del sistema. Por eso es
 * una de las tres opciones de [com.forge.pixpin.data.BarraDeArriba] y no la única forma.
 */
class FranjaDeArriba(private val context: Context) {

    private var vista: View? = null

    /** Si la franja está puesta ahora mismo. */
    val puesta: Boolean get() = vista != null

    fun poner() {
        if (vista != null) return
        if (!android.provider.Settings.canDrawOverlays(context)) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val alto = (ALTO_DP * context.resources.displayMetrics.density).toInt()
        val v = object : View(context) {
            // Se queda el gesto entero: devolver `true` en el ABAJO es lo que hace que los
            // movimientos siguientes también vengan aquí en vez de irse al sistema.
            override fun onTouchEvent(event: MotionEvent): Boolean = true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            alto,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // Sin foco —para no robarle el teclado ni el atrás al editor— pero **tocable**:
            // sin tocarla no habría nada que tragar. Y dibujada hasta el borde de verdad,
            // por encima de donde vive la barra de estado.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { wm.addView(v, lp) }.onSuccess { vista = v }
    }

    fun quitar() {
        val v = vista ?: return
        vista = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    private companion object {
        /** Lo que mide el gesto del borde; con menos, el deslizamiento se escapa por debajo. */
        const val ALTO_DP = 26f
    }
}
