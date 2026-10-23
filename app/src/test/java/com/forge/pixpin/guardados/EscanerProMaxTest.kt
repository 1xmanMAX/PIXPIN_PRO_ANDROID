package com.forge.pixpin.guardados

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Escanear desde el clip del chat con «Escáner Pro Max»** (23-oct-2026): la petición que se le
 * hace y cómo se lee lo que devuelve (`ClipData`, el extra `URIS` y `data`). Ver [EscanerProMax].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EscanerProMaxTest {

    private fun uri(n: Int): Uri = Uri.parse("content://com.scannerpromax.files/attach/scan_$n.pdf")

    @Test
    fun `la peticion abre la camara directa, documento, varias paginas, en PDF y sin revisar`() {
        val i = EscanerProMax.peticion()
        assertEquals("com.scannerpromax.action.SCAN", i.action)
        assertEquals("com.scannerpromax", i.`package`)
        assertEquals("document", i.getStringExtra("com.scannerpromax.extra.MODE"))
        assertEquals("pdf", i.getStringExtra("com.scannerpromax.extra.FORMAT"))
        assertTrue(i.getBooleanExtra("com.scannerpromax.extra.MULTI_PAGE", false))
        // Sin revisión: falso aunque el valor por defecto que se lea sea verdadero.
        assertFalse(i.getBooleanExtra("com.scannerpromax.extra.REVIEW", true))
        assertEquals("PixPin", i.getStringExtra("com.scannerpromax.extra.CALLER_LABEL"))
        // No se pide OCR: no hay dónde guardarlo sin que se vea.
        assertFalse(i.hasExtra("com.scannerpromax.extra.OCR"))
    }

    @Test
    fun `cancelar o volver sin datos no adjunta nada`() {
        assertEquals(EscanerProMax.Resultado.Nada, EscanerProMax.resultado(Activity.RESULT_CANCELED, Intent().setData(uri(1))))
        assertEquals(EscanerProMax.Resultado.Nada, EscanerProMax.resultado(Activity.RESULT_OK, null))
        assertEquals(EscanerProMax.Resultado.Nada, EscanerProMax.resultado(Activity.RESULT_OK, Intent()))
    }

    @Test
    fun `lo normal - los tres sitios con lo mismo, sin repetir y en su orden`() {
        val clip = ClipData.newRawUri("scan", uri(1)).apply { addItem(ClipData.Item(uri(2))) }
        val data = Intent()
            .setDataAndType(uri(1), "application/pdf")
            .putParcelableArrayListExtra("com.scannerpromax.extra.URIS", arrayListOf(uri(1), uri(2)))
            .putExtra("com.scannerpromax.extra.MIME_TYPE", "application/pdf")
            .putExtra("com.scannerpromax.extra.PAGE_COUNT", 5)
        data.clipData = clip
        val r = EscanerProMax.resultado(Activity.RESULT_OK, data) as EscanerProMax.Resultado.Archivos
        assertEquals(listOf(uri(1), uri(2)), r.uris)
        assertEquals("application/pdf", r.tipo)
        assertEquals(5, r.paginas)
    }

    @Test
    fun `solo data - un archivo, con el tipo del intento`() {
        val data = Intent().setDataAndType(uri(7), "image/jpeg")
        val r = EscanerProMax.resultado(Activity.RESULT_OK, data) as EscanerProMax.Resultado.Archivos
        assertEquals(listOf(uri(7)), r.uris)
        assertEquals("image/jpeg", r.tipo)
        assertEquals(1, r.paginas)
    }

    @Test
    fun `solo el extra URIS tambien vale`() {
        val data = Intent().putParcelableArrayListExtra("com.scannerpromax.extra.URIS", arrayListOf(uri(3), uri(4)))
        val r = EscanerProMax.resultado(Activity.RESULT_OK, data) as EscanerProMax.Resultado.Archivos
        assertEquals(listOf(uri(3), uri(4)), r.uris)
        assertEquals(null, r.tipo)
    }

    @Test
    fun `juntar - primero el clip, luego el extra, luego data, sin nulos ni repetidos`() {
        assertEquals(listOf("a", "b", "c"), EscanerProMax.archivosDe(listOf("a", null, "b"), listOf("b", "c"), "a"))
        assertEquals(listOf("d"), EscanerProMax.archivosDe(emptyList<String>(), null, "d"))
        assertEquals(emptyList<String>(), EscanerProMax.archivosDe(emptyList<String>(), null, null))
    }
}
