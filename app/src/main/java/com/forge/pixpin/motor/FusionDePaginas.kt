package com.forge.pixpin.motor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **Varias páginas de un PDF en un mismo lienzo.**
 *
 * Lo pidió el usuario el 13-sep-2026: «fusionar páginas (por ejemplo la 4, la 5 y la 6) en un
 * mismo lienzo, separadas a una distancia razonable». Es lo que se hace en papel cuando un
 * detalle se entiende mirando dos planos a la vez: se ponen uno al lado del otro en la mesa y se
 * dibuja por encima de los dos.
 *
 * Aquí van solo **las cuentas**: dónde cae cada página y a qué resolución se pinta. El trabajo
 * con el PDF y con el almacén de escenas está en `guardados.FusionarPaginas`, que es lo que sí
 * necesita Android.
 *
 * Dos decisiones que se ven en el resultado:
 *
 * - **En fila o en columna, según cómo sean las páginas.** Tres A4 verticales en columna son una
 *   tira de tres mil unidades de alto por mil de ancho: para mirar dos a la vez hay que alejarse
 *   hasta que no se lea nada. Puestas en fila, el conjunto queda casi cuadrado, que es la forma
 *   que aprovecha una pantalla. Con páginas apaisadas pasa lo contrario, así que se mira lo que
 *   miden y se elige. Ver [enFila].
 * - **Lo que se ve manda sobre lo que pesa.** Cada página se pinta a mapa de bits, así que entre
 *   todas hay un presupuesto de píxeles ([PIXELES_EN_TOTAL]): con dos páginas cada una sale a
 *   buena resolución y con doce, más justas. Sin tope, fusionar seis planos grandes se lleva la
 *   memoria del teléfono por delante.
 */
object FusionDePaginas {

    /** Más de esto no se fusiona: son mapas de bits, y a partir de aquí no cabrían. */
    const val TOPE_DE_PAGINAS = 12

    /** Los píxeles de todas las páginas juntas: 24 millones son ~96 MB mientras se montan. */
    const val PIXELES_EN_TOTAL = 24_000_000.0

    /** Y ninguna página más ancha que esto, por si se fusionan dos: no hace falta más. */
    const val ANCHO_MAXIMO = 2400

    /** Ni más estrecha, aunque sean doce: por debajo no se lee. */
    const val ANCHO_MINIMO = 700

    /**
     * La separación entre páginas, en tanto por uno del lado más largo.
     *
     * Empezó en 0,04 y el usuario las quiso **más separadas** (14-sep-2026): con dos planos casi
     * pegados no se ve dónde acaba uno, y al dibujar encima un trazo cruza de página sin querer.
     */
    const val SEPARACION = 0.14

    /** Una página colocada: su sitio y lo que mide, en unidades del lienzo. */
    data class Sitio(val x: Double, val y: Double, val ancho: Double, val alto: Double)

    /**
     * **En fila** (una al lado de otra) o en columna.
     *
     * La regla es quedarse con el conjunto más cuadrado, que es el que mejor se pasea: páginas
     * verticales van en fila y apaisadas en columna. Se decide con la página media, no con la
     * primera, para que una portada apaisada entre planos verticales no vuelque a las demás.
     */
    fun enFila(medidas: List<Pair<Double, Double>>): Boolean {
        if (medidas.isEmpty()) return true
        val altura = medidas.map { it.second }.sorted()[medidas.size / 2]
        val anchura = medidas.map { it.first }.sorted()[medidas.size / 2]
        return altura >= anchura
    }

    /** La separación que se deja entre páginas, en unidades del lienzo. */
    fun separacion(tamanos: List<Pair<Double, Double>>): Double {
        val lado = tamanos.maxOfOrNull { max(it.first, it.second) } ?: return 0.0
        return lado * SEPARACION
    }

    /**
     * Dónde cae cada página, en el orden en que llegan.
     *
     * En fila se alinean **por arriba**, y en columna por la izquierda: las páginas de un
     * documento suelen medir lo mismo, y cuando una se sale de la norma queda claro que es ella
     * la distinta y no que se haya torcido el montaje.
     */
    fun sitios(tamanos: List<Pair<Double, Double>>, enFila: Boolean): List<Sitio> {
        val hueco = separacion(tamanos)
        var x = 0.0
        var y = 0.0
        return tamanos.map { (an, al) ->
            val s = Sitio(x, y, an, al)
            if (enFila) x += an + hueco else y += al + hueco
            s
        }
    }

    /**
     * **A cuántos píxeles de ancho se pinta cada página**, repartiendo [PIXELES_EN_TOTAL] entre
     * las que haya. [medidas] son los tamaños del papel (los puntos del PDF), y lo que se
     * devuelve es un ancho en píxeles por página, proporcional al suyo: una página del doble de
     * ancha sale con el doble de píxeles, o se vería con la mitad de detalle que su vecina.
     */
    fun anchos(medidas: List<Pair<Double, Double>>): List<Int> {
        if (medidas.isEmpty()) return emptyList()
        // Con «k píxeles por punto», los píxeles de todas son k² · Σ(ancho · alto).
        val area = medidas.sumOf { max(it.first, 1.0) * max(it.second, 1.0) }
        var k = if (area > 0) sqrt(PIXELES_EN_TOTAL / area) else 1.0
        // **Los topes se ponen a la escala, no a cada página.** Recortando el ancho de cada una
        // por separado, una página del doble de grande que su vecina salía con el mismo ancho de
        // píxeles, o sea con la mitad de detalle: dos planos juntos, uno nítido y otro borroso.
        val masAncha = medidas.maxOf { max(it.first, 1.0) }
        val masEstrecha = medidas.minOf { max(it.first, 1.0) }
        k = min(k, ANCHO_MAXIMO / masAncha)
        // Y si con eso la más estrecha no se lee, se sube para todas: pasarse de píxeles se
        // aguanta, no ver lo que pone no.
        k = max(k, ANCHO_MINIMO / masEstrecha)
        return medidas.map { (an, _) -> (max(an, 1.0) * k).toInt().coerceAtLeast(1) }
    }

    /**
     * El nombre de la hoja fusionada: «Páginas 3 a 5» si van seguidas, y si no, «3, 5 y 8».
     *
     * Se dice **qué páginas son** y no «Páginas (3)», porque lo que uno busca en la lista del
     * proyecto es «dónde está lo de la 4» (usuario, 14-sep-2026).
     */
    fun nombre(paginas: List<Int>): String {
        if (paginas.isEmpty()) return "Páginas"
        if (paginas.size == 1) return "Página ${paginas[0] + 1}"
        return "Páginas " + cuales(paginas)
    }

    /**
     * El rótulo de una de sus páginas: «3 a 5 · pág. 4».
     *
     * Lleva el grupo delante a propósito: en la rejilla del proyecto, las páginas de una fusión se
     * ven así de un vistazo como lo que son —tres páginas que van juntas— y no como tres sueltas.
     */
    fun rotulo(paginas: List<Int>, pagina: Int): String = "${cuales(paginas)} · pág. ${pagina + 1}"

    /** «3 a 5» si van seguidas; «3, 5 y 8» si no. */
    private fun cuales(paginas: List<Int>): String {
        val n = paginas.map { it + 1 }
        if (n.size == 1) return "${n[0]}"
        if (n.zipWithNext().all { (a, b) -> b == a + 1 }) return "${n.first()} a ${n.last()}"
        return n.dropLast(1).joinToString(", ") + " y " + n.last()
    }
}
