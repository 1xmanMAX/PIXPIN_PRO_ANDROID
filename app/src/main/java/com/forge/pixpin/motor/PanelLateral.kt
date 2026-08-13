package com.forge.pixpin.motor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * El panel de estilo del lienzo, **pegado al lateral y manejado con el dedo**.
 *
 * ## Qué problema resuelve
 *
 * El panel de antes es una lista de botoncitos: cinco colores, cuatro grosores,
 * tres opacidades, cinco rellenos. Para subir el grosor hay que abrir el panel,
 * buscar la fila, apuntar a la cuarta muestra y volver a cerrarlo — y todo eso
 * tapando el dibujo, que es justo lo que se está mirando para decidir.
 *
 * Aquí no hay que apuntar a nada. El grosor y la opacidad son **deslizadores
 * verticales** que se recorren con el pulgar sin levantar la vista, y las
 * propiedades de tres o cinco estilos —el relleno, la línea, el color— son una
 * bolita que se **arrastra hacia el lienzo**: al empezar a moverla salen las
 * opciones en fila, se suelta encima de una y la bolita vuelve a su sitio. Un
 * solo gesto para abrir, elegir y cerrar, sin un panel que tape nada.
 *
 * ## Dónde se pone
 *
 * En el lateral **contrario a la mano**: la bolita se arrastra hacia el centro
 * de la pantalla, y si el panel estuviera bajo la mano el propio brazo taparía
 * las opciones que están saliendo.
 *
 * ## Y no salen todos
 *
 * Solo lo que aplique a lo que se esté haciendo. Esa tabla no se decide aquí:
 * la pone [propiedadesPara], que es lo mismo que decide el panel de siempre.
 */
