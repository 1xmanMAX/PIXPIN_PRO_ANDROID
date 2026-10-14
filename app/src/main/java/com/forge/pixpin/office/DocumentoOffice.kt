package com.forge.pixpin.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * **Un Word o un PowerPoint abierto con la maquetación del PC** (11-oct-2026, «las nuevas funciones
 * de PixPin PC»: Word y PowerPoint maquetados como en Office, dentro del lector de PDF).
 *
 * Es la fuente de hojas del [com.forge.pixpin.pdf.LectorPdfActivity] cuando lo que se abre no es
 * un PDF: cuántas hay, cuánto mide cada una y su dibujo a un ancho dado —en vectores, así que
 * acercarse la vuelve a pintar nítida, sin rasterizar nada—. Lo demás del lector (tinta,
 * marcadores, negativo, pasar hojas) no sabe que no es un PDF.
 *
 * **Perezoso como en el PC**: abrir lee el ZIP y los estilos; cada página se maqueta cuando se
 * pide (Word en orden, PowerPoint en cualquiera). [maquetarTodo] va sacando el resto por detrás.
 *
 * Todas las llamadas al .so van por **un hilo propio con pila holgada** ([hilo]): ordenadas (el .so
 * no quiere dos a la vez sobre el mismo documento) y lejos del de la pantalla.
 */
