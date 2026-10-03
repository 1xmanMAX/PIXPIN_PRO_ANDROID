package com.forge.pixpin.lecciones

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * **Dónde viven las lecciones**: un archivo por lección y un mensaje del chat que lo señala.
 *
 * Se eligió así tras mirar cómo guarda PixPin lo demás (ver `docs/lecciones.md`):
 * - La sincronización va **por chats**: cada chat lleva sus mensajes y los archivos que esos
 *   mensajes nombran. Un tipo de dato nuevo con su propio canal obligaba a tocar el protocolo y
 *   la aplicación de Windows. Un mensaje con un adjunto en `guardados/` viaja hoy, sin tocar nada.
 * - Borrar el mensaje deja su lápida y se lleva el archivo: una lección borrada no resucita.
 * - Entra en las copias de seguridad, en el `.pixpin` y en el buscador del teléfono
 *   (`atajos/IndiceDelBuscador`), que ya recogen los archivos del chat.
 * - Una lección de un proyecto va en el chat de ese proyecto, como decidió el usuario.
 *
 * El archivo es la verdad; el mensaje lleva un resumen legible (para Windows, para versiones
 * viejas y para el propio chat). La lista vive en memoria ([todas]) y se recarga cuando el chat
 * cambia, venga el cambio de aquí o de la sincronización.
 */
class LeccionesStore(context: Context) {
    private val app = context.applicationContext
    private val mensajes = MensajesStore(app)

    /** Una lección con el mensaje que la lleva (de él sale el proyecto). */
    class Entrada(val leccion: Leccion, val mensaje: Mensaje) {
        val proyecto: String? get() = mensaje.proyecto
        val indice: Buscador.Indice by lazy { Buscador.Indice(leccion) }
    }

    fun carpeta(): File = File(mensajes.carpetaDeAdjuntos(), "lecciones").apply { mkdirs() }

    /** Lee todas y deja la lista al día. Trabajo de disco. */
    fun recargar(): List<Entrada> = synchronized(CERROJO) {
        val antes = CACHE
        val nueva = HashMap<String, Pair<Long, Leccion>>()
        val salida = ArrayList<Entrada>()
        for (m in mensajes.leer()) {
            if (!esLeccion(m)) continue
            val ruta = m.ruta ?: continue
            val f = File(ruta)
            val cuando = f.lastModified()
            val l = antes[ruta]?.takeIf { it.first == cuando }?.second
                ?: runCatching { Leccion.leer(f.readText()) }.getOrNull()
                ?: continue
            nueva[ruta] = cuando to l
            salida += Entrada(l, m)
        }
        CACHE = nueva
        // **Una lección, una tarjeta** (4-oct-2026): si su mensaje quedó dos veces en el chat
        // —dos guardados a la vez, o lo mismo llegado por dos caminos al sincronizar—, la lista
        // tenía la misma clave dos veces y la pantalla de lecciones se cerraba al abrirla.
        val ordenadas = salida.sortedByDescending { it.leccion.tocada }.distinctBy { it.leccion.id }
        _todas.value = ordenadas
        ordenadas
    }

    /**
     * Guarda [l]. La primera vez crea su mensaje en el chat de [proyecto] (o en el general); las
     * siguientes reescribe el archivo y pone al día el resumen del mensaje.
     */
    fun guardar(l: Leccion, proyecto: String?): Leccion = guardar(l, proyecto, emptyList(), emptyList())

    /**
     * Guarda [leccion] con sus fotos y audios: [nuevos] son los mensajes recién hechos (foto o
     * voz, sin chat todavía) y [quitados] los ids de los que ya no van. Los nuevos se meten en
     * el chat de la lección **respondiéndola**, después de ella; los quitados se borran del chat
     * con su archivo.
     */
    fun guardar(leccion: Leccion, proyecto: String?, nuevos: List<Mensaje>, quitados: List<String>): Leccion =
        synchronized(GUARDANDO) { guardarYa(leccion, proyecto, nuevos, quitados) }

