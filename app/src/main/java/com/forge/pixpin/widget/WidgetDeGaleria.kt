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
import com.forge.pixpin.ui.GaleriaDeCapturasActivity

/**
 * **El widget de la galería** (8-oct-2026): las capturas más recientes de `Pictures/PixPin` en
 * una rejilla, cada una con su chapita de los días que le quedan antes de irse a la papelera
 * (roja si es hoy o mañana, naranja a dos o tres días; las conservadas, sin chapita). Tocar una la
 * abre en la galería de capturas ([GaleriaDeCapturasActivity.EXTRA_CAPTURA]).
 *
 * Qué sale y qué pone cada chapita lo decide [LogicaDeLosWidgets.casillas]; las miniaturas las
 * pide [ServicioDeGaleria] a Android ya reducidas ([LogicaDeLosWidgets.LADO_MINIATURA]): una
 * captura a tamaño real son megas, y los `RemoteViews` que cruzan al lanzador tienen un tope.
 */
class WidgetDeGaleria : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, marco(context, id))
        // Que las chapitas cuenten los días aunque nadie abra la aplicación.
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_galeria_rejilla)
    }

    companion object {
        // Distintos de los del widget de tareas (7101-7103).
        private const val PI_ABRIR = 7201
        private const val PI_CASILLAS = 7202

        /** Que todos los widgets de galería vuelvan a mirar las capturas. Barato si no hay ninguno. */
        fun refrescar(context: Context) {
            runCatching {
                val m = AppWidgetManager.getInstance(context) ?: return
                val ids = m.getAppWidgetIds(ComponentName(context, WidgetDeGaleria::class.java))
                if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.widget_galeria_rejilla)
            }
        }

        fun marco(context: Context, id: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_galeria)
            val abrir = PendingIntent.getActivity(
                context, PI_ABRIR,
                Intent(context, GaleriaDeCapturasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_galeria_cabecera, abrir)
            v.setOnClickPendingIntent(R.id.widget_galeria_vacio, abrir)
            val servicio = Intent(context, ServicioDeGaleria::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            servicio.data = Uri.parse(servicio.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            v.setRemoteAdapter(R.id.widget_galeria_rejilla, servicio)
            v.setEmptyView(R.id.widget_galeria_rejilla, R.id.widget_galeria_vacio)
            // MUTABLE: cada casilla le pega cuál es. Explícita a la galería, que no se exporta.
            v.setPendingIntentTemplate(
                R.id.widget_galeria_rejilla,
                PendingIntent.getActivity(
                    context, PI_CASILLAS,
                    Intent(context, GaleriaDeCapturasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )
            return v
        }
    }
}
