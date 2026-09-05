package com.forge.pixpin.croquis3d

/**
 * **El motor de tintas: qué es una tinta, dicho en un solo sitio.**
 *
 * ## El problema que resuelve
 *
 * Las tintas nacieron una a una, y cada una se pintaba en su rama. Añadir la cuchilla fue
 * abrir un `when` de mil líneas y meter un caso más entre el rodillo y la luz; cambiar cómo
 * se comporta el rodillo fue tocar cuatro sitios lejanos —el pintado del que se está
 * trazando, el de los ya dibujados, el de la lámina del proyecto y el de la muestra de la
 * barra— y acordarse de los cuatro. Cuando algo se define en cuatro sitios, tarde o temprano
 * los cuatro dicen cosas distintas: el rodillo estuvo un tiempo opaco al proyectarlo a una
 * lámina y translúcido en la pantalla, y eso no fue un descuido, fue lo que tenía que pasar.
 *
 * Aquí una tinta es **un valor**: qué sección barre, cómo se apoya en la superficie, qué
 * tapa, si alumbra, si deja grano y si el selector la ensancha o la ahonda. Quien pinta pregunta; quien
 * proyecta a una lámina pregunta lo mismo; quien dibuja la muestra de la barra, también.
 *
 * ## Aislado a propósito
 *
 * Este archivo **no toca Android ni Compose**: son las señas de una tinta y las cuentas que
 * salen de ellas, nada más. Por eso se puede comprobar entero sin dispositivo y por eso una
 * tinta nueva se puede diseñar aquí —y probar aquí— antes de que nadie la pinte. Lo que
 * necesita un lienzo para pintarla vive en [Croquis3DLienzo]; lo que **es**, vive aquí.
 *
 * @see Pincel para los nombres que se guardan en el archivo del croquis.
 */
