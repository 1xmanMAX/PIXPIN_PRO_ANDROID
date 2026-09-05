package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot

/**
 * El croquis en el espacio: **dos herramientas y nada más**.
 *
 * ## De qué va
 *
 * Dibujar en tres dimensiones con el dedo tiene un problema que no tiene dibujar en dos:
 * la pantalla es plana y el espacio no, así que **un toque no dice dónde**. Un punto de la
 * pantalla es una recta entera del mundo, y hay que decidir en qué parte de esa recta cae
 * el trazo. Los programas de modelar lo resuelven con menús, planos de trabajo y sistemas
 * de coordenadas; aquí se resuelve con dos herramientas que se turnan.
 *
 * - **El lápiz** dibuja sobre un plano: el de la lámina que se toque, o —si no se toca
 *   ninguna— el de la propia pantalla. Nada de menús: el plano lo eliges tocando dónde.
 * - **La lámina** dibuja el plano. Trazas una raya y esa raya **se barre hacia dentro de
 *   la pantalla**, en la dirección en la que estás mirando. Desde donde estás no ves nada
 *   nuevo —el barrido va justo hacia donde miras, así que la lámina se ve de canto y
 *   parece la misma raya—; en cuanto giras, ahí está el plano.
 *
 * Con esas dos se construye todo: se pone una lámina, se gira, se dibuja encima, se pone
 * otra lámina en otra dirección, se vuelve a girar. Es la forma de croquizar en el espacio
 * que no necesita aprender nada.
 *
 * ## Y por qué no hay mallas ni sólidos
 *
 * Porque no es un programa de modelar. Lo que se hace aquí es **un boceto que se puede
 * mirar desde otro lado**, que es lo que a un croquis a mano le falta siempre. Trazos y
 * láminas dan eso; una malla traería normales, materiales, recorte y orden de triángulos
 * —y ninguna de esas cosas hace que se dibuje mejor.
 */
@Serializable
data class Croquis(
    val trazos: List<Trazo3D> = emptyList(),
    val laminas: List<Lamina3D> = emptyList(),
    /**
     * De qué color es el espacio. **Nulo es el del tema**, que es lo que viene de fábrica.
     *
     * Va con el croquis y no con la aplicación porque es una decisión del dibujo: un
     * apunte a lápiz pide papel, unas líneas de color piden pizarra, y un croquis que se va
     * a enseñar pide el fondo que le vaya. Guardado con él, se abre como se dejó.
     *
     * Nulo y no un blanco puesto a mano: así el que no lo toque sigue teniendo el fondo del
     * tema, que se pone claro u oscuro con el del sistema. Un color fijo se quedaría blanco
     * de noche.
     */
    val colorDelFondo: String? = null,
    /** El eje sobre el que se refleja lo que se dibuja, si se ha puesto uno. Ver [Espejo3D]. */
    val espejo: Espejo3D? = null,
    /** Los grupos que hay, en el orden en que se hicieron. Ver [Grupo3D]. */
    val grupos: List<Grupo3D> = emptyList(),
    /** Las imágenes puestas en el espacio. Ver [Imagen3D]. */
    val imagenes: List<Imagen3D> = emptyList(),
    /** Las vistas congeladas, en el orden en que se guardaron. Ver [Vista3D]. */
    val vistas: List<Vista3D> = emptyList(),
    /** Dónde está el sol, si se ha encendido. Ver [Sol3D]. */
    val sol: Sol3D? = null,
    /** Las tintas guardadas, para volver a usarlas. Ver [PuntaGuardada]. */
    val puntas: List<PuntaGuardada> = emptyList(),
    /**
     * Los colores que se han ido usando, del último al primero.
     *
     * Una paleta fija de seis colores es la paleta de quien hizo la aplicación; los que uno
     * usa de verdad son otros, y son pocos: el gris del hormigón de este proyecto, el rojo
     * de las cotas, el azul de lo que va debajo. Guardando los que se eligen, la fila de al
     * lado del aro **se convierte en la paleta de este croquis** sin que nadie la monte.
     */
    val favoritos: List<String> = emptyList(),
    /**
     * **El interruptor de las luces**: si lo que alumbra alumbra, y cuánto. Ver [Luces3D].
     */
    val luces: Luces3D = Luces3D(),
    /**
     * **Si las hojas llevan pauta**: su retícula y sus cuadros de medir.
     *
     * Puesta de fábrica, porque es lo que convierte una hoja en una mesa de dibujo: sin ella
     * no se sabe cómo se dobla la superficie ni cuánto mide nada de lo que hay encima. Pero
     * hay dos veces en las que sobra y molesta bastante — cuando lo dibujado ya llena la hoja
     * y la pauta se le mete entre las líneas, y al enseñar el croquis a alguien, donde lo que
     * importa es el dibujo y no la mesa donde se hizo—. Así que se puede quitar.
     *
     * Y va con el croquis, no con la aplicación: es una decisión de este dibujo, como el
     * color del fondo, y se abre como se dejó.
     */
    val pautaDeLasHojas: Boolean = true,
    /**
     * **La apertura del objetivo**: cuánto se desenfoca lo que está lejos del punto enfocado.
     *
     * Cero es lo de siempre —todo nítido, que es como se dibuja—. Abriéndola, lo que está por
     * delante y por detrás del plano de enfoque se va poniendo blando, como en una foto con el
     * diafragma abierto. Y sirve para lo mismo que en una foto: **decir qué es lo importante**.
     * Un croquis lleno se lee mal porque todo grita igual; con una apertura corta, la pieza
     * que se está enseñando queda limpia y lo demás se convierte en el sitio donde está.
     *
     * Va con el croquis porque es de la vista de este croquis, como el color del fondo.
     */
    val apertura: Double = 0.0,
    /**
     * **En qué punto está enfocado**, o el centro de la vista si no hay ninguno.
     *
     * Un punto del mundo y no una distancia: una distancia solo quiere decir algo desde una
     * cámara, así que al girar la vista el enfoque se iría a otra parte. Clavado a un punto,
     * se puede dar la vuelta al croquis y lo enfocado sigue siendo lo mismo. Ver [apertura].
     */
    val enfoque: Pt3? = null,
    /**
     * **El pixelado**: de cero —nítido— a uno, donde cada punto de la pantalla es un cuadro.
     *
     * No es un filtro puesto encima: el croquis **se pinta de verdad a menos resolución** y se
     * amplía sin suavizar, que es exactamente lo que hacía una pantalla de las de antes. Por
     * eso los cuadros salen alineados entre sí y no como una cuadrícula pegada al cristal, y
     * por eso girar la vista los mueve como movería cualquier otra cosa del dibujo.
     */
    val pixelado: Double = 0.0,
    /**
     * **La luna**: la otra luz, la de noche. Ver [Sol3D].
     *
     * Es un sol con otros modales. Está más baja casi siempre, y su sombra no es la sombra
     * negra y corta del mediodía sino **una sombra larga, azulada y floja** —lo que hace la
     * luna es exactamente eso—. Va aparte del sol y no en vez de él: un croquis puede querer
     * las dos —el estudio de una fachada a las siete y a las once— y apagar una es apagarla,
     * no perder la otra.
     */
    val luna: Sol3D? = null
) {
    val vacio: Boolean get() = trazos.isEmpty() && laminas.isEmpty() && imagenes.isEmpty()

    /**
     * Lo que se ve, que es lo único que hay que encuadrar y ordenar por hondura.
     *
     * **Sin las hojas, mientras haya algo dibujado.** Una hoja recta no tiene tamaño —es un
     * plano, llega hasta donde haga falta—, así que encuadrando por sus esquinas lo dibujado
     * se quedaría en un punto en mitad de la pantalla. Con el croquis vacío sí valen: es lo
     * único que hay a lo que mirar.
     */
    fun puntos(): List<Pt3> {
        val dibujado = trazos.filterNot { it.oculto }.flatMap { it.puntos } +
            imagenes.filterNot { it.oculto }.flatMap { it.esquinas }
        return dibujado.ifEmpty { laminas.flatMap { it.esquinas() } }
    }

    /** Si hay algo escondido, para poder ofrecer enseñarlo otra vez. */
    val hayOcultos: Boolean get() = trazos.any { it.oculto } || imagenes.any { it.oculto }
}

/**
 * **La punta con la que se dibuja, tal y como se la haya dejado uno.**
 *
 * Las tres tintas dicen *de qué* está hecha la raya —tinta lisa, listón, banda que no
 * tapa—, pero no cómo es la punta que la deja. Y eso es lo que uno acaba queriendo tocar:
 * el listón un poco más largo que ancho para que las verticales salgan gruesas, el rodillo
 * más estrecho para una franja fina, el rotulador sin pulso para que una arista salga
 * pareja. Antes eso eran cinco puntas fijas de fábrica, la mitad de las cuales no le valían
 * a nadie; ahora son **cuatro números** sobre las tres que hay.
 *
 * - [ancho] y [largo] son **lo que mide la sección de ancho y de alto**. Con los dos
 *   iguales, la punta es la que se dibujó; separándolos se aplasta o se estira, que es lo
 *   que convierte un círculo en una elipse y un cuadrado en un rectángulo. Y el ancho, en
 *   las puntas sin sección dibujada, es lo que las hace finas o gordas.
 * - [angulo] es hacia dónde apunta ese canto **en la pantalla**, en radianes. Cero es
 *   tumbada —el canto horizontal—, así que bajando se pinta gordo y yendo de lado, fino.
 * - [pulso] es si el trazo lleva la mano dentro: engorda al apretar y adelgaza al correr.
 *   Apagado, la raya sale pareja, que es lo que se quiere en una arista o en un eje.
 */
/**
 * Un rasgo de los que forman una tinta dibujada a mano.
 *
 * Los puntos van dentro del cuadrado de lado dos centrado en el cero —sin tamaño, que lo
 * pone el grueso del trazo— y **no se recolocan**: se guardan donde se trazaron, para que
 * dos rasgos dibujados uno al lado del otro sigan estando uno al lado del otro.
 */
@Serializable
data class TrazoDeLaPunta(
    val puntos: List<Pt>,
    val color: String,
    /** Lo gordo que va el rasgo, en tanto por uno del tamaño de la marca. */
    val grosor: Double = 0.12
)

/**
 * **Las secciones de fábrica**: de qué está cortada la punta.
 *
 * Es lo primero que se elige de un pincel y lo que más se nota al trazar. Una punta redonda
 * deja el mismo grueso vaya por donde vaya; una cuadrada deja esquinas; una rectangular
 * pinta ancho de través y fino a lo largo; un rombo afila las dos direcciones; una cuña deja
 * un lado limpio y otro sucio.
 *
 * Van en el cuadrado de lado dos centrado en el cero, sin tamaño: el tamaño lo pone el
 * grosor, y lo ancha y lo larga las estiran. Se puede además dibujar una a mano, y entonces
 * manda esa. Ver [PuntaDelPincel.perfil].
 */
enum class SeccionDePunta {
    CIRCULO, CUADRADO, RECTANGULO, ROMBO, CUÑA;

