package com.forge.pixpin.horario

import android.content.Context
import com.forge.pixpin.ia.Momento
import com.forge.pixpin.pin.LlamadaSecretaActivity
import com.forge.pixpin.pin.Recordatorios
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * **Las llamadas del horario** (25-oct-2026, la parte de disco y de alarmas de `horario.rs` del PC):
 * antes de cada clase suena la **llamada secreta** ([LlamadaSecretaActivity]) con el nombre de la
 * asignatura, la hora y el aula; al contestar se oye la nota de voz grabada para esa clase y
 * después la voz del teléfono lee lo apuntado con `>` y las evaluaciones cercanas. Las evaluaciones
 * llaman 3 días y 1 día antes.
 *
 * **Con el mismo despertador que los recordatorios** ([Recordatorios], `setAlarmClock`): el PC tiene
 * un vigía que relee la agenda cada pocos segundos; en el teléfono la app no vive, así que se ponen
 * alarmas para los próximos [Horario.DIAS_A_LA_VISTA] días y se rehacen al guardar el horario, al
 * arrancar la app y cada vez que suena una (así la semana siempre va por delante). Las que sobran se
 * quitan, como en [com.forge.pixpin.pin.RecordatoriosDeTareas].
 * Sin permiso de alarmas exactas (la versión de Google Play, 30-oct-2026) suenan con algún minuto de
 * retraso en vez de fallar: ver [com.forge.pixpin.pin.AlarmaExacta].
 *
 * El horario vive en `filesDir/horario.md` y las notas de voz en `filesDir/horario/voz/`: en el PC
 * es «solo de este equipo y no viaja», y aquí igual, con el mismo nombre y formato.
 */
object AvisosDelHorario {

    private const val APUNTADAS = "alarmas-del-horario.txt"
    private const val PREFS = "horario"
    private const val POSPUESTOS = "pospuestos"
    private val cerrojo = Any()

    fun ruta(context: Context) = File(context.filesDir, Horario.FICHERO)

    fun carpetaDeVoz(context: Context) = File(context.filesDir, "horario/voz")

    /** El texto del horario; la [Horario.PLANTILLA] si aún no hay. */
    fun texto(context: Context): String = runCatching { ruta(context).takeIf { it.isFile }?.readText() }.getOrNull() ?: Horario.PLANTILLA

    fun leer(context: Context): Horario.Leido = Horario.leer(texto(context), Momento.deMsLocales(ahoraLocal()))

    /** Guarda el horario y rehace sus alarmas. Trabajo de disco. */
    fun guardar(context: Context, texto: String) {
        ruta(context).writeText(texto)
        reprogramar(context)
    }

    /** Los ms locales de ahora (como los cuenta el horario). */
    fun ahoraLocal(utc: Long = System.currentTimeMillis()): Long = utc + TimeZone.getDefault().getOffset(utc)

    /** De ms locales a UTC, con el cambio de hora que toque ese día. */
    fun aUtc(local: Long): Long =
        LocalDateTime.ofEpochSecond(Math.floorDiv(local, 1000L), (Math.floorMod(local, 1000L) * 1_000_000).toInt(), ZoneOffset.UTC)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Cuándo se grabó un fichero, en ms locales. */
    private fun guardadaEn(f: File): Long? = f.takeIf { it.isFile }?.lastModified()?.let { ahoraLocal(it) }

    /**
     * **Rehace las alarmas del horario**: las de los próximos días y las pospuestas («Volver a
     * llamar en»). Trabajo de disco.
     */
    fun reprogramar(context: Context): Unit = synchronized(cerrojo) {
        val app = context.applicationContext
        val ahora = System.currentTimeMillis()
        val h = runCatching { leer(app) }.getOrNull() ?: return
        val quieren = Horario.avisos(h, ahoraLocal(ahora), Horario.DIAS_A_LA_VISTA)
            .associate { it.id to aUtc(it.cuando) }.toMutableMap()
        pospuestos(app).forEach { id ->
            val cuando = id.substringAfterLast('|').toLongOrNull()
            if (cuando != null && cuando > ahora) quieren[id] = cuando
        }
        val fichero = File(app.filesDir, APUNTADAS)
        val antes = runCatching { fichero.readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        (antes - quieren.keys).forEach { Recordatorios.quitar(app, it) }
        // Se ponen todas: poner otra vez la misma la deja igual (el id la cambia, no la duplica).
        quieren.forEach { (id, cuando) -> Recordatorios.poner(app, id, cuando) }
        runCatching { fichero.writeText(quieren.keys.joinToString("\n")) }
        Unit
    }

    private fun pospuestos(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(POSPUESTOS, emptySet()).orEmpty()

    private fun ponerPospuestos(context: Context, v: Set<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(POSPUESTOS, v).apply()

    /** «Volver a llamar en»: el mismo aviso a otra hora (UTC). */
    fun posponer(context: Context, id: String, cuandoUtc: Long) {
        val base = id.substringBefore('|')
        val nuevo = "$base|$cuandoUtc"
        ponerPospuestos(context, pospuestos(context).filter { it.substringBefore('|') != base }.toSet() + nuevo)
        reprogramar(context)
    }

    /**
     * **Suena un aviso del horario** que acaba de vencer: su llamada. Si el horario cambió y ya no
     * se encuentra, sale igual con el nombre que llevaba el id. Y se rehacen las alarmas, para que
     * la semana siga por delante.
     */
    fun sonar(context: Context, id: String) {
        val app = context.applicationContext
        ponerPospuestos(app, pospuestos(app) - id)
        val ahora = ahoraLocal()
        val h = runCatching { leer(app) }.getOrDefault(Horario.Leido())
        val llamada = Horario.avisoDe(h, id, ahora)?.let { Horario.llamadaDe(h, it, carpetaDeVoz(app), ::guardadaEn) }
            ?: Horario.Llamada(null, id.substringBefore('|').substringAfterLast(':'), id, "", null)
        runCatching {
            LlamadaSecretaActivity.llamar(
                app, llamada.audio?.absolutePath, llamada.nombre, llamada.mensaje,
                detalle = llamada.detalle.ifBlank { null }, texto = llamada.texto
            )
        }
        runCatching { reprogramar(app) }
    }
}
