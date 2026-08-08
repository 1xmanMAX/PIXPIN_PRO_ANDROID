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
        tool = next
        if (next != Tool.SELECTION && next != Tool.LASSO) selectedIds = emptySet()
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
    fun pointerDown(pRaw: Pt, pressure: Double = 1.0, zoom: Double = 1.0) {
        sceneAtGestureStart = scene.elements
        val threshold = DEFAULT_HIT_THRESHOLD / zoom
        // Solo se engancha al DIBUJAR. Al seleccionar, mover o encuadrar el
        // dedo tiene que ir donde va: tirar de él ahí sería un estorbo.
        val p = if (tool.isShape || tool.isLinear || tool.isFreehand) {
            anclajeActivo = buscarAnclaje(
                scene.visibleConReferencias, pRaw, zoom,
                if (tool.isFreehand) engancheLapiz else enganche,
                extra = if (tool.isFreehand) emptyList() else
                    anclajesDeTablas(scene.tablas, scene.origenCoordenadas, scene.escala) +
                        anclajesDelEje(
                            scene.origenCoordenadas, pRaw,
                            radio = enganche.radio / zoom.coerceAtLeast(0.0001),
                            activo = enganche.eje
                        )
            )
            anclajeActivo?.punto ?: pRaw
        } else {
            anclajeActivo = null
            pRaw
        }

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

            Tool.RECTANGLE, Tool.DIAMOND, Tool.MOSAIC, Tool.SPOTLIGHT,
            Tool.FRAME, Tool.ESCALA_GRAFICA -> beginCreate(elementTypeOf(tool), p)

            Tool.ARROW, Tool.LINE, Tool.MEASURE, Tool.SCALE ->
                beginCreateLinear(elementTypeOf(tool), p)

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
                        strokeWidth = e.strokeWidth * HIGHLIGHTER_WIDTH
                    )
                }
                scene = scene.copy(elements = scene.elements + e)
                gesture = Gesture.Creating(e.id)
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
            Tool.RELLENO, Tool.RECORTAR, Tool.EXTENDER, Tool.NUDO ->
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
            Tool.TEXT -> plantarTexto(inicio, DEFAULT_HIT_THRESHOLD / z)
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
        val existente = getElementAtPosition(editables, p, threshold)
            ?.takeIf { it.type == ElementType.TEXT && !it.locked }
        if (existente != null) {
            selectedIds = emptySet()
            pendingTextId = existente.id
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
    fun pointerMove(pRaw: Pt, pressure: Double = 1.0, zoom: Double = 1.0) {
        // **También al recolocar un punto de algo ya dibujado.** El imán servía
        // solo mientras se trazaba, y corregir después la punta de una flecha
        // para que cayera justo en una esquina había que hacerlo a pulso, que
        // es precisamente lo que el imán existe para evitar.
        val enCurso = (gesture as? Gesture.Creating)?.elementId
            ?: (gesture as? Gesture.MovingPoint)?.elementId
        val moviendoPunto = gesture is Gesture.MovingPoint
        val engancha = enCurso != null &&
            (moviendoPunto || tool.isShape || tool.isLinear || tool.isFreehand)
        val p = if (engancha) {
            // Al recolocar un punto ya dibujado mandan los ajustes de siempre,
            // aunque la herramienta puesta sea el lápiz: lo que se está haciendo
            // es afinar una punta, no trazar a mano.
            val aMano = tool.isFreehand && !moviendoPunto
            anclajeActivo = buscarAnclaje(
                scene.visibleConReferencias, pRaw, zoom,
                if (aMano) engancheLapiz else enganche, excluir = enCurso,
                extra = if (aMano) emptyList() else
                    anclajesDeTablas(scene.tablas, scene.origenCoordenadas, scene.escala) +
                        anclajesDelEje(
                            scene.origenCoordenadas, pRaw,
                            radio = enganche.radio / zoom.coerceAtLeast(0.0001),
                            activo = enganche.eje
                        )
            )
            anclajeActivo?.punto ?: pRaw
        } else {
            anclajeActivo = null
            pRaw
        }
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
                val destino = buscarAnclaje(
                    scene.visibleConReferencias.filter { it.id !in suyas },
                    pRaw, zoom, enganche
                )?.also { anclajeActivo = it }?.punto ?: p
                scene = scene.copy(
                    elements = moverAlfiler(scene.elements, scene.alfileres, alfiler, destino),
                    alfileres = scene.alfileres.mapIndexed { i, a ->
                        if (i == g.indice) a.copy(punto = destino) else a
                    }
                )
            }

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
                selectedIds = getElementsWithinSelection(editables, b, g.mode)
                    .map { it.id }.toSet()
            }

            is Gesture.Creating -> updateCreating(g.elementId, p, pressure)

            is Gesture.Arcoing -> {
                val e = scene.byId(g.elementId) ?: return
                // **El arco también se imanta.** Un arco se traza casi siempre
                // para llegar hasta algo —donde la guía corta a otra figura, un
                // vértice, el punto que ya marcaste—, y a pulso ese final queda
                // pasado o corto por unos grados que luego cantan. El enganche
                // se busca sobre el punto crudo y el ángulo se saca de ahí: da
                // igual que el anclaje no caiga exactamente sobre el óvalo,
                // porque lo que se usa es la dirección desde su centro.
                val destino = buscarAnclaje(
                    scene.visibleConReferencias, pRaw, zoom, enganche, excluir = g.elementId
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
                replace(e.withPointMovedTo(g.indice, destino).touched())
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
                        escena.elements, escena.alfileres, salvo = g.elementId
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

            is Gesture.Moving -> {
                val dx = p.x - g.startPointer.x
                val dy = p.y - g.startPointer.y
                // **Cada figura se mueve según lo que sus clavos le dejen.**
                // Sin clavos sigue al dedo; con uno gira alrededor de él; con
                // dos no se mueve. Ver [Libertad]: es la ley entera del alfiler,
                // y es lo que convierte el clavo en un eje de verdad en vez de
                // en un pegamento que arrastra el conjunto en bloque.
                applyToOriginals(g.originals) {
                    if (scene.alfileres.isEmpty()) dragElements(it, dx, dy)
                    else arrastrarConAlfileres(it, scene.alfileres, g.startPointer, p)
                }
                scene = scene.copy(
                    alfileres = refrescarAlfileres(scene.elements, scene.alfileres)
                )
                seguirConLasFlechas()
            }

            is Gesture.Resizing -> {
                applyToOriginals(g.originals) { originals ->
                    if (originals.size == 1) {
                        listOf(resizeSingleElement(originals[0], g.handle, p, keepAspectRatio, resizeFromCenter))
                    } else {
                        resizeMultipleElements(originals, g.handle, p)
                    }
                }
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
        when (val g = gesture) {
            is Gesture.Tocando -> terminarToque(g.punto, p, zoom)

            is Gesture.Lassoing -> {
                selectedIds = getElementsWithinLasso(editables, g.points, DEFAULT_HIT_THRESHOLD / zoom)
                    .map { it.id }.toSet()
                lassoPath = emptyList()
            }

            is Gesture.BoxSelecting -> selectionBox = null

            is Gesture.Creating -> finishCreating(g.elementId, zoom)

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
            is Gesture.Moving, is Gesture.Resizing, is Gesture.Rotating -> Unit

            else -> Unit
        }
        gesture = Gesture.None
        bindingHighlight = null
        commit()
    }

    /** Cancela el gesto en curso y deshace lo que llevaba hecho. */
    fun cancel() {
        if (gesture !is Gesture.None) scene = scene.copy(elements = sceneAtGestureStart)
        gesture = Gesture.None
        selectionBox = null
        lassoPath = emptyList()
        bindingHighlight = null
    }

    // ---------------------------------------------------------------------
    // Modificadores
    // ---------------------------------------------------------------------

    /** Conservar proporción al redimensionar y trazar recto al dibujar. */
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

    /**
     * Con qué engancha el lápiz: **solo con el canto de las guías**.
     *
     * Se deriva de [enganche] para que apagar el imán lo apague también aquí, y
     * respeta que las guías se puedan desactivar por su cuenta. El porqué de que
     * el lápiz no enganche a lo demás, en [AjustesEnganche.SOLO_GUIAS].
     */
    private val engancheLapiz: AjustesEnganche
        get() = AjustesEnganche.SOLO_GUIAS.copy(
            activo = enganche.activo,
            bordeDeGuia = enganche.bordeDeGuia,
            radio = enganche.radio
        )

    /** El punto al que se está enganchando ahora, para que la vista lo señale. */
    var anclajeActivo: Anclaje? = null
        private set

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
    private fun finishCreating(id: String, zoom: Double) {
        val e = scene.byId(id) ?: return

        val tooSmall = !e.isFreeDraw && !e.isLinear && e.width < MIN_CREATED_SIZE &&
            e.height < MIN_CREATED_SIZE
        val degenerate = e.isLinear && absolutePoints(e).let { pts ->
            pts.size < 2 ||
                kotlin.math.hypot(pts.last().x - pts.first().x, pts.last().y - pts.first().y) <
                MIN_CREATED_SIZE
        }
        if (tooSmall || degenerate) {
            scene = scene.copy(elements = scene.elements.filter { it.id != id })
            return
        }

        var finished = e
        if (e.type == ElementType.ARROW) {
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
        if (tool == Tool.MEASURE && finished.isMeasure) pendingCotaId = finished.id

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

        val selected = selectedElements()

        // 1. ¿Un tirador? Tiene prioridad sobre todo lo demás: está encima de
        //    la forma y si perdiera, redimensionar sería imposible.
        if (selected.isNotEmpty()) {
            val handles = getSelectionTransformHandles(
                selected, zoom, alfileres = scene.alfileres.map { it.punto }
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
                    hit.type.esPunto -> Gesture.MovingPoint(
                        selected.first().id,
                        hit.indice ?: 0
                    )

                    else -> Gesture.Resizing(hit.type, selected)
                }
                return
            }
        }

        // 2. ¿Un elemento? Se selecciona y empieza a moverse.
        val hit = getElementAtPosition(editables, p, threshold)
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

    fun deleteSelection() = mutate { deleteSelected(it, selectedIds).also { selectedIds = emptySet() } }
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
        history.record(before, scene.elements)
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

    /** Cambia el texto de un elemento de texto (lo que escribe el teclado). */
    fun updateText(id: String, text: String, measuredWidth: Double, measuredHeight: Double) {
        mutate { elements ->
            elements.map {
                if (it.id == id) {
                    it.copy(text = text, width = measuredWidth, height = measuredHeight).touched()
                } else it
            }
        }
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
        history.record(before, scene.elements)
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

    fun setViewport(v: Viewport) {
        scene = scene.copy(viewport = v)
    }

    /** Reemplaza la escena entera (al cargar de disco). Vacía el historial. */
    fun load(next: Scene) {
        scene = next
        selectedIds = emptySet()
        gesture = Gesture.None
        pendingTextId = null
        pendingScaleId = null
        pendingCotaId = null
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
        history.record(before, scene.elements)
    }

    private inline fun mutateSelected(block: (List<Element>) -> List<Element>) {
        mutate { elements ->
            val changed = block(elements.filter { it.id in selectedIds }).associateBy { it.id }
            elements.map { changed[it.id] ?: it }
        }
    }

    /** Anota en el historial lo ocurrido durante el gesto. */
    private fun commit() {
        history.record(sceneAtGestureStart, scene.elements)
        sceneAtGestureStart = scene.elements
    }

    private companion object {
        /**
         * Por debajo de esto, lo dibujado se descarta.
         *
         * Un toque seco con la herramienta de rectángulo deja una forma de
         * tamaño cero: invisible, pero sigue ahí y roba los toques siguientes
         * al picar. Excalidraw hace lo mismo al soltar.
         */
        const val MIN_CREATED_SIZE = 2.0

        /** Opacidad del marcador: se tiene que ver lo subrayado por debajo. */
        const val HIGHLIGHTER_OPACITY = 40

        /** Cuánto engorda el marcador respecto al lápiz. */
        const val HIGHLIGHTER_WIDTH = 5.0

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
        Tool.ARROW -> ElementType.ARROW
        Tool.LINE -> ElementType.LINE
        Tool.FREEDRAW, Tool.HIGHLIGHTER -> ElementType.FREEDRAW
        Tool.TEXT -> ElementType.TEXT
        Tool.IMAGE -> ElementType.IMAGE
        Tool.MOSAIC -> ElementType.MOSAIC
        Tool.SPOTLIGHT -> ElementType.SPOTLIGHT
        Tool.SERIAL -> ElementType.SERIAL
        Tool.FRAME -> ElementType.FRAME
        Tool.ESCALA_GRAFICA -> ElementType.ESCALA_GRAFICA
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
private sealed interface Gesture {
    data object None : Gesture
    data class Creating(val elementId: String) : Gesture
    data class Moving(val startPointer: Pt, val originals: List<Element>) : Gesture
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
     * Arrancando un clavo para volver a clavarlo en otro sitio.
     *
     * Va por índice y no por objeto porque el clavo se reescribe en cada
     * fotograma —su punto es el que sigue al dedo— y guardar el de antes lo
     * dejaría clavado donde estaba.
     */
    data class MovingPin(val indice: Int) : Gesture
}
