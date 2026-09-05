package com.forge.pixpin.motor

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * La espina de un trazo: la curva que pasa por los puntos de control, ya muestreada.
 *
 * ## Por qué Catmull-Rom **centrípeta** y no otra
 *
 * La de siempre —la uniforme— calcula cada tangente a partir de la distancia a los
 * vecinos, y cuando un vecino está dieciséis veces más lejos que el otro sale un
 * tirador enorme gobernando un tramo diminuto: el trazo se pasa de largo y vuelve.
 * Es el rizo de las esquinas que ya mordió una vez (ver `OutlineBuilder` en
 * `Shapes.kt`, donde se paró con la tirita de muestrear parejo). La centrípeta —nudos a distancia
 * `|Δp|^0.5`— es, según Yuksel, **la única de la familia que no hace cúspides ni
 * bucles dentro de un tramo**, se espacien los puntos como se espacien. Y un dedo
 * sobre un cristal los espacia como le da la gana: lento en las curvas, a saltos
 * en las rectas.
 *
 * La evaluación es la forma Hermite no uniforme de `CatmullRomCurve3` de three.js,
 * guardas incluidas, porque es la formulación probada en producción de la misma
 * curva.
 *
 * ## Por qué el muestreo es adaptativo
 *
 * Muestrear a paso fijo obliga a elegir entre dos males: paso corto y una recta de
 * un palmo suelta mil puntos que nadie ve, o paso largo y la curva cerrada sale a
 * facetas. Aquí cada tramo se pasa a su Bézier equivalente y se parte por de
 * Casteljau **solo donde la cuerda aún no da el pego**, con la parada del AGG de
 * Shemanarev: planitud contra la tolerancia y giro contra [anguloTope]. Los puntos
 * van solos a donde hay curvatura, que es exactamente donde hacen falta. La
 * fluidez manda: la espina se recalcula mientras el dedo dibuja.
 *
 * ## Por qué hay esquinas «queridas»
 *
 * Una spline que pasa por todos los puntos redondea todos los picos, también los
 * dibujados a propósito. En cada índice de `esquinas` el nudo se parte en dos: cada
 * lado calcula su tangente sin mirar al otro (como si la curva empezara o acabara
 * ahí) y la muestra de la esquina se emite con el punto de control **tal cual**.
 * Una esquina dibujada a propósito sigue siendo esquina, jamás se redondea.
 */
class Espina(
    /** Las muestras en el mundo, aplanadas de tres en tres: `x0 y0 z0 x1 y1 z1…`. */
    val xyz: DoubleArray,
    /** La tangente **unitaria** en cada muestra, aplanada igual que [xyz]. */
    val tangentes: DoubleArray,
    /** De qué tramo `[p_i, p_i+1]` sale cada muestra. */
    val deQueTramo: IntArray,
    /** En qué punto de su tramo cae cada muestra, `u` de 0 a 1. */
    val dondeEnElTramo: DoubleArray,
    /** El largo recorrido hasta cada muestra, en el mundo y empezando en cero. */
    val recorrido: DoubleArray
) {
    val cuantas: Int get() = xyz.size / 3
}

/**
 * Construye la [Espina] centrípeta que pasa por [puntos].
 *
 * [toleranciaEnMundo] es lo lejos que puede quedarse la polilínea muestreada de la
 * curva de verdad, **en unidades del mundo**: por eso multiplicar el mundo por mil
 * con su tolerancia por mil da las mismas muestras, y por eso quien pinta puede
 * pedir «medio píxel de los de ahora» y olvidarse del zoom.
 *
 * [anguloTope] es el giro (en radianes) que aún se tolera entre cuerdas seguidas:
 * la planitud sola dejaría pasar codos cerrados de tramos cortos, que caben en la
 * tolerancia pero se ven facetados.
 *
 * [topeDeMuestras] es la red de seguridad de memoria: llegado al tope se deja de
 * afinar la curva, pero **los puntos de control se emiten todos y exactos** aunque
 * el tope se quede corto — el trazo puede salir basto, nunca mentir por dónde pasa.
 *
 * Con dos puntos sale la recta: dos muestras y ni una más, porque la parada de
 * planitud corta a la primera. Con uno, esa única muestra; con cero, nada.
 */
