package com.forge.pixpin.sincro

/**
 * **La seña de un mensaje: su número y la letra del aparato donde nació.**
 *
 * `47a` es «el cuarenta y siete del teléfono»; `12t`, «el doce de la tableta». Juntos dan un
 * nombre **único en todo el conjunto de aparatos sin que ninguno tenga que preguntarle nada a
 * los demás**, y eso es lo que permite sincronizar sin servidor.
 *
 * El problema difícil de sincronizar sin un servidor que reparta nombres es justo ese: si dos
 * aparatos crean algo a la vez, ¿quién es el 47? Con una letra por aparato el problema
 * desaparece — el teléfono solo reparte números con `a` y la tableta solo con `t`, así que
 * **no pueden chocar**— y encima el nombre se puede decir en voz alta y buscar en el chat, que
 * es la gracia del número que ya lleva cada mensaje. Ver [com.forge.pixpin.guardados.Mensaje.numero].
 *
 * **La seña no cambia al viajar.** Un `47a` que llega a la tableta sigue siendo `47a` allí: es
 * lo que permite reconocer después que son el mismo mensaje. Si cambiara, cada sincronización
 * duplicaría todo.
 */
object Sena {

    /** Las letras que se reparten, en orden. Ver [libre]. */
    const val LETRAS = "abcdefghijklmnopqrstuvwxyz"

    /**
     * La seña de un mensaje: su número pegado a la letra del aparato.
     *
     * El número cero es un mensaje de antes de que esto existiera y **no tiene seña**: no se
     * puede sincronizar lo que no se sabe nombrar. Devuelve `null` para que quien lo llame
     * tenga que decidir qué hace con él en vez de inventarse un `0a` que chocaría con todos
     * los demás ceros de todos los aparatos.
     */
    fun de(numero: Int, letra: Char): String? =
        if (numero <= 0) null else "$numero$letra"

    /** El número de una seña, o `null` si no lo es. */
    fun numeroDe(sena: String): Int? {
        val n = sena.dropLast(1).toIntOrNull() ?: return null
        return if (valida(sena)) n else null
    }

    /** La letra de una seña, o `null` si no lo es. */
    fun letraDe(sena: String): Char? = if (valida(sena)) sena.last() else null

    /** Si esto tiene forma de seña: dígitos y una letra al final, y el número mayor que cero. */
    fun valida(sena: String): Boolean {
        if (sena.length < 2) return false
        if (sena.last() !in LETRAS) return false
        val n = sena.dropLast(1).toIntOrNull() ?: return false
        return n > 0
    }

    /**
     * **Una letra libre para un aparato que se une al grupo.**
     *
     * Se reparte sola —lo decidió el usuario el 9-sep-2026— y no la elige nadie: elegirla a
     * mano era más bonito («l» de laptop) pero se agota, choca, y obligaría a resolver ese
     * choque **justo al emparejar**, que es el peor momento para leer un aviso. El aparato
     * lleva además un **nombre** que sí pone el usuario, y es el que se ve al sincronizar.
     *
     * Devuelve `null` si el grupo ya tiene veintiséis aparatos, que es más de los que nadie
     * va a emparejar; el que llame decide qué decir entonces.
     */
    fun libre(ocupadas: Set<Char>): Char? = LETRAS.firstOrNull { it !in ocupadas }
}
