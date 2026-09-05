package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Las cuentas de los controles que se manejan arrastrando.
 *
 * ## Por qué esto está aparte de la interfaz
 *
 * Un deslizador es dos cosas: unos píxeles que se pintan y **una regla de tres**
 * que convierte dónde está el dedo en qué valor sale. La segunda es donde se
 * cuelan los fallos —el que se pasa de rango, el que salta de dos en dos, el que
 * al soltar en el borde elige la opción de al lado— y es la que no se puede
 * comprobar sin un móvil si vive dentro de un `Composable`.
 *
 * Así que vive aquí, en funciones que no saben de Compose ni de Android, y la
 * interfaz solo las llama. Ver [PanelLateralDeEstilo].
 */

/**
 * Qué opción cae bajo un arrastre de [dx] píxeles.
 *
 * [paso] es lo que hay que arrastrar para pasar de una opción a la siguiente.
 * [haciaLaIzquierda] invierte el sentido: el panel puede estar a la derecha de
 * la pantalla, y entonces las opciones salen hacia la izquierda y arrastrar
 * hacia allí es avanzar, no retroceder.
 *
 * El resultado nunca se sale de la lista: pasarse arrastrando deja en la última,
 * que es lo que espera la mano —se empuja hasta el final y se suelta— y no que
 * la selección dé la vuelta.
 */
fun opcionArrastrada(dx: Float, paso: Float, cuantas: Int, haciaLaIzquierda: Boolean): Int {
    if (cuantas <= 0) return -1
    if (paso <= 0f) return 0
    val avance = if (haciaLaIzquierda) -dx else dx
    // Media casilla de margen: el centro de cada opción es su punto, así que se
    // redondea. Sin esto habría que pasar la opción entera para llegar a ella.
    val i = (avance / paso).roundToInt()
    return i.coerceIn(0, cuantas - 1)
}

/**
 * **Qué opción cae bajo un arrastre vertical, contando desde la que ya estaba.**
 *
 * Es la hermana de [opcionArrastrada] para el gesto de arriba y abajo, y se diferencia en
 * algo más que el eje: **parte de la opción puesta**, no de la primera. Arrastrar cero deja
 * lo que había, bajar un paso pasa a la siguiente y subir uno vuelve a la anterior.
 *
 * Eso es lo que hace que el gesto sea el mismo en los dos lienzos. En el croquis en el
 * espacio, cada mando es un botón que enseña **lo que hay puesto** y se sube o se baja desde
 * ahí; midiendo desde la primera opción, el mismo gesto haría cosas distintas según lo que
 * estuviera elegido — y eso es exactamente lo que no se puede hacer de reojo.
 *
 * Bajar avanza porque las opciones salen en columna hacia abajo: el dedo va por donde va la
 * lista, que es lo único que no hay que explicar.
 */
fun opcionArrastradaAbajo(dy: Float, paso: Float, cuantas: Int, desde: Int): Int {
    if (cuantas <= 0) return -1
    val ahora = desde.coerceIn(0, cuantas - 1)
    if (paso <= 0f) return ahora
    // Media casilla de margen, como en la horizontal: el centro de cada opción es su punto.
    return (ahora + (dy / paso).roundToInt()).coerceIn(0, cuantas - 1)
}

/**
 * Si un arrastre de [dx] píxeles cuenta ya como abrir el desplegable.
 *
 * Hace falta un mínimo porque **un toque también arrastra**: el dedo nunca se
 * levanta exactamente donde cayó, y sin margen cualquier toque abriría el
 * desplegable y elegiría algo. Ver `viewConfiguration.touchSlop`, que es de
 * donde sale el número que se pasa aquí.
 */
fun abreElDesplegable(dx: Float, minimo: Float): Boolean = abs(dx) >= minimo

/**
 * De dónde está el dedo a qué fracción del recorrido, con **arriba = 1**.
 *
 * Se invierte a propósito: en un deslizador vertical, arriba es más —más grosor,
 * más opacidad— porque es como se lee un termómetro y como se entiende el
 * volumen. En coordenadas de pantalla arriba es cero, así que la vuelta se da
 * aquí y no en cada control.
 */
fun fraccionVertical(y: Float, alto: Float): Float {
    if (alto <= 0f) return 0f
    return (1f - y / alto).coerceIn(0f, 1f)
}

