package com.forge.pixpin.motor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Punto en coordenadas de **escena** (no de pantalla ni de bitmap).
 *
 * En Excalidraw la escena es infinita y el punto es un par de `number`; aquí
 * `Double` por lo mismo que en `croquis`: con coordenadas grandes un `Float`
 * pierde precisión visible y las formas empiezan a bailar al hacer zoom.
 */
@Serializable
data class Pt(val x: Double, val y: Double)

/**
 * Tipos de elemento. Se dejan fuera frame, embeddable e iframe.
 *
 * Los tres últimos **no existen en Excalidraw**: son de PixPin y vienen del
 * motor de anotación viejo, donde eran lo más útil para trabajar sobre una
 * captura de pantalla. Se conservan porque tapar un dato, señalar una zona y
 * numerar pasos no lo cubre ninguna herramienta del original. Su nombre
 * serializado lleva el prefijo `pixpin-` para que un `.excalidraw` que salga de
 * aquí no choque nunca con un tipo que el original añada más adelante.
 */
@Serializable
enum class ElementType {
    @SerialName("rectangle") RECTANGLE,
    @SerialName("diamond") DIAMOND,
    @SerialName("ellipse") ELLIPSE,
    @SerialName("arrow") ARROW,
    @SerialName("line") LINE,
    @SerialName("freedraw") FREEDRAW,
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,

    /** Tapa lo que hay debajo, pixelado o desenfocado. */
    @SerialName("pixpin-mosaic") MOSAIC,

    /**
     * Oscurece todo MENOS su caja. Se pinta siempre el último.
     *
     * **Ya no se puede crear**: la sustituyó [LUPA], que hace lo mismo —señalar
     * un sitio— pero enseñándolo en vez de escondiendo el resto. El tipo se
     * queda porque los dibujos guardados lo llevan dentro, y quitarlo del todo
     * haría que un archivo de antes no se pudiera ni abrir.
     */
    @SerialName("pixpin-spotlight") SPOTLIGHT,

    /**
     * La lupa: **un trozo del dibujo, enseñado en grande en otro sitio**.
     *
     * Son dos rectángulos y no uno, y ahí está toda la idea:
     *
     * - **La caja del elemento** (`x`, `y`, `width`, `height`) es el cristal:
     *   dónde se ve el resultado.
     * - **[foco]** es a dónde mira, en coordenadas del dibujo. Lo que se recoge
     *   es un recuadro centrado ahí, [aumento] veces más pequeño que el cristal
     *   — por eso lo que entra sale agrandado justo ese tanto.
     *
     * Guardar el foco **en absoluto y no relativo al cristal** es lo que permite
     * apartar el resultado sin mover lo que se está mirando, que es para lo que
     * sirve una lupa en una lámina: el detalle se ve al lado, con su flecha, sin
     * taparlo.
     */
    @SerialName("pixpin-lupa") LUPA,

    /** Un círculo con un número dentro: 1, 2, 3… El número va en `text`. */
    @SerialName("pixpin-serial") SERIAL,

    /**
     * La cota: una raya que dice cuánto mide lo que cruza.
     *
     * Viene del croquis, que existía solo para esto. Es un elemento de dos
     * puntos como la línea, pero su rótulo **no se guarda**: se calcula al
     * pintar, a partir de su largo y de la escala de la escena. Así una cota
     * nunca puede mentir — al mover un extremo o al recalibrar, el número
     * cambia solo, que es justo lo que no pasaría si fuera un texto pegado.
     */
    @SerialName("pixpin-measure") MEASURE,

    /**
     * El arco: el trozo de circunferencia que uno quiere.
     *
     * Se guarda como la **caja del óvalo** más dónde empieza y cuánto barre, y
     * no como una lista de puntos: así se puede seguir estirando el óvalo
     * después y el arco se reajusta solo, que es lo que uno espera de algo que
     * se trazó con un compás. Ver [Arco].
     */
    @SerialName("pixpin-arc") ARC,

