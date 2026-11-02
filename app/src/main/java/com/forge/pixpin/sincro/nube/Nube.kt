package com.forge.pixpin.sincro.nube

import com.forge.pixpin.sincro.Llaves
import com.forge.pixpin.sincro.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * # PixPin nube (2-nov-2026, puerto de `crates/pindesk-sincro/src/nube/` del PC)
 *
 * Los chats **compartidos** pueden ir, además de por la red local, por la nube: la web de la obra
 * (protocolo 1 y la capacidad `deltas1`). Lo que viaja es lo mismo que por la red local —mensajes
 * portátiles, el proyecto, los archivos con sus rutas de Android— y de un lienzo o una tinta **solo
 * los trazos nuevos** ([TrazosNube]). Los chats propios siguen solo por la red local.
 *
 * - [ClienteNube]: una petición por puerta de la API, sobre un [Transporte] (OkHttp en el teléfono).
 * - [TrazosNube]: el delta de una escena de Excalidraw y la regla para juntar.
 * - [EstadoDeChat] / [ChatsEnLaNube]: lo sabido de cada chat y qué chats van por la nube.
 * - [VueltaNube]: bajar, juntar y subir un chat.
 * - [SinConexion]: «Sin conexión» en un visor.
 * - [NubeEnMarcha]: los ajustes, la llave cifrada y el bucle que sube y baja.
 *
 * Ver `docs/nube.md` del PC.
 */
object Nube {
    const val PROTOCOLO = "pixpin-nube"
    const val VERSION = 1
    const val DELTAS = "deltas1"
    const val PREFIJO_LLAVE = "pxn_"

    /** Lo que firma un aparato para presentarse (`PUT /yo`, §3.2). */
    fun textoDeYo(aparatoNube: String, id: String, clave: String) = "pixpin-nube|yo|$aparatoNube|$id|$clave"

    fun firmaDeYo(llaves: Llaves, aparatoNube: String, id: String): String =
        llaves.firmar(textoDeYo(aparatoNube, id, Llaves.b64(llaves.publica)).toByteArray())

    /** **El espacio de un chat compartido en la nube**: el mismo para todas sus personas. */
    fun espacioCompartido(creador: String, chat: String): String =
        "compartido-" + sha256("$creador|$chat".toByteArray()).substring(0, 16)

    /** Si una llave tiene la forma de las de la nube. */
    fun llaveValida(llave: String): Boolean {
        val l = llave.trim()
        return l.startsWith(PREFIJO_LLAVE) && l.length > PREFIJO_LLAVE.length + 8 &&
            l.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
    }

    /** Si una dirección se puede usar: `https://`, o `http://` hacia este equipo (pruebas). */
    fun direccionValida(d: String): Boolean {
        val t = d.trim().trimEnd('/')
        if (t.startsWith("https://") && t.length > 9) return true
        return listOf("http://127.0.0.1", "http://localhost", "http://10.0.2.2").any { t == it || t.startsWith("$it:") || t.startsWith("$it/") }
    }

    /** La llave enmascarada, para enseñarla (`pxn…a1b2`). */
    fun enmascarar(l: String): String = if (l.length <= 8) "••••" else l.take(3) + "…" + l.takeLast(4)

    internal val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
}

/** **Por qué no salió.** */
sealed class FalloNube(mensaje: String) : Exception(mensaje) {
    /** Sin red, o el servidor no contesta: se reintenta con espera. */
    class Red(m: String) : FalloNube("sin red: $m")
    /** La llave no vale (401) o no deja (403): no se reintenta solo. */
    class Llave(m: String) : FalloNube(m)
    /** Otro subió entre medias: bajar, juntar y volver a subir. */
    class Conflicto(val revision: Long) : FalloNube("la nube va por la revisión $revision")
    /** Lo que no está (404). */
    class NoEsta : FalloNube("no está en la nube")
    class Servidor(val codigo: Int, m: String) : FalloNube("la nube dijo $codigo: $m")
    class Formato(m: String) : FalloNube("respuesta rara: $m")
    class Disco(m: String) : FalloNube("disco: $m")

