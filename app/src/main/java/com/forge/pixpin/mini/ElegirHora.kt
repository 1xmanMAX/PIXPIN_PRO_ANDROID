package com.forge.pixpin.mini

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import java.time.LocalDateTime

/**
 * **Elegir a qué hora se recuerda una tarea**: el día y luego la hora, con los diálogos del
 * sistema. Se empieza en la que tenía o, si no, en la próxima hora en punto. Una hora ya pasada de
 * hoy pasa a mañana, como los recordatorios de los mensajes: nadie pide que le recuerden algo ayer.
 */
object ElegirHora {
    fun pedir(context: Context, inicial: LocalDateTime?, listo: (LocalDateTime) -> Unit) {
        val ahora = LocalDateTime.now()
        val base = inicial?.takeIf { it.isAfter(ahora) } ?: ahora.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, mi ->
                listo(alFuturo(LocalDateTime.of(y, m + 1, d, h, mi), LocalDateTime.now()))
            }, base.hour, base.minute, DateFormat.is24HourFormat(context)).show()
        }, base.year, base.monthValue - 1, base.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    /** Una hora que ya pasó, al día siguiente a la misma hora. */
    fun alFuturo(hora: LocalDateTime, ahora: LocalDateTime): LocalDateTime =
        if (hora.isAfter(ahora)) hora else hora.plusDays(1).let { if (it.isAfter(ahora)) it else ahora.plusMinutes(1).withSecond(0).withNano(0) }
}
