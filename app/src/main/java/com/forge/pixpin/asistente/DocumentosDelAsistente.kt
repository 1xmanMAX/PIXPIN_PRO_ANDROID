package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.FillStyle
import com.forge.pixpin.motor.PdfAHtml
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.PlanoDePdf
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.leerPdf
import com.forge.pixpin.motor.randomId
import com.forge.pixpin.motor.randomSeed
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **Los documentos del chat como herramientas del asistente** (PC `asistente/documentos_acciones.rs`,
 * 14-oct-2026): anotar un PDF, un Word, un PowerPoint o un plano (resaltar, subrayar o tachar un texto,
 * encerrarlo o marcarlo, una nota, un trazo) **en las mismas capas que el lector y el visor de planos**
 * —`anot-<uid>-p<i>` con su `.hoja`, o la capa `anot-<uid>` del plano—, para que se vea al abrir y viaje
 * al PC; y buscar un texto dentro y abrir el visor ahí.
 *
 * **El modelo decide poco a propósito**: qué y dónde por el TEXTO del documento, no por coordenadas
 * que no ve. Un PDF escaneado pasa por el OCR del teléfono (ML Kit; como mucho [MAX_OCR] hojas).
 */
class DocumentosDelAsistente(private val contexto: Context, private val almacen: MensajesStore, private val chat: String?) {

    enum class Sirve { DOCUMENTO, ESCANEADO, CUALQUIERA }

    /** Un archivo de los chats: su mensaje, su nombre y el de su chat. */
    class Candidato(val mensaje: Mensaje, val nombre: String, val ruta: File, val proyecto: String?, val proyectoNombre: String, val cuando: Long)

