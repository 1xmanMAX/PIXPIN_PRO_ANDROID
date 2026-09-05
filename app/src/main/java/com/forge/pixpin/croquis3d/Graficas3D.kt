package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Formula
import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.randomId

/**
 * **Gráficas en el espacio**: una superficie `z = f(x, y)` o una curva `(x(t), y(t), z(t))`,
 * puestas en el croquis como trazos.
 *
 * Trazos y no otra cosa a propósito: es lo que el croquis sabe pintar, ordenar por hondura,
 * mover, girar, estirar, licuar y exportar como modelo. Una superficie sale como su
 * **alambrada** —líneas de `y` constante y líneas de `x` constante—, que es como se dibuja
 * una superficie a mano en cualquier pizarra y como se lee mejor girándola; una curva sale
 * como un solo trazo. Los tres ejes van con ella, más finos y en gris.
 *
 * El sistema es el del croquis: `x` a la derecha, `y` hacia dentro, `z` arriba. Una unidad
 * de la función son [escala] unidades del croquis. Donde la función no existe o se sale
 * de la ventana de `z`, la alambrada se corta, igual que en la gráfica plana.
 *
 * Sin Android: entran fórmulas y límites, salen trazos. Ver [Formula].
 */
object Graficas3D {

    data class Superficie(
        val formula: String,
        val xDesde: Double = -3.0,
        val xHasta: Double = 3.0,
        val yDesde: Double = -3.0,
        val yHasta: Double = 3.0,
        val zDesde: Double = -3.0,
        val zHasta: Double = 3.0,
        val escala: Double = 40.0,
        /** Cuántas líneas de la alambrada por cada dirección. */
        val lineas: Int = 16
    )

    data class Curva(
        val fx: String,
        val fy: String,
        val fz: String,
        val tDesde: Double = 0.0,
        val tHasta: Double = 2 * Math.PI,
        val escala: Double = 40.0
    )

    /** Cuántas muestras lleva cada línea de la alambrada y la curva. */
    const val MUESTRAS = 64
    const val MUESTRAS_DE_CURVA = 400

    /** El color de los ejes: gris, para no pelearse con lo dibujado. */
    const val COLOR_DE_LOS_EJES = "#8a8f98"

    /** La alambrada de la superficie con sus ejes, o null si la fórmula no vale. */
    fun superficie(s: Superficie, color: String, grosor: Double): List<Trazo3D>? {
        val f = Formula.compilar(s.formula) ?: return null
        if (!f.variables.all { it == "x" || it == "y" }) return null
        if (!(s.xHasta > s.xDesde) || !(s.yHasta > s.yDesde) || !(s.zHasta > s.zDesde)) return null
        if (!(s.escala > 0.0) || s.lineas < 2) return null

        val salida = ArrayList<Trazo3D>()
        fun punto(x: Double, y: Double): Pt3? {
            val z = f.en(x, y)
            if (!z.isFinite() || z < s.zDesde || z > s.zHasta) return null
            return Pt3(x * s.escala, y * s.escala, z * s.escala)
        }
        fun recorrer(muestra: (Int) -> Pt3?) {
            var rama = ArrayList<Pt3>()
            for (i in 0..MUESTRAS) {
                val p = muestra(i)
                if (p == null) {
                    if (rama.size >= 2) salida += trazo(rama, color, grosor)
                    rama = ArrayList()
                } else rama += p
            }
            if (rama.size >= 2) salida += trazo(rama, color, grosor)
        }
        // Las líneas de y constante, barriendo la x; y las de x constante, barriendo la y.
        for (j in 0..s.lineas) {
            val y = s.yDesde + (s.yHasta - s.yDesde) * j / s.lineas
            recorrer { i -> punto(s.xDesde + (s.xHasta - s.xDesde) * i / MUESTRAS, y) }
        }
        for (j in 0..s.lineas) {
            val x = s.xDesde + (s.xHasta - s.xDesde) * j / s.lineas
            recorrer { i -> punto(x, s.yDesde + (s.yHasta - s.yDesde) * i / MUESTRAS) }
        }
        if (salida.isEmpty()) return null
        salida += ejes(
            s.xDesde * s.escala, s.xHasta * s.escala,
            s.yDesde * s.escala, s.yHasta * s.escala,
            s.zDesde * s.escala, s.zHasta * s.escala, grosor
        )
        return salida
    }

