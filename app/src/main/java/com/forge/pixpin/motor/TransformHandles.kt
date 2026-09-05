package com.forge.pixpin.motor

import kotlin.math.abs

/**
 * Los tiradores de la selección. Port de `transformHandles.ts`.
 *
 * Ocho para redimensionar (cuatro esquinas y cuatro lados) más uno de rotación
 * separado por encima. Se calculan **en coordenadas de escena pero con tamaño
 * de pantalla**: por eso todo se divide por el zoom, para que el tirador se vea
 * siempre igual de grande da igual el aumento.
 */

enum class HandleType {
    NW, NE, SW, SE, N, E, S, W, ROTATION,

    /**
     * Las **puntas** de una flecha o una línea.
     *
     * Una raya no tiene esquinas que estirar: tiene dos extremos, y lo que se
     * quiere hacer con ella es mover uno. Metida en una caja de redimensionar,
     * arrastrar una esquina escalaba los dos puntos a la vez y no había forma
     * de recolocar solo la punta — que es justo lo único que se hace con una
     * flecha después de dibujarla. Es como lo resuelve Excalidraw.
     */
    POINT_START, POINT_END,

    /** Un punto intermedio de una línea de varios tramos. */
    POINT_MID,

    /**
     * El **medio de un tramo**: no es un punto todavía.
     *
     * Arrastrarlo crea uno ahí y sigue moviéndolo, que es como se dobla una
     * línea en el original. Sale más pequeño y hueco para que se distinga de un
     * punto de verdad: si se vieran iguales, no habría forma de saber cuáles se
     * pueden borrar.
     */
    POINT_ADD,

    /**
     * Los del **sólido**, que no se estira por una caja como los demás.
     *
     * Una caja en isométrica no cabe en un rectángulo de pantalla: su ancho y su fondo
     * son dos ejes del suelo que se dibujan en diagonal, y su altura sube recta. Con los
     * ocho tiradores de siempre no había forma de decir cuál de los tres se quiere
     * cambiar —un arrastre en diagonal vale para dos— y encima salían colocados sobre la
     * huella sin proyectar, o sea lejos de lo dibujado.
     *
     * Estos van sobre las esquinas de verdad y cada uno toca **una cosa**: la esquina de
     * enfrente cambia la huella entera, las dos de los lados un eje cada una, y el de
     * arriba del todo la altura. Es el mismo gesto con el que se dibujó, que es lo que
     * hace que se entiendan sin explicarlos.
     */
    SOLIDO_HUELLA, SOLIDO_ANCHO, SOLIDO_FONDO, SOLIDO_ALTURA,

    /**
     * Los dos que aparecieron con la cota y el giro.
     *
     * [SOLIDO_COTA] sube la caja entera sin cambiar lo que mide —es lo que permite
     * apoyarla encima de otra— y va en el pie de la misma arista vertical en cuya punta
     * está [SOLIDO_ALTURA]: abajo se levanta, arriba se estira. [SOLIDO_GIRO] va apartado
     * de la planta y la hace girar sobre el suelo, no sobre la pantalla.
     */
    SOLIDO_COTA, SOLIDO_GIRO
}

/** Los del sólido: ni estiran una caja ni mueven un punto de un recorrido. */
val HandleType.esDeSolido: Boolean
    get() = this == HandleType.SOLIDO_HUELLA || this == HandleType.SOLIDO_ANCHO ||
        this == HandleType.SOLIDO_FONDO || this == HandleType.SOLIDO_ALTURA ||
        this == HandleType.SOLIDO_COTA || this == HandleType.SOLIDO_GIRO

/** Los que mueven un punto suelto en vez de estirar una caja. */
val HandleType.esPunto: Boolean
    get() = this == HandleType.POINT_START || this == HandleType.POINT_END ||
        this == HandleType.POINT_MID || this == HandleType.POINT_ADD

/**
 * Los extremos, que son **los únicos que se anclan a una forma**.
 *
 * Un punto intermedio es un doblez del recorrido, no un destino: engancharlo a
 * una caja haría que la línea se retorciera sola al mover esa caja, sin que
 * nadie hubiera dicho que ese doblez tuviera nada que ver con ella.
 */
val HandleType.esExtremo: Boolean
    get() = this == HandleType.POINT_START || this == HandleType.POINT_END

/** Un tirador: rectángulo `[x, y, ancho, alto]` en coordenadas de escena. */
data class TransformHandle(
    val type: HandleType,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    /**
     * Qué punto mueve, en los tiradores de punto.
     *
     * En [HandleType.POINT_ADD] es el punto **anterior** al tramo: al arrastrarlo
     * se inserta uno nuevo justo detrás. Null en los de redimensionar, que no
     * tienen punto que señalar.
     */
    val indice: Int? = null
) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
}

