package com.forge.pixpin.motor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * El estado del lienzo: herramienta activa, estilo de lo próximo que se dibuje,
 * qué se ve y qué está seleccionado. Port de la parte de `AppState` que usa
 * este módulo.
 */

/**
 * Cómo serializar para que el JSON salga idéntico al de `.excalidraw`.
 *
 * Las tres opciones son obligatorias y cada una arregla un problema distinto:
 *
 * - `encodeDefaults`: sin esto, un elemento con los valores por defecto saldría
 *   medio vacío y Excalidraw no sabría rellenarlo.
 * - `explicitNulls = false`: [Element] es una clase plana, así que un rectángulo
 *   tiene `points`, `text` y `fileId` a null. Excalidraw **no emite** los campos
 *   que no aplican al tipo; escribirlos como null ensucia el archivo y lo aleja
 *   del formato original.
 * - `ignoreUnknownKeys`: al leer un `.excalidraw` hecho en la web vienen campos
 *   que este módulo no porta (`frameId`, `elbowed`, `customData`…). Sin esto,
 *   abrir un archivo del navegador reventaría.
 */
val ExcalidrawJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * Herramientas. Quedan fuera frame, embeddable, láser y bote.
 *
 * Las últimas no son de Excalidraw: [HIGHLIGHTER] es el lápiz con otro ajuste;
 * [MOSAIC], [SPOTLIGHT] y [SERIAL] vienen del motor de anotación viejo, donde
 * eran lo más útil sobre una captura; y [MEASURE] y [SCALE] vienen del croquis,
 * que era una aplicación entera dedicada a medir. Ver [ElementType] y [Escala].
 */
enum class Tool {
    SELECTION, LASSO, HAND,
    RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT, IMAGE,
    ERASER,
    HIGHLIGHTER, MOSAIC, SPOTLIGHT, SERIAL, FRAME,


    /** La cota: se arrastra sobre lo que se quiere medir y se rotula sola. */
    MEASURE,

    /**
     * Escalar: se arrastra sobre algo de medida **conocida** y se dicta cuánto
     * mide. Es lo que le da unidades a todas las cotas de la escena.
     */
    SCALE,

    /**
     * El bote: **rellena el hueco que se toque**, sea de quien sea.
     *
     * Es la única herramienta que no dibuja una figura nueva sino que mira las
     * que ya hay: busca hasta dónde llega el espacio tocado sin salirse y pinta
     * eso. Un toque, sin arrastrar. Ver [Regiones].
     */
    RELLENO,

    /**
     * Recortar: se toca el trozo de raya que sobra y se va, hasta donde la
     * cruzan las demás. Si el trozo estaba en medio, la raya se parte. Ver
     * [Recorte].
     */
    RECORTAR,

    /** Extender: se toca la punta que se queda corta y llega hasta lo primero que topa. */
    EXTENDER,

    /**
     * La escala gráfica: se arrastra para decir lo ancha que va y ella sola se
     * reparte en cuadros redondos. Ver [EscalaGrafica].
     */
    ESCALA_GRAFICA,

    /**
     * Soldar vértices: se tocan los que no se tienen que separar y a partir de
     * ahí se mueven juntos. Ver [Nudos].
     */
    NUDO;

    /** Las que crean una forma con caja al arrastrar. */
    val isShape: Boolean
        get() = this == RECTANGLE || this == DIAMOND || this == ELLIPSE ||
            this == MOSAIC || this == SPOTLIGHT || this == FRAME ||
            this == ESCALA_GRAFICA

    /** Las que crean un elemento de puntos. */
    val isLinear: Boolean
        get() = this == ARROW || this == LINE || this == MEASURE || this == SCALE

    /** Las que dibujan siguiendo el dedo. */
    val isFreehand: Boolean get() = this == FREEDRAW || this == HIGHLIGHTER
}

