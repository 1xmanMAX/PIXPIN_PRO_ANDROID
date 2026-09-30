package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.Scene
import java.io.File

/**
 * **Lo anotado sobre un adjunto del chat, nombrado por su mensaje** (28-sep-2026).
 *
 * La tinta de un PDF suelto, la de un Word o un libro, sus marcadores y los espacios para anotar
 * se guardaban con la **ruta absoluta** del documento (una huella en el nombre del dibujo, una
 * clave en las preferencias). La ruta es de este aparato: en el otro el mismo PDF vive en otra, y
 * [Disco.alcance] solo manda lo que cita un mensaje o una hoja. Por eso no viajaban.
 *
 * Ahora todo va en `pins/draw/` con el **código único del mensaje** ([Codigos.unico]), que es el
 * mismo en los dos aparatos:
 *
 * - `anot-<uid>-p<n>.excalidraw.gz`: la tinta de la página `n` (desde 0) de un PDF.
 * - `anot-<uid>.excalidraw.gz`: la tinta de un Word o un libro ([delDocumento]).
 * - `anot-<uid>.marcas`: los marcadores, el mismo texto de siempre.
 * - `anot-<uid>.espacios`: los espacios del PDF, `0`..`3` (1 = izquierda, 2 = derecha).
 * - `anot-<uid>.maqueta`: la columna fijada de un Word o un libro (`columna,izq,der,t,g,l`). Sin
 *   ella la tinta no se pinta —el visor solo la enseña con la columna fijada— o cae en otro sitio.
 * - `anot-<uid>.voz`: el marcador verde de la voz (`párrafo:fracción`; vacío, sin verde).
 * - `anot-<uid>.sitio`: por dónde se iba leyendo (fracción del alto), para seguir en el otro aparato.
 * - `anot-<uid>-p<n>.hoja`, `anot-<uid>.hoja`: **el marco de la tinta** ([Marco]), dónde está la hoja
 *   en las unidades en que están escritos los puntos de su tinta.
 *
 * **El PDF de un proyecto** lleva su tinta en las hojas, que ya viajan; sus marcadores y espacios van
 * aquí con el código **del proyecto** en vez del de un mensaje (`anot-<uid del proyecto>.marcas`).
 * Y **los marcadores de un lienzo** van junto a su dibujo, `<dibujo>.marcas` ([delLienzo]): viajan
 * con él.
 *
 * Un PDF leído «como texto» se anota en otro dibujo y otra columna que el PDF de hojas, y sus
 * marcadores tienen otra forma: va con `anot-<uid>-texto`.
 *
 * Lo de un **PDF de proyecto** no pasa por aquí: es el dibujo de su hoja, que ya viaja.
 */
object AnotacionesDelAdjunto {
    const val CARPETA = "pins/draw"
    private const val PREFIJO = "anot-"

    fun dePagina(uid: String, pagina: Int) = "$PREFIJO$uid-p$pagina"

    fun delPdf(uid: String) = "$PREFIJO$uid"

    /** La base de un Word, un libro, o un PDF leído como texto. */
    fun delDocumento(uid: String, original: File) =
        if (original.name.endsWith(".pdf", ignoreCase = true)) "$PREFIJO$uid-texto" else "$PREFIJO$uid"

    fun marcas(filesDir: File, base: String) = File(filesDir, "$CARPETA/$base.marcas")
    fun voz(filesDir: File, base: String) = File(filesDir, "$CARPETA/$base.voz")
    fun sitio(filesDir: File, base: String) = File(filesDir, "$CARPETA/$base.sitio")

    /** Los marcadores de un lienzo: al lado de su dibujo, con el mismo nombre. */
    fun delLienzo(filesDir: File, dibujo: String) = File(filesDir, "$CARPETA/$dibujo.marcas")

    /** `pins/draw/X.excalidraw.gz` → `pins/draw/X.marcas`; null si no es un dibujo. */
    fun marcasJuntoA(rel: String): String? =
        if (rel.startsWith("$CARPETA/") && rel.endsWith(".excalidraw.gz") && rel.indexOf('/', CARPETA.length + 1) < 0)
            rel.removeSuffix(".excalidraw.gz") + ".marcas" else null

    /** Todo lo de [uid] que hay en disco. Para borrarlo con su mensaje. */
    fun todoDe(filesDir: File, uid: String): List<File> =
        porUid(filesDir)[uid].orEmpty().map { File(filesDir, it) }
    fun espacios(filesDir: File, base: String) = File(filesDir, "$CARPETA/$base.espacios")
    fun maqueta(filesDir: File, base: String) = File(filesDir, "$CARPETA/$base.maqueta")
    fun hoja(filesDir: File, base: String) = File(filesDir, "$CARPETA/$base.hoja")

