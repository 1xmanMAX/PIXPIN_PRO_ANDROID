package com.forge.pixpin.motor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * **Un mando del lateral: se toca para lo evidente y se arrastra para graduarlo.**
 *
 * Es el mando del croquis en el espacio, traído tal cual al lienzo plano. Quien usa las dos
 * pantallas no tiene por qué aprender dos formas de subir un grosor.
 *
 * ## Por qué un botón y no una tira
 *
 * Una tira vertical de ciento cincuenta píxeles pegada al panel es medio dibujo tapado por
 * un mando que se usa tres segundos, y encima con la puntería al revés: la parte que más se
 * usa —lo fino, lo poco— es la que menos sitio tiene. Aquí el recorrido **lo pone la mano**:
 * el botón puede medir dos centímetros y el mando, la pantalla entera.
 *
 * El recorrido se mide **desde donde se posó el dedo** y en `dp`, no desde el borde del
 * botón. Hacia arriba es más y hacia abajo es menos, que es hacia donde va todo lo que sube
 * en la vida. Se dan las dos componentes porque no todos los mandos son una recta: el del
 * color es un círculo —el ángulo es el tono y lo lejos, la viveza— y los demás se quedan con
 * la subida y no miran la otra. Ver [tonoDelArrastre] y [grosorArrastrado].
 *
 * El toque se separa del arrastre por el mismo umbral que usa el sistema
 * (`viewConfiguration.touchSlop`): por debajo de él la mano quieta tiembla, y sin margen
 * cualquier toque cambiaría el grosor sin querer.
 *
 * Y se queda con el dedo aunque salga del botón —consume los eventos hasta que se levante—,
 * que es lo que permite que el recorrido sea largo sin tener que seguir apuntando.
 *
 * ## Lo que sale al lado
 *
 * [alLado] es lo que flota **hacia el lienzo** mientras se maneja: la rueda del color, el
 * punto del grosor de verdad, la raya con su estilo. Va fuera de la medida —`unbounded`— así
 * que aparecer no le quita sitio a nadie ni empuja a los mandos de al lado. Es lo que
 * resuelve el problema de fondo de un botón de dos centímetros: dentro no cabe un trazo
 * gordo, así que sin esto se ve un círculo tocando los bordes y de ahí para arriba todo
 * igual, justo cuando más falta hace saber cuánto es.
 */
