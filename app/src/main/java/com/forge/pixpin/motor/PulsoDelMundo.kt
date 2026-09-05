package com.forge.pixpin.motor

/**
 * **El pulso del trazo, medido en el mundo**: cuánto engorda por apretar y
 * cuánto adelgaza por correr, con el ancho de cada muestra en unidades de mundo.
 *
 * Es lo único que separa una raya de un trazo a mano alzada. Una línea de ancho
 * parejo se lee como algo trazado con instrumento; la mano deja siempre una
 * línea que nace fina, engorda donde se apoya, adelgaza en los sitios rápidos y
 * se va afilando al levantar. Sin eso, un apunte a mano alzada se ve como un
 * diagrama.
 *
 * ## Por qué en el mundo y no en la pantalla
 *
 * La versión de pantalla vivía en el lienzo del espacio y medía los huecos
 * entre puntos **ya proyectados**: la proyección estira unos tramos y encoge
 * otros, así que el mismo trazo cambiaba de pulso al mover la cámara o al
 * acercarse. Aquí las distancias son las del mundo y los tiempos, los del
 * reloj: el pulso queda **grabado en el trazo** y es el mismo se mire desde
 * donde se mire. La forma de la cuenta es la de aquella versión —que ya estaba
 * afinada a ojo— con las entradas buenas.
 *
 * ## De dónde sale
 *
 * De dos sitios, y se multiplican:
 *
 * - **De lo que aprieta la punta**, cuando el lápiz óptico lo mide. Es lo
 *   bueno: es exactamente lo que hizo la mano.
 * - **De lo rápido que iba**, siempre. Con reloj, la velocidad de verdad:
 *   mundo recorrido entre tiempo pasado. Sin reloj, lo separadas que quedaron
 *   las muestras —el trazo se guarda cada tantas, así que ir deprisa las
 *   separa—. En los dos casos se compara con la mediana **de ese mismo trazo**:
 *   así vale igual con el dedo que con lápiz, a cualquier escala del mundo y a
 *   cualquier velocidad de mano, sin ninguna constante que calibrar.
 *
 * Con el dedo, o con un lápiz que no mida presión, llega todo igual: entonces
 * manda la velocidad, que es lo que hace cualquiera que dibuja simulando
 * presión.
 *
 * ## Y las rectas no
 *
 * Un trazo de pocas muestras —una raya con regla, o una enderezada por
 * pararse— sale de ancho parejo. Afilándole las puntas, una recta se
 * convertiría en una lenteja, y una arista tiene que ser una arista.
 */

/**
 * El ancho de cada muestra del trazo, en unidades de **mundo**.
 *
 * [calibre] es el grosor del estilo, en mundo. [presiones] llega por muestra
 * —ya interpolada a la espina— o nula cuando no hay quien la mida. [recorrido]
 * es el largo acumulado en el mundo, una entrada por muestra. [tiempos] llega
 * en segundos por muestra, o nulo: entonces la señal de velocidad es el hueco
 * de mundo contra la mediana de los huecos del propio trazo, que se normaliza
 * sola y sale **invariante a escala**.
 *
 * Devuelve tantos anchos como muestras, nunca NaN y nunca cero o negativo.
 */
