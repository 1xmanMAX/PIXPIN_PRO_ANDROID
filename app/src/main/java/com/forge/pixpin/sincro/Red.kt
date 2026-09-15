package com.forge.pixpin.sincro

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.motor.ExcalidrawStore
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * **La parte de Android: escuchar en la Wi-Fi, anunciarse y encontrar a los demás.**
 *
 * Todo lo que decide está en [Protocolo], [Envio] y [Disco]; esto solo abre sockets y habla con
 * el servicio de descubrimiento del sistema (mDNS, el mismo que usan las impresoras y los
 * Chromecast), que no necesita internet ni permisos especiales. Lo usan la sincronización (ver
 * [Presencia]) y el envío de una sola vez, cada uno con su tipo de servicio.
 */
class Red(private val context: Context) {

    /** Un aparato encontrado en la red, con lo que dice su anuncio. */
    data class Vecino(val nombre: String, val host: InetAddress, val puerto: Int, val datos: Map<String, String>) {
        val id: String get() = datos["id"].orEmpty()
    }

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val principal = Handler(Looper.getMainLooper())
    private var servidor: ServerSocket? = null
    private var anuncio: NsdManager.RegistrationListener? = null
    private var anunciadoCon: String? = null
    private var busqueda: NsdManager.DiscoveryListener? = null
    private var candado: WifiManager.MulticastLock? = null
    private val vecinos = ConcurrentHashMap<String, Vecino>()

    val puerto: Int get() = servidor?.localPort ?: 0
    val escuchando: Boolean get() = servidor != null