    /** Mirar si ya tiene mensaje y crearlo va junto: dos guardados a la vez no hacen dos mensajes. */
    private fun guardarYa(leccion: Leccion, proyecto: String?, nuevos: List<Mensaje>, quitados: List<String>): Leccion {
        val l = leccion.copy(adjuntos = (leccion.adjuntos - quitados.toSet() + nuevos.map { it.id }).distinct())
        val f = File(carpeta(), l.id + EXTENSION)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(Leccion.escribir(l))
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        val ruta = f.absolutePath
        val existente = mensajes.leer().firstOrNull { it.ruta == ruta || it.id == PREFIJO + l.id }
        if (existente == null) {
            mensajes.anadir(
                Mensaje(
                    id = PREFIJO + l.id,
                    cuando = l.creada,
                    clase = Clase.ARCHIVO,
                    texto = resumen(l),
                    ruta = ruta,
                    nombre = nombreDe(l),
                    bytes = f.length(),
                    proyecto = proyecto
                )
            )
        } else {
            mensajes.actualizar(existente.id) { it.copy(texto = resumen(l), nombre = nombreDe(l), bytes = f.length()) }
        }
        val idDelMensaje = existente?.id ?: (PREFIJO + l.id)
        val chat = existente?.proyecto ?: proyecto
        nuevos.forEach { mensajes.anadir(it.copy(proyecto = chat, respondeA = idDelMensaje)) }
        if (quitados.isNotEmpty()) quitar(quitados.toSet())
        recargar()
        return l
    }

    /** Los mensajes de las fotos y audios de [l], en su orden. */
    fun adjuntosDe(l: Leccion): List<Mensaje> {
        if (l.adjuntos.isEmpty()) return emptyList()
        val porId = mensajes.leer().associateBy { it.id }
        return l.adjuntos.mapNotNull { porId[it] }
    }

    private fun quitar(ids: Set<String>) {
        val todos = mensajes.leer()
        val van = todos.filter { it.id in ids }
        if (van.isEmpty()) return
        mensajes.reescribir(todos.filter { it.id !in ids })
        van.forEach { mensajes.borrarAdjunto(it) }
    }

    /** Borra la lección: su mensaje (con lápida, para que no vuelva al sincronizar) y su archivo. */
    fun borrar(e: Entrada) {
        // Sus fotos y audios se van con ella.
        quitar(e.leccion.adjuntos.toSet())
        mensajes.reescribir(mensajes.leer().filter { it.id != e.mensaje.id })
        mensajes.borrarAdjunto(e.mensaje)
        recargar()
    }

    companion object {
        const val EXTENSION = ".leccion"
        private const val PREFIJO = "lec-"
        private val CERROJO = Any()
        private val GUARDANDO = Any()
        @Volatile private var CACHE: Map<String, Pair<Long, Leccion>> = emptyMap()
        private val _todas = MutableStateFlow<List<Entrada>>(emptyList())

        /** Todas las lecciones, de la más tocada a la menos. Se llena con [recargar]. */
        val todas: StateFlow<List<Entrada>> = _todas

        fun esLeccion(m: Mensaje): Boolean = m.clase == Clase.ARCHIVO && m.ruta?.endsWith(EXTENSION) == true

        /**
         * [mensajes] sin las lecciones ni sus fotos y audios: lo que enseña el chat. Las
         * lecciones se ven en su sección (Proyectos → 💡), no mezcladas con lo guardado.
         */
        fun sinLecciones(mensajes: List<Mensaje>): List<Mensaje> {
            val lecciones = mensajes.mapNotNullTo(HashSet()) { m -> m.id.takeIf { esLeccion(m) } }
            if (lecciones.isEmpty()) return mensajes
            return mensajes.filter { it.id !in lecciones && (it.respondeA == null || it.respondeA !in lecciones) }
        }

        fun nuevoId(ahora: Long = System.currentTimeMillis()): String =
            ahora.toString(36) + (0..2).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")

        fun nombreDe(l: Leccion): String = "💡 " + l.titulo.take(80)

        /** Lo que se lee en el chat, en Windows y en las versiones que no conocen las lecciones. */
        fun resumen(l: Leccion): String = buildString {
            append(when (l.tipo) { Leccion.TIPO_ERROR -> "⚠️ Error que no repetir"; Leccion.TIPO_ACIERTO -> "✅ Lo que funcionó"; else -> "💡 Lección" })
            append(": ").append(l.titulo)
            if (l.quePaso.isNotBlank()) append("\nQué pasó: ").append(l.quePaso)
            if (l.porQue.isNotBlank()) append("\nPor qué: ").append(l.porQue)
            if (l.proxima.isNotBlank()) append("\nLa próxima vez: ").append(l.proxima)
            if (l.repeticiones.isNotEmpty()) append("\n🔁 Pasó ").append(l.vecesQuePaso).append(" veces")
            if (l.adjuntos.isNotEmpty()) append("\n📎 ").append(l.adjuntos.size).append(if (l.adjuntos.size == 1) " adjunto" else " adjuntos")
            val etiquetas = l.todasLasEtiquetas
            if (etiquetas.isNotEmpty()) append("\n").append(etiquetas.joinToString(" ") { "#$it" })
        }
    }
}
