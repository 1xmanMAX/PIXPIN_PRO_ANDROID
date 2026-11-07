package com.forge.pixpin.cuenta

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * **La cuenta de PinDesk** (7-nov-2026): entrar con Google, la sesión, y traer del servidor (`servidor/pindesk`) el
 * catálogo de funciones y la licencia de cada persona ([Disponible]). También es la sesión con la que el asistente
 * pregunta al servidor (la clave de Claude nunca está en la app, ver [com.forge.pixpin.asistente.AccesoDePrueba]).
 *
 * La conexión va siempre por HTTPS (y Android no acepta certificados que el usuario instale a mano), y lo que decide
 * va firmado: ni quien esté «en medio» de la red ni quien toque los archivos de la app puede darse Pro.
 */
object Cuenta {
    /** La dirección del servidor (la de la compilación); vacía: la app va sola con el catálogo de fábrica. */
    val servidor: String get() = com.forge.pixpin.BuildConfig.SERVIDOR_PINDESK.ifBlank { com.forge.pixpin.BuildConfig.SERVIDOR_IA_DE_PRUEBA }.trimEnd('/')
    /** La clave pública del servidor, para comprobar sus firmas. */
    val publica: String get() = com.forge.pixpin.BuildConfig.FIRMA_PUBLICA
    private val clienteWeb: String get() = com.forge.pixpin.BuildConfig.GOOGLE_CLIENTE_WEB

    /** Si esta compilación trae cuentas (servidor https, cliente de Google y la clave pública). */
    val configurada: Boolean get() = servidor.startsWith("https://") && clienteWeb.isNotBlank() && publica.isNotBlank()

    data class Datos(val email: String, val nombre: String?, val foto: String?)
    private val _datos = MutableStateFlow<Datos?>(null)
    /** Quién ha entrado (null: nadie). */
    val datos: StateFlow<Datos?> get() = _datos

    /** El cupo del asistente de hoy, si se sabe: (quedan, total). */
    val cupo = MutableStateFlow<Pair<Int, Int>?>(null)

    private val red by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private val fondo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun fichero(c: Context) = File(c.noBackupFilesDir, "cuenta.json")

    private fun guardado(c: Context): JsonObject? = runCatching { Json.parseToJsonElement(fichero(c).readText()) as JsonObject }.getOrNull()

    /** La sesión guardada (va con cada petición), o null. */
    fun sesion(c: Context): String? = guardado(c)?.get("sesion")?.jsonPrimitive?.content

    private fun guardar(c: Context, sesion: String, d: Datos) {
        fichero(c).parentFile?.mkdirs()
        fichero(c).writeText(buildJsonObject {
            put("sesion", JsonPrimitive(sesion)); put("email", JsonPrimitive(d.email))
            d.nombre?.let { put("nombre", JsonPrimitive(it)) }; d.foto?.let { put("foto", JsonPrimitive(it)) }
        }.toString())
        _datos.value = d
    }

    /**
     * **Al arrancar la app**: lo guardado (catálogo, licencia, quién es) antes que nada, y luego, de fondo, lo nuevo
     * del servidor; después, cada 6 horas mientras la app siga viva.
     */
    fun arrancar(c: Context) {
        val app = c.applicationContext
        Disponible.cargar(app)
        guardado(app)?.let { g -> g["email"]?.jsonPrimitive?.content?.let { _datos.value = Datos(it, g["nombre"]?.jsonPrimitive?.content, g["foto"]?.jsonPrimitive?.content) } }
        if (!configurada) return
        fondo.launch {
            delay(2_000) // que la app abra primero
            while (true) {
                runCatching { refrescar(app) }
                runCatching { Actualizar.mirar() }
                delay(6 * 3_600_000L)
            }
        }
    }

