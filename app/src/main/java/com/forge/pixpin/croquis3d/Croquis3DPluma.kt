package com.forge.pixpin.croquis3d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.forge.pixpin.motor.Pt3
import com.forge.pixpin.motor.parseColor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * **El pintor del Motor Pluma: trazos fijos al mundo.**
 *
 * Es el pintado del régimen nuevo —los trazos con [Trazo3D.tiempos]— y su regla es una
 * sola: **nada del trazo depende de la cámara**. La forma vive en el esqueleto (cocido una
 * vez, en el mundo: ver [EsqueletoDelTrazo]), el ancho es de mundo y se convierte a pantalla
 * multiplicando por la escala de la vista, y la luz es una dirección **del mundo**: orbitar
 * no cambia ni la forma, ni el gordo, ni de qué lado está el tono. Es la diferencia entre
 * mirar una cosa que está ahí y mirar un dibujo que se rehace para cada mirada.
 *
 * Lo único que aquí sabe de la pantalla es el **clamp de pintado**: una raya que quedaría en
 * menos de un píxel se pinta con un pelo de ancho para que siga estando. Ese mínimo no
 * vuelve jamás al mundo —es maquillaje del fotograma, no una medida— y por eso se aplica al
 * final del todo, sobre píxeles ya convertidos.
 *
 * Por fotograma el trabajo es: proyectar el esqueleto, medir los anchos en píxeles, sacar
 * los dos rieles de la cinta, cuantizar el tono contra la luz y rellenar por tramos. Todo
 * sobre los mismos arrays reutilizados ([BorradorDePluma]): **cero asignaciones por
 * muestra**, que es la norma de la casa — lo que se asigna por muestra no se ve como
 * memoria, se ve como el tirón al girar.
 */

/**
 * La luz del croquis, fija al MUNDO. Si el croquis tiene sol, la suya; si no, la del aire.
 *
 * Devuelve la dirección **hacia** la luz, unitaria: es lo que quiere un lambert, y tenerlo
 * decidido aquí evita que cada tinta se invente su convenio de signos. Con sol puesto es
 * su rayo cambiado de signo —el rayo viaja del sol al suelo ([Sol3D.rayo]) y la luz se
 * mira al revés—, así que mover el sol con el dedo mueve también el tono de los trazos,
 * que es exactamente lo que uno espera de mover el sol.
 */
fun luzDelMundo(croquis: Croquis): Pt3 {
    val sol = croquis.sol ?: return LUZ_DEL_AIRE
    val rayo = sol.rayo
    return Pt3(-rayo.x, -rayo.y, -rayo.z)
}

/**
 * **La luz del aire**: la que hay cuando nadie ha encendido el sol.
 *
 * Arriba sobre todo y un poco de lado, como el foco de un estudio: cayendo de arriba el
 * ojo lee el tono como volumen —con la luz de abajo lo abultado se ve hundido—, y el poco
 * de lado (a la derecha y hacia el que mira, que de fábrica mira desde `-y`) es para que
 * ni las caras horizontales ni las que dan de frente se queden sin tono: con la luz
 * clavada en la vertical, todo lo tumbado empata en el escalón más claro y el sombreado
 * no dice nada. Fija **en el mundo** a propósito: girar la vista no puede mover la luz,
 * o el tono nadaría por el trazo al orbitar y nada parecería quieto.
 */
private val LUZ_DEL_AIRE: Pt3 = normalizado(Pt3(0.35, -0.25, 0.9))

/**
 * **Pinta un trazo del régimen nuevo**, o devuelve `false` si este pincel aún no va por
 * aquí y tiene que caer al camino viejo.
 *
 * Los cubiertos: la punta redonda y la luz como **silueta analítica del tubo** (la
 * perpendicular en pantalla del eje proyectado: para un cilindro bajo ortográfica es
 * exacta, porque la simetría de revolución hace que el contorno sea el eje ± radio), y el
 * listón, el rodillo y la cuchilla como **cinta fija al mundo** (los dos rieles se
 * calculan en el mundo con el marco del esqueleto y se proyectan tal cual, así que la
 * cinta se escorza y se pone de canto como una raya de pintura de verdad).
 *
 * Los que devuelven `false`, y por qué: la sección dibujada a mano ([PuntaDelPincel.perfil],
 * que es un barrido de figura propia), la marca estampada ([PuntaDelPincel.dibujo]), la
 * tinta tramada ([PuntaDelPincel.trama]) y la punta de canto ([PuntaDelPincel.deCanto])
 * tienen geometría propia que el camino viejo ya sabe hacer y esta pluma todavía no; y el
 * material encendido sobre pinceles que no son la luz ([PuntaDelPincel.alumbra]) lleva su
 * resplandor y su filamento, que tampoco están aquí. Con lente puesta, un trazo con alguna
 * muestra **detrás del ojo** también cae al camino viejo: ahí la proyección envuelve y el
 * ancho por muestra deja de significar nada (ver [Camara3D.ojo]).
 *
 * [moviendo] ya no apaga el tono —eso se veía, y lo que se leía era que el croquis se
 * aplanaba al girarlo—; lo que queda de él es lo que no se nota. Degrada **tonos, jamás
 * geometría**: la forma y el ancho son los mismos
 * quieto que girando —si no, el trazo respiraría al soltar el dedo— y lo único que se
 * ahorra es la cuantización del sombreado, que pasa a un solo escalón (el del medio, que
 * es el color elegido tal cual: así al soltar aparecen los tonos alrededor del color y no
 * un salto de claridad).
 */
