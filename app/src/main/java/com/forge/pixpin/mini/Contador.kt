package com.forge.pixpin.mini

/**
 * Un contador: un número que sube y baja de uno en uno, o de lo que se diga.
 *
 * ## Por qué merece ser una mini-app
 *
 * Porque contar a mano es justo lo que se hace mal. Cajas descargadas, vueltas de una
 * pista, personas que entran, series de un ejercicio: son cosas que se cuentan mientras se
 * hace otra cosa, y ahí la cabeza pierde el número en cuanto alguien pregunta la hora. Un
 * botón grande y un número grande es toda la aplicación.
 *
 * ## El paso
 *
 * De uno casi siempre, pero se guarda **con el contador** y no en los ajustes: quien
 * cuenta cajas de doce quiere que ese contador suba de doce, y el de al lado, de uno. Un
 * ajuste global obligaría a cambiarlo cada vez que se pasa de una cuenta a la otra.
 */
data class Cuenta(
    val valor: Long = 0L,
    /** Cuánto sube o baja cada toque. Nunca cero: un paso de cero no cuenta nada. */
    val paso: Long = 1L
)

/** Leer, escribir y mover un contador. Puro: se comprueba sin dispositivo. */
object Contador {

    /** Más allá de esto no es una cuenta, es un desbordamiento esperando. */
    const val TOPE = 1_000_000_000L

    fun leer(documento: String): Cuenta {
        val valores = Cabecera.cuerpo(documento).lineSequence()
            .mapNotNull { linea ->
                val limpia = linea.trim().removePrefix("-").trim()
                val corte = limpia.indexOf(':')
                if (corte <= 0) return@mapNotNull null
                limpia.take(corte).trim().lowercase() to limpia.drop(corte + 1).trim()
            }
            .toMap()
        return Cuenta(
            valor = valores["valor"]?.toLongOrNull()?.coerceIn(-TOPE, TOPE) ?: 0L,
            // Un paso de cero o negativo dejaría un contador que no cuenta o que baja al
            // sumar. Se corrige al leer, no al escribir: el archivo puede venir tocado a
            // mano, y quien lo lee es quien tiene que aguantar lo que encuentre.
            paso = valores["paso"]?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        )
    }

    fun escribir(titulo: String, c: Cuenta): String =
        Cabecera.linea(titulo) + "- valor: ${c.valor}\n- paso: ${c.paso}"

    fun mas(c: Cuenta): Cuenta = c.copy(valor = (c.valor + c.paso).coerceIn(-TOPE, TOPE))

    fun menos(c: Cuenta): Cuenta = c.copy(valor = (c.valor - c.paso).coerceIn(-TOPE, TOPE))

    /** Reiniciar deja el paso como estaba: lo que se pone a cero es la cuenta. */
    fun reiniciar(c: Cuenta): Cuenta = c.copy(valor = 0L)

    fun conPaso(c: Cuenta, paso: Long): Cuenta = c.copy(paso = paso.coerceAtLeast(1L))
}

/**
 * El documento de una ruleta: su nombre y sus participantes, uno por línea.
 *
 * El sorteo en sí no vive aquí sino en [Ruleta], que es el mismo que usa el pin: una
 * ruleta que reparte distinto según desde dónde se abra no sería la misma ruleta.
 */
object RuletaDoc {

    fun leer(documento: String): List<String> = Ruleta.nombres(Cabecera.cuerpo(documento))

    fun escribir(titulo: String, nombres: List<String>): String =
        Cabecera.linea(titulo) + nombres.joinToString("\n") { enUnaLinea(it) }

    /**
     * Añade un nombre. Lo vacío no entra y el tope es el de la ruleta.
     *
     * Pasado ese tope los nombres ni se leen ni se distinguen al girar, así que aceptar el
     * cuarenta y uno sería aceptar algo que no se va a poder usar.
     */
    fun anadir(nombres: List<String>, texto: String): List<String> {
        val limpio = enUnaLinea(texto)
        if (limpio.isEmpty() || nombres.size >= Ruleta.MAXIMO) return nombres
        return nombres + limpio
    }

    fun quitar(nombres: List<String>, indice: Int): List<String> {
        if (indice !in nombres.indices) return nombres
        return nombres.filterIndexed { i, _ -> i != indice }
    }
}
