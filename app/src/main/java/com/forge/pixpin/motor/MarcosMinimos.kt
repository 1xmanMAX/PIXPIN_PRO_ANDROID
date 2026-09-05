package com.forge.pixpin.motor

import kotlin.math.sqrt

/**
 * Marcos de rotación mínima a lo largo de una curva en el espacio.
 *
 * ## Por qué hace falta un marco
 *
 * Una cinta —o un tubo, o una hoja que acompaña al trazo— necesita en cada
 * muestra de la curva **un par de ejes perpendiculares al tangente**: uno que
 * diga «este es el ancho» y otro que diga «este es el canto». El tangente solo
 * no basta: alrededor de él la cinta puede estar girada como se quiera, y esa
 * elección, muestra a muestra, es el marco.
 *
 * ## Por qué no vale el de Frenet
 *
 * El marco de Frenet elige la normal hacia donde la curva se dobla, y eso lo
 * rompe dos veces: **en los tramos rectos no existe** (nada se dobla) y **en
 * las inflexiones pega un salto de media vuelta** (la curva pasa de doblarse
 * hacia un lado a doblarse hacia el otro). Una cinta con ese marco sale
 * retorcida como un lazo justo donde el trazo es más suave.
 *
 * ## Qué se hace en su lugar
 *
 * El marco de rotación mínima: el que **gira lo imprescindible** para seguir
 * al tangente y ni un grado más. Se calcula por **doble reflexión** —Wang,
 * Jüttler, Zheng y Liu, *Computation of Rotation Minimizing Frames*, 2008,
 * Tabla I al pie de la letra—, que lo aproxima con error a la **cuarta
 * potencia del paso**: partir el paso por dos deja dieciséis veces menos
 * deriva, cuando proyectar el marco de muestra en muestra solo la parte por
 * cuatro. La cuenta por muestra son dos reflexiones: una lleva el punto y el
 * marco al punto siguiente, la otra endereza el tangente reflejado sobre el
 * de verdad, y el giro de más se cancela solo.
 *
 * De regalo cae la paridad que importa aquí: en una **curva plana** el marco
 * deja un eje clavado en la normal del plano de punta a punta, que es
 * exactamente el marco con el que ya se dibujan las hojas planas en el lienzo
 * del espacio. Lo plano no cambia; lo torcido, por fin, no se retuerce.
 *
 * Todo va sobre [DoubleArray] planos y el bucle no asigna ni un objeto: esto
 * se recalcula mientras la mano dibuja, y una lluvia de objetos por muestra
 * es un tirón del recolector en mitad del trazo.
 */

/**
 * Los marcos de toda la curva, del primer punto al último.
 *
 * [xyz] son las muestras (`3n`: x, y, z por muestra) y [tangentes] sus
 * tangentes **unitarios** (`3n`). Devuelve `6n`: por muestra, primero `r`
 * (el eje de referencia, perpendicular al tangente) y luego `s = t × r`
 * (el tercer eje, que completa el triedro a derechas).
 *
 * [arranque] orienta el primer marco: se toma **su componente perpendicular
 * al primer tangente**, normalizada. Así el que llama puede pedir «empieza
 * con el canto hacia arriba» sin tener que fabricar él un vector exactamente
 * perpendicular. Si es nulo o casi paralelo al tangente —no queda componente
 * que valga— se siembra con `(0,0,1) × t`, y si el tangente apunta casi al
 * eje vertical, con `(0,1,0) × t`: siempre hay un arranque bien definido.
 */
