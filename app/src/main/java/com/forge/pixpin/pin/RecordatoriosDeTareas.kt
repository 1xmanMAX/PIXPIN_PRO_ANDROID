package com.forge.pixpin.pin

import android.content.Context
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.mini.AlarmasDeTareas
import java.io.File

/**
 * **Las alarmas de las tareas con hora** (ver [AlarmasDeTareas]): con el mismo despertador que
 * los recordatorios de los mensajes ([Recordatorios]). Se rehacen enteras cada vez que cambia el
 * chat —aquí, al sincronizar o al llegar del PC— y al arrancar la app (también tras reiniciar el
 * teléfono): las que sobran se quitan, las nuevas se ponen. Trabajo de disco.
 */
object RecordatoriosDeTareas {

    private const val FICHERO = "alarmas-de-tareas.txt"
    private val cerrojo = Any()

    fun reprogramar(context: Context, ahora: Long = System.currentTimeMillis()): Unit = synchronized(cerrojo) {
        val app = context.applicationContext
        val alarmas = runCatching { AlarmasDeTareas.deLosMensajes(MensajesStore(app).leer(), ahora) }.getOrNull() ?: return
        val apuntadas = File(app.filesDir, FICHERO)
        val antes = runCatching { apuntadas.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        val ahoraIds = alarmas.map { it.id }.toSet()
        (antes - ahoraIds).forEach { Recordatorios.quitar(app, it) }
        // Poner otra vez una que ya estaba la deja igual (el mismo id la cambia, no la duplica).
        alarmas.filter { it.id !in antes }.forEach { Recordatorios.poner(app, it.id, it.cuando) }
        runCatching { apuntadas.writeText(ahoraIds.joinToString("\n")) }
        Unit
    }

    /**
     * **Sonó una**: se quita su hora de la tarea (como hace el PC, y lo quitado viaja) y se enseña
     * encima de todo, como cualquier recordatorio de PixPin.
     */
    fun sonar(context: Context, id: String) {
        val app = context.applicationContext as? PixPinApp ?: return
        val (mensaje, hora) = AlarmasDeTareas.deId(id) ?: return
        var texto: String? = null
        runCatching {
            MensajesStore(app).cambiar { todos ->
                todos.map { m ->
                    if (m.id != mensaje) m
                    else AlarmasDeTareas.sono(m.texto, hora)?.let { (doc, t) -> texto = t; m.copy(texto = doc) } ?: m
                }
            }
        }
        texto?.let { app.overlayManager.pinTexto("☑ $it") }
    }
}
