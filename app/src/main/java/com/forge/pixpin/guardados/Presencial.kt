package com.forge.pixpin.guardados

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **Grabar una reunión presencial** (28-oct-2026, lo de `crates/pixpin-proyecto/src/presencial.rs`
 * del PC del 27-oct-2026; el usuario: «supón que estoy en una reunión PRESENCIAL: que siga grabando
 * aunque haya salido de la app, que aparezca un botón flotante para tomar FOTOS de la reunión y, como
 * las capturas, que todo se adjunte a la reunión; y las fotos y las capturas a resolución COMPLETA»).
 *
 * Lo puro, sin Android, con las mismas cuentas que el PC para que los dos guarden lo mismo:
 *
 * - **Los tres modos** de «Grabar clase» ([Modo]) y, en el teléfono, **las dos formas** de la
 *   presencial ([Forma]): el vídeo de la sala o solo el audio.
 * - **Qué cámara** ([camaraElegida]), **a qué resolución** ([mejorModo], [modoParaClip]) y **cuántos
 *   bits** ([bitsDelClip], [bitsDeLaReunion]).
 * - **El minuto y el nombre de cada foto** ([msDeLaFoto], [nombreDeLaFoto]).
 * - **De dónde salió** una imagen o un clip: `fuente` ([FUENTE], [DE_CAMARA], [DEL_MOVIL]). **Nunca
 *   `origen`**: es un campo del mensaje ([Mensaje.origen]); escrito también en el `resto` del PC, la
 *   línea salía con `"origen"` dos veces y el PC no la podía leer. Lo ya escrito así se rescata al
 *   leer ([rescatar]), como `cuaderno::rescatar` del PC (`92b2843e`).
 * - **Los clips** de la nota: `clips: [{ms, id, duracionMs}]` en orden de `ms` ([clipsEnJson],
 *   [clipsDe]) y en cada clip `enGrabacion: {de, ms, duracionMs}` ([enlaceDelClip]). **Sin clips la
 *   clave se quita** (null, nunca `[]`).
 * - **Cuándo hace falta la imagen entera** al acercarla en «Estudiar la clase» ([haceFaltaLaEntera]).
 *
 * Las claves de cada objeto van **en orden alfabético**, como las escribe el PC (`serde_json` sin
 * `preserve_order`): `{"duracionMs":…,"id":…,"ms":…}` y `{"de":…,"duracionMs":…,"ms":…}`. Así lo que
 * sale de aquí es byte a byte lo del PC y una fusión que compare el texto no ve cambios donde no los
 * hay. Se prueba en `PresencialTest`.
 */
object Presencial {

    // --- Los modos ----------------------------------------------------------------------------

    /** **Qué se graba**: el selector de arriba de «Grabar clase», como `presencial::Modo` del PC. */
    enum class Modo(val palabra: String) {
        /** El vídeo de la pantalla con su sonido. */
        PANTALLA("pantalla"),
        /** Solo el sonido, con capturas de la pantalla entera. */
        CAPTURAS("capturas"),
        /** Una reunión en persona: el micrófono y la cámara (fotos y clips). */
        PRESENCIAL("presencial");

        /** El siguiente del selector, en rueda. */
        fun siguiente(): Modo = when (this) {
            PANTALLA -> CAPTURAS
            CAPTURAS -> PRESENCIAL
            PRESENCIAL -> PANTALLA
        }

        /** El anterior del selector, en rueda. */
        fun anterior(): Modo = when (this) {
            PANTALLA -> PRESENCIAL
            CAPTURAS -> PANTALLA
            PRESENCIAL -> CAPTURAS
        }

        /**
         * **Los sonidos al elegirlo**, (micrófono, equipo). En persona no hay reunión en el equipo que
         * oír. En el teléfono el sonido del equipo no se graba nunca (ver `clase_solo_microfono`), pero
         * la cuenta es la del PC.
         */
        fun sonidos(): Pair<Boolean, Boolean> = if (this == PRESENCIAL) true to false else true to true

        /** Si se graba el vídeo de la pantalla. */
        fun conVideo(): Boolean = this == PANTALLA

        /** Si el botón grande es «Foto» (la cámara) y no «Captura». */
        fun conCamara(): Boolean = this == PRESENCIAL

