package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.min

/**
 * **Dónde se pone cada sublienzo alrededor de su página.**
 *
 * Un sublienzo es la zona de una página resuelta aparte, y lo que se quiere ver es **de dónde
 * salió**: la miniatura pegada a su recuadro, con una línea que los une. Iban alternando
 * izquierda y derecha por orden de llegada, y eso hacía justo lo contrario de lo que se espera:
 * una zona del borde izquierdo salía enseñada a la derecha, cruzando la página por encima de las
 * demás líneas (usuario, 14-sep-2026: «lo que está en el lado izquierdo se presenta en el lado
 * derecho y eso no es lógico»).
 *
 * Ahora cada uno va **al lado más cercano de los cuatro** —izquierda, derecha, arriba o abajo, el
 * que tenga su zona más a mano— y **a la altura (o a la anchura) de su zona**. Si ahí ya hay otro,
 * se corre lo mínimo para no montarse; si su lado se llena, se prueba el siguiente más cercano.
 * Nunca se pierde uno: si los cuatro lados están llenos, el suyo crece.
 *
 * Es una función de geometría y nada más: la usan el lector de PDF (`pdf/SublienzosDelPdf.kt`) y
 * la página web (`motor/SublienzosWeb.kt`), así que los dos colocan igual y se prueba una vez.
 */
object SitioDeSublienzos {

    enum class Lado {
        IZQUIERDA, DERECHA, ARRIBA, ABAJO;

        /** Si este lado es una banda vertical (los sublienzos se reparten por su altura). */
        val vertical: Boolean get() = this == IZQUIERDA || this == DERECHA
    }

    /** Un sublienzo colocado: en qué lado y qué hueco ocupa, en las unidades de la página. */
    data class Puesto(
        val lado: Lado,
        val x: Double,
        val y: Double,
        val ancho: Double,
        val alto: Double
    ) {
        /** De dónde sale la línea que va a la zona: el centro del borde que mira a la página. */
        val salidaX: Double get() = when (lado) {
            Lado.IZQUIERDA -> x + ancho
            Lado.DERECHA -> x
            else -> x + ancho / 2
        }
        val salidaY: Double get() = when (lado) {
            Lado.ARRIBA -> y + alto
            Lado.ABAJO -> y
            else -> y + alto / 2
        }
    }

    /**
     * Coloca un sublienzo por zona.
     *
     * [pagina] es la caja de la página; [zonas], el recuadro de cada zona dentro de ella;
     * [proporciones], el alto / ancho de cada sublienzo. [lado] es el lado de la caja en que se
     * mete cada miniatura (se ajusta dentro conservando su forma) y [hueco] el aire que se deja
     * entre ellas y con la página. Lo que se devuelve va **en el orden de entrada**.
     *
     * Los sublienzos se reparten empezando por los que tienen su lado más a mano: el que está
     * pegado a un borde tiene menos sitios buenos que el del centro, así que elige antes.
     */
    fun colocar(
        pagina: Bounds,
        zonas: List<Bounds>,
        proporciones: List<Double>,
        lado: Double,
        hueco: Double
    ): List<Puesto> {
        if (zonas.isEmpty()) return emptyList()
        val medidas = zonas.indices.map { i ->
            val prop = proporciones.getOrElse(i) { 1.0 }.takeIf { it.isFinite() && it > 0 } ?: 1.0
            // Cada uno cabe en un cuadrado de [lado]: así una banda de arriba no se come la
            // página por traer un sublienzo alargado.
            if (prop >= 1.0) (lado / prop) to lado else lado to (lado * prop)
        }
        // Lo ocupado de cada banda, como trozos del eje libre.
        val ocupado = Lado.entries.associateWith { ArrayList<Pair<Double, Double>>() }
        val salida = arrayOfNulls<Puesto>(zonas.size)
        val orden = zonas.indices.sortedBy { distancias(pagina, zonas[it]).values.min() }
        for (i in orden) {
            val (an, al) = medidas[i]
            val zona = zonas[i]
            val porCercania = distancias(pagina, zona).entries.sortedBy { it.value }.map { it.key }
            var puesto: Puesto? = null
            for (l in porCercania) {
                puesto = enEsteLado(pagina, zona, l, an, al, hueco, ocupado.getValue(l), forzar = false)
                if (puesto != null) break
            }
            // Los cuatro llenos: el más cercano crece. Perder un sublienzo no es una opción.
            val fin = puesto ?: enEsteLado(pagina, zona, porCercania.first(), an, al, hueco, ocupado.getValue(porCercania.first()), forzar = true)!!
            val eje = if (fin.lado.vertical) fin.y to (fin.y + fin.alto) else fin.x to (fin.x + fin.ancho)
            ocupado.getValue(fin.lado) += eje
            salida[i] = fin
        }
        return salida.map { it!! }
    }