    /**
     * El relleno de un espacio cerrado: **la mancha que no es de nadie**.
     *
     * Todos los demás rellenos pertenecen a una figura —el fondo de un
     * rectángulo, el de un lazo—, así que solo se puede colorear lo que alguien
     * dibujó de una sola vez. Pero el hueco entre tres líneas y media elipse no
     * es de ninguna de las cuatro, y hasta ahora no había forma de pintarlo:
     * había que repasarlo a mano con el lápiz y rellenar eso.
     *
     * Guarda **el contorno que se encontró** (en `points`) más sus huecos (en
     * `huecos`), no una referencia a las figuras que lo encerraban. Es a
     * propósito: rehacer la región en cada fotograma costaría un barrido
     * completo de la escena, y sobre todo haría que el relleno cambiase solo al
     * mover cualquier cosa que lo rozara. Lo que se rellenó se queda relleno; si
     * el hueco cambia, se vuelve a tocar con el bote. Ver [Regiones].
     */
    @SerialName("pixpin-region") REGION,

    /**
     * La escala gráfica: la reglita a cuadros de los planos.
     *
     * Se dibuja por su caja, como el mosaico o el foco, y lo que enseña sale de
     * lo ancha que sea y de la escala de la escena. Ver [EscalaGrafica].
     */
    @SerialName("pixpin-scalebar") ESCALA_GRAFICA,

    /**
     * El marco: **la hoja**.
     *
     * Delimita qué parte del lienzo infinito es «el dibujo». En el editor sigues
     * viendo y dibujando fuera de él, pero el pin y la exportación enseñan solo
     * lo de dentro. Estirarlo por una esquina es lo que da más hoja cuando se
     * llena, sin mover una sola de las notas que ya hay.
     *
     * Es el mismo `frame` del original, con su nombre serializado, así que un
     * dibujo con marco va y viene de la web sin traducir nada.
     */
    @SerialName("frame") FRAME,

    /**
     * Un punto con su letra: **A, B, C sobre el dibujo**.
     *
     * Es la herramienta de las matemáticas. Un croquis de geometría se explica
     * nombrando los puntos —«el triángulo ABC», «la mediatriz de AB»—, y sin
     * poder nombrarlos cada frase hay que acompañarla de un dedo señalando.
     *
     * Su caja **no tiene tamaño**: `x` e `y` son el punto y ya está. El redondel
     * y la letra se dibujan alrededor, como el rótulo de una cota, para que
     * arrastrarlo lo mueva en vez de estirarlo. Ver [Puntos].
     */
    @SerialName("pixpin-point") PUNTO,

    /**
     * El plano cartesiano: **los ejes para graficar una función**.
     *
     * No es «unos ejes dibujados»: es un instrumento que sabe cuánto vale una
     * unidad, así que puede decir dónde cae el (3, −2) y seguir sabiéndolo
     * después de estirarlo. De ahí sale su comportamiento raro al redimensionar
     * —por un lado aparecen más números, por una esquina los mismos más
     * grandes— que es lo que no podría hacer un dibujo. Ver [Plano].
     */
    @SerialName("pixpin-axes") PLANO,

    /**
     * La caja del boceto en volumen: **la única pieza 3D del motor**.
     *
     * Es un rectángulo apoyado en el suelo isométrico más lo que levanta. Se
     * pinta como tres caras —la tapa y dos paredes— con el mismo color base a
     * tres claridades, y se apoya con su sombra. Ver [Solido] y [Proyeccion].
     *
     * ## Por qué la huella reutiliza `x`, `y`, `width` y `height`
     *
     * Un sólido necesita cinco números, y cuatro de ellos **ya son exactamente
     * los de siempre**: la huella es un rectángulo, igual que la caja de un
     * rectángulo o de una imagen. Guardándola ahí, mover la caja, estirarla por
     * un tirador, meterla en un grupo, alinearla, duplicarla, contarla para el
     * encuadre o serializarla es el camino de siempre, sin una sola rama nueva.
     * Un centro y tres semilados serían más «3D» y no servirían para ninguna de
     * esas cosas.
     *
     * Lo único que no cabía es **cuánto levanta**, y eso va en [Element.altura].
     *
     * El convenio es que `x` e `y` son el punto de la escena donde se apoya el
     * origen de la huella —la esquina `(0, 0, 0)` ya proyectada— y que `width`,
     * `height` y `altura` van en **píxeles de escena**, es decir con una unidad
     * del mundo por píxel. Ver [solidoDe].
     *
     * Consecuencia asumida y documentada: lo que se dibuja **sobresale de su
     * caja** hacia arriba y hacia la izquierda, porque la proyección lleva los
     * vértices donde los lleva. La caja sigue siendo la huella —que es lo que se
     * estira y lo que se mueve— y quien necesita lo que ocupa de verdad pregunta
     * por [cajaConLoQueDibuja].
     */
    @SerialName("pixpin-solid") SOLIDO
}

