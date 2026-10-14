package com.forge.pixpin.asistente

import com.forge.pixpin.guardados.Clase
import com.forge.pixpin.guardados.Mensaje
import com.forge.pixpin.ia.MensajeIa
import com.forge.pixpin.ia.Rol
import com.forge.pixpin.motor.Proyecto

/**
 * **El chat «Asistente» y lo que se le pregunta** (`apps/pixpin/src/asistente/chat.rs` del PC y
 * `docs/manual-asistente-android.md` §3.1, §3.3, §4.1). Todo lo que decide va aquí, sin Android:
 *
 * - **Cuál es el chat del asistente**, sin crear dos: el proyecto que lleve la marca
 *   [Proyecto.asistente]; si no, el que tenga los mensajes de id fijo que el PC deja en él
 *   (`auto-automatizaciones`, `auto-figuras`); si no, el que se llame «Asistente» y tenga notas
 *   del aparato «IA». Solo si no hay ninguno se crea uno (al tocarlo por primera vez).
 * - **Quién contesta**: las respuestas son notas normales con `aparato = "IA"` —ni formato nuevo
 *   ni campos que el PC no conozca—, y fuera de su chat contestan a la pregunta (`respondeA`).
 * - **La conversación que ve el modelo**: lo último del chat, recortado para que quepa.
 */
object ChatDelAsistente {
    /** El aparato de las respuestas del asistente (la chapa `#48·IA`). */
    const val APARATO = "IA"
    const val NOMBRE = "Asistente"
    const val MENCION = "@asistente"
    const val NOMBRE_DEL_LIENZO = "Lienzo del asistente"

    /** La fila de la lista de chats cuando aún no existe: al tocarla se crea. */
    const val SIN_CREAR = "asistente:sin-crear"

    /** Los mensajes de id fijo que el PC deja en el chat del asistente (§3.5, §3.7). */
    private val IDS_DEL_PC = setOf("auto-automatizaciones", "auto-figuras")

    /** El texto con el que nace la respuesta, mientras piensa. */
    const val PENSANDO = "💭 …"

    fun esRespuesta(m: Mensaje): Boolean = m.aparato == APARATO

    /** **El chat del asistente**, si ya hay uno (ver arriba el orden). */
    fun buscar(proyectos: List<Proyecto>, mensajes: List<Mensaje>): Proyecto? {
        val vivos = proyectos.filter { !it.archivado }
        vivos.firstOrNull { it.asistente == true }?.let { return it }
        val conIdsDelPc = mensajes.asSequence().filter { it.id in IDS_DEL_PC }.mapNotNull { it.proyecto }.toSet()
        vivos.firstOrNull { it.id in conIdsDelPc }?.let { return it }
        val conIa = mensajes.asSequence().filter { esRespuesta(it) }.mapNotNull { it.proyecto }.toSet()
        return vivos.firstOrNull { it.nombre.trim().equals(NOMBRE, ignoreCase = true) && it.id in conIa }
    }

    /** ¿Lleva el texto la mención `@asistente` (como palabra)? */
    fun pideAlAsistente(texto: String): Boolean = Regex("(^|\\s)@asistente\\b", RegexOption.IGNORE_CASE).containsMatchIn(texto)

    /** El pedido sin la mención. */
    fun sinMencion(texto: String): String =
        texto.replace(Regex("(^|\\s)@asistente\\b[,:]?", RegexOption.IGNORE_CASE), " ").trim()

    /**
     * **¿Contesta el asistente a esta nota?** En su chat, a todo lo que escribe el usuario; en
     * cualquier otro, solo a lo que lleve `@asistente`.
     */
    fun leToca(m: Mensaje, chatDelAsistente: String?): Boolean =
        m.clase == Clase.NOTA && !esRespuesta(m) && m.texto.isNotBlank() &&
            ((chatDelAsistente != null && m.proyecto == chatDelAsistente) || pideAlAsistente(m.texto))

    /**
     * **El lienzo propio del asistente en este chat**: el último dibujo hecho por él
     * (`aparato == "IA"`). Null si aún no hay: se crea con [NOMBRE_DEL_LIENZO].
     */
    fun lienzoPropio(mensajes: List<Mensaje>, chat: String?): Mensaje? =
        mensajes.lastOrNull { it.proyecto == chat && it.clase == Clase.DIBUJO && esRespuesta(it) && it.referencia != null }

    /** Una foto entre los últimos [FOTO_ATRAS] mensajes la ve el modelo (`FOTO_ATRAS` del PC). */
    const val FOTO_ATRAS = 4

    /**
     * **La foto que ve el modelo**: la última imagen del usuario entre los últimos [FOTO_ATRAS]
     * mensajes del chat antes de [pregunta] (aunque haya una respuesta en medio).
     */
    fun fotoDe(mensajes: List<Mensaje>, pregunta: Mensaje): Mensaje? {
        val delChat = mensajes.filter { it.proyecto == pregunta.proyecto && !it.enBuzon }
        val hasta = delChat.indexOfFirst { it.id == pregunta.id }.let { if (it < 0) delChat.size else it }
        return delChat.subList(maxOf(0, hasta - FOTO_ATRAS), hasta)
            .lastOrNull { it.clase == Clase.IMAGEN && !esRespuesta(it) && it.ruta != null }
    }

    /** Cuántas letras de conversación anterior caben, a ~3 por token, en un contexto de 4096. */
    const val LETRAS_DE_HISTORIA = 3600

