package com.forge.pixpin.cuenta

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Actualizarse sola** (7-nov-2026): qué versión vale (ya comprobada su firma, ver [FirmadoTest]). */
class ActualizarTest {
    private fun v(json: String) = Json.parseToJsonElement(json) as JsonObject
    private val sha = "a".repeat(64)

    @Test
    fun solo_una_mas_nueva_por_https_y_con_su_huella() {
        val n = Actualizar.desde(v("""{"tipo":"version","versionCode":300,"versionName":"1.0","url":"https://x/a.apk","sha256":"$sha","notas":"Nuevo"}"""), 272)!!
        assertEquals(300, n.versionCode); assertFalse(n.obligatoria); assertEquals("Nuevo", n.notas)
        // La misma o una más vieja: nada.
        assertNull(Actualizar.desde(v("""{"tipo":"version","versionCode":272,"url":"https://x/a.apk","sha256":"$sha"}"""), 272))
        // Sin https, sin huella o con otro tipo de documento: nada.
        assertNull(Actualizar.desde(v("""{"tipo":"version","versionCode":300,"url":"http://x/a.apk","sha256":"$sha"}"""), 272))
        assertNull(Actualizar.desde(v("""{"tipo":"version","versionCode":300,"url":"https://x/a.apk","sha256":"corta"}"""), 272))
        assertNull(Actualizar.desde(v("""{"tipo":"licencia","versionCode":300,"url":"https://x/a.apk","sha256":"$sha"}"""), 272))
    }

    @Test
    fun con_version_minima_es_obligatoria_para_las_viejas() {
        val doc = v("""{"tipo":"version","versionCode":300,"url":"https://x/a.apk","sha256":"$sha","minimo":290}""")
        assertTrue(Actualizar.desde(doc, 280)!!.obligatoria)
        assertFalse(Actualizar.desde(doc, 295)!!.obligatoria)
    }
}
