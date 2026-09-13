package com.forge.pixpin.motor

import java.io.File

/**
 * **Dónde viven las tablas**: un JSON por tabla en `files/tablas/`, con el nombre de su
 * identificador. Solo `java.io`, sin Android, así que se prueba y se usa igual en la JVM.
 *
 * Se escribe en un archivo aparte y luego se renombra encima: si el teléfono se apaga a mitad
 * de guardar, queda la versión de antes entera y no media tabla.
 */
class TablasEnDisco(private val carpeta: File) {

    fun archivo(id: String): File = File(carpeta, limpio(id) + ".json")

    fun cargar(id: String): TablaDeCalculo? {
        val f = archivo(id)
        if (!f.exists()) return null
        return runCatching { TablaDeCalculo.deJson(f.readText()) }.getOrNull()
    }

    fun guardar(id: String, tabla: TablaDeCalculo): Boolean = runCatching {
        carpeta.mkdirs()
        val destino = archivo(id)
        val temporal = File(carpeta, limpio(id) + ".tmp")
        temporal.writeText(TablaDeCalculo.aJson(tabla))
        if (!temporal.renameTo(destino)) {
            destino.delete()
            check(temporal.renameTo(destino))
        }
    }.isSuccess

    fun borrar(id: String): Boolean = archivo(id).delete()

    /** Lo que hay guardado, lo más reciente primero: identificador y tabla. */
    fun todas(): List<Pair<String, TablaDeCalculo>> =
        (carpeta.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                runCatching { TablaDeCalculo.deJson(f.readText()) }.getOrNull()?.let { id to it }
            }
            .sortedByDescending { it.second.tocado }

    companion object {
        fun de(filesDir: File) = TablasEnDisco(File(filesDir, "tablas"))

        private val RARO = Regex("[^A-Za-z0-9._-]")
        private fun limpio(id: String) = RARO.replace(id, "_")
    }
}
