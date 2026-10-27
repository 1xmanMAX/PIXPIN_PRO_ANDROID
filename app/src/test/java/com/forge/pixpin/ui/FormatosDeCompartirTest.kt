package com.forge.pixpin.ui

import com.forge.pixpin.ui.FormatosDeCompartir.Clase
import com.forge.pixpin.ui.FormatosDeCompartir.Contenido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **La tabla de la hoja de compartir** (27-oct-2026): qué formatos tiene cada contenido, en qué
 * orden y con qué nombre. Lo pidió el usuario: «en todo compartir de toda la aplicación siempre
 * tienen que estar las mismas opciones que en las demás… de forma entendible siempre».
 */
class FormatosDeCompartirTest {

    @Test
    fun elOrdenEsElDelPc() {
        // La imagen y el PDF delante, lo entero detrás y la Wi-Fi al final.
        assertEquals(
            listOf(
                Clase.PNG, Clase.JPG, Clase.SVG, Clase.PDF, Clase.WEB, Clase.WORD, Clase.MARKDOWN, Clase.TEXTO,
                Clase.CSV, Clase.OTRO, Clase.PIXPIN, Clase.ORIGINAL, Clase.ANKI, Clase.WIFI
            ),
            Clase.entries.toList()
        )
    }

    @Test
    fun cadaContenidoTieneSuListaYEnOrden() {
        for (c in Contenido.entries) {
            val lista = FormatosDeCompartir.formatosDe(c)
            assertTrue("$c sin formatos", lista.isNotEmpty())
            assertTrue("$c fuera de orden: $lista", FormatosDeCompartir.enOrden(lista))
            assertEquals("$c repite", lista.size, lista.toSet().size)
            if (Clase.WIFI in lista) assertEquals("$c: la Wi-Fi va la última", Clase.WIFI, lista.last())
        }
    }

    @Test
    fun laTablaEntera() {
        val esperada = mapOf(
            Contenido.PROYECTO to "PNG JPG SVG PDF WEB WORD MARKDOWN TEXTO OTRO PIXPIN ORIGINAL WIFI",
            Contenido.MENSAJES to "PNG JPG SVG PDF WEB WORD MARKDOWN TEXTO OTRO PIXPIN ORIGINAL WIFI",
            Contenido.NOTA to "PDF WEB WORD MARKDOWN TEXTO PIXPIN WIFI",
            Contenido.PDF to "PNG JPG PDF WEB TEXTO PIXPIN ORIGINAL WIFI",
            Contenido.DOCUMENTO to "PNG JPG PDF WEB TEXTO PIXPIN ORIGINAL WIFI",
            Contenido.HOJA_DE_CALCULO to "PNG JPG PDF WEB CSV PIXPIN ORIGINAL WIFI",
            Contenido.TABLA to "WEB CSV",
            Contenido.PLANO to "PNG JPG PDF WEB OTRO PIXPIN ORIGINAL WIFI",
            Contenido.MODELO_3D to "PNG JPG PDF WEB PIXPIN ORIGINAL WIFI",
            Contenido.CROQUIS_3D to "PNG JPG PDF WEB OTRO PIXPIN ORIGINAL",
            Contenido.GRABACION to "PDF WEB WORD MARKDOWN TEXTO PIXPIN ORIGINAL ANKI WIFI",
            Contenido.LECCION to "PNG JPG PDF WEB MARKDOWN TEXTO PIXPIN ORIGINAL",
            Contenido.CARPETA to "PNG JPG PDF",
            Contenido.IMAGEN to "PNG JPG PDF WEB TEXTO PIXPIN ORIGINAL WIFI",
            Contenido.ARCHIVO to "TEXTO PIXPIN ORIGINAL WIFI"
        )
        assertEquals(Contenido.entries.toSet(), esperada.keys)
        for ((c, l) in esperada) assertEquals("$c", l, FormatosDeCompartir.formatosDe(c).joinToString(" "))
    }

