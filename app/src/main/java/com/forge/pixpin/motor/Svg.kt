package com.forge.pixpin.motor

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * El dibujo escrito como SVG.
 *
 * Aquí solo está **la sintaxis**: pasar de las órdenes que ya genera el motor a
 * la cadena de texto que entiende un SVG. La decisión de qué se dibuja y en qué
 * orden vive en [DrawSvg], que es la que necesita Android; esto es texto puro y
 * se comprueba sin dispositivo, comparando cadenas.
 *
 * ## Por qué sale tan barato
 *
 * El generador rugoso ([Rough]) ya devuelve las figuras como una lista de
 * [Op] —mover, recta, curva cúbica—, que es **exactamente** el repertorio de un
 * camino SVG:
 *
 * | El motor emite     | SVG escribe                |
 * |--------------------|----------------------------|
 * | `Op.Move`          | `M x y`                    |
 * | `Op.LineTo`        | `L x y`                    |
 * | `Op.CurveTo`       | `C x1 y1 x2 y2 x y`        |
 * | contorno del lápiz | `Q` por puntos medios      |
 * | relleno con huecos | `fill-rule="evenodd"`      |
 *
 * Es la misma correspondencia que hace falta para escribir dentro de un PDF
 * (`m`, `l`, `c`), así que lo que se escriba aquí sirve dos veces.
 *
 * ## El punto y la coma
 *
 * Los números van **siempre con punto decimal**. Es lo primero que rompe un
 * exportador escrito sin pensar en ello: en un móvil configurado en español,
 * `"%.2f"` sin locale escribe `3,14`, el visor lee dos números donde había uno
 * y el dibujo sale reventado — pero solo en los móviles que tienen esa
 * configuración, así que no se ve al probarlo. Por eso todo pasa por [num] y
 * [num] usa [Locale.ROOT].
 */
object Svg {

    /**
     * Cuántos decimales se escriben.
     *
     * Dos es de sobra: la unidad es el píxel de escena, así que el error máximo
     * es medio centésimo de píxel. Escribir los diecisiete que da un `Double`
     * multiplicaría por cinco el tamaño del archivo sin que nadie notara la
     * diferencia.
     */
    private const val DECIMALES = 2

