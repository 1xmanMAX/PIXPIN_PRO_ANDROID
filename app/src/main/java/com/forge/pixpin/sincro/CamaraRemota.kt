package com.forge.pixpin.sincro

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **El móvil como cámara de la reunión que graba el PC** (28-oct-2026, `docs/movil-como-camara.md`
 * del PC, `crates/pixpin-sincro/src/camara.rs`).
 *
 * El usuario, en el PC: «agregar una función especial para conectar con Android, para que el MÓVIL
 * tome las fotos y se envíen directamente y se adjunten aquí» (a la reunión que se está grabando
 * en el PC). Y: «primero el PC le pasa al móvil el tiempo de la grabación, para que el móvil marque
 * cada foto con el minuto de la grabación en que se hizo; luego la manda y el PC la pone justo
 * ahí». Y clips de vídeo cortos.
 *
 * **No hay transporte nuevo**: el canal cifrado del grupo, el mismo puerto y el mismo `suelto` de
 * «Enviar a tus aparatos» ([AlGrupo]), con tres cosas más que solo se usan si los dos dicen
 * [CAPACIDAD] en el `hola`:
 *
 * 1. **`camara_remota`** ([PETICION]): el estado de la grabación del PC ([Anuncio]). El móvil
 *    pregunta (`{"t":"camara_remota","reloj":t0}` → el anuncio con `reloj: t1`) y el PC avisa (el
 *    anuncio con `reloj: t0` → `{"t":"camara_remota","reloj":t1}`). Con las tres horas se mide el
 *    desfase de los relojes como NTP ([desfase]).
 * 2. **Cada foto o clip** es un `suelto` con `destino: "grabacion"` ([DESTINO]) y su minuto ya
 *    calculado aquí con el [RelojDeGrabacion] (ver [ConexionDeCamara.mandar]).
 * 3. Lo que no es una foto ni un clip MP4 (una HEIC), o pesa demasiado, el PC lo niega antes del
 *    «vale»: no sale ningún trozo y la conexión sigue sana.
 *
 * Todo esto es lógica pura (sin Android): lo prueban `CamaraRemotaTest` y, contra el PC de verdad,
 * `CamaraDelPcTest`.
 */
object CamaraRemota {
    /** Lo que dicen en el `hola` los que saben hacer de cámara y recibirla. */
    const val CAPACIDAD = "camara1"
    /** El `t` de la pregunta y del anuncio, en los dos sentidos. */
    const val PETICION = "camara_remota"
    /** El `destino` del `suelto` de una foto o un clip para la grabación. */
    const val DESTINO = "grabacion"
    /** Lo que dice el «listo» de una foto que entró en la grabación. */
    const val DONDE = "grabacion"
    const val H264 = "h264"
    const val HEVC = "hevc"
    /** Lo más largo de un clip si el anuncio no lo dice: dos minutos. */
    const val CLIP_MAXIMO_MS = 2 * 60 * 1000L
    /** Lo más grande que acepta el PC: una foto de 64 MB, un clip de 256 MB. */
    const val TOPE_DE_FOTO = 64L shl 20
    const val TOPE_DE_CLIP = 256L shl 20
    /** Lo que se deja pasar entre el anuncio y el reloj de aquí antes de corregirlo. */
    const val TOLERANCIA_MS = 250L
    const val SOLO_FOTOS_Y_CLIPS = "Solo se aceptan fotos (JPEG, PNG o WebP) y clips MP4"

