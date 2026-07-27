package com.forge.pixpin.capture

import android.graphics.Bitmap

/**
 * Pasa el fotograma capturado entre el servicio y la UI de captura.
 * Un Bitmap de pantalla completa ocupa ~10 MB, demasiado para un extra de Intent;
 * servicio y actividad comparten proceso, así que basta un holder en memoria.
 */
object FrameHolder {

    @Volatile
    var bitmap: Bitmap? = null
        private set

    fun set(frame: Bitmap) {
        synchronized(this) {
            val old = bitmap
            bitmap = frame
            if (old != null && old != frame && !old.isRecycled) old.recycle()
        }
    }

    fun take(): Bitmap? {
        synchronized(this) {
            val current = bitmap ?: return null
            bitmap = null
            return current
        }
    }

    fun clear() {
        synchronized(this) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = null
        }
    }
}
