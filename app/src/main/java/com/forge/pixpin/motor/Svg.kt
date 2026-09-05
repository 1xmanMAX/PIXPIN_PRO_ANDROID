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
 * | contorno del lápiz | `C` cosido por puntos medios |
 * | `Op.Cerrar`        | `Z`                        |
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
            Op.Cerrar -> append('Z')
        }
    }.trim()

    /**
     * Los cuatro constructores de camino, ya en SVG.
     *
     * La geometría no se hace aquí: se pide a [Caminos], que es donde vive para
     * las tres salidas. Aquí solo queda escribirla. Cuando esto tenía su propia
     * costura de cuadráticas, era una copia de la del renderizador esperando a
     * separarse de ella.
     */
    fun caminoSuaveCerrado(pts: List<Pt>): String = camino(opsSuaveCerrado(pts))

    /**
     * **El contorno del lápiz, escrito para que pese poco.**
     *
     * Es la misma curva que [caminoSuaveCerrado] —cosida por los puntos medios— pero escrita
     * como se escribe cuando hay que mandar setecientos trazos de letra manuscrita en un
     * archivo: en **cuadráticas** (`q`, cuatro números por punto donde la cúbica gastaba
     * seis; la cúbica de allí era una cuadrática convertida, así que la forma es idéntica),
     * **relativas** (cada punto es lo que cambia respecto del anterior, y un salto de tres
     * píxeles son dos cifras) y a **un decimal**, que en píxeles de escena es más fino de lo
     * que se puede ver. Con eso, un punto pasa de cuarenta y seis bytes a doce.
     *
     * Las diferencias se calculan sobre los valores **ya redondeados**, para que el error de
     * cada uno no se vaya sumando a lo largo del trazo.
     */
    fun caminoDelLapiz(pts: List<Pt>): String {
        if (pts.isEmpty()) return ""
        if (pts.size < 4) return camino(opsDePuntos(pts, cerrado = true))
        val sb = StringBuilder(pts.size * 14)
        var cx = redondeo(pts[0].x)
        var cy = redondeo(pts[0].y)
        sb.append('M').append(num1(cx)).append(' ').append(num1(cy))
        var tiradorX = pts[1].x
        var tiradorY = pts[1].y
        var i = 2
        while (i < pts.size) {
            val hastaX = redondeo((pts[i - 1].x + pts[i].x) / 2)
            val hastaY = redondeo((pts[i - 1].y + pts[i].y) / 2)
            val tx = redondeo(tiradorX)
            val ty = redondeo(tiradorY)
            sb.append('q').append(num1(tx - cx)).append(' ').append(num1(ty - cy))
                .append(' ').append(num1(hastaX - cx)).append(' ').append(num1(hastaY - cy))
            cx = hastaX
            cy = hastaY
            tiradorX = pts[i].x
            tiradorY = pts[i].y
            i++
        }
        sb.append('z')
        return sb.toString()
    }

    private fun redondeo(v: Double): Double = (v * 10.0).roundToLong() / 10.0

    /** Un número a un decimal, sin ceros de más y con punto. */
    private fun num1(v: Double): String {
        if (!v.isFinite()) return "0"
        val r = redondeo(v)
        if (r == floor(r) && abs(r) < 1e15) return r.toLong().toString()
        return String.format(Locale.ROOT, "%.1f", r)
    }

    fun caminoCerrado(pts: List<Pt>): String = camino(opsDePuntos(pts, cerrado = true))

    fun caminoDeAnillos(anillos: List<List<Pt>>): String = camino(opsDeAnillos(anillos, 3))

    /** Igual, pero admitiendo contornos de dos puntos: los usa el texto en curvas. */
    fun caminoDeContornos(contornos: List<List<Pt>>): String =
        camino(opsDeAnillos(contornos, 2))

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

}
