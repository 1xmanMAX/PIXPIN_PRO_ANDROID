package com.forge.pixpin.motor

/**
 * **Los sublienzos de una página, pegados a ella en la página web.**
 *
 * Lo pidió el usuario el 13-sep-2026: al compartir como web una página con sublienzos, que no
 * salgan como hojas sueltas sino **como en el lector de PDF** —la página en el centro, el
 * recuadro de cada zona, y su sublienzo al lado unido por una línea—.
 *
 * Va **dentro del mismo SVG de la página**: se ensancha su `viewBox` para hacer sitio a los
 * lados y cada sublienzo entra como un `<svg>` anidado, en vectores. Así el visor no necesita
 * nada nuevo: se pasea, se amplía y se mide igual que la página, y al acercarse lo resuelto
 * en el sublienzo se ve nítido. Es texto puro, sin Android, para poder probarlo en la JVM.
 */
object SublienzosWeb {

    /**
     * Un sublienzo para pegar: [caja] es su recuadro en la página, en unidades del SVG.
     *
     * [foto], si no es nula, es dónde estaba la foto de la zona dentro del sublienzo, **que no
     * viene en [svg]**: la zona es un trozo de la página, que ya está en el documento, así que se
     * toma de ella con un `<use>` recortado en vez de meter la misma imagen otra vez. Con varias
     * zonas por página era casi todo el peso del archivo (usuario, 14-sep-2026).
     */
    class Adjunto(val caja: Bounds, val svg: String, val foto: Bounds? = null)


    private const val AZUL = "#1971c2"

    /** El SVG de la página con sus [adjuntos] a los lados. Sin adjuntos, tal cual. */
    fun adjuntar(pagina: String, adjuntos: List<Adjunto>, sello: String = "p"): String {
        if (adjuntos.isEmpty()) return pagina
        val apertura = Regex("<svg\\b[^>]*>").find(pagina) ?: return pagina
        val vb = viewBoxDe(apertura.value) ?: return pagina
        val (x, y, w, h) = vb
        val ordenados = adjuntos.sortedBy { it.caja.midY }
        val lado = w * LADO
        val hueco = w * HUECO
        // **Cada uno por el lado más cercano a su zona**, igual que en el lector del teléfono.
        // Ver [SitioDeSublienzos], que es quien lo reparte y donde está probado.
        val caja = Bounds(x, y, x + w, y + h)
        val puestos = SitioDeSublienzos.colocar(
            caja, ordenados.map { it.caja }, ordenados.map { proporcionDe(it.svg) }, lado, hueco
        )
        val todo = SitioDeSublienzos.todoJunto(caja, puestos, hueco)
        val nuevoX = todo.x1
        val nuevoY = todo.y1
        val nuevoAncho = todo.width
        val nuevoAlto = todo.height

        val grosor = w * 0.0028
        val grupo = StringBuilder("<g class=\"sublienzos\">\n")
        for ((i, a) in ordenados.withIndex()) {
            val p = puestos[i]
            // La línea sale del borde del sublienzo que mira a la página y llega al de la zona
            // que mira a él: con los cuatro lados, ya no vale mirar solo izquierda y derecha.
            val desdeX = p.salidaX
            val cy = p.salidaY
            val hastaX = when (p.lado) {
                SitioDeSublienzos.Lado.IZQUIERDA -> a.caja.x1
                SitioDeSublienzos.Lado.DERECHA -> a.caja.x2
                else -> a.caja.midX
            }
            val hastaY = when (p.lado) {
                SitioDeSublienzos.Lado.ARRIBA -> a.caja.y1
                SitioDeSublienzos.Lado.ABAJO -> a.caja.y2
                else -> a.caja.midY
            }
            grupo.append("<line x1=\"").append(Svg.num(desdeX)).append("\" y1=\"").append(Svg.num(cy))
                .append("\" x2=\"").append(Svg.num(hastaX)).append("\" y2=\"").append(Svg.num(hastaY))
                .append("\" stroke=\"").append(AZUL).append("\" stroke-width=\"").append(Svg.num(grosor)).append("\"/>\n")
            grupo.append("<circle cx=\"").append(Svg.num(hastaX)).append("\" cy=\"").append(Svg.num(hastaY))
                .append("\" r=\"").append(Svg.num(grosor * 2.2)).append("\" fill=\"").append(AZUL).append("\"/>\n")
            grupo.append("<rect x=\"").append(Svg.num(p.x)).append("\" y=\"").append(Svg.num(p.y))
                .append("\" width=\"").append(Svg.num(p.ancho)).append("\" height=\"").append(Svg.num(p.alto))
                .append("\" rx=\"").append(Svg.num(lado * 0.04)).append("\" fill=\"#ffffff\" stroke=\"").append(AZUL)
                .append("\" stroke-width=\"").append(Svg.num(grosor)).append("\"/>\n")
            val margen = lado * 0.03
            val usar = a.foto?.let { f -> deLaPagina("pagina-$sello", "$sello-sl$i-", f, a.caja) }
            grupo.append(anidado(a.svg, "$sello-sl$i-", p.x + margen, p.y + margen, p.ancho - 2 * margen, p.alto - 2 * margen, usar))
        }
        grupo.append("</g>\n")

        val nuevaApertura = apertura.value
            .replace(Regex("\\sviewBox=\"[^\"]*\""), " viewBox=\"${Svg.num(nuevoX)} ${Svg.num(nuevoY)} ${Svg.num(nuevoAncho)} ${Svg.num(nuevoAlto)}\"")
            .replace(Regex("\\swidth=\"[^\"]*\""), " width=\"${Svg.num(nuevoAncho)}\"")
            .replace(Regex("\\sheight=\"[^\"]*\""), " height=\"${Svg.num(nuevoAlto)}\"")
        val cierre = pagina.lastIndexOf("</svg>")
        if (cierre < apertura.range.last) return pagina
        val cuerpo = pagina.substring(apertura.range.last + 1, cierre)
        // La página en un grupo con nombre, para que los sublienzos puedan tomar su zona de ahí.
        val envuelto = if (adjuntos.any { it.foto != null }) "<g id=\"pagina-$sello\">$cuerpo</g>\n" else cuerpo
        return pagina.substring(0, apertura.range.first) + nuevaApertura + envuelto + grupo + pagina.substring(cierre)
    }

