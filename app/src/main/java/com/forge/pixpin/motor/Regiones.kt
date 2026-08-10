package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * El bote de pintura: **rellenar el hueco que dejan varias figuras**.
 *
 * Hasta ahora un relleno era siempre de una figura —el fondo de un rectángulo,
 * el de un lazo cerrado—, así que solo se podía colorear lo que alguien hubiera
 * dibujado de una tacada. El espacio entre tres líneas y media elipse no es de
 * ninguna de las cuatro y no había forma de pintarlo: había que repasarlo a mano
 * con el lápiz y rellenar ese repaso, que es tanto trabajo como no tenerlo.
 *
 * Aquí se hace al revés: se toca dentro del hueco y **se busca hasta dónde llega
 * sin salirse**. Si lo que se toca está encerrado, sale su contorno con sus
 * agujeros; si se escapa por una rendija, no sale nada — y eso es una respuesta,
 * no un fallo: quiere decir que el espacio no está cerrado.
 *
 * ## Por qué por rejilla y no por geometría
 *
 * La respuesta «de libro» sería construir el grafo de todas las intersecciones
 * entre figuras y buscar la cara mínima que contiene el punto. Es exacto y es
 * una fuente inagotable de casos degenerados: tres líneas que se cortan en el
 * mismo punto, un trazo tangente a un círculo, un garabato con doscientos
 * cruces consigo mismo. Cada uno de esos casos, en un motor de dibujo a mano
 * alzada, es lo **normal**, no la excepción.
 *
 * Pintar las paredes en una rejilla y derramar desde el punto tocado no tiene
 * casos degenerados: una tangente es una pared, un cruce triple es una pared, y
 * el resultado depende solo de por dónde se puede pasar. A cambio se pierde
 * precisión, y esa pérdida es justo la tolerancia que hace falta:
 *
 * **Un hueco más estrecho que una celda se da por cerrado.** Dibujando con el
 * dedo, dos trazos que «se tocan» casi nunca se tocan de verdad; sin tolerancia,
 * el bote no funcionaría casi nunca y nadie sabría por qué. Con ella, cierra lo
 * que el ojo ve cerrado.
 *
 * Sin Android: la rejilla es un `BooleanArray` y el contorno sale de recorrer
 * los bordes de las celdas. Se puede comprobar entero sin dispositivo, que es lo
 * que importa cuando el resultado es una lista de puntos que nadie va a mirar a
 * ojo.
 */

/** Un espacio cerrado, ya encontrado. Los huecos son los agujeros de dentro. */
data class Region(val contorno: List<Pt>, val huecos: List<List<Pt>> = emptyList())

/** Cómo de fino se busca. Los valores por defecto son los que usa la app. */
data class AjustesRelleno(
    /**
     * En cuántas celdas se parte el lado mayor de la zona de trabajo.
     *
     * Es el mando que reparte precisión y tiempo, y **también es el que decide
     * cuánto se sale la mancha**. El relleno crece una celda hacia el trazo para
     * llegar hasta él (ver `dilatar`), así que puede asomar hasta una celda por
     * la cara de fuera: con celdas gordas eso es un reborde de color alrededor
     * de la figura, que es lo que se veía como «el relleno se sale de su
     * contenedor».
     *
     * Subido de 320 a 480 por eso: en un dibujo de 2000 px de ancho la celda
     * pasa de 6 a 4 px, así que lo que puede asomar es menos que el grosor de un
     * trazo normal y queda tapado por él. El coste sube con el cuadrado, y 480
     * son 230.000 celdas — un derrame de unos pocos milisegundos, y solo al
     * tocar.
     */
    val celdas: Int = 480,
    /**
     * Cuánto se aparta el borde de la zona de trabajo del dibujo, en celdas.
     *
     * Tiene que ser al menos una: el borde es lo que detecta que el espacio
     * **no** estaba cerrado —el derrame lo alcanza y se sabe que se ha escapado—
     * y pegado al dibujo se confundiría con una pared.
     */
    val margen: Int = 3,
    /**
     * Cuántas celdas crece la mancha hacia las paredes al terminar.
     *
     * El derrame se para **antes** de la celda de la pared, así que sin esto el
     * relleno acabaría una celda antes del trazo y se vería una raya de fondo
     * entre la mancha y la línea. Creciendo una celda, la mancha llega hasta el
     * trazo y este la tapa por encima. Más de una empezaría a colarse al otro
     * lado de las paredes de un solo trazo.
     */
    val dilatacion: Int = 1
)

