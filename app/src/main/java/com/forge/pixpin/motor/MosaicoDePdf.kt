package com.forge.pixpin.motor

/**
 * **Las cuentas del plano partido en hojas pequeñas.**
 *
 * Un plano rasterizado de una pieza tiene el detalle que tenga esa imagen y ni uno más:
 * acercarse a leer una cota devuelve una mancha. La solución es partirlo en cuadros y
 * rasterizar **cada cuadro a su tamaño**, con lo que el mismo plano acaba teniendo varias
 * veces más píxeles sin que ninguna imagen suelta sea gigante.
 *
 * ## Una sola rejilla, y hecha entera
 *
 * La primera versión tenía **pirámide de niveles**, como las teselas de un mapa: cada nivel
 * doblaba la resolución y se pedía el que hiciera falta al acercarse. Eso es lo correcto en
 * un mapa **porque las teselas se bajan de internet** y traerlas todas sería absurdo. Aquí el
 * archivo está en el teléfono, así que la pirámide solo aportaba lo malo: al acercarse había
 * que rasterizar otra vez, y se veía cargar por pedazos. Lo dijo el usuario y tiene razón
 * (3-sep-2026): «lo de Google Maps era una analogía; aquí pon varias imágenes pequeñas y ya».
 *
 * Así que hay **una sola rejilla**, a la mejor resolución que quepa en el presupuesto de
 * memoria, hecha entera de una vez al abrir. A partir de ahí acercarse y alejarse no
 * rasteriza nada: son mapas de bits que ya están.
 *
 * Todo esto son cuentas y no toca Android: se comprueba sin dispositivo. Quien rasteriza es
 * [PdfDoc.trozos] y quien lo guarda, [ElMosaicoDelPapel].
 */
object MosaicoDePdf {

    /** A cuántos píxeles de lado se rasteriza cada cuadro. */
    const val LADO_EN_PIXELES = 512

    /**
     * **En qué calidad se guarda cada cuadro**, de 0 a 100.
     *
     * Los cuadros no se guardan en crudo sino en WEBP: medio mega cada uno pasa a ser unas
     * decenas de kilobytes, y así el plano entero se escribe en el teléfono ([CuadrosEnDisco])
     * en vez de ir soltando trozos y volviendo al PDF a por ellos. Noventa y dos es alta calidad —en un plano, que es
     * líneas negras sobre blanco, no se distingue del crudo— y sigue siendo ligero. Lo pidió
     * así el usuario (4-sep-2026): «un formato que sea ligero y de alta calidad».
     */
    const val CALIDAD_DEL_CUADRO = 92

    /**
     * **Cuánta memoria se le concede a los cuadros abiertos**, en bytes.
     *
     * Los cuadros van en `RGB_565` —dos bytes por píxel, y una página no tiene transparencia—
     * así que con esto salen unos treinta y dos millones de píxeles: cinco mil de ancho en un
     * A0, o casi cinco mil en un A4, que son seiscientos puntos por pulgada. Es lo que ocupa
     * un puñado de fotos abiertas, y a cambio la página se lee.
     *
     * Es el de fábrica: quien lo monta pasa el que le corresponda a **este** teléfono. Ver
     * [presupuestoDe].
     */
    const val PRESUPUESTO: Long = 64L * 1024 * 1024

    /**
     * **Lo que se le puede conceder a este aparato**, a partir de lo que Android dice que le
     * da a la aplicación ([memoriaDeLaApp], en megabytes).
     *
     * Una cuarta parte: el resto hace falta para el propio dibujo, sus imágenes y el editor.
     * Con un teléfono holgado sale el presupuesto entero y la página se afina todo lo que se
     * puede; con uno justo baja, y lo que pasa entonces es que la página se ve algo menos
     * fina —no que la aplicación se quede sin memoria, que es lo que no puede pasar—.
     */
    fun presupuestoDe(memoriaDeLaApp: Int): Long {
        val cuarto = memoriaDeLaApp.toLong() * 1024 * 1024 / 4
        return cuarto.coerceIn(MINIMO_DEL_PRESUPUESTO, PRESUPUESTO)
    }

    /** Por debajo de esto no se baja: sería peor que no partir la página. */
    const val MINIMO_DEL_PRESUPUESTO: Long = 24L * 1024 * 1024