    /**
     * La zona [zona] de la página, puesta donde estaba la foto [foto] del sublienzo y recortada a
     * ella: un `<use>` del grupo [grupo] con la escala y el desplazamiento que llevan la una a la otra.
     */
    internal fun deLaPagina(grupo: String, prefijo: String, foto: Bounds, zona: Bounds): String? {
        if (zona.width <= 0 || zona.height <= 0) return null
        val sx = foto.width / zona.width
        val sy = foto.height / zona.height
        val tx = foto.x1 - zona.x1 * sx
        val ty = foto.y1 - zona.y1 * sy
        return "<clipPath id=\"${prefijo}zona\"><rect x=\"${Svg.num(foto.x1)}\" y=\"${Svg.num(foto.y1)}\" " +
            "width=\"${Svg.num(foto.width)}\" height=\"${Svg.num(foto.height)}\"/></clipPath>" +
            "<g clip-path=\"url(#${prefijo}zona)\"><use href=\"#$grupo\" xlink:href=\"#$grupo\" " +
            "transform=\"matrix(${num4(sx)} 0 0 ${num4(sy)} ${Svg.num(tx)} ${Svg.num(ty)})\"/></g>\n"
    }

    private fun num4(v: Double): String = String.format(java.util.Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.')

    /**
     * El sublienzo como `<svg>` anidado en su hueco, con sus ids renombrados para no chocar. [usar]
     * va justo encima de su fondo: es la foto de la zona tomada de la página. Ver [deLaPagina].
     */
    internal fun anidado(svg: String, prefijo: String, x: Double, y: Double, ancho: Double, alto: Double, usar: String? = null): String {
        val limpio = svg.trim().removePrefix("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").trim()
        val apertura = Regex("<svg\\b[^>]*>").find(limpio) ?: return ""
        val cierre = limpio.lastIndexOf("</svg>").takeIf { it > apertura.range.last } ?: return ""
        val vb = Regex("\\sviewBox=\"([^\"]*)\"").find(apertura.value)?.groupValues?.get(1) ?: return ""
        val cuerpo = limpio.substring(apertura.range.last + 1, cierre)
            .replace("id=\"", "id=\"$prefijo")
            .replace("url(#", "url(#$prefijo")
            .replace("href=\"#", "href=\"#$prefijo")
            .let { c ->
                if (usar == null) c else {
                    // Detrás del fondo del sublienzo no se vería: justo después de él.
                    val t = c.trimStart()
                    val finDelFondo = if (t.startsWith("<rect")) t.indexOf("/>") + 2 else 0
                    t.substring(0, finDelFondo) + usar + t.substring(finDelFondo)
                }
            }
        return "<svg x=\"${Svg.num(x)}\" y=\"${Svg.num(y)}\" width=\"${Svg.num(ancho)}\" height=\"${Svg.num(alto)}\" " +
            "viewBox=\"$vb\" preserveAspectRatio=\"xMidYMid meet\" overflow=\"hidden\">" + cuerpo + "</svg>\n"
    }

    private fun viewBoxDe(apertura: String): List<Double>? =
        Regex("\\sviewBox=\"([^\"]*)\"").find(apertura)?.groupValues?.get(1)
            ?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }

    /** Alto / ancho de un SVG, por su `viewBox`. */
    private fun proporcionDe(svg: String): Double {
        val a = Regex("<svg\\b[^>]*>").find(svg)?.value ?: return 1.0
        val vb = viewBoxDe(a) ?: return 1.0
        return vb[3] / vb[2]
    }

    /** El lado de un sublienzo y el aire alrededor, en fracciones del ancho de la página. */
    private const val LADO = 0.36
    private const val HUECO = 0.035
}
