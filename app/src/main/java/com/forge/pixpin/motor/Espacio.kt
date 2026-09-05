package com.forge.pixpin.motor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * El espacio de tres ejes: **x, y, z proyectados en el papel, y se gira**.
 *
 * Es el tercer instrumento de graficar, después del plano y la recta (ver
 * [Plano]), y comparte con ellos lo que los hace instrumentos: la unidad, los
 * pasos de números y de cuadros, y el estirado que por un lado enseña más
 * números y por una esquina agranda los mismos. Lo que añade es la tercera
 * dimensión y, con ella, **un punto de vista**.
 *
 * ## La proyección
 *
 * Paralela —sin fuga—, que es la de los libros: dos rectas paralelas del
 * espacio siguen paralelas en el papel y una unidad mide lo mismo en todo el
 * eje, sin lo cual las cifras de la regla no valdrían para nada. El punto de
 * vista son dos ángulos: [Element.azimut], cuánto se ha dado la vuelta
 * alrededor de la vertical, y [Element.elevacion], cuánto se mira desde arriba.
 * La z va siempre hacia arriba del papel.
 *
 * ## El giro
 *
 * El tirador de giro de la selección, que en los demás elementos inclina la
 * caja, aquí **da la vuelta al espacio**: la caja se queda derecha —los
 * números se leen— y lo que gira es lo que hay dentro. Es el mismo gesto
 * que uno hace con una maqueta encima de la mesa. Ver [girarElEspacio].
 *
 * ## Lo que cabe
 *
 * Los ejes se alargan hasta tocar la caja, cada uno según cómo caiga
 * proyectado: cuanto más de frente se mire un eje, más corto se ve y más
 * unidades le caben. Así el instrumento llena siempre su caja, y estirar la
 * caja por un lado —la unidad se queda— es meter más números.
 *
 * Sin Android: es geometría, y es la parte que se equivoca en silencio.
 */

/** Hacia dónde apunta cada eje **en el papel**, por unidad de escena. */
data class ProyeccionDelEspacio(val ex: Pt, val ey: Pt, val ez: Pt) {
    /** Dónde cae el punto ([x], [y], [z]) del espacio, relativo al origen. */
    fun de(x: Double, y: Double, z: Double): Pt =
        Pt(ex.x * x + ey.x * y + ez.x * z, ex.y * x + ey.y * y + ez.y * z)
}

/**
 * La vista de partida, que es la del cuaderno: la x viene hacia quien mira
 * cayendo a la izquierda, la y se va a la derecha y la z sube.
 */
const val AZIMUT_BASE = -120.0

/** Lo que se mira desde arriba si el elemento no lo dice, en grados. */
const val ELEVACION_POR_DEFECTO = 25.0

/** Lo que se aparta de la caja para que quepan las letras de los ejes. */
private const val MARGEN_DE_LETRAS = 12.0

/** El giro del elemento, en grados, sobre la vista de partida. */
val Element.azimutDelEspacio: Double get() = azimut ?: 0.0

/** La inclinación del elemento, en grados, acotada para que el suelo no se dé la vuelta. */
val Element.elevacionDelEspacio: Double
    get() = (elevacion ?: ELEVACION_POR_DEFECTO).coerceIn(0.0, 89.0)

/**
 * La proyección de [e]: los tres versores del espacio llevados al papel.
 *
 * Primero se gira el espacio alrededor de la vertical ([Element.azimut]) y
 * después se inclina la mirada ([Element.elevacion]): lo que está más lejos
 * sube en el papel tanto como diga la inclinación, y la z sube siempre, algo
 * menos cuanto más en picado se mire.
 */
fun proyeccionDelEspacio(e: Element): ProyeccionDelEspacio {
    val a = Math.toRadians(AZIMUT_BASE + e.azimutDelEspacio)
    val b = Math.toRadians(e.elevacionDelEspacio)
    fun p(x: Double, y: Double, z: Double): Pt {
        val derecha = x * cos(a) - y * sin(a)
        val lejos = x * sin(a) + y * cos(a)
        val arriba = lejos * sin(b) + z * cos(b)
        // La y de la escena crece hacia abajo.
        return Pt(derecha, -arriba)
    }
    return ProyeccionDelEspacio(p(1.0, 0.0, 0.0), p(0.0, 1.0, 0.0), p(0.0, 0.0, 1.0))
}

/**
 * Hasta dónde llega cada eje, en unidades, para que sus dos puntas quepan en
 * la caja: (alcance en x, en y, en z).
 */
fun alcanceDelEspacio(e: Element): Triple<Double, Double, Double> {
    val u = e.unidadDelPlano
    val pr = proyeccionDelEspacio(e)
    val medioAncho = (e.width / 2 - MARGEN_DE_LETRAS).coerceAtLeast(1.0)
    val medioAlto = (e.height / 2 - MARGEN_DE_LETRAS).coerceAtLeast(1.0)
    fun alcance(v: Pt): Double {
        var r = Double.MAX_VALUE
        if (abs(v.x) > 1e-6) r = minOf(r, medioAncho / abs(v.x))
        if (abs(v.y) > 1e-6) r = minOf(r, medioAlto / abs(v.y))
        return if (r == Double.MAX_VALUE) 0.0 else r / u
    }
    return Triple(alcance(pr.ex), alcance(pr.ey), alcance(pr.ez))
}

