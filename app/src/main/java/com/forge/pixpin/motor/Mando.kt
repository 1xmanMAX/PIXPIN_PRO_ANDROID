package com.forge.pixpin.motor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * **El mando: una bola con todo lo que se le puede hacer a lo elegido, a la vista.**
 *
 * Es el mismo mando que el del croquis en el espacio, con dos flechas en vez de tres. Y es
 * el mismo a propósito: quien usa las dos pantallas no tiene que aprender dos mandos, y lo
 * que se aprende girando un volumen sirve igual para colocar una planta.
 *
 * ## Por qué una bola y no los tiradores de la caja
 *
 * Los tiradores de siempre —las ocho esquinas del recuadro— **están donde está la figura**,
 * y eso es justo lo que falla con el dedo: se ven diminutos cuando la figura es pequeña, se
 * amontonan cuando hay varias juntas, se van fuera de la pantalla cuando se trabaja de
 * cerca, y arrastrar la propia figura tapa con la mano lo que uno intenta colocar. En un
 * plano de ingeniería, donde se coloca una pieza mirando dónde encaja con la de al lado,
 * eso es tener la mano encima de lo único que importa.
 *
 * El mando está siempre en el mismo sitio y siempre del mismo tamaño, así que la figura
 * nunca queda debajo de la mano.
 *
 * ## Cinco cosas, cinco sitios, y ninguna escondida
 *
 * - **Las dos flechas** mueven por la `x` y por la `y`, y por ninguna otra: es lo que hace
 *   falta para alinear. Rojo la `x` y verde la `y`, que es el convenio de siempre.
 * - **El hueco entre las flechas** mueve libre: es lo que uno hace el noventa por ciento
 *   del tiempo, así que es lo más grande y no hay que apuntar.
 * - **El tirador del borde** gira. Solo se lleva por el borde, en redondo, que es
 *   exactamente el gesto de girar algo.
 * - **La cajita de fuera** agranda y encoge sin cambiar la proporción.
 * - **La de la esquina de enfrente** estira, cada lado por su cuenta.
 *
 * ## Lo que se mueve el dedo es lo que se mueve el dibujo
 *
 * **Nada aquí va por velocidad.** Un mando por velocidad no se para cuando el dedo se para,
 * sino cuando el dedo vuelve al centro, así que empujar un poco para mover un poco deja la
 * figura andando sola. Aquí es directo: la figura se mueve los mismos píxeles que el dedo, y
 * todo el arrastre entra en **un solo deshacer**. Ver [DrawController.empezarAManejar].
 */
