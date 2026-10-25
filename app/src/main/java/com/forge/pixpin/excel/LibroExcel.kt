package com.forge.pixpin.excel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * **Un libro de Excel abierto con la lectura del PC** (11-oct-2026, «las nuevas funciones de
 * PixPin PC»: el visor de hojas).
 *
 * Como en el PC, **se abre al instante**: se leen las filas de la primera pantalla y se pinta; el
 * resto de la hoja llega por tandas por detrás ([leerElResto]). Lo que se pinta se pide **por
 * trozos de [FILAS_POR_TROZO] filas** ([Hoja.pedir]): nunca se copian cien mil filas de golpe, y
 * los trozos que no se ven se olvidan ([MAX_TROZOS]).
 *
 * Todas las llamadas al .so van por **un hilo propio** ([hilo]): en orden, y lejos de la pantalla.
 * La vista ([VistaHoja]) solo lee lo ya traído y pide lo que le falte; cuando llega algo, se le
 * avisa con [alCambiar] (desde ese hilo).
 */
class LibroExcel private constructor(private val origen: Origen, val ruta: String?, val libro: Pxhj.Libro) {
    /**
     * **De dónde salen las hojas** (25-oct-2026, «todo lo incorporas en la nueva»): el .so (un archivo
     * de Excel) o unas tablas de PixPin ya en memoria ([TablasEnElVisor]). Lo de arriba —trozos,
     * tandas, el hilo— es lo mismo para las dos. Se llama siempre desde [hilo].
     */
    internal interface Origen {
        fun abrirHoja(i: Int, hasta: Int): Boolean
        fun ultimoError(): String?
        /** 1 ya entera, 0 falta, -1 no se pudo. */
        fun cargar(i: Int, celdas: Int): Int
        fun geometria(i: Int): Pxhj.Geometria?
        fun trozo(i: Int, f0: Int, f1: Int, conocidos: Int): Pxhj.Trozo?
        fun copiar(i: Int, f0: Int, c0: Int, f1: Int, c1: Int): String?
        fun lista(i: Int, f: Int, c: Int): String?
        fun imagen(ruta: String): ByteArray?
        fun cerrar()
    }

    /** El libro leído por `libpixpinoffice.so`, con su número [h]. */
    private class DelNativo(private val h: Long) : Origen {
        override fun abrirHoja(i: Int, hasta: Int) = HojasNativo.abrirHoja(h, i, hasta)
        override fun ultimoError() = HojasNativo.ultimoError()
        override fun cargar(i: Int, celdas: Int) = HojasNativo.cargar(h, i, celdas)
        override fun geometria(i: Int): Pxhj.Geometria? {
            val b = HojasNativo.geometria(h, i) ?: return null
            return runCatching { Pxhj.geometria(b) }.onFailure { android.util.Log.w(TAG, "geometría ilegible: ${it.message}") }.getOrNull()
        }
        override fun trozo(i: Int, f0: Int, f1: Int, conocidos: Int): Pxhj.Trozo? {
            val b = HojasNativo.trozo(h, i, f0, f1, conocidos) ?: return null
            return runCatching { Pxhj.trozo(b) }.onFailure { android.util.Log.w(TAG, "trozo ${f0 / FILAS_POR_TROZO} ilegible: ${it.message}") }.getOrNull()
        }
        override fun copiar(i: Int, f0: Int, c0: Int, f1: Int, c1: Int) = HojasNativo.copiar(h, i, f0, c0, f1, c1)
        override fun lista(i: Int, f: Int, c: Int) = HojasNativo.lista(h, i, f, c)
        override fun imagen(ruta: String) = HojasNativo.imagen(h, ruta)
        override fun cerrar() = HojasNativo.cerrar(h)
    }