fun DrawScope.pintarTrazoPluma(
    trazo: Trazo3D,
    esqueleto: EsqueletoDelTrazo,
    base: BaseDeCamara,
    luz: Pt3,
    moviendo: Boolean
): Boolean {
    val n = esqueleto.cuantas
    // Con menos de dos muestras no hay cinta que armar: el punto suelto lo pinta el camino
    // viejo, que ya sabe hacer de un trazo un círculo.
    if (n < 2) return false

    val punta = trazo.punta
    // **La trama ya no cae al camino viejo.** Caía, y por eso una tinta con textura se
    // dibujaba «de otra forma» que la lisa: otro cuerpo, otra silueta y otro grano (lo
    // reportó el usuario el 6-sep-2026). Ahora el cuerpo es EL MISMO que el de la lisa —la
    // sección barrida— y el grano se estampa encima. Ver [estamparTrama].
    if (punta.tienePerfil || punta.tieneDibujo || punta.deCanto) {
        return false
    }
    val esLuz = trazo.pincel == Pincel.LUZ
    if (punta.alumbra && !esLuz) return false

    val b = BorradorDePluma
    b.listo(n)

    // El clamp de pintado, en píxeles de esta pantalla. **Solo pintado**: jamás vuelve al
    // mundo, o alejarse engordaría los trazos de verdad y acercarse los encontraría gordos.
    val minPx = MINIMO_EN_PANTALLA_DE_PLUMA.dp.toPx().toDouble()
    // El ancho de la punta multiplica al del esqueleto, como en el camino viejo: es el
    // mando de lo gordo que sale a igualdad de grosor elegido, y acotado igual que allí.
    val delAncho = punta.ancho.coerceIn(0.2, 4.0)

    // **El color, como lo hace el pintado actual**: el elegido con lo que tapa dentro del
    // alfa. La luz lleva dos colores —cristal apagado y gas encendido— y a media asta se
    // mezclan, que es lo que hace un tubo arrancando. Ver [Trazo3D.colorApagada].
    val alfa = (255 * trazo.opacidad).toInt().coerceIn(0, 255)
    val encendida = parseColor(trazo.color, alfa)
    val tinta = if (esLuz) {
        trazo.colorApagada?.let { apagada ->
            mezcladosDePluma(
                parseColor(apagada, alfa), encendida, trazo.luz.toFloat().coerceIn(0f, 1f)
            )
        } ?: encendida
    } else {
        encendida
    }

    // **Cuántos escalones de tono.** La luz es una fuente, no una cara: no tiene lambert y
    // va a tono pleno. Lo demás lleva sombra **siempre**, se esté moviendo la vista o no;
    // cuántos escalones lo decide lo que le esté costando al aparato. Ver
    // [LaCalidad.escalonesDeTono].
    val cuantos = if (esLuz) 1 else LaCalidad.escalonesDeTono
    if (cuantos == 1) {
        java.util.Arrays.fill(b.tonos, 0, n, 0)
    } else {
        tonosEn(esqueleto.marcos, luz, cuantos, n, b.tonos)
    }
    coloresDeTonoEn(tinta, cuantos, b.coloresDeTono)

    // **Una sola forma de pintar: la sección barrida.** El usuario lo dijo con sus ojos:
    // el único pincel que se leía sólido era el que barría su sección por el mundo, y los
    // demás eran «variaciones alargadas» del mismo relleno plano. Así que TODOS barren su
    // sección — la redonda un octógono, la cuadrada un cuadrado, la cuchilla su tabique
    // hondo y las planas su tablilla — con los anillos de vértices CLAVADOS AL MUNDO por
    // los marcos del esqueleto. Al orbitar cambia qué caras se ven (eso hace una caja de
    // verdad) pero ningún vértice se mueve: las uniones no pueden temblar, porque no hay
    // nada en ellas que se recalcule.
    barrerSeccion(trazo, esqueleto, base, luz, delAncho, cuantos, b)
    // Y el grano encima, si la tinta lo lleva: la tinta es la misma, con textura dentro.
    if (punta.trama.hayQuePintarla) {
        estamparTrama(esqueleto, b, n, seccionDe(trazo.pincel).size / 2, punta.trama, delAncho, tinta, alfa)
    }
    return true
}

/**
 * **El grano de una tinta tramada, alrededor de la tinta entera.**
 *
 * El cuerpo ya está pintado y es el de la tinta lisa: esto solo estampa la marca encima. Y
 * la marca es **el propio anillo de la sección**, proyectado cada cierto trecho del
 * recorrido: como el anillo da la vuelta completa a la sección barrida, el grano rodea la
 * tinta por todos lados en vez de quedarse en una cara —que es lo que pidió el usuario
 * (6-sep-2026)—, y como sale de la geometría que ya está en el mundo, se escorza y gira con
 * el trazo sin recortar nada contra la silueta: un anillo cae exactamente sobre el tubo.
 *
 * - **Rayado**: los anillos, y nada más. Es la sección cortada.
 * - **Cruzado**: los anillos más dos líneas a lo largo. Con los anillos solos se ve un
 *   peine; lo que dice «esto está en sombra» es la retícula.
 * - **Puntos**: en vez de cerrar el anillo, una gota en un vértice que va rotando, para que
 *   salga un punteado repartido y no una fila de puntos por el mismo sitio.
 *
 * El paso y el grueso salen del ancho del trazo, así que son tantas marcas por ancho se
 * mire de cerca o de lejos; por debajo de [LO_QUE_YA_SE_TRAMA_PLUMA] en pantalla no se
 * estampa nada, porque entre dos marcas ya no cabe tinta.
 */
private fun DrawScope.estamparTrama(
    esqueleto: EsqueletoDelTrazo,
    b: BorradorDePluma,
    n: Int,
    k: Int,
    trama: Trama,
    delAncho: Double,
    tinta: Int,
    alfa: Int
) {
    if (n < 2 || k < 3) return
    val p = b.anillosPantalla
    // Lo ancho que se ve: entre dos vértices opuestos del anillo de en medio.
    val medio = n / 2
    val opuesto = k / 2
    val ax = p[(medio) * 2]; val ay = p[(medio) * 2 + 1]
    val bx = p[(opuesto * n + medio) * 2]; val by = p[(opuesto * n + medio) * 2 + 1]
    val anchoPx = hypot(bx - ax, by - ay)
    if (anchoPx < LO_QUE_YA_SE_TRAMA_PLUMA.dp.toPx()) return

    var suma = 0.0
    for (i in 0 until n) suma += esqueleto.anchos[i]
    val anchoMundo = (suma / n) * delAncho
    if (anchoMundo <= 0.0) return
    val paso = anchoMundo * PASO_DE_LA_TRAMA
    val pelo = (anchoPx * GRUESO_DE_LA_TRAMA).toFloat().coerceAtLeast(1f)
    val tono = Color(aclaradoDePluma(tinta, -HONDO_DE_LA_TRAMA, alfa))

    val recorrido = esqueleto.recorrido
    val camino = b.camino
    camino.rewind()
    var siguiente = recorrido[0] + paso
    var cuantas = 0
    var cual = 0
    for (i in 1 until n) {
        if (cuantas >= MARCAS_DE_SOBRA_PLUMA) break
        if (recorrido[i] < siguiente) continue
        siguiente = recorrido[i] + paso
        cuantas++
        if (trama == Trama.PUNTOS) {
            // La gota va rotando de vértice: repartida por toda la vuelta.
            val j = (cual++ * 3) % k
            val o = (j * n + i) * 2
            camino.addOval(
                androidx.compose.ui.geometry.Rect(
                    androidx.compose.ui.geometry.Offset(p[o].toFloat(), p[o + 1].toFloat()),
                    pelo * 1.4f
                )
            )
            continue
        }
        // El anillo entero: la vuelta completa a la sección.
        var o = i * 2
        camino.moveTo(p[o].toFloat(), p[o + 1].toFloat())
        for (j in 1 until k) {
            o = (j * n + i) * 2
            camino.lineTo(p[o].toFloat(), p[o + 1].toFloat())
        }
        camino.close()
    }
    if (trama == Trama.PUNTOS) {
        drawPath(camino, tono)
        return
    }
    drawPath(camino, tono, style = androidx.compose.ui.graphics.drawscope.Stroke(width = pelo))
    if (trama != Trama.CRUZADO) return
    // Las dos de a lo largo: dos aristas opuestas del barrido, de punta a punta.
    camino.rewind()
    for (j in intArrayOf(0, k / 2)) {
        for (i in 0 until n) {
            val o = (j * n + i) * 2
            if (i == 0) camino.moveTo(p[o].toFloat(), p[o + 1].toFloat())
            else camino.lineTo(p[o].toFloat(), p[o + 1].toFloat())
        }
    }
    drawPath(camino, tono, style = androidx.compose.ui.graphics.drawscope.Stroke(width = pelo))
}