@Composable
fun PanelLateralDeEstilo(
    aplican: Set<Propiedad>,
    estilo: ItemStyle,
    zurdo: Boolean,
    colores: List<String>,
    coloresDeFondo: List<String>,
    onEstilo: (ItemStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    // Las opciones salen **hacia el lienzo**, o sea al contrario del lado en el
    // que vive el panel. Ver la nota de arriba.
    val haciaLaIzquierda = zurdo

    // **Los controles se pintan con el color que hay puesto.** La raya del
    // grosor sale del color del trazo, la trama del relleno sale del color de
    // fondo: así el panel no dice «grosor» en gris, dice *esta* raya, la que va
    // a salir. Cambiar el color cambia el panel entero, que es lo que hace que
    // parezca una sola cosa y no una lista de ajustes sueltos.
    val neutro = MaterialTheme.colorScheme.onSurface
    val tintaDelTrazo = colorDeEstilo(estilo.strokeColor, neutro)
    val tintaDelFondo = colorDeEstilo(estilo.backgroundColor, tintaDelTrazo)

    // **Los controles encogen si son muchos.** El panel no se desplaza —eso
    // recortaría el desplegable de la bolita— así que lo que salga tiene que
    // caber de una vez. Con una figura seleccionada salen ocho, y en un móvil
    // corto el último se quedaba fuera de la pantalla: justo el pulso, que es
    // el que va al final. Antes que perder un control, todos un poco menores.
    val cuantos = aplican.count { it in EN_EL_LATERAL } +
        (if (Propiedad.FUENTE in aplican) 1 else 0) +
        (if (Propiedad.RUGOSIDAD in aplican) 2 else 0)
    val apretado = cuantos >= 7
    val bola = if (apretado) 26.dp else BOLA
    val altoDeslizador = if (apretado) 76.dp else ALTO_DEL_DESLIZADOR

    // **Sin desplazamiento.** Un contenedor que se desplaza recorta lo que se
    // sale de él, y de aquí se sale justo lo que hay que ver: la fila de
    // opciones que sale de la bolita salía cortada por el borde del panel. Antes
    // que eso, los controles van pequeños y apretados para que quepan.
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SEPARACION)
    ) {
        if (Propiedad.TRAZO in aplican) {
            SelectorArrastrable(
                opciones = colores,
                actual = colores.indexOf(estilo.strokeColor).coerceAtLeast(0),
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Color del trazo",
                onElegir = { onEstilo(estilo.copy(strokeColor = colores[it])) }
            ) { hex, _ -> MuestraDeColor(hex) }
        }

        if (Propiedad.FONDO in aplican) {
            SelectorArrastrable(
                opciones = coloresDeFondo,
                actual = coloresDeFondo.indexOf(estilo.backgroundColor).coerceAtLeast(0),
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Color de fondo",
                onElegir = { onEstilo(estilo.copy(backgroundColor = coloresDeFondo[it])) }
            ) { hex, _ -> MuestraDeColor(hex) }
        }

        if (Propiedad.RELLENO in aplican) {
            // **La primera opción es «sin relleno», y es la que faltaba.** Un
            // garabato que se cierra sobre sí mismo se rellena solo, y si el
            // selector solo ofrece tramas no hay ninguna forma de decir «ninguna»:
            // la trama se queda puesta y no hay interruptor. Apagarlo es poner el
            // fondo en transparente, así que se hace desde aquí, que es donde
            // aparece el problema.
            val rellenos: List<FillStyle?> = listOf(null) + FillStyle.entries
            val puesto =
                if (isTransparent(estilo.backgroundColor)) 0
                else rellenos.indexOf(estilo.fillStyle).coerceAtLeast(1)
            SelectorArrastrable(
                opciones = rellenos,
                actual = puesto,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Relleno",
                // Con el color del fondo, que es con el que se va a rellenar.
                tinta = tintaDelFondo,
                onElegir = { i ->
                    val fs = rellenos[i]
                    onEstilo(
                        if (fs == null) {
                            estilo.copy(backgroundColor = "transparent")
                        } else {
                            // Al encender un relleno estando en transparente hay
                            // que darle un color, o se elegiría una trama que no
                            // se ve y parecería que el botón no hace nada.
                            val fondo =
                                if (isTransparent(estilo.backgroundColor)) {
                                    coloresDeFondo.firstOrNull { !isTransparent(it) }
                                        ?: estilo.backgroundColor
                                } else {
                                    estilo.backgroundColor
                                }
                            estilo.copy(fillStyle = fs, backgroundColor = fondo)
                        }
                    )
                }
            ) { fs, tinta ->
                if (fs == null) {
                    Text("∅", fontSize = 15.sp, color = tinta)
                } else {
                    Canvas(Modifier.size(20.dp)) { dibujarRelleno(fs, tinta) }
                }
            }
        }

        if (Propiedad.LINEA in aplican) {
            val lineas = StrokeStyle.entries
            SelectorArrastrable(
                opciones = lineas,
                actual = lineas.indexOf(estilo.strokeStyle),
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Tipo de línea",
                tinta = tintaDelTrazo,
                onElegir = { onEstilo(estilo.copy(strokeStyle = lineas[it])) }
            ) { ss, tinta -> Canvas(Modifier.size(22.dp)) { dibujarLinea(ss, tinta) } }
        }

        if (Propiedad.ESQUINAS in aplican) {
            // Dos: en pico o redondeadas. La bolita vale igual para dos que
            // para cinco, y así todo se toca de la misma manera.
            val redondas = listOf(false, true)
            SelectorArrastrable(
                opciones = redondas,
                actual = if (estilo.roundness != null) 1 else 0,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Esquinas",
                tinta = tintaDelTrazo,
                onElegir = { i ->
                    val r = if (redondas[i]) Roundness(Roundness.ADAPTIVE_RADIUS) else null
                    onEstilo(estilo.copy(roundness = r))
                }
            ) { redonda, tinta ->
                Canvas(Modifier.size(20.dp)) {
                    if (redonda) {
                        drawRoundRect(
                            tinta, size = size,
                            cornerRadius = CornerRadius(size.minDimension / 3),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    } else {
                        drawRect(tinta, size = size, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }

        if (Propiedad.FORMA_FLECHA in aplican) {
            SelectorArrastrable(
                opciones = FormaDeFlecha.entries,
                actual = FormaDeFlecha.de(estilo).ordinal,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Forma de la flecha",
                tinta = tintaDelTrazo,
                onElegir = { i -> onEstilo(FormaDeFlecha.entries[i].aplicadaA(estilo)) }
            ) { forma, tinta ->
                Text(forma.glifo, fontSize = 15.sp, color = tinta)
            }
        }

        if (Propiedad.MOSAICO in aplican) {
            val modos = listOf(false, true)
            SelectorArrastrable(
                opciones = modos,
                actual = if (estilo.mosaicBlur) 1 else 0,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Tapar",
                tinta = tintaDelTrazo,
                onElegir = { i -> onEstilo(estilo.copy(mosaicBlur = modos[i])) }
            ) { desenfocar, tinta ->
                Canvas(Modifier.size(20.dp)) {
                    if (desenfocar) {
                        // Aros que se desvanecen: eso es desenfocar.
                        for (i in 3 downTo 1) {
                            drawCircle(
                                tinta.copy(alpha = 0.28f * i),
                                radius = size.minDimension / 2 * i / 3
                            )
                        }
                    } else {
                        // Cuadros duros: eso es pixelar.
                        val lado = size.width / 4
                        for (fx in 0..3) for (fy in 0..3) {
                            if ((fx + fy) % 2 == 0) {
                                drawRect(tinta, Offset(fx * lado, fy * lado), Size(lado, lado))
                            }
                        }
                    }
                }
            }
        }

        if (Propiedad.FUENTE in aplican) {
            val familias = ItemStyle.FONT_FAMILIES
            SelectorArrastrable(
                opciones = familias,
                actual = familias.indexOf(ItemStyle.fontFamilyResuelta(estilo.fontFamily))
                    .coerceAtLeast(0),
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Letra",
                tinta = tintaDelTrazo,
                onElegir = { onEstilo(estilo.copy(fontFamily = familias[it])) }
            ) { familia, tinta ->
                // **La propia letra**, escrita con su fuente: se elige mirando
                // cuál se parece a lo que quieres, no leyendo su nombre.
                Text(
                    "Aa",
                    fontSize = 13.sp,
                    color = tinta,
                    fontFamily = composeFontFamily(familia)
                )
            }
        }

        if (Propiedad.GROSOR in aplican) {
            // **Cualquier grosor, no cuatro.** Los cuatro de siempre son los del
            // original y están bien para elegir a dedo entre botones, pero un
            // deslizador puede dar todos los de en medio sin costar nada: el
            // trazo que hace falta para tachar no es ninguno de los cuatro.
            DeslizadorVertical(
                fraccion = fraccionDelGrosor(estilo.strokeWidth),
                descripcion = "Grosor",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                onFraccion = { f ->
                    val g = grosorDeLaFraccion(f)
                    if (g != estilo.strokeWidth) onEstilo(estilo.copy(strokeWidth = g))
                }
            ) { tinta, avance ->
                // La propia raya, del grosor que se está eligiendo y **del color
                // que hay puesto**: se ve gorda cuando es gorda y roja cuando es
                // roja. Es lo que se viene a mirar.
                drawLine(
                    tinta,
                    Offset(size.width * 0.2f, size.height / 2),
                    Offset(size.width * 0.8f, size.height / 2),
                    strokeWidth = (1f + avance * 7f).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        if (Propiedad.FUENTE in aplican) {
            val tamanos = ItemStyle.FONT_SIZES
            val puesto = masCercano(estilo.fontSize, tamanos)
            DeslizadorVertical(
                fraccion = fraccionDeLaCasilla(puesto, tamanos.size),
                descripcion = "Tamaño de la letra",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                onFraccion = { f ->
                    val i = casillaDe(f, tamanos.size)
                    if (i != puesto) onEstilo(estilo.copy(fontSize = tamanos[i]))
                },
                // La «A» va como texto y no como dibujo: una letra dibujada a
                // mano no diría de qué tamaño sale la de verdad.
                dentro = {
                    Text(
                        "A",
                        fontSize = (10 + puesto * 4).sp,
                        color = tintaDelTrazo,
                        fontFamily = composeFontFamily(estilo.fontFamily)
                    )
                }
            )
        }

        if (Propiedad.OPACIDAD in aplican) {
            DeslizadorVertical(
                fraccion = fraccionDelValor(estilo.opacity, MINIMA_OPACIDAD, 100),
                descripcion = "Opacidad",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                onFraccion = { f ->
                    val v = valorConPaso(f, MINIMA_OPACIDAD, 100, PASO_DE_OPACIDAD)
                    if (v != estilo.opacity) onEstilo(estilo.copy(opacity = v))
                }
            ) { tinta, avance ->
                // Un disco con la transparencia puesta: se ve lo que se pierde.
                drawCircle(
                    tinta.copy(alpha = (0.12f + avance * 0.88f)),
                    radius = size.minDimension / 2.6f
                )
            }
        }

        // **El pulso, desplegado y debajo de los deslizadores.** Son tres y se
        // cambia entre ellos a cada rato mientras se dibuja —una guía va recta,
        // lo que se enseña va a mano—, así que meterlos en una bolita que hay
        // que arrastrar costaba más que enseñarlos. Con tres opciones, verlas
        // ocupa lo mismo que esconderlas.
        if (Propiedad.RUGOSIDAD in aplican) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PULSOS.forEachIndexed { i, r ->
                    val puesto = estilo.roughness == r
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = if (puesto) 6.dp else 2.dp,
                        border = if (puesto) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier.size(bola)
                    ) {
                        Box(
                            Modifier.clickable { onEstilo(estilo.copy(roughness = r)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(20.dp)) { dibujarPulso(i, tintaDelTrazo) }
                        }
                    }
                }
            }
        }
    }
}



/**
 * El color de un estilo, listo para pintar con él.
 *
 * «transparent» no es un color con el que se pueda dibujar una muestra, así que
 * en ese caso manda [siNoHay]: enseñar la trama del relleno en transparente
 * sería enseñar un hueco, y lo que se quiere saber es qué trama es.
 */
private fun colorDeEstilo(hex: String, siNoHay: Color): Color =
    if (isTransparent(hex)) siNoHay else Color(parseColor(hex))

/** Los tres pulsos del original, de recto a temblón. */
private val PULSOS = listOf(
    Element.ROUGHNESS_ARCHITECT, Element.ROUGHNESS_ARTIST, Element.ROUGHNESS_CARTOONIST
)

/**
 * Las tres formas de una flecha, como **una sola cosa que se elige**.
 *
 * En el modelo son dos campos sueltos —`elbowed` y `roundness`— y eso deja
 * estados que no significan nada, como «de codos y curva a la vez». Aquí se
 * vuelven tres opciones, que es como se piensan: recta, curva o de codos.
 */
private enum class FormaDeFlecha(val glifo: String) {
    RECTA("╱"), CURVA("⌒"), CODOS("⌐");

    fun aplicadaA(estilo: ItemStyle): ItemStyle = when (this) {
        RECTA -> estilo.copy(elbowed = false, roundness = null)
        CURVA -> estilo.copy(elbowed = false, roundness = Roundness(Roundness.PROPORTIONAL_RADIUS))
        CODOS -> estilo.copy(elbowed = true, roundness = null)
    }

    companion object {
        fun de(estilo: ItemStyle): FormaDeFlecha = when {
            estilo.elbowed -> CODOS
            estilo.roundness != null -> CURVA
            else -> RECTA
        }
    }
}

/** Qué propiedades tienen un control en este panel, para contarlas. */
private val EN_EL_LATERAL = setOf(
    Propiedad.TRAZO, Propiedad.FONDO, Propiedad.RELLENO, Propiedad.LINEA,
    Propiedad.ESQUINAS, Propiedad.FORMA_FLECHA, Propiedad.MOSAICO,
    Propiedad.GROSOR, Propiedad.OPACIDAD, Propiedad.FUENTE
)

/** Lo menos transparente que se deja llegar: a cero no habría nada que ver. */
private const val MINIMA_OPACIDAD = 10
private const val PASO_DE_OPACIDAD = 5

/**
 * Un deslizador vertical: arriba es más.
 *
 * Se puede arrastrar y también tocar en un punto — tocar arriba del todo pone el
 * máximo. Las dos cosas porque no son el mismo gesto: se arrastra cuando se
 * busca el valor mirando el dibujo, y se toca cuando ya se sabe dónde está.
 *
 * [dibujo] pinta lo que hay dentro del mango con el avance actual, así que el
 * mango **es** la muestra: la raya gorda si el grosor es gordo. `avance` va de 0
 * a 1 y es lo mismo que [fraccion], pasado por comodidad de quien pinta.
 */
@Composable
private fun DeslizadorVertical(
    fraccion: Float,
    descripcion: String,
    onFraccion: (Float) -> Unit,
    /** Lo que mide de alto. Encoge cuando hay muchos controles. */
    alto: Dp = ALTO_DEL_DESLIZADOR,
    tinta: Color? = null,
    /** Lo que va dentro del mango si no es un dibujo, como una letra de verdad. */
    dentro: (@Composable () -> Unit)? = null,
    dibujo: DrawScope.(tinta: Color, avance: Float) -> Unit = { _, _ -> }
) {
    val densidad = LocalDensity.current
    val vibrar = LocalHapticFeedback.current
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val relleno = MaterialTheme.colorScheme.primary
    val tintaDelMango = tinta ?: MaterialTheme.colorScheme.onSurface
    var altoPx by remember { mutableFloatStateOf(0f) }
    val recorrido = alto - MANGO

    Surface(
        shape = RoundedCornerShape(ANCHO / 2),
        color = fondo,
        shadowElevation = 3.dp
    ) {
        Box(
            Modifier
                .width(ANCHO)
                .height(alto)
                .semantics { contentDescription = descripcion }
                .pointerInput(Unit) {
                    altoPx = size.height.toFloat()
                    detectTapGestures { pos ->
                        vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onFraccion(fraccionVertical(pos.y, size.height.toFloat()))
                    }
                }
                .pointerInput(Unit) {
                    altoPx = size.height.toFloat()
                    // Se sigue **la posición**, no el incremento: así el mango
                    // va donde está el dedo y no se va quedando atrás al llegar
                    // a los topes, que es lo que pasa acumulando deltas.
                    var y = 0f
                    detectVerticalDragGestures(
                        onDragStart = { inicio ->
                            y = inicio.y
                            vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onVerticalDrag = { cambio, delta ->
                            cambio.consume()
                            y += delta
                            onFraccion(fraccionVertical(y, size.height.toFloat()))
                        }
                    )
                }
        ) {
            // La barra llena, de abajo hasta donde va el valor.
            Canvas(Modifier.size(ANCHO, alto)) {
                val alto = size.height * fraccion
                drawRect(
                    relleno.copy(alpha = 0.25f),
                    topLeft = Offset(0f, size.height - alto),
                    size = Size(size.width, alto)
                )
            }
            // Y el mango, que es la muestra.
            val arriba = with(densidad) { (recorrido * (1f - fraccion)).toPx() }
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(0, arriba.toInt()) }
                    .padding(2.dp)
                    .size(MANGO - 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, relleno, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (dentro != null) {
                    dentro()
                } else {
                    Canvas(Modifier.size(MANGO - 4.dp)) { dibujo(tintaDelMango, fraccion) }
                }
            }
        }
    }
}

/**
 * La bolita que se arrastra para elegir entre unas pocas opciones.
 *
 * ## El gesto
 *
 * Quieta, enseña lo que hay puesto. En cuanto se arrastra hacia el lienzo salen
 * todas las opciones en fila desde ella, y la que quede bajo el dedo se marca;
 * al soltar, esa se aplica y la bolita **vuelve a su sitio**. Si se suelta sin
 * haberse movido de verdad —lo que pasa en cualquier toque, porque el dedo
 * nunca se levanta donde cayó— no cambia nada. Ver [abreElDesplegable].
 *
 * ## Por qué no es un menú
 *
 * Un menú son tres gestos: abrir, elegir, y que se cierre. Y mientras está
 * abierto tapa el dibujo. Esto es **uno solo**, y lo que tapa lo tapa el tiempo
 * que dura el dedo encima.
 */
@Composable
private fun <T> SelectorArrastrable(
    opciones: List<T>,
    actual: Int,
    haciaLaIzquierda: Boolean,
    descripcion: String,
    onElegir: (Int) -> Unit,
    /** Lo que mide la bolita. Encoge cuando hay muchos controles. */
    bola: Dp = BOLA,
    /** Con qué color se pintan las muestras. Null = el del tema. */
    tinta: Color? = null,
    contenido: @Composable (T, Color) -> Unit
) {
    val densidad = LocalDensity.current
    val vibrar = LocalHapticFeedback.current
    val slop = LocalViewConfiguration.current.touchSlop
    val alcance = rememberCoroutineScope()
    val paso = with(densidad) { PASO_ENTRE_OPCIONES.toPx() }

    var arrastre by remember { mutableFloatStateOf(0f) }
    var abierto by remember { mutableStateOf(false) }
    val corrimiento = remember { Animatable(0f) }
    var marcada by remember { mutableStateOf(actual) }

    val tintaDeLaMuestra = tinta ?: MaterialTheme.colorScheme.onSurface

    // **Del tamaño de la bolita y nada más.** La fila de opciones se mide
    // aparte —`unbounded`— y se coloca por encima sin contar para el tamaño: si
    // contara, el panel entero se ensancharía de golpe al empezar a arrastrar y
    // todo lo de al lado daría un salto.
    Box(Modifier.size(bola), contentAlignment = Alignment.Center) {
        // **Las opciones, saliendo de la bolita hacia el lienzo.** Van fuera de
        // cualquier Surface con forma: una superficie redondeada recorta a sus
        // hijos, y esta fila tiene que poder salirse del panel.
        if (abierto) {
            // Cada opción ocupa **exactamente un paso**, que es lo mismo que hay
            // que arrastrar para llegar a ella. Así lo que se ve y lo que cuenta
            // [opcionArrastrada] son la misma cosa; con el ancho suelto, el dedo
            // marcaba una y el dibujo señalaba otra.
            //
            // Y la fila se corre media fila menos medio paso para que **la
            // opción que hay puesta caiga justo bajo la bolita**: arrastrar cero
            // es quedarse en la que ya estaba.
            val corrida = PASO_ENTRE_OPCIONES * (opciones.size - 1) / 2f
            Row(
                Modifier
                    .wrapContentSize(unbounded = true)
                    .offset(x = if (haciaLaIzquierda) -corrida else corrida),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val enOrden = if (haciaLaIzquierda) opciones.indices.reversed() else opciones.indices
                enOrden.forEach { i ->
                    val elegida = i == marcada
                    Box(
                        Modifier.width(PASO_ENTRE_OPCIONES),
                        contentAlignment = Alignment.Center
                    ) {
                        // **La marcada se señala con un aro, no pintándola de
                        // otro color.** Si el fondo cambiara, la muestra dejaría
                        // de verse del color que va a salir — que es justo lo
                        // que se está mirando para elegir.
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = if (elegida) 8.dp else 3.dp,
                            border = if (elegida) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                            modifier = Modifier.size(if (elegida) bola + 6.dp else bola)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                contenido(opciones[i], tintaDeLaMuestra)
                            }
                        }
                    }
                }
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 3.dp,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(corrimiento.value.toInt(), 0) }
                .size(bola)
                .semantics { contentDescription = descripcion }
        ) {
            Box(
                Modifier.pointerInput(opciones, haciaLaIzquierda, actual) {
                    detectDragGestures(
                        onDragStart = {
                            arrastre = 0f
                            marcada = actual
                        },
                        onDrag = { cambio, delta ->
                            cambio.consume()
                            arrastre += delta.x
                            if (!abierto && abreElDesplegable(arrastre, slop)) {
                                abierto = true
                                vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (abierto) {
                                val antes = marcada
                                marcada = opcionArrastrada(
                                    arrastre, paso, opciones.size, haciaLaIzquierda
                                )
                                if (marcada != antes) {
                                    vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                alcance.launch { corrimiento.snapTo(arrastre) }
                            }
                        },
                        onDragEnd = {
                            if (abierto) onElegir(marcada)
                            abierto = false
                            arrastre = 0f
                            // **Y vuelve a su sitio**, con animación: si saltara
                            // de golpe parecería que se ha roto algo.
                            alcance.launch { corrimiento.animateTo(0f) }
                        },
                        onDragCancel = {
                            abierto = false
                            arrastre = 0f
                            alcance.launch { corrimiento.animateTo(0f) }
                        }
                    )
                },
                contentAlignment = Alignment.Center
            ) {
                opciones.getOrNull(actual)?.let { contenido(it, tintaDeLaMuestra) }
            }
        }
    }
}

/** Una muestra de color, con su hueco a cuadros si es transparente. */
@Composable
private fun MuestraDeColor(hex: String) {
    val transparente = isTransparent(hex)
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(
                if (transparente) Color.Transparent else Color(parseColor(hex))
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (transparente) {
            Text("∅", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ANCHO: Dp = 30.dp
private val MANGO: Dp = 28.dp

/**
 * Lo que miden los controles.
 *
 * Van justos a propósito: **el panel no se desplaza** —desplazarse recortaría
 * el desplegable de la bolita— así que todo lo que salga tiene que caber de una
 * vez. Con una figura seleccionada salen ocho controles, y esa es la cuenta que
 * manda estos números.
 */
private val ALTO_DEL_DESLIZADOR: Dp = 92.dp
private val BOLA: Dp = 30.dp
private val SEPARACION: Dp = 5.dp

/** Cuánto hay que arrastrar para pasar de una opción a la siguiente. */
private val PASO_ENTRE_OPCIONES: Dp = 40.dp

/**
 * El rayado, dibujado tal cual: un cuadro con su trama dentro.
 *
 * Vive aquí arriba y no dentro de una pantalla porque lo usan **los dos**
 * paneles, el de siempre y el del lateral, y una muestra de relleno que se
 * dibujara distinto en cada sitio sería otra forma de mentir.
 */
fun DrawScope.dibujarRelleno(fs: FillStyle, tinta: Color) {
    val borde = 1.2.dp.toPx()
    drawRect(tinta.copy(alpha = 0.45f), size = size, style = Stroke(width = borde))
    when (fs) {
        FillStyle.SOLID -> drawRect(tinta, size = size)
        FillStyle.HACHURE, FillStyle.LINEAS -> {
            // El de rayas rectas va sin temblor; el otro, torcido.
            val torcer = if (fs == FillStyle.HACHURE) 1.5f else 0f
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    tinta, Offset(x, size.height), Offset(x + size.height + torcer, 0f),
                    strokeWidth = borde
                )
                x += size.width / 3.2f
            }
        }
        FillStyle.CROSS_HATCH -> {
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    tinta, Offset(x, size.height), Offset(x + size.height, 0f),
                    strokeWidth = borde
                )
                drawLine(
                    tinta, Offset(x, 0f), Offset(x + size.height, size.height),
                    strokeWidth = borde
                )
                x += size.width / 2.4f
            }
        }
        FillStyle.ZIGZAG -> {
            val paso = size.width / 4
            var y = paso / 2
            while (y < size.height) {
                var x = 0f
                var arriba = true
                while (x < size.width) {
                    drawLine(
                        tinta, Offset(x, if (arriba) y else y + paso / 2),
                        Offset(x + paso / 2, if (arriba) y + paso / 2 else y),
                        strokeWidth = borde
                    )
                    x += paso / 2
                    arriba = !arriba
                }
                y += paso
            }
        }
    }
}

/** Continua, a trazos o de puntos: la propia raya. */
fun DrawScope.dibujarLinea(ss: StrokeStyle, tinta: Color) {
    val y = size.height / 2
    val grosor = 2.dp.toPx()
    when (ss) {
        StrokeStyle.SOLID ->
            drawLine(tinta, Offset(0f, y), Offset(size.width, y), strokeWidth = grosor)
        StrokeStyle.DASHED -> {
            var x = 0f
            while (x < size.width) {
                drawLine(
                    tinta, Offset(x, y),
                    Offset(minOf(x + size.width / 5, size.width), y), strokeWidth = grosor
                )
                x += size.width / 3
            }
        }
        StrokeStyle.DOTTED -> {
            var x = grosor
            while (x < size.width) {
                drawCircle(tinta, radius = grosor / 2, center = Offset(x, y))
                x += size.width / 5
            }
        }
    }
}

/**
 * El pulso: una raya cada vez más torcida.
 *
 * Es lo que hace la rugosidad, así que enseñarlo es enseñarla. Con glifos había
 * que probar los tres para saber cuál era cuál.
 */
fun DrawScope.dibujarPulso(cuanto: Int, tinta: Color) {
    val y = size.height / 2
    val grosor = 2.dp.toPx()
    if (cuanto <= 0) {
        drawLine(tinta, Offset(0f, y), Offset(size.width, y), strokeWidth = grosor)
        return
    }
    val vaiven = size.height / 5f * cuanto
    val camino = Path().apply {
        moveTo(0f, y)
        val tramos = 4
        for (i in 1..tramos) {
            val x = size.width * i / tramos
            val alto = if (i % 2 == 0) y - vaiven else y + vaiven
            quadraticTo(x - size.width / (tramos * 2f), alto, x, y)
        }
    }
    drawPath(camino, tinta, style = Stroke(width = grosor))
}
