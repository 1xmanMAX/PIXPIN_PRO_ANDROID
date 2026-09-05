package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * La caja: **la única pieza del boceto 3D**.
 *
 * Con cajas se explica casi todo lo que se explica en un croquis conceptual —un
 * mueble, un edificio, cómo se apilan tres módulos, por dónde entra una pieza en
 * otra—, y una caja tiene la propiedad que ninguna otra forma tiene: **se
 * dibuja sola con tres números y se entiende sin leyenda**.
 *
 * ## Se define por la huella, no por un centro y unas medidas
 *
 * Una caja es un rectángulo del suelo ([x], [y], [ancho], [fondo]) más una
 * [altura]. Definida así, el rectángulo del suelo es exactamente el mismo dato
 * que ya tiene cualquier elemento plano del motor, así que colocarla, moverla y
 * estirarla es lo de siempre, y la retícula del suelo la enmarca sin tener que
 * inventar nada. Un centro y tres semilados serían más «3D» y no valdrían para
 * ninguna de esas tres cosas.
 *
 * ## Y no hay más formas
 *
 * Ni esferas ni cilindros ni mallas. Es un boceto: lo que hace falta es poder
 * poner un volumen donde uno quiere y que se lea de un vistazo, no modelar. La
 * caja da eso y no arrastra ninguna de las cosas que traería una malla —normales
 * por vértice, luces, recorte, orden de triángulos—, que son justo las que
 * costarían fotogramas.
 */
data class Solido(
    /** Esquina de la huella en el suelo. */
    val x: Double,
    val y: Double,
    /** Lo que mide la huella a lo largo del eje `x`. */
    val ancho: Double,
    /** Lo que mide la huella a lo largo del eje `y`: su «alto» visto en planta. */
    val fondo: Double,
    /** Lo que levanta desde donde apoya. */
    val altura: Double,
    /**
     * **A qué altura apoya.** Cero es el suelo, que es donde estaba todo hasta ahora.
     *
     * Es lo que permite apilar: una caja encima de otra es una caja cuya base está a la
     * altura de la tapa de la de abajo. Ver [Element.cota].
     */
    val base: Double = 0.0,
    /** Cuánto está girada la planta sobre el eje vertical. Ver [Element.giroEnPlanta]. */
    val giro: Double = 0.0,
    /** Cuánto está volcada sobre un eje horizontal. Ver [Element.inclinacion]. */
    val inclinacion: Double = 0.0,
    /** Qué pieza es. Ver [FormaDeSolido]. */
    val forma: FormaDeSolido = FormaDeSolido.CAJA,
    /**
     * El perfil del torno, **en tanto por uno**: `x` es el radio y `y`, la altura.
     *
     * Solo lo usa [FormaDeSolido.REVOLUCION] y va normalizado a propósito: así estirar la
     * pieza por sus tiradores estira el perfil con ella sin tener que reescribirlo, igual
     * que los puntos de una raya se escalan con su caja.
     */
    val perfil: List<Pt> = emptyList(),
    /** El contorno de la planta, en tanto por uno. Ver [Element.planta]. */
    val planta: List<Pt> = emptyList()
) {
    /**
     * La misma caja con las tres medidas en positivo.
     *
     * Arrastrar de derecha a izquierda para dibujarla deja un [ancho] negativo,
     * y eso **le da la vuelta al orden de los vértices**: con la huella al revés
     * todas las caras quedan del revés y el reparto de visibles saldría
     * invertido — se pintarían las tres de detrás. Es un fallo que no avisa: la
     * caja se ve, solo que sombreada al contrario. Se normaliza una vez, aquí, y
     * no vuelve a poder pasar.
     *
     * La altura negativa se trata igual. Una caja colgando hacia abajo del suelo
     * es algo que nadie quiere dibujar a propósito y que sale de un arrastre que
     * se pasó de largo.
     */
    fun normalizado(): Solido = Solido(
        x = min(x, x + ancho),
        y = min(y, y + fondo),
        ancho = abs(ancho),
        fondo = abs(fondo),
        altura = abs(altura),
        // La cota sí puede ser cualquier cosa positiva: es dónde apoya, no una medida.
        // Por debajo del suelo no se deja, por lo mismo que no se deja una altura
        // negativa — sale de pasarse arrastrando y no de querer dibujarlo.
        base = max(base, 0.0),
        giro = giro,
        inclinacion = inclinacion,
        forma = forma,
        perfil = perfil,
        planta = planta
    )
}

/**
 * Los ocho vértices, en coordenadas del mundo.
 *
 * El orden **es parte del contrato** y [Cara] depende de él: primero los cuatro
 * del suelo dando la vuelta en sentido contrario a las agujas del reloj visto
 * desde arriba (0..3), y luego esos mismos cuatro arriba (4..7), de forma que el
 * de arriba del vértice `i` es siempre `i + 4`.
 *
 * ```
 *      7------6        z
 *     /|     /|        |  y
 *    4------5 |        | /
 *    | 3----|-2        |/
 *    |/     |/         o------x
 *    0------1
 * ```
 */
fun verticesDe(solido: Solido): List<Pt3> =
    anillosDe(solido.normalizado()).flatten()

/**
 * Los **anillos** de vértices de la pieza, de abajo arriba y ya puestos en el mundo.
 *
 * Aquí está la generalización que abarata todo lo demás. Casi todas las piezas tienen dos
 * —el suelo y la tapa— y entre ellos va una tira de caras; la de revolución tiene tantos
 * como puntos del perfil, y entre cada dos va otra tira igual. La cuenta es la misma
 * repetida, y por eso el torno no ha traído ni una cara nueva ni un caso nuevo al reparto
 * de visibles, al orden de pintado, al picado ni al exportador.
 */
fun anillosDe(solido: Solido): List<List<Pt3>> {
    val s = solido.normalizado()
    val abajo = s.base
    val arriba = s.base + s.altura
    val planta = plantaDe(s)

    if (s.forma == FormaDeSolido.REVOLUCION) {
        val perfil = perfilUtil(s)
        return perfil.map { punto ->
            planta.map { p ->
                // El perfil dice **cuánto se encoge la planta** a esa altura: al radio
                // cero, todo el anillo se junta en el eje y la tira de caras de al lado
                // se convierte sola en un cono. Es lo que hace la punta de una copa.
                aMundo(
                    s,
                    s.ancho / 2 + (p.x - s.ancho / 2) * punto.x,
                    s.fondo / 2 + (p.y - s.fondo / 2) * punto.x,
                    abajo + (arriba - abajo) * punto.y
                )
            }
        }
    }

    val suelo = planta.map { aMundo(s, it.x, it.y, abajo) }
    val tapa = planta.mapIndexed { i, p ->
        // **La cuña es la caja con dos vértices bajados.** Los de encima de la arista `y`
        // mayor se quedan en la base, así que la tapa se convierte en un faldón inclinado
        // y la pared de ese lado se queda sin superficie: proyecta área cero y se cae
        // sola por donde ya se caían las traseras. Ni una cara nueva ni un caso nuevo.
        val z = if (s.forma == FormaDeSolido.CUNA && (i == 2 || i == 3)) abajo else arriba
        aMundo(s, p.x, p.y, z)
    }
    return listOf(suelo, tapa)
}

/**
 * El perfil que se va a tornear de verdad: **ordenado, acotado y nunca de menos de dos**.
 *
 * Un perfil de un punto no es un cuerpo, y uno desordenado en altura daría anillos que se
 * cruzan. Sin ninguno se usa el cilindro recto, que es el perfil más simple que existe y
 * el que menos sorprende si alguna vez llega una pieza sin él.
 */
private fun perfilUtil(s: Solido): List<Pt> {
    val limpio = s.perfil
        .filter { it.x.isFinite() && it.y.isFinite() }
        .map { Pt(it.x.coerceIn(0.0, 1.0), it.y.coerceIn(0.0, 1.0)) }
        .sortedBy { it.y }
    if (limpio.size < 2) return listOf(Pt(1.0, 0.0), Pt(1.0, 1.0))
    return aclarado(limpio, MAXIMOS_ANILLOS)
}

/**
 * El perfil, **con como mucho [tope] puntos y quedándose con los que dicen algo**.
 *
 * Aquí hay dos números que hay que respetar. Cada punto del perfil es un anillo de vértices,
 * y entre cada dos anillos va una tira de caras: un perfil trazado a mano alzada trae
 * doscientos puntos, y eso son **casi cinco mil caras** que repartir, ordenar y pintar en
 * cada fotograma. La pieza saldría idéntica y la aplicación se arrastraría.
 *
 * ## Y se aclara por forma, no por reparto
 *
 * Estaba repartido por igual —uno de cada tantos— y para una pieza de ingeniería eso es lo
 * peor que se puede hacer: un perfil escalonado tiene toda su información **en los
 * escalones**, y coger uno de cada trece se lleva por delante justo el hombro del escalón y
 * deja trece puntos repartidos por los tramos rectos, donde no pasa nada. Una arandela
 * salía con el canto redondeado y el fondo lleno de anillos que sobraban.
 *
 * Ahora se quitan los puntos que **no cambian la silueta**, que es el criterio de toda la
 * vida para aclarar una polilínea: se mide lo lejos que se queda cada punto de la recta
 * entre sus vecinos que se quedan, y el que se queda cerca no aporta nada. Un cilindro
 * acaba en **dos anillos** en vez de en dieciséis, un cono en dos, una arandela en los seis
 * de sus escalones y un jarrón en los que le hagan falta hasta el tope. Menos caras, y
 * además son las que había que conservar.
 */
private fun aclarado(perfil: List<Pt>, tope: Int): List<Pt> {
    if (perfil.size <= 2) return perfil
    // Se aprieta hasta que quepa. El primer intento va con una tolerancia fina —media
    // milésima del tamaño de la pieza, que no se ve—, y de ahí para arriba doblando: un
    // perfil limpio pasa a la primera y uno tembloroso se aprieta unas cuantas veces.
    var tolerancia = TOLERANCIA_DEL_PERFIL
    var salida = simplificado(perfil, tolerancia)
    var vueltas = 0
    while (salida.size > tope && vueltas < VUELTAS_PARA_AJUSTAR) {
        tolerancia *= 2
        salida = simplificado(perfil, tolerancia)
        vueltas++
    }
    // Y si ni así cabe —un perfil de verdad enrevesado—, se reparte por igual lo que quede,
    // que es lo de antes: un tope es un tope.
    if (salida.size <= tope) return salida
    val paso = (salida.size - 1).toDouble() / (tope - 1)
    return (0 until tope).map { salida[(it * paso).roundToInt().coerceIn(salida.indices)] }
}

/**
 * La polilínea sin los puntos que no cambian su forma (Ramer–Douglas–Peucker).
 *
 * Las dos puntas se quedan siempre: son las que dicen dónde empieza y dónde acaba la pieza.
 */
private fun simplificado(puntos: List<Pt>, tolerancia: Double): List<Pt> {
    if (puntos.size <= 2) return puntos
    val sequeda = BooleanArray(puntos.size)
    sequeda[0] = true
    sequeda[puntos.size - 1] = true
    // Sin recursión: un perfil a mano alzada puede traer miles de puntos, y la recursión de
    // este algoritmo llega a ser tan honda como puntos hay.
    val pendiente = ArrayDeque<Pair<Int, Int>>()
    pendiente.addLast(0 to puntos.size - 1)
    while (pendiente.isNotEmpty()) {
        val (a, b) = pendiente.removeLast()
        if (b - a < 2) continue
        var elPeor = -1
        var loPeor = tolerancia
        for (i in a + 1 until b) {
            val d = distanceToSegment(puntos[i], puntos[a], puntos[b])
            if (d > loPeor) {
                loPeor = d
                elPeor = i
            }
        }
        if (elPeor < 0) continue
        sequeda[elPeor] = true
        pendiente.addLast(a to elPeor)
        pendiente.addLast(elPeor to b)
    }
    return puntos.filterIndexed { i, _ -> sequeda[i] }
}

/**
 * Lo lejos que se puede quedar un punto de la silueta sin que se le eche de menos.
 *
 * El perfil va en tanto por uno, así que esto es media milésima de la pieza: por debajo de
 * eso no hay pantalla que lo enseñe.
 */
private const val TOLERANCIA_DEL_PERFIL = 0.0005

/** Cuántas veces se dobla la tolerancia buscando que quepa. Ver [aclarado]. */
private const val VUELTAS_PARA_AJUSTAR = 12

/** Cuántos anillos como mucho tornea una pieza de revolución. Ver [aclarado]. */
const val MAXIMOS_ANILLOS = 16

/**
 * La planta, en coordenadas de la propia pieza: **de dónde salen los lados**.
 *
 * Cuatro lados es el rectángulo de la huella tal cual, con sus esquinas — y tiene que
 * serlo, porque un cuadrado inscrito en la elipse de la huella no es la huella. Los demás
 * se inscriben en ella: tres dan un prisma triangular y veinticuatro, un cilindro. El
 * sentido del recorrido es el mismo en todos, que es de lo que depende el reparto de
 * caras vistas (ver [carasDeSolido]).
 */
private fun plantaDe(s: Solido): List<Pt> {
    // **Si trae contorno, manda el contorno.** Es lo que hace que levantar un triangulo
    // de una barra triangular y levantar un garabato de lo que de, sin que ninguna de las
    // dos cosas sea un caso aparte. Ver [Element.planta].
    if (s.planta.size >= 3) {
        return s.planta.map { Pt(it.x * s.ancho, it.y * s.fondo) }
    }
    val n = s.forma.lados
    if (n == 4) {
        return listOf(
            Pt(0.0, 0.0), Pt(s.ancho, 0.0), Pt(s.ancho, s.fondo), Pt(0.0, s.fondo)
        )
    }
    val rx = s.ancho / 2
    val ry = s.fondo / 2
    return (0 until n).map { k ->
        // Se arranca desde abajo —un vértice mirando a `-y`— para que el triángulo salga
        // con una punta hacia el frente en vez de con un lado.
        val t = -Math.PI / 2 + 2 * Math.PI * k / n
        Pt(rx + rx * cos(t), ry + ry * sin(t))
    }
}

