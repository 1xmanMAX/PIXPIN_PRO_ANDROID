package com.forge.pixpin.motor

import kotlin.math.hypot
import kotlinx.serialization.Serializable

/**
 * El alfiler: **un clavo que atraviesa dos figuras**.
 *
 * La analogía es literal y conviene tenerla en la cabeza, porque de ella sale
 * todo el comportamiento. Dos listones de madera clavados por un punto:
 *
 * - **No se pueden separar.** Muevas el que muevas, va el otro detrás.
 * - **Sí pueden girar uno respecto del otro**, alrededor del clavo. Eso es una
 *   articulación, y el clavo es su eje.
 * - **Con dos clavos ya no gira nada.** Dos puntos en común fijan la posición
 *   relativa entera: los dos listones pasan a ser una sola pieza.
 *
 * Lo primero que hubo aquí fue más pobre: soldaba **vértices** de rayas, y solo
 * si coincidían. No servía para clavar un círculo con otro, ni para clavar por
 * el cruce de dos líneas —donde no hay vértice de nadie— ni para girar sobre el
 * clavo. Ahora se clava **en un punto**, y lo que se clava es *cualquier
 * figura*: por su vértice si lo tiene ahí, y si no, por el sitio de su caja
 * donde le entra el clavo.
 *
 * Todo esto es geometría pura: quién arrastra a quién se decide aquí, sin
 * Android, porque una articulación que se despega es de las cosas que se ven
 * tarde y se arreglan mal.
 */

/**
 * Por dónde agarra el alfiler a una figura.
 *
 * Dos formas, y la diferencia importa. Un lineal se agarra por **un punto suyo**
 * ([indice]): mover ese punto es mover el agarre, y así una raya puede girar
 * sobre el clavo estirándose desde la otra punta. Las demás figuras no tienen
 * puntos, así que se agarran por **una proporción de su caja** ([local]): el
 * clavo entra por el mismo sitio de la figura aunque la figura crezca.
 */
@Serializable
data class Agarre(
    val elementId: String,
    /** El vértice por el que agarra, si la figura tiene vértices. */
    val indice: Int? = null,
    /** Y si no, dónde le entra el clavo: de 0 a 1 en cada eje de su caja. */
    val local: Pt? = null
)

/** Un clavo, con todo lo que atraviesa. */
@Serializable
data class Alfiler(
    /** Dónde está clavado, en coordenadas de escena. */
    val punto: Pt,
    val agarres: List<Agarre>
) {
    val valido: Boolean get() = agarres.map { it.elementId }.distinct().size >= 2
}

/** Dónde cae ahora mismo el agarre de [a] sobre [e]. */
fun puntoDelAgarre(e: Element, a: Agarre): Pt? {
    if (a.indice != null) return absolutePoints(e).getOrNull(a.indice)
    val l = a.local ?: return null
    val c = getElementAbsoluteCoords(e)
    return pointRotateRads(
        Pt(c.x1 + l.x * (c.x2 - c.x1), c.y1 + l.y * (c.y2 - c.y1)),
        Pt(c.cx, c.cy),
        e.angle
    )
}

/**
 * Clava un alfiler en [p], o lo quita si ya había uno ahí.
 *
 * **Se clava donde de verdad se cruzan las figuras.** El dedo no acierta un
 * cruce ni de lejos, así que el punto se afina con el mismo imán que coloca los
 * trazos: si hay una intersección o un vértice cerca, el clavo va exactamente
 * ahí. Clavarlo «casi» en el cruce dejaría las dos figuras unidas por un punto
 * que no está en ninguna de las dos, y al girar se vería el desajuste.
 *
 * Devuelve null si no hay al menos **dos figuras distintas** que atravesar: un
 * clavo que sujeta una sola cosa no sujeta nada.
 */
fun clavarEn(
    elementos: List<Element>,
    alfileres: List<Alfiler>,
    p: Pt,
    radio: Double
): List<Alfiler>? {
    // Un toque sobre un clavo lo saca: es la misma pregunta al revés.
    alfileres.firstOrNull { hypot(it.punto.x - p.x, it.punto.y - p.y) <= radio }?.let {
        return alfileres - it
    }

    val candidatas = elementos.filter { e ->
        !e.isDeleted && !e.locked && !e.isFrame &&
            puntoEnElPerimetro(e, p, radio) != null
    }
    if (candidatas.map { it.id }.distinct().size < 2) return null

    // Afinar: primero un cruce entre las candidatas, luego un vértice, y si no
    // hay nada, el punto tal cual.
    val cruce = interseccionesCerca(candidatas, p, radio).minByOrNull {
        hypot(it.punto.x - p.x, it.punto.y - p.y)
    }?.punto
    val vertice = candidatas.flatMap { absolutePoints(it) }
        .filter { hypot(it.x - p.x, it.y - p.y) <= radio }
        .minByOrNull { hypot(it.x - p.x, it.y - p.y) }
    val donde = cruce ?: vertice ?: p

    val agarres = candidatas.map { e -> agarrarEn(e, donde, radio) }
    return alfileres + Alfiler(donde, agarres)
}

