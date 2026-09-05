package com.forge.pixpin.guardados

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * **Una nota de voz, pasada a texto en el propio teléfono.**
 *
 * Sin servidores y sin pagar. El reconocedor es [MotorVosk] (Kaldi, código abierto): se le
 * da el audio entero y devuelve el texto frase a frase, con el tiempo de cada palabra. El
 * de Google que trae Android se probó primero y no valía: se quedaba con la primera frase
 * de cada sesión y decía «idioma no disponible» con el idioma descargado, porque el
 * paquete que instala el teléfono («es-PE», «es-419») no cuadra con la etiqueta pedida.
 *
 * **Cualquier formato**: el archivo —`.m4a` nuestro, `.ogg` de WhatsApp, `.mp3`, `.wav`,
 * `.amr`— se decodifica con [MediaCodec] a PCM mono de 16 kHz, que es lo que el reconocedor
 * quiere, y se guarda en un archivo temporal. Ver [decodificar].
 */
object Transcriptor {

    /** El reconocedor lee a esta frecuencia; lo demás se remuestrea aquí. */
    const val HERCIOS = 16_000
    private const val BYTES_POR_SEGUNDO = HERCIOS * 2

    /** Siempre: el reconocedor va dentro de la aplicación. Ver [MotorVosk]. */
    @Suppress("UNUSED_PARAMETER")
    fun disponible(context: Context): Boolean = true

    /** Si el modelo del idioma ya está en el aparato (si no, se baja la primera vez). */
    fun enLocal(context: Context): Boolean = MotorVosk.modeloListo(context, Locale.getDefault().toLanguageTag())

    /** Un trozo de texto y en qué milisegundo del audio empieza. */
    class Segmento(val desdeMs: Int, val texto: String)

    /**
     * **El texto con sus tiempos**, una línea por párrafo: `[1:23] lo que se dijo`. Los
     * segmentos se juntan en párrafos de unos [PARRAFO_MS]: así cada línea es un sitio al
     * que saltar en el audio sin que el texto sea una lista de frases sueltas. Ver
     * [tiempoDe] para leerlo de vuelta.
     */
    fun conTiempos(segmentos: List<Segmento>, prefijo: String = ""): String {
        val lineas = ArrayList<String>()
        var desde = -1
        val trozo = StringBuilder()
        for (sg in segmentos) {
            if (desde >= 0 && sg.desdeMs - desde >= PARRAFO_MS) {
                lineas += "[${marcaDeTiempo(desde)}] $prefijo${trozo.toString().trim()}"
                trozo.clear(); desde = -1
            }
            if (desde < 0) desde = sg.desdeMs
            trozo.append(sg.texto).append(' ')
        }
        if (trozo.isNotBlank()) lineas += "[${marcaDeTiempo(desde.coerceAtLeast(0))}] $prefijo${trozo.toString().trim()}"
        return lineas.joinToString("\n\n")
    }

