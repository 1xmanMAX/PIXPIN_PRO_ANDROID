package com.forge.pixpin.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * **Por dónde sale la petición** (`transporte.rs` del PC): una interfaz pequeña para que el [Cliente]
 * no sepa de redes. En el teléfono, OkHttp ([OkHttpJev], lo mismo que usa el asistente); en las
 * pruebas, un [Grabado] que contesta lo que se le dio y apunta lo que se le pidió. Nunca sale nada a la
 * red en las pruebas.
 */
fun interface Transporte {
    /**
     * Manda la petición y espera la respuesta entera, como mucho [tiempoMs]. Lanza (lo que sea) si no
     * se llegó a hablar con el servidor: el cliente lo cuenta como [ErrorJev.Red].
     */
    fun enviar(metodo: String, url: String, cabeceras: List<Pair<String, String>>, cuerpo: ByteArray, tiempoMs: Long): Contestado
}

/** Lo que contestó el servidor: su código y su cuerpo (también si no es 2xx). */
class Contestado(val codigo: Int, val cuerpo: ByteArray)

/** El de verdad: OkHttp, solo HTTPS, con el tiempo de cada llamada. */
object OkHttpJev : Transporte {
    private val base by lazy { okhttp3.OkHttpClient.Builder().build() }

    override fun enviar(metodo: String, url: String, cabeceras: List<Pair<String, String>>, cuerpo: ByteArray, tiempoMs: Long): Contestado {
        require(url.startsWith("https://")) { "solo https" }
        val cliente = base.newBuilder().callTimeout(tiempoMs.coerceAtLeast(1), TimeUnit.MILLISECONDS).build()
        val b = okhttp3.Request.Builder().url(url)
        if (metodo == "POST") b.post(cuerpo.toRequestBody("application/json".toMediaType())) else b.get()
        for ((k, v) in cabeceras) b.header(k, v)
        cliente.newCall(b.build()).execute().use { r ->
            return Contestado(r.code, r.body?.bytes() ?: ByteArray(0))
        }
    }
}

/** Lo que se le pidió a un [Grabado]. */
data class Pedido(val metodo: String, val url: String, val cabeceras: List<Pair<String, String>>, val cuerpo: ByteArray)

/**
 * **Un transporte de mentira** que contesta, en orden, lo grabado (`Grabado` del PC), y se queda con
 * cada pedido para comprobarlo. Sin nada que contestar, falla como una red caída.
 */
class Grabado(respuestas: List<Result<Contestado>> = emptyList()) : Transporte {
    private val contestar = ArrayDeque(respuestas)
    val pedidos: MutableList<Pedido> = java.util.Collections.synchronizedList(mutableListOf())

    val llamadas: Int get() = pedidos.size

    override fun enviar(metodo: String, url: String, cabeceras: List<Pair<String, String>>, cuerpo: ByteArray, tiempoMs: Long): Contestado {
        pedidos += Pedido(metodo, url, cabeceras, cuerpo)
        val r = synchronized(contestar) { contestar.removeFirstOrNull() } ?: throw java.io.IOException("sin respuesta grabada")
        return r.getOrThrow()
    }

    companion object {
        /** Un 200 con este JSON. */
        fun ok(json: String): Result<Contestado> = Result.success(Contestado(200, json.toByteArray()))
        fun codigo(c: Int, json: String = "{}"): Result<Contestado> = Result.success(Contestado(c, json.toByteArray()))
        fun caida(motivo: String = "sin red"): Result<Contestado> = Result.failure(java.io.IOException(motivo))

        /** Una respuesta con estos Nouls y Scores ya escritos (`grabadas::respuesta` del PC). */
        fun respuesta(partes: List<String>) =
            """{"model":"jev-1.14.0","answers":{${partes.joinToString(",")}},"usage":{"input_tokens":180,"output_tokens":9}}"""

        fun noul(nombre: String, p: Double) = "\"$nombre\":{\"type\":\"noul\",\"noul\":$p}"

        fun nouls(v: List<Pair<String, Double>>) = respuesta(v.map { (n, p) -> noul(n, p) })

        fun score(nombre: String, niveles: List<Double>): String {
            val valor = niveles.withIndex().sumOf { (i, p) -> i * p }
            val probs = niveles.withIndex().joinToString(",") { (i, p) -> "\"$i\":$p" }
            return "\"$nombre\":{\"type\":\"score\",\"score\":$valor,\"confidence\":0.6,\"legend\":{},\"probabilities\":{$probs}}"
        }
    }
}

