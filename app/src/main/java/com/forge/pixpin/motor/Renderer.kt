package com.forge.pixpin.motor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/**
 * Pintado de elementos. Port de `renderElement.ts` y `staticScene.ts`.
 *
 * El orden que sigue cada forma es el del original y no es intercambiable:
 * **primero el relleno, después el trazo**. Al revés, el relleno rugoso taparía
 * el borde por dentro y el contorno quedaría más fino de lo pedido.
 */
class Renderer(
    /** Cómo conseguir el bitmap de una imagen a partir de su `fileId`. */
    private val imageProvider: (String) -> Bitmap? = { null },
    /**
     * De qué letra se pinta cada familia.
     *
     * Va como parámetro y no como constante porque cargar una fuente necesita
     * un `Context` y este archivo no tiene ninguno; quien construya el
     * renderizador le pasa [DrawFonts.provider]. Sin él, el texto sale con la
     * letra del sistema en vez de con la de Excalidraw.
     */
    private val typefaces: (Int?) -> android.graphics.Typeface? = { null },
    /**
     * Modo noche. **Es un filtro de pintado, no un cambio en el dibujo**: los
     * colores guardados siguen siendo los del modo día. El porqué, en
     * [DrawTheme].
     */
    private val dark: Boolean = false,
    /**
     * Lo que hay **debajo** del dibujo, si se está anotando sobre algo.
     *
     * Solo lo usa el mosaico, que no inventa píxeles: los coge de aquí y los
     * devuelve gordos. Su píxel (0, 0) es el punto (0, 0) de la escena, así que
     * en el pin y en la captura —donde la escena se mide en píxeles de la
     * imagen— la correspondencia es directa y no hay nada que convertir.
     *
     * A null (el lienzo infinito, que no tiene fondo) el mosaico se pinta como
     * una placa esmerilada: sigue tapando, que es para lo que está.
     */
    private val backdrop: Bitmap? = null,
    /**
     * Si esto es una exportación y no la pantalla.
     *
     * **Es la diferencia entre un adorno y el dibujo.** Unas cuantas cosas se
     * miden en píxeles de pantalla y se dividen por el aumento para verse
     * siempre igual de grandes: los puntos de una tabla, su número, la cruz del
     * centro de una guía. En pantalla es lo correcto —son señales para el ojo y
     * para el dedo—, pero al exportar el aumento es otro: encajar un plano en un
     * A4 puede dar un aumento de 0,2, y dividir por 0,2 **multiplica por cinco**.
     * De ahí que salieran enormes en el PDF.
     *
     * Exportando, esas medidas se toman en píxeles de escena tal cual, y las que
     * son puro andamio —la cruz de una guía, el eje— no se dibujan.
     */
    private val paraExportar: Boolean = false
) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Un pincel **solo para medir texto**, que no dibuja nunca.
     *
     * Medir con el mismo pincel con el que se pinta obliga a reconfigurarlo
     * entero después, y olvidarse no da un error: da un trazo con los valores de
     * fábrica. Fue justo lo que pasó con la cota — se medía el rótulo con el
     * pincel de la raya y la raya salía luego rellena, de grosor cero y negra,
     * o sea invisible, con solo los banderines de las puntas asomando como
     * pelos. Un pincel aparte hace que eso no pueda volver a pasar.
     */
    private val medidor = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Pasa un color por el filtro del modo noche. En modo día no toca nada. */
    private fun tema(argb: Int): Int = DrawTheme.filtrar(argb, dark)

    /**
     * Caché de geometría rugosa, por elemento.
     *
     * **Es lo que hace usable un lienzo con muchos elementos.** Generar el
     * trazo de rough.js no es barato —recorre el generador pseudoaleatorio y
     * monta Béziers punto a punto—, y sin caché se rehacía **en cada fotograma
     * para cada forma visible**. Con veinte no se nota; al panear con
     * doscientas, se caen fotogramas.
     *
     * Se guardan `Path` en coordenadas de escena, no bitmaps: el zoom lo aplica
     * la matriz del lienzo, así que el mismo camino vale a cualquier aumento y
     * ocupa una fracción de lo que ocuparía rasterizarlo. Excalidraw cachea un
     * canvas por elemento porque allí el zoom se aplica al rasterizar; aquí no
     * hace falta.
     *
     * `LinkedHashMap` en modo acceso + [MAX_CACHED_SHAPES] = LRU: una escena
     * enorme no crece sin freno, y lo que se ha salido de pantalla hace rato es
     * lo primero en caer.
     */
    private val shapeCache = object : LinkedHashMap<String, CachedShape>(
        64, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedShape>) =
            size > MAX_CACHED_SHAPES
    }

    /**
     * Geometría de [e], de la caché o recién generada.
     *
     * La entrada se invalida comparando **los campos que afectan al dibujo**, no
     * `version`: mientras se arrastra para crear una forma, `withPoint` y las
     * previsualizaciones cambian la caja **sin** tocar la versión, así que
     * fiarse de ella dejaría el trazo congelado en el primer fotograma.
     */
    private fun geometryOf(e: Element): CachedShape? {
        shapeCache[e.id]?.let { if (hasSameGeometry(it.source, e)) return it }
        val built = buildGeometry(e) ?: return null
        shapeCache[e.id] = built
        return built
    }

    /**
     * Pasa la geometría de [Shapes] —puntos y órdenes, sin Android— a los
     * `Path` que necesita el lienzo.
     *
     * La generación en sí vive en `buildShapeGeometry` para poder comprobarla
     * sin dispositivo; aquí solo queda la traducción.
     */
    private fun buildGeometry(e: Element): CachedShape? {
        // **El lápiz también se cachea, y es el que más lo necesita.** Su
        // contorno sale de `perfect-freehand`, que recorre los puntos dos veces
        // —alisado y proyección a los lados— y devuelve cientos de vértices.
        // Sin caché eso se rehacía **para cada trazo en cada fotograma**: con
        // treinta trazos en pantalla el dibujo se volvía inmanejable, y se
        // notaba mucho más en el pin que en el editor porque allí el lector de
        // toques entrega las muestras históricas del lápiz y provoca varios
        // repintados por fotograma.
        // El arco se dibuja como la línea que recorre: no guarda sus puntos,
        // los genera de la caja del óvalo y de cuánto barre.
        if (e.type == ElementType.ARC) {
            val puntos = puntosDelArco(e)
            if (puntos.size < 2) return null
            val comoLinea = e.copy(type = ElementType.LINE, points = puntos, roundness = null)
            val g = buildShapeGeometry(comoLinea) ?: return null
            return CachedShape(e, g.outline.toClosedPath(), g.stroke.toPath(), null)
        }

        // El relleno de un hueco **no tiene trazo**: el borde ya lo dibujan las
        // figuras que lo encierran, y repasarlo por dentro dejaría un doble
        // contorno donde el ojo espera una sola línea. Lo único que aporta es la
        // mancha, y va con sus agujeros por regla par/impar.
        if (e.type == ElementType.REGION) {
            val anillos = anillosDeRegion(e)
            if (anillos.isEmpty() || anillos.first().size < 3) return null
            val fill = if (needsRoughFill(e)) {
                Rough(roughOptionsFor(e)).fillPolygons(anillos).toPath()
            } else null
            return CachedShape(e, anillos.toEvenOddPath(), Path(), fill)
        }

        if (e.type == ElementType.FREEDRAW) {
            val pts = absolutePoints(e)
            if (pts.isEmpty()) return null
            val contorno = getStroke(pts, e.pressures, strokeOptionsFor(e))
            if (contorno.isEmpty()) return null

            // **Un lápiz que se cierra sobre sí mismo se puede rellenar**, igual
            // que en el original. Faltaba: el modelo lo admitía —`hasBackground`
            // dice que sí desde el principio y el panel ofrece fondo y rayado—
            // pero aquí no se pintaba, así que elegir un fondo para un garabato
            // cerrado no hacía absolutamente nada.
            //
            // La silueta que se rellena **no es la mancha del trazo** sino el
            // camino por el que fue el dedo, simplificado como hace Excalidraw
            // (`simplify(points, 0.75)`): rellenar la mancha metería el color
            // por debajo del propio trazo y lo engordaría por dentro.
            if (!rellenaSuLazo(e)) return CachedShape(e, Path(), contorno.toSmoothClosedPath(), null)

            val lazo = douglasPeucker(pts, FREEDRAW_FILL_TOLERANCE)
            val fill = if (needsRoughFill(e)) {
                Rough(roughOptionsFor(e)).fillPolygon(lazo).toPath()
            } else null
            return CachedShape(e, lazo.toSmoothClosedPath(), contorno.toSmoothClosedPath(), fill)
        }

        val g = buildShapeGeometry(e) ?: return null
        return CachedShape(
            source = e,
            outlinePath = g.outline.toClosedPath(),
            strokePath = g.stroke.toPath(),
            fillPath = g.fill?.toPath()
        )
    }

    /**
     * La escala con la que se rotulan las cotas de la escena que se está
     * pintando.
     *
     * Se toma en [renderScene] en vez de pasarla a cada elemento porque es una
     * propiedad del dibujo entero, no de la cota: todas miden con la misma vara
     * y recalibrar tiene que corregirlas todas a la vez. Ver [Escala].
     */
    private var escalaActual: Escala? = null

    /**
     * A qué aumento se está pintando.
     *
     * Lo necesitan las marcas que tienen que verse **del mismo tamaño mires al
     * zoom que mires**, como la cruz del centro de una guía: son señales para el
     * ojo y para el dedo, no parte del dibujo, así que se miden en píxeles de
     * pantalla y se dividen por el zoom al llevarlas a la escena.
     */
    private var zoomActual: Double = 1.0

    /**
     * Con qué aumento se miden los adornos.
     *
     * En pantalla, el de verdad: así se ven siempre del mismo tamaño mires al
     * zoom que mires. Exportando, uno fijo: lo que sale del archivo tiene que
     * medir lo que mide en la escena y no depender de en qué encaje.
     */
    private val zoomDeAdornos: Double get() = if (paraExportar) 1.0 else zoomActual

    /** Pinta la escena visible. */
    fun renderScene(
        canvas: Canvas, scene: Scene, screenWidth: Double, screenHeight: Double
    ) {
        escalaActual = scene.escala
        zoomActual = scene.viewport.zoom.coerceAtLeast(0.0001)
        canvas.save()
        // Un único cambio de matriz para todo: el resto del código dibuja
        // siempre en coordenadas de escena y se olvida del zoom.
        canvas.scale(scene.viewport.zoom.toFloat(), scene.viewport.zoom.toFloat())
        canvas.translate(scene.viewport.scrollX.toFloat(), scene.viewport.scrollY.toFloat())

        val visible = getVisibleElements(
            // Escondidas no se pintan; siguen ahí, guardadas, para volver.
            if (scene.referenciasVisibles) scene.elements
            else scene.elements.filter { !it.reference },
            scene.viewport, screenWidth, screenHeight
        )
        // El foco se pinta el último **de todos**, y no en su sitio del montón:
        // lo que hace es oscurecer el resto, así que si se pintara en orden lo
        // dibujado después se quedaría fuera de la sombra y el efecto se rompía.
        for ((i, element) in visible.withIndex()) {
            if (element.type == ElementType.SPOTLIGHT) continue
            // Lo que hay debajo de este elemento, por si es un mosaico y tiene
            // que sacar de ahí sus píxeles. Ver [fondoDelDibujo].
            capaDebajo = if (element.type == ElementType.MOSAIC) visible.subList(0, i) else null
            renderElement(canvas, element)
        }
        capaDebajo = null
        val focos = visible.filter { it.type == ElementType.SPOTLIGHT }
        if (focos.isNotEmpty()) {
            drawSpotlights(canvas, focos, scene.viewport, screenWidth, screenHeight)
        }

        // Los puntos tecleados van **encima de todo**: son la referencia contra
        // la que se dibuja, y tapados por lo que se acaba de trazar dejarían de
        // servir justo cuando más falta hacen.
        // El eje es la referencia contra la que se teclean coordenadas: se ve
        // mientras se trabaja y no sale en lo entregado.
        if (paraExportar) { canvas.restore(); return }
        scene.origenCoordenadas?.let { origen ->
            // El eje, **una sola vez**: es del dibujo, no de cada serie. Con una
            // cruz por tabla, tres series encima del mismo punto pintaban tres
            // cruces de tres colores y parecían tres ejes distintos.
            drawEjeDeCoordenadas(
                canvas, origen, scene.viewport, screenWidth, screenHeight
            )
            scene.tablas.filter { it.visible }.forEach {
                drawTabla(canvas, it, origen, scene.escala, scene.viewport.zoom)
            }
        }
        canvas.restore()
    }

    /**
     * Los puntos de una tabla de coordenadas, con su origen.
     *
     * Se pintan **con tamaño de pantalla**, dividiendo por el zoom: son
     * referencias, no dibujo, y tienen que verse igual de acertables muy
     * acercado y muy alejado. Lo mismo que hacen los tiradores de la selección.
     */
    /**
     * El eje: una cruz en el origen.
     *
     * Sin verlo no se sabe respecto a qué son los números que se teclean. Va en
     * gris y no del color de ninguna serie porque **es de todas**.
     */
    private fun drawEjeDeCoordenadas(
        canvas: Canvas, origen: Pt, viewport: Viewport,
        screenWidth: Double, screenHeight: Double
    ) {
        val zoom = viewport.zoom
        val ox = origen.x.toFloat()
        val oy = origen.y.toFloat()

        // **Las dos rectas, de lado a lado de lo que se ve.** Antes era una
        // crucecita de catorce píxeles, y ahora que el dedo se imanta al eje hay
        // que poder ver a qué se está pegando: una recta que se corta a dos
        // dedos del origen no se lee como un eje, se lee como una marca.
        val topLeft = viewport.toScene(0.0, 0.0)
        val bottomRight = viewport.toScene(screenWidth, screenHeight)

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (EJE_TRAZO / zoom).toFloat()
        paint.color = tema(EJE_COLOR)
        // A trazos: es una referencia, no algo dibujado. Sólida se confundiría
        // con una línea del propio dibujo.
        paint.pathEffect = DashPathEffect(
            floatArrayOf((8 / zoom).toFloat(), (8 / zoom).toFloat()), 0f
        )
        canvas.drawLine(topLeft.x.toFloat(), oy, bottomRight.x.toFloat(), oy, paint)
        canvas.drawLine(ox, topLeft.y.toFloat(), ox, bottomRight.y.toFloat(), paint)

        // Y el cero, sólido y más grueso: de las tres cosas a las que engancha
        // el eje, es la que más se busca.
        paint.pathEffect = null
        paint.strokeWidth = (TABLA_TRAZO / zoom).toFloat()
        val brazo = (TABLA_ORIGEN / zoom).toFloat()
        canvas.drawLine(ox - brazo, oy, ox + brazo, oy, paint)
        canvas.drawLine(ox, oy - brazo, ox, oy + brazo, paint)
    }

    /**
     * Los puntos tecleados en una tabla, **con su número**.
     *
     * ## Numerados por su posición, con apóstrofo
     *
     * `1'`, `2'`, `3'`… en el orden en que están escritos en la tabla. El
     * apóstrofo no es un adorno: es lo que los distingue de los puntos
     * etiquetados a mano, que van A, B, C. En un croquis donde conviven los
     * vértices de la figura y una serie de coordenadas tecleadas, poder decir
     * «de A a 3'» sin ambigüedad es la diferencia entre explicarse y señalar
     * con el dedo.
     *
     * Sale de la posición y no de un contador guardado porque la tabla se edita:
     * quitar la fila tercera tiene que renumerar de la cuarta en adelante, y un
     * número guardado se quedaría diciendo lo que decía.
     *
     * ## Y más gordos
     *
     * Eran de cuatro píxeles y se perdían: son el sitio al que hay que llevar el
     * dedo para engancharse, no una mota. Van en píxeles de pantalla y no de
     * escena, así que se ven igual de acertables muy acercado y muy alejado.
     */
    private fun drawTabla(
        canvas: Canvas, tabla: TablaDeCoordenadas, origen: Pt, escala: Escala?, zoom: Double
    ) {
        val color = tema(parseColor(tabla.color, 255))
        val radio = (TABLA_PUNTO / zoomDeAdornos).toFloat()
        val tam = (TABLA_LETRA / zoomDeAdornos).toFloat()

        for ((i, p) in puntosEnEscena(tabla, origen, escala).withIndex()) {
            // Relleno con aro blanco alrededor: sobre una foto oscura, un punto
            // de color sin más se pierde.
            fillPaint.reset()
            fillPaint.isAntiAlias = true
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.WHITE
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radio * 1.55f, fillPaint)
            fillPaint.color = color
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radio, fillPaint)

            val etiqueta = "${i + 1}'"
            paint.reset()
            paint.isAntiAlias = true
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = tam
            paint.typeface = typefaces(null)
            paint.isFakeBoldText = true
            // Arriba a la derecha del punto: es donde menos tapa cuando los
            // puntos van seguidos formando una poligonal, que es como llegan de
            // una libreta de campo.
            val x = (p.x + radio * 1.8f).toFloat()
            val y = (p.y - radio * 1.4f).toFloat()

            // Con halo, como todo lo que tiene que leerse sobre lo que sea.
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = tam * PUNTO_HALO.toFloat()
            paint.color = contrastingTextColor(color, 255)
            canvas.drawText(etiqueta, x, y, paint)
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawText(etiqueta, x, y, paint)
            paint.isFakeBoldText = false
        }
    }

    /**
     * Oscurece todo menos las cajas marcadas.
     *
     * Con varios focos se hace **una sola sombra con todos los huecos**: una
     * sombra por foco se sumaría en las zonas que solapan y quedaría más oscuro
     * justo donde el usuario quiere mirar.
     */
    private fun drawSpotlights(
        canvas: Canvas, focos: List<Element>, viewport: Viewport,
        screenWidth: Double, screenHeight: Double
    ) {
        val topLeft = viewport.toScene(0.0, 0.0)
        val bottomRight = viewport.toScene(screenWidth, screenHeight)

        val sombra = Path()
        sombra.addRect(
            topLeft.x.toFloat(), topLeft.y.toFloat(),
            bottomRight.x.toFloat(), bottomRight.y.toFloat(),
            Path.Direction.CW
        )
        for (f in focos) {
            val c = getElementAbsoluteCoords(f)
            val hueco = Path()
            // **El hueco es un óvalo, no un rectángulo.**
            //
            // Con el hueco cuadrado, el foco era un telón negro con una ventana:
            // servía para «mira solo esto» y para nada más, y a nadie le hace
            // falta tapar la pantalla entera para señalar un botón. Redondeado
            // se lee como lo que es —una linterna sobre el sitio— y por eso
            // ahora la sombra es mucho más suave (ver [SPOTLIGHT_DIM]): resalta
            // sin esconder el contexto, que es justo lo que se necesita
            // señalando algo dentro de una captura.
            val redondeo = minOf(c.x2 - c.x1, c.y2 - c.y1).toFloat() * SPOTLIGHT_REDONDEO
            hueco.addRoundRect(
                c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat(),
                redondeo, redondeo,
                Path.Direction.CCW
            )
            // Con la rotación aplicada al hueco: el foco también se puede girar.
            if (f.angle != 0.0) {
                val m = android.graphics.Matrix()
                m.setRotate(Math.toDegrees(f.angle).toFloat(), c.cx.toFloat(), c.cy.toFloat())
                hueco.transform(m)
            }
            sombra.op(hueco, Path.Op.DIFFERENCE)
        }

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        // La opacidad del elemento gradúa cuánto se oscurece el resto.
        val opacidad = (focos.maxOf { it.opacity } * 255 / 100).coerceIn(0, 255)
        fillPaint.color = Color.argb(opacidad * SPOTLIGHT_DIM / 255, 0, 0, 0)
        canvas.drawPath(sombra, fillPaint)
    }

    /** Pinta un elemento suelto, en coordenadas de escena. */
    fun renderElement(canvas: Canvas, element: Element) {
        if (element.isDeleted) return

        val c = getElementAbsoluteCoords(element)
        canvas.save()
        if (element.angle != 0.0) {
            canvas.rotate(
                Math.toDegrees(element.angle).toFloat(), c.cx.toFloat(), c.cy.toFloat()
            )
        }

        // **La referencia se pinta translúcida.** No es decoración: es lo que
        // dice de un vistazo qué es guía y qué es dibujo, y lo que permite
        // trazar encima sin perder de vista lo que se está trazando.
        val opacidad = if (element.reference) {
            element.opacity * REFERENCIA_OPACIDAD / 100
        } else element.opacity
        val alpha = (opacidad * 255 / 100).coerceIn(0, 255)
        renderElementCuerpo(canvas, element, alpha)
        // La cruz del centro de una guía redonda, encima de su trazo.
        // La cruz del centro es una señal para el dedo, no parte del dibujo: al
        // exportar sobra, y encima salía gigante por medirse en pantalla.
        if (!paraExportar && element.reference && esRedonda(element)) marcarCentro(canvas, element)
        canvas.restore()
    }

    /**
     * Marca el centro de una circunferencia guía con una crucecita.
     *
     * El centro de un círculo **no está dibujado en ninguna parte**: es el único
     * punto notable de la figura que no se ve. Se puede enganchar a él desde
     * siempre, pero había que apuntar a ciegas y confiar en que el imán tirase,
     * y trazar un radio o un diámetro a ojo desde un centro invisible es
     * exactamente lo que uno hace mal.
     *
     * Solo en las guías: en el dibujo de verdad sería una marca que nadie ha
     * pedido y que además acabaría en la imagen exportada.
     */
    private fun marcarCentro(canvas: Canvas, e: Element) {
        val c = getElementAbsoluteCoords(e)
        val brazo = (CENTRO_BRAZO / zoomDeAdornos).toFloat()
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (CENTRO_TRAZO / zoomDeAdornos).toFloat()
        paint.color = tema(parseColor(e.strokeColor, 255))
        val cx = c.cx.toFloat()
        val cy = c.cy.toFloat()
        canvas.drawLine(cx - brazo, cy, cx + brazo, cy, paint)
        canvas.drawLine(cx, cy - brazo, cx, cy + brazo, paint)
    }

    /** Las que tienen centro que enseñar: el óvalo y el arco, que sale de uno. */
    private fun esRedonda(e: Element): Boolean =
        e.type == ElementType.ELLIPSE || e.type == ElementType.ARC

    /** Lo de siempre, para no repetir el `when` entero. */
    private fun renderElementCuerpo(canvas: Canvas, element: Element, alpha: Int) {
        when (element.type) {
            ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE,
            ElementType.REGION -> drawCachedShape(canvas, element, alpha)
            ElementType.LINE, ElementType.ARROW -> drawLinear(canvas, element, alpha)
            ElementType.FREEDRAW -> drawFreeDraw(canvas, element, alpha)
            ElementType.IMAGE -> drawImage(canvas, element, alpha)
            ElementType.TEXT -> drawText(canvas, element, alpha)
            ElementType.MOSAIC -> drawMosaic(canvas, element, alpha)
            ElementType.SERIAL -> drawSerial(canvas, element, alpha)
            ElementType.PUNTO -> drawPunto(canvas, element, alpha)
            ElementType.MEASURE -> drawMeasure(canvas, element, alpha)
            // El arco pasa por el mismo generador rugoso que una línea, así
            // que sale con el pulso del resto. Ver `buildGeometry`.
            ElementType.ARC -> drawCachedShape(canvas, element, alpha)
            ElementType.FRAME -> drawFrame(canvas, element)
            ElementType.ESCALA_GRAFICA -> drawEscalaGrafica(canvas, element, alpha)
            // El foco no se pinta aquí: va el último de todos, en renderScene.
            ElementType.SPOTLIGHT -> Unit
        }
    }

    // ---------------------------------------------------------------------
    // Las tres herramientas propias de PixPin
    // ---------------------------------------------------------------------

    /**
     * Tapa un trozo de lo que hay debajo.
     *
     * **Coge los píxeles del fondo, no los inventa**: recorta la zona, la
     * encoge a una miniatura y la vuelve a estirar sin filtrar. Ese ida y vuelta
     * es el pixelado; con [Element.mosaicBlur] la miniatura se estira **con**
     * filtro bilineal y sale el desenfoque. Dos efectos con el mismo camino y
     * sin tocar `RenderScript`, que está retirado desde Android 12.
     */
    private fun drawMosaic(canvas: Canvas, e: Element, alpha: Int) {
        val c = getElementAbsoluteCoords(e)
        val destino = RectF(c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat())
        val fuente = backdrop

        fillPaint.reset()
        fillPaint.alpha = alpha

        if (destino.width() < 1f || destino.height() < 1f) return

        // **Sin foto debajo, se pixela el propio dibujo.**
        //
        // Antes se pintaba una placa esmerilada y ya: sobre una captura tapaba
        // de verdad, pero en un dibujo del lienzo —donde no hay foto— la
        // herramienta no pixelaba nada, ponía un cuadro blanquecino encima. Y un
        // cuadro blanco no es un mosaico: es un tachón.
        //
        // Lo que hay debajo se puede pintar, así que se pinta: se dibuja la
        // escena de debajo **a la resolución del grano** y se estira sin
        // filtrar. Reducir promedia y estirar sin filtro da el canto duro; es el
        // mismo camino que con la foto, cambiando de dónde salen los píxeles.
        if (fuente == null) {
            val mini = fondoDelDibujo(e, c) ?: run {
                fillPaint.isAntiAlias = true
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = Color.argb(alpha * 220 / 255, 224, 224, 228)
                canvas.drawRect(destino, fillPaint)
                return
            }
            fillPaint.isFilterBitmap = e.mosaicBlur
            fillPaint.isAntiAlias = false
            canvas.drawBitmap(mini, null, destino, fillPaint)
            return
        }

        // El recorte se acota al fondo: una caja que se sale devolvería null.
        val recorte = Rect(
            c.x1.toInt().coerceIn(0, fuente.width - 1),
            c.y1.toInt().coerceIn(0, fuente.height - 1),
            c.x2.toInt().coerceIn(1, fuente.width),
            c.y2.toInt().coerceIn(1, fuente.height)
        )
        if (recorte.width() < 1 || recorte.height() < 1) return

        val mini = miniaturaDe(e, fuente, recorte) ?: return

        // Al pixelar, **sin filtrar**: el bloque tiene que salir con el canto
        // duro. Al desenfocar sí, que es lo que convierte los mismos bloques en
        // una mancha suave.
        fillPaint.isFilterBitmap = e.mosaicBlur
        fillPaint.isAntiAlias = false
        canvas.drawBitmap(mini, null, destino, fillPaint)
    }

    /**
     * La miniatura de la que sale el mosaico, **cacheada**.
     *
     * Aquí estaba el gasto que se notaba: cada fotograma recortaba del fondo un
     * bitmap del tamaño de la caja —sobre una captura grande, megas— y lo
     * volvía a escalar. Con el mosaico quieto eso es exactamente el mismo
     * resultado una y otra vez, así que se guarda y solo se rehace cuando
     * cambia algo que lo afecte: dónde está, cuánto mide, el grano o el modo.
     *
     * La miniatura ocupa nada —unas decenas de píxeles de lado— así que caben
     * muchas sin preocuparse; lo caro era hacerla.
     */
    private fun miniaturaDe(e: Element, fuente: Bitmap, recorte: Rect): Bitmap? {
        // El grano es **fijo**, no una fracción del recuadro: agrandar el
        // recuadro tapa más, no tapa distinto. Ver [mosaicoGrano].
        val lado = mosaicoGrano(e.strokeWidth).coerceAtLeast(1.0)
        val miniW = Math.ceil(recorte.width() / lado).toInt().coerceIn(1, MAX_LADO_MINI)
        val miniH = Math.ceil(recorte.height() / lado).toInt().coerceIn(1, MAX_LADO_MINI)

        val clave = "${recorte.left},${recorte.top},${recorte.right},${recorte.bottom}," +
            "$miniW,$miniH,${e.mosaicBlur}"
        mosaicCache[e.id]?.let { if (it.clave == clave) return it.mini }

        val mini = runCatching {
            val trozo = Bitmap.createBitmap(
                fuente, recorte.left, recorte.top, recorte.width(), recorte.height()
            )
            // `filter = true` al **reducir** siempre, también al pixelar: cada
            // bloque sale entonces del promedio de lo que tapa, en vez de del
            // píxel que caiga justo en la rejilla. Sin promediar, mover el
            // mosaico un píxel podía cambiar el bloque entero, y sobre texto
            // pequeño llegaban a leerse letras dentro de un bloque.
            val reducida = Bitmap.createScaledBitmap(trozo, miniW, miniH, true)
            if (trozo !== reducida && !trozo.isRecycled) trozo.recycle()
            reducida
        }.getOrNull() ?: return null

        mosaicCache.put(e.id, CachedMosaic(clave, mini))?.let {
            if (it.mini !== mini && !it.mini.isRecycled) it.mini.recycle()
        }
        return mini
    }

    /**
     * Lo que hay debajo del mosaico, dibujado **a la resolución del grano**.
     *
     * Pintar la escena directamente en un mapa de bits diminuto es lo mismo que
     * pintarla grande y reducirla —cada bloque sale del promedio de lo que
     * tapa— pero cuesta una fracción, que importa porque esto se rehace cada vez
     * que cambia algo de debajo.
     *
     * Se saltan los mosaicos y los focos de debajo: un mosaico que se pixela a
     * sí mismo se degradaría en cada pasada, y la sombra del foco taparía el
     * grano con una mancha uniforme.
     */
    private fun fondoDelDibujo(e: Element, c: AbsoluteCoords): Bitmap? {
        val debajo = capaDebajo?.filter {
            it.type != ElementType.MOSAIC && it.type != ElementType.SPOTLIGHT
        }.orEmpty()
        if (debajo.isEmpty()) return null

        val ancho = c.x2 - c.x1
        val alto = c.y2 - c.y1
        val lado = mosaicoGrano(e.strokeWidth).coerceAtLeast(1.0)
        val miniW = Math.ceil(ancho / lado).toInt().coerceIn(1, MAX_LADO_MINI)
        val miniH = Math.ceil(alto / lado).toInt().coerceIn(1, MAX_LADO_MINI)

        val clave = "dibujo:${c.x1},${c.y1},${c.x2},${c.y2},$miniW,$miniH,${e.mosaicBlur}," +
            debajo.joinToString(",") { "${it.id}:${it.version}" }.hashCode()
        mosaicCache[e.id]?.let { if (it.clave == clave) return it.mini }

        val mini = runCatching {
            val bmp = Bitmap.createBitmap(miniW, miniH, Bitmap.Config.ARGB_8888)
            val lienzo = Canvas(bmp)
            // El papel primero: la escena no pinta su fondo, así que sin esto lo
            // de debajo saldría flotando sobre transparente y el mosaico dejaría
            // ver a través de él justo donde no hay nada dibujado.
            lienzo.drawColor(parseColor(DrawTheme.fondoDe(dark)))
            lienzo.scale((miniW / ancho).toFloat(), (miniH / alto).toFloat())
            lienzo.translate(-c.x1.toFloat(), -c.y1.toFloat())
            val guardado = capaDebajo
            capaDebajo = null
            for (el in debajo) renderElement(lienzo, el)
            capaDebajo = guardado
            bmp
        }.getOrNull() ?: return null

        mosaicCache.put(e.id, CachedMosaic(clave, mini))?.let {
            if (it.mini !== mini && !it.mini.isRecycled) it.mini.recycle()
        }
        return mini
    }

    /**
     * La miniatura de un mosaico, para quien tenga que **guardarla** en vez de
     * pintarla.
     *
     * La necesita el SVG. Un mosaico no se puede escribir como trazos: lo que
     * hace es coger los píxeles de debajo y devolverlos gordos, así que lo que
     * hay que guardar son esos píxeles. Y resulta que lo que ya se calcula aquí
     * para pintarlo —una miniatura de unas decenas de píxeles de lado— es
     * **justo** lo que conviene incrustar: se estira en el visor igual que se
     * estira en la pantalla, y el archivo pesa lo que pesa un icono en vez de lo
     * que pesaría el recorte a tamaño real.
     *
     * [debajo] son los elementos que van por debajo de [e] en la escena, por si
     * no hay foto y el mosaico tiene que pixelar el propio dibujo.
     */
    internal fun miniaturaDelMosaico(e: Element, debajo: List<Element>): Bitmap? {
        val c = getElementAbsoluteCoords(e)
        val fuente = backdrop ?: run {
            val guardado = capaDebajo
            capaDebajo = debajo
            val mini = fondoDelDibujo(e, c)
            capaDebajo = guardado
            return mini
        }
        val recorte = Rect(
            c.x1.toInt().coerceIn(0, fuente.width - 1),
            c.y1.toInt().coerceIn(0, fuente.height - 1),
            c.x2.toInt().coerceIn(1, fuente.width),
            c.y2.toInt().coerceIn(1, fuente.height)
        )
        if (recorte.width() < 1 || recorte.height() < 1) return null
        return miniaturaDe(e, fuente, recorte)
    }

    /** Lo que hay debajo del elemento que se está pintando, si hace falta. */
    private var capaDebajo: List<Element>? = null

    /** Un mosaico ya reducido, con la huella de lo que lo hizo así. */
    private class CachedMosaic(val clave: String, val mini: Bitmap)

    /**
     * Caché de mosaicos, LRU y pequeña.
     *
     * Al salir una entrada se recicla su bitmap: son pocos y diminutos, pero
     * dejarlos al recolector con el dedo dibujando llena la memoria de basura
     * justo cuando más falta hace no pararse a limpiarla.
     */
    private val mosaicCache = object : LinkedHashMap<String, CachedMosaic>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMosaic>):
            Boolean {
            val fuera = size > MAX_CACHED_MOSAICS
            if (fuera && !eldest.value.mini.isRecycled) eldest.value.mini.recycle()
            return fuera
        }
    }

    /**
     * La escala gráfica: la reglita a cuadros blancos y negros de los planos.
     *
     * Se pinta **lisa y exacta**, sin pasar por rough.js, por lo mismo que la
     * cota: una escala temblorosa no se puede medir con una regla encima, y
     * medirla es justo para lo que está. El reparto en cuadros redondos lo
     * decide [barraDeEscala], que es geometría pura y se comprueba sin pintar.
     *
     * Sale en la exportación como cualquier otro elemento, que es toda la
     * gracia: la imagen se va por ahí, alguien la recorta o la reescala, y la
     * barra encoge con ella — así que sigue diciendo la verdad cuando el «1:50»
     * escrito a mano ya sería mentira.
     */
    private fun drawEscalaGrafica(canvas: Canvas, e: Element, alpha: Int) {
        val c = getElementAbsoluteCoords(e)
        val ancho = c.x2 - c.x1
        val altoCaja = c.y2 - c.y1
        if (ancho <= 1.0 || altoCaja <= 1.0) return

        val barra = barraDeEscala(ancho, escalaActual) ?: return
        val alto = (altoCaja * ALTO_DE_LA_BARRA).coerceAtLeast(1.0)
        val tinta = tema(parseColor(e.strokeColor, alpha))

        // Los cuadros: uno sí y uno no, empezando por el lleno. El hueco va del
        // color del papel y no transparente, o sobre una foto oscura la mitad de
        // la regla desaparecería.
        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        for (i in 0 until barra.tramos) {
            val x1 = c.x1 + i * barra.anchoDeTramo
            fillPaint.color = if (i % 2 == 0) tinta else contrastingTextColor(tinta, alpha)
            canvas.drawRect(
                x1.toFloat(), c.y1.toFloat(),
                (x1 + barra.anchoDeTramo).toFloat(), (c.y1 + alto).toFloat(),
                fillPaint
            )
        }

        // El marco de la regla, que es lo que la separa del fondo.
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = e.strokeWidth.toFloat().coerceAtLeast(1f)
        paint.color = tinta
        canvas.drawRect(
            c.x1.toFloat(), c.y1.toFloat(),
            (c.x1 + barra.ancho).toFloat(), (c.y1 + alto).toFloat(),
            paint
        )

        // Y las cifras, una por marca, con la unidad al final. La primera se
        // alinea a la izquierda y la última a la derecha: centradas, se salen de
        // la barra por los dos extremos.
        val tam = (e.fontSize ?: (altoCaja * (1 - ALTO_DE_LA_BARRA) * 0.8))
            .coerceAtLeast(1.0)
        paint.style = Paint.Style.FILL
        paint.textSize = tam.toFloat()
        paint.typeface = typefaces(e.fontFamily)
        val base = (c.y1 + alto + tam * 1.05).toFloat()
        for (i in 0..barra.tramos) {
            val x = c.x1 + i * barra.anchoDeTramo
            paint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                barra.tramos -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val texto = if (i == barra.tramos) "${barra.etiqueta(i)} ${barra.unidad}"
            else barra.etiqueta(i)
            canvas.drawText(texto, x.toFloat(), base, paint)
        }
    }

    /**
     * El marco: **la hoja**.
     *
     * Se dibuja como un contorno fino y liso, sin rugosidad: no es parte del
     * dibujo, es la referencia de hasta dónde llega. Por eso tampoco sale en el
     * pin ni en la exportación — allí lo único que hace es decidir el encuadre.
     */
    private fun drawFrame(canvas: Canvas, e: Element) {
        val c = getElementAbsoluteCoords(e)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = FRAME_STROKE
        paint.color = tema(FRAME_COLOR)
        canvas.drawRoundRect(
            c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat(),
            FRAME_RADIUS, FRAME_RADIUS, paint
        )

        // El nombre va FUERA, encima de la esquina: dentro se comería el sitio
        // útil de la hoja, que es justo lo que el marco existe para dar.
        val nombre = e.name ?: return
        paint.style = Paint.Style.FILL
        paint.textSize = FRAME_LABEL_SIZE
        paint.typeface = typefaces(null)
        canvas.drawText(nombre, c.x1.toFloat(), (c.y1 - FRAME_LABEL_GAP).toFloat(), paint)
    }

    /**
     * Un círculo con un número dentro: 1, 2, 3…
     *
     * Sirve para enumerar pasos sobre una captura, y por eso el número va en
     * blanco o negro según el color del círculo y no en un color fijo: sobre un
     * círculo amarillo, un número blanco no se lee.
     */
    private fun drawSerial(canvas: Canvas, e: Element, alpha: Int) {
        val c = getElementAbsoluteCoords(e)
        val radio = minOf(c.x2 - c.x1, c.y2 - c.y1) / 2
        if (radio <= 0) return

        val fondo = tema(parseColor(e.strokeColor, alpha))
        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = fondo
        canvas.drawCircle(c.cx.toFloat(), c.cy.toFloat(), radio.toFloat(), fillPaint)

        val texto = e.text ?: return
        paint.reset()
        paint.isAntiAlias = true
        paint.color = contrastingTextColor(fondo, alpha)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (radio * SERIAL_TEXT_RATIO).toFloat()
        paint.isFakeBoldText = true
        // Centrado óptico: `drawText` alinea por la línea base, no por el centro
        // del glifo, así que sin corregir el número queda alto dentro del círculo.
        val fm = paint.fontMetrics
        val baseline = c.cy - (fm.ascent + fm.descent) / 2
        canvas.drawText(texto, c.cx.toFloat(), baseline.toFloat(), paint)
    }

    /**
     * Un punto con su letra: **A, B, C sobre el dibujo**.
     *
     * ## Negro y diminuto
     *
     * El redondel va negro y del tamaño de la cabeza de un alfiler. Empezó
     * gordo y con aro blanco, pensando en que se viera de lejos, y gordo
     * **tapaba justo lo que estaba señalando**: en un vértice se comía el
     * vértice, y ya no se veía dónde se cruzan las dos rectas. Un punto de
     * geometría marca un sitio; si esconde el sitio, no sirve.
     *
     * Lo que sigue siendo grande es la zona por la que se coge, que no se ve:
     * ver [RADIO_DE_AGARRE].
     *
     * ## La letra, con halo
     *
     * La letra sí va del color del trazo, y con halo del color contrario por lo
     * mismo que el rótulo de una cota: sobre una zona de su mismo color
     * desaparecería. Ver [dibujarRotulo].
     */
    private fun drawPunto(canvas: Canvas, e: Element, alpha: Int) {
        val cx = e.x.toFloat()
        val cy = e.y.toFloat()

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tema(Color.argb(alpha, 0, 0, 0))
        canvas.drawCircle(cx, cy, RADIO_DEL_PUNTO.toFloat(), fillPaint)

        val texto = e.text ?: return
        if (texto.isEmpty()) return
        val donde = sitioDeLaEtiqueta(e)
        val tam = (e.fontSize ?: PUNTO_LETRA).coerceAtLeast(1.0)
        val tinta = tema(parseColor(e.strokeColor, alpha))

        paint.reset()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = tam.toFloat()
        paint.typeface = typefaces(e.fontFamily)
        // Centrado óptico: la línea base no es el centro del glifo, y sin
        // corregirlo la letra queda alta respecto del punto al que se refiere.
        val fm = paint.fontMetrics
        val base = (donde.y - (fm.ascent + fm.descent) / 2).toFloat()

        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = (tam * PUNTO_HALO).toFloat()
        paint.color = contrastingTextColor(tinta, alpha)
        canvas.drawText(texto, donde.x.toFloat(), base, paint)

        paint.style = Paint.Style.FILL
        paint.color = tinta
        canvas.drawText(texto, donde.x.toFloat(), base, paint)
    }

    /**
     * La cota: una raya que dice cuánto mide lo que cruza.
     *
     * **Se pinta con el mismo pulso que el resto del dibujo.** Estuvo lisa y
     * exacta un tiempo, con el argumento de que una medida no debe temblar; y
     * es verdad que no debe, pero el resultado no parecía dibujado sino
     * insertado: una raya vectorial muerta en medio de un dibujo hecho a mano,
     * y además más fina que sus vecinas, porque el trazo rugoso pasa dos veces
     * y este pasaba una. Lo que no puede temblar es **el número**, y el número
     * no tiembla.
     *
     * El número **no se guarda con el elemento**: se calcula aquí, del largo y
     * de la escala. Así una cota no puede mentir: al mover un extremo o al
     * recalibrar el dibujo, el rótulo cambia solo.
     */
    private fun drawMeasure(canvas: Canvas, e: Element, alpha: Int) {
        val pts = absolutePoints(e)
        if (pts.size < 2) return
        val a = pts.first()
        val b = pts.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val largo = kotlin.math.hypot(dx, dy)
        if (largo < MIN_MEASURE_LENGTH) return

        val color = tema(parseColor(e.strokeColor, alpha))
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = e.strokeWidth.toFloat()
        paint.color = color

        // **La raya se abre en el medio para dejar sitio al número.** El
        // rótulo iba encima, y encima de la raya es donde está lo que se está
        // midiendo: tapaba justo eso. Partiéndola, el número queda *dentro* de
        // la cota —que es como se acota en un plano— y no tapa nada.
        val hueco = huecoDelRotulo(e, largo)
        // **Con el pulso del resto del dibujo.** Los dos trozos pasan por el
        // mismo generador que una línea a mano, así que la cota se ve dibujada
        // y no insertada — y de paso vuelve a tener el grosor de sus vecinas,
        // porque el trazo rugoso pasa dos veces.
        fun trazo(desde: Pt, hasta: Pt) {
            val rough = Rough(roughOptionsFor(e).copy(preserveVertices = true))
            canvas.drawPath(rough.doubleLine(desde.x, desde.y, hasta.x, hasta.y).toPath(), paint)
        }
        if (hueco <= 0.0) {
            trazo(a, b)
        } else {
            val ux = dx / largo
            val uy = dy / largo
            val corte = (largo - hueco) / 2
            trazo(a, Pt(a.x + ux * corte, a.y + uy * corte))
            trazo(Pt(b.x - ux * corte, b.y - uy * corte), b)
        }

        // Los banderines de los extremos: la perpendicular que marca dónde
        // empieza y dónde acaba la medida. Sin ellos, una cota corta no se
        // distingue de una raya cualquiera.
        val nx = -dy / largo
        val ny = dx / largo
        val ala = (e.strokeWidth * MEASURE_TICK).coerceAtLeast(MEASURE_TICK_MIN)
        for (extremo in listOf(a, b)) {
            canvas.drawLine(
                (extremo.x - nx * ala).toFloat(), (extremo.y - ny * ala).toFloat(),
                (extremo.x + nx * ala).toFloat(), (extremo.y + ny * ala).toFloat(),
                paint
            )
        }
        puntaDeCota(canvas, a, b, e.strokeWidth)
        puntaDeCota(canvas, b, a, e.strokeWidth)

        dibujarRotulo(canvas, e, a, b, dx, dy, nx, ny, color, alpha)
    }

    /**
     * Cuánto hay que abrir la raya para que quepa su número.
     *
     * Cero si no cabe: en una cota corta, partirla dejaría dos muñones y el
     * número saliéndose por los dos lados. Ahí es mejor la raya entera con el
     * número encima, que se lee peor pero no engaña sobre dónde empieza y acaba
     * la medida.
     */
    private fun huecoDelRotulo(e: Element, largo: Double): Double {
        val tam = e.fontSize ?: MEASURE_TEXT_SIZE
        if (tam <= 0.0) return 0.0
        medidor.reset()
        medidor.textSize = tam.toFloat()
        medidor.typeface = typefaces(e.fontFamily)
        val ancho = medidor.measureText(textoDeCota(e, escalaActual)).toDouble()
        val hueco = ancho + tam * MEASURE_LABEL_GAP * 2
        return if (hueco > largo * MAXIMO_HUECO) 0.0 else hueco
    }

    /** Media punta de flecha, apuntando de [en] hacia afuera de [hacia]. */
    private fun puntaDeCota(canvas: Canvas, en: Pt, hacia: Pt, grosor: Double) {
        val ang = kotlin.math.atan2(hacia.y - en.y, hacia.x - en.x)
        val largo = (grosor * MEASURE_HEAD).coerceAtLeast(MEASURE_HEAD_MIN)
        for (s in listOf(-1, 1)) {
            val giro = ang + s * MEASURE_HEAD_ANGLE
            canvas.drawLine(
                en.x.toFloat(), en.y.toFloat(),
                (en.x + largo * kotlin.math.cos(giro)).toFloat(),
                (en.y + largo * kotlin.math.sin(giro)).toFloat(),
                paint
            )
        }
    }

    /**
     * El número, encima de la línea y **nunca del revés**.
     *
     * Va con halo del color contrario y no con recuadro: sobre una captura, un
     * recuadro tapa justo lo que se está midiendo, y sin nada detrás el número
     * desaparece en cuanto cae sobre una zona de su mismo color.
     */
    private fun dibujarRotulo(
        canvas: Canvas, e: Element, a: Pt, b: Pt,
        dx: Double, dy: Double, nx: Double, ny: Double, color: Int, alpha: Int
    ) {
        val texto = textoDeCota(e, escalaActual)
        val tam = (e.fontSize ?: MEASURE_TEXT_SIZE)
        if (tam <= 0.0) return

        canvas.save()
        canvas.translate(((a.x + b.x) / 2).toFloat(), ((a.y + b.y) / 2).toFloat())
        val grados = Math.toDegrees(kotlin.math.atan2(dy, dx))
        canvas.rotate((if (rotuloDelReves(grados)) grados + 180.0 else grados).toFloat())

        paint.reset()
        paint.isAntiAlias = true
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = tam.toFloat()
        paint.typeface = typefaces(e.fontFamily)

        // **Centrado en la raya, no encima.** La raya se ha abierto para
        // dejarle sitio (ver `drawMeasure`), así que el número va justo en el
        // hueco: donde lo pone un plano. El desplazamiento es el que centra el
        // glifo sobre la línea base, que `drawText` no hace por su cuenta.
        val fm = paint.fontMetrics
        val separacion = -(fm.ascent + fm.descent) / 2
        // El halo primero y el número encima: al revés se comería los perfiles.
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = (tam * MEASURE_HALO).toFloat()
        paint.color = contrastingTextColor(color, alpha)
        canvas.drawText(texto, 0f, separacion.toFloat(), paint)

        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawText(texto, 0f, separacion.toFloat(), paint)
        canvas.restore()
    }

    // ---------------------------------------------------------------------
    // Formas
    // ---------------------------------------------------------------------

    /**
     * Pinta una forma rugosa a partir de su geometría cacheada.
     *
     * El relleno va antes que el trazo, como en el original: al revés, el
     * relleno taparía el borde por dentro y el contorno se vería más fino.
     */
    private fun drawCachedShape(canvas: Canvas, e: Element, alpha: Int) {
        val shape = geometryOf(e) ?: return
        drawFill(canvas, e, shape, alpha)
        canvas.drawPath(shape.strokePath, strokePaint(e, alpha))
    }

    /** El fondo de una forma: liso o rayado, siempre recortado a su silueta. */
    private fun drawFill(canvas: Canvas, e: Element, shape: CachedShape, alpha: Int) {
        if (!e.hasBackground || isTransparent(e.backgroundColor)) return

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.color = tema(parseColor(e.backgroundColor, alpha))

        if (shape.fillPath == null) {
            // El sólido se pinta liso a propósito: con ruido dejaría huecos
            // blancos justo por dentro del borde.
            fillPaint.style = Paint.Style.FILL
            canvas.drawPath(shape.outlinePath, fillPaint)
        } else {
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = (e.strokeWidth / 2).toFloat()
            fillPaint.strokeCap = Paint.Cap.ROUND
            canvas.save()
            // El rayado se recorta a la silueta: el barrido puede sobresalir
            // un píxel en las esquinas y se vería fuera de la forma.
            canvas.clipPath(shape.outlinePath)
            canvas.drawPath(shape.fillPath, fillPaint)
            canvas.restore()
        }
    }

    private fun drawLinear(canvas: Canvas, e: Element, alpha: Int) {
        if (absolutePoints(e).size < 2) return
        drawCachedShape(canvas, e, alpha)

        if (e.type == ElementType.ARROW) {
            e.startArrowhead?.let { drawArrowhead(canvas, e, ArrowEnd.START, it, alpha) }
            e.endArrowhead?.let { drawArrowhead(canvas, e, ArrowEnd.END, it, alpha) }
        }
    }

    /**
     * El lápiz.
     *
     * No pasa por rough.js: un trazo a mano **ya es** irregular, y sacudirlo
     * otra vez lo convierte en un borrón. Lo que se pinta es el **contorno
     * relleno** de la mancha que calcula [getStroke], no una línea recorrida con
     * un pincel: es lo que da los extremos afilados y el ancho que fluye con la
     * velocidad. El porqué, en [Freehand].
     */
    private fun drawFreeDraw(canvas: Canvas, e: Element, alpha: Int) {
        // El camino ya viene hecho de la caché: el trazo que se está dibujando
        // ahora mismo sí se recalcula —sus puntos cambian en cada fotograma— y
        // todos los anteriores no, que es donde estaba el coste.
        val shape = geometryOf(e) ?: return

        // El fondo del lazo va **debajo del trazo**, como en cualquier otra
        // forma: encima le comería el borde por dentro.
        if (rellenaSuLazo(e)) drawFill(canvas, e, shape, alpha)

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = tema(parseColor(e.strokeColor, alpha))
        canvas.drawPath(shape.strokePath, fillPaint)
    }

    private fun drawImage(canvas: Canvas, e: Element, alpha: Int) {
        val fileId = e.fileId ?: return
        val bitmap = imageProvider(fileId) ?: return
        val c = getElementAbsoluteCoords(e)

        canvas.save()
        // El volteo va en el signo de `scale`; se aplica reflejando el lienzo
        // alrededor del centro de la imagen.
        if (e.scale.getOrElse(0) { 1.0 } < 0 || e.scale.getOrElse(1) { 1.0 } < 0) {
            canvas.scale(
                if (e.scale[0] < 0) -1f else 1f,
                if (e.scale[1] < 0) -1f else 1f,
                c.cx.toFloat(), c.cy.toFloat()
            )
        }

        val src = e.crop?.let {
            Rect(
                it.x.toInt(), it.y.toInt(),
                (it.x + it.width).toInt(), (it.y + it.height).toInt()
            )
        } ?: Rect(0, 0, bitmap.width, bitmap.height)

        val dst = RectF(c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat())
        canvas.drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            this.alpha = alpha
        })
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, e: Element, alpha: Int) {
        val content = e.text ?: return
        val size = e.fontSize ?: 20.0
        val c = getElementAbsoluteCoords(e)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = tema(parseColor(e.strokeColor, alpha))
        paint.textSize = size.toFloat()
        paint.typeface = typefaces(e.fontFamily)
        paint.style = Paint.Style.FILL
        paint.textAlign = when (e.textAlign) {
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }

        val originX = when (e.textAlign) {
            TextAlign.CENTER -> c.cx
            TextAlign.RIGHT -> c.x2
            else -> c.x1
        }
        // El interlineado de Excalidraw es 1.25 del tamaño de fuente.
        val lineHeight = size * 1.25
        // **La primera línea se apoya en su ascendente, no en su tamaño.**
        //
        // Estaba en `y1 + size`, y el cuadro de escribir coloca la suya en
        // `y1 + ascendente` —que es como coloca el texto cualquier caja de
        // texto—: la diferencia son unos pocos píxeles hacia abajo, justo los
        // que se veía saltar el texto al darle a intro. Ahora se pinta donde se
        // estaba escribiendo.
        val fm = paint.fontMetrics
        var y = c.y1 - fm.ascent
        for (line in content.split('\n')) {
            canvas.drawText(line, originX.toFloat(), y.toFloat(), paint)
            y += lineHeight
        }
    }

    // ---------------------------------------------------------------------
    // Pinceles
    // ---------------------------------------------------------------------

    private fun strokePaint(e: Element, alpha: Int): Paint {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = tema(parseColor(e.strokeColor, alpha))
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        // Con trazo no continuo el original engorda medio punto, porque al
        // desactivar la doble pasada la línea se ve más fina que una sólida.
        paint.strokeWidth = when (e.strokeStyle) {
            StrokeStyle.SOLID -> e.strokeWidth
            else -> e.strokeWidth + 0.5
        }.toFloat()
        paint.pathEffect = when (e.strokeStyle) {
            StrokeStyle.SOLID -> null
            StrokeStyle.DASHED -> DashPathEffect(
                floatArrayOf(8f, (8 + e.strokeWidth).toFloat()), 0f
            )
            StrokeStyle.DOTTED -> DashPathEffect(
                floatArrayOf(1.5f, (6 + e.strokeWidth).toFloat()), 0f
            )
        }
        return paint
    }

    private fun drawArrowhead(
        canvas: Canvas, e: Element, end: ArrowEnd, head: Arrowhead, alpha: Int
    ) {
        val shape = getArrowheadPoints(e, end, head) ?: return
        val p = strokePaint(e, alpha).apply { pathEffect = null }

        when (head) {
            Arrowhead.CIRCLE, Arrowhead.CIRCLE_OUTLINE -> {
                p.style = if (head == Arrowhead.CIRCLE) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawCircle(
                    shape.tip.x.toFloat(), shape.tip.y.toFloat(),
                    (shape.diameter / 2).toFloat(), p
                )
            }
            Arrowhead.TRIANGLE, Arrowhead.TRIANGLE_OUTLINE -> {
                val path = Path().apply {
                    moveTo(shape.tip.x.toFloat(), shape.tip.y.toFloat())
                    lineTo(shape.wings.first.x.toFloat(), shape.wings.first.y.toFloat())
                    lineTo(shape.wings.second.x.toFloat(), shape.wings.second.y.toFloat())
                    close()
                }
                p.style = if (head == Arrowhead.TRIANGLE) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawPath(path, p)
            }
            Arrowhead.DIAMOND, Arrowhead.DIAMOND_OUTLINE -> {
                val opp = shape.opposite ?: return
                val path = Path().apply {
                    moveTo(shape.tip.x.toFloat(), shape.tip.y.toFloat())
                    lineTo(shape.wings.first.x.toFloat(), shape.wings.first.y.toFloat())
                    lineTo(opp.x.toFloat(), opp.y.toFloat())
                    lineTo(shape.wings.second.x.toFloat(), shape.wings.second.y.toFloat())
                    close()
                }
                p.style = if (head == Arrowhead.DIAMOND) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawPath(path, p)
            }
            // La flecha y la barra son dos rayas sueltas, sin cerrar.
            Arrowhead.ARROW, Arrowhead.BAR -> {
                p.style = Paint.Style.STROKE
                canvas.drawLine(
                    shape.tip.x.toFloat(), shape.tip.y.toFloat(),
                    shape.wings.first.x.toFloat(), shape.wings.first.y.toFloat(), p
                )
                canvas.drawLine(
                    shape.tip.x.toFloat(), shape.tip.y.toFloat(),
                    shape.wings.second.x.toFloat(), shape.wings.second.y.toFloat(), p
                )
            }
        }
    }

    private companion object {
        /**
         * Cuánto llega a oscurecer el foco, a opacidad plena. 0-255.
         *
         * Bajado de 150 a 96: con el valor de antes, lo de alrededor
         * desaparecía y el foco solo servía para presentaciones. Señalando algo
         * dentro de una captura hace falta **seguir viendo el contexto** —de
         * qué pantalla es, dónde está ese botón—, y para eso basta con
         * apagarlo, no con borrarlo. Quien quiera el telón sube la opacidad del
         * elemento, que es su mando.
         */
        const val SPOTLIGHT_DIM = 96

        /** Cuánto se redondea el hueco, en proporción de su lado corto. */
        const val SPOTLIGHT_REDONDEO = 0.35f

        // ---- Las tablas de coordenadas ----

        /**
         * Radio del punto tecleado, en píxeles de pantalla.
         *
         * Subido de 4 a 6,5: con cuatro se perdían. Son el sitio al que hay que
         * llevar el dedo para engancharse, no una mota.
         */
        const val TABLA_PUNTO = 6.5

        /**
         * Y el tamaño de su número, también en píxeles de pantalla.
         *
         * Subido dos veces: de 16 a 22 y de 22 a 30, porque seguía sin leerse.
         * Un número que hay que acercarse a distinguir no sirve para lo que
         * está — decir de qué punto de la tabla se está hablando. Y va en
         * negrita por lo mismo: a este tamaño, sobre un plano lleno de rayas
         * finas, el grosor se lee antes que el tamaño.
         */
        const val TABLA_LETRA = 30.0

        /** Medio brazo de la cruz del origen y grosor de su trazo. */
        const val TABLA_ORIGEN = 14.0
        const val TABLA_TRAZO = 1.5

        /** Cuánto de su opacidad conserva una referencia, en tanto por ciento. */
        const val REFERENCIA_OPACIDAD = 35

        /**
         * Medio brazo de la cruz que marca el centro de una guía redonda, y su
         * grosor, **en píxeles de pantalla**: se ve igual a cualquier aumento,
         * como los tiradores de la selección.
         */
        const val CENTRO_BRAZO = 7.0
        const val CENTRO_TRAZO = 1.4

        /** El eje es de todas las series, así que no lleva el color de ninguna. */
        val EJE_COLOR = Color.rgb(90, 90, 96)

        /** Las rectas van más finas que la cruz del cero: son el fondo. */
        const val EJE_TRAZO = 1.0

        /** Cuántos mosaicos reducidos se guardan antes de tirar el más viejo. */
        const val MAX_CACHED_MOSAICS = 24

        /**
         * Tope de bloques por lado.
         *
         * Con el grano fijo, un mosaico del tamaño de una captura entera pediría
         * miles de bloques; el tope los acota y, si se alcanza, el grano sale un
         * poco más gordo de lo pedido — que es infinitamente mejor que quedarse
         * sin memoria por tapar un número de teléfono.
         */
        const val MAX_LADO_MINI = 400

        /** Tamaño del número respecto al radio de su círculo. */
        const val SERIAL_TEXT_RATIO = 1.25

        /** Tamaño de la letra de un punto cuando no trae uno propio. */
        const val PUNTO_LETRA = 22.0

        /** Grosor del halo de esa letra, en múltiplos de su tamaño. */
        const val PUNTO_HALO = 0.22

        // ---- La cota ----

        /** Por debajo de esto no hay dirección fiable que rotular. */
        const val MIN_MEASURE_LENGTH = 0.5

        /** Medio banderín de extremo, en múltiplos del grosor de trazo. */
        const val MEASURE_TICK = 3.0
        const val MEASURE_TICK_MIN = 6.0

        /** La punta de flecha, igual pero en su dirección. */
        const val MEASURE_HEAD = 5.0
        const val MEASURE_HEAD_MIN = 9.0
        val MEASURE_HEAD_ANGLE = Math.toRadians(20.0)

        /** Tamaño del rótulo cuando el elemento no trae uno propio. */
        const val MEASURE_TEXT_SIZE = 20.0

        /** Aire a los lados del número dentro del hueco, en múltiplos de su tamaño. */
        const val MEASURE_LABEL_GAP = 0.45

        /**
         * Qué parte de la cota puede llegar a ser hueco.
         *
         * Pasado eso ya no es una cota partida: son dos muñones con un número
         * en medio, y no se ve dónde empieza ni dónde acaba lo que se mide.
         */
        const val MAXIMO_HUECO = 0.72

        /** Grosor del halo del número, en múltiplos de su tamaño. */
        const val MEASURE_HALO = 0.22

        /** El marco: contorno fino y gris, como en el original. */
        const val FRAME_STROKE = 1.5f
        const val FRAME_RADIUS = 8f
        const val FRAME_LABEL_SIZE = 13f
        const val FRAME_LABEL_GAP = 6f
        val FRAME_COLOR = Color.rgb(0xBB, 0xBB, 0xC4)

        /**
         * Cuántas formas se recuerdan.
         *
         * Un `Path` pesa poco, pero la escena es infinita y sin tope la caché
         * crecería con cada elemento que se llegue a ver. Con este número entra
         * de sobra todo lo visible en pantalla más un buen margen alrededor.
         */
        const val MAX_CACHED_SHAPES = 512
    }
}

