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
    val local: Pt? = null,
    /**
     * En qué punto **del recorrido** de una raya está clavado: de 0 a 1.
     *
     * Esta es la tercera forma de guardarlo y la buena, y las dos anteriores
     * fallaron por el mismo motivo — atar el clavo a algo que cambia:
     *
     * 1. **Proporción de la caja.** La caja de una raya horizontal no tiene
     *    alto, así que la proporción vertical era una división por cero
     *    disfrazada: en cuanto la raya giraba, el clavo saltaba al otro lado.
     * 2. **Distancia al origen del elemento.** Mejor, pero el origen de una raya
     *    **es su primer punto**, y al mover una punta se recalcula: el clavo
     *    quedaba medido desde un sitio que ya no era ese y la raya pegaba un
     *    salto enorme. Eran los estirones infinitos.
     *
     * Medido sobre el propio recorrido no hay nada que se descoloque: el clavo
     * está «a un tercio de la raya», y eso sigue significando lo mismo la
     * estires, la gires o le muevas una punta.
     */
    val t: Double? = null
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
    val c = getElementAbsoluteCoords(e)
    val centro = Pt(c.cx, c.cy)
    // **Con la inclinación puesta.** Los puntos de un lineal se guardan sin
    // girar y el ángulo va aparte, así que devolver el punto en crudo daba un
    // clavo que no estaba donde se ve el vértice. Al girar sobre él, la cuenta
    // de recolocar salía torcida y la figura acababa girando sobre su propio
    // centro: era el vértice del triángulo que no se comportaba.
    if (a.indice != null) {
        return absolutePoints(e).getOrNull(a.indice)
            ?.let { pointRotateRads(it, centro, e.angle) }
    }
    a.t?.let { return puntoEnElRecorrido(e, it) }
    val l = a.local ?: return null
    return pointRotateRads(
        Pt(c.x1 + l.x * (c.x2 - c.x1), c.y1 + l.y * (c.y2 - c.y1)),
        centro,
        e.angle
    )
}

/** El punto que está a la fracción [t] del recorrido de una raya, ya girado. */
private fun puntoEnElRecorrido(e: Element, t: Double): Pt? {
    val pts = contornosDe(e).firstOrNull()?.puntos ?: return null
    if (pts.size < 2) return pts.firstOrNull()
    val total = largoDe(pts)
    if (total <= 0.0) return pts.first()
    var falta = t.coerceIn(0.0, 1.0) * total
    for (i in 0 until pts.size - 1) {
        val tramo = hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
        if (falta <= tramo || i == pts.size - 2) {
            val f = if (tramo <= 0.0) 0.0 else (falta / tramo).coerceIn(0.0, 1.0)
            return Pt(
                pts[i].x + (pts[i + 1].x - pts[i].x) * f,
                pts[i].y + (pts[i + 1].y - pts[i].y) * f
            )
        }
        falta -= tramo
    }
    return pts.last()
}

/** A qué fracción del recorrido de [e] cae [p]. */
private fun fraccionDelRecorrido(e: Element, p: Pt): Double {
    val pts = contornosDe(e).firstOrNull()?.puntos ?: return 0.0
    if (pts.size < 2) return 0.0
    val total = largoDe(pts)
    if (total <= 0.0) return 0.0

    var recorrido = 0.0
    var mejor = Double.MAX_VALUE
    var respuesta = 0.0
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val largo = hypot(b.x - a.x, b.y - a.y)
        val d = distanceToSegment(p, a, b)
        if (d < mejor) {
            mejor = d
            val f = if (largo <= 0.0) 0.0 else
                (((p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y)) / (largo * largo))
                    .coerceIn(0.0, 1.0)
            respuesta = (recorrido + f * largo) / total
        }
        recorrido += largo
    }
    return respuesta
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

/**
 * Cómo agarra el clavo a [e] si entra por [donde].
 *
 * Tres formas, de la mejor a la peor:
 *
 * 1. **Por un vértice**, si lo hay ahí. Es la más fuerte: el clavo se mueve
 *    exactamente con ese punto, y la raya puede estirarse desde el otro.
 * 2. **Por la fracción del recorrido** ([Agarre.t]) en todo lo que se dibuja
 *    con puntos: «a un tercio de la raya». Su caja puede estar aplastada —una
 *    raya horizontal no tiene alto— y una proporción sobre cero no significa
 *    nada en cuanto la figura gira.
 * 3. **Por proporción de la caja** ([Agarre.local]) en las figuras con caja de
 *    verdad, que es lo único que aguanta que además se las estire.
 */