fun marcosMinimos(xyz: DoubleArray, tangentes: DoubleArray, arranque: Pt3?): DoubleArray {
    val n = xyz.size / 3
    val marcos = DoubleArray(n * 6)
    if (n == 0) return marcos

    val tx = tangentes[0]
    val ty = tangentes[1]
    val tz = tangentes[2]

    // La siembra: el componente perpendicular del arranque, si lo hay.
    var rx = 0.0
    var ry = 0.0
    var rz = 0.0
    var sembrado = false
    if (arranque != null) {
        val aa = arranque.x * arranque.x + arranque.y * arranque.y + arranque.z * arranque.z
        if (aa > LARGO_NULO) {
            val at = arranque.x * tx + arranque.y * ty + arranque.z * tz
            val px = arranque.x - at * tx
            val py = arranque.y - at * ty
            val pz = arranque.z - at * tz
            val pp = px * px + py * py + pz * pz
            // Se compara contra el largo del propio arranque: lo que se mide
            // es el ángulo, no los metros, y así da igual la escala que traiga.
            if (pp > aa * CASI_PARALELO) {
                rx = px; ry = py; rz = pz
                sembrado = true
            }
        }
    }
    if (!sembrado) {
        // (0,0,1) × t: perpendicular al tangente y horizontal, que para una
        // cinta sin más señas es el canto que uno espera.
        rx = -ty; ry = tx; rz = 0.0
        if (rx * rx + ry * ry < CASI_PARALELO) {
            // El tangente apunta casi al eje vertical y el producto de arriba
            // se queda en nada: se cruza con (0,1,0), que ahí no degenera.
            rx = tz; ry = 0.0; rz = -tx
        }
    }
    val inv = 1.0 / sqrt(rx * rx + ry * ry + rz * rz)
    rx *= inv; ry *= inv; rz *= inv

    marcos[0] = rx
    marcos[1] = ry
    marcos[2] = rz
    marcos[3] = ty * rz - tz * ry
    marcos[4] = tz * rx - tx * rz
    marcos[5] = tx * ry - ty * rx

    propagarMarcos(xyz, tangentes, marcos, 0)
    return marcos
}

/**
 * Los marcos de una curva que ha crecido, **sin repetir lo ya calculado**.
 *
 * Es para la mano incremental: mientras se dibuja, cada muestra nueva llega
 * con las anteriores ya resueltas, y recalcular el trazo entero por cada
 * punto convierte el coste del gesto en cuadrático. Aquí se copian [previos]
 * hasta la muestra `desdeMuestra − 1` y se recalcula solo desde ahí,
 * sembrando con ese último marco estable.
 *
 * Da **exactamente lo mismo** —bit a bit, no «parecido»— que calcular todo
 * de nuevo partiendo de ese marco: la propagación es el mismo bucle sobre las
 * mismas posiciones, y hasta el renormalizado periódico cae en las mismas
 * muestras porque va por índice global y no por pasos dados. Si no fuera
 * idéntico, la cinta cambiaría de forma un pelo al levantar el dedo, cuando
 * el trazo pasa de incremental a entero.
 *
 * Si [desdeMuestra] no deja ningún marco que copiar, se calcula todo desde
 * cero con la siembra por defecto.
 */
fun extenderMarcos(
    xyz: DoubleArray,
    tangentes: DoubleArray,
    previos: DoubleArray,
    desdeMuestra: Int
): DoubleArray {
    val n = xyz.size / 3
    // No fiarse de que previos llegue hasta donde dice desdeMuestra: se copia
    // lo que de verdad hay, y de ahí para delante se calcula.
    val estables = minOf(desdeMuestra, previos.size / 6, n)
    if (estables < 1) return marcosMinimos(xyz, tangentes, null)

    val marcos = DoubleArray(n * 6)
    System.arraycopy(previos, 0, marcos, 0, estables * 6)
    propagarMarcos(xyz, tangentes, marcos, estables - 1)
    return marcos
}

/**
 * El bucle de la doble reflexión: rellena los marcos de `desde + 1` en
 * adelante, leyendo el marco de [desde] ya escrito en [marcos].
 *
 * Que [marcosMinimos] y [extenderMarcos] compartan este bucle no es ahorro
 * de líneas: es lo que **garantiza** que extender y recalcular den lo mismo.
 *
 * Todo son variables locales de coma flotante: ni un objeto por vuelta.
 */
