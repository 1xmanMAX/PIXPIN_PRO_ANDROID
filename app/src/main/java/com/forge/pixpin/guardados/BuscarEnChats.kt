package com.forge.pixpin.guardados

/**
 * **Buscar dentro de todos los chats** desde el buscador de la lista de chats, como en Telegram
 * (25-oct-2026, lo del PC `97e7f88`, `d106969` y `02b3124`; el usuario: «que el buscador busque
 * también dentro de todos los chats»). Debajo de los chats que casan por su nombre sale una sección
 * **«Mensajes · N»** con los mensajes de *cualquier* chat que llevan lo escrito, del más nuevo al
 * más viejo, con su trozo de texto y lo encontrado resaltado; tocar uno abre su chat en él.
 *
 * **Desde el 27-oct-2026, como si todo estuviera dentro de un chat** (el usuario): arriba las
 * pestañas de la ficha ([SECCIONES]: Fotos, Vídeos, Archivos…), cada resultado con su miniatura y
 * el chat donde está, y tocarlo **abre la cosa** en su visor ([alTocar], [AbrirMensaje]); «Ir al
 * mensaje» lleva a su burbuja, que ahora sí llega aunque esté muy arriba ([adondeSaltar]).
 *
 * Aquí la parte pura, copiada de `crates/pixpin-ui/src/buscar_en_chats.rs` con sus pruebas
 * (`BuscarEnChatsTest`) para que dé lo mismo en todos los aparatos: el índice ya aplanado, la
 * búsqueda con su tope, el trozo, la espera entre tecla y tecla y cómo se reparte la sección. Quien
 * lee el chat y pinta es [com.forge.pixpin.ui.ListaDeChats].
 *
 * Decisiones que no son evidentes (las del PC):
 *
 * - **Se aplana una vez, al indexar**, y no en cada búsqueda: con cien mil mensajes, normalizar
 *   todo en cada tecla se notaría. Buscar es entonces un `contains` sobre lo ya aplanado.
 * - **El aplanado es de un carácter a uno** ([aplanar], con la tabla de `resaltado.rs` del PC, que
 *   tiene algo más que [sinAcentos]: `ã å õ ç ý ÿ`): así lo encontrado en lo aplanado cae en el
 *   mismo sitio del original, y los tramos se sacan del texto de verdad.
 * - **Los blancos cuentan como espacio**: «hola mundo» encuentra «hola⏎mundo».
 * - **Se cuenta todo, se devuelve hasta el tope**: la sección dice cuántos hay de verdad y «Ver
 *   más» pide el siguiente tramo.
 *
 * Los índices de [Tramo] van en unidades de `String` de Kotlin (UTF-16), no en bytes como el PC:
 * son los que entiende un `AnnotatedString`. Lo que cuenta caracteres (lo de antes y el largo del
 * trozo) cuenta por carácter de verdad (punto de código), como el `chars()` de Rust.
 */
object BuscarEnChats {

    /** Un tramo a resaltar, `[desde, hasta)` en índices del texto. */
    data class Tramo(val desde: Int, val hasta: Int)

    /**
     * Un carácter en su forma «para comparar»: minúscula y sin tilde, de uno a uno (la tabla de
     * `resaltado::normalizar` del PC).
     */
    fun normalizar(c: Char): Char = when (val bajo = c.lowercaseChar()) {
        'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ñ' -> 'n'
        'ç' -> 'c'
        'ý', 'ÿ' -> 'y'
        else -> bajo
    }

    private fun tramos(texto: String, aguja: String, pararEnLaPrimera: Boolean): List<Tramo> {
        // La aguja en blanco no coincide con nada (no con todo): resaltarlo todo no dice nada.
        if (aguja.isBlank()) return emptyList()
        val n = aguja.length
        if (n > texto.length) return emptyList()
        val buscada = CharArray(n) { normalizar(aguja[it]) }
        val letras = CharArray(texto.length) { normalizar(texto[it]) }
        val salida = ArrayList<Tramo>()
        var i = 0
        while (i + n <= letras.size) {
            var igual = true
            for (k in 0 until n) if (letras[i + k] != buscada[k]) { igual = false; break }
            if (igual) {
                salida += Tramo(i, i + n)
                if (pararEnLaPrimera) return salida
                // Se salta lo encontrado entero: dos resaltados no se pisan.
                i += n
            } else i++
        }
        return salida
    }

    /** Todas las apariciones de [aguja] en [texto], de izquierda a derecha y sin solaparse. */
    fun coincidencias(texto: String, aguja: String): List<Tramo> = tramos(texto, aguja, false)