/**
 * Estilo que se aplica a lo siguiente que se dibuje (`currentItem*`).
 *
 * Va aparte del elemento porque es **estado del editor, no del dibujo**: al
 * cambiar el color con nada seleccionado, lo que cambia es esto; con algo
 * seleccionado, cambian los elementos *y* esto, para que lo siguiente salga
 * igual. Es la conducta del original y la que espera la mano.
 */
@Serializable
data class ItemStyle(
    val strokeColor: String = Element.DEFAULT_STROKE_COLOR,
    val backgroundColor: String = Element.TRANSPARENT,
    val fillStyle: FillStyle = FillStyle.SOLID,
    val strokeWidth: Double = 2.0,
    val strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val roughness: Int = Element.ROUGHNESS_ARTIST,
    val opacity: Int = 100,
    val roundness: Roundness? = Roundness(Roundness.ADAPTIVE_RADIUS),
    val startArrowhead: Arrowhead? = null,
    val endArrowhead: Arrowhead? = Arrowhead.ARROW,
    /** Las flechas nuevas salen de codos. Ver [Elbow]. */
    val elbowed: Boolean = false,
    val fontSize: Double = 20.0,
    val fontFamily: Int = FONT_EXCALIFONT,
    /**
     * El mosaico tapa con mancha en vez de con bloques.
     *
     * Va en el estilo y no solo en el elemento por lo mismo que el color: se
     * elige una vez y lo siguiente que se tape sale igual, sin volver al panel.
     */
    val mosaicBlur: Boolean = false,
    val textAlign: TextAlign = TextAlign.LEFT,
    val verticalAlign: VerticalAlign = VerticalAlign.TOP
) {
    companion object {
        /** Los cuatro grosores del original (`STROKE_WIDTH`). */
        val STROKE_WIDTHS = listOf(1.0, 2.0, 4.0, 8.0)

        /**
         * Los grosores **del lápiz** (`FREEDRAW_STROKE_WIDTH`), que son otros.
         *
         * No es un capricho del original: el trazo a mano se dibuja como una
         * mancha de `strokeWidth × 4,25` (ver [FreedrawTuning]), así que con la
         * escala de las formas el lápiz saldría cuatro veces más gordo de lo que
         * pide el botón. Cada posición del selector vale menos aquí.
         */
        val FREEDRAW_STROKE_WIDTHS = listOf(0.5, 1.0, 2.0, 4.0)

        /** El grosor de lápiz que corresponde a [ancho] en la escala de formas. */
        fun freedrawWidthFor(ancho: Double): Double {
            val i = STROKE_WIDTHS.indexOfFirst { it >= ancho }
            return FREEDRAW_STROKE_WIDTHS[if (i < 0) FREEDRAW_STROKE_WIDTHS.lastIndex else i]
        }

        /** Los cuatro tamaños de fuente (`FONT_SIZE`). */
        val FONT_SIZES = listOf(16.0, 20.0, 28.0, 36.0)

        /**
         * Las tres familias que ofrece el original, con **sus números**
         * (`FONT_FAMILY`).
         *
         * Los números no son correlativos y no se pueden reordenar: van en el
         * `.excalidraw` y son lo que decide con qué letra se reabre un dibujo
         * hecho en la web. El 4 está libre a propósito —lo usó una fuente que
         * el original ya no trae— y los antiguos 1, 2 y 3 siguen valiendo como
         * alias de estos tres (ver [fontFamilyResuelta]).
         */
        const val FONT_EXCALIFONT = 5
        const val FONT_NUNITO = 6
        const val FONT_COMIC_SHANNS = 8

        /** Numeración vieja, la de los dibujos de antes. */
        const val FONT_VIRGIL = 1
        const val FONT_HELVETICA = 2
        const val FONT_CASCADIA = 3

        /** Las tres, en el orden en que las ofrece el original. */
        val FONT_FAMILIES = listOf(FONT_EXCALIFONT, FONT_NUNITO, FONT_COMIC_SHANNS)

        /**
         * La familia con la que hay que pintar, resolviendo los alias viejos.
         *
         * Virgil era la fuente a mano de las primeras versiones y Excalifont la
         * sustituyó; Helvetica y Cascadia hacían de normal y monoespaciada. Un
         * dibujo guardado con aquellos números tiene que seguir viéndose con la
         * letra que le toca, no caer al valor por defecto.
         */
        fun fontFamilyResuelta(id: Int?): Int = when (id) {
            null, FONT_VIRGIL, FONT_EXCALIFONT -> FONT_EXCALIFONT
            FONT_HELVETICA, FONT_NUNITO -> FONT_NUNITO
            FONT_CASCADIA, FONT_COMIC_SHANNS -> FONT_COMIC_SHANNS
            else -> FONT_EXCALIFONT
        }
    }
}

