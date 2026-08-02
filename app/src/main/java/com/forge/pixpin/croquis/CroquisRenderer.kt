package com.forge.pixpin.croquis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dibuja un croquis en un `Canvas`.
 *
 * Uno solo para los tres destinos —la pantalla, el PDF y el JPG— porque un
 * `PdfDocument` también entrega un `Canvas`. Si algo se ve en el móvil, sale
 * igual en el papel: no hay dos caminos que puedan divergir.
 */
object CroquisRenderer {

    /** Grosor de línea en píxeles de pantalla; no escala con el zoom, como en CAD. */
    private const val TRAZO_PX = 2.2f
    private const val PUNTA_PX = 10f

    fun dibujar(
        canvas: Canvas,
        croquis: Croquis,
        vista: Vista,
        anchoPx: Int,
        altoPx: Int,
        fondoBitmap: Bitmap? = null,
        tinta: Int = Color.BLACK,
        locale: Locale = Locale.getDefault()
    ) {
        val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = TRAZO_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = tinta
        }
        val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tinta }

        fondoBitmap?.let { dibujarFondo(canvas, it, croquis.fondo, vista, anchoPx, altoPx) }

        for (e in croquis.entidades) {
            when (e) {
                is Entidad.Linea -> {
                    val a = p(e.a, vista, anchoPx, altoPx)
                    val b = p(e.b, vista, anchoPx, altoPx)
                    canvas.drawLine(a.x, a.y, b.x, b.y, trazo)
                }

                is Entidad.Polilinea -> {
                    if (e.puntos.size >= 2) {
                        val path = Path()
                        e.puntos.forEachIndexed { i, punto ->
                            val q = p(punto, vista, anchoPx, altoPx)
                            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
                        }
                        if (e.cerrada) path.close()
                        canvas.drawPath(path, trazo)
                    }
                }

                is Entidad.Rect -> {
                    val a = p(e.a, vista, anchoPx, altoPx)
                    val b = p(e.b, vista, anchoPx, altoPx)
                    canvas.drawRect(
                        minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y), trazo
                    )
                }

                is Entidad.Circulo -> {
                    val c = p(e.centro, vista, anchoPx, altoPx)
                    canvas.drawCircle(c.x, c.y, (e.radio * vista.pixelsPorMetro).toFloat(), trazo)
                }

                is Entidad.Texto -> {
                    val q = p(e.en, vista, anchoPx, altoPx)
                    relleno.textSize = (e.alturaM * vista.pixelsPorMetro).toFloat()
                        .coerceIn(6f, 400f)
                    canvas.drawText(e.texto, q.x, q.y, relleno)
                }

                is Entidad.Cota -> dibujarCota(
                    canvas, e, croquis.decimales, vista, anchoPx, altoPx, trazo, relleno, locale
                )
            }
        }
    }

    /**
     * La imagen se coloca **en el mundo**: su esquina cae en `origen` y cada
     * píxel suyo vale `metrosPorPixel`. A partir de ahí medir sobre la captura y
     * medir sobre lo dibujado es exactamente el mismo código.
     */
    private fun dibujarFondo(
        canvas: Canvas, bmp: Bitmap, fondo: Fondo?, vista: Vista, anchoPx: Int, altoPx: Int
    ) {
        if (fondo == null) return
        val mpp = fondo.metrosPorPixel
        val superiorIzq = fondo.origen
        // La Y de una imagen crece hacia abajo y la del mundo hacia arriba: por
        // eso la esquina inferior RESTA el alto en metros.
        val inferiorDer = P(superiorIzq.x + bmp.width * mpp, superiorIzq.y - bmp.height * mpp)
        val a = p(superiorIzq, vista, anchoPx, altoPx)
        val b = p(inferiorDer, vista, anchoPx, altoPx)
        canvas.drawBitmap(bmp, null, RectF(a.x, a.y, b.x, b.y), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun dibujarCota(
        canvas: Canvas,
        cota: Entidad.Cota,
        decimales: Int,
        vista: Vista,
        anchoPx: Int,
        altoPx: Int,
        trazo: Paint,
        relleno: Paint,
        locale: Locale
    ) {
        val a = p(cota.a, vista, anchoPx, altoPx)
        val b = p(cota.b, vista, anchoPx, altoPx)
        val dx = b.x - a.x
        val dy = b.y - a.y
        val largo = hypot(dx, dy)
        if (largo < 0.5f) return

        // Perpendicular unitaria, para separar la línea de cota de la medida.
        val nx = -dy / largo
        val ny = dx / largo
        val sep = (cota.desplazamiento * vista.pixelsPorMetro).toFloat()
        val a2 = Px(a.x + nx * sep, a.y + ny * sep)
        val b2 = Px(b.x + nx * sep, b.y + ny * sep)

        canvas.drawLine(a2.x, a2.y, b2.x, b2.y, trazo)
        // Líneas de referencia, del punto medido a su cota.
        canvas.drawLine(a.x, a.y, a2.x, a2.y, trazo)
        canvas.drawLine(b.x, b.y, b2.x, b2.y, trazo)
        punta(canvas, a2, b2, trazo)
        punta(canvas, b2, a2, trazo)

        val texto = CroquisGeometria.formatear(cota.medida(), decimales, locale)
        relleno.textSize = 34f
        val ancho = relleno.measureText(texto)
        canvas.save()
        canvas.translate((a2.x + b2.x) / 2f, (a2.y + b2.y) / 2f)
        var grados = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // Nunca del revés: una cota se lee de izquierda a derecha o no se lee.
        if (grados > 90f || grados < -90f) grados += 180f
        canvas.rotate(grados)
        canvas.drawText(texto, -ancho / 2f, -8f, relleno)
        canvas.restore()
    }

    private fun punta(canvas: Canvas, en: Px, hacia: Px, trazo: Paint) {
        val ang = atan2((hacia.y - en.y).toDouble(), (hacia.x - en.x).toDouble())
        val abre = Math.toRadians(20.0)
        for (s in listOf(-1, 1)) {
            val a = ang + s * abre
            canvas.drawLine(
                en.x, en.y,
                (en.x + PUNTA_PX * cos(a)).toFloat(),
                (en.y + PUNTA_PX * sin(a)).toFloat(),
                trazo
            )
        }
    }

    private fun p(punto: P, vista: Vista, anchoPx: Int, altoPx: Int): Px =
        CroquisGeometria.aPantalla(punto, vista, anchoPx, altoPx)
}
