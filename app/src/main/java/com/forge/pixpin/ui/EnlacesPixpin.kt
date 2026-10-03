package com.forge.pixpin.ui

import android.content.Context
import android.widget.Toast
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.HojasDelProyecto
import com.forge.pixpin.motormd.Incrustados
import com.forge.pixpin.sincro.Codigos

/**
 * **Los enlaces de PixPin dentro de una nota** (los escribe el PC desde el 30-sep-2026):
 * `pixpin:hoja=<proyecto>/<hoja>` abre esa hoja y `pixpin:mensaje=<proyecto>/<mensaje>` abre su
 * chat en ese mensaje. Van con **códigos únicos**, los mismos en los dos aparatos. Sin esto
 * Android intentaba abrir el esquema como una dirección y no encontraba aplicación.
 */
object EnlacesPixpin {

    /** Abre [url] si es un enlace de PixPin. Devuelve si lo era (aunque lo enlazado ya no esté). */
    fun abrir(context: Context, url: String): Boolean {
        val e = Incrustados.enlace(url) ?: return false
        val app = context.applicationContext as? PixPinApp ?: return true
        when (e) {
            is Incrustados.Enlace.Hoja -> {
                val h = PaginasVivas.hoja(app, e.hoja, e.proyecto)
                if (h == null) aviso(context, "La hoja ya no está")
                else abrirHoja(context, app, h.proyecto, HojasDelProyecto.Pagina(h.hoja))
            }
            is Incrustados.Enlace.Mensaje -> {
                val proyecto = app.proyectos.proyectos.value.firstOrNull { it.id == e.proyecto || Codigos.unico(it) == e.proyecto }
                Thread {
                    val m = runCatching {
                        com.forge.pixpin.guardados.MensajesStore(app).leer().firstOrNull { it.id == e.mensaje || Codigos.unico(it) == e.mensaje }
                    }.getOrNull()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (m == null) aviso(context, "El mensaje ya no está")
                        else com.forge.pixpin.guardados.MensajesActivity.irAlMensaje(
                            context, m.proyecto, proyecto?.nombre ?: "", m.id
                        )
                    }
                }.start()
            }
        }
        return true
    }

    private fun aviso(context: Context, texto: String) =
        Toast.makeText(context, texto, Toast.LENGTH_SHORT).show()
}