/**
 * Qué trozo de escena se está mirando.
 *
 * Igual que en Excalidraw, el lienzo infinito **no reserva memoria**: es solo
 * este desplazamiento y este zoom. Panear cambia dos números.
 */
@Serializable
data class Viewport(
    val scrollX: Double = 0.0,
    val scrollY: Double = 0.0,
    val zoom: Double = 1.0
) {
    /** De pantalla a escena (`viewportCoordsToSceneCoords`). */
    fun toScene(screenX: Double, screenY: Double): Pt =
        Pt(screenX / zoom - scrollX, screenY / zoom - scrollY)

    /** De escena a pantalla (`sceneCoordsToViewportCoords`). */
    fun toScreen(p: Pt): Pt = Pt((p.x + scrollX) * zoom, (p.y + scrollY) * zoom)

    companion object {
        const val MIN_ZOOM = 0.1
        const val MAX_ZOOM = 30.0
    }
}

/**
 * Acota la vista a una superficie fija: la imagen sobre la que se anota.
 *
 * Dibujar dentro del pin no es lo mismo que dibujar en el pin `canvas`. Sobre
 * una captura **hay un lienzo, y es la propia imagen**: alejarse hasta ver el
 * vacío alrededor no aporta nada y desorienta, porque el pin es una ventana de
 * dos dedos de ancho. Así que:
 *
 * - No se puede alejar más allá de que la imagen llene el hueco.
 * - **Sí se puede acercar todo lo que haga falta**, que es lo que permite
 *   afinar un trazo sin agrandar el pin.
 * - El paneo se frena en los bordes: la imagen nunca deja hueco a los lados.
 *
 * Con [bounds] a null no toca nada, y el lienzo sigue siendo infinito. Es lo
 * que hace que el mismo motor sirva para los dos casos sin duplicarlo.
 */
fun clampViewportToBounds(
    viewport: Viewport,
    bounds: Bounds?,
    screenWidth: Double,
    screenHeight: Double
): Viewport {
    if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return viewport
    if (screenWidth <= 0 || screenHeight <= 0) return viewport

    // El zoom al que la superficie entra justo: por debajo sobraría fondo.
    val fitZoom = minOf(screenWidth / bounds.width, screenHeight / bounds.height)
    val zoom = viewport.zoom.coerceIn(fitZoom, Viewport.MAX_ZOOM)

    // En pantalla, `screen = (scene + scroll) · zoom`. Para que el borde
    // izquierdo no se despegue de 0 hace falta `scrollX >= -bounds.x1`; para
    // que el derecho no deje hueco, `scrollX <= ancho/zoom - bounds.x2`.
    val minScrollX = screenWidth / zoom - bounds.x2
    val maxScrollX = -bounds.x1
    val minScrollY = screenHeight / zoom - bounds.y2
    val maxScrollY = -bounds.y1

    return viewport.copy(
        // Cuando la superficie es más pequeña que el hueco los dos topes se
        // cruzan; ahí se centra en vez de pegarla a un borde arbitrario.
        scrollX = if (minScrollX > maxScrollX) (minScrollX + maxScrollX) / 2
        else viewport.scrollX.coerceIn(minScrollX, maxScrollX),
        scrollY = if (minScrollY > maxScrollY) (minScrollY + maxScrollY) / 2
        else viewport.scrollY.coerceIn(minScrollY, maxScrollY),
        zoom = zoom
    )
}