    /**
     * **El marco de la tinta** (30-sep-2026, idea del usuario; formato acordado con PixPin para
     * Windows, `docs/investigacion/2026-09-29-marco-de-la-tinta-android.md` de ese repo).
     *
     * Un rectángulo invisible alrededor de la hoja —la página de un PDF, la columna de un Word o un
     * libro— **en las mismas unidades que los puntos de su tinta**. Cada aparato escribe en las
     * unidades de su capa; antes el otro tenía que adivinarlas (por los espacios, por el margen de la
     * maqueta) y se equivocaba: la tinta llegaba agrandada, encogida o corrida. Ahora quien lee lleva
     * ese rectángulo al de su propia hoja ([hacia]) y la tinta cae encima.
     *
     * Archivo hermano de la tinta, `<base>.hoja`, dos líneas: `x0,y0,x1,y1` (punto decimal) y `v1`.
     * No va dentro de la escena: una versión vieja lo pintaría o tiraría la escena entera.
     */
    data class Marco(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        val ancho get() = x1 - x0
        val alto get() = y1 - y0
        fun valido() = listOf(x0, y0, x1, y1).all { it.isFinite() } && ancho > 1e-3 && alto > 1e-3
        fun aTexto() = "${n(x0)},${n(y0)},${n(x1)},${n(y1)}\nv1\n"

        /**
         * Lleva un elemento de las unidades de **este** marco a las de [destino] (la misma hoja).
         * Eje por eje: si las proporciones no coinciden, cada trazo queda en el mismo sitio relativo
         * de la hoja. El grosor y la letra, con la escala media.
         */
        fun hacia(destino: Marco): (Element) -> Element {
            val ex = destino.ancho / ancho
            val ey = destino.alto / alto
            val dx = destino.x0 - x0 * ex
            val dy = destino.y0 - y0 * ey
            val k = kotlin.math.sqrt(kotlin.math.abs(ex * ey))
            return { e ->
                e.copy(
                    x = e.x * ex + dx, y = e.y * ey + dy,
                    width = e.width * ex, height = e.height * ey,
                    // Los puntos van relativos a (x, y): solo se escalan.
                    points = e.points?.map { p -> Pt(p.x * ex, p.y * ey) },
                    lastCommittedPoint = e.lastCommittedPoint?.let { p -> Pt(p.x * ex, p.y * ey) },
                    huecos = e.huecos?.map { h -> h.map { p -> Pt(p.x * ex, p.y * ey) } },
                    strokeWidth = e.strokeWidth * k,
                    fontSize = e.fontSize?.let { it * k }
                )
            }
        }

        /** [escena] escrita con este marco, en las unidades de [destino]. Tal cual si ya coinciden. */
        fun llevar(escena: Scene, destino: Marco): Scene =
            if (casiIgual(destino)) escena else escena.copy(elements = escena.elements.map(hacia(destino)))

        fun casiIgual(o: Marco) = listOf(x0 - o.x0, y0 - o.y0, x1 - o.x1, y1 - o.y1).all { kotlin.math.abs(it) < 0.01 }

        companion object {
            /** Hasta tres decimales, sin ceros de cola y nunca con coma: `-1050`, `1414.286`. */
            private fun n(v: Double): String {
                val t = String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
                return if (t == "-0") "0" else t
            }

            /** Null si no se entiende: entonces manda la regla de antes. */
            fun deTexto(t: String?): Marco? {
                val lineas = t?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
                if (lineas.isEmpty() || lineas.size > 2) return null
                if (lineas.size == 2 && lineas[1] != "v1") return null
                val n = lineas[0].split(',').map { it.trim().toDoubleOrNull() ?: return null }
                if (n.size != 4) return null
                return Marco(n[0], n[1], n[2], n[3]).takeIf { it.valido() }
            }

            /** La hoja de un PDF tal como la mide el editor: [ancho] de ancho, desde el cero. */
            fun deHoja(ancho: Double, proporcion: Double) = Marco(0.0, 0.0, ancho, ancho / proporcion.coerceAtLeast(0.01))

            /**
             * **La regla de antes del marco**, para la tinta que llega sin él: el lector del móvil
             * escribía en unidades que dependían de los espacios puestos. La capa mide la hoja más
             * los espacios, su vista abarca siempre `ancho·(1 + 2·margen)` y empieza en `−ancho·margen`.
             * Es la misma cuenta que `capa_del_movil` del PC: sin espacios, `-1050,0,2450,…`; con los
             * dos, la hoja tal cual. [espacios]: 1 = izquierda, 2 = derecha.
             */
            fun delLectorViejo(espacios: Int, ancho: Double, margen: Double, proporcion: Double): Marco {
                val izq = if (espacios and 1 != 0) 1.0 else 0.0
                val der = if (espacios and 2 != 0) 1.0 else 0.0
                val k = (1.0 + 2.0 * margen) / (1.0 + margen * (izq + der))
                val x0 = ancho * margen * izq * k - ancho * margen
                return Marco(x0, 0.0, x0 + ancho * k, ancho / proporcion.coerceAtLeast(0.01) * k)
            }
        }
    }