fun anchosDelMundo(
    calibre: Double,
    presiones: DoubleArray?,
    recorrido: DoubleArray,
    tiempos: DoubleArray?
): DoubleArray {
    val n = recorrido.size
    if (n == 0) return DoubleArray(0)

    // Una raya con regla, o un trazo de dos toques, sale de ancho parejo: la
    // velocidad pide comparar huecos y aquí no hay con qué, y afilar las puntas
    // convertiría la recta en una lenteja. La presión media sí se conserva:
    // apretar la mitad sigue siendo un trazo la mitad de gordo.
    if (n < MINIMO_PARA_EL_PULSO) {
        val parejo = calibre * presionMedia(presiones, n)
        return DoubleArray(n) { parejo }
    }

    // ---- La señal de «lo rápido que iba», una por muestra ----
    val senal = DoubleArray(n)
    if (tiempos != null) {
        // Con reloj: velocidad de verdad, **medida en una ventana de tiempo
        // alrededor de cada muestra** y no entre dos muestras seguidas. Las
        // muestras llegan a rachas: el paso por los huecos de pantalla mete
        // varias con el mismo instante y después una con todo el tiempo del
        // toque, y el cociente muestra a muestra daba de cero a infinito en
        // cuatro muestras — el rotulador salía a bandas, gordo y fino a saltos,
        // como si la presión bailara. En la ventana se suman el mundo recorrido
        // y el tiempo pasado antes de dividir, y ese cociente sí es la
        // velocidad de la mano. Un reloj parado o hacia atrás no es una
        // velocidad infinita: la ventana se cierra ahí y, si no queda tiempo,
        // se hereda la velocidad anterior.
        var anterior = 0.0
        for (i in 0 until n) {
            var j = i
            var k = i
            while (j > 0 && tiempos[j - 1] <= tiempos[i] &&
                tiempos[i] - tiempos[j - 1] < VENTANA_DEL_RELOJ / 2
            ) j--
            while (k < n - 1 && tiempos[k + 1] >= tiempos[i] &&
                tiempos[k + 1] - tiempos[i] < VENTANA_DEL_RELOJ / 2
            ) k++
            // Al menos una muestra a cada lado, si la hay: con muestras muy
            // separadas la ventana se queda vacía y el hueco sigue valiendo.
            if (j == i && i > 0) j = i - 1
            if (k == i && i < n - 1) k = i + 1
            val dt = tiempos[k] - tiempos[j]
            senal[i] = if (dt > 0.0) (recorrido[k] - recorrido[j]) / dt else anterior
            anterior = senal[i]
        }
    } else {
        // Sin reloj: lo separadas que quedaron las muestras hace de velocidad.
        for (i in 1 until n) senal[i] = recorrido[i] - recorrido[i - 1]
        // La primera muestra no tiene hueco detrás: se le repite el de la segunda.
        senal[0] = senal[1]
    }

    // La mediana del propio trazo, **sin ordenar el array entero**: selección
    // rápida sobre una copia, que reparte de sitio. La media no valdría: una
    // sola parada larga la arrastraría y adelgazaría el trazo entero.
    val mediana = laDeEnMedio(senal.copyOf(), n)

    // ¿Dice algo la presión? Si llega toda igual —el dedo, o un lápiz que no la
    // mide—, no se le hace caso: multiplicar por una constante solo adelgazaría
    // el trazo entero.
    var hayPresion = false
    if (presiones != null && presiones.isNotEmpty()) {
        var floja = Double.MAX_VALUE
        var fuerte = -Double.MAX_VALUE
        for (i in 0 until minOf(presiones.size, n)) {
            val p = presiones[i]
            if (p < floja) floja = p
            if (p > fuerte) fuerte = p
        }
        hayPresion = fuerte - floja > VARIACION_QUE_CUENTA
    }

    val salida = DoubleArray(n)
    for (i in 0 until n) {
        // Lo deprisa que iba aquí, de 0 —al paso del trazo o menos— a 1 —a
        // RANGO_DEL_PULSO veces su mediana—. La versión de pantalla acotaba la
        // mediana con un mínimo en píxeles; en el mundo no hay unidad fija y un
        // tope absoluto rompería la invariancia a escala, así que la mediana
        // nula —un trazo clavado en el sitio más de la mitad del tiempo— se
        // trata aparte: sin paso normal con el que comparar, solo cuenta si la
        // muestra se movió o no.
        val deprisa =
            if (mediana > 0.0) ((senal[i] / mediana - 1.0) / RANGO_DEL_PULSO).coerceIn(0.0, 1.0)
            else if (senal[i] > 0.0) 1.0
            else 0.0
        var suyo = 1.0 - deprisa * (1.0 - MINIMO_DEL_PULSO)
        if (hayPresion && presiones != null) {
            val p = presiones[minOf(i, presiones.size - 1)].coerceIn(0.0, 1.0)
            suyo *= MINIMO_DEL_PULSO + (1.0 - MINIMO_DEL_PULSO) * p
        }
        // Y las dos puntas se afilan: es donde la mano posa y levanta, y un
        // trazo que acaba en un tajo recto no parece hecho a mano.
        val delBorde = minOf(i, n - 1 - i)
        if (delBorde < PUNTAS_QUE_SE_AFILAN) {
            val cuanto = (delBorde + 1.0) / (PUNTAS_QUE_SE_AFILAN + 1.0)
            suyo *= AFILADO + (1.0 - AFILADO) * cuanto
        }
        // El cinturón: pase lo que traiga la entrada —hasta un NaN colado en el
        // recorrido—, el factor queda en un rango sano y de aquí no sale un
        // ancho nulo, negativo ni NaN.
        salida[i] = if (suyo.isNaN()) 1.0 else suyo.coerceIn(SUELO_DEL_FACTOR, 1.0)
    }

    // Alisado de cinco: [1, 2, 1] / 4 pasado dos veces, que es [1, 4, 6, 4, 1] / 16.
    // Sin él, una muestra suelta que llegó lejos —o un tembleque de la presión
    // del lápiz— deja un estrangulamiento en mitad del trazo, y se ve como un
    // fallo y no como pulso. Era de tres; con el lápiz óptico, que mide la
    // presión con ruido en cada muestra, tres no bastaban para que el rotulador
    // saliera parejo. En muestras y no en mundo, para que el mismo gesto mil
    // veces más grande dé los mismos anchos. El calibre entra aquí y se sale ya
    // en anchos de mundo.
    val paso = DoubleArray(n)
    for (i in 0 until n) {
        val a = salida[if (i == 0) 0 else i - 1]
        val b = salida[if (i == n - 1) n - 1 else i + 1]
        paso[i] = (a + salida[i] * 2.0 + b) / 4.0
    }
    val anchos = DoubleArray(n)
    for (i in 0 until n) {
        val a = paso[if (i == 0) 0 else i - 1]
        val b = paso[if (i == n - 1) n - 1 else i + 1]
        anchos[i] = calibre * (a + paso[i] * 2.0 + b) / 4.0
    }
    return anchos
}

