package com.forge.pixpin.guardados

import com.forge.pixpin.guardados.EstudiarClase.Marcador
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * **Una grabación para sacarla fuera** (25-oct-2026, `pixpin-proyecto::clase_exportar` del PC, 270b812,
 * dcca06b y cd491a6; el usuario allí: «en las grabaciones, que el menú de compartir sea el de toda la
 * app, con los mismos formatos; sobre todo el HTML, que se vea claro e intuitivo»).
 *
 * Una clase o una reunión grabada es una nota de voz (`VOZ`, con su `transcripcion` en renglones
 * `[m:ss] texto`) y, si se grabó como clase, lo enlazado: las capturas ([Mensaje.capturas]), el vídeo
 * ([Mensaje.video]), los marcadores ([Mensaje.marcadores]) y las banderitas ([Mensaje.marcas]). Las
 * capturas y el vídeo llevan a su vez `enGrabacion: {de, ms}`.
 *
 * Aquí está lo puro de sacarla, **letra por letra lo del PC** (las mismas páginas salen de los dos
 * aparatos): [Datos], lo que hace falta de una grabación con sus ficheros por ruta ([deLaNota]), y lo
 * que sale de ella:
 *
 * - [html]: una página sola, sin nada de la red, con el reproductor, la barra del tiempo con los
 *   marcadores y las capturas, la transcripción que salta al tocar su minuto y se resalta mientras
 *   suena, las capturas en su minuto (con lupa), claro y oscuro según el sistema, para el móvil y
 *   para imprimir. El CSS y el guion son los del PC (`resources/clase_exportar/`). **Sin marcadores
 *   ni capturas, el texto a lo ancho** (cd491a6): una nota de voz sin más no deja una columna vacía.
 * - [markdown]: el mismo orden en Markdown, con las capturas enlazadas (de ahí salen el Word y el PDF);
 *   [paqueteMarkdown], en un ZIP con la carpeta de las capturas al lado (dcca06b).
 * - [texto]: la transcripción con sus minutos.
 *
 * Nada de esto toca el disco: los ficheros los lee quien llama ([ExportarGrabacion]), que dice con
 * [Medio] si van dentro de la página o al lado. Lo prueba `ClaseExportarTest` con las pruebas del PC.
 */
object ClaseExportar {

    /** **Una grabación lista para sacarla**, con sus ficheros por ruta (`ClaseParaExportar` del PC). */
    data class Datos(
        val titulo: String = "",
        /** La fecha ya escrita («25/10/2026 18:05»). */
        val fecha: String = "",
        val duracionMs: Long = 0,
        val audio: String? = null,
        val video: String? = null,
        /** `(ms, ruta)`, en orden. */
        val capturas: List<Pair<Long, String>> = emptyList(),
        /** Los trozos de la transcripción: su minuto (si lo lleva) y lo que dice, ya sin marcas. */
        val lineas: List<Pair<Long?, String>> = emptyList(),
        /** Los marcadores y las banderitas (sin nota), en orden. */
        val marcadores: List<Marcador> = emptyList(),
        /** Lo demás que cuelga de la grabación (un PDF adjunto, una foto que no es captura). */
        val adjuntos: List<String> = emptyList(),
        /**
         * **Lo importante que halló Jev** en la transcripción (28-oct-2026, PC `a6b248fe`; ver
         * [Importantes]): chapitas en cada renglón y un filtro arriba. Vacío, la página es la de siempre.
         */
        val importantes: List<Importantes.Importante> = emptyList(),
        /**
         * **Los temas de la reunión** (29-oct-2026, PC `clase_exportar.rs`; ver [TemasDeLaReunion]), en el
         * orden de la agenda: lo tratado con su minuto y lo que quedó sin tratar. Vacío, la página es la de
         * siempre.
         */
        val temas: List<TemasDeLaReunion.Tema> = emptyList(),
        /** **Lo que Jev sacó mientras se grababa** ([ActaViva]): los minutos de cada tema, las decisiones y las preguntas sin respuesta. */
        val acta: ActaViva.Acta = ActaViva.Acta()
    ) {
        /** La duración: la apuntada o, sin ella, la última señal. */
        fun duracion(): Long {
            if (duracionMs > 0) return duracionMs
            return (lineas.mapNotNull { it.first } + capturas.map { it.first } + marcadores.map { it.ms }).maxOrNull() ?: 0
        }

        /** Todos los ficheros de la grabación (para «Originales»), sin repetir. */
        fun ficheros(): List<String> =
            (listOfNotNull(audio, video) + capturas.map { it.second } + adjuntos).distinct()
    }

