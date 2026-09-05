package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.forge.pixpin.data.ProyectosRepository
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.motor.Proyectos
import com.forge.pixpin.motor.Scene
import java.io.File

/**
 * **Lo que cae en la conversación de un proyecto se une al proyecto.**
 *
 * Una foto de la obra, el PDF que mandó el cliente, una nota escrita a vuelapluma: llegan
 * al chat porque es donde se guarda todo sin pensar, y después cuesta llevarlos a las hojas
 * del proyecto —había que compartir, elegir PixPin, elegir el proyecto—. Esto lo hace en un
 * toque: cada mensaje se convierte en la hoja que le corresponde.
 *
 * - Una foto → un lienzo con la foto (o el apunte ya hecho encima, si se dibujó en el chat).
 * - Un dibujo → una copia como hoja. Copia y no enlace: la hoja del proyecto se edita por
 *   su cuenta y no tiene que cambiar el mensaje del que salió.
 * - Una nota de texto (o un `.md`) → una hoja de notas.
 * - Un PDF → el documento del proyecto si aún no tiene; si ya tiene, cada página como una
 *   hoja de lienzo con la página de fondo, que es lo único que cabe en un proyecto de un
 *   solo documento.
 *
 * Lo demás —voz, mini-apps, archivos de otro tipo— no tiene hoja en la que vivir y se deja
 * donde está. Devuelve cuántas hojas se añadieron.
 */
object UnirAlProyecto {

    /** Cuántas páginas de un PDF se meten como lienzos cuando el proyecto ya tiene documento. */
    private const val TOPE_DE_PAGINAS_COMO_FOTO = 40

    /** Ancho al que se guarda una página del PDF como foto: legible sin pesar decenas de MB. */
    private const val ANCHO_DE_PAGINA = 1600

    fun sePuedeUnir(m: Mensaje): Boolean = when (m.clase) {
        Clase.IMAGEN -> m.ruta != null || m.referencia != null
        Clase.DIBUJO -> m.referencia != null
        Clase.NOTA -> m.texto.isNotBlank()
        Clase.ARCHIVO -> m.ruta != null && (esPdf(m) || esMarkdown(m))
        else -> false
    }

    /**
     * Lo que **entra solo** en el proyecto al caer en su chat: lo que viene de fuera —una
     * foto, un PDF, un archivo de notas—. Una nota escrita en el chat o un dibujo adjunto
     * no: «hola, ¿a qué hora?» no es una hoja, y un dibujo del propio proyecto pegado en su
     * chat ya está en el proyecto. Esos se unen a mano, desde el menú.
     */
    fun seUneSolo(m: Mensaje): Boolean =
        sePuedeUnir(m) && (m.clase == Clase.IMAGEN || m.clase == Clase.ARCHIVO)

    fun unir(context: Context, proyectos: ProyectosRepository, proyectoId: String, mensajes: List<Mensaje>, ahora: Long): Int {
        val antes = proyectos.porId(proyectoId)?.hojas?.size ?: return 0
        var n = 0
        for (m in mensajes.sortedBy { it.cuando }) {
            if (!sePuedeUnir(m)) continue
            val proyecto = proyectos.porId(proyectoId) ?: break
            val hojas = hojasDe(context, proyectos, proyecto, m, ahora, n)
            n += hojas.size.coerceAtLeast(1)
            for (h in hojas) proyectos.porId(proyectoId)?.let { proyectos.conHoja(it, h, ahora) }
        }
        // Se cuenta mirando el proyecto y no lo devuelto: el PDF que se vuelve documento
        // pone sus hojas por su cuenta.
        return (proyectos.porId(proyectoId)?.hojas?.size ?: antes) - antes
    }

