package com.forge.pixpin.guardados

import android.util.AtomicFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

/**
 * **Grabar sin perder nada** (28-oct-2026, el usuario: «todo tiene que ser rápido y fiable al grabar:
 * si se perdiera algo sería malo, se perdería una reunión»). Lo de `grabacion_a_medias.rs` del PC
 * (`crates/pixpin-proyecto`, `5595c79f` y `4fec53d3`), **campo por campo**, y su guía
 * (`docs/grabar-sin-perder-nada.md`, «Android»).
 *
 * Mientras se graba una clase o una reunión, todo lo que se va haciendo se apunta **en el disco en el
 * acto**, en un diario por grabación:
 *
 * ```text
 * filesDir/grabaciones-a-medias/<marca>/
 *     estado.json        el Diario: chat, modo, nombre, reloj de pared, latido,
 *                        y lo capturado con su minuto
 *     captura-1.png      cada captura o foto, en cuanto se hace
 *     captura-2.jpg
 * ```
 *
 * La nota se graba en `<audio>.parte` con su respaldo WAV al lado (`<audio>.respaldo.wav`, ver
 * [com.forge.pixpin.audio.RespaldoWav]); el vídeo, en trozos de unos minutos ([Diario.trozos]).
 *
 * - **Al terminar bien** la clase entra en el chat y el diario se borra.
 * - **Si algo se corta** (Android mata la app, se apaga el teléfono), al abrir PixPin quedan diarios:
 *   [pendientes] los encuentra con una sola lectura de carpeta, [hallar] mira qué se puede salvar de
 *   cada uno y [plan] lo dice. Se pregunta «Recuperar / Descartar» ([RecuperarGrabacion]); **nada se
 *   borra sin que se elija Descartar**.
 *
 * El diario se escribe con [AtomicFile] (que es el `.tmp` + renombrar del PC): un corte a mitad de
 * escribirlo deja el anterior entero.
 *
 * ## Lo que lleva de más el del teléfono (no lo lee nadie más: el diario no viaja)
 *
 * - `trozos`: los trozos del vídeo (`MediaMuxer` no hace MP4 fragmentado; ver [TrozosDeVideo]), con
 *   su minuto, su ruta, lo que duran al cerrarse y su `grupo` (los de un mismo tamaño se juntan).
 * - `capturas_del_video`: los minutos de las capturas de una clase con vídeo, que se sacan de sus
 *   fotogramas al terminar (el permiso de la pantalla no da para otra captura a la vez).
 * - Un clip con `ruta` vacía es uno de la reunión con vídeo de la sala: se recorta del vídeo.
 *
 * Lo puro (el JSON, el reloj, [hallar], [plan], [espacio]) se prueba en la JVM; leer y escribir el
 * diario usa [AtomicFile] y se prueba con Robolectric.
 */
object GrabacionAMedias {

    /** La carpeta de los diarios, dentro de `filesDir`. */
    const val CARPETA = "grabaciones-a-medias"
    /** El diario de cada grabación. */
    const val FICHERO = "estado.json"
    /** La versión del formato del diario. */
    const val VERSION = 1

    /** Entre dos latidos, lo que la hora puede adelantar a la nota sin que sea un hueco. */
    const val UMBRAL_HUECO_MS = 10_000L

    /** Lo menos que dura una nota: menos es un resbalón en el botón ([com.forge.pixpin.pin.Voz.MINIMO_MS]). */
    const val MINIMO_NOTA_MS = 700L

    /** Por debajo de esto libre en el disco, se avisa. */
    const val ESPACIO_POCO = 2L * 1024 * 1024 * 1024
    /** Por debajo de esto, se para el vídeo y se sigue solo con el sonido. */
    const val ESPACIO_CRITICO = 500L * 1024 * 1024

    /** Cada cuánto late el diario mientras se graba (el del PC). */
    const val CADA_LATIDO_MS = 5_000L
    /** Cada cuánto se mira el disco mientras se graba (el del PC). */
    const val CADA_ESPACIO_MS = 20_000L

    /** **El JSON del diario**: todos los campos (los `null` también, como `serde`), con sangría de dos. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Un tramo de la hora de pared (ms UTC). `hasta` null: sin cerrar. */
    @Serializable
    data class Tramo(val desde: Long = 0, var hasta: Long? = null)