/** Por debajo de este ancho en pantalla no se trama: entre dos marcas ya no cabe tinta. */
private const val LO_QUE_YA_SE_TRAMA_PLUMA = 5.0
/** El tope de marcas por trazo y fotograma: más allá, el grano ya no dice nada más. */
private const val MARCAS_DE_SOBRA_PLUMA = 400

/**
 * **El barrido de la sección**: los anillos en el mundo, las caras por su normal, y las
 * tiras de cara pintadas de atrás nunca — porque en una sección CONVEXA las caras visibles
 * no se tapan entre sí y comparten vértice exacto en cada arista: ni huecos ni costuras.
 */
private fun DrawScope.barrerSeccion(
    trazo: Trazo3D,
    esqueleto: EsqueletoDelTrazo,
    base: BaseDeCamara,
    luz: Pt3,
    delAncho: Double,
    cuantos: Int,
    b: BorradorDePluma
) {
    val n = esqueleto.cuantas
    val seccion = seccionDe(trazo.pincel)
    val k = seccion.size / 2
    b.listoParaAnillos(n, k)

    // Los anillos, en el MUNDO: centro + (r·cR + s·cS)·medioAncho. Todo sale del esqueleto
    // cocido — nada de esto mira a la cámara.
    val xyz = esqueleto.xyz
    val marcos = esqueleto.marcos
    val anchos = esqueleto.anchos
    for (j in 0 until k) {
        val cR = seccion[2 * j]
        val cS = seccion[2 * j + 1]
        val fila = b.anillos
        var o = j * 3 * n
        for (i in 0 until n) {
            val m = 6 * i
            val w2 = (anchos[i] * delAncho / 2).coerceAtLeast(1e-9)
            val rx = marcos[m]; val ry = marcos[m + 1]; val rz = marcos[m + 2]
            val sx = marcos[m + 3]; val sy = marcos[m + 4]; val sz = marcos[m + 5]
            fila[o] = xyz[3 * i] + (rx * cR + sx * cS) * w2
            fila[o + 1] = xyz[3 * i + 1] + (ry * cR + sy * cS) * w2
            fila[o + 2] = xyz[3 * i + 2] + (rz * cR + sz * cS) * w2
            o += 3
        }
    }
    // Y a la pantalla, por filas, con la base pagada del fotograma.
    for (j in 0 until k) {
        b.filaMundo(j, n)
        if (!proyectaPluma(base, b.filaDeMundo, n, b.filaDePantalla)) return
        b.guardaFila(j, n)
    }

    // Cada CARA (entre el anillo j y el j+1): su dirección radial media es fija al mundo
    // por muestra; con ella se decide si mira al ojo y qué tono le da la luz del mundo.
    for (j in 0 until k) {
        val j2 = (j + 1) % k
        var mR = (seccion[2 * j] + seccion[2 * j2]) / 2
        var mS = (seccion[2 * j + 1] + seccion[2 * j2 + 1]) / 2
        val ml = kotlin.math.sqrt(mR * mR + mS * mS)
        if (ml < 1e-9) continue
        mR /= ml; mS /= ml
        var arranque = -1
        var tonoDeRun = 0
        var i = 0
        while (i <= n) {
            var visible = false
            var tono = 0
            if (i < n) {
                val m = 6 * i
                val nx = marcos[m] * mR + marcos[m + 3] * mS
                val ny = marcos[m + 1] * mR + marcos[m + 4] * mS
                val nz = marcos[m + 2] * mR + marcos[m + 5] * mS
                visible = nx * base.fx + ny * base.fy + nz * base.fz < 0.0
                if (visible && cuantos > 1) {
                    val lambert = kotlin.math.abs(nx * luz.x + ny * luz.y + nz * luz.z)
                    tono = (lambert * cuantos).toInt().coerceAtMost(cuantos - 1)
                }
            }
            if (visible && (arranque < 0 || tono == tonoDeRun)) {
                if (arranque < 0) { arranque = i; tonoDeRun = tono }
            } else if (arranque >= 0) {
                val hasta = i - 1
                if (hasta > arranque) {
                    pintaTira(b, j, j2, arranque, hasta, n, b.coloresDeTono[tonoDeRun])
                }
                // Al cambiar de tono con la cara aún visible, el tramo nuevo ARRANCA en la
                // muestra frontera: los dos tramos la comparten y no queda rendija.
                arranque = if (visible) hasta.coerceAtLeast(0) else -1
                tonoDeRun = tono
            }
            i++
        }
    }

    // Las tapas: el polígono del anillo entero, cuando la punta mira al ojo. Es lo que
    // hace que el cuadrado acabe EN CUADRADO — nada de un núcleo redondo asomando. La
    // redonda y la luz NO llevan tapa: sus puntas ya vienen cerradas en domo desde el
    // cocido, y un disco plano encima era justo el «contorno circular» que aparecía de
    // golpe en un borde al girar la vista.
    if (trazo.pincel == Pincel.REDONDO || trazo.pincel == Pincel.LUZ) return
    val t0 = base.fx * esqueleto.tangentes[0] + base.fy * esqueleto.tangentes[1] +
        base.fz * esqueleto.tangentes[2]
    if (t0 > 0.0) pintaTapa(b, 0, k, n, tonoDeTapa(esqueleto, 0, luz, cuantos, b))
    val u = 3 * (n - 1)
    val tn = base.fx * esqueleto.tangentes[u] + base.fy * esqueleto.tangentes[u + 1] +
        base.fz * esqueleto.tangentes[u + 2]
    if (tn < 0.0) pintaTapa(b, n - 1, k, n, tonoDeTapa(esqueleto, n - 1, luz, cuantos, b))
}

/** El tono de una tapa: el lambert de su tangente, con la paleta de siempre. */
private fun tonoDeTapa(
    esqueleto: EsqueletoDelTrazo, i: Int, luz: Pt3, cuantos: Int, b: BorradorDePluma
): Int {
    if (cuantos <= 1) return b.coloresDeTono[0]
    val t = 3 * i
    val lambert = kotlin.math.abs(
        esqueleto.tangentes[t] * luz.x + esqueleto.tangentes[t + 1] * luz.y +
            esqueleto.tangentes[t + 2] * luz.z
    )
    return b.coloresDeTono[(lambert * cuantos).toInt().coerceAtMost(cuantos - 1)]
}