data class Tinta(
    /** Qué figura barre la punta a lo largo del recorrido. */
    val seccion: Seccion,
    /** Cómo se coloca esa figura respecto de la superficie sobre la que se traza. */
    val seApoya: ComoSeApoya,
    /** Qué hace el número del selector con ella. */
    val manda: LoQueMandaElSelector,
    /**
     * Cuánto tapa de fábrica, de cero a uno. **Antes de** lo que diga la opacidad del trazo,
     * que multiplica a esto.
     */
    val tapa: Double,
    /**
     * Si emite en vez de teñir: entonces se pinta sumando y **por encima del blanco** en las
     * pantallas que tengan margen. Ver el pintado de la luz en [Croquis3DLienzo].
     */
    val alumbra: Boolean,
    /**
     * Si varias pasadas suyas **se funden en una sola mano**.
     *
     * Es lo que distingue una tinta de pintar de una de dibujar: cruzar dos veces con el
     * rodillo tiene que dar el mismo tono que cruzar una, y cruzar dos veces con el rotulador
     * no. Se consigue volcando todas las pasadas del mismo plano de una vez, y por eso hay
     * que saberlo antes de pintar nada.
     */
    val unaSolaMano: Boolean,
    /** Si el pulso de la mano la engorda y la adelgaza. */
    val obedeceAlPulso: Boolean,
    /** Si deja grano en vez de mancha lisa, y de qué clase. Ver [Trama]. */
    val trama: Trama = Trama.NINGUNA
) {

    /** Lo gordo que sale de verdad, dado el número del selector. */
    fun gordoDe(delSelector: Double): Double = delSelector * manda.multiplica

    companion object {

        /**
         * **Las señas de cada punta.** Es la tabla de todas las tintas que hay.
         *
         * Leída de arriba abajo se ve de un vistazo en qué se parecen y en qué no, que es
         * exactamente lo que no se veía cuando cada una vivía en su rama. Y una tinta nueva
         * es una línea más aquí antes que un caso más en ningún sitio.
         */
        fun de(pincel: Pincel, punta: PuntaDelPincel = PuntaDelPincel()): Tinta =
            when (pincel.vigente) {
                // El rotulador: una barra redonda, con el pulso de la mano dentro.
                Pincel.REDONDO -> Tinta(
                    seccion = if (punta.tienePerfil) Seccion.DIBUJADA else Seccion.REDONDA,
                    seApoya = ComoSeApoya.MIRANDO_AL_QUE_MIRA,
                    manda = LoQueMandaElSelector.LO_ANCHO,
                    tapa = 1.0,
                    alumbra = punta.alumbra,
                    unaSolaMano = false,
                    obedeceAlPulso = punta.pulso,
                    trama = punta.trama
                )

                // El listón: la misma barra en escuadra. Lo que le da la arista es la
                // sección, no un adorno pintado encima.
                Pincel.CUADRADO -> Tinta(
                    seccion = if (punta.tienePerfil) Seccion.DIBUJADA else Seccion.CUADRADA,
                    seApoya = ComoSeApoya.MIRANDO_AL_QUE_MIRA,
                    manda = LoQueMandaElSelector.LO_ANCHO,
                    tapa = 1.0,
                    alumbra = punta.alumbra,
                    unaSolaMano = false,
                    obedeceAlPulso = false,
                    trama = punta.trama
                )

                // La cuchilla: una lámina **de pie sobre la hoja**, y el selector la ahonda.
                Pincel.CUCHILLA -> Tinta(
                    seccion = Seccion.LAMINA,
                    seApoya = ComoSeApoya.DE_PIE_EN_LA_HOJA,
                    manda = LoQueMandaElSelector.LO_HONDO,
                    tapa = 1.0,
                    alumbra = punta.alumbra,
                    unaSolaMano = false,
                    obedeceAlPulso = false,
                    trama = punta.trama
                )

                // El rodillo: una franja **tumbada en la hoja**, de pintura, y de una sola
                // mano por muy cruzadas que vayan las pasadas.
                //
                // **Salvo que sea de luz**, y entonces las dos cosas cambian a la vez. Llevaba
                // el alumbrar clavado a `false`, así que poner el material de luz en un rodillo
                // no hacía absolutamente nada — y era el único material que se le podía negar a
                // una punta, justo después de haber separado el material de la forma para que
                // eso no pasara. Y lo de la mano única cae con ello: una mano de pintura no se
                // suma consigo misma porque es pintura, pero **una luz sí se suma** —donde dos
                // tubos se cruzan brilla más—, que es de lo que está hecho el encendido.
                Pincel.RESALTADOR -> Tinta(
                    seccion = Seccion.LAMINA,
                    seApoya = ComoSeApoya.TUMBADA_EN_LA_HOJA,
                    manda = LoQueMandaElSelector.LO_ANCHO_DE_SOBRA,
                    tapa = 1.0,
                    alumbra = punta.alumbra,
                    unaSolaMano = !punta.alumbra,
                    obedeceAlPulso = false,
                    trama = punta.trama
                )

                // La luz: no es tinta, es un tubo encendido. Suma en vez de tapar.
                Pincel.LUZ -> Tinta(
                    seccion = if (punta.tienePerfil) Seccion.DIBUJADA else Seccion.REDONDA,
                    seApoya = ComoSeApoya.MIRANDO_AL_QUE_MIRA,
                    manda = LoQueMandaElSelector.LO_ANCHO,
                    tapa = 1.0,
                    alumbra = true,
                    unaSolaMano = false,
                    obedeceAlPulso = false,
                    trama = punta.trama
                )

                // Las retiradas se abren como la redonda. Ver [Pincel.vigente]: aquí no
                // pueden llegar, y si llegan es que alguien añadió una punta y no la puso en
                // esta tabla — así que se comporta como la de siempre en vez de reventar.
                else -> de(Pincel.REDONDO, punta)
            }
    }
}

/**
 * **El grano de una tinta**: qué marca deja dentro de su trazo.
 *
 * No cambia la forma del trazo ni su color: cambia **de qué parece estar hecho**. Es lo que
 * un croquis a mano dice con el rayado —esto está cortado—, con el punteado —esto es
 * tierra, arena, relleno— y con el cruzado —esto está en sombra—, y es información que no
 * cabe en un color: un croquis en blanco y negro las distingue las tres.
 *
 * Las marcas se estampan **a lo largo del recorrido y de través**, recortadas a la silueta
 * del trazo. Van así y no en una retícula de pantalla por lo mismo que la banda del rodillo
 * está tumbada en la hoja y no de cara al que mira: una trama pegada al cristal se queda
 * quieta al girar la vista y delata que el croquis es un dibujo plano. Pegada al trazo, se
 * escorza con él.
 *
 * Y su tamaño sale del ancho del trazo, no de un número aparte: una raya fina rayada con el
 * paso de una gorda es una raya de puntos sueltos. Ver [PASO_DE_LA_TRAMA].
 */
