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
     * contenido opcional—: un formulario suelto que el contenido de la página
     * pinta al final, marcado como de esa capa (`/OC … BDC … EMC`).
     *
     * **Antes iba en una anotación** (un sello) y así la página no se tocaba.
     * Pero el lector de PDF de Android (`PdfRenderer`, el de PixPin y el de
     * muchos visores del móvil) **no pinta las anotaciones**: el usuario exportaba
     * y veía el PDF limpio (30-sep-2026). Dentro del contenido la pinta todo el
     * mundo. Lo que se conserva:
     *
     * - **Se puede apagar.** En Acrobat, Foxit, PDF-XChange u Okular sale un
     *   panel de capas y se enciende y se apaga: se ve el plano limpio o el
     *   plano con tus marcas, sin dos archivos.
     * - **Lo de la página no se reescribe**: se le añaden dos flujos, uno que
     *   guarda la pila gráfica antes (`q`) y otro que la repone y pinta encima.
     * - **Cada tanda es su capa.** Anotar otro día añade otra, con su nombre, y
     *   se pueden mirar por separado.
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

        // Y la página la pinta al final, marcada como de la capa. Dos flujos
        // nuevos alrededor de los suyos: la pila gráfica que dejen a medias no
        // nos mueve el dibujo. La caja del formulario es la de la página, y su
        // matriz la identidad: cae justo donde caía el sello de antes.
        val abrir = pedirNumero()
        val pintar = pedirNumero()
        val nombreForma = "${PREFIJO}T$forma"
        val nombreCapa = "${PREFIJO}OC$capa"
        objetos += ObjetoPdf(abrir, flujoComprimido("q\n".toByteArray(Charsets.ISO_8859_1)))
        objetos += ObjetoPdf(
            pintar,
            flujoComprimido("Q\nq\n/OC /$nombreCapa BDC\n/$nombreForma Do\nEMC\nQ\n".toByteArray(Charsets.ISO_8859_1))
        )
        val nuestros = PdfValor.Dicc(
            mapOf(
                "XObject" to PdfValor.Dicc(mapOf(nombreForma to PdfValor.Ref(forma, 0))),
                "Properties" to PdfValor.Dicc(mapOf(nombreCapa to PdfValor.Ref(capa, 0)))
            )
        )
        objetos += ObjetoPdf(
            numeroPagina,
            PdfValor.Dicc(
                pagina.entradas + mapOf(
                    "Contents" to PdfValor.Lista(listOf(PdfValor.Ref(abrir, 0)) + contenidosDe(pagina) + PdfValor.Ref(pintar, 0)),
                    "Resources" to fusionarRecursos(archivo, pagina, nuestros)
                )
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

    // ---------------------------------------------------------------------
    // Ensanchar la hoja y el índice
    // ---------------------------------------------------------------------

    /**
     * **La hoja más ancha**, [izquierda] y [derecha] puntos a cada lado (29-sep-2026): los
     * márgenes para anotar del lector, dentro del PDF. Cambia `/MediaBox` y `/CropBox` de la
     * página y nada más: lo que había sigue en su sitio, porque el origen no se mueve.
     *
     * Solo con la hoja derecha: girada, lo que en pantalla es «a los lados» es otro eje de la caja,
     * y el lector no pone márgenes ahí. Null si no se puede.
     */
    fun ensanchar(archivo: PdfArchivo, indicePagina: Int, izquierda: Double, derecha: Double): ByteArray? {
        if (archivo.cifrado || (izquierda <= 0 && derecha <= 0)) return null
        if (giroDePagina(archivo, indicePagina) != 0) return null
        val numero = archivo.paginas().getOrNull(indicePagina) ?: return null
        val pagina = archivo.diccDe(PdfValor.Ref(numero, 0)) ?: return null
        val (x0, y0, x1, y1) = cajaDePagina(archivo, indicePagina) ?: return null
        val caja = PdfValor.Lista(listOf(x0 - izquierda, y0, x1 + derecha, y1).map { PdfValor.Numero(it) })
        return PdfEscritura.incremental(
            archivo,
            listOf(ObjetoPdf(numero, PdfValor.Dicc(pagina.entradas + mapOf("MediaBox" to caja, "CropBox" to caja))))
        )
    }

    /** Un marcador del índice: su título, la hoja (desde 0) y a qué altura de ella, de 0 (arriba) a 1. */
    data class Marcador(val titulo: String, val pagina: Int, val alto: Double)

    /**
     * **Los marcadores, en el índice del PDF** (`/Outlines`), detrás de los que ya tuviera: el
     * panel de marcadores de cualquier lector los enseña y llevan a su hoja, a su altura.
     * Null si no se puede o no hay ninguno.
     */
    fun conMarcadores(archivo: PdfArchivo, marcadores: List<Marcador>): ByteArray? {
        if (archivo.cifrado) return null
        val paginas = archivo.paginas()
        val validos = marcadores.filter { it.pagina in paginas.indices }
        if (validos.isEmpty()) return null
        val numeroCatalogo = (archivo.trailer.entradas["Root"] as? PdfValor.Ref)?.numero ?: return null
        val catalogo = archivo.diccDe(PdfValor.Ref(numeroCatalogo, 0)) ?: return null

        var siguiente = PdfEscritura.primerNumeroLibre(archivo)
        val objetos = ArrayList<ObjetoPdf>()
        val existente = catalogo.entradas["Outlines"] as? PdfValor.Ref
        val raiz = existente?.let { archivo.diccDe(it) }
        val numeroRaiz = if (raiz != null) existente.numero else siguiente++
        val nuestros = validos.map { siguiente++ }
        val ultimoViejo = raiz?.entradas?.get("Last") as? PdfValor.Ref

        validos.forEachIndexed { k, m ->
            val caja = cajaDePagina(archivo, m.pagina)
            val arriba = caja?.let { it[3] - m.alto.coerceIn(0.0, 1.0) * (it[3] - it[1]) }
            val destino = PdfValor.Lista(
                listOf(PdfValor.Ref(paginas[m.pagina], 0), PdfValor.Nombre("XYZ"), PdfValor.Nulo,
                    arriba?.let { PdfValor.Numero(it) } ?: PdfValor.Nulo, PdfValor.Nulo)
            )
            objetos += ObjetoPdf(nuestros[k], PdfValor.Dicc(buildMap {
                put("Title", PdfValor.Cadena(textoPdf(m.titulo)))
                put("Parent", PdfValor.Ref(numeroRaiz, 0))
                put("Dest", destino)
                if (k > 0) put("Prev", PdfValor.Ref(nuestros[k - 1], 0))
                else if (ultimoViejo != null) put("Prev", ultimoViejo)
                if (k < nuestros.size - 1) put("Next", PdfValor.Ref(nuestros[k + 1], 0))
            }))
        }
        // El último de los que había apunta al primero nuestro.
        if (ultimoViejo != null) archivo.diccDe(ultimoViejo)?.let { d ->
            objetos += ObjetoPdf(ultimoViejo.numero, PdfValor.Dicc(d.entradas + ("Next" to PdfValor.Ref(nuestros.first(), 0))))
        }
        val cuenta = ((archivo.resolver(raiz?.entradas?.get("Count")) as? PdfValor.Numero)?.valor ?: 0.0).let { if (it < 0) 0.0 else it }
        objetos += ObjetoPdf(numeroRaiz, PdfValor.Dicc(raiz?.entradas.orEmpty() + buildMap {
            put("Type", PdfValor.Nombre("Outlines"))
            if (raiz?.entradas?.get("First") == null) put("First", PdfValor.Ref(nuestros.first(), 0))
            put("Last", PdfValor.Ref(nuestros.last(), 0))
            put("Count", PdfValor.Numero(cuenta + nuestros.size))
        }))
        if (existente == null) {
            objetos += ObjetoPdf(numeroCatalogo, PdfValor.Dicc(catalogo.entradas + mapOf(
                "Outlines" to PdfValor.Ref(numeroRaiz, 0),
                // Que el lector abra con el panel de marcadores, si el documento no decía otra cosa.
                "PageMode" to (catalogo.entradas["PageMode"] ?: PdfValor.Nombre("UseOutlines"))
            )))
        }
        return PdfEscritura.incremental(archivo, objetos)
    }

    /**
     * **Los marcadores que ya trae el PDF**, en su índice (`/Outlines`): lo inverso de
     * [conMarcadores] (30-sep-2026). Un PDF exportado desde PixPin lleva ahí sus marcadores, y el
     * lector los recoge al abrirlo por primera vez; también los de cualquier otro documento.
     * Van en orden de lectura, con los de dentro de cada uno detrás de él. Los que apuntan a un
     * destino con nombre o a otra cosa que una hoja se saltan.
     */
    fun marcadoresDelIndice(archivo: PdfArchivo, tope: Int = 200): List<Marcador> {
        if (archivo.cifrado) return emptyList()
        val catalogo = archivo.diccDe(archivo.trailer.entradas["Root"]) ?: return emptyList()
        val raiz = archivo.diccDe(catalogo.entradas["Outlines"]) ?: return emptyList()
        val porNumero = archivo.paginas().withIndex().associate { (i, n) -> n to i }
        val salida = ArrayList<Marcador>()
        val vistos = HashSet<Int>()
        fun recorrer(primero: PdfValor?, fondo: Int) {
            var ref = primero as? PdfValor.Ref
            while (ref != null && salida.size < tope && fondo < 16 && vistos.add(ref.numero)) {
                val m = archivo.diccDe(ref) ?: break
                val destino = archivo.resolver(m.entradas["Dest"])
                    ?: (archivo.diccDe(m.entradas["A"])?.takeIf { it.nombre("S") == "GoTo" }?.let { archivo.resolver(it.entradas["D"]) })
                val lista = (destino as? PdfValor.Lista)?.valores
                val pagina = (lista?.firstOrNull() as? PdfValor.Ref)?.let { porNumero[it.numero] }
                if (pagina != null) {
                    val titulo = (archivo.resolver(m.entradas["Title"]) as? PdfValor.Cadena)?.bytes?.let { textoDePdf(it) }.orEmpty()
                    // Con `/XYZ izquierda arriba zoom`, la altura; si no, lo alto de la hoja.
                    val arriba = if ((lista.getOrNull(1) as? PdfValor.Nombre)?.valor == "XYZ")
                        (archivo.resolver(lista.getOrNull(3)) as? PdfValor.Numero)?.valor else null
                    val caja = cajaDePagina(archivo, pagina)
                    val alto = if (arriba != null && caja != null && caja[3] > caja[1])
                        ((caja[3] - arriba) / (caja[3] - caja[1])).coerceIn(0.0, 1.0) else 0.0
                    salida += Marcador(titulo.trim(), pagina, alto)
                }
                recorrer(m.entradas["First"], fondo + 1)
                ref = m.entradas["Next"] as? PdfValor.Ref
            }
        }
        recorrer(raiz.entradas["First"], 0)
        return salida
    }

    /** Un texto del PDF: UTF-16 si trae su marca, y si no, latín-1 (lo que casi siempre es). */
    private fun textoDePdf(b: ByteArray): String =
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else String(b, Charsets.ISO_8859_1)
}
