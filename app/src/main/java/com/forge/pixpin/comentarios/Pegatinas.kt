package com.forge.pixpin.comentarios

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **El comentario avanzado: la pegatina con su minichat** (4-nov-2026, `crates/pindesk-comentarios` del PC,
 * `docs/comentarios.md`). El usuario: «un emoticono de comentario, cuatro tipos (tres de urgencia y uno de
 * comentario normal), que pego donde sea como una pegatina; una vez puesto funciona como un minichat».
 *
 * Las pegatinas van en el mismo `.comentarios.json` que los comentarios de las notas, en la clave
 * [Fichero.CLAVE] (`pegatinas`); lo demás del fichero no se toca. Ids `<aparato>-<hora hex>-<n>`, horas en
 * ms UTC, y `cambiado` que siempre avanza (`max(ahora, cambiado + 1)`). **Nada se borra**: quitar deja su
 * lápida. Lo que no se conoce, en cualquier nivel, se conserva ([resto]).
 */
enum class Tipo(val nombre: String, val color: Long, val colorDelSigno: Long, val emoji: String, val signo: String) {
    URGENTE("urgente", 0xFFE53935, 0xFFFFFFFF, "🔴", "!!!"),
    ALTA("alta", 0xFFFB8C00, 0xFFFFFFFF, "🟠", "!!"),
    MEDIA("media", 0xFFFDD835, 0xFF3E2F00, "🟡", "!"),
    NOTA("nota", 0xFF5C6BC0, 0xFFFFFFFF, "💬", "");

    val esUrgencia: Boolean get() = this != NOTA
    fun siguiente(): Tipo = entries[(ordinal + 1) % entries.size]

    companion object {
        fun deNombre(s: String?): Tipo = when (s?.trim()?.lowercase()) {
            "urgente" -> URGENTE
            "alta" -> ALTA
            "media" -> MEDIA
            else -> NOTA
        }
    }
}

