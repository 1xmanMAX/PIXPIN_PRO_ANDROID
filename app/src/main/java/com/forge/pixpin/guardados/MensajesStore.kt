package com.forge.pixpin.guardados

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Dónde viven los mensajes guardados.
 *
 * ## Se añade una línea, no se reescribe el archivo
 *
 * Un archivo JSON con todo dentro hay que **volver a escribirlo entero** cada vez que se
 * añade algo, y aquí dentro va texto largo: una nota de mil palabras, el contenido de un
 * markdown compartido. Guardar la línea número doscientos costaría reescribir las
 * doscientas, y eso se nota justo cuando más molesta — al mandar algo deprisa.
 *
 * Así que es un archivo de **una línea por mensaje**: añadir es escribir al final y ya.
 * Solo se reescribe entero al borrar o al editar, que son gestos raros y lentos por
 * naturaleza. Es lo mismo que hace cualquier registro de eventos, y por lo mismo.
 *
 * ## Y una línea rota no se lleva por delante el resto
 *
 * Si una línea no se puede leer —una versión antigua, un guardado a medias por quedarse
 * sin batería— se salta esa y se siguen leyendo las demás. Con un JSON único, esa misma
 * línea rota dejaría **todo** ilegible, que es la diferencia entre perder una cosa y
 * perderlo todo.
 */
class MensajesStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val archivo: File get() = File(context.filesDir, "guardados.jsonl")

    /** La carpeta de los adjuntos: copias propias, para que no se las lleve nadie. */
    fun carpetaDeAdjuntos(): File =
        File(context.filesDir, "guardados").apply { mkdirs() }

    /** Todo lo guardado, en el orden en que se escribió. */
    fun leer(): List<Mensaje> {
        val f = archivo
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { linea ->
            if (linea.isBlank()) null
            else runCatching { json.decodeFromString<Mensaje>(linea) }.getOrNull()
        }
    }

    /** Añade uno. Es una línea al final del archivo: no toca lo que ya había. */
    fun anadir(mensaje: Mensaje) {
        runCatching {
            archivo.appendText(json.encodeToString(Mensaje.serializer(), mensaje) + "\n")
        }
    }

    /**
     * Reescribe la lista entera.
     *
     * Para borrar, fijar y editar. Se escribe en un archivo aparte y se cambia al final:
     * quedarse a medias aquí significaría perder todo lo guardado, y eso no puede pasar
     * por cerrar la aplicación en mal momento.
     */
    fun reescribir(mensajes: List<Mensaje>) {
        runCatching {
            val temporal = File(context.filesDir, "guardados.jsonl.nuevo")
            temporal.writeText(
                mensajes.joinToString("") {
                    json.encodeToString(Mensaje.serializer(), it) + "\n"
                }
            )
            // **Renombrar, no copiar.** `copyTo` escribe el archivo entero por segunda
            // vez y, sobre todo, **no es atómico**: morir a mitad de la copia deja
            // `guardados.jsonl` truncado, que es justo lo que el archivo aparte venía a
            // evitar. `renameTo` es un solo paso del sistema de archivos: o está lo
            // viejo o está lo nuevo, nunca medio archivo.
            if (!temporal.renameTo(archivo)) {
                // Renombrar puede fallar si el destino está en otro sistema de archivos;
                // aquí los dos están en `filesDir`, pero si un día dejan de estarlo, más
                // vale una copia que perder lo guardado.
                temporal.copyTo(archivo, overwrite = true)
                temporal.delete()
            }
        }
    }

    /**
     * Copia un archivo de fuera a la carpeta propia y devuelve la ruta.
     *
     * **Copiar y no apuntar** a donde estaba: lo que se guarda tiene que seguir ahí dentro
     * de un mes, y una ruta a la galería o a descargas se rompe en cuanto el usuario
     * ordena su móvil. Guardar una referencia que se puede evaporar es no guardar nada.
     */
    fun copiarAdjunto(origen: File, nombre: String): String? = runCatching {
        val destino = File(carpetaDeAdjuntos(), "${System.currentTimeMillis()}_$nombre")
        origen.copyTo(destino, overwrite = true)
        destino.absolutePath
    }.getOrNull()

    /** Borra el archivo de un mensaje. Solo al borrar el mensaje, no antes. */
    fun borrarAdjunto(ruta: String?) {
        if (ruta.isNullOrBlank()) return
        // Solo lo que es nuestro: un dibujo del lienzo o una página de un PDF viven en su
        // sitio y los usa alguien más. Borrar ahí sería llevarse el original.
        if (!ruta.contains("/guardados/")) return
        runCatching { File(ruta).delete() }
    }
}
