package com.forge.pixpin.pin

import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.forge.pixpin.PixPinApp
import java.util.Calendar

/**
 * Pide la hora del recordatorio con **el reloj del sistema**.
 *
 * Uno propio se vería mejor y sería peor: el de Android respeta el formato de 12 o 24
 * horas del móvil, lo sabe usar todo el mundo y no hay que mantenerlo.
 *
 * Es una actividad y no un diálogo dentro del pin porque un pin es una ventana **sin
 * foco** —para eso no roba el teclado a lo que hay debajo— y un selector de hora sin foco
 * no recibe toques. Se abre transparente, pregunta y se cierra.
 */
class HoraDelRecordatorioActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pinId = intent.getStringExtra(EXTRA_PIN)
        if (pinId == null) {
            finish()
            return
        }
        val app = application as? PixPinApp
        val ahora = Calendar.getInstance()
        // Nace **una hora más tarde**, no a la hora actual: nadie pone un recordatorio
        // para ahora mismo, y arrancar en la hora en punto siguiente ahorra la mitad de
        // los giros de rueda en el caso más común.
        val dialogo = TimePickerDialog(
            this,
            { _, hora, minuto ->
                app?.overlayManager?.ponerHoraDeRecordatorio(pinId, hora, minuto)
                finish()
            },
            (ahora.get(Calendar.HOUR_OF_DAY) + 1) % 24,
            0,
            android.text.format.DateFormat.is24HourFormat(this)
        )
        // Cancelar tiene que cerrar la actividad o se queda una pantalla en blanco encima
        // de todo, que es de las cosas que hacen desinstalar una aplicación.
        dialogo.setOnCancelListener { finish() }
        dialogo.setOnDismissListener { if (!isFinishing) finish() }
        dialogo.show()
    }

    companion object {
        const val EXTRA_PIN = "pin"
    }
}
