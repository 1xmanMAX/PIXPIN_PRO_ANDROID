package com.forge.pixpin.anki

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * **«Enviar a Anki» del menú de un mensaje** (25-oct-2026, `ventana_chat/anki.rs` del PC). Qué
 * tarjetas da cada mensaje ([Tarjetas]):
 *
 * - **Una clase grabada** (nota de voz con `grabacion: "clase"` y `capturas: [{ms, id}]`, la de
 *   «Grabar clase» del PC): una tarjeta por captura, con la imagen delante y lo dicho en ese tramo
 *   de la transcripción detrás.
 * - **Una foto**: la imagen delante y su descripción (su texto, el OCR) detrás.
 * - **Una nota**: sus «pregunta :: respuesta», «P: / R:» y huecos `{{c1::…}}`.
 *
 * Van al mazo `PixPin::<chat>` con las etiquetas `pixpin` y la del chat. Sin Android: lo prueba
 * `TarjetasTest`; quien las manda es [EnviarAAnkiActivity].
 */
object AnkiDelMensaje {

    /** Lo que pregunta la tarjeta de una captura (`anki-pregunta-clase` del PC). */
    const val PREGUNTA_CLASE = "¿Qué se explicó aquí?"

    /** Las capturas de una clase grabada, `(ms, id)` por orden de minuto (`grabar_clase::capturas_de`). */
    fun capturasDe(m: Mensaje): List<Pair<Long, String>> =
        m.capturas.orEmpty().mapNotNull { c ->
            val o = c as? JsonObject ?: return@mapNotNull null
            val ms = runCatching { o["ms"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return@mapNotNull null
            val id = runCatching { o["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return@mapNotNull null
            ms to id
        }.sortedBy { it.first }

    /** Si el mensaje da alguna tarjeta (para ofrecerlo en el menú). */
    fun sePuede(m: Mensaje): Boolean = when (m.clase) {
        Clase.VOZ -> capturasDe(m).isNotEmpty()
        Clase.IMAGEN -> !m.ruta.isNullOrBlank()
        else -> Tarjetas.deLaNota(m.texto).isNotEmpty()
    }

    /** El mazo de un chat. `::` es el separador de submazos de Anki: dentro del nombre no. */
    fun mazoDe(nombre: String): String = "PixPin::" + nombre.trim().replace("::", ":")

    private val MARCA = Regex("""^\s*\[(?:(\d+):)?(\d+):(\d\d)\]\s*""")

    /**
     * **Los trozos de una transcripción** con su minuto, `(ms, texto)` (`voz::trozos` +
     * `voz::sin_marcas` del PC; los mismos párrafos `[m:ss]` que enseña `LetraActivity`). Los que no
     * llevan minuto se quedan fuera: no se sabe en qué tramo cayeron.
     */
    fun trozosDe(transcripcion: String?): List<Pair<Long, String>> {
        val texto = transcripcion?.trim().orEmpty().replace("\r\n", "\n")
        if (texto.isEmpty()) return emptyList()
        val v = mutableListOf<Pair<Long, String>>()
        val bloque = StringBuilder()
        fun cerrar() {
            val limpio = bloque.toString().trim()
            bloque.setLength(0)
            if (limpio.isEmpty()) return
            val m = MARCA.find(limpio) ?: return
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val ms = (h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000
            v += ms to sinMarcas(limpio.substring(m.range.last + 1).trim())
        }
        for (linea in texto.split('\n')) {
            if (linea.isBlank()) cerrar()
            else { if (bloque.isNotEmpty()) bloque.append('\n'); bloque.append(linea) }
        }
        cerrar()
        return v
    }

    /** Sin las marcas de Markdown que no aportan (`#`, `**`, `__`) ni las imágenes (`![audio](…)`). */
    fun sinMarcas(texto: String): String =
        texto.lines().filterNot { it.trimStart().startsWith("![") }
            .joinToString("\n") { it.trimStart('#', ' ').replace("**", "").replace("__", "") }
            .trim()

    /**
     * El nombre de una imagen en Anki: **único y estable** (`pixpin-<id>.<ext>`), así mandar dos
     * veces la misma clase no llena Anki de copias.
     */
    fun nombreEnAnki(m: Mensaje): String {
        val ext = (m.ruta ?: m.nombre).substringAfterLast('.', "png").lowercase().takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "png"
        return "pixpin-${m.id}.$ext"
    }

    /**
     * **Las tarjetas de ese mensaje**, con sus etiquetas. [todos]: los mensajes (de ahí salen las
     * capturas de una clase); [titulo]: cómo se llama el audio; [chat]: el nombre del chat.
     * [existe] dice si un fichero está (en las pruebas, siempre).
     */
    fun tarjetasDe(
        m: Mensaje, todos: List<Mensaje>, titulo: String, chat: String,
        existe: (String) -> Boolean = { java.io.File(it).isFile }
    ): List<Tarjetas.Tarjeta> {
        fun leer(c: Mensaje): Tarjetas.Imagen? {
            val ruta = c.ruta?.takeIf { it.isNotBlank() && existe(it) } ?: return null
            return Tarjetas.Imagen(nombreEnAnki(c), ruta)
        }
        val v = when (m.clase) {
            Clase.VOZ -> {
                val capturas = capturasDe(m).mapNotNull { (ms, id) ->
                    val c = todos.firstOrNull { it.id == id } ?: return@mapNotNull null
                    leer(c)?.let { ms to it }
                }
                Tarjetas.deLaClase(titulo, PREGUNTA_CLASE, trozosDe(m.transcripcion), capturas)
            }
            Clase.IMAGEN -> leer(m)?.let { listOf(Tarjetas.deLaFoto(it, m.texto, m.nombre)) }.orEmpty()
            else -> Tarjetas.deLaNota(m.texto)
        }
        val etiquetas = listOf("pixpin", Tarjetas.etiqueta(chat))
        return v.map { it.copy(etiquetas = etiquetas) }
    }
}
