package com.forge.pixpin.office

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **Una página de Word o una diapositiva de PowerPoint ya maquetada**, tal como la deja la lectura
 * del PC (`crates/pixpin-office`, compilada para Android en `libpixpinoffice.so`): todo en su sitio
 * y en puntos (1/72 de pulgada), origen arriba a la izquierda de la hoja.
 *
 * Llega en el formato PXOF (`herramientas/pixpin-office-android/src/pxof.rs`, little-endian), que
 * aquí se lee a objetos sin nada de Android para poder probarlo en la JVM; lo pinta
 * [PintorOffice]. Cada texto trae, letra a letra, **la cara, el glifo y la x** con que lo midió la
 * maquetación: así el renglón se parte en el teléfono exactamente donde se ve partido.
 */
object Pxof {
    const val VERSION = 2

    class NoSeLee(porque: String) : Exception(porque)

    /** Un archivo de letra (y su índice dentro de una colección), y lo que hay que fingir al pintarla. */
    data class Cara(val ruta: String, val indice: Int, val negritaFalsa: Boolean, val cursivaFalsa: Boolean)

    sealed interface Relleno {
        data class Solido(val argb: Int) : Relleno
        /** [angulo] en grados: 0 de izquierda a derecha, 90 de arriba abajo. */
        class Lineal(val angulo: Float, val posiciones: FloatArray, val colores: IntArray) : Relleno
        class Radial(val posiciones: FloatArray, val colores: IntArray) : Relleno
    }

    enum class Discontinuo { SOLIDA, RAYAS, PUNTOS, RAYA_PUNTO }

    data class Trazo(val argb: Int, val grosor: Float, val discontinuo: Discontinuo)

    /**
     * Una figura de un trayecto: [puntos] y [orden] como los de un `Path` (0 = recta a x,y; 1 = bézier
     * con 6 números). [tinte]: cómo se tiñe respecto al relleno de la forma (las caras del cubo y del
     * bisel de las formas predefinidas, PC f38c011): 0 ninguno, 1 oscuro, 2 oscuro suave, 3 claro,
     * 4 claro suave, 5 aparte (mismo color, en otra geometría para que no se agujereen).
     */
    class Figura(
        val x0: Float, val y0: Float,
        val tipos: ByteArray, val puntos: FloatArray,
        val cerrada: Boolean, val rellenar: Boolean, val trazar: Boolean,
        val tinte: Int = 0
    )

    /**
     * Un efecto de DrawingML (`a:effectLst`, PC 954e77e) sobre lo que envuelve un [Efectos]. Las
     * medidas, en puntos de donde está el [Efectos].
     */
    sealed interface Efecto {
        /** `a:outerShdw`: la silueta, de [argb], desenfocada, movida, escalada y sesgada (grados) respecto a [ancla] (fracciones de la caja). */
        class SombraExterior(
            val argb: Int, val desenfoque: Float, val dx: Float, val dy: Float,
            val escalaX: Float, val escalaY: Float, val sesgoX: Float, val sesgoY: Float, val anclaX: Float, val anclaY: Float
        ) : Efecto
        /** `a:innerShdw`: sombra por dentro del borde, hacia [dx], [dy]. */
        class SombraInterior(val argb: Int, val desenfoque: Float, val dx: Float, val dy: Float) : Efecto
        /** `a:glow`: un halo de [radio] puntos alrededor. */
        class Brillo(val argb: Int, val radio: Float) : Efecto
        /** `a:softEdge`: el borde se desvanece hacia dentro [radio] puntos. */
        class BordeSuave(val radio: Float) : Efecto
    }

    sealed interface Elemento

    class Texto(
        val x: Float, val y: Float, val tam: Float, val argb: Int,
        /** Es el total de páginas (`NUMPAGES`): se escribe el de verdad al pintar. */
        val total: Boolean,
        val ancho: Float,
        val enlace: String?,
        val texto: String,
        /** Por letra: número de cara en [Pagina.caras], glifo, carácter (código) y x desde [x]. */
        val caras: IntArray, val glifos: IntArray, val codigos: IntArray, val xs: FloatArray
    ) : Elemento

    class Rect(val x: Float, val y: Float, val ancho: Float, val alto: Float, val relleno: Relleno?, val borde: Trazo?) : Elemento
    class Linea(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val trazo: Trazo) : Elemento
    /** [recorte]: lo que se quita por la izquierda, arriba, derecha y abajo, en fracciones. */
    class Imagen(val id: String, val x: Float, val y: Float, val ancho: Float, val alto: Float, val recorte: FloatArray?, val opacidad: Float) : Elemento
    class Trayecto(val relleno: Relleno?, val borde: Trazo?, val figuras: List<Figura>) : Elemento
    /** [m] = a b c d e f: x' = a·x + c·y + e, y' = b·x + d·y + f. [recorte] = x, y, ancho, alto en coordenadas del grupo. */
    class Grupo(val m: FloatArray, val recorte: FloatArray?, val elementos: List<Elemento>) : Elemento
    /** Elementos con efectos (sombras, brillo, borde suave): se pintan como siempre y además sus efectos. */
    class Efectos(val efectos: List<Efecto>, val elementos: List<Elemento>) : Elemento

