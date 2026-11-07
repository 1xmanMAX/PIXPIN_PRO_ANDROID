package com.forge.pixpin.comunidad

import android.content.Context
import com.forge.pixpin.cuenta.Cuenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * **La galería de la comunidad** (7-nov-2026, el usuario: «una galería universal compartida en la que las personas
 * puedan subir figuras, miniapps, plantillas y todo tipo de contribuciones, y otras puedan revisarlas, decir "me
 * gusta", descargarlas y usarlas, y seguir contribuyendo»). Habla con `servidor/pindesk/src/galeria.mjs`.
 *
 * Mirar no pide cuenta; subir, dar «me gusta» y denunciar, sí (la de Google, [Cuenta]). Lo descargado se comprueba
 * con su huella SHA-256 antes de usarlo.
 */
object Comunidad {
    enum class Tipo(val id: String, val nombre: String, val emoji: String) {
        MINIAPP("miniapp", "Miniapps", "🧩"),
        FIGURA("figura", "Figuras", "🔷"),
        PLANTILLA("plantilla", "Plantillas", "📋"),
        LIENZO("lienzo", "Lienzos", "🎨"),
        PLANO("plano", "Planos", "📐"),
        DOCUMENTO("documento", "Documentos", "📄"),
        OTRO("otro", "Otros", "📦");

        companion object { fun de(id: String?) = entries.firstOrNull { it.id == id } ?: OTRO }
    }

    data class Item(
        val id: String, val tipo: Tipo, val titulo: String, val descripcion: String, val etiquetas: List<String>,
        val nombreArchivo: String, val bytes: Long, val sha256: String, val conVista: Boolean, val autor: String,
        val actualizada: Long, val version: Int, val gustos: Int, val descargas: Int, val meGusta: Boolean,
        val mia: Boolean, val estado: String?, val motivo: String?,
    )

    class Fallo(m: String) : Exception(m)

    /** Si hay servidor para la galería (la dirección de [Cuenta], por https). */
    val hay: Boolean get() = Cuenta.servidor.startsWith("https://")

    private val red by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    private val JSON = "application/json".toMediaType()

    private fun pedido(c: Context, ruta: String, cuerpo: JsonObject? = null, sesion: Boolean = true): Request {
        val b = Request.Builder().url("${Cuenta.servidor}$ruta")
        if (sesion) Cuenta.sesion(c)?.let { b.header("Authorization", "Bearer $it") }
        if (cuerpo != null) b.post(cuerpo.toString().toRequestBody(JSON))
        return b.build()
    }

    private fun motivo(codigo: Int, cuerpo: String): String {
        val e = runCatching { (Json.parseToJsonElement(cuerpo) as JsonObject)["error"]?.jsonPrimitive?.content }.getOrNull()
        return when (e ?: codigo.toString()) {
            "sin_sesion", "401" -> "Entra con tu cuenta de Google para hacer esto"
            "cuenta_bloqueada" -> "Esta cuenta está bloqueada"
            "demasiadas_hoy" -> "Ya subiste muchas hoy: sigue mañana"
            "archivo_demasiado_grande", "demasiado_grande", "413" -> "El archivo es demasiado grande (3 MB como mucho)"
            "falta_titulo" -> "Ponle un título"
            "no_existe", "404" -> "Ya no está en la galería"
            "no_es_tuya" -> "Solo su autor puede cambiarla"
            "demasiados_intentos", "429" -> "Demasiados intentos: espera un poco"
            else -> "No se pudo ($e)"
        }
    }

    private fun json(r: Request): JsonObject = red.newCall(r).execute().use { res ->
        val t = res.body?.string().orEmpty()
        if (!res.isSuccessful) throw Fallo(motivo(res.code, t))
        Json.parseToJsonElement(t) as JsonObject
    }

    fun deJson(o: JsonObject): Item {
        fun s(k: String) = o[k]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val a = o["archivo"]?.jsonObject
        return Item(
            id = s("id").orEmpty(), tipo = Tipo.de(s("tipo")), titulo = s("titulo").orEmpty(), descripcion = s("descripcion").orEmpty(),
            etiquetas = (o["etiquetas"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            nombreArchivo = a?.get("nombre")?.jsonPrimitive?.content ?: "archivo", bytes = a?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0,
            sha256 = a?.get("sha256")?.jsonPrimitive?.content.orEmpty(), conVista = o["vista"]?.jsonPrimitive?.booleanOrNull == true,
            autor = s("autor") ?: "Anónimo", actualizada = o["actualizada"]?.jsonPrimitive?.longOrNull ?: 0,
            version = o["version"]?.jsonPrimitive?.intOrNull ?: 1, gustos = o["gustos"]?.jsonPrimitive?.intOrNull ?: 0,
            descargas = o["descargas"]?.jsonPrimitive?.intOrNull ?: 0, meGusta = o["meGusta"]?.jsonPrimitive?.booleanOrNull == true,
            mia = o["mia"]?.jsonPrimitive?.booleanOrNull == true, estado = s("estado"), motivo = s("motivo"),
        )
    }

    enum class Orden(val id: String, val nombre: String) { POPULARES("populares", "Populares"), NUEVAS("nuevas", "Nuevas"), DESCARGAS("descargas", "Más descargadas") }

    suspend fun lista(c: Context, tipo: Tipo?, busqueda: String, orden: Orden, desde: Int = 0): Pair<Int, List<Item>> = withContext(Dispatchers.IO) {
        val q = buildString {
            append("?orden=").append(orden.id).append("&desde=").append(desde).append("&n=30")
            if (tipo != null) append("&tipo=").append(tipo.id)
            if (busqueda.isNotBlank()) append("&q=").append(URLEncoder.encode(busqueda.trim(), "UTF-8"))
        }
        val o = json(pedido(c, "/v1/galeria$q"))
        (o["total"]?.jsonPrimitive?.intOrNull ?: 0) to o["items"]!!.jsonArray.map { deJson(it.jsonObject) }
    }

    suspend fun mias(c: Context): List<Item> = withContext(Dispatchers.IO) {
        json(pedido(c, "/v1/galeria/mias"))["items"]!!.jsonArray.map { deJson(it.jsonObject) }
    }

    fun urlDeVista(id: String) = "${Cuenta.servidor}/v1/galeria/$id/vista"

    suspend fun vista(id: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { red.newCall(Request.Builder().url(urlDeVista(id)).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
    }

    suspend fun gusta(c: Context, id: String, si: Boolean): Item = withContext(Dispatchers.IO) {
        deJson(json(pedido(c, "/v1/galeria/$id/gusta", buildJsonObject { put("si", si) })))
    }

    suspend fun denunciar(c: Context, id: String, motivo: String): Unit = withContext(Dispatchers.IO) {
        json(pedido(c, "/v1/galeria/$id/denunciar", buildJsonObject { put("motivo", motivo) })); Unit
    }

    suspend fun borrar(c: Context, id: String): Unit = withContext(Dispatchers.IO) {
        json(pedido(c, "/v1/galeria/$id/borrar", buildJsonObject { })); Unit
    }

    /** **La baja** a [destino], y comprueba que es lo que dice la galería (su huella). */
    suspend fun bajar(c: Context, it: Item, destino: File): Unit = withContext(Dispatchers.IO) {
        red.newCall(pedido(c, "/v1/galeria/${it.id}/archivo")).execute().use { r ->
            if (!r.isSuccessful) throw Fallo(motivo(r.code, r.body?.string().orEmpty()))
            val b = r.body?.bytes() ?: throw Fallo("No llegó nada")
            if (it.sha256.isNotBlank() && huella(b) != it.sha256) throw Fallo("Lo que llegó no es lo publicado (la huella no cuadra)")
            destino.writeBytes(b)
        }
    }

    fun huella(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** **Publicar** (o una versión nueva de una suya, con [reemplaza]). Queda pendiente de revisión si el servidor lo pide. */
    suspend fun publicar(
        c: Context, tipo: Tipo, titulo: String, descripcion: String, etiquetas: List<String>,
        nombre: String, contenido: ByteArray, vista: ByteArray?, reemplaza: String? = null,
    ): Item = withContext(Dispatchers.IO) {
        if (contenido.size > 3_000_000) throw Fallo("El archivo es demasiado grande (3 MB como mucho)")
        val b64 = java.util.Base64.getEncoder()
        val cuerpo = buildJsonObject {
            put("tipo", tipo.id); put("titulo", titulo.trim()); put("descripcion", descripcion.trim())
            put("etiquetas", JsonArray(etiquetas.map { JsonPrimitive(it) }))
            put("nombre", nombre); put("contenido", b64.encodeToString(contenido))
            vista?.let { put("vista", b64.encodeToString(it)) }
            reemplaza?.let { put("reemplaza", it) }
        }
        deJson(json(pedido(c, "/v1/galeria", cuerpo))["item"]!!.jsonObject)
    }

    /** El tipo que le toca a un archivo por su extensión. */
    fun tipoDe(nombre: String): Tipo = when (nombre.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> Tipo.MINIAPP
        "dxf", "dwg", "ifc" -> Tipo.PLANO
        "excalidraw", "pindesk" -> Tipo.LIENZO
        "pdf", "docx", "doc", "md", "txt", "xlsx", "pptx", "odt" -> Tipo.DOCUMENTO
        "svg", "png", "jpg", "jpeg", "webp" -> Tipo.FIGURA
        else -> if (nombre.endsWith(FIGURA)) Tipo.FIGURA else Tipo.OTRO
    }

    /** Así se llaman las figuras de la biblioteca cuando viajan por la galería (una `FiguraGuardada` en JSON). */
    const val FIGURA = ".figura.json"
}