/**
 * El espacio cerrado que contiene [p], o null si no hay ninguno.
 *
 * Null significa una de tres, y las tres quieren decir lo mismo para quien
 * dibuja —«esto no está cerrado»—: que el punto caiga fuera de todo, que el
 * derrame llegue al borde de la zona de trabajo, o que se haya tocado justo
 * encima de un trazo y no haya sitio libre al lado.
 */
fun regionEn(
    elementos: List<Element>,
    p: Pt,
    ajustes: AjustesRelleno = AjustesRelleno()
): Region? {
    val paredes = elementos.filter { !it.isDeleted && esPared(it) }
    if (paredes.isEmpty()) return null

    val segmentos = paredes.flatMap { segmentosDe(it) }
    if (segmentos.isEmpty()) return null

    val rejilla = Rejilla.para(segmentos, p, ajustes) ?: return null
    for ((a, b) in segmentos) rejilla.pintarPared(a, b)

    if (!rejilla.derramar(p)) return null
    repeat(max(ajustes.dilatacion, 0)) { rejilla.dilatar() }

    // **Primero se pegan a las paredes, después se simplifican.**
    //
    // El contorno que sale del derrame es una escalera: sigue los bordes de las
    // celdas, así que allí donde la pared va en diagonal la mancha asoma por un
    // lado y se queda corta por el otro. Es exactamente lo que se veía —«en unos
    // sitios se sale y en otros ni llega al borde»— y no se arregla subiendo la
    // resolución: con celdas más finas la escalera es más fina, pero sigue
    // siendo una escalera, y cada duplicar cuesta cuatro veces más.
    //
    // Pegando cada vértice a la línea que tiene al lado, el contorno deja de
    // aproximar la pared y pasa a **estar sobre ella**. El orden importa: si se
    // simplificara antes, la simplificación elegiría vértices de la escalera y
    // se pegarían los que quedaran, no los que había que pegar.
    val anillos = rejilla.contornos()
        .map { simplificar(pegadoALasParedes(it, segmentos, rejilla.celda * ARRIME), rejilla.celda) }
        .filter { it.size >= 3 }
    if (anillos.isEmpty()) return null

    // El de fuera es el de mayor superficie: los demás son sus agujeros. Con la
    // mancha ya derramada no puede haber dos «de fuera», así que no hace falta
    // mirar quién está dentro de quién.
    val fuera = anillos.maxByOrNull { abs(areaDe(it)) }!!
    return Region(fuera, anillos.filter { it !== fuera })
}

/**
 * ¿Este elemento hace de pared?
 *
 * La regla es «lo que dibuja una línea encierra; lo que es un fondo, no». La
 * imagen y la hoja se quedan fuera porque son el soporte: contando la foto sobre
 * la que se anota, **todo** estaría siempre cerrado —la propia foto sería el
 * contorno— y tocar en un sitio vacío teñiría la captura entera. El foco y el
 * mosaico son manchas, no bordes. El texto tampoco: su caja no se dibuja, y
 * chocar contra un rectángulo invisible es de las cosas que más desconciertan.
 */
