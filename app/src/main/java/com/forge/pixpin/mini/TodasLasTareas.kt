package com.forge.pixpin.mini

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import java.io.File
import java.time.LocalDate

/**
 * **Las listas de tareas de TODOS los chats juntas** (Tareas v3 del PC, 3/4-oct-2026), sin
 * Android. Lo pinta [TodasLasTareasActivity]; aquí está lo que se puede probar sin pantalla:
 * reunir, ordenar, buscar, agrupar y las cuentas de marcar, quitar y mover.
 *
 * Es el porte de `apps/pixpin/src/tareas.rs` y `tareas/tarjetas.rs` del PC, con sus reglas y sus
 * textos. **No hay una segunda copia de las tareas**: cada lista es el documento de un mensaje
 * `MINIAPP` de `tareas` ([Tareas]), y lo que se cambia aquí es ese mensaje, que es lo que viaja.
 *
 * - **El Inbox**: lo que se apunta sin decir dónde va a la lista «Inbox» de «Mensajes guardados»
 *   (se crea si no está), como la caja de la ventana del PC y el pedido `anadir_tarea` sin
 *   `proyecto` ni `codigo`. De ahí se reparte con «Mover a…».
 * - **Antes de tocar una tarea se comprueba que sigue siendo ella** (su texto crudo en ese
 *   número): si la lista cambió en el chat o llegó cambiada del PC, se devuelve `null` y no se
 *   toca nada, igual que `tareas::marcar` del PC.
 */
object TodasLasTareas {

    /** Cómo se llama la lista adonde va todo lo apuntado sin destino. Ver `tareas::INBOX` del PC. */
    const val INBOX = "Inbox"

    /** El nombre del chat general, el que no es de ningún proyecto. */
    const val GUARDADOS = "Mensajes guardados"

    /** Lo que se queda arriba, tachada, una tarea recién marcada (`SE_QUEDA` del PC). */
    const val SE_QUEDA_MS = 5_000L

    /** Lo que dura el «Deshacer» de una tarea quitada (`DURA_DESHACER` del PC). */
    const val DURA_DESHACER_MS = 6_000L

    // ---- La lectura ------------------------------------------------------

    /** Una tarea de una lista, ya leída. */
    data class Fila(
        /** Su número en el documento (desde 0). */
        val indice: Int,
        /** El texto tal cual está guardado, con su fecha y sus imágenes: para comprobar, antes de tocarla, que sigue siendo esta. */
        val crudo: String,
        /** Lo que se enseña: sin la fecha ni las imágenes. */
        val texto: String,
        val hecha: Boolean,
        /** El día en que se creó, si lo lleva (`➕ AAAA-MM-DD`). */
        val creada: LocalDate?,
        /** Los enlaces de sus imágenes, en orden. Se resuelven con [archivoDeEnlace]. */
        val imagenes: List<String>
    )

    /** Una lista de tareas de un chat. */
    data class Lista(
        /** El proyecto del chat, o nulo para «Mensajes guardados». */
        val proyecto: String?,
        /** Cómo se llama el chat. */
        val chat: String,
        /** El `id` del mensaje. */
        val codigo: String,
        /** Su título (o el nombre del mensaje). */
        val titulo: String,
        /** Cuándo se creó el mensaje: las listas más nuevas van primero. */
        val cuando: Long,
        /** Todas sus tareas, en el orden del documento. */
        val filas: List<Fila>
    ) {
        val guardados: Boolean get() = proyecto == null

        /** Lo que la distingue de todas las demás: el chat y el mensaje. */
        val clave: String get() = "${proyecto.orEmpty()}/$codigo"

        val pendientes: Int get() = filas.count { !it.hecha }

        /**
         * Lo que se ve, en dos montones: arriba lo pendiente (ordenado con [ordenDePendiente]) y
         * aparte, plegable, lo hecho en el orden del documento. [sigueArriba] deja arriba, tachada
         * y en su sitio, una tarea recién marcada: un toque sin querer se deshace en el mismo sitio.
         */
        fun aLaVista(sigueArriba: (Fila) -> Boolean): Pair<List<Fila>, List<Fila>> {
            val (arriba, plegadas) = filas.partition { !it.hecha || sigueArriba(it) }
            return arriba.sortedWith(ORDEN_DE_PENDIENTE) to plegadas
        }
    }

