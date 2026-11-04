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
 * (`⏰` o, si no tiene, la fecha del título de su lista: «HOY SÁBADO»; 2-nov-2026, `e775e9f4` del PC) lo
 * cuenta el código, y la tabla de allí da urgente, alta, normal o baja. Lo que el usuario marcó (⏫, 🔼)
 * manda.
 *
 * **Las listas de temas de una reunión** («Temas · Clase…», las que una nota de voz enlaza en
 * `temasLista`) no se preguntan: son puntos de una agenda, no tareas con plazo.
 *
 * Va en segundo plano: al leer las listas ([aplicar]) se pone lo ya sabido y se pregunta por lo que
 * falta en un hilo (hasta [POR_TANDA] tareas, cuatro a la vez); al acabar sube [novedades] y Tareas
 * relee (lo que no cupo va en la siguiente tanda, al releer). Tras un fallo, esa tarea no se vuelve a
 * preguntar en 10 min.
 *
 * **Lo que dijo Jev se guarda y viaja** (4-nov-2026, el usuario: «si ya se hizo la revisión, que no se
 * vuelva a hacer; lo revisado en el PC llega al celular y lo del celular al PC; sin gastar tokens»): en el
 * `anot-<uid>.urgencia.json` de su lista ([UrgenciasGuardadas]). Antes de preguntar se mira ahí; lo que ya
 * está, sea de este aparato o de otro, no se pregunta. Las prioridades se recalculan con esas señales en
 * cada aparato cada día (el plazo cambia; las señales, no).
 *
 * **Las tareas nacidas aquí se revisan al momento** ([alCambiarLasTareas]): al apuntarlas, sin esperar a
 * abrir Tareas. Las que llegaron de otro aparato solo se preguntan al abrir Tareas y si su revisión aún no
 * ha llegado: quien la creó ya la hizo.
 *
 * Sin clave o con la función apagada, `prioridad` se queda en null y Tareas ordena y enseña todo como
 * siempre.
 */
object UrgenciaEnLaApp {

    private sealed interface Sabido {
        class Dicho(val s: Urgencia.Senales) : Sabido
        class Fallo(val cuando: Long) : Sabido

        /** Un tema de una reunión: sin prioridad. */
        object Tema : Sabido
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

    /**
     * **Lo que ya se sabe de la urgencia de una tarea** (29-oct-2026, `jev::urgencia::sabidas` del PC): para
     * la urgencia en vivo de la reunión ([com.forge.pixpin.guardados.ReunionConJev]). null si aún no se sabe.
     */
    fun sabidas(clave: String): Urgencia.Senales? = (synchronized(sabido) { sabido[clave] } as? Sabido.Dicho)?.s

    /** Y cambiarlo (lo que la apura en vivo, o su «Deshacer»). En memoria, como siempre: no viaja. */
    fun ponerSabidas(clave: String, s: Urgencia.Senales?) {
        synchronized(sabido) { if (s == null) sabido.remove(clave) else sabido[clave] = Sabido.Dicho(s) }
        _novedades.value++
    }

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
     * Los días que faltan para la fecha del título de la lista (dicha el día en que se creó, en la hora
     * local), contados desde [hoy] (día local, como [Momento.dia]). 2-nov-2026, `dias_de_la_lista` del PC.
     */
    fun diasDeLaLista(l: TodasLasTareas.Lista, hoy: Long): Long? {
        val creada = Math.floorDiv(l.cuando + java.util.TimeZone.getDefault().getOffset(l.cuando), 86_400_000L)
        return Urgencia.diasDeLaLista(l.titulo, FechasDichas.Fecha.deDias(creada), FechasDichas.Fecha.deDias(hoy))
    }

    /**
     * **La prioridad y su porqué** con lo que dijo Jev: los días, de su hora o, si no tiene, de la lista
     * ([diasLista]). El porqué solo si no la marcó el usuario (entonces su chapita es la suya).
     */
    fun decidir(m: TareaInteligente.Frase, s: Urgencia.Senales, hoy: Long, diasLista: Long?): Pair<Urgencia.Prioridad, Urgencia.Motivo?> {
        val marca = marcaDe(m.urgencia)
        val dias = diasHasta(m, hoy) ?: diasLista
        val motivo = if (marca == Urgencia.Marca.NINGUNA) Urgencia.Motivo.de(s, dias, m.hora == null) else null
        return Urgencia.decidir(marca, s, dias) to motivo
    }