    /**
     * Si [aguja] aparece en [texto], sin mirar mayúsculas ni tildes. Es lo que filtra también los
     * chats de la lista por su nombre y su última línea (PC `02b3124`: «cimentacion» encuentra la
     * «Cimentación»).
     */
    fun hayCoincidencia(texto: String, aguja: String): Boolean = tramos(texto, aguja, true).isNotEmpty()

    /** El texto en minúsculas y sin tildes, con cada blanco hecho espacio: de un carácter a uno. */
    fun aplanar(texto: String): String {
        val sb = StringBuilder(texto.length)
        for (c in texto) sb.append(if (c.isWhitespace()) ' ' else normalizar(c))
        return sb.toString()
    }

    /** Lo que se busca, ya aplanado; nulo si no hay nada que buscar. */
    fun agujaPlana(aguja: String): String? = aguja.trim().takeIf { it.isNotEmpty() }?.let(::aplanar)

    /** Un trozo de texto de un mensaje (su texto, su transcripción, el nombre de su fichero), tal cual y aplanado. */
    class Pieza(val texto: String) {
        internal val plano: String = aplanar(texto)
        override fun equals(other: Any?) = other is Pieza && other.texto == texto
        override fun hashCode() = texto.hashCode()
    }

    /** Lo que se sabe de un mensaje para buscarlo y llevar a él. */
    data class Entrada(
        /** El chat donde está ([com.forge.pixpin.sincro.Disco.chatDe]: el proyecto, o `general`). */
        val chat: String,
        /** Su código único: desempata el orden, como en el PC. */
        val codigo: String,
        /** Milisegundos desde 1970: ordena del más nuevo al más viejo. */
        val cuando: Long,
        val piezas: List<Pieza>,
        /** Su id en este aparato: por él se le encuentra al abrir el chat ([MensajesActivity.irAlMensaje]). */
        val id: String = codigo,
        /** En qué pestañas de [SECCIONES] sale (fotos, archivos…), sin «Todo». Ver [seccionesDe]. */
        val secciones: Set<Seccion> = emptySet()
    ) {
        internal fun piezaCon(agujaPlana: String): Int = piezas.indexOfFirst { it.plano.contains(agujaPlana) }

        companion object {
            /** Una entrada con los textos que se le den, sin los vacíos ni los repetidos. */
            fun nueva(
                chat: String, codigo: String, cuando: Long, textos: List<String?>, id: String = codigo,
                secciones: Set<Seccion> = emptySet()
            ): Entrada {
                val piezas = ArrayList<Pieza>()
                for (t0 in textos) {
                    val t = t0?.trim().orEmpty()
                    if (t.isEmpty() || piezas.any { it.texto == t }) continue
                    piezas += Pieza(t)
                }
                return Entrada(chat, codigo, cuando, piezas, id, secciones)
            }
        }
    }

    /**
     * Un mensaje encontrado: cuál (por su posición en lo buscado) y en qué pieza (-1: ninguna, al
     * mirar una pestaña sin haber escrito nada y sin texto que enseñar).
     */
    data class Acierto(val entrada: Int, val pieza: Int)

    /**
     * Lo que dio una búsqueda: los primeros `tope` y cuántos hay en total en la pestaña pedida, y
     * [porSeccion], cuántos casan en cada pestaña de [SECCIONES] (el número de su chapa).
     */
    data class Hallado(
        val aciertos: List<Acierto> = emptyList(),
        val total: Int = 0,
        val porSeccion: Map<Seccion, Int> = emptyMap()
    ) {
        /** Si quedan por enseñar («Ver más»). */
        val hayMas: Boolean get() = total > aciertos.size
    }

    /**
     * **Busca** [aguja] en [entradas], que tienen que venir ya del más nuevo al más viejo. Devuelve
     * los [tope] primeros y cuenta todos.
     *
     * **Con [seccion], solo lo de esa pestaña** (27-oct-2026, el usuario: «arriba filtros como
     * Archivos, Fotos…, como si todo estuviera dentro de un chat»). Se filtra **antes** del tope y no
     * después: filtrar los veinte primeros dejaba «Archivos» con dos aunque hubiera cuarenta. Y con
     * una pestaña puesta **no hace falta escribir**: sin aguja salen todos los de esa clase de todos
     * los chats, que es lo que hace la ficha de un chat con lo suyo. Sin aguja y en «Todo», nada:
     * eso sería el archivo entero, y para eso ya está la lista de chats.
     */
    fun buscar(entradas: Iterable<Entrada>, aguja: String, tope: Int, seccion: Seccion = Seccion.TODO): Hallado {
        val plana = agujaPlana(aguja)
        if (plana == null && seccion == Seccion.TODO) return Hallado()
        val aciertos = ArrayList<Acierto>()
        var total = 0
        val porSeccion = HashMap<Seccion, Int>()
        for ((i, e) in entradas.withIndex()) {
            val pieza = if (plana == null) (if (e.piezas.isEmpty()) -1 else 0) else e.piezaCon(plana)
            if (plana != null && pieza < 0) continue
            porSeccion[Seccion.TODO] = (porSeccion[Seccion.TODO] ?: 0) + 1
            for (s in e.secciones) porSeccion[s] = (porSeccion[s] ?: 0) + 1
            if (seccion != Seccion.TODO && seccion !in e.secciones) continue
            total++
            if (aciertos.size < tope) aciertos += Acierto(i, pieza)
        }
        return Hallado(aciertos, total, porSeccion)
    }