fun esPared(e: Element): Boolean = when (e.type) {
    // El punto tampoco: es una marca sobre el dibujo, no encierra nada, y un
    // redondel de cinco píxeles no puede detener un derrame.
    ElementType.IMAGE, ElementType.FRAME, ElementType.SPOTLIGHT,
    ElementType.MOSAIC, ElementType.TEXT, ElementType.ESCALA_GRAFICA,
    ElementType.PUNTO -> false

    /**
     * **Un relleno no es pared, y esto costó descubrirlo.**
     *
     * Al principio sí lo era, y el bote funcionaba de maravilla las primeras
     * veces y luego empezaba a rellenar a trozos o con formas raras. El motivo:
     * el contorno de un relleno no cae exactamente sobre los trazos que lo
     * encerraban —sale de una rejilla, con su celda de margen— así que cada
     * mancha dejaba en la escena una pared nueva ligeramente desplazada de las
     * de verdad. La siguiente chocaba contra ella y se quedaba corta, y la
     * siguiente contra las dos. Cuantos más rellenos, peor: exactamente lo que
     * se veía.
     */
    ElementType.REGION -> false

    ElementType.RECTANGLE, ElementType.DIAMOND, ElementType.ELLIPSE,
    ElementType.ARROW, ElementType.LINE, ElementType.FREEDRAW,
    ElementType.SERIAL, ElementType.MEASURE, ElementType.ARC -> true
}

/** Los anillos de un relleno en coordenadas de escena: el de fuera y sus huecos. */
fun anillosDeRegion(e: Element): List<List<Pt>> {
    val fuera = e.points?.map { Pt(e.x + it.x, e.y + it.y) } ?: return emptyList()
    val dentro = e.huecos.orEmpty().map { anillo -> anillo.map { Pt(e.x + it.x, e.y + it.y) } }
    return listOf(fuera) + dentro
}

/**
 * Un relleno ya colocado, a partir de la región encontrada.
 *
 * El color sale del fondo del pincel, y **si no hay fondo se usa el del trazo**:
 * el fondo nace transparente, así que sin esa salida el primer botellazo de todo
 * el mundo no pintaría nada y parecería que la herramienta está rota.
 */
fun nuevaRegion(region: Region, style: ItemStyle): Element {
    val caja = boundsOfPoints(region.contorno)
    val color = if (isTransparent(style.backgroundColor)) style.strokeColor
    else style.backgroundColor
    return newElement(
        ElementType.REGION, caja.x1, caja.y1, style,
        width = caja.width, height = caja.height
    ).copy(
        backgroundColor = color,
        points = region.contorno.map { Pt(it.x - caja.x1, it.y - caja.y1) },
        huecos = region.huecos.map { anillo ->
            anillo.map { Pt(it.x - caja.x1, it.y - caja.y1) }
        }.ifEmpty { null }
    )
}

/**
 * Mete [relleno] en la escena **por debajo de lo que lo encierra**.
 *
 * Si fuera encima taparía por dentro justo los trazos que forman el hueco, y una
 * línea a la que le comen medio grosor se ve más fina que sus vecinas: el dibujo
 * queda desigual sin que se sepa por qué. Debajo del todo tampoco vale — se
 * escondería detrás de la foto sobre la que se anota, que va al fondo y
 * bloqueada.
 *
 * Así que va justo debajo de **la primera pared que se cruza en su camino**: por
 * encima del soporte y por debajo de todo lo que lo dibuja.
 */
fun conRellenoDebajo(
    elementos: List<Element>,
    relleno: Element,
    /**
     * Dónde se tocó. **Lo que ya estuviera relleno ahí se sustituye.**
     *
     * Un bote no apila capas de pintura: vuelves a dar sobre el mismo hueco
     * porque querías otro color, no dos manchas superpuestas. Sin esto, insistir
     * dejaba una pila de rellenos idénticos que había que deshacer uno a uno, y
     * el rayado de todos ellos se sumaba hasta verse como un sólido sucio.
     */
    tocado: Pt? = null
): List<Element> {
    val previos = if (tocado == null) emptySet() else elementos.filter {
        it.isRegion && !it.isDeleted && !it.locked &&
            // Solo se sustituye lo del mismo mundo: una mancha de guía no borra
            // una del dibujo aunque estén una encima de la otra.
            it.reference == relleno.reference && puntoEnRegion(it, tocado)
    }.map { it.id }.toSet()
    val base = elementos.filter { it.id !in previos }

    val caja = getElementBounds(relleno)
    val indice = base.indexOfFirst {
        !it.isDeleted && !it.locked && esPared(it) && boundsOverlap(caja, getElementBounds(it))
    }
    if (indice < 0) return base + relleno
    return base.subList(0, indice) + relleno + base.subList(indice, base.size)
}

