package com.forge.pixpin.motor

/**
 * La máquina de estados del dedo. Port de la parte de `App.tsx` que decide qué
 * pasa entre que se toca la pantalla y que se levanta el dedo.
 *
 * **No sabe nada de Android ni de Compose**, igual que `AnnotationController`.
 * Recibe puntos ya convertidos a coordenadas de escena y devuelve escenas
 * nuevas. Eso es lo que permite probar aquí dentro toda la interacción —qué se
 * selecciona, qué se mueve, dónde acaba— sin dispositivo.
 *
 * La regla que gobierna todo el archivo: **una operación en curso se calcula
 * siempre contra los elementos originales**, guardados al empezar el gesto, y
 * nunca contra el resultado del fotograma anterior. Aplicar deltas encadenados
 * acumula error de redondeo y hace que la forma se deforme sola si arrastras
 * despacio.
 */
class DrawController(initial: Scene = Scene()) {

    var scene: Scene = initial
        private set

    var selectedIds: Set<String> = emptySet()
        private set

    var tool: Tool = Tool.SELECTION
        private set

    /** El rectángulo de selección en curso, para que la vista lo pinte. */
    var selectionBox: Bounds? = null
        private set

    /** El trazo del lazo en curso. */
    var lassoPath: List<Pt> = emptyList()
        private set

    /** La forma a la que se engancharía la punta si se soltase ahora. */
    var bindingHighlight: String? = null
        private set

    private val history = History()
    private var gesture: Gesture = Gesture.None

    /** La escena tal como estaba al empezar el gesto: la base del historial. */
    private var sceneAtGestureStart: List<Element> = emptyList()

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    // ---------------------------------------------------------------------
    // Herramienta y selección
    // ---------------------------------------------------------------------

    /**
     * Cambia de herramienta.
     *
     * Salir del modo selección **deselecciona**: si no, los tiradores se
     * quedarían encima mientras dibujas y robarían el primer toque.
     */
    fun selectTool(next: Tool) {
        // **La caja a medio hacer se cierra al irse.** El gesto del volumen deja
        // una caja esperando altura entre fase y fase, y cambiar de herramienta
        // ahí la dejaría pendiente para siempre: el siguiente toque con el sólido
        // —a lo mejor media hora después y en la otra punta del dibujo— habría
        // levantado aquella. Se fija con lo que tenga, que es lo que se ve.
        if (tool == Tool.SOLIDO && next != Tool.SOLIDO) fijarSolido()
        // Un gesto de dos tiempos no sobrevive a cambiar de herramienta: dejarlo a medias
        // haría que el siguiente toque, tres minutos después, torneara algo por sorpresa.
        if (next != Tool.REVOLUCION) figuraATornear = null
        tool = next
        // La bolita también conserva lo elegido: es una forma de seleccionar más, y su
        // gesto es justo ir sumando — cambiar a ella para seguir cogiendo y perder lo que se
        // llevaba sería lo contrario de para lo que está.
        if (next != Tool.SELECTION && next != Tool.LASSO && next != Tool.BOLITA) {
            selectedIds = emptySet()
        }
    }

    /**
     * El óvalo de referencia cuyo borde está bajo el dedo, si lo hay.
     *
     * Solo elipses y arcos, y solo de referencia: repasar un rectángulo no
     * significa nada todavía, y repasar algo del dibujo de verdad sería
     * sorprendente — ahí lo que se quiere es dibujar otra cosa encima.
     */
    private fun ovaloDeReferenciaEn(p: Pt, threshold: Double): Element? =
        scene.visibleConReferencias.lastOrNull {
            it.reference && !it.locked &&
                (it.type == ElementType.ELLIPSE || it.type == ElementType.ARC) &&
                isPointOnElementOutline(p, it, threshold)
        }

    /** Empieza a trazar sobre [guia], que se queda donde está. */
    private fun empezarArcoSobre(guia: Element, p: Pt) {
        val angulo = anguloEnElOvalo(guia, p)
        val arco = newElement(
            ElementType.ARC, guia.x, guia.y, scene.style,
            width = guia.width, height = guia.height
        ).copy(angle = guia.angle, arcStart = angulo, arcSweep = 0.0)
        scene = scene.copy(elements = scene.elements + arco)
        gesture = Gesture.Arcoing(arco.id, angulo)
    }

    // ---------------------------------------------------------------------
    // Referencias
    // ---------------------------------------------------------------------

    val hayReferencias: Boolean get() = scene.elements.any { it.reference && !it.isDeleted }

    val referenciasVisibles: Boolean get() = scene.referenciasVisibles

    /** Esconde o enseña las referencias. No borra nada. */
    fun alternarReferencias() {
        scene = scene.copy(referenciasVisibles = !scene.referenciasVisibles)
        // Lo escondido no se puede tener seleccionado: los tiradores se
        // quedarían flotando sobre algo que ya no se ve.
        if (!scene.referenciasVisibles) {
            selectedIds = selectedIds.filter { id -> scene.byId(id)?.reference != true }.toSet()
        }
    }

    // **No hay «borrar todas las guías».** Lo hubo y se ha quitado: un solo
    // toque que se lleva por delante todo el andamio es de las cosas que solo se
    // pulsan por error, y el error sale carísimo. Se esconden para mirar el
    // dibujo limpio, y lo que sobre se quita con el borrador en modo guía, de
    // una en una y viendo lo que se quita.

    fun setSelection(ids: Set<String>) {
        selectedIds = ids
    }

    fun selectAll() {
        selectedIds = editables.filter { !it.locked }.map { it.id }.toSet()
    }

    fun deselect() {
        selectedIds = emptySet()
    }

    /** Los elementos seleccionados, en el orden de la escena. */
    fun selectedElements(): List<Element> = scene.elements.filter { it.id in selectedIds }

    // ---------------------------------------------------------------------
    // El gesto
    // ---------------------------------------------------------------------

    /**
     * El dedo baja en [p] (coordenadas de escena).
     *
     * [pressure] solo la usa el lápiz. [zoom] hace falta para que los umbrales
     * de toque midan lo mismo en pantalla a cualquier aumento.
     */
    /**
     * **Modo dedo: todo se coge más fácil.**
     *
     * Un dedo no es un lápiz. Los umbrales de toque están pensados para acertar
     * con punta fina, y con el dedo se falla constantemente: se pica al lado de
     * la raya, se coge la figura de detrás, no se agarra el tirador. Con esto
     * puesto, todas las zonas de toque se ensanchan de golpe — no una a una, que
     * es como se olvida siempre alguna.
     *
     * No cambia **nada** de lo que se dibuja: solo hasta dónde llega el dedo.
     */
    var modoDedo: Boolean = false

    /**
     * Cuánto alto necesita un texto para caber en un ancho dado.
     *
     * Lo pone quien tenga Android delante —medir letras depende de la fuente— y sirve para
     * lo que el usuario espera de una caja de texto: **estrecharla reparte el texto en más
     * renglones y la caja se hace más alta sola**. Sin esto, estrechar dejaba el texto
     * saliéndose por un lado o el alto mintiendo sobre lo que hay dentro.
     *
     * Nulo quiere decir «aquí no hay quien mida»: entonces la caja se estira a pelo, que es
     * lo que hacía siempre.
     */
    var altoDelTexto: ((Element, Double) -> Double)? = null

    /** Lo que se ensancha cada zona de toque con el modo dedo puesto. */
    private fun margenDelDedo(zoom: Double): Double =
        DEFAULT_HIT_THRESHOLD * (if (modoDedo) ENSANCHE_DEL_DEDO else 1.0) / zoom

    fun pointerDown(
        pRaw: Pt,
        pressure: Double = 1.0,
        zoom: Double = 1.0,
        /**
         * La hora que trae el evento, **no la del reloj de la pared**.
         *
         * Con la de la pared, la resta entre esta y la del latido son las horas que lleva
         * encendido el aparato: el gesto se cumplía en el primer fotograma y cualquier
         * raya salía recta nada más empezarla. Ver [latido].
         */
        cuando: Long = 0L
    ) {
        zoomDelTrazo = zoom
        sceneAtGestureStart = scene.elements
        val threshold = margenDelDedo(zoom)
        // Solo se engancha al DIBUJAR. Al seleccionar, mover o encuadrar el
        // dedo tiene que ir donde va: tirar de él ahí sería un estorbo.
        // **Una sola pregunta al motor del imán.** Ver [Iman]: qué engancha con
        // qué deja de decidirse aquí, herramienta por herramienta, y pasa a
        // salir de la faena que se esté haciendo.
        // Una sola tabla de faenas y una sola puerta al imán. Ver [faenaDeAhora] e [imantado].
        val p = imantado(pRaw, faenaDeAhora(hayAlgoEnLaMano = true), zoom)

        when (tool) {
            // **El punto de partida se guarda en coordenadas de PANTALLA.** En
            // escena no vale: la conversión depende del desplazamiento, que es
            // justo lo que este gesto cambia. Ver [Gesture.Panning].
            Tool.HAND -> gesture = Gesture.Panning(scene.viewport.toScreen(p), scene.viewport)

            Tool.ERASER -> {
                gesture = Gesture.Erasing
                scene = scene.copy(
                    elements = eraseAt(scene.elements, p, threshold, ::editable)
                )
            }

            Tool.LASSO -> {
                lassoPath = listOf(p)
                gesture = Gesture.Lassoing(mutableListOf(p))
            }

            Tool.SELECTION -> beginSelectionGesture(p, threshold, zoom)

            // **Repasar un óvalo de referencia dibuja el trozo que repasas.**
            // Es el transportador: se pone el círculo en azul, se recorre con el
            // lápiz y queda el arco. Solo cuando el dedo baja sobre su borde; en
            // cualquier otro sitio, la elipse hace lo de siempre.
            //
            // El margen es **mucho más ancho que el de picar** y por eso tiene
            // su propia constante. Una guía es una raya fina, y acertarle a diez
            // píxeles con el dedo sale mal una de cada tres: lo que pasaba
            // entonces era que en vez de repasar el arco nacía un círculo nuevo
            // encima, que es un resultado bastante peor que fallar. Al revés no
            // duele: empezar un arco sin querer se deshace levantando el dedo,
            // porque un barrido de nada no deja nada. Ver [UMBRAL_GUIA].
            Tool.ELLIPSE -> {
                val guia = ovaloDeReferenciaEn(p, UMBRAL_GUIA / zoom.coerceAtLeast(0.0001))
                if (guia != null && !modoReferencia) empezarArcoSobre(guia, p)
                else beginCreate(elementTypeOf(tool), p)
            }

            // **La lupa es una varita: lo que toca lo convierte.**
            //
            // No traza nada ni pregunta de qué forma la quieres. Se dibuja lo
            // que sea con las herramientas de siempre —un círculo, un rombo, un
            // garabato cerrado a pulso— y se toca con la lupa: ese mismo
            // contorno pasa a enseñar en grande lo que hay debajo. Sobre el
            // hueco no hace nada, porque no hay nada que convertir.
            Tool.LUPA, Tool.SPOTLIGHT -> {
                editables.lastOrNull { sirveDeLupa(it) && isPointInElement(p, it) }
                    ?.let { deLaFigura(it, tool) }
            }

            Tool.RECTANGLE, Tool.DIAMOND, Tool.MOSAIC,
            Tool.FRAME, Tool.ESCALA_GRAFICA,
            // El cronograma se pone como cualquier lámina: se arrastra su caja y dentro
            // aparece la rejilla ya repartida, con sus tres filas. Ver [Cronograma].
            Tool.CRONOGRAMA -> beginCreate(elementTypeOf(tool), p)

            // La bolita: un barrido, y lo que toque entra o sale. No crea nada, así que no
            // hay elemento en curso ni nada que soltar al levantar.
            Tool.BOLITA -> {
                yaTocados = emptySet()
                gesture = Gesture.PasandoLaBolita
                pasarLaBolita(p, zoom)
            }

            Tool.SOLIDO -> empezarSolido(p)

            // **Se toca la figura y se sube.**
            //
            // El mismo gesto que la segunda fase de dibujar una caja: el dedo se apoya en
            // lo que se quiere levantar y **arrastrando hacia arriba se le da altura**,
            // viéndola crecer. Antes salía de un toque con una altura adivinada, y
            // adivinar la altura de algo es justo lo que no se puede hacer por el otro.
            //
            // Un toque seco, sin arrastre, la deja en un cuadro de alto: es lo mismo que
            // hace la herramienta de la caja, y evita que un dedo torpe deje una plancha.
            Tool.EXTRUIR -> {
                // Nace **sin altura**: mientras no se suba, lo que se ve es su huella en
                // el suelo, que es la señal de que falta la segunda mitad del gesto.
                getElementAtPosition(editables, p, threshold, scene.vista)
                    ?.let { extruirTocando(it, p) }
            }

            // **El torno, en dos tiempos**: primero la figura, después el eje.
            Tool.REVOLUCION -> {
                val tocado = getElementAtPosition(editables, p, threshold, scene.vista)
                val pendiente = figuraATornear?.let { scene.byId(it) }
                when {
                    tocado == null -> figuraATornear = null
                    // Segundo tiempo: la raya que hace de eje. Se admite tocar la misma
                    // figura otra vez para desdecirse sin cambiar de herramienta.
                    pendiente != null && tocado.id != pendiente.id -> {
                        // **El eje es la raya, se haya tocado primero o después.** Iba
                        // por orden y tocando antes la raya salía al revés: la raya hacía
                        // de figura y la figura de eje, y el cuerpo era cualquier cosa.
                        val eje = if (tocado.isLinear) tocado
                        else if (pendiente.isLinear) pendiente else tocado
                        val figura = if (eje.id == tocado.id) pendiente else tocado
                        val cuerpo = revolucionado(figura, eje, scene.vista)
                        if (cuerpo != null) {
                            val fuera = setOf(figura.id, eje.id)
                            mutate { lista -> lista.filterNot { it.id in fuera } + cuerpo }
                            selectedIds = setOf(cuerpo.id)
                        }
                        figuraATornear = null
                    }
                    pendiente != null -> figuraATornear = null
                    else -> figuraATornear = tocado.id
                }
            }

            Tool.ARROW, Tool.LINE, Tool.MEASURE, Tool.SCALE ->
                beginCreateLinear(elementTypeOf(tool), p)

            // **La flecha libre**: nace como flecha —con su punta— pero se traza a pulso.
            // Ver [Tool.FLECHA_LIBRE].
            Tool.FLECHA_LIBRE -> {
                val e = newElement(ElementType.ARROW, p.x, p.y, scene.style).copy(
                    points = listOf(Pt(0.0, 0.0)),
                    endArrowhead = Arrowhead.ARROW,
                    // **Y lisa, no rugosa.** El trazado a mano alzada de las figuras dibuja
                    // cada tramo dos veces y algo torcido, que es lo que le da el aire de
                    // boceto a un rectángulo de cuatro tramos. Una flecha trazada a pulso
                    // tiene **cientos** de tramos de un píxel, así que ese mismo adorno la
                    // convierte en una maraña de rayas que además tiembla al moverla. Lo que
                    // uno traza a pulso ya lleva su temblor: no hay que añadirle otro.
                    roughness = Element.ROUGHNESS_ARCHITECT,
                    reference = modoReferencia
                )
                scene = scene.copy(elements = scene.elements + e)
                gesture = Gesture.Creating(e.id)
                olvidarElDedoParado()
            }

            Tool.FREEDRAW, Tool.HIGHLIGHTER -> {
                // El lápiz tiene su propia escala de grosores: la mancha se
                // dibuja a 4,25× y con la escala de las formas saldría enorme.
                var e = newElement(ElementType.FREEDRAW, p.x, p.y, scene.style)
                    .copy(
                        pressures = listOf(pressure),
                        strokeWidth = ItemStyle.freedrawWidthFor(scene.style.strokeWidth),
                        reference = modoReferencia
                    )
                // El marcador es el lápiz translúcido y gordo: no hace falta un
                // tipo de elemento propio, y así se edita, se borra y se exporta
                // por el mismo camino que el resto.
                if (tool == Tool.HIGHLIGHTER) {
                    e = e.copy(
                        opacity = HIGHLIGHTER_OPACITY,
                        strokeWidth = e.strokeWidth * ItemStyle.ENGORDE_DEL_MARCADOR
                    )
                }
                scene = scene.copy(elements = scene.elements + e)
                gesture = Gesture.Creating(e.id)
                // El dedo acaba de posarse: desde aquí se cuenta lo que lleva quieto, que
                // es lo que decide si esto acaba en recta o en compás. Ver [latido].
                olvidarElDedoParado()
                quietoEn = p
                quietoDesde = cuando
            }

            // Un toque = un número. No se arrastra, igual que el texto.
            Tool.SERIAL -> {
                val radio = scene.style.fontSize * SERIAL_RADIUS
                val e = newElement(
                    ElementType.SERIAL, p.x - radio, p.y - radio, scene.style,
                    width = radio * 2, height = radio * 2
                ).copy(text = nextSerial().toString(), reference = modoReferencia)
                scene = scene.copy(elements = scene.elements + e)
                gesture = Gesture.None
                commit()
            }

            // **El texto también espera a que se levante el dedo.** Plantaba
            // al bajarlo, y el primer dedo de un pellizco para hacer zoom baja
            // igual que un toque: la escena se llenaba de textos vacíos que
            // nadie había pedido. Es el mismo arreglo que salvó al bote.
            Tool.TEXT -> gesture = Gesture.Tocando(p)

            // **Las de un toque no hacen nada hasta que se levanta el dedo.**
            //
            // Actuaban al bajarlo, y eso las disparaba sin querer: el primer
            // dedo de un pellizco para hacer zoom, o el que empieza a arrastrar
            // el lienzo, bajan igual que un toque. La escena se llenaba de
            // botellazos que nadie había pedido, y encima quedaban hechos —el
            // gesto se cancela al aparecer el segundo dedo, pero lo que ya se
            // había anotado en el historial no se deshace solo.
            //
            // Esperando al final, mover el dedo o apoyar el segundo lo anula.
            Tool.RELLENO, Tool.RECORTAR, Tool.EXTENDER, Tool.NUDO, Tool.PUNTO ->
                gesture = Gesture.Tocando(p)

            // La imagen la coloca la interfaz, que es quien abre el selector.
            Tool.IMAGE -> gesture = Gesture.None
        }
    }

    /**
     * Cierra una herramienta de un toque, si de verdad fue un toque.
     *
     * Con el dedo paseado por medio no se hace nada: quien arrastra no está
     * tocando, está intentando mover el lienzo o se ha equivocado de sitio.
     */
    private fun terminarToque(inicio: Pt, fin: Pt, zoom: Double) {
        val z = zoom.coerceAtLeast(0.0001)
        if (kotlin.math.hypot(fin.x - inicio.x, fin.y - inicio.y) > TOQUE_QUIETO / z) return
        when (tool) {
            Tool.RELLENO -> rellenar(inicio)
            Tool.PUNTO -> plantarPunto(inicio, z)
            Tool.TEXT -> plantarTexto(inicio, margenDelDedo(z))
            Tool.RECORTAR -> recortar(inicio, z)
            Tool.EXTENDER -> extender(inicio, z)
            Tool.NUDO -> soldar(inicio, z)
            else -> Unit
        }
    }