    /** Lo que hay de la zona a cada borde de la página: por ahí se elige lado. */
    private fun distancias(pagina: Bounds, zona: Bounds): Map<Lado, Double> = mapOf(
        Lado.IZQUIERDA to (zona.x1 - pagina.x1),
        Lado.DERECHA to (pagina.x2 - zona.x2),
        Lado.ARRIBA to (zona.y1 - pagina.y1),
        Lado.ABAJO to (pagina.y2 - zona.y2)
    )

    /**
     * El hueco de este lado más cercano a la zona, o null si no cabe ninguno.
     *
     * Con [forzar], se pone detrás de todo lo que ya hay aunque se salga de la página: es el
     * último recurso cuando los cuatro lados están llenos.
     */
    private fun enEsteLado(
        pagina: Bounds,
        zona: Bounds,
        lado: Lado,
        an: Double,
        al: Double,
        hueco: Double,
        ocupado: List<Pair<Double, Double>>,
        forzar: Boolean
    ): Puesto? {
        val vertical = lado.vertical
        val medida = if (vertical) al else an
        // El eje libre: la altura de la página en los lados, su anchura arriba y abajo.
        val desde = if (vertical) pagina.y1 else pagina.x1
        val hasta = if (vertical) pagina.y2 else pagina.x2
        val centroDeLaZona = if (vertical) zona.midY else zona.midX
        val quiere = centroDeLaZona - medida / 2
        val tope = hasta - medida
        val sitio = if (forzar) {
            (ocupado.maxOfOrNull { it.second + hueco } ?: desde).coerceAtLeast(desde)
        } else {
            elHuecoMasCerca(quiere, medida, desde, tope, hueco, ocupado) ?: return null
        }
        // Y la banda, fuera de la página por su lado.
        return when (lado) {
            Lado.IZQUIERDA -> Puesto(lado, pagina.x1 - hueco - an, sitio, an, al)
            Lado.DERECHA -> Puesto(lado, pagina.x2 + hueco, sitio, an, al)
            Lado.ARRIBA -> Puesto(lado, sitio, pagina.y1 - hueco - al, an, al)
            Lado.ABAJO -> Puesto(lado, sitio, pagina.y2 + hueco, an, al)
        }
    }

    /**
     * El sitio libre más cercano a [quiere] dentro de `[desde, tope]`, para algo que mide
     * [medida] y dejando [hueco] con lo ya puesto. Null si no hay ninguno.
     *
     * Se prueba lo que se quiere y, si choca, **pegarse a cada obstáculo por arriba y por abajo**:
     * de todos esos sitios se coge el que menos se aparte de lo que se quería, que es lo que hace
     * que la miniatura acabe lo más cerca posible de su zona.
     */
    private fun elHuecoMasCerca(
        quiere: Double,
        medida: Double,
        desde: Double,
        tope: Double,
        hueco: Double,
        ocupado: List<Pair<Double, Double>>
    ): Double? {
        if (tope < desde) return null
        fun libre(p: Double) = ocupado.none { (a, b) -> p < b + hueco && p + medida + hueco > a }
        val candidatos = ArrayList<Double>(2 * ocupado.size + 1)
        candidatos += quiere.coerceIn(desde, tope)
        for ((a, b) in ocupado) {
            candidatos += (b + hueco).coerceIn(desde, tope)
            candidatos += (a - hueco - medida).coerceIn(desde, tope)
        }
        return candidatos.filter { libre(it) }.minByOrNull { abs(it - quiere) }
    }

    /** La caja que ocupa todo junto —página y sublienzos— con [margen] de aire alrededor. */
    fun todoJunto(pagina: Bounds, puestos: List<Puesto>, margen: Double): Bounds {
        var x1 = pagina.x1
        var y1 = pagina.y1
        var x2 = pagina.x2
        var y2 = pagina.y2
        for (p in puestos) {
            x1 = min(x1, p.x - margen); y1 = min(y1, p.y - margen)
            x2 = maxOf(x2, p.x + p.ancho + margen); y2 = maxOf(y2, p.y + p.alto + margen)
        }
        return Bounds(x1, y1, x2, y2)
    }
}