fun espinaCentripeta(
    puntos: List<Pt3>,
    esquinas: IntArray = IntArray(0),
    toleranciaEnMundo: Double,
    anguloTope: Double = 0.2,
    topeDeMuestras: Int = 4096
): Espina {
    val n = puntos.size
    val obra = EspinaEnObra(n, topeDeMuestras)
    if (n == 0) return obra.termina()
    if (n == 1) {
        // Un punto no tiene dirección: la tangente sale del arreglo de la obra.
        val p = puntos[0]
        obra.emiteControl(p.x, p.y, p.z, 0.0, 0.0, 0.0, 0, 0.0)
        return obra.termina()
    }

    // Las esquinas, a un vector de marcas para mirarlas en O(1). Los extremos ya
    // van «partidos» de serie —se duplican abajo—, así que marcarlos no añade nada,
    // y un índice fuera de rango se ignora en vez de tumbar el trazo entero.
    val esEsquina = BooleanArray(n)
    for (i in esquinas) if (i in 1 until n - 1) esEsquina[i] = true

    // Sin tolerancia no hay parada de planitud que valga: se acota por abajo para
    // que un cero (o un negativo) no deje la subdivisión colgando del puro límite
    // de recursión en cada tramo.
    val tol = if (toleranciaEnMundo > TOLERANCIA_MINIMA) toleranciaEnMundo else TOLERANCIA_MINIMA
    val tol2 = tol * tol

    var tramo: TramoCubico? = null
    for (i in 0 until n - 1) {
        val a = puntos[i]
        val b = puntos[i + 1]
        // Extremos y esquinas: el vecino que falta (o que no se quiere mirar) se
        // sustituye por el propio punto. La distancia nula que eso produce la
        // recogen las guardas de los nudos, igual que hace three.js con la punta.
        val antes = if (i == 0 || esEsquina[i]) a else puntos[i - 1]
        val despues = if (i == n - 2 || esEsquina[i + 1]) b else puntos[i + 2]
        tramo = TramoCubico(antes, a, b, despues)
        // El punto de control, tal cual vino: es la promesa de la espina. La
        // tangente es la de salida (m1), que en una esquina es la del lado que
        // empieza aquí; el lado que llega se lee en la muestra anterior.
        obra.emiteControl(a.x, a.y, a.z, tramo.m1x, tramo.m1y, tramo.m1z, i, 0.0)
        tramo.parte(obra, tol2, anguloTope, i)
    }
    // El último punto lo emite alguien: todos los demás los emitió «su» tramo al
    // empezar, y a este le toca al tramo final al acabar, con la tangente de
    // llegada (m2) y u = 1 para que interpolar por tramo clave el extremo.
    val fin = puntos[n - 1]
    obra.emiteControl(fin.x, fin.y, fin.z, tramo!!.m2x, tramo.m2y, tramo.m2z, n - 2, 1.0)
    return obra.termina()
}

/**
 * Reparte un valor por punto de control —presión, tiempo, lo que sea— entre las
 * muestras de la [espina], lineal dentro de cada tramo.
 *
 * Vive aquí y no en quien pinta porque la espina ya sabe **de qué tramo salió cada
 * muestra y en qué punto de él**: rehacer esa cuenta fuera sería buscar el tramo
 * por distancia, que es más caro y falla justo en las esquinas, donde dos tramos
 * comparten el mismo sitio del mundo.
 *
 * En `u = 0` y `u = 1` devuelve el valor del punto de control **exacto**, sin
 * redondeo de por medio: la presión con la que se apoyó y se levantó el lápiz es
 * de las cosas que se notan si se mueven.
 */
