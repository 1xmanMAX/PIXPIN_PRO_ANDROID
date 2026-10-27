package com.forge.pixpin.motor

/**
 * **Una hoja lleva su propio papel** (27-oct-2026, el usuario: «el modo noche de algunas figuras
 * es feo: el blanco simplemente se transforma en negro y el negro en blanco; arregla los
 * diseños»).
 *
 * [DrawTheme.adaptar] adapta cada tinta **contra el papel del lienzo**, y eso está bien para lo
 * que se dibuja suelto encima del papel. Pero una plantilla (un planificador, un diario, un bloc)
 * trae **su propia página**: el rectángulo relleno de abajo que lo encierra todo. Contra el papel
 * del lienzo de noche, la página blanca se quedaba blanca (contra la pizarra ya se lee) pero su
 * tinta oscura sí se daba la vuelta a clara: letras y rayas claras sobre la página blanca, o sea
 * casi nada. Lo mismo dentro de un símbolo: la tinta grafito, blanca dentro del cuerpo blanco.
 * Y una plantilla de noche del PC (la gemela «(noche)», con su paleta hecha a mano en
 * `figuras_de_serie/noche.rs`) era peor: su papel azul noche no llega a 3:1 contra la pizarra, así
 * que se **aclaraba** hasta un azul grisáceo claro, con la tinta clara encima.
 *
 * La regla de ahora, que es la de `noche.rs` («la hoja lleva su propio rectángulo de papel: lo que
 * pongan detrás no se nota»):
 *
 * - **Las piezas de una hoja se pintan como se diseñaron**, sin adaptar: la página, su tinta, sus
 *   rayas y sus acentos. La de día es una hoja blanca sobre la mesa oscura; la de noche, su azul
 *   noche con su tinta clara. Nada se invierte.
 * - **Lo que se escribe encima de una hoja se adapta a la hoja, no al lienzo**: el rotulador blanco
 *   del lienzo de noche sale oscuro sobre la página blanca de una plantilla de día, y se lee.
 * - **Dentro de una figura, cada pieza se adapta a lo que tiene debajo en la propia figura.** Un
 *   símbolo de electrónica es un cuerpo relleno de blanco con tinta grafito dentro y patas fuera.
 *   Contra la pizarra, la tinta grafito se daba la vuelta a blanco **también dentro del cuerpo
 *   blanco**, y la onda o la flecha de dentro desaparecían: blanco sobre blanco. Ahora la pata y el
 *   contorno, que van sobre el lienzo, se aclaran; la onda, que va sobre el cuerpo, se queda oscura.
 * - Lo demás, como siempre: contra el papel del lienzo.
 *
 * Una hoja es un **grupo** (el exterior, el que pone [estampar]) cuya pieza de más abajo es un
 * rectángulo de relleno liso y opaco que tapa la caja de todo el grupo. Sin grupo no hay hoja: un
 * rectángulo relleno suelto es dibujo, y se sigue adaptando como dibujo. Es más estricto que
 * [HojaDePlantilla.paginaDe] a propósito: esto se mira en cada cambio del dibujo, mientras se
 * pinta, y tiene que costar una pasada.
 *
 * Es pintura, no dibujo, como todo el tema: los colores guardados no cambian.
 *
 * Sin Android: se prueba en la JVM.
 */
object PapelDeLaHoja {

    /** Cómo se pinta la tinta de cada pieza. Las que no salen aquí, contra el papel del lienzo. */
    class Mapa(
        /** Las piezas de una hoja: tal cual. */
        val comoSeDiseno: Set<String>,
        /** Lo de encima de una hoja: contra el papel de esa hoja (ARGB opaco). */
        val papelDe: Map<String, Int>
    ) {
        companion object {
            val VACIO = Mapa(emptySet(), emptyMap())
        }
    }

    /** Lo que la página puede quedarse corta de la caja del grupo (su propio trazo, redondeos). */
    private const val HOLGURA = 2.0

    /** Una hoja encontrada: su posición en el montón, su caja y su papel. */
    private class Hoja(val indice: Int, val caja: Bounds, val papel: Int)

    /**
     * Las figuras más grandes no se miran pieza contra pieza: cuesta el cuadrado de las piezas, y
     * esto se rehace en cada cambio del dibujo. Los símbolos tienen decenas; una tabla enorme
     * agrupada se queda como antes.
     */
    private const val PIEZAS_MAXIMAS_DE_UNA_FIGURA = 400

    /** Las formas cuyo relleno hace de papel a lo que se pinta encima. */
    private val FORMAS_CON_RELLENO = setOf(ElementType.RECTANGLE, ElementType.ELLIPSE, ElementType.DIAMOND, ElementType.REGION)

    /** Si el punto ([x], [y]) cae dentro del relleno de [e], con caja [c]. */
    private fun cubre(e: Element, c: Bounds, x: Double, y: Double): Boolean {
        if (x < c.x1 || x > c.x2 || y < c.y1 || y > c.y2) return false
        val rx = c.width / 2
        val ry = c.height / 2
        if (rx <= 0 || ry <= 0) return false
        val dx = (x - c.midX) / rx
        val dy = (y - c.midY) / ry
        return when (e.type) {
            ElementType.ELLIPSE -> dx * dx + dy * dy <= 1.0
            ElementType.DIAMOND -> Math.abs(dx) + Math.abs(dy) <= 1.0
            else -> true
        }
    }