/** ¿El punto cae dentro del relleno, contando sus agujeros? */
fun puntoEnRegion(e: Element, p: Pt): Boolean {
    val anillos = anillosDeRegion(e)
    if (anillos.isEmpty()) return false
    // Par/impar sobre todos los anillos a la vez: dentro de un agujero se cruzan
    // dos contornos y vuelve a quedar fuera, que es exactamente lo que se quiere.
    var dentro = false
    for (anillo in anillos) if (cruzaImpar(p, anillo)) dentro = !dentro
    return dentro
}

private fun cruzaImpar(p: Pt, poligono: List<Pt>): Boolean {
    if (poligono.size < 3) return false
    var dentro = false
    var j = poligono.size - 1
    for (i in poligono.indices) {
        val a = poligono[i]
        val b = poligono[j]
        if ((a.y > p.y) != (b.y > p.y) &&
            p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
        ) {
            dentro = !dentro
        }
        j = i
    }
    return dentro
}

// -------------------------------------------------------------------------
// La rejilla
// -------------------------------------------------------------------------

/**
 * La zona de trabajo partida en celdas, con sus paredes y su mancha.
 *
 * Las dos van en el mismo array —`PARED`, `LIBRE`, `MANCHA`— y no en dos: se
 * consulta una vez por celda en el bucle más caliente del algoritmo.
 */
