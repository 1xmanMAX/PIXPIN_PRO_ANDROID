package com.forge.pixpin.lecciones

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
        // La que espera su «Deshacer» no se enseña aunque su archivo siga ahí.
        val fuera = _borrando.value?.leccion?.id
        _todas.value = if (fuera == null) ordenadas else ordenadas.filter { it.leccion.id != fuera }
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

    /**
     * Los mensajes de las fotos y audios de una lección: los de su lista y **también los que le
     * responden** —si dos aparatos le pusieron una foto cada uno, la lista del archivo solo trae la
     * de uno, pero las dos fotos le responden—. Los de la lista primero, en su orden.
     */
    fun adjuntosDe(e: Entrada): List<Mensaje> = adjuntosDe(e.leccion, e.mensaje.id)

    fun adjuntosDe(l: Leccion, idDelMensaje: String = PREFIJO + l.id): List<Mensaje> {
        val todos = mensajes.leer()
        val porId = todos.associateBy { it.id }
        val deLaLista = l.adjuntos.mapNotNull { porId[it] }
        val vistos = deLaLista.mapTo(HashSet()) { it.id }
        return deLaLista + todos.filter { it.respondeA == idDelMensaje && it.id !in vistos && esAdjunto(it) }
    }

    /** Quita del chat (con lápida) y del disco: leer y reescribir van juntos. Ver [MensajesStore.cambiar]. */
    private fun quitar(ids: Set<String>) {
        if (ids.isEmpty()) return
        var van = emptyList<Mensaje>()
        mensajes.cambiar { todos -> van = todos.filter { it.id in ids }; todos.filter { it.id !in ids } }
        van.forEach { mensajes.borrarAdjunto(it) }
    }

    /**
     * Borra la lección: su mensaje (con lápida, para que no vuelva al sincronizar), su archivo y sus
     * fotos y audios. Bajo el mismo cerrojo que guardar: un guardado a la vez no la resucita.
     */
    fun borrar(e: Entrada) = synchronized(GUARDANDO) {
        quitar(adjuntosDe(e).mapTo(HashSet()) { it.id } + e.mensaje.id)
        recargar()
    }

    /**
     * **Borrar con Deshacer** (lo trae el PC, v2): se quita de la vista ya y se borra de verdad al
     * acabar el plazo ([PARA_DESHACER]) o al salir de la lista ([borrarYa]). Si ya había otra
     * esperando, esa se borra ahora. El disco, en [scope] y fuera del hilo que pinta.
     */
    fun borrarConDeshacer(e: Entrada, scope: CoroutineScope) {
        val antes = _borrando.value
        _borrando.value = e
        _todas.value = _todas.value.filter { it.leccion.id != e.leccion.id }
        scope.launch(Dispatchers.IO) {
            if (antes != null && antes.leccion.id != e.leccion.id) runCatching { borrar(antes) }
            delay(PARA_DESHACER)
            // Solo si sigue siendo esta la que espera: Deshacer o un borrado después la sueltan.
            if (_borrando.compareAndSet(e, null)) runCatching { borrar(e) }
        }
    }

    /** Borra ya la que esperaba su «Deshacer» (al salir de la lista). */
    fun borrarYa(scope: CoroutineScope) {
        val e = _borrando.value ?: return
        if (_borrando.compareAndSet(e, null)) scope.launch(Dispatchers.IO) { runCatching { borrar(e) } }
    }

    /** **Deshacer**: la que iba a borrarse vuelve a la lista tal cual estaba. */
    fun deshacer(scope: CoroutineScope) {
        if (_borrando.value == null) return
        _borrando.value = null
        scope.launch(Dispatchers.IO) { runCatching { recargar() } }
    }

    companion object {
        const val EXTENSION = ".leccion"
        /** Lo que espera un borrado para poder deshacerse: lo mismo que en el PC. */
        const val PARA_DESHACER = 6_000L
        private val _borrando = MutableStateFlow<Entrada?>(null)

        /** La lección que se acaba de borrar y aún puede volver con «Deshacer». */
        val borrando: StateFlow<Entrada?> = _borrando
        private const val PREFIJO = "lec-"
        private val CERROJO = Any()
        private val GUARDANDO = Any()
        @Volatile private var CACHE: Map<String, Pair<Long, Leccion>> = emptyMap()
        private val _todas = MutableStateFlow<List<Entrada>>(emptyList())

        /** Todas las lecciones, de la más tocada a la menos. Se llena con [recargar]. */
        val todas: StateFlow<List<Entrada>> = _todas

        fun esLeccion(m: Mensaje): Boolean = m.clase == Clase.ARCHIVO && m.ruta?.endsWith(EXTENSION) == true

        /** Una foto o un audio: lo que una lección lleva colgado. */
        private fun esAdjunto(m: Mensaje): Boolean = (m.clase == Clase.IMAGEN || m.clase == Clase.VOZ) && !m.enBuzon

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
