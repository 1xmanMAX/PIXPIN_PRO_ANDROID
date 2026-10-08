package com.forge.pixpin.sincro

/**
 * **Adónde va un archivo suelto del PC**, según lo que haya delante en el móvil (la tabla de
 * `2026-10-07-archivos-al-chat-abierto-android.md` del PC, la misma que su `al_frente::decidir`):
 *
 * | Delante  | Una foto      | Otro archivo                |
 * |----------|---------------|-----------------------------|
 * | un chat  | a ese chat    | a ese chat                  |
 * | un lienzo| al lienzo     | **se niega** antes del vale |
 * | nada     | a la general  | a la general                |
 *
 * Sin Android, para probarlo en la JVM; `Presencia` le pasa lo que hay.
 */
object LoAbierto {
    enum class Delante { LIENZO, CHAT, NADA }

    /** Con los dos a la vista (pantalla partida), el que se puso delante el último. */
    fun delante(hayLienzo: Boolean, ordenDelLienzo: Long, hayChat: Boolean, ordenDelChat: Long): Delante = when {
        hayLienzo && (!hayChat || ordenDelLienzo > ordenDelChat) -> Delante.LIENZO
        hayChat -> Delante.CHAT
        else -> Delante.NADA
    }

    fun esFoto(mime: String) = mime.startsWith("image/")

    /** Antes del «vale»: `null` si se acepta, o el error con que se niega. */
    fun aceptar(delante: Delante, mime: String, miNombre: String): String? =
        if (delante == Delante.LIENZO && !esFoto(mime)) "$miNombre ${Protocolo.SOLO_FOTOS}" else null
}