    class Pagina(
        val ancho: Float, val alto: Float, val numero: Int,
        val fondo: Relleno?, val caras: List<Cara>, val elementos: List<Elemento>,
        /** Los bytes que ocupaba: para la caché. */
        val peso: Int
    ) {
        /** Ancho entre alto. */
        val proporcion: Float get() = if (alto > 0f) ancho / alto else 0.7f

        /**
         * Los textos con su caja en la página (los grupos solo trasladan, como el PC: un texto
         * girado da la caja de antes de girar). Para buscar, elegir y copiar, y los enlaces.
         */
        fun textos(): List<Pair<Texto, FloatArray>> {
            val v = ArrayList<Pair<Texto, FloatArray>>()
            fun bajar(els: List<Elemento>, dx: Float, dy: Float) {
                for (e in els) when (e) {
                    is Texto -> v += e to floatArrayOf(e.x + dx, e.y + dy - e.tam * 0.9f, e.ancho, e.tam * 1.2f)
                    is Grupo -> bajar(e.elementos, dx + e.m[4], dy + e.m[5])
                    is Efectos -> bajar(e.elementos, dx, dy)
                    else -> {}
                }
            }
            bajar(elementos, 0f, 0f)
            return v
        }

        /** El texto que hay en ese punto (en puntos), si lo hay. */
        fun textoEn(x: Float, y: Float): Texto? =
            textos().firstOrNull { (_, r) -> x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3] }?.first

        /** El texto de la hoja en orden de lectura: renglón a renglón, de arriba abajo. */
        fun textoPlano(): String = renglones(textos()).joinToString("\n") { r -> r.joinToString(" ") { it.texto.trim() }.trim() }
            .lines().filter { it.isNotBlank() }.joinToString("\n")

