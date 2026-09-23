package com.forge.pixpin.motor

/**
 * **Entre punto y punto, curva y no recta** (23-sep-2026). El usuario, con el grafito: «dibujo
 * una zona curva y sale como un polígono de varias líneas rectas, se nota en las esquinas».
 *
 * El lápiz da una muestra cada tantos milisegundos, y en un trazo rápido quedan separadas; unidas
 * con rectas, cada muestra es una esquina. Aquí se unen con una **Catmull-Rom centrípeta**, que
 * **pasa por todas y cada una de las muestras** —no mueve ninguna: no es alisar ni corregir, lo
 * que se trazó sigue ahí tal cual— y solo decide cómo se va de una a la siguiente, siguiendo la
 * dirección que traía la mano. La centrípeta (α = ½) es la que no hace lazos ni se pasa de largo
 * cuando dos muestras caen muy juntas y la siguiente lejos, que es justo lo que pasa al frenar.
 */
object CurvaDelTrazo {

    /** Como mucho, tantos trocitos entre dos muestras: una muestra suelta muy lejos no dispara la cuenta. */
    private const val TOPE_POR_TRAMO = 48

    /**
     * Los puntos de la curva, cada [paso] unidades como mucho, con la presión repartida igual.
     * Las muestras originales van todas, en su sitio exacto.
     */
    fun porLosPuntos(puntos: List<Pt>, presiones: List<Double>?, paso: Double): Pair<List<Pt>, List<Double>?> {
        // Fuera las muestras repetidas: dos iguales seguidas no dicen hacia dónde se va.
        val pts = ArrayList<Pt>(puntos.size)
        val prs = if (presiones != null && presiones.size == puntos.size) ArrayList<Double>(puntos.size) else null
        for (i in puntos.indices) {
            val p = puntos[i]
            val ultimo = pts.lastOrNull()
            if (ultimo != null && Math.hypot(p.x - ultimo.x, p.y - ultimo.y) < 1e-6) continue
            pts += p
            prs?.add(presiones!![i])
        }
        if (pts.size < 3 || paso <= 0.0) return pts to prs
        val sale = ArrayList<Pt>(pts.size * 4)
        val salePr = prs?.let { ArrayList<Double>(pts.size * 4) }
        sale += pts[0]; salePr?.add(prs[0])
        for (i in 0 until pts.size - 1) {
            val p1 = pts[i]; val p2 = pts[i + 1]
            // En las puntas no hay vecino: se refleja el que hay, así la curva sale derecha hacia fuera.
            val p0 = if (i > 0) pts[i - 1] else Pt(2 * p1.x - p2.x, 2 * p1.y - p2.y)
            val p3 = if (i + 2 < pts.size) pts[i + 2] else Pt(2 * p2.x - p1.x, 2 * p2.y - p1.y)
            val largo = Math.hypot(p2.x - p1.x, p2.y - p1.y)
            val n = Math.ceil(largo / paso).toInt().coerceIn(1, TOPE_POR_TRAMO)
            for (k in 1..n) {
                val u = k.toDouble() / n
                // La última de cada tramo es la muestra misma, sin cuentas que la muevan un pelo.
                sale += if (k == n) p2 else centripeta(p0, p1, p2, p3, u)
                salePr?.add(prs[i] + (prs[i + 1] - prs[i]) * u)
            }
        }
        return sale to salePr
    }

    /** Un punto del tramo p1→p2 en [u] (0…1), con la forma de Barry y Goldman. */
    fun centripeta(p0: Pt, p1: Pt, p2: Pt, p3: Pt, u: Double): Pt {
        fun nudo(a: Pt, b: Pt) = Math.sqrt(Math.hypot(b.x - a.x, b.y - a.y)).coerceAtLeast(1e-4)
        val t0 = 0.0
        val t1 = t0 + nudo(p0, p1)
        val t2 = t1 + nudo(p1, p2)
        val t3 = t2 + nudo(p2, p3)
        val t = t1 + (t2 - t1) * u
        fun mezcla(a: Pt, b: Pt, ta: Double, tb: Double): Pt {
            val wa = (tb - t) / (tb - ta); val wb = (t - ta) / (tb - ta)
            return Pt(a.x * wa + b.x * wb, a.y * wa + b.y * wb)
        }
        val a1 = mezcla(p0, p1, t0, t1)
        val a2 = mezcla(p1, p2, t1, t2)
        val a3 = mezcla(p2, p3, t2, t3)
        val b1 = mezcla(a1, a2, t0, t2)
        val b2 = mezcla(a2, a3, t1, t3)
        return mezcla(b1, b2, t1, t2)
    }
}