    /**
     * Empieza a atender a quien venga, **una conexión detrás de otra**: dos aparatos escribiendo
     * el mismo chat a la vez es justo lo que hay que evitar. Un «PING» se contesta y se cierra
     * sin más: es como la pantalla comprueba si un aparato recordado sigue ahí.
     */
    fun escuchar(puertoPreferido: Int, atender: (Socket, InputStream) -> Unit) {
        if (servidor != null) return
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(puertoPreferido)) } }
            .getOrElse { ServerSocket(0) }
        servidor = s
        Thread({
            while (!s.isClosed) {
                val cliente = runCatching { s.accept() }.getOrNull() ?: break
                runCatching {
                    cliente.use { c ->
                        c.soTimeout = 10_000
                        c.tcpNoDelay = true
                        val entrada = PushbackInputStream(c.getInputStream(), 4)
                        val cuatro = ByteArray(4)
                        var n = 0
                        while (n < 4) { val r = entrada.read(cuatro, n, 4 - n); if (r < 0) break; n += r }
                        if (n == 4 && String(cuatro) == "PING") {
                            c.getOutputStream().apply { write("PONG".toByteArray()); flush() }
                        } else if (n > 0) {
                            entrada.unread(cuatro, 0, n)
                            // Largo: mientras el otro decide qué sincronizar o cuál conservar, aquí no llega nada.
                            c.soTimeout = 30 * 60_000
                            atender(c, entrada)
                        }
                    }
                }
            }
        }, "pixpin-red-escucha").start()
    }

    /** Se anuncia en la red. Anunciar otra vez lo mismo no hace nada; algo distinto sustituye al anterior. */
    fun anunciar(tipo: String, nombre: String, datos: Map<String, String>) {
        val puerto = puerto.takeIf { it > 0 } ?: return
        val clave = "$tipo|$nombre|$puerto|$datos"
        principal.post {
            if (servidor == null || (anuncio != null && anunciadoCon == clave)) return@post
            dejarDeAnunciar()
            val info = NsdServiceInfo().apply {
                serviceName = nombre.take(60)
                serviceType = tipo
                port = puerto
                for ((k, v) in datos) setAttribute(k, v)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { principal.post { if (anuncio === this) { anuncio = null; anunciadoCon = null } } }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            anuncio = l
            anunciadoCon = clave
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { anuncio = null; anunciadoCon = null }
        }
    }

    private fun dejarDeAnunciar() {
        anuncio?.let { runCatching { nsd.unregisterService(it) } }
        anuncio = null
        anunciadoCon = null
    }

    /**
     * Busca servicios de [tipo] que cumplan [vale] y avisa con la lista cada vez que cambia.
     * Llamar desde el hilo principal.
     */
    fun buscar(tipo: String, vale: (Map<String, String>) -> Boolean, alCambiar: (List<Vecino>) -> Unit) {
        pararBusqueda()
        vecinos.clear()
        alCambiar(emptyList())
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        candado = runCatching { wifi?.createMulticastLock("pixpin-red")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val porResolver = ArrayDeque<NsdServiceInfo>()
        var resolviendo = false
        lateinit var l: NsdManager.DiscoveryListener
        fun lista() = vecinos.values.distinctBy { it.id.ifBlank { it.nombre } }.sortedBy { it.nombre }
        fun siguiente() {
            if (resolviendo || busqueda !== l) return
            val info = porResolver.removeFirstOrNull() ?: return
            resolviendo = true
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, error: Int) {
                        principal.post {
                            resolviendo = false
                            if (error == NsdManager.FAILURE_ALREADY_ACTIVE) principal.postDelayed({ porResolver.addLast(info); siguiente() }, 300)
                            siguiente()
                        }
                    }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        principal.post {
                            resolviendo = false
                            val datos = i.attributes.mapValues { it.value?.decodeToString().orEmpty() }
                            @Suppress("DEPRECATION") val host = i.host
                            if (busqueda === l && host != null && vale(datos)) {
                                vecinos[i.serviceName] = Vecino(datos["n"] ?: i.serviceName, host, i.port, datos)
                                alCambiar(lista())
                            }
                            siguiente()
                        }
                    }
                })
            }.onFailure { resolviendo = false }
        }
        l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(tipo: String) {}
            override fun onDiscoveryStopped(tipo: String) {}
            override fun onStartDiscoveryFailed(tipo: String, e: Int) {}
            override fun onStopDiscoveryFailed(tipo: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                principal.post { porResolver.addLast(info); siguiente() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                principal.post { if (vecinos.remove(info.serviceName) != null) alCambiar(lista()) }
            }
        }
        busqueda = l
        runCatching { nsd.discoverServices(tipo, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun pararBusqueda() {
        busqueda?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        busqueda = null
        runCatching { candado?.release() }
        candado = null
    }

    fun cerrar() {
        pararBusqueda()
        dejarDeAnunciar()
        runCatching { servidor?.close() }
        servidor = null
    }

    companion object {
        const val TIPO = "_pixpin._tcp."
        /** Uno fijo, para poder teclearlo; si está ocupado, cualquiera. */
        const val PUERTO = 47474

        fun conectar(host: InetAddress, puerto: Int): Socket = Socket().apply {
            connect(InetSocketAddress(host, puerto), 8_000)
            soTimeout = 120_000
            tcpNoDelay = true
        }

        /** Si hay un PixPin escuchando ahí. Rápido: poco más de un segundo como mucho. */
        fun sondear(host: String, puerto: Int): Boolean = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, puerto), 1_200)
                s.soTimeout = 1_500
                s.getOutputStream().apply { write("PING".toByteArray()); flush() }
                val b = ByteArray(4)
                var n = 0
                while (n < 4) { val r = s.getInputStream().read(b, n, 4 - n); if (r < 0) break; n += r }
                n == 4 && String(b) == "PONG"
            }
        }.getOrDefault(false)

        /** La dirección de este aparato en la Wi-Fi, para conectar a mano si el descubrimiento no va. */
        fun miDireccion(context: Context): String? = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val red = cm.activeNetwork ?: return null
            cm.getLinkProperties(red)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()

        /**
         * El disco con los avisos a la aplicación: recargar los proyectos, el chat y los lienzos
         * abiertos cuando la sincronización escribe algo.
         */
        fun disco(context: Context): Disco {
            val app = context.applicationContext
            val principal = Handler(Looper.getMainLooper())
            return Disco(app.filesDir) { cambio ->
                when (cambio) {
                    Disco.Cambio.MENSAJES -> MensajesStore.cambios.value = MensajesStore.cambios.value + 1
                    Disco.Cambio.PROYECTOS -> {
                        (app as? PixPinApp)?.proyectos?.recargar()
                        com.forge.pixpin.guardados.ChatDeLosProyectos.reparar(app)
                    }
                    Disco.Cambio.ARCHIVOS -> principal.post { ExcalidrawStore.revision.intValue++ }
                    Disco.Cambio.IDENTIDAD -> Presencia.identidadCambio()
                }
            }
        }
    }
}