private class Rejilla(
    val x1: Double,
    val y1: Double,
    val celda: Double,
    val cols: Int,
    val filas: Int
) {
    private val estado = ByteArray(cols * filas)

    private fun indice(ix: Int, iy: Int) = iy * cols + ix
    private fun dentro(ix: Int, iy: Int) = ix in 0 until cols && iy in 0 until filas

    fun columnaDe(x: Double): Int = floor((x - x1) / celda).toInt()
    fun filaDe(y: Double): Int = floor((y - y1) / celda).toInt()

    fun esPared(ix: Int, iy: Int) = dentro(ix, iy) && estado[indice(ix, iy)] == PARED
    fun esMancha(ix: Int, iy: Int) = dentro(ix, iy) && estado[indice(ix, iy)] == MANCHA

    /** La esquina superior izquierda de la celda, en coordenadas de escena. */
    fun esquina(ix: Int, iy: Int) = Pt(x1 + ix * celda, y1 + iy * celda)

    /**
     * Pinta el tramo de pared que va de [a] a [b] (Amanatides y Woo).
     *
     * **Avanza celda a celda, nunca en diagonal**, y eso no es un detalle de
     * implementación: es lo que hace la pared estanca. Marcando solo las celdas
     * que tocan los extremos —o dando saltos en diagonal— una línea inclinada
     * deja pasar la mancha por las esquinas, y el relleno se escapa por una
     * pared que a la vista está entera.
     */
    fun pintarPared(a: Pt, b: Pt) {
        var ix = columnaDe(a.x)
        var iy = filaDe(a.y)
        val ixFin = columnaDe(b.x)
        val iyFin = filaDe(b.y)

        val dx = b.x - a.x
        val dy = b.y - a.y
        val pasoX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val pasoY = if (dy > 0) 1 else if (dy < 0) -1 else 0

        // Cuánto falta —en parámetro de la recta— para cruzar el siguiente borde
        // vertical y el siguiente horizontal, y cuánto se tarda de borde a borde.
        var tX = if (pasoX == 0) Double.MAX_VALUE
        else (x1 + (ix + if (pasoX > 0) 1 else 0) * celda - a.x) / dx
        var tY = if (pasoY == 0) Double.MAX_VALUE
        else (y1 + (iy + if (pasoY > 0) 1 else 0) * celda - a.y) / dy
        val saltoX = if (pasoX == 0) Double.MAX_VALUE else celda / abs(dx)
        val saltoY = if (pasoY == 0) Double.MAX_VALUE else celda / abs(dy)

        marcarPared(ix, iy)
        // Cortafuegos: con coordenadas absurdas (infinitos, NaN) el recorrido no
        // termina solo, y esto se llama con lo que haya en la escena.
        var vueltas = 0
        val tope = cols + filas + 4
        while ((ix != ixFin || iy != iyFin) && vueltas++ < tope) {
            if (tX < tY) {
                ix += pasoX
                tX += saltoX
            } else {
                iy += pasoY
                tY += saltoY
            }
            marcarPared(ix, iy)
        }
    }

    private fun marcarPared(ix: Int, iy: Int) {
        if (dentro(ix, iy)) estado[indice(ix, iy)] = PARED
    }

    /**
     * Derrama desde [p]. Devuelve false si el espacio no estaba cerrado.
     *
     * Que la mancha llegue al borde de la zona de trabajo **es** la prueba de
     * que se ha escapado: el borde está a varias celdas de lo más externo que
     * haya dibujado, así que solo se alcanza saliendo por algún lado.
     */
    fun derramar(p: Pt): Boolean {
        val inicio = celdaLibreCerca(columnaDe(p.x), filaDe(p.y)) ?: return false

        val pila = ArrayDeque<Int>()
        pila.addLast(inicio)
        estado[inicio] = MANCHA

        while (pila.isNotEmpty()) {
            val i = pila.removeLast()
            val ix = i % cols
            val iy = i / cols
            if (ix == 0 || iy == 0 || ix == cols - 1 || iy == filas - 1) return false

            for ((jx, jy) in listOf(
                ix - 1 to iy, ix + 1 to iy, ix to iy - 1, ix to iy + 1
            )) {
                if (!dentro(jx, jy)) continue
                val j = indice(jx, jy)
                if (estado[j] == LIBRE) {
                    estado[j] = MANCHA
                    pila.addLast(j)
                }
            }
        }
        return true
    }

    /**
     * La celda libre desde la que derramar, o null si no hay ninguna al lado.
     *
     * Tocar justo encima de un trazo es lo más normal del mundo —el dedo tapa
     * medio centímetro—, y ahí lo que quiere quien toca es rellenar a un lado.
     * Se busca en anillos hacia afuera, así que gana la más cercana.
     */
    private fun celdaLibreCerca(ix: Int, iy: Int): Int? {
        if (!dentro(ix, iy)) return null
        if (estado[indice(ix, iy)] == LIBRE) return indice(ix, iy)
        for (r in 1..BUSQUEDA_LIBRE) {
            for (dx in -r..r) for (dy in -r..r) {
                if (abs(dx) != r && abs(dy) != r) continue
                val jx = ix + dx
                val jy = iy + dy
                if (dentro(jx, jy) && estado[indice(jx, jy)] == LIBRE) return indice(jx, jy)
            }
        }
        return null
    }

    /** Crece la mancha una celda hacia las paredes que la rodean. */
    fun dilatar() {
        val frontera = mutableListOf<Int>()
        for (iy in 0 until filas) for (ix in 0 until cols) {
            if (estado[indice(ix, iy)] != MANCHA) continue
            for ((jx, jy) in listOf(
                ix - 1 to iy, ix + 1 to iy, ix to iy - 1, ix to iy + 1
            )) {
                // El borde de la zona de trabajo no se invade: es lo que sostiene
                // la comprobación de «se ha escapado».
                if (jx !in 1 until cols - 1 || jy !in 1 until filas - 1) continue
                if (estado[indice(jx, jy)] != PARED) continue
                frontera += indice(jx, jy)
            }
        }
        for (i in frontera) estado[i] = MANCHA
    }

    /**
     * Los contornos de la mancha, en coordenadas de escena.
     *
     * Se recorren los **bordes de celda** que separan mancha de no-mancha, cada
     * uno orientado para dejar la mancha a un lado, y se encadenan por sus
     * vértices. Salen tantos anillos como bordes tenga la mancha: uno por fuera
     * y uno por cada agujero, sin tener que distinguirlos aquí.
     */
    fun contornos(): List<List<Pt>> {
        // Vértice = esquina de celda, numerada en una rejilla de (cols+1)×(filas+1).
        val salidas = HashMap<Int, MutableList<Int>>()
        fun vertice(ix: Int, iy: Int) = iy * (cols + 1) + ix
        fun arista(desdeX: Int, desdeY: Int, hastaX: Int, hastaY: Int) {
            salidas.getOrPut(vertice(desdeX, desdeY)) { mutableListOf() }
                .add(vertice(hastaX, hastaY))
        }

        for (iy in 0 until filas) for (ix in 0 until cols) {
            if (estado[indice(ix, iy)] != MANCHA) continue
            if (!esMancha(ix, iy - 1)) arista(ix, iy, ix + 1, iy)
            if (!esMancha(ix + 1, iy)) arista(ix + 1, iy, ix + 1, iy + 1)
            if (!esMancha(ix, iy + 1)) arista(ix + 1, iy + 1, ix, iy + 1)
            if (!esMancha(ix - 1, iy)) arista(ix, iy + 1, ix, iy)
        }

        val anillos = mutableListOf<List<Pt>>()
        while (salidas.isNotEmpty()) {
            val arranque = salidas.keys.first()
            val camino = mutableListOf<Int>()
            var actual = arranque
            var cerro = false
            // Un vértice puede tener dos salidas cuando la mancha se estrangula
            // en una esquina; se coge cualquiera y el otro anillo sale en la
            // vuelta siguiente. Lo importante es que ningún borde se quede sin
            // recorrer, y por eso se consumen de uno en uno.
            while (true) {
                val siguientes = salidas[actual] ?: break
                val siguiente = siguientes.removeAt(siguientes.size - 1)
                if (siguientes.isEmpty()) salidas.remove(actual)
                camino += actual
                actual = siguiente
                if (actual == arranque) {
                    cerro = true
                    break
                }
            }
            // **Solo se queda con lo que cierra.** En un estrangulamiento, una
            // vuelta puede dejar el vértice descuadrado y la siguiente acabar en
            // un callejón; ese camino abierto no es el borde de nada, y meterlo
            // como anillo pintaría una cuña que no existe.
            if (cerro && camino.size >= 4) {
                anillos += camino.map { esquina(it % (cols + 1), it / (cols + 1)) }
            }
        }
        return anillos
    }

    companion object {
        private const val LIBRE: Byte = 0
        private const val PARED: Byte = 1
        private const val MANCHA: Byte = 2

        /** Hasta dónde se busca sitio libre al tocar encima de un trazo. */
        private const val BUSQUEDA_LIBRE = 3

        /**
         * La rejilla que cubre lo dibujado **y** el punto tocado, o null si el
         * punto se sale de ahí: fuera del dibujo no hay nada que encerrar.
         */
        fun para(
            segmentos: List<Pair<Pt, Pt>>, p: Pt, ajustes: AjustesRelleno
        ): Rejilla? {
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for ((a, b) in segmentos) {
                minX = min(minX, min(a.x, b.x)); maxX = max(maxX, max(a.x, b.x))
                minY = min(minY, min(a.y, b.y)); maxY = max(maxY, max(a.y, b.y))
            }
            if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
                return null
            }
            if (p.x < minX || p.x > maxX || p.y < minY || p.y > maxY) return null

            val ancho = maxX - minX
            val alto = maxY - minY
            val lado = max(ancho, alto)
            if (lado <= 0.0) return null

            val celdas = ajustes.celdas.coerceIn(32, 1024)
            val celda = lado / celdas
            val margen = max(ajustes.margen, 1)
            val cols = ceil(ancho / celda).toInt() + 1 + margen * 2
            val filas = ceil(alto / celda).toInt() + 1 + margen * 2
            return Rejilla(
                x1 = minX - margen * celda,
                y1 = minY - margen * celda,
                celda = celda,
                cols = cols,
                filas = filas
            )
        }
    }
}

