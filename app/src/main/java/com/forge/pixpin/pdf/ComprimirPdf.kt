package com.forge.pixpin.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.forge.pixpin.motor.PdfEscritura
import com.forge.pixpin.motor.PdfValor
import com.forge.pixpin.motor.entero
import com.forge.pixpin.motor.leerPdf
import com.forge.pixpin.motor.nombre
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * **Un PDF más ligero sin que se note.**
 *
 * Lo pidió el usuario el 14-sep-2026: un PDF de cuatro páginas escaneadas pesaba 10 MB, y todo lo
 * que salía de él (la página web, los envíos) arrastraba ese peso. Lo que hacen los compresores
 * como iLovePDF —que por dentro son Ghostscript con `-dPDFSETTINGS=/printer` o `/ebook`— es casi
 * todo esto: **las fotos de dentro se bajan a la resolución a la que de verdad se van a ver o
 * imprimir** y se vuelven a guardar en JPEG con una calidad razonable. Un escaneo a 600 ppp con
 * JPEG al 95 % son varios megas por página; a 300 ppp y al 82 % se ve igual, también impreso.
 *
 * Lo demás no se toca: el texto, los vectores y las fuentes son lo que hace nítido un plano. Y
 * **nunca a peor**: una foto que no gana al menos una cuarta parte se deja como estaba, y si el
 * archivo entero no baja un 15 % o no se puede volver a leer, se queda el original.
 *
 * ## Lo que se hace, por orden (14-sep-2026)
 *
 * La primera versión solo tocaba las fotos que ya eran JPEG y en RGB o gris, y con eso **nueve de
 * cada diez documentos se quedaban igual** mientras un compresor de internet les quitaba el 70 %
 * (usuario). Los tres motivos eran estos, y los tres están arreglados:
 *
 * 1. **El documento no se rehace por sus páginas, se reescribe entero**
 *    ([com.forge.pixpin.motor.PdfEscritura.completo]). Antes, un PDF con marcadores, con un
 *    formulario o con capas se dejaba sin tocar —rehacerlo los perdería—, y marcadores los tiene
 *    casi todo lo que sale de un programa de oficina. De paso, reescribir tira por sí solo los
 *    paquetes de objetos y los índices viejos.
 * 2. **Las fotos se aceptan como vengan**: en JPEG o en crudo, con Flate, LZW o RunLength encima,
 *    con predictor, y en gris, RGB, CMYK, con perfil ICC o **con paleta** —que es lo normal en un
 *    escaneo de pocos colores y se rechazaba entero—. Ver [fotoMasLigera].
 * 3. **La resolución objetivo baja de 300 a 200 ppp** ([PPP]): a 300, un escaneo hecho a 300 no
 *    tenía nada que quitar. El peso va con el cuadrado, así que eso solo son cuatro veces menos
 *    píxeles.
 *
 * Y lo que no es una foto también baja: cada flujo se vuelve a apretar con el Deflate más lento
 * —sin pérdida— y lo que venía en claro se comprime, que es lo que hacen `qpdf` y el optimizador
 * de OCRmyPDF antes de tocar una imagen.
 *
 * Lo bilevel (una página en blanco y negro a 1 bit, un CCITT o un JBIG2) no se toca: ya pesa poco
 * y pasarlo a JPEG lo engordaría y lo dejaría sucio.
 */
object ComprimirPdf {

    /**
     * **Puntos por pulgada a los que quedan las fotos.**
     *
     * Estaba en 300 —los de `-dPDFSETTINGS=/printer` de Ghostscript— y con eso un escaneo hecho a
     * 300 ppp no bajaba ni un byte: no había nada que quitar. Los compresores que el usuario comparó
     * (iLovePDF y compañía) trabajan alrededor de 150 ppp, que es lo que hace falta para leer e
     * imprimir una hoja, y de ahí su «70 % menos». 200 es el punto medio: cuatro veces menos
     * píxeles que 300 —el peso va con el cuadrado— y todavía por encima de lo que imprime bien.
     */
    const val PPP = 200.0

    /** La calidad del JPEG. 80 es lo que usan los compresores de web y no se nota a la vista. */
    const val CALIDAD_JPEG = 80

    /** Lo que tiene que bajar una foto para cambiarla: un 20 %. */
    private const val GANANCIA_DE_LA_FOTO = 0.8