    /** Lo más y lo menos que se afina, en píxeles por unidad del dibujo. */
    const val LO_MAS_FINO = 16.0
    const val LO_MENOS_FINO = 1.5

    /**
     * **Cuántos píxeles se le dedican al plano entero.**
     *
     * Cuatrocientos millones: en el A0 del proyecto que sirve de banco de pruebas salen unos
     * dieciséis mil por veinticuatro mil píxeles —**508 puntos por pulgada** sobre el papel—,
     * mil quinientos cuadros y cuarenta y siete pasadas al PDF, o sea un par de minutos la
     * primera vez que se abre y nunca más. Una cota escrita a dos milímetros queda con
     * cuarenta píxeles de alto.
     *
     * Estuvo en doscientos millones (359 ppp, la cota a 28 px) y el usuario pidió más detalle
     * (4-sep-2026). Subirlo otra vez se puede —el tope de [LO_MAS_FINO] son 676 ppp— pero el
     * trabajo y el disco crecen con el cuadrado: al doble de fineza, cuatro veces todo.
     */
    const val PIXELES_DEL_PLANO = 400_000_000.0

    /**
     * **A cuántos píxeles por unidad del dibujo se rasteriza el papel.**
     *
     * Esto se probó antes atado a **los puntos por pulgada del papel de verdad** —260, que es
     * lo que hace falta para imprimir— y el resultado seguía viéndose con grano. El motivo es
     * que en una pantalla el papel no se mira a su tamaño: el dibujo mide siempre 1400
     * unidades de ancho sea un A4 o un A0, y se puede ampliar hasta [Viewport.MAX_ZOOM]. Lo
     * que se ve, entonces, no lo decide el tamaño físico del papel sino **cuántos píxeles de
     * imagen hay por unidad del dibujo frente al aumento**: con 260 ppp un A4 salía a 1,53
     * píxeles por unidad, o sea con grano en cuanto se pasaba del doble de aumento.
     *
     * Así que se reparten [PIXELES_DEL_PLANO] entre lo que mide el dibujo. En una hoja normal
     * salen unos ocho píxeles por unidad: se ve limpio hasta unos ocho aumentos, que es más
     * de diez veces el papel entero en la pantalla. Más allá, y hasta los cuatrocientos
     * aumentos que deja el lienzo, **ninguna imagen fija llega**: eso solo lo da el dibujo
     * vectorial.
     */
    fun pixelesPorUnidad(anchoEnUnidades: Double, altoEnUnidades: Double): Double {
        if (anchoEnUnidades <= 0 || altoEnUnidades <= 0) return LO_MENOS_FINO
        val f = Math.sqrt(PIXELES_DEL_PLANO / (anchoEnUnidades * altoEnUnidades))
        return f.coerceIn(LO_MENOS_FINO, LO_MAS_FINO)
    }

    /**
     * **A qué escala se descomprime un cuadro para este aumento.**
     *
     * Un cuadro guardado tiene [LADO_EN_PIXELES] de lado, pero mirando el plano entero esos
     * píxeles no se ven: se están encogiendo a un puñado. Descomprimirlo a la mitad, a un
     * cuarto o a un octavo —que es lo que sabe hacer `inSampleSize`— da **exactamente lo que
     * se ve** y ocupa el cuadrado de menos: un cuadro a un octavo son ocho kilobytes en vez
     * de medio mega. Así el plano entero se pinta con sus propios cuadros, sin volver a la
     * imagen de una pieza y sin volver al PDF: solo se lee el mismo archivo de otra manera.
     *
     * [zoom] es cuántos píxeles de pantalla mide una unidad del dibujo.
     */
    fun muestraPara(fineza: Double, zoom: Double): Int {
        if (zoom <= 0 || fineza <= 0) return 1
        var m = 1
        while (m < MUESTRA_MAXIMA && fineza / (m * 2) >= zoom) m *= 2
        return m
    }

    /** Más allá de esto no se encoge: serían cuadros de dieciséis píxeles. */
    const val MUESTRA_MAXIMA = 32

