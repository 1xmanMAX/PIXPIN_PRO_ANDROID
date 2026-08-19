package com.forge.pixpin.pin

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Notas de voz: grabarlas, guardarlas y volver a oírlas.
 *
 * ## Por qué existe
 *
 * Hay cosas que se dicen en tres segundos y no se escriben en tres minutos: «acuérdate de
 * llamar al del taller y preguntarle por la pieza esa que faltaba». Escribir eso en un pin
 * cuesta más que la propia gestión, y por eso no se apunta y se olvida.
 *
 * ## Dónde vive el audio
 *
 * En el almacén privado de la aplicación, junto a lo demás que se pinea. **No en la
 * galería ni en descargas**: una nota de voz de doce segundos no es un archivo que uno
 * quiera ver mezclado con su música, y sacarla de ahí es lo que hace que borrarla desde
 * fuera deje el pin apuntando a un hueco.
 *
 * ## El formato
 *
 * `.m4a` con AAC, que es lo que reproduce cualquier cosa sin depender de códecs raros, y
 * a una calidad de voz —no de música—: doce segundos ocupan unos veinte kilobytes. Grabar
 * a calidad de disco una nota que dice «llama al taller» es gastar batería y espacio en
 * los armónicos de una voz.
 */
object Voz {

    /** Dónde se guardan. Junto a las imágenes de los pines, por lo mismo. */
    private fun carpeta(context: Context): File =
        File(context.filesDir, "voz").apply { mkdirs() }

    /** El archivo de una nota nueva. El nombre lleva la hora: ordena solo. */
    fun archivoNuevo(context: Context): File =
        File(carpeta(context), "voz_${System.currentTimeMillis()}.m4a")

    /** Borra la grabación de un pin. Se llama al tirar el pin, no antes. */
    fun borrar(ruta: String?) {
        if (ruta.isNullOrBlank()) return
        runCatching { File(ruta).delete() }
    }

    /**
     * Empieza a grabar en [destino]. Devuelve el grabador, o null si no se pudo.
     *
     * Que no se pueda es un caso **normal**, no un fallo: otra aplicación tiene el
     * micrófono cogido, o el permiso se acaba de retirar. Quien llame se lo dice al
     * usuario y sigue; reventar aquí dejaría la pantalla de grabar colgada.
     */
    fun empezar(context: Context, destino: File): MediaRecorder? = runCatching {
        val grabador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        grabador.setAudioSource(MediaRecorder.AudioSource.MIC)
        grabador.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        grabador.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // Calidad de voz, no de música: ver la nota de arriba.
        grabador.setAudioSamplingRate(MUESTREO)
        grabador.setAudioEncodingBitRate(BITS_POR_SEGUNDO)
        grabador.setOutputFile(destino.absolutePath)
        grabador.prepare()
        grabador.start()
        grabador
    }.getOrNull()

    /**
     * Para de grabar y suelta el micrófono.
     *
     * `stop()` revienta si no llegó a grabarse nada —una pulsación de medio segundo— y en
     * ese caso el archivo queda a medias e ilegible. Se avisa devolviendo false para que
     * quien llame lo borre en vez de crear un pin que no suena.
     */
    fun parar(grabador: MediaRecorder?): Boolean {
        if (grabador == null) return false
        val bien = runCatching { grabador.stop() }.isSuccess
        runCatching { grabador.release() }
        return bien
    }

    /**
     * Lo más alto que ha sonado desde la última vez que se preguntó, de 0 a 32767.
     *
     * ## Por qué se pregunta al grabar y no al pintar la lista
     *
     * La onda de una nota **solo se puede capturar mientras entra el sonido**: el `.m4a` ya
     * escrito es AAC comprimido, y sacarle las amplitudes obliga a descodificarlo entero con
     * un `MediaExtractor` y un `MediaCodec`. Hacer eso al pintar cada fila de la lista de
     * guardados —veinte notas en pantalla, y otra vez al desplazar— dejaría la lista
     * inservible: se arrastraría a tirones y calentaría el móvil para dibujar cincuenta
     * barritas. Se captura una vez, al grabar, donde no cuesta nada, y se guarda con el
     * mensaje.
     *
     * ## Y aquí no se monta ningún hilo
     *
     * `getMaxAmplitude()` devuelve el máximo **desde la llamada anterior**, así que quien
     * pregunte marca el ritmo. La pantalla que graba ya tiene un bucle para el cronómetro:
     * llama a esto cada [MS_ENTRE_PICOS] y va soltando el valor en un
     * [com.forge.pixpin.guardados.Picos]. Un hilo propio aquí dentro sería un hilo más que
     * parar a mano al salir de la pantalla, y el que se olvida es el que se queda girando.
     *
     * Devuelve 0 sin grabador o si el micrófono ya se soltó: un cero es una barra en el
     * suelo, no un fallo.
     */
    fun pico(grabador: MediaRecorder?): Int {
        if (grabador == null) return 0
        return runCatching { grabador.maxAmplitude }.getOrDefault(0).coerceAtLeast(0)
    }

    /**
     * Cada cuánto se le pregunta al micrófono, en milisegundos.
     *
     * Veinte veces por segundo: menos deja la onda a trozos —una sílaba entera cabe en un
     * hueco— y más solo engorda lo que hay que guardar para dibujar las mismas 50 barras.
     */
    const val MS_ENTRE_PICOS = 50L

    /** Cuánto dura una grabación, en milisegundos. Cero si no se puede leer. */
    fun duracion(ruta: String?): Int {
        if (ruta.isNullOrBlank()) return 0
        return runCatching {
            val p = MediaPlayer()
            p.setDataSource(ruta)
            p.prepare()
            val d = p.duration
            p.release()
            d
        }.getOrDefault(0)
    }

    /** Lo menos que puede durar una nota para que valga la pena guardarla. */
    const val MINIMO_MS = 700

    private const val MUESTREO = 22_050
    private const val BITS_POR_SEGUNDO = 32_000
}

/**
 * La duración escrita como se lee: `0:07`, `1:24`.
 *
 * Puro y aparte para poder comprobarlo: los minutos y segundos mal redondeados dan cosas
 * como `0:60`, que se ve enseguida y queda fatal.
 */
fun duracionLegible(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val segundos = (total % 60).toString().padStart(2, '0')
    val minutos = (total / 60) % 60
    val horas = total / 3600
    // Con horas hay que decirlas: sin esto, una grabación de una hora se rotulaba
    // «60:00» y una de dos, «120:00», que no es una duración, es una cuenta sin acabar.
    return if (horas > 0) {
        "$horas:${minutos.toString().padStart(2, '0')}:$segundos"
    } else {
        "$minutos:$segundos"
    }
}

/**
 * Dentro de cuánto cae la próxima vez que den las [hora]:[minuto].
 *
 * Si ya han pasado hoy, es mañana. Es la cuenta que espera cualquiera al poner un
 * recordatorio a una hora: «a las diez» significa las diez que vienen, no las de esta
 * mañana, y sin esto un recordatorio puesto a las once para las diez saltaría en el acto.
 */
fun proximaVezQueSean(ahora: Long, hora: Int, minuto: Int, zona: java.util.TimeZone): Long {
    val c = java.util.Calendar.getInstance(zona)
    c.timeInMillis = ahora
    c.set(java.util.Calendar.HOUR_OF_DAY, hora)
    c.set(java.util.Calendar.MINUTE, minuto)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    if (c.timeInMillis <= ahora) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}
