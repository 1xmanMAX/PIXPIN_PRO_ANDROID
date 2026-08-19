package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * La lupa: **mirar un trozo pequeño y enseñarlo grande en otro sitio**.
 *
 * Es la herramienta de las láminas: el plano entero se ve, y al lado —con su
 * flecha— el detalle del que se está hablando, agrandado. Lo que la hace útil no
 * es agrandar, que eso ya lo hace el zoom, sino **agrandar sin dejar de ver el
 * conjunto**: las dos cosas a la vez y en el mismo papel.
 *
 * ## Las dos cajas
 *
 * Una lupa son dos recuadros unidos por una cuenta:
 *
 * ```
 *          ┌───────┐  el foco: lo que se mira
 *          │  abc  │  (pequeño, donde está la cosa)
 *          └───┬───┘
 *              │  la flecha dice de dónde sale
 *      ┌───────┴────────┐
 *      │                │  el cristal: lo que se ve
 *      │      abc       │  (grande, donde se quiera)
 *      └────────────────┘
 * ```
 *
 * El **cristal** es la caja del propio elemento, así que se mueve y se estira
 * con los tiradores de siempre, sin nada especial. El **foco** se guarda aparte
 * ([Element.foco]) y **en coordenadas del dibujo, no relativas al cristal**: por
 * eso apartar el cristal no arrastra lo que se está mirando, que es exactamente
 * lo que se pide de una lupa de lámina.
 *
 * El recuadro del foco no se guarda: **se calcula**. Es el cristal dividido por
 * el aumento, siempre, y de ahí sale la propiedad que hace que esto no mienta
 * nunca — lo que se ve dentro del cristal está a escala [Element.aumento] de lo
 * que hay en el foco, se estire lo que se estire. Guardando las dos cajas por
 * separado habría que mantenerlas a mano y a la primera se descuadran.
 *
 * Todo esto es geometría y vive fuera de Android: se puede comprobar.
 */

/** Lo menos que agranda una lupa: nada. A uno se ve del mismo tamaño. */
const val AUMENTO_MINIMO = 1.0

/** Y lo más. Más allá se ve el grano de la pantalla, no el detalle. */
const val AUMENTO_MAXIMO = 12.0

/** Con cuánto nace: el doble, que es lo que se pide sin pensarlo. */
const val AUMENTO_POR_DEFECTO = 2.0

/**
 * Cuánto agranda esta lupa **de verdad**.
 *
 * Sale de la geometría —el cristal partido por el foco— y no de un número
 * guardado, porque el cristal se puede estirar con los tiradores: un número
 * aparte se quedaría diciendo ×2 mientras se está viendo ×5. Así el deslizador
 * enseña siempre lo que se ve, se haya llegado por donde se haya llegado.
 */
fun aumentoDe(e: Element): Double {
    val ancho = e.focoAncho
    if (ancho == null || ancho <= 0.0) {
        return (e.aumento ?: AUMENTO_POR_DEFECTO).coerceIn(AUMENTO_MINIMO, AUMENTO_MAXIMO)
    }
    val c = getElementAbsoluteCoords(e)
    return (abs(c.x2 - c.x1) / ancho).coerceIn(AUMENTO_MINIMO, AUMENTO_MAXIMO)
}

/**
 * El cristal que le toca a una lupa para agrandar [aumento] veces.
 *
 * Es lo que hace el deslizador: **mueve la ventana, no la zona mirada**. Crece
 * desde su propio centro para que subir el zoom no la desplace de donde uno la
 * había dejado.
 */
fun conAumento(e: Element, aumento: Double): Element {
    val region = regionDeLaLupa(e)
    if (region.width <= 0.0 || region.height <= 0.0) return e
    val z = aumento.coerceIn(AUMENTO_MINIMO, AUMENTO_MAXIMO)
    val c = getElementAbsoluteCoords(e)
    val w = region.width * z
    val h = region.height * z
    return e.copy(
        x = c.cx - w / 2,
        y = c.cy - h / 2,
        width = w,
        height = h,
        aumento = z,
        // Se fija el tamaño del foco al primer ajuste: hasta entonces podía
        // venir del cristal, y cambiar el cristal lo habría cambiado también.
        focoAncho = region.width,
        focoAlto = region.height
    ).touched()
}

