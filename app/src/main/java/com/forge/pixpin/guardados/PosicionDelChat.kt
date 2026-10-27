package com.forge.pixpin.guardados

/**
 * **Dónde se abre cada chat y cuándo se queda abajo** (27-oct-2026, pedido del usuario: «cuando entro
 * al asistente me aparece arriba, no en el último mensaje como un chat normal. Configura todos los
 * chats para que siempre aparezcan en el último mensaje o donde lo dejaste, y un botón en la
 * esquinita como en Telegram para ir directamente abajo»).
 *
 * Lo que fallaba: la lista se colocaba una sola vez en la **fila** del último mensaje, que es poner
 * el **principio** de ese mensaje arriba de la pantalla. Con las respuestas largas del asistente eso
 * es ver su primera línea y nada más; y luego, al crecer el pie (la tarjeta del asistente, las
 * recetas, el teclado) o la respuesta mientras se escribía, nadie volvía a bajar. Ahora:
 *
 * - **Al abrir**: si al salir se había dejado subido, se vuelve a ese mensaje y a ese punto de él
 *   ([alAbrir]); si no, al final **del todo** (el borde de abajo del último mensaje).
 * - **Mientras está abierto**: si se estaba abajo y llega algo, crece lo último o cambia el pie,
 *   se sigue abajo; si se subió a leer, nada lo mueve ([queHacer]).
 * - **Lo que no se ha visto**: lo que hay por debajo del último mensaje que llegó a verse, para la
 *   chapa del botón de bajar, como Telegram ([sinLeer]).
 *
 * Sin Android: lo prueba `PosicionDelChatTest`. Se guarda en las preferencias del teléfono por chat
 * (no viaja: dónde se quedó cada uno es de cada aparato).
 */
object PosicionDelChat {

    /**
     * Lo que se apunta de un chat al salir. [arriba]: el primer mensaje que se veía, o nada si se
     * estaba abajo; [desplazamiento]: cuánto de él quedaba por encima del borde, en píxeles;
     * [leidoHasta]: el último mensaje que llegó a verse.
     */
    data class Guardada(val arriba: String?, val desplazamiento: Int, val leidoHasta: String?)

    /** Adónde ir al abrir. */
    sealed interface AlAbrir {
        /** Al final del todo. */
        data object Abajo : AlAbrir

        /** A [id], con [desplazamiento] píxeles de él por encima del borde. */
        data class En(val id: String, val desplazamiento: Int) : AlAbrir
    }

    /**
     * **Adónde se abre** con lo guardado y los mensajes que hay ahora ([ids], en el orden del chat).
     * Abajo si no hay nada guardado, si se dejó abajo o si aquel mensaje ya no está (se borró, o es
     * de antes de una sincronización que lo quitó): mejor el final que un sitio cualquiera.
     */
    fun alAbrir(guardada: Guardada?, ids: List<String>): AlAbrir {
        val id = guardada?.arriba ?: return AlAbrir.Abajo
        if (ids.isEmpty() || id !in ids) return AlAbrir.Abajo
        // Si el que estaba arriba es ya el último, es lo mismo que estar abajo.
        if (id == ids.last()) return AlAbrir.Abajo
        return AlAbrir.En(id, guardada.desplazamiento.coerceAtLeast(0))
    }

    /**
     * **Lo que no se ha visto**: cuántos mensajes hay detrás de [leidoHasta]. Si no se sabe o ya no
     * está, ninguno (la chapa no puede inventarse un número).
     */
    fun sinLeer(ids: List<String>, leidoHasta: String?): Int {
        if (leidoHasta == null) return 0
        val i = ids.lastIndexOf(leidoHasta)
        return if (i < 0) 0 else ids.size - 1 - i
    }

    /**
     * El más avanzado de dos «leído hasta»: el que va más abajo en [indice] (la posición de cada id).
     * Uno que ya no está no cuenta.
     */
    fun masAvanzado(indice: Map<String, Int>, a: String?, b: String?): String? {
        val ia = a?.let { indice[it] } ?: -1
        val ib = b?.let { indice[it] } ?: -1
        return when {
            ia < 0 && ib < 0 -> null
            ib > ia -> b
            else -> a
        }
    }

    /**
     * **Una foto de cómo está la lista**: cuántas filas tiene, la clave de la última, lo que mide la
     * última si se ve (-1 si no), lo que mide el pie flotante y si se está abajo del todo.
     */
    data class Foto(val filas: Int, val ultima: Any?, val altoDeLaUltima: Int, val pie: Int, val abajo: Boolean)

    /** Qué hacer ante el cambio de [antes] a [ahora]. */
    enum class Accion {
        /** Bajar al final: se estaba abajo y ha llegado algo, ha crecido lo último o el pie. */
        BAJAR,

        /** Lo ha movido quien mira (o un salto): apuntar si ahora está abajo o no. */
        APUNTAR,

        /** Ha cambiado lo que se ve (buscar, otra sección): ni bajar ni cambiar lo apuntado. */
        NADA
    }

    /**
     * **Si hay que seguir abajo** (lo de «pegado al final» de Telegram). [pegado]: si se estaba
     * abajo antes de este cambio. Lo que cuenta como «ha llegado algo» es que crezca la lista **por
     * el final** (otra última fila y más filas), que la última fila mida más (la respuesta del
     * asistente mientras se escribe) o que cambie el pie (el teclado, la tarjeta del asistente).
     * Que la última entre o salga de la pantalla (-1 ↔ su alto) es desplazarse, no crecer. Con la
     * lista cambiada de otra forma (filtrar, buscar, «ver en contexto») no se baja: se pelearía con
     * el salto, que es lo que pasaba cuando se bajaba a cada cambio de tamaño.
     */
    fun queHacer(antes: Foto?, ahora: Foto, pegado: Boolean): Accion {
        if (antes == null) return Accion.APUNTAR
        val anadido = ahora.ultima != antes.ultima && ahora.filas > antes.filas
        val crecio = ahora.ultima == antes.ultima && ahora.filas == antes.filas &&
            antes.altoDeLaUltima >= 0 && ahora.altoDeLaUltima > antes.altoDeLaUltima
        val pie = ahora.pie != antes.pie
        if (anadido || crecio || pie) return if (pegado && !ahora.abajo) Accion.BAJAR else Accion.NADA
        val otraLista = ahora.filas != antes.filas || ahora.ultima != antes.ultima
        return if (otraLista) Accion.NADA else Accion.APUNTAR
    }

    // ------------------------------------------------------------------ guardar

    /** `arriba \t desplazamiento \t leidoHasta`, con los vacíos como cadena vacía. */
    fun aTexto(g: Guardada): String = listOf(g.arriba.orEmpty(), g.desplazamiento.toString(), g.leidoHasta.orEmpty()).joinToString("\t")

    fun deTexto(texto: String?): Guardada? {
        val p = texto?.split('\t') ?: return null
        if (p.size != 3) return null
        val desplazamiento = p[1].toIntOrNull() ?: return null
        return Guardada(p[0].ifEmpty { null }, desplazamiento, p[2].ifEmpty { null })
    }
}
