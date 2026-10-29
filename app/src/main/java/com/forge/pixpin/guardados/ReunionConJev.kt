package com.forge.pixpin.guardados

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.audio.Grabador
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.jev.Cliente
import com.forge.pixpin.jev.Compromisos
import com.forge.pixpin.jev.EnLaReunion
import com.forge.pixpin.jev.FechasDichas
import com.forge.pixpin.jev.Jev
import com.forge.pixpin.jev.Temas
import com.forge.pixpin.jev.TiempoDeTemas
import com.forge.pixpin.jev.Urgencia
import com.forge.pixpin.jev.UrgenciaEnLaApp
import com.forge.pixpin.jev.UrgenciaViva
import com.forge.pixpin.mini.Cabecera
import com.forge.pixpin.mini.MiniApp
import com.forge.pixpin.mini.TareaInteligente
import com.forge.pixpin.mini.TareaRapidaActivity
import com.forge.pixpin.mini.Tareas
import com.forge.pixpin.mini.TodasLasTareas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * **La reunión con Jev mientras se graba, en la app** (29-oct-2026; `grabar_clase/temas.rs`,
 * `grabar_clase/reunion.rs`, `jev/temas.rs` y `jev/en_vivo.rs` del PC; `docs/jev.md` §5 y §8 y
 * «Android: los temas de la reunión en el móvil», pasos 1, 3, 4 y 5).
 *
 * - **Antes de grabar** ([Preparada]): los temas y la duración prevista que se eligieron en
 *   [GrabarClaseActivity] (y la lista de tareas de la que salieron, si salieron de una).
 * - **Grabando** ([estado], [empezar]): los temas con sus casillas (un toque marca o desmarca a mano,
 *   `aMano`, con el minuto del toque, y Jev ya no lo toca; lo que marca Jev sale con ✓ y su minuto y el
 *   aviso «✓ Tema 2 tratado · 12:30»), la cabecera con cómo va la escucha, y **las fichas**: tareas y
 *   eventos propuestos (✓ los apunta en Tareas, ✕ los descarta), los cambios de urgencia ya aplicados
 *   (con «Deshacer») y el tema olvidado. Lo pinta [TemasEnLaBarra] en la barra de cada grabación.
 * - **Al guardar** ([terminar] y [guardar]): `temas` en la nota de voz y una lista de tareas «Temas ·
 *   <nombre>» enlazada en `temasLista` (o la elegida, con lo tratado marcado), y el acta ([ActaViva]).
 *
 * Sin clave, sin red o si Jev falla, **nada se marca ni se propone solo**: la cabecera lo dice y los
 * temas se marcan a mano. La grabación nunca depende de esto. Todo lo de aquí vive en el hilo principal.
 */
object ReunionConJev {

    /** Lo elegido en «Preparar»: los temas, la lista de la que salieron (proyecto de aquí, id) y la duración prevista. */
    data class Preparada(
        val temas: List<TemasDeLaReunion.Tema>,
        val lista: Pair<String?, String>? = null,
        val duracionMin: Int? = null
    )

    /** Lo que deja [GrabarClaseActivity] para la grabación que arranca ahora (mismo proceso). */
    @Volatile var preparada: Preparada? = null

    enum class TipoDeFicha { PROPUESTA, CAMBIO, AVISO }

    /** Una ficha debajo de la barra: una propuesta (por su id del acta), un cambio de urgencia ya hecho o un aviso. */
    data class Ficha(val id: Int, val tipo: TipoDeFicha, val texto: String, val propuesta: String? = null, val cambio: Int? = null)

    /** **Lo que pinta la barra.** */
    data class Estado(
        val activa: Boolean = false,
        val temas: List<TemasDeLaReunion.Tema> = emptyList(),
        /** Cómo va la escucha (o por qué no). */
        val cabecera: String? = null,
        /** El último aviso («✓ Tema 2 tratado · 12:30», «Decisión apuntada · 12:30»…). */
        val aviso: String? = null,
        /** El tema que acaba de marcar Jev (se ilumina). */
        val brillo: String? = null,
        val fichas: List<Ficha> = emptyList(),
        /** La lista de los temas, abierta debajo de la barra. */
        val abierta: Boolean = false,
        /** Lo que se está oyendo ahora (un parcial del reconocedor). */
        val parcial: String = ""
    )

    /** Las fichas que se ven a la vez y las que se guardan como mucho. */
    const val FICHAS_A_LA_VISTA = 3
    private const val MAXIMO_FICHAS = 12
    /** Las tareas que ya existen que pueden cambiar de urgencia en la reunión. */
    private const val TAREAS = 8
    /** Lo que espera Jev a una petición de los temas (3 preguntas por tema) y a una frase de la reunión. */
    private const val TIEMPO_TEMAS_MS = 4_000L
    private const val TIEMPO_REUNION_MS = 3_000L

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> get() = _estado

    private val principal by lazy { Handler(Looper.getMainLooper()) }