    /** Si vale la pena volver a intentarlo más tarde (red o servidor caído). */
    val pasajero: Boolean get() = this is Red || (this is Servidor && codigo >= 500)
}

/** Lo que contestó el servidor, también si no es 2xx. */
class Contestado(val codigo: Int, val cuerpo: ByteArray)

/** **Manda una petición** y espera la respuesta entera. Lanza si no se llegó a hablar con el servidor. */
fun interface Transporte {
    fun enviar(metodo: String, url: String, cabeceras: List<Pair<String, String>>, cuerpo: ByteArray, segundos: Long): Contestado
}

/** **Los pedidos a la nube**, uno por puerta de la API (§3 y `deltas1`). Bloquea: desde un hilo de fondo. */
class ClienteNube(direccion: String, private val llave: String, private val t: Transporte) {
    private val base = direccion.trim().trimEnd('/')

    private fun url(ruta: String) = "$base/nube/api/v1/$ruta"

    private fun errorDe(cuerpo: ByteArray): String = runCatching {
        ((Nube.JSON.parseToJsonElement(cuerpo.decodeToString()) as JsonObject)["error"] as JsonPrimitive).content
    }.getOrElse { cuerpo.decodeToString().take(200) }

    private fun pedir(metodo: String, ruta: String, cuerpo: ByteArray = ByteArray(0), tipo: String? = null, segundos: Long = TIEMPO): Contestado {
        val cab = buildList {
            add("Authorization" to "Bearer $llave")
            if (tipo != null) add("Content-Type" to tipo)
        }
        val r = try { t.enviar(metodo, url(ruta), cab, cuerpo, segundos) } catch (e: FalloNube) { throw e } catch (e: Exception) { throw FalloNube.Red(e.message ?: e.javaClass.simpleName) }
        return when (r.codigo) {
            in 200..299 -> r
            401, 403 -> throw FalloNube.Llave(errorDe(r.cuerpo))
            404 -> throw FalloNube.NoEsta()
            409 -> throw FalloNube.Conflicto(runCatching { ((Nube.JSON.parseToJsonElement(r.cuerpo.decodeToString()) as JsonObject)["revision"] as JsonPrimitive).longOrNull }.getOrNull() ?: 0)
            else -> throw FalloNube.Servidor(r.codigo, errorDe(r.cuerpo))
        }
    }

    private fun json(r: Contestado): JsonObject = try {
        Nube.JSON.parseToJsonElement(r.cuerpo.decodeToString()) as JsonObject
    } catch (e: Exception) { throw FalloNube.Formato(e.message ?: "json") }

    private fun conJson(metodo: String, ruta: String, cuerpo: JsonElement): JsonObject =
        json(pedir(metodo, ruta, cuerpo.toString().toByteArray(), "application/json"))

    /** `GET hola`: comprueba la llave y dice qué sabe la nube. */
    fun hola(): Hola {
        val h = Hola.de(json(pedir("GET", "hola")))
        if (h.protocolo != Nube.PROTOCOLO) throw FalloNube.Formato("no es una nube de PixPin (${h.protocolo})")
        return h
    }

    /** `PUT yo`: la persona de este aparato, con su firma. */
    fun presentar(id: String, clave: String, firma: String) {
        conJson("PUT", "yo", buildJsonObject { put("id", id); put("clave", clave); put("firma", firma) })
    }

