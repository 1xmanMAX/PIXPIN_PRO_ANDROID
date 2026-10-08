package com.forge.pixpin.widget

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.guardados.MensajesStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * **Los widgets, al día sin que nadie se lo diga** (8-oct-2026). Un widget no ve nada: hay que
 * avisarle. Se le avisa desde los puntos por donde ya pasa todo cambio, para no tener que acordarse
 * en cada pantalla:
 *
 * - **Tareas**: [MensajesStore.cambios], que sube con cada escritura del chat (marcar en la app o
 *   en el chat, apuntar en el Inbox, lo que trae la sincronización o el PC), y la lista de
 *   proyectos (un proyecto renombrado cambia el nombre pequeño de sus filas).
 * - **Galería**: el `MediaStore` de imágenes y vídeos (una captura nueva, lo que el barrendero
 *   manda a la papelera, lo que se borra desde la galería) y [CaducidadDeCapturas.cambios]
 *   (conservar, «Dar 7 días más»).
 *
 * Con un respiro ([RESPIRO_MS]): guardar escribe varias veces seguidas, y una ráfaga de fotos de
 * la cámara no debe releer la galería por cada una. Avisar es barato si no hay ningún widget
 * puesto (ver [WidgetDeTareas.refrescar]). Con la aplicación cerrada no hay quien avise: para eso
 * está la media hora de `updatePeriodMillis`; y al arrancar la aplicación se avisa una vez, por si
 * algo cambió mientras el widget no miraba.
 */
object AvisoDeWidgets {

    const val RESPIRO_MS = 400L

    @OptIn(FlowPreview::class)
    fun vigilar(app: PixPinApp) {
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            combine(app.proyectos.proyectos, MensajesStore.cambios) { _, v -> v }
                .debounce(RESPIRO_MS)
                .collect { WidgetDeTareas.refrescar(app) }
        }

        // El observador cuenta en un flujo y el flujo avisa con su respiro.
        val galeria = MutableStateFlow(0L)
        val observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { galeria.value = galeria.value + 1 }
        }
        runCatching {
            app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observador)
            app.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observador)
        }
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            combine(galeria, CaducidadDeCapturas.cambios) { a, b -> a + b }
                .debounce(RESPIRO_MS * 3)
                .collect { WidgetDeGaleria.refrescar(app) }
        }
    }
}
