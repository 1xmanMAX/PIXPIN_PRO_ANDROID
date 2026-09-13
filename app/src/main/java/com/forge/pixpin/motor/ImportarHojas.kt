package com.forge.pixpin.motor

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt

/**
 * **Un libro de hojas de cálculo convertido en tablas de PixPin**: cada hoja, una tabla con sus
 * valores, sus fórmulas, su negrita, sus colores y sus anchos. Lo pidió el usuario el
 * 11-sep-2026: que un Excel compartido al chat entre como entra un PDF, y se abra en Tablas.
 *
 * ## Qué se lee
 *
 * - **`.xlsx`** —Excel, y lo que baja Google Sheets—: un ZIP de XML. Se leen con SAX, de corrido
 *   y sin cargar el árbol: un libro de cien mil celdas no revienta la memoria del teléfono.
 * - **`.ods`** —LibreOffice, y Sheets también lo baja—: igual, con otro vocabulario.
 * - **`.csv` y `.tsv`**, con el separador que traigan (`;` en la Excel española, `,` en la inglesa).
 * - Un **`.xls`** antiguo es binario (BIFF) y no se lee: se dice, en vez de enseñar basura.
 *
 * ## Fórmulas que no se saben calcular
 *
 * Excel tiene cuatrocientas funciones y aquí hay noventa; y hay cosas que una tabla suelta no
 * puede tener, como `Hoja2!A1`. El libro guarda **el último resultado** de cada fórmula, así que
 * se calcula todo y se compara: donde lo de aquí no coincide con lo que dijo Excel, la celda se
 * queda **con su valor** en vez de enseñar un error o un total distinto. Solo esa celda: las que
 * dependen de ella siguen siendo fórmulas y vuelven a cuadrar. Ver [conValoresDondeNoCuadra].
 */
object ImportarHojas {

    class HojaImportada(
        val nombre: String,
        val tabla: TablaDeCalculo,
        /** Cuántas fórmulas se quedaron con su valor porque aquí no se sabían calcular. */
        val formulasComoValor: Int = 0
    ) {
        fun llamada(otro: String) = HojaImportada(otro, tabla.copy(nombre = otro), formulasComoValor)
    }

    /** Lo que no se puede leer, con el porqué dicho para quien lo va a leer. */
    class NoSeLee(mensaje: String) : Exception(mensaje)

    private val LEGIBLES = setOf("xlsx", "xlsm", "ods", "csv", "tsv")

    fun extension(nombre: String): String = nombre.substringAfterLast('.', "").lowercase()

    /** Si el archivo es un libro de hojas (o un `.xls`, que se reconoce para decir que no se lee). */
    fun esLibro(nombre: String): Boolean = extension(nombre).let { it in LEGIBLES || it == "xls" }

    /**
     * Las hojas de [archivo]. [nombre] es el nombre con el que llegó —el del archivo copiado puede
     * no tener extensión—. Una sola hoja se llama como el archivo; varias, «archivo · hoja».
     */
    fun leer(archivo: File, nombre: String = archivo.name, ahora: Long = System.currentTimeMillis()): List<HojaImportada> {
        val ext = extension(nombre).ifEmpty { extension(archivo.name) }
        val base = nombre.substringBeforeLast('.').ifBlank { "Tabla" }
        val hojas = when (ext) {
            "xlsx", "xlsm" -> deXlsx(archivo, ahora)
            "ods" -> deOds(archivo, ahora)
            "csv", "tsv" -> listOf(deCsv(textoDe(archivo), base, ext == "tsv", ahora))
            "xls" -> throw NoSeLee("Los .xls antiguos no se pueden leer: ábrelo y guárdalo como .xlsx")
            else -> {
                val cabeza = archivo.inputStream().use { i -> ByteArray(4).also { i.read(it) } }
                if (cabeza[0] == 'P'.code.toByte() && cabeza[1] == 'K'.code.toByte()) {
                    val z = entradas(archivo)
                    if (z.containsKey("content.xml")) deOds(archivo, ahora) else deXlsx(archivo, ahora)
                } else listOf(deCsv(textoDe(archivo), base, false, ahora))
            }
        }
        if (hojas.isEmpty()) throw NoSeLee("El libro no tiene ninguna hoja con datos")
        return if (hojas.size == 1) listOf(hojas[0].llamada(base)) else hojas.map { it.llamada("$base · ${it.nombre}") }
    }

