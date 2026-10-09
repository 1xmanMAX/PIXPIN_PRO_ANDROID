package com.forge.pixpin.mini

import com.forge.pixpin.guardados.Mensaje
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **Qué tareas tienen que sonar y cuándo** (8-oct-2026, las «tareas con recordatorio» del PC).
 * La hora va en el texto de la tarea (`⏰ 2026-10-09 10:00`, ver [Tareas.horaDe]); aquí se saca la
 * lista de alarmas que tiene que haber puestas, y lo que queda de una tarea cuando ya sonó.
 *
 * Sin Android: lo prueba `AlarmasDeTareasTest`. Quien las pone es `pin/RecordatoriosDeTareas`.
 */
object AlarmasDeTareas {

    /** El prefijo de la alarma de una tarea, el mismo id que el PC (`tareas::id_de_recordatorio`). */
    const val PREFIJO = "tarea:"

    data class Alarma(
        /** `tarea:<id del mensaje>:<hora local en ms desde 1970>`. */
        val id: String,
        /** Cuándo suena, en ms UTC. */
        val cuando: Long,
        /** Lo que se enseña al sonar. */
        val texto: String
    )

    /** La hora local en ms desde 1970, como la cuenta el PC. */
    fun msLocales(hora: LocalDateTime): Long = hora.toEpochSecond(ZoneOffset.UTC) * 1000

    fun idDe(mensaje: String, hora: LocalDateTime) = "$PREFIJO$mensaje:${msLocales(hora)}"

    /** El mensaje y la hora local de un id de [idDe]; null si no es de una tarea. */
    fun deId(id: String): Pair<String, LocalDateTime>? {
        if (!id.startsWith(PREFIJO)) return null
        val resto = id.removePrefix(PREFIJO)
        val corte = resto.lastIndexOf(':')
        if (corte <= 0) return null
        val ms = resto.substring(corte + 1).toLongOrNull() ?: return null
        return resto.substring(0, corte) to LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000L), 0, ZoneOffset.UTC)
    }

    /** Las alarmas de las tareas pendientes con hora que aún no ha pasado. */
    fun deLosMensajes(mensajes: List<Mensaje>, ahora: Long, zona: ZoneId = ZoneId.systemDefault()): List<Alarma> =
        mensajes.filter { TodasLasTareas.esLista(it) && !it.enBuzon }.flatMap { m ->
            Tareas.leer(m.texto).filter { !it.hecha }.mapNotNull { t ->
                val hora = Tareas.horaDe(t.texto) ?: return@mapNotNull null
                val cuando = hora.atZone(zona).toInstant().toEpochMilli()
                if (cuando <= ahora) return@mapNotNull null
                Alarma(idDe(m.id, hora), cuando, "☑ " + Tareas.legible(t.texto).ifBlank { "Tarea con imagen" })
            }
        }.distinctBy { it.id }

    /**
     * **Ya sonó**: el documento con la hora quitada de la tarea pendiente que la llevaba, y su
     * texto para enseñarlo. null si ya no está (hecha, quitada, o la quitó el PC al sonar allí).
     */
    fun sono(documento: String, hora: LocalDateTime): Pair<String, String>? {
        val tareas = Tareas.leer(documento)
        val i = tareas.indexOfFirst { !it.hecha && Tareas.horaDe(it.texto) == hora }
        if (i < 0) return null
        val t = tareas[i]
        val nuevas = tareas.toMutableList().also { it[i] = t.copy(texto = Tareas.conHora(t.texto, null)) }
        return Tareas.escribir(Cabecera.titulo(documento), nuevas) to Tareas.legible(t.texto).ifBlank { "Tarea con imagen" }
    }
}
