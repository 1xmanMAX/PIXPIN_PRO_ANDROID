package com.forge.pixpin.motor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.roundToInt

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
    /**
     * Algo ha cambiado. **Y si fue con el dedo todavía puesto, se dice.**
     *
     * Quien hospeda esto tiene una barra, unos paneles y unos botones que enseñan lo que
     * hay —qué herramienta, qué color, si se puede deshacer— y todo eso se rehace cuando se
     * le avisa. Pero un trazo avisa **en cada punto**: doscientas veces por segundo se
     * rehacía la pantalla entera del editor para que la barra siguiera diciendo exactamente
     * lo mismo. Eso es el tirón que se siente al trazar rápido, y crece con lo que haya en
     * la barra.
     *
     * El lienzo no necesita ese aviso para repintarse —se repinta solo, ver [tick]—, así
     * que con el dedo puesto lo único que hace falta es apuntar que hay que guardar. La
     * barra se pone al día al levantar el dedo, que es cuando puede haber cambiado algo
     * suyo.
     */
    onChange: (mientrasSeTraza: Boolean) -> Unit = {},
    /**
     * El zoom está clavado: dos dedos siguen desplazando, pero no acercan.
     *
     * Hace falta trabajando de cerca sobre un detalle: la mano que dibuja apoya,
     * el segundo dedo roza, y el acercamiento se va sin querer. Se pierde el
     * encuadre que costó encontrar. Panear se deja porque eso no se pierde: se
     * vuelve arrastrando lo mismo que se fue.
     */
    zoomBloqueado: Boolean = false,
    /**
     * Las figuras salen perfectas sin apoyar el segundo dedo.
     *
     * El gesto de los dos dedos se queda —es el bueno para un círculo suelto—,
     * pero con esto puesto el modificador **no se apaga al levantarlos**: se
     * vuelve aquí, y no a falso. Sin eso, el primer trazo con el segundo dedo
     * apagaba el interruptor sin que nadie lo hubiera tocado.
     */
    figurasPerfectas: Boolean = false,
    /**
     * **Solo el lápiz dibuja**; el dedo mueve el papel.
     *
     * Se enciende solo al ver el primer toque de stylus —quien tiene lápiz
     * dibuja con el lápiz— pero **se puede apagar**, y ese era el fallo: sin
     * interruptor, perder el lápiz dejaba el lienzo bloqueado para siempre, sin
     * forma de volver a anotar con el dedo. Lo decide quien hospeda; aquí solo
     * se obedece. Ver [onLapizDetectado].
     */
    modoLapiz: Boolean = false,
    /** Ha entrado un toque de lápiz: quien hospeda decide si enciende el modo. */
    onLapizDetectado: () -> Unit = {},
    /**
     * Se ha tocado el lienzo, con lo que sea. Es lo que cierra lo que flote encima —la
     * ventana de ajustes— en cuanto uno vuelve al dibujo.
     */
    onTocar: () -> Unit = {},
    /**
     * **Los colores del gesto rápido.** Con un dedo apoyado, el lápiz que se posa no traza:
     * abre esta tira sobre su punta y eligiendo a un lado y a otro cambia el color. Vacía,
     * el gesto no existe. Ver [alApoyarseElLapiz].
     */
    coloresRapidos: List<String> = emptyList(),
    /** Se ha elegido un color con el gesto rápido. */
    onColorRapido: (String) -> Unit = {},
    /**
     * **El atajo de la pantalla completa.** Sin barra a la vista, con un dedo apoyado en
     * el lienzo el lápiz que se posa abre el **abanico** —lápiz, borrador, color, grosor—
     * en vez de trazar. Fuera de la pantalla completa se queda lo de siempre: un dedo es
     * el color y dos dedos, el borrador. Lo mismo, y con más seguridad, se pide desde el
     * botón flotante: ver [agarre] y [MenuRapido].
     */
    pantallaCompleta: Boolean = false,
    /** Se ha elegido una herramienta en el abanico: el lápiz o el borrador. */
    onHerramientaRapida: (Tool) -> Unit = {},
    /** Se ha elegido un grosor: uno de los lápices hechos, o jalando arriba y abajo. */
    onGrosorRapido: (Double) -> Unit = {},
    /**
     * **El agarre del botón flotante.** Mientras haya dedos apretándolo —o acaben de
     * levantarse—, lo que se pose en el lienzo no traza: abre el abanico. Y mientras se
     * mantenga, cada lápiz que se pose lo vuelve a abrir. Vale cualquier puntero, no solo
     * el lápiz, que el «lápiz» de muchos aparatos es capacitivo. Ver [AgarreDelBoton].
     */
    agarre: AgarreDelBoton? = null,
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
    /**
     * **El plano grande por trozos**, si el papel es de los que no caben en una imagen.
     * Ver [ElMosaicoDelPapel].
     */
    mosaico: ElMosaicoDelPapel? = null,
    /**
     * **El PDF a la resolución de la pantalla**, para leerlo de cerca: los cuadros del
     * mosaico son una imagen fija y pasado su aumento se ven estirados. Ver [LaminaDeCerca].
     */
    laminaFina: LaminaDeCerca? = null,
    /**
     * **El papel leído como líneas**, cuando el plano se abrió así. Ver [PlanoEnPantalla].
     * Con esto puesto no se pinta ni el mosaico ni la lámina: no hacen falta.
     */
    planoVectorial: PlanoEnPantalla? = null,
    /** Si [backdrop] es el papel sobre el que se dibuja y hay que pintarlo. */
    papelALaVista: Boolean = false,
    /**
     * Dónde se pone la lupa: en la esquina contraria a la mano que dibuja.
     *
     * Con la mano derecha, el dedo entra por la derecha y tapa lo que hay a su
     * izquierda… y viceversa. Poner la lupa en el lado por donde entra la mano
     * sería taparla con el brazo.
     */
    zurdo: Boolean = false,
    /** El fondo pautado. Solo aquí: no viaja en el archivo. Ver [Cuadricula]. */
    cuadricula: Cuadricula = Cuadricula.NINGUNA
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

    /** La tira de colores del gesto rápido, mientras el lápiz la recorre. Ver [coloresRapidos]. */
    var selectorRapido by remember { mutableStateOf<SelectorRapido?>(null) }
    /** El pincel de los rótulos del menú rápido. Uno, no uno por fotograma. */
    val pincelDeRotulos = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    /** El menú rápido que hay en pantalla —el abanico, la rueda del color, el grosor—. Ver [MenuRapido]. */
    var menu by remember { mutableStateOf<MenuRapido?>(null) }
    /** Los iconos del abanico, uno por opción de [OpcionDelAbanico], hechos una vez. */
    val iconosDelAbanico = listOf(
        rememberVectorPainter(Icons.Filled.Gesture),
        rememberVectorPainter(Icons.Filled.AutoFixNormal),
        rememberVectorPainter(Icons.Filled.Palette),
        rememberVectorPainter(Icons.Filled.Category),
        rememberVectorPainter(Icons.Filled.RadioButtonUnchecked),
        rememberVectorPainter(Icons.Filled.Remove),
        rememberVectorPainter(Icons.Filled.NorthEast),
        rememberVectorPainter(Icons.Filled.CropSquare),
        rememberVectorPainter(Icons.Outlined.Diamond)
    )

    /**
     * **Al dedo apoyado se le lleva la hora**, mientras dura un trazo a mano alzada.
     *
     * No se mide dentro del movimiento del dedo porque un dedo quieto de verdad **no manda
     * movimientos**: el gesto de pararse —la recta y el compás— no salía justo cuando se
     * hacía bien. Late un fotograma sí y otro también mientras hay algo en la mano, y ni un
     * fotograma cuando no lo hay: con el lienzo quieto aquí no corre nada.
     *
     * Y solo se repinta **si el latido ha cambiado algo**. Subiendo el contador en cada
     * pulso se repintaría el lienzo sesenta veces por segundo durante todo el trazo para
     * enseñar exactamente lo mismo. Ver [DrawController.latido].
     */
    var trazandoAMano by remember { mutableStateOf(false) }
    LaunchedEffect(trazandoAMano) {
        while (trazandoAMano) {
            withFrameNanos { }
            if (controller.latido(android.os.SystemClock.uptimeMillis())) {
                tick++
                onChange(true)
            }
        }
    }


    val renderer =
        remember(
            imageProvider, dark, backdrop, mosaico, laminaFina, planoVectorial, papelALaVista,
            cuadricula
        ) {
            // **Va por nombre y no por orden**: este constructor tiene ya ocho parámetros del
            // mismo tipo nullable y colocarlos de memoria es la forma de que el papel acabe
            // de telón y el telón de papel sin que el compilador diga nada.
            Renderer(
                imageProvider = imageProvider,
                typefaces = DrawFonts.provider(context),
                dark = dark,
                backdrop = backdrop,
                mosaico = mosaico,
                laminaFina = laminaFina,
                planoVectorial = planoVectorial,
                papelALaVista = papelALaVista,
                cuadricula = cuadricula
            )
        }

    fun touched(mientrasSeTraza: Boolean = false) {
        tick++
        onChange(mientrasSeTraza)
    }

    Canvas(
        // **El candado del zoom va en la llave, y por eso no funcionaba.**
        //
        // `pointerInput` no se reinicia al recomponer: se reinicia cuando cambia
        // una de sus llaves. Con `controller` como única llave, el bloque de
        // abajo seguía corriendo con el valor de `zoomBloqueado` que tenía
        // cuando arrancó —falso, siempre— y el candado no se enteraba nunca de
        // que lo habían echado. Se veía como un botón que se enciende y no hace
        // nada, que es lo peor que puede hacer un botón.
        modifier = modifier.pointerInput(controller, zoomBloqueado, figurasPerfectas, modoLapiz) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                var pointers = 1
                var gestureStarted = false

                if (first.type == PointerType.Stylus) onLapizDetectado()
                val esLapiz = first.type == PointerType.Stylus
                onTocar()

                /**
                 * **Elegir en un abanico.** Se sigue a ESTE puntero —lápiz o dedo, que el
                 * botón flotante vale con cualquier cosa que toque el lienzo—: la opción es
                 * hacia dónde se lo lleva desde el centro, y se elige al levantar. Sin salir
                 * del centro muerto, o llevándolo fuera del arco, no se elige nada.
                 */
                suspend fun AwaitPointerEventScope.elegirEnElAbanico(
                    primero: PointerInputChange, abierto: MenuRapido.Abanico
                ): OpcionDelAbanico? {
                    val quien = primero.id
                    val centro = abierto.centro
                    val muerto = RADIO_MUERTO_DEL_ABANICO.dp.toPx()
                    val n = abierto.opciones.size
                    fun opcionDe(pos: Offset): Int {
                        val d = pos - centro
                        if (d.getDistance() < muerto) return -1
                        val angulo = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat()
                        var mejor = -1
                        var cerca = ARCO_DEL_ABANICO / n / 2 + HOLGURA_DEL_ABANICO
                        for (i in 0 until n) {
                            var dif = abs(angulo - anguloDelAbanico(i, n))
                            if (dif > 180f) dif = 360f - dif
                            if (dif < cerca) { cerca = dif; mejor = i }
                        }
                        return mejor
                    }
                    var elegida = opcionDe(primero.position)
                    menu = MenuRapido.Abanico(centro, abierto.opciones, elegida)
                    touched()
                    primero.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pluma = ev.changes.firstOrNull { it.id == quien && it.pressed }
                        ev.changes.forEach { it.consume() }
                        if (pluma == null) break
                        elegida = opcionDe(pluma.position)
                        menu = MenuRapido.Abanico(centro, abierto.opciones, elegida)
                        touched()
                    }
                    menu = null
                    touched()
                    return abierto.opciones.getOrNull(elegida)
                }

                /**
                 * **Elegir en la rueda del color**, la misma del panel: el tono es el ángulo
                 * y la viveza, lo lejos del centro. Se aplica al levantar.
                 */
                suspend fun AwaitPointerEventScope.elegirEnLaRueda(
                    primero: PointerInputChange, abierta: MenuRapido.Color
                ) {
                    val quien = primero.id
                    val centro = abierta.centro
                    val disco = RADIO_DE_LA_RUEDA.dp.toPx()
                    var hex: String? = null
                    fun senalar(pos: Offset) {
                        val d = pos - centro
                        val lejos = d.getDistance()
                        val tono = ((Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())) + 360.0) % 360.0).toFloat()
                        val viveza = (lejos / disco).coerceIn(0f, 1f)
                        hex = enTexto(deHsv(floatArrayOf(tono, viveza, abierta.claridad)))
                        // Fuera del disco se sigue por el borde: apurar el tono más vivo no
                        // puede pedir puntería.
                        val punto = if (lejos <= disco || lejos == 0f) pos else centro + d * (disco / lejos)
                        menu = MenuRapido.Color(centro, abierta.tonos, abierta.claridad, punto, hex)
                        touched()
                    }
                    senalar(primero.position)
                    primero.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pluma = ev.changes.firstOrNull { it.id == quien && it.pressed }
                        ev.changes.forEach { it.consume() }
                        if (pluma == null) break
                        senalar(pluma.position)
                    }
                    menu = null
                    touched()
                    hex?.let(onColorRapido)
                }

                /**
                 * **El abanico principal**, centrado donde se posa el lápiz. Lo que se elija
                 * o se hace ya (el borrador) o deja abierto lo siguiente —los lápices
                 * hechos, la rueda del color, el grosor— para el siguiente toque.
                 */
                suspend fun AwaitPointerEventScope.abrirElAbanico(primero: PointerInputChange, porMemoria: Boolean) {
                    val centro = primero.position
                    val abanico = MenuRapido.Abanico(centro, OPCIONES_DEL_ABANICO, -1)
                    when (elegirEnElAbanico(primero, abanico)) {
                        OpcionDelAbanico.BORRADOR -> onHerramientaRapida(Tool.ERASER)
                        OpcionDelAbanico.LAPIZ -> {
                            menu = MenuRapido.Abanico(centro, LAPICES_DEL_ABANICO, -1)
                            touched()
                        }
                        OpcionDelAbanico.FIGURAS -> {
                            menu = MenuRapido.Abanico(centro, FIGURAS_DEL_ABANICO, -1)
                            touched()
                        }
                        OpcionDelAbanico.COLOR -> {
                            val estilo = controller.estiloActivo()
                            // Con la claridad de la tinta puesta, lo que se ve es lo que sale;
                            // y con un suelo, que con tinta negra el disco saldría negro.
                            val claridad = enHsv(parseColor(estilo.strokeColor, 255))[2].coerceAtLeast(CLARIDAD_MINIMA)
                            menu = MenuRapido.Color(centro, tonosDeLaRueda(claridad), claridad, null, null)
                            touched()
                        }
                        // Sin dedo a la vista y sin elegir nada: eso era un trazo que se
                        // quería dar, no un menú. Se apaga para que el siguiente sí trace.
                        null -> if (porMemoria) agarre?.apagar()
                        else -> {}
                    }
                }

                /**
                 * **Lo que toca el lienzo con un menú abierto lo maneja**: elige en los
                 * lápices hechos, en la rueda del color o jala el grosor. Tocar lejos del
                 * menú lo cierra sin más, y ese toque no traza.
                 */
                suspend fun AwaitPointerEventScope.manejarElMenu(primero: PointerInputChange, abierto: MenuRapido) {
                    val lejos = (primero.position - abierto.centro).getDistance()
                    when (abierto) {
                        is MenuRapido.Abanico -> {
                            if (lejos > RADIO_EXTERIOR_DEL_ABANICO.dp.toPx() * 1.4f) {
                                menu = null; touched(); primero.consume(); return
                            }
                            val opcion = elegirEnElAbanico(primero, abierto) ?: return
                            opcion.tool?.let(onHerramientaRapida)
                            LAPICES_DEL_ABANICO.indexOf(opcion).takeIf { it >= 0 }?.let {
                                onGrosorRapido(GROSORES_DE_LOS_LAPICES[it])
                            }
                        }
                        is MenuRapido.Color -> {
                            if (lejos > RADIO_DE_LA_RUEDA.dp.toPx() * 1.35f) {
                                menu = null; touched(); primero.consume(); return
                            }
                            elegirEnLaRueda(primero, abierto)
                        }
                    }
                }

                // **Un menú abierto se atiende antes que nada**: lo dejó el abanico para el
                // siguiente toque, y puede que el dedo del botón ya no esté.
                menu?.let { abierto ->
                    manejarElMenu(first, abierto)
                    agarre?.renovar(android.os.SystemClock.uptimeMillis())
                    return@awaitEachGesture
                }

                // **El botón flotante manda.** Con dedos apretándolo —o armado, que muchos
                // teléfonos cancelan el dedo en cuanto el lápiz se acerca—, lo que se posa en
                // el lienzo no traza: abre el abanico. Ver [AgarreDelBoton].
                if (agarre?.agarrar(first.uptimeMillis) == true) {
                    abrirElAbanico(first, porMemoria = agarre.dedos == 0)
                    agarre.renovar(android.os.SystemClock.uptimeMillis())
                    return@awaitEachGesture
                }

                // **Con lápiz a la vista, el dedo mueve el papel.**
                //
                // Un dedo solo panea y dos siguen haciendo pellizco, pero
                // ninguno de los dos dibuja: dibuja el lápiz. Así se puede
                // apoyar la mano, recolocar el dibujo y seguir trazando sin
                // cambiar de modo ni tocar ningún botón.
                // **El texto se escribe con el dedo, aunque haya lápiz.**
                //
                // Poner un texto no es trazar: es tocar un sitio y teclear, y con
                // el modo lápiz puesto ese toque se lo comía el paneo — no había
                // forma de colocar un texto sin sacar el lápiz. La herramienta de
                // texto es la excepción, y solo ella.
                val elDedoEscribe = controller.tool == Tool.TEXT
                if (modoLapiz && !esLapiz && !elDedoEscribe) {
                    var previo = first.position
                    var dedos = 1
                    first.consume()
                    while (true) {
                        val evento = awaitPointerEvent()
                        val activos = evento.changes.filter { it.pressed }
                        if (activos.isEmpty()) break
                        // **El lápiz que se posa con un dedo apoyado es un atajo.**
                        //
                        // Con un dedo, el lápiz no traza: abre la tira de colores sobre su
                        // punta y se elige arrastrando. Con dos dedos, el lápiz **borra**
                        // mientras los dedos sigan ahí, y al soltarlos vuelve la
                        // herramienta que había. Es lo que evita ir a la barra cada vez
                        // que hay que corregir un trazo o cambiar de tinta: la mano que no
                        // dibuja ya está sobre la pantalla, y con ella se pide.
                        val lapiz = activos.firstOrNull { it.type == PointerType.Stylus }
                        if (lapiz != null) {
                            val apoyados = activos.count { it.type != PointerType.Stylus }
                            evento.changes.forEach { it.consume() }
                            // **Recorrer una tira con el lápiz**: la casilla sale de cuánto se ha
                            // ido a un lado desde donde se posó (y, si hay filas, de cuánto ha
                            // subido). Devuelve (casilla, fila) al levantar el lápiz.
                            suspend fun AwaitPointerEventScope.recorrer(
                                ancla: Offset, clase: ClaseDelSelector,
                                pasoX: Float, n: Int, i0: Int,
                                pasoY: Float = 0f, m: Int = 1, f0: Int = 0
                            ): Pair<Int, Int> {
                                var indice = i0
                                var fila = f0
                                selectorRapido = SelectorRapido(ancla, indice, clase, fila, f0, i0)
                                touched()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pluma = ev.changes.firstOrNull {
                                        it.type == PointerType.Stylus && it.pressed
                                    }
                                    ev.changes.forEach { it.consume() }
                                    if (pluma == null) break
                                    indice = ((pluma.position.x - ancla.x) / pasoX + i0)
                                        .roundToInt().coerceIn(0, n - 1)
                                    if (m > 1 && pasoY > 0f) {
                                        fila = ((ancla.y - pluma.position.y) / pasoY + f0)
                                            .roundToInt().coerceIn(0, m - 1)
                                    }
                                    selectorRapido = SelectorRapido(ancla, indice, clase, fila, f0, i0)
                                    touched()
                                }
                                selectorRapido = null
                                touched()
                                return indice to fila
                            }
                            when {
                                // Pantalla completa: con un dedo apoyado el lápiz abre el
                                // abanico, como con el botón flotante pero apoyando en el lienzo.
                                pantallaCompleta -> {
                                    val abierto = menu
                                    if (abierto != null) manejarElMenu(lapiz, abierto) else abrirElAbanico(lapiz, false)
                                    continue
                                }
                                apoyados == 1 && coloresRapidos.isNotEmpty() -> {
                                    val n = coloresRapidos.size
                                    val (indice, _) = recorrer(
                                        lapiz.position, ClaseDelSelector.COLORES,
                                        PASO_DEL_SELECTOR.dp.toPx(), n, n / 2
                                    )
                                    onColorRapido(coloresRapidos[indice])
                                    continue
                                }
                                apoyados >= 2 -> {
                                    val deAntes = controller.tool
                                    controller.selectTool(Tool.ERASER)
                                    val v0 = controller.scene.viewport
                                    var ultimo = v0.toScene(
                                        lapiz.position.x.toDouble(), lapiz.position.y.toDouble()
                                    )
                                    controller.pointerDown(
                                        ultimo, lapiz.pressure.toDouble(), v0.zoom, lapiz.uptimeMillis
                                    )
                                    touched(mientrasSeTraza = true)
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val pluma = ev.changes.firstOrNull {
                                            it.type == PointerType.Stylus && it.pressed
                                        }
                                        ev.changes.forEach { it.consume() }
                                        if (pluma == null) break
                                        val v = controller.scene.viewport
                                        ultimo = v.toScene(
                                            pluma.position.x.toDouble(), pluma.position.y.toDouble()
                                        )
                                        controller.pointerMove(
                                            ultimo, pluma.pressure.toDouble(), v.zoom, pluma.uptimeMillis
                                        )
                                        touched(mientrasSeTraza = true)
                                    }
                                    controller.pointerUp(ultimo, controller.scene.viewport.zoom)
                                    controller.selectTool(deAntes)
                                    touched()
                                    continue
                                }
                                else -> break
                            }
                        }
                        val v = controller.scene.viewport
                        // Igual que arriba: el fotograma en que entra o sale un
                        // dedo no mueve nada, o el lienzo pega un brinco.
                        if (activos.size != dedos) {
                            dedos = activos.size
                            previo = activos.first().position
                            evento.changes.forEach { it.consume() }
                            continue
                        }
                        if (activos.size > 1) {
                            val factor = if (zoomBloqueado) 1f else evento.calculateZoom()
                            controller.setViewport(
                                zoomAnchored(
                                    v,
                                    factor = factor,
                                    from = evento.calculateCentroid(useCurrent = false),
                                    to = evento.calculateCentroid(useCurrent = true)
                                )
                            )
                        } else {
                            val ahora = activos.first().position
                            controller.setViewport(
                                v.copy(
                                    scrollX = v.scrollX + (ahora.x - previo.x) / v.zoom,
                                    scrollY = v.scrollY + (ahora.y - previo.y) / v.zoom
                                )
                            )
                            previo = ahora
                        }
                        evento.changes.forEach { it.consume() }
                        touched(mientrasSeTraza = true)
                    }
                    dedo = null
                    return@awaitEachGesture
                }

                val vp = controller.scene.viewport
                val start = vp.toScene(first.position.x.toDouble(), first.position.y.toDouble())
                // Cuándo y dónde empezó, para saber luego si fue una pulsación
                // larga: es la puerta de atrás de lo clavado. Ver más abajo.
                val cuandoEmpezo = first.uptimeMillis
                var seMovio = false
                controller.pointerDown(
                    start, first.pressure.toDouble(), vp.zoom, first.uptimeMillis
                )
                // Mientras haya un trazo a mano en la mano, hay que llevarle la hora: es
                // lo que endereza la raya y clava el compás. Ver [DrawController.latido].
                trazandoAMano = controller.tool.isFreehand
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

                var ultimoToque = cuandoEmpezo
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { c ->
                        ultimoToque = c.uptimeMillis
                        if ((c.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                            seMovio = true
                        }
                    }
                    // **Rechazo de palma.** Trazando con el lápiz, la mano
                    // apoyada entra como un dedo más, y hasta ahora eso se leía
                    // como el segundo dedo del pellizco: el trazo se cancelaba
                    // solo por apoyar la mano, que es como se escribe. Con el
                    // lápiz en la pantalla, los dedos no existen.
                    val active = event.changes.filter { it.pressed }
                        .let { if (esLapiz) it.filter { c -> c.type == PointerType.Stylus } else it }
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
                                touched(mientrasSeTraza = true)
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
                                controller.pointerMove(
                                    last, principal.pressure.toDouble(), v.zoom,
                                    principal.uptimeMillis
                                )
                                dedo = principal.position
                                touched(mientrasSeTraza = true)
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
                            controller.keepAspectRatio = figurasPerfectas
                        }
                        // Ha aparecido un segundo dedo: lo que llevara empezado
                        // el controlador se cancela, porque el usuario no quería
                        // dibujar sino encuadrar.
                        if (gestureStarted) {
                            controller.cancel()
                            gestureStarted = false
                        }
                        huboDosDedos = true
                        // **El fotograma en que cambia el número de dedos no
                        // mueve nada.**
                        //
                        // El centroide de «antes» se calcula con los dedos que
                        // había y el de «ahora» con los que hay: al levantar uno,
                        // el punto de referencia salta del medio de los dos a la
                        // yema del que queda, y esa diferencia se aplicaba tal
                        // cual como si fuera un desplazamiento. Era el brinco del
                        // dibujo justo al soltar el pellizco, y pasaba también al
                        // apoyar el segundo dedo, solo que ahí no se notaba
                        // porque el gesto acababa de empezar.
                        //
                        // Con los dedos entrando y saliendo no hay movimiento que
                        // medir: se anota cuántos hay y se espera al siguiente.
                        val cambiaronLosDedos = active.size != pointers
                        pointers = active.size
                        val zoom = if (zoomBloqueado) 1f else event.calculateZoom()
                        if (cambiaronLosDedos) {
                            event.changes.forEach { it.consume() }
                            continue
                        }
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
                            touched(mientrasSeTraza = true)
                        }
                        event.changes.forEach { it.consume() }
                        continue
                    }

                    // Se ha levantado el dedo de la figura perfecta: se sigue
                    // trazando a mano suelta, sin cortar nada. Es lo que permite
                    // enderezar un tramo y seguir, que es como se usa.
                    if (perfecta) {
                        perfecta = false
                        controller.keepAspectRatio = figurasPerfectas
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
                        controller.pointerMove(
                            last, change.pressure.toDouble(), v.zoom, change.uptimeMillis
                        )
                        dedo = change.position
                        touched(mientrasSeTraza = true)
                    }
                    change.consume()
                }

                dedo = null
                trazandoAMano = false
                // El modificador no sobrevive al gesto: si no, la siguiente
                // forma nacería cuadrada sin que nadie lo haya pedido.
                if (perfecta) controller.keepAspectRatio = figurasPerfectas
                if (gestureStarted) {
                    controller.pointerUp(last, controller.scene.viewport.zoom)
                    touched()
                }

                // **La pulsación larga rescata lo clavado.**
                //
                // Una imagen con el candado no responde al toque —para eso se
                // clava— así que ponerlo era una decisión sin vuelta: no había
                // forma de volver a cogerla ni de quitárselo. Aguantar el dedo
                // encima la marca igualmente, que es el gesto de «sé lo que
                // estoy haciendo» de siempre, y con ella marcada el botón del
                // candado abierto ya la suelta.
                val fueLarga = !seMovio && !huboDosDedos &&
                    (ultimoToque - cuandoEmpezo) >= MILIS_DE_PULSACION_LARGA
                if (fueLarga && controller.tool == Tool.SELECTION &&
                    controller.selectedIds.isEmpty()
                ) {
                    if (controller.marcarClavadoEn(start, UMBRAL_DEL_DEDO / vp.zoom)) touched()
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
        // Lo marcado, para lo que solo tiene sentido mientras se toca: el
        // contorno de lo que mira una lupa. Ver [Renderer.marcados].
        renderer.marcados = controller.selectedIds
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
            // **Las aristas de detrás, a trazos.**
            //
            // Es el recurso de dibujo técnico de siempre: se insinúa lo que la propia
            // caja tapa. Solo con la caja seleccionada —dibujadas siempre serían un
            // emborronado sobre el croquis— y ahí sirven además para otra cosa: dos de
            // sus tiradores viven en la esquina de atrás, y sin las aristas parecen
            // flotar sobre el vacío. Ver [aristasOcultasDeElemento].
            val aTrazos = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(4.dp.toPx(), 4.dp.toPx())
            )
            for ((a, b) in controller.aristasOcultasDeLaSeleccion()) {
                val pa = vp.toScreen(a)
                val pb = vp.toScreen(b)
                drawLine(
                    SELECTION_COLOR.copy(alpha = 0.55f),
                    Offset(pa.x.toFloat(), pa.y.toFloat()),
                    Offset(pb.x.toFloat(), pb.y.toFloat()),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = aTrazos
                )
            }

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
                // **Lo que se ve, no la caja del elemento.** Un arco recortado guarda
                // el óvalo del que salió, así que con `getCommonBounds` el recuadro
                // salía enorme y casi vacío alrededor de una uña de trazo. Ver
                // [envolturaVisible].
                val bounds = envolturaComun(selected, scene.vista)
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
                selected, vp.zoom, vista = scene.vista,
                alfileres = controller.scene.alfileres.map { it.punto }
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

        // **Los dos anillos con los que se orienta un volumen.**
        //
        // El horizontal lo hace girar de pie sobre el suelo y el vertical lo tumba. Cada
        // uno se dibuja en su plano del mundo y luego se proyecta, así que el primero sale
        // como el rombo achatado de la isométrica y el segundo, de canto: eso solo ya
        // cuenta cuál hace qué sin tener que explicarlo. Ver [anilloDelVolumen].
        for ((eje, aro) in controller.anillosDelVolumen()) {
            if (aro.size < 2) continue
            val camino = androidx.compose.ui.graphics.Path()
            for ((i, punto) in aro.withIndex()) {
                val v = vp.toScreen(punto)
                if (i == 0) camino.moveTo(v.x.toFloat(), v.y.toFloat())
                else camino.lineTo(v.x.toFloat(), v.y.toFloat())
            }
            drawPath(
                camino,
                SELECTION_COLOR.copy(alpha = if (eje == EjeDeGiro.EN_PLANTA) 0.75f else 0.45f),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // **La figura que espera eje**, con el torno a medio gesto.
        //
        // Un gesto de dos tiempos sin marca es una aplicación que no responde: se toca la
        // figura, no pasa nada visible, y uno vuelve a tocar. Ver [Tool.REVOLUCION].
        controller.figuraATornear?.let { id ->
            scene.byId(id)?.let { pendiente ->
                val b = envolturaVisible(pendiente, scene.vista)
                val tl = vp.toScreen(Pt(b.x1, b.y1))
                val br = vp.toScreen(Pt(b.x2, b.y2))
                drawRect(
                    SELECTION_COLOR.copy(alpha = 0.9f),
                    Offset(tl.x.toFloat() - 4f, tl.y.toFloat() - 4f),
                    Size((br.x - tl.x).toFloat() + 8f, (br.y - tl.y).toFloat() + 8f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                        )
                    )
                )
            }
        }

        // **El vértice al que se está apoyando la caja que se mueve.**
        //
        // Sin señal, el imán de los volúmenes es magia: la caja salta y no se sabe a qué.
        // Con el punto marcado se ve a qué esquina se ha pegado, que además es lo que
        // dice a qué altura ha quedado apoyada. Ver [imanEntreSolidos].
        controller.apoyoActivo?.let { punto ->
            val v = vp.toScreen(punto)
            val r = APOYO_RADIO.dp.toPx()
            drawCircle(Color.White, r, Offset(v.x.toFloat(), v.y.toFloat()))
            drawCircle(
                SELECTION_COLOR, r, Offset(v.x.toFloat(), v.y.toFloat()),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // **Lo que mide la caja, mientras se está poniendo.**
        //
        // En cuadros, que es como se lee una proporción. Ver [DrawController.medidasDeLaCaja].
        controller.medidasDeLaCaja()?.let { (donde, texto) ->
            val v = vp.toScreen(donde)
            drawIntoCanvas { canvas ->
                val pincel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                pincel.textSize = MEDIDA_LETRA.dp.toPx()
                pincel.textAlign = android.graphics.Paint.Align.CENTER
                val y = (v.y - MEDIDA_SEPARACION.dp.toPx()).toFloat()
                // Halo blanco primero: el rótulo cae justo encima del dibujo.
                pincel.style = android.graphics.Paint.Style.STROKE
                pincel.strokeWidth = 3.dp.toPx()
                pincel.color = android.graphics.Color.WHITE
                canvas.nativeCanvas.drawText(texto, v.x.toFloat(), y, pincel)
                pincel.style = android.graphics.Paint.Style.FILL
                pincel.color = android.graphics.Color.argb(255, 0x69, 0x65, 0xDB)
                canvas.nativeCanvas.drawText(texto, v.x.toFloat(), y, pincel)
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

        // **La bolita, en la pantalla y no en el dibujo**: es un gesto sobre el cristal, no
        // una figura de la escena, así que mide lo mismo a cualquier aumento. Se pinta
        // porque sin verla uno no sabe cuánto abarca el barrido y coge de más. Ver
        // [Tool.BOLITA].
        controller.bolita?.let { b ->
            val en = vp.toScreen(b)
            val donde = Offset(en.x.toFloat(), en.y.toFloat())
            val r = RADIO_DE_LA_BOLITA.toFloat()
            drawCircle(SELECTION_FILL, radius = r, center = donde)
            drawCircle(SELECTION_COLOR, radius = r, center = donde, style = Stroke(width = 2f))
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

        // La tira de colores del gesto rápido, sobre la punta del lápiz.
        selectorRapido?.let { s ->
            val paso = PASO_DEL_SELECTOR.dp.toPx()
            val n = coloresRapidos.size
            val y = s.ancla.y - ALTURA_DEL_SELECTOR.dp.toPx()
            val x0 = s.ancla.x - s.indiceBase * paso
            // Un lomo detrás, para que se lea sobre cualquier dibujo.
            drawRoundRect(
                Color.Black.copy(alpha = 0.45f),
                topLeft = Offset(x0 - paso * 0.6f, y - paso * 0.55f),
                size = androidx.compose.ui.geometry.Size(paso * (n - 1) + paso * 1.2f, paso * 1.1f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(paso * 0.55f)
            )
            for ((i, hex) in coloresRapidos.withIndex()) {
                val centro = Offset(x0 + i * paso, y)
                val r = (if (i == s.indice) paso * 0.42f else paso * 0.28f)
                drawCircle(Color(parseColor(hex)), r, centro)
                drawCircle(Color.White, r, centro, style = Stroke(width = if (i == s.indice) 3f else 1.5f))
            }
        }

        // **El menú rápido.** El abanico: media corona sobre el punto con un icono por
        // opción (o, en los lápices hechos, un punto de cada grosor) y la elegida en
        // claro. El color: la misma rueda del panel con el punto bajo el lápiz y la tinta
        // elegida en el aro. Ver [MenuRapido].
        menu?.let { m ->
            when (m) {
                is MenuRapido.Abanico -> {
                    val n = m.opciones.size
                    val r1 = RADIO_MUERTO_DEL_ABANICO.dp.toPx() * 1.6f
                    val r2 = RADIO_EXTERIOR_DEL_ABANICO.dp.toPx()
                    val rm = (r1 + r2) / 2
                    val paso = ARCO_DEL_ABANICO / n
                    val inicio = -90f - ARCO_DEL_ABANICO / 2 - HOLGURA_DEL_ABANICO
                    val caja = Offset(m.centro.x - rm, m.centro.y - rm)
                    val lado = androidx.compose.ui.geometry.Size(rm * 2, rm * 2)
                    drawArc(
                        Color.Black.copy(alpha = 0.6f), inicio, ARCO_DEL_ABANICO + HOLGURA_DEL_ABANICO * 2, false,
                        caja, lado, style = Stroke(r2 - r1)
                    )
                    if (m.elegida >= 0) {
                        drawArc(
                            Color.White.copy(alpha = 0.35f), anguloDelAbanico(m.elegida, n) - paso / 2, paso, false,
                            caja, lado, style = Stroke(r2 - r1)
                        )
                    }
                    drawCircle(Color.White.copy(alpha = 0.25f), RADIO_MUERTO_DEL_ABANICO.dp.toPx(), m.centro)
                    val icono = ICONO_DEL_ABANICO.dp.toPx()
                    for ((i, opcion) in m.opciones.withIndex()) {
                        val a = Math.toRadians(anguloDelAbanico(i, n).toDouble())
                        val cx = m.centro.x + (rm * cos(a)).toFloat()
                        val cy = m.centro.y + (rm * sin(a)).toFloat()
                        val indice = opcion.icono
                        if (indice >= 0) {
                            translate(cx - icono / 2, cy - icono / 2) {
                                with(iconosDelAbanico[indice]) {
                                    draw(androidx.compose.ui.geometry.Size(icono, icono), colorFilter = ColorFilter.tint(Color.White))
                                }
                            }
                        } else {
                            // Un lápiz hecho: un punto de su grosor, en la tinta que hay.
                            val g = GROSORES_DE_LOS_LAPICES.getOrElse(LAPICES_DEL_ABANICO.indexOf(opcion)) { 2.0 }
                            drawCircle(Color.White, (2.5f + g.toFloat() * 1.3f).dp.toPx(), Offset(cx, cy))
                        }
                    }
                }
                is MenuRapido.Color -> {
                    val disco = RADIO_DE_LA_RUEDA.dp.toPx()
                    val sombra = SOMBRA_DE_LA_RUEDA.toPx()
                    drawCircle(Color.Black.copy(alpha = 0.25f), disco + sombra, m.centro)
                    drawCircle(Brush.sweepGradient(m.tonos, m.centro), disco, m.centro)
                    // El gris del centro: la viveza sale de lo lejos que está el lápiz.
                    val gris = Color(deHsv(floatArrayOf(0f, 0f, m.claridad)))
                    drawCircle(
                        Brush.radialGradient(listOf(gris, gris.copy(alpha = 0f)), m.centro, disco),
                        disco, m.centro
                    )
                    val hex = m.hex
                    val punto = m.punto
                    if (hex != null && punto != null) {
                        val tinta = Color(parseColor(hex))
                        drawCircle(tinta, disco + sombra * 2.5f, m.centro, style = Stroke(sombra * 3))
                        val p = 9.dp.toPx()
                        drawCircle(tinta, p, punto)
                        drawCircle(Color.White, p, punto, style = Stroke(2.dp.toPx()))
                        drawCircle(Color.Black.copy(alpha = 0.45f), p + 2.dp.toPx(), punto, style = Stroke(1.5f))
                    }
                }
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
    // **Sin modo noche a propósito, al revés que [DrawExport.aBitmap].** Allí el fondo lo
    // pinta la propia escena —su papel— y por eso la tinta tiene que ir a juego; aquí no se
    // pinta papel ninguno: los dos sitios que enseñan esto, el pin y la galería de figuras,
    // ponen detrás un blanco fijo. Filtrando la tinta saldría blanco sobre blanco.
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
/** La bolita del vértice al que se apoya una caja. Ver [imanEntreSolidos]. */
private const val APOYO_RADIO = 5f

/** El rótulo de las medidas: cuánto separa del dibujo y de qué tamaño va. */
private const val MEDIDA_SEPARACION = 10f
private const val MEDIDA_LETRA = 12f

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

/** Cuánto hay que aguantar el dedo para que cuente como pulsación larga. */
private const val MILIS_DE_PULSACION_LARGA = 450L

/** El margen del dedo al buscar algo clavado, en píxeles de escena. */
private const val UMBRAL_DEL_DEDO = 24.0

/**
 * **Un toque con cuatro dedos: se van todos los mandos y queda el dibujo.**
 *
 * ## Qué resuelve
 *
 * Un lienzo de móvil tiene el dibujo debajo y los mandos encima, y eso está bien mientras se
 * dibuja: lo que se toca cada dos trazos tiene que estar a mano. Pero hay dos momentos en los
 * que sobra todo: **enseñar** lo que se ha hecho —a alguien que está al lado, o para una
 * captura— y **trabajar una zona grande** a pantalla completa. Hasta ahora eso pedía ir
 * apagando cosas una a una, y volver a encenderlas después.
 *
 * ## Por qué cuatro dedos y no un botón
 *
 * Un botón para esconder los botones es el pez que se muerde la cola: ocupa sitio justo en el
 * modo del que uno quiere salir, y para volver hay que dejarlo a la vista, así que no se
 * esconde nada del todo. Con un gesto no queda nada en la pantalla y **el mismo gesto vuelve**
 * — que es lo único que hay que recordar.
 *
 * Cuatro y no dos ni tres porque el lienzo ya usa uno —trazar— y dos —encuadrar y cuadrar la
 * figura—, y tres es el gesto del sistema en muchos móviles. Con cuatro no se pisa nada, y
 * nadie apoya cuatro dedos sin querer.
 *
 * ## Y no cambia la herramienta
 *
 * Se sigue dibujando con lo que hubiera puesto. El modo no es «ahora se mira»: es la misma
 * sesión con la pantalla despejada, así que entrar y salir no puede costar volver a elegir el
 * lápiz, el color y el grosor.
 *
 * ## Cómo se lee
 *
 * En la pasada **inicial**, que es la que llega antes que a nadie, y **sin consumir** mientras
 * no haya cuatro: así el lienzo sigue trazando y encuadrando como si esto no estuviera. En
 * cuanto se juntan los cuatro se avisa —[alJuntarse], que es donde se cancela el trazo que el
 * primer dedo hubiera empezado— y a partir de ahí sí se consume todo, para que soltarlos no
 * deje una raya suelta.
 *
 * Cuenta como toque si los cuatro se levantan pronto y sin irse a ningún sitio: mantenerlos y
 * arrastrar no es este gesto, y no tiene por qué hacer nada.
 */
fun Modifier.elToqueDeCuatroDedos(
    /** Los cuatro dedos acaban de juntarse: lo que hubiera empezado a trazarse se cancela. */
    alJuntarse: () -> Unit,
    /** Y se han levantado sin moverse: es el toque. */
    alTocar: () -> Unit
): Modifier = pointerInput(Unit) {
    val margen = viewConfiguration.touchSlop * MARGEN_DEL_TOQUE
    awaitPointerEventScope {
        var juntos = false
        var cuando = 0L
        var donde = Offset.Zero
        var valido = false
        while (true) {
            val evento = awaitPointerEvent(PointerEventPass.Initial)
            val apoyados = evento.changes.filter { it.pressed }
            val todos = apoyados.size >= DEDOS_PARA_ESCONDER
            if (!juntos && todos) {
                juntos = true
                valido = true
                // **La hora del cuarto dedo, no la del primero.**
                //
                // Se cogía la del primero, y los cuatro no se posan a la vez: quien apoya uno
                // y añade los otros tres —o quien tarda un pelo— ya llegaba con medio segundo
                // gastado antes de empezar, así que el toque se descartaba por lento sin
                // haber hecho nada raro.
                cuando = evento.changes.maxOf { it.uptimeMillis }
                donde = centroDe(apoyados)
            }
            if (!juntos) continue
            // A partir de aquí el gesto es nuestro: nada de lo que pase con estos dedos tiene
            // que llegar al lienzo.
            evento.changes.forEach { it.consume() }
            if (apoyados.isNotEmpty()) {
                // **Y solo se mide mientras están los cuatro.**
                //
                // El centro se sacaba de los dedos que quedaran, y al levantarlos se levantan
                // de uno en uno: con tres, el centro salta al hueco que deja el que falta —
                // muchísimo más que el margen— así que el gesto se invalidaba **siempre** al
                // soltar. Eso era lo de «no esconde nada»: se detectaba bien y se descartaba
                // en el último fotograma.
                if (todos && (centroDe(apoyados) - donde).getDistance() > margen) valido = false
                continue
            }
            // Todos fuera: se cierra el gesto.
            val ultimo = evento.changes.maxOfOrNull { it.uptimeMillis } ?: cuando
            if (valido && ultimo - cuando <= LO_QUE_DURA_UN_TOQUE_DE_CUATRO) alTocar()
            juntos = false
            valido = false
        }
    }
}

/**
 * **El toque de dos y de tres dedos: deshacer y rehacer.**
 *
 * Se lee como el de cuatro —en la pasada inicial y sin consumir nada—, así que el lienzo
 * sigue encuadrando con dos dedos como siempre: solo cuenta como toque si los dedos se
 * posan y se levantan pronto **sin irse a ningún sitio**, que es lo que distingue un toque
 * de un pellizco. Con cuatro dedos manda el otro gesto y este se calla.
 *
 * El lápiz no cuenta como dedo: se puede tener el lápiz apoyado y dar el toque con la otra
 * mano.
 */
fun Modifier.elToqueDeVariosDedos(
    /** Se han posado y levantado tantos dedos (dos o tres) sin moverse. */
    alTocar: (dedos: Int) -> Unit
): Modifier = pointerInput(Unit) {
    val margen = viewConfiguration.touchSlop * MARGEN_DEL_TOQUE
    awaitPointerEventScope {
        var enMarcha = false
        var maximo = 0
        var cuando = 0L
        var donde = Offset.Zero
        // **Y lo separados que estaban.** Un pellizco corto y centrado pasaba por toque de
        // dos dedos: al abrir hacia los dos lados a la vez el centro apenas se mueve, así que
        // el margen del centro no lo cazaba —uno pedía acercar y lo que salía era deshacer—.
        // Lo que distingue un pellizco de un toque no es dónde está la mano, es que **los
        // dedos se separan o se juntan**.
        var separados = 0f
        var valido = false
        while (true) {
            val evento = awaitPointerEvent(PointerEventPass.Initial)
            val dedos = evento.changes.filter { it.pressed && it.type != PointerType.Stylus }
            if (dedos.size >= 2 && dedos.size > maximo) {
                if (!enMarcha) {
                    enMarcha = true
                    valido = true
                }
                maximo = dedos.size
                // La hora y el sitio del último dedo en llegar: se mide desde que están todos.
                cuando = evento.changes.maxOf { it.uptimeMillis }
                donde = centroDe(dedos)
                separados = loSeparadosQueEstan(dedos)
            }
            if (!enMarcha) continue
            if (dedos.size >= DEDOS_PARA_ESCONDER) valido = false
            if (dedos.isNotEmpty()) {
                if (dedos.size == maximo) {
                    if ((centroDe(dedos) - donde).getDistance() > margen) valido = false
                    if (kotlin.math.abs(loSeparadosQueEstan(dedos) - separados) > margen) valido = false
                }
                continue
            }
            val ultimo = evento.changes.maxOfOrNull { it.uptimeMillis } ?: cuando
            if (valido && (maximo == 2 || maximo == 3) && ultimo - cuando <= LO_QUE_DURA_UN_TOQUE) {
                alTocar(maximo)
            }
            enMarcha = false
            maximo = 0
            valido = false
        }
    }
}

/**
 * **El tirón de tres dedos hacia arriba: saca la hoja adhesiva.**
 *
 * Convive con [elToqueDeVariosDedos] sin pelearse, y no por casualidad: aquel solo cuenta como
 * toque si los dedos **no se van a ningún sitio**, así que un tirón invalida el toque por su
 * propia definición. Uno es «tres dedos y sueltas», el otro «tres dedos y subes».
 *
 * Se pide que suban de verdad —más hacia arriba que a los lados— y que **no se separen**: tres
 * dedos que se abren mientras suben son una mano acomodándose sobre el cristal, no un tirón.
 * Y se avisa **una sola vez por gesto**: sin eso, seguir subiendo sacaría una hoja por
 * fotograma.
 *
 * Como todo lo que decide un gesto aquí, se mide **en la pantalla** y no en el espacio del
 * dibujo: lo que la mano hace no cambia porque el lienzo esté más o menos acercado.
 */
fun Modifier.elTironDeTresDedos(alSubir: () -> Unit): Modifier = pointerInput(Unit) {
    val margen = viewConfiguration.touchSlop
    val subida = margen * LO_QUE_SUBE_UN_TIRON
    awaitPointerEventScope {
        var enMarcha = false
        var maximo = 0
        var donde = Offset.Zero
        var separados = 0f
        var valido = false
        var avisado = false
        while (true) {
            val evento = awaitPointerEvent(PointerEventPass.Initial)
            val dedos = evento.changes.filter { it.pressed && it.type != PointerType.Stylus }
            if (dedos.size >= 2 && dedos.size > maximo) {
                if (!enMarcha) { enMarcha = true; valido = true; avisado = false }
                maximo = dedos.size
                donde = centroDe(dedos)
                separados = loSeparadosQueEstan(dedos)
            }
            if (!enMarcha) continue
            if (dedos.size >= DEDOS_PARA_ESCONDER) valido = false
            if (dedos.isEmpty()) { enMarcha = false; maximo = 0; valido = false; continue }
            if (valido && !avisado && maximo == 3 && dedos.size == 3) {
                // Abrir o cerrar la mano no es subir: eso es un pellizco con tres dedos.
                if (kotlin.math.abs(loSeparadosQueEstan(dedos) - separados) > margen * 2) {
                    valido = false
                    continue
                }
                val ido = centroDe(dedos) - donde
                // Arriba es y negativa, y tiene que ganarle claramente al movimiento lateral:
                // si no, una mano que barre en diagonal sacaría la hoja sin querer.
                if (-ido.y > subida && -ido.y > kotlin.math.abs(ido.x) * 1.5f) {
                    avisado = true
                    alSubir()
                }
            }
        }
    }
}

/** Cuántos márgenes de arrastre hay que subir para que sea un tirón y no un temblor. */
private const val LO_QUE_SUBE_UN_TIRON = 4f

/** Cuánto puede durar un toque de dos o tres dedos. */
private const val LO_QUE_DURA_UN_TOQUE = 350L

/**
 * Lo separados que están unos dedos: la mayor distancia entre dos de ellos.
 *
 * Con dos es la distancia entre ellos, que es justo lo que cambia al pellizcar. Con tres vale
 * igual: si la mano abre o cierra, alguna de las tres parejas se estira. Ver
 * [elToqueDeVariosDedos].
 */
private fun loSeparadosQueEstan(dedos: List<PointerInputChange>): Float {
    var mayor = 0f
    for (i in dedos.indices) {
        for (j in i + 1 until dedos.size) {
            val d = (dedos[i].position - dedos[j].position).getDistance()
            if (d > mayor) mayor = d
        }
    }
    return mayor
}

/** La tira de colores del gesto rápido: dónde se ancló y cuál está señalado. */
/**
 * Lo que se está eligiendo con el lápiz y por dónde va. [indiceBase] y [filaBase] son la
 * casilla y la fila de partida —las que caen bajo el lápiz al posarse—, que es lo que
 * coloca la tira en pantalla.
 */
class SelectorRapido(
    val ancla: Offset,
    val indice: Int,
    val clase: ClaseDelSelector = ClaseDelSelector.COLORES,
    val fila: Int = 0,
    val filaBase: Int = 0,
    val indiceBase: Int = 0
)

enum class ClaseDelSelector { COLORES, HERRAMIENTAS }

/**
 * **El menú rápido de la pantalla completa**: lo que hay en pantalla mientras se mantiene
 * el botón flotante y el lápiz elige. El [Abanico] es media corona de opciones sobre el
 * punto donde se posó el lápiz ([elegida] −1 si ninguna); [Color], la rueda del color del
 * panel. Lo de dentro es lo que hace falta para pintarlo: se calcula al mover, no al pintar.
 */
sealed class MenuRapido(val centro: Offset) {
    class Abanico(centro: Offset, val opciones: List<OpcionDelAbanico>, val elegida: Int) : MenuRapido(centro)
    /** [tonos], el barrido del disco, ya hecho para no rehacerlo por fotograma. */
    class Color(
        centro: Offset, val tonos: List<androidx.compose.ui.graphics.Color>, val claridad: Float,
        val punto: Offset?, val hex: String?
    ) : MenuRapido(centro)
}

/**
 * Las opciones del abanico. [icono] es cuál de los iconos del lienzo le toca, o −1 a los
 * lápices hechos, que se pintan como un punto de su grosor; [tool], la herramienta que
 * pone, si pone una.
 */
enum class OpcionDelAbanico(val icono: Int, val tool: Tool? = null) {
    LAPIZ(0), BORRADOR(1, Tool.ERASER), COLOR(2), FIGURAS(3),
    FINO(-1, Tool.FREEDRAW), MEDIO(-1, Tool.FREEDRAW), GRUESO(-1, Tool.FREEDRAW),
    ELIPSE(4, Tool.ELLIPSE), LINEA(5, Tool.LINE), FLECHA(6, Tool.ARROW),
    CUADRADO(7, Tool.RECTANGLE), ROMBO(8, Tool.DIAMOND)
}

/** El abanico principal, de izquierda a derecha; el de los lápices hechos, con sus grosores; y el de las figuras. */
private val OPCIONES_DEL_ABANICO = listOf(
    OpcionDelAbanico.LAPIZ, OpcionDelAbanico.BORRADOR, OpcionDelAbanico.COLOR, OpcionDelAbanico.FIGURAS
)
private val LAPICES_DEL_ABANICO = listOf(OpcionDelAbanico.FINO, OpcionDelAbanico.MEDIO, OpcionDelAbanico.GRUESO)
private val GROSORES_DE_LOS_LAPICES = listOf(1.0, 2.5, 6.0)
private val FIGURAS_DEL_ABANICO = listOf(
    OpcionDelAbanico.ELIPSE, OpcionDelAbanico.LINEA, OpcionDelAbanico.FLECHA,
    OpcionDelAbanico.CUADRADO, OpcionDelAbanico.ROMBO
)

/**
 * **El ángulo de la opción [i] de [n]**, en grados de pantalla: cero a las tres y en el
 * sentido del reloj, como `atan2` y como `drawArc`. Las opciones se reparten por igual a lo
 * largo de [ARCO_DEL_ABANICO], centrado hacia arriba, de izquierda a derecha.
 */
private fun anguloDelAbanico(i: Int, n: Int): Float =
    -90f - ARCO_DEL_ABANICO / 2 + ARCO_DEL_ABANICO * (i + 0.5f) / n

/** Cuánto arco abarca el abanico, en grados; y cuánto se admite fuera de una opción. */
private const val ARCO_DEL_ABANICO = 150f
private const val HOLGURA_DEL_ABANICO = 10f

/** El centro muerto del abanico —hasta ahí no se elige nada— y su borde de fuera, en dp. */
private const val RADIO_MUERTO_DEL_ABANICO = 18
private const val RADIO_EXTERIOR_DEL_ABANICO = 104
private const val ICONO_DEL_ABANICO = 26

/** El barrido de tonos de la rueda del color, a la claridad de la tinta puesta. */
private fun tonosDeLaRueda(claridad: Float): List<Color> =
    List(TONOS_DEL_AJUSTE + 1) { Color(deHsv(floatArrayOf(it * 360f / TONOS_DEL_AJUSTE, 1f, claridad))) }

private const val TONOS_DEL_AJUSTE = 24

/** Lo que separa dos colores de la tira, en dp, y a qué altura sobre la punta va. */
private const val PASO_DEL_SELECTOR = 34
private const val ALTURA_DEL_SELECTOR = 64

/** El centro de los dedos que hay apoyados. */
private fun centroDe(dedos: List<PointerInputChange>): Offset =
    if (dedos.isEmpty()) Offset.Zero
    else dedos.fold(Offset.Zero) { a, c -> a + c.position } / dedos.size.toFloat()

/**
 * Cuántos dedos hacen falta y cuánto puede durar el toque.
 *
 * Medio segundo largo: cuatro dedos no se posan y se levantan a la vez ni queriendo, así que
 * con menos margen el gesto no sale la mitad de las veces. Y con más, mantenerlos apoyados un
 * rato —que es lo que hace quien está apartando la mano— acabaría escondiendo los mandos.
 */
private const val DEDOS_PARA_ESCONDER = 4
private const val LO_QUE_DURA_UN_TOQUE_DE_CUATRO = 900L

/** Y cuánto se les deja moverse, en veces el umbral del sistema. */
private const val MARGEN_DEL_TOQUE = 4f