/**
 * La casilla de [cuantas] que le toca a una fracción del recorrido.
 *
 * Reparto en partes iguales y con el redondeo en el centro de cada casilla: con
 * cuatro grosores, el primer cuarto de arriba es el cuarto grosor entero, no
 * solo su borde.
 */
fun casillaDe(fraccion: Float, cuantas: Int): Int {
    if (cuantas <= 0) return -1
    if (cuantas == 1) return 0
    return (fraccion.coerceIn(0f, 1f) * (cuantas - 1)).roundToInt().coerceIn(0, cuantas - 1)
}

/** Dónde queda el mango de un deslizador de casillas, en fracción del alto. */
fun fraccionDeLaCasilla(indice: Int, cuantas: Int): Float {
    if (cuantas <= 1) return 1f
    return (indice.coerceIn(0, cuantas - 1)).toFloat() / (cuantas - 1)
}

/**
 * Un valor continuo redondeado a su paso.
 *
 * La opacidad va de 0 a 100 y no tiene sentido dejarla en 63: se salta de cinco
 * en cinco para que el número sea redondo y para que el mismo sitio del dedo dé
 * siempre el mismo valor.
 */
fun valorConPaso(fraccion: Float, minimo: Int, maximo: Int, paso: Int): Int {
    if (maximo <= minimo || paso <= 0) return minimo
    val crudo = minimo + fraccion.coerceIn(0f, 1f) * (maximo - minimo)
    val redondo = (crudo / paso).roundToInt() * paso
    return redondo.coerceIn(minimo, maximo)
}

/** Y la vuelta: en qué fracción del recorrido queda un valor. */
fun fraccionDelValor(valor: Int, minimo: Int, maximo: Int): Float {
    if (maximo <= minimo) return 0f
    return ((valor - minimo).toFloat() / (maximo - minimo)).coerceIn(0f, 1f)
}

/**
 * Cuál de los valores de una lista es el que hay puesto.
 *
 * Se busca **el más cercano** y no el igual: un dibujo abierto de fuera puede
 * traer un grosor de 3,5 que no está en la lista, y el deslizador tiene que
 * colocarse en algún sitio en vez de saltar al primero.
 */
fun masCercano(valor: Double, valores: List<Double>): Int {
    if (valores.isEmpty()) return -1
    var mejor = 0
    var distancia = Double.MAX_VALUE
    valores.forEachIndexed { i, v ->
        val d = abs(v - valor)
        if (d < distancia) {
            distancia = d
            mejor = i
        }
    }
    return mejor
}

/**
 * De dónde está el mango a qué grosor sale, y al revés.
 *
 * **No es lineal, y con razón**: la diferencia entre uno y dos puntos se ve
 * muchísimo, y la que hay entre dieciocho y diecinueve no se ve. Repartido a
 * partes iguales, medio deslizador daría grosores que a ojo son el mismo y el
 * primer centímetro de recorrido se saltaría todos los finos de golpe.
 *
 * Va **al cubo**: a medio recorrido salen unos tres puntos, y los dos primeros
 * tercios se los reparten los grosores de escribir y subrayar, que son los que
 * de verdad se afinan. Los gordos siguen estando, arriba del todo, donde se
 * llega de un empujón porque tampoco hace falta puntería para pedir «muy
 * gordo».
 *
 * Y sale **a cuartos de punto**: un grosor de 3,8172 no lo ha pedido nadie,
 * ensucia el archivo y hace que el mismo sitio del dedo dé dos valores
 * distintos según el píxel exacto.
 */
fun grosorDeLaFraccion(f: Float): Double {
    val x = f.coerceIn(0f, 1f).toDouble()
    val crudo = GROSOR_MINIMO + (GROSOR_MAXIMO - GROSOR_MINIMO) * x * x * x
    return grosorRedondo(crudo)
}

/**
 * El redondeo de todo grosor que sale de un mando: **a paso limpio, y con el
 * paso a la medida del número**. A cuartos de punto en los grosores de siempre;
 * por debajo, más fino — con el suelo en 0,01 para los planos, el cuarto de
 * punto se tragaba todos los finos de golpe (y el 0,01 mismo redondeaba a
 * cero). Los pasos son múltiplos de 0,01, y el céntimo final quita la morralla
 * del coma flotante antes de que se guarde.
 */
