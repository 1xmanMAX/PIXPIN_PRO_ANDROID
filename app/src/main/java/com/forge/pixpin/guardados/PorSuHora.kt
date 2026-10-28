package com.forge.pixpin.guardados

import com.forge.pixpin.sincro.CamaraRemota
import com.forge.pixpin.sincro.Codigos
import java.io.File
import java.util.TreeMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **Las fotos por su hora** (28-oct-2026, `crates/pixpin-proyecto/src/por_su_hora.rs` del PC,
 * copiado regla a regla con sus pruebas en `PorSuHoraTest`).
 *
 * El usuario, en el PC: «que se puedan tomar fotos normalmente aunque NO se esté conectado; cuando
 * logre conectarse, como la foto lleva su hora y la grabación también guarda su hora, si la hora de
 * la foto está fuera de la grabación, que la ignore; si está dentro, que calcule dónde va y la ponga
 * ahí». Y: «agregar COLABORADORES: personas que pueden tomar fotos en cualquier momento».
 *
 * **La hora de pared manda**: la grabación guarda en su nota de voz cuándo empezó, cuándo acabó y
 * sus pausas en el reloj de quien graba ([RelojDePared], `reloj`), y cada foto lleva la hora en que
 * se hizo ([Mensaje.tomadaEn]) y de qué aparato es ([Mensaje.aparato]). Llegue por donde llegue (la
 * sincronización normal o la del diario, «Enviar por Wi‑Fi», un chat compartido o la cámara en
 * directo), **el aparato que grabó** hace la cuenta:
 *
 * 1. **La hora corregida** ([horaCorregida]): la de la foto menos lo que iba adelantado el reloj de
 *    su aparato, con la medida más cercana a esa hora ([Desfases.cercano]). Sin medida, tal cual y
 *    la foto queda **«hora sin comprobar»**.
 * 2. **El minuto** ([msEnLaGrabacion], [lugar]): fuera de la ventana, nada; dentro, lo corrido sin
 *    lo pausado. En una pausa, el minuto en que se paró. 30 s de margen en los bordes.
 * 3. **Quién puede aportar** ([admitido]): lo de mis aparatos siempre; lo de otra persona, si la
 *    invitaron (`colaboradores`, su código o [TODOS]).
 * 4. **Una sola vez** ([decidir], [colocar]): en `capturas` de la nota (con `aparato`, `uid` y
 *    `horaSinComprobar`) y en la carpeta de la grabación, en su minuto. Lo ya puesto (por su id, su
 *    uid o el `fotoUid` de la copia de la cámara en directo) no se repite.
 *
 * **Solo coloca el aparato que grabó** (`reloj.aparato`), y **la foto de otra persona no se toca**:
 * lo que dice dónde va está en la nota, que es de quien graba.
 *
 * Todo es puro (listas de [Mensaje] de entrada y de salida): lo que lee y escribe en disco está en
 * `sincro/FotosPorSuHora.kt`.
 */
object PorSuHora {

    /** Lo que se deja pasar en los bordes de la grabación (el error de un reloj medido y el de la nota). */
    const val MARGEN_MS = 30_000L
    /** Cuántas medidas del desfase se recuerdan de cada aparato. */
    const val MEDIDAS_MAXIMAS = 32
    /** En `colaboradores`: todos los del chat compartido. */
    const val TODOS = "*"
    /** En una captura de la nota: su hora no se pudo comprobar. */
    const val SIN_COMPROBAR = "horaSinComprobar"

    // ------------------------------------------------------------ el reloj

    /** Donde acaba la ventana: el fin, o lo último visto si se cortó. Null: sigue abierta. */
    fun finalDe(r: RelojDePared): Long? = when {
        r.fin > 0 -> maxOf(r.fin, r.inicio)
        r.vistoEn > 0 -> maxOf(r.vistoEn, r.inicio)
        else -> null
    }

    private fun tramos(r: RelojDePared): List<Pair<Long, Long>> =
        (r.pausas + r.huecos).mapNotNull { t -> if (t.size >= 2) t[0] to t[1] else null }

