package com.forge.pixpin.asistente

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.ConexionesNativas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * **El puente de las conexiones** ([ConexionesNativas.Puente]): lo que en el PC es de Windows.
 *
 * - **La red**: `HttpURLConnection` (en el PC, WinHTTP), con el mismo trato: un código que no es 2xx
 *   vuelve con su cuerpo (el JSON del error) y su `Retry-After`; una respuesta de más de `limite` se corta.
 * - **Lo guardado** (el ID de cliente y los tokens de OAuth), cifrado con una clave de la **Keystore de
 *   Android** que no sale del aparato (en el PC, DPAPI), en `files/claves/`, que no se sincroniza.
 * - **El navegador** para dar el permiso, y lo del chat: elegir un archivo, su texto, dejar uno en el
 *   chat y preguntar «¿Sí?» antes de subir.
 */
class ConexionesDelTelefono(
    private val contexto: Context,
    private val almacen: MensajesStore? = null,
    private val chat: String? = null,
    private val preguntar: (String) -> Boolean = { false },
    private val parar: AtomicBoolean = AtomicBoolean(false)
) : ConexionesNativas.Puente {

    companion object {
        private const val ALIAS = "pixpin-conexiones"
        private val json = Json { ignoreUnknownKeys = true }

        /** Donde va lo guardado de las conexiones (no viaja: es de este teléfono). */
        fun carpetaDeClaves(c: Context) = File(c.filesDir, "claves").apply { mkdirs() }

        /** La espera al navegador que está en marcha (Conectar otra vez o Desconectar la corta). */
        val esperando = AtomicBoolean(false)

        private fun paquete(codigo: Int, cabeza: String, cuerpo: ByteArray): ByteArray {
            val c = cabeza.toByteArray()
            return ByteBuffer.allocate(8 + c.size + cuerpo.size).order(ByteOrder.LITTLE_ENDIAN).putInt(codigo).putInt(c.size).put(c).put(cuerpo).array()
        }
    }

    // ------------------------------------------------------------------------------- la red

    override fun http(metodo: String, url: String, cabeceras: String, cuerpo: ByteArray, limite: Int): ByteArray = try {
        val u = URL(url)
        // Como el PC: `http://` solo hacia este aparato.
        if (u.protocol == "http" && u.host != "127.0.0.1" && u.host != "localhost") error("solo se habla con el servicio por https")
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.requestMethod = metodo
        for (par in json.parseToJsonElement(cabeceras).jsonArray) {
            val a = par.jsonArray
            c.setRequestProperty((a[0] as JsonPrimitive).content, (a[1] as JsonPrimitive).content)
        }
        if (cuerpo.isNotEmpty() || metodo in setOf("POST", "PUT", "PATCH")) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(cuerpo.size)
            c.outputStream.use { it.write(cuerpo) }
        }
        val codigo = c.responseCode
        val flujo = if (codigo in 200..299) c.inputStream else c.errorStream
        val salida = java.io.ByteArrayOutputStream()
        flujo?.use { f ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = f.read(buf); if (n < 0) break
                salida.write(buf, 0, n)
                if (salida.size() > limite) error("la respuesta pasa de ${maxOf(limite / (1024 * 1024), 1)} MB")
            }
        }
        val cabeza = buildJsonObject {
            c.getHeaderField("Location")?.let { put("ubicacion", it) }
            c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.let { put("esperar", it) }
        }
        c.disconnect()
        paquete(codigo, cabeza.toString(), salida.toByteArray())
    } catch (e: Exception) {
        paquete(0, buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }.toString(), ByteArray(0))
    }

    // --------------------------------------------------------------------------- el cifrado

    private fun clave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    /** `[12 de iv][cifrado con su etiqueta]`. */
    override fun cifrar(datos: ByteArray): ByteArray = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave()) }
        c.iv + c.doFinal(datos)
    }.getOrDefault(ByteArray(0))

    override fun descifrar(datos: ByteArray): ByteArray = runCatching {
        if (datos.size < 13) return ByteArray(0)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, datos.copyOfRange(0, 12))) }
        c.doFinal(datos, 12, datos.size - 12)
    }.getOrDefault(ByteArray(0))

    // -------------------------------------------------------------------------- el navegador

    override fun abrir(url: String): String = runCatching {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        ""
    }.getOrElse { it.message ?: "no se pudo abrir el navegador" }

    override fun parado(): Boolean = parar.get()

    // ------------------------------------------------------------------------------ el chat

    override fun archivo(descripcion: String): String = try {
        val a = almacen ?: error("no hay chat")
        val c = DocumentosDelAsistente(contexto, a, chat).elegir(descripcion.ifBlank { null }, DocumentosDelAsistente.Sirve.CUALQUIERA)
        buildJsonObject { put("nombre", c.nombre); put("ruta", c.ruta.path) }.toString()
    } catch (e: Exception) {
        buildJsonObject { put("error", e.message ?: "no hay archivo") }.toString()
    }

    override fun textoDe(ruta: String): String = try {
        buildJsonObject { put("texto", texto(File(ruta))) }.toString()
    } catch (e: Exception) {
        buildJsonObject { put("error", e.message ?: "no se pudo leer") }.toString()
    }

    /** El texto de un fichero suelto (lo bajado de Drive): el lector de cada tipo, como `leer_documento`. */
    private fun texto(f: File): String {
        val ext = f.extension.lowercase()
        val sb = StringBuilder()
        when {
            ext == "pdf" -> {
                val doc = com.forge.pixpin.motor.leerPdf(f.readBytes()) ?: error("no se pudo leer el PDF")
                if (doc.cifrado) error("el PDF está protegido")
                for (n in doc.paginas().indices.take(200)) {
                    val lineas = com.forge.pixpin.motor.PdfAHtml.lineasEnOrden(com.forge.pixpin.motor.PdfAHtml.trozosDe(com.forge.pixpin.motor.PlanoDePdf.de(doc, n)?.textos.orEmpty()))
                    sb.append("--- página ${n + 1} ---\n").append(lineas.joinToString("\n") { it.texto }).append('\n')
                }
            }
            ext in setOf("docx", "doc", "pptx", "ppt", "odt", "rtf") -> {
                val d = com.forge.pixpin.office.HojasOffice.de(contexto, f.path) ?: error("no se pudo leer «${f.name}»")
                d.maquetarTodo { }
                for (n in 0 until d.cuantas()) sb.append("--- ${if (d.esPresentacion) "diapositiva" else "página"} ${n + 1} ---\n").append(d.texto(n)).append('\n')
            }
            com.forge.pixpin.motor.ImportarHojas.esLibro(f.name) -> for (h in com.forge.pixpin.motor.ImportarHojas.leer(f, f.name)) {
                sb.append("--- hoja ${h.nombre} ---\n")
                h.tabla.celdas.entries.take(4000).forEach { (d, v) -> sb.append(d).append('=').append(v).append('\n') }
            }
            ext in setOf("png", "jpg", "jpeg", "webp", "bmp", "heic") -> {
                val bm = com.forge.pixpin.pin.ImageStore.load(f.path) ?: error("no se pudo abrir la imagen")
                sb.append(LectorDeLetras.lineas(bm)?.joinToString("\n").orEmpty()).also { bm.recycle() }
            }
            else -> sb.append(f.readText())
        }
        return sb.toString()
    }

    override fun alChat(ruta: String, frase: String): String = try {
        val a = almacen ?: error("no hay chat")
        val origen = File(ruta)
        val ahora = System.currentTimeMillis()
        val destino = File(a.carpetaDeAdjuntos(), "$ahora-${origen.name}")
        origen.copyTo(destino, overwrite = true)
        val imagen = destino.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
        a.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = if (imagen) Clase.IMAGEN else Clase.ARCHIVO,
            ruta = destino.path, nombre = origen.name, bytes = destino.length(), texto = frase, proyecto = chat))
        ""
    } catch (e: Exception) {
        e.message ?: "no se pudo dejar en el chat"
    }

    override fun confirmar(pregunta: String): Boolean = preguntar(pregunta)

    override fun carpeta(): String = File(contexto.cacheDir, "conexiones").apply { mkdirs() }.path

    // ----------------------------------------------------------------------------- atajos

    private val claves get() = carpetaDeClaves(contexto).path

    fun hacer(herramienta: String, args: String): Result<String> =
        ConexionesNativas.ok(ConexionesNativas.hacer(herramienta, args, claves, this)).map { (it as JsonPrimitive).content }

    fun estado(servicio: String) = ConexionesNativas.ok(ConexionesNativas.estado(servicio, claves, this))
    fun guardarCliente(servicio: String, pegado: String) = ConexionesNativas.ok(ConexionesNativas.guardarCliente(servicio, pegado, claves, this))
    fun conectar(servicio: String) = ConexionesNativas.ok(ConexionesNativas.conectar(servicio, claves, this))
    fun desconectar(servicio: String) = ConexionesNativas.ok(ConexionesNativas.desconectar(servicio, claves, this))
    fun eventos(desde: Long, hasta: Long) = ConexionesNativas.ok(ConexionesNativas.eventos(desde, hasta, claves, this)).map { it as? JsonArray ?: JsonArray(emptyList()) }
}
