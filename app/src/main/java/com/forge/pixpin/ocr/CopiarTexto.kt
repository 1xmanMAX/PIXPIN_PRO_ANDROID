package com.forge.pixpin.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.forge.pixpin.ocr.indra.MotorIndra
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * **«Texto» de la captura y «Copiar texto» del pin** (26-oct-2026, PC f4d8926 / c0632e7 / 8efde62:
 * `copiar_texto_aparte` y `pines::leer_texto`): leer el texto de una imagen con Indra **en un hilo
 * propio**, decir «Leyendo…» mientras tanto y dejarlo en el portapapeles al acabar.
 *
 * **Lo que llega tarde se tira**, como en el PC: cada pedido lleva su boleto y, si entretanto se
 * pidió otro, el viejo no copia nada (de dos capturas de texto seguidas solo se copia la última);
 * y quien pide puede decir además si su resultado sigue valiendo ([pedir] `sigueValiendo`: el pin
 * se cerró o cambió de imagen → no). Leer una imagen cuesta de 0,3 a 1,5 s en un móvil.
 */
object CopiarTexto {
    private val boleto = AtomicLong()
    /** Uno a la vez: Indra lee de uno en uno (su cerrojo) y así el orden es el de los pedidos. */
    private val hilo by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "copiar-texto") } }
    private val principal by lazy { Handler(Looper.getMainLooper()) }

    /** Lo que se dice al acabar (los textos del PC, `pedido-ocr-copiado` / `pedido-ocr-sin-texto`). Puro. */
    fun aviso(texto: String?): String = when {
        texto == null -> "No se pudo leer el texto de la imagen."
        texto.isBlank() -> "No se encontró texto en la imagen."
        else -> {
            val corto = texto.replace('\n', ' ').let { if (it.length > 60) it.take(57).trimEnd() + "…" else it }
            "Texto copiado (${texto.length} caracteres): «$corto»"
        }
    }

    /** Si el resultado del pedido [mio] vale todavía: es el último pedido y quien lo pidió sigue ahí. Puro. */
    fun vale(mio: Long, ultimo: Long, sigueValiendo: Boolean): Boolean = mio == ultimo && sigueValiendo

    /**
     * **Lee [imagen] y copia su texto**, en el formato del PC ([ParrafosOcr]). [imagen] pasa a ser
     * de este pedido: se recicla al acabar. [alAcabar] (en el hilo principal) recibe el texto (null
     * si no se pudo) y si valió; si llegó tarde no se copió nada. Devuelve el boleto.
     */
    fun pedir(
        context: Context,
        imagen: Bitmap,
        sigueValiendo: () -> Boolean = { true },
        alAcabar: (texto: String?, valio: Boolean) -> Unit = { _, _ -> }
    ): Long {
        val app = context.applicationContext
        val mio = boleto.incrementAndGet()
        Toast.makeText(app, "Leyendo…", Toast.LENGTH_SHORT).show()
        hilo.execute {
            val texto = try {
                if (boleto.get() != mio) null else MotorIndra.texto(app, imagen)
            } finally {
                if (!imagen.isRecycled) imagen.recycle()
            }
            principal.post {
                if (!vale(mio, boleto.get(), sigueValiendo())) { alAcabar(texto, false); return@post }
                if (!texto.isNullOrBlank()) {
                    app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pixpin", texto))
                }
                Toast.makeText(app, aviso(texto), Toast.LENGTH_SHORT).show()
                alAcabar(texto, true)
            }
        }
        return mio
    }
}
