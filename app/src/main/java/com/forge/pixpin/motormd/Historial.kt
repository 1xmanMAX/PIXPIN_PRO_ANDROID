package com.forge.pixpin.motormd

/**
 * Deshacer y rehacer, como su `RichEditorHistory`.
 *
 * En su editor los dos botones viven arriba a la derecha, en una píldora propia,
 * y están siempre. Aquí hacen más falta todavía: la barra de formato puede
 * cambiar media nota de un toque —quitar formato sobre todo el texto, envolver
 * en un bloque de código— y sin vuelta atrás eso da miedo apretarlo. Un botón
 * que asusta es un botón que no se usa.
 *
 * ## Por qué no vale el deshacer del teclado
 *
 * El campo de texto trae el suyo, pero solo conoce lo que se **teclea**. Lo que
 * escribe un botón de la barra le llega como un cambio de programa y no siempre
 * entra en su pila; y lo que sí entra, entra letra a letra. Con este, cada
 * toque de la barra es exactamente un paso atrás.
 *
 * ## Agrupar
 *
 * Escribir «hola» son cuatro cambios y **un** paso de deshacer, no cuatro. Se
 * juntan los cambios que añaden o quitan un solo carácter seguido del anterior,
 * y se corta al llegar a un espacio o a un salto de línea. Es el corte por
 * palabras que hace cualquier editor, y el que espera quien aprieta el botón.
 */
class Historial(private val maximo: Int = 200) {

    private val pasos = ArrayDeque<String>()
    private val rehechos = ArrayDeque<String>()

    /** Lo último anotado, para saber contra qué se compara el cambio siguiente. */
    private var ultimo: String? = null

    /** Si el cambio anterior fue una letra suelta, se puede seguir agrupando. */
    private var agrupando = false

    val puedeDeshacer: Boolean get() = pasos.isNotEmpty()
    val puedeRehacer: Boolean get() = rehechos.isNotEmpty()

    /** Arranca el historial en [texto] sin anotar ningún paso. */
    fun empezar(texto: String) {
        pasos.clear()
        rehechos.clear()
        ultimo = texto
        agrupando = false
    }

    /**
     * Anota que el texto pasó a ser [texto].
     *
     * Un cambio nuevo tira lo rehecho: es lo que hace todo el mundo, y guardar
     * una rama que ya no se puede alcanzar solo sirve para confundir.
     */
    fun anota(texto: String) {
        val antes = ultimo
        if (antes == texto) return
        ultimo = texto
        rehechos.clear()

        if (antes == null) return

        val seguir = agrupando && esUnaLetraMas(antes, texto)
        if (!seguir) {
            pasos.addLast(antes)
            while (pasos.size > maximo) pasos.removeFirst()
        }
        agrupando = esUnaLetraMas(antes, texto)
    }

    /** Devuelve el texto de antes, o null si no hay a dónde volver. */
    fun deshacer(actual: String): String? {
        val paso = pasos.removeLastOrNull() ?: return null
        rehechos.addLast(actual)
        ultimo = paso
        agrupando = false
        return paso
    }

    fun rehacer(actual: String): String? {
        val paso = rehechos.removeLastOrNull() ?: return null
        pasos.addLast(actual)
        ultimo = paso
        agrupando = false
        return paso
    }

    /**
     * ¿Es [despues] igual que [antes] con **un carácter de más o de menos** que
     * no sea un espacio?
     *
     * El espacio corta el grupo a propósito: es donde acaba una palabra, y por
     * tanto donde tiene sentido que se pare el deshacer.
     */
    private fun esUnaLetraMas(antes: String, despues: String): Boolean {
        val largo: String
        val corto: String
        if (despues.length == antes.length + 1) {
            largo = despues; corto = antes
        } else if (antes.length == despues.length + 1) {
            largo = antes; corto = despues
        } else {
            return false
        }

        var i = 0
        while (i < corto.length && corto[i] == largo[i]) i++
        // El carácter que sobra tiene que ser el único distinto: el resto del
        // texto debe coincidir desplazado uno.
        if (largo.substring(i + 1) != corto.substring(i)) return false
        return !largo[i].isWhitespace()
    }
}
