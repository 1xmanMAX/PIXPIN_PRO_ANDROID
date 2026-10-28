package com.forge.pixpin.ui

/**
 * **Los formatos de la hoja de compartir: cuáles, en qué orden y cómo se llaman** (27-oct-2026).
 *
 * Lo pidió el usuario: «en todo compartir de toda la aplicación siempre tienen que estar las mismas
 * opciones que en las demás: compartir como imagen, como tal cosa, como PixPin, como HTML y todo, de
 * forma entendible siempre». La hoja ya era una sola ([HojaDeCompartir]), pero cada sitio que la abre
 * ponía sus formatos en su orden y con su nombre: «Página web» primero en el plano y tercero en el
 * lector de PDF, «TXT» en una lección y «Texto» en una grabación, «Editable» en el lienzo y «.pixpin»
 * en los visores, «Archivo» en el chat y «XLSX» en el Excel. Ahora quien la abre solo dice **qué**
 * formatos hay (por su id) y aquí se decide, una vez para toda la aplicación:
 *
 * - **El orden**, el mismo siempre ([Clase], de arriba abajo): la imagen (PNG, JPG, SVG), el PDF, la
 *   página web, Word, Markdown, el texto, la tabla en CSV, lo propio de ese contenido (el modelo 3D,
 *   el DXF), el `.pixpin`, el original, Anki y, al final, enviar por Wi-Fi a mis aparatos. Como en la
 *   hoja del PC (`apps/pixpin/src/compartir.rs`): la imagen y el PDF delante, lo entero detrás.
 * - **El nombre**, que dice qué sale sin saber de extensiones: «Imagen PNG», «Página web (HTML)»,
 *   «PixPin (.pixpin)», «Original (XLSX)».
 * - **Qué tiene sentido para cada contenido** ([TABLA]): una nota no tiene imagen ni SVG, un plano no
 *   tiene Word. La hoja enseña solo lo de esta tabla cuando quien la abre dice qué es
 *   ([Compartible.contenido]).
 *
 * Sin nada de Android: lo prueba `FormatosDeCompartirTest` en la JVM. Los iconos, que sí son de
 * Compose, están en [HojaDeCompartir].
 */
object FormatosDeCompartir {

    /** Cada formato, **en el orden en que sale en la hoja**. [rotulo] es su nombre (vacío en [OTRO]: el suyo). */
    enum class Clase(val rotulo: String) {
        PNG("Imagen PNG"),
        JPG("Imagen JPG"),
        SVG("Dibujo SVG"),
        PDF("PDF"),
        WEB("Página web (HTML)"),
        WORD("Word"),
        MARKDOWN("Markdown"),
        TEXTO("Texto"),
        CSV("Tabla (CSV)"),
        /** Lo propio de un contenido (el modelo 3D de un croquis, el DXF de un DWG): con su nombre. */
        OTRO(""),
        PIXPIN("PixPin (.pixpin)"),
        ORIGINAL("Original"),
        ANKI("Anki"),
        WIFI("Por Wi‑Fi a mis aparatos")
    }

    /** Qué es lo que se comparte. Ver [TABLA]. */
    enum class Contenido {
        /** Un proyecto, un lienzo (con sus marcos), una foto anotada o una página de un PDF del chat. */
        PROYECTO,
        /** Varios mensajes del chat marcados a la vez. */
        MENSAJES,
        /** Una nota de texto (Markdown). */
        NOTA,
        /** Un PDF abierto en el lector. */
        PDF,
        /** Un Word, un PowerPoint o un libro (EPUB), en el lector o en el visor. */
        DOCUMENTO,
        /** Un libro de Excel (o un CSV) en su visor. */
        HOJA_DE_CALCULO,
        /** Una tabla de PixPin en su editor. */
        TABLA,
        /** Un plano (DWG, DXF) en su visor. */
        PLANO,
        /** Un modelo 3D (IFC, OBJ, STL…) en su visor. */
        MODELO_3D,
        /** Un croquis 3D de PixPin. */
        CROQUIS_3D,
        /** Una grabación: una nota de voz o la carpeta de su clase. */
        GRABACION,
        /** Una lección. */
        LECCION,
        /**
         * Una carpeta (un cuaderno) de páginas del chat. **Con todo, como un proyecto** (28-oct-2026,
         * «que añada todos los formatos que tienen los demás»): la imagen de una página, el PDF, la
         * web, el texto de sus notas, su `.pixpin`, el ZIP de sus páginas y la Wi-Fi. Ver
         * [com.forge.pixpin.guardados.CompartirCarpeta].
         */
        CARPETA,
        /** Una imagen suelta: una captura de la galería, un pin, lo recién capturado. */
        IMAGEN,
        /** Cualquier otro archivo: un vídeo, un audio, un ZIP… */
        ARCHIVO
    }

