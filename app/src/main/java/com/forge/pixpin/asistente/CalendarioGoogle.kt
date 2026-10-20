package com.forge.pixpin.asistente

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.TimeZone

/**
 * **Tu Google Calendar en PixPin** (PC `calendario_google.rs`, 18-oct-2026), solo para ver: los
 * eventos salen en la rejilla del horario, avisan con la misma llamada que las clases y el asistente
 * contesta «¿qué tengo hoy?».
 *
 * La red va aparte y cada 15 minutos (y al conectar): lo leído se guarda en `calendario-google.json`
 * (la misma forma que el PC: `{leido, eventos}`), así el horario y los avisos lo tienen al instante y
 * sin internet. Quien lo lee ([eventosEntre]) nunca espera a Google.
 */
object CalendarioGoogle {
    data class Evento(
        val id: String, val calendario: String, val titulo: String,
        /** UTC; en uno de todo el día, la medianoche UTC de su fecha. */
        val inicioMs: Long, val finMs: Long, val todoElDia: Boolean,
        val lugar: String, val color: String, val enlace: String
    )

    const val CADA_MS = 15 * 60_000L
    private const val ATRAS_MS = 2 * 86_400_000L
    private const val ADELANTE_MS = 21 * 86_400_000L
    private const val DIA = 86_400_000L

    @Volatile private var cache: Pair<Long, List<Evento>>? = null
    @Volatile var version = 0L
        private set

    private fun fichero(c: Context) = File(c.filesDir, "calendario-google.json")