/**
 * A dónde mira la lupa.
 *
 * Sin foco puesto, **a su propio centro**: una lupa recién dejada sobre el papel
 * agranda lo que tiene debajo, como una lupa de verdad. Solo al apartar el
 * cristal o al arrastrar el foco pasan a ser dos sitios distintos, y entonces ya
 * se guarda el punto.
 */
fun focoDe(e: Element): Pt {
    e.foco?.let { return it }
    val c = getElementAbsoluteCoords(e)
    return Pt(c.cx, c.cy)
}

/**
 * El recuadro que se recoge, centrado en el foco.
 *
 * Mide lo que el cristal **dividido por el aumento**: si el cristal tiene 200 de
 * ancho y agranda 4 veces, se recogen 50. Así lo que entra llena exactamente lo
 * que sale.
 */
fun regionDeLaLupa(e: Element): Bounds {
    val c = getElementAbsoluteCoords(e)
    // **Su tamaño se guarda.** Solo cuando no lo lleva —una lupa de antes— se
    // deduce del cristal, que era la forma vieja de calcularlo.
    val ancho = e.focoAncho ?: (abs(c.x2 - c.x1) / (e.aumento ?: AUMENTO_POR_DEFECTO))
    val alto = e.focoAlto ?: (abs(c.y2 - c.y1) / (e.aumento ?: AUMENTO_POR_DEFECTO))
    val f = focoDe(e)
    return Bounds(
        x1 = f.x - ancho / 2,
        y1 = f.y - alto / 2,
        x2 = f.x + ancho / 2,
        y2 = f.y + alto / 2
    )
}

/**
 * ¿El dedo ha caído en el foco?
 *
 * Se pica **el recuadro entero**, no solo su centro: en una lupa de mucho
 * aumento el recuadro es diminuto y acertarle a un punto sería imposible, así
 * que se le da además un margen del grosor de un dedo.
 */
fun tocaElFoco(e: Element, p: Pt, margen: Double): Boolean {
    val r = regionDeLaLupa(e)
    // Con forma propia se pica su caja **con margen**: en un contorno raro y
    // diminuto, exigir acertar dentro del garabato sería no poder cogerlo.
    if (e.forma != null) {
        return p.x >= r.x1 - margen && p.x <= r.x2 + margen &&
            p.y >= r.y1 - margen && p.y <= r.y2 + margen
    }
    if (!e.lupaRedonda) {
        return p.x >= r.x1 - margen && p.x <= r.x2 + margen &&
            p.y >= r.y1 - margen && p.y <= r.y2 + margen
    }
    // Redondo se pica el óvalo, no su caja: con la caja, las cuatro esquinas
    // respondían a un sitio donde no hay foco dibujado.
    val rx = r.width / 2 + margen
    val ry = r.height / 2 + margen
    if (rx <= 0 || ry <= 0) return false
    val dx = (p.x - r.midX) / rx
    val dy = (p.y - r.midY) / ry
    return dx * dx + dy * dy <= 1.0
}

/**
 * La lupa con el foco puesto en otro sitio.
 *
 * Se guarda **siempre** el punto, aunque coincida con el centro del cristal:
 * a partir del primer arrastre las dos cajas son cosas distintas y dejar el foco
 * nulo lo volvería a atar al cristal en cuanto este se moviera.
 */
fun conFoco(e: Element, foco: Pt): Element = e.copy(foco = foco).touched()

/**
 * Con qué se señala de dónde sale lo que se ve.
 *
 * Las dos líneas son la forma de toda la vida en un plano: salen de los costados
 * del detalle y se abren hasta los del cristal, como un cono. Dicen lo mismo que
 * la flecha y encima dicen **cuánto** se ha ampliado, porque la apertura se ve.
 * La flecha ocupa menos y es más clara cuando el detalle queda lejos.
 */
@kotlinx.serialization.Serializable
enum class GuiaDeLupa {
    NINGUNA,
    FLECHA,
    DOS_LINEAS,

    /**
     * Un punto rojo en el sitio, y una raya hasta la ventana.
     *
     * Es lo que se usa cuando lo que se señala es **un sitio y no una zona**: un
     * detalle diminuto en un plano grande, donde el contorno de lo mirado sería
     * una mota que no se ve. El punto sí se ve, y en rojo se encuentra de un
     * vistazo por encima de lo que haya dibujado debajo.
     */
    PUNTO;

    /**
     * ¿Este modo dibuja el contorno de la zona mirada?
     *
     * En la flecha y el cono sí, **y con el grosor de la lupa**: son los modos
     * que dicen «esto de aquí es aquello de allí», y para eso hay que ver la
     * forma de lo señalado. El punto lo sustituye por una marca y el invisible
     * no señala nada.
     */
    val dibujaLaZona: Boolean get() = this == FLECHA || this == DOS_LINEAS
}