// -------------------------------------------------------------------------
// Del escalón al contorno
// -------------------------------------------------------------------------

/**
 * Quita los puntos que no aportan forma.
 *
 * El contorno sale de recorrer bordes de celda, así que es una escalera: una
 * diagonal de cien celdas llega con cuatrocientos puntos, todos a un paso de
 * distancia. Sin esto, cada relleno metería miles de puntos en la escena, el
 * archivo pesaría lo que no está escrito y el rayado tardaría una eternidad.
 *
 * Dos pasadas: primero se tiran los colineales exactos —los tramos rectos de la
 * escalera, que son la mayoría— y después Douglas-Peucker con la tolerancia de
 * una celda, que es la precisión que la rejilla tenía de todas formas. El
 * resultado no se despega del original más de lo que ya se había redondeado.
 */
internal fun simplificar(anillo: List<Pt>, tolerancia: Double): List<Pt> {
    if (anillo.size < 4) return anillo
    val sinRectas = quitarColineales(anillo)
    if (sinRectas.size < 4) return sinRectas

    // Douglas-Peucker quiere una polilínea con principio y final, y esto es un
    // anillo. Se parte por el punto más lejano al primero: los dos extremos de
    // la partición son los dos vértices más marcados del contorno, así que
    // ninguno de los dos se puede perder por el camino.
    val a = 0
    val b = sinRectas.indices.maxByOrNull { i ->
        val d = sinRectas[i]
        (d.x - sinRectas[0].x) * (d.x - sinRectas[0].x) +
            (d.y - sinRectas[0].y) * (d.y - sinRectas[0].y)
    } ?: return sinRectas
    if (b <= a + 1) return sinRectas

    val primera = douglasPeucker(sinRectas.subList(a, b + 1), tolerancia)
    val segunda = douglasPeucker(sinRectas.subList(b, sinRectas.size) + sinRectas[0], tolerancia)
    // Los dos trozos comparten sus extremos: se quitan los repetidos al coser.
    return primera + segunda.subList(1, segunda.size - 1)
}