/**
 * Tamaño del tirador según con qué se apunte.
 *
 * Los valores son los del original. PixPin usa [TOUCH] por defecto: 28px
 * frente a los 8 del ratón, porque un tirador de 8px con el dedo es
 * inalcanzable.
 */
object HandleSize {
    const val MOUSE = 8.0
    const val PEN = 16.0
    const val TOUCH = 28.0
}

private const val ROTATION_RESIZE_HANDLE_GAP = 16.0
private const val DEFAULT_TRANSFORM_HANDLE_SPACING = 2.0

/**
 * Qué tiradores omitir.
 *
 * Con varios elementos o con una línea diagonal, los del lado sobran o caen
 * justo encima del trazo; el original los quita para no estorbar.
 */
data class OmitSides(
    val n: Boolean = false, val e: Boolean = false,
    val s: Boolean = false, val w: Boolean = false,
    val nw: Boolean = false, val ne: Boolean = false,
    val sw: Boolean = false, val se: Boolean = false,
    val rotation: Boolean = false
) {
    companion object {
        /** Por defecto Excalidraw solo muestra las esquinas. */
        val ONLY_CORNERS = OmitSides(n = true, e = true, s = true, w = true)
        val MULTIPLE_ELEMENTS = ONLY_CORNERS
        val NONE = OmitSides()
    }
}

/**
 * Tiradores para unas coordenadas dadas (`getTransformHandlesFromCoords`).
 *
 * La aritmética de los desplazamientos se copia tal cual: `centeringOffset`
 * compensa que el tirador se dibuja por su esquina y no por su centro, y
 * `dashedLineMargin` lo separa de la línea discontinua de la selección.
 */
fun getTransformHandlesFromCoords(
    coords: AbsoluteCoords,
    angle: Double,
    zoom: Double,
    handleSize: Double = HandleSize.TOUCH,
    omit: OmitSides = OmitSides.ONLY_CORNERS,
    margin: Double = 4.0,
    spacing: Double = DEFAULT_TRANSFORM_HANDLE_SPACING
): List<TransformHandle> {
    val (x1, y1, x2, y2, cx, cy) = coords

    val handleWidth = handleSize / zoom
    val handleHeight = handleSize / zoom
    val handleMarginX = handleSize / zoom
    val handleMarginY = handleSize / zoom

    val width = x2 - x1
    val height = y2 - y1
    val dashedLineMargin = margin / zoom
    val centeringOffset = (handleSize - spacing * 2) / (2 * zoom)

    val out = mutableListOf<TransformHandle>()

    fun handle(type: HandleType, hx: Double, hy: Double) {
        // El tirador se gira alrededor del centro del elemento: con la forma
        // inclinada los tiradores acompañan en vez de quedarse rectos.
        val rotated = pointRotateRads(
            Pt(hx + handleWidth / 2, hy + handleHeight / 2), Pt(cx, cy), angle
        )
        out += TransformHandle(
            type,
            rotated.x - handleWidth / 2,
            rotated.y - handleHeight / 2,
            handleWidth,
            handleHeight
        )
    }

    if (!omit.nw) handle(
        HandleType.NW,
        x1 - dashedLineMargin - handleMarginX + centeringOffset,
        y1 - dashedLineMargin - handleMarginY + centeringOffset
    )
    if (!omit.ne) handle(
        HandleType.NE,
        x2 + dashedLineMargin - centeringOffset,
        y1 - dashedLineMargin - handleMarginY + centeringOffset
    )
    if (!omit.sw) handle(
        HandleType.SW,
        x1 - dashedLineMargin - handleMarginX + centeringOffset,
        y2 + dashedLineMargin - centeringOffset
    )
    if (!omit.se) handle(
        HandleType.SE,
        x2 + dashedLineMargin - centeringOffset,
        y2 + dashedLineMargin - centeringOffset
    )
    if (!omit.rotation) handle(
        HandleType.ROTATION,
        x1 + width / 2 - handleWidth / 2,
        y1 - dashedLineMargin - handleMarginY + centeringOffset -
            ROTATION_RESIZE_HANDLE_GAP / zoom
    )

    // Los tiradores de lado solo aparecen si hay sitio: en una forma pequeña
    // se amontonarían con los de esquina y no se podría acertar a ninguno.
    val minimumSizeForEightHandles = (5 * handleSize) / zoom
    if (abs(width) > minimumSizeForEightHandles) {
        if (!omit.n) handle(
            HandleType.N,
            x1 + width / 2 - handleWidth / 2,
            y1 - dashedLineMargin - handleMarginY + centeringOffset
        )
        if (!omit.s) handle(
            HandleType.S,
            x1 + width / 2 - handleWidth / 2,
            y2 + dashedLineMargin - centeringOffset
        )
    }
    if (abs(height) > minimumSizeForEightHandles) {
        if (!omit.w) handle(
            HandleType.W,
            x1 - dashedLineMargin - handleMarginX + centeringOffset,
            y1 + height / 2 - handleHeight / 2
        )
        if (!omit.e) handle(
            HandleType.E,
            x2 + dashedLineMargin - centeringOffset,
            y1 + height / 2 - handleHeight / 2
        )
    }
    return out
}

