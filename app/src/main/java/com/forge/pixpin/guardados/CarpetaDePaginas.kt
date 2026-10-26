package com.forge.pixpin.guardados

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * **Las carpetas de páginas del chat** (25-oct-2026, portado del PC: `pixpin-proyecto::
 * carpeta_de_paginas`): un cuaderno con nombre dentro de la conversación, «como si fuera un PDF».
 *
 * El usuario del PC: «alguien usa la plantilla de diario: el día 1 la llena en un lienzo, el día 2
 * en otro, y en medio escribe más cosas en el chat. Con los días las hojas quedan dispersas». La
 * carpeta las junta **sin moverlas**: al abrirla se ven sus páginas en orden —Día 1, Día 2…— y al
 * exportarla sale un PDF con una página por cada una.
 *
 * ## Cómo viaja (tiene que ser igual que en el PC)
 *
 * Una carpeta es **un mensaje más**, de clase [Clase.MINIAPP] con `miniapp = "carpeta"` y el
 * documento entero en `texto`, igual que la tabla del PC ([TablasDelChat]). El PC eligió este hueco
 * y no una clase nueva **porque este teléfono no la sabría leer**: el `enum` [Clase] tiene diez
 * palabras y kotlinx tumbaría el mensaje que trajera otra.
 *
 * ```json
 * {"formato":1,"paginas":[{"id":"1761400000000","titulo":"Día 1"},
 *                          {"id":"1761486400000","titulo":"Día 2"}]}
 * ```
 *
 * Las páginas **siguen siendo mensajes normales** del chat: la carpeta solo guarda la lista
 * ordenada de sus `id` y el título de cada una. Un lienzo puede estar en varias carpetas, y si se
 * borra la carpeta no se rompe: la página que ya no está se salta al leerla ([vivas]) y se quita
 * al guardar ([podar]). El nombre de la carpeta es el `nombre` del mensaje; **lo que no se entiende
 * del documento —un campo de una versión más nueva— se conserva al reescribirlo** ([resto]), como
 * hace el `#[serde(flatten)]` del PC.
 *
 * ## La carpeta de una grabación (25-oct-2026, PC `2061e6b` y `40daac7`)
 *
 * **Una clase o reunión grabada entra en el chat dentro de su carpeta** (el usuario del PC: «una
 * grabación completa siempre debería ir dentro de una carpeta, para que en el chat no salgan varias
 * imágenes y un audio, que ocupa mucho espacio»). Es una carpeta como las otras con campos de más,
 * todos opcionales, que solo se escriben en ella (un aparato que no los sepa los guarda en [resto] y
 * la sigue leyendo como una carpeta normal; §5.1 de `manual-sincronizar-lo-nuevo.md` del PC):
 *
 * - `tipo: "grabacion"` ([TIPO_GRABACION]). Sin él (o vacío), una carpeta de páginas de siempre.
 * - `voz`: el `id` de su nota de voz (no va si la nota no se pudo cerrar).
 * - `video`: el `id` de su vídeo (no va si no se grabó la pantalla).
 * - `duracionMs`: lo que duró, para la tarjeta sin leer la nota.
 * - En cada página, `ms` (el minuto en que se hizo; la voz y el vídeo, `0`) y `adjunto: true` si se
 *   adjuntó a la clase después y **no es de lo grabado** (un PDF del profesor, una foto de la
 *   pizarra). `adjunto` no va si es `false`.
 *
 * ```json
 * {"formato":1,"tipo":"grabacion","voz":"1761400000001","video":"1761400000002","duracionMs":5400000,
 *  "paginas":[{"id":"1761400000001","titulo":"","ms":0},{"id":"1761400000002","titulo":"","ms":0},
 *             {"id":"1761400000003","titulo":"⏱ 1:05","ms":65000},{"id":"9","titulo":"Apuntes","adjunto":true}]}
 * ```
 *
 * Las claves van **en el orden del PC** (el de los campos de su `struct`): `formato`, `tipo`, `voz`,
 * `video`, `duracionMs`, `paginas`; y en cada página `id`, `titulo`, `ms`, `adjunto`. Así lo que se
 * escribe aquí es byte a byte lo del PC y la fusión no ve cambios donde no los hay.
 *
 * **Lo de dentro no sale suelto en el chat** ([dentroDeCarpetas], PC `40daac7`): lo que está en una
 * carpeta, de cualquier tipo, lo representa la tarjeta de su carpeta. Sigue siendo un mensaje normal
 * (viaja, se busca, sale en la galería) y al quitarlo de la carpeta vuelve a verse.
 *
 * Todo esto es puro (sin Android): se prueba en la JVM con las pruebas del PC portadas.
 */
