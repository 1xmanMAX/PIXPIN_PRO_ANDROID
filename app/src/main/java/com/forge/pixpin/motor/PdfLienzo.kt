package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * El dibujo escrito como **órdenes de PDF**.
 *
 * Es la última pieza de anotar un PDF ajeno: [PdfLectura] sabe leerlo,
 * [PdfEscritura] sabe añadirle una revisión y [PdfAnotado] sabe colgarle una
 * capa. Faltaba lo que va dentro de esa capa, y es esto.
 *
 * ## Sale barato, y no por casualidad
 *
 * Un flujo de contenido de PDF entiende `m`, `l`, `c` y `h` — mover, recta,
 * curva cúbica y cerrar. Que son **exactamente** las cuatro que emite el motor
 * desde que se unificaron los caminos en [Caminos]. Así que esto traduce, no
 * recalcula: el garabato de un rectángulo sale del mismo generador y con la
 * misma semilla que el de la pantalla, así que tiembla igual.
 *
 * | El motor emite | El PDF escribe |
 * |---|---|
 * | `Op.Move` | `x y m` |
 * | `Op.LineTo` | `x y l` |
 * | `Op.CurveTo` | `x1 y1 x2 y2 x y c` |
 * | `Op.Cerrar` | `h` |
 *
 * ## Y la matriz lo pone todo en su sitio
 *
 * Al principio va un solo `cm` —el que calcula [PdfAnotado.matrizDePagina]— que
 * lleva los píxeles que se tocaron a los puntos del papel, con la Y al revés y
 * con la hoja girada si lo estaba. A partir de ahí **todo se escribe con los
 * mismos números que usa el motor por dentro**, sin convertir punto a punto: es
 * lo que hace que esto sea una traducción y no un segundo sistema de
 * coordenadas esperando a desincronizarse.
 *
 * ## Lo que no se escribe
 *
 * El fondo. Al anotar una página, la hoja se ve **debajo como referencia** y no
 * es un elemento del dibujo, así que no puede llegar aquí — y menos mal: una
 * foto de la página estampada encima de la propia página taparía su texto con
 * una imagen y ese texto dejaría de poder buscarse. Ver [Proyecto].
 */
object PdfLienzo {

    /** Lo que hay que meter en el PDF para que la capa se dibuje. */
    class Resultado(
        /** Las órdenes, ya escritas. */
        val contenido: ByteArray,
        /** Lo que esas órdenes nombran: transparencias e imágenes. */
        val recursos: PdfValor.Dicc,
        /** Y los objetos sueltos a los que esos recursos apuntan. */
        val extra: List<ObjetoPdf>
    )

    /**
     * A cuántos píxeles por píxel de escena se incrustan las imágenes.
     *
     * El mismo criterio que en el SVG: guardarlas a su resolución original es
     * tirar el peso, y al doble de su tamaño en el dibujo se ven nítidas
     * ampliando al 200 %, que es más de lo que nadie amplía una anotación.
     */
    private const val DENSIDAD_IMAGEN = 2.0
    private const val MAX_LADO_IMAGEN = 2048
    private const val CALIDAD_JPEG = 88

    /**
     * Escribe [scene] para pegarla sobre una página.
     *
     * [matriz] es la de [PdfAnotado.matrizDePagina] y [primerObjeto] el primer
     * número libre del archivo, para numerar las imágenes que haya que añadir.
     */
    fun deEscena(
        context: Context,
        scene: Scene,
        matriz: DoubleArray,
        primerObjeto: Int,
        imageProvider: (String) -> Bitmap? = { null }
    ): Resultado? = runCatching {
        val pintables = scene.contenidoVisible.let { visible ->
            if (scene.referenciasVisibles) visible else visible.filter { !it.reference }
        }
        if (pintables.isEmpty()) return null

        val pincel = Pincel(DrawFonts.provider(context), imageProvider, primerObjeto)
        val cuerpo = ByteArrayOutputStream()
        cuerpo.orden("q")
        cuerpo.orden(
            matriz.joinToString(" ") { PdfEscritura.numero(it) } + " cm"
        )
        // Redondeados los dos, como en pantalla: un trazo a mano no acaba en
        // pico ni se quiebra en las esquinas.
        cuerpo.orden("1 J")
        cuerpo.orden("1 j")

        for ((i, e) in pintables.withIndex()) {
            if (e.type == ElementType.SPOTLIGHT) continue
            cuerpo.write(pincel.elemento(e, pintables.subList(0, i)))
        }
        pintables.filter { it.type == ElementType.SPOTLIGHT }
            .takeIf { it.isNotEmpty() }
            ?.let { cuerpo.write(pincel.focos(it, pintables)) }

        cuerpo.orden("Q")
        Resultado(cuerpo.toByteArray(), pincel.recursos(), pincel.objetos)
    }.getOrNull()