fun interpolaParalela(espina: Espina, porPunto: DoubleArray): DoubleArray {
    val salida = DoubleArray(espina.cuantas)
    if (porPunto.isEmpty()) return salida
    for (j in 0 until espina.cuantas) {
        val i = espina.deQueTramo[j].coerceIn(0, porPunto.size - 1)
        val siguiente = if (i + 1 < porPunto.size) i + 1 else i
        val u = espina.dondeEnElTramo[j]
        salida[j] = porPunto[i] * (1.0 - u) + porPunto[siguiente] * u
    }
    return salida
}

/**
 * Por debajo de esto, un nudo cuenta como distancia nula. Es la guarda de
 * `CatmullRomCurve3` de three.js (líneas 235–237): dos puntos repetidos darían un
 * `dt` cero y las tangentes se irían a infinito; en su lugar el `dt` malo copia al
 * bueno y la curva sigue como si el punto repetido no estorbara.
 */
private const val NUDO_CASI_NULO = 1e-4

/**
 * Hasta dónde se deja partir un tramo: el límite del AGG. A 32 niveles la cuerda
 * mide una parte en 2^32 del tramo — si a esa escala aún no es plana, lo que hay
 * debajo es ruido numérico, no curva.
 */
private const val PARTICIONES_TOPE = 32

/** Un cuadrado por debajo de esto es «no hay vector»: ni dirección ni ángulo que medir. */
private const val CASI_NADA = 1e-24

/** El suelo de la tolerancia. Solo protege de un cero o un negativo por descuido. */
private const val TOLERANCIA_MINIMA = 1e-9

/**
 * Un tramo `[a, b]` de la spline, ya con sus tangentes y su Bézier equivalente.
 *
 * Las tangentes salen de la forma no uniforme de three.js: se calculan en el
 * tiempo de los nudos y se reescalan con `dt1` para dejar el tramo parametrizado
 * en `[0, 1]`, que es donde viven la Hermite y la Bézier de aquí abajo.
 */
private class TramoCubico(antes: Pt3, a: Pt3, b: Pt3, despues: Pt3) {

    // Los extremos del tramo (p1 y p2 de la Hermite).
    val ax = a.x; val ay = a.y; val az = a.z
    val bx = b.x; val by = b.y; val bz = b.z

    // Las tangentes en los extremos (m1 y m2), y los dos mangos de la Bézier
    // equivalente: b1 = a + m1/3, b2 = b − m2/3. Se calculan una vez por tramo.
    val m1x: Double; val m1y: Double; val m1z: Double
    val m2x: Double; val m2y: Double; val m2z: Double
    private val c1x: Double; private val c1y: Double; private val c1z: Double
    private val c2x: Double; private val c2y: Double; private val c2z: Double

    init {
        // Nudos centrípetos: t avanza |Δp|^0.5 por tramo, o sea (|Δp|²)^0.25.
        var dt1 = Math.pow(cuadradoEntre(a, b), 0.25)
        var dt0 = Math.pow(cuadradoEntre(antes, a), 0.25)
        var dt2 = Math.pow(cuadradoEntre(b, despues), 0.25)
        // Las guardas de three.js, en su mismo orden: primero el tramo propio,
        // porque los vecinos degenerados copian de él.
        if (dt1 < NUDO_CASI_NULO) dt1 = 1.0
        if (dt0 < NUDO_CASI_NULO) dt0 = dt1
        if (dt2 < NUDO_CASI_NULO) dt2 = dt1

        m1x = tangenteDeSalida(antes.x, ax, bx, dt0, dt1)
        m1y = tangenteDeSalida(antes.y, ay, by, dt0, dt1)
        m1z = tangenteDeSalida(antes.z, az, bz, dt0, dt1)
        m2x = tangenteDeLlegada(ax, bx, despues.x, dt1, dt2)
        m2y = tangenteDeLlegada(ay, by, despues.y, dt1, dt2)
        m2z = tangenteDeLlegada(az, bz, despues.z, dt1, dt2)

        c1x = ax + m1x / 3.0; c1y = ay + m1y / 3.0; c1z = az + m1z / 3.0
        c2x = bx - m2x / 3.0; c2y = by - m2y / 3.0; c2z = bz - m2z / 3.0
    }

