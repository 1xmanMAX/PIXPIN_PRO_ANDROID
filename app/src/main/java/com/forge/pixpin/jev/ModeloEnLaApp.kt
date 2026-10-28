package com.forge.pixpin.jev

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Nube
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.Respuesta
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.ia.Trozo

/**
 * **Qué modelo contesta cada pedido al asistente** (28-oct-2026, `apps/pixpin/src/jev/modelo.rs` del PC,
 * `e51da395`): antes del turno, Jev dice lo difícil que es, si es una orden simple, si pide razonar y
 * si un error importaría ([Modelo]); la tabla de allí elige el modelo rápido (Claude Haiku, DeepSeek
 * Flash: el de siempre) o el fuerte del mismo proveedor y con la misma clave (Claude Sonnet, DeepSeek
 * V4 Pro), con el mismo «pensar».
 *
 * - Las órdenes del camino rápido ni preguntan: se hacen en el teléfono, como siempre.
 * - La pregunta se hace **en el hilo del turno**, al pedirle la primera respuesta ([MotorSegunJev]): el
 *   chat no espera nada al enviar.
 * - La pastilla de encima de la caja del chat «Asistente» enseña lo elegido para el último pedido
 *   («Claude Sonnet · Jev») durante 15 minutos ([rotulo]).
 * - **Sin clave de TypeSafe, con la función apagada, o si Jev falla o tarda, el de siempre.** Lo elegido
 *   no viaja.
 */
object ModeloEnLaApp {

    /** El modelo fuerte de cada proveedor y cómo se llama en la pastilla. */
    fun fuerteDe(p: Nube.Proveedor): Pair<String, String> = when (p) {
        Nube.Proveedor.CLAUDE -> "claude-sonnet-5-5" to "Claude Sonnet"
        Nube.Proveedor.DEEPSEEK -> "deepseek-v4-pro" to "DeepSeek V4 Pro"
    }

    /** Cómo se llama en la pastilla el de siempre. */
    fun rapidoDe(p: Nube.Proveedor): String = when (p) {
        Nube.Proveedor.CLAUDE -> "Claude Haiku"
        Nube.Proveedor.DEEPSEEK -> "DeepSeek Flash"
    }

    /** Lo que dura en la pastilla lo elegido para el último pedido. */
    const val EN_LA_PASTILLA_MS = 15 * 60_000L

    /** Lo elegido en el último pedido de cada chat (`""` el general): cuándo y el rótulo. Lo lee Compose. */
    private val elegido = mutableStateMapOf<String, Pair<Long, String>>()

    private fun apuntar(chat: String?, rotulo: String) {
        val ahora = System.currentTimeMillis()
        android.os.Handler(android.os.Looper.getMainLooper()).post { elegido[chat.orEmpty()] = ahora to rotulo }
    }

    /** **El rótulo de la pastilla**: lo que eligió Jev para el último pedido de este chat, si fue hace poco. */
    fun rotulo(chat: String?, ahora: Long = System.currentTimeMillis()): String? {
        val (cuando, r) = elegido[chat.orEmpty()] ?: return null
        return if (ahora - cuando < EN_LA_PASTILLA_MS) "$r · Jev" else null
    }

    /** Cuándo se eligió (para saber cuándo esconder la pastilla). */
    fun cuandoSeEligio(chat: String?): Long? = elegido[chat.orEmpty()]?.first

    /** Los últimos mensajes de la conversación, con quién los dijo (`recientes` del PC). */
    fun recientes(conversacion: List<Pair<Boolean, String>>): List<Pair<String, String>> =
        conversacion.takeLast(Modelo.RECIENTES).map { (delUsuario, t) -> (if (delUsuario) "usuario" else "asistente") to t }

    /**
     * **Un motor que elige su modelo** al pedirle la primera respuesta. Los dos son del mismo
     * proveedor: ven lo mismo y aguantan lo mismo. [elegir] da la ruta, o lanza (entonces, el rápido).
     */
    class MotorSegunJev(
        private val rapido: com.forge.pixpin.ia.Motor,
        private val fuerte: com.forge.pixpin.ia.Motor,
        private val rotulos: Pair<String, String>,
        private val elegir: () -> Modelo.Ruta,
        private val alElegir: (String) -> Unit = {}
    ) : com.forge.pixpin.ia.Motor {
        @Volatile private var motor: com.forge.pixpin.ia.Motor? = null

        @Synchronized
        fun elegido(): com.forge.pixpin.ia.Motor {
            motor?.let { return it }
            val m = try {
                when (elegir()) {
                    Modelo.Ruta.FUERTE -> { alElegir(rotulos.second); fuerte }
                    Modelo.Ruta.RAPIDO -> { alElegir(rotulos.first); rapido }
                }
            } catch (e: Exception) {
                // Un fallo no elige nada: el de siempre.
                runCatching { android.util.Log.w("PixPinJev", "sin modelo elegido, el de siempre: ${e.message}") }
                rapido
            }
            motor = m
            return m
        }

        override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta = elegido().completar(peticion, alTrozo)

        override fun soltar() {
            rapido.soltar(); fuerte.soltar()
        }
    }

    /**
     * **El motor de este pedido**: [motor] (el de los ajustes) o, con Jev, uno que elige entre ese y el
     * fuerte. Solo con la nube ([Nube.Motor]) y si el pedido no es una orden del camino rápido
     * ([esOrden]); [ligero], sin pensar, como el de siempre. Los mensajes de antes se leen al elegir, en
     * el hilo del turno.
     */
    fun segunJev(context: Context, motor: com.forge.pixpin.ia.Motor, pregunta: Mensaje, esOrden: Boolean, ligero: Boolean): com.forge.pixpin.ia.Motor {
        if (esOrden || motor !is Nube.Motor) return motor
        val app = context.applicationContext
        val cliente = Jev.para(app, Jev.Funcion.MODELO) ?: return motor
        val p = motor.proveedor
        val clave = com.forge.pixpin.asistente.ClavesDeLaNube.leer(app, p) ?: return motor
        val (modelo, nombre) = fuerteDe(p)
        val fuerte = Nube.Motor(p, clave, ligero = ligero, modelo = modelo)
        return MotorSegunJev(motor, fuerte, rapidoDe(p) to nombre, elegir = {
            val anteriores = conversacion(runCatching { MensajesStore(app).leer() }.getOrDefault(emptyList()), pregunta)
            val texto = com.forge.pixpin.asistente.ChatDelAsistente.sinMencion(pregunta.texto)
            Modelo.elegir(cliente, Modelo.Contexto(texto, recientes(anteriores))).first
        }, alElegir = { apuntar(pregunta.proyecto, it) })
    }

    /** La conversación hasta el pedido (sin él): del mismo chat, con texto, `true` si la dijo el usuario. */
    fun conversacion(mensajes: List<Mensaje>, pregunta: Mensaje): List<Pair<Boolean, String>> =
        mensajes.filter { it.proyecto == pregunta.proyecto && !it.enBuzon && it.id != pregunta.id && it.cuando <= pregunta.cuando && it.texto.isNotBlank() }
            .sortedBy { it.cuando }
            .map { (it.aparato != com.forge.pixpin.asistente.ChatDelAsistente.APARATO) to it.texto.trim() }
}
