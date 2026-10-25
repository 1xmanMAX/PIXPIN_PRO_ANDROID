package com.forge.pixpin.guardados.qwen3

import com.forge.pixpin.guardados.Transcriptor
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Qwen3-ASR-0.6B sobre el ONNX Runtime del APK**: cargar los tres modelos y transcribir
 * (25-oct-2026, puerto de `crates/pixpin-voz/src/qwen3/motor.rs` y `qwen3/mod.rs` del PC).
 *
 * Un motor **opcional** de voz a texto para notas y grabaciones de clases o reuniones. Whisper
 * sigue siendo **el de reserva**: con Qwen3-ASR elegido pero sin bajar, en un teléfono sin
 * memoria, con «todo en el primero» (traducir, que Qwen no hace) o si falla, se pasa con Whisper
 * ([com.forge.pixpin.guardados.Transcriptor]). En el banco del PC (FLEURS es_419) deja el WER en
 * la mitad o menos que Whisper base: 5,8 / 8,3 / 37,7 % en limpio / reunión / aula frente a
 * 11,8 / 22,5 / 94,6 %.
 *
 * El decodificador es el de `offline-recognizer-qwen3-asr-impl.cc` de sherpa-onnx (Apache-2.0)
 * —que el `libsherpa-onnx-jni.so` del APK ya trae, pero sin estas dos ideas—, con los dos
 * cambios medidos en el PC (`docs/voz-qwen3.md`):
 *
 * - **La entrada del decoder va en trozos de 64 tokens** (con la caché ya rellena de los
 *   anteriores) y no de una vez. El decoder int8 cuantiza cada activación con una sola escala
 *   por llamada: con los ~280 tokens de una tanda juntos, la escala la marcan los vectores de
 *   audio y el texto pierde precisión (WER 7,6 % de una vez frente a 6,5 % en trozos de 64). De
 *   paso, los logits de la entrada bajan de ~170 MB a ~40 MB, que en un teléfono importa.
 * - **La caché de claves y valores mide lo justo** para la tanda (guion + tope de tokens) en vez
 *   de un máximo fijo.
 *
 * Y la tercera, de los cortes: **tandas de hasta 15 s por los silencios, una línea `[m:ss]` cada una** ([GuionQwen3.tandas]).
 *
 * **Memoria**: ~1,5 GB de pico (medido en el PC con la misma exportación). Por eso solo se ofrece
 * en teléfonos con 6 GB o más ([seOfrece]), corre en un servicio en primer plano
 * ([com.forge.pixpin.guardados.PasarATextoService]) y con 4 hilos de CPU: NNAPI y XNNPACK no
 * ayudan con `MatMulInteger`, y los núcleos ARM con `sdot` van bien con int8.
 */
object MotorQwen3 {

    /** Cabezas de clave/valor y su tamaño en el decoder de 0,6B (`[.., 8, 128]`). */
    private const val CABEZAS_KV = 8
    private const val DIM_CABEZA = 128
    /** Lo que ocupa una posición de la caché, por capa (claves o valores). */
    private const val POR_POSICION = CABEZAS_KV * DIM_CABEZA
    /** Lo ancho de cada vector de audio que sale del encoder. */
    private const val ANCHO_AUDIO = 1024
    /** Tokens por llamada al decoder al leer el guion (ver arriba). */
    const val TROZO_DE_ENTRADA = 64

    /** Los nombres de entrada que se esperan del decoder, en orden. */
    private val ENTRADAS = listOf("input_ids", "audio_features", "attention_mask", "cache_position")

    // ---- Lo bajado: `filesDir/qwen3-asr/` --------------------------------------------------

    /** `filesDir/qwen3-asr/`. */
    fun carpeta(context: android.content.Context): File = File(context.filesDir, "qwen3-asr")

    /** Si un fichero está entero (con su tamaño exacto; el sha256 se comprobó al bajarlo). */
    private fun estaEntero(p: File, f: CatalogoQwen3.Fichero) = p.isFile && p.length() == f.bytes

    /** Si los seis ficheros están bajados y enteros. */
    fun modeloListo(carpeta: File): Boolean = CatalogoQwen3.FICHEROS.all { estaEntero(File(carpeta, it.nombre), it) }

    /** Si hay algo bajado (aunque sea a medias): para ofrecer «Borrar». */
    fun algoBajado(carpeta: File): Boolean = carpeta.listFiles()?.isNotEmpty() == true