/**
 * De la pieza al mundo: **el giro, la inclinación y el sitio**, en ese orden.
 *
 * Los dos ángulos se toman alrededor del **origen de la huella** y no de su centro: ese
 * origen es el punto que el elemento guarda proyectado, el que no se mueve al estirar por
 * los tiradores y al que se enganchan los imanes. Girando o volcando sobre el centro,
 * cambiar el ángulo movería además la pieza de sitio, que es lo que uno no ha pedido.
 *
 * Primero se vuelca sobre el eje `x` y después se gira sobre el vertical: al revés, el
 * eje de vuelco iría cambiando con el giro y los dos anillos del mando dejarían de
 * corresponderse con lo que hacen.
 */
private fun aMundo(s: Solido, dx: Double, dy: Double, dz: Double): Pt3 {
    var x = dx
    var y = dy
    var z = dz
    if (s.inclinacion != 0.0) {
        val c = cos(s.inclinacion)
        val sn = sin(s.inclinacion)
        val ny = y * c - z * sn
        val nz = y * sn + z * c
        y = ny
        z = nz
    }
    if (s.giro != 0.0) {
        val c = cos(s.giro)
        val sn = sin(s.giro)
        val nx = x * c - y * sn
        val ny = x * sn + y * c
        x = nx
        y = ny
    }
    return Pt3(s.x + x, s.y + y, z)
}

/** [aMundo] con el origen en cero: sirve para medir direcciones, no sitios. */
private fun aMundoDesdeCero(s: Solido, dx: Double, dy: Double, dz: Double): Pt3 {
    val p = aMundo(s, dx, dy, dz)
    return Pt3(p.x - s.x, p.y - s.y, p.z)
}

/** Un punto de la planta a ras de la base, para lo que solo necesita el suelo. */
private fun enPlanta(s: Solido, dx: Double, dy: Double): Pt {
    val p = aMundo(s, dx, dy, 0.0)
    return Pt(p.x, p.y)
}

/**
 * Las seis caras de la caja.
 *
 * Cada una guarda **qué vértices la forman y hacia dónde mira**. Los vértices
 * van ordenados de manera que, mirando la cara desde fuera del sólido, se
 * recorren en sentido contrario a las agujas del reloj. Ese orden es el que hace
 * que el signo del área proyectada diga si la cara se ve: ver [carasDeSolido].
 */
enum class Cara(val esquinas: List<Int>, val normal: Pt3) {
    /** La de arriba. */
    TAPA(listOf(4, 5, 6, 7), Pt3(0.0, 0.0, 1.0)),

    /** La que apoya en el suelo. Nunca se ve: siempre se mira desde arriba. */
    BASE(listOf(0, 3, 2, 1), Pt3(0.0, 0.0, -1.0)),

    PARED_Y_MENOS(listOf(0, 1, 5, 4), Pt3(0.0, -1.0, 0.0)),
    PARED_X_MAS(listOf(1, 2, 6, 5), Pt3(1.0, 0.0, 0.0)),
    PARED_Y_MAS(listOf(2, 3, 7, 6), Pt3(0.0, 1.0, 0.0)),
    PARED_X_MENOS(listOf(3, 0, 4, 7), Pt3(-1.0, 0.0, 0.0))
}

/** Una cara ya proyectada, lista para pintar. */
data class CaraDeSolido(
    val cara: Cara,
    /** El polígono en coordenadas de escena, con sus cuatro esquinas. */
    val poligono: List<Pt>,
    /** Cuánto se aclara el color base al pintarla. Ver [claridadDe]. */
    val claridad: Double,
    /** Clave de pintado: de menor a mayor es de atrás hacia delante. */
    val profundidad: Double,
    /** ¿Mira hacia quien lo mira? */
    val visible: Boolean,
    /**
     * La normal **de verdad** de esta cara, en el mundo.
     *
     * No es la de [Cara], que es la de una caja de pie y a escuadra. Sale del producto
     * vectorial de sus propios vértices, así que viene con el giro y el vuelco puestos —y
     * en un cilindro cada lado trae la suya—. Es lo que hace que la luz sepa qué
     * inclinación tiene lo que está iluminando en vez de repartir tres tonos fijos.
     */
    val normal: Pt3 = Pt3(0.0, 0.0, 0.0),
    /** Qué vértices la forman, por índice. Ver [verticesDe]. */
    val indices: List<Int> = emptyList()
)

/**
 * Las caras del sólido, **ordenadas de atrás hacia delante**.
 *
 * ## Qué se ve, por el signo del área
 *
 * Una cara se ve si mira hacia quien mira, y eso se sabe sin normales ni
 * productos escalares: se proyecta su polígono y se mira **el signo de su área
 * con signo**. Si al aplanarla los vértices siguen recorriéndose en el sentido
 * en el que se ordenaron, la cara está de cara; si el sentido se ha invertido,
 * se le está viendo el envés y hay que tirarla. Es el mismo criterio que usa
 * cualquier tarjeta gráfica para descartar caras traseras, y aquí sale gratis
 * porque el polígono ya estaba proyectado para pintarlo.
 *
 * En una caja se ven **tres de las seis**: la tapa y dos paredes. La base no se
 * ve nunca —no hay forma de mirar desde debajo del suelo, ver [Vista]— y las dos
 * paredes de detrás quedan tapadas por las de delante.
 *
 * ## Y en qué orden se pintan
 *
 * De atrás hacia delante, por la [profundidad] del centro de cada cara. Con una
 * sola caja el orden solo importa si se piden todas (`soloVisibles = false`,
 * para pintarla translúcida), porque las tres visibles no se solapan; pero es
 * también la clave con la que el que dibuja varias cajas las ordena entre sí, y
 * conviene que las dos cuentas sean la misma.
 *
 * El centro basta como clave: las caras de una caja son planas y no se cruzan
 * entre ellas, que es justo el caso en el que el pintor por profundidad media se
 * equivoca.
 */
fun carasDeSolido(
    solido: Solido,
    vista: Vista,
    paso: Double = PASO_ISO,
    soloVisibles: Boolean = true
): List<CaraDeSolido> {
    val s = solido.normalizado()
    val vertices = verticesDe(s)
    val enPantalla = vertices.map { proyectar(it, vista, paso) }

    return facetasDe(s).map { (cara, esquinas) ->
        val poligono = esquinas.map { enPantalla[it] }
        val enElMundo = esquinas.map { vertices[it] }
        val normal = normalDe(enElMundo)
        CaraDeSolido(
            cara = cara,
            poligono = poligono,
            claridad = claridadDeNormal(normal, vista),
            profundidad = enElMundo.sumOf { profundidad(it.x, it.y, it.z, vista) } / enElMundo.size,
            // Estricto, y así una caja aplastada se apaña sola: si la huella no
            // tiene ancho, lo que queda es un tabique, y sus otras cuatro caras
            // proyectan área cero. Cero no es «visible», es nada — pintarlas
            // dejaría un reguero de rayas del color del relleno sobre el dibujo.
            visible = areaConSigno(poligono) > 0.0,
            normal = normal,
            indices = esquinas
        )
    }.filter { !soloVisibles || it.visible }.sortedBy { it.profundidad }
}

/**
 * Las caras de la pieza: **la tapa, la base y una por lado**.
 *
 * Sale de la misma cuenta para las cuatro formas, y esa es la razón de que un cilindro no
 * haya costado nada: por dentro no es una forma nueva, es un prisma con más lados. El
 * orden de los vértices de cada cara —el que decide, por el signo del área, si se ve— es
 * el mismo que tenía la caja escrito a mano, y por eso las pruebas de siempre siguen
 * valiendo palabra por palabra.
 */
private fun facetasDe(s: Solido): List<Pair<Cara, List<Int>>> {
    val n = ladosDe(s)
    val anillos = numeroDeAnillos(s)
    val ultimo = (anillos - 1) * n
    val salida = mutableListOf(
        Cara.TAPA to (ultimo until ultimo + n).toList(),
        Cara.BASE to (0 until n).reversed().toList()
    )
    // Una tira de caras **entre cada dos anillos**. Con dos anillos es la caja de
    // siempre; con los diez de un perfil, los nueve tramos del torneado.
    for (nivel in 0 until anillos - 1) {
        val a0 = nivel * n
        val a1 = (nivel + 1) * n
        for (k in 0 until n) {
            val b = (k + 1) % n
            salida += etiquetaDelLado(k, n) to listOf(a0 + k, a0 + b, a1 + b, a1 + k)
        }
    }
    return salida
}

/** Cuántos lados tiene su planta: los del contorno guardado, o los de su forma. */
private fun ladosDe(s: Solido): Int =
    if (s.planta.size >= 3) s.planta.size else s.forma.lados

/** Cuántos anillos tiene la pieza: dos, salvo el torno, que tiene los del perfil. */
private fun numeroDeAnillos(s: Solido): Int =
    if (s.forma == FormaDeSolido.REVOLUCION) perfilUtil(s).size else 2

/**
 * Cómo se llama el lado número [k]. Es **una etiqueta y no geometría**.
 *
 * En la caja son los cuatro nombres de siempre, en el mismo orden en el que estaban. En
 * las demás formas se le pone el del eje al que más mira: nadie va a llamar por su nombre
 * a la cara diecisiete de un cilindro, pero el nombre sigue haciendo falta para que el
 * resto del motor no tenga que saber cuántos lados hay.
 */
private fun etiquetaDelLado(k: Int, n: Int): Cara {
    if (n == 4) return PAREDES_DE_LA_CAJA[k]
    val t = -Math.PI / 2 + 2 * Math.PI * (k + 0.5) / n
    val dx = cos(t)
    val dy = sin(t)
    return if (abs(dx) >= abs(dy)) {
        if (dx > 0) Cara.PARED_X_MAS else Cara.PARED_X_MENOS
    } else {
        if (dy > 0) Cara.PARED_Y_MAS else Cara.PARED_Y_MENOS
    }
}

private val PAREDES_DE_LA_CAJA = listOf(
    Cara.PARED_Y_MENOS, Cara.PARED_X_MAS, Cara.PARED_Y_MAS, Cara.PARED_X_MENOS
)

/**
 * La normal de una cara, del producto vectorial de sus tres primeros vértices.
 *
 * Con el orden en el que se guardan —recorridos en sentido contrario a las agujas del
 * reloj mirándolos desde fuera— sale apuntando hacia fuera sin tener que decidir nada.
 * Devuelve el vector nulo si la cara está degenerada, que es lo que le pasa a la pared que
 * la cuña se come: sin superficie no hay hacia dónde mirar.
 */
private fun normalDe(vertices: List<Pt3>): Pt3 {
    if (vertices.size < 3) return Pt3(0.0, 0.0, 0.0)
    val a = vertices[0]
    val b = vertices[1]
    val c = vertices[2]
    val ux = b.x - a.x
    val uy = b.y - a.y
    val uz = b.z - a.z
    val vx = c.x - a.x
    val vy = c.y - a.y
    val vz = c.z - a.z
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val largo = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    if (largo <= 1e-12) return Pt3(0.0, 0.0, 0.0)
    return Pt3(nx / largo, ny / largo, nz / largo)
}

/**
 * Los polígonos de las caras que se ven, en orden de pintado.
 *
 * La forma corta de [carasDeSolido] para quien solo quiere el contorno: el
 * calco, la silueta o una prueba.
 */
fun carasVisibles(solido: Solido, vista: Vista, paso: Double = PASO_ISO): List<List<Pt>> =
    carasDeSolido(solido, vista, paso).map { it.poligono }

/**
 * Área con signo del polígono ya proyectado (fórmula del cordón de zapato).
 *
 * Como en pantalla la `y` crece hacia abajo, el signo sale al revés de lo que
 * dice el libro: aquí **positivo es la cara de frente**. Y ojo, que
 * `Regiones.areaDe` usa el convenio contrario —recorre el anillo al revés—, así
 * que las dos no son intercambiables aunque se llamen casi igual.
 */
internal fun areaConSigno(poligono: List<Pt>): Double {
    if (poligono.size < 3) return 0.0
    var suma = 0.0
    for (i in poligono.indices) {
        val a = poligono[i]
        val b = poligono[(i + 1) % poligono.size]
        suma += a.x * b.y - b.x * a.y
    }
    return suma / 2
}

// -------------------------------------------------------------------------
// El sombreado
// -------------------------------------------------------------------------

/**
 * Lo que se aclara u oscurece el color de una cara a la siguiente.
 *
 * **Un escalón fijo, y ya está.** No hay vector de luz, ni normal por vértice,
 * ni un coseno por fotograma: la tapa un escalón más clara, la pared de la
 * izquierda el color base tal cual, y la de la derecha un escalón más oscura.
 *
 * Es lo que hace un dibujante a mano, y por dos motivos. El primero es que
 * basta: tres tonos separados por el mismo salto ya dicen «esto es un volumen»,
 * y el ojo no pide más en un boceto. El segundo es que es **estable**: al girar
 * la vista los tonos se quedan donde están —la luz va pegada a la pantalla, no
 * al modelo— y el dibujo no parpadea, que es exactamente lo que pasaría con una
 * luz de verdad al cruzar una cara por delante de ella.
 *
 * Un escalón grande da un cómic y uno pequeño no se ve; este está donde las tres
 * caras se distinguen sin que la oscura parezca de otro color.
 */
