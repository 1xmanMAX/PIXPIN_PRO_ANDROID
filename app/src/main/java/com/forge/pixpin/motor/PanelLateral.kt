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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.roundToInt
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
    zoom: () -> Float = { 1f },
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
    /**
     * Un toque en el mando del color abre el taller: la misma rueda del gesto y, al lado,
     * los colores guardados.
     *
     * Lo abre quien tiene sitio para ponerlo —el editor— y no el panel. Aquí dentro se
     * podría **pintar** al lado del mando, pero no se podría **tocar**: lo que se sale de
     * un contenedor se ve y no recibe el dedo, y un taller que no se puede tocar no es un
     * taller. Ver [ElTallerDelColor].
     */
    onAbrirColor: (() -> Unit)? = null,
    /** Los colores guardados, que salen como puntos dentro de la rueda. Ver [LaRuedaDelColor]. */
    marcasDeColor: List<String> = emptyList(),
    /** Si el papel es oscuro: decide con qué color se enseña cada tinta. Ver [colorDeEstilo]. */
    noche: Boolean = false,
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
    // **Los mandos no se tiñen con la tinta.**
    //
    // Estuvieron teñidos a propósito —cambiar el color cambiaba el panel entero y eso lo
    // hacía parecer una sola cosa— y era peor de usar: con una tinta clara los mandos se
    // borraban sobre el panel, y con una oscura de noche pasaba lo mismo. Un mando tiene que
    // leerse **siempre igual**, porque lo que dice no es de qué color se pinta sino cuánto
    // hay de algo. El color se enseña donde toca: en el botón del color y en la muestra de lo
    // que va a salir. Lo pidió el usuario (3-sep-2026) y tiene razón.
    val neutro = MaterialTheme.colorScheme.onSurface
    val tintaDelTrazo = colorDeEstilo(estilo.strokeColor, neutro, noche)
    val tintaDelFondo = colorDeEstilo(estilo.backgroundColor, tintaDelTrazo, noche)

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
        (if (Propiedad.RUGOSIDAD in aplican) 1 else 0)
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
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = FONDO_DEL_PANEL),
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
        // **El color vuelve al lateral, y esta vez no miente.**
        //
        // Estuvo aquí y se fue por una razón buena: la bolita solo conocía **sus** cinco
        // colores, así que cogiendo uno de la paleta de arriba se quedaba señalando el
        // primero de su lista, y tocarla deshacía lo que acabas de elegir. Un mando que
        // miente sobre lo que hay puesto es peor que no tenerlo.
        //
        // Eso se arregla en una línea: **el color que hay puesto entra en la lista**. Si es
        // uno de los de siempre, es el que está marcado; si viene de la paleta grande, se
        // añade el primero. Así el mando enseña siempre lo que hay y elegir desde él nunca
        // deshace nada.
        //
        // Y vuelve porque es lo que más se cambia mientras se dibuja, y ahora se cambia con
        // el mismo gesto que todo lo demás del panel: se posa el dedo y se baja. Abrir una
        // ventana flotante para pasar de negro a rojo es abrir una ventana de más — la
        // paleta grande sigue ahí para elegir despacio y para cambiar de combinación.
        if (Propiedad.TRAZO in aplican) {
            ElColorQueHay(
                estilo = estilo,
                onEstilo = onEstilo,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                onAbrirTaller = onAbrirColor,
                marcas = marcasDeColor,
                noche = noche
            )
        }

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
            LaLineaQueHay(estilo, onEstilo, neutro, haciaLaIzquierda, bola)
        }

        // **De qué está hecha la tinta**: lisa, encendida o con grano. En un plano el color
        // no basta para decir qué es una cosa —una sección se raya, una sombra se cruza, un
        // terreno se puntea, un tubo encendido resplandece— y hoy todo eso se hacía con el
        // mismo trazo liso de distinto color. Ver [MaterialDeTinta].
        if (Propiedad.MATERIAL in aplican) {
            LaTintaQueHay(estilo, onEstilo, tintaDelTrazo, haciaLaIzquierda, bola)
            // **Y el brillo no está aquí, y es a propósito.**
            //
            // Estuvo, un mando por trazo al lado de la tinta. Lo que uno hace con las luces de
            // un plano es subirlas todas o apagarlas todas —se enseña el dibujo, se apagan; se
            // mira de noche, se suben— y con un mando por trazo eso son veinte gestos para una
            // decisión. La llave es una y vive aparte, como la del croquis en el espacio. Ver
            // [LucesDelDibujo].
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
                tinta = neutro,
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
                tinta = neutro,
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
                tinta = neutro,
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

        // **Macizo o de alambre**, en el lateral y no escondido en un panel: es la
        // decisión que más se toca de un volumen. Y ya no hay lista de piezas que elegir:
        // la forma de un volumen es la de la figura que se levantó. Ver [extruido].
        if (Propiedad.VOLUMEN in aplican) {
            val modos = listOf(false, true)
            SelectorArrastrable(
                opciones = modos,
                actual = if (estilo.esqueleto) 1 else 0,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                descripcion = "Volumen",
                tinta = neutro,
                onElegir = { i -> onEstilo(estilo.copy(esqueleto = modos[i])) }
            ) { alambre, tinta ->
                Canvas(Modifier.size(20.dp)) {
                    val w = size.width
                    val h = size.height
                    val cara = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.15f, h * 0.3f)
                        lineTo(w * 0.6f, h * 0.15f)
                        lineTo(w * 0.85f, h * 0.4f)
                        lineTo(w * 0.4f, h * 0.55f)
                        close()
                    }
                    if (alambre) {
                        drawPath(cara, tinta, style = Stroke(width = 1.2f))
                        // Las aristas de detrás, que es lo que distingue al alambre.
                        drawLine(
                            tinta.copy(alpha = 0.5f),
                            Offset(w * 0.15f, h * 0.3f), Offset(w * 0.15f, h * 0.7f),
                            strokeWidth = 1.2f
                        )
                        drawLine(
                            tinta.copy(alpha = 0.5f),
                            Offset(w * 0.4f, h * 0.55f), Offset(w * 0.4f, h * 0.95f),
                            strokeWidth = 1.2f
                        )
                    } else {
                        drawPath(cara, tinta)
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
                tinta = neutro,
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
                tinta = neutro,
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
            ElGrosorQueHay(
                estilo = estilo,
                onEstilo = onEstilo,
                zoom = zoom,
                anchoPintado = anchoPintado,
                tinta = neutro,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola,
                marcas = marcas[Deslizador.GROSOR].orEmpty()
            )
        }

        if (Propiedad.FUENTE in aplican) {
            ElTamanoDeLaLetra(
                estilo = estilo,
                onEstilo = onEstilo,
                tinta = neutro,
                zoom = zoom,
                haciaLaIzquierda = haciaLaIzquierda,
                bola = bola
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
                tinta = neutro,
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
                tinta = neutro,
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
                tinta = neutro,
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
            LoQueTapa(
                estilo, onEstilo, neutro, haciaLaIzquierda, bola,
                marcas = marcas[Deslizador.OPACIDAD].orEmpty()
            )
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

        // **Y cuánto le hace caso al pulso de la mano**, que es de lo que más se cambia
        // dibujando: una guía va recta y lo que se enseña va a mano. Estuvieron los tres
        // desplegados —tres bolitas en fila— y eso son tres sitios que mirar para una sola
        // decisión, en un panel donde todo lo demás es un botón que enseña lo que hay.
        if (Propiedad.RUGOSIDAD in aplican) {
            ElPulsoQueHay(estilo, onEstilo, neutro, haciaLaIzquierda, bola)
        }
    }
}

// ---- Los cinco mandos que se posan y se arrastran ----
//
// Son los que se tocan **mientras se dibuja**: de qué color, de qué grueso, cuánto tapa, de
// qué línea y con cuánto pulso. Todos se manejan igual —se posa el dedo encima y se sube o
// se baja, sin levantarlo— y todos enseñan al lado, flotando hacia el lienzo, lo que va a
// salir a su tamaño de verdad. Es el mando del croquis en el espacio, y es el mismo a
// propósito: quien usa las dos pantallas no tiene que aprender dos formas de subir un
// grosor. Ver [MandoDelPanel].

/**
 * **El color que hay puesto**, y la rueda que sale al arrastrarlo.
 *
 * Dos gestos y dos usos, que es lo que pedía un botón que se toca treinta veces mientras se
 * dibuja:
 *
 * - **Se arrastra y sale una rueda de color ahí mismo.** El dedo no la toca: la rueda sale
 *   al lado y lo que manda es hacia dónde y cuánto se ha ido el dedo desde donde se posó —el
 *   ángulo es el tono y lo lejos, la viveza—. Se suelta y se queda el color que hubiera. Un
 *   solo gesto, sin abrir nada y sin apuntar a nada.
 * - **Y de un toque se abre el taller**, con la misma rueda, la tira de luz y los colores
 *   guardados. Ahí se elige despacio, que también hace falta.
 *
 * Sustituye a la bolita de cinco colores, y no por gusto: cinco colores son cinco, y el que
 * uno busca casi nunca es uno de esos cinco. La lista tenía además un fallo que no se
 * arregla alargándola —cogido un color de la paleta grande, la bolita se quedaba señalando
 * el primero de los suyos y tocarla deshacía lo que acababas de elegir—. Un mando que puede
 * dar **cualquier** color no puede mentir sobre cuál hay puesto: enseña el que hay.
 *
 * La claridad no la toca el arrastre: es la que hace que una tinta sea *esa* tinta, y
 * cambiarla de refilón dejaría el dibujo descolorido sin que nadie lo pidiera. Para eso está
 * la tira del taller.
 */
@Composable
private fun ElColorQueHay(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    haciaLaIzquierda: Boolean,
    bola: Dp,
    onAbrirTaller: (() -> Unit)?,
    marcas: List<String>,
    noche: Boolean = false
) {
    val puesto by rememberUpdatedState(estilo)
    // **Adónde apunta la marca de la rueda.** En dos números sueltos y no en una pareja
    // dentro de un estado: los lee el pintado del disco, así que moverlos repinta la rueda y
    // no recompone el panel. Ver [LaRuedaDelColor].
    var haceFalta by remember { mutableStateOf(false) }
    var elTono by remember { mutableFloatStateOf(0f) }
    var laViveza by remember { mutableFloatStateOf(0f) }
    var claridad by remember { mutableFloatStateOf(1f) }
    // **En `dp`, que es como llega el recorrido.** Estaba en píxeles —`toPx()`— y el
    // arrastre viene en `dp`: en una pantalla de hoy eso pone el centro de la rueda a casi
    // el triple de distancia de donde se pinta, o sea fuera de la pantalla. El dedo no
    // entraba nunca en ella y el color no cambiaba nunca. Ver [MandoDelPanel].
    val densidad = LocalDensity.current
    // **Dónde están de verdad el botón y la rueda**, no dónde deberían estar.
    //
    // El apartado dice a cuánto se pinta la rueda del botón, y con eso se sabía dónde caía su
    // centro... si la colocación hace exactamente lo que uno cree. Preguntándoselo a la
    // colocación no hay nada que creer: se resta un centro del otro y esa es la distancia
    // real, con su parte de arriba abajo incluida — que es la que se colaba y dejaba la marca
    // un poco desplazada de la punta del lápiz.
    var centroDelBoton by remember { mutableStateOf(Offset.Zero) }
    var centroDeLaRueda by remember { mutableStateOf(Offset.Zero) }
    MandoDelPanel(
        descripcion = "Color del trazo",
        bola = bola,
        haciaLaIzquierda = haciaLaIzquierda,
        apartado = APARTADO_DE_LA_RUEDA,
        desdeElCentro = true,
        alAgarrar = {
            val hsv = enHsv(parseColor(puesto.strokeColor, 255))
            // **La claridad de la tinta va tal cual; el mínimo es solo para ver el disco.**
            //
            // Era una sola variable, y esa subida se colaba en el color que se emite: con una
            // tinta oscura, agarrar el mando para mover **el tono** la aclaraba sin que nadie
            // lo hubiera pedido. El mínimo existe para que la rueda no salga negra, que es
            // cosa de cómo se ve y no de qué tinta es. Ver [CLARIDAD_MINIMA].
            claridad = hsv[2].coerceAtLeast(0.06f)
            // La rueda sale **antes** de tocarla, con la marca en el color que hay puesto:
            // es lo que dice hacia dónde hay que llevar el dedo. Ver abajo.
            elTono = hsv[0]
            laViveza = hsv[1]
            haceFalta = true
        },
        alArrastrar = { lado, subida ->
            // **El dedo tiene que llegar a la rueda, y ahí manda dónde está.**
            //
            // Se medía el ángulo y la distancia **desde donde se posó el dedo**, y la rueda
            // se pinta al lado del botón: dos círculos distintos, uno debajo de la mano y
            // otro a un palmo. Lo que se veía no era donde estaba el dedo sino una
            // traducción, y con la traducción había tonos a los que no se llegaba —el medio
            // giro que cae hacia el borde de la pantalla se quedaba fuera del alcance del
            // pulgar—.
            //
            // Ahora es la rueda que se ve: el dedo se lleva hasta ella y **desde que entra**
            // la marca va donde va el dedo, punto por punto. Fuera de la rueda no se cambia
            // nada, que es lo que permite ir a buscarla sin dejar el color por el camino.
            val desvio = centroDeLaRueda - centroDelBoton
            val haciaElLado: Float
            val haciaArriba: Float
            if (desvio == Offset.Zero) {
                // Todavía sin medir —el primer fotograma— así que vale lo calculado.
                haciaElLado = lado - if (haciaLaIzquierda) -APARTADO_DE_LA_RUEDA.value
                else APARTADO_DE_LA_RUEDA.value
                haciaArriba = subida
            } else {
                haciaElLado = lado - with(densidad) { desvio.x.toDp().value }
                haciaArriba = subida + with(densidad) { desvio.y.toDp().value }
            }
            val enLaRueda = haciaElLado
            if (kotlin.math.hypot(enLaRueda, haciaArriba) > RADIO_DE_LA_RUEDA) {
                return@MandoDelPanel
            }
            // **Y si el dedo pasa por encima de una marca, cae en ella.** Es lo que
            // convierte un punto pintado en un sitio al que se puede volver: a pulso, sobre
            // una rueda de dos dedos, nadie acierta dos veces el mismo tono.
            val encima =
                marcaImantada(enLaRueda, haciaArriba, RADIO_DE_LA_RUEDA, puntosDe(marcas))
            val cual: String
            if (encima >= 0) {
                cual = marcas[encima]
                val hsv = enHsv(parseColor(cual, 255))
                elTono = hsv[0]
                laViveza = hsv[1]
            } else {
                val tono = tonoDelArrastre(enLaRueda, haciaArriba)
                val viveza = vivezaDelArrastre(enLaRueda, haciaArriba, RADIO_DE_LA_RUEDA)
                elTono = tono
                laViveza = viveza
                cual = enTexto(deHsv(floatArrayOf(tono, viveza, claridad)))
            }
            if (!cual.equals(puesto.strokeColor, true)) {
                onEstilo(puesto.copy(strokeColor = cual))
            }
        },
        alSoltar = { haceFalta = false },
        alTocar = { onAbrirTaller?.invoke() },
        alMedirElCentro = { centroDelBoton = it },
        alLado = {
            if (haceFalta) {
                LaRuedaDelColor(
                    { elTono }, { laViveza }, marcas,
                    // **Con la claridad de la tinta que hay puesta.**
                    //
                    // Faltaba, y por eso el color que salía no era el de debajo del dedo: la
                    // rueda se pintaba a brillo de tope —el valor de fábrica— mientras la
                    // tinta se montaba con `claridad`, la de la tinta puesta. Con un color
                    // apagado, lo que se veía y lo que salía eran dos colores distintos. El
                    // mismo mando del croquis en el espacio sí la pasaba, que es la razón de
                    // que allí funcionara y aquí no.
                    claridad = claridad.coerceAtLeast(CLARIDAD_MINIMA),
                    alMedirElCentro = { centroDeLaRueda = it }
                )
            }
        }
    ) {
        Box(
            Modifier
                .size(bola * 0.62f)
                .clip(CircleShape)
                .background(
                    colorDeEstilo(estilo.strokeColor, MaterialTheme.colorScheme.onSurface, noche)
                )
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Flechitas()
    }
}

/**
 * **Un mando de los que recorren una escala: se posa el dedo y se sube o se baja.**
 *
 * El otro de los dos tipos que hay en este panel, junto a [ElMandoDeOpciones]. Uno señala
 * dentro de una lista corta que se ve; este recorre una escala que no se puede enseñar
 * entera —hay infinitos grosores— así que lo que enseña es **lo que va a salir**: el número
 * dentro del botón mientras se arrastra, y al lado la muestra a su tamaño de verdad.
 *
 * Está escrito una sola vez a propósito. Eran cuatro mandos copiados —el grosor, lo que tapa,
 * la letra y el brillo—, cada uno con su chivato, su pastilla y su reloj, y cuatro copias de
 * un mismo mando se separan solas: una enseñaba el número dentro y otra no, una se quedaba en
 * la pantalla más tiempo que la de al lado. Con uno, tocarlos todos es tocar este.
 *
 * Arriba es más, que es hacia donde va todo lo que sube en la vida — y al revés que la lista
 * de opciones, donde el dedo señala lo que ve debajo. Ver [MandoDelPanel].
 */
@Composable
private fun ElMandoContinuo(
    descripcion: String,
    nombre: String,
    haciaLaIzquierda: Boolean,
    bola: Dp,
    /** El valor de ahora, escrito corto: es lo que se lee mientras se arrastra. */
    enNumero: () -> String,
    /** El dedo se posa: se apunta de dónde parte la escala. */
    alAgarrar: () -> Unit,
    /** Y se mueve: el recorrido en `dp`, positivo hacia arriba. */
    alArrastrar: (recorrido: Float) -> Unit,
    /** Lo que va a salir, a tamaño de verdad, para la pastilla de al lado. */
    muestra: @Composable RowScope.() -> Unit,
    dentro: @Composable BoxScope.() -> Unit
) {
    val chivato = rememberChivato()
    MandoDelPanel(
        descripcion = descripcion,
        bola = bola,
        haciaLaIzquierda = haciaLaIzquierda,
        alAgarrar = {
            alAgarrar()
            chivato.agarrar()
        },
        alArrastrar = { _, subida -> alArrastrar(subida) },
        alSoltar = { chivato.soltar() },
        alTocar = { chivato.deReojo() },
        alLado = {
            MuestraDelMando(chivato.visible) {
                muestra()
                DiceLaMuestra(nombre, enNumero())
            }
        }
    ) {
        dentro()
        Flechitas()
        // **El número va solo en la pastilla de al lado, no también dentro del botón.**
        //
        // Estaba en los dos sitios a la vez: el mismo «40 %» sobre la bolita y otra vez en la
        // pastilla, a dos dedos de distancia. Dos copias del mismo dato compiten entre sí —se
        // lee dos veces para comprobar que dicen lo mismo— y encima el de dentro tapaba
        // justo la muestra, que es lo que el botón tiene que enseñar. Ver [MuestraDelMando].
    }
}

/**
 * **Lo gordo que sale, con la muestra al tamaño de verdad.**
 *
 * El punto que flota al lado **es** lo que va a salir: lo que mide el trazo en el dibujo, por
 * el aumento de este momento. No se puede adivinar del número, porque el mismo cuatro se ve
 * gordo de cerca y fino de lejos, y el lápiz pinta una mancha de alrededor del triple de lo
 * que dice su número. Ver [anchoPintadoDelLapiz].
 *
 * Dentro del botón va la bolita también a escala, pero acotada: en dos centímetros no entra un
 * trazo de veinte a mucho zoom, y ahí es justo donde más falta hace saber cuánto es.
 *
 * Y el arrastre va **a saltos iguales de tamaño y no de número** — ver [grosorArrastrado].
 */
@Composable
private fun ElGrosorQueHay(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    /**
     * El aumento de **ahora mismo**, invocado al pintar.
     *
     * Como número llegaba congelado: el editor solo se rehace al levantar el dedo, así que
     * durante el pellizco la muestra se quedaba en el aumento de antes y pegaba el salto al
     * final. Y rehacer el panel entero sesenta veces por segundo es el tirón que este
     * proyecto lleva dos arreglos evitando. Como lambda, los dos sitios que la usan son
     * bloques de dibujo, así que Compose repinta dos círculos y **no recompone ni vuelve a
     * medir nada**. Ver [zoomVivo] y [LaRuedaDelColor], que es el mismo acuerdo.
     */
    zoom: () -> Float,
    anchoPintado: Double?,
    tinta: Color,
    haciaLaIzquierda: Boolean,
    bola: Dp,
    /**
     * Los grosores guardados, de 0 a 1 del recorrido. Ver [MarcasDelDeslizador].
     *
     * **El mando las respeta pero no las pone**: se ponen en los ajustes. Un mando no tiene
     * mango que tocar —se posa el dedo y se sube—, así que el gesto de guardar del
     * deslizador viejo no tiene aquí traducción que no sea inventar un gesto nuevo.
     */
    marcas: List<Float> = emptyList()
) {
    val puesto by rememberUpdatedState(estilo)
    var partida by remember { mutableDoubleStateOf(estilo.strokeWidth) }
    val lasMarcas by rememberUpdatedState(marcas)
    // **Lo que va a salir, en píxeles de pantalla y a este aumento.** Ya viene en píxeles: el
    // lienzo pinta en píxeles crudos, no en `dp`. Y con el lápiz lo que sale es la mancha de
    // su cuerpo y no el número elegido, que es lo que dice [anchoPintado].
    val comoSaldra = { ((anchoPintado ?: puesto.strokeWidth) * zoom()).toFloat() }
    ElMandoContinuo(
        descripcion = "Grosor",
        nombre = "Grosor",
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola,
        enNumero = { grosorEscrito(estilo.strokeWidth) },
        alAgarrar = { partida = puesto.strokeWidth },
        alArrastrar = { recorrido ->
            // **Y al pasar por un grosor guardado, se cae en él.** Es lo que se perdió al
            // cambiar el deslizador por el mando: sin imán, volver al grosor con el que uno
            // escribe es cuestión de puntería, y a pulso no sale el mismo dos veces. El
            // tirón se hace sobre la fracción del recorrido y no sobre el número, que es
            // donde viven las marcas y donde la escala es pareja. Ver [conIman].
            val crudo = grosorArrastrado(partida, recorrido)
            val gordo =
                if (lasMarcas.isEmpty()) crudo
                else grosorDeLaFraccion(conIman(fraccionDelGrosor(crudo), lasMarcas))
            if (gordo != puesto.strokeWidth) onEstilo(puesto.copy(strokeWidth = gordo))
        },
        muestra = { ElPuntoDeVerdad(tinta, comoSaldra) }
    ) {
        Canvas(Modifier.size(bola * 0.66f)) {
            val radio = (comoSaldra() / 2)
                .coerceIn(size.minDimension * 0.07f, size.minDimension * 0.5f)
            drawCircle(tinta, radius = radio, center = center)
        }
    }
}

/**
 * El punto del trazo que va a salir, dentro de un hueco que mide siempre lo mismo.
 *
 * El hueco fijo no es un detalle: la pastilla se centra sobre el mando, así que cada píxel que
 * creciera el punto la estiraría medio píxel hacia cada lado y la pastilla se iría corriendo
 * sobre el panel según se sube el grosor. El sitio que ocupa una cosa no puede depender de lo
 * que dice.
 */
@Composable
private fun ElPuntoDeVerdad(tinta: Color, cuanto: () -> Float) {
    Canvas(Modifier.padding(end = 10.dp).size(PUNTO_DE_LA_MUESTRA)) {
        // En píxeles, que es como viene. Con tope, que a mucho aumento un trazo de veinte
        // ocupa media pantalla — y que se salga del hueco también es una respuesta.
        val radio = (cuanto() / 2).coerceIn(1.5f, size.minDimension / 2)
        drawCircle(tinta, radius = radio, center = center)
    }
}

/**
 * **Lo que tapa la tinta**: la pastilla del color sobre el damero.
 *
 * El damero de debajo es lo que hace que se vea que algo es transparente — sin él, una tinta
 * al veinte por ciento sobre el fondo del panel es un color más claro y no un color que deja
 * ver lo de detrás.
 *
 * Esta sí es una recta: de nada a del todo hay cien, y cien es cien en cualquier punto de la
 * escala. Ver [opacidadArrastrada].
 */
@Composable
private fun LoQueTapa(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    tinta: Color,
    haciaLaIzquierda: Boolean,
    bola: Dp,
    /** Las opacidades guardadas, de 0 a 1. Como en [ElGrosorQueHay]: se respetan, no se ponen. */
    marcas: List<Float> = emptyList()
) {
    val puesto by rememberUpdatedState(estilo)
    var partida by remember { mutableIntStateOf(estilo.opacity) }
    val lasMarcas by rememberUpdatedState(marcas)
    ElMandoContinuo(
        descripcion = "Opacidad",
        nombre = "Opacidad",
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola,
        enNumero = { "${estilo.opacity} %" },
        alAgarrar = { partida = puesto.opacity },
        alArrastrar = { recorrido ->
            // El mismo imán que el del grosor, y aquí la fracción **es** el número partido
            // por cien: la escala de la opacidad sí es una recta.
            val cruda = opacidadArrastrada(partida, recorrido)
            val cuanta =
                if (lasMarcas.isEmpty()) cruda
                else opacidadDeLaFraccion(conIman(cruda / 100f, lasMarcas))
            if (cuanta != puesto.opacity) onEstilo(puesto.copy(opacity = cuanta))
        },
        muestra = {
            Canvas(Modifier.padding(end = 10.dp).size(34.dp)) {
                dibujarDamero()
                drawCircle(
                    tinta.copy(alpha = estilo.opacity / 100f),
                    radius = size.minDimension * 0.44f
                )
            }
        }
    ) {
        Canvas(Modifier.size(bola * 0.62f)) {
            dibujarDamero()
            drawCircle(
                tinta.copy(alpha = estilo.opacity / 100f),
                radius = size.minDimension * 0.42f
            )
        }
    }
}

/**
 * **Lo grande que sale la letra**, con la «A» al tamaño de verdad flotando al lado.
 *
 * Era el último deslizador que quedaba de los de tocar a diario, y desentonaba: se subía el
 * grosor posando el dedo y se subía la letra recorriendo una tira de ciento cincuenta píxeles.
 * Un panel donde casi todo se hace igual y una cosa no, se aprende dos veces.
 *
 * Y de paso deja de dar **cuatro tamaños**. Los cuatro son los del original y están bien para
 * elegir entre botones; arrastrando salen todos los de en medio sin costar nada, y el rótulo
 * que hace falta para una cota casi nunca es uno de los cuatro.
 *
 * La «A» va como texto y no como dibujo —una letra dibujada a mano no diría de qué tamaño sale
 * la de verdad— y la de al lado va a **su tamaño en la pantalla**, contando el aumento: es lo
 * único que dice si el rótulo va a caber donde uno lo quiere poner.
 */
@Composable
private fun ElTamanoDeLaLetra(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    tinta: Color,
    /**
     * El aumento de ahora. **Aquí no sale tan gratis como en el grosor, y conviene decir por
     * qué**: la muestra de este mando es una letra escrita, y una letra se decide componiendo,
     * no pintando. Leyéndolo dentro de sus lambdas, la invalidación se queda en ellas —una «A»
     * de un carácter— y no sube al panel.
     */
    zoom: () -> Float,
    haciaLaIzquierda: Boolean,
    bola: Dp
) {
    val puesto by rememberUpdatedState(estilo)
    var partida by remember { mutableDoubleStateOf(estilo.fontSize) }
    val densidad = LocalDensity.current
    val comoSaldra = { (estilo.fontSize * zoom()).toFloat() }
    ElMandoContinuo(
        descripcion = "Tamaño de la letra",
        nombre = "Letra",
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola,
        enNumero = { estilo.fontSize.toInt().toString() },
        alAgarrar = { partida = puesto.fontSize },
        alArrastrar = { recorrido ->
            val tam = tamanoDeLetraArrastrado(partida, recorrido)
            if (tam != puesto.fontSize) onEstilo(puesto.copy(fontSize = tam))
        },
        muestra = {
            Box(
                Modifier.padding(end = 10.dp).widthIn(min = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "A",
                    // En píxeles de pantalla, como el punto del grosor. Con tope, que a mucho
                    // aumento una «A» de noventa y seis no cabe en ninguna pastilla.
                    fontSize = with(densidad) {
                        comoSaldra().coerceIn(10f, LO_QUE_CABE_DE_LETRA).toSp()
                    },
                    color = tinta,
                    fontFamily = composeFontFamily(estilo.fontFamily)
                )
            }
        }
    ) {
        Text(
            "A",
            // Dentro del botón, acotada a lo que cabe: en dos centímetros no entra una letra
            // de noventa y seis, y a partir de ahí todas se verían iguales.
            fontSize = with(densidad) { comoSaldra().coerceIn(10f, (bola * 0.6f).toPx()).toSp() },
            color = tinta,
            fontFamily = composeFontFamily(estilo.fontFamily)
        )
    }
}

/**
 * **Continua, a trazos o de puntos**, con la raya de verdad flotando al lado.
 *
 * En el botón cabe una muestra de veintidós píxeles, y a ese tamaño una raya de puntos y una
 * a trazos se parecen demasiado. La de al lado va larga y del grueso y el color que hay
 * puestos, que es la única forma de decidir sin soltar y probar.
 */
@Composable
private fun LaLineaQueHay(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    tinta: Color,
    haciaLaIzquierda: Boolean,
    bola: Dp
) {
    val lineas = StrokeStyle.entries
    ElMandoDeOpciones(
        descripcion = "Tipo de línea",
        opciones = lineas,
        actual = lineas.indexOf(estilo.strokeStyle).coerceAtLeast(0),
        onElegir = { onEstilo(estilo.copy(strokeStyle = lineas[it])) },
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola
    ) { ss -> Canvas(Modifier.size(width = 22.dp, height = 20.dp)) { dibujarLinea(ss, tinta) } }
}

/**
 * **De qué está hecha la tinta**, con el trazo de verdad flotando al lado.
 *
 * La muestra **es** el material: no hay forma de saber qué es «cruzado» leyendo la palabra, y
 * se sabe en cuanto se ve el trazo al lado del rayado. Es la misma razón por la que la línea
 * se elige viendo su raya y no su nombre.
 */
@Composable
private fun LaTintaQueHay(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    tinta: Color,
    haciaLaIzquierda: Boolean,
    bola: Dp
) {
    val materiales = MaterialDeTinta.entries
    ElMandoDeOpciones(
        descripcion = "Tinta",
        opciones = materiales,
        actual = materiales.indexOf(estilo.material).coerceAtLeast(0),
        onElegir = { onEstilo(estilo.copy(material = materiales[it])) },
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola
    ) { cual ->
        Canvas(Modifier.size(width = 24.dp, height = 16.dp)) { dibujarMaterial(cual, tinta) }
    }
}

/**
 * Un trazo de muestra hecho de ese material.
 *
 * No pasa por el motor de pintado —una muestra de veinte píxeles no necesita el contorno
 * exacto de la figura— pero sí dice lo mismo que él: la luz se apaga hacia fuera y tira a
 * blanco por el medio, y el grano va de través a por donde va la raya y recortado a la banda.
 * Ver [Renderer.conMaterial].
 */
fun DrawScope.dibujarMaterial(cual: MaterialDeTinta, tinta: Color) {
    val medio = size.height / 2
    val gordo = size.height * 0.62f
    val izquierda = Offset(size.height * 0.32f, medio)
    val derecha = Offset(size.width - size.height * 0.32f, medio)
    if (cual.alumbra) {
        // De fuera adentro: el resplandor, el cuerpo y el filamento casi blanco. El HDR abre
        // mucho más el resplandor, que es lo que lo distingue: no brilla más de color, alumbra
        // más alrededor.
        val abre = if (cual == MaterialDeTinta.HDR) 2.2f else 1f
        drawLine(
            tinta.copy(alpha = 0.2f), izquierda, derecha,
            gordo * (1 + 1.4f * abre), StrokeCap.Round
        )
        drawLine(
            tinta.copy(alpha = 0.45f), izquierda, derecha,
            gordo * (1 + 0.5f * abre), StrokeCap.Round
        )
        drawLine(tinta, izquierda, derecha, gordo, StrokeCap.Round)
        drawLine(Color.White, izquierda, derecha, gordo * 0.32f, StrokeCap.Round)
        return
    }
    drawLine(tinta, izquierda, derecha, gordo, StrokeCap.Round)
    if (cual == MaterialDeTinta.LISA) return

    val grano = Color(
        red = tinta.red * 0.58f, green = tinta.green * 0.58f, blue = tinta.blue * 0.58f,
        alpha = tinta.alpha
    )
    val paso = gordo * 0.7f
    val pelo = (gordo * 0.14f).coerceAtLeast(1f)
    clipPath(
        Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = izquierda.x - gordo / 2,
                    top = medio - gordo / 2,
                    right = derecha.x + gordo / 2,
                    bottom = medio + gordo / 2,
                    radiusX = gordo / 2,
                    radiusY = gordo / 2
                )
            )
        }
    ) {
        if (cual == MaterialDeTinta.PUNTOS) {
            var x = izquierda.x
            var impar = false
            while (x <= derecha.x) {
                val y = if (impar) medio - gordo * 0.2f else medio + gordo * 0.2f
                drawCircle(grano, radius = pelo * 1.4f, center = Offset(x, y))
                x += paso
                impar = !impar
            }
        } else {
            var x = izquierda.x - gordo
            while (x <= derecha.x + gordo) {
                drawLine(
                    grano,
                    Offset(x, medio - gordo / 2), Offset(x + gordo, medio + gordo / 2),
                    strokeWidth = pelo
                )
                if (cual == MaterialDeTinta.CRUZADO) {
                    drawLine(
                        grano,
                        Offset(x, medio + gordo / 2), Offset(x + gordo, medio - gordo / 2),
                        strokeWidth = pelo
                    )
                }
                x += paso
            }
        }
    }
}

