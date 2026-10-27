package com.forge.pixpin.sincro

/**
 * **En qué orden se sincroniza** (27-oct-2026). El usuario: «añade un listado de prioridades al
 * sincronizar, por defecto: 1) las listas de tareas, primero y lo más rápido; 2) los chats
 * compartidos; 3) los archivos (PDF y demás); 4) los lienzos, textos, notas y lo demás; 5) al
 * final lo pesado: vídeos y grabaciones. Dentro de cada nivel, y para los grupos (una grabación =
 * su grupo de ficheros, su peso total), lo más ligero primero, para avanzar más rápido».
 *
 * Por qué importa: **una vuelta se puede cortar** (la Wi-Fi del teléfono que se duerme, la
 * aplicación que se cierra). Lo que haya pasado hasta entonces queda bien puesto en los dos lados
 * —cada cosa va por su lado y se escribe entera o nada—, así que lo que va primero es lo que llega
 * siempre: una casilla tachada no puede esperar detrás de un vídeo de 300 MB.
 *
 * La vuelta ([Sesion.vuelta]) va en tres tiempos: **los mensajes de todos los chats** (en el orden
 * de [ordenarChats]: los que tienen listas de tareas, los compartidos y luego del más ligero al más
 * pesado), **los archivos de todos juntos** (en el de [ordenar]) y al final lo acordado de cada
 * uno. Solo cambia el orden de las peticiones: el otro aparato —el PC también— contesta cada una
 * por su lado, como siempre.
 *
 * Puro y sin Android: se prueba en `PrioridadTest`.
 */
object Prioridad {

    /** Los niveles, del primero al último. */
    enum class Nivel { TAREAS, COMPARTIDOS, ARCHIVOS, LIENZOS_Y_NOTAS, PESADO }

    /** Vídeo y audio: lo pesado, al final. */
    private val PESADOS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3gpp",
        "m4a", "mp3", "wav", "ogg", "oga", "opus", "aac", "flac", "amr", "wma"
    )

    /** Documentos y fotos: «los archivos (PDF y demás)». */
    private val DOCUMENTOS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "csv", "txt",
        "zip", "png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "bmp", "svg", "tif", "tiff", "dwg", "dxf"
    )

    /** El nivel de un archivo solo por lo que es (sin mirar su chat). */
    fun nivelDe(rel: String): Nivel {
        val nombre = rel.substringAfterLast('/').lowercase()
        val ext = nombre.removeSuffix(".gz").substringAfterLast('.', "")
        return when {
            ext in PESADOS -> Nivel.PESADO
            nombre.endsWith(".excalidraw.gz") || nombre.endsWith(".croquis.gz") || rel.startsWith("tablas/") ||
                rel.startsWith("notas/") || ext == "md" || ext == "json" -> Nivel.LIENZOS_Y_NOTAS
            ext in DOCUMENTOS -> Nivel.ARCHIVOS
            else -> Nivel.LIENZOS_Y_NOTAS
        }
    }

    /**
     * Lo que se sabe de un archivo para ordenarlo: su ruta, lo que pesa (el mayor de los dos lados),
     * su grupo (una grabación, un mensaje con su adjunto y lo anotado; null: va solo), si lo nombra
     * una lista de tareas y si es de un chat compartido.
     */
    data class Pieza(
        val rel: String,
        val bytes: Long,
        val grupo: String? = null,
        val deTareas: Boolean = false,
        val compartido: Boolean = false
    )

    /**
     * El nivel de una pieza: lo pesado es pesado siempre (un vídeo de una lista de tareas o de un
     * chat compartido sigue yendo al final); lo demás sube a [Nivel.TAREAS] o [Nivel.COMPARTIDOS]
     * si es de eso.
     */
    fun nivel(p: Pieza): Nivel {
        val propio = nivelDe(p.rel)
        return when {
            propio == Nivel.PESADO -> Nivel.PESADO
            p.deTareas -> Nivel.TAREAS
            p.compartido -> Nivel.COMPARTIDOS
            else -> propio
        }
    }

    /**
     * **El orden de los archivos.** Cada grupo va junto, en el nivel de su pieza más tardía (una
     * grabación con su vídeo es pesada entera) y pesando lo que pesan todas; los grupos de un
     * nivel, del más ligero al más pesado; dentro de un grupo, también lo ligero primero. A igual
     * peso, por nombre, para que el orden no dependa del azar.
     */
    fun <T> ordenar(cosas: List<T>, pieza: (T) -> Pieza): List<T> {
        if (cosas.size < 2) return cosas
        val piezas = cosas.map { it to pieza(it) }
        fun claveDeGrupo(p: Pieza) = p.grupo ?: ("\u0000" + p.rel)
        val porGrupo = piezas.groupBy { claveDeGrupo(it.second) }
        val nivelDelGrupo = porGrupo.mapValues { (_, l) -> l.maxOf { nivel(it.second).ordinal } }
        val pesoDelGrupo = porGrupo.mapValues { (_, l) -> l.sumOf { it.second.bytes.coerceAtLeast(0) } }
        return piezas.sortedWith(
            compareBy<Pair<T, Pieza>>(
                { nivelDelGrupo.getValue(claveDeGrupo(it.second)) },
                { pesoDelGrupo.getValue(claveDeGrupo(it.second)) },
                { claveDeGrupo(it.second) },
                { it.second.bytes },
                { it.second.rel }
            )
        ).map { it.first }
    }

    /**
     * **El orden de los chats** para sus mensajes: primero los que tienen listas de tareas, luego
     * los compartidos con otras personas y luego los demás, del más ligero ([peso]: cuántos
     * mensajes) al más pesado. A igualdad, por id.
     */
    fun ordenarChats(chats: Collection<String>, conTareas: Set<String>, compartidos: Set<String>, peso: (String) -> Long = { 0 }): List<String> =
        chats.distinct().sortedWith(compareBy({
            when (it) { in conTareas -> 0; in compartidos -> 1; else -> 2 }
        }, { peso(it) }, { it }))
}
