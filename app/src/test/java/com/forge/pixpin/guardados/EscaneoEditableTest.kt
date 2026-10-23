package com.forge.pixpin.guardados

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Lo escaneado queda etiquetado y se puede editar en el escáner** (23-oct-2026): la etiqueta del
 * mensaje (y cómo viaja en el JSON), la petición EDIT, la lectura del resultado y del «ya no está», y
 * el mensaje con su archivo nuevo. Ver [EscanerProMax] y [Mensaje.escaneado].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EscaneoEditableTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun uri(n: Int): Uri = Uri.parse("content://com.scannerpromax.fileprovider/cache/attach/$n/Escaneo.pdf")

    private fun mensaje(escaneado: Escaneado? = null, nombre: String = "Factura.pdf") = Mensaje(
        id = "m1", cuando = 1L, clase = Clase.ARCHIVO, ruta = "/data/guardados/1_Factura.pdf",
        nombre = nombre, bytes = 100, texto = "pie", escaneado = escaneado
    )

    @Test
    fun `la etiqueta viaja en el JSON como escaneado con app y documento`() {
        val m = mensaje(EscanerProMax.etiqueta("0123456789abcdef"))
        val o = json.encodeToJsonElement(Mensaje.serializer(), m).jsonObject
        val e = o["escaneado"]!!.jsonObject
        assertEquals("com.scannerpromax", e["app"]!!.jsonPrimitive.content)
        assertEquals("0123456789abcdef", e["documento"]!!.jsonPrimitive.content)
        // Y vuelve igual.
        assertEquals(m, json.decodeFromString(Mensaje.serializer(), json.encodeToString(Mensaje.serializer(), m)))
    }

    @Test
    fun `sin etiqueta no se escribe nada y lo de antes se sigue leyendo`() {
        val o = json.encodeToJsonElement(Mensaje.serializer(), mensaje()).jsonObject
        assertFalse(o.containsKey("escaneado"))
        val viejo = """{"id":"x","cuando":2,"clase":"ARCHIVO","nombre":"a.pdf"}"""
        assertNull(json.decodeFromString(Mensaje.serializer(), viejo).escaneado)
        // Una etiqueta sin documento (escáner que no lo dio) se lee y no se puede reabrir.
        val sinId = json.decodeFromString(Mensaje.serializer(), """{"id":"x","cuando":2,"clase":"ARCHIVO","escaneado":{"app":"com.scannerpromax"}}""")
        assertTrue(EscanerProMax.esEscaneo(sinId))
        assertNull(EscanerProMax.documentoDe(sinId))
        val o2 = json.encodeToJsonElement(Mensaje.serializer(), sinId).jsonObject
        assertEquals(setOf("app"), (o2["escaneado"] as JsonObject).keys)
    }

    @Test
    fun `marcar y leer la etiqueta`() {
        assertFalse(EscanerProMax.esEscaneo(mensaje()))
        assertNull(EscanerProMax.documentoDe(mensaje()))
        val m = mensaje(EscanerProMax.etiqueta("abc"))
        assertTrue(EscanerProMax.esEscaneo(m))
        assertEquals("abc", EscanerProMax.documentoDe(m))
        assertNull(EscanerProMax.etiqueta(" ").documento)
        // De otro escáner: es un escaneo, pero este no lo puede reabrir.
        assertNull(EscanerProMax.documentoDe(mensaje(Escaneado("otra.app", "abc"))))
    }

    @Test
    fun `la peticion EDIT lleva el documento y el mismo formato que al escanear`() {
        val i = EscanerProMax.peticionDeEdicion("0123456789abcdef")
        assertEquals("com.scannerpromax.action.EDIT", i.action)
        assertEquals("com.scannerpromax", i.`package`)
        assertEquals("0123456789abcdef", i.getStringExtra("com.scannerpromax.extra.DOCUMENT_ID"))
        assertEquals("pdf", i.getStringExtra("com.scannerpromax.extra.FORMAT"))
        assertTrue(i.getBooleanExtra("com.scannerpromax.extra.MULTI_PAGE", false))
        assertEquals("PixPin", i.getStringExtra("com.scannerpromax.extra.CALLER_LABEL"))
    }

    @Test
    fun `el resultado trae el documento`() {
        val data = Intent()
            .setDataAndType(uri(1), "application/pdf")
            .putExtra("com.scannerpromax.extra.PAGE_COUNT", 3)
            .putExtra("com.scannerpromax.extra.DOCUMENT_ID", "abc")
        val r = EscanerProMax.resultado(Activity.RESULT_OK, data) as EscanerProMax.Resultado.Archivos
        assertEquals(listOf(uri(1)), r.uris)
        assertEquals("abc", r.documento)
        assertEquals(3, r.paginas)
        // Un escáner de antes no lo manda: el escaneo entra igual, sin documento.
        val viejo = EscanerProMax.resultado(Activity.RESULT_OK, Intent().setData(uri(2))) as EscanerProMax.Resultado.Archivos
        assertNull(viejo.documento)
    }

    @Test
    fun `cancelado porque el documento ya no esta`() {
        val no = Intent().putExtra("com.scannerpromax.extra.ERROR", "document_not_found")
        assertEquals(EscanerProMax.Resultado.NoExiste, EscanerProMax.resultado(Activity.RESULT_CANCELED, no))
        val malo = Intent().putExtra("com.scannerpromax.extra.ERROR", "invalid_document_id")
        assertEquals(EscanerProMax.Resultado.NoExiste, EscanerProMax.resultado(Activity.RESULT_CANCELED, malo))
        val otro = Intent().putExtra("com.scannerpromax.extra.ERROR", "unsupported_action")
        assertEquals(EscanerProMax.Resultado.Nada, EscanerProMax.resultado(Activity.RESULT_CANCELED, otro))
        assertEquals(EscanerProMax.Resultado.Nada, EscanerProMax.resultado(Activity.RESULT_CANCELED, null))
    }

    @Test
    fun `que hacer al pulsar editar`() {
        assertEquals(EscanerProMax.AlEditar.FALTA_EL_ESCANER, EscanerProMax.queHacerAlEditar(instalado = false, sabeEditar = false, documento = "abc"))
        assertEquals(EscanerProMax.AlEditar.SIN_DOCUMENTO, EscanerProMax.queHacerAlEditar(true, true, null))
        assertEquals(EscanerProMax.AlEditar.ESCANER_ANTIGUO, EscanerProMax.queHacerAlEditar(true, false, "abc"))
        assertEquals(EscanerProMax.AlEditar.ABRIR, EscanerProMax.queHacerAlEditar(true, true, "abc"))
    }

    @Test
    fun `el mensaje con su archivo nuevo conserva lo suyo y la etiqueta`() {
        val m = mensaje(EscanerProMax.etiqueta("abc"), nombre = "Mi factura.pdf")
        val n = EscanerProMax.conArchivoNuevo(m, "/data/guardados/2_Escaneo.pdf", 250, "Escaneo.pdf", null)
        assertEquals("/data/guardados/2_Escaneo.pdf", n.ruta)
        assertEquals(250L, n.bytes)
        // El nombre que le puso el usuario se queda si sigue siendo un PDF.
        assertEquals("Mi factura.pdf", n.nombre)
        assertEquals("abc", EscanerProMax.documentoDe(n))
        assertEquals(m.id, n.id)
        assertEquals(m.cuando, n.cuando)
        assertEquals("pie", n.texto)
        // Otro documento si el escáner lo dice; otra extensión, otro nombre.
        val j = EscanerProMax.conArchivoNuevo(m, "/x/3_Escaneo.jpg", 9, "Escaneo.jpg", "def")
        assertEquals("Escaneo.jpg", j.nombre)
        assertEquals("def", EscanerProMax.documentoDe(j))
        // Y cambia el mensaje, que es lo que hace que la sincronización lo mande.
        assertFalse(json.encodeToString(Mensaje.serializer(), m) == json.encodeToString(Mensaje.serializer(), n))
    }
}
