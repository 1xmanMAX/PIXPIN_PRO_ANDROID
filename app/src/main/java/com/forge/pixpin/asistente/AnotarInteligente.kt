package com.forge.pixpin.asistente

import android.content.Context
import com.forge.pixpin.ia.Acciones
import com.forge.pixpin.ia.Anotacion
import com.forge.pixpin.ia.Anotacion.Pagina
import com.forge.pixpin.ia.Anotacion.Palabra
import com.forge.pixpin.ia.Anotacion.Renglon
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Motor
import com.forge.pixpin.ia.Peticion
import com.forge.pixpin.ia.Seguir
import com.forge.pixpin.ia.Trozo
import com.forge.pixpin.motor.Element
import com.forge.pixpin.motor.ElementType
import com.forge.pixpin.motor.ExcalidrawStore
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.sincro.AnotacionesDelAdjunto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * **`anotar_inteligente`, del lado del teléfono** (PC `asistente/anotar_inteligente.rs`, 15-oct-2026):
 * leer la página CON dónde cae cada palabra, pedirle al modelo las anotaciones y pintarlas en la capa
 * del documento según llegan. El motor es uno ([Anotacion], el del PC); aquí solo se **lee** cada
 * formato y se **escribe** en la MISMA capa que leen y sincronizan los visores:
 *
 * - PDF, Word y PowerPoint: la hoja del lector (`anot-<uid>-p<i>` con su `.hoja`), 1400 de ancho.
 * - Una foto: su lienzo (`Mensaje.dibujoDeLaFoto`), con la foto en 0,0 a lo que mide al cargarla.
 * - Un plano: la capa `anot-<uid>` del visor, en sus unidades.
 * - Un Excel: la tinta de la hoja (`anot-<uid>-h<i>`), en píxeles de la hoja al 100 %.
 *
 * Diferencia con el PC: los visores del teléfono no releen la capa mientras están abiertos, así que el
 * visor se abre al acabar el turno (no con la primera anotación).
 */
