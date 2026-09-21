package com.forge.pixpin.motor

/**
 * **Marcadores con emoticono, los mismos en todas partes** (21-sep-2026).
 *
 * Los pidió el usuario primero para leer un Word o un libro ([Lectura.Marcador]: una fracción del
 * documento) y después **para el lienzo 2D y para el lector de PDF**, «que todos tengan la misma
 * interfaz». En un documento basta con la altura; en un lienzo no: uno marca **un sitio**, que
 * está en algún punto de un plano infinito, y además quiere poder moverlo de ahí. Así que una
 * marca es un punto:
 *
 * - **Lienzo 2D**: [x] e [y] son coordenadas de la escena, las mismas en las que vive lo dibujado.
 *   Se arrastra por el lienzo y se queda donde se suelte.
 * - **Lector de PDF**: [x] es la fracción a lo ancho de la hoja y [y] es **la página más la
 *   fracción a lo alto** (2,5 = la mitad de la tercera hoja). Así el orden del riel es el orden
 *   del documento, que es el que uno espera al pasar el dedo.
 *
 * El riel lateral es [com.forge.pixpin.ui.RielDeMarcas], común a los tres sitios. Aquí solo van
 * las cuentas, sin Android, para poder comprobarlas.
 */
data class Marca(val id: Long, val emoji: String, val x: Double, val y: Double)

object Marcas {
    /** Cuántas caben. Las de más se van por abajo, como en [Lectura]. */
    const val MAXIMO = 24

    /** Los mismos emoticonos que en los lectores: es la misma función. */
    val EMOJIS = Lectura.EMOJIS

    /**
     * **En el orden en que se leen**: de arriba abajo y, a igualdad, de izquierda a derecha. Es el
     * orden del riel, y sale de dónde está cada marca y no de cuándo se puso: mover una marca
     * **es** reordenarlas, y no hay dos sitios que puedan contradecirse.
     */
    fun enOrden(lista: List<Marca>): List<Marca> = lista.sortedWith(compareBy({ it.y }, { it.x }))

    /** Una más. Si ya no caben, se va la más vieja: lo que se acaba de poner no se pierde nunca. */
    fun con(lista: List<Marca>, x: Double, y: Double, emoji: String, ahora: Long): List<Marca> {
        val nueva = Marca(idLibre(lista, ahora), emoji, x, y)
        val todas = lista + nueva
        return enOrden(if (todas.size <= MAXIMO) todas else todas.sortedBy { it.id }.takeLast(MAXIMO))
    }

    /** Poner dos en el mismo milisegundo es raro, pero dos marcas con el mismo id serían una. */
    private fun idLibre(lista: List<Marca>, ahora: Long): Long {
        var id = ahora
        while (lista.any { it.id == id }) id++
        return id
    }

    /** Movida a otro sitio. La lista vuelve ordenada, que es lo que cambia el riel. */
    fun movida(lista: List<Marca>, id: Long, x: Double, y: Double): List<Marca> =
        enOrden(lista.map { if (it.id == id) it.copy(x = x, y = y) else it })

    fun sin(lista: List<Marca>, id: Long): List<Marca> = lista.filterNot { it.id == id }

    /** Guardadas en una línea, como las de [Lectura]: `id:x:y:emoji|…`. */
    fun aTexto(lista: List<Marca>): String =
        enOrden(lista).joinToString("|") { "${it.id}:${num(it.x)}:${num(it.y)}:${it.emoji}" }

    fun deTexto(texto: String?): List<Marca> = enOrden(
        texto.orEmpty().split('|').mapNotNull { trozo ->
            val p = trozo.split(':')
            if (p.size < 4) return@mapNotNull null
            val id = p[0].toLongOrNull() ?: return@mapNotNull null
            val x = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val y = p[2].toDoubleOrNull() ?: return@mapNotNull null
            // El emoticono puede llevar dos puntos dentro; lo que queda de la línea es suyo.
            val emoji = p.drop(3).joinToString(":").ifBlank { EMOJIS[0] }
            Marca(id, emoji, x, y)
        }
    )

    private fun num(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == Math.rint(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    /**
     * **Adónde hay que llevar la vista para que la marca quede arriba**, en las unidades de la
     * escena: lo que pidió el usuario —«que me lleve directo a ese punto estando el emoticono en
     * la parte superior»—. Devuelve el desplazamiento del [Viewport] con la marca a [margen]
     * píxeles del borde de arriba y centrada a lo ancho.
     */
    fun vistaConLaMarcaArriba(m: Marca, zoom: Double, ancho: Double, margen: Double = 0.0): Viewport {
        val z = zoom.coerceAtLeast(1e-6)
        return Viewport(scrollX = ancho / (2 * z) - m.x, scrollY = margen / z - m.y, zoom = z)
    }

    /** De qué página es una marca del lector de PDF, y a qué altura de ella. Ver [Marca]. */
    fun paginaDe(m: Marca): Int = kotlin.math.floor(m.y).toInt().coerceAtLeast(0)

    fun altoEnLaPagina(m: Marca): Double = (m.y - paginaDe(m)).coerceIn(0.0, 1.0)

    /** Cómo se guarda un sitio del lector de PDF: la página y en qué parte de ella cae. */
    fun enLaPagina(pagina: Int, fraccionAlto: Double, fraccionAncho: Double = 0.5): Pair<Double, Double> =
        fraccionAncho.coerceIn(0.0, 1.0) to (pagina.coerceAtLeast(0) + fraccionAlto.coerceIn(0.0, 1.0))
}