    /**
     * Cómo se ordena lo pendiente: primero lo que tiene fecha, de la más vieja a la más nueva (lo
     * que más lleva esperando); detrás lo que no la tiene. A igualdad, el orden del documento.
     */
    val ORDEN_DE_PENDIENTE: Comparator<Fila> =
        compareBy<Fila> { it.creada == null }.thenBy { it.creada }.thenBy { it.indice }

    /**
     * Una imagen dentro del texto de una tarea, `![img 01](enlace)`: el enlace sin blancos ni `)`.
     * La misma regla que `mini::imagenes_de` del PC y `docs/investigacion/2026-10-03-tareas-con-
     * imagenes-android.md`. (Local a propósito: `Tareas.imagenes` la tendrá en común.)
     */
    private val IMAGEN = Regex("""!\[[^\]]*]\(([^)\s]+)\)""")
    private val BLANCOS = Regex("""\s+""")

    /** El texto sin sus imágenes (los blancos que dejan, juntados en uno) y los enlaces, en orden. */
    fun imagenesDe(texto: String): Pair<String, List<String>> {
        val enlaces = IMAGEN.findAll(texto).map { it.groupValues[1] }.toList()
        if (enlaces.isEmpty()) return texto.trim() to emptyList()
        return BLANCOS.replace(IMAGEN.replace(texto, " "), " ").trim() to enlaces
    }

    /** Cambia cada enlace de imagen por lo que diga [cambio] (nulo = se deja como estaba). */
    fun cambiarEnlaces(texto: String, cambio: (String) -> String?): String =
        IMAGEN.replace(texto) { m ->
            val viejo = m.groupValues[1]
            val nuevo = cambio(viejo) ?: return@replace m.value
            m.value.substring(0, m.value.length - viejo.length - 1) + nuevo + ")"
        }

    /** Las tareas de un documento, con su texto partido de su fecha y de sus imágenes. */
    fun filasDe(documento: String): List<Fila> =
        Tareas.leer(documento).mapIndexed { i, t ->
            val (visible, creada) = Tareas.partir(t.texto)
            val (texto, imagenes) = imagenesDe(visible)
            Fila(i, t.texto, texto, t.hecha, creada, imagenes)
        }

    fun esLista(m: Mensaje): Boolean = m.clase == Clase.MINIAPP && m.miniapp == MiniApp.TAREAS.id

    /** Cómo se llama una lista: su título, o el nombre del mensaje (`pedidos::nombre_de_lista`). */
    fun nombreDeLista(m: Mensaje): String = Cabecera.titulo(m.texto).ifEmpty { m.nombre }

    /** La lista de un mensaje, si es una lista de tareas. También vacía: es adonde mover. */
    fun listaDe(m: Mensaje, chat: String): Lista? =
        if (!esLista(m)) null
        else Lista(m.proyecto, chat, m.id, nombreDeLista(m), m.cuando, filasDe(m.texto))

    /** Si una lista es el Inbox: la de «Mensajes guardados» que se llama así. */
    fun esInbox(l: Lista): Boolean = l.guardados && l.titulo.trim().equals(INBOX, ignoreCase = true)

    /**
     * El orden de las listas: el Inbox el primero (es donde cae lo apuntado y lo que hay que
     * repartir); luego las que tienen algo pendiente; entre ellas, la más nueva arriba. Las ya
     * hechas enteras, al final. Igual que `tareas::ordenar_listas` del PC.
     */
    val ORDEN_DE_LISTAS: Comparator<Lista> =
        compareByDescending<Lista> { esInbox(it) }
            .thenByDescending { it.pendientes > 0 }
            .thenByDescending { it.cuando }
            .thenBy { it.clave }

    /**
     * Todas las listas de todos los chats, ya ordenadas. [nombres] son los proyectos que hay
     * (id → nombre): una lista de un proyecto que ya no está no sale, como en el PC, que solo
     * mira los chats de su índice.
     */
    fun reunir(mensajes: List<Mensaje>, nombres: Map<String, String>): List<Lista> =
        mensajes.mapNotNull { m ->
            val chat = if (m.proyecto == null) GUARDADOS else nombres[m.proyecto] ?: return@mapNotNull null
            listaDe(m, chat)
        }.sortedWith(ORDEN_DE_LISTAS)

