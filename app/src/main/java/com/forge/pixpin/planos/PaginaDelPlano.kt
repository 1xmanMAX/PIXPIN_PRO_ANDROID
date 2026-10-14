package com.forge.pixpin.planos

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * **El plano como página web, por capas** (14-oct-2026; lo del PC del 13-oct, `pixpin_cad::web`).
 * El usuario, allí: «al visor de AutoCAD impleméntale un exportador en formato HTML en el que se
 * considere todo a la hora de exportar».
 *
 * Un solo `.html` que se abre sin internet y enseña el plano como el visor: rayas, rellenos,
 * sombreados con su patrón, círculos y arcos exactos, letras (SHX como trazos, TrueType rellenas)
 * e imágenes, cada cosa con su color y su grosor, **por capas** que se encienden y apagan en la
 * página; encima, las cotas y los marcos del visor y lo anotado con el motor (un SVG del mismo
 * motor que exporta el lienzo). Rueda o pellizco para acercar, arrastrar para mover, «Ver todo»,
 * claro u oscuro con los colores del visor.
 *
 * **Es la misma página que hace el PC**, número a número: los mismos datos en el mismo orden y el
 * mismo guion ([PaginaDelPlanoGuion], copiado de `web.js`). Así, un plano exportado aquí o allí se ve y
 * se maneja igual, y lo que se arregle en uno se trae al otro sin pensar.
 *
 * **Ligera aunque el plano sea grande** (un plano de carreteras son millones de vértices): va lo
 * que ya tiene el modelo del visor ([ModeloCad]), no el DWG, con cada cosa en sus tramos ordenados
 * por tamaño y sitio. La página hace lo mismo que la tarjeta: lo que en pantalla mediría menos de
 * un píxel no se dibuja, de lo demás solo lo que cae en la ventana, y cada tramo se vuelve un
 * `Path2D` la primera vez que se ve. Los números van en binario (palabras de 4 bytes), comprimidos
 * con gzip —el navegador los descomprime solo, `DecompressionStream`— y en base64 dentro de la
 * página. Se escriben **de corrido** al compresor: nunca está entero en memoria sin comprimir.
 *
 * El plano por capas lo saca la lectura nativa en el proceso aparte (`PlanoNativo.capas`, el
 * formato `PXCAPAS1` del PC, ver [leer]); si no se puede, va el modelo del visor como una capa.
 */
object PaginaDelPlano {
    const val MIME_TYPE = "text/html"

    /** El grosor «por defecto» de AutoCAD (`GROSOR_NORMAL` del PC). */
    const val GROSOR_NORMAL = -3

    /** Un trozo del plano: lo de una capa con un grosor (centésimas de mm). */
    class Grupo(val capa: String, val grosor: Int, val encendida: Boolean, val modelo: ModeloCad)

    /** Una imagen puesta en el plano (IMAGE): el archivo que dice y dónde cae (esquina y lados). */
    class ImagenDelPlano(val capa: String, val ruta: String, val esquina: DoubleArray, val u: DoubleArray, val v: DoubleArray)

    class PorCapas(val grupos: List<Grupo>, val imagenes: List<ImagenDelPlano>)

    /** Lo anotado ya como SVG: mide [caja] (`x0, y0, x1, y1`) en unidades de la capa; un punto de la capa cae en el plano en `cero + (x·u, −y·u)`. */
    class Anotado(val svg: String, val caja: DoubleArray, val u: Double, val ceroX: Double, val ceroY: Double)

    /** Una imagen del plano ya leída (PNG o JPEG), con su sitio respecto al origen. */
    class ImagenWeb(val capa: String, val mime: String, val bytes: ByteArray, val esquina: DoubleArray, val u: DoubleArray, val v: DoubleArray)

    /** Los rótulos de la página. */
    class Textos(
        val todo: String = "Ver todo",
        val tema: String = "Claro / oscuro",
        val capas: String = "Capas",
        val grosores: String = "Grosores",
        val anotaciones: String = "Anotaciones",
        val todas: String = "Todas",
        val ninguna: String = "Ninguna",
        val sinSoporte: String = "Este navegador no sabe descomprimir el plano: ábrelo con Chrome, Edge, Firefox o Safari recientes.",
        val pista: String = "Pellizco o rueda: acercar · Arrastrar: mover · Doble toque: ver todo",
    )

