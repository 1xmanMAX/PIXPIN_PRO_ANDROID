package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.sinAcentos
import com.forge.pixpin.ia.Agente
import com.forge.pixpin.ia.EditarTexto
import com.forge.pixpin.ia.Lienzo
import com.forge.pixpin.ia.LienzoDestino
import com.forge.pixpin.ia.Lint
import com.forge.pixpin.ia.Llamada
import com.forge.pixpin.ia.Preguntar
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Motor
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.PeticionOcr
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.LienzoAlFrente
import com.forge.pixpin.motor.PdfAHtml
import com.forge.pixpin.motor.PlanoDePdf
import com.forge.pixpin.motor.RecibeElementos
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.motor.leerPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Hace de verdad lo que pide el modelo** (`ejecutor.rs`, `al_lienzo.rs` y `ocr.rs` del PC).
 *
 * **Dibujar y gráficas → al lienzo**, en un solo paso de deshacer:
 * - con un lienzo abierto delante ([LienzoAlFrente]), ahí, centrado en lo que se ve;
 * - si no, en el **lienzo propio del asistente** de este chat (el último dibujo con aparato «IA»),
 *   a la derecha de lo que ya había para no taparlo;
 * - y si no hay, en uno nuevo, «Lienzo del asistente», que entra al chat como un dibujo más.
 *
 * **Leer (OCR)**: el teléfono no tiene el OCR de Windows ni ML Kit. Las hojas de un PDF que ya
 * traen texto se leen de él, sin adivinar nada; las escaneadas y las fotos las lee **la visión del
 * propio modelo** (`mmproj`), hoja a hoja. Con el 0.8B eso inventa a menudo (medido en el PC): el
 * resultado lo dice. El texto entero va al chat como nota y como `.txt`, con aparato «IA». Lo
 * leído con la visión pasa por «Mi vocabulario» de los ajustes (25-oct-2026, `docs/ocr.md` del PC).
 */
