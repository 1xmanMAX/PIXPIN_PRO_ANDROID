package com.forge.pixpin.croquis3d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.forge.pixpin.motor.AlisadoDelPuntero
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.randomId
import kotlin.math.abs
import kotlin.math.hypot

/**
 * El estado del croquis y lo que le pasa al tocarlo.
 *
 * Aparte de la pantalla a propósito, como el controlador del lienzo: aquí está **la ley
 * de la aplicación** —dónde cae el dedo, qué se crea al levantarlo, qué borra el
 * borrador— y todo eso son cuentas que se pueden comprobar sin dispositivo. La pantalla
 * solo pinta lo que hay y avisa de lo que pasa.
 */
class Croquis3DControlador {

    var croquis by mutableStateOf(Croquis())
        private set
    var camara by mutableStateOf(Camara3D())
        private set

    var herramienta by mutableStateOf(Herramienta3D.LAPIZ)
    var color by mutableStateOf(COLORES.first())
    var grosor by mutableStateOf(6.0)

    /**
     * **En qué capa se está dibujando.** Nula, en ninguna: suelto.
     *
     * Una capa es un grupo con un trabajo más: lo que se dibuja mientras está elegida entra
     * en ella. Es lo que convierte los grupos en algo que se usa **mientras** se dibuja —el
     * armazón en una, el detalle en otra, y se apaga el detalle para ver el armazón— en vez
     * de en algo que solo sirve para ordenar después. Ver [Grupo3D].
     */
    var capaActiva by mutableStateOf<String?>(null)
        private set

    /** Se pone a dibujar en una capa, o suelto si se toca la que ya estaba. */
    fun dibujarEnLaCapa(id: String?) {
        capaActiva = id?.takeIf { id != capaActiva && croquis.grupos.any { g -> g.id == id } }
    }

    /** Una capa nueva, vacía y elegida: lo siguiente que se dibuje va a ella. */
    fun capaNueva() {
        anotar()
        val capa = Grupo3D(randomId(), "${croquis.grupos.size + 1}")
        croquis = croquis.copy(grupos = croquis.grupos + capa)
        capaActiva = capa.id
    }

    /** Con qué punta se dibuja. Ver [Pincel]. */
    var pincel by mutableStateOf(Pincel.REDONDO)

    /** Y cómo está esa punta: lo ancha, lo larga de canto, ladeada y con pulso o sin él. */
    var punta by mutableStateOf(PuntaDelPincel())

    /**
     * Cuánto tapa la tinta: uno es opaca y cero es invisible.
     *
     * Es lo que deja dibujar encima sin perder lo de debajo —líneas de construcción, un
     * volumen tanteado por encima de otro— sin tener que cambiar de color ni de punta.
     */
    var opacidad by mutableStateOf(1.0)

    /**
     * **De qué color está el tubo cuando no alumbra.** Nulo, el mismo más apagado.
     *
     * Es la mitad que faltaba de una luz: el color de la barra es el del gas encendido, y
     * este el del cristal. Ver [Trazo3D.colorApagada].
     */
    var colorApagada by mutableStateOf<String?>(null)

