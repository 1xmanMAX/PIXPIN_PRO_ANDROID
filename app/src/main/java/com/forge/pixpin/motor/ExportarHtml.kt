package com.forge.pixpin.motor

/**
 * **El documento web: lo dibujado en un solo archivo que se abre en cualquier navegador.**
 *
 * Un `.html` que se manda por donde sea y se abre sin instalar nada y sin conexión. Dentro
 * van todas las páginas —dibujos planos como SVG y croquis del espacio como geometría— con
 * su visor escrito en la propia página. La idea viene de analizar el `cad-html-plugin` de
 * mlightcad/cad-viewer, que empaqueta un plano en un HTML de solo mirar; ellos incrustan un
 * motor 3D entero (del orden de un mega por archivo), aquí el visor son unos kilobytes
 * porque lo que hay que enseñar son rayas, hojas y bolas.
 *
 * ## Un documento, muchas páginas
 *
 * Una hoja de un dibujo con marcos, un croquis del espacio, una nota, una hoja de un
 * proyecto: todas caben en el mismo archivo y se pasa de una a otra con el menú o con las
 * flechas. **La que no se mira lleva `hidden`**, así que el navegador no la pinta y tener
 * veinte no cuesta nada mientras no se abran. Cada una lleva lo suyo: su encuadre, su pila
 * de deshacer, sus anotaciones.
 *
 * ## La misma interfaz para las dos clases de página
 *
 * Una sola barra abajo. Los mandos que no valen para la página que se mira se esconden: con
 * un dibujo salen lápiz, resaltador y borrador; con un croquis del espacio, girar, mover y
 * medir, más los cajones de vistas y de grupos. Lo de encajar, guardar y compartir vale para
 * las dos. Ver [VisorEspacio] para el visor del espacio.
 *
 * ## Lápiz y dedo
 *
 * En un dibujo, **en cuanto se posa un lápiz la página se entera**: desde ese momento el
 * lápiz traza y el dedo mueve el papel, sin tener que cambiar de modo, igual que en la
 * aplicación. Y el otro extremo del lápiz —el que muchos declaran como goma— borra.
 */
object ExportarHtml {

    const val MIME_TYPE = "text/html"

    /** El grupo del SVG donde viven las anotaciones. El visor lo busca por este id. */
    const val ID_DEL_CROQUIS = "croquis"

    /**
     * **Una página del documento.** O un dibujo plano —un SVG, que se puede anotar— o un
     * croquis del espacio —la geometría en JSON, que se gira y se mide—.
     *
     * [fondo] es de qué color es su papel: la barra toma el de la primera y el fondo de la
     * ventana cambia al de la página que se mire.
     */
    sealed class HojaWeb(val nombre: String, val fondo: String) {
        /**
         * Un dibujo plano. [plano] es, si lo hay, **el papel del PDF como geometría** —lo que
         * saca [PlanoWeb]— en vez de como fotografía: se pinta en un lienzo debajo del SVG, se
         * ve nítido a cualquier aumento y trae las capas de AutoCAD para encender y apagar.
         * Ver [VisorPlano].
         */
        class Dibujo(
            nombre: String,
            val svg: String,
            fondo: String,
            val plano: String? = null,
            /**
             * La escala del dibujo, si se calibró: es lo que deja **medir** en el documento y
             * que la medida salga en metros y no en píxeles. Ver [Escala].
             */
            val escala: Escala? = null
        ) : HojaWeb(nombre, fondo)
        /** [datos] es lo que escribe `croquis3d/ExportarCroquisHtml`. */
        class Espacio(nombre: String, val datos: String, fondo: String) : HojaWeb(nombre, fondo)
        /**
         * **Una nota**, ya como HTML —lo escribe `motormd/MarkdownHtml`—. No se pinta: la
         * compone el navegador, que es quien sabe repartir el texto en el ancho que haya y
         * dejar buscar dentro. Por eso una nota exportada se lee bien en un teléfono y una
         * nota dibujada, no.
         */
        class Nota(nombre: String, val html: String, fondo: String) : HojaWeb(nombre, fondo)
    }

    /**
     * **Qué lleva el documento exportado.** Lo pidió el usuario (5-sep-2026): poder limitar
     * lo que se manda —una lámina para enseñar no necesita lápiz ni guardar—. Lo que se apaga
     * no va: ni su botón, ni su atajo de teclado, ni su código si no lo usa nadie.
     */
    /** Con qué calidad viaja el audio; va en el mismo conjunto de funciones como `audio:<calidad>`. */
    const val PREFIJO_DE_AUDIO = "audio:"
    fun calidadDeAudio(marcadas: Set<String>?): String =
        marcadas?.firstOrNull { it.startsWith(PREFIJO_DE_AUDIO) }?.removePrefix(PREFIJO_DE_AUDIO) ?: AudioLigero.LIGERO
    fun conCalidadDeAudio(marcadas: Set<String>, calidad: String): Set<String> =
        marcadas.filterNot { it.startsWith(PREFIJO_DE_AUDIO) }.toSet() + (PREFIJO_DE_AUDIO + calidad)

    class Opciones(
        val lapiz: Boolean = true,
        val resaltador: Boolean = true,
        val borrador: Boolean = true,
        val deshacer: Boolean = true,
        val medir: Boolean = true,
        val capas: Boolean = true,
        val paginas: Boolean = true,
        val guardar: Boolean = true,
        val compartir: Boolean = true
    ) {
        /** Las herramientas que se dejan coger, por su nombre en el visor. */
        fun herramientas(): List<String> = buildList {
            add("mano")
            if (lapiz) add("lapiz")
            if (resaltador) add("marcador")
            if (borrador) add("goma")
            if (medir) add("medir")
            add("girar"); add("mover")
        }

        companion object {
            /** Los nombres con los que se guardan en los ajustes. Ver `Settings.funcionesWeb`. */
            val NOMBRES = listOf(
                "lapiz", "resaltador", "borrador", "deshacer", "medir", "capas", "paginas",
                "guardar", "compartir"
            )

            /** Las opciones a partir de lo marcado en los ajustes; null es «todo». */
            fun de(marcadas: Set<String>?): Opciones {
                if (marcadas == null) return Opciones()
                return Opciones(
                    lapiz = "lapiz" in marcadas, resaltador = "resaltador" in marcadas,
                    borrador = "borrador" in marcadas, deshacer = "deshacer" in marcadas,
                    medir = "medir" in marcadas, capas = "capas" in marcadas,
                    paginas = "paginas" in marcadas, guardar = "guardar" in marcadas,
                    compartir = "compartir" in marcadas
                )
            }
        }
    }

    /** Un documento de una sola página, que es el caso corriente. */
    fun pagina(
        svg: String,
        titulo: String,
        fondo: String,
        nombre: String = titulo,
        plano: String? = null
    ): String = paginas(listOf(HojaWeb.Dibujo("", svg, fondo, plano)), titulo, nombre)