    /**
     * **Cuántos cuadros se tienen abiertos —descomprimidos— a la vez.**
     *
     * En crudo, el papel entero de un plano grande no cabe: un A0 a doscientos sesenta puntos
     * por pulgada son casi trescientos megas. Así que abiertos se tienen **los de alrededor de
     * lo que se mira** y se cierran los de más allá; volver a ellos es descomprimir el que se
     * guardó ([CALIDAD_DEL_CUADRO]), que son milisegundos, no rasterizar otra vez el PDF.
     *
     * La calidad no depende de esto: es la misma en todo el plano.
     */
    fun cuantosCaben(presupuesto: Long): Int {
        val porCuadro = LADO_EN_PIXELES.toLong() * LADO_EN_PIXELES * 2
        return (presupuesto / porCuadro).toInt().coerceAtLeast(MINIMO_DE_CUADROS)
    }

    /** Nunca menos que esto: hay que poder tapar la pantalla y su contorno. */
    const val MINIMO_DE_CUADROS = 24

    /**
     * **Cuánta memoria se le deja a la franja que se está rasterizando**, en bytes.
     *
     * Una franja se pide a `PdfRenderer`, que solo sabe dar `ARGB_8888`: cuatro bytes por
     * píxel y es un pico —en cuanto está troceada en cuadros se suelta—. Cuarenta y ocho megas
     * dan para una tira del ancho del plano y un cuadro de alto, y en un plano de los que no
     * piden tanta fineza, para dos filas de una vez: la mitad de pasadas.
     */
    const val PRESUPUESTO_DE_LA_FRANJA: Long = 48L * 1024 * 1024

    /**
     * **Cuántas filas de cuadros se rasterizan de una vez.**
     *
     * Al menos una: aunque no quepa en el presupuesto, hay que poder hacer algo. Las franjas
     * van en filas enteras de cuadros para que ningún cuadro quede partido entre dos.
     */
    fun filasPorFranja(columnas: Int, presupuesto: Long = PRESUPUESTO_DE_LA_FRANJA): Int {
        if (columnas <= 0) return 1
        val porFila = columnas.toLong() * LADO_EN_PIXELES * LADO_EN_PIXELES * 4
        return (presupuesto / porFila).toInt().coerceAtLeast(1)
    }

    /** En qué franja cae un cuadro. */
    fun franjaDe(c: Cuadro, filasPorFranja: Int): Int = c.fila / filasPorFranja.coerceAtLeast(1)

    /** Cuántas franjas hacen falta para el plano entero. */
    fun cuantasFranjas(filas: Int, filasPorFranja: Int): Int {
        if (filas <= 0 || filasPorFranja <= 0) return 0
        return (filas + filasPorFranja - 1) / filasPorFranja
    }

    /**
     * **Los cuadros de alrededor de [vista]**, para tener hecho también lo que hay justo
     * fuera de la pantalla: así al apartar el dedo ya está, en vez de rasterizarse entonces.
     */
    fun losDeAlrededor(vista: Bounds, ancho: Double, alto: Double, lado: Double): List<Cuadro> =
        losDe(
            Bounds(
                vista.x1 - lado, vista.y1 - lado, vista.x2 + lado, vista.y2 + lado
            ),
            ancho, alto, lado
        )

    /** Lo que mide un cuadro en unidades del dibujo, a esa resolución. */
    fun ladoDelCuadro(pixelesPorUnidad: Double): Double =
        LADO_EN_PIXELES / pixelesPorUnidad.coerceAtLeast(1e-6)

    /** Un cuadro de la rejilla. */
    data class Cuadro(val columna: Int, val fila: Int)

    /** Dónde cae un cuadro en el dibujo. */
    fun donde(c: Cuadro, lado: Double): Bounds =
        Bounds(c.columna * lado, c.fila * lado, (c.columna + 1) * lado, (c.fila + 1) * lado)

    /** Cuántas columnas y cuántas filas hacen falta para tapar el papel. */
    fun rejilla(ancho: Double, alto: Double, lado: Double): Pair<Int, Int> {
        if (ancho <= 0 || alto <= 0 || lado <= 0) return 0 to 0
        return Math.ceil(ancho / lado).toInt() to Math.ceil(alto / lado).toInt()
    }

    /** **Todos los cuadros del plano**, que es lo que se rasteriza al abrir. */
    fun todos(ancho: Double, alto: Double, lado: Double): List<Cuadro> {
        val (columnas, filas) = rejilla(ancho, alto, lado)
        if (columnas <= 0 || filas <= 0) return emptyList()
        val salida = ArrayList<Cuadro>(columnas * filas)
        for (f in 0 until filas) for (c in 0 until columnas) salida += Cuadro(c, f)
        return salida
    }