    /** `GET chats`: los suyos y los compartidos donde está dentro. */
    fun chats(): List<ChatDeLaNube> = (json(pedir("GET", "chats"))["chats"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(ChatDeLaNube::de) }

    /** `GET chats/<chat>`: el inventario entero, con su revisión. */
    fun inventario(espacio: String, chat: String): JsonObject =
        json(pedir("GET", "chats/${codificar(chat)}" + if (espacio.isEmpty()) "" else "?espacio=${codificar(espacio)}"))

    /** `PUT chats/<chat>`: el inventario entero (protocolo 1). */
    fun subirInventario(chat: String, inv: JsonObject): Subido = Subido.de(conJson("PUT", "chats/${codificar(chat)}", inv))

    /** `PATCH chats/<chat>`: solo lo que cambió (`deltas1`). */
    fun parche(chat: String, p: JsonObject): Subido = Subido.de(conJson("PATCH", "chats/${codificar(chat)}", p))

    /** `GET chats/<chat>/cambios?espacio=&desde=`. */
    fun cambios(espacio: String, chat: String, desde: Long): JsonObject =
        json(pedir("GET", "chats/${codificar(chat)}/cambios?" + (if (espacio.isEmpty()) "" else "espacio=${codificar(espacio)}&") + "desde=$desde"))

    /** `HEAD objetos/<sha>`: si la nube ya lo tiene. */
    fun hayObjeto(sha: String): Boolean = try { pedir("HEAD", "objetos/$sha"); true } catch (_: FalloNube.NoEsta) { false }

    /** `PUT objetos/<sha>`: un archivo crudo. */
    fun subirObjeto(sha: String, bytes: ByteArray) { pedir("PUT", "objetos/$sha", bytes, "application/octet-stream") }

    /** `GET objetos/<sha>`, comprobando que llegó lo que se pidió. */
    fun bajarObjeto(sha: String): ByteArray {
        val r = pedir("GET", "objetos/$sha")
        if (sha256(r.cuerpo) != sha) throw FalloNube.Formato("el objeto $sha llegó cambiado")
        return r.cuerpo
    }

    /** `GET novedades?desde=&espera=`: lo que cambió, esperando con la conexión abierta. */
    fun novedades(desde: Long, espera: Long): Novedades {
        val e = espera.coerceIn(0, 30)
        return Novedades.de(json(pedir("GET", "novedades?desde=$desde&espera=$e", segundos = e + MARGEN_DE_ESPERA)))
    }

    companion object {
        const val TIEMPO = 60L
        const val MARGEN_DE_ESPERA = 15L

        /** `encodeURIComponent` de un id de chat o un espacio. */
        fun codificar(s: String): String = buildString {
            for (b in s.toByteArray()) {
                val c = b.toInt() and 0xff
                if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) append(c.toChar())
                else append("%%%02X".format(c))
            }
        }
    }
}

// ------------------------------------------------------------------ los cuerpos (§3, §4, deltas1)

