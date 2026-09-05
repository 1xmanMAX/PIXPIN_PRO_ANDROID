package com.forge.pixpin.croquis3d

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.pixpin.motor.Pt
import com.forge.pixpin.motor.parseColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * **El mando: una bola con todo lo que se le puede hacer a lo elegido, a la vista.**
 *
 * ## Por qué una bola y no tiradores en la figura
 *
 * En un dibujo plano, lo elegido se maneja por sus esquinas: se agarra el tirador y se
 * estira. En el espacio eso no funciona, y no por gusto: los tiradores **están donde está
 * la figura**, así que se ven pequeños cuando está lejos, se solapan cuando la vista está
 * escorzada, desaparecen cuando queda detrás de una hoja y hay que perseguirlos por la
 * pantalla cada vez que se gira. Encima, arrastrar el propio dibujo con el dedo tapa justo
 * lo que uno intenta colocar.
 *
 * El mando está siempre en el mismo sitio y siempre del mismo tamaño.
 *
 * ## Cuatro cosas, cuatro sitios, y ninguna escondida
 *
 * Antes había modos: se elegía «manejar» o «escalar», y dentro de manejar un eje, y según
 * el modo el mismo arrastre hacía una cosa u otra. Eso es un mando que hay que aprender.
 * Ahora cada cosa **tiene su sitio y se ve**:
 *
 * - **Las tres flechas**, en estrella dentro del disco, mueven por la `x`, la `y` y la `z`.
 *   Se agarra una y se tira: lo elegido se va por ese eje y por ninguno más. En estrella y
 *   no apuntando a donde de verdad va cada eje, porque así están siempre las tres a mano
 *   —proyectadas de verdad se solaparían en cuanto la vista se pusiera de frente—; el color
 *   dice cuál es cuál, que es el de siempre: `x` roja, `y` verde y `z` azul.
 * - **El hueco entre las flechas** mueve libre, por el plano de la hoja: es lo que uno hace
 *   el noventa por ciento del tiempo, así que es lo más grande y no hay que apuntar.
 * - **El tirador del borde** gira. Solo se puede llevar **por el borde**, en redondo, que
 *   es exactamente el gesto de girar algo: no hay forma de equivocarse y salirse.
 * - **La cajita de fuera** redimensiona. Se tira de ella hacia fuera y crece, se mete hacia
 *   dentro y encoge; está separada del disco a propósito, para que no se pise con el
 *   tirador de girar ni con las flechas.
 *
 * ## Lo que se mueve el dedo es lo que se mueve el dibujo
 *
 * **Nada aquí va por velocidad.** Lo estuvo, y era un error de los que solo se ven usando:
 * un mando por velocidad no se para cuando el dedo se para, sino cuando el dedo vuelve al
 * centro, así que empujar un poco para agrandar un poco dejaba la figura creciendo sola sin
 * parar. Ahora es directo: el dibujo se mueve los mismos píxeles que el dedo, y todo el
 * arrastre entra en **un solo deshacer**.
 */
