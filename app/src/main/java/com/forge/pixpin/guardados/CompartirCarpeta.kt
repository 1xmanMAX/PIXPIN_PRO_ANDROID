package com.forge.pixpin.guardados

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Wifi
import com.forge.pixpin.PixPinApp
import com.forge.pixpin.motor.ExcalidrawJson
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.ExportarProyecto
import com.forge.pixpin.motor.Hoja
import com.forge.pixpin.motor.PaquetePixpin
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Proyecto
import com.forge.pixpin.ui.Compartible
import com.forge.pixpin.ui.CompartirPaginas
import com.forge.pixpin.ui.ExportarVisor
import com.forge.pixpin.ui.ExportarWebDe
import com.forge.pixpin.ui.FormatosDeCompartir
import androidx.compose.runtime.collectAsState
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * **Compartir un cuaderno (una carpeta de páginas del chat) con todos los formatos** (28-oct-2026).
 *
 * El usuario: «mejora el compartir de los cuadernos para que añada todos los formatos que tienen
 * los demás: PDF, HTML, PixPin, fotos y lo demás, igual que en las otras hojas de compartir. Esa
 * interfaz tiene que estar siempre unificada». Hasta hoy el cuaderno solo daba el PDF y una página
 * en imagen; desde el chat, su «Compartir» mandaba el JSON de la carpeta como texto.
 *
 * **Una sola función arma lo compartible** ([compartible]), y la usan todos los sitios desde donde
 * se comparte un cuaderno: el botón de su pantalla ([CarpetaActivity]), «Compartir» en el menú de
 * su mensaje del chat y lo marcado en el chat ([conSusPaginas] y [deVarios]). La hoja es la de
 * siempre ([com.forge.pixpin.ui.HojaDeCompartir]), con el orden, el nombre y el icono de toda la
 * aplicación ([FormatosDeCompartir], contenido [FormatosDeCompartir.Contenido.CARPETA]):
 *
 * - **Imagen PNG y JPG** de una página, sacada del mismo PDF del cuaderno para que se vea igual.
 * - **PDF** con las páginas marcadas ([CarpetaEnPdf]).
 * - **Página web (HTML)**: cada página con su visor de siempre —un lienzo con su SVG, una nota como
 *   texto, una página de un PDF con lo anotado— ([ExportarWebDe]); una foto o un archivo, como
 *   imagen. Con el panel de «Opciones» de toda página web.
 * - **Word, Markdown y Texto**, si alguna página lleva texto (una nota, la transcripción de una
 *   nota de voz): cada una con su título ([CompartirPaginas.formatosDeTexto]).
 * - **PixPin (.pixpin)**: el cuaderno editable, que se abre en otro teléfono y **en el PC** con su
 *   carpeta. Ver [pixpin].
 * - **Original (ZIP)**: cada página tal cual, numerada en el orden del cuaderno.
 * - **Por Wi‑Fi a mis aparatos**: su `.pixpin`, por la pantalla de enviar.
 *
 * Lo que se marque en «Qué páginas» vale para todo lo que admite páginas (el PDF, la web, Word, el
 * `.pixpin`, el ZIP); la imagen, de una en una.
 *
 * **La carpeta de una grabación** (la clase con su voz, su vídeo, sus capturas y sus clips) sale
 * como una grabación ([ExportarGrabacion]: la web con el reproductor, el PDF y el Word con las
 * capturas en su minuto, Anki…), y su `.pixpin`, su original y la Wi‑Fi son los de la clase entera,
 * con su carpeta, no solo el audio.
 *
 * Todo lee el disco: **fuera del hilo de la pantalla**.
 */
object CompartirCarpeta {

    /** Una página del cuaderno: su mensaje, su título y su sitio (desde 1). */
    class Pagina(val mensaje: Mensaje, val titulo: String, val numero: Int)