/**
 * **Cuánto manda el pulso de la mano**: recta, a mano o temblona.
 *
 * Es lo que hace la rugosidad, así que la muestra **es** la propia raya cada vez más
 * torcida: con nombres había que probar los tres para saber cuál era cuál.
 */
@Composable
private fun ElPulsoQueHay(
    estilo: ItemStyle,
    onEstilo: (ItemStyle) -> Unit,
    tinta: Color,
    haciaLaIzquierda: Boolean,
    bola: Dp
) {
    ElMandoDeOpciones(
        descripcion = "Pulso",
        opciones = PULSOS.indices.toList(),
        actual = PULSOS.indexOf(estilo.roughness).coerceAtLeast(0),
        onElegir = { onEstilo(estilo.copy(roughness = PULSOS[it])) },
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola
    ) { i -> Canvas(Modifier.size(width = 22.dp, height = 20.dp)) { dibujarPulso(i, tinta) } }
}

/**
 * **Un mando de los que eligen entre unas pocas opciones: se arrastra por ellas, viéndolas.**
 *
 * Es el mando de siempre —se posa el dedo y se sube o se baja— pero **enseñando la lista**:
 * en cuanto se agarra salen todas las opciones en columna, con la que hay puesta debajo del
 * dedo, y la que queda bajo el dedo se marca y se aplica. Se suelta y ahí se queda.
 *
 * ## Por qué la lista y no solo la muestra
 *
 * Sin ella el mando es adivinar: se ve lo que hay puesto y lo que sale al mover, pero no
 * **cuántas** hay, ni si la que se busca está arriba o abajo, ni cuánto falta para llegar. Con
 * cinco tintas eso es mover a ciegas hasta que aparece la buena. Con la lista delante, elegir
 * es señalar — que es lo que ya hacía el mando de la letra, el único que la enseñaba.
 *
 * ## El dedo señala, así que bajar es la de abajo
 *
 * La columna sale hacia abajo, así que mover el dedo hacia abajo marca la de abajo: el dedo
 * apunta a lo que se ve, sin regla que aprender. Es lo contrario de lo que hacen el grosor y
 * lo que tapa —ahí arriba es más— y no se pisan: en uno se recorre una escala a ciegas y en
 * el otro se señala una lista que está a la vista.
 *
 * Y el toque pasa a la siguiente, dando la vuelta: con tres opciones, tocar tres veces es el
 * camino más corto a cualquiera de ellas.
 */