    /** Dónde vive una tarea de la reunión (la que existía o la apuntada al aceptar una propuesta). */
    private data class TareaEnLista(val proyecto: String?, val codigo: String, val lista: String, val crudo: String) {
        fun visible(): String = Tareas.legible(crudo)
        fun clave(): String = "${proyecto.orEmpty()}\u001f$lista\u001f${UrgenciaEnLaApp.textoParaJev(TareaInteligente.marcas(visible()))}"
    }

    private class Deshacer(val ahora: TareaEnLista, val antes: TareaEnLista, val senalesAntes: Urgencia.Senales?, val vivaAntes: UrgenciaViva.TareaViva?)

    /** **La reunión en marcha.** */
    private class Sesion(
        val app: Context,
        val nombre: String,
        val proyecto: String?,
        val lista: Pair<String?, String>?,
        val msDeLaNota: () -> Long,
        /** El diario de «Grabar sin perder nada» de esta grabación: los temas van ahí en el acto. */
        val diario: DiarioEnMarcha? = null
    ) {
        var escucha: EscuchaDeLaReunion? = null
        var mesa: EnLaReunion.Mesa? = null
        var agendaIds: List<String> = emptyList()
        val decisiones = ArrayList<ActaViva.Dicho>()
        val propuestas = ArrayList<ActaViva.Propuesta>()
        val cambios = ArrayList<ActaViva.CambioDeUrgencia>()
        var tiempoPorTema: ActaViva.TiempoPorTema? = null
        val abiertas = LinkedHashMap<String, ActaViva.Dicho>()
        val sitios = HashMap<String, TareaEnLista>()
        val deshaceres = HashMap<Int, Deshacer>()
        var nFichas = 0
        var parada = false

        fun acta() = ActaViva.Acta(decisiones.toList(), abiertas.values.toList(), tiempoPorTema, propuestas.toList(), cambios.toList())
    }

    private var sesion: Sesion? = null

    // --- Empezar --------------------------------------------------------------------------------

    /**
     * **Empieza la reunión con Jev** para la grabación de [g] (en el hilo principal, con la nota ya
     * grabando). [msDeLaNota]: por dónde va la nota (el minuto de un toque a mano). Sin temas ni ideas
     * encendidas no hace nada y la barra queda como siempre.
     */
    fun empezar(context: Context, nombre: String, proyecto: String?, g: Grabador?, diario: DiarioEnMarcha? = null, msDeLaNota: () -> Long) {
        terminar()
        val app = context.applicationContext
        val p = preparada
        preparada = null
        val temas = p?.temas.orEmpty()
        val s = Sesion(app, nombre, proyecto, p?.lista, msDeLaNota, diario)
        sesion = s
        alDiario(s, temas, conLaLista = true)
        _estado.value = Estado(activa = true, temas = temas, cabecera = if (temas.isNotEmpty()) TextosDeLaReunion.PREPARANDO else null)
        if (temas.isNotEmpty()) runCatching { PrepararTemas.olvidarLaProxima(app) }
        val duracionMs = p?.duracionMin?.let { it * 60_000L }
        Thread({
            val arranque = runCatching { preparar(app, s, nombre, proyecto, temas, duracionMs, g) }.getOrNull()
            principal.post {
                if (sesion !== s || s.parada) { arranque?.first?.parar(); return@post }
                val (escucha, cabecera) = arranque ?: (null to TextosDeLaReunion.SIN_PCM)
                s.escucha = escucha
                if (temas.isNotEmpty() || escucha != null) _estado.update { it.copy(cabecera = cabecera ?: it.cabecera) }
                if (escucha != null && g != null && !escucha.empezar(g)) s.escucha = null
            }
        }, "pixpin-reunion-arranque").apply { isDaemon = true }.start()
    }