/**
 * Las rayas del espacio: el suelo pautado, los tres ejes con sus marcas y una
 * punta en el extremo positivo de cada uno.
 *
 * El suelo va primero y fino, como la rejilla del plano: es el papel sobre el
 * que se lee la altura de las cosas. Los ejes, encima. Las marcas de cada eje
 * van cruzadas en la dirección de otro eje, que es como se ven las marcas de
 * una regla puesta de canto.
 */
fun trazosDelEspacio(e: Element): List<TrazoDelPlano> {
    if (e.width <= 0 || e.height <= 0) return emptyList()
    val u = e.unidadDelPlano
    val o = origenDelPlano(e)
    val pr = proyeccionDelEspacio(e)
    val (ax, ay, az) = alcanceDelEspacio(e)
    fun en(x: Double, y: Double, z: Double): Pt {
        val p = pr.de(x * u, y * u, z * u)
        return Pt(o.x + p.x, o.y + p.y)
    }
    val out = mutableListOf<TrazoDelPlano>()

    // El suelo: una rejilla en el plano xy. No llega hasta donde llegan los
    // ejes: cada eje cabe en la caja por su cuenta, pero la esquina del suelo
    // —los dos alcances sumados— se saldría, así que se encoge lo justo para
    // que sus cuatro esquinas queden dentro.
    val pasoDeCuadros = pasoUtil(e.pasoDeCuadrosDelPlano, u * hypot(pr.ex.x, pr.ex.y).coerceAtLeast(0.2), MINIMO_CUADRO_DEL_ESPACIO)
    val medioAncho = (e.width / 2 - MARGEN_DE_LETRAS).coerceAtLeast(1.0)
    val medioAlto = (e.height / 2 - MARGEN_DE_LETRAS).coerceAtLeast(1.0)
    val anchoDelSuelo = (abs(pr.ex.x) * ax + abs(pr.ey.x) * ay) * u
    val altoDelSuelo = (abs(pr.ex.y) * ax + abs(pr.ey.y) * ay) * u
    val encoge = minOf(
        1.0,
        if (anchoDelSuelo > 1e-9) medioAncho / anchoDelSuelo else 1.0,
        if (altoDelSuelo > 1e-9) medioAlto / altoDelSuelo else 1.0
    )
    val nx = floor(ax * encoge / pasoDeCuadros).toInt().coerceIn(0, MAXIMAS_DEL_ESPACIO)
    val ny = floor(ay * encoge / pasoDeCuadros).toInt().coerceIn(0, MAXIMAS_DEL_ESPACIO)
    if (nx > 0 && ny > 0) {
        val lx = nx * pasoDeCuadros
        val ly = ny * pasoDeCuadros
        for (i in -nx..nx) {
            val x = i * pasoDeCuadros
            out += TrazoDelPlano(en(x, -ly, 0.0), en(x, ly, 0.0), eje = false)
        }
        for (j in -ny..ny) {
            val y = j * pasoDeCuadros
            out += TrazoDelPlano(en(-lx, y, 0.0), en(lx, y, 0.0), eje = false)
        }
    }

    // Los ejes, de punta a punta.
    out += TrazoDelPlano(en(-ax, 0.0, 0.0), en(ax, 0.0, 0.0), eje = true)
    out += TrazoDelPlano(en(0.0, -ay, 0.0), en(0.0, ay, 0.0), eje = true)
    out += TrazoDelPlano(en(0.0, 0.0, -az), en(0.0, 0.0, az), eje = true)

    // Las marcas: en la x cruzadas según la y, en la y según la x, y en la z
    // según la x. Miden lo mismo en el papel las tres.
    fun marcas(alcance: Double, sitio: (Double) -> Pt, cruce: Pt) {
        val largo = hypot(cruce.x, cruce.y)
        if (largo < 1e-6) return
        val cx = cruce.x / largo * MARCA
        val cy = cruce.y / largo * MARCA
        for (n in enteros(alcance, pasoDeCuadros)) {
            if (n == 0) continue
            val c = sitio(n * pasoDeCuadros)
            out += TrazoDelPlano(Pt(c.x - cx, c.y - cy), Pt(c.x + cx, c.y + cy), eje = true)
        }
    }
    marcas(ax, { en(it, 0.0, 0.0) }, pr.ey)
    marcas(ay, { en(0.0, it, 0.0) }, pr.ex)
    marcas(az, { en(0.0, 0.0, it) }, pr.ex)

    // Las puntas, en el extremo positivo: es lo que dice hacia dónde crece cada eje.
    val punta = largoDeLaPunta(e)
    out += puntaDeFlecha(en(ax, 0.0, 0.0), pr.ex.x, pr.ex.y, punta)
    out += puntaDeFlecha(en(0.0, ay, 0.0), pr.ey.x, pr.ey.y, punta)
    out += puntaDeFlecha(en(0.0, 0.0, az), pr.ez.x, pr.ez.y, punta)
    return out
}