    /**
     * Rellena el hueco cerrado que contiene [p].
     *
     * Si no hay ninguno **no se dibuja nada** y se deja dicho en
     * [rellenoSinCerrar]. Es la única respuesta honesta: pintar «lo que se pueda»
     * cuando el espacio está abierto teñiría media escena, y un botellazo que
     * hay que deshacer asusta más que uno que no hace nada.
     */
    /**
     * De qué serie salen las letras de los puntos: A, a o 1.
     *
     * Es del controlador y no del estilo del elemento porque **es una decisión
     * del dibujo entero**, no de cada punto: en un croquis los vértices son
     * mayúsculas y los lados minúsculas, y quien elige una serie va a poner
     * diez puntos seguidos de esa serie.
     */
    var seriePuntos: SerieDePunto = SerieDePunto.MAYUSCULAS

    /**
     * Planta un punto con su letra donde se ha tocado.
     *
     * El sitio ya llega imantado —el imán actúa antes, en `pointerDown`—, que es
     * lo que hace que el punto caiga **exactamente** en la intersección o en el
     * vértice y no a dos píxeles. En geometría eso no es cosmético: un punto que
     * no está en la intersección no es el punto del que habla el enunciado.
     */
    private fun plantarPunto(p: Pt, zoom: Double) {
        // **O cae en un sitio notable o no cae.** Un punto de geometría a dos
        // píxeles del vértice deja de ser ese punto, y todo lo que se deduzca de
        // él a partir de ahí es falso. Ver [sitioValidoParaPunto].
        val anclaje = Iman.sitio(
            scene, p, zoom, Iman.Faena.SITIO_NOTABLE, enganche, elementos = editables
        )
        if (anclaje == null) {
            puntoSinSitio = true
            return
        }
        puntoSinSitio = false

        val before = scene.elements
        val e = nuevoPunto(
            id = randomId(),
            donde = anclaje.punto,
            elementos = editables,
            serie = seriePuntos,
            estilo = scene.style,
            seed = randomSeed()
        ).copy(reference = modoReferencia)
        scene = scene.copy(elements = scene.elements + e)
        selectedIds = setOf(e.id)
        anotar(before)
    }

    /**
     * No había cruce, extremo ni centro donde se tocó.
     *
     * Se dice, por lo mismo que el bote dice cuando no encuentra hueco cerrado:
     * callarse dejaría a alguien tocando una y otra vez sin entender por qué no
     * aparece nada. Ver [rellenoSinCerrar].
     */
    var puntoSinSitio: Boolean = false
        private set

    fun limpiarAvisoDePunto() {
        puntoSinSitio = false
    }

    private fun rellenar(p: Pt) {
        // **El bote también vive en un solo mundo.** En modo guía encierran las
        // guías y solo ellas; fuera, el dibujo y solo él. Mezclarlos hacía que
        // el andamio recortara los rellenos del dibujo por sitios donde no hay
        // nada pintado —y al revés, que no se pudiera rellenar un hueco del
        // andamio porque una raya del dibujo lo cruzaba.
        val region = regionEn(editables, p, ajustesRelleno)
        if (region == null) {
            rellenoSinCerrar = true
            return
        }
        rellenoSinCerrar = false
        val relleno = nuevaRegion(region, scene.style).copy(reference = modoReferencia)
        scene = scene.copy(elements = conRellenoDebajo(scene.elements, relleno, tocado = p))
    }

    /**
     * Planta un texto donde se tocó, o abre el que ya hubiera ahí.
     *
     * **Tocar un texto ya puesto lo abre para corregirlo**, en vez de plantar
     * otro encima: es lo que evita el montón de textos superpuestos que salía al
     * intentar arreglar una errata, porque el de abajo seguía ahí.
     *
     * Y el texto recién plantado **no se selecciona**: como todavía no tiene
     * medidas —su caja se mide cuando hay algo escrito— los nueve tiradores de
     * la selección salían amontonados encima del cursor, justo donde estabas
     * escribiendo.
     */
    private fun plantarTexto(p: Pt, threshold: Double) {
        val debajo = getElementAtPosition(editables, p, threshold, scene.vista)

        // Un texto que ya está: se abre para seguir escribiendo.
        val existente = debajo?.takeIf { it.type == ElementType.TEXT && !it.locked }
        if (existente != null) {
            selectedIds = emptySet()
            pendingTextId = existente.id
            return
        }

        // **Una figura que admite texto: se escribe dentro de ella.**
        //
        // Es lo que convierte esto en una herramienta de diagramas. Antes el
        // texto caía encima de la figura como un elemento suelto: se veía igual
        // hasta que movías la caja y el texto se quedaba atrás. Ver
        // [TextoEnFiguras].
        //
        // Se busca **dentro** y no con el buscador de siempre: una figura sin
        // relleno solo se coge por su contorno —así es como se selecciona algo
        // transparente sin robarle el toque a lo que tenga detrás— y con esa
        // regla, tocar el centro de un rectángulo vacío no encontraba nada. Para
        // escribir dentro, el sitio natural es justamente el centro.
        val contenedor = editables.lastOrNull {
            admiteTextoDentro(it.type) && !it.locked && isPointInElement(p, it)
        }
        if (contenedor != null) {
            // Si ya tenía texto, se sigue escribiendo en el suyo.
            val suyo = textoDe(contenedor, editables)
            if (suyo != null) {
                selectedIds = emptySet()
                pendingTextId = suyo.id
                return
            }
            val hueco = esquinaDelHueco(contenedor)
            val texto = newElement(ElementType.TEXT, hueco.x, hueco.y, scene.style)
                .copy(reference = contenedor.reference, textAlign = TextAlign.CENTER)
            val (conBound, atado) = atados(contenedor, texto)
            scene = scene.copy(
                elements = scene.elements.map { if (it.id == contenedor.id) conBound else it } + atado
            )
            selectedIds = emptySet()
            pendingTextId = atado.id
            return
        }

        val e = newElement(ElementType.TEXT, p.x, p.y, scene.style)
            .copy(reference = modoReferencia)
        scene = scene.copy(elements = scene.elements + e)
        selectedIds = emptySet()
        pendingTextId = e.id
    }

    /**
     * Recorta el trozo de raya que se toca, hasta donde la cruzan otras.
     *
     * Es el `trim` de toda la vida: se traza de largo, se cruza con lo que tenga
     * que cruzar y se van quitando los sobrantes tocándolos. Ver [Recorte].
     */
    private fun recortar(p: Pt, zoom: Double) {
        val victima = figuraCerca(p, zoom) ?: return
        val trozos = recortarEn(victima, paredesPara(victima), p)
        if (trozos == null) return
        mutate { elementos ->
            elementos.flatMap {
                when {
                    it.id != victima.id -> listOf(it)
                    trozos.isEmpty() -> listOf(it.copy(isDeleted = true).touched())
                    else -> trozos
                }
            }
        }
    }

    /**
     * Suelda entre sí los vértices que hay bajo el dedo, o los desuelda si ya
     * lo estaban.
     *
     * El mismo toque hace las dos cosas a propósito: soldar y desoldar son la
     * misma pregunta —«¿estos van juntos?»— y separarlas en dos herramientas
     * obligaría a elegir antes de saber qué hay ahí. Si no hay dos vértices que
     * juntar no pasa nada, y se avisa igual que con el bote.
     */
    private fun soldar(p: Pt, zoom: Double) {
        val nuevos = clavarEn(editables, scene.alfileres, p, UMBRAL_GUIA / zoom)
        if (nuevos == null) {
            nudoSinPareja = true
            return
        }
        nudoSinPareja = false
        scene = scene.copy(alfileres = refrescarAlfileres(scene.elements, nuevos))
    }

    /**
     * El último intento de soldar no encontró dos vértices que juntar.
     *
     * Lo mismo que [rellenoSinCerrar] y por lo mismo: el controlador no sabe
     * pintar avisos, y un toque que no hace nada sin decir por qué parece una
     * herramienta rota.
     */
    var nudoSinPareja: Boolean = false
        private set

    fun limpiarAvisoNudo() {
        nudoSinPareja = false
    }

    /**
     * Los ángulos que enseñar ahora mismo, o nada si no se está moviendo nada.
     *
     * Aparecen al empezar el gesto y se van al soltar: un dibujo con todos sus
     * ángulos escritos no se lee, pero mientras arrastras un vértice el ángulo
     * es lo único que quieres saber. Ver [angulosInternos].
     */
    fun angulosDelGesto(): List<AnguloInterno> {
        val moviendose = when (val g = gesture) {
            is Gesture.GirandoEtiqueta -> setOf(g.elementId)
            is Gesture.MovingPoint -> setOf(g.elementId)
            is Gesture.Moving -> g.originals.map { it.id }.toSet()
            is Gesture.Rotating -> g.originals.map { it.id }.toSet()
            is Gesture.MovingPin ->
                scene.alfileres.getOrNull(g.indice)?.agarres?.map { it.elementId }?.toSet()
                    ?: emptySet()
            is Gesture.Creating -> setOf(g.elementId)
            else -> emptySet()
        }
        if (moviendose.isEmpty()) return emptyList()
        return angulosInternos(scene.visibleConReferencias, scene.alfileres, moviendose)
    }

    /** Dónde hay que pintar las cabezas de los clavos. Ver [Alfiler]. */
    fun nudosVisibles(): List<Pt> =
        puntosDeAlfileres(scene.visibleConReferencias, scene.alfileres)

    /**
     * Estira la raya que se toca hasta la primera figura que se encuentre.
     *
     * **Solo rayas.** Un rectángulo no se puede «estirar hasta topar»: no tiene
     * punta, tiene cuatro lados, y lo que uno quiere ahí es agrandarlo — que se
     * hace arrastrando sus tiradores, y ya funciona.
     */
    private fun extender(p: Pt, zoom: Double) {
        val victima = figuraCerca(p, zoom, soloRayas = true) ?: return
        val estirada = extenderEn(victima, paredesPara(victima), p) ?: return
        mutate { elementos -> elementos.map { if (it.id == victima.id) estirada else it } }
    }

    /**
     * La figura que hay bajo el dedo, de las que se pueden recortar.
     *
     * No solo rayas: también el óvalo, el arco, el rectángulo y el rombo. En un
     * plano se traza la caja de largo y se le van quitando los trozos que
     * sobran, y no poder hacerlo con lo que no fuera una línea dejaba la
     * herramienta a medias.
     */
    private fun figuraCerca(p: Pt, zoom: Double, soloRayas: Boolean = false): Element? {
        val vale: (Element) -> Boolean = when {
            soloRayas -> { e -> e.isLinear }
            else -> { e ->
                e.isLinear || e.type == ElementType.ELLIPSE || e.type == ElementType.ARC ||
                    e.type == ElementType.RECTANGLE || e.type == ElementType.DIAMOND
            }
        }
        return editables.filter { !it.locked && vale(it) }
            .lastOrNull { isPointOnElementOutline(p, it, UMBRAL_GUIA / zoom) }
    }

    /** Contra qué se corta o hasta dónde se estira: todo lo demás que se ve. */
    private fun paredesPara(e: Element): List<Element> =
        scene.visibleConReferencias.filter { it.id != e.id && !it.isFrame }

    /** El dedo se mueve. */
    fun pointerMove(
        pRaw: Pt,
        pressure: Double = 1.0,
        zoom: Double = 1.0,
        /** La hora del evento. Ver [pointerDown]. */
        cuando: Long = 0L
    ) {
        zoomDelTrazo = zoom
        // **Lo quieto que está el dedo se mide en pantalla.** A un aumento pequeño, un
        // temblor de dos píxeles son metros de la escena: midiéndolo en unidades del
        // dibujo, el gesto saldría a un aumento y no saldría a otro.
        val anclado = quietoEn
        if (anclado == null ||
            kotlin.math.hypot(pRaw.x - anclado.x, pRaw.y - anclado.y) * zoom > TEMBLOR_DEL_DEDO
        ) {
            quietoEn = pRaw
            quietoDesde = cuando
        }
        // **También al recolocar un punto de algo ya dibujado.** El imán servía
        // solo mientras se trazaba, y corregir después la punta de una flecha
        // para que cayera justo en una esquina había que hacerlo a pulso, que
        // es precisamente lo que el imán existe para evitar.
        val enCurso = (gesture as? Gesture.Creating)?.elementId
            ?: (gesture as? Gesture.MovingPoint)?.elementId
        val moviendoPunto = gesture is Gesture.MovingPoint
        // Al recolocar un punto ya dibujado se afina, no se traza: mandan los
        // ajustes de siempre aunque la herramienta puesta sea el lápiz, porque
        // lo que se está haciendo es corregir una punta.
        val p = imantado(
            pRaw,
            faenaDeAhora(hayAlgoEnLaMano = enCurso != null, moviendoPunto = moviendoPunto),
            zoom,
            excluir = enCurso
        )

        when (val g = gesture) {
            is Gesture.None -> return

            is Gesture.Panning -> {
                val v = g.startViewport
                // El dedo llega en coordenadas de escena, calculadas con el
                // desplazamiento **de ahora**. Se deshace esa conversión con el
                // mismo viewport para recuperar dónde está el dedo de verdad, y
                // solo entonces se mide contra el punto de partida.
                val pantalla = scene.viewport.toScreen(p)
                scene = scene.copy(
                    viewport = v.copy(
                        scrollX = v.scrollX + (pantalla.x - g.startPointer.x) / v.zoom,
                        scrollY = v.scrollY + (pantalla.y - g.startPointer.y) / v.zoom
                    )
                )
            }

            is Gesture.Erasing ->
                scene = scene.copy(
                    elements = eraseAt(
                        scene.elements, p, DEFAULT_HIT_THRESHOLD / zoom, ::editable
                    )
                )

            // Se toca y se arrastra: no era un toque. Se deja constancia para
            // que al levantar no se dispare nada.
            is Gesture.Tocando -> Unit

            // Arrancar el clavo y volver a clavarlo en otro sitio, con todo lo
            // que atraviesa puesto.
            is Gesture.MovingPin -> {
                val alfiler = scene.alfileres.getOrNull(g.indice) ?: return
                // **El clavo también se imanta.** Un clavo se pone donde dos
                // figuras se cruzan, así que al recolocarlo lo que uno busca es
                // otro cruce o un vértice — y eso a pulso no se acierta. Se
                // excluyen las figuras que el propio clavo lleva: si no, se
                // pegaría a sí mismo en cuanto se moviera un pelo.
                val suyas = alfiler.agarres.map { it.elementId }.toSet()
                val destino = Iman.sitio(
                    scene, pRaw, zoom, Iman.Faena.AFINANDO, enganche,
                    elementos = scene.visibleConReferencias.filter { it.id !in suyas }
                )?.also { anclajeActivo = it }?.punto ?: p
                scene = scene.copy(
                    elements = moverAlfiler(scene.elements, scene.alfileres, alfiler, destino),
                    alfileres = scene.alfileres.mapIndexed { i, a ->
                        if (i == g.indice) a.copy(punto = destino) else a
                    }
                )
            }

            is Gesture.PasandoLaBolita -> pasarLaBolita(p, zoom)

            is Gesture.Lassoing -> {
                g.points += p
                lassoPath = g.points.toList()
            }

            is Gesture.BoxSelecting -> {
                val b = Bounds(
                    minOf(g.origin.x, p.x), minOf(g.origin.y, p.y),
                    maxOf(g.origin.x, p.x), maxOf(g.origin.y, p.y)
                )
                selectionBox = b
                selectedIds = getElementsWithinSelection(editables, b, g.mode, scene.vista)
                    .map { it.id }.toSet()
            }

            is Gesture.Creating -> updateCreating(g.elementId, p, pressure)

            // Primera fase: la huella, siempre sobre el suelo. Ver [empezarSolido].
            is Gesture.Huella -> {
                val e = scene.byId(g.elementId) ?: return
                val h = huellaArrastrada(g.origen, p, scene.vista)
                replace(e.copy(x = h.x, y = h.y, width = h.ancho, height = h.fondo))
            }

            // Segunda fase: **solo el desplazamiento vertical**.
            //
            // De `p` se lee la `y` y nada más. No es una simplificación: es lo
            // que hace imposible la ambigüedad de la isométrica (ver
            // [empezarSolido]) y por eso la `x` no aparece en esta cuenta.
            //
            // Se resta porque en pantalla la `y` crece hacia abajo: subir el dedo
            // tiene que subir la caja.
            is Gesture.Levantando -> {
                val e = scene.byId(g.elementId) ?: return
                val subido = g.alturaDePartida + (g.inicio.y - p.y)
                replace(e.copy(altura = alturaImantada(subido)))
            }

            // La caja ya hecha, por sus tiradores. El del alto lee **solo la `y`**, por
            // lo mismo que la segunda fase de dibujarla; los del suelo llevan el dedo al
            // suelo y lo imantan, como la primera.
            is Gesture.MoldeandoSolido -> {
                val e = scene.byId(g.elementId) ?: return
                when (g.tirador) {
                    // Los dos de la arista de atrás leen **solo la `y`**, por lo mismo
                    // que la segunda fase de dibujarla: en isométrica un arrastre en
                    // diagonal vale para subir y para alejarse, y la `x` es la que
                    // introduce esa ambigüedad. El de arriba pone la cima —lo que mide
                    // sale de restarle la cota—, y el de abajo, dónde apoya.
                    HandleType.SOLIDO_ALTURA -> {
                        val cima = alturaImantada((e.y - p.y) / PASO_DEL_SOLIDO)
                        replace(e.copy(altura = kotlin.math.max(cima - e.cota, 0.0)))
                    }

                    HandleType.SOLIDO_COTA ->
                        replace(e.copy(cota = alturaImantada((e.y - p.y) / PASO_DEL_SOLIDO)))

                    HandleType.SOLIDO_GIRO ->
                        replace(e.copy(giroEnPlanta = giroPedido(e, p, scene.vista)))

                    // Y los del suelo llevan el dedo al suelo y lo imantan, como la
                    // primera fase. El tirador va a la altura a la que la caja apoya, así
                    // que se le descuenta esa subida antes de desproyectar: si no, una
                    // caja apoyada en alto crecería sola al agarrarla.
                    else -> {
                        val enElSuelo = Pt(p.x, p.y + e.cota * PASO_DEL_SOLIDO)
                        val h = huellaMoldeada(
                            Pt(e.x, e.y), enElSuelo, e.width, e.height, g.tirador,
                            scene.vista, giro = e.giroEnPlanta
                        )
                        replace(e.copy(x = h.x, y = h.y, width = h.ancho, height = h.fondo))
                    }
                }
            }

            is Gesture.BarraDelPlan -> {
                val e = scene.byId(g.elementId) ?: return
                val nueva = tareaArrastrada(e, g.indice, g.mano, p, g.agarre) ?: return
                replace(
                    e.copy(
                        tareas = e.tareas.mapIndexed { i, t -> if (i == g.indice) nueva else t }
                    ).touched()
                )
            }

            is Gesture.GirandoVolumen -> {
                val ahora = anguloEnElAnillo(g.centro, g.eje, p, scene.vista)
                if (ahora != null) {
                    val bruto = ahora - g.anguloInicial
                    // A quince grados, como el giro en planta por su tirador: es lo que
                    // hace que dos piezas torcidas queden torcidas lo mismo.
                    val delta = Math.round(bruto / ESCALON_DEL_GIRO) * ESCALON_DEL_GIRO
                    applyToOriginals(g.originals) { originales ->
                        originales.map { giradoEnElEspacio(it, g.centro, delta, g.eje, scene.vista) }
                    }
                }
            }

            is Gesture.Arcoing -> {
                val e = scene.byId(g.elementId) ?: return
                // **El arco también se imanta.** Un arco se traza casi siempre
                // para llegar hasta algo —donde la guía corta a otra figura, un
                // vértice, el punto que ya marcaste—, y a pulso ese final queda
                // pasado o corto por unos grados que luego cantan. El enganche
                // se busca sobre el punto crudo y el ángulo se saca de ahí: da
                // igual que el anclaje no caiga exactamente sobre el óvalo,
                // porque lo que se usa es la dirección desde su centro.
                val destino = Iman.sitio(
                    scene, pRaw, zoom, Iman.Faena.AFINANDO, enganche, excluir = g.elementId
                )?.also { anclajeActivo = it }?.punto ?: p
                val angulo = anguloEnElOvalo(e, destino)
                replace(
                    e.copy(
                        arcSweep = barridoAcumulado(e.arcSweep ?: 0.0, g.anguloPrevio, angulo)
                    )
                )
                g.anguloPrevio = angulo
            }

            is Gesture.MovingPoint -> {
                val e = scene.byId(g.elementId) ?: return
                // El segundo dedo apoyado endereza el tramo respecto **al punto
                // vecino**, que es contra el que se está trazando: contra el
                // otro extremo, doblar el tramo del medio saldría torcido.
                val puntos = absolutePoints(e)
                val vecino = puntos.getOrNull(if (g.indice == 0) 1 else g.indice - 1)
                val destino = if (keepAspectRatio && vecino != null) constrainToAxis(vecino, p) else p
                replace(e.conPuntoEnElMundo(g.indice, destino).touched())
                // **Y con él, todo lo que tenga clavado.** Es lo que hace que un
                // triángulo hecho con tres rayas se deforme sin abrirse por el
                // vértice que estás moviendo. Ver [Alfiler].
                scene = scene.copy(
                    elements = arrastrarAlfiler(
                        scene.elements, scene.alfileres,
                        Agarre(g.elementId, indice = g.indice), destino
                    )
                ).let { escena ->
                    // **Y el clavo no se mueve.** Ver [fijarAlfileres]: tirando
                    // de una punta, la raya gira sobre el clavo en vez de
                    // llevárselo por delante.
                    val fijados = fijarAlfileres(
                        escena.elements, escena.alfileres,
                        movido = Agarre(g.elementId, indice = g.indice)
                    )
                    escena.copy(
                        elements = fijados,
                        alfileres = refrescarAlfileres(fijados, escena.alfileres)
                    )
                }
                // La forma bajo la punta se resalta: si se suelta ahí, se ancla.
                // Solo en los extremos: un doblez del medio no se ancla a nada.
                if (e.type == ElementType.ARROW && esExtremo(e, g.indice)) {
                    bindingHighlight = getHoveredElementForBinding(
                        scene.visible.filter { it.id != g.elementId }, destino, scene.viewport.zoom
                    )?.id
                }
            }

            // La letra da vueltas alrededor de su punto. No se aleja de él ni
            // se inclina: ver [conLaEtiquetaHacia].
            is Gesture.GirandoEtiqueta -> {
                scene = scene.copy(
                    elements = scene.elements.map {
                        if (it.id == g.elementId) conLaEtiquetaHacia(it, p) else it
                    }
                )
            }

            is Gesture.Moving -> {
                val dx = p.x - g.startPointer.x
                val dy = p.y - g.startPointer.y

                // **Un punto arrastrado se imanta igual que al ponerlo.**
                //
                // Le faltaba, y dejaba la herramienta a medias: nacía clavado en
                // la intersección y en cuanto había que recolocarlo —porque la
                // figura cambió, o porque cayó en el cruce de al lado— se movía
                // a pulso y ahí se quedaba, a dos píxeles del sitio. Un punto de
                // geometría fuera de su sitio no es un descuido estético: deja
                // de ser ese punto.
                //
                // Se imanta **el punto, no el dedo**. Al agarrarlo uno no acierta
                // en su centro exacto, y arrastrando el puntero imantado el punto
                // acabaría a esa misma distancia del anclaje: es el propio
                // destino del punto el que tiene que buscar sitio.
                val unPunto = g.originals.singleOrNull()?.takeIf { it.type == ElementType.PUNTO }
                if (unPunto != null) {
                    val destino = Pt(unPunto.x + dx, unPunto.y + dy)
                    val anclaje = Iman.sitio(
                        scene, destino, zoom, Iman.Faena.MOVIENDO, enganche,
                        excluir = unPunto.id,
                        elementos = editables.filter { it.id != unPunto.id }
                    )?.takeIf { sitioValidoParaPunto(it, editables) }
                    // Con el imán suelto se mueve libre, que es lo que hace un
                    // imán: tira cuando estás cerca y no ata cuando no lo estás.
                    // Es la diferencia con ponerlo, donde sin sitio no se pone
                    // nada — poner es decidir dónde va, y mover es corregirlo.
                    anclajeActivo = anclaje
                    val donde = anclaje?.punto ?: destino
                    applyToOriginals(g.originals) { originales ->
                        originales.map { it.copy(x = donde.x, y = donde.y) }
                    }
                    scene = scene.copy(
                        alfileres = refrescarAlfileres(scene.elements, scene.alfileres)
                    )
                    seguirConLasFlechas()
                    return
                }

                // **Cada figura se mueve según lo que sus clavos le dejen.**
                // Sin clavos sigue al dedo; con uno gira alrededor de él; con
                // dos no se mueve. Ver [Libertad]: es la ley entera del alfiler,
                // y es lo que convierte el clavo en un eje de verdad en vez de
                // en un pegamento que arrastra el conjunto en bloque.
                applyToOriginals(g.originals) {
                    if (scene.alfileres.isEmpty()) dragElements(it, dx, dy)
                    else arrastrarConAlfileres(it, scene.alfileres, g.startPointer, p)
                }

                // **Una caja arrimada a otra se apoya en ella.**
                //
                // Es el imán que hace que apilar sea un gesto y no puntería: en
                // isométrica, subir un volumen y alejarlo se ven exactamente igual, así
                // que dejar una caja *encima* de otra a ojo es imposible de acertar y de
                // comprobar. Enganchando a los vértices de las vecinas, arrimarla la deja
                // apoyada y con la cota puesta. Ver [imanEntreSolidos].
                val unSolido = g.originals.singleOrNull()?.takeIf { it.isSolido }
                if (unSolido != null) {
                    val movida = scene.byId(unSolido.id)
                    val apoyo = movida?.let {
                        imanEntreSolidos(
                            it, editables.filter { o -> o.id != it.id },
                            scene.vista, UMBRAL_GUIA / zoom.coerceAtLeast(0.0001)
                        )
                    }
                    apoyoActivo = apoyo?.punto
                    if (movida != null && apoyo != null) {
                        replace(movida.copy(x = apoyo.x, y = apoyo.y, cota = apoyo.cota))
                    }
                }
                scene = scene.copy(
                    alfileres = refrescarAlfileres(scene.elements, scene.alfileres)
                )
                recolocarTextosDentro()
                seguirConLasFlechas()
            }

            is Gesture.MoviendoElFoco -> {
                val lupa = scene.byId(g.elementId) ?: return
                replace(
                    conFoco(
                        lupa,
                        Pt(
                            g.focoInicial.x + (p.x - g.start.x),
                            g.focoInicial.y + (p.y - g.start.y)
                        )
                    )
                )
            }

            is Gesture.Resizing -> {
                applyToOriginals(g.originals) { originals ->
                    if (originals.size == 1) {
                        listOf(resizeSingleElement(originals[0], g.handle, p, keepAspectRatio, resizeFromCenter))
                    } else {
                        // Con el segundo dedo apoyado —o con las figuras
                        // perfectas puestas— manda la proporción; si no, la
                        // decide el tirador. Ver [resizeMultipleElements].
                        resizeMultipleElements(
                            originals, g.handle, p,
                            if (keepAspectRatio) true else null,
                            scene.vista
                        )
                    }
                }
                ajustarAltoDeTextos()
                recolocarTextosDentro()
                seguirConLasFlechas()
            }

            is Gesture.Rotating -> {
                applyToOriginals(g.originals) { originals ->
                    if (originals.size == 1) {
                        // Con un clavo, gira sobre él: eso es la articulación.
                        listOf(girarSobreAlfiler(originals[0], scene.alfileres, p, discreteAngle))
                    } else {
                        rotateMultipleElements(originals, p, discreteAngle)
                    }
                }
                seguirConLasFlechas()
            }
        }
    }

