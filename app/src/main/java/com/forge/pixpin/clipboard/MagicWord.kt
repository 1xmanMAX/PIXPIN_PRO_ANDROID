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

    private val WORDS: Map<String, MiniApp> = mapOf(
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
     */
    fun detect(text: String?): MiniApp? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.length > LONGEST) return null
        // Sin espacios por dentro: «el time es oro» es una frase, no una orden.
        if (t.any { it.isWhitespace() }) return null
        return WORDS[t.lowercase()]
    }

    private val LONGEST = WORDS.keys.maxOf { it.length }
}
