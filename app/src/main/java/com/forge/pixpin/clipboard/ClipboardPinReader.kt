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
        val text = item.text?.toString()

        item.uri?.let { uri ->
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()

            // El texto manda cuando la URI es solo la versión enriquecida de lo
            // mismo. Google Sheets, por ejemplo, deja la selección como texto Y
            // como HTML: mirando la URI primero, una tabla copiada acababa
            // siendo un pin de archivo y el texto no lo veía nadie.
            val uriIsRichTextOfTheSameThing = type == null || type.startsWith("text/")
            if (uriIsRichTextOfTheSameThing && !text.isNullOrBlank()) {
                return ContentClassifier.classify(text)
            }

            return when {
                // Sin tipo y sin texto, lo más probable es una imagen.
                type == null || type.startsWith("image/") -> PinContent.ImageUri(uri.toString())
                else -> PinContent.FileUri(uri.toString())
            }
        }

        val body = text
            ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        return ContentClassifier.classify(body)
    }
}
