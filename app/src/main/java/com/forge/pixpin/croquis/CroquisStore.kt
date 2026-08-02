package com.forge.pixpin.croquis

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * El croquis se guarda en **su propio archivo**, no dentro del `PinState`.
 *
 * Un `PinState` se lee entero al arrancar la aplicación para restaurar los
 * pines: meterle cientos de entidades lo volvería caro justo en el momento en
 * el que más importa arrancar rápido. Mismo trato que reciben las imágenes en
 * `ImageStore` y los archivos en `FileStore`.
 */
object CroquisStore {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(context: Context): File =
        File(context.filesDir, "pins/croquis").apply { mkdirs() }

    /**
     * Dónde vive el croquis de un pin dado.
     *
     * El archivo se nombra por el id del pin, así que la ruta es **deducible**
     * y no hay que devolvérsela a nadie. Importa porque el editor se lanza
     * desde el overlay, que no recibe resultados de actividad: si la ruta
     * hubiera que propagarla, la calibración se perdería al cerrar.
     */
    fun rutaDe(context: Context, id: String): String =
        File(dir(context), "$id.json").absolutePath

    /** Escribe el croquis y devuelve su ruta, o null si no se pudo. */
    fun guardar(context: Context, id: String, croquis: Croquis): String? = runCatching {
        val destino = File(dir(context), "$id.json")
        // Se escribe a un temporal y se renombra: si el proceso muere a mitad
        // —cosa que en esta aplicación pasa, porque los fabricantes agresivos
        // matan el servicio— el croquis anterior sigue entero en vez de quedar
        // un JSON truncado que ya no abre.
        val temporal = File(dir(context), "$id.json.tmp")
        temporal.writeText(json.encodeToString(croquis))
        if (destino.exists()) destino.delete()
        if (!temporal.renameTo(destino)) return null
        destino.absolutePath
    }.getOrNull()

    fun cargar(path: String?): Croquis? = runCatching {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null
        json.decodeFromString<Croquis>(file.readText())
    }.getOrNull()

    fun borrar(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }
}
