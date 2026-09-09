package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **El plano de un PDF leído como rayas y no como fotografía.**
 *
 * Un plano de AutoCAD no es una imagen: por dentro son líneas, y por eso se puede mirar de
 * cerca sin que se deshaga. Todo lo que hasta ahora hacía PixPin con un PDF —el mosaico de
 * cuadros, la lámina de cerca, la página que viaja dentro del documento web— pasaba por
 * **rasterizarlo**, o sea, por convertir esas líneas en píxeles; y una vez son píxeles hay un
 * techo: por muchos que se pongan, ampliar más de la cuenta enseña el grano. Ver
 * `MosaicoDePdf` y `LaminaDeCerca`, donde ese techo está medido.
 *
 * Esto lee las líneas. Entra el archivo tal cual y sale la geometría de una página: caminos
 * con su color y su grosor, repartidos por **capas** —las de AutoCAD, que el PDF guarda como
 * grupos de contenido opcional (OCG)— y el texto con su sitio y su tamaño. Con eso, el
 * documento web pinta el plano con el lienzo del navegador y se ve nítido a cualquier
 * aumento, pesando además bastante menos que la fotografía que sustituye. Ver [PlanoWeb].
 *
 * ## Qué entiende y qué no
 *
 * Entiende lo que un plano usa: caminos (`m l c v y re h`), pintarlos (`S s f B b n`),
 * la matriz (`cm`), el estado (`q Q w gs`), los colores de los espacios corrientes
 * (`G g RG rg K k` y `cs/CS` + `sc/scn`), las capas (`BDC /OC … EMC`), los recortes
 * rectangulares (`W n`), los formularios (`Do` de un `/Form`) y el texto (`BT … ET`).
 *
 * No entiende, **a propósito**: imágenes —un plano vectorial no tiene, y si las tiene es que
 * es un escaneo y entonces esto no sirve: se cuentan y se avisa con [Plano.sinEntender]—,
 * sombreados (`sh`), patrones, transparencias por grupo y espacios de color raros, que se
 * aproximan a gris. Lo que no se entiende no revienta: se salta.
 *
 * ## Los números en punto fijo
 *
 * Las coordenadas salen ya en **pasos de 1/[FINEZA] de punto** y como enteros. No es un
 * detalle de ahorro: es lo que permite (1) empalmar segmentos comparando extremos con `==`
 * —AutoCAD escribe cada tramo de una polilínea por separado, y volver a unirlos quita la
 * mitad de las órdenes—, y (2) escribir el archivo web como diferencias pequeñas, que es de
 * donde sale que un plano de ochocientos mil segmentos quepa en menos de un mega. Un
 * doscientoscincuentaiseisavo de punto es una milésima de milímetro, o sea que ni ampliando
 * cuatrocientas veces —el tope del lienzo— se llega a ver el escalón.
 */
object PlanoDePdf {

    /** Pasos por punto de las coordenadas. Ver la nota de arriba. */
    const val FINEZA = 256

    /**
     * Tope de puntos de una página.
     *
     * Un plano grande de verdad ronda el millón y medio; pasado el tope se deja de leer y se
     * entrega lo que haya con [Plano.cortado] puesto, que quien llame usará para volver a la
     * imagen. Es lo que evita que un archivo enorme —o roto— se coma la memoria del teléfono.
     */
    const val TOPE_DE_PUNTOS = 6_000_000

    /** Hasta dónde se sigue metiendo un formulario dentro de otro. */
    private const val FONDO_MAXIMO = 12

    /**
     * Cuántas brochas distintas se guardan como mucho.
     *
     * Una brocha es cada combinación de capa, color, grosor y clase de pintada, y en un plano
     * salen unas decenas. Pero un PDF puede traer un degradado hecho de mil líneas de mil
     * colores, y ahí cada raya sería su propia brocha: pasado el tope, el color se redondea y
     * el grosor también, que junta las parecidas en vez de dejar que el recuento crezca sin
     * final.
     */
    internal const val TOPE_DE_BROCHAS = 2048

    // ---------------------------------------------------------------------
    // Lo que sale
    // ---------------------------------------------------------------------

    /** Una capa del plano: en AutoCAD, una capa del dibujo. */
    class Capa(val nombre: String, val encendida: Boolean)

    /**
     * **Un montón de caminos que se pintan igual**: misma capa, mismo color, mismo grosor y
     * misma clase de pintada. Se agrupan así porque el lienzo del navegador cobra por cambio
     * de pincel: mil caminos negros de un pelo son **una** orden de pintar, no mil.
     *
     * [ops] son las órdenes ([MOVER], [LINEA], [CURVA], [CERRAR]) y [xs]/[ys] los puntos que
     * gastan, en el orden en que se gastan: `MOVER` y `LINEA` uno, `CURVA` tres, `CERRAR`
     * ninguno.
     */
    class Brocha(
        val capa: Int,
        /** `0xRRGGBB`. */
        val color: Int,
        /** Opacidad, de 0 a 1. */
        val alfa: Double,
        /** En puntos del papel; 0 es un pelo —lo más fino que dibuje la pantalla—. */
        val grosor: Double,
        val relleno: Boolean,
        /** El rayado, en puntos, o vacío si es continua. */
        val raya: DoubleArray,
        val ops: ByteArray,
        val xs: IntArray,
        val ys: IntArray
    ) {
        val puntos: Int get() = xs.size
    }

    /**
     * Un texto del plano, con la matriz que lo coloca.
     *
     * [a],[b],[c],[d],[x],[y] llevan el texto desde «una letra de un punto de alto apoyada en
     * el origen, escrita hacia la derecha» hasta donde va en el papel, con su giro y su
     * inclinación. [ancho] es lo que ocupa según el PDF, que es lo que permite estirar la
     * letra del navegador hasta que ocupe exactamente eso: sin ese dato, un rótulo escrito con
     * una tipografía que no está en el aparato se saldría de su casilla.
     */
    class Texto(
        val capa: Int,
        val color: Int,
        val alfa: Double,
        val texto: String,
        val a: Double, val b: Double, val c: Double, val d: Double,
        val x: Double, val y: Double,
        val ancho: Double,
        /** `sans-serif`, `serif` o `monospace`, sacado del nombre de la fuente. */
        val familia: String,
        val negrita: Boolean,
        val cursiva: Boolean
    )

    /**
     * **Una foto del PDF que viaja tal cual.**
     *
     * Un plano suele traer alguna: el logotipo del estudio, una ortofoto de fondo. Si el
     * archivo la guarda en JPEG —que es lo corriente— se pasa **sin tocarla**, en los bytes
     * en que ya estaba, y el navegador la dibuja. La matriz lleva el cuadrado de la imagen
     * —con el (0,0) en su esquina de arriba a la izquierda, como se mira— hasta el papel.
     */
    class Imagen(
        val capa: Int,
        val alfa: Double,
        /** El tipo MIME de [datos], para poder escribir el `data:` de la página. */
        val tipo: String,
        val datos: ByteArray,
        val a: Double, val b: Double, val c: Double, val d: Double,
        val x: Double, val y: Double,
        /**
         * **La transparencia de la imagen, si la trae** (`/SMask`), en sus bytes de origen.
         *
         * Es una segunda imagen en gris del mismo tamaño donde el negro es transparente y el
         * blanco opaco. Aquí **no se junta con la de color**: juntarlas es descomprimir dos
         * JPEG y recorrer un millón de píxeles, y esto se lee en una prueba de JVM sin
         * Android. Cada quien la junta a su manera y una sola vez — la pantalla en
         * [PlanoEnPantalla] con un `Bitmap`, la página web en un lienzo suyo.
         *
         * **Por qué importa.** Un plano de Revit pinta así sus zonas de color: no rellena el
         * polígono, sino que pone encima un JPEG de ese color recortado por su máscara. En el
         * plano del usuario (8-sep-2026) eran **21 pares** de imágenes, y como la máscara se
         * rechazaba, las 42 caían en [Plano.sinEntender] y **el plano entero se mandaba como
         * fotografía**: ni vectorial en la web, ni líneas en la pantalla, y de ahí que un
         * plano grande fuera a tirones. Ver [pasarLaFoto].
         */
        val mascara: ByteArray? = null,
        /** El tipo MIME de [mascara]. */
        val tipoMascara: String? = null
    )

    /** Una página leída. Las medidas van en puntos del papel. */
    class Plano(
        val ancho: Double,
        val alto: Double,
        val capas: List<Capa>,
        val brochas: List<Brocha>,
        val textos: List<Texto>,
        /** Las fotos que sí se pudieron pasar tal cual. Ver [Imagen]. */
        val fotos: List<Imagen>,
        /**
         * **Cuántas cosas pinta la página que aquí no están.**
         *
         * Imágenes que no se pudieron pasar tal cual, degradados, patrones, un `Do` que no se
         * pudo resolver… Con que haya una, la página se manda como **fotografía de toda
         * ella**: es preferible una lámina borrosa a una nítida a la que le falta algo, porque
         * lo borroso se ve y lo que falta no. Ver [PlanoWeb.deArchivo].
         */
        val sinEntender: Int,
        /** Si se llegó al [TOPE_DE_PUNTOS] y la lectura se cortó. */
        val cortado: Boolean
    ) {
        val puntos: Int get() = brochas.sumOf { it.puntos }
        /**
         * Si tiene bastante geometría como para que valga la pena mandarlo en vez de la foto.
         *
         * Una página escaneada da cero: son una imagen y nada más, y entonces lo que hay que
         * mandar es la imagen. Ver [PlanoWeb.deArchivo], que además exige que no haya ninguna.
         */
        val valeLaPena: Boolean get() = !cortado && (puntos > 16 || textos.size > 2)
    }