/**
 * La presión media de las primeras [n] muestras, o 1 si no hay quien la mida.
 *
 * Es lo único que conserva un trazo corto. Se descartan los valores rotos y se
 * acota cada uno a su rango: una presión de 3 no la ha medido ningún lápiz.
 */
private fun presionMedia(presiones: DoubleArray?, n: Int): Double {
    if (presiones == null || presiones.isEmpty()) return 1.0
    var suma = 0.0
    var buenas = 0
    for (i in 0 until minOf(presiones.size, n)) {
        val p = presiones[i]
        if (p.isNaN()) continue
        suma += p.coerceIn(0.0, 1.0)
        buenas++
    }
    return if (buenas == 0) 1.0 else suma / buenas
}

/**
 * El valor de en medio de los primeros [cuantos], **sin ordenarlos todos**.
 *
 * Es la selección rápida de toda la vida: se reparte alrededor de un pivote y
 * se sigue solo por el lado en el que cae la mitad. Ordenar entero cuesta más y
 * fabricaría trabajo por cada trazo que se recalcula. Se le pasa una copia
 * porque reparte de sitio.
 */
private fun laDeEnMedio(de: DoubleArray, cuantos: Int): Double {
    var desde = 0
    var hasta = cuantos - 1
    val meta = cuantos / 2
    while (desde < hasta) {
        val pivote = de[(desde + hasta) / 2]
        var i = desde
        var j = hasta
        while (i <= j) {
            while (de[i] < pivote) i++
            while (de[j] > pivote) j--
            if (i <= j) {
                val t = de[i]; de[i] = de[j]; de[j] = t
                i++; j--
            }
        }
        if (meta <= j) hasta = j else if (meta >= i) desde = i else break
    }
    return de[meta]
}

/**
 * Por debajo de cuántas muestras no hay pulso que valga: es una raya, no un
 * trazo. Y los topes de la cuenta, **los mismos que tenía la versión de
 * pantalla** porque ya estaban afinados a ojo: a cuánto adelgaza lo rápido, a
 * cuántas veces la mediana se llega a ese mínimo, cuánta variación tiene que
 * traer la presión para hacerle caso, cuántas muestras se afilan en cada punta
 * y a cuánto llega la mismísima punta.
 */
private const val MINIMO_PARA_EL_PULSO = 6

/**
 * A cuánto adelgaza como mucho lo rápido, y lo flojo. Estaba en 0,55 —casi la
 * mitad— y el rotulador se veía como si la presión bailara: entre correr y
 * apretar poco, el trazo bajaba a un tercio. Un rotulador de verdad varía
 * mucho menos; el pulso se nota igual sin que la raya deje de ser una raya.
 */
private const val MINIMO_DEL_PULSO = 0.7

/** A cuántas veces la mediana se llega al mínimo: más rango, pulso más suave. */
private const val RANGO_DEL_PULSO = 2.5

/**
 * La ventana de tiempo, en segundos, en la que se mide la velocidad: sesenta
 * milésimas son tres o cuatro toques de una pantalla normal, que es lo que hace
 * falta para que las rachas de muestras con el mismo instante no cuenten.
 */
private const val VENTANA_DEL_RELOJ = 0.06
private const val VARIACION_QUE_CUENTA = 0.02
private const val PUNTAS_QUE_SE_AFILAN = 3
private const val AFILADO = 0.45

/**
 * El suelo del factor entero: correr a tope, apretando lo mínimo, en la
 * mismísima punta. Con entradas sanas nada baja de aquí por construcción; el
 * tope explícito es el cinturón que lo garantiza también con entradas rotas.
 */
private const val SUELO_DEL_FACTOR = AFILADO * MINIMO_DEL_PULSO * MINIMO_DEL_PULSO
