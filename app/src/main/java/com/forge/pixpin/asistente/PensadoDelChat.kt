package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.ia.Contexto
import com.forge.pixpin.ia.HojaTrabajo
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Pensar
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import com.forge.pixpin.sincro.Codigos
import java.util.concurrent.ConcurrentHashMap

/**
 * **Lo que pensó cada respuesta y la hoja de trabajo del chat** (`apps/pixpin/src/asistente/
 * pensado.rs` del PC): el registro va en `pins/draw/anot-<uid>.asistente.json` (§3.2), se lee para
 * la sección plegable de la burbuja y para seguir la conversación (la hoja del chat es la de la
 * **última** respuesta que la tenga). Uno roto o de otra versión se ignora y se mira el anterior.
 */
object PensadoDelChat {
    /** Cuántas respuestas hacia atrás se busca la hoja. */
    private const val BUSCAR_HOJA_EN = 30
    /** Lo último de la conversación que entra en el turno, y cuánto de cada mensaje. */
    private const val ULTIMOS = 12
    private const val LARGO = 1500

    /** Lo ya leído (por id de la nota); null = no tiene o está roto. Se olvida al reescribir. */
    private val cache = ConcurrentHashMap<String, Any>()
    private val NADA = Any()

    fun registroDe(c: Context, m: Mensaje): HojaTrabajo.Registro? {
        (cache[m.id])?.let { return it as? HojaTrabajo.Registro }
        val r = runCatching { HojaTrabajo.Registro.leer(AnotacionesDelAdjunto.asistente(c.filesDir, Codigos.unico(m))) }.getOrNull()
        cache[m.id] = r ?: NADA
        return r
    }

    /** Olvida lo leído (llegó un registro nuevo al sincronizar, o se reescribió). */
    fun olvidar(id: String? = null) { if (id == null) cache.clear() else cache.remove(id) }

    fun guardar(c: Context, m: Mensaje, r: HojaTrabajo.Registro) {
        runCatching { r.escribir(AnotacionesDelAdjunto.asistente(c.filesDir, Codigos.unico(m))) }
        cache[m.id] = r
    }

    /** **La hoja del chat**: la de la última respuesta que la tenga (copiada: se reescribe aparte). */
    fun hojaDelChat(c: Context, delChat: List<Mensaje>): HojaTrabajo.Hoja =
        delChat.asReversed().asSequence().filter { ChatDelAsistente.esRespuesta(it) && it.clase == Clase.NOTA }.take(BUSCAR_HOJA_EN)
            .mapNotNull { registroDe(c, it) }.firstOrNull()?.hoja?.copia() ?: HojaTrabajo.Hoja()

    /** Lo que se busca con `buscar_historial`: la conversación guardada y lo que se pensó. */
    fun fuentes(delChat: List<Mensaje>, hoja: HojaTrabajo.Hoja): List<Contexto.Fuente> =
        conversacion(delChat).map { (_, n, delUsuario, texto) -> Contexto.Fuente("#$n ${if (delUsuario) "usuario" else "asistente"}", texto) } +
            hoja.turnos.filter { it.pensado.isNotEmpty() }.map { Contexto.Fuente("T${it.n} pensado", "${it.peticion} — ${it.pensado}") }

    /** (id, número, del usuario, texto) de las notas de la conversación. */
    private fun conversacion(delChat: List<Mensaje>) = delChat
        .filter { it.clase == Clase.NOTA && it.texto.isNotBlank() && it.texto != ChatDelAsistente.PENSANDO }
        .map { m -> Quad(m.id, m.numero, !ChatDelAsistente.esRespuesta(m), if (ChatDelAsistente.esRespuesta(m)) ChatDelAsistente.limpiarRespuesta(m.texto) else ChatDelAsistente.sinMencion(m.texto)) }
        .filter { it.cuarto.isNotBlank() }

    private data class Quad(val id: String, val numero: Int, val delUsuario: Boolean, val cuarto: String)

    /**
     * **El historial del turno en el chat «Asistente»** (`preparar_con_hoja` del PC): lo último de
     * la conversación desde el último reinicio, con la hoja en el sistema; si pasa del 70 % de la
     * ventana, se reinicia con los dos últimos mensajes y la hoja lo apunta.
     */
    fun historial(
        instrucciones: String, hoja: HojaTrabajo.Hoja, anteriores: List<Mensaje>, nuevo: MensajeIa,
        ventana: Int, herramientas: List<com.forge.pixpin.ia.Herramienta>
    ): Pair<MutableList<MensajeIa>, Boolean> {
        var conv = conversacion(anteriores)
        hoja.reinicioDesde?.let { desde -> val i = conv.indexOfFirst { it.id == desde }; if (i >= 0) conv = conv.drop(i) }
        conv = conv.takeLast(ULTIMOS)
        val pares = conv.map { it.delUsuario to it.cuarto.take(LARGO) }
        val sistema = Contexto.sistemaConHoja(instrucciones, hoja, null)
        val p = Contexto.preparar(sistema, pares, nuevo, herramientas + Contexto.herramientaBuscar(), ventana, Contexto.RESERVA_RESPUESTA)
        if (p.reiniciado) {
            hoja.reinicioDesde = conv.getOrNull(p.primero)?.id
            hoja.reinicios++
        }
        // El primer turno de la conversación no empieza con una respuesta suelta.
        val h = p.historial
        while (h.size > 2 && h[1].rol == com.forge.pixpin.ia.Rol.ASISTENTE) h.removeAt(1)
        return h to p.reiniciado
    }

    /** Lo que se junta de los eventos del turno para su registro. */
    class Recogido {
        val pensamiento = StringBuilder()
        var segundos: Float? = null
        var plan: Pensar.Plan? = null
        val herramientas = mutableListOf<String>()
        var reinicio = false

        fun ver(e: com.forge.pixpin.ia.Agente.Evento) {
            when (e) {
                is com.forge.pixpin.ia.Agente.Evento.Pensando -> pensamiento.append(e.texto)
                is com.forge.pixpin.ia.Agente.Evento.PensoListo -> segundos = (segundos ?: 0f) + e.segundos
                is com.forge.pixpin.ia.Agente.Evento.Peticion -> plan = e.plan
                is com.forge.pixpin.ia.Agente.Evento.Reinicio -> reinicio = true
                is com.forge.pixpin.ia.Agente.Evento.Resultado -> if (e.ok) herramientas += e.nombre
                else -> {}
            }
        }

        /** El registro, con la hoja reescrita. */
        fun registro(hoja: HojaTrabajo.Hoja, pedido: String, respuesta: String, incompleto: Boolean, reiniciado: Boolean): HojaTrabajo.Registro {
            val p = plan ?: Pensar.Plan.porOmision(pedido)
            hoja.reescribir(p, HojaTrabajo.ResumenDelTurno(herramientas, respuesta, incompleto, pensamiento.toString()))
            return HojaTrabajo.Registro(pensamiento.toString(), segundos?.takeIf { pensamiento.isNotBlank() }, p, hoja, reiniciado || reinicio)
        }
    }
}