/** Geometría rugosa ya generada, con el elemento del que salió. */
private class CachedShape(
    /** El elemento tal como estaba al generar: contra este se compara. */
    val source: Element,
    /** Silueta lisa, para el relleno sólido y para recortar el rayado. */
    val outlinePath: Path,
    val strokePath: Path,
    /** Trazos del rayado, o null si el relleno es liso o no hay. */
    val fillPath: Path?
)

/**
 * ¿Los dos elementos producen exactamente el mismo dibujo?
 *
 * Es el predicado que decide si la caché sirve, y **tiene que ser exacto en un
 * sentido**: dar un falso positivo dejaría en pantalla la forma vieja.
 *
 * No entran `angle` —la rotación la aplica la matriz del lienzo, no la
 * geometría—, ni el color del trazo ni la opacidad, que solo tocan el pincel.
 * Sí entra [Element.backgroundColor], aunque parezca solo color: de si es
 * transparente depende que se pida o no el relleno, y pedirlo consume números
 * del generador y cambia el garabato del contorno.
 */
internal fun hasSameGeometry(a: Element, b: Element): Boolean =
    a.type == b.type &&
        a.seed == b.seed &&
        a.x == b.x && a.y == b.y &&
        a.width == b.width && a.height == b.height &&
        a.strokeWidth == b.strokeWidth &&
        a.strokeStyle == b.strokeStyle &&
        a.roughness == b.roughness &&
        a.fillStyle == b.fillStyle &&
        a.roundness == b.roundness &&
        isTransparent(a.backgroundColor) == isTransparent(b.backgroundColor) &&
        a.points == b.points &&
        a.huecos == b.huecos &&
        // **El arco no guarda sus puntos: guarda cuánto barre.** Al faltar estos
        // dos, un arco que se está trazando tenía la misma huella en cada
        // fotograma —su caja es la del óvalo guía y no se mueve— así que la
        // caché devolvía el primer trozo generado y no lo rehacía nunca: se
        // dibujaba un pellizco de arco y ahí se quedaba, pasearas el dedo por
        // donde lo pasearas. Era el «solo se dibuja un punto» del transportador.
        a.arcStart == b.arcStart &&
        a.arcSweep == b.arcSweep