    /** Los envoltorios de los que se sabe sacar los píxeles en crudo. */
    private val DESENVOLVIBLES = setOf("FlateDecode", "Fl", "LZWDecode", "LZW", "RunLengthDecode", "RL")

    /** Ninguna imagen de más de esto se toca: no cabría en memoria. */
    private const val TOPE_DE_PIXELES = 80_000_000L

    /**
     * Comprime [archivo] **en su sitio** si merece la pena. Devuelve cuántos bytes se ahorraron
     * (0 si se quedó igual). Trabajo de disco y de CPU: fuera del hilo principal.
     */
    fun enSuSitio(archivo: File): Long = runCatching {
        val original = archivo.readBytes()
        val ligero = comprimir(original) ?: return 0
        if (ligero.size > original.size * 0.85) return 0
        val temporal = File(archivo.parentFile, archivo.name + ".ligero")
        temporal.writeBytes(ligero)
        // Que se pueda abrir de verdad antes de sustituir nada.
        if (com.forge.pixpin.motor.PdfDoc.pageCount(temporal.path) <= 0) { temporal.delete(); return 0 }
        if (!temporal.renameTo(archivo)) { temporal.copyTo(archivo, overwrite = true); temporal.delete() }
        (original.size - ligero.size).toLong()
    }.getOrDefault(0L)

    /** Los bytes comprimidos, o null si no hay nada que ganar o no se puede con garantías. */
    fun comprimir(bytes: ByteArray): ByteArray? {
        val pdf = leerPdf(bytes) ?: return null
        if (pdf.cifrado) return null
        // El tope de píxeles de una foto: la página más grande a [PPP]. Una foto no se ve más
        // grande que la página donde está pegada.
        var mayor = 0.0
        for (i in pdf.paginas().indices) {
            val (an, al) = pdf.tamanoDePagina(i) ?: continue
            mayor = max(mayor, max(an, al))
        }
        // Sin páginas medibles se usa un A4: mejor aligerar con una medida razonable que no hacerlo.
        val lado = ceil((if (mayor > 0) mayor else 842.0) / 72.0 * PPP).toInt()
        // **Lo que no se toca**: las máscaras de las demás imágenes. Son el canal de
        // transparencia, y pasarlas por un JPEG deja halos en los bordes. Se miran antes, porque
        // una máscara no sabe que lo es: lo dice quien la usa.
        val mascaras = HashSet<Int>()
        for (n in pdf.indice.keys) {
            val d = (runCatching { pdf.objeto(n) }.getOrNull() as? PdfValor.Flujo)?.dicc ?: continue
            for (clave in listOf("SMask", "Mask")) {
                (d.entradas[clave] as? PdfValor.Ref)?.let { mascaras += it.numero }
            }
        }
        val ayuda = Ayuda({ pdf.resolver(it) }, { pdf.descomprimir(it) })
        var ganado = 0L
        // **Se reescribe el archivo entero**, no se rehace por páginas: así no se pierden los
        // marcadores, los formularios ni las capas, que es lo que antes obligaba a no tocar el
        // documento. Ver [com.forge.pixpin.motor.PdfEscritura.completo].
        val salida = PdfEscritura.completo(pdf) { numero, valor ->
            val flujo = valor as? PdfValor.Flujo ?: return@completo null
            val nuevo = (if (numero in mascaras) null else fotoMasLigera(flujo, lado, ayuda))
                ?: masApretado(flujo) { ayuda.descomprimir(it) }
            if (nuevo != null) ganado += flujo.datos.size - nuevo.datos.size
            nuevo
        } ?: return null
        // Reescribir también ahorra por su cuenta —los paquetes de objetos y los índices viejos se
        // quedan fuera—, así que lo que manda es el tamaño final y no lo que se ganó por flujo.
        if (salida.size >= bytes.size) return null
        if (ganado <= 0 && salida.size >= bytes.size * 0.95) return null
        // **Y que lo escrito se vuelva a leer con las mismas páginas.** Un archivo que no abre es
        // mucho peor que uno que pesa: quien llama comprueba además que lo abra Android, pero esto
        // vale también donde no hay Android. Ver [enSuSitio].
        val revisado = leerPdf(salida) ?: return null
        if (revisado.paginas().size != pdf.paginas().size) return null
        return salida
    }

