package com.forge.pixpin.guardados

import java.net.URLEncoder
import java.util.Locale

/**
 * **Leer un documento de PixPin en «Lectura Veloz»** (27-oct-2026, lo pidió el usuario).
 *
 * Lectura Veloz es otra aplicación suya (`com.lecturaveloz.app`, repo `1xmanMAX/Speed-Reading-Tool`)
 * y aquí se usa como el escáner ([EscanerProMax]): la opción «Lectura veloz» de los visores y del
 * menú del mensaje la abre **con ese documento ya cargado** —el archivo original, que ella sabe leer—
 * y **en el marcador verde de la voz** de PixPin. Mientras se lee allí, ella devuelve su punto cada
 * pocos segundos y PixPin mueve su marcador verde: gana siempre **la posición más reciente**.
 *
 * **El contrato lo define el código de Lectura Veloz** (commit cf7474f): `web/js/app.js`
 * (`pixpinParams`, `wordForPixpin`, `pixpinPosition`, `syncPixpin`, `openFromPixpin`), el plugin
 * nativo `TtsPlugin.kt` (`copyUri`, `notifyPixpin`) y `tauri.conf.json` (el enlace `lecturaveloz://pixpin`).
 * Está contado en `docs/integracion-lectura-veloz.md`.
 *
 * - **Ida**: `lecturaveloz://pixpin/abrir?doc=…&nombre=…&uri=…&tipo=pdf|texto&pagina=…&frac=…&fraccion=…&paginas=…&t=…`
 *   ([enlace]), con `ACTION_VIEW`. `nombre` lleva la extensión buena: con ella elige ella cómo leerlo.
 * - **Vuelta**: el aviso `com.forge.pixpin.LECTURA_VELOZ` ([ACCION_DE_VUELTA]) con `doc`, `tipo`,
 *   `pagina`, `frac`, `fraccion`, `t`, `palabra` y `total` ([progresoDe]).
 *
 * **El marcador de PixPin no cambia de forma**: es el `anot-<uid>.voz` de siempre (`párrafo:fracción`,
 * [com.forge.pixpin.motor.Lectura.vozATexto]) y, en un PDF, también el 🟢 de sus marcadores
 * (página + fracción de la hoja). Así viaja a los demás aparatos y al PC sin tocar nada. La hora de
 * lo que puso Lectura Veloz y su último porcentaje (para la chapa) se quedan en el teléfono ([Ultimo]).
 *
 * Aquí está lo que no necesita Android, para probarlo sin aparato. Ver `LecturaVelozTest`; lo del
 * teléfono es [LecturaVelozEnElTelefono].
 */
object LecturaVeloz {
    const val PAQUETE = "com.lecturaveloz.app"
    const val ESQUEMA = "lecturaveloz"
    const val HOST = "pixpin"
    /** El aviso con que Lectura Veloz devuelve su punto. Ver [LecturaVelozReceiver]. */
    const val ACCION_DE_VUELTA = "com.forge.pixpin.LECTURA_VELOZ"

    /**
     * **«Abrir en PixPin» desde Lectura Veloz** (28-oct-2026): ella manda el archivo con
     * ACTION_VIEW a [AbrirConPixPinActivity], como cualquier «Abrir con», y si el libro vino de
     * PixPin añade este extra con su `doc`. Si es un mensaje del chat, se abre ese mensaje (con su
     * marcador verde) en vez de la copia de paso. Un PixPin sin esto lo abre de paso igual.
     */
    const val EXTRA_DOC = "com.lecturaveloz.extra.PIXPIN_DOC"

    const val TIPO_PDF = "pdf"
    const val TIPO_TEXTO = "texto"

    /**
     * Lo que Lectura Veloz sabe leer y PixPin tiene como documento (`importers.js`, `EXT`): sin
     * imágenes ni JSON, que no son «un documento para leer».
     */
    val EXTENSIONES = setOf(
        "pdf", "txt", "md", "markdown", "html", "htm", "xhtml", "epub", "docx", "odt", "rtf", "fb2",
        "mobi", "azw3", "pptx", "xlsx", "csv"
    )

    private fun extension(n: String?): String = n.orEmpty().substringAfterLast('/').let { s ->
        if ('.' in s) s.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    }