@Composable
fun Croquis3DMando(controlador: Croquis3DControlador, modifier: Modifier = Modifier) {
    // Dónde se ha dejado el tirador de girar, en radianes. Se queda donde se suelta: es la
    // marca de cuánto se lleva girado en este rato.
    var anguloDelTirador by remember { mutableStateOf(ARRANQUE_DEL_TIRADOR) }
    var agarrado by remember { mutableStateOf<Agarre?>(null) }

    // **Las flechas apuntan a donde de verdad va cada eje.**
    //
    // Estaban clavadas en estrella de tres puntas, y esa es la queja de fondo: se tiraba de
    // la flecha de la `x` hacia arriba y la figura se iba hacia el lado, porque la `x` en la
    // pantalla iba hacia el lado. Con la flecha puesta donde de verdad va el eje, **la
    // figura acompaña al dedo**: se tira por donde apunta y se va por donde apunta.
    //
    // Y se acortan las que apuntan hacia el que mira, como se acorta cualquier cosa que
    // señala de frente: de un vistazo se ve cuál está de punta y no da nada arrastrar por
    // ella. Con un mínimo, eso sí, o dejaría de poder cogerse justo cuando hace falta.
    val flechas = flechasDe(controlador)

    val cuerpo = MaterialTheme.colorScheme.surfaceVariant
    val tinta = MaterialTheme.colorScheme.onSurfaceVariant
    val realce = MaterialTheme.colorScheme.primary

    Box(modifier.size(LADO.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(LADO.dp)
                .pointerInput(Unit) {
                    val lado = size.width.toFloat()
                    val centro = centroDelDisco(lado)
                    val radio = lado * RADIO
                    var ultimoAngulo = 0.0
                    var ultimoRadio = 0f
                    var ultimoDesvio = Offset.Zero
                    var porDondeVaLaFlecha = 0.0
                    detectDragGestures(
                        onDragStart = { donde ->
                            // Las de ahora mismo, no las de cuando se compuso la pantalla.
                            val deAhora = flechasDe(controlador)
                            agarrado = queSeHaAgarrado(donde, lado, deAhora)
                            porDondeVaLaFlecha =
                                deAhora.firstOrNull { it.agarre == agarrado }?.angulo ?: 0.0
                            if (agarrado != null) controlador.empezarAManejar()
                            ultimoAngulo = anguloDe(donde - centro)
                            ultimoRadio = (donde - centro).getDistance()
                            ultimoDesvio = donde - centro
                        },
                        onDrag = { cambio, arrastre ->
                            cambio.consume()
                            when (val cual = agarrado) {
                                null -> Unit

                                Agarre.GIRAR -> {
                                    val angulo = anguloDe(cambio.position - centro)
                                    // La diferencia se normaliza a media vuelta: cruzando
                                    // por arriba el ángulo salta de `+π` a `−π`, y sin esto
                                    // la figura pegaría una vuelta entera en un fotograma.
                                    var d = angulo - ultimoAngulo
                                    while (d > Math.PI) d -= 2 * Math.PI
                                    while (d < -Math.PI) d += 2 * Math.PI
                                    ultimoAngulo = angulo
                                    anguloDelTirador = angulo
                                    controlador.girarLaSeleccion(d, EjeDelMundo.LIBRE)
                                }

                                Agarre.ESCALAR -> {
                                    // Por la razón entre lo lejos que está el dedo del
                                    // centro antes y ahora: tirar al doble de distancia
                                    // dobla el tamaño, meterlo a la mitad lo parte por la
                                    // mitad. Es lo que hace una esquina de verdad.
                                    val ahora = (cambio.position - centro).getDistance()
                                        .coerceAtLeast(radio * 0.25f)
                                    if (ultimoRadio > 1e-3f) {
                                        controlador.escalarLaSeleccion(
                                            (ahora / ultimoRadio).toDouble()
                                        )
                                    }
                                    ultimoRadio = ahora
                                }

                                Agarre.DEFORMAR -> {
                                    // **Cada lado por su cuenta.** Se mira cuánto se ha
                                    // apartado el dedo del centro a lo ancho y a lo alto, y
                                    // cada uno estira lo suyo: tirando en diagonal hacia
                                    // arriba, sube más de lo que ensancha, que es lo que se
                                    // está viendo hacer con la mano.
                                    val ahora = cambio.position - centro
                                    val antes = ultimoDesvio
                                    val minimo = radio * 0.2f
                                    fun cuanto(a: Float, b: Float): Double {
                                        if (kotlin.math.abs(b) < minimo) return 1.0
                                        return (a / b).toDouble().coerceIn(0.5, 2.0)
                                    }
                                    controlador.deformarLaSeleccion(
                                        cuanto(kotlin.math.abs(ahora.x), kotlin.math.abs(antes.x)),
                                        cuanto(kotlin.math.abs(ahora.y), kotlin.math.abs(antes.y))
                                    )
                                    ultimoDesvio = ahora
                                }

                                Agarre.LIBRE -> controlador.moverLaSeleccion(
                                    arrastre.x.toDouble(), arrastre.y.toDouble()
                                )

                                else -> controlador.moverLaSeleccionPorElEje(
                                    cual.eje!!,
                                    // Lo que se ha corrido **por la flecha**: arrastrando en
                                    // cruz a ella no se pide nada, que es lo que uno espera
                                    // de una flecha. Y por el ángulo que tenía al cogerla,
                                    // no por el de ahora: el eje se mueve con la figura, y
                                    // persiguiéndolo el arrastre daría tirones.
                                    avanceDeLaFlecha(
                                        Pt(arrastre.x.toDouble(), arrastre.y.toDouble()),
                                        porDondeVaLaFlecha
                                    )
                                )
                            }
                        },
                        onDragEnd = { agarrado = null },
                        onDragCancel = { agarrado = null }
                    )
                }
        ) {
            val lado = size.minDimension
            val centro = centroDelDisco(lado)
            val radio = lado * RADIO

            // El disco.
            drawCircle(cuerpo.copy(alpha = 0.92f), radius = radio, center = centro)
            drawCircle(
                tinta.copy(alpha = if (agarrado == Agarre.LIBRE) 0.55f else 0.28f),
                radius = radio, center = centro, style = Stroke(width = 1.5f)
            )

            // Las tres flechas, en estrella. **Y con una agarrada, las otras dos se
            // quitan de en medio**: mientras se mueve por un eje, las demás no se pueden
            // usar y lo único que hacen es tapar la que sí —que es justo el sitio de la
            // pantalla que uno está mirando—.
            val moviendo = agarrado in Agarre.LAS_FLECHAS
            for (flecha in flechas) {
                if (moviendo && agarrado != flecha.agarre) continue
                flecha(centro, radio, flecha, agarrado == flecha.agarre)
            }

            // **El redondito del medio: ese es el joystick.** Mover libre siempre fue tocar
            // el hueco entre las flechas, pero un hueco no se ve — había que saberlo. Con la
            // bolita puesta se lee de un vistazo: joystick en el centro para ir a donde sea,
            // puntas en el borde para ir por su eje. El mando del plano lleva lo mismo.
            if (!moviendo) {
                val bolita = radio * BOLITA_DEL_MEDIO
                drawCircle(Color.White.copy(alpha = 0.85f), radius = bolita, center = centro)
                drawCircle(
                    Color.Black.copy(alpha = 0.45f),
                    radius = bolita, center = centro, style = Stroke(width = 1.5f)
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
                    if (agarrado == Agarre.GIRAR) realce else realce.copy(alpha = 0.75f),
                    topLeft = Offset(enElBorde.x - ancho / 2, enElBorde.y - alto / 2),
                    size = Size(ancho, alto),
                    cornerRadius = CornerRadius(alto / 2)
                )
            }

            // Y la cajita de redimensionar, fuera y suelta.
            val caja = centroDeLaCaja(lado)
            val ladoDeLaCaja = lado * CAJA
            drawRoundRect(
                if (agarrado == Agarre.ESCALAR) realce else cuerpo.copy(alpha = 0.95f),
                topLeft = Offset(caja.x - ladoDeLaCaja / 2, caja.y - ladoDeLaCaja / 2),
                size = Size(ladoDeLaCaja, ladoDeLaCaja),
                cornerRadius = CornerRadius(ladoDeLaCaja * 0.25f)
            )
            drawRoundRect(
                realce.copy(alpha = 0.8f),
                topLeft = Offset(caja.x - ladoDeLaCaja / 2, caja.y - ladoDeLaCaja / 2),
                size = Size(ladoDeLaCaja, ladoDeLaCaja),
                cornerRadius = CornerRadius(ladoDeLaCaja * 0.25f),
                style = Stroke(width = 1.6f)
            )
            // Y la de estirar, en la esquina de enfrente: dos flechas en cruz, porque lo
            // que hace es tirar de lo ancho y de lo alto por separado.
            val otra = centroDeLaOtraCaja(lado)
            drawRoundRect(
                if (agarrado == Agarre.DEFORMAR) realce else cuerpo.copy(alpha = 0.95f),
                topLeft = Offset(otra.x - ladoDeLaCaja / 2, otra.y - ladoDeLaCaja / 2),
                size = Size(ladoDeLaCaja, ladoDeLaCaja),
                cornerRadius = CornerRadius(ladoDeLaCaja * 0.25f)
            )
            drawRoundRect(
                realce.copy(alpha = 0.8f),
                topLeft = Offset(otra.x - ladoDeLaCaja / 2, otra.y - ladoDeLaCaja / 2),
                size = Size(ladoDeLaCaja, ladoDeLaCaja),
                cornerRadius = CornerRadius(ladoDeLaCaja * 0.25f),
                style = Stroke(width = 1.6f)
            )
            val brazo = ladoDeLaCaja * 0.28f
            val tintaDeLaOtra = if (agarrado == Agarre.DEFORMAR) Color.White else realce
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
                if (agarrado == Agarre.ESCALAR) Color.White else realce,
                Offset(caja.x - punta, caja.y + punta),
                Offset(caja.x + punta, caja.y - punta),
                strokeWidth = 2f
            )
        }

        // Cuántos hay elegidos, y copiarlos. Van en las dos esquinas que no usa nadie: el
        // tirador vive arriba a la izquierda del disco y la cajita arriba a la derecha.
        Text(
            "${controlador.seleccion.size}",
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
                .clickable { controlador.copiarLaSeleccion() },
            contentAlignment = Alignment.Center
        ) {
            Text("⧉", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Una flecha del disco: una raya del centro afuera y su punta de flecha. */
private fun DrawScope.flecha(centro: Offset, radio: Float, cual: Flecha, agarrada: Boolean) {
    val color = Color(parseColor(cual.agarre.eje!!.color, 255))
    val u = porElAngulo(cual.angulo, 1f)
    val perpendicular = Offset(-u.y, u.x)

    // **La raya del eje, solo con el dedo puesto**, y para los dos lados: un eje tiene
    // dirección, y lo que se está moviendo puede ir hacia atrás. Las flechas enteras decían
    // por dónde *se puede* ir; esta dice por dónde *se está* yendo, que es lo que hace falta
    // ver cuando ya estás moviendo y miras la pieza y no el mando. El mando del lienzo plano
    // lleva lo mismo.
    if (agarrada) {
        val lejos = radio * EJE_QUE_SE_VE
        drawLine(
            color.copy(alpha = 0.9f),
            centro - u * lejos,
            centro + u * lejos,
            strokeWidth = GORDO_AGARRADA,
            // **A trazos.** Continua se confunde con lo dibujado —una recta gorda cruzando el
            // croquis parece una pieza más— y lo que dice no es una pieza: es por dónde se
            // está moviendo esto ahora mismo. A trazos se lee como lo que es, una guía.
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(TRAMO_DE_LA_GUIA, HUECO_DE_LA_GUIA)
            )
        )
    }

    // **El triangulito, con su centro en el borde del disco.** Estaba apoyado ahí y creciendo
    // hacia fuera, así que el mando se leía como una estrella de rayos y no como un disco con
    // sus salidas, y las tres flechas no quedaban a la misma distancia del centro. El
    // centroide de un triángulo está a un tercio de su alto desde la base, así que se retrasa
    // eso. Lo pidió el usuario (3-sep-2026).
    val largo = radio * PUNTA_DE_LA_FLECHA
    val apoyo = centro + u * (radio * cual.parte - largo / 3f)
    val camino = Path()
    val morro = apoyo + u * largo
    camino.moveTo(morro.x, morro.y)
    camino.lineTo(apoyo.x + perpendicular.x * largo * 0.55f, apoyo.y + perpendicular.y * largo * 0.55f)
    camino.lineTo(apoyo.x - perpendicular.x * largo * 0.55f, apoyo.y - perpendicular.y * largo * 0.55f)
    camino.close()
    drawPath(camino, color.copy(alpha = if (agarrada) 1f else 0.85f))
}

/**
 * Qué se ha agarrado al empezar a arrastrar.
 *
 * El orden importa: primero lo pequeño y de fuera —la cajita y el tirador—, y el disco al
 * final. Mirándolo al revés, el disco se quedaría con todo lo que le cayera encima.
 */
private fun queSeHaAgarrado(donde: Offset, lado: Float, flechas: List<Flecha>): Agarre? {
    val centro = centroDelDisco(lado)
    val radio = lado * RADIO
    val alcance = lado * ALCANCE

    if ((donde - centroDeLaCaja(lado)).getDistance() <= alcance) return Agarre.ESCALAR
    if ((donde - centroDeLaOtraCaja(lado)).getDistance() <= alcance) return Agarre.DEFORMAR

    val fuera = (donde - centro).getDistance()
    // El tirador se coge por todo el borde, no solo donde está pintado: es un aro estrecho,
    // y pedir puntería a un dedo sobre un aro de cuatro milímetros es pedirle que falle.
    if (fuera > radio * ARO_DE_GIRAR && fuera <= radio + alcance) return Agarre.GIRAR

    if (fuera <= radio) {
        // Y dentro, la flecha más cercana **a su raya**, no la del sector: entre dos flechas
        // hay sitio de sobra para mover libre, que es lo que se hace casi siempre.
        val cerca = flechas.minByOrNull { deLaFlecha(donde, centro, radio, it) }
        if (cerca != null && deLaFlecha(donde, centro, radio, cerca) <= alcance) return cerca.agarre
        return Agarre.LIBRE
    }
    return null
}

/** A qué distancia pasa un toque de la raya de una flecha. */
private fun deLaFlecha(donde: Offset, centro: Offset, radio: Float, cual: Flecha): Float {
    // Hasta el morro de verdad: la flecha se agarra donde se ve, no donde se veía.
    val hasta = centro + porElAngulo(
        cual.angulo, radio * cual.parte + radio * PUNTA_DE_LA_FLECHA * 2f / 3f
    )
    return distanciaASegmento(
        Pt(donde.x.toDouble(), donde.y.toDouble()),
        Pt(centro.x.toDouble(), centro.y.toDouble()),
        Pt(hasta.x.toDouble(), hasta.y.toDouble())
    ).toFloat()
}

/**
 * Cuánto se ha pedido por una flecha: lo arrastrado, proyectado sobre ella.
 *
 * Arrastrando en la dirección de la flecha se pide todo lo arrastrado; en cruz, nada. Es la
 * cuenta entera de una flecha, y está aquí fuera para poder comprobarla.
 */
internal fun avanceDeLaFlecha(arrastre: Pt, angulo: Double): Double =
    arrastre.x * cos(angulo) + arrastre.y * sin(angulo)

/** Una flecha ya colocada: por dónde apunta en la pantalla y cuánto se ve de ella. */
internal class Flecha(val agarre: Agarre, val angulo: Double, val parte: Float)

/**
 * Las flechas que hay que enseñar, puestas **por donde de verdad va cada eje**.
 *
 * Se sacan proyectando el eje de verdad, así que valen con la vista girada, escorzada o con
 * la lente abierta. Lo que mide el eje en la pantalla, comparado con el que más mide, dice
 * además **cuánto se ve de él**: un eje que apunta al que mira sale corto, como sale corto
 * un palo que señala de frente.
 *
 * ## Y el que apunta a la cara no se pinta
 *
 * Puesto de frente a un plano —de alzado, de perfil, en planta— **uno de los tres ejes va
 * derecho al ojo**. De ese eje no se ve nada: su flecha se queda en un punto en el centro y
 * apuntando a donde caiga el redondeo, así que lo que uno ve es una flecha señalando en una
 * dirección que no significa nada, y tirar de ella mueve la figura hacia dentro o hacia
 * fuera de la pantalla sin que se note en ningún sitio. En esa postura solo hay dos
 * direcciones que existan, y solo se enseñan esas dos: **las del plano que se está
 * mirando**. Para irse por la tercera hay que girar la vista, que es exactamente lo que uno
 * haría sobre el papel.
 *
 * Sin nada elegido no hay figura a la que mirarle el centro, así que no se puede saber por
 * dónde va ningún eje: entonces se cae a la estrella de tres puntas, que es la postura de
 * reposo.
 */
internal fun flechasDe(controlador: Croquis3DControlador): List<Flecha> {
    val vistos = Agarre.LAS_FLECHAS.map { it to controlador.ejeEnPantalla(it.eje!!) }
    val masLargo = vistos.mapNotNull { (_, v) -> v?.let { hypot(it.x, it.y) } }.maxOrNull()
    if (masLargo == null || masLargo < 1e-9) {
        return Agarre.LAS_FLECHAS.map { Flecha(it, it.eje!!.angulo, 1f) }
    }
    return vistos.mapNotNull { (agarre, v) ->
        if (v == null) return@mapNotNull null
        val parte = (hypot(v.x, v.y) / masLargo).toFloat()
        if (parte < DE_PUNTA) return@mapNotNull null
        Flecha(agarre, atan2(v.y, v.x), parte.coerceIn(MINIMO_DE_LA_FLECHA, 1f))
    }
}

/** Lo que se puede agarrar del mando. Las tres flechas llevan su eje. */
internal enum class Agarre(val eje: EjeDelMundo? = null) {
    LIBRE, GIRAR, ESCALAR, DEFORMAR,
    FLECHA_X(EjeDelMundo.X), FLECHA_Y(EjeDelMundo.Y), FLECHA_Z(EjeDelMundo.Z);

    companion object {
        val LAS_FLECHAS = listOf(FLECHA_X, FLECHA_Y, FLECHA_Z)
    }
}

/** Lo más corta que se deja una flecha escorzada: por debajo no se podría coger. */
private const val MINIMO_DE_LA_FLECHA = 0.45f

/**
 * Por debajo de esta parte de lo que mide el eje más largo, una flecha está **de punta** y
 * no se pinta.
 *
 * Un cuarto son unos catorce grados de escorzo: hasta ahí la flecha sigue diciendo algo, y
 * a partir de ahí lo que dice es ruido. Ver [flechasDe].
 */
private const val DE_PUNTA = 0.25f

/**
 * Dónde se pone cada eje **cuando no se sabe por dónde va**, en radianes de pantalla.
 *
 * En estrella de tres puntas, que es como se ve un triedro desde su esquina: la `z` hacia
 * arriba —que es donde está el arriba en todas partes— y la `x` y la `y` abriéndose abajo.
 * Es la postura de reposo, la de cuando no hay nada elegido: en cuanto lo hay, cada flecha
 * se va a donde de verdad va su eje. Ver [flechasDe].
 */
private val EjeDelMundo.angulo: Double
    get() = when (this) {
        EjeDelMundo.Z -> -Math.PI / 2
        EjeDelMundo.X -> Math.PI / 6
        EjeDelMundo.Y -> Math.PI - Math.PI / 6
        EjeDelMundo.LIBRE -> 0.0
    }

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
    centroDelDisco(lado) + porElAngulo(ANGULO_DE_LA_OTRA_CAJA, lado * (RADIO + SEPARACION_DE_LA_CAJA))

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
 * Gordas y con la punta grande a propósito: son el mando de mover por un eje, se cogen con
 * el dedo y se usan mirando la figura y no el mando. Una flecha fina obliga a mirar dónde se
 * pone el dedo, que es tiempo mirando el sitio equivocado de la pantalla.
 */
private const val PUNTA_DE_LA_FLECHA = 0.30f
/** Hasta dónde llega la raya del eje que se está usando, en radios del disco. */
private const val EJE_QUE_SE_VE = 2.4f

/** Lo gorda que va la bolita del medio, en radios del disco. */
private const val BOLITA_DEL_MEDIO = 0.22f

private const val GORDO_AGARRADA = 7f

/** Lo que mide cada trazo de la guía del eje y el hueco entre dos. Ver [flecha]. */
private const val TRAMO_DE_LA_GUIA = 16f
private const val HUECO_DE_LA_GUIA = 12f

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

/** Desde qué parte del radio para fuera se coge el aro de girar. */
private const val ARO_DE_GIRAR = 0.88f

/** Lo lejos que se puede tocar de un tirador y seguir cogiéndolo, en tanto por uno. */
private const val ALCANCE = 0.115f