    private fun extension(n: String) = n.substringAfterLast('.', "").lowercase()
    private fun esPlano(n: String) = com.forge.pixpin.planos.LectorDePlanos.esPlano(n)
    private fun esOfficeDePaginas(n: String) = extension(n) in setOf("docx", "docm", "dotx", "dotm", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm")
    private fun esImagen(n: String) = extension(n) in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic")

    private fun sirve(n: String, s: Sirve) = when (s) {
        Sirve.ESCANEADO -> extension(n) == "pdf" || esImagen(n)
        Sirve.DOCUMENTO -> extension(n) == "pdf" || esOfficeDePaginas(n) || esPlano(n)
        Sirve.CUALQUIERA -> extension(n).isNotEmpty()
    }

    private fun nombreDelChat(p: String?) = p?.let { (contexto.applicationContext as? com.forge.pixpin.PixPinApp)?.proyectos?.porId(it)?.nombre }
        ?: com.forge.pixpin.mini.TodasLasTareas.GUARDADOS

    /** **Los archivos de todos los chats**, sin el del asistente (PC `candidatos`). */
    fun candidatos(): List<Candidato> {
        val delAsistente = runCatching { Asistente.asegurarChat(contexto).id }.getOrNull()
        return almacen.leer().filter { m -> !m.enBuzon && m.ruta != null && File(m.ruta).isFile && m.proyecto != delAsistente &&
            m.clase in setOf(Clase.ARCHIVO, Clase.IMAGEN, Clase.PAGINA) }
            .map { m -> Candidato(m, m.nombre.trim().ifBlank { File(m.ruta!!).name }, File(m.ruta!!), m.proyecto, nombreDelChat(m.proyecto), m.cuando) }
    }

    private val VACIAS = setOf("de", "del", "la", "el", "los", "las", "en", "y", "mi", "mis", "que", "con", "por", "para", "un", "una", "abre",
        "abrir", "abreme", "archivo", "fichero", "documento", "muestrame", "ensename", "ese", "esa", "este", "esta", "al", "a", "lo")

    private fun palabras(s: String) = Acciones.normalizar(s).split(Regex("[^\\p{L}\\p{N}]")).filter { it.isNotEmpty() }

    private fun parecido(q: String, n: String) = when {
        q == n -> 2.0
        min(q.length, n.length) >= 4 && (q.startsWith(n) || n.startsWith(q)) -> 1.5
        else -> 0.0
    }

    /** **Cuánto encaja un archivo con la descripción** (PC `puntuar`). */
    fun puntuar(c: Candidato, descripcion: String): Double {
        val nombre = palabras(c.nombre); val del = palabras(c.proyectoNombre); val ext = extension(c.nombre)
        var s = 0.0
        for (q in palabras(descripcion)) {
            val tipo = when (q) {
                "plano", "planos", "dwg", "dxf", "cad" -> esPlano(c.nombre)
                "pdf" -> ext == "pdf"
                "excel", "xlsx", "libro" -> ext in setOf("xlsx", "xls", "xlsm", "csv")
                "word", "docx" -> ext in setOf("docx", "doc")
                "foto", "imagen", "captura" -> esImagen(c.nombre)
                "presentacion", "diapositivas", "pptx" -> ext in setOf("pptx", "ppt")
                else -> null
            }
            if (tipo != null) s += if (tipo) 1.0 else -0.5
            if (q in VACIAS) continue
            s += max(nombre.maxOfOrNull { parecido(q, it) } ?: 0.0, (del.maxOfOrNull { parecido(q, it) } ?: 0.0) * 0.5)
        }
        return s
    }

    /** **Elige el archivo** (PC `elegir`): el nombrado o descrito; sin nada, el adjunto más reciente que sirva del chat. */
    fun elegir(archivo: String?, s: Sirve): Candidato {
        val todos = candidatos().filter { sirve(it.nombre, s) }
        val descripcion = archivo?.trim()?.takeIf { it.isNotEmpty() }
            ?: return todos.filter { it.proyecto == chat }.maxByOrNull { it.cuando } ?: throw IllegalStateException(noSirve(s))
        val comoRuta = File(descripcion.trim('"', '\'', '«', '»'))
        if (comoRuta.isAbsolute && comoRuta.isFile) todos.firstOrNull { it.ruta == comoRuta }?.let { return it }
        val puntuados = todos.map { c -> (puntuar(c, descripcion) + if (c.proyecto == chat) 0.25 else 0.0) to c }
            .filter { it.first >= 1.5 }
            .sortedWith(compareByDescending<Pair<Double, Candidato>> { it.first }.thenByDescending { it.second.cuando })
            .distinctBy { it.second.ruta }
        val mejor = puntuados.firstOrNull()?.first
            ?: throw IllegalStateException("no hay ningun archivo en los chats que se llame o trate de «$descripcion»; pregunta al usuario el nombre")
        val parecidos = puntuados.filter { it.first >= mejor - 0.3 }.map { it.second }
        if (parecidos.size > 1) {
            val lista = parecidos.take(5).mapIndexed { i, c -> "${i + 1}) ${c.nombre} (chat ${c.proyectoNombre})" }.joinToString("; ")
            throw IllegalStateException("no abras nada todavia; contesta al usuario EXACTAMENTE esto: «Hay ${parecidos.size} que encajan: $lista. ¿Cual?» y, cuando elija, vuelve a llamar con su nombre exacto")
        }
        return puntuados[0].second
    }

    private fun noSirve(s: Sirve): String {
        val que = when (s) { Sirve.ESCANEADO -> "una foto o un PDF"; Sirve.DOCUMENTO -> "un PDF, Word, PowerPoint o plano"; Sirve.CUALQUIERA -> "ningun archivo" }
        val hay = candidatos().filter { it.proyecto == chat }.sortedByDescending { it.cuando }
        if (hay.isEmpty()) return "en este chat no hay $que; pregunta al usuario cual (por su nombre)"
        val consejo = when (s) {
            Sirve.DOCUMENTO -> "; para marcar o explicar algo en un Excel o una foto usa anotar_inteligente, y para leer un Excel leer_excel"
            Sirve.ESCANEADO -> "; un Excel ya es una tabla (leer_excel), y un Word o PowerPoint se lee con leer_documento"
            else -> ""
        }
        return "esta herramienta necesita $que y en este chat solo hay ${hay.take(6).joinToString(", ") { "«${it.nombre}»" }}$consejo"
    }

    // ---------------------------------------------------------------- el texto de las páginas

    /** El texto de una hoja con la caja de cada letra (fracciones de la hoja; null en los huecos). */
    class PaginaDeTexto(val texto: String, val cajas: List<FloatArray?>)

    /** Las páginas: su texto y el alto de cada hoja en unidades del lector ([ANCHO_HOJA] de ancho). */
    class Paginas(val textos: List<PaginaDeTexto>, val altos: List<Double>)

    companion object {
        /** El ancho de la hoja del lector (el del PC, `ANCHO_HOJA`): las capas se escriben en estas unidades. */
        val ANCHO_HOJA = PdfDoc.PAGE_WIDTH.toDouble()
        const val MAX_OCR = 12
        const val ANCHO_OCR = 1700
        /** El grupo de lo que pone el asistente (para encontrarlo y borrarlo junto), el del PC. */
        const val GRUPO = "asistente-anotado"
    }

    /** Un texto con cajas por trozo (PDF, Office): cada letra con su parte del ancho del trozo. */
    private fun pagina(trozos: List<Pair<String, FloatArray>>, w: Double, h: Double): PaginaDeTexto {
        val sb = StringBuilder(); val cajas = ArrayList<FloatArray?>()
        // Por renglones (de arriba abajo y de izquierda a derecha): así una búsqueda de dos palabras casa.
        val ordenados = trozos.sortedWith(compareBy({ Math.round(it.second[1] / max(it.second[3].toDouble(), 1.0)) }, { it.second[0] }))
        var yRenglon = Float.NaN
        for ((t, c) in ordenados) {
            if (!yRenglon.isNaN() && kotlin.math.abs(c[1] - yRenglon) > c[3] * 0.5f) { sb.append('\n'); cajas += null }
            else if (sb.isNotEmpty()) { sb.append(' '); cajas += null }
            yRenglon = c[1]
            val n = max(t.length, 1)
            val paso = c[2] / n
            for ((k, ch) in t.withIndex()) {
                val x0 = c[0] + paso * k
                sb.append(ch)
                cajas += floatArrayOf((x0 / w).toFloat(), (c[1] / h).toFloat(), ((x0 + paso) / w).toFloat(), ((c[1] + c[3]) / h).toFloat())
            }
        }
        return PaginaDeTexto(sb.toString(), cajas)
    }

    /** **Las páginas de un PDF, Word o PowerPoint** (PC `paginas`): la capa de texto, o el OCR si es escaneado. */
    fun paginas(ruta: File, nombre: String): Paginas {
        if (esOfficeDePaginas(nombre)) {
            val d = com.forge.pixpin.office.HojasOffice.de(contexto, ruta.path) ?: throw IllegalStateException("no se pudo abrir «$nombre»")
            d.maquetarTodo { }
            val textos = ArrayList<PaginaDeTexto>(); val altos = ArrayList<Double>()
            for (i in 0 until d.cuantas()) {
                val p = d.pagina(i) ?: continue
                altos += ANCHO_HOJA * p.alto / max(p.ancho, 1f)
                textos += pagina(p.textos().map { (t, r) -> t.texto to r }, p.ancho.toDouble(), p.alto.toDouble())
            }
            if (textos.isEmpty()) throw IllegalStateException("el documento no tiene paginas")
            return Paginas(textos, altos)
        }
        val archivo = leerPdf(ruta.readBytes()) ?: throw IllegalStateException("no se pudo abrir el PDF")
        if (archivo.cifrado) throw IllegalStateException("el PDF está protegido con contraseña")
        val n = archivo.paginas().size
        val textos = ArrayList<PaginaDeTexto>(); val altos = ArrayList<Double>()
        var leidas = 0
        for (i in 0 until n) {
            val plano = PlanoDePdf.de(archivo, i)
            val w = plano?.ancho ?: PdfDoc.medidaEnPuntos(ruta.path, i)?.first ?: 595.0
            val h = plano?.alto ?: PdfDoc.medidaEnPuntos(ruta.path, i)?.second ?: 842.0
            altos += ANCHO_HOJA * h / max(w, 1.0)
            val trozos = PdfAHtml.trozosDe(plano?.textos.orEmpty()).map { it.texto to floatArrayOf(it.x.toFloat(), (it.y - it.alto * 0.8).toFloat(), (it.fin - it.x).toFloat(), it.alto.toFloat()) }
            var p = pagina(trozos, w, h)
            if (p.texto.trim().length < 20 && leidas < MAX_OCR) {
                leidas++
                PdfDoc.render(ruta.path, i, ANCHO_OCR)?.let { bm -> p = paginaDeOcr(bm).also { bm.recycle() } }
            }
            textos += p
        }
        return Paginas(textos, altos)
    }

    /** El texto de una imagen por el OCR: cada letra con la caja de su palabra repartida a partes iguales. */
    private fun paginaDeOcr(bm: android.graphics.Bitmap): PaginaDeTexto {
        val palabras = LectorDeLetras.leer(bm)
        val w = max(bm.width, 1).toDouble(); val h = max(bm.height, 1).toDouble()
        return pagina(palabras.map { it.texto to floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.w.toFloat(), it.h.toFloat()) }, w, h)
    }