    /** Los puntos de la sección, en el mismo sentido para todas. */
    fun puntos(): List<Pt> = when (this) {
        CIRCULO -> (0 until PUNTOS_DEL_CIRCULO).map {
            val t = 2 * Math.PI * it / PUNTOS_DEL_CIRCULO
            Pt(kotlin.math.cos(t), kotlin.math.sin(t))
        }
        CUADRADO -> listOf(Pt(-1.0, -1.0), Pt(1.0, -1.0), Pt(1.0, 1.0), Pt(-1.0, 1.0))
        RECTANGULO -> listOf(Pt(-1.0, -0.3), Pt(1.0, -0.3), Pt(1.0, 0.3), Pt(-1.0, 0.3))
        ROMBO -> listOf(Pt(-1.0, 0.0), Pt(0.0, -1.0), Pt(1.0, 0.0), Pt(0.0, 1.0))
        CUÑA -> listOf(Pt(-1.0, -0.7), Pt(1.0, 0.0), Pt(-1.0, 0.7))
    }

    /** Cómo se enseña en su botón. */
    val glifo: String
        get() = when (this) {
            CIRCULO -> "●"
            CUADRADO -> "■"
            RECTANGULO -> "▬"
            ROMBO -> "◆"
            CUÑA -> "◗"
        }

    companion object {
        /**
         * Con cuántos lados se hace un círculo.
         *
         * Estuvo en dieciséis y se le veían: un trazo gordo mirado de cerca era un tubo de
         * dieciséis caras, no un cilindro. Treinta y dos es donde la esquina deja de
         * distinguirse de la curva, y como esto es un perfil que se calcula una vez —no algo
         * que se recorra por fotograma— el doble de puntos no cuesta nada.
         */
        private const val PUNTOS_DEL_CIRCULO = 32

        /** La sección con la que nace cada tinta, para poder enseñarla sin haber elegido. */
        fun deLaTinta(pincel: Pincel): SeccionDePunta = when (pincel.vigente) {
            Pincel.CUADRADO -> CUADRADO
            Pincel.RESALTADOR -> RECTANGULO
            else -> CIRCULO
        }
    }
}

@Serializable
data class PuntaDelPincel(
    val ancho: Double = 1.0,
    val largo: Double = 1.0,
    val angulo: Double = 0.0,
    val pulso: Boolean = true,
    /**
     * **El perfil de la punta**: cómo es su sección, dibujada a mano.
     *
     * Es el mando de verdad, y los otros son atajos suyos: una punta redonda, un listón y
     * una plumilla no son tres cosas distintas, son tres secciones. Dibujando la sección se
     * pueden tener las que no vienen de fábrica —una punta en cuña, una en uve, una
     * estrellada, un pincel plano con una esquina comida— y el trazo sale de **barrer esa
     * figura a lo largo del recorrido**, que es literalmente lo que hace una punta contra el
     * papel.
     *
     * Los puntos van dentro del cuadrado de lado dos centrado en el cero, así que la figura
     * no trae tamaño: el tamaño lo pone el grosor, y [ancho] y [largo] la estiran. A nulo,
     * la sección es la que le toque a su tinta.
     */
    val perfil: List<Pt>? = null,
    /**
     * **La tinta dibujada a mano**: la marca que deja la punta, tal cual se trazó.
     *
     * Es el paso siguiente a la sección. Una sección es una figura maciza y da tintas
     * macizas; una marca dibujada puede ser **lo que sea** —dos rayas cruzadas, un rasgo con
     * un pelo al lado, tres puntos de tres colores— y el trazo sale de estamparla a lo largo
     * del recorrido, girada con él. Eso es lo que hace que se noten las curvas de la punta y
     * que dos tintas distintas se distingan de verdad.
     *
     * Va **con sus colores dentro**: cada rasgo lleva el suyo, así que una tinta puede tener
     * tres. Por eso lo dibujado no se cierra ni se rellena —eso convertía cualquier rasgo en
     * una mancha—: se guarda como se trazó, dentro del cuadrado de lado dos.
     */
    val dibujo: List<TrazoDeLaPunta>? = null,
    /**
     * Cuánto se le hace caso al pulso de la mano, de cero a uno y pico.
     *
     * Es el mando fino de lo que el interruptor decía a lo bruto: en cero la raya sale
     * pareja —lo que uno quiere en una arista o un eje—, en uno responde como responde la
     * mano, y por encima exagera, que sirve para que se note en un croquis pequeño.
     */
    val sensibilidad: Double = 1.0,
    /**
     * Si el trazo **alumbra**, sea la tinta que sea.
     *
     * La luz era una tinta más y eso la dejaba coja: lo que uno quiere no es «dibujar con
     * luz», es que **esta punta** —la suya, con su sección y su ancho— salga encendida. Con
     * esto puesto, cualquier pincel se pinta como un tubo: resplandor alrededor, el color
     * vivo y el filamento blanco por el centro, sumando en vez de tapar.
     */
    val alumbra: Boolean = false,
    /**
     * **El grano de la tinta**: si en vez de dejar una mancha lisa deja una trama.
     *
     * Es el tercer material, y el que faltaba. Una tinta lisa y una tinta encendida son las
     * dos formas de dejar color; una tinta tramada es la de **decir de qué está hecho algo
     * sin dibujarlo**, que es la mitad de lo que hace un croquis a mano: el rayado de una
     * sección cortada, el punteado de la tierra, el cruzado de la sombra. Sin ella, esas tres
     * cosas se hacían pasando el rodillo veinte veces con la mano.
     *
     * La trama va **dentro del trazo**, no encima: se recorta a su silueta y sus marcas van
     * de través a por donde va la raya, así que se escorza y se gira con el dibujo como
     * cualquier otra cosa del croquis. Ver [Trama].
     */
    val trama: Trama = Trama.NINGUNA,
    /**
     * **El lápiz: una raya y nada más.**
     *
     * Todas las demás puntas barren su sección por el espacio, y de ahí les vienen el bulto,
     * el afilado de las puntas y el lomo. Eso es lo que uno quiere cuando está **construyendo**
     * algo. Pero un croquis también se anota: una cota, una flecha, una guía, un «esto va
     * aquí» — y para eso el bulto estorba, porque compite con lo dibujado y porque una guía
     * no es una pieza que esté ahí, es una raya escrita encima.
     *
     * Con esto puesto el trazo se pinta como en el lienzo plano: **una raya lisa del grueso
     * que se pidió**, sin volumen, sin sombra y sin responder al pulso. Sigue estando en el
     * espacio —se gira con el croquis y se tapa con lo que tiene delante—, pero se lee como
     * una anotación y no como una pieza. Y es la punta más barata que hay, que es lo que
     * conviene a lo que se traza a puñados.
     */
    val plana: Boolean = false
) {
    /** Si la punta es de canto: entonces el trazo depende de hacia dónde vaya. */
    val deCanto: Boolean get() = largo > 1.05

    /** Si se le ha dibujado una sección propia y hay que barrerla. */
    val tienePerfil: Boolean get() = (perfil?.size ?: 0) >= 3

    /** Si se le ha dibujado una marca a mano y hay que estamparla. */
    val tieneDibujo: Boolean get() = !dibujo.isNullOrEmpty()
}

/**
 * **Una tinta guardada**: la punta, su color, su grueso y lo que tapa, juntos.
 *
 * Una tinta no es solo la forma de la punta: es esa forma **con ese color, ese grueso y esa
 * transparencia**. Guardando las cuatro cosas, volver a una tinta es un toque; guardando
 * solo la forma, habría que reconstruir las otras tres de memoria cada vez, que es
 * exactamente lo que hace que nadie use una galería de pinceles.
 */
@Serializable
data class PuntaGuardada(
    val id: String,
    val punta: PuntaDelPincel,
    val color: String,
    val grosor: Double,
    val opacidad: Double,
    /** Con qué punta se pinta: una tinta guardada puede ser un listón o un rodillo. */
    val pincel: Pincel = Pincel.REDONDO,
    /**
     * Si viene de fábrica. Las de fábrica **no se tiran**.
     *
     * La galería empezaba vacía, y una galería vacía no dice para qué sirve. Con las cuatro
     * de siempre dentro —rotulador, listón, rodillo y luz— se abre y ya se entiende: son
     * ejemplos y son el sitio al que volver cuando uno se ha inventado una tinta rara y
     * quiere el rotulador de vuelta.
     */
    val deFabrica: Boolean = false
)

/**
 * **Las luces del croquis, con su llave de paso.**
 *
 * Hay una tinta que alumbra —y cualquier punta se puede poner a alumbrar—, y eso es una
 * decisión que se toma **trazo a trazo y para siempre**: lo que se dibujó encendido se queda
 * encendido. En un croquis con cuatro tubos de neón puestos, ver cómo queda la pieza sin
 * ellos exigía repintar los cuatro y volver a subirlos uno a uno, que es de las cosas que no
 * hace nadie.
 *
 * Con la llave de paso es un toque. Es el mismo gesto que esconder una capa —se apaga, se
 * mira, se enciende— y por eso se parece a aquello: **lo apagado sigue estando ahí**, con su
 * color y su grueso, solo que sin alumbrar. Apagadas, un trazo de luz es tinta lisa del color
 * que se le puso, que es exactamente lo que es una luz apagada.
 *
 * Y con [fuerza], porque encender no es una postura sola: un neón de rótulo y la raya con la
 * que se marca una cota no gritan igual, y el mismo croquis se enseña de día y de noche.
 * Multiplica a lo que lleve cada trazo, así que la mezcla que uno montó se conserva: se sube
 * y se baja **entera**.
 *
 * Va con el croquis y no con la aplicación por lo mismo que el color del fondo: es una
 * decisión de este dibujo, y se abre como se dejó.
 */
@Serializable
data class Luces3D(
    val encendidas: Boolean = true,
    /** Cuánto alumbran, de cero a dos. Uno es como se dibujó cada trazo. */
    val fuerza: Double = 1.0
) {
    /** Lo que multiplica a la luz de cada trazo. Cero es apagado, y apagado es tinta lisa. */
    val cuanto: Double get() = if (encendidas) fuerza.coerceIn(0.0, 2.0) else 0.0
}

/**
 * **El sol**: de dónde viene la luz, para que lo dibujado tire sombra.
 *
 * ## Para qué, en un croquis
 *
 * Un croquis en el espacio dice cómo es una cosa; la sombra dice **dónde está**. Sin ella,
 * un trazo flotando a un metro del suelo y otro apoyado en él se ven exactamente igual desde
 * casi cualquier ángulo, y hay que girar para saber cuál es cuál. Con la sombra puesta se
 * ve de un vistazo, sin girar nada — que es la mitad del trabajo de mirar un croquis—. Y de
 * paso sirve para lo que sirve un estudio de soleamiento: mover el sol y ver hasta dónde
 * llega la sombra del alero a las cinco de la tarde.
 *
 * ## Dos números y ya
 *
 * El [azimut] es por dónde sale —girando alrededor de la vertical— y la [altura] cuánto ha
 * subido sobre el horizonte. No hay fecha ni latitud a propósito: esto no es un calculador
 * solar, es una luz que se mueve con el dedo hasta que la sombra dice lo que uno quiere
 * mirar. La sombra cae sobre el suelo, que es el plano que ya está ahí de referencia.
 */
