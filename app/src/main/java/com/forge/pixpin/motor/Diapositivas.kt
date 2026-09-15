package com.forge.pixpin.motor

import org.w3c.dom.Element as Nodo
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * **Una presentación de PowerPoint leída como diapositivas**: qué hay en cada una y dónde.
 *
 * Lo pidió el usuario el 14-sep-2026: poder traer un PowerPoint al lienzo y presentarlo. Aquí
 * solo se **lee**; lo que pinta está en `guardados/DiapositivasAPdf.kt`, que convierte cada
 * diapositiva en una página de PDF —en vectores: el texto sigue siendo texto— para que entre
 * por el mismo camino que entra un PDF y se presente página a página.
 *
 * ## Qué se lee
 *
 * - **`.pptx`** (PowerPoint 2007 en adelante, Google Slides, Keynote al exportar): un ZIP de XML.
 * - **`.odp`** no, de momento; y **`.ppt`** antiguo es binario: se reconoce para decir que no se
 *   lee y cómo arreglarlo, en vez de enseñar basura.
 *
 * De cada diapositiva: el fondo (color o foto), las fotos (con su recorte), las formas básicas
 * (rectángulo, redondeado, elipse, línea) con relleno y borde, el texto con tamaño, negrita,
 * cursiva, subrayado, color, alineación y viñetas, las tablas, y los grupos con su escala. Lo
 * que traen la plantilla (`slideLayout`) y el patrón (`slideMaster`) —logotipos, franjas,
 * posiciones de título y cuerpo— también. Lo que **no**: gráficos, SmartArt, animaciones,
 * degradados (se toma su primer color), sombras y fuentes incrustadas.
 *
 * Las medidas se quedan en **EMU**, la unidad de Office: 12 700 por punto tipográfico, así que
 * pasarlas a una página de PDF es dividir.
 */
object Diapositivas {

    const val EMU_POR_PUNTO = 12_700.0

    class Presentacion(
        val ancho: Long,
        val alto: Long,
        val diapositivas: List<Diapositiva>
    )

    class Diapositiva(
        val numero: Int,
        /** Color de fondo en ARGB, si lo hay. */
        val fondo: Int?,
        /** Foto de fondo: la entrada del ZIP. */
        val fotoDeFondo: String?,
        /** De abajo arriba, como se pintan. */
        val piezas: List<Pieza>,
        val notas: String = ""
    )

    /** Un rectángulo en EMU, ya en coordenadas de la diapositiva. */
    data class Caja(val x: Double, val y: Double, val ancho: Double, val alto: Double)

    sealed class Pieza {
        abstract val caja: Caja
        /** Giro en grados, en el sentido de las agujas. */
        abstract val giro: Double
    }

    class Foto(
        override val caja: Caja,
        override val giro: Double,
        /** La entrada del ZIP con la imagen. */
        val entrada: String,
        /** Recorte en tanto por uno de cada lado: izquierda, arriba, derecha, abajo. */
        val recorte: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        val volteadaH: Boolean = false,
        val volteadaV: Boolean = false
    ) : Pieza()

    enum class Geometria { RECTANGULO, REDONDEADO, ELIPSE, LINEA, TRIANGULO, ROMBO }

    class Forma(
        override val caja: Caja,
        override val giro: Double,
        val geometria: Geometria,
        val relleno: Int?,
        val borde: Int?,
        /** Grosor del borde en EMU. */
        val grosor: Double,
        val texto: Texto?,
        val volteadaH: Boolean = false,
        val volteadaV: Boolean = false
    ) : Pieza()

    class Tabla(
        override val caja: Caja,
        /** Anchos de columna y altos de fila, en EMU. */
        val columnas: List<Double>,
        val filas: List<Double>,
        /** Por filas; una celda fusionada por otra se queda en `null`. */
        val celdas: List<List<Celda?>>
    ) : Pieza() {
        override val giro: Double get() = 0.0
    }

    class Celda(val texto: Texto, val relleno: Int?, val abarcaColumnas: Int, val abarcaFilas: Int)

    enum class Ancla { ARRIBA, CENTRO, ABAJO }
    enum class Alineacion { IZQUIERDA, CENTRO, DERECHA, JUSTIFICADO }

    class Texto(
        val parrafos: List<Parrafo>,
        val ancla: Ancla,
        /** Márgenes interiores en EMU: izquierda, arriba, derecha, abajo. */
        val margenes: DoubleArray,
        /** `normAutofit fontScale`: PowerPoint encoge el texto para que quepa. */
        val escala: Double = 1.0,
        val ajustarLineas: Boolean = true
    )

