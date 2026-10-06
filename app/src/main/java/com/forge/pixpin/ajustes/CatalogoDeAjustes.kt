package com.forge.pixpin.ajustes

import com.forge.pixpin.data.Settings
import com.forge.pixpin.lecciones.Texto
import com.forge.pixpin.motor.HerramientasPorSitio.Sitio
import com.forge.pixpin.motor.gruposDe

/**
 * **Los ajustes como lista** (5-oct-2026, porte de «Ajustes v2» del PC, commit 8be72d8): en qué
 * sección cae cada opción, con qué palabras se encuentra, qué claves guarda y cuándo se ha
 * movido de la de fábrica.
 *
 * Es lo que hace posible, en la pantalla, el buscador («dias» encuentra «Días»), el **punto azul**
 * de lo que difiere de fábrica, el botón de volver a fábrica de cada opción y el «Restablecer
 * <sección>». La pantalla sigue pintando sus tarjetas de siempre —ninguna opción se pierde—; aquí
 * solo se dice qué es cada una.
 *
 * Volver a fábrica es **quitar sus claves**, no escribir el valor de fábrica: así lo que traiga una
 * versión nueva (una herramienta, una palabra mágica) le llega solo a quien nunca lo tocó.
 *
 * Sin Android: se prueba en `AjustesV2Test`.
 */
object CatalogoDeAjustes {

    /** Las secciones, en su orden. Los nombres son los de siempre de Android, que el usuario ya conoce. */
    enum class Seccion(val titulo: String, val resumen: String) {
        CAPTURAR("Capturar", "Cómo se recorta la pantalla y en qué formato se copia"),
        PDF("PDF", "Cuánto se comprimen al guardarlos en PixPin"),
        DIBUJAR("Dibujar", "Imán, mano, modo guía y qué herramientas salen en cada sitio"),
        PINEAR("Pinear", "Las palabras que se convierten en mini-app al pinearlas"),
        VOZ("Voz", "Con qué se pasan los audios a texto, en qué idioma, y bajar los modelos"),
        ASPECTO("Aspecto", "Modo noche, negro OLED, fluidez y la letra de los pines")
    }

    /**
     * Una opción: una tarjeta de la pantalla. [claves] son los nombres con que se guarda en el
     * almacén de ajustes; [valor] lo que se compara con el de fábrica para el punto azul.
     */
    class Opcion(
        val id: String,
        val seccion: Seccion,
        val titulo: String,
        val ayuda: String,
        val claves: List<String>,
        /** Palabras que la encuentran sin estar en el título ni en la explicación. */
        val otras: String = "",
        val valor: (Settings) -> Any?
    ) {
        internal val pajar: String = Texto.normal("$titulo $ayuda $otras ${seccion.titulo}")
    }

