package com.forge.pixpin.privacidad

/**
 * **Privacidad y datos** (30-oct-2026, para publicar en Google Play): la política, el correo de
 * contacto y **lo que sale del teléfono y adónde**, escrito para el usuario.
 *
 * Es lo que se enseña en Ajustes › «Privacidad y datos» ([PrivacidadYDatos]) y lo que tiene que
 * casar con la política publicada y con la ficha de «Seguridad de los datos» de Play. Si una
 * función nueva manda algo a internet, **se apunta aquí** (y en la política): un destino que no
 * sale en esta lista es un destino que el usuario no sabe que existe.
 *
 * La auditoría del 30-oct-2026 (todo lo que abre una conexión): IA con la clave del usuario
 * ([com.forge.pixpin.ia.Nube], [com.forge.pixpin.jev.Cliente]), la web del asistente
 * ([com.forge.pixpin.asistente.web.Buscar], [com.forge.pixpin.asistente.web.RedDelTelefono]), las
 * voces de Edge ([com.forge.pixpin.ui.VozDeEdge]), las descargas de modelos (Vosk, Whisper,
 * Qwen3-ASR, Indra y su diccionario), «compartir por enlace» ([com.forge.pixpin.motor.SubirArchivo])
 * y la sincronización con el PC por la Wi-Fi ([com.forge.pixpin.sincro.Red]). No hay analíticas, ni
 * anuncios, ni informes de fallos, ni servidores de PixPin. Sin Android: se prueba en `PrivacidadTest`.
 */
object Privacidad {

    /** La política de privacidad publicada (la misma URL que en la ficha de Play). */
    const val URL_POLITICA = "https://1xmanmax.github.io/PIXPIN_PRO_ANDROID/privacidad.html"

    /** A quién se escribe: dudas de privacidad, borrar datos y los reportes de contenido de la IA. */
    const val CORREO_CONTACTO = "max.antony.8.5.1@gmail.com"

    /** Un sitio adonde sale algo: [que] se manda, [adonde] (el servidor) y [cuando] (qué lo provoca). */
    data class Destino(val que: String, val adonde: String, val cuando: String)

    /** **Lo que sale del teléfono**, en el orden en que más importa (lo personal primero). */
    val DESTINOS: List<Destino> = listOf(
        Destino(
            "Lo que preguntas al asistente, lo reciente del chat y lo que le adjuntes (fotos, documentos)",
            "Anthropic (Claude) o DeepSeek",
            "Solo si pegas tu propia clave y le preguntas. La clave se guarda cifrada en el teléfono."
        ),
        Destino(
            "Los textos de cada función de Jev: renglones de una clase, frases de una reunión, tareas, lo que dictas",
            "TypeSafe (api.typesafe.ai)",
            "Solo con tu clave de TypeSafe y con esa función encendida (Ajustes › Asistente)."
        ),
        Destino(
            "Lo que el asistente busca y las páginas que lee para contestarte",
            "DuckDuckGo o Brave Search, y la web que lea",
            "Si «La web del asistente» está encendida. Sin cookies ni tu clave de IA."
        ),
        Destino(
            "El texto que se lee en voz alta",
            "Microsoft (voces de Edge)",
            "Solo si eliges una voz de Edge; si no, se lee con la voz del teléfono, sin red."
        ),
        Destino(
            "El archivo que eliges compartir por enlace",
            "litterbox.catbox.moe o temp.sh",
            "Solo al tocar «compartir por enlace»; se borra solo en horas o días."
        ),
        Destino(
            "Nada tuyo: solo se piden los archivos",
            "alphacephei.com (Vosk), huggingface.co y github.com (Whisper, Qwen3-ASR, Indra, diccionario)",
            "Al bajar un modelo de voz o de leer texto, cuando tú lo pides."
        ),
        Destino(
            "Tus capturas, notas y chats sincronizados",
            "Tus propios aparatos (el PC), por tu Wi-Fi",
            "Al sincronizar: directo en la red de casa, sin pasar por internet ni por servidores."
        ),
        Destino(
            "Lo que pida la página",
            "La web que enlace",
            "Una página HTML que abras puede cargar imágenes o guiones de internet, como en un navegador."
        )
    )

    /** Lo que **no** se hace, para que el resumen no deje dudas. */
    const val LO_QUE_NO = "Sin anuncios, sin analíticas, sin cuenta y sin servidores de PixPin: lo demás se queda en el teléfono. " +
        "El dictado usa el reconocedor del propio teléfono, sin red."

    /** El resumen en texto plano (una línea por destino), para el correo o para copiarlo. */
    fun resumen(): String = buildString {
        for (d in DESTINOS) append("• ").append(d.que).append(" → ").append(d.adonde).append(". ").append(d.cuando).append('\n')
        append(LO_QUE_NO)
    }
}
