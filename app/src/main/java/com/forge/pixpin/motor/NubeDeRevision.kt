package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * **La nube de revisión dinámica** (25-oct-2026, porte de `nube_revision.rs` del PC): la de
 * Bluebeam y Acrobat, la que rodea en un plano lo que ha cambiado.
 *
 * Lo que pidió el usuario en el PC y aquí se respeta igual: **los festones no cambian de tamaño
 * ni de forma; al crecer el contorno caben más**. Una nube estirada no se aplasta.
 *
 * ## Cómo viaja (y por qué aquí es una `line`)
 *
 * El PC la escribe como **una `line` cerrada con los festones ya calculados** más dos claves
 * suyas, [Element.nubeRadio] y [Element.nubeBase]; la caja (`x`, `y`, `width`, `height`) es la
 * de fuera, la que tocan las puntas de los festones. Aquí se guarda **exactamente igual**: así
 * cualquier camino que serialice la escena —guardar, la sincronía, mandarla por Wi-Fi, la fusión
 * figura a figura— la deja como el PC la espera, sin una traducción que se pueda olvidar en uno.
 *
 * Lo que cambia es cómo se trata **dentro**: una línea con [Element.nubeRadio] válido deja de ser
 * «de puntos» ([Element.isLinear] da falso). Con eso todo lo que ya sabe de cajas —los ocho
 * tiradores, el estirado, el giro alrededor del centro de la caja— vale tal cual, en vez de
 * cientos de tiradores de vértice que la deshacían al tocar uno. El contorno se pinta siempre de
 * la caja ([festones]) y los puntos se rehacen al anotar cada cambio ([nubesAlDia]), para que el
 * picado (que mira los puntos de la línea) y quien no la conozca (Excalidraw) vean lo mismo.
 *
 * Geometría pura y sin Android: se comprueba en `NubeDeRevisionTest`.
 */
object NubeDeRevision {

    /** El radio del festón si no se dice otro: un diámetro de 24, el de Bluebeam a escala 1. */
    const val RADIO_DE_FABRICA = 12.0

    /** Por debajo de esto no se distingue un festón de un temblor. */
    const val RADIO_MINIMO = 2.0

    /** Y por encima, una nube de tres festones no es una nube. */
    const val RADIO_MAXIMO = 400.0

    /**
     * **Cuánto sale cada festón de su cuerda**, en radios. Con 0,8 y la cuerda de un diámetro el
     * arco barre unos 155° y entre dos festones queda un pico de unos 25°: la nube clásica.
     */
    const val PANZA = 0.8

    /** Los tamaños de festón que ofrece el panel, del fino al gordo. */
    val RADIOS_DEL_PANEL = listOf(6.0, 9.0, 12.0, 18.0, 26.0)

    /** La figura sobre la que va la nube. [palabra] es la de `nubeBase` en el fichero. */
    enum class Base(val palabra: String) {
        RECTANGULO("rectangulo"),
        ELIPSE("elipse");

        companion object {
            /** Sin palabra, o con una que no se conoce, rectángulo: es la nube de siempre. */
            fun desdePalabra(p: String?): Base = if (p == "elipse") ELIPSE else RECTANGULO
        }
    }

    /**
     * Un festón: un arco de circunferencia de [desde] a [hasta], con la panza hacia fuera. Ángulos
     * en radianes de pantalla (la y baja); [barrido] lleva signo.
     */
    data class Feston(
        val desde: Pt,
        val hasta: Pt,
        val centro: Pt,
        val radio: Double,
        val angulo: Double,
        val barrido: Double
    ) {
        fun punto(t: Double): Pt {
            val a = angulo + barrido * t
            return Pt(centro.x + radio * cos(a), centro.y + radio * sin(a))
        }

        /** Lo que mide la cuerda: el «diámetro» real de este festón. */
        val cuerda: Double get() = hypot(hasta.x - desde.x, hasta.y - desde.y)

        /** Un tramo recto cada 2 px de arco, entre 4 y 32: sin facetas a la vista. */
        val pasos: Int get() = ceil(abs(barrido) * radio / 2.0).toInt().coerceIn(4, 32)
    }

    /** El radio que se usa de verdad. Un número roto (NaN, infinito, negativo) vale el de fábrica. */
    fun radioUtil(radio: Double?): Double =
        if (radio != null && radio.isFinite() && radio > 0.0) radio.coerceIn(RADIO_MINIMO, RADIO_MAXIMO)
        else RADIO_DE_FABRICA