    /** **El reloj de pared** de la grabación: el mismo que [RelojDePared] ([RelojDeLaGrabacion.delDiario]). */
    @Serializable
    data class Reloj(
        var inicio: Long = 0,
        var fin: Long? = null,
        val pausas: MutableList<Tramo> = mutableListOf(),
        /**
         * Lo que la hora siguió sin que la nota grabara (el teléfono congeló la app): sin estos, una
         * foto con hora de después caería tarde en la nota.
         */
        val huecos: MutableList<Tramo> = mutableListOf()
    )

    /** El último latido: a qué hora la nota iba por qué minuto. */
    @Serializable
    data class Latido(val utc: Long = 0, val ms: Long = 0, val pausa: Boolean = false)

    /** Una captura (de la pantalla o foto de la cámara), ya en el disco, en la carpeta del diario. */
    @Serializable
    data class CapturaApuntada(
        val ms: Long = 0,
        val fichero: String = "",
        @SerialName("de_camara") val deCamara: Boolean = false
    )

    /** Un clip de la cámara: dónde se graba y, al cerrarse, lo que dura. Ruta vacía: sale del vídeo. */
    @Serializable
    data class ClipApuntado(
        val ms: Long = 0,
        val ruta: String = "",
        @SerialName("duracion_ms") var duracionMs: Long? = null
    )

    /** Un trozo del vídeo (solo en el teléfono). [grupo]: los del mismo tamaño se juntan. */
    @Serializable
    data class TrozoApuntado(
        val ms: Long = 0,
        val ruta: String = "",
        @SerialName("duracion_ms") var duracionMs: Long? = null,
        val grupo: Int = 0
    )

    /** Una marca puesta a mano en un minuto. */
    @Serializable
    data class Marcador(val ms: Long = 0, val texto: String = "")

