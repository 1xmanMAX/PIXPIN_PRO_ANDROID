package com.forge.pixpin.sincro

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * **Qué red tiene el teléfono, avisado por Android** (6-nov-2026, el usuario: «que la sincronización en segundo
 * plano sea inteligente: que no esté buscando a cada rato sino que empiece cuando se conecta a una red, y con datos
 * móviles solo los chats de la nube; los demás no, que son locales»).
 *
 * - [Tipo.WIFI]: una red de casa o del trabajo (Wi-Fi o cable, sin medir): ahí están el PC y los demás aparatos.
 * - [Tipo.DATOS]: datos móviles (o una red que se paga por lo que se gasta): solo la nube.
 * - [Tipo.NINGUNA]: nada que hacer; se espera a que vuelva ([esperarRed]) en vez de reintentar.
 */
object RedDelTelefono {
    enum class Tipo { NINGUNA, DATOS, WIFI }

    private val _tipo = MutableStateFlow(Tipo.WIFI)
    val tipo: StateFlow<Tipo> get() = _tipo
    @Volatile private var instalada = false

    /** La regla, sin Android, para probarla. */
    fun tipoDe(conInternet: Boolean, wifiOCable: Boolean, sinMedir: Boolean): Tipo = when {
        !conInternet -> Tipo.NINGUNA
        wifiOCable && sinMedir -> Tipo.WIFI
        else -> Tipo.DATOS
    }

    private fun tipoDe(c: NetworkCapabilities?): Tipo = if (c == null) Tipo.NINGUNA else tipoDe(
        conInternet = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        wifiOCable = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        sinMedir = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    )

    @Synchronized
    fun instalar(contexto: Context) {
        if (instalada) return
        instalada = true
        val cm = contexto.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { _tipo.value = tipoDe(cm.getNetworkCapabilities(cm.activeNetwork)) }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _tipo.value = tipoDe(caps) }
                override fun onLost(network: Network) { _tipo.value = tipoDe(cm.getNetworkCapabilities(cm.activeNetwork)) }
            })
        }
    }

    val hayRed: Boolean get() = _tipo.value != Tipo.NINGUNA
    val enWifi: Boolean get() = _tipo.value == Tipo.WIFI

    /** Espera, sin gastar nada, a que haya red. */
    suspend fun esperarRed() { if (!hayRed) _tipo.first { it != Tipo.NINGUNA } }
}