    class Parrafo(
        val tramos: List<Tramo>,
        val alineacion: Alineacion,
        /** La viñeta, o `null` sin viñeta. */
        val vineta: String?,
        val nivel: Int,
        /** Sangría a la izquierda en EMU. */
        val sangria: Double = 0.0
    )

    class Tramo(
        val texto: String,
        /** En puntos. */
        val tamano: Double,
        val negrita: Boolean,
        val cursiva: Boolean,
        val subrayado: Boolean,
        val color: Int,
        val fuente: String?
    )

    class NoSeLee(mensaje: String) : Exception(mensaje)

    fun extension(nombre: String) = nombre.substringAfterLast('.', "").lowercase()

    /** Si el archivo es una presentación (o un `.ppt`, que se reconoce para decir que no se lee). */
    fun esPresentacion(nombre: String): Boolean =
        extension(nombre) in setOf("pptx", "ppsx", "potx", "pptm", "ppt", "pps", "odp", "key")

    fun leer(archivo: File, nombre: String = archivo.name): Presentacion {
        when (extension(nombre)) {
            "ppt", "pps" -> throw NoSeLee(
                "Los .ppt antiguos no se pueden leer: ábrelo y guárdalo como .pptx o como PDF"
            )
            "odp" -> throw NoSeLee("Las presentaciones .odp aún no se leen: guárdala como .pptx o PDF")
            "key" -> throw NoSeLee("Keynote no se lee directamente: exporta a PowerPoint o PDF")
        }
        val zip = runCatching { ZipFile(archivo) }.getOrElse {
            throw NoSeLee("El archivo no es una presentación de PowerPoint (.pptx)")
        }
        return zip.use { Lector(it).leer() }
    }

    // ------------------------------------------------------------------------------------------

    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private class Lector(val zip: ZipFile) {

        private val fabrica = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Nada de entidades externas: un archivo que llega por el chat no manda en el disco.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }

        fun xml(ruta: String): Nodo? {
            val e = zip.getEntry(ruta) ?: return null
            val bytes = zip.getInputStream(e).use { it.readBytes() }
            return runCatching {
                fabrica.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            }.getOrNull()
        }

        /** Las relaciones de una parte: id → ruta ya resuelta dentro del ZIP. */
        fun relaciones(parte: String): Map<String, String> {
            val dir = parte.substringBeforeLast('/', "")
            val rels = xml("$dir/_rels/${parte.substringAfterLast('/')}.rels") ?: return emptyMap()
            val m = HashMap<String, String>()
            for (r in rels.hijos()) {
                if (r.localName != "Relationship") continue
                if (r.getAttribute("TargetMode") == "External") continue
                m[r.getAttribute("Id")] = resolver(dir, r.getAttribute("Target"))
            }
            return m
        }

        fun tipoDeRelacion(parte: String, tipo: String): String? {
            val dir = parte.substringBeforeLast('/', "")
            val rels = xml("$dir/_rels/${parte.substringAfterLast('/')}.rels") ?: return null
            return rels.hijos().firstOrNull { it.getAttribute("Type").endsWith("/$tipo") }
                ?.let { resolver(dir, it.getAttribute("Target")) }
        }

        lateinit var tema: Map<String, Int>

        fun leer(): Presentacion {
            val pres = xml("ppt/presentation.xml")
                ?: throw NoSeLee("El archivo no es una presentación de PowerPoint (.pptx)")
            val tam = pres.hijo(NS_P, "sldSz")
            val ancho = tam?.getAttribute("cx")?.toLongOrNull() ?: 12_192_000L
            val alto = tam?.getAttribute("cy")?.toLongOrNull() ?: 6_858_000L
            val relsPres = relaciones("ppt/presentation.xml")
            val orden = pres.hijo(NS_P, "sldIdLst")?.hijos()
                ?.mapNotNull { relsPres[it.getAttributeNS(NS_R, "id")] }
                ?: emptyList()
            val dias = orden.mapIndexedNotNull { i, ruta ->
                runCatching { diapositiva(i + 1, ruta) }.getOrNull()
            }
            if (dias.isEmpty()) throw NoSeLee("La presentación no tiene diapositivas que se puedan leer")
            return Presentacion(ancho, alto, dias)
        }

