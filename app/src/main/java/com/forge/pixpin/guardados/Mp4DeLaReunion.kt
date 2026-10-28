package com.forge.pixpin.guardados

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * **Lo que se hace con los MP4 de la reunión presencial al terminar** (28-oct-2026), siempre
 * **copiando las muestras tal cual**, sin recomprimir (una hora tarda segundos), como
 * [VideoDeLaClase.sacarElAudio]:
 *
 * - [unirAudio]: la nota de voz sale de la pista de audio del vídeo; si el vídeo va en dos tramos
 *   (la calidad automática bajó a 720p pasada la hora), de los dos seguidos.
 * - [recortar]: un clip de la forma «vídeo de la sala» es un trozo del propio vídeo de la reunión (la
 *   cámara no puede grabar dos vídeos a la vez): desde el fotograma clave anterior hasta su final.
 * - [ponerSonido]: un clip de la forma «solo audio» se graba sin micrófono (el micrófono lo tiene la
 *   nota) y al terminar se le pone el trozo de la nota que sonaba.
 */
object Mp4DeLaReunion {
    private const val TAG = "PixPinReunion"

    /** El tamaño del búfer de una muestra: un fotograma clave HEVC/H.264 de 1080p cabe de sobra. */
    private const val BUFFER = 4 * 1024 * 1024