        companion object {
            /** Los tres, en el orden del selector. */
            val TODOS = listOf(PANTALLA, CAPTURAS, PRESENCIAL)

            /** Lo recordado. Lo que no se entiende (o nada) es el de capturas: el que menos ocupa. */
            fun de(palabra: String?): Modo = when (palabra?.trim()) {
                "pantalla" -> PANTALLA
                "presencial" -> PRESENCIAL
                else -> CAPTURAS
            }
        }
    }

    /**
     * **Las dos formas de la presencial en el teléfono** (§3.3 de las novedades del PC, «En el móvil
     * es el uso natural»): [VIDEO], el móvil apoyado mirando a la sala, graba el vídeo entero de la
     * reunión; [AUDIO], el móvil en la mesa, la nota de voz de siempre. En las dos hay «Foto» y «Clip».
     */
    enum class Forma(val clave: String) {
        VIDEO("video"),
        AUDIO("audio");

        companion object {
            /** Lo recordado; lo que no se entiende, solo audio (lo que menos ocupa). */
            fun de(clave: String?): Forma = entries.firstOrNull { it.clave == clave?.trim() } ?: AUDIO
        }
    }

    /**
     * **La calidad del vídeo de la reunión**: [ALTA] 1080p30 (~5 Mbps H.264, ~4 HEVC), [LIGERA] 720p
     * (~2,5 Mbps) para las largas y [AUTOMATICA], 1080p la primera hora y 720p después
     * ([altoDelVideo]).
     */
    enum class Calidad(val clave: String) {
        ALTA("1080"),
        LIGERA("720"),
        AUTOMATICA("auto");

        companion object {
            /** Lo recordado; lo que no se entiende, la automática. */
            fun de(clave: String?): Calidad = entries.firstOrNull { it.clave == clave?.trim() } ?: AUTOMATICA
        }
    }

    /** Una hora: pasada, la calidad automática baja a 720p. */
    const val UNA_HORA_MS = 3_600_000L

    /** **El lado corto del vídeo de la reunión** que toca a los [llevadoMs] de grabación. */
    fun altoDelVideo(calidad: Calidad, llevadoMs: Long): Int = when (calidad) {
        Calidad.ALTA -> 1080
        Calidad.LIGERA -> 720
        Calidad.AUTOMATICA -> if (llevadoMs >= UNA_HORA_MS) 720 else 1080
    }

    /**
     * Si hay que **cambiar a 720p** ahora: la automática, pasada la hora y grabando aún a más. El vídeo
     * sigue en otro tramo (ver [Tramo]); los codificadores no cambian de tamaño a mitad de fichero.
     */
    fun tocaBajar(calidad: Calidad, llevadoMs: Long, altoActual: Int): Boolean =
        altoActual > altoDelVideo(calidad, llevadoMs)

    /**
     * **Los bits por segundo del vídeo de la reunión** (§3.3: «1080p30 a ~4–6 Mbps VBR, o 720p a
     * ~2,5 Mbps»): con HEVC algo menos para la misma calidad. Es la media: se codifica a ritmo variable.
     */
    fun bitsDeLaReunion(alto: Int, hevc: Boolean): Int = when {
        alto >= 1080 -> if (hevc) 4_000_000 else 5_000_000
        else -> if (hevc) 2_000_000 else 2_500_000
    }

    /** Fotogramas por segundo del vídeo y de los clips. */
    const val FPS = 30

    /** El sonido del vídeo de la reunión: AAC mono a 48 000 y 96 kb/s, el de la Grabadora del PC. */
    const val SONIDO_BITS = 96_000
    const val SONIDO_MUESTREO = 48_000

    // --- La cámara ----------------------------------------------------------------------------

    /** Dónde está la cámara en el aparato. */
    enum class Lado {
        /** La de la pantalla: mira a quien graba. */
        DELANTE,
        /** La de atrás: mira a la sala. */
        DETRAS,
        /** Una suelta (USB) o que no dice dónde está. */
        FUERA;

        /** Lo que se prefiere para una reunión en persona: menos es mejor. */
        internal val preferencia: Int get() = when (this) {
            DETRAS -> 0
            FUERA -> 1
            DELANTE -> 2
        }
    }