/** Un archivo binario de la escena (`BinaryFileData`): la imagen de un pin. */
@Serializable
data class SceneFile(
    val id: String,
    val mimeType: String,
    /** Ruta local del archivo. El original guarda un dataURL; aquí pesa menos. */
    val path: String,
    val created: Long = 0L
)

/**
 * La escena completa.
 *
 * Es inmutable a propósito: cada operación devuelve una escena nueva, que es lo
 * que hace que el historial por deltas sea trivial de calcular y que no haga
 * falta clonar nada a mano antes de modificar.
 */
@Serializable
data class Scene(
    val elements: List<Element> = emptyList(),
    val files: Map<String, SceneFile> = emptyMap(),
    val viewport: Viewport = Viewport(),
    val style: ItemStyle = ItemStyle(),
    val backgroundColor: String = "#ffffff",
    /**
     * Cuánto mide de verdad un píxel, si se ha calibrado alguna vez.
     *
     * Va en la escena y no en cada cota porque **es una propiedad del dibujo**:
     * todas las cotas de un plano miden con la misma vara, y recalibrar tiene
     * que corregirlas todas a la vez. Con null se rotula en píxeles.
     *
     * El campo no existe en Excalidraw. No pasa nada: el JSON lo ignora al leer
     * (`ignoreUnknownKeys`) y al exportar a `.excalidraw` ni siquiera se emite,
     * porque el archivo de intercambio se arma aparte en [ExcalidrawStore].
     */
    val escala: Escala? = null,
    /**
     * Series de puntos metidos por coordenadas. Ver [TablaDeCoordenadas].
     *
     * Van en la escena y no como elementos porque **no son dibujo, son
     * referencia**: se ven, se enganchan y se editan desde su tabla, pero no se
     * arrastran de uno en uno. Lo que se traza uniéndolos sí son elementos
     * normales, y por eso se puede rehacer el trazado sin volver a teclear nada.
     */
    val tablas: List<TablaDeCoordenadas> = emptyList(),
    /**
     * El (0, 0) de las coordenadas, en la escena. **Uno para todo el dibujo.**
     *
     * Va aquí y no en cada tabla porque un eje es del plano, no de la serie: si
     * cada tabla contara desde su propio origen, dos puntos con las mismas
     * coordenadas caerían en sitios distintos del papel y compararlos dejaría de
     * significar nada. A null todavía no se ha puesto ninguno.
     */
    val origenCoordenadas: Pt? = null,
    /**
     * Si las líneas de referencia están a la vista.
     *
     * Esconderlas **no las borra**: es para mirar el dibujo limpio un momento y
     * volver. Mientras están escondidas tampoco imantan, que es lo coherente —
     * pegarse a algo que no se ve desconcierta más de lo que ayuda.
     */
    val referenciasVisibles: Boolean = true,
    /**
     * Los alfileres: los clavos que unen figuras. Ver [Alfiler].
     *
     * Van en la escena y no en los elementos porque **son una relación entre
     * varios**: metidos en uno habría que mantenerlos al día en los dos, y
     * bastaría con borrar uno para dejar al otro apuntando al vacío.
     */
    val alfileres: List<Alfiler> = emptyList()
) {

    /** Lo que se ve de verdad: sin borrar y, si están escondidas, sin referencias. */
    val visibleConReferencias: List<Element>
        get() = if (referenciasVisibles) visible else visible.filter { !it.reference }
    /** Los que se pintan: sin borrar. */
    val visible: List<Element> get() = elements.filter { !it.isDeleted }

    /**
     * La hoja, si se ha puesto una.
     *
     * Es el primer marco de la escena. Uno solo a propósito: el original admite
     * varios porque exporta cada uno por separado, pero aquí un dibujo es **un**
     * pin, y con dos marcos no habría forma de saber cuál manda.
     */
    val marco: Element? get() = visible.firstOrNull { it.isFrame }

    /**
     * Lo que se ve fuera del editor: en el pin y al exportar.
     *
     * Sin marco es todo el dibujo. Con marco, **solo lo que cae dentro**, y el
     * marco mismo no entra: es la hoja, no una raya dibujada encima.
     */
    val contenidoVisible: List<Element>
        get() {
            val m = marco ?: return visible
            val caja = getElementBounds(m)
            return visible.filter { !it.isFrame && boundsOverlap(caja, getElementBounds(it)) }
        }

    fun byId(id: String): Element? = elements.firstOrNull { it.id == id }

    fun selected(ids: Set<String>): List<Element> = elements.filter { it.id in ids }
}

