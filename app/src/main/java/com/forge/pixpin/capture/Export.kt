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
import com.forge.pixpin.data.CopyFormat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guardar en galería (MediaStore), copiar imagen al portapapeles y compartir. */
object Export {

    /**
     * Guarda en la galería, con el formato que se haya elegido en los ajustes.
     *
     * Sigue admitiéndose forzarlo por parámetro para quien tenga un motivo,
     * pero lo normal es que lo decida el usuario una vez y valga para todo:
     * copiar, compartir y guardar deberían dar el mismo archivo.
     */
    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = formatoElegido(context).compresor,
        quality: Int = 100
    ): Uri? {
        val extension = when (format) {
            Bitmap.CompressFormat.JPEG -> "jpg"
            Bitmap.CompressFormat.PNG -> "png"
            else -> "webp"
        }
        val name = "PixPin_${timestamp()}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/$extension")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PixPin")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(format, quality, out)
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

    /** Escribe la imagen temporal (E/S: fuera del hilo de UI) y devuelve su Uri. */
    fun prepareShare(context: Context, bitmap: Bitmap): Uri? = writeShareFile(context, bitmap)

    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = formatoElegido(context).mime
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
            val elegido = formatoElegido(context)
            val file = File(dir, "pixpin_${System.currentTimeMillis()}.${elegido.extension}")
            FileOutputStream(file).use { bitmap.compress(elegido.compresor, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // El formato de la imagen
    // ---------------------------------------------------------------------

    /**
     * Qué formato ha pedido el usuario. PNG si no se sabe.
     *
     * Se lee de la instantánea que mantiene [com.forge.pixpin.PixPinApp] y no
     * del `Flow`: esto se llama desde funciones normales, en mitad de una
     * escritura a disco, y esperar aquí a una corrutina significaría bloquear
     * el hilo de E/S por un dato que ya está resuelto desde el arranque.
     */
    private fun formatoElegido(context: Context): CopyFormat =
        (context.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes?.copyFormat
            ?: CopyFormat.PNG

    /** El formato elegido, para quien tenga que anunciarlo (un `Intent`). */
    fun mimeElegido(context: Context): String = formatoElegido(context).mime

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * El recorte de la captura con lo dibujado encima, **con el motor**.
     *
     * Antes lo hacía `AnnotationRenderer.bake`, del motor viejo. Es el mismo
     * horneado que usa el pin ([PinExporter]): la escena está en píxeles de la
     * imagen, así que pintarla es zoom 1 y sin desplazamiento.
     *
     * Se pinta sobre la imagen **entera** y se recorta después, y no al revés,
     * por el mosaico: sus píxeles salen del fondo mirando las coordenadas de la
     * escena, y sobre un bitmap ya recortado estaría leyendo desplazado — o
     * sea, taparía una cosa con otra.
     */
    fun horneaCaptura(
        context: Context,
        original: Bitmap,
        escena: com.forge.pixpin.motor.Scene,
        recorte: android.graphics.Rect
    ): Bitmap {
        val completa = original.copy(Bitmap.Config.ARGB_8888, true)
        if (escena.visible.isNotEmpty()) {
            com.forge.pixpin.motor.Renderer(
                imageProvider = { id ->
                    escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) }
                },
                typefaces = com.forge.pixpin.motor.DrawFonts.provider(context),
                backdrop = completa
            ).renderScene(
                android.graphics.Canvas(completa),
                escena.copy(viewport = com.forge.pixpin.motor.Viewport()),
                completa.width.toDouble(),
                completa.height.toDouble()
            )
        }

        val x = recorte.left.coerceIn(0, (completa.width - 1).coerceAtLeast(0))
        val y = recorte.top.coerceIn(0, (completa.height - 1).coerceAtLeast(0))
        val ancho = recorte.width().coerceIn(1, completa.width - x)
        val alto = recorte.height().coerceIn(1, completa.height - y)
        val recortada = Bitmap.createBitmap(completa, x, y, ancho, alto)
        if (recortada !== completa && !completa.isRecycled) completa.recycle()
        return recortada
    }
}

/** Convierte el contenido de un pin en bitmap para guardarlo en galería. */
object PinExporter {

