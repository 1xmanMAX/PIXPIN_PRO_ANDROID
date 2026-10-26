package com.forge.pixpin.ocr.indra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.forge.pixpin.data.OCR_INDRA
import com.forge.pixpin.guardados.qwen3.OrtPorJna
import com.forge.pixpin.ocr.CatalogoIndra
import com.forge.pixpin.ocr.Hunspell
import com.forge.pixpin.ocr.LineaLeida
import com.forge.pixpin.ocr.PalabraLeida
import com.forge.pixpin.ocr.ParrafosOcr
import com.forge.pixpin.ocr.Vocabulario
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * **El lector Indra en el teléfono** (26-oct-2026, `crates/pixpin-ocr/src/indra.rs` del PC con el
 * canal portado del escáner): la descarga, la carga, la memoria y la lectura de un `Bitmap`.
 *
 * - **Pesos**: a `files/indra/`, los mismos ficheros y sha256 que el PC y el escáner
 *   ([CatalogoIndra]), solo si el usuario elige Indra en Ajustes › Capturar (licencia OpenRAIL-M
 *   modificada de Datalab: no pueden ir en el APK). Nunca se bajan solos.
 * - **Runtime**: el `libonnxruntime.so` que ya trae el APK (el de sherpa-onnx), por [OrtPorJna];
 *   nada que bajar. Solo 64 bits, como Whisper.
 * - **Memoria**: se carga al leer la primera imagen (o al [precargar]) y **se suelta a los tres
 *   minutos sin leer nada** (LM ~37 MB, modelos ~10 MB, diccionario ~2 MB). Igual que el PC.
 * - **Nunca en el hilo principal**: [leer] es lento (cientos de ms); quien llama lo pone en un hilo.
 *
 * Si algo falla (sin runtime, un fichero roto) [leer] devuelve null y queda en el registro; quien
 * llama hace como si no hubiera lector.
 */
object MotorIndra {
    private const val TAG = "PixPinOcr"
    /** Sin leer nada en este tiempo, se suelta (`SOLTAR_TRAS` del PC). */
    const val SOLTAR_TRAS_MS = 180_000L

    /** El registro, que en las pruebas de la JVM (sin Android) no está. */
    private fun info(m: String) { runCatching { Log.i(TAG, m) } }
    private fun aviso(m: String, e: Throwable? = null) { runCatching { Log.w(TAG, m, e) } }

    fun carpeta(context: Context) = File(context.applicationContext.filesDir, "indra")

    private fun estaEntero(f: File, fichero: CatalogoIndra.Fichero) = f.isFile && f.length() == fichero.bytes

    /** Si los pesos y el diccionario están bajados enteros (el sha256 se comprobó al bajarlos). */
    fun listo(dir: File): Boolean = CatalogoIndra.FICHEROS.all { estaEntero(File(dir, it.nombre), it) }

    /** Lo que falta por bajar, en MB (lo que se enseña en Ajustes). */
    fun megasQueFaltan(dir: File): Long =
        (CatalogoIndra.FICHEROS.filterNot { estaEntero(File(dir, it.nombre), it) }.sumOf { it.bytes } + 999_999) / 1_000_000

    /** **Si se lee con Indra** (puro): elegido y bajado. Elegido sin bajar es como no tener lector. */
    fun usaIndra(motor: String, bajado: Boolean): Boolean = motor == OCR_INDRA && bajado

    private fun ajustes(context: Context) = (context.applicationContext as? com.forge.pixpin.PixPinApp)?.ajustes

    /** Si ahora mismo se puede leer texto (Indra elegido y bajado). Mira el disco: no en el hilo de la interfaz si se puede evitar. */
    fun enUso(context: Context): Boolean = usaIndra(ajustes(context)?.ocrMotor ?: "", listo(carpeta(context)))

    /** Con qué se carga: si cambia (otro vocabulario, corregir sí/no), se suelta y se recarga. */
    data class Config(val corregir: Boolean, val vocabulario: String, val hilos: Int)

    private fun config(context: Context): Config {
        val a = ajustes(context)
        val nucleos = Runtime.getRuntime().availableProcessors()
        // El modo cuidadoso del PC: dos hilos con poca memoria.
        val memoria = runCatching { com.forge.pixpin.guardados.qwen3.MotorQwen3.memoriaTotalMb(context) }.getOrDefault(0L)
        val hilos = if (memoria in 1..4_500) 2 else nucleos.coerceIn(1, 4)
        return Config(a?.ocrCorregir ?: true, a?.ocrVocabulario ?: "", hilos)
    }

