package com.forge.pixpin.ia

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * **llama.cpp en un proceso aparte** (`llama-server`, `servidor.rs` del PC), hablado por HTTP
 * local.
 *
 * ## Por qué un proceso y no una biblioteca dentro de PixPin
 *
 * - **Memoria que se devuelve entera**: matar el proceso tras [Config.reposoMs] sin uso devuelve
 *   los ~1-3 GB del modelo al sistema de golpe; un modelo cargado dentro de la app dejaría el
 *   montón de la app hinchado y la haría candidata a que Android la mate.
 * - **Un fallo del motor no tumba PixPin**: si `llama-server` muere sin memoria (el sistema lo
 *   mata antes que a la app, que es la que está delante), el chat dice que falló y ya.
 * - **El mismo protocolo que el PC**: el JSON de OpenAI, sin JNI que mantener.
 *
 * En Android el ejecutable viaja como `lib/arm64-v8a/libllamaserver.so` y se lanza desde
 * `nativeLibraryDir`, el único sitio desde el que Android 10+ deja ejecutar algo de la app (W^X).
 * Es hijo del proceso de la app: si Android mata la app, mata con ella a todo su grupo de
 * procesos, así que el modelo no se queda huérfano comiendo memoria (lo que en el PC hace el
 * «trabajo de Windows»).
 *
 * **Seguridad**: escucha solo en `127.0.0.1`, en un puerto libre al azar, con una **clave nueva
 * en cada arranque**. La clave va por un fichero privado (`--api-key-file`, 0600) que se borra en
 * cuanto el servidor está listo: así no aparece en la línea de órdenes del proceso. Ni otra
 * máquina de la Wi-Fi ni otra app del teléfono pueden usarlo.
 *
 * Todo lo de aquí bloquea: se llama desde un hilo de fondo, nunca desde el de la pantalla.
 */
class ServidorLlama(val config: Config) : Motor {

    data class Config(
        /** El ejecutable (`nativeLibraryDir/libllamaserver.so` en el teléfono). */
        val servidor: File,
        val modelo: File,
        val vision: File?,
        /** Carpeta privada para la clave de un uso y el registro del servidor. */
        val trabajo: File,
        /** Tokens de contexto: modesto, para no ahogar la RAM de un teléfono. */
        val contexto: Int = CONTEXTO,
        /** Hilos: los núcleos grandes ([hilosParaEsteAparato]); null, lo que decida llama.cpp. */
        val hilos: Int? = null,
        /** Tras cuánto sin usarlo se cierra el modelo para devolver la memoria. */
        val reposoMs: Long = 5 * 60_000L,
        /** Cuánto se espera a que cargue (un teléfono lento con el 2B y la visión tarda). */
        val arranqueMaxMs: Long = 180_000L
    ) {
        /** Los argumentos de `llama-server` (sin el ejecutable). */
        fun argumentos(puerto: Int, ficheroDeClave: File): List<String> = buildList {
            addAll(listOf("-m", modelo.absolutePath, "--host", "127.0.0.1", "--port", puerto.toString()))
            addAll(listOf("--api-key-file", ficheroDeClave.absolutePath))
            addAll(listOf("-c", contexto.toString()))
            // La plantilla de chat del propio GGUF: la que sabe escribir y leer las llamadas de Qwen.
            add("--jinja")
            addAll(listOf("-np", "1", "--no-ui"))
            vision?.let { addAll(listOf("--mmproj", it.absolutePath)) }
            addAll(listOf("--cache-reuse", REUTILIZAR.toString()))
            hilos?.let { addAll(listOf("-t", it.toString())) }
            // Sin tarjeta gráfica: este llama.cpp es solo CPU (NEON + dotprod).
            addAll(listOf("-ngl", "0"))
        }
    }

    private class Proceso(val proceso: Process, val puerto: Int, val clave: String, val cargadoEnMs: Long)

    private val estado = Any()

    /** Se lee sin el cerrojo (volátil): preguntar si está cargado no espera a que acabe de cargar. */
    @Volatile private var proceso: Proceso? = null
    private var ultimoUso = System.currentTimeMillis()
    private var ocupado = false
    private var vigia: ScheduledFuture<*>? = null

    /** Una petición a la vez (el servidor va con una sola ranura). */
    private val turno = ReentrantLock()

    /** ¿Está el modelo en memoria ahora mismo? */
    val cargado: Boolean get() = proceso?.proceso?.isAlive == true

    /** Cuánto tardó en cargar la última vez (ms), o null. */
    val tiempoDeCarga: Long? get() = proceso?.cargadoEnMs

