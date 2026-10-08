package com.forge.pixpin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.R
import com.forge.pixpin.capture.BarrenderoDeCapturas
import com.forge.pixpin.capture.CaducidadDeCapturas
import com.forge.pixpin.capture.GaleriaLogica
import com.forge.pixpin.ui.GaleriaDeCapturasActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Las casillas del widget de la galería, con sus miniaturas, en el hilo de la fábrica. */
class ServicioDeGaleria : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Fabrica(applicationContext)

    private class Fabrica(private val context: Context) : RemoteViewsFactory {
        private class Celda(val uri: Uri, val nombre: String, val casilla: LogicaDeLosWidgets.Casilla, val foto: Bitmap?)

        @Volatile private var celdas: List<Celda> = emptyList()

        /**
         * Las miniaturas ya hechas, por captura: una captura nueva o una chapita que cambia no
         * obliga a decodificar otra vez las otras once. Solo se guardan las que se ven.
         */
        private val hechas = HashMap<Uri, Bitmap?>()

        override fun onCreate() {}
        override fun onDestroy() { celdas = emptyList(); hechas.clear() }

        override fun onDataSetChanged() {
            val ahora = System.currentTimeMillis()
            val lista = BarrenderoDeCapturas.listar(context)
            val dias = diasDeCaducidad()
            val registro = runCatching { CaducidadDeCapturas.leer(BarrenderoDeCapturas.raiz(context), ahora) }
                .getOrDefault(CaducidadDeCapturas.Registro(desde = ahora))
            val pares = lista.orEmpty().map { it.nombre to it.cuando }
            val casillas = LogicaDeLosWidgets.casillas(pares, registro, dias, ahora)
            val nuevas = casillas.map { c ->
                val cap = lista!![c.indice]
                Celda(cap.uri, cap.nombre, c, hechas[cap.uri] ?: miniatura(cap.uri))
            }
            hechas.keys.retainAll(nuevas.mapTo(HashSet()) { it.uri })
            nuevas.forEach { hechas[it.uri] = it.foto }
            celdas = nuevas
            runCatching {
                val m = AppWidgetManager.getInstance(context)
                val ids = m.getAppWidgetIds(ComponentName(context, WidgetDeGaleria::class.java))
                val v = RemoteViews(context.packageName, R.layout.widget_galeria)
                val n = if (lista == null) 0 else LogicaDeLosWidgets.vivas(pares, registro, dias, ahora)
                v.setTextViewText(R.id.widget_galeria_cuenta, LogicaDeLosWidgets.cuenta(n))
                v.setTextViewText(R.id.widget_galeria_vacio, LogicaDeLosWidgets.vacioDeGaleria(fallo = lista == null))
                if (ids.isNotEmpty()) m.partiallyUpdateAppWidget(ids, v)
            }
        }

        /** Los días que duran las capturas, de los ajustes; sin esperar más de un segundo (los ajustes viven en disco). */
        private fun diasDeCaducidad(): Int {
            val app = context as? PixPinApp ?: return CaducidadDeCapturas.DIAS
            return runCatching {
                runBlocking { withTimeoutOrNull(1_000) { app.settings.settings.first().diasCaducidad } }
            }.getOrNull() ?: app.ajustes.diasCaducidad
        }

        /**
         * La miniatura de una captura, cuadrada y de [LogicaDeLosWidgets.LADO_MINIATURA] de lado.
         * La pide a Android (`loadThumbnail`, que la tiene en caché y vale también para un vídeo)
         * y recorta el centro. null si no se pudo: la casilla sale con su fondo.
         */
        private fun miniatura(uri: Uri): Bitmap? = runCatching {
            val lado = LogicaDeLosWidgets.LADO_MINIATURA
            val b = context.contentResolver.loadThumbnail(uri, Size(lado, lado), null)
            val (x, y, l) = LogicaDeLosWidgets.recorteCuadrado(b.width, b.height)
            if (l <= 0) return@runCatching null
            val cuadrada = Bitmap.createBitmap(b, x, y, l, l)
            val final = if (l > lado) Bitmap.createScaledBitmap(cuadrada, lado, lado, true) else cuadrada
            if (cuadrada !== b && cuadrada !== final) cuadrada.recycle()
            if (b !== cuadrada && b !== final) b.recycle()
            final
        }.onFailure { Log.w("PixPin", "widget de galería: sin miniatura de $uri", it) }.getOrNull()

        override fun getCount(): Int = celdas.size

        override fun getViewAt(position: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_galeria_celda)
            val c = celdas.getOrNull(position) ?: return v
            if (c.foto != null) v.setImageViewBitmap(R.id.widget_galeria_foto, c.foto)
            else v.setImageViewResource(R.id.widget_galeria_foto, R.drawable.widget_sin_foto)
            v.setContentDescription(R.id.widget_galeria_foto, "${c.nombre}. ${c.casilla.descripcion}")
            val chapita = c.casilla.chapita
            val tono = c.casilla.tono
            if (chapita == null || tono == null) {
                v.setViewVisibility(R.id.widget_galeria_chapita, View.GONE)
            } else {
                v.setViewVisibility(R.id.widget_galeria_chapita, View.VISIBLE)
                v.setTextViewText(R.id.widget_galeria_chapita, chapita)
                val (fondo, tinta) = when (tono) {
                    GaleriaLogica.Tono.URGENTE -> R.drawable.widget_chapita_urgente to 0xFFFFFFFF.toInt()
                    GaleriaLogica.Tono.PRONTO -> R.drawable.widget_chapita_pronto to 0xFF1C1C1E.toInt()
                    else -> R.drawable.widget_chapita_lejos to 0xFFE5E5EA.toInt()
                }
                v.setInt(R.id.widget_galeria_chapita, "setBackgroundResource", fondo)
                v.setTextColor(R.id.widget_galeria_chapita, tinta)
            }
            v.setOnClickFillInIntent(
                R.id.widget_galeria_celda,
                Intent().putExtra(GaleriaDeCapturasActivity.EXTRA_CAPTURA, c.uri.toString())
            )
            return v
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = celdas.getOrNull(position)?.uri?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true
    }
}
