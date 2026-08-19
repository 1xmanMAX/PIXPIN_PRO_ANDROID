package com.forge.pixpin.motor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * El lienzo 3D: **de tres números a la pantalla plana de siempre**.
 *
 * Esto no es un motor 3D y no quiere serlo. Es lo que hace falta para que un
 * boceto conceptual —una caja, un volumen, un croquis de cómo se apilan las
 * cosas— se pueda dibujar encima del mismo `Canvas` que ya pinta todo lo demás.
 * Lo único que aporta es **una cuenta**: dónde cae en pantalla un punto del que
 * sabemos tres coordenadas.
 *
 * Se hace a mano, sin biblioteca, y no por gusto: se midió, y meter un motor de
 * verdad añadía 3,15 MB a un APK de 4,68 MB. Casi doblar la aplicación para
 * dibujar cajas es un precio que no se paga. Todo lo que hay aquí cabe en dos
 * multiplicaciones por punto.
 *
 * ## El convenio del mundo
 *
 * `x` e `y` son el **suelo** —el mismo plano en el que se dibuja siempre— y `z`
 * va **hacia arriba**. La pantalla es la de Excalidraw: `y` crece hacia abajo.
 * Por eso subir en el mundo resta en pantalla, que es la única línea de la
 * proyección que se lee al revés de lo que uno espera.
 *
 * ## Isométrica de 30°, y no perspectiva
 *
 * Sin punto de fuga: dos cajas iguales se dibujan iguales estén donde estén, y
 * una arista de tres unidades mide exactamente tres veces la de una. En un
 * boceto conceptual eso vale más que el realismo — se puede medir sobre el
 * dibujo, y sobre todo **se puede imantar a una retícula**, que con perspectiva
 * no se podría porque el paso cambiaría en cada punto de la pantalla.
 *
 * Ver [proyectar], [desproyectar], [Vista] e [imanDeReticula].
 */

// -------------------------------------------------------------------------
// Las constantes de la isométrica
// -------------------------------------------------------------------------

/**
 * Lo que mide en pantalla un paso del mundo: el lado del cubo unidad.
 *
 * El mismo número que el cuadro del papel pautado ([PASO_DE_CUADRICULA]) para
 * que un cubo unidad y una casilla de la cuadrícula plana se sientan del mismo
 * tamaño al alternar entre dibujar en plano y dibujar en volumen.
 */
const val PASO_ISO = 20.0

/**
 * Lo que **avanza a lo ancho** un paso del suelo, en tanto por uno del paso.
 *
 * Los dos ejes del suelo se dibujan subiendo 30° sobre la horizontal, uno hacia
 * cada lado. Un paso de largo `s` en esa dirección se descompone en
 * `s·cos30 = s·√3/2` de ancho y `s·sen30 = s/2` de alto: de ahí salen estos dos
 * números y no de ningún otro sitio.
 *
 * Consecuencia de tomar los 30° exactos: los tres ejes se dibujan **sin
 * acortar** —una arista de longitud `s` mide `s` en el papel, vaya en la
 * dirección que vaya—, así que un cubo de lado `s` sale como un hexágono de
 * `s·√3` de ancho por `2s` de alto. Eso es lo que hace que se pueda medir sobre
 * el dibujo con una regla.
 */
val AVANCE_H: Double = sqrt(3.0) / 2

/** Lo que **sube** un paso del suelo, en tanto por uno del paso: `sen 30° = ½`. */
const val AVANCE_V: Double = 0.5

// -------------------------------------------------------------------------
// El punto del mundo y desde dónde se mira
// -------------------------------------------------------------------------

/** Un punto del mundo. `x`, `y` en el suelo; `z` hacia arriba. */
data class Pt3(val x: Double, val y: Double, val z: Double)

/**
 * Desde dónde se mira: **cuatro cuartos de vuelta y nada más**.
 *
 * No hay órbita libre, y es una decisión de diseño, no una limitación de la
 * cuenta —girar un ángulo cualquiera serían dos senos más—. El motivo es el
 * punto de pivote: en cuanto la cámara puede orbitar, el usuario tiene que
 * saber **alrededor de qué** está girando, y ahí es donde se pierde todo el
 * mundo. Un arrastre de más y el modelo se ha ido de la pantalla, o se está
 * mirando desde debajo del suelo, y no hay forma evidente de volver. El que
 * empieza no vuelve: se queda dando vueltas intentando recuperar la vista que
 * tenía.
 *
 * Con cuatro vistas fijas eso no puede pasar. Hay un botón que gira un cuarto,
 * cuatro toques devuelven al sitio exacto de partida, y **no existe ningún
 * estado del que no se pueda salir**. Además cada vista sigue siendo isométrica
 * pura, así que la retícula, el imán y las cuentas de tamaño valen igual en las
 * cuatro. Un boceto conceptual no necesita más.
 *
 * [cuartos] son los cuartos de vuelta que se le dan **al mundo**, en el sentido
 * contrario a las agujas del reloj visto desde arriba. La cámara no se mueve
 * nunca; lo que gira es lo dibujado.
 *
 * Se serializa —vive en [Scene.vista]— con nombres escritos a mano y no con los
 * de la constante: es lo mismo que se hace con [ElementType], y por lo mismo,
 * que el nombre guardado en el archivo pueda sobrevivir a que alguien renombre
 * la constante en el código.
 */