    /** El pid del servidor, para medir su memoria en las pruebas. */
    val pid: Long? get() = proceso?.proceso?.let { pidDe(it) }

    /**
     * Carga el modelo ya (el chat lo llama al abrir el asistente para que la primera respuesta no
     * tarde). Devuelve el puerto y la clave.
     */
    fun precargar(): Pair<Int, String> = synchronized(estado) {
        val vivo = proceso?.takeIf { it.proceso.isAlive }
        val p = vivo ?: run {
            proceso?.let { matar(it.proceso) }
            proceso = null
            arrancar(config).also { proceso = it }
        }
        ultimoUso = System.currentTimeMillis()
        if (vigia == null) vigia = RELOJ.scheduleWithFixedDelay(
            ::vigilar, PASO_DEL_VIGIA_MS, PASO_DEL_VIGIA_MS, TimeUnit.MILLISECONDS
        )
        p.puerto to p.clave
    }

    override fun precalentar() {
        runCatching { precargar() }
    }

    override fun completar(peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta = turno.withLock {
        val (puerto, clave) = precargar()
        marcar(true)
        try {
            pedir(puerto, clave, peticion, alTrozo)
        } finally {
            marcar(false)
        }
    }

    override fun soltar() {
        synchronized(estado) {
            proceso?.let { matar(it.proceso) }
            proceso = null
            vigia?.cancel(false)
            vigia = null
        }
    }

    private fun marcar(o: Boolean) = synchronized(estado) {
        ocupado = o
        ultimoUso = System.currentTimeMillis()
    }

    /** Cierra el modelo tras el reposo (nunca a mitad de una respuesta). */
    private fun vigilar() {
        synchronized(estado) {
            val p = proceso
            if (p == null || !p.proceso.isAlive) {
                proceso = null
                vigia?.cancel(false); vigia = null
                return
            }
            if (!ocupado && System.currentTimeMillis() - ultimoUso >= config.reposoMs) {
                matar(p.proceso)
                proceso = null
                vigia?.cancel(false); vigia = null
            }
        }
    }

    companion object {
        /** Los tokens de contexto con que se arranca: en el PC 8192; aquí la mitad, por la RAM. */
        const val CONTEXTO = 4096

        /** `--cache-reuse` de fábrica, como el PC. */
        const val REUTILIZAR = 256

        private const val PASO_DEL_VIGIA_MS = 15_000L

        /** Un solo hilo (de fondo, que no retiene la app) para los vigías de todos los motores. */
        private val RELOJ: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pixpin-ia-reposo").apply { isDaemon = true }
        }

        private val AZAR = SecureRandom()

        /** Una clave de un solo uso: 32 bytes al azar del generador seguro del sistema. */
        internal fun claveNueva(): String {
            val b = ByteArray(32)
            AZAR.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        internal fun puertoLibre(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

        /**
         * **Los hilos para este aparato**: los núcleos grandes. Un teléfono tiene 2-3 tipos de
         * núcleo; los pequeños (los de la frecuencia más baja) frenan a los demás en llama.cpp,
         * que reparte el trabajo a partes iguales. [frecuencias]: la máxima de cada núcleo.
         */
        fun hilosPorNucleos(frecuencias: List<Long>): Int? {
            val f = frecuencias.filter { it > 0 }
            if (f.isEmpty()) return null
            val minima = f.min()
            val grandes = if (f.all { it == minima }) f.size else f.count { it > minima }
            return grandes.coerceIn(1, 8)
        }

        /** Lee las frecuencias de `/sys` (vale en Android y en Linux). */
        fun hilosParaEsteAparato(): Int? {
            val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) } ?: return null
            val fs = cpus.mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
            // Nunca más que los que el sistema deja usar a este proceso (afinidad, cgroups).
            val disponibles = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            return minOf(hilosPorNucleos(fs) ?: disponibles, disponibles)
        }

        private fun pidDe(p: Process): Long? = runCatching {
            Process::class.java.getMethod("pid").invoke(p) as Long
        }.getOrNull() ?: runCatching {
            // Android (y Java 8): el campo privado del UNIXProcess.
            p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong()
        }.getOrNull()

        private fun matar(p: Process) {
            p.destroy()
            if (!esperar(p, 2_000)) {
                p.destroyForcibly()
                esperar(p, 2_000)
            }
        }

        private fun esperar(p: Process, ms: Long): Boolean {
            val hasta = System.currentTimeMillis() + ms
            while (p.isAlive && System.currentTimeMillis() < hasta) Thread.sleep(50)
            return !p.isAlive
        }

        private fun arrancar(c: Config): Proceso {
            for ((que, f) in listOf("el motor" to c.servidor, "el modelo" to c.modelo, "la vision" to c.vision)) {
                if (f != null && !f.isFile) throw ErrorIa.Falta("$que (${f.name})")
            }
            if (!c.trabajo.isDirectory && !c.trabajo.mkdirs()) throw ErrorIa.Arranque("sin carpeta de trabajo")
            val puerto = puertoLibre()
            val clave = claveNueva()
            // La clave por fichero privado y no en la línea de órdenes; se borra al estar listo.
            val ficheroDeClave = File(c.trabajo, "clave-${System.nanoTime()}")
            ficheroDeClave.writeText(clave)
            ficheroDeClave.setReadable(false, false); ficheroDeClave.setReadable(true, true)
            ficheroDeClave.setWritable(false, false); ficheroDeClave.setWritable(true, true)
            val registro = File(c.trabajo, "servidor.log")
            val t0 = System.currentTimeMillis()
            val proceso = try {
                ProcessBuilder(listOf(c.servidor.absolutePath) + c.argumentos(puerto, ficheroDeClave))
                    .directory(c.modelo.parentFile)
                    .redirectErrorStream(true)
                    .redirectOutput(registro)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .start()
            } catch (e: IOException) {
                ficheroDeClave.delete()
                throw ErrorIa.Arranque(e.message ?: "no se pudo lanzar")
            }
            try {
                while (true) {
                    if (!proceso.isAlive) throw ErrorIa.Arranque("el motor se cerro (${proceso.exitValue()})${colaDelRegistro(registro)}")
                    if (salud(puerto)) return Proceso(proceso, puerto, clave, System.currentTimeMillis() - t0)
                    if (System.currentTimeMillis() - t0 > c.arranqueMaxMs) throw ErrorIa.Arranque("no cargo en ${c.arranqueMaxMs / 1000} s")
                    Thread.sleep(150)
                }
            } catch (e: Throwable) {
                matar(proceso)
                throw e
            } finally {
                ficheroDeClave.delete()
            }
        }

        private fun colaDelRegistro(r: File): String {
            val lineas = runCatching { r.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
            return if (lineas.isEmpty()) "" else ": " + lineas.takeLast(3).joinToString(" | ")
        }

        /** `GET /health` contesta 200 cuando el modelo ya está cargado (503 mientras carga). */
        private fun salud(puerto: Int): Boolean = runCatching {
            val c = URL("http://127.0.0.1:$puerto/health").openConnection() as HttpURLConnection
            c.connectTimeout = 300
            c.readTimeout = 2_000
            try { c.responseCode == 200 } finally { c.disconnect() }
        }.getOrDefault(false)

        /** La petición de chat, en vivo. Cerrar la conexión hace que `llama-server` deje de generar. */
        internal fun pedir(puerto: Int, clave: String, peticion: Peticion, alTrozo: (Trozo) -> Seguir): Respuesta {
            val cuerpo = peticion.aJson(true).toString().toByteArray(Charsets.UTF_8)
            val c = try {
                (URL("http://127.0.0.1:$puerto/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5_000
                    // Leer una imagen grande en un teléfono puede tardar; más de 5 min sin un
                    // byte es que algo se colgó.
                    readTimeout = 300_000
                    setFixedLengthStreamingMode(cuerpo.size)
                    setRequestProperty("Authorization", "Bearer $clave")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                }
            } catch (e: IOException) {
                throw ErrorIa.Conexion(e.message ?: "sin conexion")
            }
            try {
                try {
                    c.outputStream.use { it.write(cuerpo) }
                } catch (e: IOException) {
                    throw ErrorIa.Conexion(e.message ?: "no se pudo mandar")
                }
                val codigo = try { c.responseCode } catch (e: IOException) { throw ErrorIa.Conexion(e.message ?: "sin respuesta") }
                if (codigo != 200) {
                    val error = runCatching { c.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
                    throw ErrorIa.Protocolo("HTTP $codigo: ${error.take(300)}")
                }
                val acumulador = Acumulador()
                try {
                    c.inputStream.use { e ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = e.read(buf)
                            if (n < 0) break
                            for (t in acumulador.meter(buf, 0, n)) {
                                if (alTrozo(t) == Seguir.PARAR) return acumulador.respuesta(cortado = true)
                            }
                            if (acumulador.acabado) break
                        }
                    }
                } catch (e: IOException) {
                    throw ErrorIa.Conexion(e.message ?: "se corto")
                }
                return acumulador.respuesta(cortado = false)
            } finally {
                c.disconnect()
            }
        }
    }
}
