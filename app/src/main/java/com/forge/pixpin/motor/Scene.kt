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
 * [MOSAIC], [LUPA] y [SERIAL] vienen del motor de anotación viejo, donde
 * eran lo más útil sobre una captura; y [MEASURE] y [SCALE] vienen del croquis,
 * que era una aplicación entera dedicada a medir. Ver [ElementType] y [Escala].
 */
enum class Tool {
    SELECTION, LASSO, HAND,
    RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT, IMAGE,

    /**
     * **La flecha libre**: se traza a pulso como el lápiz y acaba en punta.
     *
     * La flecha de siempre va de un punto a otro en línea recta, y eso sirve para señalar
     * pero no para rodear: lo que uno hace a mano al anotar es una raya que da la vuelta a
     * algo y termina apuntándolo. Por dentro **es una flecha** —el mismo tipo de elemento,
     * con su punta— con todos los puntos por los que pasó la mano en vez de dos, así que se
     * edita, se colorea, se exporta y se le cambia la punta por el mismo camino que a las
     * demás. Ver [DrawController.pointerDown].
     */
    FLECHA_LIBRE,
    ERASER,
    HIGHLIGHTER, MOSAIC, LUPA, SPOTLIGHT, SERIAL, FRAME,


    /** La cota: se arrastra sobre lo que se quiere medir y se rotula sola. */
    MEASURE,

    /**
     * Escalar: se arrastra sobre algo de medida **conocida** y se dicta cuánto
     * mide. Es lo que le da unidades a todas las cotas de la escena.
     */
    SCALE,

    /**
     * **La bolita**: se pasa por encima de lo que se quiera y va entrando en la selección.
     *
     * No hay rectángulo que encuadrar ni tecla que mantener. Se pasa por algo y entra; se
     * vuelve a pasar por algo que ya estaba dentro y sale. Es la forma de seleccionar que
     * no obliga a pensar en la selección: sirve igual para coger cuatro figuras de un
     * barrido que para quitar la que sobra, con el mismo gesto y sin cambiar de modo.
     *
     * Es la misma que la del croquis en el espacio, y a propósito: allí nació porque con la
     * vista girada un recuadro de selección no encuadra lo que uno cree, y aquí hace falta
     * por otra razón —con el dedo, un recuadro sobre un dibujo apretado coge de más y coge
     * de menos, y hay que repetirlo—.
     */
    BOLITA,

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
    NUDO,

    /**
     * Punto etiquetado: **A, B, C sobre el dibujo**.
     *
     * Un toque —imantado a la intersección o al vértice más cercano— y ahí
     * queda el punto con su letra, que sale sola siguiendo la serie. Es la
     * herramienta de las matemáticas: un croquis de geometría se explica
     * nombrando los puntos, no señalándolos con el dedo. Ver [Puntos].
     */
    PUNTO,

    /**
     * La caja en volumen: **el boceto 3D del motor**.
     *
     * Se arrastra la huella por el suelo isométrico y luego se levanta. Son dos
     * fases y no una, y el porqué está en [DrawController]: en isométrica un
     * arrastre vertical puro no se distingue de una diagonal por el suelo, así
     * que separarlas es la única forma de que la ambigüedad no exista. Ver
     * [Solido] y [ElementType.SOLIDO].
     */
    SOLIDO,

    /**
     * El **cronograma**: se arrastra la caja y nace un plan con tres filas dentro.
     *
     * Nace con filas y no vacío a propósito: una figura que aparece en blanco obliga a
     * descubrir dónde se le añaden cosas antes de que enseñe nada, y lo que uno quiere al
     * poner un cronograma es ver ya la rejilla y empezar a arrastrar barras.
     * Ver [Cronograma].
     */
    CRONOGRAMA,

