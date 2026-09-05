package com.forge.pixpin.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * **Si es de noche de verdad**: por la hora, o porque la habitación está a oscuras.
 *
 * El modo noche del sistema lo decide una hora fija o un botón, y ninguna de las dos
 * cosas sabe que a las cinco de la tarde de un día de invierno ya se dibuja con la luz
 * apagada, ni que a las diez de la noche en una terraza iluminada el modo noche molesta.
 * El sensor de luz sí lo sabe. Se lee con **histéresis** —a oscuras por debajo de diez
 * lux, con luz por encima de cuarenta— para que la sombra de la mano al pasar por delante
 * no cambie el tema de la aplicación cada dos segundos.
 *
 * La hora manda de ocho de la tarde a siete de la mañana; el resto del día, la luz. Sin
 * sensor (un aparato que no lo trae) queda solo la hora.
 */
@Composable
fun rememberAOscuras(): Boolean {
    val contexto = LocalContext.current
    var porLaHora by remember { mutableStateOf(esHoraDeNoche()) }
    var porLaLuz by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            porLaHora = esHoraDeNoche()
            delay(60_000)
        }
    }
    DisposableEffect(contexto) {
        val gestor = contexto.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = gestor?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val oidor = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val lux = e.values.firstOrNull() ?: return
                porLaLuz = when {
                    lux < LUX_A_OSCURAS -> true
                    lux > LUX_CON_LUZ -> false
                    else -> porLaLuz
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) gestor.registerListener(oidor, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { if (sensor != null) gestor.unregisterListener(oidor) }
    }
    return porLaHora || porLaLuz == true
}

private const val LUX_A_OSCURAS = 10f
private const val LUX_CON_LUZ = 40f

/** De ocho de la tarde a siete de la mañana. */
internal fun esHoraDeNoche(hora: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean =
    hora >= 20 || hora < 7