    /** Lo que hace falta para escuchar (lee el disco: fuera del hilo principal). La escucha, o null y por qué. */
    private fun preparar(
        app: Context, s: Sesion, nombre: String, proyecto: String?, temas: List<TemasDeLaReunion.Tema>,
        duracionMs: Long?, g: Grabador?
    ): Pair<EscuchaDeLaReunion?, String?> {
        if (com.forge.pixpin.sincro.CamaraDelPc.haciendoDeCamara()) return null to TextosDeLaReunion.CAMARA_DEL_PC
        val clave = Jev.clave(app)
        val agenda = temas.map { it.texto }
        // Los temas: sin agenda no se pregunta; sin clave o apagado, se dice.
        val temasPorJev = agenda.isNotEmpty() && clave != null && Jev.encendida(app, Jev.Funcion.TEMAS)
        val porQueNoTemas = when {
            agenda.isEmpty() -> null
            clave == null -> TextosDeLaReunion.SIN_CLAVE
            !Jev.encendida(app, Jev.Funcion.TEMAS) -> TextosDeLaReunion.APAGADO
            else -> null
        }
        val ideas = EnLaReunion.Ideas(
            tareas = Jev.encendida(app, Jev.Funcion.TAREAS_DICHAS),
            urgencia = Jev.encendida(app, Jev.Funcion.URGENCIA_EN_VIVO),
            decisiones = Jev.encendida(app, Jev.Funcion.DECISIONES),
            preguntas = Jev.encendida(app, Jev.Funcion.PREGUNTAS_PENDIENTES),
            citas = Jev.encendida(app, Jev.Funcion.FECHAS_AL_HORARIO),
            tiempoPorTema = Jev.encendida(app, Jev.Funcion.TIEMPO_POR_TEMA),
            temaOlvidado = Jev.encendida(app, Jev.Funcion.TEMA_OLVIDADO)
        )
        val conLoDemas = clave != null && ideas.alguna()
        if (!temasPorJev && !conLoDemas) return null to porQueNoTemas
        if (g == null) return null to TextosDeLaReunion.SIN_PCM
        val hoy = FechasDichas.Fecha.hoy()
        var mesa: EnLaReunion.Mesa? = null
        if (conLoDemas) {
            val tareas = if (ideas.urgencia) tareasDePartida(app, proyecto, s.lista, hoy) else emptyList()
            tareas.forEach { (viva, sitio) -> s.sitios[viva.id] = sitio }
            mesa = EnLaReunion.Mesa(ideas, nombre, agenda, hoy, duracionMs, tareas.map { it.first })
            s.mesa = mesa
            s.agendaIds = temas.map { it.id }
        }
        val pendientes = TemasDeLaReunion.paraJev(temas).map { Temas.Pendiente(it.id, it.texto) }
        val escucha = EscuchaDeLaReunion(
            app, nombre, agenda, pendientes,
            clienteTemas = if (temasPorJev) Cliente(clave!!, tiempoMs = TIEMPO_TEMAS_MS) else null,
            mesa = mesa,
            clienteMesa = if (conLoDemas) Cliente(clave!!, tiempoMs = TIEMPO_REUNION_MS) else null,
            idioma = Transcriptor.idiomaElegido(app),
            alVolver = { v -> principal.post { volver(s, v) } }
        )
        return escucha to (porQueNoTemas ?: TextosDeLaReunion.PREPARANDO)
    }

    /**
     * **Las tareas pendientes que pueden cambiar de urgencia**: las del chat de la reunión primero y luego
     * las del Inbox, menos la lista de los temas; cada una con lo que Tareas ya sabe de su urgencia.
     */
    private fun tareasDePartida(app: Context, proyecto: String?, sinLaLista: Pair<String?, String>?, hoy: FechasDichas.Fecha): List<Pair<UrgenciaViva.TareaViva, TareaEnLista>> {
        val mensajes = MensajesStore(app).leer()
        val nombres = (app as? PixPinApp)?.proyectos?.proyectos?.value?.associate { it.id to it.nombre }.orEmpty()
        val listas = TodasLasTareas.reunir(mensajes.filter { !it.enBuzon }, nombres)
        val v = ArrayList<Pair<UrgenciaViva.TareaViva, TareaEnLista>>()
        val orden = listas.filter { it.proyecto == proyecto } + listas.filter { it.proyecto != proyecto && TodasLasTareas.esInbox(it) }
        for (l in orden) {
            if (sinLaLista?.second == l.codigo) continue
            for (f in l.filas.filter { !it.hecha }) {
                if (v.size >= TAREAS) return v
                val titulo = f.marcas.titulo.trim()
                if (titulo.isEmpty()) continue
                val sitio = TareaEnLista(l.proyecto, l.codigo, l.titulo, f.crudo)
                val fecha = Tareas.horaDe(f.crudo)?.toLocalDate()?.let { FechasDichas.Fecha(it.year, it.monthValue, it.dayOfMonth) }
                val viva = UrgenciaViva.TareaViva.nueva("t${v.size + 1}", titulo, fecha, UrgenciaEnLaApp.sabidas(sitio.clave()), hoy)
                v += viva to sitio
            }
        }
        return v
    }

    // --- Lo que llega de la escucha -------------------------------------------------------------

    private fun volver(s: Sesion, v: EscuchaDeLaReunion.Vuelta) {
        if (sesion !== s || s.parada) return
        when (v) {
            is EscuchaDeLaReunion.Vuelta.Tratado -> tratadoPorJev(s, v.tratado)
            is EscuchaDeLaReunion.Vuelta.Aviso -> {
                val texto = when (v.aviso) {
                    EscuchaDeLaReunion.Como.ESCUCHANDO -> if (_estado.value.temas.isEmpty()) TextosDeLaReunion.ESCUCHANDO_REUNION else TextosDeLaReunion.ESCUCHANDO
                    EscuchaDeLaReunion.Como.SIN_MODELO -> TextosDeLaReunion.SIN_MODELO
                    EscuchaDeLaReunion.Como.JEV_FALLA -> TextosDeLaReunion.JEV_FALLA
                    EscuchaDeLaReunion.Como.JUSTO -> TextosDeLaReunion.JUSTO
                    EscuchaDeLaReunion.Como.SIN_PCM -> TextosDeLaReunion.SIN_PCM
                    EscuchaDeLaReunion.Como.REUNION_FALLA -> null
                }
                if (v.aviso == EscuchaDeLaReunion.Como.REUNION_FALLA) avisar(TextosDeLaReunion.VIVO_FALLA)
                else if (_estado.value.temas.isNotEmpty() || v.aviso != EscuchaDeLaReunion.Como.ESCUCHANDO) _estado.update { it.copy(cabecera = texto) }
            }
            is EscuchaDeLaReunion.Vuelta.Reunion -> deLaReunion(s, v.sucesos)
            is EscuchaDeLaReunion.Vuelta.Parcial -> _estado.update { it.copy(parcial = v.texto) }
        }
    }

