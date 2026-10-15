package com.forge.pixpin.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Photo
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.PaquetePixpin
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Scene
import java.io.File

/**
 * **Lo común del «Exportar» de los visores** (14-oct-2026; el PC lo hizo el 13-oct: «revisa que
 * todos los visores tengan PDF, HTML y .pixpin, además de los clásicos como imagen o su formato
 * original»). No es otra hoja: es la [HojaDeCompartir] de siempre, y aquí están los formatos que
 * cada visor le pasa sin tener que escribirlos otra vez.
 *
 * - **Original**: el archivo tal cual.
 * - **.pixpin**: si el archivo es un adjunto del chat, **su proyecto entero con su chat** (con lo
 *   anotado sobre él, las capas `anot-<uid>`, que viajan con el mensaje: [com.forge.pixpin.sincro.ChatQueViaja]);
 *   si es de un mensaje sin proyecto, un proyecto con ese mensaje; si no está en el chat, un
 *   `.pixpin` con el archivo como mensaje (lo anotado fuera del chat no va), como el PC.
 * - **La vista** (un visor que no tiene hojas: el 3D, una hoja de cálculo): la imagen de lo que se
 *   ve, y con ella un PDF y una página web, como el PC con sus visores 3D.
 * - **Una página de un PDF** como imagen, con lo anotado encima.
 */
object ExportarVisor {

    fun carpeta(c: Context): File = File(c.cacheDir, "share").apply { mkdirs() }

    /** Un nombre de archivo limpio, con [extension]. */
    fun nombre(titulo: String, extension: String): String = ExportarProyecto.nombreDeArchivo(titulo.ifBlank { "archivo" }) + "." + extension

