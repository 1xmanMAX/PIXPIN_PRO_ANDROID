package com.forge.pixpin.motor

import kotlin.math.abs

/**
 * Pegarle una capa a una página de un PDF ajeno.
 *
 * Es el eslabón entre [PdfLectura] —que sabe dónde está cada cosa—, [PdfLienzo]
 * —que traduce el dibujo a órdenes de PDF— y [PdfEscritura] —que añade la
 * revisión al final—. Lo que hace aquí es **la cirugía de la página**: decirle
 * al archivo que esa hoja tiene un contenido más.
 *
 * ## Lo que hay que hacer para que no se rompa nada
 *
 * 1. El contenido de una página puede ser **un flujo o una lista de flujos**. Se
 *    normaliza a lista y el nuestro se añade al final: así se pinta encima.
 * 2. Se envuelve lo que había en `q … Q`. Un contenido ajeno puede dejar la
 *    pila de estado gráfica a medias —un `q` sin su `Q`—, y entonces nuestro
 *    dibujo heredaría su matriz y su color y aparecería torcido, en otro sitio o
 *    de otro color. No es hipotético: es de lo más corriente en PDF generados
 *    por programas de maquetación.
 * 3. Los **recursos** de la página —dónde se buscan las imágenes y los estados
 *    de transparencia— pueden estar heredados del padre. Se resuelven, se
 *    fusionan con los nuestros y se escriben en la página, que es lo único que
 *    podemos tocar sin arriesgarnos a cambiárselos a otras hojas.
 * 4. Nuestros nombres llevan prefijo (`/PxG0`, `/PxI0`) para no pisar los suyos.
 */
object PdfAnotado {

    /** Prefijo de todo lo que añadimos, para no chocar con lo del archivo. */
    const val PREFIJO = "Px"

    /**
     * El archivo con [contenido] pintado encima de la página [indicePagina].
     *
     * [contenido] son las órdenes ya escritas (ver [PdfLienzo]), [recursos] lo
     * que esas órdenes necesiten —estados de transparencia, imágenes— y
     * [extra] los objetos sueltos a los que esos recursos apunten.
     *
     * Devuelve null si el PDF está cifrado, si la página no existe o si algo no
     * cuadra. **Nunca devuelve un archivo a medias**: o sale entero o no sale.
     */
    fun fundir(
        archivo: PdfArchivo,
        indicePagina: Int,
        contenido: ByteArray,
        recursos: PdfValor.Dicc? = null,
        extra: List<ObjetoPdf> = emptyList()
    ): ByteArray? {
        if (archivo.cifrado) return null
        if (contenido.isEmpty()) return null
        val numeroPagina = archivo.paginas().getOrNull(indicePagina) ?: return null
        val pagina = archivo.diccDe(PdfValor.Ref(numeroPagina, 0)) ?: return null

        var siguiente = maxOf(
            PdfEscritura.primerNumeroLibre(archivo),
            (extra.maxOfOrNull { it.numero } ?: 0) + 1
        )
        fun pedirNumero(): Int = siguiente++

        // Los dos flujos que protegen lo que había: uno abre la pila antes y el
        // otro la cierra después. Van comprimidos como todo lo demás.
        val abrir = pedirNumero()
        val nuestro = pedirNumero()

        val objetos = ArrayList<ObjetoPdf>(extra)
        objetos += ObjetoPdf(abrir, flujoComprimido("q\n".toByteArray(Charsets.ISO_8859_1)))
        objetos += ObjetoPdf(
            nuestro,
            flujoComprimido(
                ("Q\nq\n".toByteArray(Charsets.ISO_8859_1) + contenido +
                    "\nQ\n".toByteArray(Charsets.ISO_8859_1))
            )
        )

        val anteriores = contenidosDe(pagina)
        val nuevosContenidos = PdfValor.Lista(
            listOf(PdfValor.Ref(abrir, 0)) + anteriores + PdfValor.Ref(nuestro, 0)
        )

        val nuevaPagina = PdfValor.Dicc(
            pagina.entradas +
                mapOf(
                    "Contents" to nuevosContenidos,
                    "Resources" to fusionarRecursos(archivo, pagina, recursos)
                )
        )
        objetos += ObjetoPdf(numeroPagina, nuevaPagina)

        return PdfEscritura.incremental(archivo, objetos)
    }

    // ---------------------------------------------------------------------
    // La capa: lo dibujado va encima y aparte
    // ---------------------------------------------------------------------