    /** **El diario de una grabación**: los campos del PC en su orden, y los del teléfono detrás. */
    @Serializable
    data class Diario(
        var version: Int = 0,
        /** Su nombre de carpeta: la hora de empezar, en ms UTC. */
        var marca: String = "",
        /** El chat donde va (vacío: el general) y su nombre para enseñarlo. */
        var proyecto: String = "",
        @SerialName("nombre_proyecto") var nombreProyecto: String = "",
        /** El nombre de la nota: «Clase 28-10 10h05». */
        var nombre: String = "",
        /** `pantalla`, `capturas` o `presencial` ([Presencial.Modo.palabra]). */
        var modo: String = "",
        var microfono: Boolean = false,
        var equipo: Boolean = false,
        /** Dónde se graba la nota (sin el `.parte`; el respaldo va al lado). */
        var audio: String = "",
        /** Dónde va el vídeo ya junto, si se graba. */
        var video: String? = null,
        val reloj: Reloj = Reloj(),
        var latido: Latido = Latido(),
        val capturas: MutableList<CapturaApuntada> = mutableListOf(),
        val clips: MutableList<ClipApuntado> = mutableListOf(),
        val marcadores: MutableList<Marcador> = mutableListOf(),
        /** La grabación que se anunció a otro aparato, para recoger lo que llegó de él. */
        var movil: String? = null,
        /** Lo demás ya entró en el chat y solo falta la nota. */
        @SerialName("solo_la_nota") var soloLaNota: Boolean = false,
        // --- Solo del teléfono ---
        val trozos: MutableList<TrozoApuntado> = mutableListOf(),
        @SerialName("capturas_del_video") val capturasDelVideo: MutableList<Long> = mutableListOf()
    ) {
        /** Su carpeta, dentro de la de los diarios de [raiz] (`filesDir`). */
        fun carpeta(raiz: File): File = File(carpetaDeDiarios(raiz), marca)

        /** El nombre del siguiente fichero de captura (`captura-N.ext`), sin apuntarla aún. */
        fun ficheroDeCaptura(numero: Int, extension: String): String =
            "captura-$numero.${extension.trim().trimStart('.').ifEmpty { "png" }}"

        /** **Guarda una captura** en [dir] en el acto (`captura-N.ext`) y la apunta. Hay que [guardar] después. */
        fun apuntarCaptura(dir: File, ms: Long, bytes: ByteArray, deCamara: Boolean, extension: String): File {
            dir.mkdirs()
            val fichero = ficheroDeCaptura(capturas.size + 1, extension)
            val f = File(dir, fichero)
            escribirEntero(f, bytes)
            capturas += CapturaApuntada(ms, fichero, deCamara)
            return f
        }

        /** Apunta una captura que ya está escrita en la carpeta del diario (la foto de la cámara). */
        fun apuntarCaptura(ms: Long, fichero: String, deCamara: Boolean) {
            capturas += CapturaApuntada(ms, fichero, deCamara)
        }

        /** Un clip empieza en [ms], grabándose en [ruta]. */
        fun empezarClip(ms: Long, ruta: String) {
            clips += ClipApuntado(ms, ruta, null)
        }

        /** El clip de [ruta] se cerró y dura [duracionMs]. */
        fun cerrarClip(ruta: String, duracionMs: Long) {
            clips.lastOrNull { it.ruta == ruta }?.duracionMs = duracionMs.coerceAtLeast(0)
        }

        /** Un trozo del vídeo empieza en [ms]. */
        fun empezarTrozo(ms: Long, ruta: String, grupo: Int) {
            trozos += TrozoApuntado(ms, ruta, null, grupo)
        }

        /** El trozo de [ruta] se cerró y dura [duracionMs]. */
        fun cerrarTrozo(ruta: String, duracionMs: Long) {
            trozos.lastOrNull { it.ruta == ruta }?.duracionMs = duracionMs.coerceAtLeast(0)
        }

        /**
         * **Un latido**: a la hora [utc] la nota va por [ms]. Lleva las pausas y apunta un hueco si la
         * hora corrió mucho más que la nota sin estar en pausa (el teléfono congeló la app). Devuelve si
         * cambió algo que merezca escribirse ya (una pausa, un hueco). `Diario::latir` del PC.
         */
        fun latir(utc: Long, ms: Long, pausa: Boolean): Boolean {
            val antes = latido
            latido = Latido(utc, ms, pausa)
            var cambio = false
            if (pausa && !antes.pausa) {
                reloj.pausas += Tramo(utc, null)
                cambio = true
            } else if (!pausa && antes.pausa) {
                reloj.pausas.lastOrNull()?.takeIf { it.hasta == null }?.hasta = utc
                cambio = true
            } else if (!pausa) {
                val sinGrabar = (utc - antes.utc) - (ms - antes.ms)
                if (sinGrabar > UMBRAL_HUECO_MS) {
                    // La nota se paró donde iba y siguió después: el hueco es lo que la hora corrió de
                    // más, justo antes de este latido.
                    reloj.huecos += Tramo(utc - sinGrabar, utc)
                    cambio = true
                }
            }
            return cambio
        }

        /** Acaba el reloj a la hora [utc]. */
        fun acabar(utc: Long) {
            reloj.pausas.lastOrNull()?.takeIf { it.hasta == null }?.hasta = utc
            reloj.fin = utc
        }

        /**
         * **El minuto de la nota** a la hora de pared [utc]: lo que pasó desde el inicio menos las pausas
         * y los huecos de antes. Nunca negativo. La misma cuenta que [RelojDeLaGrabacion.msHasta].
         */
        fun msEn(utc: Long): Long = RelojDeLaGrabacion.msHasta(RelojDeLaGrabacion.delDiario(reloj, "", 0), utc)

        /** **Lo capturado en orden**, con su minuto dentro de la nota de [duracionMs]. */
        fun lineaDeTiempo(duracionMs: Long): List<Pair<Long, Elemento>> {
            val tope = duracionMs.coerceAtLeast(0)
            val v = capturas.mapIndexed { i, c -> c.ms.coerceIn(0, tope) to Elemento(Tipo.CAPTURA, i) } +
                clips.mapIndexed { i, c -> c.ms.coerceIn(0, tope) to Elemento(Tipo.CLIP, i) } +
                marcadores.mapIndexed { i, m -> m.ms.coerceIn(0, tope) to Elemento(Tipo.MARCADOR, i) }
            return v.sortedWith(compareBy<Pair<Long, Elemento>>({ it.first }, { it.second.tipo.ordinal }, { it.second.i }))
        }

        /** **Lo apunta** en [dir] (que se crea si no está), entero o nada. */
        fun guardar(dir: File) {
            dir.mkdirs()
            escribirEntero(File(dir, FICHERO), aJson().toByteArray())
        }

        /** El JSON del diario. */
        fun aJson(): String = json.encodeToString(serializer(), this)

        /** Una copia profunda (lo que se escribe en otro hilo). */
        fun copia(): Diario = deJson(aJson())

        companion object {
            /** Un diario nuevo para una grabación que empieza a la hora [inicio]. `Diario::nuevo` del PC. */
            fun nuevo(inicio: Long, proyecto: String, nombre: String, audio: String): Diario = Diario(
                version = VERSION,
                marca = inicio.toString(),
                proyecto = proyecto,
                nombre = nombre,
                audio = audio,
                reloj = Reloj(inicio = inicio),
                latido = Latido(inicio, 0, false)
            )

            fun deJson(texto: String): Diario = json.decodeFromString(serializer(), texto)

            /**
             * Lo lee de [dir]. Si el bueno no se entiende (no debería: se escribe entero), prueba con lo
             * que quedara de la escritura a medias.
             */
            fun leer(dir: File): Diario? {
                val f = File(dir, FICHERO)
                val de = { bytes: ByteArray -> runCatching { deJson(String(bytes)) }.getOrNull() }
                runCatching { AtomicFile(f).readFully() }.getOrNull()?.let(de)?.let { return it }
                for (sufijo in listOf(".new", ".bak", ".tmp")) {
                    val otro = File(dir, FICHERO + sufijo)
                    if (otro.isFile) runCatching { otro.readBytes() }.getOrNull()?.let(de)?.let { return it }
                }
                return null
            }
        }
    }