    /**
     * **La cámara con que se empieza**: la recordada si sigue; si no, la que mejor mira a la sala
     * (atrás, luego una USB, luego la de delante) y, entre iguales, la primera que da el sistema. Null
     * sin cámaras.
     */
    fun camaraElegida(camaras: List<Pair<String, Lado>>, recordada: String?): Int? {
        val r = recordada?.trim().orEmpty()
        if (r.isNotEmpty()) camaras.indexOfFirst { it.first == r }.takeIf { it >= 0 }?.let { return it }
        return camaras.indices.minWithOrNull(compareBy<Int>({ camaras[it].second.preferencia }, { it }))
    }

    /** Una forma de dar imagen que tiene la cámara: su tamaño, su formato y sus fps (0 si no lo dice). */
    data class ModoDeCamara(val ancho: Int, val alto: Int, val formato: String, val fps: Int = 0) {
        internal val pixeles: Long get() = ancho.toLong() * alto.toLong()
        internal val enJpeg: Boolean get() = formato.trim().uppercase() in setOf("MJPG", "JPEG", "JPG")
    }

    /**
     * **La resolución de las fotos: la mayor que tenga la cámara.** Gana la de más píxeles; entre dos
     * iguales, la que ya llega en JPEG; luego la primera. Los modos sin tamaño no cuentan. Null si no
     * queda ninguno.
     */
    fun mejorModo(modos: List<ModoDeCamara>): Int? =
        modos.indices.filter { modos[it].ancho > 0 && modos[it].alto > 0 }
            .maxWithOrNull(compareBy<Int>({ modos[it].pixeles }, { modos[it].enJpeg }, { -it }))

    /** El tope de los clips: 1080p a 30 fps. */
    const val CLIP_ANCHO_MAXIMO = 1920
    const val CLIP_ALTO_MAXIMO = 1080
    const val CLIP_FPS_MAXIMO = 30

    /**
     * **El modo de los clips**: el de más píxeles que no pase de 1080p (en cualquiera de los dos lados:
     * la cámara puede ir de pie) ni de 30 fps; entre iguales, el de más fps y luego el que no llega en
     * JPEG. Null si no hay ninguno así.
     */
    fun modoParaClip(modos: List<ModoDeCamara>): Int? =
        modos.indices.filter {
            val m = modos[it]
            m.ancho > 0 && m.alto > 0 && maxOf(m.ancho, m.alto) <= CLIP_ANCHO_MAXIMO &&
                minOf(m.ancho, m.alto) <= CLIP_ALTO_MAXIMO && m.fps <= CLIP_FPS_MAXIMO
        }.maxWithOrNull(compareBy<Int>({ modos[it].pixeles }, { modos[it].fps }, { !modos[it].enJpeg }, { -it }))

    /**
     * **Lo que pesa un clip por segundo**, en bits: ~5 Mbps a 1080p y en proporción por debajo, nunca
     * menos de 1 Mbps ni más de 6; con HEVC un 30 % menos. Es la media, no el tope.
     */
    fun bitsDelClip(ancho: Int, alto: Int, hevc: Boolean): Int {
        val pixeles = ancho.coerceAtLeast(0).toLong() * alto.coerceAtLeast(0).toLong()
        val base = 1920L * 1080L
        val h264 = (5_000_000L * pixeles / base).coerceIn(1_000_000L, 6_000_000L)
        val bits = if (hevc) h264 * 7 / 10 else h264
        return bits.coerceAtLeast(1_000_000L).toInt()
    }

    /** El sonido de los clips: AAC a 96 kb/s. */
    const val CLIP_BITS_SONIDO = 96_000

    // --- Los clips ----------------------------------------------------------------------------

    /** Un clip de vídeo de la reunión: cuándo empezó, cuánto dura y su mensaje. */
    data class Clip(val ms: Long, val duracionMs: Long, val id: String)

    /** La clave de los clips en la nota de voz. */
    const val CLIPS = "clips"

    /**
     * Lo que se apunta en la nota: sus clips en orden, `[{duracionMs, id, ms}]` (las claves en el orden
     * del PC). **Null sin clips**: la clave se quita y una clase sin ellos es byte a byte la de antes.
     */
    fun clipsEnJson(clips: List<Clip>): JsonArray? =
        if (clips.isEmpty()) null else buildJsonArray {
            for (c in clips.sortedBy { it.ms }) add(buildJsonObject {
                put("duracionMs", JsonPrimitive(c.duracionMs))
                put("id", JsonPrimitive(c.id))
                put("ms", JsonPrimitive(c.ms))
            })
        }