/** Tiradores de un elemento suelto (`getTransformHandles`). */
fun getTransformHandles(
    element: Element,
    zoom: Double,
    handleSize: Double = HandleSize.TOUCH,
    omit: OmitSides = OmitSides.ONLY_CORNERS
): List<TransformHandle> {
    // Un elemento bloqueado no se toca: tampoco enseña por dónde cogerlo.
    if (element.locked) return emptyList()
    return getTransformHandlesFromCoords(
        getElementAbsoluteCoords(element), element.angle, zoom, handleSize, omit
    )
}

/**
 * Los tiradores de una raya: **sus dos puntas**.
 *
 * Van sobre los extremos de verdad y no sobre las esquinas de su caja, que en
 * una diagonal caen donde no hay nada dibujado. Se giran con el elemento, igual
 * que los de redimensionar.
 */
fun getLinearHandles(
    element: Element, zoom: Double, handleSize: Double = HandleSize.TOUCH
): List<TransformHandle> {
    if (element.locked) return emptyList()
    val pts = absolutePoints(element)
    if (pts.size < 2) return emptyList()

    val c = getElementAbsoluteCoords(element)
    val centro = Pt(c.cx, c.cy)
    val lado = handleSize / zoom

    fun tirador(type: HandleType, p: Pt, indice: Int, escala: Double = 1.0): TransformHandle {
        val girado = pointRotateRads(p, centro, element.angle)
        val medida = lado * escala
        return TransformHandle(
            type, girado.x - medida / 2, girado.y - medida / 2, medida, medida, indice
        )
    }

    val out = mutableListOf<TransformHandle>()
    pts.forEachIndexed { i, p ->
        val tipo = when (i) {
            0 -> HandleType.POINT_START
            pts.lastIndex -> HandleType.POINT_END
            else -> HandleType.POINT_MID
        }
        out += tirador(tipo, p, i)
    }

    // Los del medio de cada tramo, para doblar la línea. En la flecha de codos
    // no salen: allí el recorrido lo calcula el motor a partir de los extremos,
    // así que un punto puesto a mano desaparecería en el siguiente recálculo.
    if (!element.elbowed) {
        for (i in 0 until pts.lastIndex) {
            val medio = Pt((pts[i].x + pts[i + 1].x) / 2, (pts[i].y + pts[i + 1].y) / 2)
            // Solo si el tramo da de sí: en uno corto, el tirador de añadir se
            // amontonaría con los de sus dos puntas y no se acertaría a ninguno.
            val largo = kotlin.math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
            if (largo > lado * 2.5) {
                out += tirador(HandleType.POINT_ADD, medio, i, escala = ADD_HANDLE_RATIO)
            }
        }
    }
    return out
}

/** Cuánto más pequeño es el tirador de «añadir punto» que uno de verdad. */
const val ADD_HANDLE_RATIO = 0.6

/** Tiradores de una selección múltiple: solo esquinas, y sin rotar. */
fun getSelectionTransformHandles(
    elements: List<Element>,
    zoom: Double,
    handleSize: Double = HandleSize.TOUCH,
    /** Desde dónde se mira: los tiradores de un sólido van donde caiga proyectado. */
    vista: Vista = Vista.CERO,
    /**
     * Dónde hay clavos. **Los tiradores que caen encima de uno no se pintan.**
     *
     * Un clavo y un tirador en el mismo sitio son dos cosas distintas con dos
     * reglas distintas peleándose por el mismo toque, y ganaba el tirador —que
     * es más gordo y va delante—: acercabas el dedo al clavo, agarrabas el
     * tirador y la estructura se movía entera en vez de articularse. Donde hay
     * clavo manda el clavo, y el tirador desaparece: **un punto, una ley**.
     */
    alfileres: List<Pt> = emptyList()
): List<TransformHandle> = handlesSinAlfileres(elements, zoom, handleSize, vista, alfileres)

private fun handlesSinAlfileres(
    elements: List<Element>,
    zoom: Double,
    handleSize: Double,
    vista: Vista,
    alfileres: List<Pt>
): List<TransformHandle> {
    val todos = handlesDe(elements, zoom, handleSize, vista)
    if (alfileres.isEmpty()) return todos
    val margen = handleSize / zoom.coerceAtLeast(0.0001)
    return todos.filterNot { h ->
        h.type.esPunto && alfileres.any {
            abs(it.x - h.centerX) <= margen && abs(it.y - h.centerY) <= margen
        }
    }
}