// -------------------------------------------------------------------------
// Fábrica de elementos
// -------------------------------------------------------------------------

/**
 * Elemento nuevo con el estilo activo (`newElement`).
 *
 * La semilla se sortea **aquí y una sola vez**. Es el punto donde se decide que
 * el garabato de esta forma sea estable para siempre.
 */
fun newElement(
    type: ElementType,
    x: Double,
    y: Double,
    style: ItemStyle,
    width: Double = 0.0,
    height: Double = 0.0
): Element = Element(
    id = randomId(),
    type = type,
    x = x,
    y = y,
    width = width,
    height = height,
    strokeColor = style.strokeColor,
    backgroundColor = style.backgroundColor,
    fillStyle = style.fillStyle,
    strokeWidth = style.strokeWidth,
    strokeStyle = style.strokeStyle,
    roughness = style.roughness,
    opacity = style.opacity,
    // El redondeo solo se aplica a quien lo admite; ponerlo en una elipse
    // ensuciaría el JSON con un campo que nadie lee.
    roundness = if (type.acceptsRoundness) style.roundness else null,
    seed = randomSeed(),
    versionNonce = randomVersionNonce(),
    updated = System.currentTimeMillis(),
    points = if (type == ElementType.LINE || type == ElementType.ARROW ||
        type == ElementType.MEASURE || type == ElementType.FREEDRAW
    ) listOf(Pt(0.0, 0.0)) else null,
    pressures = if (type == ElementType.FREEDRAW) listOf(1.0) else null,
    elbowed = type == ElementType.ARROW && style.elbowed,
    mosaicBlur = type == ElementType.MOSAIC && style.mosaicBlur,
    startArrowhead = if (type == ElementType.ARROW) style.startArrowhead else null,
    endArrowhead = if (type == ElementType.ARROW) style.endArrowhead else null,
    text = if (type == ElementType.TEXT) "" else null,
    // La cota también lleva letra: su rótulo se calcula al pintar, pero con qué
    // tamaño y con qué familia sale se elige como en cualquier texto.
    fontSize = if (type == ElementType.TEXT || type == ElementType.MEASURE) {
        style.fontSize
    } else null,
    fontFamily = if (type == ElementType.TEXT || type == ElementType.MEASURE) {
        style.fontFamily
    } else null,
    textAlign = if (type == ElementType.TEXT) style.textAlign else null,
    verticalAlign = if (type == ElementType.TEXT) style.verticalAlign else null
)

private val ElementType.acceptsRoundness: Boolean
    get() = this == ElementType.RECTANGLE || this == ElementType.DIAMOND ||
        this == ElementType.LINE || this == ElementType.ARROW

/** Elemento de imagen que ocupa exactamente el tamaño dado. */
fun newImageElement(
    fileId: String, x: Double, y: Double, width: Double, height: Double,
    style: ItemStyle = ItemStyle()
): Element = newElement(ElementType.IMAGE, x, y, style, width, height)
    .copy(fileId = fileId, backgroundColor = Element.TRANSPARENT)