/** Qué guía lleva esta lupa. Ver [GuiaDeLupa]. */
fun guiaDe(e: Element): GuiaDeLupa =
    // Las lupas de antes no llevan campo: se traduce lo que tenían. Guardarlo
    // como dos síes y noes daba cuatro combinaciones para tres modos, y la
    // cuarta —los dos a la vez— significaba lo que a uno le pareciera.
    e.guia ?: if (e.lupaFlecha) GuiaDeLupa.FLECHA else GuiaDeLupa.NINGUNA

/**
 * Las rayas de la guía, **de borde a borde**.
 *
 * Una si es flecha, dos si es cono, ninguna si la lupa está apoyada sobre lo que
 * mira —ahí no hay nada que señalar— o si se ha apagado.
 *
 * Salen del borde y no del centro: una raya que nace dentro del recuadro cruza
 * por encima de lo que se quiere enseñar, que es justo lo que no puede hacer. Y
 * el borde se calcula **contra el contorno de verdad**, sea un óvalo, un rombo o
 * un garabato, así que la guía toca la figura y no una caja imaginaria.
 */
fun lineasDeLaGuia(e: Element): List<Pair<Pt, Pt>> {
    val guia = guiaDe(e)
    if (guia == GuiaDeLupa.NINGUNA || laLupaEstaEncima(e)) return emptyList()

    val cristal = getElementAbsoluteCoords(e)
    val region = regionDeLaLupa(e)
    val centroCristal = Pt(cristal.cx, cristal.cy)
    val centroFoco = Pt(region.midX, region.midY)

    val dx = centroCristal.x - centroFoco.x
    val dy = centroCristal.y - centroFoco.y
    val largo = hypot(dx, dy)
    if (largo < 1e-6) return emptyList()

    val delCristal = puntosDelCristal(e, cristal)
    val delFoco = puntosDelFoco(e)

    if (guia == GuiaDeLupa.FLECHA || guia == GuiaDeLupa.PUNTO) {
        // **Con el punto, la raya llega al punto**, no al borde de la zona.
        //
        // Salía del contorno como en la flecha, y el punto se quedaba flotando
        // en el centro sin tocarla: se veían dos cosas sueltas en vez de una
        // marca con su raya. En este modo el contorno ni siquiera se dibuja, así
        // que ese borde no existe para quien mira. La raya va hasta el centro y
        // el punto se pinta encima, que es lo que las une de verdad.
        val desde = if (guia == GuiaDeLupa.PUNTO) {
            centroFoco
        } else {
            salidaDelContorno(centroFoco, delFoco, dx, dy)
        }
        val hasta = salidaDelContorno(centroCristal, delCristal, -dx, -dy)
        return listOf(desde to hasta)
    }

    // El cono: las dos rayas salen **de costado**, perpendiculares a la
    // dirección que une las dos figuras. Por eso se abren en vez de cruzarse.
    val px = -dy / largo
    val py = dx / largo
    return listOf(1.0, -1.0).map { lado ->
        val a = salidaDelContorno(centroFoco, delFoco, px * lado, py * lado)
        val b = salidaDelContorno(centroCristal, delCristal, px * lado, py * lado)
        a to b
    }
}

/**
 * Por dónde sale de un contorno un rayo que parte de su centro.
 *
 * Se prueban todos sus lados y se coge **el corte más lejano**: en una figura con
 * entrantes —un garabato, una estrella— el primero deja la raya metida dentro de
 * la propia figura, y lo que se quiere es el borde de fuera.
 */
fun salidaDelContorno(centro: Pt, contorno: List<Pt>, dx: Double, dy: Double): Pt {
    if (contorno.size < 2) return centro
    var mejor = 0.0
    for (i in contorno.indices) {
        val a = contorno[i]
        val b = contorno[(i + 1) % contorno.size]
        val t = corteDelRayo(centro, dx, dy, a, b) ?: continue
        if (t > mejor) mejor = t
    }
    if (mejor <= 0.0) return centro
    return Pt(centro.x + dx * mejor, centro.y + dy * mejor)
}

