package com.forge.pixpin.guardados.qwen3

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * **El ONNX Runtime que ya trae el APK, hablado por su API en C con JNA** (25-oct-2026).
 *
 * `docs/voz-qwen3.md` § Android pide correr Qwen3-ASR con `OrtSession.run` y sugiere
 * `com.microsoft.onnxruntime:onnxruntime-android`. **No se añade**: el APK ya lleva un
 * `libonnxruntime.so` (el 1.27.1 que usa sherpa-onnx para Whisper, en `jniLibs/arm64-v8a`), y el
 * AAR de Microsoft trae otro con el mismo nombre. Dos `lib/arm64-v8a/libonnxruntime.so` no caben
 * en un APK: o el empaquetado falla, o gana uno y el otro se queda sin el suyo. Y no son
 * intercambiables: el de sherpa exporta sus símbolos con versión (`OrtGetApiBase@@VERS_1.27.1`) y
 * `libsherpa-onnx-jni.so` pide exactamente esa, así que cambiarlo por el de Microsoft (otra
 * versión) rompería Whisper al cargar. Además serían ~20 MB más de APK por lo mismo.
 *
 * Así que se hace lo del PC (`crates/pixpin-voz/src/ort.rs`): la biblioteca exporta **un solo
 * símbolo**, `OrtGetApiBase`, que da una **tabla de punteros a función** (`struct OrtApi`). La
 * tabla solo crece por el final entre versiones, así que la posición de cada función es fija:
 * los índices son los mismos que contó el PC en el `onnxruntime_c_api.h` de la API 17. JNA ya va
 * en el APK (lo usa Vosk) y llama a un puntero a función sin escribir ni una línea de C.
 *
 * Solo 64 bits: el `libonnxruntime.so` del APK es de `arm64-v8a` (como Whisper), y aquí `size_t`
 * va como `Long`. En un teléfono de 32 bits [cargar] falla y se pasa con Whisper (o se dice).
 *
 * Fuera del teléfono (las pruebas de la JVM) carga el `libonnxruntime.so` que se le diga.
 */
