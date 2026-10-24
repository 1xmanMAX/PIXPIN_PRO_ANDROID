package com.forge.pixpin.excel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * **Una hoja de Excel en pantalla**, pintada con Canvas como la pinta el PC
 * (`pixpin_render::hoja_office`): cuadrícula, rellenos, bordes con la prioridad de Excel, texto con
 * su letra, alineación, ajuste, recorte y desborde sobre las vecinas vacías, combinaciones, imágenes,
 * gráficos sencillos, encabezados (A, B, C… / 1, 2, 3…) y paneles inmovilizados.
 *
 * **Lo que cuesta un fotograma depende de la pantalla, no de la hoja**: solo se mira lo que se ve,
 * cada trozo de filas llega ya resuelto ([LibroExcel.Hoja.pedir]) y lo medido de cada texto se guarda
 * en su celda. Se pinta en px de hoja al 100 % con el lienzo escalado, así que acercar no rehace nada.
 *
 * Gestos: un dedo desplaza (con inercia), dos acercan alrededor de los dedos; tocar elige una celda
 * (o una fila/columna en los encabezados, todo en la esquina); mantener y arrastrar elige un
 * rango, igual que arrastrar por los encabezados.
 *
 * Lo del PC del 12-oct (9975385): barras de datos completas, iconos, minigráficos, el triángulo rojo
 * de las notas, los botones del autofiltro y las flechas de las listas desplegables (solo para ver).
 * Y el **modo oscuro** ([oscuro], `pixpin_render::hoja_office` con `OpcionesHoja::oscuro`): papel,
 * rejilla y encabezados oscuros y el texto sin relleno debajo, claro; los rellenos, las imágenes y
 * los gráficos se quedan con sus colores ([com.forge.pixpin.office.Oscuro]).
 */
@SuppressLint("ViewConstructor")
class VistaHoja(context: Context, val libro: LibroExcel) : View(context) {
    private val densidad = resources.displayMetrics.density

    /** Lo que mira cada hoja (para volver a ella como se dejó). */
    class Estado {
        var x = 0.0
        var y = 0.0
        var zoom = 1.0
        var anclaF = 0
        var anclaC = 0
        var activaF = 0
        var activaC = 0
        var iniciado = false
    }

    private val estados = HashMap<Int, Estado>()
    var hoja: LibroExcel.Hoja? = null
        private set
    private var est = Estado()

    /** Se llama al cambiar la selección o lo que se ve (para la barra de arriba). */
    var alCambiar: (() -> Unit)? = null
    /** Un toque suelto en la hoja (para sacar o esconder los mandos, como en los otros visores). */
    var alTocar: (() -> Unit)? = null