private fun JsonObject.texto(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.largo(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

/** La respuesta de `GET hola`. */
data class Hola(val protocolo: String, val version: Int, val aparatoId: String, val espacio: String, val persona: String?, val capacidades: List<String>, val novedades: Long) {
    fun sabe(c: String) = c in capacidades

    companion object {
        fun de(o: JsonObject): Hola {
            val a = o["aparato"] as? JsonObject ?: JsonObject(emptyMap())
            return Hola(
                o.texto("protocolo"), o.largo("version").toInt(), a.texto("id"), a.texto("espacio"),
                (a["persona"] as? JsonPrimitive)?.contentOrNull, (o["capacidades"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                o.largo("novedades")
            )
        }
    }
}

/** Un delta de un archivo. */
data class Delta(val sha256: String, val bytes: Long) {
    fun json() = buildJsonObject { put("sha256", sha256); put("bytes", bytes) }

    companion object {
        fun de(o: JsonObject) = Delta(o.texto("sha256"), o.largo("bytes"))
    }
}

/** Una línea del inventario de archivos. [deltas] null: sin la lista (protocolo 1). */
data class ArchivoNube(val rel: String, val sha256: String, val bytes: Long, val tocado: Long? = null, val deltas: List<Delta>? = null) {
    val losDeltas: List<Delta> get() = deltas.orEmpty()

    fun json() = buildJsonObject {
        put("rel", rel); put("sha256", sha256); put("bytes", bytes)
        if (tocado != null) put("tocado", tocado)
        if (deltas != null) put("deltas", JsonArray(deltas.map { it.json() }))
    }

    companion object {
        fun de(o: JsonObject) = ArchivoNube(
            o.texto("rel"), o.texto("sha256"), o.largo("bytes"), (o["tocado"] as? JsonPrimitive)?.longOrNull,
            (o["deltas"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Delta::de) }
        )
    }
}

/**
 * **Un parche** (`PATCH`, `deltas1`): solo lo que cambió desde [base]. Es también cada entrada de
 * `cambios`, con la [revision] que dejó. [proyecto]: null no cambió; [JsonNull], ya no tiene.
 */
data class Parche(
    val espacio: String? = null,
    val base: Long = 0,
    val tocado: Long = 0,
    val nombre: String? = null,
    val compartido: JsonElement? = null,
    val poner: List<JsonElement> = emptyList(),
    val quitar: List<String> = emptyList(),
    val proyecto: JsonElement? = null,
    val archivosPoner: List<ArchivoNube> = emptyList(),
    val archivosQuitar: List<String> = emptyList(),
    val revision: Long? = null
) {
    val vacio: Boolean get() = nombre == null && compartido == null && poner.isEmpty() && quitar.isEmpty() && proyecto == null && archivosPoner.isEmpty() && archivosQuitar.isEmpty()

    fun json(): JsonObject = buildJsonObject {
        if (espacio != null) put("espacio", espacio)
        put("base", base); put("tocado", tocado)
        if (nombre != null) put("nombre", nombre)
        if (compartido != null) put("compartido", compartido)
        put("mensajes", buildJsonObject { put("poner", JsonArray(poner)); put("quitar", JsonArray(quitar.map(::JsonPrimitive))) })
        if (proyecto != null) put("proyecto", proyecto)
        put("archivos", buildJsonObject { put("poner", JsonArray(archivosPoner.map { it.json() })); put("quitar", JsonArray(archivosQuitar.map(::JsonPrimitive))) })
        if (revision != null) put("revision", revision)
    }

    companion object {
        fun de(o: JsonObject): Parche {
            val m = o["mensajes"] as? JsonObject
            val a = o["archivos"] as? JsonObject
            return Parche(
                (o["espacio"] as? JsonPrimitive)?.contentOrNull, o.largo("base"), o.largo("tocado"),
                (o["nombre"] as? JsonPrimitive)?.contentOrNull, o["compartido"]?.takeIf { it !is JsonNull },
                (m?.get("poner") as? JsonArray).orEmpty().toList(),
                (m?.get("quitar") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                if (o.containsKey("proyecto")) o["proyecto"] else null,
                (a?.get("poner") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(ArchivoNube::de) },
                (a?.get("quitar") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                (o["revision"] as? JsonPrimitive)?.longOrNull
            )
        }
    }
}

/** Lo que contesta la nube a una subida. */
data class Subido(val ok: Boolean, val espacio: String, val revision: Long, val faltan: List<String>) {
    companion object {
        fun de(o: JsonObject) = Subido(
            (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false, o.texto("espacio"), o.largo("revision"),
            (o["faltan"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        )
    }
}

/** Un chat que cambió (`novedades.cambios[i]`). */
data class Novedad(val espacio: String, val chat: String, val revision: Long, val compartido: Boolean?, val borrado: Boolean?)

data class Novedades(val seq: Long, val reiniciar: Boolean, val cambios: List<Novedad>) {
    companion object {
        fun de(o: JsonObject) = Novedades(
            o.largo("seq"), (o["reiniciar"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["cambios"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { Novedad(it.texto("espacio"), it.texto("chat"), it.largo("revision"), (it["compartido"] as? JsonPrimitive)?.booleanOrNull, (it["borrado"] as? JsonPrimitive)?.booleanOrNull) }
            }
        )
    }
}

/** Un chat de la lista (`GET chats`). */
data class ChatDeLaNube(val espacio: String, val chat: String, val nombre: String, val revision: Long, val compartido: Boolean?) {
    companion object {
        fun de(o: JsonObject) = ChatDeLaNube(o.texto("espacio"), o.texto("chat"), o.texto("nombre"), o.largo("revision"), (o["compartido"] as? JsonPrimitive)?.booleanOrNull)
    }
}