private fun quitarColineales(anillo: List<Pt>): List<Pt> {
    val out = mutableListOf<Pt>()
    for (i in anillo.indices) {
        val anterior = anillo[(i - 1 + anillo.size) % anillo.size]
        val actual = anillo[i]
        val siguiente = anillo[(i + 1) % anillo.size]
        val cruz = (actual.x - anterior.x) * (siguiente.y - anterior.y) -
            (actual.y - anterior.y) * (siguiente.x - anterior.x)
        if (abs(cruz) > 1e-9) out += actual
    }
    return if (out.size >= 3) out else anillo
}

/**
 * Douglas-Peucker sobre una polilínea abierta.
 *
 * Es el `simplify` que usa Excalidraw antes de rellenar un lápiz cerrado, y el
 * que aquí desescalona los contornos que salen de la rejilla. Uno solo para los
 * dos: quitar los puntos que no aportan forma es el mismo problema.
 */
internal fun douglasPeucker(puntos: List<Pt>, tolerancia: Double): List<Pt> {
    if (puntos.size < 3) return puntos
    var peor = 0.0
    var indice = 0
    for (i in 1 until puntos.size - 1) {
        val d = distanceToSegment(puntos[i], puntos.first(), puntos.last())
        if (d > peor) {
            peor = d
            indice = i
        }
    }
    if (peor <= tolerancia) return listOf(puntos.first(), puntos.last())
    val izquierda = douglasPeucker(puntos.subList(0, indice + 1), tolerancia)
    val derecha = douglasPeucker(puntos.subList(indice, puntos.size), tolerancia)
    return izquierda.subList(0, izquierda.size - 1) + derecha
}

