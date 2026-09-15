package com.forge.pixpin.motor

import android.content.Context
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * **El formato único: un proyecto entero en un archivo, editable en cualquier PixPin.**
 *
 * Un `.pixpin` es un **ZIP** —nada inventado: lo abre cualquier lenguaje y cualquier
 * ordenador— con dentro lo que un proyecto tiene repartido por el teléfono: sus lienzos, sus
 * croquis del espacio, sus notas, sus fotos y su PDF. Es lo que hace falta para pasar un
 * proyecto a otro aparato —o a la versión de escritorio— y seguir editándolo allí, que es lo
 * que pidió el usuario (5-sep-2026). La descripción completa del formato, para quien tenga que
 * leerlo desde otra aplicación, está en `docs/formato-pixpin.md`.
 *
 * Dentro va todo **sin comprimir dos veces**: los lienzos y los croquis, que en el teléfono
 * viven en gzip, se meten como JSON plano —el ZIP ya comprime— y así un editor de escritorio
 * los lee sin más. Las rutas absolutas del teléfono **no viajan**: cada foto va dentro con su
 * identificador, y al importar se le pone la ruta que toque en el aparato nuevo.
 *
 * ## Lo que hay dentro
 *
 * | Entrada                         | Qué es                                                  |
 * |---------------------------------|---------------------------------------------------------|
 * | `manifest.json`                 | Formato, versión, quién lo escribió y cuándo.           |
 * | `proyecto.json`                 | El [Proyecto] tal cual, con las rutas del PDF quitadas. |
 * | `lienzos/<id>.excalidraw`       | Cada lienzo, en JSON de Excalidraw (rutas de fotos = id).|
 * | `imagenes/<id>`                 | Cada foto de los lienzos, en su formato original.       |
 * | `croquis/<id>.json`             | Cada croquis del espacio, en su JSON.                   |
 * | `notas/<id-de-hoja>.md`         | Cada nota, en Markdown.                                 |
 * | `tablas/<id>.json`              | Cada tabla con fórmulas. Ver [TablaDeCalculo].          |
 * | `documento.pdf`                 | El PDF del proyecto, si lo hay (el limpio, sin anotar). |
 * | `chat/mensajes.jsonl`           | Opcional: los mensajes de su chat, uno por línea.       |
 * | `chat/adjuntos/<clave>`         | Opcional: los archivos de esos mensajes.                |
 *
 * El chat solo lo pone el envío por Wi-Fi (`sincro/ChatQueViaja.kt`); quien no lo entienda lo
 * ignora y el proyecto se abre igual.
 */
object PaquetePixpin {

    const val EXTENSION = "pixpin"
    const val MIME_TYPE = "application/zip"
    const val VERSION = 1