        private fun diapositiva(numero: Int, ruta: String): Diapositiva? {
            val sld = xml(ruta) ?: return null
            if (sld.getAttribute("show") == "0") return null   // oculta
            val layoutRuta = tipoDeRelacion(ruta, "slideLayout")
            val layout = layoutRuta?.let { xml(it) }
            val masterRuta = layoutRuta?.let { tipoDeRelacion(it, "slideMaster") }
            val master = masterRuta?.let { xml(it) }
            val temaRuta = masterRuta?.let { tipoDeRelacion(it, "theme") }
            tema = colores(temaRuta?.let { xml(it) }, master)

            val estilos = EstilosDelPatron(master)
            val capas = listOfNotNull(
                master?.let { Capa(it, relaciones(masterRuta!!), null) },
                layout?.let { Capa(it, relaciones(layoutRuta), null) },
                Capa(sld, relaciones(ruta), null)
            )

            // El fondo: el de la diapositiva, si no el de la plantilla, si no el del patrón.
            var fondo: Int? = null
            var fotoDeFondo: String? = null
            for (c in capas.asReversed()) {
                val bg = c.raiz.hijo(NS_P, "cSld")?.hijo(NS_P, "bg") ?: continue
                val bgPr = bg.hijo(NS_P, "bgPr")
                if (bgPr != null) {
                    fondo = relleno(bgPr)
                    fotoDeFondo = bgPr.hijo(NS_A, "blipFill")?.hijo(NS_A, "blip")
                        ?.getAttributeNS(NS_R, "embed")?.let { c.rels[it] }
                } else {
                    bg.hijo(NS_P, "bgRef")?.let { fondo = color(it) }
                }
                if (fondo != null || fotoDeFondo != null) break
            }

            val piezas = ArrayList<Pieza>()
            val ocultarPatron = sld.getAttribute("showMasterSp") == "0" ||
                layout?.getAttribute("showMasterSp") == "0"
            // Del patrón y de la plantilla solo lo que no es un hueco a rellenar: los huecos
            // vacíos en PowerPoint no se ven al presentar.
            if (!ocultarPatron && master != null) {
                arbol(capas[0], master, estilos, null, null, piezas, soloDecorado = true)
            }
            if (layout != null && sld.getAttribute("showMasterSp") != "0") {
                arbol(capas[capas.size - 2], layout, estilos, null, master, piezas, soloDecorado = true)
            }
            arbol(capas.last(), sld, estilos, layout, master, piezas, soloDecorado = false)

            val notas = tipoDeRelacion(ruta, "notesSlide")?.let { xml(it) }?.let { n ->
                n.descendientes(NS_A, "t").joinToString("") { it.textContent }
            }.orEmpty()

            return Diapositiva(numero, fondo ?: 0xFFFFFFFF.toInt(), fotoDeFondo, piezas, notas)
        }

        private class Capa(val raiz: Nodo, val rels: Map<String, String>, val nada: Unit?)

        private fun arbol(
            capa: Capa, raiz: Nodo, estilos: EstilosDelPatron,
            layout: Nodo?, master: Nodo?, sale: MutableList<Pieza>, soloDecorado: Boolean
        ) {
            val spTree = raiz.hijo(NS_P, "cSld")?.hijo(NS_P, "spTree") ?: return
            recorrer(capa, spTree, Transformacion.IDENTIDAD, estilos, layout, master, sale, soloDecorado)
        }

        private fun recorrer(
            capa: Capa, grupo: Nodo, t: Transformacion, estilos: EstilosDelPatron,
            layout: Nodo?, master: Nodo?, sale: MutableList<Pieza>, soloDecorado: Boolean
        ) {
            for (n in grupo.hijos()) {
                when (n.localName) {
                    "sp" -> {
                        val ph = n.hijo(NS_P, "nvSpPr")?.hijo(NS_P, "nvPr")?.hijo(NS_P, "ph")
                        if (soloDecorado && ph != null) continue
                        forma(capa, n, ph, t, estilos, layout, master)?.let { sale += it }
                    }
                    "cxnSp" -> if (!soloDecorado || true) forma(capa, n, null, t, estilos, null, null)?.let { sale += it }
                    "pic" -> {
                        val ph = n.hijo(NS_P, "nvPicPr")?.hijo(NS_P, "nvPr")?.hijo(NS_P, "ph")
                        if (soloDecorado && ph != null) continue
                        foto(capa, n, t)?.let { sale += it }
                    }
                    "grpSp" -> {
                        val x = n.hijo(NS_P, "grpSpPr")?.hijo(NS_A, "xfrm")
                        recorrer(capa, n, t.dentro(x), estilos, layout, master, sale, soloDecorado)
                    }
                    "graphicFrame" -> if (!soloDecorado) tabla(n, t)?.let { sale += it }
                    "AlternateContent" -> {
                        // Lo moderno va en `Choice` y lo que entiende cualquiera en `Fallback`.
                        n.hijos().firstOrNull { it.localName == "Fallback" }?.let {
                            recorrer(capa, it, t, estilos, layout, master, sale, soloDecorado)
                        }
                    }
                }
            }
        }