    const val MOVER: Byte = 1
    const val LINEA: Byte = 2
    const val CURVA: Byte = 3
    const val CERRAR: Byte = 4

    // ---------------------------------------------------------------------
    // Leer
    // ---------------------------------------------------------------------

    /** La página [pagina] de este PDF ya leído, o null si no se entiende. */
    fun de(archivo: PdfArchivo, pagina: Int): Plano? {
        if (archivo.cifrado) return null
        val hoja = archivo.pagina(pagina) ?: return null
        val caja = mediaBox(archivo, hoja) ?: return null
        if (caja.giro % 90 != 0) return null
        val datos = contenido(archivo, hoja) ?: return null
        val recursos = heredado(archivo, hoja, "Resources") as? PdfValor.Dicc

        val interprete = Interprete(archivo, caja)
        interprete.capasDelDocumento()
        interprete.ejecutar(datos, recursos, 0)
        return interprete.cosecha()
    }

    /** Lo mismo desde los bytes del archivo. */
    fun de(bytes: ByteArray, pagina: Int): Plano? = leerPdf(bytes)?.let { de(it, pagina) }

    /** Y desde el archivo en disco. Ver [de]. */
    fun deArchivo(ruta: String, pagina: Int): Plano? = runCatching {
        de(java.io.File(ruta).readBytes(), pagina)
    }.getOrNull()

    /**
     * El contenido de la página, ya descomprimido.
     *
     * `/Contents` puede ser un flujo o una lista de flujos que se leen **como si fueran
     * uno**: el formato permite partir el contenido por donde quiera, incluso a mitad de una
     * orden, así que se pegan con un salto de línea entre medias y se interpreta el conjunto.
     */
    private fun contenido(archivo: PdfArchivo, hoja: PdfValor.Dicc): ByteArray? {
        val trozos = when (val c = archivo.resolver(hoja.entradas["Contents"])) {
            is PdfValor.Flujo -> listOf(c)
            is PdfValor.Lista -> c.valores.mapNotNull { archivo.resolver(it) as? PdfValor.Flujo }
            else -> return null
        }
        if (trozos.isEmpty()) return null
        if (trozos.size == 1) return archivo.descomprimir(trozos[0])
        val salida = java.io.ByteArrayOutputStream()
        for (t in trozos) {
            salida.write(archivo.descomprimir(t) ?: continue)
            salida.write('\n'.code)
        }
        return salida.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * La caja de la página: dónde empieza el papel, cuánto mide y **cómo se mira**.
     *
     * `/Rotate` no es un adorno: el plano del que salió todo esto viene con 270, o sea que el
     * archivo guarda una hoja vertical que se ve apaisada. Android lo aplica al rasterizar la
     * página —el papel del editor ya sale girado— así que si aquí no se girara igual, las
     * líneas caerían atravesadas sobre sus propias anotaciones.
     */
    private fun mediaBox(archivo: PdfArchivo, hoja: PdfValor.Dicc): Caja? {
        val lista = (heredado(archivo, hoja, "MediaBox") as? PdfValor.Lista)?.valores ?: return null
        val n = lista.mapNotNull { (archivo.resolver(it) as? PdfValor.Numero)?.valor }
        if (n.size < 4) return null
        val x0 = minOf(n[0], n[2])
        val y0 = minOf(n[1], n[3])
        val x1 = maxOf(n[0], n[2])
        val y1 = maxOf(n[1], n[3])
        if (x1 - x0 < 1 || y1 - y0 < 1) return null
        val giro = ((heredado(archivo, hoja, "Rotate") as? PdfValor.Numero)?.valor?.toInt() ?: 0)
            .let { ((it % 360) + 360) % 360 }
        return Caja(x0, y0, x1, y1, giro)
    }

    /** Una entrada de la página que puede estar escrita en un padre. */
    private fun heredado(archivo: PdfArchivo, hoja: PdfValor.Dicc, clave: String): PdfValor? {
        var d: PdfValor.Dicc? = hoja
        var saltos = 0
        while (d != null && saltos++ < 32) {
            d.entradas[clave]?.let { return archivo.resolver(it) }
            d = archivo.diccDe(d.entradas["Parent"])
        }
        return null
    }

    /**
     * El papel y su giro. [ancho] y [alto] son ya **los de la página como se mira**: con un
     * cuarto de vuelta, la hoja vertical del archivo es una lámina apaisada.
     */
    internal class Caja(
        val x0: Double, val y0: Double, val x1: Double, val y1: Double,
        val giro: Int = 0
    ) {
        private val ancho0 get() = x1 - x0
        private val alto0 get() = y1 - y0
        val deLado = giro == 90 || giro == 270
        val ancho get() = if (deLado) alto0 else ancho0
        val alto get() = if (deLado) ancho0 else alto0

        /** Un punto del PDF, en el sitio donde se ve: origen arriba a la izquierda, y hacia abajo. */
        fun enX(px: Double, py: Double): Double = when (giro) {
            90 -> py - y0
            180 -> x1 - px
            270 -> y1 - py
            else -> px - x0
        }

        fun enY(px: Double, py: Double): Double = when (giro) {
            90 -> px - x0
            180 -> py - y0
            270 -> x1 - px
            else -> y1 - py
        }

        /** Y una dirección, que gira igual pero sin trasladarse. */
        fun vectorX(vx: Double, vy: Double): Double = when (giro) {
            90 -> vy
            180 -> -vx
            270 -> -vy
            else -> vx
        }

        fun vectorY(vx: Double, vy: Double): Double = when (giro) {
            90 -> vx
            180 -> vy
            270 -> -vx
            else -> -vy
        }
    }
}

// -------------------------------------------------------------------------
// El intérprete
// -------------------------------------------------------------------------

/**
 * **Lo que hace un PDF cuando se mira: se ejecuta.**
 *
 * El contenido de una página es un programa de una máquina de pintar con pila: los números se
 * apilan y una palabra los gasta. `100 200 m` deja la pluma en (100,200); `300 400 l S` traza
 * una raya hasta ahí. Esto lo ejecuta, con la diferencia de que en vez de encender píxeles
 * apunta la geometría.
 *
 * Va a mano y sin objetos por token —los números caen en un `DoubleArray` y las palabras se
 * comparan por bytes— porque un plano grande son veinticinco megas de programa y un millón y
 * medio de órdenes: con un `PdfValor` por número esto sería un desfile de basura por el
 * recolector. Ver la lección de [VisorEspacio] sobre leer campos sueltos en vez de objetos.
 */
private class Interprete(val archivo: PdfArchivo, val caja: PlanoDePdf.Caja) {

    // ---- Lo que se va cosechando ----
    private val brochas = LinkedHashMap<Long, BrochaViva>()
    private val textos = ArrayList<PlanoDePdf.Texto>()
    private val fotos = ArrayList<PlanoDePdf.Imagen>()
    private var pesoDeFotos = 0
    private val capas = ArrayList<PlanoDePdf.Capa>()
    private val capaPorObjeto = HashMap<Int, Int>()
    private val rayados = ArrayList<DoubleArray>()
    private var sinEntender = 0
    private var puntos = 0
    private var cortado = false

    /** El camino en construcción, ya en pasos de 1/FINEZA y con la y hacia abajo. */
    private var ops = ByteArray(256)
    private var nOps = 0
    private var px = IntArray(256)
    private var py = IntArray(256)
    private var nPts = 0
    private var cerroSubcamino = false
    private var minX = Int.MAX_VALUE
    private var minY = Int.MAX_VALUE
    private var maxX = Int.MIN_VALUE
    private var maxY = Int.MIN_VALUE
    /** Dónde empezó el subcamino, para `h` y para `re`. */
    private var inicioX = 0
    private var inicioY = 0
    private var ultimoX = 0.0
    private var ultimoY = 0.0

    private var recortePendiente = false
    private var e = Estado()
    private val pila = ArrayList<Estado>(32)
    /** Las capas metidas unas en otras por `BDC`/`EMC`. */
    private val marcas = ArrayList<Int>(16)

    // ---- Las capas del documento ----