@Serializable
data class Sol3D(
    /** Por dónde sale, en radianes alrededor de la vertical. */
    val azimut: Double = -Math.PI / 4,
    /** Cuánto ha subido sobre el horizonte, en radianes. Cero es rasante. */
    val altura: Double = Math.PI / 4
) {
    /**
     * Hacia dónde viajan los rayos: **del sol al suelo**, así que bajando.
     *
     * Con el sol muy bajo la sombra se estira hasta el infinito, que es lo que hace un sol
     * muy bajo; se le deja un mínimo de altura para que «infinito» no sea una división por
     * cero.
     */
    val rayo: Pt3
        get() {
            val alta = altura.coerceIn(ALTURA_MINIMA, Math.PI / 2)
            return normalizado(
                Pt3(
                    kotlin.math.sin(azimut) * kotlin.math.cos(alta),
                    kotlin.math.cos(azimut) * kotlin.math.cos(alta),
                    -kotlin.math.sin(alta)
                )
            )
        }

    /**
     * Dónde cae la sombra de un punto **en el suelo**, o nada si el rayo no llega.
     *
     * El suelo es el plano `z = 0`, el mismo de la retícula: es el que ya está ahí como
     * referencia, y una sombra sobre otra cosa exigiría decidir sobre qué —y en un croquis
     * eso es una pregunta que nadie quiere contestar—. Lo que está por debajo del suelo no
     * tira sombra hacia arriba: eso no es una sombra, es un reflejo.
     */
    fun sombraDe(p: Pt3): Pt3? {
        val d = rayo
        if (d.z >= -1e-6 || p.z < 0) return null
        val cuanto = -p.z / d.z
        return Pt3(p.x + d.x * cuanto, p.y + d.y * cuanto, 0.0)
    }

    companion object {
        /** Lo más rasante que se deja el sol: por debajo, la sombra se va al infinito. */
        private const val ALTURA_MINIMA = 0.08
    }
}

/**
 * **Una vista congelada**: desde dónde se estaba mirando, guardado para volver.
 *
 * Croquizando en el espacio uno encuentra el sitio bueno —el ángulo desde el que la pieza se
 * entiende, el alzado exacto donde las medidas cuadran, el escorzo que enseña el hueco— y lo
 * pierde en cuanto gira para dibujar otra cosa. Volver a él a pulso no se puede: son dos
 * ángulos, un aumento, un punto al que se mira y una lente, y todos a la vez.
 *
 * Se guarda **la cámara entera**, así que volver es volver de verdad y no aproximadamente. Y
 * se guarda con el croquis: las vistas de un dibujo son del dibujo, y sirven la semana que
 * viene igual que hoy.
 */
@Serializable
data class Vista3D(
    val id: String,
    val nombre: String,
    val camara: Camara3D,
    /**
     * Qué trozo de la pantalla se congeló, en tanto por uno, o nada para la vista entera.
     *
     * **Encuadrar es la mitad de congelar una vista.** Lo que uno quiere llevarse a la
     * lámina casi nunca es la pantalla entera: es el detalle del nudo, o la pieza sin el
     * medio metro de aire que hay al lado. Marcando el rectángulo se lleva eso, y la lámina
     * sale con **esa** proporción y a esa escala, no recortada después.
     */
    val recorte: Recorte? = null
)

/**
 * **La cámara de una vista, ya puesta en su recorte.**
 *
 * Un recorte no se aplica cortando la imagen después: se aplica **mirando ahí**. Se lleva el
 * centro de la cámara al punto que había en el centro del rectángulo y se acerca lo que haga
 * falta para que el rectángulo llene el ancho. Así la lámina no es un trozo de una foto: es
 * la misma escena mirada de cerca, con todo el detalle que tenga.
 *
 * Sin recorte, la cámara tal cual.
 */
fun camaraEncuadrada(vista: Vista3D, ancho: Double, alto: Double): Camara3D {
    val recorte = vista.recorte?.takeIf { it.sirve } ?: return vista.camara
    val camara = vista.camara
    val plano = Plano3D(camara.centro, camara.adelante)
    val centro = plano.corte(
        camara.rayo(Pt(recorte.centroX * ancho, recorte.centroY * alto), ancho, alto)
    )?.first ?: camara.centro
    return camara.copy(centro = centro, zoom = camara.zoom / recorte.ancho)
}

/** Un rectángulo de la pantalla, en tanto por uno de su ancho y de su alto. */
@Serializable
data class Recorte(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val ancho: Double get() = abs(x1 - x0)
    val alto: Double get() = abs(y1 - y0)
    val centroX: Double get() = (x0 + x1) / 2
    val centroY: Double get() = (y0 + y1) / 2

    /** Si vale la pena: un recorte de dos píxeles es un toque, no un encuadre. */
    val sirve: Boolean get() = ancho > 0.05 && alto > 0.05
}

/**
 * **Una imagen puesta en el espacio**: una foto, un plano escaneado, una referencia.
 *
 * Es lo que convierte un croquis en un croquis *de algo*: se pone la foto de la pieza —o el
 * plano de la planta, o la captura de lo que sea— tumbada en la hoja, y se dibuja encima
 * calcando lo que hace falta. A mano eso es papel de calco, y en el espacio es lo mismo
 * pero pudiendo girar para ver si lo calcado cuadra.
 *
 * Se guarda **por sus cuatro esquinas** y no por un centro con su tamaño y sus ángulos, y
 * ahí está la gracia: mover, girar, escalar y voltear una imagen pasa a ser exactamente lo
 * mismo que hacerle eso a un trazo —mover sus puntos—, así que el mando de lo elegido la
 * maneja sin una sola línea aparte. Y una imagen escorzada por la vista sale escorzada sola,
 * porque sus esquinas se proyectan como cualquier otra cosa del croquis.
 *
 * El orden de las esquinas es el de leer: arriba a la izquierda, arriba a la derecha, abajo
 * a la derecha, abajo a la izquierda.
 */
@Serializable
data class Imagen3D(
    val id: String,
    /** Dónde está el archivo, en el almacén privado de la aplicación. */
    val ruta: String,
    val esquinas: List<Pt3>,
    val opacidad: Double = 1.0,
    val oculto: Boolean = false,
    val grupo: String? = null
)

/** Un trazo del lápiz: por dónde pasó, en el mundo, y con qué punta. */
@Serializable
data class Trazo3D(
    val id: String,
    val puntos: List<Pt3>,
    val color: String,
    /** Lo gordo que se pidió, en dp. Es el número del selector, y con él se vuelve a medir. */
    val grosor: Double,
    /** Con qué sección. Los croquis guardados antes de que hubiera puntas traen la redonda. */
    val pincel: Pincel = Pincel.REDONDO,
    /**
     * **Lo que mide de gordo en el mundo**, en dp a aumento uno.
     *
     * Un trazo no es una raya pintada sobre el cristal: es una barra que está ahí, en el
     * espacio, con su grueso. Acercándose se ve más gorda, igual que se ve más largo lo que
     * mide un metro. Con el grosor medido en la pantalla pasaba lo contrario —lo dibujado
     * crecía y las líneas no—, así que al acercarse el croquis se iba quedando de alambre y
     * no había forma de mirar de cerca cómo era un trazo por dentro.
     *
     * **Es el número del selector tal cual, sin dividir por el aumento.** Estuvo dividido, y
     * eso ataba el grueso a la pantalla del momento en que se trazó: con el mismo número
     * puesto, una raya hecha de cerca salía mucho más fina que una hecha de lejos, y al
     * alejarse para ver las dos juntas no se parecían en nada. Lo que dice el selector es
     * **cuánto mide la raya en el croquis**, y eso no depende de desde dónde se la esté
     * mirando: la misma cifra da la misma raya, se dibuje de cerca o de lejos.
     *
     * Lo que sí cambia con el aumento es cómo se ve, que es lo que tiene que cambiar:
     * acercándose se ve gorda, como cualquier cosa que está ahí.
     *
     * Nulo en los croquis de antes de esto: esos se siguen pintando a lo ancho de la
     * pantalla, que es como se dibujaron y como su autor los recuerda.
     */
    val calibre: Double? = null,
    /**
     * Cuánto apretaba la mano en cada punto, de cero a uno. Uno por punto.
     *
     * Es lo que hace que un trazo a mano alzada se vea a mano alzada: apretando engorda y
     * corriendo adelgaza. Lo manda el lápiz óptico cuando el aparato lo sabe medir; con el
     * dedo, o con un lápiz que no lo diga, llega todo a uno y el pulso se saca entonces de
     * **lo rápido que iba la mano**, que se lee en lo separados que quedaron los puntos.
     *
     * Nulo en los croquis de antes: esos se pintan de grosor parejo, como se dibujaron.
     */
    val presiones: List<Double>? = null,
    /**
     * **La hora de cada punto**, en segundos desde la primera muestra — la cuarta lista
     * paralela, aligerada por los MISMOS índices que puntos, presiones y normales.
     *
     * Es además **el discriminador del motor nuevo**: un trazo con tiempos se pinta fijo al
     * mundo (espina suave, marcos de rotación mínima, anchos y luz del mundo); uno sin
     * ellos —todo lo guardado hasta ahora— se pinta como se dibujó, byte a byte. Ninguna
     * migración: la regla es por trazo, y las versiones viejas de la aplicación abren los
     * croquis nuevos tirando este campo sin enterarse.
     */
    val tiempos: List<Double>? = null,
    /**
     * Cuánto deja ver lo que hay debajo: uno tapa del todo y cero no se ve.
     *
     * Va por trazo y no por color porque es una decisión del trazo —esta línea es de
     * construcción y esta otra es la buena—, y así se puede repintar lo elegido sin perder
     * lo transparente que iba.
     */
    val opacidad: Double = 1.0,
    /**
     * La normal de la superficie sobre la que se trazó. **La necesita el rodillo.**
     *
     * Un trazo de punta redonda es un tubo: se ve igual de gordo desde donde se mire, así
     * que basta con pintarlo de frente a la cámara. Una banda de rodillo no: **está tumbada
     * sobre la hoja**, y girando la vista tiene que escorzarse y acabar viéndose de canto,
     * como una raya de pintura sobre una pared. Sin saber sobre qué plano se dio la pasada,
     * lo único que se puede hacer es ponerla siempre de cara al que mira, y entonces la
     * banda gira con la cámara y parece que se despega del dibujo.
     *
     * Nula en los croquis de antes de esto: esos se pintan de cara, como se pintaban.
     */
    val normal: Pt3? = null,
    /**
     * La normal de la superficie **en cada punto**, cuando la superficie es curva.
     *
     * Nula en una hoja plana, que es el caso corriente: ahí [normal] vale para todo el trazo
     * y guardar la misma cosa cien veces sería engordar el archivo por nada.
     *
     * En una bola o en una hoja doblada no vale. Las tintas que son una superficie —el
     * rodillo, la cuchilla— sacan sus dos bordes apartándose de la línea **por la
     * perpendicular de la hoja**, y con una sola perpendicular para todo el trazo esa cuenta
     * es falsa en cuanto la hoja se curva: donde la superficie ha girado noventa grados, la
     * banda se aparta hacia dentro de la bola y se pliega sobre sí misma. Es el «se pinta
     * incompleto» de subrayar sobre una esfera: no faltaba banda, estaba doblada.
     */
    val normales: List<Pt3>? = null,
    /**
     * Si está escondido: sigue en el croquis pero no se pinta ni se toca.
     *
     * Es lo que hace falta para dibujar dentro de algo: se aparta lo que tapa, se trabaja
     * en lo de debajo y se vuelve a enseñar. Borrarlo y volver a dibujarlo no es lo mismo,
     * y girar la vista para ver por un hueco es pelear con el croquis en vez de dibujarlo.
     */
    val oculto: Boolean = false,
    /**
     * Cuánto alumbra, de cero a uno. **Solo lo mira la tinta de luz.**
     *
     * En cero es tinta normal —el color liso que se eligió, sin resplandor ni filamento— y
     * en uno es el tubo encendido del todo. En medio se va abriendo: el resplandor crece y
     * se carga, el color se aviva y el filamento blanco aparece. Sirve para lo que un
     * interruptor de luz: no todo lo que se marca con luz tiene que gritar igual.
     */
    val luz: Double = 1.0,
    /**
     * **De qué color está el tubo cuando no alumbra.**
     *
     * Un tubo de neón tiene dos colores y no uno: apagado es el cristal —un gris verdoso, un
     * ámbar sucio, lo que sea que le pusieran— y encendido es el gas, que no tiene por qué
     * parecerse en nada. Con un solo color, apagar una luz dejaba el mismo color más flojo,
     * y eso no es un tubo apagado: es la misma luz con menos corriente.
     *
     * Nulo quiere decir «el mismo, más apagado», que es como se comportaba y como se abren
     * los croquis de antes. Ver [Croquis3DLienzo].
     */
    val colorApagada: String? = null,
    /** A qué grupo pertenece, si es que a alguno. Ver [Grupo3D]. */
    val grupo: String? = null,
    /** Con qué punta se dejó, tal y como estaba puesta. Ver [PuntaDelPincel]. */
    val punta: PuntaDelPincel = PuntaDelPincel(),
    /**
     * Hacia dónde miraba el canto de la punta **en el mundo**, no en la pantalla.
     *
     * Sin esto, una punta de canto está clavada al cristal: se gira la vista y el mismo
     * trazo cambia de gordo a fino, como si la plumilla se hubiera girado sola. Y eso está
     * mal aquí: lo dibujado está en el espacio y no se mueve porque uno se mueva. Guardando
     * el eje de la punta dentro del plano en el que se trazó, la punta **gira con el
     * dibujo**: se mira desde otro lado y el trazo se escorza como se escorzaría una raya de
     * tinta de verdad.
     *
     * Nulo en los trazos de punta redonda —que no tienen canto— y en los croquis de antes.
     */
    val ejeDeLaPunta: Pt3? = null
)