/** Dónde corta el rayo `centro + t·d` al segmento `a-b`, o null si no lo corta. */
private fun corteDelRayo(centro: Pt, dx: Double, dy: Double, a: Pt, b: Pt): Double? {
    val ex = b.x - a.x
    val ey = b.y - a.y
    val den = dx * ey - dy * ex
    if (abs(den) < 1e-12) return null
    val cx = a.x - centro.x
    val cy = a.y - centro.y
    val t = (cx * ey - cy * ex) / den
    val u = (cx * dy - cy * dx) / den
    if (t < 0 || u < 0 || u > 1) return null
    return t
}

/**
 * La raya principal de la guía, para poner la punta de la flecha.
 *
 * Se conserva porque la punta va **en el extremo del foco** y hace falta saber
 * cuál es. Con el cono no hay punta: las dos rayas ya dicen a dónde van.
 */
fun flechaDeLaLupa(e: Element): Pair<Pt, Pt>? =
    if (guiaDe(e) != GuiaDeLupa.FLECHA) null else lineasDeLaGuia(e).firstOrNull()

/**
 * ¿La lupa está apoyada sobre lo que mira?
 *
 * Mientras lo esté no hay nada que señalar —el detalle está ahí mismo, debajo—
 * así que ni recuadro ni raya: solo ensuciarían. En cuanto se aparta, las dos
 * cosas aparecen solas.
 */
fun laLupaEstaEncima(e: Element): Boolean {
    val c = getElementAbsoluteCoords(e)
    val r = regionDeLaLupa(e)
    return r.x1 < c.x2 && r.x2 > c.x1 && r.y1 < c.y2 && r.y2 > c.y1
}

/**
 * Una lupa recién trazada.
 *
 * Nace **sin foco** —o sea, mirando lo que tiene debajo— porque es lo que se
 * entiende sin leer nada: la dejas encima y agranda. Apartarla es el segundo
 * gesto, y para entonces ya se ve lo que hace.
 *
 * Y con un tamaño mínimo: un cristal de dos píxeles trazado por un toque suelto
 * no se ve, y parecería que la herramienta no ha hecho nada.
 */
fun lupaNueva(e: Element): Element {
    val ancho = abs(e.width)
    val alto = abs(e.height)
    if (ancho >= LADO_MINIMO_DE_LUPA && alto >= LADO_MINIMO_DE_LUPA) return e
    val w = max(ancho, LADO_MINIMO_DE_LUPA)
    val h = max(alto, LADO_MINIMO_DE_LUPA)
    return e.copy(
        x = e.x - (w - ancho) / 2,
        y = e.y - (h - alto) / 2,
        width = w,
        height = h
    )
}

/** Lo menos que puede medir un cristal para que se vea algo dentro. */
const val LADO_MINIMO_DE_LUPA = 60.0

/** Las cuatro esquinas de una caja, en orden. */
fun esquinasDeCaja(b: Bounds): List<Pt> =
    listOf(Pt(b.x1, b.y1), Pt(b.x2, b.y1), Pt(b.x2, b.y2), Pt(b.x1, b.y2))

/**
 * El contorno del cristal como una lista de puntos.
 *
 * Redondo sale como un polígono de muchos lados en vez de con curvas: los tres
 * sitios que lo necesitan —pantalla, SVG y PDF— saben dibujar una polilínea y
 * ninguno de los tres tendría que aprender bézier para esto. A [LADOS_DEL_ARO]
 * lados, a la vista es un óvalo.
 */
fun puntosDelCristal(e: Element, c: AbsoluteCoords): List<Pt> {
    // **La forma copiada manda sobre todo lo demás.** Ver [Element.forma].
    e.forma?.takeIf { it.size >= 3 }?.let { forma ->
        val w = c.x2 - c.x1
        val h = c.y2 - c.y1
        return forma.map { Pt(c.x1 + it.x * w, c.y1 + it.y * h) }
    }
    if (!e.lupaRedonda) {
        return listOf(Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2))
    }
    val rx = (c.x2 - c.x1) / 2
    val ry = (c.y2 - c.y1) / 2
    return (0 until LADOS_DEL_ARO).map { i ->
        val a = 2 * Math.PI * i / LADOS_DEL_ARO
        Pt(c.cx + rx * kotlin.math.cos(a), c.cy + ry * kotlin.math.sin(a))
    }
}

/**
 * El contorno de lo que se mira, **con la misma forma que el cristal**.
 *
 * Con el cristal redondo lo que entra es un óvalo —el recorte se hace ahí— así
 * que dibujar un recuadro sería enseñar una zona que no es la que se ve. Además
 * de mentir, hace dudar de cuál de las dos manda.
 */
