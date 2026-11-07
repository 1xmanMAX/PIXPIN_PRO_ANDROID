package com.forge.pixpin.asistente

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Artefactos
import com.forge.pixpin.ia.Dxf
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **Lo que crea el asistente, en el chat** ([Artefactos]): cada archivo entra como un adjunto más, del aparato «IA», y
 * se abre con su visor (la miniapp en el visor web, el PDF en el lector, el DXF en el de planos). Crear otra vez el
 * mismo nombre en el mismo chat lo **reemplaza** (la misma burbuja): así «cámbiale el color» no llena el chat de copias.
 *
 * Editar y leer valen también para los archivos de texto que el usuario adjuntó (su copia dentro de la app).
 */
class ArtefactosDelChat(
    private val contexto: Context,
    private val almacen: MensajesStore,
    private val chat: String?,
) {
    /** El último archivo creado o cambiado en este turno (por si el pedido decía «…y ábrelo»). */
    @Volatile var ultimo: File? = null
        private set
    /** Su mensaje en el chat. */
    @Volatile var ultimoMensaje: String? = null
        private set

    fun hacer(p: Artefactos.Pedido): Result<String> = runCatching {
        when (p) {
            is Artefactos.Pedido.Crear -> crear(p)
            is Artefactos.Pedido.Editar -> editar(p)
            is Artefactos.Pedido.Leer -> leer(p)
            is Artefactos.Pedido.Pdf -> pdf(p)
            is Artefactos.Pedido.Plano -> plano(p)
        }
    }

    // ------------------------------------------------------------------------------------ los archivos del chat

    private fun delChat(): List<Mensaje> = almacen.leer().filter { it.proyecto == chat && it.clase == Clase.ARCHIVO && it.ruta != null && !it.enBuzon }

    private fun esTexto(m: Mensaje) = Artefactos.extension(m.nombre) in Artefactos.EXTENSIONES + "dxf"

    /** El archivo del chat con ese nombre: igual, sin mayúsculas, o el único que lo contiene (el más reciente). */
    private fun buscar(nombre: String): Mensaje? {
        val todos = delChat().filter { File(it.ruta!!).isFile }.sortedByDescending { it.cuando }
        val n = nombre.trim().lowercase()
        return todos.firstOrNull { it.nombre == nombre.trim() }
            ?: todos.firstOrNull { it.nombre.lowercase() == n }
            ?: todos.firstOrNull { it.nombre.lowercase().substringBeforeLast('.') == n.substringBeforeLast('.') }
            ?: todos.filter { it.nombre.lowercase().contains(n.substringBeforeLast('.')) }.takeIf { it.size == 1 }?.first()
    }

    private fun lista(): String {
        val t = delChat().filter { esTexto(it) || it.nombre.lowercase().endsWith(".pdf") }.sortedByDescending { it.cuando }.take(30)
        return if (t.isEmpty()) "No hay archivos de texto en este chat." else "Archivos del chat (del mas nuevo al mas viejo):\n" +
            t.joinToString("\n") { "- ${it.nombre} (${it.bytes / 1024 + 1} KB${if (it.aparato == ChatDelAsistente.APARATO) ", tuyo" else ""})" }
    }

    /** Lo dentro de la app: lo único que se puede reescribir (nunca un archivo de fuera). */
    private fun esDeLaApp(f: File): Boolean = runCatching {
        val c = f.canonicalPath
        c.startsWith(contexto.filesDir.canonicalPath) || c.startsWith(almacen.carpetaDeAdjuntos().canonicalPath)
    }.getOrDefault(false)

    /**
     * **Deja [bytes] en el chat como [nombre]**: si el asistente ya hizo uno con ese nombre en este chat, lo reemplaza
     * (la misma burbuja, más arriba en la conversación no se toca); si no, uno nuevo.
     */
    private fun guardar(nombre: String, bytes: ByteArray, descripcion: String): Pair<File, Boolean> {
        val ahora = System.currentTimeMillis()
        val previo = delChat().filter { it.aparato == ChatDelAsistente.APARATO && it.nombre.equals(nombre, ignoreCase = true) }.maxByOrNull { it.cuando }
        if (previo != null) {
            val f = File(previo.ruta!!)
            if (esDeLaApp(f)) {
                f.writeBytes(bytes)
                almacen.actualizar(previo.id) { it.copy(bytes = f.length(), texto = descripcion.ifBlank { it.texto }) }
                ultimo = f; ultimoMensaje = previo.id
                return f to true
            }
        }
        val ext = Artefactos.extension(nombre)
        val f = File(almacen.carpetaDeAdjuntos(), "ia-$ahora-${UUID.randomUUID().toString().take(6)}.$ext").apply { writeBytes(bytes) }
        val id = UUID.randomUUID().toString()
        almacen.anadir(
            Mensaje(
                id = id, cuando = ahora, clase = Clase.ARCHIVO, ruta = f.path, nombre = nombre,
                bytes = f.length(), texto = descripcion, proyecto = chat, aparato = ChatDelAsistente.APARATO
            )
        )
        ultimo = f; ultimoMensaje = id
        return f to false
    }

    private fun conRevision(nombre: String, texto: String): String {
        val r = Artefactos.revisar(nombre, texto)
        return if (r.isEmpty()) "Revision: bien (cerrado y con lo necesario)." else "Revision, arreglalo con editar_archivo:\n" + r.joinToString("\n") { "- $it" }
    }

    // ------------------------------------------------------------------------------------ crear, editar, leer

    private fun crear(p: Artefactos.Pedido.Crear): String {
        val (_, reemplazado) = guardar(p.nombre, p.contenido.toByteArray(Charsets.UTF_8), p.descripcion)
        val lineas = p.contenido.lines().size
        return "${if (reemplazado) "Reemplazado" else "Creado"} «${p.nombre}» en el chat ($lineas lineas, ${p.contenido.length / 1024 + 1} KB); el usuario lo abre tocandolo. " +
            conRevision(p.nombre, p.contenido)
    }

    private fun editar(p: Artefactos.Pedido.Editar): String {
        val m = buscar(p.nombre) ?: error("no hay ningun archivo «${p.nombre}» en este chat. ${lista()}")
        if (!esTexto(m)) error("«${m.nombre}» no es un archivo de texto que se pueda editar")
        val f = File(m.ruta!!)
        if (!esDeLaApp(f)) error("«${m.nombre}» no esta dentro de PinDesk: no se puede cambiar")
        val antes = f.readText()
        val (despues, hechos) = Artefactos.editar(antes, p.cambios).getOrThrow()
        f.writeText(despues)
        almacen.actualizar(m.id) { it.copy(bytes = f.length()) }
        ultimo = f; ultimoMensaje = m.id
        return "Cambiado «${m.nombre}»: $hechos sustitucion(es); ahora tiene ${despues.lines().size} lineas. " + conRevision(m.nombre, despues)
    }

    private fun leer(p: Artefactos.Pedido.Leer): String {
        val n = p.nombre ?: return lista()
        val m = buscar(n) ?: error("no hay ningun archivo «$n» en este chat. ${lista()}")
        if (m.nombre.lowercase().endsWith(".pdf")) error("«${m.nombre}» es un PDF: para leerlo usa ocr")
        if (!esTexto(m)) error("«${m.nombre}» no es un archivo de texto")
        val f = File(m.ruta!!)
        if (f.length() > 3_000_000) error("«${m.nombre}» es demasiado grande para leerlo entero")
        return "«${m.nombre}»:\n" + Artefactos.numerar(f.readText(), p.desde, p.hasta)
    }

    // ------------------------------------------------------------------------------------ PDF

    private fun pdf(p: Artefactos.Pedido.Pdf): String {
        val carpeta = File(contexto.cacheDir, "asistente-pdf").apply { mkdirs() }
        val temporal = File(carpeta, "pdf-${System.nanoTime()}.pdf")
        try {
            val motivo = htmlAPdf(p.html, temporal, p.papel, p.horizontal)
            if (motivo != null) error(motivo)
            val hojas = runCatching {
                android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(temporal, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
            }.getOrDefault(0)
            val (_, reemplazado) = guardar(p.nombre, temporal.readBytes(), "")
            val avisos = Artefactos.revisar("documento.html", p.html).filter { !it.contains("viewport") }
            return "${if (reemplazado) "Reemplazado" else "Creado"} «${p.nombre}» en el chat: $hojas hoja(s) ${p.papel}${if (p.horizontal) " horizontal" else ""}, ${temporal.length() / 1024 + 1} KB." +
                if (avisos.isEmpty()) "" else " Avisos del HTML (si salio mal, vuelve a crear_pdf corregido): " + avisos.joinToString("; ")
        } finally {
            temporal.delete()
        }
    }

    /**
     * **HTML → PDF con el motor de Chrome del teléfono**: un `WebView` sin pantalla carga el documento y su
     * `PrintDocumentAdapter` lo escribe (como «Guardar como PDF», sin el diálogo; ver [android.print.PdfDesdeWeb]).
     * Null si salió bien; si no, por qué. Bloquea (hasta 60 s): se llama desde el hilo del turno, nunca el principal.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun htmlAPdf(html: String, destino: File, papel: String, horizontal: Boolean): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return "no se puede hacer el PDF desde el hilo de la pantalla"
        val listo = CountDownLatch(1)
        var bien = false
        var fallo: String? = null
        val principal = Handler(Looper.getMainLooper())
        var web: WebView? = null
        principal.post {
            try {
                val w = WebView(contexto)
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.allowFileAccess = false
                w.settings.allowContentAccess = false
                var hecho = false
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (hecho) return
                        hecho = true
                        // Un respiro para fuentes, imágenes y el JS que pinte algo al cargar.
                        principal.postDelayed({
                            val tamano = when (papel) {
                                "carta" -> android.print.PrintAttributes.MediaSize.NA_LETTER
                                "A3" -> android.print.PrintAttributes.MediaSize.ISO_A3
                                "A5" -> android.print.PrintAttributes.MediaSize.ISO_A5
                                else -> android.print.PrintAttributes.MediaSize.ISO_A4
                            }.let { if (horizontal) it.asLandscape() else it.asPortrait() }
                            val atributos = android.print.PrintAttributes.Builder()
                                .setMediaSize(tamano)
                                .setResolution(android.print.PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                                .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
                                .build()
                            android.print.PdfDesdeWeb.escribir(view.createPrintDocumentAdapter("PinDesk"), atributos, destino) { ok ->
                                bien = ok
                                if (!ok) fallo = "el telefono no pudo pasar el HTML a PDF"
                                listo.countDown()
                            }
                        }, 700)
                    }
                }
                w.loadDataWithBaseURL("https://pindesk.local/", html, "text/html", "utf-8", null)
            } catch (e: Throwable) {
                fallo = "no se pudo preparar el PDF: ${e.message}"
                listo.countDown()
            }
        }
        val llego = listo.await(60, TimeUnit.SECONDS)
        principal.post { runCatching { web?.destroy() } }
        if (!llego) return "el PDF tardo demasiado (¿una imagen o una fuente que no carga?)"
        return if (bien && destino.length() > 0) null else fallo ?: "no salio el PDF"
    }

    // ------------------------------------------------------------------------------------ planos

    private fun plano(p: Artefactos.Pedido.Plano): String {
        val dxf = Dxf.escribir(p.plano)
        val (_, reemplazado) = guardar(p.nombre, dxf.toByteArray(Charsets.US_ASCII), "")
        return "${if (reemplazado) "Reemplazado" else "Creado"} «${p.nombre}» en el chat (DXF, se abre en el visor de planos): ${Dxf.resumen(p.plano)}. " +
            "Comprueba que las medidas son las pedidas; para cambiarlo, vuelve a llamar a crear_plano con el plano entero corregido."
    }
}