    /** Un número tal como se escribe en un SVG: con punto y sin ceros de más. */
    fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val redondeado = (v * 100.0).roundToLong() / 100.0
        // Los enteros se escriben sin `.0`, que es la mitad de los números de un
        // camino y el ahorro se nota en un dibujo con muchos trazos.
        if (redondeado == floor(redondeado) && abs(redondeado) < 1e15) {
            return redondeado.toLong().toString()
        }
        return String.format(Locale.ROOT, "%.${DECIMALES}f", redondeado)
            .trimEnd('0').trimEnd('.')
    }

    /** Las órdenes del generador rugoso, como camino. */
    fun camino(ops: List<Op>): String = buildString {
        for (op in ops) when (op) {
            is Op.Move -> punto("M", op.x, op.y)
            is Op.LineTo -> punto("L", op.x, op.y)
            is Op.CurveTo -> {
                append("C")
                append(num(op.x1)).append(' ').append(num(op.y1)).append(' ')
                append(num(op.x2)).append(' ').append(num(op.y2)).append(' ')
                append(num(op.x)).append(' ').append(num(op.y))
            }
        }
    }.trim()

    /**
     * El contorno del lápiz, cerrado y **suavizado**.
     *
     * Es la misma costura que hace el renderizador en pantalla: cuadráticas que
     * pasan por los puntos medios de cada par, con cada punto de tirador. Tenía
     * que repetirse aquí y no reutilizarse porque allí devuelve un `Path` de
     * Android y aquí hace falta texto — pero la fórmula es la misma, y si una
     * cambia la otra tiene que cambiar con ella.
     */
    fun caminoSuaveCerrado(pts: List<Pt>): String {
        if (pts.isEmpty()) return ""
        if (pts.size < 4) return caminoCerrado(pts)
        return buildString {
            punto("M", pts[0].x, pts[0].y)
            cuadratica(pts[1], medio(pts[1], pts[2]))
            for (i in 2 until pts.size - 1) cuadratica(pts[i], medio(pts[i], pts[i + 1]))
            append('Z')
        }
    }

    /** Una polilínea cerrada a rectas. */
    fun caminoCerrado(pts: List<Pt>): String {
        if (pts.isEmpty()) return ""
        return buildString {
            punto("M", pts[0].x, pts[0].y)
            for (i in 1 until pts.size) punto("L", pts[i].x, pts[i].y)
            append('Z')
        }
    }

    /**
     * Varios anillos en un solo camino.
     *
     * Con `fill-rule="evenodd"` puesto en el atributo, esto es lo que hace que
     * el agujero de un relleno sea un agujero de verdad y no una mancha más.
     * Ver la nota equivalente del renderizador.
     */
    fun caminoDeAnillos(anillos: List<List<Pt>>): String =
        anillos.filter { it.size >= 3 }.joinToString("") { caminoCerrado(it) }

    /** Igual, pero admitiendo contornos de dos puntos: los usa el texto en curvas. */
    fun caminoDeContornos(contornos: List<List<Pt>>): String =
        contornos.filter { it.size >= 2 }.joinToString("") { caminoCerrado(it) }

    /** El color, en `#rrggbb`. La transparencia va aparte, en [alfa]. */
    fun hex(argb: Int): String =
        String.format(Locale.ROOT, "#%06x", argb and 0xFFFFFF)

    /** La transparencia de un color ARGB, de 0 a 1, como la escribe un SVG. */
    fun alfa(argb: Int): Double = ((argb ushr 24) and 0xFF) / 255.0

    /**
     * El patrón de guiones de un trazo, o null si es continuo.
     *
     * Son los mismos números que el `DashPathEffect` del renderizador, para que
     * una raya a trazos se vea igual de espaciada en el SVG que en la pantalla.
     */
    fun guionesDe(estilo: StrokeStyle, grosor: Double): String? = when (estilo) {
        StrokeStyle.SOLID -> null
        StrokeStyle.DASHED -> "${num(8.0)} ${num(8 + grosor)}"
        StrokeStyle.DOTTED -> "${num(1.5)} ${num(6 + grosor)}"
    }

    /** Texto seguro dentro de un atributo o de un nodo. */
    fun escapar(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            // Los caracteres de control no son válidos ni escapados: se tiran.
            else -> if (c.code >= 0x20 || c == '\t' || c == '\n') append(c)
        }
    }

    /**
     * El archivo entero.
     *
     * El `viewBox` lleva las coordenadas de la escena tal cual, así que **nada
     * hay que desplazar**: los caminos se escriben con los mismos números que
     * usa el motor por dentro. `width` y `height` en píxeles son lo que hace que
     * al insertarlo en un documento entre con un tamaño razonable en vez de
     * ocupar la hoja entera.
     */
    fun documento(
        caja: Bounds,
        fondo: String?,
        cuerpo: String,
        titulo: String? = null
    ): String = buildString {
        val w = caja.width
        val h = caja.height
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
        append("xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
        append("width=\"${num(w)}\" height=\"${num(h)}\" ")
        append("viewBox=\"${num(caja.x1)} ${num(caja.y1)} ${num(w)} ${num(h)}\">\n")
        if (titulo != null) append("<title>${escapar(titulo)}</title>\n")
        if (fondo != null) {
            append("<rect x=\"${num(caja.x1)}\" y=\"${num(caja.y1)}\" ")
            append("width=\"${num(w)}\" height=\"${num(h)}\" fill=\"$fondo\"/>\n")
        }
        append(cuerpo)
        if (!cuerpo.endsWith("\n")) append('\n')
        append("</svg>\n")
    }

    // -- internos -----------------------------------------------------------

    private fun StringBuilder.punto(orden: String, x: Double, y: Double) {
        append(orden).append(num(x)).append(' ').append(num(y))
    }

    private fun StringBuilder.cuadratica(tirador: Pt, hasta: Pt) {
        append('Q').append(num(tirador.x)).append(' ').append(num(tirador.y)).append(' ')
        append(num(hasta.x)).append(' ').append(num(hasta.y))
    }

    private fun medio(a: Pt, b: Pt) = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
}