@Composable
private fun <T> ElMandoDeOpciones(
    descripcion: String,
    opciones: List<T>,
    actual: Int,
    onElegir: (Int) -> Unit,
    haciaLaIzquierda: Boolean,
    bola: Dp,
    muestra: @Composable (T) -> Unit
) {
    val elegir by rememberUpdatedState(onElegir)
    val vibrar = LocalHapticFeedback.current
    var abierto by remember { mutableStateOf(false) }
    // **El toque también enseña la lista, un momento.** El toque rápido pasa a
    // la siguiente opción, y sin esto lo hacía a ciegas: el cambio se aplicaba
    // pero no aparecía nada — un mando que funciona y no se ve funcionar se
    // lee como roto. Es el mismo trato que el chivato de los mandos continuos
    // ([rememberChivato]): se asoma, se queda un suspiro y se va sola.
    var arrastrando by remember { mutableStateOf(false) }
    var toques by remember { mutableIntStateOf(0) }
    LaunchedEffect(toques) {
        if (toques > 0 && !arrastrando) {
            kotlinx.coroutines.delay(900L)
            if (!arrastrando) abierto = false
        }
    }
    var partida by remember { mutableIntStateOf(actual) }
    // **La última que se aplicó, apuntada aquí y no leída de fuera.**
    //
    // Se miraba el valor que llegaba de arriba, y ese solo se pone al día cuando la pantalla
    // se vuelve a componer: arrastrando deprisa llegan varias muestras del dedo antes de eso,
    // así que la misma opción se elegía cinco veces seguidas **y vibraba cinco veces**. Eso
    // era el zumbido: no era un cambio por vibración, eran cinco vibraciones por cambio.
    var ultima by remember { mutableIntStateOf(actual) }
    val ahora by rememberUpdatedState(actual)
    // **En `dp`, que es como llega el recorrido.** Estaba en píxeles —`toPx()`— y el arrastre
    // viene en `dp`: en una pantalla de hoy eso hace el paso casi el triple de largo, y había
    // que bajar media pantalla para pasar de la segunda opción a la tercera.
    val paso = PASO_ENTRE_OPCIONES.value
    MandoDelPanel(
        descripcion = descripcion,
        bola = bola,
        haciaLaIzquierda = haciaLaIzquierda,
        apartado = bola / 2 + PASO_ENTRE_OPCIONES,
        alAgarrar = {
            partida = ahora
            ultima = ahora
            arrastrando = true
            abierto = true
        },
        alArrastrar = { _, subida ->
            // Se le pasa la bajada, no la subida: el dedo señala la opción que ve debajo.
            val cual = opcionArrastradaAbajo(-subida, paso, opciones.size, partida)
            if (cual >= 0 && cual != ultima) {
                ultima = cual
                vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                elegir(cual)
            }
        },
        alSoltar = {
            arrastrando = false
            abierto = false
        },
        alTocar = {
            val siguiente = (ahora + 1) % opciones.size
            ultima = siguiente
            elegir(siguiente)
            // Que se vea a qué se ha pasado: la lista se asoma y se va sola.
            abierto = true
            toques++
        },
        alLado = {
            AnimatedVisibility(
                visible = abierto,
                enter = fadeIn(spring()) + scaleIn(spring(), initialScale = 0.7f),
                exit = fadeOut(spring())
            ) {
                // **La lista se coloca de modo que la puesta caiga a la altura del mando**:
                // arrastrar cero es quedarse en la que ya estaba. Cada opción ocupa
                // exactamente un paso, que es lo mismo que hay que arrastrar para llegar a
                // ella: así lo que se ve y lo que cuenta [opcionArrastradaAbajo] son la misma
                // cosa.
                Column(
                    Modifier
                        .wrapContentSize(unbounded = true)
                        .offset(
                            y = PASO_ENTRE_OPCIONES *
                                ((opciones.size - 1) / 2f - actual.coerceIn(0, opciones.size - 1))
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    opciones.forEachIndexed { i, opcion ->
                        val elegida = i == actual
                        // El crecer de la marcada, con muelle: saltando de tamaño se ve como
                        // un parpadeo y no como que la has cogido.
                        val tam by animateDpAsState(
                            targetValue = if (elegida) bola + 8.dp else bola,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "opcion"
                        )
                        Box(
                            Modifier.height(PASO_ENTRE_OPCIONES),
                            contentAlignment = Alignment.Center
                        ) {
                            // **La marcada se señala con un aro, no pintándola de otro
                            // color.** Si el fondo cambiara, la muestra dejaría de verse del
                            // color que va a salir — que es justo lo que se está mirando.
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = if (elegida) 8.dp else 3.dp,
                                border = if (elegida) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    null
                                },
                                modifier = Modifier.size(tam)
                            ) {
                                Box(contentAlignment = Alignment.Center) { muestra(opcion) }
                            }
                        }
                    }
                }
            }
        }
    ) {
        opciones.getOrNull(actual)?.let { muestra(it) }
        Flechitas()
    }
}