/**
 * **Una tira de cara**: por el anillo j de ida y por el j2 de vuelta, cerrada y rellena.
 *
 * ## Cuando la tira se dobla sobre sí misma
 *
 * Una tira larga se pinta como **un solo polígono**, que es lo barato y lo que no deja
 * costuras entre cuadros. Pero un trazo que se aleja del ojo, al girar la vista, acaba
 * proyectándose encima de sí mismo: entonces ese polígono se cruza, y la regla de relleno de
 * siempre —la del número de vueltas— **descuenta** el trozo que va al revés en vez de
 * rellenarlo. Lo que se ve es un agujero justo donde la tinta se superpone con la tinta, y
 * solo al girar (lo reportó el usuario el 6-sep-2026).
 *
 * Así que se mira si el sentido de los cuadros cambia dentro de la tira. Si no cambia —que
 * es lo normal— se pinta el polígono largo de siempre, sin costuras. Si cambia, se pinta
 * **cuadro a cuadro y todos en el mismo sentido**: entonces donde dos se solapan las vueltas
 * se suman en vez de restarse, y ahí hay tinta, que es lo que tiene que haber.
 */
private fun DrawScope.pintaTira(
    b: BorradorDePluma, j: Int, j2: Int, desde: Int, hasta: Int, n: Int, color: Int
) {
    val p = b.anillosPantalla
    val camino = b.camino
    camino.rewind()
    if (!seDobla(p, j, j2, desde, hasta, n)) {
        var o = (j * n + desde) * 2
        camino.moveTo(p[o].toFloat(), p[o + 1].toFloat())
        for (i in desde + 1..hasta) {
            o = (j * n + i) * 2
            camino.lineTo(p[o].toFloat(), p[o + 1].toFloat())
        }
        for (i in hasta downTo desde) {
            o = (j2 * n + i) * 2
            camino.lineTo(p[o].toFloat(), p[o + 1].toFloat())
        }
        camino.close()
        drawPath(camino, Color(color))
        return
    }
    for (i in desde until hasta) {
        val a = (j * n + i) * 2
        val bb = (j * n + i + 1) * 2
        val c = (j2 * n + i + 1) * 2
        val d = (j2 * n + i) * 2
        cuadroEnElMismoSentido(camino, p, a, bb, c, d)
    }
    drawPath(camino, Color(color))
}

/** Dos veces el área con signo del cuadrilátero: su signo es el sentido en que va. */
internal fun areaDelCuadro(p: DoubleArray, a: Int, b: Int, c: Int, d: Int): Double =
    (p[a] * p[b + 1] - p[b] * p[a + 1]) +
        (p[b] * p[c + 1] - p[c] * p[b + 1]) +
        (p[c] * p[d + 1] - p[d] * p[c + 1]) +
        (p[d] * p[a + 1] - p[a] * p[d + 1])

/** Si dentro de la tira hay cuadros de los dos sentidos: entonces se ha doblado. */
internal fun seDobla(p: DoubleArray, j: Int, j2: Int, desde: Int, hasta: Int, n: Int): Boolean {
    var signo = 0
    for (i in desde until hasta) {
        val area = areaDelCuadro(p, (j * n + i) * 2, (j * n + i + 1) * 2, (j2 * n + i + 1) * 2, (j2 * n + i) * 2)
        if (area > 0.0) { if (signo < 0) return true; signo = 1 }
        else if (area < 0.0) { if (signo > 0) return true; signo = -1 }
    }
    return false
}

/** Mete el cuadrilátero en el camino, siempre en el mismo sentido de giro. */
private fun cuadroEnElMismoSentido(camino: Path, p: DoubleArray, a: Int, b: Int, c: Int, d: Int) {
    camino.moveTo(p[a].toFloat(), p[a + 1].toFloat())
    if (areaDelCuadro(p, a, b, c, d) >= 0.0) {
        camino.lineTo(p[b].toFloat(), p[b + 1].toFloat())
        camino.lineTo(p[c].toFloat(), p[c + 1].toFloat())
        camino.lineTo(p[d].toFloat(), p[d + 1].toFloat())
    } else {
        camino.lineTo(p[d].toFloat(), p[d + 1].toFloat())
        camino.lineTo(p[c].toFloat(), p[c + 1].toFloat())
        camino.lineTo(p[b].toFloat(), p[b + 1].toFloat())
    }
    camino.close()
}

/** Una tapa: el anillo entero de la muestra [i], como polígono. */
private fun DrawScope.pintaTapa(b: BorradorDePluma, i: Int, k: Int, n: Int, color: Int) {
    val p = b.anillosPantalla
    val camino = b.camino
    camino.rewind()
    var o = (0 * n + i) * 2
    camino.moveTo(p[o].toFloat(), p[o + 1].toFloat())
    for (j in 1 until k) {
        o = (j * n + i) * 2
        camino.lineTo(p[o].toFloat(), p[o + 1].toFloat())
    }
    camino.close()
    drawPath(camino, Color(color))
}

/**
 * La sección de cada pincel: pares (cR, cS) por esquina, en tantos del medio ancho, en
 * orden de giro. cR va por la normal del marco (lo hondo) y cS por el través (lo ancho).
 * Todas son variaciones de la misma caja — que es exactamente lo que pidió el usuario:
 * la cuadrada de base, y las demás alargándose por su eje.
 */
private fun seccionDe(pincel: Pincel): DoubleArray = when (pincel) {
    Pincel.CUADRADO -> SECCION_CUADRADA
    Pincel.CUCHILLA -> SECCION_HONDA
    Pincel.PLANO, Pincel.LAPIZ, Pincel.RESALTADOR -> SECCION_PLANA
    else -> SECCION_REDONDA
}

private val SECCION_REDONDA = DoubleArray(16).also {
    for (j in 0 until 8) {
        val a = Math.PI / 8 + j * Math.PI / 4
        it[2 * j] = Math.cos(a)
        it[2 * j + 1] = Math.sin(a)
    }
}
private val SECCION_CUADRADA = doubleArrayOf(1.0, 1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0)
private val SECCION_HONDA = doubleArrayOf(1.0, 0.14, -1.0, 0.14, -1.0, -0.14, 1.0, -0.14)
private val SECCION_PLANA = doubleArrayOf(0.16, 1.0, -0.16, 1.0, -0.16, -1.0, 0.16, -1.0)

// ---------------------------------------------------------------------------
// Los ayudantes puros: sin DrawScope, para poder probarlos en la JVM.
// ---------------------------------------------------------------------------

/**
 * Los dos rieles de la cinta en PANTALLA: para cada muestra, centro ± medioAncho·dirección.
 *
 * [anchosPx] trae el ancho **entero** de cada muestra —mundo por escala, ya con el clamp
 * de pintado puesto— y el medio ancho sale de dividirlo aquí: así el que llama piensa en
 * «lo gordo que es el trazo», que es como se piensa, y la mitad es un detalle del riel.
 *
 * Es la versión pura del camino caliente: asigna sus dos arrays de salida porque una
 * prueba quiere resultados suyos, y el pintor no pasa por aquí — escribe sobre los
 * cuadernos de [BorradorDePluma] con [railesEn], que es la misma cuenta.
 */