enum class Trama {
    /** Lisa: la tinta de siempre. */
    NINGUNA,

    /** Rayada de través: la sección cortada. */
    RAYADO,

    /** Rayada de través y a lo largo: la sombra. */
    CRUZADO,

    /** Punteada: la tierra, la arena, el relleno. */
    PUNTOS;

    /** Si hay algo que estampar. */
    val hayQuePintarla: Boolean get() = this != NINGUNA
}

/** Qué figura barre una punta a lo largo del recorrido. */
enum class Seccion {
    /** Un círculo: la barra de toda la vida. */
    REDONDA,

    /** Un cuadrado: el listón, con su arista. */
    CUADRADA,

    /**
     * Una lámina sin espesor: no barre un volumen, barre **una superficie**.
     *
     * Es lo que tienen en común el rodillo y la cuchilla, y lo que las separa de las demás:
     * las otras dejan un cuerpo con su bulto, estas dejan una cara. Lo único que cambia
     * entre las dos es cómo se apoya esa cara. Ver [ComoSeApoya].
     */
    LAMINA,

    /** La que venga dibujada a mano en la propia punta. Ver [PuntaDelPincel.perfil]. */
    DIBUJADA
}

/**
 * Cómo se coloca la sección respecto de la superficie sobre la que se traza.
 *
 * **Es la seña que más se nota y la que más veces se ha hecho mal.** Una tinta que mira
 * siempre al que mira es un cartel: al girar la vista gira con ella y se despega del dibujo.
 * Una que se apoya en la hoja se escorza y acaba viéndose de canto, que es lo que hace una
 * raya de pintura sobre una mesa.
 */
enum class ComoSeApoya {
    /** De cara a quien mira: la sección se planta en la pantalla. */
    MIRANDO_AL_QUE_MIRA,

    /** Tumbada en la hoja: la franja va pegada a la superficie. El rodillo. */
    TUMBADA_EN_LA_HOJA,

    /** De pie sobre la hoja: la lámina sale de ella por su perpendicular. La cuchilla. */
    DE_PIE_EN_LA_HOJA
}

/**
 * Qué hace el número del selector con la tinta.
 *
 * El mando es el mismo y la pregunta también —cuánta tinta quiero— pero el eje al que se
 * aplica no: en una barra es lo ancho, en una cuchilla es lo hondo. Y hay tintas que piden
 * números de otro orden: un rodillo a los grosores de dibujar no pinta, raya.
 */
enum class LoQueMandaElSelector(val multiplica: Double) {
    /** Lo ancho, tal cual: las tintas de dibujar. */
    LO_ANCHO(1.0),

    /** Lo ancho, pero de sobra: el rodillo, que se usa para dar manos de color. */
    LO_ANCHO_DE_SOBRA(Croquis3DControlador.ENGORDE_DEL_RESALTADOR),

    /** Lo hondo: la cuchilla, que baja lo que diga el mando y se ensancha con el recorrido. */
    LO_HONDO(HONDURA_DE_LA_CUCHILLA)
}

/**
 * Cuánto baja una cuchilla por cada unidad del selector.
 *
 * Cuatro: el selector llega a sesenta y cuatro, así que una cuchilla baja de un palmo a una
 * pared entera. Multiplica por lo mismo que el rodillo ensancha, y por la misma razón: los
 * números de dibujar una raya se quedan cortos en cuanto lo que se pide es una superficie.
 */
const val HONDURA_DE_LA_CUCHILLA = 4.0

/**
 * Cada cuántas veces el ancho del trazo se estampa una marca de la trama.
 *
 * Del ancho y no en píxeles: el paso tiene que salir de lo gordo que es la raya, porque una
 * trama es una proporción —tantas rayas por ancho— y no una medida. Con un paso fijo, una
 * raya fina sale de puntos sueltos y una gorda, maciza.
 */
