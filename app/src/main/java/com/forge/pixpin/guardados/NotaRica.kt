package com.forge.pixpin.guardados

import com.forge.pixpin.motormd.Markdown
import com.forge.pixpin.motormd.MarkdownBlock
import com.forge.pixpin.motormd.SpanKind
import java.io.File

/**
 * **La nota rica del chat** (25-oct-2026, como el PC: `docs/nota-rica-del-chat.md`).
 *
 * Una `NOTA` cuyo `texto` es Markdown —el mismo que escribe el editor de notas— se pinta con
 * formato dentro de su burbuja, y sus fotos `![…](pixpin:files/guardados/pc/<chat>/notas/<ms>-….png)`
 * se ven **en su sitio**, nunca la ruta como texto. No hay campos nuevos: es la `NOTA` de siempre.
 *
 * Aquí solo va lo puro (se prueba en `NotaRicaTest`): partir el texto en trozos de Markdown,
 * álbumes y adjuntos, repartir el álbum en filas y resolver la ruta de una foto. Es
 * `pixpin_ui::nota_rica` del PC; el Markdown de los trozos de texto lo pinta el de siempre,
 * [com.forge.pixpin.motormd.MarkdownText]. Lo de Compose está en `NotaRicaUi.kt`.
 */
object NotaRica {

    /** Una `![alt](enlace)`. El `|300` que el editor pone detrás del alt (el ancho) no es del alt. */
    data class Imagen(val alt: String, val enlace: String)

    sealed interface Trozo {
        /** Markdown para [Markdown.parse], ya con los saltos de línea del chat. */
        data class Texto(val markdown: String) : Trozo
        /** Una o varias fotos seguidas: un álbum. */
        data class Album(val fotos: List<Imagen>) : Trozo
        /** Un fichero de la nota que no es una foto (un PDF, un audio): se nombra con 📎, no se pinta. */
        data class Adjunto(val imagen: Imagen) : Trozo
    }

    /** Si el enlace es una foto, por su extensión (`es_foto` del PC). */
    fun esFoto(enlace: String): Boolean {
        val sinResto = enlace.split('?', '#').first()
        val ext = sinResto.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff")
    }

    /** Una `![alt](enlace)` al principio de [s] y cuánto ocupa; null si no cierra (`imagen_en`). */
    internal fun imagenEn(s: String): Pair<Imagen, Int>? {
        if (!s.startsWith("![")) return null
        val s2 = s.substring(2)
        val cierre = s2.indexOf("](").takeIf { it >= 0 } ?: return null
        val alt = s2.substring(0, cierre)
        if ('\n' in alt) return null
        val tras = s2.substring(cierre + 2)
        val fin = cierreDelParentesis(tras) ?: return null
        var enlace = tras.substring(0, fin).trim()
        // Un título detrás de la ruta (`![a](ruta "titulo")`) no es la ruta.
        enlace = enlace.split(" \"").first().trim().trimStart('<').trimEnd('>')
        if (enlace.isEmpty()) return null
        return Imagen(alt.split('|').first().trim(), enlace) to (2 + cierre + 2 + fin + 1)
    }

    /**
     * **El `)` que cierra el `(` de una ruta o un enlace** (25-oct-2026, `cierre_del_parentesis`
     * del PC), en [tras] (lo de detrás del `(`): el que lo empareja, contando los que se abren
     * dentro, porque una ruta de Windows (`C:\Fotos (2)\a.png`) o una dirección de la Wikipedia
     * los llevan. Entre `<…>` (`![](<ruta con ( suelto>)`), el de después del `>`. Si no se
     * emparejan, el primero, como antes. Cortar en el primero dejaba la foto sin salir y un trozo
     * de ruta como texto.
     */
    fun cierreDelParentesis(tras: String): Int? {
        if (tras.trimStart().startsWith('<')) {
            val g = tras.indexOf('>')
            if (g >= 0) {
                val k = tras.indexOf(')', g)
                if (k >= 0) return k
            }
        }
        var nivel = 0
        for ((k, c) in tras.withIndex()) {
            when {
                c == '(' -> nivel++
                c == ')' && nivel == 0 -> return k
                c == ')' -> nivel--
                c == '\n' -> break
            }
        }
        return tras.indexOf(')').takeIf { it >= 0 }
    }

