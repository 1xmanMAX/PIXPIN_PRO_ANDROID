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
     * Cuánto puede desviarse el contorno del lápiz al aligerarlo, en píxeles de escena.
     *
     * Un tercio de píxel no se ve ni ampliando: el trazo de un lápiz mide varios píxeles de
     * ancho y su borde está suavizado. Con eso se queda con menos de la mitad de los puntos.
     */
    private const val TOLERANCIA_DEL_CONTORNO = 0.3

    /** Calidad del WEBP de las imágenes con transparencia. Ver [Lapicero.comoDatos]. */
    private const val CALIDAD_WEBP = 85

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

    /** El punto tecleado y su número, en píxeles de escena. Los mismos del PDF. */
    private const val RADIO_DE_TABLA = 3.25
    private const val LETRA_DE_TABLA = 15.0

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
        imageProvider: (String) -> Bitmap? = { null },
        /**
         * La página sobre la que se dibujó, si la hay.
         *
         * Es el papel que en pantalla se ve de fondo al anotar un PDF: su
         * píxel (0,0) es el (0,0) de la escena. **No es un elemento**, así que
         * ningún recorrido por los elementos lo encuentra — aquí estaba el
         * fallo de que la página no salía en lo exportado: se exportaban las
         * anotaciones flotando sobre nada.
         */
        papel: Bitmap? = null,
        /**
         * **El mismo papel, pero con todos los píxeles que se puedan.**
         *
         * [papel] manda en la geometría —su tamaño **son** las unidades de la escena, porque
         * sobre él se dibujó— y por eso no se puede cambiar por uno más grande sin descolocar
         * lo anotado. Pero en la página web el papel es una imagen, y una imagen se puede
         * poner con más resolución de la que ocupa: aquí entra la página rasterizada al
         * detalle, colocada en el sitio y el tamaño de [papel]. Al ampliar en el navegador se
         * lee, en vez de verse la mancha de mil cuatrocientos píxeles que salía antes.
         */
        papelFino: Bitmap? = null,
        /**
         * **Qué hoja del dibujo se escribe**, cuando el dibujo tiene varias.
         *
         * Nula, sale lo de siempre: el dibujo entero, o la primera hoja si hay marcos. Con
         * una puesta, sale **esa**, encuadrada en ella y con lo que cae dentro —y con su
         * pauta debajo, que es parte del papel y no un trazo—. Es lo que convierte un
         * lienzo con tres láminas en un documento web de tres páginas, igual que el PDF
         * saca una página por marco. Ver [ExportarHtml.paginas] y [DrawPdf].
         */
        soloEstaHoja: Element? = null,
        /**
         * **El papel va aparte y no como imagen dentro del SVG.**
         *
         * Con un plano leído como geometría (ver `PlanoWeb`), el papel lo pinta el visor en su
         * propio lienzo: aquí [papel] sigue mandando en el encuadre —el tamaño de la página
         * **son** las unidades de la escena— pero no se incrusta la fotografía, que es
         * justamente lo que se quería quitar de encima.
         */
        papelAparte: Boolean = false
    ): String? = runCatching {
        val contenido =
            if (soloEstaHoja != null)
                listOfNotNull(soloEstaHoja.takeIf { it.papel != null }) + scene.contenidoDe(soloEstaHoja)
            else scene.contenidoVisible
        // Las guías se pintan o no según el mismo interruptor que en pantalla:
        // el SVG tiene que parecerse a lo que se está viendo, no a otra cosa.
        val pintables =
            if (scene.referenciasVisibles) contenido
            else contenido.filter { !it.reference }
        if (pintables.isEmpty() && papel == null) return null

        val marco = soloEstaHoja ?: scene.marco
        // Con página, el encuadre es la página (y lo que se salga de ella):
        // exportar la anotación de una hoja recortando la hoja dejaría el
        // resultado sin el contexto que le da sentido.
        val b = when {
            marco != null -> getElementBounds(marco)
            papel != null -> {
                val pagina = Bounds(0.0, 0.0, papel.width.toDouble(), papel.height.toDouble())
                if (pintables.isEmpty()) pagina else {
                    val c = getCommonBounds(pintables)
                    Bounds(
                        min(pagina.x1, c.x1), min(pagina.y1, c.y1),
                        max(pagina.x2, c.x2), max(pagina.y2, c.y2)
                    )
                }
            }
            else -> getCommonBounds(pintables)
        }
        val margen = if (marco != null || papel != null) 0.0 else MARGEN
        val caja = Bounds(b.x1 - margen, b.y1 - margen, b.x2 + margen, b.y2 + margen)
        if (caja.width <= 0 || caja.height <= 0) return null

        // **De noche, la tinta se da la vuelta.** En la aplicación el modo noche es un filtro
        // de pintado —los colores guardados siguen siendo los del día— y el SVG no lo pasaba:
        // un dibujo hecho sobre papel de pizarra se exportaba con su tinta negra original, o
        // sea negro sobre negro. Ver [DrawTheme.filtrar].
        val pincel = Lapicero(
            DrawFonts.provider(context), imageProvider, scene, caja, papel,
            DrawTheme.esDeNoche(scene.backgroundColor)
        )
        val cuerpo = StringBuilder()
        if (!papelAparte) papel?.let { cuerpo.append(pincel.papel(papelFino ?: it, it.width, it.height)) }
        for ((i, e) in pintables.withIndex()) {
            if (e.type == ElementType.SPOTLIGHT) continue
            cuerpo.append(pincel.elemento(e, pintables.subList(0, i)))
        }
        // Los puntos tecleados, que **no son elementos**: viven en las tablas y
        // ningún recorrido por los elementos los ve. El mismo hueco que tenía el
        // PDF, y por el mismo motivo — no hay tipo que barrer.
        scene.origenCoordenadas?.let { origen ->
            for (tabla in scene.tablas.filter { it.visible }) {
                cuerpo.append(pincel.tabla(tabla, origen, scene.escala))
            }
        }

        // El foco el último de todos y de una sola pieza, por lo mismo que en
        // pantalla: dos sombras superpuestas oscurecen el doble donde se cruzan.
        pintables.filter { it.type == ElementType.SPOTLIGHT }
            .takeIf { it.isNotEmpty() }
            ?.let { cuerpo.append(pincel.focos(it)) }

        // **Sin papel dentro, tampoco fondo.** El SVG empieza siempre por un rectángulo del
        // color del lienzo que lo tapa todo; con el papel aparte —el plano pintado debajo, en
        // su propio lienzo— ese rectángulo tapaba justo el plano, y la página exportada salía
        // en blanco con solo las anotaciones encima. Ver [papelAparte] y `VisorPlano`.
        val fondo = if (papelAparte) null else Svg.hex(parseColor(scene.backgroundColor))
        // Los glifos, delante: un `<use>` puede apuntar a algo que venga después, pero hay
        // lectores de SVG que agradecen encontrarlo antes.
        Svg.documento(caja, fondo, pincel.defs() + cuerpo.toString())
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
        imageProvider: (String) -> Bitmap? = { null },
        papel: Bitmap? = null,
        /** El mismo papel con más píxeles, si se tiene. Ver [aTexto]. */
        papelFino: Bitmap? = null
    ): File? = runCatching {
        val texto = aTexto(context, scene, imageProvider, papel, papelFino) ?: return null
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
        private val caja: Bounds,
        /** La página de fondo, si la hay. Ver [aTexto]. */
        private val papel: Bitmap? = null,
        /** Si el papel es oscuro. Ver [DrawTheme.filtrar]. */
        private val noche: Boolean = false
    ) {

        /**
         * **El color tal como se ve**, no como está guardado: pasado por el filtro del modo
         * noche, igual que hace el renderizador de la pantalla.
         *
         * Tapa a propósito a la función del mismo nombre del paquete, que es la que se llama
         * en las dos docenas de sitios donde este escritor pone un color. Un solo punto por
         * el que pasan todos, en vez de acordarse en cada uno.
         */
        private fun parseColor(color: String, alpha: Int = 255): Int =
            DrawTheme.filtrar(com.forge.pixpin.motor.parseColor(color, alpha), noche)

        /** Solo para medir y para sacar perfiles; no pinta nunca. */
        private val medidor = Paint(Paint.ANTI_ALIAS_FLAG)

        /**
         * **Cada letra, definida una sola vez.**
         *
         * Una página de texto son miles de letras y cada una, como perfil, son decenas de
         * puntos: escribiéndolas todas seguidas, un documento con cuatro notas pesaba dos
         * megas (lo reportó el usuario). El perfil de una letra no depende de dónde esté
         * —solo de la fuente, el tamaño y la letra—, así que se define una vez en `<defs>` y
         * cada aparición es un `<use>` de cuarenta bytes. Sigue sin haber ninguna fuente:
         * son los mismos perfiles, guardados una vez en vez de mil.
         */
        private val glifos = LinkedHashMap<String, String>()
        private val definiciones = StringBuilder()

        /** Las definiciones de los glifos, para poner delante del cuerpo. */
        fun defs(): String =
            if (definiciones.isEmpty()) "" else "<defs>\n$definiciones</defs>\n"

        /**
         * El renderizador, **solo para los mosaicos y las lupas**.
         *
         * Un mosaico no se puede escribir como trazos: lo que hace es coger los
         * píxeles de debajo. Quien sabe sacarlos es el renderizador, así que se
         * le piden a él en vez de reimplementar la reducción aquí y arriesgarse
         * a que las dos versiones se separen.
         *
         * **Con la página debajo.** Se construía sin telón, y de ahí que en lo
         * exportado la lupa enseñara un cristal en blanco y el mosaico una
         * placa esmerilada: para el renderizador no había nada debajo que
         * ampliar ni que pixelar. La página es lo que en pantalla ve la lupa,
         * así que aquí tiene que verla igual.
         */
        private val renderizador by lazy { Renderer(imageProvider, backdrop = papel) }

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
                ElementType.PLANO, ElementType.RECTA, ElementType.ESPACIO -> plano(e, alpha)
                // **El volumen se escribe como lo que es: polígonos rellenos.**
                // No hay nada que perder por el camino —una caja isométrica *son*
                // tres cuadriláteros con su color— así que sale idéntica a como
                // se ve, y sin ninguna de las cosas que un SVG no sabe hacer.
                ElementType.SOLIDO -> volumen(e, alpha)
                ElementType.CRONOGRAMA -> cronograma(e, alpha)
                // El marco es la hoja, no una raya: decide el encuadre y no se
                // dibuja.
                // El marco no se dibuja —es el encuadre— salvo su pauta, que sí es
                // parte de la hoja: se eligió papel rayado para que salga rayado.
                ElementType.FRAME -> pautaDeLaHoja(e, alpha)
                // **El foco sí se guarda.** Desde que su sombra vive dentro de
                // un marco es dibujo como cualquier otro: el anillo entre el
                // marco y la figura señalada, pintado. Antes oscurecía la
                // pantalla entera y eso no se podía escribir en un archivo.
                ElementType.SPOTLIGHT -> foco(e)
                // La lupa se guarda **con sus píxeles dentro**, como el mosaico:
                // lo que enseña no son trazos sino un trozo del dibujo a otra
                // escala, y describirlo con figuras sería volver a dibujarlo.
                ElementType.LUPA -> lupa(e, alpha, debajo)
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

        /**
         * La caja en volumen: la sombra y sus caras, de atrás hacia delante.
         *
         * Se reusan [solido], [rayado] y [trazoDe] pasándoles **un elemento con
         * el fondo ya aclarado** en vez de duplicar aquí el cálculo del color.
         * Es una copia barata —el elemento es un `data class`— y garantiza que
         * una cara exportada lleve exactamente el mismo relleno, el mismo rayado
         * y la misma opacidad que cualquier otra figura del archivo.
         *
         * Un solo generador rugoso para las tres caras y recorridas en orden,
         * igual que en pantalla: es lo que hace que el SVG salga con el mismo
         * garabato que se estaba viendo.
         */
        private fun volumen(e: Element, alpha: Int): String {
            val caras = carasDeElemento(e, scene.vista)
            if (caras.isEmpty()) return ""
            val salida = StringBuilder()

            // Sin sombra, igual que en pantalla: lo exportado tiene que ser lo dibujado.
            val rough = Rough(roughOptionsFor(e))
            val rugoso = needsRoughFill(e)
            val hayFondo = !isTransparent(e.backgroundColor)
            for (cara in caras) {
                val silueta = Svg.caminoCerrado(cara.poligono)
                if (hayFondo && silueta.isNotEmpty()) {
                    val conTono =
                        e.copy(backgroundColor = aclarar(e.backgroundColor, cara.claridad))
                    salida.append(
                        if (rugoso) {
                            rayado(
                                conTono, silueta,
                                Svg.camino(rough.fillPolygon(cara.poligono)),
                                alpha, evenOdd = false
                            )
                        } else {
                            solido(conTono, silueta, alpha, evenOdd = false)
                        }
                    )
                }
                salida.append(trazoDe(e, Svg.camino(rough.polygon(cara.poligono)), alpha))
            }
            return salida.toString()
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
            // **Aligerado antes de escribirlo.** El contorno que da el lápiz es densísimo —un
            // punto cada píxel, por los dos lados— y escrito tal cual, un cuaderno de letra
            // manuscrita pesaba tres megas y medio (lo trajo el usuario). Se tiran los puntos
            // que no cambian la forma en más de un tercio de píxel y se escribe compacto:
            // el mismo trazo, diez veces menos bytes. Ver [Svg.caminoDelLapiz].
            val fino = douglasPeucker(contorno, TOLERANCIA_DEL_CONTORNO)
            cuerpo.append(
                "<path d=\"${Svg.caminoDelLapiz(fino)}\" " +
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

        /**
         * La lupa, como una imagen incrustada.
         *
         * En pantalla vuelve a dibujar la escena dentro del cristal; en un
         * archivo eso serían todos esos trazos otra vez, recortados a mano y a
         * otra escala. Un trozo de imagen dice lo mismo, se abre en cualquier
         * visor y ocupa lo que ocupa un icono. La montura y la guía sí van como
         * figuras: son dibujo, no contenido.
         */
        private fun lupa(e: Element, alpha: Int, debajo: List<Element>): String {
            val c = getElementAbsoluteCoords(e)
            val ancho = c.x2 - c.x1
            val alto = c.y2 - c.y1
            if (ancho < 1 || alto < 1) return ""

            val cuerpo = StringBuilder()
            val cristal = Svg.camino(opsDePuntos(puntosDelCristal(e, c), cerrado = true))

            renderizador.contenidoDeLaLupa(e, debajo)?.let { mini ->
                comoDatos(mini)?.let { datos ->
                    // El recorte: es lo que hace que un cristal redondo lo sea
                    // también en el archivo y no un cuadro con un aro pintado.
                    val id = "lupa" + e.id.hashCode().toUInt().toString(16)
                    cuerpo.append("<defs><clipPath id=\"$id\">")
                    cuerpo.append("<path d=\"$cristal\"/>")
                    cuerpo.append("</clipPath></defs>\n")
                    cuerpo.append(
                        "<image x=\"${Svg.num(c.x1)}\" y=\"${Svg.num(c.y1)}\" " +
                            "width=\"${Svg.num(ancho)}\" height=\"${Svg.num(alto)}\" " +
                            "preserveAspectRatio=\"none\" clip-path=\"url(#$id)\"" +
                            (if (alpha < 255) " opacity=\"${Svg.num(alpha / 255.0)}\"" else "") +
                            " xlink:href=\"$datos\"/>\n"
                    )
                }
            }

            cuerpo.append(trazoDe(e, cristal, alpha))
            if (lineasDeLaGuia(e).isNotEmpty()) {
                cuerpo.append(
                    trazoDe(
                        e,
                        Svg.camino(opsDePuntos(puntosDelFoco(e), cerrado = true)),
                        alpha * 3 / 4
                    )
                )
                for ((a, b) in lineasDeLaGuia(e)) {
                    cuerpo.append(raya(b, a, parseColor(e.strokeColor, alpha), e.strokeWidth))
                }
            }
            return cuerpo.toString()
        }

        /** El anillo del foco: el marco menos la figura, con regla par-impar. */
        private fun foco(e: Element): String {
            val c = getElementAbsoluteCoords(e)
            val marco = Svg.camino(opsDePuntos(esquinasDeCaja(Bounds(c.x1, c.y1, c.x2, c.y2)), true))
            val dentro = puntosDelFoco(e)
            val hueco = if (dentro.size >= 3) Svg.camino(opsDePuntos(dentro, cerrado = true)) else ""
            val negro = (oscurecimientoDe(e) / 100.0)
            return "<path d=\"$marco $hueco\" fill=\"#000000\" " +
                "fill-opacity=\"${Svg.num(negro)}\" fill-rule=\"evenodd\"/>\n"
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
        /**
         * La página de fondo, incrustada **debajo de todo** y en su sitio: su
         * píxel (0,0) es el (0,0) de la escena, igual que la pinta la pantalla.
         */
        fun papel(bmp: Bitmap, ancho: Int, alto: Int): String {
            // **Siempre JPEG.** La página se dibuja sobre blanco —no tiene nada
            // transparente— pero el mapa de bits sigue declarando canal alfa, y
            // por ese camino salía en PNG: una hoja escaneada eran varios megas
            // en base64 dentro de la página web, que el navegador del móvil
            // tardaba en abrir o directamente no abría. En JPEG pesa diez veces
            // menos y se ve igual.
            val datos = comoDatos(bmp, opaco = true) ?: return ""
            // El tamaño es el del papel en unidades de la escena, no el del mapa de bits: si
            // viene uno más fino, se coloca aquí mismo y el navegador lo enseña al ampliar.
            return "<image x=\"0\" y=\"0\" width=\"$ancho\" height=\"$alto\" " +
                "xlink:href=\"$datos\"/>\n"
        }

        private fun comoDatos(bmp: Bitmap, opaco: Boolean = !tieneTransparencia(bmp)): String? =
            runCatching {
                val salida = ByteArrayOutputStream()
                val tipo = if (opaco) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, salida)
                    "jpeg"
                } else {
                    // Con transparencia, WEBP y no PNG: pesa una fracción y todos los
                    // navegadores lo abren desde hace años. En Android 10 el WEBP a calidad
                    // menor de cien ya es con pérdida; desde el 11 se pide por su nombre.
                    val formato =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                            Bitmap.CompressFormat.WEBP_LOSSY
                        else Bitmap.CompressFormat.WEBP
                    bmp.compress(formato, CALIDAD_WEBP, salida)
                    "webp"
                }
                "data:image/$tipo;base64," +
                    Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()

        /**
         * **Si la imagen tiene de verdad algún píxel transparente.**
         *
         * `Bitmap.hasAlpha()` no lo dice: dice si el mapa de bits *podría* tenerla, y en
         * Android toda captura y toda foto pegada viene en ARGB, así que decía que sí siempre
         * y **todas** las imágenes iban en PNG a calidad cien. Ahí estaba el documento de dos
         * megas con cuatro notas. Se mira el alfa de los píxeles, a saltos: una imagen con
         * transparencia la tiene por zonas, no en un píxel suelto.
         */
        private fun tieneTransparencia(bmp: Bitmap): Boolean {
            if (!bmp.hasAlpha()) return false
            val paso = maxOf(1, maxOf(bmp.width, bmp.height) / 256)
            val fila = IntArray(bmp.width)
            var y = 0
            while (y < bmp.height) {
                bmp.getPixels(fila, 0, bmp.width, 0, y, bmp.width, 1)
                var x = 0
                while (x < bmp.width) {
                    if ((fila[x] ushr 24) < 250) return true
                    x += paso
                }
                y += paso
            }
            return false
        }

        /**
         * Los puntos de una tabla de coordenadas, con su número.
         *
         * `1'`, `2'`, `3'`… por su posición, que es lo que los distingue de los
         * etiquetados a mano. Del tamaño que tienen al exportar y no al de
         * pantalla, igual que en el PDF.
         */
        fun tabla(t: TablaDeCoordenadas, origen: Pt, escalaDelPlano: Escala?): String {
            val color = parseColor(t.color, 255)
            val s = StringBuilder()
            for ((i, p) in puntosEnEscena(t, origen, escalaDelPlano).withIndex()) {
                s.append(
                    "<circle cx=\"${Svg.num(p.x)}\" cy=\"${Svg.num(p.y)}\" " +
                        "r=\"${Svg.num(RADIO_DE_TABLA * 1.55)}\" fill=\"#ffffff\"/>\n" +
                        "<circle cx=\"${Svg.num(p.x)}\" cy=\"${Svg.num(p.y)}\" " +
                        "r=\"${Svg.num(RADIO_DE_TABLA)}\" fill=\"${Svg.hex(color)}\"/>\n"
                )
                val perfil = perfilDe(
                    "${i + 1}'",
                    p.x + RADIO_DE_TABLA * 1.8, p.y - RADIO_DE_TABLA * 1.4,
                    LETRA_DE_TABLA, null, Paint.Align.LEFT, negrita = true
                )
                if (perfil.isNotEmpty()) {
                    s.append(
                        "<path d=\"${Svg.caminoDeContornos(perfil)}\" " +
                            "fill=\"${Svg.hex(color)}\"/>\n"
                    )
                }
            }
            return s.toString()
        }

        // -- el foco ---------------------------------------------------------

        /**
         * Todos los focos en **una sola sombra con todos los huecos**.
         *
         * Los huecos van como polilíneas y no como rectángulos redondeados de
         * SVG porque así se pueden girar punto a punto: un `rect` con `rx` no se
         * puede meter dentro de un camino par/impar, y sin par/impar el agujero
         * no es un agujero.
         */
        /**
         * Los focos, **como los pinta la pantalla** y no como los pintaba el
         * foco antiguo. La sombra es la caja del propio foco —no el dibujo
         * entero—, el hueco es la figura que se marcó ([puntosDelFoco], con su
         * forma: redonda, de caja o a pulso), y cuánto oscurece lo dice el
         * mando del foco ([oscurecimientoDe]). Aquí estaba el fallo de que en
         * lo exportado lo oscuro tapaba toda la zona anotada: este escritor se
         * quedó con el diseño viejo cuando el de pantalla cambió.
         */
        fun focos(focos: List<Element>): String = buildString {
            for (f in focos) {
                val c = getElementAbsoluteCoords(f)
                val d = StringBuilder(
                    Svg.caminoCerrado(
                        listOf(
                            Pt(c.x1, c.y1), Pt(c.x2, c.y1),
                            Pt(c.x2, c.y2), Pt(c.x1, c.y2)
                        )
                    )
                )
                val dentro = puntosDelFoco(f)
                if (dentro.size >= 3) d.append(Svg.caminoCerrado(dentro))

                val negro = oscurecimientoDe(f) / 100.0
                // El giro va en el grupo, como en pantalla se gira el camino
                // entero: así el hueco no se despega del marco al torcerlo.
                val giro = if (f.angle == 0.0) "" else {
                    " transform=\"rotate(${Svg.num(Math.toDegrees(f.angle))} " +
                        "${Svg.num(c.cx)} ${Svg.num(c.cy)})\""
                }
                append("<path d=\"$d\" fill=\"#000000\" fill-rule=\"evenodd\" ")
                append("fill-opacity=\"${Svg.num(negro)}\"$giro/>\n")
            }
        }

        // -- la escala gráfica -----------------------------------------------

        /**
         * El cronograma, **tal como se ve**: rejilla, barras y nombres.
         *
         * Sin nada que perder por el camino: son rectángulos, rayas y texto, y de eso el
         * SVG sabe todo. Se escribe con las mismas cuentas que lo pintan en pantalla —las
         * de [barraDeTarea] y compañía— para que lo exportado y lo dibujado no puedan
         * separarse el día que se toque el reparto de la rejilla.
         */
        private fun cronograma(e: Element, alpha: Int): String {
            val c = getElementAbsoluteCoords(e)
            if (c.x2 - c.x1 <= 1.0 || c.y2 - c.y1 <= 1.0) return ""
            val tinta = parseColor(e.strokeColor, alpha)
            val relleno = if (isTransparent(e.backgroundColor)) tinta
            else parseColor(e.backgroundColor, alpha)
            val grosor = e.strokeWidth.coerceAtLeast(1.0)
            val s = StringBuilder()

            fun raya(x1: Double, y1: Double, x2: Double, y2: Double, suave: Boolean) {
                s.append(
                    "<line x1=\"${Svg.num(x1)}\" y1=\"${Svg.num(y1)}\" " +
                        "x2=\"${Svg.num(x2)}\" y2=\"${Svg.num(y2)}\" " +
                        "stroke=\"${Svg.hex(tinta)}\" stroke-width=\"${Svg.num(grosor)}\"" +
                        (if (suave) " stroke-opacity=\"0.45\"" else "") + "/>\n"
                )
            }

            for (x in columnasDelCronograma(e)) raya(x, c.y1, x, c.y2, true)
            val alto = altoDeFila(e)
            val filas = yDeLasFilas(e)
            for (i in 0..e.tareas.size) {
                val y = filas + i * alto
                raya(c.x1, y, c.x2, y, true)
            }
            s.append(
                "<rect x=\"${Svg.num(c.x1)}\" y=\"${Svg.num(c.y1)}\" " +
                    "width=\"${Svg.num(c.x2 - c.x1)}\" height=\"${Svg.num(c.y2 - c.y1)}\" " +
                    "fill=\"none\" stroke=\"${Svg.hex(tinta)}\" " +
                    "stroke-width=\"${Svg.num(grosor)}\"/>\n"
            )

            val tam = letraDelCronograma(e, alto)
            val anchoCol = anchoDeColumna(e)
            for (k in 0 until kotlin.math.max(1, e.periodos)) {
                val x = xDeLaEscala(e) + (k + 0.5) * anchoCol
                s.append(
                    "<text x=\"${Svg.num(x)}\" y=\"${Svg.num(c.y1 + (filas - c.y1) * 0.72)}\" " +
                        "text-anchor=\"middle\" font-size=\"${Svg.num(tam)}\" " +
                        "fill=\"${Svg.hex(tinta)}\">${Svg.escapar("${k + 1}")}</text>\n"
                )
            }
            for ((i, t) in e.tareas.withIndex()) {
                val b = barraDeTarea(e, i) ?: continue
                val color = t.color?.let { parseColor(it, alpha) } ?: relleno
                s.append(
                    "<rect x=\"${Svg.num(b.x1)}\" y=\"${Svg.num(b.y1)}\" " +
                        "width=\"${Svg.num(b.width)}\" height=\"${Svg.num(b.height)}\" " +
                        "rx=\"${Svg.num(b.height / 3)}\" fill=\"${Svg.hex(color)}\"" +
                        opacidad(color) + "/>\n"
                )
                if (t.nombre.isNotBlank()) {
                    s.append(
                        "<text x=\"${Svg.num(c.x1 + tam * 0.3)}\" " +
                            "y=\"${Svg.num(b.y1 + b.height * 0.5 + tam * 0.36)}\" " +
                            "font-size=\"${Svg.num(tam)}\" fill=\"${Svg.hex(tinta)}\">" +
                            Svg.escapar(t.nombre) + "</text>\n"
                    )
                }
            }
            return s.toString()
        }

        /** Las rayas o los puntos que trae impresa una hoja. Ver [rayasDeLaPauta]. */
        private fun pautaDeLaHoja(e: Element, alpha: Int): String {
            if (e.papel == null) return ""
            val rayas = rayasDeLaPauta(e)
            if (rayas.isEmpty()) return ""
            val tinta = parseColor(e.strokeColor, alpha * OPACIDAD_DE_LA_PAUTA / 100)
            val s = StringBuilder()
            for ((a, b) in rayas) {
                if (a == b) {
                    s.append(
                        "<circle cx=\"${Svg.num(a.x)}\" cy=\"${Svg.num(a.y)}\" r=\"1\" " +
                            "fill=\"${Svg.hex(tinta)}\"/>\n"
                    )
                } else {
                    s.append(
                        "<line x1=\"${Svg.num(a.x)}\" y1=\"${Svg.num(a.y)}\" " +
                            "x2=\"${Svg.num(b.x)}\" y2=\"${Svg.num(b.y)}\" " +
                            "stroke=\"${Svg.hex(tinta)}\" stroke-width=\"1\"/>\n"
                    )
                }
            }
            return s.toString()
        }

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

        /** El plano cartesiano. Ver [Plano]: aquí solo se pasa a SVG. */
        private fun plano(e: Element, alpha: Int): String {
            if (e.width <= 0 || e.height <= 0) return ""
            val tinta = parseColor(e.strokeColor, alpha)
            val grosor = e.strokeWidth.coerceAtLeast(0.5)
            val s = StringBuilder()
            for (trazo in trazosDelInstrumento(e)) {
                // El mismo pelo y la misma transparencia que en pantalla: ver
                // [GROSOR_DE_LA_REJILLA]. Estaban escritos a mano en los tres sitios.
                val ancho = if (trazo.eje) grosor else GROSOR_DE_LA_REJILLA
                val opaco = if (trazo.eje) 1.0 else ALFA_DE_LA_REJILLA
                s.append(
                    "<line x1=\"${Svg.num(e.x + trazo.a.x)}\" y1=\"${Svg.num(e.y + trazo.a.y)}\" " +
                        "x2=\"${Svg.num(e.x + trazo.b.x)}\" y2=\"${Svg.num(e.y + trazo.b.y)}\" " +
                        "stroke=\"${Svg.hex(tinta)}\" stroke-width=\"${Svg.num(ancho)}\" " +
                        "stroke-opacity=\"$opaco\"/>\n"
                )
            }
            val tam = (e.fontSize ?: 11.0).coerceAtLeast(1.0)
            for (n in numerosDelInstrumento(e)) {
                val x = if (n.horizontal) e.x + n.donde.x else e.x + n.donde.x - tam * 0.3
                val y =
                    if (n.horizontal) e.y + n.donde.y + tam * 1.15
                    else e.y + n.donde.y + tam * 0.35
                val alineado = if (n.horizontal) Paint.Align.CENTER else Paint.Align.RIGHT
                s.append(enCurvas(n.texto, x, y, tam, e.fontFamily, alineado, tinta, false))
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
            medidor.reset()
            medidor.isAntiAlias = true
            medidor.textSize = tam.toFloat()
            medidor.typeface = typefaces(familia)
            medidor.isFakeBoldText = negrita
            medidor.textAlign = Paint.Align.LEFT
            val anchos = FloatArray(texto.length)
            medidor.getTextWidths(texto, anchos)
            val total = anchos.sum().toDouble()
            // La alineación se resuelve aquí, letra a letra desde la izquierda.
            var cx = when (alineado) {
                Paint.Align.CENTER -> x - total / 2
                Paint.Align.RIGHT -> x - total
                else -> x
            }
            val s = StringBuilder()
            s.append("<g fill=\"").append(Svg.hex(color)).append('"').append(opacidad(color)).append(">")
            var i = 0
            while (i < texto.length) {
                val c = texto[i]
                if (!c.isWhitespace() && anchos[i] > 0f) {
                    val id = glifo(c, tam, familia, negrita)
                    if (id != null) {
                        s.append("<use href=\"#").append(id).append("\" x=\"").append(Svg.num(cx))
                            .append("\" y=\"").append(Svg.num(y)).append("\"/>")
                    }
                }
                cx += anchos[i]
                i++
            }
            s.append("</g>\n")
            return s.toString()
        }

        /** El id del glifo de esta letra a este tamaño, definiéndolo si es la primera vez. */
        private fun glifo(letra: Char, tam: Double, familia: Int?, negrita: Boolean): String? {
            val clave = "${familia ?: -1}|${Svg.num(tam)}|${if (negrita) 1 else 0}|$letra"
            glifos[clave]?.let { return it }
            val perfil = perfilDe(letra.toString(), 0.0, 0.0, tam, familia, Paint.Align.LEFT, negrita)
            if (perfil.isEmpty()) return null
            val id = "g${glifos.size + 1}"
            glifos[clave] = id
            definiciones.append("<path id=\"").append(id).append("\" d=\"")
                .append(Svg.caminoDeContornos(perfil)).append("\"/>\n")
            return id
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