        // ---- Formas y texto ----

        private fun forma(
            capa: Capa, sp: Nodo, ph: Nodo?, t: Transformacion, estilos: EstilosDelPatron,
            layout: Nodo?, master: Nodo?
        ): Pieza? {
            val spPr = sp.hijo(NS_P, "spPr")
            // Un hueco sin posición propia la hereda de la plantilla o del patrón.
            val heredadas = if (ph != null) listOfNotNull(
                layout?.let { huecoIgual(it, ph) }, master?.let { huecoIgual(it, ph) }
            ) else emptyList()
            val xfrm = spPr?.hijo(NS_A, "xfrm")
                ?: heredadas.firstNotNullOfOrNull { it.hijo(NS_P, "spPr")?.hijo(NS_A, "xfrm") }
                ?: return null
            val (caja, giro) = t.aplicar(xfrm) ?: return null
            val volteadaH = xfrm.getAttribute("flipH") == "1"
            val volteadaV = xfrm.getAttribute("flipV") == "1"

            val geom = when (spPr?.hijo(NS_A, "prstGeom")?.getAttribute("prst")) {
                "roundRect", "round2SameRect", "snipRoundRect" -> Geometria.REDONDEADO
                "ellipse", "donut", "pie", "chord" -> Geometria.ELIPSE
                "line", "straightConnector1", "bentConnector3", "curvedConnector3" -> Geometria.LINEA
                "triangle", "rtTriangle" -> Geometria.TRIANGULO
                "diamond" -> Geometria.ROMBO
                else -> if (sp.localName == "cxnSp") Geometria.LINEA else Geometria.RECTANGULO
            }
            val estilo = sp.hijo(NS_P, "style")
            val relleno = spPr?.let { relleno(it) } ?: run {
                if (spPr?.hijo(NS_A, "noFill") != null) null
                else estilo?.hijo(NS_A, "fillRef")?.takeIf { it.getAttribute("idx") != "0" }?.let { color(it) }
            }
            val ln = spPr?.hijo(NS_A, "ln")
            val borde = when {
                ln?.hijo(NS_A, "noFill") != null -> null
                ln != null && relleno(ln) != null -> relleno(ln)
                else -> estilo?.hijo(NS_A, "lnRef")?.takeIf { it.getAttribute("idx") != "0" }?.let { color(it) }
            }
            val grosor = ln?.getAttribute("w")?.toDoubleOrNull() ?: 12_700.0

            val tipoPh = ph?.getAttribute("type").orEmpty()
            val txBody = sp.hijo(NS_P, "txBody")
            val texto = txBody?.let { texto(it, tipoPh, ph != null, estilos, heredadas) }
                ?.takeIf { tx -> tx.parrafos.any { p -> p.tramos.any { it.texto.isNotBlank() } } }
            if (relleno == null && borde == null && texto == null) return null
            return Forma(caja, giro, geom, relleno, borde, grosor, texto, volteadaH, volteadaV)
        }

        /** El hueco de [raiz] que corresponde a [ph]: por índice y, si no, por tipo. */
        private fun huecoIgual(raiz: Nodo, ph: Nodo): Nodo? {
            val idx = ph.getAttribute("idx")
            val tipo = ph.getAttribute("type").ifEmpty { "body" }
            val arbol = raiz.hijo(NS_P, "cSld")?.hijo(NS_P, "spTree") ?: return null
            val huecos = arbol.descendientes(NS_P, "sp").mapNotNull { s ->
                s.hijo(NS_P, "nvSpPr")?.hijo(NS_P, "nvPr")?.hijo(NS_P, "ph")?.let { s to it }
            }
            fun tipoDe(n: Nodo) = n.getAttribute("type").ifEmpty { "body" }
            fun parecido(a: String, b: String) = a == b ||
                (a in TITULOS && b in TITULOS)
            return huecos.firstOrNull { idx.isNotEmpty() && it.second.getAttribute("idx") == idx && parecido(tipoDe(it.second), tipo) }?.first
                ?: huecos.firstOrNull { parecido(tipoDe(it.second), tipo) }?.first
                ?: huecos.firstOrNull { idx.isNotEmpty() && it.second.getAttribute("idx") == idx }?.first
        }