    /**
     * **Levantar**: se toca una figura cerrada y se convierte en volumen.
     *
     * Estaba escondida en el panel de acciones, que es donde se guarda lo que se toca una
     * vez al mes — y esto es la puerta de entrada al 3D. Como herramienta se ve, y sobre
     * todo se usa igual que las demás: se elige y se toca lo que se quiere levantar.
     */
    EXTRUIR,

    /**
     * **Torno**: se toca la figura y después el eje, y sale el cuerpo de revolución.
     *
     * En dos tiempos y no marcando las dos cosas a la vez: hay que decir **cuál es cuál**,
     * y con una selección normal no hay forma —la figura y el eje son dos elementos y el
     * orden se pierde—. Tocando primero lo que se tornea y después la raya, no hay nada
     * que explicar. Ver [DrawController.figuraATornear].
     */
    REVOLUCION;

    /** Las que crean una forma con caja al arrastrar. */
    val isShape: Boolean
        get() = this == RECTANGLE || this == DIAMOND || this == ELLIPSE ||
            this == MOSAIC || this == LUPA || this == FRAME ||
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
    /** De qué está hecha la tinta: lisa, encendida o con grano. Ver [MaterialDeTinta]. */
    val material: MaterialDeTinta = MaterialDeTinta.LISA,
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
    val verticalAlign: VerticalAlign = VerticalAlign.TOP,
    /** Negrita, cursiva y tachado del texto. Ver [Propiedad.ESTILO_DE_TEXTO]. */
    val negrita: Boolean = false,
    val cursiva: Boolean = false,
    val tachado: Boolean = false,
    /** Lo que agranda la lupa, y de qué forma es. Ver [Propiedad.LUPA]. */
    val aumento: Double = AUMENTO_POR_DEFECTO,
    val lupaRedonda: Boolean = true,
    /** Con qué se señala de dónde sale lo que se ve. Ver [GuiaDeLupa]. */
    val guia: GuiaDeLupa = GuiaDeLupa.FLECHA,
    /** Cuánto oscurece un foco lo de fuera, de 10 a 90 por ciento. */
    val oscurecer: Int = OSCURECER_POR_DEFECTO,
    /** Qué parte del marco de un foco ocupa su zona iluminada. Ver [conZona]. */
    val zona: Double = ZONA_POR_DEFECTO,
    /** El trazo a mano sale firme, sin adelgazar. Ver [Element.presionFirme]. */
    val presionFirme: Boolean = false,
    /**
     * Qué pieza es un volumen y si va macizo o de alambre. Ver [Propiedad.VOLUMEN].
     *
     * Estaban solo en el panel de acciones —detrás de un botón que hay que abrir— y ahí
     * no los encontraba nadie: son las dos decisiones que más se tocan de una caja y
     * estaban donde se guarda lo que se toca una vez. En el estilo van al lateral, que es
     * donde uno mira, y además se quedan cargadas para la siguiente pieza.
     */
    val esqueleto: Boolean = false
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

        /**
         * El grosor de lápiz que corresponde a [ancho] en la escala de formas.
         *
         * **Es la mitad, y sin escalones.** Las dos listas de arriba son una la
         * mitad de la otra en las cuatro posiciones, así que dividir entre dos
         * da exactamente los mismos cuatro valores **y además todos los de en
         * medio**.
         *
         * Antes se buscaba la posición en [STROKE_WIDTHS] y se devolvía la de
         * [FREEDRAW_STROKE_WIDTHS], que era lo correcto cuando el grosor se
         * elegía entre cuatro botones. Con el deslizador —que da cualquier
         * grosor de 0,5 a 20— eso dejaba el lápiz en cuatro escalones, y de 4
         * en adelante, más de la mitad del recorrido, salía siempre lo mismo:
         * el mando se movía y el trazo no cambiaba.
         */
        fun freedrawWidthFor(ancho: Double): Double = ancho / 2