/**
 * **El cache de respuestas** (`Cache` del PC): por la huella SHA-256 del cuerpo, [CABEN] respuestas;
 * sale la más vieja cuando se llena. Una petición idéntica no se paga dos veces.
 */
class Cache {
    private val mapa = LinkedHashMap<String, Respuestas>()

    @Synchronized fun leer(h: String): Respuestas? = mapa[h]

    @Synchronized fun guardar(h: String, r: Respuestas) {
        mapa[h] = r
        while (mapa.size > CABEN) mapa.remove(mapa.keys.first())
    }

    @get:Synchronized val cuantas: Int get() = mapa.size

    companion object {
        const val CABEN = 2048

        /** El que comparten todos los clientes del proceso. */
        val delProceso = Cache()
    }
}

/**
 * **El cliente de TypeSafe** (`cliente.rs` del PC): manda una [Peticion] y devuelve sus [Respuestas],
 * o lanza un [ErrorJev] (un fallo nunca se lee como «no»). **Bloquea**: se llama desde un hilo de fondo.
 *
 * - **Tiempo**: 2 s ([TIEMPO_MS]). Jev contesta en 70-500 ms; si tarda más, la función sigue como siempre.
 * - **Registro**: el cuerpo exacto de cada petición y su respuesta van a `Log.d` (etiqueta
 *   `PixPinJev`), las imágenes cambiadas por su tamaño. **La clave, nunca**.
 * - **Cache**: una petición idéntica no se vuelve a mandar mientras siga en el [Cache] del proceso.
 * - **Reintento**: uno solo, si TypeSafe dice 429 (límite) o 529 (sobrecargado) y queda tiempo.
 */
