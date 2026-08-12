package com.forge.pixpin.motor

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Saca un PDF con **solo lo que se haya marcado** de un proyecto.
 *
 * ## Por qué existe aparte del PDF del proyecto
 *
 * El PDF de un proyecto con plano de origen se mantiene solo: cada anotación se
 * escribe dentro en cuanto se cierra la página, y ese archivo **es** el
 * documento. Pero entregar no siempre es entregar todo: se manda al cliente el
 * alzado y la planta, no las nueve pruebas de en medio.
 *
 * Esto arma un documento nuevo con las láminas elegidas, en el orden del
 * proyecto, y lo deja con **el nombre del proyecto**: lo que llega al otro lado
 * se llama como la obra, no `documento-1738.pdf`.
 */
object ExportarProyecto {

    /**
     * Escribe el PDF de lo marcado y devuelve el archivo.
     *
     * [marcadas] son las claves de [HojasDelProyecto.Pagina]. Devuelve null si
     * no había nada que escribir, para que quien llama avise en vez de compartir
     * un archivo vacío.
     */
    fun aArchivo(
        context: Context,
        proyecto: Proyecto,
        marcadas: Set<String>,
        escenaDe: (String) -> Scene?,
        imageProvider: (String) -> Bitmap? = { null }
    ): File? = runCatching {
        val paginas = HojasDelProyecto.paginas(proyecto, escenaDe)
            .filter { it.clave in marcadas }
        if (paginas.isEmpty()) return null

        // **Una sola escena con un marco por lámina**, y que la exportación de
        // siempre la pagine. Ya sabe hacerlo —un marco, una página— así que no
        // hay que repetir aquí ni el encuadre ni la orientación ni el margen.
        //
        // Un lienzo sin marcos se envuelve en uno del tamaño de lo que tenga:
        // así también es una lámina y entra por el mismo sitio, sin un caso
        // aparte que se comporte distinto.
        val elementos = mutableListOf<Element>()
        val notas = mutableListOf<String>()

        paginas.forEach { pagina ->
            val hoja = pagina.hoja
            if (hoja.nota != null) {
                notas += hoja.nota!!
                return@forEach
            }
            val escena = hoja.dibujo?.let(escenaDe) ?: return@forEach

            val marco = pagina.marco?.let { id -> escena.marcos.firstOrNull { it.id == id } }
            if (marco != null) {
                elementos += escena.contenidoDe(marco)
                elementos += marco
                return@forEach
            }

            val visible = escena.contenidoVisible.filterNot { it.isFrame }
            if (visible.isEmpty()) return@forEach
            val caja = getCommonBounds(visible)
            elementos += visible
            elementos += Element(
                id = "lamina-${hoja.id}",
                type = ElementType.FRAME,
                x = caja.x1 - MARGEN_DE_LAMINA,
                y = caja.y1 - MARGEN_DE_LAMINA,
                width = caja.width + MARGEN_DE_LAMINA * 2,
                height = caja.height + MARGEN_DE_LAMINA * 2,
                seed = 1
            )
        }

        val nombre = nombreDeArchivo(proyecto.nombre)
        var archivo: File? = null

        if (elementos.isNotEmpty()) {
            archivo = DrawPdf.aArchivo(
                context, Scene(elements = elementos), nombre, imageProvider
            )
        }

        // Las notas van detrás, como texto de verdad. Ver [PdfDeNota].
        notas.forEach { nota ->
            val actual = archivo
            val base = if (actual != null) actual.readBytes() else pdfEnBlanco()
            val conNota = PdfDeNota.aniadir(base, nota) ?: return@forEach
            val destino = actual ?: File(
                File(context.cacheDir, "share").apply { mkdirs() }, "$nombre.pdf"
            )
            destino.writeBytes(conNota)
            archivo = destino
        }

        return archivo?.takeIf { it.length() > 0 }
    }.getOrNull()

    /** El aire que se le deja a un lienzo sin marcos al convertirlo en lámina. */
    private const val MARGEN_DE_LAMINA = 24.0

    /**
     * El nombre del proyecto, apto para un archivo.
     *
     * Se quitan las barras y los dos puntos —que separan carpetas o rompen el
     * nombre en algunos sistemas— y se recorta: un nombre larguísimo se corta
     * solo al llegar al otro lado y queda peor que corto de aquí.
     */
    fun nombreDeArchivo(nombre: String): String {
        val limpio = nombre.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "-")
            .replace(Regex("""\s+"""), " ")
            .take(60)
            .trim()
        return limpio.ifBlank { "proyecto" }
    }

    /** Un PDF vacío del que partir cuando lo primero que entra es una nota. */
    private fun pdfEnBlanco(): ByteArray {
        val objetos = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
        )
        val cuerpo = StringBuilder("%PDF-1.4\n")
        val posiciones = mutableListOf<Int>()
        objetos.forEach {
            posiciones += cuerpo.length
            cuerpo.append(it)
        }
        val xref = cuerpo.length
        cuerpo.append("xref\n0 ${objetos.size + 1}\n0000000000 65535 f \n")
        posiciones.forEach { cuerpo.append("%010d 00000 n \n".format(it)) }
        cuerpo.append("trailer\n<< /Size ${objetos.size + 1} /Root 1 0 R >>\n")
            .append("startxref\n").append(xref).append("\n%%EOF\n")
        return cuerpo.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
