package com.forge.pixpin.guardados

import android.app.Activity
import android.widget.Toast
import com.forge.pixpin.R
import com.forge.pixpin.sincro.FotosPorSuHora
import com.forge.pixpin.sincro.Red

/**
 * **Lo que hace el menú de un mensaje con las fotos por su hora** (28-oct-2026, §12 y §14 de la guía
 * del PC): invitar a todos los del chat a aportar fotos a una clase ([alternarTodos]) y poner a mano
 * una foto en la grabación del chat donde cae su hora, o en la última ([adjuntar]). Fuera del hilo de
 * la pantalla; lo escrito viaja como cualquier cambio del chat.
 */
object FotosALaGrabacion {

    /** «Que todos los del chat aporten fotos» o «Solo mis fotos en esta grabación», según como esté. */
    fun alternarTodos(a: Activity, voz: Mensaje) {
        val si = !PorSuHora.abiertaATodos(voz)
        Thread {
            val bien = runCatching { FotosPorSuHora.invitarATodos(Red.disco(a), voz.proyecto, voz.id, si) }.getOrDefault(false)
            a.runOnUiThread {
                if (bien) Toast.makeText(a, if (si) R.string.clase_todos_aportan_hecho else R.string.clase_solo_mis_fotos_hecho, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** «Adjuntar a la grabación»: la foto en su minuto (o al final), en la grabación más a mano del chat. */
    fun adjuntar(a: Activity, foto: Mensaje) {
        Thread {
            val r = runCatching { FotosPorSuHora.aManoLaMejor(Red.disco(a), foto.proyecto, foto.id) }.getOrNull()
            a.runOnUiThread {
                val texto = if (r == null) a.getString(R.string.foto_sin_grabaciones)
                else a.getString(R.string.foto_adjuntada_a_la_grabacion, r.first, GrabarClase.minuto(r.second))
                Toast.makeText(a, texto, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