    /** El lector cargado. */
    class Cargado(val modelos: ModelosPorJna, val canal: CanalIndra, val config: Config) : AutoCloseable {
        @Volatile var ultima: Long = System.currentTimeMillis()
        override fun close() = modelos.close()
    }

    /**
     * **Carga el lector de [dir]** con [ort] (el del APK, o en las pruebas uno de la máquina).
     * Lanza si falta algo; quien llama decide.
     */
    fun cargar(ort: OrtPorJna, dir: File, c: Config): Cargado {
        val t0 = System.nanoTime()
        val meta = MetaIndra.deJson(File(dir, "meta.json").readText())
        val modelos = ModelosPorJna.abrir(ort, dir, c.hilos)
        try {
            val lm = if (modelos.conSalidasDeLm && File(dir, "lm.npz").isFile) {
                runCatching { LmIndra.cargar(File(dir, "lm.npz")) }.onFailure { aviso("Indra sin modelo de lenguaje", it) }.getOrNull()
            } else null
            val canal = CanalIndra(meta, lm)
            if (c.corregir) {
                val aff = File(dir, "es_ES.aff")
                val dic = File(dir, "es_ES.dic")
                if (aff.isFile && dic.isFile) canal.lexico = Hunspell.cargar(aff.readText(), dic.readText())
            }
            canal.vocabulario = Vocabulario.deTexto(c.vocabulario)
            info(
                "Indra cargado en ${(System.nanoTime() - t0) / 1_000_000} ms (ONNX Runtime ${modelos.versionDelRuntime}, LM ${lm != null}, " +
                    "diccionario ${canal.lexico?.tamano ?: 0} raíces, ${c.hilos} hilos)"
            )
            return Cargado(modelos, canal, c)
        } catch (e: Throwable) {
            modelos.close()
            throw e
        }
    }