    /** Un elemento de la [Diario.lineaDeTiempo], por su posición en su lista. */
    data class Elemento(val tipo: Tipo, val i: Int)

    /** De qué lista es un [Elemento] (en el orden del PC al empatar en el minuto). */
    enum class Tipo { CAPTURA, CLIP, MARCADOR }

    /** Escribe [bytes] en [f] sin que un corte deje algo a medias ([AtomicFile]: `.new` + renombrar). */
    fun escribirEntero(f: File, bytes: ByteArray) {
        val a = AtomicFile(f)
        val s = a.startWrite()
        try {
            s.write(bytes)
            a.finishWrite(s)
        } catch (e: Throwable) {
            a.failWrite(s)
            throw e
        }
    }

    /** La carpeta de todos los diarios. */
    fun carpetaDeDiarios(raiz: File): File = File(raiz, CARPETA)

    /**
     * **Los diarios que quedaron** en [raiz]: grabaciones que no llegaron a guardarse. Una sola lectura de
     * carpeta (y un fichero por diario). Las más viejas primero.
     */
    fun pendientes(raiz: File): List<Pair<File, Diario>> =
        (carpetaDeDiarios(raiz).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { d -> Diario.leer(d)?.let { d to it } }
            .sortedBy { it.second.reloj.inicio }

    /** [ruta] con [sufijo] añadido al nombre entero. */
    fun conSufijo(ruta: String, sufijo: String): File = File(ruta + sufijo)

    /**
     * **Descartar**: lo pidió el usuario. Se borra la carpeta del diario y lo que dice que es suyo (la nota
     * a medias, su respaldo, el vídeo y sus trozos, los clips). Lo que ya entró en un chat no se toca.
     */
    fun descartar(dir: File, d: Diario) {
        val rutas = mutableListOf<File>()
        if (d.audio.isNotEmpty()) {
            rutas += File(d.audio)
            rutas += conSufijo(d.audio, ".parte")
            rutas += conSufijo(d.audio, ".respaldo.wav")
            rutas += conSufijo(d.audio, ".rehecho.parte")
        }
        d.video?.takeIf { it.isNotEmpty() }?.let { rutas += File(it); rutas += conSufijo(it, ".parte") }
        d.clips.filter { it.ruta.isNotEmpty() }.forEach { rutas += File(it.ruta) }
        d.trozos.filter { it.ruta.isNotEmpty() }.forEach { rutas += File(it.ruta) }
        rutas.forEach { runCatching { it.delete() } }
        runCatching { dir.deleteRecursively() }
    }

    // --- Mirar un MP4 ----------------------------------------------------------------------------

    /** **Cómo quedó un MP4** (`pixpin_sistema::mp4::EstadoMp4` del PC). */
    enum class EstadoMp4 {
        /** No existe, está vacío o no es un MP4. */
        NADA,
        /** Cortado sin índice: no se puede abrir. */
        SIN_INDICE,
        /** Fragmentado sin `mfra`/`sidx`: se abre. */
        FRAGMENTADO,
        /** Fragmentado con su índice. */
        FRAGMENTADO_CON_INDICE,
        /** Cerrado bien (`moov`). */
        CERRADO;

        /** Si se puede abrir tal cual. */
        val seAbre: Boolean get() = this == CERRADO || this == FRAGMENTADO || this == FRAGMENTADO_CON_INDICE
    }

    /** Las cajas de arriba de un MP4, en orden. Para en la primera sin cabecera entera. */
    fun cajas(f: File): List<String> {
        val v = ArrayList<String>()
        RandomAccessFile(f, "r").use { r ->
            val largo = r.length()
            var pos = 0L
            val h = ByteArray(8)
            while (pos + 8 <= largo && v.size < 100_000) {
                r.seek(pos)
                r.readFully(h)
                val tipo = h.copyOfRange(4, 8)
                if (!tipo.all { (it in 0x21..0x7e) || it == 0x20.toByte() }) break
                val corto = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
                    ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
                val tam = when (corto) {
                    0L -> largo - pos
                    1L -> {
                        if (pos + 16 > largo) break
                        r.readLong()
                    }
                    else -> corto
                }
                if (tam < 8) break
                v += String(tipo, Charsets.ISO_8859_1)
                val siguiente = pos + tam
                if (siguiente < pos) break
                pos = siguiente
            }
        }
        return v
    }

    /** **Cómo quedó** el MP4 de [f] (solo las cabeceras de las cajas: microsegundos). */
    fun mirar(f: File?): EstadoMp4 {
        if (f == null || !f.isFile) return EstadoMp4.NADA
        val v = runCatching { cajas(f) }.getOrNull() ?: return EstadoMp4.NADA
        return when {
            "moof" in v -> if ("mfra" in v || "sidx" in v) EstadoMp4.FRAGMENTADO_CON_INDICE else EstadoMp4.FRAGMENTADO
            "moov" in v -> EstadoMp4.CERRADO
            "ftyp" in v || "mdat" in v -> EstadoMp4.SIN_INDICE
            else -> EstadoMp4.NADA
        }
    }

    // --- Hallar y el plan ------------------------------------------------------------------------

    /** Lo que se encontró en el disco de una grabación cortada. */
    data class Hallado(
        /** La nota ya cerrada en su nombre. */
        val audioListo: Boolean,
        /** Cómo quedó el `.parte` de la nota. */
        val audioParte: EstadoMp4,
        /** Lo que dura el respaldo WAV, si lo hay y tiene algo. */
        val respaldoMs: Long?,
        /** El vídeo ya junto en su nombre. */
        val videoListo: Boolean,
        /** Cómo quedó lo grabado del vídeo (sus trozos, o su `.parte`). */
        val videoParte: EstadoMp4,
        /** Las capturas del diario cuyo fichero sigue ahí. */
        val capturas: List<Int>,
        /** Los clips con fichero que se abre. */
        val clips: List<Int>,
        /** Los clips que salen del vídeo (ruta vacía y cerrados). */
        val clipsDelVideo: List<Int> = emptyList(),
        /** Los trozos del vídeo que se abren. */
        val trozos: List<Int> = emptyList()
    )

    /**
     * **Mira el disco**: qué queda de la grabación del diario [d] (en [dir]). [respaldoMs] lee el
     * respaldo WAV ([com.forge.pixpin.audio.RespaldoWav.duracionMs]).
     */
    fun hallar(dir: File, d: Diario, respaldoMs: (File) -> Long?): Hallado {
        val audio = d.audio.takeIf { it.isNotEmpty() }
        val video = d.video?.takeIf { it.isNotEmpty() }
        val trozos = d.trozos.indices.filter { d.trozos[it].ruta.isNotEmpty() && mirar(File(d.trozos[it].ruta)).seAbre }
        val videoParte = when {
            d.trozos.isEmpty() -> video?.let { mirar(conSufijo(it, ".parte")) } ?: EstadoMp4.NADA
            trozos.isNotEmpty() -> EstadoMp4.CERRADO
            d.trozos.any { it.ruta.isNotEmpty() && mirar(File(it.ruta)) == EstadoMp4.SIN_INDICE } -> EstadoMp4.SIN_INDICE
            else -> EstadoMp4.NADA
        }
        return Hallado(
            audioListo = audio != null && mirar(File(audio)) == EstadoMp4.CERRADO,
            audioParte = audio?.let { mirar(conSufijo(it, ".parte")) } ?: EstadoMp4.NADA,
            respaldoMs = audio?.let { respaldoMs(conSufijo(it, ".respaldo.wav")) }?.takeIf { it > 0 },
            videoListo = video != null && mirar(File(video)) == EstadoMp4.CERRADO,
            videoParte = videoParte,
            capturas = d.capturas.indices.filter { File(dir, d.capturas[it].fichero).isFile && d.capturas[it].fichero.isNotEmpty() },
            clips = d.clips.indices.filter { d.clips[it].ruta.isNotEmpty() && mirar(File(d.clips[it].ruta)).seAbre },
            clipsDelVideo = d.clips.indices.filter { d.clips[it].ruta.isEmpty() && (d.clips[it].duracionMs ?: 0) > 0 },
            trozos = trozos
        )
    }

    /** De dónde sale la nota recuperada. */
    enum class Nota {
        /** Ya estaba cerrada en su nombre. */
        LISTA,
        /** Del `.parte`, que llegó a cerrarse. */
        DEL_PARTE,
        /** Rehecha del respaldo WAV. */
        DEL_RESPALDO,
        /** No hay nota que salvar. */
        NINGUNA
    }

    /** De dónde sale el vídeo recuperado. */
    enum class Video {
        LISTO,
        /** De lo grabado (en el teléfono, de sus trozos que se abren). */
        DEL_PARTE,
        /** Hay vídeo pero ninguno se abre: se conserva hasta Descartar. */
        PERDIDO,
        NINGUNO
    }

    /** **Qué se va a recuperar** de una grabación cortada. */
    data class Plan(
        val nota: Nota,
        /** Lo que dura la nota (del respaldo, o del último latido). */
        val duracionMs: Long,
        val video: Video,
        val capturas: List<Int>,
        val clips: List<Int>,
        val trozos: List<Int> = emptyList(),
        val clipsDelVideo: List<Int> = emptyList(),
        /** Las capturas que salen de los fotogramas del vídeo (sus minutos). */
        val capturasDelVideo: List<Long> = emptyList()
    ) {
        /** Si hay algo que salvar (si no, solo queda descartar). */
        fun algo(): Boolean = nota != Nota.NINGUNA || video == Video.LISTO || video == Video.DEL_PARTE ||
            capturas.isNotEmpty() || clips.isNotEmpty()

        /** Los minutos para el aviso, redondeados (al menos 1 si hay algo). */
        fun minutos(): Long {
            val m = (duracionMs + 30_000) / 60_000
            return if (duracionMs > 0) maxOf(m, 1) else 0
        }
    }

    /**
     * **El plan** para el diario [d] con lo [Hallado]. La nota, por orden: la ya cerrada, el `.parte`
     * cerrado, el respaldo. Lo que dura sale del respaldo (exacto) o del último latido. `plan` del PC.
     */
    fun plan(d: Diario, h: Hallado): Plan {
        val nota = when {
            h.audioListo -> Nota.LISTA
            h.audioParte == EstadoMp4.CERRADO -> Nota.DEL_PARTE
            (h.respaldoMs ?: 0) >= MINIMO_NOTA_MS -> Nota.DEL_RESPALDO
            else -> Nota.NINGUNA
        }
        val video = when {
            d.soloLaNota -> Video.NINGUNO
            h.videoListo -> Video.LISTO
            h.videoParte.seAbre -> Video.DEL_PARTE
            h.videoParte == EstadoMp4.SIN_INDICE -> Video.PERDIDO
            else -> Video.NINGUNO
        }
        val conVideo = video == Video.LISTO || video == Video.DEL_PARTE
        return Plan(
            nota = nota,
            duracionMs = (h.respaldoMs ?: d.latido.ms).coerceAtLeast(0),
            video = video,
            capturas = if (d.soloLaNota) emptyList() else h.capturas,
            clips = if (d.soloLaNota) emptyList() else h.clips,
            trozos = if (video == Video.DEL_PARTE) h.trozos else emptyList(),
            clipsDelVideo = if (conVideo) h.clipsDelVideo else emptyList(),
            capturasDelVideo = if (conVideo) d.capturasDelVideo.toList() else emptyList()
        )
    }

    // --- El disco --------------------------------------------------------------------------------

    /** Lo que dice el disco de cuánto cabe. */
    enum class Espacio {
        BIEN,
        /** Menos de [ESPACIO_POCO]: avisar. */
        POCO,
        /** Menos de [ESPACIO_CRITICO]: parar el vídeo, seguir con el sonido. */
        CRITICO
    }

    /** **Cuánto cabe**, con [libres] bytes libres (null: no se sabe, y se sigue como si cupiera). */
    fun espacio(libres: Long?): Espacio = when {
        libres == null -> Espacio.BIEN
        libres < ESPACIO_CRITICO -> Espacio.CRITICO
        libres < ESPACIO_POCO -> Espacio.POCO
        else -> Espacio.BIEN
    }
}