    /** **El archivo tal cual.** El rótulo es su terminación (DWG, XLSX…). */
    fun original(c: Context, ruta: String, nombre: String, detalle: String = "Tal cual, sin cambiar nada"): Compartible.Formato =
        Compartible.Formato(
            "original", Icons.Filled.Description, nombre.substringAfterLast('.', "").uppercase().ifBlank { "Original" },
            Compartible.NINGUNA,
            generar = {
                val desde = File(ruta)
                if (!desde.isFile) return@Formato null
                val limpio = nombre.replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80).ifBlank { desde.name }
                val destino = File(carpeta(c), limpio)
                desde.copyTo(destino, overwrite = true)
                Compartible.Salida(destino, AbrirCon.mimeDe(destino).takeIf { it != "*/*" } ?: "application/octet-stream", detalle)
            }
        )

    /**
     * **El `.pixpin`** del archivo: ver arriba. Lee el chat: solo dentro de [Compartible.Formato.generar].
     * [junto]: los ids de los mensajes que van con él si no vive en un proyecto (las fotos y audios
     * de una lección): sin proyecto, el `.pixpin` lleva el mensaje y esos.
     */
    fun pixpin(c: Context, ruta: String, nombre: String, junto: List<String> = emptyList()): Compartible.Formato =
        Compartible.Formato(
            "editable", Icons.Filled.FolderZip, ".pixpin", Compartible.NINGUNA,
            generar = { paquete(c, ruta, nombre, junto) }
        )

    private fun paquete(c: Context, ruta: String, nombre: String, junto: List<String> = emptyList()): Compartible.Salida? {
        val archivo = File(ruta)
        if (!archivo.isFile) return null
        val app = c.applicationContext as? com.forge.pixpin.PixPinApp
        val mensajes = com.forge.pixpin.guardados.MensajesStore(c).leer()
        val suyo = mensajes.firstOrNull { it.ruta == archivo.absolutePath } ?: mensajes.firstOrNull { it.ruta == ruta }
        val titulo = nombre.substringBeforeLast('.').ifBlank { "archivo" }
        // Los códigos puestos antes de empaquetar, como al enviar por Wi-Fi.
        runCatching { com.forge.pixpin.sincro.Red.disco(c).sellar() }
        val proyecto = suyo?.proyecto?.let { id -> app?.proyectos?.let { it.recargar(); it.porId(id) } }
        val (p, chat, detalle) = when {
            proyecto != null -> Triple(
                proyecto,
                com.forge.pixpin.sincro.ChatQueViaja.preparar(proyecto, com.forge.pixpin.sincro.ChatQueViaja.delProyecto(c, proyecto), c.filesDir),
                "El proyecto «${proyecto.nombre}» con su chat y lo anotado"
            )
            suyo != null -> {
                val solo = com.forge.pixpin.motor.Proyecto(id = "suelto-${suyo.id}", nombre = titulo)
                val conEllos = listOf(suyo) + mensajes.filter { it.id in junto && it.id != suyo.id }
                Triple(solo, com.forge.pixpin.sincro.ChatQueViaja.preparar(solo, conEllos, c.filesDir), "El archivo con lo anotado, como mensaje")
            }
            else -> {
                val ahora = System.currentTimeMillis()
                val m = com.forge.pixpin.guardados.Mensaje(
                    id = "m-$ahora", cuando = ahora, clase = com.forge.pixpin.guardados.Clase.ARCHIVO,
                    ruta = archivo.absolutePath, nombre = nombre, bytes = archivo.length()
                )
                val solo = com.forge.pixpin.motor.Proyecto(id = "suelto-$ahora", nombre = titulo)
                Triple(solo, com.forge.pixpin.sincro.ChatQueViaja.preparar(solo, listOf(m), c.filesDir), "El archivo como mensaje (no está en el chat)")
            }
        }
        val destino = File(carpeta(c), nombre(p.nombre, PaquetePixpin.EXTENSION))
        PaquetePixpin.escribir(
            c, p, destino,
            croquisDe = { id -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(c, id) },
            chat = chat?.texto, adjuntosDelChat = chat?.adjuntos.orEmpty()
        ) ?: return null
        return Compartible.Salida(destino, PaquetePixpin.MIME_TYPE, detalle)
    }

    /**
     * **El `.pixpin` del proyecto donde vive lo que se ve** (14-oct-2026 en el PC,
     * `Exportable::proyecto`): para lo que no es un adjunto con ruta, como un croquis 3D. El proyecto
     * entero con su chat y lo anotado; si [deQuien] no da ninguno, [suelto] (o nada).
     */
    fun pixpinDelProyecto(c: Context, deQuien: (List<com.forge.pixpin.motor.Proyecto>, List<com.forge.pixpin.guardados.Mensaje>) -> com.forge.pixpin.motor.Proyecto?, suelto: (() -> Compartible.Salida?)? = null): Compartible.Formato =
        Compartible.Formato(
            "editable", Icons.Filled.FolderZip, ".pixpin", Compartible.NINGUNA,
            generar = {
                val app = c.applicationContext as? com.forge.pixpin.PixPinApp
                runCatching { com.forge.pixpin.sincro.Red.disco(c).sellar() }
                app?.proyectos?.recargar()
                val p = deQuien(app?.proyectos?.proyectos?.value.orEmpty(), com.forge.pixpin.guardados.MensajesStore(c).leer())
                if (p == null) suelto?.invoke() else {
                    val chat = com.forge.pixpin.sincro.ChatQueViaja.preparar(p, com.forge.pixpin.sincro.ChatQueViaja.delProyecto(c, p), c.filesDir)
                    val destino = File(carpeta(c), nombre(p.nombre, PaquetePixpin.EXTENSION))
                    PaquetePixpin.escribir(
                        c, p, destino,
                        croquisDe = { id -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(c, id) },
                        chat = chat?.texto, adjuntosDelChat = chat?.adjuntos.orEmpty()
                    )?.let { Compartible.Salida(it, PaquetePixpin.MIME_TYPE, "El proyecto «${p.nombre}» con su chat") }
                }
            }
        )

    // ------------------------------------------------------------------ imágenes

    /** Un mapa de bits a PNG o JPG en `cache/share`. Lo recicla. */
    fun guardarImagen(c: Context, mapa: Bitmap, titulo: String, jpg: Boolean): Compartible.Salida {
        val destino = File(carpeta(c), nombre(titulo, if (jpg) "jpg" else "png"))
        destino.outputStream().buffered().use { o ->
            if (jpg) {
                // El JPG no tiene transparencia: sobre blanco, no sobre negro.
                val opaco = Bitmap.createBitmap(mapa.width, mapa.height, Bitmap.Config.ARGB_8888)
                Canvas(opaco).apply { drawColor(Color.WHITE); drawBitmap(mapa, 0f, 0f, null) }
                opaco.compress(Bitmap.CompressFormat.JPEG, 90, o)
                opaco.recycle()
            } else mapa.compress(Bitmap.CompressFormat.PNG, 100, o)
        }
        val resumen = "${mapa.width} × ${mapa.height} px"
        if (!mapa.isRecycled) mapa.recycle()
        return Compartible.Salida(destino, if (jpg) "image/jpeg" else "image/png", resumen)
    }

    /** PNG y JPG de lo que dé [foto] (con las páginas que admita: [paginas], como en [Compartible.Formato]). */
    fun imagenes(c: Context, titulo: (List<String>) -> String, paginas: Int = Compartible.NINGUNA, foto: suspend (List<String>) -> Bitmap?): List<Compartible.Formato> =
        listOf(false, true).map { jpg ->
            Compartible.Formato(
                if (jpg) "jpg" else "png", if (jpg) Icons.Filled.Photo else Icons.Filled.Image, if (jpg) "JPG" else "PNG", paginas,
                generar = { claves -> foto(claves)?.let { guardarImagen(c, it, titulo(claves), jpg) } }
            )
        }

    /**
     * **La vista, en todos los formatos** (el visor 3D, la hoja de cálculo): PNG, JPG, un PDF de una
     * hoja con la imagen y una página web con ella. [foto] da lo que se ve ahora.
     */
    fun deLaVista(c: Context, titulo: String, foto: suspend () -> Bitmap?): List<Compartible.Formato> =
        imagenes(c, { titulo }) { foto() } + listOf(
            Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.NINGUNA, generar = {
                val mapa = foto() ?: return@Formato null
                val destino = File(carpeta(c), nombre(titulo, "pdf"))
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    // La hoja mide lo que la imagen a 150 ppp, sin pasar de un A3.
                    val k = minOf(72f / 150f, 1190.55f / maxOf(mapa.width, mapa.height))
                    val w = (mapa.width * k).toInt().coerceAtLeast(1); val h = (mapa.height * k).toInt().coerceAtLeast(1)
                    val hoja = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, 1).create())
                    hoja.canvas.scale(k, k)
                    hoja.canvas.drawBitmap(mapa, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(hoja)
                    destino.outputStream().buffered().use { doc.writeTo(it) }
                } finally { doc.close(); if (!mapa.isRecycled) mapa.recycle() }
                Compartible.Salida(destino, "application/pdf", "La vista, como se ve ahora")
            }),
            Compartible.Formato("web", Icons.Filled.Language, "Página web", Compartible.NINGUNA, generar = {
                val mapa = foto() ?: return@Formato null
                val datos = java.io.ByteArrayOutputStream().also { mapa.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                val ancho = mapa.width
                if (!mapa.isRecycled) mapa.recycle()
                val destino = File(carpeta(c), nombre(titulo, "html"))
                val t = titulo.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                destino.writeText(
                    "<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<title>$t</title><style>body{margin:0;background:#21262f;color:#e8e8ec;font:14px system-ui,sans-serif}" +
                        "h1{font-size:16px;margin:0;padding:10px 14px}img{display:block;max-width:100%;width:${ancho}px;height:auto;margin:0 auto}</style></head>" +
                        "<body><h1>$t</h1><img alt=\"$t\" src=\"data:image/jpeg;base64,${java.util.Base64.getEncoder().encodeToString(datos)}\"></body></html>\n"
                )
                Compartible.Salida(destino, com.forge.pixpin.motor.ExportarHtml.MIME_TYPE, "La vista, como imagen")
            })
        )

    /**
     * **Una página de un PDF con lo anotado**, a [ancho] píxeles (la tinta va en las unidades de
     * [PdfDoc.PAGE_WIDTH], como en el lector y en [com.forge.pixpin.guardados.paginaAnotada]).
     */
    fun paginaConTinta(c: Context, pdf: String, pagina: Int, escena: Scene?, ancho: Int = 2 * PdfDoc.PAGE_WIDTH): Bitmap? {
        val hoja = PdfDoc.render(pdf, pagina, ancho) ?: return null
        val mapa = if (hoja.isMutable) hoja else hoja.copy(Bitmap.Config.ARGB_8888, true).also { hoja.recycle() }
        val e = escena?.takeIf { it.contenidoVisible.isNotEmpty() } ?: return mapa
        runCatching {
            com.forge.pixpin.motor.Renderer(
                { id -> e.files[id]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } },
                com.forge.pixpin.motor.DrawFonts.provider(c), paraExportar = true
            ).renderScene(
                Canvas(mapa),
                e.copy(elements = e.contenidoVisible, viewport = com.forge.pixpin.motor.Viewport(scrollX = 0.0, scrollY = 0.0, zoom = mapa.width.toDouble() / PdfDoc.PAGE_WIDTH)),
                mapa.width.toDouble(), mapa.height.toDouble()
            )
        }
        return mapa
    }

    /**
     * **Lo que se ve en una superficie de la tarjeta** (el visor 3D: un `GLSurfaceView`), copiado de
     * la pantalla con `PixelCopy`: la ventana pinta con la tarjeta y no hay otra cosa que leer.
     */
    suspend fun fotoDeLaTarjeta(superficie: android.view.SurfaceView): Bitmap? {
        val w = superficie.width; val h = superficie.height
        if (w <= 0 || h <= 0) return null
        val mapa = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bien = kotlinx.coroutines.suspendCancellableCoroutine { k ->
            runCatching {
                android.view.PixelCopy.request(superficie, mapa, { r -> if (k.isActive) k.resumeWith(Result.success(r == android.view.PixelCopy.SUCCESS)) },
                    android.os.Handler(android.os.Looper.getMainLooper()))
            }.onFailure { if (k.isActive) k.resumeWith(Result.success(false)) }
        }
        return if (bien) mapa else { mapa.recycle(); null }
    }

    /** Lo que se ve en una vista corriente (la hoja de cálculo): dibujada en un mapa de bits, en el hilo de la pantalla. */
    suspend fun fotoDe(v: android.view.View, fondo: Int = Color.WHITE): Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        if (v.width <= 0 || v.height <= 0) return@withContext null
        val mapa = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        Canvas(mapa).also { it.drawColor(fondo); v.draw(it) }
        mapa
    }

    /** Las páginas de un documento de [cuantas] hojas, para elegir cuál va en imagen. */
    fun paginasDe(cuantas: Int): List<Compartible.Pagina> = List(cuantas) { Compartible.Pagina("p$it", "Página ${it + 1}") }

    fun paginaDeClave(clave: String?): Int? = clave?.removePrefix("p")?.toIntOrNull()
}