    /**
     * Dónde hay `` `código` `` en un renglón (con sus comillas, `tramos_de_codigo` del PC): de
     * tantas comillas como abren hasta otras tantas.
     */
    internal fun tramosDeCodigo(linea: String): List<IntRange> {
        val tramos = ArrayList<IntRange>()
        var i = 0
        while (true) {
            val inicio = linea.indexOf('`', i).takeIf { it >= 0 } ?: break
            var tiras = 0
            while (inicio + tiras < linea.length && linea[inicio + tiras] == '`') tiras++
            val valla = linea.substring(inicio, inicio + tiras)
            val j = linea.indexOf(valla, inicio + tiras)
            if (j >= 0) {
                tramos += inicio until j + tiras
                i = j + tiras
            } else i = inicio + tiras
        }
        return tramos
    }

    /**
     * Las imágenes de un renglón y el texto entre ellas, en orden (`partir_imagenes`). **Lo que va
     * dentro de un `` `código` `` es texto aunque lo parezca** (25-oct-2026, como el PC): quien
     * explica la sintaxis escribe `` `![](foto.png)` ``, y eso no es una foto.
     */
    internal fun partirImagenes(linea: String): List<Any> {
        val codigo = tramosDeCodigo(linea)
        val salida = ArrayList<Any>()
        // Dónde empieza el texto aún sin soltar, y desde dónde se busca.
        var desde = 0
        var buscar = 0
        while (true) {
            val i = linea.indexOf("![", buscar).takeIf { it >= 0 } ?: break
            val hallada = if (codigo.any { i in it }) null else imagenEn(linea.substring(i))
            if (hallada == null) {
                // Un `![` que no cierra, o de un código: texto, y se sigue buscando detrás.
                buscar = i + 2
                continue
            }
            if (i > desde) salida += linea.substring(desde, i)
            salida += hallada.first
            desde = i + hallada.second
            buscar = desde
        }
        if (desde < linea.length) salida += linea.substring(desde)
        return salida
    }

    /**
     * **El texto de una nota con sus fotos dichas como «🖼 alt»** (`sin_rutas_de_fotos` del PC):
     * en una línea (la lista de chats, lo citado, lo fijado) la ruta no le dice nada a nadie y se
     * comería todo el sitio. Lo demás, tal cual. La foto pegada en el PC lleva su número de alt
     * (`![foto 2](notas/…)`) y aquí sale «🖼 foto 2».
     */
    fun sinRutasDeFotos(texto: String): String {
        if ("![" !in texto) return texto
        val salida = StringBuilder(texto.length)
        var resto = texto
        while (true) {
            val i = resto.indexOf("![").takeIf { it >= 0 } ?: break
            salida.append(resto, 0, i)
            val tras = resto.substring(i + 2)
            val c = tras.indexOf("](").takeIf { it >= 0 && '\n' !in tras.substring(0, it) }
            val f = c?.let { cierreDelParentesis(tras.substring(it + 2)) }
            if (c != null && f != null) {
                val alt = tras.substring(0, c).split('|').first().trim()
                salida.append(if (alt.isEmpty()) "🖼" else "🖼 ").append(alt)
                resto = tras.substring(c + 2 + f + 1)
            } else {
                salida.append("![")
                resto = tras
            }
        }
        salida.append(resto)
        return salida.toString()
    }