    /**
     * Lo dibujado como **una capa propia encima de la página**.
     *
     * Es la forma buena, y la que se usa por defecto. [fundir] mete el dibujo
     * dentro del contenido de la página, lo que funciona pero lo deja soldado:
     * a partir de ahí es tan «de la página» como el texto que ya tenía. Aquí no.
     * El dibujo va en una **capa** con su nombre —lo que un PDF llama grupo de
     * contenido opcional— dentro de una **anotación** propia.
     *
     * Lo que eso cambia, que no es poco:
     *
     * - **La página no se toca en absoluto.** Ni su contenido ni sus recursos:
     *   lo único que cambia de ella es que su lista de anotaciones tiene una
     *   más. Es la edición menos invasiva que admite el formato, y por tanto la
     *   que menos puede romper. Ni siquiera hace falta ya proteger la pila
     *   gráfica con `q`/`Q`, porque nunca entramos en su flujo.
     * - **Se puede apagar.** En Acrobat, Foxit, PDF-XChange u Okular sale un
     *   panel de capas y se enciende y se apaga: se ve el plano limpio o el
     *   plano con tus marcas, sin dos archivos.
     * - **Se puede quitar.** Es una anotación, así que cualquier lector con
     *   herramientas la selecciona y la borra. Fundida en el contenido habría
     *   que reescribir el archivo.
     * - **Cada tanda es su capa.** Anotar otro día añade otra, con su nombre, y
     *   se pueden mirar por separado.
     *
     * Lo que hay que decir por delante: **el interruptor solo aparece en
     * lectores de escritorio**. En el móvil, en Chrome y en el visor de Android
     * —todos con el mismo motor por dentro— la capa se ve, porque nace
     * encendida, pero no hay panel donde apagarla. Se pierde el interruptor, no
     * el dibujo.
     *
     * [nombre] es lo que se leerá en ese panel: conviene que diga algo, como
     * «PixPin — 9 de agosto», y no «Capa 1».
     */
    fun anotar(
        archivo: PdfArchivo,
        indicePagina: Int,
        contenido: ByteArray,
        recursos: PdfValor.Dicc? = null,
        extra: List<ObjetoPdf> = emptyList(),
        nombre: String = "PixPin"
    ): ByteArray? {
        if (archivo.cifrado) return null
        if (contenido.isEmpty()) return null
        val numeroPagina = archivo.paginas().getOrNull(indicePagina) ?: return null
        val pagina = archivo.diccDe(PdfValor.Ref(numeroPagina, 0)) ?: return null
        val caja = cajaDePagina(archivo, indicePagina) ?: return null

        var siguiente = maxOf(
            PdfEscritura.primerNumeroLibre(archivo),
            (extra.maxOfOrNull { it.numero } ?: 0) + 1
        )
        fun pedirNumero(): Int = siguiente++

        val capa = pedirNumero()
        val forma = pedirNumero()
        val marca = pedirNumero()
        val objetos = ArrayList<ObjetoPdf>(extra)

        // La capa: un nombre y poco más. Lo que la hace una capa es que la
        // nombre el catálogo y que el dibujo diga que es suyo.
        objetos += ObjetoPdf(
            capa,
            PdfValor.Dicc(
                mapOf(
                    "Type" to PdfValor.Nombre("OCG"),
                    "Name" to PdfValor.Cadena(textoPdf(nombre))
                )
            )
        )

        // El dibujo, como formulario suelto. Sus recursos son los suyos y no los
        // de la página: por eso la página no hay que tocarla.
        val cajaPdf = PdfValor.Lista(caja.map { PdfValor.Numero(it) })
        objetos += ObjetoPdf(
            forma,
            PdfValor.Flujo(
                PdfValor.Dicc(
                    buildMap {
                        put("Type", PdfValor.Nombre("XObject"))
                        put("Subtype", PdfValor.Nombre("Form"))
                        put("FormType", PdfValor.Numero(1.0))
                        put("BBox", cajaPdf)
                        put("Matrix", identidad())
                        put("OC", PdfValor.Ref(capa, 0))
                        put("Resources", recursos ?: PdfValor.Dicc(emptyMap()))
                        put("Filter", PdfValor.Nombre("FlateDecode"))
                    }
                ),
                PdfEscritura.desinflar(contenido)
            )
        )

        // Y la anotación que la coloca sobre la hoja.
        objetos += ObjetoPdf(
            marca,
            PdfValor.Dicc(
                buildMap {
                    put("Type", PdfValor.Nombre("Annot"))
                    put("Subtype", PdfValor.Nombre("Stamp"))
                    put("Rect", cajaPdf)
                    // **El bit de imprimir.** Sin él la anotación se ve en
                    // pantalla y no sale en el papel, que es la peor forma de
                    // enterarse: cuando ya has impreso.
                    put("F", PdfValor.Numero(4.0))
                    put("AP", PdfValor.Dicc(mapOf("N" to PdfValor.Ref(forma, 0))))
                    put("OC", PdfValor.Ref(capa, 0))
                    put("T", PdfValor.Cadena(textoPdf("PixPin")))
                    put("Contents", PdfValor.Cadena(textoPdf(nombre)))
                    put("NM", PdfValor.Cadena(textoPdf("$PREFIJO-$indicePagina-$marca")))
                }
            )
        )

        // De la página solo cambia esto: una anotación más.
        val anotaciones = when (val a = archivo.resolver(pagina.entradas["Annots"])) {
            is PdfValor.Lista -> a.valores
            else -> emptyList()
        }
        objetos += ObjetoPdf(
            numeroPagina,
            PdfValor.Dicc(
                pagina.entradas + ("Annots" to PdfValor.Lista(anotaciones + PdfValor.Ref(marca, 0)))
            )
        )

        // Y el catálogo tiene que conocer la capa, o el panel no la enseña.
        val catalogo = anunciarLaCapa(archivo, capa) ?: return null
        objetos += catalogo

        return PdfEscritura.incremental(archivo, objetos)
    }