fun puntosDelFoco(e: Element): List<Pt> {
    val r = regionDeLaLupa(e)
    // Lo que se mira tiene **la forma de lo que se ve**: es el mismo recorte, en
    // pequeño. Con formas distintas no habría manera de saber qué entra.
    e.forma?.takeIf { it.size >= 3 }?.let { forma ->
        return forma.map { Pt(r.x1 + it.x * r.width, r.y1 + it.y * r.height) }
    }
    if (!e.lupaRedonda) return esquinasDeCaja(r)
    val rx = r.width / 2
    val ry = r.height / 2
    return (0 until LADOS_DEL_ARO).map { i ->
        val a = 2 * Math.PI * i / LADOS_DEL_ARO
        Pt(r.midX + rx * kotlin.math.cos(a), r.midY + ry * kotlin.math.sin(a))
    }
}

/** Con cuántos lados se hace el aro. Ver [puntosDelCristal]. */
const val LADOS_DEL_ARO = 64

/**
 * Convierte una figura cerrada en una lupa **con su misma forma**.
 *
 * Es la puerta grande de la herramienta: se traza lo que sea —un círculo, un
 * rombo, un garabato hecho a pulso— y se toca con la lupa. Lo que sale es un
 * duplicado de esa figura que, en vez de estar vacío, enseña en grande lo que
 * hay debajo de ella.
 *
 * **La figura de origen no se toca.** Sigue en el dibujo marcando el sitio, que
 * es lo que hace que la lupa signifique algo: aquello es lo que se está mirando
 * y esto es lo mismo, más grande.
 *
 * Devuelve null si la figura no encierra nada —una raya suelta, una flecha— o si
 * es tan pequeña que no se vería su contenido.
 */
fun lupaDesdeFigura(fuente: Element, estilo: ItemStyle, id: String, semilla: Int): Element? =
    desdeFigura(fuente, ElementType.LUPA, estilo, id, semilla)

/**
 * Convierte una figura cerrada en un **foco**: lo de fuera se oscurece.
 *
 * Misma varita que la lupa y por el mismo motivo: la forma de lo que se quiere
 * resaltar ya está dibujada, así que pedirla otra vez sobraría. Lo que cambia es
 * lo que se hace con ella —agrandar o apagar el resto—, y eso es la herramienta
 * que se tenga cogida.
 */
fun focoDesdeFigura(fuente: Element, estilo: ItemStyle, id: String, semilla: Int): Element? =
    desdeFigura(fuente, ElementType.SPOTLIGHT, estilo, id, semilla)

private fun desdeFigura(
    fuente: Element, tipo: ElementType, estilo: ItemStyle, id: String, semilla: Int
): Element? {
    val contorno = contornosDe(fuente).firstOrNull { it.cerrado && it.puntos.size >= 3 }
        ?: return null

    // **El marco nace derecho, con la figura girada dentro.**
    //
    // Antes se le copiaba el giro de la figura y se guardaba el contorno sin
    // girar. Salía torcido: tocabas un cuadrado inclinado y el marco venía
    // inclinado también, y lo que uno quiere de un marco es que sea un marco —
    // derecho, para poder estirarlo por sus esquinas sin pelearse con el ángulo.
    //
    // Se toma el contorno **tal cual está en el dibujo**, con su inclinación
    // metida ya en los puntos, y se mide su propia caja. Así la figura conserva
    // exactamente la orientación que tenía y el marco es un rectángulo normal.
    val xs = contorno.puntos.map { it.x }
    val ys = contorno.puntos.map { it.y }
    val x1 = xs.min()
    val y1 = ys.min()
    val ancho = xs.max() - x1
    val alto = ys.max() - y1
    if (ancho < 1.0 || alto < 1.0) return null
    val c = AbsoluteCoords(x1, y1, x1 + ancho, y1 + alto, x1 + ancho / 2, y1 + alto / 2)
    val forma = contorno.puntos.map { Pt((it.x - x1) / ancho, (it.y - y1) / alto) }

    val esLupa = tipo == ElementType.LUPA
    // La lupa nace **ya agrandada**: el doble, centrada en el mismo sitio. Así
    // se ve al momento qué hace, y lo único que queda es apartarla. El foco, en
    // cambio, se queda clavado sobre la figura: lo suyo es tapar el resto.
    // El foco nace con **marco alrededor**: es el anillo que se va a oscurecer,
    // y con el marco pegado a la figura no habría nada que pintar. Se estira
    // luego con los tiradores, como cualquier caja.
    val z = if (esLupa) {
        estilo.aumento.coerceIn(AUMENTO_MINIMO, AUMENTO_MAXIMO)
    } else {
        MARCO_DEL_FOCO
    }
    return Element(
        id = id,
        type = tipo,
        x = c.cx - ancho * z / 2,
        y = c.cy - alto * z / 2,
        width = ancho * z,
        height = alto * z,
        // Derecho: el giro ya está dentro de los puntos de la forma.
        angle = 0.0,
        seed = semilla,
        strokeColor = estilo.strokeColor,
        strokeWidth = estilo.strokeWidth,
        opacity = estilo.opacity,
        aumento = z,
        oscurecer = estilo.oscurecer,
        lupaRedonda = estilo.lupaRedonda,
        guia = estilo.guia,
        forma = forma,
        // **La zona mirada es la figura que se ha tocado**, con su tamaño
        // guardado: eso es lo que no se mueve por mucho que crezca el cristal.
        foco = Pt(c.cx, c.cy),
        focoAncho = ancho,
        focoAlto = alto
    )
}