    val OPCIONES: List<Opcion> = listOf(
        Opcion(
            "captura", Seccion.CAPTURAR, "Modo de captura",
            "Rápido mantiene el permiso abierto (con el icono de grabar); discreto lo pide cada vez.",
            listOf("capture_mode"), "rapido discreto permiso grabacion"
        ) { it.captureMode },
        Opcion(
            "formato", Seccion.CAPTURAR, "Formato al copiar",
            "PNG lo abre todo el mundo; WebP es la misma imagen ocupando la mitad. Los dos sin pérdida.",
            listOf("copy_format"), "png webp imagen compartir guardar"
        ) { it.copyFormat },
        Opcion(
            "pdf", Seccion.PDF, "Compresión de los PDF",
            "Al guardar un PDF en PixPin se aligera solo, en segundo plano, con pdfsqueeze.",
            listOf("compresion_pdf"), "comprimir aligerar pdfsqueeze nivel"
        ) { it.compresionPdf },
        Opcion(
            "guia", Seccion.DIBUJAR, "Modo guía",
            "El lápiz azul de las líneas de referencia, en la barra de cada sitio donde se dibuja.",
            listOf("guia_editor", "guia_pin", "guia_capa", "guia_captura"), "referencia lineas azul"
        ) { listOf(it.guiaEnEditor, it.guiaEnPin, it.guiaEnCapa, it.guiaEnCaptura) },
        Opcion(
            "iman", Seccion.DIBUJAR, "Imán",
            "A qué se pega el dedo al dibujar: esquinas, medios, centros, cruces, ejes y bordes.",
            listOf(
                "iman_activo", "iman_esquinas", "iman_medios", "iman_centros", "iman_intersecciones",
                "iman_eje", "iman_borde_guia", "iman_borde_figura"
            ),
            "enganche snap pegar esquinas intersecciones"
        ) {
            listOf(
                it.imanActivo, it.imanEsquinas, it.imanMedios, it.imanCentros, it.imanIntersecciones,
                it.imanEje, it.imanBordeDeGuia, it.imanBordeDeFigura
            )
        },
        Opcion(
            "mano", Seccion.DIBUJAR, "Mano con la que dibujas",
            "Los paneles y la lupa se van al lado contrario a la mano, para que el brazo no los tape.",
            listOf("zurdo"), "zurdo diestro izquierda derecha"
        ) { it.zurdo },
        Opcion(
            "herramientas", Seccion.DIBUJAR, "Herramientas en todos los sitios",
            "Una apagada aquí no sale en ninguna barra: ni en el lienzo, ni en el pin, ni en la pantalla, ni en el lector.",
            listOf("herramientas_apagadas"), "apagar encender lista general barra todas"
        ) { it.reparto.apagadas },
        opcionDeBarra(
            Sitio.LIENZO, "Barra del lienzo",
            "La del editor a pantalla completa. De fábrica están todas: quita lo que no uses y agrupa el resto.",
            "editor", "editor avanzado pantalla completa"
        ),
        opcionDeBarra(
            Sitio.PIN, "Barra del pin",
            "Lo que se queda en la barra flotante del pin. Cada fila es un grupo.",
            "pin", "captura flotante"
        ),
        opcionDeBarra(
            Sitio.PANTALLA, "Barra de la pantalla",
            "Las de la capa que se dibuja encima de otras apps.",
            "capa", "capa encima otras apps"
        ),
        opcionDeBarra(
            Sitio.LECTOR, "Barra del lector",
            "Las del editor rápido: anotar un PDF, un Word o un libro sin salir de él.",
            "lector", "editor rapido pdf word epub libro anotar"
        ),
        Opcion(
            "palabras", Seccion.PINEAR, "Palabras mágicas",
            "Qué palabra abre qué mini-app al pinear un texto: temporizador, lista, ruleta…",
            listOf("palabras_magicas"), "miniapp temporizador cronometro lista contador ruleta"
        ) { it.palabras },
        Opcion(
            "voz", Seccion.VOZ, "Motor de voz",
            "Con qué se pasan los audios a texto (Google, Vosk o Whisper), en qué idioma y el segundo idioma.",
            listOf("motor_de_voz", "idioma_de_voz", "segundo_idioma_de_voz", "modelo_whisper", "modo_de_idiomas"),
            "transcribir audio whisper vosk google idioma modelo"
        ) { listOf(it.motorDeVoz, it.idiomaDeVoz, it.segundoIdiomaDeVoz, it.modeloWhisper, it.modoDeIdiomas) },
        Opcion(
            "fluidez", Seccion.ASPECTO, "Máxima fluidez",
            "Pide a la pantalla su tasa más alta (90 o 120 Hz). Más suave, algo más de batería.",
            listOf("maxima_fluidez"), "hz hercios rendimiento bateria"
        ) { it.maximaFluidez },
        Opcion(
            "noche", Seccion.ASPECTO, "Aspecto",
            "Cosmos, el del sistema, claro, oscuro o automático por la hora y la luz.",
            listOf("modo_noche"), "modo noche tema oscuro claro cosmos"
        ) { it.modoNoche },
        Opcion(
            "oled", Seccion.ASPECTO, "Negro OLED",
            "Negro puro en modo noche: en un OLED apaga el píxel y gasta menos.",
            listOf("oled_negro"), "negro oscuro pantalla bateria"
        ) { it.oledNegro },
        Opcion(
            "letra", Seccion.ASPECTO, "Letra del pin",
            "Con qué letra se escribe en la edición simple.",
            listOf("pin_font"), "fuente tipografia texto"
        ) { it.pinFont }
    )