    private fun avisar(texto: String) {
        _estado.update { it.copy(aviso = texto) }
        principal.postDelayed({ _estado.update { if (it.aviso == texto) it.copy(aviso = null, brillo = null) else it } }, 4_000)
    }

    /** Jev marcó un tema: solo si sigue pendiente y nadie lo tocó a mano. */
    private fun tratadoPorJev(s: Sesion, t: Temas.Tratado) {
        val v = _estado.value.temas.toMutableList()
        if (!TemasDeLaReunion.porJev(v, t.id, t.ms, t.p)) return
        val n = v.indexOfFirst { it.id == t.id } + 1
        _estado.update { it.copy(temas = v, brillo = t.id) }
        avisar(TextosDeLaReunion.tratado(n, GrabarClase.minuto(t.ms)))
        alCambiarLosTemas(s, v)
    }

    /**
     * **Los temas, al diario** (29-oct-2026, `Estado::apuntar_temas` del PC): al empezar (con la lista elegida)
     * y cada vez que se marca uno, a mano o Jev; la grabación recuperada tras un corte los lleva.
     */
    private fun alDiario(s: Sesion, v: List<TemasDeLaReunion.Tema>, conLaLista: Boolean = false) {
        val d = s.diario ?: return
        if (v.isEmpty() && !conLaLista) return
        val j = TemasDeLaReunion.aJson(v)
        d.cambiar {
            temas = j
            if (conLaLista) temasLista = s.lista?.let { (p, id) -> listOf(TemasDeLaReunion.proyectoQueViaja(p), id) }
        }
    }

    /** Lo que siguen mirando Jev y la mesa tras un cambio en los temas (y el diario, al día). */
    private fun alCambiarLosTemas(s: Sesion, v: List<TemasDeLaReunion.Tema>) {
        alDiario(s, v)
        s.escucha?.pendientes(TemasDeLaReunion.paraJev(v).map { Temas.Pendiente(it.id, it.texto) })
        s.mesa?.tratados(s.agendaIds.withIndex().filter { (_, id) -> v.firstOrNull { it.id == id }?.hecho == true }.map { it.index })
    }

    // --- A mano ---------------------------------------------------------------------------------

    /** Abre o pliega la lista de los temas debajo de la barra. */
    fun alternarLista() = _estado.update { it.copy(abierta = !it.abierta) }

    /** **Un toque en un tema**: lo marca o lo desmarca a mano, con el minuto de ahora; Jev ya no lo toca. */
    fun marcar(id: String) {
        val s = sesion ?: return
        val v = _estado.value.temas.toMutableList()
        val t = v.firstOrNull { it.id == id } ?: return
        if (!TemasDeLaReunion.aMano(v, id, !t.hecho, runCatching { s.msDeLaNota() }.getOrNull())) return
        _estado.update { it.copy(temas = v) }
        alCambiarLosTemas(s, v)
    }

    // --- Lo demás de la reunión -----------------------------------------------------------------

    /** «30/10» o «30/10 15:00». */
    private fun corta(m: FechasDichas.Momento): String {
        val f = "${m.fecha.dia}/${m.fecha.mes}"
        return m.hora?.let { "$f $it" } ?: f
    }

    private fun ficha(s: Sesion, tipo: TipoDeFicha, texto: String, propuesta: String? = null, cambio: Int? = null): Int {
        s.nFichas++
        val f = Ficha(s.nFichas, tipo, texto, propuesta, cambio)
        _estado.update { e -> e.copy(fichas = (e.fichas + f).takeLast(MAXIMO_FICHAS)) }
        return f.id
    }

    private fun quitarFicha(id: Int) = _estado.update { e -> e.copy(fichas = e.fichas.filter { it.id != id }) }

    private fun nombresDe(s: Sesion, indices: List<Int>): String =
        indices.mapNotNull { s.agendaIds.getOrNull(it) }.mapNotNull { id -> _estado.value.temas.firstOrNull { it.id == id }?.texto }.joinToString(", ")

