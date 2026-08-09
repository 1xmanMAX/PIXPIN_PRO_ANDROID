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
    fun anotar(
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