    /** **Las páginas vivas** de la carpeta [carpeta], en su orden (en una grabación, lo adjuntado detrás). */
    fun paginasDe(carpeta: Mensaje, todos: List<Mensaje>): List<Pagina>? {
        val c = CarpetaDePaginas.deMensaje(carpeta) ?: return null
        val vivas = c.vivas(todos).map { (i, p) -> todos[i] to p }
            .let { l -> if (c.esGrabacion) l.sortedBy { it.second.adjunto } else l }
        return vivas.mapIndexed { i, (m, p) ->
            Pagina(m, p.titulo.ifBlank { m.nombre.ifBlank { CarpetasDelChat.PAGINA } }, i + 1)
        }
    }

    /**
     * **Lo compartible del cuaderno [carpeta]**, venga de donde venga. [todos] es el chat entero (lo
     * lee si no se da). Null si el mensaje no es una carpeta que se entienda.
     */
    fun compartible(context: Context, carpeta: Mensaje, todos: List<Mensaje> = MensajesStore(context).leer()): Compartible? {
        val doc = CarpetaDePaginas.deMensaje(carpeta) ?: return null
        val paginas = paginasDe(carpeta, todos) ?: return null
        val titulo = carpeta.nombre.ifBlank { CarpetasDelChat.NOMBRE_DE_SERIE }
        // **Una grabación, como una grabación**, con lo de la clase entera en vez de solo su audio.
        if (doc.esGrabacion) ExportarGrabacion.notaDe(carpeta, todos)?.let { voz ->
            return ExportarGrabacion.compartible(
                context, voz, todos,
                listOf(
                    pixpin(context, carpeta, todos, titulo, Compartible.NINGUNA),
                    original(context, paginas, titulo, Compartible.NINGUNA, "La clase tal cual: la voz, el vídeo, las capturas y lo adjuntado"),
                    wifi(context, carpeta, todos, titulo)
                )
            )
        }
        return Compartible(
            titulo,
            paginas.map { Compartible.Pagina(it.mensaje.id, it.titulo, tipoDe(it.mensaje)) },
            formatos(context, carpeta, todos, titulo, paginas),
            contenido = FormatosDeCompartir.Contenido.CARPETA
        )
    }

    /**
     * **Lo marcado en el chat, con las carpetas abiertas** (28-oct-2026): una carpeta marcada junto a
     * otros mensajes va con sus páginas (las que no estén ya), y no con el JSON de su documento.
     */
    fun conSusPaginas(lista: List<Mensaje>, todos: List<Mensaje>): List<Mensaje> =
        lista.flatMap { m -> if (CarpetaDePaginas.esCarpeta(m)) paginasDe(m, todos)?.map { it.mensaje }.orEmpty() else listOf(m) }
            .distinctBy { it.id }

    /** Si lo marcado es **un cuaderno solo**, su hoja: la misma que desde su pantalla. Si no, null. */
    fun deVarios(context: Context, lista: List<Mensaje>, todos: List<Mensaje>): Compartible? =
        lista.singleOrNull()?.takeIf(CarpetaDePaginas::esCarpeta)?.let { compartible(context, it, todos) }

    // ------------------------------------------------------------------ los formatos

