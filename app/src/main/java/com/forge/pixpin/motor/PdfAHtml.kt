package com.forge.pixpin.motor

/**
 * **Un PDF pasado a texto, para leerlo como un Word** (23-sep-2026, pedido por el usuario: «el
 * PDF lo puedes transformar como texto… lo extraes, y también tengo la opción de mostrarlo como
 * un PDF normal»).
 *
 * El texto sale de [PlanoDePdf], que ya lee el PDF por dentro para los planos: da cada trozo con
 * su sitio y su tamaño. Aquí se juntan en **líneas** (lo que está a la misma altura), las líneas
 * en **párrafos** (por el salto entre ellas, el cambio de tamaño, la sangría y el punto final),
 * se deshacen los guiones de fin de línea, lo más grande se marca como título y se quitan los
 * números de página sueltos. El resultado es una página como la de [DocxAHtml], así que el
 * lector de siempre la trata igual: letra, voz, página que sube sola, espacios, anotar, exportar.
 *
 * Límites, dichos: un **escaneo** no tiene texto (es una foto; el texto invisible de un OCR
 * [PlanoDePdf] lo salta), y un texto a **dos columnas** sale con las líneas de las dos
 * intercaladas. Para eso sigue estando el PDF de hojas.
 */
object PdfAHtml {

    class NoSeLee(mensaje: String) : Exception(mensaje)

    /** Un trozo de texto en el papel: [x]…[fin] a lo ancho, [y] la línea base, [alto] el tamaño de la letra. */
    data class Trozo(
        val x: Double, val y: Double, val fin: Double, val alto: Double, val texto: String,
        val negrita: Boolean = false, val cursiva: Boolean = false
    )

    data class Linea(val y: Double, val x0: Double, val x1: Double, val alto: Double, val texto: String, val negrita: Boolean)

    /** Un párrafo ya hecho: [nivel] 0 es texto; 1, 2 o 3, título de ese nivel. */
    data class Parrafo(val texto: String, val nivel: Int, val pagina: Int)

    /** Lo que da [PlanoDePdf] pasado a trozos: solo lo que va derecho (un rótulo girado no es el texto corrido). */
    fun trozosDe(textos: List<PlanoDePdf.Texto>): List<Trozo> = textos.mapNotNull { t ->
        val ancho = Math.hypot(t.a, t.b)
        val alto = Math.hypot(t.c, t.d)
        if (alto < 0.5 || t.texto.isEmpty()) return@mapNotNull null
        // Derecho y sin espejo: el texto corrido va de izquierda a derecha.
        if (t.a <= 0 || Math.abs(t.b) > 0.2 * ancho) return@mapNotNull null
        Trozo(t.x, t.y, t.x + t.ancho * ancho, alto, t.texto, t.negrita, t.cursiva)
    }

    /** **Las líneas**: lo que está a la misma altura, de izquierda a derecha, con espacios donde hay hueco. */
    fun lineas(trozos: List<Trozo>): List<Linea> {
        val ordenados = trozos.sortedWith(compareBy<Trozo> { it.y }.thenBy { it.x })
        val grupos = ArrayList<MutableList<Trozo>>()
        for (t in ordenados) {
            val ultimo = grupos.lastOrNull()
            val yDe = ultimo?.let { g -> g.sumOf { it.y } / g.size }
            val altoDe = ultimo?.maxOf { it.alto } ?: 0.0
            if (ultimo != null && Math.abs(t.y - yDe!!) <= 0.45 * maxOf(t.alto, altoDe)) ultimo += t
            else grupos += mutableListOf(t)
        }
        return grupos.map { g ->
            val enOrden = g.sortedBy { it.x }
            val sb = StringBuilder()
            var antes: Trozo? = null
            for (t in enOrden) {
                val a = antes
                if (a != null) {
                    val hueco = t.x - a.fin
                    val yaHay = sb.endsWith(" ") || t.texto.startsWith(" ")
                    if (!yaHay && (hueco > 0.12 * t.alto || hueco < -0.5 * t.alto)) sb.append(' ')
                }
                sb.append(t.texto)
                antes = t
            }
            val alto = enOrden.groupingBy { it.alto }.eachCount().maxByOrNull { it.value }!!.key
            Linea(
                y = g.sumOf { it.y } / g.size, x0 = enOrden.first().x, x1 = enOrden.maxOf { it.fin }, alto = alto,
                texto = sb.toString().replace(Regex("\\s+"), " ").trim(),
                negrita = enOrden.all { it.negrita }
            )
        }.filter { it.texto.isNotEmpty() }
    }

