package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.TablasDelChat
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.Documentos
import com.forge.pixpin.ia.Motor
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.ia.TablaEscaneada
import com.forge.pixpin.ia.Tareas
import com.forge.pixpin.ia.Xlsx
import com.forge.pixpin.motor.PdfAHtml
import com.forge.pixpin.motor.PlanoDePdf
import com.forge.pixpin.motor.TablaDeCalculo
import com.forge.pixpin.motor.leerPdf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Lo que hace el asistente con los documentos del usuario** (`documentos_acciones.rs` del PC):
 * buscar en sus cosas (BM25, con citas que acaban en `fuentesIa`) y pasar la tabla de una foto o un
 * PDF a una tabla del chat o a un Excel ([TablaEscaneada]).
 *
 * El teléfono no tiene OCR con cajas (ni el de Windows ni ML Kit): la tabla de un PDF con texto
 * sale de su capa de texto, con el mismo algoritmo que el PC; la de una foto la transcribe la
 * visión del modelo en filas separadas por tabuladores, y eso lo dice el resultado.
 */
class DocumentosAcciones(
    private val contexto: Context,
    private val almacen: MensajesStore,
    private val chat: String?,
    private val motor: Motor,
    private val parar: AtomicBoolean,
    /** Si se pueden mandar trozos de documentos al modelo (la nube pregunta, §4.8). */
    private val permisoDocumentos: () -> String?
) {
    /** Las citas de este turno, numeradas seguidas entre búsquedas. */
    val citas = Documentos.Citas()

    fun buscar(p: Documentos.PeticionBusqueda): Result<String> = runCatching {
        permisoDocumentos()?.let { return@runCatching it }
        val alcance = p.proyecto?.let { nombre ->
            val app = contexto.applicationContext as com.forge.pixpin.PixPinApp
            val chats = listOf(Tareas.Chat("", com.forge.pixpin.mini.TodasLasTareas.GUARDADOS, true)) +
                app.proyectos.proyectos.value.map { Tareas.Chat(it.id, it.nombre, false) }
            runCatching { Tareas.buscarChat(chats, nombre) }.getOrNull()?.let { Documentos.Alcance.solo(it.id) }
        } ?: Documentos.Alcance.TODO
        val h = DocumentosDelTelefono.buscar(contexto, p.consulta, alcance) { parar.get() }
        citas.anadir(p.consulta, h)
    }

    // ------------------------------------------------------------------------- tabla de imagen

    private fun adjuntoDe(nombre: String?): Mensaje? {
        val todos = almacen.leer().filter { m ->
            !m.enBuzon && m.ruta != null && File(m.ruta).isFile &&
                (m.clase == Clase.IMAGEN || m.ruta.lowercase().endsWith(".pdf") || m.nombre.lowercase().endsWith(".pdf"))
        }
        if (nombre == null) return todos.lastOrNull { it.proyecto == chat } ?: todos.lastOrNull()
        val n = File(nombre).name
        return todos.lastOrNull { it.nombre.equals(n, true) || File(it.ruta!!).name.equals(n, true) }
            ?: todos.map { Tareas.parecido(nombre, it.nombre.ifBlank { File(it.ruta!!).name }) to it }.filter { it.first > 0 }.maxByOrNull { it.first }?.second
    }

    fun tablaDeImagen(a: Acciones.AccionApp): Result<String> = runCatching {
        val p = a.pedido
        fun texto(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val m = adjuntoDe(texto("archivo")) ?: error("no hay ninguna foto ni PDF en los chats con una tabla")
        val ruta = File(m.ruta!!)
        val nombreDoc = m.nombre.ifBlank { ruta.name }
        val pagina = (p["pagina"] as? JsonPrimitive)?.intOrNull
        val (filas, conVision) = if (ruta.name.lowercase().endsWith(".pdf")) tablaDePdf(ruta, pagina) to false else tablaDeFoto(ruta) to true
        if (filas.size < 2 || (filas.maxOfOrNull { it.size } ?: 0) < 2) error("no se ve una tabla en «$nombreDoc»")
        val nombre = texto("nombre")?.trim()?.takeIf { it.isNotEmpty() } ?: "Tabla"
        val cabecera = (p["cabecera"] as? JsonPrimitive)?.booleanOrNull ?: TablaEscaneada.TablaLeida(filas).pareceCabecera()
        val columnas = filas.maxOf { it.size }
        val aviso = if (conVision) " (la leyó la visión del modelo: revisa los números)" else ""
        if (texto("destino") == "excel") {
            val ahora = System.currentTimeMillis()
            val f = File(almacen.carpetaDeAdjuntos(), "tabla-$ahora.xlsx")
            f.outputStream().use { Xlsx.escribir(it, nombre, filas, cabecera) }
            almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.ARCHIVO, ruta = f.path,
                nombre = "$nombre.xlsx", bytes = f.length(), proyecto = chat))
            "Excel «$nombre.xlsx» con ${filas.size} filas y $columnas columnas, en el chat$aviso."
        } else {
            val ahora = System.currentTimeMillis()
            val t: TablaDeCalculo = TablaEscaneada.tablaDeFilas(nombre, filas, cabecera, ahora)
            almacen.anadir(Mensaje(id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.MINIAPP, miniapp = TablasDelChat.MINIAPP,
                nombre = t.nombre, texto = TablaDeCalculo.aJson(t), proyecto = chat))
            "Tabla «${t.nombre}» con ${filas.size} filas y $columnas columnas, en el chat$aviso."
        }
    }

    /** La tabla de una página de un PDF con texto: sus palabras con caja, por [TablaEscaneada]. */
    private fun tablaDePdf(f: File, pagina: Int?): List<List<String>> {
        val archivo = leerPdf(f.readBytes()) ?: error("no se pudo leer el PDF")
        if (archivo.cifrado) error("el PDF está protegido con contraseña")
        val paginas = if (pagina != null) listOf(pagina - 1) else archivo.paginas().indices.take(30).toList()
        for (n in paginas) {
            val palabras = PdfAHtml.trozosDe(PlanoDePdf.de(archivo, n)?.textos.orEmpty())
                .map { TablaEscaneada.Palabra(it.texto, it.x, it.y - it.alto, it.fin - it.x, it.alto) }
            val t = TablaEscaneada.reconstruir(palabras)
            if (t.filas.size >= 2 && t.columnas >= 2) return t.filas
        }
        if (paginas.size == 1 && PlanoDePdf.de(archivo, paginas[0])?.textos.isNullOrEmpty()) error("la página ${paginas[0] + 1} es escaneada y el teléfono no tiene OCR: mándala como foto")
        return emptyList()
    }

    /** La tabla de una foto, transcrita por la visión del modelo en filas con tabuladores. */
    private fun tablaDeFoto(f: File): List<List<String>> {
        val jpeg = FotoParaElModelo.deArchivo(f, FotoParaElModelo.LADO_PARA_LEER) ?: error("no se pudo leer la foto")
        val r = motor.completar(
            Peticion(
                listOf(
                    MensajeIa.sistema("Eres un lector de tablas. Escribe SOLO la tabla de la imagen, una fila por linea y las celdas separadas por un tabulador. Sin comentarios ni Markdown."),
                    MensajeIa.usuarioConImagen("Transcribe la tabla de esta imagen.", jpeg, "image/jpeg")
                ),
                maxTokens = 2000, temperatura = 0.1
            )
        ) { if (parar.get()) Seguir.PARAR else Seguir.SI }
        return ChatDelAsistente.sinPensado(r.texto).lines().map { it.trim().trim('|') }.filter { it.isNotBlank() && !it.all { c -> c in "-|: \t" } }
            .map { l -> (if ('\t' in l) l.split('\t') else l.split('|')).map { it.trim() } }
    }
}
