package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.guardados.MensajesStore
import com.forge.pixpin.guardados.sinAcentos
import com.forge.pixpin.ia.Agente
import com.forge.pixpin.ia.Lienzo
import com.forge.pixpin.ia.Llamada
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
 * resultado lo dice. El texto entero va al chat como nota y como `.txt`, con aparato «IA».
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
    confirmar: (String) -> Boolean = { false }
) : Agente.Ejecutor {

    private val acciones = AccionesDelTelefono(contexto, almacen, chat, confirmar)

    /** El lienzo donde dibujó (su `referencia`), para `lienzoIa` y la tarjeta «Abrir lienzo». */
    @Volatile var lienzo: String? = null
        private set

    override fun ejecutar(llamada: Llamada): Result<String> = when (llamada) {
        is Llamada.Dibujar, is Llamada.Grafica -> dibujar(llamada)
        is Llamada.Ocr -> leer(llamada.peticion)
        is Llamada.App -> acciones.hacer(llamada.accion)
        is Llamada.Ayuda -> acciones.ayuda(llamada.pregunta)
        is Llamada.Figuras -> acciones.figuras(llamada.pedido) { elementos -> ponerFigura(elementos) }
    }

    // ------------------------------------------------------------------------------------- lienzo

    private fun dibujar(l: Llamada): Result<String> = runCatching {
        val semilla = System.currentTimeMillis()
        val que = if (l is Llamada.Grafica) "la grafica" else "${Lienzo.elementos(l, 0.0 to 0.0, semilla).size} figuras"
        alLienzo(que) { origen -> Lienzo.aElementos(Lienzo.elementos(l, origen, semilla)) }
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
                return "dibujada $que en el lienzo abierto"
            }
        }
        // 2. El lienzo propio del asistente en este chat, a la derecha de lo que hubiera.
        val propio = ChatDelAsistente.lienzoPropio(almacen.leer(), chat)?.referencia
        if (propio != null) {
            val ruta = ExcalidrawStore.rutaDe(contexto, propio)
            val escena = ExcalidrawStore.cargar(ruta) ?: Scene()
            val origen = Lienzo.alLado(escena.elements)
            val corridos = hacer(origen)
            ExcalidrawStore.guardar(contexto, propio, escena.copy(elements = escena.elements + corridos))
                ?: error("no se pudo guardar el lienzo")
            lienzo = propio
            return "dibujada $que en el lienzo «${ChatDelAsistente.NOMBRE_DEL_LIENZO}»"
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
        return "dibujada $que en un lienzo nuevo, «${ChatDelAsistente.NOMBRE_DEL_LIENZO}»"
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
        return ChatDelAsistente.sinPensado(r.texto).trim()
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