    fun deJson(v: JsonArray): List<Evento> = v.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        Evento(s("id"), s("calendario"), s("titulo"), l("inicio"), l("fin"), (o["todo_el_dia"] as? JsonPrimitive)?.booleanOrNull == true,
            s("lugar"), s("color"), s("enlace"))
    }

    private fun cargar(c: Context): Pair<Long, List<Evento>> {
        cache?.let { return it }
        val leido = runCatching {
            val o = Json.parseToJsonElement(fichero(c).readText()) as JsonObject
            ((o["leido"] as? JsonPrimitive)?.longOrNull ?: 0L) to deJson(o["eventos"] as JsonArray)
        }.getOrDefault(0L to emptyList())
        cache = leido
        return leido
    }

    /** Si está conectado (lo dice lo guardado; se mira al leer, no en cada pintado). */
    @Volatile var conectado: Boolean = false

    /** **Los eventos que tocan** `[desde, hasta)` (ms UTC), de lo guardado. */
    fun eventosEntre(c: Context, desdeUtc: Long, hastaUtc: Long): List<Evento> =
        if (!conectado && !fichero(c).isFile) emptyList() else cargar(c).second.filter { it.inicioMs < hastaUtc && it.finMs > desdeUtc }

    fun evento(c: Context, id: String): Evento? = cargar(c).second.firstOrNull { it.id == id }

    fun leidoMs(c: Context) = cargar(c).first

    /** **Una lectura**: de Google al caché y al disco; si cambió, se rehacen los avisos. */
    fun leer(c: Context): Result<Int> = runCatching {
        val puente = ConexionesDelTelefono(c.applicationContext)
        val estado = puente.estado(com.forge.pixpin.ia.ConexionesNativas.CALENDAR).getOrThrow() as JsonObject
        conectado = (estado["conectado"] as? JsonPrimitive)?.booleanOrNull == true
        if (!conectado) return@runCatching 0
        val ahora = System.currentTimeMillis()
        val v = puente.eventos(ahora - ATRAS_MS, ahora + ADELANTE_MS).getOrThrow()
        val eventos = deJson(v)
        val cambiaron = cargar(c).second != eventos
        val tmp = File(fichero(c).path + ".parte")
        tmp.writeText(buildJsonObject { put("leido", ahora); put("eventos", v) }.toString())
        tmp.renameTo(fichero(c))
        cache = ahora to eventos
        if (cambiaron) {
            version++
            runCatching { com.forge.pixpin.pin.RecordatoriosDelHorario.reprogramar(c) }
        }
        eventos.size
    }

    /** Vuelve a leer el fichero la próxima vez (otro camino lo cambió; las pruebas). */
    fun olvidarCache() { cache = null }

    /** Lo leído se olvida al desconectar (los avisos también). */
    fun olvidar(c: Context) {
        conectado = false
        fichero(c).delete()
        cache = 0L to emptyList()
        version++
        runCatching { com.forge.pixpin.pin.RecordatoriosDelHorario.reprogramar(c) }
    }

    private fun aLocal(utc: Long) = utc + TimeZone.getDefault().getOffset(utc)

    /** `16:00–17:30` (o «todo el día») en hora local. */
    fun tramoLegible(e: Evento): String {
        if (e.todoElDia) return "todo el día"
        fun hm(utc: Long): String { val l = Math.floorMod(aLocal(utc), DIA) / 60_000; return "${l / 60}:${"%02d".format(l % 60)}" }
        return "${hm(e.inicioMs)}–${hm(e.finMs)}"
    }

    /** Del día desde 1970 a (año, mes, día), el `civil` del PC. */
    fun civil(dias: Long): Triple<Long, Int, Int> {
        val z = dias + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }

    fun diasDesde1970(a: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) a - 1 else a
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** **Lo que se le cuenta al asistente** de `[desde, hasta)` (ms locales), como el PC. */
    fun paraElModelo(c: Context, desdeLocal: Long, hastaLocal: Long, ahora: Long = System.currentTimeMillis()): String {
        if (!conectado && !fichero(c).isFile) return "Google Calendar no esta conectado: el usuario lo conecta en Ajustes → Asistente → Conexiones (Conectar Google Calendar)."
        val desfase = aLocal(ahora) - ahora
        val evs = eventosEntre(c, desdeLocal - desfase, hastaLocal - desfase)
        val leido = leidoMs(c)
        val hace = if (leido == 0L) "aun no se ha leido (se lee en unos segundos)" else "leido hace ${maxOf(ahora - leido, 0) / 60_000} min"
        if (evs.isEmpty()) return "No hay eventos en Google Calendar en ese tramo ($hace)."
        val out = StringBuilder("${evs.size} eventos de Google Calendar ($hace):\n")
        for (e in evs) {
            val dia = Math.floorDiv(if (e.todoElDia) e.inicioMs else aLocal(e.inicioMs), DIA)
            val (a, m, d) = civil(dia)
            val semana = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")[Math.floorMod(dia + 3, 7L).toInt()]
            out.append("- $semana ${"%02d".format(d)}/${"%02d".format(m)}/$a ${tramoLegible(e)} «${e.titulo}»")
            if (e.lugar.isNotEmpty()) out.append(" en ${e.lugar}")
            out.append(" (${e.calendario})\n")
        }
        return out.toString()
    }

    /**
     * **«¿Qué tengo hoy?»** (PC `ver_calendario`): los días pedidos (`desde`: hoy, mañana, un día de la
     * semana, «semana» o AAAA-MM-DD; `dias`: cuántos), de lo ya leído.
     */
    fun verCalendario(c: Context, args: JsonObject, ahora: Long = System.currentTimeMillis()): String {
        val hoy = Math.floorDiv(aLocal(ahora), DIA)
        val desde = (args["desde"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "hoy"
        var dias = ((args["dias"] as? JsonPrimitive)?.longOrNull ?: 1L).coerceIn(1, 31)
        // (El día 0, el 1-1-1970, fue jueves: lunes = 0.)
        fun semanaDe(d: Long) = Math.floorMod(d + 3, 7L)
        val nombres = listOf("lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo")
        val sinTildes = desde.replace('á', 'a').replace('é', 'e')
        val primero = when (sinTildes) {
            "", "hoy" -> hoy
            "mañana", "manana" -> hoy + 1
            "pasado mañana", "pasado manana", "pasado" -> hoy + 2
            "semana", "esta semana" -> { dias = maxOf(dias, 7); hoy - semanaDe(hoy) }
            "semana que viene", "proxima semana", "la semana que viene" -> { dias = maxOf(dias, 7); hoy - semanaDe(hoy) + 7 }
            else -> {
                val sin = desde.replace("sábado", "sabado").replace("miércoles", "miercoles")
                val k = nombres.indexOfFirst { sin.contains(it) }
                if (k >= 0) hoy + Math.floorMod(k - semanaDe(hoy), 7L)
                else {
                    val p = desde.split('-', '/').mapNotNull { it.trim().toLongOrNull() }
                    when {
                        p.size == 3 && p[0] > 1900 -> diasDesde1970(p[0], p[1].toInt(), p[2].toInt())
                        p.size == 3 && p[2] > 1900 -> diasDesde1970(p[2], p[1].toInt(), p[0].toInt())
                        else -> hoy
                    }
                }
            }
        }
        return paraElModelo(c, primero * DIA, (primero + dias) * DIA, ahora)
    }
}