    private fun formatos(context: Context, carpeta: Mensaje, todos: List<Mensaje>, titulo: String, paginas: List<Pagina>): List<Compartible.Formato> {
        val porId = paginas.associateBy { it.mensaje.id }
        fun elegidas(claves: List<String>) = claves.mapNotNull { porId[it] }
        val base = ExportarVisor.nombre(titulo, "x").removeSuffix(".x")
        val carpetaDeSalida = ExportarVisor.carpeta(context)
        val seVe: (String) -> Boolean = { k -> porId[k]?.mensaje?.let(::seVe) == true }
        val conTexto: (String) -> Boolean = { k -> porId[k]?.mensaje?.let(::textoDe) != null }
        return buildList {
            // El primero, el PDF: lo que más se comparte de un cuaderno (y con lo que abre la hoja).
            add(Compartible.Formato("pdf", Icons.Filled.PictureAsPdf, "PDF", Compartible.VARIAS, seVe, generar = { claves ->
                val e = elegidas(claves)
                CarpetaEnPdf.escribir(context, titulo, e.map { it.mensaje })?.let {
                    Compartible.Salida(it, "application/pdf", cuantas(e.size))
                }
            }))
            if (paginas.any { seVe(it.mensaje.id) }) addAll(ExportarVisor.imagenes(
                context, { claves -> "$titulo - " + (claves.firstOrNull()?.let { porId[it] }?.titulo ?: "página") },
                Compartible.UNA, seVe
            ) { claves ->
                val p = claves.firstOrNull()?.let { porId[it] } ?: return@imagenes null
                imagenDe(context, titulo, p.mensaje)
            })
            add(web(context, titulo, porId))
            if (paginas.any { conTexto(it.mensaje.id) }) addAll(CompartirPaginas.formatosDeTexto(context, carpetaDeSalida, base, titulo, conTexto) { claves ->
                elegidas(claves).mapNotNull { p -> textoDe(p.mensaje)?.let { "## ${p.titulo}\n\n$it" } }
                    .joinToString("\n\n").takeIf { it.isNotBlank() }
            })
            add(pixpin(context, carpeta, todos, titulo, Compartible.VARIAS))
            add(original(context, paginas, titulo, Compartible.VARIAS, "Cada página tal cual, numerada, en un ZIP"))
            add(wifi(context, carpeta, todos, titulo))
        }
    }

    private fun cuantas(n: Int) = "$n ${if (n == 1) "página" else "páginas"}"

    /** Qué es cada página, debajo de su nombre en «Qué páginas». */
    private fun tipoDe(m: Mensaje): String = when (CarpetaDePaginas.piezaDe(m)) {
        CarpetaDePaginas.Pieza.LIENZO -> "Lienzo"
        CarpetaDePaginas.Pieza.PAGINA -> "Página de un PDF"
        CarpetaDePaginas.Pieza.IMAGEN -> "Foto"
        CarpetaDePaginas.Pieza.NOTA -> "Nota"
        CarpetaDePaginas.Pieza.VOZ -> "Nota de voz"
        CarpetaDePaginas.Pieza.VIDEO -> "Vídeo"
        CarpetaDePaginas.Pieza.ARCHIVO -> "Archivo"
    }

    /** Si la página sale en el PDF (y en imagen): lo que [CarpetaEnPdf] sabe pintar. */
    fun seVe(m: Mensaje): Boolean = when (m.clase) {
        Clase.DIBUJO -> m.referencia != null
        Clase.PAGINA -> m.ruta != null && m.pagina != null
        Clase.IMAGEN -> m.ruta != null
        Clase.NOTA -> m.texto.isNotBlank()
        Clase.ARCHIVO -> m.ruta?.let { it.endsWith(".pdf", ignoreCase = true) || ExportarVisor.esImagen(it) } == true
        else -> false
    }

    /** **El texto de una página**, si lo lleva: una nota, o lo transcrito de una nota de voz. */
    fun textoDe(m: Mensaje): String? = when (m.clase) {
        Clase.NOTA -> m.texto.takeIf { it.isNotBlank() }
        Clase.VOZ -> m.transcripcion?.takeIf { it.isNotBlank() }?.let {
            ExportarGrabacion.lineasDe(m).joinToString("\n") { (_, t) -> t }.ifBlank { null }
        }
        else -> null
    }

    /** Una página en imagen: la primera hoja de su PDF de ella sola, lo mismo que sale en el PDF. */
    private suspend fun imagenDe(context: Context, titulo: String, m: Mensaje): Bitmap? {
        val pdf = CarpetaEnPdf.escribir(context, "$titulo (imagen)", listOf(m)) ?: return null
        return try { PdfDoc.render(pdf.path, 0, CarpetaEnPdf.ANCHO_DE_PAGINA) } finally { pdf.delete() }
    }

    // ------------------------------------------------------------------ la página web

