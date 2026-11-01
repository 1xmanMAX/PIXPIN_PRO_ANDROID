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
        // Las tareas que llaman, de listas que ya no están, se olvidan (1-nov-2026).
        runCatching { com.forge.pixpin.mini.LlamadasGuardadas.olvidarLasDe(app, MensajesStore(app).leer().mapTo(HashSet()) { it.id }) }
        Unit
    }

    /**
     * **Sonó una**: se quita su hora de la tarea (como hace el PC, y lo quitado viaja) y se enseña
     * encima de todo, como cualquier recordatorio de PixPin.
     */
    fun sonar(context: Context, id: String) {
        val app = context.applicationContext as? PixPinApp ?: return
        val (mensaje, hora) = AlarmasDeTareas.deId(id) ?: return
        var sonada: AlarmasDeTareas.Sonada? = null
        var lista = ""
        runCatching {
            MensajesStore(app).cambiar { todos ->
                todos.map { m ->
                    if (m.id != mensaje) m
                    else AlarmasDeTareas.sonada(m.texto, hora)?.let { s ->
                        sonada = s; lista = com.forge.pixpin.mini.TodasLasTareas.nombreDeLista(m); m.copy(texto = s.documento)
                    } ?: m
                }
            }
        }
        val s = sonada ?: return
        // **Con la llamada puesta, suena como una llamada** (1-nov-2026, [com.forge.pixpin.mini.LlamadasDeTareas]):
        // la llamada secreta, que al contestar lee la tarea; «Volver a llamar en…» le vuelve a poner la hora.
        val llama = runCatching { com.forge.pixpin.mini.LlamadasDeTareas.llama(com.forge.pixpin.mini.LlamadasGuardadas.tareas(app), mensaje, s.crudo) }
            .getOrDefault(false)
        val llamo = llama && runCatching {
            LlamadaSecretaActivity.llamar(
                app, null, s.dicho, com.forge.pixpin.mini.LlamadasDeTareas.idDeLlamada(mensaje, s.crudo),
                detalle = "${com.forge.pixpin.mini.LlamadasDeTareas.TELEFONO} Tarea · ${lista.ifBlank { "Tareas" }}", texto = "Recuerda: ${s.dicho}"
            )
        }.isSuccess
        if (!llamo) app.overlayManager.pinTexto("☑ ${s.dicho}")
    }
}