// -------------------------------------------------------------------------
// Utilidades de dibujo
// -------------------------------------------------------------------------

/** Convierte las órdenes de rough.js en un `Path` de Android. */
fun List<Op>.toPath(): Path {
    val path = Path()
    for (op in this) when (op) {
        is Op.Move -> path.moveTo(op.x.toFloat(), op.y.toFloat())
        is Op.LineTo -> path.lineTo(op.x.toFloat(), op.y.toFloat())
        is Op.CurveTo -> path.cubicTo(
            op.x1.toFloat(), op.y1.toFloat(),
            op.x2.toFloat(), op.y2.toFloat(),
            op.x.toFloat(), op.y.toFloat()
        )
        Op.Cerrar -> path.close()
    }
    return path
}

/**
 * Contorno cerrado y **suavizado**, como `getSvgPathFromStroke` del original.
 *
 * Los puntos que devuelve [getStroke] son muchos y muy juntos; unirlos con
 * rectas deja el borde de la mancha dentado. El original los cose con
 * cuadráticas que pasan por los **puntos medios** de cada par: cada punto hace
 * de tirador y el camino queda continuo en tangente sin necesidad de calcular
 * nada más.
 */
private fun List<Pt>.toSmoothClosedPath(): Path {
    val path = Path()
    if (size < 4) {
        // Demasiado corto para suavizar: el original devuelve camino vacío, pero
        // aquí eso haría desaparecer un toque. Se cierra a rectas.
        if (isEmpty()) return path
        path.moveTo(this[0].x.toFloat(), this[0].y.toFloat())
        for (i in 1 until size) path.lineTo(this[i].x.toFloat(), this[i].y.toFloat())
        path.close()
        return path
    }

    path.moveTo(this[0].x.toFloat(), this[0].y.toFloat())
    path.quadTo(
        this[1].x.toFloat(), this[1].y.toFloat(),
        ((this[1].x + this[2].x) / 2).toFloat(), ((this[1].y + this[2].y) / 2).toFloat()
    )
    for (i in 2 until size - 1) {
        val a = this[i]
        val b = this[i + 1]
        path.quadTo(
            a.x.toFloat(), a.y.toFloat(),
            ((a.x + b.x) / 2).toFloat(), ((a.y + b.y) / 2).toFloat()
        )
    }
    path.close()
    return path
}