    fun leerMarco(f: File): Marco? = Marco.deTexto(leer(f))

    /** Escribe [m] salvo que el que hay ya diga lo mismo: una ida y vuelta sin cambios no provoca otro envío. */
    fun escribirMarco(f: File, m: Marco) {
        if (leerMarco(f)?.casiIgual(m) == true) return
        escribir(f, m.aTexto())
    }

    fun leer(f: File): String? = runCatching { if (f.isFile) f.readText() else null }.getOrNull()

    /** Por un temporal: la sincronización no debe leer nunca un archivo a medias (y no manda los `.tmp`). */
    fun escribir(f: File, texto: String) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(texto)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }
    }

    /**
     * **La migración**: lo viejo pasa a lo nuevo si lo nuevo aún no existe. Lo viejo se queda
     * (en esta versión no se borra nada). Devuelve si copió.
     */
    fun copiarSiFalta(viejo: File, nuevo: File): Boolean {
        if (nuevo.exists() || !viejo.isFile) return false
        return runCatching {
            nuevo.parentFile?.mkdirs()
            val tmp = File(nuevo.parentFile, nuevo.name + ".tmp")
            viejo.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(nuevo)) { tmp.copyTo(nuevo, overwrite = true); tmp.delete() }
            true
        }.getOrDefault(false)
    }

    /** Lo mismo con un texto que estaba en las preferencias. */
    fun ponerSiFalta(nuevo: File, viejo: String?): Boolean {
        if (viejo == null || nuevo.exists()) return false
        escribir(nuevo, viejo)
        return true
    }

    /** La columna fijada de un Word o un libro y la letra con la que se fijó. */
    data class Maqueta(val columna: Int, val izq: Int, val der: Int, val tamano: Int, val grosor: Int, val tipo: Int) {
        fun aTexto() = "$columna,$izq,$der,$tamano,$grosor,$tipo"

        companion object {
            fun deTexto(t: String?): Maqueta? {
                val n = t?.trim()?.split(',')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
                if (n.size != 6 || n[0] <= 0) return null
                return Maqueta(n[0], n[1], n[2], n[3], n[4], n[5])
            }
        }
    }

    private val conocidos = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Lo buscado sin éxito, con el sello del chat de entonces: no se relee mientras el chat no cambie. */
    private val ausentes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * **El código del mensaje que lleva este archivo**, o null si no es un adjunto del chat. Se
     * busca en `guardados.jsonl`; solo se descodifican las líneas que nombran el archivo. Lo
     * encontrado se recuerda (el código de un mensaje no cambia); lo no encontrado, solo mientras
     * el chat siga igual, que el archivo puede llegar después.
     */
    fun uidDe(filesDir: File, ruta: String?): String? {
        if (ruta.isNullOrBlank()) return null
        conocidos[ruta]?.let { return it }
        val chat = File(filesDir, "guardados.jsonl")
        if (!chat.isFile) return null
        val sello = "${chat.length()}:${chat.lastModified()}"
        if (ausentes[ruta] == sello) return null
        val nombre = File(ruta).name.takeIf { n -> n.isNotEmpty() && n.none { it == '"' || it == '\\' || it.code < 0x20 } }
        val uid = runCatching {
            chat.useLines { lineas ->
                lineas.filter { nombre == null || it.contains(nombre) }
                    .mapNotNull { runCatching { Disco.JSON.decodeFromString(Mensaje.serializer(), it) }.getOrNull() }
                    .firstOrNull { it.ruta == ruta }
                    ?.let { Codigos.unico(it) }
            }
        }.getOrNull()
        if (uid == null) { ausentes[ruta] = sello; return null }
        ausentes.remove(ruta)
        conocidos[ruta] = uid
        return uid
    }

    private val TERMINACIONES = listOf(".excalidraw.gz", ".marcas", ".espacios", ".maqueta", ".voz", ".sitio", ".hoja")

    /**
     * **Lo anotado que hay en disco, por código de mensaje**, en rutas relativas a `files`. Para
     * [Disco.alcance]: una sola lectura de la carpeta por vuelta.
     */
    fun porUid(filesDir: File): Map<String, List<String>> {
        val nombres = File(filesDir, CARPETA).list() ?: return emptyMap()
        val salida = HashMap<String, MutableList<String>>()
        for (n in nombres) {
            if (!n.startsWith(PREFIJO) || TERMINACIONES.none { n.endsWith(it) }) continue
            val uid = n.substring(PREFIJO.length).takeWhile { it != '-' && it != '.' }
            if (uid.length != Codigos.LARGO) continue
            salida.getOrPut(uid) { ArrayList() } += "$CARPETA/$n"
        }
        return salida
    }
}
