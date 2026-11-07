package com.forge.pixpin.cuenta

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * **Actualizarse sin la Play Store, como Telegram** (7-nov-2026). El servidor dice cuál es la última versión
 * (`/v1/version`, **firmada**: nadie puede colar otra dirección) con la **huella SHA-256** del APK. La app avisa,
 * lo baja con su avance, comprueba que la huella es la de la firma y abre el instalador de Android (la persona toca
 * «Instalar»; eso no se puede saltar fuera de la Play Store). Android, además, solo instala encima un APK firmado con
 * la misma clave que el que hay. Con **versión mínima**, quien tenga una más vieja tiene que actualizar.
 */
object Actualizar {
    data class Nueva(val versionCode: Int, val versionName: String, val url: String, val sha256: String, val notas: String, val obligatoria: Boolean, val tamano: Long?)

    /** La versión nueva que hay, si hay (la pantalla de inicio la enseña). */
    val nueva = MutableStateFlow<Nueva?>(null)
    /** Bajando: de 0 a 1; null si no. */
    val bajando = MutableStateFlow<Float?>(null)

    private val red by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    /** Pregunta al servidor; [actual] es esta versión. */
    suspend fun mirar(actual: Int = com.forge.pixpin.BuildConfig.VERSION_CODE): Nueva? = withContext(Dispatchers.IO) {
        if (!Cuenta.servidor.startsWith("https://") || Cuenta.publica.isBlank()) return@withContext null
        runCatching {
            red.newCall(Request.Builder().url("${Cuenta.servidor}/v1/version").build()).execute().use { r ->
                val o = Json.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject
                desde(Firmado.comprobar(o, Cuenta.publica), actual)
            }
        }.getOrNull().also { nueva.value = it }
    }

    /** La versión que dice el documento ya comprobado, si es más nueva que [actual] (puro, para las pruebas). */
    fun desde(v: JsonObject?, actual: Int): Nueva? {
        if (v == null || v["tipo"]?.jsonPrimitive?.content != "version") return null
        val codigo = v["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
        val url = v["url"]?.jsonPrimitive?.content ?: return null
        val sha = v["sha256"]?.jsonPrimitive?.content?.lowercase() ?: return null
        if (codigo <= actual || !url.startsWith("https://") || !Regex("^[0-9a-f]{64}$").matches(sha)) return null
        val minimo = v["minimo"]?.jsonPrimitive?.intOrNull
        return Nueva(codigo, v["versionName"]?.jsonPrimitive?.content.orEmpty(), url, sha,
            v["notas"]?.jsonPrimitive?.content.orEmpty(), minimo != null && actual < minimo, v["tamano"]?.jsonPrimitive?.longOrNull)
    }

    /** Si Android ya deja a PinDesk instalar actualizaciones (la primera vez, la persona lo permite en Ajustes). */
    fun puedeInstalar(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    fun pedirPermiso(c: Context) = runCatching {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    sealed class Resultado {
        object Instalando : Resultado()
        object HuellaMala : Resultado()
        class Fallo(val motivo: String) : Resultado()
    }

    /** La baja, comprueba la huella y abre el instalador. */
    suspend fun bajarEInstalar(c: Context, v: Nueva): Resultado = withContext(Dispatchers.IO) {
        val destino = File(File(c.cacheDir, "actualizacion").apply { mkdirs() }, "PinDesk-${v.versionCode}.apk")
        val parte = File(destino.parentFile, destino.name + ".parte")
        try {
            bajando.value = 0f
            red.newCall(Request.Builder().url(v.url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext Resultado.Fallo("No se pudo bajar (${r.code})")
                val cuerpo = r.body ?: return@withContext Resultado.Fallo("No se pudo bajar")
                val total = cuerpo.contentLength().takeIf { it > 0 } ?: v.tamano ?: -1L
                val huella = MessageDigest.getInstance("SHA-256")
                cuerpo.byteStream().use { i ->
                    parte.outputStream().buffered().use { o ->
                        val buf = ByteArray(64 * 1024); var n = 0L
                        while (true) {
                            val k = i.read(buf); if (k < 0) break
                            o.write(buf, 0, k); huella.update(buf, 0, k); n += k
                            if (total > 0) bajando.value = (n.toFloat() / total).coerceAtMost(1f)
                        }
                    }
                }
                val hex = huella.digest().joinToString("") { "%02x".format(it) }
                // La huella tiene que ser la de la versión firmada: si no, no se instala (y se borra).
                if (hex != v.sha256) { parte.delete(); return@withContext Resultado.HuellaMala }
            }
            destino.delete(); parte.renameTo(destino)
            val uri = com.forge.pixpin.ui.AbrirCon.uriDe(c, destino)
            withContext(Dispatchers.Main) {
                c.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Resultado.Instalando
        } catch (e: Exception) {
            parte.delete()
            Resultado.Fallo("Sin conexión")
        } finally { bajando.value = null }
    }
}
