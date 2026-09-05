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
 * | `documento.pdf`                 | El PDF del proyecto, si lo hay (el limpio, sin anotar). |
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
        escrito: Long = System.currentTimeMillis()
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
        val pdf: ByteArray?
    )

    /** Abre un paquete y devuelve lo que trae, o null si no es un `.pixpin`. */
    fun abrir(archivo: File): Contenido? = runCatching {
        var proyecto: Proyecto? = null
        val lienzos = HashMap<String, String>()
        val imagenes = HashMap<String, ByteArray>()
        val croquis = HashMap<String, String>()
        val notas = HashMap<String, String>()
        var pdf: ByteArray? = null
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
                }
                zip.closeEntry()
            }
        }
        val p = proyecto ?: return null
        if (!esPixpin) return null
        Contenido(p, lienzos, imagenes, croquis, notas, pdf)
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
        guardarCroquis: (String, String) -> Boolean = { _, _ -> false }
    ): Proyecto? = runCatching {
        val sufijo = "-$ahora"
        val fotos = HashMap<String, String>()
        fun fotoPuesta(id: String): String? = fotos[id] ?: run {
            val bytes = contenido.imagenes[id] ?: return null
            val nuevo = "$id$sufijo"
            val destino = ExcalidrawStore.rutaDeImagen(context, nuevo)
            destino.writeBytes(bytes)
            fotos[id] = destino.absolutePath
            destino.absolutePath
        }
        val lienzos = HashMap<String, String>()
        for ((id, json) in contenido.lienzos) {
            val escena = runCatching { ExcalidrawJson.decodeFromString<Scene>(json) }.getOrNull() ?: continue
            val conFotos = escena.copy(
                files = escena.files.mapValues { (fid, f) -> f.copy(path = fotoPuesta(fid) ?: f.path) }
            )
            val nuevo = "$id$sufijo"
            if (ExcalidrawStore.guardar(context, nuevo, conFotos) != null) lienzos[id] = nuevo
        }
        val croquis = HashMap<String, String>()
        for ((id, json) in contenido.croquis) {
            val nuevo = "$id$sufijo"
            if (guardarCroquis(nuevo, json)) croquis[id] = nuevo
        }
        var pdfRuta: String? = null
        contenido.pdf?.let { bytes ->
            carpetaDeProyectos.mkdirs()
            val destino = File(carpetaDeProyectos, "limpio$sufijo.pdf")
            destino.writeBytes(bytes)
            pdfRuta = destino.absolutePath
        }
        val p = contenido.proyecto
        p.copy(
            id = "pr$sufijo",
            tocado = ahora,
            pdfOrigen = pdfRuta,
            pdfLimpio = pdfRuta,
            hojas = p.hojas.map { h ->
                h.copy(
                    id = "${h.id}$sufijo",
                    dibujo = h.dibujo?.let { lienzos[it] },
                    croquis = h.croquis?.let { croquis[it] },
                    nota = h.nota ?: contenido.notas[h.id]
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
