package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * El dibujo como SVG, para meterlo en un documento.
 *
 * Es la tercera salida vectorial, y la que hacía falta para **pegar el dibujo
 * en un Word, en unas diapositivas o en una web** sin que se vea borroso al
 * ampliarlo. El PDF ya salía vectorial, pero un PDF se manda; no se inserta en
 * medio de un párrafo.
 *
 * ## No se reinventa nada
 *
 * La geometría no se recalcula: se pide a las mismas funciones que usa la
 * pantalla —[buildShapeGeometry], [getStroke], [anillosDeRegion]— y lo único
 * que cambia es que en vez de volcarse en un `Path` de Android se escribe como
 * texto ([Svg]). Por eso el SVG y la pantalla no pueden divergir: el garabato
 * sale del mismo generador y con la misma semilla, así que un rectángulo
 * exportado tiembla exactamente igual que el que se ve.
 *
 * ## El texto va en curvas, y va a propósito
 *
 * En PNG y en PDF el texto sigue siendo texto. Aquí **no**: cada letra se
 * convierte en su silueta.
 *
 * El motivo es que un SVG viaja solo. Si dentro dijera «esto va en Excalifont»,
 * el ordenador que lo abra tendría que tener Excalifont instalada —y no la
 * tiene—, así que Word la sustituiría por otra: cambian los anchos, el texto se
 * sale de su sitio y el dibujo deja de coincidir consigo mismo. Incrustar la
 * fuente tampoco vale: son 191 KB por archivo y Word ignora `@font-face` en un
 * SVG. En curvas se ve **idéntico en cualquier parte**, que es lo que se le
 * pide a algo que se pega en un documento de otro.
 *
 * Se aplica a **todas** las letras, no solo a las del cuadro de texto: el
 * número de una flecha, la cifra de una cota, el número dentro de un círculo y
 * las cifras de la escala gráfica salen igual de curvas. Si una sola se
 * quedara como fuente, sería justo la que se descolocaría.
 *
 * Lo que se pierde es poder seleccionar ese texto en el SVG. Es el precio, y en
 * un dibujo pegado dentro de un documento nadie lo echa de menos — el texto que
 * se busca es el del documento, no el de la figura.
 */
object DrawSvg {

    /**
     * El tipo con el que se comparte.
     *
     * Va el oficial y no `text/xml`: es lo que hace que Word, el navegador y el
     * visor de imágenes lo ofrezcan al recibirlo, en vez de tratarlo como un
     * archivo de texto que hay que abrir con algo.
     */
    const val MIME_TYPE = "image/svg+xml"

    /** Margen alrededor del contenido, en px de escena. El mismo que [DrawExport]. */
    private const val MARGEN = 10.0

    /**
     * A cuántos píxeles por píxel de escena se incrustan las imágenes.
     *
     * Una foto dentro de un SVG hay que guardarla como imagen, y el archivo pesa
     * lo que pese esa foto. Guardarla a su tamaño original es tirar el peso: una
     * captura de 12 Mpx incrustada en base64 son más de veinte megas para algo
     * que en el dibujo ocupa un dedo. Al doble de su tamaño en el dibujo se ve
     * nítida hasta ampliando al 200 %, que es más de lo que nadie va a ampliar
     * una figura dentro de un documento.
     */
    private const val DENSIDAD_IMAGEN = 2.0

    /** Y un tope duro, por si alguien pone una foto a pantalla completa. */
    private const val MAX_LADO_IMAGEN = 2048

    /**
     * Calidad del JPEG cuando la imagen no tiene transparencia.
     *
     * Una captura guardada en PNG dentro de un SVG pesa entre tres y diez veces
     * más que en JPEG y se ve igual. El PNG se reserva para las que sí tienen
     * transparencia, donde el JPEG no vale: rellenaría el hueco de negro.
     */
    private const val CALIDAD_JPEG = 88

    /**
     * Cada cuántos píxeles se toma un punto al seguir el perfil de una letra.
     *
     * Es el paso del muestreo, no el resultado: después pasa por
     * [douglasPeucker], que tira todo lo que queda en línea recta. En los palos
     * de una «l» sobrevive un punto por esquina y en la panza de una «o» los que
     * hagan falta, que es exactamente el reparto que uno querría hacer a mano.
     */
    private const val PASO_DEL_PERFIL = 0.7

    /** Cuánto se le permite desviarse al perfil simplificado, en píxeles. */
    private const val TOLERANCIA_DEL_PERFIL = 0.12