    /** El tamaño de la letra del cuerpo: el que más letras lleva. */
    fun altoDelCuerpo(lineas: List<Linea>): Double =
        lineas.groupBy { Math.round(it.alto * 2) / 2.0 }.maxByOrNull { (_, l) -> l.sumOf { it.texto.length } }?.key ?: 10.0

    /**
     * **Los párrafos de una página.** Se corta cuando la línea siguiente está más lejos de lo
     * normal, cambia de tamaño de letra, entra con sangría, o la anterior acaba corta y en punto.
     */
    fun parrafos(lineas: List<Linea>, cuerpo: Double, pagina: Int): List<Parrafo> {
        if (lineas.isEmpty()) return emptyList()
        // El interlineado normal: la mediana de los saltos entre líneas del tamaño del cuerpo.
        val saltos = lineas.zipWithNext().mapNotNull { (a, b) ->
            (b.y - a.y).takeIf { it > 0 && Math.abs(a.alto - cuerpo) < cuerpo * 0.15 && Math.abs(b.alto - cuerpo) < cuerpo * 0.15 && it < cuerpo * 3 }
        }.sorted()
        val normal = saltos.getOrNull(saltos.size / 2) ?: (cuerpo * 1.3)
        val izquierda = lineas.filter { Math.abs(it.alto - cuerpo) < cuerpo * 0.15 }.map { it.x0 }.sorted().let { it.getOrNull(it.size / 4) } ?: lineas.minOf { it.x0 }
        val derecha = lineas.maxOf { it.x1 }

        val sale = ArrayList<Parrafo>()
        var actual = StringBuilder()
        var primera: Linea? = null
        var anterior: Linea? = null
        fun cerrar() {
            val p = primera ?: return
            val texto = actual.toString().trim()
            if (texto.isNotEmpty()) sale += Parrafo(texto, nivelDe(p, texto, cuerpo), pagina)
            actual = StringBuilder(); primera = null
        }
        for (l in lineas) {
            val a = anterior
            val corta = a != null && a.x1 < izquierda + (derecha - izquierda) * 0.75 && a.texto.trimEnd().lastOrNull() in FINALES
            val nuevo = a == null ||
                l.y - a.y > normal * 1.45 ||
                Math.abs(l.alto - a.alto) > cuerpo * 0.2 ||
                (l.x0 - izquierda > l.alto * 1.2 && a.x0 - izquierda < l.alto * 0.5) ||
                corta
            if (nuevo) cerrar()
            if (primera == null) { primera = l; actual.append(l.texto) }
            else {
                // Un guion al final de la línea y minúscula al empezar la siguiente: palabra cortada.
                val t = actual.toString()
                if (t.endsWith("-") && l.texto.firstOrNull()?.isLowerCase() == true) {
                    actual.setLength(actual.length - 1); actual.append(l.texto)
                } else actual.append(' ').append(l.texto)
            }
            anterior = l
        }
        cerrar()
        // Fuera el número de página: un párrafo suelto de solo números al principio o al final.
        return sale.filterIndexed { i, p -> !((i == 0 || i == sale.lastIndex) && p.texto.length <= 12 && NUMERO_DE_PAGINA.matches(p.texto)) }
    }

    private val FINALES = setOf('.', ':', '!', '?', '…', '»', '"', '”')
    private val NUMERO_DE_PAGINA = Regex("^[\\p{Punct}\\s–—]*(\\d{1,4}|[ivxlcdmIVXLCDM]{1,6})([\\s/–—-]*(de|of)?\\s*\\d{0,4})[\\p{Punct}\\s–—]*$")