private fun grosorRedondo(crudo: Double): Double {
    val paso = when {
        crudo >= 2.0 -> 0.25
        crudo >= 0.2 -> 0.05
        else -> 0.01
    }
    val limpio = kotlin.math.round(kotlin.math.round(crudo / paso) * paso * 100.0) / 100.0
    return limpio.coerceIn(GROSOR_MINIMO, GROSOR_MAXIMO)
}

/** Y la vuelta: en qué punto del recorrido queda un grosor. */
fun fraccionDelGrosor(g: Double): Float {
    val d = GROSOR_MAXIMO - GROSOR_MINIMO
    if (d <= 0.0) return 0f
    val x = ((g - GROSOR_MINIMO) / d).coerceIn(0.0, 1.0)
    return Math.cbrt(x).toFloat()
}

/**
 * El grosor, escrito para enseñarlo mientras se arrastra.
 *
 * Sin decimales cuando no hacen falta: «4» y no «4,00». Lo que se busca es
 * saber en qué número vas, no un informe.
 */
fun grosorEscrito(g: Double): String =
    if (g == kotlin.math.floor(g)) g.toInt().toString()
    else String.format(java.util.Locale.US, "%.2f", g).trimEnd('0').replace('.', ',')

/**
 * Lo más fino y lo más gordo que se puede poner un trazo.
 *
 * El suelo bajó de 0,5 a 0,01 **por los planos**: el grosor es una medida del
 * mundo —el mismo número da la misma raya se mire desde donde se mire, como en
 * el croquis del espacio— así que dibujando muy acercado hay que bajar el
 * número, y con el suelo en 0,5 no había adónde bajarlo: a 100 aumentos medio
 * punto de escena son cincuenta píxeles de pantalla, una brocha. Con 0,01 se
 * puede trazar fino sobre el detalle de un plano, y el chivato del mando —que
 * enseña el grosor como saldrá en pantalla— dice si ya se está en lo que se
 * quiere.
 */
const val GROSOR_MINIMO = 0.01
const val GROSOR_MAXIMO = 20.0

// ---- Los mandos que se posan y se arrastran, como los del croquis en el espacio ----

/**
 * **El tono que pide un arrastre, por hacia dónde va.**
 *
 * El mando del color no es una recta: es un círculo. El dedo se posa en el botón y se va
 * hacia donde está el color que busca —arriba los verdes, abajo los morados, a un lado los
 * rojos— y el ángulo de esa salida **es** el tono. Se mide desde donde se posó, no desde el
 * centro del botón, que es lo que permite que el botón mida dos centímetros y la rueda, un
 * palmo.
 *
 * [subida] va con el signo de la mano —positiva hacia arriba— y aquí se le da la vuelta
 * para medir en ángulos de pantalla, que es como se pinta la rueda: el tono `t` cae a `t`
 * grados en el sentido de las agujas del reloj. Así lo que se ve y lo que sale es lo mismo.
 */
fun tonoDelArrastre(lado: Float, subida: Float): Float {
    val grados = Math.toDegrees(kotlin.math.atan2(-subida.toDouble(), lado.toDouble()))
    return ((grados + 360.0) % 360.0).toFloat()
}

/**
 * **Y lo viva que sale: lo lejos que se ha ido el dedo.**
 *
 * En el centro, gris; en el borde de la rueda, el color a tope. Es lo mismo que hace
 * cualquier rueda de color, y de paso resuelve el rincón malo del gesto: en el mismísimo
 * punto donde se posó el dedo no hay ángulo que medir, y ahí tampoco hay color que pedir.
 */
fun vivezaDelArrastre(lado: Float, subida: Float, radio: Float): Float {
    if (radio <= 0f) return 0f
    return (kotlin.math.hypot(lado, subida) / radio).coerceIn(0f, 1f)
}

/**
 * **Lo gordo que sale de un arrastre, contando desde lo que había.**
 *
 * Va **a saltos iguales de tamaño y no de número**: cada tanto de recorrido multiplica por
 * otro tanto, no suma otro tanto. De medio punto a veinte hay cuarenta veces, y repartir eso
 * a partes iguales por el recorrido deja lo de escribir —de dos a cuatro— en el primer
 * centímetro y el resto del dedo para grosores de tachar. Por veces, el mando responde igual
 * de fino abajo que arriba, que es como se percibe el grosor: nadie nota un cuarto de punto
 * sobre veinte, y sobre uno lo nota todo el mundo.
 *
 * [recorrido] va en `dp` y con el signo de la mano: subir engorda.
 */
