package com.forge.pixpin.sincro

import android.content.Context
import java.net.InetAddress

/**
 * **Ponerse al día con un solo lienzo, antes de escribir en él.**
 *
 * Idea del usuario (16-sep-2026): el enredo de escrituras superpuestas se arregla mucho mejor
 * **antes** que después —«traer la última versión de forma rápida y así editar sobre esa»—. Una
 * sincronización entera compara el chat, el proyecto y todos sus archivos; esto pregunta por **un
 * archivo** y lo junta, que con los parches ([Fusion.diferencia]) es cuestión de un momento aunque
 * el lienzo sea grande.
 *
 * Va en dos tiempos a propósito, porque el usuario pidió **ver antes de traer**: primero [mirar]
 * pregunta a cada aparato qué versión tiene de ese lienzo y cuándo la tocó —de ahí sale quién tiene
 * la más reciente—, y solo después [traer] la junta. Son dos conexiones cortas en vez de una larga,
 * y así ningún aparato se queda ocupado mientras se mira la lista (ver [Protocolo.ocupar]).
 *
 * Lo de siempre: **no manda nadie**, se junta ([Fusion]); y antes de tocar nada se hace copia de
 * seguridad ([Copias]), que es lo que deja revertir si algo sale mal.
 */
object AlDia {

    /** Lo que tiene un aparato de ese lienzo. */
    data class Estado(
        val id: String,
        val nombre: String,
        val host: String,
        val puerto: Int,
        /** Cuándo se tocó allí. 0 si no lo tiene. */
        val tocado: Long = 0,
        /** El resumen de su versión; nulo si no lo tiene o no se pudo preguntar. */
        val resumen: String? = null,
        val error: String? = null
    ) {
        val loTiene: Boolean get() = resumen != null
    }

    /** Lo que trajo una pasada de [traer]. */
    data class Resultado(
        val cambiados: Int,
        val deQuien: List<String>,
        val bytes: Long,
        val fallaron: Map<String, String>
    )

    /**
     * **Los aparatos a los que se puede llamar ahora mismo.**
     *
     * Dos fuentes, y hacen falta las dos —con solo la primera, el usuario vio el 16-sep-2026 que
     * «se queda buscando y no localiza a los que sí están conectados»—:
     *
     * 1. **Las direcciones recordadas** de la última vez. Son instantáneas, pero la IP de un
     *    teléfono cambia al reconectarse a la Wi-Fi, así que hay que comprobarlas: un `PING` de
     *    poco más de un segundo ([Red.sondear]) en vez de esperar a que una conexión entera se
     *    agote.
     * 2. **El anuncio de la red** (mDNS), que es lo que hace la pantalla de Sincronizar y aquí no
     *    se hacía: sin él, un aparato con dirección nueva **no aparecía nunca**. Lo que se
     *    encuentra se apunta, así que la próxima vez ya está en la primera fuente.
     */
    suspend fun aparatos(context: Context): List<Estado> {
        val disco = Red.disco(context)
        val identidad = disco.identidad.leer()
        if (!identidad.enGrupo) return emptyList()
        val otros = identidad.miembros.filter { it.id != identidad.yo.id }
        if (otros.isEmpty()) return emptyList()
        val dirs = disco.direcciones()
        val porId = LinkedHashMap<String, Estado>()
        for (m in otros) {
            val (host, puerto) = dirs[m.id] ?: continue
            if (Red.sondear(host, puerto)) porId[m.id] = Estado(m.id, m.nombre, host, puerto)
        }
        // Los que no contestaron en su dirección de siempre: se buscan por la red.
        if (porId.size < otros.size) {
            for (v in buscarPorLaRed(context, identidad)) {
                val host = v.host.hostAddress ?: continue
                val nombre = otros.firstOrNull { it.id == v.id }?.nombre ?: v.nombre
                porId[v.id.ifBlank { nombre }] = Estado(v.id, nombre, host, v.puerto)
                if (v.id.isNotBlank()) disco.apuntarDireccion(v.id, host, v.puerto)
            }
        }
        return porId.values.toList()
    }