/**
 * **Un grupo**: unos cuantos trazos que a partir de ahora son una cosa.
 *
 * Croquizando, lo que uno dibuja deja de ser trazos a los diez minutos y pasa a ser *la
 * pata*, *el respaldo*, *el hueco de la escalera*. Sin grupos, mover el respaldo es volver a
 * pasarle la bolita a los catorce trazos que lo forman **cada vez**, y esconder la pata para
 * ver lo de detrás no se puede hacer sin cazarlos todos otra vez.
 *
 * El grupo no es una caja donde se meten cosas: es una etiqueta que llevan los trazos
 * ([Trazo3D.grupo]). Así un trazo no puede estar en dos sitios a la vez ni quedarse huérfano
 * dentro de una caja que ya no existe, y deshacer no tiene que casar dos listas.
 */
@Serializable
data class Grupo3D(val id: String, val nombre: String)

/**
 * La sección del pincel: **cómo es el trazo si se corta de través**. Tres, y no más.
 *
 * - [REDONDO] deja el mismo grosor vaya por donde vaya, y es **el de dibujar**: lleva el
 *   pulso de la mano dentro —aprieta y engorda, corre y adelgaza— así que es con el que un
 *   apunte a mano alzada se ve como un apunte a mano alzada.
 * - [CUADRADO] es la barra de sección cuadrada: mismo grosor en todas las direcciones,
 *   pero con las esquinas vivas y dos caras que cogen la luz distinto. Se lee **como un
 *   listón** y no como un tubo, y es lo que hace que una arista parezca una arista.
 * - [RESALTADOR] es la tinta que no tapa: una banda ancha y translúcida, **siempre de
 *   ancho fijo y siempre de través**, como el rodillo de dar una mano de color.
 * - [LUZ] no es tinta: **es un tubo encendido**. Un resplandor del color alrededor y un
 *   filamento casi blanco por dentro, que es exactamente lo que hace una luz de neón y lo
 *   que sabe hacer una pantalla de las de ahora: en un fondo oscuro el color no se queda en
 *   el cristal, sale de él. Sirve para lo que ninguna tinta puede —marcar el recorrido de
 *   algo, un eje, una cota, lo que está encendido— sin taparle el sitio al dibujo.
 *
 * ## Y por qué solo tres
 *
 * Hubo cinco: había además una plumilla de caligrafía y un lápiz de grafito. Sobraban. Un
 * croquis no se hace eligiendo entre cinco tintas, se hace dibujando; cada punta de más es
 * una decisión más antes de trazar la primera raya, y las dos que se han ido hacían lo
 * mismo que hacen ahora la redonda con pulso y el resaltador. [PLANO] y [LAPIZ] siguen en
 * la lista **solo para que los croquis guardados abran**: al cargarlos se convierten en la
 * redonda. Ver [vigente].
 */
@Serializable
enum class Pincel {
    REDONDO, CUADRADO, PLANO, LAPIZ, RESALTADOR, LUZ,

    /**
     * **La cuchilla: fina por donde se traza y honda hacia dentro de la hoja.**
     *
     * Es la punta que faltaba, y no es un adorno: es la única que dibuja **una superficie
     * con un solo trazo**. Se traza una raya sobre la hoja y lo que queda no es una raya, es
     * una lámina de pie —un tabique, un nervio, una costilla, el canto de una chapa—. De
     * frente casi no se ve, y en cuanto se gira la vista ahí está toda su hondura.
     *
     * ## Y el grosor la hace honda, no ancha
     *
     * En las demás puntas el selector dice lo gorda que sale la raya. Aquí dice **cuánto
     * baja**, que es lo que uno quiere decidir de un tabique: lo ancho lo pone el recorrido
     * del dedo. Es el mismo mando queriendo decir la misma cosa —cuánta tinta— aplicada al
     * eje que tiene sentido en cada punta.
     *
     * Por dentro es la banda del rodillo puesta de canto: la del rodillo se tumba en la hoja
     * y esta se levanta de ella. Ver [Croquis3DLienzo].
     */
    CUCHILLA;

    /** La punta que se pinta de verdad: las retiradas se dibujan como la redonda. */
    val vigente: Pincel get() = if (this == PLANO || this == LAPIZ) REDONDO else this

    /** Cómo se llama, para decírselo a quien la está retocando. */
    val comoSeLlama: String
        get() = when (vigente) {
            CUADRADO -> "listón"
            RESALTADOR -> "rodillo"
            LUZ -> "luz"
            CUCHILLA -> "cuchilla"
            else -> "rotulador"
        }

    companion object {
        /**
         * Las que se ofrecen: **cuatro secciones, y ninguna de ellas es un material**.
         *
         * La luz estaba aquí y no era una punta: era una punta redonda **de otro material**.
         * Puesta en esta lista, elegir «luz» tiraba la sección que uno tuviera —el listón, la
         * cuchilla, la que se hubiera dibujado a mano— y la cambiaba por una redonda, así que
         * un listón encendido no se podía tener. Y al revés: cambiar de punta apagaba la luz.
         * Son dos preguntas distintas —qué forma tiene la punta y de qué está hecha la
         * tinta— y ahora se responden por separado: la forma aquí, el material con el color.
         * Ver [PuntaDelPincel.alumbra] y [PuntaDelPincel.trama].
         *
         * [LUZ] sigue en el enum **solo para que los croquis guardados abran**, como [PLANO]
         * y [LAPIZ]: los trazos que la lleven se siguen pintando encendidos.
         */
        val LAS_QUE_HAY = listOf(REDONDO, CUADRADO, CUCHILLA, RESALTADOR)
    }
}

/**
 * Una lámina: **un perfil barrido en una dirección**.
 *
 * El perfil es lo que se trazó con el dedo y la dirección, hacia dónde miraba la cámara
 * en ese momento. Se barre **hacia los dos lados** y no solo hacia dentro: barriendo solo
 * hacia dentro, la lámina nace toda por detrás de donde uno la dibujó y al girar aparece
 * desplazada respecto del trazo que la creó, que descoloca. Simétrica, el trazo se queda
 * en su mitad, que es donde uno lo puso.
 */
