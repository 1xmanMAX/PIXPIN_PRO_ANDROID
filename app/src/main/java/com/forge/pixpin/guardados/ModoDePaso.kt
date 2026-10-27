package com.forge.pixpin.guardados

/**
 * **El modo de paso: abrir un archivo de fuera, verlo y salir, sin guardarlo** (27-oct-2026).
 *
 * Lo pidió el usuario: «en Windows con el espacio previsualizo cualquier imagen, PDF, lo que sea.
 * En Android quiero abrir cualquier archivo de los formatos que aceptamos, como una app para
 * abrir archivos: lo abro, lo veo, salgo y se cierra, sin guardarse en ningún lado». Es la
 * «vista rápida» del PC (el espacio en el explorador, `espacio_explorador.rs`, que abre el
 * archivo en un pin lector) llevada al móvil: aquí no hace falta pin, va el visor de siempre.
 *
 * **Antes**, «Abrir con PixPin» hacía una copia en Mensajes guardados y la abría desde el chat:
 * cada vistazo dejaba un mensaje que viajaba a los otros aparatos. Ahora el archivo se copia a
 * `cache/de-paso/<sesión>/` —solo para poder leerlo: la URI del otro caduca— y se abre en su
 * visor con todo (riel, lápiz, marcos, imprimir, día/noche, compartir). Al salir se borra.
 *
 * **Lo anotado de paso vive aparte**: el visor lo guarda como lo de un adjunto del chat,
 * `pins/draw/anot-<uid>…`, pero con un código de paso ([uidDeLaSesion], `paso` + la sesión) que
 * **no tiene el largo de un código de mensaje**: [com.forge.pixpin.sincro.AnotacionesDelAdjunto.porUid]
 * no lo reconoce, así que no viaja ni se confunde con lo de nadie. Si se pulsa «Guardar», cada
 * archivo pasa al código del mensaje nuevo ([claveGuardada]) y desde ahí viaja como siempre.
 *
 * Esta parte no toca Android, para probarla en la JVM: qué visor le toca a cada cosa
 * ([visorPara]) y cómo se renombra lo anotado. Lo que abre y guarda está en [AbrirDePaso].
 */
object ModoDePaso {

    /** La carpeta de la caché donde vive cada sesión de paso, una subcarpeta por sesión. */
    const val CARPETA = "de-paso"

    /** Lo que antecede a la sesión en el código de paso. */
    private const val PREFIJO_UID = "paso"

    /** Lo que antecede a la sesión en el dibujo de una foto abierta de paso. */
    private const val PREFIJO_FOTO = "paso-foto-"

    /** El largo de una sesión: 12 cifras hexadecimales. Con `paso` delante, 16: nunca un código de mensaje (10). */
    const val LARGO_DE_SESION = 12

    /** Con qué visor se ve cada cosa. Lo que es Android (si el .so de Office está…) lo decide quien abre. */
    enum class Visor {
        PDF,
        /** Word o PowerPoint: maquetado en el lector de PDF si está el .so; sin él, el Word como texto. */
        OFFICE,
        EPUB,
        HTML,
        /** Excel, LibreOffice Calc, CSV: el visor de hojas. */
        HOJA,
        /** DWG, DXF: el visor de planos. */
        PLANO,
        /** IFC, RVT: el visor 3D. */
        MODELO_3D,
        IMAGEN,
        VIDEO,
        /** Un `.md` o un `.markdown`, compuesto. Ver [FormatosDeTexto]. */
        MARKDOWN,
        /**
         * Texto llano —`.txt`, `.log`, `.ini`, `.cfg`, `.srt`, `.vtt`, lo del PC—, tal cual. Un `.txt`
         * de puntos de Civil 3D va al visor 3D. Ver [FormatosDeTexto].
         */
        TEXTO,
        /** Ninguno de PixPin: se ofrece abrirlo con otra aplicación o guardarlo. */
        NINGUNO
    }

    private val DE_WORD = setOf("docx", "docm", "dotx", "dotm")
    private val DE_POWERPOINT = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm")
    private val DE_HOJA = setOf("xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods", "csv", "tsv")
    private val DE_IMAGEN = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    private val DE_VIDEO = setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov")

