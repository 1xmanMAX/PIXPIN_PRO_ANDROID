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
 * Si no está instalada, PinDesk sigue con lo de siempre ([SincronizarSola]).
 */
object LetraSyncPro {
    const val PAQUETE = "com.letrasyncpro"
    const val ACCION = "com.letrasyncpro.action.SINCRONIZAR"
    const val EXTRA_LETRA = "com.letrasyncpro.extra.LETRA"
    const val EXTRA_LRC = "com.letrasyncpro.extra.LRC"
    const val EXTRA_TITULO = "com.letrasyncpro.extra.TITULO"
    const val EXTRA_QUIEN_LLAMA = "com.letrasyncpro.extra.CALLER_LABEL"

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
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(audio.name, uri) }
    }

    /** La letra sincronizada que volvió, o null (se canceló o no trae nada). */
    fun letraDe(datos: Intent?): String? = datos?.getStringExtra(EXTRA_LETRA)?.takeIf { it.isNotBlank() }
}
