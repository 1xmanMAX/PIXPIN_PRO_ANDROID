package com.forge.pixpin.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.mini.TodasLasTareasActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * **Lo que se toca en una fila del widget de tareas**: la casilla (marcar) o el texto (abrir
 * Tareas).
 *
 * Es una actividad transparente y sin pantalla, no un receptor: desde Android 10 un receptor no
 * puede abrir pantallas por su cuenta (solo con «Mostrar sobre otras apps»), y entonces tocar el
 * texto de una tarea no hacía nada. Una actividad que lanza el toque del usuario en el widget sí
 * puede. Marcar no enseña nada: se apunta fuera del hilo principal y se cierra en el acto.
 *
 * **Sin exportar**: el proveedor tiene que estar exportado para que el lanzador lo llame, y
 * entonces cualquier aplicación podría pedirle «marca esta tarea». Aquí solo llega la plantilla
 * de PixPin.
 */
class TocarTareaDelWidget : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        atender(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun atender(intent: Intent) {
        if (intent.action != WidgetDeTareas.ACCION_FILA) return
        val codigo = intent.getStringExtra(WidgetDeTareas.EXTRA_CODIGO)
        val crudo = intent.getStringExtra(WidgetDeTareas.EXTRA_CRUDO)
        val indice = intent.getIntExtra(WidgetDeTareas.EXTRA_INDICE, -1)
        if (intent.getStringExtra(WidgetDeTareas.EXTRA_QUE) == WidgetDeTareas.QUE_ABRIR || codigo == null || crudo == null || indice < 0) {
            runCatching { startActivity(Intent(this, TodasLasTareasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w("PixPin", "widget de tareas: no se pudo abrir Tareas", it) }
            return
        }
        // Escribir el chat es disco: en el ámbito de la aplicación, que sigue aunque esto se cierre.
        val app = applicationContext as PixPinApp
        app.scope.launch(Dispatchers.IO) {
            try {
                var marcada = false
                MensajesStore(app).cambiar { todos ->
                    LogicaDeLosWidgets.marcarEn(todos, codigo, indice, crudo)?.also { marcada = true } ?: todos
                }
                if (!marcada) Log.i("PixPin", "widget de tareas: la lista cambió; se repinta sin marcar")
                // Sin esperar al aviso común (que va con un respiro): la casilla se va en el acto.
                WidgetDeTareas.refrescar(app)
            } catch (e: Exception) {
                Log.w("PixPin", "widget de tareas: no se pudo marcar", e)
            }
        }
    }
}
