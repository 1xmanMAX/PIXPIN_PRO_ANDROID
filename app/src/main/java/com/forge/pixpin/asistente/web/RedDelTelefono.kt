package com.forge.pixpin.asistente.web

import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * **La red de verdad para leer la web** (28-oct-2026, `nube/http.rs::bajar_web` del PC): un GET a
 * una página ya comprobada por [Direccion], con OkHttp y:
 *
 * - **sin cookies** ([CookieJar.NO_COOKIES]), sin credenciales ni cabecera de autorización, sin
 *   caché en disco y **sin la clave de la nube** (esto no pasa por [com.forge.pixpin.ia.Nube]);
 * - **sin seguir redirecciones**: una 3xx vuelve con su `Location` para que [Web] la compruebe
 *   otra vez antes de seguirla (hasta 5);
 * - sin proxy (como el PC) y con un DNS que **vuelve a comprobar las IP** al conectar
 *   ([Direccion.comprobarIps]): aunque el nombre cambie de IP entre la comprobación y la conexión,
 *   no se llega a una de este teléfono ni de la red de casa;
 * - tiempos cortos (10 s para conectar, 20 s sin recibir nada), **30 s la página entera** y **hasta
 *   5 MB**; lo que pase de ahí se corta.
 *
 * Una página `http://` sin cifrar no se abre en el teléfono (Android no deja por omisión): el error
 * lo dice y el modelo prueba con `https://`.
 */
object RedDelTelefono : Web.Red {
    /** Quién pide la página, sin nada del usuario. */
    private const val AGENTE = "Mozilla/5.0 (Linux; Android) PixPinMax/1.0 (asistente; lee la pagina para el usuario)"

    /** El DNS que solo deja conectar a IP públicas. */
    private object DnsPublico : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ips = InetAddress.getAllByName(hostname).toList()
            try {
                Direccion.comprobarIps(hostname, ips.map { Direccion.Ip(it.address) })
            } catch (e: Direccion.NoSeAbre) {
                throw UnknownHostException(e.message)
            }
            return ips
        }
    }

    private val cliente by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .proxy(Proxy.NO_PROXY)
            .dns(DnsPublico)
            .cache(null)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(Web.PLAZO_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    override fun resolver(servidor: String, puerto: Int): List<Direccion.Ip> {
        Direccion.Ip.de(servidor)?.let { return listOf(it) }
        return try {
            InetAddress.getAllByName(servidor).map { Direccion.Ip(it.address) }
        } catch (_: UnknownHostException) {
            throw Direccion.NoSeAbre("no se encontro el servidor «$servidor» (¿hay internet?)")
        } catch (_: SecurityException) {
            throw Direccion.NoSeAbre("PixPin no tiene permiso para usar internet")
        }
    }

    override fun bajar(url: String, cabeceras: List<Pair<String, String>>): Web.Bajada {
        val empezo = System.currentTimeMillis()
        val peticion = try {
            val b = Request.Builder().url(url).get().header("User-Agent", AGENTE)
            for ((k, v) in cabeceras) b.header(k, v)
            b.build()
        } catch (_: IllegalArgumentException) {
            throw Web.Fallo.Red("la direccion no es valida")
        }
        val r = try {
            cliente.newCall(peticion).execute()
        } catch (e: java.net.UnknownServiceException) {
            throw Web.Fallo.Red("el telefono no abre paginas sin cifrar (http://): prueba la misma direccion con https://")
        } catch (e: java.io.InterruptedIOException) {
            throw Web.Fallo.Red("la pagina tarda demasiado (mas de ${Web.PLAZO_MS / 1000} s)")
        } catch (e: UnknownHostException) {
            throw Web.Fallo.Red(e.message?.takeIf { "red local" in it } ?: "no se encontro el servidor (¿hay internet?)")
        } catch (e: javax.net.ssl.SSLException) {
            throw Web.Fallo.Red("su certificado no es valido (${e.javaClass.simpleName})")
        } catch (e: IOException) {
            throw Web.Fallo.Red("se corto la conexion (${e.javaClass.simpleName})")
        }
        r.use { resp ->
            val codigo = resp.code
            if (codigo !in 200..399) throw Web.Fallo.Http(codigo)
            val tipo = resp.header("Content-Type")
            val ubicacion = resp.header("Location")
            if (codigo in 300..399) return Web.Bajada(codigo, ByteArray(0), tipo, ubicacion)
            val todo = java.io.ByteArrayOutputStream()
            try {
                resp.body?.byteStream()?.use { s ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (System.currentTimeMillis() - empezo > Web.PLAZO_MS)
                            throw Web.Fallo.Red("la pagina tarda demasiado (mas de ${Web.PLAZO_MS / 1000} s)")
                        val n = s.read(buf)
                        if (n < 0) break
                        todo.write(buf, 0, n)
                        if (todo.size() > Paginas.BYTES_MAXIMOS)
                            throw Web.Fallo.Red("la pagina pasa de ${Paginas.BYTES_MAXIMOS / (1024 * 1024)} MB")
                    }
                }
            } catch (e: Web.Fallo) {
                throw e
            } catch (e: IOException) {
                throw Web.Fallo.Red("se corto la pagina (${e.javaClass.simpleName})")
            }
            return Web.Bajada(codigo, todo.toByteArray(), tipo, ubicacion)
        }
    }
}
