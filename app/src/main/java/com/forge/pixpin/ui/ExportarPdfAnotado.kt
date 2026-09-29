package com.forge.pixpin.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import com.forge.pixpin.motor.DocumentoAnotado
import com.forge.pixpin.motor.DrawSvg
import com.forge.pixpin.motor.ExportarHtml
import com.forge.pixpin.motor.Lectura
import com.forge.pixpin.motor.PdfDoc
import com.forge.pixpin.motor.Scene
import com.forge.pixpin.pin.ImageStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * **Un PDF anotado en el lector, como página web en la que se sigue anotando** (21-sep-2026).
 *
 * Lo pidió el usuario a la vez que los márgenes del lector: «también se puede exportar como html
 * de la misma forma y se pueda seguir anotando». Es el mismo documento web que un Word anotado
 * ([ExportarDocumentoAnotado]): las hojas en una columna, una debajo de otra, con su margen de
 * [Lectura.MARGEN_DEL_PDF] a cada lado, lo anotado encima de cada una —también lo de los
 * márgenes— y la barra de lápiz, resaltador, borrador, deshacer y guardar de toda página web.
 *
 * Si el PDF se deja leer como líneas, va **una página por hoja**, en líneas, con lo anotado
 * encima: pesa poco y el texto sigue nítido a cualquier aumento ([porHojas], 29-sep-2026). Si no
 * —un escaneo—, las hojas van como fotografía en una columna: cuantas más hojas, menos puntos por
 * hoja, para que un documento largo no se haga de decenas de megas.
 */
object ExportarPdfAnotado {
    /** Lo que mide la hoja en la página web, en píxeles CSS. El dibujo la mide en [PdfDoc.PAGE_WIDTH]. */
    private const val COLUMNA = 800
    private const val ENTRE_HOJAS = 8
    private const val PAPEL = "#f3f3f0"

    private const val ESTILO =
        ".doc{line-height:0}.doc img{display:block;width:100%;height:auto;margin:0 0 ${ENTRE_HOJAS}px;background:#fff}" +
            // **El texto de la hoja, encima y transparente** (23-sep-2026, pedido por el usuario: que el
            // «Leer en voz alta» de Edge lo lea). Cada línea en su sitio, como la capa de texto de los
            // visores de PDF de los navegadores: no se ve, pero está —se lee en alto, se selecciona y
            // se copia—. Ver [capaDeTexto].
            ".hoja-pdf{position:relative}.texto-pdf{position:absolute;left:0;top:0;right:0;bottom:0;line-height:1;color:transparent}" +
            ".texto-pdf span{position:absolute;white-space:pre;cursor:text}.texto-pdf ::selection{background:rgba(0,90,255,.25);color:transparent}"

    /**
     * **El PDF, con lo anotado o limpio** (29-sep-2026). Con el interruptor puesto —como viene—, el
     * PDF de siempre más los márgenes del lector, la tinta como vectores y los marcadores en el
     * índice ([com.forge.pixpin.motor.PdfConAnotaciones]); quitado, el PDF limpio, tal cual.
     *
     * [base] es el documento **sin nada anotado dentro**: en un proyecto, su copia limpia.
     */
    fun formatoPdf(
        c: Context, base: String, titulo: String, escenaDe: (Int) -> Scene?,
        espacios: () -> Int, marcas: () -> List<com.forge.pixpin.motor.Marca>
    ): Compartible.Formato {
        val interruptor = Compartible.Interruptor("Con anotaciones", "Tinta, márgenes y marcadores encima del PDF")
        return Compartible.Formato(
            "pdf-anotado", Icons.Filled.PictureAsPdf, "PDF", Compartible.NINGUNA,
            interruptor = interruptor,
            generar = { hacerPdf(c, base, titulo, escenaDe, espacios(), marcas(), interruptor.puesto) }
        )
    }