/**
 * Cuánto se aparta del mando el centro de la rueda del color.
 *
 * Su radio más el del propio mando: pegada, el dedo taparía el trozo de rueda que hay entre
 * los dos, que es justo por donde se sale al empezar el gesto.
 *
 * **Y es también parte del gesto**, no solo de la pintura: el dedo se lleva hasta aquí y
 * desde ahí manda dónde está dentro de la rueda. Ver [ElColorQueHay].
 */
private val APARTADO_DE_LA_RUEDA: Dp = (RADIO_DE_LA_RUEDA + 30).dp

/** Dónde cae cada marca en la rueda: su tono y lo viva que es. */
private fun puntosDe(marcas: List<String>): List<Pair<Float, Float>> =
    marcas.map { val hsv = enHsv(parseColor(it, 255)); hsv[0] to hsv[1] }

/** Lo apagado que va un mando que ahora mismo no manda nada. */
private const val ALFA_DE_LO_QUE_NO_APLICA = 0.4f

/** Lo más grande que se enseña la «A» de la muestra, en píxeles de pantalla. */
private const val LO_QUE_CABE_DE_LETRA = 96f



/**
 * El color de un estilo, listo para pintar con él.
 *
 * «transparent» no es un color con el que se pueda dibujar una muestra, así que
 * en ese caso manda [siNoHay]: enseñar la trama del relleno en transparente
 * sería enseñar un hueco, y lo que se quiere saber es qué trama es.
 */