@Serializable
enum class FillStyle {
    @SerialName("hachure") HACHURE,
    @SerialName("cross-hatch") CROSS_HATCH,
    @SerialName("solid") SOLID,
    @SerialName("zigzag") ZIGZAG,

    /**
     * Rayas **a tiralíneas**: rectas, paralelas y sin temblor.
     *
     * No está en Excalidraw y por eso lleva el prefijo `pixpin-`, como los tipos
     * propios. El rayado del original imita el rotulador, y eso es lo que se
     * quiere en un esquema; pero rellenando la sección de un plano o el hueco
     * entre dos piezas, un rayado tembloroso se lee como suciedad. Es el mismo
     * barrido de [HACHURE] sin pasar las rectas por el generador de ruido.
     */
    @SerialName("pixpin-lines") LINEAS
}

@Serializable
enum class StrokeStyle {
    @SerialName("solid") SOLID,
    @SerialName("dashed") DASHED,
    @SerialName("dotted") DOTTED
}

@Serializable
enum class Arrowhead {
    @SerialName("arrow") ARROW,
    @SerialName("bar") BAR,
    @SerialName("circle") CIRCLE,
    @SerialName("circle_outline") CIRCLE_OUTLINE,
    @SerialName("triangle") TRIANGLE,
    @SerialName("triangle_outline") TRIANGLE_OUTLINE,
    @SerialName("diamond") DIAMOND,
    @SerialName("diamond_outline") DIAMOND_OUTLINE
}

@Serializable
enum class TextAlign {
    @SerialName("left") LEFT,
    @SerialName("center") CENTER,
    @SerialName("right") RIGHT
}

@Serializable
enum class VerticalAlign {
    @SerialName("top") TOP,
    @SerialName("middle") MIDDLE,
    @SerialName("bottom") BOTTOM
}

/**
 * Redondeo de esquinas. Excalidraw lo guarda como objeto y no como booleano
 * para poder cambiar el algoritmo sin romper los dibujos viejos; se copia igual
 * porque es lo que permite leer y escribir `.excalidraw` sin traducir.
 *
 * - [LEGACY] y [PROPORTIONAL_RADIUS]: el radio es una fracción del lado corto.
 * - [ADAPTIVE_RADIUS]: radio fijo, que deja de crecer en formas grandes.
 */
@Serializable
data class Roundness(val type: Int) {
    companion object {
        const val LEGACY = 1
        const val PROPORTIONAL_RADIUS = 2
        const val ADAPTIVE_RADIUS = 3
    }
}

/** Referencia a un elemento atado (texto dentro de una forma, flecha anclada). */
@Serializable
data class BoundElement(val id: String, val type: ElementType)

/**
 * Un elemento del dibujo.
 *
 * **Es una clase plana con discriminante, no una jerarquía sellada.** Va contra
 * la costumbre de `croquis` y `annotate`, y es a propósito: el JSON de
 * `.excalidraw` es exactamente esta forma —un objeto con `type` y todos los
 * campos al mismo nivel—, así que una clase plana serializa y deserializa sin
 * capa de traducción. Una jerarquía sellada obligaría a escribir y mantener a
 * mano el mapeo en los dos sentidos, que es justo donde se cuelan los fallos de
 * interoperabilidad.
 *
 * Los campos específicos de un tipo son nulables y solo los usa ese tipo; los
 * accesos van por las extensiones del final del archivo, que fallan claro si el
 * elemento no es del tipo esperado.
 */
