package com.forge.pixpin.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La página HTML que se ve sola: que lleve el dibujo dentro, el visor dentro,
 * y nada del original editable.
 */
class ExportarHtmlTest {

    private val svg = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"100\" " +
        "viewBox=\"0 0 200 100\"><rect width=\"10\" height=\"10\"/></svg>"

    /** Un documento de tres láminas, como el que sale de un lienzo con tres marcos. */
    private fun tresHojas() = listOf(
        ExportarHtml.HojaWeb.Dibujo("Planta", svg, "#ffffff"),
        ExportarHtml.HojaWeb.Dibujo("Alzado", svg.replace("0 0 200 100", "0 0 300 200"), "#ffffff"),
        ExportarHtml.HojaWeb.Dibujo("", svg, "#ffffff")
    )

    /**
     * **Guardar no puede reescribir el guion.**
     *
     * La plantilla que guarda la página es el documento entero, y el documento incluye el
     * guion, que nombra `<g id="croquis">` dos veces: en su patrón y en el texto que devuelve.
     * Con una `replace` global —como estaba— guardar se comía **1.126 bytes de su propia
     * fuente**, el archivo guardado abría con un error de sintaxis y quedaba **muerto**: ni
     * dibujar, ni ampliar, ni mover. Solo pasaba en el archivo guardado, nunca en el
     * exportado, que es lo que lo hacía tan desconcertante (usuario, 8-sep-2026).
     *
     * Aquí se guarda igual que lo hace el navegador y se comprueba lo que importa: que el
     * trazo entre y que **el guion salga con los mismos bytes con los que entró**.
     */
    @Test
    fun `guardar mete el trazo y deja el guion intacto`() {
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto")
        val patron = Regex("""<g id="croquis"[^>]*>[\s\S]*?</g>""")
        assertTrue(
            "el guion habla de sí mismo: hay más coincidencias que hojas",
            patron.findAll(html).count() > 3
        )
        // Guardar, con el tope que lleva la página: solo las hojas, que van antes del guion.
        var i = 0
        val guardado = patron.replace(html) { m ->
            if (i >= 3) m.value else { i++; """<g id="croquis">\n<path d="M0 0 L9 9"/>\n</g>""" }
        }
        assertTrue("el trazo del usuario está", guardado.contains("""<path d="M0 0 L9 9"/>"""))
        val guion = Regex("""<script[^>]*>([\s\S]*?)</script>""")
        assertEquals(
            "el guion del archivo guardado es el mismo que el del exportado",
            guion.find(html)!!.groupValues[1],
            guion.find(guardado)!!.groupValues[1]
        )
    }

