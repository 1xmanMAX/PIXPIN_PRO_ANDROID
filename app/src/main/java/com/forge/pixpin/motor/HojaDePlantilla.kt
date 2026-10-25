package com.forge.pixpin.motor

/**
 * **La hoja de una plantilla dentro de un lienzo** (25-oct-2026, las carpetas de páginas; portado
 * del PC: `pixpin-motor2d::hoja_de_plantilla` y `exportar::hojas_de_cuaderno`). El usuario del PC:
 * «al exportar, de cada lienzo solo lo que va sobre la plantilla (la hoja) o sobre el marco que
 * puso; si no hay ninguno de los dos, todo lo dibujado». Y «Nueva página»: la misma plantilla otra
 * vez, vacía, en un lienzo nuevo.
 *
 * Una plantilla de serie ([FigurasDeSerie]: un diario, un planificador, un bloc) se estampa
 * **agrupada** ([estampar] le pone un grupo propio, el último de `groupIds`) y lleva **su página**:
 * el rectángulo grande que lo encierra todo ([paginaDe]). Con eso basta para encontrarla en un
 * lienzo lleno de cosas escritas encima: se agrupa por el grupo más exterior y se busca la página
 * de cada grupo. Lo escrito encima no es del grupo (se dibujó después), y por eso se separa la
 * plantilla de lo escrito sin adivinar nada.
 *
 * **Qué plantilla de serie es** se sabe, como en el PC (`figuras_de_serie::cual_es`), por su
 * título: el texto más grande, de 30 o más. Aquí el catálogo no está compilado dentro: llega como
 * [Catalogo], hecho una vez de [FigurasDeSerie.todas].
 *
 * Todo es puro (sin Android): recibe elementos y devuelve cajas y piezas.
 */
object HojaDePlantilla {

    /**
     * Lo mínimo que mide de área la página de una plantilla que no es de serie (una propia): así
     * un símbolo agrupado con su recuadro (una viñeta, un rótulo) no pasa por hoja. Un A4 de serie
     * mide 794 × 1123.
     */
    const val AREA_MINIMA = 300.0 * 300.0

    /** El tamaño de letra desde el que un texto es el título de una hoja (`titulo_de` del PC). */
    const val LETRA_DE_TITULO = 30.0

    /** Una plantilla encontrada en el lienzo. */
    data class Plantilla(
        /** La caja de su página. */
        val caja: Bounds,
        /** Las piezas de la plantilla (las del grupo), sin lo escrito encima. */
        val ids: List<String>,
        /** El grupo con que se estampó, o vacío si no iba agrupada. */
        val grupo: String,
        /** Las de serie con su mismo título (la de día y la de noche), si es de serie. */
        val deSerie: List<FiguraGuardada>
    )

    /**
     * **Las plantillas de serie, por su título**, leídas una vez: las que tienen título y página
     * (los planificadores, los blocs, el storyboard…), no los símbolos.
     */
    class Catalogo(figuras: List<FiguraGuardada>) {
        val porTitulo: Map<String, List<FiguraGuardada>> = figuras
            .filter { FigurasDeSerie.esDeSerie(it) }
            .mapNotNull { f -> tituloDe(f.elementos)?.let { it to f } }
            .filter { (_, f) -> paginaDe(f.elementos) != null }
            .groupBy({ it.first }, { it.second })

        companion object {
            val VACIO = Catalogo(emptyList())
        }
    }

    private fun area(c: Bounds) = maxOf(c.width, 0.0) * maxOf(c.height, 0.0)

    private fun dentro(x: Double, y: Double, c: Bounds) = x >= c.x1 && x <= c.x2 && y >= c.y1 && y <= c.y2

    /**
     * **La página de estas piezas** (`bloques::leer` del PC): el rectángulo más grande que encierra
     * casi todo —el centro de al menos ocho de cada diez de las demás piezas—. Null si no hay.
     */
    fun paginaDe(piezas: List<Element>): Pair<String, Bounds>? {
        val vivos = piezas.filter { !it.isDeleted }
        if (vivos.isEmpty()) return null
        val cajas = vivos.associate { it.id to getElementBounds(it) }
        return vivos
            .filter { it.type == ElementType.RECTANGLE }
            .filter { p ->
                val c = cajas.getValue(p.id)
                val dentroN = vivos.count { o -> o.id != p.id && cajas.getValue(o.id).let { dentro(it.midX, it.midY, c) } }
                dentroN * 10 >= (vivos.size - 1) * 8
            }
            .maxByOrNull { area(cajas.getValue(it.id)) }
            ?.let { it.id to cajas.getValue(it.id) }
    }

    /** El título de una hoja: el texto más grande, de [LETRA_DE_TITULO] o más. */
    fun tituloDe(elementos: List<Element>): String? =
        elementos
            .filter { !it.isDeleted && it.type == ElementType.TEXT && (it.fontSize ?: 0.0) >= LETRA_DE_TITULO }
            .maxByOrNull { it.fontSize ?: 0.0 }
            ?.text

    /** Si estas piezas son una plantilla: tienen página y o son de serie o la página es grande. */
    private fun plantillaDe(piezas: List<Element>, grupo: String, catalogo: Catalogo): Plantilla? {
        if (piezas.size < 2) return null
        val (_, caja) = paginaDe(piezas) ?: return null
        val deSerie = tituloDe(piezas)?.let { catalogo.porTitulo[it] }.orEmpty()
        if (deSerie.isEmpty() && area(caja) < AREA_MINIMA) return null
        return Plantilla(caja, piezas.map { it.id }, grupo, deSerie)
    }