    /** **Lo que llegó de una frase** (`de_la_reunion` del PC). */
    private fun deLaReunion(s: Sesion, sucesos: List<EnLaReunion.Suceso>) {
        val ahora = runCatching { s.msDeLaNota() }.getOrDefault(0L)
        for (x in sucesos) when (x) {
            is EnLaReunion.Suceso.Tarea -> {
                val t = x.propuesta
                val id = "p${s.propuestas.size + 1}"
                val responsable = (t.responsable as? Compromisos.Responsable.Nombre)?.nombre
                var texto = "＋ ${TextosDeLaReunion.TAREA}: ${t.texto}"
                responsable?.let { texto += " · $it" }
                t.fecha?.let { texto += " · ${corta(it)}" }
                s.propuestas += ActaViva.Propuesta(
                    id, ActaViva.Tipo.TAREA, t.ms, t.texto, responsable, t.fecha?.fecha?.toString(), t.fecha?.hora?.toString(), t.prioridad.id
                )
                ficha(s, TipoDeFicha.PROPUESTA, texto, propuesta = id)
            }
            is EnLaReunion.Suceso.Evento -> {
                val e = x.evento
                val id = "p${s.propuestas.size + 1}"
                s.propuestas += ActaViva.Propuesta(id, ActaViva.Tipo.EVENTO, e.ms, e.titulo, null, e.momento.fecha.toString(), e.momento.hora?.toString(), null)
                ficha(s, TipoDeFicha.PROPUESTA, "📅 ${TextosDeLaReunion.EVENTO}: ${e.titulo} · ${corta(e.momento)}", propuesta = id)
            }
            is EnLaReunion.Suceso.Cambio -> cambioDeUrgencia(s, x.cambio, x.tarea, ahora)
            is EnLaReunion.Suceso.Decision -> {
                s.decisiones += ActaViva.Dicho(x.ms, x.texto)
                avisar(TextosDeLaReunion.decision(GrabarClase.minuto(x.ms)))
            }
            is EnLaReunion.Suceso.Abierta -> s.abiertas[x.abierta.id] = ActaViva.Dicho(x.abierta.ms, x.abierta.texto)
            is EnLaReunion.Suceso.Contestada -> s.abiertas.remove(x.contestada.pregunta.id)
            is EnLaReunion.Suceso.Tiempos -> s.tiempoPorTema = ActaViva.TiempoPorTema(
                s.agendaIds.zip(x.porTema).map { (id, ms) -> ActaViva.TiempoDeTema(id, ms) }, x.otro
            )
            is EnLaReunion.Suceso.Aviso -> {
                val texto = when (val a = x.aviso) {
                    is TiempoDeTemas.Aviso.QuedaPoco -> TextosDeLaReunion.quedaPoco((a.quedanMs + 59_999) / 60_000, nombresDe(s, a.temas))
                    is TiempoDeTemas.Aviso.TiempoCumplido -> TextosDeLaReunion.cumplido(nombresDe(s, a.temas))
                    is TiempoDeTemas.Aviso.Cierran -> TextosDeLaReunion.cierran(nombresDe(s, a.temas))
                }
                avisar(texto)
                ficha(s, TipoDeFicha.AVISO, "⏳ $texto")
            }
        }
    }

    /**
     * **Un cambio de urgencia**: se aplica ya (la fecha nueva en el `⏰` de la tarea, lo que la apura en lo
     * que sabe Tareas), se dice y queda su «Deshacer». Es lo único que se aplica solo.
     */
    private fun cambioDeUrgencia(s: Sesion, c: UrgenciaViva.Cambio, tarea: String, ahora: Long) {
        val sitio = s.sitios[c.id] ?: return
        val viva = s.mesa?.tareaDe(c.id)
        val fecha = c.fechaDespues?.takeIf { c.fechaDespues != c.fechaAntes }
        val senales = viva?.senales?.takeIf { c.antes != c.despues }
        Thread({
            val r = runCatching { aplicarCambio(s.app, sitio, fecha, senales) }.getOrNull()
            principal.post {
                if (sesion !== s || r == null) return@post
                val (nuevo, deshacer) = r
                s.sitios[c.id] = nuevo
                var texto = tarea
                if (c.antes != c.despues) {
                    val flecha = if (c.despues.ordinal < c.antes.ordinal) "↑" else "↓"
                    texto = "$flecha $tarea: ${TextosDeLaReunion.prioridad(c.antes)} → ${TextosDeLaReunion.prioridad(c.despues)}"
                }
                fecha?.let { texto += " · " + TextosDeLaReunion.fechaNueva("${it.dia}/${it.mes}") }
                avisar(texto)
                val indice = s.cambios.size
                s.cambios += ActaViva.CambioDeUrgencia(ahora, tarea, c.antes.id, c.despues.id, c.fechaAntes?.toString(), c.fechaDespues?.toString())
                val antes = viva?.copy(fecha = c.fechaAntes, prioridad = c.antes)
                val idFicha = ficha(s, TipoDeFicha.CAMBIO, texto, cambio = indice)
                s.deshaceres[idFicha] = Deshacer(nuevo, deshacer.antes, deshacer.senalesAntes, antes)
            }
        }, "pixpin-reunion-urgencia").apply { isDaemon = true }.start()
    }

    /** La tarea [t] con su texto cambiado por [nuevoCrudo] (encontrada por su texto: la lista pudo cambiar). */
    private fun reescribir(app: Context, t: TareaEnLista, nuevoCrudo: String): TareaEnLista? {
        var hecho = false
        MensajesStore(app).actualizar(t.codigo) { m ->
            val tareas = Tareas.leer(m.texto)
            val i = tareas.indexOfFirst { it.texto == t.crudo }
            if (i < 0) m else {
                hecho = true
                m.copy(texto = Tareas.escribir(Cabecera.titulo(m.texto), tareas.toMutableList().also { it[i] = it[i].copy(texto = nuevoCrudo) }))
            }
        }
        return if (hecho) t.copy(crudo = nuevoCrudo) else null
    }