    /**
     * Lee `/OCProperties` del catálogo: cómo se llama cada capa y cuáles vienen apagadas.
     *
     * El orden importa: se respeta el de `/Order` —que es como las enseña Acrobat, y como las
     * ordenó quien dibujó el plano— y detrás van las que no estén en él.
     */
    fun capasDelDocumento() {
        val raiz = archivo.diccDe(archivo.trailer.entradas["Root"]) ?: return
        val props = archivo.diccDe(raiz.entradas["OCProperties"]) ?: return
        val todas = (archivo.resolver(props.entradas["OCGs"]) as? PdfValor.Lista)?.valores.orEmpty()
        val pred = archivo.diccDe(props.entradas["D"])
        val apagadas = (archivo.resolver(pred?.entradas?.get("OFF")) as? PdfValor.Lista)
            ?.valores?.filterIsInstance<PdfValor.Ref>()?.map { it.numero }?.toHashSet().orEmpty()

        val ordenadas = ArrayList<PdfValor.Ref>()
        pred?.entradas?.get("Order")?.let { recogerOrden(archivo.resolver(it), ordenadas, 0) }
        for (v in todas) if (v is PdfValor.Ref && ordenadas.none { it.numero == v.numero }) ordenadas += v

        for (ref in ordenadas) {
            if (capaPorObjeto.containsKey(ref.numero)) continue
            val d = archivo.diccDe(ref) ?: continue
            val nombre = (archivo.resolver(d.entradas["Name"]) as? PdfValor.Cadena)
                ?.let { textoDeCadena(it.bytes) }
                ?: d.nombre("Name") ?: "Capa ${capas.size + 1}"
            capaPorObjeto[ref.numero] = capas.size
            capas += PlanoDePdf.Capa(nombre, ref.numero !in apagadas)
        }
    }

    private fun recogerOrden(v: PdfValor?, out: ArrayList<PdfValor.Ref>, fondo: Int) {
        if (fondo > 8) return
        when (v) {
            is PdfValor.Ref -> if (out.none { it.numero == v.numero }) out += v
            is PdfValor.Lista -> for (x in v.valores) recogerOrden(x, out, fondo + 1)
            else -> Unit
        }
    }

    /** Una cadena del PDF a texto: puede venir en UTF-16 con su marca delante. */
    private fun textoDeCadena(b: ByteArray): String =
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF) {
            String(b, 2, b.size - 2, Charsets.UTF_16BE)
        } else {
            String(b, Charsets.ISO_8859_1)
        }

    // ---- Ejecutar ----

    fun ejecutar(datos: ByteArray, recursos: PdfValor.Dicc?, fondo: Int) {
        val f = Fichas(datos)
        val num = DoubleArray(16)
        var nNum = 0
        var nombre1: String? = null
        var nombre2: String? = null
        var cadena: ByteArray? = null
        var lista: ArrayList<Any>? = null

        while (true) {
            when (f.siguiente()) {
                Fichas.FIN -> break
                Fichas.NUMERO -> if (nNum < num.size) num[nNum++] = f.numero
                Fichas.NOMBRE -> { nombre2 = nombre1; nombre1 = f.texto }
                Fichas.CADENA -> cadena = f.cadena
                Fichas.LISTA -> lista = f.lista
                Fichas.OPERADOR -> {
                    if (puntos > PlanoDePdf.TOPE_DE_PUNTOS) { cortado = true; return }
                    when (f.texto) {
                        // ---- El estado ----
                        "q" -> pila.add(e.copia())
                        "Q" -> if (pila.isNotEmpty()) e = pila.removeAt(pila.size - 1)
                        "cm" -> if (nNum >= 6) e.multiplicar(num[0], num[1], num[2], num[3], num[4], num[5])
                        "w" -> if (nNum >= 1) e.grosor = num[0]
                        "gs" -> nombre1?.let { estadoExtra(recursos, it) }
                        "d" -> e.raya = rayado(lista)

                        // ---- Los colores ----
                        "G" -> if (nNum >= 1) e.colorTrazo = gris(num[0])
                        "g" -> if (nNum >= 1) e.colorRelleno = gris(num[0])
                        "RG" -> if (nNum >= 3) e.colorTrazo = rgb(num[0], num[1], num[2])
                        "rg" -> if (nNum >= 3) e.colorRelleno = rgb(num[0], num[1], num[2])
                        "K" -> if (nNum >= 4) e.colorTrazo = cmyk(num[0], num[1], num[2], num[3])
                        "k" -> if (nNum >= 4) e.colorRelleno = cmyk(num[0], num[1], num[2], num[3])
                        "CS" -> e.colorTrazo = 0x000000
                        "cs" -> e.colorRelleno = 0x000000
                        "SC", "SCN" -> {
                            // Con un nombre delante es un patrón —una trama o un degradado
                            // dentro de la tinta—, y eso tampoco se sabe pintar.
                            if (nombre1 != null) sinEntender++
                            e.colorTrazo = porComponentes(num, nNum, e.colorTrazo)
                        }
                        "sc", "scn" -> {
                            if (nombre1 != null) sinEntender++
                            e.colorRelleno = porComponentes(num, nNum, e.colorRelleno)
                        }

                        // ---- El camino ----
                        "m" -> if (nNum >= 2) mover(num[0], num[1])
                        "l" -> if (nNum >= 2) linea(num[0], num[1])
                        "c" -> if (nNum >= 6) curva(num[0], num[1], num[2], num[3], num[4], num[5])
                        "v" -> if (nNum >= 4) curva(ultimoX, ultimoY, num[0], num[1], num[2], num[3])
                        "y" -> if (nNum >= 4) curva(num[0], num[1], num[2], num[3], num[2], num[3])
                        "h" -> cerrar()
                        "re" -> if (nNum >= 4) rectangulo(num[0], num[1], num[2], num[3])

                        // ---- Pintarlo ----
                        "S" -> pintar(trazo = true, relleno = false, cerrando = false)
                        "s" -> pintar(trazo = true, relleno = false, cerrando = true)
                        "f", "F", "f*" -> pintar(false, true, false)
                        "B", "B*" -> pintar(true, true, false)
                        "b", "b*" -> pintar(true, true, true)
                        "n" -> pintar(false, false, false)
                        "W", "W*" -> recortePendiente = true

                        // ---- Las capas ----
                        "BDC" -> marcas.add(
                            if (nombre2 == "OC") capaDe(recursos, nombre1) else capaActual()
                        )
                        "BMC" -> marcas.add(capaActual())
                        "EMC" -> if (marcas.isNotEmpty()) marcas.removeAt(marcas.size - 1)

                        // ---- Lo de fuera ----
                        "Do" -> objetoExterno(recursos, nombre1, fondo)
                        "BI" -> { sinEntender++; f.saltarImagenIncrustada() }
                        // Un degradado pinta, y aquí no se sabe pintarlo: se cuenta, y la
                        // página se irá como fotografía. Ver [Plano.sinEntender].
                        "sh" -> sinEntender++

                        // ---- El texto ----
                        "BT" -> e.reiniciarTexto()
                        "ET" -> Unit
                        "Tf" -> if (nNum >= 1) { e.fuente = fuenteDe(recursos, nombre1); e.tam = num[0] }
                        "Td" -> if (nNum >= 2) e.saltoDeLinea(num[0], num[1])
                        "TD" -> if (nNum >= 2) { e.tl = -num[1]; e.saltoDeLinea(num[0], num[1]) }
                        "Tm" -> if (nNum >= 6) e.ponerTexto(num[0], num[1], num[2], num[3], num[4], num[5])
                        "T*" -> e.saltoDeLinea(0.0, -e.tl)
                        "TL" -> if (nNum >= 1) e.tl = num[0]
                        "Tc" -> if (nNum >= 1) e.tc = num[0]
                        "Tw" -> if (nNum >= 1) e.tw = num[0]
                        "Tz" -> if (nNum >= 1) e.tz = num[0]
                        "Ts" -> if (nNum >= 1) e.ts = num[0]
                        "Tr" -> if (nNum >= 1) e.tr = num[0].toInt()
                        "Tj" -> cadena?.let { escribir(it) }
                        "'" -> { e.saltoDeLinea(0.0, -e.tl); cadena?.let { escribir(it) } }
                        "\"" -> {
                            if (nNum >= 2) { e.tw = num[0]; e.tc = num[1] }
                            e.saltoDeLinea(0.0, -e.tl)
                            cadena?.let { escribir(it) }
                        }
                        "TJ" -> escribirLista(lista)
                    }
                    // Una palabra gasta todos sus argumentos, entienda esto la palabra o no:
                    // dejar números sueltos haría que el siguiente operador leyera los del
                    // anterior, que es la manera de convertir un archivo raro en un dibujo loco.
                    nNum = 0; nombre1 = null; nombre2 = null; cadena = null; lista = null
                }
            }
        }
    }

    // ---- El camino ----

    private fun sitio(x: Double, y: Double): Long {
        // La y del PDF sube y la del dibujo baja, y encima la página puede venir girada: las
        // dos cosas se arreglan aquí, una sola vez, y de aquí en adelante todo está ya en el
        // sentido en que se mira. Ver [PlanoDePdf.Caja].
        val px = e.a * x + e.c * y + e.tx0
        val py = e.b * x + e.d * y + e.ty0
        val ux = caja.enX(px, py) * PlanoDePdf.FINEZA
        val uy = caja.enY(px, py) * PlanoDePdf.FINEZA
        val qx = if (ux.isFinite()) ux.roundToInt() else 0
        val qy = if (uy.isFinite()) uy.roundToInt() else 0
        return (qx.toLong() shl 32) or (qy.toLong() and 0xFFFFFFFFL)
    }