    /** Lo que hace falta para leer lo que un flujo trae dentro. Ver [fotoMasLigera]. */
    internal class Ayuda(
        val resolver: (PdfValor?) -> PdfValor? = { it },
        val descomprimir: (PdfValor.Flujo) -> ByteArray? = { null }
    )

    /**
     * **El mismo flujo, apretado más fuerte.** Sin pérdida: se desinfla y se vuelve a inflar con
     * el Deflate más lento, que aprieta mejor que el que usa cualquier generador. Null si no gana
     * lo suficiente para que valga la pena reescribirlo.
     *
     * No se toca lo que no sea Flate a secas: una cadena de filtros o un JPEG no ganan nada aquí,
     * y una foto ya pasó por [fotoMasLigera].
     */
    internal fun masApretado(flujo: PdfValor.Flujo, descomprimir: (PdfValor.Flujo) -> ByteArray?): PdfValor.Flujo? {
        val d = flujo.dicc
        if (flujo.datos.size < 512) return null
        // Las tablas del propio archivo las rehace el escritor: no son datos que copiar.
        val tipo = d.nombre("Type")
        if (tipo == "ObjStm" || tipo == "XRef") return null
        val filtro = d.nombre("Filter")
        // **Sin comprimir**: hay generadores que escriben el contenido en claro. Comprimirlo es
        // gratis y sin pérdida.
        if (filtro == null && !d.entradas.containsKey("Filter")) {
            val apretado = inflarFuerte(flujo.datos)
            if (apretado.size > flujo.datos.size * 0.93) return null
            return PdfValor.Flujo(PdfValor.Dicc(LinkedHashMap(d.entradas).also { it["Filter"] = PdfValor.Nombre("FlateDecode") }), apretado)
        }
        if (filtro != "FlateDecode") return null
        // Con predictor, los datos que salen ya no son los que había que guardar: reescribirlos
        // pediría volver a aplicarlo. Se dejan.
        if (d.entradas.containsKey("DecodeParms")) return null
        val crudo = descomprimir(flujo) ?: return null
        val apretado = inflarFuerte(crudo)
        if (apretado.size > flujo.datos.size * 0.93) return null
        return PdfValor.Flujo(d, apretado)
    }

