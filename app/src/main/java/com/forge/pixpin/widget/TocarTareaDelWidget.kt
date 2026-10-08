package com.forge.pixpin.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * **Lo que se toca en una fila del widget de tareas**: la casilla (marcar) o el texto (abrir
 * Tareas). Aparte del proveedor y **sin exportar**: el proveedor tiene que estar exportado para
 * que el lanzador lo llame, y entonces cualquier aplicación podría mandarle «marca esta tarea».
 * A este solo llega la plantilla de PixPin.
 */
class TocarTareaDelWidget : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetDeTareas.ACCION_FILA) return
        val codigo = intent.getStringExtra(WidgetDeTareas.EXTRA_CODIGO)
        val crudo = intent.getStringExtra(WidgetDeTareas.EXTRA_CRUDO)
        val indice = intent.getIntExtra(WidgetDeTareas.EXTRA_INDICE, -1)
        if (intent.getStringExtra(WidgetDeTareas.EXTRA_QUE) == WidgetDeTareas.QUE_ABRIR || codigo == null || crudo == null || indice < 0) {
            WidgetDeTareas.abrirTareas(context)
            return
        }
        // Escribir el chat es disco: fuera del hilo principal, con el receptor vivo hasta que acabe.
        val pendiente = goAsync()
        val app = context.applicationContext as PixPinApp
        app.scope.launch(Dispatchers.IO) {
            try {
                val almacen = MensajesStore(app)
                var marcada = false
                almacen.cambiar { todos ->
                    LogicaDeLosWidgets.marcarEn(todos, codigo, indice, crudo)?.also { marcada = true } ?: todos
                }
                if (!marcada) Log.i("PixPin", "widget de tareas: la lista cambió; se repinta sin marcar")
                // Sin esperar al aviso común (que va con un respiro): la casilla se va en el acto.
                WidgetDeTareas.refrescar(app)
            } catch (e: Exception) {
                Log.w("PixPin", "widget de tareas: no se pudo marcar", e)
            } finally {
                pendiente.finish()
            }
        }
    }
}
