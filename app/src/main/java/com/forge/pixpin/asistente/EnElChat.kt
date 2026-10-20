package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.sinAcentos
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * **Lo que hace el ejecutor del chat y le devuelve al modelo lo que salió** (PC `EN_EL_CHAT`,
 * `asistente/en_el_chat.rs` y `ejecutor.rs`, 15-17 oct): leer en voz alta, aligerar un PDF, pasar un
 * audio a texto, leer un Excel o un documento entero, contar lo hecho, y los lienzos del chat
 * (listarlos, crear uno, pasar a otro: cambian adónde va lo que se dibuja en el resto del turno).
 *
 * Como en el PC, son solo para la nube (el modelo local no las ve). Todo en el hilo del turno.
 */
class EnElChat(
    private val contexto: Context,
    private val almacen: MensajesStore,
    private val chat: String?,
    /** El lienzo destino del turno y cómo cambiarlo. */
    private val destino: () -> String?,
    private val alCambiarDeLienzo: (String?) -> Unit
) {
    private fun texto(o: JsonObject, campo: String): String? =
        (o[campo] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun delChat() = almacen.leer().filter { it.proyecto == chat && !it.enBuzon }

    /** El adjunto que mejor casa con [nombre] (o el más reciente que cumpla [vale]). */
    private fun adjunto(nombre: String?, vale: (Mensaje, File) -> Boolean): Mensaje? {
        val candidatos = delChat().filter { m -> m.ruta != null && File(m.ruta).isFile && vale(m, File(m.ruta)) }
        if (nombre == null) return candidatos.lastOrNull()
        val q = sinAcentos(nombre)
        return candidatos.lastOrNull { sinAcentos(it.nombre).contains(q) || sinAcentos(File(it.ruta!!).name).contains(q) }
            ?: candidatos.lastOrNull { m -> q.split(' ').filter { it.length > 2 }.any { sinAcentos(m.nombre).contains(it) } }
    }

    private fun extension(m: Mensaje, f: File) = (m.nombre.substringAfterLast('.', "").ifEmpty { f.extension }).lowercase()

    fun hacer(a: Acciones.AccionApp): Result<String> = runCatching {
        val p = a.pedido
        when (a.herramienta) {
            "leer_en_voz_alta" -> leerEnVozAlta(texto(p, "texto"), texto(p, "archivo"))
            "comprimir_pdf" -> comprimirPdf(texto(p, "archivo"))
            "transcribir_audio" -> transcribir(texto(p, "archivo"))
            "leer_excel" -> leerExcel(texto(p, "archivo"), texto(p, "hoja"), texto(p, "rango"))
            "leer_documento" -> leerDocumento(texto(p, "archivo"), texto(p, "paginas"))
            "resumen_de_actividad" -> resumen(texto(p, "periodo") ?: "hoy")
            Acciones.LIENZOS_DEL_CHAT -> lienzosDelChat()
            Acciones.CREAR_LIENZO -> crearLienzo(texto(p, "nombre") ?: error("falta el nombre del lienzo"))
            Acciones.USAR_LIENZO -> usarLienzo(texto(p, "nombre") ?: error("falta el nombre del lienzo"))
            else -> error("«${a.herramienta}» no se hace en el chat del teléfono")
        }
    }

    // ---------------------------------------------------------------------------------- voz

    private fun leerEnVozAlta(t: String?, archivo: String?): String {
        val que = t ?: leerDocumento(archivo, null).substringAfter('\n')
        if (que.isBlank()) error("no hay nada que leer")
        android.os.Handler(android.os.Looper.getMainLooper()).post { VozDelAsistente.leer(contexto, que) }
        return "Leyendo en voz alta (${que.length} letras)."
    }

    // ---------------------------------------------------------------------------------- PDF

    private fun comprimirPdf(archivo: String?): String {
        val m = adjunto(archivo) { mm, f -> extension(mm, f) == "pdf" } ?: error("no hay ningún PDF en este chat")
        val original = File(m.ruta!!)
        val ligero = com.forge.pixpin.pdf.ComprimirPdf.comprimir(original.readBytes()) ?: error("no se pudo aligerar «${m.nombre}»")
        if (ligero.size >= original.length()) return "«${m.nombre}» ya estaba ligero (${com.forge.pixpin.motor.Detalle.legible(original.length())}); no hay copia más ligera."
        val ahora = System.currentTimeMillis()
        val base = m.nombre.substringBeforeLast('.').ifBlank { original.nameWithoutExtension }
        val f = File(almacen.carpetaDeAdjuntos(), "ligero-$ahora.pdf").apply { writeBytes(ligero) }
        almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.ARCHIVO, ruta = f.path,
            nombre = "$base (ligero).pdf", bytes = f.length(), proyecto = chat, aparato = ChatDelAsistente.APARATO))
        return "«${m.nombre}» aligerado: ${com.forge.pixpin.motor.Detalle.legible(original.length())} → ${com.forge.pixpin.motor.Detalle.legible(f.length())}. La copia está en el chat; el original no se tocó."
    }

    // ---------------------------------------------------------------------------------- audio

    private fun transcribir(archivo: String?): String {
        val m = adjunto(archivo) { mm, f -> mm.clase == Clase.VOZ || extension(mm, f) in setOf("m4a", "mp3", "wav", "ogg", "opus", "aac", "flac", "3gp") }
            ?: error("no hay ningún audio en este chat")
        m.transcripcion?.takeIf { it.isNotBlank() }?.let { return "Lo dicho en «${m.nombre.ifBlank { "la nota de voz" }}» (ya estaba pasado a texto):\n$it" }
        val r = com.forge.pixpin.guardados.Transcriptor.transcribirAqui(contexto, File(m.ruta!!), com.forge.pixpin.guardados.Transcriptor.idiomaElegido(contexto), {})
        return when (r) {
            is com.forge.pixpin.guardados.Transcriptor.Resultado.Texto -> {
                almacen.actualizar(m.id) { it.copy(transcripcion = r.texto) }
                "Lo dicho en «${m.nombre.ifBlank { "la nota de voz" }}»:\n${r.texto}"
            }
            is com.forge.pixpin.guardados.Transcriptor.Resultado.DescargandoIdioma -> error("falta bajar el idioma de la voz y no hay red")
            is com.forge.pixpin.guardados.Transcriptor.Resultado.Fallo -> error("no se pudo pasar a texto (${r.detalle ?: r.codigo})")
        }
    }

    // ---------------------------------------------------------------------------------- Excel

    private fun leerExcel(archivo: String?, hoja: String?, rango: String?): String {
        val m = adjunto(archivo) { mm, f -> com.forge.pixpin.motor.ImportarHojas.esLibro(mm.nombre.ifBlank { f.name }) }
            ?: error("no hay ningún Excel en este chat")
        val hojas = com.forge.pixpin.motor.ImportarHojas.leer(File(m.ruta!!), m.nombre.ifBlank { File(m.ruta).name })
        if (hojas.isEmpty()) error("el libro no tiene hojas")
        val h = hoja?.let { n -> hojas.firstOrNull { sinAcentos(it.nombre).contains(sinAcentos(n)) } } ?: hojas.first()
        val k = com.forge.pixpin.motor.Calculadora().also { c -> h.tabla.celdas.forEach { (d, v) -> com.forge.pixpin.motor.Celdas.leer(d)?.let { c.poner(it[0], it[1], v) } } }
        val caja = k.caja() ?: return "La hoja «${h.nombre}» está vacía."
        var (f1, c1, f2, c2) = caja.toList()
        rango?.split(':')?.mapNotNull { com.forge.pixpin.motor.Celdas.leer(it.trim().uppercase()) }?.takeIf { it.size == 2 }?.let { (a, b) ->
            f1 = minOf(a[0], b[0]); c1 = minOf(a[1], b[1]); f2 = maxOf(a[0], b[0]); c2 = maxOf(a[1], b[1])
        }
        f2 = minOf(f2, f1 + 199); c2 = minOf(c2, c1 + 25)
        val sb = StringBuilder("«${m.nombre}», hoja «${h.nombre}» (${com.forge.pixpin.motor.Celdas.nombre(f1, c1)}:${com.forge.pixpin.motor.Celdas.nombre(f2, c2)}):\n")
        for (f in f1..f2) {
            val fila = (c1..c2).mapNotNull { c -> k.texto(f, c).takeIf { it.isNotEmpty() }?.let { "${com.forge.pixpin.motor.Celdas.nombre(f, c)}=$it" } }
            if (fila.isNotEmpty()) sb.append(fila.joinToString(" | ")).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------------------------- documentos

    /** Las páginas pedidas («3», «2-5»), desde 0; null: todas. */
    private fun paginasDe(t: String?, cuantas: Int): IntRange? {
        t ?: return null
        val n = t.split('-').mapNotNull { it.trim().toIntOrNull() }
        if (n.isEmpty()) return null
        return (n.first() - 1).coerceAtLeast(0)..((n.getOrElse(1) { n.first() }) - 1).coerceAtMost(cuantas - 1)
    }

    fun leerDocumento(archivo: String?, paginas: String?): String {
        val m = adjunto(archivo) { _, _ -> true } ?: error("no hay ningún archivo en este chat")
        val f = File(m.ruta!!)
        val nombre = m.nombre.ifBlank { f.name }
        val ext = extension(m, f)
        val sb = StringBuilder()
        when {
            ext == "pdf" -> {
                val doc = com.forge.pixpin.motor.leerPdf(f.readBytes()) ?: error("no se pudo leer el PDF")
                if (doc.cifrado) error("el PDF está protegido")
                val todas = doc.paginas().indices
                val rango = paginasDe(paginas, todas.count()) ?: todas
                for (n in rango.take(60)) {
                    val lineas = com.forge.pixpin.motor.PdfAHtml.lineasEnOrden(com.forge.pixpin.motor.PdfAHtml.trozosDe(com.forge.pixpin.motor.PlanoDePdf.de(doc, n)?.textos.orEmpty()))
                    sb.append("--- página ${n + 1} ---\n").append(lineas.joinToString("\n") { it.texto }).append('\n')
                }
                if (sb.lines().count { it.isNotBlank() && !it.startsWith("---") } == 0) error("«$nombre» es un escaneado sin texto: usa leer_documento sobre una foto o la herramienta de OCR")
            }
            ext in setOf("docx", "doc", "pptx", "ppt", "odt", "rtf") -> {
                val d = com.forge.pixpin.office.HojasOffice.de(contexto, f.path) ?: error("no se pudo leer «$nombre»")
                d.maquetarTodo { }
                val rango = paginasDe(paginas, d.cuantas()) ?: (0 until d.cuantas())
                for (n in rango.take(80)) sb.append("--- ${if (d.esPresentacion) "diapositiva" else "página"} ${n + 1} ---\n").append(d.texto(n)).append('\n')
            }
            com.forge.pixpin.motor.ImportarHojas.esLibro(nombre) -> return leerExcel(nombre, null, null)
            ext in setOf("txt", "md", "csv", "tsv", "json", "html", "htm") -> sb.append(f.readText().take(60_000))
            m.clase == Clase.NOTA -> sb.append(m.texto)
            else -> error("«$nombre» no es un documento que se pueda leer aquí (si es una foto, ya la ves o usa el OCR)")
        }
        val texto = sb.toString().take(60_000)
        return "«$nombre»:\n$texto"
    }

    // ---------------------------------------------------------------------------------- actividad

    private fun resumen(periodo: String): String {
        val ahora = System.currentTimeMillis()
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            when (periodo) {
                "semana" -> set(java.util.Calendar.DAY_OF_WEEK, firstDayOfWeek)
                "mes" -> set(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }
        val desde = c.timeInMillis
        val todos = almacen.leer().filter { it.cuando in desde..ahora && !ChatDelAsistente.esRespuesta(it) }
        val notas = todos.count { it.clase == Clase.NOTA }
        val fotos = todos.count { it.clase == Clase.IMAGEN }
        val voz = todos.count { it.clase == Clase.VOZ }
        val archivos = todos.count { it.clase == Clase.ARCHIVO }
        val lienzos = todos.count { it.clase == Clase.DIBUJO || it.clase == Clase.PAGINA }
        val capturas = runCatching { com.forge.pixpin.capture.BarrenderoDeCapturas.listar(contexto)?.count { it.cuando >= desde } ?: 0 }.getOrDefault(0)
        val listas = almacen.leer().filter { com.forge.pixpin.mini.TodasLasTareas.esLista(it) }
        val tareas = listas.flatMap { com.forge.pixpin.mini.TodasLasTareas.filasDe(it.texto) }
        val hechasHoy = tareas.count { it.hecha && (it.creada?.let { d -> d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() >= desde } ?: false) }
        val pendientes = tareas.count { !it.hecha }
        val cuando = when (periodo) { "semana" -> "esta semana"; "mes" -> "este mes"; else -> "hoy" }
        return "Lo hecho $cuando: $notas notas y mensajes, $fotos fotos, $voz notas de voz, $archivos archivos, $lienzos lienzos, $capturas capturas. " +
            "Tareas: $pendientes pendientes${if (hechasHoy > 0) ", $hechasHoy hechas de las creadas $cuando" else ""}."
    }

    // ---------------------------------------------------------------------------------- lienzos

    /** Lo que tiene un lienzo, para el modelo: cuántas de cada clase y los textos que se leen. */
    private fun resumenDe(l: List<com.forge.pixpin.motor.Element>): String {
        val porTipo = l.groupingBy { it.type.name.lowercase() }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        val textos = l.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }.take(20).joinToString("; ") { "«${it.take(60)}»" }
        val b = com.forge.pixpin.motor.getCommonBounds(l)
        return "Lienzo destino: ${l.size} elementos ($porTipo), ocupa ${b.width.toInt()}x${b.height.toInt()}." + (if (textos.isNotEmpty()) " Textos: $textos." else "")
    }

    private fun cuantasCosas(ref: String): Int? =
        ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, ref))?.elements?.count { !it.isDeleted }

    private fun lienzos() = delChat().filter { ChatDelAsistente.esLienzo(it) }

    private fun lienzosDelChat(): String {
        val filas = lienzos().map { m ->
            val r = m.referencia.orEmpty()
            val n = cuantasCosas(r)
            val ahora = if (destino() == r) " (trabajas en este)" else ""
            "- «${ChatDelAsistente.nombreDeLienzo(m)}»: ${n?.let { "$it elementos" } ?: "no se pudo leer"}$ahora"
        }
        if (filas.isEmpty()) return "Este chat no tiene ningun lienzo todavia (crear_lienzo crea uno)."
        return "Lienzos de este chat:\n" + filas.joinToString("\n")
    }

    private fun crearLienzo(nombre: String): String {
        val ahora = System.currentTimeMillis()
        val id = "ia-$ahora"
        ExcalidrawStore.guardar(contexto, id, Scene()) ?: error("no se pudo crear el lienzo")
        almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.DIBUJO, nombre = nombre,
            referencia = id, proyecto = chat, aparato = ChatDelAsistente.APARATO))
        alCambiarDeLienzo(id)
        return "Creado el lienzo «$nombre» en este chat (vacio). Lo que dibujes, edites o mires con ver_lienzo ahora va a el."
    }

    private fun usarLienzo(nombre: String): String {
        val hay = lienzos()
        val q = sinAcentos(nombre)
        val m = hay.lastOrNull { sinAcentos(ChatDelAsistente.nombreDeLienzo(it)) == q }
            ?: hay.lastOrNull { sinAcentos(ChatDelAsistente.nombreDeLienzo(it)).contains(q) }
            ?: error(if (hay.isEmpty()) "no hay ningun lienzo «$nombre» en este chat (no tiene ninguno; crear_lienzo crea uno)"
                else "no hay ningun lienzo «$nombre» en este chat; los que hay: " + hay.joinToString(", ") { ChatDelAsistente.nombreDeLienzo(it) })
        alCambiarDeLienzo(m.referencia)
        val escena = m.referencia?.let { ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, it)) }
        val resumen = escena?.elements?.filter { !it.isDeleted }?.takeIf { it.isNotEmpty() }?.let { vivos ->
            val b = com.forge.pixpin.motor.getCommonBounds(vivos)
            resumenDe(vivos) + "\nFiguras (coordenadas del marco; nombralas por su #n en «entre» o «a»):\n" +
                com.forge.pixpin.ia.GeometriaPrecisa.resumen(escena.elements, com.forge.pixpin.motor.Pt(b.x1, b.y1)).lines().take(60).joinToString("\n")
        } ?: "Lienzo destino: vacio."
        return "Ahora trabajas en el lienzo «${ChatDelAsistente.nombreDeLienzo(m)}»: lo que dibujes, edites o mires con ver_lienzo va a el.\n$resumen"
    }
}