/** Añade un punto al elemento de puntos que se está trazando. */
fun Element.withPoint(scenePoint: Pt, pressure: Double = 1.0): Element {
    val rel = Pt(scenePoint.x - x, scenePoint.y - y)
    val pts = (points ?: emptyList()) + rel
    val bounds = boundsOfPoints(pts)
    return copy(
        points = pts,
        pressures = if (type == ElementType.FREEDRAW) (pressures ?: emptyList()) + pressure else pressures,
        // `width`/`height` se mantienen al día para que las cajas y el picado
        // funcionen mientras se dibuja, no solo al soltar.
        width = bounds.width,
        height = bounds.height
    )
}

/**
 * Recoloca **un punto** de un elemento de puntos.
 *
 * Es lo que hace arrastrar la punta de una flecha ya dibujada. Lo delicado no
 * es mover el punto, es que los demás no se muevan: los puntos se guardan
 * relativos a `x`/`y`, así que tocar el primero obliga a recolocar el origen del
 * elemento **y** a compensar todos los demás. Sin eso, mover la cola de una
 * flecha arrastraba la punta con ella.
 */
fun Element.withPointMovedTo(index: Int, scenePoint: Pt): Element {
    val pts = points ?: return this
    if (index !in pts.indices) return this

    return conPuntosAbsolutos(
        pts.mapIndexed { i, p -> if (i == index) scenePoint else Pt(x + p.x, y + p.y) }
    )
}

/**
 * Lleva el punto [index] a [enElMundo], **contando la inclinación**.
 *
 * Los puntos se guardan sin girar y el ángulo va aparte, así que mandar
 * directamente una coordenada de la escena a [withPointMovedTo] coloca el punto
 * donde estaría si la figura no estuviera girada — o sea, en otro sitio. Al
 * arrastrar el vértice de una raya inclinada, el vértice se iba por su cuenta.
 */
fun Element.conPuntoEnElMundo(index: Int, enElMundo: Pt): Element {
    if (angle == 0.0) return withPointMovedTo(index, enElMundo)

    // **Y se afina, porque mover el punto mueve el centro.** La figura gira
    // alrededor del centro de su caja, y al mover un punto la caja cambia: el
    // centro con el que se deshace el giro deja de ser el que se usará al
    // pintar, así que el punto acaba unos píxeles al lado. Poco, pero suficiente
    // para que un clavo se desfase — y a cada arrastre, un poco más.
    //
    // Se resuelve mirando dónde acabó de verdad y corrigiendo. No hay solución
    // directa —el centro sale de un mínimo y un máximo, que no son derivables—
    // pero cada pasada deja el error en `sen(ángulo/2)` de lo que era, así que
    // converge rápido y siempre: con dieciséis, la peor inclinación posible baja
    // de una millonésima de píxel. Se sale en cuanto no queda error.
    fun centro(e: Element) = getElementAbsoluteCoords(e).let { Pt(it.cx, it.cy) }
    var resultado = withPointMovedTo(index, pointRotateRads(enElMundo, centro(this), -angle))
    repeat(16) {
        val donde = absolutePoints(resultado).getOrNull(index) ?: return resultado
        val actual = pointRotateRads(donde, centro(resultado), angle)
        val ex = enElMundo.x - actual.x
        val ey = enElMundo.y - actual.y
        if (kotlin.math.hypot(ex, ey) < 1e-7) return resultado
        // El error se mide en la escena y se corrige en los puntos, que van sin
        // girar: hay que deshacerle el giro **al vector**, no al punto.
        val corr = pointRotateRads(Pt(ex, ey), Pt(0.0, 0.0), -angle)
        resultado = resultado.withPointMovedTo(index, Pt(donde.x + corr.x, donde.y + corr.y))
    }
    return resultado
}

/**
 * Mete un punto **después** del que ocupa [tras], en [scenePoint].
 *
 * Es lo que hace arrastrar el tirador del medio de un tramo: la línea gana un
 * doblez ahí. Devuelve el elemento sin tocar si el índice no cuadra.
 */