    /** El color de un relleno si es opaco, o null (transparente, translúcido o no se entiende). */
    internal fun rellenoOpaco(e: Element): Int? {
        if (e.fillStyle != FillStyle.SOLID || e.opacity < 90) return null
        val c = e.backgroundColor.trim()
        if (!c.startsWith("#")) return null
        val limpio = c.removePrefix("#")
        return when (limpio.length) {
            3, 6 -> DrawTheme.colorDe(c)
            8 -> if (limpio.takeLast(2).equals("ff", ignoreCase = true)) DrawTheme.colorDe("#" + limpio.take(6)) else null
            else -> null
        }
    }

    /** **El mapa de un dibujo.** Un dibujo sin grupos (lo normal) sale enseguida y vacío. */
    fun de(elementos: List<Element>): Mapa {
        if (elementos.none { it.groupIds.isNotEmpty() }) return Mapa.VACIO
        // Por grupo exterior, en el orden del montón.
        val grupos = LinkedHashMap<String, MutableList<IndexedValue<Element>>>()
        for ((i, e) in elementos.withIndex()) {
            if (e.isDeleted || e.isFrame) continue
            val g = e.groupIds.lastOrNull() ?: continue
            grupos.getOrPut(g) { ArrayList() } += IndexedValue(i, e)
        }
        val comoSeDiseno = HashSet<String>()
        val hojas = ArrayList<Hoja>()
        val papelDe = HashMap<String, Int>()
        for (piezas in grupos.values) {
            if (piezas.size < 2) continue
            val hoja = hojaDe(piezas)
            if (hoja != null) {
                piezas.forEach { comoSeDiseno += it.value.id }
                hojas += hoja
            } else if (piezas.size <= PIEZAS_MAXIMAS_DE_UNA_FIGURA) {
                papelesDentroDe(piezas, papelDe)
            }
        }
        if (hojas.isEmpty()) return if (papelDe.isEmpty()) Mapa.VACIO else Mapa(emptySet(), papelDe)
        for ((i, e) in elementos.withIndex()) {
            // Lo que ya tiene papel dentro de su figura se queda con él: la onda de un símbolo
            // puesto sobre una hoja va sobre el cuerpo del símbolo, no sobre la hoja.
            if (e.isDeleted || e.id in comoSeDiseno || e.id in papelDe) continue
            val b = getElementBounds(e)
            // La de más arriba que tenga debajo su centro.
            val hoja = hojas.lastOrNull { h ->
                h.indice < i && b.midX >= h.caja.x1 && b.midX <= h.caja.x2 && b.midY >= h.caja.y1 && b.midY <= h.caja.y2
            } ?: continue
            papelDe[e.id] = hoja.papel
        }
        return Mapa(comoSeDiseno, papelDe)
    }

    /** La hoja de un grupo: su pieza de más abajo, si es una página opaca que lo tapa todo. */
    private fun hojaDe(piezas: List<IndexedValue<Element>>): Hoja? {
        val (indice, pagina) = piezas.first()
        if (pagina.type != ElementType.RECTANGLE) return null
        val papel = rellenoOpaco(pagina) ?: return null
        val caja = getElementBounds(pagina)
        val todo = getCommonBounds(piezas.map { it.value })
        if (todo.x1 < caja.x1 - HOLGURA || todo.y1 < caja.y1 - HOLGURA ||
            todo.x2 > caja.x2 + HOLGURA || todo.y2 > caja.y2 + HOLGURA
        ) return null
        return Hoja(indice, caja, papel)
    }

    /**
     * **Dentro de una figura que no es hoja**: cada pieza, contra el relleno opaco de la propia
     * figura que tiene debajo de su centro (el de más arriba de los de debajo). Las que no tienen
     * ninguno debajo no salen: van contra el lienzo.
     */
    private fun papelesDentroDe(piezas: List<IndexedValue<Element>>, papelDe: MutableMap<String, Int>) {
        val rellenos = piezas.mapNotNull { (i, e) ->
            if (e.type !in FORMAS_CON_RELLENO) return@mapNotNull null
            val papel = rellenoOpaco(e) ?: return@mapNotNull null
            Triple(i, e, papel)
        }
        if (rellenos.isEmpty()) return
        val cajas = rellenos.associate { it.second.id to getElementBounds(it.second) }
        for ((i, e) in piezas) {
            val b = getElementBounds(e)
            val debajo = rellenos.lastOrNull { (j, r, _) ->
                j < i && cubre(r, cajas.getValue(r.id), b.midX, b.midY)
            } ?: continue
            papelDe[e.id] = debajo.third
        }
    }

    /**
     * **La tinta [argb] de la pieza [id] tal como se pinta** sobre el lienzo de papel [papelDelLienzo]:
     * tal cual si es de una hoja, adaptada a la hoja si va encima de una, y si no al lienzo.
     */
    fun tinta(mapa: Mapa, id: String?, argb: Int, papelDelLienzo: Int): Int = when {
        id == null -> DrawTheme.adaptar(argb, papelDelLienzo)
        id in mapa.comoSeDiseno -> argb
        else -> DrawTheme.adaptar(argb, mapa.papelDe[id] ?: papelDelLienzo)
    }

    /**
     * **La tinta como la pinta el PC sobre un anfitrión con su propio papel** (el plano): sobre
     * papel de día, cada color tal cual, también el blanco; sobre papel de noche, como [tinta]
     * contra ese papel. Es `dibujo::tema::tinta` del PC, que solo adapta con papel de noche
     * fijado (`con_papel`, `es_de_noche`) y deja la tinta como se eligió sobre el claro
     * (27-oct-2026, la tinta blanca de un plano anotado de noche en el PC llegaba negra).
     */
    fun tintaSoloDeNoche(mapa: Mapa, id: String?, argb: Int, papel: Int): Int =
        if (DrawTheme.esDeNoche(papel)) tinta(mapa, id, argb, papel) else argb
}
