package com.forge.pixpin.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import com.forge.pixpin.motor.DocumentoAnotado
import com.forge.pixpin.motor.DrawSvg
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.pin.ImageStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * **Un PDF anotado en el lector, como página web en la que se sigue anotando** (21-sep-2026).
 *
 * Lo pidió el usuario a la vez que los márgenes del lector: «también se puede exportar como html
 * de la misma forma y se pueda seguir anotando». Es el mismo documento web que un Word anotado
 * ([ExportarDocumentoAnotado]): las hojas en una columna, una debajo de otra, con su margen de
 * [Lectura.MARGEN_DEL_PDF] a cada lado, lo anotado encima de cada una —también lo de los
 * márgenes— y la barra de lápiz, resaltador, borrador, deshacer y guardar de toda página web.
 *
 * Las hojas van como fotografía: la página es una lectura, no un plano que medir. Cuantas más
 * hojas, menos puntos por hoja, para que un documento largo no se haga de decenas de megas.
 */
object ExportarPdfAnotado {
    /** Lo que mide la hoja en la página web, en píxeles CSS. El dibujo la mide en [PdfDoc.PAGE_WIDTH]. */
    private const val COLUMNA = 800
    private const val ENTRE_HOJAS = 8
    private const val PAPEL = "#f3f3f0"

    private const val ESTILO =
        ".doc{line-height:0}.doc img{display:block;width:100%;height:auto;margin:0 0 ${ENTRE_HOJAS}px;background:#fff}"

    fun formato(c: Context, ruta: String, titulo: String, paginas: Int, escenaDe: (Int) -> Scene?) =
        Compartible.Formato(
            "web-pdf-anotado", Icons.Filled.Language, "Página web", Compartible.NINGUNA,
            generar = {
                val funciones = (c.applicationContext as? com.forge.pixpin.PixPinApp)?.settings?.settings?.first()?.funcionesWeb
                hacer(c.applicationContext, ruta, titulo, paginas, escenaDe, funciones)?.let {
                    Compartible.Salida(it, ExportarHtml.MIME_TYPE, "Las hojas con lo anotado, y se sigue anotando")
                }
            }
        )

    fun hacer(
        c: Context, ruta: String, titulo: String, paginas: Int, escenaDe: (Int) -> Scene?, funciones: Set<String>? = null
    ): File? = runCatching {
        if (paginas <= 0) return null
        val ancho = when { paginas <= 10 -> 1600; paginas <= 40 -> 1200; else -> 960 }
        val margen = (COLUMNA * Lectura.MARGEN_DEL_PDF).toInt()
        val k = COLUMNA.toDouble() / PdfDoc.PAGE_WIDTH
        val tops = ArrayList<Double>(paginas)
        val piezas = ArrayList<DocumentoAnotado.Pieza>()
        var y = 0.0
        val cuerpo = StringBuilder()
        for (i in 0 until paginas) {
            val foto = PdfDoc.render(ruta, i, ancho) ?: continue
            val alto = COLUMNA.toDouble() * foto.height / foto.width
            cuerpo.append("<img alt=\"Hoja ").append(i + 1).append("\" width=\"").append(COLUMNA)
                .append("\" height=\"").append(Math.round(alto)).append("\" src=\"").append(enJpeg(foto)).append("\">")
            foto.recycle()
            val ancla = tops.size
            tops += y
            // Lo anotado de la hoja, con su cero en la esquina de la hoja: el margen izquierdo son equis negativas.
            escenaDe(i)?.let { e ->
                val imagen: (String) -> Bitmap? = { f -> e.files[f]?.path?.let { ImageStore.load(it) } }
                val svg = DrawSvg.aTexto(c, e.copy(backgroundColor = "#ffffff"), imagen, papelAparte = true) ?: return@let
                val caja = DocumentoAnotado.cajaDe(svg) ?: return@let
                piezas += DocumentoAnotado.Pieza(svg, margen + caja[0] * k, y + caja[1] * k, caja[2] * k, caja[3] * k, ancla)
            }
            // `height:auto` con el ancho al cien por cien da este mismo alto, sin redondear.
            y += alto + ENTRE_HOJAS
        }
        if (tops.isEmpty()) return null
        val hoja = ExportarHtml.HojaWeb.Documento(
            titulo, ESTILO, cuerpo.toString(), DocumentoAnotado.capaDe(piezas, emptyList(), COLUMNA, margen, "pdf"),
            COLUMNA, margen, tops, 16.0, PAPEL
        )
        val hecha = ExportarHtml.paginas(listOf(hoja), titulo, "$titulo (anotado)", ExportarHtml.Opciones.de(funciones))
        val limpio = ("$titulo (anotado).html").replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80)
        File(File(c.cacheDir, "share").apply { mkdirs() }, limpio).also { it.writeText(hecha) }
    }.getOrNull()

    private fun enJpeg(foto: Bitmap): String {
        val salida = ByteArrayOutputStream()
        foto.compress(Bitmap.CompressFormat.JPEG, 80, salida)
        return "data:image/jpeg;base64," + Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
    }
}