    private fun opcionDeBarra(sitio: Sitio, titulo: String, ayuda: String, clave: String, otras: String) = Opcion(
        "barra-${sitio.nombre}", Seccion.DIBUJAR, titulo, ayuda,
        listOf("${clave}_tools", "${clave}_groups"), "herramientas barra grupos $otras"
    ) { s ->
        val grupos = when (sitio) {
            Sitio.PANTALLA -> s.capaGroups
            Sitio.PIN -> s.pinGroups
            Sitio.LIENZO -> s.editorGroups
            Sitio.LECTOR -> s.lectorGroups
        }
        // Lo que lleva la barra, sin restar la lista general: apagar una en todos no es tocar esta.
        gruposDe(grupos, s.sitioGuardado(sitio))
    }

    /** Todas las claves de la pantalla: las que entran en el «Deshacer». */
    val CLAVES: Set<String> = OPCIONES.flatMapTo(LinkedHashSet()) { it.claves }

    private val DE_FABRICA = Settings()

    fun deSeccion(s: Seccion): List<Opcion> = OPCIONES.filter { it.seccion == s }

    fun porId(id: String): Opcion? = OPCIONES.firstOrNull { it.id == id }

    /** **El punto azul**: si la opción vale algo distinto de lo de fábrica. */
    fun cambiada(o: Opcion, ajustes: Settings): Boolean = o.valor(ajustes) != o.valor(DE_FABRICA)

    /** Las claves que hay que quitar para «Restablecer <sección>»: las de lo que se movió. */
    fun clavesARestablecer(s: Seccion, ajustes: Settings): List<String> =
        deSeccion(s).filter { cambiada(it, ajustes) }.flatMap { it.claves }

    /**
     * Lo que sale al buscar: **todas** las palabras tienen que estar (así «barra pin» afina en vez
     * de ensanchar), sin tildes ni mayúsculas, en el título, la explicación, sus otras palabras o
     * el nombre de su sección. Agrupado por sección, en su orden. Vacía no devuelve nada.
     */
    fun buscar(consulta: String): List<Pair<Seccion, List<Opcion>>> {
        val palabras = Texto.normal(consulta).split(' ', ',', '.').filter { it.isNotBlank() }
        if (palabras.isEmpty()) return emptyList()
        return OPCIONES.filter { o -> palabras.all { it in o.pajar } }
            .groupBy { it.seccion }
            .toSortedMap(compareBy { it.ordinal })
            .map { (s, l) -> s to l }
    }
}

/**
 * **El «Deshacer» de los ajustes**, como en el PC (`Copia` de `ventana_ajustes.rs`): cómo estaban
 * las claves de la pantalla antes de cada cambio, hasta [tope] pasos.
 *
 * No se mete en cada interruptor: mira lo guardado. Cada vez que el almacén cambia, lo de antes se
 * apunta —si de verdad cambió algo de [claves]; lo que no cambia no cuenta—. Así cualquier tarjeta,
 * la de hoy y la que se añada mañana, se deshace sin tocarla. Lo que vuelve al deshacer no se
 * apunta como un cambio nuevo.
 */
class HistorialDeAjustes(private val claves: Set<String>, private val tope: Int = 100) {
    private val pasos = ArrayDeque<Map<String, Any>>()
    private var ultimo: Map<String, Any>? = null
    private var esperando: Map<String, Any>? = null

    val hayAlgo: Boolean get() = pasos.isNotEmpty()

    /** Lo guardado ahora (todo el almacén; aquí se queda con lo suyo). */
    fun observar(todo: Map<String, Any>) {
        val ahora = todo.filterKeys { it in claves }
        val antes = ultimo
        ultimo = ahora
        if (antes == null || antes == ahora) return
        if (esperando != null) {
            if (ahora == esperando) { esperando = null; return }
            esperando = null
        }
        pasos.addLast(antes)
        while (pasos.size > tope) pasos.removeFirst()
    }

    /** Cómo estaba antes del último cambio, para restaurarlo; null si no hay nada que deshacer. */
    fun deshacer(): Map<String, Any>? {
        val v = pasos.removeLastOrNull() ?: return null
        esperando = v
        return v
    }
}