@Serializable
enum class Vista(val cuartos: Int) {
    /** La de partida: `+x` baja a la derecha, `+y` baja a la izquierda. */
    @SerialName("cero") CERO(0),
    @SerialName("cuarto") CUARTO(1),
    @SerialName("media") MEDIA(2),
    @SerialName("tres-cuartos") TRES_CUARTOS(3);

    /** El siguiente cuarto de vuelta. Cuatro veces devuelve a esta misma. */
    fun siguiente(): Vista = de(cuartos + 1)

    /** El cuarto de vuelta para el otro lado. */
    fun anterior(): Vista = de(cuartos - 1)

    companion object {
        /** La vista de esos cuartos, dando la vuelta como es debido. */
        fun de(cuartos: Int): Vista = entries[((cuartos % 4) + 4) % 4]
    }
}

/**
 * El punto del suelo `(x, y)` una vez girado a esta vista.
 *
 * Girando con esta tabla y no con senos y cosenos: un cuarto de vuelta es
 * intercambiar dos números y cambiarle el signo a uno, así que las cuatro
 * vistas son **exactas**. Con `cos(π/2)` se colaría un `6,1e-17` en cada
 * coordenada y el ir y venir de [proyectar] a [desproyectar] dejaría de cerrar
 * limpio.
 */
internal fun giroDeVista(x: Double, y: Double, vista: Vista): Pt = when (vista.cuartos) {
    0 -> Pt(x, y)
    1 -> Pt(-y, x)
    2 -> Pt(-x, -y)
    else -> Pt(y, -x)
}

// -------------------------------------------------------------------------
// Ida
// -------------------------------------------------------------------------

/**
 * De un punto del mundo a un punto de la escena.
 *
 * [paso] es lo que mide en pantalla una unidad del mundo. Va aparte del zoom
 * del lienzo a propósito: el zoom es «acércame», y esto es «cómo de grande es
 * el mundo», que es una propiedad del dibujo y viaja con él.
 *
 * La `z` se resta porque en pantalla el eje vertical crece hacia abajo, y se
 * multiplica por [paso] entero —no por [AVANCE_V]— porque en la isométrica de
 * 30° la vertical se dibuja sin acortar.
 */
fun proyectar(x: Double, y: Double, z: Double, vista: Vista, paso: Double = PASO_ISO): Pt {
    val g = giroDeVista(x, y, vista)
    return Pt(
        (g.x - g.y) * paso * AVANCE_H,
        (g.x + g.y) * paso * AVANCE_V - z * paso
    )
}

/** [proyectar] de un [Pt3]. */
fun proyectar(p: Pt3, vista: Vista, paso: Double = PASO_ISO): Pt =
    proyectar(p.x, p.y, p.z, vista, paso)

/**
 * Cómo de cerca del que mira queda un punto. **Más grande, más delante.**
 *
 * Es la clave para pintar de atrás hacia delante. La dirección en la que se
 * mira es la diagonal `(1, 1, 1)` —es la única que se proyecta en un solo punto
 * de la pantalla—, así que la distancia a la cámara sale de sumar las tres
 * coordenadas ya giradas y nada más.
 *
 * No lleva [paso]: sirve para **comparar**, y multiplicarlo todo por el mismo
 * número no cambia ningún orden.
 */
fun profundidad(x: Double, y: Double, z: Double, vista: Vista): Double {
    val g = giroDeVista(x, y, vista)
    return g.x + g.y + z
}

// -------------------------------------------------------------------------
// Vuelta
// -------------------------------------------------------------------------