    /**
     * Recoloca las flechas ancladas a lo que se está moviendo.
     *
     * **En cada fotograma, no al soltar.** Antes solo se hacía en `pointerUp`, y
     * el efecto era que arrastrabas una caja y su flecha se quedaba clavada
     * donde estaba hasta que levantabas el dedo, momento en el que pegaba un
     * salto. Un esquema no se puede reorganizar así: mientras mueves necesitas
     * ver hacia dónde va quedando la conexión.
     *
     * Es barato: solo se recalculan las flechas cuyo anclaje apunta a algo que
     * ha cambiado, y su geometría son dos puntos.
     */
    private fun seguirConLasFlechas() {
        scene = scene.copy(elements = updateBoundElements(scene.elements, selectedIds))
    }

    /** El dedo se levanta: se cierra el gesto y se anota en el historial. */
    fun pointerUp(p: Pt, zoom: Double = 1.0) {
        anclajeActivo = null
        apoyoActivo = null
        olvidarElDedoParado()
        // La bolita se va con el dedo, pero **lo elegido se queda**: es lo que se acaba de
        // seleccionar, y perderlo al levantar sería lo contrario de lo que se pretendía.
        bolita = null
        yaTocados = emptySet()
        when (val g = gesture) {
            is Gesture.Tocando -> terminarToque(g.punto, p, zoom)

            is Gesture.Lassoing -> {
                selectedIds = getElementsWithinLasso(
                    editables, g.points, DEFAULT_HIT_THRESHOLD / zoom, scene.vista
                )
                    .map { it.id }.toSet()
                lassoPath = emptyList()
            }

            is Gesture.BoxSelecting -> selectionBox = null

            is Gesture.Creating -> finishCreating(g.elementId, zoom)

            // Se levanta el dedo de la huella: la caja queda **pendiente de
            // altura**, que es el estado que espera al segundo arrastre. Una
            // huella de nada es un toque y no un dibujo: se descarta entera, como
            // cualquier otra forma de tamaño cero.
            is Gesture.Huella -> {
                val e = scene.byId(g.elementId)
                if (e == null || (e.width < MIN_CREATED_SIZE && e.height < MIN_CREATED_SIZE)) {
                    scene = scene.copy(elements = scene.elements.filter { it.id != g.elementId })
                    solidoPendiente = null
                } else {
                    solidoPendiente = e.id
                }
            }

            // **Un toque la fija; un arrastre no.** Soltar después de levantar
            // deja la caja pendiente a propósito: acertar la altura de un vistazo
            // no sale a la primera, y así se puede corregir tantas veces como haga
            // falta sin volver a empezar. El toque es el «ya está», y es el mismo
            // gesto que cierra las demás herramientas de un toque.
            is Gesture.Levantando -> {
                val z = zoom.coerceAtLeast(0.0001)
                val quieto = kotlin.math.hypot(p.x - g.inicio.x, p.y - g.inicio.y) <=
                    TOQUE_QUIETO / z
                if (quieto) fijarSolido()
            }

            // Se levanta el lápiz del transportador: el arco queda hecho y el
            // óvalo guía deja de estar pendiente. Un barrido de nada es no haber
            // trazado: se descarta el elemento entero en vez de dejar un arco
            // invisible que estorba al picar.
            is Gesture.Arcoing -> {
                val e = scene.byId(g.elementId)
                if (e != null && kotlin.math.abs(e.arcSweep ?: 0.0) < MIN_ARCO_BARRIDO) {
                    scene = scene.copy(elements = scene.elements.filter { it.id != e.id })
                }
            }

            // Al soltar la punta se decide su anclaje, como al dibujarla: si
            // cae sobre una forma se engancha, y si se ha sacado de encima se
            // suelta. Sin esto, una flecha desenganchada seguía persiguiendo a
            // la caja de la que la acababas de separar.
            is Gesture.MovingPoint -> {
                val e = scene.byId(g.elementId)
                if (e != null) {
                    // Un doblez arrastrado encima de su vecino **se borra**: es
                    // la forma de deshacer un punto de más sin otro botón, y el
                    // gesto que sale solo al intentar «estirar» la línea otra
                    // vez. Los extremos no: sin ellos no hay línea.
                    val puntos = absolutePoints(e)
                    val propio = puntos.getOrNull(g.indice)
                    val pegadoAlVecino = !esExtremo(e, g.indice) && propio != null &&
                        listOfNotNull(puntos.getOrNull(g.indice - 1), puntos.getOrNull(g.indice + 1))
                            .any {
                                kotlin.math.hypot(it.x - propio.x, it.y - propio.y) <
                                    DEFAULT_HIT_THRESHOLD / zoom
                            }
                    if (pegadoAlVecino) {
                        replace(e.withPointRemoved(g.indice).touched())
                    } else if (e.type == ElementType.ARROW && esExtremo(e, g.indice)) {
                        // **Solo los extremos se anclan.** Un punto intermedio
                        // es un doblez del recorrido, no un destino: atarlo a
                        // una caja retorcería la línea al mover esa caja.
                        val extremo = if (g.indice == 0) ArrowEnd.START else ArrowEnd.END
                        val destino = propio?.let {
                            getHoveredElementForBinding(
                                scene.visible.filter { o -> o.id != e.id }, it, zoom
                            )
                        }
                        replace(
                            if (destino != null) bindArrow(e, destino, extremo)
                            else unbindArrow(e, extremo)
                        )
                    }
                }
            }

            // Las flechas ya se han ido recolocando en cada fotograma del
            // arrastre (`seguirConLasFlechas`), así que aquí no hay nada que
            // hacer. **Y no puede repetirse**: con los dos extremos atados, cada
            // pasada usa la dirección que dejó la anterior, así que volver a
            // recalcular al soltar movía la punta un par de píxeles más — el
            // saltito final que se veía justo al levantar el dedo.
            is Gesture.Moving, is Gesture.Resizing, is Gesture.Rotating,
            is Gesture.GirandoEtiqueta -> Unit

            else -> Unit
        }
        gesture = Gesture.None
        bindingHighlight = null
        commit()
    }

    /** Cancela el gesto en curso y deshace lo que llevaba hecho. */
    fun cancel() {
        if (gesture !is Gesture.None) scene = scene.copy(elements = sceneAtGestureStart)
        // La caja a medio hacer se va con el gesto: la escena vuelve a como
        // estaba, así que dejar apuntado un id que ya no existe haría que el
        // siguiente toque con el sólido intentara levantar un fantasma.
        if (gesture is Gesture.Huella || gesture is Gesture.Levantando) {
            solidoPendiente = null
        }
        gesture = Gesture.None
        // **Y el gesto del dedo parado también se va.** Sin esto, cancelar —que es lo que
        // hace el segundo dedo al ir a encuadrar— dejaba puesto el enderezado hasta el
        // siguiente lápiz: enderezar una raya, apoyar el segundo dedo y dibujar después un
        // rectángulo lo sacaba convertido en óvalo.
        olvidarElDedoParado()
        selectionBox = null
        lassoPath = emptyList()
        bindingHighlight = null
    }

    // ---------------------------------------------------------------------
    // Modificadores
    // ---------------------------------------------------------------------

    /** Conservar proporción al redimensionar y trazar recto al dibujar. */
    // ---------------------------------------------------------------------
    // El mando de lo elegido
    // ---------------------------------------------------------------------

    /**
     * Cómo estaba la escena cuando se agarró el mando.
     *
     * **Todo el arrastre entra en un solo deshacer.** Anotando cada pellizco del dedo, un
     * gesto de dos segundos deja doscientas entradas en el historial y deshacer una vez no
     * deshace nada: hay que darle doscientas para volver a donde se estaba. Se guarda el
     * antes al agarrar y se anota una sola vez al soltar.
     */
    private var antesDeManejar: List<Element>? = null

    /** Se agarra el mando: de aquí al soltar, todo es un solo cambio. */
    fun empezarAManejar() {
        if (antesDeManejar == null) antesDeManejar = scene.elements
    }

    /** Se suelta el mando: lo que haya pasado entre medias se anota de una vez. */
    fun terminarDeManejar() {
        val antes = antesDeManejar ?: return
        antesDeManejar = null
        if (antes !== scene.elements) anotar(antes)
    }

    /** Lo elegido, cambiado sin anotar: lo anota [terminarDeManejar] al soltar. */
    private fun manejar(transformar: (List<Element>) -> List<Element>) {
        val elegidos = scene.elements.filter { it.id in selectedIds && !it.locked }
        if (elegidos.isEmpty()) return
        val cambiados = transformar(elegidos).associateBy { it.id }
        scene = scene.copy(
            elements = updateBoundElements(
                scene.elements.map { cambiados[it.id] ?: it }, selectedIds
            )
        )
    }

    /**
     * Mueve lo elegido lo que se ha corrido el dedo. **En píxeles de pantalla.**
     *
     * Lo que se mueve el dedo es lo que se mueve el dibujo, y por eso hay que dividir por el
     * aumento: un mando que moviera unidades de la escena se arrastraría un palmo estando de
     * cerca y no se movería estando de lejos.
     */
    fun moverLaSeleccion(dx: Double, dy: Double, zoom: Double) {
        val z = zoom.coerceAtLeast(0.0001)
        manejar { dragElements(it, dx / z, dy / z) }
    }

    /** Lo mismo, pero **por un solo eje**: es lo que promete una flecha. */
    fun moverLaSeleccionPorElEje(enHorizontal: Boolean, cuanto: Double, zoom: Double) {
        if (enHorizontal) moverLaSeleccion(cuanto, 0.0, zoom)
        else moverLaSeleccion(0.0, cuanto, zoom)
    }

    /** Gira lo elegido un poco más, alrededor de su centro. */
    fun girarLaSeleccion(cuanto: Double) =
        manejar { giradasAlrededorDelCentro(it, cuanto) }

    /** Agranda o encoge lo elegido, sin cambiarle la proporción. */
    fun escalarLaSeleccion(razon: Double) =
        manejar { escaladasAlrededorDelCentro(it, razon, razon) }

    /** Estira lo elegido, **cada lado por su cuenta**. */
    fun deformarLaSeleccion(aLoAncho: Double, aLoAlto: Double) =
        manejar { escaladasAlrededorDelCentro(it, aLoAncho, aLoAlto) }

    /** Una copia de lo elegido, y la copia queda elegida. */
    fun copiarLaSeleccion() = duplicateSelection()

    // ---------------------------------------------------------------------
    // La bolita de seleccionar
    // ---------------------------------------------------------------------

    /**
     * Dónde está la bolita mientras se pasa por el dibujo, o nada si no hay nadie tocando.
     *
     * Lo lee el lienzo para pintarla: sin verla, uno no sabe cuánto coge el gesto y acaba
     * seleccionando de más. Ver [Tool.BOLITA].
     */
    var bolita: Pt? = null
        private set

    /**
     * Por lo que ya ha pasado la bolita **en este barrido**.
     *
     * Sin esto, un dedo que se para encima de una figura la enciende y la apaga sesenta
     * veces por segundo: lo que se ve es una figura parpadeando y una selección que depende
     * de en qué fotograma se levantó el dedo. Cada cosa se decide **una vez por barrido**, y
     * la lista se vacía al levantar.
     */
    private var yaTocados: Set<String> = emptySet()

