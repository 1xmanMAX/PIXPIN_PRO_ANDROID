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
     * Si [backdrop] hay que **pintarlo**, y no solo mirarlo.
     *
     * El telón nació para el mosaico, que le saca los píxeles y los devuelve
     * gordos; quien lo pasaba —el pin, la captura— ya pintaba su foto por su
     * cuenta por debajo, así que aquí no había que dibujar nada.
     *
     * Con una página de un PDF no hay nadie debajo pintando: el editor abría la
     * hoja y se veía **un lienzo en blanco**. Se dibujaba a ciegas y lo trazado
     * caía donde cayera respecto de una página que no se veía — de ahí que la
     * anotación apareciera en un rincón del papel y no donde se había puesto.
     *
     * Va como interruptor y no siempre encendido porque encenderlo en el pin
     * pintaría la foto dos veces, una encima de otra.
     */
    private val papelALaVista: Boolean = false,
    /**
     * El fondo pautado, si lo hay.
     *
     * Va aquí y no en la escena porque es **andamio del editor**: se ve mientras
     * se dibuja y no viaja en el archivo ni sale al exportar, igual que los
     * tiradores de la selección. Quien exporta construye su renderizador sin
     * esto y no tiene que acordarse de apagar nada. Ver [Cuadricula].
     */
    private val cuadricula: Cuadricula = Cuadricula.NINGUNA,
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
        val clave = claveDeCache(e)
        shapeCache[clave]?.let { if (hasSameGeometry(it.source, e)) return it }
        val built = buildGeometry(e) ?: return null
        shapeCache[clave] = built
        return built
    }

    /**
     * Con qué se guarda una forma en la caché. El id, **y para el sólido también
     * la vista**.
     *
     * Aquí hay una trampa que ya mordió una vez y por eso lleva su propio
     * comentario. La caché se invalida con [hasSameGeometry], que es una **lista
     * blanca de campos del elemento**: si ninguno cambió, el `Path` guardado
     * sirve. Y la [Vista] **no está en el elemento** —vive en la escena, ver
     * [Scene.vista]—, así que al girar la cámara ningún campo del sólido cambia:
     * la caché devolvería tan campante la proyección de la vista anterior, y no
     * la reharía nunca. El volumen se quedaría mirando al otro lado para siempre.
     *
     * Es exactamente el fallo del arco, que está documentado en
     * [hasSameGeometry]: la geometría dependía de algo que el predicado no
     * miraba, y lo que se veía era un pellizco de arco que no se movía por más
     * que pasearas el dedo.
     *
     * Metiéndola en la clave, cada vista tiene su propia entrada. Además de
     * arreglarlo, sale gratis lo otro: **girar y volver no recalcula nada**, que
     * es lo que se hace todo el tiempo mirando un volumen desde los cuatro lados.
     * Las entradas viejas caen solas por el LRU.
     */
    private fun claveDeCache(e: Element): String =
        if (e.isSolido) "${e.id}#${vistaActual.cuartos}" else e.id

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

        // **La caja en volumen: una geometría por cara.**
        //
        // No cabe en el `outline`/`stroke`/`fill` de siempre porque cada cara se
        // pinta de **su propio color** —el base movido por su claridad, ver
        // [aclarar]— y eso es justo lo que da el volumen. Con un solo camino
        // habría que repasar la caja tres veces cambiando el pincel entre medias,
        // y el garabato de rough.js saldría distinto en cada pasada.
        //
        // Las caras vienen ya ordenadas de atrás hacia delante, así que pintarlas
        // en este orden es el algoritmo del pintor y no hay más que hacer.
        if (e.isSolido) {
            val caras = carasDeElemento(e, vistaActual)
            if (caras.isEmpty()) return null
            // **Un solo generador para las tres**, y en orden: rough.js sortea su
            // ruido de una secuencia con la semilla del elemento, así que
            // recorrerla siempre igual es lo que hace que la caja no tiemble al
            // repintarse. Uno por cara les daría a las tres el mismo temblor y se
            // notaría: las aristas que se juntan en un vértice se separarían.
            val rough = Rough(roughOptionsFor(e))
            val rugoso = needsRoughFill(e)
            val pintadas = caras.map { cara ->
                CaraPintada(
                    silueta = cara.poligono.toClosedPath(),
                    rayado = if (rugoso) rough.fillPolygon(cara.poligono).toPath() else null,
                    trazo = rough.polygon(cara.poligono).toPath(),
                    claridad = cara.claridad
                )
            }
            return CachedShape(e, Path(), Path(), null, pintadas)
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
     * Desde dónde se está mirando lo que está en volumen.
     *
     * Se toma en [renderScene] igual que la escala, y por el mismo motivo: es una
     * propiedad **del dibujo entero** y no de cada caja, así que pasarla elemento
     * a elemento sería repetir el mismo dato en cada llamada y dejar la puerta
     * abierta a que dos sólidos de la misma escena se pintaran desde vistas
     * distintas. Ver [Scene.vista].
     */
    private var vistaActual: Vista = Vista.CERO

    /**
     * Con qué aumento se miden los adornos.
     *
     * En pantalla, el de verdad: así se ven siempre del mismo tamaño mires al
     * zoom que mires. Exportando, uno fijo: lo que sale del archivo tiene que
     * medir lo que mide en la escena y no depender de en qué encaje.
     */
    private val zoomDeAdornos: Double get() = if (paraExportar) 1.0 else zoomActual

    /**
     * Y cuánto encogen esos adornos al exportar.
     *
     * En pantalla se miden en píxeles de pantalla para verse siempre igual, y al
     * exportar en píxeles de escena para no depender del encaje. Pero los dos
     * números no tienen por qué ser el mismo: quien dibuja suele trabajar
     * **acercado**, así que un punto de seis píxeles de pantalla al 200 % son
     * tres de escena — y exportarlo a seis lo dejaba del doble de gordo que
     * como se veía mientras se dibujaba.
     *
     * Este factor es esa diferencia. No sale de una fórmula: sale de que el
     * aumento de trabajo típico ronda el doble.
     */
    private val encogeAlExportar: Double get() = if (paraExportar) 0.5 else 1.0

    /** Pinta la escena visible. */
    fun renderScene(
        canvas: Canvas, scene: Scene, screenWidth: Double, screenHeight: Double
    ) {
        escalaActual = scene.escala
        zoomActual = scene.viewport.zoom.coerceAtLeast(0.0001)
        vistaActual = scene.vista
        canvas.save()
        // Un único cambio de matriz para todo: el resto del código dibuja
        // siempre en coordenadas de escena y se olvida del zoom.
        canvas.scale(scene.viewport.zoom.toFloat(), scene.viewport.zoom.toFloat())
        canvas.translate(scene.viewport.scrollX.toFloat(), scene.viewport.scrollY.toFloat())

        // **El papel, debajo de todo.** Su píxel (0,0) es el (0,0) de la escena,
        // así que dibujar encima de él es dibujar sobre coordenadas de la
        // página — que es exactamente lo que hace falta para devolver la capa a
        // su sitio. Ver [papelALaVista].
        if (papelALaVista) {
            backdrop?.takeIf { !it.isRecycled }?.let {
                fillPaint.reset()
                fillPaint.isFilterBitmap = true
                canvas.drawBitmap(it, 0f, 0f, fillPaint)
            }
        }

        // La rejilla, justo encima del papel y debajo de todo lo demás.
        if (cuadricula != Cuadricula.NINGUNA) {
            pintarCuadricula(canvas, scene, screenWidth, screenHeight)
        }

        val visible = getVisibleElements(
            // Escondidas no se pintan; siguen ahí, guardadas, para volver.
            if (scene.referenciasVisibles) scene.elements
            else scene.elements.filter { !it.reference },
            scene.viewport, screenWidth, screenHeight
        )
        // **Lo que mira una lupa NO se puede recortar por pantalla, y aquí
        // estaba el fallo de que se vaciara al hacer zoom.**
        //
        // `getVisibleElements` tira todo lo que cae fuera de la pantalla, que es
        // lo que hace que un dibujo enorme siga yendo fino. Pero una lupa enseña
        // **otro trozo del dibujo**, y ese trozo casi nunca es el que se está
        // mirando: en cuanto se acerca uno a la ventana, la zona de origen se
        // sale de la pantalla, sus elementos se descartan antes de llegar aquí y
        // el cristal se queda vacío. Se veía como «al hacer zoom desaparece lo
        // de dentro», y no había nada roto en la lupa: le habían quitado el
        // contenido por el camino.
        //
        // Así que para ellas se usa el montón **sin recortar**. Se prepara solo
        // si hay alguna, y lo que de verdad se pinta se acota luego contra la
        // zona mirada, que es un rectángulo pequeño.
        val hayVentanas = visible.any {
            it.type == ElementType.MOSAIC || it.type == ElementType.LUPA
        }
        val sinRecortar = if (!hayVentanas) emptyList() else {
            if (scene.referenciasVisibles) scene.elements.filter { !it.isDeleted }
            else scene.elements.filter { !it.isDeleted && !it.reference }
        }
        val dondeEsta = if (!hayVentanas) emptyMap() else {
            sinRecortar.withIndex().associate { (n, el) -> el.id to n }
        }

        // El foco se pinta el último **de todos**, y no en su sitio del montón:
        // lo que hace es oscurecer el resto, así que si se pintara en orden lo
        // dibujado después se quedaría fuera de la sombra y el efecto se rompía.
        for ((i, element) in visible.withIndex()) {
            if (element.type == ElementType.SPOTLIGHT) continue
            // Lo que hay debajo de este elemento, por si es un mosaico o una
            // lupa y tiene que sacar de ahí sus píxeles. Ver [fondoDelDibujo].
            capaDebajo = if (element.type == ElementType.MOSAIC || element.type == ElementType.LUPA) {
                dondeEsta[element.id]?.let { sinRecortar.subList(0, it) }
                    ?: visible.subList(0, i)
            } else {
                null
            }
            renderElement(canvas, element)
        }
        capaDebajo = null
        // **Primero se pregunta, y solo después se separa.** El `filter` reservaba
        // una lista nueva en cada fotograma aunque no hubiera ni un foco, que es
        // lo normal: en un dibujo de mil elementos eso es una lista de mil
        // huecos por fotograma para acabar tirándola vacía. Preguntar no reserva
        // nada, y cuando de verdad hay focos —uno o dos— la segunda pasada no se
        // nota.
        if (visible.any { it.type == ElementType.SPOTLIGHT }) {
            drawSpotlights(
                canvas,
                visible.filter { it.type == ElementType.SPOTLIGHT },
                scene.viewport, screenWidth, screenHeight
            )
        }

        // Los puntos tecleados van **encima de todo**: son la referencia contra
        // la que se dibuja, y tapados por lo que se acaba de trazar dejarían de
        // servir justo cuando más falta hacen.
        scene.origenCoordenadas?.let { origen ->
            // El eje, **una sola vez**: es del dibujo, no de cada serie. Con una
            // cruz por tabla, tres series encima del mismo punto pintaban tres
            // cruces de tres colores y parecían tres ejes distintos.
            //
            // Y no sale al exportar: es la referencia contra la que se teclean
            // coordenadas, andamio y no dibujo. **Los puntos sí salen**, que es
            // lo que se estaba llevando por delante el atajo de antes: cortaba
            // aquí de un tirón y con el eje se iban también las tablas, o sea
            // justo el contenido que alguien tecleó a mano.
            if (!paraExportar) {
                drawEjeDeCoordenadas(
                    canvas, origen, scene.viewport, screenWidth, screenHeight
                )
            }
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
        val radio = (TABLA_PUNTO / zoomDeAdornos * encogeAlExportar).toFloat()
        val tam = (TABLA_LETRA / zoomDeAdornos * encogeAlExportar).toFloat()

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
    /**
     * El foco: **la sombra se queda dentro de su marco**.
     *
     * Antes oscurecía la pantalla entera menos su caja, y eso es un telón: sirve
     * para «mira solo esto» y para nada más. En una lámina no vale — apagar el
     * plano completo para señalar un detalle es tapar el contexto, que es
     * justo lo que hace falta ver al lado.
     *
     * Ahora son **dos figuras**: el marco de fuera —la caja del elemento, que se
     * estira con los tiradores— y el hueco de dentro, que es la figura que se
     * tocó con la varita y **no cambia** al estirar el marco. Lo que se pinta es
     * el anillo entre las dos. Así se resalta algo dejando ver todo lo demás, y
     * caben varios focos en el mismo dibujo sin que se sumen sus sombras.
     */
    private fun drawSpotlights(
        canvas: Canvas, focos: List<Element>, viewport: Viewport,
        screenWidth: Double, screenHeight: Double
    ) {
        for (f in focos) {
            val c = getElementAbsoluteCoords(f)
            val marco = Path()
            marco.addRect(
                c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat(),
                Path.Direction.CW
            )

            // El hueco: la figura que se marcó, en su sitio y con su tamaño.
            val dentro = puntosDelFoco(f)
            if (dentro.size >= 3) {
                val hueco = Path()
                hueco.moveTo(dentro.first().x.toFloat(), dentro.first().y.toFloat())
                for (i in 1 until dentro.size) {
                    hueco.lineTo(dentro[i].x.toFloat(), dentro[i].y.toFloat())
                }
                hueco.close()
                marco.op(hueco, Path.Op.DIFFERENCE)
            }

            // El giro se aplica al conjunto, para que el hueco no se despegue
            // del marco al torcerlo.
            if (f.angle != 0.0) {
                val m = android.graphics.Matrix()
                m.setRotate(Math.toDegrees(f.angle).toFloat(), c.cx.toFloat(), c.cy.toFloat())
                marco.transform(m)
            }

            fillPaint.reset()
            fillPaint.isAntiAlias = true
            fillPaint.style = Paint.Style.FILL
            // **Cuánto se apaga lo de fuera lo dice el propio foco**, de 10 a 90
            // por ciento. Antes salía de su opacidad multiplicada por una
            // constante, o sea que el mando decía una cosa y la sombra hacía
            // otra. Ahora el 60 % oscurece al 60 %, y cada foco lleva el suyo.
            val cuanto = oscurecimientoDe(f)
            fillPaint.color = Color.argb((cuanto * 255 / 100).coerceIn(0, 255), 0, 0, 0)
            canvas.drawPath(marco, fillPaint)
        }
    }

    /**
     * El fondo pautado.
     *
     * Se pinta en coordenadas de escena —la matriz ya está puesta— así que el
     * grosor hay que **dividirlo por el zoom** para que la raya se vea igual de
     * fina a cualquier aumento. Sin eso, alejando queda una telaraña y acercando
     * desaparece.
     */
    private fun pintarCuadricula(
        canvas: Canvas, scene: Scene, screenWidth: Double, screenHeight: Double
    ) {
        val vp = scene.viewport
        val z = vp.zoom.coerceAtLeast(0.0001)
        val paso = pasoDeCuadricula(z)
        val arriba = vp.toScene(0.0, 0.0)
        val abajo = vp.toScene(screenWidth, screenHeight)

        val xs = lineasDeCuadricula(arriba.x, abajo.x, paso)
        val ys = lineasDeCuadricula(arriba.y, abajo.y, paso)
        if (xs.isEmpty() || ys.isEmpty()) return

        paint.reset()
        paint.isAntiAlias = true
        paint.color = tema(android.graphics.Color.argb(255, 0xC8, 0xCC, 0xD4))

        if (cuadricula == Cuadricula.PUNTOS) {
            // Un punto en cada cruce: dice lo mismo que la rejilla ensuciando la
            // mitad, que es lo que se quiere encima de un dibujo denso.
            paint.style = Paint.Style.FILL
            val r = (RADIO_DEL_PUNTO_DE_CUADRICULA / z).toFloat()
            for (x in xs) for (y in ys) {
                canvas.drawCircle(x.toFloat(), y.toFloat(), r, paint)
            }
            return
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (GROSOR_DE_CUADRICULA / z).toFloat()
        for (x in xs) {
            canvas.drawLine(x.toFloat(), arriba.y.toFloat(), x.toFloat(), abajo.y.toFloat(), paint)
        }
        for (y in ys) {
            canvas.drawLine(arriba.x.toFloat(), y.toFloat(), abajo.x.toFloat(), y.toFloat(), paint)
        }
    }

    /**
     * El plano cartesiano: su rejilla, sus dos ejes y sus dos reglas.
     *
     * Dónde cae cada raya y cada número no se decide aquí —eso es [Plano], que
     * se comprueba sin pantalla— y aquí solo queda ponerle tinta. La rejilla va
     * más clara que los ejes: son dos cosas distintas y a la misma intensidad la
     * de fondo se come a la que hay que seguir con la vista.
     */
    private fun drawPlano(canvas: Canvas, e: Element, alpha: Int) {
        if (e.width <= 0 || e.height <= 0) return
        val tinta = tema(parseColor(e.strokeColor, alpha))
        val grosor = e.strokeWidth.coerceAtLeast(0.5)

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        for (trazo in trazosDelPlano(e)) {
            paint.color =
                if (trazo.eje) tinta else conAlfa(tinta, ALFA_DE_LA_REJILLA.toFloat())
            paint.strokeWidth =
                (if (trazo.eje) grosor else GROSOR_DE_LA_REJILLA).toFloat()
            canvas.drawLine(
                (e.x + trazo.a.x).toFloat(), (e.y + trazo.a.y).toFloat(),
                (e.x + trazo.b.x).toFloat(), (e.y + trazo.b.y).toFloat(),
                paint
            )
        }

        val tam = (e.fontSize ?: TAM_DE_LA_CIFRA).coerceAtLeast(1.0)
        paint.style = Paint.Style.FILL
        paint.color = tinta
        paint.textSize = tam.toFloat()
        paint.typeface = typefaces(e.fontFamily)
        for (n in numerosDelPlano(e)) {
            // La cifra se aparta de su eje lo justo para no pisarlo: debajo en
            // la regla de abajo, a la izquierda en la de la izquierda.
            if (n.horizontal) {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    n.texto,
                    (e.x + n.donde.x).toFloat(),
                    (e.y + n.donde.y + tam * 1.15).toFloat(),
                    paint
                )
            } else {
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(
                    n.texto,
                    (e.x + n.donde.x - tam * 0.3).toFloat(),
                    (e.y + n.donde.y + tam * 0.35).toFloat(),
                    paint
                )
            }
        }
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
            ElementType.SOLIDO -> drawSolido(canvas, element, alpha)
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
            ElementType.PLANO -> drawPlano(canvas, element, alpha)
            // El foco no se pinta aquí: va el último de todos, en renderScene.
            ElementType.SPOTLIGHT -> Unit
            ElementType.LUPA -> drawLupa(canvas, element, alpha)
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

    /**
     * La caja en volumen: **la sombra, y luego las caras de atrás hacia delante**.
     *
     * ## La sombra primero, y siempre
     *
     * No es un adorno. En isométrica, subir una caja y alejarla se ven
     * **exactamente igual** —las dos la mueven hacia arriba en la pantalla y nada
     * más—, así que sin la huella marcada en el suelo no hay forma de saber dónde
     * está apoyada ni cuánto levanta. Es el canal de profundidad más barato que
     * existe y el que más dice; el porqué largo, en [sombraEnElSuelo].
     *
     * Se pinta con el color del trazo y muy translúcida: es un calco de la
     * huella, como el que hace un dibujante a mano, no una sombra proyectada de
     * verdad. Y va debajo de todo, porque el sólido se apoya encima.
     *
     * ## Y las caras, en el orden en que vienen
     *
     * [carasDeElemento] las devuelve ya ordenadas de atrás hacia delante, así que
     * pintarlas en ese orden **es** el algoritmo del pintor. Cada una con su
     * relleno —el color base movido por su claridad— y su trazo encima, que es el
     * orden de siempre en este motor: al revés, el relleno rugoso se comería el
     * borde por dentro.
     */
    private fun drawSolido(canvas: Canvas, e: Element, alpha: Int) {
        val shape = geometryOf(e) ?: return
        val caras = shape.caras ?: return

        dibujarSombra(canvas, e, alpha)

        val fondo = !isTransparent(e.backgroundColor)
        val pincelDeTrazo = strokePaint(e, alpha)
        for (cara in caras) {
            if (fondo) {
                val color = tema(parseColor(aclarar(e.backgroundColor, cara.claridad), alpha))
                fillPaint.reset()
                fillPaint.isAntiAlias = true
                fillPaint.color = color
                if (cara.rayado == null) {
                    // Liso a propósito, como el resto del motor: con ruido el
                    // relleno dejaría huecos blancos justo por dentro del borde,
                    // y en una caja eso rompe la continuidad entre dos caras que
                    // comparten arista.
                    fillPaint.style = Paint.Style.FILL
                    canvas.drawPath(cara.silueta, fillPaint)
                } else {
                    fillPaint.style = Paint.Style.STROKE
                    fillPaint.strokeWidth = (e.strokeWidth / 2).toFloat()
                    fillPaint.strokeCap = Paint.Cap.ROUND
                    canvas.save()
                    canvas.clipPath(cara.silueta)
                    canvas.drawPath(cara.rayado, fillPaint)
                    canvas.restore()
                }
            }
            canvas.drawPath(cara.trazo, pincelDeTrazo)
        }
    }

    /**
     * La huella de un sólido marcada en el suelo.
     *
     * Va translúcida y del color del trazo —no de un gris fijo— para que sobre un
     * fondo oscuro siga viéndose: un calco tenue del color con el que se dibuja
     * se lee en cualquier papel, y un gris de fábrica desaparece en la mitad de
     * ellos.
     */
    private fun dibujarSombra(canvas: Canvas, e: Element, alpha: Int) {
        val sombra = sombraDeElemento(e, vistaActual)
        if (sombra.size < 3) return
        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color =
            tema(parseColor(e.strokeColor, alpha * OPACIDAD_DE_LA_SOMBRA / 100))
        canvas.drawPath(sombra.toClosedPath(), fillPaint)
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

    /**
     * La lupa: un trozo del dibujo, enseñado en grande.
     *
     * ## Se vuelve a dibujar, no se amplía una foto
     *
     * Lo primero que uno piensa es recortar un mapa de bits de lo que hay debajo
     * y estirarlo. Eso es lo que hace el mosaico, y para el mosaico está bien
     * porque el grano **es** el efecto. Aquí sería justo lo contrario de lo que
     * se quiere: agrandar píxeles enseña píxeles, y una lupa está para ver mejor
     * el detalle, no para verlo más borroso.
     *
     * Así que se cambia la matriz del lienzo y **se pinta la escena otra vez**
     * dentro del cristal. Lo que sale está dibujado a esa escala de verdad: las
     * líneas siguen siendo finas y el texto se lee. Y de paso no se reserva ni un
     * mapa de bits — es una transformación y unas cuantas rutas, o sea lo mismo
     * que cuesta pintar el dibujo una segunda vez, pero solo del trozo que cabe.
     *
     * Con una foto detrás (una captura anotada) sí se coge del mapa de bits,
     * porque ahí los píxeles son el original: no hay nada más fino que enseñar.
     * Se hacen las dos cosas, en orden — foto y encima lo dibujado— que es lo
     * mismo que se ve fuera del cristal.
     */
    private fun drawLupa(canvas: Canvas, e: Element, alpha: Int) {
        val c = getElementAbsoluteCoords(e)
        val cristal = RectF(c.x1.toFloat(), c.y1.toFloat(), c.x2.toFloat(), c.y2.toFloat())
        if (cristal.width() < 1f || cristal.height() < 1f) return

        val region = regionDeLaLupa(e)
        val aumento = aumentoDe(e).toFloat()
        val foco = focoDe(e)
        val contorno = rutaDelCristal(e, c)

        // **El borde se desvanece.** Un canto duro delata que es un recorte
        // pegado encima; difuminado se lee como un cristal de verdad apoyado
        // sobre el papel. Se pinta todo en una capa aparte y al final se le
        // aplica una máscara redonda que se apaga hacia fuera —`DST_IN` conserva
        // lo que la máscara tiene opaco—, que es la forma barata de difuminar un
        // borde sin desenfocar nada.
        // **Y solo si cabe.** Una capa aparte se reserva como una textura, y las
        // texturas tienen tope: con la lupa muy ampliada el recuadro se pasaba y
        // la capa fallaba entera —lo de dentro **desaparecía**, que es justo lo
        // que se veía al subir el zoom—. Pasado el tope se pinta sin difuminar:
        // el borde queda duro, que es infinitamente mejor que quedarse vacío.
        // **Medido en píxeles de pantalla, no de escena.** El lienzo llega con
        // su zoom puesto, así que un cristal de dos mil puntos a zoom cuatro son
        // ocho mil píxeles de textura: el tope se pasaba igualmente y la capa
        // fallaba en silencio, llevándose lo de dentro.
        val ladoEnPantalla = maxOf(cristal.width(), cristal.height()) * zoomActual
        val cabeLaCapa = ladoEnPantalla <= LADO_MAXIMO_DE_CAPA
        val capa = if (!cabeLaCapa) -1 else canvas.saveLayer(
            cristal.left - 1f, cristal.top - 1f, cristal.right + 1f, cristal.bottom + 1f, null
        )
        canvas.save()
        canvas.clipPath(contorno)

        // El papel debajo del todo: la escena no pinta fondo, y sin esto el
        // cristal dejaría ver lo que hay detrás de la lupa mezclado con lo que
        // enseña — dos dibujos superpuestos y ninguno legible.
        fillPaint.reset()
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = parseColor(DrawTheme.fondoDe(dark))
        canvas.drawRect(cristal, fillPaint)

        // La matriz que lleva el foco al centro del cristal, agrandado.
        canvas.save()
        canvas.translate(cristal.centerX(), cristal.centerY())
        canvas.scale(aumento, aumento)
        canvas.translate(-foco.x.toFloat(), -foco.y.toFloat())

        backdrop?.takeIf { !it.isRecycled }?.let { foto ->
            imagePaint.alpha = alpha
            canvas.drawBitmap(foto, 0f, 0f, imagePaint)
        }

        // Lo dibujado que cae dentro del foco. Se saltan las lupas: una lupa
        // mirando a otra lupa se pintaría a sí misma hasta quedarse sin pila.
        val debajo = capaDebajo?.filter {
            it.type != ElementType.LUPA && it.type != ElementType.SPOTLIGHT
        }.orEmpty()
        val guardado = capaDebajo
        capaDebajo = null
        for (el in debajo) {
            if (boundsOverlap(getElementBounds(el), region)) renderElement(canvas, el)
        }
        capaDebajo = guardado
        canvas.restore()
        canvas.restore()

        if (cabeLaCapa) {
            difuminarElBorde(canvas, cristal)
            canvas.restoreToCount(capa)
        }

        // La montura, por fuera del recorte para que se vea entera.
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = tema(parseColor(e.strokeColor, alpha))
        // **La montura va más gruesa que un trazo normal.** Es el borde de una
        // ventana y además es por donde se agarra: a un punto de grosor casi no
        // se ve dónde acaba la lupa, y cuesta cogerla para moverla.
        paint.strokeWidth = (e.strokeWidth * 1.6).toFloat().coerceAtLeast(2.5f)
        canvas.drawPath(contorno, paint)

        dibujarLaGuia(canvas, e, alpha)
    }

    /**
     * Apaga el borde de la capa hacia fuera.
     *
     * El degradado va del centro —opaco del todo— al canto, y se aplica con
     * `DST_IN`, que se queda con lo que la máscara tiene opaco. El punto donde
     * empieza a apagarse ([DENTRO_NITIDO]) va alto a propósito: casi toda la
     * lupa se ve nítida y solo la orilla se funde, que es lo que hace un cristal
     * y no una mancha.
     */
    private fun difuminarElBorde(canvas: Canvas, cristal: RectF) {
        val radio = maxOf(cristal.width(), cristal.height()) / 2f
        if (radio < 2f) return
        mascaraPaint.shader = android.graphics.RadialGradient(
            cristal.centerX(), cristal.centerY(), radio,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, DENTRO_NITIDO, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(cristal, mascaraPaint)
    }

    /**
     * La brocha de la máscara, con su modo puesto una sola vez.
     *
     * `PorterDuffXfermode` es un objeto que no cambia nunca; creándolo en cada
     * fotograma serían dos reservas por lupa y fotograma para siempre lo mismo.
     */
    private val mascaraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    /**
     * El contorno del cristal, sea el que sea.
     *
     * Sale de [puntosDelCristal], que es **la misma lista** que usan el picado,
     * la guía, el SVG y el PDF. Antes esto dibujaba su propio óvalo por su
     * cuenta; en cuanto una lupa pudo tener la forma de un rombo o de un
     * garabato, eso habría sido un recorte que no coincide con nada.
     */
    private fun rutaDelCristal(e: Element, c: AbsoluteCoords): Path {
        val puntos = puntosDelCristal(e, c)
        val ruta = Path()
        if (puntos.size < 3) return ruta
        ruta.moveTo(puntos.first().x.toFloat(), puntos.first().y.toFloat())
        for (i in 1 until puntos.size) {
            ruta.lineTo(puntos[i].x.toFloat(), puntos[i].y.toFloat())
        }
        ruta.close()
        return ruta
    }

    /**
     * De dónde sale lo que se está viendo: el recuadro del foco y la raya.
     *
     * Sin esto una lupa apartada es un misterio —un trozo de dibujo flotando— y
     * la herramienta pierde justo lo que la hace servir en una lámina. La raya va
     * fina y con la punta **en el foco**, que es lo que se está señalando.
     */
    private fun dibujarLaGuia(canvas: Canvas, e: Element, alpha: Int) {
        // Apoyada sobre lo que mira no se dibuja nada: el detalle está debajo.
        if (laLupaEstaEncima(e)) return

        // **La guía se pinta FUERA del giro del elemento, y esto era un fallo.**
        //
        // El lienzo llega ya girado alrededor del centro del cristal —lo hace
        // `renderElement` para todas las figuras— así que el recuadro del foco,
        // que vive en coordenadas del dibujo y no del cristal, salía pintado en
        // otro sitio. Al girar la lupa, el foco parecía irse con ella y el dedo
        // ya no lo encontraba: lo que se picaba estaba donde de verdad, y lo que
        // se veía, girado. Deshaciendo el giro aquí, lo dibujado y lo que se
        // toca vuelven a ser lo mismo.
        canvas.save()
        if (e.angle != 0.0) {
            val c = getElementAbsoluteCoords(e)
            canvas.rotate(-Math.toDegrees(e.angle).toFloat(), c.cx.toFloat(), c.cy.toFloat())
        }
        dibujarLaGuiaDerecha(canvas, e, alpha)
        canvas.restore()
    }

    private fun dibujarLaGuiaDerecha(canvas: Canvas, e: Element, alpha: Int) {

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = tema(parseColor(e.strokeColor, alpha * 3 / 4))
        paint.strokeWidth = (e.strokeWidth / 2).toFloat().coerceAtLeast(0.75f)

        // El contorno de la zona mirada, con **la forma del cristal**: si el
        // cristal es redondo lo que entra es un óvalo, y un recuadro señalaría
        // una zona que no es.
        val puntos = puntosDelFoco(e)
        if (puntos.size < 3) return
        val contorno = Path()
        contorno.moveTo(puntos.first().x.toFloat(), puntos.first().y.toFloat())
        for (i in 1 until puntos.size) {
            contorno.lineTo(puntos[i].x.toFloat(), puntos[i].y.toFloat())
        }
        contorno.close()

        val modo = guiaDe(e)
        // **Con el grosor de la lupa**, no más fino: la zona y la ventana son
        // las dos mitades de lo mismo, y dibujadas con trazos distintos parecen
        // dos cosas que se han juntado por casualidad.
        if (modo.dibujaLaZona) {
            paint.strokeWidth = (e.strokeWidth * 1.6).toFloat().coerceAtLeast(2.5f)
            paint.color = tema(parseColor(e.strokeColor, alpha))
            canvas.drawPath(contorno, paint)
        } else if (e.id in marcados) {
            // Sin contorno propio, **entrelineado mientras se toca**: es lo que
            // se agarra para apuntar la lupa a otro sitio, y sin verlo no hay
            // forma de saber dónde está. De rayitas para que se lea como un
            // mando y no como algo dibujado.
            paint.strokeWidth = (1.5 / zoomDeAdornos).toFloat()
            paint.color = tema(parseColor(e.strokeColor, alpha * 3 / 4))
            paint.pathEffect = android.graphics.DashPathEffect(
                floatArrayOf((6.0 / zoomDeAdornos).toFloat(), (5.0 / zoomDeAdornos).toFloat()), 0f
            )
            canvas.drawPath(contorno, paint)
            paint.pathEffect = null
        }
        // **La raya engorda con el grosor, igual que el marco.** Iba a la mitad
        // —«señala, no dibuja»— y el resultado era que subir el grosor engordaba
        // la lupa y la zona pero dejaba la raya igual de fina: las tres partes
        // de la misma cosa, cada una de un peso. Ahora es una sola pieza.
        paint.strokeWidth = (e.strokeWidth * 1.6).toFloat().coerceAtLeast(2.5f)
        paint.color = tema(parseColor(e.strokeColor, alpha))

        // Las rayas que unen las dos cosas: una en la flecha y en el punto, dos
        // en el cono.
        for ((a, b) in lineasDeLaGuia(e)) {
            canvas.drawLine(b.x.toFloat(), b.y.toFloat(), a.x.toFloat(), a.y.toFloat(), paint)
        }

        // El punto gordo: cuando lo que se señala es **un sitio** y no una zona,
        // el contorno de lo mirado sería una mota. El punto se ve.
        if (modo == GuiaDeLupa.PUNTO) {
            val r = regionDeLaLupa(e)
            paint.style = Paint.Style.FILL
            // **Rojo**, que es el color de señalar: sobre un plano lleno de
            // líneas grises un punto del color del trazo se pierde.
            paint.color = Color.argb(alpha, 220, 38, 38)
            // Y **crece con el grosor**, como cualquier trazo: si la raya que
            // sale de él es gorda y el punto se queda fino, no parecen lo mismo.
            val gordo = maxOf(RADIO_DEL_PUNTO_GORDO / zoomDeAdornos, e.strokeWidth * 1.6)
            canvas.drawCircle(r.midX.toFloat(), r.midY.toFloat(), gordo.toFloat(), paint)
            return
        }

        // La punta va solo con la flecha: con el cono, las dos rayas ya dicen a
        // dónde van y una punta en cada una sería ruido.
        val (desde, hasta) = flechaDeLaLupa(e) ?: return
        val ang = kotlin.math.atan2(desde.y - hasta.y, desde.x - hasta.x)
        // La punta también crece con el grosor: con una raya gorda, una punta
        // de tamaño fijo se queda dentro de la propia raya y no se ve.
        val largo = maxOf((12.0 / zoomActual), e.strokeWidth * 4.0).coerceAtLeast(4.0)
        val abre = Math.toRadians(22.0)
        paint.style = Paint.Style.FILL
        val punta = Path()
        punta.moveTo(desde.x.toFloat(), desde.y.toFloat())
        punta.lineTo(
            (desde.x - largo * kotlin.math.cos(ang - abre)).toFloat(),
            (desde.y - largo * kotlin.math.sin(ang - abre)).toFloat()
        )
        punta.lineTo(
            (desde.x - largo * kotlin.math.cos(ang + abre)).toFloat(),
            (desde.y - largo * kotlin.math.sin(ang + abre)).toFloat()
        )
        punta.close()
        canvas.drawPath(punta, paint)
    }

    /**
     * La lupa **ya pintada en un mapa de bits**, para quien tenga que guardarla.
     *
     * La necesitan el SVG y el PDF. En pantalla la lupa vuelve a dibujar la
     * escena dentro del cristal, y eso no se puede escribir en un archivo sin
     * repetir dentro de él todo lo que enseña, recortado y a otra escala. Un
     * trozo de imagen dice lo mismo y se abre en cualquier visor.
     */
    internal fun contenidoDeLaLupa(e: Element, debajo: List<Element>): Bitmap? {
        val c = getElementAbsoluteCoords(e)
        val ancho = c.x2 - c.x1
        val alto = c.y2 - c.y1
        if (ancho < 1 || alto < 1) return null
        val lado = LADO_DE_LA_LUPA_EXPORTADA
        val w = Math.ceil(minOf(ancho * 2, lado)).toInt().coerceAtLeast(1)
        val h = Math.ceil(w * alto / ancho).toInt().coerceAtLeast(1)
        val foco = focoDe(e)
        val aumento = aumentoDe(e)

        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val lienzo = Canvas(bmp)
            lienzo.drawColor(parseColor(DrawTheme.fondoDe(dark)))
            // Del mapa de bits al dibujo: primero a la escala de salida, luego
            // la misma cuenta que en pantalla.
            lienzo.scale((w / ancho).toFloat(), (h / alto).toFloat())
            lienzo.translate((ancho / 2).toFloat(), (alto / 2).toFloat())
            lienzo.scale(aumento.toFloat(), aumento.toFloat())
            lienzo.translate(-foco.x.toFloat(), -foco.y.toFloat())
            backdrop?.takeIf { !it.isRecycled }?.let { lienzo.drawBitmap(it, 0f, 0f, null) }
            val guardado = capaDebajo
            capaDebajo = null
            for (el in debajo) {
                if (el.type == ElementType.LUPA || el.type == ElementType.SPOTLIGHT) continue
                renderElement(lienzo, el)
            }
            capaDebajo = guardado
            bmp
        }.getOrNull()
    }

    /**
     * Lo que está marcado ahora mismo.
     *
     * El motor no sabe de selecciones —las pinta el lienzo por encima— pero la
     * lupa necesita saberlo: el contorno de lo que mira es un mando y solo tiene
     * sentido mientras se la está tocando. Quien exporta no lo pone nunca, así
     * que en un archivo no aparece.
     */
    var marcados: Set<String> = emptySet()

    /** La brocha de las imágenes, una para todas. Ver [drawImage]. */
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

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
        // **La brocha se reutiliza.** Creada aquí, era una reserva de memoria
        // por imagen y por fotograma: con tres fotos en el lienzo y el dedo
        // moviéndose, doscientas al segundo. No cuesta milisegundos, pero es de
        // donde salen los tirones sueltos — la recogida de basura llega cuando
        // le parece, y siempre parece que sea a mitad de un trazo.
        imagePaint.alpha = alpha
        canvas.drawBitmap(bitmap, src, dst, imagePaint)
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
        // Negrita, cursiva y tachado. La cursiva se inclina con la matriz del
        // texto y no con otra fuente: las tres de Excalidraw no traen versión
        // itálica, así que o se sesga o no hay cursiva.
        paint.isFakeBoldText = e.negrita
        paint.textSkewX = if (e.cursiva) SESGO_DE_LA_CURSIVA else 0f
        paint.isStrikeThruText = e.tachado
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
        val lineHeight = size * INTERLINEADO
        // **La primera línea se apoya en su ascendente, no en su tamaño.**
        //
        // Estaba en `y1 + size`, y el cuadro de escribir coloca la suya en
        // `y1 + ascendente` —que es como coloca el texto cualquier caja de
        // texto—: la diferencia son unos pocos píxeles hacia abajo, justo los
        // que se veía saltar el texto al darle a intro. Ahora se pinta donde se
        // estaba escribiendo.
        val fm = paint.fontMetrics
        var y = c.y1 - fm.ascent
        // **Y con tope por los lados.** Ver [renglonesQueCaben]: antes se partía solo por
        // los saltos escritos, así que estrechar la caja dejaba el texto saliéndose por la
        // derecha por encima de lo que hubiera al lado. Una caja de texto que no contiene
        // el texto no es una caja.
        val ancho = c.x2 - c.x1
        for (line in renglonesQueCaben(content, ancho) { paint.measureText(it).toDouble() }) {
            canvas.drawText(line, originX.toFloat(), y.toFloat(), paint)
            y += lineHeight
        }
    }

    // ---------------------------------------------------------------------
    // Pinceles
    // ---------------------------------------------------------------------

    // El último guion hecho, para no rehacerlo. Los elementos de un dibujo
    // comparten estilo y grosor casi siempre —se dibuja un rato con lo mismo—
    // así que con recordar el último se acierta casi entero, y se deja de
    // reservar un objeto por línea discontinua y fotograma.
    private var estiloDelGuion: StrokeStyle? = null
    private var anchoDelGuion = Double.NaN
    private var elGuion: DashPathEffect? = null

    private fun efectoDe(estilo: StrokeStyle, ancho: Double): DashPathEffect? {
        if (estilo == StrokeStyle.SOLID) return null
        if (estilo == estiloDelGuion && ancho == anchoDelGuion) return elGuion
        val nuevo = when (estilo) {
            StrokeStyle.DASHED -> DashPathEffect(floatArrayOf(8f, (8 + ancho).toFloat()), 0f)
            else -> DashPathEffect(floatArrayOf(1.5f, (6 + ancho).toFloat()), 0f)
        }
        estiloDelGuion = estilo
        anchoDelGuion = ancho
        elGuion = nuevo
        return nuevo
    }

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
        paint.pathEffect = efectoDe(e.strokeStyle, e.strokeWidth)
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
        /**
         * Lo más que puede medir una capa aparte, **en píxeles de pantalla**.
         *
         * Muy por debajo del tope de textura de cualquier móvil. Pasado esto se
         * pinta sin difuminar: el borde queda duro, que es infinitamente mejor
         * que quedarse vacío.
         */
        const val LADO_MAXIMO_DE_CAPA = 3000.0

        /** Hasta dónde se ve nítida la lupa antes de empezar a fundirse. */
        const val DENTRO_NITIDO = 0.86f

        /** Lo que mide el punto gordo de la guía, en píxeles de pantalla. */
        const val RADIO_DEL_PUNTO_GORDO = 7.0

        /** A cuántos píxeles de ancho se guarda una lupa al exportar. */
        const val LADO_DE_LA_LUPA_EXPORTADA = 900.0

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
    val fillPath: Path?,
    /**
     * Las caras de un sólido, de atrás hacia delante. Null en todo lo demás.
     *
     * Va como una lista aparte y no reusando los tres caminos de arriba porque
     * cada cara lleva **su color**, y un `Path` no guarda color: fundirlas en un
     * solo camino perdería justo el dato que hace que se lea el volumen.
     */
    val caras: List<CaraPintada>? = null
)

/** Una cara de un sólido, ya en caminos de Android y con su tono. */
private class CaraPintada(
    /** El cuadrilátero cerrado: para el relleno liso y para recortar el rayado. */
    val silueta: Path,
    /** Trazos del rayado, o null si el relleno es liso o no hay. */
    val rayado: Path?,
    val trazo: Path,
    /** Cuánto se aclara el color base en esta cara. Ver [claridadDe]. */
    val claridad: Double
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
        a.arcSweep == b.arcSweep &&
        // **Y la altura, por lo mismo.** Es la tercera medida del sólido y no
        // está en `width` ni en `height`: sin ella, una caja que se está
        // levantando tiene la misma huella en cada fotograma —lo que crece es lo
        // que sube— así que la caché devolvería la primera altura generada y ahí
        // se quedaría. El mismo fallo del arco, con otro campo.
        //
        // Lo que **no** puede entrar aquí es la vista, porque no es un campo del
        // elemento. Esa va en la clave de la caché: ver [claveDeCache].
        a.altura == b.altura

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

/**
 * Lo fina que va la rejilla y lo pequeño que va su punto, en píxeles de
 * **pantalla**.
 *
 * En pantalla y no de escena: la referencia tiene que verse igual de discreta a
 * cualquier aumento, así que el renderizador lo divide por el zoom. Medio píxel
 * es lo justo para que se vea sin competir con el dibujo.
 */
private const val GROSOR_DE_CUADRICULA = 0.6
private const val RADIO_DEL_PUNTO_DE_CUADRICULA = 1.1

/**
 * Cuánto se inclina la cursiva.
 *
 * Un cuarto es lo que usa el propio Android para la itálica falsa; más se lee
 * como un error de pintado y menos no se distingue de la letra recta.
 */
private const val SESGO_DE_LA_CURSIVA = -0.25f


/** El tamaño de las cifras del plano si el elemento no dice otro. */
private const val TAM_DE_LA_CIFRA = 11.0

/** El mismo color con otra opacidad. */
private fun conAlfa(color: Int, factor: Float): Int = Color.argb(
    (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
    Color.red(color), Color.green(color), Color.blue(color)
)
