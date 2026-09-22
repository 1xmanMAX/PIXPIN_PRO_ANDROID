package com.forge.pixpin.motor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **Las voces de Microsoft Edge** (22-sep-2026, pedido por el usuario: «las voces online que usan
 * algunas apps gratis»). Son las de «Leer en voz alta» de Edge: más de 300 voces neuronales, gratis
 * y sin cuenta.
 *
 * **No es un servicio oficial.** Se habla con él como lo hace Edge, copiando al cliente de
 * referencia `rany2/edge-tts` (`src/edge_tts/constants.py`, `drm.py`, `communicate.py`, septiembre
 * de 2026). Microsoft puede cambiarlo o cerrarlo cuando quiera: por eso [com.forge.pixpin.ui.LectorEnVoz]
 * vuelve a la voz de Google en cuanto falla. El texto se manda a Microsoft.
 *
 * Aquí van las cuentas, sin Android ni red; la conexión está en [com.forge.pixpin.ui.VozDeEdge].
 */
object EdgeVoz {

    const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    const val CHROMIUM = "143.0.3650.75"
    private val MAYOR = CHROMIUM.substringBefore('.')

    const val URL_DE_VOCES = "https://$BASE/voices/list?trustedclienttoken=$TOKEN"

    fun urlDeHablar(conexion: String, gec: String) =
        "wss://$BASE/edge/v1?TrustedClientToken=$TOKEN&ConnectionId=$conexion&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=1-$CHROMIUM"

    /** Las cabeceras con las que se presenta Edge. */
    fun cabeceras(muid: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$MAYOR.0.0.0 Safari/537.36 Edg/$MAYOR.0.0.0",
        "Accept-Language" to "en-US,en;q=0.9",
        "Pragma" to "no-cache",
        "Cache-Control" to "no-cache",
        "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
        "Cookie" to "muid=$muid;"
    )

    /**
     * **El `Sec-MS-GEC`**: la hora en formato de Windows (centenas de nanosegundos desde 1601),
     * redondeada a 5 minutos hacia abajo, seguida del token, en SHA-256 y en mayúsculas.
     */
    fun gec(unixSegundos: Double): String {
        var t = unixSegundos + 11644473600.0
        t -= t % 300
        val centenas = java.math.BigDecimal(t).multiply(java.math.BigDecimal(10_000_000)).toBigInteger()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("$centenas$TOKEN".toByteArray(Charsets.US_ASCII))
        return hash.joinToString("") { "%02X".format(it) }
    }

    /** La fecha como la escribe JavaScript, en UTC. */
    fun fecha(ms: Long): String {
        val f = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms)) + " GMT+0000 (Coordinated Universal Time)"
    }

    fun mensajeDeAjustes(fecha: String) =
        "X-Timestamp:$fecha\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

    /** El texto que se lee, con la voz; la «Z» detrás de la hora es un fallo de Edge que hay que copiar. */
    fun mensajeDeTexto(pedido: String, fecha: String, voz: String, texto: String) =
        "X-RequestId:$pedido\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${fecha}Z\r\nPath:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${escapar(voz)}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${escapar(limpiar(texto))}</prosody></voice></speak>"

    /** Fuera los caracteres de control que el servicio no admite (un tabulador vertical de un OCR lo tumba). */
    fun limpiar(texto: String): String = texto.map { c ->
        val n = c.code
        if (n in 0..8 || n in 11..12 || n in 14..31) ' ' else c
    }.joinToString("")

    fun escapar(texto: String): String = texto
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&apos;").replace("\"", "&quot;")

    /** Lo que dice un mensaje de texto del servicio: su `Path` («turn.start», «turn.end»…). */
    fun rutaDe(mensaje: String): String? =
        mensaje.substringBefore("\r\n\r\n").split("\r\n").firstOrNull { it.startsWith("Path:") }?.substringAfter("Path:")

    /**
     * **El audio de un mensaje binario**: dos bytes con lo que miden las cabeceras, las cabeceras,
     * y detrás el trozo de MP3. Null si no es audio o viene vacío (el último, que cierra, lo está).
     */
    fun audioDe(trama: ByteArray): ByteArray? {
        if (trama.size < 2) return null
        val largo = ((trama[0].toInt() and 0xFF) shl 8) or (trama[1].toInt() and 0xFF)
        if (largo + 2 > trama.size) return null
        val cabeceras = String(trama, 2, largo, Charsets.UTF_8)
        if (!cabeceras.split("\r\n").contains("Path:audio")) return null
        val audio = trama.copyOfRange(2 + largo, trama.size)
        return audio.takeIf { it.isNotEmpty() }
    }

    /** Una voz de la lista: «es-ES-ElviraNeural», «es-ES», «Female». */
    data class Voz(val nombre: String, val idioma: String, val genero: String) {
        /** «Elvira», sin el idioma delante ni «Neural» detrás. */
        val corto: String get() = nombre.removePrefix("$idioma-").removeSuffix("Neural").replace("Multilingual", " multilingüe").trim()
    }

    fun deLista(json: String): List<Voz> = runCatching {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { v ->
            val o = v.jsonObject
            val nombre = o["ShortName"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Voz(nombre, o["Locale"]?.jsonPrimitive?.content.orEmpty(), o["Gender"]?.jsonPrimitive?.content.orEmpty())
        }
    }.getOrDefault(emptyList())

    /** Las voces de una lengua, las del país primero. */
    fun deLaLengua(voces: List<Voz>, idioma: String): List<Voz> {
        val (lengua, pais) = VozAlta.partes(idioma)
        return voces.filter { VozAlta.partes(it.idioma).first == lengua }
            .sortedWith(compareByDescending<Voz> { pais.isNotEmpty() && VozAlta.partes(it.idioma).second == pais }.thenBy { it.idioma }.thenBy { it.nombre })
    }

    /** **La voz que se usa**: la que eligió el usuario si es de esa lengua; si no, la primera del país. */
    fun elegir(voces: List<Voz>, idioma: String, preferida: String?): Voz? {
        val suyas = deLaLengua(voces, idioma)
        return suyas.firstOrNull { it.nombre == preferida } ?: suyas.firstOrNull()
    }

    /** Lo más largo que se manda de una vez. El servicio corta en 4096 bytes ya escapados. */
    const val TOPE = 1000
}