@Serializable
data class Lamina3D(
    val id: String,
    val perfil: List<Pt3>,
    val direccion: Pt3,
    /** Cuánto barre hacia **cada** lado. */
    val fondo: Double,
    val color: String,
    val grosor: Double,
    /**
     * Una dirección de barrido **por cada punto del perfil**, cuando no vale una sola.
     *
     * Con la cámara ortográfica todas las visuales son paralelas, así que barrer hacia
     * donde se mira es una sola dirección y esto sobra — de ahí que sea opcional y que los
     * croquis normales no lo lleven.
     *
     * Con lente puesta no. Cada punto de la pantalla tiene **su propia** visual, abiertas
     * en abanico desde el ojo, y barriendo todo el perfil en la misma dirección la lámina
     * deja de estar de canto en los extremos: nace ya visible y torcida respecto de la raya
     * que uno acaba de trazar. Guardando la visual de cada punto, la lámina vuelve a nacer
     * exactamente detrás de su trazo, mire la cámara como mire.
     */
    val direcciones: List<Pt3>? = null,
    /**
     * Si **no se acaba a lo hondo**: si el barrido llega hasta donde haga falta.
     *
     * Nacen así todas. Un plano tiene dos direcciones y no se parecen: a lo ancho es lo que
     * se trazó —ahí la hoja tiene la forma que se le dio y se acaba donde se acabó la raya—
     * y a lo hondo no hay nada que decidir, así que no se acaba. Acabándose también por ahí,
     * el lápiz se quedaba mudo al dibujar un poco más adentro, en una frontera que nadie ha
     * puesto y que no se ve.
     *
     * Se pinta distinto: en vez del contorno cerrado de una hoja, una franja que llega hasta
     * los cantos de la pantalla y **solo sus dos bordes de verdad**, los que dejan las
     * puntas del trazo. Los croquis de antes de esto traen hojas que sí se acababan, y se
     * siguen pintando como lo que son.
     */
    val infinita: Boolean = false,
    /**
     * Cuánto se ve la hoja, de cero a dos y pico. Uno es como nace.
     *
     * Una hoja es una mesa de dibujo: **tiene que verse lo justo**. Muy tenue no se sabe
     * dónde está y estorba menos que nada; muy fuerte tapa el dibujo que uno ha puesto
     * encima, que es exactamente lo que ha venido a mirar. Y eso cambia con lo que haya
     * dibujado, así que es un mando y no un número fijo.
     */
    val opacidad: Double = 1.0,
    /**
     * **El otro riel**: la hoja va de este perfil a ese otro, punto por punto.
     *
     * Una hoja normal se hace barriendo una raya hacia lo hondo, y eso da superficies de
     * una sola familia: prismáticas. Pero un croquis está lleno de superficies que **no**
     * son eso: el faldón entre el alero y el muro, la vela entre dos cables, el capó entre
     * dos perfiles. Todas son lo mismo —**dos curvas y la regla que va de una a otra**—, y
     * es la superficie más fácil de dar a mano: se trazan las dos aristas y lo de en medio
     * sale solo.
     *
     * Con esto puesto, [direccion] y [fondo] dejan de mandar: los dos lados de la hoja ya no
     * son «el perfil corrido hacia delante y hacia atrás», son **el perfil y este**. Tiene
     * que traer tantos puntos como [perfil]: la regla une el punto `i` con el punto `i`.
     */
    val otroPerfil: List<Pt3>? = null,
    /**
     * **La hoja esférica**: una bola sobre la que dibujar. Ver [Esfera3D].
     *
     * Lo que se croquiza sobre una esfera no se puede croquizar sobre un plano: una bóveda,
     * una cúpula, un casco, un planeta, la trayectoria de algo que gira alrededor de un
     * punto. Sobre hojas planas hay que ir montando gajos y lo que sale es un poliedro; con
     * la bola puesta, el trazo **se pega a ella** y da la vuelta por donde uno lo lleve.
     */
    val esfera: Esfera3D? = null
) {
    /** Cuánto y hacia dónde se aparta el punto `i` del perfil, hacia cada lado. */
    fun desplazamiento(i: Int): Pt3 =
        por(normalizado(direcciones?.getOrNull(i) ?: direccion), fondo)

    /**
     * **Un lado de la hoja y el otro**, en el punto `i`. Entre los dos está la superficie.
     *
     * Es lo que le da a todo esto una sola forma. Barrida, los dos lados son el perfil
     * corrido hacia delante y hacia atrás; entre dos trazos, son un trazo y el otro. Todo lo
     * demás —las tiras para apoyar el lápiz, el contorno para pintarla, las esquinas para
     * encuadrar, la retícula— está escrito contra estos dos, así que **una hoja nueva se
     * dibuja, se toca y se encuadra sin tocar nada de eso**.
     */
    fun unLado(i: Int): Pt3 =
        if (otroPerfil != null) perfil[i] else menos(perfil[i], desplazamiento(i))

    fun otroLado(i: Int): Pt3 =
        otroPerfil?.getOrNull(i) ?: mas(perfil[i], desplazamiento(i))

    /**
     * Los cuatro puntos de cada tira, de un extremo al otro del perfil.
     *
     * **Se arma una vez por hoja y se guarda.** Es contra esto contra lo que se prueba dónde
     * cae el dedo, y eso se pregunta **en cada muestra del lápiz** —doscientas por segundo—:
     * armándola cada vez, apoyar el lápiz en una bola fabricaba sus doscientas ochenta y ocho
     * caras doscientas veces por segundo para tirarlas enseguida. Una hoja no cambia: cuando
     * se le mueve algo se sustituye entera por otra, así que la suya vale mientras exista.
     */
    private val lasTiras: List<List<Pt3>> by lazy(LazyThreadSafetyMode.NONE) {
        esfera?.malla() ?: if (perfil.size < 2) emptyList() else {
            (0 until perfil.size - 1).map { i ->
                listOf(unLado(i), unLado(i + 1), otroLado(i + 1), otroLado(i))
            }
        }
    }

    fun tiras(): List<List<Pt3>> = lasTiras

    /** Sus esquinas, para encuadrar. */
    fun esquinas(): List<Pt3> {
        esfera?.let { return it.caja() }
        return perfil.indices.flatMap { listOf(unLado(it), otroLado(it)) }
    }

    /** El borde de la lámina, recorrido: un lado de ida y el otro de vuelta. */
    fun contorno(): List<Pt3> {
        // La bola no tiene contorno propio: el que se le ve depende de desde dónde se mire,
        // y eso no lo sabe la hoja. Lo pinta quien tiene la cámara delante.
        if (esfera != null || perfil.size < 2) return emptyList()
        return perfil.indices.map { unLado(it) } + perfil.indices.reversed().map { otroLado(it) }
    }
}

/**
 * **Una bola**: un centro y un radio, y con eso una superficie sobre la que dibujar.
 *
 * Se guarda así y no como una malla de puntos por lo de siempre: una esfera **es** eso, y
 * teniéndolo se puede hacer la malla tan fina como haga falta en cada momento —gorda para
 * probar por dónde pasa el dedo, fina para pintarla— sin guardar mil puntos que además no
 * dicen que aquello era una esfera.
 */
@Serializable
data class Esfera3D(
    val centro: Pt3,
    val radio: Double,
    /**
     * Lo estirada que está por cada eje. Uno por uno por uno es una bola redonda.
     *
     * Una bola perfecta es lo que se pone; lo que hace falta casi siempre es **algo
     * parecido a una bola**: un huevo, una cúpula rebajada, un casco. Con un estirado por
     * eje, la misma hoja sirve para todo eso y se le da con el mando, viéndolo.
     */
    val escala: Pt3 = Pt3(1.0, 1.0, 1.0),
    /**
     * **Hacia dónde miran sus tres ejes.** Nulo son los del mundo, que es como nace.
     *
     * Hace falta en cuanto la bola deja de ser una bola. Una esfera se ve igual gire como
     * gire, así que mientras el estirado fue uno por uno por uno no había nada que apuntar; en
     * cuanto uno hace un huevo, ese huevo **está puesto de una manera** y girarlo tiene que
     * moverlo. Con el estirado atado a los ejes del mundo, el mando de girar movía el centro
     * de la bola alrededor de sí mismo —o sea, nada— y el huevo se quedaba mirando siempre al
     * mismo sitio mientras todo lo demás giraba.
     *
     * Son tres direcciones y no un ángulo por lo mismo que el resto de la aplicación gira con
     * la mano y no por `x`, `y` y `z`: girar es llevarlas a otro sitio, y eso es la misma
     * cuenta que se le hace a cualquier punto del croquis. Tres, y en el mismo orden que
     * [escala].
     */
    val ejes: List<Pt3>? = null,
    /**
     * **Qué cuerpo es**: una bola de verdad, o un cilindro, un cono o un anillo con la
     * misma mecánica. Son las primitivas de Feather —«draw on spheres, cylinders, cones or
     * rings»— y aquí son la misma cosa que la bola: un cuerpo de revolución dado por dos
     * ángulos, con su centro, su radio, sus tres estirados y sus tres ejes. Todo lo que
     * sabe hacer la bola —moverse, girarse, estirarse, apoyar el lápiz— lo hacen ellos sin
     * una línea más, porque todo pasa por [punto] y por la malla que sale de él.
     *
     * Los croquis de antes no lo traen y salen bola, que es lo que eran.
     */
    val forma: FormaDeBola = FormaDeBola.BOLA
) {
    /** Sus tres ejes, ya puestos: los suyos si los tiene, y si no los del mundo. */
    val unos: Pt3 get() = ejes?.getOrNull(0) ?: Pt3(1.0, 0.0, 0.0)
    val otros: Pt3 get() = ejes?.getOrNull(1) ?: Pt3(0.0, 1.0, 0.0)
    val terceros: Pt3 get() = ejes?.getOrNull(2) ?: Pt3(0.0, 0.0, 1.0)

    /** Lo que mide por cada uno de sus ejes. */
    val porUno: Double get() = radio * escala.x
    val porOtro: Double get() = radio * escala.y
    val porTercero: Double get() = radio * escala.z

    /**
     * Un punto suyo: [vuelta] alrededor del eje y [subida] desde el ecuador, en radianes.
     *
     * Los dos ángulos recorren el cuerpo entero sea el que sea: la vuelta da la vuelta y la
     * subida va de abajo (−π/2) a arriba (+π/2). En la bola son la longitud y la latitud; en
     * el cilindro y el cono la subida es la altura; en el anillo, la vuelta pequeña
     * alrededor del tubo. Todos caben en la caja unidad, así que [caja] vale para todos.
     */
    fun punto(vuelta: Double, subida: Double): Pt3 {
        val cv = kotlin.math.cos(vuelta)
        val sv = kotlin.math.sin(vuelta)
        val altura = (subida / (Math.PI / 2)).coerceIn(-1.0, 1.0)
        val a: Double
        val b: Double
        val c: Double
        when (forma) {
            FormaDeBola.BOLA -> {
                val cs = kotlin.math.cos(subida)
                a = porUno * cs * cv
                b = porOtro * cs * sv
                c = porTercero * kotlin.math.sin(subida)
            }
            FormaDeBola.CILINDRO -> {
                a = porUno * cv
                b = porOtro * sv
                c = porTercero * altura
            }
            FormaDeBola.CONO -> {
                // De radio entero abajo a punta arriba.
                val k = (1.0 - altura) / 2.0
                a = porUno * k * cv
                b = porOtro * k * sv
                c = porTercero * altura
            }
            FormaDeBola.ANILLO -> {
                // La subida recorre el tubo entero: media vuelta por arriba y media por abajo.
                val theta = (subida + Math.PI / 2) * 2.0
                val r = RADIO_DEL_ANILLO + GROSOR_DEL_ANILLO * kotlin.math.cos(theta)
                a = porUno * r * cv
                b = porOtro * r * sv
                c = porTercero * GROSOR_DEL_ANILLO * kotlin.math.sin(theta)
            }
        }
        val u = unos
        val v = otros
        val w = terceros
        return Pt3(
            centro.x + u.x * a + v.x * b + w.x * c,
            centro.y + u.y * a + v.y * b + w.y * c,
            centro.z + u.z * a + v.z * b + w.z * c
        )
    }

    /**
     * La bola en cuadros, para apoyar el lápiz encima.
     *
     * Suficientes para que un trazo no se note facetado y pocos como para probarlos todos
     * en cada muestra del dedo: es la misma cuenta que hace cualquier malla.
     */
    fun malla(): List<List<Pt3>> {
        val salida = ArrayList<List<Pt3>>(MERIDIANOS * PARALELOS)
        for (j in 0 until PARALELOS) {
            val s0 = -Math.PI / 2 + Math.PI * j / PARALELOS
            val s1 = -Math.PI / 2 + Math.PI * (j + 1) / PARALELOS
            for (i in 0 until MERIDIANOS) {
                val v0 = 2 * Math.PI * i / MERIDIANOS
                val v1 = 2 * Math.PI * (i + 1) / MERIDIANOS
                salida.add(
                    listOf(punto(v0, s0), punto(v1, s0), punto(v1, s1), punto(v0, s1))
                )
            }
        }
        return salida
    }

    /** Su caja, para encuadrar: las ocho esquinas, por sus propios ejes. */
    fun caja(): List<Pt3> = listOf(-1.0, 1.0).flatMap { x ->
        listOf(-1.0, 1.0).flatMap { y ->
            listOf(-1.0, 1.0).map { z ->
                val a = porUno * x
                val b = porOtro * y
                val c = porTercero * z
                val u = unos
                val v = otros
                val w = terceros
                Pt3(
                    centro.x + u.x * a + v.x * b + w.x * c,
                    centro.y + u.y * a + v.y * b + w.y * c,
                    centro.z + u.z * a + v.z * b + w.z * c
                )
            }
        }
    }

    companion object {
        /** En cuántos gajos y en cuántas fajas se parte la bola. */
        const val MERIDIANOS = 24
        const val PARALELOS = 12

        /** El anillo: el radio de la rosca y el grosor del tubo, en tantos del radio. */
        const val RADIO_DEL_ANILLO = 0.72
        const val GROSOR_DEL_ANILLO = 0.28
    }
}