    /**
     * Apunta la capa en el catálogo, junto a las que ya hubiera.
     *
     * `/OCProperties` es la lista de capas del documento y **tiene que estar en
     * el catálogo**: una capa que no figura ahí no sale en el panel, y hay
     * lectores que directamente no dibujan lo que la lleva. Se fusiona con lo
     * que haya: un PDF de AutoCAD llega con sus propias capas y perderlas sería
     * romperle el archivo a quien más lo va a notar.
     *
     * Nace **encendida** —en `/ON`— porque lo normal es querer ver lo que
     * acabas de dibujar.
     */
    private fun anunciarLaCapa(archivo: PdfArchivo, capa: Int): ObjetoPdf? {
        val numeroCatalogo = (archivo.trailer.entradas["Root"] as? PdfValor.Ref)?.numero
            ?: return null
        val catalogo = archivo.diccDe(PdfValor.Ref(numeroCatalogo, 0)) ?: return null
        val nueva = PdfValor.Ref(capa, 0)

        val propiedades = archivo.diccDe(catalogo.entradas["OCProperties"])
        val todas = (archivo.resolver(propiedades?.entradas?.get("OCGs")) as? PdfValor.Lista)
            ?.valores.orEmpty()
        val porDefecto = archivo.diccDe(propiedades?.entradas?.get("D"))
        val orden = (archivo.resolver(porDefecto?.entradas?.get("Order")) as? PdfValor.Lista)
            ?.valores.orEmpty()
        val encendidas = (archivo.resolver(porDefecto?.entradas?.get("ON")) as? PdfValor.Lista)
            ?.valores.orEmpty()

        val nuevoD = PdfValor.Dicc(
            porDefecto?.entradas.orEmpty() + mapOf(
                "Order" to PdfValor.Lista(orden + nueva),
                "ON" to PdfValor.Lista(encendidas + nueva)
            )
        )
        val nuevasPropiedades = PdfValor.Dicc(
            propiedades?.entradas.orEmpty() + mapOf(
                "OCGs" to PdfValor.Lista(todas + nueva),
                "D" to nuevoD
            )
        )
        return ObjetoPdf(
            numeroCatalogo,
            PdfValor.Dicc(catalogo.entradas + ("OCProperties" to nuevasPropiedades))
        )
    }