/**
 * El color de una tinta **tal como va a salir**, no tal como está guardado.
 *
 * El lienzo pinta cada color por el filtro de noche —ver [DrawTheme.filtrar]—, que sobre
 * papel oscuro reescribe la claridad a un rango claro. Sin pasar por él, el panel enseñaba el
 * color del archivo y el trazo salía de otro: se elegía un azul marino y pintaba un azul
 * pastel. Es el mismo arreglo que ya llevaban [PaletaDeColores] y el cuadro de escribir; el
 * lateral se había quedado fuera.
 */
private fun colorDeEstilo(hex: String, siNoHay: Color, noche: Boolean = false): Color =
    if (isTransparent(hex)) siNoHay else Color(DrawTheme.filtrar(parseColor(hex), noche))

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
    // El trazo sí está: es lo que más se cambia dibujando, y desde que la lista incluye el
    // color que hay puesto ya no puede señalar uno que no es. El fondo no — ese se elige en
    // la paleta de arriba. Ver la nota de su control.
    Propiedad.TRAZO,
    Propiedad.RELLENO, Propiedad.LINEA,
    Propiedad.ESQUINAS, Propiedad.FORMA_FLECHA, Propiedad.MOSAICO, Propiedad.LUPA,
    Propiedad.VOLUMEN,
    Propiedad.GROSOR, Propiedad.MATERIAL, Propiedad.LUPA, Propiedad.ZONA, Propiedad.OSCURECER,
    Propiedad.OPACIDAD, Propiedad.FUENTE
)