class Cliente(
    clave: String,
    private val transporte: Transporte = OkHttpJev,
    private val cache: Cache = Cache.delProceso,
    private val tiempoMs: Long = TIEMPO_MS,
    base: String = BASE,
    private val registro: (String) -> Unit = ::registrar,
    private val dormir: (Long) -> Unit = { Thread.sleep(it) },
    private val reloj: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val clave = clave.trim()
    private val base = base.trimEnd('/')

    /** Nunca enseña la clave. */
    override fun toString() = "Cliente(${base}, ${tiempoMs} ms)"

    fun cacheDeEste(): Cache = cache

    private fun cabeceras(imagenes: Boolean): List<Pair<String, String>> = buildList {
        add("Authorization" to "Bearer $clave")
        add("Content-Type" to "application/json")
        add("Accept" to "application/json")
        if (imagenes) add("TypeSafe-Danger-Zone-Images" to "true")
    }

    /** **Pregunta.** Bloquea como mucho unos [tiempoMs]. */
    fun preguntar(p: Peticion): Respuestas {
        if (clave.isEmpty()) throw ErrorJev.SinClave()
        val cuerpo = p.cuerpo()
        val huella = huella(cuerpo)
        cache.leer(huella)?.let { registro("del cache: ${p.paraElRegistro()}"); return it }
        registro("peticion: ${p.paraElRegistro()}")
        val url = "$base/v1/systemone"
        val cabeceras = cabeceras(p.conImagenes())
        val empezo = reloj()
        var intento = 0
        val contestado: Contestado
        while (true) {
            val queda = tiempoMs - (reloj() - empezo)
            if (queda <= 0) throw ErrorJev.Red("se acabó el tiempo")
            val c = try {
                transporte.enviar("POST", url, cabeceras, cuerpo, queda)
            } catch (e: ErrorJev) {
                throw e
            } catch (e: Exception) {
                throw ErrorJev.Red(e.message ?: e.javaClass.simpleName)
            }
            if ((c.codigo == 429 || c.codigo == 529) && intento == 0 && reloj() - empezo < tiempoMs / 2) {
                intento++
                dormir(250)
                continue
            }
            contestado = c
            break
        }
        val texto = String(contestado.cuerpo, Charsets.UTF_8)
        registro("respuesta ${contestado.codigo} en ${reloj() - empezo} ms: $texto")
        if (contestado.codigo !in 200..299) throw ErrorJev.Http(contestado.codigo, detalle(texto))
        val r = Respuestas.deJson(contestado.cuerpo)
        // Cada pregunta tiene que tener su respuesta: lo que falte es un error de la petición entera,
        // no un hueco que se lea como «no».
        for (nombre in p.preguntas.keys) if (!r.tiene(nombre)) throw ErrorJev.FaltaRespuesta(nombre)
        cache.guardar(huella, r)
        return r
    }

    /** **Los modelos de la cuenta** (`GET /v1/models`): la prueba de la clave de Ajustes, que no gasta tokens. */
    fun modelos(): List<String> {
        if (clave.isEmpty()) throw ErrorJev.SinClave()
        val c = try {
            transporte.enviar("GET", "$base/v1/models", cabeceras(false), ByteArray(0), tiempoMs * 2)
        } catch (e: Exception) {
            throw ErrorJev.Red(e.message ?: e.javaClass.simpleName)
        }
        val texto = String(c.cuerpo, Charsets.UTF_8)
        if (c.codigo !in 200..299) throw ErrorJev.Http(c.codigo, detalle(texto))
        val v = runCatching { Json.parseToJsonElement(texto) as JsonObject }.getOrNull() ?: throw ErrorJev.Formato("lista de modelos")
        val lista = v["models"] as? JsonArray ?: throw ErrorJev.Formato("falta «models»")
        return lista.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
    }

    companion object {
        const val BASE = "https://api.typesafe.ai"
        const val TIEMPO_MS = 2_000L

        /** La huella SHA-256 del cuerpo, en hexadecimal. */
        fun huella(cuerpo: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(cuerpo).joinToString("") { "%02x".format(it) }

        /** El `detail` del JSON de un error, si lo trae; si no, el principio del cuerpo. */
        fun detalle(texto: String): String {
            val d = runCatching { (Json.parseToJsonElement(texto) as? JsonObject)?.get("detail") }.getOrNull()
            val s = when {
                d == null -> texto
                d is JsonPrimitive && d.isString -> d.content
                else -> d.toString()
            }
            return s.take(300)
        }

        /** Al registro de depuración de Android (en las pruebas no hay: se calla). */
        fun registrar(s: String) {
            runCatching { android.util.Log.d("PixPinJev", s) }
        }
    }
}

/**
 * **Corre [f] sobre cada elemento con hasta [hilos] a la vez, en orden** (`en_paralelo` del PC). El
 * primer error para lo que falte y se lanza: un resultado a medias no se guarda como si fuera entero.
 */
internal fun <T, R> enParalelo(cosas: List<T>, hilos: Int, f: (T) -> R): List<R> {
    if (cosas.isEmpty()) return emptyList()
    val n = hilos.coerceIn(1, 8).coerceAtMost(cosas.size)
    if (n == 1) return cosas.map(f)
    val siguiente = AtomicInteger(0)
    val parar = AtomicBoolean(false)
    val error = AtomicReference<Throwable?>(null)
    val salida = arrayOfNulls<Any?>(cosas.size)
    val hecho = BooleanArray(cosas.size)
    val pool = Executors.newFixedThreadPool(n) { r -> Thread(r, "pixpin-jev").apply { isDaemon = true } }
    try {
        val trabajos = (0 until n).map {
            pool.submit(Runnable {
                while (!parar.get()) {
                    val i = siguiente.getAndIncrement()
                    if (i >= cosas.size) return@Runnable
                    try {
                        val r = f(cosas[i])
                        synchronized(salida) { salida[i] = r; hecho[i] = true }
                    } catch (e: Throwable) {
                        parar.set(true)
                        error.compareAndSet(null, e)
                        return@Runnable
                    }
                }
            })
        }
        trabajos.forEach { it.get() }
    } finally {
        pool.shutdown()
    }
    error.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return synchronized(salida) { cosas.indices.map { if (hecho[it]) salida[it] as R else throw ErrorJev.Formato("faltó un resultado") } }
}
