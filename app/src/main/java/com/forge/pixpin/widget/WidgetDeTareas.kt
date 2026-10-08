package com.forge.pixpin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.forge.pixpin.R
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.mini.TareaRapidaActivity
import com.forge.pixpin.mini.TodasLasTareasActivity

/**
 * **El widget de tareas** (8-oct-2026): lo pendiente de TODAS las listas en la pantalla de inicio,
 * con su casilla para tacharlo ahí mismo y un «+» para apuntar.
 *
 * - **Lo que sale** lo decide [LogicaDeLosWidgets.filasDeTareas]: lo mismo y en el mismo orden que
 *   la pantalla de Tareas ([TodasLasTareasActivity]), sin lo hecho.
 * - **Marcar** va a [TocarTareaDelWidget] ([ACCION_FILA]), una actividad transparente que no
 *   enseña nada y se cierra: el usuario quiere tachar sin salir del inicio. Se escribe con [MensajesStore.cambiar], el mismo camino que
 *   la pantalla de Tareas, así que la tarea viaja al sincronizar como cualquier otra tachada. Si la
 *   lista cambió desde que se pintó el widget, no se toca nada y la lista se repinta al día.
 * - **El «+»** abre [TareaRapidaActivity]: un widget no deja escribir dentro, y esa ventanita ya
 *   apunta en el Inbox.
 * - **Tocar el texto** abre la pantalla de Tareas.
 *
 * Leer el chat va en [ServicioDeTareas], que el lanzador llama fuera del hilo principal; aquí,
 * en `onUpdate`, solo se arma el marco.
 */
class WidgetDeTareas : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, marco(context, id))
        // Que los «hace N días» avancen con la media hora de `updatePeriodMillis`.
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_tareas_lista)
    }

    companion object {
        const val ACCION_FILA = "com.forge.pixpin.widget.TAREA"
        const val EXTRA_QUE = "que"
        const val QUE_MARCAR = "marcar"
        const val QUE_ABRIR = "abrir"
        const val EXTRA_CODIGO = "codigo"
        const val EXTRA_INDICE = "indice"
        const val EXTRA_CRUDO = "crudo"

        // Los códigos de cada PendingIntent del widget, distintos entre sí y de los de la galería
        // (el lanzador los guarda por paquete: dos iguales con distinto destino se pisarían).
        private const val PI_MAS = 7101
        private const val PI_ABRIR = 7102
        private const val PI_FILAS = 7103

        /** Que todos los widgets de tareas vuelvan a leer el chat. Barato si no hay ninguno. */
        fun refrescar(context: Context) {
            runCatching {
                val m = AppWidgetManager.getInstance(context) ?: return
                val ids = m.getAppWidgetIds(ComponentName(context, WidgetDeTareas::class.java))
                if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.widget_tareas_lista)
            }
        }

        /** La cabecera, el «+» y la lista (sin filas: las pone [ServicioDeTareas]). */
        fun marco(context: Context, id: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_tareas)
            val inmutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            v.setOnClickPendingIntent(
                R.id.widget_tareas_mas,
                PendingIntent.getActivity(
                    context, PI_MAS,
                    Intent(context, TareaRapidaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    inmutable
                )
            )
            val abrir = PendingIntent.getActivity(
                context, PI_ABRIR,
                Intent(context, TodasLasTareasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                inmutable
            )
            v.setOnClickPendingIntent(R.id.widget_tareas_cabecera, abrir)
            v.setOnClickPendingIntent(R.id.widget_tareas_vacio, abrir)

            // La lista: su servicio, con un `data` propio por widget para que el sistema no
            // reutilice la fábrica de otro.
            val servicio = Intent(context, ServicioDeTareas::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            servicio.data = Uri.parse(servicio.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.widget_tareas_lista, servicio)
            v.setEmptyView(R.id.widget_tareas_lista, R.id.widget_tareas_vacio)
            // La plantilla de las filas: MUTABLE porque cada fila le pega lo suyo (qué tarea, y si
            // es marcar o abrir). Explícita y a una actividad sin exportar: nadie más la recibe ni
            // la imita. Actividad y no receptor: ver [TocarTareaDelWidget].
            v.setPendingIntentTemplate(
                R.id.widget_tareas_lista,
                PendingIntent.getActivity(
                    context, PI_FILAS,
                    Intent(context, TocarTareaDelWidget::class.java).setAction(ACCION_FILA)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
            return v
        }
    }
}
