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
    ): File? = aArchivo(context, listOf(proyecto to marcadas), proyecto.nombre, escenaDe, imageProvider)

    /**
     * **Páginas de varios proyectos en un solo PDF**, en el orden de la lista. Es lo que deja
     * juntar la página uno de un proyecto con las siete a diez de otro y mandarlas como una
     * sola cosa. Lo pidió el usuario (5-sep-2026).
     */
    fun aArchivo(
        context: Context,
        seleccion: List<Pair<Proyecto, Set<String>>>,
        nombreDelConjunto: String,
        escenaDe: (String) -> Scene?,
        imageProvider: (String) -> Bitmap? = { null }
    ): File? = runCatching {
        val paginas = seleccion.flatMap { (proyecto, marcadas) ->
            HojasDelProyecto.elegidas(proyecto, escenaDe, marcadas)
        }
        if (paginas.isEmpty()) return null

        // **Las páginas del PDF del proyecto van tal cual**, sacadas del documento —que ya lleva
        // lo anotado dentro, en su capa—: vectoriales y con su texto. Antes no salían: la hoja de
        // una página sin dibujo no tenía escena y se saltaba, y la que sí tenía salía sin el
        // papel debajo. Ver [PdfUnion.soloPaginas].
        var documento: ByteArray? = null
        for ((proyecto, marcadas) in seleccion) {
            val ruta = Proyectos.rutaDelDocumento(proyecto) { File(it).exists() } ?: continue
            val indices = HojasDelProyecto.elegidas(proyecto, escenaDe, marcadas).mapNotNull { it.hoja.pagina }
            if (indices.isEmpty()) continue
            val trozo = PdfUnion.soloPaginas(File(ruta).readBytes(), indices) ?: continue
            documento = documento?.let { PdfUnion.anadirPaginas(it, trozo) ?: it } ?: trozo
        }

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
            if (hoja.tabla != null) {
                // **Una tabla entra como la tabla de Markdown de sus valores**, ya calculados:
                // el PDF de las notas sabe componerla y partirla en páginas, y así el texto
                // sigue siendo texto que se busca y se copia.
                TablasEnDisco.de(context.filesDir).cargar(hoja.tabla)?.let { t ->
                    TablaViva.comoMarkdown(t).takeIf { it.isNotBlank() }?.let { notas += it }
                }
                return@forEach
            }
            if (hoja.nota != null) {
                // **La página marcada, no la nota entera.** Con una nota de
                // doce hojas de la que se eligen dos, entregar las doce es
                // exactamente lo que se estaba evitando al elegir.
                notas += pagina.texto ?: hoja.nota!!
                return@forEach
            }
            // Ya va en [documento], como página de verdad.
            if (hoja.pagina != null && documento != null) return@forEach
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

        val nombre = nombreDeArchivo(nombreDelConjunto)
        var archivo: File? = null

        if (elementos.isNotEmpty()) {
            archivo = DrawPdf.aArchivo(
                context, Scene(elements = elementos), nombre, imageProvider
            )
        }
        documento?.let { doc ->
            val destino = File(File(context.cacheDir, "share").apply { mkdirs() }, "$nombre.pdf")
            val dibujos = archivo?.readBytes()
            destino.writeBytes(dibujos?.let { PdfUnion.anadirPaginas(doc, it) } ?: doc)
            archivo = destino
        }

        // Las notas van detrás, como texto de verdad. Ver [PdfDeNota].
        notas.forEach { nota ->
            val actual = archivo
            val base = if (actual != null) actual.readBytes() else pdfEnBlanco()
            val conNota = PdfDeNota.aniadir(base, nota, Detalle::medioParaPdf) ?: return@forEach
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
    private fun pdfEnBlanco(): ByteArray = PdfUnion.enBlanco()

}