        private fun texto(
            txBody: Nodo, tipoPh: String, esHueco: Boolean, estilos: EstilosDelPatron,
            heredadas: List<Nodo>
        ): Texto {
            // bodyPr: el propio, y lo que no diga, de la plantilla.
            val propios = listOfNotNull(txBody.hijo(NS_A, "bodyPr")) +
                heredadas.mapNotNull { it.hijo(NS_P, "txBody")?.hijo(NS_A, "bodyPr") }
            fun atributo(nombre: String) = propios.firstNotNullOfOrNull { b ->
                b.getAttribute(nombre).takeIf { it.isNotEmpty() }
            }
            val ancla = when (atributo("anchor")) {
                "ctr" -> Ancla.CENTRO
                "b" -> Ancla.ABAJO
                "t" -> Ancla.ARRIBA
                else -> if (tipoPh in TITULOS && tipoPh != "title") Ancla.CENTRO
                else if (tipoPh == "title") Ancla.CENTRO else Ancla.ARRIBA
            }
            val margenes = doubleArrayOf(
                atributo("lIns")?.toDoubleOrNull() ?: 91_440.0,
                atributo("tIns")?.toDoubleOrNull() ?: 45_720.0,
                atributo("rIns")?.toDoubleOrNull() ?: 91_440.0,
                atributo("bIns")?.toDoubleOrNull() ?: 45_720.0
            )
            val escala = propios.firstNotNullOfOrNull { it.hijo(NS_A, "normAutofit") }
                ?.getAttribute("fontScale")?.toDoubleOrNull()?.div(100_000.0) ?: 1.0
            val ajustar = atributo("wrap") != "none"

            // Las listas de estilo, de la más cercana a la más lejana.
            val listas = listOfNotNull(txBody.hijo(NS_A, "lstStyle")) +
                heredadas.mapNotNull { it.hijo(NS_P, "txBody")?.hijo(NS_A, "lstStyle") } +
                listOfNotNull(
                    when {
                        !esHueco -> estilos.otro
                        tipoPh in TITULOS -> estilos.titulo
                        tipoPh in setOf("sldNum", "dt", "ftr") -> estilos.otro
                        else -> estilos.cuerpo
                    }
                )
            val esCuerpo = esHueco && tipoPh !in TITULOS && tipoPh !in setOf("sldNum", "dt", "ftr")

            val parrafos = txBody.hijos().filter { it.localName == "p" }.map { p ->
                val pPr = p.hijo(NS_A, "pPr")
                val nivel = pPr?.getAttribute("lvl")?.toIntOrNull() ?: 0
                val deNivel = listas.mapNotNull { it.hijo(NS_A, "lvl${nivel + 1}pPr") }
                val cadena = listOfNotNull(pPr) + deNivel
                fun pAttr(n: String) = cadena.firstNotNullOfOrNull { e -> e.getAttribute(n).takeIf { it.isNotEmpty() } }
                val alineacion = when (pAttr("algn")) {
                    "ctr" -> Alineacion.CENTRO
                    "r" -> Alineacion.DERECHA
                    "just", "dist" -> Alineacion.JUSTIFICADO
                    else -> Alineacion.IZQUIERDA
                }
                val vineta = run {
                    for (e in cadena) {
                        if (e.hijo(NS_A, "buNone") != null) return@run null
                        e.hijo(NS_A, "buChar")?.let { return@run it.getAttribute("char").ifEmpty { "•" } }
                        if (e.hijo(NS_A, "buAutoNum") != null) return@run "•"
                    }
                    if (esCuerpo) "•" else null
                }
                val sangria = pAttr("marL")?.toDoubleOrNull() ?: (nivel * 342_900.0)
                val defectos = cadena.mapNotNull { it.hijo(NS_A, "defRPr") }

                val tramos = ArrayList<Tramo>()
                for (r in p.hijos()) {
                    val t = when (r.localName) {
                        "r", "fld" -> r.hijo(NS_A, "t")?.textContent ?: ""
                        "br" -> "\n"
                        else -> continue
                    }
                    val rPr = r.hijo(NS_A, "rPr")
                    tramos += tramo(t, listOfNotNull(rPr) + defectos, tipoPh)
                }
                if (tramos.isEmpty()) {
                    // Un párrafo vacío también ocupa su línea, con su tamaño.
                    tramos += tramo("", listOfNotNull(p.hijo(NS_A, "endParaRPr")) + defectos, tipoPh)
                }
                Parrafo(tramos, alineacion, vineta.takeIf { tramos.any { it.texto.isNotBlank() } }, nivel, sangria)
            }
            return Texto(parrafos, ancla, margenes, escala, ajustar)
        }