    // ---- Lo común ----

    private fun entradas(archivo: File): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(archivo.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name.removePrefix("/")
                if (n.endsWith(".xml") || n.endsWith(".rels")) out[n] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    private fun sax(bytes: ByteArray, h: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = false
        // Un libro que llega de fuera no puede pedir que se lean otros archivos del teléfono.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.newSAXParser().parse(ByteArrayInputStream(bytes), h)
    }

    /** El nombre sin prefijo: `x:sheet` y `sheet` son lo mismo. */
    private fun local(q: String) = q.substringAfter(':')

    private fun attr(a: Attributes, nombre: String): String? {
        for (i in 0 until a.length) if (local(a.getQName(i)) == nombre) return a.getValue(i)
        return null
    }

    /** UTF-8, y si trae caracteres rotos, Latin-1: el CSV de la Excel de Windows. */
    private fun textoDe(archivo: File): String {
        val bytes = archivo.readBytes()
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    /** Un texto que, escrito tal cual, se leería como número, fecha o fórmula, va con `'`. */
    private fun textoComoLiteral(s: String): String? {
        if (s.isEmpty()) return null
        return if (s.startsWith("=") || s.startsWith("'") || Calculadora.literal(s) !is Valor.Txt) "'$s" else s
    }

    /** Un número guardado con sus diecisiete cifras (`0.30000000000000004`), a quince. */
    private fun limpio(d: Double): String {
        if (d == 0.0) return "0"
        return BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    private const val GENERAL = 0
    private const val FECHA = 1
    private const val PORCENTAJE = 2

    private fun numeroComoLiteral(v: String, tipo: Int, fecha1904: Boolean): String? {
        val d = v.trim().toDoubleOrNull() ?: return v.ifBlank { null }
        return when (tipo) {
            FECHA -> {
                val serial = if (fecha1904) d + 1462 else d
                if (serial > 0 && serial < 2958466) CalculoFormato.textoDeFecha(serial) else limpio(d)
            }
            PORCENTAJE -> CalculoFormato.general(d * 100) + "%"
            else -> limpio(d)
        }
    }

    private val VOLATIL = Regex("\\b(RAND|RANDBETWEEN|NOW|TODAY|ALEATORIO|ALEATORIO\\.ENTRE|HOY|AHORA)\\s*\\(", RegexOption.IGNORE_CASE)

    /** Si lo calculado aquí es lo mismo que el resultado que traía el libro. */
    private fun cuadra(v: Valor, guardado: String): Boolean {
        val esperado = if (guardado.isEmpty()) Valor.Txt("") else Calculadora.literal(guardado)
        return when {
            v is Valor.Num && esperado is Valor.Num ->
                Calculadora.numeros(v.n, esperado.n) == 0 || Math.abs(v.n - esperado.n) <= 1e-9 * maxOf(1.0, Math.abs(esperado.n))
            v is Valor.Txt && esperado is Valor.Txt -> v.s == esperado.s
            v is Valor.Vacio && esperado is Valor.Txt -> esperado.s.isEmpty()
            v is Valor.Log && esperado is Valor.Log -> v.b == esperado.b
            v is Valor.Err && esperado is Valor.Txt -> esperado.s.startsWith("#")
            else -> Calculadora.mostrar(v) == Calculadora.mostrar(esperado)
        }
    }

    /**
     * **Donde lo de aquí no da lo que dio Excel, se deja el valor.** Se cambian primero las celdas
     * que no dependen de otras que tampoco cuadran —la raíz del problema—, se vuelve a calcular y
     * se repite: así `=D5+1` sigue siendo fórmula aunque `D5` usara una función que aquí no hay.
     * Devuelve cuántas celdas se quedaron con su valor.
     */
    private fun conValoresDondeNoCuadra(celdas: HashMap<String, String>, guardados: Map<String, String>): Int {
        if (guardados.isEmpty()) return 0
        val calc = Calculadora()
        for ((dir, v) in celdas) Celdas.leer(dir)?.let { calc.poner(it[0], it[1], v) }
        var cambiadas = 0
        repeat(6) {
            val malas = ArrayList<IntArray>()
            val dirs = ArrayList<String>()
            for ((dir, guardado) in guardados) {
                val raw = celdas[dir] ?: continue
                if (!Calculadora.esFormula(raw) || VOLATIL.containsMatchIn(raw)) continue
                val p = Celdas.leer(dir) ?: continue
                if (!cuadra(calc.valor(p[0], p[1]), guardado)) { malas += p; dirs += dir }
            }
            if (malas.isEmpty()) return cambiadas
            val raices = if (malas.size > 2000) dirs.indices.toList() else dirs.indices.filter { i ->
                CalculoLexico.referencias(celdas[dirs[i]]!!).none { r ->
                    malas.any { m -> m[0] in r.f1..r.f2 && m[1] in r.c1..r.c2 }
                }
            }
            for (i in raices.ifEmpty { dirs.indices.toList() }) {
                val guardado = guardados[dirs[i]].orEmpty()
                if (guardado.isEmpty()) celdas.remove(dirs[i]) else celdas[dirs[i]] = guardado
                calc.poner(malas[i][0], malas[i][1], guardado)
                cambiadas++
            }
        }
        return cambiadas
    }

    // ---- XLSX ----

    private class EstiloXlsx(val negrita: Boolean, val fondo: String?, val alineacion: String?, val tipo: Int)

    private fun deXlsx(archivo: File, ahora: Long): List<HojaImportada> {
        val z = runCatching { entradas(archivo) }.getOrElse { throw NoSeLee("El archivo no es un libro de Excel válido") }
        val libro = z["xl/workbook.xml"] ?: throw NoSeLee("El archivo no es un libro de Excel válido")
        class Entrada(val nombre: String, val rid: String, val oculta: Boolean)
        val hojas = ArrayList<Entrada>()
        var fecha1904 = false
        sax(libro, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (local(q)) {
                    "sheet" -> hojas += Entrada(attr(a, "name") ?: "Hoja", attr(a, "id") ?: "", attr(a, "state").let { it == "hidden" || it == "veryHidden" })
                    "workbookPr" -> fecha1904 = attr(a, "date1904").let { it == "1" || it == "true" }
                }
            }
        })
        val rels = HashMap<String, String>()
        z["xl/_rels/workbook.xml.rels"]?.let { bytes ->
            sax(bytes, object : DefaultHandler() {
                override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                    if (local(q) == "Relationship") rels[attr(a, "Id") ?: return] = attr(a, "Target") ?: return
                }
            })
        }
        val compartidas = ArrayList<String>()
        z["xl/sharedStrings.xml"]?.let { bytes ->
            sax(bytes, object : DefaultHandler() {
                val sb = StringBuilder()
                var enT = false
                var fonetica = false
                override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                    when (local(q)) {
                        "si" -> sb.setLength(0)
                        "rPh" -> fonetica = true
                        "t" -> enT = !fonetica
                    }
                }
                override fun endElement(u: String?, l: String?, q: String) {
                    when (local(q)) {
                        "t" -> enT = false
                        "rPh" -> fonetica = false
                        "si" -> compartidas += sb.toString()
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (enT) sb.append(ch, start, length) }
            })
        }
        val estilos = z["xl/styles.xml"]?.let { estilosXlsx(it) }.orEmpty()
        return hojas.filter { !it.oculta }.mapNotNull { h ->
            val destino = rels[h.rid] ?: return@mapNotNull null
            val ruta = if (destino.startsWith("/")) destino.removePrefix("/") else "xl/$destino"
            val bytes = z[ruta] ?: return@mapNotNull null
            hojaXlsx(h.nombre, bytes, compartidas, estilos, fecha1904, ahora)
        }.filter { it.tabla.celdas.isNotEmpty() }
    }