    /** Unas tablas ya pasadas a hojas del visor: todo en memoria y entero desde el principio. */
    private class DeTablas(private val hojas: List<TablasEnElVisor.Hoja>) : Origen {
        override fun abrirHoja(i: Int, hasta: Int) = i in hojas.indices
        override fun ultimoError(): String? = null
        override fun cargar(i: Int, celdas: Int) = if (i in hojas.indices) 1 else -1
        override fun geometria(i: Int) = hojas.getOrNull(i)?.geometria
        override fun trozo(i: Int, f0: Int, f1: Int, conocidos: Int) = hojas.getOrNull(i)?.trozo(f0, f1, conocidos)
        override fun copiar(i: Int, f0: Int, c0: Int, f1: Int, c1: Int) = hojas.getOrNull(i)?.copiar(f0, c0, f1, c1)
        override fun lista(i: Int, f: Int, c: Int): String? = null
        override fun imagen(ruta: String): ByteArray? = null
        override fun cerrar() {}
    }

    /** Si las hojas son tablas de PixPin (y no la lectura del .so). */
    val deTablas: Boolean get() = origen is DeTablas

    @Volatile private var cerrado = false

    /** Se llama (desde el hilo del .so) cuando llega algo nuevo que pintar. */
    @Volatile var alCambiar: (() -> Unit)? = null

    private val hojas = ConcurrentHashMap<Int, Hoja>()