/**
 * Las cifras de los tres ejes y sus letras.
 *
 * Los números de la x y de la y cuelgan debajo de su marca; los de la z van a
 * la izquierda, como en la regla vertical del plano. El cero se escribe una
 * vez. Las letras x, y, z van pasada la punta de cada eje, centradas en la
 * prolongación del eje.
 */
fun numerosDelEspacio(e: Element): List<NumeroDelPlano> {
    if (e.width <= 0 || e.height <= 0) return emptyList()
    val u = e.unidadDelPlano
    val o = origenDelPlano(e)
    val pr = proyeccionDelEspacio(e)
    val (ax, ay, az) = alcanceDelEspacio(e)
    fun en(x: Double, y: Double, z: Double): Pt {
        val p = pr.de(x * u, y * u, z * u)
        return Pt(o.x + p.x, o.y + p.y)
    }
    val out = mutableListOf<NumeroDelPlano>()
    fun cifras(alcance: Double, v: Pt, sitio: (Double) -> Pt, horizontal: Boolean) {
        val paso = pasoUtil(e.pasoDeNumerosDelPlano, u * hypot(v.x, v.y).coerceAtLeast(0.2), MINIMO_ENTRE_NUMEROS)
        for (n in enteros(alcance, paso)) {
            if (n == 0) continue
            out += NumeroDelPlano(sitio(n * paso), escrito(n * paso), horizontal)
        }
    }
    cifras(ax, pr.ex, { en(it, 0.0, 0.0) }, horizontal = true)
    cifras(ay, pr.ey, { en(0.0, it, 0.0) }, horizontal = true)
    cifras(az, pr.ez, { en(0.0, 0.0, it) }, horizontal = false)
    out += NumeroDelPlano(o, "0", horizontal = true)

    // Las letras: pasada la punta, y subidas media letra para que queden
    // centradas en la prolongación del eje y no colgando de ella.
    val tam = (e.fontSize ?: TAM_DE_LA_LETRA).coerceAtLeast(1.0)
    val punta = largoDeLaPunta(e)
    fun letra(texto: String, alcance: Double, v: Pt, tope: Pt) {
        val largo = hypot(v.x, v.y)
        if (largo < 1e-6 || alcance <= 0.0) return
        val dx = v.x / largo * (punta + tam * 0.6)
        val dy = v.y / largo * (punta + tam * 0.6)
        out += NumeroDelPlano(Pt(tope.x + dx, tope.y + dy - tam * 0.75), texto, horizontal = true)
    }
    letra("x", ax, pr.ex, en(ax, 0.0, 0.0))
    letra("y", ay, pr.ey, en(0.0, ay, 0.0))
    letra("z", az, pr.ez, en(0.0, 0.0, az))
    return out
}

/**
 * Dónde cae el punto ([x], [y], [z]) del espacio, **en la escena**: lo que lo
 * hace instrumento. Ver [puntoDelPlano].
 */
fun puntoDelEspacio(e: Element, x: Double, y: Double, z: Double): Pt {
    val u = e.unidadDelPlano
    val o = origenDelPlano(e)
    val p = proyeccionDelEspacio(e).de(x * u, y * u, z * u)
    return Pt(e.x + o.x + p.x, e.y + o.y + p.y)
}

/**
 * El tirador de giro sobre el espacio: **da la vuelta al espacio**, no a la caja.
 *
 * Se toma el mismo ángulo que tomaría [rotateSingleElement] —el del puntero
 * respecto del centro, con el tirador arriba como cero— y se guarda como
 * [Element.azimut]. Así el tirador arriba es la vista de partida, y llevarlo
 * a un lado da la vuelta al espacio otro tanto; la caja sigue derecha y los
 * números, legibles.
 */
fun girarElEspacio(e: Element, pointer: Pt, discreto: Boolean = false): Element {
    val c = getElementAbsoluteCoords(e)
    var angulo = Math.toDegrees(Math.PI / 2 + kotlin.math.atan2(pointer.y - c.cy, pointer.x - c.cx))
    if (discreto) angulo = Math.round(angulo / 15.0) * 15.0
    angulo = ((angulo % 360.0) + 540.0) % 360.0 - 180.0
    return e.copy(azimut = angulo).touched()
}

/** Lo que mide cada marca de los ejes a cada lado, en px de escena. */
private const val MARCA = 4.0

/** El cuadro del suelo no baja de esto en el papel, o la rejilla es una mancha. */
private const val MINIMO_CUADRO_DEL_ESPACIO = 10.0

/** Cuántos cuadros del suelo como mucho a cada lado del origen. */
private const val MAXIMAS_DEL_ESPACIO = 60

/** El tamaño de la letra de los ejes si el elemento no trae uno; el de las cifras. */
private const val TAM_DE_LA_LETRA = 11.0