fun Element.withPointInserted(tras: Int, scenePoint: Pt): Element {
    val pts = points ?: return this
    if (tras < 0 || tras >= pts.size) return this
    val absolutos = pts.map { Pt(x + it.x, y + it.y) }.toMutableList()
    absolutos.add(tras + 1, scenePoint)
    return conPuntosAbsolutos(absolutos)
}

/**
 * Quita el punto [index].
 *
 * **Nunca por debajo de dos puntos**: una línea de uno solo no es una línea, es
 * un elemento invisible que sigue robando toques al picar.
 */
fun Element.withPointRemoved(index: Int): Element {
    val pts = points ?: return this
    if (pts.size <= 2 || index !in pts.indices) return this
    val absolutos = pts.map { Pt(x + it.x, y + it.y) }.toMutableList()
    absolutos.removeAt(index)
    return conPuntosAbsolutos(absolutos)
}

/**
 * Rehace el elemento a partir de sus puntos en coordenadas de escena.
 *
 * El origen vuelve a ser el primer punto y los demás se guardan relativos a él,
 * que es la forma en que Excalidraw guarda un elemento de puntos. Centralizarlo
 * es lo que evita el error de mover uno y arrastrar los demás con él.
 */
private fun Element.conPuntosAbsolutos(absolutos: List<Pt>): Element {
    if (absolutos.isEmpty()) return this
    val origen = absolutos.first()
    val relativos = absolutos.map { Pt(it.x - origen.x, it.y - origen.y) }
    val caja = boundsOfPoints(relativos)
    return copy(
        x = origen.x,
        y = origen.y,
        points = relativos,
        width = caja.width,
        height = caja.height
    )
}

/**
 * ¿Es [index] uno de los dos extremos de [element]?
 *
 * De aquí depende qué se ancla y qué no: **solo las dos puntas**. Un punto
 * intermedio es un doblez del recorrido, y engancharlo a una forma haría que la
 * línea se retorciera sola al mover esa forma.
 */
fun esExtremo(element: Element, index: Int): Boolean {
    val pts = element.points ?: return false
    return index == 0 || index == pts.lastIndex
}

/** Mueve el último punto: la previsualización mientras se arrastra. */
fun Element.withLastPointAt(scenePoint: Pt): Element {
    val pts = points ?: return this
    if (pts.isEmpty()) return this
    val updated = pts.toMutableList()
    updated[updated.size - 1] = Pt(scenePoint.x - x, scenePoint.y - y)
    val bounds = boundsOfPoints(updated)
    return copy(points = updated, width = bounds.width, height = bounds.height)
}

// -------------------------------------------------------------------------
// Acciones sobre la selección
// -------------------------------------------------------------------------

/**
 * Aplica un cambio de estilo (`actionProperties`).
 *
 * Devuelve también el estilo actualizado, porque cambiar un color debe afectar
 * a lo seleccionado **y** a lo siguiente que se dibuje.
 */
fun applyStyle(
    scene: Scene, selectedIds: Set<String>, change: (ItemStyle) -> ItemStyle,
    toElement: (Element) -> Element
): Scene {
    val newStyle = change(scene.style)
    val newElements = scene.elements.map {
        if (it.id in selectedIds && !it.locked) toElement(it).touched() else it
    }
    return scene.copy(elements = newElements, style = newStyle)
}

/**
 * El estilo nuevo llevado a un elemento, en lo que le aplique.
 *
 * Existe para que no haya tres versiones de esto. La barra vive en tres sitios
 * —el pin, la captura y el editor— y cada uno traía su propia lambda de «qué
 * campos copio»: la del pin llevaba color y grosor, la del editor además la
 * letra, y el resultado era que cambiar el tamaño del número de una cota
 * funcionaba en un sitio y en otro no.
 *
 * El texto se queda fuera a propósito: cambiarle la letra obliga a **volver a
 * medir su caja**, y eso necesita Android. Lo hace quien tenga contexto. La
 * cota no: su rótulo se calcula al pintar y no guarda caja ninguna.
 */