fun railesDePluma(
    pantalla: DoubleArray,
    anchosPx: DoubleArray,
    haciaAfuera: DoubleArray
): Pair<DoubleArray, DoubleArray> {
    val n = anchosPx.size
    val izquierda = DoubleArray(2 * n)
    val derecha = DoubleArray(2 * n)
    railesEn(pantalla, anchosPx, haciaAfuera, n, izquierda, derecha)
    return izquierda to derecha
}

/**
 * El tono de cada muestra contra la luz del mundo, cuantizado a [cuantos] escalones.
 *
 * El tono es el lambert de `r` —la normal de apoyo del marco— contra la luz, **a dos
 * caras** (el valor absoluto): una cinta se ve por las dos y la de atrás no puede pintarse
 * negra. Y cuantizado a propósito, no alisado: los escalones son el sombreado de pluma
 * —los del entintado a mano, que separa tonos en vez de degradarlos— y además hacen que
 * tramos enteros compartan camino de relleno, que es lo que los hace baratos.
 */
fun tonosDePluma(marcos: DoubleArray, luz: Pt3, cuantos: Int): IntArray {
    val n = marcos.size / 6
    val salida = IntArray(n)
    tonosEn(marcos, luz, cuantos, n, salida)
    return salida
}

// ---------------------------------------------------------------------------
// El camino caliente: las mismas cuentas, sobre cuadernos reutilizados.
// ---------------------------------------------------------------------------

/** La cuenta de [railesDePluma], escribiendo en arrays ajenos: la del camino caliente. */
private fun railesEn(
    pantalla: DoubleArray,
    anchosPx: DoubleArray,
    haciaAfuera: DoubleArray,
    n: Int,
    izquierda: DoubleArray,
    derecha: DoubleArray
) {
    for (i in 0 until n) {
        val medio = anchosPx[i] / 2
        val px = pantalla[2 * i]
        val py = pantalla[2 * i + 1]
        val ox = haciaAfuera[2 * i] * medio
        val oy = haciaAfuera[2 * i + 1] * medio
        izquierda[2 * i] = px + ox
        izquierda[2 * i + 1] = py + oy
        derecha[2 * i] = px - ox
        derecha[2 * i + 1] = py - oy
    }
}

/** La cuenta de [tonosDePluma], escribiendo en un array ajeno: la del camino caliente. */
private fun tonosEn(marcos: DoubleArray, luz: Pt3, cuantos: Int, n: Int, salida: IntArray) {
    for (i in 0 until n) {
        val lambert = abs(
            marcos[6 * i] * luz.x + marcos[6 * i + 1] * luz.y + marcos[6 * i + 2] * luz.z
        )
        // El techo se recorta y no se envuelve: lambert uno es el escalón más claro, no
        // una vuelta al cero.
        salida[i] = (lambert * cuantos).toInt().coerceAtMost(cuantos - 1)
    }
}

/**
 * Idéntica a [BaseDeCamara.proyecta] pero con el largo explícito, porque los cuadernos
 * van sobrados de sitio y `proyecta` mide por el tamaño del array. Y de paso avisa: con
 * lente puesta, una muestra **detrás del ojo** devuelve `false` — ahí la proyección
 * envuelve (ver [Camara3D.ojo]) y esta pluma prefiere ceder el trazo al camino viejo
 * antes que pintarlo retorcido.
 */
private fun proyectaPluma(
    base: BaseDeCamara,
    xyz: DoubleArray,
    n: Int,
    salida: DoubleArray
): Boolean {
    val fin = 3 * n
    var i = 0
    var o = 0
    while (i < fin) {
        val vx = xyz[i] - base.cx
        val vy = xyz[i + 1] - base.cy
        val vz = xyz[i + 2] - base.cz
        val u = vx * base.dx + vy * base.dy + vz * base.dz
        val v = vx * base.ax + vy * base.ay + vz * base.az
        if (base.campo <= 0.0) {
            salida[o] = base.medioAncho + u * base.zoom
            salida[o + 1] = base.medioAlto - v * base.zoom
        } else {
            val hondo = (vx * base.fx + vy * base.fy + vz * base.fz) + base.ojo
            if (hondo <= 0.0) return false
            val radio = hypot(u, v)
            if (radio < 1e-9) {
                salida[o] = base.medioAncho
                salida[o + 1] = base.medioAlto
            } else {
                val enPantalla = base.radioEnPantalla(radio, hondo)
                salida[o] = base.medioAncho + enPantalla * u / radio
                salida[o + 1] = base.medioAlto - enPantalla * v / radio
            }
        }
        i += 3
        o += 2
    }
    return true
}

/**
 * El ancho en píxeles de cada muestra del tubo: **mundo por escala, y el clamp al final**.
 *
 * La escala es la de la vista: con la ortográfica una unidad de mundo son `zoom` píxeles
 * para todas las muestras, y con lente es `focal/(hondo+ojo)` **por muestra** — que es lo
 * que hace que el mismo tubo salga gordo por donde pasa cerca. El clamp de pintado va
 * después de la conversión y solo aquí: es del fotograma, no del trazo.
 */
private fun mideElTubo(
    base: BaseDeCamara,
    xyz: DoubleArray,
    anchosDeMundo: DoubleArray,
    delAncho: Double,
    minPx: Double,
    n: Int,
    salida: DoubleArray
) {
    if (base.campo <= 0.0) {
        for (i in 0 until n) {
            salida[i] = (anchosDeMundo[i] * delAncho * base.zoom).coerceAtLeast(minPx)
        }
        return
    }
    for (i in 0 until n) {
        // El denominador es positivo seguro: [proyectaPluma] ya cedió el trazo si algo
        // quedaba detrás del ojo.
        val hondo = base.hondo(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]) + base.ojo
        salida[i] = (anchosDeMundo[i] * delAncho * base.focal / hondo).coerceAtLeast(minPx)
    }
}

/**
 * La perpendicular EN PANTALLA de cada muestra del eje proyectado, promediada con los
 * vecinos — la misma decisión que el contorno del camino viejo: con la del tramo a secas,
 * el riel pega un salto en cada punto justo donde el trazo dobla, que es donde más se
 * mira. Donde dos muestras caen en el mismo píxel se hereda la última: la perpendicular
 * de dos puntos pegados no significa nada y un cero cerraría el contorno a un pincho.
 */
private fun perpendicularesEnPantalla(pantalla: DoubleArray, n: Int, salida: DoubleArray) {
    var ux = 0.0
    var uy = 1.0
    for (i in 0 until n) {
        val a = if (i == 0) 0 else i - 1
        val b = if (i == n - 1) n - 1 else i + 1
        val dx = pantalla[2 * b] - pantalla[2 * a]
        val dy = pantalla[2 * b + 1] - pantalla[2 * a + 1]
        val largo = hypot(dx, dy)
        if (largo >= 1e-6) {
            ux = -dy / largo
            uy = dx / largo
        }
        salida[2 * i] = ux
        salida[2 * i + 1] = uy
    }
}