    /**
     * **Qué formatos tiene sentido ofrecer para cada contenido**, ya en el orden de [Clase]. Es lo
     * más que se ve: un formato sale si quien abre la hoja sabe hacerlo y está aquí. [Clase.OTRO]
     * (lo propio del contenido) sale siempre que se dé.
     */
    val TABLA: Map<Contenido, List<Clase>> = mapOf(
        Contenido.PROYECTO to listOf(Clase.PNG, Clase.JPG, Clase.SVG, Clase.PDF, Clase.WEB, Clase.WORD, Clase.MARKDOWN, Clase.TEXTO, Clase.OTRO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.MENSAJES to listOf(Clase.PNG, Clase.JPG, Clase.SVG, Clase.PDF, Clase.WEB, Clase.WORD, Clase.MARKDOWN, Clase.TEXTO, Clase.OTRO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.NOTA to listOf(Clase.PDF, Clase.WEB, Clase.WORD, Clase.MARKDOWN, Clase.TEXTO, Clase.PIXPIN, Clase.WIFI),
        Contenido.PDF to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.DOCUMENTO to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.HOJA_DE_CALCULO to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.CSV, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.TABLA to listOf(Clase.WEB, Clase.CSV),
        Contenido.PLANO to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.OTRO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.MODELO_3D to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.CROQUIS_3D to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.OTRO, Clase.PIXPIN, Clase.ORIGINAL),
        Contenido.GRABACION to listOf(Clase.PDF, Clase.WEB, Clase.WORD, Clase.MARKDOWN, Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL, Clase.ANKI, Clase.WIFI),
        Contenido.LECCION to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.MARKDOWN, Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL),
        Contenido.CARPETA to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.WORD, Clase.MARKDOWN, Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.IMAGEN to listOf(Clase.PNG, Clase.JPG, Clase.PDF, Clase.WEB, Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI),
        Contenido.ARCHIVO to listOf(Clase.TEXTO, Clase.PIXPIN, Clase.ORIGINAL, Clase.WIFI)
    )

    fun formatosDe(contenido: Contenido): List<Clase> = TABLA.getValue(contenido)

    /**
     * **De qué clase es un formato por su id.** Los ids son los de siempre de cada generador
     * (`web-pdf-anotado`, `pdf-anotado`, `editable`…): sus variantes caen en su clase por el prefijo.
     */
    fun claseDe(id: String): Clase {
        val i = id.lowercase()
        return when {
            i == "png" || i == "imagen" -> Clase.PNG
            i == "jpg" || i == "jpeg" -> Clase.JPG
            i == "svg" -> Clase.SVG
            i == "pdf" || i.startsWith("pdf-") -> Clase.PDF
            i == "web" || i.startsWith("web-") -> Clase.WEB
            i == "word" || i == "docx" -> Clase.WORD
            i == "md" || i == "markdown" -> Clase.MARKDOWN
            i == "texto" || i == "txt" -> Clase.TEXTO
            i == "csv" -> Clase.CSV
            i == "editable" || i == "pixpin" -> Clase.PIXPIN
            i == "original" -> Clase.ORIGINAL
            i == "anki" -> Clase.ANKI
            i == "wifi" -> Clase.WIFI
            else -> Clase.OTRO
        }
    }

    private val EXTENSION = Regex("^[A-Z0-9]{1,6}$")
    private const val WEB_A_SECAS = "Página web"

    /**
     * **El nombre con el que sale** el formato [id] que su generador llamó [propio]. El de su clase,
     * salvo en tres casos en que el propio dice algo más:
     *
     * - Lo propio de un contenido ([Clase.OTRO]): su nombre («Modelo 3D», «DXF»).
     * - Dos páginas web distintas del mismo documento («Página web por hojas», «Página web en
     *   texto»): el propio, que es lo que las distingue.
     * - El original con su terminación («XLSX», «DWG»): «Original (XLSX)», que dice qué archivo es.
     */
    fun rotulo(id: String, propio: String): String {
        val clase = claseDe(id)
        return when {
            clase == Clase.OTRO -> propio
            clase == Clase.WEB && propio.startsWith("$WEB_A_SECAS ") && !propio.startsWith("$WEB_A_SECAS (") -> propio
            clase == Clase.ORIGINAL && EXTENSION.matches(propio) -> "${clase.rotulo} ($propio)"
            else -> clase.rotulo
        }
    }

    /** [lista] en el orden de la hoja ([Clase]); los de la misma clase, como venían. */
    fun <T> ordenar(lista: List<T>, idDe: (T) -> String): List<T> = lista.sortedBy { claseDe(idDe(it)).ordinal }

    /** Lo de [lista] que tiene sentido para [contenido] (ver [TABLA]), en el orden de la hoja. Sin contenido, todo. */
    fun <T> visibles(contenido: Contenido?, lista: List<T>, idDe: (T) -> String): List<T> {
        val vale = contenido?.let { formatosDe(it).toSet() }
        return ordenar(lista.filter { vale == null || claseDe(idDe(it)).let { c -> c == Clase.OTRO || c in vale } }, idDe)
    }

    /** Si [lista] va en el orden de la hoja. Para las pruebas. */
    fun enOrden(lista: List<Clase>): Boolean = lista.zipWithNext().all { (a, b) -> a.ordinal < b.ordinal }
}