    /**
     * La bolita, pasada por [p]: **enciende y apaga lo que toca**.
     *
     * El radio va en píxeles de pantalla y se pasa a unidades de la escena, porque es lo
     * que uno ve: en unidades del dibujo, la misma bolita cogería media escena estando
     * lejos y no cogería nada estando cerca.
     */
    private fun pasarLaBolita(p: Pt, zoom: Double) {
        bolita = p
        val radio = RADIO_DE_LA_BOLITA / zoom.coerceAtLeast(0.0001)
        var nueva = selectedIds
        for (e in getElementsAtPosition(editables, p, radio, scene.vista)) {
            if (e.id in yaTocados) continue
            // **Tocar uno de un grupo los coge a todos.** Para eso está el grupo: si hubiera
            // que pasar la bolita por las catorce piezas de un despiece cada vez, agruparlas
            // no habría servido de nada.
            val suyos = getElementsInGroupOf(editables, e).ifEmpty { listOf(e) }
            val ids = suyos.mapTo(HashSet()) { it.id }
            yaTocados = yaTocados + ids
            nueva = if (e.id in nueva) nueva - ids else nueva + ids
        }
        if (nueva != selectedIds) selectedIds = nueva
    }

    // ---------------------------------------------------------------------
    // El dedo parado: la recta y el compás
    // ---------------------------------------------------------------------

    /**
     * **Se para y sale recta; se clava y sale redonda.**
     *
     * Los dos gestos que tiene el croquis en el espacio, aquí también. Son los que hacen
     * que un lienzo se pueda usar a pulso: a mano alzada, sobre un cristal y con el brazo
     * en el aire, una raya recta no sale ni queriendo y un círculo sale patata. La salida
     * de siempre —un interruptor de «figuras perfectas»— obliga a decidir **antes** de
     * empezar, y uno se entera de que quería una raya justo cuando la está trazando.
     *
     * Parándose se decide **durante**: se traza, se ve que iba a ser una raya, y se para.
     * A partir de ahí el trazo es la punta y el dedo, y sigue siéndolo hasta levantarlo:
     * se puede estirar hasta donde se quiera sin volver a empezar.
     *
     * Y parándose **sin haber ido a ningún sitio** no hay nada que enderezar: ahí está el
     * centro. Es el compás de toda la vida —se clava la punta y se abre—, y evita lo que
     * no funciona nunca, que es reconocer un lazo dibujado a pulso: dibujar la vuelta
     * entera es justo lo que uno no quiere hacer, y el reconocimiento acierta unas veces
     * sí y otras no, así que el gesto no se puede aprender.
     *
     * Ver [latido], que es quien los dispara.
     */
    var enderezandoSolo: Boolean = false
        private set

    /** Si el trazo en curso se ha convertido en compás. Ver [enderezandoSolo]. */
    var redondeandoSolo: Boolean = false
        private set

    /** Dónde se clavó el compás, en coordenadas de la escena. */
    private var centroDelCompas: Pt? = null

    /** Dónde arrancó el trazo que se está enderezando. */
    private var arranqueDelTrazo: Pt? = null

    /** Desde cuándo el dedo no se mueve, y desde dónde —en pantalla—. */
    private var quietoDesde = 0L
    private var quietoEn: Pt? = null

    /** Se olvida todo lo del gesto: al bajar el dedo y al levantarlo. */
    private fun olvidarElDedoParado() {
        enderezandoSolo = false
        redondeandoSolo = false
        centroDelCompas = null
        arranqueDelTrazo = null
        quietoEn = null
    }

    /**
     * **Le llega la hora al dedo apoyado**, fotograma a fotograma mientras dura el trazo.
     *
     * No se mide dentro del movimiento del dedo porque un dedo quieto de verdad **no manda
     * movimientos**: el gesto no salía justo cuando se hacía bien. Devuelve si algo ha
     * cambiado, para que el lienzo repinte solo entonces y no sesenta veces por segundo
     * porque sí. Ver [DrawCanvas].
     */
    fun latido(cuando: Long): Boolean {
        if (enderezandoSolo || redondeandoSolo) return false
        val g = gesture as? Gesture.Creating ?: return false
        val e = scene.byId(g.elementId)?.takeIf { it.isFreeDraw } ?: return false
        if (quietoEn == null || cuando - quietoDesde < ESPERA_PARA_LA_RECTA) return false
        return pararseYQueSalgaLimpio(e)
    }

    /**
     * El dedo se ha parado: lo trazado se pone limpio. Recta o compás, según lo que lleve.
     *
     * Se hace **ya**, sin esperar a que el dedo se vuelva a mover: si no, la raya seguiría
     * torcida a la vista mientras el dedo aguanta, que es cuando se está mirando.
     */
    private fun pararseYQueSalgaLimpio(e: Element): Boolean {
        val puntos = absolutePoints(e)
        val arranque = puntos.firstOrNull() ?: return false
        val dedo = puntos.lastOrNull() ?: return false
        val z = zoomDelTrazo.coerceAtLeast(0.0001)
        // **Parado sin haber ido a ningún sitio: eso es un compás.** Se mide en pantalla y
        // no en unidades de la escena, porque lo que decide si uno ha querido dibujar o ha
        // querido clavar la punta es cuánto se le ha movido el dedo — a cualquier aumento.
        val esUnPunto = puntos.all {
            kotlin.math.hypot(it.x - arranque.x, it.y - arranque.y) * z <= LO_QUE_ES_UN_TOQUE
        }
        // **Y al saltar el gesto, las dos puntas buscan sitio.**
        //
        // Lo trazado nació a mano alzada, así que sus puntos solo se habían pegado al canto de
        // las guías. Desde este instante ya no es un trazo de lápiz: el arranque de la raya y
        // el centro del compás son los de una figura y se enganchan como los de una figura.
        // Sin esto, la punta sí se imantaba —ver [faenaDeAhora]— pero el origen se quedaba a
        // dos píxeles del vértice del que uno creía estar saliendo. Es lo mismo que hace el
        // croquis en el espacio al enderezar.
        val enSuSitio = imantado(arranque, Iman.Faena.TRAZANDO, excluir = e.id)
        val puntaEnSuSitio = imantado(dedo, Iman.Faena.TRAZANDO, excluir = e.id)
        arranqueDelTrazo = enSuSitio
        if (esUnPunto) {
            centroDelCompas = enSuSitio
            redondeandoSolo = true
            replace(comoUnOvalo(e, enSuSitio, puntaEnSuSitio))
        } else {
            enderezandoSolo = true
            replace(comoUnaRaya(e, enSuSitio, puntaEnSuSitio))
        }
        return true
    }

    /**
     * **Y lo que sale es una raya de verdad, no un trazo a mano con forma de raya.**
     *
     * Salía un elemento de lápiz con dos puntos, y eso se parece a una raya sin serlo: no
     * admite el estilo de línea —a trazos, de puntos—, no se puede agarrar por sus dos
     * extremos para recolocarla, no engancha una flecha, se pinta con la mancha del lápiz
     * en vez de con el trazo de una figura, y **sale de otro grosor**: el lápiz mide en su
     * propia escala, la mitad que las figuras (ver [ItemStyle.freedrawWidthFor]). Es decir,
     * la raya del gesto y la de la herramienta de línea eran dos cosas distintas con la
     * misma pinta.
     *
     * Ahora es **exactamente** lo que deja la herramienta de línea, y por eso se le pone
     * también su grosor: el del estilo, no el del lápiz.
     */
    private fun comoUnaRaya(e: Element, arranque: Pt, dedo: Pt): Element =
        e.copy(
            type = ElementType.LINE,
            pressures = null,
            strokeWidth = scene.style.strokeWidth,
            roundness = null
        ).conPuntosAbsolutos(listOf(arranque, dedo))

    /**
     * **Y la rueda es un óvalo de verdad**, no un polígono de cuarenta y ocho lados.
     *
     * Por lo mismo que la raya: un polígono se ve redondo y no lo es. No se puede rellenar
     * como un óvalo, no se le puede cambiar la proporción tirando de su caja, sale de la
     * mitad de gordo que el de la herramienta y en cuanto se amplía se le ven los lados.
     * Aquí sale la misma figura que deja la herramienta de círculo: clavada en su centro
     * —que es donde se clavó la punta del compás— y abierta hasta donde va el dedo.
     */
    private fun comoUnOvalo(e: Element, centro: Pt, dedo: Pt): Element {
        val radio = kotlin.math.hypot(dedo.x - centro.x, dedo.y - centro.y)
        if (radio < 1e-9) return e
        return e.copy(
            type = ElementType.ELLIPSE,
            points = null,
            pressures = null,
            x = centro.x - radio,
            y = centro.y - radio,
            width = radio * 2,
            height = radio * 2,
            strokeWidth = scene.style.strokeWidth,
            // Lo que se rellena y con qué trama lo dice el estilo, como en cualquier
            // figura: un trazo de lápiz no llevaba fondo porque no puede tenerlo.
            backgroundColor = scene.style.backgroundColor,
            fillStyle = scene.style.fillStyle,
            roundness = null
        )
    }

    /** El aumento del último toque, para medir en pantalla lo que llega en escena. */
    private var zoomDelTrazo = 1.0

    var keepAspectRatio: Boolean = false

    /**
     * ¿Hay una forma naciendo del dedo ahora mismo?
     *
     * Lo pregunta el lienzo para saber qué significa el segundo dedo: con algo
     * naciendo significa **figura perfecta**, y sin nada, encuadrar.
     */
    val dibujando: Boolean
        get() = gesture is Gesture.Creating || gesture is Gesture.Arcoing

    /** Redimensionar desde el centro en vez de desde la esquina opuesta. */
    var resizeFromCenter: Boolean = false

    /** Enganchar la rotación a múltiplos de 15°. */
    var discreteAngle: Boolean = false

    /**
     * Qué puntos notables enganchan al dibujar. Ver [AjustesEnganche].
     *
     * Es estado del editor, no del dibujo: cambiarlo no toca lo dibujado, solo
     * cómo se coloca lo siguiente.
     */
    var enganche: AjustesEnganche = AjustesEnganche()

    /** El punto al que se está enganchando ahora, para que la vista lo señale. */
    var anclajeActivo: Anclaje? = null

    /**
     * **Qué faena es esta, decidido en un solo sitio.**
     *
     * Estaba escrita dos veces —una al bajar el dedo y otra al moverlo— y las dos listas se
     * separaron solas, que es lo que hacen siempre dos copias de una decisión: la de mover no
     * conocía el punto etiquetado, y **ninguna de las dos conocía el atajo del dedo parado**.
     * Eso se notaba: se trazaba con el lápiz, se mantenía pulsado, salía la recta… y esa recta
     * seguía imantando como un garabato —solo al canto de las guías— en vez de como la raya
     * que ya es. Ver [Iman.Faena].
     */
    private fun faenaDeAhora(
        /** Si hay algo en la mano a lo que aplicarle el imán. */
        hayAlgoEnLaMano: Boolean,
        /** Si lo agarrado es un punto de algo ya dibujado y no una figura naciendo. */
        moviendoPunto: Boolean = false
    ): Iman.Faena? = when {
        !hayAlgoEnLaMano -> null

        // **El atajo imanta como lo que ha salido, no como lo que se estaba haciendo.**
        // Desde que el gesto salta, lo que hay ya no es un trazo de lápiz sino una raya o un
        // óvalo de verdad —ver [comoUnaRaya] y [comoUnOvalo]—, y una raya se traza casi
        // siempre para que acabe donde acaba otra cosa. El motivo por el que el lápiz engancha
        // poco tampoco vale aquí: lo que rompe un garabato es saltar a un vértice **en mitad
        // del recorrido**, y en la raya del gesto no hay recorrido, hay dos puntos. Es lo que
        // el croquis en el espacio hace desde el primer día.
        enderezandoSolo || redondeandoSolo -> Iman.Faena.TRAZANDO

        moviendoPunto -> Iman.Faena.AFINANDO
        tool == Tool.PUNTO -> Iman.Faena.SITIO_NOTABLE
        tool.isFreehand -> Iman.Faena.A_MANO
        tool.isShape || tool.isLinear -> Iman.Faena.TRAZANDO
        else -> null
    }

    /**
     * **La única puerta del imán dentro del controlador.**
     *
     * Deja apuntado a qué se ha enganchado —[anclajeActivo], que es lo que la vista señala— y
     * devuelve el punto crudo si no había nada cerca: es lo que hace un imán, tirar cuando
     * estás cerca y no atar cuando no lo estás.
     */
    private fun imantado(
        p: Pt,
        faena: Iman.Faena?,
        zoom: Double = zoomDelTrazo,
        excluir: String? = null
    ): Pt {
        if (faena == null) {
            anclajeActivo = null
            return p
        }
        anclajeActivo = Iman.sitio(scene, p, zoom, faena, enganche, excluir)
        return anclajeActivo?.punto ?: p
    }

    /**
     * La figura que espera eje, con el torno en la mano.
     *
     * Es la mitad de un gesto de dos tiempos: se ha dicho **qué** se tornea y falta decir
     * **alrededor de qué**. Se señala en pantalla mientras espera, porque un gesto a
     * medias sin marca es una aplicación que no responde. Ver [Tool.REVOLUCION].
     */
    var figuraATornear: String? = null
        private set

    /**
     * El vértice de otra caja al que se está apoyando la que se mueve, para señalarlo.
     *
     * Va aparte de [anclajeActivo] porque no es lo mismo: aquel es el imán de la
     * geometría plana —cruces, medios, vértices de figuras— y este es el de los volúmenes,
     * que engancha en tres dimensiones y además cambia la cota. Ver [imanEntreSolidos].
     */
    var apoyoActivo: Pt? = null
        private set

    /**
     * Si al trazar una cota se abre el teclado para dictarle su medida.
     *
     * Las dos formas de acotar son buenas y son distintas. Dictándola, la raya
     * **acaba midiendo lo que uno dice**: es la de levantar un plano, donde las
     * medidas se saben y el dibujo tiene que obedecerlas. Sin dictar, la cota
     * mide lo que hay: es la de medir sobre una foto, donde la respuesta es
     * justo lo que no se sabe. Obligar a una de las dos convierte la otra en un
     * estorbo, así que es un interruptor y no una decisión.
     */
    var pedirLaMedida: Boolean = true

    /** Con cuánto detalle busca el bote el hueco que se toca. Ver [AjustesRelleno]. */
    var ajustesRelleno: AjustesRelleno = AjustesRelleno()

    /**
     * El último botellazo no encontró un espacio cerrado.
     *
     * Va aquí y no en un `Toast` porque el controlador no sabe pintar nada, y
     * porque **es la respuesta de la herramienta**, no un error: la interfaz lo
     * lee al refrescar y avisa, igual que hace con [pendingTextId]. Sin aviso, el
     * bote parecería roto justo cuando está funcionando bien.
     */
    var rellenoSinCerrar: Boolean = false
        private set

    fun limpiarAvisoRelleno() {
        rellenoSinCerrar = false
    }

    /**
     * El círculo crece **desde el centro**.
     *
     * En un ratón se dibuja de esquina a esquina y con Alt desde el centro; con
     * el dedo no hay Alt, y para un círculo lo natural es poner el dedo donde
     * quieres el centro y abrir — nadie piensa en la esquina de la caja de un
     * círculo, porque no se ve.
     *
     * Va **solo para la elipse** a propósito. Un rectángulo sí se piensa por sus
     * esquinas, y ponerlo también desde el centro cambiaba el gesto de media
     * herramienta sin que nadie lo hubiera pedido.
     */
    var ellipseFromCenter: Boolean = true

    /** Lo mismo, pero para todas las formas con caja. Apagado por defecto. */
    var shapesFromCenter: Boolean = false

    /**
     * Lo que se dibuje ahora es **referencia**, no dibujo.
     *
     * Es un interruptor y no una lista de herramientas paralela: las de
     * referencia tienen que ser exactamente las mismas y en la misma cantidad
     * que las normales, y duplicarlas obligaría a mantener dos listas que se
     * separarían a la primera herramienta nueva.
     */
    var modoReferencia: Boolean = false
        set(valor) {
            if (field == valor) return
            field = valor
            // Lo que estuviera seleccionado deja de ser tocable al cambiar de
            // mundo: los tiradores se quedarían encima de algo que ya no
            // responde, que es peor que no verlos.
            selectedIds = emptySet()
        }

    /**
     * ¿Sobre esto se puede actuar ahora mismo?
     *
     * **El modo guía decide sobre qué mundo se trabaja**, y esa es toda la
     * regla: dentro se selecciona, se mueve, se estira y se borra el andamio;
     * fuera, el dibujo — y **ninguna guía se puede borrar por accidente**.
     *
     * Lo que arreglaba: el andamio está para pasarle el lápiz por encima, así
     * que el borrador y el dedo pasan por él todo el rato. Sin separar los dos
     * mundos, media faena consistía en volver a trazar guías que uno mismo se
     * había llevado por delante sin enterarse.
     *
     * Dibujar no entra aquí: trazar nunca destruye nada, y el interruptor ya
     * decide de qué clase sale lo que se traza.
     */
    private fun editable(e: Element): Boolean = e.reference == modoReferencia

    /** Lo que se ve **y** se puede tocar ahora mismo. */
    private val editables: List<Element> get() = scene.visibleConReferencias.filter(::editable)


    /** Id del texto recién creado que la interfaz debe abrir para escribir. */
    var pendingTextId: String? = null
        private set

    fun clearPendingText() {
        pendingTextId = null
    }

    // ---------------------------------------------------------------------
    // Medir
    // ---------------------------------------------------------------------

    /**
     * La cota recién trazada con la herramienta de escalar, a la espera de que
     * alguien diga cuánto mide de verdad.
     *
     * El controlador no pregunta nada: no sabe pintar un diálogo ni tiene por
     * qué. Deja esto puesto y quien lleve la interfaz —el editor a pantalla
     * completa o la barrita del pin— lo ve al refrescar y abre el teclado. Es
     * el mismo trato que [pendingTextId].
     */
    var pendingScaleId: String? = null
        private set

    /** La cota que espera medida, si sigue viva. */
    fun pendingScaleElement(): Element? = pendingScaleId?.let { scene.byId(it) }

    /**
     * La cota recién trazada que está pidiendo cuánto mide y hacia dónde va.
     *
     * Igual que [pendingScaleId], y por lo mismo: el controlador no sabe pintar
     * un teclado, así que lo deja puesto y quien lleve la interfaz lo abre.
     */
    var pendingCotaId: String? = null
        private set

    fun pendingCotaElement(): Element? = pendingCotaId?.let { scene.byId(it) }

    /** Se acepta lo tecleado: la cota se rehace con ese largo y ese ángulo. */
    fun aplicarCota(largo: Double, grados: Double) {
        val id = pendingCotaId ?: return
        pendingCotaId = null
        mutate { elementos ->
            elementos.map {
                if (it.id == id) {
                    conLargoYAngulo(it, largoEnPixeles(largo, scene.escala), grados)
                } else it
            }
        }
    }

    /** Se desiste: la cota se queda como se trazó, que ya dice lo que mide. */
    fun cancelarCota() {
        pendingCotaId = null
    }