private fun agarrarEn(e: Element, donde: Pt, radio: Double): Agarre {
    val c = getElementAbsoluteCoords(e)
    val sinGirar = pointRotateRads(donde, Pt(c.cx, c.cy), -e.angle)

    if (e.isLinear || e.isFreeDraw) {
        val i = absolutePoints(e).indexOfFirst { hypot(it.x - donde.x, it.y - donde.y) <= radio }
        if (i >= 0) return Agarre(e.id, indice = i)
        return Agarre(e.id, t = fraccionDelRecorrido(e, donde))
    }

    val ancho = c.x2 - c.x1
    val alto = c.y2 - c.y1
    // Una caja sin superficie no admite proporciones: se mide sobre el trazo.
    if (ancho == 0.0 || alto == 0.0) {
        return Agarre(e.id, t = fraccionDelRecorrido(e, donde))
    }
    return Agarre(
        e.id,
        local = Pt((sinGirar.x - c.x1) / ancho, (sinGirar.y - c.y1) / alto)
    )
}

/** Los alfileres que atraviesan [id]. */
fun alfileresDe(alfileres: List<Alfiler>, id: String): List<Alfiler> =
    alfileres.filter { a -> a.agarres.any { it.elementId == id } }

/**
 * Cuánto se puede mover una figura, según cuántos clavos la atraviesan.
 *
 * **Esta es la ley entera del alfiler**, y sale de contar clavos como se cuenta
 * en cualquier mecanismo:
 *
 * | Clavos | Qué puede hacer                                    |
 * |--------|----------------------------------------------------|
 * | 0      | Todo: se traslada, gira sobre su centro, se estira. |
 * | 1      | **Solo girar**, y alrededor del clavo.              |
 * | 2 o más| Nada: dos puntos fijos fijan la figura entera.      |
 *
 * Lo importante es que **la traslación desaparece con el primer clavo**. Un
 * listón clavado por un punto no se puede llevar a otro sitio: se puede girar y
 * ya. Antes se trasladaba y arrastraba al vecino, y eso hacía que el clavo
 * pareciera un pegamento en vez de un eje — se movía todo el conjunto en bloque
 * y no había forma de articular nada.
 *
 * Para mover de sitio lo que está clavado se mueve **el clavo**, que es lo que
 * se hace en la realidad: se arranca y se vuelve a clavar. Ver [moverAlfiler].
 */
enum class Libertad { LIBRE, GIRA, FIJA }

fun libertadDe(alfileres: List<Alfiler>, id: String): Libertad =
    when (alfileresDe(alfileres, id).size) {
        0 -> Libertad.LIBRE
        1 -> Libertad.GIRA
        else -> Libertad.FIJA
    }

/**
 * Gira [e] alrededor de [centro], sumando [delta] a su inclinación.
 *
 * Vale para cualquier tipo: el motor pinta **toda** figura girada alrededor del
 * centro de su caja, así que basta con girar ese centro alrededor del eje y
 * sumar el ángulo. Un óvalo, un rombo y una raya giran los tres igual.
 */
fun girarElemento(e: Element, centro: Pt, delta: Double): Element {
    if (delta == 0.0 || e.locked) return e
    val c = getElementAbsoluteCoords(e)
    val nuevoCentro = pointRotateRads(Pt(c.cx, c.cy), centro, delta)
    return e.copy(
        x = e.x + (nuevoCentro.x - c.cx),
        y = e.y + (nuevoCentro.y - c.cy),
        angle = normalizeAngle(e.angle + delta)
    ).touched()
}

/**
 * Arrastra [seleccion] del dedo, **respetando lo que cada figura puede hacer**.
 *
 * Es donde la tabla de [Libertad] se convierte en movimiento:
 *
 * - Sin clavos, la figura sigue al dedo como siempre.
 * - Con uno, **gira alrededor de él**: el dedo marca hacia dónde apunta, no a
 *   dónde va. Es el gesto de mover la aguja de un reloj.
 * - Con dos o más no se mueve, y no hace falta avisar de nada: se ve que no se
 *   mueve, igual que se ve que una puerta con dos bisagras no se descuelga.
 */