    /** Una hoja abierta: su geometría, sus estilos y los trozos que se han traído. */
    inner class Hoja(val indice: Int) {
        @Volatile var geo: Pxhj.Geometria? = null
            private set

        /** Los estilos de la hoja; solo crece (en el hilo del .so). */
        @Volatile var estilos: Array<Pxhj.Estilo> = emptyArray()
            private set

        /** Si ya se leyó entera (y con ella lo que va al final: combinaciones, tablas, enlaces…). */
        @Volatile var completa = false
            private set

        @Volatile var fallo: String? = null
            internal set

        /** Lo que pide la vista para pintar a la vez que la hoja (una por libro: ver [VistaHoja]). */
        @JvmField internal var pintura: Any? = null

        private val trozos = object : LinkedHashMap<Int, Pxhj.Trozo>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Pxhj.Trozo>) = size > MAX_TROZOS
        }
        private val pedidos = ConcurrentHashMap.newKeySet<Int>()

        fun trozo(k: Int): Pxhj.Trozo? = synchronized(trozos) { trozos[k] }

        /** La fila [f], si su trozo ya está. */
        fun fila(f: Int): Pxhj.Fila? = trozo(f / FILAS_POR_TROZO)?.fila(f)

        /** Pide el trozo [k] si no está ni se está trayendo. */
        fun pedir(k: Int) {
            if (k < 0 || cerrado || trozo(k) != null || !pedidos.add(k)) return
            hilo.execute { traer(k) }
        }

        private fun traer(k: Int) {
            try {
                if (cerrado) return
                val conocidos = estilos.size
                val f0 = k * FILAS_POR_TROZO
                val t = origen.trozo(indice, f0, f0 + FILAS_POR_TROZO - 1, conocidos) ?: return
                if (t.desde != conocidos) return
                if (t.estilos.isNotEmpty()) estilos = estilos + t.estilos
                synchronized(trozos) { trozos[k] = t }
                // Pedir un trozo lejano lee hasta él: la geometría ha crecido.
                val g = geo
                if (g != null && !completa && f0 + FILAS_POR_TROZO > g.ys.size - 1) refrescarGeometria()
            } finally {
                pedidos.remove(k)
            }
            avisar()
        }

        internal fun refrescarGeometria() {
            val g = origen.geometria(indice) ?: return
            geo = g
            if (g.completa) completa = true
        }

        /** Ya leída entera: lo que va al final de la hoja cambia los trozos traídos. */
        internal fun alCompletar() {
            completa = true
            refrescarGeometria()
            synchronized(trozos) { trozos.clear() }
        }

        /** Las filas leídas hasta ahora (para el aviso de «leyendo»). */
        val filasLeidas: Int get() = (geo?.ys?.size ?: 1) - 1
    }

    /** La hoja [i], abriéndola si hace falta (en el hilo del .so: vuelve enseguida). */
    fun hoja(i: Int): Hoja {
        hojas[i]?.let { return it }
        val nueva = Hoja(i)
        val antes = hojas.putIfAbsent(i, nueva)
        if (antes != null) return antes
        hilo.execute {
            if (cerrado) return@execute
            val t0 = System.nanoTime()
            if (!origen.abrirHoja(i, PRIMERA_PANTALLA)) {
                nueva.fallo = origen.ultimoError() ?:"no se pudo abrir la hoja"
                avisar(); return@execute
            }
            nueva.refrescarGeometria()
            nueva.pedir(0)
            android.util.Log.i(TAG, "hoja ${i + 1}: primera pantalla en ${(System.nanoTime() - t0) / 1_000_000} ms")
            avisar()
            leerElResto(nueva, System.nanoTime())
        }
        return nueva
    }

    /** Una tanda más de la hoja, y se vuelve a poner en la cola (así los trozos que pide la vista pasan entre medias). */
    private fun leerElResto(hoja: Hoja, t0: Long) {
        hilo.execute {
            if (cerrado || hoja.completa) return@execute
            when (origen.cargar(hoja.indice, TANDA)) {
                1 -> {
                    hoja.alCompletar()
                    android.util.Log.i(TAG, "hoja ${hoja.indice + 1} entera en ${(System.nanoTime() - t0) / 1_000_000} ms (${hoja.filasLeidas} filas)")
                    avisar()
                }
                0 -> {
                    hoja.refrescarGeometria()
                    avisar()
                    leerElResto(hoja, t0)
                }
                else -> hoja.alCompletar()
            }
        }
    }

    /** Lo elegido como texto con tabuladores; [listo] se llama desde el hilo del .so. */
    fun copiar(hoja: Int, r: Pxhj.Rango, listo: (String?) -> Unit) {
        hilo.execute { listo(if (cerrado) null else origen.copiar(hoja, r.f0, r.c0, r.f1, r.c1)) }
    }

    /** Los valores de la lista desplegable de la celda (solo para ver), en el hilo del .so; null si no tiene. */
    fun lista(hoja: Int, f: Int, c: Int, listo: (List<String>?) -> Unit) {
        hilo.execute { listo(if (cerrado) null else origen.lista(hoja, f, c)?.split('\n')?.filter { it.isNotEmpty() }) }
    }

    private val imagenes = ConcurrentHashMap<String, Bitmap>()
    private val sinImagen = ConcurrentHashMap.newKeySet<String>()

    /** La imagen [ruta] del libro si ya está decodificada; si no, se pide y se avisa al llegar. */
    fun imagen(ruta: String): Bitmap? {
        imagenes[ruta]?.let { return it }
        if (cerrado || !sinImagen.add(ruta)) return null
        hilo.execute {
            val b = origen.imagen(ruta)
            val bmp = b?.let { decodificar(it) }
            if (bmp != null) { imagenes[ruta] = bmp; avisar() }
        }
        return null
    }

    private fun decodificar(b: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var muestra = 1
        while (maxOf(o.outWidth, o.outHeight) / muestra > 2048) muestra *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = muestra })
    }.getOrNull()

    private fun avisar() {
        alCambiar?.invoke()
    }

    fun cerrar() {
        if (cerrado) return
        cerrado = true
        hilo.execute { origen.cerrar() }
    }

    companion object {
        private const val TAG = "PixPinExcel"
        const val FILAS_POR_TROZO = 64
        /** Filas que se leen antes de enseñar nada (una pantalla larga con margen). */
        const val PRIMERA_PANTALLA = 80
        /** Celdas por tanda al leer el resto (unos milisegundos cada una, como el PC). */
        const val TANDA = 150_000
        /** Trozos que se guardan (64 filas cada uno): unas 25 000 filas a mano. */
        const val MAX_TROZOS = 400

        /** El hilo del .so: uno, con pila holgada (un XML raro anida mucho). */
        private val hilo = Executors.newSingleThreadExecutor { r -> Thread(null, r, "pixpin-hojas", 32L * 1024 * 1024).apply { isDaemon = true } }

        private fun <T> enHilo(f: () -> T): T = hilo.submit(Callable { f() }).get()

        /**
         * Las extensiones que lee `pixpin-hojas`: los libros modernos y, desde el PC 9975385, los `.xls`
         * antiguos (BIFF8) sin Office. Los `.xlsb` (binarios modernos) van por donde iban.
         */
        fun esLibro(nombre: String?): Boolean =
            nombre?.substringAfterLast('.', "")?.lowercase() in setOf("xlsx", "xlsm", "xltx", "xltm", "xls", "xlt")

        /** Si [nombre] (o [ruta]) se abre en el visor: es un libro, el .so está y el archivo es un ZIP o un `.xls` de verdad. */
        fun seAbre(nombre: String?, ruta: String?): Boolean =
            (esLibro(nombre) || esLibro(ruta)) && HojasNativo.disponible && ruta != null && File(ruta).let { empiezaComoZip(it) || esXlsAntiguo(it) }

        /** Un archivo roto (o un `.xlsb`) no empieza por «PK»: se queda con lo de antes. */
        fun empiezaComoZip(f: File): Boolean = runCatching {
            f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte() }
        }.getOrDefault(false)

        /** La firma de un archivo compuesto de OLE (`D0 CF 11 E0 A1 B1 1A E1`): un `.xls` antiguo. */
        fun esXlsAntiguo(f: File): Boolean = runCatching {
            val firma = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            f.inputStream().use { val b = ByteArray(8); it.read(b) == 8 && b.contentEquals(firma) }
        }.getOrDefault(false)

        /**
         * La configuración regional para los números y las fechas, como el PC con la de Windows:
         * los nombres del idioma (español o inglés) con los separadores y la fecha corta del teléfono.
         */
        fun regional(l: Locale = Locale.getDefault()): String {
            val s = java.text.DecimalFormatSymbols.getInstance(l)
            val patron = (java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, l) as? java.text.SimpleDateFormat)?.toPattern().orEmpty()
            return listOf(
                if (l.language == "en") "en" else "es",
                s.decimalSeparator.toString(),
                s.groupingSeparator.toString(),
                patron.firstOrNull { it !in "dMy" }?.toString() ?: "/",
                fechaCorta(patron),
                // Los minutos al este de UTC: el «hoy» del formato condicional («la semana pasada»…).
                (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000).toString()
            ).joinToString("\n")
        }

        /** El patrón de fecha corta de Java (`d/M/yy`) en código de Excel (`d/m/yyyy`), o "" si lleva algo raro. */
        fun fechaCorta(patron: String): String {
            if (patron.isEmpty() || !patron.all { it in "dMy/-. ," } || 'd' !in patron || 'M' !in patron) return ""
            val sep = patron.firstOrNull { it !in "dMy" }
            var p = if (sep != null && sep != '/') patron.replace(sep, '/') else patron
            // Los años, siempre con cuatro cifras (como hace Excel con la fecha corta de Windows).
            p = p.replace(Regex("y+"), "yyyy")
            return p.replace('M', 'm')
        }

        /** Abre el libro (lee la lista de hojas y los estilos: milisegundos). Trabajo de disco: fuera de la pantalla. */
        fun abrir(ruta: String): LibroExcel? {
            if (!HojasNativo.disponible || !File(ruta).isFile) return null
            return runCatching {
                val t0 = System.nanoTime()
                val h = enHilo { HojasNativo.abrir(ruta, regional()) }
                if (h == 0L) {
                    android.util.Log.w(TAG, "no se pudo abrir $ruta: ${enHilo { HojasNativo.ultimoError() }}")
                    return null
                }
                val b = enHilo { HojasNativo.libro(h) }
                val libro = b?.let { runCatching { Pxhj.libro(it) }.getOrNull() }
                if (libro == null || libro.hojas.none { it.visible && it.tipo == 0 }) {
                    hilo.execute { HojasNativo.cerrar(h) }
                    return null
                }
                android.util.Log.i(TAG, "libro abierto en ${(System.nanoTime() - t0) / 1_000_000} ms: ${File(ruta).name}")
                LibroExcel(DelNativo(h), ruta, libro)
            }.getOrNull()
        }

        /**
         * **Unas tablas de PixPin como libro** (25-oct-2026), ya pasadas con [TablasEnElVisor.hoja]
         * (que calcula: fuera de la pantalla). [ruta]: el archivo del que salieron, si lo hay.
         */
        fun deTablas(hojas: List<TablasEnElVisor.Hoja>, ruta: String? = null, activa: Int = 0): LibroExcel? {
            if (hojas.isEmpty()) return null
            return LibroExcel(DeTablas(hojas), ruta, TablasEnElVisor.libro(hojas, activa))
        }
    }
}