        private fun tramo(t: String, cadena: List<Nodo>, tipoPh: String): Tramo {
            fun a(n: String) = cadena.firstNotNullOfOrNull { e -> e.getAttribute(n).takeIf { it.isNotEmpty() } }
            val tamano = a("sz")?.toDoubleOrNull()?.div(100.0)
                ?: if (tipoPh in TITULOS) 44.0 else if (tipoPh.isNotEmpty() || tipoPh == "body") 24.0 else 18.0
            val color = cadena.firstNotNullOfOrNull { relleno(it) } ?: tema["tx1"] ?: 0xFF000000.toInt()
            val fuente = cadena.firstNotNullOfOrNull { it.hijo(NS_A, "latin")?.getAttribute("typeface") }
                ?.takeIf { it.isNotEmpty() && !it.startsWith("+") }
            return Tramo(
                texto = t,
                tamano = tamano,
                negrita = a("b") == "1",
                cursiva = a("i") == "1",
                subrayado = a("u").let { it != null && it != "none" },
                color = color,
                fuente = fuente
            )
        }

        // ---- Fotos y tablas ----

        private fun foto(capa: Capa, pic: Nodo, t: Transformacion): Foto? {
            val blipFill = pic.hijo(NS_P, "blipFill") ?: return null
            val id = blipFill.hijo(NS_A, "blip")?.getAttributeNS(NS_R, "embed") ?: return null
            val entrada = capa.rels[id] ?: return null
            val xfrm = pic.hijo(NS_P, "spPr")?.hijo(NS_A, "xfrm") ?: return null
            val (caja, giro) = t.aplicar(xfrm) ?: return null
            val src = blipFill.hijo(NS_A, "srcRect")
            fun lado(n: String) = (src?.getAttribute(n)?.toFloatOrNull() ?: 0f) / 100_000f
            return Foto(
                caja, giro, entrada,
                floatArrayOf(lado("l"), lado("t"), lado("r"), lado("b")),
                xfrm.getAttribute("flipH") == "1", xfrm.getAttribute("flipV") == "1"
            )
        }

        private fun tabla(frame: Nodo, t: Transformacion): Tabla? {
            val tbl = frame.hijo(NS_A, "graphic")?.hijo(NS_A, "graphicData")?.hijo(NS_A, "tbl") ?: return null
            val xfrm = frame.hijo(NS_P, "xfrm") ?: return null
            val (caja, _) = t.aplicar(xfrm) ?: return null
            val columnas = tbl.hijo(NS_A, "tblGrid")?.hijos()
                ?.mapNotNull { it.getAttribute("w").toDoubleOrNull() } ?: return null
            val estilos = EstilosDelPatron(null)
            val filasXml = tbl.hijos().filter { it.localName == "tr" }
            val filas = filasXml.map { it.getAttribute("h").toDoubleOrNull() ?: 370_840.0 }
            val celdas = filasXml.map { tr ->
                tr.hijos().filter { it.localName == "tc" }.map { tc ->
                    if (tc.getAttribute("hMerge") == "1" || tc.getAttribute("vMerge") == "1") null
                    else {
                        val tx = tc.hijo(NS_A, "txBody")?.let { texto(it, "", false, estilos, emptyList()) }
                            ?: Texto(emptyList(), Ancla.ARRIBA, doubleArrayOf(91_440.0, 45_720.0, 91_440.0, 45_720.0))
                        Celda(
                            tx,
                            tc.hijo(NS_A, "tcPr")?.let { relleno(it) },
                            tc.getAttribute("gridSpan").toIntOrNull() ?: 1,
                            tc.getAttribute("rowSpan").toIntOrNull() ?: 1
                        )
                    }
                }
            }
            val escalaX = caja.ancho / columnas.sum().coerceAtLeast(1.0)
            val escalaY = (caja.alto / filas.sum().coerceAtLeast(1.0)).coerceAtLeast(1.0)
            return Tabla(caja, columnas.map { it * escalaX }, filas.map { it * escalaY }, celdas)
        }