    private fun hojasDe(context: Context, proyectos: ProyectosRepository, proyecto: Proyecto, m: Mensaje, ahora: Long, n: Int): List<Hoja> {
        val nombre = m.nombre.substringBeforeLast('.').ifBlank { primeraLinea(m.texto) }
        return when (m.clase) {
            Clase.NOTA -> listOf(Hoja(id = "hoja-$ahora-$n", nombre = nombre, nota = m.texto))
            Clase.DIBUJO -> listOfNotNull(copiaDelDibujo(context, m.referencia, "hoja-$ahora-$n", nombre, ahora, n))
            Clase.IMAGEN -> {
                // **El mismo dibujo que abre el chat, no una copia.** Desde el chat la foto
                // se abre con su apunte encima y la foto clavada al fondo; la hoja del
                // proyecto tiene que ser exactamente eso, y si fuera una copia lo anotado en
                // un sitio no se vería en el otro y la foto de la copia se podía mover (lo
                // reportó el usuario el 5-sep-2026). Ver [dibujoDeLaFoto].
                val ruta = m.ruta ?: return emptyList()
                listOfNotNull(hojaDeLaFotoDelChat(context, File(ruta), mimeDe(ruta), m.dibujoDeLaFoto, "hoja-$ahora-$n", nombre, ahora, n))
            }
            Clase.ARCHIVO -> when {
                esMarkdown(m) -> {
                    val texto = runCatching { File(m.ruta!!).readText() }.getOrNull() ?: return emptyList()
                    listOf(Hoja(id = "hoja-$ahora-$n", nombre = nombre, nota = texto))
                }
                esPdf(m) -> hojasDelPdf(context, proyectos, proyecto, File(m.ruta!!), nombre, ahora, n)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * El PDF como documento del proyecto, o como fotos de sus páginas.
     *
     * Se copia a la carpeta de proyectos antes de nada: el adjunto del chat se borra con el
     * mensaje, y el proyecto no puede quedarse apuntando a un archivo que ya no está.
     */
    private fun hojasDelPdf(context: Context, proyectos: ProyectosRepository, proyecto: Proyecto, pdf: File, nombre: String, ahora: Long, n: Int): List<Hoja> {
        val paginas = PdfDoc.pageCount(pdf.absolutePath)
        if (paginas <= 0) return emptyList()
        val carpeta = File(context.filesDir, "proyectos").also { it.mkdirs() }
        if (proyecto.pdfOrigen == null) {
            val origen = runCatching { pdf.copyTo(File(carpeta, "doc-$ahora.pdf"), overwrite = true) }.getOrNull()
                ?: return emptyList()
            val limpio = runCatching { pdf.copyTo(File(carpeta, "limpio-$ahora.pdf"), overwrite = true).path }.getOrNull()
            val nuevas = (0 until paginas.coerceAtMost(Proyectos.MAX_HOJAS - proyecto.hojas.size).coerceAtLeast(0))
                .map { Hoja(id = "h-$ahora-$it", pagina = it) }
            proyectos.guardar(
                proyecto.copy(pdfOrigen = origen.path, pdfLimpio = limpio, hojas = proyecto.hojas + nuevas, tocado = ahora)
            )
            // Ya están puestas: se devuelven vacías para que [unir] no las repita.
            return emptyList()
        }
        val hojas = ArrayList<Hoja>()
        for (i in 0 until paginas.coerceAtMost(TOPE_DE_PAGINAS_COMO_FOTO)) {
            val bmp = PdfDoc.render(pdf.absolutePath, i, ANCHO_DE_PAGINA) ?: continue
            val temporal = File(context.cacheDir, "pagina-$ahora-$i.jpg")
            runCatching { temporal.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            bmp.recycle()
            val hoja = hojaConFoto(context, temporal, "image/jpeg", "hoja-$ahora-${n + i}", "$nombre ${i + 1}", ahora, n + i)
            temporal.delete()
            if (hoja != null) hojas += hoja
        }
        return hojas
    }

    /**
     * La hoja de una foto del chat: apunta al dibujo de la foto ([dibujoDeLaFoto]) y, si el
     * chat aún no lo había abierto, lo crea **como lo crearía el editor**: la foto a su
     * tamaño, al fondo y clavada. Ver `DrawEditorActivity.colocarImagenInicial` y `fijarLaFoto`.
     */
    private fun hojaDeLaFotoDelChat(context: Context, archivo: File, mime: String, dibujo: String, id: String, nombre: String, ahora: Long, n: Int): Hoja? {
        if (File(ExcalidrawStore.rutaDe(context, dibujo)).exists()) return Hoja(id = id, nombre = nombre, dibujo = dibujo)
        if (!archivo.exists()) return null
        val foto = ExcalidrawStore.guardarImagen(context, archivo, mime) ?: return null
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.absolutePath, medidas)
        val ancho = medidas.outWidth.coerceAtLeast(1).toDouble()
        val alto = medidas.outHeight.coerceAtLeast(1).toDouble()
        val escena = Scene(
            elements = listOf(
                Element(
                    id = "foto-$ahora-$n", type = ElementType.IMAGE, x = 0.0, y = 0.0,
                    width = ancho, height = alto, seed = 1, fileId = foto.id, locked = true
                )
            ),
            files = mapOf(foto.id to foto)
        )
        ExcalidrawStore.guardar(context, dibujo, escena) ?: return null
        return Hoja(id = id, nombre = nombre, dibujo = dibujo)
    }

    private fun copiaDelDibujo(context: Context, dibujo: String?, id: String, nombre: String, ahora: Long, n: Int): Hoja? {
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, dibujo ?: return null)) ?: return null
        val copia = "dib-$ahora-$n"
        ExcalidrawStore.guardar(context, copia, escena) ?: return null
        return Hoja(id = id, nombre = nombre, dibujo = copia)
    }

    private fun hojaConFoto(context: Context, archivo: File, mime: String, id: String, nombre: String, ahora: Long, n: Int): Hoja? {
        if (!archivo.exists()) return null
        val foto = ExcalidrawStore.guardarImagen(context, archivo, mime) ?: return null
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.absolutePath, medidas)
        val ancho = medidas.outWidth.coerceAtLeast(1).toDouble()
        val alto = medidas.outHeight.coerceAtLeast(1).toDouble()
        val escala = minOf(1.0, 1400.0 / ancho)
        val escena = Scene(
            elements = listOf(
                Element(
                    id = "foto-$ahora-$n", type = ElementType.IMAGE, x = 0.0, y = 0.0,
                    width = ancho * escala, height = alto * escala, seed = 1, fileId = foto.id
                )
            ),
            files = mapOf(foto.id to foto)
        )
        val dibujo = "dib-$ahora-$n"
        ExcalidrawStore.guardar(context, dibujo, escena) ?: return null
        return Hoja(id = id, nombre = nombre, dibujo = dibujo)
    }

    private fun esPdf(m: Mensaje) = m.nombre.endsWith(".pdf", true) || m.ruta?.endsWith(".pdf", true) == true
    private fun esMarkdown(m: Mensaje) =
        m.nombre.endsWith(".md", true) || m.nombre.endsWith(".txt", true) ||
            m.ruta?.endsWith(".md", true) == true

    private fun mimeDe(ruta: String) = when (ruta.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private fun primeraLinea(texto: String) =
        texto.lineSequence().map { it.trim().trimStart('#', ' ') }.firstOrNull { it.isNotBlank() }?.take(40).orEmpty()
}
