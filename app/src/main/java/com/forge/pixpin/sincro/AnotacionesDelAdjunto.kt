package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Mensaje
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

    private val TERMINACIONES = listOf(".excalidraw.gz", ".marcas", ".espacios", ".maqueta", ".voz", ".sitio")

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