    /** **Modo oscuro** (luna/sol del visor). */
    var oscuro: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            colorearMarco()
            invalidate()
        }

    private fun ui(c: Int) = com.forge.pixpin.office.Oscuro.si(oscuro, c)
    private fun papel() = ui(Color.WHITE)
    /** El relleno de una celda: el suyo, salvo el casi blanco en oscuro, que es papel. */
    private fun rellenoDe(c: Int) = if (oscuro && com.forge.pixpin.office.Oscuro.casiBlanco(c)) papel() else c
    /** Un borde sobre el papel: en oscuro el casi negro se aclara; los de color se quedan. */
    private fun raya(c: Int) = if (oscuro) com.forge.pixpin.office.Oscuro.rayaLegible(c) else c
    /** Un texto sobre el papel (sin relleno propio): en oscuro, si es oscuro, se aclara. */
    private fun legible(c: Int) = if (oscuro) com.forge.pixpin.office.Oscuro.legible(c) else c

    /** El color de un texto con el estilo [e]: con relleno propio (que no sea casi blanco) se queda como lo puso su autor. */
    private fun colorDeTexto(e: Pxhj.Estilo, argb: Int): Int =
        if (oscuro && (e.relleno == 0 || com.forge.pixpin.office.Oscuro.casiBlanco(e.relleno))) legible(argb) else argb

    private fun colorearMarco() {
        rejilla.color = ui(CUADRICULA)
        cabeceraTexto.color = ui(ENCABEZADO_TEXTO)
        avisoTexto.color = ui(0xFF666666.toInt())
    }

    /** La nota o el comentario de la celda (la de arriba a la izquierda si está combinada), si se sabe ya. */
    fun comentarioDe(f: Int, c: Int): Pxhj.Comentario? {
        val h = hoja ?: return null
        val (ff, cc) = h.geo?.combinadaEn(f, c)?.let { it.f0 to it.c0 } ?: (f to c)
        return h.trozo(ff / LibroExcel.FILAS_POR_TROZO)?.comentario(ff, cc)
    }

    /** Si la celda tiene una lista desplegable. */
    fun conLista(f: Int, c: Int): Boolean = hoja?.geo?.conLista(f, c) == true

    fun ponerHoja(i: Int) {
        hoja = libro.hoja(i)
        est = estados.getOrPut(i) { Estado() }
        scroller.forceFinished(true)
        invalidate()
        alCambiar?.invoke()
    }

    val zoom: Double get() = est.zoom
    val indice: Int get() = hoja?.indice ?: -1

    /** Lo elegido (filas y columnas), y la celda activa. */
    val seleccion: Pxhj.Rango
        get() = Pxhj.Rango(min(est.anclaF, est.activaF), min(est.anclaC, est.activaC), max(est.anclaF, est.activaF), max(est.anclaC, est.activaC))
    val activa: Pair<Int, Int> get() = est.activaF to est.activaC

    /** El texto que enseña la celda (la de arriba a la izquierda si está combinada); null si aún no se sabe. */
    fun textoDe(f: Int, c: Int): String? {
        val h = hoja ?: return null
        val g = h.geo
        val (ff, cc) = g?.combinadaEn(f, c)?.let { it.f0 to it.c0 } ?: (f to c)
        val t = h.trozo(ff / LibroExcel.FILAS_POR_TROZO) ?: run { h.pedir(ff / LibroExcel.FILAS_POR_TROZO); return null }
        return t.fila(ff)?.celda(cc)?.texto ?: ""
    }

    // ---------------------------------------------------------------------------- pinturas

    /** Las pinturas de los estilos de una hoja (solo en el hilo de la pantalla). */
    private class Pinturas {
        val texto = ArrayList<TextPaint?>()
        val metricas = ArrayList<Paint.FontMetrics?>()
        val tramos = HashMap<String, TextPaint>()
        /** Con qué modo se colorearon (al cambiar, se cambia el color de las mismas pinturas: los textos ajustados las guardan). */
        var oscuro = false
    }

    private fun pinturas(h: LibroExcel.Hoja): Pinturas {
        val p = (h.pintura as? Pinturas) ?: Pinturas().also { h.pintura = it; it.oscuro = oscuro }
        if (p.oscuro != oscuro) {
            p.oscuro = oscuro
            for (i in p.texto.indices) {
                val t = p.texto[i] ?: continue
                val e = h.estilos.getOrNull(i) ?: continue
                t.color = colorDeTexto(e, e.colorTexto)
            }
        }
        return p
    }

    private fun pinturaDe(h: LibroExcel.Hoja, p: Pinturas, i: Int): TextPaint? {
        if (i < p.texto.size) p.texto[i]?.let { return it }
        val e = h.estilos.getOrNull(i) ?: return null
        while (p.texto.size <= i) { p.texto.add(null); p.metricas.add(null) }
        val t = LetrasExcel.pintura(context.assets, e.familia, e.puntos, e.letra, colorDeTexto(e, e.colorTexto))
        p.texto[i] = t
        p.metricas[i] = t.fontMetrics
        return t
    }

    private val rejilla = Paint().apply { color = CUADRICULA; strokeWidth = 0f; style = Paint.Style.STROKE }
    private val relleno = Paint().apply { style = Paint.Style.FILL }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cabeceraTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ENCABEZADO_TEXTO; textAlign = Paint.Align.CENTER }
    private val avisoTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF666666.toInt(); textSize = 15 * densidad; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val camino = Path()
    private var lineas = FloatArray(4 * 256)
    private val discontinuos = HashMap<Int, DashPathEffect>()
    private var escalaDeLosDiscontinuos = -1f

    // ------------------------------------------------------------------------------ vista

    /** Px de pantalla por px de hoja. */
    private val e: Float get() = (densidad * est.zoom).toFloat()

    /** Lo que miden los encabezados (px de pantalla). */
    private var cabAncho = 0f
    private var cabAlto = 0f

    private fun medirEncabezados(g: Pxhj.Geometria) {
        val eh = encabezadoEscala()
        cabeceraTexto.textSize = 11f * 96f / 72f * eh
        val ultima = g.filaEn(est.y + (height / e).toDouble()) + 1
        val cifras = ultima.toString().length.coerceAtLeast(2)
        cabAncho = ceil((cifras * 8f + 14f) * eh)
        cabAlto = ceil(20f * eh)
    }

    private fun encabezadoEscala(): Float = (densidad * est.zoom.toFloat()).coerceIn(densidad * 0.75f, densidad * 1.4f)

    /** Un trozo de pantalla de la hoja (1, 2 o 4 con paneles inmovilizados). */
    private class Panel {
        var f0 = 0; var f1 = 0; var c0 = 0; var c1 = 0
        var x = 0f; var y = 0f; var ancho = 0f; var alto = 0f
        var ox = 0.0; var oy = 0.0
    }

    private val paneles = Array(4) { Panel() }
    private var nPaneles = 0

    /** Como `Rejilla::paneles` del PC, en px de pantalla relativos a la zona de celdas. */
    private fun calcularPaneles(g: Pxhj.Geometria, ancho: Float, alto: Float) {
        val e = e
        val cf = g.columnasFijas
        val ff = g.filasFijas
        val anchoFijo = min(g.x(cf) * e, ancho)
        val altoFijo = min(g.y(ff) * e, alto)
        val ox = max(est.x, g.x(cf).toDouble())
        val oy = max(est.y, g.y(ff).toDouble())
        nPaneles = 0
        fun panel(x: Float, y: Float, w: Float, h: Float, px: Double, py: Double, topeC: Int, topeF: Int) {
            if (w <= 0f || h <= 0f) return
            val p = paneles[nPaneles++]
            p.x = x; p.y = y; p.ancho = w; p.alto = h; p.ox = px; p.oy = py
            p.c0 = g.columnaEn(px); p.c1 = g.columnaEn(px + w / e)
            p.f0 = g.filaEn(py); p.f1 = g.filaEn(py + h / e)
            if (topeC > 0) p.c1 = min(p.c1, topeC - 1)
            if (topeF > 0) p.f1 = min(p.f1, topeF - 1)
        }
        if (cf > 0 && ff > 0) panel(0f, 0f, anchoFijo, altoFijo, 0.0, 0.0, cf, ff)
        if (ff > 0) panel(anchoFijo, 0f, ancho - anchoFijo, altoFijo, ox, 0.0, 0, ff)
        if (cf > 0) panel(0f, altoFijo, anchoFijo, alto - altoFijo, 0.0, oy, cf, 0)
        panel(anchoFijo, altoFijo, ancho - anchoFijo, alto - altoFijo, ox, oy, 0, 0)
    }

    private fun iniciar(g: Pxhj.Geometria) {
        if (est.iniciado) return
        est.iniciado = true
        est.zoom = (if (g.zoom in 10..400) g.zoom / 100.0 else 1.0).coerceIn(ZOOM_MINIMO, ZOOM_MAXIMO)
        est.x = g.x(max(g.columnasFijas, g.primeraColumna)).toDouble()
        est.y = g.y(max(g.filasFijas, g.primeraFila)).toDouble()
        if (g.filaActiva >= 0 && g.columnaActiva >= 0) {
            est.anclaF = g.filaActiva; est.activaF = g.filaActiva
            est.anclaC = g.columnaActiva; est.activaC = g.columnaActiva
        }
        post { alCambiar?.invoke() }
    }

    /** Hasta dónde se puede desplazar: (mínimo, máximo) en px de hoja, como `limites` del PC. */
    private fun limites(g: Pxhj.Geometria): DoubleArray {
        val e = e.toDouble()
        val anchoZona = (width - cabAncho) / e
        val altoZona = (height - cabAlto) / e
        val fx = g.x(g.columnasFijas).toDouble()
        val fy = g.y(g.filasFijas).toDouble()
        val usadoX = max(g.anchoUsado.toDouble(), g.x(g.ultimaColumna + 1).toDouble())
        val usadoY = max(g.altoUsado.toDouble(), g.y(g.ultimaFila + 1).toDouble())
        // Un poco más allá de lo usado, para que lo último no quede pegado al borde.
        val maxX = max(fx, usadoX + anchoZona * 0.35 - (anchoZona - fx))
        val maxY = max(fy, usadoY + altoZona * 0.35 - (altoZona - fy))
        return doubleArrayOf(fx, maxX, fy, maxY)
    }

    private fun acotar() {
        val g = hoja?.geo ?: return
        val l = limites(g)
        est.x = est.x.coerceIn(l[0], l[1])
        est.y = est.y.coerceIn(l[2], l[3])
    }

    // ------------------------------------------------------------------------------ pintar

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(papel())
        val h = hoja ?: return
        val g = h.geo
        if (g == null) {
            canvas.drawText(h.fallo ?: "Abriendo la hoja…", width / 2f, height / 2f, avisoTexto)
            return
        }
        iniciar(g)
        medirEncabezados(g)
        acotar()
        val ancho = width - cabAncho
        val alto = height - cabAlto
        if (ancho <= 0f || alto <= 0f) return
        calcularPaneles(g, ancho, alto)
        val p = pinturas(h)
        val e = e
        for (k in 0 until nPaneles) {
            val pa = paneles[k]
            pedirTrozos(h, pa.f0, pa.f1)
            canvas.save()
            canvas.clipRect(cabAncho + pa.x, cabAlto + pa.y, cabAncho + pa.x + pa.ancho, cabAlto + pa.y + pa.alto)
            canvas.translate(cabAncho + pa.x, cabAlto + pa.y)
            canvas.scale(e, e)
            pintarPanel(canvas, h, g, p, pa)
            canvas.restore()
        }
        // Las líneas de los paneles inmovilizados.
        relleno.color = ui(LINEA_FIJA)
        if (g.columnasFijas > 0) {
            val x = cabAncho + (g.x(g.columnasFijas) * e).toFloat()
            if (x < width) canvas.drawRect(x - 1f, cabAlto, x, height.toFloat(), relleno)
        }
        if (g.filasFijas > 0) {
            val y = cabAlto + (g.y(g.filasFijas) * e).toFloat()
            if (y < height) canvas.drawRect(cabAncho, y - 1f, width.toFloat(), y, relleno)
        }
        pintarSeleccion(canvas, g)
        pintarMarcosNumerados(canvas)
        pintarEncabezados(canvas, g)
    }

    // ---- **Los marcos numerados** (24-oct-2026, como `visor_hoja/marcos.rs` del PC) ----
    // En píxeles de la hoja al 100 % y con `parte` = la hoja (desde 0). Se pintan aquí, con la
    // hoja, y no en una capa encima: así van con ella en el mismo fotograma al desplazarla.

    /** Los marcos del libro (todas las hojas); los de esta se pintan. */
    var marcos: () -> List<com.forge.pixpin.motor.MarcosDelDocumento.Marco> = { emptyList() }
    /** Al imprimirlos, sin raya ni número. */
    var marcosOcultos = false
    private val pinturaDeMarco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.forge.pixpin.motor.MarcosDelDocumento.AZUL }
    private val numeroDeMarco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.DEFAULT_BOLD }

    /** Píxeles de pantalla por píxel de la hoja. */
    val escala: Double get() = e.toDouble()

    /** Un punto de la hoja (px al 100 %) en la vista, por el panel que se desplaza. */
    fun aVista(x: Double, y: Double): android.graphics.PointF? {
        val g = hoja?.geo ?: return null
        val e = e
        val anchoFijo = min(g.x(g.columnasFijas) * e, (width - cabAncho).coerceAtLeast(0f))
        val altoFijo = min(g.y(g.filasFijas) * e, (height - cabAlto).coerceAtLeast(0f))
        val ox = max(est.x, g.x(g.columnasFijas).toDouble())
        val oy = max(est.y, g.y(g.filasFijas).toDouble())
        return android.graphics.PointF((cabAncho + anchoFijo + (x - ox) * e).toFloat(), (cabAlto + altoFijo + (y - oy) * e).toFloat())
    }

    /** Y al revés: el punto de la hoja bajo un punto de la vista. */
    fun deVista(px: Float, py: Float): DoubleArray? {
        val g = hoja?.geo ?: return null
        val e = e.toDouble()
        val anchoFijo = min(g.x(g.columnasFijas) * e, (width - cabAncho).coerceAtLeast(0f).toDouble())
        val altoFijo = min(g.y(g.filasFijas) * e, (height - cabAlto).coerceAtLeast(0f).toDouble())
        val ox = max(est.x, g.x(g.columnasFijas).toDouble())
        val oy = max(est.y, g.y(g.filasFijas).toDouble())
        return doubleArrayOf(ox + (px - cabAncho - anchoFijo) / e, oy + (py - cabAlto - altoFijo) / e)
    }

    /** Mira desde ([x], [y]) de la hoja con el aumento [z] (las vistas de los marcadores y los marcos). */
    fun mirarDesde(x: Double, y: Double, z: Double) {
        est.zoom = z.coerceIn(ZOOM_MINIMO, ZOOM_MAXIMO)
        est.x = x; est.y = y; est.iniciado = true
        acotar()
        invalidate()
        alCambiar?.invoke()
    }

    /** Lo que ocupa la zona de las celdas, para encajar un marco. */
    val anchoDeCeldas: Float get() = (width - cabAncho).coerceAtLeast(1f)
    val altoDeCeldas: Float get() = (height - cabAlto).coerceAtLeast(1f)

    private fun pintarMarcosNumerados(canvas: Canvas) {
        if (marcosOcultos) return
        val lista = marcos()
        if (lista.isEmpty()) return
        val yo = indice
        canvas.save()
        canvas.clipRect(cabAncho, cabAlto, width.toFloat(), height.toFloat())
        pinturaDeMarco.strokeWidth = 2f * densidad
        numeroDeMarco.textSize = 12f * densidad
        lista.forEachIndexed { i, m ->
            if (m.parte != yo) return@forEachIndexed
            val a = aVista(m.x0, m.y0) ?: return@forEachIndexed
            val b = aVista(m.x1, m.y1) ?: return@forEachIndexed
            pinturaDeMarco.style = Paint.Style.STROKE
            canvas.drawRect(a.x, a.y, b.x, b.y, pinturaDeMarco)
            val n = (i + 1).toString()
            val w = numeroDeMarco.measureText(n)
            pinturaDeMarco.style = Paint.Style.FILL
            canvas.drawRect(a.x, a.y, a.x + w + 12 * densidad, a.y + 20 * densidad, pinturaDeMarco)
            canvas.drawText(n, a.x + 6 * densidad, a.y + 14.5f * densidad, numeroDeMarco)
        }
        canvas.restore()
    }

    /** Pide los trozos que se ven y uno más por cada lado (al desplazar ya están). */
    private fun pedirTrozos(h: LibroExcel.Hoja, f0: Int, f1: Int) {
        val n = LibroExcel.FILAS_POR_TROZO
        val k0 = max(0, f0 / n - 1)
        val k1 = f1 / n + 1
        for (k in k0..k1) if (h.trozo(k) == null) h.pedir(k)
    }

    /** Un texto ya colocado (en px de hoja relativos al panel). */
    private class Trabajo {
        var celda: Pxhj.Celda? = null
        var pintura: TextPaint? = null
        var texto: String = ""
        var x = 0f
        var y = 0f
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        /** 0 una línea, 1 ajustado (StaticLayout), 2 girado, 3 tramos. */
        var modo = 0
        var layout: StaticLayout? = null
        var grados = 0f
        var cx = 0f
        var cy = 0f
        var ancho = 0f
        var escalaLetra = 1f
    }

    private val trabajos = ArrayList<Trabajo>()
    private var usados = 0
    private fun trabajo(): Trabajo {
        if (usados == trabajos.size) trabajos.add(Trabajo())
        return trabajos[usados++].also { it.layout = null; it.modo = 0; it.escalaLetra = 1f; it.celda = null }
    }

    /** Los trozos de rejilla que tapa un texto desbordado: (x, y0, y1) en px de hoja relativos. */
    private var borrar = FloatArray(3 * 32)
    private var nBorrar = 0

    private val combinadasVisibles = ArrayList<Pxhj.Rango>()

    private fun pintarPanel(canvas: Canvas, h: LibroExcel.Hoja, g: Pxhj.Geometria, p: Pinturas, pa: Panel) {
        val e = e
        val u = 1f / e
        val ox = pa.ox
        val oy = pa.oy
        fun X(c: Int) = (g.x(c) - ox).toFloat()
        fun Y(f: Int) = (g.y(f) - oy).toFloat()
        val f0 = pa.f0; val f1 = pa.f1; val c0 = pa.c0; val c1 = pa.c1
        combinadasVisibles.clear()
        for (r in g.combinadas) if (r.f1 >= f0 && r.f0 <= f1) combinadasVisibles.add(r)
        val zonaL = X(c0); val zonaR = X(c1 + 1); val zonaT = Y(f0); val zonaB = Y(f1 + 1)
        val estilos = h.estilos

        // 1. Los textos primero: deciden qué rejilla tapa un texto que se desborda.
        usados = 0
        nBorrar = 0
        for (f in f0..f1) {
            val fila = h.fila(f) ?: continue
            if (fila.oculta || g.alto(f) <= 0) continue
            val cs = fila.celdas
            val ini = fila.desde(c0)
            val fin = fila.desde(c1 + 1)
            // La última con algo antes de la zona y la primera después: pueden desbordarse hasta aquí.
            var antes = ini - 1
            while (antes >= 0 && cs[antes].clase == 0) antes--
            var despues = fin
            while (despues < cs.size && cs[despues].clase == 0) despues++
            if (antes >= 0 && g.combinadaEn(f, cs[antes].col) == null) prepararTexto(h, g, p, fila, cs[antes], f, cs[antes].col, f, cs[antes].col, false, ox, oy, u)
            for (k in ini until fin) {
                val c = cs[k]
                if (c.clase == 0 || c.texto.isEmpty()) continue
                if (combinadasVisibles.isNotEmpty() && enCombinada(f, c.col) != null) continue
                prepararTexto(h, g, p, fila, c, f, c.col, f, c.col, false, ox, oy, u)
            }
            if (despues < cs.size && g.combinadaEn(f, cs[despues].col) == null) prepararTexto(h, g, p, fila, cs[despues], f, cs[despues].col, f, cs[despues].col, false, ox, oy, u)
        }
        for (r in combinadasVisibles) {
            val fila = h.fila(r.f0) ?: continue
            val c = fila.celda(r.c0) ?: continue
            if (c.clase != 0 && c.texto.isNotEmpty()) prepararTexto(h, g, p, fila, c, r.f0, r.c0, r.f1, r.c1, true, ox, oy, u)
        }

        // 2. Cuadrícula (el último píxel de cada columna y fila, como Excel).
        if (g.cuadricula) {
            var n = 0
            fun linea(x0: Float, y0: Float, x1: Float, y1: Float) {
                if (n + 4 > lineas.size) lineas = lineas.copyOf(lineas.size * 2)
                lineas[n++] = x0; lineas[n++] = y0; lineas[n++] = x1; lineas[n++] = y1
            }
            for (c in c0..c1) {
                if (g.ancho(c) <= 0) continue
                val x = X(c + 1) - u / 2
                linea(x, zonaT, x, zonaB)
            }
            val ultima = min(f1, f0 + 2000)
            for (f in f0..ultima) {
                if (g.alto(f) <= 0) continue
                val y = Y(f + 1) - u / 2
                linea(zonaL, y, zonaR, y)
            }
            canvas.drawLines(lineas, 0, n, rejilla)
            relleno.color = papel()
            for (r in combinadasVisibles) canvas.drawRect(X(r.c0), Y(r.f0), X(r.c1 + 1) - u, Y(r.f1 + 1) - u, relleno)
            for (k in 0 until nBorrar) {
                val x = borrar[3 * k]
                canvas.drawRect(x - u, borrar[3 * k + 1], x, borrar[3 * k + 2], relleno)
            }
        }

        // 3. Rellenos: columnas enteras, filas, celdas, combinaciones; y las barras de datos.
        val rc = g.rellenosDeColumnas
        var k = 0
        while (k + 2 < rc.size) {
            val a = max(rc[k], c0); val b = min(rc[k + 1], c1)
            if (a <= b) { relleno.color = rellenoDe(rc[k + 2]); canvas.drawRect(X(a) - u, zonaT, X(b + 1), zonaB, relleno) }
            k += 3
        }
        for (f in f0..f1) {
            val fila = h.fila(f) ?: continue
            val y0 = Y(f); val y1 = Y(f + 1)
            if (y1 <= y0) continue
            if (fila.relleno != 0) { relleno.color = rellenoDe(fila.relleno); canvas.drawRect(zonaL, y0 - u, zonaR, y1, relleno) }
            val cs = fila.celdas
            for (i in fila.desde(c0) until fila.desde(c1 + 1)) {
                val c = cs[i]
                val es = estilos.getOrNull(c.estilo) ?: continue
                if (es.relleno != 0) {
                    if (combinadasVisibles.isNotEmpty() && enCombinada(f, c.col) != null) continue
                    relleno.color = rellenoDe(es.relleno)
                    canvas.drawRect(X(c.col) - u, y0 - u, X(c.col + 1), y1, relleno)
                }
                c.barra?.let { barra(canvas, it, X(c.col), y0, X(c.col + 1), y1, u) }
                if (c.conjunto >= 0) {
                    // El icono a la izquierda de la celda, como Excel.
                    val lado = max(4f, min((y1 - y0 - u) * 0.8f, 15f))
                    IconosExcel.pintar(canvas, c.conjunto, c.icono, X(c.col) + 2f + lado / 2, (y0 + y1 - u) / 2, lado)
                }
            }
        }
        for (r in combinadasVisibles) {
            val c = h.fila(r.f0)?.celda(r.c0) ?: continue
            val es = estilos.getOrNull(c.estilo) ?: continue
            if (es.relleno != 0) { relleno.color = rellenoDe(es.relleno); canvas.drawRect(X(r.c0) - u, Y(r.f0) - u, X(r.c1 + 1), Y(r.f1 + 1), relleno) }
        }

        // 3b. Minigráficos.
        pintarMinigraficos(canvas, h, g, f0, f1, c0, c1, ox, oy, u)

        // 4. Bordes, ya decididos lado a lado (de menos a más fuertes).
        pintarBordes(canvas, h, g, f0, f1, c0, c1, ox, oy, u, e)

        // 5. Textos.
        for (i in 0 until usados) dibujar(canvas, trabajos[i])

        // 6. Dibujos (imágenes, gráficos, formas).
        for (d in g.dibujos) {
            val x0 = (d.x - ox).toFloat(); val y0 = (d.y - oy).toFloat()
            if (d.ancho <= 0f || d.alto <= 0f || x0 > zonaR || y0 > zonaB || x0 + d.ancho < zonaL || y0 + d.alto < zonaT) continue
            pintarDibujo(canvas, d, x0, y0, u)
        }

        // 7. Encima: las flechas de las listas, los botones del autofiltro y el triángulo de las notas.
        pintarMarcas(canvas, h, g, f0, f1, c0, c1, ox, oy, u)
    }

    /** **Una barra de datos** dentro de la celda (x0, y0)-(x1, y1), como el PC: desde/hasta, degradado o lisa, borde y eje. */
    private fun barra(canvas: Canvas, b: Pxhj.Barra, x0: Float, y0: Float, x1: Float, y1: Float, u: Float) {
        val margen = max(2f, u)
        val ix0 = x0 + margen
        val iw = x1 - x0 - u - 2 * margen
        if (iw <= 0f) return
        val alto = max(u, y1 - y0 - u - 2 * margen)
        val l = ix0 + iw * b.desde
        val r = l + max(u, iw * (b.hasta - b.desde))
        val t = y0 + margen
        if (b.degradado) {
            // Del color al casi blanco, como las barras de 2007.
            relleno.shader = android.graphics.LinearGradient(l, 0f, r, 0f, b.argb, mezclar(b.argb, Color.WHITE, 0.9f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, t + alto, relleno)
            relleno.shader = null
        } else {
            relleno.color = b.argb
            canvas.drawRect(l, t, r, t + alto, relleno)
        }
        if (b.borde != 0) {
            trazo.color = b.borde; trazo.strokeWidth = u; trazo.pathEffect = null
            canvas.drawRect(l + u / 2, t + u / 2, r - u / 2, t + alto - u / 2, trazo)
        }
        if (b.eje >= 0f) {
            val xe = ix0 + iw * b.eje
            relleno.color = b.colorEje
            canvas.drawRect(xe, y0 + margen * 0.5f, xe + u, y1 - u - margen * 0.5f, relleno)
        }
    }

    /** [a] mezclado con [b] en la proporción [t] (0 = a, 1 = b). */
    private fun mezclar(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        return Color.argb(255, m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    private val tramoMini = Path()

    /**
     * **Minigráficos** (`x14:sparklineGroups`) de las celdas a la vista: línea, columnas o
     * ganancia/pérdida, con marcadores (`pintar_minigraficos` del PC; el eje y los puntos ya vienen
     * calculados del .so).
     */
    private fun pintarMinigraficos(canvas: Canvas, h: LibroExcel.Hoja, g: Pxhj.Geometria, f0: Int, f1: Int, c0: Int, c1: Int, ox: Double, oy: Double, u: Float) {
        val n = LibroExcel.FILAS_POR_TROZO
        for (k in f0 / n..f1 / n) {
            val t = h.trozo(k) ?: continue
            for (mi in t.minis) {
                if (mi.fila < f0 || mi.fila > f1 || mi.col < c0 || mi.col > c1 || g.alto(mi.fila) <= 0 || g.ancho(mi.col) <= 0) continue
                val r = g.combinadaEn(mi.fila, mi.col) ?: Pxhj.Rango(mi.fila, mi.col, mi.fila, mi.col)
                val pad = 3f
                val bx0 = (g.x(r.c0) - ox).toFloat() + pad; val by0 = (g.y(r.f0) - oy).toFloat() + pad
                val bx1 = (g.x(r.c1 + 1) - ox).toFloat() - u - pad; val by1 = (g.y(r.f1 + 1) - oy).toFloat() - u - pad
                minigrafico(canvas, mi, bx0, by0, bx1, by1, u)
            }
        }
    }

    private fun minigrafico(canvas: Canvas, mi: Pxhj.Mini, bx0: Float, by0: Float, bx1: Float, by1: Float, u: Float) {
        val w = bx1 - bx0; val h = by1 - by0
        if (w < 4f * u || h < 4f * u) return
        val p = mi.puntos
        val n = p.size
        if (n == 0 || mi.maximo <= mi.minimo) return
        val lo = mi.minimo; val hi = mi.maximo
        fun yDe(v: Float) = by1 - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() * h
        val yCero = yDe(0f).coerceIn(by0, by1)
        // El primer máximo y el primer mínimo (los de «alto» y «bajo»).
        var iAlto = -1; var iBajo = -1
        for (i in 0 until n) {
            val v = p[i]
            if (v.isNaN()) continue
            if (iAlto < 0 || v > p[iAlto]) iAlto = i
            if (iBajo < 0 || v < p[iBajo]) iBajo = i
        }
        if (iAlto < 0) return
        val c = mi.colores
        fun colorDe(i: Int, v: Float): Int = when {
            mi.tiene(2) && i == iAlto -> c[6]
            mi.tiene(4) && i == iBajo -> c[7]
            mi.tiene(8) && i == 0 -> c[4]
            mi.tiene(16) && i == n - 1 -> c[5]
            mi.tiene(32) && v < 0f -> c[1]
            mi.tipo == 0 -> c[3]
            else -> c[0]
        }
        canvas.save()
        canvas.clipRect(bx0 - 3f, by0 - 3f, bx1 + 3f, by1 + 3f)
        if (mi.tiene(64) && lo < 0 && hi > 0) {
            relleno.color = c[2]
            canvas.drawRect(bx0, yCero, bx1, yCero + u, relleno)
        }
        if (mi.tipo == 0) {
            fun xDe(i: Int) = if (n > 1) bx0 + w * i / (n - 1) else bx0 + w / 2
            val grosor = max(u, mi.peso * 96f / 72f)
            trazo.color = c[0]; trazo.strokeWidth = grosor; trazo.pathEffect = null
            trazo.strokeJoin = Paint.Join.ROUND
            // Tramos seguidos; un hueco corta la línea salvo que se pida unir.
            tramoMini.rewind()
            var abierto = false
            for (i in 0 until n) {
                val v = p[i]
                if (v.isNaN()) { if (!mi.tiene(128)) abierto = false; continue }
                if (abierto) tramoMini.lineTo(xDe(i), yDe(v)) else { tramoMini.moveTo(xDe(i), yDe(v)); abierto = true }
            }
            canvas.drawPath(tramoMini, trazo)
            trazo.strokeJoin = Paint.Join.MITER
            val lado = max(grosor * 2.6f, 3f * u)
            for (i in 0 until n) {
                val v = p[i]
                if (v.isNaN()) continue
                val resaltado = (mi.tiene(2) && i == iAlto) || (mi.tiene(4) && i == iBajo) || (mi.tiene(8) && i == 0) ||
                    (mi.tiene(16) && i == n - 1) || (mi.tiene(32) && v < 0f)
                if (mi.tiene(1) || resaltado) {
                    relleno.color = colorDe(i, v)
                    canvas.drawRect(xDe(i) - lado / 2, yDe(v) - lado / 2, xDe(i) + lado / 2, yDe(v) + lado / 2, relleno)
                }
            }
        } else {
            val ancho = w / n
            val hueco = min(max(u, ancho * 0.2f), 3f)
            for (i in 0 until n) {
                val v = p[i]
                if (v.isNaN() || v == 0f) continue
                val x0 = bx0 + ancho * i + hueco / 2
                val x1 = max(x0 + u, bx0 + ancho * (i + 1) - hueco / 2)
                val (ya, yb) = if (mi.tipo == 2) {
                    val medio = by0 + h / 2
                    if (v > 0f) by0 to medio - u else medio + u to by1
                } else { val y = yDe(v); min(y, yCero) to max(y, yCero) }
                relleno.color = colorDe(i, v)
                canvas.drawRect(x0, ya, x1, max(yb, ya + u), relleno)
            }
        }
        canvas.restore()
    }

    /** La caja del botón de una lista o de un filtro: pegado abajo a la derecha de la celda, de 17 × 20 px como mucho (`caja_flecha` del PC). */
    private fun cajaFlecha(x0: Float, y0: Float, x1: Float, y1: Float, u: Float, salida: RectF): Boolean {
        val ancho = 17f
        val alto = min(20f, y1 - y0 - u)
        if (x1 - x0 < ancho * 1.6f || alto < 8f) return false
        salida.set(x1 - ancho - u, y1 - alto - u, x1 - u, y1 - u)
        return true
    }

    private val cajaBoton = RectF()

    /** Un triángulo hacia abajo de [ancho] centrado en [cx], con la punta en [yPunta]. */
    private fun trianguloAbajo(canvas: Canvas, cx: Float, yPunta: Float, ancho: Float, color: Int) {
        camino.rewind()
        camino.moveTo(cx - ancho / 2, yPunta - ancho / 2)
        camino.lineTo(cx + ancho / 2, yPunta - ancho / 2)
        camino.lineTo(cx, yPunta)
        camino.close()
        relleno.color = color
        canvas.drawPath(camino, relleno)
    }

    /** El botón entero (fondo, borde y lo de dentro: triángulo o embudo). */
    private fun boton(canvas: Canvas, r: RectF, u: Float, filtrado: Boolean, colorFondo: Int, colorBorde: Int, colorTinta: Int) {
        relleno.color = colorBorde
        canvas.drawRect(r, relleno)
        relleno.color = colorFondo
        canvas.drawRect(r.left + u, r.top + u, r.right - u, r.bottom - u, relleno)
        val cx = r.centerX(); val cy = r.centerY()
        if (filtrado) {
            // El embudo: filtro puesto.
            val a = 4f; val hh = 7.2f
            camino.rewind()
            camino.moveTo(cx - a, cy - hh / 2); camino.lineTo(cx + a, cy - hh / 2)
            camino.lineTo(cx + a * 0.18f, cy + hh * 0.05f); camino.lineTo(cx + a * 0.18f, cy + hh / 2)
            camino.lineTo(cx - a * 0.18f, cy + hh / 2 - hh * 0.1f); camino.lineTo(cx - a * 0.18f, cy + hh * 0.05f)
            camino.close()
            relleno.color = FILTRO_ACTIVO
            canvas.drawPath(camino, relleno)
        } else trianguloAbajo(canvas, cx, cy + 2.5f, 7f, colorTinta)
    }

    /**
     * **Marcas encima de las celdas** (solo para ver), como `pintar_flechas` y `pintar_marcas` del
     * PC: el botón de la lista desplegable en la celda activa y un triangulito discreto en las demás
     * con lista; los botones del autofiltro en su fila de encabezado; y el triángulo rojo de las
     * notas en la esquina de arriba a la derecha.
     */
    private fun pintarMarcas(canvas: Canvas, h: LibroExcel.Hoja, g: Pxhj.Geometria, f0: Int, f1: Int, c0: Int, c1: Int, ox: Double, oy: Double, u: Float) {
        fun X(c: Int) = (g.x(c) - ox).toFloat()
        fun Y(f: Int) = (g.y(f) - oy).toFloat()
        if (g.listas.isNotEmpty()) {
            var pintadas = 0
            for (lista in g.listas) for (r in lista) {
                for (f in max(r.f0, f0)..min(r.f1, f1)) {
                    if (g.alto(f) <= 0) continue
                    for (c in max(r.c0, c0)..min(r.c1, c1)) {
                        if (pintadas >= 4000) break
                        val x0 = X(c); val x1 = X(c + 1); val y0 = Y(f); val y1 = Y(f + 1)
                        if (f == est.activaF && c == est.activaC) {
                            if (cajaFlecha(x0, y0, x1, y1, u, cajaBoton)) boton(canvas, cajaBoton, u, false, ui(FLECHA_FONDO), ui(FLECHA_BORDE), ui(FLECHA_TINTA))
                        } else if (x1 - x0 >= 14f && y1 - y0 >= 8f) {
                            trianguloAbajo(canvas, x1 - 6f, y1 - 3f, 6f, ui(FLECHA_TENUE))
                        }
                        pintadas++
                    }
                }
            }
        }
        for (b in g.botones) {
            if (b.fila < f0 || b.fila > f1 || b.col < c0 || b.col > c1 || g.alto(b.fila) <= 0 || g.ancho(b.col) <= 0) continue
            if (cajaFlecha(X(b.col), Y(b.fila), X(b.col + 1), Y(b.fila + 1), u, cajaBoton)) boton(canvas, cajaBoton, u, b.filtrado, FLECHA_FONDO, FLECHA_BORDE, FLECHA_TINTA)
        }
        val n = LibroExcel.FILAS_POR_TROZO
        for (k in f0 / n..f1 / n) {
            val t = h.trozo(k) ?: continue
            for (cm in t.comentarios) {
                if (cm.fila < f0 || cm.fila > f1 || cm.col < c0 || cm.col > c1 || g.alto(cm.fila) <= 0 || g.ancho(cm.col) <= 0) continue
                // En una combinación, la esquina de toda su caja.
                val r = g.combinadaEn(cm.fila, cm.col) ?: Pxhj.Rango(cm.fila, cm.col, cm.fila, cm.col)
                val x1 = X(r.c1 + 1) - u; val y0 = Y(r.f0)
                val lado = max(6f, 4f * u)
                if (X(r.c1 + 1) - X(r.c0) < lado * 1.5f) continue
                camino.rewind()
                camino.moveTo(x1 - lado, y0); camino.lineTo(x1, y0); camino.lineTo(x1, y0 + lado); camino.close()
                relleno.color = COMENTARIO_ROJO
                canvas.drawPath(camino, relleno)
            }
        }
    }

    private fun enCombinada(f: Int, c: Int): Pxhj.Rango? {
        for (r in combinadasVisibles) if (r.contiene(f, c)) return r
        return null
    }

    /** ¿Vacía y fuera de toda combinación? (lo que deja que un texto se desborde sobre ella). */
    private fun libre(g: Pxhj.Geometria, fila: Pxhj.Fila, f: Int, c: Int): Boolean {
        val celda = fila.celda(c)
        if (celda != null && celda.clase != 0) return false
        return g.combinadaEn(f, c) == null
    }

    private fun medir(t: TextPaint, c: Pxhj.Celda): Float {
        if (c.medida < 0f) c.medida = t.measureText(c.texto)
        return c.medida
    }

    /** Coloca el texto de una celda (o de su combinación [cf0]..[cf1] × [cc0]..[cc1]): como `texto_de_celda` del PC. */
    private fun prepararTexto(
        h: LibroExcel.Hoja, g: Pxhj.Geometria, p: Pinturas, fila: Pxhj.Fila, c: Pxhj.Celda,
        cf0: Int, cc0: Int, cf1: Int, cc1: Int, combinada: Boolean, ox: Double, oy: Double, u: Float
    ) {
        if (c.texto.isEmpty()) return
        val es = h.estilos.getOrNull(c.estilo) ?: return
        val pintura = pinturaDe(h, p, c.estilo) ?: return
        val fm = p.metricas[c.estilo] ?: return
        val x0 = (g.x(cc0) - ox).toFloat(); val x1 = (g.x(cc1 + 1) - ox).toFloat()
        val y0 = (g.y(cf0) - oy).toFloat(); val y1 = (g.y(cf1 + 1) - oy).toFloat()
        if (x1 <= x0 || y1 <= y0) return
        val margen = 2f
        val anchoUtil = max(1f, x1 - x0 - u - 2 * margen)
        val sangria = es.sangria * 9f
        val ajustar = (es.ajustar || es.horizontal == 5 || es.horizontal == 7 || es.vertical == 3) && es.giro == 0 || es.giro == 255
        val altoCelda = y1 - y0 - u
        val tr = trabajo()
        tr.celda = c
        tr.pintura = pintura
        tr.texto = c.texto
        tr.l = x0; tr.t = y0; tr.r = x1 - u; tr.b = y1 - u
        if (ajustar) {
            val texto = if (es.giro == 255) c.texto.toList().joinToString("\n") else c.texto
            val ancho = max(1, (anchoUtil - sangria).roundToInt())
            val guardado = c.cache as? StaticLayout
            val layout = if (guardado != null && guardado.width == ancho) guardado else {
                val alin = when (es.horizontal) {
                    2, 6 -> Layout.Alignment.ALIGN_CENTER
                    3 -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                StaticLayout.Builder.obtain(texto, 0, texto.length, pintura, ancho).setAlignment(alin).setIncludePad(false).build().also { c.cache = it }
            }
            val alto = layout.height.toFloat()
            tr.modo = 1
            tr.layout = layout
            tr.x = x0 + margen + if (es.horizontal == 1 || es.horizontal == 0) sangria else 0f
            tr.y = when (es.vertical) {
                0 -> y0 + 1f
                1, 3, 4 -> y0 + (altoCelda - alto) / 2
                else -> y1 - u - alto - min(1f, max(0f, (altoCelda - alto) / 2))
            }
            return
        }
        var w = medir(pintura, c)
        val alto = fm.descent - fm.ascent
        // Un número que no cabe: «###».
        if (c.clase == 1 && es.giro == 0 && w > anchoUtil + 0.5f) {
            val almohadilla = max(1f, pintura.measureText("#"))
            tr.texto = "#".repeat(max(1, floor(anchoUtil / almohadilla).toInt()))
            w = almohadilla * tr.texto.length
        }
        // Encoger hasta que quepa.
        if (es.encoger && w > anchoUtil) { tr.escalaLetra = max(0.1f, anchoUtil / w); w *= tr.escalaLetra }
        // Rellenar: repetir hasta llenar.
        if (es.horizontal == 4 && w > 0f && w < anchoUtil) {
            val n = max(1, floor(anchoUtil / w).toInt())
            tr.texto = c.texto.repeat(n)
            w *= n
        }
        val top = when (es.vertical) {
            0 -> y0 + 1f
            1, 3, 4 -> y0 + ((altoCelda - alto) / 2)
            else -> y1 - u - alto - min(1f, max(0f, (altoCelda - alto) / 2))
        }
        val base = top - fm.ascent * tr.escalaLetra
        tr.y = base
        // El contable (`*`): lo de antes del relleno a la izquierda y lo demás a la derecha.
        if (c.corte in 1 until tr.texto.length && tr.texto === c.texto) {
            val izq = c.texto.substring(0, c.corte)
            val der = c.texto.substring(c.corte)
            tr.texto = izq
            tr.x = x0 + margen
            val t2 = trabajo()
            t2.celda = c; t2.pintura = pintura; t2.texto = der; t2.y = base
            t2.l = tr.l; t2.t = tr.t; t2.r = tr.r; t2.b = tr.b
            t2.x = x1 - u - margen - pintura.measureText(der)
            return
        }
        // Girado.
        val grados = es.grados
        if (grados != null && grados != 0f) {
            val a = Math.toRadians(grados.toDouble())
            val bw = (w * abs(cos(a)) + alto * abs(sin(a))).toFloat()
            val bh = (w * abs(sin(a)) + alto * abs(cos(a))).toFloat()
            tr.modo = 2
            tr.grados = grados
            tr.ancho = w
            tr.cx = when (es.horizontal) {
                1 -> x0 + margen + bw / 2
                3 -> x1 - u - margen - bw / 2
                0 -> if (grados > 0f) x1 - u - margen - bw / 2 else x0 + margen + bw / 2
                else -> (x0 + x1 - u) / 2
            }
            tr.cy = when (es.vertical) {
                0 -> y0 + margen + bh / 2
                2 -> y1 - u - margen - bh / 2
                else -> (y0 + y1 - u) / 2
            }
            tr.y = -alto / 2 - fm.ascent
            return
        }
        if (c.tramos != null && tr.texto === c.texto) tr.modo = 3
        // Dónde va y hasta dónde se desborda.
        val sobra = w - anchoUtil + sangria
        val puedeDesbordar = !combinada && sobra > 0f && c.clase == 2 && es.horizontal != 4
        var izq = cc0
        var der = cc1
        when (es.horizontal) {
            3 -> {
                if (puedeDesbordar) {
                    var falta = sobra
                    var n = 0
                    while (falta > 0f && izq > 0 && n++ < 64 && libre(g, fila, cf0, izq - 1)) { izq--; falta -= g.ancho(izq) }
                }
                tr.x = x1 - u - margen - w - sangria
            }
            2, 6 -> {
                var derX = x1
                if (es.horizontal == 6) {
                    var n = 0
                    while (n++ < 64 && der + 1 < Pxhj.MAX_COLUMNAS && libre(g, fila, cf0, der + 1) &&
                        (fila.celda(der + 1)?.let { h.estilos.getOrNull(it.estilo)?.horizontal } == 6)
                    ) der++
                    derX = (g.x(der + 1) - ox).toFloat()
                }
                val centro = (x0 + derX - u) / 2
                if (puedeDesbordar) {
                    var falta = (w - (derX - x0)) / 2
                    var n = 0
                    while (falta > 0f && izq > 0 && n++ < 64 && libre(g, fila, cf0, izq - 1) && libre(g, fila, cf0, der + 1)) {
                        izq--; der++
                        falta -= min(g.ancho(izq), g.ancho(der))
                    }
                }
                tr.x = centro - w / 2
            }
            else -> {
                if (puedeDesbordar) {
                    var falta = sobra
                    var n = 0
                    while (falta > 0f && der + 1 < Pxhj.MAX_COLUMNAS && n++ < 64 && libre(g, fila, cf0, der + 1)) { der++; falta -= g.ancho(der) }
                }
                tr.x = x0 + margen + sangria
            }
        }
        tr.ancho = w
        if (izq < cc0 || der > cc1) {
            tr.l = (g.x(izq) - ox).toFloat()
            tr.r = (g.x(der + 1) - ox).toFloat() - u
            // La rejilla que cruza el texto desbordado no se ve.
            for (k in izq until der) {
                val bx = (g.x(k + 1) - ox).toFloat()
                if (tr.x < bx && tr.x + w > bx - u) {
                    if (3 * (nBorrar + 1) > borrar.size) borrar = borrar.copyOf(borrar.size * 2)
                    borrar[3 * nBorrar] = bx; borrar[3 * nBorrar + 1] = y0; borrar[3 * nBorrar + 2] = y1 - u
                    nBorrar++
                }
            }
        }
    }

    private fun dibujar(canvas: Canvas, t: Trabajo) {
        val pintura = t.pintura ?: return
        canvas.save()
        canvas.clipRect(t.l, t.t, t.r, t.b)
        val antes = pintura.textSize
        if (t.escalaLetra != 1f) pintura.textSize = antes * t.escalaLetra
        when (t.modo) {
            1 -> { canvas.translate(t.x, t.y); t.layout?.draw(canvas) }
            2 -> {
                canvas.rotate(-t.grados, t.cx, t.cy)
                canvas.drawText(t.texto, t.cx - t.ancho / 2, t.cy + t.y, pintura)
            }
            3 -> dibujarTramos(canvas, t, pintura)
            else -> canvas.drawText(t.texto, t.x, t.y, pintura)
        }
        if (t.escalaLetra != 1f) pintura.textSize = antes
        canvas.restore()
    }

    /** Texto rico: cada tramo con su letra, uno detrás de otro en la misma línea. */
    private fun dibujarTramos(canvas: Canvas, t: Trabajo, base: TextPaint) {
        val c = t.celda ?: return
        val tramos = c.tramos ?: return
        val p = hoja?.let { pinturas(it) } ?: return
        var x = t.x
        var desde = 0
        for (tr in tramos) {
            val hasta = min(c.texto.length, desde + tr.largo)
            if (hasta <= desde) continue
            val clave = "${tr.familia}|${tr.puntos}|${tr.letra}|${tr.argb}"
            val es = hoja?.estilos?.getOrNull(c.estilo)
            val argb = if (es != null) colorDeTexto(es, tr.argb) else tr.argb
            val pt = p.tramos.getOrPut("$clave|$argb") { LetrasExcel.pintura(context.assets, tr.familia, tr.puntos, tr.letra, argb) }
            canvas.drawText(c.texto, desde, hasta, x, t.y, pt)
            x += pt.measureText(c.texto, desde, hasta)
            desde = hasta
        }
        if (desde < c.texto.length) canvas.drawText(c.texto, desde, c.texto.length, x, t.y, base)
    }

    private fun pintarBordes(
        canvas: Canvas, h: LibroExcel.Hoja, g: Pxhj.Geometria, f0: Int, f1: Int, c0: Int, c1: Int,
        ox: Double, oy: Double, u: Float, e: Float
    ) {
        if (escalaDeLosDiscontinuos != e) { discontinuos.clear(); escalaDeLosDiscontinuos = e }
        val n = LibroExcel.FILAS_POR_TROZO
        for (k in f0 / n..f1 / n) {
            val l = h.trozo(k)?.lados ?: continue
            for (i in 0 until l.size) {
                val tipo = l.tipos[i].toInt()
                val f = l.filas[i]
                val c = l.columnas[i]
                if (c < c0 - 1 || c > c1 + 1 || f < f0 - 1 || f > f1 + 1) continue
                val estilo = l.estilos[i].toInt()
                val color = l.colores[i]
                when (tipo) {
                    0 -> {
                        if (g.ancho(c) <= 0) continue
                        val b = (g.y(f) - oy).toFloat()
                        lado(canvas, true, b, (g.x(c) - ox).toFloat(), (g.x(c + 1) - ox).toFloat(), estilo, raya(color), u)
                    }
                    1 -> {
                        if (g.alto(f) <= 0) continue
                        val b = (g.x(c) - ox).toFloat()
                        lado(canvas, false, b, (g.y(f) - oy).toFloat(), (g.y(f + 1) - oy).toFloat(), estilo, raya(color), u)
                    }
                    else -> {
                        val x0 = (g.x(c) - ox).toFloat(); val x1 = (g.x(c + 1) - ox).toFloat()
                        val y0 = (g.y(f) - oy).toFloat(); val y1 = (g.y(f + 1) - oy).toFloat()
                        trazo.color = raya(color)
                        trazo.strokeWidth = Pxhj.Linea.grosor(estilo) * u
                        trazo.pathEffect = null
                        if (tipo == 2) canvas.drawLine(x0, y0, x1, y1, trazo) else canvas.drawLine(x0, y1, x1, y0, trazo)
                    }
                }
            }
        }
    }

    /** Una línea de borde sobre la frontera [b], de [a0] a [a1] (px de hoja), con grosores en px de pantalla como el PC. */
    private fun lado(canvas: Canvas, horizontal: Boolean, b: Float, a0: Float, a1: Float, estilo: Int, color: Int, u: Float) {
        fun tramo(desde: Float, hasta: Float, extra: Float) {
            if (horizontal) canvas.drawRect(a0 - (1 + extra) * u, b + desde * u, a1 + extra * u, b + hasta * u, relleno)
            else canvas.drawRect(b + desde * u, a0 - (1 + extra) * u, b + hasta * u, a1 + extra * u, relleno)
        }
        relleno.color = color
        when (estilo) {
            Pxhj.Linea.FINO, Pxhj.Linea.PELO -> tramo(-1f, 0f, 0f)
            Pxhj.Linea.MEDIO -> tramo(-1f, 1f, 1f)
            Pxhj.Linea.GRUESO -> tramo(-2f, 1f, 1f)
            Pxhj.Linea.DOBLE -> {
                tramo(-2f, 1f, 1f)
                relleno.color = papel()
                tramo(-1f, 0f, -1f)
            }
            Pxhj.Linea.NINGUNO -> {}
            else -> {
                val grosor = Pxhj.Linea.grosor(estilo)
                trazo.color = color
                trazo.strokeWidth = grosor * u
                trazo.pathEffect = discontinuos.getOrPut(estilo) { DashPathEffect(patron(estilo).map { it * u }.toFloatArray(), 0f) }
                val centro = if (grosor.toInt() % 2 == 1) b - 0.5f * u else b
                if (horizontal) canvas.drawLine(a0 - u, centro, a1, centro, trazo) else canvas.drawLine(centro, a0 - u, centro, a1, trazo)
                trazo.pathEffect = null
            }
        }
    }

    /** Rayas y huecos (px de pantalla) de cada línea discontinua. */
    private fun patron(estilo: Int): FloatArray = when (estilo) {
        Pxhj.Linea.PUNTEADO -> floatArrayOf(1f, 1f)
        Pxhj.Linea.DISCONTINUO -> floatArrayOf(3f, 1f)
        Pxhj.Linea.MEDIO_DISCONTINUO -> floatArrayOf(9f, 3f)
        Pxhj.Linea.RAYA_PUNTO -> floatArrayOf(9f, 3f, 3f, 3f)
        Pxhj.Linea.MEDIO_RAYA_PUNTO, Pxhj.Linea.RAYA_PUNTO_INCLINADA -> floatArrayOf(9f, 3f, 3f, 3f)
        Pxhj.Linea.RAYA_PUNTO_PUNTO, Pxhj.Linea.MEDIO_RAYA_PUNTO_PUNTO -> floatArrayOf(9f, 3f, 3f, 3f, 3f, 3f)
        else -> floatArrayOf(2f, 2f)
    }

    private val origen = Rect()

    private fun pintarDibujo(canvas: Canvas, d: Pxhj.Dibujo, x: Float, y: Float, u: Float) {
        rect.set(x, y, x + d.ancho, y + d.alto)
        when (d) {
            is Pxhj.Dibujo.Imagen -> {
                val bmp = libro.imagen(d.ruta)
                if (bmp == null) {
                    relleno.color = 0xFFF2F2F2.toInt(); canvas.drawRect(rect, relleno)
                    return
                }
                val r = d.recorte
                origen.set(
                    (bmp.width * r[0]).roundToInt(), (bmp.height * r[1]).roundToInt(),
                    (bmp.width * (1 - r[2])).roundToInt().coerceAtLeast(1), (bmp.height * (1 - r[3])).roundToInt().coerceAtLeast(1)
                )
                canvas.drawBitmap(bmp, origen, rect, imagenPintura)
            }
            is Pxhj.Dibujo.Forma -> {
                if (d.relleno != 0) { relleno.color = d.relleno; canvas.drawRect(rect, relleno) }
                if (d.linea != 0) { trazo.color = d.linea; trazo.strokeWidth = u; trazo.pathEffect = null; canvas.drawRect(rect, trazo) }
                if (d.texto.isNotEmpty()) textoEnCaja(canvas, d.texto, rect, d.alinear, d.vertical, 0xFF000000.toInt())
            }
            is Pxhj.Dibujo.Grafico -> GraficoExcel.pintar(canvas, d, rect, u, graficoTexto)
        }
    }

    private val imagenPintura = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val graficoTexto = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply { textSize = 12f; color = 0xFF404040.toInt() }

    private fun textoEnCaja(canvas: Canvas, texto: String, r: RectF, alinear: Int, vertical: Int, color: Int) {
        graficoTexto.color = color
        val ancho = max(1, (r.width() - 8f).roundToInt())
        val alin = when (alinear) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val l = StaticLayout.Builder.obtain(texto, 0, texto.length, graficoTexto, ancho).setAlignment(alin).build()
        val y = when (vertical) { 1 -> r.top + (r.height() - l.height) / 2; 2 -> r.bottom - 4f - l.height; else -> r.top + 4f }
        canvas.save()
        canvas.clipRect(r)
        canvas.translate(r.left + 4f, y)
        l.draw(canvas)
        canvas.restore()
        graficoTexto.color = 0xFF404040.toInt()
    }

    private fun pintarSeleccion(canvas: Canvas, g: Pxhj.Geometria) {
        val s = seleccion
        val e = e
        canvas.save()
        canvas.clipRect(cabAncho, cabAlto, width.toFloat(), height.toFloat())
        for (k in 0 until nPaneles) {
            val pa = paneles[k]
            canvas.save()
            canvas.clipRect(cabAncho + pa.x, cabAlto + pa.y, cabAncho + pa.x + pa.ancho, cabAlto + pa.y + pa.alto)
            fun sx(c: Int) = cabAncho + pa.x + ((g.x(c) - pa.ox) * e).toFloat()
            fun sy(f: Int) = cabAlto + pa.y + ((g.y(f) - pa.oy) * e).toFloat()
            val x0 = sx(s.c0); val x1 = sx(s.c1 + 1); val y0 = sy(s.f0); val y1 = sy(s.f1 + 1)
            if ((s.f0 != s.f1 || s.c0 != s.c1) && g.combinadaEn(s.f0, s.c0) != s) {
                relleno.color = SELECCION_VELO
                canvas.drawRect(x0, y0, x1, y1, relleno)
            }
            trazo.color = legible(SELECCION)
            trazo.strokeWidth = 2f * densidad
            trazo.pathEffect = null
            canvas.drawRect(x0 - densidad * 0.5f, y0 - densidad * 0.5f, x1 - densidad * 0.5f, y1 - densidad * 0.5f, trazo)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun pintarEncabezados(canvas: Canvas, g: Pxhj.Geometria) {
        val e = e
        val s = seleccion
        relleno.color = ui(ENCABEZADO_FONDO)
        canvas.drawRect(0f, 0f, width.toFloat(), cabAlto, relleno)
        canvas.drawRect(0f, 0f, cabAncho, height.toFloat(), relleno)
        val fm = cabeceraTexto.fontMetrics
        val baseCol = cabAlto / 2 - (fm.ascent + fm.descent) / 2
        val todas = s.f0 == 0 && s.f1 >= Pxhj.MAX_FILAS - 1
        val todasCol = s.c0 == 0 && s.c1 >= Pxhj.MAX_COLUMNAS - 1
        for (k in 0 until nPaneles) {
            val pa = paneles[k]
            // Columnas: los paneles de arriba (y = 0); filas: los de la izquierda (x = 0).
            if (pa.y == 0f) {
                canvas.save()
                canvas.clipRect(cabAncho + pa.x, 0f, cabAncho + pa.x + pa.ancho, cabAlto)
                for (c in pa.c0..pa.c1) {
                    val w = g.ancho(c)
                    if (w <= 0) continue
                    val x0 = cabAncho + pa.x + ((g.x(c) - pa.ox) * e).toFloat()
                    val x1 = x0 + w * e
                    if (c in s.c0..s.c1) { relleno.color = ui(if (todas) ENCABEZADO_ELEGIDO_FUERTE else ENCABEZADO_ELEGIDO); canvas.drawRect(x0, 0f, x1, cabAlto, relleno) }
                    relleno.color = ui(ENCABEZADO_LINEA)
                    canvas.drawRect(x1 - 1f, 0f, x1, cabAlto, relleno)
                    if (w * e > cabeceraTexto.textSize * 0.8f) canvas.drawText(nombreColumna(c), (x0 + x1) / 2, baseCol, cabeceraTexto)
                }
                canvas.restore()
            }
            if (pa.x == 0f) {
                canvas.save()
                canvas.clipRect(0f, cabAlto + pa.y, cabAncho, cabAlto + pa.y + pa.alto)
                for (f in pa.f0..pa.f1) {
                    val a = g.alto(f)
                    if (a <= 0) continue
                    val y0 = cabAlto + pa.y + ((g.y(f) - pa.oy) * e).toFloat()
                    val y1 = y0 + a * e
                    if (f in s.f0..s.f1) { relleno.color = ui(if (todasCol) ENCABEZADO_ELEGIDO_FUERTE else ENCABEZADO_ELEGIDO); canvas.drawRect(0f, y0, cabAncho, y1, relleno) }
                    relleno.color = ui(ENCABEZADO_LINEA)
                    canvas.drawRect(0f, y1 - 1f, cabAncho, y1, relleno)
                    if (a * e > cabeceraTexto.textSize * 0.7f) canvas.drawText((f + 1).toString(), cabAncho / 2, (y0 + y1) / 2 - (fm.ascent + fm.descent) / 2, cabeceraTexto)
                }
                canvas.restore()
            }
        }
        relleno.color = ui(ENCABEZADO_LINEA)
        canvas.drawRect(0f, cabAlto - 1f, width.toFloat(), cabAlto, relleno)
        canvas.drawRect(cabAncho - 1f, 0f, cabAncho, height.toFloat(), relleno)
        // La esquina: elige todo.
        relleno.color = ui(ENCABEZADO_ESQUINA)
        camino.reset()
        camino.moveTo(cabAncho - 4f * densidad, cabAlto - 4f * densidad - cabAlto * 0.45f)
        camino.lineTo(cabAncho - 4f * densidad, cabAlto - 4f * densidad)
        camino.lineTo(cabAncho - 4f * densidad - cabAlto * 0.45f, cabAlto - 4f * densidad)
        camino.close()
        canvas.drawPath(camino, relleno)
    }

    private val nombres = HashMap<Int, String>()
    private fun nombreColumna(c: Int): String = nombres.getOrPut(c) { ExcelTexto.nombreColumna(c) }

    // ------------------------------------------------------------------------------ gestos

    private val scroller = OverScroller(context)

    /** Qué hace el dedo que arrastra: 0 desplazar, 1 elegir celdas, 2 elegir columnas, 3 elegir filas. */
    private var arrastre = 0

    private val gestos = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(ev: MotionEvent): Boolean {
            scroller.forceFinished(true)
            arrastre = when {
                ev.y < cabAlto && ev.x >= cabAncho -> 2
                ev.x < cabAncho && ev.y >= cabAlto -> 3
                else -> 0
            }
            if (arrastre == 2 || arrastre == 3) elegirEncabezado(ev.x, ev.y, extender = false)
            return true
        }

        override fun onSingleTapUp(ev: MotionEvent): Boolean {
            alTocar?.invoke()
            if (ev.x < cabAncho && ev.y < cabAlto) { elegirTodo(); return true }
            if (arrastre == 2 || arrastre == 3) return true
            val c = celdaEn(ev.x, ev.y) ?: return true
            // En una combinación se elige entera (la activa es su esquina de arriba a la izquierda).
            val r = hoja?.geo?.combinadaEn(c.first, c.second) ?: Pxhj.Rango(c.first, c.second, c.first, c.second)
            est.activaF = r.f0; est.activaC = r.c0
            est.anclaF = r.f1; est.anclaC = r.c1
            invalidate()
            alCambiar?.invoke()
            return true
        }

        override fun onLongPress(ev: MotionEvent) {
            if (arrastre != 0 || escalando) return
            val c = celdaEn(ev.x, ev.y) ?: return
            arrastre = 1
            est.anclaF = c.first; est.anclaC = c.second
            est.activaF = c.first; est.activaC = c.second
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            alCambiar?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (escalando) return true
            when (arrastre) {
                // Desplazar no avisa a las barras: no cambia nada de lo que enseñan.
                0 -> { est.x += dx / e; est.y += dy / e; acotar(); invalidate() }
                1 -> {} // lo lleva onTouchEvent (tras mantener, GestureDetector ya no avisa)
                else -> elegirEncabezado(e2.x, e2.y, extender = true)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (arrastre != 0 || escalando) return true
            val g = hoja?.geo ?: return true
            val l = limites(g)
            val e = e
            scroller.fling(
                (est.x * e).toInt(), (est.y * e).toInt(), -vx.toInt(), -vy.toInt(),
                (l[0] * e).toInt(), (l[1] * e).toInt(), (l[2] * e).toInt(), (l[3] * e).toInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(ev: MotionEvent): Boolean {
            // Doble toque: al 100 % o al doble, alrededor del dedo.
            val nuevo = if (est.zoom < 1.5) 2.0 else 1.0
            zoomEn(nuevo, ev.x, ev.y)
            return true
        }
    })

    private var escalando = false

    private val pinza = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { escalando = true; scroller.forceFinished(true); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomEn(est.zoom * d.scaleFactor, d.focusX, d.focusY); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { escalando = false }
    })

    /** **Zoom alrededor del dedo**, eje a eje (`con_foco` del PC): lo que hay bajo el dedo sigue bajo el dedo. */
    private fun zoomEn(z: Double, px: Float, py: Float) {
        val g = hoja?.geo ?: return
        val e0 = e.toDouble()
        est.zoom = z.coerceIn(ZOOM_MINIMO, ZOOM_MAXIMO)
        val e1 = e.toDouble()
        fun foco(pos: Double, raton: Double, fijo: Double): Double {
            if (raton < fijo * e0) return pos
            val punto = pos + (raton - fijo * e0) / e0
            return punto - (raton - fijo * e1) / e1
        }
        est.x = foco(est.x, (px - cabAncho).toDouble(), g.x(g.columnasFijas).toDouble())
        est.y = foco(est.y, (py - cabAlto).toDouble(), g.y(g.filasFijas).toDouble())
        acotar()
        invalidate()
        alCambiar?.invoke()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val e = e
            est.x = scroller.currX / e.toDouble()
            est.y = scroller.currY / e.toDouble()
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        pinza.onTouchEvent(ev)
        gestos.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && arrastre == 1 && !escalando) extenderA(ev.x, ev.y)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (arrastre != 0) alCambiar?.invoke()
            arrastre = 0
        }
        return true
    }

    /** La celda bajo un punto de la vista (fila, columna), o null fuera de las celdas. */
    fun celdaEn(x: Float, y: Float): Pair<Int, Int>? {
        val g = hoja?.geo ?: return null
        val px = x - cabAncho
        val py = y - cabAlto
        val e = e
        for (k in 0 until nPaneles) {
            val p = paneles[k]
            if (px >= p.x && px < p.x + p.ancho && py >= p.y && py < p.y + p.alto) {
                val hx = p.ox + (px - p.x) / e
                val hy = p.oy + (py - p.y) / e
                return g.filaEn(hy) to g.columnaEn(hx)
            }
        }
        return null
    }

    /** Arrastrando para elegir: la activa va con el dedo; cerca del borde, la hoja corre. */
    private fun extenderA(x: Float, y: Float) {
        val borde = 28 * densidad
        var corrio = false
        if (x > width - borde) { est.x += 12 / e; corrio = true }
        if (y > height - borde) { est.y += 12 / e; corrio = true }
        if (x < cabAncho + borde) { est.x -= 12 / e; corrio = true }
        if (y < cabAlto + borde) { est.y -= 12 / e; corrio = true }
        if (corrio) acotar()
        val c = celdaEn(x.coerceIn(cabAncho + 1, width - 1f), y.coerceIn(cabAlto + 1, height - 1f)) ?: return
        if (c.first == est.activaF && c.second == est.activaC && !corrio) return
        est.activaF = c.first; est.activaC = c.second
        invalidate()
        alCambiar?.invoke()
    }

    private fun elegirEncabezado(x: Float, y: Float, extender: Boolean) {
        val c = celdaEn(x.coerceAtLeast(cabAncho + 1), y.coerceAtLeast(cabAlto + 1)) ?: return
        if (arrastre == 2) {
            if (!extender) { est.anclaC = c.second; est.anclaF = 0 }
            est.activaC = c.second; est.activaF = Pxhj.MAX_FILAS - 1
            if (!extender) est.anclaF = 0
        } else {
            if (!extender) { est.anclaF = c.first; est.anclaC = 0 }
            est.activaF = c.first; est.activaC = Pxhj.MAX_COLUMNAS - 1
        }
        invalidate()
        alCambiar?.invoke()
    }

    private fun elegirTodo() {
        est.anclaF = 0; est.anclaC = 0
        est.activaF = Pxhj.MAX_FILAS - 1; est.activaC = Pxhj.MAX_COLUMNAS - 1
        invalidate()
        alCambiar?.invoke()
    }

    /** Lleva la vista a la celda [f], [c] y la elige (un enlace a un sitio del libro). */
    fun irA(f: Int, c: Int) {
        val g = hoja?.geo
        est.anclaF = f; est.anclaC = c; est.activaF = f; est.activaC = c
        if (g != null) {
            est.x = max(g.x(g.columnasFijas).toDouble(), g.x(c).toDouble() - 20)
            est.y = max(g.y(g.filasFijas).toDouble(), g.y(f).toDouble() - 20)
            acotar()
        } else est.iniciado = true
        invalidate()
        alCambiar?.invoke()
    }

    /** Lo elegido, sin pasar de lo usado (una fila o columna entera elegida, solo hasta lo que tiene algo). */
    fun seleccionUsada(): Pxhj.Rango {
        val s = seleccion
        val g = hoja?.geo ?: return s
        return Pxhj.Rango(s.f0, s.c0, min(s.f1, max(s.f0, g.ultimaFila)), min(s.c1, max(s.c0, g.ultimaColumna)))
    }

    companion object {
        const val ZOOM_MINIMO = 0.25
        const val ZOOM_MAXIMO = 4.0

        /** Colores del marco de Excel (los de la interfaz, no los del libro), como el PC. */
        private val CUADRICULA = 0xFFD4D4D4.toInt()
        private val ENCABEZADO_FONDO = 0xFFF5F5F5.toInt()
        private val ENCABEZADO_LINEA = 0xFFD4D4D4.toInt()
        private val ENCABEZADO_TEXTO = 0xFF444444.toInt()
        private val ENCABEZADO_ELEGIDO = 0xFFD2E5DA.toInt()
        private val ENCABEZADO_ELEGIDO_FUERTE = 0xFFA9CDB8.toInt()
        private val ENCABEZADO_ESQUINA = 0xFFBDBDBD.toInt()
        private val LINEA_FIJA = 0xFF9B9B9B.toInt()
        private val SELECCION = 0xFF217346.toInt()
        private val SELECCION_VELO = 0x22217346
        /** Los botones de las listas y del autofiltro, y el triángulo de las notas (los del PC). */
        private val FLECHA_FONDO = 0xFFF2F2F2.toInt()
        private val FLECHA_BORDE = 0xFFA6A6A6.toInt()
        private val FLECHA_TINTA = 0xFF404040.toInt()
        private val FLECHA_TENUE = 0xFF9A9A9A.toInt()
        private val FILTRO_ACTIVO = 0xFF1F6FB5.toInt()
        private val COMENTARIO_ROJO = 0xFFE03030.toInt()
    }
}
