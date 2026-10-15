package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.ia.Documentos
import com.forge.pixpin.mini.TodasLasTareas
import com.forge.pixpin.motor.PdfAHtml
import com.forge.pixpin.motor.PlanoDePdf
import com.forge.pixpin.motor.leerPdf
import java.io.File

/**
 * **Qué se indexa en el teléfono para «buscar en mis cosas»** (`apps/pixpin/src/asistente/
 * documentos.rs` del PC): los chats (sus notas, voz pasada a texto, listas y los nombres de lo que
 * se mandó; **sin** las respuestas del asistente, para que no se cite a sí mismo), los PDF
 * adjuntos (página a página, de su capa de texto), los `.txt`/`.csv`/`.md` y las notas de los
 * proyectos. Word y Excel no: el teléfono los lee con la biblioteca nativa, sin texto suelto.
 *
 * El índice es una **caché** en `cacheDir/documentos` (no se sincroniza ni va en la copia de
 * seguridad): se pone al día por firma justo antes de buscar, y solo se relee lo que cambió.
 */
object DocumentosDelTelefono {
    private var indice: Documentos.Indice? = null

    /** Lo más que se lee de un PDF en una puesta al día (lo primero suele ser lo que se busca). */
    private const val MAX_PAGINAS = 300

    private fun carpeta(c: Context) = File(c.cacheDir, "documentos")

    /** **Busca**, poniendo antes al día lo que haya cambiado. Un hilo a la vez (el índice no es seguro entre hilos). */
    @Synchronized
    fun buscar(c: Context, consulta: String, alcance: Documentos.Alcance, parar: () -> Boolean): List<Documentos.Hallazgo> {
        val ix = indice ?: Documentos.Indice.cargar(carpeta(c)).also { indice = it }
        ix.ponerAlDia(fuentes(c), lector(c), parar)
        return ix.buscar(consulta, alcance)
    }

    private fun nombreDelChat(app: PixPinApp, proyecto: String?) =
        proyecto?.let { app.proyectos.porId(it)?.nombre } ?: TodasLasTareas.GUARDADOS

    /** El id con que se nombra un chat en el índice ("" = Mensajes guardados). */
    fun idDe(proyecto: String?) = proyecto ?: ""

    private fun esTexto(r: String) = r.substringAfterLast('.').lowercase() in setOf("txt", "csv", "md")
    private fun esPdf(r: String) = r.lowercase().endsWith(".pdf")

    /** **Las fuentes de ahora**, con su firma. */
    fun fuentes(c: Context): List<Documentos.Fuente> {
        val app = c.applicationContext as PixPinApp
        val mensajes = MensajesStore(c).leer().filter { !it.enBuzon }
        val salida = ArrayList<Documentos.Fuente>()
        for ((proyecto, delChat) in mensajes.groupBy { it.proyecto }) {
            val propios = delChat.filter { !ChatDelAsistente.esRespuesta(it) }
            if (propios.isEmpty()) continue
            val chat = nombreDelChat(app, proyecto)
            // La firma del chat: cuántos mensajes, lo que suman y el último (cambia si cambia algo).
            val firma = Documentos.Firma(
                propios.sumOf { (it.texto.hashCode().toLong() and 0xffffffffL) + it.nombre.length + (it.transcripcion?.length ?: 0) },
                propios.maxOf { it.cuando } * 31 + propios.size
            )
            salida += Documentos.Fuente("chat:${idDe(proyecto)}", idDe(proyecto), chat, "Chat de $chat", Documentos.Tipo.CHAT,
                "", firma, Documentos.Abrir.Mensaje(proyecto, ""))
            for (m in propios) {
                val r = m.ruta ?: continue
                if (!(esPdf(r) || esTexto(r))) continue
                val f = File(r)
                val firmaF = Documentos.Firma.de(f) ?: continue
                salida += Documentos.Fuente("f:$r", idDe(proyecto), chat, m.nombre.ifBlank { f.name },
                    if (esPdf(r)) Documentos.Tipo.PDF else Documentos.Tipo.TEXTO, r, firmaF, Documentos.Abrir.Fichero(r, null))
            }
        }
        for (p in app.proyectos.proyectos.value.filter { !it.archivado }) for (h in p.hojas) {
            val nota = h.nota ?: continue
            if (nota.isBlank()) continue
            salida += Documentos.Fuente("nota:${p.id}/${h.id}", p.id, p.nombre, h.nombre.ifBlank { "Nota" }, Documentos.Tipo.NOTA, "",
                Documentos.Firma(nota.length.toLong(), nota.hashCode().toLong()), Documentos.Abrir.Mensaje(p.id, h.id))
        }
        return salida
    }

    private fun lector(c: Context) = Documentos.Lector { f ->
        when (f.tipo) {
            Documentos.Tipo.CHAT -> {
                val proyecto = f.proyecto.ifEmpty { null }
                MensajesStore(c).leer().filter { it.proyecto == proyecto && !it.enBuzon && !ChatDelAsistente.esRespuesta(it) }
                    .mapNotNull { unidadDe(it, proyecto) }
            }
            Documentos.Tipo.PDF -> {
                val archivo = leerPdf(File(f.ruta).readBytes()) ?: error("no se pudo leer el PDF")
                if (archivo.cifrado) error("el PDF esta protegido")
                archivo.paginas().indices.take(MAX_PAGINAS).map { n ->
                    val lineas = PdfAHtml.lineasEnOrden(PdfAHtml.trozosDe(PlanoDePdf.de(archivo, n)?.textos.orEmpty()))
                    Documentos.Unidad(lineas.joinToString("\n") { it.texto }, pagina = n + 1)
                }
            }
            Documentos.Tipo.TEXTO -> listOf(Documentos.Unidad(File(f.ruta).readText().take(2_000_000)))
            Documentos.Tipo.NOTA -> {
                val (p, h) = f.clave.removePrefix("nota:").split('/', limit = 2)
                val app = c.applicationContext as PixPinApp
                listOf(Documentos.Unidad(app.proyectos.porId(p)?.hojas?.firstOrNull { it.id == h }?.nota.orEmpty()))
            }
            else -> emptyList()
        }
    }

    /** Un mensaje como unidad del chat: lo que dice, o el nombre de lo que se mandó (`adjunto`). */
    private fun unidadDe(m: Mensaje, proyecto: String?): Documentos.Unidad? {
        val abrir = Documentos.Abrir.Mensaje(proyecto, m.id)
        val texto = when (m.clase) {
            Clase.NOTA -> m.texto
            Clase.VOZ -> m.transcripcion ?: m.texto
            Clase.MINIAPP -> m.texto
            else -> null
        }
        if (!texto.isNullOrBlank()) return Documentos.Unidad(texto, abrir = abrir, cuando = m.cuando)
        val nombre = m.nombre.ifBlank { m.ruta?.let { File(it).name }.orEmpty() }
        if (nombre.isBlank()) return null
        return Documentos.Unidad(nombre + (m.texto.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""), seccion = "adjunto", abrir = abrir, cuando = m.cuando)
    }
}
