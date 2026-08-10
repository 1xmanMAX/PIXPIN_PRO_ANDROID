package com.forge.pixpin.motor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/**
 * El lienzo.
 *
 * Traduce toques a coordenadas de escena y se los pasa a [DrawController], que
 * es quien decide qué pasa. Aquí solo queda el reparto de dedos y el pintado de
 * los adornos de la selección; toda la lógica está en el controlador, que se
 * puede probar sin dispositivo.
 *
 * **Un dedo dibuja, dos mueven la vista.** Es la regla que hace usable un
 * lienzo infinito con la mano: no hay tecla de espacio ni rueda del ratón, así
 * que el segundo dedo es lo único que queda para separar «dibujo» de «me
 * muevo». El controlador nunca llega a ver los gestos de dos dedos.
 */
@Composable
fun DrawCanvas(
    controller: DrawController,
    modifier: Modifier = Modifier,
    imageProvider: (String) -> Bitmap? = { null },
    /** Modo noche: filtro de pintado, no cambia el dibujo. Ver [DrawTheme]. */
    dark: Boolean = false,
    /**
     * Toque con dos dedos, sin arrastrar.
     *
     * Dos dedos ya estaban cogidos para encuadrar, pero **solo cuando se
     * mueven**. Posarlos y levantarlos sin más no significaba nada, y es un
     * gesto que la mano hace sola: sirve de atajo sin robarle sitio a ningún
     * otro.
     */
    onTwoFingerTap: () -> Unit = {},
    onChange: () -> Unit = {},
    /**
     * El contador de cambios de quien la hospeda. **Sin esto el lienzo se queda
     * congelado ante todo lo que no venga del dedo.**
     *
     * El controlador es estado mutable corriente, no estado de Compose, así que
     * lo único que obliga a repintar es leer algo que Compose vigile. Aquí
     * dentro había un contador propio que subía en cada toque, y con eso bastaba
     * mientras todo lo que cambiaba el dibujo pasase por el dedo. Pero deshacer,
     * esconder las guías, borrar o cambiar un color se tocan en la barra: el
     * contador de la pantalla subía, la barra se redibujaba… y este lienzo se
     * saltaba entero, porque ninguno de sus parámetros había cambiado y Compose
     * no recompone lo que se puede saltar.
     *
     * El efecto era desconcertante: dabas a deshacer y no pasaba nada, tocabas
     * el lienzo y **entonces** aparecía el cambio. Parecía que el botón no
     * funcionaba, cuando lo que no funcionaba era el repintado.
     */
    cambios: Int = 0,
    /**
     * Lo que hay debajo del dibujo, si se está anotando sobre una foto.
     *
     * Solo lo usa el mosaico, que no inventa píxeles: los coge de aquí. Su
     * píxel (0, 0) es el punto (0, 0) de la escena, así que vale cuando la
     * imagen ocupa la escena desde el origen y a tamaño natural — el caso del
     * pin de imagen abierto en la edición avanzada. Ver [Renderer].
     */
    backdrop: Bitmap? = null,
    /** Si [backdrop] es el papel sobre el que se dibuja y hay que pintarlo. */
    papelALaVista: Boolean = false,
    /**
     * Dónde se pone la lupa: en la esquina contraria a la mano que dibuja.
     *
     * Con la mano derecha, el dedo entra por la derecha y tapa lo que hay a su
     * izquierda… y viceversa. Poner la lupa en el lado por donde entra la mano
     * sería taparla con el brazo.
     */
    zurdo: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // El controlador es estado mutable corriente, no estado de Compose. Este
    // contador es lo que ata las dos cosas: se lee dentro del `Canvas`, así que
    // subirlo obliga a repintar. Convertir el controlador entero a estado de
    // Compose obligaría a copiar la escena en cada punto del lápiz.
    var tick by remember { mutableIntStateOf(0) }

    /**
     * Dónde está el dedo mientras dibuja, o null si no hay nadie tocando.
     *
     * **El dedo tapa justo el punto que estás colocando.** Es el problema más
     * viejo de dibujar con la mano en una pantalla: la punta exacta del trazo
     * queda debajo de la yema y no hay forma de ver dónde cae. La salida es no
     * mover nada de sitio —que sería mentir sobre dónde estás dibujando— sino
     * enseñar ese trozo aparte, ampliado, en una esquina que no tapa la mano.
     */
    var dedo by remember { mutableStateOf<Offset?>(null) }
    val renderer = remember(imageProvider, dark, backdrop, papelALaVista) {
        Renderer(imageProvider, DrawFonts.provider(context), dark, backdrop, papelALaVista)
    }

    fun touched() {
        tick++
        onChange()
    }

    Canvas(
        modifier = modifier.pointerInput(controller) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                var pointers = 1
                var gestureStarted = false

                val vp = controller.scene.viewport
                val start = vp.toScene(first.position.x.toDouble(), first.position.y.toDouble())
                controller.pointerDown(start, first.pressure.toDouble(), vp.zoom)
                gestureStarted = true
                dedo = first.position
                touched()

                var last = start
                // Para distinguir el TOQUE con dos dedos del encuadre: si los
                // dos dedos se posan y se levantan sin que nada se mueva ni se
                // escale, era un toque.
                var huboDosDedos = false
                var huboEncuadre = false
                var perfecta = false
                // Dónde se posó el segundo dedo. Lo que distingue «cuadra esta
                // figura» de «déjame mirar de cerca» es **cuánto se ha ido de
                // ahí**, y solo él. Ver el bloque de dos dedos.
                var segundoDedo: PointerId? = null
                var dondeSePuso = Offset.Zero

                while (true) {
                    val event = awaitPointerEvent()
                    val active = event.changes.filter { it.pressed }
                    if (active.isEmpty()) break

                    if (active.size > 1) {
                        // **El segundo dedo significa dos cosas distintas, y no
                        // se sabe cuál hasta que se mueve**: quieto pide figura
                        // perfecta —círculo redondo, cuadrado cuadrado, línea al
                        // eje—, y pellizcando pide encuadrar.
                        //
                        // Decidirlo al posarse, como se hacía, rompe una de las
                        // dos siempre. Dando por hecho que era encuadre no había
                        // forma de cuadrar una figura; dando por hecho que era
                        // figura perfecta —lo que se hizo después— **no había
                        // forma de hacer zoom con una herramienta puesta**, y al
                        // levantar un dedo el trazo continuaba desde donde
                        // hubiera quedado el otro: de ahí las rayas al azar.
                        //
                        // Así que se espera. Se cuadra la figura mientras tanto,
                        // que es lo reversible, y en cuanto los dedos se mueven
                        // de verdad se deshace y se pasa a encuadrar.
                        if (gestureStarted && controller.dibujando) {
                            if (!perfecta) {
                                perfecta = true
                                controller.keepAspectRatio = true
                                // Se repinta ya: la forma tiene que cuadrarse en
                                // cuanto el dedo se apoya, no al moverlo.
                                controller.pointerMove(
                                    last, 1.0, controller.scene.viewport.zoom
                                )
                                touched()
                            }
                            // **Y se sigue dibujando con el primer dedo.**
                            //
                            // Faltaba, y dejaba el gesto a medias: con el
                            // segundo dedo apoyado, el primero dejaba de mandar
                            // puntos y la forma se congelaba en el tamaño que
                            // tuviera al apoyarlo. Para hacer un círculo del
                            // tamaño que quieres hay que poder seguir abriendo
                            // **mientras** lo mantienes cuadrado.
                            val principal = active.firstOrNull { it.id == first.id }
                            if (principal != null && principal.positionChange() != Offset.Zero) {
                                val v = controller.scene.viewport
                                last = v.toScene(
                                    principal.position.x.toDouble(),
                                    principal.position.y.toDouble()
                                )
                                controller.pointerMove(last, principal.pressure.toDouble(), v.zoom)
                                dedo = principal.position
                                touched()
                            }
                            // **El pellizco se mide con el otro dedo y solo con
                            // él, y por dónde está, no por dónde ha pasado.**
                            //
                            // Aquí estaban los dos motivos por los que mantener
                            // el segundo dedo apoyado acababa siempre en zoom:
                            //
                            // 1. `calculateZoom()` mira **todos** los dedos, así
                            //    que el que está dibujando contaba. Y tiene que
                            //    moverse, que es como se abre la figura: en
                            //    cuanto el círculo crecía un poco, la separación
                            //    entre los dos dedos cambiaba, eso se leía como
                            //    pellizco y el trazo se cancelaba. Cuanto más
                            //    grande querías la figura, antes se rompía.
                            // 2. La deriva se **acumulaba** sumando cada
                            //    movimiento. Un dedo quieto no está quieto: el
                            //    digitalizador tiembla un poco, y esos temblores
                            //    se sumaban hasta cruzar el umbral solos. Bastaba
                            //    con mantenerlo apoyado unos segundos.
                            //
                            // Con la distancia neta desde donde se posó, un dedo
                            // quieto da cero por mucho que tiemble y por mucho
                            // que el otro dibuje, y uno que pellizca de verdad se
                            // pasa del umbral enseguida — porque para pellizcar
                            // hay que mover este dedo.
                            val otro = active.firstOrNull { it.id != first.id }
                            if (otro != null && segundoDedo != otro.id) {
                                segundoDedo = otro.id
                                dondeSePuso = otro.position
                            }
                            val deriva =
                                if (otro == null) 0f
                                else (otro.position - dondeSePuso).getDistance()
                            if (deriva <= DERIVA_ENCUADRE.dp.toPx()) {
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            // Era un encuadre: lo que llevara trazado se va con
                            // él. Un trozo de raya suelto porque el usuario
                            // quería mirar de cerca no es un dibujo.
                            perfecta = false
                            controller.keepAspectRatio = false
                        }
                        // Ha aparecido un segundo dedo: lo que llevara empezado
                        // el controlador se cancela, porque el usuario no quería
                        // dibujar sino encuadrar.
                        if (gestureStarted) {
                            controller.cancel()
                            gestureStarted = false
                        }
                        huboDosDedos = true
                        pointers = active.size
                        val zoom = event.calculateZoom()
                        if (zoom != 1f || event.calculatePan() != Offset.Zero) {
                            huboEncuadre = true
                            controller.setViewport(
                                zoomAnchored(
                                    controller.scene.viewport,
                                    factor = zoom,
                                    from = event.calculateCentroid(useCurrent = false),
                                    to = event.calculateCentroid(useCurrent = true)
                                )
                            )
                            touched()
                        }
                        event.changes.forEach { it.consume() }
                        continue
                    }

                    // Se ha levantado el dedo de la figura perfecta: se sigue
                    // trazando a mano suelta, sin cortar nada. Es lo que permite
                    // enderezar un tramo y seguir, que es como se usa.
                    if (perfecta) {
                        perfecta = false
                        controller.keepAspectRatio = false
                        touched()
                    }

                    if (pointers > 1) {
                        // Se ha vuelto a un solo dedo tras encuadrar. No se
                        // reanuda el dibujo: el dedo que queda está donde acabó
                        // el pellizco, no donde el usuario quiere empezar.
                        event.changes.forEach { it.consume() }
                        continue
                    }

                    val change = active.first()
                    // **Solo dibuja el dedo que empezó.** Si se levanta él y
                    // queda otro apoyado, seguir con ese haría que el trazo
                    // pegara un salto hasta donde estuviera: una raya que nadie
                    // ha hecho, aparecida de la nada al soltar el pellizco.
                    if (change.id != first.id) {
                        change.consume()
                        continue
                    }
                    if (change.positionChange() != Offset.Zero && gestureStarted) {
                        val v = controller.scene.viewport
                        last = v.toScene(
                            change.position.x.toDouble(), change.position.y.toDouble()
                        )
                        controller.pointerMove(last, change.pressure.toDouble(), v.zoom)
                        dedo = change.position
                        touched()
                    }
                    change.consume()
                }

                dedo = null
                // El modificador no sobrevive al gesto: si no, la siguiente
                // forma nacería cuadrada sin que nadie lo haya pedido.
                if (perfecta) controller.keepAspectRatio = false
                if (gestureStarted) {
                    controller.pointerUp(last, controller.scene.viewport.zoom)
                    touched()
                }
                // Dos dedos que se posan y se levantan sin mover nada: atajo.
                if (huboDosDedos && !huboEncuadre) onTwoFingerTap()
            }
        }
    ) {
        @Suppress("UNUSED_EXPRESSION") tick
        // Y el de fuera: es lo que trae aquí los cambios hechos desde la barra.
        @Suppress("UNUSED_EXPRESSION") cambios

        val scene = controller.scene
        drawIntoCanvas { canvas ->
            renderer.renderScene(
                canvas.nativeCanvas, scene,
                size.width.toDouble(), size.height.toDouble()
            )
        }

        // Los adornos de la selección van en coordenadas de PANTALLA, no de
        // escena: así el grosor de la línea y el tamaño del tirador no cambian
        // con el zoom, que es justo lo que se busca.
        val vp = scene.viewport
        val selected = controller.selectedElements()

        if (selected.isNotEmpty()) {
            // **Una raya sola no lleva caja.** Sus puntas ya dicen dónde
            // empieza y dónde acaba, y el rectángulo alrededor de una diagonal
            // solo añade cuatro bordes donde no hay nada dibujado. Es como se
            // ve en el original.
            val soloUnaRaya = selected.size == 1 && selected.first().isLinear
            // **Y un punto tampoco.** Su caja mide cero, así que el rectángulo
            // salía como un recuadro diminuto encima del propio punto: ruido
            // justo sobre lo que se está intentando ver. Un punto seleccionado
            // ya se distingue porque su letra es lo único que hay ahí.
            val soloUnPunto = selected.size == 1 && selected.first().type == ElementType.PUNTO
            if (!soloUnaRaya && !soloUnPunto) {
                val bounds = getCommonBounds(selected)
                val tl = vp.toScreen(Pt(bounds.x1, bounds.y1))
                val br = vp.toScreen(Pt(bounds.x2, bounds.y2))
                drawRect(
                    color = SELECTION_COLOR,
                    topLeft = Offset(tl.x.toFloat(), tl.y.toFloat()),
                    size = Size((br.x - tl.x).toFloat(), (br.y - tl.y).toFloat()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            // Con los clavos: donde hay uno **no se pinta el tirador**. El
            // controlador ya no lo tiene en cuenta para el toque, pero aquí se
            // seguía pintando, así que se veía la bolita blanca encima del punto
            // rojo y parecía que seguía mandando ella.
            val handles = getSelectionTransformHandles(
                selected, vp.zoom, alfileres = controller.scene.alfileres.map { it.punto }
            )
            for (h in handles) {
                val c = vp.toScreen(Pt(h.centerX, h.centerY))
                val r = (h.width * vp.zoom / 2).toFloat()
                val centro = Offset(c.x.toFloat(), c.y.toFloat())
                // El de «añadir punto» va hueco: si se viera igual que un punto
                // de verdad, no habría forma de saber cuáles se pueden borrar.
                if (h.type != HandleType.POINT_ADD) {
                    drawCircle(Color.White, r, centro)
                }
                drawCircle(
                    SELECTION_COLOR, r, centro,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // **Los vértices soldados: puntitos y nada más.**
        //
        // Van del tamaño de una cabeza de alfiler y en rojo. Lo primero porque
        // están **siempre** a la vista mientras se dibuja, así que cualquier
        // cosa más grande sería un sarpullido encima del dibujo; lo segundo
        // porque es la única marca del lienzo que no puede confundirse con nada
        // trazado —el resto de adornos van en el morado de la selección— y
        // porque lo que dice es «esto está sujeto», que conviene ver de un
        // vistazo cuando algo se mueve y no entiendes por qué.
        //
        // En coordenadas de pantalla, como los tiradores: se ven igual a
        // cualquier aumento. Y solo aquí, en el editor: al exportar no salen.
        for (nudo in controller.nudosVisibles()) {
            val s = vp.toScreen(nudo)
            drawCircle(NUDO_COLOR, NUDO_RADIO.dp.toPx(), Offset(s.x.toFloat(), s.y.toFloat()))
        }

        // Los ángulos de las juntas, **solo mientras dura el gesto**. Es la
        // guía del nivel de burbuja: se enseña mientras colocas y desaparece en
        // cuanto sueltas, porque un dibujo con todos sus ángulos escritos no se
        // lee. Ver [angulosInternos].
        for (a in controller.angulosDelGesto()) {
            val v = vp.toScreen(a.vertice)
            val r = ANGULO_RADIO.dp.toPx()
            val etiqueta = "${a.grados.toInt()}°"
            drawIntoCanvas { canvas ->
                val pincel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                // El arco del ángulo, del tamaño de una uña y en el mismo morado
                // que el resto de los adornos: es andamio, no dibujo.
                pincel.style = android.graphics.Paint.Style.STROKE
                pincel.strokeWidth = 1.5.dp.toPx()
                pincel.color = SELECTION_COLOR.value.toLong().let {
                    android.graphics.Color.argb(200, 0x69, 0x65, 0xDB)
                }
                canvas.nativeCanvas.drawCircle(v.x.toFloat(), v.y.toFloat(), r, pincel)

                // El número **dentro** del ángulo, en su bisectriz: fuera se lee
                // como si fuera del ángulo de al lado.
                val tx = v.x + kotlin.math.cos(a.bisectriz) * r * ANGULO_TEXTO
                val ty = v.y + kotlin.math.sin(a.bisectriz) * r * ANGULO_TEXTO
                pincel.textSize = ANGULO_LETRA.dp.toPx()
                pincel.textAlign = android.graphics.Paint.Align.CENTER
                // Halo blanco primero: sobre un trazo oscuro, el número solo no
                // se lee, y es justo encima de un trazo donde siempre cae.
                pincel.style = android.graphics.Paint.Style.STROKE
                pincel.strokeWidth = 3.dp.toPx()
                pincel.color = android.graphics.Color.WHITE
                canvas.nativeCanvas.drawText(etiqueta, tx.toFloat(), ty.toFloat(), pincel)
                pincel.style = android.graphics.Paint.Style.FILL
                pincel.color = android.graphics.Color.argb(255, 0x69, 0x65, 0xDB)
                canvas.nativeCanvas.drawText(etiqueta, tx.toFloat(), ty.toFloat(), pincel)
            }
        }

        controller.selectionBox?.let { b ->
            val tl = vp.toScreen(Pt(b.x1, b.y1))
            val br = vp.toScreen(Pt(b.x2, b.y2))
            val topLeft = Offset(tl.x.toFloat(), tl.y.toFloat())
            val boxSize = Size((br.x - tl.x).toFloat(), (br.y - tl.y).toFloat())
            drawRect(SELECTION_FILL, topLeft, boxSize)
            drawRect(SELECTION_COLOR, topLeft, boxSize, style = Stroke(width = 1.dp.toPx()))
        }

        val lasso = controller.lassoPath
        if (lasso.size > 1) {
            for (i in 0 until lasso.size - 1) {
                val a = vp.toScreen(lasso[i])
                val b = vp.toScreen(lasso[i + 1])
                drawLine(
                    SELECTION_COLOR,
                    Offset(a.x.toFloat(), a.y.toFloat()),
                    Offset(b.x.toFloat(), b.y.toFloat()),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        // La lupa: el trozo que el dedo está tapando, ampliado y aparte.
        dedo?.let { pos ->
            val lado = LUPA_DP.dp.toPx()
            val margen = 8.dp.toPx()

            // **La lupa va pegada al dedo, no en una esquina fija.** Mirar a la
            // otra punta de la pantalla mientras se coloca un punto obliga a
            // apartar la vista de lo que se está haciendo, y con el dedo quieto
            // en el sitio bueno la lupa quedaba lejísimos.
            //
            // Sale en diagonal hacia arriba y **al lado contrario de la mano**:
            // ahí es donde no llega ni la yema ni el resto del dedo, que ocupa
            // bastante más que el punto de contacto. Con la derecha, arriba a la
            // izquierda; con la izquierda, arriba a la derecha.
            val hueco = lado * LUPA_SEPARACION
            val bruto = Offset(
                if (zurdo) pos.x + hueco else pos.x - hueco,
                pos.y - hueco
            )
            // Y sin salirse: pegada a un borde se vería media luna.
            val destino = Offset(
                bruto.x.coerceIn(margen + lado / 2, size.width - margen - lado / 2),
                bruto.y.coerceIn(margen + lado / 2, size.height - margen - lado / 2)
            )

            val recorte = Path().apply {
                addOval(
                    androidx.compose.ui.geometry.Rect(
                        destino.x - lado / 2, destino.y - lado / 2,
                        destino.x + lado / 2, destino.y + lado / 2
                    )
                )
            }
            clipPath(recorte) {
                drawRect(LUPA_FONDO, Offset(destino.x - lado / 2, destino.y - lado / 2), Size(lado, lado))
                drawIntoCanvas { canvas ->
                    // Que el punto bajo el dedo caiga en el centro de la lupa:
                    // el renderizador pinta en `(p + scroll) · zoom`, así que
                    // el desplazamiento sale de despejar esa igualdad.
                    val z = vp.zoom * LUPA_AUMENTO
                    val enEscena = vp.toScene(pos.x.toDouble(), pos.y.toDouble())
                    renderer.renderScene(
                        canvas.nativeCanvas,
                        scene.copy(
                            viewport = Viewport(
                                scrollX = destino.x / z - enEscena.x,
                                scrollY = destino.y / z - enEscena.y,
                                zoom = z
                            )
                        ),
                        size.width.toDouble(), size.height.toDouble()
                    )
                }
                // La cruz marca el punto exacto, que es lo que se viene a ver.
                drawLine(
                    SELECTION_COLOR,
                    Offset(destino.x - 8f, destino.y), Offset(destino.x + 8f, destino.y),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    SELECTION_COLOR,
                    Offset(destino.x, destino.y - 8f), Offset(destino.x, destino.y + 8f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            drawCircle(
                SELECTION_COLOR, lado / 2, destino,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // La forma a la que se va a enganchar la flecha se resalta antes de
        // soltar: sin este aviso, el anclaje ocurre por sorpresa.
        controller.bindingHighlight?.let { id ->
            scene.byId(id)?.let { e ->
                val b = getElementBounds(e)
                val tl = vp.toScreen(Pt(b.x1, b.y1))
                val br = vp.toScreen(Pt(b.x2, b.y2))
                drawRect(
                    BINDING_COLOR,
                    Offset(tl.x.toFloat() - 4f, tl.y.toFloat() - 4f),
                    Size((br.x - tl.x).toFloat() + 8f, (br.y - tl.y).toFloat() + 8f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

/**
 * El dibujo dentro de un pin flotante: **solo se mira**.
 *
 * Se encuadra el contenido y se pinta sin gestos propios, para que el toque lo
 * siga repartiendo el manejador del pin y copiar funcione igual que con una
 * imagen. El lienzo infinito no tiene sentido en una ventana de dos dedos de
 * ancho: eso vive en el editor.
 */
@Composable
fun DrawPreview(
    scene: Scene,
    modifier: Modifier = Modifier,
    imageProvider: (String) -> Bitmap? = { null }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember(imageProvider) { Renderer(imageProvider, DrawFonts.provider(context)) }
    Canvas(modifier) {
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        if (w <= 0 || h <= 0) return@Canvas

        // **Con hoja, el pin enseña la hoja**; sin ella, todo lo dibujado. Es la
        // razón de ser del marco: tener sitio de sobra donde trabajar en el
        // editor y que el pin siga enseñando solo el trozo que importa.
        val contenido = scene.contenidoVisible
        val marco = scene.marco
        val encuadre = if (marco != null) {
            val b = getElementBounds(marco)
            val zoom = minOf(w / b.width, h / b.height)
            Viewport(
                scrollX = w / (2 * zoom) - b.midX,
                scrollY = h / (2 * zoom) - b.midY,
                zoom = zoom
            )
        } else {
            fitToContent(scene.elements, w, h, padding = 8.0)
        }

        drawIntoCanvas { canvas ->
            renderer.renderScene(
                canvas.nativeCanvas,
                scene.copy(elements = contenido, viewport = encuadre),
                w, h
            )
        }
    }
}

/**
 * Zoom anclado al punto entre los dedos.
 *
 * Lo que hay que conservar es que **el trozo de dibujo que había bajo el
 * centro de los dedos siga estando ahí** al terminar el gesto. Escalar y luego
 * sumar el desplazamiento por separado —que es lo que hacía antes— ancla el
 * zoom en el origen del lienzo, así que el dibujo se escapa de debajo de la
 * mano en cuanto no estás mirando justo al centro de la pantalla.
 *
 * De `screen = (scene + scroll) · zoom` se despeja el punto de escena que hay
 * bajo [from], y se pide que ese mismo punto caiga bajo [to] con el zoom nuevo.
 */
internal fun zoomAnchored(v: Viewport, factor: Float, from: Offset, to: Offset): Viewport {
    val newZoom = (v.zoom * factor).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
    return v.copy(
        scrollX = to.x / newZoom - from.x / v.zoom + v.scrollX,
        scrollY = to.y / newZoom - from.y / v.zoom + v.scrollY,
        zoom = newZoom
    )
}

private val SELECTION_COLOR = Color(0xFF6965DB)

/**
 * El punto de un vértice soldado: rojo y **diminuto**.
 *
 * Dos y medio de radio es una cabeza de alfiler: se ve si lo buscas y no
 * estorba mientras dibujas, que es la única forma de que una marca permanente
 * sea soportable.
 */
private val NUDO_COLOR = Color(0xFFE03131)
private const val NUDO_RADIO = 2.5f

/** El arco del ángulo, dónde cae su número y de qué tamaño, en dp. */
private const val ANGULO_RADIO = 16f
private const val ANGULO_TEXTO = 1.55f
private const val ANGULO_LETRA = 11f
private val SELECTION_FILL = Color(0x186965DB)
private val BINDING_COLOR = Color(0xFF1971C2)

/** Lado de la lupa, en dp: lo justo para ver el punto sin comerse el lienzo. */
private const val LUPA_DP = 96

/** Cuánto amplía. Tres veces es donde un trazo fino se ve sin marearse. */
private const val LUPA_AUMENTO = 3.0

/**
 * A cuánto se separa del dedo, en múltiplos de su propio lado.
 *
 * Menos de uno la taparía la mano: el dedo no es un punto, es un óvalo de más
 * de un centímetro, y detrás va el resto del dedo y la mano.
 */
private const val LUPA_SEPARACION = 1.15f

/** Detrás va el color del papel: si no, se vería el lienzo a través. */
private val LUPA_FONDO = Color.White

/**
 * Cuánto tiene que irse el segundo dedo **de donde se posó** para que la cosa
 * deje de ser «cuadra esta figura» y pase a ser «déjame encuadrar», en dp.
 *
 * Se mide en distancia neta desde su sitio, no sumando lo que se mueve: un dedo
 * apoyado nunca está del todo quieto y el digitalizador entrega temblores de un
 * píxel constantemente, así que sumándolos el umbral se cruzaba solo con el dedo
 * parado. En neto, temblar no cuenta: para pasarse hay que ir a alguna parte.
 *
 * Veinte dp es medio centímetro. Deja apoyar el dedo con holgura —y recolocarlo
 * un poco, que la mano lo hace sola— sin que el trazo se cancele, y sigue siendo
 * mucho menos de lo que se abre la mano al pellizcar de verdad.
 */
private const val DERIVA_ENCUADRE = 20
