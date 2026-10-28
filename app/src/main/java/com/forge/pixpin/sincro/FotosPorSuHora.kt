package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.PorSuHora
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Las fotos por su hora, en este teléfono** (28-oct-2026, `apps/pixpin/src/sincronizar/por_su_hora.rs`
 * del PC). Las reglas están en [PorSuHora]; aquí, lo del disco:
 *
 * - **Los desfases medidos** ([medido]): en cada saludo de sincronizar (con el grupo y con las
 *   personas de un chat compartido, [Sesion]), en cada pregunta o aviso de la cámara y en cada
 *   `camara_remota` de la grabación del teléfono, lo que va adelantado el reloj de ese aparato en
 *   `filesDir/sincro/desfases.json`, por su código. **No viaja.**
 * - **El repaso** ([repasar], [repasarEnFondo]): tras cada sincronización (dirigiendo o respondiendo,
 *   con el grupo o con personas, normal o por diario), al recibir por «Enviar por Wi‑Fi» y al
 *   guardarse una grabación del teléfono. Solo mira los chats con una grabación con `reloj` de este
 *   aparato, así que con nada grabado aquí no cuesta nada. Es idempotente.
 * - **A mano** ([aMano]) e **invitar** ([invitarATodos]).
 */
object FotosPorSuHora {

    private val CERROJO = Any()
    private val corriendo = AtomicBoolean(false)
    private val pendientes = java.util.concurrent.ConcurrentHashMap<String, Pair<Disco, (Map<String?, Int>) -> Unit>>()

    fun fichero(filesDir: File) = File(filesDir, "sincro/desfases.json")

    /** Los desfases medidos hasta ahora. */
    fun desfases(filesDir: File): PorSuHora.Desfases = synchronized(CERROJO) { PorSuHora.Desfases.leer(fichero(filesDir)) }

    /** **Apunta lo que va adelantado el reloj del aparato [id]** (su id, no su código), medido en [ahora]. */
    fun medido(filesDir: File, id: String, desfase: Long, ahora: Long = System.currentTimeMillis()) {
        if (id.isBlank()) return
        runCatching {
            synchronized(CERROJO) {
                val f = fichero(filesDir)
                PorSuHora.Desfases.leer(f).medir(Aparato(id, "").codigo, ahora, desfase).guardar(f)
            }
        }
    }

    /** El código de este aparato y los de todos los míos (los del grupo). */
    fun yoYMios(disco: Disco): Pair<String, Set<String>> {
        val id = disco.identidad.leer()
        val yo = id.yo.codigo
        return yo to (id.miembros.map { it.codigo } + yo).toSet()
    }

    /**
     * **Repasa todos los chats ya**, en el hilo de quien llama: lo que haya llegado con hora entra en
     * su grabación. Devuelve los chats donde entró algo, con cuántas.
     */
    fun repasar(disco: Disco): Map<String?, Int> {
        val chat = File(disco.filesDir, "guardados.jsonl")
        // Lo que no lleva la palabra no tiene ninguna grabación con reloj.
        if (!chat.isFile || runCatching { !chat.readText().contains("\"reloj\"") }.getOrDefault(true)) return emptyMap()
        val (yo, mios) = yoYMios(disco)
        val desfases = desfases(disco.filesDir)
        var hechas: Map<String?, Int> = emptyMap()
        disco.cambiarMensajes { lista ->
            val todos = lista.toMutableList()
            hechas = PorSuHora.repasarTodos(todos, yo, mios, desfases)
            if (hechas.isEmpty()) null else todos
        }
        return hechas
    }

    /**
     * **Repasa en otro hilo**: se puede llamar sin miedo tras cada cosa que trae mensajes. Uno a la
     * vez y por carpeta: si se pide mientras corre, se repite al acabar.
     */
    fun repasarEnFondo(disco: Disco, alAcabar: (Map<String?, Int>) -> Unit = {}) {
        pendientes[disco.filesDir.absolutePath] = disco to alAcabar
        if (corriendo.getAndSet(true)) return
        val hilo = Thread({
            try {
                while (true) {
                    val k = pendientes.keys.firstOrNull() ?: break
                    val (d, f) = pendientes.remove(k) ?: continue
                    val h = runCatching { repasar(d) }.getOrDefault(emptyMap())
                    if (h.isNotEmpty()) runCatching { f(h) }
                }
            } finally {
                corriendo.set(false)
                // Lo pedido justo al acabar: otra vuelta.
                pendientes.values.firstOrNull()?.let { (d, f) -> repasarEnFondo(d, f) }
            }
        }, "pixpin-fotos-por-su-hora")
        runCatching { hilo.start() }.onFailure { corriendo.set(false) }
    }

    /**
     * **«Adjuntar a la grabación…»**: la foto [foto] del chat [chat] en la grabación de la nota [voz]
     * (en su minuto si su hora cae dentro, si no al final). Devuelve el minuto, o null si no se pudo.
     */
    fun aMano(disco: Disco, chat: String?, foto: String, voz: String): Long? {
        val (_, mios) = yoYMios(disco)
        val desfases = desfases(disco.filesDir)
        var ms: Long? = null
        disco.cambiarMensajes { lista ->
            val todos = lista.toMutableList()
            ms = PorSuHora.aMano(todos, chat, foto, voz, null, mios, desfases)
            todos
        }
        return ms
    }

    /**
     * **«Adjuntar a la grabación»**: la foto en la grabación más a mano del chat (la que tiene su hora
     * dentro o, si ninguna, la última). El nombre de la grabación y el minuto, o null si no hay.
     */
    fun aManoLaMejor(disco: Disco, chat: String?, foto: String): Pair<String, Long>? {
        val (voz, nombre) = PorSuHora.grabacionesPara(disco.leerMensajes(), chat, foto, desfases(disco.filesDir)).firstOrNull() ?: return null
        return aMano(disco, chat, foto, voz)?.let { nombre to it }
    }

    /** **Invita (o deja de invitar) a todos los del chat** a poner fotos en la grabación de la nota [voz]. */
    fun invitarATodos(disco: Disco, chat: String?, voz: String, si: Boolean): Boolean {
        val hecho = disco.cambiarMensajes { lista ->
            val i = lista.indexOfFirst { it.proyecto == chat && it.id == voz }
            val nueva = if (i >= 0) PorSuHora.invitar(lista[i], PorSuHora.TODOS, si) else null
            nueva?.let { lista.toMutableList().apply { set(i, it) } }
        }
        if (hecho && si) repasarEnFondo(disco)
        return hecho
    }

    /** Si la nota deja aportar fotos a todos los del chat. */
    fun abiertaATodos(m: Mensaje): Boolean = PorSuHora.abiertaATodos(m)
}
