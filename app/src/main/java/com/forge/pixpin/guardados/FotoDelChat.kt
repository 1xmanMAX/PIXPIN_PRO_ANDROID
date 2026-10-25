package com.forge.pixpin.guardados

import com.forge.pixpin.motor.Proyecto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * **La foto del chat**: lo que se ve en el círculo de cada chat (25-oct-2026, lo del PC `b7868c5`,
 * `0c250b4` y `85b84ba`; el usuario: «permitir poner un fondo en las esferas de los chats para que
 * parezca más una app de chat: dame la opción de poner emojis como su perfil, o poner una imagen
 * yo»).
 *
 * Es la evolución del logo del 4-oct ([LogoDelProyecto], que solo vivía en este aparato). Ahora
 * **viaja**: es el campo `avatar` del [Proyecto], el mismo que el PC saca de su ficha
 * (`resto["avatar"]`, `crates/pixpin-proyecto/src/avatar.rs`) y mete en el `Proyecto` que va por la
 * red. Se guarda **crudo** ([Proyecto.avatar] es un `JsonObject`) y no como una clase con sus
 * campos: un `tipo` o una clave que este aparato aún no conozca se pinta como las iniciales y
 * **vuelve tal cual** (regla 1 de sincronizar), o cada vuelta vería el proyecto cambiado.
 *
 * ```json
 * "avatar": {"tipo": "imagen",    "ruta": "pixpin:files/guardados/pc/<chat>/avatar-1761400000000.png", "cuando": 1761400000000}
 * "avatar": {"tipo": "emoji",     "emoji": "🏠", "color": "#FF885E", "color2": "#FF516A", "cuando": 1761400000000}
 * "avatar": {"tipo": "iniciales", "color": "#72D5FD", "color2": "#2A9EF1", "cuando": 1761400000000}
 * "avatar": {"tipo": "iniciales", "cuando": 1761400000000}
 * ```
 *
 * - `color` y `color2` son el degradado de arriba abajo del círculo; sin `color2`, liso; sin
 *   ninguno, el de siempre.
 * - La imagen va **cuadrada, recortada y pequeña** ([LADO] de lado, PNG) con un **nombre nuevo
 *   cada vez** (`avatar-<ms>.png`, [nombreDeImagen]): una foto nueva es un fichero nuevo, nunca el
 *   mismo cambiado, que perdería contra el del otro si su reloj va adelantado. Aquí vive en
 *   `files/guardados/` ([CARPETA]); la del PC llega a `files/guardados/pc/<chat>/`. Su ruta va en
 *   el texto del proyecto, así que [com.forge.pixpin.sincro.Disco.alcance] la lleva con él.
 * - **Volver a lo de siempre se escribe**, no se borra: `{"tipo":"iniciales"}` sin colores. Así la
 *   fusión ([com.forge.pixpin.sincro.Mezcla], campo a campo) ve un cambio y no una ausencia.
 *
 * Aquí, la parte pura, sin Android: leer y escribir el formato, los doce fondos, la rejilla de
 * emojis con su buscador, el [Recorte] y lo que se elige en la hoja ([Eleccion]). Pintarla y la hoja
 * están en `FotoDelChatUi.kt`. Pruebas: `FotoDelChatTest`.
 */
object FotoDelChat {
    /** La clave en el `Proyecto` que viaja. */
    const val CLAVE = "avatar"
    const val IMAGEN = "imagen"
    const val EMOJI = "emoji"
    const val INICIALES = "iniciales"

    /** El lado con que se guarda la imagen: el avatar más grande al 300 % y algo de sobra (como el PC). */
    const val LADO = 256

    /** Dónde se guardan las imágenes nacidas aquí, relativo a `files/`: una carpeta de la regla 2. */
    const val CARPETA = "guardados"

    /** El logo de antes del PC (`logo.png` de su carpeta): llega como `tipo: imagen` y también se limpia. */
    const val LOGO_DE_ANTES = "logo.png"