    /**
     * **Pone la prioridad de cada tarea pendiente** con lo ya sabido, y lanza en su hilo las preguntas
     * de lo que falta. Sin Jev, las listas tal cual. Lee el disco la primera vez: fuera del hilo principal.
     */
    fun aplicar(
        context: Context,
        listas: List<TodasLasTareas.Lista>,
        /** Preguntar solo por las listas nacidas en este aparato (lo de fondo, [alCambiarLasTareas]). */
        soloDeAqui: Boolean = false
    ): List<TodasLasTareas.Lista> {
        val cliente = Jev.para(context, Jev.Funcion.URGENCIA) ?: return listas
        val hoy = Momento.ahora().dia
        val ahora = System.currentTimeMillis()
        val filesDir = context.filesDir
        val yo = if (soloDeAqui) com.forge.pixpin.sincro.Codigos.deEsteAparato(filesDir) else null
        // Lo guardado de cada lista, leído una vez por tanda.
        val delDisco = HashMap<String, Map<String, UrgenciasGuardadas.Guardada>>()
        fun guardada(l: TodasLasTareas.Lista, texto: String): UrgenciasGuardadas.Guardada? {
            val uid = l.uid ?: return null
            return delDisco.getOrPut(uid) { UrgenciasGuardadas.leer(com.forge.pixpin.sincro.AnotacionesDelAdjunto.urgencias(filesDir, uid)) }[texto]
        }
        fun mia(l: TodasLasTareas.Lista) = !soloDeAqui || l.aparato == null || l.aparato == yo
        val uidDe = HashMap<String, String>()
        val faltan = LinkedHashMap<String, Pair<String?, Urgencia.Contexto>>()
        // La lista de cada una, (proyecto, mensaje): para no preguntar por los temas de una reunión.
        val lista = HashMap<String, Pair<String?, String>>()
        val salida = listas.map { l ->
            val diasLista = diasDeLaLista(l, hoy)
            l.copy(filas = l.filas.map { f ->
                if (f.hecha) return@map f
                val m = f.marcas
                val texto = textoParaJev(m)
                if (texto.isEmpty()) return@map f
                val clave = claveDe(l, texto)
                // Lo que ya revisó este u otro aparato, del disco: no se pregunta otra vez.
                val enMemoria = synchronized(sabido) { sabido[clave] }
                val s0 = if (enMemoria is Sabido.Dicho || enMemoria is Sabido.Tema) enMemoria
                else guardada(l, texto)?.let { g -> Sabido.Dicho(g.senales).also { d -> synchronized(sabido) { sabido[clave] = d } } } ?: enMemoria
                l.uid?.let { uidDe[clave] = it }
                when (val s = s0) {
                    is Sabido.Dicho -> {
                        val (p, motivo) = decidir(m, s.s, hoy, diasLista)
                        f.copy(prioridad = p, motivo = motivo)
                    }
                    // Un tema de una reunión: lo de siempre.
                    Sabido.Tema -> f
                    is Sabido.Fallo -> {
                        if (ahora - s.cuando >= TRAS_UN_FALLO_MS && mia(l)) {
                            faltan[clave] = l.proyecto to Urgencia.Contexto(texto, l.titulo, l.chat)
                            lista[clave] = l.proyecto to l.codigo
                        }
                        f
                    }
                    null -> {
                        if (mia(l)) {
                            faltan[clave] = l.proyecto to Urgencia.Contexto(texto, l.titulo, l.chat)
                            lista[clave] = l.proyecto to l.codigo
                        }
                        f
                    }
                }
            })
        }
        if (faltan.isNotEmpty() && preguntando.compareAndSet(false, true)) {
            val tanda = faltan.entries.take(POR_TANDA).map { Falta(it.key, lista[it.key], it.value.first, it.value.second, uidDe[it.key]) }
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

    /** Una tarea por preguntar: su clave, su lista (proyecto y mensaje), su chat y lo que se manda. */
    private class Falta(val clave: String, val lista: Pair<String?, String>?, val proyecto: String?, val contexto: Urgencia.Contexto, val uid: String?)

    /**
     * Lo de [aplicar], en el hilo: los mensajes recientes de cada chat y las preguntas. Las tareas de una
     * lista de temas de una reunión no se preguntan: quedan sabidas como tema.
     */
    private fun preguntar(app: Context, cliente: Cliente, todas: List<Falta>) {
        val mensajes = runCatching { MensajesStore(app).leer() }.getOrDefault(emptyList())
        val temas = listasDeTemas(mensajes)
        val (deTemas, tanda) = todas.partition { it.lista != null && it.lista in temas }
        if (deTemas.isNotEmpty()) synchronized(sabido) { deTemas.forEach { sabido[it.clave] = Sabido.Tema } }
        val porChat = HashMap<String?, List<String>>()
        val contextos = tanda.map { f ->
            f.contexto.copy(recientes = porChat.getOrPut(f.proyecto) { recientesDelChat(mensajes, f.proyecto) })
        }
        val empezo = System.currentTimeMillis()
        val respuestas = Urgencia.preguntarVarias(cliente, contextos, A_LA_VEZ)
        var fallos = 0
        // Lo dicho, por lista: a su fichero, para que ningún aparato lo vuelva a preguntar.
        val ahora = System.currentTimeMillis()
        val yo = com.forge.pixpin.sincro.Codigos.deEsteAparato(app.filesDir).orEmpty()
        val aGuardar = HashMap<String, MutableMap<String, UrgenciasGuardadas.Guardada>>()
        synchronized(sabido) {
            for ((i, r) in respuestas.withIndex()) {
                val clave = tanda[i].clave
                r.fold({
                    sabido[clave] = Sabido.Dicho(it)
                    tanda[i].uid?.let { uid -> aGuardar.getOrPut(uid) { HashMap() }[tanda[i].contexto.tarea] = UrgenciasGuardadas.Guardada(it, ahora, yo) }
                }, { e ->
                    if (fallos++ == 0) runCatching { android.util.Log.w("PixPinJev", "sin prioridad para una tarea: ${e.message}") }
                    sabido[clave] = Sabido.Fallo(System.currentTimeMillis())
                })
            }
        }
        for ((uid, nuevas) in aGuardar) runCatching {
            UrgenciasGuardadas.anadir(com.forge.pixpin.sincro.AnotacionesDelAdjunto.urgencias(app.filesDir, uid), nuevas)
        }
        runCatching { android.util.Log.i("PixPinJev", "prioridades: ${contextos.size} tareas, $fallos fallos, ${System.currentTimeMillis() - empezo} ms") }
    }

    /**
     * **Revisar al momento lo apuntado aquí** (4-nov-2026): cada vez que cambian los mensajes, pasado un
     * respiro, se pregunta por las tareas nuevas de las listas nacidas en este aparato (las demás ya las
     * revisó quien las creó). Lo ya revisado no cuesta nada: sale del disco. Lo llama [com.forge.pixpin.PixPinApp].
     */
    fun alCambiarLasTareas(context: Context, nombres: Map<String, String>) {
        if (Jev.para(context, Jev.Funcion.URGENCIA) == null) return
        val mensajes = runCatching { MensajesStore(context).leer() }.getOrNull() ?: return
        aplicar(context, TodasLasTareas.reunir(mensajes, nombres), soloDeAqui = true)
    }

    /**
     * **Las listas de temas de las reuniones**, (proyecto de aquí, id del mensaje): las que enlaza en
     * `temasLista` una nota de voz (2-nov-2026, `leer_chat` del PC).
     */
    fun listasDeTemas(mensajes: List<Mensaje>): Set<Pair<String?, String>> =
        mensajes.mapNotNullTo(HashSet()) { m ->
            com.forge.pixpin.guardados.TemasDeLaReunion.listaDe(m.temasLista)?.let { (p, id) ->
                com.forge.pixpin.guardados.TemasDeLaReunion.proyectoDeAqui(p) to id
            }
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