    /**
     * **Una tabla viaja calculada, escapada y con una sola cosa que reescribir al guardar.**
     *
     * Guardar reemplaza el JSON y la tabla estática de cada hoja de tabla con las primeras
     * coincidencias de dos patrones. Si el guion o el estilo contuvieran esos principios de
     * etiqueta escritos tal cual, guardar se los comería como pasó con `<g id="croquis">`.
     */
    @Test
    fun `una tabla viaja con sus formulas y guardar solo encuentra lo suyo`() {
        val t = TablaDeCalculo(
            nombre = "Gastos",
            celdas = mapOf(
                "A1" to "Precio", "B1" to "10", "B2" to "20", "B3" to "=SUMA(B1:B2)",
                "A4" to "<script>alert(1)</script>", "A5" to "</script><table class=\"calc\">"
            ),
            estilos = mapOf("A1" to EstiloDeCelda(n = true))
        )
        val html = ExportarHtml.paginas(listOf(ExportarHtml.HojaWeb.Tabla("Gastos", t)), "Gastos")
        assertTrue(html.contains("data-tipo=\"tabla\""))
        assertTrue("sale ya calculada", html.contains(">30</td>"))
        assertTrue("la negrita va", html.contains("<td class=\"n\">Precio</td>"))
        assertFalse("lo escrito no se vuelve etiqueta", html.contains("<script>alert"))
        assertEquals(1, Regex(Regex.escape("<script type=\"application/json\" class=\"tabla\">")).findAll(html).count())
        assertEquals(1, Regex(Regex.escape("<table class=\"calc\">")).findAll(html).count())
        assertTrue("el visor va dentro", html.contains("function crearTabla("))
        assertTrue("y el motor", html.contains("var Calculo="))
        // Una página sin tablas no arrastra ni el motor ni el visor.
        assertFalse(ExportarHtml.paginas(tresHojas(), "Sin tablas").contains("var Calculo="))
        // **Solo lo ocupado y uno alrededor**: A1:B5 lleva hasta la columna C y la fila 6.
        assertTrue(html.contains("px\">C</th>"))
        assertFalse(html.contains("px\">D</th>"))
        assertTrue(html.contains("<tr><th>6</th>"))
        assertFalse(html.contains("<tr><th>7</th>"))
        assertTrue("la fórmula va sombreada", html.contains("<td class=\"d f\">30</td>"))
        assertTrue("la tabla lleva su capa de tinta", html.contains("<svg class=\"tinta\"><g class=\"origen\"><g id=\"croquis\"></g></g></svg>"))
        assertTrue("y el lápiz en la barra", html.contains("id=\"lapiz\""))
        assertFalse("sin marca de solo lectura", html.contains("data-tabla-editable"))
        // En solo lectura: la marca va, y los botones que cambian celdas no.
        val soloVer = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Tabla("Gastos", t)), "Gastos",
            opciones = ExportarHtml.Opciones.de(ExportarHtml.conTablasEditables(ExportarHtml.Opciones.NOMBRES.toSet(), false))
        )
        assertTrue(soloVer.contains("data-tabla-editable=\"0\""))
        assertFalse(soloVer.contains("id=\"t-pegar\""))
        assertTrue(soloVer.contains("id=\"t-copiar\""))
        assertTrue("los ajustes de antes siguen dejando editar", ExportarHtml.Opciones.de(setOf("lapiz")).editarTablas)
        // Una tabla que empieza lejos de A1 enseña su margen y no la esquina vacía.
        val lejos = VisorTabla.estatica(TablaDeCalculo(celdas = mapOf("D10" to "1", "E12" to "2")))
        assertTrue(lejos.contains("px\">C</th>") && lejos.contains("px\">F</th>"))
        assertFalse(lejos.contains("px\">B</th>") || lejos.contains("px\">G</th>"))
        assertTrue(lejos.contains("<tr><th>9</th>") && lejos.contains("<tr><th>13</th>"))
        assertFalse(lejos.contains("<tr><th>8</th>") || lejos.contains("<tr><th>14</th>"))
        // **Con un lienzo al lado, la tabla oculta no se pinta encima.** Su `display:flex` pesaba
        // lo mismo que `.hoja[hidden]` y ganaba por ir detrás (usuario, 11-sep-2026).
        val mixta = ExportarHtml.paginas(listOf(tresHojas()[0], ExportarHtml.HojaWeb.Tabla("Gastos", t)), "Mixta")
        assertTrue(mixta.contains(".hoja[data-tipo=tabla][hidden]{display:none}"))
        System.getenv("PIXPIN_VOLCAR")?.let {
            java.io.File(it).writeText(html)
            java.io.File(it.removeSuffix(".html") + "-mixta.html").writeText(mixta)
            // Protegida, con B1 y B2 como las únicas celdas que se pueden cambiar.
            val protegida = t.copy(protegida = true, estilos = t.estilos + mapOf("B1" to EstiloDeCelda(e = true), "B2" to EstiloDeCelda(e = true)))
            java.io.File(it.removeSuffix(".html") + "-protegida.html")
                .writeText(ExportarHtml.paginas(listOf(ExportarHtml.HojaWeb.Tabla("Gastos", protegida)), "Protegida"))
        }
    }

    @Test
    fun `un documento lleva todas sus hojas y el menu para pasarlas`() {
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto")
        assertEquals(3, Regex("class=\"hoja\"").findAll(html).count())
        assertTrue(html.contains("data-nombre=\"Planta\""))
        assertTrue(html.contains("data-nombre=\"Alzado\""))
        // La que no trae nombre se llama por su número.
        assertTrue(html.contains("data-nombre=\"Hoja 3\""))
        // Solo se ve la primera: las demás no las pinta el navegador.
        assertEquals(2, Regex("data-nombre=\"[^\"]*\" hidden").findAll(html).count())
        assertTrue("el menú tiene que salir", html.contains("<div id=\"pizarra\" class=\"varias\">"))
        // Pasar de página va en el índice de arriba, no en la barra.
        assertTrue(html.contains("id=\"indice-fijo\""))
        assertFalse(html.contains("id=\"siguiente\""))
        assertTrue("el índice dice la hoja de ahora", html.contains("<span class=\"titulo\">Planta</span><span class=\"cuenta\">1 / 3</span>"))
        assertTrue(html.contains("viewBox=\"0 0 300 200\""))
    }

    @Test
    fun `con varias hojas hay un indice con enlaces a cada una`() {
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto")
        assertTrue(html.contains("<details id=\"indice-fijo\""))
        assertTrue(html.contains("href=\"#hoja-2\""))
        assertTrue(html.contains("id=\"hoja-3\""))
        val indice = html.substringAfter("<details id=\"indice-fijo\"").substringBefore("</details>")
        assertTrue(indice.contains("Planta") && indice.contains("Alzado") && indice.contains("Hoja 3"))
        // Una sola hoja no lleva índice.
        assertFalse(ExportarHtml.pagina(svg, "Mi dibujo", "#ffffff").contains("indice-fijo\" open"))
    }

    @Test
    fun `cada hoja lleva su grupo de anotaciones`() {
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto")
        // Dentro del dibujo, que el visor también lleva ese texto escrito.
        val lienzo = html.substringAfter("<div id=\"lienzo\">").substringBefore("<div id=\"pizarra\"")
        assertEquals(3, Regex("<g id=\"croquis\"></g>").findAll(lienzo).count())
        // Y el visor las guarda una por una, no todas en la primera.
        assertTrue(html.contains("dibujos[i++]"))
    }

    @Test
    fun `con una sola hoja no hay menu de paginas`() {
        val html = ExportarHtml.pagina(svg, "Mi dibujo", "#ffffff")
        assertFalse(html.contains("class=\"varias\""))
        assertEquals(1, Regex("class=\"hoja\"").findAll(html).count())
        assertFalse("una hoja sola no se esconde", html.contains("data-nombre=\"Hoja 1\" hidden"))
        assertFalse("ni menú de páginas", html.contains("id=\"indice\""))
    }

    @Test
    fun `un documento junta dibujos, espacio y notas con una sola barra`() {
        val html = ExportarHtml.paginas(
            listOf(
                ExportarHtml.HojaWeb.Dibujo("Planta", svg, "#ffffff"),
                ExportarHtml.HojaWeb.Espacio("Maqueta", "{\"tr\":[],\"ho\":[],\"im\":[]}", "#14161c"),
                ExportarHtml.HojaWeb.Nota("Notas", "<p>hola</p>", "#ffffff")
            ),
            "Proyecto"
        )
        assertTrue(html.contains("data-tipo=\"dibujo\""))
        assertTrue(html.contains("data-tipo=\"espacio\""))
        assertTrue(html.contains("data-tipo=\"nota\""))
        // Los dos visores van dentro, y solo si hacen falta.
        assertTrue("falta el visor del espacio", html.contains("function crearEspacio"))
        assertTrue("falta el visor del dibujo", html.contains("function crearDibujo"))
        // Una sola barra: los mandos que no valen para la página que se mira se esconden.
        assertEquals(1, Regex("id=\"barra\"").findAll(html).count())
        assertTrue(html.contains("class=\"grupo solo-dibujo\""))
        assertTrue(html.contains("class=\"grupo solo-espacio\""))
        assertTrue(html.contains("id=\"girar\""))
        assertTrue(html.contains("id=\"medir\""))
        assertTrue(html.contains("<article class=\"nota\"><p>hola</p></article>"))
        // El fondo cambia con la página.
        assertTrue(html.contains("data-fondo=\"#14161c\""))
    }

    /**
     * **Una nota exportada se puede rayar encima.** Lleva su capa de tinta, y el texto de
     * debajo sigue siendo texto: se selecciona, se copia y lo encuentra la lupa del
     * navegador. La capa solo recibe el dedo con el lápiz o el resaltador en la mano.
     */
    @Test
    fun `una nota se puede rayar y lo rayado se guarda`() {
        val html = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Nota("Notas", "<p>hola</p>", "#ffffff")), "Proyecto"
        )
        assertTrue("falta la capa de tinta", html.contains("<svg class=\"tinta\">"))
        assertTrue(html.contains("<g id=\"croquis\"></g>"))
        // Sin lápiz en la mano no se come los toques: el texto se sigue seleccionando.
        assertTrue(html.contains(".tinta{position:absolute"))
        assertTrue(html.contains("pointer-events:none"))
        assertTrue(html.contains("#lienzo.pintando .tinta{pointer-events:auto"))
        // Y trae los mismos mandos que un dibujo.
        assertTrue(html.contains("id=\"lapiz\""))
        assertTrue(html.contains("id=\"marcador\""))
        assertTrue(html.contains("id=\"goma\""))
    }

    @Test
    fun `un documento de solo dibujos no arrastra el visor del espacio`() {
        val html = ExportarHtml.pagina(svg, "Mi dibujo", "#ffffff")
        assertFalse(html.contains("function crearEspacio"))
        assertFalse(html.contains("id=\"girar\""))
    }

    @Test
    fun `un documento sin hojas no se escribe`() {
        try {
            ExportarHtml.paginas(emptyList(), "Vacío")
            throw AssertionError("tenía que quejarse")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("documento"))
        }
    }

    @Test
    fun `el nombre de una hoja no se cuela en el html`() {
        val html = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Dibujo("<b>a</b> \"x\"", svg, "#ffffff")), "Proyecto"
        )
        assertTrue(html.contains("data-nombre=\"&lt;b&gt;a&lt;/b&gt; &quot;x&quot;\""))
    }

    @Test
    fun `la pagina lleva el svg embebido y sin prologo xml`() {
        val html = ExportarHtml.pagina(svg, "Mi dibujo", "#ffffff")
        assertTrue(html.contains("<svg "))
        assertTrue(html.contains("viewBox=\"0 0 200 100\""))
        // El prólogo XML dentro de un HTML no es válido: tiene que caerse.
        assertFalse(html.contains("<?xml"))
    }

    @Test
    fun `la pagina es un documento entero con su visor dentro`() {
        val html = ExportarHtml.pagina(svg, "Mi dibujo", "#1e1e1e")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Mi dibujo</title>"))
        // El visor embebido: el que mueve el viewBox.
        assertTrue(html.contains("viewBox.baseVal"))
        assertTrue(html.contains("pointerdown"))
        assertTrue(html.contains("wheel"))
        // El fondo de la página es el del lienzo.
        assertTrue(html.contains("background:#1e1e1e"))
        // Y no carga nada de fuera: se abre sin conexión. Los xmlns del SVG
        // llevan una URL pero son identificadores, no descargas — lo que
        // delataría una descarga es un src o un href apuntando a la red.
        assertFalse(html.contains("src=\"http"))
        assertFalse(html.contains("href=\"http"))
        assertFalse(html.contains("@import"))
        assertFalse(html.contains("url("))
    }

    /**
     * El croquis de encima: lápiz y borrador, temporales. Las rayas van en
     * coordenadas del dibujo (dentro del SVG) para pasear y ampliarse con él.
     */
    @Test
    fun `la pagina trae lapiz y borrador para la pizarra`() {
        val html = ExportarHtml.pagina(svg, "t", "#fff")
        assertTrue(html.contains("id=\"lapiz\""))
        assertTrue(html.contains("id=\"goma\""))
        // Nada de almacén del navegador: lo que se guarda va en el archivo.
        assertFalse(html.contains("localStorage"))
    }

    /**
     * **Fluida también con ratón.** En un portátil el navegador entrega un
     * solo `pointermove` por fotograma aunque el ratón dé cientos de muestras,
     * y una raya de rectas entre esos pocos puntos salía a tramos, mientras
     * que en el teléfono se veía fluida. El visor pide todas las muestras
     * (`getCoalescedEvents`) y traza curvas cuadráticas por los puntos medios
     * (`Q`) en vez de polilíneas; las polilíneas de páginas ya anotadas se
     * siguen leyendo.
     */
    @Test
    fun `las rayas se trazan con todas las muestras y tal cual`() {
        val html = ExportarHtml.pagina(svg, "t", "#fff")
        assertTrue(html.contains("getCoalescedEvents"))
        assertTrue(html.contains("createElementNS(NS,'path')"))
        // Lo que puso el lápiz, recta a recta: ni curvas por los puntos medios ni punta prevista
        // (usuario, 14-sep-2026). Las rayas viejas con Q se siguen leyendo.
        assertFalse(html.contains("getPredictedEvents"))
        assertFalse(html.contains("quadraticCurveTo"))
        assertTrue(html.contains("curvas=d.indexOf('Q')>=0"))
        assertTrue(html.contains("getAttribute('points')"))
    }

    /**
     * **La pizarra viaja con el archivo.** El grupo de las anotaciones va ya en
     * el SVG, vacío, y la página sabe reescribirse con él lleno (guardar) y
     * entregarse (compartir). Y si llega ya anotada, lee las rayas del propio
     * documento.
     */
    @Test
    fun `la pagina se guarda y se comparte a si misma con las anotaciones`() {
        val html = ExportarHtml.pagina(svg, "t", "#fff", nombre = "ejercicio 3")
        assertTrue(html.contains("<g id=\"croquis\"></g>"))
        assertTrue(html.contains("id=\"guardar\""))
        assertTrue(html.contains("id=\"compartir\""))
        assertTrue(html.contains("navigator.share"))
        assertTrue(html.contains("data-nombre=\"ejercicio 3\""))
        // La copia de sí misma que se reescribe al guardar.
        assertTrue(html.contains("documentElement.outerHTML"))
        // Y el grupo de las notas queda dentro del SVG, no fuera.
        assertTrue(html.indexOf("<g id=\"croquis\"></g>") < html.indexOf("</svg>"))
    }

    /** Un SVG que ya trae anotaciones se respeta: no se le mete un segundo grupo. */
    @Test
    fun `un svg ya anotado conserva su grupo`() {
        val anotado = svg.replace("</svg>", "<g id=\"croquis\"><polyline points=\"1,1 2,2\"/></g></svg>")
        val html = ExportarHtml.pagina(anotado, "t", "#fff")
        val lienzo = html.substringAfter("<div id=\"lienzo\">").substringBefore("</div>")
        assertEquals(1, Regex("id=\"croquis\"").findAll(lienzo).count())
        assertFalse(lienzo.contains("<g id=\"croquis\"></g>"))
        assertTrue(lienzo.contains("<g id=\"croquis\"><polyline"))
    }

    /**
     * El croquis entero: resaltador, deshacer, y la paleta de colores vivos y
     * grosores. Todo dentro de la página, que es lo que la hace autosuficiente.
     */
    @Test
    fun `la pagina trae resaltador, deshacer, colores y grosores`() {
        val html = ExportarHtml.pagina(svg, "t", "#fff")
        assertTrue(html.contains("id=\"marcador\""))
        assertTrue(html.contains("id=\"deshacer\""))
        assertTrue(html.contains("id=\"paleta\""))
        for (c in ExportarHtml.COLORES) {
            assertTrue("falta el color $c", html.contains("data-color=\"$c\""))
        }
        for (g in ExportarHtml.GROSORES) {
            assertTrue("falta el grosor $g", html.contains("data-grosor=\"$g\""))
        }
        // El resaltador se funde con lo de debajo: multiplica sobre papel claro.
        assertTrue(html.contains("mix-blend-mode:var(--fusion)"))
    }

    /** Sobre un lienzo oscuro el resaltador suma luz en vez de multiplicar. */
    @Test
    fun `el tema va con la pagina, no con el documento`() {
        // Un documento puede llevar una lámina sobre papel blanco y un croquis sobre
        // pizarra: con un solo juego de colores, en una de las dos la letra queda encima de
        // su propio color. Los dos juegos van escritos y la clase la cambia el visor.
        val html = ExportarHtml.paginas(
            listOf(
                ExportarHtml.HojaWeb.Dibujo("Clara", svg, "#ffffff"),
                ExportarHtml.HojaWeb.Dibujo("Oscura", svg, "#1e1e1e")
            ),
            "Mezcla"
        )
        assertTrue(html.contains("--fusion:multiply"))
        assertTrue(html.contains("body.oscuro{"))
        assertTrue(html.contains("--fusion:screen"))
        assertTrue(html.contains("data-oscuro=\"0\""))
        assertTrue(html.contains("data-oscuro=\"1\""))
        assertTrue(html.contains("classList.toggle('oscuro'"))
        // Empieza con el tema de la primera.
        assertFalse(html.contains("<body data-nombre=\"Mezcla\" class=\"oscuro\">"))
    }

    @Test
    fun `un documento oscuro arranca en oscuro`() {
        val html = ExportarHtml.pagina(svg, "Mi dibujo", "#1e1e1e")
        assertTrue(html.contains("class=\"oscuro\">"))
        assertTrue(html.contains("background:#1e1e1e"))
    }

    @Test
    fun `la paleta se esconde de verdad`() {
        // `#paleta{display:flex}` pesa más que el `[hidden]{display:none}` del navegador: sin
        // la regla propia, los colores y los grosores se ven siempre.
        val html = ExportarHtml.pagina(svg, "Mi dibujo", "#ffffff")
        assertTrue(html.contains("#paleta[hidden]{display:none}"))
        assertTrue(html.contains("<div id=\"paleta\" hidden>"))
    }

    @Test
    fun `el titulo se escapa`() {
        val html = ExportarHtml.pagina(svg, "a<b>&c", "#fff")
        assertTrue(html.contains("<title>a&lt;b&gt;&amp;c</title>"))
    }

    @Test
    fun `solo visualizacion, ni escena editable ni datos de mas`() {
        val html = ExportarHtml.pagina(svg, "t", "#fff")
        // La pinta del JSON de escena que guarda la aplicación.
        assertFalse(html.contains("\"elements\""))
        assertFalse(html.contains("excalidraw"))
    }

    @Test
    fun `un svg sin prologo tambien entra`() {
        val pelado = svg.substringAfter("?>\n")
        val html = ExportarHtml.pagina(pelado, "t", "#fff")
        // El dibujo, una vez; los iconos de la barra son otros svg y no cuentan.
        assertEquals(1, Regex("<svg xmlns=").findAll(html).count())
    }

    // ---------------------------------------------------------------------
    // El plano vectorial
    // ---------------------------------------------------------------------

    /** Un plano de mentira: dos rayas y una capa, tal como lo escribe [PlanoWeb]. */
    private fun planoDeMentira(): String = PlanoWeb.aJson(
        PlanoDePdf.Plano(
            ancho = 100.0, alto = 50.0,
            capas = listOf(PlanoDePdf.Capa("Muros", true)),
            brochas = listOf(
                PlanoDePdf.Brocha(
                    0, 0x000000, 1.0, 0.5, false, DoubleArray(0),
                    byteArrayOf(PlanoDePdf.MOVER, PlanoDePdf.LINEA),
                    intArrayOf(0, 640), intArrayOf(0, 640)
                )
            ),
            textos = emptyList(), fotos = emptyList(), sinEntender = 0, cortado = false
        )
    )

    @Test
    fun `un dibujo con plano lleva su lienzo, sus datos y el boton de capas`() {
        val html = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Dibujo("Plano", svg, "#ffffff", planoDeMentira())),
            "Plano"
        )
        assertTrue("falta el lienzo del plano", html.contains("<canvas class=\"plano\"></canvas>"))
        assertTrue(html.contains("<script type=\"application/json\" class=\"plano\">"))
        assertTrue("la hoja tiene que anunciarse como plano", html.contains("data-plano=\"1\""))
        assertTrue(html.contains("id=\"capas\""))
        assertTrue("y el SVG sigue estando, que es donde se raya", html.contains("id=\"croquis\""))
    }

    /**
     * Un plano pesa —los datos son casi todo el archivo—, así que el visor y su descompresor
     * **solo viajan si hay plano**. Un documento de dibujos corrientes no puede engordar por
     * esto ni un kilobyte.
     */
    @Test
    fun `sin plano no viaja ni el visor ni el descompresor`() {
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto")
        assertFalse(html.contains("function crearPlano"))
        assertFalse(html.contains("function inflar"))
        assertFalse(html.contains("id=\"capas\""))
        assertFalse(html.contains("canvas class=\"plano\""))
    }

    @Test
    fun `con plano viajan el visor y el descompresor, una sola vez`() {
        val html = ExportarHtml.paginas(
            listOf(
                ExportarHtml.HojaWeb.Dibujo("Plano", svg, "#ffffff", planoDeMentira()),
                ExportarHtml.HojaWeb.Dibujo("Otro", svg, "#ffffff", planoDeMentira())
            ),
            "Proyecto"
        )
        assertEquals(1, Regex("function crearPlano").findAll(html).count())
        assertEquals(1, Regex("function inflar").findAll(html).count())
        assertEquals(2, Regex("canvas class=\"plano\"").findAll(html).count())
    }

    /**
     * **El plano se pinta con la tarjeta gráfica cuando la hay, y con el lienzo cuando no.**
     *
     * Las dos cosas viajan en la misma página: la tarjeta es lo que hace que un plano de un
     * millón de rayas se mueva sin tirones —las rayas viven en su memoria y mover es cambiar
     * la cámara—, y el lienzo de siempre es lo que hace que la página siga abriéndose donde no
     * hay tarjeta. Ver [VisorPlano].
     */
    @Test
    fun `el visor lleva la tarjeta grafica y el lienzo de respaldo`() {
        val html = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Dibujo("Plano", svg, "#ffffff", planoDeMentira())),
            "Plano"
        )
        assertTrue("falta pedir la tarjeta", html.contains("getContext('webgl'"))
        assertTrue("y su programa", html.contains("gl_Position"))
        assertTrue("y el respaldo de siempre", html.contains("getContext('2d'"))
        // Si no hay tarjeta, el lienzo se pinta con margen; con ella, del tamaño de la ventana.
        assertTrue(html.contains("MARGEN=1.0"))
    }

    /** Nada del plano puede cerrar la etiqueta que lo envuelve. Ver [PlanoWeb]. */
    @Test
    fun `los datos del plano no cierran su etiqueta`() {
        val html = ExportarHtml.paginas(
            listOf(ExportarHtml.HojaWeb.Dibujo("Plano", svg, "#ffffff", planoDeMentira())),
            "Plano"
        )
        val datos = html.substringAfter("application/json\" class=\"plano\">")
            .substringBefore("</script>")
        assertFalse(datos.contains("</"))
        assertTrue(datos.contains("\"capas\""))
    }

    // ---------------------------------------------------------------------
    // Lo que lleva el documento
    // ---------------------------------------------------------------------

    /** Lo que se apaga no va: ni el botón, ni el atajo (el visor filtra por `data-herramientas`). */
    @Test
    fun `las funciones apagadas no viajan`() {
        val poco = ExportarHtml.Opciones(
            lapiz = false, resaltador = false, borrador = false, deshacer = false,
            guardar = false, compartir = false
        )
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto", opciones = poco)
        for (id in listOf("lapiz", "marcador", "goma", "deshacer", "rehacer", "guardar", "compartir", "paleta")) {
            assertFalse("no tenía que ir «$id»", html.contains("id=\"$id\""))
        }
        assertTrue("medir sí se pidió", html.contains("id=\"medir\""))
        assertTrue("las páginas también", html.contains("id=\"indice-fijo\""))
        assertTrue(html.contains("data-herramientas=\"mano medir girar mover\""))
        // Y con todo puesto, va todo.
        val todo = ExportarHtml.paginas(tresHojas(), "Proyecto")
        for (id in listOf("lapiz", "marcador", "goma", "deshacer", "guardar", "compartir", "medir")) {
            assertTrue("faltaba «$id»", todo.contains("id=\"$id\""))
        }
    }

    @Test
    fun `sin pasar de pagina no hay menu aunque haya varias hojas`() {
        val html = ExportarHtml.paginas(tresHojas(), "Proyecto", opciones = ExportarHtml.Opciones(paginas = false))
        assertFalse(html.contains("id=\"indice\""))
        assertFalse(html.contains("class=\"varias\""))
    }

    /** La escala del dibujo viaja con la hoja: es lo que deja medir en metros. */
    @Test
    fun `la escala del dibujo viaja con su hoja`() {
        val hoja = ExportarHtml.HojaWeb.Dibujo("Planta", svg, "#ffffff", escala = Escala(0.05, "m", 2))
        val html = ExportarHtml.paginas(listOf(hoja), "Planta")
        assertTrue(html.contains("data-escala=\"0.05\" data-unidad=\"m\" data-decimales=\"2\""))
        // Sin escala válida no se escribe nada, y el visor medirá en píxeles.
        val sin = ExportarHtml.paginas(listOf(ExportarHtml.HojaWeb.Dibujo("P", svg, "#ffffff", escala = Escala(0.0))), "P")
        assertFalse(sin.contains("data-escala"))
    }

    @Test
    fun `las opciones se leen de lo marcado en los ajustes`() {
        val o = ExportarHtml.Opciones.de(setOf("lapiz", "medir"))
        // Van por grupos: con algo de un grupo, va el grupo entero. Y el índice, siempre.
        assertTrue(o.lapiz && o.resaltador && o.borrador && o.deshacer && o.medir && o.capas && o.paginas)
        assertFalse(o.guardar || o.compartir)
        assertTrue("null es todo", ExportarHtml.Opciones.de(null).compartir)
        assertTrue("sin nada, el índice sigue", ExportarHtml.Opciones.de(emptySet()).paginas)
    }

    @Test
    fun `un interruptor enciende y apaga su grupo entero`() {
        val todo = ExportarHtml.Opciones.NOMBRES.toSet() + "audio:ligero"
        val sinEdicion = ExportarHtml.conGrupo(todo, "edicion", false)
        assertTrue(sinEdicion.none { it in listOf("lapiz", "resaltador", "borrador", "deshacer") })
        assertTrue("lo demás se queda", "medir" in sinEdicion && "audio:ligero" in sinEdicion)
        assertFalse(ExportarHtml.grupoPuesto(sinEdicion, "edicion"))
        assertTrue(ExportarHtml.conGrupo(sinEdicion, "edicion", true).containsAll(listOf("lapiz", "deshacer")))
    }
}
