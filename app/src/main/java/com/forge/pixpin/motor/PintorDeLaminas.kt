package com.forge.pixpin.motor

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot

/**
 * **Quien pinta las láminas del PC en el lienzo** (25-oct-2026): la nube de revisión, el sector y
 * el segmento, la marca de ángulo (arquitos, cuadradito, relleno y letra) y el relleno degradado.
 *
 * Va aparte del [Renderer] a propósito: allí solo hay tres ganchos de una línea —antes del cuerpo
 * de cada elemento, en el relleno y en el pincel del trazo— y todo lo demás vive aquí. La
 * geometría es la de [Laminas] y [NubeDeRevision], que se comprueba sin dispositivo.
 *
 * Lo pinta **liso**, sin el temblor de rough.js: es lo que hace el PC con las láminas (rugosidad 0
 * de fábrica) y lo que se espera de una marca de ángulo o de un sector exacto. La nube con
 * rugosidad también sale lisa: los festones ya son el adorno.
 *
 * [tema] pasa cada color por el modo noche y [pincel] da el pincel del trazo del Renderer (color,
 * grosor, discontinuo y raya-punto incluidos), para que todo se vea como lo de al lado.
 */
class PintorDeLaminas(
    private val tema: (Int) -> Int,
    private val pincel: (Element, Int) -> Paint
) {
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG)

    /** El raya-punto de cada grosor, hecho una vez. */
    private val rayasPunto = HashMap<Double, DashPathEffect>()

    /** **El efecto del raya-punto** con el patrón del PC. Ver [Laminas.patronRayaPunto]. */
    fun efectoRayaPunto(grosor: Double): DashPathEffect = rayasPunto.getOrPut(grosor) {
        DashPathEffect(Laminas.patronRayaPunto(grosor).map { it.toFloat() }.toFloatArray(), 0f)
    }

    /**
     * Pinta [e] entero si es cosa de lámina (una nube, un arco cerrado o una marca de ángulo) y
     * dice si lo hizo; si no, el Renderer sigue como siempre. El lienzo ya viene girado.
     */
    fun pintar(canvas: Canvas, e: Element, alpha: Int): Boolean {
        NubeDeRevision.contornoDe(e)?.let { contorno ->
            pintarCerrado(canvas, e, contorno, alpha)
            return true
        }
        if (e.type != ElementType.ARC) return false
        Laminas.marcaDe(e)?.let { m ->
            val d = Laminas.dibujoDeMarca(e, m) ?: return true
            if (d.relleno.size >= 3) rellenar(canvas, e, caminoDe(d.relleno, cerrado = true), alpha)
            val trazo = pincel(e, alpha)
            for (raya in d.rayas) canvas.drawPath(caminoDe(raya, cerrado = false), trazo)
            m.valor?.let { valor -> escribirValor(canvas, e, d, valor, alpha) }
            return true
        }
        if (Laminas.cierreDe(e) == null) return false
        val contorno = Laminas.contornoCerrado(e) ?: return false
        pintarCerrado(canvas, e, contorno, alpha)
        return true
    }

    /** Un contorno cerrado: su relleno debajo y su trazo encima. */
    private fun pintarCerrado(canvas: Canvas, e: Element, contorno: List<Pt>, alpha: Int) {
        if (contorno.size < 2) return
        val camino = caminoDe(contorno, cerrado = true)
        rellenar(canvas, e, camino, alpha)
        canvas.drawPath(camino, pincel(e, alpha))
    }

    /** El relleno de [camino]: degradado, liso o rayado, con el fondo de [e]. */
    private fun rellenar(canvas: Canvas, e: Element, camino: Path, alpha: Int) {
        if (isTransparent(e.backgroundColor)) return
        if (rellenarConDegradado(canvas, e, camino, alpha)) return
        relleno.reset()
        relleno.isAntiAlias = true
        relleno.color = tema(colorDe(e.backgroundColor, alpha))
        if (e.fillStyle == FillStyle.SOLID) {
            relleno.style = Paint.Style.FILL
            canvas.drawPath(camino, relleno)
            return
        }
        // Rayado a 45°, recortado al contorno (la nube no es convexa: el recorte es el camino).
        relleno.style = Paint.Style.STROKE
        relleno.strokeWidth = (e.strokeWidth / 2).toFloat().coerceAtLeast(0.5f)
        val c = getElementAbsoluteCoords(e)
        val paso = (e.strokeWidth * 4 + 4)
        canvas.save()
        canvas.clipPath(camino)
        var t = c.x1 - (c.y2 - c.y1)
        while (t < c.x2) {
            canvas.drawLine(t.toFloat(), c.y2.toFloat(), (t + (c.y2 - c.y1)).toFloat(), c.y1.toFloat(), relleno)
            t += paso
        }
        canvas.restore()
        relleno.style = Paint.Style.FILL
    }

    /**
     * **El relleno degradado** de [camino] si [e] lo lleva: lineal de origen a destino o radial
     * con centro en el origen y radio hasta el destino, en fracciones de la caja (así se estira
     * con la figura). Dice si lo pintó. Es lo que el PC hace en bandas; aquí lo hace el sombreador.
     */
    fun rellenarConDegradado(canvas: Canvas, e: Element, camino: Path, alpha: Int): Boolean {
        val d = Laminas.degradadoDe(e) ?: return false
        val c = getElementAbsoluteCoords(e)
        fun en(f: Pair<Double, Double>) =
            Pt(c.x1 + f.first * (c.x2 - c.x1), c.y1 + f.second * (c.y2 - c.y1))
        val o = en(d.origen)
        val q = en(d.destino)
        val desde = tema(colorDe(d.desde, alpha))
        val hasta = tema(colorDe(d.hasta, alpha))
        relleno.reset()
        relleno.isAntiAlias = true
        relleno.style = Paint.Style.FILL
        relleno.shader = when (d.tipo) {
            Laminas.TipoDegradado.LINEAL ->
                if (hypot(q.x - o.x, q.y - o.y) < 1e-3) null
                else LinearGradient(
                    o.x.toFloat(), o.y.toFloat(), q.x.toFloat(), q.y.toFloat(),
                    desde, hasta, Shader.TileMode.CLAMP
                )
            Laminas.TipoDegradado.RADIAL -> RadialGradient(
                o.x.toFloat(), o.y.toFloat(), hypot(q.x - o.x, q.y - o.y).coerceAtLeast(1.0).toFloat(),
                desde, hasta, Shader.TileMode.CLAMP
            )
        }
        if (relleno.shader == null) relleno.color = desde
        canvas.drawPath(camino, relleno)
        relleno.shader = null
        return true
    }

    /** La letra o el valor de una marca, fuera y sobre la bisectriz. */
    private fun escribirValor(canvas: Canvas, e: Element, d: Laminas.DibujoDeMarca, valor: String, alpha: Int) {
        letra.reset()
        letra.isAntiAlias = true
        letra.textSize = (e.fontSize ?: TAM_DEL_VALOR).toFloat()
        letra.color = tema(colorDe(e.strokeColor, alpha))
        val ancho = letra.measureText(valor).toDouble()
        val metrica = letra.fontMetrics
        val alto = (metrica.descent - metrica.ascent).toDouble()
        val centro = Laminas.sitioDelValor(d, ancho, alto)
        canvas.drawText(
            valor, (centro.x - ancho / 2).toFloat(),
            (centro.y - (metrica.ascent + metrica.descent) / 2).toFloat(), letra
        )
    }

    private fun caminoDe(puntos: List<Pt>, cerrado: Boolean): Path = Path().apply {
        puntos.firstOrNull()?.let { moveTo(it.x.toFloat(), it.y.toFloat()) }
        for (i in 1 until puntos.size) lineTo(puntos[i].x.toFloat(), puntos[i].y.toFloat())
        if (cerrado) close()
    }

    /** Un color del fichero con el alfa al final (el del PC) o, si no, el de siempre. */
    private fun colorDe(s: String, alpha: Int): Int = Laminas.argbDe(s, alpha) ?: parseColor(s, alpha)

    private companion object {
        /** El tamaño del valor de una marca si no dice otro (`tam_letra` del PC: 14). */
        const val TAM_DEL_VALOR = 14.0
    }
}