const val PASO_DE_CLARIDAD = 0.22

/**
 * Cuánto se aclara ([claridad] positiva) u oscurece esta cara respecto del
 * color base del sólido.
 *
 * Quién es «la de la izquierda» se decide **en pantalla y no en el mundo**: se
 * mira hacia dónde apunta la normal ya girada. Así la luz se queda quieta
 * respecto de quien mira al cambiar de [Vista], que es lo que se quiere; si el
 * tono fuera del lado del mundo, girar un cuarto haría que la cara clara saltase
 * al otro lado y el volumen entero pareciera darse la vuelta.
 */
fun claridadDe(cara: Cara, vista: Vista): Double = claridadDeNormal(cara.normal, vista)

/**
 * Lo mismo a partir de **la normal de verdad** de la cara. Ver [CaraDeSolido.normal].
 *
 * ## Ahora hay una luz, y sigue estando pegada a la pantalla
 *
 * Antes eran tres tonos repartidos por un `if`: la tapa clara, una pared el color base y
 * la otra oscura. Con una sola caja de pie eso basta y no se distingue de esto —los
 * números salen **exactamente los mismos**, que para eso se eligió hacia dónde apunta la
 * luz—. Pero en cuanto una cara deja de estar a escuadra, el `if` no tenía nada que decir:
 * un cilindro salía como veinticuatro tiras de tres colores en vez de con un degradado, y
 * una pieza volcada se sombreaba como si siguiera de pie.
 *
 * La luz va en **el sistema de quien mira** y no en el del mundo: por eso la normal se
 * gira primero con la vista. Es lo que hace que al cambiar de vista los tonos se queden
 * donde estaban y el dibujo no parpadee — con una luz clavada al mundo, girar un cuarto
 * mandaría la cara clara al otro lado y el volumen entero parecería darse la vuelta.
 *
 * Sin normal —la cara que la cuña se come no tiene— devuelve cero: no hay nada que
 * iluminar.
 */
fun claridadDeNormal(normal: Pt3, vista: Vista): Double {
    val largo = kotlin.math.sqrt(
        normal.x * normal.x + normal.y * normal.y + normal.z * normal.z
    )
    if (largo <= 1e-12) return 0.0
    val g = giroDeVista(normal.x / largo, normal.y / largo, vista)
    val d = g.x * LUZ_X + g.y * LUZ_Y + (normal.z / largo) * LUZ_Z
    return (d * GANANCIA_DE_LA_LUZ).coerceIn(-2 * PASO_DE_CLARIDAD, 2 * PASO_DE_CLARIDAD)
}

/**
 * De dónde viene la luz, **en coordenadas de la pantalla**: de arriba y por la izquierda.
 *
 * Los números no son de gusto: con esta dirección y esta ganancia, las tres caras de una
 * caja de pie dan justo `+PASO`, `0` y `−PASO`, que es el reparto de toda la vida del
 * dibujante a mano y el que tenía este módulo escrito a mano. La luz **reproduce** lo que
 * antes era una tabla, y de propina sabe qué hacer con lo que la tabla no contemplaba.
 */
private val LUZ_X = -1.0 / kotlin.math.sqrt(2.0)
private const val LUZ_Y = 0.0
private val LUZ_Z = 1.0 / kotlin.math.sqrt(2.0)

/** Lo que se estira el coseno para que la tapa quede a un escalón exacto. */
private val GANANCIA_DE_LA_LUZ = PASO_DE_CLARIDAD * kotlin.math.sqrt(2.0)

/**
 * El color de una cara: el base, movido [claridad] hacia el blanco o el negro.
 *
 * Se hace con texto y no con `Color` de Android porque esto es núcleo del motor
 * —tiene que poder comprobarse sin dispositivo— y porque los colores del modelo
 * son cadenas `#rrggbb` de principio a fin, incluso al exportar a SVG o a PDF.
 *
 * Lo que no entiende lo devuelve tal cual: `transparent` o un color con nombre
 * no se aclaran, se dejan pasar. Es a propósito — una caja sin relleno tiene que
 * seguir sin relleno, no volverse gris claro.
 */
fun aclarar(color: String, claridad: Double): String {
    if (!color.startsWith("#")) return color
    val cuerpo = color.substring(1)
    val hex = when (cuerpo.length) {
        3 -> cuerpo.map { "$it$it" }.joinToString("")
        6 -> cuerpo
        8 -> cuerpo.substring(2)
        else -> return color
    }
    val n = hex.toLongOrNull(16) ?: return color
    val alfa = if (cuerpo.length == 8) cuerpo.substring(0, 2) else ""

    val t = claridad.coerceIn(-1.0, 1.0)
    val canales = listOf(
        ((n shr 16) and 0xFFL).toDouble(),
        ((n shr 8) and 0xFFL).toDouble(),
        (n and 0xFFL).toDouble()
    ).map { c ->
        val v = if (t >= 0) c + (255 - c) * t else c * (1 + t)
        v.roundToInt().coerceIn(0, 255)
    }
    return "#" + alfa + canales.joinToString("") { it.toString(16).padStart(2, '0') }
}

// -------------------------------------------------------------------------
// La sombra
// -------------------------------------------------------------------------

/**
 * La huella proyectada sobre el suelo: **la sombra**.
 *
 * Es el canal de profundidad más barato que existe y el más importante. Sin
 * ella, una caja dibujada en isométrica no dice a qué altura está: subirla y
 * alejarla se ven exactamente igual, porque las dos cosas la mueven hacia arriba
 * en la pantalla y nada más. Con la sombra en el suelo, esa ambigüedad
 * desaparece de golpe — se ve dónde está apoyada y cuánto flota.
 *
 * Se pinta **antes** que el sólido y sin sombreado de luz: es un calco de la
 * huella, no una sombra proyectada de verdad. Un boceto a mano hace justo eso.
 *
 * Y además es **el tirador**: es por donde se agarra la caja para moverla por el
 * suelo. Ver [tocaLaSombra].
 */
fun sombraEnElSuelo(solido: Solido, vista: Vista, paso: Double = PASO_ISO): List<Pt> =
    huellaEnElSuelo(solido).map { proyectar(it.x, it.y, 0.0, vista, paso) }

/**
 * La huella en el suelo, **en coordenadas del mundo**: la silueta vista desde arriba.
 *
 * De pie y sin volcar es la planta tal cual, así que en una caja siguen siendo sus cuatro
 * esquinas. Volcada deja de serlo —una caja tumbada apoya sobre otra cosa— y entonces la
 * huella es la envolvente de todos sus vértices aplastados contra el suelo, que es
 * literalmente lo que taparía si le diera el sol de arriba.
 *
 * **Al suelo y no a la base.** Con la pieza apoyada a media altura la huella se queda
 * abajo del todo: esa separación es la que dice cuánto flota, que es para lo que existe.
 */
fun huellaEnElSuelo(solido: Solido): List<Pt> {
    val s = solido.normalizado()
    if (s.inclinacion == 0.0) return plantaDe(s).map { enPlanta(s, it.x, it.y) }
    return envolvente(verticesDe(s).map { Pt(it.x, it.y) })
}

/**
 * La envolvente convexa de unos puntos (cadena monótona de Andrew).
 *
 * Se necesita para una sola cosa —la huella de una pieza volcada— y por eso está aquí y
 * no en un módulo aparte: son veinte líneas y traerse una biblioteca por ellas costaría
 * más que escribirlas.
 */