class AnotarInteligente(
    private val contexto: Context,
    private val docs: DocumentosDelAsistente,
    private val parar: AtomicBoolean
) {
    enum class Formato { PDF, OFFICE, FOTO, PLANO, LIBRO }

    companion object {
        const val MAX_PAGINAS = 5
        const val MAX_OCR = 3
        const val LADO_OCR = 2600
        const val MAX_FILAS = 400
        const val MAX_CELDAS = 1500
        val ANCHO_HOJA = DocumentosDelAsistente.ANCHO_HOJA

        const val SOLO_NUBE = "anotar con criterio necesita el modelo de la nube (Claude o DeepSeek en Ajustes → Asistente); " +
            "con el local puedo resaltar o encerrar un texto concreto con anotar_documento"

        fun formatoDe(nombre: String): Formato? {
            val e = nombre.substringAfterLast('.', "").lowercase()
            return when {
                e == "pdf" -> Formato.PDF
                e in setOf("docx", "docm", "dotx", "dotm", "pptx", "pptm", "ppsx", "ppsm", "potx", "potm") -> Formato.OFFICE
                e in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic") -> Formato.FOTO
                com.forge.pixpin.planos.LectorDePlanos.esPlano(nombre) -> Formato.PLANO
                e in setOf("xlsx", "xlsm", "xls") -> Formato.LIBRO
                else -> null
            }
        }

        /**
         * **Las palabras y renglones de una hoja leída** (letra a letra, cajas en fracciones) en las
         * unidades de su capa (PC `renglones_de`): una palabra acaba en un espacio o donde la letra salta
         * de renglón; un renglón, en un salto de línea o donde la letra baja (o vuelve atrás).
         */
        fun renglonesDe(t: DocumentosDelAsistente.PaginaDeTexto, ancho: Double, alto: Double): MutableList<Renglon> {
            val renglones = ArrayList<Renglon>()
            var actual = Renglon()
            val palabra = StringBuilder()
            var caja: DoubleArray? = null
            fun cerrarPalabra() {
                val c = caja
                if (c != null && palabra.isNotBlank()) actual.palabras += Palabra(palabra.toString(), c)
                caja = null; palabra.setLength(0)
            }
            fun cerrarRenglon() { if (actual.palabras.isNotEmpty()) { renglones += actual; actual = Renglon() } }
            for ((k, ch) in t.texto.withIndex()) {
                if (ch == '\n') { cerrarPalabra(); cerrarRenglon(); continue }
                val f = t.cajas.getOrNull(k)
                if (f == null || ch.isWhitespace()) { cerrarPalabra(); continue }
                val c = doubleArrayOf(f[0] * ancho, f[1] * alto, f[2] * ancho, f[3] * alto)
                val antes = caja ?: actual.palabras.lastOrNull()?.caja
                if (antes != null) {
                    val h = max(max(antes[3] - antes[1], c[3] - c[1]), 1e-3)
                    val salta = kotlin.math.abs((c[1] + c[3]) / 2 - (antes[1] + antes[3]) / 2) > h * 0.6 || c[0] < antes[0] - h * 2
                    if (salta) { cerrarPalabra(); cerrarRenglon() }
                }
                palabra.append(ch)
                val u = caja
                caja = if (u == null) c else doubleArrayOf(min(u[0], c[0]), min(u[1], c[1]), max(u[2], c[2]), max(u[3], c[3]))
            }
            cerrarPalabra(); cerrarRenglon()
            return renglones
        }

        fun altoTipico(renglones: List<Renglon>): Double {
            val h = renglones.flatMap { r -> r.palabras.map { it.caja[3] - it.caja[1] } }.filter { it > 0 }.sorted()
            return if (h.isEmpty()) 0.0 else h[h.size / 2]
        }

        /** La letra de las notas sobre una hoja de 1400: algo menos que la del texto, legible a la hoja entera. */
        fun letraDeHoja(renglones: List<Renglon>): Double {
            val t = altoTipico(renglones)
            return if (t <= 0) 20.0 else (t * 0.72).coerceIn(17.0, 24.0)
        }

        fun paginaDeHoja(numero: Int, alto: Double, renglones: MutableList<Renglon>) = Pagina(
            numero, doubleArrayOf(0.0, 0.0, ANCHO_HOJA, alto), renglones, letraDeHoja(renglones),
            // El espacio para anotar del lector.
            ANCHO_HOJA * com.forge.pixpin.motor.Lectura.MARGEN_DEL_PDF
        )

        /** «AA» para la columna 26 (desde 0). */
        fun nombreDeColumna(c: Int): String {
            var n = c + 1; val s = StringBuilder()
            while (n > 0) { val r = (n - 1) % 26; s.insert(0, 'A' + r); n = (n - 1) / 26 }
            return s.toString()
        }

        /** El pedido para el modelo: lo que dijo el usuario, el modo y el foco. */
        fun pedidoDe(args: JsonObject): String {
            fun t(k: String) = (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val s = StringBuilder(t("pedido") ?: "señala lo importante")
            t("modo")?.takeIf { it != "mixto" }?.let { s.append(" (modo: $it)") }
            t("foco")?.let { s.append(". Lo que hay que explicar: «$it» (anclalo ahi)") }
            return s.toString()
        }

        /** Las páginas pedidas (desde 0): `pagina`..`hasta`, como mucho [MAX_PAGINAS]; sin `pagina`, la 1. */
        fun paginasPedidas(args: JsonObject, total: Int): List<Int> {
            if (total == 0) return emptyList()
            fun e(k: String) = (args[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }
            val desde = (e("pagina")?.takeIf { it >= 1 }?.minus(1) ?: 0).coerceAtMost(total - 1)
            val hasta = (e("hasta")?.takeIf { it >= 1 }?.minus(1) ?: desde).coerceIn(desde, total - 1)
            return (desde..min(hasta, desde + MAX_PAGINAS - 1)).toList()
        }
    }

    /** Dónde van las figuras de cada página. */
    sealed class Destino {
        /** [cuantas]: las hojas del documento (las de antes de abrir un espacio guardan su marco). */
        class Hojas(val c: DocumentosDelAsistente.Candidato, val cuantas: Int) : Destino()
        class Foto(val id: String) : Destino()
        class Plano(val c: DocumentosDelAsistente.Candidato, val id: String, val ruta: File, val hoja: File,
                    val encaje: com.forge.pixpin.planos.CapaDelPlano.Encaje, val m: com.forge.pixpin.planos.ModeloCad) : Destino()
        class Libro(val id: String) : Destino()
    }

    class Leido(val formato: Formato, val paginas: List<Pagina>, val destino: Destino, val conOcr: Int)

    private fun texto(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** **Lee lo que se va a anotar**, con dónde cae cada palabra, en las unidades de su capa. */
    fun leer(c: DocumentosDelAsistente.Candidato, args: JsonObject): Leido {
        val f = formatoDe(c.nombre) ?: throw IllegalStateException("ese archivo no se puede anotar (PDF, Word, PowerPoint, Excel, foto o plano)")
        return when (f) {
            Formato.PDF, Formato.OFFICE -> leerHojas(c, args, f)
            Formato.FOTO -> leerFoto(c)
            Formato.PLANO -> leerPlano(c)
            Formato.LIBRO -> leerLibro(c, args)
        }
    }

    private fun leerHojas(c: DocumentosDelAsistente.Candidato, args: JsonObject, f: Formato): Leido {
        val cuantas = if (f == Formato.PDF) com.forge.pixpin.motor.leerPdf(c.ruta.readBytes())?.paginas()?.size ?: 0
        else com.forge.pixpin.office.HojasOffice.de(contexto, c.ruta.path)?.let { it.maquetarTodo { }; it.cuantas() } ?: 0
        val pedidas = paginasPedidas(args, cuantas)
        if (pedidas.isEmpty()) throw IllegalStateException("el documento no tiene paginas")
        if (parar.get()) throw IllegalStateException("el usuario lo paro")
        val p = docs.paginas(c.ruta, c.nombre, pedidas.toSet(), MAX_OCR)
        val conOcr = 0
        val paginas = pedidas.map { i -> paginaDeHoja(i, p.altos[i], renglonesDe(p.textos[i], ANCHO_HOJA, p.altos[i])) }
        return Leido(f, paginas, Destino.Hojas(c, cuantas), conOcr)
    }

    private fun leerFoto(c: DocumentosDelAsistente.Candidato): Leido {
        // El lienzo de la foto, como lo crea el chat al abrirla: la foto en 0,0 a lo que mide al cargarla.
        val id = asegurarElLienzoDeLaFoto(c)
        val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, id)) ?: throw IllegalStateException("no se pudo abrir el lienzo de la foto")
        val foto = escena.elements.firstOrNull { it.type == ElementType.IMAGE && !it.isDeleted }
        val bm = com.forge.pixpin.pin.ImageStore.load(c.ruta.path) ?: throw IllegalStateException("no se pudo abrir la imagen")
        val w = bm.width.toDouble(); val h = bm.height.toDouble()
        val mayor = max(bm.width, bm.height)
        val chica = if (mayor > LADO_OCR) android.graphics.Bitmap.createScaledBitmap(bm, max(1, bm.width * LADO_OCR / mayor), max(1, bm.height * LADO_OCR / mayor), true) else bm
        val lineas = LectorDeLetras.renglones(chica) ?: throw IllegalStateException("el OCR del teléfono no está disponible (hace falta Google Play)")
        // De los píxeles leídos a las unidades del lienzo (la foto puede estar movida o estirada).
        val fx = (foto?.width ?: w) / chica.width; val fy = (foto?.height ?: h) / chica.height
        val ox = foto?.x ?: 0.0; val oy = foto?.y ?: 0.0
        if (chica !== bm) chica.recycle()
        val renglones = lineas.map { l ->
            Renglon(l.map { p -> Palabra(p.texto, doubleArrayOf(ox + p.x * fx, oy + p.y * fy, ox + (p.x + p.w) * fx, oy + (p.y + p.h) * fy)) }.toMutableList())
        }.toMutableList()
        val wf = foto?.width ?: w; val hf = foto?.height ?: h
        val letra = max(max(altoTipico(renglones) * 0.8, wf / 75.0), 10.0)
        val pagina = Pagina(0, doubleArrayOf(ox, oy, ox + wf, oy + hf), renglones, letra, max(wf * 0.6, letra * 20))
        return Leido(Formato.FOTO, listOf(pagina), Destino.Foto(id), 1)
    }

    /** El lienzo de la foto del chat (el mismo que abre el chat), creado si aún no lo tiene. */
    private fun asegurarElLienzoDeLaFoto(c: DocumentosDelAsistente.Candidato): String {
        val m = c.mensaje
        val id = m.referencia ?: "foto-${m.id}"
        val ruta = ExcalidrawStore.rutaDe(contexto, id)
        if (!File(ruta).isFile) {
            val bm = com.forge.pixpin.pin.ImageStore.load(c.ruta.path) ?: throw IllegalStateException("no se pudo abrir la imagen")
            val archivo = ExcalidrawStore.guardarImagen(contexto, c.ruta, "image/png") ?: throw IllegalStateException("no se pudo copiar la foto")
            val e = com.forge.pixpin.motor.newImageElement(archivo.id, 0.0, 0.0, bm.width.toDouble(), bm.height.toDouble()).copy(locked = true)
            ExcalidrawStore.guardar(contexto, id, Scene(elements = listOf(e), files = mapOf(archivo.id to archivo)))
                ?: throw IllegalStateException("no se pudo crear el lienzo de la foto")
        }
        if (m.referencia == null) docs.apuntarReferencia(m.id, id)
        return id
    }

    private fun leerPlano(c: DocumentosDelAsistente.Candidato): Leido {
        val r = com.forge.pixpin.planos.LectorDePlanos.leer(contexto, c.ruta)
        val f = (r as? com.forge.pixpin.planos.LectorDePlanos.Companion.Resultado.Listo)?.archivo ?: throw IllegalStateException("no se pudo leer el plano «${c.nombre}»")
        val m = com.forge.pixpin.planos.ModeloCad.abrir(f)
        val base = com.forge.pixpin.ui.ExportarDocumentoAnotado.baseDe(contexto, c.ruta)
        val id = com.forge.pixpin.ui.ExportarDocumentoAnotado.idDeLaCapa(c.ruta, base)
        val capa = File(ExcalidrawStore.rutaDe(contexto, id))
        val hoja = AnotacionesDelAdjunto.hoja(contexto.filesDir, id)
        val encaje = AnotacionesDelAdjunto.leerMarco(hoja)?.let { com.forge.pixpin.planos.CapaDelPlano.encajeDe(it, m.caja) }
            ?: com.forge.pixpin.planos.CapaDelPlano.Encaje(com.forge.pixpin.planos.EscalaDeLaCapa.leerOPoner(capa, m), 0.0, 0.0)
        val ox = m.origenX; val oy = m.origenY
        fun aCapa(x: Double, y: Double) = encaje.aCapaX(x) to encaje.aCapaY(y)
        // Los rótulos grandes primero, sin repetir el mismo texto más de dos veces.
        val textos = docs.textosDelPlano(c).sortedByDescending { it.second[2] }
        val vistos = HashMap<String, Int>()
        val renglones = ArrayList<Renglon>()
        for ((t, p) in textos) {
            val clave = Acciones.normalizar(t.trim())
            val alto = p[2]
            if (clave.isEmpty() || alto <= 0) continue
            val n = (vistos[clave] ?: 0) + 1; vistos[clave] = n
            if (n > 2) continue
            val x = p[0] - ox; val y = p[1] - oy
            val letras = max(t.codePointCount(0, t.length), 1).toDouble()
            val largo = letras * alto * 0.75
            val palabras = ArrayList<Palabra>()
            var desde = 0
            for (w in t.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val i = t.indexOf(w, desde).coerceAtLeast(0)
                val ini = t.codePointCount(0, i).toDouble(); val fin = ini + w.codePointCount(0, w.length)
                desde = i + w.length
                val (x0, y0) = aCapa(x + largo * ini / letras, y + alto)
                val (x1, y1) = aCapa(x + largo * fin / letras, y)
                palabras += Palabra(w, doubleArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))
            }
            if (palabras.isNotEmpty()) renglones += Renglon(palabras)
        }
        val k = m.cajaUtil
        val (x0, y0) = aCapa(k[0].toDouble(), k[3].toDouble())
        val (x1, y1) = aCapa(k[2].toDouble(), k[1].toDouble())
        val caja = doubleArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
        val ancho = caja[2] - caja[0]; val alto = caja[3] - caja[1]
        val letra = max(max(altoTipico(renglones) * 2.0, max(alto, ancho) / 90.0), 1e-3)
        val pagina = Pagina(0, caja, renglones, letra, max(ancho * 0.5, letra * 20))
        return Leido(Formato.PLANO, listOf(pagina), Destino.Plano(c, id, capa, hoja, encaje, m), 0)
    }

    private fun leerLibro(c: DocumentosDelAsistente.Candidato, args: JsonObject): Leido {
        val libro = com.forge.pixpin.excel.LibroExcel.abrir(c.ruta.path) ?: throw IllegalStateException("no se pudo abrir el libro")
        try {
            val i = texto(args, "hoja")?.let { n ->
                libro.libro.hojas.indexOfFirst { Acciones.normalizar(it.nombre) == Acciones.normalizar(n) }.takeIf { it >= 0 }
                    ?: throw IllegalStateException("el libro no tiene una hoja «$n»")
            } ?: 0
            val hoja = libro.hoja(i)
            val geo = esperar { hoja.fallo?.let { throw IllegalStateException("no se pudo abrir la hoja: $it") }; hoja.geo }
                ?: throw IllegalStateException("la hoja tarda demasiado en abrirse")
            val rango = texto(args, "celdas")?.let { Anotacion.Ancla.rango(it) }
            val hastaFila = rango?.let { min(it.second.first + 1, MAX_FILAS * 4) } ?: MAX_FILAS
            val ultima = min(hastaFila, max(geo.ultimaFila, 0))
            val trozo = com.forge.pixpin.excel.LibroExcel.FILAS_POR_TROZO
            for (k in 0..(ultima / trozo)) { hoja.pedir(k); esperar { hoja.trozo(k) } }
            val renglones = ArrayList<Renglon>()
            var maxF = 0; var maxC = 0
            filas@ for (f in 0..ultima) {
                val fila = hoja.fila(f) ?: continue
                if (fila.oculta) continue
                for (celda in fila.celdas) {
                    if (rango != null && !(f in rango.first.first..rango.second.first && celda.col in rango.first.second..rango.second.second)) continue
                    val t = celda.texto.takeIf { it.isNotBlank() } ?: continue
                    val r = geo.combinadaEn(f, celda.col) ?: com.forge.pixpin.excel.Pxhj.Rango(f, celda.col, f, celda.col)
                    val x0 = geo.x(r.c0).toDouble(); val y0 = geo.y(r.f0).toDouble()
                    val x1 = geo.x(r.c1 + 1).toDouble(); val y1 = geo.y(r.f1 + 1).toDouble()
                    val total = max(t.codePointCount(0, t.length), 1).toDouble()
                    val palabras = ArrayList<Palabra>()
                    var pos = 0.0
                    for (w in t.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                        val n = w.codePointCount(0, w.length).toDouble()
                        val a = x0 + (x1 - x0) * pos / total; val b = x0 + (x1 - x0) * min((pos + n) / total, 1.0)
                        palabras += Palabra(w, doubleArrayOf(a, y0, max(b, a + 1.0), y1))
                        pos += n + 1
                    }
                    renglones += Renglon(palabras, "${nombreDeColumna(celda.col)}${f + 1}")
                    maxF = max(maxF, r.f1); maxC = max(maxC, r.c1)
                    if (renglones.size >= MAX_CELDAS) break@filas
                }
            }
            // Las notas, a la derecha de TODO lo usado de la hoja: si no, taparían columnas con datos.
            val usado = max(geo.anchoUsado.toLong(), geo.x(maxC + 1)).toDouble()
            val caja = doubleArrayOf(0.0, 0.0, usado, geo.y(maxF + 1).toDouble())
            val letra = 12.0
            val pagina = Pagina(0, caja, renglones, letra, letra * 22, soloDerecha = true)
            val uid = c.mensaje.uid?.takeIf { it.isNotBlank() } ?: AnotacionesDelAdjunto.uidDe(contexto.filesDir, c.ruta.path)
            val id = uid?.let { AnotacionesDelAdjunto.deHojaDelLibro(it, i) } ?: "xlsx-${c.ruta.path.hashCode().toUInt().toString(16)}-h$i"
            return Leido(Formato.LIBRO, listOf(pagina), Destino.Libro(id), 0)
        } finally {
            libro.cerrar()
        }
    }

    /** Espera (como mucho 15 s) a que [que] dé algo: el libro se lee en su hilo. */
    private fun <T> esperar(que: () -> T?): T? {
        val hasta = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < hasta) {
            que()?.let { return it }
            if (parar.get()) throw IllegalStateException("el usuario lo paro")
            Thread.sleep(20)
        }
        return null
    }

    // ---------------------------------------------------------------------- escribir

    /** **Añade [nuevas] a la capa de la página [numero].** */
    fun anadir(d: Destino, numero: Int, nuevas: List<Element>) {
        when (d) {
            is Destino.Hojas -> docs.escribirEnLaHoja(d.c, numero, nuevas)
            is Destino.Foto, is Destino.Libro -> {
                val id = if (d is Destino.Foto) d.id else (d as Destino.Libro).id
                val escena = ExcalidrawStore.cargar(ExcalidrawStore.rutaDe(contexto, id)) ?: Scene()
                ExcalidrawStore.guardar(contexto, id, escena.copy(elements = escena.elements + nuevas)) ?: throw IllegalStateException("no se pudo guardar lo anotado")
            }
            is Destino.Plano -> {
                val escena = ExcalidrawStore.cargar(d.ruta.path) ?: Scene()
                docs.guardarCapaDelPlano(d.id, d.ruta, d.hoja, d.encaje, d.m, escena.copy(elements = escena.elements + nuevas))
            }
        }
    }

    /** Abre el espacio para anotar de un lado de un PDF o un Word (lo verá al abrir el lector). */
    private fun abrirEspacio(c: DocumentosDelAsistente.Candidato, lado: Int, cuantas: Int) {
        val uid = c.mensaje.uid?.takeIf { it.isNotBlank() } ?: return
        val antes = docs.espaciosDe(uid)
        if (antes and lado != 0) return
        // La tinta de antes del marco no se corre: se le apunta el marco con los espacios de ahora.
        for (i in 0 until cuantas) runCatching {
            com.forge.pixpin.pdf.tintaDeLaHoja(contexto, AnotacionesDelAdjunto.dePagina(uid, i), c.ruta.path, i, antes, suelta = true, migrar = true)
        }
        AnotacionesDelAdjunto.escribir(AnotacionesDelAdjunto.espacios(contexto.filesDir, AnotacionesDelAdjunto.delPdf(uid)), (antes or lado).toString())
    }

    // ----------------------------------------------------------------------- el turno

    class Hecho(var resumen: String = "", var puestas: Int = 0, var grupo: String = "", val paginas: MutableList<Int> = ArrayList(), var nada: String? = null, var conOcr: Int = 0)

    /** **Anota**: lee, pregunta al [motor] y pone cada anotación en la capa según llega. */
    fun anotarEn(c: DocumentosDelAsistente.Candidato, args: JsonObject, motor: Motor): Hecho {
        val leido = leer(c, args)
        if (leido.paginas.all { it.vacia() }) throw IllegalStateException(
            if (leido.formato == Formato.FOTO) "en esa foto no se lee texto (ni con el OCR): no hay nada que anotar" else "esa pagina no tiene texto que anotar")
        val anotador = Anotacion.Anotador(leido.paginas, System.currentTimeMillis().toString())
        val hecho = Hecho(grupo = anotador.grupo(), conOcr = leido.conOcr)
        var texto = Anotacion.mensaje(pedidoDe(args), leido.paginas, Anotacion.MAX_LETRAS)
        // Un Excel: las cuentas comprobadas aquí (PC 16-oct), para que no marque errores que no son.
        if (leido.formato == Formato.LIBRO) runCatching {
            val filas = leido.paginas[0].renglones.groupBy { r -> r.etiqueta?.let { Anotacion.Ancla.celda(it)?.first } ?: -1 }
            val tabla = filas.toSortedMap().values.map { fila ->
                val v = ArrayList<String>()
                for (r in fila.sortedBy { Anotacion.Ancla.celda(it.etiqueta ?: "A1")?.second ?: 0 }) {
                    val col = Anotacion.Ancla.celda(r.etiqueta ?: "A1")?.second ?: 0
                    while (v.size < col) v += ""
                    v += r.texto()
                }
                v
            }
            com.forge.pixpin.ia.RevisionTabla.paraElModelo(tabla)?.let {
                texto += "\n\n$it Si piden errores de calculo, marca EXACTAMENTE estos (anclados en la celda con el valor que esta mal, con su correccion) y ninguno mas de calculo."
            }
        }
        val foto = if (leido.formato == Formato.FOTO && motor.veImagenes) FotoParaElModelo.deArchivo(c.ruta, FotoParaElModelo.LADO_PARA_LEER)?.let { it to "image/jpeg" } else null
        val usuario = if (foto != null) MensajeIa.usuarioConImagen(
            "$texto\n\n(La imagen adjunta es la hoja original. El texto de arriba sale del OCR y puede traer letras mal leidas " +
                "(simbolos, grados, subindices): entiende la hoja por la imagen, pero cita el texto TAL COMO lo leyo el OCR para anclar.)",
            foto.first, foto.second
        ) else MensajeIa.usuario(texto)
        val peticion = Peticion(listOf(MensajeIa.sistema(Anotacion.instrucciones(leido.formato == Formato.LIBRO)), usuario), maxTokens = 3000, temperatura = 0.3)
        val flujo = Anotacion.Flujo()
        var fallo: String? = null
        var espacios = 0
        var trozos = 0
        fun poner(v: JsonObject) {
            val p = when (val l = Anotacion.Leida.de(v)) {
                is Anotacion.Leida.Anotacion -> l.p
                is Anotacion.Leida.Nada -> { hecho.nada = l.motivo; return }
                is Anotacion.Leida.Mal -> return
            }
            val puesta = anotador.poner(p).getOrNull() ?: return
            val numero = anotador.paginas[puesta.en].numero
            try { anadir(leido.destino, numero, puesta.elementos) } catch (e: Exception) { if (fallo == null) fallo = e.message ?: "no se pudo guardar"; return }
            if (numero !in hecho.paginas) hecho.paginas += numero
            if (puesta.fuera && leido.destino is Destino.Hojas) {
                val lado = if (puesta.lado == Anotacion.Lado.IZQUIERDA) 1 else 2
                if (espacios and lado == 0) {
                    espacios = espacios or lado
                    abrirEspacio(leido.destino.c, lado, leido.destino.cuantas)
                }
            }
            hecho.puestas = anotador.cuantas()
        }
        val respuesta = motor.completar(peticion) { t ->
            if (t is Trozo.Texto) { trozos++; flujo.empujar(t.texto).forEach(::poner) }
            if (parar.get()) Seguir.PARAR else Seguir.SI
        }
        if (trozos == 0 && respuesta.texto.isNotEmpty()) flujo.empujar(respuesta.texto).forEach(::poner)
        fallo?.let { if (anotador.cuantas() == 0) throw IllegalStateException(it) }
        hecho.resumen = anotador.resumen()
        hecho.puestas = anotador.cuantas()
        return hecho
    }

    /** **La herramienta desde el ejecutor del chat**: elige, anota y abre el visor donde quedó. */
    fun hacer(args: JsonObject, motor: Motor?, esNube: Boolean): String {
        if (motor == null || !esNube) throw IllegalStateException(SOLO_NUBE)
        val c = docs.elegir(texto(args, "archivo"), DocumentosDelAsistente.Sirve.ANOTABLE)
        val formato = formatoDe(c.nombre)
        val h = anotarEn(c, args, motor)
        if (h.puestas == 0) return h.nada?.let { "no anote nada en «${c.nombre}»: $it" } ?: "no pude anclar ninguna anotacion en «${c.nombre}» (${h.resumen})"
        val primera = h.paginas.minOrNull()
        when (formato) {
            Formato.PDF, Formato.OFFICE, Formato.PLANO -> docs.abrirEn(c, primera, null)
            Formato.FOTO -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { com.forge.pixpin.motor.DrawEditorActivity.abrir(contexto, c.mensaje.referencia ?: "foto-${c.mensaje.id}", ExcalidrawStore.rutaDe(contexto, c.mensaje.referencia ?: "foto-${c.mensaje.id}"), null) }
            }
            Formato.LIBRO -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { com.forge.pixpin.excel.ExcelActivity.abrir(contexto, c.ruta.path, c.nombre) }
            }
            null -> {}
        }
        val paginas = if (formato == Formato.PDF || formato == Formato.OFFICE) ", pagina(s) ${h.paginas.sorted().joinToString(", ") { (it + 1).toString() }}" else ""
        return "hecho: ${h.resumen} en «${c.nombre}»$paginas; ya esta abierto ahi, en la capa de anotaciones (viaja al PC al sincronizar)"
    }
}