    private fun apuntar(op: Byte) {
        if (nOps == ops.size) ops = ops.copyOf(ops.size * 2)
        ops[nOps++] = op
    }

    private fun apuntarPunto(p: Long) {
        if (nPts == px.size) { px = px.copyOf(px.size * 2); py = py.copyOf(py.size * 2) }
        val x = (p shr 32).toInt()
        val y = p.toInt()
        px[nPts] = x
        py[nPts] = y
        nPts++
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    private fun mover(x: Double, y: Double) {
        val p = sitio(x, y)
        apuntar(PlanoDePdf.MOVER)
        apuntarPunto(p)
        inicioX = (p shr 32).toInt(); inicioY = p.toInt()
        ultimoX = x; ultimoY = y
    }

    private fun linea(x: Double, y: Double) {
        if (nOps == 0) return mover(x, y)
        apuntar(PlanoDePdf.LINEA)
        apuntarPunto(sitio(x, y))
        ultimoX = x; ultimoY = y
    }

    private fun curva(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        if (nOps == 0) mover(x1, y1)
        apuntar(PlanoDePdf.CURVA)
        apuntarPunto(sitio(x1, y1))
        apuntarPunto(sitio(x2, y2))
        apuntarPunto(sitio(x3, y3))
        ultimoX = x3; ultimoY = y3
    }

    private fun cerrar() {
        if (nOps == 0) return
        apuntar(PlanoDePdf.CERRAR)
        cerroSubcamino = true
    }

    private fun rectangulo(x: Double, y: Double, an: Double, al: Double) {
        mover(x, y)
        linea(x + an, y)
        linea(x + an, y + al)
        linea(x, y + al)
        cerrar()
    }

    /**
     * Pinta el camino y lo tira.
     *
     * [recortando] llega puesto cuando antes vino un `W`: el mismo camino que se pinta —o que
     * no, con `n`— sirve además de recorte. Aquí el recorte se guarda solo como **caja**: con
     * eso basta para tirar lo que queda fuera de un cuadro de dibujo, que es para lo que los
     * planos lo usan, y no cuesta nada. Un recorte con forma se queda en su caja, o sea que
     * deja pasar de más; pasar de más se ve, y pasar de menos borra dibujo.
     */
    private fun pintar(trazo: Boolean, relleno: Boolean, cerrando: Boolean) {
        if (nOps > 0 && (trazo || relleno)) {
            if (cerrando && !cerroSubcamino) { apuntar(PlanoDePdf.CERRAR) }
            if (dentroDelRecorte() && dentroDelPapel()) {
                if (relleno) copiarA(brochaViva(relleno = true))
                if (trazo) copiarA(brochaViva(relleno = false))
                puntos += nPts
            }
        }
        if (recortePendiente && nPts > 0) {
            e.recorte = e.recorte.corta(minX, minY, maxX, maxY)
        }
        recortePendiente = false
        nOps = 0; nPts = 0; cerroSubcamino = false
        minX = Int.MAX_VALUE; minY = Int.MAX_VALUE; maxX = Int.MIN_VALUE; maxY = Int.MIN_VALUE
    }

    private fun dentroDelRecorte(): Boolean {
        val r = e.recorte
        return !(maxX < r.x0 || minX > r.x1 || maxY < r.y0 || minY > r.y1)
    }

    /** Lo que cae del todo fuera del papel no se manda: no se puede mirar y sí pesa. */
    private fun dentroDelPapel(): Boolean {
        val margen = PlanoDePdf.FINEZA * 8
        val anchoQ = (caja.ancho * PlanoDePdf.FINEZA).toInt()
        val altoQ = (caja.alto * PlanoDePdf.FINEZA).toInt()
        return !(maxX < -margen || minX > anchoQ + margen || maxY < -margen || minY > altoQ + margen)
    }

    private fun capaActual(): Int = if (marcas.isEmpty()) -1 else marcas[marcas.size - 1]

    private fun brochaViva(relleno: Boolean): BrochaViva {
        var color = if (relleno) e.colorRelleno else e.colorTrazo
        val alfa = if (relleno) e.alfaRelleno else e.alfaTrazo
        val grosor = if (relleno) 0.0 else grosorEnPapel()
        val capa = capaActual()
        val raya = if (relleno || e.raya.isEmpty()) -1 else indiceDeRayado(e.raya)
        var gq = (grosor * 16).roundToInt().coerceIn(0, 4095)
        val aq = (alfa * 15).roundToInt().coerceIn(0, 15)
        // Pasado el tope se redondea, y con eso las mil variantes de un degradado caen en unas
        // pocas brochas. Ver [PlanoDePdf.TOPE_DE_BROCHAS].
        if (brochas.size >= PlanoDePdf.TOPE_DE_BROCHAS) {
            color = (color and 0xF0F0F0) or 0x080808
            gq = (gq / 8) * 8
        }
        val clave = (capa + 1).toLong() shl 54 or
            ((if (relleno) 1L else 0L) shl 53) or
            ((raya + 1).toLong() and 0xF shl 49) or
            (aq.toLong() shl 45) or
            (gq.toLong() shl 33) or
            (color.toLong() and 0xFFFFFF)
        brochas[clave]?.let { return it }
        val nueva = BrochaViva(
            capa, color, aq / 15.0, gq / 16.0, relleno,
            if (raya < 0) DOBLE_VACIO else rayados[raya]
        )
        brochas[clave] = nueva
        return nueva
    }

    /**
     * El grosor tal como se verá en el papel.
     *
     * El `w` del PDF va en las unidades de la matriz del momento, y un plano suele empezar con
     * un `cm` que lo achica todo doce veces: sin pasar el grosor por la misma matriz, un
     * trazo de medio punto saldría gordísimo.
     */
    private fun grosorEnPapel(): Double {
        val det = abs(e.a * e.d - e.b * e.c)
        val k = if (det > 0) sqrt(det) else hypot(e.a, e.b)
        return e.grosor * k
    }

    private fun indiceDeRayado(r: DoubleArray): Int {
        for (i in rayados.indices) if (rayados[i].contentEquals(r)) return i
        if (rayados.size >= 14) return -1
        rayados += r
        return rayados.size - 1
    }

    private fun copiarA(b: BrochaViva) {
        b.meter(ops, nOps, px, py, nPts)
    }

    // ---- Recursos ----

    private fun recurso(recursos: PdfValor.Dicc?, grupo: String, nombre: String?): PdfValor? {
        if (nombre == null) return null
        val g = archivo.diccDe(recursos?.entradas?.get(grupo)) ?: return null
        return archivo.resolver(g.entradas[nombre])
    }

    private fun capaDe(recursos: PdfValor.Dicc?, nombre: String?): Int {
        if (nombre == null) return capaActual()
        val g = archivo.diccDe(recursos?.entradas?.get("Properties")) ?: return capaActual()
        val ref = g.entradas[nombre] as? PdfValor.Ref ?: return capaActual()
        capaPorObjeto[ref.numero]?.let { return it }
        // Una capa que no estaba en el catálogo: se apunta ahora, que es mejor que perderla.
        val d = archivo.diccDe(ref)
        // `/OCMD` es un envoltorio: la capa de verdad cuelga de su `/OCGs`.
        val real = (d?.entradas?.get("OCGs") as? PdfValor.Ref) ?: ref
        capaPorObjeto[real.numero]?.let { capaPorObjeto[ref.numero] = it; return it }
        val nombreCapa = archivo.diccDe(real)?.let { dd ->
            (archivo.resolver(dd.entradas["Name"]) as? PdfValor.Cadena)?.let { textoDeCadena(it.bytes) }
        } ?: "Capa ${capas.size + 1}"
        val i = capas.size
        capas += PlanoDePdf.Capa(nombreCapa, true)
        capaPorObjeto[real.numero] = i
        capaPorObjeto[ref.numero] = i
        return i
    }

    private fun estadoExtra(recursos: PdfValor.Dicc?, nombre: String) {
        val d = recurso(recursos, "ExtGState", nombre) as? PdfValor.Dicc ?: return
        d.numero("LW")?.let { e.grosor = it }
        d.numero("CA")?.let { e.alfaTrazo = it.coerceIn(0.0, 1.0) }
        d.numero("ca")?.let { e.alfaRelleno = it.coerceIn(0.0, 1.0) }
        (archivo.resolver(d.entradas["D"]) as? PdfValor.Lista)?.let { l ->
            e.raya = rayado((l.valores.firstOrNull() as? PdfValor.Lista)?.valores?.mapNotNull {
                (it as? PdfValor.Numero)?.valor
            }?.let { ArrayList<Any>(it) })
        }
    }

    /**
     * Un `Do`: o un formulario —un trozo de dibujo aparte, que se ejecuta aquí dentro con su
     * matriz y sus recursos— o una imagen, que se cuenta y se deja.
     */
    private fun objetoExterno(recursos: PdfValor.Dicc?, nombre: String?, fondo: Int) {
        if (fondo >= 12) { sinEntender++; return }
        // **Un `Do` que no se sabe seguir se cuenta.** Puede ser una imagen —y entonces la
        // página lleva algo que aquí falta—, así que callarse sería justo el fallo que no se
        // puede tener: una lámina exportada a la que le faltan las fotos sin decir nada.
        val flujo = recurso(recursos, "XObject", nombre) as? PdfValor.Flujo
        if (flujo == null) { sinEntender++; return }
        when (flujo.dicc.nombre("Subtype")) {
            "Image" -> if (!pasarLaFoto(flujo)) sinEntender++
            "Form" -> {
                val datos = archivo.descomprimir(flujo) ?: run { sinEntender++; return }
                val guardado = e.copia()
                val marcasAntes = marcas.size
                val pilaAntes = pila.size
                (archivo.resolver(flujo.dicc.entradas["OC"]) as? PdfValor.Dicc)?.let {
                    // Un formulario puede llevar su capa puesta desde fuera.
                    val ref = flujo.dicc.entradas["OC"] as? PdfValor.Ref
                    if (ref != null) capaPorObjeto[ref.numero]?.let { c -> marcas.add(c) }
                }
                (archivo.resolver(flujo.dicc.entradas["Matrix"]) as? PdfValor.Lista)?.valores
                    ?.mapNotNull { (it as? PdfValor.Numero)?.valor }
                    ?.takeIf { it.size >= 6 }
                    ?.let { e.multiplicar(it[0], it[1], it[2], it[3], it[4], it[5]) }
                val suyos = archivo.diccDe(flujo.dicc.entradas["Resources"]) ?: recursos
                ejecutar(datos, suyos, fondo + 1)
                while (pila.size > pilaAntes) pila.removeAt(pila.size - 1)
                while (marcas.size > marcasAntes) marcas.removeAt(marcas.size - 1)
                e = guardado
            }
            // Cualquier otra cosa que se pinte y no se sepa qué es.
            else -> sinEntender++
        }
    }

    /**
     * Pasa la imagen tal cual si se puede, y dice si lo consiguió.
     *
     * Solo las que ya son un archivo de imagen por dentro —JPEG— y sin recortes de
     * transparencia: cualquier otra cosa habría que **dibujarla**, y eso es rasterizar, que es
     * justo de lo que se huye aquí. Lo que no pasa se cuenta, y con una sola que se quede
     * fuera la página entera se manda como fotografía: es preferible una página borrosa a una
     * nítida a la que le falta el sello del estudio.
     */
    private fun pasarLaFoto(flujo: PdfValor.Flujo): Boolean {
        val d = flujo.dicc
        // `/Mask` es otra cosa: o un recorte por color o un sello de un bit. Sigue fuera.
        if (d.entradas.containsKey("Mask")) return false
        if ((d.entradas["ImageMask"] as? PdfValor.Booleano)?.valor == true) return false
        if (pesoDeFotos > PESO_DE_LAS_FOTOS) return false
        val (filtro, datos) = archivo.sinElUltimoFiltro(flujo) ?: return false
        val tipo = when (filtro) {
            "DCTDecode", "DCT" -> "image/jpeg"
            "JPXDecode" -> return false
            else -> return false
        }
        // **La transparencia, si la trae.** Ver [PlanoDePdf.Imagen.mascara]: se pasa entera y
        // sin tocar, como la de color, y quien pinte las junta. Si la máscara existe pero no
        // se puede pasar tal cual, la imagen entera se rechaza: pintarla sin su transparencia
        // sería taparle al plano lo que hay debajo con un rectángulo opaco, que se ve **peor**
        // que no pintarla.
        var mascara: ByteArray? = null
        var tipoMascara: String? = null
        if (d.entradas.containsKey("SMask")) {
            val m = archivo.resolver(d.entradas["SMask"]) as? PdfValor.Flujo ?: return false
            // Una máscara con máscara, no. Ni una que a su vez sea un sello.
            if (m.dicc.entradas.containsKey("SMask") || m.dicc.entradas.containsKey("Mask")) return false
            if (!mismoTamano(d, m.dicc)) return false
            val (fm, dm) = archivo.sinElUltimoFiltro(m) ?: return false
            tipoMascara = when (fm) {
                "DCTDecode", "DCT" -> "image/jpeg"
                else -> return false
            }
            mascara = dm
            pesoDeFotos += dm.size
        }
        pesoDeFotos += datos.size
        // El cuadrado de la imagen va del (0,0) al (1,1) del espacio de dibujo, y su primera
        // fila cae **arriba**, o sea en la y = 1: de ahí que el vector de bajar sea el de la y
        // del revés.
        val ux = e.a
        val uy = e.b
        val vx = -e.c
        val vy = -e.d
        val ox = e.c + e.tx0
        val oy = e.d + e.ty0
        fotos += PlanoDePdf.Imagen(
            capa = capaActual(), alfa = e.alfaRelleno, tipo = tipo, datos = datos,
            a = caja.vectorX(ux, uy), b = caja.vectorY(ux, uy),
            c = caja.vectorX(vx, vy), d = caja.vectorY(vx, vy),
            x = caja.enX(ox, oy), y = caja.enY(ox, oy),
            mascara = mascara, tipoMascara = tipoMascara
        )
        return true
    }

    /**
     * Si la máscara mide lo mismo que su imagen.
     *
     * El PDF **no** lo exige —una máscara puede venir a otra resolución y el lector la
     * estira—, pero juntarlas píxel a píxel sí lo exige, y estirar aquí sería rasterizar. Las
     * de Revit vienen exactamente del mismo tamaño (992×877 en el plano del usuario), que es
     * lo corriente; la que no, se rechaza y su página se manda como foto, como antes.
     */
    private fun mismoTamano(imagen: PdfValor.Dicc, mascara: PdfValor.Dicc): Boolean {
        fun lado(d: PdfValor.Dicc, cual: String) =
            (archivo.resolver(d.entradas[cual]) as? PdfValor.Numero)?.valor?.toInt()
        val ancho = lado(imagen, "Width") ?: return false
        val alto = lado(imagen, "Height") ?: return false
        return ancho == lado(mascara, "Width") && alto == lado(mascara, "Height")
    }

    // ---- El texto ----

    private val fuentes = HashMap<String, Fuente>()

    private fun fuenteDe(recursos: PdfValor.Dicc?, nombre: String?): Fuente? {
        if (nombre == null) return null
        fuentes[nombre]?.let { return it }
        val d = recurso(recursos, "Font", nombre) as? PdfValor.Dicc ?: return null
        val f = Fuente.leer(archivo, d)
        fuentes[nombre] = f
        return f
    }

    private fun escribirLista(lista: ArrayList<Any>?) {
        if (lista == null) return
        for (x in lista) {
            when (x) {
                is ByteArray -> escribir(x)
                // Un número dentro de `TJ` es un empujón hacia atrás, en milésimas de em:
                // es como se aprieta o se separa una palabra sin escribirla dos veces.
                is Double -> e.tx -= x / 1000.0 * e.tam * (e.tz / 100.0)
            }
        }
    }

    /** Escribe una cadena: apunta el texto donde toca y adelanta el cursor. */
    private fun escribir(bytes: ByteArray) {
        val f = e.fuente
        val tam = e.tam
        if (f == null || tam == 0.0) return
        val th = e.tz / 100.0
        val letras = f.letras(bytes)
        var avance = 0.0
        val sb = StringBuilder(letras.size)
        for (l in letras) {
            avance += (l.ancho / 1000.0) * tam + e.tc + (if (l.esEspacio) e.tw else 0.0)
            if (l.texto != null) sb.append(l.texto)
        }
        val texto = sb.toString()
        // Modo 3 es texto invisible: lo pone el escaneo con OCR debajo de su foto. Y un texto
        // en blanco sobre papel blanco tampoco se manda: pesa y no se ve.
        val visible = e.tr != 3 && e.tr != 7 && texto.isNotBlank()
        if (visible) {
            // La matriz que lleva «una letra de un punto de alto en el origen» hasta el papel.
            // Se compone la del texto con la de la página, se le da la vuelta a la y —la del
            // navegador baja— y se guarda ya en unidades del papel.
            val m00 = e.txa * th
            val m01 = e.txb * th
            val m10 = e.txc
            val m11 = e.txd
            val ox = e.txe
            val oy = e.txf + e.ts
            val ax = m00 * e.a + m01 * e.c
            val ay = m00 * e.b + m01 * e.d
            val bx = m10 * e.a + m11 * e.c
            val by = m10 * e.b + m11 * e.d
            val ex = ox * e.a + oy * e.c + e.tx0 + (e.tx * e.txa + e.ty * e.txc) * e.a +
                (e.tx * e.txb + e.ty * e.txd) * e.c
            val ey = ox * e.b + oy * e.d + e.ty0 + (e.tx * e.txa + e.ty * e.txc) * e.b +
                (e.tx * e.txb + e.ty * e.txd) * e.d
            textos += PlanoDePdf.Texto(
                capa = capaActual(),
                color = if (e.tr == 1 || e.tr == 5) e.colorTrazo else e.colorRelleno,
                alfa = e.alfaRelleno,
                texto = texto,
                // Hacia dónde se escribe y hacia dónde caen las letras, girados con la página.
                a = caja.vectorX(ax, ay) * tam, b = caja.vectorY(ax, ay) * tam,
                c = -caja.vectorX(bx, by) * tam, d = -caja.vectorY(bx, by) * tam,
                x = caja.enX(ex, ey), y = caja.enY(ex, ey),
                // En emes: es lo que mide el rótulo en el espacio donde la matriz de arriba
                // pone una letra de una eme de alto. Ver [PlanoDePdf.Texto.ancho].
                ancho = avance / tam,
                familia = f.familia, negrita = f.negrita, cursiva = f.cursiva
            )
        }
        // El cursor sí avanza en el espacio del texto, donde el apretado horizontal cuenta.
        e.tx += avance * th
    }

    // ---- Cosechar ----

    fun cosecha(): PlanoDePdf.Plano {
        val salida = ArrayList<PlanoDePdf.Brocha>(brochas.size)
        for (b in brochas.values) salida += b.cerrar()
        return PlanoDePdf.Plano(
            ancho = caja.ancho,
            alto = caja.alto,
            capas = capas,
            brochas = salida,
            textos = textos,
            fotos = fotos,
            sinEntender = sinEntender,
            cortado = cortado
        )
    }

    // ---- Colores ----

    private fun gris(v: Double): Int {
        val g = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (g shl 16) or (g shl 8) or g
    }

    private fun rgb(r: Double, g: Double, b: Double): Int =
        ((r.coerceIn(0.0, 1.0) * 255).roundToInt() shl 16) or
            ((g.coerceIn(0.0, 1.0) * 255).roundToInt() shl 8) or
            (b.coerceIn(0.0, 1.0) * 255).roundToInt()

    private fun cmyk(c: Double, m: Double, y: Double, k: Double): Int =
        rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))

    /**
     * `sc`/`scn` sin saber en qué espacio se está: se deduce por cuántos números trae, que
     * acierta con lo corriente —gris, rgb, cmyk— y con una tinta plana la deja en gris.
     */
    private fun porComponentes(num: DoubleArray, n: Int, antes: Int): Int = when (n) {
        1 -> gris(1 - num[0].coerceIn(0.0, 1.0))
        3 -> rgb(num[0], num[1], num[2])
        4 -> cmyk(num[0], num[1], num[2], num[3])
        else -> antes
    }

    private fun rayado(lista: ArrayList<Any>?): DoubleArray {
        if (lista == null) return DOBLE_VACIO
        val v = lista.filterIsInstance<Double>().filter { it.isFinite() && it >= 0 }
        if (v.isEmpty() || v.all { it == 0.0 }) return DOBLE_VACIO
        val det = abs(e.a * e.d - e.b * e.c)
        val k = if (det > 0) sqrt(det) else 1.0
        return DoubleArray(minOf(v.size, 6)) { v[it] * k }
    }

    private companion object {
        val DOBLE_VACIO = DoubleArray(0)

        /** Tope de fotos, en bytes: pasado eso la página web pesaría más que la fotografía. */
        const val PESO_DE_LAS_FOTOS = 8 * 1024 * 1024
    }
}