private fun envolvente(puntos: List<Pt>): List<Pt> {
    if (puntos.size < 3) return puntos
    val orden = puntos.sortedWith(compareBy({ it.x }, { it.y }))
    fun cruz(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val abajo = mutableListOf<Pt>()
    for (q in orden) {
        while (abajo.size >= 2 && cruz(abajo[abajo.size - 2], abajo.last(), q) <= 0.0) {
            abajo.removeAt(abajo.size - 1)
        }
        abajo += q
    }
    val arriba = mutableListOf<Pt>()
    for (q in orden.asReversed()) {
        while (arriba.size >= 2 && cruz(arriba[arriba.size - 2], arriba.last(), q) <= 0.0) {
            arriba.removeAt(arriba.size - 1)
        }
        arriba += q
    }
    val hull = abajo.dropLast(1) + arriba.dropLast(1)
    return if (hull.size >= 3) hull else puntos
}

/**
 * ¿Cae el dedo dentro de la sombra?
 *
 * En vez de comprobar el punto contra el rombo de la pantalla, se lleva el dedo
 * al suelo con [desproyectar] y allí se compara contra el rectángulo de la
 * huella. Sale más barato —dos restas y cuatro comparaciones, sin recorrer
 * ningún polígono— y sobre todo **es exacto**, porque en el suelo el rectángulo
 * vuelve a estar alineado con los ejes.
 *
 * Es el gesto de mover el sólido, y por eso engancha por la sombra y no por la
 * caja: agarrando por la caja no habría forma de saber si el arrastre quiere
 * mover o levantar (esa es la ambigüedad que cuenta [desproyectar]). Agarrando
 * por el suelo, el dedo y lo que se mueve están en el mismo plano.
 *
 * [margen] va en unidades del mundo, para poder agarrar una caja muy plana.
 */
fun tocaLaSombra(
    solido: Solido,
    pantalla: Pt,
    vista: Vista,
    paso: Double = PASO_ISO,
    margen: Double = 0.0
): Boolean {
    val s = solido.normalizado()
    // Se lleva el dedo al suelo y allí se compara contra la huella, que ahí es un polígono
    // corriente: la proyección ya está deshecha y el giro y el vuelco vienen puestos en
    // ella. Con la planta redonda o torcida no hay cuatro restas que valgan, así que se
    // recorre — cuatro lados en una caja y veinticuatro en un cilindro, una vez por toque.
    val p = desproyectar(pantalla.x, pantalla.y, vista, paso)
    val huella = huellaEnElSuelo(s)
    if (huella.size < 3) return false
    if (dentroDelPoligono(p, huella)) return true
    return margen > 0.0 && cercaDelContorno(p, huella, margen)
}

/** ¿Cae el punto dentro del polígono? Cruces de una semirrecta, como siempre. */
private fun dentroDelPoligono(p: Pt, poligono: List<Pt>): Boolean {
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

/** ¿Y a menos de [margen] de su borde? Es lo que deja agarrar una pieza muy plana. */
private fun cercaDelContorno(p: Pt, poligono: List<Pt>, margen: Double): Boolean {
    for (i in poligono.indices) {
        val a = poligono[i]
        val b = poligono[(i + 1) % poligono.size]
        val vx = b.x - a.x
        val vy = b.y - a.y
        val largo2 = vx * vx + vy * vy
        val t = if (largo2 <= 0.0) 0.0
        else (((p.x - a.x) * vx + (p.y - a.y) * vy) / largo2).coerceIn(0.0, 1.0)
        if (hypot(p.x - (a.x + vx * t), p.y - (a.y + vy * t)) <= margen) return true
    }
    return false
}

/**
 * Cómo de cerca del que mira está el sólido entero. **Más grande, más delante.**
 *
 * Es la clave con la que se ordenan varias cajas antes de pintarlas. Toma la
 * esquina más adelantada y no el centro: dos cajas de tamaños muy distintos
 * apoyadas una al lado de la otra tienen centros que no dicen cuál tapa a cuál,
 * y su punto más cercano sí.
 */
fun profundidadDe(solido: Solido, vista: Vista): Double {
    val vertices = verticesDe(solido)
    return vertices.maxOf { profundidad(it.x, it.y, it.z, vista) }
}

// -------------------------------------------------------------------------
// El puente con el elemento
// -------------------------------------------------------------------------

/**
 * Lo que mide en pantalla una unidad del mundo **cuando el sólido es un
 * elemento de la escena**: una y no [PASO_ISO].
 *
 * Es la decisión que hace que la huella quepa en `x`/`y`/`width`/`height` sin
 * traducir nada: un píxel de escena es una unidad del mundo, así que estirar la
 * caja por un tirador cambia la huella en la misma proporción y el número que se
 * guarda es el que se ve. [PASO_ISO] sigue valiendo para lo que de verdad es un
 * paso del mundo —el cuadro de la retícula a la que imanta el gesto—, que es
 * cosa distinta del factor de la proyección.
 */
const val PASO_DEL_SOLIDO = 1.0


/**
 * El sólido que describe [e], **con la huella en el origen**.
 *
 * La huella se toma en `(0, 0)` y no en `(e.x, e.y)` porque `e.x` y `e.y` no son
 * un punto del mundo: son el punto de la **escena** donde cae el origen de la
 * huella ya proyectado. Proyectar desde el origen y trasladar después es lo que
 * mantiene esa promesa —y lo que hace que mover el elemento sea sumar, sin
 * volver a pasar por la proyección. Ver [ElementType.SOLIDO].
 */
fun solidoDe(e: Element): Solido = Solido(
    0.0, 0.0, e.width, e.height, e.altura ?: 0.0,
    base = e.cota, giro = e.giroEnPlanta, inclinacion = e.inclinacion, forma = e.formaSolida,
    perfil = if (e.formaSolida == FormaDeSolido.REVOLUCION) e.points.orEmpty() else emptyList(),
    planta = e.planta.orEmpty()
)

/** Los polígonos de [pts] llevados de la proyección a su sitio en la escena. */
private fun aLaEscena(e: Element, pts: List<Pt>): List<Pt> =
    pts.map { Pt(e.x + it.x, e.y + it.y) }

/**
 * Las caras visibles de [e] en coordenadas de escena, de atrás hacia delante.
 *
 * Es la forma en que lo consumen el pintado, la exportación y los contornos: los
 * tres quieren lo mismo —tres polígonos con su claridad— y ninguno tiene por qué
 * saber que por debajo hay una proyección.
 */
fun carasDeElemento(e: Element, vista: Vista): List<CaraDeSolido> =
    ElCuadernoDeLasCaras.de(e, vista)

/**
 * **Las caras ya calculadas, guardadas por pieza y por versión.**
 *
 * Sacar las caras de una pieza es la cuenta más cara del motor plano: una revolución son
 * decenas de anillos de veinticuatro vértices, cada vértice proyectado, cada cara con su
 * área con signo para saber si se ve, y todo ordenado por hondura. Y se pedía **entera en
 * cada fotograma**, tres veces por elemento —una para pintarlo, otra para su contorno y
 * otra para picar con el dedo—, aunque la pieza no se hubiera tocado. Ahí estaba lo de que
 * las herramientas de volumen «van a tirones»: el precio no era pintar, era volver a
 * calcular lo mismo sesenta veces por segundo.
 *
 * La llave es **la pieza, su versión y desde dónde se mira**. El motor sube [Element.version]
 * en cada cambio (ver [Element.touched]), así que una pieza que no se ha tocado devuelve lo
 * de antes y una que sí se recalcula sola: no hay nada que acordarse de invalidar.
 *
 * Cabe poco a propósito. Lo que se pide en un fotograma son las piezas que se ven, que son
 * unas cuantas, y guardar más es guardar lo que ya no se mira. Al llenarse se vacía entero:
 * es un cuaderno de borrador, no un almacén, y una pasada de más cada mucho rato no se nota
 * — lo que se notaba era la de cada fotograma.
 */
private object ElCuadernoDeLasCaras {

    /**
     * **Por lo que es la pieza, no por su número de versión.**
     *
     * La tentación era usar [Element.version], que el motor sube en cada cambio. No vale:
     * hay sitios que reescriben una pieza con `copy` sin pasar por [Element.touched] —la
     * huella de una caja mientras se arrastra, sin ir más lejos—, y con la versión de llave
     * esos cambios devolverían las caras de antes: la pieza se quedaría clavada en la
     * pantalla mientras el dedo la mueve, que es peor que el tirón que esto viene a quitar.
     *
     * [Solido] es un `data class` sacado de la geometría de la pieza, así que comparar dos
     * es comparar de qué pieza se trata. La llave lleva además dónde está y desde dónde se
     * mira, que es lo otro que cambia las caras. Nada que acordarse de invalidar.
     */
    private class Llave(val pieza: Solido, val x: Double, val y: Double, val vista: Vista) {
        override fun hashCode(): Int =
            ((pieza.hashCode() * 31 + x.hashCode()) * 31 + y.hashCode()) * 31 + vista.ordinal

        override fun equals(other: Any?): Boolean =
            other is Llave && other.vista == vista && other.x == x && other.y == y &&
                other.pieza == pieza
    }

    private val apuntado = HashMap<Llave, List<CaraDeSolido>>()

    /**
     * Con llave porque **no todo esto pasa por la pantalla**: exportar a PDF y a SVG saca
     * las caras desde otro hilo, y dos hilos escribiendo en el mismo mapa lo rompen. El
     * cierre dura lo que dura una búsqueda, que al lado de proyectar mil vértices no es
     * nada.
     */
    @Synchronized
    fun de(e: Element, vista: Vista): List<CaraDeSolido> {
        val llave = Llave(solidoDe(e), e.x, e.y, vista)
        apuntado[llave]?.let { return it }
        if (apuntado.size >= CUANTAS_CABEN) apuntado.clear()
        val caras = carasDeSolido(llave.pieza, vista, PASO_DEL_SOLIDO)
            .map { it.copy(poligono = aLaEscena(e, it.poligono)) }
        apuntado[llave] = caras
        return caras
    }

    /** Cuántas piezas se recuerdan. Las que caben en unas cuantas pantallas. */
    private const val CUANTAS_CABEN = 192
}

/** La sombra de [e] en el suelo, en coordenadas de escena. Ver [sombraEnElSuelo]. */
fun sombraDeElemento(e: Element, vista: Vista): List<Pt> =
    aLaEscena(e, sombraEnElSuelo(solidoDe(e), vista, PASO_DEL_SOLIDO))

/**
 * Lo que puede llegar a ocupar [e] en la escena, **mire uno desde donde mire**.
 *
 * La caja del sólido **depende de desde dónde se mire**: girar un cuarto no cambia lo
 * que mide el dibujo, pero sí hacia qué lado del origen cae. Con la vista puesta sale
 * exacta —los ocho vértices proyectados y su caja—, y eso es lo que hace falta para que
 * el recuadro de la selección envuelva lo que se ve y no un rectángulo mucho mayor:
 * antes se devolvía la unión de las cuatro vistas, que llega a sobrar el doble, y
 * encerrar una caja con el dedo obligaba a barrer una zona enorme y vacía.
 *
 * Quien no tenga la vista a mano tiene [envolturaDeSolidoSinVista].
 */
fun envolturaDeSolido(e: Element, vista: Vista): Bounds {
    val caja = cajaDeSolido(solidoDe(e), vista, PASO_DEL_SOLIDO)
    return Bounds(e.x + caja.x1, e.y + caja.y1, e.x + caja.x2, e.y + caja.y2)
}

/**
 * Lo mismo **sin saber desde dónde se mira**: la unión de las cuatro vistas.
 *
 * Sobra sitio —hasta el doble— y eso es exactamente lo que se quiere donde se usa: en un
 * descarte rápido, pasarse no cuesta más que unos píxeles de más, y quedarse corto
 * esconde el dibujo. Teniendo la vista a mano hay que usar [envolturaDeSolido], que da la
 * caja exacta; esto es el respaldo para quien no la tiene.
 */
fun envolturaDeSolidoSinVista(e: Element): Bounds {
    val s = solidoDe(e).normalizado()
    val diagonal = s.ancho + s.fondo
    val h = diagonal * AVANCE_H * PASO_DEL_SOLIDO
    val v = diagonal * AVANCE_V * PASO_DEL_SOLIDO
    // Hasta la cima, no hasta lo que mide: una caja apoyada en alto sube además su cota,
    // y sin contarla el recorte por pantalla la haría desaparecer al bajar la vista.
    val alto = (s.base + s.altura) * PASO_DEL_SOLIDO
    return Bounds(e.x - h, e.y - v - alto, e.x + h, e.y + v)
}

// -------------------------------------------------------------------------
// Las dos fases del gesto
// -------------------------------------------------------------------------

/** La huella que deja un arrastre: dónde se apoya y cuánto mide. */
data class HuellaArrastrada(
    /** Punto de la escena donde cae el origen de la huella. Va a `x`/`y`. */
    val x: Double,
    val y: Double,
    val ancho: Double,
    val fondo: Double
)

/**
 * La huella de arrastrar el dedo de [origen] a [dedo] **sobre el suelo**.
 *
 * Es la primera fase del gesto de la caja, y está aquí y no en el controlador
 * porque es pura cuenta y es donde se puede equivocar uno sin que se note: una
 * huella mal orientada da una caja que se ve perfectamente, solo que sombreada
 * al revés.
 *
 * ## La retícula se ancla en el dedo, no en el origen de la escena
 *
 * El nodo se busca **relativo al punto donde bajó el dedo**, así que ese punto
 * siempre cae exactamente en una esquina de la huella. Anclando la retícula al
 * (0,0) de la escena —que es lo otro que se podría hacer— la caja saltaría al
 * empezar a arrastrar, porque el origen se iría al nodo más cercano y eso puede
 * ser medio cuadro más allá de donde se puso el dedo. Un instrumento que se
 * mueve solo nada más tocarlo se siente roto.
 *
 * Y el nodo lo busca [imanDeReticula], que mide las distancias **en la pantalla
 * y no en el mundo**: en isométrica los dos ejes del suelo no forman ángulo
 * recto, y redondeando cada coordenada por su cuenta el imán engancha
 * visiblemente al nodo equivocado en dos de las cuatro esquinas de cada cuadro.
 *
 * ## Y arrastrando «hacia atrás» sale la misma caja
 *
 * Con el dedo en la dirección negativa de un eje, lo que se ha dibujado es una
 * huella cuyo origen **no** es donde bajó el dedo. Se recoloca aquí —se lleva la
 * esquina al mínimo de los dos y se proyecta ese desplazamiento— y no se deja
 * para [Solido.normalizado]: ahí se arreglaría el dibujo pero `width` y `height`
 * se quedarían en negativo dentro del elemento, y una caja con la anchura
 * negativa rompe todo lo que mide cajas, empezando por la propia selección.
 */
fun huellaArrastrada(
    origen: Pt,
    dedo: Pt,
    vista: Vista,
    celda: Double = PASO_ISO
): HuellaArrastrada {
    val nodo = imanDeReticula(
        Pt(dedo.x - origen.x, dedo.y - origen.y), vista, PASO_DEL_SOLIDO, celda
    )
    val esquina = proyectar(
        min(nodo.x, 0.0), min(nodo.y, 0.0), 0.0, vista, PASO_DEL_SOLIDO
    )
    return HuellaArrastrada(
        x = origen.x + esquina.x,
        y = origen.y + esquina.y,
        ancho = abs(nodo.x),
        fondo = abs(nodo.y)
    )
}

/**
 * Dónde caen en la escena los tiradores de una caja ya hecha.
 *
 * **Existían las dos fases del gesto para dibujarla y ninguna para corregirla.** Una vez
 * fijada, la caja solo se podía mover: para cambiarle el alto o la planta había que
 * borrarla y volver a trazarla, que en un croquis —donde media gracia está en ajustar las
 * proporciones hasta que la cosa se lee— es justo lo que uno acaba haciendo diez veces.
 *
 * Van sobre esquinas de verdad del volumen y cada uno toca una sola cosa:
 *
 * - **La esquina de enfrente** de la huella cambia la planta entera, ancho y fondo a la
 *   vez. Es literalmente el punto donde se soltó el dedo al dibujarla.
 * - **Las dos de los lados**, un eje cada una, para estrechar sin tocar lo demás.
 * - **La de arriba del todo** —encima de la esquina del origen, que en la proyección es
 *   el punto más alto de la caja— sube y baja la altura, que es la segunda fase del
 *   gesto de dibujarla dicha con un tirador.
 */
fun tiradoresDelSolido(e: Element, vista: Vista): List<Pair<HandleType, Pt>> {
    val s = solidoDe(e).normalizado()
    val n = ladosDe(s)
    val v = verticesDe(s).map {
        val p = proyectar(it, vista, PASO_DEL_SOLIDO)
        Pt(e.x + p.x, e.y + p.y)
    }
    // **Sobre vértices de verdad y no sobre esquinas calculadas aparte.** Así valen igual
    // para las cuatro formas —el «de enfrente» de un cilindro es el vértice de enfrente de
    // su anillo— y vienen con el giro y el vuelco puestos sin repetir la cuenta.
    val arriba = (numeroDeAnillos(s) - 1) * n
    return listOf(
        HandleType.SOLIDO_HUELLA to v[n / 2],
        HandleType.SOLIDO_ANCHO to v[1],
        HandleType.SOLIDO_FONDO to v[n - 1],
        // Los dos del alto van sobre la **misma arista vertical**, la de atrás: abajo se
        // sube la pieza entera, arriba se estira. Puestos así se leen sin explicarlos, que
        // es lo que separa un instrumento de una fila de botones.
        HandleType.SOLIDO_COTA to v[0],
        HandleType.SOLIDO_ALTURA to v[arriba]
    )
}

/**
 * El giro que pide el dedo, alrededor del origen de la huella.
 *
 * Se mide **en el suelo y no en la pantalla**: en isométrica los ángulos de la pantalla
 * no son los del mundo, así que girando por lo que se ve la caja avanzaría a saltos
 * distintos según hacia dónde apunte. Deshaciendo la proyección, un cuarto de vuelta del
 * dedo es un cuarto de vuelta de la caja.
 *
 * [escalon] engancha a múltiplos: quince grados por defecto, que es lo que hace que dos
 * piezas torcidas queden torcidas lo mismo.
 */
fun giroPedido(e: Element, dedo: Pt, vista: Vista, escalon: Double = ESCALON_DEL_GIRO): Double {
    val s = solidoDe(e).normalizado()
    // El tirador va a la altura de la base: se descuenta para leer el dedo en el suelo.
    val enElSuelo = desproyectar(
        dedo.x - e.x, dedo.y - e.y + s.base * PASO_DEL_SOLIDO, vista, PASO_DEL_SOLIDO
    )
    // El tirador nace mirando a `+y` de la caja, así que ese es el cero del ángulo.
    val bruto = kotlin.math.atan2(enElSuelo.y, enElSuelo.x) - Math.PI / 2
    if (escalon <= 0.0) return bruto
    return Math.round(bruto / escalon) * escalon
}

/** A cuánto engancha el giro de la planta: quince grados. */
val ESCALON_DEL_GIRO: Double = Math.PI / 12

/**
 * La huella después de arrastrar uno de los tiradores del suelo.
 *
 * Es [huellaArrastrada] con un eje congelado: el tirador de la esquina mueve los dos, y
 * los de los lados solo el suyo —al que no toca se le pasa lo que ya medía, que al ser
 * positivo no desplaza el origen—. Todo lo demás es igual, imán incluido, porque
 * corregir una caja tiene que sentirse como dibujarla.
 */
fun huellaMoldeada(
    origen: Pt,
    dedo: Pt,
    ancho: Double,
    fondo: Double,
    tirador: HandleType,
    vista: Vista,
    celda: Double = PASO_ISO,
    giro: Double = 0.0
): HuellaArrastrada {
    val suelo = Pt(dedo.x - origen.x, dedo.y - origen.y)
    val nodo = if (giro == 0.0) {
        imanDeReticula(suelo, vista, PASO_DEL_SOLIDO, celda)
    } else {
        // **Con la planta girada manda la caja, no la trama.** Los nodos de la retícula
        // del mundo caen donde caigan respecto de una caja torcida, así que enganchar a
        // ellos daría lados de 41 y de 79. Lo que tiene que salir redondo es **lo que
        // mide la caja**, así que se redondea a lo largo de sus propios lados.
        val bruto = desproyectar(suelo.x, suelo.y, vista, PASO_DEL_SOLIDO)
        val enSusEjes = pointRotateRads(bruto, Pt(0.0, 0.0), -giro)
        Pt(redondeaA(enSusEjes.x, celda), redondeaA(enSusEjes.y, celda))
    }
    val nx = if (tirador == HandleType.SOLIDO_FONDO) ancho else nodo.x
    val ny = if (tirador == HandleType.SOLIDO_ANCHO) fondo else nodo.y
    val local = Pt(min(nx, 0.0), min(ny, 0.0))
    val mundo = pointRotateRads(local, Pt(0.0, 0.0), giro)
    val esquina = proyectar(mundo.x, mundo.y, 0.0, vista, PASO_DEL_SOLIDO)
    return HuellaArrastrada(
        x = origen.x + esquina.x,
        y = origen.y + esquina.y,
        ancho = abs(nx),
        fondo = abs(ny)
    )
}

/** Al múltiplo de [celda] más cercano, aguantando una celda absurda. */
private fun redondeaA(v: Double, celda: Double): Double =
    if (celda <= 0.0 || !celda.isFinite() || !v.isFinite()) v
    else (v / celda).roundToInt() * celda

/**
 * La altura, imantada al mismo cuadro que la huella y nunca negativa.
 *
 * Se imanta por lo mismo que se imanta el suelo: en un croquis conceptual lo que
 * se quiere decir es «esta es el doble de alta que aquella», y eso solo se lee si
 * las dos miden un número redondo de cuadros. A pulso salen 41 y 79 y la relación
 * se pierde.
 *
 * Y no baja de cero porque una caja colgando por debajo del suelo no es algo que
 * nadie quiera dibujar: es lo que sale de pasarse arrastrando hacia abajo.
 */
fun alturaImantada(bruta: Double, celda: Double = PASO_ISO): Double {
    if (celda <= 0.0 || !celda.isFinite() || !bruta.isFinite()) return max(bruta, 0.0)
    return max((bruta / celda).roundToInt() * celda, 0.0)
}

/**
 * Las doce aristas de la caja, por índice de vértice. Ver [verticesDe].
 */
private fun aristasDe(s: Solido): List<Pair<Int, Int>> {
    val n = ladosDe(s)
    val anillos = numeroDeAnillos(s)
    val salida = mutableListOf<Pair<Int, Int>>()
    for (nivel in 0 until anillos) {
        val base = nivel * n
        for (k in 0 until n) {
            salida += (base + k) to (base + (k + 1) % n)
            if (nivel < anillos - 1) salida += (base + k) to (base + n + k)
        }
    }
    return salida
}

private fun claveDeArista(a: Int, b: Int): Int = min(a, b) * 65536 + max(a, b)

/**
 * **Las aristas que quedan detrás**, para dibujarlas a trazos.
 *
 * Es el recurso de dibujo técnico de toda la vida: el bulto se lee mejor cuando se
 * insinúa lo que tapa, y sobre todo se ve **dónde está la esquina de atrás** — que en una
 * caja seleccionada es justo donde viven dos de sus tiradores, el de la cota y el del
 * alto. Sin ellas, esos dos parecen flotar sobre nada.
 *
 * Salen de restar: son las doce menos las que pertenecen a alguna cara vista. No hay que
 * decidir nada más, porque el reparto de caras ya sabe cuáles se ven (ver [carasDeSolido])
 * y una arista se ve si se ve alguna de las dos caras que la comparten.
 *
 * Las de largo cero se caen: en una cuña dos vértices bajan hasta la base y tres aristas
 * se quedan sin longitud, y pintarlas sería un punto suelto en medio del faldón.
 */
fun aristasOcultasDeSolido(
    solido: Solido,
    vista: Vista,
    paso: Double = PASO_ISO
): List<Pair<Pt, Pt>> {
    val caras = carasDeSolido(solido, vista, paso, soloVisibles = false)
    val vistas = HashSet<Int>()
    for (c in caras) {
        if (!c.visible) continue
        val idx = c.cara.esquinas
        for (i in idx.indices) {
            vistas += claveDeArista(idx[i], idx[(i + 1) % idx.size])
        }
    }
    val v = verticesDe(solido).map { proyectar(it, vista, paso) }
    return aristasDe(solido.normalizado())
        .filter { claveDeArista(it.first, it.second) !in vistas }
        .map { v[it.first] to v[it.second] }
        .filter { hypot(it.second.x - it.first.x, it.second.y - it.first.y) > MINIMA_ARISTA }
}

/** Por debajo de esto una arista no es una arista: es un vértice repetido. */
private const val MINIMA_ARISTA = 0.5

/** Lo mismo en coordenadas de escena. Ver [aristasOcultasDeSolido]. */
fun aristasOcultasDeElemento(e: Element, vista: Vista): List<Pair<Pt, Pt>> =
    aristasOcultasDeSolido(solidoDe(e), vista, PASO_DEL_SOLIDO).map { (a, b) ->
        Pt(e.x + a.x, e.y + a.y) to Pt(e.x + b.x, e.y + b.y)
    }

/**
 * A dónde va la caja que se mueve cuando engancha con otra.
 *
 * [x] e [y] son el sitio del **origen de la huella en la escena**, ya proyectado, que es
 * lo que guarda el elemento; [cota] la altura a la que pasa a apoyar; y [punto], dónde se
 * ha pegado, para poder señalarlo mientras dura el gesto.
 */
data class EngancheDeSolido(
    val x: Double,
    val y: Double,
    val cota: Double,
    val punto: Pt
)

/**
 * Por dónde puede engancharse una pieza: **sus vértices y los centros de tapa y base**.
 *
 * Los vértices son lo evidente —apoyar una caja en la esquina de otra— pero se quedaban
 * cortos en el caso más común de todos: dejar una pieza **centrada** encima de otra, que
 * con esquinas solo sale si las dos miden lo mismo. Con los dos centros en la lista, una
 * caja pequeña se posa en mitad de la tapa de una grande de un tirón.
 *
 * Valen a la vez de sitios donde agarrar y de sitios donde soltar, y por eso salen de la
 * misma función: engancha el punto de la que se mueve que más cerca esté, en la pantalla,
 * de un punto de otra.
 */
private fun puntosDeEnganche(s: Solido, origen: Pt): List<Pt3> {
    val v = verticesDe(s).map { Pt3(origen.x + it.x, origen.y + it.y, it.z) }
    val n = ladosDe(s)
    fun centro(desde: Int): Pt3 {
        val trozo = v.subList(desde, desde + n)
        return Pt3(
            trozo.sumOf { it.x } / n, trozo.sumOf { it.y } / n, trozo.sumOf { it.z } / n
        )
    }
    return v + centro(0) + centro((numeroDeAnillos(s) - 1) * n)
}

/**
 * El imán entre cajas: **una se apoya en los vértices de otra**.
 *
 * Es lo que convierte dos volúmenes sueltos en algo montado. Sin él, apilar era acertar a
 * ojo dónde cae la tapa de la de abajo —y en isométrica esa puntería no existe, porque
 * subir y alejarse se ven exactamente igual—; con él, arrimar una caja a otra la deja
 * apoyada, a hueso, con la cota puesta.
 *
 * ## Se comparan las esquinas, y se miden en la pantalla
 *
 * Los candidatos son **los ocho vértices** de cada caja vecina, que salen de [verticesDe]
 * y por tanto ya llevan su cota, su giro y su forma —de una cuña se ofrecen los vértices
 * de la cuña, no los de la caja que fue—. Contra ellos se prueban las cuatro esquinas de
 * la planta de la que se mueve, a la altura a la que apoya.
 *
 * La distancia se mide **en la pantalla y no en el mundo**, por lo mismo que la mide así
 * [imanDeReticula]: los dos ejes del suelo no forman ángulo recto, y en el mundo dos
 * puntos que en la pantalla están pegados pueden estar lejísimos. Uno engancha a lo que
 * ve, no a lo que hay.
 *
 * Devuelve null si no hay nada cerca: el imán tira cuando estás cerca y no ata cuando no
 * lo estás.
 */
fun imanEntreSolidos(
    movido: Element,
    otros: List<Element>,
    vista: Vista,
    umbral: Double
): EngancheDeSolido? {
    if (!movido.isSolido) return null
    val s = solidoDe(movido).normalizado()
    val origen = desproyectar(movido.x, movido.y, vista, PASO_DEL_SOLIDO)
    val mios = puntosDeEnganche(s, origen)

    var mejor: EngancheDeSolido? = null
    var mejorDistancia = umbral * umbral
    for (o in otros) {
        if (!o.isSolido || o.isDeleted || o.id == movido.id) continue
        val so = solidoDe(o).normalizado()
        val oo = desproyectar(o.x, o.y, vista, PASO_DEL_SOLIDO)
        for (destino in puntosDeEnganche(so, oo)) {
            val alli = proyectar(destino.x, destino.y, destino.z, vista, PASO_DEL_SOLIDO)
            for (mio in mios) {
                val ahora = proyectar(mio.x, mio.y, mio.z, vista, PASO_DEL_SOLIDO)
                val dx = alli.x - ahora.x
                val dy = alli.y - ahora.y
                val d = dx * dx + dy * dy
                if (d >= mejorDistancia) continue
                mejorDistancia = d
                val nuevo = proyectar(
                    origen.x + (destino.x - mio.x), origen.y + (destino.y - mio.y),
                    0.0, vista, PASO_DEL_SOLIDO
                )
                // **También en el alto.** El punto que engancha ya no es forzosamente uno
                // de la base: colgando una pieza por su tapa del suelo de otra, lo que
                // baja es la cota en lo que ese punto tenga que bajar.
                mejor = EngancheDeSolido(
                    nuevo.x, nuevo.y, kotlin.math.max(s.base + (destino.z - mio.z), 0.0), alli
                )
            }
        }
    }
    return mejor
}

/**
 * **Extruir**: levantar una figura plana y convertirla en volumen.
 *
 * Es la forma natural de llegar al 3D desde donde uno ya está, y desde esta versión es la
 * **única**: se acabaron las piezas de catálogo. Dibujas la planta con las herramientas de
 * siempre y la levantas — un cuadrado da un cubo, un triángulo da una barra triangular, un
 * óvalo da un cilindro y un garabato cerrado da lo que dé. Ninguna de esas cosas está
 * programada por separado: todas son la misma, levantar un contorno.
 *
 * El contorno sale de [contornosDe], que es el mismo del que ya se fiaban el bote de
 * pintura y el recortar, así que una figura se levanta exactamente por donde se rellena.
 * Se guarda en tanto por uno para que estirar la pieza después estire la planta con ella.
 *
 * La pieza queda **donde estaba la figura**: se coloca el origen de la huella de manera
 * que el centro de lo dibujado no se mueva de la pantalla.
 */
fun extruido(e: Element, vista: Vista, alto: Double? = null): Element? {
    if (e.isSolido || e.type == ElementType.FRAME) return null
    // **Sin el redondeo de las esquinas.** Es un adorno de la figura plana —la esquina
    // matada de un rectángulo dibujado a mano— y no forma del volumen: dejándolo puesto,
    // el contorno llega muestreado en curva y un cubo salía con nueve lados en vez de
    // cuatro. Quitándolo, el contorno de un polígono son sus vértices y nada más.
    // **El óvalo se toma exacto y no muestreado.** Su contorno llega como una tira de
    // puntos cada pocos píxeles, y simplificarla deja un polígono con los lados
    // desiguales: un círculo levantado salía abollado. Su planta es una elipse y se sabe
    // escribir, así que se escribe.
    if (e.type == ElementType.ELLIPSE) return extruidoDeOvalo(e, vista, alto)

    val contorno = contornosDe(e.copy(roundness = null), vista = vista)
        .firstOrNull { it.cerrado && it.puntos.size >= 3 } ?: return null
    // **Primero se le quitan los puntos que no aportan forma.** El contorno viene
    // muestreado cada pocos píxeles —hace falta para las curvas y para las esquinas
    // redondeadas— así que un rectángulo llega con treinta puntos y se levantaría como un
    // polígono de treinta lados en vez de como un cubo. Con la tolerancia puesta en un
    // pellizco de su diagonal, un rectángulo vuelve a ser cuatro esquinas, un triángulo
    // tres, y una elipse se queda con los que de verdad la dibujan.
    // Y sin el punto de cierre repetido: el anillo se cierra solo, y contarlo dos veces
    // le da al volumen un lado de ancho cero — un triángulo salía con cuatro caras.
    val bruto = contorno.puntos.let {
        if (it.size > 3 && hypot(it.last().x - it.first().x, it.last().y - it.first().y) < 1e-6) {
            it.dropLast(1)
        } else it
    }
    val diagonal = hypot(
        bruto.maxOf { it.x } - bruto.minOf { it.x },
        bruto.maxOf { it.y } - bruto.minOf { it.y }
    )
    val puntos = aclaradoCiclico(
        simplificar(bruto, diagonal * TOLERANCIA_DE_LA_PLANTA), MAXIMOS_LADOS
    )
    if (puntos.size < 3) return null
    val minX = puntos.minOf { it.x }
    val maxX = puntos.maxOf { it.x }
    val minY = puntos.minOf { it.y }
    val maxY = puntos.maxOf { it.y }
    val ancho = maxX - minX
    val fondo = maxY - minY
    if (ancho <= 0.0 || fondo <= 0.0) return null

    // En tanto por uno de su caja, y en el sentido que espera el reparto de caras: si el
    // contorno viene al revés, todas las caras saldrían del revés y se pintarían las tres
    // de detrás. Es el mismo cuidado que se toma [Solido.normalizado] con la huella.
    var planta = puntos.map { Pt((it.x - minX) / ancho, (it.y - minY) / fondo) }
    if (areaConSigno(planta) < 0.0) planta = planta.reversed()

    val altura = alturaImantada(alto ?: min(ancho, fondo))
    val desvio = proyectar(ancho / 2, fondo / 2, 0.0, vista, PASO_DEL_SOLIDO)
    return e.copy(
        id = randomId(),
        seed = randomSeed(),
        type = ElementType.SOLIDO,
        x = (minX + maxX) / 2 - desvio.x,
        y = (minY + maxY) / 2 - desvio.y,
        width = ancho,
        height = fondo,
        altura = altura,
        cota = 0.0,
        giroEnPlanta = 0.0,
        inclinacion = 0.0,
        formaSolida = FormaDeSolido.EXTRUSION,
        planta = planta,
        points = null,
        roundness = null
    ).touched()
}

/**
 * **El aro que forman varias rayas sueltas**, si es que lo forman.
 *
 * Así se dibuja en cualquier programa de ingeniería: la planta no es una figura cerrada de
 * catálogo, son cuatro rectas y dos arcos que se tocan en las puntas. Antes eso no se
 * podía levantar —se pedía un contorno cerrado y cuatro rayas sueltas no lo son, aunque a
 * la vista lo parezcan—, así que había que redibujar la planta con la herramienta de
 * rectángulo para poder extruirla, que es exactamente el trabajo que uno venía a evitar.
 *
 * Se recorre encadenando por las puntas: se arranca en una, se busca la raya que empieza o
 * acaba ahí mismo, se salta a su otra punta y se sigue. Si al gastarlas todas se vuelve al
 * principio, hay aro; si sobra alguna o el camino se abre, no lo hay y no se inventa uno.
 *
 * [tolerancia] es lo cerca que tienen que estar dos puntas para contar como la misma. A
 * mano nadie cierra un contorno al píxel, y exigirlo dejaría la herramienta inservible; es
 * el mismo criterio con el que el bote de pintura decide si un hueco está cerrado.
 */
fun aroDeRayas(rayas: List<Element>, tolerancia: Double = TOLERANCIA_DEL_ARO): List<Pt>? {
    val tramos = rayas
        .filter { !it.isDeleted && (it.isLinear || it.isFreeDraw) }
        .map { absolutePoints(it) }
        .filter { it.size >= 2 }
    if (tramos.size < 2) return null

    val pendientes = tramos.toMutableList()
    val camino = pendientes.removeAt(0).toMutableList()

    while (pendientes.isNotEmpty()) {
        val punta = camino.last()
        val siguiente = pendientes.indexOfFirst {
            cerca(it.first(), punta, tolerancia) || cerca(it.last(), punta, tolerancia)
        }
        // Una raya que no engancha con ninguna punta es una raya suelta dentro de la
        // selección: entonces esto no era un contorno y no hay nada que levantar.
        if (siguiente < 0) return null
        val tramo = pendientes.removeAt(siguiente)
        val enOrden = if (cerca(tramo.first(), punta, tolerancia)) tramo else tramo.asReversed()
        camino += enOrden.drop(1)
    }

    if (!cerca(camino.first(), camino.last(), tolerancia)) return null
    // La punta repetida del cierre se quita: el anillo se cierra solo, y contarla dos
    // veces le daría al volumen un lado de ancho cero.
    return camino.dropLast(1).takeIf { it.size >= 3 }
}

private fun cerca(a: Pt, b: Pt, tolerancia: Double): Boolean =
    hypot(b.x - a.x, b.y - a.y) <= tolerancia

/** Lo cerca que tienen que estar dos puntas para contar como la misma. */
const val TOLERANCIA_DEL_ARO = 12.0

/**
 * Levanta el aro que forman varias rayas. Ver [aroDeRayas] y [extruido].
 *
 * Sale del mismo sitio que levantar una figura: lo único que cambia es de dónde viene el
 * contorno. Por eso la pieza se construye llamando a la de siempre con un elemento que
 * lleva ese contorno puesto — y no repitiendo aquí la cuenta de la planta, que es donde se
 * colarían las diferencias.
 */
fun extruidoDeRayas(rayas: List<Element>, vista: Vista, alto: Double? = null): Element? {
    val aro = aroDeRayas(rayas) ?: return null
    val patron = rayas.firstOrNull() ?: return null
    val minX = aro.minOf { it.x }
    val maxX = aro.maxOf { it.x }
    val minY = aro.minOf { it.y }
    val maxY = aro.maxOf { it.y }
    val ancho = maxX - minX
    val fondo = maxY - minY
    if (ancho <= 0.0 || fondo <= 0.0) return null

    val puntos = aclaradoCiclico(aro, MAXIMOS_LADOS)
    var planta = puntos.map { Pt((it.x - minX) / ancho, (it.y - minY) / fondo) }
    if (areaConSigno(planta) < 0.0) planta = planta.reversed()

    val desvio = proyectar(ancho / 2, fondo / 2, 0.0, vista, PASO_DEL_SOLIDO)
    return patron.copy(
        id = randomId(),
        seed = randomSeed(),
        type = ElementType.SOLIDO,
        x = (minX + maxX) / 2 - desvio.x,
        y = (minY + maxY) / 2 - desvio.y,
        width = ancho,
        height = fondo,
        altura = alturaImantada(alto ?: min(ancho, fondo)),
        cota = 0.0,
        giroEnPlanta = 0.0,
        inclinacion = 0.0,
        formaSolida = FormaDeSolido.EXTRUSION,
        planta = planta,
        points = null,
        roundness = null,
        startBinding = null,
        endBinding = null
    ).touched()
}

/**
 * El óvalo levantado: **su planta es la elipse, exacta**.
 *
 * No pasa por el contorno muestreado porque no hace falta y porque sale peor: los puntos
 * de un muestreo caen cada tantos píxeles de recorrido y, simplificados, dan un polígono
 * de lados desiguales — un círculo abollado. Repartiendo el ángulo por igual, los lados
 * salen todos iguales y a la vista es un círculo.
 */
private fun extruidoDeOvalo(e: Element, vista: Vista, alto: Double?): Element? {
    val c = getElementAbsoluteCoords(e)
    val ancho = c.x2 - c.x1
    val fondo = c.y2 - c.y1
    if (ancho <= 0.0 || fondo <= 0.0) return null
    val planta = (0 until LADOS_DEL_CILINDRO).map { k ->
        val t = -Math.PI / 2 + 2 * Math.PI * k / LADOS_DEL_CILINDRO
        Pt(0.5 + 0.5 * cos(t), 0.5 + 0.5 * sin(t))
    }
    val altura = alturaImantada(alto ?: min(ancho, fondo))
    val desvio = proyectar(ancho / 2, fondo / 2, 0.0, vista, PASO_DEL_SOLIDO)
    return e.copy(
        id = randomId(),
        seed = randomSeed(),
        type = ElementType.SOLIDO,
        x = c.cx - desvio.x,
        y = c.cy - desvio.y,
        width = ancho,
        height = fondo,
        altura = altura,
        cota = 0.0,
        giroEnPlanta = 0.0,
        inclinacion = 0.0,
        formaSolida = FormaDeSolido.EXTRUSION,
        planta = planta,
        points = null,
        roundness = null
    ).touched()
}

/**
 * **Revolución**: una figura dada la vuelta alrededor de una raya.
 *
 * Se seleccionan las dos cosas —lo que se quiere tornear y el eje— y sale el cuerpo. Es
 * como se hace en un torno de verdad y como se dibuja en un croquis: media circunferencia
 * junto a una raya es una esfera, un rectángulo junto a una raya es un cilindro, un perfil
 * de jarrón es un jarrón. Sin la raya no hay forma de saber alrededor de qué se gira, y
 * adivinarla —el borde izquierdo del dibujo, que es lo que se hacía antes— acierta una de
 * cada tres veces.
 *
 * ## Cómo se lee el contorno
 *
 * Cada punto del contorno se mide **respecto del eje**: cuánto avanza a lo largo de él
 * —eso es la altura— y cuánto se separa —eso es el radio—. De todos los que caen a la
 * misma altura manda **el más lejano**, que es la silueta que barre el giro: por eso media
 * circunferencia da una esfera llena y no una cáscara.
 *
 * El cuerpo nace **de pie**, con su eje en la vertical del mundo, aunque la raya se
 * dibujara torcida. Es lo que uno quiere en un croquis —las piezas se apoyan— y volcarlo
 * después es un anillo de los de orientar.
 */
fun revolucionado(figura: Element, eje: Element?, vista: Vista): Element? {
    val puntos = when {
        figura.isLinear || figura.isFreeDraw -> absolutePoints(figura)
        // **Sin el redondeo de las esquinas**, por lo mismo que al levantar una figura (ver
        // [extruido]): la esquina matada de un rectángulo es un adorno de la figura plana, no
        // una forma del cuerpo. Dejándolo puesto, el contorno llega en curva por las cuatro
        // esquinas y un rectángulo pegado a un eje salía como un cilindro con las dos bocas
        // achaflanadas en vez de como un cilindro.
        else -> contornosDe(figura.copy(roundness = null), vista = vista)
            .firstOrNull()?.puntos ?: return null
    }
    if (puntos.size < 2) return null

    val recta = eje?.let { absolutePoints(it) }?.takeIf { it.size >= 2 }
    val (origen, direccion) = if (recta != null) {
        val a = recta.first()
        val b = recta.last()
        val largo = hypot(b.x - a.x, b.y - a.y)
        if (largo <= 0.0) return null
        a to Pt((b.x - a.x) / largo, (b.y - a.y) / largo)
    } else {
        // Sin eje puesto, el de siempre: la vertical por el borde izquierdo del dibujo.
        // Se conserva para que revolucionar un perfil suelto siga funcionando.
        Pt(puntos.minOf { it.x }, puntos.minOf { it.y }) to Pt(0.0, 1.0)
    }

    // A lo largo del eje y a lo ancho de él, **con signo**: de qué lado del eje cae cada
    // punto es lo que hace falta para saber cuál de los dos se está torneando.
    val medidos = puntos.map { p ->
        val dx = p.x - origen.x
        val dy = p.y - origen.y
        val alo = dx * direccion.x + dy * direccion.y
        val ancho = dx * -direccion.y + dy * direccion.x
        Pt(ancho, alo)
    }

    // **Se tornea el lado que se dibujó, no los dos superpuestos.**
    //
    // Iba en valor absoluto —los dos lados barren lo mismo al girar—, y eso vale cuando el
    // perfil está a un lado del eje, que es lo normal. Cuando cruza el eje no: un perfil
    // trazado un poco por encima de la raya, con la mano temblando y algún punto colándose
    // al otro lado, salía con esos puntos doblados sobre el lado bueno y la pieza cogía
    // radio donde no lo tenía. Manda el lado que lleva la mayor parte del dibujo; y con el
    // perfil repartido a partes iguales entre los dos —un perfil dibujado simétrico— se
    // usan los dos, que es lo que quiso decir quien lo dibujó así.
    val aUnLado = medidos.filter { it.x > 0 }.sumOf { it.x }
    val alOtro = medidos.filter { it.x < 0 }.sumOf { -it.x }
    val total = aUnLado + alOtro
    val soloUnLado = total > 1e-9 && maxOf(aUnLado, alOtro) / total >= LADO_QUE_MANDA
    val elLadoBueno = if (aUnLado >= alOtro) 1.0 else -1.0
    val silueta = medidos.map { m ->
        // Del lado que manda se coge su distancia al eje; del otro, nada: ese punto está en
        // el eje a efectos de tornear, y es lo que hace que un perfil que se pasa un pelo no
        // invente radio.
        val r = if (!soloUnLado) abs(m.x) else (m.x * elLadoBueno).coerceAtLeast(0.0)
        Pt(r, m.y)
    }

    val hMin = silueta.minOf { it.y }
    val hMax = silueta.maxOf { it.y }
    val rMax = silueta.maxOf { it.x }
    val alto = hMax - hMin
    if (alto <= 0.0 || rMax <= 0.0) return null

    // **Por bandas de altura, el punto más lejano del eje: eso es la silueta que barre.**
    //
    // Hacen falta bandas y no los puntos tal cual porque un perfil cerrado —media
    // circunferencia, el contorno de un jarrón— pasa dos veces por cada altura, una por
    // fuera y otra por dentro, y lo que barre el giro es la de fuera.
    //
    // Muchas más de las que se van a guardar: aquí se mide, y el aclarado de después se
    // queda con las que dicen algo. Midiendo con dieciséis, un hombro de un escalón caía
    // dentro de una banda y salía redondeado; midiendo fino y aclarando por forma, el
    // hombro se conserva **y** la pieza acaba con menos anillos que antes. Ver [aclarado].
    val bandas = ANILLOS_AL_MEDIR
    val radios = DoubleArray(bandas)
    val hay = BooleanArray(bandas)
    val paso = alto / (bandas - 1)

    fun apuntar(r: Double, h: Double) {
        val i = (((h - hMin) / alto) * (bandas - 1)).roundToInt().coerceIn(0, bandas - 1)
        radios[i] = max(radios[i], r)
        hay[i] = true
    }

    // **Se miden los tramos, no los vértices.**
    //
    // Un perfil de ingeniería se traza con la herramienta de rayas, así que llega como seis
    // vértices y no como doscientos puntos: la pared de una arandela son **dos** puntos, uno
    // arriba y otro abajo, y por en medio no hay nada que medir. Apuntando solo los
    // vértices, todas las bandas de esa pared se quedaban sin dato y se rellenaban
    // interpolando entre el hombro y la base — o sea, **el escalón salía cono**. Es el fallo
    // de «hago una arandela y me sale un embudo».
    //
    // Recorriendo el tramo de banda en banda, la pared apunta su radio en todas las suyas y
    // el hombro se queda en la única que le toca: entre las dos hay un salto, que es lo que
    // es un escalón.
    for ((a, b) in silueta.zipWithNext()) {
        apuntar(a.x, a.y)
        val cuantos = (abs(b.y - a.y) / paso).toInt()
        for (k in 1..cuantos) {
            val t = k.toDouble() / (cuantos + 1)
            apuntar(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }
    silueta.lastOrNull()?.let { apuntar(it.x, it.y) }

    // **Los huecos se interpolan; los ceros de verdad se quedan.**
    //
    // Se rellenaban copiando de la banda vecina y **sin distinguir la banda por la que no
    // pasó nadie de la que pasó por el eje**, que son cosas contrarias: la primera es un
    // hueco del muestreo y la segunda es la punta de la pieza. Con eso, la punta de un cono
    // y los polos de una esfera heredaban el radio de al lado, así que **no había forma de
    // tornear un cono ni una esfera**: los dos salían con la punta cortada en plano. Ahora
    // se anota por dónde pasó de verdad el dibujo, y solo se rellena lo que nadie tocó.
    var anterior = -1
    for (i in 0 until bandas) {
        if (!hay[i]) continue
        if (anterior >= 0 && i - anterior > 1) {
            // Entre dos bandas con dato, una recta: copiar de la de al lado deja escalones
            // que el perfil no tiene.
            for (k in anterior + 1 until i) {
                val t = (k - anterior).toDouble() / (i - anterior)
                radios[k] = radios[anterior] + (radios[i] - radios[anterior]) * t
            }
        }
        anterior = i
    }
    // Y las puntas sin dato heredan de la primera y la última que sí lo tienen: por fuera
    // del dibujo no hay nada que interpolar.
    val primera = (0 until bandas).firstOrNull { hay[it] } ?: return null
    val ultima = (0 until bandas).last { hay[it] }
    for (i in 0 until primera) radios[i] = radios[primera]
    for (i in ultima + 1 until bandas) radios[i] = radios[ultima]

    // **Y las dos puntas valen lo que valen de verdad, no lo que valga su banda.**
    //
    // Una banda es un trocito de altura y se queda con el radio mayor de todo lo que caiga
    // dentro. En los extremos eso redondea la punta: cerca del polo de una esfera el radio
    // crece deprisa, así que la banda de arriba se lleva un radio de un puñado de unidades y
    // la esfera sale con la coronilla cortada en plano. En los extremos no hay nada por
    // encima con lo que promediar: manda el punto que está justo ahí.
    val casiNada = paso * 0.25
    silueta.filter { it.y <= hMin + casiNada }.maxOfOrNull { it.x }?.let { radios[0] = it }
    silueta.filter { it.y >= hMax - casiNada }.maxOfOrNull { it.x }
        ?.let { radios[bandas - 1] = it }

    // En la pantalla la `y` crece hacia abajo y en el mundo la altura crece hacia arriba,
    // así que el extremo más bajo del dibujo es el pie de la pieza.
    // Y se guarda **ya aclarado**: las sesenta y cuatro bandas son para medir fino, no para
    // llevarlas en el archivo. Un cilindro se queda en dos puntos y una arandela en los de
    // sus escalones. Ver [aclarado].
    val perfil = aclarado(
        (0 until bandas).map {
            Pt(radios[bandas - 1 - it] / rMax, it.toDouble() / (bandas - 1))
        },
        MAXIMOS_ANILLOS
    )

    val diametro = rMax * 2
    val centro = Pt(
        (puntos.minOf { it.x } + puntos.maxOf { it.x }) / 2,
        (puntos.minOf { it.y } + puntos.maxOf { it.y }) / 2
    )
    val desvio = proyectar(diametro / 2, diametro / 2, 0.0, vista, PASO_DEL_SOLIDO)
    return figura.copy(
        id = randomId(),
        seed = randomSeed(),
        type = ElementType.SOLIDO,
        x = centro.x - desvio.x,
        y = centro.y - desvio.y,
        width = diametro,
        height = diametro,
        // **La altura, la que se dibujó, sin imantar a la retícula.**
        //
        // Se redondeaba al paso de la isométrica, y en una pieza de revolución eso no es
        // colocar: es **cambiarle la proporción**. Un perfil de ciento treinta y siete de
        // alto por cuarenta de radio salía de ciento cincuenta, así que la pieza torneada no
        // era la que se había dibujado, y ninguna cota medida sobre el perfil valía ya. Se
        // imanta lo que se coloca a ojo —la altura de una caja que se levanta con el dedo—,
        // no lo que sale de una geometría que alguien ha trazado. Ver [alturaImantada].
        altura = alto,
        cota = 0.0,
        giroEnPlanta = 0.0,
        inclinacion = 0.0,
        formaSolida = FormaDeSolido.REVOLUCION,
        planta = null,
        points = perfil,
        roundness = null
    ).touched()
}

/**
 * Un contorno cerrado con como mucho [tope] puntos, repartidos por igual.
 *
 * Cada punto de la planta es una cara del volumen. Un garabato cerrado trae trescientos, y
 * eso son trescientas caras que repartir, ordenar y pintar en cada fotograma para dibujar
 * una silueta que con veinticuatro ya no se distingue de la de trescientas.
 */
private fun aclaradoCiclico(puntos: List<Pt>, tope: Int): List<Pt> {
    if (puntos.size <= tope) return puntos
    // **Se quitan los puntos que no cambian el contorno**, no uno de cada tantos.
    //
    // Por lo mismo que en el perfil del torno (ver [aclarado]): en una planta de ingeniería
    // toda la información está en los vértices, y coger uno de cada tres se lleva la mitad
    // de las esquinas y deja puntos de sobra repartidos por los lados rectos. Una placa con
    // una muesca salía con la muesca redondeada.
    //
    // Un anillo no tiene puntas de las que agarrar el aclarado, así que se parte por los dos
    // puntos más separados entre sí —que en una figura cerrada son siempre dos vértices— y
    // se aclara cada mitad. Es el apaño de siempre para cerrar un algoritmo abierto.
    val a = 0
    val b = puntos.indices.maxByOrNull {
        hypot(puntos[it].x - puntos[a].x, puntos[it].y - puntos[a].y)
    } ?: return puntos
    if (b <= a) return puntos

    var tolerancia = TOLERANCIA_DEL_PERFIL * hypot(
        puntos.maxOf { it.x } - puntos.minOf { it.x },
        puntos.maxOf { it.y } - puntos.minOf { it.y }
    )
    var salida = puntos
    var vueltas = 0
    while (salida.size > tope && vueltas < VUELTAS_PARA_AJUSTAR) {
        val media = simplificado(puntos.subList(a, b + 1), tolerancia)
        val otra = simplificado(puntos.subList(b, puntos.size) + puntos[a], tolerancia)
        // Las dos mitades comparten sus dos extremos: se cuentan una vez.
        salida = media + otra.drop(1).dropLast(1)
        tolerancia *= 2
        vueltas++
    }
    if (salida.size <= tope && salida.size >= 3) return salida
    // Y si ni así cabe, se reparte por igual lo que quede: un tope es un tope.
    val paso = puntos.size.toDouble() / tope
    return (0 until tope).map { puntos[(it * paso).toInt().coerceIn(puntos.indices)] }
}

/** Cuántos lados como mucho tiene la planta de una extrusión. Ver [aclaradoCiclico]. */
const val MAXIMOS_LADOS = 24

/**
 * Cuánto se puede despegar la planta del contorno del que sale, en tanto por uno de su
 * diagonal.
 *
 * Un cuatro por ciento es lo que borra el redondeo de las esquinas de un rectángulo —que
 * es adorno de la figura plana y no forma del volumen— sin comerse un vértice de verdad.
 * Por debajo, un cubo sale con las esquinas matadas en veinte lados; por encima, un
 * triángulo empieza a perder puntas.
 */
private const val TOLERANCIA_DE_LA_PLANTA = 0.04

/** Hacia dónde se repite una caja. */
enum class EjeDeRepeticion { ANCHO, FONDO, ALTO }

/**
 * La caja repetida **pegada a sí misma**, tantas veces como se pida.
 *
 * En un croquis conceptual «tres módulos apilados» o «cinco crujías iguales» es el dibujo
 * entero, y a mano salían cinco cajas que no medían lo mismo ni estaban a la misma
 * distancia — con lo cual dejaban de decir «iguales», que era justo lo único que había
 * que decir. Cada copia va exactamente a un ancho, un fondo o una altura de la anterior,
 * en la dirección de la propia caja: con la planta girada, la fila sale torcida con ella.
 *
 * Devuelve **solo las nuevas**, con su identidad propia: la original se queda donde está.
 */
fun repetirSolido(
    e: Element,
    veces: Int,
    eje: EjeDeRepeticion,
    vista: Vista
): List<Element> {
    if (!e.isSolido || veces <= 0) return emptyList()
    val s = solidoDe(e).normalizado()
    val origen = desproyectar(e.x, e.y, vista, PASO_DEL_SOLIDO)
    // El paso, en los ejes de la propia caja y luego girado como esté ella.
    // El paso va en los ejes **de la propia pieza**, con su giro y su vuelco puestos: una
    // fila de cajas torcidas sale torcida, y una de cajas volcadas sube en la dirección
    // en la que estén volcadas. Sale de restar dos puntos del mismo sistema.
    val cero = aMundoDesdeCero(s, 0.0, 0.0, 0.0)
    val uno = when (eje) {
        EjeDeRepeticion.ANCHO -> aMundoDesdeCero(s, s.ancho, 0.0, 0.0)
        EjeDeRepeticion.FONDO -> aMundoDesdeCero(s, 0.0, s.fondo, 0.0)
        EjeDeRepeticion.ALTO -> aMundoDesdeCero(s, 0.0, 0.0, s.altura)
    }
    val paso = Pt3(uno.x - cero.x, uno.y - cero.y, uno.z - cero.z)
    if (abs(paso.x) < 1e-9 && abs(paso.y) < 1e-9 && abs(paso.z) < 1e-9) return emptyList()

    return (1..veces).map { i ->
        val sitio = proyectar(
            origen.x + paso.x * i, origen.y + paso.y * i, 0.0, vista, PASO_DEL_SOLIDO
        )
        e.copy(
            id = randomId(),
            seed = randomSeed(),
            x = sitio.x,
            y = sitio.y,
            cota = kotlin.math.max(s.base + paso.z * i, 0.0)
        ).touched()
    }
}

/**
 * Las cuatro esquinas de una imagen **tumbada en el suelo**, en coordenadas de escena.
 *
 * Una imagen del suelo se guarda igual que una caja: `x` e `y` son dónde cae proyectado
 * el origen de su huella, y `width` y `height` lo que mide esa huella en el mundo. Así
 * moverla, medirla y engancharla es lo mismo que con un volumen, y sobre todo **está a la
 * misma escala**: un plano de planta metido debajo sirve para levantar las cajas encima
 * midiendo sobre él.
 */
fun imagenEnElSuelo(e: Element, vista: Vista): List<Pt> =
    listOf(
        Pt(0.0, 0.0), Pt(e.width, 0.0), Pt(e.width, e.height), Pt(0.0, e.height)
    ).map {
        val p = proyectar(it.x, it.y, 0.0, vista, PASO_DEL_SOLIDO)
        Pt(e.x + p.x, e.y + p.y)
    }

/**
 * Las aristas que **hay que dibujar de verdad**: la silueta y los quiebros.
 *
 * Aquí está lo que hacía que un cilindro se viera como una persiana. Un cuerpo redondo se
 * construye con veinticuatro caras planas, y dibujando el borde de cada una salen
 * veinticuatro rayas verticales sobre una superficie que **no tiene** ninguna. En un
 * croquis eso ya es feo; para dibujar encima —anotar, medir, meter una cota— es peor:
 * la figura se llena de líneas donde no hay nada y no queda sitio limpio.
 *
 * El criterio es el del dibujo técnico de toda la vida, y es sencillo. Una arista se ve si:
 *
 * - **es silueta** —de las dos caras que la comparten se ve una y la otra no—, que es el
 *   borde del bulto contra el fondo; o
 * - **es un quiebro** —las dos caras se ven, pero forman ángulo entre ellas—, que es una
 *   arista de verdad, como la de un cubo.
 *
 * Dos caras casi en el mismo plano no dejan arista: es superficie lisa. Con eso, un cubo
 * sigue teniendo sus doce y un cilindro se queda con su silueta y sus dos bocas, que es
 * exactamente lo que uno dibujaría a mano.
 */
fun aristasMarcadasDeSolido(
    solido: Solido,
    vista: Vista,
    paso: Double = PASO_ISO,
    /**
     * El reparto de caras ya hecho, si quien llama lo tiene.
     *
     * Repartir las caras de una pieza no es gratis —proyectar, sacar normales, ordenar— y
     * quien dibuja ya lo ha hecho un renglón antes. Sin esto se hacía **dos veces por
     * pieza y por recomposición**, y girando un cilindro con el dedo eso son cincuenta y
     * dos repartos por fotograma: era la mitad del tirón al girar.
     */
    yaRepartidas: List<CaraDeSolido>? = null
): List<Pair<Pt, Pt>> {
    val s = solido.normalizado()
    val caras = yaRepartidas ?: carasDeSolido(s, vista, paso, soloVisibles = false)
    // Por arista, las caras que la comparten.
    val porArista = HashMap<Int, MutableList<CaraDeSolido>>()
    for (cara in caras) {
        val idx = cara.indices
        for (i in idx.indices) {
            val a = idx[i]
            val b = idx[(i + 1) % idx.size]
            if (a == b) continue
            porArista.getOrPut(claveDeArista(a, b)) { mutableListOf() } += cara
        }
    }

    val v = verticesDe(s).map { proyectar(it, vista, paso) }
    val salida = mutableListOf<Pair<Pt, Pt>>()
    for ((clave, suyas) in porArista) {
        val vistas = suyas.count { it.visible }
        if (vistas == 0) continue
        val dibujar = when {
            // Silueta: la comparten una cara vista y una que no, o solo pertenece a una.
            suyas.size < 2 || vistas < suyas.size -> true
            // Quiebro: las dos se ven, pero no están en el mismo plano.
            else -> anguloEntre(suyas[0].normal, suyas[1].normal) > QUIEBRO_MINIMO
        }
        if (!dibujar) continue
        val a = clave / 65536
        val b = clave % 65536
        val pa = v.getOrNull(a) ?: continue
        val pb = v.getOrNull(b) ?: continue
        if (hypot(pb.x - pa.x, pb.y - pa.y) > MINIMA_ARISTA) salida += pa to pb
    }
    return salida
}

/** El ángulo entre dos normales, en radianes. Cero si alguna está degenerada. */
private fun anguloEntre(a: Pt3, b: Pt3): Double {
    val la = kotlin.math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
    val lb = kotlin.math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
    if (la <= 1e-12 || lb <= 1e-12) return 0.0
    val cos = ((a.x * b.x + a.y * b.y + a.z * b.z) / (la * lb)).coerceIn(-1.0, 1.0)
    return kotlin.math.acos(cos)
}

/**
 * A partir de qué ángulo dos caras dejan de ser la misma superficie: **veinte grados**.
 *
 * Un cilindro de veinticuatro lados quiebra quince grados por lado, así que con este
 * número se lee como lo que quiere ser —liso— y no como un prisma. Y un chaflán de
 * verdad, que en un croquis nadie hace de menos de treinta, se sigue viendo.
 */
private val QUIEBRO_MINIMO = Math.toRadians(20.0)

/** Lo mismo en coordenadas de escena. Ver [aristasMarcadasDeSolido]. */
/** Todas las aristas de la pieza, se vean o no. Es lo que se pinta en esqueleto. */
fun aristasDeSolido(
    solido: Solido,
    vista: Vista,
    paso: Double = PASO_ISO
): List<Pair<Pt, Pt>> {
    val s = solido.normalizado()
    val v = verticesDe(s).map { proyectar(it, vista, paso) }
    return aristasDe(s)
        .map { v[it.first] to v[it.second] }
        .filter { hypot(it.second.x - it.first.x, it.second.y - it.first.y) > MINIMA_ARISTA }
}

/** Lo mismo en coordenadas de escena. Ver [aristasDeSolido]. */
fun aristasDeElemento(e: Element, vista: Vista): List<Pair<Pt, Pt>> =
    aristasDeSolido(solidoDe(e), vista, PASO_DEL_SOLIDO).map { (a, b) ->
        Pt(e.x + a.x, e.y + a.y) to Pt(e.x + b.x, e.y + b.y)
    }

// -------------------------------------------------------------------------
// Los dos anillos: orientar un volumen en el espacio
// -------------------------------------------------------------------------

/** Sobre qué eje se está girando. Ver [anilloDelVolumen]. */
enum class EjeDeGiro {
    /** El vertical: la pieza gira **sobre el suelo**, de pie. Es el anillo horizontal. */
    EN_PLANTA,

    /** Uno horizontal: la pieza **se tumba**. Es el anillo vertical. */
    VOLCADO
}

/** Las dos direcciones del mundo que barre cada anillo. */
private fun basesDe(eje: EjeDeGiro): Pair<Pt3, Pt3> = when (eje) {
    EjeDeGiro.EN_PLANTA -> Pt3(1.0, 0.0, 0.0) to Pt3(0.0, 1.0, 0.0)
    EjeDeGiro.VOLCADO -> Pt3(0.0, 1.0, 0.0) to Pt3(0.0, 0.0, 1.0)
}

/**
 * El anillo con el que se orienta un volumen, ya proyectado.
 *
 * **Dos círculos y no ocho tiradores.** Un volumen no se orienta con esquinas: se orienta
 * diciendo cuánto gira sobre cada eje, y eso en un dibujo se dice con un aro. El
 * horizontal lo hace girar de pie sobre el suelo y el vertical lo tumba, que son los dos
 * grados de libertad que tiene algo apoyado en un plano —el tercero sería rodarlo sobre sí
 * mismo, y en un croquis conceptual no dice nada que los otros dos no digan.
 *
 * Cada uno se dibuja **en su plano del mundo** y luego se proyecta, así que el horizontal
 * sale como el rombo achatado de la isométrica y el vertical, de canto: eso solo ya cuenta
 * cuál hace qué, sin tener que explicarlo.
 */
fun anilloDelVolumen(
    centro: Pt3,
    radio: Double,
    eje: EjeDeGiro,
    vista: Vista,
    paso: Double = PASO_DEL_SOLIDO,
    tramos: Int = TRAMOS_DEL_ANILLO
): List<Pt> {
    val (u, w) = basesDe(eje)
    return (0..tramos).map { i ->
        val t = 2 * Math.PI * i / tramos
        val c = cos(t) * radio
        val sn = sin(t) * radio
        proyectar(
            centro.x + u.x * c + w.x * sn,
            centro.y + u.y * c + w.y * sn,
            centro.z + u.z * c + w.z * sn,
            vista, paso
        )
    }
}

/** ¿Cae el dedo **sobre el aro** —no dentro— con [margen] de tolerancia? */
fun tocaElAnillo(p: Pt, anillo: List<Pt>, margen: Double): Boolean {
    for (i in 0 until anillo.size - 1) {
        val a = anillo[i]
        val b = anillo[i + 1]
        val vx = b.x - a.x
        val vy = b.y - a.y
        val largo2 = vx * vx + vy * vy
        val t = if (largo2 <= 0.0) 0.0
        else (((p.x - a.x) * vx + (p.y - a.y) * vy) / largo2).coerceIn(0.0, 1.0)
        if (hypot(p.x - (a.x + vx * t), p.y - (a.y + vy * t)) <= margen) return true
    }
    return false
}

/** Con cuántos tramos se dibuja un anillo: los justos para que no se le vean los lados. */
const val TRAMOS_DEL_ANILLO = 48

/**
 * El ángulo que marca el dedo sobre un anillo, **medido en el plano del anillo**.
 *
 * No en la pantalla: un círculo del mundo se proyecta como una elipse, así que el ángulo
 * de la pantalla avanza a saltos distintos según por dónde vaya y la pieza giraría a
 * tirones. Se deshace la proyección sobre el plano del propio anillo —que es invertir una
 * matriz de dos por dos, porque la proyección es afín— y allí el ángulo es el de verdad.
 *
 * Devuelve null si ese plano se ve exactamente de canto: ahí no hay ángulo que leer, y es
 * mejor no mover nada que mover cualquier cosa.
 */
fun anguloEnElAnillo(
    centro: Pt3,
    eje: EjeDeGiro,
    pantalla: Pt,
    vista: Vista,
    paso: Double = PASO_DEL_SOLIDO
): Double? {
    val (u, w) = basesDe(eje)
    val o = proyectar(centro.x, centro.y, centro.z, vista, paso)
    val pu = proyectar(centro.x + u.x, centro.y + u.y, centro.z + u.z, vista, paso)
    val pw = proyectar(centro.x + w.x, centro.y + w.y, centro.z + w.z, vista, paso)
    val a = pu.x - o.x
    val b = pw.x - o.x
    val c = pu.y - o.y
    val d = pw.y - o.y
    val det = a * d - b * c
    if (abs(det) < 1e-9) return null
    val dx = pantalla.x - o.x
    val dy = pantalla.y - o.y
    val eu = (dx * d - b * dy) / det
    val ew = (a * dy - dx * c) / det
    if (abs(eu) < 1e-9 && abs(ew) < 1e-9) return null
    return kotlin.math.atan2(ew, eu)
}

/**
 * El centro y el radio de los anillos para lo que haya seleccionado.
 *
 * Con una pieza es su propio centro; con varias, el de todas juntas — que es lo que hace
 * que **girar un conjunto** lo gire como un bloque, cada pieza alrededor del mismo eje, en
 * vez de que cada una gire sobre sí misma y el montaje se deshaga.
 */
fun centroDeVolumenes(elementos: List<Element>, vista: Vista): Pair<Pt3, Double>? {
    val solidos = elementos.filter { it.isSolido && !it.isDeleted }
    if (solidos.isEmpty()) return null
    val puntos = solidos.flatMap { e ->
        val origen = desproyectar(e.x, e.y, vista, PASO_DEL_SOLIDO)
        verticesDe(solidoDe(e).normalizado()).map {
            Pt3(origen.x + it.x, origen.y + it.y, it.z)
        }
    }
    val cx = puntos.sumOf { it.x } / puntos.size
    val cy = puntos.sumOf { it.y } / puntos.size
    val cz = puntos.sumOf { it.z } / puntos.size
    val radio = puntos.maxOf {
        kotlin.math.sqrt(
            (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) + (it.z - cz) * (it.z - cz)
        )
    }
    return Pt3(cx, cy, cz) to kotlin.math.max(radio * HOLGURA_DEL_ANILLO, MINIMO_DEL_ANILLO)
}

/** Cuánto se separan los anillos de lo que rodean, y su tamaño mínimo. */
private const val HOLGURA_DEL_ANILLO = 1.15
private const val MINIMO_DEL_ANILLO = 24.0

/**
 * Gira [e] alrededor de [centro] el ángulo [delta] sobre [eje].
 *
 * Es la misma operación para una pieza sola que para un conjunto: se gira **dónde está**
 * —su origen, alrededor del centro— y **cómo está** —su propio ángulo, sumándole el
 * mismo delta—. Hacer solo lo segundo dejaría cada pieza girando sobre sí misma sin
 * moverse de su sitio, que es lo que uno no quiere al girar un montaje entero.
 */
fun giradoEnElEspacio(
    e: Element,
    centro: Pt3,
    delta: Double,
    eje: EjeDeGiro,
    vista: Vista
): Element {
    if (!e.isSolido || delta == 0.0) return e
    val origen = desproyectar(e.x, e.y, vista, PASO_DEL_SOLIDO)
    val c = cos(delta)
    val sn = sin(delta)
    return when (eje) {
        EjeDeGiro.EN_PLANTA -> {
            val dx = origen.x - centro.x
            val dy = origen.y - centro.y
            val sitio = proyectar(
                centro.x + dx * c - dy * sn, centro.y + dx * sn + dy * c,
                0.0, vista, PASO_DEL_SOLIDO
            )
            e.copy(x = sitio.x, y = sitio.y, giroEnPlanta = e.giroEnPlanta + delta)
        }
        EjeDeGiro.VOLCADO -> {
            val dy = origen.y - centro.y
            val dz = e.cota - centro.z
            val ny = centro.y + dy * c - dz * sn
            val nz = centro.z + dy * sn + dz * c
            val sitio = proyectar(origen.x, ny, 0.0, vista, PASO_DEL_SOLIDO)
            e.copy(
                x = sitio.x, y = sitio.y,
                cota = kotlin.math.max(nz, 0.0),
                inclinacion = e.inclinacion + delta
            )
        }
    }.touched()
}

/** La caja que envuelve al sólido ya proyectado. Sirve para picar y para encuadrar. */
fun cajaDeSolido(solido: Solido, vista: Vista, paso: Double = PASO_ISO): Bounds {
    val puntos = verticesDe(solido).map { proyectar(it, vista, paso) }
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (p in puntos) {
        minX = min(minX, p.x); minY = min(minY, p.y)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y)
    }
    return Bounds(minX, minY, maxX, maxY)
}

/**
 * Con cuántas bandas se **mide** la silueta de una revolución.
 *
 * Sesenta y cuatro: bastantes más de las que se van a guardar. Aquí se mide y después se
 * aclara por forma ([aclarado]), y las dos cosas quieren números distintos — medir fino es
 * lo que conserva el hombro de un escalón, y guardar poco es lo que hace que la pieza se
 * pinte deprisa. Midiendo con las mismas dieciséis que se guardaban, un escalón caía dentro
 * de una banda y salía redondeado.
 */
private const val ANILLOS_AL_MEDIR = 64

/**
 * Qué parte del perfil tiene que caer a un lado del eje para tornear **solo ese lado**.
 *
 * Ocho de cada diez. Por encima de eso, lo que hay al otro lado es la mano temblando al
 * cruzar la raya; por debajo, el perfil se dibujó a los dos lados a propósito y hay que
 * respetarlo. Ver [revolucionado].
 */
private const val LADO_QUE_MANDA = 0.8
