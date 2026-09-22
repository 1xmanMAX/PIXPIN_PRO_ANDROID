package com.forge.pixpin.ui

import android.content.Context
import com.forge.pixpin.motor.EdgeVoz
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * **La conexión con las voces de Microsoft Edge.** Las cuentas y el porqué están en [EdgeVoz].
 *
 * Cada trozo de texto es una conexión: se abre, se mandan los ajustes y el texto, llegan los
 * trozos de MP3 y al «turn.end» se cierra (como hace `edge-tts`). **Lo leído se guarda** en la
 * caché, por voz y texto: volver a escuchar un párrafo no gasta red ni depende de Microsoft.
 */
object VozDeEdge {

    private val cliente by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val muid = UUID.randomUUID().toString().replace("-", "").uppercase()

    /** Lo que va desfasado el reloj del teléfono con el de Microsoft: el `Sec-MS-GEC` va por la hora. */
    @Volatile private var desfase = 0.0

    /** El MP3 de [texto] con la voz [voz]: de la caché si ya se leyó; si no, pedido a Microsoft. */
    suspend fun archivo(context: Context, texto: String, voz: String): File = withContext(Dispatchers.IO) {
        val carpeta = File(context.cacheDir, "voz-edge").apply { mkdirs() }
        val destino = File(carpeta, sha1("$voz\u0000$texto") + ".mp3")
        if (destino.length() > 0) return@withContext destino.also { it.setLastModified(System.currentTimeMillis()) }
        val sale = ByteArrayOutputStream()
        for (trozo in com.forge.pixpin.motor.VozAlta.trozos(texto, EdgeVoz.TOPE)) sale.write(hablar(trozo, voz))
        val tmp = File(carpeta, destino.name + ".tmp")
        tmp.writeBytes(sale.toByteArray())
        tmp.renameTo(destino)
        podar(carpeta)
        destino
    }

    /** Un trozo, por una conexión. Si Microsoft dice que la hora no cuadra (403), se ajusta y se reintenta una vez. */
    private suspend fun hablar(texto: String, voz: String): ByteArray = try {
        hablarUnaVez(texto, voz)
    } catch (e: HoraDescuadrada) {
        desfase += e.servidor - System.currentTimeMillis() / 1000.0
        hablarUnaVez(texto, voz)
    }

    private class HoraDescuadrada(val servidor: Double) : Exception()

    private suspend fun hablarUnaVez(texto: String, voz: String): ByteArray {
        val listo = CompletableDeferred<ByteArray>()
        val audio = ByteArrayOutputStream()
        val ahora = System.currentTimeMillis() / 1000.0 + desfase
        val pedido = Request.Builder()
            .url(EdgeVoz.urlDeHablar(UUID.randomUUID().toString().replace("-", ""), EdgeVoz.gec(ahora)))
            .apply { EdgeVoz.cabeceras(muid).forEach { (k, v) -> header(k, v) } }
            .build()
        val ws = cliente.newWebSocket(pedido, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val fecha = EdgeVoz.fecha(System.currentTimeMillis())
                webSocket.send(EdgeVoz.mensajeDeAjustes(fecha))
                webSocket.send(EdgeVoz.mensajeDeTexto(UUID.randomUUID().toString().replace("-", ""), fecha, voz, texto))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (EdgeVoz.rutaDe(text) == "turn.end") {
                    webSocket.close(1000, null)
                    if (audio.size() > 0) listo.complete(audio.toByteArray())
                    else listo.completeExceptionally(IllegalStateException("Microsoft no devolvió audio"))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                EdgeVoz.audioDe(bytes.toByteArray())?.let { audio.write(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val fecha = response?.header("Date")
                if (response?.code == 403 && fecha != null) {
                    val servidor = runCatching {
                        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(fecha)!!.time / 1000.0
                    }.getOrNull()
                    if (servidor != null) { listo.completeExceptionally(HoraDescuadrada(servidor)); return }
                }
                listo.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!listo.isCompleted) listo.completeExceptionally(IllegalStateException("Microsoft cerró: $code $reason"))
            }
        })
        return try {
            withTimeout(30_000) { listo.await() }
        } finally {
            ws.cancel()
        }
    }

    /**
     * **Las voces**, de la lista de Microsoft; guardada una semana en `files/` para no pedirla cada
     * vez. Sin red y sin copia, vacía.
     */
    suspend fun voces(context: Context): List<EdgeVoz.Voz> = withContext(Dispatchers.IO) {
        val copia = File(context.filesDir, "voces-edge.json")
        val fresca = copia.exists() && System.currentTimeMillis() - copia.lastModified() < 7L * 24 * 3600 * 1000
        if (!fresca) runCatching {
            val pedido = Request.Builder().url(EdgeVoz.URL_DE_VOCES)
                .apply { EdgeVoz.cabeceras(muid).forEach { (k, v) -> header(k, v) } }
                .build()
            cliente.newCall(pedido).execute().use { r ->
                val cuerpo = r.body?.string().orEmpty()
                if (r.isSuccessful && EdgeVoz.deLista(cuerpo).isNotEmpty()) copia.writeText(cuerpo)
            }
        }
        if (copia.exists()) EdgeVoz.deLista(copia.readText()) else emptyList()
    }

    /** La caché de lo leído, como mucho [TOPE_DE_CACHE]: se van los que hace más que no se oyen. */
    private fun podar(carpeta: File) {
        val todos = carpeta.listFiles()?.filter { it.extension == "mp3" }?.sortedByDescending { it.lastModified() } ?: return
        var suma = 0L
        for (f in todos) { suma += f.length(); if (suma > TOPE_DE_CACHE) f.delete() }
    }

    private const val TOPE_DE_CACHE = 150L * 1024 * 1024

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
