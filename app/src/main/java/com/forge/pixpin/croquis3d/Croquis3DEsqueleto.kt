package com.forge.pixpin.croquis3d

import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.anchosDelMundo
import com.forge.pixpin.motor.espinaCentripeta
import com.forge.pixpin.motor.interpolaParalela
import com.forge.pixpin.motor.marcosMinimos

/**
 * **El esqueleto del trazo: todo lo caro, cocido una vez y EN EL MUNDO.**
 *
 * Es la pieza central del Motor Pluma. Un trazo con [Trazo3D.tiempos] deja de reconstruirse
 * en pantalla por fotograma: su espina suave, sus marcos, sus anchos y su recorrido se
 * calculan UNA vez —aquí— y viven en coordenadas del mundo. Por fotograma solo queda
 * proyectar, que es lo único que de verdad depende de la cámara. Eso es lo que hace que el
 * trazo se quede **quieto en el aire**: no hay nada suyo que se recalcule al orbitar.
 *
 * ## Por qué no hace falta reproyectar a la hoja curva
 *
 * El intocable dice «si se alisa, alisar en la superficie o reproyectar». Aquí se cumple por
 * geometría, no por consulta: los puntos de control ya viven SOBRE la hoja cada ~3px de
 * pantalla, y la flecha de una cuerda entre puntos así de juntos es c²/8R — sub-píxel incluso
 * con curvaturas agresivas. Lo único que despegaría la spline es un PLIEGUE (la arista viva de
 * una hoja doblada), y el trazo ya trae la marca: sus normales por punto giran fuerte justo
 * ahí. Donde giran, ese punto se declara **esquina querida**, el nudo se parte y la spline
 * pasa exacta por la arista abrazando cada cara. Hay una prueba que mide la adherencia sobre
 * un cilindro en vez de prometerla.
 */
class EsqueletoDelTrazo(
    /** Las muestras de la espina, empaquetadas: 3n dobles, en el mundo. */
    val xyz: DoubleArray,
    /** Sus tangentes unitarias: 3n. */
    val tangentes: DoubleArray,
    /**
     * El marco de cada muestra: 6n — r (la normal de apoyo) y s (el través), por muestra.
     *
     * Sobre una hoja, r ES la normal de la superficie interpolada: la cinta queda tumbada
     * en la hoja, que es el marco de siempre. Sin normales guardadas se transporta por
     * rotación mínima — y en hoja plana las dos cuentas coinciden exactamente.
     */
    val marcos: DoubleArray,
    /** El ancho de cada muestra, EN UNIDADES DE MUNDO — jamás de puntos proyectados. */
    val anchos: DoubleArray,
    /** El largo recorrido hasta cada muestra, en el mundo. */
    val recorrido: DoubleArray,
    /** La presión por muestra, interpolada, para los materiales que la quieran. */
    val presiones: DoubleArray?
) {
    val cuantas: Int get() = xyz.size / 3
}

/**
 * Cuece el esqueleto de [trazo], o null si el trazo es del régimen viejo.
 *
 * [nivel] es la densidad: 0 usa todos los puntos de control; k se queda con uno de cada 2^k
 * **conservando siempre el primero, el último y las esquinas queridas** — subconjuntos
 * anidados, así que subir de nivel nunca inventa puntos que el nivel fino no tuviera.
 */
