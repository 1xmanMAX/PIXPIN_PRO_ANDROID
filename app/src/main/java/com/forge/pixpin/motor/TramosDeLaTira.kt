package com.forge.pixpin.motor

/**
 * **Las miniaturas pequeñas, agrupadas por tanda.**
 *
 * En un proyecto de verdad la tira son doscientas miniaturas y **la mayoría vienen del mismo
 * sitio**: las diez primeras son de un PDF, las cinco siguientes los marcos de un lienzo, las
 * diez de después transcripciones de audio. Enseñarlas una a una obliga a barrer diez sellos
 * iguales para llegar a lo siguiente que es **distinto**, que es lo que uno viene buscando.
 *
 * Aquí se juntan las seguidas que salen del mismo sitio en un solo montón, que se puede abrir.
 * Es lo que pidió el usuario el 8-sep-2026, y **solo para las pequeñas**: la portada grande se
 * queda como está, porque ahí no hay nada que ahorrar. Ver [Tramo].
 *
 * Se agrupa por **tanda y no por tipo suelto**: dos PDF distintos separados por un lienzo son
 * tres montones, no dos. Si se juntaran todos los PDF del proyecto, el montón dejaría de
 * corresponder con un sitio de la tira y abrirlo movería páginas que estaban en otra parte.
 */
object TramosDeLaTira {

    /**
     * Cuántas seguidas hacen falta para que valga la pena juntarlas.
     *
     * Con dos no: un montón de dos ahorra una miniatura y cuesta un toque para abrirlo, así
     * que sale perdiendo. Con tres ya se nota, y a partir de ahí mucho.
     */
    const val MINIMO = 3

    /**
     * Un trozo de la tira: o una página suelta, o un montón de [paginas] que vienen del mismo
     * sitio. [desde] es la posición de la primera dentro de la lista original, que es lo que
     * hace falta para poder abrir la que se toque.
     */
    class Tramo(val desde: Int, val paginas: List<HojasDelProyecto.Pagina>, val deQue: String) {
        val montón: Boolean get() = paginas.size > 1
        val primera: HojasDelProyecto.Pagina get() = paginas.first()
    }

    /**
     * **De dónde viene una página**, para saber si es de la misma tanda que la anterior.
     *
     * Las páginas de un PDF se miran todas iguales a propósito —son «el PDF», aunque cada
     * página sea una hoja distinta del proyecto—, mientras que cada lienzo y cada nota son lo
     * suyo: dos lienzos seguidos son dos montones, porque son dos documentos.
     */
    fun origenDe(p: HojasDelProyecto.Pagina): String {
        val h = p.hoja
        return when {
            h.pagina != null -> "pdf"
            h.dibujo != null -> "lienzo:" + h.dibujo
            h.nota != null -> "nota:" + h.id
            else -> "hoja:" + h.id
        }
    }

    /** La tira ya repartida en tramos, en orden. Los montones que estén en [abiertos] salen sueltos. */
    fun de(
        paginas: List<HojasDelProyecto.Pagina>,
        abiertos: Set<String> = emptySet(),
        minimo: Int = MINIMO
    ): List<Tramo> {
        val tramos = ArrayList<Tramo>()
        var i = 0
        while (i < paginas.size) {
            val origen = origenDe(paginas[i])
            var j = i + 1
            while (j < paginas.size && origenDe(paginas[j]) == origen) j++
            val cuantas = j - i
            // Un montón se nombra por su **primera clave**, no por su origen ni por su
            // posición: el origen se repite si el mismo lienzo sale dos veces en la tira, y la
            // posición cambia en cuanto se abre el montón de antes. La clave de la primera
            // página no cambia mientras esa página exista, que es justo lo que hace falta para
            // que un montón siga abierto al volver a la pantalla.
            val nombre = paginas[i].clave
            if (cuantas >= minimo && nombre !in abiertos) {
                tramos += Tramo(i, paginas.subList(i, j), origen)
            } else {
                for (k in i until j) tramos += Tramo(k, listOf(paginas[k]), origen)
            }
            i = j
        }
        return tramos
    }
}