    /** Si [nombre] (o la ruta) es algo que se puede leer en Lectura Veloz. */
    fun admite(nombre: String?, ruta: String? = null): Boolean =
        extension(nombre) in EXTENSIONES || extension(ruta) in EXTENSIONES

    fun tipoDe(nombre: String?, ruta: String? = null): String =
        if (extension(nombre) == "pdf" || (extension(nombre) !in EXTENSIONES && extension(ruta) == "pdf")) TIPO_PDF else TIPO_TEXTO

    /**
     * **El nombre que se le da**, con la extensión **del archivo de verdad**: Lectura Veloz elige con
     * ella cómo leerlo. Un mensaje renombrado «Capítulo 3» (sin extensión) o con otra se arregla con
     * la de [ruta]; sin nombre, el de la ruta. Sin barras ni saltos, y no muy largo.
     */
    fun nombreConExtension(nombre: String?, ruta: String?): String {
        val delArchivo = extension(ruta)
        val limpio = nombre.orEmpty().replace(Regex("[/\\\\\\r\\n\\t]"), " ").trim()
            .ifBlank { ruta.orEmpty().substringAfterLast('/').ifBlank { "Documento" } }
        val conExt = when {
            delArchivo.isEmpty() -> limpio
            extension(limpio) == delArchivo -> limpio
            else -> "$limpio.$delArchivo"
        }
        if (conExt.length <= 120) return conExt
        val ext = extension(conExt)
        return if (ext.isEmpty()) conExt.take(120) else conExt.dropLast(ext.length + 1).take(119 - ext.length) + "." + ext
    }

    /** Un punto de lectura como lo entiende Lectura Veloz, con la hora en que se puso ([t], ms; 0, ninguno). */
    data class Posicion(val pagina: Int = 0, val frac: Float = 0f, val fraccion: Float = 0f, val t: Long = 0L) {
        fun limpia() = Posicion(pagina.coerceIn(0, MAX_PAGINAS), ok(frac), ok(fraccion), t.coerceAtLeast(0L))
    }

    const val MAX_PAGINAS = 100_000

    private fun ok(f: Float): Float = if (f.isNaN()) 0f else f.coerceIn(0f, 1f)

    private fun num(f: Float): String = String.format(Locale.ROOT, "%.5f", ok(f)).trimEnd('0').trimEnd('.')