@Serializable
data class Element(
    val id: String,
    val type: ElementType,

    // --- Geometría ---
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    /** Rotación en **radianes**, alrededor del centro de la caja. */
    val angle: Double = 0.0,

    // --- Estilo ---
    val strokeColor: String = DEFAULT_STROKE_COLOR,
    val backgroundColor: String = TRANSPARENT,
    val fillStyle: FillStyle = FillStyle.SOLID,
    val strokeWidth: Double = 2.0,
    val strokeStyle: StrokeStyle = StrokeStyle.SOLID,
    val roughness: Int = ROUGHNESS_ARTIST,
    val opacity: Int = 100,
    val roundness: Roundness? = null,

    /**
     * Semilla del generador pseudoaleatorio del trazo.
     *
     * **Es el campo más importante del modelo.** El trazo «a mano alzada» se
     * genera con ruido; si la semilla no se guarda con el elemento, cada
     * redibujado sortea un ruido distinto y la forma tiembla al mover el dedo,
     * al hacer zoom o al recomponer. Guardándola, el mismo elemento produce
     * siempre exactamente el mismo garabato.
     */
    val seed: Int,

    // --- Identidad y orden ---
    val version: Int = 1,
    val versionNonce: Int = 0,
    val isDeleted: Boolean = false,
    val groupIds: List<String> = emptyList(),
    val boundElements: List<BoundElement>? = null,
    val updated: Long = 0L,
    val link: String? = null,
    val locked: Boolean = false,

    /**
     * Es una **línea de referencia**, no parte del dibujo.
     *
     * Se pinta translúcida, imanta como cualquier otra cosa y se puede esconder
     * o borrar toda de golpe sin tocar el dibujo. Es el equivalente al lápiz azul
     * de los planos de toda la vida: se traza encima y luego se borra el azul.
     *
     * Va como **una marca en el elemento** y no como un tipo aparte a propósito:
     * cualquier herramienta puede trazar referencia sin duplicar nada. Un
     * rectángulo de referencia es el mismo rectángulo con esto puesto, así que
     * se mueve, se estira, se pica y se guarda por el mismo camino de siempre —
     * y el día que el motor gane una herramienta, esa también sabrá hacerlo.
     */
    val reference: Boolean = false,

    // --- Solo LINE, ARROW y FREEDRAW ---
    /** Puntos **relativos a (x, y)**. El primero es siempre (0, 0). */
    val points: List<Pt>? = null,
    /** Solo FREEDRAW: presión por punto, 0..1. Con el dedo llega siempre 1. */
    val pressures: List<Double>? = null,
    val simulatePressure: Boolean = true,
    /**
     * El trazo va **firme**: mismo grosor de punta a punta.
     *
     * La presión adelgaza el trazo donde la mano va rápida, que dibujando queda
     * bien y escribiendo no: una letra pequeña se queda a medio ver en las
     * curvas. Se apaga desde la configuración y solo afecta a lo que se trace a
     * partir de entonces, como cualquier otro ajuste del pincel.
     */
    val presionFirme: Boolean = false,
    val lastCommittedPoint: Pt? = null,

    // --- Solo REGION ---
    /**
     * Los agujeros del relleno, **relativos a (x, y)** igual que `points`.
     *
     * Sin esto la herramienta no serviría para lo que se pide: el hueco entre un
     * rectángulo y un círculo dentro de él es un anillo, y un anillo es un
     * contorno con un agujero. Guardando solo el contorno de fuera, el bote
     * pintaría también el círculo de dentro — justo el espacio que **no** se ha
     * tocado.
     *
     * Se pintan por regla par/impar contra `points`, que es lo que hace que un
     * hueco dentro de otro hueco vuelva a ser relleno.
     */
    val huecos: List<List<Pt>>? = null,

    // --- Solo ARROW ---
    val startArrowhead: Arrowhead? = null,
    /**
     * **El valor por defecto tiene que ser null, no [Arrowhead.ARROW].**
     *
     * Que una flecha nazca con punta se decide en `ItemStyle` y `newElement`,
     * no aquí. Si el defecto de este campo fuese `ARROW`, un rectángulo —que lo
     * tiene a null— se serializaría **sin** el campo, porque `ExcalidrawJson`
     * omite los nulos para no ensuciar el `.excalidraw`; y al releerlo, el
     * campo ausente tomaría el valor por defecto y el rectángulo volvería con
     * una punta de flecha. Lo pilló el test de ida y vuelta.
     */
    val endArrowhead: Arrowhead? = null,
    val startBinding: Binding? = null,
    val endBinding: Binding? = null,
    /**
     * Flecha de codos: se traza en ángulos de 90° en vez de ir directa.
     *
     * Es el `elbowed` del original, con su mismo nombre serializado. Para qué
     * sirve —y por qué no es lo mismo que una flecha curva— en [Elbow].
     */
    val elbowed: Boolean = false,

    // --- Solo TEXT ---
    val text: String? = null,
    val fontSize: Double? = null,
    val fontFamily: Int? = null,
    val textAlign: TextAlign? = null,
    val verticalAlign: VerticalAlign? = null,
    /** Id de la forma que contiene este texto, si está dentro de una. */
    val containerId: String? = null,
    /**
     * Negrita, cursiva y tachado.
     *
     * Van como tres marcas y no como un solo campo de «estilo» porque se
     * combinan: negrita **y** cursiva a la vez es corriente. No existen en
     * Excalidraw —de ahí que sean tres campos propios— y al exportar a
     * `.excalidraw` se pierden, que es lo mismo que le pasa a cualquier tipo de
     * los de PixPin: el archivo de intercambio guarda lo que el otro sabe leer.
     */
    val negrita: Boolean = false,
    val cursiva: Boolean = false,
    val tachado: Boolean = false,

    // --- Solo FRAME ---
    /** Nombre del marco, el que el original enseña sobre su esquina. */
    val name: String? = null,

    /**
     * Dónde empieza el arco y cuánto barre, en radianes. Solo [ElementType.ARC].
     *
     * `arcSweep` a null significa **todavía es el óvalo guía**: se ha puesto el
     * instrumento pero no se ha trazado nada con él. Es un estado real y por eso
     * se distingue de un barrido de cero, que sería un arco vacío.
     */
    val arcStart: Double? = null,
    val arcSweep: Double? = null,

    /**
     * Hacia dónde queda la letra de un punto, en radianes, y a qué distancia.
     *
     * Van juntos y en polares y no como un desplazamiento en x/y porque lo que
     * define la etiqueta es **que orbita su punto**: al mover el punto la letra
     * lo sigue conservando su sitio relativo, y al arrastrar la letra lo que
     * cambia es el ángulo. En cartesianas habría que recalcular las dos
     * coordenadas en cada movimiento y el radio se iría solo. Ver [Puntos].
     */
    val etiquetaAngulo: Double? = null,
    val etiquetaRadio: Double? = null,

    // --- Solo PLANO ---
    /**
     * Cuántos píxeles de escena mide **una unidad** del plano.
     *
     * Es el campo del que sale todo lo demás: el intervalo que se ve es la caja
     * dividida por esto. Estirando por una esquina crece con la caja —el plano
     * se ve más grande— y estirando por un lado se queda como está, que es lo
     * que hace que aparezcan más números. Ver [Plano].
     */
    val unidad: Double? = null,
    /** Cada cuántas unidades se escribe un número en las reglas. */
    val pasoDeNumeros: Double? = null,
    /** Cada cuántas unidades va una raya fina de la rejilla. */
    val pasoDeCuadros: Double? = null,

    // --- Solo SOLIDO ---
    /**
     * Lo que levanta del suelo la caja del boceto en volumen, en píxeles de
     * escena. Solo [ElementType.SOLIDO].
     *
     * Es el **único** número que no cabía en los campos de siempre: la huella ya
     * va en `x`/`y`/`width`/`height` (el porqué, en [ElementType.SOLIDO]) y lo
     * que falta es la tercera medida.
     *
     * Nulo —y no cero— quiere decir «todavía no se ha levantado»: es el estado
     * real en el que queda la caja entre la primera fase del gesto y la segunda,
     * mientras solo se ha dibujado la huella en el suelo. Distinguirlo de una
     * altura de cero es lo que permite pintar solo la sombra mientras tanto en
     * vez de una caja aplastada. Ver [DrawController] y [sombraEnElSuelo].
     */
    val altura: Double? = null,

    // --- Solo MOSAIC ---
    /** Desenfocar en vez de pixelar. Son las dos formas de tapar de PixPin. */
    val mosaicBlur: Boolean = false,

    // --- Solo LUPA ---
    /**
     * A dónde mira la lupa, en coordenadas del dibujo.
     *
     * Nulo quiere decir «a lo que tengo debajo»: una lupa recién puesta se
     * comporta como una lupa de verdad apoyada sobre el papel, y solo cuando se
     * aparta el cristal —o se arrastra el foco— pasan a ser dos sitios
     * distintos. Ver [ElementType.LUPA].
     */
    val foco: Pt? = null,
    /**
     * Cuántas veces se agranda lo que se mira.
     *
     * Es una **pista de partida**, no la verdad: lo que manda es la geometría
     * —lo que mide el cristal dividido por lo que mide el foco— porque si no,
     * estirar el cristal con los tiradores dejaría un número que ya no
     * corresponde con lo que se ve. Ver [aumentoDe].
     */
    val aumento: Double? = null,
    /**
     * Lo que mide la zona que se mira.
     *
     * **Se guarda, no se calcula.** Antes salía del cristal dividido por el
     * aumento, y eso ataba las dos cosas: agrandar la ventana agrandaba también
     * la zona mirada, así que por mucho que se estirara se seguía viendo lo
     * mismo de grande. Guardándola, la zona se queda donde y como se puso —solo
     * se cambia arrastrando su contorno— y el cristal crece por su cuenta.
     */
    val focoAncho: Double? = null,
    val focoAlto: Double? = null,
    /**
     * Cuánto se oscurece lo de fuera de un foco, de 10 a 90 por ciento.
     *
     * Del propio elemento y no un ajuste global: en la misma lámina puede haber
     * un foco suave que solo insinúa y otro fuerte que aísla del todo.
     */
    val oscurecer: Int? = null,

    /** El cristal es redondo. En cuadrado sirve para recuadrar un detalle. */
    val lupaRedonda: Boolean = true,
    /** Se dibuja la línea que dice de dónde sale lo que se está viendo. */
    val lupaFlecha: Boolean = true,
    /** Con qué se señala de dónde sale lo que se ve. Ver [GuiaDeLupa]. */
    val guia: GuiaDeLupa? = null,
    /**
     * La guía va con **dos líneas** en vez de con una flecha.
     *
     * Es la forma de toda la vida en un plano: dos rayas que salen de los
     * costados del detalle y se abren hasta los del cristal, como un cono. Dice
     * lo mismo que la flecha y encima dice **cuánto** se ha ampliado, porque se
     * ve de un vistazo lo que se abre.
     */
    val lupaDosLineas: Boolean = false,
    /**
     * El contorno del cristal, **en proporción de su caja** (de 0 a 1).
     *
     * Es lo que permite que una lupa tenga la forma que uno quiera: se traza un
     * círculo, un rombo o un garabato cerrado, se toca con la lupa y ese mismo
     * contorno pasa a ser el cristal. Guardado en proporción y no en píxeles, la
     * forma aguanta que la lupa se estire y se gire sin deformarse por su cuenta.
     *
     * Nulo es el cristal de siempre: redondo o recuadro, según [lupaRedonda].
     */
    val forma: List<Pt>? = null,

    // --- Solo IMAGE ---
    val fileId: String? = null,
    /** Factores de escala en X e Y; el signo invierte la imagen (volteo). */
    val scale: List<Double> = listOf(1.0, 1.0),
    val crop: Crop? = null
) {
    companion object {
        const val DEFAULT_STROKE_COLOR = "#1e1e1e"
        const val TRANSPARENT = "transparent"

        /** Los tres niveles de `roughness` de Excalidraw. */
        const val ROUGHNESS_ARCHITECT = 0
        const val ROUGHNESS_ARTIST = 1
        const val ROUGHNESS_CARTOONIST = 2
    }
}