/** Superficie con signo. Sirve para saber cuál es el anillo de fuera. */
internal fun areaDe(anillo: List<Pt>): Double {
    if (anillo.size < 3) return 0.0
    var suma = 0.0
    var j = anillo.size - 1
    for (i in anillo.indices) {
        suma += (anillo[j].x + anillo[i].x) * (anillo[j].y - anillo[i].y)
        j = i
    }
    return suma / 2
}

/**
 * A cuántas celdas de distancia se le permite a un vértice buscar su pared.
 *
 * Poco más de una: el derrame se para a una celda de la pared y la dilatación lo
 * acerca otra, así que la distancia real es menos de dos. Más margen empezaría a
 * pegar a la pared vértices que no eran del borde —los de un recodo hacia dentro
 * de la mancha— y eso deforma el relleno en vez de ajustarlo.
 */
private const val ARRIME = 1.6

/**
 * Lleva cada vértice del contorno **hasta la pared que tiene al lado**.
 *
 * El contorno que sale de una rejilla va por los bordes de las celdas, así que
 * contra una pared en diagonal describe una escalera: asoma por fuera en unos
 * tramos y se queda corto en otros. Aquí cada punto se proyecta sobre el
 * segmento más cercano que tenga dentro de [tolerancia] y se queda ahí.
 *
 * Lo que no está cerca de ninguna pared se deja intacto: son los tramos que
 * cruzan el aire —la mancha entre dos figuras que no se tocan— y ahí no hay
 * nada a lo que ajustarse.
 *
 * Los puntos que al pegarse caen unos encima de otros se funden: una escalera de
 * cinco peldaños contra la misma recta se convierte en dos puntos, que es lo que
 * había que dibujar desde el principio.
 */
internal fun pegadoALasParedes(
    contorno: List<Pt>,
    paredes: List<Pair<Pt, Pt>>,
    tolerancia: Double
): List<Pt> {
    if (contorno.isEmpty() || paredes.isEmpty() || tolerancia <= 0) return contorno

    val pegados = ArrayList<Pt>(contorno.size)
    for (v in contorno) {
        var mejor: Pt? = null
        var mejorDist = tolerancia
        for ((a, b) in paredes) {
            val sobre = puntoMasCercanoDelTramo(v, a, b)
            val d = hypot(sobre.x - v.x, sobre.y - v.y)
            if (d <= mejorDist) {
                mejorDist = d
                mejor = sobre
            }
        }
        pegados.add(mejor ?: v)
    }

    // Fundir los que han quedado encima: dos vértices de la escalera pegados a
    // la misma recta acaban en el mismo sitio, y un camino con puntos repetidos
    // desconcierta a todo lo que venga después.
    val juntos = ArrayList<Pt>(pegados.size)
    for (p in pegados) {
        val ultimo = juntos.lastOrNull()
        if (ultimo == null || hypot(p.x - ultimo.x, p.y - ultimo.y) > JUNTOS) juntos.add(p)
    }
    // Y el primero con el último, que también se tocan al cerrar el anillo.
    while (juntos.size > 2 &&
        hypot(juntos.first().x - juntos.last().x, juntos.first().y - juntos.last().y) <= JUNTOS
    ) {
        juntos.removeAt(juntos.size - 1)
    }
    return if (juntos.size >= 3) juntos else contorno
}

/** Dos puntos más cerca que esto son el mismo punto. */
private const val JUNTOS = 0.01

/** El punto del tramo `a`-`b` más cercano a `p`. */
private fun puntoMasCercanoDelTramo(p: Pt, a: Pt, b: Pt): Pt {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val largo2 = dx * dx + dy * dy
    if (largo2 <= 1e-12) return a
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / largo2).coerceIn(0.0, 1.0)
    return Pt(a.x + t * dx, a.y + t * dy)
}