    private fun cod(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * **El enlace que abre Lectura Veloz con el documento** (`pixpinParams` de `app.js`). [doc] es el
     * código único del mensaje; [uri], el `content://` de PixPin con permiso de lectura para ella.
     */
    fun enlace(doc: String, nombre: String, uri: String, tipo: String, pos: Posicion, paginas: Int): String {
        val p = pos.limpia()
        val t = if (tipo == TIPO_PDF) TIPO_PDF else TIPO_TEXTO
        return "$ESQUEMA://$HOST/abrir?doc=${cod(doc)}&nombre=${cod(nombre)}&uri=${cod(uri)}&tipo=$t" +
            "&pagina=${p.pagina}&frac=${num(p.frac)}&fraccion=${num(p.fraccion)}" +
            "&paginas=${paginas.coerceIn(0, MAX_PAGINAS)}&t=${p.t}"
    }

    // ---- El texto de lectura de PixPin, en párrafos, para pasar de un marcador al otro. ----

    /**
     * **Los párrafos que lee la voz de PixPin**, contados: cuántas palabras tiene cada uno, dónde
     * empieza (de 0 a 1, la fracción del alto con que se apunta el verde) y de qué hoja es (desde
     * 1; 0, sin hojas). Lo deja el visor cada vez que numera los párrafos
     * ([com.forge.pixpin.motor.VozAlta.PREPARAR]); sin él se pasa por la fracción, que es casi lo mismo.
     */
    data class Mapa(val palabras: List<Int>, val fracciones: List<Float>, val hojas: List<Int>) {
        val cuantos get() = palabras.size
        private val antes: IntArray by lazy {
            IntArray(palabras.size + 1).also { a -> for (i in palabras.indices) a[i + 1] = a[i] + palabras[i].coerceAtLeast(0) }
        }
        val total: Int get() = antes[palabras.size]
        /** Las palabras que hay antes del párrafo [p]. */
        fun antesDe(p: Int): Int = antes[p.coerceIn(0, palabras.size)]
        fun fraccionDe(p: Int): Float = fracciones.getOrNull(p)?.let { ok(it) } ?: if (total > 0) antesDe(p).toFloat() / total else 0f
        fun hojaDe(p: Int): Int = hojas.getOrNull(p) ?: 0

        /** Una línea por párrafo: `palabras,fracción,hoja`. */
        fun aTexto(): String = buildString {
            for (i in palabras.indices) append(palabras[i]).append(',').append(num(fraccionDe(i))).append(',').append(hojaDe(i)).append('\n')
        }

        companion object {
            fun deTexto(t: String?): Mapa? {
                val filas = t?.lines()?.filter { it.isNotBlank() } ?: return null
                val p = ArrayList<Int>(); val f = ArrayList<Float>(); val h = ArrayList<Int>()
                for (fila in filas) {
                    val c = fila.split(',')
                    if (c.size != 3) return null
                    p += c[0].trim().toIntOrNull()?.coerceAtLeast(0) ?: return null
                    f += c[1].trim().toFloatOrNull()?.let { ok(it) } ?: return null
                    h += c[2].trim().toIntOrNull()?.coerceAtLeast(0) ?: return null
                }
                return Mapa(p, f, h).takeIf { it.cuantos > 0 }
            }

            /** De lo que devuelve el guion del visor: los textos, dónde empieza cada uno y su hoja. */
            fun deParrafos(textos: List<String>, fracciones: List<Float>, hojas: List<Int>): Mapa? =
                if (textos.isEmpty()) null
                else Mapa(textos.map { palabrasDe(it) }, textos.indices.map { ok(fracciones.getOrElse(it) { 0f }) }, textos.indices.map { hojas.getOrElse(it) { 0 }.coerceAtLeast(0) })

            /**
             * De un texto suelto (una nota en Markdown): párrafos separados por una línea en blanco, y
             * dónde empieza cada uno por los caracteres de antes.
             */
            fun deTextoPlano(texto: String): Mapa? {
                val trozos = texto.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                if (trozos.isEmpty()) return null
                val largo = trozos.sumOf { it.length }.coerceAtLeast(1)
                var llevo = 0
                val f = trozos.map { s -> (llevo.toFloat() / largo).also { llevo += s.length } }
                return Mapa(trozos.map { palabrasDe(it) }, f, trozos.map { 0 })
            }
        }
    }

    fun palabrasDe(s: String): Int = s.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Las palabras [a, b) de la hoja [hoja] (desde 1), si el mapa sabe de hojas. */
    private fun palabrasDeLaHoja(m: Mapa, hoja: Int): Pair<Int, Int>? {
        val primero = (0 until m.cuantos).firstOrNull { m.hojaDe(it) == hoja } ?: return null
        val ultimo = (0 until m.cuantos).last { m.hojaDe(it) == hoja }
        return m.antesDe(primero) to m.antesDe(ultimo + 1)
    }

    private fun porHojas(fraccion: Float, paginas: Int): Pair<Int, Float> {
        if (paginas <= 0) return 0 to 0f
        val x = ok(fraccion) * paginas
        val pagina = kotlin.math.floor(x).toInt().coerceIn(0, paginas - 1)
        return pagina to ok(x - pagina)
    }

    /**
     * **El verde de PixPin → Lectura Veloz.** Con [mapa], el principio del párrafo en palabras (que
     * es como cuenta ella); sin él, la fracción del verde. En un PDF, además la hoja y por dónde va
     * en ella: la del párrafo si se sabe, o la que sale de la fracción con [paginas].
     */
    fun vozALv(voz: Pair<Int, Float>, mapa: Mapa?, esPdf: Boolean, paginas: Int): Posicion {
        val m = mapa?.takeIf { it.cuantos > 0 && it.total > 0 }
        if (m == null) {
            val f = ok(voz.second)
            val (pagina, frac) = if (esPdf) porHojas(f, paginas) else 0 to 0f
            return Posicion(pagina, frac, f)
        }
        val p = voz.first.coerceIn(0, m.cuantos - 1)
        val w = m.antesDe(p)
        val fraccion = w.toFloat() / m.total
        if (!esPdf) return Posicion(0, 0f, ok(fraccion))
        val hoja = m.hojaDe(p)
        val enLaHoja = if (hoja > 0) palabrasDeLaHoja(m, hoja) else null
        if (enLaHoja != null) {
            val (a, b) = enLaHoja
            return Posicion(hoja - 1, if (b > a) ok((w - a).toFloat() / (b - a)) else 0f, ok(fraccion))
        }
        val (pagina, frac) = porHojas(fraccion, paginas)
        return Posicion(pagina, frac, ok(fraccion))
    }

    /**
     * **Lectura Veloz → el verde de PixPin** (`párrafo:fracción`). Con [mapa], el párrafo en que cae
     * su palabra —en un PDF, por la hoja y la parte de ella si el mapa sabe de hojas— y la fracción
     * donde empieza ese párrafo, igual que lo apunta la voz. Sin él, `0:<fracción>`: el visor (y el
     * PC) buscan entonces el párrafo que empieza más cerca de esa fracción
     * ([com.forge.pixpin.motor.Lectura.parrafoDeLaMarca]).
     */
    fun lvAVoz(pos: Posicion, mapa: Mapa?, esPdf: Boolean): Pair<Int, Float> {
        val m = mapa?.takeIf { it.cuantos > 0 && it.total > 0 } ?: return 0 to ok(pos.fraccion)
        val enLaHoja = if (esPdf) palabrasDeLaHoja(m, pos.pagina + 1) else null
        val w = if (enLaHoja != null) enLaHoja.first + ok(pos.frac) * (enLaHoja.second - enLaHoja.first)
        else ok(pos.fraccion) * m.total
        var p = 0
        for (i in 0 until m.cuantos) {
            if (m.palabras[i] <= 0) continue
            if (m.antesDe(i) <= w) p = i else break
        }
        return p to m.fraccionDe(p)
    }

    /** El 🟢 del lector de PDF (hoja + fracción de la hoja, ver [com.forge.pixpin.motor.Marca]) → Lectura Veloz. */
    fun marcaALv(y: Double, paginas: Int): Posicion {
        val yy = if (y.isNaN()) 0.0 else y.coerceAtLeast(0.0)
        val pagina = kotlin.math.floor(yy).toInt().coerceIn(0, MAX_PAGINAS)
        val frac = ok((yy - pagina).toFloat())
        val fraccion = if (paginas > 0) ok(((pagina + frac) / paginas)) else 0f
        return Posicion(pagina, frac, fraccion)
    }

    /** Lectura Veloz → la altura del 🟢 en el lector de PDF: la hoja más la fracción de la hoja. */
    fun lvAMarca(pos: Posicion, paginas: Int): Double {
        val p = pos.limpia()
        val (pagina, frac) = if (p.pagina > 0 || p.frac > 0f || paginas <= 0) p.pagina to p.frac else porHojas(p.fraccion, paginas)
        val tope = if (paginas > 0) paginas - 1 else MAX_PAGINAS
        val hoja = pagina.coerceIn(0, tope)
        return hoja + frac.toDouble().coerceIn(0.0, 0.999)
    }

    // ---- La vuelta: lo que manda Lectura Veloz mientras se lee. ----

    /** El punto que devolvió Lectura Veloz, ya comprobado. */
    data class Progreso(
        val doc: String, val tipo: String, val pagina: Int, val frac: Float, val fraccion: Float,
        val t: Long, val palabra: Int, val total: Int
    ) {
        val posicion get() = Posicion(pagina, frac, fraccion, t)
    }

    /** Un código de mensaje: letras, cifras, `-` y `_`, no muy largo (es parte de un nombre de archivo). */
    fun docValido(doc: String?): Boolean =
        doc != null && doc.length in 1..80 && doc.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

    /**
     * **Lo que llega, comprobado**: sin `doc` válido o sin hora, nada. Lo demás se encaja: hojas desde
     * 0, fracciones de 0 a 1 (sin NaN), una hora del futuro se queda en [ahora].
     */
    fun progresoDe(doc: String?, tipo: String?, pagina: Int, frac: Float, fraccion: Float, t: Long, palabra: Int, total: Int, ahora: Long): Progreso? {
        val d = doc?.trim()
        if (!docValido(d) || t <= 0L) return null
        return Progreso(
            d!!, if (tipo == TIPO_PDF) TIPO_PDF else TIPO_TEXTO, pagina.coerceIn(0, MAX_PAGINAS), ok(frac), ok(fraccion),
            minOf(t, ahora), palabra.coerceAtLeast(0), total.coerceAtLeast(0)
        )
    }

    /** El porcentaje de la chapa: «47 %». */
    fun porcentaje(fraccion: Float): Int = Math.round(ok(fraccion) * 100f).coerceIn(0, 100)

    /**
     * **Lo último que mandó Lectura Veloz de un documento**, guardado en el teléfono: su punto y su
     * hora, y lo que se escribió con él en el marcador de PixPin ([voz], [marca] del PDF) si se
     * escribió ([escrito]). Así se sabe luego si el verde sigue siendo el suyo (y entonces vale su
     * hora y su punto exacto) o si la voz de PixPin lo movió después.
     */
    data class Ultimo(
        val t: Long, val pagina: Int, val frac: Float, val fraccion: Float,
        val escrito: Boolean = false, val voz: String? = null, val marca: Double? = null
    ) {
        val posicion get() = Posicion(pagina, frac, fraccion, t)

        fun aTexto(): String = listOf(
            "v1", t.toString(), pagina.toString(), num(frac), num(fraccion), if (escrito) "1" else "0",
            marca?.let { String.format(Locale.ROOT, "%.4f", it) }.orEmpty(), voz.orEmpty()
        ).joinToString(";")

        companion object {
            fun deTexto(s: String?): Ultimo? {
                val c = s?.split(';') ?: return null
                if (c.size != 8 || c[0] != "v1") return null
                return Ultimo(
                    c[1].toLongOrNull() ?: return null, c[2].toIntOrNull() ?: return null,
                    c[3].toFloatOrNull() ?: return null, c[4].toFloatOrNull() ?: return null,
                    c[5] == "1", c[7].ifBlank { null }, c[6].toDoubleOrNull()
                )
            }
        }
    }

    /**
     * **La hora del verde de la voz** (`.voz`): la de Lectura Veloz si sigue siendo lo que ella
     * escribió; si no, la del archivo ([modificado]: lo movió la voz, o llegó de otro aparato). Sin
     * verde, 0.
     */
    fun horaDeLaVoz(voz: String?, modificado: Long, ultimo: Ultimo?): Long = when {
        voz.isNullOrBlank() -> 0L
        ultimo != null && ultimo.escrito && ultimo.voz == voz.trim() -> ultimo.t
        else -> modificado.coerceAtLeast(0L)
    }

    /**
     * **La hora del 🟢 de un PDF**: la de Lectura Veloz si está donde ella lo dejó; si no, la de su
     * id (los marcadores se numeran con la hora en que se ponen). Sin 🟢, 0.
     */
    fun horaDeLaMarca(y: Double?, id: Long, ultimo: Ultimo?): Long = when {
        y == null -> 0L
        ultimo != null && ultimo.escrito && ultimo.marca != null && kotlin.math.abs(ultimo.marca - y) < 0.006 -> ultimo.t
        id > 1_000_000_000_000L -> id
        else -> 0L
    }

    /**
     * **Desde dónde se abre en Lectura Veloz**: el marcador de PixPin más reciente, convertido. Si
     * lo último que hay es lo que dejó la propia Lectura Veloz, su punto exacto (sin la ida y vuelta
     * por párrafos) y su hora: así ella no salta. Sin nada, desde el principio con hora 0 (ella
     * sigue entonces donde iba).
     */
    fun posicionParaAbrir(
        voz: Pair<Int, Float>?, horaVoz: Long, marca: Double?, horaMarca: Long, ultimo: Ultimo?,
        mapa: Mapa?, esPdf: Boolean, paginas: Int
    ): Posicion {
        val dePixpin = maxOf(if (voz != null) horaVoz else 0L, if (marca != null) horaMarca else 0L)
        if (ultimo != null && ultimo.escrito && ultimo.t >= dePixpin) return ultimo.posicion.limpia()
        return when {
            marca != null && esPdf && horaMarca >= horaVoz -> marcaALv(marca, paginas).copy(t = horaMarca)
            voz != null -> vozALv(voz, mapa, esPdf, paginas).copy(t = horaVoz)
            marca != null && esPdf -> marcaALv(marca, paginas).copy(t = horaMarca)
            else -> Posicion()
        }.limpia()
    }

    /** Qué se hace con un punto que llega. */
    enum class AlLlegar {
        /** Repetido o más viejo que lo último que mandó: nada. */
        IGNORAR,
        /** Más viejo que el marcador de PixPin: solo la chapa (su porcentaje). */
        SOLO_LA_CHAPA,
        /** Lo más nuevo: también el marcador de PixPin. */
        ESCRIBIR
    }

    /** Gana la posición más reciente: [horaDePixpin] es la del marcador de PixPin ahora mismo. */
    fun alLlegar(p: Progreso, ultimo: Ultimo?, horaDePixpin: Long): AlLlegar = when {
        ultimo != null && p.t <= ultimo.t -> AlLlegar.IGNORAR
        p.t <= horaDePixpin -> AlLlegar.SOLO_LA_CHAPA
        else -> AlLlegar.ESCRIBIR
    }

    /**
     * **Por qué no se puede**, en una frase, para que el aviso diga el problema de verdad y un
     * próximo informe del usuario lo señale (27-oct-2026: «me dice que no pudo abrir el archivo»).
     */
    fun motivoDe(que: AlAbrir, doc: String?, nombre: String?, ruta: String?, hayDocumento: Boolean = true): String? = when (que) {
        AlAbrir.ABRIR -> null
        AlAbrir.FALTA_LA_APP -> "«Lectura Veloz» ($PAQUETE) no está instalada"
        AlAbrir.APP_ANTIGUA -> "«Lectura Veloz» está instalada pero no recibe el enlace $ESQUEMA://$HOST"
        AlAbrir.NO_SE_PUEDE -> when {
            !hayDocumento -> "No es un archivo del chat ni una nota (sin código de mensaje)"
            !docValido(doc) -> "El documento no tiene un código de mensaje válido"
            !admite(nombre, ruta) -> "Lectura Veloz no lee archivos «.${extension(nombre).ifEmpty { extension(ruta) }.ifEmpty { "?" }}»"
            else -> null
        }
    }

    /** Un error al preparar o abrir, con lo que se intentaba y la causa (sin rutas largas). */
    fun motivoDeError(paso: String, e: Throwable): String {
        val causa = (e.message ?: "").replace(Regex("\\s+"), " ").trim().take(160)
        return "No se pudo $paso: ${e.javaClass.simpleName}" + if (causa.isNotEmpty()) " ($causa)" else ""
    }

    /** El aviso corto: la frase de siempre y, si se sabe, el motivo. */
    fun aviso(que: AlAbrir, motivo: String?): String {
        val base = when (que) {
            AlAbrir.FALTA_LA_APP -> "Hace falta instalar «Lectura Veloz» en este teléfono"
            AlAbrir.APP_ANTIGUA -> "Pon al día «Lectura Veloz»: esta versión aún no abre documentos de PixPin"
            AlAbrir.NO_SE_PUEDE -> "Este documento no se puede leer en Lectura Veloz"
            AlAbrir.ABRIR -> return ""
        }
        val m = motivo?.trim().orEmpty()
        return if (m.isEmpty() || que != AlAbrir.NO_SE_PUEDE) base else "$base: $m"
    }

    /** Qué pasa al pulsar «Lectura veloz». */
    enum class AlAbrir { ABRIR, FALTA_LA_APP, APP_ANTIGUA, NO_SE_PUEDE }

    /**
     * Sin la app, que falta; sin código de mensaje o con un formato que no lee, que no se puede; con
     * una de antes de saber abrir documentos de PixPin, que hay que ponerla al día.
     */
    fun queHacerAlAbrir(instalada: Boolean, sabeAbrir: Boolean, doc: String?, nombre: String?, ruta: String?): AlAbrir = when {
        !instalada -> AlAbrir.FALTA_LA_APP
        !docValido(doc) || !admite(nombre, ruta) -> AlAbrir.NO_SE_PUEDE
        !sabeAbrir -> AlAbrir.APP_ANTIGUA
        else -> AlAbrir.ABRIR
    }
}
