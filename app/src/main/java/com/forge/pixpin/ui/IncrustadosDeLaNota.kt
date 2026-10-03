package com.forge.pixpin.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.motor.DrawExport
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motormd.ContextoDeLaNota
import com.forge.pixpin.motormd.Incrustados
import com.forge.pixpin.motormd.VistaIncrustada
import com.forge.pixpin.sincro.Codigos
import java.io.File

/**
 * **Lo que la nota necesita de la aplicación** para enseñar los mensajes, hojas y archivos que
 * mete el PC: qué dice cada mensaje (por su código único), la miniatura de una hoja y cómo abrir
 * cada cosa con el lector de PixPin. Ver [ContextoDeLaNota].
 */
class IncrustadosDeLaNota(context: Context) : ContextoDeLaNota {
    private val ctx = context
    private val app = context.applicationContext as PixPinApp
    private val mensajes: List<Mensaje> by lazy { runCatching { com.forge.pixpin.guardados.MensajesStore(app).leer() }.getOrDefault(emptyList()) }

    private fun mensaje(codigo: String) = mensajes.firstOrNull { it.id == codigo || Codigos.unico(it) == codigo }

    private fun hora(ms: Long): String {
        val hoy = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val mismoDia = hoy.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && hoy.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
        return java.text.SimpleDateFormat(if (mismoDia) "HH:mm" else "d MMM, HH:mm", java.util.Locale("es")).format(java.util.Date(ms))
    }

    private fun foto(ruta: String?, lado: Int = 720) = ruta?.takeIf { File(it).isFile }?.let { r ->
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(r, o)
            var m = 1
            while (o.outWidth / m > lado) m *= 2
            BitmapFactory.decodeFile(r, BitmapFactory.Options().apply { inSampleSize = m })?.asImageBitmap()
        }.getOrNull()
    }

    private fun lienzo(dibujo: String?) = dibujo?.let { id ->
        runCatching {
            val e = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(ctx, id)) ?: return@runCatching null
            DrawExport.aBitmap(e, 0.35) { f -> e.files[f]?.path?.let { com.forge.pixpin.pin.ImageStore.load(it) } }?.asImageBitmap()
        }.getOrNull()
    }

    override fun vista(enlace: Incrustados.Enlace): VistaIncrustada? = when (enlace) {
        is Incrustados.Enlace.Mensaje -> mensaje(enlace.mensaje)?.let { m ->
            val chapa = Codigos.deChat(m)?.let { "#$it" }.orEmpty()
            when (m.clase) {
                Clase.IMAGEN -> VistaIncrustada(m.texto.ifBlank { m.nombre.ifBlank { "Foto" } }, chapa = chapa, hora = hora(m.cuando), miniatura = foto(m.ruta))
                Clase.ARCHIVO -> VistaIncrustada(m.nombre.ifBlank { m.ruta?.substringAfterLast('/') ?: "Archivo" },
                    detalle = m.ruta?.let { peso(it) }.orEmpty(), chapa = chapa, hora = hora(m.cuando), archivo = m.nombre.ifBlank { m.ruta })
                Clase.VOZ -> VistaIncrustada("🎤 " + m.nombre.ifBlank { "Nota de voz" },
                    detalle = (m.transcripcion ?: "").take(160), chapa = chapa, hora = hora(m.cuando))
                Clase.DIBUJO, Clase.PAGINA -> VistaIncrustada(m.nombre.ifBlank { "Lienzo" }, chapa = chapa, hora = hora(m.cuando), miniatura = lienzo(m.referencia))
                else -> VistaIncrustada(m.texto.ifBlank { m.nombre }.take(400), chapa = chapa, hora = hora(m.cuando))
            }
        }
        is Incrustados.Enlace.Hoja -> PaginasVivas.hoja(app, enlace.hoja, enlace.proyecto)?.let { e ->
            val h = e.hoja
            val clase = when {
                h.tabla != null -> "Tabla"; h.nota != null -> "Nota"; h.croquis != null -> "Croquis 3D"
                h.pagina != null -> "Página ${h.pagina!! + 1}"; else -> "Lienzo"
            }
            val mini = when {
                h.pagina != null -> listOfNotNull(e.proyecto.pdfOrigen).firstOrNull()?.let { com.forge.pixpin.motor.PdfMiniaturas.enMemoria(it, h.pagina!!)?.asImageBitmap() }
                    ?: lienzo(h.dibujo)
                h.dibujo != null -> lienzo(h.dibujo)
                else -> null
            }
            VistaIncrustada(h.nombre.ifBlank { clase }, detalle = h.nota?.take(160).orEmpty(), chapa = "$clase · ${e.proyecto.nombre}", miniatura = mini)
        }
    }

    override fun abrirEnlace(enlace: Incrustados.Enlace) {
        val url = when (enlace) {
            is Incrustados.Enlace.Hoja -> "pixpin:hoja=${enlace.proyecto}/${enlace.hoja}"
            is Incrustados.Enlace.Mensaje -> "pixpin:mensaje=${enlace.proyecto}/${enlace.mensaje}"
        }
        EnlacesPixpin.abrir(ctx, url)
    }

    /** Con el lector de PixPin: el PDF en el suyo, y lo demás por su mensaje del chat o fuera. */
    override fun abrirArchivo(ruta: String, nombre: String) {
        if (ruta.endsWith(".pdf", ignoreCase = true)) {
            com.forge.pixpin.pdf.LectorPdfActivity.abrir(ctx, ruta, nombre); return
        }
        val m = mensajes.firstOrNull { it.ruta == ruta }
        if (m != null) com.forge.pixpin.guardados.MensajesActivity.abrirYAbrir(ctx, m.id)
        else AbrirCon.abrir(ctx, File(ruta))
    }

    override fun abrirHoja(codigo: String) {
        val e = PaginasVivas.hoja(app, codigo) ?: return
        abrirHoja(ctx, app, e.proyecto, com.forge.pixpin.motor.HojasDelProyecto.Pagina(e.hoja))
    }

    override fun peso(ruta: String): String? = File(ruta).takeIf { it.isFile }?.length()?.let { com.forge.pixpin.motor.Detalle.legible(it) }
}