    /** La `t1` de `initNonuniformCatmullRom`, ya reescalada a `[0, 1]` con `dt1`. */
    private fun tangenteDeSalida(x0: Double, x1: Double, x2: Double, dt0: Double, dt1: Double): Double =
        ((x1 - x0) / dt0 - (x2 - x0) / (dt0 + dt1) + (x2 - x1) / dt1) * dt1

    /** Y la `t2`: la misma cuenta corrida un punto hacia delante. */
    private fun tangenteDeLlegada(x1: Double, x2: Double, x3: Double, dt1: Double, dt2: Double): Double =
        ((x2 - x1) / dt1 - (x3 - x1) / (dt1 + dt2) + (x3 - x2) / dt2) * dt1

    /** Parte el tramo entero y va soltando en [obra] las muestras que hagan falta. */
    fun parte(obra: EspinaEnObra, tol2: Double, anguloTope: Double, tramo: Int) {
        parteEntre(
            obra, ax, ay, az, c1x, c1y, c1z, c2x, c2y, c2z, bx, by, bz,
            0.0, 1.0, 0, tol2, anguloTope, tramo
        )
    }

    /**
     * De Casteljau con la parada del AGG, sobre el cacho de Bézier `[u0, u1]`.
     *
     * Los doce dobles sueltos no son gusto por el ladrillo: esta función corre
     * en el camino caliente del lápiz y así la recursión no crea ni un objeto.
     *
     * Se para cuando la cuerda ya vale por el cacho: planitud `(d2+d3)² ≤
     * tol²·|cuerda|²` —con `d2` y `d3` como los cruces del AGG, distancia por
     * largo de cuerda— **y además** giro del polígono de control por debajo de
     * [anguloTope], que es lo que caza los codos que caben en la tolerancia.
     * Cuando toca partir, la muestra del medio sale **de la curva de verdad**:
     * el punto medio de de Casteljau es exacto y la tangente se evalúa en la
     * derivada de la Hermite, no en la cuerda.
     */
    private fun parteEntre(
        obra: EspinaEnObra,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        x3: Double, y3: Double, z3: Double,
        u0: Double, u1: Double, hondura: Int,
        tol2: Double, anguloTope: Double, tramo: Int
    ) {
        val cx = x3 - x0; val cy = y3 - y0; val cz = z3 - z0
        val cuerda2 = cx * cx + cy * cy + cz * cz
        if (cuerda2 > CASI_NADA) {
            val d2 = normaDelCruce(x1 - x0, y1 - y0, z1 - z0, cx, cy, cz)
            val d3 = normaDelCruce(x2 - x0, y2 - y0, z2 - z0, cx, cy, cz)
            if ((d2 + d3) * (d2 + d3) <= tol2 * cuerda2) {
                val giro = angulo(x1 - x0, y1 - y0, z1 - z0, x2 - x1, y2 - y1, z2 - z1) +
                    angulo(x2 - x1, y2 - y1, z2 - z1, x3 - x2, y3 - y2, z3 - z2)
                if (giro < anguloTope) return
            }
        } else {
            // La cuerda no mide nada: o el cacho es un punto, o va y vuelve. Si
            // los mangos también caben en la tolerancia es un punto y se acabó;
            // si no, hay una vuelta escondida y hay que seguir partiendo.
            val brazo1 = cuadrado(x1 - x0, y1 - y0, z1 - z0)
            val brazo2 = cuadrado(x2 - x3, y2 - y3, z2 - z3)
            if (brazo1 <= tol2 && brazo2 <= tol2) return
        }
        if (hondura >= PARTICIONES_TOPE || !obra.quedaSitioParaCurva()) return

        // De Casteljau al medio: cada nivel es media suma, y el del centro cae
        // en la curva exacta.
        val x01 = (x0 + x1) * 0.5; val y01 = (y0 + y1) * 0.5; val z01 = (z0 + z1) * 0.5
        val x12 = (x1 + x2) * 0.5; val y12 = (y1 + y2) * 0.5; val z12 = (z1 + z2) * 0.5
        val x23 = (x2 + x3) * 0.5; val y23 = (y2 + y3) * 0.5; val z23 = (z2 + z3) * 0.5
        val x012 = (x01 + x12) * 0.5; val y012 = (y01 + y12) * 0.5; val z012 = (z01 + z12) * 0.5
        val x123 = (x12 + x23) * 0.5; val y123 = (y12 + y23) * 0.5; val z123 = (z12 + z23) * 0.5
        val xm = (x012 + x123) * 0.5; val ym = (y012 + y123) * 0.5; val zm = (z012 + z123) * 0.5
        val um = (u0 + u1) * 0.5

        parteEntre(obra, x0, y0, z0, x01, y01, z01, x012, y012, z012, xm, ym, zm, u0, um, hondura + 1, tol2, anguloTope, tramo)
        // La derivada de la Hermite en um: h00' = 6u²−6u, h10' = 3u²−4u+1,
        // h01' = −h00', h11' = 3u²−2u.
        val d00 = 6.0 * um * um - 6.0 * um
        val d10 = 3.0 * um * um - 4.0 * um + 1.0
        val d11 = 3.0 * um * um - 2.0 * um
        obra.emiteDeCurva(
            xm, ym, zm,
            ax * d00 + m1x * d10 - bx * d00 + m2x * d11,
            ay * d00 + m1y * d10 - by * d00 + m2y * d11,
            az * d00 + m1z * d10 - bz * d00 + m2z * d11,
            tramo, um
        )
        parteEntre(obra, xm, ym, zm, x123, y123, z123, x23, y23, z23, x3, y3, z3, um, u1, hondura + 1, tol2, anguloTope, tramo)
    }
}