    /**
     * **Las plantillas del lienzo**, en el orden en que se leen (de arriba abajo y, a la misma
     * altura, de izquierda a derecha).
     *
     * Primero por grupos: cada grupo exterior con página es una plantilla. Si no hay ninguna
     * agrupada (el usuario la desagrupó), se mira el lienzo entero, y cuenta solo si es de serie:
     * sin el grupo, un lienzo con un rectángulo grande no tiene por qué ser una hoja.
     */
    fun plantillas(elementos: List<Element>, catalogo: Catalogo): List<Plantilla> {
        val vivos = elementos.filter { !it.isDeleted && !it.isFrame }
        val grupos = LinkedHashMap<String, MutableList<Element>>()
        for (e in vivos) {
            val g = e.groupIds.lastOrNull() ?: continue
            grupos.getOrPut(g) { ArrayList() } += e
        }
        val v = grupos.mapNotNull { (g, piezas) -> plantillaDe(piezas, g, catalogo) }.toMutableList()
        if (v.isEmpty()) {
            plantillaDe(vivos, "", catalogo)?.takeIf { it.deSerie.isNotEmpty() }?.let { v += it }
        }
        return v.sortedWith(compareBy<Plantilla>({ it.caja.y1 }, { it.caja.x1 }))
    }

    /**
     * **La plantilla vacía de la última hoja del lienzo**, lista para estampar en uno nuevo.
     *
     * Si es de serie y tiene las mismas piezas que la de serie, sale la de serie **tal cual** (sin
     * nada de lo tecleado dentro de sus textos; de las dos gemelas, la del mismo papel); si no (una
     * alargada, una propia, una retocada), sus piezas como están, sin lo escrito encima. Null si
     * el lienzo no tiene plantilla.
     */
    fun vacia(elementos: List<Element>, catalogo: Catalogo): FiguraGuardada? {
        val p = plantillas(elementos, catalogo).lastOrNull() ?: return null
        val ids = p.ids.toSet()
        val piezas = elementos
            .filter { !it.isDeleted && it.id in ids }
            .map { e ->
                // El grupo de la estampación se quita: el lienzo nuevo pone el suyo al estampar.
                if (p.grupo.isNotEmpty() && e.groupIds.lastOrNull() == p.grupo) e.copy(groupIds = e.groupIds.dropLast(1)) else e
            }
        val papel = paginaDe(piezas)?.first?.let { id -> piezas.firstOrNull { it.id == id }?.backgroundColor }
        val iguales = p.deSerie.filter { f -> f.elementos.count { !it.isDeleted } == piezas.size }
        val deSerie = iguales.firstOrNull { f ->
            paginaDe(f.elementos)?.first?.let { id -> f.elementos.firstOrNull { it.id == id }?.backgroundColor } == papel
        } ?: iguales.firstOrNull()
        return deSerie ?: FiguraGuardada(
            id = randomId(),
            nombre = p.deSerie.firstOrNull()?.nombre.orEmpty(),
            elementos = normalizarFigura(piezas)
        )
    }

    /**
     * **Un lienzo nuevo con la plantilla vacía de [escena]**, con el mismo papel, la hoja con su
     * esquina en el (0, 0). Null si [escena] no tiene plantilla: entonces la página nueva es un
     * lienzo en blanco, y eso lo decide quien llama.
     */
    fun lienzoSiguiente(escena: Scene, catalogo: Catalogo): Scene? {
        val f = vacia(escena.elements, catalogo) ?: return null
        val caja = f.caja
        val piezas = estampar(f, Pt(caja.width / 2, caja.height / 2))
        if (piezas.isEmpty()) return null
        return Scene(elements = piezas, backgroundColor = escena.backgroundColor)
    }

    /** Una hoja de una página de la carpeta: su caja, lo que va dentro y si se recorta justo. */
    data class Hoja(val caja: Bounds, val contenido: List<Element>, val aRas: Boolean)

    /**
     * **Las hojas de un lienzo dentro de una carpeta** (`exportar::hojas_de_cuaderno` del PC): lo
     * que va sobre la plantilla —su página, una hoja por plantilla y en orden de lectura, **a ras**
     * de la página—; sin plantilla, cada marco que puso el usuario; sin marcos, todo lo dibujado.
     */
    fun hojasDeCuaderno(escena: Scene, catalogo: Catalogo): List<Hoja> {
        val visibles = escena.visible.filter { !it.isFrame }
        val dePlantillas = plantillas(escena.elements, catalogo).mapNotNull { p ->
            val dentro = visibles.filter { boundsOverlap(p.caja, getElementBounds(it)) }
            if (dentro.isEmpty() || p.caja.width <= 0 || p.caja.height <= 0) null else Hoja(p.caja, dentro, aRas = true)
        }
        if (dePlantillas.isNotEmpty()) return dePlantillas
        val deMarcos = escena.marcos.mapNotNull { marco ->
            val caja = getElementBounds(marco)
            val dentro = escena.contenidoDe(marco)
            if (caja.width <= 0 || caja.height <= 0 || dentro.isEmpty()) null
            // La hoja pautada entra con su pauta: es parte de la página (ver `DrawPdf.aArchivo`).
            else Hoja(caja, listOfNotNull(marco.takeIf { it.papel != null }) + dentro, aRas = false)
        }
        if (deMarcos.isNotEmpty()) return deMarcos
        if (visibles.isEmpty()) return emptyList()
        val caja = getCommonBounds(visibles)
        if (caja.width <= 0 && caja.height <= 0) return emptyList()
        return listOf(Hoja(caja, visibles, aRas = false))
    }
}
