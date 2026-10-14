package com.forge.pixpin.office

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 *
 * **Efectos** (PC 954e77e: sombra exterior e interior, brillo y borde suave de DrawingML): lo
 * envuelto se pinta en un mapa de bits aparte a la resolución del destino, y de su silueta salen
 * los efectos con `extractAlpha` y desenfoque (el PC lo hace con los efectos de Direct2D sobre una
 * lista de órdenes). Lo envuelto se sigue pintando en vectores encima, salvo con borde suave. Las
 * **caras teñidas** de las formas predefinidas (cubo, bisel; PC f38c011) van cada una en su camino,
 * con el relleno y encima el tinte.
 *
 * **Modo oscuro** (`oscuro` en [pintar], PC 74801b4, [Oscuro]): la hoja sin fondo propio (o con
 * fondo blanco liso) pasa al papel oscuro, y el texto y las rayas oscuras sin un relleno propio
 * debajo se aclaran conservando su tono; los rellenos, las imágenes y los gráficos se quedan con sus
 * colores, y lo que va encima de ellos también.
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
    fun pintar(canvas: Canvas, p: Pxof.Pagina, escala: Float, total: Int, nitidezDeFotos: Float = 1f, oscuro: Boolean = false) {
        val guardado = canvas.save()
        canvas.scale(escala, escala)
        caja.set(0f, 0f, p.ancho, p.alto)
        val fondo = p.fondo
        // En oscuro, la hoja de papel: la que no trae fondo (la de Word) o lo trae blanco liso (las
        // diapositivas sencillas). Una con fondo de color, degradado o imagen se ve como la diseñó
        // su autor, como en PowerPoint.
        val enOscuro = oscuro && (fondo == null || deBlanco(fondo))
        rellenos.clear()
        if (fondo == null || enOscuro) {
            relleno.shader = null; relleno.color = if (enOscuro) Oscuro.PAPEL else android.graphics.Color.WHITE; relleno.alpha = 255
            canvas.drawRect(caja, relleno)
        } else rellenarCaja(canvas, caja, fondo)
        // Lo que se salga de la hoja no se ve.
        canvas.clipRect(caja)
        val c = Contexto(canvas, p, escala, total, 0, enOscuro, null)
        for (e in p.elementos) elemento(c, e, escala * nitidezDeFotos)
        canvas.restoreToCount(guardado)
    }

    /**
     * Con qué se pinta: el lienzo, la página, cuántos [Pxof.Efectos] hay por encima ([nivel]), si va
     * en oscuro y, dentro de un efecto, cómo se pasa del mapa de bits aparte al destino de verdad
     * ([aPrincipal]: lo que hay debajo de un texto se mira siempre en el destino).
     */
    private class Contexto(
        val canvas: Canvas, val p: Pxof.Pagina, val escala: Float, val total: Int,
        val nivel: Int, val oscuro: Boolean, val aPrincipal: Matrix?
    )

    // ---------------------------------------------------------------------------- modo oscuro

    /** Lo que ya tiene un relleno propio en esta página, en píxeles del destino. */
    private val rellenos = ArrayList<RectF>()
    private val matriz = Matrix()
    private val enElDestino = RectF()

    /** La caja [l] [t] [r] [b] (en las coordenadas de ahora) en píxeles del destino. */
    private fun destino(c: Contexto, l: Float, t: Float, r: Float, b: Float): RectF {
        @Suppress("DEPRECATION") c.canvas.getMatrix(matriz)
        c.aPrincipal?.let { matriz.postConcat(it) }
        enElDestino.set(minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b))
        matriz.mapRect(enElDestino)
        return enElDestino
    }

    /** Apunta que ahí hay un relleno propio (solo en oscuro). */
    private fun apuntarRelleno(c: Contexto, l: Float, t: Float, r: Float, b: Float) {
        if (c.oscuro) rellenos += RectF(destino(c, l, t, r, b))
    }

    /**
     * El color de un texto o una raya ([raya]) que ocupa esa caja: en oscuro y sin un relleno propio
     * debajo de su centro, legible sobre el papel oscuro (de las rayas, solo las casi negras).
     */
    private fun sobrePapel(c: Contexto, argb: Int, l: Float, t: Float, r: Float, b: Float, raya: Boolean): Int {
        if (!c.oscuro) return argb
        val d = destino(c, l, t, r, b)
        val cx = d.centerX(); val cy = d.centerY()
        for (i in rellenos.indices.reversed()) if (rellenos[i].contains(cx, cy)) return argb
        return if (raya) Oscuro.rayaLegible(argb) else Oscuro.legible(argb)
    }

    /** En oscuro, un relleno liso casi blanco es papel (y no cuenta como relleno propio). */
    private fun dePapel(c: Contexto, r: Pxof.Relleno): Boolean = c.oscuro && deBlanco(r)

    private fun deBlanco(r: Pxof.Relleno) = r is Pxof.Relleno.Solido && Oscuro.casiBlanco(r.argb)

    private val PAPEL = Pxof.Relleno.Solido(Oscuro.PAPEL)

    /**
     * El último texto pintado: su color, el que se le dio y dónde empieza. Su subrayado y su tachado
     * van justo detrás en el PXOF (rayas horizontales que empiezan en su x), y van del mismo color.
     */
    private var ultimoTexto = 0
    private var ultimoTextoPintado = 0
    private var ultimoTextoX = 0f
    private var hayUltimoTexto = false

    private fun elemento(c: Contexto, e: Pxof.Elemento, escala: Float) {
        val canvas = c.canvas
        if (e !is Pxof.Linea) hayUltimoTexto = false
        when (e) {
            is Pxof.Texto -> texto(c, e)
            is Pxof.Rect -> {
                caja.set(e.x, e.y, e.x + e.ancho, e.y + e.alto)
                e.relleno?.let {
                    if (dePapel(c, it)) rellenarCaja(canvas, RectF(caja), PAPEL)
                    else { rellenarCaja(canvas, RectF(caja), it); apuntarRelleno(c, e.x, e.y, e.x + e.ancho, e.y + e.alto) }
                }
                e.borde?.let { t ->
                    ponerTrazo(t, sobrePapel(c, t.argb, e.x, e.y, e.x + e.ancho, e.y + e.alto, raya = true))
                    canvas.drawRect(e.x, e.y, e.x + e.ancho, e.y + e.alto, trazo)
                }
            }
            is Pxof.Linea -> {
                // El subrayado y el tachado de un texto, del color que se le dio al texto.
                val delTexto = hayUltimoTexto && e.trazo.argb == ultimoTexto && e.y1 == e.y2 && e.x1 == ultimoTextoX
                val color = if (c.oscuro && delTexto) ultimoTextoPintado
                else sobrePapel(c, e.trazo.argb, e.x1, e.y1, e.x2, e.y2, raya = true)
                ponerTrazo(e.trazo, color)
                canvas.drawLine(e.x1, e.y1, e.x2, e.y2, trazo)
            }
            is Pxof.Imagen -> {
                foto(c, e, escala)
                apuntarRelleno(c, e.x, e.y, e.x + e.ancho, e.y + e.alto)
            }
            is Pxof.Trayecto -> trayecto(c, e)
            is Pxof.Efectos -> conEfectos(c, e, escala)
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

    private fun ponerTrazo(t: Pxof.Trazo, argb: Int = t.argb) {
        trazo.shader = null
        trazo.color = argb
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

    private fun trayecto(c: Contexto, t: Pxof.Trayecto) {
        val canvas = c.canvas
        t.relleno?.let { original ->
            // La caja del degradado es la de toda la forma, no la de cada cara.
            if (caminoDe(t.figuras) { it.rellenar }) {
                val b = RectF()
                camino.computeBounds(b, true)
                val papel = dePapel(c, original)
                ponerRelleno(if (papel) PAPEL else original, b)
                if (caminoDe(t.figuras) { it.rellenar && it.tinte == 0 }) canvas.drawPath(camino, relleno)
                // Las caras teñidas y las que van aparte: cada clase en su camino, con el relleno y encima el tinte.
                for ((cual, tinte) in TINTES) {
                    if (!caminoDe(t.figuras) { it.rellenar && it.tinte == cual }) continue
                    canvas.drawPath(camino, relleno)
                    if (tinte != 0) {
                        relleno.shader = null; relleno.color = tinte
                        canvas.drawPath(camino, relleno)
                        ponerRelleno(if (papel) PAPEL else original, b)
                    }
                }
                relleno.shader = null
                if (!papel) apuntarRelleno(c, b.left, b.top, b.right, b.bottom)
            }
        }
        t.borde?.let { b ->
            if (caminoDe(t.figuras) { it.trazar }) {
                val caja = RectF()
                if (c.oscuro) camino.computeBounds(caja, true)
                ponerTrazo(b, sobrePapel(c, b.argb, caja.left, caja.top, caja.right, caja.bottom, raya = true))
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

    // ------------------------------------------------------------------------------- efectos

    private val silueta = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mezcla = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * **Lo que lleva efectos** (`a:effectLst`), como `con_efectos` del PC: debajo el brillo y la
     * sombra exterior, encima lo envuelto (desvanecido por el borde si lo pide) y la sombra
     * interior. Si algo falla (sin memoria, un lienzo raro), se pinta lo envuelto tal cual.
     */
    private fun conEfectos(c: Contexto, e: Pxof.Efectos, escala: Float) {
        val talCual = { for (h in e.elementos) elemento(c, h, escala) }
        if (c.nivel >= NIVELES_EFECTO || e.efectos.isEmpty()) { talCual(); return }
        val guardado = c.canvas.saveCount
        val hecho = try { efectosEnMapa(c, e, escala) } catch (_: OutOfMemoryError) { false } catch (_: RuntimeException) { false }
        if (!hecho) { c.canvas.restoreToCount(guardado); talCual() }
    }

    /** Lo de [conEfectos] con mapas de bits; false si no se pudo (y no se pintó nada). */
    private fun efectosEnMapa(c: Contexto, e: Pxof.Efectos, escala: Float): Boolean {
        val canvas = c.canvas
        val local = cajaDe(e.elementos) ?: return false
        val m = Matrix()
        @Suppress("DEPRECATION") canvas.getMatrix(m)
        val inversa = Matrix()
        if (!m.invert(inversa)) return false
        // Puntos → píxeles del lienzo.
        val mv = FloatArray(9).also { m.getValues(it) }
        // a b c d (x' = a·x + c·y): la parte lineal, para llevar desplazamientos de puntos a píxeles.
        val v = floatArrayOf(mv[Matrix.MSCALE_X], mv[Matrix.MSKEW_Y], mv[Matrix.MSKEW_X], mv[Matrix.MSCALE_Y])
        val k = kotlin.math.sqrt(kotlin.math.abs(v[0] * v[3] - v[1] * v[2])).takeIf { it.isFinite() && it > 0f } ?: return false
        val contenido = RectF(local)
        m.mapRect(contenido)
        // Margen para lo que se sale por fuera de la silueta dentro del mapa (la sombra interior).
        var margen = 2f
        var suave = 0f
        for (ef in e.efectos) when (ef) {
            is Pxof.Efecto.SombraInterior -> margen = maxOf(margen, 3f * desviacion(ef.desenfoque) * k + maxOf(kotlin.math.abs(ef.dx), kotlin.math.abs(ef.dy)) * k + 2f)
            is Pxof.Efecto.BordeSuave -> suave = ef.radio
            else -> {}
        }
        val zona = RectF(contenido.left - margen, contenido.top - margen, contenido.right + margen, contenido.bottom + margen)
        // Lo que cae fuera del lienzo (con margen para las sombras que entran) no se pinta.
        if (canvas.width > 0 && canvas.height > 0) {
            val holgura = maxOf(64f, canvas.width * 0.25f)
            if (!zona.intersect(-holgura, -holgura, canvas.width + holgura, canvas.height + holgura)) return true
        }
        if (zona.width() < 1f || zona.height() < 1f) return true
        // Sin pasar de ~4 megapíxeles: más grande, a menos resolución (una sombra no necesita más).
        val area = zona.width() * zona.height()
        val s = if (area > MAX_PIXELES) kotlin.math.sqrt(MAX_PIXELES / area) else 1f
        val w = kotlin.math.ceil(zona.width() * s).toInt().coerceAtLeast(1)
        val h = kotlin.math.ceil(zona.height() * s).toInt().coerceAtLeast(1)
        val dibujo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cd = Canvas(dibujo)
        cd.scale(s, s)
        cd.translate(-zona.left, -zona.top)
        cd.concat(m)
        // Del mapa al destino de verdad (para lo del modo oscuro).
        val alDestino = Matrix().apply { setScale(1f / s, 1f / s); postTranslate(zona.left, zona.top); c.aPrincipal?.let { postConcat(it) } }
        val sub = Contexto(cd, c.p, c.escala, c.total, c.nivel + 1, c.oscuro, alDestino)
        val guardados = rellenos.size
        for (x in e.elementos) elemento(sub, x, escala)
        // Lo apuntado en el mapa se vuelve a apuntar al pintar en vectores: fuera lo de ahora.
        while (rellenos.size > guardados) rellenos.removeAt(rellenos.size - 1)
        // El contenido, en píxeles del mapa.
        val enMapa = RectF((contenido.left - zona.left) * s, (contenido.top - zona.top) * s, (contenido.right - zona.left) * s, (contenido.bottom - zona.top) * s)
        val lineal = { dx: Float, dy: Float -> floatArrayOf((dx * v[0] + dy * v[2]) * s, (dx * v[1] + dy * v[3]) * s) }
        val px = k * s
        val desplazamiento = IntArray(2)

        canvas.save()
        canvas.concat(inversa)
        canvas.translate(zona.left, zona.top)
        canvas.scale(1f / s, 1f / s)
        // 1. El brillo: la silueta engordada y desenfocada, del color del brillo.
        for (ef in e.efectos) if (ef is Pxof.Efecto.Brillo && ef.radio * px >= 0.5f) {
            val a = alfa(dibujo, ef.radio * 0.5f * px, desplazamiento) ?: continue
            silueta.color = ef.argb
            // Dos veces: la silueta desenfocada más llena hacia fuera, como la engordada del PC.
            canvas.drawBitmap(a, desplazamiento[0].toFloat(), desplazamiento[1].toFloat(), silueta)
            canvas.drawBitmap(a, desplazamiento[0].toFloat(), desplazamiento[1].toFloat(), silueta)
            a.recycle()
        }
        // 2. La sombra exterior: movida, escalada y sesgada respecto al ancla de la caja.
        for (ef in e.efectos) if (ef is Pxof.Efecto.SombraExterior) {
            val a = alfa(dibujo, desviacion(ef.desenfoque) * px, desplazamiento) ?: continue
            val (ox, oy) = lineal(ef.dx, ef.dy).let { it[0] to it[1] }
            val ax = enMapa.left + ef.anclaX * enMapa.width()
            val ay = enMapa.top + ef.anclaY * enMapa.height()
            val tx = kotlin.math.tan(Math.toRadians(ef.sesgoX.toDouble())).toFloat()
            val ty = kotlin.math.tan(Math.toRadians(ef.sesgoY.toDouble())).toFloat()
            val t = Matrix()
            t.setValues(floatArrayOf(
                ef.escalaX, tx, ax - (ax * ef.escalaX + ay * tx) + ox,
                ty, ef.escalaY, ay - (ax * ty + ay * ef.escalaY) + oy,
                0f, 0f, 1f
            ))
            silueta.color = ef.argb
            canvas.save()
            canvas.concat(t)
            canvas.drawBitmap(a, desplazamiento[0].toFloat(), desplazamiento[1].toFloat(), silueta)
            canvas.restore()
            a.recycle()
        }
        // 3. Lo envuelto: desvanecido por el borde (desde el mapa) o en vectores, como siempre.
        val desvanecido = if (suave * px >= 0.5f) bordeSuave(dibujo, suave * px) else null
        if (desvanecido != null) {
            canvas.drawBitmap(desvanecido, 0f, 0f, mezcla)
            desvanecido.recycle()
        } else {
            canvas.restore()
            for (x in e.elementos) elemento(c, x, escala)
            canvas.save()
            canvas.concat(inversa)
            canvas.translate(zona.left, zona.top)
            canvas.scale(1f / s, 1f / s)
        }
        // 4. La sombra interior: el color fuera de la silueta movida, desenfocado y recortado por la silueta.
        for (ef in e.efectos) if (ef is Pxof.Efecto.SombraInterior) {
            val (ox, oy) = lineal(ef.dx, ef.dy).let { it[0] to it[1] }
            sombraInterior(dibujo, ef.argb, desviacion(ef.desenfoque) * px, ox, oy)?.let {
                canvas.drawBitmap(it, 0f, 0f, mezcla)
                it.recycle()
            }
        }
        canvas.restore()
        dibujo.recycle()
        return true
    }

    /** La desviación típica del desenfoque para un `blurRad` de DrawingML: la mitad, como el PC. */
    private fun desviacion(radio: Float) = (radio * 0.5f).coerceIn(0f, 250f)

    /** La silueta de [b] desenfocada [sigma] píxeles (de alfa), y dónde va en [desplazamiento]. */
    private fun alfa(b: Bitmap, sigma: Float, desplazamiento: IntArray): Bitmap? {
        val p = Paint()
        // `BlurMaskFilter` pide el radio; la desviación típica es ~0,57735 · radio.
        if (sigma >= 0.3f) p.maskFilter = BlurMaskFilter(sigma / 0.57735f, BlurMaskFilter.Blur.NORMAL)
        return b.extractAlpha(p, desplazamiento)
    }

    /** El borde suave (`a:softEdge`): [b] con la opacidad de su silueta, que se apaga hacia dentro [radio] píxeles. */
    private fun bordeSuave(b: Bitmap, radio: Float): Bitmap? {
        val d = IntArray(2)
        val a = alfa(b, radio * 0.5f, d) ?: return null
        // La silueta desenfocada vale ½ en el borde: 2·a − 1 la deja en 0 ahí y en 1 hacia dentro.
        val mascara = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cm = Canvas(mascara)
        val p = Paint().apply { color = android.graphics.Color.BLACK }
        val temporal = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(temporal).drawBitmap(a, d[0].toFloat(), d[1].toFloat(), p)
        a.recycle()
        cm.drawBitmap(temporal, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 2f, -255f,
            )))
        })
        temporal.recycle()
        val salida = b.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        Canvas(salida).drawBitmap(mascara, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        mascara.recycle()
        return salida
    }

    /** La sombra interior (`a:innerShdw`) de la silueta de [b], ya recortada por ella. */
    private fun sombraInterior(b: Bitmap, argb: Int, sigma: Float, dx: Float, dy: Float): Bitmap? {
        // Lleno de color salvo donde cae la silueta movida.
        val fuera = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cf = Canvas(fuera)
        cf.drawColor(android.graphics.Color.BLACK)
        cf.drawBitmap(b, dx, dy, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        val d = IntArray(2)
        val a = alfa(fuera, sigma, d)
        fuera.recycle()
        a ?: return null
        val salida = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cs = Canvas(salida)
        cs.drawBitmap(a, d[0].toFloat(), d[1].toFloat(), Paint().apply { color = argb })
        a.recycle()
        cs.drawBitmap(b, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        return salida
    }

    /** La caja de unos elementos en las coordenadas de ahora (aproximada, con el grosor de las rayas), o null si no ocupan nada. */
    internal fun cajaDe(els: List<Pxof.Elemento>): RectF? {
        val r = RectF()
        var hay = false
        fun unir(l: Float, t: Float, rr: Float, b: Float) {
            if (!(l.isFinite() && t.isFinite() && rr.isFinite() && b.isFinite())) return
            if (!hay) { r.set(minOf(l, rr), minOf(t, b), maxOf(l, rr), maxOf(t, b)); hay = true }
            else r.union(minOf(l, rr), minOf(t, b), maxOf(l, rr), maxOf(t, b))
        }
        for (e in els) when (e) {
            is Pxof.Texto -> unir(e.x, e.y - e.tam * 0.9f, e.x + e.ancho, e.y + e.tam * 0.3f)
            is Pxof.Rect -> { val g = (e.borde?.grosor ?: 0f) / 2; unir(e.x - g, e.y - g, e.x + e.ancho + g, e.y + e.alto + g) }
            is Pxof.Linea -> { val g = e.trazo.grosor / 2; unir(minOf(e.x1, e.x2) - g, minOf(e.y1, e.y2) - g, maxOf(e.x1, e.x2) + g, maxOf(e.y1, e.y2) + g) }
            is Pxof.Imagen -> unir(e.x, e.y, e.x + e.ancho, e.y + e.alto)
            is Pxof.Trayecto -> {
                val g = (e.borde?.grosor ?: 0f) / 2
                for (f in e.figuras) {
                    unir(f.x0 - g, f.y0 - g, f.x0 + g, f.y0 + g)
                    var i = 0
                    while (i + 1 < f.puntos.size) { unir(f.puntos[i] - g, f.puntos[i + 1] - g, f.puntos[i] + g, f.puntos[i + 1] + g); i += 2 }
                }
            }
            is Pxof.Grupo -> cajaDe(e.elementos)?.let { h ->
                val m = Matrix().apply { setValues(floatArrayOf(e.m[0], e.m[2], e.m[4], e.m[1], e.m[3], e.m[5], 0f, 0f, 1f)) }
                e.recorte?.let { h.intersect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }
                m.mapRect(h)
                unir(h.left, h.top, h.right, h.bottom)
            }
            is Pxof.Efectos -> cajaDe(e.elementos)?.let { unir(it.left, it.top, it.right, it.bottom) }
        }
        return if (hay && r.width() >= 0f && r.height() >= 0f) r else null
    }

    private fun foto(c: Contexto, e: Pxof.Imagen, escala: Float) {
        // **A lo que de verdad mide en el destino** (14-oct-2026, «algunas imágenes se ven en mala
        // calidad»): dentro de un grupo o un efecto el lienzo lleva su propio aumento, y pedir la
        // imagen solo a la escala de la hoja la traía pequeña y la estiraba.
        @Suppress("DEPRECATION") val m = c.canvas.matrix
        val v = floatArrayOf(e.ancho, 0f).also { m.mapVectors(it) }
        val enDestino = kotlin.math.hypot(v[0], v[1]) * (escala / c.escala.coerceAtLeast(1e-6f))
        val anchoPx = maxOf(e.ancho * escala, enDestino).toInt().coerceIn(16, 4096)
        val b = imagen(e.id, anchoPx) ?: return
        val r = e.recorte
        val src = if (r == null) null else android.graphics.Rect(
            (r[0] * b.width).toInt(), (r[1] * b.height).toInt(),
            ((1f - r[2]) * b.width).toInt().coerceAtLeast((r[0] * b.width).toInt() + 1),
            ((1f - r[3]) * b.height).toInt().coerceAtLeast((r[1] * b.height).toInt() + 1)
        )
        foto.alpha = (e.opacidad.coerceIn(0f, 1f) * 255).toInt()
        // **En modo noche, también las imágenes** (14-oct-2026, el usuario: «las imágenes no se
        // invierten de color en modo noche»), con la misma matriz que un PDF en oscuro.
        foto.colorFilter = if (c.oscuro) INVERTIDA else null
        c.canvas.drawBitmap(b, src, RectF(e.x, e.y, e.x + e.ancho, e.y + e.alto), foto)
        foto.colorFilter = null
    }

    private fun texto(c: Contexto, t: Pxof.Texto) {
        val n = t.glifos.size
        if (n == 0) return
        // En oscuro, legible sobre el papel si no lleva un relleno debajo.
        val color = sobrePapel(c, t.argb, t.x, t.y - t.tam * 0.9f, t.x + t.ancho.coerceAtLeast(1f), t.y + t.tam * 0.3f, raya = false)
        ultimoTexto = t.argb; ultimoTextoPintado = color; ultimoTextoX = t.x; hayUltimoTexto = true
        letra.color = color
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

    private companion object {
        /** La matriz del modo oscuro de los PDF, para las imágenes de una hoja en oscuro. */
        private val INVERTIDA by lazy { ColorMatrixColorFilter(com.forge.pixpin.pdf.MATRIZ_INVERTIDA) }

        /** Cuántos [Pxof.Efectos] anidados se pintan con sus efectos (los de más adentro, sin ellos). */
        const val NIVELES_EFECTO = 3
        /** El mapa de bits de un efecto, como mucho (más grande, a menos resolución). */
        const val MAX_PIXELES = 4_000_000f
        /** Las caras teñidas en el orden del PC: (tinte de la figura, color encima; 0 = ninguno). */
        val TINTES = listOf(5 to 0, 4 to 0x2EFFFFFF, 3 to 0x5CFFFFFF, 2 to 0x2E000000, 1 to 0x66000000)
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
