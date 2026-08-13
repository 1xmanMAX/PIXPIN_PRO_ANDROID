package com.forge.pixpin.clipboard

/** Mini-aplicaciones que se abren pineando una palabra. */
enum class MiniApp {
    TIMER, STOPWATCH, CHECKLIST, COUNTER, LEDGER, BOARD, RULETA,

    /** El lienzo infinito. */
    DRAW,

    /**
     * **La hoja**: el mismo lienzo pero acotado.
     *
     * Nace con un marco puesto, así que tiene bordes desde el primer momento y
     * el pin enseña exactamente eso. Es la diferencia entre «ponte a pensar sin
     * límites» y «apunta esto en un papel»: dos intenciones distintas que
     * merecen dos palabras distintas, aunque por dentro sean el mismo motor.
     */
    SHEET
}

/**
 * Palabras que, pineadas **solas**, abren una mini-aplicación en vez de un pin
 * de texto.
 *
 * La regla de que tenga que ir sola no es un capricho: cada palabra mágica es
 * una palabra que dejas de poder pinear como texto normal. Copiar «time» de un
 * documento para pegarlo en otro sitio tiene que seguir dando un pin de texto;
 * solo cuando lo único copiado es esa palabra se entiende que la intención era
 * abrir la herramienta.
 *
 * Por eso también son pocas y poco frecuentes en aislado. «nota» o «lista» a
 * secas se descartaron: se copian solas demasiado a menudo.
 */
object MagicWord {

    /**
     * Las de fábrica.
     *
     * Se quedan públicas porque los ajustes las enseñan y hay que poder volver a
     * ellas: «restablecer» tiene que dar exactamente esto, no una copia escrita
     * a mano en otro sitio que se quede vieja a la primera.
     */
    val POR_DEFECTO: Map<String, MiniApp> = mapOf(
        "time" to MiniApp.TIMER,
        "timer" to MiniApp.TIMER,
        "pomodoro" to MiniApp.TIMER,
        "temporizador" to MiniApp.TIMER,

        "crono" to MiniApp.STOPWATCH,
        "cronometro" to MiniApp.STOPWATCH,
        "cronómetro" to MiniApp.STOPWATCH,
        "stopwatch" to MiniApp.STOPWATCH,

        "todo" to MiniApp.CHECKLIST,
        "checklist" to MiniApp.CHECKLIST,
        "compras" to MiniApp.CHECKLIST,
        "tareas" to MiniApp.CHECKLIST,

        "count" to MiniApp.COUNTER,
        "contador" to MiniApp.COUNTER,

        "gastos" to MiniApp.LEDGER,
        "cuentas" to MiniApp.LEDGER,
        "money" to MiniApp.LEDGER,

        "board" to MiniApp.BOARD,
        "pizarra" to MiniApp.BOARD,

        // El sorteo. «random» y «azar» se quedan fuera por lo de siempre: se
        // copian solas demasiado a menudo dentro de un texto cualquiera.
        "ruleta" to MiniApp.RULETA,
        "roulette" to MiniApp.RULETA,
        "choose" to MiniApp.RULETA,
        "sorteo" to MiniApp.RULETA,
        "sortear" to MiniApp.RULETA,
        "elegir" to MiniApp.RULETA,

        // «plano» se quedó fuera a propósito: se copia solo demasiado a menudo
        // en un texto de obra, y perderlo como pin de texto costaría más de lo
        // que aporta como atajo.

        // El lienzo infinito. «draw» y «dibujo» se quedaron fuera por lo mismo
        // que «plano»: se copian solos demasiado a menudo y perderlos como pin
        // de texto costaría más de lo que aporta el atajo.
        "canvas" to MiniApp.DRAW,
        "lienzo" to MiniApp.DRAW,
        "excalidraw" to MiniApp.DRAW,

        // La hoja: el mismo lienzo, pero acotado y con bordes desde el minuto
        // uno. «papel», «página» y «nota» se quedan fuera por lo mismo que
        // «plano»: se copian solas demasiado a menudo en un texto cualquiera, y
        // perderlas como pin de texto costaría más de lo que aporta el atajo.
        "hoja" to MiniApp.SHEET,
        "sheet" to MiniApp.SHEET
    )

