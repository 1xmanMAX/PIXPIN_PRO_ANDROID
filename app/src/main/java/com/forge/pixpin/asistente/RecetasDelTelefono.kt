package com.forge.pixpin.asistente

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Automatizaciones
import java.io.File

/**
 * **El fichero de las automatizaciones en el teléfono** (§3.5): `files/guardados/asistente/
 * automatizaciones.json`, el mismo que escribe el PC, señalado por UN mensaje `ARCHIVO` de id fijo
 * `auto-automatizaciones` en el chat «Asistente» para que viaje. Se lee, se cambia y se escribe
 * enseguida con un cerrojo (para no pisar una edición del otro aparato), conservando lo que no se
 * conoce. Lo que ya corrió no viaja.
 */
object RecetasDelTelefono {
    /** Sube al cambiar (la fila de recetas se vuelve a leer). */
    val revision = mutableIntStateOf(0)

    private fun fichero(c: Context) = File(c.filesDir, Automatizaciones.RUTA)

    @Synchronized
    fun leer(c: Context): Automatizaciones.Lista {
        val f = fichero(c)
        if (!f.isFile) return Automatizaciones.Lista()
        return runCatching { Automatizaciones.Lista.deJson(f.readText()) }.getOrElse { Automatizaciones.Lista() }
    }

    /** **Lee, cambia y escribe** con el cerrojo; asegura el mensaje que lo hace viajar. */
    @Synchronized
    fun <R> cambiar(c: Context, cambio: (Automatizaciones.Lista) -> R): R {
        val f = fichero(c)
        val l = if (f.isFile) Automatizaciones.Lista.deJson(f.readText()) else Automatizaciones.Lista()
        val r = cambio(l)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(l.aJson())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        asegurarMensaje(c)
        revision.intValue++
        return r
    }

    private fun asegurarMensaje(c: Context) {
        val almacen = MensajesStore(c)
        if (almacen.leer().any { it.id == Automatizaciones.ID_MENSAJE }) return
        val f = fichero(c)
        val chat = Asistente.asegurarChat(c)
        almacen.anadir(
            Mensaje(
                id = Automatizaciones.ID_MENSAJE, cuando = System.currentTimeMillis(), clase = Clase.ARCHIVO,
                ruta = f.absolutePath, nombre = "automatizaciones.json", bytes = f.length(),
                texto = Automatizaciones.TEXTO_DEL_MENSAJE, proyecto = chat.id
            )
        )
    }
}