    /** **Lo corrido hasta [t] sin lo pausado ni los huecos** (sin mirar si está dentro de la ventana). */
    fun msHasta(r: RelojDePared, t: Long): Long {
        val tope = finalDe(r)?.let { minOf(t, it) } ?: t
        if (tope <= r.inicio) return 0
        val pausado = tramos(r).sumOf { (d, h) ->
            val hasta = if (h == 0L) tope else minOf(h, tope)
            (hasta - maxOf(d, r.inicio)).coerceAtLeast(0)
        }
        return (tope - r.inicio - pausado).coerceAtLeast(0)
    }

    /** Lo que duró (o lleva, si sigue, hasta [ahora]), sin las pausas. */
    fun duracion(r: RelojDePared, ahora: Long): Long = msHasta(r, finalDe(r) ?: ahora)

    /** Si [t] cae en una pausa o un hueco: su principio. */
    private fun pausaEn(r: RelojDePared, t: Long): Long? =
        tramos(r).firstOrNull { (d, h) -> t >= d && (h == 0L || t < h) }?.first

    /**
     * **El milisegundo de la grabación en la hora de pared [t]** (ya en el reloj de quien graba).
     * Null fuera de la ventana. Dentro de una pausa, el minuto en que se paró.
     */
    fun msEnLaGrabacion(t: Long, r: RelojDePared): Long? {
        if (r.inicio <= 0 || t < r.inicio) return null
        val f = finalDe(r)
        if (f != null && t > f) return null
        return msHasta(r, pausaEn(r, t) ?: t)
    }

    /**
     * [msEnLaGrabacion] con [margen] en los bordes y sin pasar de [duracion] (la de la nota, si se
     * sabe): lo de justo antes va al 0 y lo de justo después, al final.
     */
    fun lugar(t: Long, r: RelojDePared, duracion: Long?, margen: Long = MARGEN_MS): Long? {
        val tope = duracion?.takeIf { it > 0 } ?: duracion(r, t)
        val ms = msEnLaGrabacion(t, r) ?: when {
            r.inicio > 0 && t < r.inicio && r.inicio - t <= margen -> 0L
            else -> {
                val f = finalDe(r)
                if (f != null && t > f && t - f <= margen) msHasta(r, f) else return null
            }
        }
        return ms.coerceIn(0, tope.coerceAtLeast(0))
    }

    // ------------------------------------------------------------ los desfases

    /** Una medida: cuándo (en mi reloj) y lo que iba adelantado el suyo (negativo: atrasado). */
    @Serializable
    data class Medida(val cuando: Long, val desfase: Long)

    /** **La medida más cercana a la hora [t]**: los relojes se van moviendo. */
    fun desfaseCercano(medidas: List<Medida>, t: Long): Long? =
        medidas.minByOrNull { kotlin.math.abs(it.cuando - t) }?.desfase

    /**
     * **Lo medido de cada aparato**, por su código, como `sincro/desfases.json`:
     * `{"aparatos":{"K7Q2":[{"cuando","desfase"}]}}`. **No viaja**: cada aparato mide los suyos.
     */
    @Serializable
    data class Desfases(val aparatos: Map<String, List<Medida>> = emptyMap()) {

        /** Apunta una medida: dos del mismo minuto se quedan en la última; de cada aparato, las 32 más nuevas. */
        fun medir(codigo: String, cuando: Long, desfase: Long): Desfases {
            if (codigo.isBlank()) return this
            val v = aparatos[codigo].orEmpty().filter { kotlin.math.abs(it.cuando - cuando) >= 60_000 } + Medida(cuando, desfase)
            val orden = v.sortedBy { it.cuando }.takeLast(MEDIDAS_MAXIMAS)
            return Desfases(TreeMap(aparatos + (codigo to orden)))
        }

        /** La medida de [codigo] más cercana a [t]. */
        fun cercano(codigo: String, t: Long): Long? = aparatos[codigo]?.let { desfaseCercano(it, t) }

