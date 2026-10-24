package com.forge.pixpin.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * **El realce al oír, enganchado al `MediaPlayer`** (24-oct-2026, la mitad Android de
 * `pixpin-audio/src/volumen.rs` del PC; la cuenta pura está en [RealceDeVolumen]).
 *
 * En el PC se escribe una copia realzada del audio y se oye esa. Aquí sale más barato y sin
 * tocar la reproducción: se **mide el pico una vez** —se descodifica el fichero con
 * `MediaExtractor` + `MediaCodec` en un hilo aparte— y la ganancia se pone con
 * **`LoudnessEnhancer` sobre la sesión de audio del reproductor**. La barra, saltar, la
 * velocidad y la duración siguen igual porque el fichero es el mismo. `LoudnessEnhancer` trae
 * su propio limitador: el redondeo con tanh de [RealceDeVolumen.limitar] no se aplica tal cual,
 * pero tampoco satura.
 *
 * La medida se guarda por fichero (ruta, tamaño y fecha: si cambia, se vuelve a medir) en
 * memoria y en la carpeta temporal de la app, como en el PC: la segunda vez sale al momento.
 * La primera, el audio arranca ya **sin esperar** y el realce entra en cuanto está la medida
 * (una nota de voz son unas décimas de segundo). Lo que ya suena fuerte no se toca.
 *
 * **Nunca rompe la reproducción**: si no se puede leer, descodificar o crear el efecto, se oye
 * como antes. Quien llame a [enganchar] llama a [soltar] al liberar el reproductor.
 */
object RealceAlOir {
    private const val TAG = "RealceAlOir"

    /** La ganancia medida por clave de fichero; 1 = ya fuerte (o ilegible). */
    private val medidas = ConcurrentHashMap<String, Float>()
    private val midiendo = ConcurrentHashMap.newKeySet<String>()
    private val hilo = Executors.newSingleThreadExecutor { r -> Thread(r, "realce-al-oir").apply { isDaemon = true } }
    private val principal = Handler(Looper.getMainLooper())

    /** Lo enganchado a cada reproductor vivo. */
    private class Enganche(val sesion: Int, val clave: String) {
        var efecto: LoudnessEnhancer? = null
        var suelto = false
    }
    private val enganches = WeakHashMap<MediaPlayer, Enganche>()

    /** La carpeta de las medidas: `java.io.tmpdir` en Android es la caché de la app. */
    private val carpeta: File by lazy { File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "pixpin-audio-alto") }

    /**
     * **Realza [p] si [ruta] suena bajo.** Llamar con el reproductor ya preparado (con su sesión
     * de audio). Sin medida guardada, mide en segundo plano y engancha al acabar.
     */
    fun enganchar(p: MediaPlayer, ruta: String) {
        try {
            soltar(p)
            val sesion = p.audioSessionId
            if (sesion == 0) return
            val clave = clave(ruta) ?: return
            val e = Enganche(sesion, clave)
            synchronized(enganches) { enganches[p] = e }
            val conocida = medidas[clave] ?: leerGuardada(clave)?.also { medidas[clave] = it }
            if (conocida != null) { synchronized(enganches) { poner(e, conocida) }; return }
            // Si ya se está midiendo (otro reproductor con el mismo fichero), al acabar se
            // engancha a todos los que esperan esa clave.
            if (!midiendo.add(clave)) return
            hilo.execute {
                val g = try { medir(ruta) } catch (t: Throwable) { Log.w(TAG, "no se pudo medir $ruta: $t"); 1f }
                medidas[clave] = g
                guardar(clave, g)
                midiendo.remove(clave)
                principal.post {
                    synchronized(enganches) {
                        enganches.values.filter { it.clave == clave && it.efecto == null }.forEach { poner(it, g) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sin realce: $t")
        }
    }

    /** Quita el realce de [p] (al pararlo o liberarlo). Sin nada enganchado, no hace nada. */
    fun soltar(p: MediaPlayer?) {
        if (p == null) return
        synchronized(enganches) {
            val e = enganches.remove(p) ?: return
            e.suelto = true
            runCatching { e.efecto?.release() }
            e.efecto = null
        }
    }

    private fun poner(e: Enganche, g: Float) {
        if (e.suelto || !RealceDeVolumen.merece(g)) return
        try {
            val efecto = LoudnessEnhancer(e.sesion)
            efecto.setTargetGain(RealceDeVolumen.milibelios(g))
            efecto.enabled = true
            e.efecto = efecto
            Log.i(TAG, "audio bajo: se oye realzado ×%.2f".format(g))
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer no disponible: $t")
        }
    }

    /** La clave del fichero: su ruta, su tamaño y su fecha. Nula si no existe. */
    private fun clave(ruta: String): String? {
        val f = File(ruta)
        if (!f.isFile) return null
        val texto = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
        return "%08x%08x".format(texto.hashCode(), texto.reversed().hashCode())
    }

    private fun leerGuardada(clave: String): Float? =
        runCatching { File(carpeta, "$clave.g").takeIf { it.isFile }?.readText()?.trim()?.toFloat() }.getOrNull()

    private fun guardar(clave: String, g: Float) {
        runCatching { carpeta.mkdirs(); File(carpeta, "$clave.g").writeText(g.toString()) }
    }

    /**
     * Descodifica [ruta] entera y devuelve la ganancia de [RealceDeVolumen.Medidor]. El
     * histograma va contando según salen los trozos: no se guarda el audio en memoria.
     */
    private fun medir(ruta: String): Float {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ruta)
            var pista = -1
            var formato: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { pista = i; formato = f; break }
            }
            if (pista < 0 || formato == null) return 1f
            extractor.selectTrack(pista)
            val c = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(formato, null, null, 0)
            c.start()
            val medidor = RealceDeVolumen.Medidor()
            var flotante = false
            val info = MediaCodec.BufferInfo()
            var entradaAcabada = false
            var cortos = ShortArray(0)
            var flotantes = FloatArray(0)
            while (true) {
                var hizoAlgo = false
                if (!entradaAcabada) {
                    val i = c.dequeueInputBuffer(0)
                    if (i >= 0) {
                        hizoAlgo = true
                        val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            entradaAcabada = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 0)
                if (o >= 0) {
                    hizoAlgo = true
                    val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    if (flotante) {
                        val fb = buf.asFloatBuffer(); val n = fb.remaining()
                        if (flotantes.size < n) flotantes = FloatArray(n)
                        fb.get(flotantes, 0, n); medidor.anadir(flotantes, 0, n)
                    } else {
                        val sb = buf.asShortBuffer(); val n = sb.remaining()
                        if (cortos.size < n) cortos = ShortArray(n)
                        sb.get(cortos, 0, n); medidor.anadir(cortos, 0, n)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    hizoAlgo = true
                    val f = c.outputFormat
                    flotante = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                if (!hizoAlgo) Thread.sleep(1)
            }
            return medidor.ganancia()
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
    }
}