fun grosorArrastrado(partida: Double, recorrido: Float): Double {
    val desde = partida.coerceIn(GROSOR_MINIMO, GROSOR_MAXIMO)
    val gordo = desde * Math.pow(VECES_POR_DP, recorrido.toDouble())
    // Redondeado, que el mismo sitio del dedo dé siempre el mismo valor.
    return grosorRedondo(gordo)
}

/**
 * **Lo que tapa, contando desde lo que había.**
 *
 * Esta sí es una recta: de nada a del todo hay cien, y cien es cien en cualquier punto de la
 * escala. Un dedo entero de recorrido la cruza de punta a punta, y sale de cinco en cinco
 * para que el número sea redondo y para que el mismo sitio dé siempre el mismo valor.
 */
fun opacidadArrastrada(partida: Int, recorrido: Float): Int {
    val crudo = partida + recorrido / RECORRIDO_DEL_MANDO * 100f
    val redondo = (crudo / PASO_DE_LO_QUE_TAPA).roundToInt() * PASO_DE_LO_QUE_TAPA
    return redondo.coerceIn(0, 100)
}

/**
 * La opacidad que le toca a una fracción del recorrido, **con su paso**.
 *
 * La vuelta de [fraccionDeLaOpacidad], y la que hace falta para el imán: las marcas viven
 * en fracciones de 0 a 1 —son las mismas para cualquier escala— y lo que se guarda en el
 * estilo es el número de 0 a 100. Pasa por el mismo redondeo que [opacidadArrastrada], que
 * si no una marca podría dejar el valor en 63 justo donde el arrastre nunca lo deja.
 */
fun opacidadDeLaFraccion(fraccion: Float): Int =
    valorConPaso(fraccion, 0, 100, PASO_DE_LO_QUE_TAPA)

/** En qué punto del recorrido queda una opacidad. La vuelta de [opacidadDeLaFraccion]. */
fun fraccionDeLaOpacidad(opacidad: Int): Float = fraccionDelValor(opacidad, 0, 100)

/**
 * Cuánto hay que arrastrar para recorrer un mando de punta a punta, en `dp`.
 *
 * Un dedo de largo. Más corto, apurar un extremo pide pulso de relojero; más largo, no cabe
 * en una pantalla y hay que arrastrar dos veces.
 */
const val RECORRIDO_DEL_MANDO = 170f

/**
 * Cuántas veces engorda el trazo por cada `dp` de arrastre.
 *
 * Sale de repartir el grosor entero —de [GROSOR_MINIMO] a [GROSOR_MAXIMO], que son cuarenta
 * veces— por [RECORRIDO_DEL_MANDO], **por veces y no a partes iguales**. Ver
 * [grosorArrastrado].
 */
const val VECES_POR_DP = 1.0219

/**
 * Lo lejos que hay que irse del botón del color para llegar al borde de la rueda, en `dp`.
 *
 * Es el radio de la rueda que sale al lado y también el recorrido del gesto: la que se ve es
 * **del mismo tamaño** que la que se está manejando, así que lo que se mira es dónde está el
 * dedo y no una traducción de dónde está.
 */
const val RADIO_DE_LA_RUEDA = 66f

/** Cuánto recorrido cuesta pasar de una opción a la siguiente en un mando, en `dp`. */
const val PASO_DE_LA_OPCION = 32f

/** Lo menos clara que se deja una tinta al cogerla con la rueda: si no, el disco sale negro. */
const val CLARIDAD_MINIMA = 0.55f

/** De cinco en cinco, que es como se dice lo que tapa una tinta. */
private const val PASO_DE_LO_QUE_TAPA = 5

/**
 * **Lo grande que sale la letra, contando desde lo que había.**
 *
 * Por veces y no a partes iguales, como el grosor y por lo mismo: de ocho a diez se ve
 * muchísimo y de ochenta a ochenta y dos no se ve nada, así que un recorrido lineal deja
 * media pantalla para diferencias que nadie nota y aprieta al principio, que es donde se
 * decide. Y a números enteros: nadie ha pedido una letra de 23,7.
 */