    /** La curva con sus ejes, o null si alguna fórmula no vale. */
    fun curva(c: Curva, color: String, grosor: Double): List<Trazo3D>? {
        val fx = Formula.compilar(c.fx) ?: return null
        val fy = Formula.compilar(c.fy) ?: return null
        val fz = Formula.compilar(c.fz) ?: return null
        if (listOf(fx, fy, fz).any { f -> !f.variables.all { it == "t" } }) return null
        if (!(c.tHasta > c.tDesde) || !(c.escala > 0.0)) return null

        val salida = ArrayList<Trazo3D>()
        var rama = ArrayList<Pt3>()
        var minimo = Pt3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var maximo = Pt3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (i in 0..MUESTRAS_DE_CURVA) {
            val t = c.tDesde + (c.tHasta - c.tDesde) * i / MUESTRAS_DE_CURVA
            val x = fx.enT(t)
            val y = fy.enT(t)
            val z = fz.enT(t)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
                if (rama.size >= 2) salida += trazo(rama, color, grosor)
                rama = ArrayList()
                continue
            }
            val p = Pt3(x * c.escala, y * c.escala, z * c.escala)
            rama += p
            minimo = Pt3(minOf(minimo.x, p.x), minOf(minimo.y, p.y), minOf(minimo.z, p.z))
            maximo = Pt3(maxOf(maximo.x, p.x), maxOf(maximo.y, p.y), maxOf(maximo.z, p.z))
        }
        if (rama.size >= 2) salida += trazo(rama, color, grosor)
        if (salida.isEmpty()) return null
        // Los ejes, hasta donde llegue la curva y como poco una unidad.
        val u = c.escala
        salida += ejes(
            minOf(minimo.x, -u), maxOf(maximo.x, u),
            minOf(minimo.y, -u), maxOf(maximo.y, u),
            minOf(minimo.z, -u), maxOf(maximo.z, u), grosor
        )
        return salida
    }

    /** Cuántos trazos son los ejes: tres rectas, tres puntas y las letras x (2), y (2) y z (1). */
    const val TRAZOS_DE_LOS_EJES = 11

    /**
     * Los tres ejes, por el origen, finos y grises, **con una punta pequeña en el
     * extremo positivo y su letra pasada la punta**. La punta mide una veinteava
     * parte del eje más corto —como la de los planos de la lista de figuras, no
     * la de una flecha de diagrama— y las letras van dibujadas con rayas, que es
     * lo único que el croquis sabe pintar: la x y la z de pie en el plano xz, la y
     * de pie en el plano yz.
     */
    internal fun ejes(
        x1: Double, x2: Double, y1: Double, y2: Double, z1: Double, z2: Double, grosor: Double
    ): List<Trazo3D> {
        val fino = (grosor * 0.4).coerceAtLeast(0.5)
        val xa = minOf(x1, 0.0); val xb = maxOf(x2, 0.0)
        val ya = minOf(y1, 0.0); val yb = maxOf(y2, 0.0)
        val za = minOf(z1, 0.0); val zb = maxOf(z2, 0.0)
        val gris = COLOR_DE_LOS_EJES
        fun eje(pts: List<Pt3>) = trazo(pts, gris, fino)
        val out = ArrayList<Trazo3D>(TRAZOS_DE_LOS_EJES)
        out += eje(listOf(Pt3(xa, 0.0, 0.0), Pt3(xb, 0.0, 0.0)))
        out += eje(listOf(Pt3(0.0, ya, 0.0), Pt3(0.0, yb, 0.0)))
        out += eje(listOf(Pt3(0.0, 0.0, za), Pt3(0.0, 0.0, zb)))

        val largo = (minOf(xb - xa, yb - ya, zb - za) * PUNTA_DEL_EJE).coerceAtLeast(grosor * 2)
        val ala = largo * ALA_DE_LA_PUNTA
        // Las puntas: una uve de tres puntos, con las alas abiertas en el plano del suelo
        // para la x y la y, y hacia los lados para la z.
        out += eje(listOf(Pt3(xb - largo, ala, 0.0), Pt3(xb, 0.0, 0.0), Pt3(xb - largo, -ala, 0.0)))
        out += eje(listOf(Pt3(ala, yb - largo, 0.0), Pt3(0.0, yb, 0.0), Pt3(-ala, yb - largo, 0.0)))
        out += eje(listOf(Pt3(ala, 0.0, zb - largo), Pt3(0.0, 0.0, zb), Pt3(-ala, 0.0, zb - largo)))

        // Las letras, de alto `h`, centradas pasada la punta.
        val h = largo * 1.6
        val m = h / 2
        val cx = xb + largo + h * 0.7
        out += eje(listOf(Pt3(cx - m, 0.0, m), Pt3(cx + m, 0.0, -m)))
        out += eje(listOf(Pt3(cx - m, 0.0, -m), Pt3(cx + m, 0.0, m)))
        val cy = yb + largo + h * 0.7
        out += eje(listOf(Pt3(0.0, cy - m, m), Pt3(0.0, cy, 0.0)))
        out += eje(listOf(Pt3(0.0, cy + m, m), Pt3(0.0, cy - m, -m)))
        val cz = zb + largo + h * 0.7
        out += eje(listOf(Pt3(-m, 0.0, cz + m), Pt3(m, 0.0, cz + m), Pt3(-m, 0.0, cz - m), Pt3(m, 0.0, cz - m)))
        return out
    }

    /** Lo que mide la punta de un eje, en tantos del eje más corto. */
    private const val PUNTA_DEL_EJE = 0.05

    /** Lo que se abre cada ala respecto del largo de la punta (tangente de 25°). */
    private const val ALA_DE_LA_PUNTA = 0.466

    private fun trazo(puntos: List<Pt3>, color: String, grosor: Double): Trazo3D =
        Trazo3D(randomId(), puntos, color, grosor, calibre = grosor)
}