    /** `1:23`, `12:05`, `1:02:09`. */
    fun marcaDeTiempo(ms: Int): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val seg = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, seg) else String.format(Locale.ROOT, "%d:%02d", m, seg)
    }

    private val MARCA = Regex("""^\s*\[(?:(\d+):)?(\d+):(\d\d)\]\s*""")

    /** El tiempo (ms) con el que empieza una línea, y la línea sin él; null si no lo lleva. */
    fun tiempoDe(linea: String): Pair<Int, String>? {
        val m = MARCA.find(linea) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toInt(); val seg = m.groupValues[3].toInt()
        return ((h * 3600 + min * 60 + seg) * 1000) to linea.substring(m.range.last + 1)
    }

    /** Cuánto abarca un párrafo del texto con tiempos, como mucho. */
    private const val PARRAFO_MS = 20_000

    /** En qué acabó: el texto, o por qué no. */
    sealed class Resultado {
        /**
         * [avisos]: cuántos trozos no se entendieron; con alguno, el texto tiene huecos.
         * [segmentos]: el texto por trozos, cada uno con en qué milisegundo del audio empieza.
         */
        class Texto(val texto: String, val avisos: Int = 0, val segmentos: List<Segmento> = emptyList()) : Resultado()
        /** El modelo del idioma no está en el aparato y no se pudo bajar (sin red). */
        object DescargandoIdioma : Resultado()
        class Fallo(val codigo: Int) : Resultado()
    }

    /** No se entendió nada. */
    const val NADA = -5

    /**
     * Transcribe [archivo] y llama a [alTerminar] en el hilo principal. Puede llamarse
     * desde cualquier hilo. [avance] recibe, de 0 a 1, cuánto lleva: el primer tercio es
     * bajar el modelo si faltaba, el resto es reconocer.
     */
    fun transcribir(
        context: Context,
        archivo: File,
        idioma: String = Locale.getDefault().toLanguageTag(),
        avance: (Float) -> Unit = {},
        alTerminar: (Resultado) -> Unit
    ) {
        val principal = Handler(Looper.getMainLooper())
        val app = context.applicationContext
        Thread {
            val r = runCatching { transcribirAqui(app, archivo, idioma, avance) }.getOrElse { Resultado.Fallo(-9) }
            principal.post { alTerminar(r) }
        }.start()
    }

    /** Lo mismo, en este hilo: decodificar, bajar el modelo si falta, reconocer. */
    fun transcribirAqui(context: Context, archivo: File, idioma: String, avance: (Float) -> Unit): Resultado {
        val pcm = File(context.cacheDir, "pcm-${System.nanoTime()}.raw")
        try {
            val bien = runCatching { pcm.outputStream().buffered().use { decodificar(archivo, it) } }.isSuccess && pcm.length() > 0
            if (!bien) return Resultado.Fallo(-2)
            avance(0.05f)
            if (!MotorVosk.modeloListo(context, idioma)) {
                MotorVosk.asegurarModelo(context, idioma) { avance(0.05f + 0.3f * it) } ?: return Resultado.DescargandoIdioma
            }
            val segmentos = MotorVosk.reconocer(context, pcm, idioma) { avance(0.35f + 0.65f * it) }
                ?: return Resultado.DescargandoIdioma
            if (segmentos.isEmpty()) return Resultado.Fallo(NADA)
            return Resultado.Texto(segmentos.joinToString(" ") { it.texto }, 0, segmentos)
        } finally {
            pcm.delete()
        }
    }

    // ---- Cortes por frases (para quien quiera trocear un PCM; Vosk no los necesita) ----

    private const val TROZO_SEGUNDOS = 8
    private const val BUSQUEDA_SEGUNDOS = 4
    private const val VENTANA_MS = 20
    private const val SILENCIO_MS = 250
    private const val MINIMO_MS = 300
    private const val PARTE_DE_SILENCIO = 0.12
    private const val UMBRAL_MINIMO = 120.0

    /**
     * En qué tramos (bytes, PCM de 16 bits mono a [HERCIOS]) se parte el archivo: **por
     * frases**. Se mide la energía cada 20 ms, se llama silencio a lo que queda por debajo
     * de una fracción de la energía típica de la nota, y se corta en mitad de cada silencio
     * de más de un cuarto de segundo. Nunca se pegan dos frases; los trozos largos se parten
     * en su punto más callado; lo que no llega a un tercio de segundo es ruido.
     */
    internal fun cortes(pcm: File): List<LongRange> {
        val total = pcm.length() and 1L.inv()
        if (total <= 0) return emptyList()
        val ventana = (VENTANA_MS * BYTES_POR_SEGUNDO / 1000) and 1.inv()
        val energias = ArrayList<Double>((total / ventana).toInt() + 1)
        RandomAccessFile(pcm, "r").use { raf ->
            val buf = ByteArray(ventana)
            var pos = 0L
            while (pos + ventana <= total) {
                raf.seek(pos); raf.readFully(buf)
                val cortos = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                var suma = 0.0
                for (k in 0 until cortos.remaining()) { val v = cortos.get(k).toDouble(); suma += v * v }
                energias += Math.sqrt(suma / cortos.remaining().coerceAtLeast(1))
                pos += ventana
            }
        }
        if (energias.isEmpty()) return listOf(0 until total)
        val ordenadas = energias.sorted()
        val tipica = ordenadas[(ordenadas.size * 0.6).toInt().coerceIn(0, ordenadas.size - 1)]
        val umbral = maxOf(UMBRAL_MINIMO, tipica * PARTE_DE_SILENCIO)
        val minimoDeSilencio = SILENCIO_MS / VENTANA_MS
        val puntos = ArrayList<Long>()
        var calladasDesde = -1
        for ((k, e) in energias.withIndex()) {
            val callada = e < umbral
            if (callada && calladasDesde < 0) calladasDesde = k
            if (!callada && calladasDesde >= 0) {
                if (k - calladasDesde >= minimoDeSilencio) puntos += ((calladasDesde + k) / 2).toLong() * ventana
                calladasDesde = -1
            }
        }
        val trozo = TROZO_SEGUNDOS.toLong() * BYTES_POR_SEGUNDO
        val minimo = MINIMO_MS.toLong() * BYTES_POR_SEGUNDO / 1000
        val salida = ArrayList<LongRange>()
        var desde = 0L
        RandomAccessFile(pcm, "r").use { raf ->
            for (corte in puntos + total) {
                val fin = corte
                if (fin <= desde) continue
                while (fin - desde > trozo) {
                    val tope = desde + trozo
                    val enMedio = puntoMasCallado(raf, tope - BUSQUEDA_SEGUNDOS.toLong() * BYTES_POR_SEGUNDO, tope)
                    if (enMedio - desde >= minimo) salida += desde until enMedio
                    desde = enMedio
                }
                if (fin - desde >= minimo) salida += desde until fin
                desde = fin
            }
        }
        return salida.filter { !it.isEmpty() }
    }

    /** El principio de la ventana de [VENTANA_MS] con menos energía entre [a] y [b] (bytes, pares). */
    private fun puntoMasCallado(raf: RandomAccessFile, a: Long, b: Long): Long {
        val ventana = (VENTANA_MS * BYTES_POR_SEGUNDO / 1000) and 1.inv()
        val desde = a.coerceAtLeast(0) and 1L.inv()
        val largo = (b - desde).toInt()
        if (largo <= ventana) return b and 1L.inv()
        val datos = ByteArray(largo)
        raf.seek(desde)
        raf.readFully(datos)
        val cortos = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var mejor = b
        var menor = Double.MAX_VALUE
        var i = 0
        while (i + ventana <= largo) {
            var energia = 0.0
            var j = i / 2
            val fin = (i + ventana) / 2
            while (j < fin) { val v = cortos.get(j).toDouble(); energia += v * v; j++ }
            if (energia < menor) { menor = energia; mejor = desde + i }
            i += ventana / 2
        }
        return mejor and 1L.inv()
    }

    // ---- Decodificar a PCM ----

    /**
     * De lo que haya en el archivo a PCM de 16 bits, mono, a [HERCIOS]. Mezcla los canales
     * y remuestrea a saltos (interpolación lineal): para la voz sobra.
     *
     * **Sin esperas en el bucle**: se mete entrada y se saca salida con tiempo cero y solo
     * se descansa un momento cuando el códec no tiene nada que dar. Con esperas de diez
     * milisegundos por vuelta, un minuto de audio eran cientos de vueltas y varios segundos
     * de reloj sin hacer nada (era lo que hacía lento compartir una nota con su audio).
     */
    internal fun decodificar(archivo: File, salida: OutputStream) {
        val extractor = MediaExtractor()
        extractor.setDataSource(archivo.absolutePath)
        var pista = -1
        var formato: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { pista = i; formato = f; break }
        }
        if (pista < 0 || formato == null) { extractor.release(); return }
        extractor.selectTrack(pista)
        val codec = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(formato, null, null, 0)
        codec.start()
        var hercios = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var canales = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var entradaAcabada = false
        var salidaAcabada = false
        val remuestreador = Remuestreador(salida)
        try {
            while (!salidaAcabada) {
                var hizoAlgo = false
                if (!entradaAcabada) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        hizoAlgo = true
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaAcabada = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 0)
                when {
                    o >= 0 -> {
                        hizoAlgo = true
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        remuestreador.meter(buf, hercios, canales)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) salidaAcabada = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        hizoAlgo = true
                        val f = codec.outputFormat
                        hercios = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canales = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                if (!hizoAlgo) Thread.sleep(1)
            }
            remuestreador.vaciar()
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { extractor.release() }
        }
    }

    /** Mezcla a mono y lleva a [HERCIOS] con interpolación lineal, escribiendo PCM de 16 bits. */
    private class Remuestreador(private val salida: OutputStream) {
        private var posicion = 0.0     // en muestras de entrada
        private var ultima = 0f
        private var hayUltima = false
        private val paquete = ByteArray(8192)
        private var n = 0

        fun meter(buf: ByteBuffer, hercios: Int, canales: Int) {
            val cortos = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
            val cuantas = cortos.remaining() / canales.coerceAtLeast(1)
            if (cuantas == 0) return
            val mono = FloatArray(cuantas)
            for (i in 0 until cuantas) {
                var suma = 0f
                for (c in 0 until canales) suma += cortos.get(i * canales + c).toFloat()
                mono[i] = suma / canales
            }
            val paso = hercios.toDouble() / HERCIOS
            var p = posicion
            while (p < cuantas) {
                val i = p.toInt()
                val f = (p - i).toFloat()
                val a = if (i == 0) (if (hayUltima) ultima else mono[0]) else mono[i - 1]
                val b = mono[i]
                escribir(a + (b - a) * f)
                p += paso
            }
            posicion = p - cuantas
            ultima = mono[cuantas - 1]
            hayUltima = true
        }

        private fun escribir(v: Float) {
            val s = v.toInt().coerceIn(-32768, 32767)
            paquete[n++] = (s and 0xFF).toByte()
            paquete[n++] = ((s shr 8) and 0xFF).toByte()
            if (n >= paquete.size) { salida.write(paquete, 0, n); n = 0 }
        }

        fun vaciar() { if (n > 0) { salida.write(paquete, 0, n); n = 0 }; salida.flush() }
    }
}