fun cocerEsqueleto(trazo: Trazo3D, nivel: Int = 0): EsqueletoDelTrazo? {
    // **El discriminador**: sin tiempos o sin calibre, el trazo es del régimen viejo y se
    // pinta como se dibujó. Ver [Trazo3D.tiempos].
    if (trazo.tiempos == null || trazo.calibre == null) return null
    val crudos = trazo.puntos
    if (crudos.size < 2) return null

    val esquinasCrudas = esquinasQueridas(crudos, trazo.normales)

    // El nivel: subconjunto anidado de los puntos de control.
    val indices: IntArray = if (nivel <= 0) {
        IntArray(crudos.size) { it }
    } else {
        val paso = 1 shl nivel
        val quedan = sortedSetOf(0, crudos.size - 1)
        for (i in crudos.indices step paso) quedan.add(i)
        for (e in esquinasCrudas) quedan.add(e)
        quedan.toIntArray()
    }
    val puntos = indices.map { crudos[it] }
    val dondeQuedaron = HashMap<Int, Int>(indices.size)
    indices.forEachIndexed { nuevo, viejo -> dondeQuedaron[viejo] = nuevo }
    val esquinas = esquinasCrudas.mapNotNull { dondeQuedaron[it] }.toIntArray()

    // La espina: tolerancia relativa al ancho visible — el error queda por debajo del 2%
    // del propio trazo a cualquier zoom, sin re-muestrear jamás por la vista.
    // **El nivel sube la tolerancia, no solo aclara puntos.** La flecha del error escala
    // con el cuadrado del paso, así que cada nivel multiplica la tolerancia por cuatro:
    // ahí está la ganancia real del detalle bajo — menos muestras, no solo menos tramos.
    val tolerancia = trazo.calibre * TOLERANCIA_DEL_COCIDO * (1 shl (2 * nivel.coerceIn(0, 4)))
    val espina = espinaCentripeta(
        puntos,
        esquinas = esquinas,
        toleranciaEnMundo = tolerancia.coerceAtLeast(1e-9)
    )
    val n = espina.cuantas
    if (n == 0) return null

    // Las listas paralelas, llevadas a las muestras por la MISMA espina.
    val presionesM = trazo.presiones
        ?.takeIf { it.size == crudos.size }
        ?.let { todas -> interpolaParalela(espina, DoubleArray(indices.size) { todas[indices[it]] }) }
    val tiemposM = trazo.tiempos
        .takeIf { it.size == crudos.size }
        ?.let { todos -> interpolaParalela(espina, DoubleArray(indices.size) { todos[indices[it]] }) }

    // **Solo la redonda obedece al pulso** — decisión de siempre, ahora en el mundo.
    val anchos =
        if (trazo.pincel == Pincel.REDONDO && presionesM != null) {
            anchosDelMundo(trazo.calibre, presionesM, espina.recorrido, tiemposM)
        } else {
            DoubleArray(n) { trazo.calibre }
        }

    val marcos = marcosDelTrazo(trazo, espina.xyz, espina.tangentes, indices, espina)

    // **La redonda acaba en domo, no en disco.** El barrido cierra cada punta con un
    // polígono plano — correcto para una caja, que acaba en cara, y equivocado para un
    // tubo: el disco aparecía de golpe al cruzar el ángulo y se veía como «un contorno
    // circular plano pegado a un borde». El domo son tres muestras más por punta —el
    // cuarto de circunferencia, con el ancho cayendo en coseno hasta cero— puestas AQUÍ,
    // en el mundo: se sombrean continuas con las mismas tiras del barrido, sin tapa, sin
    // aparición súbita, y tan clavadas al mundo como el resto del trazo.
    if (trazo.pincel == Pincel.REDONDO || trazo.pincel == Pincel.LUZ) {
        return conDomos(
            espina.xyz, espina.tangentes, marcos, anchos, espina.recorrido, presionesM,
            trazo.punta.ancho.coerceIn(0.2, 4.0)
        )
    }

    return EsqueletoDelTrazo(
        xyz = espina.xyz,
        tangentes = espina.tangentes,
        marcos = marcos,
        anchos = anchos,
        recorrido = espina.recorrido,
        presiones = presionesM
    )
}

/** Cuántas muestras añade cada domo y a qué alturas del cuarto de circunferencia. */
private val SENOS_DEL_DOMO = doubleArrayOf(0.5, 0.87, 1.0)
private val COSENOS_DEL_DOMO = doubleArrayOf(0.87, 0.5, 1e-4)

/**
 * El esqueleto con sus dos domos puestos: tres muestras por punta, saliendo por la
 * tangente del extremo con el marco del extremo, el ancho en coseno y el avance en seno.
 *
 * [delAncho] es el mismo factor de la punta que aplica el pintor: sin él, el domo de una
 * punta engordada saldría corto y el de una afinada, largo.
 */