@Composable
internal fun MandoDelPanel(
    descripcion: String,
    bola: Dp,
    alAgarrar: () -> Unit,
    alArrastrar: (lado: Float, subida: Float) -> Unit,
    alSoltar: () -> Unit,
    alTocar: () -> Unit = {},
    /** Hacia dónde sale lo que flota: al lado del lienzo, no al del borde. */
    haciaLaIzquierda: Boolean = false,
    /** Cuánto se aparta del mando lo que flota. La rueda pide más sitio que una pastilla. */
    apartado: Dp = SEPARACION_DE_LA_MUESTRA,
    /**
     * **Medir desde el centro del mando y no desde donde cayó el dedo.**
     *
     * Casi todos los mandos quieren el **recorrido**: cuánto se ha movido la mano desde que
     * se posó, que es lo que hace que subir un grosor sea igual se agarre el botón por donde
     * se agarre. El del color no: ahí lo que manda es **dónde está el dedo** dentro de una
     * rueda que se pinta en un sitio fijo, y esa rueda está a tanto del centro del botón, no
     * de la yema. Midiendo desde la yema, la marca salía corrida justo lo que se hubiera
     * desviado el dedo al posarlo —hasta medio botón— y nunca caía debajo del puntero.
     */
    desdeElCentro: Boolean = false,
    /**
     * Dónde ha quedado el centro del botón **en la ventana**, medido de verdad.
     *
     * Lo pide el mando del color: la rueda se pinta a tanto del botón, y para saber si el
     * dedo está dentro de ella hay que saber dónde está la rueda. Se puede calcular —es el
     * apartado— y calcularlo es fiarse de que la colocación haga exactamente lo que uno cree.
     * Preguntándoselo a la propia colocación no hay nada que creer: se resta un centro del
     * otro y esa es la distancia, salga de donde salga.
     */
    alMedirElCentro: ((Offset) -> Unit)? = null,
    alLado: (@Composable () -> Unit)? = null,
    /**
     * **Los tres interruptores del aspecto**, para que este mando le valga a los dos lienzos.
     *
     * El croquis en el espacio tenía su propia copia de este mando —el mismo gesto escrito
     * dos veces, con las dos tablas separándose solas— y la diferencia entre las dos era
     * solo de aspecto: allí los mandos no engordan al cogerlos, no vibran y no llevan
     * pastilla debajo, porque van sobre el dibujo y no dentro de un panel.
     *
     * Con esto hay **una sola implementación del gesto** y cada lienzo pide la pinta que ya
     * tenía: nada cambia de sitio ni de aspecto, y lo que se arregle aquí se arregla en los
     * dos a la vez — que era justo el problema.
     */
    engorda: Boolean = true,
    vibra: Boolean = true,
    conPastilla: Boolean = true,
    dentro: @Composable BoxScope.() -> Unit
) {
    // **Al día, y no como estaban al componer.**
    //
    // El detector se arma una sola vez —`pointerInput(Unit)`, que es lo que evita rearmarlo
    // en cada recomposición— y se queda con lo que hubiera entonces. Con las acciones
    // capturadas tal cual, el mando leería para siempre el estilo que tenía al aparecer.
    val agarrar by rememberUpdatedState(alAgarrar)
    val arrastrar by rememberUpdatedState(alArrastrar)
    val soltar by rememberUpdatedState(alSoltar)
    val tocar by rememberUpdatedState(alTocar)

    var agarrado by remember { mutableStateOf(false) }
    val vibrar = LocalHapticFeedback.current

    // Engorda un pelo al cogerlo, y en la capa de dibujo y no en el tamaño: cambiando el
    // tamaño movería a sus vecinos, que es justo el temblor que hay que evitar.
    val engorde by animateFloatAsState(
        targetValue = if (agarrado && engorda) 1.18f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "mando"
    )

    // **El dedo se mide aquí fuera, y no dentro del botón que engorda.**
    //
    // El engorde va en una capa de dibujo con escala, y una capa con escala **también
    // transforma el dedo**: dentro de ella Compose entrega la posición ya dividida por la
    // escala. Con el mando agarrado eso son quince centésimas de menos por cada `dp` que la
    // mano se aleja del centro del botón — y como es un **factor y no una resta**, el error
    // crece con la distancia: en la rueda del color el punto se quedaba corto, y más cuanto
    // más lejos, hasta casi tres centímetros en el borde de fuera.
    //
    // Y la medida del centro tenía lo mismo por otro lado: la escala es una propiedad de
    // colocación, así que al cambiar vuelve a llamar a la colocación y el centro salía medido
    // **con la capa puesta** — corrido, y temblando mientras el muelle rebota.
    //
    // El croquis en el espacio no engorda sus mandos, y por eso allí nunca pasó.
    // **Y este `Box` no se recorta.** Es el que lleva dentro lo que flota al lado —la rueda,
    // la pastilla de la muestra, el chivato— y todo eso se pinta **fuera** de los dos
    // centímetros del botón. Con un `clip` aquí se ve el mando funcionar y no se ve nada de lo
    // que dice: las funciones van y el indicador no aparece. El área de toque cuadrada que se
    // gana a cambio es de un pelo en las esquinas y no la nota nadie.
    Box(
        Modifier
            .size(bola)
            .semantics { contentDescription = descripcion }
            .then(
                if (alMedirElCentro == null) Modifier
                else Modifier.onGloballyPositioned {
                    alMedirElCentro(
                        it.positionInWindow() +
                            Offset(it.size.width / 2f, it.size.height / 2f)
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (alLado != null) {
            Box(
                Modifier
                    .wrapContentSize(unbounded = true)
                    .offset(x = if (haciaLaIzquierda) -apartado else apartado)
            ) { alLado() }
        }
        Surface(
            shape = CircleShape,
            color = if (conPastilla) MaterialTheme.colorScheme.surfaceVariant
            else Color.Transparent,
            shadowElevation = if (!conPastilla) 0.dp else if (agarrado) 10.dp else 3.dp,
            modifier = Modifier
                .graphicsLayer { scaleX = engorde; scaleY = engorde }
                .size(bola)
        ) {
            Box(
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val abajo = awaitFirstDown()
                        abajo.consume()
                        val desde = abajo.position
                        var arrastrando = false
                        while (true) {
                            val evento = awaitPointerEvent()
                            val dedo = evento.changes
                                .firstOrNull { it.id == abajo.id && it.pressed } ?: break
                            // **Lo que decide si esto es un toque o un arrastre es siempre
                            // lo que se ha movido la mano**, aunque después se mida desde
                            // otro sitio: con el umbral contra el centro, posar el dedo en
                            // el canto del botón ya contaría como arrastre y el toque no
                            // saldría nunca.
                            val recorrido = kotlin.math.hypot(
                                dedo.position.x - desde.x, desde.y - dedo.position.y
                            )
                            val origen =
                                if (desdeElCentro) Offset(size.width / 2f, size.height / 2f)
                                else desde
                            val subida = origen.y - dedo.position.y
                            val lado = dedo.position.x - origen.x
                            if (!arrastrando && recorrido > viewConfiguration.touchSlop) {
                                arrastrando = true
                                agarrado = true
                                if (vibra) {
                                    vibrar.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                agarrar()
                            }
                            if (arrastrando) arrastrar(lado.toDp().value, subida.toDp().value)
                            dedo.consume()
                        }
                        agarrado = false
                        if (arrastrando) soltar() else tocar()
                    }
                },
                contentAlignment = Alignment.Center,
                content = dentro
            )
        }
    }
}

/**
 * Las dos flechitas de un mando que se arrastra.
 *
 * Están porque **un gesto que no se ve no existe**: un botón redondo con un color dentro no
 * dice por ningún lado que se le pueda subir y bajar, y nadie lo descubre solo. Van muy
 * flojas y pegadas a los cantos: lo que tiene que leerse del mando es lo que hay puesto, no
 * el modo de empleo.
 */
@Composable
internal fun Flechitas() {
    val tinte = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ALFA_DE_LAS_FLECHITAS)
    Canvas(Modifier.fillMaxSize()) {
        val medio = size.width / 2
        val ala = size.width * 0.13f
        val hueco = size.height * HUECO_DE_LAS_FLECHITAS
        val pelo = GRUESO_DE_LAS_FLECHITAS.dp.toPx()
        fun flecha(punta: Float, haciaArriba: Boolean) {
            val base = if (haciaArriba) punta + ala else punta - ala
            drawLine(tinte, Offset(medio - ala, base), Offset(medio, punta), strokeWidth = pelo)
            drawLine(tinte, Offset(medio + ala, base), Offset(medio, punta), strokeWidth = pelo)
        }
        flecha(hueco, haciaArriba = true)
        flecha(size.height - hueco, haciaArriba = false)
    }
}

/**
 * **La pastilla que flota al lado del mando mientras se maneja.**
 *
 * Lleva la muestra **sin recortar** y el nombre de lo que se toca. La del propio botón está
 * metida en dos centímetros; esta crece con lo que se está eligiendo y no la limita ningún
 * contenedor.
 */
@Composable
internal fun MuestraDelMando(visible: Boolean, contenido: @Composable RowScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring()) + scaleIn(spring(), initialScale = 0.85f),
        exit = fadeOut(spring())
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 10.dp
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = contenido
            )
        }
    }
}