    /** Lo que falta por bajar, en MB. */
    fun megasABajar(carpeta: File): Long =
        (CatalogoQwen3.FICHEROS.filter { !estaEntero(File(carpeta, it.nombre), it) }.sumOf { it.bytes } + 999_999) / 1_000_000

    /** Borra lo bajado (Ajustes › «Borrar Qwen3-ASR»). El runtime se queda: es el de Whisper. */
    fun borrar(carpeta: File) {
        carpeta.deleteRecursively()
    }

    /** sha256 de un fichero, en hexadecimal. */
    fun sha256De(p: File): String {
        val h = MessageDigest.getInstance("SHA-256")
        p.inputStream().use { e ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = e.read(buf); if (n < 0) break; h.update(buf, 0, n) }
        }
        return h.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Si se ofrece y si toca ------------------------------------------------------------

    /** La memoria del teléfono en MB (`ActivityManager.MemoryInfo.totalMem`). */
    fun memoriaTotalMb(context: android.content.Context): Long {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return 0
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /**
     * **Si se ofrece en un teléfono así** (puro): de 64 bits (el runtime del APK es `arm64-v8a`)
     * y con 6 GB o más ([CatalogoQwen3.RAM_MINIMA_MB]). Por debajo, Android mataría la app o lo
     * que haya detrás para dejarle sitio a los ~1,5 GB que pide.
     */
    fun seOfrece(memoriaTotalMb: Long, es64Bits: Boolean): Boolean = es64Bits && memoriaTotalMb >= CatalogoQwen3.RAM_MINIMA_MB

    fun seOfrece(context: android.content.Context): Boolean =
        seOfrece(memoriaTotalMb(context), android.os.Process.is64Bit())

    /** Cómo corre ONNX Runtime con Qwen3-ASR. */
    data class Reparto(val hilos: Int, val preempaquetar: Boolean)

    /**
     * **El reparto según el teléfono** (puro): 4 hilos (los núcleos grandes; con más, los pequeños
     * frenan a los grandes en cada operación) y, con 8 GB o más, los pesos preempaquetados (~1,5×
     * más rápido y ~200 MB más). Con 6, sin preempaquetar: el modo cuidadoso del PC.
     */
    fun reparto(memoriaTotalMb: Long, nucleos: Int): Reparto =
        Reparto(hilos = nucleos.coerceIn(1, 4), preempaquetar = memoriaTotalMb >= CatalogoQwen3.RAM_HOLGADA_MB)

    /** El código corto de un idioma (`es-PE` → `es`). */
    private fun lengua(codigo: String) = codigo.split('-', '_').first().lowercase()

    /**
     * **Si toca Qwen3-ASR** con lo elegido y lo que hay (puro, `toca_qwen3` del PC): elegido,
     * bajado, ofrecido en este teléfono y con el idioma en su lista. «Todo en el primero» con dos
     * idiomas pide traducir, que Qwen3-ASR no hace: eso sigue con Whisper.
     */
    fun toca(elegido: Boolean, bajado: Boolean, seOfrece: Boolean, idioma: String, segundo: String, todoEnUno: Boolean): Boolean {
        val otro = segundo.takeIf { it.isNotBlank() }?.let { lengua(it) }?.takeIf { it != lengua(idioma) }
        val traducir = otro != null && todoEnUno
        return elegido && bajado && seOfrece && GuionQwen3.sabe(idioma) && !traducir
    }

    // ---- El modelo cargado -----------------------------------------------------------------

    /** Lo que sale de una tanda: el texto y el idioma que dijo el modelo, si se le dejó adivinar. */
    data class Oido(val texto: String, val lengua: String?)

    /**
     * **El modelo cargado**: tres sesiones, el tokenizador y el guion. Pesa (~1 GB de pesos y la
     * caché de cada tanda): se carga para una nota y se suelta al acabar, como en el PC; cargarlo
     * son unos segundos frente a los minutos de pasar una grabación.
     */
    class Cargado private constructor(
        private val ort: OrtPorJna,
        private val conv: OrtPorJna.Sesion,
        private val encoder: OrtPorJna.Sesion,
        private val decoder: OrtPorJna.Sesion,
        private val fichas: FichasQwen3.Fichas,
        private val guion: GuionQwen3.Guion,
        private val capas: Int
    ) : AutoCloseable {

        val versionDelRuntime: String get() = ort.version

        override fun close() {
            conv.close(); encoder.close(); decoder.close()
        }

        /**
         * **Pasa a texto un PCM** (16 bits, mono, 16 kHz, en un fichero), tanda a tanda.
         *
         * Con un idioma (o «todo en el primero») se le dice el primero. Con dos y «cada trozo como
         * se dijo», el modelo adivina el de cada tanda; si dice uno que no es de los dos, o el
         * texto no se sostiene en él, la tanda se rehace con el primero (la misma red que Whisper).
         * Un segmento por tanda con su principio y su fin; lista vacía si no se entendió nada.
         */
        fun transcribir(pcm: File, idioma: String, segundo: String = "", todoEnUno: Boolean = false, avance: (Float) -> Unit = {}): List<Transcriptor.Segmento> {
            val principal = lengua(idioma)
            val nombre = GuionQwen3.nombreDelIdioma(principal) ?: throw IllegalArgumentException("Qwen3-ASR no sabe $idioma")
            val otro = segundo.takeIf { it.isNotBlank() }?.let { lengua(it) }?.takeIf { it != principal && GuionQwen3.sabe(it) }
            val adivinar = otro != null && !todoEnUno
            val tandas = GuionQwen3.tandas(pcm)
            val total = tandas.size.coerceAtLeast(1).toFloat()
            val salida = ArrayList<Transcriptor.Segmento>()
            RandomAccessFile(pcm, "r").use { raf ->
                for ((n, tanda) in tandas.withIndex()) {
                    val trozo = muestras(raf, tanda)
                    if (GuionQwen3.callada(trozo)) { avance((n + 1) / total); continue }
                    val audio = FloatArray(trozo.size) { trozo[it] / 32_768f }
                    val parte: (Float) -> Unit = { f -> avance((n + f.coerceIn(0f, 1f)) / total) }
                    var lenguaDicha = principal
                    val oido = if (adivinar) {
                        val o = unaTanda(audio, null, parte)
                        val l = o.lengua
                        if (l != null && (l == principal || l == otro) && Transcriptor.creible(o.texto, l)) { lenguaDicha = l; o }
                        else unaTanda(audio, nombre, parte)
                    } else unaTanda(audio, nombre, parte)
                    val texto = oido.texto.trim()
                    if (texto.isNotEmpty() && Transcriptor.creible(texto, lenguaDicha)) salida += Transcriptor.Segmento.entre(Transcriptor.msDe(tanda.desde), Transcriptor.msDe(tanda.hasta), texto)
                    avance((n + 1) / total)
                }
            }
            avance(1f)
            return salida
        }

        private fun muestras(raf: RandomAccessFile, t: GuionQwen3.Tanda): ShortArray {
            val largo = ((t.hasta - t.desde) and 1L.inv()).toInt()
            val bytes = ByteArray(largo)
            raf.seek(t.desde); raf.readFully(bytes)
            return ShortArray(largo / 2) { ((bytes[2 * it].toInt() and 0xFF) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
        }

        /** La única salida que interesa de una sesión; las demás se sueltan. */
        private fun una(v: List<OrtPorJna.Valor>, paso: String): OrtPorJna.Valor {
            if (v.isEmpty()) throw OrtPorJna.ErrorOrt(paso, "no devolvió nada")
            v.drop(1).forEach { it.close() }
            return v[0]
        }

        /** **Una tanda**: mel → frente → encoder → decoder voraz. [nombre] es el idioma como lo entiende Qwen (`Spanish`) o null. */
        fun unaTanda(audio: FloatArray, nombre: String?, avance: (Float) -> Unit = {}): Oido {
            val vacio = Oido("", null)
            val datosMel = MelQwen3.logMel(audio)
            val marcos = datosMel.size / MelQwen3.BANDAS
            if (marcos < 2) return vacio
            var vectores = FloatArray(0)
            var n = 0
            ort.tensorF32(datosMel, longArrayOf(1, marcos.toLong(), MelQwen3.BANDAS.toLong())).use { entrada ->
                una(conv.correr(listOf(entrada), "correr el frente"), "correr el frente").use { frente ->
                    val forma = frente.forma()
                    val filas = forma.getOrNull(1)?.coerceAtLeast(0)?.toInt() ?: 0
                    if (forma.size != 3 || filas == 0) return vacio
                    val validas = minOf(GuionQwen3.tokensDeAudio(marcos), filas)
                    ort.tensorBool(BooleanArray(filas) { it < validas }, longArrayOf(1, filas.toLong())).use { mascara ->
                        una(encoder.correr(listOf(frente, mascara), "correr el encoder"), "correr el encoder").use { salida ->
                            vectores = salida.comoF32()
                            n = minOf(validas, GuionQwen3.filasConVoz(vectores, ANCHO_AUDIO))
                        }
                    }
                }
            }
            if (n == 0 || vectores.size < n * ANCHO_AUDIO) return vacio
            avance(0.2f)

            val fuente = guion.fuente(n, nombre)
            val tope = GuionQwen3.topeDeTokens(audio.size)
            val largo = fuente.size + tope
            val escritos = ArrayList<Long>()
            Cache(ort, capas, largo).use { cache ->
                // El audio entero, para las llamadas sin `<|audio_pad|>` (el grafo lo pide siempre, aunque no lo use).
                ort.tensorF32(vectores, longArrayOf(1, n.toLong(), ANCHO_AUDIO.toLong()), 0, n * ANCHO_AUDIO).use { todos ->
                    // La entrada, en trozos: cada uno con los vectores de sus pads.
                    var pos = 0
                    var usados = 0
                    var siguiente = 0L
                    while (pos < fuente.size) {
                        val trozo = fuente.copyOfRange(pos, minOf(pos + TROZO_DE_ENTRADA, fuente.size))
                        val k = trozo.count { it == guion.pad }
                        siguiente = if (k > 0) {
                            ort.tensorF32(vectores, longArrayOf(1, k.toLong(), ANCHO_AUDIO.toLong()), usados * ANCHO_AUDIO, k * ANCHO_AUDIO).use { propios ->
                                paso(trozo, pos, propios, cache)
                            }
                        } else paso(trozo, pos, todos, cache)
                        pos += trozo.size
                        usados += k
                    }
                    avance(0.3f)

                    val esperados = maxOf(audio.size / MelQwen3.HERCIOS.toFloat() * 3.5f, 1f)
                    var cur = fuente.size
                    while (!guion.esFin(siguiente) && escritos.size < tope && cur < largo) {
                        escritos += siguiente
                        val l = GuionQwen3.repiteAlFinal(escritos)
                        if (l != null) { while (escritos.size > l) escritos.removeAt(escritos.size - 1); break }
                        siguiente = paso(longArrayOf(siguiente), cur, todos, cache)
                        cur++
                        avance(0.3f + 0.7f * minOf(escritos.size / esperados, 0.99f))
                    }
                }
            }
            avance(1f)
            val (texto, lengua) = if (nombre != null) escritos to null else guion.sinPrefijo(escritos, fichas)
            return Oido(fichas.texto(texto).trim(), lengua)
        }

        /**
         * **Un paso del decoder**: [ids] desde la posición [desde], con la caché rellena hasta ahí.
         * Escribe sus claves y valores en su sitio y devuelve el token elegido tras el último.
         */
        private fun paso(ids: LongArray, desde: Int, audio: OrtPorJna.Valor, cache: Cache): Long {
            val s = ids.size
            if (desde + s > cache.largo) throw OrtPorJna.ErrorOrt("correr el decoder", "la caché se quedó corta")
            val tIds = ort.tensorI64(ids, longArrayOf(1, s.toLong()))
            val tMascara = ort.tensorI64(LongArray(s) { 1 }, longArrayOf(1, s.toLong()))
            val tPos = ort.tensorI64(LongArray(s) { (desde + it).toLong() }, longArrayOf(s.toLong()))
            val entradas = ArrayList<OrtPorJna.Valor>(4 + 2 * capas)
            entradas += listOf(tIds, audio, tMascara, tPos)
            for ((k, v) in cache.capas) { entradas += k; entradas += v }
            val salidas = try {
                decoder.correr(entradas, "correr el decoder")
            } finally {
                tIds.close(); tMascara.close(); tPos.close()
            }
            try {
                if (salidas.size != 1 + 2 * capas) throw OrtPorJna.ErrorOrt("correr el decoder", "${salidas.size} salidas")
                val bytes = s.toLong() * POR_POSICION * 4
                for ((i, kv) in cache.capas.withIndex()) {
                    for ((destino, delta) in listOf(kv.first to salidas[1 + 2 * i], kv.second to salidas[2 + 2 * i])) {
                        val n = delta.elementos()
                        if (n != s.toLong() * POR_POSICION) throw OrtPorJna.ErrorOrt("correr el decoder", "delta de $n valores para $s tokens")
                        // De la salida a su sitio en la caché, de memoria nativa a memoria nativa.
                        destino.datos().getByteBuffer(desde.toLong() * POR_POSICION * 4, bytes)
                            .put(delta.datos().getByteBuffer(0, bytes))
                    }
                }
                val logits = salidas[0]
                val forma = logits.forma()
                val vocab = forma.lastOrNull()?.toInt() ?: 0
                val total = logits.elementos()
                if (vocab == 0 || total < vocab) throw OrtPorJna.ErrorOrt("correr el decoder", "logits con forma ${forma.toList()}")
                // Solo la última fila: con 64 tokens de entrada son 40 MB de logits que no hace falta traer.
                val fila = logits.datos().getFloatArray((total - vocab) * 4, vocab)
                return GuionQwen3.elegir(fila)
            } finally {
                salidas.forEach { it.close() }
            }
        }

        companion object {
            /** **Carga los seis ficheros** de [carpeta] con el runtime [ort]. Lanza si algo no cuadra. */
            fun cargar(ort: OrtPorJna, carpeta: File, reparto: Reparto): Cargado {
                fun leer(i: Int) = File(carpeta, CatalogoQwen3.FICHEROS[i].nombre).readText()
                val ilegible = { IllegalStateException("Qwen3-ASR ilegible en $carpeta") }
                val fichas = FichasQwen3.Fichas.leer(leer(CatalogoQwen3.VOCAB), leer(CatalogoQwen3.CONFIG)) ?: throw ilegible()
                // Las fusiones solo hacen falta para el guion: se sueltan al salir.
                val guion = GuionQwen3.Guion.nuevo(fichas, FichasQwen3.Fusiones.leer(leer(CatalogoQwen3.MERGES))) ?: throw ilegible()
                fun abrir(i: Int) = ort.abrir(File(carpeta, CatalogoQwen3.FICHEROS[i].nombre).absolutePath, reparto.hilos, reparto.preempaquetar)
                val abiertas = ArrayList<OrtPorJna.Sesion>()
                try {
                    val conv = abrir(CatalogoQwen3.CONV).also { abiertas += it }
                    val encoder = abrir(CatalogoQwen3.ENCODER).also { abiertas += it }
                    val decoder = abrir(CatalogoQwen3.DECODER).also { abiertas += it }
                    val capas = capasDe(decoder.entradas, decoder.salidas) ?: throw ilegible()
                    if (conv.entradas.size != 1 || conv.salidas.size != 1 || encoder.entradas.size != 2 || encoder.salidas.size != 1) throw ilegible()
                    return Cargado(ort, conv, encoder, decoder, fichas, guion, capas)
                } catch (e: Throwable) {
                    abiertas.forEach { runCatching { it.close() } }
                    throw e
                }
            }
        }
    }

    /** La caché de claves y valores de todas las capas, `[1, largo, 8, 128]`, a ceros. */
    private class Cache(ort: OrtPorJna, capas: Int, val largo: Int) : AutoCloseable {
        val capas: List<Pair<OrtPorJna.Valor, OrtPorJna.Valor>>

        init {
            val forma = longArrayOf(1, largo.toLong(), CABEZAS_KV.toLong(), DIM_CABEZA.toLong())
            val celdas = largo.toLong() * POR_POSICION
            val v = ArrayList<Pair<OrtPorJna.Valor, OrtPorJna.Valor>>(capas)
            try {
                repeat(capas) { v += ort.tensorF32Ceros(celdas, forma) to ort.tensorF32Ceros(celdas, forma) }
            } catch (e: Throwable) {
                v.forEach { it.first.close(); it.second.close() }
                throw e
            }
            this.capas = v
        }

        override fun close() = capas.forEach { it.first.close(); it.second.close() }
    }

    /**
     * **Cuántas capas tiene el decoder**, comprobando que sus entradas y salidas son las de la
     * exportación de sherpa-onnx y están en orden (`cache_key_0`, `cache_value_0`…; `logits`,
     * `key_delta_0`…): se le pasan por posición.
     */
    fun capasDe(entradas: List<String>, salidas: List<String>): Int? {
        if (entradas.size < 6 || (entradas.size - 4) % 2 != 0) return null
        val capas = (entradas.size - 4) / 2
        if (salidas.size != 1 + 2 * capas || salidas[0] != "logits") return null
        val bien = ENTRADAS.indices.all { ENTRADAS[it] == entradas[it] } && (0 until capas).all { i ->
            entradas[4 + 2 * i] == "cache_key_$i" && entradas[5 + 2 * i] == "cache_value_$i" &&
                salidas[1 + 2 * i] == "key_delta_$i" && salidas[2 + 2 * i] == "value_delta_$i"
        }
        return if (bien) capas else null
    }

    // ---- En el teléfono ----------------------------------------------------------------------

    /**
     * **Pasa a texto con Qwen3-ASR, si toca**, en el servicio en primer plano. Null si no toca
     * (no elegido, sin bajar, sin memoria, un idioma que no sabe, traducir) **o si falla** (queda
     * en el registro): entonces quien llama pasa con Whisper, que es el de reserva. Lista vacía:
     * Qwen3-ASR no entendió nada (Whisper no lo haría mejor). Hilo de fondo.
     */
    fun reconocerSiToca(
        context: android.content.Context, pcm: File, idioma: String, segundo: String, todoEnUno: Boolean, avance: (Float) -> Unit
    ): List<Transcriptor.Segmento>? {
        val dir = carpeta(context)
        if (!toca(true, modeloListo(dir), seOfrece(context), idioma, segundo, todoEnUno)) {
            android.util.Log.i("PixPinVoz", "Qwen3-ASR elegido pero no toca (bajado=${modeloListo(dir)}, idioma=$idioma): con Whisper")
            return null
        }
        val t0 = System.nanoTime()
        return runCatching {
            com.forge.pixpin.guardados.PasarATextoService.mientras(context, "Pasando a texto con Qwen3-ASR", descarga = false) { aviso ->
                val r = reparto(memoriaTotalMb(context), Runtime.getRuntime().availableProcessors())
                Cargado.cargar(OrtPorJna.cargar(), dir, r).use { q ->
                    android.util.Log.i("PixPinVoz", "Qwen3-ASR cargado en ${(System.nanoTime() - t0) / 1_000_000} ms (ONNX Runtime ${q.versionDelRuntime}, $r)")
                    q.transcribir(pcm, idioma, segundo, todoEnUno) { avance(it); aviso(it) }
                }
            }
        }.onSuccess {
            android.util.Log.i("PixPinVoz", "pasado a texto con Qwen3-ASR en ${(System.nanoTime() - t0) / 1_000_000_000} s, audio ${pcm.length() / 32_000} s")
        }.onFailure {
            android.util.Log.w("PixPinVoz", "Qwen3-ASR falló; se pasa con Whisper", it)
        }.getOrNull()
    }

    /**
     * **Baja lo que falte** a [carpeta], con el sha256 de cada fichero (uno que no cuadra se borra
     * y es un error). Reanuda un `.parte` a medias con `Range` (son 756 MB el decoder, y en el
     * móvil la red se corta). [avance] de 0 a 1 sobre el total. Lanza si falla; hilo de fondo.
     */
    fun bajar(carpeta: File, avance: (Float) -> Unit, cancelado: AtomicBoolean) {
        carpeta.mkdirs()
        val total = CatalogoQwen3.FICHEROS.sumOf { it.bytes }.toFloat()
        var hecho = 0L
        for (f in CatalogoQwen3.FICHEROS) {
            val destino = File(carpeta, f.nombre)
            if (!estaEntero(destino, f)) {
                val antes = hecho
                bajarUno(f, carpeta, cancelado) { leidos -> avance(((antes + minOf(leidos, f.bytes)) / total).coerceAtMost(0.99f)) }
            }
            hecho += f.bytes
        }
        avance(1f)
    }

    class Cancelada : RuntimeException("cancelada")

    private fun bajarUno(f: CatalogoQwen3.Fichero, carpeta: File, cancelado: AtomicBoolean, leidos: (Long) -> Unit) {
        val destino = File(carpeta, f.nombre)
        val parte = File(carpeta, f.nombre + ".parte")
        if (parte.length() > f.bytes) parte.delete()
        val h = MessageDigest.getInstance("SHA-256")
        var tengo = 0L
        if (parte.exists()) parte.inputStream().use { e ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = e.read(buf); if (n < 0) break; h.update(buf, 0, n); tengo += n }
        }
        if (tengo < f.bytes) {
            val c = URL(f.url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            if (tengo > 0) c.setRequestProperty("Range", "bytes=$tengo-")
            try {
                val codigo = c.responseCode
                if (codigo !in 200..299) throw java.io.IOException("HTTP $codigo")
                // Sin 206 el servidor no reanuda: de cero.
                val anadir = tengo > 0 && codigo == HttpURLConnection.HTTP_PARTIAL
                if (!anadir && tengo > 0) { h.reset(); tengo = 0 }
                c.inputStream.use { entrada ->
                    java.io.FileOutputStream(parte, anadir).buffered(1 shl 16).use { salida ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (cancelado.get()) throw Cancelada()
                            val n = entrada.read(buf); if (n < 0) break
                            salida.write(buf, 0, n); h.update(buf, 0, n); tengo += n
                            if (tengo > f.bytes) throw java.io.IOException("${f.nombre}: más grande de lo esperado")
                            leidos(tengo)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        val sha = h.digest().joinToString("") { "%02x".format(it) }
        if (tengo != f.bytes || sha != f.sha256) {
            parte.delete()
            throw java.io.IOException("${f.nombre}: el sha256 no cuadra ($tengo bytes, $sha)")
        }
        destino.delete()
        if (!parte.renameTo(destino)) throw java.io.IOException("no se pudo dejar ${f.nombre}")
    }

    /**
     * **La descarga en curso**, que sobrevive a cerrar Ajustes: va en un hilo y en el servicio en
     * primer plano (son ~1 GB). Elegir Qwen3-ASR la empieza; volver a otro motor la corta. Lo
     * elegido no cambia: en cuanto están los ficheros, la siguiente nota ya va con Qwen3-ASR.
     */
    object Descarga {
        data class Estado(val bajando: Boolean = false, val avance: Float = 0f, val fallo: String? = null, val vuelta: Int = 0)

        private val _estado = kotlinx.coroutines.flow.MutableStateFlow(Estado())
        val estado: kotlinx.coroutines.flow.StateFlow<Estado> = _estado
        private val corte = AtomicBoolean(false)

        fun empezar(context: android.content.Context) {
            val app = context.applicationContext
            synchronized(this) {
                if (_estado.value.bajando) return
                corte.set(false)
                _estado.value = Estado(bajando = true, vuelta = _estado.value.vuelta + 1)
            }
            Thread {
                val r = runCatching {
                    com.forge.pixpin.guardados.PasarATextoService.mientras(app, "Bajando Qwen3-ASR", descarga = true) { aviso ->
                        var ultimo = -1
                        bajar(carpeta(app), { a ->
                            val pct = (a * 100).toInt()
                            if (pct != ultimo) { ultimo = pct; _estado.value = _estado.value.copy(avance = a); aviso(a) }
                        }, corte)
                    }
                }
                val e = r.exceptionOrNull()
                if (e != null && e !is Cancelada) android.util.Log.w("PixPinVoz", "no se pudo bajar Qwen3-ASR", e)
                _estado.value = Estado(
                    bajando = false, avance = if (e == null) 1f else 0f,
                    fallo = e?.takeIf { it !is Cancelada }?.let { it.message ?: it.toString() }, vuelta = _estado.value.vuelta + 1
                )
            }.apply { name = "bajar-qwen3-asr" }.start()
        }

        /** Corta la descarga en curso (volver a otro motor, borrar). */
        fun cancelar() = corte.set(true)

        /** Borra lo bajado, cortando antes la descarga si la hay. */
        fun borrar(context: android.content.Context) {
            cancelar()
            Thread {
                // Esperar a que el hilo suelte el `.parte` antes de borrar la carpeta.
                var esperas = 0
                while (_estado.value.bajando && esperas++ < 50) Thread.sleep(100)
                MotorQwen3.borrar(carpeta(context.applicationContext))
                _estado.value = Estado(vuelta = _estado.value.vuelta + 1)
            }.start()
        }
    }
}