    /** Lo que lleva la página además de las capas. Todo **respecto a [origenX], [origenY]** (el origen del modelo del visor). */
    class Opciones(
        val titulo: String,
        val origenX: Double,
        val origenY: Double,
        /** Lo que se ve al abrir (`x0, y0, x1, y1`). */
        val vista: DoubleArray,
        val unidades: Int,
        val cotas: List<List<DoubleArray>> = emptyList(),
        val marcos: List<ImprimirPlano.Marco> = emptyList(),
        val anotado: Anotado? = null,
        val imagenes: List<ImagenWeb> = emptyList(),
        val claro: Boolean = true,
        val textos: Textos = Textos(),
    )

    // ------------------------------------------------------------------ el plano por capas

    private val MAGIA = "PXCAPAS1".toByteArray(Charsets.US_ASCII)

    /** Lee un `.pxcapas` (proyectado en memoria: los modelos son trozos del archivo). Null si no se entiende. */
    fun leer(archivo: File): PorCapas? = runCatching {
        val datos = RandomAccessFile(archivo, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        deBytes(datos)
    }.getOrNull()

    /** `web::de_bytes` del PC: null si está roto o cortado. */
    fun deBytes(b: ByteBuffer): PorCapas? = runCatching {
        val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (d.remaining() < 8) return null
        for (i in 0 until 8) if (d.get() != MAGIA[i]) return null
        fun u32(): Int = d.int
        fun texto(): String {
            val n = u32()
            if (n < 0 || n > d.remaining()) throw IllegalStateException("cortado")
            val bytes = ByteArray(n).also { d.get(it) }
            return String(bytes, Charsets.UTF_8)
        }
        val n = u32()
        if (n < 0 || n > 1_000_000) return null
        val grupos = ArrayList<Grupo>(minOf(n, 4096))
        repeat(n) {
            val capa = texto()
            val grosor = d.short.toInt()
            val encendida = d.get().toInt() != 0
            val largo = u32()
            if (largo < 0 || largo > d.remaining()) return null
            val trozo = d.slice().order(ByteOrder.LITTLE_ENDIAN).also { it.limit(largo) }
            d.position(d.position() + largo)
            grupos += Grupo(capa, grosor, encendida, ModeloCad.deBytes(trozo))
        }
        val ni = u32()
        if (ni < 0 || ni > 1_000_000) return null
        val imagenes = ArrayList<ImagenDelPlano>(minOf(ni, 4096))
        repeat(ni) {
            val capa = texto()
            val ruta = texto()
            val v = DoubleArray(6) { d.double }
            imagenes += ImagenDelPlano(capa, ruta, doubleArrayOf(v[0], v[1]), doubleArrayOf(v[2], v[3]), doubleArrayOf(v[4], v[5]))
        }
        if (d.hasRemaining()) return null
        PorCapas(grupos, imagenes)
    }.getOrNull()

    /** Lo que ocupan todos los [grupos] (`x0, y0, x1, y1`), respecto a [origenX], [origenY]. */
    fun cajaDe(grupos: List<Grupo>, origenX: Double, origenY: Double): DoubleArray {
        val c = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (g in grupos) {
            val u = g.modelo.cajaUtil
            val ox = g.modelo.origenX - origenX; val oy = g.modelo.origenY - origenY
            c[0] = minOf(c[0], u[0] + ox); c[1] = minOf(c[1], u[1] + oy); c[2] = maxOf(c[2], u[2] + ox); c[3] = maxOf(c[3], u[3] + oy)
        }
        return if (c[0] <= c[2] && c[1] <= c[3]) c else doubleArrayOf(0.0, 0.0, 1.0, 1.0)
    }

    /** El modelo del visor entero como una sola capa: si no se pudo leer por capas. */
    fun sinCapas(m: ModeloCad): List<Grupo> = listOf(Grupo("0", GROSOR_NORMAL, true, m))

    /**
     * Las imágenes del plano que se encuentran (su ruta tal cual, junto al plano o por su nombre en
     * su carpeta), en PNG o JPEG; las demás no van. Con su sitio respecto a [origenX], [origenY].
     */
    fun imagenes(plano: File, lista: List<ImagenDelPlano>, origenX: Double, origenY: Double): List<ImagenWeb> {
        val carpeta = plano.parentFile ?: File(".")
        return lista.mapNotNull { i ->
            val r = File(i.ruta.replace('\\', '/'))
            val ruta = listOf(r, File(carpeta, r.path), File(carpeta, r.name)).firstOrNull { it.isFile } ?: return@mapNotNull null
            val mime = when (ruta.extension.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                else -> return@mapNotNull null
            }
            val bytes = runCatching { ruta.readBytes() }.getOrNull() ?: return@mapNotNull null
            ImagenWeb(i.capa, mime, bytes, doubleArrayOf(i.esquina[0] - origenX, i.esquina[1] - origenY), i.u, i.v)
        }
    }

    // ------------------------------------------------------------------ los datos

    /**
     * Las palabras de 4 bytes que van a la página, **de corrido al compresor**: solo se lleva la
     * cuenta de dónde va cada cosa ([sitio]), que es lo que apunta el JSON.
     */
    private class Datos(destino: OutputStream) {
        private val salida = destino
        private val buf = ByteArray(1 shl 16)
        private var pos = 0
        var sitio = 0
            private set
        val colores = ArrayList<Int>()
        private val colorDe = HashMap<Int, Int>()

        fun u(x: Int) {
            if (pos + 4 > buf.size) vaciar()
            buf[pos] = x.toByte(); buf[pos + 1] = (x ushr 8).toByte(); buf[pos + 2] = (x ushr 16).toByte(); buf[pos + 3] = (x ushr 24).toByte()
            pos += 4
            sitio++
        }

        fun f(x: Float) = u(java.lang.Float.floatToRawIntBits(x))

        /** El número del color en la lista de la página (cada color, una vez). */
        fun color(c: Int): Int = colorDe.getOrPut(c) { colores.add(c); colores.size - 1 }

        fun vaciar() { if (pos > 0) { salida.write(buf, 0, pos); pos = 0 } }
    }

    /** Lista creciente de reales, para juntar una tira o los triángulos de un color. */
    private class Reales {
        var v = FloatArray(64)
        var n = 0
        fun mas(x: Float) { if (n == v.size) v = v.copyOf(n * 2); v[n++] = x }
        fun limpiar() { n = 0 }
    }

    /**
     * Escribe los tramos de un tipo: primero el contenido de cada uno y al final sus fichas (7
     * palabras: caja, tamaño, desde y cuántos). Devuelve `[dónde están las fichas, cuántas]`.
     */
    private fun apartado(d: Datos, t: ModeloCad.Tramos, contenido: (Int) -> Int): IntArray {
        val fichas = ArrayList<IntArray>()
        for (i in 0 until t.n) {
            val desde = d.sitio
            val n = contenido(i)
            if (n > 0) fichas += intArrayOf(i, desde, n)
        }
        val sitio = d.sitio
        for (f in fichas) {
            val b = f[0] * 4
            d.f(t.caja[b]); d.f(t.caja[b + 1]); d.f(t.caja[b + 2]); d.f(t.caja[b + 3])
            d.f(t.tamano[f[0]])
            d.u(f[1]); d.u(f[2])
        }
        return intArrayOf(sitio, fichas.size)
    }

    private fun rango(desde: Int, cuantos: Int, largo: Int): IntRange {
        val a = desde.coerceIn(0, largo)
        val b = (desde.toLong() + cuantos).coerceAtMost(largo.toLong()).toInt()
        return a until maxOf(a, b)
    }

    /** Un grupo (capa y grosor) en palabras; devuelve su ficha JSON. `grupo()` de `web.rs`. */
    private fun grupo(d: Datos, g: Grupo, origenX: Double, origenY: Double, indiceCapa: Int): String {
        val m = g.modelo
        val ox = (m.origenX - origenX).toFloat()
        val oy = (m.origenY - origenY).toFloat()
        val vx = m.vertices
        val nv = vx.cuantos
        // Rayas: cada tira partida donde cambia el color. [color, n, x, y...]
        val tira = Reales()
        val rayas = apartado(d, m.tramosLineas) { t ->
            var tiras = 0
            var color = 0
            tira.limpiar()
            fun cerrar(c: Int) {
                if (tira.n >= 4) {
                    d.u(d.color(c)); d.u(tira.n / 2)
                    val v = tira.v
                    for (k in 0 until tira.n) d.f(v[k])
                    tiras++
                }
                tira.limpiar()
            }
            for (k in rango(m.tramosLineas.desde[t], m.tramosLineas.cuantos[t], m.lineas.cuantos)) {
                val i = m.lineas.palabra(k, 0)
                if (i == ModeloCad.CORTE) { cerrar(color); continue }
                if (i < 0 || i >= nv) continue
                val qc = vx.palabra(i, 2)
                if (qc != color && tira.n > 0) {
                    val ux = tira.v[tira.n - 2]; val uy = tira.v[tira.n - 1]
                    cerrar(color)
                    tira.mas(ux); tira.mas(uy)
                }
                color = qc
                tira.mas(vx.real(i, 0)); tira.mas(vx.real(i, 1))
            }
            cerrar(color)
            tiras
        }
        // Rellenos: los triángulos de cada color juntos. [color, n, 6n]
        val rellenos = apartado(d, m.tramosTriangulos) { t ->
            val porColor = LinkedHashMap<Int, Reales>()
            val r = rango(m.tramosTriangulos.desde[t], m.tramosTriangulos.cuantos[t], m.triangulos.cuantos)
            var k = r.first
            while (k + 2 <= r.last) {
                val a = m.triangulos.palabra(k, 0); val b = m.triangulos.palabra(k + 1, 0); val c = m.triangulos.palabra(k + 2, 0)
                k += 3
                if (a !in 0 until nv || b !in 0 until nv || c !in 0 until nv) continue
                val lista = porColor.getOrPut(vx.palabra(a, 2)) { Reales() }
                lista.mas(vx.real(a, 0)); lista.mas(vx.real(a, 1)); lista.mas(vx.real(b, 0)); lista.mas(vx.real(b, 1)); lista.mas(vx.real(c, 0)); lista.mas(vx.real(c, 1))
            }
            for ((c, v) in porColor) {
                d.u(d.color(c)); d.u(v.n / 6)
                for (j in 0 until v.n) d.f(v.v[j])
            }
            porColor.size
        }
        // Los patrones de los sombreados: sus familias (16 palabras, tal cual) y sus tramas
        // [centro2, inversa4, escala, desde, n], con el «desde» llevado a la página.
        val familiasEn = d.sitio
        for (i in 0 until m.familias.cuantos) for (k in 0 until 16) d.u(m.familias.palabra(i, k))
        val tramasEn = d.sitio
        for (i in 0 until m.tramas.cuantos) {
            for (k in 0 until 7) d.u(m.tramas.palabra(i, k))
            d.u(familiasEn + m.tramas.palabra(i, 7) * 16)
            d.u(m.tramas.palabra(i, 8))
        }
        // Sombreados: [color, trama, n, 6n]
        val vt = m.verticesTrama
        val sombreados = apartado(d, m.tramosTrama) { t ->
            val por = LinkedHashMap<Long, Reales>()
            val r = rango(m.tramosTrama.desde[t], m.tramosTrama.cuantos[t], m.triangulosTrama.cuantos)
            var k = r.first
            while (k + 2 <= r.last) {
                val a = m.triangulosTrama.palabra(k, 0); val b = m.triangulosTrama.palabra(k + 1, 0); val c = m.triangulosTrama.palabra(k + 2, 0)
                k += 3
                if (a !in 0 until vt.cuantos || b !in 0 until vt.cuantos || c !in 0 until vt.cuantos) continue
                val tr = vt.palabra(a, 3)
                if (tr < 0 || tr >= m.tramas.cuantos) continue
                val clave = (vt.palabra(a, 2).toLong() shl 32) or (tr.toLong() and 0xffffffffL)
                val lista = por.getOrPut(clave) { Reales() }
                lista.mas(vt.real(a, 0)); lista.mas(vt.real(a, 1)); lista.mas(vt.real(b, 0)); lista.mas(vt.real(b, 1)); lista.mas(vt.real(c, 0)); lista.mas(vt.real(c, 1))
            }
            for ((clave, v) in por) {
                d.u(d.color((clave ushr 32).toInt())); d.u(tramasEn + (clave and 0xffffffffL).toInt() * 9); d.u(v.n / 6)
                for (j in 0 until v.n) d.f(v.v[j])
            }
            por.size
        }
        // Círculos y arcos: [x, y, r, inicio, barrido, color]
        val arcos = apartado(d, m.tramosArcos) { t ->
            val r = rango(m.tramosArcos.desde[t], m.tramosArcos.cuantos[t], m.arcos.cuantos)
            for (i in r) {
                for (k in 0 until 5) d.u(m.arcos.palabra(i, k))
                d.u(d.color(m.arcos.palabra(i, 5)))
            }
            r.count()
        }
        // Las letras distintas: [desde, n, rayas] con su malla en em.
        val mallaEn = d.sitio
        for (i in 0 until m.mallaLetras.cuantos) { d.u(m.mallaLetras.palabra(i, 0)); d.u(m.mallaLetras.palabra(i, 1)) }
        val glifosEn = d.sitio
        for (i in 0 until m.glifos.cuantos) {
            val bruto = m.glifos.palabra(i, 1)
            d.u(mallaEn + m.glifos.palabra(i, 0) * 2)
            d.u(bruto and ModeloCad.GLIFO_DE_RAYAS.inv())
            d.u(if (bruto and ModeloCad.GLIFO_DE_RAYAS != 0) 1 else 0)
        }
        // Letras: [x, y, m0, m1, m2, m3, color, glifo]
        val letras = apartado(d, m.tramosLetras) { t ->
            var n = 0
            for (i in rango(m.tramosLetras.desde[t], m.tramosLetras.cuantos[t], m.letras.cuantos)) {
                val gl = m.letras.palabra(i, 7)
                if (gl < 0 || gl >= m.glifos.cuantos) continue
                for (k in 0 until 6) d.u(m.letras.palabra(i, k))
                d.u(d.color(m.letras.palabra(i, 6)))
                d.u(glifosEn + gl * 3)
                n++
            }
            n
        }
        fun par(a: IntArray) = "[${a[0]},${a[1]}]"
        return "{\"k\":$indiceCapa,\"g\":${g.grosor},\"on\":${g.encendida},\"o\":[${num(ox.toDouble())},${num(oy.toDouble())}]," +
            "\"L\":${par(rayas)},\"T\":${par(rellenos)},\"H\":${par(sombreados)},\"A\":${par(arcos)},\"G\":${par(letras)}}"
    }

    /** El primer color de lo dibujado en un modelo (la muestra de la capa en el panel). */
    private fun colorDe(m: ModeloCad): Int? = when {
        m.vertices.cuantos > 0 -> m.vertices.palabra(0, 2)
        m.letras.cuantos > 0 -> m.letras.palabra(0, 6)
        m.arcos.cuantos > 0 -> m.arcos.palabra(0, 5)
        m.verticesTrama.cuantos > 0 -> m.verticesTrama.palabra(0, 2)
        else -> null
    }

    /** Un número para el JSON (nunca `NaN` ni infinito), con seis decimales como mucho. */
    fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val s = String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-" || s == "-0") "0" else s
    }