/** El estado de la máquina de pintar. Ver [Interprete]. */
private class Estado {
    // La matriz: [a b c d tx0 ty0].
    var a = 1.0; var b = 0.0; var c = 0.0; var d = 1.0; var tx0 = 0.0; var ty0 = 0.0
    var grosor = 1.0
    var colorTrazo = 0x000000
    var colorRelleno = 0x000000
    var alfaTrazo = 1.0
    var alfaRelleno = 1.0
    var raya: DoubleArray = DoubleArray(0)
    var recorte = Recorte.TODO

    // El texto: la matriz del renglón y por dónde va el cursor.
    var txa = 1.0; var txb = 0.0; var txc = 0.0; var txd = 1.0; var txe = 0.0; var txf = 0.0
    var tx = 0.0; var ty = 0.0
    var fuente: Fuente? = null
    var tam = 0.0
    var tc = 0.0; var tw = 0.0; var tz = 100.0; var ts = 0.0; var tl = 0.0; var tr = 0

    fun multiplicar(na: Double, nb: Double, nc: Double, nd: Double, ne: Double, nf: Double) {
        val ra = na * a + nb * c
        val rb = na * b + nb * d
        val rc = nc * a + nd * c
        val rd = nc * b + nd * d
        val re = ne * a + nf * c + tx0
        val rf = ne * b + nf * d + ty0
        a = ra; b = rb; c = rc; d = rd; tx0 = re; ty0 = rf
    }

