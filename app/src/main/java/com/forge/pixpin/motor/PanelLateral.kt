package com.forge.pixpin.motor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

    Column(
        modifier = modifier.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (Propiedad.TRAZO in aplican) {
            SelectorArrastrable(
                opciones = colores,
                actual = colores.indexOf(estilo.strokeColor).coerceAtLeast(0),
                haciaLaIzquierda = haciaLaIzquierda,
                descripcion = "Color del trazo",
                onElegir = { onEstilo(estilo.copy(strokeColor = colores[it])) }
            ) { hex, _ -> MuestraDeColor(hex) }
        }

        if (Propiedad.FONDO in aplican) {
            SelectorArrastrable(
                opciones = coloresDeFondo,
                actual = coloresDeFondo.indexOf(estilo.backgroundColor).coerceAtLeast(0),
                haciaLaIzquierda = haciaLaIzquierda,
                descripcion = "Color de fondo",
                onElegir = { onEstilo(estilo.copy(backgroundColor = coloresDeFondo[it])) }
            ) { hex, _ -> MuestraDeColor(hex) }
        }

        if (Propiedad.RELLENO in aplican) {
            val rellenos = FillStyle.entries
            SelectorArrastrable(
                opciones = rellenos,
                actual = rellenos.indexOf(estilo.fillStyle),
                haciaLaIzquierda = haciaLaIzquierda,
                descripcion = "Relleno",
                onElegir = { onEstilo(estilo.copy(fillStyle = rellenos[it])) }
            ) { fs, tinta -> Canvas(Modifier.size(20.dp)) { dibujarRelleno(fs, tinta) } }
        }

        if (Propiedad.LINEA in aplican) {
            val lineas = StrokeStyle.entries
            SelectorArrastrable(
                opciones = lineas,
                actual = lineas.indexOf(estilo.strokeStyle),
                haciaLaIzquierda = haciaLaIzquierda,
                descripcion = "Tipo de línea",
                onElegir = { onEstilo(estilo.copy(strokeStyle = lineas[it])) }
            ) { ss, tinta -> Canvas(Modifier.size(22.dp)) { dibujarLinea(ss, tinta) } }
        }

        if (Propiedad.GROSOR in aplican) {
            val anchos = ItemStyle.STROKE_WIDTHS
            val puesto = masCercano(estilo.strokeWidth, anchos)
            DeslizadorVertical(
                fraccion = fraccionDeLaCasilla(puesto, anchos.size),
                descripcion = "Grosor",
                onFraccion = { f ->
                    val i = casillaDe(f, anchos.size)
                    if (i != puesto) onEstilo(estilo.copy(strokeWidth = anchos[i]))
                }
            ) { tinta, avance ->
                // La propia raya, del grosor que se está eligiendo: se ve gorda
                // cuando es gorda. Es lo que se viene a mirar.
                drawLine(
                    tinta,
                    Offset(size.width * 0.2f, size.height / 2),
                    Offset(size.width * 0.8f, size.height / 2),
                    strokeWidth = (1f + avance * 7f).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        if (Propiedad.OPACIDAD in aplican) {
            DeslizadorVertical(
                fraccion = fraccionDelValor(estilo.opacity, MINIMA_OPACIDAD, 100),
                descripcion = "Opacidad",
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
    }
}

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
    dibujo: DrawScope.(tinta: Color, avance: Float) -> Unit
) {
    val densidad = LocalDensity.current
    val vibrar = LocalHapticFeedback.current
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val relleno = MaterialTheme.colorScheme.primary
    val tinta = MaterialTheme.colorScheme.onSurface
    var altoPx by remember { mutableFloatStateOf(0f) }
    val recorrido = ALTO_DEL_DESLIZADOR - MANGO

    Surface(
        shape = RoundedCornerShape(ANCHO / 2),
        color = fondo,
        shadowElevation = 3.dp
    ) {
        Box(
            Modifier
                .width(ANCHO)
                .height(ALTO_DEL_DESLIZADOR)
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
            Canvas(Modifier.size(ANCHO, ALTO_DEL_DESLIZADOR)) {
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
                    .border(1.dp, relleno, CircleShape)
            ) {
                Canvas(Modifier.size(MANGO - 4.dp)) { dibujo(tinta, fraccion) }
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

    val tinta = MaterialTheme.colorScheme.onSurface

    // **Del tamaño de la bolita y nada más.** La fila de opciones se mide
    // aparte —`unbounded`— y se coloca por encima sin contar para el tamaño: si
    // contara, el panel entero se ensancharía de golpe al empezar a arrastrar y
    // todo lo de al lado daría un salto.
    Box(Modifier.size(BOLA), contentAlignment = Alignment.Center) {
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
                        Surface(
                            shape = CircleShape,
                            color = if (elegida) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            shadowElevation = 4.dp,
                            modifier = Modifier.size(if (elegida) BOLA + 6.dp else BOLA)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                contenido(
                                    opciones[i],
                                    if (elegida) MaterialTheme.colorScheme.onPrimary else tinta
                                )
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
                .size(BOLA)
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
                opciones.getOrNull(actual)?.let { contenido(it, tinta) }
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

private val ANCHO: Dp = 32.dp
private val MANGO: Dp = 30.dp
private val ALTO_DEL_DESLIZADOR: Dp = 116.dp
private val BOLA: Dp = 32.dp

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