    private fun tipoDeFormato(id: Int, codigo: String?): Int {
        if (id == 9 || id == 10) return PORCENTAJE
        if (id in 14..17 || id == 22 || id in 27..36 || id in 50..58) return FECHA
        val c = (codigo ?: return GENERAL).replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*\\]"), "").lowercase()
        if (c.contains('%')) return PORCENTAJE
        return if (Regex("[yd]").containsMatchIn(c) || (c.contains('m') && !c.contains('h') && !c.contains('s') && !c.contains('0'))) FECHA else GENERAL
    }

    private fun estilosXlsx(bytes: ByteArray): List<EstiloXlsx> {
        val formatos = HashMap<Int, String>()
        val fuentes = ArrayList<Boolean>()
        val fondos = ArrayList<String?>()
        val xfs = ArrayList<EstiloXlsx>()
        sax(bytes, object : DefaultHandler() {
            var seccion = ""
            var negrita = false
            var patron: String? = null
            var color: String? = null
            var xfFormato = 0
            var xfFuente = 0
            var xfFondo = 0
            var xfAlineacion: String? = null
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (val n = local(q)) {
                    "numFmts", "fonts", "fills", "cellXfs", "cellStyleXfs" -> seccion = n
                    "numFmt" -> attr(a, "numFmtId")?.toIntOrNull()?.let { formatos[it] = attr(a, "formatCode").orEmpty() }
                    "font" -> negrita = false
                    "b" -> if (seccion == "fonts") negrita = attr(a, "val").let { it == null || it == "1" || it == "true" }
                    "fill" -> { patron = null; color = null }
                    "patternFill" -> patron = attr(a, "patternType")
                    "fgColor" -> if (seccion == "fills") color = attr(a, "rgb")?.takeIf { it.length >= 6 }?.let { "#" + it.takeLast(6).lowercase() }
                    "xf" -> if (seccion == "cellXfs") {
                        xfFormato = attr(a, "numFmtId")?.toIntOrNull() ?: 0
                        xfFuente = attr(a, "fontId")?.toIntOrNull() ?: 0
                        xfFondo = attr(a, "fillId")?.toIntOrNull() ?: 0
                        xfAlineacion = null
                    }
                    "alignment" -> if (seccion == "cellXfs") xfAlineacion = when (attr(a, "horizontal")) {
                        "center", "centerContinuous" -> "c"
                        "right" -> "d"
                        "left" -> "i"
                        else -> null
                    }
                }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (val n = local(q)) {
                    "numFmts", "fonts", "fills", "cellXfs", "cellStyleXfs" -> if (seccion == n) seccion = ""
                    "font" -> if (seccion == "fonts") fuentes += negrita
                    "fill" -> if (seccion == "fills") fondos += if (patron == "solid") color else null
                    "xf" -> if (seccion == "cellXfs") xfs += EstiloXlsx(
                        fuentes.getOrElse(xfFuente) { false }, fondos.getOrNull(xfFondo), xfAlineacion,
                        tipoDeFormato(xfFormato, formatos[xfFormato])
                    )
                }
            }
        })
        return xfs
    }

    private fun hojaXlsx(
        nombre: String, bytes: ByteArray, compartidas: List<String>, estilos: List<EstiloXlsx>,
        fecha1904: Boolean, ahora: Long
    ): HojaImportada {
        val celdas = HashMap<String, String>()
        val guardados = HashMap<String, String>()
        val formatos = HashMap<String, EstiloDeCelda>()
        val anchos = HashMap<String, Int>()
        val base = HashMap<String, Triple<String, Int, Int>>()
        sax(bytes, object : DefaultHandler() {
            var fila = -1
            var siguiente = 0
            var f = 0
            var c = 0
            var tipo: String? = null
            var estilo = -1
            var fTipo: String? = null
            var fSi: String? = null
            val formula = StringBuilder()
            val valor = StringBuilder()
            val enLinea = StringBuilder()
            var dentro = 0 // 1 fórmula, 2 valor, 3 texto en línea
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (local(q)) {
                    "row" -> { fila = attr(a, "r")?.toIntOrNull()?.minus(1) ?: (fila + 1); siguiente = 0 }
                    "c" -> {
                        val p = attr(a, "r")?.let { Celdas.leer(it) }
                        f = p?.get(0) ?: fila
                        c = p?.get(1) ?: siguiente
                        siguiente = c + 1
                        tipo = attr(a, "t")
                        estilo = attr(a, "s")?.toIntOrNull() ?: -1
                        fTipo = null; fSi = null
                        formula.setLength(0); valor.setLength(0); enLinea.setLength(0)
                    }
                    "f" -> { dentro = 1; fTipo = attr(a, "t"); fSi = attr(a, "si") }
                    "v" -> dentro = 2
                    "t" -> if (tipo == "inlineStr") dentro = 3
                    "col" -> {
                        val desde = attr(a, "min")?.toIntOrNull() ?: return
                        val hasta = minOf(attr(a, "max")?.toIntOrNull() ?: desde, Celdas.MAX_COLS, desde + 700)
                        val ancho = attr(a, "width")?.toDoubleOrNull() ?: return
                        if (attr(a, "hidden") == "1") return
                        for (col in desde - 1 until hasta) anchos[Celdas.letras(col)] = (ancho * 7 + 5).roundToInt().coerceIn(TablaViva.ANCHO_MIN, TablaViva.ANCHO_MAX)
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                when (dentro) {
                    1 -> formula.append(ch, start, length)
                    2 -> valor.append(ch, start, length)
                    3 -> enLinea.append(ch, start, length)
                }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (local(q)) {
                    "f", "v", "t" -> dentro = 0
                    "c" -> celda()
                }
            }
            fun celda() {
                if (f < 0 || f >= Celdas.MAX_FILAS || c >= Celdas.MAX_COLS) return
                val e = estilos.getOrNull(estilo)
                var escrita: String? = null
                val texto = formula.toString()
                if (texto.isNotBlank()) {
                    escrita = "=" + texto.replace("_xlfn._xlws.", "").replace("_xlfn.", "").replace("_xlws.", "")
                    if (fTipo == "shared" && fSi != null) base[fSi!!] = Triple(escrita, f, c)
                } else if (fTipo == "shared" && fSi != null) {
                    base[fSi!!]?.let { (b, f0, c0) -> escrita = CalculoLexico.desplazar(b, f - f0, c - c0) }
                }
                val v = valor.toString()
                val literal = when (tipo) {
                    "s" -> v.trim().toIntOrNull()?.let { compartidas.getOrNull(it) }?.let { textoComoLiteral(it) }
                    "inlineStr" -> textoComoLiteral(enLinea.toString())
                    "str" -> textoComoLiteral(v)
                    "b" -> if (v.trim() == "1") "VERDADERO" else "FALSO"
                    "e" -> v.ifBlank { null }
                    else -> numeroComoLiteral(v, e?.tipo ?: GENERAL, fecha1904)
                }
                val dir = Celdas.nombre(f, c)
                val final = escrita ?: literal
                if (!final.isNullOrEmpty()) celdas[dir] = final
                if (escrita != null) guardados[dir] = literal.orEmpty()
                if (e != null) {
                    val propio = EstiloDeCelda(n = e.negrita, a = e.alineacion, f = e.fondo)
                    if (!propio.vacio) formatos[dir] = propio
                }
            }
        })
        val comoValor = conValoresDondeNoCuadra(celdas, guardados)
        // Los formatos de celdas vacías fuera de lo escrito no se traen: una fila entera pintada
        // de amarillo hasta la columna XFD no es parte de la tabla.
        val caja = Calculadora().also { k -> celdas.forEach { (d, v) -> Celdas.leer(d)?.let { k.poner(it[0], it[1], v) } } }.caja()
        val estilosDentro = if (caja == null) emptyMap() else formatos.filterKeys { d ->
            val p = Celdas.leer(d) ?: return@filterKeys false
            p[0] in caja[0]..caja[2] && p[1] in caja[1]..caja[3]
        }
        return HojaImportada(nombre, TablaDeCalculo(nombre, celdas, anchos, estilosDentro, ahora), comoValor)
    }

    // ---- ODS ----

    private val REFERENCIA_ODF = Regex("\\[([^\\]]*)\\]")

    /** `of:=SUM([.A1:.B3])` → `=SUM(A1:B3)`. Las de otra hoja se quedan como vienen y no cuadran. */
    private fun formulaOdf(f: String): String? {
        val i = f.indexOf('=')
        if (i < 0) return null
        val cuerpo = REFERENCIA_ODF.replace(f.substring(i + 1)) { m ->
            val r = m.groupValues[1]
            if (r.startsWith(".") || r.startsWith("$.")) r.replace("$.", "").removePrefix(".").replace(":.", ":") else "#REF!"
        }
        return "=$cuerpo"
    }

    private fun deOds(archivo: File, ahora: Long): List<HojaImportada> {
        val z = runCatching { entradas(archivo) }.getOrElse { throw NoSeLee("El archivo no es una hoja de cálculo válida") }
        val contenido = z["content.xml"] ?: throw NoSeLee("El archivo no es una hoja de cálculo válida")
        val salida = ArrayList<HojaImportada>()
        sax(contenido, object : DefaultHandler() {
            var nombre = ""
            var celdas = HashMap<String, String>()
            var guardados = HashMap<String, String>()
            var fila = 0
            var col = 0
            var repiteFila = 1
            val deLaFila = ArrayList<Pair<Int, String>>()
            var enCelda = false
            var repiteCol = 1
            var tipo: String? = null
            var valor: String? = null
            var fecha: String? = null
            var logico: String? = null
            var formula: String? = null
            val texto = StringBuilder()
            var parrafos = 0
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (local(q)) {
                    "table" -> { nombre = attr(a, "name") ?: "Hoja"; celdas = HashMap(); guardados = HashMap(); fila = 0 }
                    "table-row" -> { repiteFila = attr(a, "number-rows-repeated")?.toIntOrNull() ?: 1; col = 0; deLaFila.clear() }
                    "table-cell", "covered-table-cell" -> {
                        enCelda = true
                        repiteCol = attr(a, "number-columns-repeated")?.toIntOrNull() ?: 1
                        tipo = attr(a, "value-type"); valor = attr(a, "value"); fecha = attr(a, "date-value")
                        logico = attr(a, "boolean-value"); formula = attr(a, "formula")
                        texto.setLength(0); parrafos = 0
                    }
                    "p" -> if (enCelda) { if (parrafos++ > 0) texto.append('\n') }
                    "s" -> if (enCelda) repeat((attr(a, "c")?.toIntOrNull() ?: 1).coerceAtMost(100)) { texto.append(' ') }
                    "line-break" -> if (enCelda) texto.append('\n')
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (enCelda) texto.append(ch, start, length) }
            override fun endElement(u: String?, l: String?, q: String) {
                when (local(q)) {
                    "table-cell", "covered-table-cell" -> {
                        enCelda = false
                        val literal = when (tipo) {
                            "float", "currency" -> valor?.toDoubleOrNull()?.let { limpio(it) }
                            "percentage" -> valor?.toDoubleOrNull()?.let { CalculoFormato.general(it * 100) + "%" }
                            "date" -> fecha?.take(10)?.let { CalculoFormato.fecha(it) }?.let { CalculoFormato.textoDeFecha(it) }
                            "boolean" -> if (logico == "true") "VERDADERO" else "FALSO"
                            null -> null
                            else -> textoComoLiteral(texto.toString())
                        }
                        val escrita = formula?.let { formulaOdf(it) }
                        val final = escrita ?: literal
                        if (!final.isNullOrEmpty() && fila < Celdas.MAX_FILAS) {
                            for (k in 0 until repiteCol.coerceAtMost(1000)) {
                                val c = col + k
                                if (c >= Celdas.MAX_COLS) break
                                val dir = Celdas.nombre(fila, c)
                                celdas[dir] = final
                                if (escrita != null) guardados[dir] = literal.orEmpty()
                                deLaFila += c to final
                            }
                        }
                        col += repiteCol
                    }
                    "table-row" -> {
                        // Una fila repetida con algo dentro se copia; las vacías solo avanzan.
                        if (deLaFila.isNotEmpty()) for (k in 1 until repiteFila.coerceAtMost(1000)) {
                            if (fila + k >= Celdas.MAX_FILAS) break
                            for ((c, v) in deLaFila) celdas[Celdas.nombre(fila + k, c)] = v
                        }
                        fila += repiteFila
                    }
                    "table" -> if (celdas.isNotEmpty()) {
                        val comoValor = conValoresDondeNoCuadra(celdas, guardados)
                        salida += HojaImportada(nombre, TablaDeCalculo(nombre, celdas, tocado = ahora), comoValor)
                    }
                }
            }
        })
        return salida
    }

    // ---- CSV ----

    /** Un CSV: el separador es el que más sale en la primera línea (`;`, `,` o tabulador). */
    fun deCsv(texto: String, nombre: String, tabuladores: Boolean = false, ahora: Long = System.currentTimeMillis()): HojaImportada {
        val limpio = texto.removePrefix("﻿")
        val primera = limpio.lineSequence().firstOrNull().orEmpty()
        val separador = if (tabuladores) '\t' else listOf('\t', ';', ',').maxByOrNull { s -> primera.count { it == s } }
            ?.takeIf { s -> primera.contains(s) } ?: ','
        val celdas = HashMap<String, String>()
        for ((f, fila) in PortapapelesDeTabla.deSeparado(limpio, separador).withIndex()) {
            if (f >= Celdas.MAX_FILAS) break
            for ((c, v) in fila.withIndex()) {
                if (c >= Celdas.MAX_COLS) break
                if (v.isNotEmpty()) celdas[Celdas.nombre(f, c)] = v
            }
        }
        return HojaImportada(nombre, TablaDeCalculo(nombre, celdas, tocado = ahora))
    }
}