const val PASO_DE_LA_TRAMA = 0.75

/**
 * Lo gordas que van las marcas de la trama, en veces el ancho del trazo.
 *
 * Finas: lo que tiene que leerse es el trazo con su grano, no una escalera de barrotes. Por
 * encima de un quinto del ancho, el rayado se come la tinta y lo que queda es una cebra.
 */
const val GRUESO_DE_LA_TRAMA = 0.13

/**
 * Lo por debajo de la tinta que va el grano, de cero a uno.
 *
 * La trama es la **misma** tinta más apretada, no otro color encima: un rayado negro sobre
 * una tinta azul son dos tintas y se lee como una mancha sucia. Oscureciendo la suya, lo que
 * se ve es relieve.
 */
const val HONDO_DE_LA_TRAMA = 0.42

/**
 * **El material de una tinta: de qué está hecha, no qué forma tiene.**
 *
 * Son dos preguntas y estaban mezcladas en una. La punta dice **la forma**: si la sección es
 * redonda, cuadrada, una lámina o la que se haya dibujado a mano. El material dice **de qué
 * está hecho lo que sale**: tinta lisa, luz encendida o tinta con grano. Antes la luz era
 * una punta más, así que elegirla tiraba la forma que uno tuviera —el listón, la cuchilla,
 * la sección dibujada— y la cambiaba por una redonda; y cambiar de forma apagaba la luz.
 * Separadas, cualquier punta puede ser de cualquier material: un listón encendido, un
 * rodillo rayado, una sección dibujada a mano punteada.
 *
 * Va con el color y no con la punta porque es ahí donde se decide: el material es la otra
 * mitad de «de qué color va esto». Ver [PuntaDelPincel.alumbra] y [PuntaDelPincel.trama].
 *
 * **Uno cada vez.** Una luz con grano no es nada: lo que se ve de un tubo encendido es el
 * resplandor, y un rayado dentro de un resplandor no llega a la pantalla. Así que elegir un
 * material quita el anterior, que además es lo que dice la palabra.
 */
enum class MaterialDeLaTinta(
    val alumbra: Boolean,
    val trama: Trama,
    /** Ver [PuntaDelPincel.plana]. */
    val plana: Boolean = false
) {
    /** Tinta lisa: la de siempre. */
    LISO(false, Trama.NINGUNA),

    /**
     * **Lápiz**: una raya lisa, sin bulto ni sombra, para anotar, apuntar guías y hacer
     * croquis rápidos encima de lo construido. Ver [PuntaDelPincel.plana].
     */
    LAPIZ(false, Trama.NINGUNA, plana = true),

    /** Luz: el tubo encendido, que suma en vez de tapar. */
    LUZ(true, Trama.NINGUNA),

    /** Rayado de través: la sección cortada. */
    RAYADO(false, Trama.RAYADO),

    /** Rayado en retícula: la sombra. */
    CRUZADO(false, Trama.CRUZADO),

    /** Punteado: la tierra, la arena, el relleno. */
    PUNTOS(false, Trama.PUNTOS);

    /** La misma punta, hecha de este material. */
    fun puestoEn(punta: PuntaDelPincel): PuntaDelPincel =
        punta.copy(alumbra = alumbra, trama = trama, plana = plana)

    /** Si es el que lleva esa punta. */
    fun esElDe(punta: PuntaDelPincel): Boolean =
        punta.alumbra == alumbra && punta.trama == trama && punta.plana == plana

    companion object {
        /**
         * El material que lleva una punta.
         *
         * Con la luz puesta manda la luz, aunque arrastre una trama de antes: es lo que se
         * va a ver. Ver arriba, lo de uno cada vez.
         */
        fun de(punta: PuntaDelPincel): MaterialDeLaTinta = when {
            punta.alumbra -> LUZ
            // El lápiz manda sobre la trama: una raya lisa no tiene dentro donde tramar.
            punta.plana -> LAPIZ
            punta.trama == Trama.RAYADO -> RAYADO
            punta.trama == Trama.CRUZADO -> CRUZADO
            punta.trama == Trama.PUNTOS -> PUNTOS
            else -> LISO
        }
    }
}
