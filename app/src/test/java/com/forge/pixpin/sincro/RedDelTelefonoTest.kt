package com.forge.pixpin.sincro

import org.junit.Assert.assertEquals
import org.junit.Test

class RedDelTelefonoTest {
    @Test
    fun wifi_datos_y_nada() {
        assertEquals(RedDelTelefono.Tipo.WIFI, RedDelTelefono.tipoDe(conInternet = true, wifiOCable = true, sinMedir = true))
        // Datos móviles, o una Wi-Fi que se paga (el punto de acceso de otro móvil): solo la nube.
        assertEquals(RedDelTelefono.Tipo.DATOS, RedDelTelefono.tipoDe(conInternet = true, wifiOCable = false, sinMedir = false))
        assertEquals(RedDelTelefono.Tipo.DATOS, RedDelTelefono.tipoDe(conInternet = true, wifiOCable = true, sinMedir = false))
        assertEquals(RedDelTelefono.Tipo.NINGUNA, RedDelTelefono.tipoDe(conInternet = false, wifiOCable = true, sinMedir = true))
    }
}