    @Test
    fun losIdsDeCadaGeneradorCaenEnSuClase() {
        val casos = mapOf(
            "png" to Clase.PNG, "imagen" to Clase.PNG, "jpg" to Clase.JPG, "svg" to Clase.SVG,
            "pdf" to Clase.PDF, "pdf-anotado" to Clase.PDF,
            "web" to Clase.WEB, "web-anotada" to Clase.WEB, "web-pdf-anotado" to Clase.WEB,
            "word" to Clase.WORD, "md" to Clase.MARKDOWN, "texto" to Clase.TEXTO, "csv" to Clase.CSV,
            "editable" to Clase.PIXPIN, "original" to Clase.ORIGINAL, "anki" to Clase.ANKI, "wifi" to Clase.WIFI,
            "modelo" to Clase.OTRO, "dxf" to Clase.OTRO, "al-pdf" to Clase.OTRO
        )
        for ((id, clase) in casos) assertEquals(id, clase, FormatosDeCompartir.claseDe(id))
    }

    @Test
    fun losNombresSonLosMismosEnTodas() {
        // Lo que cada sitio llamaba a su manera sale igual.
        assertEquals("PixPin (.pixpin)", FormatosDeCompartir.rotulo("editable", "Editable"))
        assertEquals("PixPin (.pixpin)", FormatosDeCompartir.rotulo("editable", ".pixpin"))
        assertEquals("Texto", FormatosDeCompartir.rotulo("texto", "TXT"))
        assertEquals("Imagen PNG", FormatosDeCompartir.rotulo("png", "PNG"))
        assertEquals("Imagen JPG", FormatosDeCompartir.rotulo("jpg", "JPG"))
        assertEquals("Página web (HTML)", FormatosDeCompartir.rotulo("web", "Página web"))
        assertEquals("Página web (HTML)", FormatosDeCompartir.rotulo("web-anotada", "Página web"))
        assertEquals("Tabla (CSV)", FormatosDeCompartir.rotulo("csv", "CSV (Excel)"))
        assertEquals("Por Wi‑Fi a mis aparatos", FormatosDeCompartir.rotulo("wifi", "Enviar por Wi-Fi"))
        // El original dice qué archivo es si se sabe; si no, «Original».
        assertEquals("Original (XLSX)", FormatosDeCompartir.rotulo("original", "XLSX"))
        assertEquals("Original", FormatosDeCompartir.rotulo("original", "Archivo"))
        // Dos páginas web del mismo documento se distinguen por su nombre.
        assertEquals("Página web por hojas", FormatosDeCompartir.rotulo("web-pdf-anotado", "Página web por hojas"))
        assertEquals("Página web en texto", FormatosDeCompartir.rotulo("web-anotada", "Página web en texto"))
        // Lo propio de un contenido, con el suyo.
        assertEquals("Modelo 3D", FormatosDeCompartir.rotulo("modelo", "Modelo 3D"))
    }

    @Test
    fun ordenaYFiltraComoLaHoja() {
        // Como los daba el lector de PDF antes: el PDF, la web, las imágenes, el original, el .pixpin.
        val dados = listOf("pdf-anotado", "web-pdf-anotado", "web-anotada", "png", "jpg", "original", "editable", "wifi", "al-pdf")
        assertEquals(
            listOf("png", "jpg", "pdf-anotado", "web-pdf-anotado", "web-anotada", "al-pdf", "editable", "original", "wifi"),
            FormatosDeCompartir.visibles(null, dados) { it }
        )
        // Una carpeta no tiene página web ni .pixpin aunque alguien los diera; lo propio sí sale.
        assertEquals(
            listOf("png", "pdf", "al-pdf"),
            FormatosDeCompartir.visibles(Contenido.CARPETA, listOf("web", "pdf", "editable", "png", "al-pdf")) { it }
        )
        assertFalse(FormatosDeCompartir.enOrden(listOf(Clase.PDF, Clase.PNG)))
    }
}
