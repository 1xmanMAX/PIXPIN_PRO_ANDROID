package com.forge.pixpin.audio

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * **El respaldo crudo de la nota de una grabación** (28-oct-2026, «Grabar sin perder nada»;
 * `crates/pixpin-motor-audio/src/respaldo.rs` del PC, `97ca055d`, número a número).
 *
 * El `.m4a` que escribe `MediaMuxer` (y `MediaRecorder`) solo se puede abrir después de `stop()`, que
 * escribe su índice (`moov`) al final. Si Android mata la app a mitad de una reunión de dos horas, lo
 * que queda es un `.m4a.parte` que no abre nadie. Por eso, junto a él, se escribe **el mismo PCM en un
 * WAV**: un formato sin índice que cerrar, donde cada muestra escrita ya vale.
 *
 * - **Lo que cuesta**: mono a 22 050 Hz son 44 KB/s, unos 160 MB por hora, en disco (en memoria, solo
 *   el búfer de 256 KB). Se borra al cerrar bien la nota; mientras se graba, la guarda de espacio vigila
 *   que quepa ([com.forge.pixpin.guardados.GrabacionAMedias.espacio]).
 * - **Cuánto se puede perder**: cada [SEGUNDOS_POR_VOLCADO] segundos de sonido se vacía el búfer y se
 *   pide `FileDescriptor.sync()`, así que un apagón pierde como mucho esos segundos; que Android mate la
 *   app, solo lo del búfer.
 * - **Si escribir falla** (disco lleno), el respaldo se calla ([fallo]); la nota sigue grabándose
 *   igual. Un respaldo nunca para una grabación.
 *
 * La cabecera se escribe al empezar con los tamaños «sin fin» (`0xFFFFFFFF`) y se pone la buena al
 * cerrar; al leer uno cortado ([leer]) el tamaño sale del fichero, no de la cabecera.
 *
 * Puro (`java.io`): lo prueba `RespaldoWavTest` en la JVM, con los casos del PC.
 */
object RespaldoWav {
    /** Lo que ocupa la cabecera que se escribe aquí. */
    const val BYTES_CABECERA = 44L

    /** Cada cuántos segundos de sonido se lleva al disco de verdad. */
    const val SEGUNDOS_POR_VOLCADO = 5L

    /** El búfer en memoria. */
    private const val BUFER = 256 * 1024

    /** Lo que se pone de tamaño mientras no se sabe. */
    const val SIN_FIN = 0xFFFF_FFFFL

    /** `<destino>.respaldo.wav`: al lado del destino y con su nombre entero. */
    fun rutaDelRespaldo(destino: File): File = File(destino.path + ".respaldo.wav")