/**
 * Cómo se posa un extremo de flecha sobre la forma a la que se ata
 * (`BindMode`).
 */
@Serializable
enum class BindMode {
    /**
     * Se queda **fuera**, sobre el contorno, orbitándolo. Es lo que pasa cuando
     * sueltas la punta en el borde o justo al lado: la flecha toca la caja.
     */
    @SerialName("orbit") ORBIT,

    /**
     * Se queda **dentro**, en el punto exacto donde la soltaste. Es lo que pasa
     * cuando sueltas la punta bien adentro: la flecha entra y apunta a ese
     * sitio, no al borde más cercano.
     */
    @SerialName("inside") INSIDE
}

/**
 * Anclaje de un extremo de flecha a una forma.
 *
 * **Guarda un punto, no solo la forma**, y esa es la diferencia que faltaba. Con
 * solo el id, la flecha se ataba «a la caja» y siempre acababa proyectada al
 * borde: daba igual dónde hubieras soltado la punta. Con [fixedPoint] se
 * recuerda el sitio exacto, en proporción del ancho y el alto —de 0 a 1—, así
 * que sobrevive a mover y a redimensionar la forma; y [mode] decide si ese
 * punto se respeta tal cual o se usa solo para orientar la salida al contorno.
 */
@Serializable
data class Binding(
    val elementId: String,
    /** Cuánto se desvía el punto de contacto del centro, en -1..1. */
    val focus: Double = 0.0,
    /** Separación entre la punta y el borde de la forma, en px de escena. */
    val gap: Double = 1.0,
    /**
     * El punto agarrado, en proporción de la caja de la forma: `[0,0]` es su
     * esquina superior izquierda y `[1,1]` la inferior derecha. Null en los
     * anclajes viejos, que se comportan como siempre.
     */
    val fixedPoint: List<Double>? = null,
    val mode: BindMode = BindMode.ORBIT
)