    // ---------------------------------------------------------------------
    // El que escribe
    // ---------------------------------------------------------------------

    private class Pincel(
        private val typefaces: (Int?) -> android.graphics.Typeface?,
        private val imageProvider: (String) -> Bitmap?,
        private var siguienteObjeto: Int
    ) {
        private val medidor = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Las transparencias que se han necesitado, por su valor. */
        private val transparencias = LinkedHashMap<String, Double>()

        /** Y las imágenes incrustadas, por su nombre dentro del PDF. */
        private val imagenes = LinkedHashMap<String, PdfValor.Ref>()

        val objetos = mutableListOf<ObjetoPdf>()

        fun recursos(): PdfValor.Dicc {
            val d = mutableMapOf<String, PdfValor>()
            if (transparencias.isNotEmpty()) {
                d["ExtGState"] = PdfValor.Dicc(
                    transparencias.mapValues { (_, a) ->
                        PdfValor.Dicc(
                            mapOf(
                                "CA" to PdfValor.Numero(a),
                                "ca" to PdfValor.Numero(a)
                            )
                        )
                    }
                )
            }
            if (imagenes.isNotEmpty()) d["XObject"] = PdfValor.Dicc(imagenes.toMap())
            return PdfValor.Dicc(d)
        }

        fun elemento(e: Element, debajo: List<Element>): ByteArray {
            if (e.isDeleted) return ByteArray(0)
            val opacidad =
                if (e.reference) e.opacity * REFERENCIA_OPACIDAD / 100 else e.opacity
            val alpha = (opacidad * 255 / 100).coerceIn(0, 255)

            val dentro = when (e.type) {
                ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE,
                ElementType.REGION, ElementType.ARC -> forma(e, alpha)
                ElementType.LINE, ElementType.ARROW -> lineal(e, alpha)
                ElementType.FREEDRAW -> lapiz(e, alpha)
                ElementType.TEXT -> texto(e, alpha)
                ElementType.PUNTO -> punto(e, alpha)
                ElementType.SERIAL -> serie(e, alpha)
                ElementType.MEASURE -> cota(e, alpha)
                ElementType.IMAGE -> imagen(e, alpha)
                ElementType.MOSAIC -> ByteArray(0)
                ElementType.ESCALA_GRAFICA, ElementType.FRAME,
                ElementType.SPOTLIGHT -> ByteArray(0)
            }
            if (dentro.isEmpty()) return dentro

            // Girado: se gira el papel alrededor del centro, igual que en
            // pantalla, y no la geometría de cada trazo.
            if (e.angle == 0.0) return dentro
            val c = getElementAbsoluteCoords(e)
            val salida = ByteArrayOutputStream()
            salida.orden("q")
            salida.orden(giroSobre(e.angle, c.cx, c.cy))
            salida.write(dentro)
            salida.orden("Q")
            return salida.toByteArray()
        }

        // -- las formas ------------------------------------------------------

        private fun forma(e: Element, alpha: Int): ByteArray {
            val salida = ByteArrayOutputStream()

            if (e.type == ElementType.ARC) {
                val puntos = puntosDelArco(e)
                if (puntos.size < 2) return ByteArray(0)
                val comoLinea = e.copy(type = ElementType.LINE, points = puntos, roundness = null)
                val g = buildShapeGeometry(comoLinea) ?: return ByteArray(0)
                salida.write(relleno(e, g, alpha))
                salida.write(trazo(e, g.stroke, alpha))
                return salida.toByteArray()
            }

            if (e.type == ElementType.REGION) {
                val anillos = anillosDeRegion(e)
                if (anillos.isEmpty() || anillos.first().size < 3) return ByteArray(0)
                val silueta = opsDeAnillos(anillos, 3)
                return if (needsRoughFill(e)) {
                    rayado(e, silueta, Rough(roughOptionsFor(e)).fillPolygons(anillos), alpha, true)
                } else {
                    solido(e, silueta, alpha, true)
                }
            }

            val g = buildShapeGeometry(e) ?: return ByteArray(0)
            salida.write(relleno(e, g, alpha))
            salida.write(trazo(e, g.stroke, alpha))
            return salida.toByteArray()
        }

        private fun lineal(e: Element, alpha: Int): ByteArray {
            if (absolutePoints(e).size < 2) return ByteArray(0)
            val salida = ByteArrayOutputStream()
            salida.write(forma(e, alpha))
            if (e.type == ElementType.ARROW) {
                e.startArrowhead?.let { salida.write(punta(e, ArrowEnd.START, it, alpha)) }
                e.endArrowhead?.let { salida.write(punta(e, ArrowEnd.END, it, alpha)) }
            }
            return salida.toByteArray()
        }

        /** El lápiz: la mancha rellena, no una línea recorrida. */
        private fun lapiz(e: Element, alpha: Int): ByteArray {
            val pts = absolutePoints(e)
            if (pts.isEmpty()) return ByteArray(0)
            val contorno = getStroke(pts, e.pressures, strokeOptionsFor(e))
            if (contorno.isEmpty()) return ByteArray(0)

            val salida = ByteArrayOutputStream()
            if (rellenaSuLazo(e)) {
                val lazo = douglasPeucker(pts, FREEDRAW_FILL_TOLERANCE)
                val silueta = opsSuaveCerrado(lazo)
                salida.write(
                    if (needsRoughFill(e)) {
                        rayado(e, silueta, Rough(roughOptionsFor(e)).fillPolygon(lazo), alpha, false)
                    } else solido(e, silueta, alpha, false)
                )
            }
            salida.write(
                pintado(opsSuaveCerrado(contorno), parseColor(e.strokeColor, alpha), "f")
            )
            return salida.toByteArray()
        }

        // -- relleno y trazo -------------------------------------------------

        private fun relleno(e: Element, g: ShapeGeometry, alpha: Int): ByteArray {
            if (!e.hasBackground || isTransparent(e.backgroundColor)) return ByteArray(0)
            val silueta = opsDePuntos(g.outline, cerrado = true)
            if (silueta.isEmpty()) return ByteArray(0)
            val rayas = g.fill ?: return solido(e, silueta, alpha, false)
            return rayado(e, silueta, rayas, alpha, false)
        }

        private fun solido(
            e: Element, silueta: List<Op>, alpha: Int, parImpar: Boolean
        ): ByteArray = pintado(
            silueta, parseColor(e.backgroundColor, alpha), if (parImpar) "f*" else "f"
        )

        /**
         * Las rayas del rayado, **recortadas a la silueta**.
         *
         * El barrido puede sobresalir un píxel por las esquinas, igual que en
         * pantalla. Aquí se recorta con `W n`, que es el recorte del PDF: se
         * dibuja el camino, se dice que sirva de recorte y se descarta con `n`
         * sin pintarlo.
         */
        private fun rayado(
            e: Element, silueta: List<Op>, rayas: List<Op>, alpha: Int, parImpar: Boolean
        ): ByteArray {
            if (rayas.isEmpty()) return ByteArray(0)
            val color = parseColor(e.backgroundColor, alpha)
            val salida = ByteArrayOutputStream()
            salida.orden("q")
            salida.write(camino(silueta))
            salida.orden(if (parImpar) "W* n" else "W n")
            salida.write(alfa(color))
            salida.orden(colorDe(color, "RG"))
            salida.orden("${PdfEscritura.numero(e.strokeWidth / 2)} w")
            salida.write(camino(rayas))
            salida.orden("S")
            salida.orden("Q")
            return salida.toByteArray()
        }

        private fun trazo(e: Element, ops: List<Op>, alpha: Int): ByteArray {
            if (ops.isEmpty()) return ByteArray(0)
            val color = parseColor(e.strokeColor, alpha)
            val grosor =
                if (e.strokeStyle == StrokeStyle.SOLID) e.strokeWidth else e.strokeWidth + 0.5
            val salida = ByteArrayOutputStream()
            salida.orden("q")
            salida.write(alfa(color))
            salida.orden(colorDe(color, "RG"))
            salida.orden("${PdfEscritura.numero(grosor)} w")
            Svg.guionesDe(e.strokeStyle, e.strokeWidth)?.let {
                salida.orden("[${it.replace(" ", " ")}] 0 d")
            }
            salida.write(camino(ops))
            salida.orden("S")
            salida.orden("Q")
            return salida.toByteArray()
        }

        /** Un camino pintado de un color, con la orden que se le pase. */
        private fun pintado(ops: List<Op>, color: Int, orden: String): ByteArray {
            if (ops.isEmpty()) return ByteArray(0)
            val salida = ByteArrayOutputStream()
            salida.orden("q")
            salida.write(alfa(color))
            salida.orden(colorDe(color, "rg"))
            salida.write(camino(ops))
            salida.orden(orden)
            salida.orden("Q")
            return salida.toByteArray()
        }

        private fun raya(a: Pt, b: Pt, color: Int, grosor: Double): ByteArray {
            val salida = ByteArrayOutputStream()
            salida.orden("q")
            salida.write(alfa(color))
            salida.orden(colorDe(color, "RG"))
            salida.orden("${PdfEscritura.numero(grosor)} w")
            salida.write(camino(opsDePuntos(listOf(a, b), cerrado = false)))
            salida.orden("S")
            salida.orden("Q")
            return salida.toByteArray()
        }

        // -- las puntas de las flechas ---------------------------------------

        private fun punta(e: Element, en: ArrowEnd, cual: Arrowhead, alpha: Int): ByteArray {
            val f = getArrowheadPoints(e, en, cual) ?: return ByteArray(0)
            val color = parseColor(e.strokeColor, alpha)
            val grosor =
                if (e.strokeStyle == StrokeStyle.SOLID) e.strokeWidth else e.strokeWidth + 0.5

            fun cerrada(pts: List<Pt>, rellena: Boolean): ByteArray {
                val ops = opsDePuntos(pts, cerrado = true)
                return if (rellena) pintado(ops, color, "f")
                else {
                    val salida = ByteArrayOutputStream()
                    salida.orden("q")
                    salida.write(alfa(color))
                    salida.orden(colorDe(color, "RG"))
                    salida.orden("${PdfEscritura.numero(grosor)} w")
                    salida.write(camino(ops))
                    salida.orden("S")
                    salida.orden("Q")
                    salida.toByteArray()
                }
            }

            return when (cual) {
                Arrowhead.CIRCLE, Arrowhead.CIRCLE_OUTLINE ->
                    cerrada(circulo(f.tip, f.diameter / 2), cual == Arrowhead.CIRCLE)
                Arrowhead.TRIANGLE, Arrowhead.TRIANGLE_OUTLINE ->
                    cerrada(listOf(f.tip, f.wings.first, f.wings.second), cual == Arrowhead.TRIANGLE)
                Arrowhead.DIAMOND, Arrowhead.DIAMOND_OUTLINE -> {
                    val opuesto = f.opposite ?: return ByteArray(0)
                    cerrada(
                        listOf(f.tip, f.wings.first, opuesto, f.wings.second),
                        cual == Arrowhead.DIAMOND
                    )
                }
                Arrowhead.ARROW, Arrowhead.BAR -> {
                    val salida = ByteArrayOutputStream()
                    salida.write(raya(f.tip, f.wings.first, color, grosor))
                    salida.write(raya(f.tip, f.wings.second, color, grosor))
                    salida.toByteArray()
                }
            }
        }

        /** Un círculo como polilínea: el PDF no tiene primitiva de círculo. */
        private fun circulo(centro: Pt, radio: Double, pasos: Int = 24): List<Pt> =
            (0 until pasos).map {
                val a = 2 * Math.PI * it / pasos
                Pt(centro.x + radio * cos(a), centro.y + radio * sin(a))
            }

        // -- el texto, en curvas ---------------------------------------------

        private fun texto(e: Element, alpha: Int): ByteArray {
            val contenido = e.text ?: return ByteArray(0)
            if (contenido.isEmpty()) return ByteArray(0)
            val tam = e.fontSize ?: 20.0
            val c = getElementAbsoluteCoords(e)
            val color = parseColor(e.strokeColor, alpha)

            val alineado = when (e.textAlign) {
                TextAlign.CENTER -> Paint.Align.CENTER
                TextAlign.RIGHT -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            val x = when (e.textAlign) {
                TextAlign.CENTER -> c.cx
                TextAlign.RIGHT -> c.x2
                else -> c.x1
            }

            prepararPincel(tam, e.fontFamily, alineado)
            var y = c.y1 - medidor.fontMetrics.ascent.toDouble()
            val alto = tam * 1.25

            val salida = ByteArrayOutputStream()
            for (linea in contenido.split('\n')) {
                if (linea.isNotBlank()) {
                    val perfil = Glifos.perfilDe(medidor, linea, x, y)
                    if (perfil.isNotEmpty()) {
                        salida.write(pintado(opsDeAnillos(perfil, 2), color, "f"))
                    }
                }
                y += alto
            }
            return salida.toByteArray()
        }

        private fun punto(e: Element, alpha: Int): ByteArray {
            val salida = ByteArrayOutputStream()
            salida.write(
                pintado(
                    opsDePuntos(circulo(Pt(e.x, e.y), RADIO_DEL_PUNTO), cerrado = true),
                    android.graphics.Color.argb(alpha, 0, 0, 0), "f"
                )
            )
            val etiqueta = e.text
            if (etiqueta.isNullOrEmpty()) return salida.toByteArray()

            val tam = (e.fontSize ?: 22.0).coerceAtLeast(1.0)
            prepararPincel(tam, e.fontFamily, Paint.Align.CENTER)
            val donde = sitioDeLaEtiqueta(e)
            val fm = medidor.fontMetrics
            val base = donde.y - (fm.ascent + fm.descent) / 2.0
            val perfil = Glifos.perfilDe(medidor, etiqueta, donde.x, base)
            if (perfil.isNotEmpty()) {
                salida.write(
                    pintado(opsDeAnillos(perfil, 2), parseColor(e.strokeColor, alpha), "f")
                )
            }
            return salida.toByteArray()
        }

        private fun serie(e: Element, alpha: Int): ByteArray {
            val c = getElementAbsoluteCoords(e)
            val radio = min(c.x2 - c.x1, c.y2 - c.y1) / 2
            if (radio <= 0) return ByteArray(0)
            val fondo = parseColor(e.strokeColor, alpha)
            val salida = ByteArrayOutputStream()
            salida.write(
                pintado(opsDePuntos(circulo(Pt(c.cx, c.cy), radio), cerrado = true), fondo, "f")
            )
            val etiqueta = e.text ?: return salida.toByteArray()

            val tam = radio * SERIAL_TEXT_RATIO
            prepararPincel(tam, null, Paint.Align.CENTER, negrita = true)
            val fm = medidor.fontMetrics
            val base = c.cy - (fm.ascent + fm.descent) / 2.0
            val perfil = Glifos.perfilDe(medidor, etiqueta, c.cx, base)
            if (perfil.isNotEmpty()) {
                salida.write(
                    pintado(opsDeAnillos(perfil, 2), contrastingTextColor(fondo, alpha), "f")
                )
            }
            return salida.toByteArray()
        }

        private fun cota(e: Element, alpha: Int): ByteArray {
            val pts = absolutePoints(e)
            if (pts.size < 2) return ByteArray(0)
            val a = pts.first()
            val b = pts.last()
            val largo = hypot(b.x - a.x, b.y - a.y)
            if (largo < 0.5) return ByteArray(0)

            val color = parseColor(e.strokeColor, alpha)
            val salida = ByteArrayOutputStream()
            val rough = Rough(roughOptionsFor(e).copy(preserveVertices = true))
            salida.write(
                trazo(e, rough.doubleLine(a.x, a.y, b.x, b.y), alpha)
            )

            // Los banderines de los extremos.
            val nx = -(b.y - a.y) / largo
            val ny = (b.x - a.x) / largo
            val ala = (e.strokeWidth * 3.0).coerceAtLeast(6.0)
            for (extremo in listOf(a, b)) {
                salida.write(
                    raya(
                        Pt(extremo.x - nx * ala, extremo.y - ny * ala),
                        Pt(extremo.x + nx * ala, extremo.y + ny * ala),
                        color, e.strokeWidth
                    )
                )
            }
            return salida.toByteArray()
        }

        // -- imágenes --------------------------------------------------------

        private fun imagen(e: Element, alpha: Int): ByteArray {
            val fileId = e.fileId ?: return ByteArray(0)
            val bitmap = imageProvider(fileId) ?: return ByteArray(0)
            val c = getElementAbsoluteCoords(e)
            val ancho = c.x2 - c.x1
            val alto = c.y2 - c.y1
            if (ancho <= 0 || alto <= 0) return ByteArray(0)

            val nombre = incrustar(bitmap, ancho, alto) ?: return ByteArray(0)
            val salida = ByteArrayOutputStream()
            salida.orden("q")
            if (alpha < 255) salida.write(alfa(android.graphics.Color.argb(alpha, 0, 0, 0)))
            // La imagen se dibuja en el cuadrado unidad con la Y hacia arriba;
            // aquí la Y va hacia abajo, así que se refleja al colocarla.
            salida.orden(
                listOf(ancho, 0.0, 0.0, -alto, c.x1, c.y1 + alto)
                    .joinToString(" ") { PdfEscritura.numero(it) } + " cm"
            )
            salida.orden("${PdfEscritura.nombre(nombre)} Do")
            salida.orden("Q")
            return salida.toByteArray()
        }

        /** Mete un mapa de bits en el archivo y devuelve su nombre. */
        private fun incrustar(bmp: Bitmap, ancho: Double, alto: Double): String? = runCatching {
            val destW = min(MAX_LADO_IMAGEN.toDouble(), Math.ceil(ancho * DENSIDAD_IMAGEN))
                .toInt().coerceAtLeast(1)
            val destH = min(MAX_LADO_IMAGEN.toDouble(), Math.ceil(alto * DENSIDAD_IMAGEN))
                .toInt().coerceAtLeast(1)
            val ajustado =
                if (bmp.width <= destW && bmp.height <= destH) bmp
                else Bitmap.createScaledBitmap(bmp, destW, destH, true)

            // En JPEG y por DCTDecode: los bytes del JPEG van tal cual dentro
            // del PDF, sin descomprimir ni recodificar. Es el camino más corto
            // que hay para meter una foto en un documento.
            val salida = ByteArrayOutputStream()
            ajustado.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, salida)
            val datos = salida.toByteArray()

            val numero = siguienteObjeto++
            val nombre = "${PdfAnotado.PREFIJO}I${imagenes.size}"
            objetos += ObjetoPdf(
                numero,
                PdfValor.Flujo(
                    PdfValor.Dicc(
                        mapOf(
                            "Type" to PdfValor.Nombre("XObject"),
                            "Subtype" to PdfValor.Nombre("Image"),
                            "Width" to PdfValor.Numero(ajustado.width.toDouble()),
                            "Height" to PdfValor.Numero(ajustado.height.toDouble()),
                            "ColorSpace" to PdfValor.Nombre("DeviceRGB"),
                            "BitsPerComponent" to PdfValor.Numero(8.0),
                            "Filter" to PdfValor.Nombre("DCTDecode")
                        )
                    ),
                    datos
                )
            )
            imagenes[nombre] = PdfValor.Ref(numero, 0)
            nombre
        }.getOrNull()

        // -- el foco ---------------------------------------------------------

        fun focos(focos: List<Element>, todos: List<Element>): ByteArray {
            val caja = getCommonBounds(todos)
            val marco = listOf(
                Pt(caja.x1, caja.y1), Pt(caja.x2, caja.y1),
                Pt(caja.x2, caja.y2), Pt(caja.x1, caja.y2)
            )
            val ops = ArrayList(opsDePuntos(marco, cerrado = true))
            for (f in focos) {
                val c = getElementAbsoluteCoords(f)
                val hueco = listOf(
                    Pt(c.x1, c.y1), Pt(c.x2, c.y1), Pt(c.x2, c.y2), Pt(c.x1, c.y2)
                ).map { if (f.angle == 0.0) it else pointRotateRads(it, Pt(c.cx, c.cy), f.angle) }
                ops += opsDePuntos(hueco, cerrado = true)
            }
            val opacidad = (focos.maxOf { it.opacity } * 255 / 100).coerceIn(0, 255)
            val negro = android.graphics.Color.argb(
                (opacidad * SPOTLIGHT_DIM / 255), 0, 0, 0
            )
            return pintado(ops, negro, "f*")
        }

        // -- utilidades ------------------------------------------------------

        private fun prepararPincel(
            tam: Double, familia: Int?, alineado: Paint.Align, negrita: Boolean = false
        ) {
            medidor.reset()
            medidor.isAntiAlias = true
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(familia)
            medidor.textAlign = alineado
            medidor.isFakeBoldText = negrita
        }

        /**
         * Un camino, escrito.
         *
         * Es la traducción entera, y cabe en cuatro líneas: por eso escribir un
         * PDF salió barato después de unificar los caminos.
         */
        private fun camino(ops: List<Op>): ByteArray {
            val salida = ByteArrayOutputStream()
            for (op in ops) when (op) {
                is Op.Move -> salida.orden("${n(op.x)} ${n(op.y)} m")
                is Op.LineTo -> salida.orden("${n(op.x)} ${n(op.y)} l")
                is Op.CurveTo -> salida.orden(
                    "${n(op.x1)} ${n(op.y1)} ${n(op.x2)} ${n(op.y2)} ${n(op.x)} ${n(op.y)} c"
                )
                Op.Cerrar -> salida.orden("h")
            }
            return salida.toByteArray()
        }

        /** La transparencia, como estado gráfico con nombre. */
        private fun alfa(color: Int): ByteArray {
            val a = ((color ushr 24) and 0xFF) / 255.0
            if (a >= 1.0) return ByteArray(0)
            val clave = PdfEscritura.numero(a)
            val nombre = transparencias.entries.firstOrNull {
                PdfEscritura.numero(it.value) == clave
            }?.key ?: "${PdfAnotado.PREFIJO}G${transparencias.size}".also {
                transparencias[it] = a
            }
            val salida = ByteArrayOutputStream()
            salida.orden("${PdfEscritura.nombre(nombre)} gs")
            return salida.toByteArray()
        }

        private fun colorDe(argb: Int, orden: String): String {
            val r = ((argb shr 16) and 0xFF) / 255.0
            val g = ((argb shr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            return "${n(r)} ${n(g)} ${n(b)} $orden"
        }

        private fun giroSobre(radianes: Double, cx: Double, cy: Double): String {
            val co = cos(radianes)
            val se = sin(radianes)
            // Trasladar al centro, girar y volver, todo en una matriz.
            val e = cx - co * cx + se * cy
            val f = cy - se * cx - co * cy
            return "${n(co)} ${n(se)} ${n(-se)} ${n(co)} ${n(e)} ${n(f)} cm"
        }

        private fun n(v: Double) = PdfEscritura.numero(v)
    }

    private const val REFERENCIA_OPACIDAD = 35
    private const val SPOTLIGHT_DIM = 96
    private const val SERIAL_TEXT_RATIO = 1.25
}

/** Escribe una orden y su salto de línea. */
private fun ByteArrayOutputStream.orden(texto: String) {
    write(texto.toByteArray(Charsets.ISO_8859_1))
    write('\n'.code)
}
