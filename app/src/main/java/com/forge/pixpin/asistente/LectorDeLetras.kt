package com.forge.pixpin.asistente

import android.graphics.Bitmap
import com.forge.pixpin.ia.Calcar
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * **Las palabras de una imagen y dónde están** (lo que el PC lee con el OCR de Windows), para que al
 * calcar un dibujo las letras salgan como texto y no como rayas. ML Kit con el modelo latino dentro
 * del APK: sin internet ni servicios de Google. Las cajas, en píxeles del mapa.
 *
 * Si no se puede leer (o tarda), lista vacía: el calco sigue y las letras salen como trazos.
 */
object LectorDeLetras {
    fun leer(mapa: Bitmap): List<Calcar.Palabra> = runCatching {
        val lector = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val texto = Tasks.await(lector.process(InputImage.fromBitmap(mapa, 0)), 30, TimeUnit.SECONDS)
            texto.textBlocks.flatMap { it.lines }
                // Las torcidas no: un texto del lienzo va recto y saldría en otro sitio.
                .filter { kotlin.math.abs(it.angle) <= 8f }
                .flatMap { it.elements }
                .mapNotNull { w ->
                    val c = w.boundingBox ?: return@mapNotNull null
                    if (w.text.isBlank()) null
                    else Calcar.Palabra(w.text, c.left.toDouble(), c.top.toDouble(), c.width().toDouble(), c.height().toDouble())
                }
        } finally {
            lector.close()
        }
    }.getOrDefault(emptyList())
}