fun estiloAplicado(e: Element, nuevo: ItemStyle): Element {
    val base = e.copy(strokeColor = nuevo.strokeColor, strokeWidth = nuevo.strokeWidth)
    return if (e.isMeasure) {
        base.copy(fontSize = nuevo.fontSize, fontFamily = nuevo.fontFamily)
    } else base
}

/**
 * Borra la selección (`actionDeleteSelected`).
 *
 * Marca `isDeleted` en vez de quitar de la lista: es lo que permite que
 * deshacer devuelva el elemento **en su sitio**, y no al final del montón.
 */
fun deleteSelected(elements: List<Element>, selectedIds: Set<String>): List<Element> =
    elements.map {
        if (it.id in selectedIds && !it.locked) it.copy(isDeleted = true).touched() else it
    }

/** Bloquea o desbloquea (`actionElementLock`). */
fun toggleLock(elements: List<Element>, selectedIds: Set<String>): List<Element> {
    val selected = elements.filter { it.id in selectedIds }
    // Si hay mezcla, se bloquea todo: es lo menos sorprendente.
    val target = selected.any { !it.locked }
    return elements.map {
        if (it.id in selectedIds) it.copy(locked = target).touched() else it
    }
}

/**
 * El borrador: marca lo que toca el trazo (`eraser`).
 *
 * [alcanza] decide qué puede borrar. Lo usa el modo guía: fuera de él, el
 * borrador **no puede llevarse una guía por delante** —el andamio está ahí
 * precisamente para pasarle el lápiz por encima, y perderlo a mitad de faena es
 * de las cosas que más rabia dan—; dentro de él, solo borra guías, y así se
 * limpia el azul sin miedo a rascar el dibujo de debajo.
 */
fun eraseAt(
    elements: List<Element>, p: Pt, threshold: Double = DEFAULT_HIT_THRESHOLD,
    alcanza: (Element) -> Boolean = { true }
): List<Element> {
    val hit = getElementAtPosition(elements.filter(alcanza), p, threshold) ?: return elements
    // Borrar un miembro borra el grupo entero, igual que seleccionarlo.
    val victims = getElementsInGroupOf(elements, hit).map { it.id }.toSet()
    return elements.map {
        // Lo bloqueado no se borra, igual que en `deleteSelected`. Es lo que
        // impide que el borrador se lleve por delante la foto sobre la que se
        // está anotando al pasar por un hueco donde no había nada dibujado.
        if (it.id in victims && !it.locked) it.copy(isDeleted = true).touched() else it
    }
}

/** Quita de verdad lo marcado como borrado. Solo al guardar, no al editar. */
fun purgeDeleted(elements: List<Element>): List<Element> = elements.filter { !it.isDeleted }

/**
 * Ajusta el viewport para que quepa todo (`scrollToContent` / `fitToContent`).
 */
fun fitToContent(
    elements: List<Element>, screenWidth: Double, screenHeight: Double, padding: Double = 32.0
): Viewport {
    val visible = elements.filter { !it.isDeleted }
    if (visible.isEmpty()) return Viewport()
    val b = getCommonBounds(visible)
    if (b.width <= 0 || b.height <= 0) return Viewport()

    val zoom = minOf(
        (screenWidth - padding * 2) / b.width,
        (screenHeight - padding * 2) / b.height
    ).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)

    return Viewport(
        scrollX = screenWidth / (2 * zoom) - b.midX,
        scrollY = screenHeight / (2 * zoom) - b.midY,
        zoom = zoom
    )
}

/** Los elementos que caen dentro de la pantalla (`getVisibleCanvasElements`). */
fun getVisibleElements(
    elements: List<Element>, viewport: Viewport, screenWidth: Double, screenHeight: Double
): List<Element> {
    val topLeft = viewport.toScene(0.0, 0.0)
    val bottomRight = viewport.toScene(screenWidth, screenHeight)
    val view = Bounds(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    return elements.filter { !it.isDeleted && boundsOverlap(view, getElementBounds(it)) }
}
