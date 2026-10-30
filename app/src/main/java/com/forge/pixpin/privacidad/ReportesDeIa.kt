package com.forge.pixpin.privacidad

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * **Reportar una respuesta de la IA** (30-oct-2026, política de contenido generado por IA de Google
 * Play): todo lo que escribe una IA en la app —el asistente, lo importante de una clase, el repaso,
 * las propuestas de la reunión— se puede marcar como ofensivo, peligroso o falso **sin salir de la
 * app**. El reporte se guarda aquí, en `filesDir/reportes-ia.jsonl` (una línea JSON por reporte), y
 * se ofrece mandarlo por correo a [Privacidad.CORREO_CONTACTO]: no hay servidor que lo recoja.
 *
 * Aquí va lo que no necesita Android (la línea, el correo, el archivo); el diálogo está en
 * [DialogoDeReporte]. Se prueba en `ReportesDeIaTest`.
 */
object ReportesDeIa {

    const val ARCHIVO = "reportes-ia.jsonl"

    /** Lo más largo del contenido que se guarda (una respuesta larga no infla el archivo). */
    const val LARGO_CONTENIDO = 8_000
    const val LARGO_DETALLE = 1_000

    /** Por qué se reporta. [id] es lo que se guarda (no cambia aunque cambie el rótulo). */
    enum class Motivo(val id: String, val rotulo: String) {
        OFENSIVO("ofensivo", "Ofensivo"),
        PELIGROSO("peligroso", "Peligroso"),
        FALSO("falso", "Falso"),
        OTRO("otro", "Otro");

        companion object {
            fun deId(id: String?): Motivo = entries.firstOrNull { it.id == id } ?: OTRO
        }
    }

    /** Dónde salió lo reportado (el [id] es lo que va en el archivo y en el correo). */
    enum class Funcion(val id: String, val rotulo: String) {
        ASISTENTE("asistente", "Asistente"),
        LO_IMPORTANTE("jev-lo-importante", "Lo importante de la clase (Jev)"),
        REUNION("jev-reunion", "Lo de la reunión (Jev)"),
        REPASO("jev-repaso", "Repasar en voz alta (Jev)"),
        OTRA("otra", "Otra función con IA")
    }

    /** **Un reporte.** [cuando] en ms desde 1970; [detalle] lo que escribió el usuario (puede ir vacío). */
    @Serializable
    data class Reporte(
        val cuando: Long,
        val funcion: String,
        val motivo: String,
        val contenido: String,
        val detalle: String = "",
        val version: String = ""
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Arma el reporte, recortando lo largo. */
    fun nuevo(
        funcion: Funcion, motivo: Motivo, contenido: String, detalle: String,
        cuando: Long = System.currentTimeMillis(), version: String = ""
    ): Reporte = Reporte(
        cuando = cuando,
        funcion = funcion.id,
        motivo = motivo.id,
        contenido = contenido.trim().take(LARGO_CONTENIDO),
        detalle = detalle.trim().take(LARGO_DETALLE),
        version = version
    )

    /** **La línea del archivo**: JSON en una sola línea (los saltos van escapados). */
    fun aLinea(r: Reporte): String = json.encodeToString(Reporte.serializer(), r)

    /** Al revés; null si la línea no se entiende (se salta sin llevarse las demás). */
    fun deLinea(linea: String): Reporte? =
        runCatching { json.decodeFromString(Reporte.serializer(), linea.trim()) }.getOrNull()

    /** **Lo apunta** al final de [archivo] (lo crea si no existe). */
    fun guardar(archivo: File, r: Reporte) {
        archivo.parentFile?.mkdirs()
        archivo.appendText(aLinea(r) + "\n", Charsets.UTF_8)
    }

    /** Los reportes guardados, en orden. */
    fun leer(archivo: File): List<Reporte> =
        if (!archivo.isFile) emptyList() else archivo.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull(::deLinea)

    /** La fecha legible (UTC, para que el correo no dependa del huso del teléfono). */
    fun fecha(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** El asunto del correo. */
    fun asunto(r: Reporte): String =
        "PixPin · Reporte de IA · ${Motivo.deId(r.motivo).rotulo} · ${Funcion.entries.firstOrNull { it.id == r.funcion }?.rotulo ?: r.funcion}"

    /** **El cuerpo del correo**: lo que se reporta y por qué, en texto plano. */
    fun cuerpo(r: Reporte): String = buildString {
        append("Motivo: ").append(Motivo.deId(r.motivo).rotulo).append('\n')
        append("Función: ").append(Funcion.entries.firstOrNull { it.id == r.funcion }?.rotulo ?: r.funcion).append('\n')
        append("Fecha: ").append(fecha(r.cuando)).append('\n')
        if (r.version.isNotBlank()) append("Versión: ").append(r.version).append('\n')
        if (r.detalle.isNotBlank()) append("\nComentario:\n").append(r.detalle).append('\n')
        append("\nContenido reportado:\n").append(r.contenido).append('\n')
    }
}