    /** Los cuadros que tapan [vista], sin salirse del papel. */
    fun losDe(vista: Bounds, ancho: Double, alto: Double, lado: Double): List<Cuadro> {
        if (ancho <= 0 || alto <= 0 || lado <= 0) return emptyList()
        val (columnas, filas) = rejilla(ancho, alto, lado)
        val c0 = Math.floor(maxOf(vista.x1, 0.0) / lado).toInt().coerceIn(0, columnas - 1)
        val f0 = Math.floor(maxOf(vista.y1, 0.0) / lado).toInt().coerceIn(0, filas - 1)
        // Un borde justo en la costura no pide la columna de al lado: se resta un pelo.
        val c1 = Math.floor((minOf(vista.x2, ancho) - 1e-9) / lado).toInt().coerceIn(0, columnas - 1)
        val f1 = Math.floor((minOf(vista.y2, alto) - 1e-9) / lado).toInt().coerceIn(0, filas - 1)
        if (vista.x2 <= 0 || vista.y2 <= 0 || vista.x1 >= ancho || vista.y1 >= alto) return emptyList()
        val salida = ArrayList<Cuadro>((c1 - c0 + 1) * (f1 - f0 + 1))
        for (f in f0..f1) for (c in c0..c1) salida += Cuadro(c, f)
        return salida
    }

    /**
     * **En qué orden se rasterizan.**
     *
     * Empezando por los que se están mirando y siguiendo hacia fuera: lo que uno tiene
     * delante se afina primero y el resto va llegando mientras tanto. Sin esto, un plano de
     * doscientos cuadros empieza por la esquina de arriba y tarda en llegar a donde miras.
     */
    fun porDondeEmpezar(todos: List<Cuadro>, mirando: Bounds, lado: Double): List<Cuadro> {
        if (lado <= 0) return todos
        val cx = (mirando.x1 + mirando.x2) / 2 / lado
        val cy = (mirando.y1 + mirando.y2) / 2 / lado
        return todos.sortedBy { c ->
            val dx = c.columna + 0.5 - cx
            val dy = c.fila + 0.5 - cy
            dx * dx + dy * dy
        }
    }

    // ---------------------------------------------------------------------------------
    // La lámina de cerca
    // ---------------------------------------------------------------------------------

    /**
     * **Lo que se rasteriza para leer de cerca**: un pedazo del papel y a cuántos píxeles.
     *
     * Ver [laminaPara] y `LaminaDeCerca`.
     */
    data class Lamina(
        /** Qué pedazo del papel, en unidades del dibujo. */
        val donde: Bounds,
        /** Y a cuántos píxeles por unidad se rasteriza: eso es lo que se lee. */
        val fineza: Double,
        val ancho: Int,
        val alto: Int
    )

    /**
     * **Cuántos píxeles se le dejan a la lámina de cerca.**
     *
     * Es la pantalla y un poco más. Cuatro millones son unos dos mil por dos mil: más que
     * cualquier teléfono, y a `PdfRenderer` hay que pedírselos en `ARGB_8888` —dieciséis
     * megas mientras se monta, ocho ya guardada en `RGB_565`—. Subirlo no mejora nada: por
     * encima de los píxeles que tiene la pantalla no hay nada que ver.
     */
    const val PIXELES_DE_LA_LAMINA = 4_000_000.0

    /**
     * **Cuánto más de lo que se ve se rasteriza.**
     *
     * Un cuarto por cada lado: así apartar el dedo un poco no obliga a volver al PDF, que es
     * lo caro. Más margen sería pagar píxeles que casi nunca se miran.
     */
    const val MARGEN_DE_LA_LAMINA = 1.25

    /**
     * **Hasta cuánto se estira una lámina antes de pedir otra.**
     *
     * Sin holgura, un pellizco de nada dejaría la lámina caducada y se volvería al PDF a cada
     * gesto. Con un cuarto de margen, acercarse un poco se resuelve estirando la que hay —se
     * ve un pelo más blanda— y solo se rasteriza otra vez cuando de verdad hace falta.
     */
    const val HOLGURA_DE_LA_LAMINA = 1.25