    /** Le pone a lo elegido el color de apagado, para poder verlo mientras se elige. */
    fun apagarLaSeleccionDe(color: String?) {
        if (seleccion.isEmpty()) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map {
                if (it.id in seleccion) it.copy(colorApagada = color) else it
            }
        )
    }

    /**
     * Cuánto alumbra la tinta de luz, de cero a uno. Ver [Trazo3D.luz].
     *
     * De fábrica, del todo: quien elige la punta de luz quiere luz. El interruptor está
     * para bajarla, que es lo que hace falta cuando lo encendido es media docena de cosas y
     * todas gritando se comen el croquis.
     */
    var luz by mutableStateOf(1.0)

    /**
     * Lo gordo que sale de verdad el trazo.
     *
     * **El resaltador sí hace caso al selector, y sale mucho más ancho que el número.**
     *
     * Tuvo un ancho fijo, con el argumento de que un rotulador de subrayar tiene el ancho
     * que tiene. Vale para subrayar un renglón y no vale para nada más: en el espacio, la
     * misma tinta se usa para marcar una zona de una pieza, para pintar una franja entera de
     * una fachada o para dar color a una cara —y ahí un ancho fijo se queda corto o se pasa
     * siempre—. Así que se elige como todo lo demás.
     *
     * Lo que se conserva es que **es ancho**: el número del selector se multiplica, porque
     * los grosores de dibujar no subrayan —pintan una raya de color encima— y nadie quiere
     * tener que subir el selector al tope cada vez que coge el rotulador.
     */
    val grosorDeVerdad: Double
        get() = Tinta.de(pincel, punta).gordoDe(grosor)

    /**
     * El plano de trabajo: **el único que hay**.
     *
     * No es estado que se elija, es lo que hay dibujado. Con un solo plano a la vez no hay
     * nada que seleccionar, nada que soltar y ninguna forma de equivocarse de superficie:
     * dibujas donde está el plano, y si quieres otro sitio, mueves el plano. Es la mesa de
     * dibujo, no una capa.
     */
    val base: String? get() = croquis.laminas.firstOrNull()?.id

    /** Sin plano no se dibuja: la pantalla lo dice en vez de tragarse el trazo. */
    val hayPlano: Boolean get() = croquis.laminas.isNotEmpty()

    /**
     * Si el espejo está puesto. Ver [Espejo3D].
     *
     * Aparte del eje a propósito: el eje es del croquis y se guarda con él, pero **querer
     * simetría ahora mismo** es una decisión del momento. Se dibuja media pieza con el
     * espejo puesto, se apaga para poner algo que solo va de un lado, y se vuelve a
     * encender sin tener que trazar el eje otra vez.
     */
    var espejoPuesto by mutableStateOf(true)

    /**
     * **El candado del aumento**: con él echado, los dedos desplazan pero no acercan.
     *
     * Trabajando a una escala concreta —calcando encima de una imagen, midiendo sobre el
     * plano— cualquier pellizco la estropea, y volver al aumento exacto de antes no se
     * puede. Nadie hace un arrastre con dos dedos perfectamente paralelo, así que sin
     * candado la escala se va sola poco a poco.
     */
    var zoomBloqueado by mutableStateOf(false)

    /**
     * Si hay eje trazado, hay hoja y está puesto: entonces lo que se dibuje sale doble.
     *
     * **Sin hoja no hay eje.** El eje de simetría vive dentro del plano de trabajo —es una
     * raya sobre la mesa—, así que quitada la mesa no queda de qué colgarlo: seguir
     * pintándolo en el aire, y peor, seguir reflejando contra él, es prometer una simetría
     * que ya no se apoya en nada.
     */
    val espejaAhora: Boolean get() = espejoPuesto && croquis.espejo != null && hayPlano

    /** Los trazos elegidos. Se mueven juntos, se borran juntos y se repintan juntos. */
    var seleccion by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Cuánto se lleva volteado lo elegido con la mano, **solo para contarlo**.
     *
     * No se usa para girar nada: el giro ya está hecho sobre los puntos. Es para que el
     * cubo se voltee con la figura y uno sepa en qué postura la ha dejado —girando en
     * libertad es facilísimo perder de vista cuál era el frente—. Se pone a cero al cambiar
     * lo elegido, que es cuando la cuenta deja de significar nada. Ver [girarLaSeleccionConLaMano].
     */
    var vueltaDeLoElegido by mutableStateOf(Postura(0.0, 0.0))
        private set

    /** Dónde está la bolita de seleccionar mientras se pasa por encima del dibujo. */
    var bolita by mutableStateOf<Pt?>(null)
        private set

    /** Lo que se está trazando ahora mismo, ya puesto en el mundo. */
    var trazoEnCurso by mutableStateOf<List<Pt3>>(emptyList())
        private set

    /** Y cuánto apretaba la mano en cada uno de esos puntos. Ver [Trazo3D.presiones]. */
    var presionEnCurso by mutableStateOf<List<Double>>(emptyList())
        private set

    /** Sobre qué plano se está trazando, para el rodillo. Ver [Trazo3D.normal]. */
    var normalEnCurso by mutableStateOf<Pt3?>(null)
        private set

    /**
     * Y la perpendicular de la hoja **en cada punto** de lo que se lleva trazado.
     *
     * Hace falta mientras se traza y no solo al soltar: las tintas que son una superficie se
     * ven ya al trazarlas, y sobre una bola con una sola perpendicular se ven dobladas. Ver
     * [Trazo3D.normales].
     */
    var normalesEnCurso by mutableStateOf<List<Pt3>>(emptyList())

    /**
     * **La hora de cada punto del trazo en curso** — la cuarta lista paralela.
     *
     * En segundos desde la primera muestra. De aquí sale la velocidad REAL de la mano, que
     * es lo que el pulso del motor nuevo usa en vez de inferirla de los huecos proyectados
     * — la dependencia de cámara nº16 del mapa. Ver [Trazo3D.tiempos].
     */
    var tiemposEnCurso by mutableStateOf<List<Double>>(emptyList())
        private set

    /** El instante de la primera muestra, para que los tiempos empiecen en cero. */
    private var arranqueDelReloj = 0L

    /** El tramo de mundo que viene siendo normal en este trazo (media móvil). 0 = aún nada. */
    private var pasoTipico = 0.0

    /**
     * **De qué cara de la hoja es este trazo**: el signo de normal·mirada al bajar el dedo.
     *
     * Es la guarda contra el teleporte del limbo: en una hoja curva vista al sesgo, el rayo
     * puede dejar de cortar la cara que se ve y enganchar la de atrás — y el trazo saltaba
     * medio croquis en un tramo (medido: 422 unidades con vecinos de 3). Una pisada cuya
     * normal mira al revés que la primera es la otra cara: se rechaza, y el trazo se queda
     * en el limbo como se queda en el canto. Cero = sin cara fijada (plano, no hoja).
     */
    private var caraDelTrazo = 0.0

    /**
     * **El 1€ del pincel** — x, y y presión, un filtro por eje.
     *
     * Alisa lo que se PINTA, nunca lo que se MIDE: la lógica del gesto —el temblor, el
     * dedo parado, el compás— sigue leyendo el punto crudo, porque sus umbrales están
     * afinados para la señal cruda y un filtro delante les cambiaría el significado.
     */
    private val alisador = AlisadoDelPuntero()

    /** Dónde caería el dedo ahora, para señalarlo en pantalla. */
    var apoyo by mutableStateOf<Pt3?>(null)
        private set

    /**
     * A dónde va la vista, mientras dura el viaje. Nulo con la vista quieta.
     *
     * **Encajar es un viaje y no un salto.** Puesta de golpe, una vista recta llega sin que
     * uno sepa por dónde ha venido: la escena cambia entera en un fotograma y hay que
     * volver a buscar dónde está cada cosa. Girando hasta ella en un cuarto de segundo se
     * ve **de dónde a dónde** se ha ido, que es justo lo que hace falta para no perderse —y
     * el cubo gira con ella, contando lo mismo desde su esquina—.
     *
     * El viaje lo anda la pantalla, que es la única que tiene reloj de fotogramas: aquí
     * están el punto de partida y el destino, y [andarElViaje] lo recorre. Ver [pedirVista].
     */
    var viaje by mutableStateOf<ViajeDeLaVista?>(null)
        private set

    /**
     * Si el trazo en curso se ha enderezado solo por haber parado el dedo.
     *
     * **Se para y sale recta.** Es el gesto que tienen todas las aplicaciones de dibujo
     * que se usan con la mano, y aquí hacía más falta que en ninguna: en el espacio, una
     * raya recta es la que sirve de arista, de eje y de perfil de un plano que después
     * tiene que quedar plano —y a pulso, sobre una pantalla y girando la vista, no sale
     * recta ni queriendo—. Estaba la regla del interruptor, pero obliga a decidir antes de
     * empezar: se traza, se ve que iba a ser una raya, y se para.
     *
     * A partir de ese momento el trazo es **la punta y donde esté el dedo**, y sigue
     * siéndolo hasta que se levanta: se puede parar, ver que la raya se ha enderezado y
     * seguir estirándola hasta donde se quiera.
     *
     * Lo lee la pantalla para avisar —una raya que se endereza sola sin decir nada se lee
     * como que se ha perdido lo trazado—.
     */
    var enderezandoSolo by mutableStateOf(false)

    /**
     * Que lo trazado ya era una curva cuando el dedo se paró: **no se endereza**.
     *
     * Con pestillo y no preguntándolo cada vez porque [latido] corre **fotograma a
     * fotograma** mientras el dedo está apoyado, y medir la flecha del arco es recorrer el
     * trazo entero: en un trazo largo eso es una pasada por fotograma para volver a
     * contestar lo mismo. Un trazo que ya es curva no deja de serlo por seguir dibujando,
     * así que basta con decidirlo una vez. Ver [yaEsUnaCurva].
     */
    private var esCurvaYSeQueda = false
        private set

    /**
     * Si el trazo en curso se ha convertido en una rueda por haber parado el dedo.
     *
     * **Se para y sale redonda.** Es el mismo gesto que endereza una raya, y lo que decide
     * cuál de los dos toca es lo que se llevaba dibujado: si se ha dado la vuelta y se ha
     * cerrado, era una rueda. A mano no sale ni una cosa ni la otra —un círculo a pulso es
     * una patata, y sobre una hoja escorzada, una patata torcida—, y las dos son de las que
     * hacen falta a todas horas: un eje, un agujero, un asa, el fondo de un vaso.
     *
     * Desde ahí, **el dedo la estira**: la esquina de la que se colgó se queda quieta y la
     * de enfrente sigue al dedo, así que se agranda, se achica y se aplasta a óvalo sin
     * levantar la mano. Ver [esquinasDelLazo].
     */
    var redondeandoSolo by mutableStateOf(false)
        private set

    /**
     * Si hay un trazo en la mano ahora mismo.
     *
     * Lo mira la pantalla para llevarle la hora mientras dura: ver [latido].
     */
    val trazando: Boolean get() = trazoEnCurso.isNotEmpty()

    private var ancho = 1.0
    private var alto = 1.0

    /** El plano sobre el que se está dibujando este trazo. Se decide al bajar el dedo. */
    private var planoDelTrazo: Plano3D? = null

    /** Desde cuándo el dedo no se mueve, y desde dónde. Ver [enderezandoSolo]. */
    private var quietoDesde = 0L
    private var quietoEn: Pt? = null

    /** De qué esquina cuelga la rueda, cuál se lleva el dedo y desde dónde. Ver [redondeandoSolo]. */
    private var anclajeDeLaRueda: Pt3? = null
    private var vivaDeLaRueda: Pt3? = null

    /** Dónde se clavó el compás. Ver [redondeandoSolo]. */
    private var centroDeLaRueda: Pt3? = null
    private var dedoAlRedondear: Pt3? = null

    /** Y sobre qué lámina, si fue sobre una: es lo que deja seguir una superficie curva. */
    private var laminaDelTrazo: String? = null

    /**
     * Lo que ya ha tocado la bolita en este pase.
     *
     * Sin esta lista, arrastrar la bolita por encima de un trazo lo enciende y lo apaga
     * sesenta veces por segundo y al levantar el dedo queda en lo que tocara la suerte.
     * Cada trazo cambia **una vez por pase**: entra si estaba fuera y sale si estaba
     * dentro, que es lo que uno espera al pasar la bolita por encima.
     */
    private var yaTocados: Set<String> = emptySet()

    private val atras = ArrayDeque<Croquis>()
    private val adelante = ArrayDeque<Croquis>()

    val puedeDeshacer: Boolean get() = atras.isNotEmpty()
    val puedeRehacer: Boolean get() = adelante.isNotEmpty()

    /** Lo que mide la vista, para quien tenga que pintar el croquis a otra escala. */
    val anchoDeLaVista: Double get() = ancho
    val altoDeLaVista: Double get() = alto

    fun medida(w: Double, h: Double) {
        ancho = w.coerceAtLeast(1.0)
        alto = h.coerceAtLeast(1.0)
        // Ya se sabe lo que mide el cristal: si había una vista esperando a que se supiera,
        // ahora se puede encuadrar de verdad. Ver [ponerLaVista].
        laVistaQueEspera?.let { laVistaQueEspera = null; ponerLaVista(it) }
    }

    /**
     * La vista que se pidió antes de que la pantalla estuviera medida.
     *
     * Encuadrar una zona pide saber lo que mide el cristal —una zona está dicha en tanto
     * por uno de él—, y al abrir la actividad todavía no se ha medido nada. Se apunta y se
     * cumple en cuanto se sepa, que es un fotograma después.
     */
    private var laVistaQueEspera: String? = null

    // ---------------------------------------------------------------------
    // El dedo
    // ---------------------------------------------------------------------

    /**
     * El dedo, con la hora.
     *
     * La hora entra por la puerta y no se lee aquí dentro para que lo de pararse a
     * enderezar —lo único que depende del tiempo en toda la aplicación— se pueda
     * comprobar sin esperar de verdad medio segundo en cada prueba.
     */
    fun tocar(
        p: Pt,
        fase: Fase,
        /**
         * **La hora del evento, no la de quien lo atiende.**
         *
         * Entre las muestras que llegan juntas en un fotograma hay milisegundos de
         * diferencia, y de ahí sale lo deprisa que iba la mano y si el dedo se ha parado. La
         * pantalla pasa la que trae el evento —la del reloj de arranque—, y el latido cuenta
         * en esa misma: ver [latido]. Mezclar los dos relojes hace que la resta valga las
         * horas que lleva encendido el aparato, y entonces todo parece parado.
         */
        cuando: Long = System.currentTimeMillis(),
        /** Cuánto aprieta la punta, de cero a uno. Uno con el dedo y con quien no lo mide. */
        presion: Double = 1.0
    ) {
        when (fase) {
            Fase.BAJA -> {
                if (herramienta == Herramienta3D.MANO) return
                if (herramienta == Herramienta3D.LICUAR) {
                    anotar()
                    licuandoDesde = p
                    return
                }
                if (herramienta == Herramienta3D.BORRADOR) {
                    borrando = false
                    borrarEn(p)
                    return
                }
                if (herramienta == Herramienta3D.SELECCION) {
                    yaTocados = emptySet()
                    pasarLaBolita(p)
                    return
                }
                val impacto = apoyoDelDedo(p) ?: return
                planoDelTrazo = impacto.plano
                normalEnCurso = impacto.plano.normal
                laminaDelTrazo = impacto.lamina
                val arranque = imantado(impacto.punto, impacto.plano)
                trazoEnCurso = listOf(arranque)
                presionEnCurso = listOf(presion)
                normalesEnCurso = listOf(impacto.plano.normal)
                apoyo = arranque
                enderezandoSolo = false
                esCurvaYSeQueda = false
                quietoDesde = cuando
                quietoEn = p
                alisador.reiniciar()
                arranqueDelReloj = cuando
                tiemposEnCurso = listOf(0.0)
                pasoTipico = 0.0
                caraDelTrazo = impacto.plano.normal.let {
                    it.x * camara.adelante.x + it.y * camara.adelante.y +
                        it.z * camara.adelante.z
                }.let { if (kotlin.math.abs(it) < 1e-6) 0.0 else it }
            }

            Fase.MUEVE -> {
                if (herramienta == Herramienta3D.MANO) return
                if (herramienta == Herramienta3D.LICUAR) {
                    licuar(p)
                    return
                }
                if (herramienta == Herramienta3D.BORRADOR) {
                    borrarEn(p)
                    return
                }
                if (herramienta == Herramienta3D.SELECCION) {
                    pasarLaBolita(p)
                    return
                }
                // El lápiz sigue la superficie punto a punto —por eso una curva se
                // dibuja curva sobre otra curva—; el plano, que todavía no tiene dónde
                // apoyarse, se traza contra el de la pantalla que se fijó al bajar el dedo.
                val enLaLamina = laminaDelTrazo?.let { id ->
                    croquis.laminas.firstOrNull { it.id == id }
                }
                // **El 1€ alisa el pincel, no el gesto.** El punto filtrado es el que se
                // proyecta y se guarda —el trazo sale limpio mientras se traza, no
                // después— y como se filtra EN PANTALLA y luego se reproyecta, el punto
                // alisado sigue apoyado a la hoja a distancia cero, dentro del canto y
                // eligiendo el corte cercano en hoja curva: el intocable de «alisar en la
                // superficie» se cumple por construcción. En modo realidad se puentea: con
                // la cámara movida por los sensores, filtrar en pantalla perseguiría a la
                // cámara en vez de a la mano.
                val alisada = if (realidad) null else alisador.filtrar(p.x, p.y, presion, cuando)
                val pDelPincel = if (alisada == null) p else Pt(alisada.x, alisada.y)
                val presionDelPincel = alisada?.presion ?: presion
                val instante = (cuando - arranqueDelReloj) / 1000.0
                var normalDeAqui: Pt3? = null
                val punto = if (enLaLamina != null) {
                    // El punto anterior va de guía: en una hoja curva, el rayo la corta
                    // dos veces y sin guía el trazo se cuela a la cara de atrás. Ver
                    // [dondeCaeEnElPlano].
                    val donde = dondeCaeEnElPlano(
                        enLaLamina, camara, pDelPincel, ancho, alto, trazoEnCurso.lastOrNull()
                    ) ?: return
                    // **Paseando por fuera de la hoja no se acumulan puntos.** El dedo se
                    // sale, el trazo se queda pegado al canto, y sin esto cada fotograma
                    // metería otro punto en el mismo sitio: cien puntos donde hay uno.
                    if (donde.enElBorde) {
                        val ultimo = trazoEnCurso.lastOrNull()
                        if (ultimo != null && largo(menos(donde.punto, ultimo)) < 1e-3) return
                    }
                    // **Y con qué perpendicular**: en una hoja curva no es la misma en cada
                    // punto, y de ella dependen las tintas que son una superficie. Ver
                    // [Trazo3D.normales].
                    normalDeAqui = donde.plano.normal
                    donde.punto
                } else {
                    (planoDelTrazo ?: return).corte(
                        camara.rayo(pDelPincel, ancho, alto)
                    )?.first ?: return
                }
                // **El dedo parado endereza el trazo.** Se mide en la pantalla y no en el
                // mundo: lo que uno hace es dejar de mover el dedo, y a un aumento pequeño
                // un temblor de dos píxeles son metros del croquis. Ver [enderezandoSolo].
                val anclado = quietoEn
                if (anclado == null || hypot(p.x - anclado.x, p.y - anclado.y) > TEMBLOR) {
                    quietoEn = p
                    quietoDesde = cuando
                } else if (tocaEnderezar(cuando)) {
                    pararseYQueSalgaLimpio(punto)
                }

                // Solo se imanta la punta que se está poniendo, y solo cuando el trazo va
                // recto: en un trazo a pulso, enganchar cada punto intermedio lo dejaría
                // lleno de tirones. Enderezado sí, porque entonces el único punto que se
                // mueve **es** la punta, y una raya recta se traza casi siempre para que
                // acabe donde acaba otra cosa.
                val recto = enderezandoSolo && !redondeandoSolo
                val donde = if (recto) imantado(punto, planoDelTrazo ?: return) else punto
                // Enderezado, el trazo es la punta y el dedo: **se sustituye, no se
                // acumula**. Añadiendo puntos y enderezando solo al soltar, el dedo iba
                // dejando el rastro torcido a la vista y la raya solo aparecía al final.
                normalDeAqui?.let { normalEnCurso = it }
                if (redondeandoSolo) {
                    trazoEnCurso = laRuedaConElDedo(punto)
                    presionEnCurso = emptyList()
                    normalesEnCurso = emptyList()
                    tiemposEnCurso = emptyList()
                } else if (enderezandoSolo) {
                    trazoEnCurso = listOf(trazoEnCurso.first(), donde)
                    presionEnCurso = listOf(presionEnCurso.first(), presionDelPincel)
                    normalesEnCurso = normalesEnCurso.take(1)
                    tiemposEnCurso = listOf(tiemposEnCurso.firstOrNull() ?: 0.0, instante)
                } else {
                    // **Y el punto que no aporta nada no entra.**
                    //
                    // El trazo en curso se guardaba entero de nuevo en cada muestra del
                    // lápiz, y una lista de mil puntos copiada doscientas veces por segundo es
                    // trabajo que **crece con el cuadrado de lo largo que sea el trazo**: el
                    // primer palmo va fino y el metro va a tirones, que es exactamente lo que
                    // se siente. Guardando solo los puntos que aportan forma —uno cada tres
                    // píxeles de pantalla, que es justo lo que se iba a guardar al soltar de
                    // todas formas— la lista se queda corta y el copiado deja de importar.
                    //
                    // Lo trazado sale igual: el aligerado de [soltar] es esta misma regla, así
                    // que lo que se tira aquí es lo que se iba a tirar allí.
                    // **La marcha por la superficie.** Todo lo que se aleja de la última
                    // pisada se recorre por pasos de pantalla adaptativos, con la guarda de
                    // cara y la rampa de los deslizones. Ver [caminarHasta].
                    caminarHasta(pDelPincel, presionDelPincel, instante)
                }
                apoyo = donde
            }

            Fase.LEVANTA -> {
                borrando = false
                if (herramienta == Herramienta3D.LICUAR) {
                    licuandoDesde = null
                    return
                }
                if (herramienta == Herramienta3D.SELECCION) {
                    bolita = null
                    yaTocados = emptySet()
                    return
                }
                clavarLaPunta(p, presion, cuando)
                soltar()
                apoyo = null
                laminaDelTrazo = null
                enderezandoSolo = false
                redondeandoSolo = false
                centroDeLaRueda = null
                anclajeDeLaRueda = null
                quietoEn = null
            }

            Fase.CANCELA -> {
                borrando = false
                if (herramienta == Herramienta3D.LICUAR) {
                    licuandoDesde = null
                    return
                }
                if (herramienta == Herramienta3D.SELECCION) {
                    // La bolita se va, pero lo elegido se queda: al girar para mirar la
                    // selección desde otro lado, perderla sería exactamente lo contrario
                    // de lo que se pretendía.
                    bolita = null
                    yaTocados = emptySet()
                    return
                }
                trazoEnCurso = emptyList()
                presionEnCurso = emptyList()
                normalesEnCurso = emptyList()
                tiemposEnCurso = emptyList()
                normalEnCurso = null
                planoDelTrazo = null
                laminaDelTrazo = null
                apoyo = null
                enderezandoSolo = false
                redondeandoSolo = false
                centroDeLaRueda = null
                anclajeDeLaRueda = null
                quietoEn = null
            }
        }
    }

    /**
     * La hora, mientras el dedo sigue apoyado. **Es lo que hace que mantener pulsado valga.**
     *
     * Pararse a enderezar se medía solo dentro del movimiento del dedo, y ahí estaba el
     * fallo: un dedo **de verdad quieto no manda movimientos**. Android no tiene nada que
     * contar, así que no avisa, así que el contador no corría y la raya no se enderezaba
     * nunca —justo en el caso que el gesto promete, que es dejar el dedo pegado a la
     * pantalla sin moverlo—. Solo salía cuando la mano temblaba lo justo para seguir
     * mandando eventos y no tanto como para pasarse de [TEMBLOR].
     *
     * Así que la pantalla le trae la hora fotograma a fotograma mientras dura el trazo, y
     * el enderezado se cumple con el dedo clavado. Solo late mientras hay algo en la mano:
     * con la aplicación quieta no corre nada.
     */
    fun latido(cuando: Long) {
        // **Con un solo punto ya vale**, que es el caso que este gesto promete: el dedo
        // clavado y quieto. Pidiendo dos, el compás dejó de salir en cuanto los puntos que no
        // aportan forma dejaron de guardarse —un dedo de verdad quieto no llega a moverse los
        // tres píxeles que hacen falta para que se apunte un segundo punto—, así que el gesto
        // solo funcionaba con la mano temblando lo justo. Ver [Fase.MUEVE].
        if (trazoEnCurso.isEmpty() || !tocaEnderezar(cuando)) return
        if (planoDelTrazo == null) return
        pararseYQueSalgaLimpio(trazoEnCurso.last())
    }

    /**
     * **El dedo se ha parado: lo dibujado se pone limpio.**
     *
     * Recta o rueda, según lo que se llevara trazado. Y se hace **ya**, sin esperar a que el
     * dedo se mueva otra vez: si no, el aviso aparecería con la raya todavía torcida.
     */
    private fun pararseYQueSalgaLimpio(dedo: Pt3) {
        val plano = planoDelTrazo ?: return
        // **Parado sin haber ido a ningún sitio: eso es un compás.**
        //
        // El círculo salía de reconocer un lazo: había que dibujar la vuelta entera a pulso
        // y esperar a que el programa dijera «esto era un círculo». Dos cosas mal. Que
        // dibujar la vuelta a pulso es justo lo que uno no quiere hacer —por eso pide un
        // círculo—, y que el reconocimiento acierta o no acierta, así que el gesto no se
        // puede aprender: unas veces sale y otras no.
        //
        // Con el dedo quieto en un sitio no hay nada que reconocer: **ahí está el centro**,
        // y a partir de ahí se tira hasta el radio que uno quiera, viéndolo crecer. Es el
        // compás de toda la vida: se clava la punta y se abre.
        if (esUnPunto(trazoEnCurso)) {
            centroDeLaRueda = trazoEnCurso.first()
            dedoAlRedondear = dedo
            redondeandoSolo = true
            trazoEnCurso = laRuedaConElDedo(dedo)
            presionEnCurso = emptyList()
            tiemposEnCurso = emptyList()
            return
        }
        // **Y una curva trazada a propósito se queda como está.**
        //
        // Enderezar lo que hubiera hacía imposible el plano curvo: una curva se traza
        // despacio, así que uno se para, y al pararse el arco se cambiaba por la cuerda de
        // sus puntas. La hoja nacía plana y no había forma de saber por qué. Ver
        // [yaEsUnaCurva].
        if (yaEsUnaCurva(trazoEnCurso.map { camara.aPantalla(it, ancho, alto) })) {
            esCurvaYSeQueda = true
            return
        }
        enderezandoSolo = true
        trazoEnCurso = listOf(trazoEnCurso.first(), imantado(trazoEnCurso.last(), plano))
        presionEnCurso = listOf(presionEnCurso.first(), presionEnCurso.last())
        tiemposEnCurso = listOf(
            tiemposEnCurso.firstOrNull() ?: 0.0, tiemposEnCurso.lastOrNull() ?: 0.0
        )
    }

    /** La rueda de ahora mismo: clavada en su centro y abierta hasta donde va el dedo. */
    private fun laRuedaConElDedo(dedo: Pt3): List<Pt3> {
        val centro = centroDeLaRueda ?: return trazoEnCurso
        val plano = planoDelTrazo ?: return trazoEnCurso
        val radio = largo(menos(dedo, centro))
        if (radio < 1e-9) return trazoEnCurso
        // **Los tramos los pide el tamaño en pantalla, no una cifra fija.** Con cuarenta y
        // ocho para todos, un círculo pequeño gastaba puntos de sobra y uno grande —o mirado
        // de cerca— se veía como el polígono que era. Se pide un tramo por cada dos píxeles
        // de contorno, que es donde una esquina deja de distinguirse de una curva.
        val vueltaEnPantalla = 2 * Math.PI * radio * camara.zoom
        val pasos = (vueltaEnPantalla / PIXELES_POR_TRAMO).toInt()
            .coerceIn(TRAMOS_MINIMOS_DEL_CIRCULO, TRAMOS_MAXIMOS_DEL_CIRCULO)
        return circuloEnElPlano(centro, radio, plano.normal, camara.derecha, pasos)
    }

    /**
     * Si lo trazado **no se ha ido a ningún sitio**: si es un toque y no un recorrido.
     *
     * Se mide en pantalla y no en unidades del croquis: lo que decide si uno ha querido
     * dibujar o ha querido clavar el compás es cuánto se le ha movido el dedo, y eso es una
     * distancia de pantalla — a cualquier aumento.
     */
    private fun esUnPunto(puntos: List<Pt3>): Boolean {
        if (puntos.isEmpty()) return false
        val desde = puntos.first()
        return puntos.all { largo(menos(it, desde)) * camara.zoom <= LO_QUE_ES_UN_TOQUE }
    }

    /** Si el dedo lleva parado lo suficiente como para que lo trazado salga limpio. */
    private fun tocaEnderezar(cuando: Long): Boolean =
        !enderezandoSolo && !redondeandoSolo && !esCurvaYSeQueda &&
            quietoEn != null && cuando - quietoDesde >= ESPERA_PARA_LA_RECTA

    /**
     * Dónde pisa el dedo en el mundo, por la MISMA tubería que el trazado: sobre la
     * lámina con la guía de continuidad, o contra el plano fijado al bajar el dedo.
     */
    /**
     * **Camina por la pantalla hasta [destino], pisando la superficie a cada paso.**
     *
     * Es la única puerta de entrada de puntos al trazo a mano alzada, y hace tres cosas que
     * un append suelto no puede:
     *
     * - **Recorre los huecos.** Un salto de eventos —o el arrastre del filtro— se sube por
     *   la pantalla en pasos de la puerta de captura, proyectando cada uno con su guía: los
     *   tramos salen del tamaño de los demás.
     * - **Rampa los deslizones.** En una hoja vista casi de canto, tres píxeles de pantalla
     *   son legítimamente muchos metros. Si un paso de mundo sale desproporcionado al paso
     *   típico del trazo, el paso de pantalla se parte por la mitad (hasta medio píxel): la
     *   zona empinada se recorre en rampa suave en vez de a saltos.
     * - **No cruza el limbo.** Una pisada cuya normal mira al revés que la cara con la que
     *   nació el trazo es la cara de atrás de la hoja: el rayo dejó de cortar lo que se ve.
     *   Se para ahí — el trazo se queda en el limbo como se queda en el canto — en vez de
     *   teletransportarse medio croquis (medido: un tramo de 422 con vecinos de 3).
     */
    private fun caminarHasta(
        destino: Pt,
        presion: Double,
        instante: Double,
        puertaDeMundo: Double = minimoEntrePuntos(),
        /**
         * **Que el último punto sea exactamente el destino.**
         *
         * La marcha para al quedarle menos de medio píxel, que es lo razonable mientras se
         * traza. Pero al levantar el lápiz eso deja la punta hasta medio píxel corta, y la
         * punta tiene que caer **donde acabó el lápiz** —es lo que promete [clavarLaPunta] y
         * lo que se ve—. Antes salía bien de casualidad: con la puerta a tres píxeles siempre
         * quedaba más de medio píxel por recorrer. Apretando el muestreo dejó de sobrar, así
         * que ahora se dice en vez de confiarlo.
         */
        remata: Boolean = false
    ) {
        val arranque = trazoEnCurso.lastOrNull() ?: return
        // **Un trazo larguísimo deja de apretar puntos.** Con un punto por píxel, una raya
        // que cruza la pantalla cuatro veces son miles, y cada uno copia las cuatro listas
        // del trazo en curso. Pasados unos cuantos miles, la mano ya no está describiendo un
        // detalle de un píxel: se afloja la puerta y se sigue. Lo que sobre lo quita el
        // aligerado al soltar.
        val puerta =
            if (trazoEnCurso.size > PUNTOS_HOLGADOS) puertaDeMundo * AFLOJE else puertaDeMundo
        var cursor = camara.aPantalla(arranque, ancho, alto)
        val tDesde = tiemposEnCurso.lastOrNull() ?: 0.0
        val saltoTotal = hypot(destino.x - cursor.x, destino.y - cursor.y)
        if (saltoTotal < 1e-9) return
        var previo: Pt3 = arranque
        var paso = MINIMO_EN_PANTALLA
        var vueltas = 0
        while (vueltas < TOPE_DE_LA_MARCHA) {
            vueltas++
            val quedaX = destino.x - cursor.x
            val quedaY = destino.y - cursor.y
            val queda = hypot(quedaX, quedaY)
            if (queda < MEDIO_PIXEL) break
            val d = if (paso >= queda) queda else paso
            val siguiente = Pt(cursor.x + quedaX / queda * d, cursor.y + quedaY / queda * d)
            val pisada = pisadaDelDedo(siguiente)
            if (pisada == null) {
                cursor = siguiente
                continue
            }
            // La guarda de cara: la otra cara de la hoja no es sitio para este trazo.
            val n = pisada.normal
            if (n != null && caraDelTrazo != 0.0) {
                val cara = n.x * camara.adelante.x + n.y * camara.adelante.y +
                    n.z * camara.adelante.z
                if (cara * caraDelTrazo < -1e-6) return
            }
            val tramo = largo(menos(pisada.punto, previo))
            // La rampa: un tramo desproporcionado con sitio para partir el paso, se parte.
            if (pasoTipico > 0.0 && tramo > pasoTipico * DESPROPORCION && d > MEDIO_PIXEL) {
                paso = d / 2
                continue
            }
            cursor = siguiente
            paso = (paso * 1.6).coerceAtMost(MINIMO_EN_PANTALLA)
            if (tramo < puerta) continue
            val f = (1.0 - (queda - d).coerceAtLeast(0.0) / saltoTotal).coerceIn(0.0, 1.0)
            posar(pisada, presion, tDesde + (instante - tDesde) * f, tramo)
            previo = pisada.punto
        }
        if (!remata) return
        val ultimo = trazoEnCurso.lastOrNull() ?: return
        val alFinal = pisadaDelDedo(destino) ?: return
        val cola = largo(menos(alFinal.punto, ultimo))
        if (cola <= 1e-12) return
        posar(alFinal, presion, instante, cola)
    }

    /** Un punto más en el trazo en curso, con lo que lleva pegado. Ver [caminarHasta]. */
    private fun posar(pisada: Pisada, presion: Double, instante: Double, tramo: Double) {
        pisada.normal?.let { normalEnCurso = it }
        trazoEnCurso = trazoEnCurso + pisada.punto
        presionEnCurso = presionEnCurso + presion
        normalesEnCurso = normalesEnCurso +
            (pisada.normal ?: normalEnCurso ?: Pt3(0.0, 0.0, 1.0))
        tiemposEnCurso = tiemposEnCurso + instante
        pasoTipico = if (pasoTipico <= 0.0) tramo else pasoTipico * 0.7 + tramo * 0.3
    }

    private class Pisada(val punto: Pt3, val normal: Pt3?)

    private fun pisadaDelDedo(p: Pt): Pisada? {
        val enLaLamina = laminaDelTrazo?.let { id ->
            croquis.laminas.firstOrNull { it.id == id }
        }
        if (enLaLamina != null) {
            val donde = dondeCaeEnElPlano(
                enLaLamina, camara, p, ancho, alto, trazoEnCurso.lastOrNull()
            ) ?: return null
            return Pisada(donde.punto, donde.plano.normal)
        }
        val corte = (planoDelTrazo ?: return null).corte(camara.rayo(p, ancho, alto))?.first
            ?: return null
        return Pisada(corte, null)
    }

    /**
     * **La punta acaba bajo el lápiz.**
     *
     * El 1€ deja el último punto filtrado unos píxeles por detrás de la mano — es su
     * retardo, y es el precio de alisar. En todas partes se disimula solo; en el instante
     * de levantar es lo que más se ve: la raya se quedaría corta. Así que al levantar se
     * proyecta el punto CRUDO y se remata con él: el trazo acaba exactamente donde acabó
     * el lápiz. El aligerado de [soltar] conserva la última punta, así que el remate
     * sobrevive. En modo realidad no hay filtro, luego no hay nada que rematar.
     */
    private fun clavarLaPunta(p: Pt, presion: Double, cuando: Long) {
        if (realidad) return
        if (trazoEnCurso.isEmpty()) return
        if (herramienta == Herramienta3D.MANO || herramienta == Herramienta3D.BORRADOR ||
            herramienta == Herramienta3D.SELECCION
        ) {
            return
        }
        val punto = pisadaDelDedo(p) ?: return
        val instante = (cuando - arranqueDelReloj) / 1000.0
        when {
            // El compás sigue al dedo: la rueda se abre hasta donde de verdad se soltó.
            redondeandoSolo -> trazoEnCurso = laRuedaConElDedo(punto.punto)
            enderezandoSolo -> {
                val plano = planoDelTrazo ?: return
                trazoEnCurso = listOf(trazoEnCurso.first(), imantado(punto.punto, plano))
                if (presionEnCurso.isNotEmpty()) {
                    presionEnCurso = listOf(presionEnCurso.first(), presion)
                }
                tiemposEnCurso = listOf(tiemposEnCurso.firstOrNull() ?: 0.0, instante)
            }
            else -> {
                // **La cola se remata con la misma marcha que la captura.** El retardo del
                // filtro puede ser varias veces el paso de la puerta, y un remate de un
                // salto dejaría el tramo gigante del final. Ver [caminarHasta].
                caminarHasta(p, presion, instante, puertaDeMundo = 1e-9, remata = true)
            }
        }
    }

    private fun soltar() {
        val bruto = trazoEnCurso
        val brutoDelPulso = presionEnCurso
        val brutoDeLasNormales = normalesEnCurso
        val brutoDelReloj = tiemposEnCurso
        // El plano en el que se estaba dibujando, antes de soltarlo: lo necesita el espejo
        // para saber cuál es la perpendicular de su eje **dentro de la hoja**.
        val planoDeAhora = planoDelTrazo
        trazoEnCurso = emptyList()
        presionEnCurso = emptyList()
        normalesEnCurso = emptyList()
        tiemposEnCurso = emptyList()
        normalEnCurso = null
        planoDelTrazo = null
        if (bruto.size < 2) return

        // Los mismos índices para los puntos y para el pulso: aligerando cada lista por su
        // cuenta se descolocarían entre sí y el grueso dejaría de seguir a la mano.
        // Una rueda ya está limpia: se guarda tal cual, sin aligerar ni enderezar nada. Y
        // sin pulso, que en una figura de compás no pinta nada: lo que la haría gorda por un
        // lado y fina por otro es por dónde iba el dedo, y ahí ya no hay dedo que valga.
        val esUnaRueda = redondeandoSolo
        val indices =
            when {
                esUnaRueda -> bruto.indices.toList()
                // El eje del espejo sale recto siempre: un eje de simetría curvo no es un
                // eje, y nadie que trace uno a pulso quiere la curva que le ha salido.
                herramienta == Herramienta3D.ESPEJO || enderezandoSolo ->
                    listOf(0, bruto.size - 1)
                else -> indicesAligerados(bruto, toleranciaDeLaCurva())
            }
        val puntos = indices.map { bruto[it] }
        val presiones =
            if (esUnaRueda) null else indices.map { brutoDelPulso.getOrElse(it) { 1.0 } }
        // **Las perpendiculares siguen a los puntos por los mismos índices**, como el pulso:
        // aligerando cada lista por su cuenta se descolocarían entre sí y la banda de una
        // superficie se apartaría por la perpendicular de otro punto.
        //
        // Y solo se guardan **si de verdad cambian**. En una hoja plana son todas la misma,
        // y guardar cien copias del mismo vector es engordar el archivo para no decir nada.
        // Ver [Trazo3D.normales].
        val normales = indices
            .mapNotNull { brutoDeLasNormales.getOrNull(it) }
            .takeIf { it.size == puntosDeLosIndices(indices, bruto) && curvan(it) }
        // **La cuarta lista, por los mismos índices** — y es la llave del motor nuevo.
        //
        // La rueda no tiene tiempos de verdad (sus puntos los puso el compás, no la mano):
        // se le sintetizan uniformes, que con presiones a nulo dan pulso parejo — y le
        // abren la puerta del motor nuevo, que es lo que importa. Si las listas se
        // descolocaran (no debería pasar), mejor sin tiempos —el trazo queda en el régimen
        // viejo— que mentir sobre la velocidad de la mano.
        val tiempos = when {
            esUnaRueda -> List(puntos.size) { it / (puntos.size - 1).coerceAtLeast(1).toDouble() }
            brutoDelReloj.size == bruto.size -> indices.map { brutoDelReloj[it] }
            else -> null
        }
        if (puntos.size < 2) return
        // Un trazo de nada es un toque, no un dibujo: al querer girar con un dedo suelto
        // se dejaría un punto en el aire en cada intento.
        if (largoDelTrazo(puntos) < MINIMO_PARA_CONTAR / camara.zoom) return

        anotar()
        croquis = when (herramienta) {
            Herramienta3D.LAMINA -> {
                // **La forma la pone el trazo; la profundidad no se acaba.**
                //
                // Un plano tiene dos direcciones y aquí no se parecen en nada: **a lo ancho
                // es lo que se trazó** —esa es la forma que uno quiso, recta o curva, y por
                // ahí la hoja se acaba donde se acabó la raya—, y **a lo hondo no hay nada
                // que decidir**: es hacia donde se estaba mirando, y ahí la hoja llega hasta
                // donde haga falta. Acabándose también por ahí, el lápiz se quedaba mudo al
                // dibujar un poco más adentro de lo que se había trazado, que es una
                // frontera que nadie ha puesto y que no se ve.
                //
                // **Y a lo ancho es exactamente lo que se trazó.**
                //
                // Se alargaba un poco por las dos puntas, siguiendo por donde iba la curva,
                // con la idea de que un plumazo de cuatro dedos no diera una mesa de cuatro
                // dedos. Pero eso es **adivinar el plano de alguien**: se traza una raya de
                // un palmo y al levantar el dedo aparece medio palmo más por cada lado que
                // uno no ha dibujado, y con una curva ese medio palmo va donde le parece. El
                // plano es lo que se traza; si hace falta más ancho, se traza más ancho, que
                // cuesta un gesto y sale donde uno quiere.
                val perfil = puntos
                val nueva = Lamina3D(
                    id = randomId(),
                    perfil = perfil,
                    // **Se barre hacia donde se está mirando.** Es lo que hace que desde
                    // aquí no se vea nada nuevo y al girar aparezca el plano: la lámina
                    // nace de canto para quien la dibuja.
                    direccion = camara.adelante,
                    // Y con lente puesta, hacia donde se mira **en cada punto**. Las
                    // visuales de una lente van en abanico desde el ojo, no en paralelo:
                    // barriendo todo el perfil en la misma dirección, la lámina nacía
                    // torcida respecto de la raya recién trazada y se veía de nacimiento,
                    // en vez de aparecer al girar. Ver [Lamina3D.direcciones].
                    // **Y una sola dirección de barrido, siempre.**
                    //
                    // Guardar la visual de cada punto servía para que la hoja naciera de
                    // canto con la lente abierta. Con la hoja llegando hasta el fondo eso ya
                    // no puede ser —una hoja sin final no se esconde detrás de su trazo por
                    // mucho que se barra en abanico— y en cambio sí trae un problema: a
                    // treinta pantallas de aquí, la visual de un punto no significa nada.
                    // Ver [Lamina3D.direcciones], que sigue ahí por los croquis de antes.
                    direcciones = null,
                    fondo = EL_PLANO_ENTERO / camara.zoom,
                    infinita = true,
                    // **La hoja es blanca, no del color de la tinta.** Es una mesa de
                    // dibujo: saliendo del color con el que uno estaba pintando, dos hojas
                    // puestas en dos momentos salen de dos colores sin que nadie lo haya
                    // pedido, y encima compiten con el dibujo. Blanca y translúcida es lo
                    // que hace un papel.
                    color = COLOR_DE_LA_HOJA,
                    grosor = grosor
                )
                // **Y el eje de simetría se va con la hoja vieja.** Estaba trazado sobre
                // ella; sobre la nueva, que está en otro sitio y en otra dirección, no
                // significa nada. Se vuelve a trazar en dos segundos, y así nunca hay un
                // eje colgado de un plano que ya no existe.
                //
                // **Un plano a la vez, y el nuevo sustituye al viejo.**
                //
                // Es una mesa de dibujo, no una pila de capas: se coloca donde hace falta,
                // se dibuja, y cuando toca otra orientación se vuelve a colocar. Con
                // varios a la vez habría que elegir en cuál se dibuja —y elegir superficie
                // es exactamente el trabajo que esta aplicación no quiere pedirle a nadie.
                // Lo dibujado encima **se queda donde está**: ya vive en el espacio.
                croquis.copy(laminas = listOf(nueva), espejo = null)
            }
            // **La bola: se clava el centro y se abre, como el compás.**
            //
            // Es el mismo gesto que hace un círculo, y a propósito: una esfera es un círculo
            // que además tiene fondo, y aprender dos gestos para dos cosas que se piden
            // igual no lo hace nadie.
            Herramienta3D.ESFERA -> {
                val radio = largo(menos(puntos.last(), puntos.first()))
                if (radio < MINIMO_PARA_CONTAR / camara.zoom) return
                laBolaRecienPuesta = randomId()
                croquis.copy(
                    laminas = listOf(
                        Lamina3D(
                            id = laBolaRecienPuesta!!,
                            // El perfil queda de recuerdo de por dónde se trazó: quien manda
                            // es la bola. Ver [Lamina3D.esfera].
                            perfil = listOf(puntos.first(), puntos.last()),
                            direccion = camara.adelante,
                            fondo = 0.0,
                            color = COLOR_DE_LA_HOJA,
                            grosor = grosor,
                            esfera = Esfera3D(puntos.first(), radio, forma = formaDeBola)
                        )
                    ),
                    espejo = null
                )
            }
            // **El plano compuesto: la primera línea lo crea y la segunda lo dobla.**
            //
            // La primera nace igual que un plano de barrer, y a propósito: así se ve algo en
            // cuanto se levanta el dedo, se gira la vista con el plano puesto y se traza la
            // otra arista mirando dónde tiene que caer. Si no naciera nada, la segunda línea
            // habría que darla de memoria.
            //
            // La segunda lo convierte en lo que de verdad es: **el perfil, el otro y la regla
            // que va de uno a otro**. Ahí deja de barrer —`fondo = 0` y ya no es infinita—,
            // porque la hoja ya no llega hasta el fondo: llega hasta la segunda arista.
            Herramienta3D.PLANO_COMPUESTO -> {
                val aMedias = laHojaAMedias()
                if (aMedias == null) {
                    val nueva = randomId()
                    elPlanoAMedias = nueva
                    croquis.copy(
                        laminas = listOf(
                            Lamina3D(
                                id = nueva,
                                perfil = puntos,
                                direccion = camara.adelante,
                                direcciones = null,
                                fondo = EL_PLANO_ENTERO / camara.zoom,
                                infinita = true,
                                color = COLOR_DE_LA_HOJA,
                                grosor = grosor
                            )
                        ),
                        espejo = null
                    )
                } else {
                    val (uno, otro) = emparejados(aMedias.perfil, puntos)
                    elPlanoAMedias = null
                    croquis.copy(
                        laminas = listOf(
                            aMedias.copy(
                                perfil = uno,
                                otroPerfil = otro,
                                fondo = 0.0,
                                infinita = false
                            )
                        ),
                        espejo = null
                    )
                }
            }
            Herramienta3D.ESPEJO -> {
                val eje = normalizado(menos(puntos.last(), puntos.first()))
                val deLaHoja = planoDeAhora?.normal ?: camara.adelante
                val normal = normalizado(producto(eje, deLaHoja))
                // Un eje que se ve de punta no da plano: la perpendicular sale de nada y
                // reflejar mandaría el dibujo a cualquier sitio. Se deja el que hubiera.
                if (largo(normal) < 0.5) return
                espejoPuesto = true
                croquis.copy(espejo = Espejo3D(puntos.first(), eje, normal))
            }

            else -> {
                val trazo = Trazo3D(
                    randomId(), puntos, color, grosorDeVerdad, pincel,
                    // Lo que mide en el mundo, para que a partir de ahora sea un sólido y
                    // no una raya pintada en el cristal. **Y sin dividir por el aumento**:
                    // el número del selector es una medida del croquis, no de la pantalla de
                    // ese momento. Ver [Trazo3D.calibre].
                    calibre = grosorDeVerdad,
                    presiones = presiones,
                    tiempos = tiempos,
                    opacidad = opacidad,
                    normal = planoDeAhora?.normal,
                    normales = normales,
                    colorApagada = colorApagada.takeIf { pincel == Pincel.LUZ || punta.alumbra },
                    // Lo que se dibuja va a la capa que esté puesta. Ver [capaActiva].
                    grupo = capaActiva,
                    luz = luz,
                    punta = punta,
                    // Hacia dónde mira el canto **en el mundo**: la derecha de la pantalla
                    // aplastada contra el plano y girada lo que diga el ladeo. Guardado así,
                    // la punta gira con el dibujo en vez de con la cámara.
                    ejeDeLaPunta = ejeDeLaPunta(planoDeAhora?.normal ?: camara.adelante)
                )
                // **Y su reflejo, si hay espejo puesto.** Nace como un trazo más y no
                // como una copia atada al original: se puede borrar, mover o repintar por
                // separado, que es lo que uno acaba queriendo en cuanto la pieza deja de
                // ser simétrica del todo. El pulso se refleja con él, así que los dos lados
                // salen con el mismo pulso de mano.
                val reflejado = croquis.espejo
                    ?.takeIf { espejaAhora }
                    ?.let {
                        trazo.copy(
                            id = randomId(),
                            puntos = it.reflejo(trazo.puntos),
                            // Y la normal también, o la banda del rodillo del otro lado
                            // quedaría tumbada sobre la hoja de este.
                            normal = trazo.normal?.let { n -> it.reflejoDeDireccion(n) }
                        )
                    }
                croquis.copy(trazos = croquis.trazos + trazo + listOfNotNull(reflejado))
            }
        }

        // **La bola nace elegida y con el mando puesto.**
        //
        // Una bola perfecta casi nunca es lo que se quiere: se quiere un huevo, una cúpula
        // rebajada, algo estirado por un lado. Naciendo elegida, lo siguiente que se toca es
        // el mando y ya se le está dando forma, sin tener que ir a buscarla con la bolita de
        // seleccionar antes de poder tocarla.
        laBolaRecienPuesta?.let {
            laBolaRecienPuesta = null
            herramienta = Herramienta3D.SELECCION
            elegir(setOf(it))
        }
    }

    /** Si el borrador va apoyado: mientras dure, lo que se lleve es una sola anotación. */
    private var borrando = false

    /** La bola que se acaba de poner, para dejarla elegida. */
    private var laBolaRecienPuesta: String? = null

    /**
     * **El plano compuesto que está a medias**: el que ya tiene su primera línea y espera la
     * segunda. Vacío, la siguiente línea empieza uno nuevo.
     *
     * Es lo que hace que la herramienta se turne sola —una línea crea, la otra dobla, la
     * siguiente vuelve a crear— sin ningún botón de «ahora la otra». Y es un **id** y no un
     * `true/false` a propósito: si por el camino se deshace, se pone otro plano o se cambia
     * de herramienta, la hoja que hay deja de ser esta y la cuenta se pone a cero sola. Un
     * interruptor se quedaría encendido esperando la segunda línea de un plano que ya no
     * existe. Ver [Herramienta3D.PLANO_COMPUESTO].
     */
    var elPlanoAMedias by mutableStateOf<String?>(null)
        private set

    /** Si la siguiente línea del plano compuesto va a doblar el que hay, en vez de crear uno. */
    val esperandoLaSegundaLinea: Boolean
        get() = elPlanoAMedias != null && elPlanoAMedias == laHojaAMedias()?.id

    /** La hoja que está esperando su segundo riel, si es que la hay. */
    private fun laHojaAMedias(): Lamina3D? = croquis.laminas.firstOrNull()
        ?.takeIf { it.id == elPlanoAMedias && it.otroPerfil == null && it.esfera == null }

    /**
     * Hacia dónde mira el canto de la punta, **dentro del plano en el que se dibuja**.
     *
     * Se toma la derecha de la pantalla, se aplasta contra el plano —que es la dirección
     * «hacia la derecha» de esa superficie tal y como se está viendo— y se gira lo que diga
     * el ladeo. Ver [Trazo3D.ejeDeLaPunta].
     */
    private fun ejeDeLaPunta(normal: Pt3): Pt3? {
        // Toda punta que **no da lo mismo desde todos lados** necesita su eje: la de sección
        // dibujada, la alargada de canto y el listón, que es un cuadrado y tiene esquinas.
        // La redonda no: un círculo se ve igual se mire desde donde se mire.
        val haceFalta =
            punta.tienePerfil || punta.deCanto || pincel.vigente == Pincel.CUADRADO
        if (!haceFalta) return null
        val n = normalizado(normal)
        var u = menos(camara.derecha, por(n, escalar(camara.derecha, n)))
        if (largo(u) < 1e-6) u = camara.derecha
        u = normalizado(u)
        val v = normalizado(producto(n, u))
        val cos = kotlin.math.cos(punta.angulo)
        val sen = kotlin.math.sin(punta.angulo)
        return normalizado(mas(por(u, cos), por(v, sen)))
    }

    /**
     * La punta enganchada a lo que ya hay, **si cae cerca y en el mismo plano**.
     *
     * Sin esto no se puede construir nada: dos rayas que tenían que juntarse quedan a
     * medio píxel, la lámina que se apoya en ellas nace despegada y el croquis se deshace
     * en cuanto se gira. Con el imán, unir es tocar cerca.
     *
     * **Solo engancha a puntas del mismo plano.** En la pantalla, dos puntos separados por
     * medio metro de profundidad se ven pegados: enganchar por lo que se ve mandaría el
     * trazo a otra altura del espacio, que es de los errores más difíciles de ver y de
     * deshacer. Se compara en el mundo y contra el plano en el que se está dibujando.
     */
    private fun imantado(punto: Pt3, plano: Plano3D): Pt3 {
        val cerca = UMBRAL_DEL_IMAN / camara.zoom
        var mejor: Pt3? = null
        var mejorDistancia = cerca
        fun probar(candidato: Pt3) {
            // Fuera del plano de trabajo no se engancha: ver el porqué arriba.
            if (abs(escalar(menos(candidato, plano.punto), plano.normal)) > cerca) return
            val d = largo(menos(candidato, punto))
            if (d < mejorDistancia) {
                mejorDistancia = d
                mejor = candidato
            }
        }
        for (t in croquis.trazos) {
            probar(t.puntos.first())
            probar(t.puntos.last())
        }
        for (l in croquis.laminas) {
            probar(l.perfil.first())
            probar(l.perfil.last())
        }
        return mejor ?: punto
    }

    /**
     * Dónde apoya el dedo, **y toda la ley de la aplicación en cuatro líneas**.
     *
     * - El **plano** se traza sobre la pantalla, porque es lo único que puede: es el gesto
     *   que crea la superficie, y antes de él no hay ninguna sobre la que apoyarse.
     * - El **lápiz** dibuja sobre la hoja y solo sobre la hoja: si el dedo se sale, el
     *   trazo se queda pegado a su borde. Sin hoja no dibuja nada.
     *
     * Esto último es una decisión y no una limitación. Un trazo en el aire, en el plano de
     * la pantalla, se ve perfecto desde donde se hizo y **se descoloca en cuanto giras**:
     * quedó a la profundidad en la que estaba la cámara, que no es ningún sitio. Obligando
     * a que todo se apoye, lo dibujado está donde uno cree que está.
     */
    private fun apoyoDelDedo(p: Pt): Impacto? {
        // La hoja y la bola se trazan sobre la pantalla: son los gestos que **crean** la
        // superficie, y antes de ellos no hay ninguna sobre la que apoyarse.
        // **Y el plano compuesto, sus dos líneas.** La segunda también: si se apoyara en la
        // hoja que acaba de nacer, la otra arista saldría pegada a la primera y la
        // superficie no tendría fondo — que es justo lo que se ha venido a dar.
        if (herramienta == Herramienta3D.LAMINA ||
            herramienta == Herramienta3D.ESFERA ||
            herramienta == Herramienta3D.PLANO_COMPUESTO
        ) {
            val rayo = camara.rayo(p, ancho, alto)
            val plano = Plano3D(camara.centro, camara.adelante)
            val corte = plano.corte(rayo) ?: return null
            return Impacto(corte.first, plano, null, corte.second)
        }
        val laBase = croquis.laminas.firstOrNull() ?: return null
        return dondeCaeEnElPlano(laBase, camara, p, ancho, alto)
    }

    private fun minimoEntrePuntos(): Double = MINIMO_EN_PANTALLA / camara.zoom

    /** Lo que se le deja apartarse a la línea al aligerar, en unidades del croquis. */
    private fun toleranciaDeLaCurva(): Double = TOLERANCIA_DE_LA_CURVA / camara.zoom

    /** Cuántos puntos van a quedar: para no guardar unas perpendiculares descolocadas. */
    private fun puntosDeLosIndices(indices: List<Int>, bruto: List<Pt3>): Int =
        indices.count { it in bruto.indices }

    /**
     * Si un puñado de perpendiculares **no son todas la misma**: si la hoja se curva.
     *
     * Con media docena de grados basta para que se note en una banda ancha, y por debajo de
     * eso es el ruido de las cuentas. Ver [Trazo3D.normales].
     */
    private fun curvan(normales: List<Pt3>): Boolean {
        val primera = normales.firstOrNull() ?: return false
        return normales.any { escalar(primera, it) < COSENO_DE_HOJA_CURVA }
    }

    private fun largoDelTrazo(puntos: List<Pt3>): Double =
        puntos.zipWithNext().sumOf { (a, b) -> largo(menos(b, a)) }

    // ---------------------------------------------------------------------
    // Seleccionar
    // ---------------------------------------------------------------------

    /**
     * La bolita de seleccionar: **enciende y apaga lo que toca**.
     *
     * No hay rectángulo que encuadrar ni tecla que mantener. Se pasa la bolita por encima
     * de lo que se quiera y va entrando; se vuelve a pasar por algo que ya estaba dentro y
     * sale. Es la forma de seleccionar que no obliga a pensar en la selección: sirve igual
     * para coger cuatro trazos de un barrido que para quitar uno que sobra, con el mismo
     * gesto y sin cambiar de modo.
     *
     * El radio va en píxeles de pantalla porque es lo que uno ve: en el mundo, la misma
     * bolita cogería media escena de lejos y nada de cerca.
     */
    private fun pasarLaBolita(p: Pt) {
        bolita = p
        val radio = RADIO_DE_LA_BOLITA
        var nueva = seleccion
        for (t in croquis.trazos) {
            if (t.oculto || t.id in yaTocados) continue
            if (!cercaEnPantalla(t.puntos, p, radio)) continue
            // **Tocar uno de un grupo los coge a todos.** Para eso está el grupo: si hubiera
            // que pasar la bolita por los catorce trazos del respaldo cada vez, agruparlos
            // no habría servido de nada.
            val suyos =
                if (t.grupo == null) listOf(t)
                else croquis.trazos.filter { it.grupo == t.grupo && !it.oculto }
            // Un conjunto que se rehacía entero por cada muestra del dedo y por cada trazo
            // tocado. Se apunta encima del que hay: pasar la bolita es un gesto seguido, y lo
            // ya tocado solo crece hasta que se levanta el dedo.
            val tocados = HashSet(yaTocados)
            suyos.forEach { tocados += it.id }
            yaTocados = tocados
            val ids = suyos.mapTo(HashSet()) { it.id }
            nueva = if (t.id in nueva) nueva - ids else nueva + ids
        }
        // Y las imágenes, que no son una raya sino una superficie: se cogen apuntándoles
        // dentro, que es como uno señala una foto.
        val rayo = camara.rayo(p, ancho, alto)
        for (i in croquis.imagenes) {
            if (i.oculto || i.id in yaTocados) continue
            if (corteConCuadrilatero(rayo, i.esquinas) == null) continue
            yaTocados = yaTocados + i.id
            nueva = if (i.id in nueva) nueva - i.id else nueva + i.id
        }
        if (nueva != seleccion) elegir(nueva)
    }

    // ---------------------------------------------------------------------
    // Mover, girar, redimensionar y copiar lo elegido
    // ---------------------------------------------------------------------

    /**
     * Empieza un manejo: **una sola anotación para todo el gesto**.
     *
     * El mando manda decenas de retoques por segundo. Anotando cada uno, el historial se
     * llena de un mismo movimiento partido en cien pasos y deshacer deja de servir para
     * nada: habría que tocarlo cien veces para volver a donde se estaba.
     */
    fun empezarAManejar() {
        if (seleccion.isEmpty()) return
        anotar()
    }

    /**
     * Mueve lo elegido **por el plano de la hoja**, tantos píxeles de pantalla.
     *
     * Píxeles de pantalla y no unidades del mundo: lo que se pide es que el dibujo se mueva
     * **lo mismo que se ha movido el dedo**. Convertirlo a través del plano, y no sumarlo a
     * ojo, es lo que hace que eso siga siendo cierto con la vista girada, escorzada o con
     * la lente abierta.
     */
    fun moverLaSeleccion(dx: Double, dy: Double) {
        val centro = centroDeLaSeleccion() ?: return
        val plano = planoDeManejo(centro)
        val enPantalla = camara.aPantalla(centro, ancho, alto)
        val destino = plano.corte(
            camara.rayo(Pt(enPantalla.x + dx, enPantalla.y + dy), ancho, alto)
        )?.first ?: return
        val d = menos(destino, centro)
        cambiarLaSeleccion { mas(it, d) }
    }

    /**
     * Mueve lo elegido **por un eje del mundo**, tantos píxeles de pantalla.
     *
     * Es la otra mitad de [moverLaSeleccion]: aquella coloca dentro de la hoja, esta va por
     * un eje y solo por él. Hacen falta las dos. Por el plano se coloca a ojo, que es lo que
     * uno hace casi siempre; por un eje se ajusta, se alinea y —con la `z`— **se despega de
     * la hoja**, que dentro del plano no se puede por definición.
     *
     * La cuenta es la que hace que se sienta directo: se mira **hacia dónde va ese eje en la
     * pantalla**, se proyecta sobre él lo que se ha movido el dedo, y eso es lo que anda la
     * figura. Arrastrando en la dirección del eje se mueve todo lo arrastrado; arrastrando
     * en cruz no se mueve nada, que es exactamente lo que uno espera de un eje.
     */
    fun moverLaSeleccionEnEje(eje: EjeDelMundo, dx: Double, dy: Double) {
        val direccion = eje.direccion ?: return moverLaSeleccion(dx, dy)
        val centro = centroDeLaSeleccion() ?: return
        val enPantalla = enPantallaDelEje(centro, direccion) ?: return
        // Cuántos píxeles **del eje** ha pedido el dedo: lo arrastrado, proyectado sobre la
        // dirección en la que se ve el eje. En cruz sale cero, que es lo que tiene que ser.
        val porUnidad = hypot(enPantalla.x, enPantalla.y)
        if (porUnidad < 1e-9) return
        moverLaSeleccionPorElEje(eje, (dx * enPantalla.x + dy * enPantalla.y) / porUnidad)
    }

    /**
     * Y lo mismo pidiendo directamente **cuántos píxeles avanzar por el eje**.
     *
     * Es lo que manda el mando: sus flechas están puestas en triángulo y no apuntando a
     * donde de verdad va cada eje, así que quien arrastra una flecha ya sabe cuánto ha
     * pedido —lo que se ha corrido por la flecha— y no hace falta volver a proyectarlo.
     *
     * La escala sale de **cómo se ve el eje**: cien píxeles de arrastre son cien píxeles de
     * recorrido en la pantalla. Con un eje visto de punta eso no existe —no se ve nada de
     * él— y entonces manda el aumento, que es la escala del resto de la escena: sin esa
     * salida, mirando desde arriba la `z` no se podría mover, que es justo cuando más falta
     * hace despegar algo de la hoja.
     */
    fun moverLaSeleccionPorElEje(eje: EjeDelMundo, avance: Double) {
        val direccion = eje.direccion ?: return
        val centro = centroDeLaSeleccion() ?: return
        val enPantalla = enPantallaDelEje(centro, direccion)
        val porUnidad = enPantalla?.let { hypot(it.x, it.y) } ?: 0.0
        val escala = if (porUnidad > camara.zoom * DE_PUNTA) porUnidad else camara.zoom
        if (escala < 1e-9) return
        cambiarLaSeleccion { mas(it, por(direccion, avance / escala)) }
    }

    /**
     * Hacia dónde va un eje del mundo en la pantalla, en píxeles por unidad.
     *
     * Se mide proyectando de verdad dos puntos y no con una fórmula: así vale igual con la
     * vista girada, escorzada o con la lente abierta, que es donde una fórmula fallaría.
     * Devuelve nulo cuando el eje apunta hacia la cámara —visto de punta no se puede
     * arrastrar por él, y forzarlo mandaría la figura al infinito con un temblor—.
     */
    fun enPantallaDelEje(centro: Pt3, direccion: Pt3): Pt? {
        val paso = 1.0
        val a = camara.aPantalla(centro, ancho, alto)
        val b = camara.aPantalla(mas(centro, por(direccion, paso)), ancho, alto)
        val v = Pt((b.x - a.x) / paso, (b.y - a.y) / paso)
        return if (hypot(v.x, v.y) < MINIMO_DEL_EJE_EN_PANTALLA) null else v
    }

    /** Y el mismo eje, para pintarlo: nulo si está de punta. */
    fun ejeEnPantalla(eje: EjeDelMundo): Pt? {
        val direccion = eje.direccion ?: return null
        val centro = centroDeLaSeleccion() ?: return null
        return enPantallaDelEje(centro, direccion)
    }

    /**
     * Gira lo elegido sobre sí mismo.
     *
     * Alrededor del eje que se pida, y sin eje, alrededor de la normal de la hoja — que es
     * girar dentro del papel, el giro que uno quiere casi siempre.
     */
    fun girarLaSeleccion(radianes: Double, alrededorDe: EjeDelMundo = EjeDelMundo.LIBRE) {
        val centro = centroDeLaSeleccion() ?: return
        girarAlrededorDe(alrededorDe.direccion ?: planoDeManejo(centro).normal, radianes)
    }

    /**
     * **Girar lo elegido con la mano, como se gira la vista.**
     *
     * Es lo que hace el cubo cuando hay algo elegido: se arrastra y la figura se voltea con
     * libertad, sin elegir eje ni pensar en cuál es cuál. La cuenta es la de una bola de
     * mando: **arrastrar de lado la gira alrededor del arriba de la pantalla y arrastrar
     * hacia abajo alrededor de su derecha**, así que la figura acompaña al dedo mire uno
     * desde donde mire.
     *
     * Alrededor de los ejes **de la cámara** y no de los del mundo, y ahí está lo bueno: no
     * hay ninguna postura a la que no se llegue, que es justo lo que le falta a girar por
     * `x`, `y` o `z` —esos tres dan tres giros, y lo que uno quiere es ponerlo *así*—.
     */
    fun girarLaSeleccionConLaMano(dx: Double, dy: Double) {
        if (seleccion.isEmpty()) return
        val vuelta = Math.PI * 2 / VUELTA_DE_LA_MANO
        if (dx != 0.0) girarAlrededorDe(camara.arriba, -dx * vuelta)
        if (dy != 0.0) girarAlrededorDe(camara.derecha, -dy * vuelta)
        // Y se apunta lo volteado, para que el cubo lo cuente. Ver [vueltaDeLoElegido].
        vueltaDeLoElegido = Postura(
            vueltaDeLoElegido.giro + dx * vuelta,
            vueltaDeLoElegido.inclinacion + dy * vuelta
        )
    }

    /** Rodrigues: girar lo elegido alrededor de un eje cualquiera, por su centro. */
    private fun girarAlrededorDe(direccion: Pt3, radianes: Double) {
        val centro = centroDeLaSeleccion() ?: return
        val eje = normalizado(direccion)
        if (largo(eje) < 0.5) return
        val cos = kotlin.math.cos(radianes)
        val sen = kotlin.math.sin(radianes)
        cambiarLaSeleccion { p ->
            // Tres productos y una suma, sin construir ninguna matriz.
            val v = menos(p, centro)
            mas(
                centro,
                mas(
                    mas(por(v, cos), por(producto(eje, v), sen)),
                    por(eje, escalar(eje, v) * (1 - cos))
                )
            )
        }
    }

    /** Agranda o encoge lo elegido alrededor de su centro. */
    fun escalarLaSeleccion(factor: Double) {
        if (factor <= 0.0 || abs(factor - 1.0) < 1e-6) return
        val centro = centroDeLaSeleccion() ?: return
        cambiarLaSeleccion { mas(centro, por(menos(it, centro), factor)) }
        laBolaElegida()?.let { bola ->
            ponerLaBola(bola.copy(radio = bola.radio * factor))
        }
    }

    /**
     * **Estira lo elegido sin guardar la proporción**: una por lo ancho y otra por lo alto.
     *
     * La cajita de siempre agranda a lo bestia —todo por igual—, y eso vale para acercar una
     * pieza al tamaño de otra. Pero media forma que uno quiere no está a otra escala: está
     * **estirada**. Un óvalo es un círculo estirado, un ladrillo es un cubo estirado, una
     * cúpula rebajada es media bola aplastada. Sin esto hay que dibujarlos otra vez.
     *
     * Se estira por **lo ancho y lo alto de la pantalla**, que es por donde se está tirando:
     * lo que uno ve moverse es lo que ha pedido. Lo que salga hacia el fondo se queda como
     * está, que es lo que hace cualquier tirador de esquina.
     */
    fun deformarLaSeleccion(porLoAncho: Double, porLoAlto: Double) {
        if (porLoAncho <= 0.0 || porLoAlto <= 0.0) return
        if (abs(porLoAncho - 1.0) < 1e-6 && abs(porLoAlto - 1.0) < 1e-6) return
        val centro = centroDeLaSeleccion() ?: return
        val u = camara.derecha
        val v = camara.arriba
        cambiarLaSeleccion { p ->
            val d = menos(p, centro)
            val x = escalar(d, u)
            val y = escalar(d, v)
            val resto = menos(d, mas(por(u, x), por(v, y)))
            mas(centro, mas(resto, mas(por(u, x * porLoAncho), por(v, y * porLoAlto))))
        }
        laBolaElegida()?.let { bola ->
            // La bola no tiene puntos que mover: tiene tres estirados, uno por **cada uno de
            // sus ejes**. Se reparte lo pedido entre ellos según cuánto mire cada eje a lo
            // ancho y a lo alto de la pantalla, que es lo que uno está viendo estirarse. Por
            // los suyos y no por los del mundo: en cuanto la bola se ha girado, los del mundo
            // ya no son sus lados, y estirar «a lo ancho» le crecía por donde no era.
            fun porEje(eje: Pt3): Double =
                1.0 + (porLoAncho - 1.0) * abs(escalar(u, eje)) +
                    (porLoAlto - 1.0) * abs(escalar(v, eje))
            ponerLaBola(
                bola.copy(
                    escala = Pt3(
                        bola.escala.x * porEje(bola.unos),
                        bola.escala.y * porEje(bola.otros),
                        bola.escala.z * porEje(bola.terceros)
                    )
                )
            )
        }
    }

    /** La bola, si es lo que está elegido. Ver [Esfera3D]. */
    private fun laBolaElegida(): Esfera3D? =
        croquis.laminas.firstOrNull { it.id in seleccion }?.esfera

    private fun ponerLaBola(nueva: Esfera3D) {
        croquis = croquis.copy(
            laminas = croquis.laminas.map {
                if (it.id in seleccion && it.esfera != null) it.copy(esfera = nueva) else it
            }
        )
    }

    // ---------------------------------------------------------------------
    // Licuar
    // ---------------------------------------------------------------------

    /**
     * **El radio del pincel de licuar**, en píxeles de pantalla.
     *
     * De pantalla y no del mundo a propósito: lo que uno empuja es lo que ve bajo el dedo,
     * y de lejos quiere mover una zona grande del croquis y de cerca un detalle. Es la misma
     * decisión que la bolita de seleccionar.
     */
    var radioDeLicuar by mutableStateOf(RADIO_DE_LICUAR)

    /** Dónde estaba el dedo en la última muestra, mientras se licúa. */
    private var licuandoDesde: Pt? = null

    /**
     * **Licuar: empujar y tirar de lo dibujado con el dedo.** Es el «3D Liquify» de Feather.
     *
     * Todo punto de cualquier trazo que caiga, **en la pantalla**, a menos de
     * [radioDeLicuar] del dedo se mueve con él: lo que está justo debajo se mueve tanto como
     * el dedo, y hacia el borde del pincel cada vez menos, con la campana suave
     * `(1 - (d/r)²)²` que usan los pinceles de deformar de toda la vida — sin escalón en el
     * borde, sin pico en el centro. El movimiento se hace en el plano de la vista, a la
     * escala de la vista: un píxel de dedo es un píxel de trazo, mire uno desde donde mire.
     *
     * Los trazos no cambian de puntos —ni se parten ni se rehacen—, solo se recolocan, así
     * que conservan su pulso, sus tiempos y sus normales. El deshacer devuelve el croquis
     * entero de antes de bajar el dedo. Lo escondido no se toca: no se ve, no se empuja.
     */
    private fun licuar(p: Pt) {
        val desde = licuandoDesde ?: return
        val dx = p.x - desde.x
        val dy = p.y - desde.y
        licuandoDesde = p
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) return
        val radio = radioDeLicuar.coerceAtLeast(1.0)
        // El empujón en el mundo: la derecha y el arriba de la vista, a la escala de la
        // vista. La pantalla tiene la y hacia abajo, de ahí el signo.
        val empujon = mas(por(camara.derecha, dx / camara.zoom), por(camara.arriba, -dy / camara.zoom))
        val base = camara.base(ancho, alto)
        var tocado = false
        val trazos = croquis.trazos.map { t ->
            if (t.oculto) return@map t
            var cambiado = false
            val puntos = t.puntos.map { q ->
                val enPantalla = base.aPantalla(q)
                val d = kotlin.math.hypot(enPantalla.x - desde.x, enPantalla.y - desde.y)
                if (d >= radio) q else {
                    val u = d / radio
                    val peso = (1 - u * u).let { it * it }
                    cambiado = true
                    mas(q, por(empujon, peso))
                }
            }
            if (!cambiado) t else { tocado = true; t.copy(puntos = puntos) }
        }
        if (tocado) croquis = croquis.copy(trazos = trazos)
    }

    /**
     * **Mete trazos ya hechos** —una gráfica, lo que venga de fuera— y los deja elegidos con
     * la flecha puesta, que es lo primero que se hace con algo recién puesto: colocarlo. Un
     * solo paso del historial para todos. Ver [Graficas3D].
     */
    fun insertarTrazos(nuevos: List<Trazo3D>) {
        if (nuevos.isEmpty()) return
        anotar()
        croquis = croquis.copy(trazos = croquis.trazos + nuevos)
        herramienta = Herramienta3D.SELECCION
        elegir(nuevos.map { it.id }.toSet())
    }

    /**
     * Copia lo elegido y **se queda con la copia elegida**.
     *
     * Con la copia seleccionada, lo siguiente que se haga con el mando la coloca: copiar y
     * mover son un solo gesto seguido, que es como se usa siempre. Dejando elegido el
     * original, la copia nacería encima y escondida, y habría que ir a buscarla.
     */
    fun copiarLaSeleccion() {
        if (seleccion.isEmpty()) return
        anotar()
        val centro = centroDeLaSeleccion()
        // Un pelín corrida, para que se vea que hay dos y no una.
        val aparte = centro?.let { por(camara.derecha, CORRIMIENTO_DE_LA_COPIA / camara.zoom) }
            ?: Pt3(0.0, 0.0, 0.0)
        val copias = croquis.trazos.filter { it.id in seleccion }.map {
            // La copia sale suelta: si naciera dentro del grupo del original, moverla
            // movería también al original —tocar uno los coge a todos— y sería imposible
            // separarla de lo que se acaba de copiar.
            it.copy(id = randomId(), grupo = null, puntos = it.puntos.map { q -> mas(q, aparte) })
        }
        val copiasDeImagen = croquis.imagenes.filter { it.id in seleccion }.map {
            it.copy(id = randomId(), grupo = null, esquinas = it.esquinas.map { q -> mas(q, aparte) })
        }
        croquis = croquis.copy(
            trazos = croquis.trazos + copias,
            imagenes = croquis.imagenes + copiasDeImagen
        )
        elegir((copias.map { it.id } + copiasDeImagen.map { it.id }).toSet())
    }

    private fun cambiarLaSeleccion(como: (Pt3) -> Pt3) {
        if (seleccion.isEmpty()) return
        croquis = croquis.copy(
            trazos = croquis.trazos.map {
                if (it.id in seleccion) it.copy(puntos = it.puntos.map(como)) else it
            },
            // Una imagen se maneja **igual que un trazo**: se le mueven sus cuatro esquinas
            // y ya está girada, escalada o volteada. Ver [Imagen3D].
            imagenes = croquis.imagenes.map {
                if (it.id in seleccion) it.copy(esquinas = it.esquinas.map(como)) else it
            },
            // **Y la bola, por su centro y por sus tres ejes.**
            //
            // No tiene puntos que mover, así que lo que se le mueve es de dónde cuelga —y,
            // en cuanto deja de ser redonda, **hacia dónde mira**—. Moviéndole solo el
            // centro, girar un huevo era girar su centro alrededor de sí mismo, o sea nada:
            // el huevo se quedaba mirando al mismo sitio mientras el resto del croquis
            // giraba. Los ejes se llevan con la misma cuenta que cualquier punto, restándole
            // el centro ya movido: así valen para girar, para voltear y para lo que venga,
            // sin que esto tenga que saber cuál de las tres cosas se está haciendo.
            //
            // Lo que le cambia el tamaño va aparte, en cada gesto: aquí solo se acompaña.
            laminas = croquis.laminas.map {
                val bola = it.esfera
                if (it.id in seleccion && bola != null) {
                    val centro = como(bola.centro)
                    val ejes = listOf(bola.unos, bola.otros, bola.terceros).map { eje ->
                        val movido = menos(como(mas(bola.centro, eje)), centro)
                        if (largo(movido) < 1e-9) eje else normalizado(movido)
                    }
                    it.copy(esfera = bola.copy(centro = centro, ejes = ejes))
                } else it
            }
        )
    }

    /**
     * Cambia lo elegido, **y pone a cero la cuenta de lo volteado**.
     *
     * Por aquí pasan todas: lo volteado es de esta selección y de ninguna otra, y dejando la
     * cuenta puesta el cubo contaría de la figura de antes.
     */
    private fun elegir(nueva: Set<String>) {
        seleccion = nueva
        vueltaDeLoElegido = Postura(0.0, 0.0)
    }

    /** El centro de lo elegido: por dónde se agarra, se gira y se escala. */
    fun centroDeLaSeleccion(): Pt3? {
        val puntos = croquis.trazos.filter { it.id in seleccion }.flatMap { it.puntos } +
            croquis.imagenes.filter { it.id in seleccion }.flatMap { it.esquinas } +
            croquis.laminas.filter { it.id in seleccion }.mapNotNull { it.esfera?.centro }
        if (puntos.isEmpty()) return null
        return por(puntos.fold(Pt3(0.0, 0.0, 0.0)) { a, q -> mas(a, q) }, 1.0 / puntos.size)
    }

    /**
     * El plano por el que se maneja lo elegido: el de la hoja, por el centro de lo elegido.
     *
     * Manejándolo por el plano de la pantalla, lo dibujado se saldría de la hoja en cuanto
     * la vista estuviera girada — y que eso no pase es el trabajo de esta aplicación. Sin
     * hoja, el de la pantalla, que es lo único que queda.
     */
    private fun planoDeManejo(centro: Pt3): Plano3D {
        val normal = croquis.laminas.firstOrNull()?.let { normalDeTira(it.tiras().first()) }
            ?: camara.adelante
        return Plano3D(centro, normal)
    }

    /** Suelta lo elegido. Lo llama la pantalla al cambiar de herramienta. */
    fun soltarLaSeleccion() {
        elegir(emptySet())
        bolita = null
    }

    /**
     * **Esconde lo elegido**, y lo suelta.
     *
     * Suelto porque lo que no se ve no se puede manejar: dejarlo elegido deja el mando
     * encendido moviendo algo invisible, que es la mejor manera de perder un trazo sin
     * enterarse. Vuelve con [enseñarLoOculto], y también con deshacer.
     */
    fun ocultarLaSeleccion() {
        if (seleccion.isEmpty()) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.id in seleccion) it.copy(oculto = true) else it },
            imagenes = croquis.imagenes.map {
                if (it.id in seleccion) it.copy(oculto = true) else it
            }
        )
        elegir(emptySet())
    }

    /** Vuelve a enseñar todo lo escondido. */
    fun enseñarLoOculto() {
        if (!croquis.hayOcultos) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.oculto) it.copy(oculto = false) else it },
            imagenes = croquis.imagenes.map { if (it.oculto) it.copy(oculto = false) else it }
        )
    }

    fun borrarLaSeleccion() {
        if (seleccion.isEmpty()) return
        anotar()
        croquis = sinGruposVacios(
            croquis.copy(
                trazos = croquis.trazos.filterNot { it.id in seleccion },
                imagenes = croquis.imagenes.filterNot { it.id in seleccion }
            )
        )
        elegir(emptySet())
    }

    /**
     * Repinta lo elegido.
     *
     * Es lo que hace que la selección valga la pena y no sea solo un borrador con pasos de
     * más: se dibuja del tirón y luego se decide de qué color va cada cosa.
     */
    fun pintarLaSeleccion(nuevo: String) {
        if (seleccion.isEmpty()) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.id in seleccion) it.copy(color = nuevo) else it }
        )
    }

    /** Le cambia la punta a lo elegido, como el color o el grosor. */
    fun empincelarLaSeleccion(nuevo: Pincel) {
        if (seleccion.isEmpty()) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.id in seleccion) it.copy(pincel = nuevo) else it }
        )
    }

    // ---------------------------------------------------------------------
    // Las imágenes
    // ---------------------------------------------------------------------

    /**
     * **Pone una imagen en el espacio**, tumbada en la hoja y de cara al que mira.
     *
     * Nace donde se está mirando y del tamaño de media vista, que es lo que hace que
     * aparezca *ahí* y no haya que salir a buscarla. Tumbada en la hoja porque para eso se
     * trae una imagen a un croquis: para calcar encima. Y derecha respecto de la pantalla
     * —no del plano— porque uno la mira, no la lee de canto.
     *
     * Queda elegida al ponerla: lo primero que se hace con una imagen recién traída es
     * colocarla, y así el mando ya la tiene cogida.
     */
    fun ponerImagen(ruta: String, proporcion: Double) {
        val laHoja = croquis.laminas.firstOrNull()
        val normal = laHoja?.let { normalDeTira(it.tiras().first()) } ?: camara.adelante
        val plano = Plano3D(laHoja?.perfil?.first() ?: camara.centro, normal)
        val centro = plano.corte(camara.rayo(Pt(ancho / 2, alto / 2), ancho, alto))?.first
            ?: camara.centro

        // Los dos lados, derechos respecto de la pantalla: la derecha de la cámara aplastada
        // contra el plano, y su perpendicular dentro de él.
        var u = menos(camara.derecha, por(normal, escalar(camara.derecha, normal)))
        if (largo(u) < 1e-6) u = camara.derecha
        u = normalizado(u)
        var v = normalizado(producto(normal, u))
        // Y con el arriba de verdad arriba: según de qué lado se mire la hoja, esa
        // perpendicular sale mirando abajo y la imagen nacería del revés.
        if (camara.aPantalla(mas(centro, v), ancho, alto).y >
            camara.aPantalla(centro, ancho, alto).y
        ) {
            v = por(v, -1.0)
        }

        val medioAncho = ANCHO_DE_LA_IMAGEN / camara.zoom / 2
        val medioAlto = medioAncho * proporcion.coerceIn(0.05, 20.0)
        val aLosLados = por(u, medioAncho)
        val arriba = por(v, medioAlto)

        anotar()
        val imagen = Imagen3D(
            id = randomId(),
            ruta = ruta,
            esquinas = listOf(
                mas(menos(centro, aLosLados), arriba),
                mas(mas(centro, aLosLados), arriba),
                menos(mas(centro, aLosLados), arriba),
                menos(menos(centro, aLosLados), arriba)
            )
        )
        croquis = croquis.copy(imagenes = croquis.imagenes + imagen)
        elegir(setOf(imagen.id))
    }

    // ---------------------------------------------------------------------
    // Los grupos
    // ---------------------------------------------------------------------

    /**
     * **Junta lo elegido en un grupo.**
     *
     * A partir de ahí son una cosa: tocar uno los elige a todos, y se mueven, se esconden y
     * se borran juntos. Lo que ya estuviera en otro grupo se cambia a este —un trazo en dos
     * grupos a la vez no significa nada—, y el grupo que se quede vacío desaparece solo.
     */
    fun agruparLaSeleccion() {
        if (seleccion.size < 2) return
        anotar()
        val grupo = Grupo3D(randomId(), "${croquis.grupos.size + 1}")
        croquis = sinGruposVacios(
            croquis.copy(
                trazos = croquis.trazos.map {
                    if (it.id in seleccion) it.copy(grupo = grupo.id) else it
                },
                imagenes = croquis.imagenes.map {
                    if (it.id in seleccion) it.copy(grupo = grupo.id) else it
                },
                grupos = croquis.grupos + grupo
            )
        )
    }

    /** Deshace un grupo. Los trazos se quedan donde están, sueltos. */
    fun desagrupar(id: String) {
        if (croquis.grupos.none { it.id == id }) return
        anotar()
        if (capaActiva == id) capaActiva = null
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.grupo == id) it.copy(grupo = null) else it },
            imagenes = croquis.imagenes.map { if (it.grupo == id) it.copy(grupo = null) else it },
            grupos = croquis.grupos.filterNot { it.id == id }
        )
    }

    /** Elige un grupo entero, desde la lista. */
    fun elegirElGrupo(id: String) {
        elegir(
            (croquis.trazos.filter { it.grupo == id && !it.oculto }.map { it.id } +
                croquis.imagenes.filter { it.grupo == id && !it.oculto }.map { it.id }).toSet()
        )
    }

    /** Esconde o vuelve a enseñar un grupo entero. */
    fun ocultarElGrupo(id: String, ocultar: Boolean) {
        val suyos = idsDelGrupo(id)
        if (suyos.isEmpty() || grupoEscondido(id) == ocultar) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.grupo == id) it.copy(oculto = ocultar) else it },
            imagenes = croquis.imagenes.map {
                if (it.grupo == id) it.copy(oculto = ocultar) else it
            }
        )
        if (ocultar) elegir(seleccion - suyos)
    }

    /** Qué hay dentro de un grupo, para la lista. */
    fun idsDelGrupo(id: String): Set<String> =
        (croquis.trazos.filter { it.grupo == id }.map { it.id } +
            croquis.imagenes.filter { it.grupo == id }.map { it.id }).toSet()

    /** Si todo lo de un grupo está escondido. */
    fun grupoEscondido(id: String): Boolean {
        val trazos = croquis.trazos.filter { it.grupo == id }
        val imagenes = croquis.imagenes.filter { it.grupo == id }
        if (trazos.isEmpty() && imagenes.isEmpty()) return false
        return trazos.all { it.oculto } && imagenes.all { it.oculto }
    }

    /** Un grupo sin trazos no es un grupo: se va solo, sin que nadie lo borre. */
    private fun sinGruposVacios(c: Croquis): Croquis {
        val vivos = (c.trazos.mapNotNull { it.grupo } + c.imagenes.mapNotNull { it.grupo }).toSet()
        // **La capa en la que se está dibujando no se va aunque esté vacía**: es el sitio al
        // que va lo siguiente, y desaparecer justo antes de que llegue el primer trazo es la
        // peor forma de perder una capa recién hecha.
        val queSeQuedan = vivos + setOfNotNull(capaActiva)
        return if (c.grupos.all { it.id in queSeQuedan }) c
        else c.copy(grupos = c.grupos.filter { it.id in queSeQuedan })
    }

    /**
     * Empieza a retocar lo elegido con un mando continuo: **una sola anotación**.
     *
     * Lo mismo que [empezarAManejar], para las tiras que mandan un valor por fotograma. Sin
     * esto, arrastrar la transparencia deja cincuenta pasos en el historial y deshacer una
     * vez no deshace nada.
     */
    fun empezarARetocar() {
        if (seleccion.isEmpty()) return
        anotar()
    }

    /** Le cambia lo que tapa a lo elegido, como el color o el grosor. */
    fun transparentarLaSeleccion(nueva: Double) {
        if (seleccion.isEmpty()) return
        croquis = croquis.copy(
            trazos = croquis.trazos.map {
                if (it.id in seleccion) it.copy(opacidad = nueva.coerceIn(0.0, 1.0)) else it
            }
        )
    }

    /** Le cambia la punta a lo elegido: lo ancha, lo larga, el ladeo y el pulso. */
    fun empuntarLaSeleccion(nueva: PuntaDelPincel) {
        if (seleccion.isEmpty()) return
        croquis = croquis.copy(
            trazos = croquis.trazos.map { if (it.id in seleccion) it.copy(punta = nueva) else it }
        )
    }

    /**
     * **La llave de paso de las luces**: enciende, apaga y sube el conjunto.
     *
     * Como el sol y como esconder una capa, no entra en el historial: apagar las luces para
     * ver la pieza y volver a encenderlas no es dibujar, y metido en deshacer se come los
     * pasos de verdad —dos toques al interruptor y el trazo de antes ya no vuelve—.
     */
    fun ponerLasLuces(luces: Luces3D) {
        croquis = croquis.copy(luces = luces)
    }

    /**
     * **La apertura del objetivo**, de cerrada a abierta. Ver [Croquis.apertura].
     *
     * No entra en el historial: es cómo se está mirando el croquis, no algo que se le haya
     * hecho, igual que la llave de las luces.
     */
    fun abrirElDiafragma(cuanto: Double) {
        croquis = croquis.copy(apertura = cuanto.coerceIn(0.0, 1.0))
    }

    /**
     * **Reflejar lo que ya está dibujado al otro lado del espejo.**
     *
     * El espejo puesto refleja **lo que se traza a partir de ahí**, y eso es lo que uno quiere
     * mientras dibuja: se traza medio respaldo y aparece el otro medio. Pero llega el momento
     * en que la pieza ya está hecha y lo que hace falta es lo contrario — poner el espejo
     * ahora y que se duplique **lo de antes**—, y sin esto había que repasar el dibujo entero a
     * mano por el otro lado.
     *
     * Cada reflejo nace como un trazo más y no como una copia atada al original: se puede
     * borrar, mover o repintar por su cuenta, que es lo que uno acaba queriendo en cuanto la
     * pieza deja de ser simétrica del todo. Y va en el historial de una pieza: un toque lo
     * hace y deshacer lo quita entero.
     */
    fun reflejarLoDibujado(): Boolean {
        val espejo = croquis.espejo ?: return false
        val suyos = croquis.trazos.filterNot { it.oculto }
        if (suyos.isEmpty()) return false
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos + suyos.map {
                it.copy(
                    id = randomId(),
                    puntos = espejo.reflejo(it.puntos),
                    normal = it.normal?.let { n -> espejo.reflejoDeDireccion(n) },
                    normales = it.normales?.map { n -> espejo.reflejoDeDireccion(n) },
                    ejeDeLaPunta = it.ejeDeLaPunta?.let { e -> espejo.reflejoDeDireccion(e) }
                )
            }
        )
        return true
    }

    /** **El pixelado**, de nítido a un cuadro por punto. Ver [Croquis.pixelado]. */
    fun pixelar(cuanto: Double) {
        croquis = croquis.copy(pixelado = cuanto.coerceIn(0.0, 1.0))
    }

    /**
     * **Dónde se enfoca**: en lo que haya elegido, y si no hay nada, en lo que se está mirando.
     *
     * Las dos formas son la misma pregunta contestada con lo que hay a mano. Con algo elegido
     * es evidente —eso es lo que uno quiere limpio—; sin nada, el centro de la vista, que es
     * lo que uno está mirando por definición y lo que deja el enfoque donde apunta la cámara.
     */
    fun enfocarDondeSeMira() {
        croquis = croquis.copy(enfoque = centroDeLaSeleccion() ?: camara.centro)
    }

    /**
     * **La pauta de las hojas**: si llevan retícula y cuadros de medir.
     *
     * No entra en el historial, como el interruptor de las luces y por lo mismo: es cómo se
     * está mirando el croquis, no algo que se le haya hecho. Ver [Croquis.pautaDeLasHojas].
     */
    fun ponerLaPauta(puesta: Boolean) {
        croquis = croquis.copy(pautaDeLasHojas = puesta)
    }

    /**
     * Enciende o mueve la luna. A nulo, se apaga. Como el sol, no entra en el historial.
     */
    fun ponerLaLuna(luna: Sol3D?) {
        croquis = croquis.copy(luna = luna)
    }

    /**
     * **El brillo de la tinta de luz, en un solo mando: de apagada a a tope.**
     *
     * Un interruptor aparte y un mando de brillo al lado son dos cosas que dicen lo mismo,
     * y siempre acaban discrepando —encendido al cero, apagado al setenta—. Aquí el cero
     * **es** apagada: se baja del todo y la luz deja de alumbrar; se sube y alumbra lo que
     * se le pida. Si hay algo elegido, se le cambia también a eso, como el color.
     */
    /**
     * **El rodillo que se está pasando ahora mismo, hecho un trazo más.**
     *
     * Va aquí y no en el lienzo por una razón de fluidez. La escena y lo que está en la mano
     * se pintan en dos capas para que trazar no obligue a repintar el croquis entero (ver
     * [LoQueSePinta]), y el rodillo es la única cosa de la mano que **tiene** que ir con la
     * escena: se pinta unido a su montón, que es lo que hace que pasarlo por encima de otra
     * pasada no se vea más oscuro hasta soltar el dedo.
     *
     * Devolviendo la lista vacía en cuanto no hay un rodillo puesto, **la escena ni siquiera
     * llega a mirar el trazo en curso** y no se entera de que cambia — que es justo lo que
     * hace falta para que la separación sirva de algo. Ver [pintarLosRodillos].
     */
    fun elRodilloEnLaMano(): List<Trazo3D> {
        // El de luz no: alumbra, y lo que alumbra se suma en vez de fundirse con su montón.
        // Se pinta como cualquier otra tinta encendida, con lo que está en la mano.
        if (pincel.vigente != Pincel.RESALTADOR || punta.alumbra) return emptyList()
        val puntos = trazoEnCurso
        val normal = normalEnCurso
        if (puntos.size < 2 || normal == null) return emptyList()
        val enLaMano = Trazo3D(
            id = "subrayando-ahora",
            puntos = puntos,
            color = color,
            grosor = grosorDeVerdad,
            pincel = Pincel.RESALTADOR,
            calibre = grosorDeVerdad,
            opacidad = opacidad,
            normal = normal,
            normales = normalesEnCurso.takeIf { it.isNotEmpty() }
        )
        // Y su reflejo, si hay espejo puesto: si no, la mitad simétrica sería la que se
        // sumaría con lo de debajo en vez de unirse a su montón.
        val espejo = croquis.espejo?.takeIf { espejoPuesto }
            ?: return listOf(enLaMano)
        return listOf(
            enLaMano,
            enLaMano.copy(
                id = "subrayando-ahora-reflejo",
                puntos = espejo.reflejo(puntos),
                normal = espejo.reflejoDeDireccion(normal),
                normales = normalesEnCurso
                    .map { espejo.reflejoDeDireccion(it) }
                    .takeIf { it.isNotEmpty() }
            )
        )
    }

    fun alumbrar(cuanta: Double) {
        luz = cuanta.coerceIn(0.0, 1.0)
        if (seleccion.isNotEmpty()) alumbrarLaSeleccion(luz)
    }

    /**
     * **Une dos trazos con una hoja**: la superficie reglada que va del uno al otro.
     *
     * ## Qué hace esto que no hiciera nada
     *
     * Las hojas de barrer dan superficies prismáticas: valen para un muro, una mesa, un
     * suelo. Pero medio croquis no es eso —el faldón que va del alero a la esquina, la vela
     * entre dos cables, el capó entre dos perfiles, la rampa que sube torciéndose— y todas
     * esas son **dos curvas y la regla que las une**. Dibujar las dos aristas es fácil y es
     * lo que uno hace igualmente; lo de en medio no hay que dibujarlo, sale.
     *
     * ## Los dos en el mismo sentido
     *
     * Si uno va de izquierda a derecha y el otro de derecha a izquierda, la regla los cruza
     * y la hoja sale retorcida como una cinta de Möbius —correcto y completamente inútil—.
     * Así que si las puntas se cruzan, el segundo se lee al revés: nadie traza dos aristas
     * pensando en el sentido en que las traza.
     */
    fun unirLosTrazos(): Boolean {
        val elegidos = croquis.trazos.filter { it.id in seleccion }
        if (elegidos.size != 2) return false
        val uno = elegidos[0].puntos
        val otro = elegidos[1].puntos
        if (uno.size < 2 || otro.size < 2) return false

        val (perfil, otroPerfil) = emparejados(uno, otro)

        anotar()
        croquis = croquis.copy(
            laminas = listOf(
                Lamina3D(
                    id = randomId(),
                    perfil = perfil,
                    otroPerfil = otroPerfil,
                    direccion = camara.adelante,
                    fondo = 0.0,
                    color = COLOR_DE_LA_HOJA,
                    grosor = grosor
                )
            ),
            espejo = null
        )
        soltarLaSeleccion()
        return true
    }

    /**
     * **Dos rayas puestas a punto para ser los dos rieles de una hoja.**
     *
     * Hace las dos cosas que hacen falta y que nadie piensa al trazar:
     *
     * - **Las pone en el mismo sentido.** Si una va de izquierda a derecha y la otra al
     *   revés, la regla las cruza y la hoja sale retorcida como una cinta de Möbius:
     *   correcta y completamente inútil. Si las puntas se cruzan, la segunda se lee al revés.
     * - **Les da los mismos puntos.** La regla une el punto `i` con el punto `i`, así que una
     *   de veinte puntos y otra de doscientos no se pueden emparejar tal cual.
     */
    private fun emparejados(uno: List<Pt3>, otro: List<Pt3>): Pair<List<Pt3>, List<Pt3>> {
        val alReves = largo(menos(otro.first(), uno.first())) >
            largo(menos(otro.last(), uno.first()))
        val segundo = if (alReves) otro.reversed() else otro
        val cuantos = maxOf(uno.size, segundo.size).coerceAtMost(PUNTOS_DE_LA_REGLA)
        return remuestreado(uno, cuantos) to remuestreado(segundo, cuantos)
    }

    /** Si hay justo dos trazos elegidos: lo que hace falta para unirlos. */
    val sePuedenUnir: Boolean
        get() = croquis.trazos.count { it.id in seleccion } == 2

    /** Le sube o le baja la luz a lo elegido, como el color o lo que tapa. */
    fun alumbrarLaSeleccion(nueva: Double) {
        if (seleccion.isEmpty()) return
        croquis = croquis.copy(
            trazos = croquis.trazos.map {
                if (it.id in seleccion) it.copy(luz = nueva.coerceIn(0.0, 1.0)) else it
            }
        )
    }

    fun engrosarLaSeleccion(nuevo: Double) {
        if (seleccion.isEmpty()) return
        anotar()
        croquis = croquis.copy(
            trazos = croquis.trazos.map {
                // Y se vuelve a medir en el mundo, que es donde vive el trazo: el número
                // del selector es el mismo esté uno mirando de cerca o de lejos, así que dos
                // trazos con el mismo número miden lo mismo. Ver [Trazo3D.calibre].
                if (it.id in seleccion) it.copy(grosor = nuevo, calibre = nuevo)
                else it
            }
        )
    }

    /**
     * El borrador: **se lleva los trazos, y solo los trazos**.
     *
     * Por lo cerca que pasan en la pantalla, que es como uno los ve. Un trazo es una raya
     * sin grosor en el espacio: pedir que el rayo la corte sería pedir puntería imposible.
     *
     * **La hoja no se borra.** Se llevaba también la lámina cuando el dedo caía en un
     * hueco, y eso era un desastre silencioso: la hoja ocupa toda la pantalla y está debajo
     * de todo, así que cualquier pasada del borrador que no acertara una raya se llevaba la
     * mesa de dibujo entera —y con ella el apoyo de todo lo que viniera después—. Quitarla
     * es una decisión, no un descuido: se hace con la ✕ de la barra, con el plano puesto.
     */
    /**
     * **El borrador fino**: si se lleva solo el trozo que toca o la raya entera.
     *
     * Fino de fábrica, porque es lo que hace un borrador: se pasa por encima de una esquina
     * que sobra y desaparece esa esquina, no la línea de la que formaba parte. La otra forma
     * también hace falta —una raya que sobra entera se quita de un toque, sin repasarla— pero
     * es la excepción, y perder medio dibujo por rozarlo es peor que tener que repasar.
     *
     * Lo gordo que borra sale del **mismo mando del grosor** que la tinta: no hay un número
     * más que aprender, y el borrador se ve en la barra como se ve la punta. Ver
     * [radioDelBorrador].
     */
    var borradorFino by mutableStateOf(true)

    /**
     * **Qué cuerpo pone el compás**: bola, cilindro, cono o anillo. El gesto es el mismo
     * para los cuatro —se clava el centro y se abre—; esto dice qué sale. Ver
     * [Esfera3D.forma].
     */
    var formaDeBola by mutableStateOf(FormaDeBola.BOLA)

    /**
     * Lo gordo que borra, en píxeles de pantalla.
     *
     * Lo que mide el trazo que saldría con la punta puesta, que es lo que uno tiene delante
     * en la barra. Acotado por los dos lados: por debajo no se acierta a nada y por encima
     * deja de ser fino.
     */
    private val radioDelBorrador: Double
        get() = (grosorDeVerdad * camara.zoom / 2)
            .coerceIn(UMBRAL_DEL_BORRADOR, LO_MAS_GORDO_DEL_BORRADOR)

    /**
     * **Borra el trozo que toca y deja lo demás**, partiendo la raya si hace falta.
     *
     * Es lo que hace un borrador de verdad, y lo que no había: el de antes se llevaba la raya
     * **entera** en cuanto la rozabas, así que quitar la esquina que sobra de un croquis
     * costaba borrar la línea y volver a trazarla. Aquí lo que desaparece es lo que pasa por
     * debajo del borrador; lo que queda a los lados sigue siendo trazo, cada trozo por su
     * cuenta.
     *
     * ## Se parte en trozos, no se recorta
     *
     * Un trazo es una ristra de puntos con su presión y su perpendicular **en cada uno**, así
     * que cortarlo por el medio no es acortar una lista: es repartirla en varias, y a cada
     * trozo le tiene que ir su parte de todo lo demás. Los trozos de un solo punto se tiran
     * —un punto no es una raya— y el que se queda entero conserva su identidad, para que
     * deshacer y la selección no se despisten.
     */
    private fun borrarConPrecision(p: Pt) {
        val radio = radioDelBorrador
        var toco = false
        val quedan = ArrayList<Trazo3D>(croquis.trazos.size)
        for (t in croquis.trazos) {
            if (t.oculto || !cercaEnPantalla(t.puntos, p, radio)) {
                quedan += t
                continue
            }
            val trozos = loQueQuedaDe(t, p, radio)
            if (trozos.size == 1 && trozos[0].puntos.size == t.puntos.size) {
                quedan += t
                continue
            }
            toco = true
            quedan += trozos
        }
        if (!toco) return
        if (!borrando) {
            borrando = true
            anotar()
        }
        croquis = sinGruposVacios(croquis.copy(trazos = quedan))
    }

    /** Los trozos de un trazo que sobreviven a una pasada del borrador. */
    private fun loQueQuedaDe(t: Trazo3D, p: Pt, radio: Double): List<Trazo3D> {
        val trozos = ArrayList<Trazo3D>(2)
        var desde = -1
        fun cerrar(hasta: Int) {
            if (desde < 0 || hasta - desde < 2) {
                desde = -1
                return
            }
            trozos += t.copy(
                id = if (desde == 0 && hasta == t.puntos.size) t.id else randomId(),
                puntos = t.puntos.subList(desde, hasta).toList(),
                presiones = t.presiones?.let {
                    it.subList(desde.coerceAtMost(it.size), hasta.coerceAtMost(it.size)).toList()
                },
                normales = t.normales?.let {
                    it.subList(desde.coerceAtMost(it.size), hasta.coerceAtMost(it.size)).toList()
                }
            )
            desde = -1
        }
        for (i in t.puntos.indices) {
            val v = camara.aPantalla(t.puntos[i], ancho, alto)
            val fuera = hypot(v.x - p.x, v.y - p.y) <= radio
            if (fuera) cerrar(i) else if (desde < 0) desde = i
        }
        cerrar(t.puntos.size)
        return trozos
    }

    private fun borrarEn(p: Pt) {
        if (borradorFino) {
            borrarConPrecision(p)
            return
        }
        // **Se lleva todos los que toca, no el primero.**
        //
        // Iba de uno en uno, y borrar es un gesto de arrastre: pasando el borrador por veinte
        // rayas hacían falta veinte pasadas, cada una recorriendo el croquis entero. Con las
        // muestras del lápiz que llegan entre fotograma y fotograma eso se multiplicó por
        // cinco y la aplicación se quedaba clavada. En una sola pasada y con una sola
        // anotación en el historial: deshacer devuelve lo que se llevó el borrador de una
        // vez, que es lo que uno espera de un gesto.
        val fuera = croquis.trazos
            .filter { !it.oculto && cercaEnPantalla(it.puntos, p) }
            .mapTo(HashSet()) { it.id }
        if (fuera.isEmpty()) return
        // **Una anotación por pasada, no una por raya.** Una pasada del borrador es un gesto,
        // y deshacer un gesto tiene que devolverlo entero; además, con una por raya un solo
        // arrastre se comía media memoria de deshacer y los pasos de verdad se perdían.
        if (!borrando) {
            borrando = true
            anotar()
        }
        croquis = sinGruposVacios(
            croquis.copy(trazos = croquis.trazos.filterNot { it.id in fuera })
        )
    }

    /**
     * Si un trazo pasa cerca de un punto de la pantalla.
     *
     * **Con un descarte previo por la caja del trazo**, que es lo que la hace barata. Sin él,
     * cada pregunta proyectaba todos los puntos de todos los trazos: eso lo hacen el borrador
     * y la bolita de seleccionar **en cada muestra del dedo**, y con un croquis de mil rayas
     * era el trabajo de un fotograma entero por muestra. La caja se saca una vez por trazo y
     * se guarda ([cajaDelTrazo]), así que el noventa y nueve por ciento de las preguntas se
     * contestan con cuatro restas.
     *
     * Y en cuanto uno de sus tramos pasa cerca, se contesta que sí: no hace falta seguir
     * proyectando el resto del trazo.
     */
    private fun cercaEnPantalla(
        puntos: List<Pt3>,
        p: Pt,
        umbral: Double = UMBRAL_DEL_BORRADOR
    ): Boolean {
        if (puntos.isEmpty()) return false
        val caja = cajaDelTrazo(puntos)
        // El margen en el mundo: lo que valen en él los píxeles del umbral.
        val margen = umbral / camara.zoom
        val rayo = camara.rayo(p, ancho, alto)
        // Lo lejos que pasa la visual del centro de la caja. Más que su radio y el margen, no
        // hay forma de que toque.
        val d = menos(caja.centro, rayo.origen)
        val alLado = largo(menos(d, por(rayo.direccion, escalar(d, rayo.direccion))))
        if (alLado > caja.radio + margen) return false

        var anterior: Pt? = null
        for (q in puntos) {
            val v = camara.aPantalla(q, ancho, alto)
            if (hypot(v.x - p.x, v.y - p.y) <= umbral) return true
            anterior?.let { if (distanciaASegmento(p, it, v) <= umbral) return true }
            anterior = v
        }
        return false
    }

    /** El centro y el radio de un trazo en el mundo, para descartarlo de un vistazo. */
    private class CajaDelTrazo(val centro: Pt3, val radio: Double)

    /**
     * La caja de un trazo, sacada una vez y guardada.
     *
     * Se guarda **por quién es la lista de puntos y no por cómo es**: los puntos de un trazo
     * son el mismo objeto mientras el trazo exista —nunca se tocan, se sustituye el trazo
     * entero—, así que compararla por identidad cuesta nada y compararla por contenido
     * costaría justo lo que se quería ahorrar.
     */
    private val cajasGuardadas =
        object : LinkedHashMap<Identidad, CajaDelTrazo>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Identidad, CajaDelTrazo>
            ): Boolean = size > TOPE_DE_LAS_CAJAS
        }

    /** Una lista comparada por identidad, para poder usarla de llave sin recorrerla. */
    private class Identidad(val cual: Any) {
        override fun hashCode(): Int = System.identityHashCode(cual)
        override fun equals(other: Any?): Boolean = other is Identidad && other.cual === cual
    }

    private fun cajaDelTrazo(puntos: List<Pt3>): CajaDelTrazo =
        cajasGuardadas.getOrPut(Identidad(puntos)) {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var z0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var z1 = -Double.MAX_VALUE
            for (q in puntos) {
                if (q.x < x0) x0 = q.x
                if (q.y < y0) y0 = q.y
                if (q.z < z0) z0 = q.z
                if (q.x > x1) x1 = q.x
                if (q.y > y1) y1 = q.y
                if (q.z > z1) z1 = q.z
            }
            val centro = Pt3((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
            CajaDelTrazo(centro, largo(menos(Pt3(x1, y1, z1), centro)))
        }

    // ---------------------------------------------------------------------
    // La vista
    // ---------------------------------------------------------------------

    /**
     * Un gesto de navegación entero: **según cuántos dedos, una cosa distinta**.
     *
     * - **Uno gira.** Es lo que más se hace en un croquis en el espacio, así que se lleva
     *   el gesto más barato. Con el lápiz encima de la pantalla, un dedo sigue siendo un
     *   dedo: dibuja el lápiz y navega la mano, como en una mesa de dibujo.
     * - **Dos desplazan** y acercan a la vez, porque en la mano van juntos: nadie hace un
     *   pellizco puro ni un arrastre sin nada de pellizco, y separarlos obligaría a decidir
     *   cuál gana, que es lo que hace que una vista se sienta pegajosa.
     * - **Tres tocan la cámara misma**: de lado la ladean, y arriba y abajo abren y cierran
     *   la lente, de la ortográfica al ojo de pez. No mueven nada de sitio; cambian desde
     *   qué postura y con qué óptica se está mirando.
     */
    // ---------------------------------------------------------------------
    // Verlo puesto en el sitio
    // ---------------------------------------------------------------------

    /**
     * **El croquis, puesto en el mundo de verdad**: la cámara detrás y el dibujo delante.
     *
     * Un croquis en el espacio se hace casi siempre *de* algo que está ahí —la habitación
     * que se va a amueblar, la pieza que va a encajar en otra, el hueco donde va— y hasta
     * aquí solo se podía mirar contra un fondo liso, que no dice nada de si cabe. Puesto
     * encima de lo que se ve por la cámara, la pregunta se contesta sola: se levanta el
     * teléfono y ahí está, del tamaño que va a tener y en el sitio donde va.
     *
     * ## Se mira alrededor, no se orbita
     *
     * Es lo que hace que esto sea estar delante de algo y no verlo en una peana. La cámara
     * del croquis es de órbita —girarla la lleva alrededor de lo mirado, que es lo que hay
     * que hacer para dibujar—, y aquí eso está al revés: girándose, lo que hay delante
     * **se sale** de la vista. Ver [Camara3D.mirandoDesdeElMismoSitio].
     *
     * ## Lo que no hace
     *
     * **No sigue los pasos.** Lleva el giro del teléfono y nada más, así que mirar alrededor
     * funciona y andar hacia el croquis no lo acerca. Para eso hace falta que el aparato
     * mire el suelo y reconozca dónde está, que es otra cosa entera y otra biblioteca. Con
     * el giro solo ya se contesta lo que se pregunta —cómo queda ahí, cuánto ocupa— y
     * funciona en cualquier teléfono. El croquis se coloca con la mano: dos dedos lo
     * apartan y lo acercan.
     */
    var realidad by mutableStateOf(false)
        private set

    /** La cámara de antes de asomarse, para volver a dejarla como estaba al salir. */
    private var comoSeMiraba: Camara3D? = null

    /**
     * Se asoma al mundo, o se vuelve.
     *
     * Al asomarse se abre la lente: **sin punto de fuga esto no se sostiene**. La
     * ortográfica no tiene campo de visión, así que no hay forma de cuadrarla con la de la
     * cámara del teléfono, y lo dibujado se vería del mismo tamaño estuviera donde
     * estuviera — o sea, pegado al cristal. Ver [LENTE_DE_UN_TELEFONO].
     */
    fun verEnLaRealidad(si: Boolean) {
        if (si == realidad) return
        viaje = null
        if (si) {
            comoSeMiraba = camara
            // La lente del aparato si ya se conoce, la de un teléfono corriente si no; y
            // proyectando como una cámara, no como un ojo de pez. Ver [Camara3D.rectilinea].
            camara = camara.copy(lente = laLenteQueToca(), rectilinea = true)
            encajadoDelante()
            faltaColocarlo = true
        } else {
            comoSeMiraba?.let { camara = it }
            comoSeMiraba = null
        }
        realidad = si
    }

    /**
     * **El croquis entero delante, al encender el modo.**
     *
     * Se entraba con el aumento que hubiera —y el que hay suele ser el de estar dibujando
     * un detalle de cerca—, así que el ojo se plantaba **dentro** del croquis: se levantaba
     * el teléfono y lo que había era una pared de trazos o nada, sin forma de saber hacia
     * dónde estaba el resto. Es lo contrario de lo que el modo promete. Aquí se mira al
     * centro de lo dibujado con un aumento en el que quepa todo; la cámara de antes se
     * guarda y vuelve al salir, así que el detalle no se pierde.
     *
     * Solo el centro y el aumento: los ángulos los va a poner la primera postura del
     * teléfono, y el resto —lente, ladeo— ya está puesto.
     */
    private fun encajadoDelante() {
        val puntos = croquis.puntos()
        if (puntos.isEmpty()) return
        val centro = Pt3(
            puntos.sumOf { it.x } / puntos.size,
            puntos.sumOf { it.y } / puntos.size,
            puntos.sumOf { it.z } / puntos.size
        )
        val radio = puntos.maxOf { largo(menos(it, centro)) }.coerceAtLeast(1.0)
        val cabe = minOf(ancho, alto) / (radio * 2.4)
        camara = camara.copy(
            centro = centro,
            zoom = cabe.coerceIn(Camara3D.ZOOM_MINIMO, Camara3D.ZOOM_MAXIMO)
        )
    }

    /**
     * Lo que abarca de verdad la cámara del aparato **en vertical y en la pantalla**, en
     * radianes, cuando ya se ha podido medir. Ver [lenteDelAparato].
     */
    private var campoDelAparato: Double? = null

    private fun laLenteQueToca(): Double =
        campoDelAparato?.let { it / Camara3D.CAMPO_MAXIMO } ?: LENTE_DE_UN_TELEFONO

    /**
     * **La lente de verdad del aparato**, medida al encender su cámara.
     *
     * [LENTE_DE_UN_TELEFONO] es un número para un teléfono corriente, y ningún teléfono es
     * corriente: la cámara de atrás abre entre cincuenta y ochenta grados según el modelo,
     * y encima lo que llega a la pantalla es un recorte de eso. Con la lente equivocada el
     * croquis **no gira al mismo ritmo que la habitación** —se adelanta o se retrasa al
     * volver la cabeza—, que es la sensación de que flota en vez de estar puesto. Quien
     * enciende la cámara le pregunta al aparato lo que abarca y lo trae aquí. Ver
     * [LenteDelAparato].
     *
     * Se aplica sin mover el ojo: llega un instante después de encender el modo, y un
     * salto del croquis justo al aparecer se notaría. Ver [Camara3D.conLenteDesdeElMismoSitio].
     */
    fun lenteDelAparato(campoVertical: Double) {
        if (!campoVertical.isFinite()) return
        if (campoVertical < Math.toRadians(20.0) || campoVertical > Camara3D.CAMPO_MAXIMO) return
        campoDelAparato = campoVertical
        if (!realidad) return
        val lente = campoVertical / Camara3D.CAMPO_MAXIMO
        if (abs(lente - camara.lente) < 1e-6) return
        camara = camara.conLenteDesdeElMismoSitio(lente, alto)
    }

    /**
     * Todavía no se ha colocado el croquis delante de quien mira. Ver [mirarComoElTelefono].
     */
    private var faltaColocarlo = false

    /**
     * **Clavar el croquis delante de donde se está mirando ahora.**
     *
     * Asomado al mundo, el croquis se coloca solo la primera vez y a partir de ahí se queda
     * quieto en el sitio mientras uno se gira. Eso está bien hasta que **el sitio no es el
     * bueno**: se enciende el modo apuntando a la mesa y lo que uno quería era ponerlo en
     * mitad de la habitación, o se ha andado con los dedos hasta perderlo de vista. Sin una
     * forma de recolocarlo hay que salir del modo y volver a entrar mirando al sitio justo,
     * que es un baile absurdo.
     *
     * Esto lo vuelve a plantar delante: se apunta a donde se lo quiere y se clava ahí. De
     * ahí en adelante se queda, que es lo que hace que esté **puesto en la habitación** y no
     * pegado al cristal. Ver [mirarComoElTelefono] y [andar].
     */
    fun clavarloDelante() {
        if (!realidad) return
        faltaColocarlo = true
    }

    /**
     * El teléfono se ha movido: la vista mira a donde mira él. Ver [PosturaDelTelefono].
     *
     * **La primera postura coloca; las demás miran.** Son dos cosas distintas y hacen falta
     * las dos. Si la primera mirara alrededor como el resto, el croquis se quedaría en el
     * rumbo del mundo en el que se dibujó —al norte, pongamos— y quien encendiera el modo
     * mirando al sur no vería nada: una pantalla con la habitación y ningún dibujo, sin
     * ninguna pista de hacia dónde girarse. Así que la primera vez se gira **alrededor del
     * croquis**, que es lo que lo pone delante; de ahí en adelante el ojo se queda quieto y
     * lo que gira es la mirada, que es lo que lo deja clavado en el sitio.
     */
    fun mirarComoElTelefono(postura: PosturaDelTelefono) {
        if (!realidad) return
        camara = if (faltaColocarlo) {
            faltaColocarlo = false
            val colocada = camara.copy(
                giro = postura.giro,
                inclinacion = Camara3D.conInclinacion(postura.inclinacion),
                balanceo = postura.balanceo
            )
            // **Y con eso, la escala del mundo.** El croquis se acaba de plantar a la
            // distancia del ojo; se decide que eso son [METROS_AL_COLOCARLO], y a partir
            // de ahí un metro de acelerómetro o de paso son tantas unidades del croquis.
            colocada.ojo(alto)?.let { ojo ->
                val d = largo(menos(colocada.centro, ojo))
                if (d > 0.0 && d.isFinite()) unidadesPorMetro = d / METROS_AL_COLOCARLO
            }
            colocada
        } else {
            camara.mirandoDesdeElMismoSitio(
                postura.giro, postura.inclinacion, postura.balanceo, alto
            )
        }
    }

    /**
     * Cuántas unidades del croquis es un metro de la habitación, desde que se colocó. Cero
     * hasta que se coloca: antes no hay escala que valga.
     */
    private var unidadesPorMetro = 0.0

    /**
     * **El teléfono se ha desplazado** tantos metros (en el sistema del mundo: x al este, y
     * al norte, z arriba), según su acelerómetro. El ojo se va con él y el croquis se queda
     * donde estaba, que es lo que hace que acercarse acerque. Ver [InerciaDelTelefono].
     *
     * El sistema del mundo del sensor es el mismo que el del croquis —los dos con la z
     * arriba, los dos a derechas, el norte hacia dentro—, así que no hay nada que girar. Ver
     * [PosturaDelTelefono].
     */
    fun desplazarseComoElTelefono(metros: Pt3) {
        if (!realidad || unidadesPorMetro <= 0.0) return
        val d = por(metros, unidadesPorMetro)
        if (largo(d) < 1e-9 || !largo(d).isFinite()) return
        camara = camara.copy(centro = mas(camara.centro, d))
    }

    /**
     * **Un paso.** El podómetro del aparato ha contado uno: se avanza [LARGO_DE_PASO] por
     * donde se mira, en horizontal. Es la navegación a estima de los peatones —un paso, un
     * rumbo— y es lo que de verdad funciona sin cámara para andar hacia el croquis: el
     * acelerómetro integrado se pierde en dos zancadas, el contador de pasos no.
     *
     * Por donde se mira, y hacia delante siempre: el podómetro no sabe si se anda de
     * espaldas, y casi nadie lo hace con un teléfono delante.
     */
    fun darUnPaso() {
        if (!realidad || unidadesPorMetro <= 0.0) return
        val a = camara.adelante
        val horizontal = normalizado(Pt3(a.x, a.y, 0.0))
        if (largo(horizontal) < 1e-6) return
        camara = camara.copy(
            centro = mas(camara.centro, por(horizontal, LARGO_DE_PASO * unidadesPorMetro))
        )
    }

    fun navegar(dx: Double, dy: Double, zoom: Double, enX: Double, enY: Double, gesto: Gesto) {
        // La mano manda sobre el viaje: tocar la vista mientras está encajándose lo corta
        // ahí mismo. Una vista que sigue yéndose sola mientras uno la gira se siente rota.
        viaje = null
        when (gesto) {
            // **Asomado al mundo, la vista la gira el teléfono y no el dedo.** Dos mandos
            // para lo mismo se pelean: el dedo la giraba, el sensor la devolvía al fotograma
            // siguiente, y lo que se veía era la escena temblando. Mover y acercar sí siguen
            // siendo del dedo, que es como se coloca el croquis en el sitio.
            Gesto.GIRAR -> if (!realidad) {
                camara = camara.girada(
                    dx / VUELTA_ENTERA * Math.PI * 2,
                    dy / VUELTA_ENTERA * Math.PI * 2
                )
            }

            Gesto.DESPLAZAR -> camara = camara.desplazada(dx, dy)

            Gesto.LENTE -> {
                // Asomado al mundo, la lente es la del aparato: cambiarla descuadra lo
                // dibujado de lo que se ve detrás, que es lo único que hace que encaje.
                if (realidad) return
                // Subir abre la lente: se tira del campo hacia arriba, como quien abre una
                // persiana. Y el aumento se deja quieto — con tres dedos apoyados es
                // imposible no pellizcar un poco, y la lente se iría llena de tirones.
                camara = camara
                    .balanceada(dx / VUELTA_ENTERA * Math.PI * 2)
                    .conLente(-dy / RECORRIDO_DE_LA_LENTE)
                return
            }
        }
        // **Asomado al mundo, acercarse es andar hacia el croquis, no ampliarlo.**
        //
        // El aumento es la lente, y la lente aquí es la del aparato: subirlo hace el dibujo
        // más grande sin que la habitación de detrás crezca con él, así que lo dibujado se
        // despega de lo que se ve y deja de estar puesto en ningún sitio. Lo que uno pide al
        // pellizcar mirando por la cámara es **acercarse**, que es otra cosa: mover el ojo
        // hacia delante por donde mira. Ver [andar].
        if (realidad) {
            andar(zoom)
            return
        }
        acercar(zoom, Pt(enX, enY))
    }

    /**
     * **Andar hacia donde se mira**, asomado al mundo.
     *
     * ## Lo que esto es y lo que no
     *
     * El teléfono sabe **hacia dónde** está mirando —eso lo dice la brújula y el sensor de
     * postura, y por eso girarse funciona— pero no sabe **dónde está**: para saberlo haría
     * falta seguir la habitación por la cámara, que es lo que hace ARCore y no un sensor.
     * Sacarlo del acelerómetro es imposible en la práctica: hay que integrar dos veces, y el
     * error se acumula en metros en cuestión de segundos. Así que dar un paso adelante de
     * verdad no mueve el croquis, y sin esto no había forma de acercarse a mirar un detalle:
     * el dibujo se venía con uno.
     *
     * Mientras eso no esté, se anda **con los dedos**: pellizcar hacia fuera adelanta y hacia
     * dentro retrocede, por donde se esté mirando. No es lo mismo que caminar, pero resuelve
     * lo que hacía falta —ponerse delante de un punto y verlo de cerca— y se combina con el
     * giro del aparato, que ese sí es de verdad.
     *
     * ## Cuánto se anda
     *
     * Lo que haría falta andar para que lo que se está mirando creciera ese tanto: acercarse
     * a la mitad de la distancia lo hace el doble de grande. Así el pellizco responde igual
     * de lejos que de cerca, que es lo que espera la mano.
     */
    private fun andar(factor: Double) {
        if (abs(factor - 1.0) < 1e-4) return
        val ojo = camara.ojo(alto) ?: return
        val cuanto = largo(menos(camara.centro, ojo)) * (1.0 - 1.0 / factor)
        if (!cuanto.isFinite()) return
        camara = camara.copy(centro = mas(camara.centro, por(camara.adelante, cuanto)))
    }

    /**
     * Acerca **hacia donde están los dedos**, no hacia el centro de la pantalla.
     *
     * Es la diferencia entre navegar y perseguir el dibujo: acercándose al centro, para
     * mirar de cerca una esquina hay que ampliar y después desplazar hasta encontrarla
     * otra vez. Se guarda el punto del mundo que había bajo los dedos, se cambia el
     * aumento y se mueve el centro lo justo para que ese punto vuelva a caer donde estaba.
     */
    private fun acercar(factor: Double, en: Pt) {
        if (zoomBloqueado) return
        if (abs(factor - 1.0) < 1e-4) return
        val plano = Plano3D(camara.centro, camara.adelante)
        val antes = plano.corte(camara.rayo(en, ancho, alto))?.first
        camara = camara.conZoom(factor)
        if (antes == null) return
        val despues = Plano3D(camara.centro, camara.adelante)
            .corte(camara.rayo(en, ancho, alto))?.first ?: return
        camara = camara.copy(centro = mas(camara.centro, menos(antes, despues)))
    }

    fun orbitar(dGiro: Double, dInclinacion: Double, zoom: Double) {
        viaje = null
        camara = camara.girada(dGiro, dInclinacion).conZoom(if (zoomBloqueado) 1.0 else zoom)
    }

    fun desplazar(dx: Double, dy: Double) {
        camara = camara.desplazada(dx, dy)
    }

    /** Vuelve a mirar todo lo dibujado, a un aumento en el que quepa. */
    fun encuadrar() {
        val puntos = croquis.puntos()
        if (puntos.isEmpty()) {
            camara = Camara3D(zoom = camara.zoom)
            return
        }
        val centro = Pt3(
            puntos.sumOf { it.x } / puntos.size,
            puntos.sumOf { it.y } / puntos.size,
            puntos.sumOf { it.z } / puntos.size
        )
        val radio = puntos.maxOf { largo(menos(it, centro)) }.coerceAtLeast(1.0)
        val cabe = minOf(ancho, alto) / (radio * 2.4)
        camara = camara.copy(centro = centro, zoom = cabe.coerceIn(Camara3D.ZOOM_MINIMO, Camara3D.ZOOM_MAXIMO))
    }

    // ---------------------------------------------------------------------
    // Las tintas guardadas
    // ---------------------------------------------------------------------

    /**
     * **Guarda la tinta que hay puesta**: la punta, el color, el grueso y lo que tapa.
     *
     * Las cuatro cosas juntas, porque una tinta es eso: guardando solo la forma habría que
     * reconstruir de memoria las otras tres cada vez, que es lo que hace que nadie use una
     * galería de pinceles.
     */
    fun guardarLaPunta() {
        croquis = croquis.copy(
            puntas = croquis.puntas +
                PuntaGuardada(randomId(), punta, color, grosor, opacidad, pincel)
        )
    }

    /** Todas las tintas de la galería: **las de fábrica primero** y luego las de uno. */
    val lasTintas: List<PuntaGuardada> get() = LAS_DE_FABRICA + croquis.puntas

    /**
     * Añade un rasgo a la tinta que se está dibujando.
     *
     * Se acumulan: una tinta puede tener varios rasgos y **cada uno con su color**, que es
     * lo que hace que dos tintas se distingan de verdad y no salgan las dos negras.
     */
    fun dibujarEnLaPunta(rasgo: List<Pt>, color: String) {
        // Un toque suelto es un punto, y un punto es una marca como otra cualquiera: quien
        // lo pone en el taller lo pone a propósito. Antes se tiraba por «corto».
        if (rasgo.isEmpty()) return
        punta = punta.copy(
            dibujo = punta.dibujo.orEmpty() + TrazoDeLaPunta(rasgo, color),
            // La marca manda sobre la sección: teniendo las dos puestas no se sabría cuál
            // se está usando, y la que se acaba de dibujar es la que se quiere.
            perfil = null
        )
    }

    /**
     * **Quita el último rasgo de la punta, y solo el último.**
     *
     * Una tinta se traza a rasgos —uno, y luego otro al lado, y luego uno de otro color— y
     * el tercero sale torcido. Con solo el borrador de vaciarlo todo, arreglar ese tercero
     * costaba volver a dibujar los tres, así que en la práctica nadie hacía tintas de más de
     * un rasgo: se conformaba con el primero que saliera bien. Es lo mismo que deshacer, y
     * por eso no hace falta explicarlo.
     */
    fun deshacerEnLaPunta() {
        val marca = punta.dibujo ?: return
        punta = punta.copy(dibujo = marca.dropLast(1).takeIf { it.isNotEmpty() })
    }

    /**
     * **Rellena la punta dibujada, o la deja a rasgos.**
     *
     * Lo que se traza en el taller se guarda **tal cual**, sin cerrar ni rellenar: una marca,
     * y el trazo sale de estamparla a lo largo del recorrido. Está bien y es lo que hace que
     * una tinta pueda ser dos rayas cruzadas o un rasgo con un pelo al lado — cerrarlo todo
     * convertía cualquier rasgo en una mancha.
     *
     * Lo que faltaba es lo contrario: **dibujar una figura y que salga maciza**. Sin esto, a
     * quien traza un círculo o una gota le sale su contorno hueco y no hay forma de
     * rellenarlo, que era el «las líneas salen huecas y no se pueden rellenar». Es la misma
     * figura leída de otra manera: como **la sección de la punta**, que sí se barre y se
     * rellena. Ver [PuntaDelPincel.perfil] y [PuntaDelPincel.dibujo].
     *
     * Se queda con el rasgo más largo: si hay varios, el que tiene más puntos es la figura y
     * los otros son adornos, y una sección es una sola figura.
     */
    fun rellenarLaPunta(maciza: Boolean) {
        if (maciza) {
            val rasgo = punta.dibujo?.maxByOrNull { it.puntos.size }?.puntos ?: return
            if (rasgo.size < 3) return
            punta = punta.copy(perfil = rasgo, dibujo = null)
        } else {
            val perfil = punta.perfil ?: return
            punta = punta.copy(perfil = null, dibujo = listOf(TrazoDeLaPunta(perfil, color)))
        }
    }

    /** Si hay algo dibujado en la punta, relleno o a rasgos. */
    val hayPuntaDibujada: Boolean get() = punta.tienePerfil || punta.tieneDibujo

    /** Borra la marca dibujada y deja la punta de su tinta. */
    fun borrarLaMarca() {
        punta = punta.copy(dibujo = null, perfil = null)
    }

    /**
     * Se apunta un color como usado: **la paleta se hace sola**.
     *
     * El último, primero, y sin repetidos: lo que uno acaba de usar es lo que va a volver a
     * usar. No entra en el historial —elegir un color no es dibujar— y se queda con unos
     * pocos: una lista larga de colores es tan inútil como no tener ninguno.
     */
    fun recordarElColor(nuevo: String) {
        val limpio = nuevo.lowercase()
        val ya = croquis.favoritos.map { it.lowercase() }
        if (ya.firstOrNull() == limpio) return
        croquis = croquis.copy(
            favoritos = (listOf(limpio) + croquis.favoritos.filterNot { it.lowercase() == limpio })
                .take(CUANTOS_FAVORITOS)
        )
    }

    /** Vuelve a una tinta guardada, entera. */
    fun usarLaPunta(id: String) {
        val guardada = lasTintas.firstOrNull { it.id == id } ?: return
        punta = guardada.punta
        pincel = guardada.pincel
        grosor = guardada.grosor
        opacidad = guardada.opacidad
        // El color de las de fábrica no se impone: son formas de punta, y el color con el
        // que uno está dibujando es suyo. Las guardadas por uno sí lo traen: se guardaron
        // con él a propósito.
        if (!guardada.deFabrica) color = guardada.color
    }

    /** Las de fábrica no se tiran: son el sitio al que volver. */
    fun tirarLaPunta(id: String) {
        if (LAS_DE_FABRICA.any { it.id == id }) return
        croquis = croquis.copy(puntas = croquis.puntas.filterNot { it.id == id })
    }

    // ---------------------------------------------------------------------
    // El sol
    // ---------------------------------------------------------------------

    /**
     * Enciende el sol o lo mueve. A nulo, se apaga.
     *
     * No entra en el historial: mover el sol es mirar, no dibujar, y llenar el deshacer de
     * pasos del sol dejaría el deshacer sin servir para lo que sirve.
     */
    fun ponerElSol(sol: Sol3D?) {
        croquis = croquis.copy(sol = sol)
    }

    /** Quita el eje del espejo. Lo que se dibujó por duplicado se queda como está. */
    fun quitarElEspejo() {
        if (croquis.espejo == null) return
        anotar()
        croquis = croquis.copy(espejo = null)
    }

    /**
     * Cuánto se ve la hoja: de invisible a bien marcada.
     *
     * No entra en el historial: es mirar, no dibujar, y llenarlo de pasos de esto dejaría el
     * deshacer sin servir para lo que sirve.
     */
    fun transparentarLaHoja(cuanto: Double) {
        val hoja = croquis.laminas.firstOrNull() ?: return
        croquis = croquis.copy(
            laminas = listOf(hoja.copy(opacidad = cuanto.coerceIn(0.0, MAXIMO_DE_LA_HOJA)))
        )
    }

    /** Quita la hoja, **y con ella su eje de simetría**. Lo dibujado se queda: ya vive en el espacio. */
    fun quitarLaHoja() {
        if (croquis.laminas.isEmpty()) return
        anotar()
        croquis = croquis.copy(laminas = emptyList(), espejo = null)
    }

    // ---------------------------------------------------------------------
    // El fondo
    // ---------------------------------------------------------------------

    /**
     * Empieza a pintar el fondo: **una sola anotación para todo el arrastre**.
     *
     * Lo mismo que [empezarAManejar] y por lo mismo: el color se cambia arrastrando por una
     * tira y eso manda decenas de colores por segundo. Anotando cada uno, deshacer tendría
     * que tocarse cincuenta veces para volver al fondo anterior.
     */
    fun empezarAPintarElFondo() = anotar()

    /**
     * De qué color es el espacio. Nulo devuelve el del tema.
     *
     * Va dentro del croquis, así que se guarda con él y **se deshace como todo lo demás**:
     * un fondo elegido por error se quita con el mismo botón que un trazo de más.
     */
    fun pintarElFondo(color: String?) {
        if (croquis.colorDelFondo == color) return
        croquis = croquis.copy(colorDelFondo = color)
    }

    /** Vuelve a la lente de siempre, la ortográfica, y endereza la cámara. */
    fun quitarLaLente() {
        camara = camara.copy(lente = 0.0, balanceo = 0.0)
    }

    /**
     * **El doble toque: encaja en la vista recta que se tenga más a mano.**
     *
     * Girando en libertad uno acaba casi siempre a un pelo de una vista recta —de frente,
     * de perfil, en planta— sin llegar a ella, y ese pelo es justo lo que estropea un
     * croquis: las paralelas dejan de ser paralelas y lo que se mide no vale. Buscarla a
     * pulso con el dedo es imposible y el cubo pide apuntar a un cuadradito.
     *
     * Dos toques y ya, en cualquier parte de la pantalla. No hay que elegir cuál: la que se
     * quiere es **la que ya se está mirando casi**, y eso lo sabe la propia cámara.
     */
    fun encajarEnLaVistaMasCercana() {
        pedirVista(CaraDelCubo.laMasCercanaA(camara.adelante).postura)
    }

    /**
     * Manda la vista a una de las seis de dibujo técnico, **girando hasta ella**.
     *
     * El destino se corrige al ángulo equivalente más cercano: sin eso, ir de un giro de
     * 350° a uno de 0° da la vuelta entera por el lado largo, y lo que se ve es la escena
     * dando un volantazo para acabar donde estaba. Ver [ViajeDeLaVista].
     */
    fun pedirVista(destino: Postura) {
        viajarA(
            camara.copy(
                giro = porElCaminoCorto(camara.giro, destino.giro),
                inclinacion = Camara3D.conInclinacion(destino.inclinacion),
                balanceo = 0.0
            )
        )
    }

    // ---------------------------------------------------------------------
    // Las vistas congeladas
    // ---------------------------------------------------------------------

    /**
     * **Congela desde dónde se está mirando ahora.**
     *
     * Se guarda la cámara entera —los dos ángulos, el aumento, a qué punto se mira, el
     * ladeo y la lente—, que es lo único que hace que volver sea volver de verdad. Ver
     * [Vista3D].
     */
    fun congelarLaVista(recorte: Recorte? = null) {
        anotar()
        croquis = croquis.copy(
            vistas = croquis.vistas + Vista3D(
                randomId(),
                "${croquis.vistas.size + 1}",
                camara,
                recorte?.takeIf { it.sirve }
            )
        )
    }

    /**
     * Si se está marcando **la zona** que se va a congelar.
     *
     * Encuadrar es la mitad de congelar una vista: lo que uno quiere llevarse a la lámina
     * casi nunca es la pantalla entera, es el detalle del nudo o la pieza sin el medio metro
     * de aire de al lado. Mientras está puesto, el dedo no dibuja ni gira: marca el
     * rectángulo, y al soltarlo se congela con él.
     */
    var recortando by mutableStateOf(false)

    /** El rectángulo que se está marcando ahora mismo, para pintarlo. */
    var elRecorte by mutableStateOf<Recorte?>(null)
        private set

    fun marcandoLaZona(recorte: Recorte?) {
        elRecorte = recorte
    }

    fun dejarDeRecortar() {
        recortando = false
        elRecorte = null
    }

    /**
     * Se pone en una vista guardada **de golpe**, sin viaje.
     *
     * Es lo que hace falta al abrir el croquis desde una de sus láminas: no se está
     * volviendo de ningún sitio, se está llegando, y ver la cámara viajar desde una postura
     * que nadie ha visto es un movimiento que no significa nada.
     */
    /**
     * Se abre el croquis **puesto en esa vista**, viniendo de una de sus láminas.
     *
     * **Y en la zona que se marcó, no solo desde donde se miraba.** Guardaba la cámara y
     * nada más, así que tocar una lámina de un rincón —una zona marcada a mano y congelada—
     * devolvía el croquis entero visto de lejos: la lámina enseñaba un detalle y el croquis
     * se abría en otro sitio, y había que volver a buscar a mano lo que se acababa de
     * tocar. Encuadrar la zona es lo que hace que la lámina y el croquis enseñen lo mismo, y
     * es la misma cuenta con la que se proyectó la lámina. Ver [camaraEncuadrada].
     */
    fun ponerLaVista(id: String) {
        val vista = croquis.vistas.firstOrNull { it.id == id } ?: return
        viaje = null
        // Sin la pantalla medida no se puede encuadrar una zona: se apunta para el primer
        // fotograma, y mientras tanto se pone la cámara tal cual para que no se vea el hueco.
        if (vista.recorte?.sirve == true && ancho <= 1.0) {
            laVistaQueEspera = id
            camara = vista.camara
            return
        }
        camara = camaraEncuadrada(vista, ancho, alto)
    }

    /** Vuelve a una vista guardada, **girando hasta ella** como a una cara del cubo. */
    fun irALaVista(id: String) {
        val vista = croquis.vistas.firstOrNull { it.id == id } ?: return
        viajarA(vista.camara.copy(giro = porElCaminoCorto(camara.giro, vista.camara.giro)))
    }

    fun borrarLaVista(id: String) {
        if (croquis.vistas.none { it.id == id }) return
        anotar()
        croquis = croquis.copy(vistas = croquis.vistas.filterNot { it.id == id })
    }

    /** Empieza el viaje de la cámara de ahora a la que se pida. Ver [ViajeDeLaVista]. */
    private fun viajarA(destino: Camara3D) {
        viaje = ViajeDeLaVista(camara, destino)
    }

    /**
     * Anda el viaje: cero es de donde salió y uno es el destino.
     *
     * Y **se endereza por el camino**: una vista técnica ladeada no es una vista técnica, y
     * haber ladeado la cámara antes con tres dedos no debería estropear la planta. El
     * balanceo se va a cero al mismo ritmo que giran los otros dos ángulos, así que la
     * pantalla se va poniendo derecha mientras la vista se encaja, en un solo movimiento.
     */
    fun andarElViaje(cuanto: Float) {
        val v = viaje ?: return
        val t = cuanto.toDouble().coerceIn(0.0, 1.0)
        fun entre(a: Double, b: Double) = a + (b - a) * t
        camara = camara.copy(
            giro = entre(v.desde.giro, v.hasta.giro),
            inclinacion = Camara3D.conInclinacion(entre(v.desde.inclinacion, v.hasta.inclinacion)),
            balanceo = entre(v.desde.balanceo, v.hasta.balanceo),
            lente = entre(v.desde.lente, v.hasta.lente),
            centro = Pt3(
                entre(v.desde.centro.x, v.hasta.centro.x),
                entre(v.desde.centro.y, v.hasta.centro.y),
                entre(v.desde.centro.z, v.hasta.centro.z)
            ),
            // **El aumento se interpola multiplicando, no sumando.** Ir de un aumento al
            // doble y de ahí al cuádruple tiene que sentirse igual de rápido en los dos
            // tramos, y sumando no lo es: la primera mitad del viaje se comería casi todo el
            // acercamiento y la segunda parecería que se ha parado.
            zoom = v.desde.zoom * Math.pow(v.hasta.zoom / v.desde.zoom, t)
        )
        if (t >= 1.0) viaje = null
    }

    // ---------------------------------------------------------------------
    // Historial
    // ---------------------------------------------------------------------

    private fun anotar() {
        atras.addLast(croquis)
        if (atras.size > TOPE_DEL_HISTORIAL) atras.removeFirst()
        adelante.clear()
    }

    fun deshacer() {
        val previo = atras.removeLastOrNull() ?: return
        adelante.addLast(croquis)
        croquis = previo
    }

    fun rehacer() {
        val siguiente = adelante.removeLastOrNull() ?: return
        atras.addLast(croquis)
        croquis = siguiente
    }

    fun vaciar() {
        if (croquis.vacio) return
        anotar()
        // El fondo se queda: vaciar es tirar el dibujo, no cambiar de papel.
        croquis = Croquis(colorDelFondo = croquis.colorDelFondo)
        elegir(emptySet())
        bolita = null
    }

    fun cargar(c: Croquis) {
        // **Se queda el último plano y ya.** Un croquis guardado antes de que hubiera un
        // solo plano de trabajo puede traer varios, y con varios cargados el lápiz
        // dibujaría en uno cualquiera de ellos sin que nadie lo haya elegido. Lo dibujado
        // no se toca: los trazos ya están en el espacio y siguen donde estaban.
        croquis = c.copy(
            laminas = c.laminas.takeLast(1),
            // Las puntas retiradas se convierten al abrir: así el croquis viejo se ve como
            // se va a ver a partir de ahora, y no hay dos formas de pintar lo mismo.
            trazos = c.trazos.map { if (it.pincel.vigente != it.pincel) it.copy(pincel = it.pincel.vigente) else it }
        )
        elegir(emptySet())
        atras.clear()
        adelante.clear()
    }

    companion object {
        /** Los colores del croquis: los de un juego de rotuladores, no una paleta entera. */
        val COLORES = listOf(
            "#1e1e1e", "#f1f3f5", "#e03131", "#1971c2", "#2f9e44", "#f08c00", "#9c36b5"
        )

        /**
         * La tinta de fábrica **según el tema**: casi negra de día y casi blanca de noche.
         *
         * Una tinta casi negra sobre un fondo oscuro no es una tinta: es un trazo que no
         * está. Abriendo la aplicación de noche, el croquis parecía no responder.
         */
        fun tintaDeFabrica(deNoche: Boolean): String = if (deNoche) "#f1f3f5" else "#1e1e1e"

        /**
         * Los cuatro fondos de un toque, y **el negro de verdad**.
         *
         * El último no es un gris muy oscuro más: es el cero de una pantalla OLED, donde el
         * píxel **se apaga** en vez de encenderse a poca luz. Y eso cambia dos cosas de una
         * vez. Un croquis sobre pizarra ya se veía bien, pero sobre negro puro el dibujo
         * flota en la nada —no hay un rectángulo gris de fondo, no hay canto de pantalla— y
         * las tintas de luz, que van sumando, tienen por fin de dónde salir: sobre un gris
         * oscuro, un tubo encendido compite con la luz que ya emite el fondo entero.
         *
         * Y de paso, en un panel de esos, un croquis sobre negro **gasta bastante menos
         * batería** que el mismo croquis sobre gris, que en algo que se usa con el brazo en
         * alto se agradece.
         *
         * Va el último y no el primero: no es el que se pide a diario. Ver [FONDO_NEGRO].
         */
        val FONDOS = listOf("#ffffff", "#f4efe4", "#c9ccd1", "#1b1f24", FONDO_NEGRO)

        /** El negro de verdad: el píxel apagado de una pantalla OLED. */
        const val FONDO_NEGRO = "#000000"

        /**
         * Los grosores del trazo, **en dp**.
         *
         * Cinco y no tres, y llegando hasta catorce: las puntas —el tubo, el listón y la
         * plumilla— se distinguen por su relieve, y el relieve necesita ancho donde caber.
         * En píxeles crudos el tope eran ocho píxeles, tres dp escasos, y a ese ancho las
         * tres puntas se veían iguales: eran tres puntas de adorno. Y el de en medio,
         * seis, es el que viene puesto: es donde un trazo ya tiene cuerpo sin comerse el
         * croquis.
         */
        val GROSORES = listOf(2.0, 4.0, 6.0, 9.0, 14.0)

        /**
         * Lo ancho que es el resaltador, en dp. **Fijo, y ancho de verdad.**
         *
         * Cincuenta y dos: esto no es un rotulador de subrayar renglones, es **un rodillo**.
         * Da una pasada ancha de color con una sola mano, que es para lo que sirve en un
         * croquis —teñir una cara entera, marcar una zona, separar un volumen de otro—; a
         * veintidós seguía siendo una raya gorda, y una raya gorda ya la hace la punta
         * redonda. Lo que no tiene es cuerpo: es ancho y translúcido, no grueso.
         */
        /**
         * Cuánto multiplica el resaltador al número del selector.
         *
         * Seis: con el selector abajo del todo sale del ancho de un rotulador de subrayar
         * corriente, y arriba del todo da para pasarle una mano de color a una cara entera
         * —que es para lo que se pide un rodillo en un croquis del espacio y para lo que se
         * quedaba corto siempre—. Ver [grosorDeVerdad].
         */
        const val ENGORDE_DEL_RESALTADOR = 6.0

        /**
         * Lo ancha que nace una imagen, en píxeles de pantalla.
         *
         * Media pantalla larga: lo bastante para verla y para empezar a calcar encima, y no
         * tanto como para tapar el croquis que ya hay. Colocarla es lo siguiente que se
         * hace, y para eso está el mando.
         */
        private const val ANCHO_DE_LA_IMAGEN = 700.0

        /**
     * **Las cuatro tintas de siempre, ya en la galería.**
     *
     * Sin forma dibujada: son las puntas de verdad —la redonda, el listón, el rodillo y la
     * de luz—, cada una con su grueso típico, y están para reconocerlas de un vistazo y
     * para poder volver a ellas.
     */
    val LAS_DE_FABRICA = listOf(
        PuntaGuardada(
            "fabrica-redondo", PuntaDelPincel(), "#1e1e1e", 3.0, 1.0,
            Pincel.REDONDO, deFabrica = true
        ),
        PuntaGuardada(
            "fabrica-cuadrado", PuntaDelPincel(), "#1e1e1e", 6.0, 1.0,
            Pincel.CUADRADO, deFabrica = true
        ),
        PuntaGuardada(
            "fabrica-resaltador", PuntaDelPincel(), "#ffd43b", 12.0, 0.45,
            Pincel.RESALTADOR, deFabrica = true
        ),
        // La luz ya no es una punta, es un material: una redonda **hecha de luz**. Guardada
        // así, quien la use se lleva la sección redonda y el material encendido, que es lo
        // que había antes con `Pincel.LUZ` — y además puede cambiarle la sección sin
        // apagarla. El identificador no cambia para que las guardadas de antes casen.
        PuntaGuardada(
            "fabrica-luz", PuntaDelPincel(alumbra = true), "#4dabf7", 5.0, 1.0,
            Pincel.REDONDO, deFabrica = true
        ),
        // Y una tramada, que si no el material nuevo no lo encuentra nadie: la de rayar una
        // sección cortada, que es para lo que más se pide.
        PuntaGuardada(
            "fabrica-rayado", PuntaDelPincel(trama = Trama.RAYADO), "#1e1e1e", 10.0, 1.0,
            Pincel.REDONDO, deFabrica = true
        )
    )

        /**
         * Cuánto se le deja moverse al dedo para que siga siendo un toque, en píxeles.
         *
         * Lo que cabe en la yema: por debajo de eso nadie ha querido dibujar nada.
         */
        private const val LO_QUE_ES_UN_TOQUE = 14.0

        /** Con cuántas reglas se une un trazo con otro. */
        private const val PUNTOS_DE_LA_REGLA = 64

        /** Cuántos colores se recuerdan. Una lista larga es tan inútil como ninguna. */
        private const val CUANTOS_FAVORITOS = 8

        /** De qué color nace una hoja: blanca, como un papel. */
        const val COLOR_DE_LA_HOJA = "#ffffff"

        /** Lo más que se puede marcar una hoja con su mando. */
        const val MAXIMO_DE_LA_HOJA = 2.5

        /**
         * Hasta dónde llega la hoja **a lo hondo**, en píxeles de pantalla.
         *
         * Cuarenta mil: unas treinta pantallas hacia dentro y otras tantas hacia fuera, que
         * a efectos de dibujar es lo mismo que no acabarse, y sigue siendo un número con el
         * que las cuentas salen exactas.
         */
        private const val EL_PLANO_ENTERO = 40000.0

        /** Lo que tiene que medir un trazo, en píxeles, para no ser un toque suelto. */
        private const val MINIMO_PARA_CONTAR = 12.0

        /** Cada cuántos píxeles se guarda un punto del trazo. Ver [aligerado]. */
        /**
         * **Cada cuánto se posa un punto mientras se traza**, en píxeles de pantalla.
         *
         * Estuvo en tres, y tres píxeles se ven: una curva trazada deprisa salía como una
         * tira de rectas cosidas, y acercándose a mirarla se le veían las esquinas a
         * cualquier trazo. Con uno, la marcha pisa donde de verdad pasó la mano; lo que
         * sobra se quita al soltar, y **se quita por forma y no por distancia**, así que un
         * trazo recto no acaba con tres veces más puntos que antes. Ver [indicesAligerados].
         */
        private const val MINIMO_EN_PANTALLA = 1.0

        /**
         * Cuánto se le deja apartarse a la línea al aligerar, en píxeles de pantalla.
         *
         * Un tercio de píxel: por debajo no se ve ni acercándose, y por encima empiezan a
         * asomar las esquinas en las curvas cerradas.
         */
        private const val TOLERANCIA_DE_LA_CURVA = 0.33

        /** Cuánto contorno de un círculo se le concede a cada tramo, en píxeles. */
        private const val PIXELES_POR_TRAMO = 2.0
        private const val TRAMOS_MINIMOS_DEL_CIRCULO = 48
        private const val TRAMOS_MAXIMOS_DEL_CIRCULO = 720

        /** A partir de cuántos puntos un trazo deja de apretar, y cuánto se afloja. */
        private const val PUNTOS_HOLGADOS = 3000
        private const val AFLOJE = 3.0

        /** Por debajo de medio píxel de pantalla ya no hay nada que afinar en la marcha. */
        private const val MEDIO_PIXEL = 0.5

        /** Cuántas veces el paso típico puede crecer un tramo antes de partir la marcha. */
        private const val DESPROPORCION = 4.0

        /**
         * Techo de vueltas de la marcha por muestra: una red, no un presupuesto.
         *
         * Con el paso a un píxel hacen falta tres veces más vueltas para el mismo recorrido,
         * así que el techo sube con él: si no, un trazo rápido se quedaría a medias.
         */
        private const val TOPE_DE_LA_MARCHA = 1536

        /**
         * Cuánto hay que tener el dedo parado para que el trazo salga recto, y cuánto
         * puede temblar sin dejar de estar parado.
         *
         * Medio segundo largo: menos y una raya trazada despacio se endereza sola en
         * mitad de una curva, que es peor que no tenerlo. Y ocho píxeles de temblor,
         * porque un dedo apoyado nunca está quieto del todo —con dos o tres, el
         * contador no llegaba nunca a cumplirse en una mano normal—.
         */
        private const val ESPERA_PARA_LA_RECTA = 550L
        private const val TEMBLOR = 8.0

        /**
         * Cuánto tienen que separarse dos perpendiculares para que la hoja cuente como
         * curva. Cinco grados. Ver [Trazo3D.normales].
         */
        private val COSENO_DE_HOJA_CURVA = kotlin.math.cos(Math.toRadians(5.0))

        /** A cuánto de un trazo, en píxeles, se lo lleva el borrador. */
        private const val UMBRAL_DEL_BORRADOR = 22.0

        /**
         * Y lo más gordo que se deja borrar de una pasada, en píxeles.
         *
         * El borrador fino sale del grosor de la punta, y la punta llega a sesenta y cuatro:
         * a ese ancho y de cerca, una pasada se llevaría media pantalla y dejaría de ser
         * fino. Ver [radioDelBorrador].
         */
        private const val LO_MAS_GORDO_DEL_BORRADOR = 90.0

        /** A cuántos píxeles de una punta ya puesta engancha la nueva. */
        private const val UMBRAL_DEL_IMAN = 26.0

        /** Por debajo de esto, un eje se ve de punta y no se puede arrastrar por él. */
        private const val MINIMO_DEL_EJE_EN_PANTALLA = 1e-3

        /**
         * Por debajo de esta parte del aumento, un eje está tan de punta que su escala en
         * la pantalla ya no significa nada. Ver [moverLaSeleccionPorElEje].
         */
        private const val DE_PUNTA = 0.15

        /**
         * Cuántos píxeles de arrastre son una vuelta entera de lo elegido.
         *
         * Más corta que la de la vista: el cubo mide setenta y cuatro puntos de lado, y con
         * la vuelta de la pantalla entera no daría ni para un cuarto de giro sin soltar.
         */
        private const val VUELTA_DE_LA_MANO = 320.0

        /** Cuántos píxeles de arrastre van de la ortográfica al ojo de pez. */
        private const val RECORRIDO_DE_LA_LENTE = 520.0

        /**
         * Qué lente se pone al asomarse al mundo, en tanto por uno de [Camara3D.CAMPO_MAXIMO].
         *
         * Sale de cuadrar el campo de visión con el de la cámara de atrás de un teléfono
         * corriente, que anda por los sesenta y cinco grados. Es el número que hace que lo
         * dibujado y lo que se ve detrás **crezcan al mismo ritmo**: con la lente más
         * cerrada, el croquis se queda pequeño respecto de la habitación y parece una
         * maqueta; más abierta, se come la pared.
         *
         * No es exacto —cada teléfono trae la suya, y hay tres cámaras detrás— pero
         * preguntarle al aparato cuál está usando obliga a arrastrar media biblioteca de
         * cámara hasta aquí, y el error de unos grados no se ve. Ver
         * [Croquis3DControlador.verEnLaRealidad].
         */
        const val LENTE_DE_UN_TELEFONO = 65.0 / 160.0

        /**
         * A cuántos metros se da por puesto el croquis al colocarlo delante. Es lo que ata
         * las unidades del croquis a los metros de la habitación: no hay otra medida.
         */
        const val METROS_AL_COLOCARLO = 1.5

        /** Lo que anda un paso corriente, en metros. */
        const val LARGO_DE_PASO = 0.7

        /** El radio de fábrica del pincel de licuar, en píxeles de pantalla. */
        const val RADIO_DE_LICUAR = 110.0

        /** Lo gorda que es la bolita de seleccionar, en píxeles de pantalla. */
        const val RADIO_DE_LA_BOLITA = 30.0

        /** Cuánto se aparta una copia de su original, en píxeles. */
        private const val CORRIMIENTO_DE_LA_COPIA = 26.0

        private const val TOPE_DEL_HISTORIAL = 60

        /** Cuántas cajas de trazo se guardan. Bastantes más de las que se preguntan a la vez. */
        private const val TOPE_DE_LAS_CAJAS = 512
    }
}

/**
 * Un viaje de la vista: de qué postura sale y a cuál va.
 *
 * Guarda **las dos cámaras enteras** y no solo los ángulos: una vista guardada trae también
 * su aumento, a qué punto miraba y qué lente llevaba, y volver a ella es volver a todo eso.
 * Encajar en una cara del cubo es el mismo viaje con el aumento y el centro quietos.
 *
 * Guarda el punto de partida entero en vez de ir sumando cachitos por fotograma. Sumando,
 * un viaje interrumpido a media escala —o un fotograma que se pierde— deja la vista a
 * medio camino de ninguna parte; con el origen y el destino puestos, cada fotograma coloca
 * la vista donde le toca y el último la deja **exacta** en la vista recta, que es lo único
 * que importa de encajar.
 */
class ViajeDeLaVista(val desde: Camara3D, val hasta: Camara3D)

/**
 * El mismo ángulo, pero por el lado corto de la vuelta.
 *
 * Un giro de 350° y otro de 0° son el mismo sitio pero a media vuelta de distancia si se
 * restan a lo bruto. Se busca el equivalente que caiga a menos de media vuelta de donde se
 * está, y girar deja de dar el rodeo.
 */
internal fun porElCaminoCorto(desde: Double, hasta: Double): Double {
    var d = (hasta - desde) % (Math.PI * 2)
    if (d > Math.PI) d -= Math.PI * 2
    if (d < -Math.PI) d += Math.PI * 2
    return desde + d
}

/** Distancia de un punto a un segmento, en la pantalla. */
internal fun distanciaASegmento(p: Pt, a: Pt, b: Pt): Double {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val largo2 = vx * vx + vy * vy
    val t = if (largo2 <= 0.0) 0.0
    else (((p.x - a.x) * vx + (p.y - a.y) * vy) / largo2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + vx * t), p.y - (a.y + vy * t))
}
