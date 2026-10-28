package com.forge.pixpin.jev

import android.content.Context
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.mini.TareaInteligente
import com.forge.pixpin.mini.TodasLasTareas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **La prioridad de cada tarea según su contexto, en Tareas** (28-oct-2026, `apps/pixpin/src/jev/urgencia.rs`
 * del PC, `fa4ca12b`): por cada tarea pendiente, Jev juzga lo que pasa si se hace tarde, si otro la
 * espera, si el plazo lo puso otro y si en su chat la piden ya ([Urgencia]); cuánto falta para su hora
 * (`⏰`) lo cuenta el código, y la tabla de allí da urgente, alta, normal o baja. Lo que el usuario
 * marcó (⏫, 🔼) manda.
 *
 * Va en segundo plano: al leer las listas ([aplicar]) se pone lo ya sabido y se pregunta por lo que
 * falta en un hilo (hasta [POR_TANDA] tareas, cuatro a la vez); al acabar sube [novedades] y Tareas
 * relee. **Lo sabido se queda en memoria mientras la app está abierta: nada va al disco ni viaja** (las
 * prioridades se calculan en cada aparato). Tras un fallo, esa tarea no se vuelve a preguntar en 10 min.
 *
 * Sin clave o con la función apagada, `prioridad` se queda en null y Tareas ordena y enseña todo como
 * siempre.
 */
object UrgenciaEnLaApp {

    private sealed interface Sabido {
        class Dicho(val s: Urgencia.Senales) : Sabido
        class Fallo(val cuando: Long) : Sabido
    }

    private val sabido = HashMap<String, Sabido>()
    private val preguntando = AtomicBoolean(false)
    private val _novedades = MutableStateFlow(0)

    /** Sube cuando acaba una tanda: Tareas relee. */
    val novedades: StateFlow<Int> get() = _novedades

    private const val TRAS_UN_FALLO_MS = 10 * 60_000L
    private const val A_LA_VEZ = 4
    const val POR_TANDA = 60

    /** La clave de una tarea: su texto (sin la fecha de creada), su lista y su chat. */
    fun claveDe(l: TodasLasTareas.Lista, texto: String) = "${l.proyecto.orEmpty()}\u001f${l.titulo}\u001f$texto"

    /** La marca del usuario. */
    fun marcaDe(u: TareaInteligente.Urgencia): Urgencia.Marca = when (u) {
        TareaInteligente.Urgencia.MUY_URGENTE -> Urgencia.Marca.MUY_URGENTE
        TareaInteligente.Urgencia.URGENTE -> Urgencia.Marca.URGENTE
        TareaInteligente.Urgencia.NORMAL -> Urgencia.Marca.NINGUNA
    }

    /** Lo que se le manda de la tarea: su título sin marcas y, si tiene, su nota. */
    fun textoParaJev(f: TareaInteligente.Frase): String {
        val n = f.nota?.trim().orEmpty()
        return if (n.isNotEmpty()) "${f.titulo.trim()} ($n)" else f.titulo.trim()
    }

    /** Los días que faltan para su hora (negativo, ya pasó), contados por el código. */
    fun diasHasta(f: TareaInteligente.Frase, hoy: Long): Long? = f.hora?.let { it.dia - hoy }

    /**
     * **Pone la prioridad de cada tarea pendiente** con lo ya sabido, y lanza en su hilo las preguntas
     * de lo que falta. Sin Jev, las listas tal cual. Lee el disco la primera vez: fuera del hilo principal.
     */
    fun aplicar(context: Context, listas: List<TodasLasTareas.Lista>): List<TodasLasTareas.Lista> {
        val cliente = Jev.para(context, Jev.Funcion.URGENCIA) ?: return listas
        val hoy = Momento.ahora().dia
        val ahora = System.currentTimeMillis()
        val faltan = LinkedHashMap<String, Pair<String?, Urgencia.Contexto>>()
        val salida = listas.map { l ->
            l.copy(filas = l.filas.map { f ->
                if (f.hecha) return@map f
                val m = f.marcas
                val texto = textoParaJev(m)
                if (texto.isEmpty()) return@map f
                val clave = claveDe(l, texto)
                when (val s = synchronized(sabido) { sabido[clave] }) {
                    is Sabido.Dicho -> f.copy(prioridad = Urgencia.decidir(marcaDe(m.urgencia), s.s, diasHasta(m, hoy)))
                    is Sabido.Fallo -> {
                        if (ahora - s.cuando >= TRAS_UN_FALLO_MS) faltan[clave] = l.proyecto to Urgencia.Contexto(texto, l.titulo, l.chat)
                        f
                    }
                    null -> { faltan[clave] = l.proyecto to Urgencia.Contexto(texto, l.titulo, l.chat); f }
                }
            })
        }
        if (faltan.isNotEmpty() && preguntando.compareAndSet(false, true)) {
            val tanda = faltan.entries.take(POR_TANDA).map { it.key to it.value }
            val app = context.applicationContext
            Thread({
                try { preguntar(app, cliente, tanda) } finally {
                    preguntando.set(false)
                    _novedades.value++
                }
            }, "pixpin-jev-urgencia").apply { isDaemon = true }.start()
        }
        return salida
    }

    /** Lo de [aplicar], en el hilo: los mensajes recientes de cada chat y las preguntas. */
    private fun preguntar(app: Context, cliente: Cliente, tanda: List<Pair<String, Pair<String?, Urgencia.Contexto>>>) {
        val mensajes = runCatching { MensajesStore(app).leer() }.getOrDefault(emptyList())
        val porChat = HashMap<String?, List<String>>()
        val contextos = tanda.map { (_, pc) ->
            val (proyecto, c) = pc
            c.copy(recientes = porChat.getOrPut(proyecto) { recientesDelChat(mensajes, proyecto) })
        }
        val empezo = System.currentTimeMillis()
        val respuestas = Urgencia.preguntarVarias(cliente, contextos, A_LA_VEZ)
        var fallos = 0
        synchronized(sabido) {
            for ((i, r) in respuestas.withIndex()) {
                val clave = tanda[i].first
                r.fold({ sabido[clave] = Sabido.Dicho(it) }, { e ->
                    if (fallos++ == 0) runCatching { android.util.Log.w("PixPinJev", "sin prioridad para una tarea: ${e.message}") }
                    sabido[clave] = Sabido.Fallo(System.currentTimeMillis())
                })
            }
        }
        runCatching { android.util.Log.i("PixPinJev", "prioridades: ${contextos.size} tareas, $fallos fallos, ${System.currentTimeMillis() - empezo} ms") }
    }

    /**
     * Los últimos mensajes escritos del chat (notas con texto, no las listas de tareas ni archivos), del
     * más viejo al más nuevo (`recientes_del_chat` del PC).
     */
    fun recientesDelChat(mensajes: List<Mensaje>, proyecto: String?): List<String> =
        mensajes.asSequence()
            .filter { it.proyecto == proyecto && it.ruta == null && !it.enBuzon && !TodasLasTareas.esLista(it) }
            .sortedBy { it.cuando }
            .map { it.texto.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(Urgencia.RECIENTES)
}
