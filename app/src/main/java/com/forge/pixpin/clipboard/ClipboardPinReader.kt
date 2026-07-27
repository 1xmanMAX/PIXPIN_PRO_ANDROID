package com.forge.pixpin.clipboard

import android.content.ClipboardManager
import android.content.Context

/**
 * Lee y clasifica el contenido del portapapeles.
 * IMPORTANTE: en Android 10+ solo se puede leer con la app en primer plano;
 * por eso quien invoca esto es ClipboardPinActivity (actividad transparente).
 */
class ClipboardPinReader(private val context: Context) {

    fun read(): PinContent {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return PinContent.Empty
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return PinContent.Empty
        if (clip.itemCount == 0) return PinContent.Empty

        val item = clip.getItemAt(0)

        item.uri?.let { uri ->
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            return when {
                type == null || type.startsWith("image/") -> PinContent.ImageUri(uri.toString())
                else -> PinContent.FileUri(uri.toString())
            }
        }

        val text = item.text?.toString()
            ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        return ContentClassifier.classify(text)
    }
}
