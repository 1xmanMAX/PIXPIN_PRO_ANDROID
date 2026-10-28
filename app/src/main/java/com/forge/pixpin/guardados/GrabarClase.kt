package com.forge.pixpin.guardados

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * **Grabar una clase o una reunión con capturas en su minuto** (25-oct-2026, lo mismo que
 * `ventana_chat/grabar_clase.rs` del PC del 24-oct-2026). Lo puro, sin Android: cómo se apuntan
 * las capturas en la nota de voz y en cada imagen, cuál toca en cada momento del audio, el
 * cronómetro con pausas y la zona que se repite.
 *
 * ## El enlace, igual que el PC, campo por campo
 *
 * La nota de voz lleva `grabacion: "clase"` y `capturas: [{id, ms}]` (en orden de minuto, **siempre**,
 * aunque esté vacía: es lo que dice que la nota es de una clase) y, si se grabó la pantalla,
 * `video: {id}`; cada imagen, `enGrabacion: {de, ms}`, y el vídeo (un `ARCHIVO` `.mp4`),
 * `enGrabacion: {de, ms: 0}` (PC `df01da9`, `docs/novedades-2026-10-25-para-los-otros-aparatos.md` §3). Las claves van **en orden alfabético** porque así las escribe
 * el PC (`serde_json` sin `preserve_order` guarda los objetos en un `BTreeMap`): lo que sale de
 * aquí es byte a byte lo que saldría de allí, y una fusión que compare el texto no ve cambios
 * donde no los hay. Ver [Mensaje.capturas] y [Mensaje.enGrabacion].
 *
 * La parte con Android está en [GrabarClaseService] (la barra flotante, el micrófono y las
 * fotos), [GrabarClaseActivity] (preparar) y [LetraActivity] (verlas junto al texto).
 */
object GrabarClase {

    /** Lo que se pone en [Mensaje.grabacion] de la nota de una clase con capturas. */
    const val CLASE = "clase"

    /** Una captura hecha grabando: el milisegundo de la nota y el mensaje de la imagen. */
    data class Enlace(val ms: Long, val id: String)

    /** Lo que se apunta en la nota de voz: sus capturas en orden, `[{id, ms}]`. */
    fun capturasEnJson(capturas: List<Enlace>): JsonArray = buildJsonArray {
        capturas.sortedBy { it.ms }.forEach { c ->
            add(buildJsonObject {
                put("id", JsonPrimitive(c.id))
                put("ms", JsonPrimitive(c.ms))
            })
        }
    }

    /** Lo que se apunta en cada imagen: de qué nota es y en qué minuto, `{de, ms}`. */
    fun enGrabacion(de: String, ms: Long): JsonObject = buildJsonObject {
        put("de", JsonPrimitive(de))
        put("ms", JsonPrimitive(ms))
    }

    /** Lo que se apunta en la nota de voz si se grabó la pantalla: `{id}` del vídeo. */
    fun videoEnJson(id: String): JsonObject = buildJsonObject { put("id", JsonPrimitive(id)) }

