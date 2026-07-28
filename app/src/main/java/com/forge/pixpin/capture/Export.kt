package com.forge.pixpin.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guardar en galería (MediaStore), copiar imagen al portapapeles y compartir. */
object Export {

    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val name = "PixPin_${timestamp()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PixPin")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun copyToClipboard(context: Context, bitmap: Bitmap): Boolean {
        val uri = writeShareFile(context, bitmap) ?: return false
        val clip = ClipData.newUri(context.contentResolver, "PixPin", uri)
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(clip)
        return true
    }

    /** Escribe el PNG temporal (E/S: fuera del hilo de UI) y devuelve su Uri. */
    fun prepareShare(context: Context, bitmap: Bitmap): Uri? = writeShareFile(context, bitmap)

    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun writeShareFile(context: Context, bitmap: Bitmap): Uri? {
        return runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "pixpin_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

/** Convierte el contenido de un pin en bitmap para guardarlo en galería. */
object PinExporter {

    fun savePin(context: Context, state: com.forge.pixpin.pin.PinState): Boolean {
        val bitmap = render(state) ?: return false
        val uri = Export.saveToGallery(context, bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
        return uri != null
    }

    private fun render(state: com.forge.pixpin.pin.PinState): Bitmap? = when (state.type) {
        com.forge.pixpin.pin.PinType.IMAGE ->
            state.imagePath?.let { com.forge.pixpin.pin.ImageStore.load(it) }?.let { bmp ->
                // Lo dibujado sobre el pin se hornea al exportar: hasta aquí eran
                // vectores encima de la imagen, re-editables.
                if (state.annotations.isEmpty()) bmp
                else {
                    val baked = com.forge.pixpin.annotate.AnnotationRenderer.bake(
                        bmp,
                        android.graphics.Rect(0, 0, bmp.width, bmp.height),
                        state.annotations
                    )
                    if (!bmp.isRecycled) bmp.recycle()
                    baked
                }
            }

        com.forge.pixpin.pin.PinType.COLOR -> {
            val argb = state.colorArgb ?: return null
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                eraseColor(argb)
            }
        }

        com.forge.pixpin.pin.PinType.TEXT -> renderText(state.text)

        com.forge.pixpin.pin.PinType.FILE -> renderText(state.fileName)
    }

    private fun renderText(text: String?): Bitmap? {
        if (text.isNullOrBlank()) return null
        val width = 720
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 40f
        }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .build()
        val pad = 48
        val bitmap = Bitmap.createBitmap(
            width + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(canvas)
        return bitmap
    }
}