    /**
     * **La conversación para el modelo**: las instrucciones, lo último del chat y la pregunta.
     *
     * - En el chat del asistente: los turnos de antes como `user`/`assistant`, de lo más nuevo hacia
     *   atrás hasta [LETRAS_DE_HISTORIA] (un 0.8B con 4096 de contexto no da para más).
     * - En otro chat (`@asistente`): lo último que hay escrito en él, como contexto, en el mismo
     *   mensaje de la pregunta: «sobre lo que hay ahí».
     */
    fun historial(
        sistema: String,
        mensajes: List<Mensaje>,
        pregunta: Mensaje,
        enSuChat: Boolean,
        foto: Pair<ByteArray, String>?
    ): MutableList<MensajeIa> {
        val pedido = sinMencion(pregunta.texto).ifBlank { pregunta.texto }
        val antes = mensajes.filter { it.proyecto == pregunta.proyecto && !it.enBuzon && it.cuando <= pregunta.cuando && it.id != pregunta.id }
        val salida = mutableListOf(MensajeIa.sistema(sistema))
        if (enSuChat) {
            val turnos = ArrayList<MensajeIa>()
            var letras = 0
            for (m in antes.asReversed()) {
                if (m.clase != Clase.NOTA || m.texto.isBlank() || m.texto == PENSANDO) continue
                val texto = sinMencion(limpiarRespuesta(m.texto))
                if (texto.isBlank()) continue
                letras += texto.length
                if (letras > LETRAS_DE_HISTORIA) break
                turnos += MensajeIa.texto(if (esRespuesta(m)) Rol.ASISTENTE else Rol.USUARIO, texto)
            }
            salida += turnos.asReversed().dropWhile { it.rol == Rol.ASISTENTE }
        }
        val contexto = if (enSuChat) "" else {
            val lineas = ArrayList<String>()
            var letras = 0
            for (m in antes.asReversed()) {
                val t = when (m.clase) {
                    Clase.NOTA -> m.texto
                    Clase.IMAGEN -> "[foto] " + m.texto
                    Clase.ARCHIVO, Clase.PAGINA -> "[archivo «${m.nombre}»] " + m.texto
                    Clase.DIBUJO -> "[lienzo «${m.nombre}»]"
                    else -> m.texto.take(200)
                }.trim()
                if (t.isBlank()) continue
                val linea = (if (m.numero > 0) "#${m.numero}: " else "") + t.take(400)
                letras += linea.length
                if (letras > LETRAS_DE_HISTORIA || lineas.size >= 12) break
                lineas += linea
            }
            if (lineas.isEmpty()) "" else "Lo ultimo de este chat:\n" + lineas.asReversed().joinToString("\n") + "\n\n"
        }
        val texto = contexto + pedido
        salida += if (foto != null) MensajeIa.usuarioConImagen(texto, foto.first, foto.second) else MensajeIa.usuario(texto)
        return salida
    }

    /** Lo que se le enseña al modelo de una respuesta suya de antes: sin las líneas de estado ni avisos. */
    fun limpiarRespuesta(t: String): String = t.lineSequence()
        .filterNot { l -> ESTADOS.any { l.startsWith(it) } }
        .joinToString("\n")
        .replace(" ⏹ (parado)", "")
        .trim()

    private val ESTADOS = listOf("✏️ ", "📊 ", "🔎 ", "⚙️ ", "🔁 ", "💭 ", "⚠️ ")

    /** La línea de estado mientras usa una herramienta (`chat::rotulo_de` del PC). */
    fun rotuloDe(herramienta: String): String = when (herramienta) {
        "dibujar_en_lienzo" -> "✏️ dibujando en el lienzo…"
        "grafica" -> "📊 haciendo la gráfica…"
        "ocr" -> "🔎 leyendo el documento…"
        else -> "⚙️ trabajando…"
    }

    const val CORRIGIENDO = "🔁 corrigiendo…"
    const val PARADO = " ⏹ (parado)"

    /**
     * **Lo que enseña la burbuja**: lo escrito y, debajo, la línea de estado si la hay; mientras
     * escribe acaba en ` ▍` (`EnVivo::mostrar` del PC).
     */
    fun mostrar(texto: String, estado: String?, escribiendo: Boolean): String {
        val base = texto.trimEnd().ifEmpty { if (estado == null) PENSANDO else "" }
        val conEstado = if (estado != null) (if (base.isEmpty()) estado else "$base\n$estado") else base
        return if (escribiendo && estado == null && base != PENSANDO) "$conEstado ▍" else conEstado
    }

    /** El texto final de la respuesta, con los fallos al final en una línea (`⚠️ a · b`). */
    fun textoFinal(texto: String, fallos: List<String>, parado: Boolean): String {
        val base = texto.trim()
        val conFallos = if (fallos.isEmpty()) base else (if (base.isEmpty()) "" else "$base\n") + "⚠️ " + fallos.distinct().joinToString(" · ")
        return when {
            parado && conFallos.isEmpty() -> "⏹ (parado)"
            parado -> conFallos + PARADO
            else -> conFallos.ifEmpty { com.forge.pixpin.ia.Agente.SIN_PALABRAS }
        }
    }

    /** Parte del texto del modelo que no se enseña: lo que va entre `<think>` y `</think>`. */
    fun sinPensado(t: String): String {
        val abre = t.indexOf("<think>")
        if (abre < 0) return t
        val cierra = t.indexOf("</think>", abre)
        return if (cierra < 0) t.substring(0, abre) else t.substring(0, abre) + t.substring(cierra + "</think>".length).trimStart()
    }

}