    /** Los clips de una nota, en orden. Una entrada rota se salta sin llevarse las demás. */
    fun clipsDe(lista: JsonArray?): List<Clip> =
        lista.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val ms = numero(o["ms"]) ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            Clip(ms.coerceAtLeast(0), (numero(o["duracionMs"]) ?: 0L).coerceAtLeast(0), id)
        }.sortedBy { it.ms }

    /** Los clips de la nota [m]. */
    fun clipsDe(m: Mensaje): List<Clip> = clipsDe(m.clips)

    /**
     * **Añade un clip a la clase**, en su sitio (`clase::poner_clip` del PC): un id que ya estaba no se
     * repite (null: no cambió).
     */
    fun ponerClip(lista: JsonArray?, ms: Long, duracionMs: Long, id: String): JsonArray? {
        val antes = clipsDe(lista)
        if (antes.any { it.id == id }) return null
        return clipsEnJson(antes + Clip(ms.coerceAtLeast(0), duracionMs.coerceAtLeast(0), id))
    }

    /** El clip que suena en [ms] (el último que empezó, si se pisan). Null entre clips. */
    fun clipEn(clips: List<Clip>, ms: Long): Int? =
        clips.indexOfLast { ms >= it.ms && ms < it.ms + it.duracionMs.coerceAtLeast(1) }.takeIf { it >= 0 }

    /** **El enlace de un clip con su grabación**: `{de, duracionMs, ms}`. */
    fun enlaceDelClip(de: String, ms: Long, duracionMs: Long): JsonObject = buildJsonObject {
        put("de", JsonPrimitive(de))
        put("duracionMs", JsonPrimitive(duracionMs.coerceAtLeast(0)))
        put("ms", JsonPrimitive(ms.coerceAtLeast(0)))
    }

    /** Lo que dura un clip según su `enGrabacion` (0 si no lo dice: una captura). */
    fun duracionDelEnlace(m: Mensaje): Long = (numero(m.enGrabacion?.get("duracionMs")) ?: 0L).coerceAtLeast(0)

    // --- Las fotos ----------------------------------------------------------------------------

    /** **El minuto de una foto**: el de cuando se pulsó, sin pasar de lo grabado. */
    fun msDeLaFoto(llevadoAlPulsar: Long, duracion: Long? = null): Long {
        val ms = llevadoAlPulsar.coerceAtLeast(0)
        return if (duracion == null) ms else minOf(ms, duracion.coerceAtLeast(0))
    }

    /**
     * **El nombre del fichero de una imagen de la grabación**: `foto-…` las de la cámara y `clase-…` las
     * capturas, con la hora en que se guardó y su número. Sin extensión, `png`.
     */
    fun nombreDeLaFoto(deCamara: Boolean, cuandoMs: Long, numero: Int, extension: String): String {
        val tronco = if (deCamara) "foto" else "clase"
        val ext = extension.trim().trimStart('.').ifEmpty { "png" }.lowercase()
        return "$tronco-$cuandoMs-$numero.$ext"
    }

    /** **El nombre de un clip**: «Reunión 28-10 10h05 · clip 2.mp4», como el PC. */
    fun nombreDelClip(nombre: String, numero: Int): String = "$nombre · clip $numero.mp4"

    // --- fuente -------------------------------------------------------------------------------

    /** **La clave de dónde salió** una imagen o un clip. No `origen`: ver [rescatar]. */
    const val FUENTE = "fuente"
    /** Lo de la cámara del aparato que graba (la reunión presencial). */
    const val DE_CAMARA = "camara"
    /** Lo de la cámara del móvil haciendo de cámara del PC (§3.4). */
    const val DEL_MOVIL = "movil"

    /** De dónde salió: `camara`, `movil` o null (la pantalla, y todo lo de antes). */
    fun fuenteDe(m: Mensaje): String? = m.fuente

    /** Si una imagen o un clip salió de la cámara (y no de la pantalla). */
    fun esDeCamara(m: Mensaje): Boolean = m.fuente == DE_CAMARA

    /** Si lo mandó el móvil haciendo de cámara del PC. */
    fun esDelMovil(m: Mensaje): Boolean = m.fuente == DEL_MOVIL

    /**
     * **Rescata lo escrito con `origen` en el `resto`** (27-oct-2026 en el PC): la primera versión de la
     * reunión presencial del PC apuntaba `origen: "camara"` en el `resto`, y `origen` ya es un campo del
     * mensaje. Esa línea lleva `"origen"` dos veces; leída aquí gana la última (la del `resto`) y el
     * mensaje se quedaba con `origen = "camara"`: al reescribirlo, el PC lo tomaba por el aparato de
     * donde se copió y la foto perdía su marca. Si es una de las fuentes de la cámara, vuelve a su sitio
     * ([Mensaje.fuente]) y el campo del mensaje queda vacío, como `cuaderno::rescatar` del PC. Un
     * `origen` de verdad (la seña de un envío) no se toca.
     */
    fun rescatar(m: Mensaje): Mensaje {
        val o = m.origen ?: return m
        if (o != DE_CAMARA && o != DEL_MOVIL) return m
        return m.copy(origen = null, fuente = m.fuente ?: o)
    }

    // --- La resolución completa al estudiar ---------------------------------------------------

    /**
     * **Si hay que cargar la imagen entera** en «Estudiar la clase»: cuando se pinta más ancha que lo
     * cargado (acercada) y la de verdad tiene más píxeles. Con un margen del 5 %.
     */
    fun haceFaltaLaEntera(anchoPintado: Float, anchoCargado: Int, anchoDeVerdad: Int): Boolean =
        anchoDeVerdad > anchoCargado && anchoPintado > anchoCargado * 1.05f

    /**
     * **El paso de lectura de la imagen entera**: 1 (tal cual) mientras quepa en [maximoPixeles]; si no,
     * la mitad, la cuarta parte… Una foto de 50 megapíxeles no cabe entera en una textura del teléfono.
     */
    fun pasoParaLaEntera(ancho: Int, alto: Int, maximoPixeles: Long = PIXELES_DE_LA_ENTERA): Int {
        var paso = 1
        while (paso < 64 && (ancho.toLong() / paso) * (alto.toLong() / paso) > maximoPixeles) paso *= 2
        return paso
    }

    /** Lo más que se carga de una imagen «entera»: 24 megapíxeles (96 MB en ARGB), lo que pinta una GPU. */
    const val PIXELES_DE_LA_ENTERA = 24_000_000L

    // --- Los tramos del vídeo -----------------------------------------------------------------

    /**
     * **Un tramo del vídeo de la reunión**: desde qué milisegundo de la nota va y cuánto dura. Hay más
     * de uno cuando la calidad automática baja a 720p pasada la hora.
     */
    data class Tramo(val desdeMs: Long, val duracionMs: Long) {
        val hastaMs: Long get() = desdeMs + duracionMs
    }

    /**
     * **De qué tramo sale un clip** del vídeo de la reunión, y desde dónde dentro de él: (índice,
     * desde, duración). El clip se corta al final de su tramo (no salta de un fichero a otro). Null si
     * cae fuera de lo grabado.
     */
    fun tramoDelClip(tramos: List<Tramo>, ms: Long, duracionMs: Long): Triple<Int, Long, Long>? {
        val i = tramos.indexOfFirst { ms >= it.desdeMs && ms < it.hastaMs }.takeIf { it >= 0 } ?: return null
        val t = tramos[i]
        val dentro = ms - t.desdeMs
        val dura = minOf(duracionMs.coerceAtLeast(0), t.duracionMs - dentro)
        if (dura <= 0) return null
        return Triple(i, dentro, dura)
    }

    // --- Lo que se guarda ---------------------------------------------------------------------

    /** Una foto ya en la carpeta de adjuntos: su mensaje, su minuto, su fichero. */
    data class FotoGuardada(
        val id: String, val ms: Long, val ruta: String, val nombre: String, val bytes: Long,
        /** La hora de pared del disparo (28-oct-2026, las fotos por su hora: `tomadaEn`). 0: no se sabe. */
        val tomadaEn: Long = 0
    )

    /** Un clip ya en la carpeta de adjuntos. */
    data class ClipGuardado(
        val id: String, val ms: Long, val duracionMs: Long, val ruta: String, val nombre: String, val bytes: Long
    )

    /** El vídeo de la reunión ya en la carpeta de adjuntos. */
    data class VideoGuardado(val id: String, val ruta: String, val nombre: String, val bytes: Long, val duracionMs: Int)

    /**
     * **Los mensajes de una reunión**, en el orden y con los números seguidos del PC (`guardar` de
     * `grabar_clase.rs`): la nota, el vídeo, cada foto y cada clip. La nota lleva `grabacion: "clase"`,
     * `capturas` (siempre, las fotos de la cámara son capturas), `video: {id}` si lo hay y `clips` si
     * los hay; cada foto, su pie, `enGrabacion: {de, ms}` y `fuente: "camara"`; cada clip, `enGrabacion:
     * {de, duracionMs, ms}` y `fuente: "camara"`; el vídeo, `enGrabacion: {de, ms: 0}`. La carpeta va
     * aparte ([carpeta]).
     */
    fun mensajes(
        vozId: String, nombre: String, ahora: Long, proyecto: String?,
        rutaVoz: String, bytesVoz: Long, duracionVoz: Int, picos: List<Int>,
        video: VideoGuardado?, fotos: List<FotoGuardada>, clips: List<ClipGuardado>
    ): List<Mensaje> {
        val enOrden = fotos.sortedBy { it.ms }
        val clipsEnOrden = clips.sortedBy { it.ms }
        val voz = Mensaje(
            id = vozId,
            cuando = ahora,
            clase = Clase.VOZ,
            ruta = rutaVoz,
            nombre = GrabarClase.nombreDeLaNota(nombre),
            bytes = bytesVoz,
            duracionMs = duracionVoz,
            picos = picos,
            proyecto = proyecto,
            capturas = GrabarClase.capturasEnJson(enOrden.map { GrabarClase.Enlace(it.ms, it.id) }),
            grabacion = GrabarClase.CLASE,
            video = video?.let { GrabarClase.videoEnJson(it.id) },
            clips = clipsEnJson(clipsEnOrden.map { Clip(it.ms, it.duracionMs, it.id) })
        )
        var cuando = ahora + 1
        val v = video?.let {
            Mensaje(
                id = it.id, cuando = cuando++, clase = Clase.ARCHIVO, ruta = it.ruta, nombre = it.nombre,
                bytes = it.bytes, duracionMs = it.duracionMs, proyecto = proyecto,
                enGrabacion = GrabarClase.enGrabacion(vozId, 0)
            )
        }
        val imagenes = enOrden.map { f ->
            Mensaje(
                id = f.id, cuando = cuando++, clase = Clase.IMAGEN, texto = GrabarClase.pie(f.ms, nombre),
                ruta = f.ruta, nombre = f.nombre, bytes = f.bytes, proyecto = proyecto,
                enGrabacion = GrabarClase.enGrabacion(vozId, f.ms), fuente = DE_CAMARA,
                tomadaEn = f.tomadaEn.takeIf { it > 0 }
            )
        }
        val deVideo = clipsEnOrden.map { c ->
            Mensaje(
                id = c.id, cuando = cuando++, clase = Clase.ARCHIVO, ruta = c.ruta, nombre = c.nombre,
                bytes = c.bytes, duracionMs = c.duracionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), proyecto = proyecto,
                enGrabacion = enlaceDelClip(vozId, c.ms, c.duracionMs), fuente = DE_CAMARA
            )
        }
        return listOf(voz) + listOfNotNull(v) + imagenes + deVideo
    }

    /**
     * **La carpeta de la reunión** (§3.2): la nota y el vídeo sin título y, en el orden de la
     * grabación, las fotos como las capturas («⏱ 1:05») y cada clip con «▶ 1:05».
     */
    fun carpeta(vozId: String, duracionMs: Long, videoId: String?, fotos: List<FotoGuardada>, clips: List<ClipGuardado>): CarpetaDePaginas =
        GrabarClase.carpetaDeLaGrabacion(
            vozId, duracionMs, videoId,
            fotos.map { GrabarClase.Enlace(it.ms, it.id) },
            clips.map { Clip(it.ms, it.duracionMs, it.id) }
        )

    /** Un número de JSON (no una cadena), o null. */
    private fun numero(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