    private fun identidad(): PdfValor.Lista = PdfValor.Lista(
        listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0).map { PdfValor.Numero(it) }
    )

    /**
     * Un texto para dentro del PDF, en UTF-16 con su marca por delante.
     *
     * Es la única codificación del formato que admite acentos y eñes sin
     * sorpresas. En latín-1 a secas, «Revisión» sale «Revisin» o peor en la
     * mitad de los lectores.
     */
    private fun textoPdf(texto: String): ByteArray =
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + texto.toByteArray(Charsets.UTF_16BE)

    // ---------------------------------------------------------------------
    // Una hoja nueva al final
    // ---------------------------------------------------------------------

    /**
     * Añade [contenido] como **una página más** al final del documento.
     *
     * Es lo otro que se le pide a un PDF de obra: no solo anotar lo que hay,
     * sino **meter una lámina propia** —un croquis, un detalle, una hoja de
     * cálculos— dentro del mismo documento que se va a entregar. Sin esto había
     * que mandar dos archivos y decir «mira también el otro».
     *
     * Se hace con el mismo mecanismo que todo lo demás: la página nueva es un
     * objeto que se añade al final, y el árbol de páginas se reescribe con un
     * hijo más y la cuenta subida. **Ni un byte del original se mueve**, así que
     * todo lo que ya estaba sigue exactamente igual.
     *
     * A diferencia de anotar, aquí el dibujo **no va en una capa**: una hoja
     * nueva es la hoja entera, y ponerla en una capa que se puede apagar
     * dejaría una página en blanco al apagarla.
     */
    fun aniadirPagina(
        archivo: PdfArchivo,
        contenido: ByteArray,
        ancho: Double,
        alto: Double,
        recursos: PdfValor.Dicc? = null,
        extra: List<ObjetoPdf> = emptyList()
    ): ByteArray? {
        if (archivo.cifrado) return null
        if (contenido.isEmpty() || ancho <= 0 || alto <= 0) return null

        val raiz = archivo.diccDe(archivo.trailer.entradas["Root"]) ?: return null
        val numeroArbol = raiz.ref("Pages")?.numero ?: return null
        val arbol = archivo.diccDe(PdfValor.Ref(numeroArbol, 0)) ?: return null

        var siguiente = maxOf(
            PdfEscritura.primerNumeroLibre(archivo),
            (extra.maxOfOrNull { it.numero } ?: 0) + 1
        )
        val flujo = siguiente++
        val pagina = siguiente++

        val objetos = ArrayList<ObjetoPdf>(extra)
        objetos += ObjetoPdf(flujo, flujoComprimido(contenido))
        objetos += ObjetoPdf(
            pagina,
            PdfValor.Dicc(
                mapOf(
                    "Type" to PdfValor.Nombre("Page"),
                    "Parent" to PdfValor.Ref(numeroArbol, 0),
                    "MediaBox" to PdfValor.Lista(
                        listOf(0.0, 0.0, ancho, alto).map { PdfValor.Numero(it) }
                    ),
                    "Contents" to PdfValor.Ref(flujo, 0),
                    "Resources" to (recursos ?: PdfValor.Dicc(emptyMap()))
                )
            )
        )

        val hijos = (archivo.resolver(arbol.entradas["Kids"]) as? PdfValor.Lista)?.valores.orEmpty()
        objetos += ObjetoPdf(
            numeroArbol,
            PdfValor.Dicc(
                arbol.entradas + mapOf(
                    "Kids" to PdfValor.Lista(hijos + PdfValor.Ref(pagina, 0)),
                    // La cuenta se saca de los hijos y no del `/Count` que
                    // hubiera: un archivo remendado puede traerlo mal, y un
                    // `/Count` que no cuadra con los hijos deja el documento
                    // con páginas que unos lectores enseñan y otros no.
                    "Count" to PdfValor.Numero((hijos.size + 1).toDouble())
                )
            )
        )

        return PdfEscritura.incremental(archivo, objetos)
    }

    /** El `/Contents` de una página, siempre como lista de referencias. */
    private fun contenidosDe(pagina: PdfValor.Dicc): List<PdfValor> =
        when (val c = pagina.entradas["Contents"]) {
            null -> emptyList()
            is PdfValor.Lista -> c.valores
            else -> listOf(c)
        }

    /**
     * Los recursos de la página con los nuestros dentro.
     *
     * Se **resuelven heredando**: un PDF puede declarar los recursos una sola
     * vez en la raíz del árbol de páginas, y una hoja que no los traiga los toma
     * del padre. Escribir en la hoja un diccionario que solo tenga lo nuestro le
     * quitaría a esa página sus propias fuentes e imágenes — o sea, la dejaría
     * en blanco.
     */
    private fun fusionarRecursos(
        archivo: PdfArchivo,
        pagina: PdfValor.Dicc,
        nuestros: PdfValor.Dicc?
    ): PdfValor {
        val heredados = recursosHeredados(archivo, pagina)
        if (nuestros == null) return heredados ?: PdfValor.Dicc(emptyMap())
        val base = heredados?.entradas.orEmpty()
        val mezcla = base.toMutableMap()
        for ((clave, valor) in nuestros.entradas) {
            val viejo = archivo.diccDe(base[clave])
            val nuevo = valor as? PdfValor.Dicc
            mezcla[clave] =
                if (viejo != null && nuevo != null) PdfValor.Dicc(viejo.entradas + nuevo.entradas)
                else valor
        }
        return PdfValor.Dicc(mezcla)
    }

    private fun recursosHeredados(archivo: PdfArchivo, pagina: PdfValor.Dicc): PdfValor.Dicc? {
        var d: PdfValor.Dicc? = pagina
        var saltos = 0
        while (d != null && saltos++ < 32) {
            archivo.diccDe(d.entradas["Resources"])?.let { return it }
            d = archivo.diccDe(d.entradas["Parent"])
        }
        return null
    }

    /** Un flujo con sus datos comprimidos, que es como se escribe todo aquí. */
    fun flujoComprimido(datos: ByteArray): PdfValor.Flujo = PdfValor.Flujo(
        PdfValor.Dicc(mapOf("Filter" to PdfValor.Nombre("FlateDecode"))),
        PdfEscritura.desinflar(datos)
    )

    // ---------------------------------------------------------------------
    // De la imagen que se anotó a las coordenadas del PDF
    // ---------------------------------------------------------------------

    /**
     * La matriz que lleva **los píxeles que tocaste a los puntos del papel**.
     *
     * Es la pieza en la que se apoya todo lo demás. Tú anotas sobre una imagen
     * de la página; el PDF mide en puntos, con el origen abajo a la izquierda y
     * la Y hacia arriba, y encima la hoja puede estar girada. En vez de
     * convertir punto por punto —y equivocarse en uno de cada tantos—, se
     * escribe **una sola matriz al principio** y a partir de ahí el dibujo se
     * anota con exactamente los mismos números que usa el motor por dentro.
     *
     * Las cuatro esquinas de la imagen caen en las cuatro esquinas del papel.
     * Eso es lo que hace que lo dibujado quede donde lo pusiste aunque la hoja
     * venga girada, que es de lo más normal en un plano.
     *
     * Devuelve `[a, b, c, d, e, f]`, que es como lo escribe un `cm`.
     */
    fun matrizDePagina(
        archivo: PdfArchivo,
        indicePagina: Int,
        anchoImagen: Double,
        altoImagen: Double
    ): DoubleArray? {
        if (anchoImagen <= 0 || altoImagen <= 0) return null
        val caja = cajaDePagina(archivo, indicePagina) ?: return null
        val giro = giroDePagina(archivo, indicePagina)
        val (x0, y0, x1, y1) = caja
        val ancho = abs(x1 - x0)
        val alto = abs(y1 - y0)
        if (ancho <= 0 || alto <= 0) return null

        // Cuántos puntos mide un píxel de la imagen. Se toma del lado que la
        // imagen y la hoja comparten según el giro.
        val porPixel = if (giro == 90 || giro == 270) alto / anchoImagen else ancho / anchoImagen
        if (porPixel <= 0 || !porPixel.isFinite()) return null
        val s = porPixel

        return when (giro) {
            90 -> doubleArrayOf(0.0, s, s, 0.0, x0, y0)
            180 -> doubleArrayOf(-s, 0.0, 0.0, s, x1, y0)
            270 -> doubleArrayOf(0.0, -s, -s, 0.0, x1, y1)
            else -> doubleArrayOf(s, 0.0, 0.0, -s, x0, y1)
        }
    }

    /** El `/MediaBox` de la página, heredando del padre si hace falta. */
    fun cajaDePagina(archivo: PdfArchivo, indicePagina: Int): DoubleArray? {
        var d = archivo.pagina(indicePagina) ?: return null
        var saltos = 0
        while (saltos++ < 32) {
            val caja = (archivo.resolver(d.entradas["MediaBox"]) as? PdfValor.Lista)?.valores
            if (caja != null && caja.size >= 4) {
                val n = caja.mapNotNull { (archivo.resolver(it) as? PdfValor.Numero)?.valor }
                if (n.size >= 4) {
                    // La caja puede venir con las esquinas al revés; se ordena.
                    return doubleArrayOf(
                        minOf(n[0], n[2]), minOf(n[1], n[3]),
                        maxOf(n[0], n[2]), maxOf(n[1], n[3])
                    )
                }
            }
            d = archivo.diccDe(d.entradas["Parent"]) ?: return null
        }
        return null
    }

    /**
     * Cuánto está girada la hoja, en grados y siempre 0, 90, 180 o 270.
     *
     * También se hereda, y también hay archivos que lo escriben en negativo o
     * pasado de vuelta: `-90` y `630` son los dos 270.
     */
    fun giroDePagina(archivo: PdfArchivo, indicePagina: Int): Int {
        var d = archivo.pagina(indicePagina) ?: return 0
        var saltos = 0
        while (saltos++ < 32) {
            val r = (archivo.resolver(d.entradas["Rotate"]) as? PdfValor.Numero)?.valor
            if (r != null) return (((r.toInt() % 360) + 360) % 360) / 90 * 90
            d = archivo.diccDe(d.entradas["Parent"]) ?: return 0
        }
        return 0
    }

    private operator fun DoubleArray.component4(): Double = this[3]
}