    /**
     * El dibujo escrito, o null si no había nada que escribir.
     *
     * Se encuadra igual que el PNG: con hoja manda la hoja y sin ella manda el
     * contenido con un margen. Que las tres salidas encuadren igual no es un
     * detalle — es lo que hace que exportar dos veces en formatos distintos dé
     * la misma figura.
     */
    fun aTexto(
        context: Context,
        scene: Scene,
        imageProvider: (String) -> Bitmap? = { null }
    ): String? = runCatching {
        val contenido = scene.contenidoVisible
        // Las guías se pintan o no según el mismo interruptor que en pantalla:
        // el SVG tiene que parecerse a lo que se está viendo, no a otra cosa.
        val pintables =
            if (scene.referenciasVisibles) contenido
            else contenido.filter { !it.reference }
        if (pintables.isEmpty()) return null

        val marco = scene.marco
        val b = if (marco != null) getElementBounds(marco) else getCommonBounds(pintables)
        val margen = if (marco != null) 0.0 else MARGEN
        val caja = Bounds(b.x1 - margen, b.y1 - margen, b.x2 + margen, b.y2 + margen)
        if (caja.width <= 0 || caja.height <= 0) return null

        val pincel = Lapicero(DrawFonts.provider(context), imageProvider, scene, caja)
        val cuerpo = StringBuilder()
        for ((i, e) in pintables.withIndex()) {
            if (e.type == ElementType.SPOTLIGHT) continue
            cuerpo.append(pincel.elemento(e, pintables.subList(0, i)))
        }
        // El foco el último de todos y de una sola pieza, por lo mismo que en
        // pantalla: dos sombras superpuestas oscurecen el doble donde se cruzan.
        pintables.filter { it.type == ElementType.SPOTLIGHT }
            .takeIf { it.isNotEmpty() }
            ?.let { cuerpo.append(pincel.focos(it)) }

        Svg.documento(caja, Svg.hex(parseColor(scene.backgroundColor)), cuerpo.toString())
    }.getOrNull()

    /**
     * El SVG en un archivo, listo para compartir.
     *
     * Va a `cache/share` por lo mismo que el PDF: es la única carpeta que
     * publica el `FileProvider`, y un archivo de cualquier otro sitio daría un
     * fallo de permisos en cuanto el destinatario intentara abrirlo.
     */
    fun aArchivo(
        context: Context,
        scene: Scene,
        nombre: String,
        imageProvider: (String) -> Bitmap? = { null }
    ): File? = runCatching {
        val texto = aTexto(context, scene, imageProvider) ?: return null
        val carpeta = File(context.cacheDir, "share").apply { mkdirs() }
        val archivo = File(carpeta, if (nombre.endsWith(".svg")) nombre else "$nombre.svg")
        archivo.writeText(texto)
        archivo
    }.getOrNull()

    // ---------------------------------------------------------------------
    // El que escribe
    // ---------------------------------------------------------------------