    private fun le32(b: ByteArray, en: Int, v: Long) {
        for (i in 0 until 4) b[en + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun le16(b: ByteArray, en: Int, v: Int) {
        b[en] = (v and 0xff).toByte(); b[en + 1] = ((v ushr 8) and 0xff).toByte()
    }

    /** La cabecera RIFF/WAVE de PCM de 16 bits. */
    fun cabecera(muestreo: Int, canales: Int, bytesDeDatos: Long): ByteArray {
        val c = maxOf(canales, 1)
        val alineado = 2 * c
        val riff = if (bytesDeDatos == SIN_FIN) SIN_FIN else minOf(bytesDeDatos + 36, SIN_FIN)
        val b = ByteArray(44)
        "RIFF".toByteArray().copyInto(b, 0)
        le32(b, 4, riff)
        "WAVE".toByteArray().copyInto(b, 8)
        "fmt ".toByteArray().copyInto(b, 12)
        le32(b, 16, 16)
        le16(b, 20, 1)
        le16(b, 22, c)
        le32(b, 24, muestreo.toLong())
        le32(b, 28, muestreo.toLong() * alineado)
        le16(b, 32, alineado)
        le16(b, 34, 16)
        "data".toByteArray().copyInto(b, 36)
        le32(b, 40, bytesDeDatos)
        return b
    }

    /** **Un respaldo escribiéndose.** Crea (o trunca) [ruta] y escribe la cabecera. */
    class Respaldo(val ruta: File, val muestreo: Int, canales: Int = 1) {
        val canales = maxOf(canales, 1)
        private val fos = FileOutputStream(ruta)
        private var abierto = true
        private val bufer = ByteArray(BUFER)
        private var enBufer = 0

        /** Marcos escritos (al búfer o al disco). */
        var marcos = 0L
            private set
        private var sinVolcar = 0L

        /** Por qué dejó de escribir, si dejó. */
        var fallo: IOException? = null
            private set

        init {
            fos.write(cabecera(muestreo, this.canales, SIN_FIN))
            fos.flush()
        }

        /**
         * Un bloque intercalado de 16 bits ([n] muestras de [muestras]), detrás de lo anterior. Si falla,
         * el respaldo se calla (las siguientes no hacen nada) y devuelve false esa vez.
         */
        fun escribir(muestras: ShortArray, n: Int = muestras.size): Boolean {
            // Cerrado (o callado): no hace nada, y solo dice false si fue por un fallo.
            if (!abierto || fallo != null) return fallo == null
            return try {
                var i = 0
                while (i < n) {
                    if (enBufer + 2 > bufer.size) vaciar()
                    val m = muestras[i].toInt()
                    bufer[enBufer++] = (m and 0xff).toByte()
                    bufer[enBufer++] = ((m shr 8) and 0xff).toByte()
                    i++
                }
                val k = (n / canales).toLong()
                marcos += k
                sinVolcar += k
                if (sinVolcar >= muestreo.toLong() * SEGUNDOS_POR_VOLCADO) volcar()
                true
            } catch (e: IOException) {
                fallo = e
                runCatching { fos.close() }
                abierto = false
                false
            }
        }

        private fun vaciar() {
            if (enBufer > 0) fos.write(bufer, 0, enBufer)
            enBufer = 0
        }

        /** Vacía el búfer al disco (sin cerrar): hasta el disco de verdad. */
        fun volcar() {
            if (!abierto) return
            vaciar()
            fos.flush()
            fos.fd.sync()
            sinVolcar = 0
        }

        /** **Cierra con la cabecera buena.** Devuelve los marcos escritos. */
        fun cerrar(): Long {
            if (!abierto) return marcos
            abierto = false
            try {
                vaciar()
                fos.flush()
                fos.fd.sync()
            } finally {
                runCatching { fos.close() }
            }
            val datos = minOf(marcos * 2 * canales, SIN_FIN - 36)
            RandomAccessFile(ruta, "rw").use { r ->
                r.seek(0)
                r.write(cabecera(muestreo, canales, datos))
                r.fd.sync()
            }
            return marcos
        }

        /** Lo deja como esté, sin cabecera buena (lo que deja un corte; para las pruebas). */
        internal fun soltarSinCerrar() {
            runCatching { vaciar(); fos.flush() }
            runCatching { fos.close() }
            abierto = false
        }
    }

    /** Lo que dice un WAV (cerrado o cortado). */
    data class Wav(val muestreo: Int, val canales: Int, val inicio: Long, val marcos: Long) {
        /** Lo que dura, en milisegundos. */
        fun duracionMs(): Long = if (muestreo <= 0) 0 else marcos * 1000 / muestreo
    }

    private fun u32(b: ByteArray, en: Int): Long =
        (b[en].toLong() and 0xff) or ((b[en + 1].toLong() and 0xff) shl 8) or
            ((b[en + 2].toLong() and 0xff) shl 16) or ((b[en + 3].toLong() and 0xff) shl 24)

    private fun u16(b: ByteArray, en: Int): Int = (b[en].toInt() and 0xff) or ((b[en + 1].toInt() and 0xff) shl 8)

    /**
     * **Lee la cabecera** de un WAV de PCM de 16 bits, aunque esté cortado: el tamaño de los datos sale
     * de lo que mide el fichero (y de la cabecera solo si es menor). Lanza si no es un WAV así.
     */
    fun leer(ruta: File): Wav {
        RandomAccessFile(ruta, "r").use { f ->
            val largo = f.length()
            val riff = ByteArray(12)
            f.readFully(riff)
            if (String(riff, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(riff, 8, 4, Charsets.ISO_8859_1) != "WAVE")
                throw IOException("no es un WAV")
            var formato: Pair<Int, Int>? = null
            var pos = 12L
            val trozo = ByteArray(8)
            while (true) {
                if (pos + 8 > largo) throw IOException("WAV sin datos")
                f.seek(pos)
                f.readFully(trozo)
                val id = String(trozo, 0, 4, Charsets.ISO_8859_1)
                val tam = u32(trozo, 4)
                if (id == "fmt ") {
                    val fmt = ByteArray(16)
                    f.readFully(fmt)
                    val etiqueta = u16(fmt, 0)
                    val canales = u16(fmt, 2)
                    val muestreo = u32(fmt, 4)
                    val bits = u16(fmt, 14)
                    if (etiqueta != 1 || bits != 16 || canales == 0 || muestreo == 0L) throw IOException("WAV que no es PCM de 16 bits")
                    formato = muestreo.toInt() to canales
                } else if (id == "data") {
                    val (muestreo, canales) = formato ?: throw IOException("WAV sin formato antes de los datos")
                    val inicio = pos + 8
                    val enFichero = (largo - inicio).coerceAtLeast(0)
                    val dichos = if (tam == SIN_FIN) enFichero else minOf(tam, enFichero)
                    return Wav(muestreo, canales, inicio, dichos / (2L * canales))
                }
                pos += 8 + tam + (tam and 1)
                if (pos >= largo) throw IOException("WAV sin datos")
            }
        }
    }

    /** Lo que dura el respaldo de [ruta], o null si no hay uno que se lea. */
    fun duracionMs(ruta: File): Long? = if (!ruta.isFile) null else runCatching { leer(ruta).duracionMs() }.getOrNull()

    /** **Recorre las muestras** de un WAV en trozos de como mucho [marcos] marcos, intercaladas. */
    fun recorrer(ruta: File, wav: Wav, marcos: Int, cada: (ShortArray, Int) -> Unit) {
        val porMarco = 2 * wav.canales
        val bytes = ByteArray(maxOf(marcos, 1) * porMarco)
        val muestras = ShortArray(maxOf(marcos, 1) * wav.canales)
        BufferedInputStream(FileInputStream(ruta), 256 * 1024).use { s ->
            var saltar = wav.inicio
            while (saltar > 0) {
                val k = s.skip(saltar)
                if (k <= 0) throw IOException("WAV más corto que su cabecera")
                saltar -= k
            }
            var quedan = wav.marcos
            while (quedan > 0) {
                val n = minOf(quedan, maxOf(marcos, 1).toLong()).toInt()
                val total = n * porMarco
                var leidos = 0
                while (leidos < total) {
                    val k = s.read(bytes, leidos, total - leidos)
                    if (k < 0) throw IOException("WAV cortado")
                    leidos += k
                }
                val ns = n * wav.canales
                for (i in 0 until ns) muestras[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                cada(muestras, ns)
                quedan -= n
            }
        }
    }

    /**
     * **El tiempo de un bloque** al rehacer la nota: [marcos] marcos ya pasados a [muestreo] Hz, en µs
     * (lo que lleva cada búfer de entrada del codificador AAC).
     */
    fun microsegundos(marcos: Long, muestreo: Int): Long = if (muestreo <= 0) 0 else marcos * 1_000_000L / muestreo

    /**
     * **El respaldo en su propio hilo** (28-oct-2026): el hilo del micrófono solo copia el bloque y lo
     * deja en una cola; escribir y, cada 5 s, `sync()` (que en una memoria lenta tarda) van aquí, sin
     * que el micrófono pierda ni un bloque. Si la cola se llena (el disco se atascó), el respaldo se
     * calla: la nota sigue.
     */
    class EnHilo(ruta: File, muestreo: Int) {
        private val respaldo = Respaldo(ruta, muestreo, 1)
        private val cola = ArrayBlockingQueue<ShortArray>(256)
        @Volatile private var corriendo = true
        @Volatile var callado = false
            private set
        private val hilo = Thread({ bucle() }, "pixpin-respaldo").apply { isDaemon = true; start() }

        val ruta: File get() = respaldo.ruta

        /** Copia [n] muestras de [muestras] a la cola. No espera nunca. */
        fun escribir(muestras: ShortArray, n: Int) {
            if (callado || n <= 0) return
            if (!cola.offer(muestras.copyOf(n))) callado = true
        }

        private fun bucle() {
            while (corriendo || cola.isNotEmpty()) {
                val b = cola.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!respaldo.escribir(b)) callado = true
            }
        }

        /** Escribe lo que quede en la cola y cierra con la cabecera buena. */
        fun cerrar(): Long {
            corriendo = false
            runCatching { hilo.join(5_000) }
            return runCatching { respaldo.cerrar() }.getOrDefault(respaldo.marcos)
        }
    }
}
