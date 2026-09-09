package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * **El plano del PDF pintado como líneas, en la pantalla del teléfono.**
 *
 * Es la otra manera de traer un plano: en vez de rasterizarlo por cuadros —ver
 * [ElMosaicoDelPapel], que extrae el papel a trozos y los guarda en disco— aquí se lee **la
 * geometría** del PDF ([PlanoDePdf]) y se pinta directamente. La diferencia se nota en las dos
 * cosas de las que se quejaba el plano por cuadros:
 *
 * - **No hay nada que cargar al acercarse.** Una imagen, por muchos píxeles que tenga, se
 *   acaba: pasado su aumento hay que volver al PDF a por más, y eso son segundos de espera
 *   cada vez que uno se acerca. Unas líneas no se acaban nunca: la misma raya vale para
 *   mirarla de lejos y para pegarle la nariz.
 * - **Se ve nítido siempre**, incluidas las letras y las cotas, que es donde se veía el grano.
 *
 * Lo que cuesta es **abrirlo**: hay que leer el PDF entero una vez (unos segundos en un plano
 * grande) y guardar sus segmentos en memoria. A cambio, después no se vuelve a tocar el
 * archivo nunca.
 *
 * ## Cómo se pinta deprisa
 *
 * Un plano de verdad son un millón y medio de puntos y hay que repintarlos en cada fotograma
 * mientras se mueve el dedo. Tres cosas lo hacen posible, las mismas que en el visor de la
 * página web:
 *
 * - **Segmentos sueltos en un `FloatArray`**, no objetos: se pintan con un solo
 *   `drawLines` por tanda, que es una llamada a Skia y no un millón.
 * - **Tandas con su caja**: la tanda que no toca la pantalla ni se mira.
 * - **Dos niveles de detalle**: de lejos, las rayas que no llegan a un píxel no se pintan —no
 *   se pueden ver— y eso quita el noventa por ciento del trabajo justo cuando hay más a la
 *   vista.
 */