    fun savePin(context: Context, state: com.forge.pixpin.pin.PinState): Boolean {
        val bitmap = render(context, state) ?: return false
        val uri = Export.saveToGallery(context, bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
        return uri != null
    }

    /**
     * Copia el pin al portapapeles. Pasa por [render], así que lo dibujado
     * encima va incluido: antes se copiaba el archivo original tal cual y las
     * anotaciones se quedaban por el camino.
     */
    fun copyPin(context: Context, state: com.forge.pixpin.pin.PinState): Boolean {
        val bitmap = render(context, state) ?: return false
        val ok = Export.copyToClipboard(context, bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
        return ok
    }

    /**
     * Pinta lo dibujado **encima** de la foto y devuelve el resultado.
     *
     * La escena está en píxeles de la imagen, así que el encuadre es la
     * identidad: zoom 1 y sin desplazamiento. Es la ventaja de haber elegido ese
     * sistema de coordenadas — hornear no necesita ninguna conversión, y por eso
     * lo copiado sale exactamente como se veía en el pin.
     */
    private fun horneaDibujo(
        context: Context,
        original: Bitmap,
        escena: com.forge.pixpin.motor.Scene
    ): Bitmap {
        val salida = original.copy(Bitmap.Config.ARGB_8888, true)
        if (!original.isRecycled && salida !== original) original.recycle()
        com.forge.pixpin.motor.Renderer(
            imageProvider = { id ->
                escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) }
            },
            typefaces = com.forge.pixpin.motor.DrawFonts.provider(context),
            backdrop = salida
        ).renderScene(
            android.graphics.Canvas(salida),
            escena.copy(viewport = com.forge.pixpin.motor.Viewport()),
            salida.width.toDouble(),
            salida.height.toDouble()
        )
        return salida
    }

    private fun render(
        context: Context, state: com.forge.pixpin.pin.PinState
    ): Bitmap? = when (state.type) {
        com.forge.pixpin.pin.PinType.IMAGE ->
            state.imagePath?.let { com.forge.pixpin.pin.ImageStore.load(it) }?.let { bmp ->
                // Lo dibujado sobre el pin se hornea al exportar: hasta aquí eran
                // vectores encima de la imagen, re-editables.
                val escena = com.forge.pixpin.motor.ExcalidrawStore.cargar(
                    com.forge.pixpin.motor.ExcalidrawStore.rutaDe(context, state.id)
                )
                if (escena == null || escena.visible.isEmpty()) bmp
                else horneaDibujo(context, bmp, escena)
            }

        com.forge.pixpin.pin.PinType.COLOR -> {
            val argb = state.colorArgb ?: return null
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                eraseColor(argb)
            }
        }

        com.forge.pixpin.pin.PinType.TEXT -> renderText(state.text)

        com.forge.pixpin.pin.PinType.FILE -> renderText(state.fileName)

        // Las mini-aplicaciones se exportan por su contenido en texto: el
        // contador, su número; la lista y las cuentas, sus líneas. El
        // temporizador no tiene nada que valga la pena guardar en la galería.
        com.forge.pixpin.pin.PinType.COUNTER -> renderText("${state.widget.count}")
        com.forge.pixpin.pin.PinType.CHECKLIST,
        com.forge.pixpin.pin.PinType.LEDGER,
        com.forge.pixpin.pin.PinType.TABLE -> renderText(state.text)
        com.forge.pixpin.pin.PinType.TIMER -> null

        // El croquis se retiró: los pines de ese tipo se descartan al cargar
        // la lista, así que aquí no puede llegar ninguno. La rama existe porque
        // el valor sigue en el enum para que los datos viejos no revienten.
        com.forge.pixpin.pin.PinType.CROQUIS -> null

        // El dibujo, igual: su propio renderizador, el mismo que pinta la
        // pantalla, para que lo copiado coincida con lo que se ve.
        // Con la ruta deducida del id como respaldo: un pin que no llegó a
        // guardarse la ruta devolvía null y la copia salía vacía.
        com.forge.pixpin.pin.PinType.DRAW ->
            com.forge.pixpin.motor.ExcalidrawStore.cargar(
                state.drawPath
                    ?: com.forge.pixpin.motor.ExcalidrawStore.rutaDe(context, state.id)
            )?.let { escena ->
                com.forge.pixpin.motor.DrawExport.aBitmap(escena) { id ->
                    escena.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) }
                }
            }
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