/** Recorte de una imagen, en píxeles del archivo original. */
@Serializable
data class Crop(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val naturalWidth: Double,
    val naturalHeight: Double
)

// -------------------------------------------------------------------------
// Predicados de tipo. Se copian de `typeChecks.ts` porque media librería
// pregunta por ellos y así el resto del código lee igual que el original.
// -------------------------------------------------------------------------

val Element.isLinear: Boolean
    get() = type == ElementType.LINE || type == ElementType.ARROW ||
        type == ElementType.MEASURE

val Element.isFreeDraw: Boolean get() = type == ElementType.FREEDRAW

/** Los que tienen relleno: los demás ignoran `backgroundColor` y `fillStyle`. */
val Element.hasBackground: Boolean
    get() = when (type) {
        ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE,
        ElementType.LINE, ElementType.FREEDRAW, ElementType.REGION -> true
        // El sólido tiene relleno **y es lo que más lo necesita**: sin fondo, la
        // caja es un alambre y las tres claridades no tienen sobre qué actuar,
        // así que el volumen no se lee. Ver [aclarar].
        ElementType.SOLIDO -> true
        else -> false
    }

/** El relleno de un hueco: no tiene trazo propio, solo mancha. Ver [Regiones]. */
val Element.isRegion: Boolean get() = type == ElementType.REGION

