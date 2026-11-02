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
 *   `proyecto` ni `codigo`. De ahí se reparte con «Mover a…». Hay uno solo: si llegan dos (cada
 *   aparato creó el suyo antes de sincronizar), se juntan ([unirInboxes]).
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
        val imagenes: List<String>,
        /**
         * **Lo que lleva [texto], por partes** (tareas inteligentes del PC, 18-oct-2026): el título
         * limpio, la urgencia (`⏫`/`🔼`), las `#etiquetas` y la nota (`💬`). La tarjeta enseña esto;
         * buscar sigue mirando [texto] entero, así que «#casa» o «⏫» también encuentran.
         */
        val marcas: TareaInteligente.Frase = TareaInteligente.marcas(texto),
        /**
         * **Su prioridad según su contexto** (28-oct-2026, Jev; [com.forge.pixpin.jev.UrgenciaEnLaApp]).
         * null: sin Jev, o aún no se sabe; entonces todo va como siempre. Se calcula en cada aparato y
         * no se guarda ni viaja.
         */
        val prioridad: com.forge.pixpin.jev.Urgencia.Prioridad? = null,
        /**
         * Y su porqué, para la pista de la chapita (2-nov-2026, `Fila::motivo` del PC; null si la marcó
         * el usuario o no se sabe). Tampoco se guarda ni viaja.
         */
        val motivo: com.forge.pixpin.jev.Urgencia.Motivo? = null
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
        val filas: List<Fila>,
        /**
         * **El aparato donde nació la lista** (27-oct-2026, [Mensaje.aparato]): en un chat
         * compartido dice si la apuntó otra persona ([TareasCompartidas.autor]). null en las de
         * antes de los códigos de aparato, que son mías.
         */
        val aparato: String? = null
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
     * Cómo se ordena lo pendiente: **con Jev, primero por su prioridad** (urgente, alta, normal, baja;
     * lo que aun no se sabe cuenta como normal; 28-oct-2026, `orden_de_pendiente` del PC); luego lo que
     * tiene fecha, de la más vieja a la más nueva (lo que más lleva esperando); detrás lo que no la
     * tiene. A igualdad, el orden del documento. Sin Jev todas son «normal» y el orden es el de siempre.
     */
    val ORDEN_DE_PENDIENTE: Comparator<Fila> =
        compareBy<Fila> { (it.prioridad ?: com.forge.pixpin.jev.Urgencia.Prioridad.NORMAL).ordinal }
            .thenBy { it.creada == null }.thenBy { it.creada }.thenBy { it.indice }

    private val BLANCOS = Regex("""\s+""")

    /** Las tareas de un documento, con su texto partido de su fecha y de sus imágenes. */
    fun filasDe(documento: String): List<Fila> =
        Tareas.leer(documento).mapIndexed { i, t ->
            val (visible, creada) = Tareas.partir(t.texto)
            val (texto, imagenes) = Tareas.imagenes(visible)
            Fila(i, t.texto, texto, t.hecha, creada, imagenes)
        }

    fun esLista(m: Mensaje): Boolean = m.clase == Clase.MINIAPP && m.miniapp == MiniApp.TAREAS.id

    /** Cómo se llama una lista: su título, o el nombre del mensaje (`pedidos::nombre_de_lista`). */
    fun nombreDeLista(m: Mensaje): String = Cabecera.titulo(m.texto).ifEmpty { m.nombre }

    /** La lista de un mensaje, si es una lista de tareas. También vacía: es adonde mover. */
    fun listaDe(m: Mensaje, chat: String): Lista? =
        if (!esLista(m)) null
        else Lista(m.proyecto, chat, m.id, nombreDeLista(m), m.cuando, filasDe(m.texto), m.aparato)

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
     * (id → nombre).
     *
     * **Las de todos los chats, también los que no están en [nombres]** (1-nov-2026). El usuario:
     * «no todos los grupos de tareas que creo aparecen en Tareas; uno que creo en otro chat no
     * aparece». Antes una lista cuyo chat no estaba en los proyectos de este teléfono no salía
     * («como en el PC, que solo mira los chats de su índice»), pero aquí hay chats que la lista de
     * chats sí enseña sin ficha de proyecto: un chat llegado del PC o de un chat compartido antes
     * de que lleguen sus proyectos, o la ficha aún sin releer. En el PC todo chat está en su índice,
     * así que allí salen todas; aquí, igual que hace la lista de chats ([com.forge.pixpin.guardados.conversaciones]),
     * se nombra con su id si no se sabe su nombre. Borrar un proyecto se lleva sus mensajes, así
     * que esto no resucita nada borrado.
     */
    fun reunir(mensajes: List<Mensaje>, nombres: Map<String, String>): List<Lista> =
        mensajes.mapNotNull { m ->
            val chat = if (m.proyecto == null) GUARDADOS else nombres[m.proyecto] ?: m.proyecto
            listaDe(m, chat)
        }.sortedWith(ORDEN_DE_LISTAS)

    /**
     * Si un mensaje es **un Inbox**: una lista del chat general que se llama así (sin mirar
     * mayúsculas ni blancos) y no espera en el buzón. La misma regla que `tareas::apuntar_con` del
     * PC: el Inbox no tiene un código fijo, se reconoce por su chat y su nombre.
     */
    fun esMensajeInbox(m: Mensaje): Boolean =
        m.proyecto == null && esLista(m) && !m.enBuzon && nombreDeLista(m).trim().equals(INBOX, ignoreCase = true)

    /**
     * **El orden de los Inbox repetidos**: el más antiguo primero (por `cuando` y luego por `id`).
     * Es el que se queda al juntarlos ([unirInboxes]), el mismo en todos los aparatos sin tener que
     * ponerse de acuerdo; el PC coge el primero de su cuaderno, que se escribe en orden de llegada
     * y casi siempre es también el más antiguo.
     */
    private val ORDEN_DE_INBOX: Comparator<Mensaje> = compareBy<Mensaje> { it.cuando }.thenBy { it.id }

    /**
     * El Inbox de «Mensajes guardados», si ya está (`tareas::apuntar_con` del PC). Si hay varios
     * (ver [unirInboxes]), el que se queda: el más antiguo.
     */
    fun inboxEn(mensajes: List<Mensaje>): Mensaje? = mensajes.filter(::esMensajeInbox).minWithOrNull(ORDEN_DE_INBOX)

    /**
     * **«En Tareas veo dos Inbox»** (el usuario, 1-nov-2026: «debería ser uno solo, unificado en
     * todos los aparatos sincronizados»).
     *
     * POR QUÉ salen dos: el Inbox no tiene un código fijo, se crea con uno al azar la primera vez que
     * se apunta algo sin decir dónde, en el aparato donde se apunte (aquí [com.forge.pixpin.mini.TareaRapidaActivity.alInbox],
     * en el PC `tareas::apuntar_con` con `pedidos::nueva_lista`). Si el teléfono y el PC apuntan
     * antes de sincronizar —o dos aparatos sin conexión—, cada uno crea el suyo, y al sincronizar
     * viajan los dos: dos mensajes distintos que se llaman «Inbox».
     *
     * El arreglo, sin cambiar nada del formato (el PC no lo necesita): **se juntan**. Se queda el
     * más antiguo ([inboxEn]) y recibe, al final y tal cual (con su fecha, su hora y su estado), las
     * tareas de los demás que no tenga ya (mismo texto crudo: así juntarlo dos veces, o en dos
     * aparatos a la vez, no las repite); los demás se borran. Borrar deja su lápida y viaja, así
     * que el PC también se queda con uno. Y no se pierde nada: si el PC añade algo a un Inbox
     * borrado antes de enterarse, la sincronización lo rescata («lo modificado gana a lo borrado»,
     * [com.forge.pixpin.sincro.Fusion]) y la próxima vez se vuelve a juntar.
     *
     * Devuelve los mensajes con un solo Inbox, o null si ya había uno o ninguno (no hay que tocar
     * nada). Lo que espera en el buzón no cuenta (tampoco para el PC).
     */
    fun unirInboxes(mensajes: List<Mensaje>): List<Mensaje>? {
        val inboxes = mensajes.filter(::esMensajeInbox).sortedWith(ORDEN_DE_INBOX)
        if (inboxes.size < 2) return null
        val queda = inboxes.first()
        val sobran = inboxes.drop(1).map { it.id }.toSet()
        val tareas = Tareas.leer(queda.texto).toMutableList()
        val vistas = tareas.mapTo(HashSet()) { it.texto }
        for (otro in inboxes.drop(1)) for (t in Tareas.leer(otro.texto)) if (vistas.add(t.texto)) tareas += t
        val texto = Tareas.escribir(Cabecera.titulo(queda.texto).ifEmpty { INBOX }, tareas)
        return mensajes.mapNotNull { m ->
            when {
                m.id == queda.id -> m.copy(texto = texto)
                m.id in sobran && esMensajeInbox(m) -> null
                else -> m
            }
        }
    }

    // ---- Buscar ----------------------------------------------------------

    /**
     * Las palabras de lo buscado. Sin palabras no se filtra nada.
     *
     * «hari ini» (hoy, en indonesio) son dos palabras que nombran un día: van juntas, o «hari» sola no
     * encontraría nada (1-nov-2026, `tareas::tarjetas::palabras` del PC).
     */
    fun palabras(consulta: String): List<String> {
        val v = ArrayList<String>()
        for (p in consulta.split(BLANCOS)) {
            if (p.isEmpty()) continue
            val antes = v.lastOrNull()
            if (plano(p) == "ini" && antes != null && plano(antes) == "hari") v[v.lastIndex] = "$antes $p"
            else v += p
        }
        return v
    }

    /** Una palabra en su forma de comparar ([normalizar] letra a letra). */
    private fun plano(s: String): String = s.map(::normalizar).joinToString("")

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
        // «aujourd’hui» con el apóstrofo curvo que pone el teclado vale lo mismo que con el recto.
        '’' -> '\''
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

    /** «Hoy», ya en su forma de comparar ([plano]). */
    private val HOY = setOf(
        "hoy", "today", "今天", "今日", "आज", "اليوم", "hoje", "aujourd'hui", "heute",
        "сегодня", "오늘", "oggi", "hari ini", "bugun"
    )
    /** «Ayer», ya en su forma de comparar ([plano]). */
    private val AYER = setOf(
        "ayer", "yesterday", "昨天", "昨日", "कल", "أمس", "امس", "ontem", "hier",
        "gestern", "вчера", "어제", "ieri", "kemarin", "dun"
    )

    private val FECHA_ESCRITA = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /**
     * **«Hoy» y «ayer» en los catorce idiomas del PC** (1-nov-2026, `tareas::tarjetas::dia_de`), con las
     * palabras que dice la pista del buscador de cada uno (今天, сегодня, aujourd’hui, bugün…): se aceptan
     * todas a la vez, porque quien busca puede escribir en otro idioma que el de PixPin. «कल» es ayer y
     * mañana en hindi: aquí, ayer.
     */
    fun diaDe(palabra: String): Dia? {
        val p = plano(palabra)
        return when (p) {
            in HOY -> Dia.Hace(0)
            in AYER -> Dia.Hace(1)
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
        /**
         * **También las listas vacías, sin buscar** (1-nov-2026). Un grupo recién creado en un chat
         * nace vacío y no salía en las tarjetas hasta tener alguna tarea, y al usuario le parecía
         * que no estaba («uno que creo en otro chat no aparece»). Con esto sale con su encabezado
         * y «nada pendiente». Buscando no cambia nada: una lista sin nada que coincida no sale. El
         * PC (`tarjetas::agrupar`) no las enseña; es solo la vista, no cambia nada de lo que viaja.
         */
        conVacias: Boolean = false,
        sigueArriba: (Lista, Fila) -> Boolean = { _, _ -> false }
    ): List<Grupo> = listas.mapIndexedNotNull { li, l ->
        val (arriba, hechas) = l.aLaVista { sigueArriba(l, it) }
        val a = arriba.filter { coincide(l, it, palabras, hoy) }.map { it.indice }
        val h = hechas.filter { coincide(l, it, palabras, hoy) }.map { it.indice }
        if (a.isEmpty() && h.isEmpty() && !(conVacias && palabras.isEmpty() && l.filas.isEmpty())) null else Grupo(li, a, h)
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
    /**
     * Los colores de los grupos (PC `11bd1f6`, `tareas/ventana.rs`): doce tonos bien distintos
     * (violeta, verde, rojo, naranja, azul, rosa, turquesa, oliva, marrón, añil, magenta y dorado),
     * cada uno con el par de su degradado en el PC; aquí se usa el primero.
     */
    val COLORES_DE_GRUPO: IntArray = intArrayOf(
        0x5B2DA8, 0x0F7A4A, 0xA8323E, 0xB8650F, 0x1767A8, 0xA8306E,
        0x0E7C86, 0x6E7A12, 0x7A4A2A, 0x3A44B8, 0x8A2AA8, 0x9C7A0E,
    )

    /**
     * **El color de cada lista** (su sitio en [COLORES_DE_GRUPO]), sin repetir mientras haya colores
     * (el usuario, 10-oct-2026: «que cada grupo de tareas tenga un color diferente»). Igual que el PC,
     * para que un grupo tenga el mismo color en los dos: cada lista parte del color de su clave
     * (FNV-1a) y, si ya lo tiene otra, toma el siguiente libre; se reparten por orden de clave (no
     * por el de la pantalla, que cambia al tachar). Las listas vacías no gastan color.
     * [claves]: la clave de cada lista y si tiene tareas.
     */
    fun coloresDeListas(claves: List<Pair<String, Boolean>>): IntArray {
        val n = COLORES_DE_GRUPO.size
        fun semilla(c: String): Int {
            var h = 0x811c9dc5.toInt()
            for (b in c.toByteArray(Charsets.UTF_8)) h = (h xor (b.toInt() and 0xff)) * 0x01000193
            return ((h.toLong() and 0xffffffffL) % n).toInt()
        }
        val out = IntArray(claves.size) { semilla(claves[it].first) }
        val usado = BooleanArray(n)
        // Por bytes, como el `cmp` de Rust.
        val orden = claves.indices.filter { claves[it].second }.sortedWith { a, b -> compararBytes(claves[a].first, claves[b].first) }
        for (i in orden) {
            val desde = out[i]
            val k = (0 until n).map { (desde + it) % n }.firstOrNull { !usado[it] } ?: desde
            usado[k] = true
            out[i] = k
        }
        return out
    }

    private fun compararBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

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

    /**
     * **Recordar la tarea [f] a una hora** (o dejar de recordarla con null): la marca `⏰` en su
     * texto, como el PC. null si la lista cambió y ya no es esta.
     */
    fun ponerHora(documento: String, f: Fila, hora: java.time.LocalDateTime?): String? {
        val tareas = Tareas.leer(documento)
        if (!sigue(tareas, f)) return null
        val nuevas = tareas.toMutableList().also { it[f.indice] = it[f.indice].copy(texto = Tareas.conHora(it[f.indice].texto, hora)) }
        return Tareas.escribir(Cabecera.titulo(documento), nuevas)
    }

    /** El documento sin la tarea [f], y la tarea tal cual estaba (para [reponer]). */
    fun quitar(documento: String, f: Fila): Pair<String, Tarea>? {
        val tareas = Tareas.leer(documento)
        if (!sigue(tareas, f)) return null
        return Tareas.escribir(Cabecera.titulo(documento), Tareas.borrar(tareas, f.indice)) to tareas[f.indice]
    }

    /** «Deshacer»: la tarea vuelve a su sitio (o al final, si la lista se acortó), con su fecha y su estado. */
    fun reponer(documento: String, indice: Int, tarea: Tarea): String =
        Tareas.escribir(Cabecera.titulo(documento), Tareas.reponer(Tareas.leer(documento), indice, tarea))

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

    /** El fichero de este aparato al que lleva [enlace] (`pixpin:files/…` o ruta absoluta), si está. */
    fun archivoDeEnlace(filesDir: File, enlace: String): File? =
        Tareas.rutaDeImagen(enlace, filesDir.path)?.let(::File)?.takeIf { it.isFile }

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
        val nuevo = Tareas.cambiarEnlaces(texto) { enlace ->
            val origen = archivoDeEnlace(filesDir, enlace) ?: return@cambiarEnlaces null
            val ext = origen.extension.lowercase().ifEmpty { "png" }
            val destino = generateSequence(n + 1) { it + 1 }.take(10_000)
                .map { File(carpeta, Tareas.nombreDeCopia(ahora, it, ext)) }
                .firstOrNull { !it.exists() }
            if (destino == null) { fallo = true; return@cambiarEnlaces null }
            n = destino.name.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: (n + 1)
            if (runCatching { carpeta.mkdirs(); origen.copyTo(destino) }.isFailure) { fallo = true; null }
            else destino.absolutePath
        }
        return if (fallo) null else nuevo
    }
}
