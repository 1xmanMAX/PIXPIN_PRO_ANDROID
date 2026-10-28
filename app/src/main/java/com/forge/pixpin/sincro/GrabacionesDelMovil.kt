package com.forge.pixpin.sincro

import com.forge.pixpin.guardados.CarpetaDePaginas
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.GrabarClase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.PorSuHora
import com.forge.pixpin.guardados.Presencial
import com.forge.pixpin.guardados.RelojDePared
import java.io.File

/**
 * **El teléfono graba y el PC le manda capturas** (28-oct-2026, §13 de `docs/movil-como-camara.md` del
 * PC: «las fotos tienen que ser BIDIRECCIONALES con la PC: la PC no puede tomar fotos, pero sí
 * CAPTURAS»). Es `Camaras` de `crates/pixpin-proyecto/src/camara_del_movil.rs` del PC con el teléfono
 * en el sitio de quien graba: el mismo código de «quien graba» vale para cualquier aparato.
 *
 * - **Lleva el reloj de su grabación** ([empezar], [seguirA] con lo que dice el cronómetro, [acabar])
 *   y lo anuncia ([anuncio]): es lo que va en `suya` de la `camara_remota` al PC ([GrabacionDelMovil]
 *   lo manda al empezar, pausar, seguir, acabar y cada 30 s).
 * - **Recibe cada captura del PC** ([recibir], desde el [Respondedor]): su minuto es el que calculó el
 *   PC (`ms`, con el reloj de aquí pasado al suyo) o, sin él, el de su hora con el desfase.
 * - **La pone en la clase** ([adjuntar]) al guardarse ([guardada]; la nota no existe antes) o al
 *   momento si ya está guardada: una IMAGEN con `enGrabacion {de, ms}`, `fuente: "pantalla"`,
 *   `deAparato`, `tomadaEn` y `fotoUid` (la `clave`), en `capturas` de la nota y en su carpeta.
 * - **Por su hora** (§12.4): una foto sin `grabacion` entra en la de ahora o una acabada si cae dentro,
 *   o en cualquier grabación guardada de este teléfono ([PorSuHora.buscarPorHora]); si no, [NINGUNA].
 *
 * Sin Android: lo prueban `GrabacionesDelMovilTest` y, contra el PC de verdad, `FotosPorSuHoraConElPcTest`.
 */
