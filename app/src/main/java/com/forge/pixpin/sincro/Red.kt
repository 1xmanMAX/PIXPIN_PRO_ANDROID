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
    /** Lo anunciado: con el tipo nuevo y, si se pudo, con el de antes. */
    private val anuncios = mutableListOf<NsdManager.RegistrationListener>()
    private var anunciadoCon: String? = null
    private var busquedas: List<NsdManager.DiscoveryListener> = emptyList()
    /** Sube con cada búsqueda: lo que llega de una anterior ya no cuenta. */
    private var ronda = 0
    private var candado: WifiManager.MulticastLock? = null
    private val vecinos = ConcurrentHashMap<String, Vecino>()

    val puerto: Int get() = servidor?.localPort ?: 0
    val escuchando: Boolean get() = servidor != null

    /**
     * Empieza a atender a quien venga, **una conexión detrás de otra**: dos aparatos escribiendo
     * el mismo chat a la vez es justo lo que hay que evitar. Un «PING» se contesta y se cierra
     * sin más: es como la pantalla comprueba si un aparato recordado sigue ahí.
     */
    /**
     * Con [enParalelo], cada conexión se atiende en su propio hilo. Lo usa el envío a varios
     * aparatos (16-sep-2026): ahí quien manda se queda esperando a aprobar a cada uno, y con una
     * sola cola el segundo que llega ni siquiera aparecería en la lista hasta acabar con el
     * primero. La sincronización sigue de una en una **a propósito**: dos conversaciones
     * escribiendo el mismo chat a la vez es lo que se quiere evitar.
     */
    fun escuchar(puertoPreferido: Int, enParalelo: Boolean = false, atender: (Socket, InputStream) -> Unit) {
        if (servidor != null) return
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(puertoPreferido)) } }
            .getOrElse { ServerSocket(0) }
        servidor = s
        Thread({
            while (!s.isClosed) {
                val cliente = runCatching { s.accept() }.getOrNull() ?: break
                val trabajo = Runnable {
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
                if (enParalelo) Thread(trabajo, "pixpin-red-atiende").start() else trabajo.run()
            }
        }, "pixpin-red-escucha").start()
    }

    /**
     * Se anuncia en la red. Anunciar otra vez lo mismo no hace nada; algo distinto sustituye al anterior.
     *
     * **Con los dos nombres a la vez** (1-nov-2026, como el PC: `pindesk_shell::mdns::anunciar`):
     * un tipo de PinDesk (`_pindesk…._tcp.`) se anuncia también con su tipo de antes
     * ([tipoDeAntes]), para que lo vean los aparatos sin actualizar, que solo buscan `_pixpin…`.
     * Si el de antes falla, se sigue con el nuevo solo.
     */
    fun anunciar(tipo: String, nombre: String, datos: Map<String, String>) {
        val puerto = puerto.takeIf { it > 0 } ?: return
        val clave = "$tipo|$nombre|$puerto|$datos"
        principal.post {
            if (servidor == null || (anuncios.isNotEmpty() && anunciadoCon == clave)) return@post
            dejarDeAnunciar()
            anunciadoCon = clave
            for (t in conElDeAntes(tipo)) {
                val info = NsdServiceInfo().apply {
                    serviceName = nombre.take(60)
                    serviceType = t
                    port = puerto
                    for ((k, v) in datos) setAttribute(k, v)
                }
                val l = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(i: NsdServiceInfo) {}
                    override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {
                        principal.post { if (anuncios.remove(this) && anuncios.isEmpty()) anunciadoCon = null }
                    }
                    override fun onServiceUnregistered(i: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
                }
                anuncios += l
                runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { anuncios.remove(l) }
            }
            if (anuncios.isEmpty()) anunciadoCon = null
        }
    }

    private fun dejarDeAnunciar() {
        for (l in anuncios) runCatching { nsd.unregisterService(l) }
        anuncios.clear()
        anunciadoCon = null
    }

    /**
     * Busca servicios de [tipo] que cumplan [vale] y avisa con la lista cada vez que cambia.
     * Llamar desde el hilo principal.
     *
     * Un tipo de PinDesk se busca **también con su tipo de antes** (1-nov-2026), a la vez: un
     * aparato sin actualizar solo sale con `_pixpin…`, y uno al día sale con los dos; ese queda una
     * vez, con lo del nuevo ([juntar]).
     */
    fun buscar(tipo: String, vale: (Map<String, String>) -> Boolean, alCambiar: (List<Vecino>) -> Unit) {
        pararBusqueda()
        vecinos.clear()
        alCambiar(emptyList())
        val estaRonda = ++ronda
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        candado = runCatching { wifi?.createMulticastLock("pixpin-red")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        // Cada uno con el tipo por el que salió: 0 el nuevo, 1 el de antes.
        val porResolver = ArrayDeque<Pair<NsdServiceInfo, Int>>()
        var resolviendo = false
        fun lista() = juntar(
            vecinos.filterKeys { it.startsWith("0|") }.values.toList(),
            vecinos.filterKeys { it.startsWith("1|") }.values.toList()
        )
        // **Sin resolver dos veces lo mismo, y con tope** (3-nov-2026, `docs/emparejar.md` del PC): un
        // aparato al día sale con los dos tipos; el de antes solo se resuelve si su nombre no salió
        // ya con el nuevo. Y una resolución que Android no contesta no para la cola: a los 5 s, la siguiente.
        val vistosNuevos = HashSet<String>()
        var turno = 0
        fun siguiente() {
            if (resolviendo || ronda != estaRonda) return
            var par = porResolver.removeFirstOrNull() ?: return
            while (par.second == 1 && par.first.serviceName in vistosNuevos) par = porResolver.removeFirstOrNull() ?: return
            val (info, cual) = par
            if (cual == 0) vistosNuevos += info.serviceName
            resolviendo = true
            val esteTurno = ++turno
            principal.postDelayed({ if (turno == esteTurno && resolviendo) { resolviendo = false; siguiente() } }, 5_000)
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, error: Int) {
                        principal.post {
                            if (turno != esteTurno) return@post
                            resolviendo = false
                            if (error == NsdManager.FAILURE_ALREADY_ACTIVE) principal.postDelayed({ porResolver.addLast(info to cual); siguiente() }, 300)
                            siguiente()
                        }
                    }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        principal.post {
                            val tarde = turno != esteTurno
                            if (!tarde) resolviendo = false
                            val datos = i.attributes.mapValues { it.value?.decodeToString().orEmpty() }
                            @Suppress("DEPRECATION") val host = i.host
                            if (ronda == estaRonda && host != null && vale(datos)) {
                                vecinos["$cual|${i.serviceName}"] = Vecino(datos["n"] ?: i.serviceName, host, i.port, datos)
                                alCambiar(lista())
                            }
                            if (!tarde) siguiente()
                        }
                    }
                })
            }.onFailure { resolviendo = false }
        }
        val nuevas = conElDeAntes(tipo).mapIndexed { cual, t ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(tipo: String) {}
                override fun onDiscoveryStopped(tipo: String) {}
                override fun onStartDiscoveryFailed(tipo: String, e: Int) {}
                override fun onStopDiscoveryFailed(tipo: String, e: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    principal.post { porResolver.addLast(info to cual); siguiente() }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    principal.post { if (ronda == estaRonda && vecinos.remove("$cual|${info.serviceName}") != null) alCambiar(lista()) }
                }
            }
            t to l
        }
        busquedas = nuevas.map { it.second }
        for ((t, l) in nuevas) runCatching { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun pararBusqueda() {
        ronda++
        for (l in busquedas) runCatching { nsd.stopServiceDiscovery(l) }
        busquedas = emptyList()
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
        /**
         * El tipo mDNS de la sincronización: `_pindesk._tcp.` desde el 1-nov-2026, como el PC
         * (`pindesk_sincro::SERVICIO`). Hasta entonces era `_pixpin._tcp.`, que se sigue
         * anunciando y buscando a la vez ([tipoDeAntes]): lo usan los aparatos sin actualizar.
         */
        const val TIPO = "_pindesk._tcp."

        /** El prefijo del nombre de la instancia que se anuncia: «PinDesk <aparato>» (antes «PixPin …»). */
        const val INSTANCIA = "PinDesk"

        /** El nombre de la instancia de [aparato]: se enseña, no se compara (se compara el TXT `g`/`id`). */
        fun instancia(aparato: String): String = "$INSTANCIA $aparato"

        /**
         * El tipo de antes de PinDesk de un tipo de ahora: `_pindesk._tcp.` → `_pixpin._tcp.`,
         * `_pindeskenvio._tcp.` → `_pixpinenvio._tcp.`… null si no es uno de los nuestros (o ya es
         * uno de antes). Como `tipo_de_antes` del PC; puro, para probarlo sin red.
         */
        fun tipoDeAntes(tipo: String): String? {
            val t = tipo.trim()
            if (!t.startsWith("_pindesk") || !t.contains("._tcp")) return null
            return "_pixpin" + t.removePrefix("_pindesk")
        }

        /** [tipo] y, si lo tiene, su tipo de antes: el nuevo delante. */
        fun conElDeAntes(tipo: String): List<String> = listOfNotNull(tipo, tipoDeAntes(tipo))

        /**
         * **Junta lo encontrado con el tipo de ahora y con el de antes**: un aparato que sale en los
         * dos queda una vez, con lo del nuevo. Se le reconoce por su TXT (`id`; sin él, `g` y el
         * nombre), no por el nombre de la instancia, que cambia de «PixPin» a «PinDesk».
         */
        fun juntar(ahora: List<Vecino>, antes: List<Vecino>): List<Vecino> =
            (ahora + antes).distinctBy { clave(it) }.sortedBy { it.nombre }

        private fun clave(v: Vecino): String = v.id.ifBlank { v.datos["g"].orEmpty() + "|" + v.nombre }
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
                    // La galería se relee sola al volver a ella; esto es para quien la tenga delante.
                    Disco.Cambio.GALERIA -> com.forge.pixpin.capture.CapturasEnElTelefono.cambio.value++
                }
            }.also { it.capturas = com.forge.pixpin.capture.CapturasEnElTelefono(app) }
        }
    }
}