private fun handlesDe(
    elements: List<Element>, zoom: Double, handleSize: Double, vista: Vista
): List<TransformHandle> {
    if (elements.isEmpty()) return emptyList()

    // **Un punto no se estira: no tiene tamaño que estirar.**
    //
    // Su caja mide cero, así que los ocho tiradores nacían todos amontonados
    // encima de él. Arrastrarlo agarraba uno de esos tiradores en vez del propio
    // punto, y lo que salía era un redimensionado de una caja de tamaño cero:
    // el punto se iba a coordenadas absurdas. Sin tiradores, el dedo lo coge a
    // él, que es lo único que se puede hacer con un punto.
    if (elements.size == 1 && elements.first().type == ElementType.PUNTO) {
        return emptyList()
    }

    // Una raya sola se coge por las puntas; en grupo, no: ahí lo que se mueve
    // es el conjunto, y unas puntas sueltas entre varias cajas confundirían.
    if (elements.size == 1 && elements.first().isLinear) {
        return getLinearHandles(elements.first(), zoom, handleSize)
    }
    // **El plano enseña los ocho.** Los del medio no son un adorno en él: son
    // los que extienden el intervalo en vez de escalarlo, y sin ellos la mitad
    // de lo que sabe hacer no tendría por dónde tocarse. Ver [Plano].
    // La caja tiene los suyos, uno por cada cosa que se le puede cambiar. Ver
    // [HandleType.SOLIDO_HUELLA] y [tiradoresDelSolido].
    if (elements.size == 1 && elements.first().isSolido) {
        val e = elements.first()
        if (e.locked) return emptyList()
        val lado = handleSize / zoom
        return tiradoresDelSolido(e, vista).map { (tipo, p) ->
            TransformHandle(tipo, p.x - lado / 2, p.y - lado / 2, lado, lado)
        }
    }
    // **Los tiradores del arco van alrededor de lo que pinta.** Su caja es la del óvalo
    // entero, así que en un arco recortado salían flotando lejísimos del trazo, en medio
    // de la nada. Se le pasa la caja del trozo pero **con el centro del óvalo**: es el
    // punto sobre el que gira el elemento, y el que tiene que girar los tiradores con él.
    if (elements.size == 1 && elements.first().type == ElementType.ARC) {
        val e = elements.first()
        if (e.locked) return emptyList()
        val caja = cajaDelArco(e)
        val c = getElementAbsoluteCoords(e)
        return getTransformHandlesFromCoords(
            AbsoluteCoords(caja.x1, caja.y1, caja.x2, caja.y2, c.cx, c.cy),
            e.angle, zoom, handleSize
        )
    }
    if (elements.size == 1 && elements.first().esInstrumento) {
        return getTransformHandles(elements.first(), zoom, handleSize, OmitSides.NONE)
    }
    if (elements.size == 1) return getTransformHandles(elements.first(), zoom, handleSize)
    // La misma caja que pinta el recuadro y que estira el bloque. Ver [envolturaVisible].
    val b = envolturaComun(elements, vista)
    // **Los ocho, también con varios elementos.** Solo salían las esquinas, y con
    // ellas la selección únicamente crece en proporción: una figura estampada de
    // la lista no se podía estrechar para meterla en un hueco. Los del medio son
    // los que aplastan un eje. Ver [resizeMultipleElements].
    return getTransformHandlesFromCoords(
        AbsoluteCoords(b.x1, b.y1, b.x2, b.y2, b.midX, b.midY),
        angle = 0.0,
        zoom = zoom,
        handleSize = handleSize,
        omit = OmitSides.NONE
    )
}

/**
 * ¿Sobre qué tirador está el dedo? Null si sobre ninguno.
 *
 * Devuelve el tirador entero y no solo su tipo porque los de punto llevan
 * además **qué punto** mueven, y con varios intermedios el tipo ya no basta
 * para saberlo.
 *
 * Los de punto ganan a los de añadir cuando se solapan: acertar un punto que ya
 * existe es lo que se intenta casi siempre, y crear uno sin querer obliga a
 * deshacer.
 */
fun hitHandle(
    handles: List<TransformHandle>, p: Pt, angle: Double, center: Pt
): TransformHandle? {
    fun toca(h: TransformHandle): Boolean {
        // Los tiradores ya vienen girados, así que para compararlos con un
        // rectángulo recto se desgira el punto igual que en el picado.
        val local = pointRotateRads(p, center, -angle)
        val hc = pointRotateRads(Pt(h.centerX, h.centerY), center, -angle)
        return abs(local.x - hc.x) <= h.width / 2 && abs(local.y - hc.y) <= h.height / 2
    }
    return handles.firstOrNull { it.type != HandleType.POINT_ADD && toca(it) }
        ?: handles.firstOrNull { toca(it) }
}