/** El renglón de una muestra: qué se está tocando y en cuánto va. */
@Composable
internal fun RowScope.DiceLaMuestra(nombre: String, cuanto: String? = null) {
    Text(
        nombre,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
    if (cuanto != null) {
        Text(
            cuanto,
            Modifier.padding(start = 8.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * **La rueda del color**: el disco entero, con la marca donde está el dedo.
 *
 * Es **una sola rueda para las dos cosas**: la que sale al arrastrar el mando —que no se
 * toca, el dedo sigue en el botón— y la del taller, que sí se toca. Antes el taller enseñaba
 * otra distinta, un aro de tonos con el color en el medio, y eso son dos ruedas que hay que
 * aprender por separado para la misma decisión: la que uno se aprende con el gesto rápido no
 * le servía al abrir el panel. Le pasa [alElegir] quien la deja tocar, y ya.
 *
 * El disco se pinta con dos brochas y no punto a punto: una que da la vuelta al círculo
 * repartiendo los tonos y otra que sale del centro con el gris, que es exactamente lo que es
 * una rueda de color —el tono alrededor y la viveza hacia fuera—. Y la marca cae **donde está
 * pintado ese tono**: el barrido de Compose empieza a las tres y va con las agujas del reloj,
 * que es el mismo sentido en el que [tonoDelArrastre] mide el gesto.
 *
 * ## Y se pinta con la claridad que hay puesta
 *
 * No a plena luz siempre. La rueda no da más que tonos y vivezas —lo claro que va lo pone la
 * tira de abajo— así que pintándola siempre encendida enseñaba unos colores y devolvía otros:
 * con una tinta apagada, el rojo del disco era un rojo vivo y lo que salía era granate. Con la
 * claridad puesta, **lo que se ve es lo que sale**, y una marca oscura cae sobre un disco
 * oscuro en vez de sobre uno que no se le parece.
 */
@Composable
internal fun LaRuedaDelColor(
    /**
     * Dónde va la marca: el tono y lo viva.
     *
     * **Se leen al pintar y no al componer**, que es lo que hace que la marca vaya debajo del
     * dedo y no un pelo por detrás. Leídas aquí arriba, cada muestra del dedo tiene que
     * esperar a que se vuelva a componer el panel entero —con sus diez mandos— antes de
     * mover el punto, y eso se ve como que la marca va arrastrándose detrás. Invocadas dentro
     * del `Canvas`, la lectura la registra el pintado: se repinta el disco y nada más.
     */
    tono: () -> Float,
    viveza: () -> Float,
    /**
     * **Las marcas: los colores guardados, como puntos dentro de la rueda.**
     *
     * Un tono que uno ha usado y quiere repetir no se encuentra a pulso: la rueda da todos
     * los colores y por eso ninguno en concreto. Con la marca puesta, el color de uno está
     * **donde está** —en su sitio de la rueda, no en una lista aparte— así que se ve de un
     * vistazo hacia dónde llevar el dedo, y al pasar cerca se cae dentro. Ver [marcaImantada].
     */
    marcas: List<String> = emptyList(),
    /** Lo clara que va la rueda: la de la tinta que hay puesta. */
    claridad: Float = 1f,
    /**
     * Lo que mide de lado a lado, **la sombra incluida**.
     *
     * Por defecto, el disco de [RADIO_DE_LA_RUEDA] más el filo de [SOMBRA_DE_LA_RUEDA] por
     * cada lado. Lo que tiene que medir [RADIO_DE_LA_RUEDA] es el disco, que es contra lo que
     * el arrastre mide la viveza.
     */
    lado: Dp = LADO_DE_LA_RUEDA,
    /** Qué hacer al tocarla. Sin esto, la rueda solo se mira. */
    alElegir: ((tono: Float, viveza: Float) -> Unit)? = null,
    /** Dónde ha quedado su centro en la ventana. Ver [MandoDelPanel.alMedirElCentro]. */
    alMedirElCentro: ((Offset) -> Unit)? = null
) {
    val tonos = remember(claridad) {
        List(TONOS_DE_LA_RUEDA + 1) {
            Color(deHsv(floatArrayOf(it * 360f / TONOS_DE_LA_RUEDA, 1f, claridad)))
        }
    }
    val centroDeLaRueda = remember(claridad) { Color(deHsv(floatArrayOf(0f, 0f, claridad))) }
    val elegir by rememberUpdatedState(alElegir)
    val tocable = if (alElegir == null) Modifier else Modifier.pointerInput(Unit) {
        fun senalar(donde: Offset) {
            val medio = Offset(size.width / 2f, size.height / 2f)
            val d = donde - medio
            // **Contra el disco, no contra el borde de fuera.** Es el mismo acuerdo que
            // hace falta al arrastrar: lo que se pinta llega hasta el disco, así que lo que
            // se pide tiene que medirse contra el disco. Ver [SOMBRA_DE_LA_RUEDA].
            val radio = kotlin.math.min(size.width, size.height) / 2f - SOMBRA_DE_LA_RUEDA.toPx()
            if (radio <= 0f) return
            val lejos = d.getDistance()
            // Fuera del disco no hay color que pedir, pero sí se sigue el dedo por el borde:
            // apurar el tono más vivo no puede pedir puntería de relojero.
            val tonoTocado = ((Math.toDegrees(
                kotlin.math.atan2(d.y.toDouble(), d.x.toDouble())
            ) + 360.0) % 360.0).toFloat()
            elegir?.invoke(tonoTocado, (lejos / radio).coerceIn(0f, 1f))
        }
        awaitEachGesture {
            val abajo = awaitFirstDown()
            senalar(abajo.position)
            abajo.consume()
            while (true) {
                val evento = awaitPointerEvent()
                val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                senalar(dedo.position)
                dedo.consume()
            }
        }
    }
    val medido = if (alMedirElCentro == null) Modifier else Modifier.onGloballyPositioned {
        alMedirElCentro(
            it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f)
        )
    }
    Canvas(Modifier.size(lado).then(medido).then(tocable)) {
        val radio = size.minDimension / 2
        val centro = Offset(size.width / 2, size.height / 2)
        drawCircle(Color.Black.copy(alpha = 0.25f), radius = radio, center = centro)
        val disco = radio - SOMBRA_DE_LA_RUEDA.toPx()
        drawCircle(Brush.sweepGradient(tonos, centro), radius = disco, center = centro)
        // El gris del centro: la viveza sale de lo lejos que está el dedo.
        drawCircle(
            Brush.radialGradient(
                listOf(centroDeLaRueda, centroDeLaRueda.copy(alpha = 0f)),
                center = centro,
                radius = disco
            ),
            radius = disco,
            center = centro
        )

        // Los guardados, cada uno en su sitio y de su color: un rojo cae en el rojo. Con
        // aro claro por fuera y filo oscuro por dentro, para que se vean lo mismo sobre el
        // amarillo que sobre el azul marino.
        for (hex in marcas) {
            val hsv = enHsv(parseColor(hex, 255))
            val donde = enLaRueda(hsv[0], hsv[1], disco)
            val en = Offset(centro.x + donde.x.toFloat(), centro.y + donde.y.toFloat())
            drawCircle(Color(parseColor(hex, 255)), radius = PUNTO_DE_LA_MARCA, center = en)
            drawCircle(
                Color.Black.copy(alpha = 0.45f),
                radius = PUNTO_DE_LA_MARCA, center = en, style = Stroke(2.6f)
            )
            drawCircle(
                Color.White.copy(alpha = 0.95f),
                radius = PUNTO_DE_LA_MARCA + 1f, center = en, style = Stroke(1.6f)
            )
        }

        val angulo = Math.toRadians(tono().toDouble())
        val cuanLejos = viveza()
        val marca = Offset(
            centro.x + (kotlin.math.cos(angulo) * cuanLejos * disco).toFloat(),
            centro.y + (kotlin.math.sin(angulo) * cuanLejos * disco).toFloat()
        )
        // Blanca por fuera y negra por dentro, para que se vea encima de cualquier tono.
        drawCircle(Color.White, radius = MARCA_DE_LA_RUEDA, center = marca, style = Stroke(3f))
        drawCircle(
            Color.Black.copy(alpha = 0.6f),
            radius = MARCA_DE_LA_RUEDA, center = marca, style = Stroke(1f)
        )
    }
}

/**
 * **El taller del color: la misma rueda, y al lado los colores guardados.**
 *
 * Es lo que sale de un toque en el mando del color, y es donde se elige despacio: se señala
 * el tono en el aro, se gradúa de negro a blanco en la tira y, si lo que se busca es uno de
 * los de la paleta puesta, está ahí al lado sin tener que apuntarlo en el aro.
 *
 * La rueda es la misma que la del arrastre a propósito: lo que se aprende con el gesto rápido
 * vale aquí, y al revés. Un panel que eligiera el color de otra manera sería una segunda cosa
 * que aprender para la misma decisión.
 */
@Composable
internal fun ElTallerDelColor(
    actual: String,
    guardados: List<String>,
    onElegir: (String) -> Unit,
    /** Los marcados, para que salgan como puntos dentro de la rueda. */
    marcas: List<String> = emptyList(),
    /**
     * Guardar el color que hay puesto como marca de la rueda. Sin esto, no sale el más.
     *
     * **Se guardan a mano y no solos.** Lo que uno toca buscando un color no son sus colores:
     * son los veinte tonos por los que ha pasado el dedo, y una lista que se llena sola de
     * eso no sirve para volver a nada.
     */
    onGuardar: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hsv = enHsv(parseColor(actual, 255))
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 12.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // **La misma rueda que sale al arrastrar el mando**, y aquí además se toca.
                // Ver [LaRuedaDelColor].
                LaRuedaDelColor(
                    tono = { hsv[0] },
                    viveza = { hsv[1] },
                    marcas = marcas,
                    claridad = hsv[2],
                    lado = ANILLO.dp,
                    alElegir = { tono, viveza ->
                        // La claridad no la toca la rueda: la pone la tira de abajo, y es la
                        // que hace que una tinta sea *esa* tinta.
                        onElegir(
                            enTexto(
                                deHsv(floatArrayOf(tono, viveza, hsv[2].coerceAtLeast(0.06f)))
                            )
                        )
                    }
                )
                // Los guardados, en columnas de a tres. Lo que uno toca buscando un color no
                // son sus colores —son los tonos por los que ha pasado el dedo—, así que
                // aquí van los de la paleta puesta, que sí son una decisión tomada.
                Column(Modifier.padding(start = 10.dp)) {
                    for (fila in guardados.take(COLORES_A_MANO).chunked(COLUMNAS_DE_COLORES)) {
                        Row {
                            for (c in fila) {
                                val esEste = c.equals(actual, true)
                                Box(
                                    Modifier
                                        .padding(3.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color(parseColor(c, 255)))
                                        .border(
                                            if (esEste) 2.dp else 1.dp,
                                            if (esEste) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                        .clickable { onElegir(c) }
                                )
                            }
                        }
                    }
                    onGuardar?.let { guardar ->
                        Box(
                            Modifier
                                .padding(3.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { guardar(actual) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            LaTiraDeLaLuz(hsv, onElegir)
        }
    }
}

/**
 * **De negro a blanco pasando por el color**, y leyéndose igual en los dos sentidos.
 *
 * Es una sola cuenta y no dos: por debajo de la mitad el color se apaga y por encima se lava,
 * y leer dónde está el pulgar es esa misma cuenta al revés. Con dos reglas distintas —una
 * para ir y otra para volver— el pulgar salta al cruzar el medio.
 */
@Composable
private fun LaTiraDeLaLuz(hsv: FloatArray, onElegir: (String) -> Unit) {
    // **El gesto se arma una vez y lo que cambia entra por un valor recordado.**
    //
    // Iba con `hsv[0]` de clave, y una clave viva aquí **es un arrastre muerto**: Compose
    // cancela y rearma el bloque de `pointerInput` en cuanto la clave cambia, y el
    // `awaitFirstDown` del bloque nuevo ya no ve ninguna bajada porque el dedo lleva rato
    // apoyado. Tocar seguía yendo —la muestra del toque ya se había emitido— y deslizar no,
    // que es exactamente como se notaba.
    //
    // Y el tono cambiaba casi en cada muestra sin que se viera venir: el color va y vuelve
    // por `#rrggbb`, o sea por tres enteros de ocho bits, así que el `hsv[0]` que sale casi
    // nunca es el que entró. Solo los tonos exactos —0, 60, 120…— sobrevivían al ida y
    // vuelta, y por eso en azul puro casi parecía funcionar.
    //
    // La tira del croquis en el espacio nunca tuvo esto porque su cuenta le llega como
    // parámetro y la envuelve en `rememberUpdatedState`. Es lo mismo que se hace aquí.
    val alValor: (Float) -> Unit = { cuanto ->
        val nuevo =
            if (cuanto <= 0.5f) {
                deHsv(floatArrayOf(hsv[0], 1f, (cuanto * 2f).coerceAtLeast(0.06f)))
            } else {
                deHsv(floatArrayOf(hsv[0], ((1f - cuanto) * 2f).coerceIn(0f, 1f), 1f))
            }
        onElegir(enTexto(nuevo))
    }
    val darValor by rememberUpdatedState(alValor)
    val valor = if (hsv[2] < 0.995f) hsv[2] / 2 else 1f - hsv[1] / 2
    val colores = listOf(
        Color.Black,
        Color(deHsv(floatArrayOf(hsv[0], 1f, 1f))),
        Color.White
    )
    Canvas(
        Modifier
            .padding(top = 10.dp)
            .size(width = ANCHO_DE_LA_TIRA.dp, height = 26.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val abajo = awaitFirstDown()
                    darValor((abajo.position.x / size.width).coerceIn(0f, 1f))
                    abajo.consume()
                    while (true) {
                        val evento = awaitPointerEvent()
                        val dedo = evento.changes.firstOrNull { it.pressed } ?: break
                        darValor((dedo.position.x / size.width).coerceIn(0f, 1f))
                        dedo.consume()
                    }
                }
            }
    ) {
        val alto = size.height * 0.5f
        val arriba = (size.height - alto) / 2
        drawRoundRect(
            Brush.horizontalGradient(colores),
            topLeft = Offset(0f, arriba),
            size = Size(size.width, alto),
            cornerRadius = CornerRadius(alto / 2)
        )
        val x = (valor * size.width).coerceIn(alto / 2, size.width - alto / 2)
        drawCircle(Color.White, radius = alto * 0.62f, center = Offset(x, size.height / 2))
        drawCircle(
            Color.Black.copy(alpha = 0.35f),
            radius = alto * 0.62f, center = Offset(x, size.height / 2), style = Stroke(1f)
        )
    }
}

/**
 * **Deshacer y rehacer, en su propia caja y al final del lateral.**
 *
 * Estaban arriba, en la caja de salir. Y ahí molestaban por dos motivos que se ven usando:
 * deshacer es el botón más pulsado de cualquier editor y **se pulsa sin mirar**, así que su
 * sitio es donde ya está la mano —el lateral, con el resto de lo que se toca dibujando— y no
 * al otro extremo de la pantalla; y una barra de arriba se lee de reojo por encima del
 * dibujo, que es el sitio que uno está mirando.
 *
 * En caja aparte y no con los mandos de estilo, porque no son lo mismo: los de arriba dicen
 * **con qué** se dibuja y estos, **qué hacer con lo dibujado**. Separados por su hueco, la
 * mano da con ellos por el sitio y no por el icono. Es el mismo reparto del croquis en el
 * espacio.
 *
 * Son dos puntos como los demás y no dos iconos sueltos: en un lateral que se maneja posando
 * el dedo en bolitas, un botón cuadrado se lee como que hace otra cosa.
 */
@Composable
fun CajaDeDeshacer(
    puedeDeshacer: Boolean,
    puedeRehacer: Boolean,
    onDeshacer: () -> Unit,
    onRehacer: () -> Unit,
    bola: Dp = BOLA,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = FONDO_DEL_PANEL),
                RoundedCornerShape(CANTO_DEL_PANEL)
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SEPARACION)
    ) {
        PuntoDeAccion(true, "Deshacer", puedeDeshacer, bola, onDeshacer)
        PuntoDeAccion(false, "Rehacer", puedeRehacer, bola, onRehacer)
    }
}

/**
 * Un punto de los de hacer algo, no de los de graduar algo.
 *
 * Apagado se queda a la vista y en gris en vez de desaparecer: un botón que va y viene cambia
 * de sitio a sus vecinos, y entonces deshacer deja de estar siempre donde estaba —que es lo
 * único que hace que pueda pulsarse sin mirar.
 *
 * **Y apagado tiene que verse apagado, no invisible.** Iba a tres décimas de tinta sobre el
 * fondo del panel, y con el tema oscuro eso es un gris sobre otro gris casi igual: al abrir
 * el editor —que es cuando no hay nada que deshacer— los dos puntos no estaban. Al 45 % se
 * lee que el botón está ahí y que ahora no hace nada, que son las dos cosas que tiene que
 * decir.
 */
@Composable
private fun PuntoDeAccion(
    atras: Boolean,
    descripcion: String,
    encendido: Boolean,
    bola: Dp,
    alTocar: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (encendido) 3.dp else 0.dp,
        modifier = Modifier
            .size(bola)
            .semantics { contentDescription = descripcion }
    ) {
        Box(
            Modifier.clickable(enabled = encendido, onClick = alTocar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (atras) Icons.Filled.Undo else Icons.Filled.Redo,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = if (encendido) 1f else 0.45f)
            )
        }
    }
}

/** El damero que hay debajo de una tinta para que se vea que deja ver. */
internal fun DrawScope.dibujarDamero(cuadros: Int = 4) {
    val lado = size.width / cuadros
    var y = 0f
    var impar = false
    while (y < size.height) {
        var x = 0f
        var k = impar
        while (x < size.width) {
            drawRect(
                if (k) Color(0x22FFFFFF) else Color(0x33000000),
                topLeft = Offset(x, y),
                size = Size(
                    kotlin.math.min(lado, size.width - x),
                    kotlin.math.min(lado, size.height - y)
                )
            )
            x += lado
            k = !k
        }
        y += lado
        impar = !impar
    }
}

/** El color como lo entiende Android: tono, viveza y claridad. */
internal fun deHsv(hsv: FloatArray): Int = android.graphics.Color.HSVToColor(hsv)

internal fun enHsv(argb: Int): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }

/** Y de vuelta a como se guarda en el dibujo: `#rrggbb`. */
internal fun enTexto(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)

/** En cuántos tonos se reparte la vuelta de la rueda. */
private const val TONOS_DE_LA_RUEDA = 24

/** Lo gordo que va un color guardado dentro de la rueda. Ver [LaRuedaDelColor]. */
private const val PUNTO_DE_LA_MARCA = 5.5f

/** El canto oscuro que la despega del dibujo, y lo gorda que va su marca. */
/**
 * El filo oscuro que asoma por fuera del disco, **por fuera y no por dentro**.
 *
 * Estaba en píxeles y se le restaba al radio, así que el disco pintado medía un pelo menos
 * que [RADIO_DE_LA_RUEDA] — y [RADIO_DE_LA_RUEDA] es con lo que el arrastre divide para
 * saber lo viva que sale la tinta. Esa diferencia es la que hacía las dos cosas raras de la
 * rueda, que eran la misma:
 *
 * - **El punto no iba bajo el lápiz.** Se pinta a `viveza · disco` y el dedo está a
 *   `viveza · RADIO_DE_LA_RUEDA`, así que el hueco entre los dos **crece con la distancia**:
 *   nada en el centro y unos cuantos píxeles en el borde. Justo lo que se veía.
 * - **Y el color no era el de debajo.** La tinta salía con `distancia / RADIO_DE_LA_RUEDA` y
 *   lo pintado en ese punto es `distancia / disco`: parecido, y por eso costaba de ver, pero
 *   nunca el mismo.
 *
 * En `dp` para que la cuenta cuadre sin depender de la densidad de la pantalla, y sumada al
 * tamaño en vez de restada al radio: así el disco mide [RADIO_DE_LA_RUEDA] **exactamente** y
 * no hay dos radios que mantener de acuerdo.
 */
/**
 * Los tres números de las flechitas, con nombre porque los usan los dos lienzos.
 *
 * Estaban escritos a pelo aquí y como constantes en el croquis en el espacio, y **ya se
 * habían separado**: el hueco iba al 0,07 en un sitio y al 0,06 en el otro, sin que nadie
 * lo hubiera decidido. Es el tipo de deriva que justifica todo este trabajo.
 */
private const val ALFA_DE_LAS_FLECHITAS = 0.22f
private const val HUECO_DE_LAS_FLECHITAS = 0.07f
private const val GRUESO_DE_LAS_FLECHITAS = 1.4f

internal val SOMBRA_DE_LA_RUEDA = 3.dp

/**
 * Lo que ocupa la rueda entera: el disco más el filo por cada lado.
 *
 * **La cuenta que importa es la de vuelta**: `LADO_DE_LA_RUEDA / 2 - SOMBRA_DE_LA_RUEDA`
 * tiene que dar [RADIO_DE_LA_RUEDA] clavado, porque ese es el radio contra el que el
 * arrastre mide lo viva que sale la tinta. La fija una prueba, que es la única forma de que
 * no se vuelva a descuadrar sin que se note. Ver [SOMBRA_DE_LA_RUEDA].
 */
internal val LADO_DE_LA_RUEDA: Dp = (RADIO_DE_LA_RUEDA * 2).dp + SOMBRA_DE_LA_RUEDA * 2
private const val MARCA_DE_LA_RUEDA = 7f

/** Lo que mide el aro del taller y su tira, y cuántos guardados salen al lado. */
private const val ANILLO = 96
private const val ANCHO_DE_LA_TIRA = 168
private const val COLORES_A_MANO = 9
private const val COLUMNAS_DE_COLORES = 3

/**
 * **Lo que se está tocando, y cuánto dura en la pantalla.**
 *
 * Hace falta **porque el mando es un botón**: en una tira, dónde está el dedo ya dice qué
 * valor hay; en un botón de dos centímetros, no. Así que lo dice la muestra de al lado, y
 * solo mientras se ajusta —con la mano fuera se va sola, que es para mirar de reojo mientras
 * se arrastra y no una etiqueta más pegada al panel.
 *
 * **El reloj se arma con la cuenta y no con lo que dice.** Arrastrando, lo que dice cambia en
 * cada muestra del dedo, así que atado a eso se estaría rearmando un reloj por fotograma para
 * no usarlo ninguna vez.
 */
@androidx.compose.runtime.Stable
internal class ElChivato {
    /** Si la muestra está a la vista. */
    var visible by mutableStateOf(false)
        private set

    /** Si la mano sigue encima: entonces la muestra no se va. */
    var agarrado by mutableStateOf(false)

    /**
     * **Qué se está ajustando, para quien lo enseñe con palabras.**
     *
     * Los tres van a null en el lienzo plano, que no los usa: allí la pastilla del lado
     * ya dice lo que hay que decir. Los llena el croquis en el espacio, que enseña **el
     * nombre al lado y el número dentro del propio mando** — y esa es la parte que no se
     * comparte, porque son dos vistas legítimas del mismo estado. Lo que sí se comparte es
     * esto: el estado y su reloj, que era lo que estaba escrito dos veces.
     */
    var dice by mutableStateOf<String?>(null)
        private set
    var numero by mutableStateOf<String?>(null)
        private set
    var deQuien by mutableStateOf<String?>(null)
        private set

    /** Lo que se está ajustando: su nombre y su número, y de qué mando son. */
    fun ensenar(quien: String, nombre: String, cuanto: String? = null) {
        deQuien = quien
        dice = nombre
        numero = cuanto
        visible = true
    }

    /** Cuántas veces hay algo nuevo que quitar de la pantalla. Ver [rememberChivato]. */
    var cuenta by mutableStateOf(0)
        private set

    fun agarrar() {
        agarrado = true
        visible = true
    }

    /** La mano se va: a partir de aquí, la muestra tiene sus segundos contados. */
    fun soltar() {
        agarrado = false
        cuenta++
    }

    /** Enseñar algo de pasada, sin mano encima: un toque que cambió algo. */
    fun deReojo() {
        visible = true
        agarrado = false
        cuenta++
    }

    /** Lo mismo, diciendo qué. Ver [ensenar]. */
    fun deReojo(quien: String, nombre: String, cuanto: String? = null) {
        ensenar(quien, nombre, cuanto)
        agarrado = false
        cuenta++
    }

    internal fun callar() {
        visible = false
        dice = null
        numero = null
        deQuien = null
    }
}

/** Un chivato con su reloj puesto. */
@Composable
internal fun rememberChivato(): ElChivato {
    val chivato = remember { ElChivato() }
    androidx.compose.runtime.LaunchedEffect(chivato.cuenta) {
        if (chivato.visible && !chivato.agarrado) {
            kotlinx.coroutines.delay(LO_QUE_DURA_EL_CHIVATO)
            chivato.callar()
        }
    }
    return chivato
}

/** Lo que se queda en la pantalla una muestra a la que ya no le queda mano encima. */
private const val LO_QUE_DURA_EL_CHIVATO = 900L
