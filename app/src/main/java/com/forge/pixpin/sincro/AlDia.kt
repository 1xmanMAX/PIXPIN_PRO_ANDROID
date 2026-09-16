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

    /** Los aparatos del grupo a los que se puede llamar ahora mismo, por su última dirección. */
    fun aparatos(context: Context): List<Estado> {
        val disco = Red.disco(context)
        val identidad = disco.identidad.leer()
        if (!identidad.enGrupo) return emptyList()
        val dirs = disco.direcciones()
        return identidad.miembros.filter { it.id != identidad.yo.id }.mapNotNull { m ->
            val (host, puerto) = dirs[m.id] ?: return@mapNotNull null
            Estado(m.id, m.nombre, host, puerto)
        }
    }

    /**
     * Pregunta a cada aparato qué versión tiene de [rel], **sin mover nada**.
     *
     * Un aparato apagado o fuera de la Wi-Fi sale con su [Estado.error] puesto, no desaparece: que
     * no conteste es justo lo que el usuario necesita ver antes de decidir.
     */
    fun mirar(context: Context, chat: String, rel: String, avance: (Estado) -> Unit = {}): List<Estado> {
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
