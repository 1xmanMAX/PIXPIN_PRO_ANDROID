package com.forge.pixpin.guardados

/**
 * La onda de una nota de voz: la fila de barritas que se ve en vez de una raya.
 *
 * ## Por qué una onda y no una barra de progreso
 *
 * Una nota de voz sin onda son todas iguales: doce rayas grises idénticas en las que hay
 * que entrar una por una para saber cuál era. Con la onda se distingue de un vistazo la que
 * empieza fuerte de la que tiene un silencio en medio, y —lo que más se usa— se puede
 * pinchar a la mitad sabiendo que ahí hay voz y no aire.
 *
 * ## La normalización es **relativa a esa nota**, no a 32767
 *
 * `getMaxAmplitude()` devuelve hasta 32767, pero una nota dictada bajito, con el móvil sobre
 * la mesa, no pasa de 3000. Dividir por el máximo teórico dibujaría esa nota como una **raya
 * plana** pegada al suelo: exactamente la información que se quería evitar. Se divide por el
 * pico más alto de la propia nota, así que la barra más alta siempre llega arriba y lo que
 * se ve es la **forma** —dónde hubo voz y dónde no—, que es lo único que dice algo. Es lo
 * mismo que hace Telegram, que guarda la onda ya normalizada en 5 bits por muestra.
 *
 * ## Y un suelo mínimo
 *
 * Una barra de altura 0 no se dibuja: el silencio dejaría huecos en la fila y la onda
 * parecería rota o a medio cargar. Con [SUELO_DE_BARRA] el silencio es una barra bajita,
 * que se lee como lo que es.
 *
 * ## Sin Android a propósito
 *
 * Aquí solo hay listas y números, así que el submuestreo —la parte que de verdad puede
 * salir mal: listas vacías, más barras que picos, divisiones entre cero— se comprueba
 * entera sin micrófono ni pantalla.
 */

/**
 * Cuántas barras se dibujan como mucho.
 *
 * Sale de las medidas reales de Telegram (`SeekBarWaveform`): barras de **2 dp cada 3 dp**
 * sobre una franja útil de 14 dp, dentro de una fila de 30 dp de alto. En el ancho que le
 * queda a la onda dentro de la burbuja —unos 150 dp— caben 150/3 = 50. Más barras no se
 * verían: serían más finas que el trazo mínimo y quedarían como una mancha.
 */
const val BARRAS_DE_LA_ONDA = 50

/** El alto útil de la franja, en dp. Los 14 dp de Telegram dentro de sus 30 dp de fila. */
const val ALTO_DE_LA_ONDA_DP = 14

/** Lo más bajita que puede quedar una barra, para que el silencio se vea y no falte. */
const val SUELO_DE_BARRA = 0.05f

/**
 * Cuántos picos se guardan por nota, como mucho.
 *
 * Los mensajes se escriben **una línea de JSON por mensaje**, y un pico cada 50 ms son
 * 1200 números por minuto: una nota de diez minutos engordaría su línea a decenas de
 * kilobytes para dibujar 50 barras. Con 256 sobra de largo para submuestrear a 50 y la
 * línea sigue siendo una línea. Ver [Picos], que va reduciendo sobre la marcha.
 */
const val PICOS_GUARDADOS = 256

/**
 * Los picos crudos convertidos en las [cuantas] alturas que se pintan, de 0 a 1.
 *
 * Un solo recorrido sirve para los dos casos que se dan de verdad:
 *
 * - **Menos picos que barras** (una nota de un segundo): se estira, repitiendo el mismo pico
 *   en varias barras. Fallar aquí —o devolver media onda— dejaría las notas cortas, que son
 *   la mayoría, con la fila a medias.
 * - **Muchos más picos que barras**: se promedia por tramos en vez de coger uno de cada n.
 *   Coger uno de cada n se salta justo los golpes de voz y da una onda más plana de lo que
 *   sonó.
 *
 * Con la lista vacía devuelve la lista vacía: una nota grabada antes de que esto existiera
 * no tiene picos, y quien dibuja se limita a no dibujar onda.
 */
fun aBarras(picos: List<Int>, cuantas: Int = BARRAS_DE_LA_ONDA): List<Float> {
    if (picos.isEmpty() || cuantas <= 0) return emptyList()
    // El pico más alto de **esta** nota: ver la nota de arriba sobre la normalización.
    // Nunca cero, que sería dividir entre cero en una nota de silencio absoluto.
    val maximo = (picos.maxOrNull() ?: 0).coerceAtLeast(1)
    val n = picos.size
    return List(cuantas) { i ->
        val desde = (i.toLong() * n / cuantas).toInt()
        // Al menos un pico por barra: con menos picos que barras, los dos extremos caerían
        // en el mismo sitio y el tramo saldría vacío.
        val hasta = (((i + 1).toLong() * n / cuantas).toInt()).coerceAtLeast(desde + 1)
            .coerceAtMost(n)
        var suma = 0L
        for (k in desde until hasta) suma += picos[k].coerceAtLeast(0)
        val medio = suma.toFloat() / (hasta - desde)
        (medio / maximo).coerceIn(SUELO_DE_BARRA, 1f)
    }
}

/**
 * El alto en píxeles —o en dp— de una barra de valor [valor] sobre una franja de [maximo].
 *
 * Aparte y puro porque es donde se cuelan los valores imposibles: un pico negativo o un
 * valor mayor que 1 dibujaría una barra fuera de la fila, encima del texto de al lado.
 */
fun altoDeBarra(valor: Float, maximo: Int = ALTO_DE_LA_ONDA_DP): Float =
    valor.coerceIn(SUELO_DE_BARRA, 1f) * maximo

/**
 * Junta los picos de dos en dos, quedándose con la mitad.
 *
 * Promediando, no descartando: descartar la mitad se come los golpes de voz y aplana la
 * onda. Si sobra uno al final se queda tal cual, que es mejor que perder el final de la nota.
 */
fun aMitad(picos: List<Int>): List<Int> {
    if (picos.size < 2) return picos.toList()
    val salida = ArrayList<Int>((picos.size + 1) / 2)
    var i = 0
    while (i < picos.size) {
        salida.add(
            if (i + 1 < picos.size) (picos[i] + picos[i + 1]) / 2 else picos[i]
        )
        i += 2
    }
    return salida
}

/**
 * El acumulador de picos mientras se graba, **acotado**.
 *
 * Quien graba llama a [anota] con lo que devuelve `Voz.pico(grabador)` desde el bucle que ya
 * tiene para el cronómetro; aquí no se monta ningún hilo. Cuando se llega a [tope] se junta
 * lo guardado de dos en dos y se pasa a mirar uno de cada dos picos: así una nota de treinta
 * segundos y una de diez minutos ocupan lo mismo y las dos conservan su forma entera, en vez
 * de guardar el principio y cortar el resto.
 */
class Picos(private val tope: Int = PICOS_GUARDADOS) {

    private val lista = ArrayList<Int>()
    /** De cuántos picos se anota uno. Se dobla cada vez que se reduce. */
    private var paso = 1
    private var vistos = 0

    /** Un pico del micrófono. Los negativos —que no deberían existir— se toman como cero. */
    fun anota(pico: Int) {
        vistos++
        if (vistos % paso != 0) return
        lista.add(pico.coerceAtLeast(0))
        if (lista.size >= tope) {
            val reducida = aMitad(lista)
            lista.clear()
            lista.addAll(reducida)
            paso *= 2
            vistos = 0
        }
    }

    /** Lo acumulado, para guardarlo en el mensaje. */
    fun lista(): List<Int> = lista.toList()
}