/** Dónde está pegada (`ancla.rs`). Aquí solo se usan `pagina` y `escena`; `texto` se conserva tal cual. */
data class Ancla(
    val sitio: String,
    val pagina: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
    val sobre: String = "",
    val cita: JsonElement? = null,
    val posicion: JsonElement? = null,
    val resto: Map<String, JsonElement> = emptyMap()
) {
    /** Si se sabe dónde pintarla: con su punto. */
    val conPunto: Boolean get() = (sitio == PAGINA || sitio == ESCENA) && x?.isFinite() == true && y?.isFinite() == true

    fun movida(x: Double, y: Double): Ancla = copy(x = redondo(x), y = redondo(y))

    companion object {
        const val PAGINA = "pagina"
        const val ESCENA = "escena"
        const val TEXTO = "texto"

        fun redondo(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
        fun pagina(pagina: Int, x: Double, y: Double) = Ancla(PAGINA, pagina, redondo(x), redondo(y))
        fun escena(x: Double, y: Double) = Ancla(ESCENA, null, redondo(x), redondo(y))
    }
}

/** Lo adjunto a una entrada: un fichero, una imagen, un atajo o un lienzo. Lo que no se conoce, en [resto]. */
data class Adjunto(
    val tipo: String = ARCHIVO,
    val ruta: String? = null,
    val nombre: String? = null,
    val tinta: String? = null,
    val chat: String? = null,
    val mensaje: String? = null,
    val lienzo: String? = null,
    val elemento: String? = null,
    val punto: List<Double>? = null,
    val rotulo: String? = null,
    val resto: Map<String, JsonElement> = emptyMap()
) {
    fun rutas(): List<String> = listOfNotNull(ruta, tinta)

    companion object {
        const val ARCHIVO = "archivo"
        const val IMAGEN = "imagen"
        const val MENSAJE = "mensaje"
        const val ELEMENTO = "elemento"
        const val LIENZO = "lienzo"

        fun esImagen(nombre: String): Boolean {
            val n = nombre.lowercase()
            return listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff").any { n.endsWith(it) }
        }

        fun archivo(ruta: String, nombre: String) = Adjunto(if (esImagen(nombre)) IMAGEN else ARCHIVO, ruta = ruta, nombre = nombre)
    }
}

data class Entrada(
    val id: String,
    val autor: String = "",
    val aparato: String = "",
    val cuando: Long = 0,
    val cambiado: Long = 0,
    val texto: String = "",
    val adjuntos: List<Adjunto> = emptyList(),
    val borrado: Boolean = false,
    val resto: Map<String, JsonElement> = emptyMap()
)

data class Comentario(
    val id: String,
    val tipo: Tipo = Tipo.NOTA,
    val ancla: Ancla = Ancla(""),
    val autor: String = "",
    val aparato: String = "",
    val creado: Long = 0,
    val cambiado: Long = 0,
    val resuelto: Boolean = false,
    val borrado: Boolean = false,
    val tarea: String? = null,
    val hilo: List<Entrada> = emptyList(),
    val resto: Map<String, JsonElement> = emptyMap()
) {
    fun entradas(): List<Entrada> = hilo.filter { !it.borrado }

    /** La primera línea con texto, hasta 80 letras. */
    fun resumen(): String {
        val linea = entradas().firstOrNull { it.texto.isNotBlank() }?.texto?.lines()?.firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        return if (linea.length > 80) linea.take(80) + "…" else linea
    }

    /** Lo último que pasó en ella o en su hilo. */
    fun ultimo(): Long = hilo.fold(maxOf(cambiado, creado)) { m, e -> maxOf(m, e.cambiado, e.cuando) }
}

/** Quién escribe: su nombre y el código de su aparato. */
data class Quien(val autor: String, val aparato: String)

/** Las pegatinas de un documento, con sus operaciones (`Pegatinas` de `modelo.rs`). */
data class Pegatinas(val lista: List<Comentario> = emptyList()) {

    fun vivas(): List<Comentario> = lista.filter { !it.borrado }
    fun get(id: String): Comentario? = lista.firstOrNull { it.id == id && !it.borrado }
    fun abiertas(): Int = vivas().count { !it.resuelto }

    fun idNuevo(aparato: String, cuando: Long): String {
        val usados = HashSet<String>()
        for (c in lista) { usados += c.id; c.hilo.forEach { usados += it.id } }
        val a = aparato.filter { it.isLetterOrDigit() && it.code < 128 }.take(16).ifEmpty { "x" }
        var n = 0
        while (true) {
            val id = "$a-${java.lang.Long.toHexString(cuando)}-$n"
            if (id !in usados) return id
            n++
        }
    }

    /** Pone una nueva y devuelve su id. */
    fun poner(tipo: Tipo, ancla: Ancla, quien: Quien, cuando: Long): Pair<Pegatinas, String> {
        val id = idNuevo(quien.aparato, cuando)
        return copy(lista = lista + Comentario(id, tipo, ancla, quien.autor, quien.aparato, cuando, cuando)) to id
    }

    private fun tocar(id: String, cuando: Long, f: (Comentario) -> Comentario): Pegatinas {
        val i = lista.indexOfFirst { it.id == id && !it.borrado }
        if (i < 0) return this
        val antes = lista[i]
        val despues = f(antes)
        if (despues == antes) return this
        return copy(lista = lista.toMutableList().also { it[i] = despues.copy(cambiado = maxOf(cuando, antes.cambiado + 1)) })
    }

    fun mover(id: String, ancla: Ancla, cuando: Long) = tocar(id, cuando) { it.copy(ancla = ancla) }
    fun cambiarTipo(id: String, tipo: Tipo, cuando: Long) = tocar(id, cuando) { it.copy(tipo = tipo) }
    fun resolver(id: String, si: Boolean, cuando: Long) = tocar(id, cuando) { it.copy(resuelto = si) }
    fun vincularTarea(id: String, tarea: String, cuando: Long) = tocar(id, cuando) { it.copy(tarea = tarea) }
    fun quitar(id: String, cuando: Long) = tocar(id, cuando) { c ->
        c.copy(borrado = true, hilo = c.hilo.map { e -> e.copy(borrado = true, texto = "", adjuntos = emptyList(), cambiado = maxOf(e.cambiado, cuando)) })
    }

    /** Escribe en su hilo. Sin texto ni adjuntos, nada. Devuelve el id de la entrada. */
    fun escribir(id: String, quien: Quien, cuando: Long, texto: String, adjuntos: List<Adjunto> = emptyList(), conId: String? = null): Pair<Pegatinas, String?> {
        val t = texto.trim().replace("\r\n", "\n")
        if (t.isEmpty() && adjuntos.isEmpty()) return this to null
        val i = lista.indexOfFirst { it.id == id && !it.borrado }
        if (i < 0) return this to null
        val eid = conId ?: idNuevo(quien.aparato, cuando)
        val c = lista[i]
        val nueva = c.copy(hilo = c.hilo + Entrada(eid, quien.autor, quien.aparato, cuando, cuando, t, adjuntos))
        return copy(lista = lista.toMutableList().also { it[i] = nueva }) to eid
    }

    private fun tocarEntrada(id: String, entrada: String, cuando: Long, f: (Entrada) -> Entrada): Pegatinas {
        val i = lista.indexOfFirst { it.id == id && !it.borrado }
        if (i < 0) return this
        val c = lista[i]
        val j = c.hilo.indexOfFirst { it.id == entrada && !it.borrado }
        if (j < 0) return this
        val antes = c.hilo[j]
        val despues = f(antes)
        if (despues == antes) return this
        val hilo = c.hilo.toMutableList().also { it[j] = despues.copy(cambiado = maxOf(cuando, antes.cambiado + 1)) }
        return copy(lista = lista.toMutableList().also { it[i] = c.copy(hilo = hilo) })
    }

    fun quitarEntrada(id: String, entrada: String, cuando: Long) =
        tocarEntrada(id, entrada, cuando) { it.copy(borrado = true, texto = "", adjuntos = emptyList()) }

    /** El lienzo de lo anotado sobre el adjunto [k] (una imagen) de una entrada (`poner_tinta`). */
    fun ponerTinta(id: String, entrada: String, k: Int, tinta: String, cuando: Long) =
        tocarEntrada(id, entrada, cuando) { e -> e.copy(adjuntos = e.adjuntos.mapIndexed { i, a -> if (i == k) a.copy(tinta = tinta) else a }) }

    fun editar(id: String, entrada: String, texto: String, cuando: Long): Pegatinas {
        val t = texto.trim().replace("\r\n", "\n")
        return tocarEntrada(id, entrada, cuando) { e -> e.copy(texto = t, borrado = e.borrado || (t.isEmpty() && e.adjuntos.isEmpty())) }
    }

    /** Las rutas de los adjuntos de las vivas, sin repetir (para que viajen con su documento). */
    fun rutas(): List<String> = vivas().flatMap { c -> c.entradas().flatMap { e -> e.adjuntos.flatMap { it.rutas() } } }.distinct()

    /**
     * **La lista «Comentarios (n)»**: las abiertas primero, por urgencia (urgente, alta, media, nota) y dentro
     * lo más reciente arriba; las resueltas al final (`lista.rs`).
     */
    fun ordenadas(): List<Comentario> = vivas().sortedWith(
        compareBy<Comentario> { it.resuelto }.thenBy { it.tipo.ordinal }.thenByDescending { it.ultimo() }.thenBy { it.id }
    )
}

/** El `.comentarios.json` (`fichero.rs`): las pegatinas en su clave, lo demás tal cual. */
object Fichero {
    const val CLAVE = "pegatinas"
    const val VERSION = 1

    private val BONITO = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val COMPACTO = Json

    /** Lo que hay en [texto], o null si el JSON está roto (entonces no se escribe encima). Vacío, nada. */
    fun leer(texto: String?): Pegatinas? {
        if (texto.isNullOrBlank()) return Pegatinas()
        val o = runCatching { Json.parseToJsonElement(texto) as? JsonObject }.getOrNull() ?: return null
        val l = o[CLAVE] as? JsonArray ?: return Pegatinas()
        return Pegatinas(l.mapNotNull { (it as? JsonObject)?.let(::comentarioDe) })
    }

    /** El fichero con [p] en su clave, conservando todo lo demás de [antes]. */
    fun escribir(antes: String?, p: Pegatinas): String {
        val viejo = antes?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val m = LinkedHashMap<String, JsonElement>()
        m["version"] = viejo?.get("version") ?: JsonPrimitive(VERSION)
        viejo?.forEach { (k, v) -> if (k != "version" && k != CLAVE) m[k] = v }
        if ("comentarios" !in m) m["comentarios"] = JsonArray(emptyList())
        m[CLAVE] = JsonArray(p.lista.map(::aJson))
        return BONITO.encodeToString(JsonElement.serializer(), JsonObject(m)) + "\n"
    }

    /** Si [texto] trae alguna pegatina. */
    fun llevaPegatinas(texto: String?): Boolean =
        ((texto?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() })?.get(CLAVE) as? JsonArray)?.isNotEmpty() == true

    /** El JSON compacto de un comentario (sin hilo) o de una entrada: el desempate de [Fusion]. */
    internal fun compacto(e: JsonElement): String = COMPACTO.encodeToString(JsonElement.serializer(), e)

    // ---------------------------------------------------------------- leer

    private fun s(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun l(o: JsonObject, k: String): Long = (o[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
    private fun d(o: JsonObject, k: String): Double? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun b(o: JsonObject, k: String): Boolean = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun sinNulo(e: JsonElement?): JsonElement? = e?.takeIf { it !is JsonNull }

    private val CLAVES_ANCLA = setOf("sitio", "pagina", "x", "y", "sobre", "cita", "posicion")
    private val CLAVES_ADJUNTO = setOf("tipo", "ruta", "nombre", "tinta", "chat", "mensaje", "lienzo", "elemento", "punto", "rotulo")
    private val CLAVES_ENTRADA = setOf("id", "autor", "aparato", "cuando", "cambiado", "texto", "adjuntos", "borrado")
    private val CLAVES_COMENTARIO = setOf("id", "tipo", "ancla", "autor", "aparato", "creado", "cambiado", "resuelto", "borrado", "tarea", "hilo")

    private fun anclaDe(o: JsonObject) = Ancla(
        s(o, "sitio").orEmpty(), d(o, "pagina")?.toInt(), d(o, "x"), d(o, "y"), s(o, "sobre").orEmpty(),
        sinNulo(o["cita"]), sinNulo(o["posicion"]), o.filterKeys { it !in CLAVES_ANCLA }
    )

    private fun adjuntoDe(o: JsonObject) = Adjunto(
        s(o, "tipo") ?: Adjunto.ARCHIVO, s(o, "ruta"), s(o, "nombre"), s(o, "tinta"), s(o, "chat"), s(o, "mensaje"),
        s(o, "lienzo"), s(o, "elemento"),
        (o["punto"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 },
        s(o, "rotulo"), o.filterKeys { it !in CLAVES_ADJUNTO }
    )

    private fun entradaDe(o: JsonObject): Entrada? {
        val id = s(o, "id")?.takeIf { it.isNotEmpty() } ?: return null
        return Entrada(
            id, s(o, "autor").orEmpty(), s(o, "aparato").orEmpty(), l(o, "cuando"), l(o, "cambiado"), s(o, "texto").orEmpty(),
            (o["adjuntos"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::adjuntoDe) } ?: emptyList(),
            b(o, "borrado"), o.filterKeys { it !in CLAVES_ENTRADA }
        )
    }

    private fun comentarioDe(o: JsonObject): Comentario? {
        val id = s(o, "id")?.takeIf { it.isNotEmpty() } ?: return null
        return Comentario(
            id, Tipo.deNombre(s(o, "tipo")), (o["ancla"] as? JsonObject)?.let(::anclaDe) ?: Ancla(""),
            s(o, "autor").orEmpty(), s(o, "aparato").orEmpty(), l(o, "creado"), l(o, "cambiado"), b(o, "resuelto"), b(o, "borrado"),
            s(o, "tarea"), (o["hilo"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::entradaDe) } ?: emptyList(),
            o.filterKeys { it !in CLAVES_COMENTARIO }
        )
    }

    // ---------------------------------------------------------------- escribir (en el orden del PC)

    private fun obj(vararg pares: Pair<String, JsonElement?>, resto: Map<String, JsonElement>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in pares) if (v != null) m[k] = v
        for ((k, v) in resto) if (k !in m) m[k] = v
        return JsonObject(m)
    }

    private fun str(v: String?) = v?.let { JsonPrimitive(it) }
    private fun strNoVacio(v: String) = v.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }

    internal fun aJson(a: Ancla): JsonObject = obj(
        "sitio" to JsonPrimitive(a.sitio), "pagina" to a.pagina?.let { JsonPrimitive(it) },
        "x" to a.x?.let { JsonPrimitive(it) }, "y" to a.y?.let { JsonPrimitive(it) }, "sobre" to strNoVacio(a.sobre),
        "cita" to a.cita, "posicion" to a.posicion, resto = a.resto
    )

    internal fun aJson(a: Adjunto): JsonObject = obj(
        "tipo" to JsonPrimitive(a.tipo), "ruta" to str(a.ruta), "nombre" to str(a.nombre), "tinta" to str(a.tinta),
        "chat" to str(a.chat), "mensaje" to str(a.mensaje), "lienzo" to str(a.lienzo), "elemento" to str(a.elemento),
        "punto" to a.punto?.let { p -> JsonArray(p.map { JsonPrimitive(it) }) }, "rotulo" to str(a.rotulo), resto = a.resto
    )

    internal fun aJson(e: Entrada): JsonObject = obj(
        "id" to JsonPrimitive(e.id), "autor" to JsonPrimitive(e.autor), "aparato" to strNoVacio(e.aparato),
        "cuando" to JsonPrimitive(e.cuando), "cambiado" to JsonPrimitive(e.cambiado), "texto" to strNoVacio(e.texto),
        "adjuntos" to e.adjuntos.takeIf { it.isNotEmpty() }?.let { l -> JsonArray(l.map { aJson(it) }) },
        "borrado" to (if (e.borrado) JsonPrimitive(true) else null), resto = e.resto
    )

    internal fun aJson(c: Comentario, conHilo: Boolean = true): JsonObject = obj(
        "id" to JsonPrimitive(c.id), "tipo" to JsonPrimitive(c.tipo.nombre), "ancla" to aJson(c.ancla),
        "autor" to JsonPrimitive(c.autor), "aparato" to strNoVacio(c.aparato), "creado" to JsonPrimitive(c.creado),
        "cambiado" to JsonPrimitive(c.cambiado), "resuelto" to JsonPrimitive(c.resuelto),
        "borrado" to (if (c.borrado) JsonPrimitive(true) else null), "tarea" to str(c.tarea),
        "hilo" to JsonArray(if (conHilo) c.hilo.map { aJson(it) } else emptyList()), resto = c.resto
    )
}

/**
 * **Juntar dos aparatos** (`fusion.rs`): por id; lo de la pegatina (sin el hilo) gana por `cambiado` y, a
 * igualdad, por su JSON compacto mayor; el hilo se suma entrada a entrada (igual regla) y se ordena por
 * `cuando` e id. **Lo nuevo gana a lo quitado**: una quitada vuelve si en el otro lado tiene una entrada viva
 * escrita después de su lápida. Conmutativo, asociativo e idempotente.
 */
object Fusion {

    private fun gana(a: JsonObject, horaA: Long, b: JsonObject, horaB: Long): Boolean =
        horaA > horaB || (horaA == horaB && Fichero.compacto(a) >= Fichero.compacto(b))

    private fun juntarHilos(a: List<Entrada>, b: List<Entrada>): List<Entrada> {
        val porId = java.util.TreeMap<String, Entrada>()
        for (e in a + b) {
            val otra = porId[e.id]
            if (otra == null || gana(Fichero.aJson(e), e.cambiado, Fichero.aJson(otra), otra.cambiado)) porId[e.id] = e
        }
        return porId.values.sortedWith(compareBy<Entrada> { it.cuando }.thenBy { it.id })
    }

    private fun juntarUna(a: Comentario, b: Comentario): Comentario {
        val aGana = gana(Fichero.aJson(a, conHilo = false), a.cambiado, Fichero.aJson(b, conHilo = false), b.cambiado)
        val (ganadora, otra) = if (aGana) a to b else b to a
        var c = ganadora.copy(hilo = juntarHilos(a.hilo, b.hilo))
        if (c.borrado && !otra.borrado && otra.hilo.any { !it.borrado && it.cuando > ganadora.cambiado }) {
            c = c.copy(borrado = false, cambiado = maxOf(c.cambiado, otra.ultimo()))
        }
        return c
    }

    fun juntar(a: Pegatinas, b: Pegatinas): Pegatinas {
        val porId = java.util.TreeMap<String, Comentario>()
        for (c in a.lista + b.lista) porId[c.id] = porId[c.id]?.let { juntarUna(it, c) } ?: c
        return Pegatinas(porId.values.sortedWith(compareBy<Comentario> { it.creado }.thenBy { it.id }))
    }

    /**
     * **Al llegar un `.comentarios.json`** que también cambió aquí: lo que llega, con las pegatinas juntadas
     * con las de aquí. null si lo que llega ya lo trae todo (se queda como llega) o algo está roto.
     */
    fun alLlegar(aqui: String?, llega: String): String? {
        val mio = Fichero.leer(aqui) ?: return null
        val suyo = Fichero.leer(llega) ?: return null
        if (mio.lista.isEmpty()) return null
        val junta = juntar(mio, suyo)
        if (junta.lista.map { Fichero.aJson(it) } == suyo.lista.map { Fichero.aJson(it) }) return null
        return Fichero.escribir(llega, junta)
    }
}