    /** El anuncio de la red, unos segundos. Hay que pedirlo desde el hilo de la pantalla. */
    private suspend fun buscarPorLaRed(context: Context, identidad: Identidad): List<Red.Vecino> {
        val codigo = identidad.codigo ?: return emptyList()
        val etiqueta = Grupo.etiqueta(Grupo.clave(codigo))
        val red = Presencia.red
        val encontrados = java.util.concurrent.ConcurrentHashMap<String, Red.Vecino>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            red.buscar(Red.TIPO, { it["g"] == etiqueta && it["id"] != identidad.yo.id }) { lista ->
                for (v in lista) encontrados[v.id.ifBlank { v.nombre }] = v
            }
        }
        // Lo que tarda un anuncio en llegar: unos segundos. Se corta en cuanto hay alguno y ya
        // no cambia, para no hacer esperar de más.
        var iguales = 0
        var antes = 0
        var vueltas = 0
        while (vueltas++ < 16) {
            kotlinx.coroutines.delay(400)
            if (encontrados.size == antes) iguales++ else { iguales = 0; antes = encontrados.size }
            if (antes > 0 && iguales >= 3) break
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { red.pararBusqueda() }
        return encontrados.values.toList()
    }

    /**
     * Pregunta a cada aparato qué versión tiene de [rel], **sin mover nada**.
     *
     * Un aparato apagado o fuera de la Wi-Fi sale con su [Estado.error] puesto, no desaparece: que
     * no conteste es justo lo que el usuario necesita ver antes de decidir.
     */
    suspend fun mirar(context: Context, chat: String, rel: String, avance: (Estado) -> Unit = {}): List<Estado> {
        val disco = Red.disco(context)
        return aparatos(context).map { a ->
            val puesto = runCatching {
                hablar(disco, a) { sesion ->
                    val info = sesion.infoDe(chat, rel)
                    a.copy(tocado = info?.tocado ?: 0L, resumen = info?.resumen)
                }
            }.getOrElse { a.copy(error = explicar(it)) }
            avance(puesto)
            puesto
        }
    }

    /**
     * Junta ese lienzo con los aparatos dados, uno detrás de otro, y deja lo acordado apuntado
     * para que la sincronización entera de después no tenga que volver sobre él.
     *
     * Devuelve qué pasó. Si uno falla se sigue con los demás: quedarse a medias no rompe nada.
     */
    fun traer(
        context: Context,
        chat: String,
        rel: String,
        destinos: List<Estado>,
        avance: (String) -> Unit = {}
    ): Resultado {
        val disco = Red.disco(context)
        val hecho = Sesion.Hecho()
        val deQuien = ArrayList<String>()
        val fallaron = LinkedHashMap<String, String>()
        var bytes = 0L
        var antes = resumenDeAqui(disco, chat, rel)
        // Antes de tocar nada, cómo estaba: es lo que hace posible revertir. Ver [Copias].
        Copias(disco).hacer(chat, "Antes de ponerme al día con este lienzo")
        for (a in destinos) {
            avance(a.nombre)
            try {
                hablar(disco, a) { sesion ->
                    val prep = sesion.soloEsteArchivo(chat, rel)
                    sesion.aplicarArchivos(prep, hecho)
                    sesion.apuntarArchivo(chat, rel)
                    bytes += sesion.enviados + sesion.recibidos
                }
                val ahora = resumenDeAqui(disco, chat, rel)
                if (ahora != antes) { deQuien += a.nombre; antes = ahora }
            } catch (e: Throwable) {
                fallaron[a.nombre] = explicar(e)
            }
        }
        return Resultado(hecho.archivos, deQuien, bytes, fallaron)
    }

    private fun resumenDeAqui(disco: Disco, chat: String, rel: String): String? =
        disco.archivos(chat, HashMap()).firstOrNull { it.ruta == rel }?.resumen

    /** Qué versión de ese lienzo hay en este aparato, para saber cuáles son distintas. */
    fun resumenDeEste(context: Context, chat: String, rel: String): String? =
        resumenDeAqui(Red.disco(context), chat, rel)

    /** Abre una conexión corta con un aparato, hace lo suyo y se despide pase lo que pase. */
    private fun <T> hablar(disco: Disco, a: Estado, hacer: (Sesion) -> T): T =
        Red.conectar(InetAddress.getByName(a.host), a.puerto).use { socket ->
            val sesion = Sesion.conectar(socket.getInputStream(), socket.getOutputStream(), disco)
            try {
                val salida = hacer(sesion)
                sesion.adios()
                salida
            } finally {
                sesion.soltar()
            }
        }

    private fun explicar(e: Throwable): String = when (e) {
        is java.net.ConnectException, is java.net.SocketTimeoutException, is java.net.NoRouteToHostException ->
            "No contesta: ¿está encendido y en esta Wi-Fi?"
        else -> e.message ?: e::class.java.simpleName
    }
}