    /** Un sitio encontrado: la hoja (desde 0), las cajas de sus renglones (unidades del lector) y su contexto. */
    class Sitio(val pagina: Int, val cajas: List<DoubleArray>, val contexto: String)

    /** **Busca [consulta] en las páginas** (sin mayúsculas ni tildes, como Ctrl+F). [pagina]: solo esa (desde 0). */
    fun buscarEnPaginas(p: Paginas, consulta: String, pagina: Int?, maximo: Int): List<Sitio> {
        val q = Acciones.normalizar(consulta).replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) return emptyList()
        val v = ArrayList<Sitio>()
        for ((k, t) in p.textos.withIndex()) {
            if (pagina != null && pagina != k) continue
            // Lo normal y lo de verdad van letra a letra (normalizar no cambia el largo de una letra).
            val normal = buildString { for (c in t.texto) append(Acciones.normalizar(c.toString()).firstOrNull() ?: c) }.replace('\n', ' ')
            var desde = 0
            while (v.size < maximo) {
                val i = normal.indexOf(q, desde); if (i < 0) break
                desde = i + q.length
                val alto = p.altos.getOrNull(k) ?: (ANCHO_HOJA * 1.414)
                val cajas = cajasDe(t, i, q.length).map { c -> doubleArrayOf(c[0] * ANCHO_HOJA, c[1] * alto, c[2] * ANCHO_HOJA, c[3] * alto) }
                if (cajas.isEmpty()) continue
                val contexto = t.texto.substring(max(i - 40, 0), min(i + q.length + 40, t.texto.length)).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                v += Sitio(k, cajas, contexto)
            }
            if (v.size >= maximo) break
        }
        return v
    }

    /** Las cajas de los renglones de las letras [inicio] … [inicio]+[largo] (fracciones). */
    private fun cajasDe(t: PaginaDeTexto, inicio: Int, largo: Int): List<DoubleArray> {
        val salida = ArrayList<DoubleArray>()
        var actual: DoubleArray? = null
        for (k in inicio until min(inicio + largo, t.cajas.size)) {
            val c = t.cajas[k] ?: continue
            val a = actual
            if (a != null && kotlin.math.abs(c[1] - a[1]) < (a[3] - a[1]) * 0.5) { a[2] = max(a[2], c[2].toDouble()); a[3] = max(a[3], c[3].toDouble()) }
            else { actual = doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble()); salida += actual }
        }
        return salida
    }

    // ------------------------------------------------------------------------- las figuras

    enum class ComoAnotar(val palabra: String, val color: String) {
        RESALTAR("resaltar", "#ffd43b"), SUBRAYAR("subrayar", "#e03131"), TACHAR("tachar", "#e03131"), NOTA("nota", "#343a40"),
        ENCERRAR("encerrar", "#e03131"), MARCO("marco", "#3d8bff"), DIBUJAR("dibujar", "#e03131");
        companion object { fun de(s: String) = entries.firstOrNull { it.palabra == s } }
    }

    private fun el(tipo: ElementType, x: Double, y: Double, w: Double, h: Double, color: String) = Element(
        id = randomId(), type = tipo, x = x, y = y, width = w, height = h, seed = randomSeed(), strokeColor = color, roughness = 0,
        groupIds = listOf(GRUPO), updated = System.currentTimeMillis()
    )

    /**
     * **Las figuras de una anotación** sobre [cajas] (`[x0, y0, x1, y1]` en unidades de la capa, y hacia
     * abajo), como el PC (`figuras` y `pines::marcas_texto`): [letra], el alto de letra de referencia.
     */
    fun figuras(como: ComoAnotar, cajas: List<DoubleArray>, color: String, nota: String?, puntos: List<Pair<Double, Double>>, letra: Double): List<Element> {
        val grosor = max(letra * 0.12, 1.0)
        fun union(): DoubleArray = doubleArrayOf(cajas.minOf { it[0] }, cajas.minOf { it[1] }, cajas.maxOf { it[2] }, cajas.maxOf { it[3] })
        return when (como) {
            ComoAnotar.RESALTAR, ComoAnotar.SUBRAYAR, ComoAnotar.TACHAR -> cajas.mapNotNull { b ->
                val x = b[0].roundToInt().toDouble(); val y = b[1].roundToInt().toDouble()
                val w = max((b[2] - b[0]).roundToInt(), 1).toDouble(); val h = max((b[3] - b[1]).roundToInt(), 1).toDouble()
                val g = max(h * 0.09, 1.5)
                if (como == ComoAnotar.RESALTAR) {
                    val aire = h * 0.12
                    el(ElementType.RECTANGLE, x - aire, y - aire, w + 2 * aire, h + 2 * aire, color + "00")
                        .copy(backgroundColor = color, fillStyle = FillStyle.SOLID, strokeWidth = 1.0, opacity = 40)
                } else {
                    val altura = if (como == ComoAnotar.TACHAR) y + h * 0.55 else y + h + g
                    el(ElementType.LINE, x, altura, w, 0.0, color).copy(strokeWidth = g, points = listOf(Pt(0.0, 0.0), Pt(w, 0.0)))
                }
            }
            ComoAnotar.ENCERRAR, ComoAnotar.MARCO -> {
                if (cajas.isEmpty()) return emptyList()
                val u = union(); val aire = letra * 0.6
                listOf(el(if (como == ComoAnotar.ENCERRAR) ElementType.ELLIPSE else ElementType.RECTANGLE,
                    u[0] - aire * 1.5, u[1] - aire, (u[2] - u[0]) + aire * 3, (u[3] - u[1]) + aire * 2, color).copy(strokeWidth = grosor * 1.5))
            }
            ComoAnotar.NOTA -> {
                val texto = nota?.trim().orEmpty()
                if (texto.isEmpty()) return emptyList()
                val u = if (cajas.isEmpty()) doubleArrayOf(0.0, 0.0, 0.0, letra) else union()
                val tam = max(letra, 1.0)
                val lineas = partir(texto, 28)
                val ancho = (lineas.maxOfOrNull { it.length } ?: 1) * tam * 0.55 + tam
                val alto = lineas.size * tam * 1.3 + tam * 0.6
                val x = u[2] + tam * 0.8; val y = u[1] - tam * 0.3
                listOf(
                    el(ElementType.RECTANGLE, x, y, ancho, alto, "#e8c54700").copy(backgroundColor = "#fff3a3", fillStyle = FillStyle.SOLID, strokeWidth = 1.0),
                    el(ElementType.TEXT, x + tam * 0.5, y + tam * 0.3, ancho - tam, alto - tam * 0.6, color)
                        .copy(text = lineas.joinToString("\n"), fontSize = tam, fontFamily = com.forge.pixpin.motor.ItemStyle.FONT_NUNITO, strokeWidth = 1.0)
                )
            }
            ComoAnotar.DIBUJAR -> {
                if (puntos.size < 2) return emptyList()
                val x0 = puntos.minOf { it.first }; val y0 = puntos.minOf { it.second }
                val p0 = puntos.first()
                listOf(el(ElementType.LINE, p0.first, p0.second, puntos.maxOf { it.first } - x0, puntos.maxOf { it.second } - y0, color)
                    .copy(strokeWidth = grosor * 1.5, points = puntos.map { Pt(it.first - p0.first, it.second - p0.second) }))
            }
        }
    }

    /** Parte un texto en renglones de unas [n] letras, por palabras. */
    private fun partir(texto: String, n: Int): List<String> {
        val v = ArrayList<String>()
        for (parrafo in texto.lines()) {
            val l = StringBuilder()
            for (w in parrafo.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                if (l.isNotEmpty() && l.length + 1 + w.length > n) { v += l.toString(); l.clear() }
                if (l.isNotEmpty()) l.append(' ')
                l.append(w)
            }
            v += l.toString()
        }
        return v
    }

    private fun zonaEn(zona: List<Double>, marco: DoubleArray): DoubleArray? {
        if (zona.size < 4) return null
        val (x, y, w, h) = zona
        if (x !in 0.0..1.0 || y !in 0.0..1.0 || w <= 0 || h <= 0) return null
        val mw = marco[2] - marco[0]; val mh = marco[3] - marco[1]
        return doubleArrayOf(marco[0] + x * mw, marco[1] + y * mh, marco[0] + min(x + w, 1.0) * mw, marco[1] + min(y + h, 1.0) * mh)
    }

    private fun puntosEn(puntos: List<Double>, marco: DoubleArray): List<Pair<Double, Double>> {
        val mw = marco[2] - marco[0]; val mh = marco[3] - marco[1]
        return puntos.chunked(2).filter { it.size == 2 }.map { (marco[0] + it[0].coerceIn(0.0, 1.0) * mw) to (marco[1] + it[1].coerceIn(0.0, 1.0) * mh) }
    }

    private fun colorDe(t: String?, como: ComoAnotar) = t?.let(com.forge.pixpin.ia.Herramientas::color) ?: como.color

    // ----------------------------------------------------------------------------- anotar

    /** Lo hecho: la hoja (desde 0; null en un plano), cuántas figuras, sitios y apariciones, y la vista del plano. */
    class Anotado(val pagina: Int?, val figuras: Int, val sitios: Int, val hallados: Int, val vista: DoubleArray?)

    private fun texto(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    private fun numeros(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()

    /** **Anota** (PC `anotar_en`) en la capa del documento [c]. */
    fun anotarEn(c: Candidato, args: JsonObject): Anotado {
        val como = texto(args, "tipo")?.let(ComoAnotar::de) ?: throw IllegalStateException("falta «tipo»")
        val txt = texto(args, "texto")
        val zona = numeros(args, "zona"); val puntos = numeros(args, "puntos")
        val color = colorDe(texto(args, "color"), como)
        val nota = texto(args, "nota") ?: if (como == ComoAnotar.NOTA) txt else null
        val pagina = (args["pagina"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 }?.minus(1)
        if (txt == null && zona.size < 4 && !(como == ComoAnotar.DIBUJAR && puntos.size >= 4) && como != ComoAnotar.NOTA) {
            throw IllegalStateException("di sobre que texto del documento va («texto») o una «zona»")
        }
        if (esPlano(c.nombre)) return anotarPlano(c, como, txt, zona, puntos, color, nota)
        val p = paginas(c.ruta, c.nombre)
        val hoja: Int; val cajas: List<DoubleArray>; val sitios: Int; val hallados: Int
        if (txt != null && (como != ComoAnotar.NOTA || zona.size < 4)) {
            val s = buscarEnPaginas(p, txt, pagina, 40)
            val primera = s.firstOrNull() ?: throw IllegalStateException("«$txt» no aparece en «${c.nombre}»")
            hoja = primera.pagina
            val deLaHoja = s.filter { it.pagina == hoja }.take(20)
            val marcarTodo = como in setOf(ComoAnotar.RESALTAR, ComoAnotar.SUBRAYAR, ComoAnotar.TACHAR)
            cajas = if (marcarTodo) deLaHoja.flatMap { it.cajas } else primera.cajas
            sitios = if (marcarTodo) deLaHoja.size else 1
            hallados = s.size
        } else {
            hoja = min(pagina ?: 0, max(p.altos.size - 1, 0))
            val marco = doubleArrayOf(0.0, 0.0, ANCHO_HOJA, p.altos.getOrNull(hoja) ?: (ANCHO_HOJA * 1.414))
            cajas = listOf(zonaEn(zona, marco) ?: doubleArrayOf(ANCHO_HOJA * 0.6, 60.0, ANCHO_HOJA * 0.62, 90.0))
            sitios = 1; hallados = 0
        }
        val alto = p.altos.getOrNull(hoja) ?: (ANCHO_HOJA * 1.414)
        val letra = cajas.firstOrNull()?.let { (it[3] - it[1]).coerceIn(10.0, 40.0) } ?: 18.0
        val nuevas = figuras(como, cajas, color, nota, puntosEn(puntos, doubleArrayOf(0.0, 0.0, ANCHO_HOJA, alto)), letra)
        if (nuevas.isEmpty()) throw IllegalStateException("no salio ninguna figura (¿una nota sin texto?)")
        escribirEnLaHoja(c, hoja, nuevas)
        return Anotado(hoja, nuevas.size, sitios, hallados, null)
    }

    /**
     * Escribe en la capa de la hoja [i] de un PDF o un Word del chat: la MISMA que lee y guarda el lector
     * (`anot-<uid>-p<i>` con su `.hoja`), en las unidades de la hoja.
     */
    private fun escribirEnLaHoja(c: Candidato, i: Int, nuevas: List<Element>) {
        val uid = c.mensaje.uid?.takeIf { it.isNotBlank() } ?: run { throw IllegalStateException("«${c.nombre}» no tiene código de mensaje: ábrelo una vez y vuelve a pedirlo") }
        val id = AnotacionesDelAdjunto.dePagina(uid, i)
        val escena = com.forge.pixpin.pdf.tintaDeLaHoja(contexto, id, c.ruta.path, i, 0, suelta = true, migrar = false) ?: Scene()
        ExcalidrawStore.guardar(contexto, id, escena.copy(elements = escena.elements + nuevas)) ?: throw IllegalStateException("no se pudo guardar lo anotado")
        AnotacionesDelAdjunto.escribirMarco(AnotacionesDelAdjunto.hoja(contexto.filesDir, id), com.forge.pixpin.pdf.marcoDeLaHoja(c.ruta.path, i))
    }

    /** Los textos del plano (la orden nativa en su proceso, con caché). */
    private fun textosDelPlano(c: Candidato): List<Pair<String, DoubleArray>> {
        val r = com.forge.pixpin.planos.LectorDePlanos.textos(contexto, c.ruta)
        val f = (r as? com.forge.pixpin.planos.LectorDePlanos.Companion.Resultado.Listo)?.archivo ?: throw IllegalStateException("no se pudo leer el plano «${c.nombre}»: $r")
        val v = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()) as JsonArray
        return v.mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            val t = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
            t to doubleArrayOf((a[1] as JsonPrimitive).doubleOrNull ?: 0.0, (a[2] as JsonPrimitive).doubleOrNull ?: 0.0, (a[3] as JsonPrimitive).doubleOrNull ?: 0.0)
        }
    }

    /** `ver Jr Antisuyo` → `ver jr antisuyo`: sin mayúsculas, tildes ni puntos (PC `plegar`). */
    private fun plegar(s: String) = Acciones.normalizar(s).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** **Busca en los textos del plano** (PC `plano_cad::textos::buscar`): entera, o por palabras sueltas; los más grandes primero. */
    fun buscarEnElPlano(textos: List<Pair<String, DoubleArray>>, consulta: String, maximo: Int): List<Pair<String, DoubleArray>> {
        val q = plegar(consulta)
        if (q.isEmpty()) return emptyList()
        val palabras = q.split(' ')
        val enteras = ArrayList<Pair<String, DoubleArray>>(); val sueltas = ArrayList<Pair<String, DoubleArray>>()
        for (t in textos) {
            val p = plegar(t.first)
            if (p.contains(q)) enteras += t
            else if (palabras.all { w -> p.split(' ').any { x -> x == w || (w.length >= 4 && x.startsWith(w)) } }) sueltas += t
        }
        return (if (enteras.isEmpty()) sueltas else enteras).sortedByDescending { it.second[2] }.take(maximo)
    }

    /** La vista para enseñar lo hallado (PC `vista_de`): centrada en el texto, unas 40 letras de ancho. */
    fun vistaDe(t: Pair<String, DoubleArray>): DoubleArray {
        val largo = max(t.first.length, 4).toDouble(); val alto = t.second[2]
        val ancho = max(max(alto * 40.0, alto * largo * 1.6), 1e-6)
        return doubleArrayOf(t.second[0] + alto * largo * 0.35, t.second[1] + alto * 0.5, ancho)
    }

    private fun anotarPlano(c: Candidato, como: ComoAnotar, txt: String?, zona: List<Double>, puntos: List<Double>, color: String, nota: String?): Anotado {
        val r = com.forge.pixpin.planos.LectorDePlanos.leer(contexto, c.ruta)
        val f = (r as? com.forge.pixpin.planos.LectorDePlanos.Companion.Resultado.Listo)?.archivo ?: throw IllegalStateException("no se pudo leer el plano «${c.nombre}»")
        val m = com.forge.pixpin.planos.ModeloCad.abrir(f)
        val ox = m.origenX; val oy = m.origenY
        // Las cajas en coordenadas del plano (relativas a su origen, y hacia arriba).
        val cajasPlano: List<DoubleArray>; val hallados: Int; val letraPlano: Double
        if (txt != null && (como != ComoAnotar.NOTA || zona.size < 4)) {
            val h = buscarEnElPlano(textosDelPlano(c), txt, 10)
            if (h.isEmpty()) throw IllegalStateException("«$txt» no aparece en el plano «${c.nombre}»")
            val uno = como in setOf(ComoAnotar.ENCERRAR, ComoAnotar.MARCO, ComoAnotar.NOTA)
            cajasPlano = h.take(if (uno) 1 else 10).map { (t, p) ->
                val largo = max(t.length, 1) * p[2] * 0.75
                val x = p[0] - ox; val y = p[1] - oy
                doubleArrayOf(x, y, x + largo, y + p[2])
            }
            hallados = h.size; letraPlano = h[0].second[2]
        } else {
            val k = m.caja
            val x0 = k[0].toDouble(); val y0 = k[1].toDouble(); val x1 = k[2].toDouble(); val y1 = k[3].toDouble()
            val caja = if (zona.size >= 4 && zona[2] > 0 && zona[3] > 0) {
                val ax = x1 - x0; val ay = y1 - y0
                doubleArrayOf(x0 + zona[0] * ax, y1 - (zona[1] + zona[3]) * ay, x0 + (zona[0] + zona[2]) * ax, y1 - zona[1] * ay)
            } else doubleArrayOf(x1 - (x1 - x0) * 0.2, y1 - (y1 - y0) * 0.1, x1 - (x1 - x0) * 0.05, y1 - (y1 - y0) * 0.05)
            cajasPlano = listOf(caja); hallados = 0; letraPlano = max((caja[3] - caja[1]) * 0.2, (y1 - y0) * 0.01)
        }
        // La capa del plano, en las unidades de su fichero (o las de las letras si aún no tiene).
        val base = com.forge.pixpin.ui.ExportarDocumentoAnotado.baseDe(contexto, c.ruta)
        val id = com.forge.pixpin.ui.ExportarDocumentoAnotado.idDeLaCapa(c.ruta, base)
        val ruta = File(ExcalidrawStore.rutaDe(contexto, id))
        val hojaF = AnotacionesDelAdjunto.hoja(contexto.filesDir, id)
        val encaje = AnotacionesDelAdjunto.leerMarco(hojaF)?.let { com.forge.pixpin.planos.CapaDelPlano.encajeDe(it, m.caja) }
            ?: com.forge.pixpin.planos.CapaDelPlano.Encaje(com.forge.pixpin.planos.EscalaDeLaCapa.leerOPoner(ruta, m), 0.0, 0.0)
        // Del plano (y arriba) a la capa (y abajo): la esquina de arriba.
        fun aCapa(k: DoubleArray) = doubleArrayOf(encaje.aCapaX(k[0]), encaje.aCapaY(k[3]), encaje.aCapaX(k[2]), encaje.aCapaY(k[1]))
        val nuevas: List<Element> = if (como == ComoAnotar.MARCO) {
            // El marco para imprimir de verdad, el del visor.
            val u = doubleArrayOf(cajasPlano.minOf { it[0] }, cajasPlano.minOf { it[1] }, cajasPlano.maxOf { it[2] }, cajasPlano.maxOf { it[3] })
            val aire = letraPlano * 2
            val marco = com.forge.pixpin.planos.ImprimirPlano.Marco(u[0] - aire, u[1] - aire, u[2] + aire, u[3] + aire)
            val escena = ExcalidrawStore.cargar(ruta.path) ?: Scene()
            val (cotas, marcos) = com.forge.pixpin.planos.CapaDelPlano.leer(escena, encaje)
            val con = com.forge.pixpin.planos.CapaDelPlano.con(escena, encaje, cotas, marcos + marco, System.currentTimeMillis())
            guardarCapaDelPlano(id, ruta, hojaF, encaje, m, con)
            return Anotado(null, 1, cajasPlano.size, hallados, vistaDelPlano(cajasPlano[0], letraPlano, ox, oy))
        } else {
            val cajas = cajasPlano.map(::aCapa)
            val letra = max(letraPlano / encaje.u, 1.0)
            val k = m.caja
            val todo = aCapa(doubleArrayOf(k[0].toDouble(), k[1].toDouble(), k[2].toDouble(), k[3].toDouble()))
            figuras(como, cajas, color, nota, puntosEn(puntos, todo), letra)
        }
        if (nuevas.isEmpty()) throw IllegalStateException("no salio ninguna figura (¿una nota sin texto?)")
        val escena = ExcalidrawStore.cargar(ruta.path) ?: Scene()
        guardarCapaDelPlano(id, ruta, hojaF, encaje, m, escena.copy(elements = escena.elements + nuevas))
        return Anotado(null, nuevas.size, cajasPlano.size, hallados, vistaDelPlano(cajasPlano[0], letraPlano, ox, oy))
    }

    private fun vistaDelPlano(c: DoubleArray, letra: Double, ox: Double, oy: Double): DoubleArray {
        val ancho = max((c[2] - c[0]) * 6.0, letra * 40.0)
        return doubleArrayOf((c[0] + c[2]) / 2 + ox, (c[1] + c[3]) / 2 + oy, ancho)
    }

    /** La hoja antes que la capa (una capa sin su hoja al lado se leería con otra escala), como el visor. */
    private fun guardarCapaDelPlano(id: String, ruta: File, hojaF: File, encaje: com.forge.pixpin.planos.CapaDelPlano.Encaje,
                                    m: com.forge.pixpin.planos.ModeloCad, escena: Scene) {
        val texto = com.forge.pixpin.planos.CapaDelPlano.hojaDe(encaje, m.caja).aTexto()
        if (!hojaF.isFile || hojaF.readText() != texto) hojaF.writeText(texto)
        ExcalidrawStore.guardar(contexto, id, escena) ?: throw IllegalStateException("no se pudo guardar lo anotado en el plano")
        ruta.setLastModified(System.currentTimeMillis())
    }

    /** Abre el documento donde quedó (el lector en su hoja, o el plano en su vista). */
    fun abrirEn(c: Candidato, pagina: Int?, vista: DoubleArray?) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            if (esPlano(c.nombre)) com.forge.pixpin.planos.PlanoActivity.abrir(contexto, c.ruta.path, c.nombre, vista)
            else com.forge.pixpin.pdf.LectorPdfActivity.abrir(contexto, c.ruta.path, c.nombre, mensaje = c.mensaje.id, pagina = pagina?.plus(1))
        }
    }

    // ------------------------------------------------------------------------------ exportar

    /**
     * **Los formatos de «Exportar» de [c]**, los mismos que la hoja de compartir del chat y de cada visor:
     * un PDF con lo anotado y su página web; un Word, un PowerPoint o un libro, su PDF y su página web;
     * un plano, su PDF, su página web, sus imágenes y su DXF; una foto, PNG, JPG y PDF; y el original.
     */
    private fun formatos(c: Candidato): List<com.forge.pixpin.ui.Compartible.Formato> {
        val ruta = c.ruta.path
        val titulo = c.nombre.substringBeforeLast('.')
        val v = ArrayList<com.forge.pixpin.ui.Compartible.Formato>()
        when {
            esPlano(c.nombre) -> {
                val r = com.forge.pixpin.planos.LectorDePlanos.leer(contexto, c.ruta)
                val f = (r as? com.forge.pixpin.planos.LectorDePlanos.Companion.Resultado.Listo)?.archivo ?: throw IllegalStateException("no se pudo leer el plano «${c.nombre}»")
                val m = com.forge.pixpin.planos.ModeloCad.abrir(f)
                val base = com.forge.pixpin.ui.ExportarDocumentoAnotado.baseDe(contexto, c.ruta)
                val id = com.forge.pixpin.ui.ExportarDocumentoAnotado.idDeLaCapa(c.ruta, base)
                val capa = File(ExcalidrawStore.rutaDe(contexto, id))
                val encaje = AnotacionesDelAdjunto.leerMarco(AnotacionesDelAdjunto.hoja(contexto.filesDir, id))?.let { com.forge.pixpin.planos.CapaDelPlano.encajeDe(it, m.caja) }
                    ?: com.forge.pixpin.planos.CapaDelPlano.Encaje(com.forge.pixpin.planos.EscalaDeLaCapa.leerOPoner(capa, m), 0.0, 0.0)
                val escena = ExcalidrawStore.cargar(capa.path)
                val (cotas, marcos) = escena?.let { com.forge.pixpin.planos.CapaDelPlano.leer(it, encaje) } ?: (emptyList<List<DoubleArray>>() to emptyList())
                v += com.forge.pixpin.planos.ExportarPlano.compartible(contexto, com.forge.pixpin.planos.ExportarPlano.Plano(c.ruta, c.nombre, m, escena, encaje, cotas, marcos)).formatos
            }
            extension(c.nombre) == "pdf" -> {
                val anotado = com.forge.pixpin.ui.ExportarPdfAnotado.DelChat(contexto, ruta)
                v += com.forge.pixpin.ui.ExportarPdfAnotado.formatoPdf(contexto, ruta, titulo, anotado::escena, anotado::espacios, anotado::marcas)
                val cuantas = runCatching { leerPdf(c.ruta.readBytes())?.paginas()?.size }.getOrNull() ?: 1
                v += com.forge.pixpin.ui.ExportarPdfAnotado.formato(contexto, ruta, titulo, cuantas, anotado::escena)
                v += com.forge.pixpin.ui.ExportarVisor.imagenes(contexto, { titulo }) { com.forge.pixpin.ui.ExportarVisor.paginaConTinta(contexto, ruta, 0, anotado.escena(0)) }
            }
            esOfficeDePaginas(c.nombre) -> {
                val anotado = com.forge.pixpin.ui.ExportarPdfAnotado.DelChat(contexto, ruta)
                v += com.forge.pixpin.ui.ExportarPdfAnotado.formatoPdf(contexto, ruta, titulo, anotado::escena, anotado::espacios, anotado::marcas,
                    prepararBase = { com.forge.pixpin.office.HojasOffice.de(contexto, ruta)?.let { com.forge.pixpin.office.OfficeAPdf.enCache(contexto, it) }?.path })
                if (com.forge.pixpin.ui.ExportarDocumentoAnotado.esDocumento(c.nombre, ruta)) v += com.forge.pixpin.ui.ExportarDocumentoAnotado.formato(contexto, c.ruta, c.nombre)
            }
            com.forge.pixpin.ui.ExportarDocumentoAnotado.esDocumento(c.nombre, ruta) -> v += com.forge.pixpin.ui.ExportarDocumentoAnotado.formato(contexto, c.ruta, c.nombre)
            esImagen(c.nombre) -> {
                val foto: suspend (List<String>) -> android.graphics.Bitmap? = { com.forge.pixpin.pin.ImageStore.load(ruta) }
                v += com.forge.pixpin.ui.ExportarVisor.imagenes(contexto, { titulo }, foto = foto)
                v += com.forge.pixpin.ui.ExportarVisor.deLaVista(contexto, titulo) { com.forge.pixpin.pin.ImageStore.load(ruta) }.filter { it.id == "pdf" || it.id == "web" }
            }
        }
        v += com.forge.pixpin.ui.ExportarVisor.original(contexto, ruta, c.nombre)
        return v
    }

    /** El formato de «Exportar» que corresponde a lo que pidió el modelo (los ids del PC). */
    private fun elegido(formatos: List<com.forge.pixpin.ui.Compartible.Formato>, formato: String) = when (formato) {
        "pdf" -> formatos.firstOrNull { it.id == "pdf-anotado" } ?: formatos.firstOrNull { it.id.startsWith("pdf") }
        "web" -> formatos.firstOrNull { it.id == "web-anotada" } ?: formatos.firstOrNull { it.id.contains("web") }
        else -> formatos.firstOrNull { it.id == formato }
    }

    /** El texto de un PDF, Word o PowerPoint (o de un plano), para «texto» y «md». */
    private fun textoDe(c: Candidato, md: Boolean): String {
        if (esPlano(c.nombre)) return textosDelPlano(c).joinToString("\n") { it.first }
        val p = paginas(c.ruta, c.nombre)
        return p.textos.withIndex().joinToString("\n\n") { (i, t) -> (if (md) "## Página ${i + 1}\n\n" else "") + t.texto.trim() }
    }

    /** **Exporta** (PC `exportar_a`): los ficheros hechos, ya metidos en el chat. */
    fun exportar(c: Candidato, formato: String): List<File> {
        val f = formato.trim().lowercase()
        val hechos: List<File> = if (f == "texto" || f == "md") {
            val destino = File(com.forge.pixpin.ui.ExportarVisor.carpeta(contexto), com.forge.pixpin.ui.ExportarVisor.nombre(c.nombre.substringBeforeLast('.'), if (f == "md") "md" else "txt"))
            destino.writeText(textoDe(c, f == "md"))
            listOf(destino)
        } else {
            val todos = formatos(c)
            val el = elegido(todos, f) ?: throw IllegalStateException("«${c.nombre}» no se exporta a $f: se puede a ${todos.map { it.id.substringBefore('-') }.distinct().joinToString(", ")}")
            val generar = el.generar ?: throw IllegalStateException("$f no deja un archivo")
            val salida = kotlinx.coroutines.runBlocking { generar(emptyList()) } ?: throw IllegalStateException("exportar a $f no dejo ningun fichero")
            listOf(salida.archivo)
        }
        val ahora = System.currentTimeMillis()
        return hechos.mapIndexed { i, h ->
            val destino = File(almacen.carpetaDeAdjuntos(), "${ahora + i}-${h.name}")
            h.copyTo(destino, overwrite = true)
            almacen.anadir(Mensaje(id = java.util.UUID.randomUUID().toString(), cuando = ahora + i, clase = if (esImagen(h.name)) Clase.IMAGEN else Clase.ARCHIVO,
                ruta = destino.path, nombre = h.name, bytes = destino.length(), texto = "«${c.nombre}» en $f", proyecto = chat))
            destino
        }
    }

    // ------------------------------------------------------------------------- buscar dentro

    /** **Busca dentro y abre ahí** (PC `buscar_dentro`): lo que se dice y dónde abrir. */
    fun buscarDentro(c: Candidato, consulta: String, abrir: Boolean): String {
        if (esPlano(c.nombre)) {
            val ts = textosDelPlano(c)
            val h = buscarEnElPlano(ts, consulta, 5)
            val primero = h.firstOrNull() ?: throw IllegalStateException("«$consulta» no aparece en el plano «${c.nombre}» (${ts.size} textos mirados)")
            if (abrir) abrirEn(c, null, vistaDe(primero))
            val lista = h.joinToString("; ") { "«${it.first}» en (${"%.1f".format(java.util.Locale.ROOT, it.second[0])}, ${"%.1f".format(java.util.Locale.ROOT, it.second[1])})" }
            return "${h.size} sitio(s) en «${c.nombre}»: $lista${if (abrir) "; abierto en el primero" else ""}"
        }
        val p = paginas(c.ruta, c.nombre)
        val s = buscarEnPaginas(p, consulta, null, 8)
        val primero = s.firstOrNull() ?: throw IllegalStateException("«$consulta» no aparece en «${c.nombre}»")
        if (abrir) abrirEn(c, primero.pagina, null)
        return "${s.size} sitio(s) en «${c.nombre}»: ${s.joinToString("; ") { "pagina ${it.pagina + 1}: «…${it.contexto}…»" }}${if (abrir) "; abierto en el primero" else ""}"
    }
}
