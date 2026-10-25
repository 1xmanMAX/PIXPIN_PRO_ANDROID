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

    // ----------------------------------------------------------------------- «Conversación nueva» (PC 17-oct)

    /** Lo que dice la nota que corta la conversación. */
    const val TEXTO_NUEVA_CONVERSACION = "🧹 Conversación nueva: lo de arriba ya no se le manda al asistente."

    fun esCorte(m: Mensaje): Boolean = esRespuesta(m) && m.nuevaConversacionIa == true

    /** **Lo que el asistente ve de la conversación**: lo de después de la última «Conversación nueva». */
    fun desdeElCorte(mensajes: List<Mensaje>): List<Mensaje> {
        val i = mensajes.indexOfLast { esCorte(it) }
        return if (i < 0) mensajes else mensajes.subList(i + 1, mensajes.size)
    }

    /** La nota que corta: del asistente, en su chat. */
    fun notaDeCorte(proyecto: String?, ahora: Long): Mensaje = Mensaje(
        id = java.util.UUID.randomUUID().toString(), cuando = ahora, clase = Clase.NOTA, texto = TEXTO_NUEVA_CONVERSACION,
        proyecto = proyecto, aparato = APARATO, nuevaConversacionIa = true
    )

    /**
     * **«(Ahora: vie 17-oct-2026 14:05, hora local del usuario.)»** al final del pedido, solo con la nube
     * (PC `nota_de_ahora`): con la hora de ahora el modelo entiende «lo de esta mañana».
     */
    fun notaDeAhora(ahoraUtc: Long, desfase: Long): String = fechaYHora(ahoraUtc, desfase)?.let { "\n\n(Ahora: $it, hora local del usuario.)" }.orEmpty()

    /** `vie 3-oct-2026 14:05` en hora local (PC `fechas::fecha_y_hora`); null antes del 2001 (sin hora). */
    fun fechaYHora(msUtc: Long, desfaseMs: Long): String? {
        if (msUtc < 978_307_200_000L) return null
        val m = com.forge.pixpin.ia.Momento.deMsLocales(msUtc + desfaseMs)
        val (y, mes, d) = com.forge.pixpin.ia.Fechas.fechaDe(m.dia)
        val meses = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
        val sem = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")[com.forge.pixpin.ia.Fechas.diaDeLaSemana(m.dia).toInt()]
        return "$sem $d-${meses[(mes - 1).toInt()]}-$y %02d:%02d".format(m.minuto / 60, m.minuto % 60)
    }

    /** Hora local menos UTC, redondeada al cuarto de hora. */
    fun desfaseLocal(): Long {
        val ahora = System.currentTimeMillis()
        val d = java.util.TimeZone.getDefault().getOffset(ahora).toLong()
        return Math.round(d / 900_000.0) * 900_000L
    }

    /** Los archivos del chat con su fecha al minuto (hasta 15), para la nube. */
    fun archivosConFecha(delChat: List<Mensaje>, desfase: Long): String {
        val l = desdeElCorte(delChat).filter { it.ruta != null && it.nombre.isNotBlank() }.takeLast(15)
        if (l.isEmpty()) return ""
        return "\n\n(Archivos del chat: " + l.joinToString("; ") { m -> m.nombre + (fechaYHora(m.cuando, desfase)?.let { " — $it" } ?: "") } + ".)"
    }

    // ----------------------------------------------------------------------- `@` para elegir lienzo (§4.4)

    const val MENCION_LIENZO = "@lienzo:"
    const val OPCIONES_MAX = 6

    /** Si es un lienzo (o una hoja de PDF con dibujo) en el que se puede dibujar. */
    fun esLienzo(m: Mensaje) = (m.clase == Clase.DIBUJO || m.clase == Clase.PAGINA) && !m.referencia.isNullOrEmpty()

    /** Cómo se le llama a un lienzo en la lista y en la mención. */
    fun nombreDeLienzo(m: Mensaje): String {
        val n = m.nombre.trim()
        return when {
            n.isNotEmpty() && !n.endsWith(".excalidraw") -> n
            m.clase == Clase.PAGINA -> "Hoja ${m.pagina?.let { it + 1 } ?: m.numero}"
            else -> "Lienzo ${m.numero}"
        }
    }

    fun mencionDeLienzo(m: Mensaje) = MENCION_LIENZO + nombreDeLienzo(m).replace(Regex("\\s"), "_")

    private fun sinTildesMencion(s: String) = s.lowercase().map {
        when (it) { 'á', 'à', 'ä' -> 'a'; 'é', 'è', 'ë' -> 'e'; 'í', 'ì', 'ï' -> 'i'; 'ó', 'ò', 'ö' -> 'o'; 'ú', 'ù', 'ü' -> 'u'; '_' -> ' '; else -> it }
    }.joinToString("")

    data class Opcion(val rotulo: String, val mencion: String)

    /** **Lo que se ofrece al escribir `@`**: el asistente (fuera de su chat) y los lienzos y hojas del chat. */
    fun opcionesDeMencion(delChat: List<Mensaje>, escrito: String, enChatDelAsistente: Boolean): List<Opcion> {
        val buscado = sinTildesMencion(escrito)
        val v = ArrayList<Opcion>()
        if (!enChatDelAsistente) v += Opcion("🤖 Asistente", MENCION)
        val vistas = HashSet<String?>()
        for (m in delChat.asReversed().filter(::esLienzo)) {
            if (!vistas.add(m.referencia)) continue
            v += Opcion("${if (m.clase == Clase.PAGINA) "📄" else "🎨"} ${nombreDeLienzo(m)}", mencionDeLienzo(m))
        }
        return v.filter { o -> buscado.isEmpty() || sinTildesMencion(o.rotulo).contains(buscado) || sinTildesMencion(o.mencion.trimStart('@')).startsWith(buscado) }
            .take(OPCIONES_MAX)
    }

    /**
     * **La pregunta con sus menciones de lienzo resueltas**: el texto para el modelo («el lienzo
     * «Nombre»») y la referencia del lienzo nombrado. Una mención que no casa se deja tal cual.
     */
    fun resolverLienzo(pregunta: String, delChat: List<Mensaje>): Pair<String, String?> {
        val texto = StringBuilder()
        var destino: String? = null
        var resto = pregunta
        while (true) {
            val i = resto.lowercase().indexOf(MENCION_LIENZO).takeIf { it >= 0 } ?: break
            texto.append(resto, 0, i)
            val tras = resto.substring(i + MENCION_LIENZO.length)
            val fin = tras.indexOfFirst { it.isWhitespace() }.let { if (it < 0) tras.length else it }
            val nombre = tras.substring(0, fin).trimEnd(',', '.', ';', ':', '?', '!')
            val buscado = sinTildesMencion(nombre)
            val hallado = delChat.asReversed().filter(::esLienzo).firstOrNull { sinTildesMencion(nombreDeLienzo(it)) == buscado }
            if (hallado != null) { texto.append("el lienzo «${nombreDeLienzo(hallado)}»"); destino = hallado.referencia }
            else texto.append(resto, i, i + MENCION_LIENZO.length + nombre.length)
            resto = tras.substring(nombre.length)
        }
        texto.append(resto)
        return texto.toString() to destino
    }

    /** Si se pide abrir el lienzo al terminar («dibuja… y ábrelo»). */
    fun pideAbrir(pregunta: String): Boolean {
        val t = sinTildesMencion(pregunta)
        return listOf("abrelo", "abremelo", "abre el lienzo", "abrir el lienzo", "abre el dibujo", "abrelo al terminar", "muestramelo", "ensenamelo", "y abre", "y abrir").any { t.contains(it) }
    }

    /** Un mensaje de sistema (`auto-*`: letras, figuras, automatizaciones): viaja, pero no es burbuja. */
    fun esDeSistema(m: Mensaje): Boolean = m.id.startsWith("auto-")

    /** **El chat del asistente**, si ya hay uno (ver arriba el orden). */
    fun buscar(proyectos: List<Proyecto>, mensajes: List<Mensaje>): Proyecto? {
        val vivos = proyectos.filter { !it.archivado }
        // Con varios marcados (llegaron de dos aparatos antes de que el PC los junte), **el más
        // antiguo**, por `creado` y luego por id: el mismo que elige el PC al unificarlos
        // (`asistente_unico.rs`, 22-oct-2026), así ninguno escribe en el que va a desaparecer.
        vivos.filter { it.asistente == true }
            .minWithOrNull(compareBy<Proyecto>({ it.creado.coerceAtLeast(0) }, { it.id }))
            ?.let { return it }
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
        val antes = desdeElCorte(mensajes.filter { it.proyecto == pregunta.proyecto && !it.enBuzon && it.cuando <= pregunta.cuando && it.id != pregunta.id })
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

    private val ESTADOS = listOf("✏️ ", "📊 ", "🔎 ", "⚙️ ", "🔁 ", "💭 ", "⚠️ ", "🗂️ ", "🖼️ ", "📖 ", "❓ ")

    /** La línea de estado mientras usa una herramienta (`chat::rotulo_de` del PC). */
    fun rotuloDe(herramienta: String): String = when (herramienta) {
        "dibujar_en_lienzo" -> "✏️ dibujando en el lienzo…"
        "grafica" -> "📊 haciendo la gráfica…"
        "ocr" -> "🔎 leyendo el documento…"
        com.forge.pixpin.ia.Ojos.NOMBRE -> "👀 mirando el lienzo…"
        "buscar_historial" -> "🗂️ buscando en la conversación…"
        "calcar_imagen" -> "🖼️ calcando la imagen…"
        "ayuda_app" -> "📖 mirando la ayuda…"
        else -> "⚙️ trabajando…"
    }

    /** Cómo queda en la nota una pregunta Sí/No (§4.3): con su respuesta, o que caducó. */
    fun lineaDePregunta(pregunta: String, r: Boolean?): String = "❓ $pregunta → " + when (r) {
        true -> "✅ Sí"
        false -> "✖️ No"
        null -> "⌛ sin respuesta: no se hizo nada"
    }

    /** Las preguntas contestadas, delante del texto de la respuesta. */
    fun conPreguntas(preguntas: List<String>, texto: String): String =
        if (preguntas.isEmpty()) texto else (preguntas + texto).filter { it.isNotBlank() }.joinToString("\n")

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