    /**
     * Calibra con la medida que se acaba de dictar y devuelve si valió.
     *
     * Al calibrar se pasa a medir: nadie escala por escalar, se escala para
     * poder acotar, y obligar a volver a la barra a por la cota sería un paso
     * de más justo en el momento en que ya se sabe lo que se quiere hacer.
     */
    fun applyScale(
        medidaReal: Double,
        unidad: String = Escala.METRO,
        decimales: Int = 2
    ): Boolean {
        val cota = pendingScaleElement() ?: return false
        val escala = Escala.calibrar(longitudDe(cota), medidaReal, unidad, decimales)
            ?: return false
        scene = scene.copy(escala = escala)
        pendingScaleId = null
        selectTool(Tool.MEASURE)
        commit()
        return true
    }

    /**
     * Se desiste de calibrar: la raya se va con ello.
     *
     * No se deja como cota suelta a propósito. Se trazó para decir «esto mide
     * tanto» y quedó sin decirlo; dejarla pondría en el dibujo una medida que
     * nadie ha confirmado, que es el error que más caro sale de todos.
     */
    fun cancelScale() {
        val id = pendingScaleId ?: return
        pendingScaleId = null
        scene = scene.copy(elements = scene.elements.filter { it.id != id })
        commit()
    }

    // ---------------------------------------------------------------------
    // Tablas de coordenadas
    // ---------------------------------------------------------------------

    /**
     * Añade una tabla con su origen donde se diga y un color libre.
     *
     * El origen entra por parámetro y no se pone en (0,0) porque el (0,0) de la
     * escena está donde está —a saber dónde, en un lienzo infinito— y lo que
     * quiere quien mete coordenadas es que su origen caiga donde está mirando.
     */
    fun addTabla(origenSiNoHay: Pt, nombre: String = ""): TablaDeCoordenadas {
        val tabla = TablaDeCoordenadas(
            id = randomId(),
            nombre = nombre,
            color = colorLibreDeTabla(scene.tablas.map { it.color })
        )
        scene = scene.copy(
            tablas = scene.tablas + tabla,
            // El eje se coloca **la primera vez y ya**: moverlo después
            // desplazaría todos los puntos ya tecleados, que es lo último que
            // espera quien solo quería empezar otra serie.
            origenCoordenadas = scene.origenCoordenadas ?: origenSiNoHay
        )
        return tabla
    }

    /** Recoloca el eje. Todo lo tecleado se mueve con él, que es la gracia. */
    fun setOrigenCoordenadas(p: Pt) {
        scene = scene.copy(origenCoordenadas = p)
    }

    /**
     * La tabla de ese color, creándola si no había ninguna.
     *
     * **Un color, una serie.** Es lo que hace que mirar un punto en el dibujo
     * baste para saber de qué tabla salió, y por eso el selector de color del
     * editor cambia de tabla en vez de repintar la que estás mirando: dos
     * series del mismo color serían indistinguibles justo donde importa.
     */
    fun tablaDeColor(color: String, origenSiNoHay: Pt): TablaDeCoordenadas {
        scene.tablas.firstOrNull { it.color.equals(color, ignoreCase = true) }?.let { return it }
        val nueva = TablaDeCoordenadas(id = randomId(), color = color)
        scene = scene.copy(
            tablas = scene.tablas + nueva,
            origenCoordenadas = scene.origenCoordenadas ?: origenSiNoHay
        )
        return nueva
    }

    /** Reemplaza una tabla entera: es lo que hace aceptar el editor. */
    fun updateTabla(tabla: TablaDeCoordenadas) {
        scene = scene.copy(
            tablas = scene.tablas.map { if (it.id == tabla.id) tabla else it }
        )
    }

    fun removeTabla(id: String) {
        scene = scene.copy(tablas = scene.tablas.filter { it.id != id })
    }

    /** Quita la calibración: las cotas vuelven a rotularse en píxeles. */
    fun clearScale() {
        if (scene.escala == null) return
        scene = scene.copy(escala = null)
        commit()
    }

    // ---------------------------------------------------------------------
    // Creación
    // ---------------------------------------------------------------------

    /**
     * Dónde se tocó para crear la forma en curso.
     *
     * **Tiene que guardarse aparte.** Es el ancla del gesto, y el elemento no
     * sirve para recordarla: su `x`/`y` cambian en cada fotograma según hacia
     * dónde vaya el dedo.
     */
    private var origenCreacion: Pt = Pt(0.0, 0.0)

    /** ¿La forma en curso crece desde el centro? */
    private var desdeElCentro: Boolean = false

    /**
     * Saca una lupa de una figura ya dibujada, con su misma forma.
     *
     * Nace **encima de ella y marcada**: así se ve al momento que ha aparecido
     * un duplicado —lo primero que se hace es apartarlo—, y con la flecha puesta
     * el arrastre siguiente ya lo mueve. La figura de origen no se toca; queda
     * marcando el sitio, que es lo que le da sentido a la lupa.
     */
    private fun deLaFigura(fuente: Element, herramienta: Tool) {
        val estilo = estiloActivo()
        val id = randomId()
        val semilla = (0..999_999).random()
        val nueva = if (herramienta == Tool.SPOTLIGHT) {
            focoDesdeFigura(fuente, estilo, id, semilla)
        } else {
            lupaDesdeFigura(fuente, estilo, id, semilla)
        } ?: return
        val before = scene.elements
        // **La figura de origen se va con ella.** Se ha convertido, no
        // duplicado: dejándola puesta quedaban dos contornos idénticos uno
        // encima de otro, y al apartar la lupa el de abajo parecía un dibujo
        // suelto que nadie había hecho. Lo que marcaba el sitio ahora es la
        // propia zona mirada, que sale al tocar la lupa.
        //
        // Se borra marcándola, no quitándola: así deshacer la devuelve **a su
        // sitio** en el montón y no al final.
        scene = scene.copy(
            elements = scene.elements.map {
                if (it.id == fuente.id) it.copy(isDeleted = true).touched() else it
            } + nueva
        )
        selectTool(Tool.SELECTION)
        selectedIds = setOf(nueva.id)
        anotar(before)
    }

    // ---------------------------------------------------------------------
    // La caja en volumen: un gesto en dos fases
    // ---------------------------------------------------------------------

    /**
     * La caja que está esperando a que se le dé altura, si hay alguna.
     *
     * Es el estado que separa las dos fases del gesto, y va público porque la
     * interfaz lo necesita para decir «ahora levántala»: sin un aviso, quien
     * suelta el dedo después de dibujar la huella cree que la herramienta ha
     * fallado —ve una plancha en el suelo y nada más— y vuelve a arrastrar sin
     * saber que eso ya es la segunda fase.
     */
    var solidoPendiente: String? = null
        private set

    /**
     * Empieza —o continúa— la caja en volumen.
     *
     * ## Por qué el gesto va en dos fases, y no en una
     *
     * **Es la decisión de diseño de toda la herramienta, y no es una comodidad:
     * es que en una fase no se puede.** En isométrica, arrastrar el dedo en
     * vertical puro hacia arriba tiene dos lecturas que dan **exactamente el
     * mismo movimiento en pantalla**: subir en `z`, o avanzar en diagonal por el
     * suelo hacia `(k, k, 0)`. Las dos suben el punto los mismos píxeles y
     * ninguna lo mueve de lado. No hay forma de saber cuál quería el usuario, y
     * adivinar mal es peor que no adivinar — una caja que se levanta cuando
     * querías moverla es un fallo que hay que deshacer, y pasa una de cada dos
     * veces justo porque nadie arrastra perfectamente en diagonal. El porqué
     * geométrico está entero en [desproyectar].
     *
     * Partiéndolo, la ambigüedad **no puede aparecer**:
     *
     * 1. El primer arrastre dibuja la huella **sobre el suelo**, imantada a la
     *    retícula isométrica. Todo lo que hace el dedo se interpreta como suelo,
     *    así que no hay nada que confundir.
     * 2. Se suelta, y el siguiente arrastre **solo lee el desplazamiento vertical
     *    de la pantalla** y con él levanta la caja. Lo que se mueva de lado se
     *    ignora del todo — no se descarta por pequeño ni se reparte entre los dos
     *    ejes: sencillamente no se mira. Al no leerse el eje horizontal, la
     *    lectura «es una diagonal por el suelo» no existe.
     *
     * Un toque la fija; se puede seguir arrastrando tantas veces como haga falta
     * hasta que quede a la altura que se quería.
     */
    private fun empezarSolido(p: Pt) {
        val pendiente = solidoPendiente?.let { scene.byId(it) }
        if (pendiente != null && !pendiente.isDeleted) {
            gesture = Gesture.Levantando(pendiente.id, p, pendiente.altura ?: 0.0)
            return
        }
        // La altura nace **nula y no cero**: es el estado real «todavía no se ha
        // levantado», y lo que hace que mientras se dibuja la huella se pinte solo
        // la sombra y la planta. Ver [Element.altura].
        val e = newElement(ElementType.SOLIDO, p.x, p.y, scene.style)
            .copy(reference = modoReferencia, altura = null)
        scene = scene.copy(elements = scene.elements + e)
        solidoPendiente = null
        gesture = Gesture.Huella(e.id, p)
    }

    /**
     * Da por buena la caja pendiente y cierra el gesto.
     *
     * Una caja que se fija sin haberse levantado nunca se queda con **un cuadro
     * de alto** en vez de con cero: un sólido de altura nula es una plancha, y
     * como se pinta con el mismo tono que la tapa no se distingue de un
     * rectángulo cualquiera. Quien toca la herramienta del volumen quiere volumen;
     * si de verdad quería una plancha, tiene el rectángulo.
     */
    fun fijarSolido() {
        val e = solidoPendiente?.let { scene.byId(it) }
        solidoPendiente = null
        if (e == null || e.isDeleted) return
        if ((e.altura ?: 0.0) > 0.0) return

        val before = scene.elements
        replace(e.copy(altura = PASO_ISO).touched())
        // **Solo se anota si no hay gesto en marcha.** Con el dedo todavía
        // apoyado —el toque que fija la altura— quien anota es el `commit` del
        // final de `pointerUp`, y hacerlo también aquí metería dos entradas en el
        // historial para un solo gesto: habría que deshacer dos veces para
        // quitar una caja.
        if (gesture is Gesture.None) anotar(before)
    }

    private fun beginCreate(type: ElementType, p: Pt) {
        origenCreacion = p
        // La hoja siempre de esquina a esquina: se coloca mirando dónde caen
        // sus bordes, no dónde queda su centro.
        desdeElCentro = type != ElementType.FRAME &&
            (shapesFromCenter || (ellipseFromCenter && type == ElementType.ELLIPSE))
        val e = newElement(type, p.x, p.y, scene.style).copy(reference = modoReferencia)
        scene = scene.copy(elements = scene.elements + e)
        gesture = Gesture.Creating(e.id)
    }

    private fun beginCreateLinear(type: ElementType, p: Pt) {
        val e = newElement(type, p.x, p.y, scene.style)
            .copy(points = listOf(Pt(0.0, 0.0), Pt(0.0, 0.0)), reference = modoReferencia)
        scene = scene.copy(elements = scene.elements + e)
        gesture = Gesture.Creating(e.id)
    }

    private fun updateCreating(id: String, p: Pt, pressure: Double) {
        val e = scene.byId(id) ?: return
        val updated = when {
            // **Clavado el compás, el dedo abre la rueda**; enderezado, el trazo es la
            // punta y el dedo. En los dos casos se **sustituye** lo trazado en vez de
            // acumular: añadiendo puntos y limpiando solo al soltar, el rastro torcido se
            // queda a la vista y la figura buena solo aparece al final, que es tarde.
            // Y sin mirar de qué tipo es: desde que el gesto salta, lo que hay ya no es un
            // trazo de lápiz sino un óvalo o una raya. Ver [comoUnOvalo] y [comoUnaRaya].
            // La flecha libre no se endereza ni se redondea sola: **es libre**, y lo que se
            // traza a pulso para rodear algo no puede convertirse en una recta al pararse
            // la mano. Va antes que los dos gestos, que es lo que los desactiva.
            tool == Tool.FLECHA_LIBRE -> e.withPoint(p)

            redondeandoSolo -> centroDelCompas?.let { comoUnOvalo(e, it, p) } ?: e

            enderezandoSolo -> arranqueDelTrazo?.let { comoUnaRaya(e, it, p) } ?: e

            e.isFreeDraw -> e.withPoint(p, pressure)

            e.isLinear -> {
                val end = if (keepAspectRatio) constrainToAxis(Pt(e.x, e.y), p) else p
                val moved = e.withLastPointAt(end)
                // La forma bajo la punta se resalta para avisar de que, si se
                // suelta ahí, la flecha se va a quedar enganchada.
                if (e.type == ElementType.ARROW) {
                    bindingHighlight = getHoveredElementForBinding(
                        scene.visible.filter { it.id != id }, end, scene.viewport.zoom
                    )?.id
                }
                moved
            }

            // **El óvalo se agarra por su borde, no por la esquina de su caja.**
            //
            // Antes el dedo acababa en la esquina de una caja que no se ve, así
            // que el trazo quedaba lejos de la yema —a la diagonal, un 41% más
            // afuera— y había que dibujar a ojo compensando esa distancia. Peor
            // todavía: el imán engancha el punto del dedo, así que enganchar el
            // óvalo a una esquina o a un cruce era imposible; lo que se pegaba
            // ahí era el vértice de una caja invisible, y la circunferencia
            // pasaba por otro sitio.
            //
            // Ahora la circunferencia **pasa siempre por el dedo**. El punto por
            // el que se agarra se va corriendo por el borde según hacia dónde
            // tires, que es lo que hace la mano con un compás.
            e.type == ElementType.ELLIPSE && desdeElCentro -> {
                val dx = p.x - origenCreacion.x
                val dy = p.y - origenCreacion.y
                val (sw, sh) = if (keepAspectRatio) {
                    // Círculo perfecto: el radio **es** lo que hay hasta el dedo.
                    kotlin.math.hypot(dx, dy).let { it to it }
                } else {
                    // Óvalo libre: por un punto pasan infinitas elipses, así que
                    // hace falta una regla. Se conserva la proporción que tenía
                    // agarrándolo por la esquina —ancho contra alto, tal cual se
                    // arrastra— y se agranda lo justo para que el borde alcance
                    // el dedo. El √2 sale de ahí: con semiejes |dx| y |dy| el
                    // dedo se queda a la mitad de camino, porque
                    // (dx/a)² + (dy/b)² vale 2 y tiene que valer 1.
                    (kotlin.math.abs(dx) * RAIZ_DE_DOS) to (kotlin.math.abs(dy) * RAIZ_DE_DOS)
                }
                e.copy(
                    x = origenCreacion.x - sw, y = origenCreacion.y - sh,
                    width = sw * 2, height = sh * 2
                )
            }

            // **Desde el centro**: el punto que tocaste es el centro y la forma
            // crece a su alrededor. Es lo natural con el dedo para un círculo —
            // pones el dedo donde quieres el centro y abres— y `shapesFromCenter`
            // lo apaga para quien prefiera la esquina.
            desdeElCentro -> {
                val semiW = kotlin.math.abs(p.x - origenCreacion.x)
                val semiH = kotlin.math.abs(p.y - origenCreacion.y)
                val (sw, sh) = if (keepAspectRatio) {
                    maxOf(semiW, semiH).let { it to it }
                } else semiW to semiH
                e.copy(
                    x = origenCreacion.x - sw, y = origenCreacion.y - sh,
                    width = sw * 2, height = sh * 2
                )
            }

            // **El rombo nace de un vértice.** El punto que tocas es su punta de
            // arriba —no la esquina de una caja invisible, donde no hay nada
            // dibujado— y el dedo abre la figura hacia abajo. Es lo que la mano
            // espera de una forma cuyos vértices son lo único que se ve.
            e.type == ElementType.DIAMOND -> {
                val semiW = kotlin.math.abs(p.x - origenCreacion.x)
                val alto = kotlin.math.abs(p.y - origenCreacion.y)
                val (sw, h) = if (keepAspectRatio) {
                    maxOf(semiW, alto / 2).let { it to it * 2 }
                } else semiW to alto
                e.copy(
                    x = origenCreacion.x - sw,
                    y = minOf(origenCreacion.y, p.y),
                    width = sw * 2,
                    height = h
                )
            }

            else -> {
                // **El ancla es el punto que se tocó, no `e.x`.** Parece lo
                // mismo y no lo es: `e` se relee de la escena en cada fotograma,
                // así que en cuanto arrastrabas hacia la izquierda su `x` pasaba
                // a ser la del dedo y el origen se perdía. A partir de ahí la
                // forma solo crecía bien hacia la derecha y hacia abajo — que es
                // exactamente lo que se veía.
                var x1 = minOf(origenCreacion.x, p.x)
                var y1 = minOf(origenCreacion.y, p.y)
                var w = kotlin.math.abs(p.x - origenCreacion.x)
                var h = kotlin.math.abs(p.y - origenCreacion.y)
                if (keepAspectRatio) {
                    val side = maxOf(w, h)
                    if (p.x < origenCreacion.x) x1 = origenCreacion.x - side
                    if (p.y < origenCreacion.y) y1 = origenCreacion.y - side
                    w = side; h = side
                }
                e.copy(x = x1, y = y1, width = w, height = h)
            }
        }
        replace(updated)
    }