    /** Si un mensaje es una grabación: una nota de voz. */
    fun esGrabacion(m: Mensaje): Boolean = m.clase == Clase.VOZ

    /** Si la nota de voz se grabó como clase (con capturas o vídeo). */
    fun esDeClase(m: Mensaje): Boolean = m.grabacion == GrabarClase.CLASE

    /** `enGrabacion: {de, ms}` de un mensaje: de qué nota cuelga y en qué minuto. */
    fun enGrabacion(m: Mensaje): Pair<String, Long>? {
        val en = m.enGrabacion ?: return null
        val de = (en["de"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val ms = (en["ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0
        return de to ms.coerceAtLeast(0)
    }

    private fun extension(ruta: String): String =
        nombre(ruta).substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun esVideo(ruta: String) = extension(ruta) in setOf("mp4", "m4v", "webm", "mov", "mkv")

    /** El nombre que se enseña de un fichero. */
    fun nombre(ruta: String): String = ruta.substringAfterLast('/').substringAfterLast('\\')

    /**
     * **De la nota de voz y lo enlazado en ella** (`de_la_nota` del PC). [mensajes] es el chat entero
     * (para encontrar las capturas y el vídeo por su id); [ruta] da el fichero de un mensaje si está en
     * este aparato; [lineas] son los trozos de la transcripción ya partidos.
     */
    fun deLaNota(
        voz: Mensaje,
        mensajes: List<Mensaje>,
        lineas: List<Pair<Long?, String>>,
        fecha: String,
        ruta: (Mensaje) -> String?
    ): Datos {
        val porId = mensajes.associateBy { it.id }
        val usados = HashSet<String>()
        val capturas = ArrayList<Pair<Long, String>>()
        for (c in voz.capturas.orEmpty()) {
            val o = c as? JsonObject ?: continue
            val ms = (o["ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: continue
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
            val m = porId[id] ?: continue
            usados += m.id
            ruta(m)?.let { capturas += ms.coerceAtLeast(0) to it }
        }
        var video = GrabarClase.videoDe(voz)?.let { porId[it] }?.let { m -> usados += m.id; ruta(m) }
        // Lo que cuelga de la nota sin estar en sus listas (lo que llega por la carpeta de la
        // grabación): una imagen es una captura de su minuto; un vídeo, el vídeo; lo demás, un adjunto.
        val adjuntos = ArrayList<String>()
        for (m in mensajes) {
            if (m.id == voz.id || m.id in usados) continue
            val (de, ms) = enGrabacion(m) ?: continue
            if (de != voz.id) continue
            val r = ruta(m) ?: continue
            when {
                m.clase == Clase.IMAGEN -> capturas += ms to r
                video == null && esVideo(r) -> video = r
                else -> adjuntos += r
            }
        }
        val marcadores = EstudiarClase.marcadoresDe(voz.marcadores).toMutableList()
        for (ms in voz.marcas.map { it.toLong() }) {
            if (marcadores.none { it.ms == ms }) marcadores += Marcador(ms.coerceAtLeast(0), "")
        }
        val titulo = voz.nombre.trim().let { t ->
            val punto = t.lastIndexOf('.')
            if (punto > 0 && t.length - punto - 1 <= 4) t.substring(0, punto) else t
        }
        return Datos(
            titulo = titulo.ifEmpty { voz.texto.lineSequence().firstOrNull().orEmpty().trim() },
            fecha = fecha,
            duracionMs = voz.duracionMs.toLong().coerceAtLeast(0),
            audio = ruta(voz),
            video = video,
            capturas = capturas.sortedBy { it.first },
            lineas = lineas,
            marcadores = marcadores.sortedBy { it.ms },
            adjuntos = adjuntos,
            importantes = Importantes.de(voz).orEmpty(),
            temas = TemasDeLaReunion.temasDe(voz),
            acta = ActaViva.de(voz)
        )
    }

    // --- El orden de lo que se lee ------------------------------------------------------------

    /** Una fila de lo que se lee: una línea, una captura o un marcador, por su sitio en su lista. */
    sealed interface Fila {
        data class Linea(val i: Int) : Fila
        data class Captura(val i: Int) : Fila
        data class DeMarcador(val i: Int) : Fila
    }

    /**
     * **La transcripción con las capturas y los marcadores en su minuto.** Las líneas conservan su
     * orden; lo demás va delante de la primera línea con minuto posterior: una captura de las 12:05 va
     * detrás de la línea que empezó a las 12:00, que es la que se estaba diciendo. En el mismo minuto,
     * el marcador antes que la captura.
     */
    fun filas(c: Datos): List<Fila> {
        val senales = (c.marcadores.mapIndexed { i, m -> Triple(m.ms, 0, Fila.DeMarcador(i) as Fila) } +
            c.capturas.mapIndexed { i, (ms, _) -> Triple(ms, 1, Fila.Captura(i) as Fila) })
            .sortedWith(compareBy({ it.first }, { it.second }))
        val v = ArrayList<Fila>(c.lineas.size + senales.size)
        var s = 0
        c.lineas.forEachIndexed { i, (t, _) ->
            if (t != null) while (s < senales.size && senales[s].first < t) v += senales[s++].third
            v += Fila.Linea(i)
        }
        while (s < senales.size) v += senales[s++].third
        return v
    }

    /** El minuto como se lee: `12:34` o `1:02:03`. */
    fun minuto(ms: Long): String = GrabarClase.minuto(ms)

    /** El tipo MIME de un fichero de la grabación, por su extensión. */
    fun mimeDe(ruta: String): String = when (extension(ruta)) {
        "m4a", "mp4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "weba" -> "audio/webm"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    // --- Los rótulos ----------------------------------------------------------------------------

    /** Lo que la página dice (los del PC en español, `grabacion-*`). */
    data class Rotulos(
        val idioma: String = "es",
        val transcripcion: String = "Transcripción",
        val capturas: String = "Capturas",
        val captura: String = "Captura",
        val marcadores: String = "Marcadores",
        val marcador: String = "Marcador",
        val adjuntos: String = "Adjuntos",
        val duracion: String = "Duración",
        val sinTexto: String = "Esta grabación aún no tiene transcripción.",
        val buscar: String = "Buscar en la transcripción",
        val seguir: String = "Seguir el audio",
        val reproducir: String = "Reproducir o pausar",
        val velocidad: String = "Velocidad",
        val irAlMinuto: String = "Ir a este minuto",
        val cerrar: String = "Cerrar",
        val audio: String = "Audio",
        val video: String = "Vídeo",
        /** El filtro de lo importante: «Todo» (cada tipo dice lo suyo, [Importantes.Tipo.rotulo]). */
        val importanteTodo: String = "Todo",
        /** «Temas de la reunión» y la chapita de lo que quedó sin tratar; «Decisiones» y «Preguntas sin respuesta» (29-oct-2026). */
        val temas: String = "Temas de la reunión",
        val sinTratar: String = "Sin tratar",
        val decisiones: String = "Decisiones",
        val preguntasAbiertas: String = "Preguntas sin respuesta"
    )

    /**
     * Las chapitas de lo importante de un renglón ([ms]), y sus tipos para el filtro (`data-tipos`).
     * Sin nada, cadenas vacías (`chapitas_de` del PC).
     */
    private fun chapitasDe(c: Datos, ms: Long): Pair<String, String> {
        val tipos = Importantes.tiposEn(c.importantes, ms)
        if (tipos.isEmpty()) return "" to ""
        val chapas = tipos.joinToString("") { "<span class=\"chip ${it.id}\">${escapar(it.rotulo)}</span>" }
        return " data-tipos=\"${tipos.joinToString(" ") { it.id }}\"" to "<span class=\"chips\">$chapas</span>"
    }

    // --- Escapar --------------------------------------------------------------------------------

    /** Texto para HTML, dentro de una etiqueta o de un atributo entre comillas. */
    fun escapar(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /**
     * **Una ruta relativa como URL**: todo lo que no es letra, cifra, `-._~` o `/` va en `%XX`. Así un
     * nombre con espacios o acentos se abre, y nada que venga de un nombre de fichero puede volverse
     * `http:` (los dos puntos van escapados) ni salirse de la carpeta (`..` se quita).
     */
    fun urlRelativa(ruta: String): String =
        ruta.split('/', '\\').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/") { trozo ->
            buildString {
                for (b in trozo.toByteArray(Charsets.UTF_8)) {
                    val c = b.toInt() and 0xff
                    val ch = c.toChar()
                    if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~")) append(ch)
                    else append('%').append(String.format(Locale.ROOT, "%02X", c))
                }
            }
        }

    // --- HTML -----------------------------------------------------------------------------------

    /** Cómo va un fichero en la página: dentro (base64) o al lado, por su ruta relativa a la página. */
    sealed interface Medio {
        class Incrustado(val mime: String, val bytes: ByteArray) : Medio
        data class AlLado(val ruta: String) : Medio
    }

    private fun src(m: Medio): String = when (m) {
        is Medio.Incrustado -> "data:${escapar(m.mime)};base64,${java.util.Base64.getEncoder().encodeToString(m.bytes)}"
        is Medio.AlLado -> urlRelativa(m.ruta)
    }

    private fun recurso(nombre: String): String =
        ClaseExportar::class.java.getResourceAsStream("/clase_exportar/$nombre")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private val CSS: String by lazy { recurso("pagina.css") }
    private val JS: String by lazy { recurso("pagina.js") }

    // Los saltos se quedan: la página los enseña (`pre-line`) y la búsqueda no los pierde.
    private fun textoConSaltos(s: String) = escapar(s.trim())

    /** Un minuto que salta: un enlace con `data-ms` o, sin nada que reproducir, solo el minuto. */
    private fun minutoHtml(ms: Long, conMedio: Boolean): String {
        val m = minuto(ms)
        return if (conMedio) "<a class=\"t\" href=\"#t-$ms\" data-ms=\"$ms\">$m</a>" else "<span class=\"t\">$m</span>"
    }

    private fun pc(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    /** **La página de la grabación**, entera en un fichero. [medio] dice cómo va cada fichero; null, no va. */
    fun html(c: Datos, r: Rotulos, medio: (String) -> Medio?): String {
        val audio = c.audio?.let { a -> medio(a)?.let { a to it } }
        val video = c.video?.let { v -> medio(v)?.let { v to it } }
        val conMedio = audio != null || video != null
        val duracion = c.duracion()
        val capturas = c.capturas.map { (_, ruta) -> medio(ruta)?.let(::src) }
        val nCapturas = capturas.count { it != null }
        val h = StringBuilder(64 * 1024)
        h.append("<!doctype html>\n")
        h.append("<html lang=\"${escapar(r.idioma)}\">\n<head>\n<meta charset=\"utf-8\">\n")
        h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        h.append("<meta name=\"color-scheme\" content=\"light dark\">\n")
        h.append("<meta name=\"generator\" content=\"PixPin\">\n<title>${escapar(c.titulo)}</title>\n<style>\n$CSS</style>\n</head>\n")
        h.append("<body data-duracion=\"$duracion\">\n<header class=\"cab\">\n<h1>${escapar(c.titulo)}</h1>\n<p class=\"meta\">")
        val meta = ArrayList<String>()
        if (c.fecha.isNotBlank()) meta += "<span>${escapar(c.fecha)}</span>"
        if (duracion > 0) meta += "<span>${escapar(r.duracion)} ${minuto(duracion)}</span>"
        if (nCapturas > 0) meta += "<span>$nCapturas ${escapar(r.capturas.lowercase())}</span>"
        if (c.marcadores.isNotEmpty()) meta += "<span>${c.marcadores.size} ${escapar(r.marcadores.lowercase())}</span>"
        h.append(meta.joinToString("<span class=\"punto\" aria-hidden=\"true\">·</span>"))
        h.append("</p>\n</header>\n")

        // El reproductor: el vídeo arriba (si lo hay) y la barra que se queda pegada al subir.
        video?.let { (_, v) ->
            h.append("<section class=\"pantalla\"><video id=\"video\" src=\"${src(v)}\" preload=\"metadata\" playsinline")
            h.append(if (audio != null) " muted" else " controls")
            h.append(if (audio != null) "" else " data-maestro")
            h.append("></video></section>\n")
        }
        h.append("<section class=\"barra-fija\" id=\"barra-fija\">\n")
        audio?.let { (_, a) -> h.append("<audio id=\"audio\" src=\"${src(a)}\" preload=\"metadata\" controls data-maestro></audio>\n") }
        if (conMedio) {
            val reproducir = escapar(r.reproducir)
            h.append("<div class=\"mandos\" hidden>")
            h.append("<button type=\"button\" id=\"play\" class=\"play\" aria-label=\"$reproducir\" title=\"$reproducir\"><span class=\"i-play\" aria-hidden=\"true\"></span></button>")
            h.append("<span class=\"reloj\"><span id=\"ahora\">0:00</span> / <span id=\"total\">${minuto(duracion)}</span></span>")
            h.append("<div class=\"linea-tiempo\" id=\"linea-tiempo\" role=\"slider\" tabindex=\"0\" aria-label=\"${escapar(r.duracion)}\" aria-valuemin=\"0\" aria-valuemax=\"${duracion / 1000}\" aria-valuenow=\"0\">")
            h.append("<div class=\"riel\"><div class=\"hecho\" id=\"hecho\"></div></div>")
            fun por(ms: Long) = if (duracion > 0) (ms.toDouble() / duracion * 100.0).coerceIn(0.0, 100.0) else 0.0
            c.capturas.forEachIndexed { i, (ms, _) ->
                if (capturas[i] == null) return@forEachIndexed
                val n = i + 1
                h.append("<a class=\"senal captura\" href=\"#captura-$n\" data-ms=\"$ms\" style=\"left:${pc(por(ms))}%\" title=\"${escapar(r.captura)} $n · ${minuto(ms)}\"></a>")
            }
            for (m in c.marcadores) {
                val titulo = if (m.texto.isBlank()) "${r.marcador} · ${minuto(m.ms)}" else "${minuto(m.ms)} · ${m.texto.trim()}"
                h.append("<a class=\"senal marcador\" href=\"#t-${m.ms}\" data-ms=\"${m.ms}\" style=\"left:${pc(por(m.ms))}%\" title=\"${escapar(titulo)}\"></a>")
            }
            h.append("<div class=\"globo\" id=\"globo\" hidden></div></div>")
            h.append("<button type=\"button\" id=\"velocidad\" class=\"velocidad\" title=\"${escapar(r.velocidad)}\">1×</button>")
            h.append("</div>\n")
        }
        // El filtro de lo importante (Jev): «Todo» y un botón por tipo hallado.
        if (c.importantes.isNotEmpty() && c.lineas.isNotEmpty()) {
            h.append("<div class=\"filtro-importante\" role=\"group\"><button type=\"button\" data-filtro=\"\" class=\"activo\">${escapar(r.importanteTodo)}</button>")
            val cuentas = Importantes.cuentas(c.importantes)
            for (t in Importantes.Tipo.entries) {
                val n = cuentas[t] ?: 0
                if (n > 0) h.append("<button type=\"button\" data-filtro=\"${t.id}\" class=\"${t.id}\"><span class=\"punto-chip\" aria-hidden=\"true\"></span>${escapar(t.rotulo)} $n</button>")
            }
            h.append("</div>\n")
        }
        if (c.lineas.isNotEmpty()) {
            val buscar = escapar(r.buscar)
            h.append("<div class=\"busqueda\"><input type=\"search\" id=\"buscar\" placeholder=\"$buscar\" aria-label=\"$buscar\" autocomplete=\"off\">")
            if (conMedio) h.append("<label class=\"seguir\"><input type=\"checkbox\" id=\"seguir\" checked> ${escapar(r.seguir)}</label>")
            h.append("</div>\n")
        }
        // Al lado: los temas, las decisiones, las preguntas, los marcadores y el índice de las capturas.
        // Sin ellos, el texto a lo ancho.
        val conActa = c.acta.decisiones.isNotEmpty() || c.acta.preguntasAbiertas.isNotEmpty()
        val conLado = c.marcadores.isNotEmpty() || nCapturas > 0 || c.temas.isNotEmpty() || conActa
        h.append(if (conLado) "</section>\n<main class=\"cuerpo con-lado\">\n" else "</section>\n<main class=\"cuerpo\">\n")
        if (conLado) {
            h.append("<aside class=\"lado\">\n")
            if (c.temas.isNotEmpty()) h.append(temasHtml(c, r, conMedio))
            h.append(dichosHtml(r.decisiones, c.acta.decisiones, conMedio))
            h.append(dichosHtml(r.preguntasAbiertas, c.acta.preguntasAbiertas, conMedio))
            if (c.marcadores.isNotEmpty()) {
                h.append("<section class=\"bloque\"><h2>${escapar(r.marcadores)}</h2>\n<ol class=\"lista-marcadores\">\n")
                for (m in c.marcadores) {
                    val texto = if (m.texto.isBlank()) escapar(r.marcador) else escapar(m.texto.trim())
                    h.append("<li data-ms=\"${m.ms}\">${minutoHtml(m.ms, conMedio)} <span>$texto</span></li>\n")
                }
                h.append("</ol></section>\n")
            }
            if (nCapturas > 0) {
                h.append("<section class=\"bloque solo-pantalla\"><h2>${escapar(r.capturas)}</h2>\n<div class=\"miniaturas\" id=\"miniaturas\"></div></section>\n")
            }
            h.append("</aside>\n")
        }

        h.append("<article class=\"texto\" id=\"texto\">\n<h2>${escapar(r.transcripcion)}</h2>\n")
        if (c.lineas.isEmpty()) h.append("<p class=\"vacio\">${escapar(r.sinTexto)}</p>\n")
        for (f in filas(c)) when (f) {
            is Fila.Linea -> {
                val (ms, texto) = c.lineas[f.i]
                if (ms != null) {
                    val (tipos, chapas) = chapitasDe(c, ms)
                    h.append("<p class=\"linea\" id=\"t-$ms\" data-ms=\"$ms\"$tipos>${minutoHtml(ms, conMedio)}$chapas<span class=\"dicho\">${textoConSaltos(texto)}</span></p>\n")
                }
                else h.append("<p class=\"linea sin-tiempo\"><span class=\"dicho\">${textoConSaltos(texto)}</span></p>\n")
            }
            is Fila.Captura -> {
                val s = capturas[f.i] ?: continue
                val ms = c.capturas[f.i].first
                val n = f.i + 1
                val pie = escapar("${r.captura} $n · ${minuto(ms)}")
                h.append("<figure class=\"captura\" id=\"captura-$n\" data-ms=\"$ms\" data-n=\"$n\">")
                h.append("<button type=\"button\" class=\"ampliar\" aria-label=\"$pie\"><img src=\"$s\" alt=\"$pie\" loading=\"lazy\" decoding=\"async\"></button>")
                h.append("<figcaption>${minutoHtml(ms, conMedio)} ${escapar(r.captura)} $n</figcaption></figure>\n")
            }
            is Fila.DeMarcador -> {
                val m = c.marcadores[f.i]
                h.append("<p class=\"marca\" data-ms=\"${m.ms}\">${minutoHtml(m.ms, conMedio)}<span class=\"cinta\">${escapar(r.marcador)}</span><span class=\"dicho\">${escapar(m.texto.trim())}</span></p>\n")
            }
        }
        h.append("</article>\n</main>\n")

        // Lo que no va dentro (el vídeo al lado, los adjuntos): para abrirlo.
        val fuera = ArrayList<Pair<String, String>>()
        video?.let { (ruta, m) -> if (m is Medio.AlLado) fuera += "${r.video} · ${nombre(ruta)}" to m.ruta }
        audio?.let { (ruta, m) -> if (m is Medio.AlLado) fuera += "${r.audio} · ${nombre(ruta)}" to m.ruta }
        for (a in c.adjuntos) {
            val m = medio(a) ?: continue
            fuera += nombre(a) to when (m) {
                is Medio.Incrustado -> src(m)
                is Medio.AlLado -> m.ruta
            }
        }
        if (fuera.isNotEmpty()) {
            h.append("<footer class=\"adjuntos\"><h2>${escapar(r.adjuntos)}</h2><ul>\n")
            for ((n, s) in fuera) {
                val enlace = if (s.startsWith("data:")) s else urlRelativa(s)
                h.append("<li><a href=\"$enlace\" download=\"${escapar(n)}\">${escapar(n)}</a></li>\n")
            }
            h.append("</ul></footer>\n")
        }

        h.append("<dialog id=\"lupa\" class=\"lupa\" aria-label=\"${escapar(r.capturas)}\">")
        h.append("<figure><img id=\"lupa-img\" alt=\"\"><figcaption id=\"lupa-pie\"></figcaption></figure>")
        h.append("<div class=\"lupa-mandos\">")
        h.append("<button type=\"button\" id=\"lupa-antes\" aria-label=\"‹\">‹</button>")
        if (conMedio) h.append("<button type=\"button\" id=\"lupa-ir\">${escapar(r.irAlMinuto)}</button>")
        h.append("<button type=\"button\" id=\"lupa-despues\" aria-label=\"›\">›</button>")
        h.append("<button type=\"button\" id=\"lupa-cerrar\">${escapar(r.cerrar)}</button></div></dialog>\n")
        h.append("<script>\n").append(JS).append("</script>\n</body>\n</html>\n")
        return h.toString()
    }

    /** **La agenda con su estado**: cada tema con ✓ y su minuto (que salta ahí) o, si quedó sin tratar, resaltado con su chapita. */
    private fun temasHtml(c: Datos, r: Rotulos, conMedio: Boolean): String {
        val (hechos, total) = TemasDeLaReunion.cuenta(c.temas)
        val h = StringBuilder("<section class=\"bloque\"><h2>${escapar(r.temas)} <span class=\"cuenta\">$hechos/$total</span></h2>\n<ol class=\"lista-temas\">\n")
        for (t in c.temas) {
            if (t.hecho) {
                val minuto = t.ms?.let { " " + minutoHtml(it, conMedio) }.orEmpty()
                val data = t.ms?.let { " data-ms=\"$it\"" }.orEmpty()
                h.append("<li class=\"tema hecho\"$data><span class=\"casilla\" aria-hidden=\"true\">✓</span><span>${escapar(t.texto)}</span>$minuto${tiempoHtml(c, t.id)}</li>\n")
            } else {
                h.append("<li class=\"tema pendiente\"><span class=\"casilla\" aria-hidden=\"true\"></span><span>${escapar(t.texto)}</span> <span class=\"chip sin-tratar\">${escapar(r.sinTratar)}</span>${tiempoHtml(c, t.id)}</li>\n")
            }
        }
        h.append("</ol></section>\n")
        return h.toString()
    }

    /** Lo que llevó un tema («6 min»), si se contó y fue algo. */
    private fun tiempoDe(c: Datos, id: String): String? =
        c.acta.tiempoPorTema?.de(id)?.takeIf { it > 0 }?.let(ActaViva::duracionCorta)

    private fun tiempoHtml(c: Datos, id: String): String =
        tiempoDe(c, id)?.let { " <span class=\"duracion\">${escapar(it)}</span>" }.orEmpty()

    /** **Las decisiones o las preguntas sin respuesta**, cada una con su minuto (que salta ahí). Sin nada, nada. */
    private fun dichosHtml(titulo: String, v: List<ActaViva.Dicho>, conMedio: Boolean): String {
        if (v.isEmpty()) return ""
        val h = StringBuilder("<section class=\"bloque\"><h2>${escapar(titulo)} <span class=\"cuenta\">${v.size}</span></h2>\n<ol class=\"lista-marcadores lista-acta\">\n")
        for (d in v) h.append("<li data-ms=\"${d.ms}\">${minutoHtml(d.ms, conMedio)} <span>${escapar(d.texto.trim())}</span></li>\n")
        h.append("</ol></section>\n")
        return h.toString()
    }

    // --- Markdown y texto ---------------------------------------------------------------------

    private fun mdEscapar(s: String): String = buildString {
        s.trim().lines().forEachIndexed { i, l ->
            if (i > 0) append("  \n")
            val linea = l.trimEnd()
            // Lo que al principio de un renglón cambiaría su forma.
            if (linea.isNotEmpty() && linea[0] in "#>-+*|") append('\\')
            for (c in linea) {
                if (c in "\\`*_[]<>") append('\\')
                append(c)
            }
        }
    }

    private fun cabeceraTexto(c: Datos, r: Rotulos): List<String> = buildList {
        if (c.fecha.isNotBlank()) add(c.fecha.trim())
        if (c.duracion() > 0) add("${r.duracion} ${minuto(c.duracion())}")
    }

    /**
     * **La grabación en Markdown**: el título, la fecha y la duración, los marcadores, y la
     * transcripción con las capturas en su minuto. [enlace] da la ruta relativa de cada fichero, que
     * va escapada como URL; null, no se enlaza.
     */
    fun markdown(c: Datos, r: Rotulos, enlace: (String) -> String?): String {
        val v = StringBuilder("# ${mdEscapar(c.titulo)}\n\n")
        val cab = cabeceraTexto(c, r)
        if (cab.isNotEmpty()) v.append("*${mdEscapar(cab.joinToString(" · "))}*\n\n")
        val medios = ArrayList<String>()
        for ((rotulo, ruta) in listOf(r.audio to c.audio, r.video to c.video)) {
            val e = ruta?.let(enlace) ?: continue
            medios += "- $rotulo: [${mdEscapar(nombre(ruta))}](${urlRelativa(e)})"
        }
        for (a in c.adjuntos) {
            val e = enlace(a) ?: continue
            medios += "- [${mdEscapar(nombre(a))}](${urlRelativa(e)})"
        }
        if (medios.isNotEmpty()) v.append(medios.joinToString("\n")).append("\n\n")
        // Los temas de la reunión y el acta (29-oct-2026, como el PC).
        if (c.temas.isNotEmpty()) {
            val (hechos, total) = TemasDeLaReunion.cuenta(c.temas)
            v.append("## ${r.temas} ($hechos/$total)\n\n")
            for (t in c.temas) {
                val min = t.ms?.takeIf { t.hecho }?.let { " · ${minuto(it)}" }.orEmpty()
                val marca = if (t.hecho) "x" else " "
                val falta = if (t.hecho) "" else " — *${r.sinTratar}*"
                val tiempo = tiempoDe(c, t.id)?.let { " ($it)" }.orEmpty()
                v.append("- [$marca] ${mdEscapar(t.texto)}$min$tiempo$falta\n")
            }
            v.append('\n')
        }
        for ((titulo, dichos) in listOf(r.decisiones to c.acta.decisiones, r.preguntasAbiertas to c.acta.preguntasAbiertas)) {
            if (dichos.isEmpty()) continue
            v.append("## $titulo\n\n")
            for (d in dichos) v.append("- ${minuto(d.ms)} · ${mdEscapar(d.texto)}\n")
            v.append('\n')
        }
        if (c.marcadores.isNotEmpty()) {
            v.append("## ${r.marcadores}\n\n")
            for (m in c.marcadores) {
                val texto = if (m.texto.isBlank()) r.marcador else mdEscapar(m.texto)
                v.append("- **${minuto(m.ms)}** $texto\n")
            }
            v.append('\n')
        }
        v.append("## ${r.transcripcion}\n\n")
        if (c.lineas.isEmpty()) v.append("*${r.sinTexto}*\n\n")
        for (f in filas(c)) when (f) {
            is Fila.Linea -> {
                val (ms, texto) = c.lineas[f.i]
                if (ms != null) v.append("**${minuto(ms)}** ${mdEscapar(texto)}\n\n") else v.append("${mdEscapar(texto)}\n\n")
            }
            is Fila.Captura -> {
                val (ms, ruta) = c.capturas[f.i]
                enlace(ruta)?.let { v.append("![${r.captura} ${f.i + 1} · ${minuto(ms)}](${urlRelativa(it)})\n\n") }
            }
            is Fila.DeMarcador -> {
                val m = c.marcadores[f.i]
                v.append("> **${r.marcador} · ${minuto(m.ms)}** ${mdEscapar(m.texto)}\n\n")
            }
        }
        return v.toString().trimEnd() + "\n"
    }

    /** El nombre de la captura [i] dentro de la carpeta de la grabación: `captura-01-1m05s.png`. */
    fun nombreDeCaptura(i: Int, ms: Long, ruta: String): String {
        val s = ms.coerceAtLeast(0) / 1000
        val ext = extension(ruta).ifEmpty { "png" }
        return String.format(Locale.ROOT, "captura-%02d-%dm%02ds.%s", i + 1, s / 60, s % 60, ext)
    }

    /**
     * **El Markdown con su carpeta, en un ZIP**: `<base>.md` y, al lado, `<base>_archivos/` con las
     * capturas (lo que exporta Notion u Obsidian). [bytes] lee cada captura; la que no se lee no se enlaza.
     */
    fun paqueteMarkdown(c: Datos, r: Rotulos, base: String, bytes: (String) -> ByteArray?): ByteArray {
        val carpeta = "${base}_archivos"
        val dentro = ArrayList<Pair<String, ByteArray>>()
        val nombres = HashMap<String, String>()
        c.capturas.forEachIndexed { i, (ms, ruta) ->
            val b = bytes(ruta) ?: return@forEachIndexed
            val n = "$carpeta/${nombreDeCaptura(i, ms, ruta)}"
            nombres[ruta] = n
            dentro += n to b
        }
        val md = markdown(c, r) { nombres[it] }
        val salida = ByteArrayOutputStream()
        ZipOutputStream(salida).use { zip ->
            zip.putNextEntry(ZipEntry("$base.md"))
            zip.write(md.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((n, b) in dentro) {
                // Una imagen ya va comprimida: guardada tal cual.
                val e = ZipEntry(n).apply {
                    method = ZipEntry.STORED
                    size = b.size.toLong()
                    compressedSize = b.size.toLong()
                    crc = CRC32().apply { update(b) }.value
                }
                zip.putNextEntry(e)
                zip.write(b)
                zip.closeEntry()
            }
        }
        return salida.toByteArray()
    }

    /** **La transcripción en texto**: el título, la fecha y la duración, y cada línea con su minuto. */
    fun texto(c: Datos, r: Rotulos): String {
        val v = StringBuilder(c.titulo.trim()).append('\n')
        val cab = cabeceraTexto(c, r)
        if (cab.isNotEmpty()) v.append(cab.joinToString(" · ")).append('\n')
        v.append('\n')
        if (c.lineas.isEmpty()) v.append(r.sinTexto).append("\n\n")
        for (f in filas(c)) when (f) {
            is Fila.Linea -> {
                val (ms, texto) = c.lineas[f.i]
                if (ms != null) v.append("[${minuto(ms)}] ${texto.trim()}\n\n") else v.append("${texto.trim()}\n\n")
            }
            is Fila.DeMarcador -> {
                val m = c.marcadores[f.i]
                val texto = m.texto.trim()
                if (texto.isEmpty()) v.append("[${minuto(m.ms)}] -- ${r.marcador}\n\n")
                else v.append("[${minuto(m.ms)}] -- ${r.marcador}: $texto\n\n")
            }
            is Fila.Captura -> {}
        }
        return v.toString().trimEnd() + "\n"
    }
}