    /**
     * **La extensión de lo que llega**: la del nombre si la trae; si no —un `content://` sin nombre—,
     * la que corresponde a su tipo. Sin `MimeTypeMap` (no hay en la JVM) y con los tipos que PixPin
     * abre, incluidos los alias de los planos que anuncia cada visor (ver `data/TiposDeArchivo`).
     */
    fun extensionDe(nombre: String?, mime: String?): String {
        val delNombre = nombre.orEmpty().substringAfterLast('/').let { n -> if ('.' in n) n.substringAfterLast('.').lowercase() else "" }
        if (delNombre.isNotEmpty()) return delNombre
        val m = mime.orEmpty().lowercase().substringBefore(';').trim()
        return when (m) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "text/html", "application/xhtml+xml" -> "html"
            "text/markdown", "text/x-markdown", "text/x-web-markdown" -> "md"
            "text/plain" -> "txt"
            "text/x-log" -> "log"
            "text/vtt" -> "vtt"
            "application/x-subrip", "text/x-subrip", "application/x-srt" -> "srt"
            "text/csv", "text/comma-separated-values" -> "csv"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.oasis.opendocument.spreadsheet" -> "ods"
            "model/ifc", "application/ifc" -> "ifc"
            "image/vnd.dxf", "application/dxf", "application/x-dxf", "image/x-dxf" -> "dxf"
            "image/vnd.dwg", "application/acad", "application/x-acad", "application/autocad_dwg", "application/dwg",
            "application/x-dwg", "image/x-dwg", "drawing/dwg", "application/x-autocad" -> "dwg"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heic" -> "heic"
            "image/svg+xml" -> "svg"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            else -> ""
        }
    }

    /**
     * **Qué visor le toca** a un archivo de nombre [nombre] y tipo [mime]. Manda la extensión (el
     * tipo que dice un explorador es a menudo `octet-stream`); sin ella, el tipo. Es la misma tabla
     * que sigue el chat al tocar un adjunto (`MensajesActivity.abrir`), sin lo que solo tiene
     * sentido dentro de él (una lección, un proyecto).
     *
     * **Los de texto, como en el PC** (27-oct-2026, ver [FormatosDeTexto]): `md` y `markdown` al
     * Markdown; `txt`, `log`, `ini`, `cfg`, `srt` y `vtt`, como texto llano. Y lo que llega **sin
     * extensión** —un explorador que solo dice `application/octet-stream`, o un `README`— se mira
     * por dentro si se tienen sus primeros bytes ([cabeza]): si es texto, se ve como texto.
     */
    fun visorPara(nombre: String?, mime: String?, cabeza: ByteArray? = null): Visor {
        val ext = extensionDe(nombre, mime)
        val m = mime.orEmpty().lowercase()
        return when {
            ext == "pdf" -> Visor.PDF
            ext in DE_WORD || ext in DE_POWERPOINT -> Visor.OFFICE
            ext == "epub" -> Visor.EPUB
            // Un SVG es una página: el visor web lo pinta tal cual (el lienzo no lo lee como foto).
            ext in setOf("html", "htm", "xhtml", "svg") -> Visor.HTML
            ext in DE_HOJA -> Visor.HOJA
            ext == "dwg" || ext == "dxf" -> Visor.PLANO
            ext == "ifc" || ext == "rvt" -> Visor.MODELO_3D
            ext in FormatosDeTexto.DE_MARKDOWN -> Visor.MARKDOWN
            ext in FormatosDeTexto.LLANAS -> Visor.TEXTO
            ext in DE_IMAGEN -> Visor.IMAGEN
            ext in DE_VIDEO -> Visor.VIDEO
            // Sin extensión conocida, el tipo genérico: una foto o un vídeo que el explorador no nombra.
            ext.isEmpty() && m.startsWith("image/") -> Visor.IMAGEN
            ext.isEmpty() && m.startsWith("video/") -> Visor.VIDEO
            // Un tipo de texto que no se conoce (`text/x-algo`): texto llano.
            ext.isEmpty() && m.startsWith("text/") -> Visor.TEXTO
            // Sin extensión ni tipo útil: lo que hay dentro.
            ext.isEmpty() && cabeza != null && FormatosDeTexto.pareceTexto(cabeza) -> Visor.TEXTO
            else -> Visor.NINGUNO
        }
    }

    /** Si [visor] se ve en PixPin; si no, lo compartido va al menú corto. */
    fun seVe(visor: Visor) = visor != Visor.NINGUNO

    // ------------------------------------------------------------ sesiones y códigos

    /** Si [sesion] tiene la forma de una sesión de paso (12 cifras hexadecimales en minúscula). */
    fun esSesion(sesion: String?): Boolean =
        sesion != null && sesion.length == LARGO_DE_SESION && sesion.all { it in '0'..'9' || it in 'a'..'f' }

    /** Una sesión nueva a partir de [azar] (dos `Long` al azar bastan; se recorta a 12 cifras). */
    fun sesionDe(azar: Long): String = java.lang.Long.toHexString(azar).padStart(16, '0').takeLast(LARGO_DE_SESION)

    /** **El código de paso** de la sesión: hace las veces del código único de un mensaje. */
    fun uidDeLaSesion(sesion: String): String = PREFIJO_UID + sesion

    /**
     * **La sesión de paso de una ruta**, si el archivo vive en `…/de-paso/<sesión>/…`; null si no.
     * Es lo que mira [com.forge.pixpin.sincro.AnotacionesDelAdjunto.uidDe] antes de buscar en el chat.
     */
    fun sesionDeLaRuta(ruta: String?): String? {
        if (ruta.isNullOrBlank()) return null
        val partes = ruta.replace('\\', '/').split('/')
        val i = partes.lastIndexOf(CARPETA)
        if (i < 0 || i + 2 > partes.lastIndex) return null
        return partes[i + 1].takeIf { esSesion(it) }
    }

    /** El código de paso de [ruta], o null si no es de una sesión de paso. */
    fun uidDeLaRuta(ruta: String?): String? = sesionDeLaRuta(ruta)?.let { uidDeLaSesion(it) }

    /** El dibujo donde se anota una foto abierta de paso (en `pins/draw/`, como cualquier lienzo). */
    fun dibujoDeLaFoto(sesion: String): String = PREFIJO_FOTO + sesion

    /** El dibujo de la foto ya guardada: el mismo que le pone el chat ([dibujoDeLaFoto] de un mensaje). */
    fun dibujoDeLaFotoGuardada(idDelMensaje: String): String = "foto-$idDelMensaje"

    /**
     * **Cómo se llama lo anotado de paso una vez guardado.** [nombre] es un archivo de `pins/draw/`;
     * si es de la sesión ([uidDePaso]) devuelve su nombre con el código del mensaje nuevo
     * ([uidNuevo]) —`anot-paso<sesión>-p3.excalidraw.gz` → `anot-<uid>-p3.excalidraw.gz`—, y si no lo
     * es, null. Los temporales a medio escribir (`.tmp`) no se llevan: serían basura.
     */
    fun claveGuardada(nombre: String, uidDePaso: String, uidNuevo: String): String? {
        val prefijo = "anot-$uidDePaso"
        if (!nombre.startsWith(prefijo) || nombre.endsWith(".tmp")) return null
        val resto = nombre.substring(prefijo.length)
        // Tras el código, o nada, o un separador: `anot-pasoX-p0…`, `anot-pasoX.marcas`. Así una
        // sesión no se come a otra que empiece igual.
        if (resto.isNotEmpty() && resto[0] != '-' && resto[0] != '.') return null
        return "anot-$uidNuevo$resto"
    }

    /** Todo lo que hay que renombrar de [nombres] (los de `pins/draw/`) al guardar: de → a. */
    fun renombres(nombres: Collection<String>, sesion: String, uidNuevo: String, idDelMensaje: String?): List<Pair<String, String>> {
        val uid = uidDeLaSesion(sesion)
        val foto = dibujoDeLaFoto(sesion)
        return nombres.sorted().mapNotNull { n ->
            claveGuardada(n, uid, uidNuevo)?.let { n to it }
                ?: if (idDelMensaje != null && n.startsWith(foto) && !n.endsWith(".tmp") &&
                    n.substring(foto.length).let { r -> r.isEmpty() || r[0] == '.' }
                ) n to dibujoDeLaFotoGuardada(idDelMensaje) + n.substring(foto.length) else null
        }
    }

    /** Lo de `pins/draw/` que es de la sesión [sesion] y se borra al salir sin guardar. */
    fun esDeLaSesion(nombre: String, sesion: String): Boolean {
        val uid = uidDeLaSesion(sesion)
        val foto = dibujoDeLaFoto(sesion)
        fun tras(prefijo: String) = nombre.startsWith(prefijo) && nombre.substring(prefijo.length).let { r -> r.isEmpty() || r[0] == '-' || r[0] == '.' }
        return tras("anot-$uid") || tras(foto)
    }

    /** Lo de `pins/draw/` que es de **alguna** sesión de paso (para la limpieza al arrancar). */
    fun sesionDelNombre(nombre: String): String? {
        val tras = when {
            nombre.startsWith("anot-$PREFIJO_UID") -> nombre.removePrefix("anot-$PREFIJO_UID")
            nombre.startsWith(PREFIJO_FOTO) -> nombre.removePrefix(PREFIJO_FOTO)
            else -> return null
        }
        val sesion = tras.take(LARGO_DE_SESION)
        val resto = tras.drop(LARGO_DE_SESION)
        return sesion.takeIf { esSesion(it) && (resto.isEmpty() || resto[0] == '-' || resto[0] == '.') }
    }

    // ------------------------------------------------------------ compartir a PixPin

    /**
     * **Lo que se hace con algo compartido a PixPin** (27-oct-2026, el usuario: «quita ese menú para
     * los formatos conocidos»). Un solo archivo que PixPin sabe ver se abre de paso, sin menú; un
     * `.pixpin` se importa (es un proyecto); todo lo demás —un formato desconocido, varios archivos,
     * texto o un enlace— va al menú corto ([Recibido.MENU]).
     */
    enum class Recibido { DE_PASO, IMPORTAR, MENU }

    fun queHacerConLoCompartido(cuantos: Int, nombre: String?, mime: String?): Recibido {
        if (cuantos != 1) return Recibido.MENU
        val ext = extensionDe(nombre, mime)
        if (ext == "pixpin") return Recibido.IMPORTAR
        // El texto que acompaña a un archivo (el enlace con que Chrome comparte un PDF, el asunto)
        // no cambia nada: el archivo se abre de paso, y «Guardar» lo deja en el chat sin él.
        return if (seVe(visorPara(nombre, mime))) Recibido.DE_PASO else Recibido.MENU
    }
}