    /**
     * Cierra la creación.
     *
     * Una forma de tamaño cero es un toque, no un dibujo: se descarta en vez de
     * dejar un elemento invisible que luego estorba al picar.
     */
    /** Lo que recorre una polilínea de punta a punta, pasando por todo. */
    private fun loQueRecorre(pts: List<Pt>): Double {
        var largo = 0.0
        for (i in 1 until pts.size) {
            largo += kotlin.math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        return largo
    }

    private fun finishCreating(id: String, zoom: Double) {
        val e = scene.byId(id) ?: return

        // La lupa no se descarta por pequeña: se le da el tamaño mínimo. Un
        // toque suelto con la lupa puesta es «quiero una lupa aquí», y borrarla
        // por no haber arrastrado lo suficiente parece que la herramienta falla.
        val tooSmall = !e.isFreeDraw && !e.isLinear && e.type != ElementType.LUPA &&
            e.width < MIN_CREATED_SIZE && e.height < MIN_CREATED_SIZE
        // **Lo que mide una raya es lo que recorre, no lo que separa sus puntas.**
        //
        // Con la distancia de la primera punta a la última, una flecha libre que rodea algo
        // y vuelve a apuntarlo **se borraba al soltar**: sus dos puntas acaban juntas, así
        // que la cuenta decía que no tenía tamaño. Con dos puntos —una raya o una flecha de
        // las de siempre— las dos cuentas dan lo mismo, así que esto no cambia nada de lo de
        // antes. Ver [Tool.FLECHA_LIBRE].
        val degenerate = e.isLinear && absolutePoints(e).let { pts ->
            pts.size < 2 || loQueRecorre(pts) < MIN_CREATED_SIZE
        }
        if (tooSmall || degenerate) {
            scene = scene.copy(elements = scene.elements.filter { it.id != id })
            return
        }

        var finished = e
        // **La lupa nace con su foco escrito**, aunque sea el centro del cristal.
        // Con el foco a nulo, «a dónde miro» se calcula del cristal cada vez, así
        // que la lupa se lo llevaría detrás al moverla por cualquier camino que
        // no sea el de siempre —arrastrar con clavos puestos, por ejemplo—.
        // Escribiéndolo aquí, apartar el cristal siempre deja el foco donde está.
        if (e.type == ElementType.LUPA) {
            finished = lupaNueva(finished).let { it.copy(foco = focoDe(it)) }
        }
        // **Una flecha libre no se ata a nada.** Atar es de las flechas que unen dos cosas,
        // y atarla reescribiría sus puntos como una recta entre los dos extremos: se perdería
        // justo lo que se acaba de trazar a pulso. Ver [updateBoundPoints].
        if (e.type == ElementType.ARROW && absolutePoints(e).size <= 2) {
            val pts = absolutePoints(e)
            val others = scene.visible.filter { it.id != id }

            // La punta manda: es el extremo que el usuario acaba de soltar y el
            // que expresa la intención.
            val destino = getHoveredElementForBinding(others, pts.last(), zoom)
            destino?.let { finished = bindArrow(finished, it, ArrowEnd.END) }

            // El origen solo se ancla a una forma **distinta** del destino. Una
            // flecha con los dos extremos atados a la misma caja no significa
            // nada y se quedaba pegada a ella pasara lo que pasara.
            getHoveredElementForBinding(others, pts.first(), zoom)
                ?.takeIf { it.id != destino?.id }
                ?.let { finished = bindArrow(finished, it, ArrowEnd.START) }
            if (finished.startBinding != null || finished.endBinding != null) {
                finished = updateBoundPoints(finished, scene.elements)
            }
        }
        replace(finished)


        // La raya de escalar se queda como cota: es la que enseña la medida
        // conocida contra la que se calibró, y verla es lo que permite darse
        // cuenta de que se apuntó mal. Lo único que falta es el número, y eso
        // lo pide la interfaz en cuanto ve esto puesto.
        if (tool == Tool.SCALE && finished.isMeasure) pendingScaleId = finished.id

        // Y la cota **pide su medida nada más trazarla**, con el mismo teclado.
        // Es el momento en que uno sabe cuánto mide; mandarle a buscar un panel
        // después es perder el número por el camino.
        if (tool == Tool.MEASURE && finished.isMeasure && pedirLaMedida) {
            pendingCotaId = finished.id
        }

        // **La herramienta NO se desactiva al soltar.** Excalidraw vuelve a la
        // flecha después de cada forma porque en escritorio se retoma con una
        // tecla; aquí eso obliga a volver a la barra entre cuadrado y cuadrado,
        // que es justo lo que hacía insoportable dibujar con el dedo. La capa
        // de anotación de PixPin siempre se comportó así: la herramienta se
        // queda puesta hasta que la cambias.
        //
        // Tampoco se selecciona lo recién dibujado: con la herramienta aún
        // activa, los tiradores solo estorbarían al siguiente trazo.
    }

    /** Fuerza el trazo al eje más cercano (el equivalente a mantener Shift). */
    private fun constrainToAxis(from: Pt, to: Pt): Pt {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) Pt(to.x, from.y)
        else Pt(from.x, to.y)
    }

    // ---------------------------------------------------------------------
    // Selección
    // ---------------------------------------------------------------------

    private fun beginSelectionGesture(p: Pt, threshold: Double, zoom: Double) {
        // 0. ¿Un clavo? **Antes que nada.** Un clavo y un tirador en el mismo
        //    sitio son dos reglas peleándose por el mismo toque, y hasta ahora
        //    ganaba el tirador: acercabas el dedo al clavo, agarrabas el
        //    tirador y la estructura se movía entera en vez de articularse.
        //    Donde hay clavo manda el clavo.
        val clavo = scene.alfileres.indexOfFirst {
            kotlin.math.hypot(it.punto.x - p.x, it.punto.y - p.y) <= UMBRAL_GUIA / zoom
        }
        if (clavo >= 0) {
            gesture = Gesture.MovingPin(clavo)
            return
        }

        // 0,5. ¿La letra de un punto? **Antes que la figura que tenga debajo.**
        //    La letra vive fuera del punto, encima de la figura que se está
        //    nombrando, así que el toque caería sobre la figura y arrastraría el
        //    triángulo entero en vez de recolocar la letra. Y colocarla a mano
        //    hace falta: la automática acierta casi siempre, pero «casi» no
        //    basta cuando dos vértices están juntos y las dos letras se pisan.
        //    Y **el propio punto gana a su letra**: la letra vive a veintidós
        //    píxeles, y con el margen del dedo su zona y la del punto se
        //    solapaban. Tocando el punto salía girando la letra, o sea que el
        //    punto dejaba de poder moverse. Primero se mira si el dedo cae en el
        //    punto; solo si no, se prueba con la letra, y sin margen extra.
        val sobreUnPunto = editables.any {
            it.type == ElementType.PUNTO &&
                kotlin.math.hypot(it.x - p.x, it.y - p.y) <= RADIO_DE_AGARRE
        }
        val letra = if (sobreUnPunto) null else editables.lastOrNull {
            it.type == ElementType.PUNTO && tocaLaEtiqueta(it, p, 0.0)
        }
        if (letra != null) {
            gesture = Gesture.GirandoEtiqueta(letra.id)
            selectedIds = setOf(letra.id)
            return
        }

        val selected = selectedElements()

        // 0,75. ¿El recuadro de una lupa? **Antes que la figura de debajo.**
        //    Lo que se mira suele caer encima de algo dibujado, así que el toque
        //    se lo llevaría ese algo y no habría forma de apuntar la lupa a otro
        //    sitio. Solo cuenta **fuera del cristal**: donde los dos se solapan
        //    —una lupa recién puesta está encima de lo que mira— arrastrar
        //    significa apartar la lupa, que es el gesto que se hace primero.
        // **El foco también tiene zona que mover.** Es la figura que se resalta,
        // y hasta ahora solo se podía estirar el marco de fuera: lo de dentro
        // —que es lo que de verdad se está señalando— no había forma de tocarlo.
        val laLupa = selected.singleOrNull()?.takeIf {
            it.type == ElementType.LUPA || it.type == ElementType.SPOTLIGHT
        }
        if (laLupa != null) {
            // Con `hitElementItself` y no comparando la caja a pelo: esa
            // comparación ignora el giro, así que con la lupa girada el dedo
            // caía «fuera del cristal» estando dentro y se ponía a mover el foco.
            val dentroDelCristal = hitElementItself(p, laLupa, UMBRAL_GUIA / zoom, scene.vista)
            if (!dentroDelCristal && tocaElFoco(laLupa, p, UMBRAL_GUIA / zoom)) {
                gesture = Gesture.MoviendoElFoco(laLupa.id, p, focoDe(laLupa))
                return
            }
        }

        // 1. ¿Un tirador? Tiene prioridad sobre todo lo demás: está encima de
        //    la forma y si perdiera, redimensionar sería imposible.
        if (selected.isNotEmpty()) {
            val handles = getSelectionTransformHandles(
                selected, zoom, vista = scene.vista,
                alfileres = scene.alfileres.map { it.punto }
            )
            val center = if (selected.size == 1) {
                getElementAbsoluteCoords(selected[0]).let { Pt(it.cx, it.cy) }
            } else {
                getCommonBounds(selected).let { Pt(it.midX, it.midY) }
            }
            val angle = if (selected.size == 1) selected[0].angle else 0.0
            val hit = hitHandle(handles, p, angle, center)
            if (hit != null) {
                gesture = when {
                    hit.type == HandleType.ROTATION -> Gesture.Rotating(selected)

                    // El tirador del medio de un tramo no mueve nada: **crea**
                    // un punto ahí y a partir de ese momento lo arrastra. Así
                    // doblar una línea es un solo gesto y no «añadir, buscar,
                    // mover».
                    hit.type == HandleType.POINT_ADD -> {
                        val e = selected.first()
                        val nuevo = e.withPointInserted(hit.indice ?: 0, p).touched()
                        replace(nuevo)
                        Gesture.MovingPoint(e.id, (hit.indice ?: 0) + 1)
                    }

                    // Un punto se mueve solo; la caja de redimensionar
                    // escalaría el recorrido entero.
                    // La caja no se estira por un rectangulo: cada tirador suyo cambia
                    // una cosa. Ver [HandleType.SOLIDO_HUELLA].
                    hit.type.esDeSolido -> Gesture.MoldeandoSolido(
                        selected.first().id, hit.type
                    )

                    hit.type.esPunto -> Gesture.MovingPoint(
                        selected.first().id,
                        hit.indice ?: 0
                    )

                    else -> Gesture.Resizing(hit.type, selected)
                }
                return
            }
        }

        // 1.a ¿La barra de un cronograma? Va antes que mover la figura: dentro de un
        //     plan, lo que uno quiere arrastrar casi siempre es una barra, no la lámina.
        //     La lámina se sigue moviendo agarrándola por cualquier otro sitio.
        val plan = selected.singleOrNull()
            ?.takeIf { it.type == ElementType.CRONOGRAMA && !it.locked }
        if (plan != null) {
            val toque = toqueEnBarra(plan, p, UMBRAL_GUIA / zoom.coerceAtLeast(0.0001) / 2)
            if (toque != null) {
                val col = anchoDeColumna(plan)
                val t = plan.tareas[toque.indice]
                val agarre = if (col <= 0.0) 0.0
                else (p.x - xDeLaEscala(plan)) / col - t.desde
                gesture = Gesture.BarraDelPlan(plan.id, toque.indice, toque.mano, agarre)
                return
            }
        }

        // 1.b ¿Uno de los dos anillos? Van alrededor de lo seleccionado y por debajo de
        //     los tiradores en prioridad: los tiradores son pequeños y concretos, y los
        //     anillos ocupan media pantalla — al revés, no se podría agarrar ninguno.
        val volumenes = selected.filter { it.isSolido && !it.locked }
        if (volumenes.isNotEmpty()) {
            val anillos = centroDeVolumenes(volumenes, scene.vista)
            if (anillos != null) {
                val (centro, radio) = anillos
                val margen = UMBRAL_GUIA / zoom.coerceAtLeast(0.0001)
                for (eje in EjeDeGiro.entries) {
                    val aro = anilloDelVolumen(centro, radio, eje, scene.vista)
                    if (!tocaElAnillo(p, aro, margen)) continue
                    val angulo = anguloEnElAnillo(centro, eje, p, scene.vista) ?: continue
                    gesture = Gesture.GirandoVolumen(centro, eje, angulo, volumenes)
                    return
                }
            }
        }

        // 2. ¿Un elemento? Se selecciona y empieza a moverse.
        // **Tocar el texto de una caja es tocar la caja.**
        //
        // Un texto de dentro no se arrastra solo: es una propiedad de su figura,
        // y su sitio se recalcula centrado cada vez que la figura se mueve. Al
        // permitir cogerlo aparte pasaba lo previsible — se arrastraba, y al
        // soltarlo volvía de un salto al centro de una caja que no se había
        // movido. Cogiendo la caja, la palabra se va con ella, que es lo que uno
        // espera al tirar de la etiqueta de un diagrama.
        val hit = getElementAtPosition(editables, p, threshold, scene.vista)
            ?.let { tocado ->
                if (tocado.type == ElementType.TEXT) contenedorDe(tocado, editables) ?: tocado
                else tocado
            }
        if (hit != null) {
            // Tocar algo ya seleccionado no rehace la selección: si no, mover
            // un grupo de varios sería imposible sin volver a seleccionarlo.
            if (hit.id !in selectedIds) {
                selectedIds = getElementsInGroupOf(editables, hit).map { it.id }.toSet()
            }
            // **Solo lo seleccionado.** Lo clavado a ello NO se arrastra: esa
            // es justo la diferencia entre un clavo y un pegamento. Lo que hace
            // el clavo es limitar a la propia figura —con uno solo puede girar,
            // con dos no puede nada—, y para mover el conjunto se mueve el
            // clavo. Ver [Libertad].
            gesture = Gesture.Moving(p, selectedElements())
            return
        }

        // 3. Nada: se deselecciona y se abre el rectángulo de selección.
        selectedIds = emptySet()
        gesture = Gesture.BoxSelecting(p, BoxSelectionMode.CONTAIN)
    }

    // ---------------------------------------------------------------------
    // Acciones
    // ---------------------------------------------------------------------

    /**
     * Borra lo seleccionado, **y el texto que viva dentro de lo borrado**.
     *
     * Sin esto, borrar el rectángulo de un diagrama dejaba su palabra flotando
     * en el aire: un texto huérfano que ya no sabe de quién era y que hay que
     * cazar aparte. Ver [TextoEnFiguras].
     */
    fun deleteSelection() = mutate { elementos ->
        val cajas = elementos.filter { it.id in selectedIds }
        val susTextos = cajas.flatMap { caja ->
            elementos.filter { it.type == ElementType.TEXT && it.containerId == caja.id }
        }.map { it.id }
        deleteSelected(elementos, selectedIds + susTextos).also { selectedIds = emptySet() }
    }
    fun duplicateSelection() = mutate { elements ->
        val copies = duplicateElements(elements.filter { it.id in selectedIds })
        selectedIds = copies.map { it.id }.toSet()
        elements + copies
    }
    fun toggleLockSelection() = mutate { toggleLock(it, selectedIds) }
    fun bringForward() = mutate { moveOneRight(it, selectedIds) }
    fun sendBackward() = mutate { moveOneLeft(it, selectedIds) }
    fun bringToFront() = mutate { moveAllRight(it, selectedIds) }
    fun sendToBack() = mutate { moveAllLeft(it, selectedIds) }
    fun group() = mutate { groupElements(it, selectedIds) }
    fun ungroup() = mutate { ungroupElements(it, selectedIds) }
    fun align(axis: AlignAxis, position: AlignPosition) =
        mutate { alignElements(it, selectedIds, axis, position) }
    fun distribute(axis: AlignAxis) = mutate { distributeElements(it, selectedIds, axis) }

    /**
     * Cambia lo seleccionado uno a uno, con historial.
     *
     * Es la puerta para las ediciones que no son de estilo sino de geometría —
     * dictarle a una raya cuánto mide y hacia dónde va— sin que cada una tenga
     * que ser un método propio del controlador.
     */
    fun mutarSeleccion(transformar: (Element) -> Element) = mutate { elementos ->
        elementos.map { if (it.id in selectedIds && !it.locked) transformar(it) else it }
    }

    /**
     * Repite la caja seleccionada **pegada a sí misma**, una vez por toque.
     *
     * Una vez y no cinco de golpe: pulsar tres veces son tres módulos y se ve crecer la
     * fila, que es más fácil de acertar que decidir un número por adelantado y luego
     * corregirlo. Ver [repetirSolido].
     */
    fun repetirLaCaja(eje: EjeDeRepeticion) {
        val caja = selectedElements().singleOrNull()?.takeIf { it.isSolido && !it.locked }
            ?: return
        val copias = repetirSolido(caja, 1, eje, scene.vista)
        if (copias.isEmpty()) return
        mutate { it + copias }
        // Lo nuevo queda seleccionado: así se sigue repitiendo desde la última y la fila
        // crece por la punta en vez de volver a nacer de la primera.
        selectedIds = copias.map { it.id }.toSet()
    }

    /**
     * Lo que mide la caja que se está tocando, y dónde escribirlo.
     *
     * **En cuadros y no en píxeles.** Lo que uno quiere decir en un croquis conceptual es
     * «esta es el doble de alta que aquella», y eso solo se lee si los números son
     * pequeños y redondos —que lo son, porque el imán engancha al cuadro—. Un `120 × 80 ×
     * 160` obliga a dividir mentalmente para ver la misma proporción que dice `6 × 4 × 8`.
     *
     * Solo mientras dura el gesto: un dibujo con todas sus cajas acotadas no se lee, y
     * cuando importa la medida es justo mientras se está poniendo. Es la misma regla que
     * siguen los ángulos internos.
     */
    fun medidasDeLaCaja(): Pair<Pt, String>? {
        val id = when (val g = gesture) {
            is Gesture.Huella -> g.elementId
            is Gesture.Levantando -> g.elementId
            is Gesture.MoldeandoSolido -> g.elementId
            else -> null
        } ?: return null
        val e = scene.byId(id)?.takeIf { it.isSolido } ?: return null
        val s = solidoDe(e).normalizado()
        fun cuadros(v: Double): String {
            val n = v / PASO_ISO
            return if (kotlin.math.abs(n - Math.round(n)) < 0.05) "${Math.round(n)}"
            else String.format(java.util.Locale.US, "%.1f", n)
        }
        val texto = buildString {
            append(cuadros(s.ancho)).append(" × ").append(cuadros(s.fondo))
            if (s.altura > 0.0) append(" × ").append(cuadros(s.altura))
            if (s.base > 0.0) append("  ↑").append(cuadros(s.base))
        }
        val caja = envolturaDeSolido(e, scene.vista)
        return Pt(caja.midX, caja.y1) to texto
    }

    /**
     * Los dos anillos que hay que pintar ahora mismo, si los hay.
     *
     * Salen de lo seleccionado y no del gesto: son un mando, y un mando se ve **antes**
     * de tocarlo. Con varias piezas seleccionadas hay unos solos, alrededor de todas, que
     * es lo que dice que van a girar juntas.
     */
    fun anillosDelVolumen(): List<Pair<EjeDeGiro, List<Pt>>> = adornosDelVolumen().anillos

    /** Las aristas de detrás de lo seleccionado, a trazos. Ver [aristasOcultasDeElemento]. */
    fun aristasOcultasDeLaSeleccion(): List<Pair<Pt, Pt>> = adornosDelVolumen().aristas

    /** Los adornos que la vista pinta alrededor de un volumen seleccionado. */
    class AdornosDelVolumen(
        val anillos: List<Pair<EjeDeGiro, List<Pt>>>,
        val aristas: List<Pair<Pt, Pt>>
    )

    private var claveDeAdornos: String? = null
    private var adornosGuardados = AdornosDelVolumen(emptyList(), emptyList())

    /**
     * Los aros y las aristas ocultas, **calculados una vez y no en cada fotograma**.
     *
     * Los dos salen de repartir las caras del volumen y de proyectar un centenar de
     * puntos, y los dos se piden mientras se dibuja la pantalla: al hacer zoom sobre un
     * dibujo con una pieza seleccionada, eso era rehacer el reparto de caras sesenta veces
     * por segundo para dar exactamente el mismo resultado. No dependen del zoom —van en
     * coordenadas de escena— así que basta rehacerlos cuando cambia la pieza o la vista.
     */
    private fun adornosDelVolumen(): AdornosDelVolumen {
        val volumenes = selectedElements().filter { it.isSolido }
        if (volumenes.isEmpty()) {
            claveDeAdornos = null
            adornosGuardados = AdornosDelVolumen(emptyList(), emptyList())
            return adornosGuardados
        }
        // **La clave es la geometría, no la versión.** Al girar con el anillo, cada
        // fotograma se recalcula desde los originales del gesto, así que la versión sale
        // siempre la misma —original + 1— y los adornos se quedaban congelados en la
        // primera posición: se veía como que el recuadro y los aros no acompañaban a la
        // pieza. Con lo que de verdad los mueve dentro de la clave, acompañan.
        val clave = buildString {
            append(scene.vista.cuartos)
            for (v in volumenes) {
                append('|').append(v.id)
                append(':').append(v.x).append(',').append(v.y)
                append(',').append(v.width).append(',').append(v.height)
                append(',').append(v.altura).append(',').append(v.cota)
                append(',').append(v.giroEnPlanta).append(',').append(v.inclinacion)
                append(',').append(v.formaSolida)
            }
        }
        if (clave == claveDeAdornos) return adornosGuardados

        val anillos = centroDeVolumenes(volumenes, scene.vista)?.let { (centro, radio) ->
            EjeDeGiro.entries.map { it to anilloDelVolumen(centro, radio, it, scene.vista) }
        } ?: emptyList()
        val aristas = volumenes.flatMap { aristasOcultasDeElemento(it, scene.vista) }
        claveDeAdornos = clave
        adornosGuardados = AdornosDelVolumen(anillos, aristas)
        return adornosGuardados
    }