        /**
         * Cuánto engorda el marcador respecto al lápiz.
         *
         * Vive aquí y no en el controlador porque hacen falta los dos: quien
         * traza el subrayado y quien tiene que enseñar de qué ancho va a salir.
         */
        const val ENGORDE_DEL_MARCADOR = 5.0

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

        /**
         * Subido de 30 a 400 por los planos importados: con el lado mayor de un
         * plano en 1600 px de escena, un detalle de centímetros necesita cientos
         * de aumentos para ocupar la pantalla, y con la tinta de plano a un pelo
         * los trazos siguen finos hasta el fondo. Lo que se pinta se recorta
         * antes por la vista (getVisibleElements) y los sólidos ya se pintan en
         * local (ver Renderer), así que las coordenadas de dispositivo de lo
         * visible se mantienen chicas; más allá de esto, la precisión del
         * `float` del rasterizador empieza a comerse fracciones de píxel en las
         * cadenas que cruzan la pantalla de punta a punta.
         */
        const val MAX_ZOOM = 400.0
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
/**
 * **La llave de paso de las luces**: si están encendidas y cuánto alumbran.
 *
 * ## Por qué es una sola para todo el dibujo
 *
 * Porque es lo que uno quiere tocar. Lo que se hace con las luces de un plano es **subirlas
 * todas o apagarlas todas** —se enseña el dibujo, se apagan; se mira de noche, se suben— y
 * con un mando por trazo eso son veinte gestos para una decisión. Es la misma llave que la
 * del croquis en el espacio, y por lo mismo.
 *
 * ## Y apagada, la tinta de luz es tinta lisa
 *
 * No es un caso raro: es lo que promete el mando. En cero sale el color que se eligió, ni más
 * ni menos —ni resplandor, ni capas blancas encima, ni nada—, y de ahí para arriba se va
 * encendiendo. Sin esto, una tinta de luz apagada seguía siendo una raya lavada que no era el
 * color de nadie.
 *
 * ## Hasta el doble
 *
 * Hasta uno, la luz **se enciende**: el color se va hacia su tono vivo y el centro hacia el
 * blanco. De uno para arriba ya no queda color al que ir, y lo que sube es **cuánto se sale
 * del blanco de la pantalla** — que es lo que hace una fuente cuando le subes la corriente
 * estando ya encendida. Eso último solo se ve de verdad en una pantalla con margen: ver
 * [ElBrilloDeMas].
 */
@Serializable
data class LucesDelDibujo(
    val encendidas: Boolean = true,
    /** Cuánto alumbran, de cero a dos. Uno es como se dibujó cada trazo. */
    val fuerza: Double = 1.0
) {
    /** Lo que multiplica a la luz de cada trazo. Cero es apagado, y apagado es tinta lisa. */
    val cuanto: Double get() = if (encendidas) fuerza.coerceIn(0.0, LO_MAS_QUE_ALUMBRAN) else 0.0

    companion object {
        /** Hasta dónde llega la llave. Ver arriba. */
        const val LO_MAS_QUE_ALUMBRAN = 2.0
    }
}

@Serializable
data class Scene(
    val elements: List<Element> = emptyList(),
    val files: Map<String, SceneFile> = emptyMap(),
    val viewport: Viewport = Viewport(),
    val style: ItemStyle = ItemStyle(),
    val backgroundColor: String = "#ffffff",
    /**
     * **La llave de paso de las luces del dibujo.** Ver [LucesDelDibujo].
     *
     * Va en la escena y no en cada trazo porque **es una decisión del dibujo entero**: las
     * luces se encienden y se apagan a la vez, como las de una habitación. Es la misma que la
     * del croquis en el espacio.
     */
    val luces: LucesDelDibujo = LucesDelDibujo(),
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
    val alfileres: List<Alfiler> = emptyList(),
    /**
     * Desde dónde se mira lo que está en volumen. **Una para todo el dibujo.**
     *
     * Va aquí y no en cada caja, y esa es la decisión: una vista por sólido sería
     * un dibujo imposible, con cada volumen mirado desde un sitio distinto y sin
     * ninguna relación espacial entre ellos —dos cajas apoyadas una junto a otra
     * dejarían de estar apoyadas—. La cámara es del dibujo, como lo es la
     * [escala], y girarla gira **todo** a la vez, que es lo que conserva el
     * croquis.
     *
     * Con valor por defecto para que un dibujo guardado antes de que esto
     * existiera siga abriéndose: el campo ausente en el JSON toma [Vista.CERO],
     * que es exactamente la vista con la que se dibujó. Al `.excalidraw` de
     * intercambio no sale —ese archivo se arma aparte en [ExcalidrawStore]— por
     * lo mismo que la escala: es un campo propio y el otro no sabría leerlo.
     */
    val vista: Vista = Vista.CERO
) {

    /** Lo que se ve de verdad: sin borrar y, si están escondidas, sin referencias. */
    val visibleConReferencias: List<Element>
        get() = if (referenciasVisibles) visible else visible.filter { !it.reference }
    /** Los que se pintan: sin borrar. */
    val visible: List<Element> get() = elements.filter { !it.isDeleted }

    /**
     * La hoja que manda: **la primera**.
     *
     * El pin y la exportación a imagen enseñan una sola, y tiene que ser una
     * decisión y no un sorteo: con dos marcos, cuál se ve no puede depender del
     * orden en que el motor recorra la lista. La primera que se puso.
     *
     * Que solo se enseñe una no quiere decir que solo pueda haber una: el PDF
     * las saca **todas, una por página**, que es para lo que sirve un documento
     * de varias hojas. Ver [marcos] y [DrawPdf].
     */
    val marco: Element? get() = marcos.firstOrNull()

    /**
     * **Todas las hojas**, en el orden en que se pusieron.
     *
     * El pin y la exportación a imagen siguen enseñando solo la primera —una
     * ventana de dos dedos de ancho no puede enseñar tres hojas— pero el PDF las
     * saca todas, **una por página**, que es para lo que existe un documento de
     * varias hojas. Ver [DrawPdf].
     */
    val marcos: List<Element> get() = visible.filter { it.isFrame }

    /** Lo que cae dentro de [marco], sin el marco mismo. */
    fun contenidoDe(marco: Element): List<Element> {
        val caja = getElementBounds(marco)
        return visible.filter { !it.isFrame && boundsOverlap(caja, getElementBounds(it)) }
    }

    /**
     * Lo que se ve fuera del editor: en el pin y al exportar.
     *
     * Sin marco es todo el dibujo. Con marco, **solo lo que cae dentro**, y el
     * marco mismo no entra: es la hoja, no una raya dibujada encima.
     */
    val contenidoVisible: List<Element>
        get() = marco?.let { contenidoDe(it) } ?: visible

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
    material = style.material,
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
    negrita = type == ElementType.TEXT && style.negrita,
    cursiva = type == ElementType.TEXT && style.cursiva,
    tachado = type == ElementType.TEXT && style.tachado,
    // La cota también lleva letra: su rótulo se calcula al pintar, pero con qué
    // tamaño y con qué familia sale se elige como en cualquier texto.
    fontSize = if (type == ElementType.TEXT || type == ElementType.MEASURE) {
        style.fontSize
    } else null,
    fontFamily = if (type == ElementType.TEXT || type == ElementType.MEASURE) {
        style.fontFamily
    } else null,
    textAlign = if (type == ElementType.TEXT) style.textAlign else null,
    verticalAlign = if (type == ElementType.TEXT) style.verticalAlign else null,
    // **El cronograma nace con filas.** Una figura que aparece en blanco obliga a
    // descubrir dónde se le añaden cosas antes de enseñar nada, y lo que uno quiere al
    // poner un cronograma es ver la rejilla y empezar a arrastrar barras. Las tres nacen
    // escalonadas —cada una detrás de la anterior— porque es lo que es un plan.
    tareas = if (type == ElementType.CRONOGRAMA) {
        List(FILAS_DE_FABRICA) { TareaDelCronograma(desde = it.toDouble(), cuanto = 1.0) }
    } else emptyList(),
    // La pieza que se dibuja con la herramienta es siempre una caja: la forma de un
    // volumen sale de levantar una figura, no de una lista. Ver [extruido].
    esqueleto = type == ElementType.SOLIDO && style.esqueleto
)

/** Con cuántas filas nace un cronograma: las justas para que se entienda de qué va. */
private const val FILAS_DE_FABRICA = 3

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
internal fun Element.conPuntosAbsolutos(absolutos: List<Pt>): Element {
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
 * El estilo que tiene [e] ahora mismo, leído del propio elemento.
 *
 * Hace falta para que los mandos **enseñen lo que hay marcado** en vez del
 * pincel. Sin esto el panel decía siempre lo del pincel: marcabas una raya roja
 * y fina y los mandos seguían en negro y gordo, así que tocar cualquiera de
 * ellos —la opacidad, por ejemplo— le encajaba a la raya el color y el grosor
 * del pincel de paso. Es el fallo de «cambio la opacidad y se me cambia el
 * color».
 */
fun estiloDe(e: Element): ItemStyle {
    val base = ItemStyle()
    return ItemStyle(
        strokeColor = e.strokeColor,
        backgroundColor = e.backgroundColor,
        fillStyle = e.fillStyle,
        // El lápiz guarda **la mitad** del número del mando (ver
        // [ItemStyle.freedrawWidthFor]), así que al leerlo se deshace la cuenta:
        // si no, marcar un garabato bajaría el deslizador a la mitad solo.
        strokeWidth = if (e.isFreeDraw) e.strokeWidth * 2 else e.strokeWidth,
        strokeStyle = e.strokeStyle,
        roughness = e.roughness,
        opacity = e.opacity,
        material = e.material,
        roundness = e.roundness,
        startArrowhead = e.startArrowhead,
        endArrowhead = e.endArrowhead,
        elbowed = e.elbowed,
        fontSize = e.fontSize ?: base.fontSize,
        fontFamily = e.fontFamily ?: base.fontFamily,
        mosaicBlur = e.mosaicBlur,
        textAlign = e.textAlign ?: base.textAlign,
        verticalAlign = e.verticalAlign ?: base.verticalAlign,
        negrita = e.negrita,
        cursiva = e.cursiva,
        tachado = e.tachado,
        aumento = aumentoDe(e),
        lupaRedonda = e.lupaRedonda,
        guia = guiaDe(e),
        // El aumento sale de la geometría, no del campo: ver [aumentoDe].
        oscurecer = oscurecimientoDe(e),
        zona = zonaDe(e),
        presionFirme = e.presionFirme,
        // **Y qué pieza es un volumen, que faltaba y rompía el mando.**
        //
        // El panel enseña [estiloActivo], que sale de aquí. Sin estos dos campos, con una
        // caja marcada el mando leía siempre «caja» y «macizo» — daba igual lo que fuera
        // la pieza— y, peor, [cambiarEstilo] comparaba contra ese «caja» de mentira: pedir
        // caja no cambiaba nada porque creía que ya lo era, y pedir cualquier otra cosa se
        // aplicaba pero el mando volvía a saltar a caja al recomponerse. Desde fuera se
        // veía exactamente como lo que se veía: que solo se podía poner la cúbica.
        esqueleto = e.esqueleto
    )
}

/**
 * [e] con el estilo [s] puesto, **en lo que le aplique y solo en eso**.
 *
 * Quién puede llevar qué lo decide [propiedadesDeTipo], que es la misma tabla
 * con la que el panel decide qué mandos enseñar. Volcar el estilo entero
 * escribiría en un texto un tipo de línea que no se pinta y en una hoja un color
 * que no tiene.
 */
fun conEstilo(e: Element, s: ItemStyle): Element {
    val aplican = propiedadesDeTipo(e.type)
    var out = e
    if (Propiedad.TRAZO in aplican) out = out.copy(strokeColor = s.strokeColor)
    if (Propiedad.FONDO in aplican) out = out.copy(backgroundColor = s.backgroundColor)
    if (Propiedad.RELLENO in aplican) out = out.copy(fillStyle = s.fillStyle)
    if (Propiedad.LINEA in aplican) out = out.copy(strokeStyle = s.strokeStyle)
    if (Propiedad.GROSOR in aplican) {
        out = out.copy(
            strokeWidth =
                if (e.isFreeDraw) ItemStyle.freedrawWidthFor(s.strokeWidth) else s.strokeWidth
        )
    }
    if (Propiedad.RUGOSIDAD in aplican) out = out.copy(roughness = s.roughness)
    if (Propiedad.ESQUINAS in aplican) out = out.copy(roundness = s.roundness)
    if (Propiedad.PUNTAS in aplican) {
        out = out.copy(startArrowhead = s.startArrowhead, endArrowhead = s.endArrowhead)
    }
    if (Propiedad.FORMA_FLECHA in aplican) out = out.copy(elbowed = s.elbowed)
    if (Propiedad.MOSAICO in aplican) out = out.copy(mosaicBlur = s.mosaicBlur)
    if (Propiedad.OPACIDAD in aplican) out = out.copy(opacity = s.opacity)
    if (Propiedad.MATERIAL in aplican) out = out.copy(material = s.material)
    if (Propiedad.FUENTE in aplican) {
        out = out.copy(fontSize = s.fontSize, fontFamily = s.fontFamily)
    }
    if (Propiedad.ESTILO_DE_TEXTO in aplican) {
        out = out.copy(negrita = s.negrita, cursiva = s.cursiva, tachado = s.tachado)
    }
    if (Propiedad.LUPA in aplican) {
        out = out.copy(lupaRedonda = s.lupaRedonda, guia = s.guia)
        // **El aumento cambia el tamaño del cristal, no un número.** Ver
        // [conAumento]: la zona mirada se queda como está y la ventana crece.
        if (out.type == ElementType.LUPA) out = conAumento(out, s.aumento)
    }
    if (Propiedad.OSCURECER in aplican) out = out.copy(oscurecer = s.oscurecer)
    if (Propiedad.ZONA in aplican) out = conZona(out, s.zona)
    if (Propiedad.PRESION in aplican) out = out.copy(presionFirme = s.presionFirme)
    if (Propiedad.VOLUMEN in aplican) out = out.copy(esqueleto = s.esqueleto)
    return out
}

/**
 * [destino] con **solo lo que cambió** entre [anterior] y [nuevo].
 *
 * Es la pieza que hacía falta. Un panel de estilos no dice «ponle este estilo»,
 * dice «súbele la opacidad»: lo que llega es el estilo entero con un campo
 * distinto, y volcarlo tal cual escribe los otros quince encima. Con algo
 * marcado eso se veía enseguida —subías la opacidad y la figura cambiaba de
 * color y de grosor—, porque lo que se volcaba era el pincel.
 *
 * Comparando campo a campo, tocar un mando toca **ese** mando. Es tedioso de
 * escribir y no hay atajo: el estilo es una clase de datos y el lenguaje no dice
 * cuál de sus campos acaba de cambiar.
 */
fun conCambios(destino: ItemStyle, anterior: ItemStyle, nuevo: ItemStyle): ItemStyle {
    var out = destino
    if (nuevo.strokeColor != anterior.strokeColor) out = out.copy(strokeColor = nuevo.strokeColor)
    if (nuevo.backgroundColor != anterior.backgroundColor) {
        out = out.copy(backgroundColor = nuevo.backgroundColor)
    }
    if (nuevo.fillStyle != anterior.fillStyle) out = out.copy(fillStyle = nuevo.fillStyle)
    if (nuevo.strokeWidth != anterior.strokeWidth) out = out.copy(strokeWidth = nuevo.strokeWidth)
    if (nuevo.strokeStyle != anterior.strokeStyle) out = out.copy(strokeStyle = nuevo.strokeStyle)
    if (nuevo.roughness != anterior.roughness) out = out.copy(roughness = nuevo.roughness)
    if (nuevo.opacity != anterior.opacity) out = out.copy(opacity = nuevo.opacity)
    if (nuevo.material != anterior.material) out = out.copy(material = nuevo.material)
    if (nuevo.roundness != anterior.roundness) out = out.copy(roundness = nuevo.roundness)
    if (nuevo.startArrowhead != anterior.startArrowhead) {
        out = out.copy(startArrowhead = nuevo.startArrowhead)
    }
    if (nuevo.endArrowhead != anterior.endArrowhead) {
        out = out.copy(endArrowhead = nuevo.endArrowhead)
    }
    if (nuevo.elbowed != anterior.elbowed) out = out.copy(elbowed = nuevo.elbowed)
    if (nuevo.fontSize != anterior.fontSize) out = out.copy(fontSize = nuevo.fontSize)
    if (nuevo.fontFamily != anterior.fontFamily) out = out.copy(fontFamily = nuevo.fontFamily)
    if (nuevo.mosaicBlur != anterior.mosaicBlur) out = out.copy(mosaicBlur = nuevo.mosaicBlur)
    if (nuevo.textAlign != anterior.textAlign) out = out.copy(textAlign = nuevo.textAlign)
    if (nuevo.verticalAlign != anterior.verticalAlign) {
        out = out.copy(verticalAlign = nuevo.verticalAlign)
    }
    if (nuevo.negrita != anterior.negrita) out = out.copy(negrita = nuevo.negrita)
    if (nuevo.cursiva != anterior.cursiva) out = out.copy(cursiva = nuevo.cursiva)
    if (nuevo.tachado != anterior.tachado) out = out.copy(tachado = nuevo.tachado)
    if (nuevo.aumento != anterior.aumento) out = out.copy(aumento = nuevo.aumento)
    if (nuevo.lupaRedonda != anterior.lupaRedonda) {
        out = out.copy(lupaRedonda = nuevo.lupaRedonda)
    }
    if (nuevo.guia != anterior.guia) out = out.copy(guia = nuevo.guia)
    if (nuevo.oscurecer != anterior.oscurecer) out = out.copy(oscurecer = nuevo.oscurecer)
    if (nuevo.zona != anterior.zona) out = out.copy(zona = nuevo.zona)
    if (nuevo.presionFirme != anterior.presionFirme) {
        out = out.copy(presionFirme = nuevo.presionFirme)
    }
    // **Y el alambre, que faltaba aquí y por eso el modo esqueleto no hacía nada.**
    //
    // Esta función es la que decide qué mando se ha movido: lo que no aparece en esta
    // lista no llega nunca al elemento. El interruptor del lateral cambiaba el pincel
    // —lo siguiente que se dibujara sí salía en alambre— pero la pieza ya marcada se
    // quedaba exactamente igual, que es lo que se veía: «el esqueleto no funciona».
    if (nuevo.esqueleto != anterior.esqueleto) out = out.copy(esqueleto = nuevo.esqueleto)
    return out
}

/**
 * El cambio de estilo llevado a un elemento: **solo lo que se tocó, y solo si le
 * aplica**.
 *
 * Existe para que no haya tres versiones de esto. La barra vive en tres sitios
 * —el pin, la captura y el editor— y cada uno traía su propia lambda de «qué
 * campos copio»: la del pin llevaba color y grosor, la del editor además la
 * letra, y el resultado era que cambiar el tamaño del número de una cota
 * funcionaba en un sitio y en otro no.
 *
 * Son las tres piezas de arriba en fila: se lee el estilo que tiene el elemento
 * ([estiloDe]), se le mete encima lo que haya cambiado en el panel
 * ([conCambios]) y se vuelca a los campos que su tipo admita ([conEstilo]).
 *
 * El texto se queda a medias a propósito: cambiarle la letra obliga a **volver a
 * medir su caja**, y eso necesita Android. La medida la pone quien tenga
 * contexto. La cota no: su rótulo se calcula al pintar y no guarda caja ninguna.
 */
fun estiloAplicado(e: Element, anterior: ItemStyle, nuevo: ItemStyle): Element =
    conEstilo(e, conCambios(estiloDe(e), anterior, nuevo))

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
    // **El borrador atraviesa lo que no puede borrar.** Descartándolas antes de
    // buscar, y no después, pasar por encima de una foto borra lo que hay
    // dibujado debajo en vez de no hacer nada: si la foto ganase el toque, el
    // borrador se quedaría muerto sobre toda la superficie que ella tapa.
    val candidatos = elements.filter { alcanza(it) && !intocableParaElBorrador(it) }
    val hit = getElementAtPosition(candidatos, p, threshold) ?: return elements
    // Borrar un miembro borra el grupo entero, igual que seleccionarlo.
    val victims = getElementsInGroupOf(elements, hit).map { it.id }.toSet()
    return elements.map {
        if (it.id in victims && !intocableParaElBorrador(it)) {
            it.copy(isDeleted = true).touched()
        } else it
    }
}

/**
 * Lo que el borrador nunca se lleva.
 *
 * Lo bloqueado, porque para eso se bloquea. Y **las imágenes**, aunque estén
 * sueltas: se meten para dibujar encima de ellas, así que el borrador las
 * recorre entero y a la mínima se llevaba la foto en vez de la línea que se
 * quería quitar —y con ella, la referencia de todo lo demás—. Una foto sobra
 * pocas veces, y para esas está seleccionarla y tirarla a la papelera, que es
 * un gesto que se hace a propósito y no de refilón.
 */
fun intocableParaElBorrador(e: Element): Boolean =
    e.locked || e.type == ElementType.IMAGE

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
    return elements.filter { !it.isDeleted && boundsOverlap(view, cajaConLoQueDibuja(it)) }
}

