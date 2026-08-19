package com.forge.pixpin.motor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
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
    onEstilo: (ItemStyle) -> Unit,
    /**
     * A qué zoom se está mirando el lienzo.
     *
     * Hace falta para enseñar **el grosor de verdad**: cuatro puntos de escena
     * son cuatro píxeles al cien por cien y cuarenta al mil por cien. Sin esto,
     * la muestra del deslizador mentiría justo cuando más se mira, que es
     * trabajando de cerca.
     */
    zoom: Float = 1f,
    /**
     * Lo ancho que se pinta de verdad el trazo con el grosor que hay puesto, en
     * píxeles de escena.
     *
     * Casi siempre es el propio grosor —una raya de cuatro sale de cuatro— y por
     * eso a null se usa ese. El lápiz no: pinta una mancha de alrededor del
     * triple, y el marcador todavía más, así que quien lo sepa lo dice y la
     * muestra enseña el trazo que va a salir en vez del número elegido. Ver
     * [anchoPintadoDelLapiz].
     */
    anchoPintado: Double? = null,
    /** Las marcas guardadas de cada deslizador. Ver [MarcasDelDeslizador]. */
    marcas: Map<Deslizador, List<Float>> = emptyMap(),
    onMarcas: ((Deslizador, List<Float>) -> Unit)? = null,
    /**
     * La trama se enseña aunque no haya fondo puesto.
     *
     * Lo pide quien sabe qué se está dibujando: una región **es** relleno, así que su
     * trama es la mitad de la decisión. Ver [rellenoALaVista].
     */
    rellenoObligatorio: Boolean = false,
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
    // La trama solo sale con un fondo puesto: sin él no hay nada que tramar y
    // apagar el relleno es cosa del color de fondo. Ver más abajo.
    // **Con el bote, la trama sale siempre.**
    //
    // La regla de «solo con un fondo puesto» está bien para un rectángulo —sin fondo no
    // hay nada que tramar— pero con el bote de relleno **no hay figura sin fondo**: lo
    // único que hace esa herramienta es rellenar, y el mando de con qué trama rellenar
    // desaparecía justo ahí. Se elegía el color y no había forma de decir si iba macizo o
    // a rayas hasta después de haber pintado algo.
    val rellenoALaVista = Propiedad.RELLENO in aplican &&
        (rellenoObligatorio || !isTransparent(estilo.backgroundColor))
    val cuantos = aplican.count { it in EN_EL_LATERAL } +
        (if (Propiedad.RELLENO in aplican && !rellenoALaVista) -1 else 0) +
        (if (Propiedad.FUENTE in aplican) 1 else 0) +
        (if (Propiedad.RUGOSIDAD in aplican) 2 else 0)
    val apretado = cuantos >= 7
    val bola = if (apretado) 26.dp else BOLA
    val altoDeslizador = if (apretado) ALTO_APRETADO else ALTO_DEL_DESLIZADOR

    // **Sin desplazamiento.** Un contenedor que se desplaza recorta lo que se
    // sale de él, y de aquí se sale justo lo que hay que ver: la fila de
    // opciones que sale de la bolita salía cortada por el borde del panel. Antes
    // que eso, los controles van pequeños y apretados para que quepan.
    Column(
        modifier = modifier
            // **Separado del borde y fuera del gesto del sistema.**
            //
            // Pegado al canto, arrastrar la bolita hacia el lienzo era empezar
            // un deslizamiento desde el borde: Android se lo quedaba como el
            // gesto de «atrás» y el editor se cerraba en mitad de un cambio de
            // color. El hueco lo separa del canto, y `systemGestureExclusion`
            // le dice al sistema que esta franja es nuestra —lo mismo que hace
            // cualquier app con un menú lateral.
            .padding(start = SEPARACION_DEL_BORDE, end = SEPARACION_DEL_BORDE, top = 6.dp, bottom = 6.dp)
            .systemGestureExclusion()
            // **Y todos dentro de una misma cápsula.**
            //
            // Sueltos sobre el dibujo se leían como seis cosas que casualmente
            // están en fila; con el fondo detrás son **un mando**, y además se
            // ven: una bolita blanca sobre un dibujo claro no se distinguía.
            //
            // Va con `background` y no con una `Surface`: una superficie con
            // forma **recorta a sus hijos**, y de aquí tiene que poder salirse
            // la fila de opciones de la bolita, que es lo que hace que elegir un
            // color sea un solo gesto. `background` solo pinta detrás.
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = FONDO_DEL_PANEL),
                RoundedCornerShape(CANTO_DEL_PANEL)
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SEPARACION)
    ) {
        // **El color ya no está aquí, y es a propósito.**
        //
        // Estuvieron las dos bolitas —trazo y fondo— y desde que el color vive
        // en la barra de arriba eran la misma decisión en dos sitios. Peor que
        // redundante: la bolita solo conocía sus cinco colores, así que al coger
        // uno de la paleta se quedaba señalando el primero de su lista, y tocarla
        // deshacía lo que acababas de elegir. Un mando que miente sobre lo que
        // hay puesto es peor que no tenerlo. Ver [PaletaDeColores].

        // **La trama solo sale si hay algo que tramar.**
        //
        // Tenía una opción de más, y de las que confunden: un ∅ de «sin relleno»
        // que hacía exactamente lo mismo que la primera muestra del color de
        // fondo —poner el fondo en transparente—. La misma decisión desde dos
        // sitios, y encima uno de ellos, al encenderse, se inventaba un color de
        // fondo que nadie había pedido.
        //
        // Ahora el relleno se apaga donde siempre estuvo, en el color de fondo,
        // y la trama aparece cuando hay fondo que tramar. Es como lo hace el
        // original y deja cada control con una sola cosa que decir.
        if (rellenoALaVista) {
            val rellenos = RELLENOS_QUE_SE_OFRECEN
            SelectorArrastrable(
                opciones = rellenos,
                actual = rellenos.indexOf(estilo.fillStyle).coerceAtLeast(0),
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Relleno",
                // Con el color del fondo, que es con el que se va a rellenar.
                tinta = tintaDelFondo,
                onElegir = { onEstilo(estilo.copy(fillStyle = rellenos[it])) }
            ) { fs, tinta -> Canvas(Modifier.size(20.dp)) { dibujarRelleno(fs, tinta) } }
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

        // **La lupa: tres mandos y ninguno más.** Cuánto agranda, de qué forma
        // es el cristal y si lleva guía. Todo lo demás de una lupa —a dónde
        // mira, dónde se ve— se hace con el dedo encima del dibujo, que es donde
        // se entiende: un mando para las coordenadas del foco sería pedirle a
        // alguien que apunte una lupa escribiendo números.
        if (Propiedad.LUPA in aplican) {
            // Tres formas de señalar: nada, flecha o el cono de dos rayas —el
            // de los planos de toda la vida—. Cada icono **es** lo que dibuja.
            val guias = GuiaDeLupa.entries
            SelectorArrastrable(
                opciones = guias,
                actual = guias.indexOf(estilo.guia),
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Guía",
                tinta = tintaDelTrazo,
                onElegir = { i -> onEstilo(estilo.copy(guia = guias[i])) }
            ) { cual, tinta ->
                Canvas(Modifier.size(18.dp)) {
                    val lado = size.width / 2.6f
                    drawRect(tinta, Offset(0f, 0f), Size(lado, lado), style = Stroke(1.5f))
                    when (cual) {
                        GuiaDeLupa.NINGUNA -> Unit
                        GuiaDeLupa.FLECHA -> drawLine(
                            tinta, Offset(lado, lado), Offset(size.width, size.height),
                            strokeWidth = 1.5f
                        )
                        GuiaDeLupa.DOS_LINEAS -> {
                            drawLine(
                                tinta, Offset(lado, 0f), Offset(size.width, size.height - lado),
                                strokeWidth = 1.5f
                            )
                            drawLine(
                                tinta, Offset(0f, lado), Offset(size.width - lado, size.height),
                                strokeWidth = 1.5f
                            )
                        }
                        // El punto gordo, que es literalmente lo que dibuja.
                        GuiaDeLupa.PUNTO -> {
                            drawCircle(tinta, radius = lado / 2.2f, center = Offset(lado / 2, lado / 2))
                            drawLine(
                                tinta, Offset(lado, lado), Offset(size.width, size.height),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }
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
                marcas = marcas[Deslizador.GROSOR].orEmpty(),
                onMarcas = onMarcas?.let { f -> { m: List<Float> -> f(Deslizador.GROSOR, m) } },
                descripcion = "Grosor",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                haciaLaIzquierda = haciaLaIzquierda,
                // **La muestra a tamaño real, como en cualquier app de dibujo.**
                // Mientras se arrastra sale al lado un punto del grosor exacto
                // con el que va a salir el trazo **a este zoom**, y su número.
                // Un deslizador que solo se dibuja a sí mismo obliga a soltar,
                // mirar el trazo, y volver.
                // El número va en tanto por ciento del recorrido, como el de la
                // opacidad; el punto de al lado sigue siendo el trazo de verdad,
                // que es lo que se mira para decidir. Ver [porcentajeDelGrosor].
                muestra = { "${porcentajeDelGrosor(estilo.strokeWidth)} %" to
                    ((anchoPintado ?: estilo.strokeWidth) * zoom).toFloat() },
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
                haciaLaIzquierda = haciaLaIzquierda,
                muestra = { estilo.fontSize.toInt().toString() to 0f },
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

        // **El aumento se arrastra, no se elige de una lista.**
        //
        // Buscar cuánto agrandar es exactamente lo que se hace mirando: se sube
        // hasta que el detalle se lee y ahí se para. Con siete opciones sueltas
        // había que probarlas una a una, y ninguna caía donde hacía falta. Va
        // con los otros dos deslizadores porque se usa igual que ellos.
        if (Propiedad.LUPA in aplican) {
            DeslizadorVertical(
                fraccion = fraccionDelAumento(estilo.aumento),
                marcas = marcas[Deslizador.AUMENTO].orEmpty(),
                onMarcas = onMarcas?.let { f -> { m: List<Float> -> f(Deslizador.AUMENTO, m) } },
                descripcion = "Aumento",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                haciaLaIzquierda = haciaLaIzquierda,
                muestra = { "×" + redondeoDelAumento(estilo.aumento) to 0f },
                onFraccion = { f ->
                    val a = aumentoDeLaFraccion(f)
                    if (a != estilo.aumento) onEstilo(estilo.copy(aumento = a))
                }
            ) { tinta, avance ->
                // Dos aros: el pequeño es lo que se mira y el grande lo que se
                // ve. Cuanto más avanza el mando, más se separan.
                drawCircle(tinta, radius = size.minDimension / 6f, style = Stroke(1.5f))
                drawCircle(
                    tinta,
                    radius = size.minDimension / 6f + avance * size.minDimension / 3f,
                    style = Stroke(1.5f)
                )
            }
        }

        // **Cuánto se apaga lo de fuera del foco.** De 10 a 90: por debajo no se
        // nota que hay foco y por encima lo de alrededor deja de verse, y
        // entonces ya no está resaltando nada — está tapando.
        if (Propiedad.OSCURECER in aplican) {
            DeslizadorVertical(
                fraccion = fraccionDelValor(estilo.oscurecer, OSCURECER_MINIMO, OSCURECER_MAXIMO),
                descripcion = "Oscurecer",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                haciaLaIzquierda = haciaLaIzquierda,
                muestra = { "${estilo.oscurecer} %" to 0f },
                onFraccion = { f ->
                    val v = valorConPaso(f, OSCURECER_MINIMO, OSCURECER_MAXIMO, PASO_DE_OPACIDAD)
                    if (v != estilo.oscurecer) onEstilo(estilo.copy(oscurecer = v))
                }
            ) { tinta, avance ->
                // Un disco que se va llenando de sombra: lo que se está haciendo.
                drawCircle(tinta.copy(alpha = 0.25f), radius = size.minDimension / 2.6f)
                drawCircle(
                    tinta.copy(alpha = 0.25f + avance * 0.75f),
                    radius = size.minDimension / 2.6f * avance
                )
            }
        }

        // La zona iluminada de un foco: cuánto del marco queda a la vista.
        if (Propiedad.ZONA in aplican) {
            DeslizadorVertical(
                fraccion = ((estilo.zona - ZONA_MINIMA) / (ZONA_MAXIMA - ZONA_MINIMA))
                    .toFloat().coerceIn(0f, 1f),
                marcas = marcas[Deslizador.ZONA].orEmpty(),
                onMarcas = onMarcas?.let { f -> { m: List<Float> -> f(Deslizador.ZONA, m) } },
                descripcion = "Zona",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                haciaLaIzquierda = haciaLaIzquierda,
                muestra = { "${Math.round(estilo.zona * 100)} %" to 0f },
                onFraccion = { f ->
                    val z = ZONA_MINIMA + (ZONA_MAXIMA - ZONA_MINIMA) * f
                    val redondo = Math.round(z * 100) / 100.0
                    if (redondo != estilo.zona) onEstilo(estilo.copy(zona = redondo))
                }
            ) { tinta, avance ->
                // Un aro fijo —el marco— y dentro un disco que crece: es
                // exactamente lo que hace el mando.
                drawCircle(tinta, radius = size.minDimension / 2.4f, style = Stroke(1.5f))
                drawCircle(tinta, radius = size.minDimension / 2.4f * (0.15f + avance * 0.85f))
            }
        }

        if (Propiedad.OPACIDAD in aplican) {
            DeslizadorVertical(
                fraccion = fraccionDelValor(estilo.opacity, MINIMA_OPACIDAD, 100),
                marcas = marcas[Deslizador.OPACIDAD].orEmpty(),
                onMarcas = onMarcas?.let { f -> { m: List<Float> -> f(Deslizador.OPACIDAD, m) } },
                descripcion = "Opacidad",
                alto = altoDeslizador,
                tinta = tintaDelTrazo,
                haciaLaIzquierda = haciaLaIzquierda,
                muestra = { "${estilo.opacity} %" to 0f },
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

        // **Negrita, cursiva y tachado: los tres a la vista.**
        //
        // Son tres interruptores independientes y se combinan, así que una
        // bolita que se arrastra no vale —esa elige *una* de varias— y un
        // desplegable tampoco: se encienden y se apagan sobre la marcha, mientras
        // se escribe. Tres botones que se quedan pulsados es lo que hace
        // cualquier editor de texto, y se lee sin explicación.
        if (Propiedad.ESTILO_DE_TEXTO in aplican) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                BotonDeTexto("B", estilo.negrita, bola, FontWeight.Bold) {
                    onEstilo(estilo.copy(negrita = !estilo.negrita))
                }
                BotonDeTexto("I", estilo.cursiva, bola, cursiva = true) {
                    onEstilo(estilo.copy(cursiva = !estilo.cursiva))
                }
                BotonDeTexto("S", estilo.tachado, bola, tachado = true) {
                    onEstilo(estilo.copy(tachado = !estilo.tachado))
                }
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

/**
 * Las tramas que se ofrecen: **cuatro, no cinco**.
 *
 * `FillStyle` tiene cinco, pero dos de ellas son rayas diagonales —el rayado a
 * mano y el de tiralíneas— y en una muestra de veinte píxeles se leen como la
 * misma opción repetida. La de tiralíneas se queda en el modelo, porque hay
 * dibujos guardados con ella y tiene que seguir pintándose; lo que se quita es
 * de la lista de elegir, que es donde molestaba.
 */
private val RELLENOS_QUE_SE_OFRECEN = listOf(
    FillStyle.HACHURE, FillStyle.CROSS_HATCH, FillStyle.ZIGZAG, FillStyle.SOLID
)

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
    Propiedad.ESTILO_DE_TEXTO,
    // El trazo y el fondo **no están**: el color se elige en la paleta de
    // arriba. Ver la nota del principio de la lista de controles.
    Propiedad.RELLENO, Propiedad.LINEA,
    Propiedad.ESQUINAS, Propiedad.FORMA_FLECHA, Propiedad.MOSAICO, Propiedad.LUPA,
    Propiedad.GROSOR, Propiedad.LUPA, Propiedad.ZONA, Propiedad.OSCURECER,
    Propiedad.OPACIDAD, Propiedad.FUENTE
)

/**
 * Lo menos opaco que se deja llegar.
 *
 * **Cero.** Estuvo en diez, con el argumento de que a cero no queda nada que
 * ver; pero un deslizador que no llega a su tope de abajo se nota —el mango se
 * planta antes de la raya— y la vuelta atrás es inmediata: se sube y ahí sigue
 * todo. Con el recorrido entero, lo que dice el mando y lo que hay es lo mismo.
 */
private const val MINIMA_OPACIDAD = 0
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
/**
 * El aumento, de 0 a 1 y al revés.
 *
 * No lineal: **por su logaritmo**. De ×2 a ×3 se nota muchísimo y de ×10 a ×11
 * casi nada, así que un recorrido lineal deja media barra para diferencias que
 * nadie ve y aprieta al principio justo donde se decide. Con el logaritmo, el
 * mismo trozo de recorrido dobla el aumento en cualquier punto de la barra.
 */
internal fun fraccionDelAumento(aumento: Double): Float {
    val a = aumento.coerceIn(AUMENTO_MINIMO, AUMENTO_MAXIMO)
    val lo = kotlin.math.ln(AUMENTO_MINIMO)
    val hi = kotlin.math.ln(AUMENTO_MAXIMO)
    return ((kotlin.math.ln(a) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

internal fun aumentoDeLaFraccion(f: Float): Double {
    val lo = kotlin.math.ln(AUMENTO_MINIMO)
    val hi = kotlin.math.ln(AUMENTO_MAXIMO)
    val bruto = kotlin.math.exp(lo + (hi - lo) * f.coerceIn(0f, 1f).toDouble())
    // A un decimal: los números redondos son los que uno quiere apuntar en una
    // lámina, y ×2,4 y ×2,41 no se distinguen mirando.
    return (Math.round(bruto * 10) / 10.0).coerceIn(AUMENTO_MINIMO, AUMENTO_MAXIMO)
}

/** El aumento como se escribe: sin decimal si no hace falta. */
internal fun redondeoDelAumento(aumento: Double): String {
    // **Un decimal como mucho.** El aumento sale de una división —lo que mide el
    // cristal partido por lo que mide la zona— y eso da números como
    // 3.7142857142857144. Nadie apunta eso en una lámina, y encima no cabía.
    val redondo = Math.round(aumento * 10) / 10.0
    return if (redondo % 1.0 == 0.0) redondo.toInt().toString() else "$redondo"
}

@Composable
private fun DeslizadorVertical(
    fraccion: Float,
    descripcion: String,
    onFraccion: (Float) -> Unit,
    /**
     * Los valores guardados de este deslizador, de 0 a 1.
     *
     * Se pintan en la barra y **tiran del mango** al pasar cerca. Ver
     * [MarcasDelDeslizador]: lo que resuelven es volver al grosor con el que uno
     * escribe, que a ojo nunca sale el mismo dos veces.
     */
    marcas: List<Float> = emptyList(),
    onMarcas: ((List<Float>) -> Unit)? = null,
    /** Lo que mide de alto. Encoge cuando hay muchos controles. */
    alto: Dp = ALTO_DEL_DESLIZADOR,
    tinta: Color? = null,
    /** Lo que va dentro del mango si no es un dibujo, como una letra de verdad. */
    dentro: (@Composable () -> Unit)? = null,
    /** Hacia dónde sale la muestra: al lado del lienzo, no al del borde. */
    haciaLaIzquierda: Boolean = false,
    /**
     * Qué enseñar mientras se arrastra: el número y, si tiene sentido, el
     * tamaño **real en píxeles de pantalla** de lo que se está eligiendo. A cero
     * el segundo, solo sale el número.
     */
    muestra: (() -> Pair<String, Float>)? = null,
    dibujo: DrawScope.(tinta: Color, avance: Float) -> Unit = { _, _ -> }
) {
    val vibrar = LocalHapticFeedback.current
    val relleno = MaterialTheme.colorScheme.primary
    val tintaDelMango = tinta ?: MaterialTheme.colorScheme.onSurface
    var arrastrando by remember { mutableStateOf(false) }

    // **El relleno persigue al dedo en vez de saltar.** Con el valor a pelo, el
    // deslizador da brincos de casilla en casilla; con el muelle, la barra va
    // detrás del dedo y el control se siente continuo aunque el valor no lo sea.
    val suave by animateFloatAsState(
        targetValue = fraccion,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "relleno"
    )
    // Y engorda un pelo al agarrarlo, como cualquier deslizador de hoy. Va en la
    // capa de dibujo —`graphicsLayer`— y no en el tamaño: si cambiara el tamaño
    // movería a sus vecinos, que es justo el temblor que había que quitar.
    val engorde by animateFloatAsState(
        targetValue = if (arrastrando) 1.14f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "engorde"
    )

    // **De tamaño fijo, pase lo que pase.** Aquí dentro va también la pastilla
    // de la muestra, que es ancha y aparece y desaparece; sin fijar el tamaño,
    // el panel entero se ensanchaba al agarrar el deslizador y volvía a
    // encogerse al soltar, y cada cambio de cifra lo movía otra vez. Eso era el
    // temblor de la barra.
    Box(Modifier.size(ANCHO, alto), contentAlignment = Alignment.Center) {
        MuestraAlArrastrar(arrastrando, muestra, haciaLaIzquierda, tintaDelMango)

        // **La función se relee en cada arrastre, y aquí había un fallo feo.**
        //
        // `pointerInput(Unit)` no se reinicia al recomponer: se queda con las
        // funciones de la **primera** vez y las llama siempre. Y esta función
        // lleva dentro el estilo que el panel estaba enseñando entonces, así que
        // cambiar el color de una figura y tocar después cualquier deslizador
        // le volvía a escribir **el color de antes** — se veía como si el color
        // se deshiciera solo. Pasaba en los tres: grosor, opacidad y aumento.
        val avisar by rememberUpdatedState(onFraccion)
        val guardar by rememberUpdatedState(onMarcas)
        val lasMarcas by rememberUpdatedState(marcas)
        // Si está abierto el botoncito de poner o quitar marca. Se abre tocando
        // **el propio mango**, que es donde uno mira cuando piensa «este valor».
        var menuDeMarca by remember { mutableStateOf(false) }

        /** Lo que se avisa ya imantado a las marcas. */
        val avisarConIman: (Float) -> Unit = { f -> avisar(conIman(f, lasMarcas)) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = engorde }
                .clip(RoundedCornerShape(ANCHO / 2))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = descripcion }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val donde = fraccionVertical(pos.y, size.height.toFloat())
                        // **Tocar el mango no lo mueve: abre el guardar.** Es el
                        // único sitio de la barra donde tocar ya no significaba
                        // nada —el mango ya está ahí— así que es el que queda
                        // libre para esto, y además es donde se está mirando.
                        if (guardar != null &&
                            tocaElMango(pos.y, size.height.toFloat(), fraccion, MANGO.toPx())
                        ) {
                            menuDeMarca = !menuDeMarca
                            vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                            return@detectTapGestures
                        }
                        menuDeMarca = false
                        vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        avisarConIman(donde)
                    }
                }
                .pointerInput(Unit) {
                    // Se sigue **la posición**, no el incremento: así el mango
                    // va donde está el dedo y no se va quedando atrás al llegar
                    // a los topes, que es lo que pasa acumulando deltas.
                    var y = 0f
                    detectVerticalDragGestures(
                        onDragStart = { inicio ->
                            y = inicio.y
                            arrastrando = true
                            vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragEnd = { arrastrando = false },
                        onDragCancel = { arrastrando = false },
                        onVerticalDrag = { cambio, delta ->
                            cambio.consume()
                            y += delta
                            avisarConIman(fraccionVertical(y, size.height.toFloat()))
                        }
                    )
                }
        ) {
            // La barra llena, de abajo hasta donde va el valor, con el color
            // que hay puesto: el deslizador **es** una muestra más.
            Canvas(Modifier.fillMaxSize()) {
                val altoLleno = size.height * suave
                drawRect(
                    relleno.copy(alpha = 0.30f),
                    topLeft = Offset(0f, size.height - altoLleno),
                    size = Size(size.width, altoLleno)
                )
                // Las marcas guardadas: una rayita cruzando la barra. Se pintan
                // **dentro** de la barra y no fuera para que no le roben sitio
                // al lienzo, que es lo que hay al otro lado.
                for (m in lasMarcas) {
                    // Con la cuenta del mango, no con la corta: ver [yDelMango].
                    val y = yDelMango(m, size.height, MANGO.toPx())
                    drawLine(
                        relleno.copy(alpha = 0.9f),
                        Offset(size.width * 0.15f, y),
                        Offset(size.width * 0.85f, y),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // El mango, fuera de la capa que engorda para que no se deforme.
        //
        // **En reposo va limpio, sin nada dentro.** Llevaba siempre la muestra
        // —la rayita del grosor, el disco de la opacidad— y en un deslizador que
        // está ahí todo el rato eso es una manchita permanente encima del
        // dibujo, justo en el borde por el que se mira. La muestra sigue
        // estando, pero **donde hace falta**: mientras se arrastra, y en la
        // pastilla de al lado, que es grande y se lee. Ver [MuestraAlArrastrar].
        val recorrido = alto - MANGO
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = recorrido * (1f - suave))
                .size(MANGO)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                // **Blanco, y no el color del tema.** El mango se mira contra el
                // dibujo, no contra la aplicación: en modo noche el `surface` es
                // casi negro y el mango desaparecía dentro de su propia barra,
                // que es justo lo que no puede pasarle a la pieza que se agarra.
                .background(BLANCO_DEL_MANGO),
            contentAlignment = Alignment.Center
        ) {
            if (arrastrando) {
                if (dentro != null) {
                    dentro()
                } else {
                    Canvas(Modifier.size(MANGO - 8.dp)) { dibujo(tintaDelMango, suave) }
                }
            }
        }

        // **Poner o quitar la marca, al lado del mango.**
        //
        // Sale al tocar el mango y se va al elegir o al tocar cualquier otra
        // parte de la barra: es un gesto de dos toques y no un modo. Va hacia el
        // lienzo, como todo lo que sale de este panel, porque hacia el otro lado
        // está el borde de la pantalla.
        val puesta = marcaEn(lasMarcas, fraccion)
        if (menuDeMarca && guardar != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(
                        x = if (haciaLaIzquierda) -BOTON_DE_MARCA else BOTON_DE_MARCA,
                        y = recorrido * (1f - suave)
                    )
                    .size(MANGO)
                    .clickable {
                        val nuevas = if (puesta != null) {
                            sinMarca(lasMarcas, fraccion)
                        } else {
                            conMarca(lasMarcas, fraccion)
                        }
                        guardar?.invoke(nuevas)
                        menuDeMarca = false
                        vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (puesta != null) "×" else "+",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * La pastilla que sale al lado mientras se arrastra un deslizador.
 *
 * Va **fuera de la medida**: se dibuja sin ocupar sitio, porque el sitio que
 * ocuparía se lo quitaría a sus vecinos cada vez que aparece o cambia de cifra.
 *
 * ## Y de tamaño fijo, que es lo que faltaba
 *
 * Fijar el hueco del deslizador impidió que **el panel** se ensanchara, pero la
 * pastilla seguía creciendo con el valor: el punto de muestra va del tamaño del
 * trazo, así que de un grosor fino a uno gordo pasaba de dos píxeles a cuarenta
 * y cuatro. Y como se centra sobre el deslizador, cada píxel que crecía la
 * estiraba **medio píxel hacia cada lado**: la pastilla se iba corriendo sobre
 * el panel según se subía el grosor, en proporción exacta al cambio. Se veía
 * como que la barra entera se desplazaba, y el sitio que ocupa una cosa no puede
 * depender de lo que dice.
 *
 * Así que el punto vive en un hueco del tamaño del punto **más gordo posible** y
 * crece dentro de él, y la cifra tiene un ancho mínimo para que «2» y «12,25` no
 * midan distinto. La pastilla mide siempre lo mismo y no se mueve de su sitio.
 */
@Composable
private fun MuestraAlArrastrar(
    arrastrando: Boolean,
    muestra: (() -> Pair<String, Float>)?,
    haciaLaIzquierda: Boolean,
    tinta: Color
) {
    if (muestra == null) return
    val densidad = LocalDensity.current
    AnimatedVisibility(
        visible = arrastrando,
        enter = fadeIn(spring()) + scaleIn(spring(), initialScale = 0.85f),
        exit = fadeOut(spring())
    ) {
        val (texto, tamPx) = muestra()
        Box(
            Modifier
                .wrapContentSize(unbounded = true)
                .offset(
                    x = if (haciaLaIzquierda) -SEPARACION_DE_LA_MUESTRA
                    else SEPARACION_DE_LA_MUESTRA
                )
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tamPx > 0f) {
                        // **El punto, del tamaño exacto que va a salir**, dentro
                        // de un hueco que mide siempre lo mismo. Con tope: a
                        // mucho zoom un trazo de veinte puntos ocupa media
                        // pantalla y la muestra dejaría de caber.
                        val lado = with(densidad) { tamPx.toDp() }
                            .coerceIn(2.dp, PUNTO_DE_LA_MUESTRA)
                        Box(
                            Modifier.size(PUNTO_DE_LA_MUESTRA),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(lado)
                                    .clip(CircleShape)
                                    .background(tinta)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        texto,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        // Ancho mínimo y centrada: sin esto la pastilla se
                        // encoge y se estira a cada cifra, que es el mismo baile
                        // en pequeño.
                        textAlign = ComposeTextAlign.Center,
                        modifier = Modifier.widthIn(min = CIFRA_DE_LA_MUESTRA)
                    )
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
    val elegir by rememberUpdatedState(onElegir)

    val tintaDeLaMuestra = tinta ?: MaterialTheme.colorScheme.onSurface

    // **Del tamaño de la bolita y nada más.** La fila de opciones se mide
    // aparte —`unbounded`— y se coloca por encima sin contar para el tamaño: si
    // contara, el panel entero se ensancharía de golpe al empezar a arrastrar y
    // todo lo de al lado daría un salto.
    Box(Modifier.size(bola), contentAlignment = Alignment.Center) {
        // **Las opciones, saliendo de la bolita hacia el lienzo.** Van fuera de
        // cualquier Surface con forma: una superficie redondeada recorta a sus
        // hijos, y esta fila tiene que poder salirse del panel.
        AnimatedVisibility(
            visible = abierto,
            enter = fadeIn(spring()) + scaleIn(spring(), initialScale = 0.7f),
            exit = fadeOut(spring())
        ) {
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
                    // El crecer de la marcada, con muelle: saltando de tamaño
                    // se ve como un parpadeo y no como que la has cogido.
                    val tamDeLaOpcion by animateDpAsState(
                        targetValue = if (elegida) bola + 8.dp else bola,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "opcion"
                    )
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
                            modifier = Modifier.size(tamDeLaOpcion)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                contenido(opciones[i], tintaDeLaMuestra)
                            }
                        }
                    }
                }
            }
        }

        val engorde by animateFloatAsState(
            targetValue = if (abierto) 1.2f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "bolita"
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = if (abierto) 10.dp else 3.dp,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(corrimiento.value.toInt(), 0) }
                .graphicsLayer { scaleX = engorde; scaleY = engorde }
                .size(bola)
                .semantics { contentDescription = descripcion }
        ) {
            Box(
                // Lo mismo que en el deslizador: el gesto se queda con la
                // función de cuando arrancó, y esa lleva dentro el estilo de
                // entonces. Ver [DeslizadorVertical].
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
                            if (abierto) elegir(marcada)
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

private val ANCHO: Dp = 30.dp
private val MANGO: Dp = 28.dp

/**
 * Lo que miden los controles.
 *
 * Van justos a propósito: **el panel no se desplaza** —desplazarse recortaría
 * el desplegable de la bolita— así que todo lo que salga tiene que caber de una
 * vez. Con una figura seleccionada salen ocho controles, y esa es la cuenta que
 * manda estos números.
 *
 * Los deslizadores son **lo más largo que cabe**: son los dos únicos controles
 * que se recorren, y en noventa píxeles el pulgar elegía entre cuatro grosores
 * de un empujón. Alargarlos no cuesta sitio a los demás —las bolitas miden
 * treinta— y es la diferencia entre afinar y acertar de casualidad.
 */
private val ALTO_DEL_DESLIZADOR: Dp = 150.dp

/**
 * El blanco del mango, el mismo de día y de noche.
 *
 * Es la pieza que se agarra, así que tiene que verse contra **el dibujo** —que
 * puede ser cualquier cosa— y no contra el tema de la aplicación.
 */
private val BLANCO_DEL_MANGO = Color.White

/** Y con el panel lleno, lo que quede: sigue siendo más que los noventa de antes. */
private val ALTO_APRETADO: Dp = 112.dp

/**
 * La cápsula que agrupa los mandos: cuánto se transparenta y cuánto se redondea.
 *
 * Translúcida y no opaca porque está **encima del dibujo**: lo que hay debajo se
 * tiene que seguir intuyendo, o el panel deja de ser un mando al borde y pasa a
 * ser una franja que tapa. Con este valor se ve el trazo de detrás sin que las
 * bolitas pierdan su contorno.
 */
private const val FONDO_DEL_PANEL = 0.72f
private val CANTO_DEL_PANEL: Dp = 26.dp
private val BOLA: Dp = 30.dp
private val SEPARACION: Dp = 5.dp

/**
 * Cuánto se separa el panel del canto de la pantalla.
 *
 * No es estética: los primeros milímetros del borde son de Android, que los usa
 * para el gesto de «atrás». Ver la nota de [PanelLateralDeEstilo].
 */
private val SEPARACION_DEL_BORDE: Dp = 14.dp

/** Cuánto se separa de su control la muestra que sale al arrastrar. */
private val SEPARACION_DE_LA_MUESTRA: Dp = 92.dp

/**
 * El hueco del punto de muestra y el ancho mínimo de su cifra.
 *
 * Los dos existen para lo mismo: que la pastilla mida **siempre igual**. El
 * punto crece dentro de su hueco en vez de empujarlo, y la cifra tiene sitio
 * reservado para cuatro caracteres. Ver [MuestraAlArrastrar].
 */
private val PUNTO_DE_LA_MUESTRA: Dp = 44.dp
private val CIFRA_DE_LA_MUESTRA: Dp = 44.dp

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
            // El de rayas rectas va a tiralíneas; el otro, con el pulso puesto.
            //
            // **Y la diferencia tiene que verse en una muestra de veinte
            // píxeles.** Estaba hecha torciendo el final de la raya un píxel y
            // medio, que a ese tamaño no se aprecia: salían dos dibujos
            // idénticos, y dos muestras iguales se leen como una opción
            // repetida. Ahora el rayado a mano se quiebra por el medio, que es
            // lo que se nota.
            val tiemblo = if (fs == FillStyle.HACHURE) borde * 2.5f else 0f
            var x = -size.height
            while (x < size.width) {
                val desde = Offset(x, size.height)
                val hasta = Offset(x + size.height, 0f)
                if (tiemblo == 0f) {
                    drawLine(tinta, desde, hasta, strokeWidth = borde)
                } else {
                    // El quiebro va perpendicular a la raya, que en una diagonal
                    // a 45° es sumar lo mismo en las dos coordenadas.
                    val medio = Offset(
                        (desde.x + hasta.x) / 2 + tiemblo,
                        (desde.y + hasta.y) / 2 + tiemblo
                    )
                    drawLine(tinta, desde, medio, strokeWidth = borde)
                    drawLine(tinta, medio, hasta, strokeWidth = borde)
                }
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

/**
 * Un interruptor de estilo de texto: la propia letra, con su estilo puesto.
 *
 * La muestra **es** lo que hace: la B sale en negrita, la I inclinada y la S
 * tachada. Un icono dibujado diría lo mismo con un dibujo que hay que aprender.
 */
@Composable
private fun BotonDeTexto(
    letra: String,
    puesto: Boolean,
    bola: Dp,
    peso: FontWeight = FontWeight.Normal,
    cursiva: Boolean = false,
    tachado: Boolean = false,
    onTocar: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (puesto) 6.dp else 2.dp,
        border = if (puesto) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.size(bola)
    ) {
        Box(Modifier.clickable { onTocar() }, contentAlignment = Alignment.Center) {
            Text(
                letra,
                fontSize = 14.sp,
                fontWeight = peso,
                fontStyle = if (cursiva) androidx.compose.ui.text.font.FontStyle.Italic
                else androidx.compose.ui.text.font.FontStyle.Normal,
                textDecoration = if (tachado) {
                    androidx.compose.ui.text.style.TextDecoration.LineThrough
                } else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/** Lo cerca del mango que hay que tocar para que cuente como tocarlo a él. */
private const val EL_MANGO = 0.07f

/** Lo lejos del mango que sale el botón de poner o quitar marca. */
private val BOTON_DE_MARCA = 40.dp