/** Qué cuerpo de revolución es una [Esfera3D]. Ver [Esfera3D.forma]. */
@Serializable
enum class FormaDeBola { BOLA, CILINDRO, CONO, ANILLO }

/**
 * **El espejo**: un eje, y lo que se dibuja a un lado sale también al otro.
 *
 * ## Para qué
 *
 * Casi todo lo que uno croquiza es simétrico —un coche, una fachada, una pieza, una silla—
 * y dibujar dos veces lo mismo a mano no solo es el doble de trabajo: es que **no sale
 * igual**, y lo que se ve es que la cosa está torcida. Con el eje puesto se dibuja media
 * pieza y la otra media aparece exacta.
 *
 * ## Cómo se guarda
 *
 * Como el plano que refleja, no como la raya que se trazó. La raya —[punto] y [direccion]—
 * se guarda para poder pintarla, pero lo que hace el trabajo es [normal]: la perpendicular
 * al eje **dentro de la hoja**. Reflejar es entonces una resta, y vale igual para lo que
 * esté despegado de la hoja: un trazo que se levantó diez centímetros se refleja a diez
 * centímetros del otro lado, que es lo que uno espera de una simetría de verdad y no de un
 * espejo plano.
 */
@Serializable
data class Espejo3D(
    /** Un punto por el que pasa el eje. */
    val punto: Pt3,
    /** Hacia dónde va el eje, para pintarlo. */
    val direccion: Pt3,
    /** La normal del plano que refleja: perpendicular al eje y metida en la hoja. */
    val normal: Pt3
) {
    /** El otro lado de un punto. */
    fun reflejo(p: Pt3): Pt3 =
        menos(p, por(normal, 2.0 * escalar(menos(p, punto), normal)))

    /** Y el de un trazo entero, con sus puntos en el mismo orden. */
    fun reflejo(puntos: List<Pt3>): List<Pt3> = puntos.map { reflejo(it) }

    /**
     * El reflejo de una dirección: como el de un punto pero **sin llevarse el sitio**.
     *
     * Una dirección no está en ningún lado, así que reflejarla como si fuera un punto la
     * dejaría además desplazada. Hace falta para la normal de la hoja: la banda del rodillo
     * del otro lado tiene que quedar tumbada sobre la hoja reflejada, no sobre la original.
     */
    fun reflejoDeDireccion(d: Pt3): Pt3 = menos(d, por(normal, 2.0 * escalar(d, normal)))
}

/**
 * Por qué eje del mundo se maneja lo elegido.
 *
 * [LIBRE] no es «por ninguno»: es **por el plano de la hoja**, que es donde uno coloca las
 * cosas el noventa por ciento del tiempo. Los otros tres son la salida para cuando hace
 * falta precisión —o para despegar de la hoja, que es lo que hace [Z]—.
 *
 * Los colores son los de siempre en cualquier programa de tres dimensiones: la `x` roja, la
 * `y` verde y la `z` azul. No hay ninguna razón para inventarse otros.
 */
enum class EjeDelMundo(val direccion: Pt3?, val color: String) {
    LIBRE(null, "#6965DB"),
    X(Pt3(1.0, 0.0, 0.0), "#e03131"),
    Y(Pt3(0.0, 1.0, 0.0), "#2f9e44"),
    Z(Pt3(0.0, 0.0, 1.0), "#1971c2")
}

/** Dónde ha caído un toque en el espacio, y sobre qué. */
data class Impacto(
    val punto: Pt3,
    /** El plano sobre el que se apoya lo que se dibuje: el de la lámina tocada. */
    val plano: Plano3D,
    /** La lámina tocada, si se tocó alguna. */
    val lamina: String?,
    /** A qué distancia, para quedarse con la más cercana. */
    val distancia: Double,
    /**
     * Si el dedo se salió de la hoja y el punto se ha pegado a su borde.
     *
     * Lo mira quien dibuja para no amontonar veinte puntos en el mismo sitio mientras el
     * dedo pasea por fuera, y la pantalla para avisar de que ahí ya no se dibuja.
     */
    val enElBorde: Boolean = false
)

/**
 * Dónde cae un toque: **sobre la lámina más cercana, o sobre la pantalla**.
 *
 * Este es el corazón de la aplicación y donde se decide si dibujar en el espacio se siente
 * natural o imposible. La regla es una sola: **el dedo cae sobre lo que hay debajo**. Si
 * bajo el dedo hay una lámina, se dibuja en ella; si no hay nada, se dibuja en el plano de
 * la propia pantalla, que pasa por el centro de la cámara.
 *
 * No hay «elegir plano de trabajo» ni un sistema de coordenadas que configurar: se toca
 * donde se quiere dibujar, y ya.
 */
fun dondeCae(
    croquis: Croquis,
    rayo: Rayo,
    camara: Camara3D
): Impacto {
    var mejorTira: List<Pt3>? = null
    var mejorLamina: String? = null
    var mejorDistancia = Double.MAX_VALUE
    for (lamina in croquis.laminas) {
        for (tira in lamina.tiras()) {
            val t = distanciaDelCorte(rayo, tira)
            if (t == NO_CORTA || t >= mejorDistancia) continue
            mejorDistancia = t
            mejorTira = tira
            mejorLamina = lamina.id
        }
    }
    mejorTira?.let { tira ->
        val donde = enElRayo(rayo, mejorDistancia)
        val normal = normalizado(producto(menos(tira[1], tira[0]), menos(tira[3], tira[0])))
        return Impacto(donde, Plano3D(donde, normal), mejorLamina, mejorDistancia)
    }

    // Sin nada debajo, el plano de la pantalla: perpendicular a la mirada y pasando por el
    // centro de la cámara. Es donde uno cree que está dibujando cuando no hay nada más.
    val plano = Plano3D(camara.centro, camara.adelante)
    val corte = plano.corte(rayo)
        // Un rayo paralelo al plano de la pantalla no puede pasar —la cámara mira hacia
        // él— pero si pasara, mejor el origen del rayo que un punto inventado.
        ?: return Impacto(rayo.origen, plano, null, 0.0)
    return Impacto(corte.first, plano, null, corte.second)
}

/**
 * Dónde corta un rayo a un cuadrilátero, partiéndolo en dos triángulos.
 *
 * Se parte en dos y no se prueba contra su plano porque una tira barrida puede no ser
 * plana del todo —el perfil viene de un dedo, no de una regla— y un test contra el plano
 * daría cortes fuera de la tira, que se sentirían como tocar el aire y que te pinte.
 */
internal fun corteConCuadrilatero(rayo: Rayo, quad: List<Pt3>): Pair<Pt3, Double>? {
    val t = distanciaDelCorte(rayo, quad)
    if (t == NO_CORTA) return null
    return enElRayo(rayo, t) to t
}

/**
 * Lo mismo, **en un número y sin fabricar nada**: a qué distancia por el rayo cae el corte.
 *
 * Es la que se usa donde esto se pregunta a destajo —una tira tras otra, en cada muestra del
 * lápiz—, porque ahí lo caro no es la cuenta sino la basura. Ver [corteConTriangulo].
 */
internal fun distanciaDelCorte(rayo: Rayo, quad: List<Pt3>): Double {
    if (quad.size < 4) return NO_CORTA
    val a = corteConTriangulo(rayo, quad[0], quad[1], quad[2])
    val b = corteConTriangulo(rayo, quad[0], quad[2], quad[3])
    return when {
        a == NO_CORTA -> b
        b == NO_CORTA -> a
        else -> kotlin.math.min(a, b)
    }
}

/** El punto que está a esa distancia por el rayo. */
internal fun enElRayo(rayo: Rayo, t: Double): Pt3 = mas(rayo.origen, por(rayo.direccion, t))

/**
 * Möller–Trumbore, que es la forma barata de cortar un rayo con un triángulo.
 *
 * ## Y escrito en números sueltos, que es lo que lo hace barato de verdad
 *
 * Escrito con vectores se lee mejor y **es de lo más caro que hacía la aplicación**. Esta
 * cuenta se hace contra cada tira de la hoja, y la hoja se pregunta **en cada muestra del
 * lápiz**: doscientas por segundo. Con un perfil trazado a pulso —doscientos puntos— eso son
 * cuatrocientos triángulos por muestra, y cada uno fabricaba media docena de vectores de
 * usar y tirar. Ochenta mil objetos por segundo largos, todos para tirarlos en el acto: no
 * se ve como memoria gastada, se ve como que **dibujar sobre un plano va a tirones**.
 *
 * Las mismas cuentas en variables sueltas no fabrican ni un objeto, y solo se arma el punto
 * del corte —uno— cuando el rayo acierta de verdad. Devuelve la distancia por el rayo y
 * deja el punto en [ElCorte], para no fabricar tampoco la pareja.
 */
private fun corteConTriangulo(rayo: Rayo, a: Pt3, b: Pt3, c: Pt3): Double {
    val abx = b.x - a.x; val aby = b.y - a.y; val abz = b.z - a.z
    val acx = c.x - a.x; val acy = c.y - a.y; val acz = c.z - a.z
    val d = rayo.direccion
    // p = direccion × ac
    val px = d.y * acz - d.z * acy
    val py = d.z * acx - d.x * acz
    val pz = d.x * acy - d.y * acx
    val det = abx * px + aby * py + abz * pz
    if (abs(det) < 1e-9) return NO_CORTA
    val inv = 1.0 / det
    val o = rayo.origen
    val tx = o.x - a.x; val ty = o.y - a.y; val tz = o.z - a.z
    val u = (tx * px + ty * py + tz * pz) * inv
    if (u < -1e-6 || u > 1 + 1e-6) return NO_CORTA
    // q = t × ab
    val qx = ty * abz - tz * aby
    val qy = tz * abx - tx * abz
    val qz = tx * aby - ty * abx
    val v = (d.x * qx + d.y * qy + d.z * qz) * inv
    if (v < -1e-6 || u + v > 1 + 1e-6) return NO_CORTA
    val t = (acx * qx + acy * qy + acz * qz) * inv
    if (t < 0) return NO_CORTA
    return t
}

/** Que no hay corte. Un número y no un nulo, para no envolver el resultado en nada. */
internal const val NO_CORTA = -1.0