/**
 * ¿De esta figura se puede sacar una lupa?
 *
 * Hace falta que encierre algo: una lupa es un recorte, y un recorte necesita un
 * dentro. Una raya, una flecha o una cota no tienen dentro.
 */
fun sirveDeLupa(e: Element): Boolean =
    !e.isDeleted && e.type != ElementType.LUPA && e.type != ElementType.SPOTLIGHT &&
        contornosDe(e).any { it.cerrado && it.puntos.size >= 3 }


/** Lo menos que puede oscurecer un foco, en por ciento. */
const val OSCURECER_MINIMO = 10

/** Y lo más: por encima de esto lo de alrededor deja de verse. */
const val OSCURECER_MAXIMO = 90

/** Con cuánto nace un foco: se nota sin esconder el contexto. */
const val OSCURECER_POR_DEFECTO = 45

/** Cuánto oscurece este foco, siempre dentro de lo razonable. */
fun oscurecimientoDe(e: Element): Int =
    (e.oscurecer ?: OSCURECER_POR_DEFECTO).coerceIn(OSCURECER_MINIMO, OSCURECER_MAXIMO)

/** Cuánto sobresale el marco de un foco recién puesto, respecto a su figura. */
const val MARCO_DEL_FOCO = 1.8

/**
 * El foco con su zona iluminada más grande o más pequeña, **dentro del mismo
 * marco**.
 *
 * Es lo contrario que [conAumento] y por un buen motivo: en una lupa lo que se
 * ajusta es cuánto agranda —así que crece la ventana— y en un foco lo que se
 * ajusta es cuánto ilumina, con la sombra llegando siempre hasta donde llega el
 * marco. Cambiar el marco aquí movería el borde de la sombra, que es lo único
 * que el usuario había colocado a mano.
 *
 * [parte] va de 0 a 1: qué fracción del marco ocupa la zona.
 */
fun conZona(e: Element, parte: Double): Element {
    val c = getElementAbsoluteCoords(e)
    val ancho = abs(c.x2 - c.x1)
    val alto = abs(c.y2 - c.y1)
    if (ancho <= 0.0 || alto <= 0.0) return e
    val p = parte.coerceIn(ZONA_MINIMA, ZONA_MAXIMA)
    return e.copy(focoAncho = ancho * p, focoAlto = alto * p).touched()
}

/** Qué parte del marco ocupa ahora la zona iluminada, de 0 a 1. */
fun zonaDe(e: Element): Double {
    val c = getElementAbsoluteCoords(e)
    val ancho = abs(c.x2 - c.x1)
    if (ancho <= 0.0) return ZONA_POR_DEFECTO
    val suya = e.focoAncho ?: return ZONA_POR_DEFECTO
    return (suya / ancho).coerceIn(ZONA_MINIMA, ZONA_MAXIMA)
}

/** Lo menos que puede ocupar la zona: por debajo no se ve lo que se resalta. */
const val ZONA_MINIMA = 0.15

/** Y lo más: pegada al marco no queda anillo que oscurecer. */
const val ZONA_MAXIMA = 0.92

/** Con cuánto nace, que es el inverso de [MARCO_DEL_FOCO]. */
const val ZONA_POR_DEFECTO = 1.0 / MARCO_DEL_FOCO