    /**
     * Escribe [proyecto] entero en [destino].
     *
     * [croquisDe] da el JSON de un croquis por su id —lo sabe `croquis3d`, que el motor no
     * puede importar—. Devuelve el archivo, o null si algo no se pudo leer.
     */
    fun escribir(
        context: Context,
        proyecto: Proyecto,
        destino: File,
        croquisDe: (String) -> String? = { null },
        escrito: Long = System.currentTimeMillis(),
        /** Los mensajes del chat, ya en texto, y sus archivos por clave. Ver la tabla de arriba. */
        chat: String? = null,
        adjuntosDelChat: Map<String, File> = emptyMap()
    ): File? = runCatching {
        destino.parentFile?.mkdirs()
        ZipOutputStream(destino.outputStream().buffered()).use { zip ->
            fun entrada(nombre: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(nombre))
                zip.write(bytes)
                zip.closeEntry()
            }
            entrada(
                "manifest.json",
                ("{\"formato\":\"pixpin\",\"version\":$VERSION,\"aplicacion\":\"pixpin-android\"," +
                    "\"escrito\":$escrito,\"proyecto\":\"${escaparJson(proyecto.nombre)}\"}")
                    .toByteArray()
            )

            // El proyecto, sin rutas del teléfono: el PDF va dentro con nombre fijo.
            val limpio = proyecto.copy(pdfOrigen = null, pdfLimpio = null)
            entrada("proyecto.json", Proyectos.json.encodeToString(Proyecto.serializer(), limpio).toByteArray())

            val pdf = listOfNotNull(proyecto.pdfLimpio, proyecto.pdfOrigen)
                .map { File(it) }.firstOrNull { it.exists() }
            if (pdf != null) entrada("documento.pdf", pdf.readBytes())

            val fotosPuestas = HashSet<String>()
            for (hoja in proyecto.hojas) {
                hoja.nota?.let { entrada("notas/${hoja.id}.md", it.toByteArray()) }
                hoja.tabla?.let { id ->
                    TablasEnDisco.de(context.filesDir).cargar(id)?.let {
                        entrada("tablas/$id.json", TablaDeCalculo.aJson(it).toByteArray())
                    }
                }
                val dibujo = hoja.dibujo ?: continue
                val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, dibujo)) ?: continue
                // Las fotos, dentro y por su id; en el JSON la ruta pasa a ser ese id.
                val conRutasLimpias = escena.copy(
                    files = escena.files.mapValues { (id, f) ->
                        f.path?.let { ruta ->
                            val origen = File(ruta)
                            if (origen.exists() && fotosPuestas.add(id)) entrada("imagenes/$id", origen.readBytes())
                        }
                        f.copy(path = "imagenes/$id")
                    }
                )
                entrada("lienzos/$dibujo.excalidraw", ExcalidrawJson.encodeToString(conRutasLimpias).toByteArray())
            }
            for (id in proyecto.croquis) {
                croquisDe(id)?.let { entrada("croquis/$id.json", it.toByteArray()) }
            }
            if (chat != null) {
                entrada("chat/mensajes.jsonl", chat.toByteArray())
                for ((clave, archivo) in adjuntosDelChat) {
                    if (archivo.isFile) entrada("chat/adjuntos/$clave", archivo.readBytes())
                }
            }
        }
        destino
    }.getOrNull()

    /** Lo que se lee de un paquete, antes de darle sitio en el aparato. */
    class Contenido(
        val proyecto: Proyecto,
        val lienzos: Map<String, String>,
        val imagenes: Map<String, ByteArray>,
        val croquis: Map<String, String>,
        val notas: Map<String, String>,
        val pdf: ByteArray?,
        val tablas: Map<String, String> = emptyMap(),
        val chat: String? = null,
        val adjuntosDelChat: Map<String, ByteArray> = emptyMap()
    )

    /** Abre un paquete y devuelve lo que trae, o null si no es un `.pixpin`. */
    fun abrir(archivo: File): Contenido? = runCatching {
        var proyecto: Proyecto? = null
        val lienzos = HashMap<String, String>()
        val imagenes = HashMap<String, ByteArray>()
        val croquis = HashMap<String, String>()
        val notas = HashMap<String, String>()
        val tablas = HashMap<String, String>()
        var pdf: ByteArray? = null
        var chat: String? = null
        val adjuntosDelChat = HashMap<String, ByteArray>()
        var esPixpin = false
        ZipInputStream(archivo.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val nombre = e.name
                val bytes = zip.readBytes()
                when {
                    nombre == "manifest.json" -> esPixpin = String(bytes).contains("\"pixpin\"")
                    nombre == "proyecto.json" ->
                        proyecto = Proyectos.json.decodeFromString(Proyecto.serializer(), String(bytes))
                    nombre == "documento.pdf" -> pdf = bytes
                    nombre.startsWith("lienzos/") ->
                        lienzos[nombre.removePrefix("lienzos/").removeSuffix(".excalidraw")] = String(bytes)
                    nombre.startsWith("imagenes/") -> imagenes[nombre.removePrefix("imagenes/")] = bytes
                    nombre.startsWith("croquis/") ->
                        croquis[nombre.removePrefix("croquis/").removeSuffix(".json")] = String(bytes)
                    nombre.startsWith("notas/") ->
                        notas[nombre.removePrefix("notas/").removeSuffix(".md")] = String(bytes)
                    nombre.startsWith("tablas/") ->
                        tablas[nombre.removePrefix("tablas/").removeSuffix(".json")] = String(bytes)
                    nombre == "chat/mensajes.jsonl" -> chat = String(bytes)
                    nombre.startsWith("chat/adjuntos/") -> adjuntosDelChat[nombre.removePrefix("chat/adjuntos/")] = bytes
                }
                zip.closeEntry()
            }
        }
        val p = proyecto ?: return null
        if (!esPixpin) return null
        Contenido(p, lienzos, imagenes, croquis, notas, pdf, tablas, chat, adjuntosDelChat)
    }.getOrNull()

    /**
     * **Le da sitio en este aparato** a lo que trae un paquete: un proyecto nuevo, con ids
     * nuevos para no pisar nada, sus lienzos y fotos donde van, y su PDF copiado.
     *
     * [guardarCroquis] escribe un croquis por su id nuevo y su JSON —lo sabe `croquis3d`—.
     * Devuelve el proyecto ya listo para guardarlo en el repositorio.
     */
    fun importar(
        context: Context,
        contenido: Contenido,
        ahora: Long,
        carpetaDeProyectos: File,
        guardarCroquis: (String, String) -> Boolean = { _, _ -> false },
        /**
         * **Si un id ya se usa aquí** (un proyecto, una hoja). Lo que no esté en uso **conserva su id**
         * (15-sep-2026): así lo recibido se llama igual que en el aparato de donde vino, y al
         * sincronizarse después con él no sale repetido. Por omisión todo se da por usado y se le
         * pone la hora detrás, como siempre.
         */
        idEnUso: ((String) -> Boolean)? = null
    ): Proyecto? = runCatching {
        val sufijo = "-$ahora"
        fun libre(id: String, enUso: Boolean) = if (idEnUso == null || enUso) "$id$sufijo" else id
        val fotos = HashMap<String, String>()
        fun fotoPuesta(id: String): String? = fotos[id] ?: run {
            val bytes = contenido.imagenes[id] ?: return null
            val nuevo = libre(id, ExcalidrawStore.rutaDeImagen(context, id).exists())
            val destino = ExcalidrawStore.rutaDeImagen(context, nuevo)
            destino.writeBytes(bytes)
            fotos[id] = destino.absolutePath
            destino.absolutePath
        }
        val lienzos = HashMap<String, String>()
        val nombreDeLienzo = contenido.lienzos.keys.associateWith { id -> libre(id, File(ExcalidrawStore.rutaDe(context, id)).exists()) }
        for ((id, json) in contenido.lienzos) {
            val escena = runCatching { ExcalidrawJson.decodeFromString<Scene>(json) }.getOrNull() ?: continue
            val conFotos = escena.copy(
                files = escena.files.mapValues { (fid, f) -> f.copy(path = fotoPuesta(fid) ?: f.path) },
                // La marca de una zona señala a su sublienzo por su id, y el id cambia aquí: sin
                // esto, en el otro aparato la marca apuntaba a nada y la web salía sin sublienzos.
                elements = escena.elements.map { e ->
                    val enlace = e.enlace
                    if (enlace != null && contenido.lienzos.containsKey(enlace)) e.copy(enlace = nombreDeLienzo.getValue(enlace)) else e
                }
            )
            val nuevo = nombreDeLienzo.getValue(id)
            if (ExcalidrawStore.guardar(context, nuevo, conFotos) != null) lienzos[id] = nuevo
        }
        val croquis = HashMap<String, String>()
        for ((id, json) in contenido.croquis) {
            val nuevo = libre(id, File(context.filesDir, "croquis3d/$id.croquis.gz").exists())
            if (guardarCroquis(nuevo, json)) croquis[id] = nuevo
        }
        val tablas = HashMap<String, String>()
        val almacen = TablasEnDisco.de(context.filesDir)
        for ((id, json) in contenido.tablas) {
            val t = TablaDeCalculo.deJson(json) ?: continue
            val nuevo = libre(id, almacen.archivo(id).exists())
            // Con su id de allí, tal cual: igual que en el otro aparato, que es lo que la sincronización compara.
            if (almacen.guardar(nuevo, if (nuevo == id) t else t.copy(tocado = ahora))) tablas[id] = nuevo
        }
        var pdfRuta: String? = null
        contenido.pdf?.let { bytes ->
            carpetaDeProyectos.mkdirs()
            val destino = File(carpetaDeProyectos, "limpio$sufijo.pdf")
            destino.writeBytes(bytes)
            pdfRuta = destino.absolutePath
        }
        val p = contenido.proyecto
        val hojaNueva = p.hojas.associate { it.id to libre(it.id, idEnUso?.invoke(it.id) ?: true) }
        p.copy(
            id = if (idEnUso != null && !idEnUso(p.id)) p.id else "pr$sufijo",
            tocado = ahora,
            pdfOrigen = pdfRuta,
            pdfLimpio = pdfRuta,
            hojas = p.hojas.map { h ->
                h.copy(
                    id = hojaNueva.getValue(h.id),
                    padre = h.padre?.let { hojaNueva[it] ?: "$it$sufijo" },
                    dibujo = h.dibujo?.let { lienzos[it] },
                    croquis = h.croquis?.let { croquis[it] },
                    nota = h.nota ?: contenido.notas[h.id],
                    tabla = h.tabla?.let { tablas[it] }
                )
            },
            croquis = p.croquis.mapNotNull { croquis[it] }
        )
    }.getOrNull()

    private fun escaparJson(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Los bytes de un `.gz` del teléfono como texto, para quien tenga que dar un croquis. */
    fun textoDeGz(archivo: File): String? = runCatching {
        GZIPInputStream(archivo.inputStream()).use { it.readBytes().decodeToString() }
    }.getOrNull()
}