/**
 * Lo que ocupa un elemento **contando lo que dibuja fuera de su caja**.
 *
 * Casi todo cabe en la suya, pero una lupa no: su guía sale hasta la zona que
 * mira, que puede estar en la otra punta del dibujo. Recortando por la caja, al
 * alejarse un poco se salía de pantalla y con ella **desaparecían su marco y su
 * raya**, aunque la raya cruzara justo por delante de los ojos. Lo mismo con un
 * foco, que oscurece un anillo alrededor de una zona que puede quedar lejos.
 */
fun cajaConLoQueDibuja(e: Element): Bounds {
    val caja = getElementBounds(e)
    // **Y un sólido tampoco cabe en la suya.** Su caja es la huella en el suelo,
    // y lo que se dibuja se levanta por encima de ella tanto como mida de alto.
    // Recortando por la huella, una caja alta desaparecía de golpe al bajar la
    // vista un poco: seguía habiendo volumen delante de los ojos y la huella ya
    // había salido de pantalla. Se devuelve lo que ocupa **desde cualquier
    // vista** porque aquí no se sabe cuál es la puesta, y pasarse es gratis.
    if (e.isSolido) return envolturaDeSolidoSinVista(e)
    if (e.type != ElementType.LUPA && e.type != ElementType.SPOTLIGHT) return caja
    val zona = regionDeLaLupa(e)
    return Bounds(
        minOf(caja.x1, zona.x1), minOf(caja.y1, zona.y1),
        maxOf(caja.x2, zona.x2), maxOf(caja.y2, zona.y2)
    )
}