    /**
     * **El documento entero.** [titulo] es el de la ventana; [nombre], cómo se llamará el
     * archivo al guardarlo desde el navegador.
     */
    fun paginas(
        hojas: List<HojaWeb>,
        titulo: String,
        nombre: String = titulo,
        opciones: Opciones = Opciones()
    ): String {
        require(hojas.isNotEmpty()) { "un documento sin hojas no es un documento" }
        val fondo = hojas.first().fondo
        val oscuro = esOscuro(fondo)
        val hayEspacio = hojas.any { it is HojaWeb.Espacio }
        val hayDibujo = hojas.any { it is HojaWeb.Dibujo }
        val hayPlano = hojas.any { it is HojaWeb.Dibujo && it.plano != null }
        // **Una nota también se raya**, así que lleva los mismos mandos aunque no haya
        // ningún dibujo en el documento. Lo que no lleva es el visor del SVG: eso sí es de
        // los dibujos. Ver [crearNota].
        val hayPintable = hayDibujo || hojas.any { it is HojaWeb.Nota }
        val tamano = hojas.sumOf {
            when (it) {
                is HojaWeb.Dibujo -> it.svg.length + (it.plano?.length ?: 0)
                is HojaWeb.Espacio -> it.datos.length
                is HojaWeb.Nota -> it.html.length
            }
        }
        return buildString(tamano + 60000) {
            append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n")
            append("<meta charset=\"utf-8\"/>\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\"/>\n")
            append("<meta name=\"color-scheme\" content=\"").append(if (oscuro) "dark" else "light").append("\"/>\n")
            append("<meta name=\"generator\" content=\"PixPin\"/>\n")
            append("<title>").append(escapar(titulo)).append("</title>\n")
            append("<style>").append(ESTILO.replace("FONDO", fondo)).append("</style>\n</head>\n")
            // **El tema va con la página.** Un documento puede llevar una lámina sobre papel
            // blanco y un croquis sobre pizarra; con un solo juego de colores para todo, en
            // una de las dos la letra queda encima de su propio color. La clase la pone el
            // armazón al cambiar de hoja.
            append("<body data-nombre=\"").append(escapar(nombre)).append('"')
            // Lo que se deja coger: el visor filtra sus herramientas por esto, para que lo que
            // no tiene botón tampoco entre por el teclado.
            append(" data-herramientas=\"").append(opciones.herramientas().joinToString(" ")).append('"')
            if (oscuro) append(" class=\"oscuro\"")
            append(">\n")
            append("<div id=\"lienzo\">\n")
            for ((i, hoja) in hojas.withIndex()) {
                append("<div class=\"hoja\" data-tipo=\"")
                append(
                    when (hoja) {
                        is HojaWeb.Espacio -> "espacio"
                        is HojaWeb.Nota -> "nota"
                        is HojaWeb.Dibujo -> "dibujo"
                    }
                )
                append("\" data-fondo=\"").append(escapar(hoja.fondo))
                append("\" data-oscuro=\"").append(if (esOscuro(hoja.fondo)) "1" else "0")
                append("\" data-nombre=\"")
                append(escapar(hoja.nombre.ifBlank { "Hoja ${i + 1}" })).append('"')
                // El armazón lo mira para enseñar u ocultar el cajón de capas. Ver [VisorPlano].
                if (hoja is HojaWeb.Dibujo && hoja.plano != null) append(" data-plano=\"1\"")
                if (hoja is HojaWeb.Dibujo) hoja.escala?.takeIf { it.valida }?.let { e ->
                    append(" data-escala=\"").append(e.unidadesPorPixel).append('"')
                    append(" data-unidad=\"").append(escapar(e.unidad)).append('"')
                    append(" data-decimales=\"").append(e.decimales).append('"')
                }
                if (i > 0) append(" hidden")
                append(">\n")
                when (hoja) {
                    is HojaWeb.Dibujo -> {
                        // El plano va **debajo** del SVG: el papel es geometría y lo anotado
                        // sigue siendo SVG. Ver [VisorPlano].
                        hoja.plano?.let {
                            append("<canvas class=\"plano\"></canvas>\n")
                            append("<script type=\"application/json\" class=\"plano\">")
                            append(it.replace("</", "<\\/"))
                            append("</script>\n")
                        }
                        append(conGrupoDeCroquis(hoja.svg))
                    }
                    // El JSON en un `script` de tipo desconocido: el navegador no lo ejecuta
                    // y no hay que escapar comillas ni acentos, solo la etiqueta de cierre.
                    is HojaWeb.Espacio ->
                        append("<script type=\"application/json\" class=\"datos\">")
                            .append(hoja.datos.replace("</", "<\\/"))
                            .append("</script>")
                    is HojaWeb.Nota ->
                        append("<article class=\"nota\">").append(hoja.html).append("</article>")
                            .append("\n<svg class=\"tinta\"><g id=\"")
                            .append(ID_DEL_CROQUIS).append("\"></g></svg>")
                }
                append("\n</div>\n")
            }
            append("</div>\n")
            append(barra(hojas.size > 1 && opciones.paginas, hayPintable, hayEspacio, hayPlano && opciones.capas, opciones))
            append("<script>")
            if (hayEspacio) append(VisorEspacio.JS)
            if (hayPlano) { append(VisorPlano.INFLAR); append(VisorPlano.JS) }
            if (hayDibujo) append(VISOR_DIBUJO)
            append(SHELL)
            append("</script>\n</body>\n</html>\n")
        }
    }

    /**
     * El grupo de las anotaciones, dentro del SVG y el último de todo: así las notas van
     * encima del dibujo y pasean y se amplían con él. Va ya en el archivo, vacío, para que
     * guardar sea rellenarlo.
     */
    private fun conGrupoDeCroquis(svg: String): String {
        val cuerpo = svg.trim().removePrefix("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").trim()
        if (!cuerpo.endsWith("</svg>") || cuerpo.contains("id=\"$ID_DEL_CROQUIS\"")) return cuerpo
        return cuerpo.removeSuffix("</svg>").trimEnd() + "\n<g id=\"$ID_DEL_CROQUIS\"></g>\n</svg>"
    }

    /**
     * Los colores del croquis: **vivos y chillones a propósito**. Una anotación rápida tiene
     * que verse a la primera encima de un plano o de una página de texto, y para eso hacen
     * falta colores que no existan en el dibujo de debajo. El primero es el que sale puesto.
     */
    val COLORES = listOf(
        "#ff1744", "#ff9100", "#ffea00", "#00e676",
        "#00e5ff", "#2979ff", "#d500f9", "#ffffff", "#111111"
    )

    /** Los grosores del lápiz, en píxeles de pantalla. El resaltador va al triple. */
    val GROSORES = listOf(2, 4, 9)

