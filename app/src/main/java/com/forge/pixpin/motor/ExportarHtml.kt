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
        /**
         * **Una tabla con fórmulas.** Viaja lo escrito —el JSON de [TablaDeCalculo]— y la
         * tabla ya calculada para leerse sin guion; en el navegador sigue calculando, se edita,
         * se pega desde Excel y se guarda. Ver [VisorTabla].
         */
        class Tabla(nombre: String, val tabla: TablaDeCalculo, fondo: String = "#ffffff") : HojaWeb(nombre, fondo)
    }

    /**
     * **Qué lleva el documento exportado.** Lo pidió el usuario (5-sep-2026): poder limitar
     * lo que se manda —una lámina para enseñar no necesita lápiz ni guardar—. Lo que se apaga
     * no va: ni su botón, ni su atajo de teclado, ni su código si no lo usa nadie.
     */
    /** Qué hojas salen de un lienzo con marcos: solo los marcos, el lienzo entero, o ambos. */
    const val PREFIJO_DE_HOJAS = "hojas:"
    const val HOJAS_MARCOS = "marcos"
    const val HOJAS_ENTERO = "entero"
    const val HOJAS_AMBOS = "ambos"
    fun hojasDelLienzo(marcadas: Set<String>?): String =
        marcadas?.firstOrNull { it.startsWith(PREFIJO_DE_HOJAS) }?.removePrefix(PREFIJO_DE_HOJAS) ?: HOJAS_AMBOS
    fun conHojasDelLienzo(marcadas: Set<String>, cuales: String): Set<String> =
        marcadas.filterNot { it.startsWith(PREFIJO_DE_HOJAS) }.toSet() + (PREFIJO_DE_HOJAS + cuales)

    /** Con qué calidad viaja el audio; va en el mismo conjunto de funciones como `audio:<calidad>`. */
    const val PREFIJO_DE_AUDIO = "audio:"
    fun calidadDeAudio(marcadas: Set<String>?): String =
        marcadas?.firstOrNull { it.startsWith(PREFIJO_DE_AUDIO) }?.removePrefix(PREFIJO_DE_AUDIO) ?: AudioLigero.LIGERO
    fun conCalidadDeAudio(marcadas: Set<String>, calidad: String): Set<String> =
        marcadas.filterNot { it.startsWith(PREFIJO_DE_AUDIO) }.toSet() + (PREFIJO_DE_AUDIO + calidad)

    /** Si el grupo [grupo] va en la página: basta con que vaya algo suyo. Ver [Opciones.GRUPOS]. */
    fun grupoPuesto(marcadas: Set<String>, grupo: String): Boolean =
        Opciones.GRUPOS[grupo].orEmpty().any { it in marcadas }

    /** Enciende o apaga el grupo entero; una clave suelta que no sea de ningún grupo, sola. */
    fun conGrupo(marcadas: Set<String>, grupo: String, puesto: Boolean): Set<String> {
        val claves = Opciones.GRUPOS[grupo] ?: listOf(grupo)
        return if (puesto) marcadas + claves else marcadas - claves.toSet()
    }

    /**
     * **Si quien abre la página puede cambiar las celdas de las tablas.** Va como marca de
     * «solo ver» y no como nombre más: los ajustes guardados antes de que existiera no la
     * traen, y tienen que seguir dejando editar, que es lo que hacían.
     */
    const val TABLAS_SOLO_VER = "tablas:solo-ver"
    fun tablasEditables(marcadas: Set<String>?): Boolean = marcadas?.contains(TABLAS_SOLO_VER) != true
    fun conTablasEditables(marcadas: Set<String>, editables: Boolean): Set<String> =
        if (editables) marcadas - TABLAS_SOLO_VER else marcadas + TABLAS_SOLO_VER

    class Opciones(
        /** Ver [TABLAS_SOLO_VER]. */
        val editarTablas: Boolean = true,
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

            /**
             * **Las funciones van por grupos**, y cada grupo es un interruptor: lo que funciona
             * junto se enciende junto (lo pidió el usuario el 13-sep-2026). El índice de páginas
             * no está en ninguno porque va siempre.
             */
            val GRUPOS: Map<String, List<String>> = linkedMapOf(
                "edicion" to listOf("lapiz", "resaltador", "borrador", "deshacer"),
                "especiales" to listOf("medir", "capas"),
                "guardar" to listOf("guardar", "compartir")
            )

            /** Las opciones a partir de lo marcado en los ajustes; null es «todo». */
            fun de(marcadas: Set<String>?): Opciones {
                if (marcadas == null) return Opciones()
                // Lo guardado antes de los grupos puede traer un grupo a medias: con que vaya
                // algo suyo, va entero, que es lo que dice el interruptor.
                val edicion = grupoPuesto(marcadas, "edicion")
                val especiales = grupoPuesto(marcadas, "especiales")
                val guardado = grupoPuesto(marcadas, "guardar")
                return Opciones(
                    editarTablas = tablasEditables(marcadas),
                    lapiz = edicion, resaltador = edicion, borrador = edicion, deshacer = edicion,
                    medir = especiales, capas = especiales,
                    paginas = true, guardar = guardado, compartir = guardado
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
        // Una tabla también se raya: el lápiz va encima de las celdas. Ver `crearTabla`.
        val hayPintable = hayDibujo || hojas.any { it is HojaWeb.Nota || it is HojaWeb.Tabla }
        val hayTabla = hojas.any { it is HojaWeb.Tabla }
        val tamano = hojas.sumOf {
            when (it) {
                is HojaWeb.Dibujo -> it.svg.length + (it.plano?.length ?: 0)
                is HojaWeb.Espacio -> it.datos.length
                is HojaWeb.Nota -> it.html.length
                is HojaWeb.Tabla -> it.tabla.celdas.size * 60
            }
        }
        return buildString(tamano + 60000) {
            append("<!DOCTYPE html>\n<html lang=\"es\">\n<head>\n")
            append("<meta charset=\"utf-8\"/>\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\"/>\n")
            append("<meta name=\"color-scheme\" content=\"").append(if (oscuro) "dark" else "light").append("\"/>\n")
            append("<meta name=\"generator\" content=\"PixPin\"/>\n")
            append("<title>").append(escapar(titulo)).append("</title>\n")
            append("<style>").append(ESTILO.replace("FONDO", fondo))
            if (hayTabla) append('\n').append(VisorTabla.ESTILO)
            append("</style>\n</head>\n")
            // **El tema va con la página.** Un documento puede llevar una lámina sobre papel
            // blanco y un croquis sobre pizarra; con un solo juego de colores para todo, en
            // una de las dos la letra queda encima de su propio color. La clase la pone el
            // armazón al cambiar de hoja.
            append("<body data-nombre=\"").append(escapar(nombre)).append('"')
            // Lo que se deja coger: el visor filtra sus herramientas por esto, para que lo que
            // no tiene botón tampoco entre por el teclado.
            append(" data-herramientas=\"").append(opciones.herramientas().joinToString(" ")).append('"')
            if (hayTabla && !opciones.editarTablas) append(" data-tabla-editable=\"0\"")
            if (oscuro) append(" class=\"oscuro\"")
            append(">\n")
            // **El índice, a la vista y con enlaces** (lo pidió el usuario el 13-sep-2026): cada
            // hoja con su nombre, y tocarla lleva a ella. Plegable, y plegado en un teléfono.
            if (hojas.size > 1 && opciones.paginas) {
                // El título es **la hoja que se está mirando** («Canvas 8»); al abrirlo, todas.
                append("<details id=\"indice-fijo\" open><summary><span class=\"titulo\">")
                append(escapar(hojas[0].nombre.ifBlank { "Hoja 1" }))
                append("</span><span class=\"cuenta\">1 / ").append(hojas.size).append("</span></summary><nav>")
                for ((i, hoja) in hojas.withIndex()) {
                    append("<a href=\"#hoja-").append(i + 1).append("\" data-i=\"").append(i).append("\">")
                    append("<b>").append(i + 1).append("</b> ")
                    append(escapar(hoja.nombre.ifBlank { "Hoja ${i + 1}" })).append("</a>")
                }
                append("</nav></details>\n")
            }
            append("<div id=\"lienzo\">\n")
            for ((i, hoja) in hojas.withIndex()) {
                // **Su dirección**: `#hoja-3` abre la tercera. Ver el índice de arriba.
                append("<div class=\"hoja\" id=\"hoja-").append(i + 1).append("\" data-tipo=\"")
                append(
                    when (hoja) {
                        is HojaWeb.Espacio -> "espacio"
                        is HojaWeb.Nota -> "nota"
                        is HojaWeb.Dibujo -> "dibujo"
                        is HojaWeb.Tabla -> "tabla"
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
                    // La barra de fórmula arriba, la tabla ya calculada en medio y lo escrito
                    // al final. Guardar reescribe las dos últimas: ver `paginaAnotada`.
                    is HojaWeb.Tabla -> {
                        append("<div class=\"tabla-fx\"><span class=\"tabla-dir\">A1</span>")
                        append("<input class=\"tabla-fx-in\" type=\"text\" spellcheck=\"false\" autocomplete=\"off\" ")
                        append("autocapitalize=\"off\" aria-label=\"Contenido de la celda\" placeholder=\"Valor o =SUMA(A1:A3)\"/></div>\n")
                        // La tinta va dentro de la caja que se desplaza, así se mueve con las celdas;
                        // su grupo es uno más de los que reescribe guardar.
                        append("<div class=\"tabla-caja\" tabindex=\"0\"><svg class=\"tinta\"><g class=\"origen\"><g id=\"")
                        append(ID_DEL_CROQUIS).append("\"></g></g></svg>")
                        append(VisorTabla.estatica(hoja.tabla)).append("</div>\n")
                        append("<script type=\"application/json\" class=\"tabla\">").append(VisorTabla.json(hoja.tabla)).append("</script>")
                    }
                }
                append("\n</div>\n")
            }
            append("</div>\n")
            append(barra(hojas.size > 1 && opciones.paginas, hayPintable, hayEspacio, hayPlano && opciones.capas, opciones, hayTabla))
            append("<script>")
            if (hayEspacio) append(VisorEspacio.JS)
            if (hayPlano) { append(VisorPlano.INFLAR); append(VisorPlano.JS) }
            if (hayDibujo) append(VISOR_DIBUJO)
            if (hayTabla) { append(VisorTabla.CALCULO); append(VisorTabla.JS) }
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
        o: Opciones = Opciones(),
        tabla: Boolean = false
    ): String = buildString {
        append("<div id=\"estado\" role=\"status\" aria-live=\"polite\"></div>\n")
        // La pastilla de la presentación. Ver `presentar` en el armazón.
        append("<div id=\"presentacion\" hidden>")
        append(boton("p-anterior", "Anterior (←)", "M15 5l-7 7 7 7"))
        append("<span id=\"p-cuenta\"></span>")
        append(boton("p-siguiente", "Siguiente (→)", "M9 5l7 7-7 7"))
        if (dibujo) {
            append("<i class=\"p-sep\"></i>")
            append("<button data-m=\"mano\" title=\"Pasar\" aria-label=\"Pasar\">" + icono("M8 13V5a2 2 0 1 1 4 0v6M12 11V4a2 2 0 1 1 4 0v7M16 12V6a2 2 0 1 1 4 0v8a7 7 0 0 1-7 7h-1a7 7 0 0 1-6-3.4L3.5 13a2 2 0 0 1 3.4-2.1L8 13") + "</button>")
            append("<button data-m=\"lapiz\" title=\"Lápiz\" aria-label=\"Lápiz\">" + icono("M4 20l4-1L19.5 7.5a2.1 2.1 0 0 0-3-3L5 16l-1 4zM14 6l4 4") + "</button>")
            append("<button data-m=\"marcador\" title=\"Resaltador\" aria-label=\"Resaltador\">" + icono("M4 20h16M6 16l8.5-8.5a2.1 2.1 0 0 1 3 3L9 19H6v-3zM13 8l3 3") + "</button>")
            append("<button data-m=\"goma\" title=\"Borrador\" aria-label=\"Borrador\">" + icono("M20 20H8M4.6 14.4l8.8-8.8a2 2 0 0 1 2.8 0l3.2 3.2a2 2 0 0 1 0 2.8L13 18H8.6l-4-4a1 1 0 0 1 0-1.4zM9.5 9.5l5 5") + "</button>")
        }
        append("<i class=\"p-sep\"></i>")
        append(boton("p-encajar", "Encajar (0)", "M4 9V5a1 1 0 0 1 1-1h4M15 4h4a1 1 0 0 1 1 1v4M20 15v4a1 1 0 0 1-1 1h-4M9 20H5a1 1 0 0 1-1-1v-4"))
        append(boton("p-salir", "Salir (Esc)", "M6 6l12 12M18 6L6 18"))
        append("</div>\n")
        append("<div id=\"cajon\" hidden></div>\n")
        // **Presentar e imprimir, arriba** (16-sep-2026, pedido por el usuario). Estaban en la
        // barra de abajo, entre las herramientas de dibujar, y son lo contrario: no se usan
        // mientras se dibuja, sino cuando ya está. Arriba, lejos de la mano, no se tocan sin
        // querer y se encuentran donde uno los busca.
        append("<div id=\"arriba\">")
        append(boton("presentar", "Presentar (F5)", "M3 4h18v12H3zM12 16v4M8 20h8M10 8l5 2.5-5 2.5z"))
        if (dibujo) {
            append(boton("marcar-zona", "Imprimir una zona", "M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M9 9h6v6H9z"))
        }
        append(boton("imprimir", "Imprimir (Ctrl+P)", "M7 8V3h10v5M7 17H4v-7h16v7h-3M7 14h10v7H7z"))
        append("</div>\n")
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
        // Pasar de página ya no va en la barra: lo hace el índice de arriba, que dice en qué hoja
        // se está y lleva a cualquiera (lo pidió el usuario el 13-sep-2026; dos sitios eran redundancia).
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
        // **Presentar e imprimir** (14-sep-2026), como en la aplicación: la hoja a pantalla
        // completa con una pastilla para pasar y anotar, y el diálogo de impresión del
        // navegador con una hoja por página.
        if (espacio) {
            append("<div class=\"grupo solo-espacio\">")
            append(boton("vistas", "Vistas", "M3 5h18v11H3zM8 20h8"))
            append(boton("grupos", "Grupos", "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"))
            // **La escena**: suelo, sombra, niebla, giradiscos, efectos… y la guía de gestos.
            // Lo que trae de serie el visor de la galería de Feather (6-sep-2026).
            append(boton("escena", "Escena (suelo, niebla, efectos)", "M4 7h10M18 7h2M4 12h3M11 12h9M4 17h13M21 17h-1M14 5v4M7 10v4M17 15v4"))
            append(boton("guia", "Guía de gestos (?)", "M9 9a3 3 0 1 1 4.5 2.6c-1 .6-1.5 1.2-1.5 2.4M12 17.5v.5M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"))
            append("</div>")
        }
        if (tabla) {
            // **La tabla**: deshacer, negrita, Σ, y el portapapeles con botón, que en un
            // teléfono no hay Ctrl+C. Y el CSV, que abre cualquier hoja de cálculo.
            // Deshacer: si ya está el de la tinta, ese mismo deshace también las celdas.
            val deshacerComun = dibujo && o.deshacer && (o.lapiz || o.resaltador || o.borrador)
            val primeros = StringBuilder()
            if (o.deshacer && !deshacerComun) {
                primeros.append(boton("t-deshacer", "Deshacer (Ctrl+Z)", "M9 14L4 9l5-5M4 9h9a6 6 0 0 1 0 12h-3"))
                primeros.append(boton("t-rehacer", "Rehacer (Ctrl+Y)", "M15 14l5-5-5-5M20 9h-9a6 6 0 0 0 0 12h3"))
            }
            // En solo lectura no se ofrece nada que cambie celdas. Ver [TABLAS_SOLO_VER].
            if (o.editarTablas) {
                primeros.append(boton("t-negrita", "Negrita (Ctrl+B)", "M7 5h6a4 4 0 0 1 0 8H7zM7 13h7a4 4 0 0 1 0 8H7z"))
                primeros.append(boton("t-suma", "Autosuma", "M18 5H6l6 7-6 7h12"))
            }
            if (primeros.isNotEmpty()) append("<div class=\"grupo solo-tabla\">").append(primeros).append("</div>")
            append("<div class=\"grupo solo-tabla\">")
            append(boton("t-copiar", "Copiar (Ctrl+C)", "M9 9h11v11H9zM5 15H4V4h11v1"))
            if (o.editarTablas) append(boton("t-pegar", "Pegar (Ctrl+V)", "M9 3h6v4H9zM7 5H5v16h14V5h-2M9 12h6M9 16h4"))
            append(boton("t-csv", "Bajar CSV", "M12 4v11M7 10l5 5 5-5M5 20h14"))
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
        html,body{margin:0;height:100%;background:FONDO;color:var(--tinta);
          font:14px/1.3 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
          -webkit-tap-highlight-color:transparent}
        /* **Todo lo que ata la página a la ventana cuelga de `html.vivo`**, y el guion se
           pone esa clase al arrancar. Sin guion —hay visores dentro de otras aplicaciones
           que abren un HTML con el guion apagado, y ahí se abren muchos de los que se
           mandan por mensajería— la página se queda como estaba: `overflow:hidden` y
           `touch-action:none` sobre el dibujo dejaban una lámina a pantalla completa **sin
           zoom, sin desplazamiento y sin nada que tocar**. Es justo lo que reportó el
           usuario el 8-sep-2026 al abrir uno reenviado. Así, sin guion, al menos queda un
           documento que se amplía con dos dedos y se pasea, que es lo que hace el navegador
           solo cuando se le deja. */
        html.vivo,html.vivo body{overflow:hidden;overscroll-behavior:none}
        #lienzo{position:fixed;inset:0}
        .hoja{position:absolute;inset:0}
        .hoja[hidden]{display:none}
        /* Solo el SVG de la hoja, no los que lleva dentro: un sublienzo es un `<svg>` anidado con su
           propio tamaño, y con `#lienzo svg` algunos navegadores lo estiraban a toda la hoja. */
        #lienzo .hoja>svg{width:100%;height:100%;display:block;
          user-select:none;-webkit-user-select:none}
        html.vivo #lienzo svg{touch-action:none;cursor:grab}
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
        canvas.espacio{position:absolute;inset:0;display:block}
        html.vivo canvas.espacio{touch-action:none;cursor:grab}
        /* La portada del croquis, encima hasta el primer fotograma. Ver VisorEspacio. */
        img.portada{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;
          pointer-events:none;transition:opacity .3s;z-index:2}
        #guia-caja{position:fixed;inset:0;z-index:8;background:rgba(0,0,0,.45);display:flex;
          align-items:center;justify-content:center;padding:16px}
        .guia{width:min(360px,100%);max-height:86vh;overflow:auto;padding:14px 16px;border-radius:18px;
          background:var(--vidrio);border:1px solid var(--filete);box-shadow:0 12px 40px rgba(0,0,0,.3);
          backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px)}
        .guia h2{margin:0 0 6px;font-size:16px}
        .guia h3{margin:12px 0 4px;font-size:11px;letter-spacing:.09em;text-transform:uppercase;opacity:.6}
        .guia .par{display:flex;justify-content:space-between;gap:12px;padding:5px 0;
          border-bottom:1px solid var(--filete);font-size:14px}
        .guia .par span:last-child{opacity:.75;text-align:right}
        .guia .cerrar{margin-top:14px;width:100%;padding:10px;border:none;border-radius:12px;
          background:#e03131;color:#fff;font:inherit;cursor:pointer}
        /* El plano vectorial va debajo del SVG y no recibe el dedo: el que manda sigue siendo
           el SVG, que es donde se raya. Sin el `z-index` el lienzo, que va posicionado, se
           pintaría por encima del SVG, que no lo va. */
        /* El lienzo del plano es **más grande que la ventana** y sobresale por los cuatro
           lados: así, al mover, lo que asoma ya está pintado. Su sitio y su tamaño los pone
           el visor. Ver [VisorPlano]. */
        canvas.plano{position:absolute;left:0;top:0;display:block;pointer-events:none;z-index:0}
        /* La raya mientras se escribe, encima de todo y sin coger el dedo. Ver la tinta viva. */
        canvas.tinta-viva{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:3}
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
        /* **La cota.** Naranja para que no se confunda con el dibujo, y con el grosor y la
           letra medidos en pantalla (`non-scaling-stroke`) para que se lean igual de cerca que
           de lejos. La cifra lleva un reborde del color del papel: sobre un plano lleno de
           líneas, un número sin reborde no se lee. */
        #medida{pointer-events:none}
        #medida line{stroke:#ff8a3d;stroke-width:2px;vector-effect:non-scaling-stroke}
        #medida path{fill:#ff8a3d;stroke:none}
        #medida .pendiente{fill:none;stroke:#ff8a3d;stroke-width:2px;vector-effect:non-scaling-stroke}
        #medida text{fill:#ff8a3d;text-anchor:middle;paint-order:stroke;
          stroke:var(--papel,#fff);stroke-width:4px;vector-effect:non-scaling-stroke;
          font-weight:600;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
        #medida .viva line{stroke:#ff5722;stroke-width:3px}
        #medida .viva text{fill:#ff5722}
        .grupo+.grupo{border-left:1px solid var(--filete);padding-left:6px;margin-left:2px}
        #barra button,#arriba button{width:40px;height:40px;border:none;border-radius:11px;background:transparent;
          color:inherit;display:inline-flex;align-items:center;justify-content:center;
          cursor:pointer;touch-action:manipulation;padding:0}
        #barra button:hover,#arriba button:hover{background:var(--filete)}
        #barra button:active,#arriba button:active{transform:scale(.93)}
        #barra button.activo,#arriba button.activo{background:#e03131;color:#fff}
        #barra button:disabled,#arriba button:disabled{opacity:.35;cursor:default}
        /* Los botones del zoom sobran con rueda, pero en el espacio y con el dedo son la
           única forma de acercarse sin usar las dos manos. */
        @media (pointer:coarse){
          .solo-raton{display:none}
          body.enElEspacio #mas,body.enElEspacio #menos{display:inline-flex}
        }
        /* **Color y grosor en la misma línea** (16-sep-2026, pedido del usuario): el grosor
           estaba en una fila debajo, y son dos mitades de la misma decisión —con qué pinto—.
           En una pantalla estrecha la fila se parte sola, que es lo que hace `wrap`. */
        #paleta{display:flex;flex-direction:row;flex-wrap:wrap;gap:6px;align-items:center;justify-content:center}
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
        /* **Arriba a la derecha**: presentar, marcar una zona e imprimir. Ver [barra]. */
        /* Mismo cristal, mismo filete, mismos botones que la barra de abajo: son la misma
           interfaz en dos sitios, y con dos pintas distintas parecen dos aplicaciones
           (lo dijo el usuario el 16-sep-2026). */
        #arriba{position:fixed;top:calc(12px + env(safe-area-inset-top));right:12px;z-index:6;display:flex;gap:6px;
          padding:6px;border-radius:16px;background:var(--vidrio);border:1px solid var(--filete);
          box-shadow:0 6px 24px rgba(0,0,0,.18);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);
          color:var(--tinta)}
        /* Mientras se marca la zona a imprimir: el dedo marca, no dibuja. */
        html.marcando #lienzo{cursor:crosshair}
        #zona-marca{position:fixed;z-index:7;border:2px dashed var(--tinta,#3b82f6);
          background:rgba(59,130,246,.12);pointer-events:none;border-radius:4px}
        #indice-fijo{position:fixed;top:12px;left:12px;z-index:5;max-width:240px;max-height:70vh;overflow:auto;
          padding:4px 6px;border-radius:14px;background:var(--vidrio);border:1px solid var(--filete);
          box-shadow:0 6px 24px rgba(0,0,0,.12);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
        #indice-fijo summary{cursor:pointer;font-weight:600;padding:6px 8px;list-style:none;white-space:nowrap;
          display:flex;align-items:center;gap:10px;max-width:228px}
        #indice-fijo summary .titulo{overflow:hidden;text-overflow:ellipsis}
        #indice-fijo summary .cuenta{opacity:.55;font-weight:500;font-size:12px;flex:none}
        #indice-fijo summary::after{content:"▾";opacity:.6;flex:none}
        #indice-fijo[open] summary::after{content:"▴"}
        #indice-fijo summary::-webkit-details-marker{display:none}
        #indice-fijo a{display:block;padding:6px 8px;border-radius:9px;color:inherit;text-decoration:none;
          white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        #indice-fijo a b{opacity:.5;font-weight:600;margin-right:4px}
        #indice-fijo a:hover{background:var(--filete)}
        #indice-fijo a.activo{background:#e03131;color:#fff}
        #indice-fijo a.activo b{opacity:.8}
        #pizarra:not(.varias) #anterior,#pizarra:not(.varias) #indice,
        #pizarra:not(.varias) #siguiente{display:none}
        @media (max-width:600px){
          #cajon{top:auto;left:8px;right:8px;width:auto;max-height:52vh;
            bottom:calc(116px + env(safe-area-inset-bottom))}
        }
        @media (min-width:900px){#barra button,#arriba button{width:38px;height:38px}}
        /* **Presentar**: solo la hoja y la pastilla. */
        #presentacion{position:fixed;left:50%;bottom:calc(16px + env(safe-area-inset-bottom));
          transform:translateX(-50%);z-index:20;display:flex;align-items:center;gap:2px;padding:4px 6px;
          border-radius:24px;background:rgba(0,0,0,.66);color:#fff;transition:opacity .2s}
        #presentacion[hidden]{display:none}
        #presentacion.escondida{opacity:0;pointer-events:none}
        #presentacion button{width:40px;height:40px;border:none;border-radius:20px;background:transparent;
          color:#fff;display:grid;place-items:center;cursor:pointer;padding:0}
        #presentacion button svg{width:22px;height:22px}
        #presentacion button.activo{background:rgba(255,255,255,.28)}
        #presentacion button:disabled{opacity:.35}
        #p-cuenta{font-size:14px;min-width:52px;text-align:center}
        .p-sep{width:1px;height:22px;background:rgba(255,255,255,.3);margin:0 4px}
        /* **Presentando, nada de desenfoque de fondo.**
           En pantalla completa, Chromium —y con él Brave, que es donde lo vio el usuario el
           16-sep-2026— compone mal las capas con `backdrop-filter`: al aparecer o desaparecer
           una (la paleta, que se va al coger el borrador) **pinta negro toda la pantalla** y el
           dibujo deja de verse hasta salir. El desenfoque es un adorno; el dibujo, no. Se
           cambia por un fondo sólido, que además se lee mejor sobre una diapositiva. */
        html.presentando #colores,html.presentando #grosores{
          backdrop-filter:none!important;-webkit-backdrop-filter:none!important;
          background:rgba(0,0,0,.72)!important;color:#fff;border-color:rgba(255,255,255,.18)!important}
        html.presentando #arriba,
        html.presentando #barra,html.presentando #pizarra>*:not(#presentacion):not(#paleta),html.presentando #indice-fijo,
          html.presentando #estado,html.presentando #cajon,html.presentando #aviso{display:none!important}
        /* **Imprimir**: una hoja por página, sin mandos. */
        @media print{
          @page{margin:10mm}
          html.vivo,html.vivo body,html,body{overflow:visible!important;height:auto!important;background:#fff!important}
          #barra,#pizarra,#estado,#cajon,#indice-fijo,#paleta,#aviso,#presentacion,#arriba,#zona-marca{display:none!important}
          #lienzo{position:static!important}
          .hoja,.hoja[hidden]{position:relative!important;display:block!important;inset:auto!important;
            width:100%!important;height:auto!important;page-break-after:always;break-after:page;overflow:visible!important}
          #lienzo .hoja>svg{width:100%!important;height:auto!important;max-height:95vh}
          html.imprimir-una .hoja:not(.a-imprimir){display:none!important}
          .hoja:last-child{page-break-after:auto;break-after:auto}
        }
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
// Medir: cotas de plano —dos puntas, flechas y la cifra encima—, y se quedan puestas.
// Ver la sección «Medir» más abajo.
var cotas=[], medida=null, grupoMedida=null, agarreCota=null;
var escala=parseFloat(caja.dataset.escala)||0, unidad=caja.dataset.unidad||'', decimales=parseInt(caja.dataset.decimales)||2;
// El papel, si vino como geometría en vez de como foto. Ver [VisorPlano].
var plano=(typeof crearPlano==='function')?crearPlano(caja,{encuadrar:encuadrarEn}):null;

// **Un encuadre por fotograma.** Dos dedos mandan dos `pointermove` por fotograma y la rueda
// del ratón o la tableta, más; cambiar el `viewBox` repinta la hoja entera, así que se apunta
// que hace falta y se hace una vez, justo antes de pintar. [v] cambia en el acto: las cuentas
// de dónde cae el dedo siguen siendo exactas.
var fotograma=0;
function aplicar(){
  if(fotograma) return;
  fotograma=requestAnimationFrame(aplicarYa);
}
function aplicarYa(){
  if(fotograma){ cancelAnimationFrame(fotograma); fotograma=0; }
  svg.setAttribute('viewBox',v.x+' '+v.y+' '+v.w+' '+v.h);
  if(plano) plano.ver(v);
  // Las cotas se miden en pantalla —la flecha mide lo mismo de cerca que de lejos—, así que
  // cambiar el encuadre obliga a rehacerlas. Ver [pintarMedida].
  if(grupoMedida&&(cotas.length||medida)) pintarMedida();
  // Un trazo a medias se había dibujado con el encuadre de antes: se vuelve a dibujar.
  if(trazo) repintarTintaViva();
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
// Las rayas son **la línea tal cual la trazó el lápiz**: `M` y una `L` con todas las muestras.
// Las de antes del 14-sep-2026 eran curvas por los puntos medios —el punto de partida, el
// control de cada Q y la L final son las muestras— y se leen igual, como las polilíneas.
function leerPuntos(r){
  var lista=[], i;
  if(r.localName==='path'){
    var d=r.getAttribute('d')||'';
    var re=/([MLQ])([^MLQ]*)/g, m, ultimaL=null, curvas=d.indexOf('Q')>=0;
    while((m=re.exec(d))){
      var n=m[2].trim().split(/[\s,]+/).map(Number);
      if(m[1]==='M'&&n.length>=2)lista.push({x:n[0],y:n[1]});
      else if(m[1]==='Q'&&n.length>=4)lista.push({x:n[0],y:n[1]});
      else if(m[1]==='L'&&curvas&&n.length>=2)ultimaL={x:n[0],y:n[1]};
      else if(m[1]==='L')for(var j=0;j+1<n.length;j+=2)lista.push({x:n[j],y:n[j+1]});
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
  olvidarLosImanes();
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
// **La raya es lo que puso el lápiz, sin arreglos** (lo pidió el usuario el 14-sep-2026: con
// curvas por los puntos medios y la cola prevista, lo escrito «se movía» y parecía corregido).
// Cada muestra es un vértice, y entre dos, una recta.
//
// Y **ligera**: cada número lleva los decimales que hacen falta para medio píxel de pantalla
// en el aumento con el que se trazó, ni uno más, y la `L` se escribe una sola vez.
function redondeo(k){ return Math.pow(10,Math.max(0,Math.min(3,Math.ceil(-Math.log10(k*0.5))))); }
function anadirPunto(t,p){
  var pts=t.puntos, n=pts.length, f=t.f||(t.f=redondeo(t.m?t.m.k:1));
  pts.push(p);
  var x=Math.round(p.x*f)/f, y=Math.round(p.y*f)/f;
  if(n===0){t.abierto='M'+x+' '+y;return;}
  t.abierto+=(n===1?'L':' ')+x+' '+y;
}
// ---- La tinta viva ----
//
// **Mientras se escribe, la raya no está en el SVG.** Cambiar el `d` de un camino obliga al
// navegador a repintar la hoja entera —con un plano o una foto grande debajo, eso es cada
// muestra del lápiz— y el trazo se quedaba atrás de la punta. Así que la raya viva se dibuja
// en un lienzo transparente encima, **solo el tramo nuevo** cada vez, y al levantar el lápiz
// pasa al SVG de una vez. Con `desynchronized` el navegador lo enseña sin esperar al resto de
// la página, que es lo que usan las pizarras para ir pegadas a la punta. Se dibuja **lo mismo**
// que irá al SVG —rectas entre las muestras—, así que al soltar no cambia nada. Pedido el 13-sep-2026, probando con tableta gráfica. **Sin predecir** hacia dónde va la punta:
// esa cola se corregía en cada muestra y lo escrito parecía moverse (14-sep-2026).
var viva=null, vctx=null;
function prepararTintaViva(marca){
  if(!viva){
    viva=document.createElement('canvas');
    viva.className='tinta-viva';
    caja.appendChild(viva);
    try{ vctx=viva.getContext('2d',{desynchronized:true}); }catch(err){}
    vctx=vctx||viva.getContext('2d');
  }
  var r=caja.getBoundingClientRect(), dpr=window.devicePixelRatio||1;
  var w=Math.max(1,Math.round(r.width*dpr)), h=Math.max(1,Math.round(r.height*dpr));
  if(viva.width!==w||viva.height!==h){ viva.width=w; viva.height=h; }
  vctx.setTransform(dpr,0,0,dpr,-r.left*dpr,-r.top*dpr);
  vctx.clearRect(r.left,r.top,r.width,r.height);
  vctx.lineCap='round'; vctx.lineJoin='round';
  // El resaltador es translúcido y se funde con el papel: el lienzo entero, no cada tramo,
  // que si no los tramos se pisan y salen cuentas más oscuras.
  viva.style.opacity=marca?'0.55':'1';
  viva.style.mixBlendMode=marca?'var(--fusion)':'normal';
  return r;
}
function limpiarTintaViva(){
  if(!viva) return;
  vctx.setTransform(1,0,0,1,0,0); vctx.clearRect(0,0,viva.width,viva.height);
}
function estiloVivo(g,t){ g.strokeStyle=t.color; g.lineWidth=t.ancho; }
// El tramo que añade la muestra [n] de la raya: la misma recta que irá al SVG.
function tramoVivo(t,n){
  var q=t.pan, g=vctx;
  estiloVivo(g,t);
  g.beginPath();
  g.moveTo(q[n-1].x,q[n-1].y);
  g.lineTo(q[n].x,q[n].y);
  g.stroke();
}
// El punto del principio, para que un toque se vea mientras el lápiz sigue apoyado.
function colaViva(t){
  if(t.pan.length!==1) return;
  var g=vctx, q=t.pan[0];
  estiloVivo(g,t);
  g.beginPath(); g.moveTo(q.x,q.y); g.lineTo(q.x+0.01,q.y); g.stroke();
}
// El encuadre cambió con la raya a medias (un dedo pellizcando mientras escribe el lápiz): la
// raya se vuelve a poner en pantalla desde sus puntos del dibujo, que son los que valen.
function repintarTintaViva(){
  if(!trazo||!viva) return;
  var m=marco();
  trazo.r=prepararTintaViva(trazo.marca);
  trazo.m=m;
  trazo.ancho=trazo.anchoDibujo/m.k;
  trazo.pan=trazo.puntos.map(function(p){ return {x:(p.x-m.ox)/m.k, y:(p.y-m.oy)/m.k}; });
  colaViva(trazo);
  for(var i=1;i<trazo.pan.length;i++) tramoVivo(trazo,i);
}
function pintarTrazo(t){ t.setAttribute('d',t.abierto); }
// El navegador junta en un solo pointermove todas las muestras llegadas desde el fotograma
// anterior; un ratón da cientos por segundo. Sin pedirlas, una raya rápida salía a tramos.
function muestras(e){
  var lote=(e.getCoalescedEvents&&e.getCoalescedEvents())||[];
  return lote.length?lote:[e];
}
function empezarTrazo(px,py,marca,puntero){
  // **El marco se mide una vez por raya**: medirlo en cada muestra era preguntarle al
  // navegador dónde está el SVG cientos de veces por segundo. Si el encuadre cambia a mitad,
  // [aplicarYa] lo vuelve a medir.
  var m=marco(), p=aEscena(px,py,m);
  var ancho=marca?api.grosor()*3:api.grosor();
  trazo=document.createElementNS(NS,'path');
  trazo.puntero=puntero;
  trazo.setAttribute('fill','none');
  trazo.setAttribute('stroke',api.color());
  trazo.setAttribute('stroke-width',r3(ancho*p.k));
  trazo.setAttribute('stroke-linecap','round');
  trazo.setAttribute('stroke-linejoin','round');
  if(marca){trazo.setAttribute('stroke-opacity','0.55');trazo.setAttribute('class','marca');}
  trazo.puntos=[];trazo.ultimo={x:px,y:py};
  trazo.m=m; trazo.marca=marca; trazo.color=api.color(); trazo.ancho=ancho; trazo.anchoDibujo=ancho*p.k;
  trazo.pan=[{x:px,y:py}];
  anadirPunto(trazo,{x:p.x,y:p.y});
  trazo.r=prepararTintaViva(marca);
  colaViva(trazo);
}
/** Una muestra más de la raya viva, en píxeles de pantalla. */
function seguirTrazo(cx,cy){
  if(Math.hypot(cx-trazo.ultimo.x,cy-trazo.ultimo.y)<0.5) return false; // menos de medio píxel no se ve
  trazo.ultimo={x:cx,y:cy};
  anadirPunto(trazo,aEscena(cx,cy,trazo.m));
  trazo.pan.push({x:cx,y:cy});
  tramoVivo(trazo,trazo.pan.length-1);
  return true;
}
function soltarTrazo(){
  if(!trazo)return;
  // El dibujo ha cambiado: el imán tiene que volver a mirarlo. Ver [imanesDeLaHoja].
  olvidarLosImanes();
  var t=trazo;
  trazo=null;
  if(t.puntos.length>=2){        // un punto solo no se ve
    pintarTrazo(t);
    croquis.appendChild(t);
    apuntar({que:'pinta',raya:t});
  }
  // La tinta viva se borra **cuando el SVG ya enseña la raya**, dos fotogramas después; si se
  // borrase a la vez, se vería un parpadeo. Si mientras tanto empezó otra raya, se redibuja.
  requestAnimationFrame(function(){ requestAnimationFrame(function(){
    limpiarTintaViva();
    if(trazo) repintarTintaViva();
  }); });
}
// ---- Medir: cotas de plano ----
//
// **Una cota, no una cifra suelta.** Antes medir eran dos puntos gordos, una raya y un número
// en la barra de estado, y la medida siguiente borraba la anterior. En un plano eso no sirve:
// se miden varias cosas y se comparan, y la cifra tiene que estar **donde está lo medido**.
// Ahora cada medida es una cota como la de un plano —línea, flecha en cada punta y la cifra
// encima, en su sitio— que **se queda puesta**, se puede coger y mover, y se pega a las líneas
// del dibujo. Lo pidió el usuario el 8-sep-2026.
//
// Todo se guarda en unidades del dibujo y se pinta contando el aumento, para que la flecha y
// la letra midan lo mismo de cerca que de lejos. Por eso [aplicar] repinta al encuadrar.

/** Lo que mide una cota, ya escrito: en la unidad del dibujo si se calibró, en píxeles si no. */
function textoDeCota(c){
  var d=Math.hypot(c.b.x-c.a.x, c.b.y-c.a.y);
  return escala>0 ? (d*escala).toFixed(decimales)+' '+unidad : d.toFixed(1)+' px';
}
/** Una flecha en la punta (x,y), apuntando desde (dx,dy), del tamaño [t] en unidades. */
function flechaDe(x,y,dx,dy,t){
  var l=Math.hypot(dx,dy)||1, ux=dx/l, uy=dy/l, px=-uy, py=ux;
  var f=document.createElementNS(NS,'path');
  f.setAttribute('d','M'+x+' '+y+'L'+(x+ux*t+px*t*0.36)+' '+(y+uy*t+py*t*0.36)+
                     'L'+(x+ux*t-px*t*0.36)+' '+(y+uy*t-py*t*0.36)+'Z');
  return f;
}
function pintarMedida(){
  if(!grupoMedida){ grupoMedida=document.createElementNS(NS,'g'); grupoMedida.id='medida'; svg.appendChild(grupoMedida); }
  while(grupoMedida.firstChild) grupoMedida.removeChild(grupoMedida.firstChild);
  var k=aEscena(0,0).k;                 // unidades del dibujo por píxel de pantalla
  var todas=cotas.concat(medida&&medida.b?[medida]:[]);
  for(var i=0;i<todas.length;i++) pintarUnaCota(todas[i], k, i===agarreCota&&agarreCota!==null);
  // El primer punto de una cota a medias: un aspa, que no es una cota todavía.
  if(medida&&!medida.b){
    var t=7*k, a=medida.a;
    var x=document.createElementNS(NS,'path');
    x.setAttribute('d','M'+(a.x-t)+' '+a.y+'h'+(2*t)+'M'+a.x+' '+(a.y-t)+'v'+(2*t));
    x.setAttribute('class','pendiente');
    grupoMedida.appendChild(x);
  }
}
function pintarUnaCota(c, k, viva){
  var g=document.createElementNS(NS,'g');
  g.setAttribute('class','cota'+(viva?' viva':''));
  var dx=c.b.x-c.a.x, dy=c.b.y-c.a.y, l=Math.hypot(dx,dy);
  var linea=document.createElementNS(NS,'line');
  linea.setAttribute('x1',c.a.x); linea.setAttribute('y1',c.a.y);
  linea.setAttribute('x2',c.b.x); linea.setAttribute('y2',c.b.y);
  g.appendChild(linea);
  // Las flechas solo si la cota da para ellas: en una cota más corta que su propia punta,
  // dos flechas encontradas son una mancha y no se entiende nada.
  var t=9*k;
  if(l>t*2.4){
    g.appendChild(flechaDe(c.a.x,c.a.y, dx, dy, t));
    g.appendChild(flechaDe(c.b.x,c.b.y,-dx,-dy, t));
  }
  // La cifra, **encima de la línea y girada con ella**, como en un plano. Y siempre legible:
  // pasado el vertical se lee del revés, así que se le da la vuelta.
  var gr=Math.atan2(dy,dx)*180/Math.PI;
  if(gr>90||gr<-90) gr+=180;
  var mx=(c.a.x+c.b.x)/2, my=(c.a.y+c.b.y)/2;
  var texto=document.createElementNS(NS,'text');
  texto.setAttribute('x',mx); texto.setAttribute('y',my-5*k);
  texto.setAttribute('transform','rotate('+gr+' '+mx+' '+my+')');
  texto.setAttribute('font-size',(13*k));
  texto.textContent=textoDeCota(c);
  g.appendChild(texto);
  grupoMedida.appendChild(g);
}

/**
 * **Medir es libre**: el punto va exactamente donde se toca. Hubo un imán que pegaba el punto a
 * la geometría cercana, y en un plano pegaba a puntos que no se ven —muestras de una diagonal,
 * esquinas que no eran la buscada—; lo quitó el usuario el 14-sep-2026.
 */
function imantar(p){ return {x:p.x,y:p.y}; }
function olvidarLosImanes(){}

/** Qué cota y qué parte de ella cae bajo el dedo: una punta, o su mitad para moverla entera. */
function cotaBajoElDedo(p,k){
  var r=16*k, rr=r*r;
  for(var i=cotas.length-1;i>=0;i--){    // la de encima primero, que es la última puesta
    var c=cotas[i];
    if((c.a.x-p.x)*(c.a.x-p.x)+(c.a.y-p.y)*(c.a.y-p.y)<rr) return {i:i,parte:'a'};
    if((c.b.x-p.x)*(c.b.x-p.x)+(c.b.y-p.y)*(c.b.y-p.y)<rr) return {i:i,parte:'b'};
    // El cuerpo: distancia al segmento, para poder arrastrarla entera.
    var dx=c.b.x-c.a.x, dy=c.b.y-c.a.y, ll=dx*dx+dy*dy;
    if(!ll) continue;
    var t=((p.x-c.a.x)*dx+(p.y-c.a.y)*dy)/ll;
    if(t<0.18||t>0.82) continue;         // cerca de las puntas manda la punta
    var qx=c.a.x+dx*t, qy=c.a.y+dy*t;
    if((qx-p.x)*(qx-p.x)+(qy-p.y)*(qy-p.y)<rr) return {i:i,parte:'todo',t:t};
  }
  return null;
}

/**
 * Coger una cota que ya está puesta. Devuelve `true` si la ha cogido.
 *
 * Se guarda **dónde estaba** para poder devolverla si el gesto resulta no ser un arrastre
 * —por ejemplo si aparece un segundo dedo y lo que se quería era hacer zoom—. Ver
 * [cancelarArrastreDeCota].
 */
function cogerCotaEn(px,py){
  var k=aEscena(0,0).k, p=aEscena(px,py);
  var cogida=cotaBajoElDedo(p,k);
  if(!cogida) return false;
  var c=cotas[cogida.i];
  agarreCota=cogida.i;
  arrastreDeCota={i:cogida.i,parte:cogida.parte,x:p.x,y:p.y,
                  antes:{a:{x:c.a.x,y:c.a.y},b:{x:c.b.x,y:c.b.y}}};
  return true;
}
/** Devolver la cota a donde estaba y soltarla, sin dar la medida por buena. */
function cancelarArrastreDeCota(){
  if(!arrastreDeCota) return;
  var c=cotas[arrastreDeCota.i];
  if(c&&arrastreDeCota.antes){ c.a=arrastreDeCota.antes.a; c.b=arrastreDeCota.antes.b; }
  arrastreDeCota=null; agarreCota=null; pintarMedida();
}
/** Poner un punto de medida donde se ha tocado. */
function ponerPuntoDeMedida(px,py){
  var k=aEscena(0,0).k, p=aEscena(px,py);
  if(!medida){                       // primer punto
    medida={a:imantar(p,k)};
    api.decir('Primer punto puesto: toca el segundo');
  } else {                           // segundo: la cota se queda
    medida.b=imantar(p,k);
    if(Math.hypot(medida.b.x-medida.a.x,medida.b.y-medida.a.y)>0.0001){
      cotas.push(medida);
      api.decir(textoDeCota(medida)+'  ·  toca una cota para moverla');
    }
    medida=null;
  }
  pintarMedida();
}
var arrastreDeCota=null;
function arrastrarCota(px,py){
  if(!arrastreDeCota) return;
  var k=aEscena(0,0).k, p=aEscena(px,py), c=cotas[arrastreDeCota.i];
  if(!c) return;
  if(arrastreDeCota.parte==='todo'){
    var dx=p.x-arrastreDeCota.x, dy=p.y-arrastreDeCota.y;
    c.a={x:c.a.x+dx,y:c.a.y+dy}; c.b={x:c.b.x+dx,y:c.b.y+dy};
    arrastreDeCota.x=p.x; arrastreDeCota.y=p.y;
  } else {
    // La punta que se mueve sí se imanta; la cota entera no, que se movería a saltos.
    c[arrastreDeCota.parte]=imantar(p,k);
  }
  pintarMedida();
}
function soltarCota(){
  if(!arrastreDeCota) return;
  var c=cotas[arrastreDeCota.i];
  // Una cota arrastrada hasta quedar en un punto se ha querido borrar.
  if(c&&Math.hypot(c.b.x-c.a.x,c.b.y-c.a.y)<aEscena(0,0).k*6){
    cotas.splice(arrastreDeCota.i,1); api.decir('Cota quitada');
  } else if(c) api.decir(textoDeCota(c));
  arrastreDeCota=null; agarreCota=null; pintarMedida();
}
/** Quitarlas todas. Es lo que hace salir de medir, y la tecla de escape. */
function quitarMedida(){ cotas=[]; medida=null; arrastreDeCota=null; agarreCota=null; if(grupoMedida) pintarMedida(); }

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
    // **El lápiz sí mide al posarse**: es una punta, no pellizca, y esperar al levantarlo
    // solo lo haría torpe. Lo que espera es el dedo. Ver [tocaMedir].
    else if(q==='medir'){ if(cogerCotaEn(e.clientX,e.clientY)) cotaConElDedo=e.pointerId; else ponerPuntoDeMedida(e.clientX,e.clientY); }
    else if(!arrastre){ arrastre={id:e.pointerId,x:e.clientX,y:e.clientY}; api.agarrado(true); }
    return;
  }
  if(dd.length===1){
    var q1=loQueHace(e);
    if(trazo) return;   // el lápiz está escribiendo: el dedo no le quita el trazo
    if(q1==='lapiz'||q1==='marcador') empezarTrazo(e.clientX,e.clientY,q1==='marcador',e.pointerId);
    else if(q1==='goma') borrarEn(e.clientX,e.clientY);
    // **Midiendo, el dedo no hace nada todavía.**
    //
    // El primer dedo de un pellizco es idéntico a un toque: no hay forma de distinguirlos
    // hasta que pasa algo más —llega el segundo dedo, se mueve, o se levanta—. Poniendo el
    // punto al posarse, cada zoom de dos dedos dejaba una medida a medias sin querer, que es
    // lo que reportó el usuario el 9-sep-2026. Así que se apunta lo que ha pasado y **se
    // decide al levantar**: si no llegó un segundo dedo y no se movió, es un toque y pone
    // punto. Ver [soltar].
    else if(q1==='medir'){
      tocaMedir={id:e.pointerId, x:e.clientX, y:e.clientY, movido:false, cogida:false};
      if(cogerCotaEn(e.clientX,e.clientY)){ tocaMedir.cogida=true; cotaConElDedo=e.pointerId; }
    }
    else { arrastre={id:e.pointerId,x:e.clientX,y:e.clientY}; api.agarrado(true); }
  } else if(dd.length===2){
    // El segundo dedo encuadra: un trazo del dedo a medias se queda como iba; el del lápiz
    // sigue, que es de otra mano.
    arrastre=null;
    if(trazo&&trazo.puntero!==undefined&&dedos.get(trazo.puntero)&&dedos.get(trazo.puntero).tipo!=='pen') soltarTrazo();
    // **Esto era un pellizco desde el principio.** Se deshace lo que el primer dedo hubiera
    // empezado a medir: la cota que hubiera cogido vuelve a donde estaba y no se pone punto.
    if(tocaMedir){
      if(tocaMedir.cogida) cancelarArrastreDeCota();
      cotaConElDedo=null; tocaMedir=null;
    }
    pellizco={d:Math.hypot(dd[0].x-dd[1].x,dd[0].y-dd[1].y)};
  }
});
caja.addEventListener('pointermove',function(e){
  if(!dedos.has(e.pointerId))return;
  var d0=dedos.get(e.pointerId); d0.x=e.clientX; d0.y=e.clientY;
  var dd=losDedos();
  if(pellizco&&dd.length===2&&e.pointerType!=='pen'){
    var dist=Math.hypot(dd[0].x-dd[1].x,dd[0].y-dd[1].y);
    // **La pinza no puede dar saltos** (16-sep-2026). Con la mano apoyada —presentando con la
    // mano o con el borrador, que es cuando el dedo no dibuja— el navegador manda dos puntos
    // que aparecen, desaparecen y saltan de un lado a otro de la pantalla: la razón entre dos
    // medidas seguidas se iba a cien, el encuadre salía disparado a kilómetros del dibujo y la
    // pantalla se quedaba vacía. Un pellizco de verdad, entre dos avisos, no dobla ni parte por
    // la mitad la distancia; lo que pase de ahí es la palma y se ignora.
    if(dist>0&&pellizco.d>0){
      var f=pellizco.d/dist;
      if(f>=0.5&&f<=2) zoom(f,(dd[0].x+dd[1].x)/2,(dd[0].y+dd[1].y)/2);
    }
    pellizco.d=dist;
  } else if(trazo&&trazo.puntero===e.pointerId){
    var lote=muestras(e);
    for(var i=0;i<lote.length;i++) seguirTrazo(lote[i].clientX,lote[i].clientY);
  } else if(!trazo&&!pellizco&&loQueHace(e)==='goma'){
    var mg=marco(), lg=muestras(e);
    for(var g=0;g<lg.length;g++)borrarEn(lg[g].clientX,lg[g].clientY,mg);
  } else if(tocaMedir&&tocaMedir.id===e.pointerId&&!pellizco){
    // Hasta que no se mueva de verdad no es un arrastre: un dedo tiembla, y un temblor no
    // puede convertir un toque en otra cosa. El umbral se mide en pantalla.
    if(!tocaMedir.movido&&Math.hypot(e.clientX-tocaMedir.x,e.clientY-tocaMedir.y)>8) tocaMedir.movido=true;
    if(tocaMedir.movido){
      if(tocaMedir.cogida) arrastrarCota(e.clientX,e.clientY);
      else {
        // **Y midiendo, un dedo que arrastra pasea el papel**, como en cualquier otro modo.
        // Antes no hacía nada: para moverse por el plano había que salir de medir.
        var km=aEscena(0,0).k;
        v.x-=(e.clientX-tocaMedir.x)*km; v.y-=(e.clientY-tocaMedir.y)*km;
        tocaMedir.x=e.clientX; tocaMedir.y=e.clientY;
        aplicar();
      }
    }
  } else if(cotaConElDedo===e.pointerId){
    arrastrarCota(e.clientX,e.clientY);
  } else if(arrastrando(e)){
    var k=aEscena(0,0).k;
    v.x-=(e.clientX-arrastre.x)*k; v.y-=(e.clientY-arrastre.y)*k;
    arrastre.x=e.clientX; arrastre.y=e.clientY;
    aplicar();
  }
});
// Qué dedo trae una cota cogida: mientras la trae, ese dedo no pasea el papel.
var cotaConElDedo=null;
// El dedo que está midiendo, mientras no se sabe todavía qué quiere. Ver el `pointerdown`.
var tocaMedir=null;
function soltar(e){
  dedos.delete(e.pointerId);
  if(tocaMedir&&tocaMedir.id===e.pointerId){
    // Ni segundo dedo ni movimiento: era un toque, y ahora sí se pone el punto.
    if(!tocaMedir.movido&&!tocaMedir.cogida) ponerPuntoDeMedida(e.clientX,e.clientY);
    else if(tocaMedir.cogida&&!tocaMedir.movido) cancelarArrastreDeCota();  // se rozó y ya
    tocaMedir=null;
  }
  if(cotaConElDedo===e.pointerId){ cotaConElDedo=null; soltarCota(); }
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
aplicarYa();
return {
 tipo:'dibujo',
 // Una hoja escondida mide cero: el plano se pinta al asomarse a ella, no antes.
 activar:function(){ aplicarYa(); },
 desactivar:function(){soltarTrazo();},
 medir:function(){ if(plano) plano.medir(); },
 capas:plano?plano.capas:null,
 soloLineas:plano?plano.soloLineas:null,
 esSoloLineas:plano?plano.esSoloLineas:null,
 hayRellenos:plano?plano.hayRellenos:null,
 encajar:encajar,
 zoom:function(f){var r=svg.getBoundingClientRect();zoom(f,r.left+r.width/2,r.top+r.height/2);},
 // El encuadre de ahora y cómo ponerlo: es lo que deja imprimir solo un trozo. Ver `marcarZona`.
 vista:function(){return {x:v.x,y:v.y,w:v.w,h:v.h};},
 // **¿Me he perdido?** Con la mano apoyada, presentando, salen pinzas involuntarias que
 // llevan el encuadre a kilómetros del dibujo: la pantalla se queda vacía —negra, con este
 // papel— y presentando no hay botón de encajar a mano, así que no había vuelta atrás
 // (usuario, 16-sep-2026). Esto dice si lo que se ve ya no toca al dibujo, o si lo que
 // queda de él en pantalla es una mota.
 perdido:function(){
   // **Perdido es no ver nada del dibujo**, y solo eso. Con un «casi nada» se colaban
   // encuadres legítimos —alejarse del todo, o meterse en un detalle— y la pantalla daba un
   // salto sola, que es peor que el problema.
   var ix=Math.min(v.x+v.w,casa.x+casa.w)-Math.max(v.x,casa.x);
   var iy=Math.min(v.y+v.h,casa.y+casa.h)-Math.max(v.y,casa.y);
   return ix<=0||iy<=0;
 },
 ponerVista:function(c){ v={x:c.x,y:c.y,w:c.w,h:c.h}; aplicar(); },
 deEscena:function(px,py){ return aEscena(px,py); },
 // **Salir de medir ya no borra las cotas**: para eso están puestas. Lo que se deja a medias
 // —un primer punto sin su pareja— sí se suelta, que no es nada todavía. Se quitan todas con
 // la tecla de escape, y una a una arrastrando su punta sobre la otra. Ver [quitarMedida].
 modo:function(m){ modo=m; if(m!=='medir'){ medida=null; if(grupoMedida) pintarMedida(); } else api.decir('Toca dos puntos: la cota se queda puesta'); },
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
// **La página se declara viva aquí y no antes.** De esta clase cuelga todo lo que le quita
// el control al navegador —el recorte a la ventana y el `touch-action` del dibujo—, así que
// se pone cuando ya se sabe que hay armazón que lo sustituya. Ver la hoja de estilo.
document.documentElement.className+=' vivo';
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
  if(d.dataset.tipo==='tabla') return crearTabla(d, api);
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
  // Lo que puso el lápiz, recta a recta, y a medio píxel: ver `anadirPunto` del dibujo.
  function anadirPunto(t,p){
    var pts=t.puntos, n=pts.length;
    pts.push(p);
    var x=Math.round(p.x*2)/2, y=Math.round(p.y*2)/2;
    if(n===0){t.abierto='M'+x+' '+y;return;}
    t.abierto+=(n===1?'L':' ')+x+' '+y;
  }
  function pintar(t){ t.setAttribute('d',t.abierto); }
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
  var d=id('deshacer'), r=id('rehacer'), td=id('t-deshacer'), tr=id('t-rehacer');
  if(d) d.disabled=!(actual&&actual.puedeDeshacer&&actual.puedeDeshacer());
  if(r) r.disabled=!(actual&&actual.puedeRehacer&&actual.puedeRehacer());
  if(td) td.disabled=!(actual&&actual.puedeDeshacer&&actual.puedeDeshacer());
  if(tr) tr.disabled=!(actual&&actual.puedeRehacer&&actual.puedeRehacer());
}
function marcarHerramienta(){
  ['mano','lapiz','marcador','goma','girar','mover','medir'].forEach(function(n){
    var b=id(n); if(b) b.classList.toggle('activo', actual&&actual.modoActual===n);
  });
  var p=id('paleta');
  if(p) p.hidden=!(actual&&(actual.modoActual==='lapiz'||actual.modoActual==='marcador'));
  cajaLienzo.classList.toggle('pintando', !!(actual&&actual.pintando&&actual.pintando()));
  cajaLienzo.classList.toggle('midiendo', !!(actual&&actual.modoActual==='medir'));
  [].forEach.call(document.querySelectorAll('#presentacion [data-m]'),function(b){
    b.classList.toggle('activo', !!(actual&&actual.modoActual===b.dataset.m));
    b.hidden=!(actual&&actual.herramientas.indexOf(b.dataset.m)>=0);
  });
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
  var esTabla=hojas[i].dataset.tipo==='tabla';
  document.body.classList.toggle('enLaTabla',esTabla);
  var hayPlano=hojas[i].dataset.plano==='1';
  // Una nota lleva los mismos mandos que un dibujo: se raya encima igual. Ver [crearNota].
  // Una tabla también: el lápiz raya encima de las celdas, y además lleva su grupo aparte.
  [].forEach.call(document.querySelectorAll('.solo-dibujo'),function(g){g.hidden=esEspacio;});
  [].forEach.call(document.querySelectorAll('.solo-tabla'),function(g){g.hidden=!esTabla;});
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
  // El índice marca la hoja de ahora, y la dirección la nombra: así un enlace lleva a ella.
  [].forEach.call(document.querySelectorAll('#indice-fijo a'),function(a){a.classList.toggle('activo',+a.dataset.i===i);});
  var tit=document.querySelector('#indice-fijo .titulo'), cue=document.querySelector('#indice-fijo .cuenta');
  if(tit) tit.textContent=hojas[i].dataset.nombre||('Hoja '+(i+1));
  if(cue) cue.textContent=(i+1)+' / '+hojas.length;
  try{ if(location.hash!=='#hoja-'+(i+1)) history.replaceState(null,'','#hoja-'+(i+1)); }catch(e){}
  api.decir('');
  marcarHerramienta(); refrescar();
}
function desdeLaDireccion(){
  var h=location.hash||'';
  if(h.indexOf('#hoja-')!==0) return;
  var n=parseInt(h.slice(6),10);
  if(n>0) irA(n-1);
}
window.addEventListener('hashchange',desdeLaDireccion);
(function(){
  var d=id('indice-fijo');
  if(!d) return;
  if(window.innerWidth<600) d.open=false;
  [].forEach.call(d.querySelectorAll('a'),function(a){
    a.addEventListener('click',function(e){ e.preventDefault(); irA(+a.dataset.i); if(window.innerWidth<600) d.open=false; });
  });
})();

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
if(id('escena')) id('escena').onclick=function(){ pintarEscena(); };
if(id('guia')) id('guia').onclick=function(){ alternarGuia(); };
// **La escena del croquis**: interruptores, y abajo el OBJ para llevárselo a Blender.
function pintarEscena(){
  if(!actual||!actual.escena) return;
  var lista=actual.escena();
  var filas=lista.map(function(g){
    var d=document.createElement('div'); d.className='fila';
    var c=document.createElement('input'); c.type='checkbox'; c.checked=g.puesto();
    c.onchange=function(){g.poner(c.checked);};
    var n=document.createElement('span'); n.className='n'; n.textContent=g.nombre;
    n.onclick=function(){c.checked=!c.checked;c.onchange();};
    d.appendChild(c); d.appendChild(n);
    return d;
  });
  var pie=document.createElement('div'); pie.className='mini';
  if(actual.obj) pie.appendChild(boton('Bajar OBJ',function(){ bajarObj(); }));
  pie.appendChild(boton('Guía',function(){ alternarGuia(); }));
  abrir('Escena',filas,pie);
}
// El giradiscos se para solo al tocar: el cajón, si está abierto, tiene que enterarse.
api.escenaCambio=function(){ if(!cajon.hidden&&cajon.dataset.que==='Escena'){ cajon.dataset.que=''; pintarEscena(); } };
function bajarObj(){
  if(!actual||!actual.obj) return;
  var m=actual.obj(), nombre=(document.body.dataset.nombre||'croquis').replace(/[\\/:*?"<>|]+/g,'-').trim()||'croquis';
  function baja(texto,archivo){
    var a=document.createElement('a');
    a.href=URL.createObjectURL(new Blob([texto],{type:'text/plain'})); a.download=archivo;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function(){URL.revokeObjectURL(a.href);},4000);
  }
  baja(m.obj,nombre+'.obj'); setTimeout(function(){ baja(m.mtl,'croquis.mtl'); },300);
  avisar('OBJ y MTL bajados: ábrelos juntos');
}
// **La guía de gestos**: una tarjeta con lo que hace cada dedo, cada botón y cada tecla.
function alternarGuia(){
  var g=id('guia-caja');
  if(g){ g.parentNode.removeChild(g); return; }
  if(!actual||!actual.guia) return;
  g=document.createElement('div'); g.id='guia-caja';
  var t=document.createElement('div'); t.className='guia';
  var h=document.createElement('h2'); h.textContent='Cómo se mueve'; t.appendChild(h);
  actual.guia().forEach(function(b){
    var h3=document.createElement('h3'); h3.textContent=b.t; t.appendChild(h3);
    b.f.forEach(function(f){
      var r=document.createElement('div'); r.className='par';
      var a=document.createElement('span'); a.textContent=f[0];
      var c=document.createElement('span'); c.textContent=f[1];
      r.appendChild(a); r.appendChild(c); t.appendChild(r);
    });
  });
  var cerrar=boton('Entendido',function(){ alternarGuia(); }); cerrar.className='cerrar';
  t.appendChild(cerrar);
  g.appendChild(t);
  g.onclick=function(e){ if(e.target===g) alternarGuia(); };
  document.body.appendChild(g);
}
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
if(id('t-deshacer')) id('t-deshacer').onclick=function(){if(actual.deshacer)actual.deshacer();};
if(id('t-rehacer')) id('t-rehacer').onclick=function(){if(actual.rehacer)actual.rehacer();};
['t-negrita','t-suma','t-copiar','t-pegar','t-csv'].forEach(function(n){
  var b=id(n);
  // `mousedown` sin foco: pulsar el botón no le quita la celda elegida a la tabla.
  if(b){ b.onmousedown=function(e){e.preventDefault();}; b.onclick=function(){ if(actual&&actual.accion)actual.accion(n); }; }
});
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
  var dibujos=pagina.filter(function(p){return p&&(p.tipo==='dibujo'||p.tipo==='nota'||p.tipo==='tabla');}), i=0;
  var re=/<g id="croquis"[^>]*>[\s\S]*?<\/g>/g;
  // **Solo los primeros, y son los del dibujo.**
  //
  // La plantilla es el documento **entero**, y el documento incluye este mismo guion, que
  // habla de `<g id="croquis">` dos veces: en el patrón de aquí arriba y en el texto que
  // devuelve esta función. Con una `replace` global, guardar reescribía también **su propia
  // fuente** —153 bytes, medidos—, el guion quedaba con un error de sintaxis y el archivo
  // guardado se abría **muerto**: sin poder dibujar, sin poder ampliar, sin nada. Es el fallo
  // que reportó el usuario el 8-sep-2026 («lo edito, lo guardo, lo reenvío, y ya no puedo
  // hacer zoom ni manejar ni nada»), y solo salía en el archivo guardado, nunca en el
  // exportado, porque hace falta guardar una vez para estropearlo.
  //
  // Los grupos de verdad están en el `#lienzo`, que va **antes** del guion: son las primeras
  // coincidencias, tantas como hojas con grupo. Lo que venga después es el guion hablando de
  // sí mismo y se deja **tal cual**.
  var salida=PLANTILLA.replace(re,function(entero){
    if(i>=dibujos.length) return entero;
    var p=dibujos[i++];
    return p?'<g id="croquis">\n'+p.rayas()+'</g>':'<g id="croquis"></g>';
  });
  // **Las tablas: su JSON y su tabla ya calculada**, con el mismo tope. Las etiquetas se
  // escriben a trozos para que el patrón no se encuentre a sí mismo en este guion.
  var tablas=pagina.filter(function(p){return p&&p.tipo==='tabla';});
  if(tablas.length){
    var j=0, m=0, abre='<'+'script type="application/json" class="'+'tabla">';
    salida=salida.replace(new RegExp(abre+'[\\s\\S]*?<\\/script>','g'),function(entero){
      return j>=tablas.length?entero:abre+tablas[j++].json()+'<\/script>';
    });
    salida=salida.replace(new RegExp('<'+'table class="'+'calc">[\\s\\S]*?<\\/table>','g'),function(entero){
      return m>=tablas.length?entero:tablas[m++].estatica();
    });
  }
  return salida;
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
  // La tabla primero: las letras que se escriben en ella no son atajos. Y en cualquier campo
  // de texto, tampoco.
  if(actual&&actual.tecla&&actual.tecla(e)) return;
  var tg=e.target;
  if(tg&&(tg.tagName==='INPUT'||tg.tagName==='TEXTAREA'||tg.isContentEditable)) return;
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
  else if(k==='o'&&actual.orto)actual.orto();
  else if(k===']'&&actual.lente)actual.lente(0.08);
  else if(k==='['&&actual.lente)actual.lente(-0.08);
  else if(k==='t'&&actual.giradiscos){actual.giradiscos();api.escenaCambio();}
  else if(k==='?'||(k==='/'&&e.shiftKey))alternarGuia();
  else if((k==='+'||k==='=')&&actual.zoom)actual.zoom(1/1.3);
  else if(k==='-'&&actual.zoom)actual.zoom(1.3);
  else if(k==='escape'){cajon.hidden=true;cajon.dataset.que='';api.decir('');}
});
// ---- Presentar (14-sep-2026) ----
// La hoja a pantalla completa y una pastilla abajo. Con la mano puesta, un toque en el tercio
// derecho o un barrido a la izquierda pasa a la siguiente; en el izquierdo, a la anterior; en
// el medio esconde la pastilla. Con el lápiz se anota encima como siempre.
var raiz=document.documentElement, pastilla=id('presentacion'), presentando=false, toque=null;
function cuenta(){
  if(!pastilla) return;
  id('p-cuenta').textContent=(iHoja+1)+' / '+pagina.length;
  id('p-anterior').disabled=iHoja<=0;
  id('p-siguiente').disabled=iHoja>=pagina.length-1;
}
function pasar(d){
  var antes=iHoja;
  irA(iHoja+d);
  if(iHoja!==antes&&actual&&actual.encajar) actual.encajar();
  cuenta();
}
function presentar(){
  if(!pastilla) return;
  presentando=true; raiz.classList.add('presentando'); pastilla.hidden=false;
  pastilla.classList.remove('escondida');
  if(actual&&actual.herramientas.indexOf('mano')>=0) elegir('mano');
  if(raiz.requestFullscreen) raiz.requestFullscreen().catch(function(){});
  setTimeout(function(){ if(actual&&actual.medir) actual.medir(); if(actual&&actual.encajar) actual.encajar(); },250);
  marcarHerramienta(); cuenta();
}
function dejarDePresentar(){
  if(!presentando) return;
  presentando=false; raiz.classList.remove('presentando'); if(pastilla) pastilla.hidden=true;
  if(document.fullscreenElement&&document.exitFullscreen) document.exitFullscreen().catch(function(){});
  setTimeout(function(){ if(actual&&actual.medir) actual.medir(); },250);
}
function enModoPasar(){ return presentando&&(!actual||!actual.modoActual||actual.modoActual==='mano'||actual.modoActual==='mover'||actual.modoActual==='girar'); }
if(id('presentar')) id('presentar').onclick=presentar;
if(pastilla){
  id('p-anterior').onclick=function(){pasar(-1);};
  id('p-siguiente').onclick=function(){pasar(1);};
  id('p-salir').onclick=dejarDePresentar;
  if(id('p-encajar')) id('p-encajar').onclick=function(){ if(actual&&actual.encajar) actual.encajar(); };
  [].forEach.call(pastilla.querySelectorAll('[data-m]'),function(b){
    b.onclick=function(){
      if(!actual||actual.herramientas.indexOf(b.dataset.m)<0) return;
      actual.modoActual=b.dataset.m; actual.modo(b.dataset.m); marcarHerramienta();
      // **Un empujón de repintado**: cambiar de herramienta hace aparecer y desaparecer la
      // paleta, y en pantalla completa hay navegadores que dejan la capa vieja pegada. Tocar
      // el tamaño de la hoja obliga a componer de nuevo y se limpia.
      if(actual.medir) actual.medir();
      cajaLienzo.style.transform='translateZ(0)';
      void cajaLienzo.offsetHeight;
      cajaLienzo.style.transform='';
    };
  });
  // **Presentando también se mueve y se amplía** (16-sep-2026). Antes el dedo se le quitaba al
  // visor en cuanto empezaba —se tragaba el `pointerdown`—, así que presentando no se podía ni
  // desplazar el dibujo ni hacer pinza: solo pasar hojas. Ahora **no se le quita nada**: el
  // visor mueve y amplía como siempre, y aquí solo se mira, al levantar el dedo, si aquello fue
  // un toque o un barrido rápido de un solo dedo; si lo fue, se pasa de hoja y el encuadre se
  // rehace ([pasar] llama a `encajar`). Con dos dedos —una pinza— no se pasa nunca de hoja.
  var dedos=0;
  cajaLienzo.addEventListener('pointerdown',function(e){
    dedos++;
    if(!enModoPasar()) return;
    toque=dedos===1?{x:e.clientX,y:e.clientY,t:Date.now()}:null;
  },true);
  cajaLienzo.addEventListener('pointercancel',function(){ dedos=Math.max(0,dedos-1); toque=null; if(dedos===0) noPerderse(); },true);
  // Al soltar el último dedo, si el dibujo se ha ido de la pantalla, se vuelve a encajar.
  function noPerderse(){
    if(!actual||!actual.perdido) return;
    if(actual.perdido()&&actual.encajar) actual.encajar();
  }
  cajaLienzo.addEventListener('pointerup',function(e){
    var eranVarios=dedos>1;
    dedos=Math.max(0,dedos-1);
    if(dedos===0) noPerderse();
    if(!enModoPasar()||!toque||eranVarios){ toque=null; return; }
    var dx=e.clientX-toque.x, dy=e.clientY-toque.y, t=Date.now()-toque.t; toque=null;
    // Un barrido es rápido y derecho; uno lento es que estabas moviendo el dibujo y se respeta.
    if(t<500&&Math.abs(dx)>60&&Math.abs(dx)>Math.abs(dy)*1.5){ pasar(dx<0?1:-1); return; }
    if(Math.abs(dx)<12&&Math.abs(dy)<12){
      var w=innerWidth;
      if(e.clientX<w/3) pasar(-1);
      else if(e.clientX>w*2/3) pasar(1);
      else pastilla.classList.toggle('escondida');
    }
  },true);
  document.addEventListener('fullscreenchange',function(){ if(!document.fullscreenElement&&presentando) dejarDePresentar(); });
  document.addEventListener('keydown',function(e){
    if(e.key==='F5'){ e.preventDefault(); if(presentando) dejarDePresentar(); else presentar(); e.stopImmediatePropagation(); return; }
    if(!presentando) return;
    var k=e.key;
    if(k==='ArrowRight'||k==='PageDown'||k===' '){ e.preventDefault(); e.stopImmediatePropagation(); pasar(1); }
    else if(k==='ArrowLeft'||k==='PageUp'){ e.preventDefault(); e.stopImmediatePropagation(); pasar(-1); }
    else if(k==='Escape'){ e.stopImmediatePropagation(); dejarDePresentar(); }
  },true);
}

// ---- Imprimir (14-sep-2026) ----
// El diálogo del navegador, con el papel que se elija; la hoja de estilo de impresión pone
// una hoja por página. Con varias hojas se pregunta si todas o solo la que se mira.
function imprimir(){
  hojas.forEach(function(d){d.classList.remove('a-imprimir');});
  raiz.classList.remove('imprimir-una');
  if(pagina.length>1&&!confirm('¿Imprimir todas las hojas?\n\nAceptar: todas · Cancelar: solo la que estás viendo')){
    raiz.classList.add('imprimir-una'); hojas[iHoja].classList.add('a-imprimir');
  }
  if(actual&&actual.encajar) actual.encajar();
  setTimeout(function(){ window.print(); },60);
}
if(id('imprimir')) id('imprimir').onclick=imprimir;

// ---- Marcar una zona para imprimir (16-sep-2026) ----
// Pedido por el usuario: «no me deja imprimir porque no hay marcos; añade marcos temporales
// para imprimir la zona seleccionada». Un marco de verdad es parte del dibujo y hay que ir a la
// aplicación a ponerlo; esto es **un rectángulo de usar y tirar**: se arrastra sobre lo que se
// quiere en el papel y se imprime solo eso. No se guarda ni sale en el dibujo.
var marcando=false, marcaCaja=null, marcaDesde=null;
function pintarMarca(a,b){
  if(!marcaCaja){ marcaCaja=document.createElement('div'); marcaCaja.id='zona-marca'; document.body.appendChild(marcaCaja); }
  var x=Math.min(a.x,b.x), y=Math.min(a.y,b.y);
  marcaCaja.style.left=x+'px'; marcaCaja.style.top=y+'px';
  marcaCaja.style.width=Math.abs(b.x-a.x)+'px'; marcaCaja.style.height=Math.abs(b.y-a.y)+'px';
}
function quitarMarca(){ if(marcaCaja){ marcaCaja.remove(); marcaCaja=null; } }
function dejarDeMarcar(){ marcando=false; marcaDesde=null; raiz.classList.remove('marcando'); quitarMarca(); }
function marcarZona(){
  if(!actual||actual.tipo!=='dibujo'||!actual.vista){ estado.textContent='Esto solo vale en una hoja de dibujo'; return; }
  if(marcando){ dejarDeMarcar(); estado.textContent=''; return; }
  marcando=true; raiz.classList.add('marcando');
  estado.textContent='Arrastra sobre lo que quieras imprimir';
}
if(id('marcar-zona')) id('marcar-zona').onclick=marcarZona;
// En captura y antes que el visor: marcando, el dedo marca y no dibuja ni mueve.
cajaLienzo.addEventListener('pointerdown',function(e){
  if(!marcando) return;
  marcaDesde={x:e.clientX,y:e.clientY};
  pintarMarca(marcaDesde,marcaDesde);
  e.stopPropagation(); e.preventDefault();
},true);
cajaLienzo.addEventListener('pointermove',function(e){
  if(!marcando||!marcaDesde) return;
  pintarMarca(marcaDesde,{x:e.clientX,y:e.clientY});
  e.stopPropagation(); e.preventDefault();
},true);
cajaLienzo.addEventListener('pointerup',function(e){
  if(!marcando||!marcaDesde) return;
  e.stopPropagation(); e.preventDefault();
  var a=marcaDesde, b={x:e.clientX,y:e.clientY};
  dejarDeMarcar();
  if(Math.abs(b.x-a.x)<24||Math.abs(b.y-a.y)<24){ estado.textContent='Zona demasiado pequeña'; return; }
  var p1=actual.deEscena(Math.min(a.x,b.x),Math.min(a.y,b.y));
  var p2=actual.deEscena(Math.max(a.x,b.x),Math.max(a.y,b.y));
  imprimirZona({x:p1.x,y:p1.y,w:p2.x-p1.x,h:p2.y-p1.y});
},true);
// Se imprime **solo esa zona**: se encuadra ahí, se manda a imprimir esta hoja sola y, al
// volver del diálogo, el dibujo se queda como estaba.
function imprimirZona(c){
  var antes=actual.vista(), quien=actual;
  function devolver(){ removeEventListener('afterprint',devolver); quien.ponerVista(antes); }
  addEventListener('afterprint',devolver);
  actual.ponerVista(c);
  hojas.forEach(function(d){d.classList.remove('a-imprimir');});
  raiz.classList.add('imprimir-una'); hojas[iHoja].classList.add('a-imprimir');
  estado.textContent='';
  setTimeout(function(){ window.print(); },80);
}
document.addEventListener('keydown',function(e){ if(e.key==='Escape'&&marcando){ dejarDeMarcar(); estado.textContent=''; } });
addEventListener('afterprint',function(){ raiz.classList.remove('imprimir-una'); if(actual&&actual.medir) actual.medir(); });

// La dirección se lee antes de ir a la primera, que la reescribe.
var alAbrir=location.hash||'';
irA(0);
if(alAbrir.indexOf('#hoja-')===0){ var n0=parseInt(alAbrir.slice(6),10); if(n0>0) irA(n0-1); }
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