    /** El id del vídeo de una nota de clase, o null si no se grabó la pantalla. */
    fun videoDe(m: Mensaje): String? =
        (m.video?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Si la nota es de una clase grabada (lo dice `grabacion`, aunque no tenga capturas). */
    fun esClase(m: Mensaje): Boolean = m.grabacion == CLASE

    /** El nombre del vídeo: `Clase 24-10 18h05.mp4`, como el PC. */
    fun nombreDelVideo(nombre: String): String = "$nombre.mp4"

    /**
     * Las capturas de una nota de voz grabada como clase, en orden. Vacío si no es de una clase o
     * si lo apuntado no se entiende; una entrada rota se salta sin llevarse las demás (como
     * `capturas_de` del PC).
     */
    fun capturasDe(m: Mensaje): List<Enlace> =
        m.capturas.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val ms = (o["ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
            Enlace(ms, id)
        }.sortedBy { it.ms }

    /** De qué nota es una imagen hecha grabando, y en qué minuto. Null si no es de ninguna. */
    fun deLaGrabacion(m: Mensaje): Enlace? {
        val o = m.enGrabacion ?: return null
        val de = o["de"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull ?: return null
        val ms = o["ms"]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull ?: return null
        return Enlace(ms, de)
    }

    /**
     * **Añade una captura a la clase** (25-oct-2026, `clase::poner_captura` del PC, 8d7fe93): la de un
     * fotograma del vídeo anotado al estudiar, el mensaje [id] en [ms]. Un id que ya estaba no se
     * repite (null: no cambió); lo demás que lleve cada captura se respeta. Las claves, `{id, ms}`, en
     * el orden alfabético en que las escribe el PC, y la lista por su minuto.
     */
    fun ponerCaptura(lista: JsonArray?, ms: Long, id: String): JsonArray? {
        val antes = lista.orEmpty()
        if (antes.any { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == id }) return null
        val nueva = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("ms", JsonPrimitive(ms.coerceAtLeast(0)))
        }
        fun msDe(e: kotlinx.serialization.json.JsonElement) = ((e as? JsonObject)?.get("ms") as? JsonPrimitive)?.longOrNull ?: 0L
        return JsonArray((antes + nueva).sortedBy(::msDe))
    }

    /**
     * **La carpeta de una clase** (25-oct-2026, `carpeta_de_la_grabacion` del PC, e015e2b y 40daac7): la
     * nota, el vídeo y las capturas en el orden de la grabación, cada una con su minuto (las capturas
     * con el suyo de título, «⏱ 1:05»; la nota y el vídeo, sin título: se ve su nombre).
     *
     * **Y los clips de la cámara** (28-oct-2026, la reunión presencial, `carpeta_de_la_grabacion` del PC
     * de `69ac5a89`): cada uno con «▶ 1:05» en su minuto, mezclados con las capturas por su `ms` (a la
     * par, la captura primero, como el orden estable del PC).
     */
    fun carpetaDeLaGrabacion(
        voz: String?, duracionMs: Long, video: String?, capturas: List<Enlace>,
        clips: List<Presencial.Clip> = emptyList()
    ): CarpetaDePaginas {
        var c = CarpetaDePaginas.grabacion(voz.orEmpty(), duracionMs).copy(video = video.orEmpty())
        for (id in listOfNotNull(voz, video)) c = c.anadirAlMinuto(id, "", 0) ?: c
        val enOrden = (capturas.map { Triple(it.ms, it.id, "⏱ ${minuto(it.ms)}") } +
            clips.map { Triple(it.ms, it.id, "▶ ${minuto(it.ms)}") }).sortedBy { it.first }
        for ((ms, id, titulo) in enOrden) c = c.anadirAlMinuto(id, titulo, ms) ?: c
        return c
    }

    /**
     * **La captura que toca en [posicionMs]**: la última hecha antes (o en ese mismo momento).
     * Antes de la primera, la primera: algo hay que enseñar. Null sin capturas. [tiempos] en
     * orden, como los da [capturasDe].
     */
    fun capturaEn(tiempos: List<Long>, posicionMs: Long): Int? {
        if (tiempos.isEmpty()) return null
        return tiempos.indexOfLast { it <= posicionMs }.coerceAtLeast(0)
    }

    /** El minuto de una captura en el pie y en los avisos: `12:34` o `1:02:03`. */
    fun minuto(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val seg = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, seg)
        else String.format(Locale.ROOT, "%d:%02d", m, seg)
    }

    /**
     * La fecha del nombre de la nota, en hora local: `24-10 18h05`. Sin `:`, como el PC (Windows
     * no lo admite en un nombre de fichero, y el nombre viaja hasta allí).
     */
    fun fechaDelNombre(ahoraMs: Long, zona: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zona).apply { timeInMillis = ahoraMs }
        return String.format(
            Locale.ROOT, "%02d-%02d %02dh%02d",
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }

    /** El pie de cada captura en el chat: `⏱ 12:34 · Clase 24-10 18h05`, como el PC. */
    fun pie(ms: Long, nombre: String): String = "⏱ ${minuto(ms)} · $nombre"

    /** El nombre del fichero de la nota: con su `.m4a`, como `nombre_de_la_voz` del PC. */
    fun nombreDeLaNota(nombre: String): String = if (nombre.endsWith(".m4a")) nombre else "$nombre.m4a"

    /**
     * **Una zona de la pantalla**, en píxeles del fotograma entero. Propia y no
     * `android.graphics.Rect`, que en las pruebas de JVM no existe.
     */
    data class Zona(val x: Int, val y: Int, val ancho: Int, val alto: Int)

    /** Lo menos que mide de lado una zona para valer: menos es un toque, no una zona. */
    const val LADO_MINIMO = 24

    /**
     * La zona que se puede recortar de un fotograma de [ancho]×[alto]: [zona] metida dentro, o null
     * si casi no cae dentro (el teléfono giró y lo elegido queda fuera). Null [zona] es la pantalla
     * entera. Así la misma zona se repite captura tras captura sin que un giro reviente el recorte.
     */
    fun recorte(zona: Zona?, ancho: Int, alto: Int): Zona? {
        if (ancho <= 0 || alto <= 0) return null
        if (zona == null) return Zona(0, 0, ancho, alto)
        val x0 = zona.x.coerceIn(0, ancho)
        val y0 = zona.y.coerceIn(0, alto)
        val x1 = (zona.x + zona.ancho).coerceIn(0, ancho)
        val y1 = (zona.y + zona.alto).coerceIn(0, alto)
        if (x1 - x0 < LADO_MINIMO || y1 - y0 < LADO_MINIMO) return null
        return Zona(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * La zona entre dos esquinas arrastradas con el dedo sobre una vista de [anchoVista]×[altoVista]
     * que enseña el fotograma de [anchoFoto]×[altoFoto] estirado, ya en píxeles del fotograma.
     * Null si es tan pequeña que fue un toque.
     */
    fun zonaArrastrada(
        ax: Float, ay: Float, bx: Float, by: Float,
        anchoVista: Float, altoVista: Float, anchoFoto: Int, altoFoto: Int
    ): Zona? {
        if (anchoVista <= 0f || altoVista <= 0f) return null
        val kx = anchoFoto / anchoVista
        val ky = altoFoto / altoVista
        val x0 = (minOf(ax, bx) * kx).toInt()
        val y0 = (minOf(ay, by) * ky).toInt()
        val x1 = (maxOf(ax, bx) * kx).toInt()
        val y1 = (maxOf(ay, by) * ky).toInt()
        return recorte(Zona(x0, y0, x1 - x0, y1 - y0), anchoFoto, altoFoto)
    }

    /**
     * **El tiempo que lleva la nota, sin las pausas.** Es el minuto que se apunta en cada captura:
     * con la nota en pausa no entra audio, así que el reloj de la pared se adelantaría y la captura
     * caería más tarde de lo que se oye. [reloj] es inyectable para las pruebas.
     */
    class Cronometro(private val reloj: () -> Long = { System.nanoTime() / 1_000_000 }) {
        private val desde = reloj()
        private var pausado = 0L
        private var pausaDesde: Long? = null

        val enPausa: Boolean get() = pausaDesde != null

        fun pausar(pausa: Boolean) {
            val ahora = reloj()
            val p = pausaDesde
            if (pausa && p == null) pausaDesde = ahora
            else if (!pausa && p != null) { pausado += ahora - p; pausaDesde = null }
        }

        fun llevadoMs(): Long {
            val ahora = pausaDesde ?: reloj()
            return (ahora - desde - pausado).coerceAtLeast(0)
        }
    }

    /**
     * **El tamaño del vídeo de la pantalla**: la pantalla reducida a [lado] por el lado largo (como
     * el PC, 1280) y cada lado múltiplo de 16. Los codificadores H.264 de muchos teléfonos fallan a
     * resolución completa o con lados impares, y diapositivas a 1280 se leen bien.
     */
    fun medidasDelVideo(ancho: Int, alto: Int, lado: Int = 1280): Pair<Int, Int> {
        val largo = maxOf(ancho, alto, 1)
        val k = minOf(1.0, lado.toDouble() / largo)
        fun a16(v: Int) = ((v * k).toInt() / 16 * 16).coerceAtLeast(16)
        return a16(ancho) to a16(alto)
    }

    /**
     * **El tamaño del vídeo con un perfil** (25-oct-2026, como `Perfil::medidas` del PC): la
     * pantalla dentro de la caja de su perfil, **girada como la pantalla** (el PC graba apaisado; un
     * teléfono de pie con «Normal» va a 1080×1920, no a 486×1080), nunca más grande que la pantalla
     * y con los lados múltiplos de 16 como [medidasDelVideo].
     */
    fun medidasDelVideo(ancho: Int, alto: Int, perfil: PerfilDeVideo): Pair<Int, Int> {
        val (largoTope, cortoTope) = perfil.tope ?: PerfilDeVideo.TOPE_NATIVO
        val largo = maxOf(ancho, alto, 1)
        val corto = maxOf(minOf(ancho, alto), 1)
        val k = minOf(1.0, largoTope.toDouble() / largo, cortoTope.toDouble() / corto)
        // Con una pizca: 1920 · (1280/1920) da 1279,99… y se iría a 1264.
        fun a16(v: Int) = ((v * k + 1e-6).toInt() / 16 * 16).coerceAtLeast(16)
        return a16(ancho) to a16(alto)
    }

    /** Cuánto dura armado el ✕ de tirar: pasado esto, otro toque vuelve a avisar. */
    const val TIRAR_DURA_MS = 3_000L
}

/**
 * **La calidad del vídeo de la clase** (25-oct-2026, como `pixpin_motor_video::Perfil` del PC,
 * `cdad692`): «Ligero», «Normal» y «Nítido». Cada uno dice hasta qué tamaño se graba, cuántos
 * fotogramas por segundo y cuántos bits se le dejan al codificador; los números son los del PC,
 * para que una hora de clase pese lo mismo grabada en un sitio que en otro (en «Normal», unos
 * 300 MB **en el peor caso**). [clave] es la del PC, y es lo que se guarda de la elección.
 *
 * En el teléfono el codificador es siempre el H.264 del aparato (`MediaRecorder`): del PC no se
 * trae la cadena de caídas gráfica → procesador → modo sencillo ni el aviso de con cuál se graba.
 */
enum class PerfilDeVideo(
    val clave: String,
    /** La caja más grande, (lado largo, lado corto); null, la pantalla tal cual (hasta [TOPE_NATIVO]). */
    val tope: Pair<Int, Int>?,
    /** Fotogramas por segundo como mucho. */
    val porSegundo: Int,
    /** Bits por segundo de media y de pico para un vídeo de [referencia]. */
    val media: Int,
    val pico: Int,
    val referencia: Pair<Int, Int>
) {
    LIGERO("ligero", 1280 to 720, 10, 300_000, 1_500_000, 1280 to 720),
    NORMAL("normal", 1920 to 1080, 15, 550_000, 3_000_000, 1920 to 1080),
    NITIDO("nitido", null, 30, 1_200_000, 6_000_000, 1920 to 1080);

    /** El siguiente al tocar (vuelve al primero tras el último), como en el PC. */
    fun siguiente(): PerfilDeVideo = entries[(ordinal + 1) % entries.size]

    companion object {
        /** El tope duro de «la pantalla tal cual»: 4K, como el PC. */
        val TOPE_NATIVO = 3840 to 2160

        /** Bytes por segundo del AAC de la nota (96 kb/s). */
        const val BYTES_POR_SEGUNDO_AAC = 12_000

        /** El de una [clave]; cualquier otra cosa, «Normal» (el de salida). */
        fun deClave(clave: String?): PerfilDeVideo = entries.firstOrNull { it.clave == clave } ?: NORMAL

        /**
         * **Bits por segundo de media y de pico** para un vídeo de [ancho]×[alto] (`bits_por_segundo`
         * del PC): proporcionales a los píxeles respecto a la referencia, pero nunca menos de la
         * mitad (el texto pequeño necesita sus bits igual) ni más de cuatro veces.
         */
        fun bitsPorSegundo(p: PerfilDeVideo, ancho: Int, alto: Int): Pair<Int, Int> {
            val px = maxOf(ancho, 2).toLong() * maxOf(alto, 2)
            val ref = maxOf(p.referencia.first, 2).toLong() * maxOf(p.referencia.second, 2)
            val factor = (px * 1000 / ref).coerceIn(500, 4000)
            return (p.media * factor / 1000).toInt() to (p.pico * factor / 1000).toInt()
        }

        /** **Megas por hora en el peor caso**: el vídeo a su media todo el rato y el sonido AAC. */
        fun megasPorHora(mediaVideo: Int, conSonido: Boolean): Long {
            val video = mediaVideo.toLong() / 8 * 3600
            val sonido = if (conSonido) BYTES_POR_SEGUNDO_AAC.toLong() * 3600 else 0
            return (video + sonido) / 1_000_000
        }
    }
}
