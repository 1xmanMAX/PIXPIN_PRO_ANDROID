package com.forge.pixpin.motor

/**
 * **Una hoja de PDF como SVG, con el texto de verdad** (30-sep-2026).
 *
 * El usuario: al exportar a página web un PDF anotado en el lector, las hojas iban como fotos —un
 * PDF de cien hojas y 3 MB salía de 14 o 15— y no se podía buscar nada. Quería lo que hace el
 * lienzo con un PDF: pararse a mitad de pintarlo y quedarse con lo que va **encima del papel**
 * —texto, líneas, imágenes— como cosas de verdad ([PlanoDePdf]). Aquí eso se escribe como un SVG
 * que va dentro de la página: se ve nítido a cualquier aumento, pesa poco, y **el texto es texto**,
 * así que el «buscar» del navegador lo encuentra, se selecciona y se copia.
 *
 * - Los caminos van en enteros de 1/[PASOS] de punto y como diferencias (`l3 -12`): lo corto.
 * - El texto, en tiras: los trozos seguidos con la misma letra y en la misma línea base van en un
 *   solo `<text>` estirado a lo que ocupaba en el PDF (`textLength`), que la letra del navegador no
 *   es la del documento. Es lo que hace el visor del lienzo ([VisorPlano]), pero en el documento.
 * - Cada imagen se escribe una vez aunque se ponga en varios sitios, también en varias hojas de la
 *   misma página ([YaPuestas]): un logotipo en cada hoja pesaba cien veces.
 * - Las capas apagadas no se escriben.
 *
 * Sin Android: se prueba en la JVM.
 */
object PlanoSvg {

    /** Pasos por punto de los caminos: un dieciseisavo de punto, invisible a cualquier aumento razonable. */
    const val PASOS = 16

    /** El estilo que necesitan las hojas; una vez por página, no por hoja. */
    /**
     * Las letras, primero **las que miden lo mismo que las de los PDF** (Times, Helvetica, Courier:
     * las de Windows y Mac, y sus gemelas libres Liberation/Tinos/Arimo/Cousine). Con la genérica
     * del navegador —en Linux, DejaVu, mucho más ancha— la línea se apretaba para caber y las
     * palabras se veían pegadas (30-sep-2026).
     */
    const val ESTILO =
        "svg.hoja-svg{display:block;width:100%;height:auto;background:#fff}" +
            ".hoja-svg text{white-space:pre;font-family:Arial,Helvetica,\"Liberation Sans\",Arimo,\"Nimbus Sans\",Roboto,sans-serif}" +
            ".hoja-svg .fs{font-family:\"Times New Roman\",Times,\"Liberation Serif\",Tinos,\"Nimbus Roman\",\"Noto Serif\",serif}" +
            ".hoja-svg .fm{font-family:\"Courier New\",Courier,\"Liberation Mono\",Cousine,\"Nimbus Mono PS\",monospace}" +
            ".hoja-svg .fc{font-family:\"Arial Narrow\",\"Helvetica Neue Condensed\",\"Liberation Sans Narrow\",\"Roboto Condensed\",sans-serif}" +
            ".hoja-svg .n{font-weight:bold}.hoja-svg .i{font-style:italic}"