    /**
     * La foto del chat leída. Solo para pintar y decidir: lo que se guarda y viaja es el JSON crudo
     * del [Proyecto].
     */
    data class Avatar(
        val tipo: String = "",
        val emoji: String? = null,
        /** `#RRGGBB`: arriba del degradado. */
        val color: String? = null,
        /** `#RRGGBB`: abajo del degradado. Sin él, liso. */
        val color2: String? = null,
        /** Solo con `imagen`: el fichero, con su ruta local (como `pdfOrigen`). */
        val ruta: String? = null,
        /** Cuándo se puso, en milisegundos desde 1970. Informativo. */
        val cuando: Long = 0L
    ) {
        val esImagen: Boolean get() = tipo == IMAGEN && !ruta.isNullOrEmpty()

        /** El emoji, si es de emoji y trae uno que no esté en blanco. */
        val elEmoji: String? get() = if (tipo == EMOJI) emoji?.takeIf { it.isNotBlank() } else null

        /** Si se pinta como siempre: iniciales (o un tipo que no se conoce, o un emoji vacío) sin fondo propio. */
        val esLoDeSiempre: Boolean get() = !esImagen && elEmoji == null && fondo == null

        /** El degradado `(arriba, abajo)` en `0xRRGGBB`, si se eligió uno válido. Con un solo color, liso. */
        val fondo: Pair<Int, Int>?
            get() {
                val arriba = color?.let(::colorDeTexto) ?: return null
                val abajo = color2?.let(::colorDeTexto) ?: arriba
                return arriba to abajo
            }

        /** En JSON, como lo escribe el PC: sin los campos vacíos ni `cuando` a cero. */
        fun aJson(): JsonObject = JsonObject(buildMap {
            put("tipo", JsonPrimitive(tipo))
            emoji?.let { put("emoji", JsonPrimitive(it)) }
            color?.let { put("color", JsonPrimitive(it)) }
            color2?.let { put("color2", JsonPrimitive(it)) }
            ruta?.let { put("ruta", JsonPrimitive(it)) }
            if (cuando != 0L) put("cuando", JsonPrimitive(cuando))
        })
    }

    /** Una imagen ya guardada. */
    fun imagen(ruta: String, cuando: Long) = Avatar(tipo = IMAGEN, ruta = ruta, cuando = cuando)

    /** Un emoji sobre su fondo (`#RRGGBB` de arriba y de abajo). */
    fun emoji(emoji: String, fondo: Pair<String, String>?, cuando: Long): Avatar {
        val (c, c2) = colores(fondo)
        return Avatar(tipo = EMOJI, emoji = emoji, color = c, color2 = c2, cuando = cuando)
    }

    /** Las iniciales sobre el fondo elegido o, sin fondo, el de siempre: esto último es «volver a lo de siempre». */
    fun iniciales(fondo: Pair<String, String>?, cuando: Long): Avatar {
        val (c, c2) = colores(fondo)
        return Avatar(tipo = INICIALES, color = c, color2 = c2, cuando = cuando)
    }

    private fun colores(fondo: Pair<String, String>?): Pair<String?, String?> = when {
        fondo == null -> null to null
        fondo.first == fondo.second -> fondo.first to null
        else -> fondo.first to fondo.second
    }