    fun ponerTexto(na: Double, nb: Double, nc: Double, nd: Double, ne: Double, nf: Double) {
        txa = na; txb = nb; txc = nc; txd = nd; txe = ne; txf = nf
        tx = 0.0; ty = 0.0
    }

    /** `Td`: el cursor salta desde el principio del renglón anterior. */
    fun saltoDeLinea(dx: Double, dy: Double) {
        // El salto va en el espacio del renglón: se suma a la matriz, no a la página.
        txe += dx * txa + dy * txc
        txf += dx * txb + dy * txd
        tx = 0.0; ty = 0.0
    }

    fun reiniciarTexto() {
        txa = 1.0; txb = 0.0; txc = 0.0; txd = 1.0; txe = 0.0; txf = 0.0
        tx = 0.0; ty = 0.0
    }

    fun copia(): Estado {
        val o = Estado()
        o.a = a; o.b = b; o.c = c; o.d = d; o.tx0 = tx0; o.ty0 = ty0
        o.grosor = grosor
        o.colorTrazo = colorTrazo; o.colorRelleno = colorRelleno
        o.alfaTrazo = alfaTrazo; o.alfaRelleno = alfaRelleno
        o.raya = raya
        o.recorte = recorte
        o.txa = txa; o.txb = txb; o.txc = txc; o.txd = txd; o.txe = txe; o.txf = txf
        o.tx = tx; o.ty = ty
        o.fuente = fuente; o.tam = tam
        o.tc = tc; o.tw = tw; o.tz = tz; o.ts = ts; o.tl = tl; o.tr = tr
        return o
    }
}

/** El recorte, guardado solo como caja. Ver [Interprete.pintar]. */
private class Recorte(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    fun corta(ax: Int, ay: Int, bx: Int, by: Int) =
        Recorte(maxOf(x0, ax), maxOf(y0, ay), minOf(x1, bx), minOf(y1, by))

    companion object {
        val TODO = Recorte(Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, Int.MAX_VALUE / 2)
    }
}

/** Una brocha mientras se llena. Ver [PlanoDePdf.Brocha]. */
private class BrochaViva(
    val capa: Int,
    val color: Int,
    val alfa: Double,
    val grosor: Double,
    val relleno: Boolean,
    val raya: DoubleArray
) {
    var ops = ByteArray(64)
    var nOps = 0
    var xs = IntArray(64)
    var ys = IntArray(64)
    var nPts = 0

    fun meter(o: ByteArray, no: Int, x: IntArray, y: IntArray, np: Int) {
        if (nOps + no > ops.size) ops = ops.copyOf(maxOf(ops.size * 2, nOps + no))
        System.arraycopy(o, 0, ops, nOps, no)
        nOps += no
        if (nPts + np > xs.size) {
            val n = maxOf(xs.size * 2, nPts + np)
            xs = xs.copyOf(n); ys = ys.copyOf(n)
        }
        System.arraycopy(x, 0, xs, nPts, np)
        System.arraycopy(y, 0, ys, nPts, np)
        nPts += np
    }

    fun cerrar() = PlanoDePdf.Brocha(
        capa, color, alfa, grosor, relleno, raya,
        ops.copyOf(nOps), xs.copyOf(nPts), ys.copyOf(nPts)
    )
}

// -------------------------------------------------------------------------
// Las fichas del contenido
// -------------------------------------------------------------------------

/**
 * El troceador del programa de la página.
 *
 * Devuelve una ficha por llamada y **no crea nada** para los números, que son casi todo: el
 * valor se deja en [numero] y quien llama lo apila donde quiera. Los nombres y las cadenas sí
 * salen como objetos, pero de esos hay pocos.
 */
private class Fichas(val b: ByteArray) {
    var pos = 0
    var numero = 0.0
    var texto = ""
    var cadena: ByteArray = ByteArray(0)
    var lista: ArrayList<Any> = ArrayList()

