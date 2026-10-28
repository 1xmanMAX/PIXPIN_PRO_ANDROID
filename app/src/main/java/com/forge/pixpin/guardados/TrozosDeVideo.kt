package com.forge.pixpin.guardados

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * **El vídeo de una grabación, en trozos** (28-oct-2026, «Grabar sin perder nada», la guía del PC:
 * «`MediaMuxer` no hace MP4 fragmentado: se graba en trozos de unos minutos y al recuperar se juntan
 * sin recodificar»). Un MP4 solo se abre si se cerró (su `moov` va al final): si Android mata la app a
 * la hora y media, de un fichero único no queda nada; en trozos, todos menos el que se grababa.
 *
 * - **La pantalla** ([VideoDeLaClase]) cambia de trozo cada [TROZO_MS] sin soltar nada: se prepara una
 *   `MediaRecorder` nueva, se le da la pantalla (`VirtualDisplay.setSurface`) y se cierra la de antes.
 *   Cada trozo empieza en un fotograma clave (es el primero de su codificador).
 * - **La cámara** ([CamaraDeLaReunion]) usa `MediaRecorder.setNextOutputFile`: al acercarse a
 *   [bytesDelTrozo] el escritor pasa al fichero siguiente **en el siguiente fotograma clave**, sin cortar
 *   la cámara (cambiar su superficie obligaría a volver a atarla).
 * - **El sonido nunca va en el vídeo**: lo graba la nota ([com.forge.pixpin.audio.Grabador.Realzado],
 *   con su respaldo WAV). Al juntar ([unir]) se le pone al vídeo el trozo de la nota que le toca.
 *
 * Al juntar, cada trozo va **en su minuto** ([colocar]): donde empezó en la nota o, si el anterior
 * acaba después, justo detrás. El hueco de un cambio de trozo (unas décimas) es un fotograma quieto,
 * y vídeo y sonido siguen a la par.
 */
object TrozosDeVideo {
    private const val TAG = "PixPinTrozos"

    /** Lo que dura un trozo de la pantalla: lo más que se pierde si Android mata la app. */
    const val TROZO_MS = 3 * 60_000L

    /** Un búfer de muestra donde cabe un fotograma clave de 1080p. */
    private const val BUFFER = 4 * 1024 * 1024

    /** **Lo que ocupa un trozo de la cámara** a [bitsPorSegundo]: unos [TROZO_MS] de vídeo. */
    fun bytesDelTrozo(bitsPorSegundo: Int): Long =
        maxOf(bitsPorSegundo.toLong() / 8 * (TROZO_MS / 1000), 8L * 1024 * 1024)

    /**
     * **Dónde va cada trozo** al juntarlos, en ms desde el primero: el minuto en que empezó ([desdes],
     * los de la nota) y, si el anterior aún no acabó ([duraciones]), justo detrás de él. Nunca se
     * solapan ni van hacia atrás.
     */
    fun colocar(desdes: List<Long>, duraciones: List<Long>): List<Long> {
        if (desdes.isEmpty()) return emptyList()
        val base = desdes.first()
        val v = ArrayList<Long>(desdes.size)
        var fin = 0L
        for (i in desdes.indices) {
            val o = maxOf(desdes[i] - base, fin, 0L)
            v += o
            fin = o + duraciones.getOrElse(i) { 0L }.coerceAtLeast(0)
        }
        return v
    }

    /** Un trozo para juntar: su fichero y el minuto de la nota en que empezó. */
    data class Trozo(val archivo: File, val desdeMs: Long)