    private fun hacerPdf(
        c: Context, base: String, titulo: String, escenaDe: (Int) -> Scene?,
        e: Int, m: List<com.forge.pixpin.motor.Marca>, conAnotaciones: Boolean
    ): Compartible.Salida? {
        val limpio = ("$titulo.pdf").replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80)
        val destino = File(File(c.cacheDir, "share").apply { mkdirs() }, limpio)
        val original = File(base).readBytes()
        if (!conAnotaciones) {
            destino.writeBytes(original)
            return Compartible.Salida(destino, "application/pdf", "El PDF limpio, sin nada anotado")
        }
        val hecho = com.forge.pixpin.motor.PdfConAnotaciones.hacer(
            c.applicationContext, original, escenaDe,
            izquierda = e and 1 != 0, derecha = e and 2 != 0,
            marcadores = com.forge.pixpin.motor.PdfConAnotaciones.marcadoresDe(m),
            hojaPintada = { i -> PdfDoc.render(base, i, PdfDoc.PAGE_WIDTH) }
        ) ?: return null
        destino.writeBytes(hecho)
        val resumen = buildList {
            add("Editable, con lo anotado encima")
            if (e != 0) add("márgenes")
            if (m.isNotEmpty()) add("${m.size} marcador" + if (m.size == 1) "" else "es")
        }.joinToString(" · ")
        return Compartible.Salida(destino, "application/pdf", resumen)
    }

    /**
     * **Lo anotado de un PDF del chat**, sin abrirlo: su tinta hoja por hoja, sus márgenes y sus
     * marcadores, buscados como los busca el lector (por el código del mensaje, y si no, por la ruta).
     */
    class DelChat(private val c: Context, private val ruta: String) {
        private val uid = com.forge.pixpin.sincro.AnotacionesDelAdjunto.uidDe(c.filesDir, ruta)
        private val base = uid?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.delPdf(it) }
        private val huella = ruta.hashCode().toUInt().toString(16)

        fun escena(i: Int): Scene? {
            val nombres = listOfNotNull(uid?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.dePagina(it, i) }, "pdf-$huella-p$i")
            return nombres.firstNotNullOfOrNull { n ->
                com.forge.pixpin.motor.ExcalidrawStore.rutaDe(c, n).takeIf { File(it).exists() }?.let { com.forge.pixpin.motor.ExcalidrawStore.cargar(it) }
            }
        }

        fun espacios(): Int =
            base?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(com.forge.pixpin.sincro.AnotacionesDelAdjunto.espacios(c.filesDir, it)) }?.trim()?.toIntOrNull()
                ?: c.getSharedPreferences("lectura", Context.MODE_PRIVATE).getInt("pdf:$ruta:espacios", 0)

        fun marcas(): List<com.forge.pixpin.motor.Marca> = com.forge.pixpin.motor.Marcas.deTexto(
            base?.let { com.forge.pixpin.sincro.AnotacionesDelAdjunto.leer(com.forge.pixpin.sincro.AnotacionesDelAdjunto.marcas(c.filesDir, it)) }
                ?: c.getSharedPreferences("marcas", Context.MODE_PRIVATE).getString("pdf:$ruta", null)
        )
    }

    fun formato(c: Context, ruta: String, titulo: String, paginas: Int, escenaDe: (Int) -> Scene?) =
        Compartible.Formato(
            "web-pdf-anotado", Icons.Filled.Language, "Página web (hojas)", Compartible.NINGUNA,
            generar = {
                val funciones = (c.applicationContext as? com.forge.pixpin.PixPinApp)?.settings?.settings?.first()?.funcionesWeb
                hacer(c.applicationContext, ruta, titulo, paginas, escenaDe, funciones)?.let {
                    Compartible.Salida(it, ExportarHtml.MIME_TYPE, "Las hojas con lo anotado, y se sigue anotando")
                }
            }
        )

    fun hacer(
        c: Context, ruta: String, titulo: String, paginas: Int, escenaDe: (Int) -> Scene?, funciones: Set<String>? = null
    ): File? = runCatching {
        if (paginas <= 0) return null
        val ancho = when { paginas <= 10 -> 1600; paginas <= 40 -> 1200; else -> 960 }
        val margen = (COLUMNA * Lectura.MARGEN_DEL_PDF).toInt()
        val k = COLUMNA.toDouble() / PdfDoc.PAGE_WIDTH
        val tops = ArrayList<Double>(paginas)
        val piezas = ArrayList<DocumentoAnotado.Pieza>()
        var y = 0.0
        val cuerpo = StringBuilder()
        // El PDF leído una vez, para sacar el texto de cada hoja; y su idioma, para la voz del navegador.
        val leido = runCatching { com.forge.pixpin.motor.leerPdf(File(ruta).readBytes()) }.getOrNull()?.takeIf { !it.cifrado }
        val textos = (0 until paginas).map { i -> leido?.let { runCatching { com.forge.pixpin.motor.PlanoDePdf.de(it, i) }.getOrNull() } }
        val idioma = com.forge.pixpin.motor.VozAlta.idiomaDelTexto(
            textos.asSequence().filterNotNull().flatMap { it.textos.asSequence() }.take(800).joinToString(" ") { it.texto },
            java.util.Locale.getDefault().language.ifBlank { "es" }
        )
        // **Si las hojas se dejan leer como líneas, van como líneas** (29-sep-2026). Ver [porHojas].
        val planos = textos.map { p -> p?.takeIf { it.valeLaPena && it.sinEntender == 0 && it.ancho > 0 && it.alto > 0 } }
        if (planos.any { it != null }) {
            porHojas(c, ruta, titulo, paginas, escenaDe, funciones, planos)?.let { return it }
        }
        for (i in 0 until paginas) {
            val foto = PdfDoc.render(ruta, i, ancho) ?: continue
            val alto = COLUMNA.toDouble() * foto.height / foto.width
            cuerpo.append("<div class=\"hoja-pdf\"><img alt=\"Hoja ").append(i + 1).append("\" width=\"").append(COLUMNA)
                .append("\" height=\"").append(Math.round(alto)).append("\" src=\"").append(enJpeg(foto)).append("\">")
            textos[i]?.let { cuerpo.append(capaDeTexto(it, idioma)) }
            cuerpo.append("</div>")
            foto.recycle()
            val ancla = tops.size
            tops += y
            // Lo anotado de la hoja, con su cero en la esquina de la hoja: el margen izquierdo son equis negativas.
            escenaDe(i)?.let { e ->
                val imagen: (String) -> Bitmap? = { f -> e.files[f]?.path?.let { ImageStore.load(it) } }
                val svg = DrawSvg.aTexto(c, e.copy(backgroundColor = "#ffffff"), imagen, papelAparte = true) ?: return@let
                val caja = DocumentoAnotado.cajaDe(svg) ?: return@let
                piezas += DocumentoAnotado.Pieza(svg, margen + caja[0] * k, y + caja[1] * k, caja[2] * k, caja[3] * k, ancla)
            }
            // `height:auto` con el ancho al cien por cien da este mismo alto, sin redondear.
            y += alto + ENTRE_HOJAS
        }
        if (tops.isEmpty()) return null
        val hoja = ExportarHtml.HojaWeb.Documento(
            titulo, ESTILO, cuerpo.toString(), DocumentoAnotado.capaDe(piezas, emptyList(), COLUMNA, margen, "pdf"),
            COLUMNA, margen, tops, 16.0, PAPEL
        )
        val hecha = ExportarHtml.paginas(listOf(hoja), titulo, "$titulo (anotado)", ExportarHtml.Opciones.de(funciones))
        val limpio = ("$titulo (anotado).html").replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80)
        File(File(c.cacheDir, "share").apply { mkdirs() }, limpio).also { it.writeText(hecha) }
    }.getOrNull()

    /**
     * **Una página web por hoja, con la hoja en líneas y lo anotado encima** (29-sep-2026).
     *
     * El usuario: «cuando lo comparto como HTML pesa mucho; aplica la técnica del canvas cuando lo
     * separa por hojas: solo se envía la capa de anotación y sobre eso las líneas, y el texto sigue
     * nítido al hacer zoom». Es lo que hace la página web de un proyecto con su PDF
     * ([com.forge.pixpin.motor.ExportarProyectoWeb]): la hoja viaja como geometría ([com.forge.pixpin.motor.PlanoWeb])
     * y la pinta el visor en su lienzo, a cualquier aumento; lo anotado va en el SVG de encima. Ninguna
     * foto, salvo en las hojas que no se dejan leer (un escaneo): esas van en WebP, como en el proyecto.
     *
     * Lo que se pierde frente a la columna de fotos: el texto invisible para «Leer en voz alta» y
     * el ir hoja tras hoja deslizando; se pasa de hoja con el menú y las flechas del documento.
     */
    private fun porHojas(
        c: Context, ruta: String, titulo: String, paginas: Int, escenaDe: (Int) -> Scene?,
        funciones: Set<String>?, planos: List<com.forge.pixpin.motor.PlanoDePdf.Plano?>
    ): File? {
        val hojas = ArrayList<ExportarHtml.HojaWeb>(paginas)
        for (i in 0 until paginas) {
            val leido = planos.getOrNull(i)
            val plano = leido?.let { runCatching { com.forge.pixpin.motor.PlanoWeb.aJson(it) }.getOrNull() }
            val escena = (escenaDe(i) ?: Scene()).copy(backgroundColor = "#ffffff")
            // **El papel manda en el encuadre**: mide PAGE_WIDTH, como la hoja sobre la que se anotó.
            // Con la hoja en líneas no viaja, así que solo hacen falta sus medidas —salvo que una
            // lupa o un mosaico tengan que coger sus píxeles—; si no, la hoja pintada.
            val sinPixeles = plano != null && escena.elements.none { !it.isDeleted && (it.type == com.forge.pixpin.motor.ElementType.LUPA || it.type == com.forge.pixpin.motor.ElementType.MOSAIC) }
            val papel = (if (sinPixeles) null else PdfDoc.render(ruta, i, PdfDoc.PAGE_WIDTH))
                ?: leido?.let { Bitmap.createBitmap(PdfDoc.PAGE_WIDTH, Math.round(PdfDoc.PAGE_WIDTH * it.alto / it.ancho).toInt().coerceAtLeast(1), Bitmap.Config.ALPHA_8) }
                ?: continue
            val imagen: (String) -> Bitmap? = { f -> escena.files[f]?.path?.let { ImageStore.load(it) } }
            val svg = DrawSvg.aTexto(
                c, escena, imagen, papel,
                papelFino = if (plano == null) papel else null,
                papelAparte = plano != null
            )
            papel.recycle()
            if (svg != null) hojas += ExportarHtml.HojaWeb.Dibujo("Hoja ${i + 1}", svg, "#ffffff", plano)
        }
        if (hojas.isEmpty()) return null
        val hecha = ExportarHtml.paginas(hojas, titulo, "$titulo (anotado)", ExportarHtml.Opciones.de(funciones))
        val limpio = ("$titulo (anotado).html").replace(Regex("""[^\p{L}\p{N} ()._-]"""), "_").takeLast(80)
        return File(File(c.cacheDir, "share").apply { mkdirs() }, limpio).also { it.writeText(hecha) }
    }

    /**
     * **Las líneas de la hoja**, cada una en su sitio sobre la foto, en píxeles de la columna. Las
     * medidas del PDF van en puntos; la hoja mide [COLUMNA] de ancho en la página.
     */
    private fun capaDeTexto(plano: com.forge.pixpin.motor.PlanoDePdf.Plano, idioma: String): String {
        val lineas = com.forge.pixpin.motor.PdfAHtml.lineas(com.forge.pixpin.motor.PdfAHtml.trozosDe(plano.textos))
        if (lineas.isEmpty() || plano.ancho <= 0) return ""
        val k = COLUMNA / plano.ancho
        val sb = StringBuilder("<div class=\"texto-pdf\" lang=\"").append(idioma).append("\">")
        for (l in lineas) {
            val tam = l.alto * k
            // La línea base menos lo que sube la letra: la caja de la línea empieza un poco más arriba.
            sb.append("<span style=\"left:").append(num(l.x0 * k)).append("px;top:").append(num(l.y * k - tam * 0.86))
                .append("px;font-size:").append(num(tam)).append("px\">")
                .append(l.texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                .append("</span> ")
        }
        return sb.append("</div>").toString()
    }

    private fun num(v: Double) = (Math.round(v * 10) / 10.0).toString()

    private fun enJpeg(foto: Bitmap): String {
        val salida = ByteArrayOutputStream()
        foto.compress(Bitmap.CompressFormat.JPEG, 80, salida)
        return "data:image/jpeg;base64," + Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
    }
}