/**
 * Un riel de la cinta EN EL MUNDO: `xyz ± eje·ancho/2`, con el eje sacado del marco.
 *
 * Por el través `s` ([porLaNormal] falso) la cinta queda tumbada en la hoja —el listón y
 * el rodillo—; por la normal `r` queda de pie —la cuchilla: honda, no ancha—. [lado] es
 * `+1` para el riel izquierdo y `-1` para el derecho: la misma cuenta dos veces, sin
 * duplicar la función.
 */
private fun railDelMundoEn(
    xyz: DoubleArray,
    marcos: DoubleArray,
    anchosDeMundo: DoubleArray,
    delAncho: Double,
    porLaNormal: Boolean,
    lado: Double,
    n: Int,
    salida: DoubleArray
) {
    // En el marco, r ocupa 6i..6i+2 y s ocupa 6i+3..6i+5. Ver [EsqueletoDelTrazo.marcos].
    val desplaza = if (porLaNormal) 0 else 3
    for (i in 0 until n) {
        val medio = anchosDeMundo[i] * delAncho / 2 * lado
        val e = 6 * i + desplaza
        salida[3 * i] = xyz[3 * i] + marcos[e] * medio
        salida[3 * i + 1] = xyz[3 * i + 1] + marcos[e + 1] * medio
        salida[3 * i + 2] = xyz[3 * i + 2] + marcos[e + 2] * medio
    }
}

/**
 * **El clamp de pintado de la cinta**: donde los dos rieles proyectados quedan a menos
 * del mínimo —la cinta vista de canto—, se separan hasta el mínimo alrededor de su punto
 * medio. Simétrico alrededor del medio a propósito: el eje de la cinta no se mueve, así
 * que el clamp no corre el trazo de sitio, solo le deja un pelo de cuerpo para que se vea.
 *
 * La dirección de separar es la que ya llevan los rieles; de canto perfecto no la hay, y
 * entonces se usa la perpendicular del eje proyectado (los puntos medios, que el propio
 * clamp no toca) — una cinta de canto es una raya fina, y una raya se engorda de través.
 */
private fun ensanchaLoInvisible(
    izquierda: DoubleArray,
    derecha: DoubleArray,
    n: Int,
    minPx: Double
) {
    var ux = 0.0
    var uy = 1.0
    for (i in 0 until n) {
        val dx = derecha[2 * i] - izquierda[2 * i]
        val dy = derecha[2 * i + 1] - izquierda[2 * i + 1]
        val largo = hypot(dx, dy)
        // La dirección se apunta SIEMPRE que la haya, no solo al ensanchar: si la cinta se
        // pone de canto a mitad de camino, lo que se hereda es la dirección de al lado y
        // no una de fábrica.
        if (largo > 1e-9) {
            ux = dx / largo
            uy = dy / largo
        }
        if (largo >= minPx) continue
        if (largo <= 1e-9) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == n - 1) n - 1 else i + 1
            val mdx = (izquierda[2 * b] + derecha[2 * b] - izquierda[2 * a] - derecha[2 * a]) / 2
            val mdy = (izquierda[2 * b + 1] + derecha[2 * b + 1] -
                izquierda[2 * a + 1] - derecha[2 * a + 1]) / 2
            val ml = hypot(mdx, mdy)
            if (ml > 1e-9) {
                ux = -mdy / ml
                uy = mdx / ml
            }
            // Y si tampoco hay eje —todo cae en el mismo píxel—, se hereda la última: lo
            // mismo que hacen las perpendiculares del contorno.
        }
        val mx = (izquierda[2 * i] + derecha[2 * i]) / 2
        val my = (izquierda[2 * i + 1] + derecha[2 * i + 1]) / 2
        val medio = minPx / 2
        izquierda[2 * i] = mx - ux * medio
        izquierda[2 * i + 1] = my - uy * medio
        derecha[2 * i] = mx + ux * medio
        derecha[2 * i + 1] = my + uy * medio
    }
}

/**
 * La luz del mundo puesta en esta pantalla: hacia dónde cae el brillo sobre un tubo.
 *
 * Cambia con la cámara Y ESO ES LO CORRECTO: la geometría del trazo está clavada al mundo,
 * pero por dónde le entra la luz al que mira depende de desde dónde mira — como en
 * cualquier cilindro de verdad. Si la luz cae a lo largo de la mirada no hay lado, y el
 * brillo se queda arriba, que es donde lo pone cualquier estudio.
 */
private fun luzEnPantalla(base: BaseDeCamara, luz: Pt3, salida: DoubleArray) {
    val u = luz.x * base.dx + luz.y * base.dy + luz.z * base.dz
    val v = -(luz.x * base.ax + luz.y * base.ay + luz.z * base.az)
    val l = kotlin.math.hypot(u, v)
    if (l < 1e-9) {
        salida[0] = 0.0
        salida[1] = -1.0
    } else {
        salida[0] = u / l
        salida[1] = v / l
    }
}

/**
 * El eje de una banda anidada del tubo: el centro corrido hacia la luz, y su ancho.
 *
 * [parte] es cuánto del ancho entero mide la banda; el corrimiento es lo que sobra a cada
 * lado por cuánto mira la perpendicular de la muestra hacia la luz de pantalla — así el
 * filo se pega al riel iluminado sin salirse jamás del tubo.
 */
private fun bandaEn(
    pantalla: DoubleArray,
    afuera: DoubleArray,
    anchosPx: DoubleArray,
    parte: Double,
    luzU: Double,
    luzV: Double,
    corrida: Boolean,
    n: Int,
    centro: DoubleArray,
    anchos: DoubleArray
) {
    for (i in 0 until n) {
        val ax = afuera[2 * i]
        val ay = afuera[2 * i + 1]
        val entero = anchosPx[i]
        val deBanda = entero * parte
        anchos[i] = deBanda
        val f = if (corrida) (ax * luzU + ay * luzV).coerceIn(-1.0, 1.0) else 0.0
        val corrido = f * (entero - deBanda) / 2
        centro[2 * i] = pantalla[2 * i] + ax * corrido
        centro[2 * i + 1] = pantalla[2 * i + 1] + ay * corrido
    }
}

/** Los colores de los escalones, del más oscuro al más claro, escritos en [salida]. */
private fun coloresDeTonoEn(tinta: Int, cuantos: Int, salida: IntArray) {
    val alfa = tinta ushr 24
    for (e in 0 until cuantos) {
        // El centro de cada escalón, para que el reparto no regale un extremo: con ocho
        // escalones ninguno es el negro puro ni el blanco puro, son ocho tonos del color.
        val cuanto = TONO_DE_SOMBRA + (TONO_DE_BRILLO - TONO_DE_SOMBRA) * (e + 0.5) / cuantos
        salida[e] = aclaradoDePluma(tinta, cuanto, alfa.toInt())
    }
}