private fun propagarMarcos(xyz: DoubleArray, tangentes: DoubleArray, marcos: DoubleArray, desde: Int) {
    val n = xyz.size / 3
    var rx = marcos[desde * 6]
    var ry = marcos[desde * 6 + 1]
    var rz = marcos[desde * 6 + 2]

    for (i in desde until n - 1) {
        val b0 = i * 3
        val b1 = b0 + 3
        val j = (i + 1) * 6

        // Primera reflexión: por el plano que parte en dos el segmento. Lleva
        // el marco de este punto al siguiente.
        val v1x = xyz[b1] - xyz[b0]
        val v1y = xyz[b1 + 1] - xyz[b0 + 1]
        val v1z = xyz[b1 + 2] - xyz[b0 + 2]
        val c1 = v1x * v1x + v1y * v1y + v1z * v1z
        if (c1 < LARGO_NULO) {
            // Punto repetido: el segmento no señala a ningún sitio y reflejar
            // por él sería reflejar por ruido. **El marco anterior, tal cual.**
            marcos[j] = marcos[j - 6]
            marcos[j + 1] = marcos[j - 5]
            marcos[j + 2] = marcos[j - 4]
            marcos[j + 3] = marcos[j - 3]
            marcos[j + 4] = marcos[j - 2]
            marcos[j + 5] = marcos[j - 1]
            continue
        }

        val tx = tangentes[b0]
        val ty = tangentes[b0 + 1]
        val tz = tangentes[b0 + 2]

        val a1 = 2.0 * (v1x * rx + v1y * ry + v1z * rz) / c1
        var rLx = rx - a1 * v1x
        var rLy = ry - a1 * v1y
        var rLz = rz - a1 * v1z

        val a2 = 2.0 * (v1x * tx + v1y * ty + v1z * tz) / c1
        val tLx = tx - a2 * v1x
        val tLy = ty - a2 * v1y
        val tLz = tz - a2 * v1z

        // Segunda reflexión: endereza el tangente reflejado sobre el de
        // verdad, y arrastra a r con él. El giro que la primera metió de más
        // se cancela aquí — de ahí el nombre de doble reflexión.
        val t1x = tangentes[b1]
        val t1y = tangentes[b1 + 1]
        val t1z = tangentes[b1 + 2]
        val v2x = t1x - tLx
        val v2y = t1y - tLy
        val v2z = t1z - tLz
        val c2 = v2x * v2x + v2y * v2y + v2z * v2z
        if (c2 >= LARGO_NULO) {
            val a3 = 2.0 * (v2x * rLx + v2y * rLy + v2z * rLz) / c2
            rLx -= a3 * v2x
            rLy -= a3 * v2y
            rLz -= a3 * v2z
        }
        // Y si c2 se queda en nada, el tangente reflejado ya ES el siguiente
        // —tramo recto— y no hay nada que enderezar: r se queda en rL.

        rx = rLx; ry = rLy; rz = rLz

        // Renormalizado periódico: cada reflexión conserva el largo salvo el
        // polvo de la coma flotante, y ese polvo, paso a paso, se acumula. Va
        // por **índice global** y no por pasos dados desde la siembra, para
        // que extender un trazo renormalice en las mismas muestras que
        // recalcularlo y los dos caminos den bit a bit lo mismo.
        if ((i + 1) % PASOS_ENTRE_RENORMALIZADOS == 0) {
            val inv = 1.0 / sqrt(rx * rx + ry * ry + rz * rz)
            rx *= inv; ry *= inv; rz *= inv
        }

        marcos[j] = rx
        marcos[j + 1] = ry
        marcos[j + 2] = rz
        marcos[j + 3] = t1y * rz - t1z * ry
        marcos[j + 4] = t1z * rx - t1x * rz
        marcos[j + 5] = t1x * ry - t1y * rx
    }
}

/**
 * Un largo al cuadrado por debajo de esto es **un vector que no existe**: son
 * menos de un billonésimo de unidad de largo, que es menos que el propio ruido
 * de restar dos coordenadas de pantalla en coma flotante. Se usa para cazar el
 * punto repetido y el tramo recto antes de dividir por ellos.
 */
private const val LARGO_NULO = 1e-24

/**
 * El seno al cuadrado por debajo del cual dos direcciones cuentan como la
 * misma. Un arranque a menos de este ángulo del tangente no trae componente
 * perpendicular de verdad: normalizarla sería **amplificar ruido a eje**, y
 * el marco entero colgaría de él.
 */
private const val CASI_PARALELO = 1e-12

/**
 * Cada cuántas muestras se renormaliza `r`. La deriva por paso anda en la
 * última cifra del `Double`, así que con corregir de tanto en tanto sobra;
 * cada paso sería pagar una raíz por muestra para limpiar un polvo que aún
 * no se ve.
 */
private const val PASOS_ENTRE_RENORMALIZADOS = 128