    /**
     * La mini-aplicación que abre [text], o null si es texto corriente.
     *
     * Mayúsculas, minúsculas o mezcla dan igual; espacios alrededor también.
     * Cualquier otra cosa acompañando la descarta.
     *
     * [palabras] son las que manda el usuario. Se pasan y no se leen de aquí
     * dentro porque leerlas exigiría un disco y un contexto, y entonces esto
     * dejaría de ser una función que se puede comprobar sin móvil.
     */
    fun detect(text: String?, palabras: Map<String, MiniApp> = POR_DEFECTO): MiniApp? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || palabras.isEmpty()) return null
        if (t.length > palabras.keys.maxOf { it.length }) return null
        // Sin espacios por dentro: «el time es oro» es una frase, no una orden.
        if (t.any { it.isWhitespace() }) return null
        return palabras[t.lowercase()]
    }

    /**
     * Una palabra tal como se guarda, o null si no puede serlo.
     *
     * Se cae lo que **nunca podría dispararse**: lo vacío, lo que lleva espacios
     * —`detect` descarta cualquier cosa con un hueco— y lo larguísimo. Vale más
     * no dejarla escribir que guardarla y que no haga nada nunca.
     */
    fun normalizar(palabra: String): String? {
        val limpia = palabra.trim().lowercase()
        if (limpia.isEmpty() || limpia.length > MAXIMO) return null
        if (limpia.any { it.isWhitespace() }) return null
        return limpia
    }

    /**
     * Lee las palabras guardadas. **null es «no lo he tocado»** y entonces
     * mandan las de fábrica; una cadena vacía es «no quiero ninguna», que es
     * una respuesta distinta y se respeta.
     *
     * Formato: una por línea, `palabra=APP`. Lo que no se entienda se salta en
     * silencio —una herramienta que ya no existe, una línea a medias— porque
     * tirar los ajustes enteros por una línea rota sería mucho peor. Si la
     * misma palabra sale dos veces manda la primera, que es lo que se lee al
     * mirar la lista de arriba abajo.
     */
    fun leer(guardadas: String?): Map<String, MiniApp> {
        if (guardadas == null) return POR_DEFECTO
        val salida = LinkedHashMap<String, MiniApp>()
        guardadas.lineSequence().forEach { linea ->
            val corte = linea.indexOf('=')
            if (corte <= 0) return@forEach
            val palabra = normalizar(linea.substring(0, corte)) ?: return@forEach
            val app = runCatching {
                MiniApp.valueOf(linea.substring(corte + 1).trim().uppercase())
            }.getOrNull() ?: return@forEach
            salida.putIfAbsent(palabra, app)
        }
        return salida
    }

    /** Cómo se guardan. Ver [leer]. */
    fun escribir(palabras: Map<String, MiniApp>): String =
        palabras.entries.joinToString("\n") { "${it.key}=${it.value.name}" }

    /** Las palabras de una herramienta, en orden. */
    fun deLaApp(palabras: Map<String, MiniApp>, app: MiniApp): List<String> =
        palabras.filterValues { it == app }.keys.toList()

    /**
     * Cambia de golpe las palabras de una herramienta, dejando las demás.
     *
     * Una palabra solo puede abrir una cosa —es una clave del mapa—, así que
     * dársela a esta se la quita a la que la tuviera. Es lo que uno espera al
     * moverla de sitio, y lo contrario —que se quedara en las dos y ganara una
     * al azar— sería un misterio.
     */
    fun conLasDe(
        palabras: Map<String, MiniApp>,
        app: MiniApp,
        nuevas: List<String>
    ): Map<String, MiniApp> {
        val limpias = nuevas.mapNotNull { normalizar(it) }.distinct()
        val salida = LinkedHashMap<String, MiniApp>()
        palabras.forEach { (palabra, suya) ->
            if (suya != app && palabra !in limpias) salida[palabra] = suya
        }
        limpias.forEach { salida[it] = app }
        return salida
    }

    /** Lo más larga que puede ser una palabra mágica. */
    private const val MAXIMO = 24
}