    /**
     * Escribe los elementos.
     *
     * Es una clase y no un puñado de funciones sueltas porque necesita cargar
     * cosas —la letra, las imágenes, el renderizador del mosaico— y hacerlo una
     * vez por exportación en vez de una vez por elemento.
     */
    private class Lapicero(
        private val typefaces: (Int?) -> android.graphics.Typeface?,
        private val imageProvider: (String) -> Bitmap?,
        private val scene: Scene,
        private val caja: Bounds
    ) {

        /** Solo para medir y para sacar perfiles; no pinta nunca. */
        private val medidor = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * El renderizador, **solo para los mosaicos**.
         *
         * Un mosaico no se puede escribir como trazos: lo que hace es coger los
         * píxeles de debajo. Quien sabe sacarlos es el renderizador, así que se
         * le piden a él en vez de reimplementar la reducción aquí y arriesgarse
         * a que las dos versiones se separen.
         */
        private val renderizador by lazy { Renderer(imageProvider) }

        fun elemento(e: Element, debajo: List<Element>): String {
            if (e.isDeleted) return ""
            val opacidad =
                if (e.reference) e.opacity * REFERENCIA_OPACIDAD / 100 else e.opacity
            val alpha = (opacidad * 255 / 100).coerceIn(0, 255)

            val dentro = when (e.type) {
                ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE,
                ElementType.REGION, ElementType.ARC -> forma(e, alpha)
                ElementType.LINE, ElementType.ARROW -> lineal(e, alpha)
                ElementType.FREEDRAW -> lapiz(e, alpha)
                ElementType.IMAGE -> imagen(e, alpha)
                ElementType.TEXT -> texto(e, alpha)
                ElementType.MOSAIC -> mosaico(e, alpha, debajo)
                ElementType.SERIAL -> serie(e, alpha)
                ElementType.PUNTO -> punto(e, alpha)
                ElementType.MEASURE -> cota(e, alpha)
                ElementType.ESCALA_GRAFICA -> escalaGrafica(e, alpha)
                // El marco es la hoja, no una raya: decide el encuadre y no se
                // dibuja. El foco va aparte, el último de todos.
                ElementType.FRAME, ElementType.SPOTLIGHT -> ""
            }
            if (dentro.isBlank()) return ""

            // La rotación se aplica igual que en pantalla: girando el papel
            // alrededor del centro del elemento, no rehaciendo su geometría.
            if (e.angle == 0.0) return dentro
            val c = getElementAbsoluteCoords(e)
            val grados = Math.toDegrees(e.angle)
            return "<g transform=\"rotate(${Svg.num(grados)} " +
                "${Svg.num(c.cx)} ${Svg.num(c.cy)})\">\n$dentro</g>\n"
        }

        // -- formas ---------------------------------------------------------

        /** Rectángulo, rombo, óvalo, arco y relleno de hueco: todo lo rugoso. */
        private fun forma(e: Element, alpha: Int): String {
            // El arco se dibuja como la línea que recorre, igual que en pantalla.
            if (e.type == ElementType.ARC) {
                val puntos = puntosDelArco(e)
                if (puntos.size < 2) return ""
                val comoLinea =
                    e.copy(type = ElementType.LINE, points = puntos, roundness = null)
                val g = buildShapeGeometry(comoLinea) ?: return ""
                return relleno(e, g, alpha) + trazoDe(e, Svg.camino(g.stroke), alpha)
            }

            if (e.type == ElementType.REGION) {
                val anillos = anillosDeRegion(e)
                if (anillos.isEmpty() || anillos.first().size < 3) return ""
                val silueta = Svg.caminoDeAnillos(anillos)
                // Sin trazo: el borde ya lo dibujan las figuras que encierran el
                // hueco, y repasarlo dejaría doble contorno.
                return if (needsRoughFill(e)) {
                    val rayas = Rough(roughOptionsFor(e)).fillPolygons(anillos)
                    rayado(e, silueta, Svg.camino(rayas), alpha, evenOdd = true)
                } else {
                    solido(e, silueta, alpha, evenOdd = true)
                }
            }

            val g = buildShapeGeometry(e) ?: return ""
            return relleno(e, g, alpha) + trazoDe(e, Svg.camino(g.stroke), alpha)
        }

        private fun lineal(e: Element, alpha: Int): String {
            if (absolutePoints(e).size < 2) return ""
            val cuerpo = StringBuilder(forma(e, alpha))
            if (e.type == ElementType.ARROW) {
                e.startArrowhead?.let { cuerpo.append(punta(e, ArrowEnd.START, it, alpha)) }
                e.endArrowhead?.let { cuerpo.append(punta(e, ArrowEnd.END, it, alpha)) }
            }
            return cuerpo.toString()
        }

        /**
         * El lápiz: la mancha rellena, no una línea recorrida.
         *
         * Es lo que le da los extremos afilados y el ancho que fluye con la
         * velocidad, y por eso va como camino relleno y no como `stroke`.
         */
        private fun lapiz(e: Element, alpha: Int): String {
            val pts = absolutePoints(e)
            if (pts.isEmpty()) return ""
            val contorno = getStroke(pts, e.pressures, strokeOptionsFor(e))
            if (contorno.isEmpty()) return ""

            val cuerpo = StringBuilder()
            if (rellenaSuLazo(e)) {
                val lazo = douglasPeucker(pts, FREEDRAW_FILL_TOLERANCE)
                val silueta = Svg.caminoSuaveCerrado(lazo)
                cuerpo.append(
                    if (needsRoughFill(e)) {
                        rayado(
                            e, silueta,
                            Svg.camino(Rough(roughOptionsFor(e)).fillPolygon(lazo)),
                            alpha, evenOdd = false
                        )
                    } else solido(e, silueta, alpha, evenOdd = false)
                )
            }
            val tinta = parseColor(e.strokeColor, alpha)
            cuerpo.append(
                "<path d=\"${Svg.caminoSuaveCerrado(contorno)}\" " +
                    "fill=\"${Svg.hex(tinta)}\"${opacidad(tinta)}/>\n"
            )
            return cuerpo.toString()
        }

        // -- relleno y trazo ------------------------------------------------

        private fun relleno(e: Element, g: ShapeGeometry, alpha: Int): String {
            if (!e.hasBackground || isTransparent(e.backgroundColor)) return ""
            val silueta = Svg.caminoCerrado(g.outline)
            if (silueta.isEmpty()) return ""
            val rayas = g.fill ?: return solido(e, silueta, alpha, evenOdd = false)
            return rayado(e, silueta, Svg.camino(rayas), alpha, evenOdd = false)
        }

        private fun solido(e: Element, silueta: String, alpha: Int, evenOdd: Boolean): String {
            val color = parseColor(e.backgroundColor, alpha)
            return "<path d=\"$silueta\" fill=\"${Svg.hex(color)}\"${opacidad(color)}" +
                (if (evenOdd) " fill-rule=\"evenodd\"" else "") + "/>\n"
        }

        /**
         * Las rayas del rayado, **recortadas a la silueta**.
         *
         * El barrido puede sobresalir un píxel por las esquinas, igual que en
         * pantalla; allí se recorta con `clipPath` y aquí con un `clipPath` de
         * SVG, que es la misma idea con otro nombre.
         */
        private fun rayado(
            e: Element, silueta: String, rayas: String, alpha: Int, evenOdd: Boolean
        ): String {
            if (rayas.isEmpty()) return ""
            val color = parseColor(e.backgroundColor, alpha)
            val id = "r${recorte++}"
            val regla = if (evenOdd) " clip-rule=\"evenodd\"" else ""
            return "<clipPath id=\"$id\"><path d=\"$silueta\"$regla/></clipPath>\n" +
                "<g clip-path=\"url(#$id)\">" +
                "<path d=\"$rayas\" fill=\"none\" stroke=\"${Svg.hex(color)}\"" +
                opacidad(color, "stroke-opacity") +
                " stroke-width=\"${Svg.num(e.strokeWidth / 2)}\" stroke-linecap=\"round\"/>" +
                "</g>\n"
        }

        private var recorte = 0

        /** El trazo de una figura, con su grosor, su color y sus guiones. */
        private fun trazoDe(e: Element, camino: String, alpha: Int): String {
            if (camino.isEmpty()) return ""
            val color = parseColor(e.strokeColor, alpha)
            // Con trazo no continuo se engorda medio punto, como en pantalla:
            // sin la doble pasada la línea se ve más fina que una sólida.
            val grosor =
                if (e.strokeStyle == StrokeStyle.SOLID) e.strokeWidth else e.strokeWidth + 0.5
            val guiones = Svg.guionesDe(e.strokeStyle, e.strokeWidth)
                ?.let { " stroke-dasharray=\"$it\"" } ?: ""
            return "<path d=\"$camino\" fill=\"none\" stroke=\"${Svg.hex(color)}\"" +
                opacidad(color, "stroke-opacity") +
                " stroke-width=\"${Svg.num(grosor)}\"" +
                " stroke-linecap=\"round\" stroke-linejoin=\"round\"$guiones/>\n"
        }

        /** Una raya suelta, con el pincel del elemento y sin guiones. */
        private fun raya(a: Pt, b: Pt, color: Int, grosor: Double): String =
            "<line x1=\"${Svg.num(a.x)}\" y1=\"${Svg.num(a.y)}\" " +
                "x2=\"${Svg.num(b.x)}\" y2=\"${Svg.num(b.y)}\" " +
                "stroke=\"${Svg.hex(color)}\"${opacidad(color, "stroke-opacity")} " +
                "stroke-width=\"${Svg.num(grosor)}\" stroke-linecap=\"round\"/>\n"

        // -- las puntas de las flechas --------------------------------------

        private fun punta(e: Element, en: ArrowEnd, cual: Arrowhead, alpha: Int): String {
            val f = getArrowheadPoints(e, en, cual) ?: return ""
            val color = parseColor(e.strokeColor, alpha)
            val grosor =
                if (e.strokeStyle == StrokeStyle.SOLID) e.strokeWidth else e.strokeWidth + 0.5

            fun cerrada(pts: List<Pt>, rellena: Boolean): String {
                val d = Svg.caminoCerrado(pts)
                return if (rellena) {
                    "<path d=\"$d\" fill=\"${Svg.hex(color)}\"${opacidad(color)}/>\n"
                } else {
                    "<path d=\"$d\" fill=\"none\" stroke=\"${Svg.hex(color)}\"" +
                        opacidad(color, "stroke-opacity") +
                        " stroke-width=\"${Svg.num(grosor)}\" " +
                        "stroke-linejoin=\"round\"/>\n"
                }
            }

            return when (cual) {
                Arrowhead.CIRCLE, Arrowhead.CIRCLE_OUTLINE -> {
                    val r = f.diameter / 2
                    val relleno = cual == Arrowhead.CIRCLE
                    "<circle cx=\"${Svg.num(f.tip.x)}\" cy=\"${Svg.num(f.tip.y)}\" " +
                        "r=\"${Svg.num(r)}\" " +
                        (if (relleno) "fill=\"${Svg.hex(color)}\"${opacidad(color)}"
                        else "fill=\"none\" stroke=\"${Svg.hex(color)}\"" +
                            opacidad(color, "stroke-opacity") +
                            " stroke-width=\"${Svg.num(grosor)}\"") + "/>\n"
                }
                Arrowhead.TRIANGLE, Arrowhead.TRIANGLE_OUTLINE -> cerrada(
                    listOf(f.tip, f.wings.first, f.wings.second),
                    cual == Arrowhead.TRIANGLE
                )
                Arrowhead.DIAMOND, Arrowhead.DIAMOND_OUTLINE -> {
                    val opuesto = f.opposite ?: return ""
                    cerrada(
                        listOf(f.tip, f.wings.first, opuesto, f.wings.second),
                        cual == Arrowhead.DIAMOND
                    )
                }
                // La flecha y la barra son dos rayas sueltas, sin cerrar.
                Arrowhead.ARROW, Arrowhead.BAR ->
                    raya(f.tip, f.wings.first, color, grosor) +
                        raya(f.tip, f.wings.second, color, grosor)
            }
        }

        // -- imagen y mosaico -----------------------------------------------

        private fun imagen(e: Element, alpha: Int): String {
            val fileId = e.fileId ?: return ""
            val bitmap = imageProvider(fileId) ?: return ""
            val c = getElementAbsoluteCoords(e)
            val ancho = c.x2 - c.x1
            val alto = c.y2 - c.y1
            if (ancho <= 0 || alto <= 0) return ""

            val recortada = recortar(bitmap, e.crop) ?: return ""
            val datos = incrustar(recortada, ancho, alto) ?: return ""

            // El volteo va en el signo de `scale`, igual que en pantalla. En SVG
            // no hay «escalar alrededor de un punto», así que se compone a mano:
            // llevar el centro al origen, reflejar y devolverlo a su sitio.
            val fx = if (e.scale.getOrElse(0) { 1.0 } < 0) -1 else 1
            val fy = if (e.scale.getOrElse(1) { 1.0 } < 0) -1 else 1
            val volteo = if (fx < 0 || fy < 0) {
                " transform=\"translate(${Svg.num(if (fx < 0) 2 * c.cx else 0.0)} " +
                    "${Svg.num(if (fy < 0) 2 * c.cy else 0.0)}) scale($fx $fy)\""
            } else ""

            return "<image x=\"${Svg.num(c.x1)}\" y=\"${Svg.num(c.y1)}\" " +
                "width=\"${Svg.num(ancho)}\" height=\"${Svg.num(alto)}\" " +
                "preserveAspectRatio=\"none\"" +
                (if (alpha < 255) " opacity=\"${Svg.num(alpha / 255.0)}\"" else "") +
                "$volteo xlink:href=\"$datos\"/>\n"
        }

        /**
         * El mosaico, incrustado a la resolución de su grano.
         *
         * Aquí está la parte que no se podía escribir como trazos y donde
         * conviene no ser purista: un mosaico **es** píxeles, así que se guardan
         * píxeles. Lo bueno es que los que hay que guardar son poquísimos —la
         * miniatura de la que sale, de unas decenas de píxeles de lado— y el
         * visor la estira igual que la estira la pantalla. Un mosaico que tapa
         * media captura ocupa en el archivo lo que un icono.
         *
         * `image-rendering` es lo que pide el canto duro al ampliar. Si el visor
         * no lo respeta, el mosaico sale suavizado en vez de a cuadros: se ve
         * distinto, pero **sigue tapando**, que es para lo que está.
         */
        private fun mosaico(e: Element, alpha: Int, debajo: List<Element>): String {
            val c = getElementAbsoluteCoords(e)
            val ancho = c.x2 - c.x1
            val alto = c.y2 - c.y1
            if (ancho < 1 || alto < 1) return ""

            val mini = renderizador.miniaturaDelMosaico(e, debajo)
                // Sin nada debajo que pixelar, la placa esmerilada de siempre:
                // no hay píxeles de dónde sacar el grano, pero tapar hay que tapar.
                ?: return "<rect x=\"${Svg.num(c.x1)}\" y=\"${Svg.num(c.y1)}\" " +
                    "width=\"${Svg.num(ancho)}\" height=\"${Svg.num(alto)}\" " +
                    "fill=\"#e0e0e4\" fill-opacity=\"${Svg.num(alpha * 220 / 255 / 255.0)}\"/>\n"

            val datos = comoDatos(mini) ?: return ""
            val filtrado = if (e.mosaicBlur) "auto" else "pixelated"
            return "<image x=\"${Svg.num(c.x1)}\" y=\"${Svg.num(c.y1)}\" " +
                "width=\"${Svg.num(ancho)}\" height=\"${Svg.num(alto)}\" " +
                "preserveAspectRatio=\"none\" " +
                "style=\"image-rendering:$filtrado\"" +
                (if (alpha < 255) " opacity=\"${Svg.num(alpha / 255.0)}\"" else "") +
                " xlink:href=\"$datos\"/>\n"
        }

        private fun recortar(bmp: Bitmap, crop: Crop?): Bitmap? {
            if (crop == null) return bmp
            return runCatching {
                val x = crop.x.toInt().coerceIn(0, max(0, bmp.width - 1))
                val y = crop.y.toInt().coerceIn(0, max(0, bmp.height - 1))
                val w = crop.width.toInt().coerceIn(1, bmp.width - x)
                val h = crop.height.toInt().coerceIn(1, bmp.height - y)
                Bitmap.createBitmap(bmp, x, y, w, h)
            }.getOrNull()
        }

        /** La imagen reducida a lo que hace falta y pasada a `data:`. */
        private fun incrustar(bmp: Bitmap, ancho: Double, alto: Double): String? {
            val destW = min(
                MAX_LADO_IMAGEN.toDouble(), ceil(ancho * DENSIDAD_IMAGEN)
            ).toInt().coerceAtLeast(1)
            val destH = min(
                MAX_LADO_IMAGEN.toDouble(), ceil(alto * DENSIDAD_IMAGEN)
            ).toInt().coerceAtLeast(1)
            val ajustado =
                if (bmp.width <= destW && bmp.height <= destH) bmp
                else runCatching {
                    Bitmap.createScaledBitmap(bmp, destW, destH, true)
                }.getOrNull() ?: bmp
            return comoDatos(ajustado)
        }

        /**
         * Un mapa de bits como URI `data:`.
         *
         * JPEG si es opaco y PNG si tiene transparencia. No es una manía: el
         * JPEG rellenaría de negro lo transparente, y el PNG de una foto pesa
         * varias veces más para verse igual.
         */
        private fun comoDatos(bmp: Bitmap): String? = runCatching {
            val opaco = !bmp.hasAlpha()
            val salida = ByteArrayOutputStream()
            if (opaco) {
                bmp.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, salida)
            } else {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, salida)
            }
            val tipo = if (opaco) "jpeg" else "png"
            "data:image/$tipo;base64," +
                Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()

