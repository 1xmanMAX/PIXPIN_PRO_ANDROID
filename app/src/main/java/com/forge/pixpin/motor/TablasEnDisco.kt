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
        enMensajes?.takeIf { TablasEnMensajes.esSuya(id) }?.leer(carpeta, id)?.let { texto ->
            TablaDeCalculo.deJson(texto)?.let { return it }
        }
        val f = archivo(id)
        if (!f.exists()) return null
        return runCatching { TablaDeCalculo.deJson(f.readText()) }.getOrNull()
    }

    fun guardar(id: String, tabla: TablaDeCalculo): Boolean {
        val chat = enMensajes?.takeIf { TablasEnMensajes.esSuya(id) }
        if (chat != null && chat.escribir(carpeta, id, tabla)) return true
        return enArchivo(id, tabla)
    }

    /**
     * **Solo en el archivo**, aunque la tabla sea de un mensaje: lo que se lee de un Excel por
     * primera vez es una copia de trabajo, no una edición, y no tiene que llenar el chat de
     * mensajes (en el PC nacen al guardar lo editado, igual).
     */
    fun enArchivo(id: String, tabla: TablaDeCalculo): Boolean = runCatching {
        carpeta.mkdirs()
        val destino = archivo(id)
        val temporal = File(carpeta, limpio(id) + ".tmp")
        // Lo que no se conoce del archivo de antes (un campo del PC) se queda: ver [TablaDeCalculo.aJsonSobre].
        val antes = if (destino.exists()) runCatching { destino.readText() }.getOrNull() else null
        temporal.writeText(TablaDeCalculo.aJsonSobre(tabla, antes))
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
        /**
         * **Las tablas que viven en un mensaje del chat** (13-oct-2026, como el PC): las hojas
         * editadas de un Excel del chat (`libro-<mensaje>-<hoja>`) y las mini-apps `tabla`
         * (`mini-<mensaje>`). La aplicación lo pone al arrancar; sin él (las pruebas de la JVM,
         * la sincronización, que solo mira [archivo]) todo es archivo, como antes.
         */
        @Volatile var enMensajes: TablasEnMensajes? = null

        fun de(filesDir: File) = TablasEnDisco(File(filesDir, "tablas"))

        private val RARO = Regex("[^A-Za-z0-9._-]")
        private fun limpio(id: String) = RARO.replace(id, "_")
    }
}

/**
 * Dónde leer y escribir las tablas que son mensajes del chat. Ver [TablasEnDisco.enMensajes].
 * [carpeta] es la de las tablas que pregunta: quien lo implementa solo contesta por la suya.
 */
interface TablasEnMensajes {
    /** El JSON de la tabla [id], o null si no hay mensaje (entonces se mira el archivo). */
    fun leer(carpeta: File, id: String): String?

    /** Guarda [tabla] en su mensaje (creándolo si toca). False: no es de ningún mensaje, va a archivo. */
    fun escribir(carpeta: File, id: String, tabla: TablaDeCalculo): Boolean

    companion object {
        /** Las mini-apps `tabla` del chat se abren con este prefijo y el id del mensaje. */
        const val MINI = "mini-"
        const val LIBRO = "libro-"

        fun esSuya(id: String) = id.startsWith(MINI) || id.startsWith(LIBRO)
    }
}
