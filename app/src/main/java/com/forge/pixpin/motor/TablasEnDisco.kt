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

    /**
     * **La copia de trabajo** de una hoja de un Excel leída y aún sin editar (18-oct-2026, punto 5
     * de `docs/manual-hojas-android.md` del PC): en una carpeta aparte, **que no viaja**. Antes iba
     * a `tablas/libro-….json`, viajaba, y el PC la tomaba por una hoja editada en el móvil (y aquí
     * se migraba a mensajes como si se hubiera editado). Lo editado va a mensajes ([enMensajes]).
     */
    fun leida(id: String): File = File(carpetaDeLeidas, limpio(id) + ".json")

    private val carpetaDeLeidas: File get() = File(carpeta.absoluteFile.parentFile ?: carpeta, carpeta.name + "-leidas")

    /** Lo que hay guardado con el id [id]. Ver [leer]. */
    sealed interface Lectura {
        data class Hay(val tabla: TablaDeCalculo) : Lectura
        /** No hay nada con ese id: se puede empezar en blanco. */
        data object NoHay : Lectura
        /** **Hay algo y no es una tabla** (ni un objeto JSON): no se abre en blanco ni se guarda encima. */
        data object NoSeLee : Lectura
    }

    /**
     * La tabla [id], distinguiendo «no existe» de «existe pero no se lee» (punto 1 del manual del
     * PC): por orden, su mensaje del chat, su archivo y la copia de trabajo ([leida]). Un archivo
     * viejo roto de una hoja de libro cede ante la copia de trabajo de leer el libro, como en el PC
     * (`una_hoja_vieja_rota_no_se_pierde_y_se_lee_el_libro`): el archivo sigue tal cual.
     */
    fun leer(id: String): Lectura {
        enMensajes?.takeIf { TablasEnMensajes.esSuya(id) }?.leer(carpeta, id)?.takeIf { it.isNotBlank() }?.let { texto ->
            return TablaDeCalculo.deJson(texto)?.let { Lectura.Hay(it) } ?: Lectura.NoSeLee
        }
        val delArchivo = deArchivo(archivo(id))
        if (delArchivo is Lectura.Hay) return delArchivo
        val deTrabajo = deArchivo(leida(id))
        return if (deTrabajo is Lectura.Hay) deTrabajo else if (delArchivo == Lectura.NoSeLee || deTrabajo == Lectura.NoSeLee) Lectura.NoSeLee else Lectura.NoHay
    }

    private fun deArchivo(f: File): Lectura {
        if (!f.exists()) return Lectura.NoHay
        val texto = runCatching { f.readText() }.getOrNull() ?: return Lectura.NoSeLee
        return TablaDeCalculo.deJson(texto)?.let { Lectura.Hay(it) } ?: Lectura.NoSeLee
    }

    fun cargar(id: String): TablaDeCalculo? = (leer(id) as? Lectura.Hay)?.tabla

    /**
     * Guarda [tabla] con el id [id]. **No pisa lo que no se lee**: un mensaje o un archivo con algo
     * que no es una tabla se queda como está, y devuelve false.
     */
    fun guardar(id: String, tabla: TablaDeCalculo): Boolean {
        val chat = enMensajes?.takeIf { TablasEnMensajes.esSuya(id) }
        val texto = chat?.leer(carpeta, id)
        if (!texto.isNullOrBlank() && TablaDeCalculo.deJson(texto) == null) return false
        if (chat != null && chat.escribir(carpeta, id, tabla)) return true
        if (deArchivo(archivo(id)) == Lectura.NoSeLee) return false
        return enArchivo(id, tabla)
    }

    /**
     * **La copia de trabajo** ([leida]) de lo que se lee de un Excel por primera vez: no es una
     * edición, y no tiene que llenar el chat de mensajes ni viajar (en el PC nacen al guardar lo
     * editado, igual).
     */
    fun deTrabajo(id: String, tabla: TablaDeCalculo): Boolean = escribir(carpetaDeLeidas, leida(id), id, tabla)

    /** **Solo en el archivo** de `tablas/`, aunque la tabla sea de un mensaje. */
    fun enArchivo(id: String, tabla: TablaDeCalculo): Boolean = escribir(carpeta, archivo(id), id, tabla)

    private fun escribir(carpeta: File, destino: File, id: String, tabla: TablaDeCalculo): Boolean = runCatching {
        carpeta.mkdirs()
        val temporal = File(carpeta, limpio(id) + ".tmp")
        // Lo que no se conoce del archivo de antes (un campo del PC) se queda: ver [TablaDeCalculo.aJsonSobre].
        val antes = if (destino.exists()) runCatching { destino.readText() }.getOrNull() else null
        temporal.writeText(TablaDeCalculo.aJsonSobre(tabla, antes))
        if (!temporal.renameTo(destino)) {
            destino.delete()
            check(temporal.renameTo(destino))
        }
    }.isSuccess

    fun borrar(id: String): Boolean = archivo(id).delete().also { leida(id).delete() }

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
