package com.forge.pixpin.pin

import android.content.Context
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.mini.Horario
import java.io.File
import java.util.TimeZone

/**
 * **Las alarmas del horario de clases** ([Horario], PC `horario.rs`): con el mismo despertador que
 * los recordatorios ([Recordatorios]). Se rehacen enteras —los avisos de los próximos
 * [Horario.DIAS_A_LA_VISTA] días— al arrancar la app (también tras reiniciar el teléfono), al guardar
 * el horario y cada vez que suena una: así la agenda va siempre una semana por delante.
 *
 * Al sonar sale la **llamada secreta** ([LlamadaSecretaActivity]) con la asignatura, su hora y su
 * aula, la nota de voz de la clase si es para esta sesión y lo apuntado con `>` leído en voz alta.
 * «Volver a llamar en» deja un pospuesto (`<id>|<ms>`) que se guarda aparte hasta que suena.
 *
 * Trabajo de disco. Solo de este aparato: el horario no viaja, como en el PC.
 */
object RecordatoriosDelHorario {

    private const val APUNTADAS = "alarmas-del-horario.txt"
    private const val POSPUESTAS = "horario-pospuestas.txt"
    private val cerrojo = Any()

    private fun msLocales(utc: Long) = utc + TimeZone.getDefault().getOffset(utc)

    private fun utcDe(local: Long): Long {
        // El desfase de esa hora local (el del verano puede ser otro que el de ahora).
        val z = TimeZone.getDefault()
        val primero = local - z.getOffset(local)
        return local - z.getOffset(primero)
    }

    /** Los pospuestos que aún no han sonado: id → cuándo (UTC). */
    private fun pospuestas(files: File, ahora: Long): Map<String, Long> =
        runCatching {
            File(files, POSPUESTAS).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { l ->
                val (id, cuando) = l.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                cuando.toLongOrNull()?.takeIf { it > ahora - 60_000 }?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())

    private fun guardarPospuestas(files: File, p: Map<String, Long>) {
        runCatching { File(files, POSPUESTAS).writeText(p.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
    }

    fun reprogramar(context: Context, ahora: Long = System.currentTimeMillis()): Unit = synchronized(cerrojo) {
        val app = context.applicationContext
        val local = msLocales(ahora)
        val h = Horario.deDisco(app.filesDir, Momento.deMsLocales(local))
        // Los eventos de Google Calendar (PC 18-oct), con la misma antelación que las clases. Los de
        // todo el día no llaman.
        val deGoogle = com.forge.pixpin.asistente.CalendarioGoogle.eventosEntre(app, ahora, ahora + Horario.DIAS_A_LA_VISTA * 86_400_000L)
            .filter { !it.todoElDia }
            .map { e -> "${Horario.PREFIJO}gcal:${e.id}:${e.titulo}" to e.inicioMs - h.antelacion * 60_000L }
            .filter { it.second > ahora }
        val alarmas = Horario.avisos(h, local, Horario.DIAS_A_LA_VISTA).associate { it.id to utcDe(it.cuando) } + deGoogle +
            pospuestas(app.filesDir, ahora)
        val apuntadas = File(app.filesDir, APUNTADAS)
        val antes = runCatching { apuntadas.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        (antes - alarmas.keys).forEach { Recordatorios.quitar(app, it) }
        // Otra vez todas: una clase que cambió de hora conserva el id solo si sigue igual, y poner la
        // misma la deja como estaba.
        alarmas.forEach { (id, cuando) -> Recordatorios.poner(app, id, cuando) }
        runCatching { apuntadas.writeText(alarmas.keys.joinToString("\n")) }
        Unit
    }

    /** **Suena un aviso del horario**: su llamada. Si el horario cambió y ya no está, no llama. */
    fun sonar(context: Context, id: String) {
        val app = context.applicationContext
        val ahora = System.currentTimeMillis()
        synchronized(cerrojo) {
            val p = pospuestas(app.filesDir, ahora)
            if (id in p) guardarPospuestas(app.filesDir, p - id)
        }
        val local = msLocales(ahora)
        val h = Horario.deDisco(app.filesDir, Momento.deMsLocales(local))
        // Un evento de Google Calendar: su título, su hora, su lugar.
        val deGoogle = id.removePrefix(Horario.PREFIJO).takeIf { it.startsWith("gcal:") && id.startsWith(Horario.PREFIJO) }
            ?.removePrefix("gcal:")?.substringBefore(':')?.substringBefore('|')
            ?.let { com.forge.pixpin.asistente.CalendarioGoogle.evento(app, it) }
        if (deGoogle != null) {
            val l = Horario.Llamada(
                audio = null, nombre = deGoogle.titulo, mensaje = id,
                detalle = listOf(com.forge.pixpin.asistente.CalendarioGoogle.tramoLegible(deGoogle), deGoogle.lugar, deGoogle.calendario).filter { it.isNotEmpty() }.joinToString(" · "),
                texto = if (deGoogle.lugar.isEmpty()) deGoogle.titulo else "${deGoogle.titulo} en ${deGoogle.lugar}"
            )
            runCatching { LlamadaSecretaActivity.llamarDelHorario(app, l) }
            reprogramar(app, ahora + 1_000)
            return
        }
        val aviso = Horario.avisoDe(h, id, local)
        if (aviso != null) {
            val l = Horario.llamadaDe(h, aviso, Horario.carpetaDeVoz(app.filesDir)) { f ->
                f.takeIf { it.isFile }?.lastModified()?.let { msLocales(it) }
            }
            runCatching { LlamadaSecretaActivity.llamarDelHorario(app, l) }
        }
        reprogramar(app, ahora + 1_000)
    }

    /** «Volver a llamar en»: el mismo aviso dentro de [minutos]. */
    fun posponer(context: Context, id: String, minutos: Int): Long {
        val app = context.applicationContext
        val cuando = System.currentTimeMillis() + minutos * 60_000L
        val nuevo = "${id.substringBefore('|')}|$cuando"
        synchronized(cerrojo) {
            guardarPospuestas(app.filesDir, pospuestas(app.filesDir, System.currentTimeMillis()) - id + (nuevo to cuando))
        }
        reprogramar(app)
        return cuando
    }
}