data class CarpetaDePaginas(
    val formato: Int = FORMATO,
    val paginas: List<Pagina> = emptyList(),
    /** Lo que no se entiende, tal cual. */
    val resto: Map<String, JsonElement> = emptyMap(),
    /** Vacío: una carpeta de páginas. [TIPO_GRABACION]: una grabación. */
    val tipo: String = "",
    /** En una grabación, el `id` de su nota de voz (vacío si no entró). */
    val voz: String = "",
    /** En una grabación, el `id` de su vídeo (vacío si no lo hay). */
    val video: String = "",
    /** En una grabación, lo que duró. */
    val duracionMs: Long = 0
) {
    /** Una página: qué mensaje es y cómo se llama dentro de la carpeta. */
    data class Pagina(
        /** El `id` del mensaje, el mismo con el que se responde a un mensaje (`respondeA`). */
        val id: String,
        /** «Día 2». Vacío: se enseña el nombre del mensaje. */
        val titulo: String = "",
        val resto: Map<String, JsonElement> = emptyMap(),
        /** En una grabación, el minuto (ms desde que empezó) en que se hizo. */
        val ms: Long? = null,
        /** En una grabación, que se adjuntó a la clase y no es de lo grabado. */
        val adjunto: Boolean = false
    )

    /** Si es la carpeta de una grabación. */
    val esGrabacion: Boolean get() = tipo == TIPO_GRABACION

    /**
     * **Qué es cada pieza de una carpeta**, para su icono y su visor (26-oct-2026, `icono_de` de
     * `ventana_chat/carpetas.rs` del PC): un vídeo con la cámara (`VIDEOCAM`) aunque sea un archivo o
     * una foto, un lienzo con el lápiz (`DRAW`), una página de un PDF con el libro (`MENU_BOOK`), una
     * foto (`IMAGE`), una nota de voz con el micrófono (`MIC`), una nota (`EDIT_NOTE`) y lo demás con
     * la hoja (`DESCRIPTION`).
     */
    enum class Pieza { VIDEO, LIENZO, PAGINA, IMAGEN, VOZ, NOTA, ARCHIVO }

    /** **Lo que es de la grabación**: la nota, el vídeo y las capturas, en su orden. En una carpeta de páginas, todas. */
    fun deLaGrabacion(): List<Pagina> = paginas.filter { !it.adjunto }

    /** **Las capturas de una grabación**: lo grabado que no es ni su nota ni su vídeo, en su orden. */
    fun capturas(): List<Pagina> = deLaGrabacion().filter { it.id != voz && it.id != video }

    /** **Lo adjuntado a la clase** (que no es de lo grabado), en su orden. */
    fun adjuntos(): List<Pagina> = paginas.filter { it.adjunto }

    /** **Adjunta a la clase** el mensaje [id], al final y marcado como adjunto. Null si ya estaba. */
    fun adjuntar(id: String, titulo: String): CarpetaDePaginas? =
        anadir(id, titulo)?.let { c -> c.copy(paginas = c.paginas.dropLast(1) + c.paginas.last().copy(adjunto = true)) }

    /** Añade al final una pieza de la grabación, con su minuto. Como [anadir]: null si ya estaba. */
    fun anadirAlMinuto(id: String, titulo: String, ms: Long): CarpetaDePaginas? =
        anadir(id, titulo)?.let { c -> c.copy(paginas = c.paginas.dropLast(1) + c.paginas.last().copy(ms = ms.coerceAtLeast(0))) }

    val size: Int get() = paginas.size
    fun isEmpty() = paginas.isEmpty()

    /** Dónde está el mensaje [id], o -1. */
    fun posicion(id: String): Int = paginas.indexOfFirst { it.id == id }

    fun contiene(id: String) = posicion(id) >= 0

    /**
     * **El documento, para ir en `Mensaje.texto`**, con las claves en el orden del PC (`formato`,
     * `paginas` y detrás lo que no se entendía). Una versión más nueva se respeta: el formato
     * nunca baja.
     */
    fun escribir(): String {
        val raiz = LinkedHashMap<String, JsonElement>()
        raiz["formato"] = JsonPrimitive(maxOf(formato, FORMATO))
        if (tipo.isNotEmpty()) raiz["tipo"] = JsonPrimitive(tipo)
        if (voz.isNotEmpty()) raiz["voz"] = JsonPrimitive(voz)
        if (video.isNotEmpty()) raiz["video"] = JsonPrimitive(video)
        if (duracionMs != 0L) raiz["duracionMs"] = JsonPrimitive(duracionMs)
        raiz["paginas"] = JsonArray(paginas.map { p ->
            val o = LinkedHashMap<String, JsonElement>()
            o["id"] = JsonPrimitive(p.id)
            o["titulo"] = JsonPrimitive(p.titulo)
            p.ms?.let { o["ms"] = JsonPrimitive(it) }
            if (p.adjunto) o["adjunto"] = JsonPrimitive(true)
            p.resto.forEach { (k, v) -> if (k !in CLAVES_DE_PAGINA) o[k] = v }
            JsonObject(o)
        })
        resto.forEach { (k, v) -> if (k !in CLAVES_DEL_DOCUMENTO) raiz[k] = v }
        return JsonObject(raiz).toString()
    }

    /**
     * **Añade una página al final.** Null si ya estaba (o sin id): la misma hoja dos veces en un
     * cuaderno solo confunde.
     */
    fun anadir(id: String, titulo: String): CarpetaDePaginas? {
        if (id.isEmpty() || contiene(id)) return null
        return copy(paginas = paginas + Pagina(id, limpio(titulo)))
    }

    /** Quita la página del mensaje [id]; el mensaje no se toca. Null si no estaba. */
    fun quitar(id: String): CarpetaDePaginas? {
        val i = posicion(id)
        if (i < 0) return null
        return copy(paginas = paginas.filterIndexed { k, _ -> k != i })
    }

    /**
     * **Lleva la página [desde] al puesto [hasta]** (los dos desde 0; un [hasta] de más cae al
     * final). Null si no se movió.
     */
    fun mover(desde: Int, hasta: Int): CarpetaDePaginas? {
        if (desde < 0 || desde >= paginas.size) return null
        val destino = hasta.coerceIn(0, paginas.size - 1)
        if (desde == destino) return null
        val l = paginas.toMutableList()
        val p = l.removeAt(desde)
        l.add(destino, p)
        return copy(paginas = l)
    }

    fun subir(i: Int): CarpetaDePaginas? = if (i > 0) mover(i, i - 1) else null

    fun bajar(i: Int): CarpetaDePaginas? = mover(i, i + 1)

    /** Otro título para la página [i]. Null si no cambió. */
    fun renombrar(i: Int, titulo: String): CarpetaDePaginas? {
        val t = limpio(titulo)
        val p = paginas.getOrNull(i) ?: return null
        if (p.titulo == t) return null
        return copy(paginas = paginas.mapIndexed { k, x -> if (k == i) x.copy(titulo = t) else x })
    }

    /**
     * **Las páginas que siguen en el chat**, en su orden y con su posición en [mensajes]. Una
     * página cuyo mensaje se borró se salta sin más.
     */
    fun vivas(mensajes: List<Mensaje>): List<Pair<Int, Pagina>> {
        val sitio = HashMap<String, Int>()
        mensajes.forEachIndexed { i, m -> sitio.putIfAbsent(m.id, i) }
        return paginas.mapNotNull { p -> sitio[p.id]?.let { it to p } }
    }

    /** Sin las páginas cuyo mensaje ya no existe, y cuántas se quitaron. */
    fun podar(existe: (String) -> Boolean): Pair<CarpetaDePaginas, Int> {
        val quedan = paginas.filter { existe(it.id) }
        return copy(paginas = quedan) to (paginas.size - quedan.size)
    }

    /**
     * **El título de la página que viene**: el de la última con su número uno más («Día 3» tras
     * «Día 2»). Sin páginas, o si la última no lleva número, [porDefecto] con el puesto que le
     * toca («Página 4»).
     */
    fun tituloSiguiente(porDefecto: String): String =
        serie() ?: "${porDefecto.trim()} ${paginas.size + 1}"

    /**
     * **El título con el que entra una hoja que ya existía** («Añadir a carpeta…»): el siguiente
     * de la serie si la carpeta lleva una; si no, el nombre que ya tenía la hoja; si no tenía,
     * el de serie.
     */
    fun tituloAlAnadir(nombreDeLaHoja: String, porDefecto: String): String =
        serie() ?: if (nombreDeLaHoja.isNotBlank()) limpio(nombreDeLaHoja) else tituloSiguiente(porDefecto)

    private fun serie(): String? =
        paginas.lastOrNull { it.titulo.isNotBlank() }?.let { siguienteTitulo(it.titulo) }

    companion object {
        /** La palabra de `Mensaje.miniapp`: la del PC (`carpeta_de_paginas::MINIAPP`). No se cambia nunca. */
        const val MINIAPP = "carpeta"

        /** La versión del documento que se escribe. */
        const val FORMATO = 1

        /** Lo más largo de un título de página, como el nombre de un lienzo (en letras). */
        const val TOPE_TITULO = 80

        /** El prefijo de los mensajes de sistema del PC (`cuaderno::PREFIJO_DE_SISTEMA`). */
        const val PREFIJO_DE_SISTEMA = "auto-"

        /** El `tipo` de la carpeta de una clase o reunión grabada (`TIPO_GRABACION` del PC). Es un dato guardado. */
        const val TIPO_GRABACION = "grabacion"

        /** Las claves que se leen a campos: lo demás va a `resto` tal cual. */
        private val CLAVES_DEL_DOCUMENTO = setOf("formato", "tipo", "voz", "video", "duracionMs", "paginas")
        private val CLAVES_DE_PAGINA = setOf("id", "titulo", "ms", "adjunto")

        private val json = Json { ignoreUnknownKeys = true }

        /** **Una carpeta de grabación vacía**: de la nota [voz] (vacía si no entró) y que duró [duracionMs]. */
        fun grabacion(voz: String, duracionMs: Long): CarpetaDePaginas =
            CarpetaDePaginas(tipo = TIPO_GRABACION, voz = voz, duracionMs = duracionMs.coerceAtLeast(0))

        /** Si el mensaje es la carpeta de una grabación. */
        fun esGrabacion(m: Mensaje): Boolean = deMensaje(m)?.esGrabacion == true

        /**
         * **Los `id` de lo que está dentro de alguna carpeta** de la conversación (de páginas o de una
         * grabación): lo que el chat no enseña suelto, porque lo representa la tarjeta de su carpeta
         * (`dentro_de_carpetas` del PC, 40daac7).
         */
        fun dentroDeCarpetas(mensajes: List<Mensaje>): Set<String> =
            mensajes.mapNotNull(::deMensaje).flatMapTo(HashSet()) { c -> c.paginas.map { it.id } }

        /**
         * **Lo que el chat enseña suelto** (26-oct-2026): [mensajes] sin lo que está dentro de una
         * carpeta **que también está en [mensajes]**.
         *
         * **Por qué no basta [dentroDeCarpetas] de todo el archivo.** En la v0.142.0 se sacaba de
         * todos los mensajes (todos los chats, las lecciones, lo del buzón) y se quitaba de la lista
         * ya filtrada: si la carpeta no estaba en esa lista —otra sección, en el buzón, con un emoji
         * puesto, o con su documento roto—, lo de dentro se quitaba igual y **no quedaba nada a la
         * vista**: ni la tarjeta ni las piezas. El usuario: «no hay una interfaz para ver lo de las
         * grabaciones, ni nada». Ahora solo se esconde lo que tiene su tarjeta al lado; si la carpeta
         * falta, sus piezas salen sueltas, como en un aparato que no sabe de carpetas (§5 del manual
         * del PC: «un aparato que no la sepa lo enseña suelto, sin perder nada»).
         */
        fun sueltosDelChat(mensajes: List<Mensaje>): List<Mensaje> {
            val dentro = dentroDeCarpetas(mensajes)
            if (dentro.isEmpty()) return mensajes
            return mensajes.filter { it.id !in dentro || esCarpeta(it) }
        }

        /** Qué pieza es [m] ([Pieza]), como `icono_de` del PC. */
        fun piezaDe(m: Mensaje): Pieza = when {
            SeccionesDelChat.esVideo(m) -> Pieza.VIDEO
            m.clase == Clase.DIBUJO -> Pieza.LIENZO
            m.clase == Clase.PAGINA -> Pieza.PAGINA
            m.clase == Clase.IMAGEN -> Pieza.IMAGEN
            m.clase == Clase.VOZ -> Pieza.VOZ
            m.clase == Clase.NOTA -> Pieza.NOTA
            else -> Pieza.ARCHIVO
        }

        /** Si el mensaje es una carpeta. */
        fun esCarpeta(m: Mensaje): Boolean = m.clase == Clase.MINIAPP && m.miniapp == MINIAPP

        /** Las carpetas de una conversación, por su posición en la lista. */
        fun carpetasDe(mensajes: List<Mensaje>): List<Int> =
            mensajes.indices.filter { esCarpeta(mensajes[it]) }

        /**
         * Que la página de un mensaje puede ir en una carpeta: lo que se ve como una hoja (un
         * lienzo, una página de un PDF, una foto) y también una nota o un archivo. Una carpeta
         * dentro de otra, no; un mensaje de sistema, tampoco.
         */
        fun puedeIr(m: Mensaje): Boolean =
            !esCarpeta(m) && !m.id.startsWith(PREFIJO_DE_SISTEMA) && m.id.isNotEmpty()

        /**
         * **Lee el documento de una carpeta.** Un texto vacío es una carpeta vacía (así nace); uno
         * que no se entiende lanza, y quien llama **no lo sobreescribe** (reescribir lo que no se
         * entiende sería perderlo).
         */
        fun leer(texto: String): CarpetaDePaginas {
            if (texto.isBlank()) return CarpetaDePaginas()
            val raiz = json.parseToJsonElement(texto) as? JsonObject
                ?: throw IllegalArgumentException("una carpeta es un objeto JSON")
            val formato = when (val f = raiz["formato"]) {
                null, JsonNull -> FORMATO
                is JsonPrimitive -> f.intOrNull ?: throw IllegalArgumentException("formato")
                else -> throw IllegalArgumentException("formato")
            }
            val paginas = when (val l = raiz["paginas"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> l.map { e ->
                    val o = e as? JsonObject ?: throw IllegalArgumentException("página")
                    Pagina(
                        id = cadena(o["id"]),
                        titulo = cadena(o["titulo"]),
                        resto = o.filterKeys { it !in CLAVES_DE_PAGINA },
                        ms = numero(o["ms"]),
                        adjunto = logico(o["adjunto"])
                    )
                }
                else -> throw IllegalArgumentException("paginas")
            }
            return CarpetaDePaginas(
                formato, paginas, raiz.filterKeys { it !in CLAVES_DEL_DOCUMENTO },
                tipo = cadena(raiz["tipo"]),
                voz = cadena(raiz["voz"]),
                video = cadena(raiz["video"]),
                duracionMs = numero(raiz["duracionMs"]) ?: 0
            )
        }

        /** Un número entero, o null si no va; otra cosa no se entiende (como el `Option<i64>` del PC). */
        private fun numero(e: JsonElement?): Long? = when (e) {
            null, JsonNull -> null
            is JsonPrimitive ->
                if (e.isString) throw IllegalArgumentException("se esperaba un número")
                else e.content.toLongOrNull() ?: throw IllegalArgumentException("se esperaba un número entero")
            else -> throw IllegalArgumentException("se esperaba un número")
        }

        private fun logico(e: JsonElement?): Boolean = when (e) {
            null, JsonNull -> false
            is JsonPrimitive ->
                if (e.isString) throw IllegalArgumentException("se esperaba sí o no")
                else e.content.toBooleanStrictOrNull() ?: throw IllegalArgumentException("se esperaba sí o no")
            else -> throw IllegalArgumentException("se esperaba sí o no")
        }

        private fun cadena(e: JsonElement?): String = when (e) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (e.isString) e.content else throw IllegalArgumentException("se esperaba texto")
            else -> throw IllegalArgumentException("se esperaba texto")
        }

        /** La carpeta de un mensaje, o null si no es una carpeta o su documento no se entiende. */
        fun deMensaje(m: Mensaje): CarpetaDePaginas? =
            if (esCarpeta(m)) runCatching { leer(m.texto) }.getOrNull() else null

        /**
         * **El id de una carpeta nueva**: el milisegundo, como en el PC (`Mensaje::miniapp` pone
         * `sello.cuando`), **sin repetir el de otro mensaje** (313032d del PC: un lienzo y la
         * carpeta hechos en el mismo milisegundo compartían id y la carpeta se confundía con el
         * lienzo). Devuelve el `cuando` que toca, que es también el id.
         */
        fun cuandoLibre(ahora: Long, mensajes: List<Mensaje>): Long {
            val usados = mensajes.mapTo(HashSet()) { it.id }
            var cuando = ahora
            while (cuando.toString() in usados) cuando++
            return cuando
        }

        /**
         * **Un mensaje de carpeta nuevo**, vacío o con [primera] dentro, con su nombre recortado.
         * Sin número ni sello: los pone [MensajesStore.anadir] como a cualquier mensaje.
         */
        fun mensajeNuevo(
            nombre: String,
            cuando: Long,
            proyecto: String?,
            carpeta: CarpetaDePaginas = CarpetaDePaginas()
        ): Mensaje = Mensaje(
            id = cuando.toString(),
            cuando = cuando,
            clase = Clase.MINIAPP,
            miniapp = MINIAPP,
            nombre = tomar(nombre.trim(), TOPE_TITULO),
            texto = carpeta.escribir(),
            proyecto = proyecto
        )

        /**
         * El nombre de una carpeta nueva: [base] («Cuaderno») si no lo lleva otra carpeta del
         * chat; si no, «Cuaderno 2», «Cuaderno 3»… (`carpetas::nombre_libre` del PC).
         */
        fun nombreLibre(base: String, mensajes: List<Mensaje>): String {
            val usados = mensajes.filter(::esCarpeta).map { it.nombre.trim() }.toSet()
            if (base !in usados) return base
            return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in usados }
        }

        /**
         * **El título siguiente a [t]**: el último número que lleva, uno más, con los mismos
         * ceros delante («Día 09» → «Día 10», «Página 7 · lunes» → «Página 8 · lunes»). Null si no
         * lleva número, o si es enorme (una fecha pegada, un código: más de nueve cifras).
         */
        fun siguienteTitulo(t: String): String? {
            val s = t.trim()
            val ultima = s.indexOfLast { it in '0'..'9' }
            if (ultima < 0) return null
            val fin = ultima + 1
            var inicio = ultima
            while (inicio > 0 && s[inicio - 1] in '0'..'9') inicio--
            val cifras = s.substring(inicio, fin)
            if (cifras.length > 9) return null
            val n = cifras.toLongOrNull() ?: return null
            val siguiente = (n + 1).toString().padStart(cifras.length, '0')
            return s.substring(0, inicio) + siguiente + s.substring(fin)
        }

        /** Un título sin saltos de línea, sin espacios de más y con su tope. */
        fun limpio(t: String): String =
            tomar(t.replace('\n', ' ').replace('\r', ' ').trim(), TOPE_TITULO)

        /** Las primeras [n] letras de verdad (no unidades UTF-16: un emoji es una). */
        private fun tomar(s: String, n: Int): String {
            if (s.codePointCount(0, s.length) <= n) return s
            return s.substring(0, s.offsetByCodePoints(0, n))
        }
    }
}