    /**
     * **Los festones de una nube** con esa base, en esa caja (puede venir con medidas negativas) y
     * con ese radio. En el sentido de las agujas del reloj en pantalla, empezando por la esquina de
     * arriba a la izquierda (o por arriba, en la elipse). Vacío si la caja no mide nada.
     *
     * En una caja pequeña —menos de tres radios de lado— el festón se encoge para que quepan: es lo
     * único que depende del tamaño, y solo ahí.
     */
    fun festones(base: Base, x: Double, y: Double, ancho: Double, alto: Double, radio: Double): List<Feston> {
        val x0 = minOf(x, x + ancho)
        val x1 = maxOf(x, x + ancho)
        val y0 = minOf(y, y + alto)
        val y1 = maxOf(y, y + alto)
        val w = x1 - x0
        val h = y1 - y0
        if (!w.isFinite() || !h.isFinite() || w < 1.0 || h < 1.0) return emptyList()
        val r = minOf(radioUtil(radio), minOf(w, h) / 3.0)
        val panza = r * PANZA
        val salida = ArrayList<Feston>()
        when (base) {
            Base.RECTANGULO -> {
                val esquinas = listOf(
                    Pt(x0 + panza, y0 + panza), Pt(x1 - panza, y0 + panza),
                    Pt(x1 - panza, y1 - panza), Pt(x0 + panza, y1 - panza)
                )
                for (i in 0 until 4) {
                    val a = esquinas[i]
                    val b = esquinas[(i + 1) % 4]
                    val n = cuantos(hypot(b.x - a.x, b.y - a.y), r, 1)
                    for (k in 0 until n) {
                        val desde = hacia(a, b, k.toDouble() / n)
                        val hasta = if (k + 1 == n) b else hacia(a, b, (k + 1).toDouble() / n)
                        feston(desde, hasta, panza)?.let { salida += it }
                    }
                }
            }
            Base.ELIPSE -> {
                val cx = (x0 + x1) / 2
                val cy = (y0 + y1) / 2
                val rx = w / 2 - panza
                val ry = h / 2 - panza
                // La base muestreada fina y su largo acumulado: los festones van a distancias
                // iguales por el perímetro, no a ángulos iguales (en una elipse alargada saldrían
                // más apretados en las puntas).
                val muestras = 720
                val muestra = (0..muestras).map { i ->
                    val t = -Math.PI / 2 + 2 * Math.PI * i / muestras
                    Pt(cx + rx * cos(t), cy + ry * sin(t))
                }
                val acumulado = DoubleArray(muestras + 1)
                for (i in 1..muestras) {
                    acumulado[i] = acumulado[i - 1] +
                        hypot(muestra[i].x - muestra[i - 1].x, muestra[i].y - muestra[i - 1].y)
                }
                val total = acumulado[muestras]
                val n = cuantos(total, r, 3)
                fun en(s: Double): Pt {
                    var i = 1
                    while (i < muestras && acumulado[i] < s) i++
                    val a0 = acumulado[i - 1]
                    val a1 = acumulado[i]
                    val t = if (a1 > a0) (s - a0) / (a1 - a0) else 0.0
                    return hacia(muestra[i - 1], muestra[i], t)
                }
                val marcas = (0 until n).map { k -> en(total * k / n) }
                for (k in 0 until n) feston(marcas[k], marcas[(k + 1) % n], panza)?.let { salida += it }
            }
        }
        return salida
    }

    /** Cuántos festones caben en [largo]: largo entre diámetro, redondeado, y al menos [minimo]. */
    private fun cuantos(largo: Double, radio: Double, minimo: Int): Int =
        (largo / (2.0 * radio)).roundToInt().coerceIn(minimo, 100_000)

    private fun hacia(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /**
     * El festón de [a] a [b] con la panza hacia fuera: a la izquierda del sentido de la marcha,
     * que en un contorno que va como las agujas del reloj en pantalla es fuera. La panza no pasa
     * de media cuerda: así el arco nunca pasa de media vuelta.
     */
    private fun feston(a: Pt, b: Pt, panza: Double): Feston? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val cuerda = hypot(dx, dy)
        if (cuerda < 1e-3) return null
        val fueraX = dy / cuerda
        val fueraY = -dx / cuerda
        val s = minOf(panza, cuerda / 2).coerceAtLeast(1e-3)
        val radio = (cuerda * cuerda / 4 + s * s) / (2 * s)
        val medio = hacia(a, b, 0.5)
        val centro = Pt(medio.x + fueraX * (s - radio), medio.y + fueraY * (s - radio))
        val angulo = atan2(a.y - centro.y, a.x - centro.x)
        val apice = atan2(fueraY, fueraX)
        val mitad = asin((cuerda / 2 / radio).coerceIn(-1.0, 1.0))
        var giro = apice - angulo
        while (giro > Math.PI) giro -= 2 * Math.PI
        while (giro < -Math.PI) giro += 2 * Math.PI
        val signo = if (giro >= 0) 1.0 else -1.0
        return Feston(a, b, centro, radio, angulo, 2 * mitad * signo)
    }