    // --- Las pestañas de la búsqueda (27-oct-2026) ---------------------------------------------

    /**
     * **Las pestañas de arriba de la búsqueda**: las de la ficha de un chat ([SeccionesDelChat.DE_LA_FICHA])
     * con «Todo» delante y sin las que no son una clase de cosa: «Proyectos» (ya son las filas de
     * chats) y «Fijados» (es de cada chat, no de todos).
     */
    val SECCIONES = listOf(
        Seccion.TODO, Seccion.FOTOS, Seccion.VIDEOS, Seccion.ARCHIVOS, Seccion.ENLACES, Seccion.VOZ, Seccion.DIBUJOS
    )

    /**
     * En qué pestañas de [SECCIONES] sale [m], con **la misma** regla que el chat ([deSeccion]): un
     * vídeo no es foto ni archivo, una página de PDF es archivo, un enlace es lo que lleva una
     * dirección… Así algo sale en «Archivos» aquí si y solo si sale en la ficha de su chat. Lo del
     * buzón no sale en ninguna (en el chat tampoco): solo en «Todo».
     */
    fun seccionesDe(m: Mensaje): Set<Seccion> {
        val una = listOf(m)
        return SECCIONES.filterTo(HashSet()) { it != Seccion.TODO && deSeccion(una, it).isNotEmpty() }
    }

    // --- Qué hace tocar un resultado (27-oct-2026) ---------------------------------------------

    /**
     * **Qué hace tocar un resultado.** El usuario (27-oct-2026): «al tocar un PDF, que lo abra
     * directamente; o, si quiero, que me lleve a ese mensaje. Ahora le doy clic y me abre el chat,
     * pero no me lleva al mensaje». Lo que tiene visor se abre en él, como al tocarlo en su burbuja
     * ([AbrirMensaje]); lo demás lleva a su sitio en el chat. «Ir al mensaje» (el botón de la fila,
     * o mantenerla pulsada) lleva siempre.
     */
    enum class AlTocar { ABRIR, IR_AL_MENSAJE }

    /**
     * La decisión, sin Android. Lleva a la burbuja en vez de abrir:
     *
     * - **Una nota sin enlace ni hoja**: no hay nada que abrir —en el chat, tocarla no hace nada— y
     *   lo que se quiere es leerla con lo de alrededor.
     * - **Una nota de voz**: se escucha con la barra del reproductor, que vive en el chat.
     * - **Lo que nombra un fichero que no está** en este aparato ([existe]; la sincronización trae
     *   los mensajes antes que los ficheros): abrirlo no abriría nada, y en el chat se ve que falta.
     * - Lo que no tiene a qué apuntar (un lienzo sin su dibujo, una página sin su PDF).
     */
    fun alTocar(m: Mensaje, existe: (String) -> Boolean = { true }): AlTocar {
        val ruta = m.ruta
        val abre = when (m.clase) {
            Clase.VOZ -> false
            Clase.NOTA -> m.referencia != null || primerEnlace(m.texto) != null
            Clase.PROYECTO -> m.referencia != null
            Clase.MINIAPP -> true
            Clase.DIBUJO, Clase.TABLA, Clase.CROQUIS -> m.referencia != null
            Clase.PAGINA -> ruta != null && m.pagina != null && m.referencia != null && existe(ruta)
            Clase.IMAGEN, Clase.ARCHIVO -> ruta != null && existe(ruta)
        }
        return if (abre) AlTocar.ABRIR else AlTocar.IR_AL_MENSAJE
    }