    /**
     * Levanta lo plano y lo convierte en volumen. Ver [extruido].
     *
     * Es la puerta de entrada al 3D desde donde uno ya está: se dibuja la planta con las
     * herramientas de siempre y se levanta, sin aprender otro gesto.
     */
    fun extruirSeleccion() {
        val marcados = selectedElements().filter { !it.locked }
        if (marcados.isEmpty()) return

        // **Varias rayas que cierran un contorno se levantan como una sola pieza.**
        //
        // Es como se dibuja una planta en cualquier programa de ingeniería: cuatro rectas
        // que se tocan en las puntas, no una figura de catálogo. Antes había que
        // redibujarla con la herramienta de rectángulo para poder extruirla, que es justo
        // el trabajo que uno venía a evitar. Ver [aroDeRayas].
        if (marcados.size > 1 && marcados.all { it.isLinear || it.isFreeDraw }) {
            val cuerpo = extruidoDeRayas(marcados, scene.vista)
            if (cuerpo != null) {
                val fuera = marcados.map { it.id }.toSet()
                mutate { lista -> lista.filterNot { it.id in fuera } + cuerpo }
                selectedIds = setOf(cuerpo.id)
                return
            }
        }

        val nuevos = marcados.mapNotNull { extruido(it, scene.vista) }
        if (nuevos.isEmpty()) return
        val viejos = marcados.filter { extruido(it, scene.vista) != null }.map { it.id }.toSet()
        mutate { lista -> lista.filterNot { it.id in viejos } + nuevos }
        selectedIds = nuevos.map { it.id }.toSet()
    }

    /**
     * Levanta lo que se toque con la herramienta, o **el aro que forme con lo marcado**.
     *
     * Con varias rayas marcadas, tocar una de ellas levanta el contorno entero: es lo que
     * uno espera después de haberse molestado en marcarlas.
     */
    private fun extruirTocando(victima: Element, p: Pt) {
        val marcados = selectedElements().filter { !it.locked }
        val enGrupo = marcados.size > 1 && victima.id in marcados.map { it.id } &&
            marcados.all { it.isLinear || it.isFreeDraw }
        val cuerpo = if (enGrupo) extruidoDeRayas(marcados, scene.vista)
        else extruido(victima, scene.vista)?.copy(altura = null)
        if (cuerpo == null) return
        val fuera = if (enGrupo) marcados.map { it.id }.toSet() else setOf(victima.id)
        mutate { lista -> lista.filterNot { it.id in fuera } + cuerpo }
        selectedIds = setOf(cuerpo.id)
        if (cuerpo.altura == null) {
            solidoPendiente = cuerpo.id
            gesture = Gesture.Levantando(cuerpo.id, p, 0.0)
        }
    }

    /**
     * Da la vuelta a una figura alrededor de una raya. Ver [revolucionado].
     *
     * **Se seleccionan las dos cosas**: lo que se tornea y el eje. Con dos elementos
     * marcados, la raya hace de eje y la otra de figura; con uno solo se conserva lo de
     * antes —el propio trazo es el perfil— para que revolucionar un perfil suelto siga
     * funcionando.
     */
    fun revolucionarSeleccion() {
        val marcados = selectedElements().filter { !it.locked }
        if (marcados.isEmpty()) return
        val eje = if (marcados.size >= 2) marcados.firstOrNull { it.isLinear } else null
        val figura = marcados.firstOrNull { it.id != eje?.id } ?: return
        val cuerpo = revolucionado(figura, eje, scene.vista) ?: return
        val viejos = listOfNotNull(figura.id, eje?.id).toSet()
        mutate { lista -> lista.filterNot { it.id in viejos } + cuerpo }
        selectedIds = setOf(cuerpo.id)
    }

    /**
     * Una hoja más en el cuaderno, **debajo de la última**. Ver [Cuaderno].
     *
     * [dondeSiNoHay] es el centro de lo que se está mirando, para que la primera hoja
     * aparezca delante y no en el origen del lienzo — que puede estar a media pantalla.
     */
    fun anadirHoja(tamano: TamanoDePapel, dondeSiNoHay: Pt) {
        val hoja = sitioDeLaHojaSiguiente(scene, tamano, dondeSiNoHay)
        mutate { it + hoja }
        selectedIds = setOf(hoja.id)
    }

    /** El tamaño y la pauta de las hojas marcadas. */
    fun cambiarPapel(tamano: TamanoDePapel) = mutarSeleccion { e ->
        if (!e.isFrame) e
        // Se conserva el ancho y se recalcula el alto: cambiar de tamaño es cambiar de
        // proporción, no encoger la hoja. Ver [TamanoDePapel].
        else e.copy(papel = tamano, height = e.width * tamano.proporcion).touched()
    }

    fun cambiarPauta(pauta: PautaDeHoja) =
        mutarSeleccion { if (it.isFrame) it.copy(pauta = pauta).touched() else it }

    /**
     * Va a la hoja de al lado y **la encuadra**.
     *
     * Pasar página en un cuaderno es que la hoja siguiente ocupe la pantalla, no
     * desplazarse un poco hacia abajo y quedarse a medias entre dos.
     */
    fun pasarDeHoja(delta: Int, ancho: Double, alto: Double) {
        val hojas = hojasEnOrden(scene)
        if (hojas.isEmpty()) return
        val actual = hojas.indexOfFirst { it.id in selectedIds }
            .takeIf { it >= 0 }
            ?: hojaMasCentrada(hojas, ancho, alto)
        val destino = hojas.getOrNull((actual + delta).coerceIn(hojas.indices)) ?: return
        scene = scene.copy(
            viewport = fitToContent(listOf(destino), ancho, alto, padding = MARGEN_DE_LA_HOJA)
        )
        selectedIds = setOf(destino.id)
    }

    /** Cuál de las hojas está más cerca del centro de lo que se ve ahora mismo. */
    private fun hojaMasCentrada(hojas: List<Element>, ancho: Double, alto: Double): Int {
        val centro = scene.viewport.toScene(ancho / 2, alto / 2)
        return hojas.indices.minByOrNull {
            val b = getElementBounds(hojas[it])
            kotlin.math.hypot(b.midX - centro.x, b.midY - centro.y)
        } ?: 0
    }

    /** Una fila más en el cronograma, detrás de la última. Ver [conTareaNueva]. */
    fun anadirTarea() =
        mutarSeleccion { if (it.type == ElementType.CRONOGRAMA) conTareaNueva(it, "") else it }

    /** Una fila menos. */
    fun quitarTarea() =
        mutarSeleccion { if (it.type == ElementType.CRONOGRAMA) sinLaUltimaTarea(it) else it }

    /** Una columna más o menos en la escala. */
    fun cambiarPeriodos(delta: Int) = mutarSeleccion {
        if (it.type == ElementType.CRONOGRAMA) conPeriodos(it, it.periodos + delta) else it
    }

    /** Le pone nombre a una fila. */
    fun renombrarTarea(indice: Int, nombre: String) = mutarSeleccion { e ->
        if (e.type != ElementType.CRONOGRAMA) e
        else e.copy(
            tareas = e.tareas.mapIndexed { i, t -> if (i == indice) t.copy(nombre = nombre) else t }
        ).touched()
    }

    /** Macizo o de alambre. Ver [Element.esqueleto]. */
    fun cambiarEsqueleto(esqueleto: Boolean) =
        mutarSeleccion { if (it.isSolido) it.copy(esqueleto = esqueleto).touched() else it }

    /**
     * Tumba una imagen en el suelo, o la vuelve a poner de cara.
     *
     * **Y la deja donde estaba.** Al tumbarla, `x`/`y` pasan a querer decir otra cosa —el
     * origen de su huella ya proyectado, como en un volumen— así que cambiando solo la
     * bandera la imagen pegaría un salto. Se recoloca para que su centro siga en el mismo
     * punto de la pantalla, que es lo que uno espera de un interruptor.
     */
    fun tumbarImagen(enElSuelo: Boolean) = mutarSeleccion { e ->
        if (e.type != ElementType.IMAGE || e.enElSuelo == enElSuelo) return@mutarSeleccion e
        val centro = Pt(e.x + e.width / 2, e.y + e.height / 2)
        val desvio = proyectar(e.width / 2, e.height / 2, 0.0, scene.vista, PASO_DEL_SOLIDO)
        val sitio = if (enElSuelo) {
            Pt(centro.x - desvio.x, centro.y - desvio.y)
        } else {
            Pt(centro.x + desvio.x - e.width / 2, centro.y + desvio.y - e.height / 2)
        }
        e.copy(x = sitio.x, y = sitio.y, enElSuelo = enElSuelo).touched()
    }

    fun flipSelectionHorizontal() = mutateSelected { flipHorizontal(it) }
    fun flipSelectionVertical() = mutateSelected { flipVertical(it) }

    /** Coloca una imagen ya guardada en el almacén de la escena. */
    /**
     * Mete una imagen en la escena, centrada en [at].
     *
     * [alFondo] la coloca **debajo de todo y bloqueada**, que es como entra la
     * foto de un pin cuando se abre en la edición avanzada: allí lo que ya
     * había dibujado encima tiene que seguir viéndose encima, y mover la foto
     * descuadraría el dibujo respecto a lo que enseña el pin, que sigue
     * pintando la foto por su cuenta.
     */
    fun placeImage(
        file: SceneFile, at: Pt, width: Double, height: Double, alFondo: Boolean = false
    ) {
        val before = scene.elements
        val e = newImageElement(file.id, at.x - width / 2, at.y - height / 2, width, height, scene.style)
            .copy(locked = alFondo)
        scene = scene.copy(
            elements = if (alFondo) listOf(e) + scene.elements else scene.elements + e,
            files = scene.files + (file.id to file)
        )
        if (!alFondo) {
            selectedIds = setOf(e.id)
            selectTool(Tool.SELECTION)
        }
        anotar(before)
    }

    /**
     * Mete elementos ya hechos en el dibujo y los deja marcados.
     *
     * Es la puerta por la que entra lo que no se traza con el dedo: una figura
     * de la lista, una tabla pegada de la hoja de cálculo. Llegan colocados —
     * quien los estampa ya sabe dónde los quiere— así que aquí solo se añaden.
     *
     * **Se quedan seleccionados y con la flecha puesta.** Lo primero que se hace
     * con algo recién estampado es moverlo un poco, y encontrárselo ya cogido
     * ahorra el toque de buscarlo; dejar la herramienta anterior activa sería
     * dibujar encima al primer intento.
     */
    /**
     * Añade [elementos] a la escena, y de normal los deja marcados para
     * colocarlos. Con [marcar] apagado solo entran: es lo que pide un plano
     * importado — miles de trazos marcados son miles de adornos de selección
     * por fotograma, y el grupo se marca igual con un toque cuando haga falta.
     */
    fun insertar(elementos: List<Element>, marcar: Boolean = true) {
        if (elementos.isEmpty()) return
        val before = scene.elements
        scene = scene.copy(elements = scene.elements + elementos)
        if (marcar) {
            selectTool(Tool.SELECTION)
            selectedIds = elementos.map { it.id }.toSet()
        }
        anotar(before)
    }

    /**
     * Manda al fondo y **bloquea** los elementos indicados.
     *
     * Es lo que convierte una imagen en el soporte sobre el que se dibuja en vez
     * de en un elemento más: bloqueada no se selecciona, no se arrastra, no se
     * estira y el borrador no se la lleva; al fondo, todo lo dibujado queda por
     * encima de ella pase lo que pase.
     *
     * No pasa por el historial a propósito: no es una edición del dibujo sino
     * cómo está montada la escena, y deshacer hasta desbloquear la foto sería
     * deshacer algo que el usuario nunca hizo.
     */
    fun fijarAlFondo(ids: Set<String>) {
        if (ids.isEmpty()) return
        val fijados = scene.elements.filter { it.id in ids }.map { it.copy(locked = true) }
        scene = scene.copy(elements = fijados + scene.elements.filter { it.id !in ids })
        selectedIds = selectedIds - ids
    }

    /**
     * Clava lo que hay marcado **en su sitio**.
     *
     * Clavado no se mueve, no se estira y el borrador no se lo lleva: es lo que
     * se le pide a una foto que está ahí para dibujar encima de ella. Se hace
     * por elemento y no de golpe porque en el mismo dibujo suele haber una que
     * hace de fondo y otras que todavía se están colocando.
     *
     * **Se anota en el historial**, al contrario que [fijarAlFondo]: aquello es
     * cómo está montada la escena y esto es algo que el usuario acaba de hacer,
     * así que deshacer tiene que devolverlo.
     */
    /**
     * Marca lo clavado que haya en [p], para poder soltarlo.
     *
     * **Lo clavado no responde al toque** —para eso se clava— así que sin una
     * puerta de atrás, poner el candado era una decisión sin vuelta: la foto
     * quedaba intocable para siempre. Esta es la puerta: la pulsación larga, que
     * es el gesto de «sé lo que estoy haciendo» de toda la vida.
     */
    fun marcarClavadoEn(p: Pt, threshold: Double): Boolean {
        val clavado = scene.visible.lastOrNull {
            it.locked && hitElementItself(p, it, threshold, scene.vista)
        } ?: return false
        selectTool(Tool.SELECTION)
        selectedIds = setOf(clavado.id)
        return true
    }

    /** Suelta lo marcado, aunque esté clavado. El botón del candado abierto. */
    fun soltarSeleccion() {
        val ids = selectedIds
        if (ids.isEmpty()) return
        val before = scene.elements
        scene = scene.copy(
            elements = scene.elements.map {
                if (it.id in ids) it.copy(locked = false).touched() else it
            }
        )
        anotar(before)
    }

    /** Si lo marcado está clavado. Decide qué candado enseñar. */
    val seleccionClavada: Boolean
        get() = selectedIds.isNotEmpty() &&
            scene.elements.filter { it.id in selectedIds }.all { it.locked }

    fun clavarSeleccion() {
        val ids = selectedIds
        if (ids.isEmpty()) return
        val before = scene.elements
        scene = scene.copy(
            elements = scene.elements.map {
                if (it.id in ids) it.copy(locked = true).touched() else it
            }
        )
        anotar(before)
    }

    /**
     * Suelta todo lo clavado.
     *
     * Va sin selección a propósito: **lo clavado no se puede seleccionar**, así
     * que si soltar dependiera de tenerlo marcado no habría forma de volver
     * atrás. Es el botón que rescata una foto que se clavó sin querer.
     */
    fun soltarTodo() {
        if (scene.elements.none { it.locked }) return
        val before = scene.elements
        scene = scene.copy(
            elements = scene.elements.map { if (it.locked) it.copy(locked = false).touched() else it }
        )
        anotar(before)
    }

    /** Si hay algo clavado en el dibujo. Para saber si ofrecer soltarlo. */
    val hayClavados: Boolean get() = scene.elements.any { it.locked && !it.isDeleted }

    /** Cambia el texto de un elemento de texto (lo que escribe el teclado). */
    fun updateText(id: String, text: String, measuredWidth: Double, measuredHeight: Double) {
        mutate { elements ->
            val texto = elements.firstOrNull { it.id == id }
            val contenedor = texto?.let { contenedorDe(it, elements) }
            if (texto == null || contenedor == null) {
                return@mutate elements.map {
                    if (it.id == id) {
                        it.copy(text = text, width = measuredWidth, height = measuredHeight)
                            .touched()
                    } else it
                }
            }

            // **La caja crece si el texto no cabe, y no encoge.**
            //
            // Crecer hace falta o el texto se sale por abajo al llegar al
            // borde. No encoger es igual de importante: una caja de diagrama se
            // dibuja del tamaño que se quiere, y verla estrecharse al borrar una
            // palabra sería el dibujo corrigiendo a quien dibuja.
            val altoNecesario = figuraQueLoContiene(measuredHeight, contenedor.type)
            val anchoNecesario = figuraQueLoContiene(measuredWidth, contenedor.type)
            val caja = contenedor.copy(
                width = kotlin.math.max(contenedor.width, anchoNecesario),
                height = kotlin.math.max(contenedor.height, altoNecesario)
            )
            val donde = sitioDelTextoDentro(caja, measuredWidth to measuredHeight)
            val nuevoTexto = texto.copy(
                text = text, width = measuredWidth, height = measuredHeight,
                x = donde.x, y = donde.y
            ).touched()

            elements.map {
                when (it.id) {
                    id -> nuevoTexto
                    contenedor.id -> if (caja == contenedor) it else caja.touched()
                    else -> it
                }
            }
        }
    }

    /**
     * Recoloca el texto que vive dentro de cada figura que se haya tocado.
     *
     * Se llama después de mover, estirar o girar: el texto no es un elemento que
     * se arrastre por su cuenta, **es una propiedad de su caja**, así que su
     * sitio se vuelve a calcular en vez de desplazarse con un delta. Con el
     * delta bastaría para mover, pero al estirar la caja el texto tiene que
     * volver a centrarse, no seguir a una esquina.
     */
    private fun recolocarTextosDentro() {
        val conTexto = scene.elements.filter {
            it.type == ElementType.TEXT && it.containerId != null && !it.isDeleted
        }
        if (conTexto.isEmpty()) return
        scene = scene.copy(
            elements = scene.elements.map { e ->
                if (e.type != ElementType.TEXT || e.containerId == null) return@map e
                val caja = contenedorDe(e, scene.elements) ?: return@map e
                val donde = sitioDelTextoDentro(caja, e.width to e.height)
                if (donde.x == e.x && donde.y == e.y) e else e.copy(x = donde.x, y = donde.y)
            }
        )
    }

    /**
     * Aplica un cambio de estilo a la selección y al estilo activo.
     *
     * Los dos a la vez, como en el original: cambiar el color con algo
     * seleccionado lo cambia ahí **y** deja el pincel cargado con ese color.
     */
    fun changeStyle(change: (ItemStyle) -> ItemStyle, toElement: (Element) -> Element) {
        val before = scene.elements
        scene = applyStyle(scene, selectedIds, change, toElement)
        anotar(before)
    }

    /**
     * El estilo que hay que **enseñar** en los mandos.
     *
     * El de lo marcado si hay algo marcado, y si no, el del pincel. Los mandos
     * enseñaban siempre el del pincel, y eso es lo que hacía que tocar uno se
     * llevara por delante los demás: el panel decía negro y gordo mientras la
     * figura marcada era roja y fina, así que subir la opacidad le encajaba de
     * paso el negro y el gordo. Ver [estiloDe].
     *
     * Con varios marcados manda el primero. No hay respuesta buena ahí —tienen
     * estilos distintos por definición— y la del primero al menos es una que se
     * ve en la pantalla.
     */
    /**
     * El estilo que enseñan los mandos.
     *
     * **Manda el primero de lo marcado, salvo en la letra.** Una tabla del lienzo son
     * muchos elementos —los rectángulos del marco, los de la cabecera y los textos de las
     * celdas— y el primero del montón es un rectángulo, que no tiene tamaño de letra. El
     * mando leía entonces el de fábrica y se quedaba clavado a media barra por muchas
     * veces que se moviera: subía la letra de verdad, pero el mango no seguía a nada.
     *
     * Así que para la letra se busca **el primero que tenga letra**. Es el único campo con
     * este problema porque es el único que solo llevan algunos tipos de elemento.
     */
    fun estiloActivo(): ItemStyle {
        val marcados = selectedElements()
        val base = marcados.firstOrNull()?.let { estiloDe(it) } ?: return scene.style
        val conLetra = marcados.firstOrNull { it.fontSize != null } ?: return base
        return base.copy(
            fontSize = conLetra.fontSize ?: base.fontSize,
            fontFamily = conLetra.fontFamily ?: base.fontFamily
        )
    }