/**
 * La espina a medio hacer: las muestras van cayendo aquí, en orden y sin cajas.
 *
 * Arreglos planos que crecen doblando, y no listas de objetos, por lo de siempre:
 * esto se rehace mientras el dedo dibuja y el recolector no tiene por qué
 * enterarse. No se reserva el tope de golpe porque el trazo corriente usa una
 * fracción de él, y quien pida un tope descomunal no debe pagar la reserva.
 */
private class EspinaEnObra(puntosDeControl: Int, topeDeMuestras: Int) {

    /**
     * El sitio que queda para muestras de afinado. Los puntos de control no lo
     * gastan: ellos se emiten siempre, porque la espina promete pasar por todos.
     */
    private var sitioParaCurva = maxOf(0, topeDeMuestras - puntosDeControl)

    private var xyz = DoubleArray(CAPACIDAD_DE_SALIDA * 3)
    private var tangentes = DoubleArray(CAPACIDAD_DE_SALIDA * 3)
    private var deQueTramo = IntArray(CAPACIDAD_DE_SALIDA)
    private var dondeEnElTramo = DoubleArray(CAPACIDAD_DE_SALIDA)
    private var recorrido = DoubleArray(CAPACIDAD_DE_SALIDA)
    private var cuantas = 0

    fun quedaSitioParaCurva(): Boolean = sitioParaCurva > 0

    /** Una muestra de punto de control: entra siempre, con sus coordenadas tal cual. */
    fun emiteControl(x: Double, y: Double, z: Double, tx: Double, ty: Double, tz: Double, tramo: Int, u: Double) {
        emite(x, y, z, tx, ty, tz, tramo, u)
    }

    /** Una muestra de afinado: entra si queda sitio, y si no, la curva sale más basta. */
    fun emiteDeCurva(x: Double, y: Double, z: Double, tx: Double, ty: Double, tz: Double, tramo: Int, u: Double) {
        if (sitioParaCurva <= 0) return
        sitioParaCurva--
        emite(x, y, z, tx, ty, tz, tramo, u)
    }