class DocumentoOffice private constructor(
    private val handle: Long,
    val ruta: String,
    val esPresentacion: Boolean
) {
    /** Ancho y alto (en puntos) de cada página ya maquetada, en orden. */
    private val medidas = ArrayList<FloatArray>()
    @Volatile private var cerrado = false
    @Volatile var terminado: Boolean = false
        private set

    /** Las páginas leídas, por su peso en bytes (lo que ocupa el PXOF es una buena medida). */
    private val paginas = object : LruCache<Int, Pxof.Pagina>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Pxof.Pagina) = value.peso.coerceAtLeast(1)
    }

    /** Las imágenes ya decodificadas, con el ancho al que se decodificaron. */
    private val imagenes = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val sinImagen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Cuántas hojas se conocen ya (Word: las maquetadas; PowerPoint: todas). */
    fun cuantas(): Int = synchronized(medidas) { medidas.size }

    /** Ancho y alto en puntos de la hoja [i], o null si aún no se maquetó. */
    fun medida(i: Int): FloatArray? = synchronized(medidas) { medidas.getOrNull(i) }

    /** Ancho entre alto de la hoja [i]; si aún no se sabe, el de la primera. */
    fun proporcion(i: Int): Float {
        val m = medida(i) ?: medida(0) ?: return 0.7f
        return if (m[1] > 0f) m[0] / m[1] else 0.7f
    }

    /** La hoja [i] (desde 0), maquetándola si hace falta. Null si no existe. */
    fun pagina(i: Int): Pxof.Pagina? {
        if (i < 0 || cerrado) return null
        paginas.get(i)?.let { return it }
        val bytes = enHilo { if (cerrado) null else OfficeNativo.pagina(handle, i) } ?: return null
        val p = runCatching { Pxof.leer(bytes) }.getOrElse {
            android.util.Log.w("PixPinOffice", "hoja ${i + 1} ilegible: ${it.message}")
            return null
        }
        apuntar(i, p.ancho, p.alto)
        paginas.put(i, p)
        return p
    }

    private fun apuntar(i: Int, ancho: Float, alto: Float) = synchronized(medidas) {
        while (medidas.size <= i) medidas.add(floatArrayOf(ancho, alto))
        medidas[i] = floatArrayOf(ancho, alto)
    }

    /**
     * Maqueta lo que falte, por orden, y avisa con [alAvanzar] (cuántas hay) cada pocas. Sin
     * guardar las hojas (solo su medida): las que se miren se vuelven a pedir, que ya están hechas.
     */
    fun maquetarTodo(alAvanzar: (Int) -> Unit) {
        var n = cuantas()
        var avisadas = n
        val empezo = System.nanoTime()
        while (!cerrado) {
            val bytes = enHilo { if (cerrado) null else OfficeNativo.pagina(handle, n) } ?: break
            val (an, al) = cabecera(bytes) ?: break
            apuntar(n, an, al)
            n++
            if (n - avisadas >= 8) { alAvanzar(n); avisadas = n }
        }
        terminado = true
        android.util.Log.i("PixPinOffice", "$n hojas maquetadas en ${(System.nanoTime() - empezo) / 1_000_000} ms")
        alAvanzar(n)
    }

    /**
     * La hoja [i] pintada a [anchoPx] de ancho, en blanco y con todo, o null. [total] para los campos
     * de número de páginas. [oscuro]: en el modo oscuro de los visores ([Oscuro], PC 74801b4).
     */
    fun pintar(i: Int, anchoPx: Int, total: Int = cuantas(), oscuro: Boolean = false): Bitmap? {
        val p = pagina(i) ?: return null
        val escala = anchoPx / p.ancho.coerceAtLeast(1f)
        val alto = (p.alto * escala).toInt().coerceAtLeast(1)
        val b = runCatching { Bitmap.createBitmap(anchoPx.coerceAtLeast(1), alto, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val empezo = System.nanoTime()
        pintor().pintar(Canvas(b), p, escala, total, oscuro = oscuro)
        val ms = (System.nanoTime() - empezo) / 1_000_000
        if (ms > 60) android.util.Log.i("PixPinOffice", "hoja ${i + 1} a $anchoPx px: $ms ms")
        return b
    }

    /** Un pintor para este documento (son baratos: se hace uno por hoja, que pintan a la vez). */
    fun pintor(): PintorOffice = PintorOffice { id, ancho -> imagen(id, ancho) }

    /** Una imagen del documento a ~[anchoPx] de ancho (sin pasar de 2048), o null si no se sabe decodificar (EMF, WMF). */
    fun imagen(id: String, anchoPx: Int): Bitmap? {
        if (id in sinImagen) return null
        val quiero = anchoPx.coerceIn(16, 3072)
        imagenes.get(id)?.let { if (it.width >= quiero * 0.7f || it.width >= TOPE_IMAGEN) return it }
        val bytes = enHilo { if (cerrado) null else OfficeNativo.imagen(handle, id) } ?: run { sinImagen += id; return null }
        val medida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medida)
        if (medida.outWidth <= 0) { sinImagen += id; return null }
        var muestra = 1
        while (medida.outWidth / (muestra * 2) >= quiero) muestra *= 2
        val b = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra }) }.getOrNull()
            ?: run { sinImagen += id; return null }
        imagenes.put(id, b)
        return b
    }

    private val notas = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * **Las notas del orador** de la diapositiva [i] (PC ac7981a), un párrafo por renglón; null si no
     * tiene. Se leen al pedirlas y se guardan. Trabajo del .so: fuera de la pantalla.
     */
    fun notas(i: Int): String? {
        if (!esPresentacion || i < 0 || cerrado) return null
        notas[i]?.let { return it.ifEmpty { null } }
        val n = enHilo { if (cerrado) null else OfficeNativo.notas(handle, i) }.orEmpty()
        notas[i] = n
        return n.ifEmpty { null }
    }

    /** Todo el texto, hoja a hoja (para contar palabras). Maqueta lo que falte. */
    fun texto(hoja: Int): String = pagina(hoja)?.textoPlano().orEmpty()

    fun cerrar() {
        if (cerrado) return
        cerrado = true
        hilo.execute { OfficeNativo.cerrar(handle) }
        paginas.evictAll()
        imagenes.evictAll()
    }

    companion object {
        private const val TOPE_IMAGEN = 3072

        /** Las extensiones que se maquetan aquí: las de `pixpin_office::tipo_de`. */
        private val DE_WORD = setOf("docx", "docm", "dotx", "dotm")
        private val DE_POWERPOINT = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm")

        fun esDeOffice(nombre: String?): Boolean = extension(nombre) in DE_WORD || extension(nombre) in DE_POWERPOINT
        fun esPresentacion(nombre: String?): Boolean = extension(nombre) in DE_POWERPOINT
        private fun extension(n: String?) = n.orEmpty().substringAfterLast('.', "").lowercase()

        /**
         * El hilo del .so: uno, con 64 MB de pila (un documento raro anida mucho y la maquetación
         * es recursiva; mejor holgura que una caída sin aviso).
         */
        private val hilo = Executors.newSingleThreadExecutor { r -> Thread(null, r, "pixpin-office", 64L * 1024 * 1024).apply { isDaemon = true } }

        private fun <T> enHilo(f: () -> T): T =
            if (Thread.currentThread().name == "pixpin-office") f() else hilo.submit(Callable { f() }).get()

        @Volatile private var configurado = false

        private fun configurar(c: Context) {
            if (configurado) return
            val pixpin = com.forge.pixpin.planos.LectorDePlanos.ttfDePixPin(c)
            val usuario = com.forge.pixpin.planos.LectorDePlanos.carpetaShx(c)
            enHilo { OfficeNativo.configurar("pixpin:${pixpin.absolutePath}\nusuario:${usuario.absolutePath}\nsistema:/system/fonts\n") }
            configurado = true
        }

        /**
         * Abre [ruta] y maqueta su primera hoja (para que el lector tenga ya su tamaño). Null si
         * no hay .so o no se pudo leer: entonces se abre como antes (el Word como texto, el
         * PowerPoint pasado a PDF). Trabajo de disco: fuera del hilo de la pantalla.
         */
        fun abrir(c: Context, ruta: String): DocumentoOffice? {
            if (!OfficeNativo.disponible || !File(ruta).isFile) return null
            return runCatching {
                configurar(c.applicationContext)
                val empezo = System.nanoTime()
                val h = enHilo { OfficeNativo.abrir(ruta) }
                if (h == 0L) {
                    android.util.Log.w("PixPinOffice", "no se pudo abrir $ruta: ${enHilo { OfficeNativo.ultimoError() }}")
                    return null
                }
                val esPresentacion = enHilo { OfficeNativo.tipo(h) } == 1
                val d = DocumentoOffice(h, ruta, esPresentacion)
                if (d.pagina(0) == null) { d.cerrar(); return null }
                if (esPresentacion) {
                    // Las diapositivas se saben todas; su tamaño es el de la presentación.
                    val n = enHilo { OfficeNativo.listas(h) }
                    val m = d.medida(0)!!
                    for (i in 1 until n) d.apuntar(i, m[0], m[1])
                    d.terminado = true
                }
                android.util.Log.i("PixPinOffice", "abierto en ${(System.nanoTime() - empezo) / 1_000_000} ms: ${File(ruta).name}")
                d
            }.getOrNull()
        }

        /** Ancho y alto de la cabecera de un PXOF, sin leer el resto. */
        internal fun cabecera(b: ByteArray): Pair<Float, Float>? {
            if (b.size < 14 || b[0] != 'P'.code.toByte() || b[1] != 'X'.code.toByte()) return null
            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return bb.getFloat(6) to bb.getFloat(10)
        }
    }
}

/**
 * **Los Word y PowerPoint abiertos en el lector**, por su ruta: el lector pide hojas por ruta (como
 * hace con `PdfDoc`) y aquí se reparte. Se quedan abiertos los dos últimos (volver a uno es al
 * momento); el tercero cierra el más viejo.
 */
object HojasOffice {
    private val abiertos = LinkedHashMap<String, DocumentoOffice>()

    fun es(ruta: String?): Boolean = DocumentoOffice.esDeOffice(ruta)

    /** El documento de [ruta], abriéndolo si hace falta. Trabajo de disco. */
    fun de(c: Context, ruta: String): DocumentoOffice? {
        synchronized(abiertos) { abiertos[ruta]?.let { return it } }
        val d = DocumentoOffice.abrir(c, ruta) ?: return null
        synchronized(abiertos) {
            abiertos[ruta]?.let { d.cerrar(); return it }
            abiertos[ruta] = d
            while (abiertos.size > 2) {
                val viejo = abiertos.keys.first()
                abiertos.remove(viejo)?.cerrar()
            }
        }
        return d
    }

    /** El de [ruta] si ya está abierto, sin abrirlo. */
    fun yaAbierto(ruta: String): DocumentoOffice? = synchronized(abiertos) { abiertos[ruta] }
}