class GrabacionesDelMovil(
    /** Donde espera lo que llega antes de que se guarde la clase. */
    private val cola: File,
    /** El nombre de este teléfono («Móvil de Max»). */
    var aparato: String,
    /** El código de este teléfono (`K7Q2`): el `aparato` del reloj de sus grabaciones. */
    var codigo: String = ""
) {
    /** Una foto o un clip llegado, esperando a que la clase se guarde. */
    data class Llegada(
        val ms: Long, val duracionMs: Long, val clip: Boolean, val fichero: File, val nombre: String,
        /** Del aparato que la hizo (su nombre). */
        val de: String,
        val tomadaEn: Long,
        /** El uid de la foto en su aparato (la `clave`): con él no entra dos veces. */
        val clave: String
    )

    /** Lo que acaba de llegar, para el aviso («Captura del PC · 12:34»). */
    data class Recibida(val ms: Long, val clip: Boolean, val de: String)

    /**
     * **Dónde quedó una clase guardada**: el disco del teléfono, el chat, su nota y su carpeta, y la
     * hora sin repetir de los mensajes nuevos.
     */
    data class Destino(val disco: Disco, val proyecto: String?, val voz: String?, val carpeta: String?, val hora: () -> Long = System::currentTimeMillis)

    private class Grabacion(
        val id: String, val titulo: String, val reloj: RelojDeGrabacion,
        var duracion: Long? = null, val cola: MutableList<Llegada> = ArrayList(), var destino: Destino? = null, var recibidas: Int = 0
    ) {
        fun tope(ahora: Long): Long = (duracion ?: reloj.duracion(ahora)).coerceAtLeast(0)
    }

    private var actual: Grabacion? = null
    private val acabadas = ArrayList<Grabacion>()
    private val desfases = HashMap<String, Long>()
    private val nuevas = ArrayList<Recibida>()

    /** Si se anuncia y se aceptan capturas. */
    var encendida = true

    /** **Dónde buscar las grabaciones ya guardadas** de este teléfono (por su id o su hora), si se sabe. */
    var almacen: Disco? = null

    /** El id de la que está en curso. */
    val enCurso: String? get() = actual?.id

    /** Lo llegado a la que está en curso. */
    val recibidas: Int get() = actual?.recibidas ?: 0

    /** **Empieza una grabación** con id [id], que ya va por [ms]. La que hubiera en curso se da por acabada. */
    fun empezar(id: String, titulo: String, ahora: Long, ms: Long = 0) {
        acabar(ahora)
        actual = Grabacion(id, titulo, RelojDeGrabacion.empezar(ahora, ms))
    }

    /** Con lo que dice el cronómetro. Devuelve si cambió la pausa (para avisar al PC). */
    fun seguirA(ahora: Long, ms: Long, pausa: Boolean): Boolean {
        val g = actual ?: return false
        val antes = g.reloj.enPausa
        g.reloj.seguirA(ahora, ms, pausa, CamaraRemota.TOLERANCIA_MS)
        return antes != g.reloj.enPausa
    }

    /** **Acaba la que está en curso.** Lo suyo queda en la cola hasta [guardada]. */
    fun acabar(ahora: Long) {
        val g = actual ?: return
        g.reloj.acabar(ahora)
        actual = null
        acabadas.add(0, g)
        while (acabadas.size > ACABADAS) tirarCola(acabadas.removeAt(acabadas.size - 1))
    }

    /** **La grabación se tiró** sin guardarse: lo suyo también. */
    fun tirar() {
        val g = actual?.also { actual = null } ?: acabadas.firstOrNull { it.destino == null }?.also { acabadas.remove(it) } ?: return
        tirarCola(g)
    }

    /**
     * **El reloj de pared de la grabación**, como va en la nota (`reloj`), visto en [ahora]: el de la que
     * está en curso, o de la última acabada sin guardar.
     */
    fun relojDePared(ahora: Long): RelojDePared? {
        val g = actual ?: acabadas.firstOrNull { it.destino == null } ?: return null
        return relojDe(g, ahora)
    }

    private fun relojDe(g: Grabacion, ahora: Long) = RelojDePared(
        aparato = codigo, inicio = g.reloj.desde, fin = g.reloj.fin ?: 0L, pausas = g.reloj.pausasAlCable(),
        vistoEn = if (g.reloj.fin != null) 0L else ahora, grabacion = g.id
    )

    /**
     * **La clase se guardó** en [destino] y su nota dura [duracion]: lo que esperaba entra ya, en su
     * minuto, y el reloj de pared entra en la nota si aún no lo lleva (el del diario de la grabación,
     * con sus huecos, manda si ya está). Va a la última acabada sin guardar (o a la de ahora). Devuelve
     * cuántas entraron.
     */
    fun guardada(destino0: Destino, duracion: Long?, ahora: Long): Int {
        // Sin carpeta dicha: la de la grabación de esa nota, si la hay.
        val destino = if (destino0.carpeta != null || destino0.voz == null) destino0 else destino0.copy(
            carpeta = destino0.disco.leerMensajes().firstOrNull { it.proyecto == destino0.proyecto && CarpetaDePaginas.deMensaje(it)?.voz == destino0.voz }?.id
        )
        if (acabadas.all { it.destino != null }) acabar(ahora)
        val g = acabadas.firstOrNull { it.destino == null } ?: return 0
        if (duracion != null && duracion > 0) g.duracion = duracion
        val tope = g.tope(ahora)
        var hechas = 0
        val quedan = ArrayList<Llegada>()
        for (l0 in g.cola.toList()) {
            val ms = l0.ms.coerceIn(0, tope)
            val l = l0.copy(ms = ms, duracionMs = minOf(l0.duracionMs, (tope - ms).coerceAtLeast(0)))
            if (runCatching { adjuntar(destino, g.titulo, l) }.isSuccess) { ficha(l.fichero).delete(); hechas++ } else quedan += l
        }
        g.cola.clear()
        g.cola += quedan
        val voz = destino.voz
        if (voz != null) {
            val reloj = relojDe(g, ahora).copy(fin = g.reloj.fin ?: ahora, vistoEn = 0)
            destino.disco.cambiarMensajes { lista ->
                val i = lista.indexOfFirst { it.proyecto == destino.proyecto && it.id == voz }
                if (i < 0 || lista[i].reloj != null) null else lista.toMutableList().apply { set(i, lista[i].copy(reloj = reloj.aJson())) }
            }
        }
        g.destino = destino
        return hechas
    }

    /**
     * **Lo que se anuncia al PC** (lo que va en `suya`): la de ahora; si no hay, la última acabada (para
     * que sepa que acabó). Null apagada o sin ninguna.
     */
    fun anuncio(ahora: Long): CamaraRemota.Anuncio? {
        if (!encendida) return null
        val (g, activa) = actual?.let { it to true } ?: (acabadas.firstOrNull() ?: return null) to false
        return CamaraRemota.Anuncio(
            grabacion = g.id, titulo = g.titulo, aparato = aparato, activa = activa, enPausa = g.reloj.enPausa,
            msActual = g.reloj.msEn(ahora), duracion = if (activa) 0 else g.tope(ahora), desde = g.reloj.desde,
            pausas = g.reloj.pausasAlCable()
        )
    }

    /** Lo que va adelantado el reloj de [aparato] (su id), medido al anunciarle la grabación. */
    fun medir(aparato: String, desfase: Long) { desfases[aparato] = desfase }

    private fun buscar(id: String?): Grabacion? = if (id.isNullOrEmpty()) null else (listOfNotNull(actual) + acabadas).firstOrNull { it.id == id }

    /** La grabación guardada de este teléfono que se anunció con el id [grabacion]. */
    private fun guardadaConId(grabacion: String?): Pair<String?, PorSuHora.Grabacion>? {
        val d = almacen ?: return null
        return PorSuHora.buscarPorId(d.leerMensajes(), grabacion.orEmpty(), codigo)
    }

    /** **Antes del «vale»**: que la grabación exista aquí y se acepten capturas. El motivo del «no», o null. */
    fun aceptar(grabacion: String?, tomadaEn: Long, mime: String, bytes: Long): String? {
        CamaraRemota.aceptar(grabacion, tomadaEn, mime, bytes)?.let { return it }
        if (!encendida) return "La cámara del PC está apagada en el teléfono"
        if (buscar(grabacion) == null && tomadaEn <= 0 && guardadaConId(grabacion) == null) return "Esa grabación ya no está en el teléfono"
        return null
    }

    /** Lo que llega: lo de un `suelto` con `destino: "grabacion"`. */
    data class Foto(
        val nombre: String, val mime: String, val bytes: Long, val grabacion: String?, val ms: Long?,
        val tomadaEn: Long, val reloj: Long, val duracionMs: Long, val clave: String
    ) {
        val clip: Boolean get() = CamaraRemota.esClip(mime)

        companion object {
            fun de(p: Protocolo.Peticion) = Foto(
                p.nombre ?: "captura.png", p.mime ?: "application/octet-stream", p.bytes, p.grabacion, p.ms,
                p.tomadaEn, p.reloj, p.duracionMs, p.clave.orEmpty()
            )
        }
    }

    /**
     * **Una captura llegó** a [fichero] (temporal) de [de] ([deId], para su desfase). Entra ya si la
     * clase está guardada; si no, espera en la cola. Devuelve su minuto; lanza
     * [IllegalStateException] con el motivo si no se puede.
     */
    fun recibir(p: Foto, fichero: File, deId: String, de: String, ahora: Long): Long {
        aceptar(p.grabacion, p.tomadaEn, p.mime, p.bytes)?.let { throw IllegalStateException(it) }
        val g = buscar(p.grabacion) ?: return recibirPorSuHora(p, fichero, deId, de, ahora)
        val tope = g.tope(ahora)
        val ms = CamaraRemota.msDeLaFoto(g.reloj, ahora, tope, p.ms, p.tomadaEn, p.reloj, desfases[deId])
        val duracionMs = if (p.clip) CamaraRemota.duracionDelClip(p.duracionMs, ms, tope) else 0L
        var l = Llegada(ms, duracionMs, p.clip, fichero, nombreDe(p, g.recibidas + 1), de, p.tomadaEn, p.clave)
        val d = g.destino
        if (d != null) {
            adjuntar(d, g.titulo, l)
        } else {
            val dir = File(cola, sano(g.id)).apply { mkdirs() }
            val sitio = File(dir, "${g.cola.size}-${l.nombre}")
            if (!fichero.renameTo(sitio)) { fichero.copyTo(sitio, overwrite = true); fichero.delete() }
            l = l.copy(fichero = sitio)
            // La ficha, en el acto: un cierre de golpe antes de guardar la clase no se lleva su minuto.
            runCatching { ficha(sitio).writeText("""{"ms":${l.ms},"duracionMs":${l.duracionMs},"clip":${l.clip},"de":${kotlinx.serialization.json.JsonPrimitive(de)},"tomadaEn":${l.tomadaEn}}""") }
            g.cola += l
        }
        g.recibidas++
        nuevas += Recibida(ms, p.clip, de)
        return ms
    }

    /**
     * **Una foto que no sabe de qué grabación es** (o de una que ya no se recuerda): por su hora, pasada
     * a este reloj con su desfase. En la de ahora o una acabada si cae dentro; si no, en una guardada.
     */
    private fun recibirPorSuHora(p: Foto, fichero: File, deId: String, de: String, ahora: Long): Long {
        val desfase = if (p.reloj != 0L) p.reloj else desfases[deId] ?: 0L
        val t = p.tomadaEn - desfase
        val enMemoria = (listOfNotNull(actual) + acabadas).mapNotNull { g ->
            val ms = PorSuHora.lugar(t, relojDe(g, ahora), g.duracion, PorSuHora.MARGEN_MS) ?: return@mapNotNull null
            Triple(g, g.reloj.desde, ms)
        }.maxByOrNull { it.second }
        if (enMemoria != null) return recibir(p.copy(grabacion = enMemoria.first.id, ms = enMemoria.third), fichero, deId, de, ahora)
        val disco = almacen ?: throw IllegalStateException(NINGUNA)
        val todos = disco.leerMensajes()
        val porId = guardadaConId(p.grabacion)?.let { (chat, g) ->
            val duracion = g.duracionMs.takeIf { it > 0 }
            val ms = (p.ms ?: (if (p.tomadaEn > 0) PorSuHora.lugar(t, g.reloj, duracion) else null) ?: g.duracionMs)
                .coerceIn(0, duracion ?: Long.MAX_VALUE)
            Triple(chat, g, ms)
        }
        val (chat, g, ms) = porId ?: PorSuHora.buscarPorHora(todos, t, codigo) ?: throw IllegalStateException(NINGUNA)
        val tope = maxOf(PorSuHora.duracion(g.reloj, ahora), g.duracionMs)
        val l = Llegada(
            ms, if (p.clip) CamaraRemota.duracionDelClip(p.duracionMs, ms, tope) else 0L, p.clip, fichero, nombreDe(p, 1),
            de, p.tomadaEn, p.clave
        )
        adjuntar(Destino(disco, chat, g.voz, g.carpeta), g.titulo, l)
        nuevas += Recibida(ms, p.clip, de)
        return ms
    }

    /** Lo llegado desde la última vez que se preguntó. */
    fun tomarNuevas(): List<Recibida> = ArrayList(nuevas).also { nuevas.clear() }

    private fun tirarCola(g: Grabacion) {
        for (l in g.cola) { l.fichero.delete(); ficha(l.fichero).delete() }
        g.cola.clear()
        File(cola, sano(g.id)).delete()
    }

    companion object {
        /** Cuántas grabaciones acabadas se recuerdan para lo que llegue tarde. */
        const val ACABADAS = 4
        const val NINGUNA = CamaraRemota.NINGUNA
        /** De dónde salió una captura del PC en la grabación del teléfono (§13). */
        const val DE_PANTALLA = "pantalla"

        private fun ficha(f: File) = File(f.parentFile, f.name + ".json")
        private fun sano(id: String) = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(80)

        /** El nombre con que entra: el suyo si cuadra; si no, uno de serie. */
        internal fun nombreDe(p: Foto, n: Int): String {
            val suyo = Envio.nombreSano(p.nombre)
            val ext = suyo.substringAfterLast('.', "").lowercase()
            val bueno = if (p.clip) ext == "mp4" else ext in setOf("png", "jpg", "jpeg", "webp")
            return if (bueno && suyo.isNotBlank()) suyo else "${if (p.clip) "clip" else "captura"}-$n.${if (p.clip) "mp4" else "png"}"
        }

        /**
         * **Pone una captura en la clase guardada** [destino]: el mensaje (el fichero copiado a los
         * adjuntos, tal cual llegó), su enlace a la nota, su sitio en `capturas` y en la carpeta. La misma
         * foto otra vez (por su `clave`) no se copia de nuevo. Devuelve el id del mensaje.
         */
        fun adjuntar(destino: Destino, titulo: String, l: Llegada): String {
            val disco = destino.disco
            val yo = disco.identidad.leer().yo.codigo
            var id = ""
            disco.cambiarMensajes { lista ->
                val todos = lista.toMutableList()
                val delChat = lista.filter { it.proyecto == destino.proyecto }
                val ya = yaLlego(todos, delChat, destino, l)
                if (ya != null) {
                    id = ya
                    l.fichero.delete()
                    return@cambiarMensajes todos
                }
                val cuando = generateSequence(destino.hora()) { it + 1 }.first { c -> delChat.none { it.id == c.toString() } }
                val dir = File(disco.filesDir, "guardados").apply { mkdirs() }
                val final = File(dir, "${cuando}_${l.nombre}")
                if (!l.fichero.renameTo(final)) { l.fichero.copyTo(final, overwrite = true); l.fichero.delete() }
                val rotulo = "🖥 ${if (l.clip) "▶" else "⏱"} ${CamaraRemota.minuto(l.ms)}"
                val voz = destino.voz
                val numero = maxOf(delChat.maxOfOrNull { it.numero } ?: 0, delChat.size) + 1
                val m = Codigos.sellar(
                    Mensaje(
                        id = cuando.toString(), cuando = cuando, clase = if (l.clip) Clase.ARCHIVO else Clase.IMAGEN,
                        texto = if (titulo.isBlank()) rotulo else "$rotulo · ${titulo.trim()}",
                        ruta = final.absolutePath, nombre = l.nombre, bytes = final.length(), proyecto = destino.proyecto,
                        numero = numero, duracionMs = if (l.clip) l.duracionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                        enGrabacion = voz?.let { if (l.clip) Presencial.enlaceDelClip(it, l.ms, l.duracionMs) else GrabarClase.enGrabacion(it, l.ms) },
                        fuente = DE_PANTALLA, deAparato = l.de.ifBlank { null }, tomadaEn = l.tomadaEn.takeIf { it > 0 },
                        fotoUid = l.clave.ifBlank { null }
                    ),
                    yo
                )
                todos += m
                id = m.id
                if (voz != null) todos.indexOfFirst { it.proyecto == destino.proyecto && it.id == voz }.takeIf { it >= 0 }?.let { i ->
                    val n = todos[i]
                    todos[i] = if (l.clip) n.copy(clips = Presencial.ponerClip(n.clips, l.ms, l.duracionMs, m.id) ?: n.clips)
                    else n.copy(capturas = GrabarClase.ponerCaptura(n.capturas, l.ms, m.id) ?: n.capturas)
                }
                destino.carpeta?.let { c -> todos.indexOfFirst { it.proyecto == destino.proyecto && it.id == c } }?.takeIf { it >= 0 }?.let { i ->
                    CarpetaDePaginas.deMensaje(todos[i])?.anadirEnSuMinuto(m.id, rotulo, l.ms)?.let { k -> todos[i] = todos[i].copy(texto = k.escribir()) }
                }
                todos
            }
            return id
        }

        /**
         * **Si la foto [l] ya está en el chat**: copiada antes (su `fotoUid` es la `clave`) o llegada por la
         * sincronización (su uid es la `clave`; entonces se pone esa en la grabación, sin copiarla). El id
         * del mensaje que la tiene.
         */
        private fun yaLlego(todos: MutableList<Mensaje>, delChat: List<Mensaje>, destino: Destino, l: Llegada): String? {
            val clave = l.clave.trim()
            if (clave.isEmpty()) return null
            delChat.firstOrNull { it.fotoUid == clave }?.let { return it.id }
            val m = delChat.firstOrNull { !l.clip && it.uid == clave } ?: return null
            val voz = destino.voz
            if (voz != null) {
                val f = PorSuHora.FotoConHora(m.id, Codigos.unico(m), m.aparato.orEmpty(), l.tomadaEn)
                PorSuHora.colocar(todos, destino.proyecto, PorSuHora.Grabacion(voz = voz, carpeta = destino.carpeta), f, l.ms, false, true)
            }
            return m.id
        }
    }
}