    /**
     * **El estado de la grabación del PC** (`Anuncio` en Rust), tal cual viaja. Lo que vale lo de
     * por omisión no se escribe (`encodeDefaults = false`), salvo [t], que va siempre; los campos
     * en el mismo orden que el PC, para que el JSON salga idéntico.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Anuncio(
        @EncodeDefault val t: String = PETICION,
        /** El id de la grabación (no el de la nota de voz, que aún no existe). Vacío: no graba. */
        val grabacion: String = "",
        /** «Clase 27-10 10h05». */
        val titulo: String = "",
        /** El nombre del PC. */
        val aparato: String = "",
        /** Grabando (aunque sea en pausa). `false` con [grabacion]: ya acabó, y lo pendiente aún entra. */
        val activa: Boolean = false,
        @SerialName("en_pausa") val enPausa: Boolean = false,
        /** En qué milisegundo de la grabación va al escribir este anuncio. */
        @SerialName("ms_actual") val msActual: Long = 0,
        /** Lo que duró, cuando ya acabó. */
        val duracion: Long = 0,
        /** Cuándo empezó, en el reloj del PC. */
        val desde: Long = 0,
        /** Las pausas, `[desde, hasta]` en el reloj del PC (`hasta` 0: sigue en pausa). */
        val pausas: List<List<Long>> = emptyList(),
        /** El reloj de quien escribe este JSON, al escribirlo (`t0` o `t1` de la medida). */
        val reloj: Long = 0,
        /** Los códecs de vídeo que el PC sabe ver. Vacío: no recibe clips. */
        val codecs: List<String> = emptyList(),
        /** Lo más largo de un clip, en ms. */
        val clipMaximoMs: Long = 0,
        val error: String? = null
    ) {
        /** Si hay una grabación a la que mandar fotos ahora mismo. */
        val grabando: Boolean get() = activa && grabacion.isNotEmpty()

        /** Si el PC recibe clips de vídeo: sin [codecs], el botón de clip no sale. */
        val conClips: Boolean get() = codecs.isNotEmpty()

        /** En qué graba un clip para este PC: HEVC si lo sabe ver, H.264 si no; `null`, sin clips. */
        val codecDelClip: String? get() = when {
            HEVC in codecs -> HEVC
            H264 in codecs -> H264
            else -> null
        }

        /** Lo más largo de un clip para este PC. */
        val clipMaximo: Long get() = if (clipMaximoMs > 0) clipMaximoMs else CLIP_MAXIMO_MS
    }

    /**
     * **La pregunta `camara_remota` de quien conduce** (28-oct-2026, `Pregunta` de `camara.rs` del PC).
     * Sola (`{"t":"camara_remota","reloj":t0}`) es la de siempre: «¿cómo va tu grabación?». Con
     * [suya], quien pregunta **también graba** y dice cómo va la suya (el teléfono que graba, para que
     * el PC le mande capturas, §13). Va metida en `suya` y no suelta porque la petición también se lee
     * como una `Peticion` de sincronizar, cuyo `desde` es otra cosa. Un aparato de antes la contesta
     * igual y no pasa nada.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Pregunta(
        @EncodeDefault val t: String = PETICION,
        val reloj: Long = 0,
        val suya: Anuncio? = null
    )

    fun aTexto(p: Pregunta): String = Protocolo.json.encodeToString(Pregunta.serializer(), p)
    fun preguntaDe(t: String): Pregunta? = runCatching { Protocolo.json.decodeFromString(Pregunta.serializer(), t) }.getOrNull()

    /**
     * **Lo que contesta quien graba a una foto cuya hora no cae en ninguna de sus grabaciones**
     * (§12.4): el teléfono la deja como una foto normal (y la sincroniza como siempre).
     */
    const val NINGUNA = "La foto no cae en ninguna grabación de este equipo"

    /** Si es la respuesta [NINGUNA]. */
    fun esNinguna(error: String): Boolean = error.contains("no cae en ninguna")

    fun aTexto(a: Anuncio): String = Protocolo.json.encodeToString(Anuncio.serializer(), a)
    fun deTexto(t: String): Anuncio = Protocolo.json.decodeFromString(Anuncio.serializer(), t)

    /**
     * **El desfase entre dos relojes, como NTP**: [t0] sale de aquí, [t1] es el reloj del otro al
     * contestar, [t2] vuelve aquí. Lo que va adelantado el otro (negativo: atrasado), suponiendo
     * que la ida y la vuelta tardan lo mismo. Un [t2] roto (antes que [t0]) cuenta como [t0].
     */
    fun desfase(t0: Long, t1: Long, t2: Long): Long = t1 - (t0 + maxOf(t2, t0)) / 2

    /** El minuto como en el pie de las capturas del PC: `12:34` o `1:02:03`. */
    fun minuto(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }

    /** Si el `mime` es de un clip (y no de una foto). */
    fun esClip(mime: String): Boolean = mime.startsWith("video/")

    /** Lo que acepta el PC, antes de preguntarle: una foto JPEG, PNG o WebP, o un clip MP4. */
    fun tipoAceptado(mime: String): Boolean = mime in setOf("image/jpeg", "image/png", "image/webp", "video/mp4")

    /** Si por sus primeros bytes es una foto que el PC sabe enseñar (`es_imagen` del PC). */
    fun esImagen(cabeza: ByteArray): Boolean {
        fun empieza(vararg b: Int) = cabeza.size >= b.size && b.indices.all { cabeza[it] == b[it].toByte() }
        return empieza(0xFF, 0xD8, 0xFF) ||
            empieza(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) ||
            (cabeza.size >= 12 && String(cabeza, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(cabeza, 8, 4, Charsets.ISO_8859_1) == "WEBP")
    }

    /** Si es un JPEG: lo que sale de la cámara y lo que hay que mandar. */
    fun esJpeg(cabeza: ByteArray): Boolean =
        cabeza.size >= 3 && cabeza[0] == 0xFF.toByte() && cabeza[1] == 0xD8.toByte() && cabeza[2] == 0xFF.toByte()

    private val MARCAS_HEIF = setOf("heic", "heix", "heim", "heis", "hevc", "mif1", "msf1", "avif", "avis")

    /** Si es una foto HEIC/HEIF/AVIF (la caja `ftyp` con su marca): el PC no la acepta. */
    fun esHeif(cabeza: ByteArray): Boolean =
        cabeza.size >= 12 && String(cabeza, 4, 4, Charsets.ISO_8859_1) == "ftyp" &&
            String(cabeza, 8, 4, Charsets.ISO_8859_1) in MARCAS_HEIF

    /** Si es un MP4 de verdad (`ftyp` en el byte 4) y no una foto HEIC, que usa la misma caja. */
    fun esMp4(cabeza: ByteArray): Boolean =
        cabeza.size >= 12 && String(cabeza, 4, 4, Charsets.ISO_8859_1) == "ftyp" && !esHeif(cabeza)

    /**
     * **Si lo que contestó el PC a una toma es un «no» para siempre** (la grabación ya no está, no
     * es una foto, pesa demasiado): no se vuelve a intentar. Lo demás (la cámara apagada en el PC,
     * ocupado, «cambió mientras se mandaba», no se pudo guardar) se reintenta.
     */
    fun esNoParaSiempre(error: String): Boolean =
        error.contains("ya no está en el PC") || error.startsWith("Solo se aceptan") ||
            error.contains("demasiado grande") || error.contains("no dice de qué grabación") || esNinguna(error)

    // ------------------------------------------------------------ quien graba (§13)

    /**
     * **Antes del «vale», en quien graba** (`camara::aceptar` del PC): que diga de qué grabación es (o
     * traiga su hora, §12.4), que sea una foto o un clip de los que se saben enseñar y de un tamaño
     * razonable. El motivo del «no», o null.
     */
    fun aceptar(grabacion: String?, tomadaEn: Long, mime: String, bytes: Long): String? = when {
        grabacion.isNullOrBlank() && tomadaEn <= 0 -> "La foto no dice de qué grabación es"
        !tipoAceptado(mime) -> SOLO_FOTOS_Y_CLIPS
        bytes !in 1..(if (esClip(mime)) TOPE_DE_CLIP else TOPE_DE_FOTO) -> "La foto o el clip es demasiado grande"
        else -> null
    }

    /**
     * **El minuto de la grabación de una foto que llega a quien graba** (`ms_de_la_foto` del PC): con
     * [ms] (lo calculó quien la hizo), ese; sin él y con [tomadaEn], su hora pasada a este reloj con el
     * desfase ([reloj] el que mandó, o [medido]); sin nada, el minuto en que llega. Siempre dentro de
     * `[0, lo que lleva o duró]`; [duracion], si se sabe la de verdad, manda.
     */
    fun msDeLaFoto(r: RelojDeGrabacion, ahora: Long, duracion: Long?, ms: Long?, tomadaEn: Long, reloj: Long, medido: Long?): Long {
        val tope = (duracion ?: r.duracion(ahora)).coerceAtLeast(0)
        val m = when {
            ms != null -> ms
            tomadaEn > 0 -> r.msEn(tomadaEn - if (reloj != 0L) reloj else (medido ?: 0L))
            else -> r.msEn(ahora)
        }
        return m.coerceIn(0, tope)
    }

    /** **Lo que dura un clip** en la grabación (`duracion_del_clip` del PC). */
    fun duracionDelClip(duracionMs: Long, ms: Long, tope: Long): Long =
        minOf(duracionMs.coerceIn(0, 2 * CLIP_MAXIMO_MS), (tope - ms).coerceAtLeast(0))

    /** Si es el «No sé qué es» de un PixPin de antes. */
    fun esSinSoporte(error: String): Boolean = error.startsWith("No sé qué es")
}

/**
 * **Quien graba en este aparato** (28-oct-2026, §13: el teléfono graba y el PC le manda capturas; el
 * `Camara` de `camara.rs` del PC). Lo da quien atiende al grupo ([Respondedor]): con él, la
 * `camara_remota` se contesta con el anuncio de la grabación de aquí y un `suelto` con `destino:
 * "grabacion"` entra en ella. Ver [GrabacionesDelMovil].
 */
interface QuienGraba {
    /** La grabación de aquí, para contestar a `camara_remota`; null sin ninguna. */
    fun anuncio(): CamaraRemota.Anuncio?
    /** **Antes del «vale»**: el motivo del «no», o null si se acepta. */
    fun aceptar(p: Protocolo.Peticion): String?
    /** La foto llegó entera a [fichero] (temporal: se copia o se mueve). Su minuto; lanza con el motivo si no entra. */
    fun guardar(p: Protocolo.Peticion, fichero: java.io.File, otro: Aparato): Long
}