private fun conDomos(
    xyz: DoubleArray,
    tangentes: DoubleArray,
    marcos: DoubleArray,
    anchos: DoubleArray,
    recorrido: DoubleArray,
    presiones: DoubleArray?,
    delAncho: Double
): EsqueletoDelTrazo {
    val n = anchos.size
    val extra = SENOS_DEL_DOMO.size
    val m = n + 2 * extra
    val xyz2 = DoubleArray(3 * m)
    val tan2 = DoubleArray(3 * m)
    val mar2 = DoubleArray(6 * m)
    val anc2 = DoubleArray(m)
    val rec2 = DoubleArray(m)
    val pre2 = if (presiones != null) DoubleArray(m) else null

    fun copiaMuestra(deIdx: Int, aIdx: Int) {
        System.arraycopy(xyz, 3 * deIdx, xyz2, 3 * aIdx, 3)
        System.arraycopy(tangentes, 3 * deIdx, tan2, 3 * aIdx, 3)
        System.arraycopy(marcos, 6 * deIdx, mar2, 6 * aIdx, 6)
    }

    fun domo(extremoIdx: Int, haciaFuera: Double, destino: (Int) -> Int) {
        val radio = anchos[extremoIdx] * delAncho / 2
        val tx = tangentes[3 * extremoIdx] * haciaFuera
        val ty = tangentes[3 * extremoIdx + 1] * haciaFuera
        val tz = tangentes[3 * extremoIdx + 2] * haciaFuera
        for (d in 0 until extra) {
            val a = destino(d)
            copiaMuestra(extremoIdx, a)
            val avance = radio * SENOS_DEL_DOMO[d]
            xyz2[3 * a] = xyz[3 * extremoIdx] + tx * avance
            xyz2[3 * a + 1] = xyz[3 * extremoIdx + 1] + ty * avance
            xyz2[3 * a + 2] = xyz[3 * extremoIdx + 2] + tz * avance
            anc2[a] = anchos[extremoIdx] * COSENOS_DEL_DOMO[d]
            pre2?.set(a, presiones!![extremoIdx])
        }
    }

    // El cuerpo, tal cual, corrido tres sitios.
    for (i in 0 until n) {
        copiaMuestra(i, i + extra)
        anc2[i + extra] = anchos[i]
        pre2?.set(i + extra, presiones!![i])
    }
    // El domo de arranque va DEL VÉRTICE HACIA EL CUERPO (las muestras crecen a lo largo
    // del trazo), y el de la punta, del cuerpo hacia el vértice.
    domo(0, -1.0) { d -> extra - 1 - d }
    domo(n - 1, +1.0) { d -> n + extra + d }

    // El recorrido sigue monótono: los avances del domo se suman por su lado.
    rec2[extra] = 0.0
    for (i in 1 until n) rec2[i + extra] = recorrido[i]
    val radio0 = anchos[0] * delAncho / 2
    for (d in 0 until extra) {
        rec2[extra - 1 - d] = -radio0 * SENOS_DEL_DOMO[d]
    }
    val radioN = anchos[n - 1] * delAncho / 2
    for (d in 0 until extra) {
        rec2[n + extra + d] = recorrido[n - 1] + radioN * SENOS_DEL_DOMO[d]
    }
    // Y sin números negativos: se corre todo para que el primero sea cero.
    val base = rec2[0]
    for (i in 0 until m) rec2[i] -= base

    return EsqueletoDelTrazo(xyz2, tan2, mar2, anc2, rec2, pre2)
}

/**
 * **Las esquinas que alguien quiso**: los quiebros del propio trazo y los pliegues de la
 * hoja. En ellas el nudo se parte y la spline no redondea — «una esquina querida sigue
 * esquina» es intocable, y el suavizador tiene que saber dónde están.
 */
private fun esquinasQueridas(puntos: List<Pt3>, normales: List<Pt3>?): List<Int> {
    val salida = ArrayList<Int>(4)
    // Quiebros del trazo: el ángulo entre el segmento que llega y el que sale.
    for (i in 1 until puntos.size - 1) {
        val ax = puntos[i].x - puntos[i - 1].x
        val ay = puntos[i].y - puntos[i - 1].y
        val az = puntos[i].z - puntos[i - 1].z
        val bx = puntos[i + 1].x - puntos[i].x
        val by = puntos[i + 1].y - puntos[i].y
        val bz = puntos[i + 1].z - puntos[i].z
        val la = Math.sqrt(ax * ax + ay * ay + az * az)
        val lb = Math.sqrt(bx * bx + by * by + bz * bz)
        if (la < 1e-12 || lb < 1e-12) continue
        val cos = (ax * bx + ay * by + az * bz) / (la * lb)
        if (cos < COSENO_DE_ESQUINA) salida.add(i)
    }
    // Pliegues de la hoja: donde la normal guardada gira fuerte, la superficie se dobla y
    // la spline no puede cortar la arista. Se marcan los dos lados del giro.
    if (normales != null && normales.size == puntos.size) {
        for (i in 1 until normales.size) {
            val a = normales[i - 1]
            val b = normales[i]
            val cos = a.x * b.x + a.y * b.y + a.z * b.z
            if (cos < COSENO_DE_PLIEGUE) {
                salida.add(i - 1)
                salida.add(i)
            }
        }
    }
    return salida.distinct()
}