/** Cómo agarra el clavo a [e] si entra por [donde]. */
private fun agarrarEn(e: Element, donde: Pt, radio: Double): Agarre {
    if (e.isLinear || e.isFreeDraw) {
        val i = absolutePoints(e).indexOfFirst { hypot(it.x - donde.x, it.y - donde.y) <= radio }
        if (i >= 0) return Agarre(e.id, indice = i)
    }
    val c = getElementAbsoluteCoords(e)
    val sinGirar = pointRotateRads(donde, Pt(c.cx, c.cy), -e.angle)
    val ancho = (c.x2 - c.x1).takeIf { it != 0.0 } ?: 1.0
    val alto = (c.y2 - c.y1).takeIf { it != 0.0 } ?: 1.0
    return Agarre(
        e.id,
        local = Pt((sinGirar.x - c.x1) / ancho, (sinGirar.y - c.y1) / alto)
    )
}

/** Los alfileres que atraviesan [id]. */
fun alfileresDe(alfileres: List<Alfiler>, id: String): List<Alfiler> =
    alfileres.filter { a -> a.agarres.any { it.elementId == id } }

/**
 * Todo lo que va detrás de [ids] al moverlos: **el racimo**.
 *
 * Se propaga de clavo en clavo hasta que no hay más: dos listones clavados y un
 * tercero clavado al segundo se mueven los tres, porque físicamente son una
 * sola cosa. Sin la propagación, mover el primero rompería la unión del tercero
 * sin tocarlo siquiera.
 */
fun racimoDe(alfileres: List<Alfiler>, ids: Set<String>): Set<String> {
    if (alfileres.isEmpty()) return ids
    val racimo = ids.toMutableSet()
    var creciendo = true
    while (creciendo) {
        creciendo = false
        for (a in alfileres) {
            val suyos = a.agarres.map { it.elementId }
            if (suyos.none { it in racimo }) continue
            for (id in suyos) if (racimo.add(id)) creciendo = true
        }
    }
    return racimo
}

/**
 * Lleva los agarres de [alfiler] a [destino], menos el que ya está movido.
 *
 * Un agarre por vértice mueve **solo ese punto** —la raya se estira o gira sobre
 * su otra punta, que es lo que hace una articulación—, y uno por caja mueve
 * **la figura entera**, porque un círculo no puede llevar un punto suyo a otro
 * sitio sin irse con él.
 */
fun arrastrarAlfiler(
    elementos: List<Element>,
    alfileres: List<Alfiler>,
    movido: Agarre,
    destino: Pt
): List<Element> {
    val alfiler = alfileres.firstOrNull { a ->
        a.agarres.any { it.elementId == movido.elementId && it.indice == movido.indice }
    } ?: return elementos

    val otros = alfiler.agarres.filterNot {
        it.elementId == movido.elementId && it.indice == movido.indice
    }
    if (otros.isEmpty()) return elementos

    return elementos.map { e ->
        val agarres = otros.filter { it.elementId == e.id }
        if (agarres.isEmpty() || e.locked) return@map e
        var actualizado = e
        for (a in agarres) {
            actualizado = if (a.indice != null) {
                actualizado.withPointMovedTo(a.indice, destino)
            } else {
                val actual = puntoDelAgarre(actualizado, a) ?: continue
                actualizado.copy(
                    x = actualizado.x + (destino.x - actual.x),
                    y = actualizado.y + (destino.y - actual.y)
                )
            }
        }
        actualizado.touched()
    }
}

/**
 * Mueve el clavo a [destino] y **se lleva todo lo que atraviesa**.
 *
 * Es el gesto de arrancar el clavo con las dos maderas puestas y volver a
 * clavarlo en otro sitio: lo que estaba unido sigue unido, y cada figura va
 * detrás por donde la agarraba. Un agarre por vértice lleva **ese punto**; uno
 * por caja lleva **la figura entera**, porque un círculo no puede llevar un
 * punto suyo a otro sitio sin irse con él.
 */
fun moverAlfiler(
    elementos: List<Element>, alfiler: Alfiler, destino: Pt
): List<Element> = elementos.map { e ->
    val agarres = alfiler.agarres.filter { it.elementId == e.id }
    if (agarres.isEmpty() || e.locked) return@map e
    var actualizado = e
    for (a in agarres) {
        actualizado = if (a.indice != null) {
            actualizado.withPointMovedTo(a.indice, destino)
        } else {
            val actual = puntoDelAgarre(actualizado, a) ?: continue
            actualizado.copy(
                x = actualizado.x + (destino.x - actual.x),
                y = actualizado.y + (destino.y - actual.y)
            )
        }
    }
    actualizado.touched()
}