    /**
     * **La pista de audio de [tramos], seguidos, a [m4a]**. Los tramos se pegan uno detrás de otro: el
     * segundo empieza donde acabó el primero. Si un tramo trae otro formato de audio (no debería: los
     * graba el mismo código), se para ahí. False si no salió nada.
     */
    fun unirAudio(tramos: List<File>, m4a: File): Boolean {
        if (tramos.size == 1) return VideoDeLaClase.sacarElAudio(tramos[0], m4a)
        var muxer: MediaMuxer? = null
        return runCatching {
            var pista = -1
            var formato: MediaFormat? = null
            var base = 0L
            var escritas = 0
            val buf = ByteBuffer.allocate(BUFFER)
            val info = MediaCodec.BufferInfo()
            for (t in tramos) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(t.absolutePath)
                    val i = pistaDe(ex, "audio/") ?: continue
                    val f = ex.getTrackFormat(i)
                    if (formato == null) {
                        formato = f
                        muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        pista = muxer!!.addTrack(f)
                        muxer!!.start()
                    } else if (!mismoAudio(formato!!, f)) {
                        Log.w(TAG, "un tramo trae otro audio: la nota acaba antes")
                        break
                    }
                    ex.selectTrack(i)
                    var ultimo = -1L
                    var paso = 0L
                    while (true) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val t0 = ex.sampleTime
                        if (ultimo >= 0) paso = (t0 - ultimo).coerceAtLeast(0)
                        ultimo = t0
                        info.set(0, n, base + t0, if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer!!.writeSampleData(pista, buf, info)
                        escritas++
                        ex.advance()
                    }
                    // El siguiente tramo, justo detrás de la última muestra (y lo que dura ella).
                    if (ultimo >= 0) base += ultimo + paso.coerceAtLeast(1)
                } finally {
                    runCatching { ex.release() }
                }
            }
            muxer?.stop()
            escritas > 0
        }.onFailure { Log.w(TAG, "no se pudo sacar el audio de los tramos", it) }
            .getOrDefault(false)
            .also { runCatching { muxer?.release() }; if (!it) m4a.delete() }
    }

    /**
     * **Un trozo de [mp4]**, de [desdeMs] a [desdeMs] + [duracionMs], a [salida]: la imagen desde el
     * fotograma clave de antes (sin él no se ve nada hasta el siguiente) y el sonido desde [desdeMs],
     * los dos corridos al mismo cero para que sigan a la par. Con su giro. False si no salió.
     */
    fun recortar(mp4: File, desdeMs: Long, duracionMs: Long, salida: File): Boolean {
        var muxer: MediaMuxer? = null
        val video = MediaExtractor()
        val audio = MediaExtractor()
        return runCatching {
            video.setDataSource(mp4.absolutePath)
            audio.setDataSource(mp4.absolutePath)
            val pv = pistaDe(video, "video/") ?: return@runCatching false
            val pa = pistaDe(audio, "audio/")
            muxer = MediaMuxer(salida.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            giroDe(mp4)?.let { muxer!!.setOrientationHint(it) }
            val mv = muxer!!.addTrack(video.getTrackFormat(pv))
            val ma = pa?.let { muxer!!.addTrack(audio.getTrackFormat(it)) }
            muxer!!.start()
            val desdeUs = desdeMs.coerceAtLeast(0) * 1000
            val hastaUs = desdeUs + duracionMs.coerceAtLeast(0) * 1000
            video.selectTrack(pv)
            video.seekTo(desdeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val cero = video.sampleTime.coerceAtLeast(0)
            val fuentes = mutableListOf(Fuente(video, mv, hastaUs, cero))
            if (pa != null && ma != null) {
                audio.selectTrack(pa)
                audio.seekTo(desdeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                fuentes += Fuente(audio, ma, hastaUs, cero)
            }
            val escritas = volcar(muxer!!, fuentes)
            muxer!!.stop()
            escritas > 0
        }.onFailure { Log.w(TAG, "no se pudo recortar el clip", it) }
            .getOrDefault(false)
            .also {
                runCatching { muxer?.release() }
                runCatching { video.release() }
                runCatching { audio.release() }
                if (!it) salida.delete()
            }
    }

    /**
     * **[video] (sin sonido) con el sonido de [nota]** desde [desdeMs], a [salida]: el clip grabado con
     * la cámara mientras la nota tenía el micrófono. Las dos líneas de tiempo no cuentan las pausas, así
     * que el trozo de la nota que va con el clip es el que empieza en su minuto. Con su giro.
     */
    fun ponerSonido(video: File, nota: File, desdeMs: Long, salida: File): Boolean {
        var muxer: MediaMuxer? = null
        val v = MediaExtractor()
        val a = MediaExtractor()
        return runCatching {
            v.setDataSource(video.absolutePath)
            a.setDataSource(nota.absolutePath)
            val pv = pistaDe(v, "video/") ?: return@runCatching false
            val pa = pistaDe(a, "audio/")
            muxer = MediaMuxer(salida.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            giroDe(video)?.let { muxer!!.setOrientationHint(it) }
            val mv = muxer!!.addTrack(v.getTrackFormat(pv))
            val ma = pa?.let { muxer!!.addTrack(a.getTrackFormat(it)) }
            muxer!!.start()
            v.selectTrack(pv)
            val duracionUs = runCatching { v.getTrackFormat(pv).getLong(MediaFormat.KEY_DURATION) }.getOrDefault(Long.MAX_VALUE / 4)
            val fuentes = mutableListOf(Fuente(v, mv, Long.MAX_VALUE / 2, 0))
            if (pa != null && ma != null) {
                val desdeUs = desdeMs.coerceAtLeast(0) * 1000
                a.selectTrack(pa)
                a.seekTo(desdeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                fuentes += Fuente(a, ma, desdeUs + duracionUs, desdeUs)
            }
            val escritas = volcar(muxer!!, fuentes)
            muxer!!.stop()
            escritas > 0
        }.onFailure { Log.w(TAG, "no se pudo poner el sonido al clip", it) }
            .getOrDefault(false)
            .also {
                runCatching { muxer?.release() }
                runCatching { v.release() }
                runCatching { a.release() }
                if (!it) salida.delete()
            }
    }

    /** Una pista que se copia: hasta qué tiempo (del original) y cuánto se le resta. */
    private class Fuente(val ex: MediaExtractor, val pista: Int, val hastaUs: Long, val restaUs: Long) {
        var acabada = false
    }

    /**
     * Copia las muestras de [fuentes] al [muxer], **mezcladas por su tiempo** (la de menor tiempo
     * primero): un MP4 con una pista entera y luego la otra se lee mal en algunos reproductores.
     */
    private fun volcar(muxer: MediaMuxer, fuentes: List<Fuente>): Int {
        val buf = ByteBuffer.allocate(BUFFER)
        val info = MediaCodec.BufferInfo()
        var escritas = 0
        while (true) {
            for (f in fuentes) if (!f.acabada && (f.ex.sampleTime < 0 || f.ex.sampleTime >= f.hastaUs)) f.acabada = true
            val f = fuentes.filter { !it.acabada }.minByOrNull { it.ex.sampleTime - it.restaUs } ?: break
            val n = f.ex.readSampleData(buf, 0)
            if (n < 0) { f.acabada = true; continue }
            val t = (f.ex.sampleTime - f.restaUs).coerceAtLeast(0)
            info.set(0, n, t, if ((f.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(f.pista, buf, info)
            escritas++
            f.ex.advance()
        }
        return escritas
    }

    private fun pistaDe(ex: MediaExtractor, tipo: String): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(tipo) == true }

    private fun mismoAudio(a: MediaFormat, b: MediaFormat): Boolean {
        fun n(f: MediaFormat, k: String) = runCatching { f.getInteger(k) }.getOrDefault(-1)
        return a.getString(MediaFormat.KEY_MIME) == b.getString(MediaFormat.KEY_MIME) &&
            n(a, MediaFormat.KEY_SAMPLE_RATE) == n(b, MediaFormat.KEY_SAMPLE_RATE) &&
            n(a, MediaFormat.KEY_CHANNEL_COUNT) == n(b, MediaFormat.KEY_CHANNEL_COUNT)
    }

    /** El giro apuntado en un MP4 (0, 90, 180 o 270), o null si no se sabe. */
    private fun giroDe(mp4: File): Int? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(mp4.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

/**
 * **La foto de la cámara, en un JPEG que lean todos** (28-oct-2026, §3.3: «JPEG de la cámara, o
 * HEVC/HEIC convertido a JPEG ~92 para que el PC lo lea»), **a su resolución entera**.
 *
 * - Un JPEG derecho (sin giro en su EXIF) se queda **tal cual**, byte a byte como salió.
 * - Un JPEG con giro se endereza, como todo lo que entra en el chat ([FotoDerecha]): aquí todo lee
 *   las fotos sin mirar el EXIF. Se recomprime a 92 (la de las cámaras) y **se le copia el EXIF**
 *   (fecha, cámara, exposición…) con el giro ya a «normal».
 * - Un HEIC (algunos teléfonos) se pasa a JPEG a 92 igual, con su EXIF.
 *
 * Si no cabe en memoria (una foto enorme en un teléfono justo), se queda como salió: de lado es mejor
 * que perderla, y el PC la gira al leerla.
 */
object FotoDeLaCamara {
    /** La calidad del JPEG cuando hay que volver a escribirlo: la de las cámaras. */
    const val CALIDAD = 92

    /** Las etiquetas del EXIF que se conservan al volver a escribir la foto. */
    private val ETIQUETAS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
    )

    /** Si los primeros bytes son de un JPEG. */
    fun esJpeg(cabeza: ByteArray): Boolean = cabeza.size >= 3 &&
        (cabeza[0].toInt() and 0xFF) == 0xFF && (cabeza[1].toInt() and 0xFF) == 0xD8 && (cabeza[2].toInt() and 0xFF) == 0xFF

    /** Deja [archivo] en un JPEG derecho a resolución entera, en su sitio. True si quedó en JPEG. */
    fun enSuSitio(archivo: File): Boolean = runCatching {
        val cabeza = archivo.inputStream().use { e -> ByteArray(16).let { b -> b.copyOf(maxOf(0, e.read(b))) } }
        val jpeg = esJpeg(cabeza)
        val exif = runCatching { ExifInterface(archivo.absolutePath) }.getOrNull()
        val orientacion = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val giro = GiroExif.de(orientacion)
        if (jpeg && giro.nada) return true
        val original = BitmapFactory.decodeFile(archivo.absolutePath) ?: return jpeg
        val derecha = if (jpeg) FotoDerecha.girada(original, giro) else original
        val tmp = File(archivo.parentFile, archivo.name + ".tmp")
        tmp.outputStream().use { derecha.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
        if (derecha !== original) derecha.recycle()
        original.recycle()
        // El EXIF de la cámara, con el giro ya hecho.
        runCatching {
            val nuevo = ExifInterface(tmp.absolutePath)
            for (t in ETIQUETAS) exif?.getAttribute(t)?.let { nuevo.setAttribute(t, it) }
            nuevo.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            nuevo.saveAttributes()
        }
        if (!tmp.renameTo(archivo)) { tmp.delete(); return jpeg }
        true
    }.getOrElse { false }
}