    /**
     * El Inbox de «Mensajes guardados», si ya está: la primera lista del chat general que se
     * llama así y no espera en el buzón (`tareas::apuntar_con` del PC).
     */
    fun inboxEn(mensajes: List<Mensaje>): Mensaje? = mensajes.firstOrNull {
        it.proyecto == null && esLista(it) && !it.enBuzon && nombreDeLista(it).trim().equals(INBOX, ignoreCase = true)
    }

    // ---- Buscar ----------------------------------------------------------

    /** Las palabras de lo buscado. Sin palabras no se filtra nada. */
    fun palabras(consulta: String): List<String> = consulta.split(BLANCOS).filter { it.isNotEmpty() }

    /**
     * Un carácter en su forma de comparar: minúscula y sin tilde, **uno a uno** para que los
     * tramos resaltados caigan en su sitio del original. La tabla de `pixpin_ui::resaltado` del PC
     * (la eñe vale ene: buscar «cana» saca «caña», mejor que no encontrar nada sin la eñe a mano).
     */
    fun normalizar(c: Char): Char = when (val b = c.lowercaseChar()) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        'ý', 'ÿ' -> 'y'
        else -> b
    }

    /**
     * Dónde aparece [aguja] en [texto], de izquierda a derecha y sin solaparse, sin mirar
     * mayúsculas ni tildes. Una aguja en blanco no coincide con nada.
     */
    fun coincidencias(texto: String, aguja: String, soloLaPrimera: Boolean = false): List<IntRange> {
        if (aguja.isBlank() || aguja.length > texto.length) return emptyList()
        val buscada = CharArray(aguja.length) { normalizar(aguja[it]) }
        val tramos = ArrayList<IntRange>(1)
        var i = 0
        while (i + buscada.size <= texto.length) {
            var k = 0
            while (k < buscada.size && normalizar(texto[i + k]) == buscada[k]) k++
            if (k == buscada.size) {
                tramos += i until i + buscada.size
                if (soloLaPrimera) return tramos
                i += buscada.size
            } else i++
        }
        return tramos
    }

    fun hayCoincidencia(texto: String, aguja: String): Boolean = coincidencias(texto, aguja, true).isNotEmpty()

    /**
     * Los tramos de [texto] que hay que resaltar con todas las [palabras], ordenados y sin
     * pisarse (dos resaltes encima se verían más oscuros).
     */
    fun resaltes(texto: String, palabras: List<String>): List<IntRange> {
        val todos = palabras.flatMap { coincidencias(texto, it) }.sortedBy { it.first }
        val juntos = ArrayList<IntRange>(todos.size)
        for (t in todos) {
            val ultimo = juntos.lastOrNull()
            if (ultimo != null && t.first <= ultimo.last + 1) juntos[juntos.lastIndex] = ultimo.first..maxOf(ultimo.last, t.last)
            else juntos += t
        }
        return juntos
    }

    /** El día que nombra una palabra, si nombra alguno. */
    sealed interface Dia {
        /** Hace tantos días: hoy es 0, ayer 1. */
        data class Hace(val dias: Int) : Dia
        /** Una fecha escrita entera (`2026-10-01`). */
        data class El(val fecha: LocalDate) : Dia

        /** Si una tarea creada el día [creada] es de este día. Una del futuro (otro reloj) no es «hoy». */
        fun es(creada: LocalDate?, hoy: LocalDate): Boolean = when (this) {
            is Hace -> creada != null && !creada.isAfter(hoy) && Tareas.diasDesde(creada, hoy) == dias.toLong()
            is El -> creada == fecha
        }
    }

    private val FECHA_ESCRITA = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    fun diaDe(palabra: String): Dia? {
        val p = palabra.map(::normalizar).joinToString("")
        return when (p) {
            "hoy", "today" -> Dia.Hace(0)
            "ayer", "yesterday" -> Dia.Hace(1)
            "anteayer" -> Dia.Hace(2)
            else -> FECHA_ESCRITA.find(p)?.let { m ->
                // Mes 1-12 y día 1-31, como el PC: basta para no tomar un número por una fecha.
                // Una que no existe en el calendario (31 de febrero) no es de ninguna tarea.
                val (a, me, d) = m.destructured
                if (me.toInt() !in 1..12 || d.toInt() !in 1..31) null
                else runCatching { Dia.El(LocalDate.of(a.toInt(), me.toInt(), d.toInt())) }.getOrNull()
            }
        }
    }

    /**
     * Si la tarea [f] de la lista [l] sale con estas palabras: han de estar TODAS, cada una en el
     * texto de la tarea, en el nombre de su lista o en el de su chat, o nombrar el día en que se
     * apuntó («hoy», «ayer», «anteayer», `2026-10-01`).
     */
    fun coincide(l: Lista, f: Fila, palabras: List<String>, hoy: LocalDate): Boolean =
        palabras.all { p ->
            hayCoincidencia(f.texto, p) || hayCoincidencia(l.titulo, p) || hayCoincidencia(l.chat, p) ||
                diaDe(p)?.es(f.creada, hoy) == true
        }

    // ---- Agrupar ---------------------------------------------------------

    /** Las tarjetas de una lista que salen, ya ordenadas: números de [Lista.filas]. */
    data class Grupo(
        /** El sitio de la lista en las listas de la pantalla. */
        val lista: Int,
        /** Lo pendiente (y lo recién tachado), como lo ordena [Lista.aLaVista]. */
        val arriba: List<Int>,
        /** Lo hecho, plegable, en el orden del documento. */
        val hechas: List<Int>
    ) {
        /** Cuántas de arriba siguen pendientes (lo recién tachado no cuenta). */
        fun pendientes(l: Lista): Int = arriba.count { i -> l.filas.getOrNull(i)?.hecha == false }
    }

    /**
     * Los grupos que se enseñan, en el orden de las listas. Sin buscar, las listas con alguna
     * tarea; buscando, solo lo que coincide, y una lista sin nada que coincida no sale.
     */
    fun agrupar(
        listas: List<Lista>,
        palabras: List<String>,
        hoy: LocalDate,
        sigueArriba: (Lista, Fila) -> Boolean = { _, _ -> false }
    ): List<Grupo> = listas.mapIndexedNotNull { li, l ->
        val (arriba, hechas) = l.aLaVista { sigueArriba(l, it) }
        val a = arriba.filter { coincide(l, it, palabras, hoy) }.map { it.indice }
        val h = hechas.filter { coincide(l, it, palabras, hoy) }.map { it.indice }
        if (a.isEmpty() && h.isEmpty()) null else Grupo(li, a, h)
    }

    /** «hoy», «hace 1 día», «hace N días» (`mini-tarea-edad`). Nunca la fecha: lo pidió el usuario. */
    fun edad(creada: LocalDate?, hoy: LocalDate): String? {
        creada ?: return null
        return when (val d = Tareas.diasDesde(creada, hoy)) {
            0L -> "hoy"
            1L -> "hace 1 día"
            else -> "hace $d días"
        }
    }

    /** «Nada pendiente», «1 pendiente», «N pendientes» · «1 lista», «N listas» (`tareas-resumen`). */
    fun resumen(pendientes: Int, listas: Int): String {
        val p = when (pendientes) { 0 -> "Nada pendiente"; 1 -> "1 pendiente"; else -> "$pendientes pendientes" }
        return p + " · " + if (listas == 1) "1 lista" else "$listas listas"
    }

    /** «nada pendiente», «1 pendiente», «N pendientes» del encabezado de un grupo (`tareas3-pendientes`). */
    fun pendientesDeGrupo(n: Int): String = when (n) { 0 -> "nada pendiente"; 1 -> "1 pendiente"; else -> "$n pendientes" }

    /** «1 tarea encontrada», «N tareas encontradas» (`tareas3-encontradas`). */
    fun encontradas(n: Int): String = if (n == 1) "1 tarea encontrada" else "$n tareas encontradas"

    // ---- Escribir --------------------------------------------------------
    //
    // Todo devuelve el documento nuevo, o nulo si la tarea ya no es la que se vio (hay que releer).

    /** Si la tarea número [Fila.indice] de [tareas] sigue siendo [f]. */
    private fun sigue(tareas: List<Tarea>, f: Fila): Boolean = tareas.getOrNull(f.indice)?.texto == f.crudo

    /** El documento con la tarea [f] hecha o pendiente. */
    fun marcar(documento: String, f: Fila, hecha: Boolean): String? {
        val tareas = Tareas.leer(documento)
        if (!sigue(tareas, f)) return null
        return Tareas.escribir(Cabecera.titulo(documento), Tareas.marcar(tareas, f.indice, hecha))
    }

    /** El documento sin la tarea [f], y la tarea tal cual estaba (para [reponer]). */
    fun quitar(documento: String, f: Fila): Pair<String, Tarea>? {
        val tareas = Tareas.leer(documento)
        if (!sigue(tareas, f)) return null
        return Tareas.escribir(Cabecera.titulo(documento), Tareas.borrar(tareas, f.indice)) to tareas[f.indice]
    }

    /** «Deshacer»: la tarea vuelve a su sitio (o al final, si la lista se acortó), con su fecha y su estado. */
    fun reponer(documento: String, indice: Int, tarea: Tarea): String {
        val tareas = Tareas.leer(documento).toMutableList()
        tareas.add(indice.coerceIn(0, tareas.size), tarea)
        return Tareas.escribir(Cabecera.titulo(documento), tareas)
    }

    /**
     * Pasa la tarea [f] del documento [origen] al final de [destino], con su fecha y su estado
     * (`tareas::mover` del PC). [crudoNuevo] es su texto con las imágenes ya copiadas al chat de
     * destino (el mismo crudo si no hay que copiar). Devuelve (origen, destino) nuevos.
     */
    fun mover(origen: String, f: Fila, destino: String, crudoNuevo: String = f.crudo): Pair<String, String>? {
        val tareas = Tareas.leer(origen)
        if (!sigue(tareas, f)) return null
        val suyas = Tareas.leer(destino) + Tarea(crudoNuevo, f.hecha)
        return Tareas.escribir(Cabecera.titulo(origen), Tareas.borrar(tareas, f.indice)) to
            Tareas.escribir(Cabecera.titulo(destino), suyas)
    }

    /** El documento de una lista con [texto] añadido al final, con la fecha de [hoy]. */
    fun conTarea(documento: String, texto: String, hoy: LocalDate = LocalDate.now()): String =
        Tareas.escribir(Cabecera.titulo(documento), Tareas.anadir(Tareas.leer(documento), texto, hoy = hoy))

    // ---- Imágenes --------------------------------------------------------

    /** Lo que dice el enlace de una imagen ya resuelto: `pixpin:files/…` o una ruta absoluta. */
    private const val PORTATIL = "pixpin:files/"

    /** El fichero de este aparato al que lleva [enlace], si está. */
    fun archivoDeEnlace(filesDir: File, enlace: String): File? {
        val f = when {
            enlace.startsWith(PORTATIL) -> File(filesDir, enlace.removePrefix(PORTATIL))
            enlace.startsWith("/") -> File(enlace)
            else -> return null
        }
        return f.takeIf { it.isFile }
    }

    /**
     * El texto de una tarea que pasa a otro chat, con sus imágenes **copiadas** a la carpeta del
     * chat (`files/guardados/tarea-<ms>-<nn>.<ext>`) y los enlaces cambiados, como `tareas::mover`
     * del PC: borrar la lista o el chat de origen no deja la tarea sin su foto, y en el PC el enlace
     * se encuentra en el chat de destino. Lo que aún no está en este aparato se deja como estaba.
     * Si una copia falla, nulo (y no se mueve nada).
     */
    fun conImagenesCopiadas(texto: String, filesDir: File, carpeta: File, ahora: Long): String? {
        var fallo = false
        var n = 0
        val nuevo = cambiarEnlaces(texto) { enlace ->
            val origen = archivoDeEnlace(filesDir, enlace) ?: return@cambiarEnlaces null
            val ext = origen.extension.lowercase().ifEmpty { "png" }
            val destino = generateSequence(n + 1) { it + 1 }.take(10_000)
                .map { File(carpeta, "tarea-$ahora-${it.toString().padStart(2, '0')}.$ext") }
                .firstOrNull { !it.exists() }
            if (destino == null) { fallo = true; return@cambiarEnlaces null }
            n = destino.name.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: (n + 1)
            if (runCatching { carpeta.mkdirs(); origen.copyTo(destino) }.isFailure) { fallo = true; null }
            else destino.absolutePath
        }
        return if (fallo) null else nuevo
    }
}
