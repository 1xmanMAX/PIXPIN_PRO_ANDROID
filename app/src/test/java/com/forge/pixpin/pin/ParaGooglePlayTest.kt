package com.forge.pixpin.pin

import com.forge.pixpin.ui.AbrirCon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Lo que cambia para Google Play** (30-oct-2026): la alarma inexacta cuando falta el permiso, la
 * pantalla completa de la llamada solo si se puede, los APK que no se instalan, el servicio de los
 * pins que explica su uso y los secretos fuera de la copia de seguridad.
 */
class ParaGooglePlayTest {

    @Test fun `antes de Android 12 la alarma siempre es exacta`() {
        assertEquals(AlarmaExacta.Modo.EXACTA, AlarmaExacta.modo(29, puedeExactas = false))
        assertEquals(AlarmaExacta.Modo.EXACTA, AlarmaExacta.modo(30, puedeExactas = false))
    }

    @Test fun `desde Android 12 sin permiso la alarma es inexacta`() {
        assertEquals(AlarmaExacta.Modo.INEXACTA, AlarmaExacta.modo(31, puedeExactas = false))
        assertEquals(AlarmaExacta.Modo.INEXACTA, AlarmaExacta.modo(36, puedeExactas = false))
        assertEquals(AlarmaExacta.Modo.EXACTA, AlarmaExacta.modo(31, puedeExactas = true))
        assertEquals(AlarmaExacta.Modo.EXACTA, AlarmaExacta.modo(36, puedeExactas = true))
    }

    @Test fun `el permiso se ofrece como mucho una vez cada rato`() {
        assertTrue(AlarmaExacta.tocaOfrecer(0L, 1_000L))
        assertFalse(AlarmaExacta.tocaOfrecer(1_000L, 1_000L + AlarmaExacta.CADA_MS - 1))
        assertTrue(AlarmaExacta.tocaOfrecer(1_000L, 1_000L + AlarmaExacta.CADA_MS))
        // El reloj del teléfono hacia atrás no deja el aviso callado para siempre.
        assertTrue(AlarmaExacta.tocaOfrecer(5_000_000L, 1_000L))
    }

    @Test fun `se reponen solo los recordatorios que siguen esperando`() {
        val apuntadas = mapOf<String, Any?>("a" to 100L, "b" to 200L, "c" to 300L, "raro" to "x", "nulo" to null)
        assertEquals(mapOf("c" to 300L), Recordatorios.pendientes(apuntadas, 200L))
        assertEquals(emptyMap<String, Long>(), Recordatorios.pendientes(emptyMap<String, Long>(), 0L))
    }

    @Test fun `la pantalla completa solo si el sistema la deja`() {
        // Android 14+: decide canUseFullScreenIntent, lo declare o no el manifiesto.
        assertTrue(PantallaCompleta.permitida(34, sistema = true, declarado = true))
        assertFalse(PantallaCompleta.permitida(34, sistema = false, declarado = true))
        assertFalse(PantallaCompleta.permitida(35, sistema = null, declarado = true))
        // Antes: el permiso declarado (APK de GitHub sí, Play no).
        assertTrue(PantallaCompleta.permitida(33, sistema = null, declarado = true))
        assertFalse(PantallaCompleta.permitida(29, sistema = null, declarado = false))
    }

    @Test fun `un APK se reconoce por su tipo o por su extension`() {
        assertTrue(AbrirCon.esApk(AbrirCon.MIME_APK, "lo-que-sea.bin"))
        assertTrue(AbrirCon.esApk(null, "PixPin.APK"))
        assertTrue(AbrirCon.esApk("application/octet-stream", "app.apk"))
        assertFalse(AbrirCon.esApk("application/pdf", "plano.pdf"))
        assertFalse(AbrirCon.esApk(null, "apk"))
    }

    private val manifiesto = File("src/main/AndroidManifest.xml")

    @Test fun `el uso especial del servicio de los pins va dentro de su servicio`() {
        val texto = manifiesto.readText()
        val servicio = Regex("""<service\s+android:name="\.floating\.PinHostService"[^>]*>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL)
            .find(texto)
        assertTrue("PinHostService debe llevar dentro su <property>", servicio != null)
        assertTrue(servicio!!.groupValues[1].contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertEquals("solo una vez, la del servicio", 1, Regex("PROPERTY_SPECIAL_USE_FGS_SUBTYPE").findAll(texto).count())
    }

    @Test fun `los secretos no van a la copia en la nube`() {
        val texto = manifiesto.readText()
        assertTrue(texto.contains("android:dataExtractionRules=\"@xml/reglas_de_copia\""))
        assertTrue(texto.contains("android:fullBackupContent=\"@xml/copia_de_seguridad\""))
        val reglas = File("src/main/res/xml/reglas_de_copia.xml").readText()
        val nube = reglas.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val traspaso = reglas.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        val antiguas = File("src/main/res/xml/copia_de_seguridad.xml").readText()
        for (secreto in listOf("sincro/identidad.json", "sincro/persona.json", "sincro/compartidos.json")) {
            assertTrue("$secreto en la nube", nube.contains("path=\"$secreto\""))
            assertTrue("$secreto en Android 10-11", antiguas.contains("path=\"$secreto\""))
        }
        // El id del aparato tiene que ser único: tampoco pasa al teléfono nuevo.
        assertTrue(traspaso.contains("path=\"sincro/identidad.json\""))
    }
}
