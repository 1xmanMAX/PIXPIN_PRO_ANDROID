package com.forge.pixpin.pin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.forge.pixpin.PixPinApp

/**
 * Lo que pasa cuando llega la hora: **el pin aparece y se oye**.
 *
 * No una notificación. Una notificación es una fila más en una lista que uno ya ignora, y
 * lo que se pidió fue «que me lo recuerdes», no «que me lo apuntes». El pin vuelve a la
 * pantalla, encima de lo que sea que se esté haciendo, y dice lo que tenga que decir.
 */
class RecordatorioReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Lo que se le pone delante al identificador cuando quien avisa es una mini-app.
         *
         * Con un prefijo y no con otro extra en el intento porque la alarma ya guardada
         * en el sistema lleva solo este campo: añadir otro dejaría sin efecto las alarmas
         * puestas antes de actualizar, que es justo las que ya están esperando.
         */
        const val DE_UNA_MINIAPP = "mini:"

        /** Y el que lleva un mensaje guardado al que se le puso hora. Ver `Mensaje.recuerdaEn`. */
        const val DE_UN_MENSAJE = "msg:"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Recordatorios.ACCION) return
        val pinId = intent.getStringExtra(Recordatorios.EXTRA_PIN) ?: return
        val app = context.applicationContext as? PixPinApp ?: return
        // **Una mini-app no es un pin**, así que no hay ninguno al que hacer sonar: lo
        // que hay es un temporizador o una alarma dentro de la conversación. Se saca su
        // texto a la pantalla en ese momento, que es lo mismo que hace un recordatorio
        // normal —aparecer encima de lo que estés haciendo— sin inventar otro camino.
        if (pinId.startsWith(DE_UNA_MINIAPP)) {
            val id = pinId.removePrefix(DE_UNA_MINIAPP)
            val mensaje = runCatching {
                com.forge.pixpin.guardados.MensajesStore(context).leer()
                    .firstOrNull { it.id == id }
            }.getOrNull() ?: return
            val titulo = com.forge.pixpin.mini.Cabecera.titulo(mensaje.texto)
                .ifBlank { mensaje.nombre }
            app.overlayManager.pinTexto(titulo)
            return
        }
        // **Un mensaje guardado al que se le puso hora.** Lo que se pidió fue «recuérdamelo»,
        // así que aparece en la pantalla como cualquier otro recordatorio —encima de lo que
        // se esté haciendo— con lo que decía, y se le quita la hora para que no quede una
        // alarma fantasma en la conversación. Ver `MensajesActivity.ponerRecordatorio`.
        // **Una tarea con hora** (`⏰` en su texto, como el PC). Ver [RecordatoriosDeTareas].
        if (pinId.startsWith(com.forge.pixpin.mini.AlarmasDeTareas.PREFIJO)) {
            RecordatoriosDeTareas.sonar(context, pinId)
            return
        }
        if (pinId.startsWith(DE_UN_MENSAJE)) {
            val id = pinId.removePrefix(DE_UN_MENSAJE)
            val almacen = com.forge.pixpin.guardados.MensajesStore(context)
            val mensaje = runCatching { almacen.leer().firstOrNull { it.id == id } }.getOrNull() ?: return
            runCatching { almacen.actualizar(id) { it.copy(recuerdaEn = null) } }
            // **Una nota de voz con hora es una llamada secreta**: suena como una llamada y el
            // recado se oye por el auricular. Ver [LlamadaSecretaActivity].
            val grabacion = mensaje.ruta?.takeIf { mensaje.clase == com.forge.pixpin.guardados.Clase.VOZ && java.io.File(it).exists() }
            if (grabacion != null) {
                // Quien «llama»: el nombre que se le puso al programarla; si no, el de la nota.
                val quien = LlamadaSecretaActivity.quienLlama(context, mensaje.id)
                    ?: mensaje.nombre.substringBeforeLast('.').ifBlank { "Llamada" }
                val abierta = runCatching { LlamadaSecretaActivity.llamar(context, grabacion, quien, mensaje.id) }.isSuccess
                if (abierta) return
            }
            // **Con su archivo** (como el PC desde el 8-oct-2026): si el mensaje es una foto o un
            // documento, sale a la pantalla también eso, como pin, junto al recado. El pin borra
            // su archivo al cerrarse, así que se le da una copia y no el del chat.
            val archivo = mensaje.ruta?.let { java.io.File(it) }?.takeIf {
                it.isFile && (mensaje.clase == com.forge.pixpin.guardados.Clase.IMAGEN || mensaje.clase == com.forge.pixpin.guardados.Clase.ARCHIVO)
            }
            val texto = mensaje.texto.ifBlank { if (archivo != null) "" else mensaje.nombre }
                .ifBlank { if (archivo != null) "" else context.getString(com.forge.pixpin.R.string.guardados_titulo) }
            if (texto.isNotBlank()) app.overlayManager.pinTexto(texto)
            if (archivo != null) {
                val pendiente = goAsync()
                Thread {
                    try {
                        val copia = ArchivoDelRecordatorio.copiar(context, archivo, mensaje.nombre, mensaje.clase == com.forge.pixpin.guardados.Clase.IMAGEN)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            when {
                                copia == null -> app.overlayManager.pinTexto(mensaje.nombre.ifBlank { archivo.name })
                                copia.esImagen -> app.overlayManager.pinImage(copia.ruta)
                                else -> app.overlayManager.pinFile(copia.ruta, copia.nombre, copia.mime)
                            }
                        }
                    } finally {
                        pendiente.finish()
                    }
                }.start()
            }
            return
        }
        app.overlayManager.sonarRecordatorio(pinId)
    }
}

/** La copia del archivo de un recordatorio que se le da al pin (el pin borra la suya al cerrarse). */
object ArchivoDelRecordatorio {
    class Copia(val ruta: String, val nombre: String, val mime: String, val esImagen: Boolean)

    fun copiar(context: Context, archivo: java.io.File, nombre: String, imagen: Boolean): Copia? = runCatching {
        if (imagen) {
            ImageStore.importFromUri(context, android.net.Uri.fromFile(archivo))?.let { return Copia(it, nombre, "image/png", true) }
        }
        val visible = nombre.ifBlank { archivo.name }
        val extension = visible.substringAfterLast('.', archivo.extension).lowercase()
        val carpeta = java.io.File(context.filesDir, "pins/files").apply { mkdirs() }
        val seguro = visible.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(50).ifBlank { "archivo" }
        val destino = java.io.File(carpeta, "${System.currentTimeMillis()}_$seguro" + if (extension.isNotBlank()) ".$extension" else "")
        archivo.copyTo(destino, overwrite = true)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: com.forge.pixpin.data.TiposDeArchivo.principal(visible) ?: "application/octet-stream"
        Copia(destino.absolutePath, visible, mime, false)
    }.getOrNull()
}