    private fun inflarFuerte(datos: ByteArray): ByteArray {
        val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        return try {
            d.setInput(datos); d.finish()
            val salida = ByteArrayOutputStream(datos.size / 2 + 64)
            val tanda = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(tanda)
                if (n <= 0) break
                salida.write(tanda, 0, n)
            }
            salida.toByteArray()
        } finally {
            d.end()
        }
    }

    /**
     * **Una foto más pequeña y ligera**, o null si no se puede o no gana bastante.
     *
     * Se acepta **cualquier forma en que un PDF guarde una foto**, que es lo que fallaba: antes
     * solo se tocaba el JPEG de RGB o gris y con eso nueve de cada diez documentos se quedaban
     * igual (usuario, 14-sep-2026). Ahora:
     *
     * - **Ya en JPEG** (`DCTDecode`): se descodifica y se vuelve a guardar más pequeño.
     * - **En crudo** con cualquier envoltorio que se sepa deshacer —`FlateDecode`, `LZWDecode`,
     *   con predictor o sin él— y en cualquiera de los colores corrientes: gris, RGB, CMYK, un
     *   perfil ICC o **una paleta** (`/Indexed`), que es lo que usa medio mundo para los escaneos
     *   de 256 colores y se rechazaba entero.
     * - **A cualquier profundidad** si va por paleta (1, 2, 4 u 8 bits); en color, a 8.
     *
     * Lo que sigue fuera: lo bilevel de verdad (una página en blanco y negro a 1 bit ya pesa poco y
     * en JPEG saldría sucia y más gorda), los recortes por `/Mask`, las tablas `/Decode` al revés y
     * lo que no se sabe deshacer (CCITT, JBIG2, JPEG 2000).
     */
    internal fun fotoMasLigera(flujo: PdfValor.Flujo, lado: Int, ayuda: Ayuda = Ayuda()): PdfValor.Flujo? {
        val d = flujo.dicc
        if (d.nombre("Subtype") != "Image") return null
        if (d.entradas.containsKey("Decode") || d.entradas.containsKey("ImageMask") || d.entradas.containsKey("Mask")) return null
        val filtros = filtrosDe(d)
        val ultimo = filtros.lastOrNull() ?: return null
        val ancho = d.entero("Width") ?: return null
        val alto = d.entero("Height") ?: return null
        if (ancho < 2 || alto < 2 || ancho.toLong() * alto > TOPE_DE_PIXELES) return null
        val bits = d.entero("BitsPerComponent") ?: 8
        val escala = minOf(1.0, lado.toDouble() / max(ancho, alto))
        val nuevoAncho = (ancho * escala).roundToInt().coerceAtLeast(1)
        val nuevoAlto = (alto * escala).roundToInt().coerceAtLeast(1)
        val bmp = if (ultimo == "DCTDecode" || ultimo == "DCT") {
            // Un JPEG con envoltorios encima (ASCII85 y compañía) se desenvuelve primero.
            val datos = if (filtros.size == 1) flujo.datos else ayuda.descomprimir(sinElUltimo(flujo, filtros)) ?: return null
            deJpeg(datos, ancho, alto, nuevoAncho, nuevoAlto) ?: return null
        } else {
            if (ultimo !in DESENVOLVIBLES) return null
            val color = colorDe(d, ayuda) ?: return null
            if (color.canales > 1 && bits != 8) return null
            if (color.paleta == null && bits != 8) return null
            if (bits !in intArrayOf(1, 2, 4, 8)) return null
            val crudo = ayuda.descomprimir(flujo) ?: return null
            deMuestras(crudo, ancho, alto, color, bits, nuevoAncho, nuevoAlto) ?: return null
        }
        val jpeg = ByteArrayOutputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, o); o.toByteArray() }
        bmp.recycle()
        // **Solo si gana de verdad.** Es también lo que evita estropear un dibujo de líneas: en
        // JPEG pesa más que comprimido sin pérdida, así que se rechaza él solo.
        if (jpeg.size > flujo.datos.size * GANANCIA_DE_LA_FOTO) return null
        val entradas = LinkedHashMap(d.entradas)
        entradas["Width"] = PdfValor.Numero(nuevoAncho.toDouble())
        entradas["Height"] = PdfValor.Numero(nuevoAlto.toDouble())
        entradas["Filter"] = PdfValor.Nombre("DCTDecode")
        entradas["BitsPerComponent"] = PdfValor.Numero(8.0)
        entradas.remove("DecodeParms")
        // Android lo guarda siempre en color: lo que entró en gris, en paleta o en CMYK sale en RGB.
        entradas["ColorSpace"] = PdfValor.Nombre("DeviceRGB")
        return PdfValor.Flujo(PdfValor.Dicc(entradas), jpeg)
    }

    private fun filtrosDe(d: PdfValor.Dicc): List<String> = when (val f = d.entradas["Filter"]) {
        is PdfValor.Nombre -> listOf(f.valor)
        is PdfValor.Lista -> f.valores.mapNotNull { (it as? PdfValor.Nombre)?.valor }
        else -> emptyList()
    }

    /** El mismo flujo sin su último filtro: para sacar el JPEG de dentro de sus envoltorios. */
    private fun sinElUltimo(flujo: PdfValor.Flujo, filtros: List<String>): PdfValor.Flujo {
        val entradas = LinkedHashMap(flujo.dicc.entradas)
        val quedan = filtros.dropLast(1)
        entradas["Filter"] = if (quedan.size == 1) PdfValor.Nombre(quedan[0]) else PdfValor.Lista(quedan.map { PdfValor.Nombre(it) })
        return PdfValor.Flujo(PdfValor.Dicc(entradas), flujo.datos)
    }

    /** Cómo están guardados los colores de una imagen en crudo. */
    internal class Color(val canales: Int, val paleta: IntArray? = null, val cmyk: Boolean = false)

    /**
     * El color de la imagen, o null si es de los que no se tocan.
     *
     * Una paleta (`/Indexed`) se lee de verdad —su tabla dice qué color es cada índice— porque es
     * lo más común en los escaneos de pocos colores y se estaba rechazando entero.
     */
    internal fun colorDe(d: PdfValor.Dicc, ayuda: Ayuda): Color? {
        val cs = ayuda.resolver(d.entradas["ColorSpace"]) ?: return null
        if (cs is PdfValor.Nombre) return when (cs.valor) {
            "DeviceGray", "CalGray", "G" -> Color(1)
            "DeviceRGB", "CalRGB", "RGB" -> Color(3)
            "DeviceCMYK", "CMYK" -> Color(4, cmyk = true)
            else -> null
        }
        val l = (cs as? PdfValor.Lista)?.valores ?: return null
        return when ((l.firstOrNull() as? PdfValor.Nombre)?.valor) {
            "ICCBased" -> when ((ayuda.resolver(l.getOrNull(1)) as? PdfValor.Flujo)?.dicc?.entero("N")
                ?: (ayuda.resolver(l.getOrNull(1)) as? PdfValor.Dicc)?.entero("N")) {
                1 -> Color(1); 3 -> Color(3); 4 -> Color(4, cmyk = true); else -> null
            }
            "CalGray" -> Color(1)
            "CalRGB", "Lab" -> Color(3)
            "Indexed", "I" -> paletaDe(l, ayuda)
            "DeviceGray" -> Color(1)
            "DeviceRGB" -> Color(3)
            "DeviceCMYK" -> Color(4, cmyk = true)
            else -> null
        }
    }

    /** La paleta de un `/Indexed`, ya en colores de pantalla. */
    private fun paletaDe(l: List<PdfValor>, ayuda: Ayuda): Color? {
        val base = colorDe(PdfValor.Dicc(mapOf("ColorSpace" to (l.getOrNull(1) ?: return null))), ayuda) ?: return null
        if (base.paleta != null) return null
        val tope = (ayuda.resolver(l.getOrNull(2)) as? PdfValor.Numero)?.valor?.toInt() ?: return null
        if (tope < 0 || tope > 255) return null
        val tabla = when (val t = ayuda.resolver(l.getOrNull(3))) {
            is PdfValor.Cadena -> t.bytes
            is PdfValor.Flujo -> ayuda.descomprimir(t) ?: return null
            else -> return null
        }
        val paleta = IntArray(tope + 1)
        for (i in 0..tope) {
            val o = i * base.canales
            if (o + base.canales > tabla.size) break
            fun c(k: Int) = tabla[o + k].toInt() and 0xFF
            paleta[i] = when {
                base.canales == 1 -> pixel(c(0), c(0), c(0))
                base.cmyk -> deCmyk(c(0), c(1), c(2), c(3))
                else -> pixel(c(0), c(1), c(2))
            }
        }
        return Color(1, paleta = paleta)
    }

    private fun pixel(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun deCmyk(c: Int, m: Int, y: Int, k: Int): Int {
        val nk = 255 - k
        return pixel((255 - c) * nk / 255, (255 - m) * nk / 255, (255 - y) * nk / 255)
    }

    /** El JPEG de dentro, leído a la resolución que se quiere y ni un píxel más. */
    private fun deJpeg(datos: ByteArray, ancho: Int, alto: Int, nuevoAncho: Int, nuevoAlto: Int): Bitmap? {
        val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(datos, 0, datos.size, medida)
        if (medida.outWidth != ancho || medida.outHeight != alto) return null   // CMYK u otra rareza: fuera
        var muestreo = 1
        while (ancho / (muestreo * 2) >= nuevoAncho && alto / (muestreo * 2) >= nuevoAlto) muestreo *= 2
        val bmp = BitmapFactory.decodeByteArray(datos, 0, datos.size, BitmapFactory.Options().apply { inSampleSize = muestreo }) ?: return null
        return aMedida(bmp, nuevoAncho, nuevoAlto)
    }

    /**
     * Los píxeles en crudo del PDF, hechos mapa de bits.
     *
     * Se leen **salteados** hasta lo que se necesita —un escaneo A4 en color a 600 ppp son 100 MB
     * si se lee entero— y luego se ajusta a la medida exacta, que es lo mismo que hace el camino
     * del JPEG con `inSampleSize`. Las filas de un PDF empiezan en byte entero, y con menos de 8
     * bits por muestra eso deja unos bits de relleno al final de cada una: de ahí el cálculo de
     * [fila], que si se hace mal tuerce la imagen en diagonal.
     */
    private fun deMuestras(crudo: ByteArray, ancho: Int, alto: Int, color: Color, bits: Int, nuevoAncho: Int, nuevoAlto: Int): Bitmap? {
        val fila = (ancho * color.canales * bits + 7) / 8
        if (crudo.size < fila.toLong() * alto) return null
        val paso = maxOf(1, minOf(ancho / max(nuevoAncho, 1), alto / max(nuevoAlto, 1)))
        val an = (ancho + paso - 1) / paso
        val al = (alto + paso - 1) / paso
        if (an < 1 || al < 1 || an.toLong() * al > TOPE_DE_PIXELES) return null
        val pixeles = IntArray(an * al)
        val maximo = (1 shl bits) - 1
        var i = 0
        var y = 0
        while (y < alto) {
            val base = y * fila
            var x = 0
            while (x < ancho) {
                val color1 = if (color.paleta != null) {
                    val indice = muestra(crudo, base, x, bits) ?: return null
                    color.paleta.getOrElse(indice) { color.paleta.lastOrNull() ?: 0 }
                } else {
                    val o = base + x * color.canales
                    if (o + color.canales > crudo.size) return null
                    when {
                        color.canales == 1 -> {
                            val v = crudo[o].toInt() and 0xFF
                            pixel(v, v, v)
                        }
                        color.cmyk -> deCmyk(
                            crudo[o].toInt() and 0xFF, crudo[o + 1].toInt() and 0xFF,
                            crudo[o + 2].toInt() and 0xFF, crudo[o + 3].toInt() and 0xFF
                        )
                        else -> pixel(crudo[o].toInt() and 0xFF, crudo[o + 1].toInt() and 0xFF, crudo[o + 2].toInt() and 0xFF)
                    }
                }
                if (i < pixeles.size) pixeles[i++] = color1
                x += paso
            }
            y += paso
        }
        if (maximo <= 0) return null
        val bmp = Bitmap.createBitmap(pixeles, an, al, Bitmap.Config.ARGB_8888) ?: return null
        return aMedida(bmp, nuevoAncho, nuevoAlto)
    }

    /** La muestra número [x] de una fila que empieza en [base], con [bits] bits cada una. */
    private fun muestra(datos: ByteArray, base: Int, x: Int, bits: Int): Int? {
        val desde = x * bits
        val o = base + desde / 8
        if (o >= datos.size) return null
        val b = datos[o].toInt() and 0xFF
        return when (bits) {
            8 -> b
            4 -> if (desde % 8 == 0) b shr 4 else b and 0x0F
            2 -> (b shr (6 - desde % 8)) and 0x03
            1 -> (b shr (7 - desde % 8)) and 0x01
            else -> null
        }
    }

    private fun aMedida(bmp: Bitmap, ancho: Int, alto: Int): Bitmap =
        if (bmp.width == ancho && bmp.height == alto) bmp
        else Bitmap.createScaledBitmap(bmp, ancho, alto, true).also { if (it !== bmp) bmp.recycle() }

    /**
     * **Cuántos píxeles de ancho tiene de verdad una página escaneada**: el de su foto más grande,
     * llevado al ancho de la página. Rasterizarla a más solo inventa píxeles que pesan. Null si la
     * página no trae fotos (entonces manda la resolución de siempre).
     */
    fun anchoNativo(ruta: String, pagina: Int): Int? = runCatching {
        val pdf = leerPdf(File(ruta).readBytes()) ?: return null
        val numero = pdf.paginas().getOrNull(pagina) ?: return null
        val p = pdf.diccDe(PdfValor.Ref(numero, 0)) ?: return null
        val recursos = pdf.diccDe(p.entradas["Resources"]) ?: return null
        val xobjetos = pdf.diccDe(recursos.entradas["XObject"]) ?: return null
        // El ancho de la foto que más ocupa: la del escaneo, no un logotipo suelto.
        xobjetos.entradas.values.mapNotNull { ref ->
            val d = pdf.diccDe(ref) ?: return@mapNotNull null
            if (d.nombre("Subtype") != "Image") null else (d.entero("Width") ?: 0) to (d.entero("Height") ?: 0)
        }.maxByOrNull { (w, h) -> w.toLong() * h }?.first?.takeIf { it > 0 }
    }.getOrNull()
}
