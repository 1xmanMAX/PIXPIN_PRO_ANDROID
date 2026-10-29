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
    fun enLocal(context: Context): Boolean = MotorVosk.modeloListo(context, idiomaElegido(context))

    private fun ajustes(context: Context) = (context.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes

    /**
     * **En qué idioma se habla**: el que eligió el usuario en Ajustes («es», «en»…) o, si
     * no eligió, el del teléfono («es-PE»). Los tres motores parten de aquí.
     */
    fun idiomaElegido(context: Context): String =
        ajustes(context)?.idiomaDeVoz?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()

    /** El segundo idioma para Whisper, o vacío. Ver [com.forge.pixpin.data.Settings.segundoIdiomaDeVoz]. */
    fun segundoIdioma(context: Context): String = ajustes(context)?.segundoIdiomaDeVoz.orEmpty()

    /** Si Whisper tiene que ponerlo todo en el primer idioma. Ver [com.forge.pixpin.data.Settings.modoDeIdiomas]. */
    fun todoEnUno(context: Context): Boolean = ajustes(context)?.modoDeIdiomas == com.forge.pixpin.data.MODO_TODO_EN_UNO

    /**
     * Un trozo de texto, en qué milisegundo del audio empieza y en cuál acaba.
     *
     * El trozo puede ser una palabra (Vosk), una tanda (Whisper, Qwen3-ASR), una frase (Google)
     * o una línea ya juntada por [agrupar]. [hastaMs] igual a [desdeMs] quiere decir que no se
     * sabe cuándo acaba: se le trata como un instante. Un fin antes del principio (un tiempo
     * tocado a mano) se queda en el principio.
     *
     * **Con su fin desde el 25-oct-2026** (puerto de `Segmento::entre`, `tiempos.rs` del PC):
     * para partir en líneas de 15 s hace falta saber dónde acaba cada trozo, no solo dónde empieza.
     */
    class Segmento(val desdeMs: Int, val texto: String, hastaMs: Int = desdeMs) {
        val hastaMs: Int = maxOf(hastaMs, desdeMs)

        /** El mismo trozo corrido [ms] (un turno que empieza a mitad del audio). */
        fun corrido(ms: Int) = Segmento(desdeMs + ms, texto, hastaMs + ms)

        override fun equals(other: Any?) = other is Segmento && other.desdeMs == desdeMs && other.hastaMs == hastaMs && other.texto == texto
        override fun hashCode() = (desdeMs * 31 + hastaMs) * 31 + texto.hashCode()
        override fun toString() = "Segmento($desdeMs..$hastaMs, \"$texto\")"

        companion object {
            /** Un trozo con principio y fin (`Segmento::entre` del PC). */
            fun entre(desdeMs: Int, hastaMs: Int, texto: String) = Segmento(desdeMs, texto, hastaMs)
        }
    }

    /**
     * **Cuánto abarca una línea del texto con tiempos, como mucho.**
     *
     * **Actualizado el 25-oct-2026, por petición del usuario** (puerto de 7df5532 del PC): antes
     * eran 20 s y una clase de 27 s salió en un solo bloque `[0:00]`. Ahora hay una marca cada
     * 15 s como mucho: ni frase a frase —una lista de renglones sueltos no se lee— ni de corrido.
     * El formato `[m:ss]` es el mismo, así que el PC lo lee igual.
     */
    const val PARRAFO_MS = 15_000

    /** Lo que dura como poco una línea antes de partirla por un punto: por debajo de 5 s, una frase corta se queda con la siguiente. */
    const val PARRAFO_MINIMO_MS = 5_000

    /** Un silencio entre dos trozos de más de esto es buen sitio para partir. */
    private const val PAUSA_MS = 700

    /**
     * **Junta los trozos en líneas de hasta [maxMs]**, partiendo donde mejor se lea (puerto de
     * `tiempos::agrupar` del PC, con los mismos números: el texto sale igual en los dos).
     *
     * Se van sumando trozos a la línea; cuando el siguiente la haría pasar de [maxMs] (de donde
     * empieza la línea a donde acaba el trozo), se parte. No se parte a ciegas delante de ese
     * trozo: se busca, entre los sitios de la línea que dejan al menos [minMs] detrás, **el
     * mejor**: tras un punto (`.`, `?`, `!`, `…`), si no tras un silencio largo, si no tras una
     * coma; a igualdad, el más tardío. Solo si no hay ninguno se parte delante del trozo nuevo.
     * Un trozo que solo ya pasa de [maxMs] va en su línea. Los trozos en blanco no cuentan y
     * ninguna línea sale vacía. Pura: es lo que comparten todos los motores.
     */
    fun agrupar(trozos: List<Segmento>, maxMs: Int, minMs: Int): List<Segmento> {
        val lineas = ArrayList<Segmento>()
        var linea = ArrayList<Segmento>()
        for (s in trozos) {
            val texto = s.texto.trim()
            if (texto.isEmpty()) continue
            val t = Segmento.entre(s.desdeMs, s.hastaMs, texto)
            while (linea.isNotEmpty() && t.hastaMs - linea.first().desdeMs > maxMs) {
                val corte = mejorCorte(linea, minMs) ?: linea.size
                lineas += juntar(linea.subList(0, corte))
                linea = ArrayList(linea.subList(corte, linea.size))
            }
            linea += t
        }
        if (linea.isNotEmpty()) lineas += juntar(linea)
        return lineas
    }

    /** Dónde partir [linea] (el índice del primer trozo de la siguiente), o null si no hay ningún sitio bueno que deje [minMs] detrás. */
    private fun mejorCorte(linea: List<Segmento>, minMs: Int): Int? {
        val desde = linea.firstOrNull()?.desdeMs ?: return null
        var mejor: Pair<Int, Int>? = null
        for (k in 1 until linea.size) {
            if (linea[k].desdeMs - desde < minMs) continue
            val antes = linea[k - 1]
            val nota = when {
                acabaFrase(antes.texto) -> 3
                linea[k].desdeMs - antes.hastaMs >= PAUSA_MS -> 2
                antes.texto.endsWith(',') || antes.texto.endsWith(';') || antes.texto.endsWith(':') -> 1
                else -> 0
            }
            if (nota > 0 && (mejor == null || nota >= mejor.first)) mejor = nota to k
        }
        return mejor?.second
    }

    /** Si un texto acaba una frase: punto, interrogación, exclamación o puntos suspensivos, aunque detrás vaya una comilla o un paréntesis. */
    private fun acabaFrase(texto: String): Boolean {
        val t = texto.trimEnd('"', '\'', ')', '»', '”', '’')
        return t.isNotEmpty() && t.last() in ".?!…。？！"
    }

    /** Una línea de varios trozos: empieza con el primero, acaba con el que más tarde acabe, y el texto va separado por un blanco. */
    private fun juntar(linea: List<Segmento>): Segmento {
        val desde = linea.firstOrNull()?.desdeMs ?: 0
        val hasta = linea.maxOfOrNull { it.hastaMs } ?: desde
        return Segmento.entre(desde, hasta, linea.joinToString(" ") { it.texto })
    }

    /**
     * **Las palabras de una frase de la que solo se sabe principio y fin**, con su tiempo
     * repartido según lo largas que son (`tiempos::repartir` del PC). El reparto por letras es una
     * estimación (se habla más o menos a ritmo constante); el `[m:ss]` de cada línea cae a uno o
     * dos segundos de lo dicho, que para saltar al minuto basta.
     */
    fun repartir(desdeMs: Int, hastaMs: Int, texto: String): List<Segmento> {
        val palabras = texto.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letras = palabras.sumOf { it.codePointCount(0, it.length) }.toLong()
        if (letras == 0L) return emptyList()
        // En Long: una frase de una hora por miles de letras no cabe en un Int.
        val dura = (hastaMs - desdeMs).coerceAtLeast(0).toLong()
        var hechas = 0L
        return palabras.map { p ->
            val a = desdeMs + (dura * hechas / letras).toInt()
            hechas += p.codePointCount(0, p.length)
            val b = desdeMs + (dura * hechas / letras).toInt()
            Segmento.entre(a, b, p)
        }
    }

    /**
     * **Lo que aporta una frase** de la que se sabe principio y fin: ella entera si cabe en una
     * línea ([PARRAFO_MS]), con su minuto exacto; si no, sus palabras con el tiempo repartido
     * ([repartir]), para que [agrupar] la parta en líneas de 15 s.
     */
    fun trozosDeFrase(desdeMs: Int, hastaMs: Int, texto: String): List<Segmento> {
        if (hastaMs - desdeMs <= PARRAFO_MS) {
            val t = texto.trim()
            return if (t.isEmpty()) emptyList() else listOf(Segmento.entre(desdeMs, hastaMs, t))
        }
        return repartir(desdeMs, hastaMs, texto)
    }

    /** Los trozos juntados en líneas de hasta [PARRAFO_MS] (ver [agrupar]): lo que se guarda en [Resultado.Texto.segmentos], uno por línea del texto. */
    fun lineas(trozos: List<Segmento>): List<Segmento> = agrupar(trozos, PARRAFO_MS, PARRAFO_MINIMO_MS)

    /**
     * **El texto con sus tiempos**, una línea por párrafo: `[1:23] lo que se dijo`. Los
     * segmentos se juntan en líneas de hasta [PARRAFO_MS] con [agrupar] (25-oct-2026: antes se
     * juntaban a ciegas en párrafos de 20 s). Ver [tiempoDe] para leerlo de vuelta.
     */
    fun conTiempos(segmentos: List<Segmento>, prefijo: String = ""): String =
        lineas(segmentos).joinToString("\n\n") { "[${marcaDeTiempo(it.desdeMs)}] $prefijo${it.texto}" }

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

    /** En qué acabó: el texto, o por qué no. */
    sealed class Resultado {
        /**
         * [avisos]: cuántos trozos no se entendieron; con alguno, el texto tiene huecos.
         * [segmentos]: el texto por líneas de hasta [PARRAFO_MS], cada una con en qué milisegundo del audio empieza y acaba.
         */
        class Texto(
            val texto: String, val avisos: Int = 0, val segmentos: List<Segmento> = emptyList(),
            /** El texto ya puesto por párrafos con su minuto y, por tramos, con su nombre delante. Null: se arma con [conTiempos]. */
            val porParrafos: String? = null,
            /**
             * **Los trozos tal como los dio el motor**, antes de juntarlos en líneas de 15 s
             * (28-oct-2026): palabras de Vosk, tandas de Whisper. Es lo que usa [AlinearLetra] para
             * ponerle tiempos a una letra: cuanto más finos, mejor cae cada línea.
             */
            val trozos: List<Segmento> = segmentos
        ) : Resultado()
        /** El modelo del idioma no está en el aparato y no se pudo bajar (sin red). */
        object DescargandoIdioma : Resultado()
        class Fallo(val codigo: Int, val detalle: String? = null) : Resultado()
    }

    /** No se entendió nada. */
    const val NADA = -5
    /** El audio no se pudo decodificar. */
    const val NO_SE_LEE = -2
    /** El motor no cargó (teléfono de 32 bits con Whisper, biblioteca rota). */
    const val SIN_MOTOR = -7
    /** Google no tiene el idioma instalado (o lo está bajando). */
    const val SIN_IDIOMA = -8
    /** Cualquier otra cosa: queda en el registro con su traza. */
    const val FALLO_INTERNO = -9

    /**
     * Un trozo del audio que se reconoce por su cuenta: de qué milisegundo a cuál, y qué
     * va delante de su texto (`**Ana:** `). Son los turnos de una conversación.
     */
    class Tramo(val desdeMs: Int, val hastaMs: Int, val prefijo: String = "")

    /**
     * Transcribe [archivo] y llama a [alTerminar] en el hilo principal. Puede llamarse
     * desde cualquier hilo. [avance] recibe, de 0 a 1, cuánto lleva: el primer tercio es
     * bajar el modelo si faltaba, el resto es reconocer. Con [tramos], cada uno se
     * reconoce aparte y sale con su prefijo y sus minutos (los del audio entero).
     */
    fun transcribir(
        context: Context,
        archivo: File,
        idioma: String? = null,
        avance: (Float) -> Unit = {},
        tramos: List<Tramo>? = null,
        alTerminar: (Resultado) -> Unit
    ) {
        val principal = Handler(Looper.getMainLooper())
        val app = context.applicationContext
        Thread {
            // **En primer plano** (28-oct-2026): con la app fuera, sin un servicio delante, Android
            // congela o mata el proceso a media transcripción. Ver [PasarATextoService] y [ColaDeTexto].
            val r = runCatching {
                PasarATextoService.mientras(app, app.getString(com.forge.pixpin.R.string.pasar_a_texto_titulo), descarga = false) { aviso ->
                    transcribirAqui(app, archivo, idioma ?: idiomaElegido(app), { avance(it); aviso(it) }, tramos)
                }
            }.getOrElse { e ->
                android.util.Log.e("PixPinVoz", "transcribir: " + e, e)
                Resultado.Fallo(if (e is UnsatisfiedLinkError || e is NoClassDefFoundError) SIN_MOTOR else FALLO_INTERNO, resumenDe(e))
            }
            principal.post { alTerminar(r) }
        }.start()
    }

    /** Una línea con el nombre y el mensaje de un error, para el aviso. */
    fun resumenDe(e: Throwable): String = (e::class.java.simpleName + ": " + (e.message ?: "")).take(140)

    /** Lo mismo, en este hilo: decodificar, bajar el modelo si falta, reconocer. */
    fun transcribirAqui(context: Context, archivo: File, idioma: String, avance: (Float) -> Unit, tramos: List<Tramo>? = null): Resultado {
        val pcm = File(context.cacheDir, "pcm-${System.nanoTime()}.raw")
        try {
            // Primero con el decodificador que elija el sistema; si falla o no da nada, con
            // el de software de Android, que es el mismo en todos los teléfonos.
            var decodificado = runCatching { pcm.outputStream().buffered().use { decodificar(archivo, it) } }
            if (decodificado.isFailure || pcm.length() <= 0) {
                decodificado.exceptionOrNull()?.let { android.util.Log.w("PixPinVoz", "decodificar " + archivo + " (primer intento)", it) }
                pcm.delete()
                decodificado = runCatching { pcm.outputStream().buffered().use { decodificar(archivo, it, software = true) } }
            }
            decodificado.exceptionOrNull()?.let { android.util.Log.e("PixPinVoz", "decodificar " + archivo, it) }
            if (decodificado.isFailure) return Resultado.Fallo(NO_SE_LEE, decodificado.exceptionOrNull()?.let { resumenDe(it) })
            if (pcm.length() <= 0) return Resultado.Fallo(NO_SE_LEE, "sin pista de audio en " + archivo.name)
            avance(0.05f)
            // **El motor que haya elegido el usuario** en Ajustes: Google, Vosk o Whisper.
            // **Por tramos**: cada uno a su archivo PCM, se reconoce solo, y sus minutos se
            // corren a los del audio entero. Así una conversación vuelve a salir con nombres.
            if (!tramos.isNullOrEmpty()) {
                val lineas = ArrayList<String>()
                val todos = ArrayList<Segmento>()
                var vacios = 0
                for ((k, t) in tramos.withIndex()) {
                    val trozo = File(context.cacheDir, "pcm-tramo-${System.nanoTime()}.raw")
                    try {
                        copiarTramo(pcm, trozo, t.desdeMs, t.hastaMs)
                        val parte = (k.toFloat() / tramos.size) to ((k + 1).toFloat() / tramos.size)
                        val r = reconocerPcm(context, trozo, idioma) { avance(0.1f + 0.9f * (parte.first + (parte.second - parte.first) * it)) }
                        if (r !is Resultado.Texto) { if (r is Resultado.Fallo && r.codigo == NADA) { vacios++; continue } else return r }
                        val corridos = r.segmentos.map { it.corrido(t.desdeMs) }
                        todos += corridos
                        lineas += conTiempos(corridos, prefijo = t.prefijo)
                    } finally {
                        trozo.delete()
                    }
                }
                if (todos.isEmpty()) return Resultado.Fallo(NADA)
                return Resultado.Texto(todos.joinToString(" ") { it.texto }, vacios, todos, porParrafos = lineas.joinToString("\n\n"))
            }
            return reconocerPcm(context, pcm, idioma, avance)
        } finally {
            pcm.delete()
        }
    }

    /** Los bytes de [desdeMs] a [hastaMs] de un PCM (16 bits, mono, [HERCIOS]) a otro archivo. */
    private fun copiarTramo(pcm: File, destino: File, desdeMs: Int, hastaMs: Int) {
        val total = pcm.length()
        val desde = (desdeMs.toLong() * BYTES_POR_SEGUNDO / 1000 and 1L.inv()).coerceIn(0, total)
        val hasta = (hastaMs.toLong() * BYTES_POR_SEGUNDO / 1000 and 1L.inv()).coerceIn(desde, total)
        RandomAccessFile(pcm, "r").use { raf ->
            destino.outputStream().buffered().use { out ->
                raf.seek(desde)
                var quedan = hasta - desde
                val buf = ByteArray(64 * 1024)
                while (quedan > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), quedan).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); quedan -= n
                }
            }
        }
    }

    /**
     * **El motor que de verdad pasa a texto** con lo elegido (puro): Whisper —y Qwen3-ASR, cuyo
     * respaldo es Whisper— solo si sherpa-onnx carga; si no, Vosk. Google y Vosk, tal cual.
     */
    internal fun motorQueVa(elegido: String, whisperVa: Boolean): String =
        if (!whisperVa && (elegido == com.forge.pixpin.data.MOTOR_WHISPER || elegido == com.forge.pixpin.data.MOTOR_QWEN3)) com.forge.pixpin.data.MOTOR_VOSK
        else elegido

    /** Un PCM entero por el motor que haya elegido el usuario: Google, Vosk, Whisper o Qwen3-ASR (con Whisper de reserva). */
    private fun reconocerPcm(context: Context, pcm: File, idioma: String, avance: (Float) -> Unit): Resultado {
        run {
            val elegido = ajustes(context)?.motorDeVoz ?: com.forge.pixpin.data.MOTOR_POR_DEFECTO
            // **Whisper sin sherpa-onnx** (26-oct-2026): Whisper es ya el de fábrica, y en un teléfono
            // de 32 bits no carga; antes eso era «sin motor», ahora se pasa con Vosk, que sí va.
            val motor = motorQueVa(elegido, MotorWhisper.soportado())
            val segmentos = if (motor == com.forge.pixpin.data.MOTOR_GOOGLE) {
                if (!MotorGoogle.disponible(context)) return Resultado.Fallo(SIN_MOTOR, "Google: hace falta Android 13 y su reconocimiento en el dispositivo")
                val etiqueta = when (val i = MotorGoogle.idiomaInstalado(context, idioma)) {
                    is MotorGoogle.Idioma.Vale -> i.etiqueta
                    is MotorGoogle.Idioma.Descargando -> return Resultado.Fallo(SIN_IDIOMA, "Google está bajando el idioma " + i.etiqueta)
                    is MotorGoogle.Idioma.NoHay -> return Resultado.Fallo(SIN_IDIOMA, "Google no tiene instalado " + idioma + "; instalados: " + i.instalados.joinToString(", ").ifBlank { "ninguno" })
                }
                MotorGoogle.reconocer(context, pcm, etiqueta) { avance(0.1f + 0.9f * it) }
                    ?: return Resultado.Fallo(FALLO_INTERNO, "el reconocedor de Google no contestó")
            } else if (motor == com.forge.pixpin.data.MOTOR_WHISPER || motor == com.forge.pixpin.data.MOTOR_QWEN3) {
                // **Qwen3-ASR, si se eligió y toca** (25-oct-2026, docs/voz-qwen3.md del PC): bajado,
                // en un teléfono de 6 GB o más y con un idioma que sabe. Si no toca, o falla, Whisper,
                // que es el de reserva. Si Qwen3-ASR va a transcribir, no se baja Whisper.
                val deQwen3 = if (motor == com.forge.pixpin.data.MOTOR_QWEN3)
                    com.forge.pixpin.guardados.qwen3.MotorQwen3.reconocerSiToca(context, pcm, idioma, segundoIdioma(context), todoEnUno(context)) { avance(0.05f + 0.95f * it) }
                else null
                deQwen3 ?: run {
                    if (!MotorWhisper.soportado()) return Resultado.Fallo(SIN_MOTOR)
                    if (!MotorWhisper.modeloListo(context)) {
                        MotorWhisper.asegurarModelo(context) { avance(0.05f + 0.3f * it) } ?: return Resultado.DescargandoIdioma
                    }
                    MotorWhisper.reconocer(context, pcm, idioma, segundoIdioma(context), todoEnUno(context), avance = { avance(0.35f + 0.65f * it) })
                }
            } else {
                if (!MotorVosk.modeloListo(context, idioma)) {
                    MotorVosk.asegurarModelo(context, idioma) { avance(0.05f + 0.3f * it) } ?: return Resultado.DescargandoIdioma
                }
                MotorVosk.reconocer(context, pcm, idioma) { avance(0.35f + 0.65f * it) }
            } ?: return Resultado.DescargandoIdioma
            if (segmentos.isEmpty()) return Resultado.Fallo(NADA)
            // **Los segmentos que se guardan son ya las líneas del texto** (25-oct-2026, como
            // `Transcripcion.segmentos` del PC): palabras de Vosk o tandas de Whisper juntadas en
            // líneas de hasta 15 s. Juntar lo ya juntado no cambia nada, así que [conTiempos] y los
            // turnos dan lo mismo.
            return Resultado.Texto(segmentos.joinToString(" ") { it.texto }, 0, lineas(segmentos), trozos = segmentos)
        }
    }

    // ---- Cortes por frases (para quien quiera trocear un PCM; Vosk no los necesita) ----

    /**
     * **Frases seguidas juntas en tandas de hasta [segundos]**, cada tanda un tramo
     * contiguo de audio (de donde empieza su primera frase a donde acaba la última, con
     * las pausas de en medio). A Whisper y a Google les va mejor un tramo largo que una
     * frase suelta: Whisper rellena cada trozo hasta diez segundos de silencio y con
     * trozos de dos segundos se pasaba la vida rellenando —y con tan poco contexto
     * inventaba—, y Google abre una sesión por tramo, que cuesta lo suyo. Una frase que
     * por sí sola pase de [segundos] va sola.
     */
    internal fun agrupar(trozos: List<LongRange>, segundos: Int): List<List<LongRange>> {
        val tope = segundos.toLong() * BYTES_POR_SEGUNDO
        val salida = ArrayList<List<LongRange>>()
        var tanda = ArrayList<LongRange>()
        for (t in trozos) {
            if (tanda.isNotEmpty() && t.last - tanda.first().first + 1 > tope) { salida += tanda; tanda = ArrayList() }
            tanda += t
        }
        if (tanda.isNotEmpty()) salida += tanda
        return salida
    }

    /**
     * Lo que dura como mucho una tanda de Whisper o de Qwen3-ASR: **cada tanda es una línea
     * `[m:ss]`** del texto, y el usuario pidió una marca cada 15 s como mucho (25-oct-2026,
     * `cortes::TANDA_SEGUNDOS` del PC). Antes eran 20.
     */
    const val TANDA_SEGUNDOS = 15

    /**
     * Desde cuántos segundos una tanda se cierra en la primera pausa larga ([PAUSA_DE_TANDA_MS])
     * en vez de esperar al tope: entre 8 y 15 s se prefiere partir donde se calló, que es donde
     * suele acabar una frase.
     */
    const val TANDA_MINIMA_SEGUNDOS = 8

    /** Lo que cuenta como pausa para cerrar una tanda de más de [TANDA_MINIMA_SEGUNDOS]: más que un respiro. */
    const val PAUSA_DE_TANDA_MS = 500

    /**
     * Como [agrupar], pero una tanda que ya dura [minimo] segundos o más **se cierra en la
     * primera pausa** de [pausas] (bytes, de [pausasLargas]) que cruce (`cortes::agrupar_en_pausas`
     * del PC). La pausa tiene que caer entre la tanda y la frase nueva: una de dentro de la tanda
     * (de antes de llegar al mínimo) no cuenta. Con [minimo] cero, ninguna tanda cruza una pausa.
     */
    internal fun agruparEnPausas(trozos: List<LongRange>, segundos: Int, minimo: Int, pausas: List<Long>): List<List<LongRange>> {
        val tope = segundos.toLong() * BYTES_POR_SEGUNDO
        val minimoBytes = minimo.toLong() * BYTES_POR_SEGUNDO
        val salida = ArrayList<List<LongRange>>()
        var tanda = ArrayList<LongRange>()
        for (t in trozos) {
            if (tanda.isNotEmpty()) {
                val desde = tanda.first().first
                val hasta = tanda.last().last + 1
                val cruza = hasta - desde >= minimoBytes && pausas.any { it >= hasta && it <= t.first }
                if (t.last + 1 - desde > tope || cruza) { salida += tanda; tanda = ArrayList() }
            }
            tanda += t
        }
        if (tanda.isNotEmpty()) salida += tanda
        return salida
    }

    /**
     * **Las tandas de una nota para escribir una línea cada 15 s** (`cortes::tandas_de_lineas`
     * del PC): frases ([cortes]) juntas hasta [TANDA_SEGUNDOS], cerrando en una pausa de
     * [PAUSA_DE_TANDA_MS] en cuanto pasan de [TANDA_MINIMA_SEGUNDOS]. Lo usan Whisper y Qwen3-ASR;
     * cada tanda, con su principio y su fin, es una línea.
     */
    internal fun tandasDeLineas(pcm: File): List<List<LongRange>> {
        val s = silencios(pcm)
        return agruparEnPausas(cortes(pcm, s), TANDA_SEGUNDOS, TANDA_MINIMA_SEGUNDOS, pausasLargas(s, PAUSA_DE_TANDA_MS))
    }

    /**
     * Lo que dura como mucho una tanda de **una canción** (29-oct-2026, `TANDA_CANCION_SEGUNDOS` del
     * PC): frases juntas hasta 8 s, y no 15 como una nota, para que el tiempo repartido de cada palabra
     * caiga cerca de cuando se canta.
     */
    const val TANDA_CANCION_SEGUNDOS = 8

    /** **Las tandas de una canción** (`oir_cancion_pcm` del PC): frases ([cortes]) juntas hasta [TANDA_CANCION_SEGUNDOS]. */
    internal fun tandasDeCancion(pcm: File): List<List<LongRange>> = agrupar(cortes(pcm), TANDA_CANCION_SEGUNDOS)

    /**
     * **Dónde hay una pausa de [ms] o más** (bytes): la mitad de cada silencio largo, que es justo
     * donde [cortes] parte (`cortes::pausas_largas` del PC).
     */
    internal fun pausasLargas(pcm: File, ms: Int): List<Long> = pausasLargas(silencios(pcm), ms)

    private fun pausasLargas(s: Silencios?, ms: Int): List<Long> =
        if (s == null) emptyList() else s.tramos.filter { (it.last + 1 - it.first) * VENTANA_MS >= ms }.map { s.mitad(it) }

    /** El milisegundo en que empieza un tramo (bytes de PCM de 16 bits a [HERCIOS]). */
    internal fun msDe(byte: Long): Int = (byte * 1000 / BYTES_POR_SEGUNDO).toInt()

    private val JUNK = listOf(
        // Lo que Whisper suelta cuando no oye nada: los créditos de subtítulos con que se entrenó.
        "subtítulos realizados por", "subtitulos realizados por", "amara.org", "subtítulos por", "gracias por ver",
        "suscríbete", "suscribete", "thanks for watching", "thank you for watching"
    )

    /**
     * **Si un trozo de texto parece de verdad** y no una invención del modelo. Whisper,
     * cuando le llega ruido o un silencio, se saca frases de la nada: en otros alfabetos
     * (chino, cirílico, árabe…) aunque se le haya dicho el idioma, o los «Subtítulos
     * realizados por la comunidad de Amara.org» de los vídeos con que se entrenó. Para un
     * idioma de alfabeto latino, se tira lo que traiga letras de otro alfabeto, lo que sea
     * una de esas coletillas, y lo que repita la misma palabra sin parar.
     */
    internal fun creible(texto: String, idioma: String): Boolean {
        val t = texto.trim()
        if (t.isEmpty()) return false
        val lengua = idioma.substringBefore('-').lowercase()
        if (lengua in LATINOS) {
            var letras = 0; var raras = 0
            for (c in t) {
                if (!c.isLetter()) continue
                letras++
                val b = Character.UnicodeScript.of(c.code)
                if (b != Character.UnicodeScript.LATIN && b != Character.UnicodeScript.COMMON) raras++
            }
            if (raras > 0 && raras * 5 >= letras) return false
        }
        val bajo = t.lowercase()
        if (JUNK.any { bajo.contains(it) }) return false
        // «no no no no no no no no»: la misma palabra ocho veces seguidas o más.
        val palabras = bajo.split(' ').filter { it.isNotEmpty() }
        if (palabras.size >= 8) {
            var seguidas = 1
            for (i in 1 until palabras.size) {
                seguidas = if (palabras[i] == palabras[i - 1]) seguidas + 1 else 1
                if (seguidas >= 8) return false
            }
        }
        return true
    }

    private val LATINOS = setOf("es", "en", "pt", "fr", "de", "it", "ca", "nl", "pl", "ro", "sv", "da", "no", "fi", "cs", "hu", "tr", "id", "ms", "vi", "eu", "gl")

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
    internal fun cortes(pcm: File): List<LongRange> = cortes(pcm, silencios(pcm))

    private fun cortes(pcm: File, s: Silencios?): List<LongRange> {
        val total = pcm.length() and 1L.inv()
        if (total <= 0) return emptyList()
        if (s == null) return listOf(0 until total)
        val puntos = s.tramos.map { s.mitad(it) }
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

    /**
     * Los silencios de más de un cuarto de segundo de un PCM, en ventanas de [VENTANA_MS] (de la
     * primera callada a la última, incluidas). Null si el archivo no llega a una ventana.
     */
    private class Silencios(val ventana: Int, val tramos: List<IntRange>) {
        /** Dónde se corta un silencio: en su mitad, redondeada a la ventana (bytes). */
        fun mitad(t: IntRange): Long = ((t.first + t.last + 1) / 2).toLong() * ventana
    }

    /**
     * **Los silencios de una nota**: se mide la energía cada 20 ms y se llama silencio a lo que
     * queda por debajo de una fracción de la energía típica (el percentil 60). Separado de
     * [cortes] el 25-oct-2026 para que [pausasLargas] los reuse sin leer el archivo dos veces.
     */
    private fun silencios(pcm: File): Silencios? {
        val total = pcm.length() and 1L.inv()
        if (total <= 0) return null
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
        if (energias.isEmpty()) return null
        val ordenadas = energias.sorted()
        val tipica = ordenadas[(ordenadas.size * 0.6).toInt().coerceIn(0, ordenadas.size - 1)]
        val umbral = maxOf(UMBRAL_MINIMO, tipica * PARTE_DE_SILENCIO)
        val minimoDeSilencio = SILENCIO_MS / VENTANA_MS
        val tramos = ArrayList<IntRange>()
        var calladasDesde = -1
        for ((k, e) in energias.withIndex()) {
            val callada = e < umbral
            if (callada && calladasDesde < 0) calladasDesde = k
            if (!callada && calladasDesde >= 0) {
                if (k - calladasDesde >= minimoDeSilencio) tramos += calladasDesde until k
                calladasDesde = -1
            }
        }
        return Silencios(ventana, tramos)
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
    internal fun decodificar(archivo: File, salida: OutputStream, software: Boolean = false) {
        val extractor = MediaExtractor()
        extractor.setDataSource(archivo.absolutePath)
        var pista = -1
        var formato: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { pista = i; formato = f; break }
        }
        if (pista < 0 || formato == null) { extractor.release(); throw IllegalStateException("sin pista de audio (" + extractor.trackCount + " pistas)") }
        extractor.selectTrack(pista)
        val mime = formato.getString(MediaFormat.KEY_MIME)!!
        val codec = if (software) {
            // El de software de Android (c2.android / OMX.google), que no depende del fabricante.
            val nombre = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } && (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) }
                ?.name
            if (nombre != null) MediaCodec.createByCodecName(nombre) else MediaCodec.createDecoderByType(mime)
        } else MediaCodec.createDecoderByType(mime)
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
