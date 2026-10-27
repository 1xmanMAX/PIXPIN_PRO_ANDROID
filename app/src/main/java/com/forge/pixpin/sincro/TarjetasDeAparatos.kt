package com.forge.pixpin.sincro

/**
 * **Tus aparatos como tarjetas cuadradas, de dos en dos** (27-oct-2026, pedido por el usuario).
 *
 * La pantalla de Sincronizar es vertical y sobra sitio: cada aparato del grupo va en una tarjeta
 * cuadrada con su nombre, **un punto** y su botón de sincronizar. Sin textos de estado
 * («Disponible», «sincronizado ahora mismo»): el punto lo dice. Aquí está lo que decide el punto y
 * el orden, sin Android, para probarlo (`TarjetasDeAparatosTest`); la pantalla solo lo pinta.
 */
object TarjetasDeAparatos {

    /** Lo que dice el punto de una tarjeta. El color lo pone la pantalla. */
    enum class Punto {
        /** Contesta en la Wi-Fi: se puede sincronizar ya. Verde. */
        CERCA,
        /** Sincronizando ahora con él. El color de la app. */
        SINCRONIZANDO,
        /** La última vez que se le dio a su botón no se pudo. Rojo, el de la marca de fallo. */
        FALLO,
        /** No se le ve. Gris. */
        LEJOS
    }

    /**
     * **Lo que está pasando gana a lo que pasó**: sincronizando, luego el fallo (hasta que se vuelva
     * a probar), y si no, si se le ve o no.
     */
    fun punto(cerca: Boolean, sincronizando: Boolean, fallo: Boolean): Punto = when {
        sincronizando -> Punto.SINCRONIZANDO
        fallo -> Punto.FALLO
        cerca -> Punto.CERCA
        else -> Punto.LEJOS
    }

    /** Los que se ven, primero; luego por nombre, sin mirar mayúsculas. */
    fun <T> ordenar(lista: List<T>, cerca: (T) -> Boolean, nombre: (T) -> String): List<T> =
        lista.sortedWith(compareByDescending<T> { cerca(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { nombre(it) })

    /** En filas de [columnas]; la última puede quedar a medias (la pantalla rellena el hueco). */
    fun <T> enFilas(lista: List<T>, columnas: Int = 2): List<List<T>> = lista.chunked(columnas.coerceAtLeast(1))
}