    private fun nivelDe(primera: Linea, texto: String, cuerpo: Double): Int = when {
        primera.alto >= cuerpo * 1.6 && texto.length < 200 -> 1
        primera.alto >= cuerpo * 1.25 && texto.length < 250 -> 2
        primera.negrita && texto.length < 120 && texto.lastOrNull() !in FINALES -> 3
        else -> 0
    }

    /** **La página entera**, de los párrafos de todas las hojas. [idioma] va en `lang`, para las voces. */
    fun html(titulo: String, parrafos: List<Parrafo>, idioma: String): String {
        val sb = StringBuilder()
        var pagina = -1
        for (p in parrafos) {
            // Cada hoja empieza con su marca: para saber por dónde se va, y para volver al PDF en esa hoja.
            if (p.pagina != pagina) {
                pagina = p.pagina
                sb.append("<p class=\"hoja-pdf\" id=\"hoja-").append(pagina + 1).append("\" aria-hidden=\"true\">Hoja ")
                    .append(pagina + 1).append("</p>\n")
            }
            val etiqueta = if (p.nivel == 0) "p" else "h${p.nivel}"
            sb.append('<').append(etiqueta).append(" data-hoja=\"").append(pagina + 1).append("\">")
                .append(escapar(p.texto)).append("</").append(etiqueta).append(">\n")
        }
        if (parrafos.isEmpty()) sb.append("<p class=\"nota\">Este PDF no tiene texto que se pueda sacar: seguramente es un escaneo (una foto de las hojas).</p>")
        return "<!DOCTYPE html>\n<html lang=\"" + escapar(idioma) + "\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>" + escapar(titulo) + "</title><style>" + DocxAHtml.ESTILO +
            ".hoja-pdf{margin:28px 0 8px;color:#888;font-size:.75em;letter-spacing:.08em;text-transform:uppercase;border-top:1px solid #ddd;padding-top:6px}" +
            "</style></head><body><main>\n" + sb + "</main></body></html>\n"
    }

    /**
     * **Del archivo a la página.** [idiomaDelAparato] se usa si el texto no deja claro el idioma.
     * Lanza [NoSeLee] si el archivo no se entiende o está cifrado.
     */
    fun convertir(bytes: ByteArray, titulo: String, idiomaDelAparato: String = "es"): String {
        val archivo = leerPdf(bytes) ?: throw NoSeLee("No se pudo leer el PDF")
        if (archivo.cifrado) throw NoSeLee("El PDF está protegido con contraseña")
        val porHoja = archivo.paginas().indices.map { i ->
            lineas(trozosDe(PlanoDePdf.de(archivo, i)?.textos.orEmpty()))
        }
        val cuerpo = altoDelCuerpo(porHoja.flatten())
        val parrafos = porHoja.flatMapIndexed { i, l -> parrafos(l, cuerpo, i) }
        val muestra = parrafos.asSequence().filter { it.nivel == 0 }.take(60).joinToString(" ") { it.texto }.take(5000)
        val idioma = VozAlta.idiomaDelTexto(muestra, idiomaDelAparato.substringBefore('-').ifBlank { "es" })
        return html(titulo, parrafos, idioma)
    }

    /**
     * **Cuántas palabras tiene el PDF**, para la página que sube sola ([AutoDesplazar]). Un escaneo
     * no tiene texto: entonces se cuentan [POR_HOJA_SI_NO_HAY] por hoja, lo de una página corriente.
     */
    fun contarPalabras(bytes: ByteArray): Int {
        val archivo = leerPdf(bytes) ?: return 0
        if (archivo.cifrado) return 0
        val hojas = archivo.paginas().size
        val cuenta = (0 until hojas).sumOf { i ->
            lineas(trozosDe(PlanoDePdf.de(archivo, i)?.textos.orEmpty())).sumOf { l -> l.texto.split(' ').count { it.isNotBlank() } }
        }
        return if (cuenta > 0) cuenta else hojas * POR_HOJA_SI_NO_HAY
    }

    const val POR_HOJA_SI_NO_HAY = 250

    fun esPdf(nombre: String?): Boolean = nombre?.lowercase()?.endsWith(".pdf") == true

    private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
