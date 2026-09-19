package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * **Un PDF de lo que pinta un `WebView`, sin pasar por el diálogo de imprimir** (19-sep-2026).
 *
 * Android solo ofrece imprimir una página web a través de su servicio de impresión, con su
 * diálogo; para tener el PDF en un archivo —la «vista de impresión» de un Word, o meterlo en un
 * proyecto como cualquier PDF— hay que hablarle al `PrintDocumentAdapter` a mano. Y sus dos
 * *callbacks* tienen el constructor reservado a su paquete: por eso este archivo, y solo este,
 * **vive en `android.print`**. Es el truco de siempre para esto; si algún Android lo cerrara, el
 * fallo se recoge y se dice (ver quien llama), no se cae nada.
 */
object PdfDesdeWeb {
    /** Escribe en [destino] lo que entrega [adaptador]. [alAcabar] llega en el hilo de la interfaz. */
    fun escribir(adaptador: PrintDocumentAdapter, atributos: PrintAttributes, destino: File, alAcabar: (Boolean) -> Unit) {
        runCatching {
            adaptador.onLayout(null, atributos, null, object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    val salida = runCatching {
                        ParcelFileDescriptor.open(
                            destino,
                            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE
                        )
                    }.getOrNull()
                    if (salida == null) { alAcabar(false); return }
                    adaptador.onWrite(arrayOf(PageRange.ALL_PAGES), salida, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>?) {
                            runCatching { salida.close() }
                            alAcabar(destino.length() > 0)
                        }

                        override fun onWriteFailed(error: CharSequence?) {
                            runCatching { salida.close() }
                            alAcabar(false)
                        }
                    })
                }

                override fun onLayoutFailed(error: CharSequence?) = alAcabar(false)
            }, null)
        }.onFailure { alAcabar(false) }
    }
}
