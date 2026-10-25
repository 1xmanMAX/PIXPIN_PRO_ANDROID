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
 * Todo esto es puro (sin Android): se prueba en la JVM con las pruebas del PC portadas.
 */
data class CarpetaDePaginas(
    val formato: Int = FORMATO,
    val paginas: List<Pagina> = emptyList(),
    /** Lo que no se entiende, tal cual. */
    val resto: Map<String, JsonElement> = emptyMap()
) {
    /** Una página: qué mensaje es y cómo se llama dentro de la carpeta. */
    data class Pagina(
        /** El `id` del mensaje, el mismo con el que se responde a un mensaje (`respondeA`). */
        val id: String,
        /** «Día 2». Vacío: se enseña el nombre del mensaje. */
        val titulo: String = "",
        val resto: Map<String, JsonElement> = emptyMap()
    )

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
        raiz["paginas"] = JsonArray(paginas.map { p ->
            val o = LinkedHashMap<String, JsonElement>()
            o["id"] = JsonPrimitive(p.id)
            o["titulo"] = JsonPrimitive(p.titulo)
            p.resto.forEach { (k, v) -> if (k != "id" && k != "titulo") o[k] = v }
            JsonObject(o)
        })
        resto.forEach { (k, v) -> if (k != "formato" && k != "paginas") raiz[k] = v }
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

        private val json = Json { ignoreUnknownKeys = true }

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
                        resto = o.filterKeys { it != "id" && it != "titulo" }
                    )
                }
                else -> throw IllegalArgumentException("paginas")
            }
            return CarpetaDePaginas(formato, paginas, raiz.filterKeys { it != "formato" && it != "paginas" })
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