/**
 * De un punto de la pantalla a un punto **del suelo**. Es lo que convierte un
 * dedo en una posición del mundo.
 *
 * ## Por qué solo el suelo
 *
 * Sin fijar un plano, esto no tiene una respuesta: una pantalla tiene dos
 * números y el mundo tiene tres, así que a cada punto de la pantalla le
 * corresponde **una recta entera** de puntos del mundo. Elegir uno es
 * inventárselo.
 *
 * Y se nota en cuanto se toca. Arrastrando el dedo en vertical puro hacia
 * arriba, el resultado es exactamente el mismo si se interpreta como «subir en
 * `z`» que como avanzar en diagonal por el suelo hacia `(k, k, 0)`: las dos
 * cosas mueven el punto la misma cantidad de píxeles hacia arriba y ninguna lo
 * mueve de lado. No hay manera de saber cuál quería el usuario, y adivinar mal
 * es peor que no adivinar — una caja que sube cuando querías moverla es un
 * fallo que hay que deshacer.
 *
 * Por eso la v1 trabaja siempre sobre `z = 0`: el arrastre mueve por el suelo y
 * la altura se cambia con otro gesto, que es el reparto que no puede
 * confundirse. Ver [Solido] y su sombra.
 */
fun desproyectar(sx: Double, sy: Double, vista: Vista, paso: Double = PASO_ISO): Pt {
    val h = paso * AVANCE_H
    val v = paso * AVANCE_V
    // Con paso cero o disparatado la inversa no existe: se devuelve el origen
    // en vez de un NaN que se propagaría hasta la escena y dejaría elementos
    // imposibles de tocar y de borrar.
    if (h == 0.0 || v == 0.0 || !h.isFinite() || !v.isFinite()) return Pt(0.0, 0.0)
    if (!sx.isFinite() || !sy.isFinite()) return Pt(0.0, 0.0)

    // Deshaciendo el sistema de [proyectar] con z = 0:
    //   sx = (gx - gy)·h        →  gx - gy = sx/h
    //   sy = (gx + gy)·v        →  gx + gy = sy/v
    val gx = sx / (2 * h) + sy / (2 * v)
    val gy = -sx / (2 * h) + sy / (2 * v)
    return giroDeVista(gx, gy, Vista.de(-vista.cuartos))
}

// -------------------------------------------------------------------------
// El imán
// -------------------------------------------------------------------------

/**
 * El nodo de la retícula del suelo **más cercano al dedo**, en coordenadas del
 * mundo.
 *
 * [celda] es el lado del cuadro, en unidades del mundo; el resultado siempre es
 * un múltiplo suyo.
 *
 * ## Por qué se prueban cuatro candidatos en vez de redondear
 *
 * Lo evidente sería desproyectar el dedo y redondear `x` e `y` por separado. Y
 * está mal. Redondear cada coordenada da el nodo más cercano **midiendo en el
 * mundo**, pero el dedo no está en el mundo: está en la pantalla, y la
 * proyección deforma las distancias. En pantalla los dos ejes del suelo no
 * forman un ángulo recto sino uno de 60°, y en una base así de sesgada el nodo
 * más cercano al ojo puede ser otro.
 *
 * Se ve en el rombo de la retícula. Su diagonal corta —la que va de `(0,0)` a
 * `(1,1)`— mide en pantalla lo mismo que un lado, mientras que la larga, de
 * `(1,0)` a `(0,1)`, mide `√3` veces más. Cerca de esa diagonal larga, un punto
 * que cae del lado de `(1,0)` según el redondeo está de hecho **más cerca** de
 * `(0,0)` o de `(1,1)`. Redondeando, el imán engancha visiblemente al nodo
 * equivocado en dos de las cuatro esquinas de cada cuadro.
 *
 * Probar los cuatro redondeos y quedarse con el más cercano **de verdad** —con
 * la distancia medida en pantalla, que es donde está el dedo— cuesta cuatro
 * proyecciones, y no puede fallar: el rombo son dos triángulos equiláteros, y
 * dentro de un triángulo acutángulo el vértice más cercano es siempre uno de
 * los tres suyos.
 */
fun imanDeReticula(
    pantalla: Pt,
    vista: Vista,
    paso: Double = PASO_ISO,
    celda: Double = 1.0
): Pt {
    val suelo = desproyectar(pantalla.x, pantalla.y, vista, paso)
    if (celda <= 0.0 || !celda.isFinite() || !suelo.x.isFinite() || !suelo.y.isFinite()) {
        return suelo
    }

    val i = floor(suelo.x / celda)
    val j = floor(suelo.y / celda)
    var mejor = Pt(i * celda, j * celda)
    var mejorDist = Double.MAX_VALUE
    for (di in 0..1) {
        for (dj in 0..1) {
            val cand = Pt((i + di) * celda, (j + dj) * celda)
            val p = proyectar(cand.x, cand.y, 0.0, vista, paso)
            val dx = p.x - pantalla.x
            val dy = p.y - pantalla.y
            val d = dx * dx + dy * dy
            if (d < mejorDist) {
                mejorDist = d
                mejor = cand
            }
        }
    }
    return mejor
}