        fun guardar(fichero: File) {
            fichero.parentFile?.mkdirs()
            val tmp = File(fichero.parentFile, fichero.name + ".tmp")
            tmp.writeText(JSON.encodeToString(serializer(), copy(aparatos = TreeMap(aparatos))))
            if (!tmp.renameTo(fichero)) { fichero.delete(); tmp.renameTo(fichero) }
        }

        companion object {
            fun leer(fichero: File): Desfases =
                runCatching { JSON.decodeFromString(serializer(), fichero.readText()) }.getOrDefault(Desfases())
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ------------------------------------------------------------ la foto

    /**
     * **La hora del EXIF** (`DateTimeOriginal`, `"2026:10:28 10:05:03"`, y `OffsetTimeOriginal`,
     * `"+01:00"`) en ms desde 1970. Sin zona, null: una hora local sin zona no se puede pasar a la de
     * pared.
     */
    fun msDeExif(fecha: String, desfase: String): Long? {
        val f = fecha.trim()
        val corte = f.indexOfFirst { it == ' ' || it == 'T' }
        if (corte < 0) return null
        val d = f.substring(0, corte).split(':', '-').map { it.toLongOrNull() }
        val h = f.substring(corte + 1).split(':').map { it.toLongOrNull() }
        if (d.size < 3 || h.size < 2) return null
        val a = d[0] ?: return null
        val me = d[1] ?: return null
        val di = d[2] ?: return null
        val ho = h[0] ?: return null
        val mi = h[1] ?: return null
        val se = h.getOrNull(2) ?: 0L
        if (me !in 1..12 || di !in 1..31 || ho > 23 || mi > 59 || se > 60) return null
        val z = desfase.trim()
        if (z.isEmpty()) return null
        val (signo, resto) = when {
            z[0] == '+' -> 1L to z.substring(1)
            z[0] == '-' -> -1L to z.substring(1)
            z == "Z" -> 1L to "00:00"
            else -> return null
        }
        val i = resto.indexOf(':')
        val (zh, zm) = if (i >= 0) resto.substring(0, i) to resto.substring(i + 1) else resto to "0"
        val zona = signo * ((zh.toLongOrNull() ?: return null) * 60 + (zm.toLongOrNull() ?: return null))
        // Días desde 1970 de una fecha civil (Howard Hinnant).
        val y = if (me <= 2) a - 1 else a
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (me + 9) % 12
        val doy = (153 * mp + 2) / 5 + di - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val dias = era * 146_097 + doe - 719_468
        return ((dias * 86_400 + ho * 3600 + mi * 60 + se) - zona * 60) * 1000
    }

    /** **Lo que hace falta de una foto** para ponerla por su hora. */
    data class FotoConHora(
        val id: String,
        /** Su código único (el mismo en todos los aparatos). */
        val uid: String,
        /** El código del aparato que la hizo (vacío: de los de antes, mío). */
        val aparato: String,
        /** Su hora, en el reloj de su aparato. */
        val tomadaEn: Long,
        /** Para qué grabación la hizo, si lo sabía. */
        val para: String? = null
    )

    /** La foto de un mensaje: una IMAGEN con su hora (`tomadaEn` o, si falta, la del EXIF). Null lo demás. */
    fun fotoDe(m: Mensaje): FotoConHora? {
        if (m.clase != Clase.IMAGEN || m.id.startsWith(CarpetaDePaginas.PREFIJO_DE_SISTEMA)) return null
        val t = m.tomadaEn?.takeIf { it > 0 } ?: run {
            val f = m.tomadaEnExif ?: return null
            val z = m.desfaseExif ?: return null
            msDeExif(f, z)
        } ?: return null
        return FotoConHora(m.id, Codigos.unico(m), m.aparato.orEmpty().trim(), t, m.paraGrabacion?.takeIf { it.isNotBlank() })
    }

    /** La hora de una foto en el reloj de quien graba; [comprobada] `false`: sin medida, se creyó. */
    data class Hora(val utc: Long, val comprobada: Boolean)

    /**
     * **La hora de la foto pasada al reloj de quien graba** ([dueno], su código): la del mismo
     * aparato, tal cual; la de otro, menos su desfase más cercano a esa hora; sin medida, tal cual y
     * sin comprobar.
     */
    fun horaCorregida(foto: FotoConHora, dueno: String, desfases: Desfases): Hora {
        if (foto.aparato.isEmpty() || foto.aparato == dueno) return Hora(foto.tomadaEn, true)
        val d = desfases.cercano(foto.aparato, foto.tomadaEn) ?: return Hora(foto.tomadaEn, false)
        return Hora(foto.tomadaEn - d, true)
    }

    // ------------------------------------------------------------ la grabación

    /** **Una grabación de un chat que admite fotos por su hora**: su nota, su carpeta y lo que ya tiene dentro. */
    data class Grabacion(
        val voz: String,
        val carpeta: String? = null,
        val reloj: RelojDePared = RelojDePared(),
        /** La de la nota (la del audio de verdad), si se sabe. */
        val duracionMs: Long = 0,
        val colaboradores: List<String> = emptyList(),
        /** Los ids (y uids) de lo que ya está dentro. */
        val ya: MutableSet<String> = HashSet(),
        val titulo: String = ""
    )

    /** Los códigos invitados de una nota (`colaboradores`). */
    fun colaboradoresDe(m: Mensaje): List<String> = colaboradoresDe(m.colaboradores)

    fun colaboradoresDe(a: JsonArray?): List<String> =
        a.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }

    /** **Invita** ([si]) o deja de invitar a [codigo] ([TODOS]: a todos los del chat). Null si no cambió. */
    fun invitar(m: Mensaje, codigo: String, si: Boolean): Mensaje? {
        val c = codigo.trim()
        if (c.isEmpty()) return null
        val v = colaboradoresDe(m)
        if ((c in v) == si) return null
        val nueva = if (si) v + c else v - c
        return m.copy(colaboradores = if (nueva.isEmpty()) null else JsonArray(nueva.map(::JsonPrimitive)))
    }

    /** Si la nota deja aportar a todos los del chat. */
    fun abiertaATodos(m: Mensaje): Boolean = TODOS in colaboradoresDe(m)

    /** **Si lo de [aparato] puede entrar**: lo mío siempre; lo de otra persona, si la invitaron a ella o a todos. */
    fun admitido(g: Grabacion, aparato: String, mios: Set<String>): Boolean =
        aparato.isEmpty() || aparato == g.reloj.aparato || aparato in mios || g.colaboradores.any { it == TODOS || it == aparato }

    private fun texto(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** **Las grabaciones con reloj de un chat** ([mensajes], los de ese chat), con su carpeta y lo que ya tienen dentro. */
    fun grabacionesDe(mensajes: List<Mensaje>): List<Grabacion> {
        val carpetas = mensajes.mapNotNull { m -> CarpetaDePaginas.deMensaje(m)?.takeIf { it.esGrabacion }?.let { m.id to it } }
        val fuid = mensajes.mapNotNull { m -> m.fotoUid?.let { m.id to it } }.toMap()
        return mensajes.filter { it.clase == Clase.VOZ }.mapNotNull { v ->
            val reloj = RelojDePared.de(v) ?: return@mapNotNull null
            val carpeta = carpetas.firstOrNull { it.second.voz == v.id }
            val ya = HashSet<String>()
            GrabarClase.capturasDe(v).forEach { ya += it.id }
            Presencial.clipsDe(v).forEach { ya += it.id }
            v.capturas.orEmpty().forEach { c -> texto((c as? JsonObject)?.get("uid"))?.let { ya += it } }
            carpeta?.second?.paginas?.forEach { ya += it.id }
            ya += ya.mapNotNull { fuid[it] }
            Grabacion(
                voz = v.id, carpeta = carpeta?.first, reloj = reloj, duracionMs = v.duracionMs.toLong().coerceAtLeast(0),
                colaboradores = colaboradoresDe(v), ya = ya,
                titulo = if (v.nombre.isBlank()) v.texto else v.nombre.removeSuffix(".m4a")
            )
        }
    }

    /** Lo que se hace con una foto. */
    sealed class Decision {
        /** Va en la grabación de la nota [voz], en [ms]. */
        data class Colocar(val voz: String, val ms: Long, val sinComprobar: Boolean) : Decision()
        /** Ya está dentro de una (llegó dos veces). */
        object Ya : Decision()
        /** Su hora no cae en ninguna: se queda como un mensaje normal. */
        object Fuera : Decision()
        /** Cae en una, pero quien la hizo no está invitado. */
        object NoAdmitida : Decision()
    }

    /**
     * **Dónde va una foto** entre las grabaciones de su chat que grabó este aparato ([yo]; las demás
     * no se tocan). Si cae en dos, la que empezó después.
     */
    fun decidir(foto: FotoConHora, grabaciones: List<Grabacion>, yo: String, mios: Set<String>, desfases: Desfases): Decision {
        if (grabaciones.any { foto.id in it.ya || foto.uid in it.ya }) return Decision.Ya
        var noAdmitida = false
        var mejor: Triple<Grabacion, Long, Boolean>? = null
        for (g in grabaciones.filter { it.reloj.aparato == yo }) {
            if (foto.para != null && foto.para != g.voz) continue
            val hora = horaCorregida(foto, g.reloj.aparato, desfases)
            val ms = lugar(hora.utc, g.reloj, g.duracionMs.takeIf { it > 0 }, MARGEN_MS) ?: continue
            if (!admitido(g, foto.aparato, mios)) { noAdmitida = true; continue }
            if (mejor == null || g.reloj.inicio > mejor.first.reloj.inicio) mejor = Triple(g, ms, !hora.comprobada)
        }
        val m = mejor
        return when {
            m != null -> Decision.Colocar(m.first.voz, m.second, m.third)
            noAdmitida -> Decision.NoAdmitida
            else -> Decision.Fuera
        }
    }

    // ------------------------------------------------------------ en la lista

    /**
     * **Añade una foto a la lista de la nota** con su autor: `{aparato, horaSinComprobar, id, ms, uid}`
     * (las claves en el orden alfabético en que las escribe el PC). Un id o un uid que ya estaba no
     * se repite: null.
     */
    fun ponerEnLaNota(lista: JsonArray?, ms: Long, foto: FotoConHora, sinComprobar: Boolean): JsonArray? {
        val antes = lista.orEmpty()
        if (antes.any { val o = it as? JsonObject; texto(o?.get("id")) == foto.id || texto(o?.get("uid")) == foto.uid }) return null
        val c = buildJsonObject {
            if (foto.aparato.isNotEmpty()) put("aparato", JsonPrimitive(foto.aparato))
            if (sinComprobar) put(SIN_COMPROBAR, JsonPrimitive(true))
            put("id", JsonPrimitive(foto.id))
            put("ms", JsonPrimitive(ms.coerceAtLeast(0)))
            if (foto.uid.isNotEmpty()) put("uid", JsonPrimitive(foto.uid))
        }
        fun msDe(e: JsonElement) = ((e as? JsonObject)?.get("ms") as? JsonPrimitive)?.longOrNull ?: 0L
        return JsonArray((antes + c).sortedBy(::msDe))
    }

    /** Si la captura [id] de la nota tiene la hora sin comprobar. */
    fun sinComprobar(voz: Mensaje, id: String): Boolean = voz.capturas.orEmpty().any { e ->
        val o = e as? JsonObject
        texto(o?.get("id")) == id && (o?.get(SIN_COMPROBAR) as? JsonPrimitive)?.booleanOrNull == true
    }

    /** De quién es la captura [id] de la nota (el `aparato` de su entrada), si lo dice. */
    fun autorEnLaNota(voz: Mensaje, id: String): String? = voz.capturas.orEmpty().firstNotNullOfOrNull { e ->
        val o = e as? JsonObject
        if (texto(o?.get("id")) == id) texto(o?.get("aparato")) else null
    }

    private fun rotulo(ms: Long) = "⏱ ${CamaraRemota.minuto(ms)}"

    /**
     * **Pone la foto en la grabación** de la nota `g.voz` del chat [chat], en [ms]: en la lista de la
     * nota, en su carpeta y, si la foto es mía ([mia]), con su enlace `enGrabacion` y su fuente.
     * Cambia [todos] en su sitio. Devuelve si cambió algo (nada si ya estaba).
     */
    fun colocar(todos: MutableList<Mensaje>, chat: String?, g: Grabacion, foto: FotoConHora, ms: Long, sinComprobar: Boolean, mia: Boolean): Boolean {
        fun indice(id: String) = todos.indexOfFirst { it.proyecto == chat && it.id == id }
        var cambio = false
        indice(g.voz).takeIf { it >= 0 }?.let { i ->
            ponerEnLaNota(todos[i].capturas, ms, foto, sinComprobar)?.let { todos[i] = todos[i].copy(capturas = it); cambio = true }
        }
        if (mia) indice(foto.id).takeIf { it >= 0 }?.let { i ->
            val m = todos[i]
            if (m.enGrabacion == null) {
                // La de otro aparato mío es «del móvil»; la del que grabó ya dice la suya o nada.
                val fuente = if (m.fuente == null && foto.aparato != g.reloj.aparato) Presencial.DEL_MOVIL else m.fuente
                todos[i] = m.copy(enGrabacion = GrabarClase.enGrabacion(g.voz, ms), fuente = fuente)
                cambio = true
            }
        }
        g.carpeta?.let { c -> indice(c) }?.takeIf { it >= 0 }?.let { i ->
            val k = CarpetaDePaginas.deMensaje(todos[i])?.anadirEnSuMinuto(foto.id, rotulo(ms), ms)
            if (k != null) { todos[i] = todos[i].copy(texto = k.escribir()); cambio = true }
        }
        return cambio
    }

    /**
     * **Repasa un chat**: cada foto con hora que aún no está en ninguna grabación de las que grabó
     * este aparato ([yo]) y cae en una, entra. Devuelve cuántas entraron.
     */
    fun repasar(todos: MutableList<Mensaje>, chat: String?, yo: String, mios: Set<String>, desfases: Desfases): Int {
        val delChat = todos.filter { it.proyecto == chat }
        val grabaciones = grabacionesDe(delChat).filter { it.reloj.aparato == yo }
        if (grabaciones.isEmpty()) return 0
        var hechas = 0
        for (m in delChat) {
            val foto = fotoDe(m) ?: continue
            val d = decidir(foto, grabaciones, yo, mios, desfases) as? Decision.Colocar ?: continue
            val g = grabaciones.firstOrNull { it.voz == d.voz } ?: continue
            val mia = foto.aparato.isEmpty() || foto.aparato in mios
            if (colocar(todos, chat, g, foto, d.ms, d.sinComprobar, mia)) hechas++
            g.ya += foto.id
            g.ya += foto.uid
        }
        return hechas
    }

    /** Si el chat puede tener una grabación con reloj, sin mirar más. */
    private fun puedeTenerReloj(delChat: List<Mensaje>) = delChat.any { it.reloj != null }

    /**
     * **Repasa todos los chats** (los de [todos]) con una grabación de este aparato. Cambia [todos] en
     * su sitio y devuelve los chats donde entró algo, con cuántas.
     */
    fun repasarTodos(todos: MutableList<Mensaje>, yo: String, mios: Set<String>, desfases: Desfases): Map<String?, Int> {
        val hechas = LinkedHashMap<String?, Int>()
        for ((chat, delChat) in todos.groupBy { it.proyecto }) {
            if (!puedeTenerReloj(delChat)) continue
            val n = repasar(todos, chat, yo, mios, desfases)
            if (n > 0) hechas[chat] = n
        }
        return hechas
    }

    /**
     * **«Adjuntar a la grabación…»** a mano: la foto [foto] en la grabación de la nota [voz] del chat
     * [chat], en su minuto si su hora cae dentro y, si no (o no tiene hora), en [msSiNo] (o el
     * final). Devuelve el minuto donde quedó, o null si la foto o la nota ya no están.
     */
    fun aMano(todos: MutableList<Mensaje>, chat: String?, foto: String, voz: String, msSiNo: Long?, mios: Set<String>, desfases: Desfases): Long? {
        val delChat = todos.filter { it.proyecto == chat }
        val m = delChat.firstOrNull { it.id == foto } ?: return null
        val v = delChat.firstOrNull { it.id == voz } ?: return null
        val g = grabacionesDe(delChat).firstOrNull { it.voz == voz } ?: Grabacion(
            voz = voz,
            carpeta = delChat.firstOrNull { CarpetaDePaginas.deMensaje(it)?.voz == voz }?.id,
            duracionMs = v.duracionMs.toLong().coerceAtLeast(0)
        )
        val f = fotoDe(m) ?: FotoConHora(m.id, Codigos.unico(m), m.aparato.orEmpty(), 0)
        val duracion = g.duracionMs.takeIf { it > 0 }
        var ms: Long? = null
        var sin = false
        if (f.tomadaEn > 0 && g.reloj.inicio > 0) {
            val h = horaCorregida(f, g.reloj.aparato, desfases)
            ms = lugar(h.utc, g.reloj, duracion, MARGEN_MS)
            sin = ms != null && !h.comprobada
        }
        val donde = ms ?: (msSiNo ?: g.duracionMs).coerceIn(0, duracion ?: Long.MAX_VALUE)
        val mia = f.aparato.isEmpty() || f.aparato in mios
        colocar(todos, chat, g, f, donde, sin, mia)
        return donde
    }

    /**
     * **Las grabaciones de un chat donde se puede adjuntar a mano la foto [foto]** (`grabaciones_para`
     * del PC): primero las que tienen su hora dentro, luego las demás de la más nueva a la más vieja.
     * El id de la nota y su nombre.
     */
    fun grabacionesPara(todos: List<Mensaje>, chat: String?, foto: String, desfases: Desfases): List<Pair<String, String>> {
        val delChat = todos.filter { it.proyecto == chat }
        val f = delChat.firstOrNull { it.id == foto }?.let(::fotoDe)
        return delChat.filter { it.clase == Clase.VOZ && GrabarClase.esClase(it) }.map { m ->
            val r = RelojDePared.de(m)
            val dentro = f != null && r != null && lugar(horaCorregida(f, r.aparato, desfases).utc, r, null) != null
            Triple(dentro, m.cuando, m.id to (if (m.nombre.isBlank()) m.texto else m.nombre.removeSuffix(".m4a")))
        }.sortedWith(compareByDescending<Triple<Boolean, Long, Pair<String, String>>> { it.first }.thenByDescending { it.second }).map { it.third }
    }

    /**
     * **La grabación guardada de este aparato ([yo]) que se anunció con el id [grabacion]**
     * (`reloj.grabacion`), en cualquier chat: el chat y la grabación.
     */
    fun buscarPorId(todos: List<Mensaje>, grabacion: String, yo: String): Pair<String?, Grabacion>? {
        if (grabacion.isBlank()) return null
        for ((chat, delChat) in todos.groupBy { it.proyecto }) {
            if (!puedeTenerReloj(delChat)) continue
            grabacionesDe(delChat).firstOrNull { it.reloj.aparato == yo && it.reloj.grabacion == grabacion }?.let { return chat to it }
        }
        return null
    }

    /**
     * **La grabación guardada de este aparato ([yo]) donde cae la hora [t]** (ya en su reloj), en
     * cualquier chat: el chat, la grabación y el minuto. La que empezó después, si caben dos.
     */
    fun buscarPorHora(todos: List<Mensaje>, t: Long, yo: String): Triple<String?, Grabacion, Long>? {
        var mejor: Triple<String?, Grabacion, Long>? = null
        for ((chat, delChat) in todos.groupBy { it.proyecto }) {
            if (!puedeTenerReloj(delChat)) continue
            for (g in grabacionesDe(delChat)) {
                if (g.reloj.aparato != yo) continue
                val ms = lugar(t, g.reloj, g.duracionMs.takeIf { it > 0 }, MARGEN_MS) ?: continue
                if (mejor == null || g.reloj.inicio > mejor.second.reloj.inicio) mejor = Triple(chat, g, ms)
            }
        }
        return mejor
    }
}