    /** Un texto como cadena JSON que sirve también dentro de `<script>` (un nombre de capa no cierra el guion). */
    fun textoJson(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch == '<' -> append("\\u003c")
            ch == '>' -> append("\\u003e")
            ch == '&' -> append("\\u0026")
            ch == ' ' -> append("\\u2028")
            ch == ' ' -> append("\\u2029")
            ch.code < 0x20 -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
            else -> append(ch)
        }
        append('"')
    }

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sinSigno(c: Int): String = (c.toLong() and 0xffffffffL).toString()

    // ------------------------------------------------------------------ la página

    /** **La página web del plano**, en [destino]. [grupos]: el plano por capas, o [sinCapas]. */
    fun escribir(grupos: List<Grupo>, o: Opciones, destino: OutputStream) {
        val t = o.textos
        // Las capas, por su nombre, en el orden de los grupos, con la muestra de su primer color.
        val capas = ArrayList<Pair<String, Boolean>>()
        val muestras = ArrayList<Int?>()
        val indice = HashMap<String, Int>()
        val fichas = ArrayList<String>(grupos.size)
        // Los números, comprimidos de corrido: lo que se guarda es lo comprimido.
        val comprimido = ByteArrayOutputStream()
        val gz = object : GZIPOutputStream(comprimido, 1 shl 16) { init { def.setLevel(7) } }
        val d = Datos(gz)
        for (g in grupos) {
            val k = indice.getOrPut(g.capa) { capas += g.capa to g.encendida; muestras += null; capas.size - 1 }
            if (muestras[k] == null) muestras[k] = colorDe(g.modelo)
            fichas += grupo(d, g, o.origenX, o.origenY, k)
        }
        d.vaciar()
        gz.finish()
        for (i in o.imagenes) if (i.capa !in indice) { indice[i.capa] = capas.size; capas += i.capa to true; muestras += null }

        val datos = StringBuilder()
        datos.append("{\"titulo\":").append(textoJson(o.titulo))
        datos.append(",\"vista\":[").append(o.vista.joinToString(",") { num(it) }).append(']')
        datos.append(",\"claro\":").append(o.claro)
        datos.append(",\"unidad\":").append(textoJson(Medidas.sufijo(o.unidades).trim()))
        datos.append(",\"capas\":[").append(capas.indices.joinToString(",") { i ->
            val (n, on) = capas[i]
            muestras[i]?.let { "{\"n\":${textoJson(n)},\"on\":$on,\"c\":${sinSigno(it)}}" } ?: "{\"n\":${textoJson(n)},\"on\":$on}"
        }).append(']')
        datos.append(",\"grupos\":[").append(fichas.joinToString(",")).append(']')
        datos.append(",\"colores\":[").append(d.colores.joinToString(",") { sinSigno(it) }).append(']')
        datos.append(",\"cotas\":[").append(o.cotas.filter { it.size >= 2 }.joinToString(",") { c -> c.joinToString(",", "[", "]") { "${num(it[0])},${num(it[1])}" } }).append(']')
        datos.append(",\"marcos\":[").append(o.marcos.joinToString(",") { "[${num(it.x0)},${num(it.y0)},${num(it.x1)},${num(it.y1)}]" }).append(']')
        datos.append(",\"imagenes\":[")
        o.imagenes.forEachIndexed { n, i ->
            if (n > 0) datos.append(',')
            datos.append("{\"k\":").append(indice[i.capa] ?: 0).append(",\"src\":\"data:").append(i.mime).append(";base64,")
            datos.append(java.util.Base64.getEncoder().encodeToString(i.bytes))
            datos.append("\",\"e\":[${num(i.esquina[0])},${num(i.esquina[1])}],\"u\":[${num(i.u[0])},${num(i.u[1])}],\"v\":[${num(i.v[0])},${num(i.v[1])}]}")
        }
        datos.append(']')
        datos.append(",\"anotado\":").append(o.anotado?.let { a ->
            "{\"caja\":[${a.caja.joinToString(",") { num(it) }}],\"u\":${num(a.u)},\"cero\":[${num(a.ceroX)},${num(a.ceroY)}]}"
        } ?: "null")
        datos.append(",\"normal\":").append(GROSOR_NORMAL)
        datos.append(",\"t\":{\"todas\":").append(textoJson(t.todas)).append(",\"ninguna\":").append(textoJson(t.ninguna))
            .append(",\"sin\":").append(textoJson(t.sinSoporte)).append("}}")

        val titulo = html(o.titulo)
        fun s(x: String) = destino.write(x.toByteArray(Charsets.UTF_8))
        s("<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"utf-8\">\n")
        s("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
        s("<meta name=\"generator\" content=\"PixPin · plano\">\n")
        s("<title>$titulo</title>\n")
        s(ESTILO)
        s("</head><body class=\"${if (o.claro) "claro" else "oscuro"}\">\n<div id=\"barra\">")
        s("<span id=\"nombre\" title=\"$titulo\">$titulo</span>")
        for ((id, rotulo, tecla) in listOf(
            Triple("b-todo", t.todo, "F"), Triple("b-tema", t.tema, "B"), Triple("b-capas", t.capas, "L"),
            Triple("b-grosor", t.grosores, "W"), Triple("b-anot", t.anotaciones, "A")
        )) {
            if (id == "b-anot" && o.anotado == null && o.cotas.isEmpty() && o.marcos.isEmpty()) continue
            s("<button id=\"$id\" title=\"${html(rotulo)} ($tecla)\">${html(rotulo)}</button>")
        }
        s("<span id=\"pista\">${html(t.pista)}</span></div>\n")
        s("<div id=\"lienzo\"><canvas id=\"plano\"></canvas><div id=\"anot\">")
        o.anotado?.let { s(it.svg) }
        s("</div><canvas id=\"encima\"></canvas></div>\n<div id=\"panel\" hidden></div>\n<div id=\"aviso\" hidden></div>\n")
        s("<script id=\"datos\" type=\"application/json\">")
        s(datos.toString())
        s("</script>\n<script id=\"plano-gz\" type=\"application/octet-stream\">")
        destino.flush()
        // El base64 de corrido, sin cerrar [destino].
        java.util.Base64.getEncoder().wrap(object : FilterOutputStream(destino) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }).use { comprimido.writeTo(it) }
        s("</script>\n<script>\n")
        s(PaginaDelPlanoGuion.GUION)
        s("\n</script>\n</body></html>\n")
        destino.flush()
    }

    /** La página en un texto (para las pruebas y lo pequeño). */
    fun pagina(grupos: List<Grupo>, o: Opciones): String =
        ByteArrayOutputStream().also { escribir(grupos, o, it) }.toString("UTF-8")

    /**
     * Lo anotado como lo pide la página: el SVG del motor sin su cabecera XML y con la caja que
     * dice su `viewBox`. Null si no hay SVG o no se entiende su caja.
     */
    fun anotadoDe(svg: String?, u: Double, ceroX: Double, ceroY: Double): Anotado? {
        if (svg.isNullOrBlank()) return null
        val limpio = svg.replace(Regex("""^\s*<\?xml[^>]*\?>\s*"""), "")
        val vb = Regex("""viewBox="([^"]+)"""").find(limpio)?.groupValues?.get(1)?.trim()?.split(Regex("""[\s,]+"""))
            ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 } ?: return null
        return Anotado(limpio, doubleArrayOf(vb[0], vb[1], vb[0] + vb[2], vb[1] + vb[3]), u, ceroX, ceroY)
    }

    /** El estilo de la página (`ESTILO` de `web.rs`, tal cual). */
    private const val ESTILO = """<style>
:root{--fondo:#21262f;--barra:#1b1d22;--texto:#e8e8ec;--suave:#9a9aa2;--boton:#2a2b31;--sobre:#34353c;--azul:#1e88e5}
body.claro{--fondo:#fcfcf9;--barra:#f1f1f3;--texto:#1b1b1f;--suave:#5f6068;--boton:#e3e3e8;--sobre:#d6d6dc}
*{box-sizing:border-box}
html,body{margin:0;height:100%;overflow:hidden;background:var(--fondo);color:var(--texto);font:14px "Segoe UI",system-ui,sans-serif}
#barra{position:fixed;left:0;right:0;top:0;height:44px;display:flex;align-items:center;gap:6px;padding:0 10px;background:var(--barra);z-index:3;border-bottom:1px solid rgba(127,127,127,.25);overflow-x:auto}
#nombre{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:40vw;margin-right:8px}
#barra button{background:var(--boton);color:var(--texto);border:0;border-radius:8px;padding:6px 11px;font:inherit;cursor:pointer;white-space:nowrap}
#barra button:hover{background:var(--sobre)}
#barra button.on{background:var(--azul);color:#fff}
#pista{margin-left:auto;color:var(--suave);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#lienzo{position:fixed;left:0;right:0;top:44px;bottom:0;overflow:hidden;touch-action:none;cursor:grab}
#lienzo.moviendo{cursor:grabbing}
#lienzo canvas{position:absolute;left:0;top:0;width:100%;height:100%}
#anot{position:absolute;left:0;top:0;transform-origin:0 0;pointer-events:none}
#anot svg{display:block;overflow:visible}
#panel{position:fixed;right:10px;top:52px;bottom:10px;width:min(320px,calc(100vw - 20px));overflow:auto;background:var(--barra);border-radius:12px;padding:10px;z-index:4;box-shadow:0 6px 24px rgba(0,0,0,.35)}
#panel label{display:flex;align-items:center;gap:8px;padding:4px 2px;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#panel .muestra{width:12px;height:12px;border-radius:3px;flex:none;border:1px solid rgba(127,127,127,.5)}
#panel .fila{display:flex;gap:6px;margin-bottom:8px}
#panel .fila button{flex:1;background:var(--boton);color:var(--texto);border:0;border-radius:8px;padding:6px;font:inherit;cursor:pointer}
#aviso{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);background:var(--barra);padding:14px 18px;border-radius:12px;max-width:80vw;z-index:5}
@media (max-width:600px){#pista{display:none}}
@media print{#barra,#panel{display:none}#lienzo{top:0}}
</style>
"""
}