    /**
     * **La página web del cuaderno**, con las mismas hojas web de un proyecto: cada página es un
     * proyecto de una hoja ([proyectoDeLaPagina]) que [ExportarWebDe.hojas] convierte como siempre.
     * Lo que no es una hoja (una foto, la primera página de un PDF adjunto) va como imagen.
     */
    private fun web(context: Context, titulo: String, porId: Map<String, Pagina>): Compartible.Formato {
        val app = context.applicationContext as? PixPinApp
        return Compartible.Formato(
            "web", Icons.Filled.Language, "Página web", Compartible.VARIAS,
            generar = { claves ->
                val marcadas = app?.ajustes?.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                val calidad = ExportarHtml.calidadDeAudio(marcadas)
                val deNoche = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val hojas = claves.mapNotNull { porId[it] }.flatMap { p ->
                    val comoHoja = proyectoDeLaPagina(p)?.let { runCatching { ExportarWebDe.hojas(context, it, emptySet(), deNoche, calidad) }.getOrNull() }
                    if (!comoHoja.isNullOrEmpty()) comoHoja
                    else if (seVe(p.mensaje)) listOfNotNull(imagenDe(context, titulo, p.mensaje)?.let { hojaDeImagen(p.titulo, it) })
                    else emptyList()
                }
                if (hojas.isEmpty()) null else {
                    val nombre = ExportarProyecto.nombreDeArchivo(titulo)
                    val html = ExportarHtml.paginas(hojas, titulo, nombre, ExportarHtml.Opciones.de(marcadas))
                    val f = File(ExportarVisor.carpeta(context), "$nombre.html").also { it.writeText(html) }
                    Compartible.Salida(f, ExportarHtml.MIME_TYPE, ExportarWebDe.resumenDe(f, calidad).removePrefix("Página web: "))
                }
            },
            versionDeAjustes = {
                if (app == null) null else {
                    val ajustes = app.settings.settings.collectAsState(initial = app.ajustes)
                    ajustes.value.funcionesWeb
                }
            },
            ajustes = if (app == null) null else { cerrar ->
                val ajustes = app.settings.settings.collectAsState(initial = app.ajustes)
                val marcadas = ajustes.value.funcionesWeb ?: ExportarHtml.Opciones.NOMBRES.toSet()
                val alcance = androidx.compose.runtime.rememberCoroutineScope()
                com.forge.pixpin.motor.DialogoDeFuncionesWeb(
                    marcadas = marcadas,
                    onCambio = { clave, puesta ->
                        val ahora = ExportarHtml.conGrupo(marcadas, clave, puesta)
                        alcance.launch { app.settings.setFuncionesWeb(ahora) }
                    },
                    onCompartir = cerrar,
                    onCerrar = cerrar,
                    calidadDeAudio = ExportarHtml.calidadDeAudio(marcadas),
                    onCalidadDeAudio = { c -> alcance.launch { app.settings.setFuncionesWeb(ExportarHtml.conCalidadDeAudio(marcadas, c)) } },
                    hayAudio = porId.values.any { it.mensaje.texto.contains("![audio]") },
                    hayLienzo = false,
                    hayTabla = false
                )
            }
        )
    }

    /**
     * **Una página como un proyecto de una hoja**, para las hojas web: un lienzo (o una foto anotada)
     * con su dibujo, una página de un PDF con su documento y lo anotado, una nota con su texto. Null
     * si no es nada de eso.
     */
    private fun proyectoDeLaPagina(p: Pagina): Proyecto? {
        val m = p.mensaje
        val hoja = when {
            m.clase == Clase.DIBUJO && m.referencia != null -> Hoja(id = "h-${m.id}", nombre = p.titulo, dibujo = m.referencia)
            m.clase == Clase.IMAGEN && m.referencia != null -> Hoja(id = "h-${m.id}", nombre = p.titulo, dibujo = m.referencia)
            m.clase == Clase.PAGINA && m.ruta != null && m.pagina != null && File(m.ruta).isFile ->
                Hoja(id = "h-${m.id}", nombre = p.titulo, pagina = m.pagina, dibujo = m.referencia)
            m.clase == Clase.NOTA && m.texto.isNotBlank() -> Hoja(id = "h-${m.id}", nombre = p.titulo, nota = m.texto)
            else -> return null
        }
        val pdf = m.ruta?.takeIf { m.clase == Clase.PAGINA }
        return Proyecto(id = "suelto-${m.id}", nombre = p.titulo, tocado = m.cuando, hojas = listOf(hoja), pdfOrigen = pdf, pdfLimpio = pdf)
    }