/**
 * Dónde cae un toque **sobre la hoja**, y en ningún otro sitio.
 *
 * Esta función es la aplicación entera. Con una hoja puesta, todo lo que se dibuje va a
 * ella: si el rayo la corta, al punto exacto —y en una hoja curva eso es sobre la curva,
 * no sobre un plano medio inventado—; y **si el dedo se sale, al punto de su borde que
 * queda más cerca**, no a un plano prolongado hasta el infinito.
 *
 * Pegarse al borde y no prolongar es la diferencia entre dibujar sobre una hoja y dibujar
 * sobre el plano infinito que la contiene. Prolongando, el trazo se iba por fuera de la
 * superficie que se ve, quedaba flotando junto a la hoja y en cuanto uno giraba aparecía
 * un rabo colgando en el aire; se dibujaba fuera del plano sin querer y sin enterarse.
 * Pegado, la hoja recorta: el trazo se queda en el canto, que es lo que hace una hoja de
 * verdad cuando uno se pasa de largo.
 *
 * ## Y por qué manda [cerca] y no la cámara
 *
 * En una hoja curva, **un rayo la corta dos veces**: entra por la loma que se ve y sale
 * por la parte de atrás. Quedándose siempre con el corte más cercano al ojo, el trazo se
 * porta bien hasta que uno pasa por el punto donde la hoja se dobla hacia el otro lado —y
 * ahí, de un fotograma al siguiente, el corte bueno pasa a ser el otro: **un tramo entero
 * del trazo se va a la cara de atrás** y aparece cruzando la hoja por dentro. Se ve como
 * si la línea se pasara de largo, y solo pasa en las hojas curvas.
 *
 * Con el punto anterior en la mano, la regla es otra y no falla: de los cortes que haya,
 * el que caiga **más cerca de por donde iba el trazo**. Un trazo es continuo; el corte
 * bueno es el que sigue siéndolo.
 */
fun dondeCaeEnElPlano(
    lamina: Lamina3D,
    camara: Camara3D,
    p: Pt,
    ancho: Double,
    alto: Double,
    cerca: Pt3? = null
): Impacto? {
    val tiras = lamina.tiras()
    if (tiras.isEmpty()) return null
    val rayo = camara.rayo(p, ancho, alto)

    var mejorDistancia = 0.0
    var mejorTira: List<Pt3>? = null
    var mejorNota = Double.MAX_VALUE
    for (tira in tiras) {
        val t = distanciaDelCorte(rayo, tira)
        if (t == NO_CORTA) continue
        // Sin trazo empezado manda lo que se ve —lo más cercano al ojo—; con trazo
        // empezado manda la continuidad. Ver la nota de arriba.
        //
        // Y la nota se mide **sin fabricar el punto**: solo el que gane llega a existir. Es
        // lo mismo que hace [corteConTriangulo] y por la misma razón — esto se pregunta
        // doscientas veces por segundo contra cada tira de la hoja.
        val nota = if (cerca == null) t else {
            val dx = rayo.origen.x + rayo.direccion.x * t - cerca.x
            val dy = rayo.origen.y + rayo.direccion.y * t - cerca.y
            val dz = rayo.origen.z + rayo.direccion.z * t - cerca.z
            kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        if (nota < mejorNota) {
            mejorNota = nota
            mejorDistancia = t
            mejorTira = tira
        }
    }
    mejorTira?.let { tira ->
        val punto = enElRayo(rayo, mejorDistancia)
        return Impacto(punto, Plano3D(punto, normalDeTira(tira)), lamina.id, mejorDistancia)
    }

    val borde = puntoMasCercanoDeLaHoja(lamina, camara, p, ancho, alto, cerca) ?: return null
    // La normal, la de la tira que le pilla más cerca: en una hoja curva, el borde de
    // arriba y el de abajo no miran hacia el mismo lado.
    val tira = tiras.minByOrNull { t ->
        val centro = por(t.fold(Pt3(0.0, 0.0, 0.0)) { acc, q -> mas(acc, q) }, 0.25)
        largo(menos(centro, borde))
    }!!
    return Impacto(borde, Plano3D(borde, normalDeTira(tira)), lamina.id, 0.0, enElBorde = true)
}

/**
 * El punto de la hoja al que se pega el trazo cuando el rayo no la corta.
 *
 * ## Por qué la hoja entera y no solo su contorno
 *
 * Porque el rayo no solo falla por fuera. En una hoja curva vista **de perfil**, justo en
 * el punto donde la curva se dobla, la superficie es casi paralela al rayo y este se cuela
 * entre dos tiras sin cortar ninguna: falla **en medio de la hoja**. Buscando solo por el
 * contorno, el punto más cercano estaba entonces en el canto de arriba o el de abajo, y el
 * lápiz pegaba un salto y dejaba una raya recta hasta el borde. Con las aristas de todas
 * las tiras, el sitio más cercano en ese caso es el propio pliegue —que es una arista
 * compartida por dos tiras— y el trazo se queda donde tiene que quedarse.
 *
 * Las aristas de las tiras cubren la hoja como una rejilla y su contorno es parte de ellas,
 * así que esto vale para los dos casos con la misma cuenta.
 *
 * ## La cuenta, y por qué son dos cosas sumadas
 *
 * Lo que se busca es el canto que **se ve** más cerca del dedo, así que la distancia se
 * mide en la pantalla: el punto del mundo más cercano al rayo puede estar en la otra punta
 * de la hoja si esta se ve escorzada, y el trazo pegaría un salto a un canto que uno no
 * tiene debajo.
 *
 * Pero solo con eso aparecía **la raya**: pasado el borde, el dedo sigue andando por
 * fuera, y a partir de cierto punto **el canto de enfrente se ve más cerca que el que uno
 * acaba de cruzar** —siempre pasa, basta con alejarse lo suficiente, y en una hoja
 * estrecha pasa enseguida—. El trazo saltaba de un canto al otro y dejaba una raya
 * cruzando la hoja de lado a lado, como si continuara por el otro lado.
 *
 * Así que a la distancia en la pantalla se le suma **lo que costaría llegar hasta ahí desde
 * donde iba el trazo**, en píxeles también. Un canto que se ve dos píxeles más cerca pero
 * está a media hoja de distancia pierde, y pierde por mucho. Un trazo es continuo: el canto
 * bueno no es el que se ve más cerca, es el que se ve cerca **y** está donde uno estaba.
 */
private fun puntoMasCercanoDeLaHoja(
    lamina: Lamina3D,
    camara: Camara3D,
    p: Pt,
    ancho: Double,
    alto: Double,
    cerca: Pt3?
): Pt3? {
    val tiras = lamina.tiras()
    if (tiras.isEmpty()) return null

    var mejor: Pt3? = null
    var mejorNota = Double.MAX_VALUE
    for (tira in tiras) {
        val enPantalla = tira.map { camara.aPantalla(it, ancho, alto) }
        for (i in tira.indices) {
            val j = (i + 1) % tira.size
            val (d, enLaPantalla) = distanciaYCorte(p, enPantalla[i], enPantalla[j])
            // **Una arista vista de punta no dice por dónde de ella cae el dedo.**
            //
            // Es lo que pasa en la vista de perfil: el pliegue de la hoja va justo en la
            // dirección de la mirada y se proyecta en **un punto**, así que el reparto a lo
            // largo de la arista sale siempre cero y el trazo se iba a su extremo. Metros
            // enteros de salto sin que la pantalla enseñe nada raro. Cuando eso pasa, por
            // dónde va lo dice el trazo: se proyecta el punto anterior sobre la arista.
            val t = if (cerca != null && seVeDePunta(enPantalla[i], enPantalla[j])) {
                repartoEnElSegmento(cerca, tira[i], tira[j])
            } else enLaPantalla
            val candidato = mas(tira[i], por(menos(tira[j], tira[i]), t))
            // Lo que se ve más el irse de donde uno estaba, las dos cosas en píxeles.
            val nota = if (cerca == null) d
            else d + PESO_DE_LA_CONTINUIDAD * largo(menos(candidato, cerca)) * camara.zoom
            if (nota < mejorNota) {
                mejorNota = nota
                mejor = candidato
            }
        }
    }
    return mejor
}

/**
 * Cuánto pesa no despegarse frente a verse cerca, al pegarse a un canto.
 *
 * Uno: un píxel de irse de donde estaba el trazo cuesta lo mismo que un píxel de verse más
 * lejos del dedo. Con esto, deslizarse por el canto que uno acaba de cruzar sale gratis
 * —está justo al lado— y cruzarse a otro canto es carísimo, que es exactamente lo que hay
 * que conseguir.
 */
private const val PESO_DE_LA_CONTINUIDAD = 1.0

/** Si una arista se ve tan corta en la pantalla que su reparto no significa nada. */
private fun seVeDePunta(a: Pt, b: Pt): Boolean = hypot(b.x - a.x, b.y - a.y) < DE_PUNTA

/** Por dónde de un segmento del espacio cae lo más cerca de un punto, de cero a uno. */
private fun repartoEnElSegmento(p: Pt3, a: Pt3, b: Pt3): Double {
    val v = menos(b, a)
    val largo2 = escalar(v, v)
    if (largo2 <= 1e-12) return 0.0
    return (escalar(menos(p, a), v) / largo2).coerceIn(0.0, 1.0)
}

/** Cuántos píxeles de largo tiene que verse una arista para creerse su reparto. */
private const val DE_PUNTA = 2.0

/** Distancia de un punto a un segmento de la pantalla, y por dónde de ese segmento cae. */
private fun distanciaYCorte(p: Pt, a: Pt, b: Pt): Pair<Double, Double> {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val largo2 = vx * vx + vy * vy
    val t = if (largo2 <= 0.0) 0.0
    else (((p.x - a.x) * vx + (p.y - a.y) * vy) / largo2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + vx * t), p.y - (a.y + vy * t)) to t
}

/** La normal de una tira, del producto vectorial de sus lados. */
fun normalDeTira(tira: List<Pt3>): Pt3 =
    normalizado(producto(menos(tira[1], tira[0]), menos(tira[3], tira[0])))

/**
 * El trazo enderezado: **solo sus dos puntas**.
 *
 * Es el modo regla. Dibujar a pulso una raya que va a ser un plano deja el plano ondulado,
 * y un plano ondulado no sirve de apoyo para lo siguiente — que es justo para lo que se
 * pone. Con la regla, la lámina sale plana de verdad.
 */
fun enderezado(puntos: List<Pt3>): List<Pt3> =
    if (puntos.size < 2) puntos else listOf(puntos.first(), puntos.last())

/**
 * **Si lo trazado ya es una curva**, y no una raya con el pulso de cualquiera.
 *
 * Pararse con el dedo apoyado endereza lo trazado, y ese gesto es el que hace que una raya
 * recta salga recta —a pulso, sobre una pantalla y girando la vista, no sale—. Pero se
 * aplicaba **a lo que hubiera**, y una curva se traza despacio: al pararse un instante antes
 * de levantar el dedo, el arco se cambiaba por la cuerda que une sus puntas y la hoja nacía
 * plana. Trazar un plano curvo era imposible sin saber que había que hacerlo deprisa, que es
 * un secreto y no un gesto.
 *
 * Se mide la **flecha del arco**: lo que más se separa el trazo de la cuerda, en tantos por
 * uno de lo que mide la cuerda. Es una medida de forma y no de tamaño, así que da igual que
 * el plano sea de un palmo o de treinta metros.
 *
 * **Y se mide en la pantalla, no en el espacio**, por lo mismo que [esUnPunto]: lo que decide
 * si alguien ha querido una curva es lo que ha dibujado su dedo. En el espacio la respuesta
 * depende de desde dónde se mire — con la hoja casi de canto, la vista aplasta el trazo en un
 * sentido y no en el otro, así que un temblor de dos píxeles sale convertido en un arco
 * enorme y una curva de verdad puede salir recta. Es un gesto de pantalla y se mide en
 * pantalla.
 *
 * Ida y vuelta —la cuerda mide cero— no es una raya desde luego: cuenta como curva.
 */
