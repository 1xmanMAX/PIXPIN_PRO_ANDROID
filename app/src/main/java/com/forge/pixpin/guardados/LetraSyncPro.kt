package com.forge.pixpin.guardados

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

/**
 * **Sincronizar la letra de una canción con «LetraSync Pro»** (6-nov-2026, el usuario: «que la función de alinear las
 * músicas con sus letras se separe de la app y se cree una nueva que se especialice en eso, integrada como el
 * escáner»). LetraSync Pro (`com.letrasyncpro`) es otra aplicación suya y aquí se usa como «Escáner Pro Max»: se le
 * manda la canción y su letra, y vuelve la letra con sus tiempos en el mismo `[m:ss] línea` de siempre, que el PC
 * canta igual. Su contrato, en `docs/integracion.md` de su repositorio.
 *
 * **Desde el 6-nov-2026 PinDesk no sincroniza letras por su cuenta**: sin LetraSync Pro, se dice dónde bajarla.
 */
object LetraSyncPro {
    const val PAQUETE = "com.letrasyncpro"
    const val ACCION = "com.letrasyncpro.action.SINCRONIZAR"
    const val EXTRA_LETRA = "com.letrasyncpro.extra.LETRA"
    const val EXTRA_LRC = "com.letrasyncpro.extra.LRC"
    const val EXTRA_TITULO = "com.letrasyncpro.extra.TITULO"
    const val EXTRA_QUIEN_LLAMA = "com.letrasyncpro.extra.CALLER_LABEL"
    /**
     * El id de la canción en PinDesk (7-nov-2026). LetraSync Pro la guarda en su biblioteca («de PinDesk») y, si luego
     * se retoca allí, manda la letra de vuelta con [ACCION_DEVUELTA] y este id: llega sola a esta canción.
     */
    const val EXTRA_ID = "com.letrasyncpro.extra.ID"
    /** Lo que manda LetraSync Pro al retocar desde su biblioteca una canción de PinDesk (ver [LetraDevuelta]). */
    const val ACCION_DEVUELTA = "com.forge.pixpin.action.LETRA_SINCRONIZADA"
    /** Dónde se baja (sus versiones, como las de PinDesk). */
    const val ENLACE = "https://github.com/1xmanMAX/letrasync-pro-android/releases/latest"

    fun instalada(c: Context): Boolean = runCatching {
        c.packageManager.getPackageInfo(PAQUETE, 0); true
    }.getOrElse { it !is PackageManager.NameNotFoundException && false }

    /** La petición para la canción [m] (su audio y su letra); null si no tiene audio. */
    fun peticion(c: Context, m: Mensaje, titulo: String?): Intent? {
        val audio = m.ruta?.let { File(it) }?.takeIf { it.isFile } ?: return null
        val uri = com.forge.pixpin.ui.AbrirCon.uriDe(c, audio)
        return Intent(ACCION).setPackage(PAQUETE)
            .setDataAndType(uri, "audio/*")
            .putExtra(EXTRA_LETRA, m.transcripcion.orEmpty())
            .putExtra(EXTRA_TITULO, titulo ?: m.nombre)
            .putExtra(EXTRA_QUIEN_LLAMA, "PinDesk")
            .putExtra(EXTRA_ID, m.id)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(audio.name, uri) }
    }

    /** La letra sincronizada que volvió, o null (se canceló o no trae nada). */
    fun letraDe(datos: Intent?): String? = datos?.getStringExtra(EXTRA_LETRA)?.takeIf { it.isNotBlank() }
}

/**
 * **La letra que llega de LetraSync Pro sin haberla pedido ahora** (7-nov-2026): una canción de PinDesk que se retocó
 * desde la biblioteca de LetraSync. Solo se acepta para una canción que existe, es de esta app y tiene audio, y
 * solo cambia su letra (lo mismo que al volver de «Sincronizar la letra»).
 */
class LetraDevuelta : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LetraSyncPro.ACCION_DEVUELTA) return
        val id = intent.getStringExtra(LetraSyncPro.EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return
        val letra = intent.getStringExtra(LetraSyncPro.EXTRA_LETRA)?.takeIf { it.isNotBlank() && it.length < 200_000 } ?: return
        val pendiente = goAsync()
        Thread {
            try {
                val almacen = MensajesStore(context)
                val m = almacen.leer().firstOrNull { it.id == id }
                if (m != null && m.clase == Clase.VOZ && m.ruta != null && m.transcripcion != letra) {
                    almacen.actualizar(id) { it.copy(transcripcion = letra, estadoDelTexto = TEXTO_LETRA) }
                }
            } finally { pendiente.finish() }
        }.start()
    }
}