    /**
     * **El contorno liso y cerrado de unos festones** (el último punto es el primero): el del
     * relleno, el del trazo, el de los puntos que viajan y el que se toca con el dedo.
     */
    fun contornoDeFestones(festones: List<Feston>): List<Pt> {
        val salida = ArrayList<Pt>(festones.size * 16 + 1)
        for (f in festones) {
            val n = f.pasos
            salida += f.desde
            for (i in 1 until n) salida += f.punto(i.toDouble() / n)
        }
        salida.firstOrNull()?.let { salida += it }
        return salida
    }

    /** El contorno de una nube. Una caja que no mide nada da su caja: algo hay que enseñar. */
    fun contorno(base: Base, x: Double, y: Double, ancho: Double, alto: Double, radio: Double): List<Pt> {
        val f = festones(base, x, y, ancho, alto, radio)
        if (f.isEmpty()) {
            return listOf(Pt(x, y), Pt(x + ancho, y), Pt(x + ancho, y + alto), Pt(x, y + alto), Pt(x, y))
        }
        return contornoDeFestones(f)
    }

    /**
     * Sobre qué va la nube de [e], o null si no es una nube. La `line` del fichero dice su base en
     * `nubeBase`; un `rectangle` o un `ellipse` con la clave también vale (lo que escriba otro),
     * con la base de su tipo, como lee el PC (`nube_desde`). Un radio que no es un número positivo
     * no es una nube: se queda en la línea que es.
     */
    fun baseDe(e: Element): Base? {
        val r = e.nubeRadio ?: return null
        if (!r.isFinite() || r <= 0.0) return null
        return when (e.type) {
            ElementType.LINE -> Base.desdePalabra(e.nubeBase)
            ElementType.RECTANGLE -> Base.RECTANGULO
            ElementType.ELLIPSE -> Base.ELIPSE
            else -> null
        }
    }

    /** El contorno de la nube de [e] en coordenadas de escena (sin girar), o null si no lo es. */
    fun contornoDe(e: Element): List<Pt>? {
        val base = baseDe(e) ?: return null
        return contorno(base, e.x, e.y, e.width, e.height, radioUtil(e.nubeRadio))
    }

    /**
     * [e] con sus puntos **rehechos de su caja**: los festones de ahora, relativos a la esquina,
     * sin redondeo (con `roundness` la línea se pasaría por una curva y los picos se irían). Es
     * lo que escribe el PC (`nube_hacia`). Lo que no es una `line` nube se devuelve tal cual.
     */
    fun conFestones(e: Element): Element {
        if (e.type != ElementType.LINE) return e
        val c = contornoDe(e) ?: return e
        val puntos = c.map { Pt(it.x - e.x, it.y - e.y) }
        if (puntos == e.points && e.roundness == null) return e
        return e.copy(points = puntos, roundness = null)
    }

    /**
     * **Las nubes de [elementos] con sus puntos al día.** Se llama al anotar cada cambio: el
     * estirado escala los puntos viejos —los festones se deformarían—, y aquí se rehacen de la
     * caja nueva. Devuelve la misma lista si no había nada que rehacer.
     */
    fun nubesAlDia(elementos: List<Element>): List<Element> {
        if (elementos.none { it.nubeRadio != null && it.type == ElementType.LINE }) return elementos
        var cambio = false
        val salida = elementos.map { e ->
            if (e.isDeleted || e.nubeRadio == null) e
            else conFestones(e).also { if (it !== e) cambio = true }
        }
        return if (cambio) salida else elementos
    }

    /**
     * **Una nube nueva** desde [e] (la línea que nace con la herramienta), con el festón y la
     * base del pincel. Sin rugosidad y sin redondeo, como la de la galería del PC.
     */
    fun nueva(e: Element, radio: Double, base: Base): Element = e.copy(
        type = ElementType.LINE,
        nubeRadio = radioUtil(radio),
        nubeBase = base.palabra,
        roundness = null,
        roughness = Element.ROUGHNESS_ARCHITECT,
        points = emptyList(),
        startArrowhead = null,
        endArrowhead = null
    )

    /** ¿[p] (escena, sin girar) cae dentro del contorno [c]? Par/impar, vale para la nube cóncava. */
    fun dentro(c: List<Pt>, p: Pt): Boolean {
        var dentro = false
        var j = c.lastIndex
        for (i in c.indices) {
            val a = c[i]
            val b = c[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
                dentro = !dentro
            }
            j = i
        }
        return dentro
    }
}

/** **Es una nube de revisión**: ver [NubeDeRevision.baseDe]. */
val Element.esNube: Boolean get() = NubeDeRevision.baseDe(this) != null
