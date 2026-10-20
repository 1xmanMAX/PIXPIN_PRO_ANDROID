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
    confirmar: (String) -> Boolean = { false },
    /** Lo que pidió el usuario (para afinar el calco con sus palabras). */
    private val pedido: String = "",
    /** La foto de la conversación que ve el modelo (la que se calca si no nombra otra). */
    private val foto: File? = null,
    /** null si se pueden mandar trozos de documentos al modelo; si no, lo que se le contesta. */
    permisoDocumentos: () -> String? = { null },
    /** El lienzo nombrado con `@lienzo:` (su referencia): ahí se dibuja, sin abrirlo. */
    destino: String? = null,
    /** El lienzo destino del turno con sus `#n` y su marco (el mismo con que se hizo el resumen del pedido). */
    private val lienzoDelTurno: LienzoDelTurno = LienzoDelTurno(contexto, { almacen.leer() }, chat)
) : Agente.Ejecutor {
    /** El lienzo destino del turno: el de `@lienzo:`, o el que pongan crear_lienzo y usar_lienzo (PC 17-oct). */
    @Volatile private var destino: String? = destino
    private val enElChat = EnElChat(contexto, almacen, chat, { this.destino }) { ref -> this.destino = ref; if (ref != null) lienzo = ref }

    private val acciones = AccionesDelTelefono(contexto, almacen, chat, confirmar)
    val documentos = DocumentosAcciones(contexto, almacen, chat, motor, parar, permisoDocumentos)

    /** El lienzo donde dibujó (su `referencia`), para `lienzoIa` y la tarjeta «Abrir lienzo». */
    @Volatile var lienzo: String? = null
        private set

    override fun ejecutar(llamada: Llamada): Result<String> = when (llamada) {
        is Llamada.Dibujar, is Llamada.Grafica, is Llamada.DibujoSvg, is Llamada.Cronograma -> dibujar(llamada)
        is Llamada.Funciones -> runCatching {
            val piezas = com.forge.pixpin.ia.Funciones.piezas(llamada.pedido, medirFraunces)
            alLienzo("la grafica (${llamada.pedido.curvas.size} curvas)") { (x, y) -> piezas.map { it.copy(x = it.x + x, y = it.y + y) } }
        }
        is Llamada.Fisica -> runCatching {
            val piezas = com.forge.pixpin.ia.Fisica.piezas(llamada.diagrama, medirFraunces)
            alLienzo("el diagrama de ${llamada.diagrama.tipo.replace('_', ' ')}") { (x, y) -> piezas.map { it.copy(x = it.x + x, y = it.y + y) } }
        }
        is Llamada.Problema -> runCatching {
            val semilla = System.currentTimeMillis()
            val els = com.forge.pixpin.ia.Problema.elementos(llamada.problema, medirFraunces) { figuras ->
                Lienzo.aElementos(Lienzo.elementos(Llamada.Dibujar(figuras), 0.0 to 0.0, semilla))
            }
            alLienzo("el problema en limpio") { (x, y) -> els.map { it.copy(x = it.x + x, y = it.y + y) } }
        }
        is Llamada.Ecuacion -> runCatching {
            // Medida con la letra de verdad (Fraunces, la de serifa), como el PC con DirectWrite.
            val (piezas, _) = com.forge.pixpin.ia.EscribirEcuacion.piezas(llamada.ecuacion) { t, tam ->
                com.forge.pixpin.motor.DrawFonts.medirTexto(contexto, t, com.forge.pixpin.motor.ItemStyle.FONT_FRAUNCES, tam)
            }
            alLienzo("la ecuacion") { (x, y) -> piezas.map { it.copy(x = it.x + x, y = it.y + y) } }
        }
        is Llamada.Ocr -> leer(llamada.peticion)
        is Llamada.App -> when {
            llamada.accion.herramienta == com.forge.pixpin.ia.Acciones.TABLA -> documentos.tablaDeImagen(llamada.accion)
            llamada.accion.herramienta == com.forge.pixpin.ia.Acciones.ANOTAR -> anotarDocumento(llamada.accion.pedido)
            llamada.accion.herramienta == com.forge.pixpin.ia.Acciones.BUSCAR_EN -> buscarEnDocumento(llamada.accion.pedido)
            llamada.accion.herramienta == com.forge.pixpin.ia.Acciones.EXPORTAR -> exportarDocumento(llamada.accion.pedido)
            llamada.accion.herramienta == com.forge.pixpin.ia.Anotacion.NOMBRE -> runCatching {
                medirLasNotas()
                try { AnotarInteligente(contexto, docs, parar).hacer(llamada.accion.pedido, motor, motor.catalogoCompleto) }
                catch (e: IllegalStateException) { error(e.message ?: "no se pudo anotar") }
            }
            llamada.accion.fila.accion == com.forge.pixpin.ia.Acciones.EN_EL_CHAT -> enElChat.hacer(llamada.accion)
            else -> acciones.hacer(llamada.accion)
        }
        is Llamada.Buscar -> documentos.buscar(llamada.peticion)
        is Llamada.Calcar -> calcar(llamada.parametros)
        is Llamada.Ayuda -> acciones.ayuda(llamada.pregunta)
        is Llamada.Figuras -> acciones.figuras(llamada.pedido) { elementos -> ponerFigura(elementos) }
        is Llamada.Geometria -> geometria(llamada.args)
        is Llamada.Editar -> editar(llamada.pedido)
    }

    /** Lo que mide un texto en la letra de la pizarra (Fraunces), la de verdad. */
    private val medirFraunces: com.forge.pixpin.motor.MedidaDeTexto = { t, tam ->
        com.forge.pixpin.motor.DrawFonts.medirTexto(contexto, t, com.forge.pixpin.motor.ItemStyle.FONT_FRAUNCES, tam)
    }

    private val docs by lazy { DocumentosDelAsistente(contexto, almacen, chat) }

    /** Las notas del anotador se parten midiendo con su letra de verdad (Nunito), como el PC con DirectWrite. */
    private fun medirLasNotas() {
        if (com.forge.pixpin.ia.Anotacion.medidor !== com.forge.pixpin.ia.Anotacion.A_OJO) return
        val letra = com.forge.pixpin.motor.DrawFonts.typefaceFor(contexto, com.forge.pixpin.ia.Anotacion.FAMILIA)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = letra }
        com.forge.pixpin.ia.Anotacion.medidor = com.forge.pixpin.ia.Anotacion.Medidor { t, tam, negrita ->
            synchronized(p) { p.textSize = tam.toFloat(); p.isFakeBoldText = negrita; p.measureText(t).toDouble() }
        }
    }

    private fun textoDe(p: kotlinx.serialization.json.JsonObject, k: String) =
        (p[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** **Anotar un PDF, Word, PowerPoint o plano** (PC `anotar`), y abrirlo donde quedó. */
    private fun anotarDocumento(p: kotlinx.serialization.json.JsonObject): Result<String> = runCatching {
        val c = try { docs.elegir(textoDe(p, "archivo"), DocumentosDelAsistente.Sirve.DOCUMENTO) } catch (e: IllegalStateException) { error(e.message ?: "no hay documento") }
        val hecho = try { docs.anotarEn(c, p) } catch (e: IllegalStateException) { error(e.message ?: "no se pudo") }
        docs.abrirEn(c, hecho.pagina, hecho.vista)
        val tipo = textoDe(p, "tipo") ?: "anotado"
        "hecho: $tipo en «${c.nombre}»${hecho.pagina?.let { ", pagina ${it + 1}" } ?: ""}: marcado en ${hecho.sitios} sitio(s)" +
            (if (hecho.hallados > hecho.sitios) " (aparece ${hecho.hallados} veces en todo el documento; se marco lo de esta pagina o, al encerrar, lo primero)" else "") +
            " (${hecho.figuras} figuras); ya esta abierto ahi y viaja al PC al sincronizar"
    }

    /** **Buscar dentro** de un plano, PDF o Word y abrir el visor ahí (PC `buscar_en`). */
    private fun buscarEnDocumento(p: kotlinx.serialization.json.JsonObject): Result<String> = runCatching {
        val consulta = textoDe(p, "texto") ?: error("falta «texto»")
        val c = try { docs.elegir(textoDe(p, "archivo"), DocumentosDelAsistente.Sirve.DOCUMENTO) } catch (e: IllegalStateException) { error(e.message ?: "no hay documento") }
        val abrir = (p["abrir"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
        try { docs.buscarDentro(c, consulta, abrir) }
        catch (e: IllegalStateException) { error(e.message ?: "no aparece") }
    }

    /** **Exportar** un archivo del chat por el componente común (PC `exportar`): lo hecho entra en el chat. */
    private fun exportarDocumento(p: kotlinx.serialization.json.JsonObject): Result<String> = runCatching {
        val formato = textoDe(p, "formato") ?: error("falta «formato»")
        val c = try { docs.elegir(textoDe(p, "archivo"), DocumentosDelAsistente.Sirve.CUALQUIERA) } catch (e: IllegalStateException) { error(e.message ?: "no hay archivo") }
        val hechos = try { docs.exportar(c, formato) } catch (e: IllegalStateException) { error(e.message ?: "no se pudo") }
        "hecho: «${c.nombre}» exportado a $formato (${hechos.joinToString(", ") { it.name.substringAfter('-') }}), ya esta en el chat"
    }

    /** **Los ojos**: la vista del lienzo destino del turno (PC `Ejecutor::ver`). */
    override fun ver(mirada: com.forge.pixpin.ia.Ojos.Mirada): Result<com.forge.pixpin.ia.Ojos.Vista> = runCatching {
        if (mirada.zona == com.forge.pixpin.ia.Ojos.Zona.Documento) error("aqui solo se mira el lienzo: los documentos anotados se ven al abrirlos")
        lienzoDelTurno.ver(destino, mirada)
    }

    /** **Editar lo que ya hay** ([com.forge.pixpin.ia.Edicion]) en el lienzo destino del turno. */
    private fun editar(p: com.forge.pixpin.ia.Edicion.Pedido): Result<String> = runCatching {
        val fotos = {
            almacen.leer().filter { it.proyecto == chat && it.clase == Clase.IMAGEN && it.ruta != null && File(it.ruta).isFile }
                .map { it.nombre to File(it.ruta!!) }
        }
        val (hecho, ref) = try {
            lienzoDelTurno.editar(destino, p) { LienzoDelTurno.FotosDelChat(contexto, fotos) }
        } catch (e: com.forge.pixpin.ia.Edicion.Fallo) {
            error(e.message ?: "no se pudo")
        }
        if (p.herramienta != com.forge.pixpin.ia.Edicion.SELECCIONAR) lienzo = ref
        hecho
    }

    // ------------------------------------------------------------------------------------- lienzo

    private fun dibujar(l: Llamada): Result<String> = runCatching {
        val semilla = System.currentTimeMillis()
        val que = when (l) {
            is Llamada.Grafica -> "la grafica"
            is Llamada.Cronograma -> "el cronograma (${l.cronograma.tareas.size} tareas)"
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
        // El texto no se calca como forma (salían garabatos): lo que lee el OCR se tapa y va como
        // texto editable (PC 15-oct). Sin OCR, como antes.
        val pixeles = rgba.array()
        val palabras = com.forge.pixpin.ia.Calcar.palabrasQueCuentan(LectorDeLetras.leer(bm), ancho, alto, p.zona)
            .map { it.copy(color = com.forge.pixpin.ia.Calcar.colorDeTinta(pixeles, ancho, alto, it)) }
        bm.recycle()
        com.forge.pixpin.ia.Calcar.taparTexto(pixeles, ancho, alto, palabras)
        val calcado = com.forge.pixpin.ia.Calcar.calcar(pixeles, ancho, alto, p)
        if (calcado.trazos.isEmpty() && palabras.isEmpty()) error("no salio nada al calcar: la imagen es lisa o casi vacia")
        val semilla = System.currentTimeMillis()
        val que = "el calco (${calcado.trazos.size} figuras" + (if (palabras.isNotEmpty()) " y ${palabras.size} palabras)" else ")")
        alLienzo(que) { origen -> Lienzo.aElementos(Lienzo.elementosDeCalcado(calcado, origen, semilla) + Lienzo.textosDelCalco(calcado, palabras, origen, semilla)) }
    }

    /**
     * **Geometría exacta** ([com.forge.pixpin.ia.GeometriaPrecisa]) en el lienzo destino del turno (el de
     * `@lienzo:`, crear_lienzo o usar_lienzo, o el propio del asistente): ahí están las figuras que
     * nombra «entre» o «a» por su #n. Sin ninguno, uno nuevo.
     */
    private fun geometria(args: kotlinx.serialization.json.JsonObject): Result<String> = runCatching {
        val figura = args["figura"]?.toString()?.trim('"') ?: "figura"
        // En el lienzo del turno (el abierto, el de @lienzo: o el propio), con sus #n y su marco.
        val hecho = try {
            lienzoDelTurno.geometria(destino) { escena, num, marco ->
                com.forge.pixpin.ia.GeometriaPrecisa.hacer(args, escena.elements, marco, numeracion = num)
            }
        } catch (e: com.forge.pixpin.ia.Edicion.Fallo) {
            error(e.message ?: "no se pudo")
        }
        if (hecho == null) {
            val nuevos = com.forge.pixpin.ia.GeometriaPrecisa.hacer(args, emptyList(), com.forge.pixpin.motor.Pt(0.0, 0.0))
            return@runCatching alLienzo("la $figura") { (x, y) -> nuevos.map { it.copy(x = it.x + x, y = it.y + y) } }
        }
        lienzo = hecho.second
        "hecho: $figura trazada (${hecho.first})"
    }

    /** Dónde empieza el marco de 800×600 del modelo en un lienzo: la esquina de lo que ya hay. */
    fun marcoDe(elementos: List<Element>): com.forge.pixpin.motor.Pt {
        val vivos = elementos.filter { !it.isDeleted }
        if (vivos.isEmpty()) return com.forge.pixpin.motor.Pt(0.0, 0.0)
        val b = com.forge.pixpin.motor.getCommonBounds(vivos)
        return com.forge.pixpin.motor.Pt(b.x1, b.y1)
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
                return "dibujada $que en el lienzo abierto" + conSusNumeros(id, nuevos)
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
            ExcalidrawStore.guardar(contexto, propio, escena.copy(elements = escena.elements + corridos))
                ?: error("no se pudo guardar el lienzo")
            lienzo = propio
            val nombre = almacen.leer().lastOrNull { it.referencia == propio }?.let(ChatDelAsistente::nombreDeLienzo) ?: ChatDelAsistente.NOMBRE_DEL_LIENZO
            return "dibujada $que en el lienzo «$nombre»" + conSusNumeros(propio, corridos)
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
        return "dibujada $que en un lienzo nuevo, «${ChatDelAsistente.NOMBRE_DEL_LIENZO}»" + conSusNumeros(id, nuevos)
    }

    /** Los `#n` de lo recién dibujado (PC `resumen_de_lo_anadido`), para que la llamada siguiente lo edite. */
    private fun conSusNumeros(ref: String, nuevos: List<Element>): String =
        runCatching { lienzoDelTurno.anadidos(ref, nuevos.map { it.id }) }.getOrNull()?.let { "\n" + it.removePrefix("hecho: ") } ?: ""

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
