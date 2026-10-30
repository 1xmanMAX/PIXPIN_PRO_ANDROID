package com.forge.pixpin.pin

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * **Si la llamada secreta puede pedir la pantalla completa** (30-oct-2026, versión de Google Play).
 *
 * `setFullScreenIntent` necesita USE_FULL_SCREEN_INTENT. El APK de GitHub lo declara y todo sigue
 * igual; la versión de Play no (Play solo lo da a apps de llamadas y despertadores). Sin él no se
 * pide la pantalla completa y la llamada sale como aviso urgente arriba (categoría llamada, prioridad
 * máxima), que es lo que Android hace de todos modos cuando no la deja. Ver [LlamadaSecretaActivity].
 */
object PantallaCompleta {

    /** Lo que dice este aparato ahora mismo. Nunca lanza. */
    fun puede(context: Context, avisos: NotificationManager): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val sistema = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { avisos.canUseFullScreenIntent() }.getOrDefault(false) else null
        val declarado = runCatching {
            context.checkSelfPermission(android.Manifest.permission.USE_FULL_SCREEN_INTENT) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return permitida(sdk, sistema, declarado)
    }

    /**
     * Pura, para probarla: desde Android 14 decide el sistema ([sistema], lo que responde
     * `canUseFullScreenIntent`, que el usuario puede quitar); antes, basta con tener el permiso
     * ([declarado]: es «normal», se concede al instalar si el manifiesto lo pide).
     */
    fun permitida(sdk: Int, sistema: Boolean?, declarado: Boolean): Boolean =
        if (sdk >= 34) sistema == true else declarado
}