class OrtPorJna private constructor(
    /**
     * **La biblioteca, sujeta.** JNA la cierra (`dlclose`) cuando su `NativeLibrary` se recoge
     * como basura, y con ella se va la tabla: la primera prueba con el modelo de verdad se cayó así,
     * al leer la tabla a mitad de cargar. Mientras viva este objeto, vive la biblioteca.
     */
    @Suppress("unused") private val biblioteca: NativeLibrary,
    private val tabla: Pointer,
    val version: String
) {

    /** Un fallo de ONNX Runtime, con el paso en que fue. */
    class ErrorOrt(val paso: String, detalle: String) : RuntimeException("$paso: $detalle")

    private val funciones = arrayOfNulls<Function>(TAMANO_DE_LA_TABLA)

    private fun f(i: Int): Function =
        funciones[i] ?: Function.getFunction(tabla.getPointer(i.toLong() * Native.POINTER_SIZE)).also { funciones[i] = it }

    /** `OrtStatus*` nulo es que fue bien; si no, su mensaje, y se suelta. */
    private fun comprobar(estado: Pointer?, paso: String) {
        if (estado == null) return
        val mensaje = runCatching { f(GET_ERROR_MESSAGE).invokeString(arrayOf(estado), false) }.getOrNull() ?: "sin mensaje"
        runCatching { f(RELEASE_STATUS).invokeVoid(arrayOf(estado)) }
        throw ErrorOrt(paso, mensaje)
    }

    private fun llamar(i: Int, paso: String, vararg args: Any?) = comprobar(f(i).invokePointer(args), paso)

    private val entorno: Pointer = PointerByReference().let { llamar(CREATE_ENV, "crear el entorno", NIVEL_AVISOS, "pixpin-qwen3", it); it.value }
    private val memoria: Pointer = PointerByReference().let { llamar(CREATE_CPU_MEMORY_INFO, "memoria de CPU", ASIGNADOR_ARENA, MEMORIA_POR_DEFECTO, it); it.value }
    private val asignador: Pointer = PointerByReference().let { llamar(GET_ALLOCATOR_WITH_DEFAULT_OPTIONS, "asignador", it); it.value }

    /** **Un tensor**: el `OrtValue` y la memoria nativa de sus datos, que tiene que vivir lo mismo que él (ONNX Runtime no la copia). */
    inner class Valor internal constructor(internal val handle: Pointer, private val propia: Memory?) : AutoCloseable {
        private var suelto = false

        /** Dónde están sus datos (de ONNX Runtime si es una salida). */
        fun datos(): Pointer = PointerByReference().let { llamar(GET_TENSOR_MUTABLE_DATA, "leer un tensor", handle, it); it.value }

        fun forma(): LongArray {
            val info = PointerByReference()
            llamar(GET_TENSOR_TYPE_AND_SHAPE, "forma de un tensor", handle, info)
            try {
                val n = LongByReference()
                llamar(GET_DIMENSIONS_COUNT, "forma de un tensor", info.value, n)
                if (n.value == 0L) return LongArray(0)
                val m = Memory(8L * n.value)
                llamar(GET_DIMENSIONS, "forma de un tensor", info.value, m, n.value)
                return m.getLongArray(0, n.value.toInt())
            } finally {
                f(RELEASE_TENSOR_TYPE_AND_SHAPE_INFO).invokeVoid(arrayOf(info.value))
            }
        }

        fun elementos(): Long = forma().fold(1L) { a, d -> a * d.coerceAtLeast(0) }

        fun comoF32(): FloatArray {
            val n = elementos().toInt()
            return if (n == 0) FloatArray(0) else datos().getFloatArray(0, n)
        }

        /** **Enteros de 64 bits** (26-oct-2026): las clases y los largos que da el `rec.onnx` de Indra (`ocr/indra/ModelosPorJna`). */
        fun comoI64(): LongArray {
            val n = elementos().toInt()
            return if (n == 0) LongArray(0) else datos().getLongArray(0, n)
        }

        override fun close() {
            if (suelto) return
            suelto = true
            f(RELEASE_VALUE).invokeVoid(arrayOf(handle))
            propia?.close()
        }
    }

    private fun tensor(m: Memory, bytes: Long, forma: LongArray, tipo: Int): Valor {
        val out = PointerByReference()
        try {
            llamar(CREATE_TENSOR_WITH_DATA_AS_ORT_VALUE, "crear un tensor", memoria, m, bytes, forma, forma.size.toLong(), tipo, out)
        } catch (e: ErrorOrt) {
            m.close(); throw e
        }
        return Valor(out.value, m)
    }

    fun tensorF32(datos: FloatArray, forma: LongArray, desde: Int = 0, cuantos: Int = datos.size - desde): Valor {
        val m = Memory(4L * cuantos.coerceAtLeast(1))
        if (cuantos > 0) m.write(0, datos, desde, cuantos)
        return tensor(m, 4L * cuantos, forma, TIPO_F32)
    }

    /** Un tensor de ceros (la caché de claves y valores). */
    fun tensorF32Ceros(celdas: Long, forma: LongArray): Valor {
        val m = Memory(4L * celdas.coerceAtLeast(1)).apply { clear() }
        return tensor(m, 4L * celdas, forma, TIPO_F32)
    }

    fun tensorI64(datos: LongArray, forma: LongArray): Valor {
        val m = Memory(8L * datos.size.coerceAtLeast(1))
        if (datos.isNotEmpty()) m.write(0, datos, 0, datos.size)
        return tensor(m, 8L * datos.size, forma, TIPO_I64)
    }

    fun tensorBool(datos: BooleanArray, forma: LongArray): Valor {
        val m = Memory(datos.size.toLong().coerceAtLeast(1))
        for (i in datos.indices) m.setByte(i.toLong(), if (datos[i]) 1 else 0)
        return tensor(m, datos.size.toLong(), forma, TIPO_BOOL)
    }

    /** **Una sesión** (un modelo cargado), con los nombres de sus entradas y salidas en orden. */
    inner class Sesion internal constructor(private val handle: Pointer, val entradas: List<String>, val salidas: List<String>) : AutoCloseable {
        private val nombresDeEntrada = StringArray(entradas.toTypedArray())
        private val nombresDeSalida = StringArray(salidas.toTypedArray())
        private var suelta = false

        /** Corre el modelo con [valores] (en el orden de [entradas]) y devuelve todas las salidas. Quien llama las suelta. */
        fun correr(valores: List<Valor>, paso: String): List<Valor> {
            val n = valores.size
            val m = salidas.size
            val ins = Memory(8L * n.coerceAtLeast(1))
            for (i in 0 until n) ins.setPointer(8L * i, valores[i].handle)
            val outs = Memory(8L * m.coerceAtLeast(1)).apply { clear() }
            try {
                llamar(RUN, paso, handle, null, nombresDeEntrada, ins, n.toLong(), nombresDeSalida, m.toLong(), outs)
                return (0 until m).map { Valor(outs.getPointer(8L * it), null) }
            } finally {
                ins.close(); outs.close()
            }
        }

        override fun close() {
            if (suelta) return
            suelta = true
            f(RELEASE_SESSION).invokeVoid(arrayOf(handle))
        }
    }

    /**
     * **Abre un modelo**: [hilos] de CPU, todas las optimizaciones, **sin arena** (no gana
     * velocidad y cuesta ~300 MB de pico, medido en el PC) y, si no se [preempaquetar], con
     * `session.disable_prepacking` (unos 200 MB menos, algo más lento).
     */
    fun abrir(ruta: String, hilos: Int, preempaquetar: Boolean): Sesion {
        val opciones = PointerByReference().let { llamar(CREATE_SESSION_OPTIONS, "opciones", it); it.value }
        try {
            llamar(SET_INTRA_OP_NUM_THREADS, "hilos", opciones, hilos)
            llamar(SET_SESSION_GRAPH_OPTIMIZATION_LEVEL, "optimizar", opciones, OPTIMIZAR_TODO)
            llamar(DISABLE_CPU_MEM_ARENA, "sin arena", opciones)
            if (!preempaquetar) llamar(ADD_SESSION_CONFIG_ENTRY, "sin preempaquetar", opciones, "session.disable_prepacking", "1")
            val s = PointerByReference()
            llamar(CREATE_SESSION, "abrir " + ruta.substringAfterLast('/'), entorno, ruta, opciones, s)
            val sesion = s.value
            try {
                return Sesion(sesion, nombres(sesion, SESSION_GET_INPUT_COUNT, SESSION_GET_INPUT_NAME), nombres(sesion, SESSION_GET_OUTPUT_COUNT, SESSION_GET_OUTPUT_NAME))
            } catch (e: Throwable) {
                f(RELEASE_SESSION).invokeVoid(arrayOf(sesion)); throw e
            }
        } finally {
            f(RELEASE_SESSION_OPTIONS).invokeVoid(arrayOf(opciones))
        }
    }

    private fun nombres(sesion: Pointer, cuenta: Int, nombre: Int): List<String> {
        val n = LongByReference()
        llamar(cuenta, "contar entradas o salidas", sesion, n)
        return (0 until n.value).map { i ->
            val p = PointerByReference()
            llamar(nombre, "nombre de una entrada o salida", sesion, i, asignador, p)
            val texto = p.value.getString(0)
            llamar(ALLOCATOR_FREE, "soltar un nombre", asignador, p.value)
            texto
        }
    }

    companion object {
        /** La versión de la tabla que se pide: la del `.h` del que salen los índices (`ORT_API_VERSION 17`). */
        private const val VERSION_DE_LA_API = 17

        // Posiciones en `struct OrtApi` (las mismas que `mod indice` de `ort.rs` del PC).
        internal const val GET_ERROR_MESSAGE = 2
        internal const val CREATE_ENV = 3
        internal const val CREATE_SESSION = 7
        internal const val RUN = 9
        internal const val CREATE_SESSION_OPTIONS = 10
        internal const val DISABLE_CPU_MEM_ARENA = 19
        internal const val SET_SESSION_GRAPH_OPTIMIZATION_LEVEL = 23
        internal const val SET_INTRA_OP_NUM_THREADS = 24
        internal const val SESSION_GET_INPUT_COUNT = 30
        internal const val SESSION_GET_OUTPUT_COUNT = 31
        internal const val SESSION_GET_INPUT_NAME = 36
        internal const val SESSION_GET_OUTPUT_NAME = 37
        internal const val CREATE_TENSOR_WITH_DATA_AS_ORT_VALUE = 49
        internal const val GET_TENSOR_MUTABLE_DATA = 51
        internal const val GET_DIMENSIONS_COUNT = 61
        internal const val GET_DIMENSIONS = 62
        internal const val GET_TENSOR_TYPE_AND_SHAPE = 65
        internal const val CREATE_CPU_MEMORY_INFO = 69
        internal const val ALLOCATOR_FREE = 76
        internal const val GET_ALLOCATOR_WITH_DEFAULT_OPTIONS = 78
        internal const val RELEASE_STATUS = 93
        internal const val RELEASE_SESSION = 95
        internal const val RELEASE_VALUE = 96
        internal const val RELEASE_TENSOR_TYPE_AND_SHAPE_INFO = 99
        internal const val RELEASE_SESSION_OPTIONS = 100
        /** `AddSessionConfigEntry` (API 4 y siguientes). */
        internal const val ADD_SESSION_CONFIG_ENTRY = 130
        private const val TAMANO_DE_LA_TABLA = 131

        /** Todos, en el orden de la tabla (lo guarda una prueba). */
        internal val TODOS = intArrayOf(
            GET_ERROR_MESSAGE, CREATE_ENV, CREATE_SESSION, RUN, CREATE_SESSION_OPTIONS, DISABLE_CPU_MEM_ARENA,
            SET_SESSION_GRAPH_OPTIMIZATION_LEVEL, SET_INTRA_OP_NUM_THREADS, SESSION_GET_INPUT_COUNT, SESSION_GET_OUTPUT_COUNT,
            SESSION_GET_INPUT_NAME, SESSION_GET_OUTPUT_NAME, CREATE_TENSOR_WITH_DATA_AS_ORT_VALUE, GET_TENSOR_MUTABLE_DATA,
            GET_DIMENSIONS_COUNT, GET_DIMENSIONS, GET_TENSOR_TYPE_AND_SHAPE, CREATE_CPU_MEMORY_INFO, ALLOCATOR_FREE,
            GET_ALLOCATOR_WITH_DEFAULT_OPTIONS, RELEASE_STATUS, RELEASE_SESSION, RELEASE_VALUE, RELEASE_TENSOR_TYPE_AND_SHAPE_INFO,
            RELEASE_SESSION_OPTIONS, ADD_SESSION_CONFIG_ENTRY
        )

        /** `ORT_LOGGING_LEVEL_WARNING`. */
        private const val NIVEL_AVISOS = 2
        /** `ORT_ENABLE_ALL`. */
        private const val OPTIMIZAR_TODO = 99
        /** `OrtArenaAllocator` y `OrtMemTypeDefault`. */
        private const val ASIGNADOR_ARENA = 1
        private const val MEMORIA_POR_DEFECTO = 0
        /** `ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT`, `…_INT64` y `…_BOOL`. */
        private const val TIPO_F32 = 1
        private const val TIPO_I64 = 7
        private const val TIPO_BOOL = 9

        /** En el teléfono, el del APK (`lib/arm64-v8a/libonnxruntime.so`, el mismo que carga sherpa-onnx). */
        const val DEL_APK = "onnxruntime"

        @Volatile private var delProceso: Carga? = null

        /** El resultado de cargar, guardado: volver a intentar cargar una biblioteca que no está da lo mismo cada vez. */
        private class Carga(val ort: OrtPorJna?, val fallo: String?)

        /**
         * **El runtime del proceso**, cargándolo la primera vez. [biblioteca] es un nombre corto
         * (`onnxruntime`, el del APK) o una ruta entera (las pruebas). Si ya lo cargó sherpa-onnx,
         * `dlopen` devuelve el mismo: un solo runtime en el proceso.
         */
        fun cargar(biblioteca: String = DEL_APK): OrtPorJna {
            delProceso?.let { return it.ort ?: throw ErrorOrt("cargar ONNX Runtime", it.fallo ?: "") }
            synchronized(this) {
                delProceso?.let { return it.ort ?: throw ErrorOrt("cargar ONNX Runtime", it.fallo ?: "") }
                val r = runCatching {
                    if (Native.POINTER_SIZE != 8) throw ErrorOrt("cargar ONNX Runtime", "solo en teléfonos de 64 bits")
                    val lib = NativeLibrary.getInstance(biblioteca)
                    val base = lib.getFunction("OrtGetApiBase").invokePointer(arrayOf())
                        ?: throw ErrorOrt("cargar ONNX Runtime", "OrtGetApiBase devolvió nulo")
                    val getApi = Function.getFunction(base.getPointer(0))
                    val getVersion = Function.getFunction(base.getPointer(Native.POINTER_SIZE.toLong()))
                    val version = getVersion.invokeString(arrayOf(), false) ?: "?"
                    val tabla = getApi.invokePointer(arrayOf(VERSION_DE_LA_API))
                        ?: throw ErrorOrt("cargar ONNX Runtime", "la versión $version no da la API $VERSION_DE_LA_API")
                    OrtPorJna(lib, tabla, version)
                }
                val carga = Carga(r.getOrNull(), r.exceptionOrNull()?.let { it.message ?: it.toString() })
                delProceso = carga
                return carga.ort ?: throw ErrorOrt("cargar ONNX Runtime", carga.fallo ?: "")
            }
        }
    }
}