    /**
     * **Los trozos de la nota.** Las fotos seguidas (en renglones seguidos o en el mismo) son un
     * álbum; una línea en blanco, un título, una lista o un texto entre ellas lo cierran. Dentro
     * de un bloque de código, una tabla, una fórmula o una caja no se toca nada.
     *
     * **Un salto de línea es un salto de línea** (es un chat, no un `.md`): entre dos renglones
     * de párrafo seguidos se mete una línea en blanco, porque [Markdown.parse] los juntaría en
     * uno con un espacio.
     */
    fun trozos(texto: String): List<Trozo> {
        val salida = ArrayList<Trozo>()
        val md = ArrayList<String>()
        val album = ArrayList<Imagen>()
        var ultimoDeParrafo = false
        fun cerrarTexto() {
            while (md.isNotEmpty() && md.last().isBlank()) md.removeAt(md.lastIndex)
            if (md.any { it.isNotBlank() }) salida += Trozo.Texto(md.joinToString("\n").trim('\n'))
            md.clear(); ultimoDeParrafo = false
        }
        fun cerrarAlbum() { if (album.isNotEmpty()) { salida += Trozo.Album(album.toList()); album.clear() } }
        fun linea(l: String, deParrafo: Boolean) {
            cerrarAlbum()
            if (deParrafo && ultimoDeParrafo) md += ""
            md += l
            ultimoDeParrafo = deParrafo
        }

        val renglones = texto.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < renglones.size) {
            val l = renglones[i].trimEnd()
            val t = l.trim()
            i++
            // Lo que va entre vallas, entero: dentro no hay fotos ni saltos que tocar.
            val valla = when {
                t.startsWith("```") -> "```"
                t.startsWith("~~~") -> "~~~"
                t == "$$" -> "$$"
                t.startsWith(":::") && t != ":::" -> ":::"
                else -> null
            }
            if (valla != null) {
                linea(l, false)
                while (i < renglones.size) {
                    val d = renglones[i]
                    md += d
                    i++
                    if (valla == ":::" && d.trim() == ":::") break
                    if (valla != ":::" && d.trim().startsWith(valla)) break
                }
                ultimoDeParrafo = false
                continue
            }
            if (t.isEmpty()) {
                cerrarAlbum()
                md += ""
                ultimoDeParrafo = false
                continue
            }
            if (t.startsWith("|") || t.startsWith(">") || t.startsWith("#") || t.startsWith("<")) {
                linea(l, false); continue
            }
            if ("![" in t) {
                val partes = partirImagenes(t)
                if (partes.any { it is Imagen }) {
                    for (p in partes) when {
                        p is Imagen && esFoto(p.enlace) -> { if (md.isNotEmpty()) cerrarTexto(); album += p }
                        p is Imagen -> { cerrarAlbum(); cerrarTexto(); salida += Trozo.Adjunto(p) }
                        p is String && p.isBlank() -> Unit
                        p is String -> linea(p.trim(), true)
                    }
                    continue
                }
            }
            val esListaOTitulo = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s""").containsMatchIn(l) || esRegla(t)
            linea(l, !esListaOTitulo)
        }
        cerrarAlbum()
        cerrarTexto()
        // Cada trozo se cierra antes de abrir el siguiente, así que salen en el orden en que se
        // escribieron; solo se juntan dos textos que hayan quedado seguidos.
        return juntar(salida)
    }

    private fun esRegla(t: String) = Regex("""^([-*_])\s*(\1\s*){2,}$""").matches(t)

    private fun juntar(l: List<Trozo>): List<Trozo> {
        val salida = ArrayList<Trozo>()
        for (t in l) {
            val antes = salida.lastOrNull()
            if (t is Trozo.Texto && antes is Trozo.Texto) salida[salida.lastIndex] = Trozo.Texto(antes.markdown + "\n\n" + t.markdown)
            else salida += t
        }
        return salida
    }

    /**
     * **Si la nota tiene algo que enseñar con formato** (`tiene_formato` del PC). Una sin nada se
     * pinta como siempre, renglón a renglón: así una nota llana no cambia ni un píxel. Un enlace
     * suelto (`https://…`) no cuenta: ya tiene su tarjeta encima.
     */
    fun tieneFormato(texto: String): Boolean {
        if (texto.isBlank()) return false
        val trozos = trozos(texto)
        if (trozos.any { it !is Trozo.Texto }) return true
        val bloques = trozos.flatMap { Markdown.parse((it as Trozo.Texto).markdown) }
        // **Un escape cambia lo que se ve** (25-oct-2026, como el PC: `5 \* 3` es «5 * 3»): si
        // los párrafos no dicen lo mismo que los renglones escritos, hay formato. Los blancos de
        // final de renglón y los `\r\n` no cuentan: la nota llana tiene que seguir llana.
        if (bloques.all { it is MarkdownBlock.Paragraph }) {
            val leido = bloques.joinToString("\n") { (it as MarkdownBlock.Paragraph).content.text.trim() }
            val escrito = texto.replace("\r\n", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (leido != escrito) return true
        }
        return bloques.any { b ->
            when (b) {
                is MarkdownBlock.Paragraph -> b.content.spans.any { s ->
                    s.kind != SpanKind.LINK || (s.url != null && s.url != b.content.text.substring(s.start, s.end) &&
                        s.url.removePrefix("https://").removePrefix("http://") != b.content.text.substring(s.start, s.end))
                }
                else -> true
            }
        }
    }

    /**
     * **El fichero de una foto de la nota en este aparato.** Lo recibido ya llega con la ruta de
     * aquí (`Rutas.aLocal`), pero la portátil (`pixpin:files/…`) también vale, y `file://`. Una
     * relativa (`notas/…`) no debería llegar nunca —el PC la pasa a portátil al enviar—: null,
     * y se pinta el recuadro de la imagen. Tampoco se sale de `files` con un `..`.
     */
    fun archivo(filesDir: File, enlace: String): File? {
        val e = enlace.trim()
        // **Nunca una ruta de red ni una que se salga con `..`** (25-oct-2026, como el PC: allí,
        // solo con pintar una nota llegada de otro aparato, Windows llamaría a ese equipo con las
        // credenciales del usuario). Aquí no hay red de Windows, pero la regla es la misma en los
        // dos lados: la nota que se pinta no decide adónde se mira.
        if (e.startsWith("\\\\") || e.startsWith("//") || e.split('/', '\\').any { it == ".." }) return null
        val f = when {
            e.startsWith(PORTATIL) -> {
                val rel = e.removePrefix(PORTATIL)
                if (rel.isEmpty()) return null
                File(filesDir, rel)
            }
            e.startsWith("file://") -> File(e.removePrefix("file://"))
            e.startsWith("/") -> File(e)
            else -> return null
        }
        if (f.isFile) return f
        // Con espacios escritos `%20`, como los pone algún editor de Markdown.
        val decodificado = runCatching { java.net.URLDecoder.decode(f.path.replace("+", "%2B"), "UTF-8") }.getOrNull()
        return if (decodificado != null && decodificado != f.path) File(decodificado) else f
    }

    private const val PORTATIL = "pixpin:files/"

    /**
     * Cuántas fotos van en cada fila de un álbum de [n]: de tres en tres, sin que ninguna fila se
     * quede con una sola (salvo la primera de tres, la grande, como en Telegram). `filas_del_album`.
     */
    fun filasDelAlbum(n: Int): List<Int> = when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(1)
        n == 2 -> listOf(2)
        n == 3 -> listOf(1, 2)
        n == 4 -> listOf(2, 2)
        else -> {
            val filas = MutableList(n / 3) { 3 }
            when (n % 3) {
                1 -> { filas.removeAt(filas.lastIndex); filas += listOf(2, 2) }
                2 -> filas += 2
            }
            filas
        }
    }

    data class Caja(val x: Float, val y: Float, val ancho: Float, val alto: Float)

    /**
     * **El álbum** (`album` del PC): dónde va cada foto dadas sus proporciones (ancho/alto; 4:3
     * si aún no se sabe), el ancho que hay, el alto como mucho de una foto sola y de cada fila, y
     * el hueco. Una sola guarda su proporción; en una fila de varias todas toman el alto que las
     * hace llenar el ancho, y si se pasa de los topes se recortan por el centro al pintarlas.
     */
    fun album(proporciones: List<Float>, ancho: Float, altoSolo: Float, altoFila: Float, hueco: Float): Pair<List<Caja>, Float> {
        fun prop(r: Float) = if (r.isFinite() && r > 0f) r else 4f / 3f
        val a = ancho.coerceAtLeast(1f)
        if (proporciones.isEmpty()) return emptyList<Caja>() to 0f
        if (proporciones.size == 1) {
            val r = prop(proporciones[0])
            val w = minOf(a, altoSolo * r).coerceAtLeast(1f)
            val h = (w / r).coerceAtLeast(1f)
            return listOf(Caja(0f, 0f, w, h)) to h
        }
        val cajas = ArrayList<Caja>(proporciones.size)
        var y = 0f
        var k = 0
        filasDelAlbum(proporciones.size).forEachIndexed { f, cuantas ->
            if (f > 0) y += hueco
            val fila = proporciones.subList(k, k + cuantas)
            val libre = (a - hueco * (cuantas - 1)).coerceAtLeast(1f)
            val suma = fila.sumOf { prop(it).toDouble() }.toFloat()
            val h = (libre / suma).coerceIn(a / 6f, maxOf(a / 6f, altoFila)).coerceAtLeast(1f)
            var x = 0f
            fila.forEachIndexed { j, r ->
                val w = if (j + 1 == cuantas) (a - x).coerceAtLeast(1f) else (libre * prop(r) / suma).coerceAtLeast(1f)
                cajas += Caja(x, y, w, h)
                x += w + hueco
            }
            y += h
            k += cuantas
        }
        return cajas to y
    }
}