/**
 * Devuelve las figuras a su sitio para que **el clavo no se mueva**.
 *
 * Es la regla que faltaba, y la que hace que un clavo en mitad de una raya
 * signifique algo. Moviendo una punta, la raya cambia de largo y de inclinación,
 * y con ella se iba de sitio el punto por el que estaba clavada: el clavo
 * quedaba de adorno, sin sujetar nada. Ahora, después de mover la punta, la raya
 * se recoloca hasta que su punto clavado vuelve a estar en el clavo — que es
 * exactamente lo que hace una madera clavada cuando le tiras de un extremo:
 * gira sobre el clavo, no se lo lleva.
 *
 * Solo afecta a los agarres **por caja**: los agarres por vértice se mueven a
 * propósito, que para eso se arrastra el vértice.
 */
fun fijarAlfileres(
    elementos: List<Element>, alfileres: List<Alfiler>, salvo: String? = null
): List<Element> {
    if (alfileres.isEmpty()) return elementos
    return elementos.map { e ->
        if (e.locked) return@map e
        var actualizado = e
        for (a in alfileres) {
            val agarre = a.agarres.firstOrNull {
                it.elementId == e.id && it.indice == null
            } ?: continue
            if (e.id == salvo && a.agarres.any { it.elementId == salvo && it.indice != null }) {
                continue
            }
            val actual = puntoDelAgarre(actualizado, agarre) ?: continue
            actualizado = actualizado.copy(
                x = actualizado.x + (a.punto.x - actual.x),
                y = actualizado.y + (a.punto.y - actual.y)
            )
        }
        actualizado
    }
}

/**
 * Gira [e] **alrededor de su alfiler** en vez de sobre su propio centro.
 *
 * Es la articulación: un listón clavado por un extremo gira sobre el clavo, no
 * sobre su mitad. Con dos o más clavos no se gira nada — dos puntos en común
 * fijan la posición relativa entera— y por eso ahí se devuelve el elemento tal
 * cual, que es lo que hace un clavo de más en la vida real.
 */
fun girarSobreAlfiler(
    e: Element, alfileres: List<Alfiler>, pointer: Pt, discreto: Boolean = false
): Element {
    val suyos = alfileresDe(alfileres, e.id)
    if (suyos.size != 1) return if (suyos.isEmpty()) {
        rotateSingleElement(e, pointer, discreto)
    } else e

    val agarre = suyos.first().agarres.first { it.elementId == e.id }
    val eje = puntoDelAgarre(e, agarre) ?: return rotateSingleElement(e, pointer, discreto)

    // Se gira como siempre —el ángulo sale del tirador— y después se recoloca
    // para que el punto del clavo vuelva a su sitio. Girar directamente sobre
    // un centro que no es el suyo obligaría a rehacer toda la cuenta del
    // redimensionado, que va toda referida al centro de la caja.
    val girado = rotateSingleElement(e, pointer, discreto)
    val despues = puntoDelAgarre(girado, agarre) ?: return girado
    return girado.copy(x = girado.x + (eje.x - despues.x), y = girado.y + (eje.y - despues.y))
}

/**
 * Recoloca cada clavo donde esté ahora su primer agarre.
 *
 * Los clavos se guardan en coordenadas de escena, así que al mover las figuras
 * se quedarían atrás. Se recalculan en vez de moverse a mano por lo mismo que
 * la cota no guarda su número: un dato que se puede deducir y se guarda aparte
 * acaba discrepando de la realidad, y aquí «discrepar» significa que la marca
 * roja aparece donde ya no hay ninguna unión.
 */
fun refrescarAlfileres(elementos: List<Element>, alfileres: List<Alfiler>): List<Alfiler> {
    val porId = elementos.associateBy { it.id }
    return alfileres.mapNotNull { a ->
        val vivos = a.agarres.filter { porId[it.elementId]?.isDeleted == false }
        if (vivos.map { it.elementId }.distinct().size < 2) return@mapNotNull null
        val sitio = vivos.firstNotNullOfOrNull { g ->
            porId[g.elementId]?.let { puntoDelAgarre(it, g) }
        } ?: a.punto
        Alfiler(sitio, vivos)
    }
}

/** Dónde hay que pintar las cabezas de los clavos. */
fun puntosDeAlfileres(elementos: List<Element>, alfileres: List<Alfiler>): List<Pt> =
    refrescarAlfileres(elementos.filter { !it.isDeleted }, alfileres).map { it.punto }
