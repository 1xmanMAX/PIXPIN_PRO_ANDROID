package com.forge.pixpin.clipboard

/** Mini-aplicaciones que se abren pineando una palabra. */
enum class MiniApp { TIMER, CHECKLIST, COUNTER, LEDGER, BOARD }

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
        "pizarra" to MiniApp.BOARD
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
