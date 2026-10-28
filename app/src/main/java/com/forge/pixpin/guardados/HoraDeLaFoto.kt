package com.forge.pixpin.guardados

import android.media.ExifInterface

/**
 * **La hora de una foto que no hizo PixPin** (28-oct-2026, §12.2 de `docs/movil-como-camara.md` del
 * PC): una foto de la galería no trae `tomadaEn`, pero su EXIF dice cuándo se hizo
 * (`DateTimeOriginal`, `"2026:10:28 10:05:03"`) y en qué zona (`OffsetTimeOriginal`, `"+01:00"`). Se
 * copian tal cual en `tomadaEnExif` y `desfaseExif`; quien graba los pasa a ms con
 * [PorSuHora.msDeExif]. Sin zona no se copia: una hora local sin zona no se puede poner en la de pared.
 */
object HoraDeLaFoto {

    /** La fecha y la zona del EXIF de [ruta], o null si no las trae (o no es una foto con EXIF). */
    fun delExif(ruta: String): Pair<String, String>? = runCatching {
        val e = ExifInterface(ruta)
        val f = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val z = (e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_OFFSET_TIME))
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (PorSuHora.msDeExif(f, z) == null) null else f to z
    }.getOrNull()

    /** [m] con la hora del EXIF puesta, si es una foto sin hora y su fichero la trae. */
    fun conHora(m: Mensaje): Mensaje {
        if (m.clase != Clase.IMAGEN || m.tomadaEn != null || m.tomadaEnExif != null) return m
        val ruta = m.ruta?.takeIf { java.io.File(it).isFile } ?: return m
        val (f, z) = delExif(ruta) ?: return m
        return m.copy(tomadaEnExif = f, desfaseExif = z)
    }
}