/**
 * El marco de cada muestra.
 *
 * Con normales por punto (hoja curva), el marco natural es el de la superficie: la normal
 * interpolada, enderezada contra la tangente. Con una sola normal (hoja plana), la misma
 * cuenta con la normal constante. Sin ninguna —no debería pasar hoy—, rotación mínima por
 * doble reflexión, que en el plano coincide con lo anterior.
 */
private fun marcosDelTrazo(
    trazo: Trazo3D,
    xyz: DoubleArray,
    tangentes: DoubleArray,
    indices: IntArray,
    espina: com.forge.pixpin.motor.Espina
): DoubleArray {
    val normales = trazo.normales?.takeIf { it.size == trazo.puntos.size }
    if (normales != null) {
        // Cada componente de la normal viaja a las muestras por la misma espina y se
        // renormaliza: entre dos normales de la hoja el giro es pequeño y la interpolación
        // lineal renormalizada es la de siempre para eso.
        val nx = interpolaParalela(espina, DoubleArray(indices.size) { normales[indices[it]].x })
        val ny = interpolaParalela(espina, DoubleArray(indices.size) { normales[indices[it]].y })
        val nz = interpolaParalela(espina, DoubleArray(indices.size) { normales[indices[it]].z })
        return marcosDeNormales(xyz, tangentes) { i, salida ->
            salida[0] = nx[i]; salida[1] = ny[i]; salida[2] = nz[i]
        }
    }
    val fija = trazo.normal
    if (fija != null) {
        return marcosDeNormales(xyz, tangentes) { _, salida ->
            salida[0] = fija.x; salida[1] = fija.y; salida[2] = fija.z
        }
    }
    return marcosMinimos(xyz, tangentes, trazo.ejeDeLaPunta)
}

/** Endereza una normal por muestra contra su tangente y arma r y s. */
private inline fun marcosDeNormales(
    xyz: DoubleArray,
    tangentes: DoubleArray,
    normalDe: (Int, DoubleArray) -> Unit
): DoubleArray {
    val n = xyz.size / 3
    val marcos = DoubleArray(6 * n)
    val cruda = DoubleArray(3)
    var rxAnt = 0.0
    var ryAnt = 0.0
    var rzAnt = 1.0
    for (i in 0 until n) {
        normalDe(i, cruda)
        val tx = tangentes[3 * i]
        val ty = tangentes[3 * i + 1]
        val tz = tangentes[3 * i + 2]
        // La componente de la normal perpendicular a la tangente: r = n − (n·t)t.
        val punto = cruda[0] * tx + cruda[1] * ty + cruda[2] * tz
        var rx = cruda[0] - punto * tx
        var ry = cruda[1] - punto * ty
        var rz = cruda[2] - punto * tz
        val largo = Math.sqrt(rx * rx + ry * ry + rz * rz)
        if (largo < 1e-9) {
            // La normal cayó paralela a la tangente (degenerado): se arrastra el marco
            // anterior, que es lo que hace también la doble reflexión en su caso raro.
            rx = rxAnt; ry = ryAnt; rz = rzAnt
        } else {
            rx /= largo; ry /= largo; rz /= largo
        }
        marcos[6 * i] = rx
        marcos[6 * i + 1] = ry
        marcos[6 * i + 2] = rz
        // s = t × r: el través de la cinta.
        marcos[6 * i + 3] = ty * rz - tz * ry
        marcos[6 * i + 4] = tz * rx - tx * rz
        marcos[6 * i + 5] = tx * ry - ty * rx
        rxAnt = rx; ryAnt = ry; rzAnt = rz
    }
    return marcos
}

