package com.forge.pixpin.asistente

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.forge.pixpin.ia.Edicion
import com.forge.pixpin.ia.Ojos
import com.forge.pixpin.motor.DrawTheme
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Renderer
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.Viewport
import com.forge.pixpin.motor.getElementBounds
import com.forge.pixpin.motor.parseColor
import com.forge.pixpin.pin.ImageStore
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * **Pinta la vista del lienzo para el asistente** (PC `asistente/ojos.rs`): con **el mismo
 * [Renderer] del lienzo** (como exportar), la caja de [Ojos.cajaDe] a [Ojos.LADO_MAXIMO] px como
 * mucho; encima, la rejilla gris numerada en las coordenadas del modelo y la etiqueta amarilla `#n`
 * de cada elemento. Sale en PNG.
 */
object PintorDeOjos {
    fun pintar(escena: Scene, caja: Ojos.Caja, marco: Pt, num: Edicion.Numeracion, marcas: Boolean = true): Ojos.Vista {
        val (ancho, alto, s) = Ojos.tamano(caja)
        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawColor(parseColor(escena.backgroundColor))
        // Solo lo que cae en la caja (y un poco de su trazo): un lienzo grande no se pinta entero.
        val visibles = escena.elements.filter { e ->
            if (e.isDeleted) return@filter false
            val b = getElementBounds(e)
            val h = e.strokeWidth * 2 + 4
            !(b.x2 + h < caja.x0 || b.x1 - h > caja.x1 || b.y2 + h < caja.y0 || b.y1 - h > caja.y1)
        }
        val fotos = HashMap<String, Bitmap?>()
        Renderer({ id -> fotos.getOrPut(id) { escena.files[id]?.path?.let { ImageStore.load(it) } } },
            dark = DrawTheme.esDeNoche(escena.backgroundColor), paraExportar = true
        ).renderScene(
            canvas,
            escena.copy(elements = visibles, viewport = Viewport(scrollX = -caja.x0, scrollY = -caja.y0, zoom = s)),
            ancho.toDouble(), alto.toDouble()
        )
        if (marcas) pintarMarcas(canvas, escena, caja, marco, num, s, ancho, alto)
        val png = ByteArrayOutputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o); o.toByteArray() }
        bmp.recycle()
        return Ojos.Vista(png, ancho, alto, Ojos.leyenda(caja, marco, ancho, alto, s, marcas))
    }

    /** La rejilla (números en los bordes, en las coordenadas del modelo) y las etiquetas `#n`. */
    private fun pintarMarcas(c: Canvas, escena: Scene, caja: Ojos.Caja, marco: Pt, num: Edicion.Numeracion, s: Double, ancho: Int, alto: Int) {
        val paso = Ojos.pasoDeRejilla(caja)
        val raya = Paint().apply { color = Color.argb(70, 120, 120, 120); strokeWidth = 1f }
        val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 90, 90, 90); textSize = 11f }
        fun x(v: Double) = ((v - caja.x0) * s).toFloat()
        fun y(v: Double) = ((v - caja.y0) * s).toFloat()
        // Las rayas caen en múltiplos del paso **en las coordenadas del modelo** (lo que lee y escribe).
        var mx = ceil((caja.x0 - marco.x) / paso) * paso
        while (mx <= caja.x1 - marco.x) {
            val px = x(mx + marco.x)
            c.drawLine(px, 0f, px, alto.toFloat(), raya)
            c.drawText(Math.round(mx).toString(), px + 2, 12f, letra)
            mx += paso
        }
        var my = ceil((caja.y0 - marco.y) / paso) * paso
        while (my <= caja.y1 - marco.y) {
            val py = y(my + marco.y)
            c.drawLine(0f, py, ancho.toFloat(), py, raya)
            c.drawText(Math.round(my).toString(), 2f, py - 2, letra)
            my += paso
        }
        val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 214, 10) }
        val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 120, 90, 0); style = Paint.Style.STROKE; strokeWidth = 1f }
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f; isFakeBoldText = true }
        for ((n, p) in Ojos.etiquetas(escena, caja, num)) {
            val t = "#$n"
            val w = tinta.measureText(t) + 6
            val r = RectF(x(p.first), y(p.second), x(p.first) + w, y(p.second) + 16)
            c.drawRoundRect(r, 3f, 3f, fondo)
            c.drawRoundRect(r, 3f, 3f, borde)
            c.drawText(t, r.left + 3, r.top + 12, tinta)
        }
    }
}