    /** Pide al servidor lo de ahora: con sesión, su licencia y su cupo; sin ella, solo el catálogo. */
    suspend fun refrescar(c: Context): Unit = withContext(Dispatchers.IO) {
        if (!configurada) return@withContext
        val s = sesion(c)
        val pedido = Request.Builder().url(if (s != null) "$servidor/v1/estado" else "$servidor/v1/catalogo")
            .apply { if (s != null) header("Authorization", "Bearer $s") }.build()
        red.newCall(pedido).execute().use { r ->
            val o = runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject }.getOrNull()
            when {
                r.code == 401 && s != null -> {
                    // La sesión ya no vale (caducó o se cerró desde el panel): fuera la sesión, a gratis.
                    salirSinAvisar(c)
                    refrescar(c)
                }
                r.isSuccessful && o != null && s == null -> Disponible.recibir(c, o, null)
                r.isSuccessful && o != null -> {
                    Disponible.recibir(c, o["catalogo"]?.jsonObject, o["licencia"]?.jsonObject)
                    o["sesion"]?.jsonPrimitive?.content?.let { nueva -> _datos.value?.let { guardar(c, nueva, it) } }
                    o["cupo"]?.jsonObject?.let { cupo.value = (it["quedan"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) to (it["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) }
                    o["cuenta"]?.jsonObject?.let { cta ->
                        val d = Datos(cta["email"]?.jsonPrimitive?.content.orEmpty(), cta["nombre"]?.jsonPrimitive?.contentOrNull(), cta["foto"]?.jsonPrimitive?.contentOrNull())
                        if (d.email.isNotBlank()) sesion(c)?.let { guardar(c, it, d) }
                    }
                }
            }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null

    sealed class Entrada {
        class Dentro(val email: String, val nueva: Boolean) : Entrada()
        object Bloqueada : Entrada()
        class Fallo(val motivo: String) : Entrada()
    }

    /** **Entrar con Google**: el selector de cuentas del teléfono, su `id_token` al servidor y la sesión guardada. */
    suspend fun entrar(actividad: Activity): Entrada {
        if (!configurada) return Entrada.Fallo("Esta versión no trae cuentas")
        val idToken = try {
            val opcion = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(clienteWeb).build()
            val pedido = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(opcion).build()
            val r = androidx.credentials.CredentialManager.create(actividad).getCredential(actividad, pedido)
            com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(r.credential.data).idToken
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            return Entrada.Fallo("Cancelado")
        } catch (e: Exception) {
            return Entrada.Fallo("Google no dejó entrar: ${e.javaClass.simpleName}")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val cuerpo = buildJsonObject { put("idToken", JsonPrimitive(idToken)) }.toString()
                val pedido = Request.Builder().url("$servidor/v1/entrar").post(cuerpo.toRequestBody("application/json".toMediaType())).build()
                red.newCall(pedido).execute().use { r ->
                    val o = runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject }.getOrNull()
                    when {
                        r.code == 403 -> Entrada.Bloqueada
                        r.isSuccessful && o?.get("sesion") != null -> {
                            val cta = o["cuenta"]!!.jsonObject
                            val d = Datos(cta["email"]!!.jsonPrimitive.content, cta["nombre"]?.jsonPrimitive?.contentOrNull(), cta["foto"]?.jsonPrimitive?.contentOrNull())
                            guardar(actividad, o["sesion"]!!.jsonPrimitive.content, d)
                            Disponible.recibir(actividad, o["catalogo"]?.jsonObject, o["licencia"]?.jsonObject)
                            com.forge.pixpin.estadoia.EstadoDeLasIa.claveCambiada(actividad, com.forge.pixpin.ia.Nube.Proveedor.CLAUDE)
                            runCatching { refrescar(actividad) }
                            Entrada.Dentro(d.email, o["nueva"]?.jsonPrimitive?.content == "true")
                        }
                        else -> Entrada.Fallo("El servidor contestó ${r.code}")
                    }
                }
            }.getOrElse { Entrada.Fallo("Sin conexión con el servidor") }
        }
    }

    private fun salirSinAvisar(c: Context) {
        fichero(c).delete()
        _datos.value = null; cupo.value = null
        Disponible.recibir(c, null, null, sinLicencia = true)
    }

    /** Cerrar la sesión en este teléfono (vuelve a lo gratis). */
    fun salir(c: Context) {
        salirSinAvisar(c)
        com.forge.pixpin.estadoia.EstadoDeLasIa.claveCambiada(c, com.forge.pixpin.ia.Nube.Proveedor.CLAUDE)
    }
}