class EjecutorDelChat(
    private val contexto: Context,
    private val almacen: MensajesStore,
    /** El chat donde se preguntó (null: Mensajes guardados). */
    private val chat: String?,
    /** El motor, para leer con la visión del modelo. */
    private val motor: Motor,
    private val parar: AtomicBoolean,
    /** Pregunta Sí/No en la burbuja antes de lo que borra o saca algo del aparato. */
    confirmar: (String) -> Boolean = { false },
    /** Lo que pidió el usuario (para afinar el calco con sus palabras). */
    private val pedido: String = "",
    /** La foto de la conversación que ve el modelo (la que se calca si no nombra otra). */
    private val foto: File? = null,
    /** null si se pueden mandar trozos de documentos al modelo; si no, lo que se le contesta. */
    permisoDocumentos: () -> String? = { null },
    /** El lienzo nombrado con `@lienzo:` (su referencia): ahí se dibuja, sin abrirlo. */
    private val destino: String? = null,
    /**
     * **La tarjeta de `preguntar_usuario`** en la burbuja (§ del PC, 22-oct): enseña la pregunta y
     * espera la respuesta. null: aquí no hay a quién preguntar (y el turno no ofrece la herramienta).
     */
    private val dudar: ((Preguntar.Pregunta) -> Preguntar.Contestada)? = null,
    /** «Mi vocabulario» de los ajustes (`[ocr] vocabulario` del PC), para lo que lee la visión. */
    vocabularioOcr: String = "",
    /** **La web** (28-oct-2026): se llama con el dominio de cada página antes de bajarla («🌐 leyendo ugr.es…»). */
    private val alLeerLaWeb: (String) -> Unit = {}
) : Agente.Ejecutor {

    private val vocabulario = com.forge.pixpin.ocr.Vocabulario.deTexto(vocabularioOcr)

    private val acciones = AccionesDelTelefono(contexto, almacen, chat, confirmar)
    val documentos = DocumentosAcciones(contexto, almacen, chat, motor, parar, permisoDocumentos)

    /** El lienzo donde dibujó (su `referencia`), para `lienzoIa` y la tarjeta «Abrir lienzo». */
    @Volatile var lienzo: String? = null
        private set

    /** Los ids de lo último que creó o tocó (para «lo último» de `editar_texto`). */
    @Volatile private var ultimos: List<String> = emptyList()

    override fun puedePreguntar(): Boolean = dudar != null

    override fun preguntar(p: Preguntar.Pregunta): Preguntar.Contestada = dudar?.invoke(p) ?: Preguntar.Contestada.SinRespuesta

    override fun ejecutar(llamada: Llamada): Result<String> = when (llamada) {
        is Llamada.Dibujar, is Llamada.Grafica, is Llamada.DibujoSvg -> dibujar(llamada)
        is Llamada.Ocr -> leer(llamada.peticion)
        is Llamada.App -> when {
            com.forge.pixpin.asistente.web.Web.esDeLaWeb(llamada.accion.herramienta) -> web(llamada.accion)
            llamada.accion.herramienta == com.forge.pixpin.ia.Acciones.TABLA -> documentos.tablaDeImagen(llamada.accion)
            else -> acciones.hacer(llamada.accion)
        }
        is Llamada.Buscar -> documentos.buscar(llamada.peticion)
        is Llamada.Calcar -> calcar(llamada.parametros)
        is Llamada.Ayuda -> acciones.ayuda(llamada.pregunta)
        is Llamada.Figuras -> acciones.figuras(llamada.pedido) { elementos -> ponerFigura(elementos) }
        is Llamada.EditarTextos -> editarTexto(llamada.edicion)
    }

    // --------------------------------------------------------------------------------- la web

    /**
     * **La web** (28-oct-2026, `ejecutor.rs::web` del PC): buscar en internet y leer páginas con la
     * red del teléfono ([com.forge.pixpin.asistente.web.RedDelTelefono]). Un PDF de la web se lee
     * con el lector de siempre (el texto de sus hojas y, las escaneadas, con la visión del modelo),
     * desde un fichero temporal que se borra al acabar. La clave de Brave Search, si la hay, de
     * [ClavesDeLaNube].
     */
    private fun web(a: com.forge.pixpin.ia.Acciones.AccionApp): Result<String> =
        com.forge.pixpin.asistente.web.Web.hacer(
            a,
            com.forge.pixpin.asistente.web.Web.Lector(
                red = com.forge.pixpin.asistente.web.RedDelTelefono,
                pdf = ::pdfDeLaWeb,
                alLeer = alLeerLaWeb,
                claveBrave = ClavesDeLaNube.leerBrave(contexto)
            )
        )

    private fun pdfDeLaWeb(bytes: ByteArray): String {
        val carpeta = File(contexto.cacheDir, "asistente-web").apply { mkdirs() }
        val f = File(carpeta, "pagina-${System.nanoTime()}.pdf")
        try {
            f.writeBytes(bytes)
            val (texto, conVision) = leerPdfEntero(f, PeticionOcr(null, emptyList(), null))
            if (texto.isBlank()) error("el PDF no tiene texto que leer")
            return if (conVision > 0) "(leido con la vision del modelo en $conVision hoja(s): puede tener errores)\n$texto" else texto
        } finally {
            f.delete()
        }
    }

    // ----------------------------------------------------------------------- el lienzo destino

    /**
     * **Dónde trabaja el asistente ahora**: el lienzo abierto delante, el nombrado con `@lienzo:`
     * o el propio del asistente en este chat (el mismo orden que [alLienzo]). null si no hay.
     */
    private sealed class Destino {
        abstract val elementos: List<Element>
        class Abierto(val lienzo: RecibeElementos, override val elementos: List<Element>) : Destino()
        class EnDisco(val referencia: String, val escena: Scene) : Destino() { override val elementos get() = escena.elements }
    }

    private fun destinoActual(): Destino? {
        (LienzoAlFrente.actual() as? RecibeElementos)?.let { abierto ->
            val e = runCatching { runBlocking { withContext(Dispatchers.Main) { abierto.elementosDelLienzo() } } }.getOrNull()
            if (e != null) return Destino.Abierto(abierto, e)
        }
        val ref = destino?.takeIf { File(ExcalidrawStore.rutaDe(contexto, it)).isFile }
            ?: ChatDelAsistente.lienzoPropio(almacen.leer(), chat)?.referencia ?: return null
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, ref)) ?: return null
        return Destino.EnDisco(ref, escena)
    }

    /**
     * **El resumen del lienzo destino** para pegarlo al pedido («[Lienzo destino: …]», el
     * `edicion::resumen` del PC): lo que ya hay, con sus `#id`, para editarlo en vez de rehacerlo.
     * null si no hay lienzo destino.
     */
    fun resumenDelLienzo(): String? = runCatching {
        val d = destinoActual() ?: return null
        // Sin las plantillas de serie puestas: el PC las reconoce por su forma
        // (`figuras_de_serie::cual_es`, del motor 2D), que aquí aún no está.
        LienzoDestino.resumen(d.elementos)
    }.getOrNull()

    /** **Revisión automática** ([Lint]) de lo que tocó una llamada, para pegarla a su resultado. */
    private fun revision(elementos: List<Element>, tocados: Collection<String>): String =
        runCatching { Lint.paraElModelo(Lint.revisar(elementos, tocados.toSet())) }.getOrDefault("")

    private fun conRevision(hecho: String, elementos: List<Element>, tocados: Collection<String>): String {
        val r = revision(elementos, tocados)
        return if (r.isEmpty()) hecho else "$hecho\n$r"
    }

    /**
     * **`editar_texto`** en el lienzo destino, en un solo paso de deshacer: en el abierto, por su
     * historial; en el del disco, guardado de una vez por el camino de siempre.
     */
    private fun editarTexto(e: EditarTexto.Edicion): Result<String> = runCatching {
        val d = destinoActual() ?: error("no hay ningun lienzo donde cambiar textos: abre uno o nombralo con @lienzo:")
        val hecho = EditarTexto.hacer(d.elementos, e, ultimos).getOrThrow()
        if (hecho.cambiados.isNotEmpty()) {
            val cambiados = hecho.elementos.filter { it.id in hecho.cambiados }
            when (d) {
                is Destino.Abierto -> {
                    val id = runBlocking { withContext(Dispatchers.Main) { d.lienzo.cambiarElementos(cambiados) } }
                        ?: error("el lienzo abierto no dejo cambiar los textos (esta en solo mirar)")
                    lienzo = id
                }
                is Destino.EnDisco -> {
                    ExcalidrawStore.guardar(contexto, d.referencia, d.escena.copy(elements = hecho.elementos)) ?: error("no se pudo guardar el lienzo")
                    lienzo = d.referencia
                }
            }
            ultimos = hecho.cambiados
        }
        if (hecho.cambiados.isEmpty()) hecho.texto else conRevision(hecho.texto, hecho.elementos, hecho.cambiados)
    }

    // ------------------------------------------------------------------------------------- lienzo

    private fun dibujar(l: Llamada): Result<String> = runCatching {
        val semilla = System.currentTimeMillis()
        val que = when (l) {
            is Llamada.Grafica -> "la grafica"
            is Llamada.DibujoSvg -> "la ilustracion (${Lienzo.elementos(l, 0.0 to 0.0, semilla).size} figuras)"
            else -> "${Lienzo.elementos(l, 0.0 to 0.0, semilla).size} figuras"
        }
        alLienzo(que) { origen -> Lienzo.aElementos(Lienzo.elementos(l, origen, semilla)) }
    }

    /**
     * **Calcar** la foto (la de la conversación, o la que nombre) con [com.forge.pixpin.ia.Calcar],
     * afinado con las palabras del pedido, al lienzo que toque.
     */
    private fun calcar(p0: com.forge.pixpin.ia.Calcar.Parametros): Result<String> = runCatching {
        val p = com.forge.pixpin.ia.Calcar.ajustarAlPedido(p0, pedido)
        val archivo = p.imagen?.let { n ->
            almacen.leer().lastOrNull { m -> m.clase == Clase.IMAGEN && m.ruta != null && File(m.ruta).isFile &&
                (m.nombre.equals(n, true) || File(m.ruta).name.equals(n, true)) }?.ruta?.let(::File)
        } ?: foto ?: almacen.leer().lastOrNull { it.proyecto == chat && it.clase == Clase.IMAGEN && it.ruta != null && File(it.ruta).isFile }?.ruta?.let(::File)
            ?: error("no hay ninguna foto que calcar en este chat")
        val bm = FotoParaElModelo.mapaDe(archivo, 1024) ?: error("no se pudo leer la imagen")
        val rgba = java.nio.ByteBuffer.allocate(bm.width * bm.height * 4)
        bm.copyPixelsToBuffer(rgba)
        val (ancho, alto) = bm.width to bm.height
        bm.recycle()
        val calcado = com.forge.pixpin.ia.Calcar.calcar(rgba.array(), ancho, alto, p)
        if (calcado.trazos.isEmpty()) error("no salio nada al calcar: la imagen es lisa o casi vacia")
        val semilla = System.currentTimeMillis()
        val que = "el calco (${calcado.trazos.size} figuras)"
        alLienzo(que) { origen -> Lienzo.aElementos(Lienzo.elementosDeCalcado(calcado, origen, semilla)) }
    }

    /** Una figura de la biblioteca, estampada en el lienzo (ids nuevos, en un grupo). */
    private fun ponerFigura(elementos: List<Element>): Result<String> = runCatching {
        val figura = com.forge.pixpin.motor.FiguraGuardada("poner", "poner", elementos)
        alLienzo("la figura") { (x, y) ->
            val caja = com.forge.pixpin.motor.getCommonBounds(elementos)
            com.forge.pixpin.motor.estampar(figura, com.forge.pixpin.motor.Pt(x + (caja.x2 - caja.x1) / 2, y + (caja.y2 - caja.y1) / 2))
        }
    }

    /**
     * **Lo nuevo, al lienzo que toca** (`al_lienzo.rs` del PC), en un solo paso de deshacer. [hacer]
     * recibe la esquina donde empezar (0,0 en el lienzo abierto, que lo centra él).
     */
    private fun alLienzo(que: String, hacer: (Pair<Double, Double>) -> List<Element>): String {
        val semilla = System.currentTimeMillis()
        val nuevos = hacer(0.0 to 0.0)
        if (nuevos.isEmpty()) error("no habia nada que dibujar")
        // 1. El lienzo que el usuario tiene delante (pantalla partida): ahí, centrado.
        val abierto = LienzoAlFrente.actual() as? RecibeElementos
        if (abierto != null) {
            val id = runBlocking { withContext(Dispatchers.Main) { abierto.recibirElementos(nuevos) } }
            if (id != null) {
                lienzo = id
                val escena = runCatching { runBlocking { withContext(Dispatchers.Main) { abierto.elementosDelLienzo() } } }.getOrNull()
                return conLoAnadido("dibujada $que en el lienzo abierto", escena, nuevos)
            }
        }
        // 2. El lienzo nombrado con `@lienzo:`, o el propio del asistente en este chat; a la
        // derecha de lo que hubiera, para no taparlo.
        val propio = destino?.takeIf { File(ExcalidrawStore.rutaDe(contexto, it)).isFile }
            ?: ChatDelAsistente.lienzoPropio(almacen.leer(), chat)?.referencia
        if (propio != null) {
            val ruta = ExcalidrawStore.rutaDe(contexto, propio)
            val escena = ExcalidrawStore.cargar(ruta) ?: Scene()
            val origen = Lienzo.alLado(escena.elements)
            val corridos = hacer(origen)
            val todos = escena.elements + corridos
            ExcalidrawStore.guardar(contexto, propio, escena.copy(elements = todos))
                ?: error("no se pudo guardar el lienzo")
            lienzo = propio
            val nombre = almacen.leer().lastOrNull { it.referencia == propio }?.let(ChatDelAsistente::nombreDeLienzo) ?: ChatDelAsistente.NOMBRE_DEL_LIENZO
            return conLoAnadido("dibujada $que en el lienzo «$nombre»", todos, corridos)
        }
        // 3. Uno nuevo, que entra al chat como dibujo del asistente.
        val id = "ia-$semilla"
        ExcalidrawStore.guardar(contexto, id, Scene(elements = nuevos, viewport = vistaPara(nuevos)))
            ?: error("no se pudo guardar el lienzo")
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(),
                cuando = System.currentTimeMillis(),
                clase = Clase.DIBUJO,
                nombre = ChatDelAsistente.NOMBRE_DEL_LIENZO,
                referencia = id,
                proyecto = chat,
                aparato = ChatDelAsistente.APARATO
            )
        )
        lienzo = id
        return conLoAnadido("dibujada $que en un lienzo nuevo, «${ChatDelAsistente.NOMBRE_DEL_LIENZO}»", nuevos, nuevos)
    }

    /** Cuántos de lo recién dibujado se listan en el «hecho» (`MAX_ANADIDOS` del PC). */
    private val MAX_ANADIDOS = 12

    /**
     * **El «hecho» de lo dibujado** con lo que entró y sus `#id` (para que la llamada siguiente lo
     * pueda editar) y la **revisión automática** de lo nuevo ([Lint], `lint.rs` del PC): textos
     * encimados, rótulos que se salen, flechas que no llegan, tildes que faltan.
     */
    private fun conLoAnadido(hecho: String, escena: List<Element>?, nuevos: List<Element>): String {
        if (escena == null) return hecho
        val ids = nuevos.map { it.id }.toSet()
        ultimos = ids.toList()
        val enEscena = escena.filter { it.id in ids }
        val s = StringBuilder(hecho)
        val lista = LienzoDestino.listar(escena, enEscena.take(MAX_ANADIDOS))
        if (lista.isNotEmpty()) s.append(":\n").append(lista)
        if (enEscena.size > MAX_ANADIDOS) s.append("\n(+${enEscena.size - MAX_ANADIDOS} mas)")
        return conRevision(s.toString(), escena, ids)
    }

    /** Que al abrir el lienzo nuevo se vea lo dibujado (está en el 800×600 del modelo). */
    private fun vistaPara(elementos: List<Element>): com.forge.pixpin.motor.Viewport {
        val m = contexto.resources.displayMetrics
        return com.forge.pixpin.motor.fitToContent(elementos, m.widthPixels.toDouble(), m.heightPixels.toDouble(), padding = 40.0)
    }

    // ---------------------------------------------------------------------------------------- OCR

    /** Cuántas hojas escaneadas lee la visión en una llamada (cada una son segundos en un teléfono). */
    private val MAX_HOJAS_CON_VISION = 4
    private val MAX_HOJAS = 40

    private fun leer(p: PeticionOcr): Result<String> = runCatching {
        val todos = almacen.leer().filter { it.proyecto == chat && !it.enBuzon }
        val adjuntos = todos.filter { it.ruta != null && (it.clase == Clase.IMAGEN || esPdf(it)) && File(it.ruta!!).isFile }
        val elegido = p.archivo?.let { nombre ->
            val q = sinAcentos(nombre)
            adjuntos.lastOrNull { sinAcentos(it.nombre).contains(q) || sinAcentos(File(it.ruta!!).name).contains(q) }
        } ?: adjuntos.lastOrNull() ?: error("no hay ninguna foto ni PDF en este chat para leer")
        val ruta = File(elegido.ruta!!)
        val nombre = elegido.nombre.ifBlank { ruta.name }
        val (texto, conVision) = if (esPdf(elegido)) leerPdfEntero(ruta, p) else leerConVision(FotoParaElModelo.deArchivo(ruta, FotoParaElModelo.LADO_PARA_LEER)) to 1
        if (texto.isBlank()) error("no se encontró texto en «$nombre»")
        dejarEnElChat(nombre, texto)
        val aviso = if (conVision > 0) " (leído con la visión del modelo en $conVision hoja(s): puede tener errores)" else ""
        Agente.recortarResultado("Texto de «$nombre»$aviso:\n$texto")
    }

    private fun esPdf(m: Mensaje) = m.ruta?.lowercase()?.endsWith(".pdf") == true || m.nombre.lowercase().endsWith(".pdf")

    /** Las hojas con texto, de él; las demás, con la visión (hasta [MAX_HOJAS_CON_VISION]). */
    private fun leerPdfEntero(pdf: File, p: PeticionOcr): Pair<String, Int> {
        val archivo = leerPdf(pdf.readBytes()) ?: error("no se pudo leer el PDF")
        if (archivo.cifrado) error("el PDF está protegido con contraseña")
        val total = archivo.paginas().size
        val pedidas = if (p.paginas.isEmpty()) (1..total).toList()
        else p.paginas.flatMap { (a, b) -> (a..minOf(b, total)).toList() }.distinct()
        if (pedidas.isEmpty()) error("el PDF tiene $total hojas")
        val salida = StringBuilder()
        var conVision = 0
        for (n in pedidas.take(MAX_HOJAS)) {
            if (parar.get()) break
            val lineas = PdfAHtml.lineasEnOrden(PdfAHtml.trozosDe(PlanoDePdf.de(archivo, n - 1)?.textos.orEmpty()))
            var texto = lineas.joinToString("\n") { it.texto }.trim()
            if (texto.isBlank() && conVision < MAX_HOJAS_CON_VISION) {
                conVision++
                texto = leerConVision(FotoParaElModelo.deHojaDePdf(pdf, n - 1))
            }
            if (texto.isNotBlank()) salida.append("— Hoja $n —\n").append(texto).append("\n\n")
        }
        return salida.toString().trim() to conVision
    }

    /** **La visión del modelo como OCR**: que transcriba, sin comentar. */
    private fun leerConVision(jpeg: ByteArray?): String {
        jpeg ?: return ""
        val r = motor.completar(
            Peticion(
                listOf(
                    MensajeIa.sistema("Eres un lector de texto. Transcribe exactamente el texto que se ve, en su orden, sin traducir ni comentar."),
                    MensajeIa.usuarioConImagen("Transcribe todo el texto de esta imagen.", jpeg, "image/jpeg")
                ),
                maxTokens = 1500, temperatura = 0.2
            )
        ) { if (parar.get()) Seguir.PARAR else Seguir.SI }
        // **«Mi vocabulario»** (25-oct-2026): lo que se parece claramente a un término del usuario se
        // escribe como él lo pone, como hace Indra en el PC. Sin diccionario: la visión no da
        // confianza por letra (ver [com.forge.pixpin.ocr.CorrectorOcr.conVocabulario]).
        return com.forge.pixpin.ocr.CorrectorOcr.conVocabulario(ChatDelAsistente.sinPensado(r.texto).trim(), vocabulario)
    }

    /** El texto leído, al chat: una nota y un `«<nombre> (texto).txt»`, del aparato «IA». */
    private fun dejarEnElChat(nombre: String, texto: String) {
        val ahora = System.currentTimeMillis()
        val base = nombre.substringBeforeLast('.').ifBlank { nombre }
        val txt = File(almacen.carpetaDeAdjuntos(), "texto-$ahora.txt").apply { writeText(texto) }
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(), cuando = ahora, clase = Clase.NOTA,
                texto = texto.take(4000), proyecto = chat, aparato = ChatDelAsistente.APARATO
            )
        )
        almacen.anadir(
            Mensaje(
                id = UUID.randomUUID().toString(), cuando = ahora + 1, clase = Clase.ARCHIVO,
                ruta = txt.path, nombre = "$base (texto).txt", bytes = txt.length(),
                proyecto = chat, aparato = ChatDelAsistente.APARATO
            )
        )
    }
}