/** De cinco en cinco, que es como se dice cuánto se apaga o cuánto tapa algo. */
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
                color = MaterialTheme.colorScheme.surfaceVariant,
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
                color = MaterialTheme.colorScheme.surfaceVariant,
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
 * Es [ElMandoDeOpciones] con el nombre y la forma de llamarlo de siempre: unos cuantos
 * mandos del panel le pasan además con qué color pintar sus muestras, y así no hay que
 * repetir esa línea en cada uno.
 *
 * Tuvo su propia implementación, y era la misma en pequeño: la lista saliendo de la bolita,
 * la marcada con su aro y el gesto midiendo el arrastre. Dos copias de un mismo mando acaban
 * separándose —una enseñaba la lista y la otra una muestra flotante, y el mismo panel pedía
 * dos gestos distintos según qué bolita se cogiera— así que ahora hay una sola.
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
    val tintaDeLaMuestra = tinta ?: MaterialTheme.colorScheme.onSurface
    ElMandoDeOpciones(
        descripcion = descripcion,
        opciones = opciones,
        actual = actual,
        onElegir = onElegir,
        haciaLaIzquierda = haciaLaIzquierda,
        bola = bola
    ) { contenido(it, tintaDeLaMuestra) }
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
 * Lo que tapa el fondo de una caja del lateral, y de qué color va.
 *
 * **Del `surfaceVariant` y no del `surface`, que es lo que fallaba de noche.** El `surface`
 * es el color del fondo de la aplicación, así que una caja pintada con él a tres cuartos
 * sobre un lienzo oscuro es un rectángulo del color del fondo encima del fondo: no se ve
 * dónde empieza ni dónde acaba, y los mandos de dentro quedan flotando sueltos sobre el
 * dibujo. El `surfaceVariant` está para justo esto —una superficie que tiene que despegarse
 * de la de debajo— y se separa en los cuatro casos: tema claro con lienzo claro u oscuro, y
 * tema oscuro con lienzo claro u oscuro. Es el mismo que usa la barra del croquis en el
 * espacio, que es la que siempre se ha visto bien.
 */