    /**
     * La siguiente ficha. Va en bucle y no llamándose a sí misma: un archivo estropeado con
     * cien mil corchetes sueltos desbordaría la pila, y desbordar la pila es que se cierre la
     * aplicación de alguien.
     */
    fun siguiente(): Int {
        while (true) {
            saltar()
            if (pos >= b.size) return FIN
            val c = b[pos].toInt() and 0xFF
            when {
                c == '/'.code -> { texto = nombre(); return NOMBRE }
                c == '('.code -> { cadena = cadenaNormal(); return CADENA }
                c == '<'.code && pos + 1 < b.size && (b[pos + 1].toInt() and 0xFF) == '<'.code ->
                    saltarDiccionario()
                c == '<'.code -> { cadena = cadenaHex(); return CADENA }
                c == '['.code -> { lista = leerLista(); return LISTA }
                c == ']'.code || c == '}'.code || c == '{'.code || c == ')'.code || c == '>'.code ->
                    pos++
                c == '+'.code || c == '-'.code || c == '.'.code || (c in '0'.code..'9'.code) ->
                    { numero = leerNumero(); return NUMERO }
                else -> { texto = palabra(); return OPERADOR }
            }
        }
    }

    private fun saltar() {
        while (pos < b.size) {
            val c = b[pos].toInt() and 0xFF
            when {
                c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 -> pos++
                c == '%'.code -> { while (pos < b.size && b[pos].toInt() != 10 && b[pos].toInt() != 13) pos++ }
                else -> return
            }
        }
    }

    /** Un número, a mano: `String.toDouble` por cada uno serían millones de cadenas. */
    private fun leerNumero(): Double {
        var signo = 1.0
        if (b[pos] == '+'.code.toByte()) pos++
        else if (b[pos] == '-'.code.toByte()) { signo = -1.0; pos++ }
        var entero = 0.0
        while (pos < b.size) {
            val c = b[pos].toInt()
            if (c < '0'.code || c > '9'.code) break
            entero = entero * 10 + (c - '0'.code)
            pos++
        }
        if (pos < b.size && b[pos] == '.'.code.toByte()) {
            pos++
            var escala = 0.1
            while (pos < b.size) {
                val c = b[pos].toInt()
                if (c < '0'.code || c > '9'.code) break
                entero += (c - '0'.code) * escala
                escala *= 0.1
                pos++
            }
        }
        // Hay archivos con `--5` o con exponente; se les deja pasar el resto del token.
        while (pos < b.size && !esCorte(b[pos].toInt() and 0xFF)) pos++
        return signo * entero
    }

    private fun palabra(): String {
        val inicio = pos
        while (pos < b.size && !esCorte(b[pos].toInt() and 0xFF)) pos++
        if (pos == inicio) pos++
        return String(b, inicio, pos - inicio, Charsets.US_ASCII)
    }