    /**
     * Cambia el estilo tocando **solo el mando que se ha movido**.
     *
     * Lo que llega de un panel de estilos no es «ponle este estilo» sino «súbele
     * la opacidad»: el estilo entero con un campo distinto. Volcarlo tal cual
     * escribía los otros quince encima. Aquí se compara con [estiloActivo] —lo
     * que el panel estaba enseñando— y se aplica la diferencia, a lo marcado y
     * al pincel.
     *
     * [remedir] es para el texto: cambiarle la letra obliga a volver a medir su
     * caja, y eso necesita Android. Quien tenga contexto lo pone.
     */
    fun cambiarEstilo(nuevo: ItemStyle, remedir: (Element) -> Element = { it }) {
        val anterior = estiloActivo()
        if (nuevo == anterior) return
        val before = scene.elements
        val elementos = scene.elements.map {
            if (it.id in selectedIds && !it.locked) {
                remedir(estiloAplicado(it, anterior, nuevo)).touched()
            } else it
        }
        scene = scene.copy(
            elements = elementos,
            // **Y el pincel también, pero solo en lo que se tocó.** Si se
            // volcara entero, marcar una figura roja y subirle la opacidad
            // dejaría el pincel en rojo sin que nadie lo haya pedido.
            style = conCambios(scene.style, anterior, nuevo)
        )
        anotar(before)
    }

    /**
     * Le da a cada texto marcado el alto que pide su ancho.
     *
     * Se hace **mientras se arrastra**, no al soltar: la caja tiene que crecer a la vez que
     * el texto se reparte, que es lo que deja ver lo que está pasando. Y solo cuando el
     * texto no está dentro de otra figura —ahí manda el contenedor, no el texto—.
     */
    private fun ajustarAltoDeTextos() {
        val medir = altoDelTexto ?: return
        val textos = scene.elements.filter {
            it.id in selectedIds && it.type == ElementType.TEXT && it.containerId == null
        }
        if (textos.isEmpty()) return
        scene = scene.copy(
            elements = scene.elements.map { e ->
                if (e.id !in selectedIds || e.type != ElementType.TEXT || e.containerId != null) {
                    return@map e
                }
                val alto = medir(e, e.width)
                if (kotlin.math.abs(alto - e.height) < 0.5) e else e.copy(height = alto).touched()
            }
        )
    }

    fun undo() {
        history.undo(scene.elements)?.let {
            scene = scene.copy(elements = it)
            // Lo deshecho puede haber desaparecido: una selección que apunta a
            // ids que ya no existen deja tiradores flotando en el vacío.
            selectedIds = selectedIds.filter { id -> scene.byId(id) != null }.toSet()
        }
    }

    fun redo() {
        history.redo(scene.elements)?.let {
            scene = scene.copy(elements = it)
            selectedIds = selectedIds.filter { id -> scene.byId(id) != null }.toSet()
        }
    }

    /**
     * **La llave de paso de las luces del dibujo.**
     *
     * No entra en el historial, y es a propósito: subir la luz de un plano es mirarlo de otra
     * manera, no cambiarlo. Deshacer tiene que devolver el último trazo, no el brillo — por el
     * mismo camino que el encuadre. Ver [LucesDelDibujo].
     */
    fun ponerLasLuces(nuevas: LucesDelDibujo) {
        // Se acota aquí y no solo al leerlas: lo que se guarda en el dibujo tiene que ser un
        // número que signifique algo, no uno que solo se porte bien al mirarlo.
        scene = scene.copy(
            luces = nuevas.copy(
                fuerza = nuevas.fuerza.coerceIn(0.0, LucesDelDibujo.LO_MAS_QUE_ALUMBRAN)
            )
        )
    }

    /**
     * **El color del papel.**
     *
     * No entra en el historial, por lo mismo que las luces y que el encuadre: cambiar el papel
     * es mirar el dibujo de otra manera, no cambiarlo. Deshacer tiene que devolver el último
     * trazo, no el fondo. Ver [DrawTheme.esDeNoche].
     */
    fun ponerElPapel(hex: String) {
        scene = scene.copy(backgroundColor = hex)
    }

    fun setViewport(v: Viewport) {
        scene = scene.copy(viewport = v)
    }

    /**
     * Gira la cámara un cuarto de vuelta. Cuatro veces devuelve a la de partida.
     *
     * **No entra en el historial**, y eso es a propósito: mirar desde otro lado
     * no cambia el dibujo, así que deshacer no tiene por qué devolver la vista —
     * sería como si deshacer también deshiciera el zoom. Va por el mismo camino
     * que [setViewport], que es el encuadre, y no por el de las modificaciones.
     */
    fun girarVista(atras: Boolean = false) {
        // La caja a medio hacer se cierra antes de girar: la altura se levanta
        // leyendo el desplazamiento vertical, y si la vista cambiara a mitad del
        // gesto la caja seguiría subiendo desde una cámara que ya no es la que
        // había cuando se apoyó el dedo.
        fijarSolido()
        scene = scene.copy(
            vista = if (atras) scene.vista.anterior() else scene.vista.siguiente()
        )
    }

    /** Reemplaza la escena entera (al cargar de disco). Vacía el historial. */
    fun load(next: Scene) {
        scene = next
        selectedIds = emptySet()
        gesture = Gesture.None
        pendingTextId = null
        pendingScaleId = null
        pendingCotaId = null
        // El id de la caja a medio hacer es de la escena que se va; en la que
        // llega no significa nada.
        solidoPendiente = null
        history.clear()
    }

    // ---------------------------------------------------------------------
    // Interno
    // ---------------------------------------------------------------------

    private fun replace(element: Element) {
        scene = scene.copy(
            elements = scene.elements.map { if (it.id == element.id) element else it }
        )
    }

    /** Recalcula desde los originales del gesto, nunca desde el paso anterior. */
    private fun applyToOriginals(
        originals: List<Element>, transform: (List<Element>) -> List<Element>
    ) {
        val result = transform(originals).associateBy { it.id }
        scene = scene.copy(elements = scene.elements.map { result[it.id] ?: it })
    }

    private inline fun mutate(block: (List<Element>) -> List<Element>) {
        val before = scene.elements
        scene = scene.copy(elements = block(before))
        anotar(before)
    }

    /**
     * Anota un cambio ya hecho **y mueve con él la base del gesto**.
     *
     * Lo segundo es lo que impide que el mismo cambio entre dos veces en el
     * historial. Las herramientas de un toque —el punto, recortar, extender— se
     * cierran desde `pointerUp`, así que anotaban lo suyo y acto seguido
     * [commit] volvía a comparar contra los elementos de cuando bajó el dedo y
     * anotaba el mismo delta otra vez. Para quien dibuja eso es un deshacer que
     * no hace nada: el primero devuelve el punto y el segundo se come el gesto
     * sin que se mueva nada en la pantalla.
     */
    private fun anotar(before: List<Element>) {
        history.record(before, scene.elements)
        sceneAtGestureStart = scene.elements
    }

    private inline fun mutateSelected(block: (List<Element>) -> List<Element>) {
        mutate { elements ->
            val changed = block(elements.filter { it.id in selectedIds }).associateBy { it.id }
            elements.map { changed[it.id] ?: it }
        }
    }

    /** Anota en el historial lo ocurrido durante el gesto. */
    private fun commit() = anotar(sceneAtGestureStart)

    private companion object {
        /**
         * Por debajo de esto, lo dibujado se descarta.
         *
         * Un toque seco con la herramienta de rectángulo deja una forma de
         * tamaño cero: invisible, pero sigue ahí y roba los toques siguientes
         * al picar. Excalidraw hace lo mismo al soltar.
         */
        const val MIN_CREATED_SIZE = 2.0

        /** Lo que se deja alrededor de una hoja al encuadrarla. Ver [pasarDeHoja]. */
        const val MARGEN_DE_LA_HOJA = 24.0

        /** Opacidad del marcador: se tiene que ver lo subrayado por debajo. */
        const val HIGHLIGHTER_OPACITY = 40

        /** Por debajo de esto no se ha trazado nada sobre el transportador. */
        const val MIN_ARCO_BARRIDO = 0.05

        /** Radio del círculo de serie, en múltiplos del tamaño de fuente. */
        const val SERIAL_RADIUS = 0.9

        /**
         * Margen para agarrar una **raya fina** con el dedo, en px de pantalla.
         *
         * El de picar son diez y para esto se queda corto: una guía o una cota
         * son un pelo de un píxel de ancho, y fallar significa que en vez de
         * repasar el arco nace un círculo nuevo encima, o que recortar no
         * recorta nada. Veintiocho es más o menos lo que tapa la yema.
         */
        const val UMBRAL_GUIA = 28.0

        /**
         * Cuánto puede pasearse el dedo y seguir contando como un toque, en px
         * de pantalla. Más que esto es un arrastre, y un arrastre no dispara
         * ninguna herramienta de un toque.
         */
        const val TOQUE_QUIETO = 14.0

        /** Lo que hay que agrandar un óvalo para que su borde alcance el dedo. */
        val RAIZ_DE_DOS = kotlin.math.sqrt(2.0)
    }

    private fun elementTypeOf(t: Tool): ElementType = when (t) {
        Tool.RECTANGLE -> ElementType.RECTANGLE
        Tool.DIAMOND -> ElementType.DIAMOND
        Tool.ELLIPSE -> ElementType.ELLIPSE
        Tool.ARROW, Tool.FLECHA_LIBRE -> ElementType.ARROW
        Tool.LINE -> ElementType.LINE
        Tool.FREEDRAW, Tool.HIGHLIGHTER -> ElementType.FREEDRAW
        Tool.TEXT -> ElementType.TEXT
        Tool.IMAGE -> ElementType.IMAGE
        Tool.MOSAIC -> ElementType.MOSAIC
        Tool.LUPA -> ElementType.LUPA
        Tool.SERIAL -> ElementType.SERIAL
        Tool.FRAME -> ElementType.FRAME
        Tool.ESCALA_GRAFICA -> ElementType.ESCALA_GRAFICA
        Tool.SOLIDO -> ElementType.SOLIDO
        Tool.CRONOGRAMA -> ElementType.CRONOGRAMA
        // Escalar dibuja una cota como cualquier otra: la diferencia no está en
        // lo que se traza, sino en que al soltarla se pregunta cuánto mide.
        Tool.MEASURE, Tool.SCALE -> ElementType.MEASURE
        else -> ElementType.RECTANGLE
    }

    /**
     * El número que le toca al siguiente círculo de serie.
     *
     * Se cuenta **sobre la escena** y no en un contador aparte: así deshacer,
     * borrar o cargar un dibujo de disco dejan la numeración donde debe estar
     * sin tener que acordarse de nada. Se mira el mayor y se suma uno, en vez
     * de contar cuántos hay: borrando el 2 de tres círculos, el siguiente tiene
     * que ser el 4 y no otro 3.
     */
    private fun nextSerial(): Int =
        (scene.visible
            .filter { it.type == ElementType.SERIAL }
            .mapNotNull { it.text?.toIntOrNull() }
            .maxOrNull() ?: 0) + 1
}

/** Qué se está haciendo con el dedo apoyado. */
/** Cuánto se ensancha el toque con el modo dedo. Ver [DrawController.modoDedo]. */
private const val ENSANCHE_DEL_DEDO = 2.6

private sealed interface Gesture {
    data object None : Gesture
    data class Creating(val elementId: String) : Gesture
    data class Moving(val startPointer: Pt, val originals: List<Element>) : Gesture

    /**
     * La letra de un punto, dando vueltas alrededor de él.
     *
     * Gesto propio y no un `Moving` porque lo que se mueve **no es un
     * elemento**: es una propiedad suya, y el punto se queda donde está. Con un
     * `Moving` se habría arrastrado el punto entero al tirar de su letra, que es
     * justo lo contrario de lo que se quiere.
     */
    data class GirandoEtiqueta(val elementId: String) : Gesture

    /**
     * Arrastrando **a dónde mira** una lupa.
     *
     * Gesto propio por lo mismo que la letra del punto: lo que se mueve no es el
     * elemento sino una propiedad suya, y el cristal se tiene que quedar
     * exactamente donde está. Con un `Moving` se habría llevado la lupa entera.
     */
    data class MoviendoElFoco(
        val elementId: String, val start: Pt, val focoInicial: Pt
    ) : Gesture

    data class Resizing(val handle: HandleType, val originals: List<Element>) : Gesture
    /**
     * Arrastrando **una punta** de una flecha o una línea.
     *
     * Lleva el id y no el elemento porque lo que se mueve es un punto suyo: el
     * elemento se relee de la escena en cada fotograma, como en la creación.
     */
    data class MovingPoint(val elementId: String, val indice: Int) : Gesture

    /**
     * Trazando sobre el óvalo guía, como con un transportador.
     *
     * [anguloPrevio] es lo que permite acumular el barrido sin saltos al pasar
     * por el punto donde el ángulo cambia de signo. Ver [barridoAcumulado].
     */
    data class Arcoing(val elementId: String, var anguloPrevio: Double) : Gesture
    data class Rotating(val originals: List<Element>) : Gesture
    data class BoxSelecting(val origin: Pt, val mode: BoxSelectionMode) : Gesture
    data class Lassoing(val points: MutableList<Pt>) : Gesture
    /**
     * Encuadre con la herramienta de mano.
     *
     * [startPointer] va en coordenadas de **pantalla**, y es la única de las
     * gestiones que lo hace. El motivo: las de escena se calculan dividiendo
     * por el zoom y restando el desplazamiento, así que mientras se panea el
     * mismo punto de la pantalla va cambiando de coordenada de escena. Midiendo
     * contra una referencia que se mueve, el desplazamiento se realimentaba a
     * sí mismo cada fotograma y el lienzo temblaba en vez de seguir al dedo.
     */
    data class Panning(val startPointer: Pt, val startViewport: Viewport) : Gesture
    data object Erasing : Gesture

    /**
     * Herramienta de **un toque**: el bote, recortar y extender.
     *
     * Guarda dónde bajó el dedo y no hace nada hasta que se levanta. Es lo que
     * las separa de un pellizco para hacer zoom o del arranque de un paneo, que
     * empiezan exactamente igual — con un dedo posándose en la pantalla.
     */
    data class Tocando(val punto: Pt) : Gesture

    /**
     * Primera fase de la caja en volumen: **la huella, sobre el suelo**.
     *
     * Guarda dónde bajó el dedo porque ese punto es el ancla de la retícula
     * isométrica y el elemento no sirve para recordarlo: su `x`/`y` se recolocan
     * en cada fotograma según hacia dónde se arrastre. Es lo mismo que le pasa a
     * [DrawController.origenCreacion] con las formas planas.
     */
    data class Huella(val elementId: String, val origen: Pt) : Gesture

    /**
     * Segunda fase: **levantando la caja**.
     *
     * De [inicio] solo se usa la `y` para la altura —el porqué está en
     * `empezarSolido`— pero se guarda el punto entero porque la `x` sí hace falta
     * para lo otro: decidir si al soltar hubo arrastre o fue un toque, que es lo
     * que fija la altura.
     *
     * [alturaDePartida] es la que ya tenía, no cero: sin ella cada arrastre
     * empezaría desde el suelo y no se podría corregir una altura, solo volver a
     * ponerla desde el principio.
     */
    data class Levantando(
        val elementId: String, val inicio: Pt, val alturaDePartida: Double
    ) : Gesture

    /**
     * Corrigiendo una caja **ya hecha**, por uno de sus tiradores.
     *
     * Las dos fases de arriba son las de dibujarla y se acaban al soltar; esta es la de
     * despues, la que faltaba: cambiarle la planta o el alto sin volver a trazarla. Ver
     * [tiradoresDelSolido].
     */
    data class MoldeandoSolido(val elementId: String, val tirador: HandleType) : Gesture

    /**
     * Orientando volúmenes con uno de los dos anillos.
     *
     * Guarda el ángulo con el que empezó y los elementos tal como estaban: lo que se
     * aplica en cada fotograma es **el giro total desde que bajó el dedo**, no el de ese
     * fotograma. Acumulando paso a paso, el redondeo a quince grados se comería el resto
     * en cada vuelta y la pieza se quedaría corta. Ver [giradoEnElEspacio].
     */
    /**
     * Arrastrando una barra de un cronograma: se mueve o se estira.
     *
     * [agarre] es por dónde se cogió, en columnas desde su principio: sin él, la barra
     * pega un salto al empezar a moverla para ponerse con su origen bajo el dedo.
     */
    data class BarraDelPlan(
        val elementId: String,
        val indice: Int,
        val mano: ManoEnLaBarra,
        val agarre: Double
    ) : Gesture

    data class GirandoVolumen(
        val centro: Pt3,
        val eje: EjeDeGiro,
        val anguloInicial: Double,
        val originals: List<Element>
    ) : Gesture

    /**
     * Arrancando un clavo para volver a clavarlo en otro sitio.
     *
     * Va por índice y no por objeto porque el clavo se reescribe en cada
     * fotograma —su punto es el que sigue al dedo— y guardar el de antes lo
     * dejaría clavado donde estaba.
     */
    data class MovingPin(val indice: Int) : Gesture

    /** Pasando la bolita de seleccionar. Ver [Tool.BOLITA]. */
    data object PasandoLaBolita : Gesture
}

// -------------------------------------------------------------------------
// El dedo parado: la recta y el compás. Ver [DrawController.latido].
// -------------------------------------------------------------------------

/**
 * Cuánto hay que tener el dedo parado para que lo trazado salga limpio, en milisegundos.
 *
 * Medio segundo largo. Menos, y una raya trazada despacio se endereza sola en mitad de una
 * curva —que es peor que no tener el gesto—; más, y hay que esperar mirando el dedo.
 *
 * Es el mismo número que en el boceto en el espacio a propósito: **el gesto es el mismo**,
 * y un gesto que se cumple a distinto ritmo en dos sitios de la misma aplicación es dos
 * gestos que aprender.
 */
const val ESPERA_PARA_LA_RECTA = 550L

/**
 * Cuánto puede temblar el dedo, en píxeles de pantalla, sin dejar de estar parado.
 *
 * Ocho: un dedo apoyado nunca está quieto del todo, y con dos o tres el contador no llega a
 * cumplirse nunca en una mano normal.
 */
const val TEMBLOR_DEL_DEDO = 8.0

/**
 * Cuánto se le deja moverse al trazo, en píxeles de pantalla, para que siga siendo un
 * punto y no un recorrido — y por tanto un compás y no una recta.
 *
 * Lo que cabe en la yema: por debajo de eso nadie ha querido dibujar nada, ha querido
 * clavar la punta.
 */
const val LO_QUE_ES_UN_TOQUE = 14.0

/**
 * Lo gorda que es la bolita de seleccionar, en píxeles de pantalla.
 *
 * Treinta: lo que abarca la yema de un dedo. Más pequeña habría que apuntar, que es lo que
 * el gesto existe para no tener que hacer; más grande coge lo de al lado. Es el mismo
 * número que en el croquis en el espacio, porque es el mismo dedo.
 */
const val RADIO_DE_LA_BOLITA = 30.0