@Composable
fun Mando(
    controller: DrawController,
    /** El aumento de ahora, para que el dedo mueva lo que se ve que mueve. */
    zoom: Double,
    /** Algo ha cambiado: que se repinte y se guarde. */
    alCambiar: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Dónde se ha dejado el tirador de girar. Se queda donde se suelta: es la marca de
    // cuánto se lleva girado en este rato.
    var anguloDelTirador by remember { mutableStateOf(ARRANQUE_DEL_TIRADOR) }
    var agarrado by remember { mutableStateOf<AgarreDelMando?>(null) }

    val cuerpo = MaterialTheme.colorScheme.surfaceVariant
    val tinta = MaterialTheme.colorScheme.onSurfaceVariant
    val realce = MaterialTheme.colorScheme.primary

    Box(modifier.size(LADO.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(LADO.dp)
                .pointerInput(zoom) {
                    val lado = size.width.toFloat()
                    val centro = centroDelDisco(lado)
                    val radio = lado * RADIO
                    var ultimoAngulo = 0.0
                    var ultimoRadio = 0f
                    var ultimoDesvio = Offset.Zero
                    detectDragGestures(
                        onDragStart = { donde ->
                            agarrado = queSeHaAgarrado(donde, lado)
                            if (agarrado != null) controller.empezarAManejar()
                            ultimoAngulo = anguloDe(donde - centro)
                            ultimoRadio = (donde - centro).getDistance()
                            ultimoDesvio = donde - centro
                        },
                        onDrag = { cambio, arrastre ->
                            cambio.consume()
                            when (agarrado) {
                                null -> return@detectDragGestures

                                AgarreDelMando.GIRAR -> {
                                    val angulo = anguloDe(cambio.position - centro)
                                    // Cruzando por arriba el ángulo salta de `+π` a `−π`, y
                                    // sin normalizar la figura pegaría una vuelta entera en
                                    // un fotograma.
                                    var d = angulo - ultimoAngulo
                                    while (d > Math.PI) d -= 2 * Math.PI
                                    while (d < -Math.PI) d += 2 * Math.PI
                                    ultimoAngulo = angulo
                                    anguloDelTirador = angulo
                                    controller.girarLaSeleccion(d)
                                }

                                AgarreDelMando.ESCALAR -> {
                                    // Por la razón entre lo lejos que está el dedo del centro
                                    // antes y ahora: tirar al doble de distancia dobla el
                                    // tamaño. Es lo que hace una esquina de verdad.
                                    val ahora = (cambio.position - centro).getDistance()
                                        .coerceAtLeast(radio * 0.25f)
                                    if (ultimoRadio > 1e-3f) {
                                        controller.escalarLaSeleccion((ahora / ultimoRadio).toDouble())
                                    }
                                    ultimoRadio = ahora
                                }

                                AgarreDelMando.DEFORMAR -> {
                                    // **Cada lado por su cuenta.** Se mira cuánto se ha
                                    // apartado el dedo del centro a lo ancho y a lo alto, y
                                    // cada uno estira lo suyo: tirando en diagonal hacia
                                    // arriba sube más de lo que ensancha, que es lo que se
                                    // está viendo hacer con la mano.
                                    val ahora = cambio.position - centro
                                    val antes = ultimoDesvio
                                    val minimo = radio * 0.2f
                                    fun cuanto(a: Float, b: Float): Double {
                                        if (kotlin.math.abs(b) < minimo) return 1.0
                                        return (a / b).toDouble().coerceIn(0.5, 2.0)
                                    }
                                    controller.deformarLaSeleccion(
                                        cuanto(kotlin.math.abs(ahora.x), kotlin.math.abs(antes.x)),
                                        cuanto(kotlin.math.abs(ahora.y), kotlin.math.abs(antes.y))
                                    )
                                    ultimoDesvio = ahora
                                }

                                AgarreDelMando.LIBRE -> controller.moverLaSeleccion(
                                    arrastre.x.toDouble(), arrastre.y.toDouble(), zoom
                                )

                                AgarreDelMando.FLECHA_X -> controller.moverLaSeleccionPorElEje(
                                    enHorizontal = true,
                                    cuanto = arrastre.x.toDouble(),
                                    zoom = zoom
                                )

                                AgarreDelMando.FLECHA_Y -> controller.moverLaSeleccionPorElEje(
                                    enHorizontal = false,
                                    cuanto = arrastre.y.toDouble(),
                                    zoom = zoom
                                )
                            }
                            alCambiar()
                        },
                        onDragEnd = {
                            agarrado = null
                            controller.terminarDeManejar()
                            alCambiar()
                        },
                        onDragCancel = {
                            agarrado = null
                            controller.terminarDeManejar()
                            alCambiar()
                        }
                    )
                }
        ) {
            val lado = size.minDimension
            val centro = centroDelDisco(lado)
            val radio = lado * RADIO

            drawCircle(cuerpo.copy(alpha = 0.92f), radius = radio, center = centro)
            drawCircle(
                tinta.copy(alpha = if (agarrado == AgarreDelMando.LIBRE) 0.55f else 0.28f),
                radius = radio, center = centro, style = Stroke(width = 1.5f)
            )

            // Las dos flechas. **Y con una agarrada, la otra se quita de en medio**:
            // mientras se mueve por un eje la otra no se puede usar, y lo único que hace es
            // tapar la que sí — que es justo el sitio que uno está mirando.
            val moviendo = agarrado in LAS_FLECHAS
            for (flecha in LAS_FLECHAS) {
                if (moviendo && agarrado != flecha) continue
                flechaDelMando(centro, radio, flecha, agarrado == flecha)
            }

            // **El redondito del medio: ese es el joystick.**
            //
            // Mover libre siempre fue tocar el hueco entre las flechas, pero un hueco no se
            // ve — había que saberlo. Con la bolita puesta, lo que hace el mando se lee de un
            // vistazo: un joystick en el centro para ir a donde sea, y dos puntas en el borde
            // para ir por su eje. Se apaga mientras se arrastra por un eje, que entonces no
            // es lo que manda.
            if (!moviendo) {
                val bolita = radio * BOLITA_DEL_MEDIO
                drawCircle(tinta.copy(alpha = 0.85f), radius = bolita, center = centro)
                drawCircle(
                    cuerpo.copy(alpha = 0.9f),
                    radius = bolita * 0.45f, center = centro
                )
            }

            // El tirador de girar, montado en el borde y tumbado como el borde.
            val enElBorde = centro + porElAngulo(anguloDelTirador, radio)
            val ancho = lado * TIRADOR_LARGO
            val alto = lado * TIRADOR_ANCHO
            rotate(
                degrees = Math.toDegrees(anguloDelTirador).toFloat() + 90f,
                pivot = enElBorde
            ) {
                drawRoundRect(
                    if (agarrado == AgarreDelMando.GIRAR) realce else realce.copy(alpha = 0.75f),
                    topLeft = Offset(enElBorde.x - ancho / 2, enElBorde.y - alto / 2),
                    size = Size(ancho, alto),
                    cornerRadius = CornerRadius(alto / 2)
                )
            }

            // La cajita de redimensionar, fuera y suelta.
            val caja = centroDeLaCaja(lado)
            val ladoDeLaCaja = lado * CAJA
            fun cajita(donde: Offset, suya: AgarreDelMando) {
                drawRoundRect(
                    if (agarrado == suya) realce else cuerpo.copy(alpha = 0.95f),
                    topLeft = Offset(donde.x - ladoDeLaCaja / 2, donde.y - ladoDeLaCaja / 2),
                    size = Size(ladoDeLaCaja, ladoDeLaCaja),
                    cornerRadius = CornerRadius(ladoDeLaCaja * 0.25f)
                )
                drawRoundRect(
                    realce.copy(alpha = 0.8f),
                    topLeft = Offset(donde.x - ladoDeLaCaja / 2, donde.y - ladoDeLaCaja / 2),
                    size = Size(ladoDeLaCaja, ladoDeLaCaja),
                    cornerRadius = CornerRadius(ladoDeLaCaja * 0.25f),
                    style = Stroke(width = 1.6f)
                )
            }
            cajita(caja, AgarreDelMando.ESCALAR)

            // Y la de estirar, en la esquina de enfrente: dos flechas en cruz, porque lo que
            // hace es tirar de lo ancho y de lo alto por separado.
            val otra = centroDeLaOtraCaja(lado)
            cajita(otra, AgarreDelMando.DEFORMAR)
            val brazo = ladoDeLaCaja * 0.28f
            val tintaDeLaOtra =
                if (agarrado == AgarreDelMando.DEFORMAR) Color.White else realce
            drawLine(
                tintaDeLaOtra, Offset(otra.x - brazo, otra.y), Offset(otra.x + brazo, otra.y),
                strokeWidth = 2f
            )
            drawLine(
                tintaDeLaOtra, Offset(otra.x, otra.y - brazo), Offset(otra.x, otra.y + brazo),
                strokeWidth = 2f
            )

            // La diagonal de dentro dice para dónde tirar.
            val punta = ladoDeLaCaja * 0.26f
            drawLine(
                if (agarrado == AgarreDelMando.ESCALAR) Color.White else realce,
                Offset(caja.x - punta, caja.y + punta),
                Offset(caja.x + punta, caja.y - punta),
                strokeWidth = 2f
            )
        }

        // Cuántos hay elegidos, y copiarlos. En las dos esquinas que no usa nadie: el
        // tirador vive arriba a la izquierda del disco y las cajitas arriba.
        Text(
            "${controller.selectedIds.size}",
            color = tinta,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .size(BOTON.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                .clickable { controller.copiarLaSeleccion(); alCambiar() },
            contentAlignment = Alignment.Center
        ) {
            Text("⧉", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Un eje del disco: **un triangulito en el borde, y su raya solo mientras se usa**.
 *
 * Eran flechas enteras —una raya del centro afuera con su punta— y eso es mucho dibujo para
 * decir «por aquí se mueve»: dos flechas cruzadas tapan el disco entero, que es justo por
 * donde se agarra el movimiento libre. Ahora en reposo solo asoma la punta, pegada al borde:
 * dice hacia dónde sin ocupar el medio.
 *
 * Y **mientras se arrastra por un eje sale su raya**, de punta a punta y pasada del disco.
 * Las flechas decían por dónde *se puede* ir; la raya dice por dónde *se está* yendo, que es
 * lo que hace falta ver cuando ya estás moviendo algo y estás mirando la figura, no el mando.
 * La otra se quita de en medio: ver quien llama a esto.
 */
private fun DrawScope.flechaDelMando(
    centro: Offset,
    radio: Float,
    cual: AgarreDelMando,
    agarrada: Boolean
) {
    val color = cual.color
    val u = porElAngulo(cual.angulo, 1f)
    val perpendicular = Offset(-u.y, u.x)

    // La raya del eje, solo con el dedo puesto. Va para los dos lados: un eje no tiene
    // sentido, tiene dirección, y lo que se está moviendo puede ir hacia atrás.
    if (agarrada) {
        val lejos = radio * EJE_QUE_SE_VE
        drawLine(
            color.copy(alpha = 0.9f),
            centro - u * lejos,
            centro + u * lejos,
            strokeWidth = GORDO_AGARRADA,
            // A trazos: lo que dice no es una pieza, es por dónde se está moviendo esto
            // ahora mismo. Continua se confunde con lo dibujado. Igual que en el croquis
            // del espacio. Ver `Croquis3DMando`.
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(TRAMO_DE_LA_GUIA, HUECO_DE_LA_GUIA)
            )
        )
    }

    // El triangulito, apoyado en el borde y mirando afuera.
    // **El triangulito, con su centro en el borde del disco**: el centroide de un triángulo
    // está a un tercio de su alto desde la base. Ver `Croquis3DMando.flecha`.
    val largo = radio * PUNTA_DE_LA_FLECHA
    val apoyo = centro + u * (radio - largo / 3f)
    val camino = Path()
    val morro = apoyo + u * largo
    camino.moveTo(morro.x, morro.y)
    camino.lineTo(
        apoyo.x + perpendicular.x * largo * 0.55f, apoyo.y + perpendicular.y * largo * 0.55f
    )
    camino.lineTo(
        apoyo.x - perpendicular.x * largo * 0.55f, apoyo.y - perpendicular.y * largo * 0.55f
    )
    camino.close()
    drawPath(camino, color.copy(alpha = if (agarrada) 1f else 0.85f))
}

/**
 * Qué se ha agarrado al empezar a arrastrar.
 *
 * El orden importa: primero lo pequeño y de fuera —las cajitas y el tirador—, y el disco al
 * final. Mirándolo al revés, el disco se quedaría con todo lo que le cayera encima.
 */
internal fun queSeHaAgarrado(donde: Offset, lado: Float): AgarreDelMando? {
    val centro = centroDelDisco(lado)
    val radio = lado * RADIO
    val alcance = lado * ALCANCE

    if ((donde - centroDeLaCaja(lado)).getDistance() <= alcance) return AgarreDelMando.ESCALAR
    if ((donde - centroDeLaOtraCaja(lado)).getDistance() <= alcance) {
        return AgarreDelMando.DEFORMAR
    }

    val fuera = (donde - centro).getDistance()
    // El tirador se coge por todo el borde, no solo donde está pintado: es un aro estrecho, y
    // pedir puntería a un dedo sobre un aro de cuatro milímetros es pedirle que falle.
    if (fuera > radio * ARO_DE_GIRAR && fuera <= radio + alcance) return AgarreDelMando.GIRAR

    if (fuera <= radio) {
        // Dentro, la flecha más cercana **a su raya**, no la de su sector: entre las dos hay
        // sitio de sobra para mover libre, que es lo que se hace casi siempre.
        val cerca = LAS_FLECHAS.minByOrNull { deLaFlecha(donde, centro, radio, it) }
        if (cerca != null && deLaFlecha(donde, centro, radio, cerca) <= alcance) return cerca
        return AgarreDelMando.LIBRE
    }
    return null
}

/** A qué distancia pasa un toque de la raya de una flecha. */
private fun deLaFlecha(
    donde: Offset,
    centro: Offset,
    radio: Float,
    cual: AgarreDelMando
): Float {
    // Hasta el morro de verdad: se agarra donde se ve.
    val hasta = centro + porElAngulo(cual.angulo, radio + radio * PUNTA_DE_LA_FLECHA * 2f / 3f)
    return distanceToSegment(
        Pt(donde.x.toDouble(), donde.y.toDouble()),
        Pt(centro.x.toDouble(), centro.y.toDouble()),
        Pt(hasta.x.toDouble(), hasta.y.toDouble())
    ).toFloat()
}

/** Lo que se puede agarrar del mando. */
internal enum class AgarreDelMando {
    LIBRE, GIRAR, ESCALAR, DEFORMAR, FLECHA_X, FLECHA_Y;

    /** Por dónde apunta, en radianes de pantalla: la `x` al lado y la `y` hacia arriba. */
    val angulo: Double
        get() = when (this) {
            FLECHA_X -> 0.0
            FLECHA_Y -> -Math.PI / 2
            else -> 0.0
        }

    /** El convenio de siempre: `x` roja y `y` verde. */
    val color: Color
        get() = when (this) {
            FLECHA_X -> Color(0xFFE03131)
            FLECHA_Y -> Color(0xFF2F9E44)
            else -> Color.Gray
        }
}

private val LAS_FLECHAS = listOf(AgarreDelMando.FLECHA_X, AgarreDelMando.FLECHA_Y)

private fun centroDelDisco(lado: Float) = Offset(lado * CENTRO_X, lado * CENTRO_Y)

private fun centroDeLaCaja(lado: Float): Offset =
    centroDelDisco(lado) + porElAngulo(ANGULO_DE_LA_CAJA, lado * (RADIO + SEPARACION_DE_LA_CAJA))

/**
 * La otra cajita, la de estirar, **en la esquina de enfrente**.
 *
 * Enfrente a propósito: son dos gestos parecidos —los dos cambian el tamaño— y puestos
 * juntos se cogería uno por otro a cada rato. En esquinas opuestas, la mano sabe cuál está
 * cogiendo antes de mirar.
 */
private fun centroDeLaOtraCaja(lado: Float): Offset =
    centroDelDisco(lado) +
        porElAngulo(ANGULO_DE_LA_OTRA_CAJA, lado * (RADIO + SEPARACION_DE_LA_CAJA))

private fun porElAngulo(angulo: Double, largo: Float) =
    Offset((cos(angulo) * largo).toFloat(), (sin(angulo) * largo).toFloat())

private fun anguloDe(v: Offset): Double = atan2(v.y.toDouble(), v.x.toDouble())

/** El lado del mando entero y el del botón de copiar, en `dp`. */
private const val LADO = 176
private const val BOTON = 32


/** Dónde cae el disco dentro del cuadro del mando y lo gordo que es, en tanto por uno. */
private const val CENTRO_X = 0.44f
private const val CENTRO_Y = 0.56f
private const val RADIO = 0.32f

/**
 * La flecha: hasta dónde llega su raya, lo larga que es su punta y lo gorda que va.
 *
 * Gordas y con la punta grande a propósito: se cogen con el dedo y se usan **mirando la
 * figura y no el mando**. Una flecha fina obliga a mirar dónde se pone el dedo, que es
 * tiempo mirando el sitio equivocado de la pantalla.
 */
private const val PUNTA_DE_LA_FLECHA = 0.30f
private const val GORDO_AGARRADA = 7f

/** Hasta dónde llega la raya del eje que se está usando, en radios del disco. */
private const val EJE_QUE_SE_VE = 2.4f

/** Lo que mide cada trazo de la guía del eje y el hueco entre dos. Ver [flecha]. */
private const val TRAMO_DE_LA_GUIA = 16f
private const val HUECO_DE_LA_GUIA = 12f

/** Lo gorda que va la bolita del medio, en radios del disco. */
private const val BOLITA_DEL_MEDIO = 0.22f

/** El tirador de girar: lo largo y lo ancho que es, en tanto por uno del lado. */
private const val TIRADOR_LARGO = 0.115f
private const val TIRADOR_ANCHO = 0.055f

/** Dónde arranca el tirador: en la diagonal de arriba a la izquierda. */
private val ARRANQUE_DEL_TIRADOR = -Math.PI * 3 / 4

/** La cajita de redimensionar: lo grande que es, por dónde cae y cuánto se separa. */
private const val CAJA = 0.115f
private const val SEPARACION_DE_LA_CAJA = 0.155f
private val ANGULO_DE_LA_CAJA = -Math.PI / 4
private val ANGULO_DE_LA_OTRA_CAJA = -3 * Math.PI / 4

/**
 * **Lo que le sobra de caja al mando por la derecha y por abajo.**
 *
 * El cuadro del mando mide [LADO] pero lo que se ve no está centrado en él: el disco cae en
 * el `(0,44 · 0,56)` y los dos botones cuelgan hacia arriba, así que por la derecha y por
 * abajo queda caja vacía. Puesto en la esquina de la pantalla, lo que tocaba la esquina era
 * **el aire**, y el mando parecía quedarse corto y torcido hacia dentro — se leía como que
 * seguía a la izquierda.
 *
 * Sale de las mismas constantes que colocan las piezas, así que mover una pieza no puede
 * dejar esto desajustado. Quien lo ponga en una esquina se lo resta.
 */
internal val LO_QUE_SOBRA_A_LA_DERECHA: Dp =
    (LADO - maxOf(
        (CENTRO_X + RADIO) * LADO,
        (CENTRO_X * LADO + kotlin.math.cos(ANGULO_DE_LA_CAJA).toFloat() *
            (RADIO + SEPARACION_DE_LA_CAJA) * LADO) + BOTON / 2f
    )).dp

internal val LO_QUE_SOBRA_ABAJO: Dp = (LADO - (CENTRO_Y + RADIO) * LADO).dp

/** Desde qué parte del radio para fuera se coge el aro de girar. */
private const val ARO_DE_GIRAR = 0.88f

/** Lo lejos que se puede tocar de un tirador y seguir cogiéndolo, en tanto por uno. */
private const val ALCANCE = 0.115f