    private fun aplicarCambio(app: Context, t: TareaEnLista, fecha: FechasDichas.Fecha?, senales: Urgencia.Senales?): Pair<TareaEnLista, Deshacer>? {
        val antesSabidas = UrgenciaEnLaApp.sabidas(t.clave())
        var ahora = t
        if (fecha != null) {
            val hora = Tareas.horaDe(t.crudo)?.toLocalTime() ?: java.time.LocalTime.of(com.forge.pixpin.ia.Fechas.HORA_POR_OMISION / 60, 0)
            val nueva = java.time.LocalDateTime.of(java.time.LocalDate.of(fecha.anio, fecha.mes, fecha.dia), hora)
            ahora = reescribir(app, t, Tareas.conHora(t.crudo, nueva)) ?: return null
        }
        if (senales != null) UrgenciaEnLaApp.ponerSabidas(ahora.clave(), senales)
        return ahora to Deshacer(ahora, t, antesSabidas, null)
    }

    /** **✓ de una ficha**: aceptar la propuesta, deshacer el cambio o quitar el aviso. */
    fun fichaSi(id: Int) {
        val s = sesion ?: return
        val f = _estado.value.fichas.firstOrNull { it.id == id } ?: return
        when (f.tipo) {
            TipoDeFicha.PROPUESTA -> {
                val p = s.propuestas.firstOrNull { it.id == f.propuesta } ?: return
                quitarFicha(id)
                Thread({
                    val bien = runCatching { TareaRapidaActivity.alInbox(s.app, textoDe(p, s.nombre)) }.isSuccess
                    principal.post {
                        if (!bien) { avisar(TextosDeLaReunion.NO_SE_APUNTO); return@post }
                        val i = s.propuestas.indexOfFirst { it.id == p.id }
                        if (i >= 0) s.propuestas[i] = s.propuestas[i].copy(estado = ActaViva.Estado.ACEPTADA)
                        avisar(if (p.tipo == ActaViva.Tipo.TAREA) TextosDeLaReunion.TAREA_APUNTADA else TextosDeLaReunion.EVENTO_APUNTADO)
                        // Desde ahora también puede cambiar de urgencia en la reunión.
                        if (p.tipo == ActaViva.Tipo.TAREA) apuntadaEnVivo(s, p)
                    }
                }, "pixpin-reunion-aceptar").apply { isDaemon = true }.start()
            }
            TipoDeFicha.CAMBIO -> {
                val d = s.deshaceres.remove(id) ?: run { quitarFicha(id); return }
                quitarFicha(id)
                Thread({
                    val bien = runCatching {
                        if (d.ahora.crudo != d.antes.crudo) reescribir(s.app, d.ahora, d.antes.crudo)
                        UrgenciaEnLaApp.ponerSabidas(d.antes.clave(), d.senalesAntes)
                    }.isSuccess
                    principal.post {
                        if (!bien) { avisar(TextosDeLaReunion.NO_SE_APUNTO); return@post }
                        f.cambio?.let { i -> if (i in s.cambios.indices) s.cambios[i] = s.cambios[i].copy(deshecho = true) }
                        s.sitios.entries.firstOrNull { it.value == d.ahora }?.let { it.setValue(d.antes) }
                        d.vivaAntes?.let { s.mesa?.tarea(it) }
                        avisar(TextosDeLaReunion.DESHECHO)
                    }
                }, "pixpin-reunion-deshacer").apply { isDaemon = true }.start()
            }
            TipoDeFicha.AVISO -> quitarFicha(id)
        }
    }

    /** La tarea recién apuntada pasa a la mesa: también puede cambiar de urgencia (`apuntar_tarea` del PC). */
    private fun apuntadaEnVivo(s: Sesion, p: ActaViva.Propuesta) {
        val mesa = s.mesa ?: return
        Thread({
            val inbox = runCatching { TodasLasTareas.inboxEn(MensajesStore(s.app).leer()) }.getOrNull() ?: return@Thread
            val crudo = Tareas.leer(inbox.texto).lastOrNull()?.texto ?: return@Thread
            val viva = UrgenciaViva.TareaViva.nueva("a${p.id}", p.texto.trim(), p.fecha?.let { FechasDichas.Fecha.deTexto(it) }, null, FechasDichas.Fecha.hoy())
            principal.post {
                s.sitios[viva.id] = TareaEnLista(null, inbox.id, TodasLasTareas.nombreDeLista(inbox), crudo)
                mesa.tarea(viva)
            }
        }, "pixpin-reunion-apuntada").apply { isDaemon = true }.start()
    }

    /** **✕ de una ficha**: la propuesta se descarta; un cambio o un aviso solo se quitan de la vista. */
    fun fichaNo(id: Int) {
        val s = sesion ?: return
        val f = _estado.value.fichas.firstOrNull { it.id == id } ?: return
        quitarFicha(id)
        s.deshaceres.remove(id)
        if (f.tipo == TipoDeFicha.PROPUESTA) {
            val i = s.propuestas.indexOfFirst { it.id == f.propuesta }
            if (i >= 0) s.propuestas[i] = s.propuestas[i].copy(estado = ActaViva.Estado.DESCARTADA)
        }
    }

    // --- Lo que se apunta en Tareas (el mismo texto que el PC) ----------------------------------

