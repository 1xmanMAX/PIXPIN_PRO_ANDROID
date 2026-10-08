package com.forge.pixpin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.mini.TodasLasTareas
import java.time.LocalDate

/** Las filas del widget de tareas, leídas en el hilo de la fábrica (el lanzador no espera a nadie en el principal). */
class ServicioDeTareas : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Fabrica(applicationContext)

    private class Fabrica(private val context: Context) : RemoteViewsFactory {
        @Volatile private var filas: List<LogicaDeLosWidgets.FilaDeTarea> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() { filas = emptyList() }

        /** Lo llama el sistema en un hilo suyo: aquí sí se puede leer el disco. */
        override fun onDataSetChanged() {
            val (nuevas, pendientes, hayListas) = runCatching {
                val app = context as? PixPinApp
                val nombres = app?.proyectos?.proyectos?.value?.associate { it.id to it.nombre } ?: emptyMap()
                val listas = TodasLasTareas.reunir(MensajesStore(context).leer(), nombres)
                Triple(LogicaDeLosWidgets.filasDeTareas(listas, LocalDate.now()), LogicaDeLosWidgets.pendientes(listas), listas.isNotEmpty())
            }.onFailure { Log.w("PixPin", "widget de tareas: no se pudo leer el chat", it) }
                .getOrDefault(Triple(emptyList(), 0, true))
            filas = nuevas
            // La cabecera y el texto de vacío, sin rehacer el widget entero.
            runCatching {
                val m = AppWidgetManager.getInstance(context)
                val ids = m.getAppWidgetIds(ComponentName(context, WidgetDeTareas::class.java))
                val v = RemoteViews(context.packageName, R.layout.widget_tareas)
                v.setTextViewText(R.id.widget_tareas_cuenta, LogicaDeLosWidgets.cuenta(pendientes))
                v.setTextViewText(R.id.widget_tareas_vacio, LogicaDeLosWidgets.vacioDeTareas(hayListas))
                if (ids.isNotEmpty()) m.partiallyUpdateAppWidget(ids, v)
            }
        }

        override fun getCount(): Int = filas.size

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_tareas_fila)
            val f = filas.getOrNull(position) ?: return v
            v.setTextViewText(R.id.widget_tarea_texto, f.texto)
            v.setTextViewText(R.id.widget_tarea_detalle, f.detalle)
            v.setContentDescription(R.id.widget_tarea_casilla, "Marcar «${f.texto}» como hecha")
            fun relleno(que: String) = Intent()
                .putExtra(WidgetDeTareas.EXTRA_QUE, que)
                .putExtra(WidgetDeTareas.EXTRA_CODIGO, f.codigo)
                .putExtra(WidgetDeTareas.EXTRA_INDICE, f.indice)
                .putExtra(WidgetDeTareas.EXTRA_CRUDO, f.crudo)
            v.setOnClickFillInIntent(R.id.widget_tarea_casilla, relleno(WidgetDeTareas.QUE_MARCAR))
            v.setOnClickFillInIntent(R.id.widget_tarea_cuerpo, relleno(WidgetDeTareas.QUE_ABRIR))
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = filas.getOrNull(position)?.clave?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true
    }
}