/**
 * Varios anillos en un solo camino, con la regla **par/impar**.
 *
 * Es lo que hace que el agujero de un relleno sea un agujero de verdad: con la
 * regla por defecto (`WINDING`) un anillo dentro de otro se rellena igual que el
 * de fuera si los dos van en el mismo sentido, y el sentido aquí sale de cómo se
 * recorrió el contorno, que no es algo en lo que convenga confiar.
 */
private fun List<List<Pt>>.toEvenOddPath(): Path {
    val path = Path()
    path.fillType = Path.FillType.EVEN_ODD
    for (anillo in this) {
        if (anillo.size < 3) continue
        path.moveTo(anillo[0].x.toFloat(), anillo[0].y.toFloat())
        for (i in 1 until anillo.size) path.lineTo(anillo[i].x.toFloat(), anillo[i].y.toFloat())
        path.close()
    }
    return path
}

private fun List<Pt>.toClosedPath(): Path {
    val path = Path()
    if (isEmpty()) return path
    path.moveTo(this[0].x.toFloat(), this[0].y.toFloat())
    for (i in 1 until size) path.lineTo(this[i].x.toFloat(), this[i].y.toFloat())
    path.close()
    return path
}

/**
 * Blanco o negro, el que se lea encima de [fondo].
 *
 * Lo usa el número de serie, que va dentro de un círculo del color elegido: un
 * número blanco sobre amarillo no se lee, y uno negro sobre azul marino tampoco.
 * Los coeficientes son los de la luminancia percibida (Rec. 601).
 */
fun contrastingTextColor(fondo: Int, alpha: Int = 255): Int {
    val luminancia = 0.299f * Color.red(fondo) +
        0.587f * Color.green(fondo) +
        0.114f * Color.blue(fondo)
    return if (luminancia > 150f) Color.argb(alpha, 0, 0, 0) else Color.argb(alpha, 255, 255, 255)
}

/**
 * Color de Excalidraw a ARGB de Android.
 *
 * Los colores llegan como `#rrggbb` desde el JSON; `transparent` es un valor
 * propio de Excalidraw que `Color.parseColor` no entiende.
 */
fun parseColor(color: String, alpha: Int = 255): Int {
    if (isTransparent(color)) return Color.TRANSPARENT
    val base = runCatching { Color.parseColor(color) }.getOrDefault(Color.BLACK)
    return Color.argb(
        alpha * Color.alpha(base) / 255,
        Color.red(base), Color.green(base), Color.blue(base)
    )
}