    /** La urgencia de Tareas para una prioridad de Jev: urgente → ⏫, alta → 🔼; lo demás, sin marca. */
    internal fun urgenciaDe(p: String?): TareaInteligente.Urgencia = when (Urgencia.Prioridad.deId(p)) {
        Urgencia.Prioridad.URGENTE -> TareaInteligente.Urgencia.MUY_URGENTE
        Urgencia.Prioridad.ALTA -> TareaInteligente.Urgencia.URGENTE
        else -> TareaInteligente.Urgencia.NORMAL
    }

    /** El momento (`⏰`) de una fecha y una hora escritas en la propuesta (sin hora, la de siempre: 9:00). */
    internal fun momentoDe(fecha: String?, hora: String?): Momento? {
        val f = FechasDichas.Fecha.deTexto(fecha ?: return null) ?: return null
        val minuto = hora?.split(':')?.takeIf { it.size == 2 }?.let { (a, b) ->
            val h = a.trim().toIntOrNull()
            val m = b.trim().toIntOrNull()
            if (h != null && m != null) h * 60 + m else null
        }?.takeIf { it in 0 until 24 * 60 } ?: com.forge.pixpin.ia.Fechas.HORA_POR_OMISION
        return Momento(f.dias(), minuto)
    }

    /**
     * **El texto de la tarea** de una propuesta (`jev::en_vivo::texto_de` del PC, byte a byte): lo dicho, su
     * urgencia, la nota (quién y de qué reunión) y su `⏰`. «mandar la cotización 🔼 💬 Luis · 🎙
     * Coordinación ⏰ 2026-10-30 09:00».
     */
    fun textoDe(p: ActaViva.Propuesta, reunion: String): String {
        val nota = ArrayList<String>()
        p.responsable?.trim()?.takeIf { it.isNotEmpty() }?.let { nota += it }
        if (reunion.isNotBlank()) nota += "🎙 ${reunion.trim()}"
        return TareaInteligente.Frase(
            titulo = p.texto.trim(),
            urgencia = urgenciaDe(p.prioridad),
            etiquetas = emptyList(),
            hora = momentoDe(p.fecha, p.hora),
            nota = nota.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ).texto()
    }

    /** El texto de un tema o una pregunta que va a Tareas, con la reunión de nota (`temas::a_tareas` del PC). */
    fun textoATareas(texto: String, reunion: String): String = "$texto ${TareaInteligente.NOTA} 🎙 ${reunion.trim()}"

    // --- Terminar y guardar ---------------------------------------------------------------------

    /** Lo que queda de la reunión al terminar: lo que va a la nota. */
    data class Cierre(
        val temas: List<TemasDeLaReunion.Tema>,
        val acta: ActaViva.Acta,
        val lista: Pair<String?, String>?,
        val nombre: String,
        val proyecto: String?
    ) {
        fun vacio(): Boolean = temas.isEmpty() && acta.vacia()
    }

    /** **Termina**: para la escucha en el acto y devuelve lo que va a la nota (null si no había reunión). */
    fun terminar(): Cierre? {
        val s = sesion ?: return null
        sesion = null
        s.parada = true
        s.escucha?.parar()
        val e = _estado.value
        _estado.value = Estado()
        return Cierre(e.temas, s.acta(), s.lista, s.nombre, s.proyecto)
    }

    /** **Tirar** la grabación: la reunión se olvida. */
    fun tirar() {
        terminar()
    }

    /**
     * **Escribe la reunión en su nota** [notaId] (trabajo de disco): lo marcado mientras tanto en su lista
     * entra como hecho a mano; la lista «Temas · <nombre>» (nueva en el chat, o la elegida con los temas
     * nuevos añadidos) con lo tratado marcado; y en la nota, `temas`, `temasLista` y el acta.
     */
    fun guardar(context: Context, notaId: String, c: Cierre) {
        if (c.vacio()) return
        val almacen = MensajesStore(context)
        val temas = c.temas.toMutableList()
        var enlace: Pair<String?, String>? = null
        if (temas.isNotEmpty()) {
            val todos = almacen.leer()
            val elegida = c.lista?.let { (_, id) -> todos.firstOrNull { it.id == id && TodasLasTareas.esLista(it) } }
            elegida?.let { TemasDeLaReunion.conLaLista(temas, it.texto) }
            enlace = runCatching { guardarLista(context, almacen, c, temas, elegida) }
                .onFailure { android.util.Log.w("PixPinReunion", "los temas quedaron sin su lista de tareas", it) }
                .getOrNull()
        }
        almacen.actualizar(notaId) { m ->
            val conTemas = m.copy(
                temas = TemasDeLaReunion.aJson(temas) ?: m.temas,
                temasLista = enlace?.let { (p, id) -> TemasDeLaReunion.listaEnJson(TemasDeLaReunion.proyectoQueViaja(p), id) } ?: m.temasLista
            )
            if (c.acta.vacia()) conTemas else ActaViva.poner(conTemas, c.acta)
        }
    }