    /**
     * **Qué hay que rasterizar para que lo que se está mirando se lea.**
     *
     * Los cuadros del plano tienen los píxeles que tienen ([pixelesPorUnidad]) y ni uno más:
     * pasado ese aumento, por bien hecho que esté el mosaico, lo que se ve es la imagen
     * estirada. Y el lienzo llega a [Viewport.MAX_ZOOM] = 400 aumentos, así que **ninguna
     * imagen fija llega**: la única manera de leer una letra de cerca es volver al PDF y
     * pedirle **ese pedazo, a la resolución de la pantalla**, que es lo que hace cualquier
     * lector de PDF de escritorio.
     *
     * Aquí eso no es caro por lo que se rasteriza —es una pantalla— sino por la pasada al
     * documento, que en un plano de verdad son segundos. Por eso se pide con margen
     * ([MARGEN_DE_LA_LAMINA]) y se estira mientras vale ([HOLGURA_DE_LA_LAMINA]).
     *
     * [zoom] es cuántos píxeles de pantalla mide una unidad del dibujo. Devuelve nulo si no
     * hay nada que rasterizar: la vista se ha ido fuera del papel, o no hay aumento.
     */
    fun laminaPara(
        vista: Bounds,
        ancho: Double,
        alto: Double,
        zoom: Double,
        presupuesto: Double = PIXELES_DE_LA_LAMINA
    ): Lamina? {
        if (zoom <= 0 || ancho <= 0 || alto <= 0) return null
        if (vista.width <= 0 || vista.height <= 0) return null

        // Con margen, y sin salirse del papel: rasterizar fuera de la página es rasterizar
        // blanco.
        val holgadoX = vista.width * (MARGEN_DE_LA_LAMINA - 1) / 2
        val holgadoY = vista.height * (MARGEN_DE_LA_LAMINA - 1) / 2
        val x1 = maxOf(vista.x1 - holgadoX, 0.0)
        val y1 = maxOf(vista.y1 - holgadoY, 0.0)
        val x2 = minOf(vista.x2 + holgadoX, ancho)
        val y2 = minOf(vista.y2 + holgadoY, alto)
        if (x2 - x1 <= 0 || y2 - y1 <= 0) return null
        val donde = Bounds(x1, y1, x2, y2)

        // **Si no cabe a la resolución de la pantalla, se baja la resolución y no el trozo.**
        // Recortar el trozo dejaría media pantalla sin lámina, que es peor que verla toda un
        // punto más blanda. Solo pasa con pantallas enormes y márgenes grandes.
        val piden = donde.width * zoom * donde.height * zoom
        val fineza = if (piden <= presupuesto) zoom else zoom * Math.sqrt(presupuesto / piden)
        val anchoPx = Math.round(donde.width * fineza).toInt().coerceAtLeast(1)
        val altoPx = Math.round(donde.height * fineza).toInt().coerceAtLeast(1)
        return Lamina(donde, fineza, anchoPx, altoPx)
    }

    /**
     * **Si la lámina que ya está hecha sirve para lo que se mira ahora.**
     *
     * Sirve mientras tape la vista entera y tenga píxeles de sobra para el aumento. Alejarse
     * no la caduca —le sobran píxeles— pero apartarse sí, en cuanto asoma un borde: ahí
     * debajo lo que hay es el mosaico, que no es un hueco pero sí es más gordo.
     */
    fun valeLaLamina(
        l: Lamina,
        vista: Bounds,
        zoom: Double,
        holgura: Double = HOLGURA_DE_LA_LAMINA
    ): Boolean {
        if (zoom <= 0) return false
        if (l.fineza * holgura < zoom) return false
        return vista.x1 >= l.donde.x1 - 1e-6 && vista.y1 >= l.donde.y1 - 1e-6 &&
            vista.x2 <= l.donde.x2 + 1e-6 && vista.y2 <= l.donde.y2 + 1e-6
    }

    /**
     * **Si a este aumento hace falta volver al PDF.**
     *
     * Mientras los cuadros del mosaico tengan más píxeles por unidad que el aumento, lo que
     * se ve son sus píxeles y la lámina no aporta nada. Pasado eso, el mosaico se está
     * estirando y **cada aumento se ve peor**. Un pelo de margen para no montar una lámina
     * por un uno por ciento.
     */
    fun haceFaltaLamina(finezaDelMosaico: Double, zoom: Double): Boolean =
        zoom > finezaDelMosaico * 1.05
}