        // ---- Colores ----

        /** El relleno sólido (o el primer color de un degradado) que cuelga de [e]. */
        fun relleno(e: Nodo): Int? {
            e.hijo(NS_A, "solidFill")?.let { return color(it) }
            e.hijo(NS_A, "gradFill")?.hijo(NS_A, "gsLst")?.hijos()?.firstOrNull()?.let { return color(it) }
            return null
        }

        fun color(e: Nodo): Int? {
            for (c in e.hijos()) {
                val base = when (c.localName) {
                    "srgbClr" -> c.getAttribute("val").toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
                    "schemeClr" -> tema[c.getAttribute("val")]
                    "sysClr" -> (c.getAttribute("lastClr").ifEmpty { if (c.getAttribute("val") == "window") "FFFFFF" else "000000" })
                        .toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
                    "prstClr" -> PRESET[c.getAttribute("val")]
                    "scrgbClr" -> {
                        fun v(n: String) = ((c.getAttribute(n).toDoubleOrNull() ?: 0.0) / 100_000.0 * 255).toInt().coerceIn(0, 255)
                        (0xFF shl 24) or (v("r") shl 16) or (v("g") shl 8) or v("b")
                    }
                    else -> null
                } ?: continue
                return modificar(base, c)
            }
            return null
        }

        /** `lumMod`, `lumOff`, `tint`, `shade` y `alpha`: lo que un tema hace con sus colores. */
        private fun modificar(argb: Int, c: Nodo): Int {
            var r = (argb shr 16 and 0xFF) / 255.0
            var g = (argb shr 8 and 0xFF) / 255.0
            var b = (argb and 0xFF) / 255.0
            var alfa = 1.0
            for (m in c.hijos()) {
                val v = (m.getAttribute("val").toDoubleOrNull() ?: continue) / 100_000.0
                when (m.localName) {
                    "lumMod", "lumOff" -> {
                        val hsl = aHsl(r, g, b)
                        hsl[2] = if (m.localName == "lumMod") hsl[2] * v else hsl[2] + v
                        hsl[2] = hsl[2].coerceIn(0.0, 1.0)
                        val rgb = deHsl(hsl[0], hsl[1], hsl[2]); r = rgb[0]; g = rgb[1]; b = rgb[2]
                    }
                    "tint" -> { r += (1 - r) * (1 - v); g += (1 - g) * (1 - v); b += (1 - b) * (1 - v) }
                    "shade" -> { r *= v; g *= v; b *= v }
                    "alpha" -> alfa = v
                }
            }
            fun c255(x: Double) = (x * 255).toInt().coerceIn(0, 255)
            return (c255(alfa) shl 24) or (c255(r) shl 16) or (c255(g) shl 8) or c255(b)
        }