    /**
     * **Junta [trozos] en [salida]**, sin recodificar, cada uno en su minuto ([colocar]), y con el sonido
     * de [nota] desde el minuto del primero hasta el final del último (si hay nota). Los trozos que no
     * se abren se saltan (se quedan donde estén). Si un trozo trae otro formato de imagen (otro tamaño),
     * se para ahí. Devuelve los trozos que entraron (vacío si no salió nada): con los que queden se
     * puede volver a llamar para otra parte.
     */
    fun unir(trozos: List<Trozo>, nota: File?, salida: File): List<Trozo> {
        val buenos = trozos.filter { GrabacionAMedias.mirar(it.archivo).seAbre }
        if (buenos.isEmpty()) return emptyList()
        var muxer: MediaMuxer? = null
        val abiertos = ArrayList<MediaExtractor>()
        val audio = MediaExtractor()
        val entraron = ArrayList<Trozo>()
        val bien = runCatching {
            // Lo que trae cada trozo: su pista de imagen, su formato y lo que dura.
            data class Leido(val trozo: Trozo, val ex: MediaExtractor, val pista: Int, val formato: MediaFormat, val duracionUs: Long, val desdeMs: Long)
            val leidos = ArrayList<Leido>()
            for (t in buenos) {
                val ex = MediaExtractor()
                abiertos += ex
                val ok = runCatching { ex.setDataSource(t.archivo.absolutePath) }.isSuccess
                val p = if (ok) pistaDe(ex, "video/") else null
                if (p == null) continue
                val f = ex.getTrackFormat(p)
                if (leidos.isNotEmpty() && !mismaImagen(leidos.first().formato, f)) {
                    Log.w(TAG, "un trozo trae otra imagen: el vídeo acaba antes")
                    break
                }
                val d = runCatching { f.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                leidos += Leido(t, ex, p, f, d, t.desdeMs)
            }
            if (leidos.isEmpty()) return@runCatching false
            val desfases = colocar(leidos.map { it.desdeMs }, leidos.map { it.duracionUs / 1000 })
            val m = MediaMuxer(salida.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            Mp4DeLaReunion.giroDe(buenos.first().archivo)?.let { m.setOrientationHint(it) }
            val mv = m.addTrack(leidos.first().formato)
            val pa = nota?.takeIf { it.isFile }?.let { n -> runCatching { audio.setDataSource(n.absolutePath) }.getOrNull()?.let { pistaDe(audio, "audio/") } }
            val ma = pa?.let { m.addTrack(audio.getTrackFormat(it)) }
            m.start()
            val buf = ByteBuffer.allocate(BUFFER)
            val info = MediaCodec.BufferInfo()
            val desdeAudioUs = leidos.first().desdeMs.coerceAtLeast(0) * 1000
            if (pa != null) {
                audio.selectTrack(pa)
                audio.seekTo(desdeAudioUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            var audioAcabado = pa == null
            /** Escribe el sonido hasta [hastaUs] (en el tiempo del vídeo). */
            fun sonidoHasta(hastaUs: Long) {
                if (audioAcabado || ma == null) return
                while (true) {
                    val t = audio.sampleTime
                    if (t < 0) { audioAcabado = true; return }
                    val enVideo = t - desdeAudioUs
                    if (enVideo > hastaUs) return
                    val n = audio.readSampleData(buf, 0)
                    if (n < 0) { audioAcabado = true; return }
                    if (enVideo >= 0) {
                        info.set(0, n, enVideo, if ((audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        m.writeSampleData(ma, buf, info)
                    }
                    audio.advance()
                }
            }
            var fin = 0L
            var escritas = 0
            for ((i, l) in leidos.withIndex()) {
                val base = desfases[i] * 1000
                l.ex.selectTrack(l.pista)
                var ultimo = -1L
                var paso = 0L
                while (true) {
                    val n = l.ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val t0 = l.ex.sampleTime
                    if (ultimo >= 0) paso = (t0 - ultimo).coerceAtLeast(0)
                    ultimo = t0
                    val t = base + t0.coerceAtLeast(0)
                    sonidoHasta(t)
                    info.set(0, n, t, if ((l.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(mv, buf, info)
                    escritas++
                    l.ex.advance()
                }
                if (ultimo >= 0) fin = base + ultimo + paso.coerceAtLeast(1)
                entraron += l.trozo
            }
            sonidoHasta(fin)
            m.stop()
            escritas > 0
        }.onFailure { Log.w(TAG, "no se pudieron juntar los trozos del vídeo", it) }.getOrDefault(false)
        runCatching { muxer?.release() }
        abiertos.forEach { runCatching { it.release() } }
        runCatching { audio.release() }
        if (!bien) {
            salida.delete()
            return emptyList()
        }
        return entraron
    }

    private fun pistaDe(ex: MediaExtractor, tipo: String): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(tipo) == true }

    /** Si dos trozos traen la misma imagen (códec, tamaño y sus cabeceras): entonces se pueden pegar. */
    private fun mismaImagen(a: MediaFormat, b: MediaFormat): Boolean {
        fun n(f: MediaFormat, k: String) = runCatching { f.getInteger(k) }.getOrDefault(-1)
        fun csd(f: MediaFormat, k: String): ByteBuffer? = runCatching { f.getByteBuffer(k) }.getOrNull()
        return a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            n(a, MediaFormat.KEY_WIDTH) == n(b, MediaFormat.KEY_WIDTH) &&
            n(a, MediaFormat.KEY_HEIGHT) == n(b, MediaFormat.KEY_HEIGHT) &&
            csd(a, "csd-0") == csd(b, "csd-0") && csd(a, "csd-1") == csd(b, "csd-1")
    }
}