        /** Los textos del renglón que pasa por [y] (en puntos), de izquierda a derecha. */
        fun renglonEn(x: Float, y: Float): List<Texto> {
            val todos = textos()
            val tocado = todos.firstOrNull { (_, r) -> x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3] }
                ?: return emptyList()
            val base = tocado.first.y
            return todos.filter { kotlin.math.abs(it.first.y - base) < tocado.first.tam * 0.3f }.sortedBy { it.second[0] }.map { it.first }
        }

        private fun renglones(t: List<Pair<Texto, FloatArray>>): List<List<Texto>> {
            val r = ArrayList<MutableList<Pair<Texto, FloatArray>>>()
            for (p in t.sortedWith(compareBy({ it.first.y }, { it.second[0] }))) {
                val ultimo = r.lastOrNull()
                if (ultimo != null && kotlin.math.abs(ultimo[0].first.y - p.first.y) < p.first.tam * 0.3f) ultimo += p
                else r += mutableListOf(p)
            }
            return r.map { l -> l.sortedBy { it.second[0] }.map { it.first } }
        }
    }

    /** Lee una página. Lanza [NoSeLee] si los bytes no son PXOF o vienen cortados. */
    fun leer(bytes: ByteArray): Pagina = try {
        Lector(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).pagina(bytes.size)
    } catch (e: NoSeLee) {
        throw e
    } catch (e: RuntimeException) {
        // Cortado (BufferUnderflow) o con números que no cuadran: no se pinta a medias.
        throw NoSeLee("PXOF roto: ${e.javaClass.simpleName}")
    }

    /** Tope de anidado de grupos: un archivo malo no tumba la pila. */
    private const val PROFUNDIDAD = 64

    private class Lector(val b: ByteBuffer) {
        fun u8() = b.get().toInt() and 0xff
        fun u16() = b.short.toInt() and 0xffff
        fun u32(): Int {
            val v = b.int
            if (v < 0) throw NoSeLee("cuenta imposible")
            return v
        }
        fun f32() = b.float
        fun argb() = b.int
        fun str(): String {
            val n = u32()
            if (n > b.remaining()) throw NoSeLee("texto cortado")
            val a = ByteArray(n)
            b.get(a)
            return String(a, Charsets.UTF_8)
        }
        /** Una cuenta que no puede pedir más de lo que queda (cada cosa ocupa al menos [minimo] bytes). */
        fun cuenta(n: Int, minimo: Int): Int {
            if (n.toLong() * minimo > b.remaining()) throw NoSeLee("cuenta mayor que el archivo")
            return n
        }

        fun pagina(peso: Int): Pagina {
            val firma = ByteArray(4).also { b.get(it) }
            if (String(firma, Charsets.US_ASCII) != "PXOF") throw NoSeLee("no es PXOF")
            val version = u16()
            if (version != VERSION) throw NoSeLee("PXOF v$version (se lee la $VERSION)")
            val ancho = f32()
            val alto = f32()
            val numero = u32()
            val fondo = relleno()
            val nCaras = cuenta(u16(), 7)
            val caras = List(nCaras) {
                val ruta = str()
                val indice = u16()
                val f = u8()
                Cara(ruta, indice, f and 1 != 0, f and 2 != 0)
            }
            val elementos = elementos(0, nCaras)
            return Pagina(ancho, alto, numero, fondo, caras, elementos, peso)
        }

        fun relleno(): Relleno? = when (val t = u8()) {
            0 -> null
            1 -> Relleno.Solido(argb())
            2 -> {
                val angulo = f32()
                val (p, c) = paradas()
                Relleno.Lineal(angulo, p, c)
            }
            3 -> {
                val (p, c) = paradas()
                Relleno.Radial(p, c)
            }
            else -> throw NoSeLee("relleno $t")
        }

        fun paradas(): Pair<FloatArray, IntArray> {
            val n = cuenta(u16(), 8)
            val p = FloatArray(n)
            val c = IntArray(n)
            for (i in 0 until n) { p[i] = f32(); c[i] = argb() }
            return p to c
        }

        fun trazo(): Trazo? = when (val t = u8()) {
            0 -> null
            1 -> {
                val c = argb()
                val g = f32()
                val d = Discontinuo.entries.getOrElse(u8()) { Discontinuo.SOLIDA }
                Trazo(c, g, d)
            }
            else -> throw NoSeLee("trazo $t")
        }

        fun elementos(nivel: Int, caras: Int): List<Elemento> {
            if (nivel > PROFUNDIDAD) throw NoSeLee("grupos demasiado anidados")
            val n = cuenta(u32(), 1)
            val v = ArrayList<Elemento>(n)
            repeat(n) { v += elemento(nivel, caras) }
            return v
        }

        fun elemento(nivel: Int, caras: Int): Elemento = when (val t = u8()) {
            1 -> {
                val x = f32(); val y = f32(); val tam = f32(); val c = argb()
                val total = u8() and 1 != 0
                val ancho = f32()
                val enlace = str().ifEmpty { null }
                val texto = str()
                val n = cuenta(u32(), 12)
                val cs = IntArray(n); val gs = IntArray(n); val cod = IntArray(n); val xs = FloatArray(n)
                for (i in 0 until n) {
                    cs[i] = u16().also { if (it >= caras) throw NoSeLee("cara $it de $caras") }
                    gs[i] = u16(); cod[i] = b.int; xs[i] = f32()
                }
                Texto(x, y, tam, c, total, ancho, enlace, texto, cs, gs, cod, xs)
            }
            2 -> {
                val x = f32(); val y = f32(); val an = f32(); val al = f32()
                Rect(x, y, an, al, relleno(), trazo())
            }
            3 -> {
                val x1 = f32(); val y1 = f32(); val x2 = f32(); val y2 = f32()
                Linea(x1, y1, x2, y2, trazo() ?: Trazo(0xFF000000.toInt(), 1f, Discontinuo.SOLIDA))
            }
            4 -> {
                val id = str()
                val x = f32(); val y = f32(); val an = f32(); val al = f32()
                val recorte = if (u8() != 0) floatArrayOf(f32(), f32(), f32(), f32()) else null
                Imagen(id, x, y, an, al, recorte, f32())
            }
            5 -> {
                val relleno = relleno()
                val borde = trazo()
                val n = cuenta(u16(), 13)
                Trayecto(relleno, borde, List(n) { figura() })
            }
            6 -> {
                val m = FloatArray(6) { f32() }
                val recorte = if (u8() != 0) floatArrayOf(f32(), f32(), f32(), f32()) else null
                Grupo(m, recorte, elementos(nivel + 1, caras))
            }
            7 -> {
                val n = u8()
                val efectos = List(n) { efecto() }
                Efectos(efectos, elementos(nivel + 1, caras))
            }
            else -> throw NoSeLee("elemento $t")
        }

        fun efecto(): Efecto = when (val t = u8()) {
            1 -> Efecto.SombraExterior(argb(), f32(), f32(), f32(), f32(), f32(), f32(), f32(), f32(), f32())
            2 -> Efecto.SombraInterior(argb(), f32(), f32(), f32())
            3 -> Efecto.Brillo(argb(), f32())
            4 -> Efecto.BordeSuave(f32())
            else -> throw NoSeLee("efecto $t")
        }

        fun figura(): Figura {
            val f = u8()
            val x0 = f32(); val y0 = f32()
            val n = cuenta(u32(), 9)
            val tipos = ByteArray(n)
            val puntos = ArrayList<Float>(n * 2)
            for (i in 0 until n) {
                val t = u8()
                tipos[i] = t.toByte()
                when (t) {
                    0 -> { puntos += f32(); puntos += f32() }
                    1 -> repeat(6) { puntos += f32() }
                    else -> throw NoSeLee("tramo $t")
                }
            }
            return Figura(x0, y0, tipos, puntos.toFloatArray(), f and 1 != 0, f and 2 != 0, f and 4 != 0, (f shr 3) and 7)
        }
    }
}