    private fun nombre(): String {
        pos++
        val sb = StringBuilder(8)
        while (pos < b.size) {
            val c = b[pos].toInt() and 0xFF
            if (esCorte(c)) break
            pos++
            if (c == '#'.code && pos + 1 < b.size) {
                val h = String(b, pos, 2, Charsets.US_ASCII).toIntOrNull(16)
                if (h != null) { sb.append(h.toChar()); pos += 2; continue }
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun cadenaNormal(): ByteArray {
        pos++
        val out = java.io.ByteArrayOutputStream(32)
        var nivel = 1
        while (pos < b.size) {
            val c = b[pos++].toInt() and 0xFF
            when {
                c == '\\'.code -> {
                    if (pos >= b.size) break
                    val d = b[pos++].toInt() and 0xFF
                    when (d.toChar()) {
                        'n' -> out.write(10); 'r' -> out.write(13); 't' -> out.write(9)
                        'b' -> out.write(8); 'f' -> out.write(12)
                        '\n' -> Unit
                        '\r' -> { if (pos < b.size && b[pos].toInt() == 10) pos++ }
                        in '0'..'7' -> {
                            var v = d - '0'.code
                            var n = 1
                            while (n < 3 && pos < b.size && b[pos] >= '0'.code.toByte() && b[pos] <= '7'.code.toByte()) {
                                v = v * 8 + (b[pos++].toInt() - '0'.code); n++
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(d)
                    }
                }
                c == '('.code -> { nivel++; out.write(c) }
                c == ')'.code -> { nivel--; if (nivel == 0) break; out.write(c) }
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    private fun cadenaHex(): ByteArray {
        pos++
        val out = java.io.ByteArrayOutputStream(32)
        var alto = -1
        while (pos < b.size) {
            val c = (b[pos++].toInt() and 0xFF).toChar()
            if (c == '>') break
            val d = Character.digit(c, 16)
            if (d < 0) continue
            if (alto < 0) alto = d else { out.write(alto * 16 + d); alto = -1 }
        }
        if (alto >= 0) out.write(alto * 16)
        return out.toByteArray()
    }

    private fun leerLista(): ArrayList<Any> {
        pos++
        val out = ArrayList<Any>(8)
        while (pos < b.size) {
            saltar()
            if (pos >= b.size) break
            val c = b[pos].toInt() and 0xFF
            if (c == ']'.code) { pos++; break }
            when {
                c == '('.code -> out.add(cadenaNormal())
                c == '<'.code -> out.add(cadenaHex())
                c == '/'.code -> out.add(nombre())
                c == '+'.code || c == '-'.code || c == '.'.code || (c in '0'.code..'9'.code) ->
                    out.add(leerNumero())
                else -> { palabra() }
            }
            if (out.size > 4096) break
        }
        return out
    }

    /** Un diccionario suelto en el contenido (el de `BDC`): se lee y se tira. */
    private fun saltarDiccionario() {
        var nivel = 0
        while (pos < b.size) {
            if (pos + 1 < b.size && (b[pos].toInt() and 0xFF) == '<'.code &&
                (b[pos + 1].toInt() and 0xFF) == '<'.code
            ) { nivel++; pos += 2; continue }
            if (pos + 1 < b.size && (b[pos].toInt() and 0xFF) == '>'.code &&
                (b[pos + 1].toInt() and 0xFF) == '>'.code
            ) { nivel--; pos += 2; if (nivel <= 0) return; continue }
            if ((b[pos].toInt() and 0xFF) == '('.code) { cadenaNormal(); continue }
            pos++
        }
    }

    /**
     * Una imagen incrustada (`BI … ID …datos… EI`).
     *
     * Los datos van en crudo entre `ID` y `EI`, así que hay que **saltarlos a ojo**: se busca
     * un `EI` que esté suelto —con blanco delante y detrás—, porque esos dos bytes pueden
     * aparecer dentro de la imagen por casualidad.
     */
    fun saltarImagenIncrustada() {
        var i = pos
        while (i + 1 < b.size) {
            if ((b[i].toInt() and 0xFF) == 'I'.code && (b[i + 1].toInt() and 0xFF) == 'D'.code) {
                i += 2; break
            }
            i++
        }
        if (i + 1 < b.size) i++
        while (i + 2 < b.size) {
            val antes = b[i].toInt() and 0xFF
            if ((antes == 32 || antes == 10 || antes == 13 || antes == 9) &&
                (b[i + 1].toInt() and 0xFF) == 'E'.code && (b[i + 2].toInt() and 0xFF) == 'I'.code &&
                (i + 3 >= b.size || esCorte(b[i + 3].toInt() and 0xFF))
            ) { pos = i + 3; return }
            i++
        }
        pos = b.size
    }

    private fun esCorte(c: Int): Boolean =
        c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
            c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code ||
            c == '/'.code || c == '%'.code

    companion object {
        const val FIN = 0
        const val NUMERO = 1
        const val NOMBRE = 2
        const val CADENA = 3
        const val OPERADOR = 4
        const val LISTA = 5
    }
}

// -------------------------------------------------------------------------
// Las fuentes
// -------------------------------------------------------------------------

/**
 * Lo justo de una fuente para **colocar un rótulo**: qué dice cada código y cuánto ocupa.
 *
 * No se extrae la tipografía. El navegador pondrá la suya —la que más se parezca por el
 * nombre— y el visor la estirará hasta que el rótulo ocupe el ancho que decía el PDF, que es
 * lo que mantiene las cosas en su casilla aunque la letra no sea la misma. Ver
 * [PlanoDePdf.Texto].
 */
private class Fuente(
    val dosBytes: Boolean,
    val anchos: HashMap<Int, Double>,
    val anchoPorDefecto: Double,
    val aTexto: HashMap<Int, String>,
    val familia: String,
    val negrita: Boolean,
    val cursiva: Boolean
) {
    class Letra(val texto: String?, val ancho: Double, val esEspacio: Boolean)

    fun letras(bytes: ByteArray): List<Letra> {
        val out = ArrayList<Letra>(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val codigo: Int
            if (dosBytes) {
                if (i + 1 >= bytes.size) break
                codigo = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                i += 2
            } else {
                codigo = bytes[i].toInt() and 0xFF
                i++
            }
            val t = aTexto[codigo] ?: if (!dosBytes) latin(codigo) else null
            out += Letra(t, anchos[codigo] ?: anchoPorDefecto, !dosBytes && codigo == 32)
        }
        return out
    }

    private fun latin(c: Int): String? = when {
        c < 32 || c == 127 -> null
        c < 127 -> c.toChar().toString()
        // De 128 a 160 WinAnsi mete comillas y guiones donde Latin-1 no tiene nada; de ahí
        // en adelante los dos coinciden.
        c in 128..160 -> WIN_ANSI[c - 128]
        else -> c.toChar().toString()
    }

    companion object {
        private val WIN_ANSI = arrayOf(
            "€", null, "‚", "ƒ", "„", "…", "†", "‡", "ˆ", "‰", "Š", "‹", "Œ", null, "Ž", null,
            null, "'", "'", "“", "”", "•", "–", "—", "˜", "™", "š", "›", "œ", null, "ž", "Ÿ",
            " "
        )

        fun leer(archivo: PdfArchivo, d: PdfValor.Dicc): Fuente {
            val subtipo = d.nombre("Subtype")
            val base = (archivo.resolver(d.entradas["BaseFont"]) as? PdfValor.Nombre)?.valor ?: ""
            val enc = archivo.resolver(d.entradas["Encoding"])
            val nombreEnc = (enc as? PdfValor.Nombre)?.valor
            val dosBytes = subtipo == "Type0" &&
                (nombreEnc == null || nombreEnc.startsWith("Identity") || nombreEnc.contains("UCS2") ||
                    nombreEnc.endsWith("-H") || nombreEnc.endsWith("-V"))

            val anchos = HashMap<Int, Double>()
            var porDefecto = if (subtipo == "Type0") 1000.0 else 500.0

            if (subtipo == "Type0") {
                val hijo = (archivo.resolver(d.entradas["DescendantFonts"]) as? PdfValor.Lista)
                    ?.valores?.firstOrNull()?.let { archivo.diccDe(it) }
                hijo?.numero("DW")?.let { porDefecto = it }
                (archivo.resolver(hijo?.entradas?.get("W")) as? PdfValor.Lista)?.valores?.let {
                    leerW(archivo, it, anchos)
                }
            } else {
                val primero = d.entero("FirstChar") ?: 0
                (archivo.resolver(d.entradas["Widths"]) as? PdfValor.Lista)?.valores
                    ?.forEachIndexed { i, v ->
                        (archivo.resolver(v) as? PdfValor.Numero)?.valor?.let { anchos[primero + i] = it }
                    }
                if (anchos.isNotEmpty()) porDefecto =
                    archivo.diccDe(d.entradas["FontDescriptor"])?.numero("MissingWidth") ?: 0.0
            }

            val aTexto = HashMap<Int, String>()
            (archivo.resolver(d.entradas["ToUnicode"]) as? PdfValor.Flujo)?.let { f ->
                archivo.descomprimir(f)?.let { leerCMap(it, aTexto) }
            }
            (enc as? PdfValor.Dicc)?.let { leerDiferencias(archivo, it, aTexto) }

            val minus = base.lowercase()
            val familia = when {
                minus.contains("courier") || minus.contains("mono") -> "monospace"
                minus.contains("sans") -> "sans-serif"
                minus.contains("times") || minus.contains("georgia") || minus.contains("garamond") ||
                    minus.contains("cambria") || minus.contains("minion") ||
                    minus.contains("serif") -> "serif"
                else -> "sans-serif"
            }
            return Fuente(
                dosBytes, anchos, porDefecto, aTexto, familia,
                negrita = minus.contains("bold") || minus.contains("black") || minus.contains("heavy"),
                cursiva = minus.contains("italic") || minus.contains("oblique")
            )
        }

        /** `/W` de una fuente CID: `[ 3 [200 300] 10 20 500 ]`, o sea sueltos y por tramos. */
        private fun leerW(archivo: PdfArchivo, v: List<PdfValor>, out: HashMap<Int, Double>) {
            var i = 0
            while (i < v.size) {
                val primero = (archivo.resolver(v[i]) as? PdfValor.Numero)?.valor?.toInt() ?: break
                val sig = archivo.resolver(v.getOrNull(i + 1))
                if (sig is PdfValor.Lista) {
                    sig.valores.forEachIndexed { k, x ->
                        (archivo.resolver(x) as? PdfValor.Numero)?.valor?.let { out[primero + k] = it }
                    }
                    i += 2
                } else {
                    val ultimo = (sig as? PdfValor.Numero)?.valor?.toInt() ?: break
                    val ancho = (archivo.resolver(v.getOrNull(i + 2)) as? PdfValor.Numero)?.valor ?: break
                    if (ultimo - primero in 0..65535) {
                        for (c in primero..ultimo) out[c] = ancho
                    }
                    i += 3
                }
            }
        }

        /**
         * El `/ToUnicode`: un CMap con `beginbfchar` y `beginbfrange`.
         *
         * Es lo que dice qué letra es cada código cuando la fuente va incrustada con su propia
         * numeración —lo normal en un plano de AutoCAD, donde `<002d>` es una «J»—. Sin esto
         * los rótulos salen como jeroglíficos.
         */
        fun leerCMap(datos: ByteArray, out: HashMap<Int, String>) {
            val f = Fichas(datos)
            var modo = 0 // 1 = sueltos (bfchar), 2 = tramos (bfrange)
            val pila = ArrayList<Any>(3)
            while (true) {
                when (f.siguiente()) {
                    Fichas.FIN -> return
                    Fichas.CADENA -> pila.add(f.cadena)
                    Fichas.NUMERO -> pila.add(f.numero)
                    Fichas.LISTA -> pila.add(f.lista)
                    Fichas.NOMBRE -> Unit
                    Fichas.OPERADOR -> when (f.texto) {
                        "beginbfchar" -> { modo = 1; pila.clear() }
                        "beginbfrange" -> { modo = 2; pila.clear() }
                        "endbfchar", "endbfrange" -> { modo = 0; pila.clear() }
                        "begincodespacerange", "endcodespacerange" -> { modo = 0; pila.clear() }
                        else -> pila.clear()
                    }
                }
                // Las entradas no llevan operador entre medias: se sueltan en cuanto hay
                // bastantes números en la mano —dos para un suelto, tres para un tramo—.
                if (modo == 1 && pila.size >= 2) {
                    val de = pila[0] as? ByteArray
                    val a = pila[1]
                    if (de != null) apuntar(codigo(de), a, out)
                    pila.clear()
                } else if (modo == 2 && pila.size >= 3) {
                    val de = pila[0] as? ByteArray
                    val hasta = pila[1] as? ByteArray
                    val a = pila[2]
                    if (de != null && hasta != null) {
                        val d = codigo(de)
                        val h = codigo(hasta)
                        if (h >= d && h - d <= 65535) {
                            if (a is ArrayList<*>) {
                                for (i in 0..(h - d)) apuntar(d + i, a.getOrNull(i), out)
                            } else if (a is ByteArray) {
                                // Un tramo con un solo destino numera desde ahí: `<20> <22> <41>`
                                // son la A, la B y la C.
                                val base = texto(a)
                                val primero = base.lastOrNull()?.code ?: return
                                for (i in 0..(h - d)) {
                                    out[d + i] = base.dropLast(1) + (primero + i).toChar()
                                }
                            }
                        }
                    }
                    pila.clear()
                }
                if (pila.size > 3) pila.clear()
            }
        }

        private fun apuntar(codigo: Int, destino: Any?, out: HashMap<Int, String>) {
            val t = when (destino) {
                is ByteArray -> texto(destino)
                else -> return
            }
            if (t.isNotEmpty()) out[codigo] = t
        }

        /** Un código de un CMap: uno o dos bytes en hexadecimal. */
        private fun codigo(b: ByteArray): Int {
            var v = 0
            for (x in b) v = (v shl 8) or (x.toInt() and 0xFF)
            return v
        }

        /** El destino de un CMap es UTF-16 en hexadecimal: `<0041>` es la «A». */
        private fun texto(b: ByteArray): String =
            if (b.size >= 2) String(b, 0, b.size and 1.inv(), Charsets.UTF_16BE)
            else String(b, Charsets.ISO_8859_1)

        private fun leerDiferencias(archivo: PdfArchivo, enc: PdfValor.Dicc, out: HashMap<Int, String>) {
            val lista = (archivo.resolver(enc.entradas["Differences"]) as? PdfValor.Lista)?.valores ?: return
            var codigo = 0
            for (v in lista) {
                when (val x = archivo.resolver(v)) {
                    is PdfValor.Numero -> codigo = x.valor.toInt()
                    is PdfValor.Nombre -> {
                        deNombreDeGlifo(x.valor)?.let { if (codigo !in out) out[codigo] = it }
                        codigo++
                    }
                    else -> Unit
                }
            }
        }

        /** `/uni0041` y `/A` son la «A»; lo demás se deja como está. */
        private fun deNombreDeGlifo(n: String): String? = when {
            n.startsWith("uni") && n.length >= 7 ->
                n.substring(3, 7).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            n.length == 1 -> n
            else -> null
        }
    }
}