        // -- el foco ---------------------------------------------------------

        /**
         * Todos los focos en **una sola sombra con todos los huecos**.
         *
         * Los huecos van como polilíneas y no como rectángulos redondeados de
         * SVG porque así se pueden girar punto a punto: un `rect` con `rx` no se
         * puede meter dentro de un camino par/impar, y sin par/impar el agujero
         * no es un agujero.
         */
        fun focos(focos: List<Element>): String {
            val d = StringBuilder(
                Svg.caminoCerrado(
                    listOf(
                        Pt(caja.x1, caja.y1), Pt(caja.x2, caja.y1),
                        Pt(caja.x2, caja.y2), Pt(caja.x1, caja.y2)
                    )
                )
            )
            for (f in focos) d.append(Svg.caminoCerrado(huecoDelFoco(f)))

            val opacidad = (focos.maxOf { it.opacity } * 255 / 100).coerceIn(0, 255)
            val negro = opacidad * SPOTLIGHT_DIM / 255 / 255.0
            return "<path d=\"$d\" fill=\"#000000\" fill-rule=\"evenodd\" " +
                "fill-opacity=\"${Svg.num(negro)}\"/>\n"
        }

        private fun huecoDelFoco(f: Element): List<Pt> {
            val c = getElementAbsoluteCoords(f)
            val r = min(c.x2 - c.x1, c.y2 - c.y1) * SPOTLIGHT_REDONDEO
            val pts = ArrayList<Pt>(4 * PASOS_DE_ESQUINA + 4)
            // Las cuatro esquinas, en el sentido contrario al del marco: es lo
            // que hace que par/impar lo lea como agujero y no como otra mancha.
            val esquinas = listOf(
                Triple(c.x2 - r, c.y2 - r, 0.0),
                Triple(c.x1 + r, c.y2 - r, 90.0),
                Triple(c.x1 + r, c.y1 + r, 180.0),
                Triple(c.x2 - r, c.y1 + r, 270.0)
            )
            for ((cx, cy, desde) in esquinas) {
                for (i in 0..PASOS_DE_ESQUINA) {
                    val a = Math.toRadians(desde + 90.0 * i / PASOS_DE_ESQUINA)
                    pts.add(Pt(cx + r * cos(a), cy + r * sin(a)))
                }
            }
            if (f.angle == 0.0) return pts
            return pts.map { pointRotateRads(it, Pt(c.cx, c.cy), f.angle) }
        }

