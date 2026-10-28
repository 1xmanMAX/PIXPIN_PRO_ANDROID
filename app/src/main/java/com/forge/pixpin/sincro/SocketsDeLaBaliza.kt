package com.forge.pixpin.sincro

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * **Quien manda las balizas** (28-oct-2026, `Emisor` de `baliza.rs` del PC): un socket por red,
 * atado a su dirección, para que la difusión y la multidifusión salgan por cada una (con una VPN
 * puesta, la ruta por defecto es la del túnel y la Wi-Fi no se enteraría), y uno suelto para lo
 * demás. Solo `java.net`: lo prueba `BalizaContraElPcTest` contra el Rust de verdad.
 */
class EmisorDeBalizas private constructor(
    private val puerto: Int,
    private val porRed: List<Pair<Interfaz, DatagramSocket>>,
    private val suelto: DatagramSocket
) {
    val redes: List<Interfaz> get() = porRed.map { it.first }

    /** A todos: la difusión general y la de cada red, y la multidifusión. Devuelve cuántos salieron. */
    fun difundir(paquete: ByteArray): Int {
        var hechos = 0
        for ((red, s) in porRed) {
            for (destino in listOf(TODOS, red.difusion(), Ipv4.de(Baliza.GRUPO_MULTICAST))) {
                if (enviar(s, paquete, destino)) hechos++
            }
        }
        if (porRed.isEmpty() && enviar(suelto, paquete, TODOS)) hechos++
        return hechos
    }

    /** Directa a cada una de [ips], por el socket de su red si es de alguna. */
    fun a(paquete: ByteArray, ips: List<Long>): Int = ips.count { ip ->
        val s = porRed.firstOrNull { it.first.contiene(ip) }?.second ?: suelto
        enviar(s, paquete, ip)
    }

    private fun enviar(s: DatagramSocket, p: ByteArray, ip: Long): Boolean = try {
        s.send(DatagramPacket(p, p.size, InetAddress.getByAddress(Ipv4.bytes(ip)), puerto))
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun cerrar() {
        porRed.forEach { runCatching { it.second.close() } }
        runCatching { suelto.close() }
    }

    companion object {
        private const val TODOS = 0xffff_ffffL

        /** Con [puerto] = [Baliza.PUERTO] (otro en las pruebas). Una red que no deja abrir su socket se salta. */
        fun abrir(puerto: Int, redes: List<Interfaz>): EmisorDeBalizas {
            val suelto = DatagramSocket().apply { broadcast = true }
            val porRed = redes.mapNotNull { r ->
                runCatching {
                    val ip = InetAddress.getByAddress(Ipv4.bytes(r.ip))
                    MulticastSocket(InetSocketAddress(ip, 0)).apply {
                        broadcast = true
                        timeToLive = 1
                        NetworkInterface.getByInetAddress(ip)?.let { runCatching { networkInterface = it } }
                    }
                }.getOrNull()?.let { r to (it as DatagramSocket) }
            }
            return EmisorDeBalizas(puerto, porRed, suelto)
        }
    }
}

/**
 * **Quien oye las balizas** (`Oyente` del PC): atado a `0.0.0.0:puerto` con `reuseAddress` (la
 * difusión solo llega así) y apuntado a la multidifusión en cada red. Sin un
 * `WifiManager.MulticastLock` muchos teléfonos tiran la difusión y la multidifusión con la pantalla
 * apagada: lo coge [BalizaDelMovil] mientras oye.
 */
class OyenteDeBalizas private constructor(private val socket: MulticastSocket) {
    val puerto: Int get() = socket.localPort

    /** Un datagrama de [buf] y quién lo mandó (su IPv4), o null si no llegó nada en el rato. */
    fun oir(buf: ByteArray): Pair<Int, Long>? {
        val d = DatagramPacket(buf, buf.size)
        try {
            socket.receive(d)
        } catch (_: SocketTimeoutException) {
            return null
        }
        val de = d.address as? Inet4Address ?: return null
        return d.length to Ipv4.de(de.address)
    }

    fun cerrar() { runCatching { socket.close() } }

    companion object {
        fun abrir(puerto: Int, redes: List<Interfaz>, esperaMs: Int = 500): OyenteDeBalizas {
            val s = MulticastSocket(null as SocketAddress?)
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(puerto))
                s.soTimeout = esperaMs
                val grupo = InetSocketAddress(InetAddress.getByName(Baliza.GRUPO_MULTICAST), 0)
                var unido = false
                for (r in redes) {
                    val ni = runCatching { NetworkInterface.getByInetAddress(InetAddress.getByAddress(Ipv4.bytes(r.ip))) }.getOrNull() ?: continue
                    unido = runCatching { s.joinGroup(grupo, ni) }.isSuccess || unido
                }
                if (!unido) runCatching { s.joinGroup(grupo, null) }
            } catch (e: IOException) {
                s.close()
                throw e
            }
            return OyenteDeBalizas(s)
        }
    }
}

/**
 * **Las redes IPv4 de este aparato** (lo de `pixpin-shell::red::interfaces_ipv4` del PC): la
 * dirección y el prefijo de cada interfaz encendida, la Wi-Fi primero. Sin loopback, sin las
 * `169.254.x.x` y sin los datos móviles (`rmnet`, `ccmni`…: ahí no hay nadie del grupo y barrer
 * sería mandar a la red del operador). Las VPN entran: puede que el PC esté al otro lado.
 */
object RedesDelAparato {
    private val DE_DATOS = listOf("rmnet", "ccmni", "pdp", "clat", "v4-", "dummy", "ifb", "seth", "rev_rmnet")

    fun esDeDatos(nombre: String): Boolean = DE_DATOS.any { nombre.startsWith(it) }

    /** El orden: Wi-Fi, cable, punto de acceso, lo demás (VPN). */
    fun orden(nombre: String): Int = when {
        nombre.startsWith("wlan") -> 0
        nombre.startsWith("eth") -> 1
        nombre.startsWith("ap") || nombre.startsWith("swlan") || nombre.startsWith("softap") -> 2
        else -> 3
    }

    fun leer(): List<Interfaz> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && !esDeDatos(it.name) }
            .sortedBy { orden(it.name) }
            .flatMap { ni ->
                ni.interfaceAddresses.mapNotNull { a ->
                    val ip = a.address as? Inet4Address ?: return@mapNotNull null
                    if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress) null
                    else Interfaz(Ipv4.de(ip.address), a.networkPrefixLength.toInt().coerceIn(0, 32))
                }
            }
    }.getOrDefault(emptyList())
}