/**
 * **El relleno por tramos de tono**: tramos consecutivos con el mismo escalón van en el
 * mismo camino, y cada camino se rellena de una vez con el color de su escalón.
 *
 * Cada tramo es la cinta entre dos cambios de tono, con su riel de ida suavizado por
 * cuadráticas de puntos medios —el trazado de la casa: ver el `porUnLado` del lienzo—, el
 * de vuelta al revés, y cerrado. Los tramos vecinos **comparten la muestra de la
 * frontera**, así que entre tono y tono no queda rendija ninguna. Las tapas redondas solo
 * van en las puntas del trazo entero y solo en el tubo: la cinta acaba en su canto.
 *
 * El camino es uno y se rebobina por tramo: mandar a pintar copia lo que hay dentro, igual
 * que en los cuadernos del contorno del lienzo.
 */
private fun DrawScope.rellenarPorTonos(
    n: Int,
    tonos: IntArray,
    colores: IntArray,
    izquierda: DoubleArray,
    derecha: DoubleArray,
    tapasRedondas: Boolean,
    pantalla: DoubleArray?,
    anchosPx: DoubleArray?
) {
    val camino = BorradorDePluma.camino
    var i = 0
    while (i < n - 1) {
        // El tono de la tira entre dos muestras es el de la muestra donde arranca, y el
        // tramo dura mientras dure el escalón.
        val tono = tonos[i]
        var j = i
        while (j + 1 < n - 1 && tonos[j + 1] == tono) j++
        val hasta = j + 1

        camino.rewind()
        porUnLadoDePluma(camino, izquierda, i, hasta, arranca = true)
        if (tapasRedondas && hasta == n - 1 && pantalla != null && anchosPx != null) {
            // La punta final: el casquete por delante, hacia donde sale el trazo.
            var sx = pantalla[2 * hasta] - pantalla[2 * (hasta - 1)]
            var sy = pantalla[2 * hasta + 1] - pantalla[2 * (hasta - 1) + 1]
            val l = hypot(sx, sy)
            if (l < 1e-6) { sx = 1.0; sy = 0.0 } else { sx /= l; sy /= l }
            tapaDePluma(
                camino, pantalla[2 * hasta], pantalla[2 * hasta + 1], anchosPx[hasta] / 2,
                izquierda[2 * hasta], izquierda[2 * hasta + 1], sx, sy
            )
        }
        porUnLadoDePluma(camino, derecha, hasta, i, arranca = false)
        if (tapasRedondas && i == 0 && pantalla != null && anchosPx != null) {
            // Y la punta de arranque, saliendo hacia atrás.
            var sx = pantalla[0] - pantalla[2]
            var sy = pantalla[1] - pantalla[3]
            val l = hypot(sx, sy)
            if (l < 1e-6) { sx = 1.0; sy = 0.0 } else { sx /= l; sy /= l }
            tapaDePluma(
                camino, pantalla[0], pantalla[1], anchosPx[0] / 2,
                derecha[0], derecha[1], sx, sy
            )
        }
        camino.close()
        drawPath(camino, Color(colores[tono]))
        i = hasta
    }
}

/**
 * Un lado del contorno con sus curvas suaves — el `porUnLado` de la casa, sobre el array
 * empaquetado y con recorrido en los dos sentidos: [desde] mayor que [hasta] lo camina al
 * revés, que es lo que hace el riel de vuelta sin darle la vuelta a ningún array.
 */
private fun porUnLadoDePluma(
    camino: Path,
    lado: DoubleArray,
    desde: Int,
    hasta: Int,
    arranca: Boolean
) {
    val paso = if (hasta >= desde) 1 else -1
    val x0 = lado[2 * desde].toFloat()
    val y0 = lado[2 * desde + 1].toFloat()
    if (arranca) camino.moveTo(x0, y0) else camino.lineTo(x0, y0)
    // Un lado de un solo punto no camina: los tramos traen dos muestras por lo menos,
    // pero un bucle que no puede alcanzar su final no se deja escrito.
    if (hasta == desde) return
    var i = desde + paso
    while (i != hasta) {
        val ax = lado[2 * i]
        val ay = lado[2 * i + 1]
        val siguiente = i + paso
        val bx = lado[2 * siguiente]
        val by = lado[2 * siguiente + 1]
        camino.quadraticTo(
            ax.toFloat(), ay.toFloat(),
            ((ax + bx) / 2).toFloat(), ((ay + by) / 2).toFloat()
        )
        i = siguiente
    }
    if (hasta != desde) camino.lineTo(lado[2 * hasta].toFloat(), lado[2 * hasta + 1].toFloat())
}

/**
 * La tapa redonda de una punta del tubo: el casquete que va **por delante**, barrido hacia
 * el lado de la salida — la media circunferencia que pasa por delante de la punta y no la
 * que cortaría el trazo por la mitad. Es el `tapaDe` redondo del lienzo, en dobles.
 */
private fun tapaDePluma(
    camino: Path,
    cx: Double,
    cy: Double,
    radio: Double,
    desdeX: Double,
    desdeY: Double,
    salidaX: Double,
    salidaY: Double
) {
    val arranque = atan2(desdeY - cy, desdeX - cx)
    var haciaLaSalida = atan2(salidaY, salidaX) - arranque
    while (haciaLaSalida > Math.PI) haciaLaSalida -= Math.PI * 2
    while (haciaLaSalida < -Math.PI) haciaLaSalida += Math.PI * 2
    val sentido = if (haciaLaSalida >= 0) 1.0 else -1.0
    for (k in 1..PASOS_DE_LA_TAPA_DE_PLUMA) {
        val angulo = arranque + sentido * Math.PI * k / PASOS_DE_LA_TAPA_DE_PLUMA
        camino.lineTo(
            (cx + cos(angulo) * radio).toFloat(),
            (cy + sin(angulo) * radio).toFloat()
        )
    }
}

// ---------------------------------------------------------------------------
// El color. Espejos de las cuentas privadas del lienzo: este fichero no puede tocarlo.
// ---------------------------------------------------------------------------