    /**
     * El SVG de [plano], de [anchoCss] píxeles de ancho (el alto sale de la hoja). [id] distingue las
     * imágenes y máscaras de esta hoja de las de las otras de la misma página. [idioma], para que la
     * voz del navegador lea el texto en el suyo.
     */
    fun aSvg(plano: PlanoDePdf.Plano, anchoCss: Int, id: String, idioma: String? = null, yaPuestas: YaPuestas = YaPuestas()): String {
        val altoCss = anchoCss * plano.alto / plano.ancho
        val sb = StringBuilder(4096)
        sb.append("<svg class=\"hoja-svg\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            .append(n(plano.ancho)).append(' ').append(n(plano.alto))
            .append("\" width=\"").append(anchoCss).append("\" height=\"").append(n(altoCss)).append('"')
        if (idioma != null) sb.append(" lang=\"").append(idioma).append('"')
        sb.append('>')
        val seVe = { capa: Int -> plano.capas.getOrNull(capa)?.encendida != false }

        imagenes(plano, id, seVe, sb, yaPuestas)

        // Los caminos, en enteros: el grupo los lleva de pasos a puntos.
        val k = PlanoDePdf.FINEZA / PASOS
        val caminos = StringBuilder()
        for (b in plano.brochas) {
            if (!seVe(b.capa) || b.ops.isEmpty()) continue
            val d = camino(b, k)
            if (d.isEmpty()) continue
            caminos.append("<path d=\"").append(d).append('"')
            if (b.relleno) {
                if (b.color != 0) caminos.append(" fill=\"").append(hex(b.color)).append('"')
                if (b.parImpar) caminos.append(" fill-rule=\"evenodd\"")
            } else {
                caminos.append(" fill=\"none\" stroke=\"").append(hex(b.color)).append('"')
                // Un pelo (grosor 0) es lo más fino que dibuje la pantalla: una raya fina fija.
                if (b.grosor <= 0.0) caminos.append(" stroke-width=\"0.8\" vector-effect=\"non-scaling-stroke\"")
                else caminos.append(" stroke-width=\"").append(n(b.grosor * PASOS)).append('"')
                if (b.raya.isNotEmpty() && b.raya.any { it > 0 }) {
                    caminos.append(" stroke-dasharray=\"").append(b.raya.joinToString(" ") { n(it * PASOS) }).append('"')
                }
            }
            if (b.alfa < 0.999) caminos.append(" opacity=\"").append(n(b.alfa)).append('"')
            caminos.append("/>")
        }
        if (caminos.isNotEmpty()) sb.append("<g transform=\"scale(").append(n(1.0 / PASOS)).append(")\">").append(caminos).append("</g>")

        textos(plano, seVe, sb)
        return sb.append("</svg>").toString()
    }

    /** Las órdenes de una brocha como `d` de un camino, en diferencias. */
    private fun camino(b: PlanoDePdf.Brocha, k: Int): String {
        val d = StringBuilder(b.ops.size * 6)
        var p = 0
        var ux = 0
        var uy = 0
        // Dónde empezó el subcamino: `z` vuelve ahí, y lo que sigue cuenta desde ahí. Sin esto,
        // todo lo de detrás de un rectángulo cerrado salía corrido (30-sep-2026, capturas del usuario).
        var sx = 0
        var sy = 0
        fun px(i: Int) = Math.round(b.xs[i].toDouble() / k).toInt()
        fun py(i: Int) = Math.round(b.ys[i].toDouble() / k).toInt()
        for (op in b.ops) {
            when (op) {
                PlanoDePdf.MOVER -> {
                    val x = px(p); val y = py(p); p++
                    d.append('m').append(x - ux).append(if (y - uy < 0) "" else " ").append(y - uy)
                    ux = x; uy = y; sx = x; sy = y
                }
                PlanoDePdf.LINEA -> {
                    val x = px(p); val y = py(p); p++
                    d.append('l').append(x - ux).append(if (y - uy < 0) "" else " ").append(y - uy)
                    ux = x; uy = y
                }
                PlanoDePdf.CURVA -> {
                    d.append('c')
                    for (j in 0 until 3) {
                        val x = px(p + j); val y = py(p + j)
                        if (j > 0 && x - ux >= 0) d.append(' ')
                        d.append(x - ux).append(if (y - uy < 0) "" else " ").append(y - uy)
                    }
                    ux = px(p + 2); uy = py(p + 2); p += 3
                }
                PlanoDePdf.CERRAR -> { d.append('z'); ux = sx; uy = sy }
            }
        }
        // La primera orden es absoluta en SVG aunque sea `m`: desde (0,0) da lo mismo.
        return d.toString()
    }

    /**
     * **Las imágenes ya escritas en la página**, por su contenido. Las hojas van en la misma página,
     * así que una hoja puede usar (`<use>`) la imagen que escribió otra.
     */
    class YaPuestas {
        internal val porContenido = HashMap<Clave, String>()
    }

    internal class Clave(val datos: ByteArray, val mascara: ByteArray?) {
        private val h = 31 * datos.contentHashCode() + (mascara?.contentHashCode() ?: 0)
        override fun hashCode() = h
        override fun equals(other: Any?): Boolean {
            if (other !is Clave || other.h != h || !other.datos.contentEquals(datos)) return false
            val m = mascara
            val o = other.mascara
            return if (m == null || o == null) m == null && o == null else m.contentEquals(o)
        }
    }

    private fun imagenes(plano: PlanoDePdf.Plano, id: String, seVe: (Int) -> Boolean, sb: StringBuilder, ya: YaPuestas) {
        val puestas = plano.fotos.filter { seVe(it.capa) }
        if (puestas.isEmpty()) return
        val cod = java.util.Base64.getEncoder()
        // El nombre de cada imagen en la página: el de la que ya estaba, o uno nuevo que se escribe aquí.
        val nombre = HashMap<Int, String>()
        val nuevas = StringBuilder()
        for (f in puestas) {
            if (nombre.containsKey(f.id)) continue
            val conMascara = f.mascara != null && f.tipoMascara != null
            val clave = Clave(f.datos, if (conMascara) f.mascara else null)
            val hecho = ya.porContenido[clave]
            if (hecho != null) { nombre[f.id] = hecho; continue }
            val nom = id + "i" + f.id
            ya.porContenido[clave] = nom
            nombre[f.id] = nom
            nuevas.append("<image id=\"").append(nom)
                .append("\" width=\"1\" height=\"1\" preserveAspectRatio=\"none\" href=\"data:").append(f.tipo).append(";base64,")
                .append(cod.encodeToString(f.datos)).append("\"/>")
            if (conMascara) {
                // Negro transparente, blanco opaco: justo lo que hace una máscara de SVG por luminancia.
                nuevas.append("<mask id=\"").append(nom).append("k")
                    .append("\" maskContentUnits=\"objectBoundingBox\"><image width=\"1\" height=\"1\" preserveAspectRatio=\"none\" href=\"data:")
                    .append(f.tipoMascara).append(";base64,").append(cod.encodeToString(f.mascara)).append("\"/></mask>")
            }
        }
        if (nuevas.isNotEmpty()) sb.append("<defs>").append(nuevas).append("</defs>")
        for (f in puestas) {
            val nom = nombre.getValue(f.id)
            sb.append("<use href=\"#").append(nom).append("\" transform=\"matrix(")
                .append(n(f.a)).append(' ').append(n(f.b)).append(' ').append(n(f.c)).append(' ').append(n(f.d)).append(' ')
                .append(n(f.x)).append(' ').append(n(f.y)).append(")\"")
            if (f.mascara != null && f.tipoMascara != null) sb.append(" mask=\"url(#").append(nom).append("k)\"")
            if (f.alfa < 0.999) sb.append(" opacity=\"").append(n(f.alfa)).append('"')
            sb.append("/>")
        }
    }

    /**
     * Una tira de texto: trozos seguidos con la misma letra sobre la misma línea base. Va en un
     * solo `<text>` —el «buscar» encuentra frases enteras— pero **cada palabra en su sitio** (un
     * `<tspan>` con la x del PDF): estirar la línea entera desencajaba las de en medio, porque la
     * letra del navegador no mide lo mismo que la del documento (30-sep-2026).
     */
    private class Tira(val primero: PlanoDePdf.Texto) {
        val trozos = arrayListOf(Trozo(0.0, StringBuilder(primero.texto), primero.ancho))
        val fin get() = trozos.last().fin
    }

    /** Un trozo de la tira, de [inicio] a [fin] a lo largo de la línea, en unidades de la letra. */
    private class Trozo(val inicio: Double, val texto: StringBuilder, var fin: Double) {
        /** Si entre el anterior y este va un espacio (una palabra a otra): para buscar y copiar. */
        var espacioAntes = false
    }

    private fun textos(plano: PlanoDePdf.Plano, seVe: (Int) -> Boolean, sb: StringBuilder) {
        val tiras = ArrayList<Tira>()
        var actual: Tira? = null
        for (t in plano.textos) {
            if (!seVe(t.capa) || t.texto.isEmpty()) continue
            val a = actual
            if (a != null && seguido(a, t)) {
                val p = a.primero
                val u = ((t.x - p.x) * p.a + (t.y - p.y) * p.b) / (p.a * p.a + p.b * p.b)
                val ultimo = a.trozos.last()
                val hueco = u - ultimo.fin
                if (hueco in -0.05..0.03) {
                    // Pegado: la misma palabra (o un ajuste de letra).
                    ultimo.texto.append(t.texto)
                    ultimo.fin = maxOf(ultimo.fin, u + t.ancho)
                } else {
                    // Otro sitio: otro trozo, colocado donde dice el PDF. Si el hueco es de una
                    // palabra a otra, el espacio va al final del anterior y ocupa el hueco.
                    // El espacio va **entre** los dos trozos, fuera de los dos: dentro de uno, el
                    // navegador lo estiraba con la palabra y desaparecía.
                    val conEspacio = hueco > 0.12 && !ultimo.texto.endsWith(" ") && !t.texto.startsWith(" ")
                    a.trozos += Trozo(u, StringBuilder(t.texto), u + t.ancho).also { it.espacioAntes = conEspacio }
                }
                continue
            }
            actual = Tira(t).also { tiras += it }
        }
        for (tira in tiras) {
            val t = tira.primero
            if (tira.trozos.all { it.texto.isBlank() }) continue
            // **Derecho**, lo corriente, con su sitio y su tamaño en puntos: lo más corto. Girado o
            // inclinado, con su matriz; la letra a 100 y la matriz la deja en un punto, que una
            // letra de tamaño 1 la redondean al mínimo algunos navegadores.
            val derecho = Math.abs(t.b) < 1e-6 && Math.abs(t.c) < 1e-6 && Math.abs(t.a - t.d) < 1e-6 && t.a > 0
            val escala = if (derecho) t.a else 100.0
            // La x de cada trozo: en puntos del papel derecho; en unidades de la letra, girado.
            fun xDe(tr: Trozo) = if (derecho) t.x + tr.inicio * t.a else tr.inicio * 100.0
            if (derecho) {
                sb.append("<text y=\"").append(n2(t.y)).append("\" font-size=\"").append(n2(t.a)).append('"')
            } else {
                sb.append("<text transform=\"matrix(")
                    .append(n(t.a / 100)).append(' ').append(n(t.b / 100)).append(' ')
                    .append(n(t.c / 100)).append(' ').append(n(t.d / 100)).append(' ')
                    .append(n2(t.x)).append(' ').append(n2(t.y)).append(")\" font-size=\"100\"")
            }
            val clases = listOfNotNull(
                when (t.familia) { "serif" -> "fs"; "monospace" -> "fm"; "sans-serif-condensed" -> "fc"; else -> null },
                if (t.negrita) "n" else null, if (t.cursiva) "i" else null
            )
            if (clases.isNotEmpty()) sb.append(" class=\"").append(clases.joinToString(" ")).append('"')
            if (t.color != 0) sb.append(" fill=\"").append(hex(t.color)).append('"')
            if (t.alfa < 0.999) sb.append(" opacity=\"").append(n(t.alfa)).append('"')
            fun largo(tr: Trozo): String? {
                // Una sola letra cae en su sitio sin estirarla.
                val texto = tr.texto.trim()
                if (texto.length <= 1 || texto.length != tr.texto.length) return null
                val l = (tr.fin - tr.inicio) * escala
                return if (l > 0.01) n2(l) else null
            }
            if (tira.trozos.size == 1) {
                val tr = tira.trozos[0]
                sb.append(" x=\"").append(n2(xDe(tr))).append('"')
                largo(tr)?.let { sb.append(" textLength=\"").append(it).append("\" lengthAdjust=\"spacingAndGlyphs\"") }
                sb.append('>').append(escapar(tr.texto.toString().trimEnd())).append("</text>")
            } else {
                sb.append('>')
                tira.trozos.forEachIndexed { k, tr ->
                    val texto = if (k == tira.trozos.lastIndex) tr.texto.toString().trimEnd() else tr.texto.toString()
                    if (texto.isEmpty()) return@forEachIndexed
                    if (tr.espacioAntes) sb.append(' ')
                    sb.append("<tspan x=\"").append(n2(xDe(tr))).append('"')
                    largo(tr)?.let { sb.append(" textLength=\"").append(it).append("\" lengthAdjust=\"spacingAndGlyphs\"") }
                    sb.append('>').append(escapar(texto)).append("</tspan>")
                }
                sb.append("</text>")
            }
        }
    }

    /** Si [t] sigue la tira [a]: misma letra, mismo giro, misma línea base y justo detrás. */
    private fun seguido(a: Tira, t: PlanoDePdf.Texto): Boolean {
        val p = a.primero
        if (t.capa != p.capa || t.color != p.color || t.alfa != p.alfa || t.familia != p.familia ||
            t.negrita != p.negrita || t.cursiva != p.cursiva) return false
        val tam = Math.hypot(p.c, p.d)
        val tol = 0.02 * maxOf(tam, 1e-6)
        if (Math.abs(t.a - p.a) > tol || Math.abs(t.b - p.b) > tol || Math.abs(t.c - p.c) > tol || Math.abs(t.d - p.d) > tol) return false
        val largo2 = p.a * p.a + p.b * p.b
        val alto2 = p.c * p.c + p.d * p.d
        if (largo2 < 1e-12 || alto2 < 1e-12) return false
        val dx = t.x - p.x
        val dy = t.y - p.y
        val u = (dx * p.a + dy * p.b) / largo2
        // Lo que se aparta de la línea base, en alturas de letra (proyección sobre la normal de la línea).
        val v = (dx * -p.b + dy * p.a) / Math.sqrt(largo2) / Math.sqrt(alto2)
        return Math.abs(v) < 0.1 && u >= a.fin - 0.3 && u - a.fin < 3.0
    }

    private fun hex(c: Int) = "#" + String.format(java.util.Locale.ROOT, "%06x", c and 0xFFFFFF)

    private fun escapar(s: String) = buildString(s.length) {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch.code < 0x20 && ch != '\t' -> append(' ')
            else -> append(ch)
        }
    }

    /** Con dos decimales: la centésima de punto no se ve. */
    private fun n2(v: Double): String {
        val r = Math.round(v * 100)
        if (r % 100 == 0L) return (r / 100).toString()
        val t = String.format(java.util.Locale.ROOT, "%.2f", r / 100.0).trimEnd('0').trimEnd('.')
        return if (t == "-0") "0" else t
    }

    /** Hasta tres decimales, sin ceros de cola. */
    private fun n(v: Double): String {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return v.toLong().toString()
        val t = String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
        return if (t == "-0") "0" else t
    }
}