internal const val FONDO_DEL_PANEL = 0.92f
internal val CANTO_DEL_PANEL: Dp = 26.dp
internal val BOLA: Dp = 30.dp
internal val SEPARACION: Dp = 5.dp

/**
 * Cuánto se separa el panel del canto de la pantalla.
 *
 * No es estética: los primeros milímetros del borde son de Android, que los usa
 * para el gesto de «atrás». Ver la nota de [PanelLateralDeEstilo].
 */
internal val SEPARACION_DEL_BORDE: Dp = 14.dp

/** Cuánto se separa de su control la muestra que sale al arrastrar. */
internal val SEPARACION_DE_LA_MUESTRA: Dp = 92.dp

/**
 * El hueco del punto de muestra y el ancho mínimo de su cifra.
 *
 * Los dos existen para lo mismo: que la pastilla mida **siempre igual**. El
 * punto crece dentro de su hueco en vez de empujarlo, y la cifra tiene sitio
 * reservado para cuatro caracteres. Ver [MuestraAlArrastrar].
 */
private val PUNTO_DE_LA_MUESTRA: Dp = 44.dp
private val CIFRA_DE_LA_MUESTRA: Dp = 44.dp

/**
 * Cuánto hay que arrastrar para pasar de una opción a la siguiente, y lo alto que va cada
 * una en la lista: es el mismo número, porque lo que se ve y lo que cuenta el gesto tienen
 * que ser lo mismo.
 *
 * **Treinta y no cuarenta.** Con cuarenta, recorrer cinco tintas pedía un palmo de pantalla y
 * el dedo se salía por abajo antes de llegar a la última. Treinta sigue siendo más que el
 * temblor de una mano y deja las listas largas al alcance de un pulgar.
 */
private val PASO_ENTRE_OPCIONES: Dp = 30.dp

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
