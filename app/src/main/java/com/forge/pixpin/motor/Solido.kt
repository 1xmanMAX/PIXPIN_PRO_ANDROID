package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    /** Lo que levanta del suelo. */
    val altura: Double
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
        altura = abs(altura)
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
fun verticesDe(solido: Solido): List<Pt3> {
    val s = solido.normalizado()
    val x2 = s.x + s.ancho
    val y2 = s.y + s.fondo
    val z = s.altura
    return listOf(
        Pt3(s.x, s.y, 0.0),
        Pt3(x2, s.y, 0.0),
        Pt3(x2, y2, 0.0),
        Pt3(s.x, y2, 0.0),
        Pt3(s.x, s.y, z),
        Pt3(x2, s.y, z),
        Pt3(x2, y2, z),
        Pt3(s.x, y2, z)
    )
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
    val visible: Boolean
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
    val vertices = verticesDe(solido)
    val enPantalla = vertices.map { proyectar(it, vista, paso) }

    return Cara.entries.map { cara ->
        val poligono = cara.esquinas.map { enPantalla[it] }
        val centro = cara.esquinas.map { vertices[it] }
        CaraDeSolido(
            cara = cara,
            poligono = poligono,
            claridad = claridadDe(cara, vista),
            profundidad = centro.sumOf { profundidad(it.x, it.y, it.z, vista) } / centro.size,
            // Estricto, y así una caja aplastada se apaña sola: si la huella no
            // tiene ancho, lo que queda es un tabique, y sus otras cuatro caras
            // proyectan área cero. Cero no es «visible», es nada — pintarlas
            // dejaría un reguero de rayas del color del relleno sobre el dibujo.
            visible = areaConSigno(poligono) > 0.0
        )
    }.filter { !soloVisibles || it.visible }.sortedBy { it.profundidad }
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
fun claridadDe(cara: Cara, vista: Vista): Double {
    if (cara.normal.z > 0) return PASO_DE_CLARIDAD
    // La base no se ve nunca; el tono está por si alguna vez se pinta la caja
    // translúcida y se le ve el fondo por dentro.
    if (cara.normal.z < 0) return -2 * PASO_DE_CLARIDAD

    val g = giroDeVista(cara.normal.x, cara.normal.y, vista)
    val haciaLaDerecha = (g.x - g.y) > 0
    return if (haciaLaDerecha) -PASO_DE_CLARIDAD else 0.0
}

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
fun sombraEnElSuelo(solido: Solido, vista: Vista, paso: Double = PASO_ISO): List<Pt> {
    val s = solido.normalizado()
    val x2 = s.x + s.ancho
    val y2 = s.y + s.fondo
    return listOf(
        proyectar(s.x, s.y, 0.0, vista, paso),
        proyectar(x2, s.y, 0.0, vista, paso),
        proyectar(x2, y2, 0.0, vista, paso),
        proyectar(s.x, y2, 0.0, vista, paso)
    )
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
    val p = desproyectar(pantalla.x, pantalla.y, vista, paso)
    return p.x >= s.x - margen && p.x <= s.x + s.ancho + margen &&
        p.y >= s.y - margen && p.y <= s.y + s.fondo + margen
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
 * Cuánto se ve la sombra de un sólido, en tanto por ciento de su opacidad.
 *
 * Bastante menos de lo que uno pondría a ojo. Una sombra que compite con el
 * volumen deja de decir dónde está apoyado y pasa a parecer otra figura dibujada
 * en el suelo; lo que hace falta es solo que la huella se insinúe.
 *
 * Vive aquí y no en el renderizador porque **la pantalla, el SVG y el PDF tienen
 * que pintarla igual**: repartida en tres sitios, el día que alguien la retoque
 * en uno lo exportado dejaría de parecerse a lo dibujado.
 */
const val OPACIDAD_DE_LA_SOMBRA = 22

/**
 * El sólido que describe [e], **con la huella en el origen**.
 *
 * La huella se toma en `(0, 0)` y no en `(e.x, e.y)` porque `e.x` y `e.y` no son
 * un punto del mundo: son el punto de la **escena** donde cae el origen de la
 * huella ya proyectado. Proyectar desde el origen y trasladar después es lo que
 * mantiene esa promesa —y lo que hace que mover el elemento sea sumar, sin
 * volver a pasar por la proyección. Ver [ElementType.SOLIDO].
 */
fun solidoDe(e: Element): Solido =
    Solido(0.0, 0.0, e.width, e.height, e.altura ?: 0.0)

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
    carasDeSolido(solidoDe(e), vista, PASO_DEL_SOLIDO)
        .map { it.copy(poligono = aLaEscena(e, it.poligono)) }

/** La sombra de [e] en el suelo, en coordenadas de escena. Ver [sombraEnElSuelo]. */
fun sombraDeElemento(e: Element, vista: Vista): List<Pt> =
    aLaEscena(e, sombraEnElSuelo(solidoDe(e), vista, PASO_DEL_SOLIDO))

/**
 * Lo que puede llegar a ocupar [e] en la escena, **mire uno desde donde mire**.
 *
 * Existe porque hay un puñado de cosas que preguntan qué ocupa un elemento sin
 * tener a mano la [Vista] de la escena —el recorte por pantalla, sobre todo— y
 * la caja del sólido sí depende de ella: girar un cuarto no cambia lo que mide
 * el dibujo, pero sí hacia qué lado del origen cae.
 *
 * Así que se devuelve la **unión de las cuatro**, que sale de dos cuentas y no
 * depende de nada: a lo ancho la caja mide siempre `(ancho + fondo)·AVANCE_H` y
 * a lo alto `(ancho + fondo)·AVANCE_V + altura`, y lo único que se mueve es
 * dónde se apoya. Sobra sitio —hasta el doble— y eso es exactamente lo que se
 * quiere aquí: pasarse recortando esconde el dibujo, y quedarse corto no cuesta
 * más que unos píxeles de más en un descarte rápido.
 */
fun envolturaDeSolido(e: Element): Bounds {
    val s = solidoDe(e).normalizado()
    val diagonal = s.ancho + s.fondo
    val h = diagonal * AVANCE_H * PASO_DEL_SOLIDO
    val v = diagonal * AVANCE_V * PASO_DEL_SOLIDO
    val alto = s.altura * PASO_DEL_SOLIDO
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