    /**
     * **A qué fila del chat se salta para llegar a [id]** (27-oct-2026), entre las que se ven
     * ([visibles]): la suya si está; si no, **la tarjeta de la carpeta que lo lleva dentro** (lo de
     * una carpeta no sale suelto en el chat, ver [CarpetaDePaginas.sueltosDelChat]), que es donde
     * está a la vista. Null si no se ve de ninguna forma: hay que quitar filtros o cambiar de pestaña.
     */
    fun adondeSaltar(visibles: List<Mensaje>, id: String): String? {
        if (visibles.any { it.id == id }) return id
        return visibles.firstOrNull { c ->
            CarpetaDePaginas.esCarpeta(c) && id in CarpetaDePaginas.dentroDeCarpetas(listOf(c))
        }?.id
    }

    /** El orden en que se busca: lo más nuevo primero y, a la par, por chat y código. */
    val ORDEN: Comparator<Entrada> = compareByDescending<Entrada> { it.cuando }.thenBy { it.chat }.thenBy { it.codigo }

    /** El trozo de un mensaje que se enseña en su fila, en una línea, y dónde resaltar. */
    data class Fragmento(val texto: String = "", val tramos: List<Tramo> = emptyList())

    private const val PUNTOS = "…"

    /**
     * **El trozo de [texto] que se enseña** con lo encontrado: en una línea, empezando como mucho
     * [antes] caracteres antes de la primera coincidencia (por el principio de una palabra, con «…»
     * delante si se corta) y de [largo] caracteres como mucho (con «…» detrás si se corta). Sin
     * coincidencia, el principio del texto.
     */
    fun fragmento(texto: String, aguja: String, antes: Int, largo: Int): Fragmento {
        val linea = buildString { for (c in texto.trim()) append(if (c.isWhitespace()) ' ' else c) }
        val todos = coincidencias(linea, aguja)
        var desde = 0
        todos.firstOrNull()?.let { primero ->
            // Dónde empieza cada carácter de verdad antes de lo encontrado.
            val previas = ArrayList<Int>()
            var i = 0
            while (i < primero.desde) { previas += i; i += Character.charCount(linea.codePointAt(i)) }
            if (previas.size > antes) {
                val corte = previas.size - antes
                // Hacia delante hasta el principio de una palabra; si no hay espacio, por donde caiga.
                val k = (corte until previas.size).firstOrNull { linea[previas[it]] == ' ' }
                desde = if (k != null) previas[k] + 1 else previas[corte]
            }
        }
        var hasta = linea.length
        run {
            var i = desde
            var n = 0
            while (i < linea.length) {
                if (n == largo) { hasta = i; return@run }
                i += Character.charCount(linea.codePointAt(i)); n++
            }
        }
        val delante = if (desde > 0) PUNTOS else ""
        val detras = if (hasta < linea.length) PUNTOS else ""
        val corrido = delante.length
        val tramos = todos.filter { it.desde >= desde && it.desde < hasta }
            .map { Tramo(it.desde - desde + corrido, minOf(it.hasta, hasta) - desde + corrido) }
        return Fragmento(delante + linea.substring(desde, hasta) + detras, tramos)
    }

    // --- Esperar a que se deje de escribir --------------------------------------------------

    /** Lo que se espera desde la última tecla antes de buscar en todos los chats. */
    const val ESPERA_MS = 180L

    /**
     * **La espera entre tecla y tecla** (el «debounce»): cada cambio la rearma, y toca buscar cuando
     * pasa [ESPERA_MS] sin cambios. En la pantalla es un `delay` que se cancela con cada tecla; esto
     * es lo mismo dicho en cuentas, para probarlo como en el PC.
     */
    class Retardo {
        private var desde: Long? = null

        fun cambio(ahora: Long) { desde = ahora }

        /** Si toca buscar ya. Si toca, se desarma: una vez por cambio. */
        fun toca(ahora: Long): Boolean {
            val d = desde ?: return false
            if (ahora - d < ESPERA_MS) return false
            desde = null
            return true
        }

        /** Cuánto falta para que toque, si hay algo esperando. */
        fun msHasta(ahora: Long): Long? = desde?.let { (ESPERA_MS - (ahora - it).coerceAtLeast(0)).coerceAtLeast(0) }
    }

    // --- La sección en la lista -------------------------------------------------------------

    /** Cuántos mensajes se enseñan de primeras, y cuántos más con cada «Ver más». */
    const val DE_PRIMERAS = 20

    /** Cuántos caracteres del mensaje se enseñan antes de lo encontrado, y cuántos como mucho en la línea. */
    const val ANTES = 22
    const val LARGO = 160

    /** Lo que hay en un punto de la sección. */
    sealed interface Elemento {
        data object Cabecera : Elemento
        /** El mensaje encontrado `n` (de los que se enseñan). */
        data class DeMensaje(val n: Int) : Elemento
        data object VerMas : Elemento
    }

