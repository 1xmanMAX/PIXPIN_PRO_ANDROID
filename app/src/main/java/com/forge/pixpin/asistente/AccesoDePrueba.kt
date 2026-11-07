package com.forge.pixpin.asistente

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * **El acceso de prueba al asistente, con Google** (6-nov-2026, el usuario: «que inicien sesión con su correo y solo
 * tengan acceso los de la lista; luego los bloqueamos cuando termine la fase de pruebas»).
 *
 * La clave de Claude **no está en la app**: vive en el servidor de la prueba (`servidor/ia-de-prueba`, un Cloudflare
 * Worker). La persona entra con su cuenta de Google ([entrar]); el servidor comprueba que su correo está en la lista y
 * le da una **sesión**, que se guarda aquí. Desde entonces el asistente pregunta al servidor con esa sesión ([clave],
 * que [ClavesDeLaNube.leer] da cuando no hay una clave propia), y el servidor pregunta a Claude. Quitar el correo de la
 * lista lo deja sin acceso en la pregunta siguiente. Manual del protocolo (también para Windows):
 * `docs/acceso-de-prueba-ia.md`.
 */
object AccesoDePrueba {
    /** Lo que se le pone delante a la sesión para que [com.forge.pixpin.ia.Nube] sepa que va por el servidor. */
    const val PREFIJO = "pxt:"

    // **Desde el 7-nov-2026 la sesión es la de la cuenta de PinDesk** ([com.forge.pixpin.cuenta.Cuenta]) cuando la
    // compilación la trae; si no, la de la prueba de antes (`servidor/ia-de-prueba`).
    private val conCuenta: Boolean get() = com.forge.pixpin.cuenta.Cuenta.configurada
    val servidor: String get() = com.forge.pixpin.cuenta.Cuenta.servidor
    private val clienteWeb: String get() = com.forge.pixpin.BuildConfig.GOOGLE_CLIENTE_WEB

    /** Si esta compilación trae el acceso de prueba (la dirección del servidor y el cliente de Google). */
    val configurado: Boolean get() = servidor.startsWith("https://") && clienteWeb.isNotBlank()

    private fun fichero(c: Context) = File(c.noBackupFilesDir, "acceso-de-prueba.json")

    private class Guardado(val sesion: String, val email: String)

    private fun leer(c: Context): Guardado? = runCatching {
        val o = Json.parseToJsonElement(fichero(c).readText()) as JsonObject
        Guardado(o["sesion"]!!.jsonPrimitive.content, o["email"]!!.jsonPrimitive.content)
    }.getOrNull()

    /** El correo con el que se entró, o null. */
    fun correo(c: Context): String? = when {
        conCuenta -> com.forge.pixpin.cuenta.Cuenta.datos.value?.email
        configurado -> leer(c)?.email
        else -> null
    }

    /** La «clave» para [com.forge.pixpin.ia.Nube]: la sesión con su prefijo, solo para Claude. */
    fun clave(c: Context, p: com.forge.pixpin.ia.Nube.Proveedor): String? = when {
        p != com.forge.pixpin.ia.Nube.Proveedor.CLAUDE -> null
        conCuenta -> com.forge.pixpin.cuenta.Cuenta.sesion(c)?.let { PREFIJO + it }
        configurado -> leer(c)?.sesion?.let { PREFIJO + it }
        else -> null
    }

    fun salir(c: Context) {
        if (conCuenta) { com.forge.pixpin.cuenta.Cuenta.salir(c); return }
        fichero(c).delete()
        com.forge.pixpin.estadoia.EstadoDeLasIa.claveCambiada(c, com.forge.pixpin.ia.Nube.Proveedor.CLAUDE)
    }

    /** Lo que pasó al entrar. */
    sealed class Entrada {
        class Dentro(val email: String) : Entrada()
        class SinAcceso(val email: String?) : Entrada()
        class Fallo(val motivo: String) : Entrada()
    }

    /**
     * **Entrar con Google**: el selector de cuentas del teléfono (Credential Manager), su `id_token` al servidor y, si
     * el correo está en la lista, la sesión guardada.
     */
    suspend fun entrar(actividad: Activity): Entrada {
        if (conCuenta) return when (val e = com.forge.pixpin.cuenta.Cuenta.entrar(actividad)) {
            is com.forge.pixpin.cuenta.Cuenta.Entrada.Dentro -> Entrada.Dentro(e.email)
            com.forge.pixpin.cuenta.Cuenta.Entrada.Bloqueada -> Entrada.SinAcceso(null)
            is com.forge.pixpin.cuenta.Cuenta.Entrada.Fallo -> Entrada.Fallo(e.motivo)
        }
        if (!configurado) return Entrada.Fallo("Esta versión no trae el acceso de prueba")
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
                val cliente = okhttp3.OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
                val cuerpo = kotlinx.serialization.json.buildJsonObject { put("idToken", kotlinx.serialization.json.JsonPrimitive(idToken)) }.toString()
                val pedido = okhttp3.Request.Builder().url("$servidor/entrar")
                    .post(cuerpo.toRequestBody("application/json".toMediaType()))
                    .build()
                cliente.newCall(pedido).execute().use { r ->
                    val o = runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()) as JsonObject }.getOrNull()
                    val email = o?.get("email")?.jsonPrimitive?.content
                    when {
                        r.code == 403 -> Entrada.SinAcceso(email)
                        r.isSuccessful && o?.get("sesion") != null && email != null -> {
                            val guardar = kotlinx.serialization.json.buildJsonObject {
                                put("sesion", o["sesion"]!!); put("email", kotlinx.serialization.json.JsonPrimitive(email))
                            }
                            fichero(actividad).parentFile?.mkdirs()
                            fichero(actividad).writeText(guardar.toString())
                            com.forge.pixpin.estadoia.EstadoDeLasIa.claveCambiada(actividad, com.forge.pixpin.ia.Nube.Proveedor.CLAUDE)
                            Entrada.Dentro(email)
                        }
                        else -> Entrada.Fallo("El servidor de la prueba contestó ${r.code}")
                    }
                }
            }.getOrElse { Entrada.Fallo("Sin conexión con el servidor de la prueba") }
        }
    }
}