class PlanoEnPantalla private constructor(
    /** Lo que mide el papel en unidades del dibujo. */
    val ancho: Double,
    val alto: Double,
    /** Las rayas, por tandas. Se abre para poder comprobarlas: ver `PlanoEnPantallaTest`. */
    internal val tandas: List<Tanda>,
    internal val rellenos: List<Relleno>,
    internal val textos: List<Rotulo>,
    private val fotos: List<Foto>,
    /** Las capas del PDF, por si algún día se encienden y apagan también aquí. */
    val capas: List<PlanoDePdf.Capa>
) {
    /**
     * Si hay rayas de sobra como para que pintarlas todas de lejos se note. Ver [nivelDeLejos].
     */
    private val muchasRayas: Boolean by lazy { tandas.sumOf { it.puntos.size / 4 } > MUCHAS_RAYAS }

    /**
     * **Cuándo se pasa al nivel basto.** [pixel] es lo que mide un píxel en unidades del dibujo.
     *
     * Antes bastaba con que una unidad bajara de un píxel, y eso es «la página cabe en la
     * pantalla»: en una hoja de texto, donde casi todo son trazos de letra de menos de una
     * unidad, se iba el 90 % del dibujo de golpe al alejarse y volvía al acercarse. Ahora las
     * cortas se dejan solo cuando de verdad no llegan a un tercio de píxel, y en un plano
     * enorme —donde pintarlas todas cuesta— con el criterio de antes.
     */
    private fun nivelDeLejos(pixel: Float): Boolean =
        pixel * 0.3f > LARGO_QUE_SE_VE || (muchasRayas && pixel > LARGO_QUE_SE_VE)


    /**
     * **Quién pinta la lámina y a quién se avisa cuando está.**
     *
     * Sin esto el plano se pinta a pelo en cada fotograma, que es lo que hay que hacer al
     * exportar —una sola vez, sin pantalla de por medio— y lo que **no** se puede hacer
     * mientras alguien mueve el dedo. Ver [pintar].
     */
    private var ambito: CoroutineScope? = null
    private var alLlegar: (() -> Unit)? = null

    fun conObrero(ambito: CoroutineScope, alLlegar: () -> Unit): PlanoEnPantalla {
        this.ambito = ambito
        this.alLlegar = alLlegar
        return this
    }

    /** Una lámina ya pintada: el mapa de bits y **qué trozo del dibujo** cubre. */
    private class Lamina(
        val bmp: Bitmap,
        val x0: Double, val y0: Double, val x1: Double, val y1: Double,
        val pxPorUnidad: Double
    )

    @Volatile
    private var lamina: Lamina? = null
    private var obrero: Job? = null

    /**
     * **El plano entero, pintado una vez y a poca resolución.**
     *
     * Va debajo de la lámina fina y no se rehace nunca. Es lo que hace que pasear no llegue
     * nunca a enseñar papel en blanco: aunque te salgas de la zona pintada al detalle, debajo
     * sigue estando el plano —más basto, pero el plano—, igual que se veía la página de una
     * pieza antes de que hubiera cuadros.
     */
    @Volatile
    private var base: Lamina? = null
    private var obreroDeLaBase: Job? = null

    /**
     * **El plano grabado como lista de dibujo, para la tarjeta gráfica.**
     *
     * Es lo mismo que hace el visor web con WebGL —subir las rayas una vez y que mover sea
     * cambiar la cámara— con lo que Android da para ello: un [RenderNode]. Se graban las
     * órdenes una sola vez y en cada fotograma el lienzo acelerado las **reproduce** con el
     * zoom y el desplazamiento que toque; no se vuelve a recorrer ningún array. Las rayas
     * finas van a grosor **cero**, que en Skia es «un píxel de pantalla sea cual sea el
     * aumento», igual que las líneas de WebGL.
     *
     * Dos nodos, uno de lejos —solo las rayas que llegan a un píxel— y otro de cerca. Solo se
     * usan cuando el lienzo va por la tarjeta ([Canvas.isHardwareAccelerated]); al exportar
     * y en las pruebas, que van por software, se sigue por las láminas.
     */
    private class Grabado(
        val nodo: android.graphics.RenderNode,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float
    )

    /**
     * Lo grabado: el fondo (papel, fotos y manchas), **una tanda de rayas por nodo** —con su
     * caja, para reproducir solo las que tocan la pantalla— en dos versiones (de cerca todas,
     * de lejos solo las que llegan a un píxel), y los rótulos encima. null es «aún no»;
     * vacío es «no se pudo», y entonces se sigue por las láminas para siempre, que es mejor
     * que ir cambiando de camino a mitad de un gesto.
     */
    private class Grabados(
        val fondo: android.graphics.RenderNode,
        val cerca: List<Grabado>,
        val lejos: List<Grabado>,
        val rotulos: android.graphics.RenderNode
    )
    @Volatile
    private var grabados: Grabados? = null
    @Volatile
    private var sinGrabar = false
    private var obreroDeGrabar: Job? = null

    /** Un montón de rayas del mismo color y grosor, con su caja para poder saltárselas. */
    class Tanda(
        val color: Int,
        val grosor: Float,
        val alfa: Int,
        /** `x0,y0,x1,y1` por raya, en unidades del dibujo. */
        val puntos: FloatArray,
        /** Las mismas rayas, quitando las que de lejos no se ven. Ver [LARGO_QUE_SE_VE]. */
        val gordas: FloatArray,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float
    )

    /** Una mancha: va como camino porque hay que rellenarla. */
    class Relleno(val color: Int, val alfa: Int, val camino: Path)

    /** Un rótulo con la matriz que lo coloca. Ver [PlanoDePdf.Texto]. */
    class Rotulo(
        val texto: String,
        val color: Int,
        val alfa: Int,
        val matriz: Matrix,
        val tam: Float,
        val ancho: Float,
        val familia: android.graphics.Typeface
    )

    /** Una foto del PDF, ya descomprimida. */
    class Foto(val bitmap: Bitmap, val matriz: Matrix, val alfa: Int)

    /**
     * El pincel de la pantalla. **No lo toca el obrero**: los suyos se hacen dentro de
     * [dibujar], porque dos hilos moviendo el mismo `Paint` pintan cualquier cosa.
     */
    private val pincelDeLamina = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * **Pinta el plano, y casi nunca lo dibuja.**
     *
     * Un plano de verdad son un millón y medio de rayas: eso no se puede recorrer sesenta
     * veces por segundo en un teléfono, por bien que se salte lo que no se ve. Lo que se hace
     * es lo mismo que en el visor de la página web —y lo que hace cualquier mapa—: **se pinta
     * una vez en una lámina aparte y lo que se enseña es la lámina**, estirada mientras la
     * mano se mueve, y se vuelve a pintar cuando para o cuando ya no sirve.
     *
     * El lienzo llega **en coordenadas del dibujo** (el [Renderer] le aplicó el zoom y el
     * desplazamiento), así que la lámina se coloca por su rectángulo en unidades y de la
     * escala se encarga el propio lienzo.
     */
    fun pintar(canvas: Canvas, vista: Bounds, zoom: Double) {
        val ambito = this.ambito
        if (canvas.isHardwareAccelerated && android.os.Build.VERSION.SDK_INT >= 29 && !sinGrabar) {
            val g = grabados
            if (g != null) { pintarGrabado(canvas, vista, zoom, g); return }
            if (ambito != null) pedirGrabado(ambito)
            // Mientras se graba, las láminas de siempre.
        }
        if (ambito == null) {
            // Sin obrero —al exportar— se dibuja de verdad: es una vez y no hay nadie mirando.
            dibujar(canvas, vista, zoom)
            return
        }
        // **Recortado al papel.** Las láminas van en RGB_565, que no tiene transparencia:
        // lo que no se pinta queda negro, y la lámina fina sobresale del papel por los
        // cuatro lados. Sin este recorte, la pantalla salía negra con la página en medio y,
        // al mover, la lámina vieja y la nueva se turnaban tapando trozos distintos: el
        // parpadeo blanco-negro que reportó el usuario con un PDF corriente.
        canvas.save()
        canvas.clipRect(0f, 0f, ancho.toFloat(), alto.toFloat())
        val debajo = base
        if (debajo != null) {
            canvas.drawBitmap(
                debajo.bmp, null,
                RectF(debajo.x0.toFloat(), debajo.y0.toFloat(), debajo.x1.toFloat(), debajo.y1.toFloat()),
                pincelDeLamina
            )
        }
        val hecha = lamina
        if (hecha != null) {
            canvas.drawBitmap(
                hecha.bmp, null,
                RectF(hecha.x0.toFloat(), hecha.y0.toFloat(), hecha.x1.toFloat(), hecha.y1.toFloat()),
                pincelDeLamina
            )
        } else if (debajo == null) {
            // Mientras llega la primera, el papel: mejor una hoja en blanco que un agujero.
            pincelDeLamina.color = android.graphics.Color.WHITE
            canvas.drawRect(0f, 0f, ancho.toFloat(), alto.toFloat(), pincelDeLamina)
        }
        canvas.restore()
        if (debajo == null) pedirLaBase(ambito)
        if (!vale(hecha, vista, zoom)) pedirLamina(ambito, vista, zoom)
    }

    /**
     * Si la lámina que hay sirve para lo que se está mirando.
     *
     * **Se pide la siguiente antes de llegar al borde**, no al salirse: cuando la vista entra
     * en la orla exterior de la lámina se manda pintar otra, y mientras se pinta la de ahora
     * sigue tapando la pantalla entera. Así, moviendo, no se llega a ver que algo se está
     * cargando — que es justo lo que se notaba.
     */
    private fun vale(hecha: Lamina?, vista: Bounds, zoom: Double): Boolean {
        if (hecha == null) return false
        val orlaX = (hecha.x1 - hecha.x0) * ORLA
        val orlaY = (hecha.y1 - hecha.y0) * ORLA
        if (vista.x1 < hecha.x0 + orlaX || vista.x2 > hecha.x1 - orlaX ||
            vista.y1 < hecha.y0 + orlaY || vista.y2 > hecha.y1 - orlaY
        ) return false
        // Y que no se haya estirado tanto que se vea la trama: al acercarse hay que rehacerla.
        val ratio = zoom / hecha.pxPorUnidad
        return ratio in 0.55..1.35
    }

    /**
     * Manda pintar la lámina **fuera del hilo de la pantalla**.
     *
     * Se pinta el trozo que se mira con un margen —así pasear un poco no obliga a rehacerla—
     * y a la resolución de la pantalla, con un tope de píxeles: si no cabe, se afloja la
     * resolución y no el trozo, que es la misma regla que sigue la lámina del PDF.
     */
    private fun pedirLamina(ambito: CoroutineScope, vista: Bounds, zoom: Double) {
        if (obrero?.isActive == true) return
        val an = vista.width * MARGEN
        val al = vista.height * MARGEN
        val x0 = vista.midX - an / 2
        val y0 = vista.midY - al / 2
        var px = zoom
        if (an * al * px * px > PIXELES_DE_LA_LAMINA) {
            px = sqrt(PIXELES_DE_LA_LAMINA / (an * al))
        }
        val anchoPx = (an * px).toInt().coerceIn(1, 4096)
        val altoPx = (al * px).toInt().coerceIn(1, 4096)
        obrero = ambito.launch(Dispatchers.Default) {
            val bmp = pedirMapa(anchoPx, altoPx) ?: return@launch
            val lienzo = Canvas(bmp)
            lienzo.scale((bmp.width / an).toFloat(), (bmp.height / al).toFloat())
            lienzo.translate(-x0.toFloat(), -y0.toFloat())
            dibujar(lienzo, Bounds(x0, y0, x0 + an, y0 + al), bmp.width / an)
            // **La vieja no se recicla aquí.** El hilo de la pantalla puede estar pintándola
            // en este mismo instante, y pintar un mapa de bits reciclado es un cierre de la
            // aplicación. Se suelta la referencia y que la recoja el recolector.
            lamina = Lamina(bmp, x0, y0, x0 + an, y0 + al, bmp.width / an)
            alLlegar?.invoke()
        }
    }

    /**
     * Reproduce lo grabado: el fondo, las tandas que tocan la pantalla al nivel de detalle
     * que pida el aumento, y los rótulos. Ver [Grabados].
     */
    private fun pintarGrabado(canvas: Canvas, vista: Bounds, zoom: Double, g: Grabados) {
        val lejos = nivelDeLejos((1.0 / max(zoom, 1e-6)).toFloat())
        canvas.save()
        canvas.clipRect(0f, 0f, ancho.toFloat(), alto.toFloat())
        canvas.drawRenderNode(g.fondo)
        val tandas = if (lejos) g.lejos else g.cerca
        val vx0 = vista.x1.toFloat(); val vy0 = vista.y1.toFloat()
        val vx1 = vista.x2.toFloat(); val vy1 = vista.y2.toFloat()
        for (t in tandas) {
            if (t.x1 < vx0 || t.x0 > vx1 || t.y1 < vy0 || t.y0 > vy1) continue
            canvas.drawRenderNode(t.nodo)
        }
        canvas.drawRenderNode(g.rotulos)
        canvas.restore()
    }

    /** Graba todo fuera del hilo de la pantalla; si algo falla, se queda con las láminas. */
    private fun pedirGrabado(ambito: CoroutineScope) {
        if (obreroDeGrabar != null) return
        obreroDeGrabar = ambito.launch(Dispatchers.Default) {
            val hecho = runCatching { grabarTodo() }.getOrNull()
            if (hecho == null) sinGrabar = true else grabados = hecho
            alLlegar?.invoke()
        }
    }

    private fun nodo(nombre: String, pinta: (Canvas) -> Unit): android.graphics.RenderNode {
        val n = android.graphics.RenderNode(nombre)
        n.setPosition(0, 0, ancho.toInt() + 1, alto.toInt() + 1)
        n.clipToBounds = false
        val lienzo = n.beginRecording()
        try { pinta(lienzo) } finally { n.endRecording() }
        return n
    }

    private fun grabarTodo(): Grabados {
        val papel = Bounds(0.0, 0.0, ancho, alto)
        val fondo = nodo("plano-fondo") { c -> dibujar(c, papel, 1.0, pelo = true, que = QUE_FONDO) }
        val cerca = ArrayList<Grabado>(tandas.size)
        val lejos = ArrayList<Grabado>(tandas.size)
        for ((i, t) in tandas.withIndex()) {
            cerca += Grabado(nodo("t$i") { c -> dibujarTanda(c, t, todas = true) }, t.x0, t.y0, t.x1, t.y1)
            if (t.gordas.isNotEmpty()) {
                lejos += Grabado(nodo("l$i") { c -> dibujarTanda(c, t, todas = false) }, t.x0, t.y0, t.x1, t.y1)
            }
        }
        val rotulos = nodo("plano-rotulos") { c -> dibujar(c, papel, 1.0, pelo = true, que = QUE_ROTULOS) }
        return Grabados(fondo, cerca, lejos, rotulos)
    }

    /** Una tanda, tal como se graba: a pelo (grosor cero) o con su grosor si lo tiene. */
    private fun dibujarTanda(canvas: Canvas, t: Tanda, todas: Boolean) {
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
            color = t.color
            alpha = t.alfa
            strokeWidth = if (t.grosor > 1.5f) t.grosor else 0f
        }
        val rayas = if (todas) t.puntos else t.gordas
        if (rayas.isNotEmpty()) canvas.drawLines(rayas, pincel)
    }

    /** El plano entero, una vez y para siempre. Ver [base]. */
    private fun pedirLaBase(ambito: CoroutineScope) {
        if (obreroDeLaBase != null) return
        obreroDeLaBase = ambito.launch(Dispatchers.Default) {
            val px = sqrt(PIXELES_DE_LA_BASE / (ancho * alto))
            val anchoPx = (ancho * px).toInt().coerceIn(1, 4096)
            val altoPx = (alto * px).toInt().coerceIn(1, 4096)
            val bmp = pedirMapa(anchoPx, altoPx) ?: return@launch
            val lienzo = Canvas(bmp)
            lienzo.scale(bmp.width / ancho.toFloat(), bmp.height / alto.toFloat())
            // **La base va siempre en detalle basto**, cueste lo que cueste su resolución: es
            // el respaldo de debajo, y pintarle las novecientas mil rayas retrasaría a la
            // lámina buena, que es la que se mira.
            dibujar(lienzo, Bounds(0.0, 0.0, ancho, alto), bmp.width / ancho, soloGordas = true)
            base = Lamina(bmp, 0.0, 0.0, ancho, alto, bmp.width / ancho)
            alLlegar?.invoke()
        }
    }

    /**
     * Un mapa de bits, y si no cabe, uno más pequeño.
     *
     * Un teléfono modesto puede no tener veinte megas seguidos que dar: quedarse sin lámina
     * sería quedarse sin plano, y media resolución sigue siendo un plano.
     */
    private fun pedirMapa(anchoPx: Int, altoPx: Int): Bitmap? {
        // RGB_565: un plano es tinta sobre papel blanco y no necesita transparencia, y así
        // la lámina ocupa la mitad.
        runCatching { return Bitmap.createBitmap(anchoPx, altoPx, Bitmap.Config.RGB_565) }
        return runCatching {
            Bitmap.createBitmap(
                (anchoPx / 2).coerceAtLeast(1), (altoPx / 2).coerceAtLeast(1), Bitmap.Config.RGB_565
            )
        }.getOrNull()
    }

    /**
     * El plano dibujado de verdad, raya a raya. Ver [pintar], que es quien decide cuándo.
     */
    private fun dibujar(
        canvas: Canvas,
        vista: Bounds,
        zoom: Double,
        soloGordas: Boolean = false,
        /**
         * Grabando para la tarjeta: las rayas finas a grosor cero —un píxel a cualquier
         * aumento— y los rótulos todos, porque no se sabe a qué aumento se verán.
         */
        pelo: Boolean = false,
        /** Qué parte: [QUE_TODO], [QUE_FONDO] (papel, fotos, manchas) o [QUE_ROTULOS]. */
        que: Int = QUE_TODO
    ) {
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }
        val pincelDeTexto = Paint(Paint.ANTI_ALIAS_FLAG)
        val pincelDeFoto = Paint(Paint.FILTER_BITMAP_FLAG)
        val z = zoom.toFloat()
        // Un píxel de pantalla, medido en unidades del dibujo: es el tamaño de lo que ya no
        // se distingue.
        val pixel = (1.0 / max(zoom, 1e-6)).toFloat()
        val lejos = soloGordas || nivelDeLejos(pixel)

        if (que != QUE_ROTULOS) {
        // El papel, blanco: un PDF no trae fondo, y la aplicación pinta el suyo antes de
        // dibujar encima. Ver [PdfDoc.render].
        pincel.style = Paint.Style.FILL
        pincel.color = android.graphics.Color.WHITE
        pincel.alpha = 255
        canvas.drawRect(0f, 0f, ancho.toFloat(), alto.toFloat(), pincel)

        for (f in fotos) {
            pincelDeFoto.alpha = f.alfa
            canvas.drawBitmap(f.bitmap, f.matriz, pincelDeFoto)
        }

        pincel.style = Paint.Style.FILL
        for (r in rellenos) {
            pincel.color = r.color
            pincel.alpha = r.alfa
            canvas.drawPath(r.camino, pincel)
        }

        } // que != QUE_ROTULOS
        pincel.style = Paint.Style.STROKE
        for (t in tandas) {
            if (que != QUE_TODO) break
            if (t.x1 < vista.x1 || t.x0 > vista.x2 || t.y1 < vista.y1 || t.y0 > vista.y2) continue
            pincel.color = t.color
            pincel.alpha = t.alfa
            // Ninguna raya baja de un píxel: por debajo de eso desaparecería al alejarse.
            // Grabando, «un píxel» es grosor cero: Skia lo pinta de un píxel a cualquier
            // aumento, que es justo lo que hace falta cuando el aumento no se sabe.
            pincel.strokeWidth = if (pelo) (if (t.grosor > 1.5f) t.grosor else 0f) else max(t.grosor, pixel)
            val rayas = if (lejos) t.gordas else t.puntos
            if (rayas.isNotEmpty()) canvas.drawLines(rayas, pincel)
        }

        // Los rótulos, con la letra del aparato estirada hasta ocupar lo que decía el PDF.
        for (r in textos) {
            if (que == QUE_FONDO) break
            if (!pelo && r.tam * z < 4f) continue
            pincelDeTexto.color = r.color
            pincelDeTexto.alpha = r.alfa
            pincelDeTexto.typeface = r.familia
            pincelDeTexto.textSize = 100f
            pincelDeTexto.textScaleX = 1f
            val medido = pincelDeTexto.measureText(r.texto)
            if (medido > 0) pincelDeTexto.textScaleX = r.ancho * 100f / medido
            canvas.save()
            canvas.concat(r.matriz)
            canvas.scale(0.01f, 0.01f)
            canvas.drawText(r.texto, 0f, 0f, pincelDeTexto)
            canvas.restore()
        }
    }

    fun soltar() {
        obreroDeGrabar?.cancel()
        obreroDeGrabar = null
        grabados?.let { g ->
            g.fondo.discardDisplayList(); g.rotulos.discardDisplayList()
            g.cerca.forEach { it.nodo.discardDisplayList() }
            g.lejos.forEach { it.nodo.discardDisplayList() }
        }
        grabados = null
        obrero?.cancel()
        obrero = null
        obreroDeLaBase?.cancel()
        obreroDeLaBase = null
        base?.bmp?.takeIf { !it.isRecycled }?.recycle()
        base = null
        lamina?.bmp?.takeIf { !it.isRecycled }?.recycle()
        lamina = null
        for (f in fotos) if (!f.bitmap.isRecycled) f.bitmap.recycle()
    }

    companion object {

        private const val QUE_TODO = 0
        private const val QUE_FONDO = 1
        private const val QUE_ROTULOS = 2

        /** Rayas más cortas que esto (en píxeles) no se pintan de lejos: no se pueden ver. */
        private const val LARGO_QUE_SE_VE = 1.0f

        /** A partir de aquí un plano es «enorme» y de lejos se recorta como antes. */
        private const val MUCHAS_RAYAS = 300_000

        /** Cuántas rayas van en una tanda: la caja de una tanda es lo que permite saltársela. */
        private const val POR_TANDA = 8192

        /**
         * **Cuánto más grande que la pantalla se pinta la lámina.**
         *
         * Lo pidió él después de probarlo: «que precargue una zona más grande». Al doble y
         * pico de la pantalla se puede pasear un buen rato sin que haya que rehacer nada, que
         * es cuando se nota que algo se está pintando.
         */
        private const val MARGEN = 2.2

        /**
         * La orla de la lámina: entrando en ella se pide ya la siguiente.
         *
         * Con [MARGEN] 2,2 la lámina saca media pantalla por cada lado; con una orla del
         * doce por ciento se empieza a pintar la siguiente cuando aún queda por delante casi
         * un tercio de pantalla de lámina buena.
         */
        private const val ORLA = 0.12

        /** Tope de la lámina: diez millones de píxeles son veinte megas en `RGB_565`. */
        private const val PIXELES_DE_LA_LAMINA = 10_000_000.0

        /** Y la del plano entero, que va debajo: dos millones, cuatro megas. */
        private const val PIXELES_DE_LA_BASE = 2_000_000.0

        /**
         * **El plano listo para pintar.**
         *
         * Quien lo pide lee antes el PDF con [PlanoDePdf] —una sola vez, que es lo caro— y
         * decide si vale la pena: hace falta que se entienda **entero** (ver
         * [PlanoDePdf.Plano.sinEntender]), porque un plano al que le falta algo es peor que
         * uno rasterizado, y para eso está el mosaico de siempre.
         */
        fun de(plano: PlanoDePdf.Plano, anchoEnUnidades: Double): PlanoEnPantalla {
            val escala = anchoEnUnidades / plano.ancho
            val paso = (escala / PlanoDePdf.FINEZA).toFloat()

            val tandas = ArrayList<Tanda>()
            val rellenos = ArrayList<Relleno>()
            for (b in plano.brochas) {
                if (b.relleno) rellenos += relleno(b, paso) else tandas += rayas(b, paso)
            }

            val textos = plano.textos.map { t ->
                val m = Matrix()
                m.setValues(
                    floatArrayOf(
                        (t.a * escala).toFloat(), (t.c * escala).toFloat(), (t.x * escala).toFloat(),
                        (t.b * escala).toFloat(), (t.d * escala).toFloat(), (t.y * escala).toFloat(),
                        0f, 0f, 1f
                    )
                )
                Rotulo(
                    texto = t.texto,
                    color = color(t.color),
                    alfa = (t.alfa * 255).toInt().coerceIn(0, 255),
                    matriz = m,
                    tam = hypot(t.c * escala, t.d * escala).toFloat(),
                    // En emes, que es como lo mide el PDF: ver [PlanoDePdf.Texto.ancho].
                    ancho = t.ancho.toFloat(),
                    familia = when {
                        t.familia == "monospace" -> android.graphics.Typeface.MONOSPACE
                        t.familia == "serif" -> android.graphics.Typeface.SERIF
                        else -> android.graphics.Typeface.SANS_SERIF
                    }.let {
                        val estilo = when {
                            t.negrita && t.cursiva -> android.graphics.Typeface.BOLD_ITALIC
                            t.negrita -> android.graphics.Typeface.BOLD
                            t.cursiva -> android.graphics.Typeface.ITALIC
                            else -> android.graphics.Typeface.NORMAL
                        }
                        android.graphics.Typeface.create(it, estilo)
                    }
                )
            }

            val fotos = ArrayList<Foto>()
            for (f in plano.fotos) {
                val bmp = runCatching {
                    conSuMascara(f)
                }.getOrNull() ?: continue
                val m = Matrix()
                // La matriz lleva el cuadrado de la imagen al papel; el bitmap se mide en sus
                // propios píxeles, así que primero se encoge a ese cuadrado.
                m.setValues(
                    floatArrayOf(
                        (f.a * escala).toFloat(), (f.c * escala).toFloat(), (f.x * escala).toFloat(),
                        (f.b * escala).toFloat(), (f.d * escala).toFloat(), (f.y * escala).toFloat(),
                        0f, 0f, 1f
                    )
                )
                m.preScale(1f / max(bmp.width, 1), 1f / max(bmp.height, 1))
                fotos += Foto(bmp, m, (f.alfa * 255).toInt().coerceIn(0, 255))
            }

            return PlanoEnPantalla(
                ancho = plano.ancho * escala,
                alto = plano.alto * escala,
                tandas = tandas,
                rellenos = rellenos,
                textos = textos,
                fotos = fotos,
                capas = plano.capas
            )
        }

        /**
         * Las rayas de una brocha, en tandas.
         *
         * Las curvas se parten en trocitos aquí y no al pintar: partirlas cuesta, y lo que se
         * pinta sesenta veces por segundo no puede pagar nada que se pueda pagar una vez. El
         * paso es fino de sobra —una milésima de la caja— para que al acercarse no se vean
         * esquinas.
         */
        /**
         * **La imagen del PDF con su transparencia ya puesta.**
         *
         * Un plano de Revit no rellena sus zonas de color: pone encima un JPEG de ese color y
         * lo recorta con una segunda imagen en gris —la máscara—, donde el negro es
         * transparente. Aquí se juntan las dos en un solo `Bitmap`: el gris de la máscara pasa
         * a ser el alfa del píxel. Ver [PlanoDePdf.Imagen.mascara].
         *
         * Se hace **una vez, al abrir el plano**, y no en cada fotograma: después es un
         * `drawBitmap` corriente y cuesta lo mismo que cualquier otro. Si la máscara no se
         * puede leer se devuelve la imagen tal cual, opaca, que es lo que se hacía antes de
         * entenderlas.
         */
        private fun conSuMascara(f: PlanoDePdf.Imagen): Bitmap? {
            val color = BitmapFactory.decodeByteArray(f.datos, 0, f.datos.size) ?: return null
            val bytes = f.mascara ?: return color
            val mascara = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return color
            if (mascara.width != color.width || mascara.height != color.height) {
                mascara.recycle(); return color
            }
            val ancho = color.width
            val alto = color.height
            val px = IntArray(ancho * alto)
            val mx = IntArray(ancho * alto)
            color.getPixels(px, 0, ancho, 0, 0, ancho, alto)
            mascara.getPixels(mx, 0, ancho, 0, 0, ancho, alto)
            mascara.recycle()
            // El gris de la máscara es su alfa. Se mira **el canal rojo** y no la luminancia:
            // una máscara es gris, con los tres canales iguales, y calcular la media sería
            // gastar tres multiplicaciones por píxel para llegar al mismo número. Un plano
            // trae millones de píxeles de máscara y esto se nota al abrirlo.
            for (i in px.indices) px[i] = (px[i] and 0x00FFFFFF) or ((mx[i] and 0xFF) shl 24)
            val junta = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
            junta.setPixels(px, 0, ancho, 0, 0, ancho, alto)
            color.recycle()
            return junta
        }

        private fun rayas(b: PlanoDePdf.Brocha, paso: Float): List<Tanda> {
            val salida = ArrayList<Float>(b.ops.size * 4)
            var i = 0
            var p = 0
            var x = 0f
            var y = 0f
            var ix = 0f
            var iy = 0f
            while (i < b.ops.size) {
                when (b.ops[i]) {
                    PlanoDePdf.MOVER -> {
                        x = b.xs[p] * paso; y = b.ys[p] * paso; ix = x; iy = y; p++
                    }
                    PlanoDePdf.LINEA -> {
                        val nx = b.xs[p] * paso
                        val ny = b.ys[p] * paso
                        salida.add(x); salida.add(y); salida.add(nx); salida.add(ny)
                        x = nx; y = ny; p++
                    }
                    PlanoDePdf.CURVA -> {
                        val x1 = b.xs[p] * paso; val y1 = b.ys[p] * paso
                        val x2 = b.xs[p + 1] * paso; val y2 = b.ys[p + 1] * paso
                        val x3 = b.xs[p + 2] * paso; val y3 = b.ys[p + 2] * paso
                        val largo = hypot(x1 - x, y1 - y) + hypot(x2 - x1, y2 - y1) + hypot(x3 - x2, y3 - y2)
                        val trozos = min(48, max(4, (largo / 0.5f).toInt()))
                        var ax = x
                        var ay = y
                        for (k in 1..trozos) {
                            val t = k.toFloat() / trozos
                            val u = 1 - t
                            val bx = u * u * u * x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
                            val by = u * u * u * y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
                            salida.add(ax); salida.add(ay); salida.add(bx); salida.add(by)
                            ax = bx; ay = by
                        }
                        x = x3; y = y3; p += 3
                    }
                    PlanoDePdf.CERRAR -> {
                        if (x != ix || y != iy) {
                            salida.add(x); salida.add(y); salida.add(ix); salida.add(iy)
                            x = ix; y = iy
                        }
                    }
                }
                i++
            }
            // **Se parte en tandas**: la caja de una brocha entera es medio plano y no sirve
            // para saltarse nada, y sin poder saltarse nada, acercarse a una esquina costaría
            // lo mismo que mirar el plano entero. En tandas de unas miles de rayas, la caja de
            // cada una sí dice si toca la pantalla o no.
            val color = color(b.color)
            val grosor = (b.grosor * paso * PlanoDePdf.FINEZA).toFloat()
            val alfa = (b.alfa * 255).toInt().coerceIn(0, 255)
            val salidaTandas = ArrayList<Tanda>(salida.size / (POR_TANDA * 4) + 1)
            var desde = 0
            while (desde < salida.size) {
                val hasta = min(salida.size, desde + POR_TANDA * 4)
                val puntos = FloatArray(hasta - desde) { salida[desde + it] }
                var x0 = Float.MAX_VALUE
                var y0 = Float.MAX_VALUE
                var x1 = -Float.MAX_VALUE
                var y1 = -Float.MAX_VALUE
                var k = 0
                while (k + 1 < puntos.size) {
                    val vx = puntos[k]
                    val vy = puntos[k + 1]
                    if (vx < x0) x0 = vx
                    if (vx > x1) x1 = vx
                    if (vy < y0) y0 = vy
                    if (vy > y1) y1 = vy
                    k += 2
                }
                // Las que se ven de lejos: las que miden más de un píxel a un aumento de uno.
                val gordas = ArrayList<Float>(puntos.size / 4)
                var j = 0
                while (j + 3 < puntos.size) {
                    if (abs(puntos[j + 2] - puntos[j]) + abs(puntos[j + 3] - puntos[j + 1]) > LARGO_QUE_SE_VE) {
                        gordas.add(puntos[j]); gordas.add(puntos[j + 1])
                        gordas.add(puntos[j + 2]); gordas.add(puntos[j + 3])
                    }
                    j += 4
                }
                salidaTandas += Tanda(
                    color = color,
                    grosor = grosor,
                    alfa = alfa,
                    puntos = puntos,
                    gordas = FloatArray(gordas.size) { gordas[it] },
                    x0 = if (x0 == Float.MAX_VALUE) 0f else x0,
                    y0 = if (y0 == Float.MAX_VALUE) 0f else y0,
                    x1 = if (x1 == -Float.MAX_VALUE) 0f else x1,
                    y1 = if (y1 == -Float.MAX_VALUE) 0f else y1
                )
                desde = hasta
            }
            return salidaTandas
        }

        private fun relleno(b: PlanoDePdf.Brocha, paso: Float): Relleno {
            val camino = Path()
            var i = 0
            var p = 0
            while (i < b.ops.size) {
                when (b.ops[i]) {
                    PlanoDePdf.MOVER -> { camino.moveTo(b.xs[p] * paso, b.ys[p] * paso); p++ }
                    PlanoDePdf.LINEA -> { camino.lineTo(b.xs[p] * paso, b.ys[p] * paso); p++ }
                    PlanoDePdf.CURVA -> {
                        camino.cubicTo(
                            b.xs[p] * paso, b.ys[p] * paso,
                            b.xs[p + 1] * paso, b.ys[p + 1] * paso,
                            b.xs[p + 2] * paso, b.ys[p + 2] * paso
                        )
                        p += 3
                    }
                    PlanoDePdf.CERRAR -> camino.close()
                }
                i++
            }
            return Relleno(color(b.color), (b.alfa * 255).toInt().coerceIn(0, 255), camino)
        }

        private fun color(rgb: Int): Int = android.graphics.Color.rgb(
            (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF
        )
    }
}