fun tamanoDeLetraArrastrado(partida: Double, recorrido: Float): Double {
    val desde = partida.coerceIn(LETRA_MINIMA, LETRA_MAXIMA)
    val tam = desde * Math.pow(VECES_POR_DP_DE_LA_LETRA, recorrido.toDouble())
    return kotlin.math.round(tam).coerceIn(LETRA_MINIMA, LETRA_MAXIMA)
}

/** De lo más pequeño que se lee a lo más grande que cabe en un rótulo. */
const val LETRA_MINIMA = 8.0
const val LETRA_MAXIMA = 96.0

/**
 * Cuánto crece la letra por cada `dp` de arrastre.
 *
 * Sale de repartir la escala entera —doce veces— por [RECORRIDO_DEL_MANDO], por veces.
 */
const val VECES_POR_DP_DE_LA_LETRA = 1.0147

// ---- Las marcas de la rueda del color ----

/**
 * **Dónde cae un color dentro de la rueda**, en `dp` desde su centro y en coordenadas de
 * pantalla —la `y` hacia abajo—.
 *
 * El tono es el ángulo y la viveza, lo lejos: es la propia definición de la rueda. Se mide
 * con las agujas del reloj desde las tres, que es como reparte los tonos el barrido de
 * Compose y como los lee [tonoDelArrastre]; así lo que se pinta y lo que se elige están en el
 * mismo sitio.
 */
fun enLaRueda(tono: Float, viveza: Float, radio: Float): Pt {
    val a = Math.toRadians(tono.toDouble())
    val d = viveza.coerceIn(0f, 1f) * radio
    return Pt(kotlin.math.cos(a) * d, kotlin.math.sin(a) * d)
}

/**
 * **Qué marca tiene el dedo encima, si es que tiene alguna.** Devuelve su índice, o -1.
 *
 * Es lo que convierte una marca en algo que sirve: pintada sola dice «este color estaba
 * aquí», pero volver a él seguiría siendo cuestión de puntería, y a pulso sobre una rueda de
 * dos dedos no se acierta un tono concreto dos veces seguidas. Imantada, se pasa cerca y se
 * cae dentro — que es lo mismo que hacen las marcas de los deslizadores.
 *
 * [lado] y [subida] son dónde está el dedo respecto del **centro de la rueda**, en `dp` y con
 * la subida positiva hacia arriba. [marcas] son los tonos y vivezas de cada marca.
 */
fun marcaImantada(
    lado: Float,
    subida: Float,
    radio: Float,
    marcas: List<Pair<Float, Float>>
): Int {
    var mejor = -1
    var cerca = IMAN_DE_LA_MARCA_DEL_COLOR
    marcas.forEachIndexed { i, (tono, viveza) ->
        val donde = enLaRueda(tono, viveza, radio)
        val d = kotlin.math.hypot(lado - donde.x, -subida - donde.y).toFloat()
        if (d < cerca) {
            cerca = d
            mejor = i
        }
    }
    return mejor
}

/**
 * Lo cerca que hay que pasar de una marca para caer en ella, en `dp`.
 *
 * Poco más que la propia marca: el imán está para no tener que afinar el último milímetro,
 * no para robarle el gesto a quien está eligiendo el tono de al lado.
 */
const val IMAN_DE_LA_MARCA_DEL_COLOR = 13f

/** Cuántas marcas se guardan. Más que esto, dejan de ser marcas y son otra paleta. */
const val MARCAS_DE_COLOR = 8

/**
 * **Cuánto alumbra, contando desde lo que había.**
 *
 * Una recta, como lo que tapa: de apagada a tope hay uno, y ese uno vale lo mismo en
 * cualquier punto de la escala. Un dedo entero de recorrido la cruza entera, y sale a
 * centésimas para que el mismo sitio del dedo dé siempre el mismo brillo.
 */
fun brilloArrastrado(partida: Double, recorrido: Float): Double {
    val crudo = partida + recorrido / RECORRIDO_DEL_MANDO
    return (kotlin.math.round(crudo * 100) / 100.0).coerceIn(0.0, 1.0)
}