    private val cerrojo = ReentrantLock()
    private var cargado: Cargado? = null
    private val vigilante = AtomicBoolean(false)
    private val reloj by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "indra-soltar").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** Suelta el lector si estaba cargado (cambio de ajustes, borrar). Se vuelve a cargar solo al leer. */
    fun soltar() = cerrojo.withLock {
        cargado?.let { runCatching { it.close() }; info("lector Indra soltado") }
        cargado = null
    }

    /** Un reloj que suelta el lector cuando lleva [SOLTAR_TRAS_MS] sin leer. */
    private fun vigilar() {
        if (!vigilante.compareAndSet(false, true)) return
        reloj.scheduleWithFixedDelay({
            if (cerrojo.tryLock()) try {
                val c = cargado
                if (c != null && System.currentTimeMillis() - c.ultima > SOLTAR_TRAS_MS) {
                    runCatching { c.close() }
                    cargado = null
                    info("lector Indra soltado por no usarse")
                }
            } finally {
                cerrojo.unlock()
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** El cargado con la config de ahora; null si no se pudo. Con el cerrojo cogido. */
    private fun asegurar(context: Context): Cargado? {
        val c = config(context)
        cargado?.let { if (it.config == c) return it; runCatching { it.close() }; cargado = null }
        val nuevo = runCatching { cargar(OrtPorJna.cargar(), carpeta(context), c) }
            .onFailure { aviso("no se pudo cargar Indra", it) }.getOrNull() ?: return null
        cargado = nuevo
        vigilar()
        return nuevo
    }

    /**
     * **Calienta el lector** (PC e121d70, `ocr_motor::calentar`): con Indra elegido y bajado, abrir
     * una captura o pinear una foto lo carga en un hilo de baja prioridad, para que la primera
     * lectura no pague el medio segundo de abrir los modelos. No al arrancar (cargar un lector que
     * nadie usa para soltarlo a los tres minutos es gastar memoria), y no le alarga la vida: si ya
     * está cargado no toca su reloj. Si alguien está leyendo, no se le espera.
     */
    fun precargar(context: Context) {
        val app = context.applicationContext
        Thread {
            if (!enUso(app)) return@Thread
            if (!cerrojo.tryLock()) return@Thread
            try {
                if (cargado == null) asegurar(app)
            } finally {
                cerrojo.unlock()
            }
        }.apply { name = "indra-calentar"; priority = Thread.MIN_PRIORITY }.start()
    }

    /** La caja entera que cubre `[x0, y0, x1, y1]` (`rect_de` del PC). */
    private fun rect(b: FloatArray): IntArray {
        val x0 = floor(b[0]).toInt(); val y0 = floor(b[1]).toInt()
        val x1 = ceil(b[2]).toInt(); val y1 = ceil(b[3]).toInt()
        return intArrayOf(x0, y0, max(0, x1 - x0), max(0, y1 - y0))
    }

    /** Las líneas de Indra en la forma de siempre (`a_lineas` del PC): en orden de lectura, sin las vacías ni las dudosas. */
    fun aLineas(p: CanalIndra.Pagina): List<LineaLeida> = p.lineas.filter { !it.dudosa && it.texto.isNotBlank() }.map { l ->
        val r = rect(l.caja)
        LineaLeida(r[0], r[1], r[2], r[3], l.texto, l.palabras.map { w -> val q = rect(w.caja); PalabraLeida(q[0], q[1], q[2], q[3], w.texto) })
    }

    /**
     * **Lee una imagen en gris** ya a como mucho [CanalIndra.LADO_MAX] de lado, con cajas en px de
     * una imagen de [anchoImg] × [altoImg]. Null si no hay lector o falla. Hilo de fondo.
     */
    fun leerGris(context: Context, g: Gris, anchoImg: Int = g.ancho, altoImg: Int = g.alto): List<LineaLeida>? {
        if (!enUso(context)) return null
        return cerrojo.withLock {
            val c = asegurar(context) ?: return@withLock null
            val t0 = System.nanoTime()
            runCatching {
                val p = c.canal.leer(c.modelos, g)
                c.ultima = System.currentTimeMillis()
                p.aLaImagen(anchoImg, altoImg, anchoImg.toFloat() / g.ancho, altoImg.toFloat() / g.alto)
                val l = aLineas(p)
                info("Indra leyó ${g.ancho}x${g.alto} en ${(System.nanoTime() - t0) / 1_000_000} ms: ${l.size} líneas, " +
                    "${p.lineas.count { it.dudosa }} dudosas, giro ${p.giro}, inclinación ${"%.1f".format(p.inclinacion)} ${p.ms}")
                l
            }.onFailure { aviso("Indra falló leyendo", it) }.getOrNull()
        }
    }

    /** Un `Bitmap` (no se recicla) a gris, reducido a [CanalIndra.LADO_MAX] si hace falta. */
    fun gris(b: Bitmap): Gris {
        val w = b.width
        val h = b.height
        val px = ByteArray(w * h)
        val fila = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(fila, 0, w, 0, y, w, 1)
            for (x in 0 until w) px[y * w + x] = OpsGris.luma(fila[x]).toByte()
        }
        return reducir(Gris(w, h, px))
    }

    /** Más de [CanalIndra.LADO_MAX] de lado no gana nada (el detector trabaja a 1024). */
    fun reducir(g: Gris): Gris {
        val lado = max(g.ancho, g.alto)
        if (lado <= CanalIndra.LADO_MAX) return g
        val s = CanalIndra.LADO_MAX.toDouble() / lado
        return OpsGris.redimensionar(g, max(1, Math.round(g.ancho * s).toInt()), max(1, Math.round(g.alto * s).toInt()), OpsGris.Interpolacion.AREA)
    }

    /** **Las líneas de un `Bitmap`**, con cajas en sus px. Null si no hay lector o falla. Hilo de fondo. */
    fun leer(context: Context, b: Bitmap): List<LineaLeida>? {
        if (!enUso(context)) return null
        return leerGris(context, gris(b), b.width, b.height)
    }

    /** **El texto de un `Bitmap`** en el formato del PC ([ParrafosOcr]): el de «Copiar texto» y el que viaja con la galería. */
    fun texto(context: Context, b: Bitmap): String? = leer(context, b)?.let { ParrafosOcr.texto(it) }

    /**
     * **El texto de una imagen guardada** ([abrir] da sus bytes), decodificada ya reducida
     * (`inSampleSize`) para no tener en memoria una foto de 50 MP entera. Null si no hay lector, no
     * se puede abrir o falla.
     */
    fun textoDeImagen(context: Context, abrir: () -> java.io.InputStream?): String? {
        if (!enUso(context)) return null
        val opc = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        abrir()?.use { BitmapFactory.decodeStream(it, null, opc) } ?: return null
        if (opc.outWidth <= 0 || opc.outHeight <= 0) return null
        var muestra = 1
        while (max(opc.outWidth, opc.outHeight) / (muestra * 2) >= CanalIndra.LADO_MAX) muestra *= 2
        val b = abrir()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra }) } ?: return null
        return try { texto(context, b) } finally { b.recycle() }
    }

    // ---- La descarga ------------------------------------------------------------------------

    class Cancelada : RuntimeException("cancelada")

    /**
     * **Baja lo que falte** a [dir], con el sha256 de cada fichero (uno que no cuadra se borra y es
     * un error), cada uno a `.parte` y renombrado al acabar. [avance] de 0 a 1. Lanza si falla.
     */
    fun bajar(dir: File, avance: (Float) -> Unit, cancelado: AtomicBoolean) {
        dir.mkdirs()
        val total = CatalogoIndra.FICHEROS.sumOf { it.bytes }.toFloat()
        var hecho = 0L
        for (f in CatalogoIndra.FICHEROS) {
            val destino = File(dir, f.nombre)
            if (!estaEntero(destino, f)) {
                val antes = hecho
                bajarUno(f, dir, cancelado) { leidos -> avance(((antes + minOf(leidos, f.bytes)) / total).coerceAtMost(0.99f)) }
            }
            hecho += f.bytes
        }
        avance(1f)
    }

    private fun bajarUno(f: CatalogoIndra.Fichero, dir: File, cancelado: AtomicBoolean, leidos: (Long) -> Unit) {
        val destino = File(dir, f.nombre)
        val parte = File(dir, f.nombre + ".parte")
        val h = MessageDigest.getInstance("SHA-256")
        var tengo = 0L
        val c = URL(f.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        try {
            val codigo = c.responseCode
            if (codigo !in 200..299) throw java.io.IOException("${f.nombre}: HTTP $codigo")
            c.inputStream.use { entrada ->
                java.io.FileOutputStream(parte).buffered(1 shl 16).use { salida ->
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
        } catch (e: Throwable) {
            parte.delete()
            throw e
        } finally {
            c.disconnect()
        }
        val sha = h.digest().joinToString("") { "%02x".format(it) }
        if (tengo != f.bytes || sha != f.sha256) {
            parte.delete()
            throw java.io.IOException("${f.nombre}: el sha256 no cuadra ($tengo bytes, $sha)")
        }
        destino.delete()
        if (!parte.renameTo(destino)) throw java.io.IOException("no se pudo dejar ${f.nombre}")
    }

    /** Borra lo bajado (Ajustes › «Borrar Indra»), soltando antes el lector. */
    fun borrar(dir: File) {
        soltar()
        dir.deleteRecursively()
    }

    /**
     * **La descarga en curso**, que sobrevive a cerrar Ajustes (va en su hilo). Elegir Indra la
     * empieza; volver a «Sin lector» la corta; «Borrar» quita los pesos (como el PC).
     */
    object Descarga {
        data class Estado(val bajando: Boolean = false, val avance: Float = 0f, val fallo: String? = null, val vuelta: Int = 0)

        private val _estado = MutableStateFlow(Estado())
        val estado: StateFlow<Estado> = _estado
        private val corte = AtomicBoolean(false)

        fun empezar(context: Context) {
            val app = context.applicationContext
            synchronized(this) {
                if (_estado.value.bajando) return
                corte.set(false)
                _estado.value = Estado(bajando = true, vuelta = _estado.value.vuelta + 1)
            }
            Thread {
                var ultimo = -1
                val r = runCatching {
                    bajar(carpeta(app), { a ->
                        val pct = (a * 100).toInt()
                        if (pct != ultimo) { ultimo = pct; _estado.value = _estado.value.copy(avance = a) }
                    }, corte)
                }
                val e = r.exceptionOrNull()
                if (e != null && e !is Cancelada) aviso("no se pudo bajar Indra", e)
                _estado.value = Estado(
                    bajando = false, avance = if (e == null) 1f else 0f,
                    fallo = e?.takeIf { it !is Cancelada }?.let { it.message ?: it.toString() }, vuelta = _estado.value.vuelta + 1
                )
            }.apply { name = "bajar-indra" }.start()
        }

        /** Corta la descarga en curso. */
        fun cancelar() = corte.set(true)

        /** Borra lo bajado, cortando antes la descarga si la hay. */
        fun borrar(context: Context) {
            cancelar()
            val app = context.applicationContext
            Thread {
                var esperas = 0
                while (_estado.value.bajando && esperas++ < 50) Thread.sleep(100)
                MotorIndra.borrar(carpeta(app))
                _estado.value = Estado(vuelta = _estado.value.vuelta + 1)
            }.start()
        }
    }
}