        // -- la escala gráfica -----------------------------------------------

        private fun escalaGrafica(e: Element, alpha: Int): String {
            val c = getElementAbsoluteCoords(e)
            val ancho = c.x2 - c.x1
            val altoCaja = c.y2 - c.y1
            if (ancho <= 1.0 || altoCaja <= 1.0) return ""
            val barra = barraDeEscala(ancho, scene.escala) ?: return ""
            val alto = (altoCaja * ALTO_DE_LA_BARRA).coerceAtLeast(1.0)
            val tinta = parseColor(e.strokeColor, alpha)
            val papel = contrastingTextColor(tinta, alpha)

            val s = StringBuilder()
            for (i in 0 until barra.tramos) {
                val x = c.x1 + i * barra.anchoDeTramo
                val color = if (i % 2 == 0) tinta else papel
                s.append(
                    "<rect x=\"${Svg.num(x)}\" y=\"${Svg.num(c.y1)}\" " +
                        "width=\"${Svg.num(barra.anchoDeTramo)}\" height=\"${Svg.num(alto)}\" " +
                        "fill=\"${Svg.hex(color)}\"${opacidad(color)}/>\n"
                )
            }
            s.append(
                "<rect x=\"${Svg.num(c.x1)}\" y=\"${Svg.num(c.y1)}\" " +
                    "width=\"${Svg.num(barra.ancho)}\" height=\"${Svg.num(alto)}\" " +
                    "fill=\"none\" stroke=\"${Svg.hex(tinta)}\"" +
                    opacidad(tinta, "stroke-opacity") +
                    " stroke-width=\"${Svg.num(e.strokeWidth.coerceAtLeast(1.0))}\"/>\n"
            )

            val tam = (e.fontSize ?: (altoCaja * (1 - ALTO_DE_LA_BARRA) * 0.8))
                .coerceAtLeast(1.0)
            val base = c.y1 + alto + tam * 1.05
            for (i in 0..barra.tramos) {
                val x = c.x1 + i * barra.anchoDeTramo
                val alineado = when (i) {
                    0 -> Paint.Align.LEFT
                    barra.tramos -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
                val etiqueta =
                    if (i == barra.tramos) "${barra.etiqueta(i)} ${barra.unidad}"
                    else barra.etiqueta(i)
                s.append(
                    enCurvas(etiqueta, x, base, tam, e.fontFamily, alineado, tinta, false)
                )
            }
            return s.toString()
        }

        // -- la cota ---------------------------------------------------------

        private fun cota(e: Element, alpha: Int): String {
            val pts = absolutePoints(e)
            if (pts.size < 2) return ""
            val a = pts.first()
            val b = pts.last()
            val dx = b.x - a.x
            val dy = b.y - a.y
            val largo = hypot(dx, dy)
            if (largo < MIN_MEASURE_LENGTH) return ""

            val color = parseColor(e.strokeColor, alpha)
            val s = StringBuilder()

            // Con el mismo pulso que el resto: los dos trozos pasan por el
            // generador rugoso, igual que en pantalla.
            fun trazo(desde: Pt, hasta: Pt) {
                val rough = Rough(roughOptionsFor(e).copy(preserveVertices = true))
                val d = Svg.camino(rough.doubleLine(desde.x, desde.y, hasta.x, hasta.y))
                s.append(
                    "<path d=\"$d\" fill=\"none\" stroke=\"${Svg.hex(color)}\"" +
                        opacidad(color, "stroke-opacity") +
                        " stroke-width=\"${Svg.num(e.strokeWidth)}\" " +
                        "stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n"
                )
            }

            val hueco = huecoDelRotulo(e, largo)
            if (hueco <= 0.0) {
                trazo(a, b)
            } else {
                val ux = dx / largo
                val uy = dy / largo
                val corte = (largo - hueco) / 2
                trazo(a, Pt(a.x + ux * corte, a.y + uy * corte))
                trazo(Pt(b.x - ux * corte, b.y - uy * corte), b)
            }

            // Los banderines de los extremos y las medias puntas.
            val nx = -dy / largo
            val ny = dx / largo
            val ala = (e.strokeWidth * MEASURE_TICK).coerceAtLeast(MEASURE_TICK_MIN)
            for (extremo in listOf(a, b)) {
                s.append(
                    raya(
                        Pt(extremo.x - nx * ala, extremo.y - ny * ala),
                        Pt(extremo.x + nx * ala, extremo.y + ny * ala),
                        color, e.strokeWidth
                    )
                )
            }
            s.append(puntaDeCota(a, b, e.strokeWidth, color))
            s.append(puntaDeCota(b, a, e.strokeWidth, color))
            s.append(rotulo(e, a, b, dx, dy, color, alpha))
            return s.toString()
        }

        private fun puntaDeCota(en: Pt, hacia: Pt, grosor: Double, color: Int): String {
            val ang = atan2(hacia.y - en.y, hacia.x - en.x)
            val largo = (grosor * MEASURE_HEAD).coerceAtLeast(MEASURE_HEAD_MIN)
            return listOf(-1, 1).joinToString("") { s ->
                val giro = ang + s * MEASURE_HEAD_ANGLE
                raya(en, Pt(en.x + largo * cos(giro), en.y + largo * sin(giro)), color, grosor)
            }
        }

        private fun huecoDelRotulo(e: Element, largo: Double): Double {
            val tam = e.fontSize ?: MEASURE_TEXT_SIZE
            if (tam <= 0.0) return 0.0
            medidor.reset()
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(e.fontFamily)
            val ancho = medidor.measureText(textoDeCota(e, scene.escala)).toDouble()
            val hueco = ancho + tam * MEASURE_LABEL_GAP * 2
            return if (hueco > largo * MAXIMO_HUECO) 0.0 else hueco
        }

        /** El número de la cota: halo detrás y cifra encima, y nunca del revés. */
        private fun rotulo(
            e: Element, a: Pt, b: Pt, dx: Double, dy: Double, color: Int, alpha: Int
        ): String {
            val texto = textoDeCota(e, scene.escala)
            val tam = e.fontSize ?: MEASURE_TEXT_SIZE
            if (tam <= 0.0 || texto.isEmpty()) return ""

            medidor.reset()
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(e.fontFamily)
            val fm = medidor.fontMetrics
            val separacion = -(fm.ascent + fm.descent) / 2.0

            val grados = Math.toDegrees(atan2(dy, dx))
            val giro = if (rotuloDelReves(grados)) grados + 180.0 else grados
            val cx = (a.x + b.x) / 2
            val cy = (a.y + b.y) / 2

            val perfil = perfilDe(texto, 0.0, separacion, tam, e.fontFamily, Paint.Align.CENTER)
            if (perfil.isEmpty()) return ""
            val d = Svg.caminoDeContornos(perfil)
            val halo = contrastingTextColor(color, alpha)

            return "<g transform=\"translate(${Svg.num(cx)} ${Svg.num(cy)}) " +
                "rotate(${Svg.num(giro)})\">\n" +
                // El halo primero y la cifra encima: al revés se comería los perfiles.
                "<path d=\"$d\" fill=\"none\" stroke=\"${Svg.hex(halo)}\"" +
                opacidad(halo, "stroke-opacity") +
                " stroke-width=\"${Svg.num(tam * MEASURE_HALO)}\" " +
                "stroke-linejoin=\"round\"/>\n" +
                "<path d=\"$d\" fill=\"${Svg.hex(color)}\"${opacidad(color)}/>\n" +
                "</g>\n"
        }

        // -- el número de serie ----------------------------------------------

        private fun serie(e: Element, alpha: Int): String {
            val c = getElementAbsoluteCoords(e)
            val radio = min(c.x2 - c.x1, c.y2 - c.y1) / 2
            if (radio <= 0) return ""

            val fondo = parseColor(e.strokeColor, alpha)
            val s = StringBuilder(
                "<circle cx=\"${Svg.num(c.cx)}\" cy=\"${Svg.num(c.cy)}\" " +
                    "r=\"${Svg.num(radio)}\" fill=\"${Svg.hex(fondo)}\"${opacidad(fondo)}/>\n"
            )
            val texto = e.text ?: return s.toString()

            // Centrado óptico: la línea base no es el centro del glifo, así que
            // sin corregir el número queda alto dentro del círculo.
            val tam = radio * SERIAL_TEXT_RATIO
            medidor.reset()
            medidor.textSize = tam.toFloat()
            medidor.isFakeBoldText = true
            val fm = medidor.fontMetrics
            val base = c.cy - (fm.ascent + fm.descent) / 2.0
            s.append(
                enCurvas(
                    texto, c.cx, base, tam, null, Paint.Align.CENTER,
                    contrastingTextColor(fondo, alpha), negrita = true
                )
            )
            medidor.isFakeBoldText = false
            return s.toString()
        }

        /**
         * Un punto con su letra.
         *
         * El redondel va negro con aro blanco pase lo que pase con el color del
         * trazo: es una referencia que se cita en el texto, y tiene que leerse
         * sobre lo que sea. La letra sí lleva el color, y su halo, igual que en
         * pantalla.
         */
        private fun punto(e: Element, alpha: Int): String {
            val s = StringBuilder(
                "<circle cx=\"${Svg.num(e.x)}\" cy=\"${Svg.num(e.y)}\" " +
                    "r=\"${Svg.num(RADIO_DEL_PUNTO)}\" fill=\"#000000\"" +
                    (if (alpha < 255) " fill-opacity=\"${Svg.num(alpha / 255.0)}\"" else "") +
                    "/>\n"
            )
            val texto = e.text
            if (texto.isNullOrEmpty()) return s.toString()

            val tam = (e.fontSize ?: 22.0).coerceAtLeast(1.0)
            val donde = sitioDeLaEtiqueta(e)
            val tinta = parseColor(e.strokeColor, alpha)

            medidor.reset()
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(e.fontFamily)
            val fm = medidor.fontMetrics
            val base = donde.y - (fm.ascent + fm.descent) / 2.0

            val perfil = perfilDe(texto, donde.x, base, tam, e.fontFamily, Paint.Align.CENTER)
            if (perfil.isEmpty()) return s.toString()
            val d = Svg.caminoDeContornos(perfil)
            val halo = contrastingTextColor(tinta, alpha)
            s.append(
                "<path d=\"$d\" fill=\"none\" stroke=\"${Svg.hex(halo)}\"" +
                    opacidad(halo, "stroke-opacity") +
                    " stroke-width=\"${Svg.num(tam * 0.22)}\" stroke-linejoin=\"round\"/>\n"
            )
            s.append("<path d=\"$d\" fill=\"${Svg.hex(tinta)}\"${opacidad(tinta)}/>\n")
            return s.toString()
        }

        // -- el texto ---------------------------------------------------------

        private fun texto(e: Element, alpha: Int): String {
            val contenido = e.text ?: return ""
            if (contenido.isEmpty()) return ""
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

            medidor.reset()
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(e.fontFamily)
            // La primera línea se apoya en su ascendente, igual que en pantalla
            // y que en el cuadro de escribir. Ver la nota del renderizador.
            var y = c.y1 - medidor.fontMetrics.ascent.toDouble()
            val alto = tam * 1.25

            val s = StringBuilder()
            for (linea in contenido.split('\n')) {
                if (linea.isNotBlank()) {
                    s.append(enCurvas(linea, x, y, tam, e.fontFamily, alineado, color, false))
                }
                y += alto
            }
            return s.toString()
        }

        /** Una línea de texto ya convertida en su silueta. */
        private fun enCurvas(
            texto: String, x: Double, y: Double, tam: Double, familia: Int?,
            alineado: Paint.Align, color: Int, negrita: Boolean
        ): String {
            val perfil = perfilDe(texto, x, y, tam, familia, alineado, negrita)
            if (perfil.isEmpty()) return ""
            return "<path d=\"${Svg.caminoDeContornos(perfil)}\" " +
                "fill=\"${Svg.hex(color)}\"${opacidad(color)}/>\n"
        }

        /**
         * El perfil de un texto, contorno a contorno.
         *
         * `getTextPath` da la silueta —respetando la fuente, el tamaño y la
         * alineación, igual que `drawText`— y [PathMeasure] la recorre **un
         * contorno cada vez**, que es lo que hace falta para que el hueco de una
         * «o» siga siendo un hueco. Se muestrea denso y luego se simplifica: sale
         * mucho más ligero que muestrear justo, y sin decidir de antemano dónde
         * hace falta detalle.
         */
        private fun perfilDe(
            texto: String, x: Double, y: Double, tam: Double, familia: Int?,
            alineado: Paint.Align, negrita: Boolean = false
        ): List<List<Pt>> {
            medidor.reset()
            medidor.isAntiAlias = true
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(familia)
            medidor.textAlign = alineado
            medidor.isFakeBoldText = negrita

            val camino = Path()
            medidor.getTextPath(texto, 0, texto.length, x.toFloat(), y.toFloat(), camino)
            if (camino.isEmpty) return emptyList()

            val contornos = ArrayList<List<Pt>>()
            val medida = PathMeasure(camino, false)
            val pos = FloatArray(2)
            do {
                val largo = medida.length
                if (largo > 0f) {
                    val pasos = max(3, ceil(largo / PASO_DEL_PERFIL).toInt())
                    val puntos = ArrayList<Pt>(pasos + 1)
                    for (i in 0..pasos) {
                        val d = largo * i / pasos
                        if (medida.getPosTan(d, pos, null)) {
                            puntos.add(Pt(pos[0].toDouble(), pos[1].toDouble()))
                        }
                    }
                    if (puntos.size >= 3) {
                        contornos.add(douglasPeucker(puntos, TOLERANCIA_DEL_PERFIL))
                    }
                }
            } while (medida.nextContour())
            return contornos
        }

        // -- utilidades ------------------------------------------------------

        /** El atributo de transparencia, y nada si el color es opaco. */
        private fun opacidad(argb: Int, atributo: String = "fill-opacity"): String {
            val a = Svg.alfa(argb)
            return if (a >= 1.0) "" else " $atributo=\"${Svg.num(a)}\""
        }
    }

    // Las constantes que comparte con el renderizador. Están duplicadas a
    // conciencia y no sacadas a un sitio común: son de **cómo se pinta**, y el
    // día que cambien en pantalla hay que venir aquí a mirar si el SVG las
    // quiere igual. Un valor compartido escondería esa decisión.
    private const val REFERENCIA_OPACIDAD = 35
    private const val SPOTLIGHT_DIM = 96
    private const val SPOTLIGHT_REDONDEO = 0.35
    private const val PASOS_DE_ESQUINA = 6
    private const val SERIAL_TEXT_RATIO = 1.25
    private const val MIN_MEASURE_LENGTH = 0.5
    private const val MEASURE_TICK = 3.0
    private const val MEASURE_TICK_MIN = 6.0
    private const val MEASURE_HEAD = 5.0
    private const val MEASURE_HEAD_MIN = 9.0
    private val MEASURE_HEAD_ANGLE = Math.toRadians(20.0)
    private const val MEASURE_TEXT_SIZE = 20.0
    private const val MEASURE_LABEL_GAP = 0.45
    private const val MAXIMO_HUECO = 0.72
    private const val MEASURE_HALO = 0.22
}