/**
 * Los tipos propios de PixPin, que no vienen de Excalidraw.
 *
 * Se agrupan aquí para que quien recorra la escena pueda tratarlos aparte sin
 * enumerarlos a mano cada vez, y para que se vea de un vistazo cuáles son.
 */
val Element.isPixPinTool: Boolean
    get() = type == ElementType.MOSAIC || type == ElementType.SPOTLIGHT ||
        type == ElementType.SERIAL || type == ElementType.MEASURE ||
        type == ElementType.ARC || type == ElementType.REGION ||
        type == ElementType.SOLIDO

/** La cota, que se rotula sola con su medida. */
val Element.isMeasure: Boolean get() = type == ElementType.MEASURE

/** Los que se dibujan por su caja y no admiten ni relleno ni trazo propios. */
val Element.isBoxOverlay: Boolean
    get() = type == ElementType.MOSAIC || type == ElementType.SPOTLIGHT

/** La reglita a cuadros. Ver [EscalaGrafica]. */
val Element.isEscalaGrafica: Boolean get() = type == ElementType.ESCALA_GRAFICA

/**
 * Cuánto mide el lado de un bloque de mosaico, **en píxeles de escena**.
 *
 * Esto ha ido y ha vuelto, y conviene dejar escrito por qué acaba aquí.
 *
 * Primero fue un tamaño fijo. Luego se cambió a «tantos bloques en el lado
 * mayor» para que una captura enorme quedara igual de tapada que una pequeña —
 * el problema era que un grano de 32 px sobre una captura de 4000 dejaba
 * bloques tan finos que se leía lo de debajo.
 *
 * Pero contar bloques ata el grano al tamaño **del recuadro**, y eso hace algo
 * mucho peor de usar: al agrandar el recuadro para tapar un poco más, los
 * bloques crecen con él y lo que ya estaba tapado se convierte en cuatro
 * manchas. Tapar más no puede significar tapar distinto. Con un lado fijo, el
 * mosaico se comporta igual sea cual sea el recuadro y el grosor sigue siendo el
 * mando: cuatro tamaños de grano, del fino al gordo.
 *
 * Lo que resolvía el reparto por bloques —que sobre una captura enorme el grano
 * fino no tape— se resuelve mejor subiendo el grosor, que es justo el mando que
 * uno tiene en la mano.
 */