fun yaEsUnaCurva(enPantalla: List<Pt>, margen: Double = TORCIDO_QUE_YA_ES_CURVA): Boolean {
    if (enPantalla.size < 3) return false
    val desde = enPantalla.first()
    val hasta = enPantalla.last()
    val ejeX = hasta.x - desde.x
    val ejeY = hasta.y - desde.y
    val cuerda = kotlin.math.hypot(ejeX, ejeY)
    if (cuerda < 1e-9) {
        return enPantalla.any { kotlin.math.hypot(it.x - desde.x, it.y - desde.y) > 1e-9 }
    }
    var flecha = 0.0
    for (p in enPantalla) {
        // Lo que se aparta de la recta: el área del paralelogramo partida por su base.
        val fuera = kotlin.math.abs((p.x - desde.x) * ejeY - (p.y - desde.y) * ejeX) / cuerda
        if (fuera > flecha) flecha = fuera
    }
    return flecha / cuerda > margen
}

/**
 * Cuánto se puede apartar un trazo de su cuerda antes de contar como curva.
 *
 * Un seis por ciento. Por debajo está el temblor de trazar una raya a pulso, que es justo lo
 * que el gesto de pararse viene a limpiar; por encima ya no hay forma de que a alguien le
 * saliera sin querer — un cuarto de circunferencia se aparta un veinte por ciento largo.
 */
const val TORCIDO_QUE_YA_ES_CURVA = 0.06

/**
 * Cuánto encierra un trazo cerrado, sin importar sobre qué plano esté.
 *
 * Es la mitad del largo de la suma de los productos vectoriales de sus lados desde un punto
 * suyo: el área con dirección de un polígono plano. Sirve igual esté el polígono tumbado
 * donde esté, que es justo lo que hace falta aquí —lo dibujado vive sobre una hoja que puede
 * estar en cualquier postura— y no obliga a buscarle un plano ni a proyectarlo.
 */
private fun areaQueEncierra(puntos: List<Pt3>): Double {
    val origen = puntos.first()
    var suma = Pt3(0.0, 0.0, 0.0)
    for (i in 0 until puntos.size - 1) {
        suma = mas(suma, producto(menos(puntos[i], origen), menos(puntos[i + 1], origen)))
    }
    return largo(suma) / 2
}

/**
 * Cuánto puede quedar abierto un lazo, y cuánto hueco tiene que encerrar para serlo.
 *
 * El hueco es lo que separa una rueda de un garabato de ida y vuelta —que también acaba
 * donde empezó, y también recorre el doble de lo que ocupa, pero no encierra nada—. Una
 * rueda encierra el 0,39 de su diagonal al cuadrado y un cuadrado el 0,5; con 0,15 entra
 * cualquier círculo hecho a pulso, por malo que sea, y no entra una raya doblada.
 */
private const val CIERRE_DEL_LAZO = 0.5
private const val HUECO_DEL_LAZO = 0.15

/**
 * **Un círculo perfecto en el plano, del centro a donde esté el dedo.**
 *
 * El de antes se sacaba de una caja —dos esquinas en diagonal—, y una caja da elipses: sale
 * redondo solo si uno acierta a estirarla igual de ancha que de alta, que no acierta nadie.
 * Con centro y radio no hay nada que acertar: **es un círculo siempre**, y el dedo solo dice
 * lo grande.
 */
fun circuloEnElPlano(
    centro: Pt3,
    radio: Double,
    normal: Pt3,
    derechaDeLaVista: Pt3,
    pasos: Int = PASOS_DE_LA_ELIPSE
): List<Pt3> {
    val n = normalizado(normal)
    var u = menos(derechaDeLaVista, por(n, escalar(derechaDeLaVista, n)))
    if (largo(u) < 1e-6) {
        u = producto(n, if (abs(n.z) > 0.9) Pt3(1.0, 0.0, 0.0) else Pt3(0.0, 0.0, 1.0))
    }
    u = normalizado(u)
    val v = normalizado(producto(n, u))
    val vuelta = (0..pasos).map { i ->
        val t = 2 * Math.PI * i / pasos
        mas(centro, mas(por(u, radio * kotlin.math.cos(t)), por(v, radio * kotlin.math.sin(t))))
    }
    return vuelta
}

/** En cuántos tramos se parte una elipse. Con cuarenta y ocho no se le ven las esquinas. */
private const val PASOS_DE_LA_ELIPSE = 48

/**
 * Las dos esquinas de la caja de un lazo: **la que queda lejos del dedo y la de al lado de
 * él**, dentro del plano y en diagonal la una de la otra.
 *
 * La de lejos es de la que se cuelga la rueda al redondearla, y la de cerca es la que se
 * lleva el dedo al seguir arrastrando. Así, en el instante en que la rueda aparece es
 * exactamente la que se había dibujado —la caja es la misma— y a partir de ahí crece y se
 * estira por donde uno tire, sin pegar ningún salto.
 */
fun esquinasDelLazo(
    puntos: List<Pt3>,
    dedo: Pt3,
    normal: Pt3,
    derechaDeLaVista: Pt3
): Pair<Pt3, Pt3> {
    val n = normalizado(normal)
    var u = menos(derechaDeLaVista, por(n, escalar(derechaDeLaVista, n)))
    if (largo(u) < 1e-6) {
        u = producto(n, if (abs(n.z) > 0.9) Pt3(1.0, 0.0, 0.0) else Pt3(0.0, 0.0, 1.0))
    }
    u = normalizado(u)
    val v = normalizado(producto(n, u))
    val origen = puntos.first()

    val enA = puntos.map { escalar(menos(it, origen), u) }
    val enB = puntos.map { escalar(menos(it, origen), v) }
    fun esquina(a: Double, b: Double) = mas(origen, mas(por(u, a), por(v, b)))
    val cajas = listOf(
        esquina(enA.min(), enB.min()) to esquina(enA.max(), enB.max()),
        esquina(enA.max(), enB.min()) to esquina(enA.min(), enB.max())
    )
    // De las dos diagonales, la que deja el dedo cerca de su segunda punta.
    val mejor = cajas.minByOrNull { largo(menos(it.second, dedo)) }!!
    return mejor
}

/**
 * El trazo aligerado: se queda con los puntos que **aportan forma**, no con los que están
 * lejos del anterior.
 *
 * Un dedo deja doscientos puntos por segundo y la mitad están a menos de un píxel del
 * anterior. Guardarlos todos engorda el archivo y hace que cada repintado recorra diez veces
 * más puntos de los que hacen falta para dibujar la misma curva.
 *
 * **Pero aligerar por distancia era peor que no aligerar.** Dejaba la misma densidad en una
 * curva cerrada que en una recta: la recta se quedaba con puntos de sobra y la curva perdía
 * justo los que la hacían curva, así que lo trazado salía como una tira de rectas cosidas —y
 * se notaba más cuanto más se acercaba uno a mirarlo—. Lo que decide ahora es **cuánto se
 * movería la línea si ese punto no estuviera** ([tolerancia], en unidades del mundo): un
 * tramo recto se queda en dos puntos por largo que sea, y una curva cerrada conserva todos
 * los que hacen falta para que no se le vean las esquinas. Es la criba de Douglas y Peucker.
 */
fun aligerado(puntos: List<Pt3>, minimo: Double): List<Pt3> =
    indicesAligerados(puntos, minimo).map { puntos[it] }

/**
 * Lo mismo, pero diciendo **qué puntos se quedan** en vez de devolverlos.
 *
 * Hace falta porque un trazo no es solo una ristra de puntos: cada uno lleva pegado cuánto
 * apretaba la mano ahí. Aligerando las dos listas por separado se descolocarían entre sí a
 * la primera, y el grueso del trazo dejaría de corresponderse con el pulso.
 */
/**
 * El mismo recorrido con **otro número de puntos, repartidos parejos**.
 *
 * Hace falta para unir dos trazos: la regla va del punto `i` de uno al punto `i` del otro,
 * así que un trazo de doscientos puntos y otro de doce no se pueden emparejar tal cual —la
 * hoja saldría toda amontonada en el primer palmo del largo—. Repartiendo los dos por
 * distancia recorrida, el punto de en medio de uno se une con el de en medio del otro, que
 * es lo que uno ve al mirar las dos aristas.
 */
fun remuestreado(puntos: List<Pt3>, cuantos: Int): List<Pt3> {
    if (puntos.size < 2 || cuantos < 2) return puntos
    val tramos = puntos.zipWithNext().map { (a, b) -> largo(menos(b, a)) }
    val total = tramos.sum()
    if (total < 1e-12) return List(cuantos) { puntos.first() }
    val salida = ArrayList<Pt3>(cuantos)
    var tramo = 0
    var llevado = 0.0
    for (k in 0 until cuantos) {
        val meta = total * k / (cuantos - 1)
        while (tramo < tramos.size - 1 && llevado + tramos[tramo] < meta) {
            llevado += tramos[tramo]; tramo++
        }
        val dentro = if (tramos[tramo] < 1e-12) 0.0 else (meta - llevado) / tramos[tramo]
        salida.add(mas(puntos[tramo], por(menos(puntos[tramo + 1], puntos[tramo]), dentro)))
    }
    return salida
}

fun indicesAligerados(puntos: List<Pt3>, tolerancia: Double): List<Int> {
    if (puntos.size < 3) return puntos.indices.toList()
    val tope = if (tolerancia > 0.0) tolerancia else 1e-9
    // Se marca lo que se queda y se recorre con una pila, no con recursión: un trazo largo
    // son miles de puntos y la pila del sistema no está para eso.
    val queda = BooleanArray(puntos.size)
    queda[0] = true
    queda[puntos.size - 1] = true
    val pendientes = ArrayDeque<Int>()
    pendientes.addLast(0); pendientes.addLast(puntos.size - 1)
    while (pendientes.isNotEmpty()) {
        val b = pendientes.removeLast()
        val a = pendientes.removeLast()
        if (b <= a + 1) continue
        var peor = -1
        var cuanto = tope
        for (i in a + 1 until b) {
            val d = distanciaAlTramo(puntos[i], puntos[a], puntos[b])
            if (d > cuanto) { cuanto = d; peor = i }
        }
        if (peor < 0) continue
        queda[peor] = true
        pendientes.addLast(a); pendientes.addLast(peor)
        pendientes.addLast(peor); pendientes.addLast(b)
    }
    return puntos.indices.filter { queda[it] }
}

/** Lo lejos que está [p] del tramo que va de [a] a [b]. */
internal fun distanciaAlTramo(p: Pt3, a: Pt3, b: Pt3): Double {
    val ab = menos(b, a)
    val l2 = escalar(ab, ab)
    if (l2 < 1e-18) return largo(menos(p, a))
    val t = (escalar(menos(p, a), ab) / l2).coerceIn(0.0, 1.0)
    return largo(menos(p, mas(a, por(ab, t))))
}