    private fun escapar(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Si un color `#rrggbb` es más bien oscuro. Con lo que no se entienda, claro. */
    private fun esOscuro(color: String): Boolean {
        val hex = color.trim().removePrefix("#")
        if (hex.length != 6) return false
        val n = hex.toIntOrNull(16) ?: return false
        return (0.299 * ((n shr 16) and 0xff) + 0.587 * ((n shr 8) and 0xff) + 0.114 * (n and 0xff)) < 128
    }

    /** Un icono de la barra: un camino de 24×24, trazado. */
    private fun icono(camino: String): String =
        "<svg viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" fill=\"none\" stroke=\"currentColor\" " +
            "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
            "<path d=\"$camino\"/></svg>"

    private fun boton(id: String, titulo: String, camino: String, clase: String = ""): String =
        "<button id=\"$id\" title=\"$titulo\" aria-label=\"$titulo\"" +
            (if (clase.isEmpty()) "" else " class=\"$clase\"") + ">" + icono(camino) + "</button>"

    /**
     * **Una sola barra, abajo y centrada**, que es donde llega el pulgar en un teléfono y
     * donde no tapa nada en un portátil. Los iconos van dibujados dentro —caminos de 24
     * píxeles— y no como emoji, para que se vean iguales en cualquier aparato.
     *
     * Los grupos que no valen para la clase de página que se mira no se escriben siquiera si
     * el documento no tiene ninguna de esa clase, y se esconden con `hidden` cuando la
     * página de al lado sí lo es.
     */
    private fun barra(
        varias: Boolean,
        dibujo: Boolean,
        espacio: Boolean,
        plano: Boolean = false,
        o: Opciones = Opciones()
    ): String = buildString {
        append("<div id=\"estado\" role=\"status\" aria-live=\"polite\"></div>\n")
        append("<div id=\"cajon\" hidden></div>\n")
        append("<div id=\"pizarra\"")
        if (varias) append(" class=\"varias\"")
        append(">\n")
        if (dibujo && (o.lapiz || o.resaltador)) {
            // La paleta, encima de la barra y solo con un lápiz en la mano.
            append("<div id=\"paleta\" hidden>")
            append("<div id=\"colores\" role=\"group\" aria-label=\"Color\">")
            for ((i, c) in COLORES.withIndex()) {
                append("<button class=\"color")
                if (i == 0) append(" activo")
                append("\" data-color=\"").append(c).append("\" style=\"--c:")
                append(c).append("\" title=\"Color\" aria-label=\"Color ").append(c).append("\"></button>")
            }
            append("</div>")
            append("<div id=\"grosores\" role=\"group\" aria-label=\"Grosor\">")
            for ((i, g) in GROSORES.withIndex()) {
                append("<button class=\"grosor")
                if (i == 1) append(" activo")
                append("\" data-grosor=\"").append(g).append("\" title=\"Grosor\" aria-label=\"Grosor $g\">")
                append("<i style=\"width:").append(5 + i * 5).append("px;height:")
                append(5 + i * 5).append("px\"></i></button>")
            }
            append("</div>")
            append("</div>\n")
        }
        append("<div id=\"barra\" role=\"toolbar\">")
        if (varias) {
            append("<div class=\"grupo\">")
            append(boton("anterior", "Página anterior", "M15 5l-7 7 7 7"))
            append(boton("indice", "Páginas", "M4 6h16M4 12h16M4 18h10"))
            append(boton("siguiente", "Página siguiente", "M9 5l7 7-7 7"))
            append("</div>")
        }
        if (dibujo && (o.lapiz || o.resaltador || o.borrador)) {
            append("<div class=\"grupo solo-dibujo\">")
            append(boton("mano", "Mover (V)", "M8 13V5a2 2 0 1 1 4 0v6M12 11V4a2 2 0 1 1 4 0v7M16 12V6a2 2 0 1 1 4 0v8a7 7 0 0 1-7 7h-1a7 7 0 0 1-6-3.4L3.5 13a2 2 0 0 1 3.4-2.1L8 13", "activo"))
            if (o.lapiz) append(boton("lapiz", "Lápiz (P)", "M4 20l4-1L19.5 7.5a2.1 2.1 0 0 0-3-3L5 16l-1 4zM14 6l4 4"))
            if (o.resaltador) append(boton("marcador", "Resaltador (M)", "M4 20h16M6 16l8.5-8.5a2.1 2.1 0 0 1 3 3L9 19H6v-3zM13 8l3 3"))
            if (o.borrador) append(boton("goma", "Borrador (E)", "M20 20H8M4.6 14.4l8.8-8.8a2 2 0 0 1 2.8 0l3.2 3.2a2 2 0 0 1 0 2.8L13 18H8.6l-4-4a1 1 0 0 1 0-1.4zM9.5 9.5l5 5"))
            append("</div>")
        }
        if (espacio) {
            append("<div class=\"grupo solo-espacio\">")
            append(boton("girar", "Girar (G)", "M12 3.5a8.5 8.5 0 1 1-8.5 8.5M12 3.5 8.5 6.6M12 3.5 8.6 1", "activo"))
            append(boton("mover", "Mover (V)", "M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3"))
            append("</div>")
        }
        if (o.medir && (dibujo || espacio)) {
            // **Medir vale para las dos clases de página.** En el dibujo, dos toques dan la
            // distancia en la unidad de la escala del dibujo, si se calibró; en píxeles si no.
            append("<div class=\"grupo solo-medir\">")
            append(boton("medir", "Medir (D)", "M3 14.5 14.5 3 21 9.5 9.5 21zM7 11l2 2M10 8l2 2M13 5l2 2"))
            append("</div>")
        }
        if (dibujo && o.deshacer && (o.lapiz || o.resaltador || o.borrador)) {
            append("<div class=\"grupo solo-dibujo\">")
            append(boton("deshacer", "Deshacer (Ctrl+Z)", "M9 14L4 9l5-5M4 9h9a6 6 0 0 1 0 12h-3"))
            append(boton("rehacer", "Rehacer (Ctrl+Y)", "M15 14l5-5-5-5M20 9h-9a6 6 0 0 0 0 12h3"))
            append("</div>")
        }
        append("<div class=\"grupo\">")
        append(boton("menos", "Alejar (−)", "M5 12h14", "solo-raton"))
        append(boton("encajar", "Encajar (0)", "M4 9V5a1 1 0 0 1 1-1h4M15 4h4a1 1 0 0 1 1 1v4M20 15v4a1 1 0 0 1-1 1h-4M9 20H5a1 1 0 0 1-1-1v-4"))
        append(boton("mas", "Acercar (+)", "M12 5v14M5 12h14", "solo-raton"))
        append("</div>")
        if (espacio) {
            append("<div class=\"grupo solo-espacio\">")
            append(boton("vistas", "Vistas", "M3 5h18v11H3zM8 20h8"))
            append(boton("grupos", "Grupos", "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"))
            append("</div>")
        }
        if (plano) {
            // Las capas del plano: las de AutoCAD, tal cual venían en el PDF.
            append("<div class=\"grupo solo-plano\">")
            append(boton("capas", "Capas del plano", "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5M3 17l9 5 9-5"))
            append("</div>")
        }
        if (o.guardar || o.compartir) {
            append("<div class=\"grupo\">")
            if (o.guardar) append(boton("guardar", "Guardar (Ctrl+S)", "M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6"))
            if (o.compartir) append(boton("compartir", "Compartir", "M12 3v12M8 7l4-4 4 4M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7"))
            append("</div>")
        }
        append("</div>\n")
        append("<div id=\"aviso\" role=\"status\" hidden></div>\n")
        append("</div>\n")
    }

    private val ESTILO = """
        :root{--tinta:#1c1c1e;--vidrio:rgba(255,255,255,.86);--filete:rgba(0,0,0,.1);
          --fusion:multiply}
        body.oscuro{--tinta:#f2f2f2;--vidrio:rgba(30,30,32,.82);--filete:rgba(255,255,255,.12);
          --fusion:screen}
        html,body{margin:0;height:100%;overflow:hidden;background:FONDO;color:var(--tinta);
          font:14px/1.3 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
          -webkit-tap-highlight-color:transparent;overscroll-behavior:none}
        #lienzo{position:fixed;inset:0}
        .hoja{position:absolute;inset:0}
        .hoja[hidden]{display:none}
        #lienzo svg{width:100%;height:100%;display:block;touch-action:none;cursor:grab;
          user-select:none;-webkit-user-select:none}
        .hoja[data-tipo=nota]{overflow:auto;-webkit-overflow-scrolling:touch;position:absolute}
        /* La capa donde se raya, encima del texto y del alto que tenga la nota. No recibe el
           dedo salvo con lápiz o resaltador en la mano: así el texto se sigue pudiendo
           seleccionar y los enlaces se siguen pudiendo tocar. */
        .tinta{position:absolute;left:0;top:0;width:100%;pointer-events:none;overflow:visible}
        #lienzo.pintando .tinta{pointer-events:auto;touch-action:none;cursor:crosshair}
        .nota{max-width:44rem;margin:0 auto;padding:28px 20px 140px;line-height:1.65;
          font-size:16px;word-wrap:break-word}
        .nota h1,.nota h2,.nota h3,.nota h4,.nota h5,.nota h6{line-height:1.25;margin:1.4em 0 .5em}
        .nota h1{font-size:1.7em}.nota h2{font-size:1.4em}.nota h3{font-size:1.2em}
        .nota p{margin:.7em 0}
        .nota blockquote{margin:.8em 0;padding:.2em 0 .2em 1em;border-left:3px solid var(--filete);opacity:.86}
        .nota pre{background:var(--filete);padding:12px 14px;border-radius:10px;overflow:auto}
        .nota code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.92em}
        .nota pre code{font-size:.88em}
        .nota hr{border:none;border-top:1px solid var(--filete);margin:1.6em 0}
        .nota table{border-collapse:collapse;margin:1em 0;width:100%}
        .nota th,.nota td{border:1px solid var(--filete);padding:6px 10px;text-align:left;
          min-width:3em;white-space:pre-wrap;vertical-align:top}
        /* Una celda vacía sigue siendo una celda: sin esto se aplasta y la tabla sale
           «comprimida», que fue lo que reportó el usuario. */
        .nota td:empty::after,.nota th:empty::after{content:"\00a0"}
        .nota figure{margin:1em 0;text-align:center}
        .nota figure img{max-width:100%;height:auto;border-radius:8px}
        .nota figure audio{width:100%;max-width:520px}
        .nota .salto{color:#1e88e5;font-weight:600;text-decoration:none;cursor:pointer;margin-right:.4em}
        .nota .adjunto{display:inline-block;padding:8px 12px;border:1px solid #1e88e5;border-radius:10px;color:#1e88e5;text-decoration:none}
        .nota figcaption{opacity:.7;font-size:.9em;margin-top:.4em}
        .nota caption{caption-side:top;opacity:.7;padding-bottom:6px;font-size:.9em}
        .nota ul.tareas{list-style:none;padding-left:1.1em}
        .nota .medio{opacity:.6;font-style:italic}
        .nota .destacado{border:1px solid var(--filete);border-radius:12px;padding:10px 14px;margin:1em 0}
        .nota .centro{text-align:center}.nota .derecha{text-align:right}
        .nota .pie{opacity:.7;font-size:.9em}
        .nota .tapado{background:currentColor;border-radius:3px}
        .nota .tapado:hover,.nota .tapado:focus{background:transparent}
        .nota a{color:inherit}
        canvas.espacio{position:absolute;inset:0;display:block;touch-action:none;cursor:grab}
        /* El plano vectorial va debajo del SVG y no recibe el dedo: el que manda sigue siendo
           el SVG, que es donde se raya. Sin el `z-index` el lienzo, que va posicionado, se
           pintaría por encima del SVG, que no lo va. */
        /* El lienzo del plano es **más grande que la ventana** y sobresale por los cuatro
           lados: así, al mover, lo que asoma ya está pintado. Su sitio y su tamaño los pone
           el visor. Ver [VisorPlano]. */
        canvas.plano{position:absolute;left:0;top:0;display:block;pointer-events:none;z-index:0}
        .hoja{overflow:hidden}
        .hoja[data-plano] svg{position:relative;z-index:1}
        #lienzo.agarrado svg,#lienzo.agarrado canvas.espacio{cursor:grabbing}
        #lienzo.pintando svg{cursor:crosshair}
        #lienzo.midiendo canvas.espacio,#lienzo.midiendo svg{cursor:crosshair}
        /* El resaltador se funde multiplicando, que sobre papel claro es lo que hace un
           rotulador: el texto sigue negro y el fondo se tiñe. Sobre pizarra multiplicar lo
           apaga todo, y ahí lo que tiñe es sumar luz. Va con el tema de la página. */
        .marca{mix-blend-mode:var(--fusion)}
        #pizarra{position:fixed;left:0;right:0;bottom:0;display:flex;flex-direction:column;
          align-items:center;gap:10px;padding:0 10px calc(12px + env(safe-area-inset-bottom));
          pointer-events:none;z-index:5}
        #pizarra>*{pointer-events:auto}
        #barra{display:flex;gap:6px;padding:6px;border-radius:16px;background:var(--vidrio);
          border:1px solid var(--filete);box-shadow:0 6px 24px rgba(0,0,0,.18);
          backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);
          flex-wrap:wrap;justify-content:center;max-width:100%}
        .grupo{display:flex;gap:2px}
        .grupo[hidden]{display:none}
        #medida line{stroke:#ff8a3d;stroke-width:2px;vector-effect:non-scaling-stroke}
        #medida circle{fill:#ff8a3d}
        .grupo+.grupo{border-left:1px solid var(--filete);padding-left:6px;margin-left:2px}
        #barra button{width:40px;height:40px;border:none;border-radius:11px;background:transparent;
          color:inherit;display:inline-flex;align-items:center;justify-content:center;
          cursor:pointer;touch-action:manipulation;padding:0}
        #barra button:hover{background:var(--filete)}
        #barra button:active{transform:scale(.93)}
        #barra button.activo{background:#e03131;color:#fff}
        #barra button:disabled{opacity:.35;cursor:default}
        /* Los botones del zoom sobran con rueda, pero en el espacio y con el dedo son la
           única forma de acercarse sin usar las dos manos. */
        @media (pointer:coarse){
          .solo-raton{display:none}
          body.enElEspacio #mas,body.enElEspacio #menos{display:inline-flex}
        }
        #paleta{display:flex;flex-direction:column;gap:6px;align-items:center}
        /* Sin esto la paleta se ve siempre: `#paleta{display:flex}` pesa más que el
           `[hidden]{display:none}` del navegador y lo tapa. */
        #paleta[hidden]{display:none}
        #colores,#grosores{display:flex;gap:6px;padding:6px;border-radius:14px;
          background:var(--vidrio);border:1px solid var(--filete);box-shadow:0 6px 24px rgba(0,0,0,.18);
          backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);flex-wrap:wrap;
          justify-content:center;max-width:100%}
        #paleta button{width:30px;height:30px;border:2px solid rgba(255,255,255,.75);
          border-radius:50%;padding:0;cursor:pointer;box-shadow:0 0 0 1px rgba(0,0,0,.3);
          touch-action:manipulation;background:var(--c,#fff)}
        #paleta button.activo{border-color:#fff;box-shadow:0 0 0 3px #e03131}
        #grosores button{background:#fff;display:inline-flex;align-items:center;justify-content:center}
        #grosores button i{display:block;border-radius:50%;background:#222}
        #aviso{position:fixed;left:50%;top:18px;transform:translateX(-50%);padding:8px 14px;
          border-radius:10px;background:var(--vidrio);border:1px solid var(--filete);
          box-shadow:0 6px 24px rgba(0,0,0,.18);
          backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
        #estado{position:fixed;left:50%;transform:translateX(-50%);
          bottom:calc(74px + env(safe-area-inset-bottom));padding:6px 12px;border-radius:999px;
          background:var(--vidrio);border:1px solid var(--filete);font-variant-numeric:tabular-nums;z-index:4;
          white-space:nowrap;max-width:92vw;overflow:hidden;text-overflow:ellipsis;
          backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
        #estado:empty{display:none}
        #cajon{position:fixed;top:12px;right:12px;width:252px;max-height:70vh;overflow:auto;
          padding:8px;border-radius:16px;background:var(--vidrio);border:1px solid var(--filete);z-index:6;
          box-shadow:0 6px 24px rgba(0,0,0,.18);
          backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
        #cajon[hidden]{display:none}
        #cajon h2{margin:2px 6px 8px;font-size:11px;letter-spacing:.09em;
          text-transform:uppercase;opacity:.6}
        .fila{display:flex;align-items:center;gap:8px;padding:7px 8px;border-radius:10px;cursor:pointer}
        .fila:hover{background:var(--filete)}
        .fila.activo{background:#e03131;color:#fff}
        .fila input{accent-color:#e03131;width:16px;height:16px;flex:none}
        .fila .n{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .fila .lupa{opacity:.55;flex:none;padding:0 4px}
        .mini{display:flex;gap:6px;padding:6px 6px 2px}
        .mini button{flex:1;padding:7px 10px;border-radius:9px;border:1px solid var(--filete);
          background:transparent;color:inherit;cursor:pointer;font:inherit}
        .mini button:hover{background:var(--filete)}
        #pizarra:not(.varias) #anterior,#pizarra:not(.varias) #indice,
        #pizarra:not(.varias) #siguiente{display:none}
        @media (max-width:600px){
          #cajon{top:auto;left:8px;right:8px;width:auto;max-height:52vh;
            bottom:calc(116px + env(safe-area-inset-bottom))}
        }
        @media (min-width:900px){#barra button{width:38px;height:38px}}
    """.trimIndent()

    /**
     * **El visor de un dibujo plano.** Todo su estado es el `viewBox` del SVG: el navegador
     * ya lo escala a la ventana, así que cambiarlo **es** panear y hacer zoom, y la
     * geometría no se vuelve a generar nunca.
     *
     * Las anotaciones se apuntan en el grupo `#croquis` del propio SVG, en coordenadas del
     * dibujo —así pasean y se amplían con él, como tinta encima del papel— con el grosor
     * medido en píxeles de pantalla del momento en que se trazan. Cada raya es un camino de
     * curvas cuadráticas por los puntos medios de las muestras, y se recogen **todas** las
     * del puntero (`getCoalescedEvents`): con ratón el navegador entrega una por fotograma
     * y, sin las dos cosas, una raya rápida en un portátil salía a tramos rectos.
     */
    private val VISOR_DIBUJO = """
function crearDibujo(caja, api){
"use strict";
var NS='http://www.w3.org/2000/svg';
var svg=caja.querySelector('svg');
if(!svg) return null;
svg.removeAttribute('width'); svg.removeAttribute('height');
svg.setAttribute('preserveAspectRatio','xMidYMid meet');
var b=svg.viewBox.baseVal;
var casa={x:b.x,y:b.y,w:b.width,h:b.height};
var v={x:casa.x,y:casa.y,w:casa.w,h:casa.h};
var MIN=casa.w/400, MAX=casa.w*20;
var croquis=svg.querySelector('#croquis');
if(!croquis){croquis=document.createElementNS(NS,'g');croquis.id='croquis';svg.appendChild(croquis);}
var hecho=[], rehecho=[], modo='mano', trazo=null, hayLapiz=false;
// Medir: dos puntos, y la distancia en la unidad de la escala del dibujo si la hay.
var medida=[], grupoMedida=null;
var escala=parseFloat(caja.dataset.escala)||0, unidad=caja.dataset.unidad||'', decimales=parseInt(caja.dataset.decimales)||2;
// El papel, si vino como geometría en vez de como foto. Ver [VisorPlano].
var plano=(typeof crearPlano==='function')?crearPlano(caja,{encuadrar:encuadrarEn}):null;

function aplicar(){
  svg.setAttribute('viewBox',v.x+' '+v.y+' '+v.w+' '+v.h);
  if(plano) plano.ver(v);
}
// Encuadra una caja del dibujo, con un dedo de margen: es lo que usa el cajón de capas para
// llevarte a donde está lo que acabas de encender.
function encuadrarEn(c){
  var m=Math.max(c.w,c.h)*0.06;
  v={x:c.x-m, y:c.y-m, w:c.w+m*2, h:c.h+m*2};
  aplicar();
}
function marco(){
  var r=svg.getBoundingClientRect();
  var k=Math.max(v.w/r.width,v.h/r.height);
  return {k:k, ox:v.x-(r.left+(r.width-v.w/k)/2)*k, oy:v.y-(r.top+(r.height-v.h/k)/2)*k};
}
function aEscena(px,py,m){ m=m||marco(); return {x:m.ox+px*m.k, y:m.oy+py*m.k, k:m.k}; }
function zoom(factor,px,py){
  var an=Math.min(Math.max(v.w*factor,MIN),MAX);
  var f=an/v.w; if(f===1)return;
  var p=aEscena(px,py);
  v.x=p.x-(p.x-v.x)*f; v.y=p.y-(p.y-v.y)*f; v.w*=f; v.h*=f;
  aplicar();
}
// Las rayas son caminos suavizados: el punto de partida, el control de cada Q y la L final
// son justo los puntos que se trazaron. Las páginas anotadas con versiones anteriores traen
// polilíneas, y se leen igual.
function leerPuntos(r){
  var lista=[], i;
  if(r.localName==='path'){
    var re=/([MLQ])([^MLQ]*)/g, m, ultimaL=null;
    while((m=re.exec(r.getAttribute('d')||''))){
      var n=m[2].trim().split(/[\s,]+/).map(Number);
      if(m[1]==='M'&&n.length>=2)lista.push({x:n[0],y:n[1]});
      else if(m[1]==='Q'&&n.length>=4)lista.push({x:n[0],y:n[1]});
      else if(m[1]==='L'&&n.length>=2)ultimaL={x:n[0],y:n[1]};
    }
    if(ultimaL)lista.push(ultimaL);
  } else {
    var pts=(r.getAttribute('points')||'').trim().split(/\s+/);
    for(i=0;i<pts.length;i++){ var xy=pts[i].split(','); if(xy.length===2)lista.push({x:+xy[0],y:+xy[1]}); }
  }
  return lista.filter(function(q){return isFinite(q.x)&&isFinite(q.y);});
}
Array.prototype.forEach.call(croquis.children,function(r){r.puntos=leerPuntos(r);});

function deshacer(){
  var u=hecho.pop(); if(!u)return;
  if(u.que==='pinta'){ if(u.raya.parentNode)croquis.removeChild(u.raya); }
  else { croquis.insertBefore(u.raya,u.antes&&u.antes.parentNode===croquis?u.antes:null); }
  rehecho.push(u); api.refrescar();
}
function rehacer(){
  var u=rehecho.pop(); if(!u)return;
  if(u.que==='pinta'){ croquis.appendChild(u.raya); }
  else { u.antes=u.raya.nextSibling; if(u.raya.parentNode)croquis.removeChild(u.raya); }
  hecho.push(u); api.refrescar();
}
function apuntar(u){hecho.push(u);rehecho.length=0;api.refrescar();}
function distanciaAlTramo(p,a,c){
  var dx=c.x-a.x, dy=c.y-a.y, l2=dx*dx+dy*dy;
  var t=l2>0?Math.max(0,Math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/l2)):0;
  return Math.hypot(a.x+t*dx-p.x,a.y+t*dy-p.y);
}
// Se borra la raya cuyo trazado —no solo sus puntos— pasa bajo el dedo: una raya rápida
// tiene los puntos lejos unos de otros.
function borrarEn(px,py,m){
  var p=aEscena(px,py,m);
  var rayas=Array.prototype.slice.call(croquis.children);
  for(var i=0;i<rayas.length;i++){
    var r=rayas[i], pts=r.puntos||[];
    var radio=12*p.k+(+r.getAttribute('stroke-width')||0)/2;
    for(var j=0;j<pts.length;j++){
      if(distanciaAlTramo(p,pts[j],pts[j+1]||pts[j])<=radio){
        apuntar({que:'borra',raya:r,antes:r.nextSibling});
        croquis.removeChild(r);break;
      }
    }
  }
}
function r3(x){return Math.round(x*1000)/1000;}
// La raya pasa por el punto medio de cada dos muestras, con la muestra de control: entre dos
// puntos no hay una recta con codo sino una curva, y una raya trazada deprisa —pocas
// muestras muy separadas— sale fluida y no a tramos.
function anadirPunto(t,p){
  var pts=t.puntos, n=pts.length;
  pts.push(p);
  if(n===0){t.abierto='M '+r3(p.x)+' '+r3(p.y);return;}
  var a=pts[n-1], mx=r3((a.x+p.x)/2), my=r3((a.y+p.y)/2);
  t.abierto+=(n===1?' L ':' Q '+r3(a.x)+' '+r3(a.y)+' ')+mx+' '+my;
}
function pintarTrazo(t){
  var u=t.puntos[t.puntos.length-1];
  t.setAttribute('d',t.abierto+(t.puntos.length>1?' L '+r3(u.x)+' '+r3(u.y):''));
}
// El navegador junta en un solo pointermove todas las muestras llegadas desde el fotograma
// anterior; un ratón da cientos por segundo. Sin pedirlas, una raya rápida salía a tramos.
function muestras(e){
  var lote=(e.getCoalescedEvents&&e.getCoalescedEvents())||[];
  return lote.length?lote:[e];
}
function empezarTrazo(px,py,marca,puntero){
  var p=aEscena(px,py);
  trazo=document.createElementNS(NS,'path');
  trazo.puntero=puntero;
  trazo.setAttribute('fill','none');
  trazo.setAttribute('stroke',api.color());
  trazo.setAttribute('stroke-width',r3((marca?api.grosor()*3:api.grosor())*p.k));
  trazo.setAttribute('stroke-linecap','round');
  trazo.setAttribute('stroke-linejoin','round');
  if(marca){trazo.setAttribute('stroke-opacity','0.55');trazo.setAttribute('class','marca');}
  trazo.puntos=[];trazo.ultimo={x:px,y:py};
  anadirPunto(trazo,{x:p.x,y:p.y});
  pintarTrazo(trazo);
  croquis.appendChild(trazo);
}
function soltarTrazo(){
  if(!trazo)return;
  if(trazo.puntos.length<2)croquis.removeChild(trazo); // un punto solo no se ve
  else apuntar({que:'pinta',raya:trazo});
  trazo=null;
}
// ---- Medir ----
function pintarMedida(){
  if(!grupoMedida){ grupoMedida=document.createElementNS(NS,'g'); grupoMedida.id='medida'; svg.appendChild(grupoMedida); }
  Array.prototype.slice.call(grupoMedida.children).forEach(function(c){ grupoMedida.removeChild(c); });
  var k=aEscena(0,0).k;
  if(medida.length===2){
    var l=document.createElementNS(NS,'line');
    l.setAttribute('x1',medida[0].x); l.setAttribute('y1',medida[0].y);
    l.setAttribute('x2',medida[1].x); l.setAttribute('y2',medida[1].y);
    grupoMedida.appendChild(l);
  }
  for(var i=0;i<medida.length;i++){
    var c=document.createElementNS(NS,'circle');
    c.setAttribute('cx',medida[i].x); c.setAttribute('cy',medida[i].y); c.setAttribute('r',5*k);
    grupoMedida.appendChild(c);
  }
}
function medirEn(px,py){
  var p=aEscena(px,py);
  if(medida.length>=2) medida=[];
  medida.push({x:p.x,y:p.y});
  if(medida.length===2){
    var d=Math.hypot(medida[1].x-medida[0].x, medida[1].y-medida[0].y);
    // Con escala, en su unidad; sin ella, en píxeles del dibujo, que al menos no engaña.
    api.decir(escala>0 ? 'Distancia: '+(d*escala).toFixed(decimales)+' '+unidad
                       : 'Distancia: '+d.toFixed(1)+' px (sin escala)');
  } else api.decir('Primer punto puesto: toca el segundo');
  pintarMedida();
}
function quitarMedida(){ medida=[]; if(grupoMedida) pintarMedida(); }

// **Con lápiz a la vista, el dedo mueve el papel.** En cuanto se posa un lápiz la página se
// entera y no vuelve atrás: desde ese momento el lápiz traza y el dedo pasea, sin cambiar de
// modo, igual que en la aplicación. Y el otro extremo del lápiz —el que se declara como
// goma— borra mientras se use.
function loQueHace(e){
  if(modo==='medir') return 'medir';
  if(e.pointerType==='pen'){
    // La goma del lápiz solo borra si el borrador viene en el documento.
    if((e.buttons&32) && permitida('goma')) return 'goma';
    if(modo==='goma'||modo==='marcador'||modo==='lapiz') return modo;
    // Con la mano puesta, el lápiz traza con lo que haya: lápiz, si no resaltador, y si el
    // documento no trae ninguno de los dos, pasea como el dedo. Antes trazaba siempre, aunque
    // el lápiz se hubiera dejado fuera al exportar.
    if(permitida('lapiz')) return 'lapiz';
    if(permitida('marcador')) return 'marcador';
    return 'mano';
  }
  if(hayLapiz) return 'mano';
  return modo;
}
var dedos=new Map(), arrastre=null, pellizco=null;
// **Cada trazo es de un solo puntero**, y el pellizco es cosa de dos dedos: el lápiz nunca
// entra en él. Antes cualquier puntero que se moviera añadía puntos al trazo abierto, y con
// un dedo apoyado mientras se escribía con el lápiz salían rayas entre el dedo y la punta.
function losDedos(){ return Array.from(dedos.values()).filter(function(d){return d.tipo!=='pen';}); }
function arrastrando(e){ return arrastre&&arrastre.id===e.pointerId; }
caja.addEventListener('pointerdown',function(e){
  if(e.pointerType==='mouse'&&e.button!==0)return;
  if(e.pointerType==='pen'&&!hayLapiz){hayLapiz=true;api.decir('Lápiz a la vista: el dedo mueve el papel');}
  caja.setPointerCapture(e.pointerId);
  dedos.set(e.pointerId,{x:e.clientX,y:e.clientY,tipo:e.pointerType});
  var dd=losDedos();
  if(e.pointerType==='pen'){
    // El lápiz hace lo suyo aunque haya un dedo apoyado: es lo normal al escribir.
    if(trazo) return;
    var q=loQueHace(e);
    if(q==='lapiz'||q==='marcador') empezarTrazo(e.clientX,e.clientY,q==='marcador',e.pointerId);
    else if(q==='goma') borrarEn(e.clientX,e.clientY);
    else if(q==='medir') medirEn(e.clientX,e.clientY);
    else if(!arrastre){ arrastre={id:e.pointerId,x:e.clientX,y:e.clientY}; api.agarrado(true); }
    return;
  }
  if(dd.length===1){
    var q1=loQueHace(e);
    if(trazo) return;   // el lápiz está escribiendo: el dedo no le quita el trazo
    if(q1==='lapiz'||q1==='marcador') empezarTrazo(e.clientX,e.clientY,q1==='marcador',e.pointerId);
    else if(q1==='goma') borrarEn(e.clientX,e.clientY);
    else if(q1==='medir') medirEn(e.clientX,e.clientY);
    else { arrastre={id:e.pointerId,x:e.clientX,y:e.clientY}; api.agarrado(true); }
  } else if(dd.length===2){
    // El segundo dedo encuadra: un trazo del dedo a medias se queda como iba; el del lápiz
    // sigue, que es de otra mano.
    arrastre=null;
    if(trazo&&trazo.puntero!==undefined&&dedos.get(trazo.puntero)&&dedos.get(trazo.puntero).tipo!=='pen') soltarTrazo();
    pellizco={d:Math.hypot(dd[0].x-dd[1].x,dd[0].y-dd[1].y)};
  }
});
caja.addEventListener('pointermove',function(e){
  if(!dedos.has(e.pointerId))return;
  var d0=dedos.get(e.pointerId); d0.x=e.clientX; d0.y=e.clientY;
  var dd=losDedos();
  if(pellizco&&dd.length===2&&e.pointerType!=='pen'){
    var dist=Math.hypot(dd[0].x-dd[1].x,dd[0].y-dd[1].y);
    if(dist>0&&pellizco.d>0) zoom(pellizco.d/dist,(dd[0].x+dd[1].x)/2,(dd[0].y+dd[1].y)/2);
    pellizco.d=dist;
  } else if(trazo&&trazo.puntero===e.pointerId){
    var m=marco(), lote=muestras(e), nuevos=0;
    for(var i=0;i<lote.length;i++){
      var cx=lote[i].clientX, cy=lote[i].clientY;
      if(Math.hypot(cx-trazo.ultimo.x,cy-trazo.ultimo.y)<1)continue; // menos de un píxel no es un punto
      trazo.ultimo={x:cx,y:cy};
      anadirPunto(trazo,aEscena(cx,cy,m));nuevos++;
    }
    if(nuevos)pintarTrazo(trazo);
  } else if(!trazo&&!pellizco&&loQueHace(e)==='goma'){
    var mg=marco(), lg=muestras(e);
    for(var g=0;g<lg.length;g++)borrarEn(lg[g].clientX,lg[g].clientY,mg);
  } else if(arrastrando(e)){
    var k=aEscena(0,0).k;
    v.x-=(e.clientX-arrastre.x)*k; v.y-=(e.clientY-arrastre.y)*k;
    arrastre.x=e.clientX; arrastre.y=e.clientY;
    aplicar();
  }
});
function soltar(e){
  dedos.delete(e.pointerId);
  if(losDedos().length<2)pellizco=null;
  if(trazo&&trazo.puntero===e.pointerId) soltarTrazo();
  if(arrastrando(e)){ arrastre=null; }
  if(dedos.size===0){ soltarTrazo(); arrastre=null; api.agarrado(false); }
}
caja.addEventListener('pointerup',soltar);
caja.addEventListener('pointercancel',soltar);
caja.addEventListener('wheel',function(e){
  e.preventDefault();
  zoom(e.deltaY>0?1.12:1/1.12,e.clientX,e.clientY);
},{passive:false});
// El doble toque encaja solo con la mano: con el lápiz, dos toques seguidos son dos puntos.
caja.addEventListener('dblclick',function(e){e.preventDefault();if(modo==='mano')encajar();});
function encajar(){v={x:casa.x,y:casa.y,w:casa.w,h:casa.h};aplicar();}
// Lo que deja coger el documento: lo que no tiene botón tampoco entra por el teclado.
var permitidas=(document.body.dataset.herramientas||'mano lapiz marcador goma medir').split(' ');
function permitida(h){ return permitidas.indexOf(h)>=0; }
function permitidasDe(lista){ return lista.filter(permitida); }

function rayasComoTexto(){
  var s='';
  Array.prototype.forEach.call(croquis.children,function(r){
    s+='<'+r.localName;
    for(var i=0;i<r.attributes.length;i++){
      var a=r.attributes[i];
      s+=' '+a.name+'="'+String(a.value).replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"';
    }
    s+='/>\n';
  });
  return s;
}
aplicar();
return {
 tipo:'dibujo',
 // Una hoja escondida mide cero: el plano se pinta al asomarse a ella, no antes.
 activar:function(){ aplicar(); },
 desactivar:function(){soltarTrazo();},
 medir:function(){ if(plano) plano.medir(); },
 capas:plano?plano.capas:null,
 soloLineas:plano?plano.soloLineas:null,
 esSoloLineas:plano?plano.esSoloLineas:null,
 hayRellenos:plano?plano.hayRellenos:null,
 encajar:encajar,
 zoom:function(f){var r=svg.getBoundingClientRect();zoom(f,r.left+r.width/2,r.top+r.height/2);},
 modo:function(m){ modo=m; if(m!=='medir') quitarMedida(); else api.decir('Toca dos puntos del dibujo'); },
 pintando:function(){ return !hayLapiz && modo!=='mano' && modo!=='medir'; },
 herramientas:permitidasDe(['mano','lapiz','marcador','goma','medir']),
 deshacer:deshacer, rehacer:rehacer,
 puedeDeshacer:function(){return hecho.length>0;},
 puedeRehacer:function(){return rehecho.length>0;},
 rayas:rayasComoTexto
};
}
"""

    /**
     * **El armazón**: qué página se mira, la barra, los cajones y guardar.
     *
     * Guardar es reescribir la página: se copió a sí misma al abrirse —antes de tocar nada—
     * y al guardar mete las rayas de **cada** hoja en el grupo que le toca de esa copia y la
     * entrega como archivo. Quien la abra después encuentra las rayas ya en el SVG y puede
     * borrarlas, seguir y volver a guardar.
     */
    private val SHELL = """
(function(){
"use strict";
var PLANTILLA='<!DOCTYPE html>\n'+document.documentElement.outerHTML;
function id(x){return document.getElementById(x);}
var cajaLienzo=id('lienzo'), estado=id('estado'), cajon=id('cajon');
var hojas=[].slice.call(document.querySelectorAll('#lienzo .hoja'));
if(!hojas.length) return;
var color=(document.querySelector('#colores .activo')||{dataset:{}}).dataset.color||'#ff1744';
var grosor=+((document.querySelector('#grosores .activo')||{dataset:{}}).dataset.grosor||4);
var iHoja=-1, actual=null, avisoPendiente=null;

var api={
  decir:function(t){ estado.textContent=t||''; },
  refrescar:refrescar,
  agarrado:function(si){ cajaLienzo.classList.toggle('agarrado',!!si); },
  color:function(){return color;},
  grosor:function(){return grosor;}
};
var pagina=hojas.map(function(d){
  if(d.dataset.tipo==='espacio'){
    var t=d.querySelector('script.datos');
    return crearEspacio(d, JSON.parse(t.textContent), api);
  }
  if(d.dataset.tipo==='nota') return crearNota(d);
  return crearDibujo(d, api);
});
// **Una nota no se pinta: la compone el navegador**, y de ahí le viene lo que un dibujo no
// puede dar —se reparte al ancho que haya, se busca con la lupa del navegador y se puede
// seleccionar y copiar—. Lo que sí lleva es una **capa de tinta encima**: con el lápiz o el
// resaltador en la mano se raya sobre el texto, se subraya y se tacha, igual que en una hoja;
// con la mano, la capa no recibe el dedo y el texto vuelve a ser texto. Ver `.tinta`.
function crearNota(d){
  var NS='http://www.w3.org/2000/svg';
  var art=d.querySelector('.nota'), svg=d.querySelector('svg.tinta'), tam=16;
  var croquis=svg&&svg.querySelector('#croquis');
  if(svg&&!croquis){croquis=document.createElementNS(NS,'g');croquis.id='croquis';svg.appendChild(croquis);}
  var hecho=[], rehecho=[], modo='mano', trazo=null;
  function poner(v){ tam=Math.min(Math.max(v,11),30); if(art) art.style.fontSize=tam+'px'; medir(); }
  // La capa mide lo que mida el texto, no lo que mida la ventana: lo rayado tiene que
  // quedarse donde se rayó aunque después se desplace la nota o cambie el tamaño de la letra.
  function medir(){
    if(!svg||!art) return;
    var alto=Math.max(art.scrollHeight,d.clientHeight);
    svg.setAttribute('height',alto);
    svg.setAttribute('viewBox','0 0 '+d.clientWidth+' '+alto);
    svg.style.height=alto+'px';
  }
  function donde(e){
    var r=svg.getBoundingClientRect();
    return {x:e.clientX-r.left, y:e.clientY-r.top};
  }
  function r3(x){return Math.round(x*1000)/1000;}
  function anadirPunto(t,p){
    var pts=t.puntos, n=pts.length;
    pts.push(p);
    if(n===0){t.abierto='M '+r3(p.x)+' '+r3(p.y);return;}
    var a=pts[n-1];
    t.abierto+=(n===1?' L ':' Q '+r3(a.x)+' '+r3(a.y)+' ')+r3((a.x+p.x)/2)+' '+r3((a.y+p.y)/2);
  }
  function pintar(t){
    var u=t.puntos[t.puntos.length-1];
    t.setAttribute('d',t.abierto+(t.puntos.length>1?' L '+r3(u.x)+' '+r3(u.y):''));
  }
  function muestras(e){
    var lote=(e.getCoalescedEvents&&e.getCoalescedEvents())||[];
    return lote.length?lote:[e];
  }
  function borrarEn(p){
    var rayas=[].slice.call(croquis.children);
    for(var i=0;i<rayas.length;i++){
      var r=rayas[i], pts=r.puntos||[], gordo=(+r.getAttribute('stroke-width')||2)/2+10;
      for(var j=0;j<pts.length;j++){
        if(Math.hypot(pts[j].x-p.x,pts[j].y-p.y)<=gordo){
          hecho.push({que:'borra',raya:r,antes:r.nextSibling}); rehecho.length=0;
          croquis.removeChild(r); api.refrescar(); break;
        }
      }
    }
  }
  if(svg){
    svg.addEventListener('pointerdown',function(e){
      if(modo==='mano') return;
      e.preventDefault();
      svg.setPointerCapture(e.pointerId);
      var p=donde(e);
      if(modo==='goma'){ borrarEn(p); trazo=null; return; }
      var marca=(modo==='marcador');
      trazo=document.createElementNS(NS,'path');
      trazo.setAttribute('fill','none');
      trazo.setAttribute('stroke',api.color());
      trazo.setAttribute('stroke-width',marca?api.grosor()*3:api.grosor());
      trazo.setAttribute('stroke-linecap','round');
      trazo.setAttribute('stroke-linejoin','round');
      if(marca){trazo.setAttribute('stroke-opacity','0.45');trazo.setAttribute('class','marca');}
      trazo.puntos=[]; anadirPunto(trazo,p); pintar(trazo);
      croquis.appendChild(trazo);
    });
    svg.addEventListener('pointermove',function(e){
      if(modo==='mano') return;
      if(modo==='goma'){ if(e.buttons) borrarEn(donde(e)); return; }
      if(!trazo) return;
      var lote=muestras(e);
      for(var i=0;i<lote.length;i++) anadirPunto(trazo,donde(lote[i]));
      pintar(trazo);
    });
    function soltar(){
      if(!trazo) return;
      if(trazo.puntos.length<2) croquis.removeChild(trazo);
      else { hecho.push({que:'pinta',raya:trazo}); rehecho.length=0; api.refrescar(); }
      trazo=null;
    }
    svg.addEventListener('pointerup',soltar);
    svg.addEventListener('pointercancel',soltar);
    [].forEach.call(croquis.children,function(r){ r.puntos=[]; });
  }
  return {
    tipo:'nota',
    activar:medir, desactivar:function(){}, medir:medir,
    encajar:function(){ poner(16); d.scrollTop=0; },
    zoom:function(f){ poner(Math.round(tam/f)); },
    modo:function(m){ modo=m; },
    pintando:function(){ return modo!=='mano'; },
    herramientas:['mano','lapiz','marcador','goma'].filter(function(h){
      return (document.body.dataset.herramientas||'mano lapiz marcador goma').split(' ').indexOf(h)>=0; }),
    deshacer:function(){
      var u=hecho.pop(); if(!u)return;
      if(u.que==='pinta'){ if(u.raya.parentNode)croquis.removeChild(u.raya); }
      else croquis.insertBefore(u.raya,u.antes&&u.antes.parentNode===croquis?u.antes:null);
      rehecho.push(u); api.refrescar();
    },
    rehacer:function(){
      var u=rehecho.pop(); if(!u)return;
      if(u.que==='pinta') croquis.appendChild(u.raya);
      else { u.antes=u.raya.nextSibling; if(u.raya.parentNode)croquis.removeChild(u.raya); }
      hecho.push(u); api.refrescar();
    },
    puedeDeshacer:function(){return hecho.length>0;},
    puedeRehacer:function(){return rehecho.length>0;},
    rayas:function(){
      var s='';
      [].forEach.call(croquis.children,function(r){
        s+='<'+r.localName;
        for(var i=0;i<r.attributes.length;i++){
          var a=r.attributes[i];
          s+=' '+a.name+'="'+String(a.value).replace(/&/g,'&amp;').replace(/"/g,'&quot;')+'"';
        }
        s+='/>\n';
      });
      return s;
    }
  };
}
function grupoDe(i){ return pagina[i]&&pagina[i].tipo==='dibujo'?pagina[i]:null; }

function refrescar(){
  var d=id('deshacer'), r=id('rehacer');
  if(d) d.disabled=!(actual&&actual.puedeDeshacer&&actual.puedeDeshacer());
  if(r) r.disabled=!(actual&&actual.puedeRehacer&&actual.puedeRehacer());
}
function marcarHerramienta(){
  ['mano','lapiz','marcador','goma','girar','mover','medir'].forEach(function(n){
    var b=id(n); if(b) b.classList.toggle('activo', actual&&actual.modoActual===n);
  });
  var p=id('paleta');
  if(p) p.hidden=!(actual&&(actual.modoActual==='lapiz'||actual.modoActual==='marcador'));
  cajaLienzo.classList.toggle('pintando', !!(actual&&actual.pintando&&actual.pintando()));
  cajaLienzo.classList.toggle('midiendo', !!(actual&&actual.modoActual==='medir'));
}
function elegir(m){
  if(!actual||actual.herramientas.indexOf(m)<0) return;
  actual.modoActual=(actual.modoActual===m&&m!=='mano'&&m!=='girar')?actual.herramientas[0]:m;
  actual.modo(actual.modoActual);
  marcarHerramienta();
}
function irA(i){
  if(i<0||i>=pagina.length||i===iHoja) return;
  if(actual) actual.desactivar();
  iHoja=i; actual=pagina[i];
  hojas.forEach(function(d,k){d.hidden=k!==i;});
  document.body.style.background=hojas[i].dataset.fondo||'';
  document.body.classList.toggle('oscuro',hojas[i].dataset.oscuro==='1');
  document.body.classList.toggle('enElEspacio',hojas[i].dataset.tipo==='espacio');
  var esEspacio=hojas[i].dataset.tipo==='espacio';
  var hayPlano=hojas[i].dataset.plano==='1';
  // Una nota lleva los mismos mandos que un dibujo: se raya encima igual. Ver [crearNota].
  [].forEach.call(document.querySelectorAll('.solo-dibujo'),function(g){g.hidden=esEspacio;});
  [].forEach.call(document.querySelectorAll('.solo-espacio'),function(g){g.hidden=!esEspacio;});
  [].forEach.call(document.querySelectorAll('.solo-plano'),function(g){g.hidden=!hayPlano;});
  var mide=actual&&actual.herramientas.indexOf('medir')>=0;
  [].forEach.call(document.querySelectorAll('.solo-medir'),function(g){g.hidden=!mide;});
  if(!actual.modoActual) actual.modoActual=actual.herramientas[0];
  actual.activar();
  actual.modo(actual.modoActual);
  var a=id('anterior'), s=id('siguiente');
  if(a) a.disabled=i===0;
  if(s) s.disabled=i===pagina.length-1;
  if(!cajon.hidden){cajon.hidden=true;cajon.dataset.que='';}
  api.decir('');
  marcarHerramienta(); refrescar();
}

// ---- Los cajones: páginas, vistas y grupos ----
function abrir(titulo,filas,pie){
  if(cajon.dataset.que===titulo&&!cajon.hidden){cajon.hidden=true;cajon.dataset.que='';return;}
  cajon.dataset.que=titulo; cajon.hidden=false; cajon.innerHTML='';
  var t=document.createElement('h2'); t.textContent=titulo; cajon.appendChild(t);
  filas.forEach(function(f){cajon.appendChild(f);});
  if(pie) cajon.appendChild(pie);
}
function fila(texto,al,marcada){
  var d=document.createElement('div'); d.className='fila'+(marcada?' activo':''); d.tabIndex=0;
  var n=document.createElement('span'); n.className='n'; n.textContent=texto;
  d.appendChild(n);
  d.onclick=al;
  d.onkeydown=function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();al();}};
  return d;
}
function boton(t,al){var b=document.createElement('button');b.textContent=t;b.onclick=al;return b;}
if(id('indice')) id('indice').onclick=function(){
  abrir('Páginas', hojas.map(function(d,k){
    return fila(d.dataset.nombre||('Hoja '+(k+1)),function(){irA(k);cajon.hidden=true;cajon.dataset.que='';},k===iHoja);
  }));
};
if(id('anterior')) id('anterior').onclick=function(){irA(iHoja-1);};
if(id('siguiente')) id('siguiente').onclick=function(){irA(iHoja+1);};
if(id('vistas')) id('vistas').onclick=function(){
  if(!actual||!actual.vistas) return;
  abrir('Vistas', actual.vistas().map(function(v){
    return fila(v.n,function(){v.ir();cajon.hidden=true;cajon.dataset.que='';});
  }));
};
if(id('grupos')) id('grupos').onclick=function(){ pintarGrupos(); };
if(id('capas')) id('capas').onclick=function(){ pintarCapas(); };
// **Las capas del plano**: las mismas que traía el PDF de AutoCAD. Apagar las que estorban
// —las tramas, los sombreados— deja el dibujo en sus líneas, que es lo que se quiere ver, y
// además lo pinta más deprisa. `Solo líneas` hace eso de un golpe con todo lo relleno.
function pintarCapas(){
  if(!actual||!actual.capas) return;
  var lista=actual.capas();
  var filas=lista.map(function(g){
    var d=document.createElement('div'); d.className='fila';
    var c=document.createElement('input'); c.type='checkbox'; c.checked=g.puesto();
    c.onchange=function(){g.poner(c.checked);};
    var n=document.createElement('span'); n.className='n'; n.textContent=g.nombre;
    n.onclick=function(){c.checked=!c.checked;c.onchange();};
    var z=document.createElement('span'); z.className='lupa'; z.textContent='⤢';
    z.title='Ir a esta capa';
    z.onclick=function(ev){ev.stopPropagation();g.acercarse();};
    d.appendChild(c); d.appendChild(n); d.appendChild(z);
    return d;
  });
  if(!filas.length) filas=[fila('Este plano no trae capas',function(){})];
  var pie=document.createElement('div'); pie.className='mini';
  pie.appendChild(boton('Todas',function(){lista.forEach(function(g){g.poner(true);});refrescarCapas();}));
  pie.appendChild(boton('Ninguna',function(){lista.forEach(function(g){g.poner(false);});refrescarCapas();}));
  if(actual.hayRellenos&&actual.hayRellenos()){
    var b=boton(actual.esSoloLineas()?'Con relleno':'Solo líneas',function(){
      actual.soloLineas(!actual.esSoloLineas()); refrescarCapas();
    });
    pie.appendChild(b);
  }
  abrir('Capas',filas,pie);
}
function refrescarCapas(){ cajon.dataset.que=''; pintarCapas(); }
function pintarGrupos(){
  if(!actual||!actual.grupos) return;
  var lista=actual.grupos();
  if(!lista.length){ abrir('Grupos',[fila('Este croquis no tiene grupos',function(){})]); return; }
  var filas=lista.map(function(g){
    var d=document.createElement('div'); d.className='fila';
    var c=document.createElement('input'); c.type='checkbox'; c.checked=g.puesto();
    c.onchange=function(){g.poner(c.checked);};
    var n=document.createElement('span'); n.className='n'; n.textContent=g.nombre;
    n.onclick=function(){c.checked=!c.checked;c.onchange();};
    var z=document.createElement('span'); z.className='lupa'; z.textContent='⤢';
    z.title='Acercarse a este grupo';
    z.onclick=function(ev){ev.stopPropagation();g.acercarse();};
    d.appendChild(c); d.appendChild(n); d.appendChild(z);
    return d;
  });
  var pie=document.createElement('div'); pie.className='mini';
  pie.appendChild(boton('Todos',function(){lista.forEach(function(g){g.poner(true);});refrescarGrupos();}));
  pie.appendChild(boton('Ninguno',function(){lista.forEach(function(g){g.poner(false);});refrescarGrupos();}));
  abrir('Grupos',filas,pie);
}
// Repintar el cajón sin cerrarlo: `abrir` alterna, así que se le quita la marca antes.
function refrescarGrupos(){ cajon.dataset.que=''; pintarGrupos(); }

// ---- La barra ----
['mano','lapiz','marcador','goma','girar','mover','medir'].forEach(function(n){
  var b=id(n); if(b) b.onclick=function(){elegir(n);};
});
if(id('deshacer')) id('deshacer').onclick=function(){if(actual.deshacer)actual.deshacer();};
if(id('rehacer')) id('rehacer').onclick=function(){if(actual.rehacer)actual.rehacer();};
if(id('encajar')) id('encajar').onclick=function(){actual.encajar();};
if(id('mas')) id('mas').onclick=function(){if(actual.zoom)actual.zoom(1/1.3);};
if(id('menos')) id('menos').onclick=function(){if(actual.zoom)actual.zoom(1.3);};
document.querySelectorAll('#colores .color').forEach(function(b){
  b.onclick=function(){color=b.dataset.color;marcar('#colores .color',b);};
});
document.querySelectorAll('#grosores .grosor').forEach(function(b){
  b.onclick=function(){grosor=+b.dataset.grosor;marcar('#grosores .grosor',b);};
});
function marcar(sel,cual){
  document.querySelectorAll(sel).forEach(function(o){o.classList.toggle('activo',o===cual);});
}

// ---- Guardar y compartir: la página se reescribe a sí misma ----
function avisar(texto){
  var a=id('aviso'); a.textContent=texto; a.hidden=false;
  clearTimeout(avisoPendiente); avisoPendiente=setTimeout(function(){a.hidden=true;},1800);
}
// Se reescribe el grupo de cada dibujo, en orden: la primera copia del patrón es la del
// primer dibujo. Las páginas del espacio no tienen grupo y no cuentan.
function paginaAnotada(){
  // Los dibujos y las notas llevan grupo; las páginas del espacio, no.
  var dibujos=pagina.filter(function(p){return p&&(p.tipo==='dibujo'||p.tipo==='nota');}), i=0;
  var re=/<g id="croquis"[^>]*>[\s\S]*?<\/g>/g;
  return PLANTILLA.replace(re,function(){
    var p=dibujos[i++];
    return p?'<g id="croquis">\n'+p.rayas()+'</g>':'<g id="croquis"></g>';
  });
}
function nombreDelArchivo(){
  var n=(document.body.dataset.nombre||document.title||'dibujo').replace(/[\\/:*?"<>|]+/g,'-').trim();
  return (n||'dibujo')+'.html';
}
function descargar(){
  var blob=new Blob([paginaAnotada()],{type:'text/html'});
  var a=document.createElement('a');
  a.href=URL.createObjectURL(blob); a.download=nombreDelArchivo();
  document.body.appendChild(a); a.click(); document.body.removeChild(a);
  setTimeout(function(){URL.revokeObjectURL(a.href);},4000);
  avisar('Guardado: '+a.download);
}
function compartir(){
  var archivo;
  try{ archivo=new File([paginaAnotada()],nombreDelArchivo(),{type:'text/html'}); }catch(e){ return descargar(); }
  if(navigator.share&&navigator.canShare&&navigator.canShare({files:[archivo]})){
    navigator.share({files:[archivo],title:document.title}).catch(function(){});
  } else { descargar(); avisar('Guardado; compártelo desde tus descargas'); }
}
// **Guardados con red.** Estos botones se pueden dejar fuera al exportar, y un `null.onclick`
// aquí tumbaba el armazón entero: la página arrancaba sin `irA(0)` y no funcionaba ningún
// botón, ni el de medir. Lo reportó el usuario exportando con una sola función marcada.
var puedeGuardar=!!id('guardar');
if(id('guardar')) id('guardar').onclick=descargar;
if(id('compartir')) id('compartir').onclick=compartir;

addEventListener('resize',function(){ if(actual&&actual.medir) actual.medir(); });
document.addEventListener('keydown',function(e){
  var ctrl=e.ctrlKey||e.metaKey, k=(e.key||'').toLowerCase();
  if(ctrl&&k==='z'&&!e.shiftKey){e.preventDefault();if(actual.deshacer)actual.deshacer();return;}
  if(ctrl&&(k==='y'||(k==='z'&&e.shiftKey))){e.preventDefault();if(actual.rehacer)actual.rehacer();return;}
  if(ctrl&&k==='s'){e.preventDefault();if(puedeGuardar)descargar();return;}
  if(ctrl)return;
  if(e.key==='PageDown')irA(iHoja+1);
  else if(e.key==='PageUp')irA(iHoja-1);
  else if(k==='v'||k==='h')elegir(actual&&actual.tipo==='dibujo'?'mano':'mover');
  else if(k==='p')elegir('lapiz');
  else if(k==='m')elegir('marcador');
  else if(k==='e')elegir('goma');
  else if(k==='g')elegir('girar');
  else if(k==='d')elegir('medir');
  else if(k==='0')actual.encajar();
  else if((k==='+'||k==='=')&&actual.zoom)actual.zoom(1/1.3);
  else if(k==='-'&&actual.zoom)actual.zoom(1.3);
  else if(k==='escape'){cajon.hidden=true;cajon.dataset.que='';api.decir('');}
});
irA(0);
})();
// **Los saltos de tiempo de una transcripción.** Un párrafo que empieza por su minuto es
// un enlace: tocarlo lleva el audio de esa nota a ese punto y lo pone a sonar.
document.addEventListener('click',function(e){
  var a=e.target&&e.target.closest?e.target.closest('.salto'):null;
  if(!a) return;
  e.preventDefault();
  var nota=a.closest('.nota')||document;
  var au=nota.querySelector('audio');
  if(!au) return;
  au.currentTime=(+a.dataset.ms)/1000;
  au.play();
});
"""
}