    /**
     * **La foto de un proyecto, leída.** Un JSON que no es un objeto es «ninguna»; un campo de otro
     * tipo del que se espera se ignora (como `serde(default)` en el PC, que no tira la foto entera).
     */
    fun de(json: JsonElement?): Avatar? {
        val o = json as? JsonObject ?: return null
        fun texto(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return Avatar(
            tipo = texto("tipo").orEmpty(),
            emoji = texto("emoji"),
            color = texto("color"),
            color2 = texto("color2"),
            ruta = texto("ruta"),
            cuando = (o["cuando"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
        )
    }

    fun de(p: Proyecto?): Avatar? = de(p?.avatar)

    /** `#RRGGBB` (o `RRGGBB`) a `0xRRGGBB`. Otra cosa, nulo. */
    fun colorDeTexto(t: String): Int? {
        val h = t.trim().removePrefix("#")
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return h.toInt(16)
    }

    /** `0xRRGGBB` a `#RRGGBB`, como se guarda. */
    fun textoDeColor(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    /** El nombre de la imagen que se guarda en un momento: uno nuevo cada vez. */
    fun nombreDeImagen(cuando: Long) = "avatar-$cuando.png"

    /** Si [nombre] es una imagen de foto guardada por PixPin (`avatar-<cifras>.png`). */
    fun esImagenDeAvatar(nombre: String): Boolean {
        if (!nombre.startsWith("avatar-") || !nombre.endsWith(".png") || nombre.length <= 11) return false
        return nombre.substring(7, nombre.length - 4).all { it in '0'..'9' }
    }

    /**
     * **Las imágenes de foto que ya no nombra nadie** tras pasar de [antes] a [despues]: las rutas
     * (locales) de las fotos `imagen` de antes que ya no son la foto de ningún proyecto. Solo las que
     * se llaman como las guarda PixPin ([esImagenDeAvatar], o el `logo.png` del PC): una ruta que
     * nombre otra cosa (una foto del chat) no se toca nunca. Al cambiarla, la vieja se borra «sin
     * lápida» (el manual del PC, §10): ya nadie la nombra, así que no viaja.
     */
    fun sobrantes(antes: List<Proyecto>, despues: List<Proyecto>): Set<String> {
        fun rutas(l: List<Proyecto>) = l.mapNotNullTo(HashSet()) { p -> de(p)?.takeIf { it.esImagen }?.ruta }
        val quedan = rutas(despues)
        return rutas(antes).filterTo(HashSet()) { r ->
            r !in quedan && File(r).name.let { esImagenDeAvatar(it) || it == LOGO_DE_ANTES }
        }
    }

    // --- Los fondos -------------------------------------------------------------------------

    /** Un fondo del círculo: un degradado de [arriba] a [abajo], en `0xRRGGBB`. */
    data class Fondo(val arriba: Int, val abajo: Int)

    /**
     * Los fondos que se ofrecen, en el orden de la paleta del PC (`pixpin_ui::foto_del_chat::FONDOS`).
     * Los siete primeros son los degradados de los avatares de Telegram (rojo, naranja, violeta,
     * verde, turquesa, azul y rosa); detrás, amarillo, marrón, índigo, gris y grafito. Se guardan como
     * `#RRGGBB`, así que otro aparato los pinta aunque no tenga esta lista.
     */
    val FONDOS: List<Fondo> = listOf(
        Fondo(0xFF885E, 0xFF516A),
        Fondo(0xFFCD6A, 0xFFA85C),
        Fondo(0x82B1FF, 0x665FFF),
        Fondo(0xA0DE7E, 0x54CB68),
        Fondo(0x53EDD6, 0x28C9B7),
        Fondo(0x72D5FD, 0x2A9EF1),
        Fondo(0xE0A2F3, 0xD669ED),
        Fondo(0xFFE27A, 0xF5C33B),
        Fondo(0xD9A77A, 0xB07B4F),
        Fondo(0x9FA8DA, 0x5C6BC0),
        Fondo(0xB8C2CC, 0x8A96A3),
        Fondo(0x5A6270, 0x2F3540),
    )

    /**
     * El fondo que se propone para un emoji sin fondo elegido: uno fijo por chat, que sale de su
     * código único (FNV-1a sobre sus bytes UTF-8, como el PC: estable entre aparatos).
     */
    fun fondoPropuesto(codigo: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in codigo.trim().toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return (h.toUInt() % FONDOS.size.toUInt()).toInt()
    }

    /** En qué lugar de la paleta está un degradado, si es uno de ella. */
    fun lugarDelFondo(arriba: Int, abajo: Int): Int? =
        FONDOS.indexOfFirst { it.arriba == arriba && it.abajo == abajo }.takeIf { it >= 0 }

    // --- Los emojis -------------------------------------------------------------------------

    /**
     * Los de la rejilla, con las palabras con que se encuentran (castellano e inglés, en minúsculas
     * y sin tildes), por temas: caras, trabajo y estudio, casa y obra, naturaleza, comida, viajes,
     * deporte y ocio, y símbolos. Los mismos y en el mismo orden que el PC.
     */
    val EMOJIS: List<Pair<String, String>> = listOf(
        "😀" to "cara sonrisa feliz alegre smile happy face",
        "😎" to "gafas sol guay cool sunglasses",
        "🤓" to "empollon estudio gafas nerd",
        "🥳" to "fiesta celebrar party celebrate",
        "😍" to "amor enamorado love heart eyes",
        "🤔" to "pensar duda think thinking",
        "😴" to "dormir sueno sleep",
        "🤖" to "robot bot ia ai",
        "👻" to "fantasma ghost",
        "👽" to "alien extraterrestre",
        "🐱" to "gato cat",
        "🐶" to "perro dog",
        "💼" to "trabajo maletin oficina work briefcase office",
        "📚" to "libros estudio clase books study school",
        "🎓" to "graduacion universidad tesis graduation university thesis",
        "✏️" to "lapiz escribir apuntes pencil write notes",
        "📝" to "nota apuntes memo notes",
        "📐" to "regla escuadra dibujo tecnico ruler drawing",
        "📊" to "grafico datos estadistica chart data",
        "💡" to "idea bombilla light bulb",
        "🔬" to "microscopio ciencia laboratorio science lab",
        "🧪" to "quimica experimento chemistry test",
        "💻" to "ordenador portatil codigo computer laptop code",
        "📱" to "movil telefono phone mobile",
        "📅" to "calendario fecha agenda calendar",
        "📌" to "chincheta fijar pin",
        "🗂️" to "carpetas archivo fichero folders files",
        "💰" to "dinero cuentas money",
        "🏠" to "casa hogar home house",
        "🏡" to "casa jardin chalet garden house",
        "🏢" to "edificio oficina building office",
        "🏗️" to "obra construccion grua construction building site",
        "🧱" to "ladrillo muro obra brick wall",
        "🔨" to "martillo herramienta hammer tool",
        "🔧" to "llave herramienta arreglar wrench fix",
        "🪚" to "sierra carpinteria saw",
        "📏" to "regla medir metro ruler measure",
        "🛋️" to "sofa salon muebles couch furniture",
        "🛏️" to "cama dormitorio bed bedroom",
        "🚿" to "ducha bano shower bathroom",
        "🍳" to "cocina sarten kitchen cooking",
        "🔑" to "llave key",
        "🌳" to "arbol naturaleza tree nature",
        "🌻" to "girasol flor sunflower flower",
        "🌸" to "flor cerezo blossom flower",
        "🌵" to "cactus desierto desert",
        "🍀" to "trebol suerte clover luck",
        "🌊" to "ola mar playa wave sea beach",
        "⛰️" to "montana mountain",
        "🌙" to "luna noche moon night",
        "☀️" to "sol sun",
        "⭐" to "estrella star",
        "🔥" to "fuego fire hot",
        "❄️" to "nieve invierno snow winter",
        "🌈" to "arcoiris rainbow",
        "🌍" to "mundo tierra planeta world earth globe",
        "🍕" to "pizza comida food",
        "🍔" to "hamburguesa burger food",
        "🍎" to "manzana fruta apple fruit",
        "🥑" to "aguacate avocado",
        "☕" to "cafe coffee",
        "🍷" to "vino wine",
        "🍰" to "tarta pastel cake",
        "🥗" to "ensalada dieta salad",
        "✈️" to "avion viaje plane travel",
        "🚗" to "coche car",
        "🚲" to "bici bicicleta bike",
        "🚀" to "cohete lanzamiento proyecto rocket launch",
        "🏖️" to "playa vacaciones beach holiday",
        "🗺️" to "mapa viaje map travel",
        "🧳" to "maleta equipaje luggage",
        "⚽" to "futbol balon soccer football",
        "🏀" to "baloncesto basket basketball",
        "🎾" to "tenis tennis",
        "🏋️" to "gimnasio pesas gym",
        "🧘" to "yoga meditar relax",
        "🎮" to "videojuego juego game",
        "🎵" to "musica nota music",
        "🎸" to "guitarra guitar",
        "🎨" to "arte pintura paleta art paint",
        "📷" to "camara foto camera photo",
        "🎬" to "cine pelicula video movie film",
        "🎁" to "regalo gift present",
        "🎉" to "fiesta confeti party",
        "❤️" to "corazon rojo amor heart love red",
        "💙" to "corazon azul heart blue",
        "💚" to "corazon verde heart green",
        "💛" to "corazon amarillo heart yellow",
        "💜" to "corazon morado heart purple",
        "✅" to "hecho listo check done",
        "⚠️" to "aviso cuidado warning",
        "❓" to "pregunta duda question",
        "💬" to "chat conversacion mensaje message",
        "🔔" to "campana aviso bell notification",
        "🔒" to "candado privado lock private",
        "⚡" to "rayo energia lightning energy",
        "💎" to "diamante joya diamond gem",
        "👑" to "corona rey crown king",
        "🏆" to "trofeo premio trophy",
        "🎯" to "diana objetivo target goal",
    )

    /** Minúsculas y sin tildes, para buscar emojis sin pensar en acentos (la tabla de `foto_del_chat.rs`). */
    fun normalizar(s: String): String = buildString {
        for (c in s.trim().lowercase()) append(
            when (c) {
                'á', 'à', 'ä', 'â' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                'ñ' -> 'n'
                else -> c
            }
        )
    }

    /**
     * **Los emojis que encuentra [consulta]**, por su lugar en [EMOJIS]. Sin nada escrito, todos.
     * Cada palabra escrita tiene que empezar alguna de sus palabras («cas» encuentra la casa; «casa
     * jar», la del jardín); un emoji pegado se encuentra a sí mismo.
     */
    fun buscarEmojis(consulta: String): List<Int> {
        val q = normalizar(consulta)
        if (q.isEmpty()) return EMOJIS.indices.toList()
        val trozos = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return EMOJIS.indices.filter { i ->
            val (e, nombres) = EMOJIS[i]
            e == consulta.trim() || trozos.all { t -> nombres.split(' ').any { it.startsWith(t) } }
        }
    }

    // --- El recorte ---------------------------------------------------------------------------

    /** Lo más que se acerca, veces «el cuadrado más grande». */
    const val AUMENTO_MAXIMO = 8f

    /** Lo que acerca un paso de rueda (120) en el PC. */
    const val PASO = 1.2f

    /** `round` de Rust (la mitad, lejos del cero): los mismos píxeles que el PC. */
    private fun redondear(x: Float): Float = if (x >= 0f) kotlin.math.floor(x + 0.5f) else -kotlin.math.floor(-x + 0.5f)

    /**
     * **Qué cuadrado de una foto `w × h` entra en el círculo**: lo acercado (1 = el cuadrado más
     * grande que cabe, el lado corto entero) y su centro en píxeles de la foto. Al abrirla, el del
     * centro; pellizcar acerca hacia los dedos y arrastrar la lleva, sin que el cuadrado se salga
     * nunca de la foto. Lo que se ve en el círculo es lo que se guarda. Las cuentas del PC tal cual
     * (`pixpin_ui::foto_del_chat::Recorte`), en `Float` como allí en `f32`.
     */
    data class Recorte(val aumento: Float, val cx: Float, val cy: Float) {
        /** El lado del cuadrado, en píxeles de la foto. */
        fun lado(w: Int, h: Int): Float = minOf(w, h).toFloat() / aumento.coerceIn(1f, AUMENTO_MAXIMO)

        /** Dentro de la foto: el aumento entre 1 y el máximo y el cuadrado sin salirse por ningún lado. */
        fun acotado(w: Int, h: Int): Recorte {
            val a = aumento.coerceIn(1f, AUMENTO_MAXIMO)
            val mitad = minOf(w, h).toFloat() / a / 2f
            return Recorte(
                a,
                cx.coerceIn(mitad, maxOf(w - mitad, mitad)),
                cy.coerceIn(mitad, maxOf(h - mitad, mitad))
            )
        }

        /** El cuadrado `(x, y, lado)` en píxeles enteros de la foto, el que se recorta al guardar. */
        fun enPixeles(w: Int, h: Int): Triple<Int, Int, Int> {
            val r = acotado(w, h)
            val lado = redondear(r.lado(w, h)).toInt().coerceIn(1, maxOf(minOf(w, h), 1))
            val x = minOf(maxOf(redondear(r.cx - lado / 2f), 0f).toInt(), maxOf(w - lado, 0))
            val y = minOf(maxOf(redondear(r.cy - lado / 2f), 0f).toInt(), maxOf(h - lado, 0))
            return Triple(x, y, lado)
        }

        /**
         * Dónde se pinta la foto ENTERA para que su cuadrado caiga en la caja `(x, y, lado)`:
         * `(x, y, ancho, alto)`. Quien pinta la recorta al círculo.
         */
        fun destino(cajaX: Float, cajaY: Float, cajaLado: Float, w: Int, h: Int): FloatArray {
            val k = cajaLado / lado(w, h)
            return floatArrayOf(cajaX + cajaLado / 2f - cx * k, cajaY + cajaLado / 2f - cy * k, w * k, h * k)
        }

        /** Arrastrar la foto `(dx, dy)` puntos de pantalla en una caja de [ladoCaja]: el centro va al revés. */
        fun movido(w: Int, h: Int, ladoCaja: Float, dx: Float, dy: Float): Recorte {
            if (ladoCaja <= 0f) return this
            val k = lado(w, h) / ladoCaja
            return copy(cx = cx - dx * k, cy = cy - dy * k).acotado(w, h)
        }

        /**
         * Acerca (`factor` > 1) o aleja hacia un punto, en fracción de la caja (0..1 en cada eje): lo
         * que hay bajo los dedos se queda bajo ellos.
         */
        fun acercado(w: Int, h: Int, factor: Float, px: Float, py: Float): Recorte {
            val antes = lado(w, h)
            val fx = cx + (px - 0.5f) * antes
            val fy = cy + (py - 0.5f) * antes
            val a = (aumento * factor).coerceIn(1f, AUMENTO_MAXIMO)
            val despues = Recorte(a, cx, cy).lado(w, h)
            return Recorte(a, fx - (px - 0.5f) * despues, fy - (py - 0.5f) * despues).acotado(w, h)
        }

        /** Un giro de rueda del PC (`delta` en unidades de 120, positivo acerca). */
        fun rueda(w: Int, h: Int, delta: Int, px: Float, py: Float): Recorte =
            acercado(w, h, Math.pow(PASO.toDouble(), delta / 120.0).toFloat(), px, py)

        companion object {
            /** El cuadrado más grande, en el centro: como se abre. */
            fun centrado(w: Int, h: Int) = Recorte(1f, w / 2f, h / 2f)
        }
    }

    // --- Lo que se elige en la hoja -----------------------------------------------------------

    /** Lo que quedará en el círculo. */
    sealed interface Eleccion {
        /** Una imagen nueva (de la galería o pegada), con el cuadrado suyo que entra. */
        data class Nueva(val ancho: Int, val alto: Int, val recorte: Recorte) : Eleccion
        /** La imagen que ya tenía, sin tocar. */
        data object LaDeAntes : Eleccion
        data class ConEmoji(val emoji: String) : Eleccion
        data object Iniciales : Eleccion
    }

    /**
     * **Lo elegido en la hoja**, como `Estado` del PC (`ventana_chat/foto_del_chat.rs`): qué va en
     * el círculo, el fondo, el último emoji (elegir un fondo con una imagen puesta vuelve a él, o a
     * las iniciales) y si hay algo que guardar. Guardar sin haber tocado nada no escribe nada.
     */
    data class Estado(
        val eleccion: Eleccion,
        val fondo: Pair<Int, Int>?,
        val ultimoEmoji: String?,
        val tocado: Boolean = false
    ) {
        fun conImagen(ancho: Int, alto: Int) =
            copy(eleccion = Eleccion.Nueva(ancho, alto, Recorte.centrado(ancho, alto)), tocado = true)

        /** Un emoji sin fondo elegido se lleva el fondo propuesto del chat ([fondoPropuesto]). */
        fun conEmoji(e: String, codigo: String): Estado {
            val f = fondo ?: FONDOS[fondoPropuesto(codigo)].let { it.arriba to it.abajo }
            return copy(eleccion = Eleccion.ConEmoji(e), fondo = f, ultimoEmoji = e, tocado = true)
        }

        fun conFondo(n: Int): Estado {
            val f = FONDOS[n]
            val e = when (eleccion) {
                is Eleccion.Nueva, Eleccion.LaDeAntes -> ultimoEmoji?.let { Eleccion.ConEmoji(it) } ?: Eleccion.Iniciales
                else -> eleccion
            }
            return copy(eleccion = e, fondo = f.arriba to f.abajo, tocado = true)
        }

        fun comoSiempre() = copy(eleccion = Eleccion.Iniciales, fondo = null, tocado = true)

        /** Mover o acercar la imagen nueva: cuenta como tocado. */
        fun conRecorte(cambio: (Recorte) -> Recorte): Estado {
            val n = eleccion as? Eleccion.Nueva ?: return this
            return copy(eleccion = n.copy(recorte = cambio(n.recorte)), tocado = true)
        }

        /**
         * **La foto que se escribe**, sin la imagen (esa la guarda quien tiene los píxeles, y luego
         * se escribe [imagen]). Nula si no hay nada que guardar: sin tocar, o con la imagen de antes.
         */
        fun aGuardar(cuando: Long): Avatar? {
            if (!tocado) return null
            val textos = fondo?.let { (a, b) -> textoDeColor(a) to textoDeColor(b) }
            return when (val e = eleccion) {
                is Eleccion.ConEmoji -> emoji(e.emoji, textos, cuando)
                Eleccion.Iniciales -> iniciales(textos, cuando)
                is Eleccion.Nueva, Eleccion.LaDeAntes -> null
            }
        }

        companion object {
            /** Como se abre con la foto que tiene el chat. */
            fun de(a: Avatar?): Estado = Estado(
                eleccion = when {
                    a == null -> Eleccion.Iniciales
                    a.esImagen -> Eleccion.LaDeAntes
                    a.elEmoji != null -> Eleccion.ConEmoji(a.elEmoji!!)
                    else -> Eleccion.Iniciales
                },
                fondo = a?.fondo,
                ultimoEmoji = a?.elEmoji
            )
        }
    }

    /** Con lo que se pide: el proyecto con su foto nueva y dado por tocado (`tocado = ahora`), como al archivar. */
    fun conFoto(p: Proyecto, a: Avatar, ahora: Long): Proyecto = p.copy(avatar = a.aJson(), tocado = maxOf(p.tocado, ahora))

    /** La imagen nueva, relativa a `files/`. */
    fun rutaRelativaDeImagen(cuando: Long) = "$CARPETA/${nombreDeImagen(cuando)}"
}