        private fun colores(tema: Nodo?, master: Nodo?): Map<String, Int> {
            val m = HashMap<String, Int>()
            val esquema = tema?.descendientes(NS_A, "clrScheme")?.firstOrNull()
            if (esquema != null) {
                this.tema = emptyMap()
                for (c in esquema.hijos()) color(c)?.let { m[c.localName] = it }
            }
            // El patrón dice qué es texto y qué fondo: normalmente tx1 = dk1 y bg1 = lt1.
            val mapa = master?.hijo(NS_P, "clrMap")
            for ((alias, defecto) in listOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")) {
                val destino = mapa?.getAttribute(alias)?.takeIf { it.isNotEmpty() } ?: defecto
                m[destino]?.let { m[alias] = it }
            }
            m.putIfAbsent("tx1", 0xFF000000.toInt())
            m.putIfAbsent("bg1", 0xFFFFFFFF.toInt())
            m["phClr"] = m["accent1"] ?: 0xFF4472C4.toInt()
            return m
        }
    }

    /** Los estilos de texto del patrón: título, cuerpo y lo demás. */
    private class EstilosDelPatron(master: Nodo?) {
        private val tx = master?.hijo(NS_P, "txStyles")
        val titulo: Nodo? = tx?.hijo(NS_P, "titleStyle")
        val cuerpo: Nodo? = tx?.hijo(NS_P, "bodyStyle")
        val otro: Nodo? = tx?.hijo(NS_P, "otherStyle")
    }

    /**
     * La colocación de los grupos: un grupo dice dónde está (`off`, `ext`) y en qué medidas
     * vienen sus hijos (`chOff`, `chExt`), así que meterse en un grupo es escalar y mover.
     */
    internal class Transformacion(val dx: Double, val dy: Double, val sx: Double, val sy: Double) {
        fun dentro(xfrm: Nodo?): Transformacion {
            xfrm ?: return this
            val off = xfrm.hijo(NS_A, "off"); val ext = xfrm.hijo(NS_A, "ext")
            val chOff = xfrm.hijo(NS_A, "chOff"); val chExt = xfrm.hijo(NS_A, "chExt")
            fun d(e: Nodo?, a: String) = e?.getAttribute(a)?.toDoubleOrNull() ?: 0.0
            val ex = d(ext, "cx"); val ey = d(ext, "cy")
            val cx = d(chExt, "cx").takeIf { it > 0 } ?: ex
            val cy = d(chExt, "cy").takeIf { it > 0 } ?: ey
            val gsx = if (cx > 0) ex / cx else 1.0
            val gsy = if (cy > 0) ey / cy else 1.0
            // Hijo en x → grupo: off + (x − chOff)·gs; y el grupo al padre con esta.
            return Transformacion(
                dx + sx * (d(off, "x") - d(chOff, "x") * gsx),
                dy + sy * (d(off, "y") - d(chOff, "y") * gsy),
                sx * gsx, sy * gsy
            )
        }

        fun aplicar(xfrm: Nodo): Pair<Caja, Double>? {
            val off = xfrm.hijo(NS_A, "off") ?: return null
            val ext = xfrm.hijo(NS_A, "ext") ?: return null
            val x = off.getAttribute("x").toDoubleOrNull() ?: return null
            val y = off.getAttribute("y").toDoubleOrNull() ?: return null
            val w = ext.getAttribute("cx").toDoubleOrNull() ?: return null
            val h = ext.getAttribute("cy").toDoubleOrNull() ?: return null
            val giro = (xfrm.getAttribute("rot").toDoubleOrNull() ?: 0.0) / 60_000.0
            return Caja(dx + x * sx, dy + y * sy, w * sx, h * sy) to giro
        }

        companion object { val IDENTIDAD = Transformacion(0.0, 0.0, 1.0, 1.0) }
    }

    private val TITULOS = setOf("title", "ctrTitle", "subTitle")

    private val PRESET = mapOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        "gray" to 0xFF808080.toInt(), "orange" to 0xFFFFA500.toInt()
    )

    internal fun resolver(dir: String, destino: String): String {
        if (destino.startsWith("/")) return destino.removePrefix("/")
        val partes = ArrayList(dir.split('/').filter { it.isNotEmpty() })
        for (p in destino.split('/')) {
            when (p) {
                "..", -> if (partes.isNotEmpty()) partes.removeAt(partes.size - 1)
                ".", "" -> {}
                else -> partes += p
            }
        }
        return partes.joinToString("/")
    }

    private fun aHsl(r: Double, g: Double, b: Double): DoubleArray {
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2
        if (max == min) return doubleArrayOf(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
        val h = when (max) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6
        return doubleArrayOf(h, s, l)
    }

    private fun deHsl(h: Double, s: Double, l: Double): DoubleArray {
        if (s == 0.0) return doubleArrayOf(l, l, l)
        fun tono(p: Double, q: Double, t0: Double): Double {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return doubleArrayOf(tono(p, q, h + 1.0 / 3), tono(p, q, h), tono(p, q, h - 1.0 / 3))
    }

    private fun Nodo.hijos(): List<Nodo> {
        val l = ArrayList<Nodo>()
        var n = firstChild
        while (n != null) { if (n is Nodo) l += n; n = n.nextSibling }
        return l
    }

    private fun Nodo.hijo(ns: String, nombre: String): Nodo? {
        var n = firstChild
        while (n != null) {
            if (n is Nodo && n.localName == nombre && n.namespaceURI == ns) return n
            n = n.nextSibling
        }
        return null
    }

    private fun Nodo.descendientes(ns: String, nombre: String): List<Nodo> {
        val lista = getElementsByTagNameNS(ns, nombre)
        return (0 until lista.length).map { lista.item(it) as Nodo }
    }
}