fun arrastrarConAlfileres(
    originales: List<Element>,
    alfileres: List<Alfiler>,
    desde: Pt,
    hasta: Pt
): List<Element> {
    val dx = hasta.x - desde.x
    val dy = hasta.y - desde.y
    return originales.map { e ->
        if (e.locked) return@map e
        when (libertadDe(alfileres, e.id)) {
            Libertad.LIBRE -> e.copy(x = e.x + dx, y = e.y + dy).touched()
            Libertad.FIJA -> e
            Libertad.GIRA -> {
                val agarre = alfileresDe(alfileres, e.id).first()
                    .agarres.first { it.elementId == e.id }
                val eje = puntoDelAgarre(e, agarre) ?: return@map e
                val delta = anguloEntre(eje, desde, hasta)
                // Se gira y después se recoloca: girar mueve el centro de la
                // caja, y el punto del clavo tiene que acabar exactamente donde
                // estaba o el eje se iría desplazando a cada fotograma.
                val girado = girarElemento(e, eje, delta)
                val despues = puntoDelAgarre(girado, agarre) ?: return@map girado
                girado.copy(
                    x = girado.x + (eje.x - despues.x),
                    y = girado.y + (eje.y - despues.y)
                )
            }
        }
    }
}

/** Cuánto hay que girar alrededor de [eje] para ir de [desde] a [hasta]. */
internal fun anguloEntre(eje: Pt, desde: Pt, hasta: Pt): Double {
    val a = kotlin.math.atan2(desde.y - eje.y, desde.x - eje.x)
    val b = kotlin.math.atan2(hasta.y - eje.y, hasta.x - eje.x)
    if (!a.isFinite() || !b.isFinite()) return 0.0
    var d = b - a
    while (d > Math.PI) d -= 2 * Math.PI
    while (d < -Math.PI) d += 2 * Math.PI
    return d
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
                actualizado.conPuntoEnElMundo(a.indice, destino)
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
    elementos: List<Element>,
    alfileres: List<Alfiler>,
    alfiler: Alfiler,
    destino: Pt
): List<Element> = elementos.map { e ->
    val agarres = alfiler.agarres.filter { it.elementId == e.id }
    if (agarres.isEmpty() || e.locked) return@map e

    // **Un clavo puesto en un vértice mueve ese vértice, y punto.**
    //
    // Es lo que hace un triángulo de tres rayas: al llevarse un clavo, la
    // esquina va detrás y el triángulo se deforma. Antes esto caía en el caso de
    // abajo y la raya giraba rígida alrededor de su otro clavo, que con dos
    // clavos en los dos extremos se veía como un giro sobre su medio — el
    // triángulo se ponía a dar vueltas en vez de estirarse.
    //
    // Una raya puede estirarse; un círculo no, y por eso solo vale para vértices.
    val porVertice = agarres.filter { it.indice != null }
    if (porVertice.isNotEmpty()) {
        var estirado = e
        for (a in porVertice) estirado = estirado.conPuntoEnElMundo(a.indice!!, destino)
        return@map estirado.touched()
    }

    // **Si la figura tiene otro clavo, gira sobre él en vez de irse.** Es la
    // manivela: el otro clavo la sujeta, así que tirando de este lo único que
    // puede hacer es dar vueltas alrededor del primero. Sin esto, arrastrar un
    // clavo de una pieza con dos la despegaba del otro.
    val otro = alfileresDe(alfileres, e.id).firstOrNull { it !== alfiler }
    if (otro != null) {
        val ejeAgarre = otro.agarres.first { it.elementId == e.id }
        val eje = puntoDelAgarre(e, ejeAgarre) ?: return@map e
        val mano = puntoDelAgarre(e, agarres.first()) ?: return@map e
        val girado = girarElemento(e, eje, anguloEntre(eje, mano, destino))
        val despues = puntoDelAgarre(girado, ejeAgarre) ?: return@map girado
        return@map girado.copy(
            x = girado.x + (eje.x - despues.x),
            y = girado.y + (eje.y - despues.y)
        )
    }

    var actualizado = e
    for (a in agarres) {
        actualizado = if (a.indice != null) {
            actualizado.conPuntoEnElMundo(a.indice, destino)
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
            // Los agarres por vértice no se fijan: mover ese vértice **es** el
            // gesto, y devolverlo a su sitio sería no dejar mover nada.
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
