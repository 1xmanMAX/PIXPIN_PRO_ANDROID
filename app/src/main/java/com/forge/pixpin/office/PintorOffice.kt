package com.forge.pixpin.office

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * **Pinta una página de Office ([Pxof.Pagina]) en un `Canvas`, en vectores** — lo que hace en el
 * PC `pixpin-render::pagina_office` con Direct2D. Sirve igual para la hoja del lector (a la
 * resolución del aumento, así acercarse se ve nítido), para presentar, para imprimir y para el PDF
 * que se exporta (el `Canvas` de `PdfDocument` guarda vectores y texto).
 *
 * El texto se pinta **glifo a glifo con la x que midió la maquetación** (`Canvas.drawGlyphs`, con
 * el archivo de letra que eligió el .so): lo que se ve partido es donde se partió al maquetar. En
 * Android 10 y 11, que no tienen `drawGlyphs`, letra a letra con su `Typeface` en la misma x.
 *
 * [imagen] da cada imagen del documento a (más o menos) el ancho en píxeles pedido; null si no se
 * sabe pintar (un EMF): se deja el hueco.
 */
class PintorOffice(private val imagen: (id: String, anchoPx: Int) -> Bitmap?) {

    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.MITER }
    private val letra = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val foto = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val camino = Path()
    private val caja = RectF()
    private var glifos = IntArray(64)
    private var posiciones = FloatArray(128)

    /**
     * Pinta [p] con su esquina en el origen del [canvas] a [escala] píxeles por punto. [total] es
     * el número de páginas para los campos `NUMPAGES` (al maquetar a trozos aún no se sabía).
     * [nitidezDeFotos] multiplica la resolución a la que se piden las imágenes: en un PDF, donde
     * un punto es una unidad, hace falta más que uno.
     */
    fun pintar(canvas: Canvas, p: Pxof.Pagina, escala: Float, total: Int, nitidezDeFotos: Float = 1f) {
        val guardado = canvas.save()
        canvas.scale(escala, escala)
        caja.set(0f, 0f, p.ancho, p.alto)
        val fondo = p.fondo
        if (fondo == null) { relleno.shader = null; relleno.color = android.graphics.Color.WHITE; relleno.alpha = 255; canvas.drawRect(caja, relleno) }
        else rellenarCaja(canvas, caja, fondo)
        // Lo que se salga de la hoja no se ve.
        canvas.clipRect(caja)
        val c = Contexto(canvas, p, escala, total)
        for (e in p.elementos) elemento(c, e, escala * nitidezDeFotos)
        canvas.restoreToCount(guardado)
    }

    private class Contexto(val canvas: Canvas, val p: Pxof.Pagina, val escala: Float, val total: Int)

    private fun elemento(c: Contexto, e: Pxof.Elemento, escala: Float) {
        val canvas = c.canvas
        when (e) {
            is Pxof.Texto -> texto(c, e)
            is Pxof.Rect -> {
                caja.set(e.x, e.y, e.x + e.ancho, e.y + e.alto)
                e.relleno?.let { rellenarCaja(canvas, RectF(caja), it) }
                e.borde?.let { t -> ponerTrazo(t); canvas.drawRect(e.x, e.y, e.x + e.ancho, e.y + e.alto, trazo) }
            }
            is Pxof.Linea -> { ponerTrazo(e.trazo); canvas.drawLine(e.x1, e.y1, e.x2, e.y2, trazo) }
            is Pxof.Imagen -> foto(c, e, escala)
            is Pxof.Trayecto -> trayecto(canvas, e)
            is Pxof.Grupo -> {
                val g = canvas.save()
                val m = Matrix()
                // a b c d e f → [a c e; b d f]
                m.setValues(floatArrayOf(e.m[0], e.m[2], e.m[4], e.m[1], e.m[3], e.m[5], 0f, 0f, 1f))
                canvas.concat(m)
                e.recorte?.let { r -> canvas.clipRect(r[0], r[1], r[0] + r[2], r[1] + r[3]) }
                // Lo que el grupo amplía, también lo pide a más resolución (las imágenes).
                val k = kotlin.math.sqrt(kotlin.math.abs(e.m[0] * e.m[3] - e.m[1] * e.m[2])).takeIf { it.isFinite() && it > 0f } ?: 1f
                for (h in e.elementos) elemento(c, h, escala * k)
                canvas.restoreToCount(g)
            }
        }
    }

    private fun ponerTrazo(t: Pxof.Trazo) {
        trazo.shader = null
        trazo.color = t.argb
        trazo.strokeWidth = t.grosor.coerceAtLeast(0.01f)
        val w = trazo.strokeWidth
        trazo.pathEffect = when (t.discontinuo) {
            Pxof.Discontinuo.SOLIDA -> null
            Pxof.Discontinuo.RAYAS -> DashPathEffect(floatArrayOf(2f * w, 2f * w), 0f)
            Pxof.Discontinuo.PUNTOS -> DashPathEffect(floatArrayOf(1f * w, 1f * w), 0f)
            Pxof.Discontinuo.RAYA_PUNTO -> DashPathEffect(floatArrayOf(2f * w, 2f * w, 1f * w, 2f * w), 0f)
        }
    }

    /** El pincel de un relleno sobre [r] (la caja de lo que se rellena), como `pincel_relleno` del PC. */
    private fun ponerRelleno(r: Pxof.Relleno, cajaDe: RectF) {
        relleno.alpha = 255
        when (r) {
            is Pxof.Relleno.Solido -> { relleno.shader = null; relleno.color = r.argb }
            is Pxof.Relleno.Lineal -> {
                if (r.colores.size < 2) { relleno.shader = null; relleno.color = r.colores.firstOrNull() ?: android.graphics.Color.WHITE; return }
                val w = cajaDe.width(); val h = cajaDe.height()
                val cx = cajaDe.centerX(); val cy = cajaDe.centerY()
                val rad = Math.toRadians(r.angulo.toDouble())
                val s = kotlin.math.sin(rad).toFloat(); val co = kotlin.math.cos(rad).toFloat()
                val mitad = (w * kotlin.math.abs(co) + h * kotlin.math.abs(s)) / 2f
                relleno.color = android.graphics.Color.BLACK
                relleno.shader = LinearGradient(cx - co * mitad, cy - s * mitad, cx + co * mitad, cy + s * mitad, r.colores, ordenadas(r.posiciones), Shader.TileMode.CLAMP)
            }
            is Pxof.Relleno.Radial -> {
                if (r.colores.size < 2) { relleno.shader = null; relleno.color = r.colores.firstOrNull() ?: android.graphics.Color.WHITE; return }
                val w = cajaDe.width().coerceAtLeast(0.01f); val h = cajaDe.height().coerceAtLeast(0.01f)
                val radio = w / 2f * 1.4142135f
                val g = RadialGradient(cajaDe.centerX(), cajaDe.centerY(), radio, r.colores, ordenadas(r.posiciones), Shader.TileMode.CLAMP)
                // Elíptico como el del PC: el círculo, aplastado a lo alto de la caja.
                g.setLocalMatrix(Matrix().apply { setScale(1f, h / w, cajaDe.centerX(), cajaDe.centerY()) })
                relleno.color = android.graphics.Color.BLACK
                relleno.shader = g
            }
        }
    }

    /** `LinearGradient` quiere las posiciones crecientes; las de un documento a veces no lo son. */
    private fun ordenadas(p: FloatArray): FloatArray {
        val v = p.copyOf()
        for (i in v.indices) v[i] = v[i].coerceIn(0f, 1f).let { if (i > 0 && it < v[i - 1]) v[i - 1] else it }
        return v
    }

    private fun rellenarCaja(canvas: Canvas, r: RectF, rel: Pxof.Relleno) {
        ponerRelleno(rel, r)
        canvas.drawRect(r, relleno)
        relleno.shader = null
    }

    private fun trayecto(canvas: Canvas, t: Pxof.Trayecto) {
        t.relleno?.let { rel ->
            if (caminoDe(t.figuras) { it.rellenar }) {
                val b = RectF()
                camino.computeBounds(b, true)
                ponerRelleno(rel, b)
                canvas.drawPath(camino, relleno)
                relleno.shader = null
            }
        }
        t.borde?.let { b ->
            if (caminoDe(t.figuras) { it.trazar }) {
                ponerTrazo(b)
                canvas.drawPath(camino, trazo)
            }
        }
    }

    /** Las figuras que cumplen [cuales], en [camino]. False si no hay ninguna. */
    private inline fun caminoDe(figuras: List<Pxof.Figura>, cuales: (Pxof.Figura) -> Boolean): Boolean {
        camino.rewind()
        camino.fillType = Path.FillType.EVEN_ODD
        var alguna = false
        for (f in figuras) {
            if (!cuales(f)) continue
            alguna = true
            camino.moveTo(f.x0, f.y0)
            var k = 0
            val p = f.puntos
            for (t in f.tipos) {
                if (t.toInt() == 0) { camino.lineTo(p[k], p[k + 1]); k += 2 }
                else { camino.cubicTo(p[k], p[k + 1], p[k + 2], p[k + 3], p[k + 4], p[k + 5]); k += 6 }
            }
            if (f.cerrada) camino.close()
        }
        return alguna
    }

    private fun foto(c: Contexto, e: Pxof.Imagen, escala: Float) {
        val anchoPx = (e.ancho * escala).toInt().coerceIn(16, 4096)
        val b = imagen(e.id, anchoPx) ?: return
        val r = e.recorte
        val src = if (r == null) null else android.graphics.Rect(
            (r[0] * b.width).toInt(), (r[1] * b.height).toInt(),
            ((1f - r[2]) * b.width).toInt().coerceAtLeast((r[0] * b.width).toInt() + 1),
            ((1f - r[3]) * b.height).toInt().coerceAtLeast((r[1] * b.height).toInt() + 1)
        )
        foto.alpha = (e.opacidad.coerceIn(0f, 1f) * 255).toInt()
        c.canvas.drawBitmap(b, src, RectF(e.x, e.y, e.x + e.ancho, e.y + e.alto), foto)
    }

    private fun texto(c: Contexto, t: Pxof.Texto) {
        val n = t.glifos.size
        if (n == 0) return
        letra.color = t.argb
        letra.textSize = t.tam
        if (t.total) {
            // El total de páginas: no se sabía al maquetar. Con la letra de su primera cara.
            val cara = c.p.caras.getOrNull(t.caras[0]) ?: return
            prepararLetra(cara)
            c.canvas.drawText(c.total.toString(), t.x, t.y, letra)
            return
        }
        var i = 0
        while (i < n) {
            val cara = t.caras[i]
            var j = i
            while (j < n && t.caras[j] == cara) j++
            corrida(c, t, cara, i, j)
            i = j
        }
    }

    /** Los glifos [desde]..[hasta] de [t], todos de la misma cara. */
    private fun corrida(c: Contexto, t: Pxof.Texto, numeroDeCara: Int, desde: Int, hasta: Int) {
        val cara = c.p.caras.getOrNull(numeroDeCara) ?: return
        prepararLetra(cara)
        if (Build.VERSION.SDK_INT >= 31) {
            val fuente = Letras.fuente(cara)
            if (fuente != null) {
                if (glifos.size < hasta - desde) { glifos = IntArray(hasta - desde); posiciones = FloatArray(2 * (hasta - desde)) }
                var k = 0
                for (g in desde until hasta) {
                    if (t.glifos[g] == 0) continue    // ninguna letra lo tiene: hueco
                    glifos[k] = t.glifos[g]
                    posiciones[2 * k] = t.x + t.xs[g]
                    posiciones[2 * k + 1] = t.y
                    k++
                }
                if (k > 0) c.canvas.drawGlyphs(glifos, 0, posiciones, 0, k, fuente, letra)
                return
            }
        }
        // Sin drawGlyphs: letra a letra, con su Typeface, en la x medida.
        for (g in desde until hasta) {
            if (t.glifos[g] == 0) continue
            val cp = t.codigos[g]
            if (cp == ' '.code || cp < 0x20) continue
            c.canvas.drawText(String(Character.toChars(cp)), t.x + t.xs[g], t.y, letra)
        }
    }

    private fun prepararLetra(cara: Pxof.Cara) {
        letra.typeface = Letras.tipo(cara)
        letra.isFakeBoldText = cara.negritaFalsa
        letra.textSkewX = if (cara.cursivaFalsa) -0.22f else 0f
    }

    /** Las letras abiertas, por archivo e índice, para todo el proceso. */
    internal object Letras {
        private val fuentes = ConcurrentHashMap<String, Any>()
        private val tipos = ConcurrentHashMap<String, Typeface>()
        private val NADA = Any()

        fun fuente(c: Pxof.Cara): android.graphics.fonts.Font? {
            if (Build.VERSION.SDK_INT < 31) return null
            val clave = c.ruta + "#" + c.indice
            val v = fuentes.getOrPut(clave) {
                runCatching { android.graphics.fonts.Font.Builder(File(c.ruta)).setTtcIndex(c.indice).build() }.getOrNull() ?: NADA
            }
            return v as? android.graphics.fonts.Font
        }

        fun tipo(c: Pxof.Cara): Typeface {
            val clave = c.ruta + "#" + c.indice
            return tipos.getOrPut(clave) {
                runCatching { Typeface.Builder(File(c.ruta)).setTtcIndex(c.indice).build() }.getOrNull() ?: Typeface.DEFAULT
            }
        }
    }
}