    /** Una imagen como hoja de la página web: un SVG con la imagen dentro, que el visor amplía. */
    private fun hojaDeImagen(nombre: String, mapa: Bitmap): ExportarHtml.HojaWeb {
        val w = mapa.width; val h = mapa.height
        val datos = ByteArrayOutputStream().also { mapa.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        if (!mapa.isRecycled) mapa.recycle()
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\">" +
            "<image width=\"$w\" height=\"$h\" href=\"data:image/jpeg;base64,${java.util.Base64.getEncoder().encodeToString(datos)}\"/></svg>"
        return ExportarHtml.HojaWeb.Dibujo(nombre, svg, "#ffffff")
    }

    // ------------------------------------------------------------------ el .pixpin

    /**
     * **El `.pixpin` del cuaderno** ([paginas] es cómo admite las páginas): un proyecto con el nombre
     * del cuaderno, su carpeta y sus páginas. Va de dos maneras a la vez, para los dos lectores:
     *
     * - **Para el teléfono**, como cualquier `.pixpin` con su chat ([com.forge.pixpin.sincro.ChatQueViaja]):
     *   `proyecto.json` con una hoja por lienzo (los lienzos solo viajan como hojas), el PDF de sus
     *   páginas como `documento.pdf` y `chat/mensajes.jsonl` con la carpeta y sus páginas, con los
     *   mismos ids: la carpeta las sigue encontrando al otro lado.
     * - **Para el PC**, `guardados.jsonl` en la raíz: el cuaderno de su chat, como lo escribe el PC al
     *   exportar (`compartir/exportable.rs`). El PC copia el paquete entero a la carpeta del chat y, si
     *   trae `guardados.jsonl`, ese es su chat (`almacen::importar_paquete`); sin él solo vería una
     *   hoja por lienzo y la carpeta se perdería. Las rutas son relativas a esa carpeta y señalan lo
     *   que ya va dentro para el teléfono, así que nada viaja dos veces ([chatParaElPc]).
     */
    private fun pixpin(context: Context, carpeta: Mensaje, todos: List<Mensaje>, titulo: String, paginas: Int): Compartible.Formato =
        Compartible.Formato("editable", Icons.Filled.FolderZip, ".pixpin", paginas, generar = { claves ->
            paqueteDe(context, carpeta, todos, titulo, claves.takeIf { paginas != Compartible.NINGUNA }?.toSet())
                ?.let { Compartible.Salida(it, PaquetePixpin.MIME_TYPE, "El cuaderno con su carpeta: se sigue editando en otro PixPin o en el PC") }
        })

    /** Escribe el `.pixpin` del cuaderno con las páginas [solo] (null: todas). Ver [pixpin]. */
    fun paqueteDe(context: Context, carpeta: Mensaje, todos: List<Mensaje>, titulo: String, solo: Set<String>? = null): File? {
        // Los códigos puestos antes de empaquetar, como al enviar por Wi-Fi; y el chat releído con ellos.
        runCatching { com.forge.pixpin.sincro.Red.disco(context).sellar() }
        val chat = runCatching { MensajesStore(context).leer() }.getOrNull()?.takeIf { l -> l.any { it.id == carpeta.id } } ?: todos
        val suya = chat.firstOrNull { it.id == carpeta.id } ?: carpeta
        val doc = CarpetaDePaginas.deMensaje(suya) ?: return null
        val paginas = (paginasDe(suya, chat) ?: return null).filter { solo == null || it.mensaje.id in solo }
        if (paginas.isEmpty()) return null
        val idsQueVan = paginas.map { it.mensaje.id }.toSet()
        // El PDF del proyecto: el de sus páginas de PDF (el que más salga, si son de varios).
        val pdf = paginas.mapNotNull { p -> p.mensaje.ruta?.takeIf { p.mensaje.clase == Clase.PAGINA && File(it).isFile } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val hojas = paginas.mapNotNull { p ->
            val m = p.mensaje
            val dibujo = m.referencia?.takeIf { m.clase == Clase.DIBUJO || m.clase == Clase.IMAGEN || m.clase == Clase.PAGINA }
                ?.takeIf { File(ExcalidrawStore.rutaDe(context, it)).exists() }
            when {
                m.clase == Clase.PAGINA && m.ruta == pdf -> Hoja(id = "h-${m.id}", nombre = p.titulo, pagina = m.pagina, dibujo = dibujo, deMensaje = m.id)
                dibujo != null -> Hoja(id = "h-${m.id}", nombre = p.titulo, dibujo = dibujo, deMensaje = m.id)
                else -> null
            }
        }
        val ahora = System.currentTimeMillis()
        val proyecto = Proyecto(id = "cuaderno-${suya.id}", nombre = titulo, tocado = ahora, hojas = hojas, pdfOrigen = pdf, pdfLimpio = pdf)
        // La carpeta, con solo las páginas que van; ella y sus páginas, en el chat del proyecto nuevo.
        val laCarpeta = suya.copy(texto = doc.copy(paginas = doc.paginas.filter { it.id in idsQueVan }).escribir(), proyecto = proyecto.id)
        val mensajes = listOf(laCarpeta) + paginas.map { it.mensaje.copy(proyecto = proyecto.id) }
        val paquete = com.forge.pixpin.sincro.ChatQueViaja.preparar(proyecto, mensajes, context.filesDir) ?: return null
        val destino = File(ExportarVisor.carpeta(context), ExportarVisor.nombre(titulo, PaquetePixpin.EXTENSION))
        return PaquetePixpin.escribir(
            context, proyecto, destino,
            croquisDe = { id -> com.forge.pixpin.croquis3d.Croquis3DAlmacen.jsonDe(context, id) },
            escrito = ahora,
            chat = paquete.texto, adjuntosDelChat = paquete.adjuntos,
            otras = mapOf(CHAT_DEL_PC to chatParaElPc(paquete.texto).toByteArray(Charsets.UTF_8))
        )
    }

    /** El chat del paquete tal como lo lee el PC (`guardados.jsonl` de la carpeta del chat). */
    const val CHAT_DEL_PC = "guardados.jsonl"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * **El chat del paquete, en el cuaderno del PC.** [texto] son las líneas de
     * [com.forge.pixpin.sincro.ChatQueViaja.preparar]; salen las mismas, con la ruta de cada una
     * dicha desde la carpeta del chat del PC, que es donde el PC suelta el paquete:
     *
     * - un adjunto (`adjunto:<clave>`) es `chat/adjuntos/<clave>`, el mismo fichero que lee el teléfono;
     * - el PDF del proyecto (`documento:`) es `documento.pdf`;
     * - un lienzo, `lienzos/<dibujo>.excalidraw`, como lo apunta el PC al importar.
     *
     * Y sin `proyecto`: en el PC el chat es la carpeta (su `exportable.rs` tampoco lo escribe). Lo
     * demás va tal cual: las dos aplicaciones escriben el mismo mensaje con las mismas claves.
     */
    fun chatParaElPc(texto: String): String = buildString {
        for (linea in texto.lines()) {
            if (linea.isBlank()) continue
            val o = runCatching { json.parseToJsonElement(linea) as? JsonObject }.getOrNull() ?: continue
            val ruta = o["ruta"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val referencia = o["referencia"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val clase = o["clase"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val rutaDelPc = when {
                ruta == null -> if (clase == Clase.DIBUJO.name && referencia != null) "lienzos/$referencia.excalidraw" else null
                ruta.startsWith("adjunto:") -> "chat/adjuntos/" + ruta.removePrefix("adjunto:")
                ruta == "documento:" -> "documento.pdf"
                else -> ruta
            }
            val salida = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            for ((k, v) in o) if (k != "proyecto" && k != "ruta") salida[k] = v
            if (rutaDelPc != null) {
                // En su sitio de siempre, detrás del texto (el orden de los campos del mensaje).
                val conRuta = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
                var puesta = false
                for ((k, v) in salida) {
                    conRuta[k] = v
                    if (k == "texto") { conRuta["ruta"] = JsonPrimitive(rutaDelPc); puesta = true }
                }
                if (!puesta) conRuta["ruta"] = JsonPrimitive(rutaDelPc)
                append(JsonObject(conRuta).toString())
            } else append(JsonObject(salida).toString())
            append('\n')
        }
    }

    // ------------------------------------------------------------------ el original y la Wi-Fi

    /**
     * **Cada página tal cual, en un ZIP** («01 - Día 1.png»…): el archivo de un adjunto, el lienzo en
     * `.excalidraw` y la nota en `.md`. [paginas] dice si admite elegir páginas.
     */
    private fun original(context: Context, todas: List<Pagina>, titulo: String, paginas: Int, detalle: String): Compartible.Formato {
        val porId = todas.associateBy { it.mensaje.id }
        return Compartible.Formato("original", Icons.Filled.Description, "ZIP", paginas, generar = { claves ->
            val elegidas = if (paginas == Compartible.NINGUNA) todas else claves.mapNotNull { porId[it] }
            val destino = File(ExportarVisor.carpeta(context), ExportarVisor.nombre(titulo, "zip"))
            var puestas = 0
            val usados = HashSet<String>()
            ZipOutputStream(destino.outputStream().buffered()).use { zip ->
                fun entrada(p: Pagina, extension: String, escribir: (java.io.OutputStream) -> Unit) {
                    var nombre = "%02d - %s".format(p.numero, ExportarProyecto.nombreDeArchivo(p.titulo)) + if (extension.isEmpty()) "" else ".$extension"
                    var i = 2
                    while (!usados.add(nombre)) nombre = nombre.substringBeforeLast('.') + " ($i)" + (if (extension.isEmpty()) "" else ".$extension").also { i++ }
                    zip.putNextEntry(ZipEntry(nombre))
                    escribir(zip)
                    zip.closeEntry()
                    puestas++
                }
                for (p in elegidas) {
                    val m = p.mensaje
                    val archivo = m.ruta?.let(::File)?.takeIf { it.isFile }
                    when {
                        archivo != null -> entrada(p, archivo.extension) { o -> archivo.inputStream().use { it.copyTo(o) } }
                        m.clase == Clase.DIBUJO && m.referencia != null -> ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(context, m.referencia))?.let { e ->
                            entrada(p, "excalidraw") { o -> o.write(ExcalidrawJson.encodeToString(com.forge.pixpin.motor.Scene.serializer(), e).toByteArray()) }
                        }
                        m.clase == Clase.NOTA && m.texto.isNotBlank() -> entrada(p, "md") { o -> o.write(m.texto.toByteArray()) }
                    }
                }
            }
            if (puestas == 0) { destino.delete(); null }
            else Compartible.Salida(destino, "application/zip", detalle)
        })
    }

    /**
     * **«Por Wi‑Fi a mis aparatos»**: el `.pixpin` del cuaderno entero por la pantalla de enviar. Se
     * prepara fuera del hilo de la pantalla y luego se abre ella.
     */
    private fun wifi(context: Context, carpeta: Mensaje, todos: List<Mensaje>, titulo: String): Compartible.Formato =
        Compartible.Formato("wifi", Icons.Filled.Wifi, "Enviar por Wi-Fi", Compartible.NINGUNA, accion = {
            Thread {
                val f = runCatching { paqueteDe(context, carpeta, todos, titulo) }
                    .onFailure { android.util.Log.e("PixPinCompartir", "cuaderno por wifi", it) }.getOrNull()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (f != null) com.forge.pixpin.sincro.EnviarActivity.enviarArchivo(context, f.path, f.name)
                    else android.widget.Toast.makeText(context, "No se pudo preparar el cuaderno", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.start()
        })
}
