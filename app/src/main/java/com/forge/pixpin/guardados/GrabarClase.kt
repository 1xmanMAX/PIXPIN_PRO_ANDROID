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

    /** Cuánto dura armado el ✕ de tirar: pasado esto, otro toque vuelve a avisar. */
    const val TIRAR_DURA_MS = 3_000L
}