/**
 * **El armario de los esqueletos**: cocido una vez, servido mil.
 *
 * LRU de [CUANTOS_CABEN] — dimensionado POR ENCIMA de los trazos que caben en una escena
 * visible, porque un armario más pequeño que el barrido que lo recorre es el peor caso
 * clásico: fallo en cada acceso y recocción completa por fotograma. La llave lleva TODO lo
 * que decide la forma —las identidades de las cuatro listas y los campos de pincel— y la
 * cámara JAMÁS: con la vista en la llave, orbitar recocería todo y el motor moriría de
 * origen. Invalidación: ninguna que recordar — los trazos son inmutables (editar uno es
 * sustituirlo entero) y el LRU tira solo lo que ya no se mira.
 */
object ElArmarioDeLosEsqueletos {

    /** La identidad de una lista, no su contenido: comparar mil puntos por acceso es caro. */
    private class QuienEs(val de: Any?) {
        override fun hashCode(): Int = System.identityHashCode(de)
        override fun equals(other: Any?): Boolean = other is QuienEs && other.de === de
    }

    private class Llave(
        val puntos: QuienEs,
        val presiones: QuienEs,
        val normales: QuienEs,
        val tiempos: QuienEs,
        val calibre: Double,
        val pincel: Pincel,
        val nivel: Int
    ) {
        override fun hashCode(): Int {
            var h = puntos.hashCode()
            h = h * 31 + presiones.hashCode()
            h = h * 31 + normales.hashCode()
            h = h * 31 + tiempos.hashCode()
            h = h * 31 + calibre.hashCode()
            h = h * 31 + pincel.ordinal
            h = h * 31 + nivel
            return h
        }

        override fun equals(other: Any?): Boolean =
            other is Llave && other.puntos == puntos && other.presiones == presiones &&
                other.normales == normales && other.tiempos == tiempos &&
                other.calibre == calibre && other.pincel == pincel && other.nivel == nivel
    }

    private val guardados = object : LinkedHashMap<Llave, EsqueletoDelTrazo>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Llave, EsqueletoDelTrazo>
        ): Boolean = size > CUANTOS_CABEN
    }

    /**
     * El esqueleto de [trazo], del armario o recién cocido; null si el trazo es del régimen
     * viejo. Con cerrojo porque exportar entra desde otro hilo, igual que las caras del
     * sólido del motor plano.
     */
    @Synchronized
    fun de(trazo: Trazo3D, nivel: Int = 0): EsqueletoDelTrazo? {
        if (trazo.tiempos == null || trazo.calibre == null) return null
        val llave = Llave(
            QuienEs(trazo.puntos), QuienEs(trazo.presiones),
            QuienEs(trazo.normales), QuienEs(trazo.tiempos),
            trazo.calibre, trazo.pincel, nivel
        )
        guardados[llave]?.let { return it }
        val cocido = cocerEsqueleto(trazo, nivel) ?: return null
        guardados[llave] = cocido
        return cocido
    }

    /** Solo para las pruebas: un armario con restos engaña a la de «no cuece dos veces». */
    @Synchronized
    internal fun vaciar() {
        guardados.clear()
    }

    /** Trazos visibles de sobra en una escena grande, y aire: nunca menor que el barrido. */
    private const val CUANTOS_CABEN = 640
}

/**
 * El nivel de detalle de un trazo, **por su ancho en pantalla y nada más**.
 *
 * Sale de calibre×zoom — lo gordo que se VE el trazo — cuantizado a tres escalones. El zoom
 * es la única entrada legítima: la ocupación proyectada cambia con el ángulo de órbita y
 * elegir por ella devolvería la dependencia de cámara que este motor vino a matar.
 */
fun nivelDePluma(calibre: Double?, zoom: Double): Int {
    val px = (calibre ?: return 0) * zoom
    return when {
        px >= 8.0 -> 0
        px >= 2.5 -> 1
        else -> 2
    }
}

/** El error de la espina relativo al ancho del trazo: 2% del calibre, invisible. */
private const val TOLERANCIA_DEL_COCIDO = 0.02

/** Un quiebro más cerrado que esto (≈60°) es una esquina que alguien quiso. */
private const val COSENO_DE_ESQUINA = 0.5

/** Una normal que gira más que esto (≈25°) entre dos puntos es un pliegue de la hoja. */
private const val COSENO_DE_PLIEGUE = 0.9