    /** La lista de tareas de la reunión: la elegida (si sigue) o una nueva en el chat. Devuelve (proyecto, id). */
    private fun guardarLista(
        context: Context, almacen: MensajesStore, c: Cierre, temas: List<TemasDeLaReunion.Tema>, elegida: Mensaje?
    ): Pair<String?, String> {
        val titulo = TextosDeLaReunion.listaDe(c.nombre)
        fun conTodo(doc: String): String {
            var tareas = Tareas.leer(doc)
            val ya = TemasDeLaReunion.deDocumento(doc).map { TemasDeLaReunion.clave(it.first) }
            // Los temas que la lista aún no tiene, al final y con la fecha de hoy.
            for (t in temas) if (TemasDeLaReunion.clave(t.texto) !in ya) tareas = Tareas.anadir(tareas, t.texto)
            val conLosNuevos = Tareas.escribir(Cabecera.titulo(doc).ifEmpty { titulo }, tareas)
            // Y lo tratado, marcado.
            var marcadas = Tareas.leer(conLosNuevos)
            for ((i, h) in TemasDeLaReunion.marcasParaLaLista(temas, conLosNuevos)) marcadas = Tareas.marcar(marcadas, i, h)
            return Tareas.escribir(Cabecera.titulo(conLosNuevos), marcadas)
        }
        if (elegida != null) {
            almacen.actualizar(elegida.id) { m -> m.copy(texto = conTodo(m.texto)) }
            return elegida.proyecto to elegida.id
        }
        val id = UUID.randomUUID().toString()
        almacen.anadir(
            Mensaje(
                id = id, cuando = System.currentTimeMillis(), clase = Clase.MINIAPP, miniapp = MiniApp.TAREAS.id,
                texto = conTodo(Tareas.escribir(titulo, emptyList())), proyecto = c.proyecto
            )
        )
        return c.proyecto to id
    }

    /**
     * **Lo de la reunión de una grabación recuperada tras un corte** (`recuperar_una` del PC): los temas del
     * diario, con lo ya tratado, y su lista. null si el diario no los lleva.
     */
    fun cierreDelDiario(d: GrabacionAMedias.Diario): Cierre? {
        val temas = TemasDeLaReunion.temasDe(d.temas as? kotlinx.serialization.json.JsonArray)
        if (temas.isEmpty()) return null
        val lista = d.temasLista?.takeIf { it.size == 2 }?.let { (p, id) -> TemasDeLaReunion.proyectoDeAqui(p) to id }
        return Cierre(temas, ActaViva.Acta(), lista, d.nombre, d.proyecto.ifEmpty { null })
    }

    // --- Al estudiar la clase (sin clave ni red) ------------------------------------------------

    /** **Un tema cambiado a mano en la nota** [notaId] (al estudiar): en `temas` y en su lista de tareas. Trabajo de disco. */
    fun cambiarAMano(context: Context, notaId: String, tema: String, hecho: Boolean) {
        val almacen = MensajesStore(context)
        var texto: String? = null
        var lista: Pair<String, String>? = null
        almacen.actualizar(notaId) { m ->
            val v = TemasDeLaReunion.temasDe(m).toMutableList()
            if (!TemasDeLaReunion.aMano(v, tema, hecho, null)) m else {
                texto = v.firstOrNull { it.id == tema }?.texto
                lista = TemasDeLaReunion.listaDe(m.temasLista)
                m.copy(temas = TemasDeLaReunion.aJson(v))
            }
        }
        val t = texto ?: return
        val (_, id) = lista ?: return
        almacen.actualizar(id) { m ->
            if (!TodasLasTareas.esLista(m)) m else {
                val tareas = Tareas.leer(m.texto)
                val i = tareas.indexOfFirst { TemasDeLaReunion.clave(TemasDeLaReunion.limpio(Tareas.legible(it.texto))) == TemasDeLaReunion.clave(t) }
                if (i < 0) m else m.copy(texto = Tareas.escribir(Cabecera.titulo(m.texto), Tareas.marcar(tareas, i, hecho)))
            }
        }
    }

    /** **«Aceptar»** una propuesta de la nota al estudiar: al Inbox con el texto del PC, y queda `aceptada`. */
    fun aceptarDeLaNota(context: Context, notaId: String, propuesta: String, reunion: String): Boolean {
        val almacen = MensajesStore(context)
        val m = almacen.leer().firstOrNull { it.id == notaId } ?: return false
        val a = ActaViva.de(m)
        val p = a.propuestas.firstOrNull { it.id == propuesta && it.estado == ActaViva.Estado.PENDIENTE } ?: return false
        TareaRapidaActivity.alInbox(context, textoDe(p, reunion))
        almacen.actualizar(notaId) { n ->
            val b = ActaViva.de(n)
            ActaViva.poner(n, b.copy(propuestas = b.propuestas.map { if (it.id == propuesta) it.copy(estado = ActaViva.Estado.ACEPTADA) else it }))
        }
        return true
    }

    /** Apunta en el Inbox de Tareas estos textos (temas sin tratar, preguntas sin respuesta), con la reunión de nota. Devuelve cuántos. */
    fun aTareas(context: Context, textos: List<String>, reunion: String): Int =
        textos.count { t -> runCatching { TareaRapidaActivity.alInbox(context, textoATareas(t, reunion)) }.isSuccess }
}