fun mosaicoGrano(strokeWidth: Double): Double = when {
    strokeWidth <= 1.0 -> 8.0
    strokeWidth <= 2.0 -> 16.0
    strokeWidth <= 4.0 -> 32.0
    else -> 64.0
}

/** El plano cartesiano, que es un instrumento y no un dibujo. Ver [Plano]. */
val Element.isPlano: Boolean get() = type == ElementType.PLANO

/** El marco: la hoja que decide qué se ve fuera del editor. */
val Element.isFrame: Boolean get() = type == ElementType.FRAME

/** La caja del boceto en volumen. Ver [ElementType.SOLIDO] y [Solido]. */
val Element.isSolido: Boolean get() = type == ElementType.SOLIDO

/** Los que admiten punta de flecha. */
val Element.hasArrowheads: Boolean
    get() = type == ElementType.ARROW || type == ElementType.LINE

/** Los que pueden llevar esquinas redondeadas. */
val Element.canChangeRoundness: Boolean
    get() = when (type) {
        ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.LINE,
        ElementType.ARROW -> true
        else -> false
    }

/** Los que pueden anclar una flecha. */
val Element.isBindable: Boolean
    get() = when (type) {
        ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE,
        ElementType.IMAGE, ElementType.TEXT -> true
        else -> false
    }

/** «transparent» es el valor que Excalidraw usa para «sin relleno». */
fun isTransparent(color: String): Boolean =
    color == Element.TRANSPARENT || color == "#ffffff00" || color.isEmpty()

/**
 * ¿El camino se cierra sobre sí mismo?
 *
 * Portado de `isPathALoop`. Decide si una línea o un garabato se pueden
 * rellenar y si se pueden agarrar por dentro: un lazo cerrado sí, una raya no.
 * El umbral escala con el zoom porque a poco aumento dos puntos separados se
 * ven pegados y el usuario los da por unidos.
 */
fun isPathALoop(points: List<Pt>?, zoomValue: Double = 1.0): Boolean {
    val pts = points ?: return false
    if (pts.size < 3) return false
    val first = pts.first()
    val last = pts.last()
    val distance = kotlin.math.hypot(first.x - last.x, first.y - last.y)
    return distance <= LINE_CONFIRM_THRESHOLD / zoomValue
}

/**
 * A cuánto se dan por unidos los dos extremos, en px de pantalla
 * (`LINE_CONFIRM_THRESHOLD`). Se divide por el zoom: muy acercado el umbral se
 * hace más pequeño en escena, que es lo que hace que cerrar un lazo dependa de
 * lo que **se ve** y no de la escala.
 */
private const val LINE_CONFIRM_THRESHOLD = 8.0