    /**
     * **La sección «Mensajes»** debajo de los chats: su cabecera, una fila por mensaje y, si quedan,
     * «Ver más». Sin mensajes no hay sección, ni la cabecera. (En el PC además se cuentan sus píxeles
     * para pintarla a mano; aquí los pone la lista.)
     */
    fun elementos(mensajes: Int, verMas: Boolean): List<Elemento> =
        if (mensajes <= 0) emptyList()
        else buildList {
            add(Elemento.Cabecera)
            repeat(mensajes) { add(Elemento.DeMensaje(it)) }
            if (verMas) add(Elemento.VerMas)
        }

    // --- El índice de los mensajes ------------------------------------------------------------

    /**
     * Lo que se busca de un mensaje: su texto (la nota rica sin las rutas de sus fotos, que no son
     * de nadie; el pie `alt` sí cuenta), su transcripción y el nombre de su fichero. El pie de una
     * foto es su texto. No hay OCR guardado por mensaje, así que no se busca.
     */
    fun entradaDe(m: Mensaje): Entrada = Entrada.nueva(
        com.forge.pixpin.sincro.Disco.chatDe(m),
        com.forge.pixpin.sincro.Codigos.unico(m),
        m.cuando,
        listOf(NotaRica.sinRutasDeFotos(m.texto), m.transcripcion, m.nombre),
        id = m.id,
        secciones = seccionesDe(m)
    )

    /**
     * Si el mensaje tiene burbuja en su chat: lo que no la tiene (las lecciones, que se quitan antes
     * con [com.forge.pixpin.lecciones.LeccionesStore.sinLecciones], y los de sistema `auto-*`) no se
     * encuentra, porque tocarlo no llevaría a ninguna parte.
     */
    fun seVeEnElChat(m: Mensaje): Boolean = !com.forge.pixpin.asistente.ChatDelAsistente.esDeSistema(m)

    /**
     * Un mensaje encontrado, listo para pintar. [mensaje] es el mensaje entero (27-oct-2026): con él
     * la fila enseña su miniatura o su icono y se abre sin volver a leer el archivo.
     */
    data class Encontrado(
        val chat: String, val id: String, val cuando: Long, val fragmento: Fragmento,
        val mensaje: Mensaje? = null
    )

    /** Lo que da una búsqueda en el índice; [porSeccion] como en [Hallado]. */
    data class Resultado(
        val encontrados: List<Encontrado> = emptyList(),
        val total: Int = 0,
        val porSeccion: Map<Seccion, Int> = emptyMap()
    ) {
        val hayMas: Boolean get() = total > encontrados.size
    }

    /**
     * **El índice**: los mensajes de todos los chats, aplanados y en el orden en que se buscan. Se
     * pone al día con la lista entera del chat (aquí es un solo `guardados.jsonl` para todos), y un
     * mensaje que no cambió no se vuelve a aplanar. Fuera del hilo de la pantalla: lo llama la lista
     * desde `Dispatchers.Default`.
     */
    class Indice {
        private var porId: Map<String, Pair<Mensaje, Entrada>> = emptyMap()
        private var orden: List<Entrada> = emptyList()

        private var ultima: List<Mensaje>? = null

        @Synchronized
        fun ponerAlDia(mensajes: List<Mensaje>) {
            // La misma lista de antes (otra búsqueda sin que cambie el chat): nada que hacer.
            if (mensajes === ultima) return
            ultima = mensajes
            val nuevo = HashMap<String, Pair<Mensaje, Entrada>>(mensajes.size * 2)
            for (m in mensajes) {
                if (!seVeEnElChat(m)) continue
                val viejo = porId[m.id]
                nuevo[m.id] = if (viejo != null && viejo.first == m) viejo else m to entradaDe(m)
            }
            porId = nuevo
            orden = nuevo.values.map { it.second }.sortedWith(ORDEN)
        }

        @Synchronized
        fun buscar(aguja: String, tope: Int, seccion: Seccion = Seccion.TODO): Resultado {
            val hallado = buscar(orden, aguja, tope, seccion)
            return Resultado(
                hallado.aciertos.map { a ->
                    val e = orden[a.entrada]
                    val trozo = if (a.pieza < 0) Fragmento() else fragmento(e.piezas[a.pieza].texto, aguja, ANTES, LARGO)
                    Encontrado(e.chat, e.id, e.cuando, trozo, porId[e.id]?.first)
                },
                hallado.total,
                hallado.porSeccion
            )
        }
    }
}