/** Un color acercado al blanco (positivo) o al negro (negativo), con el alfa que se pida. */
private fun aclaradoDePluma(argb: Int, cuanto: Double, alfa: Int): Int {
    fun canal(desplazamiento: Int): Int {
        val v = (argb shr desplazamiento) and 0xFF
        val destino = if (cuanto >= 0) 255 else 0
        return (v + (destino - v) * abs(cuanto)).toInt().coerceIn(0, 255)
    }
    return (alfa shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
}

/** Dos colores mezclados, canal a canal: cero es el primero y uno es el segundo. */
private fun mezcladosDePluma(a: Int, b: Int, cuanto: Float): Int {
    val t = cuanto.coerceIn(0f, 1f)
    fun canal(d: Int): Int {
        val x = (a shr d) and 0xFF
        val y = (b shr d) and 0xFF
        return (x + (y - x) * t).toInt().coerceIn(0, 255)
    }
    return (canal(24) shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
}

/**
 * **Los cuadernos de la pluma**, que se reaprovechan de un trazo al siguiente y de un
 * fotograma al siguiente. Vale por lo mismo que el `Borrador` del lienzo: **todo esto se
 * pinta en un solo hilo** y un trazo no se pinta dentro de otro — se proyecta, se rellena
 * y le toca al siguiente. Los arrays crecen y no encogen, y todos los bucles miden por el
 * `n` del trazo y no por el tamaño del cuaderno. Lo que se asignara aquí por muestra no se
 * vería como memoria: se vería como el tirón al girar.
 */
private object BorradorDePluma {
    /** El eje proyectado: 2n. */
    var pantalla = DoubleArray(2 * CABEN)
        private set

    /** El ancho en píxeles de cada muestra, ya con el clamp de pintado: n. */
    var anchosPx = DoubleArray(CABEN)
        private set

    /** La dirección unitaria del medio ancho en pantalla: 2n. */
    var afuera = DoubleArray(2 * CABEN)
        private set

    /** Los dos rieles en pantalla: 2n cada uno. */
    var izquierda = DoubleArray(2 * CABEN)
        private set
    var derecha = DoubleArray(2 * CABEN)
        private set

    /** Un riel en el mundo, de camino a proyectarse: 3n. Sirve para los dos, por turnos. */
    var mundo = DoubleArray(3 * CABEN)
        private set

    /** El escalón de tono de cada muestra: n. */
    var tonos = IntArray(CABEN)
        private set

    /** El color de cada escalón. Nunca hay más de [ESCALONES_DE_TONO]. */
    val coloresDeTono = IntArray(ESCALONES_DE_TONO)

    /** Y las paletas del lomo: el flanco a la sombra y el filo vivo. */
    val coloresDelFlanco = IntArray(ESCALONES_DE_TONO)
    val coloresDelVivo = IntArray(ESCALONES_DE_TONO)

    /** La luz del mundo puesta en pantalla: (u, v) unitaria. */
    val luzPantalla = DoubleArray(2)

    /** El eje y el ancho de la banda anidada que se esté rellenando: 2n y n. */
    var centroDeBanda = DoubleArray(2 * CABEN)
        private set
    var anchosDeBanda = DoubleArray(CABEN)
        private set

    /** Los anillos del barrido: K filas de n puntos, en mundo (3·K·n) y en pantalla (2·K·n). */
    var anillos = DoubleArray(3 * 8 * CABEN)
        private set
    var anillosPantalla = DoubleArray(2 * 8 * CABEN)
        private set

    /** Una fila de anillo de paso, para proyectarla con el camino de lote. */
    var filaDeMundo = DoubleArray(3 * CABEN)
        private set
    var filaDePantalla = DoubleArray(2 * CABEN)
        private set

    fun listoParaAnillos(n: Int, k: Int) {
        listo(n)
        if (anillos.size < 3 * k * n) {
            anillos = DoubleArray(3 * k * maxOf(n, anchosPx.size))
            anillosPantalla = DoubleArray(2 * k * maxOf(n, anchosPx.size))
        }
        if (filaDeMundo.size < 3 * n) {
            filaDeMundo = DoubleArray(3 * maxOf(n, anchosPx.size))
            filaDePantalla = DoubleArray(2 * maxOf(n, anchosPx.size))
        }
    }

    /** Copia la fila [j] de [anillos] al cuaderno de paso, lista para proyectar. */
    fun filaMundo(j: Int, n: Int) {
        System.arraycopy(anillos, j * 3 * n, filaDeMundo, 0, 3 * n)
    }

    /** Y guarda la fila proyectada donde le toca. */
    fun guardaFila(j: Int, n: Int) {
        System.arraycopy(filaDePantalla, 0, anillosPantalla, j * 2 * n, 2 * n)
    }

    /** El camino del relleno, rebobinado por tramo: mandar a pintar copia lo de dentro. */
    val camino = Path()

    fun listo(n: Int) {
        if (anchosPx.size >= n) return
        // Al doble por lo menos: creciendo justo, una escena con trazos cada vez más
        // largos estrenaría arrays en cada trazo.
        val m = maxOf(n, anchosPx.size * 2)
        pantalla = DoubleArray(2 * m)
        anchosPx = DoubleArray(m)
        afuera = DoubleArray(2 * m)
        izquierda = DoubleArray(2 * m)
        derecha = DoubleArray(2 * m)
        mundo = DoubleArray(3 * m)
        tonos = IntArray(m)
        centroDeBanda = DoubleArray(2 * m)
        anchosDeBanda = DoubleArray(m)
    }

    /** Muestras de sobra para un trazo largo, y crece si hace falta. */
    private const val CABEN = 512
}

/**
 * Las tres bandas del lomo y sus tonos.
 *
 * El flanco baja poco (−0.28): es sombra de forma, no silueta negra. El lomo mide el 62% y
 * el filo el 30% — la proporción con la que un cilindro entintado a mano se lee redondo de
 * un vistazo. El filo de la tinta de luz sube más (+0.75) y va centrado: el gas brilla por
 * el medio, esté donde esté el sol.
 */
private const val LOMO_DEL_TUBO = 0.62
private const val FILO_DEL_TUBO = 0.30
private const val FLANCO_DEL_TUBO = -0.28
private const val VIVO_DEL_TUBO = 0.5
private const val VIVO_DE_LA_LUZ = 0.75

/**
 * En cuántos escalones se cuantiza el tono. Ocho: los suficientes para que una cinta
 * girando por una hoja curva se lea como volumen, y los bastante pocos para que se lean
 * **como escalones** — que es el sombreado de pluma, no un degradado de aerógrafo.
 */
private const val ESCALONES_DE_TONO = 8

/**
 * El reparto de los escalones alrededor del color elegido, de la sombra al brillo.
 *
 * La sombra es la de las caras del listón del lienzo, y el brillo va **simétrico a
 * propósito**: así el escalón del medio es el color elegido tal cual, y moviendo la vista
 * —que pinta un solo escalón, el del medio— el trazo va de su color a secas y al soltar
 * aparecen los tonos alrededor sin ningún salto de claridad.
 */
private const val TONO_DE_SOMBRA = -0.42
private const val TONO_DE_BRILLO = 0.42

/**
 * Lo más fino que se pinta, en `dp` — **el clamp de pintado, y solo de pintado**.
 *
 * El mismo pelo que el mínimo del trazo del lienzo, y por lo mismo: un trazo mide lo que
 * mide en el mundo y de lejos se ve fino, que está bien; lo que no está bien es que se vea
 * menos de un píxel, porque entonces no se ve. Jamás retroalimenta el mundo: es del
 * fotograma, y al fotograma siguiente se vuelve a medir desde el mundo.
 */
private const val MINIMO_EN_PANTALLA_DE_PLUMA = 1.1

/** En cuántos tramos se parte el casquete de una punta. Ocho no se distingue de una curva. */
private const val PASOS_DE_LA_TAPA_DE_PLUMA = 8