    private fun emite(x: Double, y: Double, z: Double, tx: Double, ty: Double, tz: Double, tramo: Int, u: Double) {
        if (cuantas * 3 == xyz.size) crece()
        val j = cuantas * 3

        xyz[j] = x; xyz[j + 1] = y; xyz[j + 2] = z

        // La tangente sale unitaria o no sale: un vector a medias envenena cada
        // producto escalar de quien pinte con esto. Sin dirección propia —puntos
        // repetidos— se hereda la cuerda hasta aquí, luego la tangente anterior,
        // y en el peor de los casos (un trazo que es un punto) una fija: ninguna
        // dirección es la buena y cualquiera es mejor que un NaN.
        var nx = tx; var ny = ty; var nz = tz
        var norma2 = nx * nx + ny * ny + nz * nz
        if (norma2 <= CASI_NADA && cuantas > 0) {
            nx = x - xyz[j - 3]; ny = y - xyz[j - 2]; nz = z - xyz[j - 1]
            norma2 = nx * nx + ny * ny + nz * nz
            if (norma2 <= CASI_NADA) {
                nx = tangentes[j - 3]; ny = tangentes[j - 2]; nz = tangentes[j - 1]
                norma2 = 1.0
            }
        }
        if (norma2 > CASI_NADA) {
            val inversa = 1.0 / sqrt(norma2)
            tangentes[j] = nx * inversa; tangentes[j + 1] = ny * inversa; tangentes[j + 2] = nz * inversa
        } else {
            tangentes[j] = 1.0; tangentes[j + 1] = 0.0; tangentes[j + 2] = 0.0
        }

        deQueTramo[cuantas] = tramo
        dondeEnElTramo[cuantas] = u
        recorrido[cuantas] = if (cuantas == 0) 0.0 else {
            val dx = x - xyz[j - 3]; val dy = y - xyz[j - 2]; val dz = z - xyz[j - 1]
            recorrido[cuantas - 1] + sqrt(dx * dx + dy * dy + dz * dz)
        }
        cuantas++
    }

    private fun crece() {
        val sitio = maxOf(1, cuantas) * 2
        xyz = xyz.copyOf(sitio * 3)
        tangentes = tangentes.copyOf(sitio * 3)
        deQueTramo = deQueTramo.copyOf(sitio)
        dondeEnElTramo = dondeEnElTramo.copyOf(sitio)
        recorrido = recorrido.copyOf(sitio)
    }

    fun termina(): Espina = Espina(
        xyz.copyOf(cuantas * 3),
        tangentes.copyOf(cuantas * 3),
        deQueTramo.copyOf(cuantas),
        dondeEnElTramo.copyOf(cuantas),
        recorrido.copyOf(cuantas)
    )
}

/**
 * Por dónde empieza la obra. Un trazo corriente cabe con un par de crecidas;
 * reservar por el tope castigaría justo al caso normal.
 */
private const val CAPACIDAD_DE_SALIDA = 64

private fun cuadradoEntre(p: Pt3, q: Pt3): Double {
    val dx = q.x - p.x; val dy = q.y - p.y; val dz = q.z - p.z
    return dx * dx + dy * dy + dz * dz
}

private fun cuadrado(x: Double, y: Double, z: Double): Double = x * x + y * y + z * z

/** El largo del producto vectorial: distancia a la cuerda por largo de cuerda, como los `d` del AGG. */
private fun normaDelCruce(ux: Double, uy: Double, uz: Double, vx: Double, vy: Double, vz: Double): Double {
    val cx = uy * vz - uz * vy
    val cy = uz * vx - ux * vz
    val cz = ux * vy - uy * vx
    return sqrt(cx * cx + cy * cy + cz * cz)
}

/**
 * El ángulo entre dos vectores, en radianes y sin signo. Con `atan2(|u×v|, u·v)`
 * y no con `acos`, que pierde el pie cerca de 0 y de π justo donde más se mide.
 * Un vector sin largo no gira nada: devolver 0 deja que decida la planitud.
 */
private fun angulo(ux: Double, uy: Double, uz: Double, vx: Double, vy: Double, vz: Double): Double {
    if (cuadrado(ux, uy, uz) <= CASI_NADA || cuadrado(vx, vy, vz) <= CASI_NADA) return 0.0
    return atan2(normaDelCruce(ux, uy, uz, vx, vy, vz), ux * vx + uy * vy + uz * vz)
}
